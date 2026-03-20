# SpecTalk Agent Architecture + Meta Wearables DAT SDK

---

## Part 1: SpecTalk Agent Architecture

### Overview

The backend uses Google ADK to run Gervis as a voice agent. The agent runs server-side,
owns the Gemini Live session, and executes all tools natively in the same Python process.
The phone is a thin audio terminal — it never calls tools or holds credentials.

### Live Model Policy

For Gemini Live in this project, always use `gemini-2.5-flash-native-audio-preview-12-2025`
unless Google announces and documents a newer supported replacement for bidirectional Live API
audio sessions. Do not switch back to `gemini-2.0-flash-live-001` or
`gemini-live-2.5-flash-preview` because both models were shut down on December 9, 2025.

### Agent Topology

```
create_gervis_agent()                    ← agents/orchestrator.py
│  model: gemini-2.5-flash-native-audio-preview-12-2025
│  ~400 line system instruction
│  VAD: low sensitivity, 320ms silence
│
├── google_search                        ← tools/search_tool.py
├── get_user_location                    ← tools/location_tool.py
├── find_nearby_places                   ← tools/maps_tool.py
├── start_background_job                 ← tools/notification_resume_tool.py
├── request_clarification                ← tools/coding_tools.py
├── generate_and_confirm_prd             ← tools/coding_tools.py
│     └── calls designer_agent.py       ← agents/team_code_pr_designers/
├── confirm_and_dispatch                 ← tools/coding_tools.py
└── lookup_project                       ← tools/project_tools.py
```

### ADK Runtime

```python
# services/gemini_live_client.py
runner = InMemoryRunner(
    agent=create_gervis_agent(),
    app_name="gervis",
)

# Per conversation: ADK session + LiveRequestQueue
session = await runner.session_service.create_session(...)
live_request_queue = LiveRequestQueue()

# Run the agent
async for event in runner.run_live(
    session=session,
    live_request_queue=live_request_queue,
    run_config=RunConfig(
        response_modalities=["AUDIO"],
        output_audio_transcription=AudioTranscriptionConfig(),
        input_audio_transcription=AudioTranscriptionConfig(),
        realtime_input_config=RealtimeInputConfig(
            automatic_activity_detection=AutomaticActivityDetection(
                disabled=False,
                start_of_speech_sensitivity=StartSensitivity.START_SENSITIVITY_LOW,
                end_of_speech_sensitivity=EndSensitivity.END_SENSITIVITY_LOW,
                prefix_padding_ms=60,
                silence_duration_ms=320,
            )
        ),
    ),
):
    # Handle audio + transcript + tool_call events
```

### Tools Reference

| Tool | File | What It Does |
|------|------|--------------|
| `google_search` | `tools/search_tool.py` | Web search via Google Custom Search API |
| `get_user_location` | `tools/location_tool.py` | Returns cached GPS from phone |
| `find_nearby_places` | `tools/maps_tool.py` | Google Maps Places API |
| `start_background_job` | `tools/notification_resume_tool.py` | Creates Job + enqueues Cloud Task |
| `request_clarification` | `tools/coding_tools.py` | Ask one Q, track count (max 3), emit state_update |
| `generate_and_confirm_prd` | `tools/coding_tools.py` | Calls designer_agent, sends PRD to phone |
| `confirm_and_dispatch` | `tools/coding_tools.py` | Confirms Job, dispatches to Cloud Tasks |
| `lookup_project` | `tools/project_tools.py` | Fuzzy slug-match user's project registry |

### Sub-Agent: team_code_pr_designers

```python
# agents/team_code_pr_designers/designer_agent.py
# Called by generate_and_confirm_prd() tool
# Uses ADK LlmAgent for PRD generation (not a Live session)
async def generate_prd(project_idea: str, clarifications: list[str]) -> dict:
    ...
```

This sub-agent generates a structured PRD from the user's spoken idea + collected clarifications.
The result is sent to the phone as a `state_update` with `prd_summary` JSON, displayed as a
`PrdConfirmationCard`, and awaits the user's voice confirmation before dispatching.

### Audio Bridge

```
phone mic (16kHz PCM) → binary WS frames
    → voice_handler.py _upstream_task()
    → live_request_queue.send_realtime_input(audio=...)
    → Gemini Live (ADK)

Gemini Live (ADK) → PCM 24kHz chunks
    → voice_handler.py _downstream_task()
    → binary WS frames
    → phone PcmAudioPlayer (24kHz)
```

### Session Lifecycle

```
AudioSessionManager (services/audio_session_manager.py)
  - Creates ADK session on first phone WebSocket connection
  - Holds session open for 30s grace period on phone disconnect
  - Re-injects resume context on reconnect
  - Registers live_request_queue for in-session job injection
```

