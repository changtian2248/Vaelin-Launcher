# Vaelin Launcher 外部事实核查（可核实证据，不含方案）

> 范围：为"能否用现有开源方案把规格书功能做出来"提供**外部事实**。本文件只给证据与判定，不写方案书。
> 方法：官方文档 + 仓库 README + 原始 LICENSE/POM 抓取，优先一手来源。github.com 主站不可达，经 `gh-proxy.com` 代理读取 `raw.githubusercontent.com`。
> 标记约定：`已验证` = 读到一手文本；`二手` = 来自第三方站点转述，未读官方原文；`未验证` = 查过但没有可靠一手来源。
> 时间：2026-11-05（Asia/Shanghai）。

---

## 1. Android 构建链

**AGP 最新稳定版**：`com.android.tools.build:gradle` 最新稳定 **9.5.0**（2026-10-28 随 Android Studio Rabbit 2 发布）。证据：Google Maven `maven-metadata.xml` 显示 9.5.0 为最新稳定、9.6.0-alpha02 为最新预发布；官方发布说明页标题为 "Android Gradle plugin 9.5.0 (October 2026)"。
- 官方：<https://developer.android.com/build/releases/agp-9-5-0-release-notes>
- 上一代稳定：9.4.0 / 9.4.1（Quail 4 / Quail 4 Patch 1，2026-09）。

**AGP 9.5.0 兼容矩阵**（已验证，Google 官方兼容表 + Rabbit 2 说明）：
- 最低 Gradle **9.6.0**；SDK Build Tools **36.0.0**；构建 JDK **17+**；支持最高 API level **37**。
- AGP 9.4 线同样为 Gradle 9.6.0 / Build Tools 36.0.0 / JDK 17+ / API 37。
- 官方：<https://developer.android.com/build/releases/about-agp>

**Kotlin 最新稳定版**：`org.jetbrains.kotlin:kotlin-stdlib` 最新稳定 **2.4.21**（2.5.0-Beta1/Beta2 为预发布）。证据：`repo.maven.apache.org` maven-metadata。
- 注意：AGP 9.x 走"内置 Kotlin"（built-in Kotlin）路线，官方要求 **KGP 2.2.10+**（源自 AGP 9.0.0 说明，9.5 说明未另立 Kotlin 下限，沿用）。见 <https://developer.android.com/build/releases/agp-9-0-0-release-notes>。
- KGP 2.4.20–2.4.21 的 JetBrains 兼容表列支持 AGP **8.5.2–9.3.1**、Gradle 7.6.3–9.7.0（<https://kotlinlang.org/docs/gradle-configure-project.html>）。**与 AGP 9.4/9.5 的配套关系存在张力**：AGP 9.x 的"内置 Kotlin"与 KGP 独立兼容表口径不同，AGP 9.4/9.5 + KGP 2.4.21 是否被官方明确支持 → **未验证**（二手来源见下）。建议以 AGP 内置 Kotlin 或按 KGP 兼容表回退到 AGP 9.3.x 来消除歧义。

**Compose BOM**：`androidx.compose:compose-bom` 最新 **2026.09.00**。证据：`dl.google.com` maven-metadata。
- Compose Compiler 现随 Kotlin 版本走（Kotlin 2.x 的 Compose 编译器插件），不再单独配 `composeOptions`。官方：<https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler>

**compileSdk 建议值**：AGP 9.5 官方最高支持 API 37；Android Studio Rabbit 2 随附 AGP 9.5。**具体建议 compileSdk 数值未在官方文档直接给出单一推荐值** → **未验证**。二手（mungomash，2026-10 更新）给出 Studio×AGP×Gradle×Kotlin×JDK 下限矩阵，可作为交叉参考，但属聚合站，需回官方核实。

**JDK**：构建 JDK 最低 17；本机 JDK 21.0.13 Temurin 满足。Gradle 9 线要求 JVM 17–27。

判定：可完整实现 — AGP/Kotlin/Compose/JDK 均有可用稳定版本，官方兼容矩阵明确（唯一未验证点是 AGP 9.4/9.5 与 KGP 2.4.21 的官方配套口径）。

