import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "dev.metro.anime"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.metro.anime"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "0.2.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keyFile = file(
                localProps.getProperty("metro.storeFile")
                    ?: "${System.getProperty("user.home")}/.config/metro-launcher/metro-release.keystore"
            )
            if (keyFile.exists()) {
                storeFile = keyFile
                storePassword = localProps.getProperty("metro.storePassword") ?: "metro1234"
                keyAlias = localProps.getProperty("metro.keyAlias") ?: "metro"
                keyPassword = localProps.getProperty("metro.keyPassword") ?: "metro1234"
            } else {
                initWith(getByName("debug"))
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Image loading with Coil
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Frosted Glass & Blur (Haze)
    implementation("dev.chrisbanes.haze:haze:1.5.3")

    // Network & JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // Media3 / ExoPlayer (HLS streaming without ads)
    val media3Version = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")
    implementation("androidx.media3:media3-effect:$media3Version")
}
