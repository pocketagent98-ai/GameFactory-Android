package com.gamefactory.core

import com.gamefactory.core.bible.GameBible
import com.gamefactory.core.checkpoint.ProjectStateCodec
import com.gamefactory.core.llm.LlmCapability
import com.gamefactory.core.llm.LlmClient
import com.gamefactory.core.llm.LlmRequest
import com.gamefactory.core.llm.MockLlmClient
import com.gamefactory.core.llm.ProviderRouter
import com.gamefactory.core.model.EngineEvent
import com.gamefactory.core.model.PipelineStage
import com.gamefactory.core.model.SafetyLimits
import com.gamefactory.core.engine.GameFactoryEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.zip.ZipInputStream

class GameFactoryEngineTest {

    private val brief = "Build a 2D offline arcade game with a space theme"

    private fun newEngine(
        client: LlmClient = MockLlmClient(),
        events: MutableList<EngineEvent> = mutableListOf(),
        checkpoints: MutableList<String> = mutableListOf(),
        limits: SafetyLimits = SafetyLimits()
    ): GameFactoryEngine = GameFactoryEngine(
        router = ProviderRouter(listOf(client)),
        limits = limits,
        listener = { events.add(it) },
        checkpointSink = { checkpoints.add(it) },
        idGenerator = { "GF-TEST" }
    )

    @Test
    fun `full pipeline run reaches DONE and produces a valid zip`() {
        val events = mutableListOf<EngineEvent>()
        val checkpoints = mutableListOf<String>()
        val engine = newEngine(events = events, checkpoints = checkpoints)

        val state = engine.runToEnd(brief, "Space Arcade")

        assertEquals(PipelineStage.DONE, state.stage)
        assertTrue("tasks should be done", state.tasks.all { it.status == com.gamefactory.core.model.TaskStatus.DONE })
        assertTrue("should generate source files", state.files.isNotEmpty())
        assertTrue("bible should exist", state.bible.contains("GAME BIBLE"))

        // events sanity
        assertTrue(events.any { it is EngineEvent.StageChanged && it.stage == PipelineStage.DONE })
        assertTrue(events.any { it is EngineEvent.Completed })

        // checkpoint written at multiple stages
        assertTrue(checkpoints.size >= 3)

        // zip valid and contains manifest
        val zip = engine.exportZipBytes()
        val entries = zip.entries().toSet()
        assertTrue("PROJECT_MANIFEST.json" in entries.map { it.name })
        assertTrue(entries.any { it.name.startsWith("game_source/") })
        assertTrue(entries.any { it.name == "store/metadata.txt" })
        assertTrue(entries.any { it.name.startsWith("QA/") })
    }

    private fun ByteArray.entries(): List<java.util.zip.ZipEntry> =
        ZipInputStream(this.inputStream()).use { z ->
            generateSequence { z.nextEntry }.toList()
        }

    @Test
    fun `plan pauses at approval and resume works`() {
        val engine = newEngine()
        val state = engine.planAndAwaitApproval(brief, "Space Arcade")
        assertEquals(PipelineStage.AWAITING_APPROVAL, state.stage)
        assertTrue(engine.state.integrations.isEmpty()) // brief has no backend words

        val done = engine.runAfterApproval()
        assertEquals(PipelineStage.DONE, done.stage)
    }

    @Test
    fun `integration detection asks for backend on auth brief`() {
        val engine = newEngine()
        engine.planAndAwaitApproval("A game with login and leaderboard and cloud saves", "Auth Game")
        val reqs = engine.state.integrations
        assertTrue(reqs.any { it.id == "backend" })
        assertTrue(reqs.first { it.id == "backend" }.requiredVariables.contains("BACKEND_SECRET_KEY"))
        // approving continues the run
        engine.approveIntegration("backend", true)
        val done = engine.runAfterApproval()
        assertEquals(PipelineStage.DONE, done.stage)
        // legal text should mention backend
        assertTrue(done.files.getValue("legal/privacy_and_terms.md").contains("backend"))
    }

    @Test
    fun `router failure during planning is reported as FAILED`() {
        class AlwaysFail : LlmClient {
            override val name = "fail"
            override val supportedCapabilities = setOf(LlmCapability.PLANNING)
            override fun complete(request: LlmRequest): String = throw RuntimeException("provider down")
        }
        val engine = newEngine(client = AlwaysFail())
        try {
            engine.planAndAwaitApproval(brief, "X")
            fail("expected FactoryException")
        } catch (e: GameFactoryEngine.FactoryException) {
            assertEquals(PipelineStage.FAILED, engine.state.stage)
        }
    }

