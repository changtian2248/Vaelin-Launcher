# Vaelin Launcher 全量规格书（目标模式唯一输入）

> 本文档自包含：项目目标 + 全部设计规格 + 协作约束，一份即完整。
> 目标部分只讲"要达到什么"；规格部分给已定设计。未覆盖的细节由主 Agent 现场调查后自行规划，但不得偏离本文任何条目。
> 本文提及的参考项目源码（Pojav 系启动器、FCL、ZL2、Modrinth App、Axolotl、Terracotta、EasyTier、cubiomes、skinview3d、schematic4j、Nucleation 等）按以下分级使用：**以 ZalithLauncher2（GPL-3.0）为启动基底的复用来源**——ZL2 自身即以 PojavLauncher 的 native 层为启动核心，并额外提供多进程架构、`:jvm` 独立进程与插件化渲染器协议（见第 6、9 条 v1.2 修订）；其余项目源码仅作参考。不以他人 APK 为基底。
>
> **v1.1 修订记录（2026-11-05，用户拍板）**：① 第 5 行由"不 Fork、仅参考"改为分级复用，Pojav 启动链纳入直接复用范围；② 第 6 条由"从零构建"改为"工程从零新建 + 启动链复用 Pojav"；③ 第 9 条补充"渲染器包兼容"要求。第 7 条"UI 全面 Compose"**未改动**——用户明确 UI 层自写。修订原因见 `01-文档/04-分轮计划与任务分解.md` §1。
>
> **v1.2 修订记录（2026-11-05，用户二次拍板）**：① 启动基底来源由 PojavLauncher 改为 **ZalithLauncher2（GPL-3.0）**——ZL2 的 native 层即 Pojav 启动核心（`jre_launcher.c` 等原班文件），另含 `:jvm` 独立进程与 `JvmService`；② 渲染器协议由"三种硬编码 `.so`"升级为 **ZL2 的 `RendererInterface` 插件化协议 + Pojav 三件兼容**；③ **Vaelin 整体许可定为 GPL-3.0**（用户原话："我对协议这一个东西不是很在意，总之属于开源协议就行"）。第 7 条"UI 全面 Compose"与"UI 层自写"两条**仍然未改动**。
> **v1.3 修订记录（2026-11-05，用户第三次拍板）**：新增第 60 条 **账号体系**——用户明确「那不然呢肯定做呀。离线账号，正版账号，第三方服务器登录我全都要」，账号从"未定义"升为必需项，第 33 条"正版上传"因此保留。**编号续接 59 而非插入 §5 中段**，以免打乱既有 59 条的编号引用；阅读顺序上它属于 §5 官方功能。同步影响：`01-文档/04` 由 7 轮调整为 8 轮，新增 R2「账号体系」，原 R2–R7 顺延为 R3–R8。

---

# 第一部分　项目目标总纲

## 1. 产品定位

1. **产品**：Android 平台 Minecraft Java 版启动器，项目名 Vaelin，包名 `com.vaelin.launcher`。
2. **目标用户**：国内为主的手机 MC Java 玩家，覆盖从新手到深度玩家（生电、红石、建筑、整合包）的全谱系。
3. **核心价值主张**：不是"又一个启动器"，而是**"玩起来之后"体验最强的启动器**——种子地图、投影、存档编辑、模块生态、AI 辅助是差异化。
4. **产品气质**：功能深但不臃肿（重活交给模块按需加载）；界面精致（Axolotl/Modrinth 设计语言）；重度用户能改造一切，新手开箱即用。
5. **AI 启动器定位**：AI 能力（AI 模块工坊、AI 答疑等）是产品标签；AI 供应商一律 BYOK（用户自带 key），不经作者服务器。

## 2. 基座与合规（已锁决策区）

