// UI type-check build: `./gradlew -c settings-uicheck.gradle.kts :uicheck:compileKotlin`
//
// The Android SDK and Google's Maven repository are not available on every machine
// (CI runners, sandboxes). This variant compiles :core plus the *pure* Compose
// screens — the ones under app/src/main/kotlin/**/ui/screens and ui/components,
// which take state and callbacks as parameters and import nothing Android-specific —
// against JetBrains Compose from Maven Central. Those artifacts publish the same
// androidx.compose.{runtime,foundation,material3,ui} packages, so the screens
// type-check exactly as they will under AGP.
//
// It does NOT replace a real Android build: the Activity, ViewModels, service,
// notifications, billing, audio and resource plumbing live outside these source
// sets and are only compiled by :app.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "IFATC Companion (ui type-check)"
include(":core")
include(":uicheck")
