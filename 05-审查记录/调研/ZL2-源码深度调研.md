# Zalith Launcher 2 源码深度调研报告

> 面向后续研究与二次开发 · 基于本地实际源码的静态分析
> 调研日期：2026-11-05 · 源码路径：`C:/Users/Administrator/WorkBuddy/2026-11-05-16-27-46/ZalithLauncher2/`

---

## 0. 执行摘要

**结论先行**：Zalith Launcher 2（下称 ZL2）是一个以 **PojavLauncher 原生启动内核 + Jetpack Compose/Material3 UI** 构成的 Android Minecraft: Java Edition 启动器。它并非传统意义上的"单进程 App"，而是一个 **多进程 + 双 JVM（ART 与 dlopen 出来的 HotSpot JVM）+ 多 ABI 原生库 + 可热插拔 APK 插件** 的复合系统。其二次开发的最大约束不在业务代码，而在以下几处硬耦合：

1. **targetSdk 被刻意钉死在 34**（`FullScreenAppCompatActivity.kt:143` 明确注释：升到 35 会强制 edge-to-edge，导致全屏实现失效）；
2. **`BuildKeys` 由第三方 Gradle 插件在编译期生成**，源码中无该类的实现，改构建链必须先解决此依赖；
3. **组件 `assets/**/manifest.json`（SHA-1 清单）不入库**，由 `merge*Assets` 任务在构建时生成，源码直接打包会失败；
4. **LWJGL Java 模块把产物直接写进 app 模块的源码目录**（`$rootDir/ZalithLauncher/src/main/assets/...`），这是跨模块的非常规写入耦合；
5. **GPL-3.0 附带 GPLv3 §7 附加条款**（改名/标注"非官方修改版"/不得移除版权声明），对 fork 分发构成实质性法律约束。

源码中同时存在 112 个全局设置项、5 类 APK 插件、4 套内置 JRE（8/17/21/25）、2 套 LWJGL（3.3.3/3.4.1）与 4 种 ABI，配置维度对运行时的影响是可观且非局部的。

**证据分级**（全文使用）：

| 标记 | 含义 |
|---|---|
| **【验】** | 已验证：直接读取本地源码/配置文件得出，附文件与行号 |
| **【推】** | 推测：基于代码结构、命名、注释或上下游关系的合理推断，未做运行时验证 |
| **【缺】** | 未验证/资料缺口：受限于获取方式、缺少历史、缺少构建或缺少设备 |

---

## 1. 源码获取、可信度与限制

### 1.1 仓库定位与元信息

| 项 | 值 | 依据 |
|---|---|---|
| 官方仓库 | `github.com/ZalithLauncher/ZalithLauncher2` | 【验】GitHub API `repos/ZalithLauncher/ZalithLauncher2`；`README.md:40` 给出 SSH 克隆地址 |
| 默认分支 | `main` | 【验】GitHub API `default_branch` |
| 本次源码对应 HEAD | `67c63a9ee87e66e03effe6fff3ab8cca2970f197` | 【验】`git ls-remote`（经 ghproxy.net 镜像）返回 HEAD；`main` 分支 |
| 版本 | `versionName = 2.8.1`，`versionCode = 200048` | 【验】`ZalithLauncher/gradle.properties:18-19` |
| 许可证 | GPL-3.0 + README 中 GPLv3 §7 附加条款 | 【验】`LICENSE`（35,133 B）、`README.md:47-58` |
| 主语言 | Kotlin（app 模块 854 个 `.kt`、46 个 `.java`、42 个 `.c`） | 【验】本地文件统计 |
| 仓库状态 | 活跃（API `pushed_at` = 2026-11-05），2184 stars / 503 forks / 94 open issues | 【验】GitHub API 快照 |

需特别区分：**`ZalithLauncher/ZalithLauncher`（已归档，ZL1，包 `net.kdt.pojavlaunch`）与 `ZalithLauncher/ZalithLauncher2`（ZL2，包 `com.movtery.zalithlauncher`）是两个完全不同的项目**。ZL2 的 README 明确声明这一点；网络检索中出现的 `ZerythLauncher/Zeryth-Launcher`、`Vera-Firefly/ZalithLauncher2`、`rhydinlioren/ZalithLauncher2` 均为 fork/改名副本，非本报告分析对象。【验】`README.md` + 检索结果 + 包名。

### 1.2 实际获取方式（重要限制）

- 直连 `https://github.com/...` 的 `git clone` 在本环境被代理以 **HTTP 502 CONNECT tunnel failed** 拒绝；绕过代理直连超时。
- `ghproxy.net` / `gh-proxy.com` 镜像可完成 `git ls-remote`，但浅克隆传输极慢（约 9 分钟仅 ~80 MB / 5 分钟 ~7 MB），不可实用。
- 最终采用 **GitHub 官方 codeload 归档**：`https://codeload.github.com/ZalithLauncher/ZalithLauncher2/zip/refs/heads/main`，413,259,749 字节，26 秒下载完成，解压到工作区 `ZalithLauncher2/`（**582 MB / 1,743 个文件**）。

**因此存在以下明确限制**【缺】：

1. 本地副本 **不含 `.git` 元数据**，无提交历史、无 `git log`/`git blame`、无 tag、无分支信息；所有结论是**单一时点快照**，无法判断某段代码的引入时间、作者意图或演进方向。
2. 仓库无 `.gitmodules`，但 CI 使用 `actions/checkout@v6` 的 `submodules: recursive`（`.github/workflows/build.yml:36-39`）——【推】该参数为模板遗留，当前无实际子模块。
3. **未执行 Gradle 构建、未编译、未安装到 Android 设备、未做动态追踪**。所有"运行逻辑"结论来自静态代码阅读与调用关系，实际时序、竞态、厂商 ROM 差异未验证。
4. 未分析 JNI C/C++ 实现细节（仅列出模块与导出接口），也未反编译 `assets/**/*.jar`、`libs/*.aar`，故 `mio.Wrapper`、LWJGL patch 内部行为属于【推】/【缺】。
5. 第三方依赖的实际版本解析、构建可复现性未验证（受网络与工具链限制）。

---

## 2. 代码整体结构

### 2.1 顶层目录与模块清单

`settings.gradle.kts` 声明 10 个 Gradle project（含 2 个 LWJGL 版本子模块与 1 个 patches 子模块）：

| 模块 | 文件数 / 体积 | 类型 / namespace | 主要职责 | 依据 |
|---|---|---|---|---|
| `ZalithLauncher` | 1472 / 522.6 MB | Android Application，`com.movtery.zalithlauncher` | 主程序：UI、账户、版本管理、下载、启动、插件、JNI | 【验】目录统计 + `build.gradle.kts` |
| `LWJGL` + `:patches` + `:lwjgl-3.3.3` + `:lwjgl-3.4.1` | 94 / 23.3 MB（30 个 jar） | 纯 Java 模块 | 合并/修补 LWJGL 3.3.3 与 3.4.1 类库，产出 merged-modules.jar 到 app assets | 【验】`LWJGL/*/build.gradle.kts` |
| `LayerController` | 58 / 0.3 MB | Android Library，`com.movtery.layer_controller` | 虚拟按键布局的数据模型、可观察状态、序列化、吸附/编辑 | 【验】目录清单 |
| `InputMap` | 12 / 0.1 MB | Android Library（Java 为主），`com.movtery.inputmap` | AWT/GLFW/LWJGL2 键码映射表、Minecraft 键位映射 | 【验】目录清单 |
| `CardGrid` | 16 / 0.1 MB | Android Library Compose | 首页卡片网格布局引擎与状态 | 【验】`engine/GridEngine.kt`、`state/CardGridState.kt` |
| `ColorPicker` | 11 / 0.04 MB | Android Library Compose | 颜色选择器 | 【验】目录清单 |
| `Guide` | 21 / 0.1 MB | Android Library Compose | 新手引导 Overlay / Registry / 定位求解 | 【验】目录清单 |
| `PetLib` | 30 / 0.6 MB | Android Library + **Rust cdylib**，`com.movtery.petlib` | 桌宠渲染（JNI + gltf 模型） | 【验】`rust/src/*.rs`、`PetLib/build.gradle.kts` |
| `Terracotta` | 10 / **30.1 MB** | Android Library，`net.burningtnt.terracotta` | 联机（Terracotta）原生库 4 个 `.so` + `TerracottaAndroidAPI.java` | 【验】目录清单 |
| `gradle/` | 3 | 版本目录 `libs.versions.toml`、wrapper | 依赖与 Gradle 版本 | 【验】 |

`ZalithLauncher/src/main/` 内部：

| 子目录 | 内容 | 说明 |
|---|---|---|
| `java/` | 871 个文件 | Kotlin 与 Java 混编，包 `com.movtery.zalithlauncher.*`，另含第三方来源包 `org.libsdl.app`（13 个 Java）、`org.lwjgl.glfw`、`org.jackhuang.hmcl`（HMCL 移植）、`com.oracle.dalvik` |
| `jni/` | 80 个文件（`.c/.h/.mk/.cpp`） | ndk-build，13 个顶层 C 文件 + `ctxbridges/`、`jsound/`、`linkerhook/` 等子目录 |
| `jniLibs/` | 43 个 `.so` | 按 4 个 ABI 分目录（arm64-v8a 12 / armeabi-v7a 13 / x86 6 / x86_64 12） |
| `assets/` | 125 个文件 | JRE 8/17/21/25（`.tar.xz`）、JNA 5.13–5.19 各 ABI natives、LWJGL jar、组件 jar、log4j 配置、法律文本、textmate 语法 |
| `res/` | 291 个文件 | 以 `drawable/` XML 矢量图为主（149 xml + 70 png）；**仅 1 个 `layout/*.xml`** —— UI 几乎全部 Compose |
| `AndroidManifest.xml` | 10,700 B | 见 §2.5 |
| `libs/`（模块根） | 10 个 aar/jar，约 112 MB | 预编译渲染器/原生库，通过 `fileTree` 引入 |

【验】以上均来自本地文件统计与目录遍历。

### 2.2 模块划分与依赖图

```
                    :ZalithLauncher (app)
                    ├─ :LayerController ──── :InputMap
                    ├─ :ColorPicker
                    ├─ :CardGrid
                    ├─ :Terracotta
                    ├─ :InputMap
                    ├─ :Guide
                    ├─ :PetLib        (preBuild → cargoBuild → jniLibs)
                    ├─ fileTree("libs/*.aar|*.jar")
                    └─ 外部 Maven 依赖（libs.versions.toml）

 :LWJGL (aggregator, task buildLwjgl)
    ├─ :LWJGL:lwjgl-3.3.3 ─┐
    ├─ :LWJGL:lwjgl-3.4.1 ─┼─→ :LWJGL:patches
    └─ 产物写入 ZalithLauncher/src/main/assets/app_runtime/lwjgl/<ver>/
```

