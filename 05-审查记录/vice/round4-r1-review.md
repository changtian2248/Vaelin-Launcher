# Round4 副审报告：R1-partial-2026-11-07T10:00 快照

- 审查人：vice-reviewer（独立只读，未修改任何代码）
- 审查范围：T1.5 / T1.6(+b) / T1.9 / T1.10 / T1.10b / T1.12 / T1.13 的交付代码与测试；T1.11（LaunchCoordinator.kt）未交付、不在范围内
- 证据分级：【读过】= 本轮实际读盘或执行命令所得；【推断】= 由已读代码推导；【未验证】= 需真机/真实 JVM 才能判定
- **总判定：有保留通过（pass with conditions）** —— 代码质量高于预期，四问中 Q1 无阻断性契约错误、Q3 进程模型成立；Q4 证据链有缺口（见 4.3，必须补）；Q2 有真实机路径盲区（见 4.2）。列出 3 项必须修（M1–M3）+ 4 项建议修（S1–S4）。

---

## 1 证据基础（我实际做过什么）

【读过】的文件（全部通读或关键段）：
- launch：VLaunchArgs.kt（496 行全读）、VaelinLauncher.kt（327 行）、VLaunchArgsTest.kt（442 行，17 测试）
- download：FileDownloader.kt、VersionDownloadPlanner.kt、LibraryRules.kt
- input：NativeInput.kt、NativeInputTest.kt
- jvm：InstallerJvmScheduler.kt（201 行）、JVMSocketServer.kt（121 行）、JVMHandler.kt（113 行）、JvmService.kt（261 行）、GameJVMRunner.kt（69 行）、JvmSchedulerContract.kt、JvmServiceContract.kt、JvmCrashException.kt、ProcessLogTailer.kt、InstallerJvmSchedulerTest.kt（242 行）
- plugin：PluginDiscovery.kt（264 行）
- renderer：VaelinRenderers.kt（141 行）、RendererLibraryLoader.kt（107 行）
- app/game：VMActivity.kt（331 行）、GameService.kt（114 行）、GameCrashWatchdog.kt（112 行）、GameCrashReceiver.kt（57 行）、GameLaunchContract.kt
- Manifest：app 与 startup-core 两个 AndroidManifest.xml + 合并产物
- diag：CrashReporter.kt（169 行）、FailureClassifier
- 测试 XML 27 份（正则统计）+ 7 份构建日志 + APK 内容清单

【未验证】（需真机或真实 JVM）：
- dlopen/Os.setenv/chdir 真实行为、`ZLBridge` JNI 符号绑定、UDP 跨进程消息到达、:game/:jvm 真实进程隔离、游戏真实启动（R1 明确 execute() 不调 launch，本来就未到那步）
- 4 个 tar.xz 只验证了 xz 魔数与条目在包内，**未解包验证 JRE 内部结构**（libjli.so 等是否真的存在）——这直接影响 M1 的严重度评估

## 2 Q1 契约与正确性

### 2.1 通过项（关键契约逐条核对）

| 契约点 | 结论 | 证据 |
|---|---|---|
| buildLibraryPath 10 段顺序 | ✅ 正确且被整串测试锁定 | VLaunchArgs.kt:193-209；VLaunchArgsTest.kt:94-123 直接比对整串 |
| purge 列表 | ✅ 11 项前缀 purge + 可覆盖 -D 键 31 个，测试覆盖「用户同键跳过+默认值注入」 | VLaunchArgs.kt:35-83、:250-270；VLaunchArgsTest.kt:190-205 |
| LibraryRules.isAllowed last-match-wins | ✅ 初始 disallow、按文档顺序覆盖，独立实现完整语义 | LibraryRules.kt:76-85 |
| RendererLibraryLoader 失败语义 | ✅ 全候选失败抛 RendererLoadException（含 rendererId/libraryName/searchedPaths），dlopen 注入、零 Android 依赖 | RendererLibraryLoader.kt |
| NativeInput 事件号/掩码 | ✅ 1003/1006 与 ZLBridge.java:29/31 对齐；BUTTON1/2/3=1<<10/11/12；MOVE/CANCEL 不补按键；尺寸非法抛异常不静默 1:1 | NativeInput.kt:120 行内；NativeInputTest.kt:75-79 |
| cacio 条目数 | ✅ 16 条（10 exports+6 opens），注释说明任务书 14 条 vs 参考实现 16 条的取舍 | VLaunchArgs.kt:86-107 |
| 渲染器注册表 | ✅ setCurrentRenderer 找不到抛 IllegalArgumentException（修正了 ZL2 静默回退缺陷，注释说明刻意差异）；9 内置渲染器 | VaelinRenderers.kt:109-118 |
| pojavEnv 解析 | ✅ split(limit=2) 保留 value 中的 '='，比 ZL2 的 split[1] 更完整（独立实现完整语义） | PluginDiscovery.kt:154-170 |
| 插件库不存在 | ✅ 不抛但记 skipped + libraryPresent=false → Registrar 拒注入，不静默 | PluginDiscovery.kt:216-239 |

