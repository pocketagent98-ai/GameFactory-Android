# Game Factory (Android)

**एक बार brief दो — बाकी factory खुद सोचे, बनाए, जाँचे और final package दे।**
*One brief in. A complete project out.*

This is the Android control panel + orchestration core for the Game Factory
architecture: a multi-stage autonomous pipeline that turns a game/app idea into
a planned, coded, QA-tested, security-audited and packaged project.

```
Brief ──▶ DeepFlow Planning ──▶ Game Bible ──▶ Task DAG ──▶ YOUR APPROVAL
        ──▶ Coding ──▶ Assets ──▶ Build ──▶ QA / Fix loop ──▶ Security audit
        ──▶ Legal package ──▶ Cross-platform matrix ──▶ Store listing ──▶ FINAL ZIP
```

## What is inside

| Module | Contents |
|---|---|
| `:core` (pure Kotlin/JVM) | The whole factory engine: planner, DAG ops, coding loop, QA gates, security scanner, legal generator, checkpoint codec, ZIP exporter, provider router with fallback |
| `:app` (Android, Kotlin + Compose) | Control panel UI: new project, live pipeline status, task list, logs, integration approval cards, settings |

## How to open (Android Studio)

1. Install **Android Studio** (Koala or newer) with SDK 34.
2. `git clone` this repository (or download the ZIP and unzip).
3. In Android Studio: **File ▶ Open ▶** select the `GameFactory` folder.
4. Let Gradle sync finish (first sync downloads dependencies).
5. Press **Run** to install on a device/emulator (minSdk 26, Android 8.0+).

## How to run the tests

The engine is fully unit-tested on the JVM (no device needed):

```
./gradlew :core:test
```

26 tests cover: DAG topological ordering & cycle detection, provider router
fallback & cooldown, security secret scanning, the full pipeline (plan →
approval → coding → QA loop → export), the automatic security-fix loop,
budget/anti-infinite-loop limits, checkpoint round-trips, ZIP validity and a
25-run stability stress test.

## Modes

- **Demo mode (default, offline):** the built-in deterministic mock provider
  drives the entire pipeline with no API key and no network.
- **Gemini mode:** enter your Google Gemini API key in Settings. It is stored
  encrypted with the Android Keystore, is never written into generated code and
  never appears in logs. Planning/coding/listing calls then go to the Gemini API.

## Security rules this project enforces (from the architecture)

- No secrets in code, chat or logs - only in a secure store.
- A separate security audit gate scans all generated files for hardcoded keys
  (OpenAI/NVIDIA/Google/Supabase patterns, passwords, bearer tokens).
- The QA loop (build → test → diagnose → fix → retest) has hard limits:
  max retries per task, same-error threshold, LLM call budget, runtime budget.
- Every stage writes a checkpoint, so a run can be resumed.
- The final ZIP contains source + bible + manifest + QA report + legal drafts.

## Honest scope note

The phone app plans, generates, audits and packages the project — it does NOT
compile a Godot/Android binary on the phone. Actual compilation of generated
projects happens on your PC / build server (the "execution plane" of the
architecture). The engine, gates, checkpoints and exports are real and tested.

## Project layout

```
core/src/main/kotlin/com/gamefactory/core/
  model/        ProjectState, TaskNode, PipelineStage, SafetyLimits, events
  llm/          LlmClient, GeminiClient, ProviderRouter (fallback), MockLlmClient
  dag/          DagOps (topological order, cycles, ready tasks)
  bible/        GameBible (source of truth, build & parse)
  engine/       GameFactoryEngine (master orchestrator), QualityGates
  security/     SecurityAudit (hardcoded-secret scanner)
  checkpoint/   ProjectStateCodec (full state round-trip)
  export/       ZipExporter (release package)
core/src/test/kotlin/     26 JVM unit tests
app/src/main/java/com/gamefactory/app/
  MainActivity, FactoryViewModel, AndroidSecretStore, ui/FactoryApp
```
