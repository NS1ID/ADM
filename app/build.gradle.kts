import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    // Must stay the same version as com.cefrium:cefrium-sdk below.
    id("com.cefrium")
}

val keystorePropsFile = rootProject.file("release.properties")
val keystoreProps = Properties()

if (keystorePropsFile.exists()) {
    keystoreProps.load(FileInputStream(keystorePropsFile))
}

val hasValidSigningProps = keystorePropsFile.exists().also { exists ->
    if (exists) {
        FileInputStream(keystorePropsFile).use { keystoreProps.load(it) }
    }
}.let {
    listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { key ->
        keystoreProps[key] != null
    }
}

android {
    namespace = "com.adm"
    // Chromium 152+ ships Java 25 (class-file 69) bytecode, so compileSdk 37 and the
    // AGP 9.4 / JDK 25 toolchain are the floor the engine requires.
    // 37.1 is required by the current Compose BOM (2026.11.01) artifacts.
    compileSdk = 37
    compileSdkMinor = 1

    lint {
        checkReleaseBuilds = false
    }

    signingConfigs {
        if (hasValidSigningProps) {
            create("release") {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    defaultConfig {
        applicationId = "com.adm"
        // Universal: minimum Android 5.0 (API 21) so it also runs on older devices;
        // targetSdk stays modern. Both 32-bit (armeabi-v7a) and 64-bit (arm64-v8a) ABIs
        // are packaged so the app supports ARM32 and ARM64 at the same time.
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        vectorDrawables {
            useSupportLibrary = true
        }

        // The engine ships one libcef.so per ABI inside the SDK's multi-ABI artifact;
        // listing the ABIs in the app keeps it off architectures with no engine.
        // Rust (JNI/FFI) native libraries are picked up from app/src/main/jniLibs.
        // Both 32-bit (armeabi-v7a) and 64-bit (arm64-v8a) ABIs are packaged so the
        // app runs on ARM32 as well as ARM64 devices. The engine's native artifacts
        // are split per ABI in the Cefrium multi-ABI artifact, and the Rust JNI
        // libs come from app/src/main/jniLibs (armeabi-v7a when present).
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            if (hasValidSigningProps) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Do NOT use `splits { abi { } }`: the engine's Gradle plugin needs legacy
    // jniLibs packaging. Publish an Android App Bundle (AAB) instead, or assemble a
    // single-ABI APK, so each user downloads only their architecture's engine.
    // (libcef.so is ~200 MB per ABI uncompressed.)

    packaging {
        resources {
            resources.excludes.add("/META-INF/{AL2.0,LGPL2.1}")
            resources.excludes.add("META-INF/kotlinx_coroutines_core.version")

            // The part below is only needed for compose builds.
            // This packaging block is required to solve interdependency conflicts.
            // They arise only when using local maven repo, so I suppose online repos have some way of solving such issues.

            // Caused by: com.android.builder.merge.DuplicateRelativeFileException: 4 files found with path 'commonMain/default/linkdata/module' from inputs:
            // - AndroidIDE\libs_source\gradle\localMvnRepository\androidx\collection\collection\1.4.2\collection-1.4.2.jar
            // - AndroidIDE\libs_source\gradle\localMvnRepository\androidx\lifecycle\lifecycle-common\2.8.7\lifecycle-common-2.8.7.jar
            // - AndroidIDE\libs_source\gradle\localMvnRepository\androidx\annotation\annotation\1.8.1\annotation-1.8.1.jar
            // - AndroidIDE\libs_source\gradle\localMvnRepository\org\jetbrains\kotlinx\kotlinx-coroutines-core\1.7.3\kotlinx-coroutines-core-1.7.3.jar
            // And some others.
            resources.pickFirsts.add("nonJvmMain/default/linkdata/package_androidx/0_androidx.knm")
            resources.pickFirsts.add("nonJvmMain/default/linkdata/root_package/0_.knm")
            resources.pickFirsts.add("nonJvmMain/default/linkdata/module")

            resources.pickFirsts.add("nativeMain/default/linkdata/root_package/0_.knm")
            resources.pickFirsts.add("nativeMain/default/linkdata/module")

            resources.pickFirsts.add("commonMain/default/linkdata/root_package/0_.knm")
            resources.pickFirsts.add("commonMain/default/linkdata/module")
            resources.pickFirsts.add("commonMain/default/linkdata/package_androidx/0_androidx.knm")

            resources.pickFirsts.add("META-INF/kotlin-project-structure-metadata.json")

            resources.merges.add("commonMain/default/manifest")
            resources.merges.add("nonJvmMain/default/manifest")
            resources.merges.add("nativeMain/default/manifest")
        }
    }

    configurations.all {
        resolutionStrategy {
            // Keep the Kotlin stdlib at the compiler version.
            force("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
            force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.3.21")
            force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.3.21")

            // AndroidX versions are resolved by Gradle + the Compose BOM.
            // The old force() pins below (needed only for AndroidIDE's offline local
            // Maven repo) are deliberately removed: they downgrade androidx.collection
            // to 1.4.2, which lacks classes the current Compose UI requires
            // (androidx.collection.OrderedScatterSetKt), crashing the app at launch.
        }
    }
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:deprecation")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget("17"))
    }
}

dependencies {

    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.core.ktx)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.collection.ktx)
    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.material3)
    implementation("com.google.android.material:material:1.14.0")
    implementation(libs.kotlin.stdlib) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    }

    // ---- Real browser engine: bundled Chromium ------------------------------
    // Cefrium embeds Chromium (CEF ported to native Android) - the same engine as
    // Google Chrome and Brave: Blink rendering, V8 JavaScript, Skia graphics, in a
    // multi-process architecture, pinned inside the APK rather than borrowed from
    // the system WebView. It also provides native EasyList ad blocking, which is the
    // same approach Brave Shields and 1DM use.
    implementation("com.cefrium:cefrium-sdk:0.21.0")

    // Runtime dependencies the engine expects.
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    implementation(libs.androidx.core)
    implementation("androidx.browser:browser:1.8.0")

    // Navigation + ViewModel for the Compose screens.
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    // Lifecycle service helpers for the foreground download/VPN services.
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    // Persistence: plain SQLite (android.database.sqlite) is used instead of Room so
    // that no annotation processor (KSP/kapt) is required for the build.

    // Networking / download engine.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okio:okio:3.9.0")

    // JSON for settings, extension manifests and IPC payloads.
    implementation("com.google.code.gson:gson:2.10.1")

    // Coroutines.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Image loading for bookmarks / thumbnails.
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Material icons extended (download, vpn, extensions, etc.).
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
}

