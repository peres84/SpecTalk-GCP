# Android Changelog

All notable changes, bug fixes, and known issues for the SpecTalk Android app.
Newest entries at the top.

---

## [Unreleased] — FCM notification tap → open conversation + ack-resume-event

### Added

#### FcmService — real notification on job completion
- `onMessageReceived` now parses the `conversation_id` from the FCM data payload and shows
  a high-priority Android notification (`CHANNEL_ID_RESUME`) with `PendingIntent` to
  `MainActivity`.
- Notification title/body come from the FCM notification block if present, otherwise from
  `data["title"]` / `data["display_summary"]` sent by the backend.
- Notification ID is `conversation_id.hashCode()` so repeated completions from the same
  conversation replace rather than stack.
- **File:** `notifications/FcmService.kt`

#### NotificationEventBus — singleton bridge for tap events
- New `object NotificationEventBus` with a `SharedFlow<String>` carrying conversation IDs.
- Decouples `MainActivity` (Activity world) from `SpecTalkNavGraph` (Compose world) without
  requiring a ViewModel or Activity reference inside the NavGraph.
- **File:** `notifications/NotificationEventBus.kt`

#### MainActivity — intent handling (cold + warm start)
- Reads `EXTRA_CONVERSATION_ID` from the launch intent in `onCreate` (cold start from tapped
  notification) and emits to `NotificationEventBus`.
- Overrides `onNewIntent` to handle the same when the app is already running in foreground.
- `android:launchMode="singleTop"` added to `AndroidManifest.xml` — required for
  `onNewIntent` to fire instead of creating a second Activity instance.
- **File:** `MainActivity.kt`, `AndroidManifest.xml`

#### SpecTalkNavGraph — navigate on notification tap
- `LaunchedEffect(Unit)` collects from `NotificationEventBus.pendingConversationId` and
  navigates to `VoiceSessionScreen` for the target conversation, popping back to Home.
- **File:** `navigation/SpecTalkNavGraph.kt`

#### ConversationRepository.ackResumeEvent
- New `suspend fun ackResumeEvent(jwt, conversationId)` — `POST /conversations/{id}/ack-resume-event`.
- Idempotent: safe to call when no pending events exist.
- **File:** `conversations/ConversationRepository.kt`

#### VoiceAgentViewModel — auto-ack after welcome-back
- Calls `ackResumeEvent` after the first `OutputTranscript` per session.
- Guarded by `resumeEventAcked` flag (reset on each `startSession`) so it fires exactly once.
- Clears the conversation badge after Gervis delivers any welcome-back or first spoken message.
- **File:** `voice/VoiceAgentViewModel.kt`

---

## [Unreleased] — Fix echo: gate mic during playback

### Fixed

#### Gervis hears its own voice — echo feedback loop
- **Symptom:** Gervis's voice was picked up by the mic and streamed back to the backend
  as user speech, causing Gemini to respond to its own output (echo loop).
- **Root cause:** `PcmAudioPlayer` creates an `AudioTrack` with an auto-generated session
  ID, while `AcousticEchoCanceler` is attached to the `AudioRecord`'s session ID. Different
  sessions mean software AEC has no reference signal for the playback audio. Hardware AEC
  (via `USAGE_VOICE_COMMUNICATION` on both recorder and player) was previously masking this,
  but became unreliable after the backend was fixed to send a single clean audio stream
  instead of the prior double-connection audio.
- **Fix:** Added a `hasPendingAudio` gate in the mic chunk callback in `VoiceAgentViewModel`.
  While `PcmAudioPlayer.hasPendingAudio` is true (Gervis is speaking), mic chunks are not
  sent to the backend. When the user barges in, Gemini sends an `interrupted` event →
  `player.clear()` is called → `hasPendingAudio` becomes false immediately → mic sending
  resumes within one chunk cycle (~64ms).
- **File:** `voice/VoiceAgentViewModel.kt`

---

## [Unreleased] — Proactive FCM push token registration on login

### Fixed

#### Push token never stored in backend — FCM notifications never delivered
- **Symptom:** FCM push notifications from completed background jobs were never received.
  The `users.push_token` column in the database was always `null`.
- **Root cause:** `FcmService.onNewToken()` is only called by Firebase when a token is
  first generated or rotated. If the app was installed before the backend was live, the
  token was issued once and `onNewToken` never fired again — so `POST /notifications/device/register`
  was never called and the backend had no token to send notifications to.
- **Fix:** After a successful `POST /auth/session` exchange, `TokenRepository.exchangeFirebaseToken()`
  now proactively calls `FirebaseMessaging.getInstance().token.await()` to fetch the
  current FCM token and immediately registers it with the backend. This runs on every
  login so the token is always up to date even if `onNewToken` never fires.
- **File:** `auth/TokenRepository.kt`

---

## [Unreleased] — GPS precision + proactive location on connect

### Added

