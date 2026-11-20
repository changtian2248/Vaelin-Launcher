# Vaelin Launcher Round2 独立复核（A–D 四问）

- 作者：vice-reviewer，日期：2026-11-05
- 性质：只读复核。未改 docs\，未改源码；对 lead 申报的实测产物做了落盘验证（大小、mtime、build 脚本、ELF 头逐字节核对）。
- 写作范围：仅本文件（review\vice\round2-assessment.md）。

---

## 0. 先交代一个影响 C 问的基准修正

我 round1-assessment.md 的统计行"27✅/27⚠️"是**计数错误**。重数逐条表，实际为 **39✅/20⚠️**。这个错误还连带出 01 文档 5.3（01:191-192）的一个不实描述——"副审把 13、14、18、19、20、21 判为 ⚠️"：**我 round1 对这六条全部判 ✅**，round1 §二 §3 表逐行可查。lead 的 38/21 与我修正后的 39/20 的真实差集只有 1 条（条目 6），详见 C 问。

---

## 实测证据复核（C 问的事实底座）

| lead 申报 | 我核验 | 结论 |
|---|---|---|
| app-debug.apk 11,514,152 字节 | `Get-Item` 实测 11,514,152 bytes，mtime 16:58 | **属实** |
| libvaelin_cubiomes.so 843.5 KB ELF64/AArch64/DYN | 实测 863,792 bytes（843.5 KB✅）；ELF 头逐字节 `7F 45 4C 46 02 01 01 00 … 03 00 B7 00`：e_type=ET_DYN(3)、e_machine=EM_AArch64(0xB7) | **属实** |
| AGP 9.0+ 内置 Kotlin，apply kotlin.android 失败 | probe 的 app/build.gradle.kts 只有 `com.android.application` + `org.jetbrains.kotlin.plugin.compose`，Kotlin 经 AGP 内置路径（`kotlin { jvmToolchain(21) }`），无 kotlin.android 插件且构建成功 | **属实，且与 research §1:22（内置 Kotlin 需 KGP 2.4.x）自洽** |
| BOM 2026.09.00 强制 compileSdk≥37 | probe compileSdk=37 + build-tools 37.0.0 构建通过；CMakeCache 确认 cmake 3.31.6、NDK 27.3.13750724、arm64-v8a | **属实** |
| 版本矩阵 | AGP 9.5.0 / Gradle 9.8.2（`.gradle/9.8.2` 目录佐证）/ Kotlin 2.4.21 / BOM 2026.09.00 / compileSdk 37 / targetSdk 36 / minSdk 26 | **assembleDebug 成功 = 第一关已过** |

两条 lead 未提的核对备注：
1. CMakeCache 里 `ANDROID_PLATFORM=android-24`，低于 minSdk 26——合规（so 的 min API ≤ 应用 minSdk 即可），但正式工程建议统一到 26，避免无谓的 API 可用性疑问。
2. probe 的 `settings.gradle.kts` 用了 `RepositoriesMode.FAIL_ON_PROJECT_REPOS` + google()/mavenCentral() 直连且构建成功——说明本机对这些仓库的直连可用（至少构建时），这对后续依赖拉取是个正面信号；但 01:204 的 gh-proxy 疑问仍然只对"大仓库 git clone"成立，不影响 maven 依赖。

**对 round2-review Q4 的更新**：我此前判版本矩阵"中高风险、需 P0 第一天实测"。现在实测已通过，风险降级为"低"：KGP 兼容表超维（AGP 9.5.0/Gradle 9.8.2 超 KGP 2.4.21 表）在最小工程上无阻塞性问题。回退组顺序相应更新为：9.5.0 定案 → 若全工程（而非 probe）遇到 KGP/Compose 编译器问题才回退 9.3.1+Gradle 9.7.0 → 内置 Kotlin 路线调整 → 8.x 线（保留但基本不用）。03:66 的回退组写 AGP 8.13.2 仍嫌过深，但既然 9.5.0 已实测，回退组的精度不再是当前风险。

---

## A. 底座三选项与第 6 条

**判定：同意 lead——第 6 条应从 ✅ 改判 ⚠️，我正式改判；选 A 方案本身有条件成立。**

