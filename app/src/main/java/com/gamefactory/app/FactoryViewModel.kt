package com.gamefactory.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gamefactory.core.checkpoint.ProjectStateCodec
import com.gamefactory.core.engine.GameFactoryEngine
import com.gamefactory.core.llm.GeminiClient
import com.gamefactory.core.llm.LlmClient
import com.gamefactory.core.llm.MockLlmClient
import com.gamefactory.core.llm.ProviderRouter
import com.gamefactory.core.model.EngineEvent
import com.gamefactory.core.model.PipelineStage
import com.gamefactory.core.model.ProjectState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Android glue for the core engine. The engine runs on a single worker
 * thread; all progress flows into [uiState] for Compose to render.
 */
class FactoryViewModel(
    private val appContext: Context,
    private val secrets: AndroidSecretStore
) : ViewModel() {

    data class UiState(
        val projects: List<ProjectState> = emptyList(),
        val current: ProjectState? = null,
        val running: Boolean = false,
        val awaitingApproval: Boolean = false,
        val zipReady: Boolean = false,
        val error: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var engine: GameFactoryEngine? = null

    val demoMode: Boolean
        get() = appContext.getSharedPreferences("gf_settings", Context.MODE_PRIVATE)
            .getBoolean(AndroidSecretStore.PREF_DEMO_MODE, true)

    fun setDemoMode(enabled: Boolean) {
        appContext.getSharedPreferences("gf_settings", Context.MODE_PRIVATE)
            .edit().putBoolean(AndroidSecretStore.PREF_DEMO_MODE, enabled).apply()
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun saveGeminiKey(key: String, model: String) {
        secrets.put(AndroidSecretStore.KEY_GEMINI, key.trim())
        appContext.getSharedPreferences("gf_settings", Context.MODE_PRIVATE)
            .edit().putString(AndroidSecretStore.KEY_MODEL, model.trim()).apply()
    }

    fun geminiKeyPresent(): Boolean = secrets.has(AndroidSecretStore.KEY_GEMINI)
    fun geminiModel(): String =
        appContext.getSharedPreferences("gf_settings", Context.MODE_PRIVATE)
            .getString(AndroidSecretStore.KEY_MODEL, "gemini-2.0-flash") ?: "gemini-2.0-flash"

    // ---------------- pipeline control ----------------

    fun startProject(brief: String, name: String) {
        if (_uiState.value.running) return
        val router = buildRouter()
        val sink = checkpointFileSink()
        _uiState.value = _uiState.value.copy(running = true, error = null, zipReady = false, awaitingApproval = false)

        executor.execute {
            try {
                val eng = GameFactoryEngine(
                    router = router,
                    listener = { ev -> publishEvent(ev) },
                    checkpointSink = sink,
                    idGenerator = { "GF-" + AndroidSecretStore.randomId() }
                )
                engine = eng
                val state = eng.planAndAwaitApproval(brief, name)
                pushState(state)
                _uiState.value = _uiState.value.copy(
                    running = false,
                    awaitingApproval = state.stage == PipelineStage.AWAITING_APPROVAL
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    running = false,
                    error = e.message ?: "unknown error"
                )
            }
        }
    }

    fun continueAfterApproval() {
        val eng = engine ?: return
        if (_uiState.value.running) return
        _uiState.value = _uiState.value.copy(running = true, awaitingApproval = false, error = null)
        executor.execute {
            try {
                val state = eng.runAfterApproval()
                pushState(state)
                _uiState.value = _uiState.value.copy(running = false, zipReady = state.stage == PipelineStage.DONE)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(running = false, error = e.message ?: "unknown error")
            }
        }
    }

    fun approveIntegration(id: String, approved: Boolean) {
        engine?.approveIntegration(id, approved)
        engine?.state?.let { pushState(it) }
    }

    fun saveZipTo(uri: java.io.OutputStream) {
        val eng = engine ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                uri.write(eng.exportZipBytes())
                uri.flush()
                uri.close()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "export failed: ${e.message}")
            }
        }
    }

    override fun onCleared() {
        executor.shutdownNow()
        super.onCleared()
    }

    // ---------------- internals ----------------

    private fun buildRouter(): ProviderRouter {
        val clients = mutableListOf<LlmClient>()
        if (demoMode) {
            clients += MockLlmClient()
        } else {
            clients += GeminiClient(
                apiKeySupplier = { secrets.get(AndroidSecretStore.KEY_GEMINI) },
                model = geminiModel()
            )
        }
        return ProviderRouter(clients)
    }

    private fun publishEvent(ev: EngineEvent) {
        when (ev) {
            is EngineEvent.Log -> engine?.state?.let { pushState(it) }
            is EngineEvent.StageChanged -> engine?.state?.let { pushState(it) }
            is EngineEvent.TaskUpdated, is EngineEvent.FileWritten -> engine?.state?.let { pushState(it) }
            else -> {}
        }
    }

    private fun pushState(state: ProjectState) {
        _uiState.value = _uiState.value.copy(current = state)
    }

    private fun checkpointFileSink(): (String) -> Unit = { encoded ->
        try {
            val dir = File(appContext.filesDir, "checkpoints").apply { mkdirs() }
            val id = ProjectStateCodec.decode(encoded).projectId.ifBlank { "latest" }
            File(dir, "$id.checkpoint").writeText(encoded)
        } catch (e: Exception) {
            // checkpointing must never kill the pipeline
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = context.applicationContext
                return FactoryViewModel(app, AndroidSecretStore(app)) as T
            }
        }
    }
}
