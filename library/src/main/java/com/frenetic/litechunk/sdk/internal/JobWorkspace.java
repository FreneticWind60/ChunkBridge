package com.frenetic.litechunk.sdk.internal;

import android.content.Context;

import com.frenetic.litechunk.sdk.ChunkBridgeException;
import com.frenetic.litechunk.sdk.ErrorCode;

import java.io.File;
import java.io.IOException;

/**
 * The per-job scratch directory.
 * <p>
 * One folder per request under {@code cacheDir/chunkbridge/jobs/}, holding the staged input archive, the unpacked
 * world and the converted output before it is packed. Everything lives in the app's cache because the engine needs
 * real files (LevelDB memory maps them) and because nothing here is worth keeping after the job ends.
 * <p>
 * The whole folder is deleted when the job finishes, whether it succeeded, failed or was cancelled. Job folders
 * found at service start are leftovers from a crash or a killed process, so they are deleted too.
 */
final class JobWorkspace {

    private static final String ROOT = "chunkbridge/jobs";

    private final File root;
    /** The input archive after it is copied out of a Uri; unused when the input is a File. */
    final File source;
    /** Where an archive is unpacked. */
    final File in;
    /** Where the engine writes the converted world. */
    final File out;

    private JobWorkspace(File root) {
        this.root = root;
        this.source = new File(root, "source");
        this.in = new File(root, "in");
        this.out = new File(root, "out");
    }

    /**
     * Creates (and clears) the job folder.
     *
     * @param context   a context whose cacheDir is used.
     * @param requestId the job id; used as the folder name.
     * @throws ChunkBridgeException when the folder cannot be created, which means storage is full or unavailable.
     */
    static JobWorkspace create(Context context, String requestId) throws ChunkBridgeException {
        File jobs = new File(context.getCacheDir(), ROOT);
        JobWorkspace workspace = new JobWorkspace(new File(jobs, sanitize(requestId)));
        if (workspace.root.isDirectory()) {
            deleteTree(workspace.root);
        }
        if (!workspace.root.mkdirs() && !workspace.root.isDirectory()) {
            throw new ChunkBridgeException(ErrorCode.INSUFFICIENT_STORAGE,
                    "Could not create a working folder for the conversion.", workspace.root.getAbsolutePath());
        }
        return workspace;
    }

    /** Deletes this job's folder, ignoring failures: it is cache, the OS may clean it anyway. */
    void delete() {
        deleteTree(root);
    }

    /**
     * Removes leftover job folders. Called once when the service starts, when no job can be running.
     *
     * @param context a context whose cacheDir is used.
     */
    static void sweep(Context context) {
        File jobs = new File(context.getCacheDir(), ROOT);
        File[] children = jobs.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            // The service is single threaded and has just started, so nothing in here can belong to a live job.
            deleteTree(child);
        }
    }

    private static String sanitize(String requestId) {
        StringBuilder safe = new StringBuilder(requestId.length());
        for (int i = 0; i < requestId.length(); i++) {
            char c = requestId.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_';
            safe.append(ok ? c : '_');
        }
        return safe.length() == 0 ? "job" : safe.toString();
    }

    /** Recursively deletes a file or folder; symlinks are not followed. */
    static void deleteTree(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    /**
     * Copies an input stream to a file.
     *
     * @return the number of bytes copied.
     */
    static long copyToFile(java.io.InputStream in, File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create " + parent.getName());
        }
        long total = 0L;
        byte[] buffer = new byte[64 * 1024];
        try (java.io.OutputStream out = new java.io.FileOutputStream(destination)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                total += read;
            }
        }
        return total;
    }
}
