plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.adcleaner.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.adcleaner.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            // 云构建用 debug 签名也能装；release 签名由 CI secrets 注入
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { viewBinding = true }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
        // BouncyCastle 多 jar 的 OSGI/Maven 元数据重复
        resources.excludes += setOf(
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            "META-INF/OSGI-INF/MANIFEST.MF",
            "META-INF/INDEX.LIST",
            "META-INF/DEPENDENCIES",
            "META-INF/NOTICE.md",
            "META-INF/LICENSE.md",
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    // 无线调试配对/连接：libadb-android（SPAKE2 配对 + TLS 连接 + shell）
    // 证书生成仍用 BC（bcpkix）；bcprov 是 libadb-android 的传递依赖，显式声明固定版本
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1") {
        // 库带的是 bcprov-jdk15to18:1.81（包名与 jdk18on 相同，共存会 DEX 重复类），
        // 排除后统一用我们固定的 bcprov-jdk18on（HKDF/AES-GCM 等 API 完全兼容）
        exclude(group = "org.bouncycastle")
    }
}
