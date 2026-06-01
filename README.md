# GrammarFloat - AI-Powered Grammar Correction and Text Enhancement for Android

## Project Description

GrammarFloat is a native Android application that enhances the system-wide text selection context menu by adding a **"Check Grammar"** option. When a user long-presses text in any app and selects this option, a floating overlay panel appears without forcing the user to leave their current app. 

The application offers powerful AI-driven text enhancements:
- **Grammar Correction:** Instantly checks and suggests grammatical corrections.
- **Explain Correction:** Provides detailed explanations of why specific grammatical changes were made.
- **Tone Adjustment:** Allows users to dynamically change the tone of their text to suit different contexts (e.g., professional, casual, confident).

### Key Features
- **Zero Context Switching:** The original app remains visible and interactive behind the floating overlay.
- **In-Place Replacement:** Automatically swaps the selected text with the corrected version in editable fields, with an automatic clipboard fallback.
- **Privacy-First:** User API keys are stored locally using encrypted storage. The app communicates directly with AI providers without any intermediary backend servers.
- **Provider Choice:** Supports multiple major AI providers (Anthropic, OpenAI, Gemini) so users can choose their preferred model.

## Tech Requirements

- **Language:** Kotlin (Java 17 compatibility)
- **Minimum SDK:** API 24 (Android 7.0)
- **Target & Compile SDK:** API 36
- **Build System:** Gradle (Kotlin DSL `.kts`)
- **UI Toolkit:** XML Views + ViewBinding
- **Core Dependencies:**
  - `androidx.core:core-ktx`
  - `androidx.appcompat:appcompat`
  - `com.google.android.material:material`
  - `androidx.security:security-crypto` (for encrypted API key storage)
  - `com.squareup.okhttp3:okhttp` (direct HTTP client for AI APIs)
  - `org.jetbrains.kotlinx:kotlinx-serialization-json`
  - `org.jetbrains.kotlinx:kotlinx-coroutines-android`

## Architecture

The application is built around a transient, service-based architecture located under the `app.grammarfloat.pro` namespace:

1. **`ProcessTextActivity`**: 
   - An invisible activity that acts as the entry point when the user selects "Check Grammar" from the context menu.
   - Handles the `ACTION_PROCESS_TEXT` intent, verifies overlay permissions, and launches the foreground overlay service.
   - Listens for broadcasts to perform in-place text replacement or fallback to a clipboard copy if the source app clears its selection.
2. **`OverlayService`**: 
   - Runs as a foreground service to stay alive while processing AI requests.
   - Manages the `OverlayPanelController` to draw the floating panel over other apps.
   - Executes asynchronous API calls for grammar checking, explanations, and tone adjustment using Coroutines on `Dispatchers.IO`.
3. **`OverlayPanelController`**: 
   - Located in the `ui` package, it inflates and controls the overlay view.
   - Manages view state transitions and user interactions (Replace, Copy, Explain, Adjust Tone).
4. **Polymorphic API Clients**: 
   - The `ApiClient` interface supports `checkGrammar`, `explainCorrection`, and `adjustTone`.
   - Handled dynamically by `ApiClientFactory` returning the user's selected provider implementation (`AnthropicApiClient`, `OpenAiApiClient`, or `GeminiApiClient`).

## How to Use the App

1. **Initial Setup:** Open the GrammarFloat app from your launcher. Grant the required "Draw over other apps" permission and enter your preferred AI provider's API key.
2. **Select Text:** Go to any application and long-press to select some text.
3. **Check Grammar:** Tap the **"Check Grammar"** option from the system context menu.
4. **Review and Action:** A floating panel will appear showing the suggested corrections. 
   - Tap **Replace** to automatically swap the text in your current app.
   - Tap **Copy** to copy the corrected text.
   - Use the extra tools in the panel to ask for an **Explanation** or to **Adjust Tone**.
   - Tap outside the panel or the close button to dismiss it.

## How to Maintain the Project

- **Adding New AI Providers:** To add a new provider, create a new class implementing the `ApiClient` interface in the `api` package, add the provider to the `Provider` enum, and update the `ApiClientFactory` and `SettingsActivity` UI.
- **Handling Android Updates:** Pay close attention to background service and window overlay restrictions in future Android versions.

## Useful and Important Commands

Here are standard Gradle commands for building and testing the app:

**Build a debug APK:**
```bash
./gradlew assembleDebug
```

**Run unit tests:**
```bash
./gradlew testDebugUnitTest
```

**Run instrumented tests:**
```bash
./gradlew connectedAndroidTest
```

**Clean the project:**
```bash
./gradlew clean
```

**Build a signed release APK (Requires keystore configuration):**
```bash
./gradlew assembleRelease
```