【验】`settings.gradle.kts:27-42`、`ZalithLauncher/build.gradle.kts:346-352`、`LayerController/build.gradle.kts`、`LWJGL/*/build.gradle.kts`。

**app 模块对各子模块的实际引用强度**（统计引用其包名的 app 源文件数）【验】：

| 子模块 | 被引用文件数 | 判断 |
|---|---:|---|
| `LayerController` | 36 | 强耦合：控制布局贯穿 UI 与游戏输入 |
| `InputMap` | 16 | 强耦合：键码常量被 `VMActivity`、`CallbackBridge`、控制编辑器直接使用 |
| `Guide` | 7 | 中等 |
| `PetLib` | 6 | 局部（首页/主题） |
| `Terracotta` | 6 | 局部（联机） |
| `CardGrid` | 6 | 局部（首页卡片） |
| `ColorPicker` | 3 | 局部（主题/编辑器） |

**结论**：`LayerController` + `InputMap` 是与主程序耦合最深的两个模块（控制布局与键码是全局共享语义），其余子模块边界较清晰、可独立替换。【验 + 推】

### 2.3 外部依赖分组

`gradle/libs.versions.toml` 定义 60+ 版本与 90+ 库别名，`ZalithLauncher/build.gradle.kts:307-398` 分组引入【验】：

| 分组 | 代表库 | 作用 |
|---|---|---|
| UI/Compose | `compose-bom 2026.09.00`、`material3 1.5.0-beta02`、`navigation3 1.2.0`、`constraintlayout-compose`、`reorderable`、`compose-richtext` | 全 Compose UI + Navigation 3 导航栈 |
| 图片 | `coil3` (compose/gif/svg/network-ktor3) | 图片/背景加载，`ZLApplication.newImageLoader` 配置 |
| 网络 | `ktor 3.6.1`（client-okhttp、server-cio、serialization）、`okhttp 5.5.1`、`okio`、`minidns-hla` | API、下载、内置 HTTP 服务（离线 Yggdrasil / TouchController 代理）、DoH 回退 |
| 编辑器 | `sora-editor` BOM 0.24.7 + `language-textmate` | 文件管理器内置代码编辑器 |
| 存储/状态 | `MMKV 1.3.14`、`Room 2.8.5`、`SQLCipher 4.20.0`、`toml4j`、`gson 2.15.0` | 设置、账户/游戏目录 DB |
| DI | `Hilt 2.60.1`、`KSP 2.3.12` | `@HiltAndroidApp`、`@AndroidEntryPoint` |
| 工具 | `commons-io/compress/codec`、`xz`、`zip4j`、`bytehook 1.0.10`、`process-phoenix 3.0.0`、`fishnet 1.1.0`、`material-kolor`、`lunarcalendar` | 解包、native hook、进程重启、native crash 捕获、主题 |
| 游戏相关 | `proxy-client-android`（TouchController）、`media3-exoplayer`、`webkit`、`appcompat` | 触控代理、视频背景、内置 WebView、对话框基座 |
| 测试 | junit4、mockwebserver3、espresso、compose-ui-test | 仅本地单测 |

**值得注意**：`sqlcipher-android` 被声明为 `implementation`，但全仓库 Kotlin/Java 源码中**未出现任何 `net.zetetic` / `SQLCipher` / `SupportOpenHelperFactory` 调用**；`AppDatabase.kt:72-78` 使用标准 `Room.databaseBuilder(...).build()`。因此【验】当前账户数据库 `launcher_data.db` **未启用 SQLCipher 加密**，该依赖是残留或预备；【推】若二次开发需要加密凭据，需要自行接入 `SupportFactory` 并处理迁移。

### 2.4 构建与配置项

#### 2.4.1 构建脚本层次

| 文件 | 关键内容 |
|---|---|
| `settings.gradle.kts` | `pluginManagement` 限定 google/mavenCentral/gradlePluginPortal；`dependencyResolutionManagement` 为 `FAIL_ON_PROJECT_REPOS`；`foojay-resolver-convention 0.8.0` 自动解析 JDK toolchain；`rootProject.name = "ZalithLauncher"` |
| `build.gradle.kts`（根） | 声明 5 个插件 `apply false`；`buildscript` 从 **JitPack** 取 `libs.buildkeys`（`com.github.MovTery.BuildKeys:core:1.0.0`） |
| `gradle.properties`（根） | `org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8`；`android.useAndroidX=true`；`nonTransitiveRClass=true`；`uniquePackageNames=false`；`r8.strictFullModeForKeepRules=false` |
| `ZalithLauncher/gradle.properties`（模块） | **`launcher_name`、`launcher_app_name`、`launcher_short_name`、`url_home`、`launcher_version_code/name`、`default_store_password`、`default_key_password`**；`oauth_client_id` / `curseforge_api_key` 为注释占位 |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle **9.5.0** |
| `gradle/libs.versions.toml` | AGP **9.3.0**、Kotlin **2.4.21**、KSP 2.3.12、`compileSdk` 相关、`minSdk 26`、`targetSdk 34` |

【验】全部文件已完整读取。

#### 2.4.2 应用构建关键逻辑（`ZalithLauncher/build.gradle.kts`）

- **编译期生成配置类**：`buildKeys { ... }`（`:297-305`）生成 `BuildKeys.OAUTH_CLIENT_ID`、`LAUNCHER_NAME`、`LAUNCHER_IDENTIFIER`、`LAUNCHER_SHORT_NAME`、`URL_HOME`、`CURSEFORGE_API`、`BUILD_ARCH`。取值优先级 `getKeyFromLocal()`（`:34-43`）为：**环境变量 → 根目录下指定文件 → gradle.properties 默认值 → 空串并打 WARN**。【验】根 `.gitignore:12-15` 忽略 `.oauth_client_id.txt` 等四个密钥文件。
- **签名**：`releaseBuild` 用 `zalith_launcher.jks` + `STORE_PASSWORD`/`KEY_PASSWORD`（env 或 `.store_password.txt`/`.key_password.txt`）；`debugBuild` 用 `zalith_launcher_debug.jks` + 明文密码。【验】**两个 `.jks` 文件本身都随仓库分发**（`ZalithLauncher/zalith_launcher.jks` 2,552 B、`zalith_launcher_debug.jks` 2,564 B），仅 release 密码未入库。
- **应用标识**：`applicationId = "com.movtery.zalithlauncher"`，`applicationIdSuffix = ".v2"`；debug 再叠加 `.debug` → `com.movtery.zalithlauncher.v2.debug`。Manifest 用 `${packageName}` 生成 provider authorities。【验】`:131-139`、`:151-156`。
- **ABI 分包**：`-Darch=all|arm|arm64|x86|x86_64` 控制 `splits.abi`（`:163-175`），输出文件名 `$launcherName-$version[-$abi].apk`（`:278-281`）。CI 5 个 arch 并行。【验】`build.yml:26-27, 86-89`。
- **资产裁剪与清单生成（核心自定义构建逻辑）**：`androidComponents.onVariants` 挂到 `merge<Variant>Assets` 的 `doLast`（`:212-285`），执行
  1. 按 `projectArch` 删除非目标架构的 JRE 包（保留 `version`、`*universal*`、`bin-$arch.tar.xz`）；
  2. 删除非目标 ABI 的 LWJGL natives 与 JNA natives；
  3. **`writeComponentAssetManifests()`**（`:58-106`）为 `components/auth_libs`、`components/caciocavallo`、`components/caciocavallo17`、`components/launcher`、`runtimes/jna` 及每个 `app_runtime/lwjgl/<ver>` 生成 `manifest.json`（字段 `formatVersion/component/algorithm/SHA-1/files[{path,size,sha1}]`）。
  【验】**本地源码中这些 `manifest.json` 均不存在**（`assets/components/` 只有 2 个 log4j XML），必须由 Gradle 构建生成；这是"源码 ≠ 可运行 APK"的关键点。
- **原生构建**：`externalNativeBuild.ndkBuild path = src/main/jni/Android.mk`；`ndkVersion = "25.2.9519653"`；`packaging.jniLibs.useLegacyPackaging = true`；`pickFirsts += **/libbytehook.so`；`buildFeatures { compose, buildConfig, prefab }`；`Java 17` + `coreLibraryDesugaring`；Kotlin `jvmTarget JVM_17`、opt-in `ExperimentalMaterial3Api`。【验】
- **R8/proguard**（`proguard-rules.pro`）：keep `org.libsdl.app.**`、`org.lwjgl.glfw.CallbackBridge`、`com.oracle.dalvik.VMLauncher`、`com.movtery.zalithlauncher.bridge.**`、VulkanChecker 系列、`CriticalNativeTest`、`opennbt`、`org.jcodings`，以及 Hilt `@AndroidEntryPoint` / `@GeneratedEntryPoint` 构造规则。【验】
- **LWJGL Java 模块**：`tasks.jar` 的 `destinationDirectory` **直接指向 `$rootDir/ZalithLauncher/src/main/assets/app_runtime/lwjgl/<ver>`**，把 lwjgl jar 合并为 `lwjgl-<ver>-merged-modules.jar` 并复制 excluded 模块；`isPreserveFileTimestamps=false`、`isReproducibleFileOrder=true` 以保证 SHA-1 清单可复现。3.3.3 用 Java 8 toolchain，3.4.1 用 Java 17 toolchain，均依赖 `:LWJGL:patches`。【验】
- **PetLib**：`cargoBuild` 任务调用 `cargo ndk --platform 26 ... build --release`，产物注入 `variant.sources.jniLibs`，且 `preBuild dependsOn cargoBuild`；缺 Rust/cargo-ndk/target 时抛 `GradleException`。【验】
- **测试**：50 个测试文件（app 43、CardGrid 3、Guide 4），**无 `androidTest` 目录**；app 单测开启 `isIncludeAndroidResources`、`isReturnDefaultValues`。【验】

#### 2.4.3 CI 配置与文档漂移

`.github/workflows/build.yml`：`workflow_call`，5 架构矩阵，JDK **21**（Temurin）、Gradle setup、Rust stable + 4 个 Android target、`cargo-ndk 4.1.2`、动态挑选/安装 NDK（回退 `27.0.12077973`），执行 `./gradlew ZalithLauncher:assemble<Variant> -Darch=<arch>`，上传 APK 与 mapping。`push_ci.yml`（任意分支 push → Debug）与 `release_ci.yml`（release published → Release + 上传 release assets）复用它。【验】

**已确认的文档/配置漂移**【验】：

