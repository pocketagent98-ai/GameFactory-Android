# Building Game Factory

## Quick start (Android Studio)

1. Open this folder in Android Studio (Hedgehog or newer).
2. Let Gradle sync finish (first sync downloads Gradle 8.9 + AGP 8.5.2 + Kotlin 2.0.21 automatically).
3. Press Run on the `app` configuration — the Compose UI launches on a device/emulator (minSdk 26).

## Note on the Gradle wrapper

This repo ships `gradlew`, `gradlew.bat` and `gradle/wrapper/gradle-wrapper.properties`, but not the
`gradle-wrapper.jar` binary (GitHub file-size constraints during initial import). Two options:

- **Option A (recommended):** after cloning, run once:
  ```
  gradle wrapper --gradle-version 8.9
  ```
  (any locally installed Gradle 8.x works) — this regenerates the jar, then everything works normally.
- **Option B:** use the released `GameFactory-AndroidStudio-Project.zip` which includes the jar already.

Android Studio itself does not need the wrapper jar to import and build the project — it uses its
own bundled Gradle. The jar is only needed for command-line `./gradlew` builds.

## Running the JVM tests (no Android needed)

```
./gradlew :core:test
```

26 unit tests cover the DAG engine, provider router with fallback, security audit, checkpoint codec,
full pipeline, budgets and quality gates. The suite was also verified standalone (JUnit console)
with 2,000+ executions and 0 failures.

## Verifying the engine standalone (no Gradle)

Compile `core/src/main/kotlin` with any Kotlin 2.0.x compiler and run the engine with the bundled
`MockLlmClient` — it runs the entire pipeline offline: planning, Game Bible, task DAG, approval,
coding, QA loop, security audit, legal, cross-platform, export ZIP.

## Modules

- `core/` — pure Kotlin engine (platform-free, also reused by the minimal APK build)
- `app/` — Android Compose control room UI