6. **工程从零新建 + 启动基底复用 ZalithLauncher2（v1.2 修订，已锁）**：APK 工程全新创建，自有包名 `com.vaelin.launcher`、自有签名、自有 Compose UI 层；**启动基底——JRE 分发与首次解压、`dlopen` 加载 JVM 与 AWT、环境变量装配、输入注入、GL Surface、多进程架构（`:jvm` / `:game` / `:filemanager`）、`JvmService` 独立进程与 `JVMSocketServer` 通信——直接复用 ZalithLauncher2（GPL-3.0）**。ZL2 的启动核心即 PojavLauncher 的 native 层（`jre_launcher.c` / `java_exec_hooks.c` / `input_bridge_v3.c` / `awt_bridge.c` / `egl_bridge.c` / `sdl_hook.c` / `exit_hook.c` 等），因此"复用 ZL2"与"JVM 启动链照抄 Pojav"是同一件事，不构成冲突。**Vaelin 整体许可定为 GPL-3.0**（用户明确接受"只要是开源协议即可"），模块模板与 lab-api 同许可证；被复用文件保留原版权声明。复用边界止于"把 JVM 拉起来并渲染出画面"——UI 层、模块系统、世界工具、内置功能全部自写。
7. **UI 全面 Compose**：全部界面以 Compose 构建。
8. **下载与联机完整保留 + 地区闸（已锁）**：内置完整的文件下载能力（游戏版本、整合包、资源）与陶瓦联机；地区识别判定为中国大陆时这三项不开放（入口隐藏 + 动作拒绝），**非大陆地区完整可用**。判定信号为系统地区 + SIM 国家码，判定失败默认按大陆处理；行业出现成熟合规实现时优先对齐。
9. **首版仅 arm64-v8a**；ARMv7 / x86_64 视后续需求再议。**渲染器协议与兼容（v1.2 修订，已锁）**：采用 ZL2 的插件化渲染器协议——`RendererInterface` 15 个成员（`getRendererId` / `getUniqueIdentifier` / `mesaBased` / `needsZinkDriverOverride` / `supportsSfpewByDefault` / `getRendererName` / `getRendererSummary` / `getMinMCVersion` / `getMaxMCVersion` / `getRendererEnv` / `getDlopenLibrary` / `getRendererLibrary` / `getRendererEGL` 及两个版本显示默认实现）、`Renderers` 注册表（内置 7 个：NGGL4ES / GL4ES / KopperZink / MobileGlues / VirGL / Freedreno / Panfrost，冲突检测 + 失败回退首个）、`RendererPlugin`（外部插件包实现同一接口，携带 packageName / glName / eglName / env / dlopen 列表）。**同时必须保留 Pojav 生态兼容**：Pojav 的渲染器 id 与库名以**额外的兼容实现并存**方式接入，不覆盖 ZL2 的同 id 实现——`opengles2`（与 ZL2 的 GL4ESRenderer 同为 `libgl4es_114.so`，可共用）、`opengles2_5` → `libgl4es_114.so`、`vulkan_zink` → `libOSMesa.so`、`opengles3_ltw` → `libltw.so`（EGL 走 `POJAVEXEC_EGL=libltw.so`）。**注意 ZL2 自身占用 `opengles3` 但指向 `libng_gl4es.so`，与 Pojav 的 `opengles3`（`libgl4es_114.so`）同名不同库，两者不合并。** 加载时从 JRE 的 `lib` 目录或应用 native 库目录 `dlopen`，失败自动回退 gl4es；LTW 以 `ltw-release.aar` 形式引入。允许用户替换这些 `.so` 或新增渲染器插件包而不重新编译启动器。**插件体系范围（v1.4 补记，待裁决）**：ZL2 的 APK 插件体系实际有 **5 类**，不止渲染器——`DriverPluginManager`（驱动包）、`RendererPluginManager`（旧渲染器协议）、`RendererV2PluginManager`（新渲染器协议）、`NativePluginManager`（native 库包）、`FFmpegPluginManager`（FFmpeg 可执行包）；统一由 `PluginLoader.loadAllPlugins()` 以 `PackageManager.queryIntentActivities(ACTION_MAIN, GET_META_DATA|GET_SHARED_LIBRARY_FILES)` 扫描已安装 APK，再交给各 Manager 解析，插件 `.so` 取自其 `ApplicationInfo.nativeLibraryDir`。v1.2 只覆盖了渲染器一类；v1.4 记录完整事实，**是否把 5 类全部纳入 Vaelin 待用户裁决**。
10. **署名与签名**：启动页、关于页固定标注「非官方修改版」+「本启动器由 AI 完成」；自有签名密钥。
11. **官方渠道红线**：不发布、不暗示、不教程化任何绕过地区限制的方法；官方模块、模块索引、官方页面不接充值、赞助通道、交易等任何资金功能。

## 3. 模块系统（项目心脏）

12. **模块是完整能力载体**：模块必须能够承载新页面、新功能、UI 重构、游戏内功能；能力上限由宿主开放的扩展点决定；性能上模块页面与内置功能无差别。
13. **宿主扩展点架构（已锁）**：模块通过声明式扩展点接入，**模块永不直接修改宿主代码**；宿主导航对模块容器化。
14. **懒加载（已锁）**：启动器启动阶段**默认不加载任何模块**；仅当模块在自身声明中明确标注"自启动加载"时才随启动加载；其余模块在用户打开其对应界面/功能时才加载运行。官方功能模块同样遵守。
15. **崩溃隔离**：模块崩溃不拖垮启动器（L1–L5：调用包裹 → 模块边界 → 模块级禁用 → 连续崩溃自动安全模式）。
16. **模块生命周期**：onLoad / onEnable / onDisable / onUnload 完整状态机，状态持久化。
17. **模块安装**：官方索引（GitHub Release zip + 热更索引 JSON）、本地导入两条路径；SHA256 校验；apiVersion 不匹配拒绝安装；模块自带 Compose 依赖时安装期拒绝。
18. **模块依赖机制**：module.json 声明依赖（id + 版本范围），拓扑加载、拒绝循环依赖；模块间能力获取走服务注册表（接口寻址）。
19. **前置模块**：通用引擎/库以无 UI 前置模块存在，按需自动安装；被依赖期间不可卸载。判据：宿主不用 + 体积大或有多个依赖者。
20. **覆盖机制（FeatureRegistry）**：内置功能注册表驱动，模块可声明对任意内置条目的 disable / replace / hide（合规开关除外）。
21. **启动零扫描**：启动路径不做任何用户内容目录的文件扫描；按需单点读取；索引增量维护；第三方 mod 数据目录默认不可见。
22. **模块数量提示**：已安装模块超过 10 个时，安装第 11 个立即弹风险提示（启动变慢、页面卡顿等）；仅提示不拦截。
23. **开发者闭环**：官方模块模板仓库（UI 型与 library 型、构建 zip + SHA256 + 发布）；lab-api 随宿主版本 tag 发布；CI 强制模块产物不含 `androidx/compose/` 类、apiVersion 与宿主一致。

