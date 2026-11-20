package com.frenetic.litechunk.sdk;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.util.Log;

import com.frenetic.litechunk.sdk.internal.Protocol;
import com.frenetic.litechunk.sdk.internal.SdkConversionService;

import java.io.File;
import java.util.List;
import java.util.UUID;

/**
 * Entry point of the ChunkBridge SDK: converts Minecraft worlds between the Java and Bedrock editions inside the
 * host app.
 *
 * <p>Typical use:
 * <pre>{@code
 * ChunkBridge.initialize(context);
 *
 * String formatId = ChunkBridge.formats().get(0).getVersions().get(0).getFormatId();
 * ConversionRequest request = new ConversionRequest.Builder()
 *         .inputUri(inputUri)                        // a .zip / .mcworld / .rar / .7z, or a world folder
 *         .outputUri(outputUri)                      // or .outputFile(new File(...))
 *         .targetFormatId(formatId)
 *         .build();
 * ChunkBridge.convert(request, callback);
 * }</pre>
 *
 * <p>Everything runs in a {@code :chunkbridge} process of the host app, in a foreground service, so the caller's
 * process never loads the 1 MB conversion engine. Progress and results arrive on the main thread. There is no
 * permission to request for conversion itself, but:
 * <ul>
 *     <li>{@link #convert(ConversionRequest, ConversionCallback)} must be called while the app is in the
 *     foreground: Android 12+ refuses to start a foreground service from the background.</li>
 *     <li>The host app should declare {@code android:largeHeap="true"} on its {@code <application>} if it converts
 *     big worlds; the SDK adapts its thread count to whatever heap it is given, but a 256 MB heap limits it to one
 *     thread.</li>
 * </ul>
 *
 * <p>The SDK keeps no state on disk between calls: inputs are staged in the app cache and every job folder is
 * deleted when it finishes.
 */
public final class ChunkBridge {

    private static final String TAG = "ChunkBridge";
    private static final Object LOCK = new Object();

    private static Context appContext;
    private static FormatCatalog catalog;

    private ChunkBridge() {
    }

