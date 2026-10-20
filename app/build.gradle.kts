plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.apostolichiro.videodl"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.apostolichiro.videodl"
        minSdk = 29
        targetSdk = 34
        // Each repository counts its builds from 1: earlier ones reached 1.0.8 (code 8) and 1.1.8 (code 108).
        // This one starts at 200, so new versions always install over old ones.
        val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toInt()
        versionCode = 200 + run
        versionName = "1.2.$run"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The emulator reaches the CI machine's test web server here.
        testInstrumentationRunnerArguments["mediaHost"] = "http://10.0.2.2:8000"
    }

    // One APK per CPU type keeps the download small: phones use arm64-v8a, the CI emulator x86_64.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    // One fixed key so every new build installs over the previous one.
    signingConfigs {
        create("fixed") {
            storeFile = file("videodl.keystore")
            storePassword = "videodl"
            keyAlias = "videodl"
            keyPassword = "videodl"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Python, ffmpeg and QuickJS run as programs, so they must be unpacked on install.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    val ytdl = "0.17.3"
    implementation("io.github.junkfood02.youtubedl-android:library:$ytdl")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdl")
    // Downloads one file over many connections at once.
    implementation("io.github.junkfood02.youtubedl-android:aria2c:$ytdl")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // The app's own video player.
    implementation("androidx.media3:media3-exoplayer:1.4.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
}
