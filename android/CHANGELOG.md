# Android Changelog

All notable changes, bug fixes, and known issues for the SpecTalk Android app.
Newest entries at the top.

---

## [Unreleased] — Phase 6: camera integration, gallery, sidebar navigation, single-conversation enforcement

### Added

#### `GlassesCameraManager` — Meta DAT camera capture
- New `object GlassesCameraManager` in `device/GlassesCameraManager.kt` wrapping the DAT
  `StreamSession` for on-demand still-frame capture from Meta Ray-Ban glasses.
- `startSession(context)` / `stopSession()` manage the `StreamSession` lifecycle.
  `stopSession()` calls `close()` (correct DAT SDK method; `stop()` does not exist).
- `observeStreamState()` collects `StreamSession.state` and drives `_isReady: StateFlow<Boolean>`;
  only `true` while state is `StreamSessionState.STREAMING`.
- `capturePhoto()` uses `videoStream.first()` to get a decoded `VideoFrame` (DAT `PhotoData`
  is an opaque marker interface with no pixel data fields and cannot be used for bytes).
  The `ByteBuffer` from `VideoFrame` is compressed to JPEG at 85% quality via
  `Bitmap.createBitmap` + `compress`.
- **File:** `device/GlassesCameraManager.kt`
- **Dependency added:** `mwdat-camera` (`libs.versions.toml` + `app/build.gradle.kts`)

#### `GalleryRepository` — local JPEG storage
- New `object GalleryRepository` in `gallery/GalleryRepository.kt`.
- `saveImage(context, jpegBytes, source)` writes a JPEG to `filesDir/gallery/` with a
  filename of `<source>_<timestamp>.jpg` (e.g. `glasses_1711000000000.jpg`, `phone_...`).
- `listImages(context)` returns all `GalleryImage(file, source, timestamp)` items sorted by
  modification time, newest first.
- `deleteImage(file)` deletes the file.
- **File:** `gallery/GalleryRepository.kt`

#### `GalleryViewModel` — gallery state management
- Loads images on `load()` using `Dispatchers.IO`; exposes `images: StateFlow<List<GalleryImage>>`.
- `delete(image)` deletes via `GalleryRepository` then reloads.
- **File:** `gallery/GalleryViewModel.kt`

#### `GalleryScreen` — image gallery UI
- New `GalleryScreen` composable in `ui/screens/GalleryScreen.kt`.
- `LazyVerticalGrid(GridCells.Fixed(2))` with 8dp gutters; each cell is a card with the image,
  source badge ("Glasses" / "Phone" pill), relative timestamp, and a delete `IconButton` overlay.
- Async bitmap loading via `produceState<ImageBitmap?>` on `Dispatchers.IO`.
- Empty state (no images) shows a centred Camera icon with helper text.
- Hamburger `TopAppBar` calls `onOpenDrawer`.
- **File:** `ui/screens/GalleryScreen.kt`

#### `AppDrawer` — sidebar navigation drawer
- New `AppDrawer` composable in `ui/components/AppDrawer.kt` using `ModalDrawerSheet`.
- Header: Mic icon (primary colour, 56dp circle), "SpecTalk" in `titleLarge`, "Powered by Gervis"
  at 50% alpha, user email at 30% alpha.
- Nav items: Conversations (`Icons.Rounded.Mic`), Gallery (`Icons.Default.PhotoLibrary`),
  Settings (`Icons.Default.Settings`) — active item uses `selectedContainerColor = primary.copy(alpha=0.12f)`.
- Destructive "Sign out" `TextButton` (error colour) pinned at the very bottom.
- **File:** `ui/components/AppDrawer.kt`

#### Phone camera capture in voice session
- `VoiceSessionScreen` adds a `rememberLauncherForActivityResult(TakePicturePreview)` launcher.
  On result, the Bitmap thumbnail is compressed to JPEG (80% quality) and passed to
  `viewModel.sendCameraImage(jpegBytes)`.
