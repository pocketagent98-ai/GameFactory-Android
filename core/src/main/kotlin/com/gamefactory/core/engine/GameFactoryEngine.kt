package com.gamefactory.core.engine

import com.gamefactory.core.bible.GameBible
import com.gamefactory.core.checkpoint.ProjectStateCodec
import com.gamefactory.core.dag.DagOps
import com.gamefactory.core.export.ZipExporter
import com.gamefactory.core.llm.LlmCapability
import com.gamefactory.core.llm.LlmRequest
import com.gamefactory.core.llm.ProviderRouter
import com.gamefactory.core.model.EngineEvent
import com.gamefactory.core.model.IntegrationRequirement
import com.gamefactory.core.model.PipelineStage
import com.gamefactory.core.model.ProjectState
import com.gamefactory.core.model.SafetyLimits
import com.gamefactory.core.model.TaskNode
import com.gamefactory.core.model.TaskStatus
import com.gamefactory.core.security.SecurityAudit

/**
 * The MASTER ORCHESTRATOR. Runs the whole pipeline:
 * plan -> bible -> task DAG -> approval -> coding -> assets -> build ->
 * QA loop -> security -> legal -> cross-platform -> listing -> export.
 *
 * Synchronous by design: the caller (Android ViewModel or a JVM test) runs it
 * on a worker thread. All progress is reported through [listener] events.
 */
