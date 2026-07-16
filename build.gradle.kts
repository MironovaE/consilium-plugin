plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "dev.katherine.consilium"
version = "0.2.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        local(providers.gradleProperty("ideaPath").get())
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

tasks.register<Sync>("releaseBundle") {
    dependsOn(tasks.named("buildPlugin"))
    from(layout.buildDirectory.dir("distributions")) {
        include("consilium-idea-plugin-${project.version}.zip")
    }
    from("INSTALL.md") {
        rename { "README.md" }
    }
    into(layout.projectDirectory.dir("install/Consilium-${project.version}"))
}

intellijPlatform {
    pluginConfiguration {
        id = "dev.katherine.consilium"
        name = "Consilium"
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "261"
        }

        vendor {
            name = "Katherine Mironova"
        }

        description = """
            A private local room for human-guided deliberation with Claude Code and Codex.
        """.trimIndent()
    }
}
