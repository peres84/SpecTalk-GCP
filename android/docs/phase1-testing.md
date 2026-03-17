# Phase 1 — What You Should See & How to Test It

This document describes the **expected UI and behaviour** for a Phase 1 + Phase 3 build.
Use it to verify the app is working correctly during local testing.

---

## Screen 1 — Home (Conversation List)

### What you should see

```
┌────────────────────────────────────┐
│ SpecTalk            ⚙️   Sign out  │  ← TopAppBar
├────────────────────────────────────┤
│                                    │
│  [if no conversations]             │
│       🎙                           │
│  No conversations yet              │
│  Say "Hey Gervis" or tap + to start│
│                                    │
│  [if conversations exist]          │
│  ┌──────────────────────────────┐  │
│  │ 💬  1h ago          [Idle]   │  │  ← state chip
│  │     Voice conversation       │  │  ← last turn summary
│  └──────────────────────────────┘  │
│  ┌──────────────────────────────┐  │
│  │ 🎙  Just now       [Active]  │  │
│  │     What should I build?     │  │
│  └──────────────────────────────┘  │
│                                    │
│                          [+]       │  ← red FAB
└────────────────────────────────────┘
```

### What each element does

| Element | Action |
|---------|--------|
| **⚙️ gear icon** | Opens Settings screen (wake word configuration) |
| **Sign out** | Signs out and returns to Login screen |
| **Conversation row (tap)** | Opens that conversation in VoiceSessionScreen and resumes it |
| **Red FAB (+)** | Starts a brand new voice session (no existing conversation) |
| **Pull down** | Refreshes the conversation list |

### Known data issue
If a conversation row shows **"null"** instead of a summary, the backend returned the string
`"null"` for `last_turn_summary`. This is a backend-side fix — the field should return JSON
`null` (not the string). The Android fallback renders `"Voice conversation"` only when the
value is truly `null`.

---

## Starting a Voice Session — 3 Ways

### Way 1: FAB (new conversation)
1. Tap the red **+** FAB on HomeScreen
2. App navigates to VoiceSessionScreen
3. Activation chime plays through speaker/headset
4. Orb animates and shows "Connecting…"
5. Session connects, orb enters listening state ("Listening…")

### Way 2: Wake word (hands-free)
1. Make sure the app is open on HomeScreen (foreground)
2. Say **"Hey Gervis"** (or your configured wake word)
3. App navigates to VoiceSessionScreen automatically
4. Activation chime plays
5. Session starts — same flow as above

> If the wake word isn't triggering, check that `HotwordService` is running:
> look for the persistent "SpecTalk — Listening for Hey Gervis…" notification in the
> notification shade. If it's missing, the service hasn't started — restart the app or
> connect a Bluetooth headset.

### Way 3: Resume existing conversation
1. Tap any conversation row on HomeScreen
2. App navigates to VoiceSessionScreen with that conversation's history loaded
3. Past turns appear immediately as chat bubbles
4. Activation chime plays and the session connects

---

## Screen 2 — Voice Session

### What you should see

```
┌────────────────────────────────────┐
│                                    │
│     ● Listening          (pill)    │  ← green pulsing dot + label
│                                    │
│         ╔══════════╗               │
│         ║    ~~~~  ║               │  ← animated orb
│         ║    🎤    ║               │     pulsing rings when connected
│         ╚══════════╝               │
│         Listening…                 │
│                                    │
│  Conversation                      │
│  ┌──────────────── You ──────────┐ │
│  │  Hello, what can you do?     │ │  ← user bubble (right-aligned)
│  └─────────────────────────────┘ │
│  ┌─ Gervis ──────────────────────┐ │
│  │  I'm Gervis! I can help you  │ │  ← assistant bubble (left-aligned)
│  │  design and build projects…  │ │
│  └─────────────────────────────┘ │
│                                    │
│     [ End Session ]                │  ← red button
└────────────────────────────────────┘
```

### Connection status pill states

| Dot colour | Label | Meaning |
|------------|-------|---------|
| Grey | Offline | Not connected |
| Orange/yellow | Connecting… | WebSocket opening, waiting for backend |
| Green (pulsing) | Listening | Connected, mic is streaming |
| Blue | Connected | Connected, mic not yet active |

### Orb states

| State | Animation | Icon |
|-------|-----------|------|
| Disconnected | Static, grey | 🎤 (MicOff) |
| Connecting | Spinner inside orb | — |
| Connected | Slow pulse, coloured rings | 🎤 (MicOff) |
| Listening (mic active) | Fast pulse, bright rings | 🎤 (Mic) |

