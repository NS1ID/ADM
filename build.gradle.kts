// AGP 9 compiles Kotlin itself and refuses the `org.jetbrains.kotlin.android` plugin.
// It takes the compiler from the kotlin-gradle-plugin jar on this classpath, so this
// is where the Kotlin version is pinned. Code On The Go injects the on-device Maven
// repo here so it resolves offline.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    // The bundled-Chromium SDK's Gradle plugin. It generates the R classes for
    // Chromium's many resource packages so the engine resolves them at runtime.
    // Its version must equal com.cefrium:cefrium-sdk in app/build.gradle.kts.
    id("com.cefrium") version "0.21.0" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
