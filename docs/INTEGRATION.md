# Integrating ChunkBridge

This document covers the details that the [README](../README.md) quick start leaves out: process model, threading,
storage, lifecycle, permissions and failure handling.

## Process model

```
host app process (e.g. com.example.app)
  ├── ChunkBridge (static API, FormatCatalog from assets/chunkbridge/formats.json)
  └── library classes (support only; no Chunker classes)

:chunkbridge process (foreground service, android:process=":chunkbridge")
  ├── SdkConversionService
  ├── EngineProvider → DexClassLoader(codeCacheDir/chunkbridge/engine-<hash>.jar)
  │                    └── com.frenetic.litechunk.engine.EngineImpl  (Chunker + leveldb + lz4)
  └── one job at a time on a single background thread
```

Consequences:

- The engine's static state, its ~1 MB dex and its memory budget live in the `:chunkbridge` process. Killing that
  process (cancellation, "force stop" from the user, OS low-memory kill) never corrupts the host app process.
- `ChunkBridge.formats()` runs in the host process and never loads the engine.
- `ChunkBridge.detect(...)`, `ChunkBridge.convert(...)`, `ChunkBridge.inspectLevel(...)` and
  `ChunkBridge.applyLevel(...)` hand work to the service process over an explicit `Intent` + `ResultReceiver` pair.
  There is no AIDL and no bound service, so the SDK works from any process of the host app.
- The host app and the SDK must be in the same APK; the SDK's service is declared by the library manifest and
  merged into the host manifest automatically.

## Initialization

`ChunkBridge.initialize(Context)` is idempotent:

1. Copies `assets/chunkbridge/engine.jar` to `codeCacheDir/chunkbridge/engine-<sha256 prefix>.jar` (once per engine
   build; stale copies are deleted) and makes it read-only where Android 14 requires it.
2. Copies `assets/chunkbridge/engine-res/**` to `filesDir/chunkbridge/engine-res` and stamps the copy, so an app
   update that ships new engine resources refreshes them.
3. Loads the format catalog.

Both copies are cheap file copies and run on the calling thread, so call `initialize` off the main thread if your app
is strict about disk I/O on startup. It does not touch the conversion engine.

The engine dex is only attached inside the service process, on the first `detect`/`convert`.

If initialization fails, later calls throw `IllegalStateException`; the usual cause is a stripped asset directory
(for example a custom packaging rule that excluded `assets/chunkbridge/**`).

## Threading

| Where | Thread |
| --- | --- |
| `ChunkBridge.initialize`, `formats`, `formatGroup`, `defaultExtension` | caller's thread, synchronous |
| `ChunkBridge.detect`, `ChunkBridge.convert` | return immediately; work happens in `:chunkbridge` |
| `DetectionCallback`, `ConversionCallback` | main thread, in order |
| `ConversionHandle.cancel` | any thread |

Progress callbacks are throttled by the engine (about 200 ms) and by the SDK (at most one per 200 ms of wall time),
so a fast conversion produces very few events. `onProgress` is not called with the same fraction twice and never
decreases.

`onStarted` fires when the service has accepted the job, before any extraction work.

## Requests

Exactly one input and one output must be set; `build()` throws `IllegalArgumentException` for programming errors so
they surface in development rather than through a callback.

Inputs:

- World **folder** (`inputFile`) — used in place; the SDK never modifies or deletes it.
- **Archive file** (`inputFile`) — `.zip`, `.mcworld`, `.rar`, `.7z`, `.tar`, `.gz`; detected by magic bytes, the file
  extension is only a hint.
- **Content URI** (`inputUri`) — the bytes are copied into the job directory immediately, so a short-lived URI grant
  is enough. Persistable grants are supported but not required.

Outputs:

- **File** (`outputFile`) — must be an absolute path; the parent directory must exist. Written as `<name>.part` and
  renamed into place, so a failed conversion never leaves a half-written archive at the final name. With
  `overwriteExisting(false)` an existing file fails the job with `OUTPUT_EXISTS`.
- **Content URI** (`outputUri`) — the service opens it with mode `"wt"` and streams the archive into it. A failure
  mid-stream leaves a partial document; delete it from your app if `onFailed` reports a write error.

`deleteSourceArchiveAfterSuccess(true)` deletes the input archive *document* (URI) or file after a successful
conversion. It is ignored for world-folder inputs.

`packAsMcworld` is not a setting: the output is always a zip archive. Rename it `.mcworld` for Bedrock or `.zip` for
Java; `ChunkBridge.defaultExtension(formatId)` returns the conventional one.

