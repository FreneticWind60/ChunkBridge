# ChunkBridge SDK

[English](README.md) | **简体中文**

在任意 Android 应用里，把 Minecraft 世界在 **Java 版与基岩版之间** 互相转换。

ChunkBridge 将 [Chunker](https://github.com/HiveGamesOSS/Chunker) 转换引擎封装为一个自包含的 Android 库。宿主应用
不需要安装 LiteChunk，不需要安装 Minecraft，也不需要联网：引擎 dex 与资源都打进 AAR（约 1.7 MB）。

```java
ChunkBridge.initialize(context);

ChunkBridge.convert(
        new ConversionRequest.Builder()
                .inputUri(inputUri)              // 世界文件夹、.zip 或 .mcworld
                .outputUri(outputUri)            // 由 ACTION_CREATE_DOCUMENT 创建，或使用 File
                .targetFormatId("BEDROCK_1.26.60")
                .build(),
        new ConversionCallback() {
            @Override public void onProgress(double fraction, Stage stage) { /* 0.0 .. 1.0 */ }

            @Override public void onCompleted(ConversionResult result) { /* 输出已写好 */ }

            @Override public void onFailed(ChunkBridgeException error) { /* error.getCode() */ }
        });
```

## SDK 能做什么

| 能力 | 入口 |
| --- | --- |
| 列出所有支持的版本 | `ChunkBridge.formats()`、`ChunkBridge.formatGroup(id)` |
| 检测世界或压缩包的版本 | `ChunkBridge.detect(File, DetectionCallback)`、`ChunkBridge.detect(Uri, DetectionCallback)` |
| 把世界文件夹 / `.zip` / `.mcworld` 转成新压缩包 | `ChunkBridge.convert(ConversionRequest, ConversionCallback)` |
| 取消正在进行的转换 | `ConversionHandle.cancel()` |

全部在设备本地完成。语言文件、方块映射和 Java 字体度量都已内置，转换过程不访问网络。

引擎支持到所捆绑 Chunker 源码已知的最新版本（撰写时为 Java 26.3.0 / 基岩 1.26.60）。权威列表请调用
`ChunkBridge.formats()`。

## SDK 不做什么

- 不检查或修改 `level.dat`，没有世界设置界面，没有文件浏览器，不处理导入 Intent，不提供分享面板。
- 除引擎自身携带的数据外不额外处理玩家数据（实体默认会转换，可用 `convertEntities(false)` 跳过）。

## 环境要求

| | |
| --- | --- |
| `minSdk` | 27（Android 8.1） |
| `compileSdk` | 35 或更高 |
| Java | 17（库以 Java 17 字节码编译） |
| 宿主构建 | AGP 8.x 或更高 |

转换本身不需要宿主进程加大堆：引擎运行在独立的 `:chunkbridge` 进程中。不过仍建议宿主开启大堆，以便转换期间其余
界面保持健康。

## 安装

### 从本地 Maven 仓库

```bash
# 在 SDK 仓库目录
./gradlew :api:publishToMavenLocal :library:publishReleasePublicationToMavenLocal
```

```kotlin
// settings.gradle.kts（或 build.gradle.kts 里的 repositories）
mavenLocal()

// app/build.gradle.kts
dependencies {
    implementation("com.frenetic.chunkbridge:chunkbridge:1.0.1")
    // 会通过传递依赖引入，这里只是显式列出
    // implementation("com.frenetic.chunkbridge:api:1.0.1")
}
```

### 从已发布的仓库

```bash
./gradlew :api:publish :library:publish                    # 输出到 build/repo
./gradlew :api:publishToMavenLocal :library:publishReleasePublicationToMavenLocal
```

把仓库指向生成的 `build/repo`，或把产物拷进你自己的仓库。

### 只用一个 AAR 文件

`library/build/outputs/aar/library-release.aar` 内含 SDK 类、Android 资源和引擎负载。把它拷进 `libs/` 的同时
**必须** 一并加入 `api` 产物，因为引擎 dex 挂载前，`ChunkerEngine` 必须已存在于宿主的类加载器中：

```kotlin
dependencies {
    implementation(files("libs/library-release.aar"))
    implementation(files("libs/api-1.0.1.jar"))   // com.frenetic.chunkbridge:api:1.0.1
}
```

### 以 git submodule 接入（复合构建）

LiteChunk 自己就是这么用的：SDK 仓库作为 submodule 放在 `chunkbridge/`，Gradle 直接从源码构建，改引擎不需要
先发布 AAR。

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
// app/build.gradle.kts —— 版本号会被忽略，复合构建里的工程优先
dependencies {
    implementation("com.frenetic.chunkbridge:chunkbridge:1.0.1")
}
```

同时开发两个仓库时，把 submodule 路径做成可覆盖的，避免必须把 SDK 放在 `chunkbridge/`：

```kotlin
// settings.gradle.kts
val chunkbridgeDir = providers.gradleProperty("chunkbridge.sdk.dir").getOrElse("chunkbridge")
includeBuild(file(chunkbridgeDir)) { /* 上面的替换规则 */ }
```

```bash
./gradlew :app:assembleDebug -Pchunkbridge.sdk.dir=../ChunkBridge
```

复合构建需要机器上有 SDK 源码，不能只用 AAR；CI 也必须先 clone（或更新 submodule）再构建 App。

## 用法

### 1. 初始化

每个进程调用一次，放在 `Application.onCreate()` 或首次调用 SDK 之前：

```java
ChunkBridge.initialize(getApplicationContext());
```

这一步只是把引擎 dex 和资源准备好；真正的引擎会在服务进程首次使用时才加载。

### 2. 选择目标版本

```java
for (FormatGroup group : ChunkBridge.formats()) {          // "JAVA"、"BEDROCK"
    for (FormatVersion version : group.getVersions()) {    // 新版本在前
        Log.i("ChunkBridge", version.getDisplayName() + " -> " + version.getFormatId());
    }
}
```

`ChunkBridge.formats()` 读取的是内置目录，同步、开销极小，可在主线程调用。

### 3. 检测（可选）

```java
ChunkBridge.detect(inputUri, (result, error) -> {
    if (result != null) {
        Log.i("ChunkBridge", result.getEditionLabel() + " " + result.getVersion());
    }
});
```

检测支持世界文件夹、`.zip`、`.mcworld`、`.rar`、`.7z`、`.tar` 和 `.gz`。检测需要加载引擎，因此该调用在服务进程
中执行并异步回调。

### 4. 转换

```java
ConversionHandle handle = ChunkBridge.convert(request, callback);
// 之后可在任意线程
handle.cancel();
```

`ConversionRequest.Builder` 必须各指定一个输入和一个输出：

- `inputFile(File)` —— 世界文件夹，或压缩包文件。
- `inputUri(Uri)` —— content URI，通常来自 `ACTION_OPEN_DOCUMENT`。
- `outputFile(File)` —— 绝对路径，父目录必须已存在。
- `outputUri(Uri)` —— 由服务以 `"wt"` 模式打开的 content URI。

可选项：`targetFormatId(String)`（必填）、`convertEntities(boolean)`（默认 `true`）、`leanMode(boolean)`
（低内存设备使用更小的缓冲区）、`overwriteExisting(boolean)`（仅对 `outputFile` 生效）、
`deleteSourceArchiveAfterSuccess(boolean)`（仅对压缩包输入生效）、`notificationTitle`、`notificationText`。

回调在主线程投递。`onCompleted` 提供输出大小（`getOutputBytes()`）、实际目标版本（`getTargetVersion()`）和
耗时（`getElapsedMs()`）。

### Stage

`Stage` 表示引擎当前所处的阶段：`TERRAIN`、`ENTITIES`、`COMPACT`、`FINISH`、`UNKNOWN`。`onProgress` 的进度值
单调递增但并非线性，不要假设每个阶段耗时固定。

### 取消

`cancel()` 会结束 `:chunkbridge` 进程，这是中断引擎的唯一方式。若请求到达时服务仍存活，回调会收到 `onCancelled`；
写到一半的输出（`*.part`）会在下次服务启动时清理。取消是尽力而为的：如果转换已经进入最后的打包步骤，仍可能
完成。回调之后进程还会存活片刻，因此紧接着发出的请求可能仍收到 `BUSY`，重试一次即可，不必当作致命错误。

### 5. 读取与修改 level.dat

存档详情页用这两个调用。它们同样跑在 `:chunkbridge` 进程，不需要通知、不可取消；读取只需几毫秒。

```java
ChunkBridge.inspectLevel(worldDirectory, (info, error) -> {
    if (error != null) return;                 // 目录里没有 level.dat 时是 WORLD_NOT_FOUND
    for (LevelField field : info.fields) {
        // field.id 是 NBT 键；field.kind（KIND_TEXT/KIND_INT/...）决定怎么渲染
    }
});

Map<String, String> changes = new HashMap<>();
changes.put("LevelName", "新名字");
ChunkBridge.applyLevel(worldDirectory, changes, (info, error) -> {
    // info 是写入后重新读取的结果，页面可以直接刷新，不必再调一次
});
```

`worldDirectory` 必须是含 `level.dat` 的目录；从 `.zip` / `.mcworld` 里取出、改完再写回压缩包由调用方负责
（LiteChunk 用 `Archive.rewrite`）。值一律是字符串，因为引擎只暴露一份允许键列表，并按游戏期望的类型写入。

## 错误码

`ChunkBridgeException.getCode()` 返回 `ErrorCode`：

| 错误码 | 含义 |
| --- | --- |
| `INPUT_UNREADABLE` | 输入不存在、不可读，或 URI 授权已失效。 |
| `ARCHIVE_UNSUPPORTED` | 不支持的压缩格式（见上文列表）。 |
| `ARCHIVE_DAMAGED` | 压缩包损坏，或使用了不支持的压缩特性。 |
| `WORLD_NOT_FOUND` | 输入在三层目录内都找不到 `level.dat` 或基岩版 `db/`。 |
| `FORMAT_UNSUPPORTED` | `targetFormatId` 不在 `ChunkBridge.formats()` 中。 |
| `OUTPUT_UNWRITABLE` | 输出 URI/文件无法创建，或路径不是绝对路径。 |
| `OUTPUT_EXISTS` | `outputFile` 已存在且 `overwriteExisting(false)`。 |
| `INSUFFICIENT_STORAGE` | 写入时设备空间不足。 |
| `OUT_OF_MEMORY` | 转换进程堆内存耗尽。 |
| `BUSY` | 已有转换在进行，或在转换期间请求了检测。结束后重试。 |
| `CANCELLED` | 请求被取消。 |
| `ENGINE_UNAVAILABLE` | 引擎 dex 无法加载（通常是 AAR 损坏或被裁剪）。 |
| `FOREGROUND_NOT_ALLOWED` | Android 拒绝启动前台服务（见下文）。 |
| `INTERNAL` | 其他错误；`getDetail()` 携带引擎信息。 |

## 前台服务规则（重要）

转换运行在 `:chunkbridge` 进程的 `dataSync` 前台服务中。Android 12+ 要求调用 `ChunkBridge.convert(...)` 时宿主
应用处于前台——例如在 Activity 回调里调用，而不是后台或 `BroadcastReceiver` 中。否则 `convert` 会立即以
`FOREGROUND_NOT_ALLOWED` 失败并通过回调返回。

Android 15+ 对 `dataSync` 前台服务有每天 6 小时的配额。单次转换耗时都不长，只有超大批量处理才会碰到；长转换
仍建议让界面保持可见。

Android 13+ 若想让进度通知可见，需要在转换前申请 `POST_NOTIFICATIONS`。即使并不在意通知也建议申请：部分厂商
ROM（已实机验证 vivo OriginOS）会把“通知不可见的前台服务”判为保活滥用，在数秒内杀掉进程。

## 限制

- 每个进程同一时间只做一次转换，并发请求返回 `BUSY`。
- 解压设有上限：50 万条目、单条目 8 GiB、总计 32 GiB，用于阻挡解压炸弹。
- 压缩包大于 `cacheDir` 可用空间时无法落盘暂存；写入前会检查剩余空间。

## 验证情况

SDK 在桌面、模拟器和真机上都有验证。要点：

- `:engine:smokeTest` 与 `:engine:roundTrip` 会把 Java 1.20.5 存档转成基岩版再转回来。
- `:library:testDebugUnitTest` 覆盖压缩包打包、容器魔数识别和世界根目录查找。
- `ConversionEndToEndTest`（目录、检测、非世界文件拒绝、Java -> 基岩 -> Java、取消）在 API 27 与 API 30 上
  反复通过。
- `RealWorldConversionTest` 在 vivo V2505A（Android 16 / API 36，开发者的主力机（穷比大学生哭哭））上：1.4 GB 的 Java 1.20.1 世界用 562 秒
  转成 `JAVA_1.20.0`；Java 26.3 世界用 5.2 秒转成 `BEDROCK_1.26.60`；写出的 `level.dat` 的 `DataVersion`
  正确，压缩包也包含应有的 `region/` 与 `db/` 结构。

完整报告与已知限制见 [VERSION.zh-CN.md](VERSION.zh-CN.md)。

## 构建本仓库

```bash
export ANDROID_HOME=/path/to/Android/Sdk     # 或写 local.properties 的 sdk.dir=...
./gradlew :library:assembleDebug            # debug AAR
./gradlew :sample:assembleDebug             # 示例宿主应用
./gradlew :library:assembleRelease :library:publishReleasePublicationToMavenLocal
```

`:library:assembleRelease` 会先跑 `:engine:engineDex`（R8）和 `:engine:dumpFormats`，生成
`build/generated/engineAssets/chunkbridge/engine.jar` 与
`build/generated/engineFormats/chunkbridge/formats.json`。这些文件不会提交。R8 需要 `ANDROID_HOME`/
`ANDROID_SDK_ROOT` 或 `local.properties`，会选用最新的 `platforms/android-*` 和 `build-tools/*/lib/d8.jar`；
若 dex 里仍引用 `VarHandle` 相关类（API 33 以下会崩），构建直接失败。

桌面引擎自检（不需要设备）：

```bash
./gradlew :engine:smokeTest
./gradlew :engine:roundTrip   # 需要 Chunker 的 fixture 存档；缺失时会干净跳过
```

`:engine:roundTrip` 会依次查找 `../Chunker-main/cli/src/test/resources/integration/worlds`、
`../../lib/Chunker-main/...`，或 `-Dchunker.fixtures=<dir>`。它做的是 Java 1.20.5 -> 基岩 -> Java 往返。

instrumentation 测试需要连接设备或模拟器：

```bash
./gradlew :library:connectedDebugAndroidTest
```

`RealWorldConversionTest` 从应用外部文件目录（`/sdcard/Android/data/<测试包名>/files/`）读取存档，缺失时自动
跳过，因此玩家存档永远不会进入仓库。

配置缓存：`:engine:engineDex` 是 `JavaExec` 任务，Gradle 会丢弃其配置缓存条目，但构建仍然成功。想要干净的日志
可以加 `--no-configuration-cache`。

## 模块

| 模块 | 内容 |
| --- | --- |
| `api` | `ChunkerEngine` 契约与小值类型。单独发布为 `com.frenetic.chunkbridge:api`。 |
| `engine` | Chunker 源码 + `EngineImpl`、桌面测试 main、R8 打包。不发布。 |
| `library` | SDK 本体：公开 API、`:chunkbridge` 服务、引擎加载、压缩包处理、资源。发布为 `com.frenetic.chunkbridge:chunkbridge`。 |
| `sample` | 极简宿主示例（SAF 选择器、版本列表、检测、转换、取消、日志）。不发布。 |

## 许可

MIT。转换引擎为 Chunker（MIT，Hive Games）；压缩包解压使用 Arcana（Apache-2.0）。详见
[THIRD-PARTY.md](THIRD-PARTY.md) 与 [LICENSE](LICENSE)。

## 更多文档

- [docs/INTEGRATION.md](docs/INTEGRATION.md) —— 集成细节、线程模型、存储布局、失败模式。
- [docs/android-api-compat.md](docs/android-api-compat.md) —— 引擎在 API 27 上必须避开的 Java 9+ API。
- [VERSION.zh-CN.md](VERSION.zh-CN.md) —— 版本说明、验证矩阵与已知限制。
