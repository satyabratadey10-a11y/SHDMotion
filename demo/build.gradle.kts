plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.tracker.motionengine.demo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tracker.motionengine.demo"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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
    implementation("com.github.satyabratadey10-a11y:SHDMotion:main-SNAPSHOT")
}
