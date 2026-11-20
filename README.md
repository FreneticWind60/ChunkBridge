# ChunkBridge SDK

Convert Minecraft worlds between **Java Edition and Bedrock Edition** from any Android app.

ChunkBridge packages the [Chunker](https://github.com/HiveGamesOSS/Chunker) conversion engine as a self-contained
Android library. The host app does not need LiteChunk installed, does not need a Minecraft installation, and does not
need network access: the engine dex and its resources ship inside the AAR (about 1.7 MB).

```java
ChunkBridge.initialize(context);

ChunkBridge.convert(
        new ConversionRequest.Builder()
                .inputUri(inputUri)              // a world folder, a .zip or a .mcworld
                .outputUri(outputUri)            // created through ACTION_CREATE_DOCUMENT or a File
                .targetFormatId("BEDROCK_1_21_120")
                .build(),
        new ConversionCallback() {
            @Override public void onProgress(double fraction, Stage stage) { /* 0.0 .. 1.0 */ }

            @Override public void onCompleted(ConversionResult result) { /* output written */ }

            @Override public void onFailed(ChunkBridgeException error) { /* error.getCode() */ }
        });
```

## What the SDK does

| Feature | Entry point |
| --- | --- |
| List every supported edition and version | `ChunkBridge.formats()`, `ChunkBridge.formatGroup(id)` |
| Detect the edition and version of a world or archive | `ChunkBridge.detect(File, DetectionCallback)`, `ChunkBridge.detect(Uri, DetectionCallback)` |
| Convert a world folder / `.zip` / `.mcworld` into a new archive | `ChunkBridge.convert(ConversionRequest, ConversionCallback)` |
| Cancel a running conversion | `ConversionHandle.cancel()` |

Everything runs on-device. Localizations, block mappings and the Java font metrics are bundled, so a conversion never
touches the network.

## What the SDK does not do

- No `level.dat` inspection or editing, no world settings UI, no file browser, no import intents, no share sheet.
- No conversion of player data other than what the engine itself carries (entities are converted by default; pass
  `convertEntities(false)` to skip them).

## Requirements

| | |
| --- | --- |
| `minSdk` | 27 (Android 8.1) |
| `compileSdk` | 35 or newer |
| Java | 17 (the library is compiled for Java 17 bytecode) |
| Host build | AGP 8.x or newer |

The host app process does not need a large heap for the conversion itself: the engine runs in a separate
`:chunkbridge` process. A large heap is still recommended on the host to keep the rest of the UI healthy while a
conversion is running.

## Install

### From a local Maven repository

```bash
# in the SDK checkout
./gradlew :api:publishToMavenLocal :library:publishReleasePublicationToMavenLocal
```

```kotlin
// settings.gradle.kts (or repositories in build.gradle.kts)
mavenLocal()

// app/build.gradle.kts
dependencies {
    implementation("com.frenetic.chunkbridge:chunkbridge:1.0.0")
    // Pulled in transitively; listed here only for clarity.
    // implementation("com.frenetic.chunkbridge:api:1.0.0")
}
```

### From a published repository

```bash
./gradlew :api:publish :library:publish       # writes build/repo
./gradlew :api:publishToMavenLocal :library:publishReleasePublicationToMavenLocal
```

Point your repository at the generated `build/repo` directory, or copy the artifacts into your own repository.

### From a single AAR file

`library/build/outputs/aar/library-release.aar` contains the SDK classes, the Android resources and the engine
payload. Copy it into `libs/` **and** add the `api` artifact as well, because `ChunkerEngine` must be present in the
host class loader before the engine dex is attached:

```kotlin
dependencies {
    implementation(files("libs/library-release.aar"))
    implementation(files("libs/api-1.0.0.jar"))   // com.frenetic.chunkbridge:api:1.0.0
}
```

## Usage

### 1. Initialize

Call once per process, from `Application.onCreate()` or before the first SDK call:

```java
ChunkBridge.initialize(getApplicationContext());
```

This only stages the engine dex and its resources; the conversion engine is loaded on first use inside the service
process.

### 2. Pick a target format

```java
for (FormatGroup group : ChunkBridge.formats()) {          // "JAVA", "BEDROCK"
    for (FormatVersion version : group.getVersions()) {    // newest first
        Log.i("ChunkBridge", version.getDisplayName() + " -> " + version.getFormatId());
    }
}
```

`ChunkBridge.formats()` reads a bundled catalog. It is synchronous, cheap, and safe to call from the main thread.

### 3. Detect (optional)

```java
ChunkBridge.detect(inputUri, (result, error) -> {
    if (result != null) {
        Log.i("ChunkBridge", result.getEditionLabel() + " " + result.getVersion());
    }
});
```

Detection accepts a world directory, a `.zip`, a `.mcworld`, a `.rar`, a `.7z`, a `.tar` or a `.gz`. The engine has to
be loaded for this, so the call runs in the service process and answers asynchronously.

### 4. Convert

```java
ConversionHandle handle = ChunkBridge.convert(request, callback);
// later, from any thread
handle.cancel();
```

`ConversionRequest.Builder` accepts exactly one input and one output:

- `inputFile(File)` — a world directory, or an archive file.
- `inputUri(Uri)` — a content URI, typically from `ACTION_OPEN_DOCUMENT`.
- `outputFile(File)` — absolute path; the parent directory must exist.
- `outputUri(Uri)` — a content URI opened with `"wt"` mode by the service.

Optional settings: `targetFormatId(String)` (required), `convertEntities(boolean)` (default `true`),
`leanMode(boolean)` (smaller buffers for low-memory devices), `overwriteExisting(boolean)` (only for
`outputFile`), `deleteSourceArchiveAfterSuccess(boolean)` (only for archive inputs), `notificationTitle`,
`notificationText`.

Callbacks are delivered on the main thread. `onCompleted` returns the output size (`getOutputBytes()`), the resolved
target version (`getTargetVersion()`) and the elapsed time (`getElapsedMs()`).

### Stage

`Stage` values describe what the engine is doing now: `TERRAIN`, `ENTITIES`, `COMPACT`, `FINISH`, `UNKNOWN`. The
fraction passed to `onProgress` is monotonic but not linear; do not assume a fixed time per stage.

### Cancellation

`cancel()` stops the `:chunkbridge` process, which is the only way to abort the engine mid-conversion. The callback
receives `onCancelled` if the service is still alive when the request arrives; a partially written output file
(`*.part`) is removed by the next service start. Cancelling is intentionally best-effort: a conversion may still
finish if it was already in its final packing step.

## Errors

`ChunkBridgeException.getCode()` returns an `ErrorCode`:

| Code | Meaning |
| --- | --- |
| `INPUT_UNREADABLE` | The input missing, not readable, or the URI grant was revoked. |
| `ARCHIVE_UNSUPPORTED` | The archive format is not supported (see the list above). |
| `ARCHIVE_DAMAGED` | The archive is corrupt or uses an unsupported compression feature. |
| `WORLD_NOT_FOUND` | The input contains no `level.dat`/Bedrock `db/` within three directory levels. |
| `FORMAT_UNSUPPORTED` | `targetFormatId` is not in `ChunkBridge.formats()`. |
| `OUTPUT_UNWRITABLE` | The output URI/file cannot be created, or the path is not absolute. |
| `OUTPUT_EXISTS` | `outputFile` exists and `overwriteExisting(false)`. |
| `INSUFFICIENT_STORAGE` | The device ran out of space while writing. |
| `OUT_OF_MEMORY` | The conversion process ran out of heap. |
| `BUSY` | Another conversion is already running in the process. |
| `CANCELLED` | The request was cancelled. |
| `ENGINE_UNAVAILABLE` | The engine dex could not be loaded (usually a damaged or stripped AAR). |
| `FOREGROUND_NOT_ALLOWED` | Android refused to start the foreground service (see below). |
| `INTERNAL` | Anything else; `getDetail()` carries the engine message. |

## Foreground service rules (important)

A conversion runs in a foreground service (`dataSync`) in the `:chunkbridge` process. Android 12+ requires the host
app to be in the foreground when `ChunkBridge.convert(...)` is called — for example from an Activity callback, not
from the background or from a `BroadcastReceiver`. Otherwise `convert` fails immediately with
`FOREGROUND_NOT_ALLOWED` and the callback receives that error.

On Android 15+ a `dataSync` foreground service has a six-hour daily quota. Each conversion is one short run, so this
only matters for very large batches; keep a user-visible screen for long conversions anyway.

On Android 13+, request `POST_NOTIFICATIONS` before converting if you want the progress notification to be visible.

## Limits

- One conversion at a time per process; parallel requests answer `BUSY`.
- Extraction is capped at 500,000 entries, 8 GiB per entry and 32 GiB in total to stop decompression bombs.
- Archives larger than the free space on `cacheDir` cannot be staged; free space is checked before writing.

## Building this repository

```bash
export ANDROID_HOME=/path/to/Android/Sdk     # or write local.properties with sdk.dir=...
./gradlew :library:assembleDebug            # debug AAR
./gradlew :sample:assembleDebug             # sample host app
./gradlew :library:assembleRelease :library:publishReleasePublicationToMavenLocal
```

`:library:assembleRelease` runs `:engine:engineDex` (R8) and `:engine:dumpFormats`, which write the generated assets
`build/generated/engineAssets/chunkbridge/engine.jar` and
`build/generated/engineFormats/chunkbridge/formats.json`. Those are never committed. R8 needs
`ANDROID_HOME`/`ANDROID_SDK_ROOT` or `local.properties`; it takes the newest installed `platforms/android-*` and
`build-tools/*/lib/d8.jar`, and fails the build if the dex still references `VarHandle` helpers (they crash below
API 33).

Desktop engine checks (no Android device needed):

```bash
./gradlew :engine:smokeTest
./gradlew :engine:roundTrip   # needs the Chunker fixture worlds; skips cleanly when absent
```

`:engine:roundTrip` looks for fixtures in `../Chunker-main/cli/src/test/resources/integration/worlds`, then
`../../lib/Chunker-main/...`, or in `-Dchunker.fixtures=<dir>`. It converts Java 1.20.5 to Bedrock and back.

Configuration cache: `:engine:engineDex` is a `JavaExec` task, so the configuration cache entry is discarded; the
build still succeeds. Use `--no-configuration-cache` if you prefer a quiet log.

## Modules

| Module | Contents |
| --- | --- |
| `api` | `ChunkerEngine` contract and small value types. Published separately as `com.frenetic.chunkbridge:api`. |
| `engine` | Chunker sources + `EngineImpl`, desktop test mains, R8 packaging. Not published. |
| `library` | The SDK: public API, `:chunkbridge` service, engine loader, archive handling, resources. Published as `com.frenetic.chunkbridge:chunkbridge`. |
| `sample` | Minimal host app (SAF pickers, format list, detect, convert, cancel, log). Not published. |

## License

MIT. The conversion engine is Chunker (MIT, Hive Games); archive extraction uses Arcana (Apache-2.0). See
[THIRD-PARTY.md](THIRD-PARTY.md) and [LICENSE](LICENSE).

## More documentation

- [docs/INTEGRATION.md](docs/INTEGRATION.md) — integration details, threading, storage layout, failure modes.
- [docs/android-api-compat.md](docs/android-api-compat.md) — the Java 9+ API surface the engine must avoid on API 27.
