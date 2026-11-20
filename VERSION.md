# Version

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
  and Bedrock Edition formats up to the newest version known to the bundled Chunker sources (Java 26.3.0 /
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
  SDK.
- Cancellation kills the `:chunkbridge` process (the engine has no abort API); job directories under
  `cacheDir/chunkbridge/jobs` are swept on service start.
- Output can be a content URI or an absolute `File`; files are written through `<name>.part` and renamed.
- Archive extraction is capped at 500,000 entries, 8 GiB per entry and 32 GiB total, with the same zip-slip guard as
  the app.
- Errors are reported as `ErrorCode` values instead of display strings; the engine still returns stable stage codes,
  which the SDK maps to `Stage`.
- The `sample` module is a zero-dependency host app used for manual verification.

### Known limitations

- Android 12+ requires the foreground service to be started while the app is in the foreground; otherwise the request
  fails with `FOREGROUND_NOT_ALLOWED`.
- Android 15+ caps `dataSync` foreground services at six hours per day; the SDK runs one conversion per request, so a
  large batch needs a user-visible flow and its own scheduling.
- One conversion per process at a time (`BUSY` otherwise); detection waits behind a running conversion.
- Cancellation is a process kill, so a `.part` output may be left behind and the callback is best-effort.
- No per-world progress detail beyond `Stage`; no estimated remaining time.
- LevelDB-backed Bedrock worlds are written with the bundled leveldb library; worlds produced by very new Bedrock
  versions may use formats newer than the bundled Chunker sources and are rejected by the engine.
- The SDK does not set `android:largeHeap`; the host must if it expects large worlds (the engine's buffers adapt, but
  a larger heap reduces retries).
- `:engine:roundTrip` needs external Chunker fixtures and skips cleanly when they are absent; `:engine:engineDex` is a
  `JavaExec` task, so Gradle discards its configuration cache entry (the build still succeeds).