### Control Message Protocol

Phone → Backend (JSON):
- `{"type": "end_of_speech"}` — user ended session or 10s inactivity
- `{"type": "location_response", "lat": ..., "lng": ...}` — GPS coordinates
- `{"type": "image", "mime_type": "image/jpeg", "data": "<base64>"}` — multimodal frame

Backend → Phone (JSON):
- `{"type": "input_transcript", "text": "..."}` — user speech
- `{"type": "output_transcript", "text": "..."}` — Gervis speech
- `{"type": "interrupted"}` — barge-in detected, phone clears audio immediately
- `{"type": "turn_complete"}` — Gervis finished a turn
- `{"type": "state_update", "state": "...", "prd_summary": {...}}` — conversation state change
- `{"type": "job_started", "job_id": "...", "spoken_ack": "..."}` — background job created
- `{"type": "job_update", "job_id": "...", "status": "..."}` — job status change
- `{"type": "request_location"}` — Gervis needs GPS
- `{"type": "session_timeout", "message": "..."}` — ~10min Gemini model limit hit
- `{"type": "error", "spoken_summary": "..."}` — surfaced error

---

## Part 2: Meta Wearables DAT SDK

> Full API reference: https://wearables.developer.meta.com/llms.txt?full=true
> Developer docs: https://wearables.developer.meta.com/docs/develop/

### Architecture

The SDK is organized into three modules:
- **mwdat-core**: Device discovery, registration, permissions, device selectors
- **mwdat-camera**: StreamSession, VideoFrame, photo capture
- **mwdat-mockdevice**: MockDeviceKit for testing without hardware

### Kotlin Patterns

- Use `suspend` functions for async operations — no callbacks
- Use `StateFlow` / `Flow` for observing state changes
- Use `DatResult<T, E>` for error handling — not exceptions
- Prefer immutable collections
- Use `sealed interface` for state hierarchies

### Error Handling

The SDK uses `DatResult<T, E>` for type-safe error handling:

```kotlin
val result = Wearables.someOperation()
result.fold(
    onSuccess = { value -> /* handle success */ },
    onFailure = { error -> /* handle error */ }
)

// Or partial handling:
result.onSuccess { value -> /* handle success */ }
result.onFailure { error -> /* handle error */ }
```

Do **not** use `getOrThrow()` — always handle both paths.

### Naming Conventions

| Suffix | Purpose | Example |
|--------|---------|---------|
| `*Manager` | Long-lived resource management | `RegistrationManager` |
| `*Session` | Short-lived flow component | `StreamSession` |
| `*Result` | DatResult type aliases | `RegistrationResult` |
| `*Error` | Error sealed interfaces | `WearablesError` |

Methods: `get*`, `set*`, `check*`, `request*`, `observe*`

### Imports

```kotlin
import com.meta.wearable.dat.core.Wearables          // Entry point
import com.meta.wearable.dat.camera.StreamSession     // Camera streaming
import com.meta.wearable.dat.camera.types.*            // VideoFrame, PhotoData, etc.
```

For testing:
```kotlin
import com.meta.wearable.dat.mockdevice.MockDeviceKit  // MockDeviceKit
```

### Key Types

- `Wearables` — SDK entry point. Call `Wearables.initialize(context)` at startup
- `StreamSession` — Camera streaming session
- `VideoFrame` — Individual video frame with bitmap data
- `AutoDeviceSelector` — Auto-selects the best available device
- `SpecificDeviceSelector` — Selects a specific device by identifier
- `StreamConfiguration` — Configure video quality, frame rate
- `MockDeviceKit` — Factory for creating simulated devices in tests

### Links