1. **第 6 条改判理由**（补充 lead 在 01:40 之外的一条）：规格书第 5 行白纸黑字："参考项目仅作参考……不 Fork 他人仓库、不以他人 APK 为基底"，点名列表含 PojavLauncher/FCL/Zalith。02 选项 A 是**直接依赖（链接）**这三家的 runtime 组件——链接使用无论如何解释都不是"仅作参考"。第 6 条"从零构建"与第 5 行的组合，在选 A 时必有一处被重释。lead 的重释（"自研产品形态/界面/模块系统/世界工具，不以他人 APK 为基底"）在工程上说得通，但这是**用户级的规格变更**，不是技术裁决。
2. **GPL-3.0 传染的代价内部自洽**：模块生态已被规格书 6.2 节锁死 GPL-3.0（AI 生成模块强制 `license: GPL-3.0`、模块模板 GPL），宿主整体 GPL-3.0 与模块世界一致；research §6:117-119 证实 Pojav 系三家 LICENSE 均 GPL-3.0，链接合规。**整案 GPL-3.0 是选项 A 的必然代价，且这个代价与规格书自身条款不矛盾。**
3. **lead 在 02:74-77 的 cubiomes 类比不成立，建议撤**：cubiomes/schematic4j/Nucleation 是第 5 行**点名允许**"源码引入"的项目，拿它们论证"链接 Pojav runtime 也行"是把两 类许可待遇不同的引入方式混为一谈。正确写法是把"第 5 行'仅作参考'是否允许链接依赖"作为独立裁决问题原文呈报给用户，不预设答案。
4. **B/C 的判定**：B 自研 JRE+图形翻译层不可达——同意，round1 P-2 已论证；C 放弃启动等于砍掉第 1 条核心，仅作为用户明确拒绝 GPL 时的最终选项。另有一个 lead 未列的弱备选：GPL runtime 做成独立 APK、宿主经 IPC 调用（规避 combined work）——但 MC 渲染/输入是单进程紧密循环，Android 跨进程跑游戏基本不可行（未验证，强怀疑死路），列出仅为完整性，不建议投入。

---

## B. 模块系统 R2 硬约束：最小验证工程设计

