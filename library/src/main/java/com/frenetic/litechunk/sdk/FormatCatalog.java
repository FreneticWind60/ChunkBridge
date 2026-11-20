package com.frenetic.litechunk.sdk;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The format catalogue.
 * <p>
 * Read from {@code assets/chunkbridge/formats.json}, which the build generates from the engine's own table. Serving
 * it from an asset rather than asking the engine means {@link ChunkBridge#formats()} works without loading 1 MB of
 * dex into the host process: the engine is only needed to actually convert something.
 */
final class FormatCatalog {

    private final String engineVersion;
    private final List<FormatGroup> groups;

    private FormatCatalog(String engineVersion, List<FormatGroup> groups) {
        this.engineVersion = engineVersion;
        this.groups = Collections.unmodifiableList(groups);
    }

    static FormatCatalog read(Context context) throws ChunkBridgeException {
        String text;
        try (InputStream in = context.getAssets().open("chunkbridge/formats.json")) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(8192);
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            text = buffer.toString("UTF-8");
        } catch (IOException failure) {
            throw new ChunkBridgeException(ErrorCode.ENGINE_UNAVAILABLE,
                    "The embedded conversion engine is missing from this build.",
                    "assets/chunkbridge/formats.json: " + failure.getMessage(), failure);
        }
        try {
            JSONObject root = new JSONObject(text);
            String engineVersion = root.optString("engineVersion", "");
            JSONArray groupArray = root.getJSONArray("groups");
            List<FormatGroup> groups = new ArrayList<>(groupArray.length());
            for (int g = 0; g < groupArray.length(); g++) {
                JSONObject group = groupArray.getJSONObject(g);
                JSONArray versionArray = group.getJSONArray("versions");
                List<FormatVersion> versions = new ArrayList<>(versionArray.length());
                for (int v = 0; v < versionArray.length(); v++) {
                    JSONObject version = versionArray.getJSONObject(v);
                    versions.add(new FormatVersion(version.getString("id"), version.optString("version")));
                }
                groups.add(new FormatGroup(group.getString("id"), group.getString("label"), versions));
            }
            return new FormatCatalog(engineVersion, groups);
        } catch (JSONException broken) {
            throw new ChunkBridgeException(ErrorCode.ENGINE_UNAVAILABLE,
                    "The embedded engine's format list could not be read.",
                    broken.getMessage(), broken);
        }
    }

    String engineVersion() {
        return engineVersion;
    }

    List<FormatGroup> groups() {
        return groups;
    }

    /** The group with this edition id, or null. */
    FormatGroup group(String editionId) {
        if (editionId == null) {
            return null;
        }
        for (FormatGroup group : groups) {
            if (group.getEditionId().equals(editionId)) {
                return group;
            }
        }
        return null;
    }

    /** The version with this format id, or null. */
    FormatVersion version(String formatId) {
        if (formatId == null) {
            return null;
        }
        for (FormatGroup group : groups) {
            for (FormatVersion version : group.getVersions()) {
                if (version.getFormatId().equals(formatId)) {
                    return version;
                }
            }
        }
        return null;
    }

    /**
     * The suffix an app should give the output document.
     * <p>
     * Bedrock uses {@code .mcworld}, which the game itself opens; Java uses plain {@code .zip}, which is what a
     * Java save actually is. Minecraft's file pickers filter on these.
     */
    String defaultExtension(String formatId) {
        if (formatId != null) {
            String prefix = formatId;
            int underscore = formatId.indexOf('_');
            if (underscore > 0) {
                prefix = formatId.substring(0, underscore);
            }
            if ("BEDROCK".equals(prefix.toUpperCase(Locale.ROOT))) {
                return "mcworld";
            }
        }
        return "zip";
    }
}