- New `VoiceAgentViewModel.sendCameraImage(jpegBytes)`: saves image to gallery
  (`GalleryRepository.saveImage(..., "phone")`), then sends via `BackendVoiceClient.sendImage()`.
- `BackendVoiceClient.sendImage(jpegBytes)`: base64-encodes the JPEG bytes and sends
  `{"type":"image","mime_type":"image/jpeg","data":"<base64>"}` over the voice WebSocket.
- **Files:** `ui/screens/VoiceSessionScreen.kt`, `voice/VoiceAgentViewModel.kt`,
  `voice/BackendVoiceClient.kt`
- **Permission added:** `android.permission.CAMERA` in `AndroidManifest.xml`

### Changed

#### `VoiceSessionScreen` — adaptive camera button in title bar
- The right-side icon in the title bar is now a 3-way switch:
  - **Glasses connected** (`isConnected && isGlassesCameraReady`) → `CameraAlt` icon; tapping
    calls `viewModel.sendGlassesFrame()` (captures a DAT glasses frame).
  - **Connected, no glasses** (`isConnected`) → `PhotoCamera` icon; tapping opens the phone
    camera via `cameraLauncher.launch(null)`.
  - **Not connected** → `Spacer(48.dp)` placeholder keeps the title centred.
- `VoiceAgentViewModel.sendGlassesFrame()` now also saves the captured frame to gallery
  (`GalleryRepository.saveImage(..., "glasses")`) in addition to sending it to the backend.
- **Files:** `ui/screens/VoiceSessionScreen.kt`, `voice/VoiceAgentViewModel.kt`,
  `voice/VoiceSessionUiState.kt` (added `isGlassesCameraReady: Boolean = false`)

#### `SpecTalkNavGraph` — `ModalNavigationDrawer` wraps all drawer routes
- `ModalNavigationDrawer` now wraps the entire `NavHost`. `drawerState` is hoisted here and
  `openDrawer()` / `closeDrawer()` are passed to `HomeScreen` and `GalleryScreen` as lambdas.
- `gesturesEnabled` is `true` only while `currentRoute` is in
  `drawerRoutes = setOf(Home, Gallery, Settings)` — swipe gesture disabled on auth and voice screens.
- `AppDrawer` is conditionally rendered only when `showDrawer` is true to avoid composing it
  over the auth screens.
- Added `Gallery` composable destination.
- **File:** `navigation/SpecTalkNavGraph.kt`

#### `HomeScreen` — hamburger menu replaces top-right icon buttons
- Removed `onNavigateToSettings` and `onSignOut` parameters (now handled by the drawer).
- Added `onOpenDrawer: () -> Unit` parameter.
- `TopAppBar.navigationIcon` changed from a logo placeholder to `Icons.Filled.Menu` (`IconButton`
  calling `onOpenDrawer`). The settings and logout `IconButton`s on the right side are removed.
- **File:** `ui/screens/HomeScreen.kt`

#### `Screen.kt` — `Gallery` destination added
- New `data object Gallery : Screen { override val route = "gallery" }`.
- **File:** `navigation/Screen.kt`

#### Inactivity timeout extended to 20 seconds
- `INACTIVITY_TIMEOUT_MS` in `VoiceAgentViewModel` changed from `10_000L` → `20_000L`.
- Rationale: 10 s was too aggressive — users thinking aloud or pausing mid-sentence triggered
  premature disconnects. 20 s gives enough headroom while still auto-closing idle sessions.
- **File:** `voice/VoiceAgentViewModel.kt`

---

## [Unreleased] — Fix wake notification sound + live wake word settings

### Fixed

#### [A-1] Wake notification plays OS sound at wrong time (premature chime)
- **Root cause**: `postWakeNotification()` used `NotificationManager.IMPORTANCE_HIGH` on the
  `hotword_wake_channel` channel. Android plays the channel's default notification sound
  immediately when the notification is posted — at wake-word detection time — before Gervis
  is connected. This produced two sounds: one premature OS chime and one correct activation
  beep when the WebSocket connected.
