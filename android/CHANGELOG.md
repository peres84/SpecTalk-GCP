# Android Changelog

All notable changes, bug fixes, and known issues for the SpecTalk Android app.
Newest entries at the top.

---

## [Unreleased] — Phase 1 + Phase 3 integration

### Fixed

#### Delete background icon always visible in conversation rows
- **Symptom:** A faint red trash icon was permanently visible on the right side of every
  conversation row even without swiping.
- **Root cause:** `ConversationRow` had no opaque background, so the `SwipeToDismissBox`
  `backgroundContent` (red with `alpha=0.15f`) bled through the transparent row surface at rest.
- **Fix:** Added `Modifier.background(MaterialTheme.colorScheme.surface)` to the root `Row`
  in `ConversationRow` so the foreground correctly covers the background at rest.
- **File:** `ui/screens/HomeScreen.kt`

#### Multiple simultaneous WebSocket connections on wake word
- **Symptom:** 4–5 WebSocket connections opened simultaneously whenever "Hey Gervis" was
  detected, each immediately receiving a backend error and closing with `EOFException`.
- **Root cause:** `HotwordService` fires `notifyWakeWord()` on both `onPartialResult` and
  `onResult`. A single utterance produces several rapid partial results, each calling
  `notifyWakeWord()` and emitting a new wake event before `isPaused` was checked.
- **Fix:** Added an early-return guard in `HotwordEventBus.notifyWakeWord()`: if `isPaused`
  is already `true` (i.e., a session is already being handled), the call is a no-op.
  A single utterance now produces exactly one wake event.
- **File:** `hotword/HotwordEventBus.kt`

#### Wake word never detected after adding BT gate (`BLUETOOTH_CONNECT` not requested)
- **Symptom:** After re-adding the Bluetooth gate to Vosk, "Hey Gervis" stopped working
  entirely — even with earbuds connected.
- **Root cause:** `HotwordService.onCreate()` calls `getProfileConnectionState()` to check
  if a BT headset is connected. This requires the `BLUETOOTH_CONNECT` runtime permission on
  API 31+ (minSdk). The permission was declared in the manifest but never requested via
  `ActivityResultContracts.RequestMultiplePermissions`. The `runCatching` guard silently
  returned `false`, so `isBtHeadsetConnected` was always `false` and Vosk never started.
- **Fix:** Added `BLUETOOTH_CONNECT` to the permission request in `HomeScreen.LaunchedEffect`.
  It is always requested (minSdk = API 31, so the permission is always required). If the user
  denies it, `HotwordService` degrades gracefully — it won't know BT state, so Vosk stays idle.
  Granting it allows accurate BT headset detection.
- **File:** `ui/screens/HomeScreen.kt`

#### Wake beep now plays immediately on wake word detection
- **Symptom:** No audible feedback when "Hey Gervis" was detected — the user couldn't tell
  the assistant was listening until the voice session screen opened.
- **Root cause:** `playActivationSound()` was called inside `VoiceAgentViewModel.startSession()`,
  which runs only after navigation to `VoiceSessionScreen`. This added a noticeable delay.
- **Fix:** Added `playWakeBeep()` to `HotwordService`. It plays a 180ms `TONE_PROP_BEEP`
  via `ToneGenerator(STREAM_MUSIC)` — using `STREAM_MUSIC` ensures the tone routes to the
  active audio output (BT headset if connected, phone speaker otherwise). It fires immediately
  in `onResult()` / `onPartialResult()` before `notifyWakeWord()` is called.
  `VoiceAgentViewModel.playActivationSound()` now only holds the 300ms pre-connect delay
  (the beep itself moved to the service).
- **File:** `hotword/HotwordService.kt`, `voice/VoiceAgentViewModel.kt`

#### Single-active-conversation model
- **Behaviour:** Only one conversation can be "active" (wake-word target) at a time.
- **FAB (+ button):** Deactivates the currently active conversation via
  `PATCH /conversations/{id}` (`{"state":"idle"}`) before opening a new session.
  The new conversation starts as `active`.
- **Wake word:** Resumes the first `active`/`awaiting_resume` conversation instead of
  always creating a new one. If none exists, a new conversation is created.
- **Conversation toggle:** `VoiceSessionScreen` now shows an **Active / Inactive** chip next
  to the connection pill. Tapping it calls `PATCH /conversations/{id}` to flip the state.
  The chip is greyed out until the backend resolves the conversation ID.
- **Files:** `conversations/ConversationRepository.kt`, `conversations/HomeViewModel.kt`,
  `voice/VoiceAgentViewModel.kt`, `voice/VoiceSessionUiState.kt`,
  `ui/screens/HomeScreen.kt`, `ui/screens/VoiceSessionScreen.kt`,
  `navigation/Screen.kt`, `navigation/SpecTalkNavGraph.kt`

#### Wake word always created a new conversation instead of resuming the active one
- **Symptom:** Every "Hey Gervis" detection opened a brand-new conversation, leading to a
  growing list of empty conversations even when an active session already existed.
