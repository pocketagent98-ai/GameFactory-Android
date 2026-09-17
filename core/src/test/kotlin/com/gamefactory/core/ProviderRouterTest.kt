package com.gamefactory.core

import com.gamefactory.core.llm.LlmCapability
import com.gamefactory.core.llm.LlmClient
import com.gamefactory.core.llm.LlmException
import com.gamefactory.core.llm.LlmRequest
import com.gamefactory.core.llm.MockLlmClient
import com.gamefactory.core.llm.ProviderRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRouterTest {

    class FailingClient(override val name: String) : LlmClient {
        override val supportedCapabilities = setOf(LlmCapability.CODING)
        override fun complete(request: LlmRequest): String = throw LlmException(name, "down")
    }

    class FlakyClient(override val name: String, var fails: Int) : LlmClient {
        override val supportedCapabilities = setOf(LlmCapability.CODING)
        override fun complete(request: LlmRequest): String {
            if (fails > 0) { fails--; throw LlmException(name, "transient") }
            return "ok"
        }
    }

    @Test
    fun `falls back to next provider on failure`() {
        val good = MockLlmClient("good")
        val router = ProviderRouter(listOf(FailingClient("bad"), good))
        val result = router.complete(LlmRequest("s", "u", LlmCapability.CODING))
        assertTrue(result.contains("FILE"))
        assertEquals(2, router.callsUsed) // one failed attempt + one success
    }

    @Test
    fun `recovers when a flaky provider stabilizes`() {
        val flaky = FlakyClient("flaky", fails = 1)
        val router = ProviderRouter(listOf(flaky))
        try {
            router.complete(LlmRequest("s", "u", LlmCapability.CODING))
            org.junit.Assert.fail("expected LlmException on first call")
        } catch (expected: LlmException) {
        }
        // provider recovered -> next call goes through
        assertEquals("ok", router.complete(LlmRequest("s", "u", LlmCapability.CODING)))
        assertEquals("ok", router.complete(LlmRequest("s", "u", LlmCapability.CODING)))
    }

    @Test(expected = LlmException::class)
    fun `throws when all providers fail`() {
        val router = ProviderRouter(listOf(FailingClient("a"), FailingClient("b")))
        router.complete(LlmRequest("s", "u", LlmCapability.CODING))
    }

    @Test(expected = LlmException::class)
    fun `throws when no provider supports capability`() {
        val codingOnly = object : LlmClient {
            override val name = "coding-only"
            override val supportedCapabilities = setOf(LlmCapability.CODING)
            override fun complete(request: LlmRequest) = "ok"
        }
        val router = ProviderRouter(listOf(codingOnly))
        router.complete(LlmRequest("s", "u", LlmCapability.PLANNING))
    }

    @Test
    fun `capability filtering works`() {
        val codingOnly = object : LlmClient {
            override val name = "coding-only"
            override val supportedCapabilities = setOf(LlmCapability.CODING)
            override fun complete(request: LlmRequest) = "ok"
        }
        val router = ProviderRouter(listOf(codingOnly))
        assertEquals(listOf("coding-only"), router.healthyProvidersFor(LlmCapability.CODING).map { it.name })
        assertTrue(router.healthyProvidersFor(LlmCapability.LISTING).isEmpty())
    }
}
