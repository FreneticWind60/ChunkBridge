# Version

**English** | [简体中文](VERSION.zh-CN.md)

## 1.0.0

First standalone release of the SDK, split out of the LiteChunk app.

### What it is

- An Android library (`com.frenetic.chunkbridge:chunkbridge`) that converts Minecraft worlds between Java Edition and
  Bedrock Edition, with the conversion engine embedded in the AAR. The host app does not need LiteChunk or Minecraft
  installed, and no network access is used.
- A stable contract artifact (`com.frenetic.chunkbridge:api`) whose `ChunkerEngine` interface is loaded by the host
  class loader before the engine dex is attached.
- Public surface: format catalog, edition/version detection, asynchronous conversion with progress and cancellation,
  typed errors. No UI, no `level.dat` editing, no file browser, no import intents.

### Engine

- Chunker-based engine (`com.frenetic.litechunk.engine.EngineImpl`), engine version `1.21.0`, covering Java Edition
  and Bedrock Edition formats up to the newest versions known to the bundled Chunker sources (Java 26.3.0 /
  Bedrock 1.26.60 at the time of writing).
- Packaged with R8 for `min-api 27`: the engine dex and its resources are assets (`chunkbridge/engine.jar`,
  `chunkbridge/engine-res/`), loaded inside a `:chunkbridge` foreground service through a `DexClassLoader`.
- The LevelDB and LZ4 code paths are the ones used by the app; mmap is disabled on Android, and the dex is checked for
  `VarHandle` references at build time.

### Changes relative to the LiteChunk app

- New module `library` holding the public API, the `:chunkbridge` service, engine loading, archive handling and
  resources; the app no longer shares these classes.
- Engine asset paths namespaced to `chunkbridge/` and generated into `build/generated/` instead of being written into
  `src/main/assets`, so a host app's own assets cannot collide and no generated file is committed.
- New `:engine:dumpFormats` task produces `formats.json`, so `ChunkBridge.formats()` never loads the engine in the host
  process.
- The `:chunkbridge` service is a foreground service (`dataSync`) started with `startForegroundService`, one
  conversion at a time, with a progress notification and a cancel action. The app's queue model is not part of the
  SDK. Detection and conversion have separate busy flags, so a `convert` issued from `DetectionCallback` works.
- Cancellation kills the `:chunkbridge` process (the engine has no abort API); job directories under
  `cacheDir/chunkbridge/jobs` are swept on service start. The service lingers for two seconds after a job so that a
  follow-up detect/convert is not dropped by an idle stop, and `onDestroy` reports `CANCELLED` for a job it had to
  abandon.
- Output can be a content URI or an absolute `File`; files are written through `<name>.part` and renamed.
- Archive extraction is capped at 500,000 entries, 8 GiB per entry and 32 GiB total, with the same zip-slip guard as
  the app.
- Errors are reported as `ErrorCode` values instead of display strings; the engine still returns stable stage codes,
  which the SDK maps to `Stage`.
- The progress notification no longer assumes that the host has a launcher activity; a host without one (kiosk apps,
  instrumentation processes) previously crashed while building the notification.
- The `sample` module is a zero-dependency host app used for manual verification.

### Known limitations

- Android 12+ requires the foreground service to be started while the app is in the foreground; otherwise the request
  fails with `FOREGROUND_NOT_ALLOWED`.
- Android 13+ requires `POST_NOTIFICATIONS` for the progress notification to be visible. Request it even when the
  notification is not interesting to the user: some vendor ROMs (verified on Vivo OriginOS / API 36) treat a foreground
  service whose notification cannot be seen as a keep-alive abuse and kill the package within seconds
  (`am_kill ... due to WithOutCancelNoti`). For the same reason, a long conversion started while the host app is in the
  background may be killed by such ROMs; keep the host visible for the duration.
- Android 15+ caps `dataSync` foreground services at six hours per day; the SDK runs one conversion per request, so a
  large batch needs a user-visible flow and its own scheduling.
- One conversion per process at a time (`BUSY` otherwise); a detection requested during a conversion fails with `BUSY`
  immediately instead of waiting, and a request sent right after a cancellation may see `BUSY` for about a second
  while the old process exits.
- Cancellation is a process kill, so a `.part` output may be left behind and the callback is best-effort.
- No per-world progress detail beyond `Stage`; no estimated remaining time.
- LevelDB-backed Bedrock worlds are written with the bundled leveldb library; worlds produced by very new Bedrock
  versions may use formats newer than the bundled Chunker sources and are rejected by the engine.
- The SDK does not set `android:largeHeap`; the host must if it expects large worlds (the engine's buffers adapt, but
  a larger heap reduces retries).
- `:engine:roundTrip` needs external Chunker fixtures and skips cleanly when they are absent; `:engine:engineDex` is a
  `JavaExec` task, so Gradle discards its configuration cache entry (the build still succeeds).

### Verification

- `:engine:smokeTest` and `:engine:roundTrip` (with local Chunker fixtures) pass.
- `:library:testDebugUnitTest`: 8 JVM tests for `WorldPacker`, `Container` sniffing and `Worlds` lookup.
- Instrumentation (`ConversionEndToEndTest`, 5 tests: catalog, detect, non-world rejection, Java -> Bedrock -> Java
  round trip, cancel) passes repeatedly on API 27 and API 30 emulators. API 27 is the `minSdk` floor and has no
  foreground service type APIs; API 30 covers the legacy `startForeground` path.
- A host app built with R8 (`:sample:assembleRelease`) keeps `ChunkerEngine` and `SdkConversionService` (checked with
  `dexdump`), so the consumer rules work when minified.
- The AAR contains `assets/chunkbridge/{engine.jar,formats.json,engine-res/}` and no engine classes in `classes.jar`
  (the engine must only live in the dex asset).
- Real saves on a physical device (Vivo V2505A, Android 16 / API 36), run through `RealWorldConversionTest`:
  - Java 1.20.1 world, 1.4 GB zip (1,418,765,922 bytes) -> `JAVA_1.20.0`: 562 s, output 504,112,174 bytes with 302
    entries (`level.dat`, `region/*.mca`, `DIM1/`, `DIM-1/`); the written `level.dat` reports `DataVersion` 3463,
    i.e. Minecraft 1.20.0.
  - Java 26.3 world, 1.1 MB zip -> `BEDROCK_1.26.60`: 5.2 s, output 589,082 bytes containing `db/*.ldb`,
    `db/CURRENT`, `db/MANIFEST-*` and `level.dat`.
- The `dataSync` six-hour cap (Android 15+) is still documented from the platform rules, not from a device run: the
  runs above stay well under it.

### Repository layout

| Path | Contents |
| --- | --- |
| `api/` | Published contract: `ChunkerEngine`, `ConversionOptions`, `Detection`, format types. |
| `engine/` | Chunker sources, `EngineImpl`, `level.dat` editor, desktop test mains, R8 packaging. |
| `library/` | Published SDK: public API, `:chunkbridge` service, engine loader, archive handling, resources, tests. |
| `sample/` | Minimal host app used for manual checks. |
| `docs/` | `INTEGRATION.md` and `android-api-compat.md`. |

### Artifacts

- `library/build/outputs/aar/library-release.aar` — AAR with the engine payload.
- `build/repo/` — Maven repository produced by `:api:publish` / `:library:publish`.
- `~/.m2/repository/com/frenetic/chunkbridge/` — artifacts from `publishToMavenLocal`.
