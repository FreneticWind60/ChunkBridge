package com.frenetic.litechunk.sdk.internal;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a converted world directory into a zip stream.
 * <p>
 * Two rules come straight from the app this SDK was extracted from, and both are load bearing:
 * <ul>
 *     <li>LevelDB files ({@code .ldb}, {@code .sst}) are stored uncompressed. Compression gains almost nothing on
 *     them, and their random-read pattern matters more than a few percent of size.</li>
 *     <li>The compression level is set once on the stream and never per entry. Switching it per entry produces
 *     archives that Android's own zip reader (the one Minecraft's share sheet and file managers use) fails to
 *     open on ART.</li>
 * </ul>
 */
public final class WorldPacker {

    private static final int BUFFER = 64 * 1024;

    private WorldPacker() {
    }

    /**
     * Packs a world folder into a stream.
     *
     * @param worldDirectory the folder whose contents become the archive root.
     * @param out            destination stream; this method closes it (the ZipOutputStream owns the deflater).
     * @throws IOException when a file cannot be read or the stream fails.
     */
    public static void pack(File worldDirectory, OutputStream out) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(out, BUFFER))) {
            zip.setLevel(Deflater.DEFAULT_COMPRESSION);
            addTree(zip, worldDirectory, "");
            zip.finish();
        }
    }

    private static void addTree(ZipOutputStream zip, File directory, String prefix) throws IOException {
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            String name = prefix + child.getName();
            if (child.isDirectory()) {
                File[] grandChildren = child.listFiles();
                if (grandChildren == null || grandChildren.length == 0) {
                    // An empty folder would otherwise vanish from the archive; Minecraft tolerates that, but
                    // keeping it makes the output look like the input when someone inspects it.
                    zip.putNextEntry(new ZipEntry(name + "/"));
                    zip.closeEntry();
                } else {
                    addTree(zip, child, name + "/");
                }
                continue;
            }
            addFile(zip, child, name);
        }
    }

    private static void addFile(ZipOutputStream zip, File file, String name) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        if (storeAsIs(file.getName())) {
            long crc = crc32(file);
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(file.length());
            entry.setCompressedSize(file.length());
            entry.setCrc(crc);
            zip.putNextEntry(entry);
            try (InputStream in = new FileInputStream(file)) {
                copy(in, zip);
            }
        } else {
            zip.putNextEntry(entry);
            try (InputStream in = new FileInputStream(file)) {
                copy(in, zip);
            }
        }
        zip.closeEntry();
    }

    private static boolean storeAsIs(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".ldb") || lower.endsWith(".sst");
    }

    private static long crc32(File file) throws IOException {
        CRC32 crc = new CRC32();
        byte[] buffer = new byte[BUFFER];
        try (InputStream in = new FileInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                crc.update(buffer, 0, read);
            }
        }
        return crc.getValue();
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[BUFFER];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
    }
}
