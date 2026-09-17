package com.gamefactory.core.llm

/** What a task needs from a model - used by the provider router. */
enum class LlmCapability { PLANNING, CODING, QA, SECURITY, LISTING }

data class LlmRequest(
    val systemPrompt: String,
    val userPrompt: String,
    val capability: LlmCapability = LlmCapability.PLANNING,
    val maxTokens: Int = 4096,
    /** Unique call id used for budget accounting. */
    val purpose: String = ""
)

/**
 * Blocking LLM client contract. The Android layer is responsible for calling
 * this off the main thread; the core stays dependency-free and testable on the JVM.
 */
interface LlmClient {
    val name: String
    val supportedCapabilities: Set<LlmCapability>
    fun complete(request: LlmRequest): String
}

class LlmException(val providerName: String, message: String) : RuntimeException("[$providerName] $message")
