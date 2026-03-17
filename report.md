# Android App Analysis Report

Date: 2026-03-17
Scope: `TODO.md`, `CLAUDE.md`, and the Android app in `android/`

## Summary

This report focuses only on the Android app.

Main findings:

- The wake word is not truly gated by "Meta device connected" state.
- The current app does not use the Meta DAT SDK yet, so it has no authoritative notion of glasses availability.
- Wake-word activation is currently driven by a phone-side Vosk listener plus generic Bluetooth headset checks.
- The app currently has no location permission, no location settings UI, no location retrieval, and no path to send location context to the backend.

## 1. Wake Word: What Is Happening

### Intended behavior

The code comments and UI text say the wake word should only be active when a Bluetooth audio device such as earbuds or Meta glasses is connected.

Relevant files:

- `android/app/src/main/java/com/spectalk/app/hotword/HotwordService.kt`
- `android/app/src/main/java/com/spectalk/app/voice/VoiceAgentViewModel.kt`
- `android/app/src/main/java/com/spectalk/app/ui/screens/HomeScreen.kt`
- `android/app/src/main/java/com/spectalk/app/ui/screens/SettingsScreen.kt`

### Actual behavior

The app starts `HotwordService` from `HomeScreen` as soon as permissions are granted, regardless of whether a Meta device is connected.

After that, the service tries to gate listening based on:

- `BluetoothProfile.HEADSET`
- `BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED`

That is only a generic Android Bluetooth headset signal. It is not the same thing as:

- Meta DAT `Wearables.devices`
- Meta DAT registration state
- Meta DAT session/device state
- "Meta glasses are really available for this app right now"

### Root cause

The Android app is not integrated with the Meta DAT SDK at all yet.

There are:

- no `mwdat-core` / `mwdat-camera` / `mwdat-mockdevice` dependencies
- no `Wearables.initialize(...)`
- no `Wearables.devices.collect`
- no DAT session state observation

So the app cannot reliably answer the question "is the device connected?" in the Meta sense.

Instead, it uses generic Bluetooth headset state as a proxy.

## 2. Why The Wake Word Can Still Work Without The Expected Device Connection

### Important architectural point

The wake-word detector is Vosk running on the phone.

That means the hotword path is currently:

- foreground service starts
- Vosk model loads on the phone
- `SpeechService` starts listening
- wake phrase is detected locally
- app opens the voice session

This is not a Meta-glasses-native wake-word path.

### Why the symptom makes sense

If the app's Bluetooth proxy state is inaccurate, stale, or not aligned with your real definition of "connected device", the phone listener can still activate.

Even more importantly:

- the service itself is still alive as a foreground microphone service
- the app does not have a stronger device-state gate than generic Bluetooth headset state
- the codebase currently has no Meta-specific connection source of truth

So from the repo alone, the behavior is consistent with the current design.

### Practical conclusion

This is not just one small bug in one line.

It is a design mismatch:

- desired behavior: "wake word only when Meta device is connected"
- current implementation: "wake word on phone, loosely gated by generic Bluetooth headset presence"

## 3. Specific Code Insights

### `HomeScreen.kt`

`HomeScreen` requests permissions and immediately starts `HotwordService`.

That means service startup is tied to app entry and permission success, not to a verified Meta device connection.

### `HotwordService.kt`

`HotwordService`:

- computes `isBtHeadsetConnected` using `BluetoothProfile.HEADSET`
- updates it using `BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED`
- starts Vosk listening when `isBtHeadsetConnected` is true and hotword is not paused

This is the main gating logic today.

### `VoiceAgentViewModel.kt`

`VoiceAgentViewModel` duplicates the same generic Bluetooth headset observation and starts the hotword service again when it thinks a headset is connected.

This reinforces the same proxy rather than fixing it.

### `SettingsScreen.kt`

The Settings screen shows:

- "Connected — wake word active"
- "Connect earbuds or Meta glasses to enable wake word"

But this status is also derived only from generic Bluetooth headset state.

So the UI is potentially overstating what the app actually knows.

## 4. Recommendation For Wake Word

If you want the wake word to work only when a Meta device is connected, the Android app should stop treating generic Bluetooth headset connection as the source of truth.

Instead, the gate should be based on Meta DAT state, for example:

- `Wearables.devices`
- registration state
- device session state
- stream/session availability if that is the real requirement

Recommended direction:

1. Add Meta DAT SDK dependencies and initialization.
2. Observe real device availability through DAT.
3. Drive hotword enable/disable from DAT state.
4. Keep Bluetooth routing as an audio-routing concern only, not as the authoritative device connection model.

## 5. Location: Current State

The Android app currently has no location feature implemented.

### Missing pieces

- No `ACCESS_FINE_LOCATION` permission in `AndroidManifest.xml`
- No `ACCESS_COARSE_LOCATION` permission in `AndroidManifest.xml`
- No runtime location permission request
- No location section in Settings
- No location provider integration
- No location payload sent to the backend

### What exists today

The Settings page only contains:

- wake word editing
- a connected-device status row

There is no location toggle, button, or permission flow yet.

## 6. Backend Gap For Location

The backend also does not currently have a path for Android to send location context.

### Current voice protocol

The voice WebSocket currently supports:

- binary PCM audio
- `{"type":"end_of_speech"}`
- `{"type":"image", ...}`

There is no location control message.

### Current voice session start API

`POST /voice/session/start` returns a new conversation ID, but it does not accept location or any context payload.

### Current maps tool

The backend maps tool expects a human-readable `location: str`.

So even if Android had coordinates today, there is nowhere in the current protocol to send them.

## 7. Recommendation For Location

On Android, the likely implementation should be:

1. Add manifest permissions:
   - `ACCESS_FINE_LOCATION`
   - optionally `ACCESS_COARSE_LOCATION`
2. Add a Settings section to request location permission.
3. Use a location provider such as `FusedLocationProviderClient`.
4. Get either:
   - current location
   - or last known location
5. Send both:
   - raw coordinates
   - a human-readable location label

The best UX is to keep this optional:

- if permission is granted, send location context automatically
- if not, the backend/agent should ask the user for a place when needed

## 8. Message To Send To The Backend Agent

You can send this directly:

```text
We need session-level location context from Android for Maps queries.

Current backend gap:
- WS /ws/voice/{conversation_id} only accepts audio, end_of_speech, and image.
- POST /voice/session/start does not accept any request body/context.
- find_nearby_places(query, location) only accepts a human-readable location string.

Please add support for one of these:
1. Preferred: accept optional location context in POST /voice/session/start
2. Or: add a new WebSocket control message type: "location_context"

Suggested payload:
{
  "type": "location_context",
  "latitude": 52.5200,
  "longitude": 13.4050,
  "accuracy_meters": 18.0,
  "location_label": "Berlin, Germany",
  "captured_at": "2026-03-17T10:12:00Z"
}

Backend behavior:
- Cache the latest location context per conversation/session.
- When the user asks a nearby/maps question without an explicit place, use location_label as the default location for find_nearby_places().
- If only coordinates are present, reverse-geocode them or fall back to a lat/lng string.
- Keep this context optional; if missing, ask the user for a place normally.
```

## 9. Final Conclusion

From the current repo, the wake-word issue is best understood as an architectural mismatch, not just a single bug:

- the app wants Meta-device-aware behavior
- the implementation currently only knows generic Bluetooth headset state

And for location:

- nothing is implemented yet on Android
- nothing is wired yet in the backend protocol for Android location context

The next correct step would be to align Android with the Meta DAT SDK for device-connected state, and then add a proper location permission + payload flow.