- **Root cause:** `HomeScreen` passed `null` to `onNavigateToVoiceSession()` on every wake
  event, causing `VoiceAgentViewModel.startSession(null)` to always call
  `tokenRepository.startVoiceSession()` (creates a new conversation).
- **Fix:** Before navigating, `HomeScreen` now looks up the first conversation with state
  `"active"` or `"awaiting_resume"` from the loaded list. If found, its ID is passed to the
  navigator so `VoiceAgentViewModel` resumes that conversation. If none exists, `null` is
  passed and a new conversation is created as before.
- **File:** `ui/screens/HomeScreen.kt`

### Added

#### Swipe-to-delete conversations
- Swipe a conversation row left on HomeScreen to reveal a red delete background with a
  trash icon. Releasing confirms deletion.
- Deletion is optimistic — the item disappears immediately from the list. If the network
  call fails, the list is restored automatically.
- Calls `DELETE /conversations/{id}` with the product JWT.
- **Files:** `conversations/ConversationRepository.kt`, `conversations/HomeViewModel.kt`,
  `ui/screens/HomeScreen.kt`

### Fixed

#### `HotwordService` crashed on startup (second BT permission crash)
- **Symptom:** App crashed immediately after granting microphone permission, before the
  wake notification could even appear.
- **Root cause:** `HotwordService.onCreate()` called `getProfileConnectionState()` directly
  (line 102), hitting the same `BLUETOOTH_CONNECT` `SecurityException` that was previously
  fixed in `VoiceAgentViewModel` but had been missed in the service itself.
- **Fix:** Wrapped the BT state read in `runCatching` — defaults to `false` (no headset)
  if the permission is not granted.
- **File:** `hotword/HotwordService.kt`

#### Wake word never detected without a Bluetooth headset
- **Symptom:** Even after the service started, saying "Hey Gervis" did nothing on a
  phone without AirPods or Meta Glasses connected.
- **Root cause:** Every Vosk start/resume path in `HotwordService` was gated behind
  `isBtHeadsetConnected`. The service started but immediately idled waiting for a BT
  headset to connect. On BT disconnect it also called `stopSelf()`, killing the service.
- **Fix:** Removed `isBtHeadsetConnected` as a gate for Vosk. The recogniser now starts
  as long as the model is loaded and the session is not paused — phone mic is used when
  no headset is connected. BT connect/disconnect still restarts Vosk (for audio routing
  changes) but no longer stops the service on disconnect.
- **File:** `hotword/HotwordService.kt`

#### `HotwordService` never started on app launch
- **Symptom:** No "SpecTalk — Listening for Hey Gervis…" notification on HomeScreen.
  Wake word never worked even with a BT headset.
- **Root cause:** `startHotwordService()` was only called from `VoiceAgentViewModel`
  when a BT headset connected. There was no call on app launch or HomeScreen entry.
- **Fix:** `HomeScreen` now requests `RECORD_AUDIO` (and `POST_NOTIFICATIONS` on
  Android 13+) on first entry, then starts `HotwordService` immediately. The service
  also restarts on every HomeScreen entry so wake word resumes after a session ends.
- **File:** `ui/screens/HomeScreen.kt`

#### `last_turn_summary` displayed as "null" text in conversation rows
- **Symptom:** Conversation rows showed the word "null" instead of a summary or the
  fallback "Voice conversation".
- **Root cause:** Backend was serializing a missing summary as the string `"null"`
  instead of JSON `null`. The Android `takeIf { it.isNotBlank() }` check passed because
  `"null"` is not blank.
- **Fix (Android workaround):** Added `&& it != "null"` to the filter so the literal
  string `"null"` is treated the same as a missing value, falling back to
  `"Voice conversation"`. Backend fix also applied separately.
- **File:** `conversations/ConversationRepository.kt`

#### `HotwordService` never started — wake word never detected
- **Symptom:** Saying "Hey Gervis" did nothing. The wake word detector never activated unless
  a Bluetooth headset was connected.
- **Root cause:** `startHotwordService()` was only called from `observeBluetoothHeadset()` on
  BT connect. There was no call to start the service on app launch or when the user reached
  HomeScreen.
- **Fix:** `HomeScreen` now requests `RECORD_AUDIO` (and `POST_NOTIFICATIONS` on Android 13+)
  on first entry, then starts `HotwordService` immediately once permission is granted. The
  service also restarts every time the user returns to HomeScreen (e.g. after ending a session).
- **File:** `ui/screens/HomeScreen.kt`

#### App crashed on FAB tap and conversation tap
- **Symptom:** Tapping the red + FAB or any conversation row in HomeScreen immediately crashed
  the app.
- **Root cause:** `VoiceAgentViewModel.init` calls `observeBluetoothHeadset()`, which calls
  `BluetoothAdapter.getProfileConnectionState()`. On Android 12+ (API 31+, which is `minSdk`),
  this requires the `BLUETOOTH_CONNECT` runtime permission. The permission was declared in
  `AndroidManifest.xml` but never requested at runtime, causing a `SecurityException` before
  the screen even rendered.
