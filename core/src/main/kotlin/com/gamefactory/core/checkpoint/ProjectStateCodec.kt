package com.gamefactory.core.checkpoint

import com.gamefactory.core.model.IntegrationRequirement
import com.gamefactory.core.model.LogEntry
import com.gamefactory.core.model.PipelineStage
import com.gamefactory.core.model.ProjectState
import com.gamefactory.core.model.TaskCategory
import com.gamefactory.core.model.TaskNode
import com.gamefactory.core.model.TaskStatus

/**
 * Checkpoint codec: serializes the full ProjectState with a line protocol so
 * a run can be paused/resumed (the "checkpoint every stage" requirement).
 * Files are wrapped in <<<path ... >>> markers so multi-line content is safe.
 */
object ProjectStateCodec {

    fun encode(state: ProjectState): String = buildString {
        appendLine("V1")
        appendLine("id=${state.projectId}")
        appendLine("name=${state.name}")
        appendLine("stage=${state.stage}")
        appendLine("brief<<EOF")
        appendLine(state.brief)
        appendLine("EOF")
        appendLine("build=${state.buildNumber}")
        appendLine("created=${state.createdAtMs}")
        for (d in state.decisions) appendLine("decision=$d")
        for (k in state.knownErrors) appendLine("known_error=$k")
        for (f in state.successfulFixes) appendLine("fix=$f")
        for (t in state.tasks) {
            appendLine("task=${t.id}|${t.status}|${t.category}|${t.attempts}|${t.deps.joinToString(",")}|${t.title}|${t.description}|${t.outputFiles.joinToString(",")}")
        }
        for (i in state.integrations) {
            appendLine("integration=${i.id}|${i.approved}|${i.label}|${i.reason}|${i.requiredVariables.joinToString(",")}|${i.alternatives.joinToString(",")}")
        }
        for (l in state.logs.takeLast(200)) {
            appendLine("log=${l.timestampMs}|${l.stage}|${l.isError}|${l.message.replace("\n", " ")}")
        }
        appendLine("bible<<EOF")
        appendLine(state.bible)
        appendLine("EOF")
        for ((path, content) in state.files) {
            appendLine("file<<<$path")
            append(if (content.endsWith("\n")) content.dropLast(1) else content)
            appendLine()
            appendLine(if (content.endsWith("\n")) ">>>N" else ">>>")
        }
    }

    fun decode(text: String): ProjectState {
        val lines = text.lineSequence().toList()
        require(lines.firstOrNull() == "V1") { "unknown checkpoint format" }
        var id = ""; var name = ""; var stage = PipelineStage.IDLE; var brief = ""
        var build = 0; var createdAt = System.currentTimeMillis()
        val decisions = mutableListOf<String>(); val known = mutableListOf<String>(); val fixes = mutableListOf<String>()
        val tasks = mutableListOf<TaskNode>(); val integrations = mutableListOf<IntegrationRequirement>()
        val logs = mutableListOf<LogEntry>(); val files = linkedMapOf<String, StringBuilder>()

        var i = 1
        var bibleText: String? = null
        while (i < lines.size) {
            val line = lines[i]
            when {
                line == "brief<<EOF" -> { brief = readBlock(lines, i + 1, "EOF"); i = skipBlock(lines, i + 1, "EOF") }
                line == "bible<<EOF" -> { bibleText = readBlock(lines, i + 1, "EOF"); i = skipBlock(lines, i + 1, "EOF") }
                line.startsWith("file<<<") -> {
                    val path = line.removePrefix("file<<<")
                    val sb = StringBuilder()
                    var j = i + 1
                    while (j < lines.size && lines[j] != ">>>" && lines[j] != ">>>N") { sb.appendLine(lines[j]); j++ }
                    var text = if (sb.isEmpty()) "" else sb.toString().dropLast(1)
                    if (j < lines.size && lines[j] == ">>>N") text += "\n"
                    files[path] = StringBuilder(text)
                    i = j
                }
                line.startsWith("id=") -> id = line.removePrefix("id=")
                line.startsWith("name=") -> name = line.removePrefix("name=")
                line.startsWith("stage=") -> stage = PipelineStage.valueOf(line.removePrefix("stage="))
                line.startsWith("build=") -> build = line.removePrefix("build=").toInt()
                line.startsWith("created=") -> createdAt = line.removePrefix("created=").toLong()
                line.startsWith("decision=") -> decisions += line.removePrefix("decision=")
                line.startsWith("known_error=") -> known += line.removePrefix("known_error=")
                line.startsWith("fix=") -> fixes += line.removePrefix("fix=")
                line.startsWith("task=") -> tasks += parseTask(line.removePrefix("task="))
                line.startsWith("integration=") -> integrations += parseIntegration(line.removePrefix("integration="))
                line.startsWith("log=") -> logs += parseLog(line.removePrefix("log="))
            }
            i++
        }
        return ProjectState(
            projectId = id, name = name, brief = brief, stage = stage,
            bible = bibleText ?: "", tasks = tasks, integrations = integrations,
            decisions = decisions, knownErrors = known, successfulFixes = fixes,
            files = files.mapValues { it.value.toString() }, logs = logs,
            buildNumber = build, createdAtMs = createdAt
        )
    }

    private fun readBlock(lines: List<String>, start: Int, end: String): String {
        val sb = StringBuilder()
        var j = start
        while (j < lines.size && lines[j] != end) { sb.appendLine(lines[j]); j++ }
        return sb.toString().trimEnd('\n')
    }

    private fun skipBlock(lines: List<String>, start: Int, end: String): Int {
        var j = start
        while (j < lines.size && lines[j] != end) j++
        return j
    }

    private fun splitSafe(s: String, sep: Char = '|'): List<String> {
        val out = mutableListOf<String>(); val sb = StringBuilder()
        for (c in s) {
            if (c == sep) { out += sb.toString(); sb.clear() } else sb.append(c)
        }
        out += sb.toString()
        return out
    }

    private fun parseTask(s: String): TaskNode {
        val p = splitSafe(s)
        return TaskNode(
            id = p[0],
            status = TaskStatus.valueOf(p[1]),
            category = TaskCategory.valueOf(p[2]),
            attempts = p[3].toInt(),
            deps = if (p[4].isBlank()) emptyList() else p[4].split(','),
            title = p.getOrElse(5) { "" },
            description = p.getOrElse(6) { "" },
            outputFiles = if (p.getOrElse(7) { "" }.isBlank()) emptyList() else p[7].split(',')
        )
    }

    private fun parseIntegration(s: String): IntegrationRequirement {
        val p = splitSafe(s)
        return IntegrationRequirement(
            id = p[0], approved = p[1].toBoolean(), label = p.getOrElse(2) { "" },
            reason = p.getOrElse(3) { "" },
            requiredVariables = if (p.getOrElse(4) { "" }.isBlank()) emptyList() else p[4].split(','),
            alternatives = if (p.getOrElse(5) { "" }.isBlank()) emptyList() else p[5].split(',')
        )
    }

    private fun parseLog(s: String): LogEntry {
        val p = splitSafe(s)
        return LogEntry(p[0].toLong(), PipelineStage.valueOf(p[1]), p.drop(3).joinToString("|"), p[2].toBoolean())
    }
}
