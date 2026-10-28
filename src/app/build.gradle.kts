plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val ksPath: String? = System.getenv("KEYSTORE_PATH")

android {
    namespace = "com.seccheck"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.seccheck"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.5.0"
    }
    flavorDimensions += "edition"
    productFlavors {
        // std:無 INTERNET 權限;net:加入 DNS 分析 VPN(只為轉送 DNS 查詢使用網路)
        create("std") { dimension = "edition"; manifestPlaceholders["appLabel"] = "SecCheck" }
        create("net") {
            dimension = "edition"; applicationIdSuffix = ".net"; versionNameSuffix = "-net"
            manifestPlaceholders["appLabel"] = "SecCheck Net"
        }
    }
    signingConfigs {
        if (ksPath != null) create("release") {
            storeFile = file(ksPath)
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            // 沒設定金鑰時退回 debug 簽章,方便測試安裝
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
