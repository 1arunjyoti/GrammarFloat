# GrammarOverlay — Android App Project Plan

> **Document purpose:** Comprehensive project plan ready for execution by AI coding agents.
> All decisions are final unless explicitly marked `[DECISION NEEDED]`.

---

## 1. Project Introduction

### 1.1 What It Is

GrammarOverlay is a native Android app that adds a **"Check Grammar"** option to the system-wide text selection context menu (the floating bar that appears on long-press in any app). When tapped, it shows a floating overlay panel — without leaving the source app — displaying an AI-corrected version of the selected text. The user can then replace the original text in-place or copy the result.

### 1.2 Problem It Solves

Android has no system-level grammar or clarity checker. Users who want to fix text must copy it, switch to a separate app, paste, correct, copy again, switch back, and paste. This app collapses that into a single tap with zero context switching.

### 1.3 User Flow (End-to-End)

```
1. User long-presses text in any app (Gmail, WhatsApp, Notes, browser, etc.)
2. Text selection context menu appears
3. User taps "Check Grammar" from the menu
4. A floating panel slides up over the current app
5. Panel shows a spinner while the AI call completes (~1–3 seconds)
6. Panel shows the original text and the corrected/improved version side by side
7a. If source field is editable → user taps "Replace" → text is swapped in-place → panel closes
7b. User taps "Copy" → corrected text goes to clipboard → panel closes
7c. User taps "✕" → panel closes with no changes
```

### 1.4 Core Goals

- **Zero context switching.** The source app must remain visible and interactive behind the overlay.
- **Fast.** Panel must appear instantly (before the API call finishes). Spinner bridges the wait.
- **Correct write-back.** "Replace" must atomically swap the selected text in the source app, not just copy to clipboard.
- **Privacy-respecting.** User API keys are stored locally in encrypted storage. No backend server. No analytics. No logging of user text.
- **Provider Choice.** Support multiple major AI providers (Anthropic, OpenAI, Gemini) so users can choose their preferred model, utilizing direct API calls for speed and minimal overhead.
- **Graceful degradation.** Every failure (no internet, API error, no permission, read-only field) has a clear, non-crashing path.

### 1.5 Non-Goals (Out of Scope for v1)

- Paid tiers or subscription management
- Translation or other text operations (grammar + clarity only)
- iOS version
- Tablet-specific layouts
- History of past corrections

---

## 2. Tech Requirements

### 2.1 Language and Platform

| Item | Choice | Reason |
|---|---|---|
| Language | Kotlin | Modern Android standard, coroutine support |
| Min SDK | API 23 (Android 6.0) | Minimum for `ACTION_PROCESS_TEXT` |
| Target SDK | API 35 (Android 15) | Current Play Store requirement |
| Build system | Gradle (Kotlin DSL `.kts`) | Standard |
| UI toolkit | XML Views + ViewBinding | Simpler for overlay/service context than Compose |

> **Why not Jetpack Compose?** Compose requires an `Activity` or `ComposeView` host. Inflating Compose inside a `WindowManager` overlay is possible but adds friction and complexity for v1. XML Views are straightforward here.

### 2.2 Dependencies

```kotlin
// build.gradle.kts (app)

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Encrypted storage for API key
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // HTTP client for AI API calls
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON parsing
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Lifecycle (for coroutine scopes in Service)
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
}
```

> No Retrofit. OkHttp directly is sufficient for a single API endpoint and avoids code generation overhead.

### 2.3 Android Permissions

```xml
<!-- AndroidManifest.xml -->

<!-- Required: Draw floating overlay on top of other apps -->
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />

<!-- Required: Keep OverlayService alive while API call is in progress -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />

<!-- Required: Android 14+ must declare foreground service type -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />

<!-- Required: AI API call -->
<uses-permission android:name="android.permission.INTERNET" />
```

**Permission behaviour notes:**
- `SYSTEM_ALERT_WINDOW` is a special permission — it cannot be granted at install time. The app must send the user to `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` for manual approval.
- `FOREGROUND_SERVICE_SPECIAL_USE` requires a `<property>` tag in the manifest explaining its use (Play Store requirement for API 34+). Value: `"Used to display a grammar-checking overlay panel while processing selected text."`
- There are no runtime permissions beyond the overlay one. No contacts, no storage, no location.

### 2.4 AI API Specifications

