package com.frenetic.litechunk.sdk.internal;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Build;
import android.util.Log;

import com.frenetic.litechunk.api.ChunkerEngine;
import com.frenetic.litechunk.sdk.ChunkBridgeException;
import com.frenetic.litechunk.sdk.ErrorCode;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Loads the engine that ships inside the AAR.
 * <p>
 * The engine is not a compile dependency of the SDK: it is a {@code classes.dex} packed into {@code
 * assets/chunkbridge/engine.jar} and loaded with a {@link dalvik.system.DexClassLoader} whose parent is the host
 * app's class loader. That parent already contains the {@code :api} classes (they are packaged into the AAR), so
 * {@link ChunkerEngine} can be used for the cast, while the engine itself stays out of the host's dex and out of
 * R8's input.
 * <p>
 * Android 14 refuses to load code from a writable file, so the dex is copied into {@code codeCacheDir} and made
 * read only. The copy is named after a hash of the asset, which makes an SDK upgrade replace the old file and a
 * downgrade not silently reuse a newer engine. The engine's data files ({@code engine-res}) are copied the same
 * way, into {@code filesDir}, because the engine resolves its packs relative to the directory it is given.
 */
public final class EngineProvider {

    private static final String TAG = "ChunkBridge";
    private static final String ASSET_DEX = "chunkbridge/engine.jar";
    private static final String ASSET_RES = "chunkbridge/engine-res";
    private static final String ENGINE_CLASS = "com.frenetic.litechunk.engine.EngineImpl";
    private static final Object LOCK = new Object();

    private static ChunkerEngine engine;

    private EngineProvider() {
    }

    /**
     * Returns the loaded engine, loading it on first use.
     *
     * @param context the service context.
     * @throws ChunkBridgeException {@link ErrorCode#ENGINE_UNAVAILABLE} when the asset is missing or the class
     *                              cannot be loaded.
     */
    public static ChunkerEngine obtain(Context context) throws ChunkBridgeException {
        synchronized (LOCK) {
            if (engine != null) {
                return engine;
            }
            try {
                File engineDir = new File(context.getCacheDir(), "chunkbridge/engine");
                if (!engineDir.isDirectory() && !engineDir.mkdirs()) {
                    throw new IOException("could not create " + engineDir.getAbsolutePath());
                }
                File temporary = new File(engineDir, "engine.jar.tmp");
                String digest = copyAsset(context.getAssets(), ASSET_DEX, temporary);
                File dex = new File(engineDir, "engine-" + digest + ".jar");
                if (dex.isFile() && dex.length() == temporary.length()) {
                    //noinspection ResultOfMethodCallIgnored
                    temporary.delete();
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    dex.delete();
                    if (!temporary.renameTo(dex)) {
                        copyFile(temporary, dex);
                        //noinspection ResultOfMethodCallIgnored
                        temporary.delete();
                    }
                }
                makeReadOnly(dex);
                cleanStale(engineDir, dex);
                File resources = stageResources(context, digest);

                dalvik.system.DexClassLoader loader = new dalvik.system.DexClassLoader(
                        dex.getAbsolutePath(),
                        null,
                        context.getApplicationInfo().nativeLibraryDir,
                        ChunkerEngine.class.getClassLoader());
                Class<?> implementation = Class.forName(ENGINE_CLASS, true, loader);
                ChunkerEngine loaded = (ChunkerEngine) implementation.getDeclaredConstructor().newInstance();
                loaded.setResourceDirectory(resources);
                engine = loaded;
                return loaded;
            } catch (ChunkBridgeException failure) {
                throw failure;
            } catch (Throwable failure) {
                Log.e(TAG, "Could not load the embedded engine", failure);
                throw new ChunkBridgeException(ErrorCode.ENGINE_UNAVAILABLE,
                        "The embedded conversion engine could not be loaded on this device.",
                        Extractor.describe(failure), failure);
            }
        }
    }