## 4. 安全与责任边界

24. **第三方模块导入警示**：非官方签名模块导入时弹风险告知（明示全权限与数据风险 + 免责条款）；「直接导入」需二次确认 + 5 秒锁定，每次必经、不可跳过；官方签名模块静默安装。
25. **合规开关不可覆盖（唯一例外）**：地区识别与合规功能开关位于核心层，不进 FeatureRegistry，任何模块不可禁用或替换。
26. **模块调用留痕**：模块 API 调用记录日志，开发者选项可查看。
27. **作者责任边界**：第三方模块造成的用户损失由用户自担；作者侧守护为合规开关不外放 + 官方渠道零涉钱 + 零教程化绕过。

## 5. 官方功能模块与内置功能

28. **渐变文字生成器**：MC 渐变颜色代码生成，与参考实现逐字一致。
29. **配方生成器**：可视化编辑 + 按 MC 版本输出正确配方 schema + 导出数据包 zip，真实存档可识别。
30. **游戏内控件系统**：触控控件布局的编辑、使用、导入导出；兼容主流布局格式。
31. **游戏内悬浮系统**：悬浮球（拖动/贴边）→ 展开面板 → 模块游戏内页签挂载；指针捕获正确；多渲染器兼容；低端机降频联动。
32. **联机模块**：陶瓦/EasyTier P2P 联机；可参考开源实现；地区闸自动禁用；VPN 权限链路 + 前台服务合规实现；关于页标注 AGPL/LGPL。
33. **皮肤编辑器**：64×64 像素编辑（画笔/橡皮/吸管/填充 + 撤销栈上限）+ 3D 实时预览 + 旧皮肤自动转换 + 正版上传；3D 预览运行时为前置模块。
34. **种子地图（核心卖点）**：cubiomes 引擎（前置模块）+ Compose 瓦片地图（LRU + 渐进渲染）+ MC 版本结构映射表（热更 JSON）+ level.dat 读种子 + 游戏内只读查看器；性能预算：瓦片缓存 32、并发 2、低端机禁用重负载；已知种子结构/出生点与参考实现一致。
35. **投影工坊**：.litematic / .schem / .schematic 三格式解析与渲染；大投影流式加载不 OOM；旋转/镜像/图层；格式互转导出；游戏内只读查看器。
36. **存档编辑器**：对任意存档删除任意区块、跨存档复制粘贴任意区块；操作前自动备份被触碰的 region 文件；粘贴仅同数据版本；坐标重映射完整；1.18 前后两代 NBT 布局兼容；字节级搬运不深解析方块数据。
37. **玻璃 UI（官方旗舰皮肤）**：一键换全套视觉（图标 + 全部页面）为 glassmorphism 风格；实时模糊按设备分级降级；深浅/OLED 三态可用；自适应图标分层 + 单色 themed icon；作为主题模块参考实现开源。
38. **资源源接入**：资源源抽象可插拔（Modrinth / CurseForge / **XyeBBS** 等）；XyeBBS 走官方开发者 API（鉴权 + 频率限制）；地区闸建在源框架层，新源自动继承合规。
39. **AI 模块工坊**：对话式生成完整模块（自然语言 → 代码工程 → 云端构建 → 安装）全链路可用；AI 生成模块强制 license/source 字段、标注 AI 生成、按第三方模块流程导入；编译失败日志回喂 AI 自动修复（上限 3 次）；成本透明提示；BYOK；离线优雅降级；承接模组翻译等长尾需求。
40. **Shizuku 权限桥**：封装 Shizuku 能力为前置模块；依赖模块逐项申请能力，用户逐项授权、可随时收回。
60. **账号体系（v1.3 新增，已锁）**：三类账号全部支持。① **离线账号**——本地用户名，UUID 由 `UUID.nameUUIDFromBytes("OfflinePlayer:<name>")` 派生，不发任何网络请求；② **微软正版账号**——OAuth 2.0 device code 流程（`POST https://login.live.com/oauth20_token.srf`）→ Xbox Live 认证（`user.auth.xboxlive.com`）→ XSTS 授权（`POST https://xsts.auth.xboxlive.com/xsts/authorize`）→ Minecraft Services 换 access token，并以 `GET https://api.minecraftservices.com/entitlements/license` 校验正版持有，未持有须给出明确提示而非静默失败；③ **第三方外置登录**——Yggdrasil API（authenticate / refresh / validate / invalidate / sessionserver），通过 `authlib-injector.jar` 以 `-javaagent:<jar绝对路径>=<服务器地址>` 注入 JVM 并附 `-Dauthlibinjector.side=client`，服务器地址优先取响应头 `x-authlib-injector-api-location`。启动参数统一五件：`auth_session`、`auth_access_token`、`auth_player_name`、`auth_uuid`（去横线）、`auth_xuid`。账号持久化须含 refreshToken 与过期时间，启动前自动刷新，过期或无正版持有都给可操作的提示；凭证落盘加密，不写明文日志。正版与第三方账号的皮肤/披风从各自 sessionserver 拉取并本地缓存，离线账号用本地皮肤文件。第 33 条"正版上传"建立在本条之上。

## 6. 开发者选项（Beta 独占）

