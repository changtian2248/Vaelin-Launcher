package com.vaelin.launcher.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.vaelin.startup.profile.GameDirectoryMode
import com.vaelin.startup.profile.VersionProfile
import com.vaelin.startup.util.GameDirectoryOperationLock
import com.vaelin.startup.util.VPaths
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class GameDirectorySettingsState(
    val loadingPreview: Boolean = false,
    val preview: IsolationMigrationPreview? = null,
    val migrating: Boolean = false,
    val progress: Pair<Int, Int>? = null,
    val progressDetail: String = "",
    val error: String? = null,
    val message: String? = null,
)

/** Owns the settings-only global directory-mode migration flow. */
class GameDirectorySettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var previewGeneration = 0L

    var state by mutableStateOf(GameDirectorySettingsState())
        private set

    fun prepareIsolation(profiles: List<VersionProfile>) {
        if (VPaths.gameDirectoryMode == GameDirectoryMode.ISOLATED) {
            state = GameDirectorySettingsState(message = "全局版本隔离已开启")
            return
        }
        if (state.loadingPreview || state.migrating) return
        val requestGeneration = ++previewGeneration
        state = GameDirectorySettingsState(loadingPreview = true)
        val profileSnapshot = profiles.toList()
        worker.execute {
            val result = runCatching {
                GameDirectoryOperationLock.withLock(VPaths.gameDirectoryLockFile) {
                    check(VPaths.gameDirectoryMode == GameDirectoryMode.SHARED) { "目录模式已变化，请重新打开设置" }
                    GameDirectoryMigrator.preview(VPaths.gameDir, profileSnapshot, VPaths.filesPrivate)
                }
            }
            mainHandler.post {
                if (requestGeneration != previewGeneration) return@post
                state = result.fold(
                    onSuccess = { GameDirectorySettingsState(preview = it) },
                    onFailure = {
                        GameDirectorySettingsState(error = it.message ?: it.javaClass.simpleName)
                    },
                )
            }
        }
    }

    fun cancelPreview() {
        if (!state.migrating) {
            previewGeneration++
            state = GameDirectorySettingsState()
        }
    }

    fun setCategorySelected(category: MigrationCategory, selected: Boolean) {
        val preview = state.preview ?: return
        val categories = preview.selection.categories.toMutableSet()
        val targets = preview.selection.targetsByCategory.toMutableMap()
        if (selected) {
            categories.add(category)
            targets.putIfAbsent(category, emptySet())
        } else {
            categories.remove(category)
            targets.remove(category)
        }
        val selection = preview.selection.copy(categories = categories, targetsByCategory = targets)
        state = state.copy(preview = preview.copy(selection = selection))
    }

    fun setTargetSelected(category: MigrationCategory, profileId: String, selected: Boolean) {
        val preview = state.preview ?: return
        if (category !in preview.selection.categories || preview.targets.none { it.profileId == profileId }) return
        val targets = preview.selection.targetsByCategory.toMutableMap()
        val targetIds = targets[category].orEmpty().toMutableSet()
        if (selected) targetIds.add(profileId) else targetIds.remove(profileId)
        targets[category] = targetIds
        val selection = preview.selection.copy(targetsByCategory = targets)
        state = state.copy(preview = preview.copy(selection = selection))
    }

    fun setLeaveUnselectedDataInSharedRoot(leave: Boolean) {
        val preview = state.preview ?: return
        val selection = preview.selection.copy(keepUnselectedDataInSharedRoot = leave)
        state = state.copy(preview = preview.copy(selection = selection))
    }

    fun confirmIsolation(
        profiles: List<VersionProfile>,
        onModeCommitted: (GameDirectoryMode) -> Unit,
    ) {
        val preview = state.preview ?: return
        if (!preview.canRun || state.migrating || state.loadingPreview) return
        val profileSnapshot = profiles.toList()
        val selection = preview.selection
        val selectedPreview = preview.copy(selection = selection)
        val total = if (selectedPreview.selectedCopies.isEmpty()) 0 else {
            selectedPreview.selectedFiles.size + selectedPreview.selectedCopies.sumOf { copy ->
                selectedPreview.selectedFiles.count { it.category == copy.category }
            }
        }
        state = state.copy(migrating = true, error = null, message = null, progress = 0 to total)
        worker.execute {
            val result = runCatching {
                GameDirectoryMigrator.execute(
                    preview = preview,
                    profiles = profileSnapshot,
                    filesPrivate = VPaths.filesPrivate,
                    selection = selection,
                    onProgress = { completed, total, detail ->
                        mainHandler.post {
                            if (state.migrating) state = state.copy(progress = completed to total, progressDetail = detail)
                        }
                    },
                    onBeforeRelease = {
                        check(VPaths.setGameDirectoryMode(GameDirectoryMode.ISOLATED)) {
                            "迁移文件已校验，但无法保存全局设置；原共享目录仍保留，重新尝试即可"
                        }
                    },
                )
            }
            mainHandler.post {
                result.fold(
                    onSuccess = {
                        state = GameDirectorySettingsState(
                            message = if (it.migratedFiles == 0) {
                                "已切换为全局版本隔离。没有复制旧共享文件；它们仍留在共享源中，隔离实例不会自动显示这些内容。"
                            } else {
                                "已切换为全局版本隔离。迁移备份保留在 ${it.backupRoot.absolutePath}；原共享目录没有删除。"
                            },
                        )
                        onModeCommitted(GameDirectoryMode.ISOLATED)
                    },
                    onFailure = {
                        state = state.copy(
                            migrating = false,
                            error = it.message ?: it.javaClass.simpleName,
                            progressDetail = "迁移失败；当前全局模式保持为共享，原数据保留。",
                        )
                    },
                )
            }
        }
    }

    /** Switching back uses the shared root without deleting/merging isolated copies. */
    fun switchToShared(onModeCommitted: (GameDirectoryMode) -> Unit) {
        if (state.migrating || state.loadingPreview) return
        state = GameDirectorySettingsState(migrating = true, progressDetail = "等待游戏目录操作结束…")
        worker.execute {
            val result = runCatching {
                GameDirectoryOperationLock.withLock(VPaths.gameDirectoryLockFile) {
                    check(VPaths.setGameDirectoryMode(GameDirectoryMode.SHARED)) {
                        "无法保存全局目录设置；当前模式未更改"
                    }
                }
            }
            mainHandler.post {
                result.fold(
                    onSuccess = {
                        state = GameDirectorySettingsState(
                            message = "已切回共享目录。各版本隔离目录中的内容已保留，没有自动合并或删除。",
                        )
                        onModeCommitted(GameDirectoryMode.SHARED)
                    },
                    onFailure = {
                        state = GameDirectorySettingsState(error = it.message ?: it.javaClass.simpleName)
                    },
                )
            }
        }
    }

    fun clearMessage() {
        state = state.copy(message = null)
    }

    override fun onCleared() {
        worker.shutdown()
    }
}
