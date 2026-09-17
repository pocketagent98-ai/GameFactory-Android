package com.gamefactory.core.bible

import com.gamefactory.core.model.ProjectState

/**
 * The Game Bible is the single source of truth: WHAT the game should be,
 * HOW it is structured, and WHAT is already completed.
 */
object GameBible {

    private val SECTIONS = listOf(
        "Concept", "Mechanics", "World", "Characters", "Technical", "Platforms",
        "Assets", "Security", "Localization", "Store"
    )

    /** Parse the raw plan text (one line per TASK) into a bible document. */
    fun build(state: ProjectState): String = buildString {
        appendLine("# GAME BIBLE - ${state.name} (${state.projectId})")
        appendLine()
        appendLine("## Brief")
        appendLine(state.brief)
        appendLine()
        appendLine("## Concept")
        appendLine("Generated from the approved brief by the DeepFlow planning stage.")
        appendLine()
        appendLine("## Task Structure")
        for (t in state.tasks) {
            appendLine("- [${t.status}] ${t.id}: ${t.title}")
        }
        appendLine()
        appendLine("## Completed Work")
        for (t in state.doneTasks()) {
            appendLine("- ${t.id}: ${t.title} (attempts: ${t.attempts})")
        }
        appendLine()
        appendLine("## Known Errors")
        for (e in state.knownErrors) appendLine("- $e")
        appendLine()
        appendLine("## Successful Fixes")
        for (f in state.successfulFixes) appendLine("- $f")
    }

    fun sectionNames(): List<String> = SECTIONS

    /** Lenient parser used when a bible.md is re-uploaded for an update run. */
    data class ParsedBible(val name: String?, val sections: Map<String, String>)

    fun parse(markdown: String): ParsedBible {
        val sections = linkedMapOf<String, StringBuilder>()
        var current = "_header"
        var name: String? = null
        for (line in markdown.lineSequence()) {
            val heading = Regex("^#{1,3}\\s+(.*)$").find(line)?.groupValues?.get(1)
            if (heading != null) {
                current = heading.trim()
                if (sections[current] == null) sections[current] = StringBuilder()
                val m = Regex("^GAME BIBLE - (.*?)\\s*\\(").find(heading)
                if (m != null) name = m.groupValues[1]
            } else {
                sections.getOrPut(current) { StringBuilder() }.appendLine(line)
            }
        }
        return ParsedBible(name, sections.mapValues { it.value.toString().trim() })
    }
}
