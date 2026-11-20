package com.vaelin.launcher.ui

import com.vaelin.startup.profile.VersionProfile
import com.vaelin.startup.renderer.VaelinRenderers

/**
 * 渲染器下拉项。把 startup-core 的 RendererInterface 转成 UI 需要的纯数据，
 * 避免 Compose 层直接持有渲染器实例（那些实例带 Lazy 的 dlopen 表，不该进状态）。
 */
data class RendererOption(
    /** 存进 VersionProfile.rendererUniqueId */
    val uniqueId: String,
    /** 存进 VersionProfile.rendererId（短 id，如 "opengles2"） */
    val rendererId: String,
    val name: String,
    val summary: String?,
)

/** 必须在 VaelinRenderers.init() 之后调用；未初始化时返回空列表而不是抛异常 */
fun rendererOptions(): List<RendererOption> =
    VaelinRenderers.getRenderers().map { renderer ->
        RendererOption(
            uniqueId = renderer.getUniqueIdentifier(),
            rendererId = renderer.getRendererId(),
            name = renderer.getRendererName(),
            summary = renderer.getRendererSummary(),
        )
    }

/**
 * 列表卡片上显示的渲染器名。
 * 两级回退：先按唯一标识匹配，再按短 id 匹配，最后显示原始短 id，
 * 这样即使渲染器在后续版本被移出注册表，老实例也不会显示成空白。
 */
fun rendererNameOf(profile: VersionProfile): String {
    val renderers = VaelinRenderers.getRenderers()
    renderers.firstOrNull { it.getUniqueIdentifier() == profile.rendererUniqueId }
        ?.let { return it.getRendererName() }
    renderers.firstOrNull { it.getRendererId() == profile.rendererId }
        ?.let { return it.getRendererName() }
    return profile.rendererId.ifBlank { "未知渲染器" }
}
