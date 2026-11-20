/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.vaelin.startup.launch

import com.vaelin.startup.download.FakeHttpTransport
import com.vaelin.startup.download.MojangAssetSource
import com.vaelin.startup.download.MojangEndpoints
import com.vaelin.startup.download.VersionJson
import com.vaelin.startup.profile.GameDirectoryMode
import com.vaelin.startup.profile.VersionProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

@Suppress("NonAsciiCharacters")
class LaunchCoordinatorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class FakeEnvironment(private val runtimeHome: File) : LaunchCoordinator.LaunchEnvironmentProvider {
        var jreName: String? = null
            private set
        var componentCalls = 0
            private set

        override suspend fun ensureJre(name: String, onProgress: (String) -> Unit): File {
            jreName = name
            onProgress("jre:$name")
            runtimeHome.mkdirs()
            return runtimeHome
        }

        override suspend fun ensureComponents(onProgress: (String) -> Unit) {
            componentCalls++
            onProgress("components")
        }

        /** 伪造 3.3.3 组件目录里的两个 jar，验证它们按顺序排在 Mojang 库之前。 */
        override fun lwjglComponent(version: VersionJson): LwjglSelection.Choice {
            val dir = File(runtimeHome.parentFile, "components/lwjgl/3.3.3")
            dir.mkdirs()
            val core = File(dir, "lwjgl.jar").apply { writeText("lwjgl-core") }
            val merged = File(dir, "lwjgl-3.3.3-merged-modules.jar").apply { writeText("merged") }
            return LwjglSelection.Choice("lwjgl/3.3.3", listOf(core, merged))
        }
    }

    private class Fixture(val root: File) {
        val versionUrl = "https://example.invalid/version/1.20.1.json"
        val clientUrl = "https://example.invalid/client.jar"
        val indexUrl = "https://example.invalid/indexes/5.json"
        val libUrl = "https://example.invalid/plain.jar"
        val clientBytes = "client-bytes".toByteArray(Charsets.UTF_8)
        val libBytes = "lib-bytes".toByteArray(Charsets.UTF_8)
        val assetBytes = "asset-bytes".toByteArray(Charsets.UTF_8)
        val assetHash = sha1Hex(assetBytes)
        val indexBytes = buildAssetIndex(assetHash, assetBytes.size.toLong()).toByteArray(Charsets.UTF_8)
        val versionJson = buildVersionJson(
            clientUrl, sha1Hex(clientBytes), clientBytes.size.toLong(),
            indexUrl, sha1Hex(indexBytes), indexBytes.size.toLong(),
            libUrl, sha1Hex(libBytes), libBytes.size.toLong(),
        )
        val manifestBytes = buildManifest(versionUrl).toByteArray(Charsets.UTF_8)
        val transport = FakeHttpTransport()

        fun registerAll() {
            transport.serve(MojangEndpoints.VERSION_MANIFEST, manifestBytes)
            transport.serveText(versionUrl, versionJson)
            transport.serve(clientUrl, clientBytes)
            transport.serve(indexUrl, indexBytes)
            transport.serve(libUrl, libBytes)
            transport.serve(MojangAssetSource.objectUrl(assetHash), assetBytes)
        }

        fun profile() = VersionProfile(id = "inst-1", minecraftVersion = "1.20.1")
        fun newEnv() = FakeEnvironment(File(root, "runtimes/jre-17"))
    }

    private fun prepare(f: Fixture, env: FakeEnvironment, progress: ArrayList<LaunchProgress>): LaunchPlan =
        runBlocking {
            LaunchCoordinator.prepare(
                context = null,
                profile = f.profile(),
                transport = f.transport,
                gameDir = f.root,
                nativesDir = File(f.root, "cache"),
                userHome = File(f.root, "files"),
                abi = LaunchCoordinator.DEFAULT_ABI,
                environmentProvider = env,
                onProgress = { progress += it },
            )
        }

    @Test
    fun `prepare 零网络跑完完整阶段并落盘可解码的 LaunchPlan`() {
        val f = Fixture(temp.root)
        f.registerAll()
        val env = f.newEnv()
        val progress = ArrayList<LaunchProgress>()

        val plan = prepare(f, env, progress)

        assertEquals("inst-1", plan.versionId)
        assertEquals("net.minecraft.client.main.Main", plan.mainClass)
        assertEquals("jre-17", env.jreName)
        assertEquals(1, env.componentCalls)
        assertEquals(
            listOf(
                "--username", "Steve",
                "--uuid", OfflineAccount.uuidFor("Steve"),
                "--gameDir", temp.root.absolutePath,
                "--version", "1.20.1",
            ),
            plan.gameArgs,
        )
        assertEquals(4, plan.classpath.size)
        // LWJGL 组件 jar 在前（补丁类赢过 Mojang 同名类），Mojang 库与 client jar 在后
        assertEquals("lwjgl.jar", File(plan.classpath[0]).name)
        assertEquals("lwjgl-3.3.3-merged-modules.jar", File(plan.classpath[1]).name)
        assertEquals("plain-1.0.jar", File(plan.classpath[2]).name)
        assertEquals("1.20.1.jar", File(plan.classpath[3]).name)
        assertEquals("lwjgl/3.3.3", plan.lwjglComponentDir)
        assertEquals(LaunchStage.entries.toList(), progress.map { it.stage }.distinct())
        assertEquals(100, progress.last().percent)

        val planFile = LaunchCoordinator.planFile(temp.root, "inst-1")
        assertTrue(planFile.isFile)
        assertEquals(plan, LaunchPlanCodec.decode(planFile.readText(Charsets.UTF_8)))

        val objectFile = File(
            File(File(File(temp.root, "assets"), "objects"), f.assetHash.take(2)),
            f.assetHash,
        )
        assertTrue(objectFile.isFile)
        assertEquals(String(f.assetBytes, Charsets.UTF_8), objectFile.readText(Charsets.UTF_8))
        assertTrue(File(temp.root, "versions/1.20.1/1.20.1.json").isFile)
    }

    @Test
    fun `全局隔离只改游戏工作目录核心资源仍留在共享 gameHome`() {
        val f = Fixture(temp.root)
        f.registerAll()
        val profile = f.profile()
        val isolatedDir = File(temp.root, "versions/${profile.id}")
        val env = f.newEnv()

        val plan = runBlocking {
            LaunchCoordinator.prepare(
                context = null,
                profile = profile,
                transport = f.transport,
                gameDir = f.root,
                contentDir = isolatedDir,
                gameDirectoryMode = GameDirectoryMode.ISOLATED,
                nativesDir = File(f.root, "cache"),
                userHome = File(f.root, "files"),
                abi = LaunchCoordinator.DEFAULT_ABI,
                environmentProvider = env,
            )
        }

        assertEquals(isolatedDir.absolutePath, plan.gameDirectory)
        assertEquals(GameDirectoryMode.ISOLATED, plan.gameDirectoryMode)
        assertTrue(File(f.root, "assets/indexes/5.json").isFile)
        assertTrue(File(f.root, "libraries/com/example/plain/1.0/plain-1.0.jar").isFile)
        assertTrue(File(f.root, "versions/1.20.1/1.20.1.json").isFile)
        assertTrue(LaunchCoordinator.planFile(f.root, profile.id).isFile)
        assertTrue(plan.gameArgs.containsAll(listOf("--gameDir", isolatedDir.absolutePath)))
    }

    @Test
    fun `第二次 prepare 已校验通过的文件零网络跳过`() {
        val f = Fixture(temp.root)
        f.registerAll()
        val first = prepare(f, f.newEnv(), ArrayList())

        // 所有下载端点改成 500：若第二次仍去下载，prepare 必然失败；
        // 校验通过的文件应被 FileDownloader / AssetDownloadPlanner 跳过，不产生网络请求。
        f.transport.fail(f.clientUrl, 500)
        f.transport.fail(f.libUrl, 500)
        f.transport.fail(f.indexUrl, 500)
        f.transport.fail(MojangAssetSource.objectUrl(f.assetHash), 500)

        val second = prepare(f, f.newEnv(), ArrayList())

        assertEquals(first, second)
    }

    @Test
    fun `版本清单找不到指定版本时显式报错禁止回退到 latest`() {
        val f = Fixture(temp.root)
        f.transport.serveText(
            MojangEndpoints.VERSION_MANIFEST,
            """{"latest":{"release":"1.21","snapshot":"1.21"},"versions":[{"id":"1.21","type":"release","url":"https://example.invalid/other.json","sha1":"x","size":1,"releaseTime":"x"}]}""",
        )

        val error = assertThrows(LaunchException::class.java) {
            prepare(f, f.newEnv(), ArrayList())
        }
        assertTrue(error.message!!.contains("找不到"))
    }

    @Test
    fun `下载失败时显式抛 LaunchException 中止`() {
        val f = Fixture(temp.root)
        f.registerAll()
        f.transport.fail(f.clientUrl, 500)

        val error = assertThrows(LaunchException::class.java) {
            prepare(f, f.newEnv(), ArrayList())
        }
        assertTrue(error.message!!.contains("版本文件下载失败"))
    }

    @Test
    fun `selectJre 按 javaVersion 选 jre-21 或 jre-17 且 profile 覆盖优先`() {
        val v21 = VersionJson.parse("""{"id":"x","mainClass":"Main","javaVersion":{"majorVersion":21}}""")
        val v17 = VersionJson.parse("""{"id":"x","mainClass":"Main","javaVersion":{"majorVersion":17}}""")
        val v8 = VersionJson.parse("""{"id":"x","mainClass":"Main","javaVersion":{"majorVersion":8}}""")
        val none = VersionJson.parse("""{"id":"x","mainClass":"Main"}""")
        val plain = VersionProfile(id = "p", minecraftVersion = "x")

        assertEquals("jre-21", LaunchCoordinator.selectJre(plain, v21))
        assertEquals("jre-17", LaunchCoordinator.selectJre(plain, v17))
        assertEquals("custom-jre", LaunchCoordinator.selectJre(plain.copy(javaRuntime = "custom-jre"), v17))
        assertThrows(LaunchException::class.java) { LaunchCoordinator.selectJre(plain, v8) }
            .also { assertTrue(it.message!!.contains("不受支持")) }
        assertThrows(LaunchException::class.java) { LaunchCoordinator.selectJre(plain, none) }
            .also { assertTrue(it.message!!.contains("缺少 javaVersion")) }
    }

    // ---------------------------------------------------------------
    // 本地已安装模组版（inheritsFrom 合并分支）
    // ---------------------------------------------------------------

    private class LocalFixture(val root: File, val forgeId: String = "1.20.1-forge-47.2.0") {
        val plainUrl = "https://example.invalid/plain-1.0.jar"
        val plainBytes = "plain-lib".toByteArray(Charsets.UTF_8)
        val forgeUrl = "https://example.invalid/forge-universal.jar"
        val forgeBytes = "forge-lib".toByteArray(Charsets.UTF_8)
        val indexUrl = "https://example.invalid/indexes/5.json"
        val assetBytes = "local-asset".toByteArray(Charsets.UTF_8)
        val assetHash = sha1Hex(assetBytes)
        val indexBytes = buildAssetIndex(assetHash, assetBytes.size.toLong()).toByteArray(Charsets.UTF_8)
        val stubJarBytes = "stub-jar-bytes".toByteArray(Charsets.UTF_8)

        val transport = FakeHttpTransport()

        fun profile() = VersionProfile(id = "forge-inst", minecraftVersion = forgeId)
        fun newEnv() = FakeEnvironment(File(root, "runtimes/jre-17"))

        fun versionsDir() = File(root, "versions")
        fun forgeJsonFile() = File(File(versionsDir(), forgeId), "$forgeId.json")
        fun stubJarFile() = File(File(versionsDir(), forgeId), "$forgeId.jar")
        fun vanillaJsonFile() = File(File(versionsDir(), "1.20.1"), "1.20.1.json")

        fun writeVanillaAndForge() {
            vanillaJsonFile().apply { parentFile.mkdirs() }.writeText(vanillaParentJson(), Charsets.UTF_8)
            forgeJsonFile().apply { parentFile.mkdirs() }.writeText(forgeChildJson(), Charsets.UTF_8)
        }

        fun prepare(progress: ArrayList<LaunchProgress> = ArrayList(), env: FakeEnvironment = newEnv()): LaunchPlan =
            runBlocking {
                LaunchCoordinator.prepare(
                    context = null,
                    profile = profile(),
                    transport = transport,
                    gameDir = root,
                    nativesDir = File(root, "cache"),
                    userHome = File(root, "files"),
                    abi = LaunchCoordinator.DEFAULT_ABI,
                    environmentProvider = env,
                    onProgress = { progress += it },
                )
            }

        /** 父 vanilla JSON（Forge 安装产物场景：父没有 downloads.client，client jar 由桩提供）。 */
        private fun vanillaParentJson(): String = """
            {
              "id":"1.20.1","type":"release","mainClass":"net.minecraft.client.main.Main","assets":"5",
              "assetIndex":{"id":"5","url":"$indexUrl","sha1":"${sha1Hex(indexBytes)}","size":${indexBytes.size}},
              "javaVersion":{"component":"java-runtime-gamma","majorVersion":17,"version":"17.0.8"},
              "libraries":[
                {"name":"com.example:plain:1.0","downloads":{"artifact":{"path":"com/example/plain/1.0/plain-1.0.jar","sha1":"${sha1Hex(plainBytes)}","size":${plainBytes.size},"url":"$plainUrl"}}}
              ],
              "arguments":{"game":["--username","${'$'}{auth_player_name}","--gameDir","${'$'}{game_directory}","--version","${'$'}{version_name}"]}
            }
        """.trimIndent()

        /** 子 Forge 安装产物 JSON：无 mainClass、带 inheritsFrom、自带库与 game 参数。 */
        private fun forgeChildJson(): String = """
            {
              "id":"$forgeId","inheritsFrom":"1.20.1","type":"release",
              "libraries":[
                {"name":"net.minecraftforge:forge:1.20.1-47.2.0:universal","downloads":{"artifact":{"path":"net/minecraftforge/forge/1.20.1-47.2.0/forge-1.20.1-47.2.0-universal.jar","sha1":"${sha1Hex(forgeBytes)}","size":${forgeBytes.size},"url":"$forgeUrl"}}}
              ],
              "arguments":{"game":["--launchTarget","forgeclient","--gameDir","/forge-should-not-appear","--tweakClass","net.minecraftforge.fml.loading.FMLClientTweaker"]}
            }
        """.trimIndent()
    }

    @Test
    fun `本地模组版走合并分支跳过清单拉取且不覆盖安装产物`() {
        val f = LocalFixture(temp.root)
        f.writeVanillaAndForge()
        f.stubJarFile().writeBytes(f.stubJarBytes)
        f.transport.serve(f.plainUrl, f.plainBytes)
        f.transport.serve(f.forgeUrl, f.forgeBytes)
        f.transport.serve(f.indexUrl, f.indexBytes)
        f.transport.serve(MojangAssetSource.objectUrl(f.assetHash), f.assetBytes)
        val progress = ArrayList<LaunchProgress>()

        val plan = f.prepare(progress)

        // 零清单请求：本地 JSON 存在时绝不访问 Mojang 清单
        assertTrue(f.transport.calls.none { it.url == MojangEndpoints.VERSION_MANIFEST })
        // 库与资源照常下载
        assertTrue(f.transport.calls.any { it.url == f.forgeUrl })
        assertTrue(f.transport.calls.any { it.url == f.indexUrl })
        assertTrue(
            File(f.root, "libraries/net/minecraftforge/forge/1.20.1-47.2.0/forge-1.20.1-47.2.0-universal.jar").isFile,
        )
        // asset index（vanilla 的 index 5）与对象文件已就位
        assertTrue(File(f.root, "assets/indexes/5.json").isFile)
        assertTrue(
            File(File(File(File(f.root, "assets"), "objects"), f.assetHash.take(2)), f.assetHash).isFile,
        )

        // mainClass 继承自父；versionId 仍用 profile.id
        assertEquals("net.minecraft.client.main.Main", plan.mainClass)
        assertEquals("forge-inst", plan.versionId)
        // game args = 父的展开在前 + 子的追加；子重复的 --gameDir 及其值被父的胜出
        assertEquals(listOf("--username", "Steve"), plan.gameArgs.take(2))
        assertTrue(plan.gameArgs.containsAll(listOf("--version", f.forgeId)))
        assertTrue(plan.gameArgs.containsAll(listOf("--launchTarget", "forgeclient")))
        assertTrue(plan.gameArgs.contains("--tweakClass"))
        assertTrue(plan.gameArgs.contains("net.minecraftforge.fml.loading.FMLClientTweaker"))
        assertFalse(plan.gameArgs.contains("/forge-should-not-appear"))

        // classpath：lwjgl 组件 jar 在前 + 排序后的库 + 桩 jar（<forgeId>.jar）收尾
        assertEquals(5, plan.classpath.size)
        assertEquals("lwjgl.jar", File(plan.classpath[0]).name)
        assertEquals("plain-1.0.jar", File(plan.classpath[2]).name)
        assertEquals("forge-1.20.1-47.2.0-universal.jar", File(plan.classpath[3]).name)
        assertEquals("${f.forgeId}.jar", File(plan.classpath[4]).name)

        // 安装产物不被回写：子 JSON 仍带 inheritsFrom，桩 jar 内容未被覆盖
        assertTrue(f.forgeJsonFile().readText(Charsets.UTF_8).contains("inheritsFrom"))
        assertTrue(f.stubJarFile().readBytes().contentEquals(f.stubJarBytes))

        // 阶段序列完整、落盘计划可解码
        assertEquals(LaunchStage.entries.toList(), progress.map { it.stage }.distinct())
        val planFile = LaunchCoordinator.planFile(f.root, "forge-inst")
        assertTrue(planFile.isFile)
        assertEquals(plan, LaunchPlanCodec.decode(planFile.readText(Charsets.UTF_8)))
    }

    @Test
    fun `本地版本全部文件就绪时零网络请求`() {
        val f = LocalFixture(temp.root)
        f.writeVanillaAndForge()
        f.stubJarFile().writeBytes(f.stubJarBytes)
        // 库文件已就位（内容与 JSON 里的 sha1 一致 → FileDownloader 直接跳过）
        File(f.root, "libraries/com/example/plain/1.0/plain-1.0.jar").apply { parentFile.mkdirs() }.writeBytes(f.plainBytes)
        File(f.root, "libraries/net/minecraftforge/forge/1.20.1-47.2.0/forge-1.20.1-47.2.0-universal.jar")
            .apply { parentFile.mkdirs() }.writeBytes(f.forgeBytes)
        // asset index 与对象文件已就位（大小一致 → AssetDownloadPlanner 直接跳过）
        File(f.root, "assets/indexes/5.json").apply { parentFile.mkdirs() }.writeBytes(f.indexBytes)
        File(File(File(File(f.root, "assets"), "objects"), f.assetHash.take(2)), f.assetHash)
            .apply { parentFile.mkdirs() }.writeBytes(f.assetBytes)

        // 传输层一个 handler 都没注册：任何网络请求都会让断言失败
        val plan = f.prepare()

        assertEquals(0, f.transport.callCount)
        assertEquals("net.minecraft.client.main.Main", plan.mainClass)
        assertEquals(5, plan.classpath.size)
    }

    @Test
    fun `本地版本的父版本 JSON 缺失时显式报错`() {
        val f = LocalFixture(temp.root)
        f.forgeJsonFile().apply { parentFile.mkdirs() }
            .writeText("""{"id":"${f.forgeId}","inheritsFrom":"1.20.1"}""", Charsets.UTF_8)

        val error = assertThrows(LaunchException::class.java) { f.prepare() }

        assertTrue("实际消息：${error.message}", error.message!!.contains("父版本 JSON 不存在"))
        assertTrue("实际消息：${error.message}", error.message!!.contains("1.20.1"))
    }
}

