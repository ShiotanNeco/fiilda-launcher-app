plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.glasslab.glass"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }

    buildFeatures {
        compose = true
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
    // Keep the engine on the host app's Compose line. The original skill starter uses a
    // newer BOM and minSdk 36, which would make this library unsuitable for the launcher.
    api("androidx.compose.ui:ui:1.11.3")
    api("androidx.compose.foundation:foundation:1.11.3")
    testImplementation("junit:junit:4.13.2")
}
