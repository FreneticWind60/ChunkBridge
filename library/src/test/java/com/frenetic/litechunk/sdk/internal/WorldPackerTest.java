package com.frenetic.litechunk.sdk.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The archive writer must keep LevelDB tables stored (never deflated) and everything else compressed, because the
 * Bedrock client reads the tables back with mmap and a deflated table is re-written on open.
 */
public final class WorldPackerTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void storesLevelDbTablesAndCompressesTheRest() throws IOException {
        File world = temporaryFolder.newFolder("world");
        write(new File(world, "level.dat"), new byte[]{0x0a, 0x00, 0x00});
        File db = new File(world, "db");
        assertTrue(db.mkdirs());
        write(new File(db, "000123.ldb"), new byte[]{0x01, 0x02, 0x03, 0x04});
        write(new File(db, "CURRENT"), "MANIFEST-000001\n".getBytes(StandardCharsets.US_ASCII));
        File empty = new File(world, "empty");
        assertTrue(empty.mkdirs());

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        WorldPacker.pack(world, buffer);

        Map<String, Integer> methods = new HashMap<>();
        Map<String, Integer> sizes = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(buffer.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                methods.put(entry.getName(), entry.getMethod());
                sizes.put(entry.getName(), (int) entry.getSize());
                zip.closeEntry();
            }
        }

        assertEquals(Integer.valueOf(ZipEntry.STORED), methods.get("db/000123.ldb"));
        assertEquals(Integer.valueOf(4), sizes.get("db/000123.ldb"));
        assertEquals(Integer.valueOf(ZipEntry.DEFLATED), methods.get("level.dat"));
        assertEquals(Integer.valueOf(ZipEntry.DEFLATED), methods.get("db/CURRENT"));
        assertNotNull("empty directories must still produce an entry", methods.get("empty/"));
    }

    @Test
    public void closesTheCallerStream() throws IOException {
        File world = temporaryFolder.newFolder("world2");
        write(new File(world, "level.dat"), new byte[]{0x0a});

        ClosingStream stream = new ClosingStream();
        WorldPacker.pack(world, stream);
        assertTrue("pack() must close the stream it was given", stream.closed);
    }

    private static void write(File file, byte[] bytes) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
    }

    private static final class ClosingStream extends ByteArrayOutputStream {
        boolean closed;

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
