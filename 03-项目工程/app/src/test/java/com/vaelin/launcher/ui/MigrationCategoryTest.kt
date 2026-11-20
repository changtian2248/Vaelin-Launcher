package com.vaelin.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MigrationCategoryTest {
    @Test
    fun classifiesMinecraftMutableFolders() {
        assertEquals(MigrationCategory.MODS, MigrationCategory.fromRelativePath("mods/example.jar"))
        assertEquals(MigrationCategory.CONFIG, MigrationCategory.fromRelativePath("config/mod.toml"))
        assertEquals(MigrationCategory.CONFIG, MigrationCategory.fromRelativePath("defaultconfigs/mod-server.toml"))
        assertEquals(MigrationCategory.CONFIG, MigrationCategory.fromRelativePath("kubejs/server_scripts/main.js"))
        assertEquals(MigrationCategory.WORLDS, MigrationCategory.fromRelativePath("saves/world/level.dat"))
        assertEquals(MigrationCategory.RESOURCE_PACKS, MigrationCategory.fromRelativePath("resourcepacks/pack.zip"))
        assertEquals(MigrationCategory.SHADERS, MigrationCategory.fromRelativePath("shaderpacks/shader.zip"))
        assertEquals(MigrationCategory.SCREENSHOTS, MigrationCategory.fromRelativePath("screenshots/shot.png"))
    }

    @Test
    fun classifiesRootSettingsAndUnknownUserContent() {
        assertEquals(MigrationCategory.GAME_SETTINGS, MigrationCategory.fromRelativePath("options.txt"))
        assertEquals(MigrationCategory.GAME_SETTINGS, MigrationCategory.fromRelativePath("servers.dat"))
        assertEquals(MigrationCategory.OTHER, MigrationCategory.fromRelativePath("journeymap/data.json"))
    }
}
