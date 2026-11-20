# Vaelin Launcher Round3 独立审查：复用边界与分轮计划

- 作者：vice-reviewer，日期：2026-11-05
- 性质：只读审查。对象：`01-文档/04-分轮计划与任务分解.md`（166 行）、`01-文档/spec/Vaelin-全量规格书.md` v1.1、`02-参考源码/PojavLauncher` 源码抽查。
- 证据基线：PojavLauncher LICENSE 首行实测 = **LGPL-3.0**（lead 说法属实，我此前 round2 采信 research 的 GPL-3.0 是错的，以本次实测为准）；EasyTier LGPL-3.0、FCL/ZL2 GPL-3.0 也与 lead 核实一致。repo 内 `.so` 文件数实测 = 0。`JREUtils.java:463-486` 与 lead 引用逐字一致。规格书 v1.1 三处修订（第 5 行分级复用 / 第 6 条工程从零新建+启动链复用 / 第 9 条渲染器包兼容，第 7 条未动）落盘属实。

---

## Q1 复用边界：方向对，但有一处确定错划 + 三处会让边界失效的暗礁

04 §3 的判定原则（"拉起 JVM 并渲染出画面之前复用；用户看到并操作的一切自写"）是对的。但按**类别**划边界在这份代码库上会失效，因为它有几个"类别混杂"文件：

### 错划 1（确定）：MainActivity 不能整体划入"不复用"

04 §3 把 MainActivity/LauncherActivity 归入"界面部分，Compose 重写"。实测 MainActivity 藏着**启动编排逻辑**：

- `MainActivity.java:352-360`：LOCAL_RENDERER 决策链——读 PREF_RENDERER → `checkRendererCompatible`（Tools.java:1392，按设备 GL 能力过滤）→ 不兼容时自动替换为首个兼容渲染器并 `releaseRenderersCache()`。这是渲染器兼容三约定（04 §2）的**调用方**，不是 UI。
- `MainActivity.java:365`：`JREUtils.redirectAndPrintJRELog()`——JRE 日志重定向入口，T1.12 崩溃日志基线的前置。
- `minecraftGLView` 的创建与生命周期编排（82/258 行）。

这约 40 行编排逻辑必须有人接手，04 没说去哪。若漏掉，T1.10（拉起 JVM 到主菜单）串不起来。**修正：MainActivity 拆两半——View/菜单编排自写，渲染器决策 + 日志重定向 + GL Surface 生命周期编排划入 startup-core 或独立的 `GameLaunchOrchestrator`。** 应写进 T1.1 的判定说明，避免 T1.1 执行者按类别整体跳过。

### 暗礁 2：MinecraftGLSurface 是复用清单里唯一强绑 UI 框架的文件

`MinecraftGLSurface.java` 首行 `import static net.kdt.pojavlaunch.MainActivity.touchCharInput`——SurfaceView/TextureView 子类、持有 Activity、静态反向依赖 MainActivity。划为"复用"没错（它就是渲染承载），但 T1.8 的"包成 Compose AndroidView"有一个没写出的前置改造：**切掉 touchCharInput 静态依赖**（改为回调接口或直接走 CallbackBridge）。这行不改，编译都过不了。T1.8 交付物里应补这一条。

### 暗礁 3：Tools.java（1264 行）是杂物间，按文件划必然错

04 §3 写"Tools.java 的路径/解压/下载部分"复用——但 Tools 同时含：

- `LOCAL_RENDERER`/`NATIVE_LIB_DIR` 全局静态（109/113 行），被 JREUtils、MainActivity 两头引用；
- 渲染器兼容判定三件套 `getCompatibleRenderers`/`checkRendererCompatible`/`releaseRenderersCache`（1364-1397 行）——启动逻辑不是下载逻辑；
- JVM 参数装配（335/453 行 `-Djava.library.path`、natives_directory）。

**这个文件必须按"节"拆，不能按"文件"划**——T1.1 的逐文件判定对 Tools.java 会失效，需要在其交付物里显式加一条："Tools.java 按 109-113/335-455/1364-1397 与下载解压区四块分别标注"。同类嫌疑：LauncherActivity（未抽查，T1.1 一并拆）。

### 暗礁 4：JREUtils 依赖 FFmpegPlugin，移植时必须动刀

`JREUtils.java:158-159`（ldLibraryPath 拼 FFmpeg 插件路径）、`:211-212`（`POJAV_FFMPEG_PATH` 环境变量）。用户已拍板第 9 条兼容对象是渲染器包**不是 FFmpeg 插件**，所以 Vaelin 不移植 FFmpegPlugin——但 JREUtils 这两处引用要移除或留 no-op，否则 T1.4 编译不过。这是 T1.4 的隐藏工作量，任务描述里没写。

### 补一句 customcontrols 的切法

