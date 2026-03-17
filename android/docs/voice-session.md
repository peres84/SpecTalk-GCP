# Voice Session — Android Implementation

Documents the voice session system: wake word detection, session lifecycle, audio bridge,
and transcript display. Read this before modifying any file in `voice/`, `hotword/`, or
`audio/`.

---

## How a Session Starts

There are three entry points — all converge on `VoiceAgentViewModel.startSession()`:

| Entry point | Where |
|-------------|-------|
| **Wake word** ("Hey Gervis") | `HotwordService` → `HotwordEventBus.notifyWakeWord()` → `HomeScreen` navigates to `VoiceSessionScreen` → ViewModel picks up via `consumePendingWakeWord()` or flow |
| **FAB** on HomeScreen | `HomeScreen` → `onNavigateToVoiceSession(null)` → `VoiceSessionScreen.LaunchedEffect` → `startSession(null)` |
| **Tap existing conversation** | `HomeScreen` → `onNavigateToVoiceSession(conversationId)` → `VoiceSessionScreen.LaunchedEffect` → `startSession(conversationId)` |

### Wake Word Navigation Detail

`HotwordEventBus` is a process-wide singleton. When the wake word fires:

1. `HotwordService` calls `HotwordEventBus.notifyWakeWord()`
2. This sets `isPaused = true`, `pendingWakeWord = true`, and emits on `wakeWordDetected` SharedFlow
3. `HomeScreen` has a `LaunchedEffect` collecting `wakeWordDetected` → calls `onNavigateToVoiceSession(null)`
4. `HomeScreen` also checks `consumePendingWakeWord()` on entry — covers the background/notification path where the SharedFlow event was emitted before `HomeScreen` was collecting
5. Once on `VoiceSessionScreen`, `VoiceAgentViewModel.init` calls `consumePendingWakeWord()` as a final fallback

`startSession()` is guarded against double-calls:
```kotlin
if (_uiState.value.isConnecting || _uiState.value.isConnected) return
```

---

## Session Lifecycle

```
startSession(conversationId?)
  │
  ├─ pause HotwordService (takes the mic)
  ├─ play activation chime (300ms)
  ├─ GET product JWT from TokenRepository
  ├─ if no conversationId: POST /voice/session/start → get new ID
  ├─ open BackendVoiceClient WebSocket
  ├─ start 12s connection timeout
  │
  ▼ WebSocket Connected
  ├─ start AndroidAudioRecorder (16kHz PCM → WebSocket binary frames)
  ├─ start PcmAudioPlayer (24kHz PCM from WebSocket binary frames)
  ├─ start 10s inactivity timer
  │
  ▼ Active session
  ├─ input_transcript  → append user turn, reset inactivity timer
  ├─ output_transcript → append assistant turn, reset inactivity timer
  ├─ interrupted       → clear audio queue immediately
  ├─ audio binary      → enqueue to PcmAudioPlayer
  │
  ▼ Session ends (manual / inactivity / "goodbye" / error)
  ├─ send end_of_speech
  ├─ close WebSocket
  ├─ stop recorder + player
  └─ resume HotwordService
```

### Inactivity Auto-Disconnect (10 seconds)

