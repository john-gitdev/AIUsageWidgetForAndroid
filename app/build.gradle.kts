plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing is read from the environment so the keystore and its password never
// live in the repo. When these are unset (a normal local checkout) assembleDebug is
// unaffected, and assembleRelease still configures — it just produces an unsigned APK
// rather than failing the build.
val releaseStoreFile: String? = System.getenv("RELEASE_STORE_FILE")
val hasReleaseSigning: Boolean = releaseStoreFile != null && file(releaseStoreFile).exists()

// Printed so a release that silently came out unsigned is obvious in the build log
// rather than only at the signature check.
logger.lifecycle(
    "release signing: " + (if (hasReleaseSigning) "ENABLED" else "DISABLED") +
        " (RELEASE_STORE_FILE=" + (releaseStoreFile ?: "<unset>") +
        ", exists=" + (releaseStoreFile != null && file(releaseStoreFile).exists()) + ")"
)

android {
    namespace = "com.example.claudewidget"
    compileSdk = 34

    defaultConfig {
        // Note: this is the installed app identity, deliberately different from the
        // Kotlin package above. Changing it again would orphan installs the same way
        // the move off com.example.* did in 1.0.9.
        applicationId = "dev.johngitdev.aiusagewidget"
        minSdk = 26
        targetSdk = 34
        versionCode = 13
        versionName = "1.1.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: "release"
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
                    ?: System.getenv("RELEASE_STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.work:work-runtime-ktx:2.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
}
