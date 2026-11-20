package com.frenetic.chunkbridge.sample;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.frenetic.litechunk.sdk.ChunkBridge;
import com.frenetic.litechunk.sdk.ChunkBridgeException;
import com.frenetic.litechunk.sdk.ConversionCallback;
import com.frenetic.litechunk.sdk.ConversionHandle;
import com.frenetic.litechunk.sdk.ConversionRequest;
import com.frenetic.litechunk.sdk.ConversionResult;
import com.frenetic.litechunk.sdk.DetectionCallback;
import com.frenetic.litechunk.sdk.DetectionResult;
import com.frenetic.litechunk.sdk.FormatGroup;
import com.frenetic.litechunk.sdk.FormatVersion;
import com.frenetic.litechunk.sdk.Stage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A deliberately plain host app: no AndroidX, no architecture components, just the SDK on top of the platform.
 *
 * <p>It shows the four things every integration needs: pick a source document, list the target formats, start a
 * conversion with a callback, and cancel it. The callback arrives on the main thread, so the views are touched
 * directly.
 */
public class MainActivity extends Activity {

    private static final int REQUEST_INPUT = 1001;
    private static final int REQUEST_OUTPUT = 1002;

    private final List<String> formatIds = new ArrayList<>();
    private final List<String> formatNames = new ArrayList<>();
    private final StringBuilder logText = new StringBuilder();

    private TextView inputLabel;
    private TextView outputLabel;
    private TextView status;
    private TextView log;
    private Spinner formatSpinner;
    private CheckBox entities;
    private CheckBox lean;
    private Button convertButton;
    private Button cancelButton;
    private ProgressBar progress;

    private Uri inputUri;
    private Uri outputUri;
    private ConversionHandle handle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ChunkBridge.initialize(this);

        inputLabel = findViewById(R.id.input_label);
        outputLabel = findViewById(R.id.output_label);
        status = findViewById(R.id.status);
        log = findViewById(R.id.log);
        formatSpinner = findViewById(R.id.format);
        entities = findViewById(R.id.entities);
        lean = findViewById(R.id.lean);
        convertButton = findViewById(R.id.convert);
        cancelButton = findViewById(R.id.cancel);
        progress = findViewById(R.id.progress);

        populateFormats();

