package com.frenetic.litechunk.sdk.internal;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * Archive containers, recognised from the file header rather than the name.
 * <p>
 * The name is only trusted for tar, whose magic ("ustar") sits at offset 257 rather than the start of the file.
 */
public enum Container {
    ZIP,
    RAR,
    SEVEN_Z,
    GZIP,
    TAR,
    OTHER,
    UNKNOWN;

    private static final int SNIFF_BYTES = 8;

    /**
     * Recognises the container of a file on disk.
     *
     * @param file the archive, may be a world folder in which case the result is not meaningful.
     * @return the container, {@link #OTHER} when the header cannot be read.
     */
    public static Container sniff(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".tar")) {
            return TAR;
        }
        try (InputStream input = new BufferedInputStream(new FileInputStream(file), SNIFF_BYTES)) {
            input.mark(SNIFF_BYTES);
            byte[] header = new byte[SNIFF_BYTES];
            int read = 0;
            while (read < header.length) {
                int step = input.read(header, read, header.length - read);
                if (step <= 0) {
                    break;
                }
                read += step;
            }
            input.reset();
            return ofHeader(header, read);
        } catch (IOException unreadable) {
            return OTHER;
        }
    }

    private static Container ofHeader(byte[] header, int length) {
        if (length >= 4 && header[0] == 'P' && header[1] == 'K'
                && (header[2] == 3 || header[2] == 5 || header[2] == 7)
                && (header[3] == 4 || header[3] == 6 || header[3] == 8)) {
            return ZIP;
        }
        if (length >= 6 && header[0] == 'R' && header[1] == 'a' && header[2] == 'r' && header[3] == '!'
                && header[4] == 0x1A && header[5] == 0x07) {
            return RAR;
        }
        if (length >= 6 && header[0] == 0x37 && header[1] == 0x7A && header[2] == (byte) 0xBC
                && header[3] == (byte) 0xAF && header[4] == 0x27 && header[5] == 0x1C) {
            return SEVEN_Z;
        }
        if (length >= 2 && header[0] == 0x1F && header[1] == (byte) 0x8B) {
            return GZIP;
        }
        return OTHER;
    }

    /** Whether {@link Extractor} can unpack this container. */
    public boolean isSupported() {
        switch (this) {
            case ZIP:
            case RAR:
            case SEVEN_Z:
            case GZIP:
            case TAR:
                return true;
            default:
                return false;
        }
    }
}