### Transcript bubbles
- **You (right side):** what Gervis heard you say — updates as you speak
- **Gervis (left side):** what Gervis responded — updates as it speaks
- All turns accumulate (scroll up to see earlier turns)
- Auto-scrolls to the latest turn

### Auto-disconnect (10 seconds)
If neither you nor Gervis speaks for 10 seconds:
- Session closes automatically
- Wake word listener reactivates
- App stays on VoiceSessionScreen (shows "Disconnected")
- Tap "End Session" to return to HomeScreen

### Barge-in (interrupting Gervis)
- Speak while Gervis is talking
- Gervis audio stops immediately
- Your new utterance is processed

---

## Screen 3 — Settings

Access via the **⚙️ gear icon** on HomeScreen.

```
┌────────────────────────────────────┐
│ ← Settings                         │
├────────────────────────────────────┤
│                                    │
│  Wake word                         │
│  ┌──────────────────────────────┐  │
│  │  Hey Gervis                  │  │
│  └──────────────────────────────┘  │
│  Default: Hey Gervis               │
│  Takes effect on next app start    │
│                                    │
│         [ Save ]                   │
│                                    │
└────────────────────────────────────┘
```

Change the wake word here. The new word is saved to SharedPreferences and used by
`HotwordService` on its next restart.

---

## Notification Behaviour

### While the app is running in the background
A persistent silent notification appears:
> **SpecTalk — Listening for "Hey Gervis"…**

This is `HotwordService` keeping the wake word detector alive. It is normal and expected.

### When the wake word fires (app in background)
A high-priority notification appears:
> **Gervis — Listening…**

Tapping it brings the app to the foreground and navigates to VoiceSessionScreen.

---

## Full End-to-End Test Checklist

Work through this top to bottom:

### Authentication
- [ ] Register with email and password → receive verification email
- [ ] Verify email → log in → land on HomeScreen
- [ ] Sign out → land on LoginScreen
- [ ] Log back in → land on HomeScreen (no re-verification needed)
- [ ] Auth state persists across app restarts (already logged in = goes straight to HomeScreen)

### HomeScreen
- [ ] Empty state shows "No conversations yet — Say Hey Gervis or tap + to start" when list is empty
- [ ] Conversation rows show: relative timestamp, state chip, summary text
- [ ] Pull-to-refresh reloads the list
- [ ] Tap ⚙️ → Settings screen opens
- [ ] Tap Sign out → LoginScreen

### New voice session (FAB)
- [ ] Tap FAB → VoiceSessionScreen opens
- [ ] Activation chime plays through speaker (or headset if connected)
- [ ] Status pill changes: Offline → Connecting… → Listening
- [ ] Orb animates correctly for each state
- [ ] Speak → your words appear as a right-aligned bubble
- [ ] Gervis responds → audio plays through speaker
- [ ] Gervis's response text appears as a left-aligned bubble
- [ ] Transcript list scrolls to latest turn automatically
- [ ] Tap "End Session" → returns to HomeScreen
- [ ] New conversation appears in HomeScreen list after session ends

### Wake word
- [ ] Say "Hey Gervis" while on HomeScreen → navigates to VoiceSessionScreen automatically
- [ ] Activation chime plays
- [ ] Session connects and starts listening
- [ ] "SpecTalk — Listening for Hey Gervis…" notification visible in notification shade

### Resume existing conversation
- [ ] Tap an existing conversation row → VoiceSessionScreen opens
- [ ] Past turns appear immediately (history loads before session connects)
- [ ] New session continues appending turns on top of history
- [ ] Tapping the same conversation again shows all turns including the new ones

### Inactivity auto-disconnect
- [ ] Start a session, say something, then stay silent for 10+ seconds
- [ ] Session closes automatically
- [ ] Wake word listener reactivates (notification reappears)

### Settings
- [ ] Change wake word to something custom (e.g. "Hey Nova")
- [ ] Restart app → "SpecTalk — Listening for Hey Nova…" in notification
- [ ] Say the new wake word → session starts

---

## What Is NOT in Phase 1 (comes later)

| Feature | Phase |
|---------|-------|
| Background job status (job_started, job_update banners) | 4 |
| Push notifications for completed jobs | 4 |
| Resume flow (welcome-back from notification) | 4 |
| Coding mode / project creation | 5 |
| Artifact browser | 6 |
| Meta Glasses video frames | 6 |
