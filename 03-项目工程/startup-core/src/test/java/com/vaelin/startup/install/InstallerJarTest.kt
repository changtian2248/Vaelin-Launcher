/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * T1.5.5 —— installer jar 下载校验与 MANIFEST Main-Class 读取单测（纯 JVM）。
 */

package com.vaelin.startup.install

import com.vaelin.startup.jvm.expectThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipOutputStream

class InstallerJarTest {

    @get:Rule
    val temp = TemporaryFolder()

    // ---- MANIFEST Main-Class 读取 ----

    /** 写一个带（或不带）Main-Class 的 jar fixture，形态对齐 Forge installer。 */
    private fun writeJar(mainClass: String?, asPlainZip: Boolean = false): File {
        val jar = File(temp.root, "installer-${System.nanoTime()}.jar")
        if (asPlainZip) {
            ZipOutputStream(FileOutputStream(jar)).use { out ->
                out.putNextEntry(JarEntry("data.txt"))
                out.write("hello".toByteArray())
                out.closeEntry()
            }
            return jar
        }
        val manifest = Manifest()
        manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        mainClass?.let { manifest.mainAttributes[Attributes.Name.MAIN_CLASS] = it }
        JarOutputStream(FileOutputStream(jar), manifest).use { out ->
            out.putNextEntry(JarEntry("data.txt"))
            out.write("hello".toByteArray())
            out.closeEntry()
        }
        return jar
    }

    @Test
    fun `read main class from manifest`() {
        val jar = writeJar("net.minecraftforge.installer.SimpleInstaller")
        assertEquals(
            "net.minecraftforge.installer.SimpleInstaller",
            InstallerJar.readInstallerMainClass(jar),
        )
    }

    @Test
    fun `read main class trims trailing slash`() {
        val jar = writeJar("com.example.Main/")
        assertEquals("com.example.Main", InstallerJar.readInstallerMainClass(jar))
    }

    @Test
    fun `jar without manifest throws explicitly`() {
        val jar = writeJar(mainClass = null, asPlainZip = true)
        val e = expectThrows<ForgeInstallException> { InstallerJar.readInstallerMainClass(jar) }
        assertTrue(e.message!!.contains("MANIFEST"))
    }

    @Test
    fun `jar with blank main class throws explicitly`() {
        val jar = writeJar("   ")
        val e = expectThrows<ForgeInstallException> { InstallerJar.readInstallerMainClass(jar) }
        assertTrue(e.message!!.contains("Main-Class"))
    }

    @Test
    fun `missing jar file throws explicitly`() {
        val e = expectThrows<ForgeInstallException> {
            InstallerJar.readInstallerMainClass(File(temp.root, "nope.jar"))
        }
        assertTrue(e.message!!.contains("不存在"))
    }

    // ---- .sha1 侧车文本解析 ----

    @Test
    fun `sha1 sidecar accepts plain hash and maven pair format`() {
        assertEquals(
            "0deb8e547a9e3f098a4c9f082903499703c52f5d",
            InstallerJar.parseSha1Sidecar("0deb8e547a9e3f098a4c9f082903499703c52f5d"),
        )
        assertEquals(
            "0deb8e547a9e3f098a4c9f082903499703c52f5d",
            InstallerJar.parseSha1Sidecar("0deb8e547a9e3f098a4c9f082903499703c52f5d  forge-1.20.1-installer.jar\n"),
        )
    }

    @Test
    fun `sha1 sidecar rejects junk`() {
        assertEquals(null, InstallerJar.parseSha1Sidecar("<html>not found</html>"))
        assertEquals(null, InstallerJar.parseSha1Sidecar(""))
        assertEquals(null, InstallerJar.parseSha1Sidecar("abc123"))
    }

    // ---- 下载（多源回退 + sha1/size 校验）----

    /** 测试替身：按 URL + method 给响应，FakeHttpTransport 不区分 method，这里自己写。 */
    private class MethodAwareTransport : com.vaelin.startup.download.HttpTransport {
        val getHandlers = HashMap<String, () -> Pair<Int, ByteArray>>()
        val headResponses = HashMap<String, Pair<Int, Long>>()