- **Fix**: Changed channel importance to `IMPORTANCE_LOW` (silent, no heads-up) so the
  notification only provides the full-screen intent / screen-wake behaviour it is actually
  needed for. New channel ID `hotword_wake_v2` forces channel recreation on existing installs
  (Android locks channel settings after first creation). Also removed `CATEGORY_CALL` from
  the notification builder to prevent OEM-specific extra audio routing.

#### [A-2] Wake word setting change has no effect until app restart
- **Root cause**: `updateListeningState()` had an `if (speechService == null)` guard that
  prevented `startListening()` from running when Vosk was already active. Since
  `startListening()` is where the Vosk grammar is rebuilt from SharedPreferences, changing
  the wake word in Settings had no effect while the service was running.
- **Fix**: Removed the guard. `startListening()` already calls `speechService?.stop()` as
  its first line, so calling it unconditionally safely restarts the recognizer with the
  current configured word on every state update (settings save, session end, HomeScreen
  entry).

---

## [Unreleased] — Fix wake word false positives

### Fixed

#### Wake word triggers without saying "Hey Gervis" [HIGH]
Three compounding root causes identified and fixed:

- **Root cause 1 — `onPartialResult` was firing the wake word:** Vosk emits partial
  results continuously as audio arrives; they are unstable mid-utterance hypotheses
  designed for live-caption display, not decision making. The constrained grammar was
  mapping phonemes similar to "gervis" (e.g. "nervous", "service") onto the wake word
  during partial evaluation. `onPartialResult` is now a no-op — all triggering moves
  exclusively to `onResult` (the final, committed hypothesis).

- **Root cause 2 — Short-name trigger (`"gervis"` alone) was too loose:** `isWakePhrase`
  matched `text == shortName` which is just `"gervis"` without "hey". A single word is
  acoustically ambiguous; the whole point of a two-word keyphrase is that the sequence
  is statistically rare in ambient speech. `isWakePhrase` replaced with `isFullWakePhrase`
  that only matches when `text.contains(configuredWord)` — the full phrase is required.

- **Root cause 3 — Short name in the Vosk grammar:** The grammar included both
  `"hey gervis"` and `"gervis"` as explicit alternatives. This actively instructed Vosk
  to search for the short name, increasing its probability of matching ambient phonemes.
  Grammar simplified to `["hey gervis", "[unk]"]` — the short name is removed.

- **Secondary improvement — Vosk model upgraded:** `vosk-model-small-en-us-0.15` (2021)
  replaced with `vosk-model-small-en-us-0.22` in `app/build.gradle.kts`. Same ~40 MB
  download; significantly better accuracy with constrained grammars. To apply: delete
  `android/app/src/main/assets/model/` and rebuild — the Gradle task re-downloads automatically.

- **Files:** `hotword/HotwordService.kt`, `app/build.gradle.kts`

---

## [Unreleased] — UX redesign: Apple-inspired interaction across all screens

### Changed

#### `Type.kt` — Apple-like typography scale
- Negative letter-spacing on large headings (`headlineLarge`: 34sp Bold, `headlineMedium`: 28sp
  Bold) matching SF Pro proportions.
- `bodyLarge` / `bodyMedium` / `bodySmall` sized at 17 / 15 / 13sp with subtle negative tracking
  for a denser, more legible feel.
- Added `titleMedium` (17sp SemiBold) and `labelLarge` (15sp SemiBold) to the scale so buttons
  and row titles have consistent, purposeful weight.
- **File:** `ui/theme/Type.kt`

#### `SplashScreen.kt` — Scale-in animation
- Added `animateFloatAsState` scale (0.88 → 1.0, 800ms `FastOutSlowInEasing`) alongside the
  existing alpha fade — the logo now grows into place instead of just fading in.
- Logo enlarged from 120dp → 140dp. "POWERED BY GERVIS" in uppercase with wide letter-spacing.
- **File:** `ui/screens/SplashScreen.kt`

