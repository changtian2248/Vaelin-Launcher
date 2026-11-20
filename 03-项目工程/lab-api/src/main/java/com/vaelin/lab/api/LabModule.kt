package com.vaelin.lab.api

/**
 * 模块入口契约（规格书 §2.1）。
 *
 * 生命周期：onLoad → onEnable →（...）→ onDisable → onUnload。
 * 懒加载已锁（规格书第 14 条）：启动时除 `metadata.startup == true` 的模块外，
 * 任何模块的 dex 都不会被读，本接口的实现类也不会被实例化。
 */
interface LabModule {
    val metadata: ModuleMetadata

    /** dex 已加载、entryClass 已实例化后调用。此处注册扩展点。 */
    fun onLoad(host: LabHost)

    /** 模块被启用（或首次进入其界面）时调用。 */
    fun onEnable()

    /** 模块被停用，但类仍驻留，可再启用。 */
    fun onDisable()

    /** 模块被卸载，释放资源。此后实例作废。 */
    fun onUnload()
}

/**
 * 与 `module.json` 一一对应的元数据。
 * `apiVersion` 主版本必须与宿主一致才会被加载（规格书 §2.1 版本协商）。
 */
data class ModuleMetadata(
    val id: String,
    val name: String,
    val version: String,
    val apiVersion: Int,
    val author: String,
    val license: String,
    val source: String,
    val startup: Boolean = false,
    val dependencies: List<ModuleDependency> = emptyList(),
)

/**
 * 依赖声明。加载器按 `id` 做拓扑排序，出现环直接拒绝整批。
 * `capabilities` 用于服务寻址，避免模块之间直接依赖实现类。
 */
data class ModuleDependency(
    val id: String,
    val versionRange: String,
    val capabilities: List<String> = emptyList(),
)