04 写"映射与事件部分复用"方向对，具体建议：`ControlData/ControlLayout/LayoutConverter/LayoutSanitizer`（数据模型与布局文件格式转换）复用——布局格式兼容让老用户可迁移，是真资产；`buttons/`、`gamepad/` 里 extends View 的部分自写。但 `gamepad/` 的 `Gamepad.java`(19KB)/`GamepadDataProvider` 是输入设备抽象不是 UI，建议复用。这个粒度留给 T1.1。

---

## Q2 R1 任务分解：两个物质性遗漏 + 一个估时错误

出口判据四条本身合理。问题在物质前提没闭环：

### 遗漏 1（最重）：T1.3 的风险比 lead 标的"未验证"更大——它是分发渠道设计问题

lead 标"未验证：JRE 包 lib/ 里有没有 libgl4es_114.so 与 libOSMesa.so"。要点是：**如果不在，Vaelin 拿不到它们就只能自己编**——OSMesa+zink 是 Mesa 子集构建，工作量是轮级不是任务级；gl4es 也要自己维护 fork。所以 T1.3 的出口判据应升级为："**确认三个渲染器 so（libgl4es_114/libOSMesa/libltw）的确切获取渠道并各落盘一份到 build-check/**"——渠道可能是 buildjre 分支 artifact、LTW aar、或需要自建 CI。这是 04 §2 三条约定的物质前提，比"MultiRTUtils 能解压"重要。另外 lead 只标了两个 so 的疑问，`libltw.so` 同样未验证（它在 LTW aar 里，aar 引入方式在 Vaelin 新工程对应"jniLibs 预置"还是"运行时从 aar 解压"没有设计——Pojav 是 `implementation fileTree(dir: 'libs', include: ['*.aar'])` 编译期打入，Vaelin 若 runtime 解压则 NATIVE_LIB_DIR 的含义要跟着变，Tools.java:185 的 nativeLibraryDir 装配逻辑受影响）。

### 遗漏 2：R1 没有任何 LTW 相关任务

04 §2 表格写了 LTW 的来源与 CI wget 方式，但 R1 十四个任务里没有一条碰 LTW；T1.11 只验证"替换 libgl4es_114.so"，没验证 `opengles3_ltw` 路线。LTW 是三个渲染器里唯一带 EGL 注入约定的（见 Q3），建议 T1.11 扩为"三渲染器路线各启动一次"或加 T1.15。

### 遗漏 3：出口判据缺机型维度

"能进 MC 主菜单"在一台真机上验收，但 `JREUtils.java:194` 的 `POJAV_EMUI_ITERATOR_MITIGATE`（华为机型专项规避）说明 Pojav 自己都按机型分支适配——单机型通过不代表量产可用。建议 R1 出口补一句："至少一台 Snapdragon（Adreno）+ 一台 Mali/麒麟系机型各进一次主菜单"，达不到就在验收报告里写明机型局限。

### 估时错误：T1.4 的 2h 不够

JREUtils.java 475 行，实测 import 依赖 15+ 个 Pojav 类（Architecture、Tools、LauncherPreferences、ExtraCore、FFmpegPlugin、MultiRTUtils、lifecycle 包等），任务要求"去掉 06-构建工具/LauncherPreferences 耦合替换为配置提供者 + 单测覆盖渲染器映射与 fallback"。这是 R1 最重的移植任务，估 2h 会烂尾，建议 4–6h 或拆成"依赖解耦"与"映射逻辑+单测"两个任务。

---

## Q3 三条约定不够——第三方渲染器的隐式兼容面至少五层

lead 问的方向是对的：`POJAV_VSYNC_IN_ZINK`、`MESA_LOADER_DRIVER_OVERRIDE=zink`、`POJAVEXEC_EGL=libltw.so` **确实是兼容面**。我的结论：04 §2 的三条约定（文件名/查找顺序/回退）只是"dlopen 层"契约，是必要不充分。实测 JREUtils 后，完整兼容面五层：

