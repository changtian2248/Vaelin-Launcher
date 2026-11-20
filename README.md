# Vaelin Launcher

Android 平台 Minecraft Java 版启动器（包名 `com.vaelin.launcher`）。

- **UI**：全 Jetpack Compose
- **ABI**：首版仅 `arm64-v8a`
- **许可**：GPL-3.0（启动基底复用 [ZalithLauncher2](https://github.com/ZalithLauncher/ZalithLauncher2)，其 native 层源自 PojavLauncher）

## 当前状态

R1 启动底座已通关，真机（小米 10 Pro / Android 13）验证：

- 原版 MC 1.20.1 可启动、进入主菜单并进入世界
- 触控可用（ZL2 指针模式语义移植）
- 渲染器可在实例详情页切换；已内置 GL4ES / Krypton / Zink / VirGL / Freedreno / Panfrost / MobileGlues 等 10 个
- Forge 安装链已实现（`:jvm` 进程跑 installer），真机成功路径验证待完成

下载系统（与启动核心并行开发）：

- 可插拔内容源框架 + 地区闸（Modrinth 直连；CurseForge 经 MCIM 镜像免 key 接入）
- 下载页（横屏双栏 / 竖屏单栏）
- 整合包导入（`.mrpack` / CurseForge zip）
- 已装内容管理与游戏目录迁移

测试基线：`52 suites / 376 tests` 全绿（`:startup-core:testDebugUnitTest`）。

## 仓库结构

```
01-文档/        规格书、架构、分轮计划、复用清单
03-项目工程/     Gradle 工程（app / startup-core / lab-api）
05-审查记录/    双审记录与深度调研
```

以下内容**不入库**（见 `.gitignore`）：

- `02-参考源码/` —— 9 个参考项目源码副本（约 341MB，各自许可见其 README）
- `04-构建产物/` —— APK 与验证产物
- `.scratch/` —— 本地工作区（交接文档、截图、日志）
- 各模块 `build/`、`.gradle/` 等构建缓存

`app/src/main/assets/`（JRE 运行时包、LWJGL 组件）与 `startup-core/src/main/jniLibs/`（渲染器/音频预置 `.so`）**已入库**，克隆后可直接构建。

## 构建

环境：JDK 21、Android SDK（compileSdk 37、NDK 27.3+）、Gradle 9.8.2。

```bash
cd 03-项目工程
ANDROID_HOME=/path/to/Android/Sdk gradle :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

单元测试：

```bash
gradle :startup-core:testDebugUnitTest
```

## 第三方组件与许可

| 组件 | 许可 | 用途 |
|---|---|---|
| ZalithLauncher2 / PojavLauncher | GPL-3.0 / LGPL-3.0 | 启动基底、JNI 桥、渲染器协议 |
| OpenJDK（jre-17 / jre-21 运行时包） | GPL-2.0 + Classpath Exception | 游戏 JVM |
| MobileGlues | LGPL-2.1 | GLES→桌面 GL 转换层（`assets/licenses/` 内附许可全文） |
| LWJGL / GL4ES / Mesa 等 | 见各自许可 | 原生库 |

本项目整体以 GPL-3.0 发布，完整许可见 [LICENSE](LICENSE)。
