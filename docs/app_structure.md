# SpecTalk App Structure

Use this document as a prompt/context handoff for any designer agent redesigning the app UI.
It describes what exists today, what flows already work, and what brand language must be
preserved.

## Product framing

- Product name: `SpecTalk`
- Assistant name: `Gervis`
- Core promise: a hands-free project creation app for Meta Ray-Ban glasses, Meta wearables,
  and Bluetooth audio devices such as AirPods
- Main interaction model: the user speaks to Gervis, Gervis asks clarifying questions,
  generates a project plan, gets confirmation, then dispatches background work

Important naming rule:
- Never call the mascot or assistant "SpecTalk"
- `SpecTalk` is the product
- `Gervis` is the assistant inside the product

## What the app does today

The app already supports these core capabilities:

- Firebase auth with login and registration
- Wake-word entry into a voice session, default wake word: `Hey Gervis`
- Manual start of a voice session from the Home screen
- Real-time voice conversation with a backend Gemini Live session
- Conversation history list on the Home screen
- PRD confirmation card inside the voice session
- Background job flow with resume via notification
- Settings for wake word, location sharing, notification behavior, and integrations
- Meta glasses image capture during an active voice session
- Phone camera fallback during an active voice session
- Gallery of captured images from glasses or phone camera

## High-level architecture

The product is intentionally split into two layers:

- Android app: thin client for auth, UI, microphone, speaker playback, camera capture,
  wake word, gallery, settings, and notifications
- Python backend: owns the Gemini Live session, tools, project orchestration, PRD generation,
  jobs, and state

Important implementation rule:
- The phone does not hold Gemini credentials
- The backend owns the Live API session

Current Live model policy:
- Use `gemini-2.5-flash-native-audio-preview-12-2025` for Gemini Live in this project

## Current navigation structure

Current top-level routes:

- `Splash`
- `Login`
- `Register`
- `Home`
- `Gallery`
- `Settings`
- `Voice Session`

Current drawer navigation:

- `Home`
- `Gallery`
- `Settings`
- `Sign out`

## Current screen structure

### Splash

- Gervis-branded launch moment
- Decides whether to send the user to auth or into the app

### Login

- Email/password login
- Google sign-in
- Clean auth layout

### Register

- Email/password account creation
- Google sign-in path

### Home

- Main hub after auth
- Top app bar with menu
- Conversation history list
- Swipe to delete conversations
- Empty state for first-time users
- Large centered FAB: `Start talking`
- Also listens for wake-word navigation into voice session

### Voice Session

This is the most important screen in the product right now.

Current structure:

- Back button
- Center title: `Gervis`
- Right-side action icon for image capture
- Large animated voice orb
- Status label such as connecting, listening, connected
- Transcript list with user and assistant turns
- End session button
- PRD confirmation card overlay when the backend enters `awaiting_confirmation`

Current image behavior:

- During an active voice session, if Meta glasses are connected and the DAT camera stream is
  ready, the top-right camera icon sends a single still frame from the glasses to Gervis
- If glasses are not available but the session is connected, the same slot becomes a phone
  camera action
- Captured images are also stored in the local gallery

This means:

- Image description is currently an in-session action
- It is a single tap action, not a long press
- It is app-driven, not a hardware-button flow on the glasses

### Gallery

- Grid of captured images
- Images can come from `glasses` or `camera`
- Each tile shows source badge and timestamp
- Delete action exists
- Empty state explains that images captured during voice sessions appear here

### Settings

Current sections include:

- Voice
  - Wake word editing and save action
- Devices
  - Connection status for Meta wearables or Bluetooth audio
- Location
  - Toggle for location sharing
- Notification behavior
- Integrations
  - OpenClaw connection form and status
- Sign out

## Current user flows

### Voice-first project flow

1. User says the wake word or taps `Start talking`
2. Voice session opens
3. Gervis listens and responds in real time
4. Gervis may ask clarification questions
5. Backend generates a PRD
6. PRD confirmation card appears in the voice session
7. User confirms or requests changes
8. Background job starts
9. User gets a notification when work is done
10. User resumes later through the app/notification/wake word

### Image description flow

1. User starts a voice session
2. With Meta glasses connected, user taps the camera icon once
3. A single current glasses frame is captured and sent to the backend
4. User asks something like:
   - "What do you see?"
   - "Describe this"
   - "Look at this"
5. Gervis responds based on the injected image

Current limitation:
- There is no standalone "capture from glasses hardware button and auto-describe" flow yet

## Brand and visual direction

Brand direction must be taken from `android/docs/branding.md`.

### Identity

- The mascot is Gervis, a retro condenser microphone character
- Personality cues: metallic red body, metallic gold capsule, black glasses, pulse motif
- The mascot is premium, warm, and characterful

### Color direction

Primary palette:

- Metallic red family for primary brand actions
- Metallic gold family for accents, highlights, and premium details
- Warm neutrals, not cold grey

Theme stance:

- Dark-first product
- Light theme exists, but the dark theme is the main emotional direction

Atmosphere:

- Warm
- Premium
- Slightly cinematic
- Confident, not playful-chaotic
- Assistive, not enterprise-generic

### Typography

- Current implementation uses Android system default typography
- Screen labels and important headings should feel elegant and premium
- Avoid sterile, overly technical visual language

### UI principles to preserve

- Hands-free feeling should remain central
- The app should feel like a companion for creating while walking, commuting, or moving
- Keep the distinction between product and assistant clear
- Preserve voice as the primary interaction, not just one feature among many
- Keep the design premium and warm rather than generic SaaS

## Functional constraints for redesign

Any redesign should respect these realities:

- Voice session is the center of the product
- Wake word is a key entry point
- Notifications are part of the resume loop
- PRD confirmation happens inside the voice session UI
- Image capture from glasses currently happens from the in-app voice-session camera action
- Home, Gallery, Settings, Login, Register, and Voice Session already exist and should still
  have clear places in the navigation model

## What a designer agent should optimize for

- Make the product feel unmistakably premium and wearable-first
- Make the voice session feel like the hero, not just a utilitarian chat screen
- Improve the clarity of the conversation-to-PRD-to-job flow
- Make image capture and visual understanding feel native and obvious
- Preserve the current capabilities while giving the app a more coherent brand system
- Design around the real existing structure rather than inventing a totally different product
