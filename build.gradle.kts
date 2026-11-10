plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "dev.katherine.consilium"
version = "0.3.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // An installed IDE keeps the build offline and fast; without one, the platform is
        // downloaded, so the project builds on any machine and in CI.
        val ideaPath = providers.gradleProperty("ideaPath").orNull
        if (ideaPath != null && file(ideaPath).exists()) {
            local(ideaPath)
        } else {
            intellijIdea(providers.gradleProperty("platformVersion"))
        }
    }
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
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