        override fun open(url: String, method: String, rangeStart: Long): com.vaelin.startup.download.HttpResponse {
            if (method == "HEAD") {
                val (code, length) = headResponses[url] ?: (404 to 0L)
                return com.vaelin.startup.download.HttpResponse(code, length, null, null)
            }
            val handler = getHandlers[url]
                ?: return com.vaelin.startup.download.HttpResponse(404, 0L, ByteArrayInputStream(ByteArray(0)), null)
            val (code, content) = handler()
            return com.vaelin.startup.download.HttpResponse(code, content.size.toLong(), ByteArrayInputStream(content), null)
        }
    }

    @Test
    fun `download falls back to second source and verifies sha1`() {
        val content = ByteArray(1024) { (it % 251).toByte() }
        val digest = java.security.MessageDigest.getInstance("SHA-1").digest(content)
        val sha1 = digest.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        val transport = MethodAwareTransport().apply {
            getHandlers["https://bmcl/maven/forge-installer.jar"] = { 404 to ByteArray(0) }
            getHandlers["https://official/maven/forge-installer.jar"] = { 200 to content }
            getHandlers["https://bmcl/maven/forge-installer.jar.sha1"] = { 404 to ByteArray(0) }
            getHandlers["https://official/maven/forge-installer.jar.sha1"] = { 200 to sha1.toByteArray() }
        }
        val target = File(temp.root, "installer.jar")
        val result = InstallerJar.download(
            transport = transport,
            installerUrls = listOf("https://bmcl/maven/forge-installer.jar", "https://official/maven/forge-installer.jar"),
            sha1Urls = listOf("https://bmcl/maven/forge-installer.jar.sha1", "https://official/maven/forge-installer.jar.sha1"),
            target = target,
        )
        assertEquals(target, result)
        assertTrue(target.isFile)
        assertEquals(content.toList(), target.readBytes().toList())
    }

    @Test
    fun `download falls back to size verification when sidecars missing`() {
        val content = "forge-installer-bytes".toByteArray()
        val transport = MethodAwareTransport().apply {
            getHandlers["https://bmcl/forge-installer.jar"] = { 200 to content }
            headResponses["https://bmcl/forge-installer.jar"] = 200 to content.size.toLong()
        }
        val target = File(temp.root, "installer-size.jar")
        val result = InstallerJar.download(
            transport = transport,
            installerUrls = listOf("https://bmcl/forge-installer.jar"),
            sha1Urls = listOf("https://bmcl/forge-installer.jar.sha1"),
            target = target,
        )
        assertEquals(target, result)
        assertEquals(content.toList(), target.readBytes().toList())
    }

    @Test
    fun `download throws when no verification info available`() {
        val transport = MethodAwareTransport() // 全部 404：sha1 与 HEAD 都拿不到
        val e = expectThrows<ForgeInstallException> {
            InstallerJar.download(
                transport = transport,
                installerUrls = listOf("https://bmcl/forge-installer.jar"),
                sha1Urls = listOf("https://bmcl/forge-installer.jar.sha1"),
                target = File(temp.root, "never.jar"),
            )
        }
        assertTrue(e.message!!.contains("校验信息"))
    }

    @Test
    fun `download throws with per-source reasons when all sources fail`() {
        val goodContent = "good".toByteArray()
        val digest = java.security.MessageDigest.getInstance("SHA-1").digest(goodContent)
        val sha1 = digest.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        val corrupt = "corrupt".toByteArray()
        val transport = MethodAwareTransport().apply {
            getHandlers["https://bmcl/forge-installer.jar"] = { 200 to corrupt }
            getHandlers["https://official/forge-installer.jar"] = { 500 to ByteArray(0) }
            getHandlers["https://bmcl/forge-installer.jar.sha1"] = { 200 to sha1.toByteArray() }
        }
        val e = expectThrows<ForgeInstallException> {
            InstallerJar.download(
                transport = transport,
                installerUrls = listOf("https://bmcl/forge-installer.jar", "https://official/forge-installer.jar"),
                sha1Urls = listOf("https://bmcl/forge-installer.jar.sha1"),
                target = File(temp.root, "both-fail.jar"),
            )
        }
        // 两个源的失败原因都要带出（sha1 不匹配 / HTTP 500）
        assertTrue(e.message!!.contains("sha1 校验失败"))
        assertTrue(e.message!!.contains("HTTP 500"))
    }
}
