package com.vaelin.startup.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内存档位判定（规格书第 48 条：4GB 以下自动降档）。
 * 纯 JVM 逻辑，不需要设备。
 */
class MemoryTiersTest {

    @Test
    fun `4GB 以下算低端机`() {
        assertTrue(MemoryTiers.isLowEndDevice(3_800))
        assertTrue(MemoryTiers.isLowEndDevice(4_095))
        assertFalse(MemoryTiers.isLowEndDevice(4_096))
        assertFalse(MemoryTiers.isLowEndDevice(12_288))
    }

    @Test
    fun `默认堆大小随总内存单调不减`() {
        val samples = listOf(1_536L, 2_048L, 3_072L, 4_096L, 6_144L, 8_192L, 16_384L)
        val heaps = samples.map { MemoryTiers.defaultHeapMb(it) }
        heaps.zipWithNext().forEach { (a, b) ->
            assertTrue("heap must not shrink as RAM grows: $heaps", a <= b)
        }
    }

    @Test
    fun `最小堆不低于 512MB`() {
        // 低于这个数值 MC 1.20 起不来，参数再小也没有意义
        assertTrue(MemoryTiers.defaultHeapMb(1_024) >= 512)
        assertEquals(512, MemoryTiers.defaultHeapMb(1_024))
    }

    @Test
    fun `最大堆不超过 4GB`() {
        // 甚至 8GB 内存的设备也不建议给 4GB 以上的堆，GC 停顿会明显变长
        assertEquals(4_096, MemoryTiers.defaultHeapMb(32_768))
    }
}
