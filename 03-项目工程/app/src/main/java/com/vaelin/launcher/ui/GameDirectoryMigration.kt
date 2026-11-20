package com.vaelin.launcher.ui

import android.os.StatFs
import android.util.AtomicFile
import com.vaelin.startup.profile.GameDirectoryMode
import com.vaelin.startup.profile.GameDirectoryResolver
import com.vaelin.startup.profile.ProfileStore
import com.vaelin.startup.profile.VersionProfile
import com.vaelin.startup.util.GameDirectoryOperationLock
import com.vaelin.startup.util.VPaths
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

/** One shared-root mutable game file. Core launcher files are excluded. */
data class MigrationFile(
    val relativePath: String,
    val category: MigrationCategory,
    val sizeBytes: Long,
    val sha256: String,
)

data class MigrationTarget(
    val profileId: String,
    val profileName: String,
    val gameDirectory: File,
    val existingUserFiles: List<MigrationFile>,
    val existingUserFileCount: Int,
    val existingUserBytes: Long,
)

data class MigrationCategorySummary(
    val category: MigrationCategory,
    val fileCount: Int,
    val bytes: Long,
)

data class MigrationConflict(
    val profileId: String,
    val profileName: String,
    val relativePath: String,
    val category: MigrationCategory,
    val sourceFile: File,
    val targetFile: File,
)

/** Categories and destination profiles are selected independently per category. */
data class IsolationMigrationSelection(
    val categories: Set<MigrationCategory> = emptySet(),
    val targetsByCategory: Map<MigrationCategory, Set<String>> = emptyMap(),
    /** Explicitly permit switching while leaving every shared file at the old shared root. */
    val keepUnselectedDataInSharedRoot: Boolean = false,
)

data class MigrationTargetCopy(
    val category: MigrationCategory,
    val target: MigrationTarget,
)

data class IsolationMigrationPreview(
    val operationId: String,
    val profileIds: List<String>,
    val targets: List<MigrationTarget>,
    val categories: List<MigrationCategorySummary>,
    val files: List<MigrationFile>,
    val conflicts: List<MigrationConflict>,
    val identicalTargetKeys: Set<String>,
    val sourceRoot: File,
    val backupRoot: File,
    val availableBackupBytes: Long,
    val availableTargetBytes: Long,
    val backupAndTargetShareFileStore: Boolean,
    val selection: IsolationMigrationSelection,
) {
    val selectedFiles: List<MigrationFile>
        get() = files.filter { it.category in selection.categories && selection.targetsByCategory[it.category].orEmpty().isNotEmpty() }

    val selectedCopies: List<MigrationTargetCopy>
        get() = selection.categories.flatMap { category ->
            selection.targetsByCategory[category].orEmpty().mapNotNull { id ->
                targets.firstOrNull { it.profileId == id }?.let { MigrationTargetCopy(category, it) }
            }
        }

    val selectedConflicts: List<MigrationConflict>
        get() = conflicts.filter { conflict ->
            conflict.category in selection.categories &&
                conflict.profileId in selection.targetsByCategory[conflict.category].orEmpty()
        }

    val selectedSourceBytes: Long
        get() = sumBytes(selectedFiles)

    val estimatedBackupBytes: Long
        get() = if (selectedCopies.isEmpty()) 0L else selectedSourceBytes

    val estimatedTargetBytes: Long
        get() = selectedCopies.sumOf { copy ->
            selectedFiles.filter { it.category == copy.category }
                .filterNot { key(copy.target.profileId, copy.category, it.relativePath) in identicalTargetKeys }
                .fold(0L) { total, file -> Math.addExact(total, file.sizeBytes) }
        }

    /** A single per-file `.tmp` copy may coexist with both retained backup and destination copy. */
    val estimatedTransientBytes: Long
        get() = if (selectedCopies.isEmpty()) 0L else selectedFiles.maxOfOrNull { it.sizeBytes } ?: 0L

    val estimatedRequiredBytes: Long
        get() = Math.addExact(Math.addExact(estimatedBackupBytes, estimatedTargetBytes), estimatedTransientBytes)

    val requiredSpaceAvailable: Boolean
        get() = if (backupAndTargetShareFileStore) {
            estimatedRequiredBytes <= minOf(availableBackupBytes, availableTargetBytes)
        } else {
            Math.addExact(estimatedBackupBytes, estimatedTransientBytes) <= availableBackupBytes &&
                Math.addExact(estimatedTargetBytes, estimatedTransientBytes) <= availableTargetBytes
        }

    val hasRequiredSelection: Boolean
        get() = files.isEmpty() || (
            selection.categories.all { selection.targetsByCategory[it].orEmpty().isNotEmpty() } &&
                (selection.categories.isNotEmpty() || selection.keepUnselectedDataInSharedRoot)
            )

    val canRun: Boolean
        get() = hasRequiredSelection && requiredSpaceAvailable && selectedConflicts.isEmpty()

    companion object {
        internal fun key(profileId: String, category: MigrationCategory, relativePath: String): String =
            "$profileId\u0000${category.name}\u0000$relativePath"

        private fun sumBytes(files: List<MigrationFile>): Long = files.fold(0L) { total, file ->
            Math.addExact(total, file.sizeBytes)
        }
    }
}

