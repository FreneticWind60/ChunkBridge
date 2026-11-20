package com.frenetic.litechunk.sdk;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Converts real player worlds that are pushed to the app's external files directory before the run:
 *
 * <pre>
 * adb push "test for moe.zip" /sdcard/Android/data/&lt;package&gt;/files/
 * </pre>
 *
 * This test is meant for manual verification on a device; the archives are never committed or shipped.
 */
@RunWith(AndroidJUnit4.class)
public class RealWorldConversionTest {
    private static final String TAG = "ChunkBridgeTest";
    /** A 1.4 GB world can take a long time on a phone; the engine is not fast at this size. */
    private static final long TIMEOUT_MINUTES = 60L;

    private Context context;
    private File external;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        external = context.getExternalFilesDir(null);
        ChunkBridge.initialize(context);
        showKeepAlive();
    }

    /**
     * Keeps the package visible for the whole run. Some vendor task cleaners stop packages whose foreground
     * notification cannot be dismissed; a visible activity keeps a 1.4 GB conversion alive. Debug builds only.
     */
    private void showKeepAlive() {
        try {
            Intent intent = new Intent()
                    .setClassName(context, "com.frenetic.litechunk.sdk.debug.KeepAliveActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (RuntimeException ignored) {
            // Debug helper only; conversions still run without it.
        }
    }

    @After
    public void tearDown() {
        // Nothing to clean up here: the archives are pushed by the test operator.
    }

    /** Java 26.3 world to Bedrock 1.26.60, the newest Bedrock format the bundled engine knows. */
    @Test
    public void convertsTheModernWorldToBedrock() throws Exception {
        File input = requireWorld("test for moe.zip");
        File output = new File(external, "test-for-moe-1.26.60.mcworld");
        convertAndAwait(input, output, "BEDROCK_1.26.60");
        assertTrue("an empty archive was written", output.length() > 0);
        assertTrue("Bedrock output must contain a db/ folder", containsEntryStartingWith(output, "db/"));
        Log.i(TAG, "Bedrock result: " + output.getAbsolutePath() + " (" + output.length() + " bytes)");
    }

    /** Java 1.20.1 world downgraded to Java 1.20.0. */
    @Test
    public void convertsTheLegacyWorldToJava1200() throws Exception {
        File input = new File(external, "legacy.zip");
        if (!input.isFile()) {
            // The original name contains non-ASCII characters; adb push often renames it.
            input = requireWorld("新建文件夹.zip");
        }
        final File world = input;
        File output = new File(external, "converted-1.20.0.zip");
        convertAndAwait(world, output, "JAVA_1.20.0");
        assertTrue("an empty archive was written", output.length() > 0);
        assertTrue("Java output must contain level.dat", containsEntry(output, "level.dat"));
        Log.i(TAG, "Java result: " + output.getAbsolutePath() + " (" + output.length() + " bytes)");
    }

    private File requireWorld(String name) {
        File file = new File(external, name);
        Assume.assumeTrue("push " + name + " to " + external.getAbsolutePath() + " first", file.isFile());
        return file;
    }

    private void convertAndAwait(File input, File output, String formatId) throws Exception {
        //noinspection ResultOfMethodCallIgnored
        output.delete();
        Log.i(TAG, "Converting " + input.getName() + " (" + input.length() + " bytes) to " + formatId);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<ChunkBridgeException> failure = new AtomicReference<>();
        AtomicReference<ConversionResult> completed = new AtomicReference<>();
        AtomicReference<Double> lastProgress = new AtomicReference<>(-1D);

        ChunkBridge.convert(new ConversionRequest.Builder()
                .inputFile(input)
                .outputFile(output)
                .targetFormatId(formatId)
                .overwriteExisting(true)
                .notificationTitle("ChunkBridge test")
                .notificationText(input.getName())
                .build(), new ConversionCallback() {
            @Override
            public void onProgress(double fraction, Stage stage) {
                if (fraction - lastProgress.get() >= 0.05D) {
                    lastProgress.set(fraction);
                    Log.i(TAG, input.getName() + ": " + Math.round(fraction * 100D) + "% " + stage);
                }
            }

            @Override
            public void onCompleted(ConversionResult result) {
                completed.set(result);
                done.countDown();
            }

            @Override
            public void onFailed(ChunkBridgeException error) {
                failure.set(error);
                done.countDown();
            }
        });

        assertTrue("conversion timed out", done.await(TIMEOUT_MINUTES, TimeUnit.MINUTES));
        assertNull(describe(failure.get()), failure.get());
        assertNotNull("no callback was delivered", completed.get());
    }

    private static String describe(ChunkBridgeException error) {
        if (error == null) {
            return null;
        }
        return error.getCode() + ": " + error.getMessage() + " / " + error.getDetail();
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
}
