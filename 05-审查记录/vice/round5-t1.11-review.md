# round5 副审报告：T1.11 启动链（LaunchCoordinator + app 接线）

- 审查人：vice-reviewer（副审）
- 日期：2026-11-07
- 方式：只读静态审查，全程未运行构建、未改动任何被审文件（仅新建本报告）
- 判定：**pass**（附 1 项 M 级发现与 4 项 S 级发现，均不阻塞 T1.11 验收，建议进 R2 修）
- 证据标注约定：【读过】= 本轮逐行读过源文件；【推断】= 由已读代码逻辑推出；【未验证】= 无法静态确认

---

## ① 跨进程契约自洽性 —— 通过

三处 identifier 逐点核对（全部【读过】）：

1. **写入点**：`startup-core/src/main/java/com/vaelin/startup/launch/LaunchCoordinator.kt:243`
   `val file = planFile(gameDir, profile.id)` —— 用的是 `profile.id`，不是 `minecraftVersion` 也不是 `name`。`gameDir` 默认 `VPaths.gameDir`（:181）。
2. **Intent 传递点**：`app/src/main/java/com/vaelin/launcher/game/GameLaunchContract.kt:53`
   `putExtra(EXTRA_VERSION_ID, profile.id)` —— 同样是 `profile.id`。
3. **读取点**：`app/src/main/java/com/vaelin/launcher/game/VMActivity.kt:254-259` 把 `params.versionId` 传给 `LaunchCoordinator.launchFromGameProcess`，后者 `LaunchCoordinator.kt:294` `planFile(VPaths.gameDir, versionId)`。

三处同源：`LaunchPlan.build` 里 `versionId = profile.id`（`LaunchPlan.kt:77`）。全链路没有任何一处用 `minecraftVersion` 或 `name` 做文件名/键。注意区分：`plan.versionId`（= profile.id，实例标识）与游戏参数 `${version_name}`（= Mojang version json 的 `version.id`，`LaunchCoordinator.kt:216`）是两个不同语义的字段，各自用法正确，不混淆。

目录一致性【读过】：两个进程各自 `VPaths.init(applicationContext)`（`VMActivity.kt:92`、`MainActivity.kt:39`），指向同一 gameDir；写文件前 `parentFile?.mkdirs() ?: throw`（`LaunchCoordinator.kt:244-245`）。

### Base64 `key=value` 编码边界 —— 不损坏（结构证明 + 测试覆盖）

【读过】`LaunchPlan.kt:130-180` + `LaunchPlanTest.kt`：

- **含 `=`**：`decode` 用 `line.indexOf('=')` 取**第一个** `=` 分割（`LaunchPlan.kt:176-179`）。key 是硬编码常量不含 `=`；value 是标准字母表 Base64，其中 `=` 只作为 padding 出现在**末尾**，属于 Base64 合法字符，`dec` 原样解码。round-trip 测试直接覆盖：`gameArgs` 含 `"参数=含等号"`、`extraJvmArgs` 含 `"-Dx=1"`、`"-Dy=中文 值"`（`LaunchPlanTest.kt:34/:52`）。
- **含空格/中文**：UTF-8 字节进 Base64，round-trip 测试含中文实例名"我的世界"、中文参数（`LaunchPlanTest.kt:27/:52-53`）。
- **含换行**：值里的换行被编码进 Base64（标准 encoder 输出不含换行符），按行切分不会撕裂；行尾 `\r` 由 `trimEnd('\r')` 处理（`LaunchPlan.kt:174`），兼容 Windows 写盘。换行值本身无直接测试，但编码输出无换行是 `java.util.Base64.getEncoder()` 的确定行为【推断，结构可证】。
- **classpath 多条**：重复 key 保序（encode :162/:165/:167 逐元素 put；decode :180 `getOrPut(key){ArrayList()}.add(value)`），`classpathFiles()`（:66）按序还原。

结论：lead 最担心的四类字符全部安全，编码器选型（标准字母表、无 wrap）与首 `=` 分割规则互相咬合。

---

## ② GameArgumentExpander 规则匹配 —— 通过

【读过】`GameArgumentExpander.kt:87-158`：

