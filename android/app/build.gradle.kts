import java.net.URI
import java.util.zip.ZipInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.spectalk.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.spectalk.app"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        // Required for libvosk.so — avoids Play Store 16 KB page-size alignment check
        jniLibs { useLegacyPackaging = true }
    }

    // Skip compression on Vosk model files — they are already binary/compressed.
    // Without this Gradle spends minutes trying to DEFLATE 68 MB of FST/mdl files.
    androidResources {
        noCompress += listOf("mdl", "fst", "int", "conf", "mat", "dubm", "ie", "stats")
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)

    // Secure storage
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.datastore.preferences)

    // Google Sign-In via Credential Manager
    implementation(libs.credentials)
    implementation(libs.credentials.play.services)
    implementation(libs.googleid)

    // Firebase Cloud Messaging (notification channel setup)
    implementation(libs.firebase.messaging)

    // WebSocket (BackendVoiceClient)
    implementation(libs.okhttp)

    // Vosk wake-word detection
    implementation("com.alphacephei:vosk-android:0.3.47@aar") { isTransitive = true }
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}

// ---------------------------------------------------------------------------
// Vosk model auto-download
// The model is not committed to git (it's ~20 MB of binary files).
// This task downloads and extracts it into assets/model/ automatically
// before every build. It is a no-op if the model already exists.
// ---------------------------------------------------------------------------
val voskModelUrl = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
val voskModelDir = file("src/main/assets/model")

tasks.register("downloadVoskModel") {
    description = "Downloads and extracts the Vosk small-EN model into assets/model/ if missing."
    onlyIf { !voskModelDir.exists() }
    doLast {
        val zipFile = File(buildDir, "tmp/vosk-model.zip")
        zipFile.parentFile.mkdirs()

        logger.lifecycle("Downloading Vosk model from $voskModelUrl ...")
        URI(voskModelUrl).toURL().openStream().use { input ->
            zipFile.outputStream().use { output -> input.copyTo(output) }
        }

        logger.lifecycle("Extracting Vosk model to $voskModelDir ...")
        val destAssets = file("src/main/assets")
        ZipInputStream(zipFile.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                // Strip the top-level folder from the zip (vosk-model-small-en-us-0.15/...)
                // and map it to assets/model/...
                val stripped = entry.name.substringAfter("/")
                if (stripped.isNotEmpty()) {
                    val outFile = File(destAssets, "model/$stripped")
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile.mkdirs()
                        outFile.outputStream().use { out -> zip.copyTo(out) }
                    }
                }
                entry = zip.nextEntry
            }
        }
        zipFile.delete()
        logger.lifecycle("Vosk model ready at $voskModelDir")
    }
}

// Wire the download to run before assets are merged
tasks.whenTaskAdded {
    if (name == "mergeDebugAssets" || name == "mergeReleaseAssets") {
        dependsOn("downloadVoskModel")
    }
}
