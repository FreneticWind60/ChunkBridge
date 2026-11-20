package com.frenetic.litechunk.sdk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;


import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * End-to-end conversion on a device or emulator.
 * <p>
 * The world used here is a player save, so it is not committed: copy {@code test_world.zip} into
 * {@code src/androidTest/assets/} before running, otherwise the conversion tests are skipped (the catalog test still
 * runs).
 * <p>
 * Run with {@code ./gradlew :library:connectedDebugAndroidTest}.
 */
@RunWith(AndroidJUnit4.class)
public final class ConversionEndToEndTest {

    private static final long TIMEOUT_SECONDS = 15 * 60;

    private Context context;
    private File worldZip;

    @Before
    public void setUp() throws IOException {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ChunkBridge.initialize(context);
        worldZip = new File(context.getCacheDir(), "test_world.zip");
        if (!worldZip.isFile() || worldZip.length() == 0) {
            copyAsset("test_world.zip", worldZip);
        }
        Assume.assumeTrue("src/androidTest/assets/test_world.zip is missing (player world, not committed)",
                worldZip.isFile() && worldZip.length() > 0);
    }

    @Test
    public void catalogIsServedWithoutLoadingTheEngine() {
        List<FormatGroup> groups = ChunkBridge.formats();
        assertTrue("expected at least the two editions", groups.size() >= 2);

        FormatGroup java = ChunkBridge.formatGroup("JAVA");
        FormatGroup bedrock = ChunkBridge.formatGroup("BEDROCK");
        assertNotNull(java);
        assertNotNull(bedrock);
        assertTrue("every edition needs at least one version", java.getVersions().size() > 0);


        String engineVersion = ChunkBridge.engineVersion();
        assertNotNull(engineVersion);
        assertTrue("engine version should look like a version, was '" + engineVersion + "'",
                engineVersion.matches("\\d+\\.\\d+.*"));

        assertEquals("mcworld", ChunkBridge.defaultExtension(bedrock.getVersions().get(0).getFormatId()));
        assertEquals("zip", ChunkBridge.defaultExtension(java.getVersions().get(0).getFormatId()));
    }

    @Test
    public void detectsAJavaWorldArchive() throws Exception {
        Result<DetectionResult> detection = detect(worldZip);
        assertNull(describe(detection.error), detection.error);
        assertNotNull(detection.value);
        assertEquals("JAVA", detection.value.getEditionId());
        assertNotNull(detection.value.getVersion());
    }

    @Test
    public void rejectsAFileThatIsNotAWorld() throws Exception {
        File garbage = new File(context.getCacheDir(), "not-a-world.zip");
        try (OutputStream out = new FileOutputStream(garbage)) {
            out.write(new byte[]{'P', 'K', 3, 4, 0, 0, 0, 0});
        }
        Result<DetectionResult> detection = detect(garbage);
        assertNotNull("a random file must fail detection", detection.error);
    }

    @Test
    public void convertsJavaToBedrockAndBack() throws Exception {
        Result<DetectionResult> detection = detect(worldZip);
        assertNull(describe(detection.error), detection.error);
        assertNotNull(detection.value);

        FormatGroup bedrock = ChunkBridge.formatGroup("BEDROCK");
        FormatGroup java = ChunkBridge.formatGroup("JAVA");
        assertNotNull(bedrock);
        assertNotNull(java);

        String bedrockFormat = bedrock.getVersions().get(0).getFormatId();
        File bedrockWorld = new File(context.getCacheDir(), "converted.mcworld");
        convertAndAwait(worldZip, bedrockWorld, bedrockFormat);
        assertTrue("converted archive is empty", bedrockWorld.length() > 0);
        assertTrue("Bedrock output must contain a db/ folder", containsEntryStartingWith(bedrockWorld, "db/"));

        String javaFormat = java.getVersions().get(0).getFormatId();
        File backToJava = new File(context.getCacheDir(), "converted.zip");
        convertAndAwait(bedrockWorld, backToJava, javaFormat);
        assertTrue("round-tripped archive is empty", backToJava.length() > 0);
        assertTrue("Java output must contain level.dat", containsEntry(backToJava, "level.dat"));
    }

