plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.vaelin.launcher"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.vaelin.launcher"
        minSdk = 26
        // targetSdk 必须停在 34，不能升。
        // 依据：ZL2 的 FullScreenAppCompatActivity.kt:143 原文注释——
        //   "注：targetSdk 需要设置为 34，从 35 开始，Activity 会被强行加入 enableEdgeToEdge，
        //    该实现就会彻底失效"
        // 我们复用它的全屏实现（沉浸式 + 刘海适配 + 触摸坐标修正），所以这条约束继承下来。
        // 若将来要升到 35+，必须先自己重写一套 edge-to-edge 下的全屏方案并真机验证。
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-r1"

        // 规格书第 9 条：首版仅 arm64-v8a。
        // startup-core 的 CMake 产物本来就只有 arm64，但 androidx 与 bytehook 的 AAR
        // 自带另外三个 ABI 的 .so，必须在 app 侧裁掉，否则 APK 会多出 3 个无意义的 ABI 目录。
        ndk {
            abiFilters += listOf("arm64-v8a")
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
    }

    packaging {
        jniLibs {
            // libbytehook.so 同时来自两处：startup-core 的 CMake 产物
            // （prefab 把 AAR 里的 .so 也复制进了 jni 目录）和 bytehook AAR 依赖本身。
            // 冲突发生在 app 的 mergeDebugNativeLibs，所以 pickFirsts 必须加在 app 模块，
            // 这与 ZL2 把该配置放在 application 模块的做法一致。
            pickFirsts += listOf("**/libbytehook.so")
            // libc++_shared.so 同理可能来自多路（jniLibs 预置库与三方 AAR 依赖），
            // 冲突发生在 app 的 mergeDebugNativeLibs，取第一份即可
            pickFirsts += listOf("**/libc++_shared.so")
            // 空壳占位库，见 startup-core/src/main/jni/CMakeLists.txt 的 awt_xawt 段
            excludes += listOf("**/libawt_headless.so")
            // 与 ZL2 一致：so 不压缩打包，便于运行时直接 dlopen 私有目录里的副本
            useLegacyPackaging = true
        }
    }

    androidResources {
        // assets 里的 JRE 是 .tar.xz（本身已是压缩格式），aapt 再压一遍没有收益，
        // 只会拖慢构建。ZL2 未显式配置，这里显式声明以免随 AGP 版本默认行为变化。
        noCompress += listOf("tar.xz")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":lab-api"))
    implementation(project(":startup-core"))

    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    // material3 不带图标（material-icons-core 是独立 artifact，版本由 BOM 管理）。
    // 只用 core 集（Search/Clear/ArrowBack 等约 20 个常用图标），不为单个图标引扩展包。
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.13.0")

    // compose-bom 的 POM 不含 lifecycle 条目（已核对 bom pom），所以这里必须写死版本号。
    // 只用它的 viewModel() 工厂让 LauncherViewModel 跟随 Activity 的 ViewModelStore，
    // 不引入 navigation-compose——导航是 sealed class + rememberSaveable 手写状态机。
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")

    testImplementation("junit:junit:4.13.2")
}
