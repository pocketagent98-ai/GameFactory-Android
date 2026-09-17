package com.gamefactory.core.export

import com.gamefactory.core.bible.GameBible
import com.gamefactory.core.model.ProjectState
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds the FINAL_DELIVERY zip: game source + bible + manifest + reports.
 * Exactly the "release package, not just a game" idea from the architecture.
 */
object ZipExporter {

    fun export(state: ProjectState): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            put("game_source/bible.md", GameBible.build(state))
            for ((path, content) in state.files) {
                put("game_source/$path", content)
            }
            put("store/metadata.txt", "Name: ${state.name}\nProject: ${state.projectId}\n")
            put("QA/test_report.txt", qaReport(state))
            put("legal/README.txt",
                "Privacy policy, terms and disclosures are generated from the actual\n" +
                "data-collection answers during the LEGAL stage.\n")
            put("PROJECT_MANIFEST.json", manifest(state))
        }
        return out.toByteArray()
    }

    fun manifest(state: ProjectState): String = buildString {
        appendLine("{")
        appendLine("  \"project_id\": \"${state.projectId}\",")
        appendLine("  \"name\": \"${state.name}\",")
        appendLine("  \"stage\": \"${state.stage}\",")
        appendLine("  \"build_number\": ${state.buildNumber},")
        appendLine("  \"tasks_total\": ${state.tasks.size},")
        appendLine("  \"tasks_done\": ${state.doneTasks().size},")
        appendLine("  \"files\": [")
        state.files.keys.forEachIndexed { i, f ->
            append("    \"$f\"")
            if (i < state.files.size - 1) append(",")
            appendLine()
        }
        appendLine("  ]")
        appendLine("}")
    }

    private fun qaReport(state: ProjectState): String = buildString {
        appendLine("QA REPORT - ${state.projectId}")
        appendLine("Build number: ${state.buildNumber}")
        appendLine("Tasks done: ${state.doneTasks().size}/${state.tasks.size}")
        appendLine("Known errors resolved: ${state.successfulFixes.size}")
        for (f in state.successfulFixes) appendLine("  FIX: $f")
    }
}
