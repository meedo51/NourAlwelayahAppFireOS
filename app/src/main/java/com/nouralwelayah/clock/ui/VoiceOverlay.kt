package com.nouralwelayah.clock.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.nouralwelayah.clock.R
import com.nouralwelayah.clock.ui.p4.LocalP4
import com.nouralwelayah.clock.voice.KbRetrievalOutcome
import com.nouralwelayah.clock.voice.TextNormalizer
import com.nouralwelayah.clock.voice.VoiceCorrectionSuggestion
import com.nouralwelayah.clock.voice.VoiceUiState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Subtle, accessible Voice Assistant microphone button for the main clock/appliance chrome.
 *
 * Strictly adheres to the Nour Alwelayah Material 3 design tokens ([C_BG], [C_CARD], [C_GOLD])
 * with a minimum 48.dp x 48.dp touch target and meaningful TalkBack content description.
 */
@Composable
fun VoiceMicButton(
    onPicture: Boolean,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    wakeWordListening: Boolean = false,
) {
    val s4 = LocalP4.current
    val baseDesc = stringResource(R.string.voice_mic_content_description)
    val wakeDesc = stringResource(R.string.voice_listening_wake_word)
    val desc = if (wakeWordListening) "$baseDesc • $wakeDesc" else baseDesc
    val shape = RoundedCornerShape(10.dp)
    val bgColor = when {
        isActive -> C_GOLD.copy(alpha = 0.22f)
        onPicture -> C_CARD.copy(alpha = 0.78f)
        else -> C_CARD
    }
    val borderColor = when {
        isActive -> C_GOLD
        wakeWordListening -> C_GOLD.copy(alpha = 0.65f)
        else -> C_GOLD.copy(alpha = 0.42f)
    }
    val iconColor = if (isActive) C_GOLD else if (onPicture) C_TEXT else C_GOLD

    with(s4) {
        Surface(
            onClick = onClick,
            shape = shape,
            color = bgColor,
            border = BorderStroke(if (isActive) 1.5.dp else 1.dp, borderColor),
            modifier = modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .semantics {
                    contentDescription = desc
                    role = Role.Button
                }
                .testTag("clock_voice_mic_button"),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.p, vertical = 8.p),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.p),
            ) {
                if (wakeWordListening) {
                    val infiniteTransition = rememberInfiniteTransition(label = "WakeWordDotPulse")
                    val pulseAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.45f,
                        targetValue = 1.0f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "WakeWordDotAlpha",
                    )
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(C_GOLD.copy(alpha = pulseAlpha))
                            .border(1.dp, C_BG.copy(alpha = 0.75f), CircleShape)
                            .semantics {
                                contentDescription = wakeDesc
                            }
                            .testTag("clock_wake_word_indicator_dot"),
                    )
                }
                MicrophoneGlyphCanvas(
                    tint = iconColor,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/**
 * Translucent BrandNavy modal overlay for the 1280 x 800 landscape Nour Alwelayah Voice Assistant.
 *
 * Key guarantees:
 * - Strictly uses BrandNavy ([C_BG]), BrandCard ([C_CARD]), BrandGold ([C_GOLD]), [C_TEXT], and [C_DIM].
 * - **Bilingual RTL Sync**: When displaying a deterministic response in English while the tablet UI is in
 *   Arabic (or vice versa), [LocalLayoutDirection] is scoped **ONLY** to the response content box —
 *   never mutating or disturbing the global `AppSettings.appLanguage` RTL state.
 * - Immediately hides on Azan/Alarm preemption (`isAlertActive == true`) or when [state] returns to
 *   [VoiceUiState.Idle] (including after the 3-second post-TTS auto-dismiss window).
 */
@Composable
fun VoiceOverlay(
    state: VoiceUiState,
    isAlertActive: Boolean,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onSelectSuggestion: (VoiceCorrectionSuggestion, String, String) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
) {
    val visible = !isAlertActive && state !is VoiceUiState.Idle
    var lastVisibleState by remember { mutableStateOf<VoiceUiState>(VoiceUiState.Idle) }
    if (visible) {
        lastVisibleState = state
    }

    BackHandler(enabled = visible) {
        if (state is VoiceUiState.Listening || state is VoiceUiState.Processing) {
            onCancel()
        } else {
            onDismiss()
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(220)) + scaleIn(
            initialScale = 0.96f,
            animationSpec = tween(240, easing = FastOutSlowInEasing),
        ),
        exit = fadeOut(animationSpec = tween(200)) + scaleOut(
            targetScale = 0.97f,
            animationSpec = tween(200),
        ),
        modifier = modifier.fillMaxSize(),
    ) {
        val displayedState = if (visible) state else lastVisibleState
        val dismissOnScrimTap = displayedState is VoiceUiState.Speaking || displayedState is VoiceUiState.Error

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(C_BG.copy(alpha = 0.88f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        if (dismissOnScrimTap) onDismiss()
                    },
                )
                .testTag("voice_overlay_root"),
            contentAlignment = Alignment.Center,
        ) {
            // Soft radial BrandGold ambient halo for visual focus on 1280x800 landscape
            Canvas(modifier = Modifier.fillMaxSize()) {
                val radius = minOf(size.width, size.height) * 0.52f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            C_GOLD.copy(alpha = 0.14f),
                            C_GOLD.copy(alpha = 0.04f),
                            Color.Transparent,
                        ),
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                    center = center,
                )
            }

            AnimatedContent(
                targetState = displayedState,
                contentKey = { target ->
                    when (target) {
                        is VoiceUiState.Listening -> "listening"
                        is VoiceUiState.Processing -> "processing"
                        is VoiceUiState.Speaking -> "speaking"
                        is VoiceUiState.Error -> "error"
                        VoiceUiState.Idle -> "idle"
                    }
                },
                transitionSpec = {
                    (fadeIn(tween(180)) + scaleIn(initialScale = 0.97f, animationSpec = tween(200)))
                        .togetherWith(fadeOut(tween(140)))
                },
                label = "VoiceOverlayStateContent",
            ) { current ->
                when (current) {
                    is VoiceUiState.Listening -> ListeningOverlayCard(
                        amplitude = current.amplitude,
                        onCancel = onCancel,
                    )

                    is VoiceUiState.Processing -> ProcessingOverlayCard(
                        onCancel = onCancel,
                    )

                    is VoiceUiState.Speaking -> SpeakingOverlayCard(
                        state = current,
                        onDismiss = onDismiss,
                        onSelectSuggestion = onSelectSuggestion,
                    )

                    is VoiceUiState.Error -> ErrorOverlayCard(
                        message = current.msg,
                        onRetry = onRetry,
                        onDismiss = onDismiss,
                    )

                    VoiceUiState.Idle -> Spacer(Modifier.size(1.dp))
                }
            }
        }
    }
}

