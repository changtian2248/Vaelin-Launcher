package com.vaelin.startup.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GameDirectoryResolverTest {
    @org.junit.Rule
    @JvmField
    val temp = TemporaryFolder()

    @Test
    fun `shared mode preserves legacy root`() {
        val shared = temp.newFolder("games")
        assertEquals(shared.canonicalFile, GameDirectoryResolver.resolve(shared, "old-profile", GameDirectoryMode.SHARED))
    }

    @Test
    fun `isolation follows ZL2 versions profile directory`() {
        val shared = temp.newFolder("games")
        assertEquals(
            java.io.File(shared, "versions/my-profile-1.20.1").canonicalFile,
            GameDirectoryResolver.resolve(shared, "my-profile-1.20.1", GameDirectoryMode.ISOLATED),
        )
    }

    @Test
    fun `isolated mode rejects path traversal ids`() {
        val shared = temp.newFolder("games")
        assertThrows(IllegalArgumentException::class.java) {
            GameDirectoryResolver.resolve(shared, "../escape", GameDirectoryMode.ISOLATED)
        }
    }

    @Test
    fun `isolated profile rejects collision with unrelated shared version manifest`() {
        val shared = temp.newFolder("games")
        val versions = java.io.File(shared, "versions").apply { mkdirs() }
        val profileDir = java.io.File(versions, "1.20.1").apply { mkdirs() }
        java.io.File(profileDir, "1.20.1.json").writeText("{}")
        val profile = VersionProfile(id = "1.20.1", minecraftVersion = "1.21")

        assertThrows(IllegalArgumentException::class.java) {
            GameDirectoryResolver.resolve(shared, profile, GameDirectoryMode.ISOLATED)
        }
    }
}
