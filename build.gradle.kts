import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    java
    id("io.github.goooler.shadow") version "8.1.8"
}

group = property("group") as String
version = property("version") as String
description = "High-performance RTP plugin integrated with EndSectors for Paper 1.21.4"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc" }
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/") { name = "placeholderapi" }
    maven("https://repo.codemc.io/repository/maven-public/") { name = "codemc" }
    maven("https://repo.dmulloy2.net/repository/public/") { name = "protocollib" }
    maven("https://oss.sonatype.org/content/repositories/snapshots/") { name = "sonatype-snapshots" }
    maven("https://jitpack.io") { name = "jitpack" }
}

dependencies {
    // Paper API (1.21.4)
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")

    // PlaceholderAPI (optional integration)
    compileOnly("me.clip:placeholderapi:2.11.6")

    // ProtocolLib (only needed because EndSectors uses it transitively)
    compileOnly("com.comphenix.protocol:ProtocolLib:5.3.0")

    // EndSectors – there is no public artifact, ship the compiled jar in libs/ or use jitpack.
    // The plugin only depends on it softly at runtime; reflection is used as a fallback.
    compileOnly(fileTree("libs") { include("*.jar") })

    // Lombok
    compileOnly("org.projectlombok:lombok:1.18.34")
    annotationProcessor("org.projectlombok:lombok:1.18.34")

    // bStats (shaded)
    implementation("org.bstats:bstats-bukkit:3.1.0")

    // Adventure MiniMessage is bundled in Paper – use compileOnly to avoid duplicate classpath
    compileOnly("net.kyori:adventure-text-minimessage:4.17.0")
    compileOnly("net.kyori:adventure-platform-bukkit:4.3.4")
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }
    processResources {
        val props = mapOf(
            "version" to project.version,
            "name" to project.name,
            "description" to project.description
        )
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
    shadowJar {
        archiveClassifier.set("")
        archiveFileName.set("${project.name}-${project.version}.jar")
        relocate("org.bstats", "pl.maksios.sectorrtp.libs.bstats")
        minimize {
            exclude(dependency("org.bstats:.*"))
        }
        mergeServiceFiles()
    }
    build {
        dependsOn(shadowJar)
    }
}
