# Android Changelog

All notable changes, bug fixes, and known issues for the SpecTalk Android app.
Newest entries at the top.

---

## [Unreleased] — Phase 1 + Phase 3 integration

### Fixed

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

### `last_turn_summary` displays "null" (string) in conversation rows
- **Symptom:** Conversation rows show the text "null" instead of a summary or the fallback
  "Voice conversation".
- **Root cause:** The backend returns the string `"null"` for `last_turn_summary` instead of
  JSON `null`. The Android fallback (`?: "Voice conversation"`) only triggers on a true JSON
  null, not the literal string.
- **Fix required:** Backend — `last_turn_summary` should be omitted or set to `null` in JSON
  when there is no summary, not the string `"null"`.

### Wake word does not survive app process kill or phone reboot
- **Symptom:** If the app is swiped away from recents or the phone is rebooted, `HotwordService`
  stops and "Hey Gervis" no longer works until the user opens the app again.
- **Root cause:** Android kills foreground services when the app process is destroyed. The
  service does not auto-restart after process kill or reboot.
- **Workaround:** Open the app and navigate to HomeScreen — the service restarts automatically.
- **Future fix:** Add `START_STICKY` to `HotwordService.onStartCommand()` (auto-restart on
  system kill) and a `BOOT_COMPLETED` broadcast receiver (restart after reboot).

### `BLUETOOTH_CONNECT` permission not requested at runtime
- **Symptom:** BT headset audio routing and BT-triggered hotword restart do not work on a
  fresh install because the permission dialog never appears.
- **Root cause:** `BLUETOOTH_CONNECT` is declared in the manifest but never requested via
  `ActivityResultContracts.RequestPermission`. The BT code is now wrapped in `runCatching`
  so it won't crash, but it silently skips BT detection.
- **Future fix:** Add a runtime permission request for `BLUETOOTH_CONNECT` alongside the
  `RECORD_AUDIO` request in `HomeScreen`.

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
