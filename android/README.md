# SpecTalk — Android App

The Android app is a secure audio terminal for SpecTalk. It handles voice I/O, wake-word
detection, push notifications, and UI. All AI intelligence lives in the backend — the app
never holds any API keys.

## Prerequisites

- Android Studio Hedgehog (2023.1.1) or newer
- JDK 11+
- Android device or emulator running API 31+ (Android 12+)
- A Firebase project with Email/Password sign-in enabled
- `google-services.json` from that Firebase project

## Getting Started

### 1. Set up Firebase

1. Go to [Firebase Console](https://console.firebase.google.com) and create a project (or use
   an existing one).
2. In **Authentication → Sign-in method**, enable **Email/Password**.
3. Click **Add app → Android**, use package name `com.spectalk.app`, and register.
4. Download `google-services.json` and place it at:
   ```
   android/app/google-services.json
   ```
   **Never commit this file to git** — it is listed in `.gitignore`.

### 2. Open the project in Android Studio

Open the `android/` directory (not the repo root) as the project root:

```
File → Open → <repo>/android
```

Android Studio will sync Gradle automatically on first open.

### 3. Build and run

```bash
# From android/ directory

# Build debug APK
./gradlew assembleDebug

# Install directly on connected device
./gradlew installDebug

# Run unit tests
./gradlew test
```

Or press **Run ▶** in Android Studio with a device or emulator selected.

---

## App Structure

```
app/src/main/java/com/spectalk/app/
├── SpecTalkApplication.kt       # Firebase init
├── MainActivity.kt              # Entry point, edge-to-edge, theme
├── auth/
│   ├── AuthUiState.kt           # Sealed interface: Loading / Unauthenticated /
│   │                            #   Authenticated / Error / VerificationEmailSent /
│   │                            #   PasswordResetSent
│   └── AuthViewModel.kt         # signIn, register, sendPasswordReset, signOut
├── navigation/
│   ├── Screen.kt                # Sealed route definitions
│   └── SpecTalkNavGraph.kt      # Full nav graph
└── ui/
    ├── screens/
    │   ├── SplashScreen.kt      # Animated fade-in, auto-routes on auth state
    │   ├── LoginScreen.kt       # Email/password + forgot password dialog
    │   ├── RegisterScreen.kt    # Email/password/confirm + email verification
    │   ├── HomeScreen.kt        # Conversation list (empty state for now)
    │   └── SettingsScreen.kt    # Wake word (SharedPreferences: pref_wake_word)
    └── theme/
        ├── Color.kt
        ├── Type.kt
        └── Theme.kt             # Material3 dark/light color schemes
```

## Auth Flow

```
App                             Firebase Auth SDK
 │                                     │
 ├─ Register screen                    │
 │   createUserWithEmailAndPassword ───►│
 │   sendEmailVerification ────────────►│ (sends email)
 │   signOut (force verify first)       │
 │                                     │
 ├─ Login screen                       │
 │   signInWithEmailAndPassword ───────►│
 │   check isEmailVerified             │
 │                                     │
 ├─ Get ID token ──────────────────────►│
 │◄── Firebase ID token ───────────────┤
 │                                     │
 ├─ POST /auth/session → backend
 │   (exchanges ID token for product JWT)
 │◄── product JWT
 │
 └─ Store JWT in EncryptedSharedPreferences (Phase 2)
```

## Navigation Flow

```
Splash → checks auth state
  ├─ already authenticated → Home
  └─ not authenticated → Login
       ├─ Login → Home (on success)
       ├─ Login → Register (create account)
       └─ Register → Login (after verification email sent)

Home → Settings (wake word)
Home → Voice Session (Phase 1, not yet implemented)
```

## Wake Word

Default wake word is **"Hey Gervis"**. Users can change it in Settings. The value is stored
under `SharedPreferences` key `pref_wake_word` and read by `HotwordService` on startup
(implemented in Phase 1).

## Key Dependencies

| Library | Purpose |
|---------|---------|
| Jetpack Compose + Material3 | UI |
| Navigation Compose | Screen routing |
| Firebase Auth KTX | Email/password authentication |
| Lifecycle ViewModel Compose | State management |
| Kotlinx Coroutines | Async operations |

## Phase Roadmap

- **Phase 0 (current)** — Auth UI: splash, login, register, home, settings
- **Phase 1** — Voice session UI, wake word, audio recorder/player, WebSocket client
- **Phase 2** — Wire auth to real backend (POST /auth/session, store product JWT)
- **Phase 3+** — See `../TODO.md`
