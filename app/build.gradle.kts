import com.android.build.api.dsl.Packaging

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
}

android {
    namespace = "com.sbai"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sbai"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        // ARMv8 (arm64-v8a) only, per project requirement.
        // (ndk abiFilters is set per-variant via splits.abi below)
        resourceConfigurations += listOf("en", "zh-rCN")
    }

    signingConfigs {
        create("release") {
            // 自签可复现构建（开发/演示证书，非分发签名）。
            // 口令优先取环境变量 / gradle 属性（CI secret），缺省回退到开发默认值；
            // 真实分发请注入 SBAI_STORE_PASSWORD / SBAI_KEY_PASSWORD 并轮换密钥。
            storeFile = file("../sbai-keystore.jks")
            storePassword = providers.gradleProperty("SBAI_STORE_PASSWORD")
                .orElse(providers.environmentVariable("SBAI_STORE_PASSWORD"))
                .orElse("sbai123456")
                .get()
            keyAlias = providers.gradleProperty("SBAI_KEY_ALIAS")
                .orElse(providers.environmentVariable("SBAI_KEY_ALIAS"))
                .orElse("sbai")
                .get()
            keyPassword = providers.gradleProperty("SBAI_KEY_PASSWORD")
                .orElse(providers.environmentVariable("SBAI_KEY_PASSWORD"))
                .orElse("sbai123456")
                .get()
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Only ship the arm64-v8a APK.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
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

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "**/kotlin/**",
                "**/*.kotlin_metadata",
            )
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // haze（Compose Multiplatform）的 Gradle 元数据会强制拉高 androidx 版本，
    // 统一 force 回与 compileSdk 34 兼容的版本。
    configurations.all {
        resolutionStrategy {
            force(
                "androidx.activity:activity:1.9.3",
                "androidx.activity:activity-ktx:1.9.3",
                "androidx.activity:activity-compose:1.9.3",
                "androidx.lifecycle:lifecycle-runtime:2.8.7",
                "androidx.lifecycle:lifecycle-runtime-ktx:2.8.7",
                "androidx.lifecycle:lifecycle-common:2.8.7",
                "androidx.core:core:1.13.1",
                "androidx.core:core-ktx:1.13.1",
            )
        }
    }

    // Prebuilt sing-box core（LxBox 同款 fork：Leadaxe/sing-box-lx，arm64-v8a only）.
    // 由 scripts/fetch-libbox.sh / CI 下载并裁剪；不提交进 git.
    implementation(files("libs/libbox.aar"))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Kototoro 风格悬浮玻璃底栏：内容 backdrop blur
    implementation("dev.chrisbanes.haze:haze:1.5.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
}