- Timer resets on every `input_transcript` or `output_transcript` event
- Timer is paused while `PcmAudioPlayer.hasPendingAudio == true` (don't cut off Gervis mid-sentence)
- On timeout: sends `{"type": "end_of_speech"}`, disconnects, resumes `HotwordService`

---

## Audio System

| Component | File | Format | Direction |
|-----------|------|--------|-----------|
| `AndroidAudioRecorder` | `audio/AndroidAudioRecorder.kt` | PCM 16kHz 16-bit mono | Phone mic → WebSocket |
| `PcmAudioPlayer` | `audio/PcmAudioPlayer.kt` | PCM 24kHz 16-bit mono | WebSocket → speaker |

Both components use `USAGE_ASSISTANT` audio attributes for correct routing to AirPods /
Meta Glasses when connected.

`AndroidAudioRecorder` attaches AEC, NS, and AGC effects when the device supports them
(`VOICE_COMMUNICATION` audio source).

`PcmAudioPlayer` tracks pending audio via an `AtomicInteger` counter. The inactivity timer
reads `hasPendingAudio` before deciding to disconnect.

### Activation Sound

- Asset: `app/src/main/res/raw/activation_sound.wav`
- Played via `SoundPool` with `USAGE_ASSISTANT` attributes
- A 300ms delay follows playback before the WebSocket opens — gives the chime time to
  finish before the mic starts streaming

---

## Transcript History

`VoiceSessionUiState` holds the full session transcript as:

```kotlin
val turns: List<ConversationTurn>   // data class ConversationTurn(val role: String, val text: String)
```

`VoiceAgentViewModel.upsertTurn()` handles both streaming (partial) and final transcripts:
- If the last turn has the **same role** → update its text (streaming update)
- If the last turn has a **different role** → append a new turn

`VoiceSessionScreen` renders `turns` as a `LazyColumn` of chat bubbles, auto-scrolling to
the latest turn on each update.

When opening an **existing** conversation (`conversationId != null`), `VoiceAgentViewModel`
fetches past turns from `GET /conversations/{id}/turns?limit=100` via `ConversationRepository.fetchTurns()`
before the WebSocket opens. The history pre-populates `turns` so the user sees the full chat
immediately. New turns from the live session are then appended on top.

---

## WebSocket Protocol

`BackendVoiceClient` connects to `WS /ws/voice/{conversationId}` with:
- `Authorization: Bearer <product_jwt>` header on upgrade
- Binary frames: raw PCM audio (phone → backend and backend → phone)
- Text frames: JSON control messages

| Message (inbound) | Handled as |
|-------------------|------------|
| `{"type":"interrupted"}` | Clear `PcmAudioPlayer` queue immediately |
| `{"type":"input_transcript","text":"..."}` | Append/update user turn |
| `{"type":"output_transcript","text":"..."}` | Append/update assistant turn |
| `{"type":"state_update","state":"..."}` | Update status pill label |
| `{"type":"job_started","job_id":"...","description":"..."}` | Show job banner |
| `{"type":"job_update","job_id":"...","status":"...","message":"..."}` | Hide banner on complete/failed |
| `{"type":"error","message":"..."}` | Show snackbar, disconnect |

| Message (outbound) | Sent when |
|--------------------|----------|
| Binary PCM frame | Every `AndroidAudioRecorder` chunk |
| `{"type":"end_of_speech"}` | Mic stop / inactivity timeout |

---

## Key Files

| File | Purpose |
|------|---------|
| `hotword/HotwordService.kt` | Foreground service, Vosk wake word detection |
| `hotword/HotwordEventBus.kt` | Process-wide wake word event bus |
| `voice/BackendVoiceClient.kt` | OkHttp WebSocket client |
| `voice/VoiceAgentViewModel.kt` | Session orchestration, mic/speaker, timers |
| `voice/VoiceSessionUiState.kt` | UI state including `turns` list |
| `audio/AndroidAudioRecorder.kt` | PCM capture with AEC/NS/AGC |
| `audio/PcmAudioPlayer.kt` | PCM playback with interrupt support |
| `ui/screens/VoiceSessionScreen.kt` | Animated orb, chat bubbles, end button |
| `ui/screens/HomeScreen.kt` | Conversation list, FAB, wake word navigation |
| `config/BackendConfig.kt` | Backend URL (set in `res/values/strings.xml`) |

---

## Configuration

### Backend URL

Set in `android/app/src/main/res/values/strings.xml`:

```xml
<!-- Local testing (replace with your machine's Wi-Fi IP) -->
<string name="backend_base_url">http://192.168.x.x:8080</string>

<!-- Production -->
<string name="backend_base_url">https://gervis-backend-XXXXXXXX-uc.a.run.app</string>
```

### Wake Word

Default: `"Hey Gervis"`. User-configurable via Settings screen.
Stored in `SharedPreferences` under key `pref_wake_word`.
`HotwordService` reads this on every recognition cycle — changes take effect on the next
service restart (Bluetooth reconnect or app restart).

### Vosk Model

Located at `android/app/src/main/assets/model/`. Not committed to git.
Downloaded automatically by Gradle before the first build (`downloadVoskModel` task).
See [README](../../README.md#building-the-android-app) for the manual fallback.
