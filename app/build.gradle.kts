plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.michealsizemore1.top25widget"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.michealsizemore1.top25widget"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "2.0"
    }

    // One fixed key so every new build can install as an update over the last one.
    // (Personal sideloaded app only; this key is not used for the Play Store.)
    signingConfigs {
        create("fixed") {
            storeFile = file("top25.keystore")
            storePassword = "top25widget"
            keyAlias = "top25"
            keyPassword = "top25widget"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("fixed") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