// Universal build: the Android 5 (API 21) baseline is kept via
// <uses-sdk tools:overrideLibrary> in AndroidManifest.xml, so transitive
// libraries that declare a higher minSdk (Material 1.14, Google Play services,
// Firebase, ML Kit, Compose) are permitted to ship in the universal APK.

// Cefrium sdk-core ships ic_launcher*.xml as <adaptive-icon> in unqualified
// res/drawable (requires API 26). With minSdk 21 AAPT2 fails to compile them.
// Replace them with a simple API-21-safe drawable in the Gradle transform cache
// before resource merging.
val patchCefriumAdaptiveIcons by tasks.registering {
    doLast {
        val home = gradle.gradleUserHomeDir
        val targets = fileTree(home) {
            include("**/transformed/cefrium-sdk-core-*/res/drawable/ic_launcher.xml")
            include("**/transformed/cefrium-sdk-core-*/res/drawable/ic_launcher_round.xml")
        }.files
        val safe = """<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@android:color/white"/>
</shape>
"""
        targets.forEach { f ->
            f.parentFile.mkdirs()
            f.writeText(safe)
            logger.lifecycle("Patched Cefrium adaptive icon: ${f}")
        }
    }
}

tasks.matching { name ->
    name.startsWith("merge") && name.endsWith("Resources") ||
    name.startsWith("generate") && name.endsWith("Resources") ||
    name.startsWith("map") && name.endsWith("SourceSetPaths") ||
    name.startsWith("process") && name.endsWith("Resources") ||
    name.contains("compile") && name.contains("LibraryResources")
}.configureEach {
    dependsOn(patchCefriumAdaptiveIcons)
}