---

## 2. DexClassLoader 模块系统

**Android 14（API 34）限制**：targetSdk ≥ 34 的应用，系统**不允许对动态加载的 dex/odex 文件持有可写权限**；必须在加载前把文件标记为只读（`DexFile` 只读 / `verify` 模式）。证据：官方文档 "Safer dynamic code loading" 章节 <https://developer.android.com/about/versions/14/behavior-changes-14#safer-dynamic-code-loading>；Frida java-bridge PR #318 复述同一机制（<https://github.com/frida/frida-java-bridge/pull/318>，二手）。

**Android 15（API 35）**：官方 "Behavior changes: Apps targeting Android 15 or higher" <https://developer.android.com/about/versions/15/behavior-changes-15>；另有 "all apps" 行为变更 <https://developer.android.com/about/versions/15/behavior-changes-all>。**Android 15 是否在 Android 14 基础上追加针对 DexClassLoader 的进一步限制 → 未验证**（官方页面未逐条展开 dexopt 相关条款）。

**应用私有目录 dexopt**：`DexClassLoader` 传统要求应用私有可写目录做 odex 缓存；API 26 起官方参考改为建议用 `createDexClassLoader` 并指定 `cacheDir`（私有目录仍可写，但 dex 源文件本身在 Android 14+ 须只读）。参考 <https://developer.android.com/reference/dalvik/system/DexClassLoader>。Scoped storage 限制的是**外部共享**目录，不影响私有目录；Android 11+ 对 targetSdk≥30 强制 scoped storage <https://source.android.com/docs/core/storage/scoped>。

**模块 APK 内打包 Compose UI + 与宿主共享 Compose runtime**：
- 可行做法方向：模块 APK 作为**插件**被宿主 `DexClassLoader`/`PathClassLoader` 加载，模块内引用**宿主已打包的 Compose runtime**（`androidx.compose.*`），通过 `Class.forName` 或依赖收敛（compileOnly）实现共享；运行时由宿主先加载 Compose runtime。
- **先例项目**：**未找到明确、可核实的"插件化 Compose 且模块 APK 复用宿主 Compose runtime"的公开先例** → **未验证**。业界常见做法是插件模块直接依赖 Compose 库（各自打包一份），而非运行时共享，因为 Compose 的 `@Composable` 编译器产出的调用链对库版本敏感。**这一点是本项最大不确定性，建议后续用最小工程实测验证**。
- 相关安全文档：<https://developer.android.com/privacy-and-security/risks/dynamic-code-loading>

判定：高难度 — Android 14/15 对可写 dex 的限制明确且必须处理，但"模块 APK 打包 Compose UI 且共享宿主 Compose runtime"的可核实先例缺失，需自建最小验证。

---

## 3. cubiomes

**仓库**：<https://github.com/Cubitect/cubiomes>（约 965 stars，主分支 `master`）。**License**：**MIT**（已读 raw LICENSE）。

**构建方式**：仓库根有 `makefile`（已验证，`makefile at cf14767... · Cubitect/cubiomes`）；另有 **CMake** 支持——`external/cubiomes/CMakeLists.txt` 被第三方仓库（deepwiki 提及）引用（二手）。仓库本身是否含 `CMakeLists.txt` → **未验证**（未直接读到根目录 CMake 文件）。纯 C 库，无第三方依赖。

**Android/JNI 封装先例**：
- `RasmusAntons/cubiomes-java`（约 0 stars）— Java 封装先例（二手，星数与存在性经搜索确认，未读源码）。
- `villevilli/cubiomes-rs` — Rust 绑定（crates.io `cubiomes`，二手）。
- `xpple/cubiomes` — 活跃 fork，MIT。
- `ethan-prime/cubiomes-cpp` — C++23 封装。
→ 有先例但**均非 Android/JNI 现成产物**，需自行写 JNI 层。