The app supports three AI providers: Anthropic, OpenAI, and Gemini. Users configure their API keys in settings and select their active provider. For performance and app size optimization, all API requests are made directly using OkHttp and JSON serialization without third-party SDKs.

#### 2.4.1 Universal System Prompt
```
You are a grammar and clarity assistant. The user will provide a text selection.
Your job is to:
1. Fix all grammar, spelling, and punctuation errors.
2. Improve sentence clarity and flow where needed, without changing the meaning or tone.
3. Return ONLY the corrected text — no explanations, no preamble, no quotes around the result.
If the text is already correct, return it unchanged.
```

#### 2.4.2 Provider Matrix

| Feature | Anthropic | OpenAI | Gemini |
|---|---|---|---|
| **Default Model** | `claude-3-5-haiku-20241022` | `gpt-4o-mini` | `gemini-2.0-flash` |
| **Endpoint** | `https://api.anthropic.com/v1/messages` | `https://api.openai.com/v1/chat/completions` | `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent` |
| **Auth Mechanism** | Header `x-api-key: {userKey}` | Header `Authorization: Bearer {userKey}` | Query parameter `?key={userKey}` |
| **Timeout** | 30 seconds | 30 seconds | 30 seconds |

---

#### 2.4.3 Anthropic API Details
- **Headers:**
  - `x-api-key: {userKey}`
  - `anthropic-version: 2023-06-01`
  - `content-type: application/json`
- **Request Body:**
  ```json
  {
    "model": "claude-3-5-haiku-20241022",
    "max_tokens": 1024,
    "system": "<system prompt>",
    "messages": [
      { "role": "user", "content": "<selected text>" }
    ]
  }
  ```
- **Response Parsing:** Extract `response.content[0].text`.

---

#### 2.4.4 OpenAI API Details
- **Headers:**
  - `Authorization: Bearer {userKey}`
  - `content-type: application/json`
- **Request Body:**
  ```json
  {
    "model": "gpt-4o-mini",
    "max_tokens": 1024,
    "messages": [
      { "role": "system", "content": "<system prompt>" },
      { "role": "user", "content": "<selected text>" }
    ]
  }
  ```
- **Response Parsing:** Extract `response.choices[0].message.content`.

---

#### 2.4.5 Gemini API Details
- **Headers:**
  - `content-type: application/json`
- **Endpoint URL:** `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key={userKey}`
- **Request Body:**
  ```json
  {
    "contents": [
      {
        "parts": [
          { "text": "<selected text>" }
        ]
      }
    ],
    "systemInstruction": {
      "parts": [
        { "text": "<system prompt>" }
      ]
    },
    "generationConfig": {
      "maxOutputTokens": 1024
    }
  }
  ```
- **Response Parsing:** Extract `response.candidates[0].content.parts[0].text`.

### 2.5 API Key & Provider Storage

- **Storage Type:** Stored securely using `EncryptedSharedPreferences` (AndroidX Security library).
- **Preference Keys:**
  - `"active_provider"`: String matching `Provider` enum name (e.g., `"ANTHROPIC"`, `"OPENAI"`, `"GEMINI"`). Defaults to `"ANTHROPIC"`.
  - `"api_key_anthropic"`: Stores the user's Anthropic API key.
  - `"api_key_openai"`: Stores the user's OpenAI API key.
  - `"api_key_gemini"`: Stores the user's Gemini API key.
- **Privacy:** Keys are never logged. Keys are only transmitted directly to the respective provider's host (`api.anthropic.com`, `api.openai.com`, or `generativelanguage.googleapis.com`).
- **Validation on Save:** When a key is saved/updated, the app performs a minimal test call (e.g. `max_tokens: 1` or lightweight text prompt) to that specific provider to confirm validity before writing to encrypted preferences.

### 2.6 Service → Activity Communication

- Uses `LocalBroadcastManager` (within-process only, no IPC).
- Two broadcast actions defined as constants in a `BroadcastActions` object:
  - `BroadcastActions.REPLACE` — user tapped Replace; payload: corrected text string
  - `BroadcastActions.DISMISS` — user tapped Copy or ✕; no payload

---

## 3. Architecture

### 3.1 Component Overview

