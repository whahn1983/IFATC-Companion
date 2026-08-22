// Plugins are declared per-module (see app/build.gradle.kts and core/build.gradle.kts)
// rather than in a root `plugins { ... apply false }` block. That keeps the pure-JVM
// :core module resolvable — and therefore compilable and testable — on a machine that
// has no Android SDK and no access to Google's Maven repository. See
// settings-core.gradle.kts and Docs/ANDROID_ARCHITECTURE.md.

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
