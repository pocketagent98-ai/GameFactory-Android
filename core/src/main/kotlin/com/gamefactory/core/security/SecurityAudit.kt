package com.gamefactory.core.security

/**
 * Security audit gate: scans generated code for hardcoded secrets and
 * unsafe patterns before the release stage. This runs as its own gate
 * (never a checkbox) per the architecture.
 */
object SecurityAudit {

    data class Finding(val file: String, val line: Int, val rule: String, val evidence: String)

    private val RULES: List<Pair<String, Regex>> = listOf(
        "hardcoded-api-key-openai" to Regex("""sk-[A-Za-z0-9]{16,}"""),
        "hardcoded-api-key-nvidia" to Regex("""nvapi-[A-Za-z0-9_\-]{20,}"""),
        "hardcoded-api-key-google" to Regex("""AIza[0-9A-Za-z\-_]{30,}"""),
        "hardcoded-supabase-secret" to Regex("""sb_secret_[A-Za-z0-9\-_]{10,}"""),
        "hardcoded-password" to Regex("""(?i)(password|passwd|secret)\s*[:=]\s*["'][^"']{4,}["']"""),
        "hardcoded-bearer" to Regex("""(?i)bearer\s+[A-Za-z0-9\-_.]{20,}"""),
        "env-key-in-source" to Regex("""(?i)(api[_-]?key|token)\s*[:=]\s*["'][A-Za-z0-9\-_]{12,}["']""")
    )

    fun scan(files: Map<String, String>): List<Finding> {
        val findings = mutableListOf<Finding>()
        for ((path, content) in files) {
            content.lineSequence().forEachIndexed { idx, line ->
                for ((rule, regex) in RULES) {
                    val match = regex.find(line) ?: continue
                    findings += Finding(path, idx + 1, rule, match.value.take(24) + "...")
                }
            }
        }
        return findings
    }

    /** True when there are no findings - i.e. the gate passes. */
    fun passes(files: Map<String, String>): Boolean = scan(files).isEmpty()
}