```
┌─────────────────────────────────────────────────────────────┐
│                        Android System                        │
│                                                             │
│  Text selection menu  ──ACTION_PROCESS_TEXT──►  ProcessText │
│                                                  Activity   │
└─────────────────────────────────────────────────────────────┘
                                  │
                    (invisible, stays alive)
                                  │
              ┌───────────────────▼───────────────────┐
              │           ProcessTextActivity          │
              │  - Reads EXTRA_PROCESS_TEXT            │
              │  - Reads EXTRA_PROCESS_TEXT_READONLY   │
              │  - Checks overlay permission           │
              │  - Starts OverlayService               │
              │  - Registers LocalBroadcast receiver   │
              │  - Waits for REPLACE or DISMISS signal │
              └───────────────────┬───────────────────┘
                                  │ startForegroundService()
                                  │
              ┌───────────────────▼───────────────────┐
              │             OverlayService             │
              │  - Draws panel via WindowManager       │
              │  - Makes AI API call (Coroutine/IO)    │
              │  - Updates panel with result           │
              │  - Sends LocalBroadcast on user action │
              └───────────────────────────────────────┘
                                  │
                    LocalBroadcast (REPLACE / DISMISS)
                                  │
              ┌───────────────────▼───────────────────┐
              │           ProcessTextActivity          │
              │  REPLACE → setResult(RESULT_OK) +      │
              │            finish()                    │
              │  DISMISS → finish()                    │
              └───────────────────────────────────────┘
```

### 3.2 File and Package Structure

```
app/src/main/
├── java/com/yourname/grammaroverlay/
│   │
│   ├── MainActivity.kt                  # Launcher entry; redirects to SettingsActivity
│   │
│   ├── SettingsActivity.kt              # UI for provider selection, API keys inputs, and permissions
│   │
│   ├── ProcessTextActivity.kt           # ACTION_PROCESS_TEXT handler; invisible; stays alive
│   │
│   ├── overlay/
│   │   ├── OverlayService.kt            # Foreground service; owns WindowManager and panel
│   │   ├── OverlayPanelController.kt    # Inflates and controls overlay_panel.xml views
│   │   └── OverlayState.kt             # Sealed class: Loading | Result | Error
│   │
│   ├── api/
│   │   ├── Provider.kt                  # Enum: ANTHROPIC, OPENAI, GEMINI
│   │   ├── ApiClient.kt                 # Interface: checkGrammar(text, apiKey): ApiResult
│   │   ├── AnthropicApiClient.kt        # Anthropic OkHttp implementation
│   │   ├── OpenAiApiClient.kt           # OpenAI OkHttp implementation
│   │   ├── GeminiApiClient.kt           # Gemini OkHttp implementation
│   │   ├── ApiClientFactory.kt          # Simple factory resolving the active ApiClient
│   │   └── ApiResult.kt                 # Sealed class: Success(text) | Failure(message)
│   │
│   ├── storage/
│   │   └── ApiKeyStore.kt              # EncryptedSharedPreferences wrapper for keys & active provider
│   │
│   └── util/
│       ├── BroadcastActions.kt         # const val REPLACE, DISMISS action strings
│       ├── OverlayPermissionHelper.kt  # canDrawOverlays() check + intent to settings
│       └── Extensions.kt              # Kotlin extension functions (View, String, etc.)
│
└── res/
    ├── layout/
    │   ├── activity_settings.xml        # Contains provider dropdown and key inputs
    │   └── overlay_panel.xml           # The floating panel layout
    ├── values/
    │   ├── strings.xml
    │   ├── colors.xml
    │   └── themes.xml                  # Includes Theme.Translucent for ProcessTextActivity
    └── drawable/
        └── bg_overlay_panel.xml        # Rounded rect background for panel card
```

### 3.3 Component Specifications

---

#### `ProcessTextActivity`

**Theme:** `Theme.Translucent.NoTitleBar` (invisible window, no dim, no animation)

**Manifest declaration:**
```xml
<activity
    android:name=".ProcessTextActivity"
    android:label="Check Grammar"
    android:theme="@style/Theme.Translucent.NoTitleBar"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.PROCESS_TEXT" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="text/plain" />
    </intent-filter>
</activity>
```