        findViewById(R.id.pick_input).setOnClickListener(view -> pickInput());
        findViewById(R.id.pick_output).setOnClickListener(view -> pickOutput());
        findViewById(R.id.detect).setOnClickListener(view -> detect());
        findViewById(R.id.clear_log).setOnClickListener(view -> {
            logText.setLength(0);
            log.setText("");
        });
        convertButton.setOnClickListener(view -> convert());
        cancelButton.setOnClickListener(view -> {
            if (handle != null) {
                handle.cancel();
                appendLog("Cancelling…");
            }
        });
        updateRunningState(false);
    }

    private void populateFormats() {
        for (FormatGroup group : ChunkBridge.formats()) {
            for (FormatVersion version : group.getVersions()) {
                formatIds.add(version.getFormatId());
                formatNames.add(group.getEditionLabel() + " " + version.getDisplayName());
            }
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, formatNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        formatSpinner.setAdapter(adapter);
        appendLog("Engine " + ChunkBridge.engineVersion() + " · " + formatIds.size() + " output formats");
    }

    // ---------------------------------------------------------------- picking

    private void pickInput() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_INPUT);
        } catch (ActivityNotFoundException missing) {
            toast("No file picker on this device.");
        }
    }

    private void pickOutput() {
        if (inputUri == null) {
            toast(getString(R.string.missing_input));
            return;
        }
        String formatId = selectedFormatId();
        String extension = ChunkBridge.defaultExtension(formatId);
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/octet-stream")
                .putExtra(Intent.EXTRA_TITLE, getString(R.string.suggested_name) + "." + extension)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_OUTPUT);
        } catch (ActivityNotFoundException missing) {
            toast("No document provider on this device.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (SecurityException failure) {
            // Not every provider grants persistable access; the immediate grant is usually enough for one job.
            appendLog("Persistable access refused, using the temporary grant.");
        }
        if (requestCode == REQUEST_INPUT) {
            inputUri = uri;
            outputUri = null;
            outputLabel.setText(getString(R.string.no_output));
            inputLabel.setText(describe(uri));
            detect();
        } else if (requestCode == REQUEST_OUTPUT) {
            outputUri = uri;
            outputLabel.setText(describe(uri));
        }
    }

    // ---------------------------------------------------------------- actions

    private void detect() {
        if (inputUri == null) {
            toast(getString(R.string.missing_input));
            return;
        }
        status.setText("Detecting…");
        ChunkBridge.detect(inputUri, new DetectionCallback() {
            @Override
            public void onFinished(DetectionResult result, ChunkBridgeException error) {
                if (error != null) {
                    status.setText("Not a world: " + error.getMessage());
                    appendLog("detect failed · " + error.getCode() + " · " + error.getMessage());
                    return;
                }
                status.setText(result.getEditionLabel() + " " + result.getVersion());
                appendLog("detected " + result.getEditionId() + " " + result.getVersion());
                preselectFormatFor(result.getEditionId());
            }
        });
    }

    /** Moves the spinner to the same edition as the source, which is what most users want. */
    private void preselectFormatFor(String editionId) {
        for (int index = 0; index < formatIds.size(); index++) {
            if (formatIds.get(index).startsWith(editionId)) {
                formatSpinner.setSelection(index);
                return;
            }
        }
    }

    private void convert() {
        if (inputUri == null) {
            toast(getString(R.string.missing_input));
            return;
        }
        if (outputUri == null) {
            toast(getString(R.string.missing_output));
            return;
        }
        ConversionRequest request = new ConversionRequest.Builder()
                .inputUri(inputUri)
                .outputUri(outputUri)
                .targetFormatId(selectedFormatId())
                .convertEntities(entities.isChecked())
                .leanMode(lean.isChecked())
                // CREATE_DOCUMENT already created an empty document at the target.
                .overwriteExisting(true)
                .notificationTitle(getString(R.string.app_name))
                .build();

        updateRunningState(true);
        progress.setProgress(0);
        status.setText("Starting…");
        handle = ChunkBridge.convert(request, new ConversionCallback() {
            @Override
            public void onStarted(String requestId) {
                appendLog("started " + requestId);
            }

            @Override
            public void onProgress(double fraction, Stage stage) {
                progress.setProgress((int) Math.round(fraction * 100D));
                status.setText(stage.name().toLowerCase(Locale.ROOT) + " · "
                        + Math.round(fraction * 100D) + "%");
            }

            @Override
            public void onCompleted(ConversionResult result) {
                updateRunningState(false);
                progress.setProgress(100);
                status.setText("Done · " + humanSize(result.getOutputBytes()));
                appendLog("done · " + result.getTargetFormatId() + " " + result.getTargetVersion()
                        + " · " + humanSize(result.getOutputBytes())
                        + " · " + result.getElapsedMs() + " ms");
            }

            @Override
            public void onFailed(ChunkBridgeException error) {
                updateRunningState(false);
                status.setText("Failed · " + error.getCode());
                appendLog("failed · " + error.getCode() + " · " + error.getMessage()
                        + (error.getDetail() == null ? "" : " · " + error.getDetail()));
            }

            @Override
            public void onCancelled(String requestId) {
                updateRunningState(false);
                status.setText("Cancelled");
                appendLog("cancelled " + requestId);
            }
        });
    }

    private void updateRunningState(boolean running) {
        convertButton.setEnabled(!running);
        cancelButton.setEnabled(running);
        formatSpinner.setEnabled(!running);
        entities.setEnabled(!running);
        lean.setEnabled(!running);
    }

    // ---------------------------------------------------------------- helpers

    private String selectedFormatId() {
        if (formatIds.isEmpty()) {
            throw new IllegalStateException("No formats were reported by the SDK.");
        }
        return formatIds.get(formatSpinner.getSelectedItemPosition());
    }

    private String describe(Uri uri) {
        String name = uri.getLastPathSegment();
        long size = -1L;
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    name = cursor.getString(nameIndex);
                }
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex);
                }
            }
        } catch (RuntimeException ignored) {
            // The label is cosmetic.
        }
        return size >= 0 ? name + " (" + humanSize(size) + ")" : String.valueOf(name);
    }

    private void appendLog(String line) {
        logText.append(line).append('\n');
        log.setText(logText.toString());
        appendLogScroll();
    }

    private void appendLogScroll() {
        View parent = log.getParent() instanceof View ? (View) log.getParent() : null;
        if (parent != null) {
            parent.post(() -> parent.requestLayout());
        }
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024D);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024D * 1024D));
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
