# 版本

[English](VERSION.md) | **简体中文**

## 1.0.1

补上存档详情所需的 level.dat 读写接口。LiteChunk 以前直接调引擎做这件事，现在可以直接去掉引擎模块、只留 SDK。

### 变化

- 新增 `ChunkBridge.inspectLevel(File, LevelCallback)` 与
  `ChunkBridge.applyLevel(File, Map<String, String>, LevelCallback)`，在 `:chunkbridge` 进程执行，原样返回引擎的
  `LevelInfo`（`LevelField`、`LevelNote`）。
- level 读写有独立的占用标志（`editing`）：转换进行中仍回 `BUSY`，但可以在 `LevelCallback` 里直接发起转换，
  反过来也一样——详情页正是这样用的。
- `LevelInfo` 以 `Bundle` 跨进程传递（`internal/LevelBundle`），因为 `api` 是纯 Java 库，不能实现 `Parcelable`。
- 没有删除任何公开 API；1.0.1 与 1.0.0 源码、二进制兼容。引擎负载没变（仍是 `1.21.0`），AAR 只多了这几个门面方法。

### 接入方式

README 里写了两种：发布 AAR / `mavenLocal`，或 git submodule + `includeBuild`——LiteChunk 用的是后者，
不需要任何发布步骤。

## 1.0.0

SDK 的首个独立版本，从 LiteChunk 应用中拆分而来。

### 这是什么

- 一个 Android 库（`com.frenetic.chunkbridge:chunkbridge`），把 Minecraft 世界在 Java 版与基岩版之间互转，转换引擎
  直接内嵌在 AAR 中。宿主应用无需安装 LiteChunk 或 Minecraft，也不使用网络。
- 一个稳定的契约产物（`com.frenetic.chunkbridge:api`）：`ChunkerEngine` 接口必须先由宿主类加载器加载，之后才会
  挂载引擎 dex。
- 公开能力：版本目录、版本检测、带进度与取消的异步转换、类型化错误。不含 UI，不含 `level.dat` 编辑，不含文件
  浏览器，不处理导入 Intent。

### 引擎

- 基于 Chunker 的引擎（`com.frenetic.litechunk.engine.EngineImpl`），引擎版本 `1.21.0`，覆盖所捆绑 Chunker 源码
  已知的最新 Java 版与基岩版版本（撰写时为 Java 26.3.0 / 基岩 1.26.60）。
- 使用 R8 按 `min-api 27` 打包：引擎 dex 及其资源作为 asset（`chunkbridge/engine.jar`、
  `chunkbridge/engine-res/`），通过 `DexClassLoader` 加载到 `:chunkbridge` 前台服务进程中。
- LevelDB 与 LZ4 使用与应用相同的代码路径；Android 上关闭 mmap，并在构建时检查 dex 是否仍引用 `VarHandle`。

### 相对 LiteChunk 应用的变化

- 新增 `library` 模块，承载公开 API、`:chunkbridge` 服务、引擎加载、压缩包处理与资源；应用不再共享这些类。
- 引擎 asset 路径加上 `chunkbridge/` 前缀，并生成到 `build/generated/` 而不是写进 `src/main/assets`，避免与宿主
  应用的 asset 冲突，也不会有生成文件被提交。
- 新增 `:engine:dumpFormats` 任务生成 `formats.json`，因此 `ChunkBridge.formats()` 不会在宿主进程中加载引擎。
- `:chunkbridge` 是 `dataSync` 前台服务，通过 `startForegroundService` 启动，一次只做一个转换，带进度通知和取消
  按钮。应用里的队列模型不属于 SDK。检测与转换使用各自独立的忙标志，因此在 `DetectionCallback` 里直接发起
  `convert` 可以成功。
- 取消即结束 `:chunkbridge` 进程（引擎没有中止 API）；`cacheDir/chunkbridge/jobs` 下的作业目录会在服务启动时
  清理。作业结束后服务会多存活两秒，避免紧随其后的检测/转换被空闲停机丢掉；`onDestroy` 会为被迫放弃的作业
  回报 `CANCELLED`。
- 输出可以是 content URI 或绝对路径 `File`；文件先写 `<name>.part` 再重命名。
- 解压上限为 50 万条目、单条目 8 GiB、总计 32 GiB，并保留与应用相同的 zip-slip 防护。
- 错误以 `ErrorCode` 而非展示字符串返回；引擎仍返回稳定的阶段码，由 SDK 映射成 `Stage`。
- 进度通知不再假设宿主存在 launcher Activity；此前没有 launcher 的宿主（kiosk 应用、instrumentation 进程）在
  构造通知时会崩溃。
- `sample` 是一个零依赖的宿主示例应用，用于手动验证。

