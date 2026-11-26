package com.nouralwelayah.clock.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import java.util.Locale

/**
 * Dynamic Jetpack Compose localization and RTL layout-direction engine for the Nour Alwelayah appliance.
 *
 * Updates [LocalConfiguration], wraps [LocalContext] with a localized [Configuration] context
 * (whilst preserving the underlying [android.app.Activity] base context for window/permission lookups),
 * and forces [LocalLayoutDirection] (`Rtl` for Arabic `"ar"`, `Ltr` for English `"en"`) instantaneously
 * without requiring an Android Activity restart.
 */
@Composable
fun NourAlwelayahLocaleProvider(
    languageCode: String,
    content: @Composable () -> Unit,
) {
    val normalizedLang = if (languageCode.trim().lowercase(Locale.ROOT) == "ar") "ar" else "en"
    val baseContext = LocalContext.current
    val baseConfiguration = LocalConfiguration.current

    val targetLocale = remember(normalizedLang) {
        Locale(normalizedLang).also { Locale.setDefault(it) }
    }

    val localizedConfiguration = remember(baseConfiguration, targetLocale) {
        Configuration(baseConfiguration).apply {
            setLocale(targetLocale)
            setLayoutDirection(targetLocale)
        }
    }

    val localizedContext = remember(baseContext, localizedConfiguration) {
        val configCtx = baseContext.createConfigurationContext(localizedConfiguration)
        // Preserve baseContext as the ContextWrapper base so (LocalContext.current as? Activity) still succeeds
        object : ContextWrapper(baseContext) {
            override fun getResources() = configCtx.resources
            override fun getAssets() = configCtx.assets
        }
    }

    val layoutDirection = if (normalizedLang == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr

    CompositionLocalProvider(
        LocalConfiguration provides localizedConfiguration,
        LocalContext provides localizedContext,
        LocalLayoutDirection provides layoutDirection,
        content = content,
    )
}

/** Returns true when the active Compose tree is in Arabic (RTL) mode. */
@Composable
fun isArabic(): Boolean = LocalLayoutDirection.current == LayoutDirection.Rtl

/** Instant bilingual string selector reacting to [NourAlwelayahLocaleProvider]. */
@Composable
fun tr(en: String, ar: String): String =
    if (LocalLayoutDirection.current == LayoutDirection.Rtl) ar else en

/** Localized display label for each [com.nouralwelayah.clock.core.Prayer] entry. */
@Composable
fun com.nouralwelayah.clock.core.Prayer.localizedLabel(): String =
    if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
        when (this) {
            com.nouralwelayah.clock.core.Prayer.FAJR -> "الفجر"
            com.nouralwelayah.clock.core.Prayer.SUNRISE -> "الشروق"
            com.nouralwelayah.clock.core.Prayer.DHUHR -> "الظهر"
            com.nouralwelayah.clock.core.Prayer.SUNSET -> "الغروب"
            com.nouralwelayah.clock.core.Prayer.MAGHRIB -> "المغرب"
        }
    } else {
        label
    }