- **last-match-wins 属实**：`argumentRuleAllows`(:122-132) 非空 rules 初始 `allowed=false`，按序遍历，每条命中即用该条 action 覆盖，最后一条命中者决定——与 `LibraryRules.isAllowed`（`LibraryRules.kt:76`，文档注释明确 last-match-wins 语义）同构，不是"命中即返回"。专项测试双向覆盖：disallow→allow 保留 `--kept`、allow→disallow 丢弃 `--dropped`（`GameArgumentExpanderTest.kt:68-77`）。
- **混合元素**：Str 直接加（:93-95）；Obj 无 rules 时直接判 value（:98）；value 支持 Str 与 Arr（:99-105）；非 Obj 非跳过。测试覆盖混合数组（`GameArgumentExpanderTest.kt:50-65`）。
- **短路逻辑**：`ruleMatches`(:134-158) 任一条件不匹配 return false（整条规则不适用，不是"整组禁用"），语义正确；os.name/osArch `equals ignoreCase`，osVersion 无 regex 才用 `containsMatchIn` 且 environment.osVersion 为空时直接 false，features 逐键 `expected==actual` 缺省 false。
- **与 ClasspathBuilder 同一环境**【读过】：`LaunchCoordinator.prepare:189` 创建单一 `RuleEnvironment.android(abi)`，:203-208 传 `ClasspathBuilder`、:222 传 `GameArgumentExpander`——同一个实例，os.name/arch/feature 的**输入**必然一致，不存在两套判定输入。

**S1（两套求值实现的归一化差异，实际影响小）**：`LibraryRules.matches` 的 osArch 比较**先过 `NativeClassifiers.normalizeArch` 归一化**（`LibraryRules.kt:47-49`），而 `GameArgumentExpander.ruleMatches` 的 osArch 是 `equals ignoreCase` 裸比较（:139-140）。若某 version json 的 game arguments 规则写 `{"arch":"amd64"}` 而环境是 `mojangArch("arm64-v8a")` 的产物，库规则会归一化命中、参数规则不命中，两边结论可能分叉。缓解因素：arguments.game 里带 `os.arch` 的规则在真实 Mojang version json 里几乎不存在，且两边输入同源。建议 R2 统一走同一个 `Rule.matches` 扩展或抽公共函数。

---

## ③ 失败语义 —— 四处全部真抛，M1/M2/M3 修复确认，未引入新死路径

【读过】逐点：

1. **selectJre Java<17 真抛**：`LaunchCoordinator.kt:159-165`，`else -> throw LaunchException("Java $major 不受支持：assets 只提供 jre-17 / jre-21（禁止静默回退到默认运行时）")`；majorVersion 缺失也抛（:155-158）。测试五分支（`LaunchCoordinatorTest.kt:178-192`）。
2. **下载非全成功真抛**：版本文件 `:114-119`、资源文件 `:141-146`，均带失败清单；manifest 找不到版本抛"禁止回退到 latest"（:90-93）；assetIndex 下载后缺失抛（:122-127）。测试覆盖（`LaunchCoordinatorTest.kt:151-175`）。
3. **plan 不存在真抛**：`LaunchCoordinator.kt:295-297` `throw LaunchException("启动计划不存在：$file（主进程 prepare 未成功或已完成？拒绝静默回退）")`。
4. **非法 Base64 真抛**：`LaunchPlan.kt:133-137` `dec` 捕获 IllegalArgumentException 转 `LaunchException`；另有缺必需字段/格式版本不匹配/行结构非法三类显式校验（:176-198），测试各一条（`LaunchPlanTest.kt:109-122`）。

### round4 M1/M2/M3 修复确认（【读过】修复后代码）

