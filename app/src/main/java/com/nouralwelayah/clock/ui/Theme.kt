package com.nouralwelayah.clock.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Nour Alwelayah Appliance Brand Palette
val C_BG = Color(0xFF0c1823)       // Primary brand dark navy (#0c1823)
val C_CARD = Color(0xFF142433)     // Mathematically elevated navy card surface (~6% white mix over #0c1823)
val C_ACCENT = Color(0xFFcc9c56)   // Primary brand gold accent (#cc9c56)
val C_GOLD = Color(0xFFcc9c56)     // Brand gold (#cc9c56)
val C_TEXT = Color(0xFFF2F5F8)     // High-contrast accessible text (>14:1 contrast on #0c1823)
val C_DIM = Color(0xFF94A7B8)      // Accessible secondary text (>5.5:1 WCAG AA contrast on #0c1823)
val C_GREY = Color(0xFF223648)     // Elevated navy-slate for secondary controls and borders
val C_CLOCK = Color(0xFFF8FAFC)    // Crisp main clock digits
val C_SECONDS = Color(0xFFcc9c56)  // Brand gold seconds indicator

private val scheme = darkColorScheme(
    primary = C_ACCENT,
    onPrimary = Color(0xFF0c1823),
    secondary = C_GOLD,
    onSecondary = Color(0xFF0c1823),
    background = C_BG,
    onBackground = C_TEXT,
    surface = C_BG,
    onSurface = C_TEXT,
    surfaceVariant = C_CARD,
    onSurfaceVariant = C_DIM,
    surfaceContainer = C_CARD,
    surfaceContainerHigh = C_CARD,
    surfaceContainerHighest = C_GREY,
    surfaceContainerLow = C_CARD,
    surfaceContainerLowest = C_BG,
    outline = C_GREY,
    outlineVariant = C_GREY,
)

@Composable
fun PrayerClockTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)
