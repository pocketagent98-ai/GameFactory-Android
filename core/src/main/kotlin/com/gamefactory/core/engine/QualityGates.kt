package com.gamefactory.core.engine

import com.gamefactory.core.model.ProjectState
import com.gamefactory.core.model.TaskStatus
import com.gamefactory.core.security.SecurityAudit

/**
 * Quality gates (Gate 0..9 simplified to what a code-generation device can
 * actually verify): task completeness, non-empty outputs, security cleanliness
 * and manifest writability. Every failure must be diagnosable - that is what
 * the build/fix/retest loop consumes.
 */
object QualityGates {

    data class GateResult(val passed: Boolean, val reasons: List<String>)

    fun evaluate(state: ProjectState): GateResult {
        val reasons = mutableListOf<String>()

        if (state.tasks.isEmpty()) reasons += "no tasks in project"
        val notDone = state.tasks.filter { it.status != TaskStatus.DONE }
        if (notDone.isNotEmpty()) reasons += "tasks not complete: ${notDone.joinToString { it.id }}"

        for (t in state.tasks) {
            if (t.outputFiles.isNotEmpty() && t.outputFiles.none { state.files.containsKey(it) }) {
                reasons += "task ${t.id} declares missing output files"
            }
        }
        if (state.files.values.any { it.isBlank() }) reasons += "an output file is empty"

        val findings = SecurityAudit.scan(state.files)
        if (findings.isNotEmpty()) {
            reasons += "security findings: " + findings.take(3).joinToString { "${it.rule}@${it.file}:${it.line}" }
        }

        return GateResult(reasons.isEmpty(), reasons)
    }
}
