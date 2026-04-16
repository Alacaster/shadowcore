import java.util.Properties

plugins {
    java
    id("xyz.jpenilla.run-paper") version "2.3.1"
}

group = "dev.shadowcore"
version = "2.0.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(24))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.dmulloy2.net/repository/public/")
}

val protocolLibRuntime by configurations.creating {
    isTransitive = false
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.comphenix.protocol:ProtocolLib:+")
    protocolLibRuntime("com.comphenix.protocol:ProtocolLib:+")
    // SQLite JDBC is bundled with Paper's runtime — no need to shade it.
    // com.mojang.authlib is NOT imported directly — we use Paper's PlayerProfile API
    // which internally handles authlib version differences.
}

val runServerPluginsDir = layout.buildDirectory.dir("run/plugins")
val runServerDir = layout.buildDirectory.dir("run")

fun ensureRunServerConfiguration(runDir: java.io.File) {
    runDir.mkdirs()

    val eulaFile = runDir.resolve("eula.txt")
    eulaFile.writeText("eula=true\n")

    val propertiesFile = runDir.resolve("server.properties")
    val properties = Properties()
    if (propertiesFile.exists()) {
        propertiesFile.inputStream().use { input ->
            properties.load(input)
        }
    }
    properties["view-distance"] = "2"
    properties["simulation-distance"] = "2"
    propertiesFile.outputStream().use { output ->
        properties.store(output, "Managed by Gradle runServer task")
    }
}

val installProtocolLibForRunServer by tasks.registering {
    group = "run paper"
    description = "Installs the most recent ProtocolLib release for local runServer if missing."

    val protocolLibJar = protocolLibRuntime.elements.map { files ->
        files
            .map { it.asFile }
            .first { it.name.startsWith("ProtocolLib") && it.extension == "jar" }
    }
    inputs.files(protocolLibRuntime)
    outputs.dir(runServerPluginsDir)

    doLast {
        val pluginsDir = runServerPluginsDir.get().asFile
        pluginsDir.mkdirs()

        val hasProtocolLib = pluginsDir
            .listFiles()
            ?.any { it.isFile && it.name.startsWith("ProtocolLib") && it.extension == "jar" }
            ?: false

        if (hasProtocolLib) {
            logger.lifecycle("ProtocolLib already present in ${pluginsDir.absolutePath}; skipping install.")
            return@doLast
        }

        val jar = protocolLibJar.get()
        copy {
            from(jar)
            into(pluginsDir)
        }

        logger.lifecycle("Installed ${jar.name} into ${pluginsDir.absolutePath}.")
    }
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to version)
    }
}

tasks.runServer {
    dependsOn(installProtocolLibForRunServer)
    minecraftVersion("1.21.11")
    doFirst {
        ensureRunServerConfiguration(runServerDir.get().asFile)
    }
}
