// Vaelin Launcher — 根构建脚本
// 版本矩阵已在本机实测（见 docs/03-构建环境与工具链.md §3.1）：
//   AGP 9.5.0 内置 Kotlin，不得再 apply org.jetbrains.kotlin.android
//   Compose BOM 2026.09.00 要求 compileSdk >= 37

plugins {
    id("com.android.application") version "9.5.0" apply false
    id("com.android.library") version "9.5.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
