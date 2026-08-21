plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
}

// Compiles the app's pure Compose screens against JetBrains Compose so they can be
// type-checked without the Android SDK. See settings-uicheck.gradle.kts.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    sourceSets["main"].kotlin.setSrcDirs(
        listOf(
            "../app/src/main/kotlin/com/h3consultingpartners/ifatccompanion/ui/screens",
            "../app/src/main/kotlin/com/h3consultingpartners/ifatccompanion/ui/components",
            "../app/src/main/kotlin/com/h3consultingpartners/ifatccompanion/ui/theme",
            "../app/src/main/kotlin/com/h3consultingpartners/ifatccompanion/ui/map",
            "../app/src/main/kotlin/com/h3consultingpartners/ifatccompanion/ui/state",
        ),
    )
}

// Compose Multiplatform 1.6.x is the last line whose -desktop artifacts carry the
// androidx.compose.* classes themselves rather than depending on Google-Maven-only
// androidx artifacts, so it is the one that resolves from Maven Central alone.
val composeVersion = "1.6.11"

configurations.all {
    // androidx.collection / androidx.annotation are Google-Maven-only. Compose needs
    // them at runtime, but this module never runs — it only type-checks — and the
    // public API surface the screens touch does not expose them.
    exclude(group = "androidx.collection")
    exclude(group = "androidx.annotation")
}

dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.compose.runtime:runtime:$composeVersion")
    implementation("org.jetbrains.compose.foundation:foundation:$composeVersion")
    implementation("org.jetbrains.compose.material3:material3:$composeVersion")
    implementation("org.jetbrains.compose.ui:ui:$composeVersion")
    implementation("org.jetbrains.compose.ui:ui-graphics:$composeVersion")
    implementation("org.jetbrains.compose.ui:ui-text:$composeVersion")
    implementation("org.jetbrains.compose.material:material-icons-core:$composeVersion")
    implementation(libs.kotlinx.coroutines.core)
}
