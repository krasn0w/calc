plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Each CI run gets a higher version code, so a new APK installs over the old one.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "io.github.krasn0w.calc"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.krasn0w.calc"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.$buildNumber"
    }

    // A fixed key (committed to this private repo) so updates install without uninstalling.
    signingConfigs {
        create("release") {
            storeFile = file("calc.jks")
            storePassword = "calc-personal"
            keyAlias = "calc"
            keyPassword = "calc-personal"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
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
    testImplementation("junit:junit:4.13.2")
}