**`onCreate()` logic (ordered):**
1. Read `intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT)` → `selectedText`
2. Read `intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)` → `isReadOnly`
3. If `selectedText` is null or blank → `finish()` immediately (no-op)
4. If `!Settings.canDrawOverlays(this)` → start `SettingsActivity` with a flag `EXTRA_NEEDS_PERMISSION = true` → `finish()`
5. If `ApiKeyStore.getApiKey(ApiKeyStore.getActiveProvider())` is null/blank → start `SettingsActivity` with flag `EXTRA_NEEDS_API_KEY = true` → `finish()`
6. Register `LocalBroadcastManager` receiver for `BroadcastActions.REPLACE` and `BroadcastActions.DISMISS`
7. Build and start `OverlayService` intent with extras: `selectedText`, `isReadOnly`
8. Do NOT call `finish()` here — activity stays alive and waits

**Broadcast receiver logic:**
- `BroadcastActions.REPLACE` received:
  ```kotlin
  val correctedText = intent.getStringExtra("corrected_text")
  val result = Intent().apply {
      putExtra(Intent.EXTRA_PROCESS_TEXT, correctedText)
  }
  setResult(RESULT_OK, result)
  finish()
  ```
- `BroadcastActions.DISMISS` received:
  ```kotlin
  setResult(RESULT_CANCELED)
  finish()
  ```

**`onDestroy()`:** Unregister the broadcast receiver.

---

#### `OverlayService`

**Type:** `LifecycleService` (from `androidx.lifecycle:lifecycle-service`) — gives coroutine scope via `lifecycleScope`.

**Foreground notification:** Must be started immediately in `onCreate()` before any other work. Notification channel ID: `"grammar_overlay"`. Notification text: `"Grammar check in progress…"`. Priority: `PRIORITY_LOW`. No sound, no vibration. Auto-cancelled when service stops.

**`onStartCommand()` logic:**
1. Read `selectedText` and `isReadOnly` from the intent
2. Call `OverlayPanelController.show(windowManager, isReadOnly)` — draws panel in Loading state
3. Launch coroutine on `Dispatchers.IO`:
   ```kotlin
   lifecycleScope.launch(Dispatchers.IO) {
       val provider = ApiKeyStore.getActiveProvider()
       val apiKey = ApiKeyStore.getApiKey(provider) ?: ""
       val client = ApiClientFactory.getClient(provider)
       val result = client.checkGrammar(selectedText, apiKey)
       withContext(Dispatchers.Main) {
           when (result) {
               is ApiResult.Success -> panelController.showResult(result.text)
               is ApiResult.Failure -> panelController.showError(result.message)
           }
       }
   }
   ```
4. Return `START_NOT_STICKY`

**`stopSelf()` is called** after the broadcast is sent (triggered by user action in panel controller callbacks).

---

#### `OverlayPanelController`

**Responsibilities:** Inflate `overlay_panel.xml`, add it to `WindowManager`, handle all view state transitions, and fire callbacks to `OverlayService` when the user acts.

**`WindowManager.LayoutParams` configuration:**
```kotlin
WindowManager.LayoutParams(
    WindowManager.LayoutParams.MATCH_PARENT,
    WindowManager.LayoutParams.WRAP_CONTENT,
    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,  // API 26+
    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
    PixelFormat.TRANSLUCENT
).apply {
    gravity = Gravity.BOTTOM
}
```

**`FLAG_NOT_TOUCH_MODAL`** — allows touches outside the panel to pass through to the app below (important: user can still interact with source app while panel is visible).

**`FLAG_WATCH_OUTSIDE_TOUCH`** — allows detecting taps outside the panel so it can be dismissed by tapping away.

**Panel states (driven by `OverlayState` sealed class):**
- `Loading` → show spinner, original text preview, hide result and action buttons
- `Result(correctedText)` → hide spinner, show diff view, show Replace/Copy buttons (Replace hidden if `isReadOnly`)
- `Error(message)` → hide spinner, show error message, show only Close button

**Diff display:** Show original text in a muted `TextView` with strikethrough styling, and corrected text in a bold/green `TextView` below it. If the texts are identical, show a single "No changes needed ✓" message instead of a diff.

**Button callbacks:**
- Replace button → `LocalBroadcastManager.sendBroadcast(Intent(BroadcastActions.REPLACE).putExtra("corrected_text", correctedText))`
- Copy button → `ClipboardManager.setPrimaryClip(...)` then dismiss
- ✕ button → `LocalBroadcastManager.sendBroadcast(Intent(BroadcastActions.DISMISS))`
- Outside tap → same as ✕

**Cleanup:** `WindowManager.removeView(panelView)` must be called before the service stops.

---

