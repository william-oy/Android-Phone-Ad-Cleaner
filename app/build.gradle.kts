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
        versionCode = 2
        versionName = "0.2.0"
        vectorDrawables { useSupportLibrary = true }
        // Conscrypt/spake2 的原生库只保留 ARM（老人手机全为 ARM）：
        // 裁掉 x86/x86_64 可减约 5MB，且不影响任何真机安装
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // 固定签名（keystore 提交在仓库 keystore/ 下，调试密钥非机密）：
    // 每次构建产物签名一致 → 覆盖安装不用卸载，app 数据与配对密钥保留，
    // 老人升级 app 不需要重新配对无线调试
    signingConfigs {
        create("adcleaner") {
            storeFile = rootProject.file("keystore/adcleaner-debug.keystore")
            storePassword = "android"
            keyAlias = "adcleaner"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("adcleaner")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("adcleaner")
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
    // 自带 Conscrypt（TLS1.3 + 公开 exportKeyingMaterial）：平台门面类的同名隐藏 API
    // 在 vivo 上被 hiddenapi 拦截，配对导出密钥材料必须用这个 APK 内置的公开类
    implementation("org.conscrypt:conscrypt-android:2.5.3")
}