    @Test
    fun `security finding triggers automatic fix loop and then passes`() {
        // First coding call leaks a secret; fix call returns clean code.
        class LeakyThenClean : LlmClient {
            var codingCalls = 0
            override val name = "leaky"
            override val supportedCapabilities = setOf(LlmCapability.PLANNING, LlmCapability.CODING, LlmCapability.LISTING)
            override fun complete(request: LlmRequest): String {
                return if (request.capability == LlmCapability.PLANNING) {
                    "TASK core.window | deps:  | Setup\nTASK ui.hud | deps: core.window | HUD"
                } else if (request.capability == LlmCapability.LISTING) {
                    "TITLE: X\nDESC: y\nKEYWORDS: a"
                } else if (request.purpose.startsWith("fix")) {
                    "FILE bad.gd\n---\nvar clean = 1\nENDFILE\n"
                } else {
                    if (codingCalls++ == 0) {
                        "FILE bad.gd\n---\nconst KEY = \"nvapi-leakedsecretkey_1234567890ab\"\nENDFILE\n"
                    } else {
                        "FILE good.gd\n---\nvar x = 2\nENDFILE\n"
                    }
                }
            }
        }
        val leaky = LeakyThenClean()
        val engine = newEngine(client = leaky)
        val state = engine.runToEnd(brief, "Leaky")
        assertEquals(PipelineStage.DONE, state.stage)
        assertTrue(state.successfulFixes.isNotEmpty())
        // the file must be clean now
        assertTrue(!state.files.getValue("bad.gd").contains("nvapi-"))
    }

    @Test
    fun `task failure after retries fails the run instead of looping forever`() {
        class NoFilesCoder : LlmClient {
            override val name = "nofiles"
            override val supportedCapabilities = setOf(LlmCapability.PLANNING, LlmCapability.CODING, LlmCapability.LISTING)
            override fun complete(request: LlmRequest): String = when (request.capability) {
                LlmCapability.PLANNING -> "TASK core.window | deps:  | Setup"
                LlmCapability.LISTING -> "TITLE: X\nDESC: y\nKEYWORDS: a"
                else -> "no file blocks at all" // coder never returns FILE
            }
        }
        val engine = newEngine(client = NoFilesCoder(), limits = SafetyLimits(maxRetriesPerTask = 2))
        try {
            engine.runToEnd(brief, "Doomed")
            fail("expected FactoryException")
        } catch (e: GameFactoryEngine.FactoryException) {
            assertEquals(PipelineStage.FAILED, engine.state.stage)
            assertTrue(engine.state.knownErrors.isNotEmpty())
        }
    }

    @Test
    fun `bible parse round trip keeps the name`() {
        val engine = newEngine()
        engine.planAndAwaitApproval(brief, "Space Arcade")
        val bible = GameBible.build(engine.state)
        val parsed = GameBible.parse(bible)
        assertEquals("Space Arcade", parsed.name)
        assertTrue(parsed.sections.containsKey("Brief"))
    }

    @Test
    fun `checkpoint codec round trips the whole state`() {
        val engine = newEngine()
        val state = engine.runToEnd(brief, "Space Arcade")
        val encoded = ProjectStateCodec.encode(state)
        val decoded = ProjectStateCodec.decode(encoded)
        assertEquals(state.projectId, decoded.projectId)
        assertEquals(state.stage, decoded.stage)
        assertEquals(state.tasks.size, decoded.tasks.size)
        assertEquals(state.files.keys, decoded.files.keys)
        state.files.forEach { (k, v) -> assertEquals(v, decoded.files[k]) }
        assertEquals(state.integrations, decoded.integrations)
        assertEquals(state.successfulFixes, decoded.successfulFixes)
        assertEquals(state.logs.size, decoded.logs.size)
    }

    @Test
    fun `llm budget is enforced`() {
        val mock = MockLlmClient()
        val engine = newEngine(client = mock, limits = SafetyLimits(maxLlmCallsPerRun = 1))
        try {
            engine.runToEnd(brief, "Budget")
            fail("expected budget exhaustion")
        } catch (e: GameFactoryEngine.FactoryException) {
            assertEquals(PipelineStage.FAILED, engine.state.stage)
            assertTrue(e.message!!.contains("budget"))
        }
    }

    @Test
    fun `many sequential runs are stable (mini stress test)`() {
        for (i in 1..25) {
            val engine = newEngine()
            val state = engine.runToEnd("arcade game number $i with platformer physics", "Run $i")
            assertEquals(PipelineStage.DONE, state.stage)
            assertNotNull(engine.finalZip)
            assertTrue(engine.finalZip!!.size > 100)
        }
    }
}