## World details (level.dat)

`inspectLevel` and `applyLevel` read and rewrite `level.dat` only — no chunks are touched, so no placeholder blocks
appear and the conversion budget does not apply. They are synchronous from the caller's point of view: the callback
fires on the main thread a few milliseconds later.

- The directory must contain `level.dat`. Extract it from a `.zip` / `.mcworld` yourself (LiteChunk uses a single
  pass `Archive.rewrite`), call the SDK on the temporary directory, then write the archive back. The SDK will not
  unpack a multi-gigabyte archive for a name edit.
- `LevelField` values are strings. `kind` (`KIND_TEXT`, `KIND_INT`, `KIND_LONG`, `KIND_BOOL`, `KIND_ENUM`,
  `KIND_READ_ONLY`) tells you which editor to show; `KIND_ENUM` fields must be written as one of `optionIds`.
- `applyLevel` takes the changed ids only; unknown ids are ignored by the engine. The returned `LevelInfo` is the
  file re-read after the write.
- `inspectLevel`/`applyLevel` do not need `POST_NOTIFICATIONS` or a foreground service. They participate in the busy
  check: a running conversion answers `BUSY`, but level work and subsequent conversions do not block each other.

## Storage layout

All intermediate data lives under the host app's `cacheDir`:

```
cacheDir/chunkbridge/jobs/<requestId>/
  source       input archive copied from a URI (absent for File inputs)
  in/          extraction target for archives
  out/         world written by the engine
```

The job directory is deleted in a `finally` block on success, failure and cancellation. On service start, the whole
`cacheDir/chunkbridge/jobs` tree is cleared, which also removes leftovers from a process that was killed mid-job.

Free space: extracting an archive needs roughly its uncompressed size; conversion needs roughly the output size
again. The SDK does not pre-check total space, but `INSUFFICIENT_STORAGE` is reported when the write fails.

## Permissions and manifest merging

The library manifest declares:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

`POST_NOTIFICATIONS` is only needed for the progress notification (Android 13+); the conversion works without it,
but Android 13+ hides the notification if the permission is not granted. Request it before converting anyway: some
vendor ROMs (verified on Vivo OriginOS / API 36) treat a foreground service whose notification is not visible as a
keep-alive abuse and kill the package within seconds (`am_kill ... due to WithOutCancelNoti`). With the permission
granted the same conversion runs for hours.

The service is exported=false and runs with `foregroundServiceType="dataSync"`. The host app must not declare a
service with the same class name or a conflicting `android:process`.

The library does **not** set `android:largeHeap`. Set it in the host application if you expect large worlds:

```xml
<application android:largeHeap="true" ... >
```

Memory plan: `MB_PER_THREAD = 128`, at most two converter threads, lean mode below 256 MB of heap. Those values are
applied inside the service process, so the host's own heap is not the constraint; a large heap still helps the
engine's read/write buffers.

## Foreground service rules

Call `convert` while the host app is in a state where Android allows starting a foreground service:

- Android 12+ (`S`) throws `ForegroundServiceStartNotAllowedException` when the app is in the background. The SDK
  maps that to `FOREGROUND_NOT_ALLOWED` and delivers it through `onFailed`; nothing is queued.
- Android 14+ (`U`) requires the `dataSync` type to be declared, which the library does.
- Android 15+ limits `dataSync` foreground services to six hours per day per app. A single conversion is far below
  that; do not build an unbounded batch queue on top of the SDK without a visible UI.

Recommended flow: user picks files in an Activity, the Activity calls `convert`, then the Activity observes progress
until `onCompleted`/`onFailed`. The conversion continues while the Activity is paused or the screen is off (a partial
wake lock is held for up to two hours), but the process may still be killed by the OS under memory pressure.

## Cancellation and process death

`cancel()` asks the service to stop and then kills its process. This is deliberate: Chunker has no abort API, and a
blocked conversion thread would otherwise keep a battery-draining job alive.

Because the process is killed:

- `onCancelled` is delivered before the kill if the request reaches the service in time (300 ms grace period).
- Any queued request in that process is lost; the host app should treat its own pending requests as failed.
- Leftover job directories are removed on the next service start.
- A `.part` output file may remain; delete it when you receive a failure/cancellation if you care.
- The next request may be answered with `BUSY` for up to about a second after `onCancelled`, because the old process
  is still exiting and still reports itself busy. Retry once after a short delay instead of treating that `BUSY` as
  fatal.