**Windows x86_64 交叉编译 arm64-v8a `.so`**：
- 可行路径：用 Android NDK（`C:\Android\Sdk\ndk`）的 `clang`/`CMake` 或 `ndk-build`，指定 `ANDROID_ABI=arm64-v8a`。NDK 自带 CMake 工具链文件，对纯 C 库交叉编译常规可行。
- cubiomes 自身是否提供 NDK/Android 构建脚本 → **未验证**（仓库为 makefile + 可能的 CMake，未见 Android 专用脚本）。
- 判断：技术上**可完整实现**（纯 C、无平台依赖），但需自建 CMake/NDK 打包步骤；**本机 NDK 是否已安装 → 未验证**（任务背景称 Android SDK 装于 `C:\Android\Sdk`，NDK 组件未单独确认）。

判定：可完整实现 — MIT、纯 C、CMake/makefile 可用，Windows+NDK 交叉编译 arm64-v8a 属常规操作（NDK 安装状态未验证）。

---

## 4. Nucleation

**仓库**：<https://github.com/Schem-at/Nucleation>。**License**：**MIT**（已验证，crates.io/docs.rs 与仓库一致）。

**形态**：Rust crate，`crates.io` 上 `nucleation` 最新 **0.12.0**；文档 <https://docs.rs/crate/nucleation/latest>。官方自称"A Minecraft schematic engine in Rust: load, build, simulate, mesh, and render schematics from seven languages"，绑定由单一注解 Rust 源（`src/bridge/`）生成。

**Rust→C FFI→NDK 可行路径**：
- 官方仓库自带 **`examples/ffi.md`**（已验证存在，路径 `examples/ffi.md`），说明启用 `ffi` feature 的构建方式。
- 官方提供 Rust 绑定示例 `examples/rust.md`。
- 同组织另有 `Schem-at/schematic-renderer`（Three.js + Rust/WASM），**非 Android 原生**。

**Android 构建先例**：Nucleation 官方未直接提供 Android 构建产物/先例 → **未验证**。路径需自行打通：`cargo-ndk`（见下）编译 `libnucleation.so`，再写 JNI 封装。

**Windows 上编译 arm64 的工具链**：
- `cargo-ndk`（<https://github.com/bbqsrc/cargo-ndk>）— "Build Rust code for Android"，处理环境配置并生成 `jniLibs` 目录结构，支持三个子命令。已验证存在。
- 依赖：`rustup` 安装 Android target（`aarch64-linux-android`）、`sdkmanager` 装 NDK、`cargo install cargo-ndk`。二手指南：<https://www.davthecoder.com/blog/rust-android-ndk-toolchain-setup-cargo-ndk>（2026-07）。
- `willir/cargo-ndk-android-gradle` — Android Gradle 项目集成 cargo-ndk 的桥（二手）。
- **本机 Rust 工具链是否已装 → 未验证**（任务未提及 Rust）。

判定：高难度 — FFI 接口与 cargo-ndk 路径明确，但官方无 Android 先例，需自建 JNI + 交叉编译链，且引入 Rust 构建依赖（本机 Rust 工具链状态未验证）。

---

## 5. schematic4j

**Maven 坐标**：`net.sandrohc:schematic4j`。
- 最新版本：**1.1.0**（证据：`repo1.maven.org` metadata，最后更新 2023-12-16）。
- Maven Central：<https://repo.maven.apache.org/maven2/net/sandrohc/schematic4j/1.1.0/>
- Central 页面：<https://central.sonatype.com/artifact/net.sandrohc/schematic4j>

**License**：**MIT**（已验证，读 raw LICENSE，Copyright (c) 2021 Sandro Marques）。

**支持格式**：支持 `.schem`、`.schematic`、`.litematic`（依据 Maven/README 描述）。**是否覆盖规格书所需的全部字段（含 BlockState 现代解析）→ 未验证**（未逐字段核对 API）。

判定：可完整实现 — MIT、Maven Central 可依赖、支持三种主流 schematic 格式（字段级覆盖未验证）。

---

## 6. 启动器参考实现

