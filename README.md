# ChunkBridge SDK

**English** | [简体中文](README.zh-CN.md)

Convert Minecraft worlds between **Java Edition and Bedrock Edition** from any Android app.

ChunkBridge packages the [Chunker](https://github.com/HiveGamesOSS/Chunker) conversion engine as a self-contained
Android library. The host app does not need LiteChunk installed, does not need a Minecraft installation, and does not
need network access: the engine dex and its resources ship inside the AAR (about 1.7 MB).

```java
ChunkBridge.initialize(context);

ChunkBridge.convert(
        new ConversionRequest.Builder()
                .inputUri(inputUri)              // a world folder, a .zip or a .mcworld
                .outputUri(outputUri)            // created through ACTION_CREATE_DOCUMENT, or a File
                .targetFormatId("BEDROCK_1.26.60")
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

The engine covers Java Edition and Bedrock Edition up to the newest versions known to the bundled Chunker sources
(Java 26.3.0 / Bedrock 1.26.60 at the time of writing). Call `ChunkBridge.formats()` for the authoritative list.

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
    implementation("com.frenetic.chunkbridge:chunkbridge:1.0.1")
    // Pulled in transitively; listed here only for clarity.
    // implementation("com.frenetic.chunkbridge:api:1.0.1")
}
```

### From the GitHub release (no build required)

Every release publishes ready-to-use artifacts on
[GitHub Releases](https://github.com/FreneticWind60/ChunkBridge/releases). Download
`chunkbridge-sdk-<version>-maven-repo.zip`, unzip it somewhere in your project, and point a repository at it:

| Asset | Use |
| --- | --- |
| `chunkbridge-sdk-1.0.1-maven-repo.zip` | Local Maven repository (AAR + `api` JAR + POMs + sources) |
| `chunkbridge-1.0.1.aar` | Single-AAR install, see below |
| `api-1.0.1.jar` | Required next to the single AAR |
| `chunkbridge-1.0.1-sources.jar`, `api-1.0.1-sources.jar` | Source attachments for the IDE |

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("libs/chunkbridge-maven") }   // the unzipped repository zip
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.frenetic.chunkbridge:chunkbridge:1.0.1")
}
```

The repository zip carries both `com.frenetic.chunkbridge:chunkbridge` (AAR) and
`com.frenetic.chunkbridge:api` (JAR), so the transitive `api` dependency resolves without network access.

### From a self-built repository

```bash
./gradlew :api:publishMavenPublicationToLocalBuildRepository :library:publishReleasePublicationToLocalBuildRepository
```

That writes the same layout into `build/repo`; point a repository at it, or copy the artifacts into your own.

### From a single AAR file

`library/build/outputs/aar/library-release.aar` contains the SDK classes, the Android resources and the engine
payload. Copy it into `libs/` **and** add the `api` artifact as well, because `ChunkerEngine` must be present in the
host class loader before the engine dex is attached:

```kotlin
dependencies {
    implementation(files("libs/library-release.aar"))
    implementation(files("libs/api-1.0.1.jar"))   // com.frenetic.chunkbridge:api:1.0.1
}
```

### From a git submodule (composite build)

This is how LiteChunk consumes the SDK: the repository sits in `chunkbridge/` as a submodule and Gradle builds it
from source, so a change to the engine reaches the app without publishing anything.

```bash
git submodule add https://github.com/FreneticWind60/ChunkBridge.git chunkbridge
git submodule update --init --recursive
```

```kotlin
// settings.gradle.kts
includeBuild("chunkbridge") {
    dependencySubstitution {
        substitute(module("com.frenetic.chunkbridge:chunkbridge")).using(project(":library"))
        substitute(module("com.frenetic.chunkbridge:api")).using(project(":api"))
    }
}
```

```kotlin
// app/build.gradle.kts — the version is ignored, the included build wins
dependencies {
    implementation("com.frenetic.chunkbridge:chunkbridge:1.0.1")
}
```

Keep the submodule path configurable when you work on both repositories at once:

```kotlin
// settings.gradle.kts
val chunkbridgeDir = providers.gradleProperty("chunkbridge.sdk.dir").getOrElse("chunkbridge")
includeBuild(file(chunkbridgeDir)) { /* substitution as above */ }
```

```bash
./gradlew :app:assembleDebug -Pchunkbridge.sdk.dir=../ChunkBridge
```

A composite build needs the SDK sources on the machine; it does not work from an AAR, and CI has to clone the
repository (or check out the submodule) before building the app.

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
finish if it was already in its final packing step. The process exits a moment after the callback, so a request sent
immediately afterwards can still answer `BUSY`; retry once instead of treating that as fatal.

### 5. Read and write level.dat

Two more calls drive a world-details screen. They run in the same `:chunkbridge` process, need no notification and
are not cancellable; reading is a few milliseconds.

```java
ChunkBridge.inspectLevel(worldDirectory, (info, error) -> {
    if (error != null) return;                 // WORLD_NOT_FOUND when the directory has no level.dat
    for (LevelField field : info.fields) {
        // field.id is the NBT key, field.kind (KIND_TEXT/KIND_INT/...) says how to render it
    }
});

