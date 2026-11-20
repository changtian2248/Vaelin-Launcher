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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModpackDetectTest {

    private fun tempZip(name: String, entries: Map<String, String>): File {
        val file = File(System.getProperty("java.io.tmpdir"), "vaelin-detect-$name-${System.nanoTime()}.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `detect mrpack by index entry`() {
        val zip = tempZip("mrpack", mapOf(
            "modrinth.index.json" to """{"formatVersion":1,"game":"minecraft","versionId":"v","name":"n","files":[],"dependencies":{"minecraft":"1.20.1"}}""",
            "overrides/config/x.cfg" to "a=1",
        ))
        assertEquals(ModpackFormat.MRPACK, ModpackDetect.detect(zip))
        zip.delete()
    }

    @Test
    fun `detect curseforge by manifest type`() {
        val zip = tempZip("cf", mapOf(
            "manifest.json" to """{"manifestType":"minecraftModpack","manifestVersion":1,"name":"n","version":"v","author":"a","minecraft":{"version":"1.20.1","modLoaders":[]},"files":[]}""",
        ))
        assertEquals(ModpackFormat.CURSEFORGE_ZIP, ModpackDetect.detect(zip))
        zip.delete()
    }

    @Test
    fun `manifest json with other type is unknown`() {
        val zip = tempZip("other", mapOf(
            "manifest.json" to """{"manifestType":"somethingElse"}""",
        ))
        assertEquals(ModpackFormat.UNKNOWN, ModpackDetect.detect(zip))
        zip.delete()
    }

    @Test
    fun `plain zip is unknown`() {
        val zip = tempZip("plain", mapOf("readme.txt" to "hi"))
        assertEquals(ModpackFormat.UNKNOWN, ModpackDetect.detect(zip))
        zip.delete()
    }

    @Test
    fun `prefixed entries listed with prefix stripped and unsafe filtered`() {
        val zip = tempZip("prefixed", mapOf(
            "overrides/config/a.cfg" to "1",
            "overrides/b.txt" to "2",
            "overrides/../evil.txt" to "3",
            "client-overrides/c.txt" to "4",
            "unrelated/d.txt" to "5",
        ))
        val listed = ModpackDetect.listPrefixedEntries(zip, listOf("overrides", "client-overrides"))
        // ../evil 被剔除；unrelated 不在前缀内
        assertEquals(setOf("config/a.cfg", "b.txt", "c.txt"), listed.toSet())
        zip.delete()
    }

    @Test
    fun `safe relative path rules`() {
        assertTrue(ModpackDetect.isSafeRelativePath("mods/a.jar"))
        assertTrue(ModpackDetect.isSafeRelativePath("config\\b.cfg")) // 反斜杠归一化后合法
        assertFalse(ModpackDetect.isSafeRelativePath("../evil"))
        assertFalse(ModpackDetect.isSafeRelativePath("mods/../../evil"))
        assertFalse(ModpackDetect.isSafeRelativePath("/abs/path"))
    }
}