- **M1（dlopen 静默 continue）已修**：`VaelinLauncher.kt:239-259`，10 个必需库缺失/dlopen 失败收集进 `failures`，非空抛 `IllegalStateException` 带完整清单，不再 continue 到 launchJVM。
- **M2（setenv/openal 吞错）已修**：`applyEnv`(:199-213) 收集全部 setenv 失败非空抛；`dlopenEngine`(:271-282) libopenal 缺失抛、dlopen 失败抛。
- **M3（isComplete 弱校验）已修**：`FileDownloader.kt:142-153`，既无 expectedSha1 也无 expectedSize 时 `throw IllegalArgumentException`，不再返回 true。
- **递归 dlopen 其余 .so 失败仅 debug 日志**（`VaelinLauncher.kt:263-267`）：非必需库，可接受；chdir 失败仅记日志（:151-154）、resolv.conf 写失败不阻断（:300-309）：维持 round4 判断，均为合理降级且注释说明理由，不算静默劣化。
- **新死路径检查**【推断】：M1 的隐含前提是打包的 JRE tar.xz 里确实含全部 10 个 so——tar.xz 在 APK 内已确认存在（round4 魔数核验），但**未解包核对 10 个 so 是否齐全**，见未验证清单。M3 的新抛错只影响"既无 sha1 又无 size"的畸形请求，VersionDownloadPlanner/AssetDownloadPlanner 的请求均带校验值，正常链路不可达。M2 的 setenv 全抛只在系统级异常时触发，键值均为路径/指针，无 `=`/`\0` 注入面。**均未发现新死路径**。

---

## ④ 线程模型 —— 基本正确，1 项 M 级 + 1 项 S 级

【读过】`LauncherViewModel.kt`：

- `preparing` 是 **`@Volatile private var`**（:60-61）——lead 问的"普通 Boolean"实际已加 `@Volatile`，可见性无问题。`launch()` 的 check-then-act（:117-118）只发生在主线程（UI 点击入口），无竞态。
- prepareAsync 三回调 `onProgress`/`onSuccess`/`onError` **全部** `mainHandler.post` 包裹（:125/:135/:147），Compose state 只在主线程写。`onSuccess` 里 `GameLauncher.start` 也在 post 内（:143）。
- 后台侧：`prepareAsync`（`LaunchCoordinator.kt:272-281`）Thread 内 try/catch 全覆盖，不吞异常；`ProgressReporter.stage` @Synchronized（`LaunchModels.kt:48-54`）串行化并发下载回调。

【读过】`VMActivity.kt`：

- **Surface 时序**：onCreate 只建 TextureView 不启动（:152）；`onSurfaceTextureAvailable`(:157-185) 主线程回调，首次 `isRunning=true` 后起 Thread → `awaitScreenSize` → `mainHandler.post { refreshWindowSize → execute }`；重建分支走 `setupBridgeWindow` 复用。`onSurfaceTextureDestroyed` 释放 Surface 置 `isSurfaceDestroyed`（:194-201），尺寸回调对销毁态短路（:189）。
- **JVM 线程收尾**：`execute`(:253-265) 另起 `vaelin-jvm-*` 线程跑 `launchFromGameProcess`（内部 `runBlocking(Dispatchers.IO)` 阻塞到游戏结束），退出后 `mainHandler.post { finish() }`。`launchFromGameProcess` 抛 LaunchException（plan 缺失/解码失败）会作为未捕获异常交 `GameCrashWatchdog`（onCreate :94 已 install），广播崩溃提示——符合显式失败设计。
- **泄漏/黑屏评估**：jvm 线程持有 Activity 引用直到 JVM 退出——这是游戏宿主的既定生命周期，不算泄漏；Activity finish 后 post 的 `finish()` 是无害 no-op。`isRunning`/`isSurfaceDestroyed`/`gameWidth` 只在主线程读写（Surface 回调与 touch 都在主线程），无需 volatile。

**M1（本轮唯一 M 级）：JVM 非 0 退出码被当作正常退出，用户无感知**。
`VMActivity.kt:260-261`：`VLog.info(TAG, "游戏退出，JVM exitCode=$exitCode")` 后**无条件** `mainHandler.post { finish() }`。exitCode != 0（MC 崩溃但 JVM 正常退出）时：finish → onDestroy(:337-345) → `GameCrashWatchdog.markNormalExit()` → GameService.onDestroy 不发崩溃广播 → **主进程没有任何崩溃提示，用户被静默弹回启动器**。对照：:game 进程被杀（SIGKILL/异常）这条路 watchdog 是覆盖的（round4 已确认），偏偏"JVM 体面退出但游戏失败"这条最常见路径没有反馈。建议 R2：按 exitCode 分支（0 → finish；非 0 → 落崩溃信息并走 GameCrashActivity 通知链），或至少在 UI 留错误状态。不阻塞 T1.11（启动链本身正确，这是退出链缺口），但按"禁止静默"准则应记 P0 级 R2 待办。

