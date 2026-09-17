package com.gamefactory.core.model

/** All pipeline stages of the Game Factory, in execution order. */
enum class PipelineStage {
    IDLE,
    DEERFLOW_PLANNING,   // deep research + planning (DeerFlow role)
    GAME_BIBLE,          // source-of-truth document creation
    TASK_DAG,            // atomic task breakdown with dependencies
    AWAITING_APPROVAL,   // user reviews plan + integrations (Plan & Approval mode)
    CODING,              // DeepSeek-Harness role: writes code files
    ASSETS,              // asset tiering: procedural first, AI/API second, GPU only if needed
    BUILD,               // build attempt
    QA,                  // quality gates + diagnose/fix/retest loop
    SECURITY,            // separate security audit gate
    LEGAL,               // privacy policy / terms / disclosures
    CROSS_PLATFORM,      // platform matrix validation
    LISTING,             // store listing metadata
    EXPORT,              // final ZIP + reports
    DONE,
    FAILED
}

enum class TaskStatus { PENDING, IN_PROGRESS, DONE, FAILED }

enum class TaskCategory { CORE, GAMEPLAY, UI, WORLD, ASSETS, AUDIO, BACKEND, SECURITY, TESTING, LOCALIZATION, LISTING }

data class TaskNode(
    val id: String,
    val title: String,
    val description: String = "",
    val deps: List<String> = emptyList(),
    val category: TaskCategory = TaskCategory.CORE,
    val status: TaskStatus = TaskStatus.PENDING,
    val attempts: Int = 0,
    val outputFiles: List<String> = emptyList()
)

data class IntegrationRequirement(
    val id: String,
    val label: String,
    val reason: String,
    val requiredVariables: List<String>,
    val alternatives: List<String> = emptyList(),
    val approved: Boolean = false
)

data class LogEntry(
    val timestampMs: Long,
    val stage: PipelineStage,
    val message: String,
    val isError: Boolean = false
)

/** Hard safety limits - the anti-infinite-loop system from the architecture. */
data class SafetyLimits(
    val maxRetriesPerTask: Int = 5,
    val maxSameError: Int = 3,
    val maxLlmCallsPerRun: Int = 200,
    val maxRuntimeMs: Long = 30L * 60L * 1000L
)

/** Source of truth for one project run. Everything the factory knows lives here. */
data class ProjectState(
    val projectId: String,
    val name: String,
    val brief: String,
    val stage: PipelineStage = PipelineStage.IDLE,
    val bible: String = "",
    val tasks: List<TaskNode> = emptyList(),
    val integrations: List<IntegrationRequirement> = emptyList(),
    val decisions: List<String> = emptyList(),
    val knownErrors: List<String> = emptyList(),
    val successfulFixes: List<String> = emptyList(),
    val files: Map<String, String> = emptyMap(),
    val logs: List<LogEntry> = emptyList(),
    val buildNumber: Int = 0,
    val createdAtMs: Long = System.currentTimeMillis()
) {
    fun withLog(message: String, isError: Boolean = false): ProjectState =
        copy(logs = logs + LogEntry(System.currentTimeMillis(), stage, message, isError))

    fun pendingTasks(): List<TaskNode> = tasks.filter { it.status == TaskStatus.PENDING }

    fun doneTasks(): List<TaskNode> = tasks.filter { it.status == TaskStatus.DONE }
}

/** Events the engine emits so a UI (or test) can follow along. */
sealed class EngineEvent {
    data class StageChanged(val stage: PipelineStage) : EngineEvent()
    data class Log(val entry: LogEntry) : EngineEvent()
    data class TaskUpdated(val task: TaskNode) : EngineEvent()
    data class AwaitingApproval(val requirements: List<IntegrationRequirement>) : EngineEvent()
    data class FileWritten(val path: String) : EngineEvent()
    data class Completed(val fileCount: Int) : EngineEvent()
    data class Failed(val reason: String) : EngineEvent()
}
