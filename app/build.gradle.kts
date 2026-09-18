import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing: reads app/keystore.properties (gitignored, never commit).
val keystoreProps = Properties().apply {
    val propsFile = file("keystore.properties")
    if (propsFile.exists()) propsFile.inputStream().use { fis -> load(fis) }
}
val releaseStoreFile = keystoreProps.getProperty("storeFile", "").trim()
val hasReleaseKey = keystoreProps.containsKey("storePassword") &&
    releaseStoreFile.isNotEmpty() && file(releaseStoreFile).exists()

android {
    namespace = "com.droplocal.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.droplocal.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0-mvp"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKey) {
                storeFile = file(keystoreProps.getProperty("storeFile").trim())
                storePassword = keystoreProps.getProperty("storePassword").trim()
                keyAlias = keystoreProps.getProperty("keyAlias").trim()
                keyPassword = keystoreProps.getProperty("keyPassword").trim()
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.datastore.preferences)
    implementation(libs.nearby.connections)
    implementation(libs.zxing.core)
    implementation(libs.zxing.embedded)
    implementation(libs.navigation.compose)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)

    testImplementation(libs.junit)
}