**S2**：`awaitScreenSize`(:208-212) 在后台线程读 `gameTextureView?.width/height`——View 尺寸跨线程读取严格说非线程安全；实际值在 onSurfaceTextureAvailable 时已就绪、读 int 无撕裂风险，与 ZL2 同形，可接受，建议注释留痕。

**S3**：Surface 重建分支 `setupBridgeWindow` 失败仅记日志（:167-170）——与 ZL2 一致的刻意取舍，注释已说明，接受。

**S4**：`decode` 对重复出现的单值 key 只取 `firstOrNull`（`LaunchPlan.kt:183`）——若未来 encode 侧误把单值写成多行会被静默取首行；当前 encode 侧单值只写一行（:145-161 逐 put），无实际风险，建议 decode 对单值 key 重复时显式报错（防御性）。

---

## 测试质量（副审独立读盘）

【读过】五个测试文件全文：

- `LaunchCoordinatorTest.kt`（224 行）：FakeHttpTransport+FakeEnvironment 全阶段零网络（:97-131）；**plan 落盘后 decode 还原等值断言**（:120-122，直接锁跨进程契约）；第二次 prepare 全 500 仍成功=已校验文件零网络跳过（:134-149）；阶段顺序按 `LaunchStage.entries` 断言（:117）。设计符合"可单测链路 SOP"。
- `LaunchPlanTest.kt`：round-trip 含中文/等号/空格（:57-68）、null 缺行（:91-107）、三个异常路径（:109-122）。
- `GameArgumentExpanderTest.kt`：混合格式+last-match-wins 双向专项（:50-77）、旧格式引号保留（:80-90）、未知占位符不静默丢弃（:93-97）。
- `ClasspathBuilderTest.kt`：坐标去重计数/排序/client jar 末尾（:48-64）、osx 过滤+natives-only 不进 classpath（:67-75）、无 artifact 无坐标抛（:78-89）。
- `OfflineAccountTest.kt`：UUID v3/v2 特征位、与规范 `nameUUIDFromBytes("OfflinePlayer:"+name)` 一致、确定性（:24-46）。

主审基线引用：`GRADLE_EXIT=0 / BUILD SUCCESSFUL in 59s / SUITES=32 TESTS=228 SKIPPED=0 FAILURES=0 ERRORS=0`；APK 62,960,066 字节。本轮副审未重跑构建（lead 明示不需要、避免抢 gradle），该基线按主审实测采信【未由副审复跑】。

---

## 未验证项（照实记录）

1. **真机全链路**：下载→装 JRE→写 plan→:game 读 plan→dlopen→launchJVM→Surface 渲染→触摸投递，全程未在真机运行过（主审亦未）。JNI 符号真实绑定、UDP、EGL 窗口绑定均未验证。
2. **M1 修复的隐含前提**：APK 内 4 个 tar.xz（jre-17/jre-21 bin-arm64+universal）解包后是否真含 `VaelinLauncher.kt:225-236` 列出的全部 10 个必需 so——未解包核对。若缺任何一个，现在会显式失败（这正确），但等于首启必失败。
3. **exitCode 非 0 的真实表现**（依赖 M1 发现的 R2 修复）。
4. 主审 228 测试基线未由副审复跑。

## 结论

四问全部核对完毕：① 契约三处同用 `profile.id`，Base64 编码对空格/`=`/换行/中文均安全（含测试证据）；② last-match-wins 属实且有双向专项测试，两处规则求值共用同一 environment 实例（唯一差异是 S1 的 arch 归一化）；③ 四处失败语义全部真抛，round4 M1/M2/M3 修复确认且无新死路径；④ 线程模型正确（@Volatile 已加、三回调全切主线程、Surface 时序与收尾合理），唯一实质缺口是 M1 非 0 退出码静默 finish。

**判定：pass**——启动链交付质量达标；M1（退出码反馈）与 S1（arch 归一化统一）、S4（decode 防御）建议列入 R2 待办；未验证项 1/2 建议在首次真机验证时优先覆盖。
