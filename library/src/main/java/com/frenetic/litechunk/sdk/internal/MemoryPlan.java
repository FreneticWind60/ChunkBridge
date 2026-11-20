package com.frenetic.litechunk.sdk.internal;

/**
 * Picks engine settings from the heap the host process was given.
 * <p>
 * Chunker's peak memory is driven by two numbers: how many worker threads convert chunks at once, and how large the
 * LevelDB write buffer is. The app this SDK was extracted from settled on two threads and a 128 MB per-thread
 * budget after measuring on real phones; a host app that did not ask for {@code android:largeHeap} gets a much
 * smaller heap than the app does, so the thread count is derived from the actual limit instead of fixed.
 */
public final class MemoryPlan {

    private static final int MB_PER_THREAD = 128;
    private static final int MAX_THREADS = 2;
    /** Below this the engine's lean profile (smaller write buffer, fewer cached blocks) is worth its slower pace. */
    private static final int LEAN_BELOW_MB = 256;

    private MemoryPlan() {
    }

    /** Heap limit of the current process, in MB. */
    public static int heapMb() {
        return (int) (Runtime.getRuntime().maxMemory() / (1024L * 1024L));
    }

    /** Worker threads to give the engine: 1 on a tight heap, 2 otherwise. */
    public static int threads() {
        return threads(heapMb());
    }

    static int threads(int heapMb) {
        int threads = heapMb / MB_PER_THREAD;
        if (threads < 1) {
            threads = 1;
        }
        return Math.min(threads, MAX_THREADS);
    }

    /** Whether the lean profile should be forced regardless of what the request asked for. */
    public static boolean leanForced() {
        return heapMb() < LEAN_BELOW_MB;
    }

    /** Whether a throwable is an out-of-memory, including one wrapped by a CompletionException. */
    public static boolean isOutOfMemory(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof OutOfMemoryError) {
                return true;
            }
            String message = cause.getMessage();
            if (message != null && message.contains("OutOfMemory")) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }
}