| 项目 | README 声明 | 实际 |
|---|---|---|
| target API | 35（`README.md:34`） | `targetSdk = 34`（`build.gradle.kts:135`） |
| JDK | 11（`README.md:35`） | CI 用 JDK 21；app 编译 Java 17；UI 库 Java 11 |
| Android Studio | Bumblebee 以上 | AGP 9.3.0 + Gradle 9.5.0，实际需要远新版本 |
| NDK | 未声明 | `ndkVersion 25.2.9519653` vs CI 回退 NDK `27.0.12077973` |
| Rust | 未声明 | PetLib 构建必需 |

【推】NDK 版本不一致可能导致 `ndk-build` 行为差异，需在本地实构建验证；`targetSdk=34` 是**有意锁定**而非疏漏（`FullScreenAppCompatActivity.kt:143` 注释明示）。

### 2.5 AndroidManifest 组件拓扑

`ZalithLauncher/src/main/AndroidManifest.xml`【验】：

| 类别 | 组件 | 关键属性 / 进程 |
|---|---|---|
| Application | `.ZLApplication` | `theme=AppTheme.ZalithLauncher`、`appCategory=game`、`isGame=true`、`allowBackup=true`、`hasFragileUserData=true`、`usesCleartextTraffic=true`、`requestLegacyExternalStorage=true`、`preserveLegacyExternalStorage=true`、`allowNativeHeapPointerTagging=false` |
| 启动 Activity | `.ui.activities.SplashActivity` | `exported=true`、`launchMode=singleTop`、`sensorLandscape`、MAIN/LAUNCHER、`configChanges=keyboardHidden\|orientation\|screenSize\|...` |
| activity-alias | `com.movtery.activity.ImportModpackActivity` | `targetActivity=SplashActivity`，VIEW/SEND，`file`/`content` + `application/*`，`meta-data import_type=modpack` |
| activity-alias | `com.movtery.activity.ImportControlsActivity` | 同上，`application/json`，`import_type=controls` |
| Activity | `.ui.activities.MainActivity` | `singleTop`、`sensorLandscape` |
| Activity | `.ui.activities.ControlEditorActivity` | 控制布局编辑器 |
| Activity | `.ui.activities.VMActivity` | **`android:process=":game"`**，`launchMode=standard` |
| Activity | `.ui.activities.ErrorActivity` / `FatalErrorActivity` | 崩溃展示；FatalError 为 Dialog 主题 |
| Activity | `.filemanager.ui.FileManagerActivity` | **`android:process=":filemanager"`**，`taskAffinity=":filemanager"`、`exported=false`、`enableOnBackInvokedCallback=true` |
| Service | `.filemanager.events.FileManagerEventService` | `:filemanager`，`exported=false`，action `...filemanager.EVENT_SERVICE` |
| Service | `.keepalive.TaskKeepAliveService` | 主进程，`foregroundServiceType=specialUse` |
| Service | `.game.download.jvm_server.JvmService` | **`:jvm`**，`specialUse` |
| Service | `.game.launch.GameService` | **`:game`**，`specialUse` |
| Service | `.terracotta.TerracottaVPNService` | **`:game`**，`exported=true`，`permission=BIND_VPN_SERVICE`，intent-filter `android.net.VpnService` |
| Service | `AppLocalesMetadataHolderService` | `enabled=false`，`autoStoreLocales=true` |
| Provider | `.provider.FolderProvider` | `exported=true`，`grantUriPermissions=true`，`permission=MANAGE_DOCUMENTS`，`DOCUMENTS_PROVIDER` |
| Provider | `androidx.core.content.FileProvider` | `exported=false`，`@xml/provider_paths` |
| `<queries>` | `net.kdt.pojavlaunch.ffmpeg`、MAIN、TTS_SERVICE | 包可见性声明 |

权限共 14 项：INTERNET、ACCESS_NETWORK_STATE、READ/WRITE_EXTERNAL_STORAGE、**MANAGE_EXTERNAL_STORAGE**、WAKE_LOCK、VIBRATE、FOREGROUND_SERVICE(+DATA_SYNC/SPECIAL_USE/CONNECTED_DEVICE)、POST_NOTIFICATIONS、RECORD_AUDIO、HIGH_SAMPLING_RATE_SENSORS。【验】

---

## 3. 程序入口与初始化流程

### 3.1 `ZLApplication.onCreate()` 顺序（关键，顺序不可随意调整）

`ZLApplication.kt:63-116`【验】：

```
1. GlobalContext = this                       // 全局 Context 单例
2. TLSSecurity.install()                      // 进程级 SSLContext/HttpsURLConnection 默认校验（屏蔽已泄露 eMudhra CA）
3. refreshContext(this) → PathManager.refreshPaths()  // 建立全部目录常量、createDirs()、handleLegacy()
4. TaskKeepAlive.initialize(this)             // 保存 applicationContext 供前台服务使用
5. Thread.setDefaultUncaughtExceptionHandler { ... }   // 注意：在 super.onCreate() 之前
      ├─ TaskSystem.stopAll()
      ├─ 若 th is SplashException → 取 th.cause
      ├─ Logger.error + writeCrashFile(PathManager.FILE_CRASH_REPORT)
      ├─ showLauncherCrash(...)  → ErrorActivity（canRestart = th !is SplashException）
      └─ Process.killProcess(Process.myPid())
6. super.onCreate()   // Hilt 注入触发点（@HiltAndroidApp）
7. runCatching 内：
      Fishnet.init(this, DIR_NATIVE_LOGS)     // native crash 捕获，日志写到外部 files/logs/native
      MMKV.initialize(this) → loadAllSettings(this)
      Logger.initialize(this)
      initializeData() → AccountsManager.initialize / GamePathManager.initialize
      PathManager.DIR_FILES_PRIVATE = getDir("files", MODE_PRIVATE)
      DEVICE_ARCHITECTURE = Architecture.getDeviceArchitecture()
      若为 32 位 x86 设备 → 改写 applicationInfo.nativeLibraryDir 指向 /x86（华硕 Zenfone 兼容）
   .onFailure → writeCrashFile + showFatalError(this, t) → FatalErrorActivity
8. onConfigurationChanged → refreshContext(this)
9. newImageLoader(): Coil ImageLoader（20MB 内存 + 512MB 磁盘缓存、Ktor fetcher、GIF/SVG、crossfade）
```

**风险点**：`PathManager` 全部字段为 `companion object` 中的 `lateinit var`，是**进程级可变全局**；`refreshPaths()` 会重建全部路径并 `createDirs()`。`ZLApplication` 与每个 `BaseAppCompatActivity.onCreate()` 都会调用它（`BaseAppCompatActivity.kt:38`）。【验】任何在 Application 之前访问 `PathManager.*` 的代码都会抛 `UninitializedPropertyAccessException`；多进程下每个进程独立初始化一份。

### 3.2 路径与环境常量（`path/PathManager.kt`）

`DIR_FILES_PRIVATE = context.filesDir`；`DIR_FILES_EXTERNAL = getExternalFilesDir(null)`，失败时回退 `Android/data/<pkg>/files`，再失败回退内部 `data_files`（`:71-79`，注释标明来自 FCL）。其余约 30 个目录：`games/`、`runtimes/`、`jna_dir/`、`components/`、`control_layouts/`、`logs/`、`logs/native/`、`net.burningtnt.terracotta/`、`special_styles/`、各类 `cache/temp_*` 等；`FILE_CRASH_REPORT = <external>/logs/launcher_crash.log`。`handleLegacy()` 删除旧版共用游戏日志。【验】

`path/UrlManager.kt` 定义全局常量与 HTTP 客户端：`URL_PROJECT`、`URL_PROJECT_INFO`（`Zalith-Info` 仓库）、`URL_SUPPORT`（爱发电）、Mojang meta 地址、插件下载链接；`GLOBAL_JSON`（`ignoreUnknownKeys=true, explicitNulls=true, coerceInputValues=true`）、`GLOBAL_CLIENT`（Ktor/OkHttp，10s 超时）、`IMAGE_LOADER_CLIENT`、`DOWNLOAD_OKHTTP_CLIENT`（无 callTimeout、连接池 64、HTTP/1.1 或 h2）；统一注入 `User-Agent = <LAUNCHER_SHORT_NAME>/Android_<VERSION_NAME>` 与 CurseForge `x-api-key`；`ResilientDns` 系统 DNS 失败回退 DoH；`TLSSecurity` 提供 sslSocketFactory/trustManager。【验】

### 3.3 `SplashActivity` 初始化链路

`ui/activities/SplashActivity.kt`【验】：

```
onCreate: installSplashScreen() → super → initUnpackItems() → setContent{ SplashScreen(...) }
          → lifecycleScope.launch(Main):
                switchScreen(Loading)
                withContext(IO): checkAllTask()  // 逐个 task.checkState()，统计已完成数
                                 loadAvailableAgreement() // assets/legal/data.json → AvailableAgreement
                若 协议未同意 或 welcomeVersion < 1 → switchScreen(Welcome)（旧版已同意则弹更新提示）
                否则 checkTasksToMain()
```

`initUnpackItems()` 构造安装项列表：`Components.entries`（6 个：auth_libs / caciocavallo / caciocavallo17 / LWJGL 3.3.3 / LWJGL 3.4.1 / launcher）+ `Jre.entries`（4 个：Internal-8/17/21/25，架构不支持或读取 `version` 失败则跳过）+ `UnpackJnaTask`，最后 `sort()`。【验】

`startAllTask()` 并发执行所有 `NOT_STARTED/PENDING/FAILED` 项，全部成功后若 `AllSettings.javaRuntime` 为空则默认设为 `Jre.JRE_8.jreName`（`Internal-8`），再 `finishSplash()`。【验】`finishSplash()` 优先处理待导入 Intent，否则 `swapToMain()` → `MainActivity`（`FLAG_ACTIVITY_CLEAR_TOP|SINGLE_TOP`）并 finish。

**外部导入链**：`onNewIntent` 在依赖未就绪时把导入 Intent 存入 `pendingImportIntent`；`resolveImportType()` 通过 `packageManager.getActivityInfo(comp, GET_META_DATA).metaData["import_type"]` 判断类型；`handleImportIntent()` 从 ACTION_SEND 的 `clipData`/`EXTRA_STREAM` 或 ACTION_VIEW 的 `data` 取 Uri，`takePersistableUriPermission`，然后把 `EXTRA_IMPORT_ACTION/URI/TYPE` 转发给 `MainActivity`。【验】

### 3.4 组件解包：SHA-1 清单 + 原子提交

这是 ZL2 的资源安装核心，值得单独说明【验】：