Map<String, String> changes = new HashMap<>();
changes.put("LevelName", "Renamed");
ChunkBridge.applyLevel(worldDirectory, changes, (info, error) -> {
    // info is the file re-read after the write, so the screen can refresh without a second call
});
```

`worldDirectory` must be a directory that contains `level.dat`; extracting it from a `.zip` / `.mcworld` and putting
it back is the caller's job (LiteChunk does it with `Archive.rewrite`). Values are plain strings because the engine
only exposes an allow list of keys and writes each one with the type the game expects.

`DataVersion`, `StorageVersion` and `NetworkVersion` are writable too, and they are the exception to "safe to touch":
they only change the declaration, not the chunk data, and a value that does not match can make the game call the save
corrupt, so a host should warn before saving one the way LiteChunk does. `StorageVersion` lives in the file header
rather than in the NBT, so the engine re-reads the header to verify it; `DataVersion` only applies to Java worlds and
`StorageVersion` / `NetworkVersion` only to Bedrock ones, and a field sent to the wrong edition is ignored rather than
written.

## Errors

`ChunkBridgeException.getCode()` returns an `ErrorCode`:

| Code | Meaning |
| --- | --- |
| `INPUT_UNREADABLE` | The input is missing, not readable, or the URI grant was revoked. |
| `ARCHIVE_UNSUPPORTED` | The archive format is not supported (see the list above). |
| `ARCHIVE_DAMAGED` | The archive is corrupt or uses an unsupported compression feature. |
| `WORLD_NOT_FOUND` | The input contains no `level.dat`/Bedrock `db/` within three directory levels. |
| `FORMAT_UNSUPPORTED` | `targetFormatId` is not in `ChunkBridge.formats()`. |
| `OUTPUT_UNWRITABLE` | The output URI/file cannot be created, or the path is not absolute. |
| `OUTPUT_EXISTS` | `outputFile` exists and `overwriteExisting(false)`. |
| `INSUFFICIENT_STORAGE` | The device ran out of space while writing. |
| `OUT_OF_MEMORY` | The conversion process ran out of heap. |
| `BUSY` | A conversion is running, or a detection was requested during one. Retry after it ends. |
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
Request it even if you do not care about the notification: some vendor ROMs (Vivo OriginOS was verified) treat a
foreground service with an invisible notification as a keep-alive abuse and kill the process within seconds.

## Limits

- One conversion at a time per process; parallel requests answer `BUSY`.
- Extraction is capped at 500,000 entries, 8 GiB per entry and 32 GiB in total to stop decompression bombs.
- Archives larger than the free space on `cacheDir` cannot be staged; free space is checked before writing.

## Verification

The SDK is tested on desktop, on emulators and on a physical phone. Highlights:

- `:engine:smokeTest` and `:engine:roundTrip` convert a Java 1.20.5 fixture world to Bedrock and back.
- `:library:testDebugUnitTest` covers the archive packer, container sniffing and world-root lookup.
- `ConversionEndToEndTest` (catalog, detect, non-world rejection, Java -> Bedrock -> Java, cancel) passes repeatedly
  on API 27 and API 30.
- `RealWorldConversionTest` on a Vivo V2505A (Android 16 / API 36): a 1.4 GB Java 1.20.1 world converted to
  `JAVA_1.20.0` in 562 s, and a Java 26.3 world converted to `BEDROCK_1.26.60` in 5.2 s; the written `level.dat`
  reports the expected `DataVersion` and the archives contain the expected `region/` and `db/` layout.

See [VERSION.md](VERSION.md) for the full report and known limitations.

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

Instrumentation tests need a connected device or emulator:

```bash
./gradlew :library:connectedDebugAndroidTest
```

`RealWorldConversionTest` reads archives from the app's external files directory
(`/sdcard/Android/data/<test package>/files/`) and skips itself when they are absent, so player saves never have to be
committed.

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
- [VERSION.md](VERSION.md) — release notes, verification matrix and known limitations.
