package com.vaelin.startup.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 渲染器注册表语义。
 *
 * 这里验证的是 Vaelin 相对 ZL2 改掉的两处行为：
 *  1. 找不到渲染器时必须抛异常，不能静默回退到列表首个；
 *  2. 唯一标识冲突时拒绝加入，不覆盖已有项。
 */
class VaelinRenderersTest {

    @Before
    fun setUp() {
        VaelinRenderers.init(reset = true)
    }

    @Test
    fun `内置渲染器全部注册且 id 唯一`() {
        val all = VaelinRenderers.getRenderers()
        assertEquals(10, all.size)

        val ids = all.map { it.getRendererId() }
        assertEquals("rendererId 重复: $ids", ids.size, ids.toSet().size)

        val uids = all.map { it.getUniqueIdentifier() }
        assertEquals("uniqueIdentifier 重复: $uids", uids.size, uids.toSet().size)
    }

    @Test
    fun `MobileGlues 身份字段与 ZL2 一致`() {
        val mg = requireNotNull(
            VaelinRenderers.getRenderers()
                .firstOrNull { it.getRendererId() == "opengles3_mobileglues" }
        ) { "MobileGlues 未注册" }
        // ZL2 MobileGluesRenderer.kt:240-260 的身份字段原值
        assertEquals("acaf8100-bbaf-4d68-b30d-38bf42fcd640", mg.getUniqueIdentifier())
        assertEquals("MobileGlues", mg.getRendererName())
        assertEquals("OpenGL 4.0", mg.getRendererSummary())
        assertEquals("libmobileglues.so", mg.getRendererLibrary())
        assertEquals("libmobileglues.so", mg.getRendererEGL())
        assertTrue(mg.supportsSfpewByDefault())
        // 注意：这里不取 getRendererEnv().value——它的惰性块引用 VPaths（Android 侧
        // lateinit），JVM 单测里求值会抛 UninitializedPropertyAccessException。
    }

    @Test
    fun `Pojav 兼容档位在内，且与 ZL2 同名项不冲突`() {
        val byId = VaelinRenderers.getRenderers().associateBy { it.getRendererId() }

        // ZL2 的 opengles3 指向 libng_gl4es.so
        assertEquals("libng_gl4es.so", byId.getValue("opengles3").getRendererLibrary())
        // Pojav 的 opengles3_ltw / vulkan_zink / opengles2_5 是额外补的
        assertEquals("libgl4es_114.so", byId.getValue("opengles2_5").getRendererLibrary())
        // 实测 ZL2 2.8.1 的 APK 里没有 libOSMesa.so 也没有 libltw.so：
        // vulkan_zink 映射到 Kopper（libglxshim + libEGL_mesa），ltw 保持 Pojav 原库名
        assertEquals("libglxshim.so", byId.getValue("vulkan_zink").getRendererLibrary())
        assertEquals("libEGL_mesa.so", byId.getValue("vulkan_zink").getRendererEGL())
        assertEquals("libltw.so", byId.getValue("opengles3_ltw").getRendererLibrary())
    }

    @Test
    fun `选择不存在的渲染器必须抛异常而不是回退`() {
        assertThrows(IllegalArgumentException::class.java) {
            VaelinRenderers.setCurrentRenderer("no-such-renderer-uuid")
        }
        assertFalse(VaelinRenderers.isCurrentRendererValid())
    }

    @Test
    fun `按短 id 选择渲染器`() {
        VaelinRenderers.setCurrentRendererById("vulkan_zink")
        assertTrue(VaelinRenderers.isCurrentRendererValid())
        assertEquals(
            "libglxshim.so",
            VaelinRenderers.getCurrentRenderer().getRendererLibrary()
        )
        assertEquals(
            "libEGL_mesa.so",
            VaelinRenderers.getCurrentRenderer().getRendererEGL()
        )
    }

    @Test
    fun `未选择就取当前渲染器会抛异常`() {
        assertThrows(IllegalStateException::class.java) {
            VaelinRenderers.getCurrentRenderer()
        }
    }

    @Test
    fun `唯一标识冲突时拒绝加入`() {
        val existing = VaelinRenderers.getRenderers().first()
        val before = VaelinRenderers.getRenderers().size

        val duplicate = object : RendererInterface {
            override fun getRendererId() = "fake-duplicate"
            override fun getUniqueIdentifier() = existing.getUniqueIdentifier()
            override fun getRendererName() = "Fake"
            override fun getRendererEnv(): Lazy<Map<String, String>> = lazy { emptyMap() }
            override fun getDlopenLibrary(): Lazy<List<String>> = lazy { emptyList() }
            override fun getRendererLibrary() = "libfake.so"
        }

        assertFalse(VaelinRenderers.addRenderer(duplicate))
        assertEquals(before, VaelinRenderers.getRenderers().size)
    }

    @Test
    fun `重复 init 不重复注册`() {
        val before = VaelinRenderers.getRenderers().size
        VaelinRenderers.init()
        assertEquals(before, VaelinRenderers.getRenderers().size)
    }
}
