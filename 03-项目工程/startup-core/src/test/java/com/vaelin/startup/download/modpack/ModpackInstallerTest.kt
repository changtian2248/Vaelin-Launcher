/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.vaelin.startup.download.modpack

import com.vaelin.startup.download.FakeHttpTransport
import com.vaelin.startup.download.sha1Of
import com.vaelin.startup.download.source.RegionDetector
import com.vaelin.startup.download.source.RegionSignals
import com.vaelin.startup.download.source.SourceGate
import com.vaelin.startup.download.source.SourceGateException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModpackInstallerTest {

    private fun sha1Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun tempDir(tag: String): File =
        File(System.getProperty("java.io.tmpdir"), "vaelin-install-$tag-${System.nanoTime()}")
            .apply { mkdirs() }

    private fun gateOf(sim: String?) = SourceGate(RegionDetector(object : RegionSignals {
        override fun systemCountry(): String? = sim
        override fun simCountry(): String? = sim
    }))

    private fun buildMrpackZip(dir: File, goodContent: ByteArray, manifest: String): File {
        val zipFile = File(dir, "pack.mrpack")
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("modrinth.index.json"))
            zip.write(manifest.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("overrides/config/test.cfg"))
            zip.write("key=value".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("client-overrides/options.txt"))
            zip.write("fov:70".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return zipFile
    }

    @Test
    fun `install mrpack downloads files and extracts overrides`() {
        val dir = tempDir("happy")
        val good = "good-mod-bytes".toByteArray()
        val goodSha1 = sha1Hex(good)
        val manifest = """
            {
              "formatVersion": 1, "game": "minecraft", "versionId": "1.0", "name": "测试包",
              "files": [
                {"path": "mods/good.jar", "hashes": {"sha1": "$goodSha1", "sha512": "x"},
                 "downloads": ["https://cdn.test/good.jar"], "fileSize": ${good.size}}
              ],
              "dependencies": {"minecraft": "1.20.1", "forge": "47.2.0"}
            }
        """.trimIndent()
        val zipFile = buildMrpackZip(dir, good, manifest)

        val transport = FakeHttpTransport()
        transport.serve("https://cdn.test/good.jar", good)

        val installer = ModpackInstaller(transport, gateOf("US"))
        val inspected = installer.inspect(zipFile)
        assertEquals(ModpackFormat.MRPACK, inspected.format)
        assertEquals("47.2.0", inspected.info.loaderVersion)
        assertEquals(setOf("config/test.cfg", "options.txt"), inspected.overrides.toSet())

        val target = File(dir, "instance")
        val progress = mutableListOf<Pair<Int, Int>>()
        val result = installer.install(inspected, zipFile, target) { c, t -> progress += c to t }

        assertTrue(result.usable)
        assertEquals(1, result.succeeded)
        assertTrue(result.failedRequired.isEmpty())
        assertTrue(result.failedOptional.isEmpty())
        assertEquals(2, result.overridesExtracted)

        val goodFile = File(target, "mods/good.jar")
        assertTrue(goodFile.isFile)
        assertEquals(goodSha1, sha1Of(goodFile))
        assertEquals("key=value", File(target, "config/test.cfg").readText())
        assertEquals("fov:70", File(target, "options.txt").readText())
        assertFalse(File(target, "overrides").exists()) // 前缀已剥离，不残留目录
        assertTrue(progress.isNotEmpty())

        dir.deleteRecursively()
    }

    @Test
    fun `required failure lands in failedRequired and unusable`() {
        val dir = tempDir("reqfail")
        val manifest = """
            {
              "formatVersion": 1, "game": "minecraft", "versionId": "1.0", "name": "n",
              "files": [
                {"path": "mods/must.jar", "hashes": {"sha1": "${"0".repeat(40)}", "sha512": "x"},
                 "downloads": ["https://cdn.test/must.jar"], "fileSize": 1}
              ],
              "dependencies": {"minecraft": "1.20.1"}
            }
        """.trimIndent()
        val zipFile = buildMrpackZip(dir, ByteArray(0), manifest)
        val transport = FakeHttpTransport()
        transport.fail("https://cdn.test/must.jar", 500)

        val installer = ModpackInstaller(transport, gateOf("US"))
        val inspected = installer.inspect(zipFile)
        val result = installer.install(inspected, zipFile, File(dir, "instance"))

        assertFalse(result.usable)
        assertEquals(1, result.failedRequired.size)
        dir.deleteRecursively()
    }

    @Test
    fun `gate blocks install on mainland`() {
        val dir = tempDir("gate")
        val manifest = """
            {"formatVersion":1,"game":"minecraft","versionId":"1.0","name":"n","files":[],
             "dependencies":{"minecraft":"1.20.1"}}
        """.trimIndent()
        val zipFile = buildMrpackZip(dir, ByteArray(0), manifest)
        val installer = ModpackInstaller(FakeHttpTransport(), gateOf("CN"))
        val inspected = installer.inspect(zipFile)
        try {
            installer.install(inspected, zipFile, File(dir, "instance"))
            fail("大陆地区应被地区闸拦截")
        } catch (e: SourceGateException) {
            assertTrue(e.message!!.contains("不开放"))
        }
        dir.deleteRecursively()
    }

    @Test
    fun `unknown format rejected at inspect`() {
        val dir = tempDir("unknown")
        val zipFile = File(dir, "plain.zip")
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("readme.txt")); zip.write("hi".toByteArray()); zip.closeEntry()
        }
        val installer = ModpackInstaller(FakeHttpTransport(), gateOf("US"))
        try {
            installer.inspect(zipFile)
            fail("应拒绝未知格式")
        } catch (e: ModpackParseException) {
            assertTrue(e.message!!.contains("无法识别"))
        }
        dir.deleteRecursively()
    }
}