### 已知限制

- Android 12+ 要求在前台启动前台服务，否则请求会以 `FOREGROUND_NOT_ALLOWED` 失败。
- Android 13+ 需要 `POST_NOTIFICATIONS` 权限，进度通知才可见。即使并不在意通知也建议申请：部分厂商 ROM
  （已在 vivo OriginOS / API 36 上验证）会把“通知不可见的前台服务”判为保活滥用，数秒内杀掉包
  （`am_kill ... due to WithOutCancelNoti`）。同理，应用在后台时开始的长转换可能被这类 ROM 杀掉，转换期间请
  保持宿主可见。
- Android 15+ 对 `dataSync` 前台服务限制每天 6 小时；SDK 每个请求只跑一次转换，超大批量需要自己的可见流程与
  调度。
- 每个进程同一时间只做一个转换（否则 `BUSY`）；转换期间发起的检测会立即返回 `BUSY` 而不是排队；取消之后紧接着
  发出的请求可能约一秒内仍看到 `BUSY`，因为旧进程尚未退出。
- 取消是杀进程，可能留下 `.part` 输出，回调也是尽力而为。
- 除 `Stage` 外没有更细的世界级进度，也没有剩余时间预估。
- 基于 LevelDB 的基岩版世界由捆绑的 leveldb 库写出；更新的基岩版可能使用比捆绑 Chunker 源码更新的格式，会被
  引擎拒绝。
- SDK 不设置 `android:largeHeap`；如果预期处理大世界，需要宿主自行开启（引擎缓冲区会自适应，但更大的堆可以
  减少重试）。
- `:engine:roundTrip` 需要外部 Chunker fixture，缺失时会干净跳过；`:engine:engineDex` 是 `JavaExec` 任务，
  Gradle 会丢弃其配置缓存条目（构建仍然成功）。

### 验证情况

- `:engine:smokeTest` 与 `:engine:roundTrip`（配合本地 Chunker fixture）通过。
- `:library:testDebugUnitTest`：8 个 JVM 测试，覆盖 `WorldPacker`、`Container` 魔数识别与 `Worlds` 查找。
- Instrumentation（`ConversionEndToEndTest`，5 个用例：目录、检测、非世界文件拒绝、Java -> 基岩 -> Java 往返、
  取消）在 API 27 与 API 30 模拟器上反复通过。API 27 是 `minSdk` 下限，没有前台服务类型 API；API 30 覆盖旧的
  `startForeground` 路径。
- 用 R8 构建的宿主应用（`:sample:assembleRelease`）仍保留 `ChunkerEngine` 与 `SdkConversionService`（用
  `dexdump` 检查），说明 consumer 规则在混淆下有效。
- AAR 内含 `assets/chunkbridge/{engine.jar,formats.json,engine-res/}`，且 `classes.jar` 中没有引擎类（引擎只能
  存在于 dex asset 中）。
- 实机真实存档（vivo V2505A，Android 16 / API 36），通过 `RealWorldConversionTest` 验证：
  - Java 1.20.1 世界，1.4 GB 压缩包（1,418,765,922 字节）-> `JAVA_1.20.0`：562 秒，输出 504,112,174 字节、
    302 个条目（`level.dat`、`region/*.mca`、`DIM1/`、`DIM-1/`）；写出的 `level.dat` 的 `DataVersion` 为
    3463，即 Minecraft 1.20.0。
  - Java 26.3 世界，1.1 MB 压缩包 -> `BEDROCK_1.26.60`：5.2 秒，输出 589,082 字节，含 `db/*.ldb`、
    `db/CURRENT`、`db/MANIFEST-*` 与 `level.dat`。
- `dataSync` 每天 6 小时配额（Android 15+）仍来自平台文档而非实机验证：上述运行远未触及该上限。

### 仓库结构

| 路径 | 内容 |
| --- | --- |
| `api/` | 对外契约：`ChunkerEngine`、`ConversionOptions`、`Detection`、格式类型。 |
| `engine/` | Chunker 源码、`EngineImpl`、`level.dat` 编辑器、桌面测试 main、R8 打包。 |
| `library/` | 对外 SDK：公开 API、`:chunkbridge` 服务、引擎加载、压缩包处理、资源、测试。 |
| `sample/` | 极简宿主示例，用于手动验证。 |
| `docs/` | `INTEGRATION.md` 与 `android-api-compat.md`。 |

### 产物

- `library/build/outputs/aar/library-release.aar` —— 含引擎负载的 AAR。
- `build/repo/` —— `:api:publish` / `:library:publish` 生成的 Maven 仓库。
- `~/.m2/repository/com/frenetic/chunkbridge/` —— `publishToMavenLocal` 的产物。
