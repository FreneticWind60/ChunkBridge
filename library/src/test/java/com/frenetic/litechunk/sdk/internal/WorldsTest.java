package com.frenetic.litechunk.sdk.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** A picked folder or an extracted archive rarely is the world itself: most exports add one or two wrappers. */
public final class WorldsTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void recognisesJavaAndBedrockWorlds() throws IOException {
        File java = temporaryFolder.newFolder("java");
        touch(new File(java, "level.dat"));
        assertTrue(Worlds.isWorld(java));

        File bedrock = temporaryFolder.newFolder("bedrock");
        File db = new File(bedrock, "db");
        assertTrue(db.mkdirs());
        touch(new File(db, "CURRENT"));
        assertTrue(Worlds.isWorld(bedrock));

        File plain = temporaryFolder.newFolder("plain");
        touch(new File(plain, "readme.txt"));
        assertFalse(Worlds.isWorld(plain));
        assertNull(Worlds.locate(plain));
    }

    @Test
    public void unwrapsNestedFoldersUpToTheLimit() throws IOException {
        File root = temporaryFolder.newFolder("extracted");
        File wrapper = new File(root, "My World");
        File nested = new File(wrapper, "backup");
        File world = new File(nested, "world");
        assertTrue(world.mkdirs());
        touch(new File(world, "level.dat"));

        assertEquals(world, Worlds.locate(root));
        assertEquals(world, Worlds.locate(nested));
    }

    @Test
    public void ignoresMacMetadataSiblings() throws IOException {
        File root = temporaryFolder.newFolder("zip");
        File metadata = new File(root, "__MACOSX");
        assertTrue(metadata.mkdirs());
        touch(new File(metadata, "level.dat"));
        File world = new File(root, "real");
        assertTrue(world.mkdirs());
        touch(new File(world, "level.dat"));

        assertEquals(world, Worlds.locate(root));
    }

    private static void touch(File file) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(0x0a);
        }
    }
}
