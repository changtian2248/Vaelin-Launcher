package com.vaelin.launcher.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.vaelin.startup.profile.VersionProfile
import com.vaelin.startup.util.GameDirectoryOperationLock
import com.vaelin.startup.util.VPaths
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Content categories supported by the current local file manager. */
enum class ManagedContentType(val directoryName: String, val label: String, val extensions: Set<String>) {
    MODS("mods", "模组", setOf(".jar")),
    SHADERS("shaderpacks", "光影", setOf(".zip", ".jar")),
    RESOURCE_PACKS("resourcepacks", "资源包", setOf(".zip")),
}

data class ManagedContentEntry(
    val file: File,
    val displayName: String,
    val enabled: Boolean,
    val sizeBytes: Long,
    val modifiedAt: Long,
)

data class InstalledContentState(
    val type: ManagedContentType = ManagedContentType.MODS,
    val entries: List<ManagedContentEntry> = emptyList(),
    val query: String = "",
    val loading: Boolean = false,
    val error: String? = null,
)

/** Instance-scoped file listing and management. Every filesystem mutation shares the storage migration lock. */
class InstalledContentViewModel(application: Application) : AndroidViewModel(application) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var generation = 0L
    private var activeProfile: VersionProfile? = null

    var state by mutableStateOf(InstalledContentState())
        private set

    val visibleEntries: List<ManagedContentEntry>
        get() = state.entries.filter { it.displayName.contains(state.query.trim(), ignoreCase = true) }

    fun load(profile: VersionProfile, type: ManagedContentType = state.type) {
        activeProfile = profile
        val requestGeneration = ++generation
        state = state.copy(type = type, entries = emptyList(), loading = true, error = null)
        worker.execute {
            VPaths.init(getApplication<Application>().applicationContext)
            val result = GameDirectoryOperationLock.withLock(VPaths.gameDirectoryLockFile) {
                runCatching {
                    val gameDirectory = VPaths.resolveGameDir(profile).canonicalFile
                    val directory = File(gameDirectory, type.directoryName).canonicalFile
                    require(directory.parentFile == gameDirectory &&
                        (directory.mkdirs() || directory.isDirectory) &&
                        directory.listFiles() != null
                    ) { "无法枚举实例目录：$directory" }
                    directory.listFiles()!!.asSequence()
                        .filter { it.isFile }
                        .filter { candidate -> isManagedFile(candidate.name, type) }
                        .map(::toEntry)
                        .sortedBy { it.displayName.lowercase() }
                        .toList()
                }
            }
            mainHandler.post {
                if (requestGeneration != generation) return@post
                state = result.fold(
                    onSuccess = { state.copy(entries = it, loading = false, error = null) },
                    onFailure = { state.copy(entries = emptyList(), loading = false, error = it.message ?: it.javaClass.simpleName) },
                )
            }
        }
    }

    fun onTypeChange(type: ManagedContentType) {
        if (state.type == type) return
        val profile = activeProfile ?: return
        state = state.copy(query = "")
        load(profile, type)
    }

    fun onQueryChange(query: String) {
        state = state.copy(query = query)
    }

    fun toggle(entry: ManagedContentEntry) {
        val profile = activeProfile ?: return
        val type = state.type
        val requestGeneration = ++generation
        state = state.copy(loading = true, error = null)
        worker.execute {
            VPaths.init(getApplication<Application>().applicationContext)
            val result = GameDirectoryOperationLock.withLock(VPaths.gameDirectoryLockFile) {
                runCatching {
                    val gameDirectory = VPaths.resolveGameDir(profile).canonicalFile
                    val directory = File(gameDirectory, type.directoryName).canonicalFile
                    require(directory.parentFile == gameDirectory && directory.isDirectory) { "实例内容目录无效：$directory" }
                    val source = entry.file.canonicalFile
                    require(source.parentFile == directory && source.isFile && isManagedFile(source.name, type)) {
                        "资源文件已移动或不属于此实例目录"
                    }
                    val targetName = if (entry.enabled) "${source.name}.disabled" else source.name.removeSuffix(".disabled")
                    val target = File(directory, targetName)
                    require(target.parentFile == directory) { "目标文件路径无效" }
                    require(!target.exists()) { "目标文件已存在：${target.name}" }
                    if (!source.renameTo(target)) throw IllegalStateException("切换启用状态失败：${source.name}")
                }
            }
            mainHandler.post {
                if (requestGeneration != generation) return@post
                if (result.isFailure) {
                    state = state.copy(loading = false, error = result.exceptionOrNull()?.message ?: "操作失败")
                } else {
                    load(profile, type)
                }
            }
        }
    }

    fun delete(entry: ManagedContentEntry, onComplete: (Result<Unit>) -> Unit = {}) {
        val profile = activeProfile ?: return
        val type = state.type
        val requestGeneration = ++generation
        state = state.copy(loading = true, error = null)
        worker.execute {
            VPaths.init(getApplication<Application>().applicationContext)
            val result = GameDirectoryOperationLock.withLock(VPaths.gameDirectoryLockFile) {
                runCatching {
                    val gameDirectory = VPaths.resolveGameDir(profile).canonicalFile
                    val directory = File(gameDirectory, type.directoryName).canonicalFile
                    require(directory.parentFile == gameDirectory && directory.isDirectory) { "实例内容目录无效：$directory" }
                    val source = entry.file.canonicalFile
                    require(source.parentFile == directory && source.isFile && isManagedFile(source.name, type)) {
                        "资源文件已移动或不属于此实例目录"
                    }
                    if (!source.delete()) throw IllegalStateException("删除失败：${source.name}")
                }
            }
            mainHandler.post {
                if (requestGeneration != generation) return@post
                if (result.isFailure) {
                    state = state.copy(loading = false, error = result.exceptionOrNull()?.message ?: "删除失败")
                    onComplete(Result.failure(result.exceptionOrNull()!!))
                } else {
                    onComplete(Result.success(Unit))
                    load(profile, type)
                }
            }
        }
    }

    private fun toEntry(file: File): ManagedContentEntry {
        val disabled = file.name.endsWith(".disabled", ignoreCase = true)
        val baseName = if (disabled) file.name.removeSuffix(".disabled") else file.name
        val displayName = baseName.substringBeforeLast('.', baseName)
        return ManagedContentEntry(
            file = file,
            displayName = displayName,
            enabled = !disabled,
            sizeBytes = file.length(),
            modifiedAt = file.lastModified(),
        )
    }

    private fun isManagedFile(name: String, type: ManagedContentType): Boolean {
        val actualName = if (name.endsWith(".disabled", ignoreCase = true)) name.removeSuffix(".disabled") else name
        return type.extensions.any { actualName.endsWith(it, ignoreCase = true) }
    }

    override fun onCleared() {
        generation += 1
        worker.shutdown()
    }
}