### 2.2 发现的问题

**M1（必须修）：dlopenJavaRuntime 10 库失败仅 continue，违背显式失败原则**
VaelinLauncher.kt:231-240：`required` 10 库中任何一库文件缺失或 dlopen 失败，仅 `VLog.error` 后 continue，最终 `launchJVM` 照常执行。libjli/libjvm 缺失时 JVM 必崩，但崩溃点在 native 层深处（jre_launcher.c），崩溃现场远离真实原因（库没装上/解压失败），日志只有一行 `dlopen 失败` ——与 T1.12 渲染器链路「全候选失败抛 RendererLoadException」的标准不一致。
**修法**：required 列表逐个检查，失败即抛（异常里带缺失库路径列表）；「递归 dlopen 其余 .so」可维持容错。另注：若 JRE tar.xz 解包产物不全，此修复会把「静默崩」变成「显式崩」，属于把问题暴露出来，不是引入新问题。

**M2（必须修）：JVM 启动链上 4 处「失败仅记日志继续」**
- `applyEnv` Os.setenv 失败仅 VLog.error（VaelinLauncher.kt:199-204）——setenv 失败意味着 LD_LIBRARY_PATH/POJAVEXEC_EGL 等关键环境变量没生效，JVM 大概率起不来或行为异常，应区分键：核心键失败抛异常，次要键告警。
- `dlopenEngine` libopenal 失败仅记日志（:253-257）——声音失效是可降级，但至少应在 GameLaunchContract 的诊断事件里上报为显式降级事件（T1.13 FailureClassifier 目前无此分类）。
- chdir 失败仅记日志（:151-154）——ZL2 同样容错，可接受但建议留显式降级标记。
- ensureResolvConf 失败不阻断（:275-284）——可接受（纯联网降级）。

**M3（必须修）：FileDownloader 无校验值时 `isComplete` 返回 true（弱校验）**
FileDownloader.kt:142-148：无 sha1 且无 size 时「本地存在即信」，违反团队准则「禁止把『本地有文件』当作『下载完成』」。R1 下载链的调用方（VersionDownloadPlanner）大多带 expectedSha1，但任何走到无校验分支的资产（如第三方库 json 缺校验字段）会静默接受损坏文件。**修法**：无校验值时返回 false 或抛异常并让上层显式决定，配合日志留痕。

### 2.3 澄清：lead 问题里的两个名实对照

- lead 提的 `progressFinalUserArgs` 是 ZL2 的名字；Vaelin 侧对应物是 `purgeUserArguments`（VLaunchArgs.kt:254/:347），语义对齐、名字不同，**无问题**。
- lead 问的 InstallerJvmScheduler.DEFAULT_RUNTIME_CHAIN = Internal-8/17/21（InstallerJvmScheduler.kt:55-56）与 GameJVMRunner.INSTALLER_RUNTIME_CHAIN = [jre-17, jre-21]（GameJVMRunner.kt:37）确实不一致，但 GameJVMRunner 注释已说明原因（assets 无 jre-8）并显式传入覆盖链，**语义自洽**；建议（S1）把 DEFAULT_RUNTIME_CHAIN 改成空链或加注释防止误用，纯代码层不阻断。

---

## 3 Q2 测试覆盖

### 3.1 覆盖得好（关键路径有锁定）

- **VLaunchArgsTest.kt:94-123**：10 段 LD_LIBRARY_PATH 用整串比对锁死——这是最容易被"顺手改"破坏的契约，测法正确。
- **InstallerJvmSchedulerTest.kt:52-145**：五个必测场景齐全（正常退出/socket 不绑/超时/进程被杀/并发互斥），Fake 时钟设计让 15 分钟超时毫秒级走完，状态机测试质量高。
- **NativeInputTest.kt**：正常/边界/钳位/NaN/零尺寸 15 个，覆盖了编码与换算的全部纯函数路径。
- 下载链：sha1 校验失败删文件、416 分片失效、206 append、200 整份重写均有测试。

