import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

plugins {
    id("com.android.library")
    id("maven-publish")
}

val sdkVersion: String = providers.gradleProperty("SDK_VERSION").get()

/**
 * Merges the asset directories produced by :engine:engineDex and :engine:dumpFormats into the one directory
 * AGP expects for a variant's generated assets.
 *
 * <p>They cannot be written into src/main/assets directly: Gradle 9 rejects a task in another project claiming an
 * output inside this project's source tree ("implicit dependency" validation). Publishing them through
 * [com.android.build.api.variant.SourceDirectories.Flat.addGeneratedSourceDirectory] also makes the task order
 * explicit for every consumer (merge, lint, AAR).
 */
abstract class MergeEngineAssets : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun merge() {
        val target = outputDirectory.get().asFile
        target.deleteRecursively()
        target.mkdirs()
        for (source in sources.files) {
            if (!source.isDirectory) {
                continue
            }
            source.walkTopDown().forEach { file ->
                if (file.isDirectory) {
                    return@forEach
                }
                val destination = File(target, file.relativeTo(source).path)
                destination.parentFile?.mkdirs()
                file.copyTo(destination, overwrite = true)
            }
        }
    }
}

android {
    namespace = "com.frenetic.litechunk.sdk"
    compileSdk = 37

    defaultConfig {
        minSdk = 27
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SDK_VERSION", "\"$sdkVersion\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// The engine payload is generated per variant: chunkbridge/engine.jar (R8 dex) and chunkbridge/formats.json.
// chunkbridge/engine-res/ is a committed source asset next to them in src/main/assets.
androidComponents.onVariants { variant ->
    val mergeAssets = tasks.register<MergeEngineAssets>(
        "mergeEngineAssets${variant.name.replaceFirstChar { it.uppercase() }}",
    ) {
        description = "Adds the generated engine payload to the ${variant.name} assets."
        dependsOn(":engine:engineDex", ":engine:dumpFormats")
        sources.from(
            rootProject.layout.buildDirectory.dir("generated/engineAssets"),
            rootProject.layout.buildDirectory.dir("generated/engineFormats"),
        )
    }
    variant.sources.assets?.addGeneratedSourceDirectory(mergeAssets, MergeEngineAssets::outputDirectory)
}

dependencies {
    // The engine dex is loaded by a DexClassLoader whose parent is the host ClassLoader. That parent must already
    // contain ChunkerEngine or the cast after Class.forName would fail. :api is therefore a real `api` dependency
    // (it ends up in the consumer's classes.dex) and is published to Maven next to this artifact.
    api(project(":api"))

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "chunkbridge"
                pom {
                    name.set("ChunkBridge SDK")
                    description.set("Java <=> Bedrock Minecraft world conversion for Android apps.")
                    url.set("https://github.com/LiteChunk/LiteChunk")
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                }
            }
        }
        repositories {
            maven {
                name = "localBuild"
                url = uri(rootProject.layout.buildDirectory.dir("repo"))
            }
        }
    }
}
