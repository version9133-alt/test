plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.version9133.opcam"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.version9133.opcam"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        resourceConfigurations += listOf("en")
    }

    signingConfigs {
        // Same shared debug key tools/build-apk.sh uses, so Gradle and script builds can update each other.
        create("shared") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "opcam-debug"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Replace with your own release key for distribution (see keystore/README.md).
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    packaging {
        resources.excludes += listOf("META-INF/*.version", "kotlin/**")
    }
}

// No dependencies on purpose: framework-only UI (no AppCompat/Material), Camera2 + Extensions from the platform.
dependencies {
}
