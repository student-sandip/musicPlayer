// app/build.gradle

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.gaanesuno"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.gaanesuno"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {

    // Existing dependencies
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)

    // --- YOU MUST ADD THESE NEW DEPENDENCIES ---
    implementation(libs.recyclerview)
    implementation(libs.media) // Crucial for the MediaStyle error you were getting
    implementation(libs.core.ktx)
    // ------------------------------------------

    // Testing dependencies
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}