# The engine dex is loaded by name through a DexClassLoader, so R8 in a host app must not touch these.
-keep class com.frenetic.litechunk.api.** { *; }
-keep class com.frenetic.litechunk.engine.EngineImpl { *; }
-keep class com.frenetic.litechunk.sdk.internal.SdkConversionService { *; }

# ResultReceiver and the request/result bundles cross a process boundary by name only, but the enum
# ordinals travel as strings, so nothing reflective needs keeping there. Keep public SDK entry points
# reachable from the host's manifest and code.
-keep class com.frenetic.litechunk.sdk.ChunkBridge { public *; }
-keep class com.frenetic.litechunk.sdk.ChunkBridgeException { *; }
