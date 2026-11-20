package com.vaelin.startup.launch

/**
 * T1.11 启动编排的阶段与进度模型。
 *
 * 纯 JVM、零 Android 依赖，可独立单测。阶段枚举的声明顺序就是编排器的执行顺序，
 * 每个阶段的权重之和为 100，用来把「阶段 + 阶段内百分比」映射成整体百分比。
 */
enum class LaunchStage(val label: String, val weight: Int) {
    FETCH_MANIFEST("拉取版本清单", 5),
    DOWNLOAD_VERSION("下载版本文件", 45),
    DOWNLOAD_ASSETS("下载资源文件", 20),
    INSTALL_JRE("准备 Java 运行时", 10),
    INSTALL_COMPONENTS("准备启动组件", 10),
    BUILD_CLASSPATH("构建类路径", 3),
    BUILD_ARGUMENTS("构建启动参数", 3),
    WRITE_PLAN("写入启动计划", 2),
    READY("准备完成", 2),
    ;

    /** 该阶段开始时，前面所有阶段累计的权重（即整体百分比起点）。 */
    fun startPercent(): Int = LaunchStage.entries.takeWhile { it != this }.sumOf { it.weight }

    companion object {
        /** 全部阶段权重之和，约定为 100。 */
        val TOTAL_WEIGHT: Int = LaunchStage.entries.sumOf { it.weight }
    }
}

/** 一次进度上报：当前阶段、整体百分比（0..100）、可读细节。 */
data class LaunchProgress(
    val stage: LaunchStage,
    val percent: Int,
    val detail: String = "",
)

/** 启动链上任何「无法继续且不允许静默回退」的错误。 */
class LaunchException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * 把「阶段 + 阶段内百分比」折算成整体百分比并上报。
 * 记录去重后的阶段顺序，供「阶段顺序」类断言使用。
 */
class ProgressReporter(private val sink: (LaunchProgress) -> Unit) {
    private val reported = ArrayList<LaunchStage>()

    /** 批量下载在多个协程里并发回调，这里必须串行化，避免阶段序列错乱。 */
    @Synchronized
    fun stage(stage: LaunchStage, detail: String = "", innerPercent: Int = 0) {
        val inner = innerPercent.coerceIn(0, 100)
        val percent = (stage.startPercent() + stage.weight * inner / 100).coerceIn(0, 100)
        reported += stage
        sink(LaunchProgress(stage, percent, detail))
    }

    /** 已上报阶段按首次出现顺序去重后的序列。 */
    @Synchronized
    fun reportedStages(): List<LaunchStage> = reported.distinct().toList()
}
