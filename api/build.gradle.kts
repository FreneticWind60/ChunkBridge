plugins {
    `java-library`
    `maven-publish`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "api"
            pom {
                name.set("ChunkBridge engine API")
                description.set("Stable contract between the ChunkBridge SDK and its dynamically loaded engine.")
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