| 项目 | 仓库 | License | 备注 |
|---|---|---|---|
| PojavLauncher | <https://github.com/PojavLauncherTeam/PojavLauncher> | **GPL-3.0**（已读 LICENSE） | 主流 Android MC:Java 启动器，有 `v3_openjdk` 分支 |
| Fold Craft Launcher (FCL) | <https://github.com/FCL-Team/FoldCraftLauncher> | **GPL-3.0**（已读 LICENSE） | 基于 HMCL + Amethyst-Android，支持多版本/模组 |
| Zalith Launcher 2 | <https://github.com/ZalithLauncher/ZalithLauncher2> | **GPL-3.0**（Copyright 2025-2026 MovTery，已读 LICENSE） | 基于 Pojav 二次开发，Kotlin |
| Modrinth App | 桌面端仓库 <https://github.com/modrinth/code>（`apps/app`） | **未验证**（monorepo，未读其 LICENSE） | 官方文档称 "Tauri + Vue" 桌面应用，codename Theseus；**Android 版仓库 → 未验证**（官网 modrinth.com/app 称 open source，但未定位到 Android 源码仓库） |

**Java 运行时下载/解压**：
- PojavLauncher：`app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/utils/JREUtils.java`（`v3_openjdk` 分支，已读全文）——`initJavaRuntime()`、`relocateLibPath()`、`setJavaEnvironment()`、`launchJavaVM()`、`getJavaArgs()`、`loadGraphicsLibrary()` 等方法完整呈现 JRE 定位、`LD_LIBRARY_PATH` 拼接、环境变量注入、JVM 参数构造与 `VMLauncher.launchJVM()` 调用。
- 解压/下载逻辑不在此文件；需进一步定位（如 `MultiRTUtils` / runtime 管理模块）→ **未验证**。
- FCL：`app/src/main/.../java/com/tungsten/fcl/...`（搜索结果显示包名 `com.tungsten.fcl`，JVM 参数日志路径 `app/~~rwA78jAVkqh3ZQkq1emZQg==/app_runtime/java/jre21/lib`）→ 具体类文件路径 **未验证**。
- ZalithLauncher 2：`settings.gradle.kts` 已知（Kotlin）；JVM 参数类路径 **未验证**。

**JVM 参数构造**：
- PojavLauncher `JREUtils.getJavaArgs()`（已读）——构造 `-Djava.home`、`-Djava.io.tmpdir`、`-Duser.home`、`-Dos.name=Linux`、`-Dorg.lwjgl.opengl.libname`、`-Dglfwstub.windowWidth/Height`、`-Djdk.lang.Process.launchMechanism=FORK` 等，并 `purgeArg` 掉用户不可覆盖的参数，再补 `-Xms/-Xmx`、`-XX:ActiveProcessorCount`。这是**最直接的 JVM 参数构造范本**。

**LWJGL 渲染器（Zink/VirGL/HGL/GL4ES）**：
- PojavLauncher `JREUtils.loadGraphicsLibrary()`（已读）——按 `LOCAL_RENDERER` 选择：`opengles2/opengles2_5/opengles3 → libgl4es_114.so`；`vulkan_zink → libOSMesa.so`；`opengles3_ltw → libltw.so`；默认回退 GL4ES。
- PojavLauncher 官方渲染器说明：<https://www.pojav.ru/wiki/faq/android/RENDERERS.html>（GL4ES 为默认，Zink/VirGL 备选）。
- FCL 渲染器：`FCL-Team/lwjgl3-fcl`（LWJGL3 for FCL，已验证存在）；渲染器实现类路径 **未验证**。
- ZalithLauncher 2：`GoyDevv/IronizedZink` 是给 Zalith 2 / FCL 的 Zink 渲染器插件（二手）；PanVK/Kopper/Zink 见 issue #2096（二手）。
- **HGL**：未见明确对应实现 → **未验证**。

判定：可完整实现 — Pojav/FCL/Zalith 均 GPL-3.0 且源码可读，Pojav 的 `JREUtils.java` 提供了 JVM 参数与渲染器库选择的完整范本；但 FCL/Zalith 的具体类路径、Modrinth Android 仓库仍未验证。

---

## 7. 陶瓦/EasyTier 联机