data class IsolationMigrationResult(
    val copiedFiles: Int,
    val skippedIdenticalFiles: Int,
    val migratedFiles: Int,
    val backupRoot: File,
)

/**
 * Preview-first, category/profile-selective shared -> ZL2-style isolation migration.
 * Source data is never moved or removed. Existing destination files are never overwritten.
 */
object GameDirectoryMigrator {
    private val sharedCoreRoots = setOf(
        "assets",
        "libraries",
        "versions",
        "runtimes",
        "jna_dir",
        "launch_plans",
    )
    private val safeProfileId = Regex("[A-Za-z0-9._-]+")

    fun preview(gameHome: File, profiles: List<VersionProfile>, filesPrivate: File): IsolationMigrationPreview {
        val sourceRoot = gameHome.canonicalFile
        require(sourceRoot.isDirectory) { "游戏根目录不存在：$sourceRoot" }
        val files = listUserFiles(sourceRoot)
        val operationId = "isolate-${UUID.randomUUID()}"
        val backupRoot = File(File(filesPrivate, "storage-backups"), operationId)
        val conflicts = ArrayList<MigrationConflict>()
        val identicalTargetKeys = LinkedHashSet<String>()

        val targets = profiles.map { profile ->
            val targetRoot = isolatedProfileRoot(sourceRoot, profile)
            val existing = listUserFiles(targetRoot, profile.id)
            files.forEach { entry ->
                val source = safeChild(sourceRoot, entry.relativePath, sourceRoot)
                val targetDisplayPath = File(targetRoot, entry.relativePath).absoluteFile
                if (hasSymbolicLinkPath(targetRoot, entry.relativePath)) {
                    conflicts += MigrationConflict(profile.id, profile.name, entry.relativePath, entry.category, source, targetDisplayPath)
                } else {
                    val target = safeChild(targetRoot, entry.relativePath, sourceRoot)
                    if (target.exists() && !target.isFile) {
                        conflicts += MigrationConflict(profile.id, profile.name, entry.relativePath, entry.category, source, targetDisplayPath)
                    } else if (target.isFile) {
                        if (target.length() == entry.sizeBytes && sha256(target) == entry.sha256) {
                            identicalTargetKeys += IsolationMigrationPreview.key(profile.id, entry.category, entry.relativePath)
                        } else {
                            conflicts += MigrationConflict(profile.id, profile.name, entry.relativePath, entry.category, source, targetDisplayPath)
                        }
                    }
                }
            }
            MigrationTarget(
                profileId = profile.id,
                profileName = profile.name,
                gameDirectory = targetRoot,
                existingUserFiles = existing,
                existingUserFileCount = existing.size,
                existingUserBytes = existing.fold(0L) { total, file -> Math.addExact(total, file.sizeBytes) },
            )
        }

        val categories = MigrationCategory.entries.map { category ->
            val categoryFiles = files.filter { it.category == category }
            MigrationCategorySummary(
                category = category,
                fileCount = categoryFiles.size,
                bytes = categoryFiles.fold(0L) { total, file -> Math.addExact(total, file.sizeBytes) },
            )
        }
        val availableBackup = StatFs(filesPrivate.canonicalPath).availableBytes
        val availableTarget = StatFs(sourceRoot.path).availableBytes
        val sameFileStore = runCatching {
            Files.getFileStore(filesPrivate.canonicalFile.toPath()) == Files.getFileStore(sourceRoot.toPath())
        }.getOrDefault(false)
        return IsolationMigrationPreview(
            operationId = operationId,
            profileIds = profiles.map { it.id },
            targets = targets,
            categories = categories,
            files = files,
            conflicts = conflicts,
            identicalTargetKeys = identicalTargetKeys,
            sourceRoot = sourceRoot,
            backupRoot = backupRoot,
            availableBackupBytes = availableBackup,
            availableTargetBytes = availableTarget,
            backupAndTargetShareFileStore = sameFileStore,
            selection = IsolationMigrationSelection(),
        )
    }

