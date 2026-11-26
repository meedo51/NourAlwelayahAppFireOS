package com.nouralwelayah.clock.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.nouralwelayah.clock.data.AppSettings
import com.nouralwelayah.clock.data.Store
import com.nouralwelayah.clock.kiosk.KioskPolicy
import com.nouralwelayah.clock.voice.DispatchResult
import com.nouralwelayah.clock.voice.KbMatch
import com.nouralwelayah.clock.voice.KbSyncState
import com.nouralwelayah.clock.voice.VoiceAssistantService
import com.nouralwelayah.clock.voice.VoiceLogExportFormat
import com.nouralwelayah.clock.voice.VoiceReadinessState
import com.nouralwelayah.clock.voice.VoiceUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val DIAG_EMERALD = Color(0xFF4CAF50)
private val DIAG_RED = Color(0xFFEF5350)

/**
 * Shared Bilingual Voice Assistant Settings, Production Diagnostics Panel, OTA KB Sync &
 * Independent Live Retrieval/Dispatcher Test Console.
 *
 * Displayed in both the Settings Tab (`SettingsTab.kt`) and the Technician Portal (`AdminDashboard.kt`).
 * Strictly adheres to the Nour Alwelayah Material 3 design system:
 * - BrandNavy (`#0C1823` / [C_BG])
 * - BrandCard (`#142433` / [C_CARD])
 * - BrandGold (`#CC9C56` / [C_GOLD])
 * - Full English (LTR) & Arabic (RTL) bilingual support.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceAssistantSection(
    s: AppSettings,
    modifier: Modifier = Modifier,
    onStatusMessage: ((String) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ar = isArabic()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val kbMeta by VoiceAssistantService.metadata.collectAsState()
    val modelManager = remember(ctx) { VoiceAssistantService.getModelManager(ctx) }
    val readinessState by modelManager.readinessState.collectAsState()
    val missingModels by modelManager.missingModelFiles.collectAsState()
    val kwsReady by modelManager.kwsReady.collectAsState()

    val otaSyncManager = remember(ctx) { VoiceAssistantService.getOtaSyncManager(ctx) }
    val kbSyncState by otaSyncManager.syncState.collectAsState()

    val voiceIoEngine = remember(ctx) { VoiceAssistantService.getVoiceIoEngine(ctx) }
    val voiceUiState by voiceIoEngine.uiState.collectAsState()
    val isWakeWordListening by voiceIoEngine.isWakeWordListening.collectAsState()

    val learningStore = remember(ctx) { VoiceAssistantService.getLearningStore(ctx) }
    val learningStats by learningStore.stats.collectAsState()
    var memoryActionBusy by remember { mutableStateOf(false) }

    var permCheckTick by remember { mutableIntStateOf(0) }
    var hasMicPermission by remember(permCheckTick, readinessState) {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var isKioskLocked by remember(permCheckTick) {
        mutableStateOf(KioskPolicy.isKioskActive(ctx))
    }

    var kbLoadedOnce by remember { mutableStateOf(false) }
    var syncStatusText by remember { mutableStateOf("") }

    var testQueryInput by remember { mutableStateOf("") }
    var queryBusy by remember { mutableStateOf(false) }
    var lastMatch by remember { mutableStateOf<KbMatch?>(null) }
    var lastDispatchResult by remember { mutableStateOf<DispatchResult?>(null) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permCheckTick++
        hasMicPermission = granted ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        modelManager.refreshReadinessAsync()
        val msg = if (hasMicPermission) {
            if (ar) "تم منح إذن الميكروفون (RECORD_AUDIO)." else "Microphone permission (RECORD_AUDIO) granted."
        } else {
            if (ar) "تم رفض إذن الميكروفون." else "Microphone permission denied."
        }
        syncStatusText = msg
        onStatusMessage?.invoke(msg)
    }

    val listenMicPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permCheckTick++
        hasMicPermission = granted
        modelManager.refreshReadinessAsync()
        if (granted) {
            voiceIoEngine.startListeningSession()
        } else {
            val msg = if (ar) "تم رفض إذن الميكروفون." else "Microphone permission denied."
            syncStatusText = msg
            onStatusMessage?.invoke(msg)
        }
    }

    LifecycleResumeEffect(ctx) {
        permCheckTick++
        hasMicPermission = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        isKioskLocked = KioskPolicy.isKioskActive(ctx)
        onPauseOrDispose { }
    }

    LaunchedEffect(Unit) {
        VoiceAssistantService.refreshMetadata(ctx)
        kbLoadedOnce = true
        modelManager.verifyAndPrepareModels()
        withContext(Dispatchers.IO) {
            learningStore.refreshStats()
        }
        permCheckTick++
    }

    /**
     * Executes the Test Query completely independently of the microphone or `.onnx` models
     * so `TextNormalizer` + SQLite `FTS5` + `VoiceCommandDispatcher` can always be verified.
     * If `speakResponse` is enabled and no Azan is active, also triggers TTS output.
     */
    fun runTestQuery(q: String) {
        val trimmed = q.trim()
        if (trimmed.isEmpty() || queryBusy) return
        keyboard?.hide()
        focusManager.clearFocus()
        queryBusy = true
        scope.launch {
            val (match, dispatchRes) = withContext(Dispatchers.IO) {
                VoiceAssistantService.testQuery(ctx, trimmed)
            }
            lastMatch = match
            lastDispatchResult = dispatchRes
            queryBusy = false

            // Also speak response through TTS if available and no Azan is active
            if (dispatchRes is DispatchResult.Success &&
                dispatchRes.spokenResponse.isNotBlank() &&
                !voiceIoEngine.isAzanOrAlarmActive()
            ) {
                voiceIoEngine.speakOrDispatchTextQuery(
                    rawQuery = trimmed,
                    speakResponse = true,
                )
            }
        }
    }

    fun triggerManualKbSync() {
        val isSyncing = kbSyncState is KbSyncState.Checking || kbSyncState is KbSyncState.Downloading
        if (isSyncing) return
        scope.launch {
            val res = VoiceAssistantService.syncKnowledgeBase(ctx)
            val msg = if (ar) res.messageAr else res.messageEn
            syncStatusText = msg
            onStatusMessage?.invoke(msg)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(C_CARD, RoundedCornerShape(14.dp))
            .border(1.dp, C_GOLD.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .padding(18.dp)
            .testTag("voice_assistant_section"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 1. Header + Enable/Disable Voice Assistant Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = tr(
                        "🎙️  Offline Voice Assistant & Production Diagnostics",
                        "🎙️  المساعد الصوتي الذكي ولوحة التشخيص الميداني"
                    ),
                    color = C_GOLD,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = tr(
                        "100% offline bilingual Arabic & English FTS5 retrieval engine + sherpa-onnx Voice I/O (zero LLM).",
                        "محرك أوامر واستعلامات ثنائي اللغة يعمل بالكامل بدون إنترنت (SQLite FTS5 + sherpa-onnx)."
                    ),
                    color = C_DIM,
                    fontSize = 13.sp,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = if (s.voiceAssistantEnabled) {
                        tr("Enabled", "مفعّل")
                    } else {
                        tr("Disabled", "معطّل")
                    },
                    color = if (s.voiceAssistantEnabled) C_GOLD else C_DIM,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Switch(
                    checked = s.voiceAssistantEnabled,
                    onCheckedChange = { enabled ->
                        Store.saveSettings(Store.settings.value.copy(voiceAssistantEnabled = enabled))
                        VoiceAssistantService.onVoiceSettingsChanged(ctx, Store.settings.value)
                        val status = if (enabled) {
                            if (ar) "تم تفعيل المساعد الصوتي." else "Voice Assistant enabled."
                        } else {
                            if (ar) "تم إيقاف المساعد الصوتي." else "Voice Assistant disabled."
                        }
                        syncStatusText = status
                        onStatusMessage?.invoke(status)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = C_BG,
                        checkedTrackColor = C_GOLD,
                    ),
                    modifier = Modifier.testTag("voice_assistant_enable_switch"),
                )
            }
        }

        // 1B. Recognition Mode Selector (Auto Bilingual | Arabic Priority | English Priority)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(C_BG, RoundedCornerShape(12.dp))
                .border(1.dp, C_GREY, RoundedCornerShape(12.dp))
                .padding(14.dp)
                .testTag("voice_recognition_mode_card"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = tr("Recognition Mode (Whisper STT)", "وضع التعرف على اللغة (Whisper STT)"),
                color = C_GOLD,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = tr(
                    "Default Auto mode handles both Arabic & English seamlessly. Lock a priority language if short commands occasionally mix up.",
                    "الوضع التلقائي يتعرف على العربية والإنجليزية معاً. يمكنك تثبيت لغة مفضلة لتقليل التداخل في الأوامر القصيرة."
                ),
                color = C_DIM,
                fontSize = 12.sp,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val modes = listOf(
                    Triple(
                        AppSettings.VOICE_MODE_AUTO,
                        tr("Auto (Bilingual)", "تلقائي (ثنائي اللغة)"),
                        "voice_recognition_mode_auto",
                    ),
                    Triple(
                        AppSettings.VOICE_MODE_AR,
                        tr("Arabic Priority", "أولوية العربية"),
                        "voice_recognition_mode_ar",
                    ),
                    Triple(
                        AppSettings.VOICE_MODE_EN,
                        tr("English Priority", "أولوية الإنجليزية"),
                        "voice_recognition_mode_en",
                    ),
                )
                modes.forEach { (modeKey, modeLabel, tag) ->
                    val selected = AppSettings.normalizeVoiceRecognitionMode(s.voiceRecognitionMode) == modeKey
                    OutlinedButton(
                        onClick = {
                            if (!selected) {
                                Store.saveSettings(Store.settings.value.copy(voiceRecognitionMode = modeKey))
                                VoiceAssistantService.onVoiceSettingsChanged(ctx, Store.settings.value)
                                val msg = if (ar) {
                                    "تم ضبط وضع التعرف على اللغة: $modeLabel"
                                } else {
                                    "Voice Recognition Mode set to: $modeLabel"
                                }
                                syncStatusText = msg
                                onStatusMessage?.invoke(msg)
                            }
                        },
                        border = BorderStroke(
                            width = if (selected) 1.5.dp else 1.dp,
                            color = if (selected) C_GOLD else C_GREY,
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (selected) C_GOLD.copy(alpha = 0.20f) else C_CARD,
                            contentColor = if (selected) C_GOLD else C_TEXT,
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag(tag),
                    ) {
                        Text(
                            text = if (selected) "✓  $modeLabel" else modeLabel,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        // 1B-2. Hands-Free Wake Word ("Welayah") & Sensitivity Controls (sherpa-onnx KeywordSpotter)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(C_BG, RoundedCornerShape(12.dp))
                .border(1.dp, C_GREY, RoundedCornerShape(12.dp))
                .padding(14.dp)
                .testTag("voice_wake_word_card"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = tr(
                                "Hands-Free Wake Word (\"Welayah\")",
                                "التفعيل الصوتي بكلمة التنبيه (\"ولاية\")"
                            ),
                            color = C_GOLD,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        val kwsBadgeText = when {
                            isWakeWordListening -> tr("Listening", "بانتظار كلمة: ولاية")
                            kwsReady -> tr("KWS Ready", "النموذج جاهز")
                            else -> tr("Not Installed", "غير مثبّت")
                        }
                        val kwsBadgeColor = when {
                            isWakeWordListening -> DIAG_EMERALD
                            kwsReady -> C_GOLD
                            else -> C_DIM
                        }
                        Box(
                            modifier = Modifier
                                .background(kwsBadgeColor.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
                                .border(1.dp, kwsBadgeColor.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                .testTag("voice_wake_word_status_badge"),
                        ) {
                            Text(
                                text = kwsBadgeText,
                                color = kwsBadgeColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    Text(
                        text = tr(
                            "Offline sherpa-onnx KeywordSpotter (voice_models/kws/). Automatically pauses during Azan/Alarms, Media playback, and TTS speech.",
                            "مكتشف الكلمات المفتاحية بدون إنترنت (voice_models/kws/). يتوقف تلقائياً أثناء الأذان والمنبهات وتشغيل الوسائط والنطق الصوتي."
                        ),
                        color = C_DIM,
                        fontSize = 12.sp,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = if (s.wakeWordEnabled) {
                            tr("On", "مفعّل")
                        } else {
                            tr("Off", "متوقف")
                        },
                        color = if (s.wakeWordEnabled) C_GOLD else C_DIM,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Switch(
                        checked = s.wakeWordEnabled,
                        onCheckedChange = { enabled ->
                            Store.saveSettings(Store.settings.value.copy(wakeWordEnabled = enabled))
                            VoiceAssistantService.onVoiceSettingsChanged(ctx, Store.settings.value)
                            val msg = if (enabled) {
                                if (ar) {
                                    "تم تفعيل الاستماع لكلمة التنبيه (\"ولاية\")."
                                } else {
                                    "Hands-Free Wake Word (\"Welayah\") enabled."
                                }
                            } else {
                                if (ar) {
                                    "تم إيقاف الاستماع لكلمة التنبيه (\"ولاية\")."
                                } else {
                                    "Hands-Free Wake Word (\"Welayah\") disabled."
                                }
                            }
                            syncStatusText = msg
                            onStatusMessage?.invoke(msg)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = C_BG,
                            checkedTrackColor = C_GOLD,
                        ),
                        modifier = Modifier.testTag("voice_wake_word_switch"),
                    )
                }
            }

            // Wake Word Sensitivity Slider (keywordsThreshold: 0.10f..0.60f)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val thresholdFormatted = String.format(Locale.US, "%.2f", s.wakeWordSensitivity)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = tr(
                            "Wake Word Sensitivity (Threshold: $thresholdFormatted)",
                            "حساسية كلمة التنبيه (العتبة: $thresholdFormatted)"
                        ),
                        color = C_TEXT,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = tr(
                            "0.10 = Most Sensitive • 0.60 = Fewest False Triggers",
                            "0.10 = حساسية أعلى • 0.60 = تقليل التفعيل الخاطئ"
                        ),
                        color = C_DIM,
                        fontSize = 11.sp,
                    )
                }
                Slider(
                    value = s.wakeWordSensitivity.coerceIn(
                        AppSettings.MIN_WAKE_WORD_SENSITIVITY,
                        AppSettings.MAX_WAKE_WORD_SENSITIVITY,
                    ),
                    onValueChange = { newVal ->
                        val rounded = (kotlin.math.round(newVal * 100f) / 100f).coerceIn(
                            AppSettings.MIN_WAKE_WORD_SENSITIVITY,
                            AppSettings.MAX_WAKE_WORD_SENSITIVITY,
                        )
                        if (kotlin.math.abs(rounded - s.wakeWordSensitivity) >= 0.005f) {
                            Store.saveSettings(Store.settings.value.copy(wakeWordSensitivity = rounded))
                        }
                    },
                    valueRange = AppSettings.MIN_WAKE_WORD_SENSITIVITY..AppSettings.MAX_WAKE_WORD_SENSITIVITY,
                    steps = 9, // 0.05 increments between 0.10 and 0.60
                    colors = SliderDefaults.colors(
                        thumbColor = C_GOLD,
                        activeTrackColor = C_GOLD,
                        inactiveTrackColor = C_GREY,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("voice_wake_word_sensitivity_slider"),
                )
            }
        }

        // 1C. Local Phrase Memory & Privacy Controls (Device-Private SQLite, Text-Only, Zero Audio)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(C_BG, RoundedCornerShape(12.dp))
                .border(1.dp, C_GREY, RoundedCornerShape(12.dp))
                .padding(14.dp)
                .testTag("voice_phrase_memory_card"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                    Text(
                        text = tr(
                            "Local Phrase Memory & Privacy Controls",
                            "ذاكرة العبارات المحلية وضوابط الخصوصية"
                        ),
                        color = C_GOLD,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = tr(
                            "Device-private SQLite • Text transcripts & intent IDs only (never stores audio, never auto-synced). Promotes after 2 taps; auto-prunes at ${learningStats.maxLearnedPhrases} entries or ${learningStats.expiryDays} days.",
                            "قاعدة بيانات خاصة بالجهاز فقط • نصوص ومعرّفات أوامر فقط (بدون تسجيلات صوتية وبدون مزامنة تلقائية). تُعتمد العبارة بعد نقرتين وتُحذف تلقائياً بعد ${learningStats.expiryDays} يوماً (الحد ${learningStats.maxLearnedPhrases})."
                        ),
                        color = C_DIM,
                        fontSize = 12.sp,
                    )
                }
            }

            Text(
                text = tr(
                    "Learned: ${learningStats.learnedPhraseCount}/${learningStats.maxLearnedPhrases}   •   Promoted (≥${learningStats.minHitsForPromotion} hits): ${learningStats.promotedPhraseCount}   •   Text Logs: ${learningStats.interactionLogCount}",
                    "المتعلّمة: ${learningStats.learnedPhraseCount}/${learningStats.maxLearnedPhrases}   •   المعتمدة (≥${learningStats.minHitsForPromotion}): ${learningStats.promotedPhraseCount}   •   السجلات النصية: ${learningStats.interactionLogCount}"
                ),
                color = C_TEXT,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.testTag("voice_phrase_memory_stats"),
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = {
                        if (memoryActionBusy) return@OutlinedButton
                        memoryActionBusy = true
                        scope.launch {
                            val res = runCatching {
                                withContext(Dispatchers.IO) {
                                    learningStore.exportTextLogs(VoiceLogExportFormat.JSON)
                                }
                            }
                            memoryActionBusy = false
                            val msg = res.fold(
                                onSuccess = { file ->
                                    if (ar) "تم تصدير السجلات النصية (JSON): ${file.absolutePath}"
                                    else "Exported text logs (JSON): ${file.absolutePath}"
                                },
                                onFailure = { e ->
                                    if (ar) "فشل تصدير JSON: ${e.message}" else "JSON export failed: ${e.message}"
                                }
                            )
                            syncStatusText = msg
                            onStatusMessage?.invoke(msg)
                        }
                    },
                    enabled = !memoryActionBusy,
                    border = BorderStroke(1.dp, C_GOLD.copy(alpha = 0.7f)),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("voice_export_logs_json_button"),
                ) {
                    Text(
                        text = tr("⬇  Export Text Logs (JSON)", "⬇  تصدير السجلات (JSON)"),
                        color = C_GOLD,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                OutlinedButton(
                    onClick = {
                        if (memoryActionBusy) return@OutlinedButton
                        memoryActionBusy = true
                        scope.launch {
                            val res = runCatching {
                                withContext(Dispatchers.IO) {
                                    learningStore.exportTextLogs(VoiceLogExportFormat.CSV)
                                }
                            }
                            memoryActionBusy = false
                            val msg = res.fold(
                                onSuccess = { file ->
                                    if (ar) "تم تصدير السجلات النصية (CSV): ${file.absolutePath}"
                                    else "Exported text logs (CSV): ${file.absolutePath}"
                                },
                                onFailure = { e ->
                                    if (ar) "فشل تصدير CSV: ${e.message}" else "CSV export failed: ${e.message}"
                                }
                            )
                            syncStatusText = msg
                            onStatusMessage?.invoke(msg)
                        }
                    },
                    enabled = !memoryActionBusy,
                    border = BorderStroke(1.dp, C_GOLD.copy(alpha = 0.7f)),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("voice_export_logs_csv_button"),
                ) {
                    Text(
                        text = tr("⬇  Export Text Logs (CSV)", "⬇  تصدير السجلات (CSV)"),
                        color = C_GOLD,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                OutlinedButton(
                    onClick = {
                        if (memoryActionBusy) return@OutlinedButton
                        memoryActionBusy = true
                        scope.launch {
                            val removed = withContext(Dispatchers.IO) {
                                learningStore.clearLearnedPhrases()
                            }
                            memoryActionBusy = false
                            val msg = if (ar) {
                                "تم مسح ذاكرة العبارات المتعلّمة ($removed عبارة)."
                            } else {
                                "Cleared local learned phrases ($removed removed)."
                            }
                            syncStatusText = msg
                            onStatusMessage?.invoke(msg)
                        }
                    },
                    enabled = !memoryActionBusy,
                    border = BorderStroke(1.dp, DIAG_RED.copy(alpha = 0.75f)),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("voice_clear_learned_phrases_button"),
                ) {
                    Text(
                        text = tr("🗑  Clear Learned Phrases", "🗑  مسح العبارات المتعلّمة"),
                        color = DIAG_RED,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        // 2. Production Voice Diagnostics Panel (4 Live Status Badges: KB, Models, Mic Permission, OTA Sync)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(C_BG, RoundedCornerShape(12.dp))
                .border(1.dp, C_GREY, RoundedCornerShape(12.dp))
                .padding(14.dp)
                .testTag("voice_diagnostics_panel"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tr(
                        "Voice Subsystem Diagnostics",
                        "تشخيص جاهزية النظام الصوتي"
                    ),
                    color = C_GOLD,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            VoiceAssistantService.refreshMetadata(ctx)
                            modelManager.verifyAndPrepareModels()
                            permCheckTick++
                        }
                    },
                    border = BorderStroke(1.dp, C_GOLD.copy(alpha = 0.5f)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("voice_diagnostics_refresh_button"),
                ) {
                    Text(
                        text = tr("⟳  Re-scan Diagnostics", "⟳  إعادة فحص الجاهزية"),
                        color = C_GOLD,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // 2A. Knowledge Base Status Row
            val meta = kbMeta
            val kbOk = meta != null && meta.kbVersion.isNotBlank() && meta.intentCount > 0
            DiagnosticRowCard(
                title = tr("Knowledge Base (nour_kb.db)", "قاعدة المعرفة (nour_kb.db)"),
                subtitle = if (kbOk && meta != null) {
                    tr(
                        "Installed: v${meta.kbVersion} • ${meta.intentCount} intents • ${meta.patternCount} patterns",
                        "المثبّتة: v${meta.kbVersion} • ${meta.intentCount} أمر • ${meta.patternCount} نمط"
                    )
                } else if (!kbLoadedOnce) {
                    tr("Checking internal SQLite FTS5 database…", "جارٍ فحص قاعدة بيانات SQLite FTS5…")
                } else {
                    tr("Warning: Missing nour_kb.db (check assets/kb/nour_kb.db or run OTA sync)", "تحذير: ملف nour_kb.db مفقود (تحقق من الملف أو نفّذ مزامنة OTA)")
                },
                badgeText = if (kbOk && meta != null) {
                    "v${meta.kbVersion} (${meta.intentCount})"
                } else if (!kbLoadedOnce) {
                    tr("Loading…", "جارٍ الفحص…")
                } else {
                    tr("Missing nour_kb.db", "nour_kb.db مفقود")
                },
                badgeColor = when {
                    kbOk -> DIAG_EMERALD
                    !kbLoadedOnce -> C_GOLD
                    else -> DIAG_RED
                },
                testTag = "voice_diag_kb_badge",
            )

            // 2B. Voice Models (sherpa-onnx) Status Row
            val modelsInitializing = readinessState is VoiceReadinessState.Initializing
            val modelsReady = !modelsInitializing && missingModels.isEmpty() && readinessState !is VoiceReadinessState.Error
            val modelsSubtitle = when {
                modelsInitializing -> tr(
                    "Scanning /NourAlwelayah/voice_models/ and assets/voice_models/…",
                    "جارٍ فحص مجلد /NourAlwelayah/voice_models/ والأصول المدمجة…"
                )
                modelsReady -> tr(
                    "All sherpa-onnx models verified (Silero VAD • Whisper INT8 • Arabic/English Piper TTS)",
                    "جميع نماذج sherpa-onnx جاهزة (Silero VAD • Whisper INT8 • نطق عربي/إنجليزي)"
                )
                readinessState is VoiceReadinessState.Error ->
                    (readinessState as VoiceReadinessState.Error).message
                else -> tr(
                    "Missing ${missingModels.size} model file(s) in /NourAlwelayah/voice_models/: ${missingModels.joinToString(", ")}",
                    "ملفات النماذج المفقودة (${missingModels.size}) في /NourAlwelayah/voice_models/: ${missingModels.joinToString(", ")}"
                )
            }
            DiagnosticRowCard(
                title = tr("Voice Models (sherpa-onnx)", "نماذج الصوت (sherpa-onnx)"),
                subtitle = modelsSubtitle,
                badgeText = when {
                    modelsInitializing -> tr("Initializing", "جارٍ التهيئة")
                    modelsReady -> tr("Ready", "جاهز")
                    else -> tr("Missing (${missingModels.size})", "مفقود (${missingModels.size})")
                },
                badgeColor = when {
                    modelsInitializing -> C_GOLD
                    modelsReady -> DIAG_EMERALD
                    else -> DIAG_RED
                },
                testTag = "voice_diag_models_badge",
            )

            // 2B-2. Wake-Word KeywordSpotter ("Welayah" — voice_models/kws/) Status Row (Optional)
            DiagnosticRowCard(
                title = tr(
                    "Wake-Word Spotter (\"Welayah\" • voice_models/kws/)",
                    "نموذج كلمة التنبيه (\"ولاية\" • voice_models/kws/)"
                ),
                subtitle = when {
                    modelsInitializing -> tr(
                        "Checking optional KeywordSpotter models in voice_models/kws/…",
                        "جارٍ فحص نماذج كلمة التنبيه الاختيارية في voice_models/kws/…"
                    )
                    kwsReady -> tr(
                        "Installed: encoder.int8.onnx • decoder.int8.onnx • joiner.int8.onnx • tokens.txt • keywords.txt",
                        "مثبّت وجاهز: encoder.int8.onnx • decoder.int8.onnx • joiner.int8.onnx • tokens.txt • keywords.txt"
                    )
                    else -> tr(
                        "Optional: Not installed in voice_models/kws/ (manual mic button remains fully operational)",
                        "اختياري: غير مثبّت في voice_models/kws/ (زر الميكروفون اليدوي يعمل بشكل كامل)"
                    )
                },
                badgeText = when {
                    modelsInitializing -> tr("Checking…", "جارٍ الفحص…")
                    kwsReady -> tr("Ready", "جاهز")
                    else -> tr("Not Installed", "غير مثبّت")
                },
                badgeColor = when {
                    kwsReady -> DIAG_EMERALD
                    else -> C_GOLD
                },
                testTag = "voice_diag_kws_badge",
            )

            // 2C. Microphone Permission Status Row (Kiosk-Aware)
            DiagnosticRowCard(
                title = tr("Microphone Permission (RECORD_AUDIO)", "إذن الميكروفون (RECORD_AUDIO)"),
                subtitle = when {
                    hasMicPermission -> tr(
                        "RECORD_AUDIO permission is granted for 16kHz AudioRecord capture.",
                        "إذن الميكروفون ممنوح لالتقاط الصوت بتردد 16kHz."
                    )
                    isKioskLocked -> tr(
                        "Missing while Kiosk Lock Task is active. Unlock Kiosk mode or tap Auto-Grant (Device Owner).",
                        "الإذن غير ممنوح أثناء قفل الكشك. افتح وضع الصيانة أو اضغط منح تلقائي."
                    )
                    else -> tr(
                        "RECORD_AUDIO is not granted. Tap Request Permission to enable voice capture.",
                        "إذن الميكروفون غير ممنوح. اضغط طلب الإذن لتفعيل الاستماع الصوتي."
                    )
                },
                badgeText = if (hasMicPermission) {
                    tr("Granted", "ممنوح")
                } else {
                    tr("Missing", "غير ممنوح")
                },
                badgeColor = if (hasMicPermission) DIAG_EMERALD else DIAG_RED,
                testTag = "voice_diag_mic_badge",
                trailingAction = if (!hasMicPermission) {
                    {
                        if (!isKioskLocked) {
                            OutlinedButton(
                                onClick = {
                                    if (KioskPolicy.tryAutoGrantRecordAudioPermission(ctx)) {
                                        permCheckTick++
                                        hasMicPermission = true
                                        modelManager.refreshReadinessAsync()
                                    } else {
                                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                                border = BorderStroke(1.dp, C_GOLD),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("voice_diag_request_mic_button"),
                            ) {
                                Text(
                                    text = tr("Request Permission", "طلب الإذن"),
                                    color = C_GOLD,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        } else if (KioskPolicy.isOwner(ctx)) {
                            OutlinedButton(
                                onClick = {
                                    KioskPolicy.tryAutoGrantRecordAudioPermission(ctx)
                                    permCheckTick++
                                    hasMicPermission = ContextCompat.checkSelfPermission(
                                        ctx,
                                        Manifest.permission.RECORD_AUDIO,
                                    ) == PackageManager.PERMISSION_GRANTED
                                    modelManager.refreshReadinessAsync()
                                },
                                border = BorderStroke(1.dp, C_GOLD),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("voice_diag_kiosk_autogrant_mic_button"),
                            ) {
                                Text(
                                    text = tr("Auto-Grant (Owner)", "منح تلقائي"),
                                    color = C_GOLD,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                } else {
                    null
                },
            )

            // 2D. OTA KB Sync Status Row + Manual KB Sync Button
            val syncBadgeText = when (val st = kbSyncState) {
                is KbSyncState.Idle -> tr("Idle", "خامل")
                is KbSyncState.Checking -> tr("Checking…", "جارٍ الفحص…")
                is KbSyncState.Downloading -> String.format(
                    Locale.US,
                    "%s %.0f%%",
                    tr("Downloading", "جارٍ التنزيل"),
                    (st.progress * 100f).coerceIn(0f, 100f),
                )
                is KbSyncState.UpToDate -> tr("Up to Date", "محدّث")
                is KbSyncState.Success -> tr("Updated v${st.newVersion}", "تم التحديث v${st.newVersion}")
                is KbSyncState.Error -> tr("Sync Error", "خطأ مزامنة")
            }
            val syncBadgeColor = when (kbSyncState) {
                is KbSyncState.Idle -> C_GOLD
                is KbSyncState.Checking, is KbSyncState.Downloading -> C_GOLD
                is KbSyncState.UpToDate, is KbSyncState.Success -> DIAG_EMERALD
                is KbSyncState.Error -> DIAG_RED
            }
            val syncSubtitle = when (val st = kbSyncState) {
                is KbSyncState.Idle -> tr(
                    "Checks /NourAlwelayah/config/nour_kb.db or OTA manifest (SHA-256 verified hot-swap).",
                    "يفحص الملف المحلي أو خادم OTA مع التحقق من بصمة SHA-256 والتبديل الفوري."
                )
                is KbSyncState.Checking -> tr(
                    "Fetching OTA manifest and comparing kb_version…",
                    "جارٍ جلب بيان التحديث ومقارنة إصدار قاعدة المعرفة…"
                )
                is KbSyncState.Downloading -> tr(
                    "Streaming nour_kb.db and verifying SHA-256 digest…",
                    "جارٍ تنزيل قاعدة المعرفة والتحقق من بصمة SHA-256…"
                )
                is KbSyncState.UpToDate -> tr(
                    "Installed Knowledge Base is verified and at the latest version (v${kbMeta?.kbVersion ?: "?"}).",
                    "قاعدة المعرفة المثبّتة سليمة وعلى أحدث إصدار (v${kbMeta?.kbVersion ?: "?"})."
                )
                is KbSyncState.Success -> tr(
                    "Atomically hot-swapped Knowledge Base to v${st.newVersion}.",
                    "تم التبديل الفوري لقاعدة المعرفة بنجاح إلى الإصدار v${st.newVersion}."
                )
                is KbSyncState.Error -> st.msg
            }
            val syncInProgress = kbSyncState is KbSyncState.Checking || kbSyncState is KbSyncState.Downloading

            DiagnosticRowCard(
                title = tr("OTA Knowledge Base Sync", "مزامنة قاعدة المعرفة (OTA)"),
                subtitle = syncSubtitle,
                badgeText = syncBadgeText,
                badgeColor = syncBadgeColor,
                testTag = "voice_diag_sync_badge",
                trailingAction = {
                    OutlinedButton(
                        onClick = { triggerManualKbSync() },
                        enabled = !syncInProgress,
                        border = BorderStroke(1.dp, C_GOLD),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("voice_kb_manual_sync_button"),
                    ) {
                        Text(
                            text = if (syncInProgress) {
                                tr("Syncing…", "جارٍ المزامنة…")
                            } else {
                                tr("⟳  Manual KB Sync", "⟳  مزامنة قاعدة المعرفة")
                            },
                            color = C_GOLD,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                },
            )

            if (kbSyncState is KbSyncState.Downloading) {
                val dl = kbSyncState as KbSyncState.Downloading
                LinearProgressIndicator(
                    progress = { dl.progress.coerceIn(0f, 1f) },
                    color = C_GOLD,
                    trackColor = C_GREY,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .testTag("voice_kb_download_progress"),
                )
            }

            // Hidden metadata label preserving testTag compatibility
            val ver = kbMeta?.kbVersion ?: "missing"
            val intents = kbMeta?.intentCount ?: 0
            val patterns = kbMeta?.patternCount ?: 0
            Text(
                text = tr(
                    "KB Version: v$ver   •   Intents: $intents   •   Patterns: $patterns",
                    "إصدار قاعدة المعرفة: v$ver   •   الأوامر: $intents   •   الأنماط: $patterns"
                ),
                color = C_DIM,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.testTag("voice_kb_metadata_label"),
            )
        }

        if (syncStatusText.isNotEmpty()) {
            Text(
                text = syncStatusText,
                color = C_GOLD,
                fontSize = 13.sp,
                modifier = Modifier.testTag("voice_kb_sync_status"),
            )
        }

        // 3. Single-Thread sherpa-onnx Voice I/O Engine Bar + Independent Test Query Input Field
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(C_BG, RoundedCornerShape(10.dp))
                .border(1.dp, C_GREY, RoundedCornerShape(10.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                    Text(
                        text = tr(
                            "Test Query Console (Works Offline Even Before .onnx Models Are Pushed)",
                            "منصة اختبار الأوامر النصية والصوتية (تعمل حتى قبل تثبيت ملفات .onnx)"
                        ),
                        color = C_GOLD,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val stateLabel = when (val st = voiceUiState) {
                        is VoiceUiState.Idle ->
                            if (voiceIoEngine.isAzanOrAlarmActive()) {
                                tr("State: Preempted (Azan/Alarm Active)", "الحالة: متوقف مؤقتاً (الأذان/المنبه قيد التشغيل)")
                            } else {
                                tr("State: Idle (Ready)", "الحالة: جاهز للاستماع")
                            }
                        is VoiceUiState.Listening ->
                            String.format(
                                Locale.US,
                                "%s (RMS %.0f%%)",
                                tr("State: Listening...", "الحالة: جارٍ الاستماع..."),
                                (st.amplitude * 100f).coerceIn(0f, 100f),
                            )
                        is VoiceUiState.Processing ->
                            tr("State: Processing (Whisper INT8 STT + FTS5)...", "الحالة: جارٍ المعالجة (Whisper INT8 + FTS5)...")
                        is VoiceUiState.Speaking ->
                            tr("State: Speaking (${st.lang.uppercase(Locale.ROOT)}): ${st.text}", "الحالة: جارٍ النطق (${st.lang}): ${st.text}")
                        is VoiceUiState.Error ->
                            tr("State: Error — ${st.msg}", "الحالة: خطأ — ${st.msg}")
                    }
                    Text(
                        text = stateLabel,
                        color = when (voiceUiState) {
                            is VoiceUiState.Listening,
                            is VoiceUiState.Speaking -> C_GOLD
                            is VoiceUiState.Error -> DIAG_RED
                            else -> C_DIM
                        },
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.testTag("voice_io_state_label"),
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val isBusyVoice = voiceUiState !is VoiceUiState.Idle &&
                        voiceUiState !is VoiceUiState.Error
                    if (isBusyVoice) {
                        OutlinedButton(
                            onClick = { voiceIoEngine.abortImmediately() },
                            border = BorderStroke(1.dp, DIAG_RED),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("voice_io_abort_button"),
                        ) {
                            Text(
                                text = tr("■  Abort", "■  إيقاف فوري"),
                                color = DIAG_RED,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    Button(
                        onClick = {
                            val grantedNow = ContextCompat.checkSelfPermission(
                                ctx,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (grantedNow) {
                                voiceIoEngine.startListeningSession()
                            } else if (!isKioskLocked) {
                                listenMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            } else {
                                // In Kiosk mode, try Device Owner auto-grant or let engine emit clear bilingual error
                                KioskPolicy.tryAutoGrantRecordAudioPermission(ctx)
                                permCheckTick++
                                voiceIoEngine.startListeningSession()
                            }
                        },
                        enabled = s.voiceAssistantEnabled && !voiceIoEngine.isAzanOrAlarmActive(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = C_ACCENT,
                            contentColor = Color.White,
                        ),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("voice_io_listen_button"),
                    ) {
                        Text(
                            text = tr("🎙️  Listen (VAD + STT)", "🎙️  تحدث الآن"),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = testQueryInput,
                    onValueChange = { testQueryInput = it },
                    label = {
                        Text(
                            tr(
                                "Type an Arabic or English command (e.g. كم الساعة الآن / What is the next prayer?)",
                                "اكتب أمراً بالعربية أو الإنجليزية (مثال: متى أذان المغرب / What time is it?)"
                            )
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { runTestQuery(testQueryInput) }),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("voice_test_query_input"),
                )

                Button(
                    onClick = { runTestQuery(testQueryInput) },
                    enabled = testQueryInput.isNotBlank() && !queryBusy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = C_GOLD,
                        contentColor = C_BG,
                    ),
                    modifier = Modifier
                        .height(54.dp)
                        .testTag("voice_test_query_run_button"),
                ) {
                    Text(
                        text = if (queryBusy) {
                            "..."
                        } else {
                            tr("▶  Run Query", "▶  تنفيذ الأمر")
                        },
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Quick-tap bilingual sample queries for immediate tablet testing without typing
            val sampleQueries = remember {
                listOf(
                    "متى الصلاة القادمة؟",
                    "كم الساعة الآن؟",
                    "ما هو التاريخ الهجري اليوم؟",
                    "What is the next prayer time?",
                    "Raise the volume",
                    "ارفع السطوع",
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                sampleQueries.forEach { sample ->
                    Box(
                        modifier = Modifier
                            .background(C_CARD, RoundedCornerShape(8.dp))
                            .border(1.dp, C_GOLD.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .clickable {
                                testQueryInput = sample
                                runTestQuery(sample)
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = sample,
                            color = C_TEXT,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            // 4. Live Retrieval & Dispatch Result Card
            val result = lastDispatchResult
            if (result != null) {
                val borderColor = when (result) {
                    is DispatchResult.Success -> C_GOLD
                    is DispatchResult.NoMatch -> C_DIM
                    is DispatchResult.Error -> DIAG_RED
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(C_CARD, RoundedCornerShape(10.dp))
                        .border(1.dp, borderColor, RoundedCornerShape(10.dp))
                        .padding(12.dp)
                        .testTag("voice_test_query_result_card"),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    when (result) {
                        is DispatchResult.Success -> {
                            val match = lastMatch
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = tr(
                                        "✓ MATCH: ${result.intentId}",
                                        "✓ تطابق: ${result.intentId}"
                                    ),
                                    color = C_GOLD,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Text(
                                    text = String.format(
                                        Locale.US,
                                        "score=%.2f | lang=%s | action=%s%s",
                                        result.score,
                                        match?.detectedLang ?: "?",
                                        result.actionType,
                                        result.commandPayload?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "",
                                    ),
                                    color = C_DIM,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                            Text(
                                text = result.spokenResponse,
                                color = C_TEXT,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            if (result.sourceRef.isNotBlank()) {
                                Text(
                                    text = tr("Source: ${result.sourceRef}", "المصدر: ${result.sourceRef}"),
                                    color = C_DIM,
                                    fontSize = 12.sp,
                                )
                            }
                        }

                        is DispatchResult.NoMatch -> {
                            Text(
                                text = tr(
                                    "No deterministic match above confidence threshold (0.35). Zero-hallucination guard active.",
                                    "لم يتم العثور على أمر مطابق فوق حد الثقة (0.35). حماية عدم التخمين مفعّلة."
                                ),
                                color = C_DIM,
                                fontSize = 14.sp,
                            )
                        }

                        is DispatchResult.Error -> {
                            Text(
                                text = result.message,
                                color = DIAG_RED,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRowCard(
    title: String,
    subtitle: String,
    badgeText: String,
    badgeColor: Color,
    testTag: String,
    trailingAction: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(C_CARD, RoundedCornerShape(10.dp))
            .border(1.dp, badgeColor.copy(alpha = 0.42f), RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = title,
                    color = C_TEXT,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )

                // Status Pill Badge (BrandGold / Emerald / Red)
                Row(
                    modifier = Modifier
                        .background(badgeColor.copy(alpha = 0.16f), RoundedCornerShape(999.dp))
                        .border(1.dp, badgeColor.copy(alpha = 0.65f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(badgeColor),
                    )
                    Text(
                        text = badgeText,
                        color = badgeColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Text(
                text = subtitle,
                color = C_DIM,
                fontSize = 12.sp,
            )
        }

        if (trailingAction != null) {
            Spacer(Modifier.width(8.dp))
            trailingAction()
        }
    }
}
