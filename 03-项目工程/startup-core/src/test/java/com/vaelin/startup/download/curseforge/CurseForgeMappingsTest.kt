/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.vaelin.startup.download.curseforge

import com.vaelin.startup.download.source.AddonKind
import com.vaelin.startup.download.source.AddonSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurseForgeMappingsTest {

    @Test
    fun `classId round trip`() {
        assertEquals(6, CurseForgeClassIds.fromKind(AddonKind.MOD))
        assertEquals(4471, CurseForgeClassIds.fromKind(AddonKind.MODPACK))
        assertEquals(12, CurseForgeClassIds.fromKind(AddonKind.RESOURCE_PACK))
        assertEquals(6552, CurseForgeClassIds.fromKind(AddonKind.SHADER))
        assertNull(CurseForgeClassIds.fromKind(AddonKind.DATA_PACK))
        assertNull(CurseForgeClassIds.fromKind(AddonKind.WORLD))

        assertEquals(AddonKind.MOD, CurseForgeClassIds.toKind(6))
        assertEquals(AddonKind.MODPACK, CurseForgeClassIds.toKind(4471))
        assertEquals(AddonKind.SHADER, CurseForgeClassIds.toKind(6552))
        assertEquals(AddonKind.MOD, CurseForgeClassIds.toKind(null))
    }

    @Test
    fun `sort field codes`() {
        assertEquals(1, CurseForgeSortFields.fromSort(AddonSort.RELEVANCE))
        assertEquals(2, CurseForgeSortFields.fromSort(AddonSort.FOLLOWS))
        assertEquals(3, CurseForgeSortFields.fromSort(AddonSort.UPDATED))
        assertEquals(6, CurseForgeSortFields.fromSort(AddonSort.DOWNLOADS))
        assertEquals(11, CurseForgeSortFields.fromSort(AddonSort.NEWEST))
    }

    @Test
    fun `loader type codes`() {
        assertEquals(1, CurseForgeLoaderTypes.fromLoaderName("forge"))
        assertEquals(4, CurseForgeLoaderTypes.fromLoaderName("Fabric"))
        assertEquals(5, CurseForgeLoaderTypes.fromLoaderName("quilt"))
        assertEquals(6, CurseForgeLoaderTypes.fromLoaderName("NEOFORGE"))
        assertNull(CurseForgeLoaderTypes.fromLoaderName("unknown"))
    }

    @Test
    fun `loader name detection in gameVersions entries`() {
        assertTrue(CurseForgeLoaderTypes.isLoaderName("Forge"))
        assertTrue(CurseForgeLoaderTypes.isLoaderName("fabric"))
        assertTrue(CurseForgeLoaderTypes.isLoaderName("NeoForge"))
        assertTrue(!CurseForgeLoaderTypes.isLoaderName("1.20.1"))
        assertEquals("neoforge", CurseForgeLoaderTypes.normalizeLoaderName("NeoForge"))
    }

    @Test
    fun `release type codes`() {
        assertEquals("release", CurseForgeReleaseTypes.fromCode(1))
        assertEquals("beta", CurseForgeReleaseTypes.fromCode(2))
        assertEquals("alpha", CurseForgeReleaseTypes.fromCode(3))
        assertEquals("alpha", CurseForgeReleaseTypes.fromCode(99))
    }
}
