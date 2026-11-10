pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Downloads the JDK 25 toolchain when it isn't installed. 25, not 21: the 2026.1 platform jars
    // are Java 25 class files (major 69), which javac 21 can't read; the output still targets 21.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "consilium-idea-plugin"