private fun sha1Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }

private fun buildManifest(versionUrl: String): String = """
    {"latest":{"release":"1.20.1","snapshot":"1.20.1"},"versions":[{"id":"1.20.1","type":"release","url":"$versionUrl","sha1":"x","size":1,"releaseTime":"2023-01-01"}]}
""".trimIndent()

private fun buildAssetIndex(hash: String, size: Long): String = """
    {"objects":{"minecraft/lang/en_us.json":{"hash":"$hash","size":$size}}}
""".trimIndent()

private fun buildVersionJson(
    clientUrl: String, clientSha1: String, clientSize: Long,
    indexUrl: String, indexSha1: String, indexSize: Long,
    libUrl: String, libSha1: String, libSize: Long,
): String = """
    {
      "id":"1.20.1","type":"release","mainClass":"net.minecraft.client.main.Main","assets":"5",
      "assetIndex":{"id":"5","url":"$indexUrl","sha1":"$indexSha1","size":$indexSize},
      "downloads":{"client":{"sha1":"$clientSha1","size":$clientSize,"url":"$clientUrl"}},
      "javaVersion":{"component":"java-runtime-gamma","majorVersion":17,"version":"17.0.8"},
      "libraries":[
        {"name":"com.example:plain:1.0","downloads":{"artifact":{"path":"com/example/plain/1.0/plain-1.0.jar","sha1":"$libSha1","size":$libSize,"url":"$libUrl"}}},
        {"name":"com.example:plain:1.0","downloads":{"artifact":{"path":"com/example/plain/1.0/plain-1.0.jar","sha1":"$libSha1","size":$libSize,"url":"$libUrl"}}}
      ],
      "arguments":{"game":["--username","${'$'}{auth_player_name}","--uuid","${'$'}{auth_uuid}","--gameDir","${'$'}{game_directory}","--version","${'$'}{version_name}"]}
    }
""".trimIndent()