    fun execute(
        preview: IsolationMigrationPreview,
        profiles: List<VersionProfile>,
        filesPrivate: File,
        selection: IsolationMigrationSelection = preview.selection,
        onProgress: (completed: Int, total: Int, detail: String) -> Unit = { _, _, _ -> },
        onBeforeRelease: () -> Unit = {},
    ): IsolationMigrationResult = GameDirectoryOperationLock.withLock(File(filesPrivate, "game-directory.lock")) {
        val result = executeLocked(preview, profiles, filesPrivate, selection, onProgress)
        onBeforeRelease()
        result
    }

    private fun executeLocked(
        preview: IsolationMigrationPreview,
        profiles: List<VersionProfile>,
        filesPrivate: File,
        selection: IsolationMigrationSelection,
        onProgress: (completed: Int, total: Int, detail: String) -> Unit,
    ): IsolationMigrationResult {
        require(VPaths.gameDirectoryMode == GameDirectoryMode.SHARED) { "全局游戏目录模式已改变，请重新生成迁移预览" }
        val selectedPreview = preview.copy(selection = selection)
        require(selectedPreview.canRun) {
            when {
                !selectedPreview.requiredSpaceAvailable -> "可用空间不足：备份 ${selectedPreview.estimatedBackupBytes} bytes + 目标 ${selectedPreview.estimatedTargetBytes} bytes + 临时文件 ${selectedPreview.estimatedTransientBytes} bytes，可用 ${minOf(preview.availableBackupBytes, preview.availableTargetBytes)} bytes"
                selectedPreview.selectedConflicts.isNotEmpty() -> "所选迁移范围存在同名异内容冲突；未覆盖任何目标文件。请取消冲突分类或先手动处理目标后重新预览。"
                !selectedPreview.hasRequiredSelection -> "请选择至少一个数据类别和一个目标实例；如不迁移数据，请先移除共享根内的用户数据或再调整方案。"
                else -> "迁移预览未通过校验"
            }
        }
        require(selection.categories.all { category -> preview.categories.any { it.category == category && it.fileCount > 0 } }) {
            "选择中包含本次预览里没有文件的数据类别，请重新盘点共享目录"
        }
        require(selection.targetsByCategory.keys.all { it in selection.categories }) {
            "目标profile必须属于已选数据类别"
        }
        require(selection.targetsByCategory.values.flatten().all { profileId -> profileId in preview.profileIds }) {
            "选择中包含预览外的目标实例，请重新生成预览"
        }
        require(profiles.map { it.id } == preview.profileIds) { "实例列表在预览后发生变化，请重新生成预览" }
        val currentProfileIds = ProfileStore.load().map { it.id }
        require(currentProfileIds == preview.profileIds) { "迁移等待期间实例列表发生变化，请重新生成预览" }
        require(listUserFiles(preview.sourceRoot) == preview.files) { "共享目录在预览后发生变化，请重新生成预览" }

        val selectedFiles = selectedPreview.selectedFiles
        val selectedCopies = selectedPreview.selectedCopies
        val totalWork = if (selectedCopies.isEmpty()) 0 else {
            selectedFiles.size + selectedCopies.sumOf { copy -> selectedFiles.count { it.category == copy.category } }
        }
        val journal = AtomicFile(File(File(filesPrivate, "storage-migrations"), "${preview.operationId}.journal")).apply {
            baseFile.parentFile?.mkdirs()
        }
        writeJournal(journal, "BACKUP", 0, totalWork, selection)

        var copied = 0
        var skipped = 0
        var completed = 0
        val backupPayloadRoot = File(preview.backupRoot, "shared-user-data")
        if (selectedCopies.isNotEmpty()) preview.backupRoot.mkdirs()
        if (selectedCopies.isNotEmpty()) {
            selectedFiles.forEach { entry ->
                val source = safeChild(preview.sourceRoot, entry.relativePath, preview.sourceRoot)
                requireSourceUnchanged(source, entry)
                val backup = safeChild(backupPayloadRoot, entry.relativePath, filesPrivate)
                if (backup.isFile) {
                    require(backup.length() == entry.sizeBytes && sha256(backup) == entry.sha256) {
                        "已有备份文件校验失败：${entry.relativePath}"
                    }
                    skipped++
                } else {
                    copyVerified(source, backup, entry)
                    copied++
                }
                completed++
                writeJournal(journal, "BACKUP", completed, totalWork, selection)
                onProgress(completed, totalWork, "备份 ${entry.relativePath}")
            }
        }

        selectedCopies.forEach { copy ->
            val profile = copy.target
            selectedFiles.filter { it.category == copy.category }.forEach { entry ->
                val source = safeChild(preview.sourceRoot, entry.relativePath, preview.sourceRoot)
                val target = safeChild(profile.gameDirectory, entry.relativePath, preview.sourceRoot)
                requireSourceUnchanged(source, entry)
                if (Files.isSymbolicLink(target.toPath()) || (target.exists() && !target.isFile)) {
                    throw IOException("目标类型冲突：${profile.profileName}/${entry.relativePath}；未覆盖目标")
                }
                if (target.isFile) {
                    require(target.length() == entry.sizeBytes && sha256(target) == entry.sha256) {
                        "目标内容冲突：${profile.profileName}/${entry.relativePath}；未覆盖目标文件"
                    }
                    skipped++
                } else {
                    copyVerified(source, target, entry)
                    copied++
                }
                require(target.isFile && target.length() == entry.sizeBytes && sha256(target) == entry.sha256) {
                    "目标文件校验失败：${profile.profileName}/${entry.relativePath}"
                }
                completed++
                writeJournal(journal, "COPYING_AND_VERIFYING:${copy.category.name}:${profile.profileId}", completed, totalWork, selection)
                onProgress(completed, totalWork, "复制并校验 ${copy.category.title} · ${profile.profileName} · ${entry.relativePath}")
            }
        }

        writeJournal(journal, "READY_TO_SWITCH", totalWork, totalWork, selection)
        return IsolationMigrationResult(copied, skipped, selectedFiles.size, preview.backupRoot)
    }

