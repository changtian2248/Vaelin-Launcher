package com.vaelin.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileSlugTest {
    @Test
    fun uniqueIdSkipsExistingProfileIdsAndOrphanDirectories() {
        val taken = setOf("1.20.1-pack", "1.20.1-pack-2")
        val id = uniqueProfileId(taken, "1.20.1-pack") { candidate ->
            candidate == "1.20.1-pack-3"
        }
        assertEquals("1.20.1-pack-4", id)
    }

    @Test
    fun slugifyProducesSafePathComponent() {
        assertEquals("1.20.1---test-pack", slugifyProfileId("1.20.1 / Test Pack"))
    }
}
