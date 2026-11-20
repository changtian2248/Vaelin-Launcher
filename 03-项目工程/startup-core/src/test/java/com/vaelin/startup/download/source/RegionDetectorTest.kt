/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.vaelin.startup.download.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionDetectorTest {

    private fun detector(system: String?, sim: String?) = RegionDetector(object : RegionSignals {
        override fun systemCountry(): String? = system
        override fun simCountry(): String? = sim
    })

    @Test
    fun `system CN blocks`() {
        assertEquals(RegionVerdict.MAINLAND, detector("CN", null).verdict())
    }

    @Test
    fun `sim CN blocks`() {
        assertEquals(RegionVerdict.MAINLAND, detector(null, "CN").verdict())
    }

    @Test
    fun `lowercase cn normalizes and blocks`() {
        assertEquals(RegionVerdict.MAINLAND, detector("cn", null).verdict())
    }

    @Test
    fun `both overseas passes`() {
        assertEquals(RegionVerdict.OVERSEAS, detector("US", "US").verdict())
    }

    @Test
    fun `single overseas signal determines`() {
        assertEquals(RegionVerdict.OVERSEAS, detector("US", null).verdict())
        assertEquals(RegionVerdict.OVERSEAS, detector(null, "JP").verdict())
    }

    @Test
    fun `no signal defaults to mainland per spec`() {
        assertEquals(RegionVerdict.MAINLAND, detector(null, null).verdict())
    }

    @Test
    fun `conflicting signals fall to mainland conservative`() {
        assertEquals(RegionVerdict.MAINLAND, detector("CN", "US").verdict())
        assertEquals(RegionVerdict.MAINLAND, detector("US", "CN").verdict())
    }

    @Test
    fun `malformed signal treated as missing`() {
        // 非法格式（长度不是 2 位大写字母）按「拿不到」处理
        assertEquals(RegionVerdict.MAINLAND, detector("C", null).verdict())
        assertEquals(RegionVerdict.OVERSEAS, detector("US", "c n").verdict())
    }

    @Test
    fun `isMainland convenience`() {
        assertTrue(detector("CN", null).isMainland())
        assertFalse(detector("US", null).isMainland())
    }
}