#### `overlay_panel.xml` — Layout Spec

```
┌──────────────────────────────────────────┐
│  ✕                              [drag handle] │  ← Top bar (drag-to-reposition, optional v2)
├──────────────────────────────────────────┤
│  Original:                               │
│  "He go to the store yesterday."         │  ← tvOriginal (muted, strikethrough if changed)
├──────────────────────────────────────────┤
│  ░░░░░░░░░░░░░░░░ (spinner)              │  ← progressBar (visible during Loading)
│                                          │
│  Corrected:                              │
│  "He went to the store yesterday."       │  ← tvCorrected (visible after result)
├──────────────────────────────────────────┤
│           [Replace]     [Copy]           │  ← action buttons
└──────────────────────────────────────────┘
```

- Panel is a `MaterialCardView` with `cornerRadius=16dp`, elevation=`8dp`
- Background: white (light) / dark surface (dark mode) — use `?attr/colorSurface`
- Width: `match_parent` with `16dp` horizontal margin (achieved via `WindowManager` params + padding)
- Max height: `40%` of screen height; use `ScrollView` around text areas to handle long selections
- Entry animation: slide up from bottom (`R.anim.slide_in_bottom`), 250ms ease-out
- Exit animation: slide down (`R.anim.slide_out_bottom`), 200ms ease-in

---

#### Polymorphic API Clients (`ApiClient`, `AnthropicApiClient`, `OpenAiApiClient`, `GeminiApiClient`)

All clients implement the `ApiClient` interface and perform custom asynchronous OkHttp network requests without using external vendor SDKs.

```kotlin
interface ApiClient {
    suspend fun checkGrammar(text: String, apiKey: String): ApiResult
}
```

##### `AnthropicApiClient`
- **Request Serialization:** Custom JSON payload using standard kotlinx-serialization (setting `model`, `max_tokens`, `system`, and `messages`).
- **Response Handling:**
  - HTTP 200: Parse `content[0].text` -> `ApiResult.Success`.
  - HTTP 401: Return `ApiResult.Failure("Invalid Anthropic key. Please update it in Settings.")`.
  - HTTP 429: Return `ApiResult.Failure("Anthropic rate limit reached. Please wait.")`.
  - HTTP 4xx/5xx: Return `ApiResult.Failure("Anthropic API error: ${response.code}")`.
  - `IOException` (no network): Return `ApiResult.Failure("No internet connection.")`.

##### `OpenAiApiClient`
- **Request Serialization:** Custom JSON payload with `model: "gpt-4o-mini"`, `max_tokens: 1024`, and standard array of system/user messages.
- **Response Handling:**
  - HTTP 200: Parse `choices[0].message.content` -> `ApiResult.Success`.
  - HTTP 401: Return `ApiResult.Failure("Invalid OpenAI key. Please update it in Settings.")`.
  - HTTP 429: Return `ApiResult.Failure("OpenAI rate limit reached. Please wait.")`.
  - HTTP 4xx/5xx: Return `ApiResult.Failure("OpenAI API error: ${response.code}")`.
  - `IOException`: Return `ApiResult.Failure("No internet connection.")`.

##### `GeminiApiClient`
- **Request Serialization:** Custom JSON payload containing the text under the `contents` part list, plus system instructions and generation config.
- **Response Handling:**
  - HTTP 200: Parse `candidates[0].content.parts[0].text` -> `ApiResult.Success`.
  - HTTP 400/401: Return `ApiResult.Failure("Invalid Gemini key or bad request. Please update in Settings.")`.
  - HTTP 429: Return `ApiResult.Failure("Gemini rate limit reached. Please wait.")`.
  - HTTP 4xx/5xx: Return `ApiResult.Failure("Gemini API error: ${response.code}")`.
  - `IOException`: Return `ApiResult.Failure("No internet connection.")`.

---

#### `SettingsActivity`

**UI elements:**
- **Provider Selector:** `Spinner` or Material `ExposedDropdownMenu` containing `Provider` options (Anthropic, OpenAI, Gemini).
- **API Key Inputs:** Three distinct `TextInputLayout` / `EditText` input blocks (input type: `textPassword`), showing/hiding based on the selected provider.
- **Action Buttons:**
  - `Button` — "Save & Validate Key" (validates the key for the *currently selected* provider using a lightweight test call).
  - `Button` — "Grant Draw-Over-Apps Permission" (toggles based on overlay authorization state).
