// Core-only build: `./gradlew -c settings-core.gradle.kts :core:test`
//
// :core is pure Kotlin/JVM, so this variant of the build resolves entirely from
// Maven Central and needs neither the Android SDK nor Google's Maven repository.
// Use it for fast engine-only verification and for CI runners without an SDK.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
}

rootProject.name = "IFATC Companion (core)"
include(":core")
