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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

@Suppress("NonAsciiCharacters")
class OfflineAccountTest {

    @Test
    fun `离线 UUID 具有 version 3 与 variant 2 特征位`() {
        val uuid = OfflineAccount.uuidForBytes("Steve")

        assertEquals(3, uuid.version())
        assertEquals(2, uuid.variant())
        assertTrue(OfflineAccount.isOfflineUuid(uuid))
        assertTrue(OfflineAccount.isOfflineUuid(uuid.toString()))
    }

    @Test
    fun `与规范 nameUUIDFromBytes OfflinePlayer 前缀一致`() {
        val expected = UUID.nameUUIDFromBytes("OfflinePlayer:Steve".toByteArray(Charsets.UTF_8))

        assertEquals(expected, OfflineAccount.uuidForBytes("Steve"))
        assertEquals(expected.toString(), OfflineAccount.uuidFor("Steve"))
        assertEquals("OfflinePlayer:", OfflineAccount.UUID_PREFIX)
    }

    @Test
    fun `同名确定不同名不同`() {
        assertEquals(OfflineAccount.uuidFor("Alex"), OfflineAccount.uuidFor("Alex"))
        assertNotEquals(OfflineAccount.uuidFor("Alex"), OfflineAccount.uuidFor("Steve"))
    }

    @Test
    fun `随机 v4 UUID 与非法文本都不是离线 UUID`() {
        assertFalse(OfflineAccount.isOfflineUuid(UUID.randomUUID()))
        assertFalse(OfflineAccount.isOfflineUuid("not-a-uuid"))
    }
}
