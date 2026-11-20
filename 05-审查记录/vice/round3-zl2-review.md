# Round3 复核：ZL2 基准重审（Q1–Q4）

- 审查人：vice-reviewer
- 基准：规格书 v1.2 + 01-文档/04 v1.1 + ZL2 源码（02-参考源码/ZalithLauncher2/ZalithLauncher/，871 文件/157,996 行）
- 方法：全部结论基于本轮实测读取 ZL2 源码，行号可复核；未改动 01-文档/ 与 lead 负责文件
- 结论速览：Q1 有 1 处实锤错划（GameHandler.kt）；Q2 有致命遗漏（GlobalContext/设置存储/通知/渲染器加载失败语义），另有 1 项 R1 任务语义可能建立在误解上（见 Q4）；Q3 三判断全部成立，薄实现需覆盖 6 个成员且注意 Lazy 签名；Q4 存在**结构性误解**——JvmService 不是游戏运行进程，游戏在 :game 进程的 VMActivity 里，模块系统的进程归属必须在 R1 重定。

---

## Q1 复用边界有没有错划：**有，1 处实锤 + 1 处隐性风险**

### 实锤错划：GameHandler.kt 不能整体划入"复用"

01-文档/04 §3.3 把 GameHandler.kt（228 行）划进复用清单，但实测：

- `GameHandler.kt:61`：构造函数收 `val activity: Activity` —— 与 ZL2 的 VMActivity 生命周期直接绑定
- `GameHandler.kt:96`：`MCOptions.setup(activity)`
- `GameHandler.kt:231`：`@Composable fun ComposableLayout()` → 内部调 ZL2 的 GameScreen —— 这是纯 UI 组合函数

三处证据说明 GameHandler 是「输入/手柄/Terracotta 桥接（可复用）+ Activity/Compose UI 绑定（必须自写）」的混合体。按 lead 自己的划线原则（"用户看到并操作的一切自写"），GameHandler 的 ComposableLayout 部分和 Activity 依赖恰恰是"用户看到的游戏画面容器"，属于自写层。

**建议**：§3.4 增加「游戏画面宿主层（ZL2 的 VMActivity + GameHandler 的 UI 绑定部分）→ 自写」条目；§3.3 的 GameHandler 条目改注「仅复用输入桥接/SdlBridge/Terracotta 逻辑，UI 绑定部分自写」。不拆的话，Vaelin 自写的游戏 Activity 要么继承 ZL2 的 UI（违背第 7 条 Compose 自写），要么 fork 整个文件（违背复用边界）。

### Launcher.kt 531 行：划入正确，但要带上依赖清单

实测 Launcher.kt 的 import：**无任何 Compose UI import**，纯启动逻辑，可以划入复用。但它强依赖这些全局件，复用 Launcher.kt 就等于连带复用或自写替换它们：

- `GlobalContext`（context/Contexts.kt 顶级委托属性 `var GlobalContext by Delegates.notNull<Context>()`，ZL2 全库 33 处引用，refreshContext → PathManager.refreshPaths）
- `AllSettings`（设置存储）、`PathManager`、`LibPath`
- `FFmpegPluginManager` / `NativePluginManager` / `RendererPluginManager`（三个插件管理器）
- `Components`（组件清单）

**建议**：§3.3 为 Launcher.kt 加一行"依赖锚点"清单，R1 任务里显式包含"自写 GlobalContext/AllSettings 等价物或直接复用 Contexts.kt"。否则 T1 系列任务会在编译期大面积报错，属于"少带一个依赖、卡半天"型陷阱。

### 被误判成 UI 的纯逻辑件：未发现，但有半 UI 半逻辑件

§3.4 的自写清单里没有发现把纯逻辑件误判成 UI 的情况。但 `VMActivity` 本身是混合体：它是 `:game` 进程的游戏画面宿主（SurfaceTextureListener + SDLSurface + ProcessPhoenix 崩溃重启，Manifest:120-124 与 :474 附近），其中 Surface 生命周期管理属于"渲染底座"而非用户可见 UI。**建议**：把"Surface 生命周期/进程崩溃重启"从自写 UI 层拆出，单列为复用或参考实现的底座逻辑，否则自写游戏 Activity 时容易漏掉 ProcessPhoenix 式崩溃恢复。

---

## Q2 R1 十四任务的致命遗漏：**确认 5 项 + 补 3 项**

lead 列的五项全部属实，且每一项都是"缺了就编译不过或首启即崩"级别：

1. **GlobalContext 单例**：33 处引用的顶级委托属性，ZL2 几乎所有非 UI 代码都经它取 Context。缺它 → 编译不过。
2. **设置存储（AllSettings）**：与 GlobalContext 同级依赖，PathManager.refreshPaths 由它触发。
3. **notification/ 前台通知**：`JvmService` 是前台 Service（自带 Notification 构造，JvmService.kt 40 行附近），Android 14+ 前台服务必须通知 + `POST_NOTIFICATIONS` 运行时权限（ZL2 在 `ui/components/_Notification.kt:69` 请求）。缺它 → `:jvm`/`:game` 服务在 Android 13+ 直接启动失败。
4. **JNA 解压**：`UnpackJnaTask.kt` 在 `components/jre/` 下。R1 T1.3 只写了"验证 JRE 包 lib/ 内 so"，JNA 的 jar/so 解压是独立步骤，缺它 → JVM 启动后 JNA 类找不到，部分 mod 加载链断。
5. **LibSortFix 类路径排序**：`LinkedHashMap` 保序 + asm 库 dedupe（插入 `org.ow2.asm:asm` 时移除 `asm-all`）。缺它 → forge 类路径冲突，装 forge 的用户必现。