    private fun listUserFiles(rootInput: File, isolatedProfileId: String? = null): List<MigrationFile> {
        val root = rootInput.canonicalFile
        if (!root.exists()) return emptyList()
        require(root.isDirectory) { "目录根不是目录：$root" }
        val children = root.listFiles()?.sortedBy { it.name }
            ?: throw IOException("无法枚举目录（迁移已阻止以避免漏迁文件）：$root")
        val result = ArrayList<MigrationFile>()
        children.filterNot { child ->
            child.name in sharedCoreRoots ||
                child.name == "minecraft_versions.json" ||
                child.name.startsWith(".vaelin-") ||
                (isolatedProfileId != null && isCoreVersionArtifact(child, isolatedProfileId))
        }.forEach { child -> collect(child, root, result) }
        return result.sortedBy { it.relativePath }
    }

    private fun isCoreVersionArtifact(file: File, profileId: String): Boolean =
        file.name == "$profileId.json" ||
            file.name == "$profileId.jar" ||
            file.name == "natives" ||
            file.name.startsWith("natives-")

    private fun collect(file: File, root: File, out: MutableList<MigrationFile>) {
        val canonicalRoot = root.canonicalFile
        val canonicalFile = file.canonicalFile
        require(canonicalFile.path == canonicalRoot.path || canonicalFile.path.startsWith(canonicalRoot.path + File.separator)) {
            "检测到目录中的路径逃逸：$file"
        }
        require(!Files.isSymbolicLink(file.toPath())) { "不支持迁移符号链接：$file" }
        when {
            file.isDirectory -> {
                val children = file.listFiles()?.sortedBy { it.name }
                    ?: throw IOException("无法枚举目录（迁移已阻止以避免漏迁文件）：$file")
                children.forEach { collect(it, root, out) }
            }
            file.isFile -> {
                val relative = root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
                out += MigrationFile(relative, MigrationCategory.fromRelativePath(relative), file.length(), sha256(file))
            }
            else -> throw IOException("遇到不可识别的特殊文件，迁移已阻止：$file")
        }
    }