#### `LoginScreen.kt` / `RegisterScreen.kt` — Apple-style auth forms
- Replaced `OutlinedTextField` with a custom `AppleTextField` composable: `TextField` (filled
  variant) with `surfaceVariant` container, transparent indicator line replaced by a subtle
  focused primary underline, and `RoundedCornerShape(14.dp)`. Matches iOS text input aesthetics.
- `AppleTextField` is `internal` and shared with `RegisterScreen`.
- Heading enlarged to `headlineLarge` (34sp Bold). Subtitle at 50% alpha below.
- Primary button: 56dp height, `RoundedCornerShape(16.dp)`. Google button same height.
- Added `imePadding()` so the keyboard does not cover the fields.
- Spacing tightened: 10dp between fields instead of 12dp; a single spacer handles error/no-error
  transitions without a layout jump.
- **Files:** `ui/screens/LoginScreen.kt`, `ui/screens/RegisterScreen.kt`

#### `HomeScreen.kt` — Pill FAB + card rows + cleaner header
- `FloatingActionButton` replaced with `ExtendedFloatingActionButton` ("Start talking" + mic
  icon, `RoundedCornerShape(28.dp)`) positioned at `FabPosition.Center`. The primary action is
  now thumb-reachable at the bottom center.
- "Sign out" `TextButton` replaced with a `Icons.AutoMirrored.Filled.Logout` `IconButton` at
  lower opacity in the top bar. Sign-out is also now available at the bottom of Settings.
- Conversation rows replaced with `ConversationCard`: a `Card` with `RoundedCornerShape(16.dp)`,
  `16.dp` horizontal margin, and `6.dp` vertical gap. `HorizontalDivider` between rows removed —
  spacing alone creates visual separation.
- Row layout: small 10dp colored-dot state indicator on the left; summary text + state chip +
  timestamp in the content column; resume badge on the right.
- Delete swipe background also clipped to `RoundedCornerShape(16.dp)` to match the card shape.
- 96dp bottom spacer added so the FAB never overlaps the last conversation row.
- **File:** `ui/screens/HomeScreen.kt`

#### `VoiceSessionScreen.kt` — Immersive full-screen orb
- Removed `TopAppBar`. Back button is an inline `IconButton` in a plain `Row` at the top of
  the content column, with `statusBarsPadding()` — no Material chrome, no title bar background.
- "Gervis" title centred using right-padding to balance the back button.
- New `VoiceOrb` composable: four concentric `CircleShape` `Box`es (200dp outer ambient ring →
  140dp middle ring → 100dp inner ring → 72dp core with mic icon). All rings share a single
  `InfiniteTransition` pulse (scale 1.0 → 1.14); pulse speed varies by state (650ms mic active,
  1100ms connected/connecting, no pulse when offline). Ring alpha scales with `isActive` state.
- Orb color: gold (secondary) when mic streaming, amber (tertiary) while connecting, red
  (primary) when connected, dim when offline.
- Status text centered below orb in `titleMedium` SemiBold with orb color.
- "End Session" button replaced with a minimal outlined pill (`widthIn(min=160dp)`,
  `RoundedCornerShape(22.dp)`). Error color on text only — no error background.
- Transcript chat bubbles: borders removed (background fill alone), corner radii increased to
  20dp / 5dp for a modern message-bubble shape.
- Fallback "project plan ready" message uses 20dp padding and a 20dp rounded surface.
- **File:** `ui/screens/VoiceSessionScreen.kt`

#### `SettingsScreen.kt` — iOS-grouped card sections + sign-out
- All five sections (Voice, Devices, Location, Notifications, Integrations) each wrapped in a
  new `SettingsGroup` composable: uppercase `labelSmall` section title above a `Surface` with
  `RoundedCornerShape(16.dp)` and `tonalElevation = 1.dp` — matches iOS Settings visual rhythm.
- `SettingsToggleRow` receives an explicit `modifier` parameter so padding lives at the call
  site, not inside the composable.
- Text fields inside sections use `RoundedCornerShape(12dp)` to match the grouped card style.
- "Sign Out" destructive `TextButton` (error color, full-width, 52dp) added at the very bottom
  of the scroll content — the standard Apple placement for a sign-out action.
