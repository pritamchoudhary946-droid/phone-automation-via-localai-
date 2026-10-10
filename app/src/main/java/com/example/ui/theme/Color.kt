package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// ── Jarvis premium palette (plan 8.4) ────────────────────────────────────────
// A deep, glassy "sci-fi HUD" identity: near-black backgrounds, a cyan/azure core
// glow with a violet secondary, and warm accents for status. Names are kept for
// source compatibility with every existing screen.

// Brand / accents
val CyanPrimary = Color(0xFF29D4FF)      // Jarvis core cyan
val CyanPrimaryDark = Color(0xFF0A84FF)  // primary action on light surfaces
val ElectricBlue = Color(0xFF4CC2FF)     // informational / executing
val IndigoAccent = Color(0xFF8B7CFF)     // secondary accent (violet)
val MintSuccess = Color(0xFF34E5B0)      // success / ready
val AmberWarning = Color(0xFFFFB020)     // warning / planning
val CrimsonDanger = Color(0xFFFF5A72)    // danger / stop

// Dark surfaces (the app now defaults to this — the Jarvis aesthetic).
val DarkBackground = Color(0xFF070A14)
val DarkSurface = Color(0xFF101527)
val DarkSurfaceVariant = Color(0xFF1B2238)
val DarkSurfaceBorder = Color(0xFF2C3654)

val DarkTextPrimary = Color(0xFFEAF0FF)
val DarkTextSecondary = Color(0xFFA6B0CE)
val DarkTextTertiary = Color(0xFF6E7899)

// Jarvis HUD extras
val JarvisGlow = Color(0x6629D4FF)       // halo around the status orb
val JarvisDeep = Color(0xFF0B1020)       // card gradient end

// Light surfaces (kept for the optional daylight theme).
val LightBackground = Color(0xFFF6F7FC)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFEDEFF7)
val LightSurfaceBorder = Color(0xFFE1E4F0)

val TextPrimary = Color(0xFF1B1C33)
val TextSecondary = Color(0xFF5A5F78)
val TextTertiary = Color(0xFF9AA0B6)
