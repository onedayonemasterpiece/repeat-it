# Повторение / Repeat It

Standalone Android exposure cards. Application ID: `com.onedayonemasterpiece.repeatit`.

Code, tests and GitHub-hosted builds live here. **All user material, assets, plans and progress remain exclusively in `onedayonemasterpiece/idea-hub/learning/`.** No private decks or PATs belong in this public repository, APK or CI output.

Implementation is written directly by ChatGPT from the owner specification dated 2026-09-07. No Codex. Local OpenCode installs the verified hosted-build artifact via USB ADB. Never uninstall, clear application data, change Record IdeaHub, use a self-hosted runner or put a signing key in this repository.

Canonical specification: https://github.com/onedayonemasterpiece/idea-hub/blob/main/prompts/implementation/deadline-spaced-recall-mvp-20260907.md

## Runtime product contract

After initial Android permissions and GitHub PAT setup, delivery is **active automatically**. There is no separate hidden or user-facing “enable application” gate. The only user stop state is an explicit **Pause**, exposed as one stateful `Поставить на паузу` / `Продолжить показы` control. Opening the app requests a fresh GitHub sync; `Обновить карточки сейчас` explicitly requests one and may be followed by immediately closing the Activity.

The overlay owns exactly one durable pending card at a time. `Помню` / `Повторить` commit a response and close that pending; failures keep the same card open and surface visible feedback. Android may suppress third-party overlays over protected system windows; that must not consume or replace the pending card.

## Build and test

JDK 17, Gradle 8.13, Android SDK 36. `gradle :core:check :app:lintRelease :app:assembleRelease`. Release assembly is unsigned until the separate hosted signing job validates the permanent key. An unsigned artifact is not an installable release.

Dependency-free core checks:

```sh
mkdir -p build/core
javac -d build/core core/src/main/java/com/onedayonemasterpiece/repeatit/core/*.java core/src/test/java/com/onedayonemasterpiece/repeatit/core/CoreChecks.java
java -ea -cp build/core com.onedayonemasterpiece.repeatit.core.CoreChecks
```

Android emulator acceptance uses synthetic data only and verifies automatic activation without an `enabled` flag, the stateful Pause/Resume control, 40 native overlay reaction cycles, delayed pending retention, screen off/on, protected Settings recovery, rotation and finite test-session completion. Passing hosted tests does not establish installation, physical delivery on a specific OEM build, real GitHub progress readback or battery consumption.
