import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.binarysimple.hvacwidget"
    compileSdk = 36

    defaultConfig {
        // D-008: a fully standalone app - its own applicationId, zero dependencies and IPC
        // on :app. minSdk/targetSdk follow the SeatMemoryWidget reference (geely-references 2e):
        // min 26, target 35 - below the firmware's own level on purpose.
        applicationId = "com.binarysimple.hvacwidget"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation("junit:junit:4.13.2")
}