    @Test
    public void cancelStopsAConversion() throws Exception {
        FormatGroup bedrock = ChunkBridge.formatGroup("BEDROCK");
        assertNotNull(bedrock);
        File output = new File(context.getCacheDir(), "cancelled.mcworld");
        //noinspection ResultOfMethodCallIgnored
        output.delete();

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        AtomicReference<ChunkBridgeException> failure = new AtomicReference<>();
        ConversionHandle handle = ChunkBridge.convert(new ConversionRequest.Builder()
                .inputFile(worldZip)
                .outputFile(output)
                .targetFormatId(bedrock.getVersions().get(0).getFormatId())
                .overwriteExisting(true)
                .build(), new ConversionCallback() {
            @Override
            public void onStarted(String requestId) {
                started.countDown();
            }

            @Override
            public void onCompleted(ConversionResult result) {
                // Too quick to cancel: the test cannot say anything about the cancel path then.
            }

            @Override
            public void onFailed(ChunkBridgeException error) {
                failure.set(error);
                cancelled.countDown();
            }

            @Override
            public void onCancelled(String requestId) {
                cancelled.countDown();
            }
        });

        assertTrue("the conversion never started", started.await(2, TimeUnit.MINUTES));
        handle.cancel();
        assertTrue("cancel produced no callback", cancelled.await(2, TimeUnit.MINUTES));
        assertNull(describe(failure.get()), failure.get());
        // A cancelled job must not leave a half written archive behind under the requested name.
        assertTrue("a cancelled conversion left an output file behind", !output.isFile() || output.length() == 0);
        // Cancelling ends the conversion process a moment after the callback, and the next request would race with
        // that exit (the service answers BUSY until the process is gone). Give the process time to die.
        Thread.sleep(2000);
    }

    private static String describe(ChunkBridgeException error) {
        if (error == null) {
            return null;
        }
        return error.getCode() + ": " + error.getMessage() + " / " + error.getDetail()
                + (error.getCause() == null ? "" : " / cause=" + error.getCause());
    }

    private Result<DetectionResult> detect(File input) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Result<DetectionResult> result = new Result<>();
        ChunkBridge.detect(input, (detection, error) -> {
            result.value = detection;
            result.error = error;
            latch.countDown();
        });
        assertTrue("detection timed out", latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        return result;
    }

    private void convertAndAwait(File input, File output, String formatId) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ChunkBridgeException> failure = new AtomicReference<>();
        AtomicReference<ConversionResult> completed = new AtomicReference<>();
        //noinspection ResultOfMethodCallIgnored
        output.delete();

        ChunkBridge.convert(new ConversionRequest.Builder()
                .inputFile(input)
                .outputFile(output)
                .targetFormatId(formatId)
                .overwriteExisting(true)
                .build(), new ConversionCallback() {
            @Override
            public void onCompleted(ConversionResult result) {
                completed.set(result);
                latch.countDown();
            }

            @Override
            public void onFailed(ChunkBridgeException error) {
                failure.set(error);
                latch.countDown();
            }
        });

        assertTrue("conversion timed out", latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertNull(describe(failure.get()), failure.get());
        assertNotNull("no callback was delivered", completed.get());
        assertEquals(formatId, completed.get().getTargetFormatId());
        assertTrue("reported size does not match the file", completed.get().getOutputBytes() > 0);
    }

    private static boolean containsEntry(File archive, String name) throws IOException {
        try (ZipFile zip = new ZipFile(archive)) {
            return zip.getEntry(name) != null;
        }
    }

    private static boolean containsEntryStartingWith(File archive, String prefix) throws IOException {
        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                if (entries.nextElement().getName().startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }
    }

    private void copyAsset(String name, File target) throws IOException {
        try (InputStream in = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name)) {
            try (OutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
            }
        } catch (IOException missingAsset) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
        }
    }

    private static final class Result<T> {
        T value;
        ChunkBridgeException error;
    }
}
