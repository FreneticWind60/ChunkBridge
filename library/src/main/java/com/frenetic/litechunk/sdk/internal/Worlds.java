package com.frenetic.litechunk.sdk.internal;

import java.io.File;

/**
 * Tells a world folder from a plain folder, and finds the world inside an extracted archive.
 */
public final class Worlds {

    /** How many wrapper folders an archive may nest the real world in. */
    private static final int MAX_DEPTH = 3;

    private Worlds() {
    }

    /**
     * Whether a folder is the root of a world.
     * <p>
     * Java saves carry {@code level.dat}; Bedrock saves carry a {@code db/} folder of LevelDB files. Checking both
     * means a Bedrock world whose {@code level.dat} is missing still converts.
     */
    public static boolean isWorld(File directory) {
        if (directory == null || !directory.isDirectory()) {
            return false;
        }
        if (new File(directory, "level.dat").isFile()) {
            return true;
        }
        File db = new File(directory, "db");
        return db.isDirectory();
    }

    /**
     * Finds the folder that holds the world, unwrapping archives that contain a single wrapper directory (which is
     * what most world exporters produce).
     *
     * @param directory the extracted archive or a picked folder.
     * @return the world root, or null when there is none within {@link #MAX_DEPTH} levels.
     */
    public static File locate(File directory) {
        return locate(directory, MAX_DEPTH);
    }

    private static File locate(File directory, int depth) {
        if (directory == null || !directory.isDirectory()) {
            return null;
        }
        if (isWorld(directory)) {
            return directory;
        }
        if (depth <= 0) {
            return null;
        }
        File[] children = directory.listFiles(File::isDirectory);
        if (children == null) {
            return null;
        }
        for (File child : children) {
            if (child.getName().startsWith("__MACOSX")) {
                continue;
            }
            if (isWorld(child)) {
                return child;
            }
        }
        for (File child : children) {
            if (child.getName().startsWith("__MACOSX")) {
                continue;
            }
            File nested = locate(child, depth - 1);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }
}
