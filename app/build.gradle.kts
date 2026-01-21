plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.mathsya_v_01"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.mathsya_v_01"
        minSdk = 30
        targetSdk = 36
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

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.games.activity)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    implementation ("pl.droidsonroids.gif:android-gif-drawable:1.2.29")
    implementation ("com.google.android.gms:play-services-maps:18.2.0")
    implementation ("io.socket:socket.io-client:1.0.1")// Socket.IO Java client
    implementation ("com.squareup.okhttp3:okhttp:4.12.0")
}