#### Full-precision GPS coordinates sent on WebSocket connect
- Android now sends a `location_response` message immediately when the WebSocket
  connection is established (if location permission is granted), so the backend cache
  is warm before the first voice turn. No round-trip required when the Maps tool runs.
- Coordinates are full-precision `Double` (e.g. `48.26319, 11.43421`) serialised via
  `JSONObject.put(String, Double)` — no rounding applied.
- `accuracy_meters` is always included when the GPS fix provides it, so the backend
  knows fix quality.
- `location_label` (reverse-geocoded city/country string) is still included as a
  display fallback but Maps queries now use raw coordinates directly.

### Fixed

#### Location fix used WiFi/cell towers instead of GPS
- **Symptom:** Coordinates sent to the backend had only 3–4 decimal places of real
  precision (~50–100 m accuracy), insufficient for Maps queries that need street-level
  accuracy.
- **Root cause:** `getCurrentLocation` was called with
  `Priority.PRIORITY_BALANCED_POWER_ACCURACY`, which uses WiFi and cell tower trilateration
  rather than the GPS chip.
- **Fix:** Changed to `Priority.PRIORITY_HIGH_ACCURACY` so the GPS chip is used, giving
  5+ decimal places (~1 m precision). Called only once per session start so battery
  impact is negligible.
- **File:** `location/UserLocationRepository.kt`

---

## [Unreleased] — Fix premature inactivity disconnect while user is speaking

### Fixed

#### Session disconnects mid-speech before transcript arrives
- **Symptom:** The session disconnected while the user was actively speaking, well before
  10 seconds of real silence had elapsed.
- **Root cause:** The inactivity timer only reset on `InputTranscript` and `OutputTranscript`
  events. There is a gap between when the user starts speaking (audio chunks streaming) and
  when the backend returns a transcript (Gemini processing latency). If this gap pushed the
  timer over 10 seconds — e.g., the user asked a long question right after Gervis finished
  a response — the timer fired mid-utterance and disconnected the session.
- **Fix:** Added `lastAudioSentTime`, a volatile timestamp updated on every outgoing PCM
  audio chunk (~every 64ms while the user is speaking). The inactivity timer now has a
  second guard: if an audio chunk was sent within the last `INACTIVITY_TIMEOUT_MS`, the
  timer continues waiting instead of disconnecting. The session only disconnects when there
  has been no transcript activity AND no outgoing audio for the full timeout window.
- **Files:** `voice/VoiceAgentViewModel.kt`

---

## [Unreleased] — Fix two simultaneous WebSocket connections (double agent bug)

### Fixed

#### Two agents responding simultaneously — double WebSocket connections
- **Symptom:** Two AI agents were listening and responding at the same time, as if two
  separate voice sessions were active simultaneously.
- **Root cause (1 — nav stack):** In Compose Navigation, the previous destination stays
  in the composition tree while a new destination is on top. When a wake word fired while
  `VoiceSessionScreen` was already showing, both `HomeScreen`'s `LaunchedEffect` (still
  active in the background) and `VoiceAgentViewModel.init`'s `wakeWordDetected.collect`
  received the event. `HomeScreen` pushed a *second* `VoiceSessionScreen` onto the nav
  stack (Home → VoiceSession → VoiceSession), creating a second ViewModel and a second
  WebSocket alongside the original.
- **Root cause (2 — async guard race):** `startSession()` checked `isConnecting` before
  setting it to `true`, but the `isConnecting = true` update was inside
  `viewModelScope.launch {}` — an async operation. Two concurrent calls could both pass
  the guard before either one updated the state, opening two WebSockets within the same
  ViewModel.
- **Fix 1:** Added `popUpTo(Screen.Home.route)` to every `navController.navigate` call
  targeting `VoiceSession`. This guarantees the nav stack is always at most
  `Home → VoiceSession`, so a second VoiceSession can never be pushed on top of an
  existing one.
- **Fix 2:** Moved `_uiState.update { it.copy(isConnecting = true) }` to *before*
  `viewModelScope.launch` in `startSession()`. The flag is now set synchronously on the
  main thread, so any subsequent call sees `isConnecting = true` immediately and returns
  early.
- **Files:** `navigation/SpecTalkNavGraph.kt`, `voice/VoiceAgentViewModel.kt`

---

## [Unreleased] — Fix echo regression (USAGE_VOICE_COMMUNICATION)

### Fixed

#### Echo returned after agent changed PcmAudioPlayer audio usage
- **Symptom:** Gervis's voice was picked up by the mic and streamed back as user speech,
  causing the assistant to hear itself.
- **Root cause:** `PcmAudioPlayer` was using `AudioAttributes.USAGE_ASSISTANT` for the
  `AudioTrack`. Hardware AEC needs the speaker output to go through the voice communication
  audio path to use it as an echo reference signal. `USAGE_ASSISTANT` routes through a
  different path, so AEC had no reference and could not cancel the echo.
- **Fix:** Changed `AudioTrack` usage back to `AudioAttributes.USAGE_VOICE_COMMUNICATION`
  so both mic (`AudioSource.VOICE_COMMUNICATION`) and speaker share the same VoIP audio
  path that Android's hardware AEC is designed for.