- `SettingsScreen` now accepts `onSignOut: () -> Unit = {}`. `SpecTalkNavGraph` passes
  `authViewModel.signOut()` + navigation to Login.
- **Files:** `ui/screens/SettingsScreen.kt`, `navigation/SpecTalkNavGraph.kt`

#### `PrdConfirmationCard.kt` — Stacked CTAs + more breathing room
- "Build it" and "Change something" buttons now stack **vertically** (full-width, 52dp height
  each) instead of side-by-side. Larger touch targets; clearer visual hierarchy.
- Horizontal padding increased from 20dp → 24dp, top corner radius 24dp → 28dp.
- Handle bar width 36dp → 40dp at 60% alpha. Header top-alignment (was `CenterVertically`)
  so long project names don't misalign the scope badge.
- Change-request flow adds a "Cancel" button below "Send" so the user can exit without a tap
  outside.
- **File:** `ui/components/PrdConfirmationCard.kt`

---

## [Unreleased] — Handle session_timeout WebSocket message

### Fixed

#### Gemini Live 10-minute session limit shown as error instead of informational message [MEDIUM]
- **Root cause:** The backend now sends `{"type": "session_timeout", "message": "..."}` when
  the Gemini Live preview model's ~10-minute hard session limit is hit. Previously this arrived
  as `{"type": "error"}` — indistinguishable from a real crash, triggering the red error state.
- **Fix:** Added `VoiceClientEvent.SessionTimeout` to `VoiceClientEvent` sealed interface in
  `BackendVoiceClient`. The `handleControlMessage` now dispatches `"session_timeout"` to this
  new event instead of falling through to the `"error"` handler. `VoiceAgentViewModel` handles
  `SessionTimeout` as a clean session teardown: flushes and stops `PcmAudioPlayer`, closes the
  WebSocket, resumes `HotwordService`, and updates `statusMessage` with the backend's human-
  readable message (e.g. "Session ended after 10 minutes. Say 'Hey Gervis' to continue."). No
  `recentError` is set — the error color/screen is not shown.
- **Files:** `voice/BackendVoiceClient.kt`, `voice/VoiceAgentViewModel.kt`

---

## [Unreleased] — Integrations: OpenClaw connect/disconnect in Settings

### Added

#### `IntegrationsRepository` — new repository for `/integrations` API
- `data class IntegrationItem(service, urlPreview, connected)` and
  `sealed class SaveResult { Success(urlPreview, message) / Error(message) }`.
- `getIntegrations(jwt)` — `GET /integrations`; returns empty list on any network or HTTP error.
- `saveIntegration(jwt, service, url, token)` — `POST /integrations`; returns `SaveResult`.
- `deleteIntegration(jwt, service)` — `DELETE /integrations/{service}`; returns Boolean.
- Same OkHttp/`Dispatchers.IO` pattern as `ConversationRepository`.
- **File:** `integrations/IntegrationsRepository.kt`

#### `SettingsScreen` — "Integrations" section
- New **Integrations** section at the bottom of Settings (below Notifications).
- Section header includes an inline `CircularProgressIndicator` while the list is loading.
- Integration status is loaded on screen entry via `LaunchedEffect(Unit)`.
- **OpenClaw row** shows:
  - Service name ("OpenClaw") + description ("OpenClaw — AI coding agent").
  - Green pill "Connected · {url_preview}" when connected; grey pill "Not connected" otherwise.
  - "Connect" button (not connected, form hidden) → expands inline connect form.
  - "Disconnect" button (error color, connected) → calls `DELETE /integrations/openclaw`, reloads.
- **Connect form** (`AnimatedVisibility`):
  - Title: "Connect OpenClaw".
  - "Base URL" `OutlinedTextField` (placeholder: `https://your-machine.tail-xxxx.ts.net`).
  - "Hook Token" `OutlinedTextField` with `PasswordVisualTransformation`.
  - "Save" button enabled only when both fields are non-blank; shows `CircularProgressIndicator`
    while in-flight.
  - On success: dismisses the form, reloads integration status, shows backend `message` field
    ("Your credentials have been encrypted and stored securely.") as a `Snackbar`.
  - On error: shows error message as a `Snackbar`.
  - "Cancel" clears and collapses the form.