41. **门禁**：仅 Beta/Debug 构建存在，Release 编译期零暴露；设置页连点版本号 7 次解锁。
42. **13 个分区全部落地**：构建信息 / 设备信息（含 ROM 识别）/ 运行时诊断 / 模块调试（加载列表、自启动标记、classloader 树、隔离记录、强制重载）/ 下载网络诊断 / 实验开关（均二次确认）/ 数据工具（一键诊断包导出、缓存清理、测试崩溃彩蛋）/ 界面透视（当前路由、路由浏览器含隐藏页直达、源码定位跳转、字符串 key）/ 文件速查（关键路径复制 + SAF 打开、目录占用、设置项长按透视 SharedPreferences key）/ 主题工具（实时主题编辑器可导出、色板查看、尺寸标尺、伪本地化）/ 性能时间线（启动分解、下载流水线、内存曲线、帧率直方图）/ 游戏启动诊断（实际 JVM 参数与环境变量 dump、渲染器选择日志、输入可视化、悬浮层调试）/ 改版元信息（依赖树导出、BuildConfig dump、Issue 模板生成器）。

## 7. 安全模式与救生通道

43. **桌面快捷方式**：static App Shortcut「以安全模式启动」（Manifest 层、非 dynamic），点击后启动器**在模块扫描层整体短路**（零扫描、零加载、零实例化）；横幅常驻提示 + 一键重启恢复；模块页可卸载/更新但不可启用。
44. **物理免疫**：该快捷方式不经过 FeatureRegistry，任何覆盖机制不可触及；桌面层入口不可被启动器内代码增删。
45. **自动触发**：连续两次异常退出 → 第三次启动自动进入安全模式并提示。

## 8. 性能与体验

46. **启动性能**：冷启动增加开销 < 10ms；启动默认零模块加载；模块 dex 体积红线 ≤5MB。
47. **运行性能**：模块页面与内置功能渲染无差别。
48. **低端机体系**：统一降频判定（种子地图、玻璃 UI、游戏内悬浮等共用）；4GB 以下自动降档；内存与帧率可观测。
49. **瓦片/大文件纪律**：瓦片 LRU + 渐进；大文件流式 + 增量索引；压测基线：7 万+ 文件的旅行地图目录、8594 文件的投影库，启动与浏览不卡死。
50. **三态主题**：浅色 / 深色 / OLED 全功能可用；主题参数可整体替换。

## 9. 发布与运营

51. **里程碑**：种子地图完成即具备首版发布条件；投影工坊走模块热更后续发布。
52. **渠道**：GitHub Release 为主；真机验收清单（用户侧，无 adb）：安装启动、渲染器兼容、模块装卸、悬浮开合、双机联机、游戏内性能。
53. **模块索引上线**：官方索引 JSON 热更机制与审核口径同步发布。
54. **文档体系**：总方案书 + 模块文档 + 开发顺序随版本同步维护，每项功能有验收标准。

## 10. 协作约束（目标模式须知）

55. **用户角色**：最终决策者——需求确认、架构拍板、模型选择、预算与并发授权、验收；不负责日常开发管理。
56. **用户侧资源约束**：真机测试、多 ROM/渲染器验证、双机联机由用户执行，AI 侧提供清单与判定标准；构建环境为 PRoot aarch64（无官方 NDK，x86_64 二进制不可直接执行），CI 使用 GitHub Actions x86_64 runner 兜底 native 构建。
57. **AI 供应商纪律**：AI 调用使用用户配置的 key（BYOK），供应商由用户指定，不得自行选型或切换。
58. **验收纪律**：内部双审通过 ≠ 用户验收；无法执行的测试标记未验证；不得虚构构建/测试通过。
59. **状态持久化**：目标、需求确认状态、任务地图、授权范围、审查结论、Bug 记录、未决问题跨会话可恢复，不重复询问已确认事项。

---

# 第二部分　模块系统详细规格

## 2.1 lab-api 契约

**Gradle 形态**：`:lab-api` 独立模块，纯 Kotlin + 仅依赖 Compose 运行时（BOM 对齐宿主），不依赖宿主业务代码。

**核心接口**：

```kotlin
interface LabModule {
    val metadata: ModuleMetadata
    fun onLoad(host: LabHost)      // 加载（ dex 进内存、类已链接 ）
    fun onEnable()                 // 启用（ 可被禁用后重新启用 ）
    fun onDisable()
    fun onUnload()
}

data class ModuleMetadata(
    val id: String,                // 唯一，如 "seed-map"
    val name: String,
    val version: String,           // semver
    val apiVersion: Int,           // 宿主 lab-api 主版本
    val author: String,
    val license: String,           // 强制
    val source: String,            // 强制，仓库地址
    val startup: Boolean = false,  // 是否声明自启动加载（懒加载机制，见 2.4）
    val dependencies: List<ModuleDependency> = emptyList()
)

interface LabPage {
    @Composable fun Content()
}

// 宿主能力接口：模块经由此访问启动器能力（文件、实例、下载、设置项注册等）
interface LabHost {
    val context: Context
    val launcherContext: LauncherContext     // 实例列表/当前实例/文件管理
    fun registerLabEntry(entry: LabEntry)    // 实验室入口
    fun registerUiExtension(ext: UiExtension)// 侧栏/顶栏/设置项注入
    fun registerRoute(route: LabRoute)       // 新路由
    fun registerInGamePage(page: InGamePage) // 游戏内页签（契约先定，宿主 T14 实现）
    // ...能力随版本演进，每个新方法标注所属能力域
}
```

