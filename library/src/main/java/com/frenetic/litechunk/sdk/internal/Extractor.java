package com.frenetic.litechunk.sdk.internal;

import com.frenetic.litechunk.sdk.ChunkBridgeException;
import com.frenetic.litechunk.sdk.ErrorCode;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import be.stef.arcana.ArcanaEntry;
import be.stef.arcana.extractor.RarExtractor;
import be.stef.arcana.extractor.SevenZExtractor;
import be.stef.arcana.extractor.TarExtractor;
import be.stef.arcana.formats.rar.rar4.Rar4Extractor;
import be.stef.arcana.formats.rar.rar5.Rar5Extractor;
import be.stef.arcana.util.ArcanaConcurrency;

/**
 * Unpacks the containers a world arrives in.
 * <p>
 * Zip is read with {@code java.util.zip} because it is faster and needs no third-party code; RAR 4/5, 7z, tar and
 * tar+gzip go through the vendored <a href="https://github.com/RealBurst/arcana">Arcana</a> decoders. Every path is
 * guarded: entry names may not escape the destination folder, and an archive may not expand past fixed limits, so a
 * hand-crafted zip bomb cannot fill the phone's storage before the engine ever sees a chunk.
 */
public final class Extractor {

    private static final int BUFFER = 64 * 1024;
    /** Guards against a "42.zip"-style archive with millions of entries. */
    private static final int MAX_ENTRIES = 500_000;
    /** No single world file is legitimately larger than this. */
    private static final long MAX_ENTRY_BYTES = 8L * 1024 * 1024 * 1024;
    /** Total unpacked size allowed per archive, roughly twice the largest transferable phone save. */
    private static final long MAX_TOTAL_BYTES = 32L * 1024 * 1024 * 1024;

    static {
        // Extraction runs inside the conversion service, which already spreads itself over the few cores a phone
        // has; Arcana otherwise sizes its worker pool from the core count and spawns one writer per core.
        ArcanaConcurrency.threads = 2;
        // The RAR decoders print a per-file progress bar to stdout by default, which is logcat spam on a phone.
        Rar4Extractor.showProgress = false;
        Rar5Extractor.showProgress = false;
    }

    private Extractor() {
    }

    /**
     * Unpacks an archive into a folder, creating it if needed.
     *
     * @param source      the archive on disk.
     * @param container   what {@link Container#sniff(File)} reported.
     * @param destination the folder to unpack into.
     * @throws ChunkBridgeException {@link ErrorCode#ARCHIVE_UNSUPPORTED} for containers this SDK cannot read,
     *                              {@link ErrorCode#ARCHIVE_DAMAGED} when the archive is corrupt, encrypted or too
     *                              large to expand safely.
     */
    public static void extract(File source, Container container, File destination) throws ChunkBridgeException {
        if (!destination.isDirectory() && !destination.mkdirs()) {
            throw new ChunkBridgeException(
                    ErrorCode.INSUFFICIENT_STORAGE,
                    "Could not create a folder to unpack the input into.",
                    destination.getAbsolutePath());
        }
        switch (container) {
            case ZIP:
                unzip(source, destination);
                return;
            case RAR:
            case SEVEN_Z:
            case TAR:
            case GZIP:
                unzipWithArcana(source, destination, container);
                return;
            default:
                throw new ChunkBridgeException(
                        ErrorCode.ARCHIVE_UNSUPPORTED,
                        "This archive format cannot be read. Unpack it and pick the world folder instead.",
                        container.name());
        }
    }