- **Status Indicators:** Text/icons representing key presence and permission status.
- **Privacy Notice:** Informative subtext detailing that all keys remain stored locally inside Android's secure encrypted shared preferences and are never sent to external servers other than direct LLM hosts.

**Key validation & saving flow:**
1. User selects a provider, enters their API key, and taps "Save & Validate".
2. Show inline spinner/progress bar.
3. Instantiate the corresponding `ApiClient` from the active selection.
4. Execute `client.checkGrammar("Hello", enteredKey)` on `Dispatchers.IO` using a fast timeout context.
5. If the request succeeds (`ApiResult.Success`):
   - Save the key to `ApiKeyStore.setApiKey(selectedProvider, enteredKey)`.
   - Update `ApiKeyStore.setActiveProvider(selectedProvider)`.
   - Show success toast/banner.
6. If the request fails (`ApiResult.Failure`):
   - Display the specific error message inline (e.g. invalid key, quota exceeded).
   - Do NOT persist the entered key.

---

#### `MainActivity`

Minimal launcher entry point that functions primarily as a router/onboarding shell.

**Content & Instructions:**
1. Guide user to grant the required **Draw Over Apps** system overlay permission.
2. Direct the user to select their desired AI provider and input their corresponding API keys.
3. Provide quick helper links to API Key dashboards:
   - Anthropic Console: `https://console.anthropic.com/`
   - OpenAI Platform: `https://platform.openai.com/`
   - Google AI Studio (Gemini): `https://aistudio.google.com/`

Immediately delegates to `SettingsActivity` layout or redirects automatically if setup is incomplete.

---

### 3.4 State and Data Flow Summary

```
Intent extra                 Local storage              In-memory (service lifetime)
─────────────────            ─────────────────          ──────────────────────────
EXTRA_PROCESS_TEXT      →    (not stored)               selectedText: String
EXTRA_PROCESS_TEXT_READONLY  (not stored)               isReadOnly: Boolean
                             ApiKeyStore (encrypted)  → apiKey: String
                             (not stored)             ← correctedText: String
```

No database. No Room. No files written. Everything is transient per session except the API key.

---

### 3.5 Error and Edge Case Handling

| Scenario | Behaviour |
|---|---|
| Overlay permission not granted | `ProcessTextActivity` redirects to `SettingsActivity` with permission prompt, then finishes |
| API key not set | `ProcessTextActivity` redirects to `SettingsActivity` with key prompt, then finishes |
| Selected text is empty/null | `ProcessTextActivity` calls `finish()` immediately — silent no-op |
| No internet during API call | Panel shows error state: "No internet connection." with Close button |
| API key invalid (401) | Panel shows error: "Invalid API key. Please update in Settings." |
| API rate limited (429) | Panel shows error: "Rate limit reached. Try again shortly." |
| API returns identical text | Panel shows "No changes needed ✓" — no Replace button shown |
| Source field is read-only | Replace button hidden; only Copy and ✕ shown |
| Source app is React Native / state-managed | Write-back silently fails in source app; inform user to paste manually if Replace appears to not work (v2 consideration: detect and fall back) |
| User rotates screen while panel is open | `WindowManager` overlay is not affected by rotation; panel remains visible |
| User presses Home while panel is open | Panel remains visible (it is a system overlay); service stays alive |
| User presses Back while panel is open | Treat as dismiss: send `BroadcastActions.DISMISS` |
| Service killed by system (low memory) | `ProcessTextActivity.onDestroy()` fires; it calls `setResult(RESULT_CANCELED)` + `finish()` safely |
| OEM suppresses `ACTION_PROCESS_TEXT` (e.g. some MIUI/Samsung builds) | App is simply not shown in the context menu — nothing to handle; document as known limitation |

---

### 3.6 Build and Release Checklist

- [ ] `minSdk 23`, `targetSdk 35` set in `build.gradle.kts`
- [ ] `FOREGROUND_SERVICE_SPECIAL_USE` `<property>` tag added to manifest with description string
- [ ] `ProcessTextActivity` has `android:exported="true"` (required for cross-app intent)
- [ ] `OverlayService` declared in manifest with `android:foregroundServiceType="specialUse"`
- [ ] ProGuard/R8 rules added to keep `kotlinx.serialization` models and OkHttp classes
- [ ] No API key or testing secrets hardcoded in source code; verified via global string searches before release
- [ ] Dark mode tested (panel uses `?attr/colorSurface` and `?attr/colorOnSurface`)
- [ ] Tested multi-provider functionality: Anthropic, OpenAI, and Gemini each produce correct results and handle errors
- [ ] Tested on: stock Android (Pixel), Samsung One UI, and Xiaomi MIUI
- [ ] Tested with: Gmail (editable), Chrome (read-only), WhatsApp (editable)

