plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// :core is a pure Kotlin/JVM module. It holds every piece of ported iOS logic that
// does not need an Android framework class: models, geodesy, the Infinite Flight
// Connect client, the ATC state machine, phraseology, ATIS, weather, the OSM
// airport-surface pipeline and taxi routing, Mock Mode, and the domain side of
// settings/entitlements. Keeping it Android-free means the whole engine is unit
// testable on a plain JVM, and it enforces the layering the iOS app has by
// convention (ATC engine independent of UI, taxi routing independent of the map
// renderer, weather independent of the map).
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging { events("failed") }
}