- **Fix:** Wrapped all Bluetooth API calls in `runCatching`. The BT headset detection now
  degrades gracefully (returns false / skips) if the permission is not granted, instead of
  crashing. Audio routing still works via the default output device.
- **File:** `voice/VoiceAgentViewModel.kt`

#### Wake word detected but VoiceSessionScreen never opened
- **Symptom:** "Hey Gervis" was recognised (notification fired) but the app stayed on
  HomeScreen and no voice session started.
- **Root cause:** `HotwordEventBus.wakeWordDetected` is a `SharedFlow` only collected inside
  `VoiceAgentViewModel`, which is scoped to `VoiceSessionScreen`. When the user was on
  HomeScreen that ViewModel didn't exist, so the event was never consumed.
- **Fix:** `HomeScreen` now collects `HotwordEventBus.wakeWordDetected` directly and calls
  `onNavigateToVoiceSession(null)`. It also checks `HotwordEventBus.consumePendingWakeWord()`
  on entry to handle the notification/background path where the SharedFlow event fired before
  HomeScreen was collecting.
- **File:** `ui/screens/HomeScreen.kt`

#### Transcript area only showed the latest turn
- **Symptom:** Each new utterance replaced the previous one in the VoiceSessionScreen.
  There was no conversation history — only a single user bubble and a single assistant bubble.
- **Root cause:** `VoiceSessionUiState` stored `latestUserTranscript: String` and
  `latestAssistantTranscript: String`. Every incoming `input_transcript` / `output_transcript`
  event overwrote these fields.
- **Fix:** Replaced the two string fields with `turns: List<ConversationTurn>`. The ViewModel
  now calls `upsertTurn()` on each transcript event, which either appends a new turn or updates
  the last turn of the same role (handles both streaming partials and final transcripts).
  `VoiceSessionScreen` renders the full list as a `LazyColumn` of chat bubbles, auto-scrolling
  to the latest turn.
- **Files:** `voice/VoiceSessionUiState.kt`, `voice/VoiceAgentViewModel.kt`,
  `ui/screens/VoiceSessionScreen.kt`

#### No conversation history when resuming an existing conversation
- **Symptom:** Tapping a past conversation from HomeScreen opened VoiceSessionScreen with an
  empty transcript area, even though past turns existed in the database.
- **Root cause:** `VoiceAgentViewModel.startSession()` did not fetch past turns when a
  `conversationId` was provided — it only opened the WebSocket.
- **Fix:** When `conversationId != null`, the ViewModel now calls
  `ConversationRepository.fetchTurns()` (`GET /conversations/{id}/turns?limit=100`) before
  opening the WebSocket. Past turns pre-populate the `turns` list so the user sees the full
  history immediately. New live turns are appended on top.
- **Files:** `conversations/ConversationRepository.kt`, `voice/VoiceAgentViewModel.kt`

### Added

#### Gradle auto-download for Vosk model
- The Vosk speech model (~20 MB of binary files) is no longer committed to git.
- A `downloadVoskModel` Gradle task runs before `mergeAssets` on every build. It downloads
  and extracts `vosk-model-small-en-us-0.15.zip` automatically if `assets/model/` is missing.
- No-op if the model directory already exists (only downloads once per machine).
- Manual fallback documented in `README.md` for network-restricted environments.
- **File:** `app/build.gradle.kts`

---

## Known Issues

### Wake word does not survive app process kill or phone reboot
- **Symptom:** If the app is swiped away from recents or the phone is rebooted, `HotwordService`
  stops and "Hey Gervis" no longer works until the user opens the app again.
- **Root cause:** Android kills foreground services when the app process is destroyed. The
  service does not auto-restart after process kill or reboot.
- **Workaround:** Open the app and navigate to HomeScreen — the service restarts automatically.
- **Future fix:** Add `START_STICKY` to `HotwordService.onStartCommand()` (auto-restart on
  system kill) and a `BOOT_COMPLETED` broadcast receiver (restart after reboot).

---

## Older Fixes

### Local backend Wi-Fi IP
- Changed `backend_base_url` in `res/values/strings.xml` from the emulator loopback
  (`10.0.2.2`) to the dev machine's local Wi-Fi IP (`192.168.0.128:8080`) for testing
  on a physical device.

### Cleartext HTTP allowed for local dev
- Added `network_security_config.xml` to allow cleartext HTTP connections to the local
  backend IP during development. Production builds should use HTTPS (Cloud Run URL).

### Vosk model compression skipped in Gradle
- Added `noCompress` rules for Vosk model file extensions (`.mdl`, `.fst`, `.int`, etc.)
  in `app/build.gradle.kts`. Without this, Gradle spent minutes trying to DEFLATE already-
  compressed binary files on every build.