- Credentials are passed once to the backend and immediately discarded — never stored on-device.
- Added `SnackbarHost` to the existing `Scaffold` and `verticalScroll` to the content `Column`.
- **File:** `ui/screens/SettingsScreen.kt`

---

## [Unreleased] — Bug fixes: auto-open navigation + wake sound timing

### Fixed

#### Auto-open on job completion does not navigate to conversation [HIGH]
- **Root cause:** Android 10+ background activity start restrictions silently block
  `startActivity()` from `FcmService` when the app is not in the foreground. The
  `runCatching` wrapper swallowed the exception, so the notification appeared but
  auto-navigation never happened. The `NotificationEventBus.emitConversationId()` call
  also had no effect because no Compose collector was active while backgrounded.
- **Fix:** `FcmService.onMessageReceived()` now writes the `conversationId` to
  `AppPreferences` (key `pref_pending_auto_open_conversation_id`) **before** attempting
  `startActivity()`. On the next foreground entry, `SpecTalkNavGraph`'s `LaunchedEffect`
  reads and clears this value and navigates to the conversation — covering the
  background/killed case reliably. The existing `emitConversationId()` + `startActivity()`
  path is unchanged and still handles the foreground case instantly.
- **Files:** `settings/AppPreferences.kt`, `notifications/FcmService.kt`,
  `navigation/SpecTalkNavGraph.kt`

#### Wake confirmation sound plays before Gemini connection is ready [MEDIUM]
- **Root cause 1:** `HotwordService.playWakeBeep()` fired a `ToneGenerator` beep
  immediately on wake-word detection — before any WebSocket was opened. This gave the user
  false "ready" feedback up to ~500ms early.
- **Root cause 2:** `VoiceAgentViewModel.playActivationSound()` was called at the start of
  `startSession()` before the WebSocket connection was even attempted. Its implementation
  was also a stub (`delay(300)`) that never actually played any sound from the `SoundPool`.
- **Fix:** Removed `playWakeBeep()` from `HotwordService` entirely (method and both call
  sites in `onResult`/`onPartialResult`). Moved `playActivationSound()` to the
  `VoiceClientEvent.Connected` handler in `VoiceAgentViewModel`, right before
  `startMicrophone()` — the sound now plays at the exact moment the backend connection is
  ready. Fixed `playActivationSound()` to call `soundPool?.play(...)` instead of the no-op
  `delay(300)` stub. Removed `suspend` modifier (no longer needed).
- **Files:** `hotword/HotwordService.kt`, `voice/VoiceAgentViewModel.kt`

---

## [Unreleased] — Setting: auto-open conversation on job-complete notification

### Added

#### Settings toggle — "Auto-open on job complete"
- New boolean preference `pref_auto_open_on_notification` (default **off**) in
  `AppPreferences`. Two new methods: `isAutoOpenOnNotification(context)` and
  `setAutoOpenOnNotification(context, enabled)`.
- **File:** `settings/AppPreferences.kt`

#### `FcmService` — auto-open logic on message received
- When the setting is enabled, `onMessageReceived` does two things in addition to showing
  the notification (which is still shown regardless):
  1. `NotificationEventBus.emitConversationId(conversationId)` — if the app is already in
     the foreground the NavGraph collects this immediately and navigates.
  2. `startActivity(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_SINGLE_TOP)` — brings the app to
     the foreground if it is backgrounded; `MainActivity.onNewIntent` fires, re-emits the
     conversation ID, and the NavGraph navigates. Wrapped in `runCatching` so a system
     background-activity-start restriction degrades gracefully (notification tap still works).
- Once `VoiceSessionScreen` opens, `startSession()` connects the WebSocket and the backend's
  existing resume-event injection makes Gervis speak the result naturally — no additional
  code required.
