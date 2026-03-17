# SpecTalk — Brand & Design Guide

## Mascot: Gervis

Gervis is the AI assistant inside SpecTalk. The mascot is a retro condenser microphone with
personality — metallic red body, gold capsule, black glasses, and a pulse waveform on the chest.

**Source files:** `android/media/MetaJarvisIcon.png`, `android/media/JarvisAPP.png`

- Used as the launcher icon (all mipmap density folders)
- Displayed on SplashScreen (`R.drawable.gervis_icon`, 120dp)
- Never call the mascot "SpecTalk" — the product is SpecTalk, the assistant is Gervis

---

## Color Palette

Derived directly from the Gervis mascot image. All tokens are defined in `ui/theme/Color.kt`.

### Primary — Metallic Red (mic body)

| Token | Hex | Usage |
|-------|-----|-------|
| `GervisRed10` | `#3B0000` | Deepest red, container on-colors |
| `GervisRed20` | `#5F0000` | onPrimary dark theme |
| `GervisRed30` | `#8B0000` | primaryContainer dark theme |
| `GervisRed40` | `#B71C1C` | **Primary — light theme** |
| `GervisRed50` | `#C62828` | Metallic highlight |
| `GervisRed80` | `#EF9A9A` | **Primary — dark theme** |
| `GervisRed90` | `#FFCDD2` | primaryContainer light theme |
| `GervisRed95` | `#FFEBEE` | Subtle tint backgrounds |

### Secondary — Metallic Gold (capsule / accents)

| Token | Hex | Usage |
|-------|-----|-------|
| `GervisGold10` | `#2C1A00` | Deepest gold |
| `GervisGold20` | `#4A2E00` | onSecondary dark theme |
| `GervisGold30` | `#6B4300` | secondaryContainer dark theme |
| `GervisGold40` | `#9A6B00` | **Secondary — light theme** |
| `GervisGold50` | `#C89A00` | Core brand gold |
| `GervisGold60` | `#D4A80E` | Metallic shimmer |
| `GervisGold80` | `#FFD966` | **Secondary — dark theme** |
| `GervisGold90` | `#FFEFA0` | secondaryContainer light theme |

### Neutral Surfaces — Warm-toned (not cold grey)

| Token | Hex | Theme | Usage |
|-------|-----|-------|-------|
| `Obsidian` | `#0C0808` | Dark | Background |
| `Carbon` | `#1A1212` | Dark | Surface / cards |
| `Charcoal` | `#2C1F1F` | Dark | Surface variant |
| `Ash` | `#3D2E2E` | Dark | Outline / dividers |
| `Cream` | `#FFF8F5` | Light | Background |
| `Linen` | `#FFF0EB` | Light | Surface / cards |
| `Silk` | `#EDE0DC` | Light | Surface variant |
| `Stone` | `#9E8986` | Light | Outline |

---

## Color Scheme (Material 3)

Defined in `ui/theme/Theme.kt`. Two schemes: `DarkColorScheme` and `LightColorScheme`.

### Dark theme (default / preferred)
```
background  = Obsidian   (#0C0808)
surface     = Carbon     (#1A1212)
primary     = GervisRed80  (soft red on dark)
secondary   = GervisGold80 (bright gold on dark)
```

### Light theme
```
background  = Cream      (#FFF8F5)
surface     = Linen      (#FFF0EB)
primary     = GervisRed40  (metallic red)
secondary   = GervisGold40 (deep gold)
```

Both themes use `isSystemInDarkTheme()` — the app follows the device setting automatically.

---

## Typography

Defined in `ui/theme/Type.kt`. Uses the system default font (Roboto on Android).

| Style | Size | Weight | Use |
|-------|------|--------|-----|
| `headlineLarge` | 32sp | SemiBold | App name on splash |
| `headlineMedium` | 28sp | SemiBold | Screen titles |
| `titleLarge` | 22sp | Medium | Section headers |
| `bodyLarge` | 16sp | Normal | Body text |
| `bodyMedium` | 14sp | Normal | Secondary text |
| `labelSmall` | 11sp | Medium | Captions, tags |

Apply `letterSpacing = 3.sp` on uppercase labels (e.g. "Powered by Gervis") for an elegant feel.

---

## Launcher Icon

The adaptive icon uses:
- **Background:** `drawable/ic_launcher_background.xml` — white (`#FFFFFF`) matching the PNG bg
- **Foreground:** `drawable/ic_launcher_foreground_padded.xml` — the Gervis PNG with 20dp inset
  on all sides so the content stays inside the adaptive icon safe zone (center 72dp of 108dp canvas)

```
mipmap-anydpi-v26/ic_launcher.xml       ← adaptive icon (API 26+)
mipmap-anydpi-v26/ic_launcher_round.xml ← round variant
mipmap-xxxhdpi/ic_launcher.png          ← fallback PNG (all densities)
drawable/gervis_icon.png                ← used by Compose painterResource()
```

> Do NOT use `R.mipmap.ic_launcher` with Compose `painterResource()` — adaptive icon XMLs are
> not supported. Always use `R.drawable.gervis_icon` in Compose.

---

## Design Principles

1. **Elegant & premium** — warm dark backgrounds, not cold grey. Red + gold feel luxurious.
2. **Dark-first** — the dark theme is the primary design. Light theme follows the same language.
3. **Transparent status bar** — edge-to-edge content. Set in `Theme.kt`.
4. **Spacing** — 32dp horizontal padding on auth screens. 24dp between major sections.
5. **Buttons** — 52dp height, full width for primary actions. Outlined style for secondary/Google.
