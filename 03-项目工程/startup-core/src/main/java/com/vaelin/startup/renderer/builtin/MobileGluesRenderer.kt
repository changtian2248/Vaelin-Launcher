/*
 * Zalith Launcher 2
 * Copyright (C) 2025-2026 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.vaelin.startup.renderer.builtin

import com.vaelin.startup.renderer.RendererInterface
import com.vaelin.startup.util.VLog
import com.vaelin.startup.util.VPaths
import org.json.JSONObject
import java.io.File

private const val TAG = "MobileGluesRenderer"

/**
 * MobileGlues 内置渲染器（第 10 个内置渲染器）。
 *
 * ZL2 的同名文件是 333 行的 ConfigurableRenderer：配置项声明（configSpec）+ 用户可调
 * 配置 + 默认值写盘三件套。Vaelin R1 不引入渲染器配置 UI，本类是它的精简移植：
 *  - 身份字段（id / uniqueIdentifier / 名称 / summary / 库名 / EGL / dlopen 表）
 *    与 ZL2 MobileGluesRenderer.kt:240-260 逐字一致；
 *  - 环境变量取 ZL2 configSpec 的 fixedEnv（LIBGL_ES=3、LIBGL_EGL=libmobileglues.so），
 *    另加 MG_DIR_PATH 指向 Vaelin 的 mobileglues 配置目录（ZL2 的
 *    getConfiguredEnv 在 MobileGluesRenderer.kt:250-254 做同一件事）；
 *  - 首次 env 求值时若 config.json 不存在，写入 ZL2 ConfigurableRenderer「默认分支」
 *    会产生的同一份 JSON（字段推导见 defaultConfigJson 的注释）。
 *
 * 不移植的部分（有意省略，不是遗漏）：
 *  - configSpec 的用户可调项与 launcher-keys 管理键文件——它们服务于 ZL2 的
 *    渲染器配置 UI；Vaelin 没有该 UI，一次性写默认值即可，不需要「launcher 管理了
 *    哪些键、清理时删哪些」的记账文件；
 *  - MG_COUNT_LAUNCH（ZL2 默认开的启动会话上报开关）不进 env：Vaelin 没有配置 UI
 *    可以关掉它，保持不设等于让 MobileGlues 走它自己的默认（不统计）。
 *
 * env 是 Lazy：目录解析与默认配置写盘都推迟到真正启动（:game 进程拼 env）时，
 * 那时 VPaths 一定已 init；未 init 时 lateinit 访问会显式抛错，不做静默兜底。
 */
object MobileGluesRenderer : RendererInterface {

    override fun getRendererId(): String = "opengles3_mobileglues"

    override fun getUniqueIdentifier(): String = "acaf8100-bbaf-4d68-b30d-38bf42fcd640"

    /** ZL2 MobileGluesRenderer.kt:244 同款：MobileGlues 默认可用 SFPEW 兼容层 */
    override fun supportsSfpewByDefault(): Boolean = true

    override fun getRendererName(): String = "MobileGlues"

    /** ZL2 MobileGluesRenderer.kt:248 同款 */
    override fun getRendererSummary(): String = "OpenGL 4.0"

    override fun getRendererLibrary(): String = "libmobileglues.so"

    override fun getRendererEGL(): String = "libmobileglues.so"

    override fun getDlopenLibrary(): Lazy<List<String>> = lazy { emptyList() }

    override fun getRendererEnv(): Lazy<Map<String, String>> = lazy {
        val dir = VPaths.mobilegluesDir
        writeDefaultConfigIfMissing(dir)
        mapOf(
            // ZL2 configSpec 的 fixedEnv（MobileGluesRenderer.kt:39-42）
            "LIBGL_ES" to "3",
            "LIBGL_EGL" to "libmobileglues.so",
            // ZL2 getConfiguredEnv 的 MG_DIR_PATH（MobileGluesRenderer.kt:253）
            "MG_DIR_PATH" to dir.absolutePath,
        )
    }

    /**
     * config.json 缺失时写一份 ZL2「默认分支」的等价物；已存在（含用户手工改过）不覆盖。
     *
     * 写盘失败只记警告不抛：config.json 缺失时 MobileGlues 会退回它自己的内置默认值，
     * 游戏仍可启动；把它升级成启动中断反而放大了一个可自愈的故障。
     */
    private fun writeDefaultConfigIfMissing(dir: File) {
        val configFile = File(dir, "config.json")
        if (configFile.isFile) return

        runCatching {
            if (!dir.isDirectory && !dir.mkdirs()) {
                error("无法创建 MobileGlues 配置目录: ${dir.absolutePath}")
            }
            val tmp = File(dir, "config.json.tmp")
            tmp.writeText(defaultConfigJson())
            if (configFile.exists() && !configFile.delete()) {
                error("无法替换旧的 MobileGlues 配置文件: ${configFile.absolutePath}")
            }
            if (!tmp.renameTo(configFile)) {
                // rename 失败退化为直接写（ProfileStore.save 同款兜底），最终保证有文件
                configFile.writeText(defaultConfigJson())
                tmp.delete()
            }
            VLog.info(TAG, "已写入 MobileGlues 默认配置: ${configFile.absolutePath}")
        }.onFailure {
            VLog.warning(TAG, "写入 MobileGlues 默认配置失败（游戏将使用 MobileGlues 内置默认值）", it)
        }
    }

    /**
     * ZL2 默认分支的 config.json 内容，逐字段对应 MobileGluesRenderer.kt:38-236 的
     * configSpec 默认值，经 RendererConfigResolver.kt:68-90 的 jsonFields 规则投影：
     *  - switch 落 JSON 时写成 0/1（Resolver 把 BooleanValue 转成 IntValue）；
     *  - 单选 choice 默认 target 是 JsonField(id)（Dsl.kt:68），值是选项原始类型（int 0）；
     *  - multidrawOrder 的五个例外档（Arrays/Elements/ElementsBaseVertex/ArraysIndirect/
     *    ElementsIndirect）defaultEnabled=false，Resolver 对 enabled=false 的项不落
     *    jsonFields，因此默认配置里没有它们；
     *  - maxGlslCacheSize 默认 IntValue(32)；sfpewLayer 的 target 是 SfpewLayer，
     *    不落 config.json；MG_COUNT_LAUNCH 的 target 是 Environment，不落 config.json。
     * 键顺序 = configSpec 声明顺序（org.json.JSONObject 保持插入序，与 ZL2 一致）。
     */
    private fun defaultConfigJson(): String {
        val json = JSONObject()
        json.put("enableANGLE", 0)
        json.put("enableNoError", 0)
        json.put(
            "multidrawOrder",
            "native,multiindirect,multibasevertex,multiarrays,indirect,basevertex,unroll,compute",
        )
        json.put("angleDepthClearFixMode", 0)
        json.put("customGLVersion", 0)
        json.put("maxGlslCacheSize", 32)
        json.put("enableExtTimerQuery", 1)
        json.put("enableExtComputeShader", 0)
        json.put("enableExtDirectStateAccess", 0)
        json.put("fsr1Setting", 0)
        return json.toString()
    }
}
