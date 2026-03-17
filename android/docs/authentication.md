# Authentication — Technical Guide

SpecTalk uses **Firebase Authentication** with two sign-in methods:
1. Email / password (with email verification)
2. Google Sign-In (via Android Credential Manager)

---

## Architecture

```
UI Screen (Composable)
    │  calls
    ▼
AuthViewModel
    │  uses
    ├─ FirebaseAuth (email/password, Google credential)
    └─ CredentialManager (Google Sign-In bottom sheet)

AuthUiState (sealed interface)
    Loading | Unauthenticated | Authenticated | Error |
    VerificationEmailSent | PasswordResetSent
```

State flows from `AuthViewModel.state: StateFlow<AuthUiState>` into each screen via
`collectAsStateWithLifecycle()`.

---

## Email / Password

### Registration flow
1. User fills email + password + confirm password
2. `AuthViewModel.register(email, password)` calls Firebase `createUserWithEmailAndPassword`
3. Firebase sends a verification email (`sendEmailVerification`)
4. User is signed out immediately — must verify email before accessing Home
5. State becomes `VerificationEmailSent` → nav to Login with a success message

### Sign-in flow
1. `AuthViewModel.signIn(email, password)` calls Firebase `signInWithEmailAndPassword`
2. If `user.isEmailVerified == false` → sign out + show error
3. If verified → state becomes `Authenticated(email)` → nav to Home

### Password reset
1. User taps "Forgot password?" → dialog with email field
2. `AuthViewModel.sendPasswordReset(email)` calls Firebase `sendPasswordResetEmail`
3. State becomes `PasswordResetSent` → brief confirmation message shown

---

## Google Sign-In

Uses **Jetpack Credential Manager** (modern replacement for legacy GoogleSignInClient).

### Setup — Firebase Console (one-time)

1. Firebase Console → **Authentication** → **Sign-in method** → **Google** → Enable
2. Copy the **Web client ID** from "Web SDK configuration"
3. Paste it into `app/src/main/res/values/strings.xml`:
   ```xml
   <string name="google_web_client_id">1234567890-abc.apps.googleusercontent.com</string>
   ```
4. Make sure your app's **SHA-1 fingerprint** is registered in Firebase.
   Run in **PowerShell**:
   ```powershell
   & "C:\Program Files\Android\Android Studio\jbr\bin\keytool" -list -v -keystore "$env:USERPROFILE\.android\debug.keystore" -alias androiddebugkey -storepass android -keypass android
   ```
   Copy the **SHA-1** line from the output and add it in:
   Firebase Console → Project Settings → Your apps → Android app → **Add fingerprint**.

### Dependencies (`libs.versions.toml`)

```toml
credentials = "1.3.0"
googleid    = "1.1.1"

[libraries]
credentials              = { group = "androidx.credentials", name = "credentials" }
credentials-play-services = { group = "androidx.credentials", name = "credentials-play-services-auth" }
googleid                 = { group = "com.google.android.libraries.identity.googleid", name = "googleid" }
```

### Sign-in code (`AuthViewModel.signInWithGoogle`)

```kotlin
fun signInWithGoogle(context: Context) {
    viewModelScope.launch {
        val credentialManager = CredentialManager.create(context)
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)   // show all Google accounts
            .setServerClientId(context.getString(R.string.google_web_client_id))
            .setAutoSelectEnabled(false)
            .build()

        val result = credentialManager.getCredential(
            context = context,
            request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()
        )

        val googleIdToken = GoogleIdTokenCredential
            .createFrom((result.credential as CustomCredential).data)
            .idToken

        val firebaseCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
        auth.signInWithCredential(firebaseCredential).await()
        // user is now signed in
    }
}
```

### Calling from Composable

```kotlin
val context = LocalContext.current
OutlinedButton(onClick = { authViewModel.signInWithGoogle(context) }) {
    Text("Continue with Google")
}
```

> Pass `context` as a parameter — do NOT store it in the ViewModel as a field.
> The call is safe because `CredentialManager` only uses it to launch the system bottom sheet.

### Error handling

| Exception | Meaning |
|-----------|---------|
| `GetCredentialCancellationException` | User dismissed the picker — reset to `Unauthenticated` |
| Any other exception | Show error message from `e.message` |

Google-authenticated users have `isEmailVerified = true` automatically — no email verification step needed.

---

## Auth State Persistence

Firebase SDK persists auth state to disk automatically. On app restart:
- `AuthViewModel.init` calls `checkCurrentUser()`
- If `auth.currentUser != null && isEmailVerified` → `Authenticated` → SplashScreen routes to Home
- Otherwise → `Unauthenticated` → SplashScreen routes to Login

No manual token storage required for Firebase auth state.

---

## Product JWT (Phase 2)

After Firebase auth is confirmed, the app exchanges the Firebase ID token for a **product JWT**
from the backend. This JWT is used on all subsequent API calls.

```
POST /auth/session
Body: { "firebase_id_token": "<token>" }
Response: { "access_token": "<jwt>", "user_id": "...", "email": "..." }
```

Store the product JWT in `EncryptedSharedPreferences` (Phase 2 — not yet implemented).
Never store the Firebase ID token — it expires in 1 hour. Let the Firebase SDK manage it.

---

## Files Reference

| File | Purpose |
|------|---------|
| `auth/AuthViewModel.kt` | All auth logic — signIn, register, signInWithGoogle, signOut |
| `auth/AuthUiState.kt` | Sealed interface for all possible auth states |
| `ui/screens/LoginScreen.kt` | Login UI + Google button |
| `ui/screens/RegisterScreen.kt` | Registration UI + Google button |
| `ui/screens/SplashScreen.kt` | Auto-routes based on initial auth state |
| `navigation/SpecTalkNavGraph.kt` | Nav graph wiring (Splash → Login/Register → Home) |
| `res/values/strings.xml` | `google_web_client_id` placeholder |
