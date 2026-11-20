# The sample deliberately keeps a releasing host app's rules minimal: the library ships its own
# consumer-rules.pro, and R8 must not strip the engine contract the SDK loads reflectively.
-keep class com.frenetic.litechunk.api.** { *; }
