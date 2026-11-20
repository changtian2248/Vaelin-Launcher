plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

// startup-core：启动基底。
// 本模块承载从 ZalithLauncher2（GPL-3.0）复用的启动链，
// 被复用文件保留原版权声明，整体随 Vaelin 以 GPL-3.0 分发。
// 复用边界见 docs/04-分轮计划与任务分解.md §3，逐文件判定见 docs/05 §2。

android {
    namespace = "com.vaelin.startup"
    compileSdk = 37
    ndkVersion = "27.3.13750724"

    defaultConfig {
        minSdk = 26
        // native 层只出 arm64-v8a（规格书第 9 条已锁）
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    externalNativeBuild {
        // ZL2 原文用的是 ndk-build，但 GNU Make 的 $(wildcard) 按空格切分参数
        // （NDK 的 build/core/add-application.mk:85 用它校验 APP_BUILD_SCRIPT），
        // 工作区路径 "Vaelin Launcher" 含空格时必然报 "points to an unknown file"。
        // 因此改用 CMake，逐目标的参数映射见 src/main/jni/CMakeLists.txt。
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
        }
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
        // Android.mk:11 用 $(call import-module,prefab/bytehook) 引入预编译库
        prefab = true
    }

    packaging {
        jniLibs {
            // bytehook 同时由 AAR 与 prefab 提供，按 ZL2 的做法只保留一份
            pickFirsts += listOf("**/libbytehook.so")
            // awt_headless 是 Android.mk:99-101 的空壳占位模块。
            // ZL2 用 Unix rm 在链接后删掉它的 .so，Windows 主机上没有 rm，
            // 这里改用 AGP 的打包排除规则达成同样效果。
            excludes += listOf("**/libawt_headless.so")
            useLegacyPackaging = true
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    // bridge 层的 @Keep 注解（ZLBridge / LoggerBridge 均标注）
    implementation("androidx.annotation:annotation:1.9.1")
    // ZLBridgeStates 用 MutableStateFlow 暴露指针/窗口状态
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Android.mk 的 exithook 目标链接 bytehook
    implementation("com.bytedance:bytehook:1.0.10")

    // JRE 解压：assets 里是 universal.tar.xz + bin-arm64.tar.xz，
    // 与 ZL2 RuntimesManager.uncompressTarXZ 用的同一套读取器。
    implementation("org.apache.commons:commons-compress:1.28.0")
    // commons-compress 把 XZ 支持做成可选依赖，不引这个会在建 XZCompressorInputStream 时
    // 抛 ClassNotFoundException: org.tukaani.xz.XZInputStream
    implementation("org.tukaani:xz:1.12")

    // 纯 JVM 单测：只覆盖不依赖 Android API 的部分（内存档位判定、渲染器注册表语义）
    testImplementation("junit:junit:4.13.2")
}
