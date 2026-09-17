package com.gamefactory.core.llm

/**
 * Capability-based provider router with automatic fallback:
 * task -> capability -> ordered healthy providers -> on failure (429/timeout/error)
 * fall through to the next one. Tracks per-provider health and cooldowns.
 */
class ProviderRouter(private val clients: List<LlmClient>) {

    private class Health {
        var consecutiveFailures = 0
        var cooldownUntilMs = 0L
    }

    private val health = clients.associate { it.name to Health() }
    private var callCount = 0

    /** Call budget; the engine checks this to respect SafetyLimits.maxLlmCallsPerRun. */
    val callsUsed: Int get() = callCount

    fun healthyProvidersFor(capability: LlmCapability): List<LlmClient> {
        val now = System.currentTimeMillis()
        return clients.filter { capability in it.supportedCapabilities }
            .sortedBy { (health[it.name] ?: Health()).consecutiveFailures }
            .filter { (health[it.name] ?: Health()).cooldownUntilMs <= now }
    }

    fun complete(request: LlmRequest): String {
        val candidates = clients.filter { request.capability in it.supportedCapabilities }
        if (candidates.isEmpty()) {
            throw LlmException("router", "no provider supports ${request.capability}")
        }
        val now = System.currentTimeMillis()
        val ordered = candidates.sortedBy { (health[it.name] ?: Health()).consecutiveFailures }
        var lastError: LlmException? = null

        for (client in ordered) {
            val h = health[client.name] ?: continue
            if (h.cooldownUntilMs > now) continue
            callCount++
            try {
                val result = client.complete(request)
                h.consecutiveFailures = 0
                h.cooldownUntilMs = 0L
                return result
            } catch (e: Exception) {
                h.consecutiveFailures++
                if (h.consecutiveFailures >= 3) {
                    h.cooldownUntilMs = now + COOLDOWN_MS
                    h.consecutiveFailures = 0 // reset counter; cooldown is active now
                }
                lastError = LlmException(client.name, e.message ?: "unknown error")
            }
        }
        throw lastError ?: LlmException("router", "all providers on cooldown")
    }

    companion object {
        const val COOLDOWN_MS = 60_000L
    }
}
