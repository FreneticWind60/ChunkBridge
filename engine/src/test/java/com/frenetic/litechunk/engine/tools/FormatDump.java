package com.frenetic.litechunk.engine.tools;

import com.frenetic.litechunk.api.ChunkerEngine;
import com.frenetic.litechunk.api.FormatGroup;
import com.frenetic.litechunk.api.FormatVersion;
import com.frenetic.litechunk.engine.EngineImpl;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Writes the engine's writable format catalogue as JSON for the SDK asset {@code chunkbridge/formats.json}.
 * <p>
 * This class lives in the test source set on purpose: it must never end up in the engine jar or the dex, and it
 * runs on the desktop JVM exactly like {@code smokeTest} does. The SDK reads this asset synchronously so the host
 * process never has to load Chunker just to populate a version picker.
 * <p>
 * {@code gradlew :engine:dumpFormats}
 */
public final class FormatDump {

    private FormatDump() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: FormatDump <output.json>");
        }
        File output = new File(args[0]);
        File parent = output.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("cannot create " + parent);
        }

        // The engine needs a resource directory for the Java edition height pack. A temp dir mirrors what the SDK
        // hands it at runtime; the dump only enumerates types, so no file inside it is required.
        File resources = Files.createTempDirectory("engine-res").toFile();
        ChunkerEngine engine = new EngineImpl();
        engine.setResourceDirectory(resources);

        StringBuilder json = new StringBuilder(8192);
        json.append('{');
        json.append("\"engineVersion\":").append(quote(engine.engineVersion())).append(',');
        json.append("\"groups\":[");
        boolean firstGroup = true;
        for (FormatGroup group : engine.formats()) {
            if (!firstGroup) {
                json.append(',');
            }
            firstGroup = false;
            json.append('{');
            json.append("\"id\":").append(quote(group.id)).append(',');
            json.append("\"label\":").append(quote(group.label)).append(',');
            json.append("\"versions\":[");
            FormatVersion[] versions = group.versions;
            for (int i = 0; i < versions.length; i++) {
                if (i > 0) {
                    json.append(',');
                }
                json.append('{');
                json.append("\"id\":").append(quote(versions[i].id)).append(',');
                json.append("\"version\":").append(quote(versions[i].version));
                json.append('}');
            }
            json.append("]}");
        }
        json.append("]}");

        try (OutputStream out = new FileOutputStream(output)) {
            out.write(json.toString().getBytes(StandardCharsets.UTF_8));
        }
        System.out.println("FormatDump: wrote " + output.getAbsolutePath() + " (" + output.length() + " bytes, "
                + engine.engineVersion() + ")");
    }

    /**
     * Minimal JSON string escaping. The catalogue only contains ids and short display names, so this is enough;
     * pulling Gson in here would add a test dependency for no benefit.
     */
    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
        return out.toString();
    }
}