**导航容器化（已锁）**：模块**不得**自行压入宿主导航栈（宿主 NavDisplay 会拒绝未注册 screen，报 Unknown screen）。宿主提供固定容器路由 `LabModulePage(moduleId, route)`，模块内部多页在**模块自己的 Compose 状态栈**切换；宿主提供返回键桥接（模块栈空时交还宿主）。

**扩展点类型**：`UiExtension` 细分目标：实验室入口（LAB）、侧栏（SIDEBAR）、顶栏（TOP_BAR）、设置项（SETTINGS）、主题（THEME）、首页卡片（HOME）。v1 只提供声明式扩展点，不提供"往宿主既有页面任意塞 Composable"的能力。

**InGame 契约先定不实现**：`InGamePage` / `InGameHost` 接口入 lab-api 占位（悬浮面板页签、指针状态查询、性能模式查询），宿主实现随游戏内悬浮阶段落地。

**版本协商**：`apiVersion` 主版本匹配才能加载；次版本只增能力不破坏；模块 apiVersion > 宿主 → 拒装并提示升级启动器。

## 2.2 module.json

```json
{
  "id": "seed-map",
  "name": "种子地图",
  "version": "1.0.0",
  "apiVersion": 1,
  "author": "Vaelin",
  "license": "GPL-3.0",
  "source": "https://github.com/vaelin-launcher/seed-map",
  "startup": false,
  "dependencies": [
    { "id": "cubiomes-bridge", "version": ">=1.0.0", "capabilities": ["biome-at", "render-tile"] }
  ],
  "entryClass": "com.vaelin.seedmap.SeedMapModule"
}
```

## 2.3 模块加载器

- `DexClassLoader(dexPath, optimizedDir, librarySearchPath, parent = 宿主 classloader)`；`optimizedDir` 用应用私有目录（ROM 对私有目录 dlopen 限制用 `os.chmod(0o755)` + `System.load` 显式加载 native 兜底）。
- 加载流程：读 `module.json` → 校验（schema / apiVersion / SHA256）→ 反射实例化 `entryClass` → `onLoad` → （按需）`onEnable`。
- **Compose 一致性铁律**：模块打包时 exclude 全部 `androidx.*` 依赖（compileOnly），运行时由宿主提供；CI 强制模块产物不得含 `androidx/compose/` 类（含 library 型）。
- 失败优雅降级：加载失败 → 模块标记「加载失败」+ 错误卡片展示原因；不影响其他模块。

## 2.4 懒加载机制（已锁）

- 启动阶段：只读**轻量清单**（模块 id/version/startup 标记，来自索引 DB，不读 dex），构造注册表。
- `startup = true` 的模块：启动后空闲线程依次加载（不阻塞首帧）。
- 其余模块：**首次**进入其注册界面/功能时才 load dex + onLoad + onEnable；加载耗时显示在界面内（不白屏）。
- 模块被 disable / 崩溃禁用后，已加载类保留，仅停用入口；卸载才彻底释放。

## 2.5 崩溃隔离 L1–L5

| 层 | 机制 |
|---|---|
| L1 调用期包裹 | 宿主调用模块接口处统一 try-catch，异常 → 记日志 + 本次调用降级 |
| L2 模块边界 | 模块 UI 外包 `ModuleErrorBoundary`（Compose 版 ErrorBoundary），渲染崩 → 显示错误卡片替代内容，宿主不崩 |
| L3 模块级禁用 | 单模块崩溃达阈值（如 3 次）→ 自动禁用该模块，提示用户 |
| L4 自动安全模式 | 连续两次异常退出 → 第三次启动自动安全模式 |
| L5 手动安全模式 | 桌面 static shortcut（见第四部分） |

## 2.6 模块管理器

- 安装：索引下载（GitHub Release zip）/ 本地导入；装前 SHA256 校验 + apiVersion 校验 + Compose 类检查；装错即拒。
- **数量提示**：已装 >10 个时，装第 11 个立即弹风险提示（启动变慢、页面卡顿可能），仅提示不拦截。
- 卸载：删目录 + dexopt 缓存 + 该模块的设置项与覆盖声明还原；被依赖中的前置模块拦截卸载。
- 启停持久化；索引 JSON 热更（带缓存与回退）。

## 2.7 FeatureRegistry（覆盖机制）

- 内置功能全部注册表驱动：页面/卡片/设置项/路由/后台任务均有 FeatureEntry（id、类型、provider、enabled、overriddenBy）。
- 模块 `overrides` 声明：`disable`（摘除）/ `replace`（同 id 替换 provider，原实现不执行）/ `hide`（隐藏入口，后台保留）。
- replace 冲突：后加载者胜，记录 `overriddenBy` 可查；卸载即还原。
- **合规开关（地区识别与功能关闭）不进注册表**——唯一不可覆盖项，位于核心层。

## 2.8 服务注册表与前置模块

- 模块间不直接类引用；能力经 `host.getService(id, Interface::class)` 按接口寻址。
- 前置模块 = 无 UI、纯服务注册的 library 型模块；被依赖时自动提示安装；候选：cubiomes-bridge、nbt-tools、shizuku-bridge、terracotta-bin、skinview3d-runtime。

## 2.9 开发者闭环

- `vaelin-module-template` 仓库：UI 型 + library 型两套模板、Gradle exclude 配置、`module.json` 模板、打包脚本、Actions 工作流（构建 zip + SHA256 + Release）。
- lab-api 发布：随宿主 git tag 发布（JitPack 或等效托管；国内可达性问题以仓库内置 `lab-api.aar` compileOnly 备选兜底）。
- 模块 CI 三检：无 `androidx/compose/` 类、apiVersion 匹配、zip 结构合规。

