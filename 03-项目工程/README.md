# project/ — 本项目源码

**本轮为空，这是有意的。**

本轮的交付边界是「方案与环境就绪」：确认 59 条里哪些能完整实现、写出实现方案、备好参考源码与构建链、建立副审交叉验证。不写业务代码。

下一轮开工时，这里会按 `docs/02` §8 的 P0 顺序建立：

```
project/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml
├── app/            宿主 App（Compose UI、导航容器、模块管理器）
├── lab-api/        模块契约库（纯 Kotlin + Compose 运行时，compileOnly 分发）
├── core/           启动器核心（实例、下载、地区闸、索引 DB）
├── runtime/        启动底座（JRE、图形翻译层、输入注入）—— 取决于 §9.1 裁决
└── native/         CMake 工程（cubiomes、Nucleation）
```

P0 阶段的第一批任务不是功能，是五组实验（E1–E5）和两处机制（地区闸权限流程、Android 14 dex 只读约束）。它们决定模块系统这个「项目心脏」到底成不成立，必须在写任何模块功能代码之前跑完。详见 `docs/02` §4.6 与 §6.1。
