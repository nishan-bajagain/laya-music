import com.android.build.api.variant.BuildConfigField
import org.jetbrains.kotlin.gradle.dsl.kotlinExtension

val appVersionName = "1.0.2"
val appVersionCode = 10002

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
    }

    // ── Signing ───────────────────────────────────────────────────────────────
    // The keystore is NEVER committed to source control.
    // For local builds: place laya-release.jks next to this file and set the
    //   three env vars below in your shell or a local ~/.gradle/gradle.properties.
    // For CI (GitHub Actions): add KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS,
    //   and KEY_PASSWORD as repository secrets. The workflow decodes the base64
    //   secret back to laya-release.jks before running assembleRelease.
    // If any required value is absent the release signing config is skipped
    //   (the APK will be unsigned — safe for local debug builds).
    val ksFile       = file("laya-release.jks")
    val ksPassword   = System.getenv("KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
    val ksAlias      = System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() }
    val ksKeyPass    = System.getenv("KEY_PASSWORD")?.takeIf { it.isNotBlank() }
    val canSign      = ksFile.exists() && ksPassword != null && ksAlias != null && ksKeyPass != null

    if (canSign) {
        signingConfigs {
            create("release") {
                storeFile     = ksFile
                storePassword = ksPassword
                keyAlias      = ksAlias
                keyPassword   = ksKeyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (canSign) signingConfigs.getByName("release") else null
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
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
            output.outputFileName.set("laya.apk")
        }
    }
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
}
