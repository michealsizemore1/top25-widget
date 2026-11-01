plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.michealsizemore1.top25widget"
    compileSdk = 35

    defaultConfig {
        // Same id as the phone app: Wear OS pairs them as one app.
        applicationId = "io.github.michealsizemore1.top25widget"
        minSdk = 30
        targetSdk = 34
        versionCode = 10
        versionName = "3.0 · build " + (System.getenv("GITHUB_RUN_NUMBER") ?: "dev")
    }

    signingConfigs {
        create("fixed") {
            storeFile = file("../app/top25.keystore")
            storePassword = "top25widget"
            keyAlias = "top25"
            keyPassword = "top25widget"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("fixed") }
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("fixed") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets { getByName("main") { java.srcDir("../shared/src/main/java") } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
    implementation("androidx.wear:wear-ongoing:1.0.0")
    implementation("androidx.wear.tiles:tiles:1.4.1")
    implementation("androidx.wear.protolayout:protolayout:1.2.1")
    implementation("androidx.wear.protolayout:protolayout-expression:1.2.1")
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.2.1")
    implementation("androidx.concurrent:concurrent-futures:1.2.0")
    implementation("com.google.guava:listenablefuture:1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
