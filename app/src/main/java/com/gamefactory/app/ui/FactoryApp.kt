package com.gamefactory.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamefactory.app.FactoryViewModel
import com.gamefactory.core.model.PipelineStage

/** Simple screen navigation - no extra dependency needed. */
sealed class Screen {
    data object Home : Screen()
    data object NewProject : Screen()
    data object Project : Screen()
    data object Settings : Screen()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FactoryApp(vm: FactoryViewModel) {
    val ui by vm.uiState.collectAsState()
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Game Factory") },
                actions = {
                    TextButton(onClick = { screen = Screen.Settings }) { Text("Settings") }
                }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (val s = screen) {
                is Screen.Home -> HomeScreen(
                    ui = ui,
                    onNewProject = { screen = Screen.NewProject },
                    onOpenProject = { screen = Screen.Project },
                    onSettings = { screen = Screen.Settings }
                )
                is Screen.NewProject -> NewProjectScreen(vm = vm, ui = ui, onBack = { screen = Screen.Home })
                is Screen.Project -> ProjectScreen(vm = vm, ui = ui, onBack = { screen = Screen.Home })
                is Screen.Settings -> SettingsScreen(vm = vm, onBack = { screen = Screen.Home })
            }
        }
    }
}

@Composable
private fun HomeScreen(
    ui: FactoryViewModel.UiState,
    onNewProject: () -> Unit,
    onOpenProject: () -> Unit,
    onSettings: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("ONE BRIEF IN.\nA COMPLETE PROJECT OUT.", fontSize = 22.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Plan -> Task DAG -> Approval -> Coding -> QA loop -> Security -> Legal -> Export",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onNewProject, modifier = Modifier.fillMaxWidth()) {
            Text("New Project")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onOpenProject, modifier = Modifier.fillMaxWidth(), enabled = ui.current != null) {
            Text(if (ui.current != null) "Open Current Project" else "No active project")
        }
        ui.error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }
    }
}

@Composable
private fun NewProjectScreen(vm: FactoryViewModel, ui: FactoryViewModel.UiState, onBack: () -> Unit) {
    var brief by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState())) {
        Text("Describe your game or app", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Example: a 2D offline arcade game with a space theme, touch controls and a leaderboard",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Project name (optional)") },
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = brief, onValueChange = { brief = it },
            label = { Text("Brief") },
            modifier = Modifier.fillMaxWidth().height(180.dp)
        )
        Spacer(Modifier.height(16.dp))
        if (vm.demoMode) {
            Text("Demo mode: runs fully offline with the built-in mock provider.", fontSize = 12.sp)
        } else if (!vm.geminiKeyPresent()) {
            Text(
                "Gemini API key missing - add it in Settings or enable demo mode.",
                color = MaterialTheme.colorScheme.error, fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(12.dp))
        Row {
            Button(
                onClick = {
                    vm.startProject(brief, name)
                    onBack()
                },
                enabled = brief.isNotBlank() && !ui.running && (vm.demoMode || vm.geminiKeyPresent())
            ) { Text("Start Factory") }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = onBack) { Text("Back") }
        }
    }
}

private val stageOrder = listOf(
    PipelineStage.DEERFLOW_PLANNING to "DeepFlow Planning",
    PipelineStage.GAME_BIBLE to "Game Bible",
    PipelineStage.TASK_DAG to "Task DAG",
    PipelineStage.AWAITING_APPROVAL to "Your Approval",
    PipelineStage.CODING to "Coding",
    PipelineStage.ASSETS to "Assets",
    PipelineStage.BUILD to "Build",
    PipelineStage.QA to "QA / Fix loop",
    PipelineStage.SECURITY to "Security audit",
    PipelineStage.LEGAL to "Legal package",
    PipelineStage.CROSS_PLATFORM to "Cross-platform",
    PipelineStage.LISTING to "Store listing",
    PipelineStage.EXPORT to "Export",
    PipelineStage.DONE to "Complete"
)