| 文件 | 职责 |
|---|---|
| `components/AssetManifest.kt` | `parseAssetManifest()` 校验 `formatVersion==1`、`component==expectedComponent`、`algorithm=="SHA-1"`、文件非空、路径无 `..`/绝对/反斜杠、无重复、SHA-1 格式；`verifyComponentDirectory()` 逐个比对 size+SHA-1 且要求**文件集合完全相等**（多余文件也算失败） |
| `components/UnpackSingleTask.kt` | `checkState()` 返回 FINISHED/PENDING/NOT_STARTED/FAILED；`run()` 用 `.xxx.installing` 暂存目录 + `.xxx.backup` 备份，`recoverInterruptedCommit()` 处理中断恢复，`commitComponent()` 用 `Files.move(..., ATOMIC_MOVE)`（不支持则普通 move），失败回滚 |
| `components/jre/UnpackJreTask.kt` | 读 `assets/runtimes/jre-N/version` 作为版本指纹；`isJreArchSupported()` 检查 `bin-<arch>.tar.xz` 是否存在；`run()` 调 `RuntimesManager.installRuntimeBinPack(universal + platformBins)` + `postPrepare()` |
| `UnpackComponentsTask` / `UnpackJnaTask` | 前者的薄封装；后者处理 JNA natives |
| `Launcher.verifyLwjglNatives()` | 游戏启动前**再次**校验对应 LWJGL 组件，失败就现场重解包（`Launcher.kt:93-113`） |

`GameLauncher`/`JvmLauncher` 通过 `initLwjglComponent(context, version)` 选择 3.3.3 或 3.4.1：版本 ≥3.4.1 用 3.4.1，否则 3.3.3；LWJGL2 时代（版本 1..299）额外加入 `lwjgl-lwjglx.jar`（`LaunchArgs.kt:159-181`）。【验】

### 3.5 启动链路总图（【验】静态调用关系）

```
[Android Launcher]
  → ZLApplication.onCreate()            (进程初始化、全局异常钩子、MMKV/Room/路径/Coil)
  → SplashActivity (MAIN/LAUNCHER)
       ├─ WelcomeScreen（首次/协议更新，写 MMKV welcomeVersion / acceptedAgreementVersion）
       ├─ UnpackScreen（6 组件 + 4 JRE + JNA 的 SHA-1 校验与解包）
       └─ MainActivity
            ├─ Renderers.init() / PluginLoader.loadAllPlugins()
            ├─ refreshData()：AccountsManager / AuthServers / GamePathManager
            ├─ FileManagerEventRegistrar.start()（跨进程文件变更订阅）
            ├─ NotificationManager.initManager()、更新检查、赞助者拉取
            └─ setContent { MainScreen (Navigation 3) + 各 Operation Layer }

[用户点击启动]
  EventViewModel.Event.Launch.Game → LaunchGameViewModel.tryLaunch()
  → LaunchGameOperation → LaunchGameViewModel.start() → GameLaunchFlow(viewModelScope).launch()
       Phase 1 账号校验（微软 validate/refresh、外置 validate/refresh/密码登录）
       Phase 2 扫描 Mods → 检测 FPE、touchcontroller、lwjgl3ify 补丁 → 生成/继承 GameManifest
       Phase 3 文件完整性校验（DownloadMode.VERIFY_AND_REPAIR，可跳过）
       Phase 4 runGame(context, version, account) + exitActivity()（finish MainActivity）
  → startGameService() → GameService (:game 前台服务)
  → startActivity(VMActivity, INTENT_RUN_GAME + LaunchConfig Parcelable)  [:game 进程]
  → VMViewModel.initSession() → GameLauncher + GameHandler  或  JvmLauncher + JVMHandler
  → TextureView.onSurfaceTextureAvailable → AbstractHandler.execute()
  → Launcher.launch() → launchJvm()
       setLdLibraryPath → setEnv(Os.setenv) → dlopenJavaRuntime() → dlopenEngine()
       → ZLBridge.setupExitMethod / initializeGameExitHook / chdir
       → VMLauncher.launchJVM(args)  [native: jre_launcher.c / libpojavexec.so]
  → HotSpot JVM 内执行 mio.Wrapper → Minecraft mainClass
```

---

## 4. Android 手机上的运行逻辑

### 4.1 启动链路（分阶段）

已在 §3.5 给出完整链路。补充关键实现细节【验】：

- **账号阶段**（`GameLaunchFlow.kt:156-188, 251-272`）：`isNetworkAvailable()` 决定行为；**无网络且非本地账号时**设置 `version.offlineAccountLogin = true`，此后 `GameLauncher` 把 account `copy(accountType = LOCAL)`（`GameLauncher.kt:93-100`）。微软账号按 `expiresAt - 5min` 或 `validateAccessToken` 判定刷新；外置账号走 `AuthServerHelper.validateOrRefresh()`，失败再 `passwordLogin()`。异常映射为私有 `LaunchReloginRequired` / `LaunchCheckFailed`，由 `LaunchGameViewModel` 转成 UI 操作状态。
- **版本清单阶段**：`VersionInfoParser(version).setInheriting(...).build()` 合并 inheritsFrom；若打了 lwjgl3ify 补丁，用 `GameLibDownloader` 重新检索并下载依赖库；结果 JSON 存入 `version.launchManifest`（内存态），最终在启动前再次 `setManifest + setInheriting` 生成完整清单。
- **VMActivity 会话**：`VMViewModel.initSession()` 依据 `INTENT_RUN_GAME` / `INTENT_RUN_JAR` 二选一构造 `LaunchSession(activityTitle, launcher, handler, inputSender)`；`_session != null` 直接返回，故会话在 ViewModel 生命周期内只初始化一次（跨配置变更保留）。【验】`VMActivity.kt:208-296`
- **Surface 就绪才真正启动 JVM**：`onSurfaceTextureAvailable()` 首次调用时 `vmViewModel.isRunning = true`，等待 `screenSizeBridge.awaitData()`，计算渲染尺寸，然后 `handler.execute(surface, screenSize, lifecycleScope)`（`:902-933`）。`AbstractHandler.execute()` 在 `Dispatchers.Default` 上 `launcher.launch(screenSize)` 并把退出码交给 `onExit`（`AbstractHandler.kt:47-57`）。

### 4.2 生命周期

| 组件 | 覆写的回调与行为 | 依据 |
|---|---|---|
| `ZLApplication` | `onCreate`、`onConfigurationChanged → refreshContext`；`SingletonImageLoader.Factory` | 【验】`ZLApplication.kt` |
| `AbstractAppCompatActivity` | `onCreate` 里 `GlobalContext = this`（**注意：会把全局 Context 改成 Activity**） | 【验】`ui/base/AbstractAppCompatActivity.kt:26-31` |
| `FullScreenAppCompatActivity` | `onCreate`/`onPostResume` 应用沉浸全屏；`dispatchTouchEvent` 修正厂商窗口缩放导致的 raw/局部坐标不一致；`refreshIgnoreNotch()` 设置 cutout mode | 【验】`ui/base/FullScreenAppCompatActivity.kt` |
| `BaseAppCompatActivity` | `onCreate`：`refreshContext + checkStoragePermissions()`；`onResume`： **`loadAllSettings(this, true)` 全量重载设置** + 权限复查 + `setTaskDescription`；`refreshData()` 由子类调用 | 【验】`ui/base/BaseAppCompatActivity.kt:34-70` |
| `SplashActivity` | `onNewIntent`（导入 Intent 暂存/转发） | 【验】 |
| `MainActivity` | `onCreate`（导入、渲染器/插件、文件事件、通知、更新、赞助者、事件流、Compose）；`onNewIntent` → `Renderers.init(true)` + `PluginLoader.loadAllPlugins(true)` 强制重载；`onResume` → `ControlManager.checkDefaultAndRefresh()`；`onDestroy` → `fmEventRegistrar.stop()`；`dispatchKeyEvent` 支持按键捕获模式 | 【验】`MainActivity.kt:210-222, 559-572, 799-812` |
| `VMActivity` | `onResume/onPause` 同步 GLFW_FOCUSED/HOVERED；`onStart/onStop` 同步 HOVERED；`onWindowFocusChanged` 复位输入；`onConfigurationChanged` 触发尺寸刷新；**重写 `setRequestedOrientation` 强制 sensorLandscape**（防 SDL 设为竖屏）；`onPostResume` 请求窗口尺寸刷新；`onDestroy` 注销 DisplayListener、停服务、`SdlBridge.reset()`、`FliteTts.shutdown()` | 【验】`VMActivity.kt:615-728` |
| `SurfaceTextureListener` | `onSurfaceTextureAvailable`（建 Surface、SDL 准备、启动 JVM）/ `SizeChanged`（50ms 去抖刷新）/ `Destroyed`（标记 `mIsSurfaceDestroyed`、解注册）/ `Updated`（放大镜取帧） | 【验】`:902-955` |
| `GameHandler` | `onResume` 刷新控制布局并发 `Game.OnResume`；`onDestroy` `Terracotta.setWaiting(false)`；`onGraphicOutput` 首帧时关闭启动信息/日志 | 【验】`handler/GameHandler.kt:136-157` |
| `JVMHandler` | AWT 帧渲染循环：`ZLBridge.renderAWTScreenFrame()` → `Canvas.drawBitmap`，直到 surface 销毁 | 【验】`handler/JVMHandler.kt:64-107` |
| 前台服务 | `GameService.onCreate/onStartCommand` 都调 `startForegroundNotification()`（Android 14+ 带 `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`），返回 `START_NOT_STICKY` | 【验】`GameService.kt` |
| `TerracottaVPNService` | `onStartCommand` 处理 START/STOP/REPOST/UPDATE_STATE；`onRevoke`/`onDestroy` 清理 tun；`addDisallowedApplication(自身)` | 【验】`TerracottaVPNService.java:74-161` |

**配置变更策略**：Manifest 对所有 Activity 声明了 `configChanges="keyboardHidden|orientation|screenSize|smallestScreenSize|screenLayout|keyboard|navigation"`，即**自行处理旋转与尺寸变化而不重建 Activity**；`FileManagerActivity` 额外包含 `uiMode|locale|layoutDirection`。这与 Compose + 原生 Surface 的长生命周期需求一致。【验】

### 4.3 权限与环境相关处理

**权限**【验】：