**补三项（lead 没列但实测发现）**：

6. **渲染器 dlopen 失败是静默的**：`GameLauncher.kt:212-228 dlopenEngine()`——先 `getDlopenLibrary()` 逐个 dlopen（:218-221），再主库两级 dlopen（裸名 → findInLdLibPath），**失败仅 `Logger.error` 无中止、无回退**（:225-227）。ZL2 靠 `-Dorg.lwjgl.opengl.libname=` 注入（:230-235）兜底，但库真没加载时游戏会以坏状态启动。Vaelin R1 的 T1.8/T1.12 必须补一条验收判据："渲染器 so dlopen 失败 → 显式报错中止，不进游戏"。
7. **JRE 系列库 dlopen 清单**：`Launcher.kt:519-525` 依次 dlopen libjli/libjvm/libfreetype/libverify/libjava/libnet/libnio——这是"JVM 拉起来"的必要前置，R1 的启动任务应显式列这 7 个库，不能只写"调 ZL2 启动逻辑"。
8. **Renderers 注册表回退语义**：`Renderers.setCurrentRenderer` 找不到当前渲染器时回退到注册表第一个内置项。Vaelin 自写注册表如果保留这个语义，用户配置的第三方渲染器插件失效时会静默换引擎——建议 Vaelin 侧改为"找不到即报错"，这要在 R1 的 T1.8 里定，拖到 R2 就是行为兼容包袱。

---

## Q3 渲染器协议三判断：**全部成立；薄实现需覆盖 6 成员，注意 Lazy 签名**

- **判断①（gl4es ↔ GL4ESRenderer/NGGL4ESRenderer）**：成立。GL4ESRenderer.kt：id=`opengles2`、`libgl4es_114.so`、env=emptyMap、dlopen=emptyList、无 EGL override。
- **判断②（zink ↔ KopperZinkRenderer）**：成立。KopperZinkRenderer.kt：id=`opengles3_desktopgl_zink_kopper`、mesaBased=true、needsZinkDriverOverride=true、env={LIBGL_ES:3}、EGL=`libEGL_mesa.so`。
- **判断③（ZL2 无 LTW）**：成立。全库检索 "ltw" 命中 22 处全是 `usePlatformDefaultWidth` 的子串误命中；唯一真命中是 GameJsonMerger.kt:255/276 的中文注释「Tweaker」。**ZL2 确实没有 libltw 对应实现，需要 Vaelin 新写薄 RendererInterface**。

### 薄实现必须覆盖的成员（实测 RendererInterface.kt:18-83）

无默认实现、必须写的 6 个：

| 成员 | 签名注意 |
|---|---|
| `getRendererId()` | String |
| `getUniqueIdentifier()` | String |
| `getRendererName()` | String |
| `getRendererEnv()` | **返回 `Lazy<Map<String, String>>`，不是 Map** |
| `getDlopenLibrary()` | **返回 `Lazy<List<String>>`** |
| `getRendererLibrary()` | String（主 so 名，如 libltw.so） |

可省的：`getRendererEGL()` 默认 null → 不注入 `POJAVEXEC_EGL`（消费点 GameLauncher.kt:469-470/494，null 时走 ZL2 默认 EGL 路径）；`getRendererSummary/getMinMCVersion/getMaxMCVersion` 默认 null；`mesaBased/needsZinkDriverOverride` 默认 false；`supportsSfpewByDefault()` 默认 false。

**两个坑**：
1. `getRendererEnv/getDlopenLibrary` 的 **Lazy 包装**——按 Map/List 直接实现会编译错。LTW 薄实现大概率为 env=emptyMap、dlopen=emptyList（对齐 GL4ES 形态），但签名必须保留 Lazy。
2. **注册时序与回退**：Renderers.kt 内置 7 项（NGGL4ES/GL4ES/KopperZink/MobileGlues/VirGL/Freedreno/Panfrost），插件渲染器经 RendererPlugin/RendererV2Plugin 注册。若 LTW 薄实现注册晚于"当前渲染器解析"，会被回退语义静默换掉（见 Q2 补项 8）——T1.8 验收要含"LTW 被选中后 dlopen 的是 libltw.so"。

---

## Q4 R2 会不会推翻 R1：**会——因为 JvmService 的角色被误解了**

### 结构性发现：JvmService 不是游戏运行进程

实测证据链：