@Composable
private fun ProjectScreen(vm: FactoryViewModel, ui: FactoryViewModel.UiState, onBack: () -> Unit) {
    val state = ui.current
    if (state == null) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Text("No project yet."); Spacer(Modifier.height(12.dp)); OutlinedButton(onClick = onBack) { Text("Back") }
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            Text("${state.name} (${state.projectId})", style = MaterialTheme.typography.titleLarge)
            Text("Stage: ${state.stage}", fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
        }

        // Pipeline checklist
        items(stageOrder) { (stage, label) ->
            val status = when {
                state.stage == stage -> "\u25B6"
                stage.ordinal < state.stage.ordinal -> "\u2714"
                else -> "\u25CB"
            }
            Text("$status  $label", fontSize = 15.sp, modifier = Modifier.padding(vertical = 3.dp))
        }

        // Approval cards
        if (state.stage == PipelineStage.AWAITING_APPROVAL) {
            item {
                Spacer(Modifier.height(16.dp))
                Text("Required integrations", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Approve what this project needs. Secret VALUES are entered only in a secure store at build time - never in code.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(state.integrations) { req ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(req.label, style = MaterialTheme.typography.titleSmall)
                        Text(req.reason, fontSize = 11.sp)
                        Text("Variables: ${req.requiredVariables.joinToString()}", fontSize = 11.sp)
                        Row {
                            Checkbox(checked = req.approved, onCheckedChange = { vm.approveIntegration(req.id, it) })
                            Text("Use ${req.label}")
                        }
                    }
                }
            }
            item {
                Button(onClick = { vm.continueAfterApproval() }, enabled = !ui.running) {
                    Text("Approve & Continue")
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        // Tasks
        if (state.tasks.isNotEmpty()) {
            item {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Tasks (${state.doneTasksCount()}/${state.tasks.size} done)",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(4.dp))
            }
            items(state.tasks) { task ->
                Text(
                    "[${task.status}] ${task.id} - ${task.title}",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }

        // Logs
        if (state.logs.isNotEmpty()) {
            item {
                Spacer(Modifier.height(12.dp))
                Text("Log", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
            }
            items(state.logs.takeLast(60)) { log ->
                Text(
                    "${if (log.isError) "!!" else "--"} ${log.message}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (log.isError) MaterialTheme.colorScheme.error else Color.Unspecified
                )
            }
        }

        item {
            Spacer(Modifier.height(20.dp))
            if (ui.zipReady) Text("DONE - export the release ZIP from the files below.", color = MaterialTheme.colorScheme.primary)
            ui.error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = onBack) { Text("Back") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun com.gamefactory.core.model.ProjectState.doneTasksCount() =
    tasks.count { it.status == com.gamefactory.core.model.TaskStatus.DONE }

@Composable
private fun SettingsScreen(vm: FactoryViewModel, onBack: () -> Unit) {
    var demo by remember { mutableStateOf(vm.demoMode) }
    var key by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(vm.geminiModel()) }
    var saved by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState())) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = demo, onCheckedChange = { demo = it; vm.setDemoMode(it) })
            Spacer(Modifier.width(8.dp))
            Text("Demo mode (offline, no API key)")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "With demo mode OFF, planning/coding/listing calls go to the Google Gemini API. Your key is stored encrypted (Android Keystore) and never written into generated code.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = key, onValueChange = { key = it },
            label = { Text(if (vm.geminiKeyPresent()) "Gemini API key (saved - enter to replace)" else "Gemini API key") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = model, onValueChange = { model = it },
            label = { Text("Model name") },
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        Spacer(Modifier.height(12.dp))
        Row {
            Button(onClick = {
                vm.saveGeminiKey(key, model)
                saved = true
            }) { Text("Save") }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = onBack) { Text("Back") }
        }
        if (saved) {
            Spacer(Modifier.height(8.dp))
            Text("Saved securely.", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
        }
    }
}
