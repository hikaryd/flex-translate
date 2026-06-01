# Aquacard Design Tokens — Flex Translate

Single source of truth for the Flex Translate dark design system, ported from
the aquacard reference. The **Android Compose values are canonical**. iOS uses
the **same** canonical hex values converted to sRGB fractions (no `≈`
approximations).

- Android: `apps/android/app/src/main/java/dev/flextranslate/ui/theme/`
  (`Color.kt`, `Type.kt`, `Theme.kt`)
- iOS: `apps/ios/FlexTranslate/Sources/FlexTranslate/Theme.swift`

---

## Colors

| Token | Hex | Role |
|---|---|---|
| Bg | `#0E0F11` | App background / window background |
| Surface | `#16181C` | Base surface (Material `surface`) |
| SurfaceElevated / surfaceVariant | `#1E2126` | Raised surface (Material `surfaceVariant`) |
| SurfaceHighest | `#24272D` | Top layer surface |
| Outline | `#2A2E35` | Borders / dividers (Material `outline`) |
| OutlineVariant | `#232730` | Subtle dividers (Material `outlineVariant`) |
| TextPrimary | `#E6E8EB` | Primary text (`onSurface` / `onBackground`) |
| TextSecondary | `#9BA1A8` | Secondary text (`onSurfaceVariant`) |
| Accent | `#7C9CFF` | Single accent — cool indigo (`primary`/`secondary`/`tertiary`) |
| OnAccent | `#0B1020` | Foreground on accent (`onPrimary`) |
| AccentContainer | `#1B2233` | Accent container (`primaryContainer`/`secondaryContainer`) |
| OnAccentContainer | `#C9D5FF` | Foreground on accent container |
| Error | `#FF6B6B` | Error (`error`) |
| ErrorContainer | `#2A1A1C` | Error container |
| OnErrorContainer | `#FFC2C2` | Foreground on error container |

### Semantic palette (badges: language / tier / confidence — used in later workstreams)

| Token | Hex |
|---|---|
| green | `#22C55E` |
| amber | `#F59E0B` |
| red | `#EF4444` |
| purple | `#A855F7` |
| pink | `#E879F9` |

### iOS sRGB conversion (canonical, exact channel / 255)

| iOS token | Source hex | red | green | blue |
|---|---|---|---|---|
| background | `#0E0F11` | 14/255 | 15/255 | 17/255 |
| surface | `#16181C` | 22/255 | 24/255 | 28/255 |
| elevated | `#1E2126` | 30/255 | 33/255 | 38/255 |
| primary | `#7C9CFF` | 124/255 | 156/255 | 255/255 |
| secondary | `#C9D5FF` | 201/255 | 213/255 | 255/255 |
| text | `#E6E8EB` | 230/255 | 232/255 | 235/255 |
| mutedText | `#9BA1A8` | 155/255 | 161/255 | 168/255 |
| danger | `#FF6B6B` | 255/255 | 107/255 | 107/255 |

---

## Typography

System font only. No custom faces. `FontFamily.Monospace` is reserved for
data / numeric readouts and applied at call sites, not in the base scale.

| Style | Weight | Size | Letter spacing |
|---|---|---|---|
| titleLarge | SemiBold | 22sp | — |
| titleMedium | SemiBold | 16sp | — |
| bodyMedium | Normal | 14sp | — |
| bodySmall | Normal | 13sp | — |
| labelLarge | Medium | 14sp | — |
| labelMedium | Medium | 12sp | 0.4sp |
| labelSmall | Medium | 11sp | 0.8sp |

---

## Radii

| Element | Radius |
|---|---|
| Badges | 4–5dp |
| Thumbnails | 8dp |
| Rows / panels | 10–12dp |
| Large cards | 16–20dp |
| iOS card (`cardRadius`) | 18 |

---

## Elevation

**None.** Depth is expressed through layered surfaces only
(Bg → Surface → SurfaceElevated → SurfaceHighest). No drop shadows.

---

## Theme rules

- **Dark theme only.** No light variant.
- **Dynamic color (Material You) OFF.** Fixed `darkColorScheme`.

---

## Banned checklist

- [ ] No light theme / light-mode variant.
- [ ] No gradients.
- [ ] No shadows or Material elevation overlays — layered surfaces only.
- [ ] No Material You / dynamic color.
- [ ] No custom fonts (system font only; monospace reserved for data).
- [ ] No `≈` approximations on iOS — use exact `channel / 255` from canonical hex.