---

## 4. Development Roadmap & Phases

This roadmap divides the project into 5 incremental phases to ensure dependencies are built in the correct order and each phase is independently verifiable.

### Phase 1: App Shell, Storage, & Onboarding (The Foundation)
*   **Goal:** Establish the app's basic entry points, data storage, and permission flows.
*   **Tasks:**
    *   Initialize the Android Gradle project (Kotlin, Min SDK 23, Target SDK 35).
    *   Implement secure preference storage shell (`ApiKeyStore`).
    *   Build `MainActivity` (launcher routing) and `SettingsActivity` (UI for provider selection and masked key inputs).
    *   Implement the overlay authorization intent handler (`Settings.ACTION_MANAGE_OVERLAY_PERMISSION`).
    *   *Note: Key validation can be mocked to always return true during this phase.*
*   **Verification:** User can open the app from the launcher, grant overlay permissions, and securely save mock API keys.

### Phase 2: Context Menu Interception (The Trigger)
*   **Goal:** Successfully intercept highlighted text from other apps and handle missing setup states gracefully.
*   **Tasks:**
    *   Set up manifest declarations and custom themes (`Theme.Translucent.NoTitleBar`).
    *   Implement `ProcessTextActivity` to handle the `ACTION_PROCESS_TEXT` intent and extract selected text.
    *   Implement startup validation: if overlay permissions or API keys are missing, automatically bounce the user to `SettingsActivity`.
    *   Implement a minimal `OverlayService` shell that simply logs the received text.
*   **Verification:** Highlight text in an external app, tap "Check Grammar", and observe either a successful redirect to Settings (if unconfigured) or a successful text log in Logcat.

### Phase 3: System Overlay & Write-back Mechanics (The Frontend)
*   **Goal:** Render a high-fidelity float card and implement the atomic text replacement.
*   **Tasks:**
    *   Design the rounded corner card layout (`overlay_panel.xml`) using Material components.
    *   Create `OverlayPanelController` with `WindowManager` parameters (`FLAG_NOT_TOUCH_MODAL`, `FLAG_WATCH_OUTSIDE_TOUCH`).
    *   Implement panel view state transitions (Loading, Result, Error) using mock static text.
    *   Implement the `LocalBroadcastManager` loop: tapping "Replace" in the panel sends a broadcast to `ProcessTextActivity` to execute `setResult(RESULT_OK)` and swap the original text.
*   **Verification:** Highlighting text shows the floating card with a mock correction. Tapping "Replace" successfully swaps the text in the original app.

### Phase 4: Polymorphic HTTP Clients (The AI Backend)
*   **Goal:** Connect OkHttp network requests to Anthropic, OpenAI, and Gemini.
*   **Tasks:**
    *   Implement `ApiClient` interface and the singleton OkHttp client.
    *   Code `AnthropicApiClient`, `OpenAiApiClient`, and `GeminiApiClient` with their respective JSON payloads.
    *   Develop `ApiClientFactory` to resolve the active provider dynamically.
    *   Hook up the real API clients to `OverlayService` (replacing the mock text) and to `SettingsActivity` (for live key validation).
*   **Verification:** Full end-to-end functionality using actual AI providers.

### Phase 5: Polish, Edge Cases, and Hardening (Release Readiness)
*   **Goal:** Ensure end-to-end stability, UI enhancements, and packaging.
*   **Tasks:**
    *   Refine rich typography and text diff representations (strike-through original text, colored corrected text).
    *   Implement clipboard fallbacks (for apps that reject atomic write-backs like some React Native fields).
    *   Robust network degradation handling (slow networks, timeout limits, quota errors).
    *   Run structural checks for R8/ProGuard minimization.
*   **Verification:** Perform cross-device checks (Pixel, Samsung, MIUI) and test multi-app scenarios (Gmail, WhatsApp, Chrome) under varied network conditions.

---

*End of project plan — v1.0*
