package com.frenetic.litechunk.sdk.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Magic-byte sniffing decides which extractor runs; the extension is only a hint. */
public final class ContainerTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void detectsFormatsByMagicBytes() throws IOException {
        assertEquals(Container.ZIP, sniff("world.zip", new byte[]{'P', 'K', 0x03, 0x04}));
        assertEquals(Container.RAR, sniff("world.rar", new byte[]{(byte) 'R', (byte) 'a', (byte) 'r', 0x21,
                0x1A, 0x07, 0x00}));
        assertEquals(Container.SEVEN_Z, sniff("world.7z", new byte[]{0x37, 0x7A, (byte) 0xBC, (byte) 0xAF,
                0x27, 0x1C}));
        assertEquals(Container.GZIP, sniff("world.gz", new byte[]{0x1F, (byte) 0x8B, 0x08, 0x00}));
    }

    @Test
    public void fallsBackToTheExtension() throws IOException {
        assertEquals(Container.TAR, sniff("world.tar", "not really a tar".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(Container.OTHER, sniff("world.dat", "anything".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    public void everyDetectedArchiveIsSupported() {
        for (Container container : new Container[]{Container.ZIP, Container.RAR, Container.SEVEN_Z,
                Container.GZIP, Container.TAR}) {
            assertTrue(container.name() + " should be supported", container.isSupported());
        }
        assertFalse(Container.OTHER.isSupported());
        assertFalse(Container.UNKNOWN.isSupported());
    }

    private Container sniff(String name, byte[] header) throws IOException {
        File file = temporaryFolder.newFile(name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(header);
        }
        return Container.sniff(file);
    }
}
