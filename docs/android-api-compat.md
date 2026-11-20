# Android 版本兼容：引擎里不能再出现的 Java 9+ 方法

`minSdk = 27`，所以引擎（以及它带进来的 LevelDB、Guava）只能调用 API 27 就有的方法。
R8 会替我们处理一部分，但**不是全部**，而漏掉的那些不会在编译或打包时报错——只会在真机上
`NoSuchMethodError`，而且会被任务调度器盖成一句 `Unable to free task which has not started`（见下）。

## R8 会替我们降级的

`List.of` / `Set.of` / `Map.of` 这类 Java 9 的集合工厂方法，R8 在 `--min-api 27` 下会改写成
`Arrays.asList` 或 `ImmutableList.of` 之类。已用 dex 反汇编确认（源码里有 1200+ 处 `List.of`，
dex 里一条 `Ljava/util/List;->of` 都没有）。

## R8 不会处理、必须自己改的

| 方法 | 语言版本 | 用在哪 | 改成 |
| --- | --- | --- | --- |
| `List.toArray(IntFunction)` | Java 11 | `Task.asyncForEach`、`TagType` | `list.toArray(generator.apply(0))` |
| `Stream.toList()` | Java 16 | 物品组件解析（染色、药水颜色） | `collect(Collectors.toList())` |
| `String.formatted(...)` | Java 15 | `MCAReader`、`ByteArrayReader` 的异常信息 | `String.format(...)` |
| `StringBuilder.isEmpty()` | Java 15 | `FontUtil`、`ChunkerBlockIdentifier` | `length() == 0` |
| `String.repeat(n)` / `isBlank()` | Java 11 | `TrackedTask.getDetailedProgress`、花盆方块实体 | 手写循环 / `trim().isEmpty()` |
| `Inflater` / `Deflater` 的 `ByteBuffer` 重载 | Java 11 | leveldb 的 `ZLib` | 换成同包的本地实现（见下） |
| `Type.getTypeName()` | Java 8（Android **API 28** 才有） | `compat/Types.Impl` | 自己拼字符串 |

## leveldb 的 `ZLib`

`org.iq80.leveldb.util.ZLib` 是 jar 里唯一走 `Inflater.setInput(ByteBuffer)` 的类，改不了源码，所以和
`ByteBufferSupport` 一样在 `engineDex` 打包时从 jar 里**剔除**，由 `engine/src/main/java/org/iq80/leveldb/util/ZLib.java`
顶替（同包同名，语义照抄上游：线程本地复用、初始容量为输入的两倍、返回已 flip 的 buffer）。

## 怎么防止再犯

`docs/perf-tools/` 之外还有个一次性脚本思路：把 `dexdump -d` 的输出与装好的 API 27 平台库
（`sdkmanager "platforms;android-27"`）逐条比对方法引用。当前只剩两条，都是安全的：

- `MethodHandles.Lookup.findVarHandle` —— Guava `AbstractFuture` 里被 try/catch 兜住的快速路径；
- `MappedByteBuffer.duplicate()` —— leveldb 的 mmap 实现，Android 上 `leveldb.mmap=false` 后不会执行。

## 为什么错误信息完全误导

`WrappedTask.setupFuture` 在 future 上挂了个 `handle`，完成时调 `free()`。上游任务是异常结束时，
`thenApply` 不会执行任务本身，任务状态还停在 `PENDING`，`free()` 于是抛
`IllegalArgumentException: Unable to free task which has not started`——**原始异常被这个新异常顶掉了**，
日志里只剩这一句。现在 `TrackedTask.free()` 对 `PENDING` 直接返回（没跑过的任务没有资源要释放），
真实异常才会浮出来。上面那串 API 问题就是这么找到的。