- **File:** `audio/PcmAudioPlayer.kt`

---

## [Unreleased] — Lock screen wake word + location request-response + remove active conversation

### Added

#### Wake word now works when the phone is locked
- **Symptom:** Saying "Hey Gervis" while the phone was locked did nothing — the foreground
  service was alive but `MainActivity` could not come to the foreground over the lock screen.
- **Fix:** Added `android:showWhenLocked="true"` and `android:turnScreenOn="true"` to
  `MainActivity` in `AndroidManifest.xml`. Combined with the existing `PARTIAL_WAKE_LOCK`
  in `HotwordService`, the app now surfaces over the lock screen and turns on the display
  when a wake word is detected.
- **File:** `app/src/main/AndroidManifest.xml`

#### On-demand location via WebSocket request-response
- Replaced the "push location at session start" approach with a request-response protocol.
- When the backend Maps tool needs coordinates it sends `{"type": "request_location"}` over
  the voice WebSocket. Android fetches a fresh GPS fix and responds with
  `{"type": "location_response", "latitude": ..., "longitude": ..., "accuracy_meters": ...,
  "location_label": "..."}`.
- Location is only fetched when the backend actually needs it (battery-efficient).
- If location sharing is disabled or permission is denied, the backend receives no response
  and should ask the user for a place normally.
- **Files:** `voice/BackendVoiceClient.kt`, `voice/VoiceAgentViewModel.kt`

### Changed

#### Active conversation concept removed
- Every FAB tap or wake word event now creates a new conversation. There is no longer an
  "active" conversation that the wake word targets — the model is simply: wake word → new
  conversation, conversation row tap → resume that conversation.
- Removed: `ActivationChip` in `VoiceSessionScreen`, `toggleActivation()` in
  `VoiceAgentViewModel`, `deactivateCurrentActive()` in `HomeViewModel`,
  `isConversationActive` in `VoiceSessionUiState`, `isActive` nav argument and route param.
- **Files:** `navigation/Screen.kt`, `navigation/SpecTalkNavGraph.kt`,
  `ui/screens/HomeScreen.kt`, `ui/screens/VoiceSessionScreen.kt`,
  `voice/VoiceSessionUiState.kt`, `voice/VoiceAgentViewModel.kt`,
  `conversations/HomeViewModel.kt`

---

## [Unreleased] - Meta device wake-word gate + location sharing

### Fixed

#### Wake word now idles until a supported connected device is available
- **Symptom:** The Android hotword listener could keep running from the phone microphone even
  when no Meta glasses or Bluetooth audio device was connected.
- **Root cause:** `HotwordService` owned its own coarse Bluetooth headset check and could still
  initialize Vosk independently of real device/session availability.
- **Fix:** Added a shared `ConnectedDeviceMonitor` that combines Meta DAT device availability
  (`Wearables.devices`) with Bluetooth/audio-device state and exposes a single
  `isWakeWordReady` gate. `HotwordService` now starts or stops listening from that shared gate
  and ignores recognizer callbacks whenever the device is not ready.
- **Files:** `device/ConnectedDeviceMonitor.kt`, `hotword/HotwordService.kt`,
  `voice/VoiceAgentViewModel.kt`, `voice/VoiceSessionUiState.kt`

#### App now initializes Meta DAT at startup for Android-side device awareness
- **Symptom:** The app had no reliable concept of "Meta device connected", so wake-word gating
  depended only on generic phone Bluetooth state.
- **Root cause:** The Android app was not initializing the Meta Wearables DAT SDK and had no
  dependency on the core DAT library.
- **Fix:** Added `mwdat-core`, initialized `Wearables` from `SpecTalkApplication`, and exposed
  app-wide device monitoring from startup.
- **Files:** `app/build.gradle.kts`, `gradle/libs.versions.toml`,
  `app/src/main/AndroidManifest.xml`, `SpecTalkApplication.kt`

### Added

#### Settings-based location sharing and current-location preview
- Added persisted location-sharing preference in Settings with runtime permission request for
  `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`.
- Added current-location fetch and reverse-geocoded summary so the user can confirm what the
  app is about to share with the voice backend.
- Added a shortcut to app settings when Android permission has been denied.
- **Files:** `settings/AppPreferences.kt`, `location/UserLocationRepository.kt`,
  `location/UserLocationContext.kt`, `ui/screens/SettingsScreen.kt`,
  `app/src/main/AndroidManifest.xml`, `app/build.gradle.kts`

#### Voice sessions now send optional location context to the backend
- When location sharing is enabled, Android now resolves a fresh location context before voice
  session start, includes it in `POST /voice/session/start`, and re-sends it over the voice
  WebSocket once connected.
- **Files:** `auth/TokenRepository.kt`, `voice/BackendVoiceClient.kt`,
  `voice/VoiceAgentViewModel.kt`

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