- [Android API Reference](https://wearables.developer.meta.com/docs/reference/android/dat/0.5)
- [Developer Documentation](https://wearables.developer.meta.com/docs/develop/)
- [GitHub Repository](https://github.com/facebook/meta-wearables-dat-android)

### Dev Environment Tips

Guide for setting up the Meta Wearables Device Access Toolkit in an Android app.

### Prerequisites

- Android Studio, minSdk 26+
- Meta AI companion app installed on test device
- Ray-Ban Meta glasses or Meta Ray-Ban Display glasses (or use MockDeviceKit for development)
- Developer Mode enabled in Meta AI app (Settings > Your glasses > Developer Mode)
- GitHub personal access token with `read:packages` scope

### Step 1: Add the Maven repository

In `settings.gradle.kts`:

```kotlin
val localProperties =
    Properties().apply {
        val localPropertiesPath = rootDir.toPath() / "local.properties"
        if (localPropertiesPath.exists()) {
            load(localPropertiesPath.inputStream())
        }
    }

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/facebook/meta-wearables-dat-android")
            credentials {
                username = ""
                password = System.getenv("GITHUB_TOKEN") ?: localProperties.getProperty("github_token")
            }
        }
    }
}
```

### Step 2: Declare dependencies

In `libs.versions.toml`:

```toml
[versions]
mwdat = "0.5.0"

[libraries]
mwdat-core = { group = "com.meta.wearable", name = "mwdat-core", version.ref = "mwdat" }
mwdat-camera = { group = "com.meta.wearable", name = "mwdat-camera", version.ref = "mwdat" }
mwdat-mockdevice = { group = "com.meta.wearable", name = "mwdat-mockdevice", version.ref = "mwdat" }
```

In `app/build.gradle.kts`:

```kotlin
dependencies {
    implementation(libs.mwdat.core)
    implementation(libs.mwdat.camera)
    implementation(libs.mwdat.mockdevice)
}
```

### Step 3: Configure AndroidManifest.xml

```xml
<manifest ...>
    <uses-permission android:name="android.permission.BLUETOOTH" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.INTERNET" />

    <application ...>
        <!-- Use 0 in Developer Mode; production apps get ID from Wearables Developer Center -->
        <meta-data
            android:name="com.meta.wearable.mwdat.APPLICATION_ID"
            android:value="0" />

        <activity android:name=".MainActivity" ...>
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="myexampleapp" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

Replace `myexampleapp` with your app's URL scheme.

### Step 4: Initialize the SDK

```kotlin
import com.meta.wearable.dat.core.Wearables

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Wearables.initialize(this)
    }
}
```

Calling SDK APIs before initialization yields `WearablesError.NOT_INITIALIZED`.

### Step 5: Register with Meta AI

```kotlin
fun startRegistration(context: Context) {
    Wearables.startRegistration(context)
}
```

Observe registration state:

```kotlin
lifecycleScope.launch {
    Wearables.registrationState.collect { state ->
        // Update UI based on registration state
    }
}
```

### Step 6: Start streaming

```kotlin
import com.meta.wearable.dat.camera.StreamSession
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector

val session = Wearables.startStreamSession(
    context = context,
    deviceSelector = AutoDeviceSelector(),
    streamConfiguration = StreamConfiguration(
        videoQuality = VideoQuality.MEDIUM,
        frameRate = 24,
    ),
)

lifecycleScope.launch {
    session.videoStream.collect { frame ->
        // Display frame
    }
}

lifecycleScope.launch {
    session.state.collect { state ->
        // Update UI based on stream state
    }
}
```

### ConnectedDeviceMonitor (SpecTalk Integration)

SpecTalk's `HotwordService` uses `ConnectedDeviceMonitor` to gate wake-word listening —
it only activates when a Meta wearable or Bluetooth audio device is connected. This prevents
accidental wake-word triggers when the user is not wearing their glasses or AirPods.

```kotlin
// device/ConnectedDeviceMonitor.kt
// Monitors connected devices and emits connected/disconnected events
// HotwordService subscribes and pauses/resumes Vosk accordingly
```

### Testing Instructions

Guide for testing DAT SDK integrations without physical Meta glasses.

MockDeviceKit simulates Meta glasses behavior for development and testing. It provides:
- `MockDeviceKit` — Entry point for creating simulated devices
- `MockRaybanMeta` — Simulated Ray-Ban Meta glasses
- `MockCameraKit` — Simulated camera with configurable video feed and photo capture

```kotlin
import com.meta.wearable.dat.mockdevice.MockDeviceKit

val mockDeviceKit = MockDeviceKit.getInstance(context)
mockDeviceKit.enable()
val device = mockDeviceKit.pairRaybanMeta()
device.powerOn()
device.unfold()
device.don()

// Set up mock camera feed
val camera = device.getCameraKit()
camera.setCameraFeed(videoUri)
```

### Resolution Options

| Quality | Size |
|---------|------|
| `VideoQuality.HIGH` | 720 x 1280 |
| `VideoQuality.MEDIUM` | 504 x 896 |
| `VideoQuality.LOW` | 360 x 640 |

Valid frame rates: `2`, `7`, `15`, `24`, `30` FPS.

### Links

- [Android API Reference](https://wearables.developer.meta.com/docs/reference/android/dat/0.5)
- [Developer Documentation](https://wearables.developer.meta.com/docs/develop/)
- [Mock Device Kit overview](https://wearables.developer.meta.com/docs/mock-device-kit)
- [GitHub Repository](https://github.com/facebook/meta-wearables-dat-android)