---

# 第三部分　第三方模块导入与安全

## 3.1 信任分级

| 等级 | 判定 | 待遇 |
|---|---|---|
| 官方签名 | 签名匹配作者公钥 | 静默安装，无弹窗 |
| 第三方 | 其余一切（含社区签名、裸包） | 完整风险弹窗流程 |

社区签名（自有签名 + license/source 齐全）在模块详情页展示「未验证开发者」+ 仓库链接，但不豁免弹窗。

## 3.2 导入弹窗（每次第三方导入必经）

**首屏**：

> **导入第三方模块？**
>
> 第三方模块拥有启动器的**几乎全部权限**——它可以修改启动器界面、读写所有文件，甚至重构 Minecraft 的启动流程。
>
> 如果是恶意模块，它可能利用启动器的文件管理权限**盗取你的游戏存档、账号信息和本地文件**。
>
> ─────────────────
> 模块：{name}（v{version}｜作者：{author}｜{签名状态}）
> ─────────────────
>
> ⚠️ 我无法验证此模块的安全性，请只导入你信任的来源。

按钮（默认焦点=取消）：

- **[无视风险，直接导入]**（红色）→ 二次确认
- **[取消]**

**二次确认 + 5 秒锁定**：

> **最后确认**
>
> 你即将以**完整权限**运行一个未经审核的第三方模块。
> 该模块将能访问启动器管理的**所有文件**，且启动器**无法阻止**其行为。
> 由此造成的一切损失由你自行承担。
>
> 模块：{name}（v{version}）

- **[确认导入]** 初始禁用，倒计时 5 秒（按钮文字实时 `确认导入（5）`…`（0）`），倒计时期间点击无效；每次导入必经，不可跳过
- **[返回]**

## 3.3 模块页管理

- 模块列表：官方徽标 / 「未验证开发者」徽标 / 「已禁用」「加载失败」状态。
- 详情页：版本、license、source、依赖列表、调用日志入口、启用/禁用/卸载、覆盖声明展示（ FeatureRegistry 的 overriddenBy ）。
- 卸载：清目录、缓存、设置项、覆盖还原；被依赖的前置模块拦截。

## 3.4 作者责任边界（口径）

- 弹窗与免责条款已尽告知义务；第三方模块损失用户自担。
- 官方渠道零教程化绕过、零涉钱。
- 合规开关在核心层，模块不可触及。

---

# 第四部分　安全模式实现规范

## 4.1 开启方式

- **桌面 static App Shortcut**「以安全模式启动」：`res/xml/shortcuts.xml` 声明（**非 dynamic**——dynamic 可被 `ShortcutManager` 运行时删除，模块进程内代码可触及；static 烧在 Manifest，安装时定型，运行时不可增删）。
- Intent 带 `safe_mode=true` extra → 主 Activity。
- 辅助入口（迭代 2，可选）：开发者选项「每次启动询问是否安全模式」，Beta 调试用。

## 4.2 实现

```kotlin
// Application.onCreate() 最早期
val safeMode = intentExtra("safe_mode") == true || prefs.getBoolean("auto_safe_mode", false)
if (safeMode) {
    ModuleLoader.globalSkip = true     // 扫描层整体短路：不读模块目录、不建 DexClassLoader
    prefs.putBoolean("safe_mode_active", true)
}
```

- **短路点在 ModuleLoader 最外层**：不是"加载后禁用 UI"，而是扫描阶段直接跳过——某模块的 `module.json` 本身就会崩也碰不到执行机会。
- `safe_mode_active` 持久化；普通重启不清除（一次会话状态，显式退出）。
- 崩溃计数：启动时检测上次非正常退出 → `crash_count` +1，正常启动清零；≥2 → `auto_safe_mode=true`。

## 4.3 UI 表现

- 首页顶部常驻横幅：「安全模式运行中——模块已禁用」+「重启恢复」按钮。
- 实验室 → 模块页可进：全部标记「已禁用（安全模式）」；允许卸载/更新，禁止启用/加载。
- 游戏内悬浮不挂载模块页；内置功能不受影响。
- 退出：点「重启恢复」→ 清三标记 → 正常重启（用户手动普通重启作兜底）。

## 4.4 验收

- static shortcut 在 Manifest（代码审查）；安全模式下 ModuleLoader 零扫描（日志验证）；崩溃计数链路单测；快捷方式在 FeatureRegistry 全面接管 UI 后仍物理可达（回归）。

---

# 第五部分　开发者选项（Beta 独占）

## 5.1 门禁

- 独立 `:dev-options` 模块，仅 Beta flavor 依赖；Release 编译期剔除 + CI 扫描 Release 产物中无相关类名/字符串。
- 设置页版本号连点 7 次解锁，状态持久化；危险开关二次确认。

## 5.2 分区明细

**A 构建信息**：版本名/码、构建类型、Git SHA（CI 注入）、构建时间、签名证书指纹 SHA-256、lab-api 版本。

**B 设备信息**：SoC/ABI/核心数、内存（ActivityManager.MemoryInfo）、存储余量、Android 版本/API、ROM 识别（MIUI/ColorOS/OriginOS/原生 + 版本号）、GPU、OpenGL ES/Vulkan、分辨率/dpi/刷新率、WebView 版本。

