plugins {
    java
}

group = "dev.shadowcore"
version = "2.0.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.dmulloy2.net/repository/public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("com.comphenix.protocol:ProtocolLib:5.3.0")
    // SQLite JDBC is bundled with Paper's runtime — no need to shade it.
    // com.mojang.authlib is NOT imported directly — we use Paper's PlayerProfile API
    // which internally handles authlib version differences.
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to version)
    }
}