| 权限 | 处理位置 | 行为 |
|---|---|---|
| 存储 | `utils/StorageUtils.kt` | Android 11+ 用 `Environment.isExternalStorageManager()` 判定，缺失时弹不可取消对话框 → `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`（先 `resolveActivity` 检查）；Android 10 及以下用 `ActivityCompat.requestPermissions(READ+WRITE)`。**仅记录全局布尔 `hasStoragePermission` / `canHandlePermission`，并不阻塞启动流程**——`BaseAppCompatActivity.checkStoragePermissions()` 只调用 `checkStoragePermissionsForInit()` |
| POST_NOTIFICATIONS | `notification/NotificationManager.kt:47-59`、`ui/components/_Notification.kt:69` | SDK<33 用 `NotificationManagerCompat.areNotificationsEnabled()`（注释承认不能 100% 确定），≥33 用 `checkSelfPermission`；按需 `requestPermissionLauncher` 申请 |
| RECORD_AUDIO | `utils/microphone/MicMeter.kt`、`ui/screens/content/elements/MicrophoneCheckElements.kt:141` | 语音/麦克风检测界面按需申请 |
| BIND_VPN_SERVICE | `TerracottaVPNService` | `VpnService.Builder` 由原生侧 `TerracottaAndroidAPI.getPendingVpnServiceRequest().startVpnService()` 建立 |
| MANAGE_DOCUMENTS | `FolderProvider` | 作为 `DocumentsProvider` 暴露文件树给系统文件选择器 |

**环境相关处理**【验】：

- **架构探测**：`utils/device/Architecture.kt` 依据 `Build.SUPPORTED_64_BIT_ABIS` 判定 32/64 位与 x86，映射 `arm64/arm/x86_64/x86` → ABI 目录名；`ZLApplication.DEVICE_ARCHITECTURE` 为静态全局。`UnpackJreTask` 据此挑选 `bin-<arch>.tar.xz`；构建期 `-Darch` 又裁剪 assets。【验】
- **内存默认值**：`setting/SettingsInitializer.kt:55-67` 按设备总内存给出 JVM 堆默认值（32 位固定 696MB；64 位按 <1G→296 … ≥6G→2048MB），并在 `loadAllSettings()` 中从用户 `jvmArgs` 里**剥离 `-Dorg.lwjgl.opengl.libname=`**（防止用户覆盖渲染器库）。【验】
- **全屏/刘海/刷新率**：`FullScreenAppCompatActivity`、`VMActivity.applyMaxRefreshRatePolicy()`（窗口 `preferredDisplayModeId` + Surface `setFrameRate`，DisplayListener 1s 冷却防抖）。`sustainedPerformance` 与 `FLAG_KEEP_SCREEN_ON` 在 `VMActivity.onCreate` 应用。【验】
- **触摸坐标兼容**：`dispatchTouchEvent` 检测 `rawX/Y != 局部坐标 + 窗口原点` 时用 `MotionEvent.obtain` 重建事件，规避厂商缩放窗口的坐标系错位。【验】`FullScreenAppCompatActivity.kt:56-138`
- **DNS / TLS / 地区镜像**：`Launcher.ensureDNSConfig()` 生成 `<game>/resolv.conf`（系统 DNS 仅保留 IPv4；中国大陆 locale 追加 `223.5.5.5`/`119.29.29.29`，否则 `1.1.1.1`/`1.0.0.1`）；`game/addons/mirror/BMCLAPI.kt` 把 Mojang/Forge/Fabric/Maven 域名映射到 BMCLAPI 或腾讯镜像；`TLSSecurity` 屏蔽已知泄露 CA 公钥。【验】
- **游戏侧环境变量**（`Launcher.setJavaEnv`，`:483-509`）：`POJAV_NATIVEDIR`、`JAVA_HOME`、`HOME`、`TMPDIR`、`LD_LIBRARY_PATH`、`PATH`、`AWTSTUB_WIDTH/HEIGHT`、`MOD_ANDROID_RUNTIME`、**`DALVIK_JAVAVM`（ART JavaVM 指针）**、**`DALVIK_APPLICATION`（Application 全局引用地址）**、`ALSOFT_DRIVERS=opensl`，以及按设置追加 `LIBGL_VGPU_DUMP`、`POJAV_ZINK_PREFER_SYSTEM_DRIVER`、`POJAV_VSYNC_IN_ZINK`、`POJAV_FFMPEG_PATH`。【验】
- **渲染器环境变量**（`GameLauncher.setRendererEnv`，`:442-536`）：`SDL_OPENGL_LIBRARY`、`LIBGL_ES`、`POJAV_RENDERER`、`POJAVEXEC_EGL`、`SDL_EGL_LIBRARY`、`SFPEW_LAYER/EGL/RELAXED_CONTEXT`、`MESA_*`、`LIB_MESA_NAME` 等，并通过 EGL10 探测设备最高 GLES 版本作为兜底。【验】

### 4.4 界面与资源加载

- **UI 框架**：几乎全 Compose（`res/layout/` 仅 1 个 XML），Material3 + `navigation3` 自建导航栈：`ui/screens/_Navigation.kt`、`NavStateHost.kt`、`NormalNavKey`/`NestedNavKey`/`TitledNavKey`/`BackStackNavKey`、`ScreenBackStackViewModel`；`BaseScreen` 提供进出场动画。【验】
- **主题**：`ui/theme/`（ColorThemeType、DarkMode、动态取色 `material-kolor`、节日特效 `festivals/`）；`ZalithLauncherTheme` 在 Splash/Main/VM/Error 四个 Activity 中复用。【验】
- **资源加载**：`ZLApplication.newImageLoader()` 配置 Coil（20MB 内存 / 512MB 磁盘于 `DIR_IMAGE_CACHE`），支持 GIF/SVG 与 Ktor 网络；背景图片、皮肤、披风、Mod 图标经此加载。【验】
- **随包资源安装**（§3.4）：从 `assets` 解包到 `DIR_COMPONENTS` / `DIR_MULTIRT` / `DIR_JNA`，每次启动以 SHA-1 清单做校验，失败即 PENDING → 用户点"同意/开始"后重解包。【验】
- **内置运行时**：`assets/runtimes/jre-{8,17,21,25}/` 的 `universal.tar.xz` + `bin-<arch>.tar.xz`，由 `RuntimesManager.installRuntimeBinPack()` 解包；`assets/app_runtime/lwjgl/{3.3.3,3.4.1}/` 的 jar 构成游戏 classpath；`assets/components/launcher/MioLaunchWrapper.jar` 提供 `mio.Wrapper` 入口（`LaunchArgs.kt:81-82`）；`assets/components/log4j-1.7.xml` / `log4j-1.12.xml` 用于缓解 Log4Shell（`%msg{nolookups}`、`RegexFilter` 拦截 `${...}`）。【验 + 推（`mio.Wrapper` 内部行为未反编译）】
- **本地化**：`res/values-*` 覆盖 ar/es/et/fil/in/it/ja/ko/pt/pt-rBR/ru/th/tr/ug/vi/zh-rCN/zh-rTW 等；`AppLanguage` 设置 + `AppLocalesMetadataHolderService`；**Java 游戏内语言**由 `LanguageHelper`/`loadLanguage()` 与 `MCOptions` 处理。【验】
- **其他**：`Guide` 模块做首次引导；`sora-editor` + textmate 语法用于内置文本/代码编辑；`media3-exoplayer` 用于视频背景。【验】

### 4.5 状态管理

| 层 | 机制 | 关键文件 | 说明 |
|---|---|---|---|
| 全局设置 | **MMKV**（`MULTI_PROCESS_MODE`） | `setting/_MMKV.kt`、`AllSettings.kt`、`SettingsRegistry.kt`、`setting/unit/*` | `AllSettings` 单例含 **112 个设置项**（string/int/long/float/bool/enum/offset/parcelable/stringList/NullableInt）；每项 `AbstractSettingUnit` 持 `MutableStateFlow`，`reloadAll()` 支持跨进程重载。`launcherMMKV()` 以 **`BuildKeys.LAUNCHER_IDENTIFIER`** 作为 MMKV ID |
| 结构化数据 | **Room**（DB `launcher_data.db`, version 2） | `database/AppDatabase.kt`、`Converters.kt` | 实体 `Account`、`AuthServer`、`GamePath`；`MIGRATION_1_2` 增加 `accounts.expiresAt`。`AccountsManager.initialize()` 取单例 |
| UI 状态 | **ViewModel + StateFlow** | `viewmodel/` 18 个类 | `ScreenBackStackViewModel`、`LaunchGameViewModel`、`VMViewModel`、`ErrorViewModel`、`EventViewModel`（事件总线）、各 Operation 状态 |
| 任务状态 | `TaskSystem` + `TaskFlowExecutor` | `coroutine/` | 全局 `CoroutineScope(Dispatchers.Default)`、`ConcurrentHashMap<String, Job>`、`MutableStateFlow<List<Task>>`；`submitTask` 自动 `TaskKeepAlive.acquire()`，`invokeOnCompletion` 一定 `release()`；`stopAll()` 会 `scope.cancel()` |
| 保活 | `TaskKeepAlive` | `keepalive/TaskKeepAlive.kt` | 引用计数 + 1.5s 延迟停服，`startForegroundService` 拉起 `TaskKeepAliveService`；`onServiceForegroundStarted` 处理"尚未进入前台不能停"的竞态 |
| 全局单例 | `GlobalContext`、`PathManager.*`、`Renderers`、`PluginLoader.allPlugins`、`ZLBridgeStates`、`AccountsManager`、`VersionsManager`、`RuntimesManager` | 分散 | 【推】多为 `object`/`companion object`，无显式依赖注入，构成隐式全局耦合 |
| 跨进程 | MMKV 多进程模式 + `:filemanager` 的 `FileManagerEventService`/`FileManagerEventRegistrar`/`FileManagerEventBus` | `filemanager/events/` | `MainActivity.onFileManagerEvent()` 检测 `versions/` 目录变化后 `VersionsManager.refresh()` |

**多进程拓扑**【验】：主进程（Application/所有 Activity 除 VMActivity/FileManagerActivity）、`:game`（VMActivity + GameService + TerracottaVPNService）、`:jvm`（JvmService）、`:filemanager`（FileManagerActivity + EventService）。因为 `ZLApplication` 是 application 级，**每个进程都会执行一遍 `onCreate`**，即 MMKV/Fishnet/PathManager/异常钩子/TaskKeepAlive 会初始化 4 次。【推】这是理解跨进程状态与崩溃日志的关键。

### 4.6 与 Android 系统的交互