**C 运行时诊断**：当前实例 JVM 内存分配、JRE 版本、当前渲染器及驱动版本串、崩溃历史 + 最近摘要、日志级别开关、游戏内 FPS 悬浮窗开关。

**D 模块调试**：已加载/未加载模块列表（含自启动标记、加载耗时）、classloader 树、崩溃隔离记录（L1–L5 哪层拦下）、强制重载、清 dexopt 缓存、模块调用日志查看。

**E 下载与网络**：当前镜像源 + 测速对比、请求日志开关（含 curl 导出）、损坏下载缓存扫描清理。

**F 实验开关（危险区）**：模拟低内存设备、指针捕获状态可视化、Compose 重组高亮/布局边界；均默认关 + 二次确认。

**G 数据工具**：一键导出诊断包（设备信息 + 最近日志 + 模块列表 → zip，可分享）、缓存占用统计与清理、彩蛋：一键触发测试崩溃。

**H 界面透视**：当前路由常驻小条（点击复制）、路由浏览器（含隐藏/实验页直达）、页面源码定位（文件:行号 + 跳转 GitHub）、当前 Activity/Fragment 栈（过渡用）、字符串资源 key 列表。

**I 文件速查**：关键路径一览（filesDir、modules/、实例目录、游戏目录、shared_prefs、dexopt 缓存）带「复制路径」「SAF 打开」、目录占用分析、设置项长按透视（SharedPreferences key/值/类型）、关键 JSON 只读查看。

**J 主题工具**：实时主题编辑器（改 seed/圆角/间距全应用实时刷新，可导出代码）、色板查看器（点击复制）、尺寸标尺（dp 网格 + 间距标注）、深色/OLED 快速切换、伪本地化检测溢出。玻璃 UI 参数为内置预设之一。

**K 性能时间线**：冷启动各阶段耗时、下载流水线各文件耗时、实时内存曲线 + GC 统计、帧率直方图。

**L 游戏启动诊断**：实际 JVM 参数/环境变量/工作目录 dump、渲染器选择日志、游戏内输入可视化（触摸点/指针捕获/按键日志）、悬浮层坐标/z-order/遮挡关系。

**M 改版元信息**：完整依赖树导出、BuildConfig 全字段 dump、Issue 模板生成器（设备信息 + 版本 + 日志 → Issue 文本）、改版向导入口（仓库结构说明）。

## 5.3 分期

- 迭代 1（首版 Beta 前）：A–D、F–J 的主体。
- 迭代 2（Beta 滚动）：E、K、L、M。

---

# 第六部分　AI 模块工坊（官方模块）

## 6.1 定位与流程

官方模块「AI 模块工坊」：对话描述想法 → AI 生成完整模块工程（module.json + Kotlin）→ 云端构建 → 安装（走第三方模块导入流程）。

```
用户想法 → AI 对话框（BYOK）→ 逐文件预览/修改 → 「构建并安装」
  → 推送用户 GitHub 仓库（fork 自 vaelin-module-template，公开，GPL）
  → GitHub Actions 构建（gradle → zip + SHA256，公开仓库免费不限分钟）
  → 下载 → 校验 → 第三方导入弹窗流程（含 5 秒锁定）
  → 编译失败：日志回喂 AI 自动修复（上限 3 次），超限转人工对话
```

## 6.2 纪律

- BYOK 唯一；key 存 Keystore 加密；日志脱敏（路径用户名/token 打码）。
- 生成模块强制 `license: GPL-3.0` + `source`；标注「AI 生成」；对话框明示「代码将公开」与大致 token 成本。
- AI 只写代码不执行代码；构建在 GitHub Actions（x86_64 runner，无 NDK/超时问题）。
- 典型用例：模组翻译（资源包型模块）、小工具、界面增强、游戏内辅助、空模块骨架。
- 离线/无 GitHub：优雅降级（提示需求，保存工程草稿）。

---

# 第七部分　Shizuku 权限桥（前置模块）

- 封装 Shizuku（用户需另行安装并用无线调试/root 激活），提供 shell 级能力：pm 系命令、shell 进程执行、`/sdcard/Android/data` 访问（shell 豁免存储限制）。
- 能力白名单制：依赖模块在 dependencies 中声明 `capabilities`（如 `shell-exec`、`android-data-access`），安装/启用时**逐项弹确认**，用户可逐项收回。
- 桥自身不进懒加载（被依赖时按需加载）；调用记录进开发者选项 D 区日志。
- 引导链路：检测 Shizuku 未装/未激活 → 图文引导激活。
- 诚实边界：Shizuku 给不了 root 级能力（改不了其他应用 /data/data、系统分区）；文案不吹"免 root 超级权限"。
- 合规：Apache-2.0 注明来源。

---

# 第八部分　玻璃 UI 官方皮肤模块

## 8.1 设计语言（参数落表进主题编辑器预设）

- 底色：深蓝灰半透明；卡片：白色 8–12% 不透明度 + 背景模糊 + 1px 顶部高光描边；大圆角 squircle；晶体棱线受光面；青蓝渐变强调色；动效以淡入淡出为主（玻璃元素避免大位移）；OLED 态深色玻璃 + 纯黑底层。

## 8.2 实现要点

