package com.vaelin.launcher.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IsolationMigrationSelectionTest {
    private fun preview(
        selection: IsolationMigrationSelection,
        identicalTargetKeys: Set<String> = emptySet(),
        conflicts: List<MigrationConflict> = emptyList(),
        availableBackupBytes: Long = 10_000,
        availableTargetBytes: Long = 10_000,
        backupAndTargetShareFileStore: Boolean = true,
    ): IsolationMigrationPreview {
        val targetOne = MigrationTarget("profile-one", "Profile One", File("/games/versions/profile-one"), emptyList(), 0, 0)
        val targetTwo = MigrationTarget("profile-two", "Profile Two", File("/games/versions/profile-two"), emptyList(), 0, 0)
        val files = listOf(
            MigrationFile("mods/a.jar", MigrationCategory.MODS, 100, "a"),
            MigrationFile("saves/world/level.dat", MigrationCategory.WORLDS, 200, "b"),
        )
        return IsolationMigrationPreview(
            operationId = "test",
            profileIds = listOf("profile-one", "profile-two"),
            targets = listOf(targetOne, targetTwo),
            categories = listOf(
                MigrationCategorySummary(MigrationCategory.MODS, 1, 100),
                MigrationCategorySummary(MigrationCategory.WORLDS, 1, 200),
            ),
            files = files,
            conflicts = conflicts,
            identicalTargetKeys = identicalTargetKeys,
            sourceRoot = File("/games"),
            backupRoot = File("/backups/test"),
            availableBackupBytes = availableBackupBytes,
            availableTargetBytes = availableTargetBytes,
            backupAndTargetShareFileStore = backupAndTargetShareFileStore,
            selection = selection,
        )
    }

    @Test
    fun selectedCategoriesAndPerCategoryTargetsDetermineCopyAndSpaceTotals() {
        val preview = preview(
            IsolationMigrationSelection(
                categories = setOf(MigrationCategory.MODS, MigrationCategory.WORLDS),
                targetsByCategory = mapOf(
                    MigrationCategory.MODS to setOf("profile-one", "profile-two"),
                    MigrationCategory.WORLDS to setOf("profile-one"),
                ),
            ),
        )

        assertTrue(preview.canRun)
        assertEquals(3, preview.selectedCopies.size)
        assertEquals(300L, preview.estimatedBackupBytes)
        assertEquals(400L, preview.estimatedTargetBytes)
        assertEquals(200L, preview.estimatedTransientBytes)
        assertEquals(900L, preview.estimatedRequiredBytes)
    }

    @Test
    fun selectedCategoryRequiresAtLeastOneDestination() {
        val preview = preview(
            IsolationMigrationSelection(
                categories = setOf(MigrationCategory.MODS),
                targetsByCategory = mapOf(MigrationCategory.MODS to emptySet()),
            ),
        )

        assertFalse(preview.hasRequiredSelection)
        assertFalse(preview.canRun)
    }

    @Test
    fun explicitlyLeavingAllLegacyDataInSharedRootAllowsNoCopySwitch() {
        val preview = preview(
            IsolationMigrationSelection(keepUnselectedDataInSharedRoot = true),
        )

        assertTrue(preview.hasRequiredSelection)
        assertTrue(preview.canRun)
        assertTrue(preview.selectedCopies.isEmpty())
        assertEquals(0L, preview.estimatedRequiredBytes)
    }

    @Test
    fun unselectedCategoryDoesNotInflateMigrationFootprint() {
        val preview = preview(
            IsolationMigrationSelection(
                categories = setOf(MigrationCategory.MODS),
                targetsByCategory = mapOf(MigrationCategory.MODS to setOf("profile-two")),
            ),
        )

        assertEquals(100L, preview.selectedSourceBytes)
        assertEquals(100L, preview.estimatedBackupBytes)
        assertEquals(100L, preview.estimatedTargetBytes)
        assertEquals(100L, preview.estimatedTransientBytes)
        assertEquals(300L, preview.estimatedRequiredBytes)
    }

    @Test
    fun identicalExistingTargetDoesNotInflateRequiredBytes() {
        val preview = preview(
            selection = IsolationMigrationSelection(
                categories = setOf(MigrationCategory.MODS),
                targetsByCategory = mapOf(MigrationCategory.MODS to setOf("profile-one")),
            ),
            identicalTargetKeys = setOf(
                IsolationMigrationPreview.key("profile-one", MigrationCategory.MODS, "mods/a.jar"),
            ),
        )

        assertEquals(100L, preview.estimatedBackupBytes)
        assertEquals(0L, preview.estimatedTargetBytes)
        assertEquals(100L, preview.estimatedTransientBytes)
        assertEquals(200L, preview.estimatedRequiredBytes)
        assertTrue(preview.canRun)
    }

    @Test
    fun perFilesystemSpaceChecksBlockWhenEitherSideIsShort() {
        val selection = IsolationMigrationSelection(
            categories = setOf(MigrationCategory.MODS),
            targetsByCategory = mapOf(MigrationCategory.MODS to setOf("profile-one")),
        )
        assertFalse(preview(selection, availableBackupBytes = 99).canRun)
        assertFalse(preview(selection, availableTargetBytes = 99).canRun)
    }

    @Test
    fun differentBackupAndTargetStoresCheckTheirIndependentPeakUsage() {
        val selection = IsolationMigrationSelection(
            categories = setOf(MigrationCategory.MODS),
            targetsByCategory = mapOf(MigrationCategory.MODS to setOf("profile-one")),
        )
        val preview = preview(
            selection,
            availableBackupBytes = 250,
            availableTargetBytes = 250,
            backupAndTargetShareFileStore = false,
        )

        assertTrue(preview.canRun)
        assertEquals(200L, preview.estimatedBackupBytes + preview.estimatedTransientBytes)
        assertEquals(200L, preview.estimatedTargetBytes + preview.estimatedTransientBytes)
    }

    @Test
    fun onlyConflictsInSelectedCategoryAndProfileBlockMigration() {
        val conflict = MigrationConflict(
            profileId = "profile-one",
            profileName = "Profile One",
            relativePath = "mods/a.jar",
            category = MigrationCategory.MODS,
            sourceFile = File("/games/mods/a.jar"),
            targetFile = File("/games/versions/profile-one/mods/a.jar"),
        )
        val selectedMods = IsolationMigrationSelection(
            categories = setOf(MigrationCategory.MODS),
            targetsByCategory = mapOf(MigrationCategory.MODS to setOf("profile-one")),
        )
        val selectedWorlds = IsolationMigrationSelection(
            categories = setOf(MigrationCategory.WORLDS),
            targetsByCategory = mapOf(MigrationCategory.WORLDS to setOf("profile-one")),
        )

        assertFalse(preview(selectedMods, conflicts = listOf(conflict)).canRun)
        assertTrue(preview(selectedWorlds, conflicts = listOf(conflict)).canRun)
    }
}
