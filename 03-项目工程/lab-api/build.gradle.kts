plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

// lab-api 是模块作者编译期依赖的契约包。
//
// Compose 一致性铁律（规格书第 17/23 条）：
//   模块产物不得含 androidx.* 类。因此这里全部用 compileOnly，
//   宿主 app 提供实现；模块打包时 exclude 全部 androidx.*。
// 如果哪天这里被改成 implementation，模块 zip 会立刻膨胀，
// 且与宿主 Compose runtime 冲突 —— CI 三检会把这种情况拦下来。

android {
    namespace = "com.vaelin.lab.api"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(21)
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    compileOnly(composeBom)
    compileOnly("androidx.compose.ui:ui")
    compileOnly("androidx.compose.runtime:runtime")
    compileOnly("androidx.compose.foundation:foundation")
    compileOnly("androidx.compose.material3:material3")
}