- `JvmService.kt` 位于 `game/download/jvm_server/` 包 —— 它属于**下载/安装子系统**
- `GameJVMRunner.kt:82/162-220`：`startJvmServiceAndWaitExit(...)`，带 `SERVICE_START_RETRY_MAX` 重试（:203/209），`GlobalContext.stopService`（:220）——跑的是**安装器 JVM**（MC 下载/安装工具链），不是游戏
- `JvmService.kt:194`：`launcher.launch(...)` 是安装器流程的 launch
- **游戏运行时**：`VMActivity.kt:227` 直接构造 `GameLauncher(...)`；VMActivity 在 Manifest:120-124 标 `android:process=":game"`。即：**游戏画面、渲染器 dlopen、进程崩溃重启，全部发生在 :game 进程的 VMActivity 里，与 :jvm 无关**

Manifest 全景：VMActivity=:game(:120-124)、FileManagerActivity=:filemanager(:132-140)、GameService=:game(:171-179)、TerracottaVPNService=:game(:181+)、JvmService=:jvm(:161-169，仅安装期)。

**这意味着 01-文档/04 §1/T1.10「拿 ZL2 的多进程与 JvmService、:jvm 进程独立打通」如果按"游戏 JVM 承载在 :jvm"理解，是错的**。ZL2 的 :jvm 只服务安装器；游戏本体（JVM + 渲染）就在 :game 进程内。T1.10 的验收判据":jvm 进程独立"即便打通，也保不了游戏崩溃隔离——游戏崩的是 :game。

**建议**：T1.10 重写为「安装器 JvmService 打通（保留，价值在下载/安装阶段的后台保活与重试）」+「游戏运行隔离 = :game 进程级隔离（ProcessPhoenix 崩溃重启模式）」两条，不要把游戏隔离押在 :jvm 上。

### 模块系统与多进程的结构冲突（R1 必须定、否则 R2 返工）

1. **模块页面渲染进程**：主进程的 NavDisplay（NavCompose）**无法**组合 :game/:jvm 进程的 Compose 内容——跨进程没有 Compose 组合通道。ZL2 自己的做法就是跨进程用 Activity 跳转（VMActivity、FileManagerActivity 各自独立 taskAffinity）。所以模块 UI 只有两条真路：
   - **A：模块页面全在主进程**渲染（DexClassLoader 在主进程加载）→ 模块崩溃连主进程，L1–L5 隔离里只有进程内隔离可用（独立 classloader + 崩溃边界），要求 E5 双异常隔离实验提前到 R1
   - **B：仿 ZL2，模块宿主是独立进程 Activity（如 :module）**→ 崩溃隔离最强，但跨进程通信（AIDL/信使）+ 模块进程里要有一套自己的 Compose 宿主与主题，工作量显著增加
   - 这是架构级选择，**必须在 R1 定**，拖到 R2 就是导航层重写。
2. **DexClassLoader 与 ：game 的关系**：若未来允许"游戏内模块"（依赖 :game 的 Surface/输入），模块 classloader 需在 :game 进程加载——而 ：game 已被 ZL2 模式定为高崩溃风险进程（游戏崩是常态）。建议 R1 明确：R2 首版模块**只覆盖主进程功能**，游戏内扩展列为 R3+，避免模块系统一上线就背着 :game 的崩溃率。
3. **E5 实验（双异常隔离）在多进程下的新约束**：Recomposer 冒泡异常在跨进程场景天然隔离（崩的是子进程），真正要防的是主进程内模块页面崩主进程——E5 的测试面应聚焦主进程模块页，:module/:game 进程靠"崩了重启"兜底即可。

### 结论

R1 的 14 任务里 **T1.10 需要重写语义**；另需新增一个 R1 级架构决策：「模块页面渲染进程 = 主进程（A）还是独立进程（B）」。其余 13 任务与多进程无结构性冲突，但 Q2 的 8 项遗漏要先补进任务清单。

---

## 附：本轮新增实测证据索引

| 证据 | 位置 |
|---|---|
| GameHandler 收 Activity / @Composable | GameHandler.kt:61/:96/:231 |
| VMActivity=:game、FileManager=:filemanager、JvmService=:jvm | ZalithLauncher/src/main/AndroidManifest.xml:120-124/:132-140/:161-169 |
| GameLauncher 在 VMActivity 内构造（游戏在 :game 跑） | ui/activities/VMActivity.kt:227 |
| JvmService 属安装子系统、带重试 | game/download/jvm_server/GameJVMRunner.kt:82/:162-220; JvmService.kt:194 |
| dlopenEngine 静默失败 | game/launch/GameLauncher.kt:212-228 |
| POJAVEXEC_EGL 注入与消费 | GameLauncher.kt:469-470/:494; RendererPlugin.kt:52; KopperZinkRenderer.kt:48 |
| JRE 7 库 dlopen | game/launch/Launcher.kt:519-525 |
| RendererInterface 必实现 6 成员 / Lazy 签名 | game/renderer/RendererInterface.kt:18-83 |
| LTW 无支持（子串误命中排除） | 全库 grep；真命中仅 GameJsonMerger.kt:255/:276 注释 |
| GlobalContext 顶级委托、33 处引用 | context/Contexts.kt |
| 注册表回退首个 | Renderers.kt setCurrentRenderer |
| 通知权限运行时请求 | ui/components/_Notification.kt:69 |