    /**
     * Binds the SDK to a context. Safe to call repeatedly; only the first call is used.
     *
     * @param context any context; the application context is retained.
     * @throws IllegalArgumentException when the context is null.
     */
    public static void initialize(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("A context is required to initialize ChunkBridge.");
        }
        synchronized (LOCK) {
            if (appContext == null) {
                appContext = context.getApplicationContext();
            }
        }
    }

    /** Whether {@link #initialize(Context)} has been called. */
    public static boolean isInitialized() {
        synchronized (LOCK) {
            return appContext != null;
        }
    }

    /** Version of the conversion engine inside this SDK, e.g. {@code 1.21.0}. */
    public static String engineVersion() {
        return catalog().engineVersion();
    }

    /**
     * Every edition and version this SDK can convert to, newest last.
     * <p>
     * Served from an asset compiled into the AAR, so it is cheap and safe to call on the main thread, and it works
     * before the engine dex is ever loaded.
     *
     * @throws IllegalStateException when the SDK was not initialized or the asset is missing from the build.
     */
    public static List<FormatGroup> formats() {
        return catalog().groups();
    }

    /**
     * The group with this edition id (for example {@code JAVA}).
     *
     * @return the group, or null when the id is unknown.
     */
    public static FormatGroup formatGroup(String editionId) {
        return catalog().group(editionId);
    }

    /**
     * The file extension an app should give a converted world of this format.
     *
     * @param formatId a {@link FormatVersion#getFormatId()}; unknown ids fall back to {@code zip}.
     * @return {@code mcworld} for Bedrock, {@code zip} for Java.
     */
    public static String defaultExtension(String formatId) {
        return catalog().defaultExtension(formatId);
    }

    /**
     * Starts a conversion.
     * <p>
     * Returns immediately. The callback is invoked on the main thread: {@code onStarted}, then any number of
     * {@code onProgress} calls, then exactly one of {@code onCompleted}, {@code onFailed} or {@code onCancelled}.
     * <p>
     * Call this while the host app is in the foreground. Android 12+ rejects a background foreground-service start;
     * the callback then receives {@link ErrorCode#FOREGROUND_NOT_ALLOWED}.
     *
     * @param request  what to convert and where to write it.
     * @param callback receives progress and the result.
     * @return a handle that can cancel the job.
     * @throws IllegalStateException    when the SDK was not initialized.
     * @throws IllegalArgumentException when the request or callback is null.
     */
    public static ConversionHandle convert(ConversionRequest request, ConversionCallback callback) {
        if (request == null) {
            throw new IllegalArgumentException("A request is required.");
        }
        if (callback == null) {
            throw new IllegalArgumentException("A callback is required.");
        }
        Context context = requireContext();
        String requestId = UUID.randomUUID().toString();

        Intent intent = new Intent(Protocol.ACTION_CONVERT);
        intent.setClass(context, SdkConversionService.class);
        intent.putExtra(Protocol.EXTRA_REQUEST_ID, requestId);
        if (request.getInputFile() != null) {
            intent.putExtra(Protocol.EXTRA_INPUT_PATH, request.getInputFile().getAbsolutePath());
        } else {
            intent.putExtra(Protocol.EXTRA_INPUT_URI, request.getInputUri().toString());
        }
        if (request.getOutputFile() != null) {
            intent.putExtra(Protocol.EXTRA_OUTPUT_PATH, request.getOutputFile().getAbsolutePath());
        } else {
            intent.putExtra(Protocol.EXTRA_OUTPUT_URI, request.getOutputUri().toString());
        }
        intent.putExtra(Protocol.EXTRA_TARGET_FORMAT, request.getTargetFormatId());
        intent.putExtra(Protocol.EXTRA_ENTITIES, request.isConvertEntities());
        intent.putExtra(Protocol.EXTRA_LEAN, request.isLeanMode());
        intent.putExtra(Protocol.EXTRA_OVERWRITE, request.isOverwriteExisting());
        intent.putExtra(Protocol.EXTRA_DELETE_SOURCE, request.isDeleteSourceArchiveAfterSuccess());
        if (request.getNotificationTitle() != null) {
            intent.putExtra(Protocol.EXTRA_NOTIFICATION_TITLE, request.getNotificationTitle());
        }
        if (request.getNotificationText() != null) {
            intent.putExtra(Protocol.EXTRA_NOTIFICATION_TEXT, request.getNotificationText());
        }
        intent.putExtra(Protocol.EXTRA_RECEIVER, new ResultReceiver(mainHandler()) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                deliver(callback, requestId, resultCode, resultData);
            }
        });

        Throwable failure = start(context, intent, true);
        if (failure != null) {
            final ChunkBridgeException error = asException(failure);
            mainHandler().post(() -> safeFail(callback, error));
        }
        return new JobHandle(requestId);
    }

    /**
     * Detects the edition and version of a world folder or archive.
     *
     * @param worldOrArchive a {@code level.dat} folder, a Bedrock world folder, or an archive.
     * @param callback       receives the result on the main thread.
     */
    public static void detect(File worldOrArchive, DetectionCallback callback) {
        if (worldOrArchive == null) {
            throw new IllegalArgumentException("A file is required.");
        }
        Intent intent = new Intent(Protocol.ACTION_DETECT);
        intent.putExtra(Protocol.EXTRA_INPUT_PATH, worldOrArchive.getAbsolutePath());
        detect(intent, callback);
    }

    /**
     * Detects the edition and version of an archive document.
     *
     * @param archiveUri a {@code .zip} / {@code .mcworld} / {@code .rar} / {@code .7z} document.
     * @param callback   receives the result on the main thread.
     */
    public static void detect(Uri archiveUri, DetectionCallback callback) {
        if (archiveUri == null) {
            throw new IllegalArgumentException("A Uri is required.");
        }
        Intent intent = new Intent(Protocol.ACTION_DETECT);
        intent.putExtra(Protocol.EXTRA_INPUT_URI, archiveUri.toString());
        detect(intent, callback);
    }

    private static void detect(Intent intent, DetectionCallback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("A callback is required.");
        }
        Context context = requireContext();
        String requestId = UUID.randomUUID().toString();
        intent.setClass(context, SdkConversionService.class);
        intent.putExtra(Protocol.EXTRA_REQUEST_ID, requestId);
        intent.putExtra(Protocol.EXTRA_RECEIVER, new ResultReceiver(mainHandler()) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                try {
                    if (resultCode == Protocol.RESULT_DETECTED) {
                        DetectionResult result = new DetectionResult(
                                resultData.getString(Protocol.KEY_EDITION_ID),
                                resultData.getString(Protocol.KEY_EDITION_LABEL),
                                resultData.getString(Protocol.KEY_VERSION));
                        callback.onFinished(result, null);
                    } else {
                        callback.onFinished(null, asException(resultData));
                    }
                } catch (RuntimeException broken) {
                    Log.e(TAG, "Detection callback threw", broken);
                }
            }
        });
        // Detection is quick and does not need the foreground: a plain start keeps the notification away.
        Throwable failure = start(context, intent, false);
        if (failure != null) {
            final ChunkBridgeException error = asException(failure);
            mainHandler().post(() -> {
                try {
                    callback.onFinished(null, error);
                } catch (RuntimeException broken) {
                    Log.e(TAG, "Detection callback threw", broken);
                }
            });
        }
    }

    /** Starts the service, returning the failure instead of throwing so callbacks stay the only error channel. */
    private static Throwable start(Context context, Intent intent, boolean foreground) {
        try {
            if (foreground) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            return null;
        } catch (RuntimeException failure) {
            return failure;
        }
    }

    private static void deliver(ConversionCallback callback, String requestId, int resultCode, Bundle data) {
        try {
            switch (resultCode) {
                case Protocol.RESULT_STARTED:
                    callback.onStarted(requestId);
                    break;
                case Protocol.RESULT_PROGRESS:
                    callback.onProgress(
                            data.getDouble(Protocol.KEY_FRACTION, 0D),
                            Stage.fromEngine(data.getString(Protocol.KEY_STAGE)));
                    break;
                case Protocol.RESULT_COMPLETED:
                    callback.onCompleted(resultOf(requestId, data));
                    break;
                case Protocol.RESULT_FAILED:
                    callback.onFailed(asException(data));
                    break;
                case Protocol.RESULT_CANCELLED:
                    callback.onCancelled(requestId);
                    break;
                default:
                    Log.w(TAG, "Ignoring unknown result code " + resultCode);
                    break;
            }
        } catch (RuntimeException broken) {
            Log.e(TAG, "Conversion callback threw", broken);
        }
    }

    private static void safeFail(ConversionCallback callback, ChunkBridgeException error) {
        try {
            callback.onFailed(error);
        } catch (RuntimeException broken) {
            Log.e(TAG, "Conversion callback threw", broken);
        }
    }

    private static ConversionResult resultOf(String requestId, Bundle data) {
        String outputPath = data.getString(Protocol.KEY_OUTPUT_PATH);
        String outputUri = data.getString(Protocol.KEY_OUTPUT_URI);
        return new ConversionResult(
                requestId,
                data.getString(Protocol.KEY_TARGET_FORMAT),
                data.getString(Protocol.KEY_TARGET_VERSION),
                outputPath == null ? null : new File(outputPath),
                outputUri == null ? null : Uri.parse(outputUri),
                data.getLong(Protocol.KEY_OUTPUT_BYTES, 0L),
                data.getLong(Protocol.KEY_ELAPSED_MS, 0L));
    }

    private static ChunkBridgeException asException(Bundle data) {
        if (data == null) {
            return new ChunkBridgeException(ErrorCode.INTERNAL, "The conversion service returned no details.");
        }
        return new ChunkBridgeException(
                errorCodeOf(data.getString(Protocol.KEY_ERROR_CODE)),
                data.getString(Protocol.KEY_ERROR_MESSAGE),
                data.getString(Protocol.KEY_ERROR_DETAIL));
    }

    private static ChunkBridgeException asException(Throwable failure) {
        if (failure instanceof ChunkBridgeException) {
            return (ChunkBridgeException) failure;
        }
        if (isForegroundStartDenied(failure)) {
            return new ChunkBridgeException(ErrorCode.FOREGROUND_NOT_ALLOWED,
                    "Android refused to start the conversion service. Call convert() while the app is in the "
                            + "foreground.",
                    failure.getClass().getName() + ": " + failure.getMessage(), failure);
        }
        return new ChunkBridgeException(ErrorCode.INTERNAL,
                "The conversion service could not be started.",
                failure.getClass().getName() + ": " + failure.getMessage(), failure);
    }

    /**
     * Android 12 throws {@code ForegroundServiceStartNotAllowedException}, which does not exist on older API
     * levels, so the class is matched by name rather than referenced.
     */
    private static boolean isForegroundStartDenied(Throwable failure) {
        String name = failure.getClass().getName();
        return name.endsWith("ForegroundServiceStartNotAllowedException")
                || (failure instanceof IllegalStateException);
    }

    private static ErrorCode errorCodeOf(String name) {
        if (name == null) {
            return ErrorCode.INTERNAL;
        }
        try {
            return ErrorCode.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            return ErrorCode.INTERNAL;
        }
    }

    private static Context requireContext() {
        synchronized (LOCK) {
            if (appContext == null) {
                throw new IllegalStateException("Call ChunkBridge.initialize(context) before using the SDK.");
            }
            return appContext;
        }
    }

    private static FormatCatalog catalog() {
        synchronized (LOCK) {
            if (catalog == null) {
                try {
                    catalog = FormatCatalog.read(requireContext());
                } catch (ChunkBridgeException failure) {
                    throw new IllegalStateException(failure.getMessage(), failure);
                }
            }
            return catalog;
        }
    }

    private static Handler mainHandler() {
        return new Handler(Looper.getMainLooper());
    }

    /** The handle returned by {@link #convert}; cancelling tells the service to stop and exit its process. */
    private static final class JobHandle implements ConversionHandle {

        private final String requestId;

        JobHandle(String requestId) {
            this.requestId = requestId;
        }

        @Override
        public String getRequestId() {
            return requestId;
        }

        @Override
        public void cancel() {
            Context context = requireContext();
            Intent intent = new Intent(Protocol.ACTION_CANCEL);
            intent.setClass(context, SdkConversionService.class);
            intent.putExtra(Protocol.EXTRA_REQUEST_ID, requestId);
            try {
                context.startService(intent);
            } catch (RuntimeException failure) {
                Log.w(TAG, "Could not deliver the cancel request", failure);
            }
        }
    }
}