### 3.2 盲区（JVM 单测 vs 真机走不同路径）

1. **dlopen/Os.setenv/chdir 零测试**：这三者是 VaelinLauncher 六步链的 2/3/4 步，全在真机才执行；单测覆盖为 0（也无法覆盖）。M1/M2 的修复必须带「真机验证：故意删一个库看是否显式崩」的验收项。
2. **UDP 跨进程协议只有单侧测试**：JVMSocketServer.awaitEvent 用 FakeEventSource 测（InstallerJvmSchedulerTest），JvmService.sendCode 发送侧无真实 socket 测试；两端字面量对齐（127.0.0.1:端口）靠人工约定，**无编译期或测试期保障**。建议（S2）：把端口与消息前缀抽成共享常量对象，两端引用同一处，消除字面量漂移风险。
3. **renderers 三个 Pojav 薄实现**（PojavLTWRenderer 等）的 getRendererEnv/getDlopenLibrary 是 Lazy 包装，测试未覆盖 Lazy 求值时机（R1 曾记录 ZL2 有这个签名坑）。
4. **204 个测试全部绿 = 全部跑在 JVM 上**；「SKIPPED=0」意味着没有测试被条件跳过，但也没有 Android 环境测试（无 Robolectric）。R1 没有声明 Robolectric，属设计取舍，不算缺失；但 M1/M2/M3 修复后的真机验收不可省。

### 3.3 次要发现

- 3 处编译警告（AssetCleanupTest.kt:32:9、AssetDownloadPlannerTest.kt:33:9/:43:34 nullable receiver 无 safe call）——测试代码，非阻断，建议顺手修。

---

## 4 Q3 进程模型

### 4.1 三进程职责划分：**成立，且与 ZL2 对齐**

【读过】app\AndroidManifest.xml：VMActivity `:game`(:43-51 附近)、GameService `:game`+specialUse、GameCrashActivity/GameCrashReceiver 无 process 属性 → 主进程；【读过】startup-core\AndroidManifest.xml:16-25：JvmService `:jvm`+specialUse+PROPERTY（会正确合并进 app）。三进程与 ZL2 的 :game/:jvm/主进程完全同构。

**GameCrashActivity/GameCrashReceiver 留主进程：成立**。理由链：
1. :game 崩了进程就没了，崩溃提示 UI 必须活在活着的进程里——只能主进程；
2. GameCrashWatchdog 用显式定向广播（setClassName，GameCrashWatchdog.kt:100-111），exported=false 仍可送达（同 UID 同包名）；
3. GameCrashReceiver.onReceive 里补 VPaths/VLog 初始化（:39-40，处理主进程被回收后冷启动）+ FLAG_ACTIVITY_NEW_TASK 起 GameCrashActivity（:47-52）——receiver 启动 Activity 必须这个 flag，写对了；
4. 崩溃广播后主进程被系统拉起分发——BroadcastReceiver 显式广播能唤醒已死进程，这是标准模式。

### 4.2 模块页面跑主进程（本快照未交付，按 R1 架构推断）

架构文档（round3 交付时的 docs/04 §5 R1 任务）里模块页面属 R2/R3。当前 R1 无模块代码，此决策尚未被代码固化。**结构性风险**（现在说，R2 定架构时用）：
- 主进程跑模块页面 = DexClassLoader 加载的第三方代码与 launcher 全部状态同进程，L1–L5 隔离只剩「进程内隔离」（独立 classloader + E5 双异常隔离），进程崩溃兜底失效——一个模块 OOM 直接带走整个 launcher UI；
- 对照方案 B（独立 :module 进程）代价是 AIDL/信使 + 子进程 Compose 宿主；
- **建议 R1 收尾时把决策写进 docs**：R2 首版模块若走主进程，必须显式接受「模块崩溃连主进程」的取舍并写入文档，不许写成「隔离已覆盖」。

### 4.3 崩溃链路的一个真实缺口（S3，建议修）

GameCrashWatchdog.kt:30-31 注释明确 SIGKILL 不覆盖（诚实标注，好）。但补一点：**:game 被 SIGKILL 时 GameService.onDestroy 可能不执行**（进程直接死），notifyUnexpectedExit 也不触发 → 主进程收不到任何通知 → 用户看到的是永久黑屏。ZL2 的应对是 GameService 存活心跳：主进程定时检查 `:game` pid 存活（ActivityManager.getRunningAppProcesses 或 /proc 扫描）。R1 可不做，但 **T1.13 的「不静默黑屏」目标在 SIGKILL 场景下尚未达成**，建议在 docs 里记为已知限制或 R2 任务。

