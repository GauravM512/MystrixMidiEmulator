plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.matrix.midiemulator"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.matrix.midiemulator"
        minSdk = 23
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.4"
    }


    signingConfigs {
        create("release") {
            storeFile = rootProject.file("mystrix-key.jks")
            storePassword = project.findProperty("KEYSTORE_PASSWORD") as String
            keyAlias = project.findProperty("KEY_ALIAS") as String
            keyPassword = project.findProperty("KEY_PASSWORD") as String
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("release")

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        getByName("debug") {
            isMinifyEnabled = false
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}