**Terracotta**：
- 主仓库：<https://github.com/burningtnt/Terracotta>（约 231 stars，Rust）。**License**：**AGPL-3.0**（已读 raw LICENSE，GNU Affero General Public License v3）。
- 描述：基于 EasyTier 的 Minecraft 联机工具，集成 HMCL 支持。
- `planetfactions/Terracotta`：存在但 `main` 分支 LICENSE 路径 404，分支/许可证 **未验证**。另见 `PCL-Community/Terracotta-lib`（分支版本，支持作为 Rust 库使用，二手）。

**EasyTier**：
- 仓库：<https://github.com/EasyTier/EasyTier>。**License**：**LGPL-3.0**（已读 raw LICENSE，GNU Lesser General Public License v3）。**注意：非 AGPL，是 LGPL-3.0**。
- 官方：<https://www.easytier.cn/>

**Android P2P 联机 + VPNService 先例**：
- EasyTier 官方自带 Android 支持：`tauri-plugin-vpnservice/android/src/main/java/TauriVpnService.kt`（已验证文件存在）——这是 VPNService 的官方实现先例。
- 另有 `easytier-contrib/easytier-android-jni`，`Kotlin/com/easytier/jni/EasyTierJNI.kt`（已验证路径），提供 Android 应用经 JNI 调用 EasyTier 核心的接口（PR #1340 引入 Android JNI 绑定）。
- 第三方：`qingkong9579/easytier-miuix`（EasyTier Android 客户端，Compose + miuix）；`amelia4angela/orbit-easytier-android`（Android 6+ EasyTier VPN 客户端，Rust JNI 核心）。
- DeepWiki 文档：<https://deepwiki.com/EasyTier/EasyTier/5.7-mobile-application>

判定：高难度 — EasyTier LGPL-3.0 + Android JNI/VPNService 先例充分（可参考实现），但 Terracotta 为 **AGPL-3.0**（网络 copyleft），若 Vaelin 将其作为库链接/分发，存在触发源码披露义务的风险，需法务评估后再定集成方式。

---

## 8. 地区闸（Android 上判定"中国大陆"）

**可用信号**：
1. `Locale.getDefault().country` / `Locale.getDefault().toLanguageTag()` — 无权限，但受系统语言/SIM 影响，非网络归属。
2. `TelephonyManager.getSimCountryIso()` — SIM 卡归属国 ISO 码，**需要 `READ_PHONE_STATE`**（运行时权限）。
3. `TelephonyManager.getNetworkCountryIso()` — 当前网络归属国 ISO 码，**Android 10 起需要 `READ_PHONE_STATE`**（官方 commit `92203589` "Added read phone state permission for getNetworkCountryIso"）。参考 <https://developer.android.com/reference/android/telephony/TelephonyManager>。

**Android 10+ 行为变化**：
- Android 10 起，设备标识符（IMEI/MEID、IMSI、SIM、build serial）被 `READ_PRIVILEGED_PHONE_STATE` 保护；此前由 `READ_PHONE_STATE` 运行时权限保护。参考 <https://developer.android.com/about/versions/10/privacy/changes> 与 <https://source.android.com/docs/core/connect/device-identifiers>。
- 注：`getSimCountryIso`/`getNetworkCountryIso` 返回的是 ISO 国家码，**不属于**设备标识符范畴，但仍受 `READ_PHONE_STATE` 门控。
- Android 14/15 是否有进一步收紧 → **未验证**（未在官方 Android 14/15 行为变更中检索到针对 `getNetworkCountryIso` 的额外条款）。

**官方文档**：
- TelephonyManager API：<https://developer.android.com/reference/android/telephony/TelephonyManager>
- Android 10 隐私变更：<https://developer.android.com/about/versions/10/privacy/changes>
- 设备标识符政策：<https://source.android.com/docs/core/connect/device-identifiers>

判定：可完整实现 — `getSimCountryIso`/`getNetworkCountryIso` 需 `READ_PHONE_STATE`，Android 10+ 权限门控明确；无 SIM 的平板/纯 Wi-Fi 设备需回退 `Locale`（精度较低）。

---

## 9. GitHub Actions 构建 Android 模块