- **File:** `notifications/FcmService.kt`

#### `SettingsScreen` — "Notifications" section
- New section header "Notifications" below the Location section.
- Uses new private `SettingsToggleRow` composable (title + subtitle + `Switch`) that matches
  the existing `LocationSettingsSection` visual style — reusable for future toggles.
- **File:** `ui/screens/SettingsScreen.kt`

---

## [Unreleased] — Fix barge-in silenced while Gervis speaks

### Fixed

#### Mic muted during playback — barge-in never reached backend
- **Symptom:** Speaking while Gervis was mid-response had no effect — the response kept
  playing and Gervis never stopped.
- **Root cause:** `VoiceAgentViewModel.startMicrophone()` gated audio chunks behind
  `hasPendingAudio != true`, muting the mic completely while the player had audio queued.
  The backend never received the user's voice, so Gemini never detected a barge-in and
  never sent `interrupted`.
- **Fix:** Removed the gate. Mic chunks are now sent unconditionally. Hardware AEC
  (`VOICE_COMMUNICATION` audio source + `AcousticEchoCanceler`) is the correct layer for
  echo suppression — the ViewModel should not silence the mic. The `hasPendingAudio` check
  inside the inactivity timer loop is unchanged (it correctly prevents auto-disconnect while
  Gervis is speaking).
- **File:** `voice/VoiceAgentViewModel.kt`

---

## [Unreleased] — Phase 5: coding mode UI + PRD confirmation card

### Added

#### `PrdSummary` — data class for PRD persistence
- New `data class PrdSummary` in `voice/PrdSummary.kt` holding all fields produced by the
  backend's PRD shaper: `projectName`, `description`, `targetPlatform` (web / mobile / backend /
  fullstack), `keyFeatures`, `techStack`, `scopeEstimate` (small / medium / large).
- `toJson()` serialises to a JSON string for SharedPreferences storage.
- `fromJson(JSONObject)` and `fromJsonString(String)` parse back from storage; both return
  `null` on any parse error (never crash).
- **File:** `voice/PrdSummary.kt`

#### `BackendVoiceClient` — parse PRD summary from `awaiting_confirmation` state_update
- `StateUpdate` event extended with optional `prdSummary: PrdSummary? = null` field (default
  null for all other state values, so existing call-sites are unaffected).
- In `handleControlMessage`, when `state == "awaiting_confirmation"`, parses the nested
  `prd_summary` JSON object from the control message into a `PrdSummary` before emitting.
- **File:** `voice/BackendVoiceClient.kt`

#### `VoiceSessionUiState` — two new fields for Phase 5 state
- `conversationState: String = ""` — mirrors the latest backend conversation state received
  via `state_update` (e.g. `"idle"`, `"coding_mode"`, `"awaiting_confirmation"`). Used by
  `VoiceSessionScreen` to decide which overlays to show.
- `prdSummary: PrdSummary? = null` — non-null only while the conversation is in
  `awaiting_confirmation`. Cleared automatically on `running_job` or `idle` transitions.
- **File:** `voice/VoiceSessionUiState.kt`

#### `VoiceAgentViewModel` — full Phase 5 state handling + PRD persistence
- **`StateUpdate` handler** now dispatches on three new state values:
  - `"coding_mode"` — updates `conversationState`; no card shown (user responds by voice).
  - `"awaiting_confirmation"` — stores the `prd_summary` in SharedPreferences, updates
    `conversationState` and `prdSummary` in UI state so the card renders immediately.
  - `"running_job"` / `"idle"` — clears stored state + PRD from SharedPreferences, sets
    `prdSummary = null` to dismiss the card.
- **PRD persistence** — six new private helpers manage two SharedPreferences keys per
  conversation:
  - `prd_summary_<conversationId>` — JSON-serialised `PrdSummary`.
  - `conv_state_<conversationId>` — the stored backend state string.
  - Both keys use the existing `spectalk_prefs` SharedPreferences file so no new file is
    created.