前提：不改规格书（7+13+14+17 条要求共享宿主 runtime 的插件模型）。两个未知数——**dex 只读**（Android 14+ targetSdk≥34 禁止对动态加载 dex 持可写权限，research §2:38）与 **Compose runtime 跨 dex 共享无公开先例**（research §2:44-46）。建议在现有 `build-check\` 旁建 `module-spike\`，一宿主一模组，五组实验，全部可在**一台 Android 14+ 真机/模拟器**上跑完：

**E1 dex 权限基线（判定 R2 是否真拦）**
- 宿主 targetSdk=36，把模块 dex 解压到 `context.codeCacheDir`，保持文件可写 → `DexClassLoader.loadClass` → 预期失败（`Opening an oat file ... writable` 类错误）。这是确认约束真实生效的阴性对照。
- 同一文件先置只读（Java 无 chmod，经 `Os.chmod(path, 0444)` 或安装期流程保证）→ 再加载 → 预期成功。**通过判据：同一 dex 仅权限不同，加载结果不同。**

**E2 Compose runtime 共享主实验（判定插件模型生死）**
- 宿主打进 Compose UI/Material3/runtime（与正式矩阵同版本：BOM 2026.09.00）；模块 dex 只含一个 `@Composable fun`（用 `org.jetbrains.kotlin.plugin.compose` 编译，**不带** compose 依赖、compileOnly 声明即可）。
- 宿主 `DexClassLoader(dexPath, null, null, hostClassLoader)`，反射取出 Composable，经 `setContent` 渲染。**通过判据：无 NoClassDefFoundError / Compose 版本协议错乱，正常显示。**

**E3 类空间与委派（判定 parent 遮蔽边界，接 round1 P 条）**
- 模块内放一个与宿主**同名类**（如 `androidx.compose.runtime.Fake` 或 lab-api 同名类）→ 验证 parent-first 下宿主类胜出、模块同包新类正常加载。这是 02:108 加载链路的直接验证，也顺带验证 lab-api 桩接入。

**E4 版本漂移矩阵（判定生态可持续性，接 round2-review Q5-2）**
- 模块分别用 BOM 2026.09.00 与相邻一个小版本（如 2026.08.x）编译 → 各自加载进同宿主。**通过判据决定治理策略**：都通过 → apiVersion 语义可以宽松；旧版失败 → module.json 必须增加 Compose 编译器/kotlinx metadata 兼容声明，且这条要写进 23 条 CI 的检查项（现有"无 androidx/compose/ 类 + apiVersion 匹配"覆盖不到编译器协议版本）。

**E5 崩溃隔离小样（接 round1 L2）**
- 模块 Composable 在重组期抛异常 + 模块内起协程抛异常，宿主侧分别验证 `Recomposer` 冒泡路径与 `CoroutineExceptionHandler` 路径都能被兜住并只卸载该模块。这一组同时是 15 条 L2 的验收原型。

**工作量估计**：宿主复用 probe 工程加 200 行内，模块一个极简 Composable，五组实验合计 1–2 天。**判据输出一张通过/失败矩阵**：E1+E2 双绿 = 插件模型通，R2 移出风险清单；E1 绿 E2 红 = dex 管线通但 Compose 共享死，需回规格书谈第 7 条裁剪；E1 红 = 整条模块路线需重新设计（概率低，research 证据明确）。**建议 P0 第一周内做，先于任何模块功能代码。**

---

## C. 38✅/21⚠️ vs 27✅/27⚠️：差集逐条

先给硬结论：**修正我的统计错误后，双方分歧只剩 1 条**。

- 我 round1 实际 ⚠️ 清单（20 条）：3,4,8,12,15,17,23,29,30,31,32,34,35,37,38,39,42,46,49,53。
- lead 的 ⚠️ 清单（21 条，从 01 文档逐行提取）：**与上面 20 条逐条相同，外加条目 6**。
- 即：lead 的 38✅/21⚠️ 与我修正后的 39✅/20⚠️ 之间，唯一分类差异是条目 6。01:191 "副审把 13、14、18、19、20、21 判 ⚠️"不成立（我全判 ✅）；"这是口径差异不是事实分歧"这句反而说反了——是**计数错误**，不是口径差异。

逐条裁决：
- **条目 6（从零构建）**：lead ⚠️ 正确，我改判 ⚠️。理由见 A 问。改判后双方 **38✅/21⚠️ 完全一致**。
- 顺带指出 lead ✅ 里两处我认为**过宽**（不影响计数，影响验收口径）：
  - **条目 51（种子地图即首版）**：lead 依据自己写"前提是启动底座可用"（01:120）却给 ✅，口径与条目 6 不一致；且 01 §3.1 依赖底座清单（01:139-146）没列 51。应转条件性 ⚠️ 或补进清单。
  - **条目 33（皮肤编辑器）**：双方都 ✅，但"正版上传"（规格书 57 行）需要 Microsoft OAuth → Xbox Live → XSTS → Minecraft Services 整条认证链，规格书通篇无账号条目。建议 ⚠️ 或在验收口径里显式声明"上传功能降级/后置"。

---

## D. 四个待裁决项的优先序

02 §9 四项：底座边界、GPL 许可、索引托管、工程目录。**建议合并前两项为一次用户裁决，且排第一**：

1. **底座 + GPL（合并裁决，第一优先）**：它们不是两个问题——选 A 即整体 GPL-3.0，选 B/C 则 GPL 问题消失。拆成两问会让用户对同一个选择回答两次。裁决内容呈报三点：① 第 5 行"仅作参考"是否允许链接依赖上游 runtime（第 6 条随之重释，措辞用 A 问第 1 条的重释句，不带辩护）；② 整案 GPL-3.0 是否接受（需如实告知：这会波及后续任何闭源诉求，且模块生态本已锁 GPL，实际损失小于直觉）；③ 裁决前 lead 不应基于选 A 写任何上游集成代码——**Q2 物质前提未落盘（Pojav/FCL/Zalith/EasyTier 四仓库 03:153-159 仍全部"⏳ 下载中"、gh-proxy 40-107 KB/s、git clone 未验证），纸面裁决换不来 P1 的开工条件**，这是它排第一的真正原因：它是 P0→P1 的关键路径闸门。
2. **索引托管（第二）**：影响条目 17/53 与国内可达性，是独立于底座的技术+运营混合决策。可以给用户的建议选项：GitHub Pages 主站 + 国内镜像（jsDelivr/Gitee）双源、索引 JSON 带签名防篡改。不阻塞 P0。
3. **工程目录（第三）**：纯 lead 技术决策，甚至不需要用户裁决——从 02 §7 的 P0-P6 分期看目录结构已隐含，直接定案写进 03 即可。放在裁决列表里反而稀释前两项的严肃性。

排序逻辑一句话：**第 1 项决定项目是否成立，第 2 项决定发布形态，第 3 项只是整理桌面。**

---

## 结论速览

| 问题 | 结论 |
|---|---|
| 实测复核 | 五项申报全部属实（APK 字节数、ELF 头 0xB7/ET_DYN、内置 Kotlin、compileSdk≥37、版本矩阵）；版本矩阵风险降级为低 |
| A | 同意第 6 条改 ⚠️（我已改判）；A 有条件成立，cubiomes 类比建议撤回，"仅作参考 vs 链接依赖"交用户裁决 |
| B | 五组实验（E1 dex 权限 / E2 Compose 共享 / E3 委派遮蔽 / E4 版本漂移 / E5 崩溃隔离），1–2 天，判据矩阵可判定插件模型生死，建议 P0 第一周 |
| C | 修正计数后双方 38/21 完全一致，唯一分歧条目 6 已收敛；lead 的 ✅ 中 51、33 两处过宽 |
| D | 底座+GPL 合并裁决排第一（关键路径闸门且物质前提未落盘），索引托管第二，工程目录降级为 lead 自决 |