    private static void unzip(File source, File destination) throws ChunkBridgeException {
        String rootPath;
        try {
            rootPath = destination.getCanonicalPath();
        } catch (IOException failure) {
            throw new ChunkBridgeException(ErrorCode.INSUFFICIENT_STORAGE, "Could not resolve the staging folder.",
                    describe(failure), failure);
        }
        int entries = 0;
        long total = 0L;
        byte[] buffer = new byte[BUFFER];
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(source), BUFFER))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw new ArchiveLimitException("The archive has more than " + MAX_ENTRIES + " entries.");
                }
                String name = entry.getName();
                if (name == null || name.isEmpty() || name.startsWith("__MACOSX")) {
                    continue;
                }
                File target = new File(destination, name);
                String path = target.getCanonicalPath();
                if (!path.equals(rootPath) && !path.startsWith(rootPath + File.separator)) {
                    throw new ArchiveLimitException("The archive points outside its own folder: " + name);
                }
                if (entry.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    target.mkdirs();
                    continue;
                }
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Could not create " + parent.getName());
                }
                long written = 0L;
                try (OutputStream out = new FileOutputStream(target)) {
                    int read;
                    while ((read = zip.read(buffer)) > 0) {
                        written += read;
                        total += read;
                        if (written > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) {
                            throw new ArchiveLimitException("The archive expands past the safety limit.");
                        }
                        out.write(buffer, 0, read);
                    }
                }
            }
        } catch (ArchiveLimitException failure) {
            throw new ChunkBridgeException(ErrorCode.ARCHIVE_DAMAGED, failure.getMessage(), null, failure);
        } catch (IOException failure) {
            throw new ChunkBridgeException(ErrorCode.ARCHIVE_DAMAGED,
                    "The archive could not be unpacked; it may be corrupt or password protected.",
                    describe(failure), failure);
        }
    }

    private static void unzipWithArcana(File source, File destination, Container container)
            throws ChunkBridgeException {
        try {
            checkDeclaredSize(source, container);
            switch (container) {
                case RAR:
                    new RarExtractor().extract(source, destination);
                    break;
                case SEVEN_Z:
                    new SevenZExtractor().extract(source, destination);
                    break;
                case TAR:
                    new TarExtractor().extract(source, destination);
                    break;
                case GZIP:
                    try (InputStream in = new BufferedInputStream(new FileInputStream(source), BUFFER);
                         InputStream gzip = new GZIPInputStream(in)) {
                        new TarExtractor().extract(gzip, destination);
                    }
                    break;
                default:
                    throw new IllegalStateException("Not an Arcana container: " + container);
            }
        } catch (ChunkBridgeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ChunkBridgeException(ErrorCode.ARCHIVE_DAMAGED,
                    "The archive could not be unpacked; it may be corrupt or password protected.",
                    describe(failure), failure);
        }
    }

    /**
     * Reads the archive index and refuses the obviously impossible before anything is written.
     * <p>
     * RAR, 7z and tar carry per-entry sizes, so a billion-byte bomb is visible without unpacking a byte. Gzip has no
     * index, but its trailer holds the uncompressed size as an unsigned 32 bit value, which is exact below 4 GB.
     */
    private static void checkDeclaredSize(File source, Container container) throws Exception {
        if (container == Container.GZIP) {
            long size = gzipSize(source);
            if (size > MAX_TOTAL_BYTES) {
                throw new ArchiveLimitException("The archive declares " + size + " bytes, past the safety limit.");
            }
            return;
        }
        List<ArcanaEntry> entries;
        switch (container) {
            case RAR:
                entries = new RarExtractor().list(source);
                break;
            case SEVEN_Z:
                entries = new SevenZExtractor().list(source);
                break;
            case TAR:
                entries = new TarExtractor().list(source);
                break;
            default:
                return;
        }
        if (entries.size() > MAX_ENTRIES) {
            throw new ArchiveLimitException("The archive has more than " + MAX_ENTRIES + " entries.");
        }
        long total = 0L;
        for (ArcanaEntry entry : entries) {
            long size = entry.getUncompressedSize();
            if (size > MAX_ENTRY_BYTES) {
                throw new ArchiveLimitException("An entry declares " + size + " bytes, past the safety limit.");
            }
            if (size > 0L) {
                total += size;
            }
            if (total > MAX_TOTAL_BYTES) {
                throw new ArchiveLimitException("The archive expands past the safety limit.");
            }
        }
    }

    /** The ISIZE field at the end of a gzip stream: uncompressed size modulo 2^32. */
    private static long gzipSize(File source) throws IOException {
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(source, "r")) {
            if (file.length() < 4L) {
                return -1L;
            }
            file.seek(file.length() - 4L);
            byte[] tail = new byte[4];
            file.readFully(tail);
            return (tail[0] & 0xFFL) | (tail[1] & 0xFFL) << 8 | (tail[2] & 0xFFL) << 16 | (tail[3] & 0xFFL) << 24;
        }
    }

    /** Renders a throwable as one log line: class and message, without a stack trace. */
    public static String describe(Throwable failure) {
        if (failure == null) {
            return null;
        }
        String message = failure.getMessage();
        String name = failure.getClass().getSimpleName();
        return message == null ? name : name + ": " + message;
    }

    /** Narrows an IOException so the caller can tell "too big" from "corrupt". */
    private static final class ArchiveLimitException extends IOException {
        ArchiveLimitException(String message) {
            super(message);
        }
    }

    /** Whether a name looks like a world archive this SDK accepts; used for friendlier errors only. */
    static boolean looksLikeArchive(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".zip") || lower.endsWith(".mcworld") || lower.endsWith(".rar")
                || lower.endsWith(".7z") || lower.endsWith(".tar") || lower.endsWith(".gz")
                || lower.endsWith(".tgz");
    }
}