| 交互面 | 实现 | 依据 |
|---|---|---|
| 外部文件导入 | 两个 `activity-alias` 接 VIEW/SEND + `content://`/`file://`；`ContentResolver.takePersistableUriPermission`；转发到 MainActivity | 【验】Manifest + `SplashActivity.kt:365-420`、`MainActivity.kt:739-797` |
| SAF / 文件共享 | `FolderProvider`（`DocumentsProvider`，自定义 `mt:*` call 方法支持 setLastModified / setPermissions / createSymlink）与 `FileProvider`（`@xml/provider_paths` 暴露 external-files/external-cache/cache/external 根） | 【验】`provider/FolderProvider.java`、`res/xml/provider_paths.xml` |
| 独立进程文件管理器 | `FileManagerLauncher.launch()` + 事件服务，主进程订阅文件变更 | 【验】`MainActivity.kt:221-222, 577-586` |
| 前台服务 | `TaskKeepAliveService`（下载保活）、`GameService`（游戏进程保活）、`JvmService`（JVM 任务）、`TerracottaVPNService`（VPN 联机）；均为 `specialUse` + `PROPERTY_SPECIAL_USE_FGS_TYPE` 声明 | 【验】Manifest + `GameService.kt`、`TerracottaVPNService.java` |
| VpnService | `Builder().setSession("Terracotta Connection").addDisallowedApplication(self)`，原生侧持有 pending request | 【验】 |
| 输入 | `dispatchKeyEvent`/`dispatchTouchEvent`/`dispatchGenericMotionEvent`、`InputDevice`（物理鼠标/手柄）、`MotionEvent` 坐标修正、`CallbackBridge.nativeSend*` / `ZLBridge.sendInputData`、`HIGH_SAMPLING_RATE_SENSORS` 陀螺仪 | 【验】`VMActivity.kt`、`CallbackBridge.java:538-555` |
| 显示 | `DisplayManager.DisplayListener`、`WindowManager.LayoutParams`（全屏、刘海、刷新率、KEEP_SCREEN_ON、sustainedPerformance）、`TextureView`/`Surface` | 【验】 |
| 剪贴板 / 分享 / 打开链接 | `ZLNativeInvoker.querySystemClipboard/putClipboardData/openLink`（供 JVM 侧 JNI 回调）、`shareFile()`、`openLinkInternal()` | 【验】`bridge/ZLNativeInvoker.kt` |
| TTS / 音频 | `FliteTts`（native flite）、`SDLActivity.isTextInputEvent`；`<queries>` 声明 TTS_SERVICE | 【验】`bridge/FliteTts.kt`、Manifest |
| 通知 | `NotificationManager.initManager()` 建渠道；`NotificationChannelData` 枚举；崩溃日志上传/分享 | 【验】 |
| 应用更新与远程信息 | `upgrade/GithubContentApi.kt` 访问 `Zalith-Info` 仓库 `contents/v2`；`LauncherUpgradeViewModel.checkOnAppStart()`；`sponsor/` 拉当月赞助者 | 【验】`UrlManager.kt:54`、`MainActivity.kt:227-239` |
| 插件发现 | `PluginLoader.loadAllPlugins()` 用 `packageManager.queryIntentActivities(ACTION_MAIN, GET_META_DATA\|GET_SHARED_LIBRARY_FILES)` 扫描已安装 APK，交给 5 个 Manager 解析 | 【验】`game/plugin/PluginLoader.kt:49-101` |

### 4.7 异常处理

**三层防线**【验】：

1. **Java/Kotlin 未捕获异常**：`ZLApplication.kt:70-88` 的全局 `UncaughtExceptionHandler` —— 停止所有任务 → 解包 `SplashException`（启动阶段异常被包装成它，`SplashException.java` 仅持有 cause）→ 写 `launcher_crash.log` → `showLauncherCrash()` 启动 `ErrorActivity`（`CLEAR_TASK|NEW_TASK`，`EXIT_LAUNCHER`，`canRestart` 依据是否 SplashException）→ **`Process.killProcess(myPid())`**。
2. **Application 初始化失败**：`runCatching{...}.onFailure` → 同样写崩溃文件，但显示 **`FatalErrorActivity`**（独立、极简、Dialog 主题，不继承 `BaseAppCompatActivity`，避免依赖已损坏的初始化链），只展示栈与复制按钮。
3. **Native 崩溃**：`Fishnet.init(this, DIR_NATIVE_LOGS)`（`ZLApplication.kt:92`）【推】捕获 native signal 并落盘到 `<external>/logs/native/`；具体实现来自第三方库，未验证。

**游戏/JVM 侧**【验】：

- `ZLBridge.setupExitMethod(context)` + `initializeGameExitHook()`（`Launcher.kt:194-195`）配合 `jni/exit_hook.c`（`LOCAL_MODULE := exithook`，依赖 bytehook）挂钩 JVM `exit`，把退出码回传 ART。
- 回调入口 `ZLNativeInvoker.jvmExit(exitCode, isSignal)`（`bridge/ZLNativeInvoker.kt:129-134`）：`staticLauncher?.exit()` → `onExit(code, isSignal)` → 清空静态引用 → **`killProgress()` = `Process.killProcess(myPid())`**（`utils/LocalUtils.kt:332`）。
- `VMActivity` 的 `exitListener`：非零退出码 → `showExitMessage()` → `ErrorActivity`（`EXIT_JVM`，携带 `JvmCrash(code,isSignal,logPath)`，可分享/上传 `latest.log`、可重启）；零退出码 → **`ProcessPhoenix.triggerRebirth()`** 重启启动器（`:465-476`）。
- 任务级异常：`TaskSystem.submitTask` 的 `catch(Throwable)` 忽略 `CancellationException` 与 `InterruptedIOException`，其余交给 `task.onError`；`SplashActivity.startAllTask()` 把解包失败标为 `FAILED` 并允许重试；`GameLaunchFlow` 通过 `TaskFlowExecutor.executePhasesAsync(onError=...)` 归类账号/通用错误。

**日志**：`LoggerBridge`（native，单例写单文件）+ `utils/logging/Logger`；`Launcher.launchJavaVM()` 打印全部 JVM 参数但对 `--accessToken` 的下一个值打码为 `********************`（`:182-192`）。`AllSettings.launcherLogRetentionDays` 默认 7 天。`crashlogs/` 支持上传 mclo.gs 类服务（`AbstractAPI.kt`、`MCLogsResponse.kt`）。【验 + 推（具体上传端点未逐行核对）】

### 4.8 退出过程

```
[正常结束 / 用户强制退出]
 ZLBridge 的 exit hook（jni/exit_hook.c + bytehook）
   → ZLNativeInvoker.jvmExit(code, isSignal)  [@Keep @JvmStatic，由 native 反射调用]
        ├─ Launcher.exit()：GameLauncher → offlineServer.stop()；JvmLauncher → no-op
        ├─ Launcher.onExit(code, isSignal)
        │     ├─ code==0 → AllSettings.finishedGame +1
        │     └─ VMActivity.exitListener → stopAllService()
        │            ├─ stopService(GameService)
        │            └─ 若 TerracottaVPNService.isRunning() → stopService(...)
        │            → code!=0 : showExitMessage() → ErrorActivity（可分享日志/重启）
        │              code==0 : ProcessPhoenix.triggerRebirth()
        ├─ staticLauncher = null
        └─ killProgress() → Process.killProcess(myPid())   // :game 进程终止

[VMActivity.onDestroy] 注销 DisplayListener、stopAllService、handler.onDestroy、
                        SdlBridge.reset()、FliteTts.shutdown()
[MainActivity 启动游戏后] GameLaunchFlow 的 Phase4 回调 exitActivity() → finish()
[ErrorActivity] onRestartClick → ProcessPhoenix.triggerRebirth；onExitClick → finish()
[启动器崩溃] UncaughtExceptionHandler → showLauncherCrash → Process.killProcess
```

【验】依据：`ZLNativeInvoker.kt:129-134`、`VMActivity.kt:465-476, 721-736`、`ErrorActivity.kt:60-73, 163-166`、`GameLaunchFlow.kt:230-244`、`utils/LocalUtils.kt:332`。

**注意**：退出路径以**杀进程 / 进程重启**为主，而非 Android 常规的 `finish()` 栈回退。原因是 JVM 被 `dlopen` 到同一进程，进程内全局状态（JNI 静态状态、已加载的 .so、类）无法干净卸载。【推】这是 PojavLauncher 系启动器的通用做法，也与 `ProcessPhoenix` 依赖、`:game` 独立进程的设计相互印证。

---

## 5. 游戏 JVM 构造细节（二次开发最常改动处）

`Launcher.launchJvm()`（`Launcher.kt:131-201`）顺序【验】：

1. `ZLNativeInvoker.staticLauncher = this`（供 native 回调）
2. `ZLBridge.setLdLibraryPath(getRuntimeLibraryPath())` —— 拼接 FFmpeg 插件、渲染器插件、NativeLib 插件、JRE `lib/jli`、`lib/server`、`/system/lib64`、`/vendor/lib64`、`/system_ext/lib64`、JNA、`runtime_mod`、per-version LWJGL natives、应用 nativeLibraryDir
3. `setEnv(screenSize)` → `initEnv()` → `Os.setenv` 逐项写入
4. `dlopenJavaRuntime()`：`libjli.so`、`libjvm.so`、`libfreetype.so`、`libverify.so`、`libjava.so`、`libnet.so`、`libnio.so`、`libawt.so`、`libawt_headless.so`、`libfontmanager.so`，再递归 dlopen 运行时目录下所有 `.so`
5. `dlopenEngine()`：`libopenal.so` + 渲染器插件/渲染器库
6. `launchJavaVM()`：`getJavaArgs()` → `progressFinalUserArgs()` → 追加 `jvmArgs`（来自 `LaunchArgs.getAllArgs()`）→ 首位插入 `"$runtimeHome/bin/java"` → 日志 → `setupExitMethod`/`initializeGameExitHook`/`chdir` → `VMLauncher.launchJVM(args)`

`LaunchArgs.getAllArgs()`（`LaunchArgs.kt:68-126`）组装：`getJavaArgs()`（含 `-cp`、LWJGL classpath、Caciocavallo AWT 参数、渲染器 libname 等）+ `getMinecraftJVMArgs()` + `NativePluginManager.getJVMEnv()` + Java>8 的 `--add-opens`-类参数（`--add-exports <pkg>/<pkg>=ALL-UNNAMED`）+ **`mio.Wrapper`** + `gameManifest.mainClass` + `getMinecraftClientArgs()`（用户名/token/UUID/版本/游戏目录/assets 等模板替换）+ QuickPlay 参数 + 版本自定义游戏参数。【验 + 推（各 `get*Args()` 的完整展开未逐行穷举）】

关键 `-D` 属性（`Launcher.kt:217-264`）：`java.home`、`java.io.tmpdir`、`jna.boot.library.path`、`user.home/language/country/timezone`、`os.name=Linux`、`pojav.path.minecraft`、`pojav.path.private.account`、`org.lwjgl.vulkan.libname`、`org.lwjgl.librarypath`（指向 per-version natives）、`glfwstub.windowWidth/Height/initEgl`、`ext.net.resolvPath`、`log4j2.formatMsgNoLookups`、JNDI RMI 加固、`fml.*`、`loader.disable_forked_guis`、`jdk.lang.Process.launchMechanism=FORK`、`file.encoding=UTF-8`、`cpu.name`。用户自定义 `jvmArgs` 中若含同名 `-D` 前缀，**启动器生成的会被跳过**（`:269-276`）。【验】

