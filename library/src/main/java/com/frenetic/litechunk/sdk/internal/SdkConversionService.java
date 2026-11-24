package com.frenetic.litechunk.sdk.internal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcelable;
import android.os.PowerManager;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Log;

import com.frenetic.litechunk.api.ChunkerEngine;
import com.frenetic.litechunk.api.ConversionOptions;
import com.frenetic.litechunk.api.Detection;
import com.frenetic.litechunk.api.FormatGroup;
import com.frenetic.litechunk.api.FormatVersion;
import com.frenetic.litechunk.api.LevelInfo;
import com.frenetic.litechunk.api.ProgressListener;
import com.frenetic.litechunk.sdk.ChunkBridgeException;
import com.frenetic.litechunk.sdk.ErrorCode;
import com.frenetic.litechunk.sdk.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Does the actual work in a separate {@code :chunkbridge} process.
 *
 * <p>Why a separate process: the engine is a dex the SDK loads itself, and its Chunker + LevelDB heap is large.
 * Keeping it out of the host process means the host's memory profile, crash reports and R8 output stay untouched,
 * a crash inside the engine does not take the UI with it, and cancelling a conversion can simply kill this process
 * - the engine has no abort API, and a chunk pass cannot be interrupted cleanly from outside.
 *
 * <p>The service accepts one job at a time. A second request while one runs is answered with
 * {@link ErrorCode#BUSY} rather than queued: the caller decides whether to wait.
 *
 * <p>Protocol: see {@link Protocol}. Everything is primitive extras plus a {@link ResultReceiver}; there is no AIDL
 * to keep in sync.
 */
public final class SdkConversionService extends Service {

    private static final String TAG = "ChunkBridge";
    private static final String CHANNEL_ID = "chunkbridge_conversion";
    private static final int NOTIFICATION_ID = 0x4348;
    /** Engine progress callbacks are throttled, both to the caller and to the notification. */
    private static final long PROGRESS_INTERVAL_MS = 200L;
    private static final long NOTIFICATION_INTERVAL_MS = 1000L;
    /** Time for the CANCELLED result to reach the caller's binder before this process dies. */
    private static final long CANCEL_KILL_DELAY_MS = 300L;
    /**
     * How long the service stays alive after its last job. A caller that converts and then immediately detects (or
     * the other way round) sends its next request within milliseconds; stopping instantly raced with that request and
     * the freshly queued job was dropped by {@code onDestroy}.
     */
    private static final long IDLE_STOP_DELAY_MS = 2000L;
    private static final long WAKE_LOCK_TIMEOUT_MS = 2L * 60L * 60L * 1000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    /** Posted with a delay so a request that follows a job closely can cancel the shutdown. */
    private final Runnable idleStop = this::stopWhenIdle;
    private ExecutorService executor;
    private NotificationManager notifications;
    private PowerManager.WakeLock wakeLock;

    /**
     * A conversion holds the engine for minutes and can be cancelled by killing the process; a detection or a
     * {@code level.dat} read/write only needs a few seconds. Keeping the flags apart means "detect, then convert
     * from the callback" works, while a second conversion is still refused instead of queued behind the first.
     */
    private volatile boolean converting;
    private volatile boolean detecting;
    private volatile boolean editing;
    private volatile boolean cancelled;
    private volatile String currentRequestId;
    private volatile ResultReceiver currentReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        executor = newWorker();
        notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createChannel();
        Log.i(TAG, "Service created");
        // Leftovers can only come from a crash or a killed process: no job can be running before this point.
        JobWorkspace.sweep(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            return START_NOT_STICKY;
        }
        Job job = Job.from(intent);
        if (job == null) {
            Log.w(TAG, "Ignoring a job without a request id");
            return START_NOT_STICKY;
        }
        if (Protocol.ACTION_CANCEL.equals(intent.getAction())) {
            cancel(job.requestId);
            return START_NOT_STICKY;
        }
        boolean conversion = Protocol.ACTION_CONVERT.equals(intent.getAction());
        boolean level = Protocol.ACTION_LEVEL_INSPECT.equals(intent.getAction())
                || Protocol.ACTION_LEVEL_APPLY.equals(intent.getAction());
        // Any incoming request keeps the service alive: the idle stop is only a linger, not a verdict.
        main.removeCallbacks(idleStop);
        if (conversion) {
            goForeground(job);
            if (converting) {
                // Do not stop the service: the running job owns it.
                sendFailure(job, new ChunkBridgeException(ErrorCode.BUSY,
                        "Another conversion is already running in this app."));
                return START_NOT_STICKY;
            }
            converting = true;
        } else if (level) {
            if (converting || detecting || editing) {
                sendFailure(job, new ChunkBridgeException(ErrorCode.BUSY,
                        "The conversion engine is still busy. Read the world details again once it is idle."));
                return START_NOT_STICKY;
            }
            editing = true;
        } else {
            if (converting || detecting || editing) {
                sendFailure(job, new ChunkBridgeException(ErrorCode.BUSY,
                        "The conversion engine is still busy. Detect again once it is idle."));
                return START_NOT_STICKY;
            }
            detecting = true;
        }
        currentRequestId = job.requestId;
        currentReceiver = job.receiver;
        cancelled = false;
        Log.i(TAG, (conversion ? "Starting conversion "
                : level ? "Starting level " + (job.levelApply ? "write " : "read ") : "Starting detection ")
                + job.requestId);
        try {
            executor.execute(() -> run(job));
        } catch (RejectedExecutionException stopping) {
            // The request arrived while the service was being torn down by its idle stop. Start a fresh worker
            // instead of dropping the job on the floor.
            Log.i(TAG, "The old worker was shutting down; starting a new one");
            executor = newWorker();
            executor.execute(() -> run(job));
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Service destroyed");
        main.removeCallbacks(idleStop);
        // A job may have been queued but never started (the system can destroy the service right after a start
        // request). Its caller would otherwise wait forever for a callback that cannot come.
        ResultReceiver receiver = currentReceiver;
        String requestId = currentRequestId;
        if ((converting || detecting || editing) && receiver != null && requestId != null) {
            Bundle data = bundleWithRequestId(requestId);
            data.putString(Protocol.KEY_ERROR_CODE, ErrorCode.CANCELLED.name());
            data.putString(Protocol.KEY_ERROR_MESSAGE,
                    "The conversion service stopped before the job finished.");
            send(receiver, Protocol.RESULT_FAILED, data);
        }
        releaseWakeLock();
        if (executor != null) {
            executor.shutdownNow();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------------------------------------------------------- job

    private void run(Job job) {
        long startedAt = SystemClock.elapsedRealtime();
        JobWorkspace workspace = null;
        Log.i(TAG, "Job started " + job.requestId);
        try {
            if (cancelled) {
                sendCancelled(job);
                return;
            }
            ChunkerEngine engine = EngineProvider.obtain(this);
            engine.setConcurrency(MemoryPlan.threads());
            engine.setLeanMode(job.lean || MemoryPlan.leanForced());

            if (job.level) {
                // level.dat work touches a folder the caller already unpacked; it needs no staging area and no
                // wake lock, and must not leave a job directory behind.
                level(job, engine);
                return;
            }

            workspace = JobWorkspace.create(this, job.requestId);
            sendStarted(job);

            File source = materialize(job, workspace);
            File worldRoot = resolveWorld(source, workspace);

            if (job.detection) {
                detection(job, engine, worldRoot);
                return;
            }

            String targetVersion = targetVersion(engine, job.targetFormatId);
            acquireWakeLock();
            File outputDirectory = workspace.out;
            if (!outputDirectory.isDirectory() && !outputDirectory.mkdirs()) {
                throw new ChunkBridgeException(ErrorCode.INSUFFICIENT_STORAGE,
                        "Could not create a folder for the converted world.");
            }
            engine.convert(worldRoot, outputDirectory, job.targetFormatId,
                    new ConversionOptions(job.entities), progressListener(job));
            sendProgress(job, 0.92D, "finish");
            long bytes = writeOutput(job, outputDirectory);
            deleteSourceDocument(job);
            // Free the slot before the callback runs: "convert, then convert again from onCompleted" is a
            // normal thing for a caller to do, and it must not be answered with BUSY.
            releaseConversion();
            sendCompleted(job, bytes, startedAt, targetVersion);
        } catch (Throwable failure) {
            if (cancelled) {
                sendCancelled(job);
            } else {
                releaseConversion();
                sendFailure(job, asException(failure));
            }
        } finally {
            releaseWakeLock();
            if (workspace != null) {
                workspace.delete();
            }
            if (job.detection) {
                detecting = false;
                currentRequestId = null;
                currentReceiver = null;
            }
            if (job.level) {
                editing = false;
                currentRequestId = null;
                currentReceiver = null;
            }
            Log.i(TAG, "Job finished " + job.requestId);
            requestIdleStop();
        }
    }

    /**
     * Stops the service once it has nothing left to do, but only from the main thread and only after a short linger:
     * a request that was already queued runs first, flips {@code converting} and keeps the service (and the workspace
     * of the running job) alive. Stopping straight from the worker thread raced with {@link #onStartCommand} and
     * interrupted fresh jobs.
     */
    private void requestIdleStop() {
        main.removeCallbacks(idleStop);
        main.postDelayed(idleStop, IDLE_STOP_DELAY_MS);
    }

    private void stopWhenIdle() {
        if (converting || detecting || editing) {
            return;
        }
        Log.i(TAG, "Idle, stopping the service");
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    /** One worker thread: the engine keeps global state, so two jobs must never overlap. */
    private static ExecutorService newWorker() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "chunkbridge-job");
            thread.setPriority(Thread.NORM_PRIORITY);
            return thread;
        });
    }

    /** Marks the engine as idle again, so the next request is accepted instead of answered with BUSY. */
    private void releaseConversion() {
        converting = false;
        currentRequestId = null;
        currentReceiver = null;
    }

    /** Turns the input path or Uri into a real file (an archive) or folder (a world). */
    private File materialize(Job job, JobWorkspace workspace) throws ChunkBridgeException {
        if (job.inputPath != null) {
            File file = new File(job.inputPath);
            if (!file.exists()) {
                throw new ChunkBridgeException(ErrorCode.INPUT_UNREADABLE,
                        "The input file does not exist.", job.inputPath);
            }
            sendProgress(job, 0.02D, "terrain");
            return file;
        }
        Uri uri = Uri.parse(job.inputUri);
        sendProgress(job, 0.01D, "terrain");
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new ChunkBridgeException(ErrorCode.INPUT_UNREADABLE,
                        "The input document could not be opened.", job.inputUri);
            }
            long bytes = JobWorkspace.copyToFile(in, workspace.source);
            if (bytes <= 0L) {
                throw new ChunkBridgeException(ErrorCode.INPUT_UNREADABLE,
                        "The input document is empty.", job.inputUri);
            }
            return workspace.source;
        } catch (ChunkBridgeException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new ChunkBridgeException(ErrorCode.INPUT_UNREADABLE,
                    "The input document could not be copied.", Extractor.describe(failure), failure);
        } catch (SecurityException failure) {
            throw new ChunkBridgeException(ErrorCode.INPUT_UNREADABLE,
                    "The app no longer has permission to read this document.", Extractor.describe(failure),
                    failure);
        }
    }

    /** Unpacks an archive, then finds the world folder inside it. */
    private File resolveWorld(File source, JobWorkspace workspace) throws ChunkBridgeException {
        File worldRoot;
        if (source.isDirectory()) {
            worldRoot = Worlds.locate(source);
            if (worldRoot == null) {
                throw new ChunkBridgeException(ErrorCode.WORLD_NOT_FOUND,
                        "That folder is not a Minecraft world.", source.getAbsolutePath());
            }
            return worldRoot;
        }
        Container container = Container.sniff(source);
        if (!container.isSupported()) {
            String name = source.getName();
            throw new ChunkBridgeException(ErrorCode.ARCHIVE_UNSUPPORTED,
                    Extractor.looksLikeArchive(name)
                            ? "This archive format cannot be read. Unpack it and pick the world folder instead."
                            : "That file is not a Minecraft world archive.",
                    name + " (" + container.name() + ")");
        }
        Extractor.extract(source, container, workspace.in);
        worldRoot = Worlds.locate(workspace.in);
        if (worldRoot == null) {
            throw new ChunkBridgeException(ErrorCode.WORLD_NOT_FOUND,
                    "The archive does not contain a Minecraft world.",
                    "no level.dat or db/ folder under " + workspace.in.getAbsolutePath());
        }
        return worldRoot;
    }

    private void detection(Job job, ChunkerEngine engine, File worldRoot) throws ChunkBridgeException {
        try {
            Detection detection = engine.detect(worldRoot);
            Bundle data = new Bundle();
            data.putString(Protocol.KEY_EDITION_ID, detection.formatId);
            data.putString(Protocol.KEY_EDITION_LABEL, detection.formatLabel);
            data.putString(Protocol.KEY_VERSION, detection.version);
            // Clear the flag before delivering: a callback that immediately starts a conversion must not see
            // the engine as still busy.
            detecting = false;
            send(job, Protocol.RESULT_DETECTED, data);
        } catch (Throwable failure) {
            throw new ChunkBridgeException(ErrorCode.WORLD_NOT_FOUND,
                    "The world could not be identified.", Extractor.describe(failure), failure);
        }
    }

    /** The version string of a target format id, used to fill {@code ConversionResult.targetVersion}. */
    private static String targetVersion(ChunkerEngine engine, String formatId) {
        try {
            for (FormatGroup group : engine.formats()) {
                for (FormatVersion version : group.versions) {
                    if (version.id.equals(formatId)) {
                        return version.version;
                    }
                }
            }
        } catch (Throwable failure) {
            Log.w(TAG, "Could not list engine formats", failure);
        }
        return null;
    }

    /** Packs the converted folder into the requested file or document. Returns the written size in bytes. */
    private long writeOutput(Job job, File outputDirectory) throws ChunkBridgeException {
        if (job.outputPath != null) {
            return writeOutputFile(job, outputDirectory);
        }
        return writeOutputDocument(job, outputDirectory);
    }

    private long writeOutputFile(Job job, File outputDirectory) throws ChunkBridgeException {
        File target = new File(job.outputPath);
        if (!target.isAbsolute() || target.getParentFile() == null) {
            // A relative path would resolve against the service process' working directory, not the caller's.
            throw new ChunkBridgeException(ErrorCode.OUTPUT_UNWRITABLE,
                    "The output path must be an absolute file path.", job.outputPath);
        }
        if (target.exists() && !job.overwrite) {
            throw new ChunkBridgeException(ErrorCode.OUTPUT_EXISTS,
                    "The output file already exists. Ask to overwrite it, or pick another name.",
                    target.getAbsolutePath());
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new ChunkBridgeException(ErrorCode.OUTPUT_UNWRITABLE,
                    "Could not create the output folder.", parent.getAbsolutePath());
        }
        // Pack into a sibling and rename at the end: an interrupted conversion must not leave a half written
        // archive at the name the caller asked for.
        File part = new File(parent, target.getName() + ".part");
        sendProgress(job, 0.95D, "finish");
        try (OutputStream out = new FileOutputStream(part)) {
            WorldPacker.pack(outputDirectory, out);
        } catch (IOException failure) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw new ChunkBridgeException(ErrorCode.OUTPUT_UNWRITABLE,
                    "The converted world could not be written.", Extractor.describe(failure), failure);
        }
        //noinspection ResultOfMethodCallIgnored
        target.delete();
        if (!part.renameTo(target)) {
            try {
                copyFile(part, target);
            } catch (IOException failure) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                throw new ChunkBridgeException(ErrorCode.OUTPUT_UNWRITABLE,
                        "The converted world could not be written.", Extractor.describe(failure), failure);
            }
            //noinspection ResultOfMethodCallIgnored
            part.delete();
        }
        return target.length();
    }

    private long writeOutputDocument(Job job, File outputDirectory) throws ChunkBridgeException {
        Uri uri = Uri.parse(job.outputUri);
        sendProgress(job, 0.95D, "finish");
        try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) {
                throw new ChunkBridgeException(ErrorCode.OUTPUT_UNWRITABLE,
                        "The output document could not be opened.", job.outputUri);
            }
            WorldPacker.pack(outputDirectory, out);
        } catch (ChunkBridgeException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new ChunkBridgeException(ErrorCode.OUTPUT_UNWRITABLE,
                    "The converted world could not be written to the chosen document.",
                    Extractor.describe(failure), failure);
        } catch (SecurityException failure) {
            throw new ChunkBridgeException(ErrorCode.OUTPUT_UNWRITABLE,
                    "The app no longer has permission to write this document.", Extractor.describe(failure),
                    failure);
        }
        return documentSize(uri);
    }

    /** Best effort size of a written document; 0 when the provider does not report one. */
    private long documentSize(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0 && !cursor.isNull(index)) {
                    return cursor.getLong(index);
                }
            }
        } catch (RuntimeException ignored) {
            // Providers are allowed to refuse; the size is informational only.
        }
        return 0L;
    }

    private static void copyFile(File source, File target) throws IOException {
        try (InputStream in = new java.io.FileInputStream(source);
             OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
    }

    /** Deletes the caller's source document, but only when the request explicitly asked for it. */
    private void deleteSourceDocument(Job job) {
        if (!job.deleteSource || job.inputUri == null) {
            return;
        }
        try {
            DocumentsContract.deleteDocument(getContentResolver(), Uri.parse(job.inputUri));
        } catch (Exception failure) {
            Log.w(TAG, "Could not delete the source document", failure);
        }
    }

    // ---------------------------------------------------------------- cancel

    private void cancel(String requestId) {
        if (!converting || requestId == null || !requestId.equals(currentRequestId)) {
            return;
        }
        cancelled = true;
        ResultReceiver receiver = currentReceiver;
        if (receiver != null) {
            send(receiver, Protocol.RESULT_CANCELLED, bundleWithRequestId(requestId));
        }
        // The engine converts chunk by chunk with no abort hook, so the cleanest stop is to end this process.
        // The caller already has its CANCELLED result; the delay just makes sure the binder call lands first.
        main.postDelayed(() -> {
            Log.i(TAG, "Killing the conversion process after a cancel request");
            android.os.Process.killProcess(android.os.Process.myPid());
        }, CANCEL_KILL_DELAY_MS);
    }

    // ---------------------------------------------------------------- progress

    private ProgressListener progressListener(Job job) {
        return new ProgressListener() {
            private long lastProgressAt;
            private long lastNotificationAt;

            @Override
            public void onProgress(double fraction, String stage) {
                long now = SystemClock.uptimeMillis();
                if (now - lastProgressAt < PROGRESS_INTERVAL_MS) {
                    return;
                }
                lastProgressAt = now;
                double mapped = 0.05D + Math.max(0D, Math.min(1D, fraction)) * 0.85D;
                sendProgress(job, mapped, stage);
                if (now - lastNotificationAt >= NOTIFICATION_INTERVAL_MS) {
                    lastNotificationAt = now;
                    updateNotification(job, (int) (mapped * 100D));
                }
            }
        };
    }

    private void sendProgress(Job job, double fraction, String stage) {
        Bundle data = new Bundle();
        data.putDouble(Protocol.KEY_FRACTION, fraction);
        data.putString(Protocol.KEY_STAGE, stage);
        send(job, Protocol.RESULT_PROGRESS, data);
    }

    // ---------------------------------------------------------------- results

    private void sendStarted(Job job) {
        send(job, Protocol.RESULT_STARTED, bundleWithRequestId(job.requestId));
    }

    private void sendCompleted(Job job, long bytes, long startedAt, String targetVersion) {
        Bundle data = bundleWithRequestId(job.requestId);
        data.putString(Protocol.KEY_TARGET_FORMAT, job.targetFormatId);
        data.putString(Protocol.KEY_TARGET_VERSION, targetVersion);
        if (job.outputPath != null) {
            data.putString(Protocol.KEY_OUTPUT_PATH, job.outputPath);
        } else {
            data.putString(Protocol.KEY_OUTPUT_URI, job.outputUri);
        }
        data.putLong(Protocol.KEY_OUTPUT_BYTES, bytes);
        data.putLong(Protocol.KEY_ELAPSED_MS, SystemClock.elapsedRealtime() - startedAt);
        send(job, Protocol.RESULT_COMPLETED, data);
    }

    private void sendFailure(Job job, ChunkBridgeException error) {
        Log.w(TAG, "Conversion failed: " + error.getCode() + " " + error.getMessage() + " "
                + (error.getDetail() == null ? "" : error.getDetail()));
        Bundle data = bundleWithRequestId(job.requestId);
        data.putString(Protocol.KEY_ERROR_CODE, error.getCode().name());
        data.putString(Protocol.KEY_ERROR_MESSAGE, error.getMessage());
        data.putString(Protocol.KEY_ERROR_DETAIL, error.getDetail());
        send(job, Protocol.RESULT_FAILED, data);
    }

    private void sendCancelled(Job job) {
        send(job, Protocol.RESULT_CANCELLED, bundleWithRequestId(job.requestId));
    }

    private static Bundle bundleWithRequestId(String requestId) {
        Bundle data = new Bundle();
        data.putString(Protocol.KEY_REQUEST_ID, requestId);
        return data;
    }

    private static void send(Job job, int resultCode, Bundle data) {
        send(job.receiver, resultCode, data);
    }

    private static void send(ResultReceiver receiver, int resultCode, Bundle data) {
        if (receiver == null) {
            return;
        }
        try {
            receiver.send(resultCode, data);
        } catch (RuntimeException failure) {
            // The caller's process may be gone; the job is still cleaned up by the finally block.
            Log.w(TAG, "Could not deliver result " + resultCode, failure);
        }
    }

    /** Maps an arbitrary failure onto the SDK's error codes. */
    private static ChunkBridgeException asException(Throwable failure) {
        if (failure instanceof ChunkBridgeException) {
            return (ChunkBridgeException) failure;
        }
        if (MemoryPlan.isOutOfMemory(failure)) {
            return new ChunkBridgeException(ErrorCode.OUT_OF_MEMORY,
                    "The device ran out of memory while converting this world.",
                    Extractor.describe(failure), failure);
        }
        if (failure instanceof SecurityException) {
            return new ChunkBridgeException(ErrorCode.OUTPUT_UNWRITABLE,
                    "A file or folder could not be accessed.", Extractor.describe(failure), failure);
        }
        if (isInterrupted(failure)) {
            // The only thing that interrupts the worker is this service shutting down, which is not something the
            // caller did wrong: report it as a cancellation instead of an internal error.
            return new ChunkBridgeException(ErrorCode.CANCELLED,
                    "The conversion was interrupted because the service stopped.", Extractor.describe(failure),
                    failure);
        }
        String message = failure.getMessage();
        if (message != null && (message.contains("ENOSPC") || message.contains("No space left"))) {
            return new ChunkBridgeException(ErrorCode.INSUFFICIENT_STORAGE,
                    "The device ran out of storage.", Extractor.describe(failure), failure);
        }
        return new ChunkBridgeException(ErrorCode.INTERNAL,
                "The conversion failed unexpectedly.", Extractor.describe(failure), failure);
    }

    /** True when the failure, or anything in its cause chain, is an interrupt. */
    private static boolean isInterrupted(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof InterruptedException || current instanceof java.io.InterruptedIOException) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- notification

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.chunkbridge_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.chunkbridge_notification_channel_description));
        channel.setShowBadge(false);
        notifications.createNotificationChannel(channel);
    }

    private void goForeground(Job job) {
        Notification notification = buildNotification(job, 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(Job job, int percent) {
        try {
            notifications.notify(NOTIFICATION_ID, buildNotification(job, percent));
        } catch (RuntimeException failure) {
            Log.w(TAG, "Could not update the notification", failure);
        }
    }

    private Notification buildNotification(Job job, int percent) {
        String title = job.title != null ? job.title : getString(R.string.chunkbridge_notification_title);
        String text = job.text != null ? job.text : getString(R.string.chunkbridge_notification_text);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.chunkbridge_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, Math.max(0, Math.min(100, percent)), percent <= 0);
        // The platform derives a content intent from the host app's launcher activity when none is set. Packages
        // without one (test hosts, launcher-less shells) make that lookup return null and the notification then
        // fails to build, so always hand it an explicit intent: open the host app when it has a launcher.
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            builder.setContentIntent(PendingIntent.getActivity(this, 0, launch,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        } else {
            builder.setContentIntent(PendingIntent.getService(this, 0, new Intent(this, SdkConversionService.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }
        Intent cancelIntent = new Intent(this, SdkConversionService.class)
                .setAction(Protocol.ACTION_CANCEL)
                .putExtra(Protocol.EXTRA_REQUEST_ID, job.requestId);
        PendingIntent cancel = PendingIntent.getService(this, job.requestId.hashCode(), cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        builder.addAction(new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                getString(R.string.chunkbridge_notification_cancel),
                cancel).build());
        return builder.build();
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            return;
        }
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ChunkBridge:convert");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (RuntimeException ignored) {
                // Already released by its timeout.
            }
        }
        wakeLock = null;
    }

    private void level(Job job, ChunkerEngine engine) throws ChunkBridgeException {
        File worldRoot = new File(job.levelPath);
        try {
            if (job.levelApply) {
                Map<String, String> changes = new LinkedHashMap<>();
                String[] ids = job.levelIds;
                String[] values = job.levelValues;
                int count = Math.min(ids == null ? 0 : ids.length, values == null ? 0 : values.length);
                for (int index = 0; index < count; index++) {
                    changes.put(ids[index], values[index]);
                }
                engine.applyLevelChanges(worldRoot, changes);
            }
            LevelInfo info = engine.inspectLevel(worldRoot);
            Bundle data = new Bundle();
            data.putBundle(Protocol.KEY_LEVEL_INFO, LevelBundle.write(info));
            // Clear the flag before delivering, like detection does: a screen may convert right from the callback.
            editing = false;
            send(job, Protocol.RESULT_LEVEL, data);
        } catch (Throwable failure) {
            throw new ChunkBridgeException(ErrorCode.WORLD_NOT_FOUND,
                    "The world details could not be read.", Extractor.describe(failure), failure);
        }
    }

    // ---------------------------------------------------------------- job extras

    /** Everything the service needs, read once from the start intent. */
    private static final class Job {

        String requestId;
        String inputPath;
        String inputUri;
        String outputPath;
        String outputUri;
        String targetFormatId;
        boolean entities;
        boolean lean;
        boolean overwrite;
        boolean deleteSource;
        String title;
        String text;
        boolean detection;
        boolean level;
        boolean levelApply;
        String levelPath;
        String[] levelIds;
        String[] levelValues;
        ResultReceiver receiver;

        static Job from(Intent intent) {
            String requestId = intent.getStringExtra(Protocol.EXTRA_REQUEST_ID);
            if (requestId == null) {
                return null;
            }
            Job job = new Job();
            job.requestId = requestId;
            job.inputPath = intent.getStringExtra(Protocol.EXTRA_INPUT_PATH);
            job.inputUri = intent.getStringExtra(Protocol.EXTRA_INPUT_URI);
            job.outputPath = intent.getStringExtra(Protocol.EXTRA_OUTPUT_PATH);
            job.outputUri = intent.getStringExtra(Protocol.EXTRA_OUTPUT_URI);
            job.targetFormatId = intent.getStringExtra(Protocol.EXTRA_TARGET_FORMAT);
            job.entities = intent.getBooleanExtra(Protocol.EXTRA_ENTITIES, true);
            job.lean = intent.getBooleanExtra(Protocol.EXTRA_LEAN, false);
            job.overwrite = intent.getBooleanExtra(Protocol.EXTRA_OVERWRITE, false);
            job.deleteSource = intent.getBooleanExtra(Protocol.EXTRA_DELETE_SOURCE, false);
            job.title = intent.getStringExtra(Protocol.EXTRA_NOTIFICATION_TITLE);
            job.text = intent.getStringExtra(Protocol.EXTRA_NOTIFICATION_TEXT);
            job.detection = Protocol.ACTION_DETECT.equals(intent.getAction());
            job.level = Protocol.ACTION_LEVEL_INSPECT.equals(intent.getAction())
                    || Protocol.ACTION_LEVEL_APPLY.equals(intent.getAction());
            job.levelApply = Protocol.ACTION_LEVEL_APPLY.equals(intent.getAction());
            job.levelPath = intent.getStringExtra(Protocol.EXTRA_LEVEL_PATH);
            job.levelIds = intent.getStringArrayExtra(Protocol.EXTRA_LEVEL_IDS);
            job.levelValues = intent.getStringArrayExtra(Protocol.EXTRA_LEVEL_VALUES);
            job.receiver = receiverOf(intent);
            return job;
        }

        @SuppressWarnings("deprecation")
        private static ResultReceiver receiverOf(Intent intent) {
            Parcelable parcelable = intent.getParcelableExtra(Protocol.EXTRA_RECEIVER);
            return parcelable instanceof ResultReceiver ? (ResultReceiver) parcelable : null;
        }
    }
}