- `UiExtension(THEME)` 整体替换 ColorScheme/形状/间距；`UiExtension(HOME/NAV_BAR)` 提供 squircle 高光组件。
- **模糊三级降级**（复用统一降频判定）：高端实时 RenderEffect blur → 中端降半径/减层 → 低端/降频静态磨砂预渲染图。
- 图标：自适应分层（squircle 底托=背景层、晶体=前景层）+ 单色 themed icon；模块启用时提示确认更换，禁用/卸载恢复默认。
- 开源为 `vaelin-module-glass-ui`，作为第三方主题模块参考实现。

---

# 第九部分　世界工具三件套详细规格

## 9.1 种子地图

- cubiomes 以源码引入编 `libcubiomes.so`（CMake，arm64-v8a 首版），JNI 接口：init / biomeAt / renderTile / findFeatures / findSpawn / scanOres / release；指针生命周期严格管理。
- Compose 瓦片地图：LRU 32 片、并发 2、渐进渲染（fallbackTileScale）；版本结构映射表独立 JSON 热更。
- level.dat 读种子；收藏导出（与社区"种子库"模块衔接）。
- 游戏内只读查看器（挂 InGamePage）；低端机禁用矿物扫描。
- 验收：已知种子结构与出生点和参考实现（cubiomes 参考工具）逐点一致；大范围平移不 OOM。

## 9.2 投影工坊

- 解析库（schematic4j，MIT 已核实）+ 渲染引擎（Nucleation，Rust→C FFI→NDK→JNI，greedy meshing，MIT 已核实）；三格式 .litematic/.schem/.schematic。
- 区块流式加载防 OOM；旋转/镜像/图层开关；格式互转导出；游戏内只读查看器。
- **投影库纪律**：启动零扫描；增量索引（mtime+size）；分页加载；压测基线 8594 文件不卡。
- 验收：三格式正确渲染；大投影不 OOM；导出 .schem 被 PC 端工具读入。

## 9.3 存档编辑器

- NBT 解析（纯 JVM 库）+ MCA region 读写（512 字节扇区表 + zlib）；删除区块 = 位置表项清零；跨存档粘贴 = 字节级搬运 + 坐标重映射（实体 double、方块实体 int、计划刻，1.18 前 Level 包裹层与 1.18 后拍平层两种布局都认）。
- **只同数据版本互粘**（跨版本明确拒绝）；粘贴前自动备份源/目标 region 文件为 zip 存旁；写操作失败可回滚备份。
- 2D 区块选择界面复用种子地图瓦片基建；操作队列化、可取消。
- 验收：删除后 MC 重进世界正常再生成；粘贴后区块内容/实体位置正确；备份可恢复。

---

# 第十部分　资源源与地区闸

## 10.1 源框架

- 源抽象接口：搜索/详情/版本列表/文件地址解析；内置源：Modrinth、CurseForge、**XyeBBS**（官方开发者 API：鉴权请求头 + 分页约定 + 频率限制，凭据走开发者注册）。
- **闸在框架层**：`SourceGate` 统一拦截"文件分发动作"（下载/安装）；检索与展示信息全球可用。
- 判定信号：系统地区 + SIM 国家码（MCC），双信号失败默认按大陆处理；不提供反绕过加固（开源无意义）；官方渠道零教程。

## 10.2 镜像与下载

- 下载器支持镜像源切换（如 BMCLAPI 类）与失败回退官方源；下载诊断进开发者选项 E 区。
- 国内网络环境差场景的断点续传、多线程分片按参考项目成熟实现移植。

---

# 第十一部分　游戏内体系

- **控件系统**：布局编辑器（增删控件、绑定按键、透明度、大小位置）、多套预设、导入导出；兼容主流布局格式（FCL 系格式按特征解析，转换失败给提示不静默丢弃）。
- **悬浮系统**：悬浮球拖动/贴边/展开面板；指针捕获获取与释放正确（展开时捕获、收起归还游戏）；兼容多渲染器（Zink/VirGL/HGL）；展开不丢游戏输入；低端机降频模式；模块游戏内页签经 InGamePage 契约挂载。
- **联机**：陶瓦/EasyTier 方案；VPNService 权限链路 + 前台服务（Android 14+ foregroundServiceType）；邀请码创建/加入/成员/状态；免责声明（P2P 自建网络）；地区闸自动隐藏；AGPL/LGPL 标注。

---

# 第十二部分　性能总则

1. 启动 <10ms 附加开销；启动零模块加载、零用户内容扫描。
2. 模块 dex ≤5MB；图片资源走 zip 文件流，不进 dex。
3. 统一降频判定：4GB 以下/用户开启/温度策略，种子地图、玻璃 UI、悬浮、游戏内查看器共用。
4. 一切大目录（瓦片、投影、存档、第三方 mod 数据）默认不可见；进入专属工具才按需、增量、分页。
5. 可观测：开发者选项 K 区提供启动分解/内存/帧率数据，供低端机调试验证。

---

# 第十三部分　验收与协作附则

- 每项功能以第一部分对应条目 + 所在部分规格为验收依据；无法自动化验证的项标注「用户侧验收」并提供清单。
- 双智能体审查通过 ≠ 用户验收；不得虚构测试通过。
- 构建环境约束（PRoot aarch64、CI x86_64 兜底）与 BYOK 纪律见第一部分 §10。
- 状态跨会话持久化，恢复时不重复询问已确认事项。

---

*全量规格书 v1.0。本文档自包含，目标模式不得依赖本文之外的任何历史文档。*