`progressFinalUserArgs()`（`:324-362`）会强制清除用户参数中的 `-Xms/-Xmx/-d32/-d64/-Xint/-XX:+Use*LargePages/-Dorg.lwjgl.opengl.libname/-Dorg.lwjgl.freetype.libname/-XX:ActiveProcessorCount`，再统一注入内存、openal/freetype/spvc/allocator 与 CPU 核数参数。【验】这是二次开发时"自定义 JVM 参数不生效"类问题的高发点。

---

## 6. 模块边界、耦合点与可扩展位置

### 6.1 模块边界评估

| 边界 | 清晰度 | 说明 |
|---|---|---|
| `CardGrid` / `ColorPicker` / `Guide` / `PetLib` | **高** | 纯 UI 组件库，仅 Compose 依赖，app 通过少量入口调用；含单元测试 |
| `InputMap` | 中 | 纯常量/映射表，但被 16 个 app 文件与 `LayerController` 直接引用，键码语义是跨模块契约 |
| `LayerController` | 中偏低 | 36 个 app 文件引用；数据模型 + 可观察状态 + 序列化 + legacy 兼容集中在一起 |
| `Terracotta` | 中 | 只暴露 1 个 Java API + `.so`，但 VPN Service 在 app 模块内，原生协议未开源可见 |
| `LWJGL` Java 模块 | **低（构建期写耦合）** | 产物直接写进 app 源码 assets 目录；classpath 顺序由 `LaunchArgs.lwjglJarOrder()` 硬编码 |
| `game/launch/*` ↔ `bridge/*` ↔ `jni/*` | **低（三层强耦合）** | Kotlin 组织参数 → Java native 声明 → C 实现；任何参数/环境变量变更常需三层同改 |
| `ZalithLauncher` 内部 | 中 | 按 `game/`、`ui/`、`setting/`、`utils/`、`filemanager/` 分包清晰，但大量 `object` 全局单例与 `GlobalContext` 绕过 DI |

### 6.2 明确的可扩展位置（有现成扩展点）

1. **APK 插件体系**（`game/plugin/`）：`PluginLoader` 统一扫描，5 个 Manager —— `DriverPluginManager`、`RendererPluginManager`（旧架构）、`RendererV2PluginManager`（新架构）、`NativePluginManager`、`FFmpegPluginManager`。新增插件类型可照 `parseApkPlugin(context, ApplicationInfo)` 模式扩展；`Renderers.addRenderer(plugin)` 是渲染器注册入口。【验】
2. **渲染器**：`game/renderer/Renderers.kt` + `renderers/`（7 个）+ `renderer/config/`（5 个 `ConfigurableRenderer` 配置）；`RendererLaunchConfig.environment` 注入环境变量。【验】
3. **账号/认证**：`game/account/` 下 `microsoft/`、`offline/`、`yggdrasil/`、`auth_server/`、`wardrobe/`；`AccountType` + `AccountsManager` 是扩展点。【验】
4. **下载源与镜像**：`game/addons/mirror/BMCLAPI.kt` 的 `REPLACE_MIRROR_HOLDERS` 映射表与 `resolveMirrorPriority()`；`game/download/engine/`（Fetcher/FileDownloader/BatchDownloader）支持断点续传与并发。【验】
5. **控制布局**：`LayerController` 的 `data/` + `observable/` + `utils/JsonAdapters.kt`，`ControlManager` 负责默认布局与刷新；导入通过 `activity-alias`。【验】
6. **设置项**：`AllSettings` + `SettingsRegistry` 的 `xxxSetting()` 工厂 + `setting/unit/`；新增设置只需声明并在 `ui/screens/content/settings/layouts/` 挂 UI。【验】
7. **导航/页面**：`navigation3` 的 `NormalNavKey`/`NestedNavKey` + `ui/screens/content/` 下各 Screen；新增页面即在 NavKey 与 `entryProvider` 注册。【验】
8. **JRE**：`Jre` 枚举 + `assets/runtimes/jre-N/` + `RuntimesManager`；新增 Java 版本需同时改枚举、assets、构建期裁剪列表（`build.gradle.kts:224` 的 `jreList` 硬编码 4 项）。【验】
9. **LWJGL 版本**：需改 `settings.gradle.kts`、`LWJGL/build.gradle.kts` 的 `lwjglVersions`、`Components.kt`、`LaunchArgs.lwjglVersionDir()`、构建期 natives 清理正则——**扩展成本高**。【验 + 推】
10. **启动时任务流**：`GameLaunchFlow.buildLaunchPhases()` 用 `buildPhase { addTask(...) }` 声明式组织，插入前置校验/补丁阶段是低成本扩展点。【验】

### 6.3 主要耦合点（改动需谨慎）

- `PathManager` / `GlobalContext` / `ZLApplication.DEVICE_ARCHITECTURE` / `AllSettings` / `Renderers` / `PluginLoader` 等**进程级可变全局**；多进程下各自一份。【验】
- `BuildKeys`（编译期生成）被 `ZLApplication`、MMKV ID、UA、OAuth、CurseForge key、启动参数 `launcher_name` 等引用（全仓库至少 5 处）。【验】
- `MMKV ID = BuildKeys.LAUNCHER_IDENTIFIER` —— **改 `launcher_name` 会导致所有设置"丢失"**（新 ID 对应新文件）。【验 + 推】
- `ZLNativeInvoker.staticLauncher` 是 native→ART 回调的唯一静态桥梁，生命周期必须与 `:game` 进程一致。【验】
- `CallbackBridge`（`org/lwjgl/glfw/CallbackBridge.java`，同时存在于 app 与 LWJGL 模块）与 `ZLBridgeStates`：游戏侧 native 通过 `@JvmStatic` 反射修改 Compose 的 `StateFlow`（指针模式/形状/窗口变更）。【验】
- `AndroidManifest` 的 `configChanges`、`process`、alias `meta-data` 与代码里的字符串常量（`IMPORT_TYPE_*`、`EXTRA_IMPORT_*`、`INTENT_RUN_*`）构成隐式契约。【验】
- `assets` 与 `jniLibs` 的二进制文件与 `Components`/`Jre` 枚举、`build.gradle.kts` 裁剪逻辑、SHA-1 清单四者必须一致。【验】
- `LaunchArgs` 与 `assets/components/launcher/*.jar` 的 `mio.Wrapper` 契约；`log4j-1.7/1.12.xml` 与版本判定。【推】

---

## 7. 配置影响矩阵（二次开发必查）

| 配置 | 位置 | 影响范围 |
|---|---|---|
| `launcher_name` / `launcher_app_name` / `launcher_short_name` | `ZalithLauncher/gradle.properties` | `BuildKeys` → **MMKV ID**、UA、Manifest `android:label`、APK 文件名、JVM 参数 `launcher_name`、`net.minecraft.clientmodname` |
| `launcher_version_code/name` | 同上 | `BuildConfig.VERSION_CODE/NAME`、启动日志、`ZALITH_VERSION_CODE` 环境变量、APK 名、更新检查 |
| `url_home` / `oauth_client_id` / `curseforge_api_key` | 同上 + env + 忽略的 `.txt` | `BuildKeys.URL_HOME`、微软 OAuth、CurseForge API 与 Mod 搜索；**缺失时功能抛异常或静默不可用**（`build.gradle.kts:34-43` 的 WARN） |
| `default_store_password` / `default_key_password` | 同上 | debug 签名；**明文随仓库分发** |
| `-Darch` | Gradle 系统属性 | ABI splits、assets 裁剪（JRE/LWJGL/JNA natives）、`manifest.json` 内容、`BUILD_ARCH`、输出文件名 |
| `minSdk/targetSdk/compileSdk` | `build.gradle.kts` | **targetSdk 不可升到 35**（全屏实现失效）；minSdk 26 决定 `Environment.isExternalStorageManager` 等分支 |
| `ndkVersion` / NDK 实际版本 | 同上 + CI | native 构建结果；两者不一致 |
| `useLegacyPackaging=true` / `pickFirsts libbytehook.so` | 同上 | `.so` 是否压缩进 APK、能否被 `dlopen` 直接从 nativeLibraryDir 加载 |
| `isMinifyEnabled=true`（release） | 同上 + `proguard-rules.pro` | JNI 反射回调类（`bridge.**`、`CallbackBridge`、`VMLauncher`、`CriticalNativeTest`、`VulkanChecker`）必须 keep，否则运行期崩溃 |
| `AllSettings`（112 项） | `setting/AllSettings.kt` | 渲染器、分辨率、JRE 选择、内存、JVM 参数、控制布局、鼠标/手柄/陀螺仪、主题、日志、更新、联机、平台偏好 |
| `versionIsolation` / `skipGameIntegrityCheck` / `autoPickJavaRuntime` | 同上 | 版本目录隔离、跳过启动前完整性校验、自动选 JRE |
| `javaRuntime` 默认值 | `SplashActivity.kt:308-311` | 组件解包完成后若为空，强制设为 `Internal-8` |
| `network_security_config.xml` | `res/xml/` | **允许明文 HTTP**（配合 Manifest `usesCleartextTraffic=true`） |
| `backup_rules.xml` / `data_extraction_rules.xml` | `res/xml/` | 均为模板注释，**未实际排除任何敏感数据**，而 Manifest `allowBackup=true` |
| `provider_paths.xml` | `res/xml/` | FileProvider 暴露 `external-path path="."`（整个外部存储根） |

---

## 8. 二次开发风险清单

### 8.1 法律与分发（最高优先级）

- **GPL-3.0 + §7 附加条款**【验】`README.md:47-58`：修改版分发时须改名或改版本号、名称中**不得含 "ZalithLauncher" 或 "ZL"**、不得使用近似名称、必须在启动页或主界面明显标注"非官方修改版"、不得移除版权声明；应用名可通过 `ZalithLauncher/gradle.properties` 修改。这与纯 GPLv3 的义务不同，**是 fork 前必须评估的合规约束**。
- 组件许可证混杂：README 列出 ANGLE(BSD-3)、bytehook(MIT)、Coil、Fishnet、gl4es、Mesa、virglrenderer 等；搜索结果提到 **LWJGLX 兼容层无明确许可证**【缺】（未在本次源码中逐项核验各二进制的 LICENSE）。二次分发前需完成 SBOM/许可证审计。
- 与 Mojang/Microsoft 的关系：启动器本身不含游戏本体，但涉及 OAuth client id、Minecraft 资产下载；【推】商用分发需单独评估 Minecraft EULA 与 API 条款。

### 8.2 构建与可复现性