1. **环境变量装配面（第 176-244 行区块）**：宿主按渲染器类型装配 env——gl4es 专用调优五连（`LIBGL_MIPMAP/NOERROR/NOINTOVLHACK/NORMALIZE/VGPU_DUMP`，178-190 行）；zink 专用（`MESA_LOADER_DRIVER_OVERRIDE=zink` + `MESA_GLSL_CACHE_DIR`，202-206 行）；`POJAV_RENDERER`（216 行）；`LIBGL_ES` 按渲染器名推导（227-239 行）。第三方渲染器 .so 内部读这些变量就是兼容面——只投放 .so 不装配 env，结果是"能 dlopen 但画面错/性能差"。
2. **EGL 注入面**：`POJAVEXEC_EGL=libltw.so`（222 行）+ `-Dglfwstub.initEgl=false`（370 行）——ltw 路线由 libltw.so 提供 EGL，GLFW stub 不自建。自带 EGL 的第三方渲染器（ANGLE 系）必须走这条注入约定，否则与 pojavexec 的 EGL 符号冲突。
3. **渲染器名→行为映射（兼容的真正含义）**：`LOCAL_RENDERER` 不是自由命名——五个 case（opengles2/opengles2_5/opengles3/vulkan_zink/opengles3_ltw）各绑定 env 装配分支与 LIBGL_ES 推导，**未知名字掉进 default 被静默改回 opengles2**（473-476 行）。所以第 9 条"兼容渲染器包"的真实含义是：第三方包要么复用这五个名字之一的行为语义，要么 Vaelin 扩展映射表自成事实标准（上游 Pojav 不认，生态分裂）。04 §2 "按三个名字投放就能用"低估了，应为"按五个名字之一声明行为 + 对应 env 装配分支存在"。
4. **native 符号面**：三级 dlopen 查找（裸名→findInLdLibPath→NATIVE_LIB_DIR）正是为了让渲染器 so 解析到 pojavexec 导出的符号（EGL 桥、input bridge）。渲染器包依赖 pojavexec 的哪些导出符号，要在 T1.2 编译时用 `llvm-readobj --dyn-symbols` 摸清并存档——这是第三方作者写新渲染器时唯一能依赖的 ABI 文档。
5. **偏好开关面（宿主→渲染器单向控制）**：`PREF_VSYNC_IN_ZINK→POJAV_VSYNC_IN_ZINK`、`PREF_BIG_CORE_AFFINITY→POJAV_BIG_CORE_AFFINITY`（222 行）、`FORCE_VSYNC`（200 行）、`POJAV_LOAD_TURNIP`（244 行，Adreno 驱动注入）。Vaelin 设置页要暴露同等开关，"兼容"才对用户成立。

**落地建议**：把"渲染器兼容面清单"（上述五层逐项）作为 T1.11 的交付物之一，替代目前一句话的三条约定。工作量不大（多半是移植 JREUtils 时顺手记录），价值是第 9 条从口号变成可验收规格。

---

## Q4 分轮结构：整体成立，两个跨轮反悔点要在 R1 埋防御

R1→R2 硬门槛、R3 三工具并行、R4-R6 穿插——结构合理，不推翻。但有两处 R2/R3 会要求 R1 决策返工的风险，现在埋防御成本最低：

1. **模块系统对初始化顺序的反向要求**：R2 的模块加载、FeatureRegistry、安全模式短路都发生在 Application/首屏前；而 R1 移植的 Pojav 代码全走全局静态初始化（`Tools.NATIVE_LIB_DIR` 在 185 行由 ctx 赋值、`LOCAL_RENDERER` 静态可变）。若 R1 把这些写成 Application.onCreate 里的直接顺序调用，R2 插入模块加载点就要改初始化顺序——正是 lead 担心的那类推翻。**防御：T1.0 就定义 Vaelin 的 InitPipeline（有序步骤接口），所有 Pojav 移植初始化注册为 pipeline step；R2 模块加载作为另一个 step 插入，零重构。** 成本约半天，不做的期望损失远大于此。
2. **游戏 Surface 的承载模式决定 R2/R3 的 UI 空间**：Pojav 是单 Activity 模式（MainActivity 承载 GLSurface + 全部页面）。若 Vaelin 沿用"游戏画面内嵌主 Activity"，R2 模块自有页面在游戏运行期的呈现方式受限，R3 游戏内控件（规格书条目 30）也会撞上同一堵墙。建议 R1 验收（T1.14）时**显式拍板：游戏运行放独立 Activity/Task**，Compose 主界面与游戏进程视图解耦。这是一个一句话决策，但 R1 不拍、R2 要改架构。
3. 反过来说，R3-R5 推翻 R1 的风险低：世界工具、内置功能、联机层都是启动链之外的纯新增。R1→R2 门槛保留正确。

### 附：对 04 §7 的两条修正意见

- §7-1 许可证："自有代码 Apache-2.0 + startup-core LGPL-3.0" **成立且自洽**（LGPL 动态链接不传染自有代码，round2 的 GPL 顾虑就此解除）。但注意 `jre_lwjgl3glfw` 目录**没有独立 LICENSE 文件**（实测），它继承仓库级 LGPL-3.0——复用清单里要带一行声明来源，避免未来审计断链。
- §7-2 minSdk："倾向跟 Pojav 一致"欠妥，应为 **max(26, Pojav minSdk)**——26 是本方既定决策（probe 已按 26 跑通），取 lower 会推翻已有验证。

---

## 结论速览

| 问题 | 结论 |
|---|---|
| Q1 | 原则对；MainActivity 确定错划（藏渲染器决策链+日志重定向）；MinecraftGLSurface 需切 touchCharInput 静态依赖；Tools.java 必须按节拆；JREUtils 的 FFmpegPlugin 引用要动刀 |
| Q2 | T1.3 升级为"三渲染器 so 获取渠道确认+落盘"；补 LTW 任务；出口判据补机型维度；T1.4 估 4-6h |
| Q3 | 三条约定不充分；完整兼容面五层（env 装配 / EGL 注入 / 名字映射语义 / native 符号 ABI / 偏好开关），建议成为 T1.11 交付物 |
| Q4 | 结构保留；R1 先埋 InitPipeline 与"游戏独立 Activity"两个防御，minSdk 取 max(26, Pojav) |