class GameFactoryEngine(
    private val router: ProviderRouter,
    private val limits: SafetyLimits = SafetyLimits(),
    private val listener: (EngineEvent) -> Unit = {},
    private val checkpointSink: (String) -> Unit = {},
    private val idGenerator: () -> String = { "GF-" + System.currentTimeMillis().toString(36).uppercase() }
) {

    var state: ProjectState = ProjectState("", "", "")
        private set

    var finalZip: ByteArray? = null
        private set

    private val startedAt = System.currentTimeMillis()

    // ---------------- public API ----------------

    /** Phase 1: brief -> plan + DAG, then PAUSE for user approval. */
    fun planAndAwaitApproval(brief: String, name: String): ProjectState {
        state = ProjectState(
            projectId = idGenerator(),
            name = name.ifBlank { deriveName(brief) },
            brief = brief.trim(),
            stage = PipelineStage.DEERFLOW_PLANNING
        )
        log("Factory started for project ${state.projectId}")

        checkBudget()

        // --- DeerFlow planning stage: research + task breakdown in one call ---
        val planText = try {
            router.complete(
                LlmRequest(
                    systemPrompt = PLANNER_SYSTEM,
                    userPrompt = brief,
                    capability = LlmCapability.PLANNING,
                    purpose = "plan"
                )
            )
        } catch (e: Exception) {
            fail("planning failed: ${e.message}")
        }
        val tasks = parseTaskLines(planText)
        if (tasks.isEmpty()) fail("planner returned no tasks")
        val ordered = DagOps.topologicalOrder(tasks) // validates DAG (no cycles, known deps)

        // --- Game Bible ---
        toStage(PipelineStage.GAME_BIBLE)
        state = state.copy(tasks = ordered)
        state = state.copy(bible = GameBible.build(state))
        log("Game Bible written with ${ordered.size} tasks")
        checkpoint()

        // --- Integration detection (Plan & Approval mode) ---
        toStage(PipelineStage.TASK_DAG)
        val requirements = detectIntegrations(brief)
        state = state.copy(integrations = requirements)
        log("Detected ${requirements.size} integration requirement(s)")

        toStage(PipelineStage.AWAITING_APPROVAL)
        listener(EngineEvent.AwaitingApproval(requirements))
        checkpoint()
        return state
    }

    /** Phase 2: continue after the user approved the plan/integrations. */
    fun runAfterApproval(): ProjectState {
        check(state.stage == PipelineStage.AWAITING_APPROVAL) { "engine is not awaiting approval (stage=${state.stage})" }
        codeAllTasks()
        assetStage()
        buildAndQaLoop()
        securityStage()
        legalStage()
        crossPlatformStage()
        listingStage()
        exportStage()
        return state
    }

    /** Convenience: full run without pausing (approves detected integrations). */
    fun runToEnd(brief: String, name: String): ProjectState {
        planAndAwaitApproval(brief, name)
        state = state.copy(integrations = state.integrations.map { it.copy(approved = true) })
        return runAfterApproval()
    }

    fun approveIntegration(id: String, approved: Boolean) {
        state = state.copy(integrations = state.integrations.map {
            if (it.id == id) it.copy(approved = approved) else it
        })
    }

    fun exportZipBytes(): ByteArray = finalZip ?: ZipExporter.export(state)

    fun checkpoint() {
        checkpointSink(ProjectStateCodec.encode(state))
    }

    // ---------------- stages ----------------

    private fun codeAllTasks() {
        toStage(PipelineStage.CODING)
        val order = DagOps.topologicalOrder(state.tasks)
        for (task in order) {
            checkBudget()
            var attempts = 0
            var lastError: String? = null
            while (attempts < limits.maxRetriesPerTask) {
                attempts++
                try {
                    val out = router.complete(
                        LlmRequest(
                            systemPrompt = CODER_SYSTEM,
                            userPrompt = "TASK_ID: ${task.id}\n${task.title}\n${task.description}\n\nGAME BIBLE (excerpt):\n${state.bible.take(2000)}",
                            capability = LlmCapability.CODING,
                            purpose = "code:${task.id}"
                        )
                    )
                    val files = parseFileBlocks(out)
                    if (files.isEmpty()) throw IllegalStateException("coder returned no files")
                    state = state.copy(files = state.files + files)
                    for (p in files.keys) listener(EngineEvent.FileWritten(p))
                    state = state.copy(tasks = state.tasks.map {
                        if (it.id == task.id) it.copy(
                            status = TaskStatus.DONE, attempts = it.attempts + attempts,
                            outputFiles = files.keys.toList()
                        ) else it
                    })
                    listener(EngineEvent.TaskUpdated(state.tasks.first { it.id == task.id }))
                    lastError = null
                    break
                } catch (e: Exception) {
                    lastError = e.message
                    log("task ${task.id} attempt $attempts failed: ${e.message}", isError = true)
                }
            }
            if (lastError != null) {
                state = state.copy(
                    tasks = state.tasks.map { if (it.id == task.id) it.copy(status = TaskStatus.FAILED) else it },
                    knownErrors = state.knownErrors + "${task.id}: $lastError"
                )
                fail("task ${task.id} failed after $attempts attempts: $lastError")
            }
            if (state.tasks.count { it.status == TaskStatus.DONE } % 5 == 0) checkpoint()
        }
        log("Coding complete: ${state.tasks.count { it.status == TaskStatus.DONE }} tasks")
        checkpoint()
    }

    private fun assetStage() {
        toStage(PipelineStage.ASSETS)
        // Asset tiering: procedural (Godot-first) assets need no LLM call;
        // only genuinely missing heavy 3D would escalate - not on a phone.
        state = state.copy(
            files = state.files + ("assets/README.md" to
                "# Assets\n\nTier 1 (procedural): preferred.\nTier 2 (AI/API): when Tier 1 is not enough.\nTier 3 (Blender+GPU): only when truly needed.\n")
        )
        log("Asset plan applied (procedural-first tiering)")
    }

    private fun buildAndQaLoop() {
        toStage(PipelineStage.BUILD)
        state = state.copy(buildNumber = state.buildNumber + 1)
        log("Build #${state.buildNumber}")

        toStage(PipelineStage.QA)
        var qaAttempt = 0
        while (true) {
            qaAttempt++
            val gate = QualityGates.evaluate(state)

            if (gate.passed) {
                log("QA pass on attempt $qaAttempt")
                break
            }
            if (qaAttempt >= limits.maxRetriesPerTask) {
                fail("QA failed after $qaAttempt attempts: ${gate.reasons.joinToString("; ")}")
            }
            log("QA failed (attempt $qaAttempt): ${gate.reasons.joinToString("; ")}", isError = true)

            // Root-cause: security findings get an explicit fix pass through the coder.
            val findings = SecurityAudit.scan(state.files)
            if (findings.isNotEmpty()) {
                val byFile = findings.groupBy { it.file }
                for ((file, fs) in byFile) {
                    val fixed = router.complete(
                        LlmRequest(
                            systemPrompt = CODER_SYSTEM,
                            userPrompt = "TASK_ID: security.fix\nFix file '$file'. Remove these findings and return the whole file again: " +
                                fs.joinToString { "${it.rule} at line ${it.line}" },
                            capability = LlmCapability.CODING,
                            purpose = "fix:$file"
                        )
                    )
                    val files = parseFileBlocks(fixed)
                    if (files.containsKey(file)) {
                        state = state.copy(files = state.files + files)
                        state = state.copy(successfulFixes = state.successfulFixes + "security findings in $file")
                        log("Fixed $file (security)")
                    }
                }
            } else {
                fail("unfixable QA failure: ${gate.reasons.joinToString("; ")}")
            }
            state = state.copy(buildNumber = state.buildNumber + 1)
            log("Rebuild #${state.buildNumber} for retest")
        }
        checkpoint()
    }

    private fun securityStage() {
        toStage(PipelineStage.SECURITY)
        val findings = SecurityAudit.scan(state.files)
        if (findings.isNotEmpty()) {
            for (f in findings) log("SECURITY: ${f.rule} in ${f.file}:${f.line}", isError = true)
            fail("security gate failed with ${findings.size} finding(s)")
        }
        state = state.copy(decisions = state.decisions + "security audit clean")
        log("Security audit clean")
    }

    private fun legalStage() {
        toStage(PipelineStage.LEGAL)
        val hasBackend = state.integrations.any { it.approved && it.id in BACKEND_IDS }
        val hasAds = state.integrations.any { it.approved && it.id == "ads" }
        val text = buildString {
            appendLine("# Privacy Policy (draft, review required)")
            if (hasBackend) appendLine("- Account/login data is stored via the chosen backend provider.")
            if (hasAds) appendLine("- Ads are served by a third-party ad network.")
            if (!hasBackend && !hasAds) appendLine("- This game collects no personal data and works fully offline.")
            appendLine("# Terms of Service (draft, review required)")
            appendLine("- Provided as-is, without warranty.")
        }
        state = state.copy(files = state.files + ("legal/privacy_and_terms.md" to text))
        log("Legal package generated (review before publishing)")
    }

    private fun crossPlatformStage() {
        toStage(PipelineStage.CROSS_PLATFORM)
        val is3d = state.brief.contains("3d", ignoreCase = true)
        val profiles = if (is3d) listOf("low-end Android (2GB)", "mid Android", "desktop")
                      else listOf("low-end Android (1GB)", "mid Android", "tablet", "desktop")
        state = state.copy(decisions = state.decisions + "test matrix: ${profiles.joinToString()}")
        log("Cross-platform matrix selected: ${profiles.joinToString()}")
    }

    private fun listingStage() {
        toStage(PipelineStage.LISTING)
        val listing = router.complete(
            LlmRequest(
                systemPrompt = LISTING_SYSTEM,
                userPrompt = state.brief,
                capability = LlmCapability.LISTING,
                purpose = "listing"
            )
        )
        state = state.copy(files = state.files + ("store/listing.txt" to listing))
        log("Store listing generated")
    }

    private fun exportStage() {
        toStage(PipelineStage.EXPORT)
        finalZip = ZipExporter.export(state)
        toStage(PipelineStage.DONE)
        listener(EngineEvent.Completed(state.files.size))
        checkpoint()
    }

    // ---------------- helpers ----------------

    private fun toStage(stage: PipelineStage) {
        state = state.copy(stage = stage)
        listener(EngineEvent.StageChanged(stage))
        log("Stage -> $stage")
    }

    private fun log(message: String, isError: Boolean = false) {
        val entry = com.gamefactory.core.model.LogEntry(System.currentTimeMillis(), state.stage, message, isError)
        state = state.copy(logs = state.logs + entry)
        listener(EngineEvent.Log(entry))
    }

    private fun fail(reason: String): Nothing {
        toStage(PipelineStage.FAILED)
        listener(EngineEvent.Failed(reason))
        checkpoint()
        throw FactoryException(reason)
    }

    private fun checkBudget() {
        if (router.callsUsed > limits.maxLlmCallsPerRun) fail("LLM call budget exhausted (${limits.maxLlmCallsPerRun})")
        if (System.currentTimeMillis() - startedAt > limits.maxRuntimeMs) fail("runtime budget exhausted")
    }

    private fun deriveName(brief: String): String =
        brief.trim().split(Regex("\\s+")).take(3).joinToString(" ").ifBlank { "Untitled Project" }

    /** Parse the TASK line protocol the planner is instructed to emit. */
    internal fun parseTaskLines(plan: String): List<TaskNode> = plan.lineSequence()
        .filter { it.startsWith("TASK ") }
        .map { line ->
            val rest = line.removePrefix("TASK ")
            val parts = splitOnPipe(rest)
            val id = parts[0].trim()
            val depsPart = parts.getOrElse(1) { "" }.trim().removePrefix("deps:").trim()
            val title = parts.drop(2).joinToString("|").trim()
            TaskNode(
                id = id,
                title = title.ifBlank { id },
                deps = if (depsPart.isBlank()) emptyList() else depsPart.split(',').map { it.trim() },
                category = categorize(id)
            )
        }
        .toList()

    /** Parse the FILE/---/ENDFILE blocks the coder is instructed to emit. */
    internal fun parseFileBlocks(out: String): Map<String, String> {
        val files = linkedMapOf<String, StringBuilder>()
        var current: String? = null
        for (line in out.lineSequence()) {
            when {
                line.startsWith("FILE ") -> current = line.removePrefix("FILE ").trim()
                line == "ENDFILE" -> current = null
                current != null -> files.getOrPut(current) { StringBuilder() }.appendLine(line)
            }
        }
        return files.mapValues { it.value.toString().trimEnd('\n') }.filterValues { it.isNotBlank() }
    }

    private fun splitOnPipe(s: String): List<String> {
        val out = mutableListOf<String>(); val sb = StringBuilder()
        for (c in s) { if (c == '|') { out += sb.toString(); sb.clear() } else sb.append(c) }
        out += sb.toString(); return out
    }

    private fun categorize(id: String) = when {
        id.startsWith("ui.") -> com.gamefactory.core.model.TaskCategory.UI
        id.startsWith("world.") || id.startsWith("assets.") -> com.gamefactory.core.model.TaskCategory.WORLD
        id.startsWith("audio.") -> com.gamefactory.core.model.TaskCategory.AUDIO
        id.startsWith("security.") -> com.gamefactory.core.model.TaskCategory.SECURITY
        id.startsWith("testing.") || id.startsWith("list.") || id.startsWith("listing.") -> com.gamefactory.core.model.TaskCategory.TESTING
        id.startsWith("player.") || id.startsWith("gameplay.") -> com.gamefactory.core.model.TaskCategory.GAMEPLAY
        else -> com.gamefactory.core.model.TaskCategory.CORE
    }

    /** Deterministic integration detection from the brief (asks the user, never invents keys). */
    internal fun detectIntegrations(brief: String): List<IntegrationRequirement> {
        val b = brief.lowercase()
        val out = mutableListOf<IntegrationRequirement>()
        if (listOf("login", "auth", "account", "leaderboard", "multiplayer", "supabase", "backend", "database").any { it in b }) {
            out += IntegrationRequirement(
                id = "backend", label = "Backend / Database",
                reason = "brief mentions accounts, leaderboards or multiplayer",
                requiredVariables = listOf("BACKEND_URL", "BACKEND_PUBLISHABLE_KEY", "BACKEND_SECRET_KEY"),
                alternatives = listOf("Supabase", "Neon", "custom")
            )
        }
        if (listOf("ads", "admob", "advertisement", "monetize").any { it in b }) {
            out += IntegrationRequirement(
                id = "ads", label = "Ad Network",
                reason = "brief mentions monetization",
                requiredVariables = listOf("AD_APP_ID", "AD_UNIT_ID"),
                alternatives = listOf("AdMob", "none")
            )
        }
        if (listOf("payment", "purchase", "iap", "in-app").any { it in b }) {
            out += IntegrationRequirement(
                id = "payments", label = "Payments",
                reason = "brief mentions purchases",
                requiredVariables = listOf("PAYMENTS_LICENSE_KEY"),
                alternatives = listOf("Google Play Billing")
            )
        }
        return out
    }

    companion object {
        val BACKEND_IDS = setOf("backend")

        val PLANNER_SYSTEM = """
            You are the DeepFlow planning stage of a Game Factory.
            Break the user's game brief into ATOMIC tasks with dependencies.
            Output ONLY lines in this exact format, nothing else:
            TASK <id> | deps: <comma separated ids, empty allowed> | <human readable title>
            Use 8-15 tasks. Cover: core setup, player, gameplay, UI, world/levels,
            assets, audio, testing, security, listing.
        """.trimIndent()

        val CODER_SYSTEM = """
            You are the DeepSeek-Harness coding stage of a Game Factory.
            Write MINIMAL, working code (Ponytail rules: reuse existing code,
            standard library first, smallest implementation that works - never
            remove security or error handling to shorten code).
            Output ONLY file blocks in this exact format:
            FILE <filename>
            ---
            <full file content>
            ENDFILE
        """.trimIndent()

        val LISTING_SYSTEM = """
            You are the store listing writer. Output a short plain-text listing:
            TITLE:, DESC:, KEYWORDS: lines only.
        """.trimIndent()
    }

    class FactoryException(reason: String) : RuntimeException(reason)
}