If the process is killed by the OS while a conversion is running, no callback is delivered. Track conversions in the
host app (for example in a `ViewModel`) and verify the output's existence when the app returns to the foreground.

## Concurrency

One conversion per process at a time. A second `convert` while one is running fails with `BUSY` — it is not queued.
The host app should serialize conversions behind its own queue.

Detection is tracked separately from conversion, so `detect` right after `onCompleted`/`onFailed` works, and a quick
`detect` followed by `convert` from inside its callback works too. A detection issued while a conversion is running
fails immediately with `BUSY` instead of waiting: the conversion may take minutes, and a queued detection would
otherwise appear to hang. Detect first, then start the conversion. `inspectLevel`/`applyLevel` use a third flag, so
a details screen can be opened from `onCompleted` as well; only a running conversion makes them answer `BUSY`.

## Error recovery

- `OUT_OF_MEMORY`: retry with `leanMode(true)`, and consider converting to a lower target version.
- `INSUFFICIENT_STORAGE`: free space on internal storage (`cacheDir`) — the output URI's storage does not matter for
  staging.
- `ARCHIVE_DAMAGED`: the archive's central directory may be truncated (common for interrupted downloads). Ask the
  user for a complete copy.
- `ENGINE_UNAVAILABLE`: usually an AAR that was processed by a packaging tool that stripped `assets/chunkbridge/**`,
  or a `DexClassLoader` denial on a heavily modified ROM. Re-install the AAR unchanged.

## Testing your integration

1. Put a real world archive on the device (a `.zip` of a world folder, or a `.mcworld`).
2. Call `detect` first and log `editionId`/`version`; it is the cheapest end-to-end check of the engine payload.
3. Convert to the opposite edition (`defaultExtension` for the file name), then convert the result back.
4. Verify the produced archive with the target game (Bedrock worlds need the `db/` directory; Java worlds need
   `level.dat`).
5. Test on API 27 (dex/minSdk floor), API 30 (legacy FGS) and API 34/35 (FGS types, notification permission,
   Android 15 quota).

The bundled `sample` app does all of the above and is the reference integration.

## Using a composite build (git submodule)

Most host apps should consume the released artifacts instead: see README → *From the GitHub release*. A composite
build is for working on the SDK and the app at the same time, or for tracking engine changes before a release.

LiteChunk does exactly that: it adds this repository as a submodule and includes the build directly:

```bash
git submodule add https://github.com/FreneticWind60/ChunkBridge.git chunkbridge
git submodule update --init --recursive
```

```kotlin
// settings.gradle.kts
val chunkbridgeDir = providers.gradleProperty("chunkbridge.sdk.dir").getOrElse("chunkbridge")
includeBuild(file(chunkbridgeDir)) {
    dependencySubstitution {
        substitute(module("com.frenetic.chunkbridge:chunkbridge")).using(project(":library"))
        substitute(module("com.frenetic.chunkbridge:api")).using(project(":api"))
    }
}
```

The host app then depends on the normal coordinates (`com.frenetic.chunkbridge:chunkbridge:1.0.1`); Gradle replaces
them with the included project, so the version string only has to be a version that exists. Working on both
repositories at once is `-Pchunkbridge.sdk.dir=../ChunkBridge` instead of a submodule checkout.

Practical notes:

- A composite build compiles the whole SDK, including the engine sources; the first build is slower than consuming
  an AAR, and `mergeEngineAssets` runs `:engine:engineDex` each time the engine sources change.
- `chunkbridge/src/main/assets/chunkbridge/engine-res/` is committed source (fonts and the height pack), so a plain
  submodule checkout is enough; nothing has to be generated before `git clone --recursive`.
- CI must check out submodules (`actions/checkout` with `submodules: recursive`, or `git submodule update --init`)
  before invoking Gradle.
- Do not add both the published AAR and the `includeBuild` — the substitution would silently lose to whichever
  appears first on the classpath and you would debug the wrong engine.

## Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| `IllegalStateException` on `formats()` | `initialize()` was not called, or `assets/chunkbridge/formats.json` was stripped. |
| Convert fails instantly with `FOREGROUND_NOT_ALLOWED` | App was in the background when `convert` was called. |
| No progress notification, conversion still works | `POST_NOTIFICATIONS` not granted (Android 13+). |
| `BUSY` unexpectedly | A previous conversion in the same app process is still running (or its callback was lost). |
| Very slow first conversion | The engine dex is being copied/loaded for the first time in the service process. |
| `VerifyError` mentioning `VarHandle` on old devices | The engine dex was not packaged by this repository's `:engine:engineDex` (guava must stay pinned). |
