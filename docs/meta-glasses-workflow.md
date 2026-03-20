# Meta Glasses Workflow

## Goal

Make SpecTalk use Meta glasses camera access the same way the `samples/CameraAccess` app does:

1. pair and register the app properly with Meta AI
2. request DAT camera permission explicitly
3. start a real `StreamSession`
4. keep the stream alive long enough to reach `STREAMING`
5. call `capturePhoto()` from the app
6. save the image locally, show it in chat, and send it to Gemini

This is the workflow we should follow for all in-app Meta glasses captures.

## What the CameraAccess sample proves

The sample in `samples/CameraAccess` is not treating the glasses camera as "available if a Meta device exists". It uses a stricter flow:

- `samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/MainActivity.kt`
  - requests Android Bluetooth/Internet permissions first
  - initializes `Wearables` only after permissions are granted
  - uses `Wearables.RequestPermissionContract()` for wearable-side permissions

- `samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/wearables/WearablesViewModel.kt`
  - observes `Wearables.registrationState`
  - explicitly calls `Wearables.startRegistration(activity)`
  - checks DAT permission status for `Permission.CAMERA`
  - requests wearable camera permission before allowing streaming
  - observes `Wearables.devices` and the selector's active device

- `samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/StreamViewModel.kt`
  - creates a real `StreamSession` with `Wearables.startStreamSession(...)`
  - continuously collects both `videoStream` and `state`
  - only allows capture when `StreamSessionState.STREAMING`
  - uses `streamSession.capturePhoto()` for still capture
  - handles both `PhotoData.Bitmap` and `PhotoData.HEIC`

## What this means for SpecTalk

SpecTalk already has some of the plumbing:

- `android/app/src/main/AndroidManifest.xml`
  - has `com.meta.wearable.mwdat.APPLICATION_ID`
- `android/app/src/main/java/com/spectalk/app/SpecTalkApplication.kt`
  - calls `Wearables.initialize(this)`
- `android/app/src/main/java/com/spectalk/app/device/ConnectedDeviceMonitor.kt`
  - observes `Wearables.devices`
- `android/app/src/main/java/com/spectalk/app/device/GlassesCameraManager.kt`
  - creates a DAT `StreamSession`
  - keeps `state` and `videoStream` collected
  - calls `capturePhoto()`

But we are still missing the sample's full access workflow:

- no explicit registration screen or registration-state UX
- no explicit `Wearables.startRegistration(...)` flow in the app
- no explicit DAT camera permission request via `Wearables.RequestPermissionContract()`
- no `Wearables.checkPermissionStatus(Permission.CAMERA)` gate before starting streaming
- readiness is still inferred mostly from session state, not from the full registration + permission pipeline

## Why the current issue likely happens

When the user presses the physical button on the glasses and the image goes into Meta AI instead of SpecTalk, that is likely **not** our app capture path.

The CameraAccess sample shows the supported app flow as:

- app starts stream session
- app calls `capturePhoto()`
- app receives `PhotoData`

The sample does **not** show a hardware shutter callback that pushes the glasses photo into the app automatically.

So there are probably two different flows:

1. Meta AI / system flow on the glasses
2. app-owned DAT capture flow via `StreamSession.capturePhoto()`

For SpecTalk, we should rely on the second one.

## Recommended SpecTalk workflow

### 1. Register the app with Meta AI

Before trying to use the camera, the app should expose a clear "Connect Meta glasses" action that runs:

- `Wearables.startRegistration(activity)`
- `Wearables.registrationState.collect { ... }`

The UI should show:

- not connected
- registering
- registered
- registration failed

### 2. Request DAT camera permission explicitly

Before starting a stream session, do the same sequence as CameraAccess:

- call `Wearables.checkPermissionStatus(Permission.CAMERA)`
- if not granted, launch `Wearables.RequestPermissionContract()`
- only continue if the result is `PermissionStatus.Granted`

Without this, a connected wearable is not the same thing as a usable DAT camera.

### 3. Start the stream only after registration + permission

Once registration and camera permission are confirmed:

- start `Wearables.startStreamSession(...)`
- keep `session.state.collect { ... }` running
- keep `session.videoStream.collect { ... }` running

We should treat `StreamSessionState.STREAMING` as the real "camera ready" signal.

### 4. Capture only from the app-owned DAT session

For both manual and agent-requested captures:

- call `streamSession.capturePhoto()`
- convert `PhotoData.Bitmap` or `PhotoData.HEIC` to bytes
- save the image to gallery
- add the image preview to the conversation
- send the bytes to Gemini as the same image flow used by phone capture

This should be identical to the normal camera-chat flow after the bytes are obtained.

### 5. Do not rely on the physical glasses shutter button

Unless Meta exposes a dedicated callback for hardware shutter events and we wire it explicitly, we should assume:

- physical button capture belongs to Meta AI / system camera behavior
- SpecTalk capture must come from our own in-app trigger or agent tool request

That means the reliable paths are:

- user taps the image button in SpecTalk
- Gervis requests a visual capture and the app runs `capturePhoto()`

## Implementation checklist for SpecTalk

### Android app

Add or tighten these flows:

- registration UI
  - call `Wearables.startRegistration(activity)`
  - observe `Wearables.registrationState`
- camera permission bridge
  - use `Wearables.RequestPermissionContract()`
  - request `Permission.CAMERA`
- stream gating
  - do not start Meta capture unless registration and permission are confirmed
- state UX
  - show separate states for:
    - glasses detected
    - registered with Meta AI
    - DAT camera permission granted
    - stream warming up
    - camera ready
- capture UX
  - manual capture from the chat button
  - agent-requested capture during listening mode
  - both should use the same DAT photo pipeline

### Files to update

- `android/app/src/main/java/com/spectalk/app/device/GlassesCameraManager.kt`
- `android/app/src/main/java/com/spectalk/app/device/ConnectedDeviceMonitor.kt`
- `android/app/src/main/java/com/spectalk/app/voice/VoiceAgentViewModel.kt`
- add a Meta registration / permission helper similar to the sample's `WearablesViewModel`
- add an activity-level permission contract similar to the sample's `MainActivity.kt`

## Recommended user experience

Inside SpecTalk, the Meta glasses flow should feel like this:

1. User connects glasses in the app once
2. App completes Meta registration
3. App requests DAT camera permission once
4. Chat screen shows `Meta camera ready`
5. User says "what do you see?" or taps image capture
6. SpecTalk captures via DAT `capturePhoto()`
7. Image appears in the chat
8. Image is saved in Gallery
9. Same image is sent to Gemini for analysis

If any prerequisite is missing, the app should say exactly which one:

- `Meta glasses not registered`
- `Meta camera permission not granted`
- `Meta camera is still warming up`
- `No active DAT stream session`

## Bottom line

The CameraAccess sample suggests the core problem is probably **not** that Meta DAT lacks programmatic photo capture. The more likely issue is that SpecTalk is still skipping part of the official access workflow:

- registration
- DAT camera permission
- active device readiness
- fully warmed `StreamSession`

That is the flow we should adopt before expecting reliable photo capture from the glasses camera.