---

## 5 Q4 验收证据链

### 5.1 证据逐条核验

| lead 引用 | 我的独立复验 | 判定 |
|---|---|---|
| SUITES=27 TESTS=204 SKIPPED=0 FAILURES=0 ERRORS=0 | ✅ 一致（我用同一正则独立统计 27 份 XML） | 成立 |
| APK 62,837,511 字节 / 295 条目 / assets 24 项 49,219,294 字节 | ✅ 逐项一致（ZipFile 读取） | 成立 |
| 四个 tar.xz 全 Stored | ✅ 4 个 tar.xz 存在且 xz 魔数（`7zXZ`/FD 37 7A 58 5A）实测存在；**但「Stored」压缩方式我没验证 zip method 字段**（PowerShell ZipFile 不直接暴露），未证实 | 部分成立（tar.xz 在包内+魔数对；Stored 说法未证实） |
| GRADLE_EXIT=0 / BUILD SUCCESSFUL in 1m 22s / 83 actionable tasks: 83 executed | ❌ **工程内 7 份 build-*.log 全部无此字样**。build-launch-final.log 实际记录是 "BUILD SUCCESSFUL in 1m 53s / 17 actionable tasks: 5 executed, 12 up-to-date"；test-final 是 17 tasks 2 executed。**83/1m22s/GRADLE_EXIT=0 在工程内找不到出处** | **不成立（作为工程内证据）** |
| 日志与产物时间一致性 | APK mtime=11-06 22:44:40，测试 XML mtime=22:44:41——同分钟，同轮构建；但工程日志 mtime 是 15:19，早于产物 7 小时 → **最终那轮构建的日志没有落盘到工程** | 缺口 |

### 5.2 判定：lead 把推断当观察了吗？

**部分是**。27/204/0 的测试统计、APK 字节数与条目数是真实观察（与我复验一致）；但：
- "83 actionable tasks: 83 executed"——工程内无任何日志支持。可能是 lead 在别处（终端未保存）看到的，但作为**验收证据**它不可复核，等同不存在；
- "GRADLE_EXIT=0" 同理，工程内无出处；
- "BUILD SUCCESSFUL in 1m 22s" 与工程内日志的 1m 53s 矛盾；
- 四个 tar.xz "全 Stored"——tar.xz 若真被压缩，JRE 解包时无损，问题不大；但这是 lead 的**转述**还是他实测的 zip method？若是转述则又是推断当观察。

**要求**：lead 补一次可复核的干净构建：`--rerun-tasks` 全量跑，把完整 stdout+exit code 落盘到工程（如 `03-项目工程\build-verification-<日期>.log`），给出 task 总数与耗时。在这之前，"R1 已交付部分通过" 的构建侧证据只有我复验的产物层事实（APK 存在且内容完整、测试 XML 全绿），**没有**全量构建通过的可复核记录。

### 5.3 补充

- APK 与测试 XML 同分钟（22:44:40/41）支持「同轮构建」推断，但**没有人能证明那轮构建是从当前源码起的干净构建**（日志缺失）。此为证据链的根本缺口。
- 编译警告 3 处已列（3.3），非阻断。

---

## 6 结论

**判定：pass with conditions。**

必须修（进入 T1.11/T1.12 收尾前）：
- **M1** VaelinLauncher.kt:231-240 dlopenJavaRuntime 10 库失败 continue → 改抛异常（带缺失清单）
- **M2** applyEnv/dlopenEngine 关键失败静默 → 区分核心键抛异常/次要键显式降级事件
- **M3** FileDownloader.kt:142-148 无校验值 isComplete=true → 显式报错

建议修：S1 DEFAULT_RUNTIME_CHAIN 加防误用注释；S2 UDP 端口/前缀抽共享常量；S3 SIGKILL 黑屏缺口写入已知限制；S4 3 处测试编译警告。

**lead 需要补的证据**（Q4）：
1. 一次 `--rerun-tasks` 干净全量构建，完整日志落盘工程内（含 exit code）；
2. 说明 "83 tasks / 1m22s / GRADLE_EXIT=0" 的原始出处（终端截屏/另一日志），或撤回该证据；
3. "tar.xz 全 Stored" 的验证方式说明。

【未验证】汇总：JNI 符号真实绑定、dlopen/setenv 真机行为、UDP 跨进程真实到达、tar.xz 解包后 JRE 内部结构完整性、SIGKILL 场景表现——均需真机，R1 交付声明里不应含这些项的"已验证"表述。
