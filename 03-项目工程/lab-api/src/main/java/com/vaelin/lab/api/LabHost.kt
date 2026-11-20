package com.vaelin.lab.api

import android.content.Context
import androidx.compose.runtime.Composable

/** 宿主实现的契约版本。模块的 `metadata.apiVersion` 主版本必须与它相等。 */
const val LAB_API_VERSION: Int = 1

/**
 * 宿主暴露给模块的能力面（规格书 §2.1）。
 *
 * 模块**永远不能**直接拿到宿主的 NavDisplay 或 Activity。
 * 页面必须经 [registerRoute] 注册成路由，由宿主的固定容器 `LabModulePage(moduleId, route)` 承载；
 * 模块内部的多页跳转用它自己的 Compose 状态栈，宿主只桥接返回键。
 */
interface LabHost {
    val context: Context

    /** 注册一个可被用户点开的功能入口。 */
    fun registerLabEntry(entry: LabEntry)

    /** 注册一处声明式 UI 扩展（v1 不支持往宿主既有页面塞 Composable）。 */
    fun registerUiExtension(extension: UiExtension)

    /** 注册一个路由，由宿主的 `LabModulePage` 容器承载。 */
    fun registerRoute(route: LabRoute)

    /** 按接口寻址获取服务，避免模块间直接依赖实现类。 */
    fun <T : Any> getService(id: String, type: Class<T>): T?
}

/** 一个页面。模块所有界面都是它的实现。 */
fun interface LabPage {
    @Composable
    fun Content()
}

/** 功能入口，出现在宿主的功能列表里。 */
data class LabEntry(
    val id: String,
    val title: String,
    val iconRes: String? = null,
)

/** 路由定义。`route` 是模块内部的相对路径，宿主拼上 moduleId 后成为全局唯一。 */
data class LabRoute(
    val route: String,
    val page: LabPage,
)

/**
 * 声明式 UI 扩展点（规格书 §2.1）。
 * v1 只允许"往宿主的这些位置声明一个入口"，不允许注入任意 Composable。
 */
sealed interface UiExtension {
    val id: String
    val title: String

    data class Sidebar(override val id: String, override val title: String) : UiExtension
    data class TopBar(override val id: String, override val title: String) : UiExtension
    data class Settings(override val id: String, override val title: String) : UiExtension
    data class Theme(override val id: String, override val title: String) : UiExtension
    data class Home(override val id: String, override val title: String) : UiExtension
}
