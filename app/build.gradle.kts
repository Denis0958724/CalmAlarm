plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ua.calmalarm.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "ua.calmalarm.app"
        minSdk = 26
        targetSdk = 36
        // Кожна збірка на GitHub отримує новий номер версії, щоб оновлення ставилися поверх старих.
        val build = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = build
        versionName = "1.0.$build"
        // Кнопка «Підтримати» є лише в APK з GitHub (-Pdonate=true). У Google Play (AAB) її немає.
        val donate = (project.findProperty("donate") as String?) == "true"
        buildConfigField("boolean", "SHOW_SUPPORT", donate.toString())
        buildConfigField("String", "SUPPORT_CARD", if (donate) "\"5168752118023400\"" else "\"\"")
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.jks")
            storePassword = "calmalarm2026"
            keyAlias = "calmalarm"
            keyPassword = "calmalarm2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
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
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