1. `BuildKeys` 插件来自 **JitPack**（`buildscript` classpath），网络不可达或插件版本漂移会导致构建失败；**源码中无 `BuildKeys` 类**，IDE 未生成前会大量报红。【验 + 推】
2. `manifest.json` 全部构建期生成；**直接把源码 assets 打进 APK 或绕过 Gradle 会在 `UnpackSingleTask.loadComponentFiles()` 处失败**。【验】
3. `LWJGL` 模块的 `tasks.jar` 把产物写入 app 源码树，可能污染工作区、造成重复构建与 SHA-1 清单不稳定（脚本已用 reproducible 设置缓解）。【验】
4. 构建需 **JDK 21（CI）/ Java 8 + Java 17 toolchain（LWJGL）/ Rust + cargo-ndk + 4 个 target / NDK / Android SDK 37**；缺少任一即失败。【验】
5. `ndkVersion 25.2.9519653` 与 CI 回退 NDK 27 不一致，需实测。【推】
6. release 签名密码仅通过 env/未入库文件提供；**两个 keystore 文件已入库**，debug 密码为明文。【验】
7. `libs/` 下 10 个预编译 aar/jar（约 112 MB）与 `jniLibs/*.so`、`Terracotta/*.so` **无对应源码**，无法独立重建或审计。【验】
8. 单元测试 50 个（主要是下载引擎、版本比较、工具、CardGrid/Guide），**无插桩测试、无 UI 测试**；核心启动链（JVM 参数组装、JNI bridge、多进程）无自动化回归保护。【验】

### 8.3 运行时与兼容性

- **targetSdk 锁定 34**：Android 15+ 的强制 edge-to-edge 会使沉浸全屏失效；同时 `MANAGE_EXTERNAL_STORAGE`、`specialUse` 前台服务、`allowNativeHeapPointerTagging=false`、明文流量等都可能触发应用商店/系统合规限制。【验 + 推】
- **多进程一致性**：4 个进程各自初始化 `ZLApplication`；`MMKV` 用 `MULTI_PROCESS_MODE`，但 `Room` 单例、`TaskSystem`、`PluginLoader`、`Renderers`、`AllSettings` 的 `StateFlow` 缓存不跨进程同步，依赖 `BaseAppCompatActivity.onResume` 的 `loadAllSettings(reloadAll=true)` 兜底。【验 + 推】
- **native/JNI 契约脆弱**：`@Keep` + R8 keep 规则是硬要求；`CriticalNative` 方法（`@CriticalNative`）对签名极敏感；`exithook` 依赖 bytehook 拦截 `exit`。改类名/包名/混淆规则极易导致启动即崩。【验 + 推】
- **版本前向适配**：源码中出现 `Minecraft 26.2+ 图形 API`、`NeoForge 12（MC 26.3+）` 等面向未来版本的特判（`AllSettings.kt:73`、`GameLauncher.kt:388-391`），说明代码与 MC 版本快照强绑定，升级游戏版本可能需要同步改启动器。【验 + 推】
- **资源/存储**：JRE 与 LWJGL 解包写入内部 `files/`；`MANAGE_EXTERNAL_STORAGE` 未获授权时仅记录布尔值而不阻断，可能在后续文件操作才暴露错误。【验】
- **退出即杀进程**：任何依赖优雅 `onDestroy`/`finally` 清理的逻辑都可能被 `Process.killProcess` 截断；二次开发若新增需要持久化的状态，应在写盘完成后再触发退出。【验 + 推】
- **`GlobalContext` 被 Activity 覆盖**：`AbstractAppCompatActivity.onCreate` 把全局 Context 设为 Activity，而 `ZLApplication.onCreate` 设为 Application；`ZLNativeInvoker` 又用 `GlobalContext as? Activity` 判断能否打开链接/剪贴板——【推】存在持有已销毁 Activity 或判断失效的风险。

### 8.4 安全

- `network_security_config.xml` 全局允许明文流量 + `usesCleartextTraffic=true`。【验】
- `backup_rules.xml`/`data_extraction_rules.xml` 是空模板，`allowBackup=true`、`hasFragileUserData=true`；账户凭据存于 **未加密** Room DB（`launcher_data.db`）与 MMKV。【验 + 推（未验证 MMKV 是否加密）】
- `FolderProvider` `exported=true` 且提供 `mt:createSymlink`、`mt:setPermissions` 等自定义 call；虽有 `MANAGE_DOCUMENTS` 权限门槛，仍需评估暴露面。【验】
- `FileProvider` 的 `external-path path="."` 授权范围较大。【验】
- release/debug keystore 入库、debug 密码明文。【验】
- `usesCleartextTraffic` + BMCLAPI/第三方镜像意味着下载链路可能走非官方 CDN；`TLSSecurity` 只针对 TLS 证书，不校验下载文件签名（下载完整性依赖 Mojang 的 SHA-1/size 清单与 `VERIFY_AND_REPAIR`）。【验 + 推】

---

## 9. 未验证内容与资料缺口（明确清单）

| # | 缺口 | 影响 | 建议补证方式 |
|---|---|---|---|
| 1 | 无 `.git` 历史、无 tag/commit 元数据 | 无法判断设计演进、无法定位引入某行为的提交 | 在可直连 GitHub 的环境补做 `git clone` 并 `git log`/`blame` |
| 2 | 未执行 Gradle 构建 | 构建可行性、`BuildKeys` 插件可用性、NDK/Rust 工具链、manifest 生成结果、R8 后 JNI 反射正确性均**未验证** | 按 CI 环境（JDK21 + NDK + Rust）跑一次 `assembleDebug -Darch=arm64` |
| 3 | 未在真机/模拟器运行 | 所有时序、多进程行为、权限流程、崩溃路径、退出路径为静态推断 | 插桩 logcat / 断点验证启动链与退出链 |
| 4 | JNI C/C++ 实现未逐行分析 | `jre_launcher.c`、`exit_hook.c`、`input_bridge_v3.c`、`egl_bridge.c` 的实际行为、JVM 参数如何传入 `JNI_CreateJavaVM` 未核实 | 阅读 `ZalithLauncher/src/main/jni/` 全部源码 + `Android.mk` |
| 5 | 二进制依赖未反编译 | `MioLaunchWrapper.jar`（`mio.Wrapper`）、LWJGL patches、10 个 aar、`Terracotta/*.so`、`jniLibs/*.so` 内部逻辑 | jadx / unzip + 许可证审计 |
| 6 | 第三方组件许可证未逐项核验 | 分发合规性（尤其 LWJGLX 无明确许可证的说法） | 建立 SBOM，对照 README 引用列表与实际文件 |
| 7 | `SQLCipher` 依赖是否另有 native/R8 侧使用 | 凭据加密判断 | 全局搜索 + 运行时检查 DB 文件头 |
| 8 | `Fishnet` native crash 捕获行为、`Zalith-Info`/更新接口返回结构 | 崩溃与更新链路 | 阅读依赖文档 / 抓包 |
| 9 | `BuildKeys` 插件 `string(..., true)` 第 3 参语义（是否加密/混淆） | 密钥是否真正受保护 | 查 `MovTery/BuildKeys` 仓库文档 |
| 10 | 全部 112 个设置项的下游影响面 | 配置回归风险 | 对每个 `AllSettings.*` 做调用点扫描（本报告仅抽样） |
| 11 | 网络检索到的第三方站点 `zalithlauncher.net/.com` 内容 | 与官方无关，README 明确否认 | 不采信；本报告未引用其内容作为源码依据 |

---

## 10. 附录：关键文件索引（按调研主题）

| 主题 | 文件（相对 `ZalithLauncher2/`） |
|---|---|
| 工程/构建 | `settings.gradle.kts`、`build.gradle.kts`、`ZalithLauncher/build.gradle.kts`、`ZalithLauncher/gradle.properties`、`gradle/libs.versions.toml`、`gradle/wrapper/gradle-wrapper.properties`、`PetLib/build.gradle.kts`、`LWJGL/*/build.gradle.kts`、`.github/workflows/{build,push_ci,release_ci}.yml` |
| 清单/权限 | `ZalithLauncher/src/main/AndroidManifest.xml`、`res/xml/{network_security_config,backup_rules,data_extraction_rules,provider_paths}.xml`、`ZalithLauncher/proguard-rules.pro` |
| Application/初始化 | `.../ZLApplication.kt`、`Internal.kt`、`SplashException.java`、`context/Contexts.kt`、`path/{PathManager,UrlManager,LibPath}.kt`、`setting/{SettingsInitializer,AllSettings,SettingsRegistry,_MMKV}.kt` |
| 启动页/资源安装 | `ui/activities/SplashActivity.kt`、`ui/screens/splash/*`、`components/*`、`components/jre/*` |
| 主界面/生命周期 | `ui/activities/MainActivity.kt`、`ui/base/{AbstractAppCompatActivity,BaseAppCompatActivity,FullScreenAppCompatActivity}.kt`、`ui/screens/_Navigation.kt`、`viewmodel/*.kt` |
| 启动流程 | `viewmodel/LaunchGameViewModel.kt`、`game/launch/{GameLaunchFlow,GameLauncher,Launcher,LaunchArgs,JvmLauncher,GameService,JvmLaunchInfo,MCOptions}.kt`、`game/launch/handler/*` |
| 游戏运行时 | `ui/activities/VMActivity.kt`、`ui/screens/game/*`、`game/input/*`、`game/renderer/*`、`game/plugin/*`、`game/sdl/*` |
| JNI 桥 | `bridge/{ZLBridge.java,ZLNativeInvoker.kt,ZLBridgeStates.kt,LoggerBridge.java,NativeLibraryLoader.java,FliteTts.kt}`、`org/lwjgl/glfw/CallbackBridge.java`、`com/oracle/dalvik/VMLauncher.java`、`org/libsdl/app/*`、`src/main/jni/Android.mk` + `*.c` |
| 异常/退出 | `ui/activities/{ErrorActivity,FatalErrorActivity}.kt`、`utils/LocalUtils.kt#killProgress`、`utils/StorageUtils.kt`、`crashlogs/*`、`utils/logging/*` |
| 多进程/系统交互 | `keepalive/*`、`notification/*`、`terracotta/TerracottaVPNService.java`、`provider/FolderProvider.java`、`filemanager/events/*` |
| 账户/数据 | `database/AppDatabase.kt`、`game/account/*`、`game/path/*`、`game/multirt/*`、`game/version/installed/*` |
| 网络/下载 | `utils/network/*`、`game/download/engine/*`、`game/version/download/*`、`game/addons/mirror/*`、`upgrade/*` |
| 合规 | `LICENSE`、`README.md:47-58` |

---

*报告完 · 所有【验】结论均可在上述本地源码路径复核；【推】与【缺】项请在补证后再作为开发决策依据。*
