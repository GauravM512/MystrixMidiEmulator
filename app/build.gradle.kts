plugins {
    id("com.android.application")
}

object AppVersion {
    const val MAJOR = 1
    const val MINOR = 2
    const val PATCH = 0
    const val BUILD = 1

    const val VERSION_NAME = "$MAJOR.$MINOR.$PATCH"
    const val VERSION_CODE = MAJOR * 1000000 +
            MINOR * 10000 +
            PATCH * 100 +
            BUILD
}

android {
    namespace = "com.matrix.midiemulator"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.matrix.midiemulator"
        minSdk = 23
        targetSdk = 37
        versionCode = AppVersion.VERSION_CODE
        versionName = AppVersion.VERSION_NAME
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
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        getByName("debug") {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
}