- **`startSession()` restoration** — after resolving the conversation ID, the ViewModel reads
  `conv_state_<id>` and `prd_summary_<id>` from SharedPreferences. If the stored state is
  `"awaiting_confirmation"`, it immediately updates the UI state so the card appears before
  the WebSocket even connects — covers the app-backgrounded case without a round-trip.
- **`confirmPrd(conversationId, confirmed, changeRequest)`** — new public method. Calls
  `ConversationRepository.confirmPrd()` on a coroutine. On success, optimistically clears
  `prdSummary` from UI state and removes the SharedPreferences entries. On failure, shows
  an error snackbar. The WebSocket `state_update` (running_job or idle) follows shortly and
  confirms the transition.
- **File:** `voice/VoiceAgentViewModel.kt`

#### `ConversationRepository.confirmPrd` — new REST call
- `POST /conversations/{conversationId}/confirm`
  - Body `{"confirmed": true}` for "Build it".
  - Body `{"confirmed": false, "change_request": "..."}` for "Change something".
  - Returns `true` on 2xx, `false` on any network error or non-2xx (404 = no pending PRD).
- Idiomatic: uses the same `OkHttpClient`, timeout, and error-handling pattern as
  `ackResumeEvent`.
- **File:** `conversations/ConversationRepository.kt`

#### `PrdConfirmationCard` — new composable
- Bottom-sheet-style `Card` with rounded top corners; appears as an overlay inside
  `VoiceSessionScreen` when `state == "awaiting_confirmation"`.
- **Card contents (top to bottom):**
  1. Drag handle bar (decorative).
  2. Header row: project name (titleLarge bold) + `ScopeBadge` pill (green = small / amber =
     medium / red = large).
  3. Description text (bodyMedium).
  4. `PlatformChip`: icon + label for web / mobile / backend / full stack.
  5. Tech stack in `FontFamily.Monospace` using primary colour.
  6. Up to 5 bullet-point key features (bodySmall).
  7. `HorizontalDivider`.
  8. Footer row: "Change something" (outlined) + "Build it" (filled primary) buttons.
- **"Change something" flow:** tapping reveals an `AnimatedVisibility` block containing an
  `OutlinedTextField` and a "Send" button. Send is disabled while the field is blank.
  The text field captures the user's change request, which is passed to `onChangeSomething`.
- Voice confirmation (user says "yes"/"no") dismisses the card automatically via the
  `state_update: running_job` or `state_update: idle` WebSocket message — no button tap needed.
- **File:** `ui/components/PrdConfirmationCard.kt`

#### `VoiceSessionScreen` — PRD card overlay + coding mode subtitle + fallback
- Scaffold content wrapped in a `Box` so the PRD card can be layered over the transcript.
- **PRD card overlay:** `AnimatedVisibility(slideInVertically / slideOutVertically)` aligned
  to `BottomCenter`. Visible whenever `uiState.prdSummary != null`. Passes `confirmPrd()`
  callbacks to the card. Stays visible after phone disconnects (card persists until state
  resolves).
- **Fallback message:** shown at the bottom when `conversationState == "awaiting_confirmation"`
  AND `prdSummary == null` AND the session is not connected. Informs the user: *"Your project
  plan is ready. Tap + and say 'Hey Gervis' to review it by voice."* Covers the edge case
  where the app was killed before the WebSocket delivered the `prd_summary`.
- **`coding_mode` subtitle:** `CompactStatusRow` now shows an italic "Gervis is designing
  your project…" line (in secondary colour) when `conversationState == "coding_mode"`.
  Gervis is asking clarifying questions during this phase; no buttons are needed.
- **File:** `ui/screens/VoiceSessionScreen.kt`

### Changed

#### `HomeScreen` — `awaiting_confirmation` chip label renamed to "Review"
- `stateLabel("awaiting_confirmation")` was `"Confirm"`, now `"Review"` to match the
  acceptance criteria and align with the amber chip colour (the chip already used
  `MaterialTheme.colorScheme.tertiary`).
- **File:** `ui/screens/HomeScreen.kt`

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