    private fun isolatedProfileRoot(sharedRoot: File, profile: VersionProfile): File {
        require(profile.id.isNotBlank() && safeProfileId.matches(profile.id)) { "实例 ID 含有不安全路径字符：${profile.id}" }
        return GameDirectoryResolver.resolve(sharedRoot, profile, GameDirectoryMode.ISOLATED)
    }

    private fun hasSymbolicLinkPath(root: File, relativePath: String): Boolean {
        var cursor = root.absoluteFile
        for (segment in relativePath.split('/')) {
            cursor = File(cursor, segment)
            if (Files.isSymbolicLink(cursor.toPath())) return true
        }
        return false
    }

    private fun safeChild(root: File, relativePath: String, protectedBase: File): File {
        require(relativePath.isNotBlank() && !relativePath.startsWith('/') && !relativePath.contains('\\')) {
            "非法迁移相对路径：$relativePath"
        }
        val segments = relativePath.split('/')
        require(segments.none { it.isBlank() || it == "." || it == ".." }) { "非法迁移相对路径：$relativePath" }
        val basePath = protectedBase.canonicalFile.toPath()
        val rootPath = root.absoluteFile.toPath().normalize()
        require(rootPath.startsWith(basePath)) { "迁移根超出受保护目录：$rootPath" }

        var cursor = basePath
        val rootSegments = basePath.relativize(rootPath)
        rootSegments.forEach { segment ->
            cursor = cursor.resolve(segment)
            require(!Files.isSymbolicLink(cursor)) { "迁移根路径包含符号链接：$cursor" }
        }
        val rootCanonical = root.canonicalFile
        require(rootCanonical.toPath() == rootPath) { "迁移根在盘点后改变了规范路径：$root" }
        segments.forEach { segment ->
            cursor = cursor.resolve(segment)
            require(!Files.isSymbolicLink(cursor)) { "迁移路径包含符号链接：$relativePath" }
        }
        val child = cursor.toFile().canonicalFile
        require(child.path.startsWith(rootCanonical.path + File.separator)) { "迁移路径逃逸：$relativePath" }
        return child
    }

    private fun requireSourceUnchanged(source: File, expected: MigrationFile) {
        require(source.isFile && source.length() == expected.sizeBytes && sha256(source) == expected.sha256) {
            "源文件在迁移过程中发生变化：${expected.relativePath}；共享模式保持不变"
        }
    }

    private fun copyVerified(source: File, target: File, expected: MigrationFile) {
        requireSourceUnchanged(source, expected)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            BufferedInputStream(FileInputStream(source)).use { input ->
                BufferedOutputStream(FileOutputStream(temp)).use { output -> input.copyTo(output) }
            }
            require(temp.length() == expected.sizeBytes && sha256(temp) == expected.sha256) {
                "复制后校验失败：${expected.relativePath}"
            }
            if (!temp.renameTo(target)) throw IOException("无法提交暂存文件：${target.absolutePath}")
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(FileInputStream(file)).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun writeJournal(
        atomicFile: AtomicFile,
        stage: String,
        completed: Int,
        total: Int,
        selection: IsolationMigrationSelection,
    ) {
        val stream = atomicFile.startWrite()
        try {
            val text = buildString {
                append("stage=").append(stage).append('\n')
                append("completed=").append(completed).append('\n')
                append("total=").append(total).append('\n')
                append("categories=").append(selection.categories.map { it.name }.sorted().joinToString(",")).append('\n')
                append("keepUnselectedDataInSharedRoot=").append(selection.keepUnselectedDataInSharedRoot).append('\n')
                selection.targetsByCategory.toSortedMap(compareBy { it.name }).forEach { (category, profileIds) ->
                    append("targets.").append(category.name).append('=')
                        .append(profileIds.sorted().joinToString(",")).append('\n')
                }
            }
            stream.write(text.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(stream)
        } catch (t: Throwable) {
            atomicFile.failWrite(stream)
            throw t
        }
    }
}
