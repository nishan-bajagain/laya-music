import org.jetbrains.kotlin.gradle.dsl.kotlinExtension
import java.util.Properties

val appVersionName = "v1.0.4"
val appVersionCode = 10012

// ── Load local.properties (not committed to git) ──────────────────────────
val localProps = Properties().also { props ->
    rootProject.file("local.properties").takeIf { it.exists() }
        ?.inputStream()?.use { props.load(it) }
}

/** Reads a key from environment variable first, then local.properties fallback. */
fun localOrEnv(propKey: String, envKey: String = propKey.replace('.', '_').uppercase()): String? =
    System.getenv(envKey)?.takeIf { it.isNotBlank() }
        ?: localProps.getProperty(propKey)?.takeIf { it.isNotBlank() }

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.jetbrains.kotlin.serialization)
    alias(libs.plugins.ksp)
}

kotlinExtension.jvmToolchain {
    languageVersion.set(JavaLanguageVersion.of(21))
}

android {
    namespace = "ca.ilianokokoro.umihi.music"
    compileSdk {
        version = release(37)
    }
    // buildToolsVersion intentionally omitted — AGP 9.3 uses its own default.

    val ytmApiKey = localOrEnv("ytm.api.key", "YTM_API_KEY") ?: ""

    defaultConfig {
        applicationId = "ca.ilianokokoro.umihi.music"
        minSdk = 24
        // targetSdk = 35 (Android 15, stable, widely deployed).
        // Using 35 rather than 37 ensures the runtime behaviour contract is stable
        // on all real devices regardless of whether they have Android 17 installed.
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // API key injected at compile-time — never hardcoded in Kotlin source.
        buildConfigField("String", "YTM_API_KEY", "\"$ytmApiKey\"")
    }

    // ── Signing ───────────────────────────────────────────────────────────────
    // Credentials are read from environment variables first (GitHub Actions
    // secrets), then local.properties (gitignored) for local dev. Release
    // builds REQUIRE the real keystore: there is no debug-key fallback, so a
    // missing value fails the build instead of silently producing a
    // debug-key-signed APK whose signature conflicts with the published
    // v1.0.2+ releases and breaks in-place updates.
    val ksFile    = file("$rootDir/laya-release.jks")
    val ksPassword = localOrEnv("keystore.password", "KEYSTORE_PASSWORD")
    val ksAlias    = localOrEnv("key.alias", "KEY_ALIAS")
    val ksKeyPass  = localOrEnv("key.password", "KEY_PASSWORD")

    val missingSigningValues = buildList {
        if (ksPassword == null) add("KEYSTORE_PASSWORD")
        if (ksAlias == null) add("KEY_ALIAS")
        if (ksKeyPass == null) add("KEY_PASSWORD")
        if (!ksFile.exists()) add("keystore file at $ksFile")
    }

    // Fail fast on release builds only. Scoped to the task graph so debug
    // builds and unit tests still run on machines that don't hold the release
    // keystore, while any release assemble/bundle/package/install aborts with
    // the exact missing value(s) named.
    if (missingSigningValues.isNotEmpty()) {
        val missingDetail = missingSigningValues.joinToString(", ")
        gradle.taskGraph.whenReady {
            if (allTasks.any { task ->
                    task.name.contains("Release") &&
                    (task.name.startsWith("assemble") ||
                        task.name.startsWith("bundle") ||
                        task.name.startsWith("package") ||
                        task.name.startsWith("install"))
                }
            ) {
                error("Release signing config missing: $missingDetail")
            }
        }
    }

    signingConfigs {
        create("release") {
            storeFile     = ksFile
            storePassword = ksPassword
            keyAlias      = ksAlias
            keyPassword   = ksKeyPass
        }
    }

    buildTypes {
        release {
            // Lyrics works through reflection/generated serialization and
            // multiple network clients. Keep this distributable diagnostic
            // release unminified until real-device behavior is confirmed;
            // minification can otherwise turn a runtime failure into an
            // indistinguishable "no lyrics found" screen.
            isMinifyEnabled = false
            isShrinkResources = false
            // Always the real keystore — the fail-fast check above guarantees
            // it is fully configured by the time a release build runs.
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    // Distribution flavors: `github` is the default distribution (GitHub
    // Releases self-update enabled); `store` is the Play Store variant where
    // self-updating is against store policy — the store manifest removes
    // REQUEST_INSTALL_PACKAGES and SELF_UPDATE_ENABLED is false, so none of
    // the update machinery runs.
    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            buildConfigField("boolean", "SELF_UPDATE_ENABLED", "true")
        }
        create("store") {
            dimension = "distribution"
            buildConfigField("boolean", "SELF_UPDATE_ENABLED", "false")
        }
    }

    // Universal APK only
    splits {
        abi {
            isEnable = false
            isUniversalApk = true
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    androidResources {
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            // Keep per-flavor APKs apart while preserving the single shared name.
            output.outputFileName.set("laya-${variant.name}.apk")
        }
    }
}

// Adding product flavors renames the per-variant unit-test tasks to
// testGithubDebugUnitTest / testStoreDebugUnitTest. Re-expose the old
// aggregate name so the documented `testDebugUnitTest` command keeps working.
tasks.register("testDebugUnitTest") {
    dependsOn("testGithubDebugUnitTest", "testStoreDebugUnitTest")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.material3)
    debugImplementation(libs.androidx.ui.tooling)

    // Desugaring
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)

    // Navigation 3
    implementation(libs.nav3.runtime)
    implementation(libs.nav3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.nav3)

    // Splash Screen
    implementation(libs.androidx.core.splashscreen)

    // Serialization
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)

    // Viewmodel
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Coil (images)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Exoplayer
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // WebKit
    implementation(libs.androidx.webkit)

    // Icons
    implementation(libs.androidx.material.icons.extended)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Custom Activity On Crash
    implementation(libs.customactivityoncrash)

    // Workers
    implementation(libs.androidx.work.runtime.ktx)

    // Reorderable list
    implementation(libs.reorderable)

    // New Pipe Extractor
    implementation(libs.newpipeextractor)

    // Palette — for extracting dominant color from album art (dynamic player background)
    implementation(libs.androidx.palette)

    // Pure JVM regression tests for the provider-independent lyrics core.
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