@Composable
private fun ListeningOverlayCard(
    amplitude: Float,
    onCancel: () -> Unit,
) {
    val s4 = LocalP4.current
    val smoothedAmp by animateFloatAsState(
        targetValue = amplitude.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 85, easing = FastOutSlowInEasing),
        label = "VoiceAmplitude",
    )
    val infiniteTransition = rememberInfiniteTransition(label = "VoiceListeningPulse")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "WavePhase",
    )
    val haloBreath by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "HaloBreath",
    )

    with(s4) {
        Surface(
            shape = RoundedCornerShape(24.p),
            color = C_CARD.copy(alpha = 0.96f),
            border = BorderStroke(1.5.dp, C_GOLD.copy(alpha = 0.55f)),
            shadowElevation = 16.dp,
            modifier = Modifier
                .widthIn(min = 420.dp, max = 660.dp)
                .padding(horizontal = 32.p)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .testTag("voice_overlay_listening_card"),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 36.p, vertical = 32.p),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.p),
            ) {
                // BrandGold Audio-Reactive Pulse Rings + Central Mic Medallion
                Box(
                    modifier = Modifier.size(156.p),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val minDim = minOf(size.width, size.height)
                        val baseR = minDim * 0.28f
                        val outerExpansion = (minDim * 0.21f) * (0.30f + 0.70f * smoothedAmp) +
                            (minDim * 0.04f) * haloBreath
                        val midExpansion = (minDim * 0.13f) * (0.25f + 0.75f * smoothedAmp)

                        // Outer reactive glow ring
                        drawCircle(
                            color = C_GOLD.copy(alpha = 0.14f + 0.18f * smoothedAmp),
                            radius = baseR + outerExpansion,
                            center = center,
                        )
                        drawCircle(
                            color = C_GOLD.copy(alpha = 0.32f + 0.25f * smoothedAmp),
                            radius = baseR + outerExpansion,
                            center = center,
                            style = Stroke(width = 2.dp.toPx()),
                        )

                        // Middle reactive ring
                        drawCircle(
                            color = C_GOLD.copy(alpha = 0.22f + 0.22f * smoothedAmp),
                            radius = baseR + midExpansion,
                            center = center,
                        )

                        // Core medallion fill & border
                        drawCircle(
                            color = C_BG,
                            radius = baseR,
                            center = center,
                        )
                        drawCircle(
                            color = C_GOLD,
                            radius = baseR,
                            center = center,
                            style = Stroke(width = 2.5.dp.toPx()),
                        )
                    }

                    MicrophoneGlyphCanvas(
                        tint = C_GOLD,
                        modifier = Modifier.size(40.p),
                    )
                }

                // Symmetric Audio-Reactive BrandGold Waveform Visualizer
                AudioReactiveWaveform(
                    amplitude = smoothedAmp,
                    phase = phase,
                    modifier = Modifier
                        .fillMaxWidth(0.76f)
                        .height(44.p),
                )

                Text(
                    text = stringResource(R.string.voice_listening),
                    color = C_GOLD,
                    fontSize = 28.f,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )

                Text(
                    text = stringResource(R.string.voice_listening_hint),
                    color = C_DIM,
                    fontSize = 18.f,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(4.p))

                OutlinedButton(
                    onClick = onCancel,
                    shape = RoundedCornerShape(12.p),
                    border = BorderStroke(1.5.dp, C_GOLD.copy(alpha = 0.65f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = C_BG.copy(alpha = 0.65f),
                        contentColor = C_TEXT,
                    ),
                    contentPadding = PaddingValues(horizontal = 28.p, vertical = 12.p),
                    modifier = Modifier
                        .heightIn(min = 50.dp)
                        .testTag("voice_overlay_cancel_button"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = null,
                        tint = C_GOLD,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.p))
                    Text(
                        text = stringResource(R.string.voice_cancel),
                        fontSize = 20.f,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProcessingOverlayCard(
    onCancel: () -> Unit,
) {
    val s4 = LocalP4.current
    with(s4) {
        Surface(
            shape = RoundedCornerShape(22.p),
            color = C_CARD.copy(alpha = 0.96f),
            border = BorderStroke(1.5.dp, C_GOLD.copy(alpha = 0.48f)),
            shadowElevation = 14.dp,
            modifier = Modifier
                .widthIn(min = 360.dp, max = 520.dp)
                .padding(horizontal = 32.p)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .testTag("voice_overlay_processing_card"),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 36.p, vertical = 32.p),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.p),
            ) {
                Box(
                    modifier = Modifier
                        .size(72.p)
                        .background(C_BG, CircleShape)
                        .border(1.dp, C_GOLD.copy(alpha = 0.35f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = C_GOLD,
                        trackColor = C_GREY,
                        strokeWidth = 3.5.dp,
                        modifier = Modifier.size(40.p),
                    )
                }

                Text(
                    text = stringResource(R.string.voice_processing),
                    color = C_TEXT,
                    fontSize = 24.f,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )

                OutlinedButton(
                    onClick = onCancel,
                    shape = RoundedCornerShape(12.p),
                    border = BorderStroke(1.dp, C_GREY),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = C_BG.copy(alpha = 0.55f),
                        contentColor = C_DIM,
                    ),
                    contentPadding = PaddingValues(horizontal = 24.p, vertical = 10.p),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("voice_overlay_cancel_button"),
                ) {
                    Text(
                        text = stringResource(R.string.voice_cancel),
                        fontSize = 18.f,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeakingOverlayCard(
    state: VoiceUiState.Speaking,
    onDismiss: () -> Unit,
    onSelectSuggestion: (VoiceCorrectionSuggestion, String, String) -> Unit,
) {
    val s4 = LocalP4.current
    val isArabicResponse = state.lang.equals("ar", ignoreCase = true)
    // Bilingual RTL Sync: strictly scope layout direction to the response box without altering AppSettings.appLanguage
    val responseLayoutDirection = if (isArabicResponse) LayoutDirection.Rtl else LayoutDirection.Ltr
    val responseTextDirection = if (isArabicResponse) TextDirection.Rtl else TextDirection.Ltr

    // Smooth visual progress countdown once TTS audio finishes (3s standard, 6s when correction chips are shown)
    val dismissDurationMs = state.autoDismissMs.coerceIn(1_000L, 12_000L).toInt()
    val dismissProgress = remember(state.text, state.isTtsFinished, dismissDurationMs) { Animatable(1f) }
    LaunchedEffect(state.text, state.isTtsFinished, dismissDurationMs) {
        if (state.isTtsFinished) {
            dismissProgress.snapTo(1f)
            dismissProgress.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = dismissDurationMs, easing = LinearEasing),
            )
        } else {
            dismissProgress.snapTo(1f)
        }
    }

    with(s4) {
        Surface(
            shape = RoundedCornerShape(24.p),
            color = C_CARD,
            border = BorderStroke(1.5.dp, C_GOLD.copy(alpha = 0.58f)),
            shadowElevation = 18.dp,
            modifier = Modifier
                .widthIn(min = 520.dp, max = 820.dp)
                .padding(horizontal = 40.p, vertical = 28.p)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .testTag("voice_overlay_speaking_card"),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 30.p, vertical = 26.p),
                verticalArrangement = Arrangement.spacedBy(18.p),
            ) {
                // Header Row — follows global appliance UI language & LayoutDirection
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.p),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.p)
                                .background(C_BG, CircleShape)
                                .border(1.dp, C_GOLD.copy(alpha = 0.65f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (state.isTtsFinished) {
                                MicrophoneGlyphCanvas(
                                    tint = C_GOLD.copy(alpha = 0.85f),
                                    modifier = Modifier.size(18.p),
                                )
                            } else {
                                MiniSpeakingBars(modifier = Modifier.size(20.p))
                            }
                        }

                        Text(
                            text = if (state.isTtsFinished) {
                                stringResource(R.string.voice_done_closing)
                            } else {
                                stringResource(R.string.voice_speaking)
                            },
                            color = C_GOLD,
                            fontSize = 20.f,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    // Detected spoken language badge (+ subtle learned-phrase indicator if promoted)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.p),
                    ) {
                        if (state.matchSource == KbRetrievalOutcome.MATCH_SOURCE_LEARNED_PHRASE) {
                            Box(
                                modifier = Modifier
                                    .background(C_GOLD.copy(alpha = 0.16f), RoundedCornerShape(8.p))
                                    .border(1.dp, C_GOLD.copy(alpha = 0.55f), RoundedCornerShape(8.p))
                                    .padding(horizontal = 10.p, vertical = 5.p)
                                    .testTag("voice_overlay_learned_badge"),
                            ) {
                                Text(
                                    text = stringResource(R.string.voice_learned_badge),
                                    color = C_GOLD,
                                    fontSize = 14.f,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .background(C_BG, RoundedCornerShape(8.p))
                                .border(1.dp, C_GOLD.copy(alpha = 0.35f), RoundedCornerShape(8.p))
                                .padding(horizontal = 12.p, vertical = 5.p)
                                .testTag("voice_overlay_lang_badge"),
                        ) {
                            Text(
                                text = if (isArabicResponse) "العربية" else "English",
                                color = C_DIM,
                                fontSize = 15.f,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }

                // Scoped Bilingual RTL/LTR Response Box — NEVER mutates global AppSettings.appLanguage
                CompositionLocalProvider(LocalLayoutDirection provides responseLayoutDirection) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(C_BG.copy(alpha = 0.65f), RoundedCornerShape(16.p))
                            .border(1.dp, C_GREY, RoundedCornerShape(16.p))
                            .padding(horizontal = 24.p, vertical = 20.p)
                            .testTag("voice_overlay_response_box"),
                        verticalArrangement = Arrangement.spacedBy(14.p),
                    ) {
                        // Recognized Query Strip
                        if (state.query.isNotBlank()) {
                            val queryIsAr = remember(state.query) {
                                TextNormalizer.detectLanguage(state.query) == "ar"
                            }
                            val queryLayoutDir = if (queryIsAr) LayoutDirection.Rtl else LayoutDirection.Ltr
                            val queryTextDir = if (queryIsAr) TextDirection.Rtl else TextDirection.Ltr

                            CompositionLocalProvider(LocalLayoutDirection provides queryLayoutDir) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(C_CARD.copy(alpha = 0.75f), RoundedCornerShape(10.p))
                                        .padding(horizontal = 14.p, vertical = 10.p)
                                        .testTag("voice_overlay_recognized_query"),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.p),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(3.dp)
                                            .height(22.p)
                                            .background(C_GOLD, RoundedCornerShape(2.dp)),
                                    )
                                    Text(
                                        text = "“${state.query.trim()}”",
                                        color = C_DIM,
                                        fontSize = 18.f,
                                        fontWeight = FontWeight.Medium,
                                        style = TextStyle(
                                            textDirection = queryTextDir,
                                            textAlign = TextAlign.Start,
                                        ),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }

                        // Deterministic Spoken Response Text (scrollable for long Stage 2 Q&A answers)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.p)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            Text(
                                text = state.text,
                                color = C_TEXT,
                                fontSize = 28.f,
                                fontWeight = FontWeight.Medium,
                                lineHeight = 40.f,
                                style = TextStyle(
                                    textDirection = responseTextDirection,
                                    textAlign = TextAlign.Start,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("voice_overlay_response_text"),
                            )
                        }

                        // Subtle source_ref Footer Badge (Stage 2 Shia Islamic Q&A citation-ready)
                        if (state.sourceRef.isNotBlank()) {
                            val sourceLabel = if (isArabicResponse) "المصدر" else "Source"
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.p)
                                    .testTag("voice_overlay_source_ref_badge"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    modifier = Modifier
                                        .background(C_CARD, RoundedCornerShape(999.dp))
                                        .border(
                                            width = 1.dp,
                                            color = C_GOLD.copy(alpha = 0.40f),
                                            shape = RoundedCornerShape(999.dp),
                                        )
                                        .padding(horizontal = 14.p, vertical = 6.p),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.p),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.p)
                                            .clip(CircleShape)
                                            .background(C_GOLD),
                                    )
                                    Text(
                                        text = "$sourceLabel: ${state.sourceRef}",
                                        color = C_GOLD,
                                        fontSize = 15.f,
                                        fontWeight = FontWeight.Medium,
                                        style = TextStyle(
                                            textDirection = responseTextDirection,
                                            textAlign = TextAlign.Start,
                                        ),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }

                // One-Tap Correction Capture Chips when retrieval is medium/low confidence or unmatched
                if (state.suggestions.isNotEmpty()) {
                    CompositionLocalProvider(LocalLayoutDirection provides responseLayoutDirection) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(C_BG.copy(alpha = 0.55f), RoundedCornerShape(14.p))
                                .border(1.dp, C_GOLD.copy(alpha = 0.38f), RoundedCornerShape(14.p))
                                .padding(horizontal = 18.p, vertical = 14.p)
                                .testTag("voice_overlay_correction_section"),
                            verticalArrangement = Arrangement.spacedBy(10.p),
                        ) {
                            Text(
                                text = if (isArabicResponse) "هل تقصد:" else "Did you mean:",
                                color = C_GOLD,
                                fontSize = 17.f,
                                fontWeight = FontWeight.SemiBold,
                                style = TextStyle(
                                    textDirection = responseTextDirection,
                                    textAlign = TextAlign.Start,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(10.p),
                                verticalArrangement = Arrangement.spacedBy(10.p),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                state.suggestions.forEach { suggestion ->
                                    val chipLabel = suggestion.localizedLabel(isArabicResponse)
                                    Surface(
                                        onClick = {
                                            onSelectSuggestion(
                                                suggestion,
                                                state.query,
                                                state.normalizedQuery,
                                            )
                                        },
                                        shape = RoundedCornerShape(999.dp),
                                        color = C_CARD,
                                        border = BorderStroke(1.dp, C_GOLD.copy(alpha = 0.72f)),
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .semantics {
                                                contentDescription = chipLabel
                                                role = Role.Button
                                            }
                                            .testTag("voice_correction_chip_${suggestion.intentId}"),
                                    ) {
                                        Box(
                                            modifier = Modifier.padding(horizontal = 16.p, vertical = 10.p),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = chipLabel,
                                                color = C_TEXT,
                                                fontSize = 17.f,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Auto-dismiss progress bar once TTS finishes
                if (state.isTtsFinished) {
                    LinearProgressIndicator(
                        progress = { dismissProgress.value },
                        color = C_GOLD,
                        trackColor = C_GREY,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                    )
                }

                // Bottom Action Row — follows global appliance UI language & LayoutDirection
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(12.p),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = C_GOLD,
                            contentColor = C_BG,
                        ),
                        contentPadding = PaddingValues(horizontal = 24.p, vertical = 12.p),
                        modifier = Modifier
                            .heightIn(min = 50.dp)
                            .testTag("voice_overlay_dismiss_button"),
                    ) {
                        // Crisp stop square glyph
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(C_BG, RoundedCornerShape(2.dp)),
                        )
                        Spacer(Modifier.width(10.p))
                        Text(
                            text = if (state.isTtsFinished) {
                                stringResource(R.string.voice_dismiss)
                            } else {
                                stringResource(R.string.voice_stop_dismiss)
                            },
                            fontSize = 19.f,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorOverlayCard(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val s4 = LocalP4.current
    val msgIsAr = remember(message) { TextNormalizer.detectLanguage(message) == "ar" }
    val msgLayoutDir = if (msgIsAr) LayoutDirection.Rtl else LayoutDirection.Ltr
    val msgTextDir = if (msgIsAr) TextDirection.Rtl else TextDirection.Ltr

    with(s4) {
        Surface(
            shape = RoundedCornerShape(22.p),
            color = C_CARD,
            border = BorderStroke(1.5.dp, C_GOLD.copy(alpha = 0.50f)),
            shadowElevation = 16.dp,
            modifier = Modifier
                .widthIn(min = 420.dp, max = 660.dp)
                .padding(horizontal = 32.p)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .testTag("voice_overlay_error_card"),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 30.p, vertical = 26.p),
                verticalArrangement = Arrangement.spacedBy(16.p),
            ) {
                Text(
                    text = stringResource(R.string.voice_error_title),
                    color = C_GOLD,
                    fontSize = 22.f,
                    fontWeight = FontWeight.Bold,
                )

                CompositionLocalProvider(LocalLayoutDirection provides msgLayoutDir) {
                    Text(
                        text = message,
                        color = C_TEXT,
                        fontSize = 20.f,
                        lineHeight = 28.f,
                        style = TextStyle(
                            textDirection = msgTextDir,
                            textAlign = TextAlign.Start,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.p, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(12.p),
                        border = BorderStroke(1.dp, C_GREY),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = C_BG.copy(alpha = 0.6f),
                            contentColor = C_TEXT,
                        ),
                        contentPadding = PaddingValues(horizontal = 22.p, vertical = 12.p),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("voice_overlay_dismiss_button"),
                    ) {
                        Text(
                            text = stringResource(R.string.voice_dismiss),
                            fontSize = 18.f,
                            fontWeight = FontWeight.Medium,
                        )
                    }

                    Button(
                        onClick = onRetry,
                        shape = RoundedCornerShape(12.p),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = C_GOLD,
                            contentColor = C_BG,
                        ),
                        contentPadding = PaddingValues(horizontal = 22.p, vertical = 12.p),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("voice_overlay_retry_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null,
                            tint = C_BG,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.p))
                        Text(
                            text = stringResource(R.string.voice_retry),
                            fontSize = 18.f,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Symmetric BrandGold multi-bar audio-reactive waveform driven by [amplitude] (`0f..1f`)
 * and ambient [phase].
 */
@Composable
private fun AudioReactiveWaveform(
    amplitude: Float,
    phase: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val barCount = 13
        val centerIndex = (barCount - 1) / 2f
        val barWidth = (size.width / (barCount * 2.15f)).coerceIn(3.dp.toPx(), 8.dp.toPx())
        val gap = (size.width - barWidth * barCount) / (barCount - 1).coerceAtLeast(1)
        val minBarH = size.height * 0.18f
        val maxBarH = size.height * 0.96f

        for (i in 0 until barCount) {
            val distNorm = abs(i - centerIndex) / centerIndex // 0 at center, 1 at edges
            val envelope = 1f - 0.55f * distNorm
            val wave = 0.5f + 0.5f * sin(phase + i * 0.62f)
            val energy = (0.16f + 0.84f * amplitude) * envelope * (0.45f + 0.55f * wave)
            val barH = (minBarH + (maxBarH - minBarH) * energy).coerceIn(minBarH, maxBarH)
            val x = i * (barWidth + gap)
            val y = (size.height - barH) / 2f
            val alpha = (0.45f + 0.55f * envelope * (0.5f + 0.5f * amplitude)).coerceIn(0.35f, 1f)

            drawRoundRect(
                color = C_GOLD.copy(alpha = alpha),
                topLeft = Offset(x, y),
                size = Size(barWidth, barH),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

/**
 * Compact 3-bar animated BrandGold equalizer shown in the Speaking header while TTS audio plays.
 */
@Composable
private fun MiniSpeakingBars(modifier: Modifier = Modifier) {
    val infinite = rememberInfiniteTransition(label = "MiniSpeakingBars")
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "MiniBarPhase",
    )
    Canvas(modifier = modifier) {
        val bars = 3
        val barW = size.width * 0.22f
        val spacing = (size.width - bars * barW) / (bars + 1)
        for (i in 0 until bars) {
            val factor = 0.30f + 0.65f * (0.5f + 0.5f * sin(phase + i * 1.4f))
            val h = size.height * factor
            val x = spacing + i * (barW + spacing)
            val y = (size.height - h) / 2f
            drawRoundRect(
                color = C_GOLD,
                topLeft = Offset(x, y),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f),
            )
        }
    }
}

/**
 * Crisp, scalable vector microphone glyph drawn with Compose [Canvas] (avoids requiring
 * `material-icons-extended` on low-RAM white-label tablet APKs).
 */
@Composable
private fun MicrophoneGlyphCanvas(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokeW = (minOf(w, h) * 0.105f).coerceAtLeast(1.8f)

        // Capsule head
        val capW = w * 0.34f
        val capH = h * 0.46f
        val capLeft = (w - capW) / 2f
        val capTop = h * 0.08f
        drawRoundRect(
            color = tint,
            topLeft = Offset(capLeft, capTop),
            size = Size(capW, capH),
            cornerRadius = CornerRadius(capW / 2f, capW / 2f),
        )

        // U-shaped cradle arc
        val arcW = w * 0.58f
        val arcH = h * 0.42f
        val arcLeft = (w - arcW) / 2f
        val arcTop = h * 0.27f
        drawArc(
            color = tint,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(arcLeft, arcTop),
            size = Size(arcW, arcH),
            style = Stroke(width = strokeW, cap = StrokeCap.Round),
        )

        // Vertical stem
        val stemTop = arcTop + arcH
        val stemBottom = h * 0.88f
        drawLine(
            color = tint,
            start = Offset(w / 2f, stemTop),
            end = Offset(w / 2f, stemBottom),
            strokeWidth = strokeW,
            cap = StrokeCap.Round,
        )

        // Horizontal base foot
        drawLine(
            color = tint,
            start = Offset(w * 0.32f, stemBottom),
            end = Offset(w * 0.68f, stemBottom),
            strokeWidth = strokeW,
            cap = StrokeCap.Round,
        )
    }
}