    /** Copies the engine's data packs into filesDir once per engine digest. */
    private static File stageResources(Context context, String digest) throws IOException {
        File bridgeDir = new File(context.getFilesDir(), "chunkbridge");
        File resources = new File(bridgeDir, "engine-res");
        File stamp = new File(bridgeDir, "engine-res.stamp");
        if (resources.isDirectory() && digest.equals(readText(stamp))) {
            return resources;
        }
        JobWorkspace.deleteTree(resources);
        if (!resources.mkdirs() && !resources.isDirectory()) {
            throw new IOException("could not create " + resources.getAbsolutePath());
        }
        copyAssets(context.getAssets(), ASSET_RES, resources);
        writeText(stamp, digest);
        return resources;
    }

    /**
     * Copies the asset to the destination, returning a short hex SHA-256 of its contents.
     *
     * @throws ChunkBridgeException when the build forgot to run {@code :engine:engineDex}.
     */
    private static String copyAsset(AssetManager assets, String assetPath, File destination)
            throws IOException, ChunkBridgeException {
        MessageDigest digest = sha256();
        try (InputStream in = assets.open(assetPath);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(destination), 64 * 1024)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
        } catch (IOException missing) {
            throw new ChunkBridgeException(ErrorCode.ENGINE_UNAVAILABLE,
                    "The embedded conversion engine is missing from this build.",
                    assetPath + ": " + missing.getMessage(), missing);
        }
        return hex(digest.digest());
    }

    /**
     * Copies an asset file or folder tree.
     * <p>
     * {@code AssetManager} has no "is directory" call; the only reliable test is to try opening the child as a
     * stream and treat a failure as "it is a directory".
     */
    private static void copyAssets(AssetManager assets, String assetPath, File destination) throws IOException {
        String[] children;
        try {
            children = assets.list(assetPath);
        } catch (IOException failure) {
            children = null;
        }
        if (children == null || children.length == 0) {
            InputStream in = null;
            try {
                in = assets.open(assetPath);
            } catch (IOException emptyFolder) {
                // An empty folder: nothing to copy.
            }
            if (in != null) {
                try (InputStream stream = in) {
                    File parent = destination.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("could not create " + parent.getAbsolutePath());
                    }
                    copy(stream, destination);
                }
            }
            return;
        }
        if (!destination.isDirectory() && !destination.mkdirs()) {
            throw new IOException("could not create " + destination.getAbsolutePath());
        }
        for (String child : children) {
            copyAssets(assets, assetPath + "/" + child, new File(destination, child));
        }
    }

    private static void copy(InputStream in, File destination) throws IOException {
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(destination), 64 * 1024)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
    }

    private static void copyFile(File source, File destination) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(source))) {
            copy(in, destination);
        }
    }

    private static void makeReadOnly(File file) {
        //noinspection ResultOfMethodCallIgnored
        file.setReadOnly();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // setReadOnly() alone is not enough for the dex loader on 14+: the file must be immutable, not just
            // a writable bit clear, and must not stay executable as a script.
            //noinspection ResultOfMethodCallIgnored
            file.setWritable(false, false);
            //noinspection ResultOfMethodCallIgnored
            file.setExecutable(false, false);
        }
    }

    private static void cleanStale(File engineDir, File keep) {
        File[] files = engineDir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (!file.equals(keep)) {
                JobWorkspace.deleteTree(file);
            }
        }
    }

    private static MessageDigest sha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException(impossible);
        }
    }

    private static String hex(byte[] hash) {
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.substring(0, 16);
    }

    private static String readText(File file) {
        if (!file.isFile()) {
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) Math.min(file.length(), 4096L)];
            int read = in.read(buffer);
            return read <= 0 ? null : new String(buffer, 0, read, "UTF-8").trim();
        } catch (IOException failure) {
            return null;
        }
    }

    private static void writeText(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("could not create " + parent.getAbsolutePath());
        }
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes("UTF-8"));
        }
    }
}