**可行性**：公开仓库用 Actions 构建**不依赖 NDK 的 Kotlin/Compose 模块**并产出 zip，**常规可行**。多处模板/示例证明：
- `ashtanko/compose-android-template` 的 `.github/workflows/ci.yml`（已验证存在）——`env: JAVA_VERSION: 21`，典型 Compose 模块 CI。
- `android/compose-samples` 的 `.github/workflows/build-sample.yml`（已验证存在）。
- `soraiyu/android-apk-template`、`StringManolo/KotlinApkTemplate`、`Professional-X/AndroidForge`（drop-in ZIP → APK 构建服务）——均为公开仓库 CI 构建模板。
- `ntufar/stasi/.github/workflows/android-ci.yml`（已验证存在）——lint + JVM 单测 + debug APK，Compose 仪器测试用 API 34 x86_64 模拟器，产物从 run summary 下载。

**典型 workflow 要点**：
- `actions/setup-java`（Temurin 21）、`gradle/actions/setup-gradle`（缓存）、`android/actions/setup-android`（接受 SDK 许可、装 build-tools/platform）。
- 用 `./gradlew :module:assembleRelease` + `actions/upload-artifact` 产出 zip。
- 无 NDK 时不需要 `android-ndk-setup`。

判定：可完整实现 — 无需 NDK 的 Kotlin/Compose 模块 CI 构建是成熟做法，有多个公开模板；唯一风险是 GitHub Actions 对 `dl.google.com` 的访问（本机直连正常，GH 云端不受影响）。

---

## 10. Shizuku

**API 接入方式**：
- 仓库：<https://github.com/RikkaApps/Shizuku-API>（The API and developer guide for Shizuku and Sui，约 2.7k stars）。
- **Maven 坐标**：`dev.rikka.shizuku:api`，最新版本 **13.1.5**（证据：Maven Central `repo.maven.apache.org/maven2/dev/rikka/shizuku/api/13.1.5/`，2023-09-21 发布）。Central 页面：<https://central.sonatype.com/artifact/dev.rikka.shizuku/api>
- 另有 `dev.rikka.shizuku:provider`（供 Shizuku 服务端使用）。
- **AIDL 依赖**：Shizuku API 通过 AIDL 与 Shizuku 服务进程通信；接入时需在 `AndroidManifest.xml` 声明 Shizuku 的 `IBindService` 意图与权限。具体 AIDL 接口定义路径 → **未验证**（未读 `Shizuku-API` 源码树）。

**License**：**MIT**（已读 raw LICENSE，Copyright (c) 2021 RikkaW）。

**能力边界**：Shizuku 让普通应用经 ADB/root 启动的 Java 进程**直接使用系统 API**，可执行 shell 级操作（如 `pm`、`am`、访问 `/data` 等）；**不能突破系统沙箱到任意 root**，能力取决于 Shizuku 服务进程本身的权限（ADB shell 权限或 root）。官方：<https://shizuku.rikka.app/>

判定：可完整实现 — MIT、Maven Central 坐标明确、API 成熟；AIDL 接口细节与最新 API 版本（13.1.5 为 2023 版，**是否有更新的 Shizuku 版本 → 未验证**）需读源码确认。

---

## 附：未验证清单（需后续补证）

1. AGP 9.4/9.5 与 KGP 2.4.21 的官方配套口径（内置 Kotlin vs 独立 KGP 兼容表）。
2. compileSdk 官方建议单一数值。
3. Android 15 是否在 Android 14 之上追加 DexClassLoader/dexopt 限制。
4. "模块 APK 打包 Compose UI 且运行时共享宿主 Compose runtime"的可核实先例。
5. cubiomes 根目录是否自带 CMake 文件；本机 NDK 是否已安装。
6. Nucleation 官方无 Android 先例；本机 Rust 工具链状态。
7. schematic4j 字段级覆盖（BlockState 现代解析）。
8. FCL/Zalith 的 JVM 参数类路径；Pojav 运行时下载/解压的具体类。
9. Modrinth Android 版仓库与 License。
10. HGL 渲染器实现对应项目。
11. Shizuku AIDL 接口定义路径与是否有更新版本。
