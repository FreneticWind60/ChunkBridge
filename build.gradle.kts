plugins {
    id("com.android.application") version "9.4.1" apply false
    id("com.android.library") version "9.4.1" apply false
}

// Every publishable module shares one SDK version. The engine keeps its own version (reported by
// ChunkBridge.engineVersion()) so the two can move independently.
subprojects {
    group = "com.frenetic.chunkbridge"
    version = providers.gradleProperty("SDK_VERSION").get()
}
