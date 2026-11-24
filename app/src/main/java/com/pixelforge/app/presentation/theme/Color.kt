package com.pixelforge.app.presentation.theme

import androidx.compose.ui.graphics.Color

// ============================================================
// PixelForge Darkroom Palette — Indie Photo Lab System
// Warm print-black grounds, fibre-white ink, hairline rules,
// and scarce safelight amber for primary control & selection.
// ============================================================

// Grounds & Stepped Surfaces (depth via lightness, zero drop-shadow glow)
val DarkBackground = Color(0xFF11100F)       // Print-black ground
val DarkSurface = Color(0xFF171614)          // Inset photo well
val DarkSurfaceVariant = Color(0xFF1F1D1A)   // Panel surface
val DarkSurfaceElevated = Color(0xFF272420)  // Elevated interactive surface

// Ink Hierarchy
val TextPrimary = Color(0xFFEAE5DC)          // Warm fibre-white
val TextSecondary = Color(0xFFB8B1A6)        // Muted paper ink
val TextTertiary = Color(0xFF968E83)         // Accessible subtle metadata ink (>=4.9:1 on dark surfaces)

// Primary — Safelight Amber (primary actions, active selection, loupe handle)
val PrimaryDark = Color(0xFFE59A3C)
val PrimaryLight = Color(0xFFF0B465)
val PrimaryContainer = Color(0xFF342410)
val OnPrimary = Color(0xFF14110C)
val OnPrimaryContainer = Color(0xFFF6D29E)

// Secondary — Cool Instrument Slate (hardware telemetry & projections)
val SecondaryDark = Color(0xFF89A8C7)
val SecondaryLight = Color(0xFFA7C1DB)
val SecondaryContainer = Color(0xFF1C2630)
val OnSecondary = Color(0xFF0E141B)
val OnSecondaryContainer = Color(0xFFCDE0F2)

// Tertiary — Warm Copper
val TertiaryDark = Color(0xFFD6856C)
val TertiaryContainer = Color(0xFF382019)
val OnTertiary = Color(0xFF1D0F0B)

// Status (diff & processing states only)
val SuccessGreen = Color(0xFF5FB886)
val WarningAmber = Color(0xFFE59A3C)
val ErrorRed = Color(0xFFE06358)
val InfoBlue = Color(0xFF89A8C7)

// Hairline Rules
val OutlineDark = Color(0xFF2F2C27)          // 1.dp panel & row rules
val OutlineMedium = Color(0xFF47423A)        // Focused / active rule

// Light variants (for future light-room mode)
val LightBackground = Color(0xFFF4F1EB)
val LightSurface = Color(0xFFFFFFFF)
val PrimaryLightTheme = Color(0xFFB36B16)
