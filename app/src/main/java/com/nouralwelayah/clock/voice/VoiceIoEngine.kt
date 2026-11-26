package com.nouralwelayah.clock.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.nouralwelayah.clock.alert.AlertState
import com.nouralwelayah.clock.data.AppSettings
import com.nouralwelayah.clock.data.Store
import com.nouralwelayah.clock.media.MediaHub
import com.nouralwelayah.clock.system.FileSystemManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * UI State emitted by [VoiceIoEngine] for the Nour Alwelayah Voice Assistant overlay and admin console.
 */
sealed class VoiceUiState {
    data object Idle : VoiceUiState()
    data class Listening(val amplitude: Float) : VoiceUiState()
    data object Processing : VoiceUiState()
    data class Speaking(
        val text: String,
        val sourceRef: String,
        val lang: String,
        val query: String = "",
        val isTtsFinished: Boolean = false,
        val suggestions: List<VoiceCorrectionSuggestion> = emptyList(),
        val normalizedQuery: String = "",
        val matchSource: String = KbRetrievalOutcome.MATCH_SOURCE_FTS5,
        val isLowConfidence: Boolean = false,
        val autoDismissMs: Long = VoiceIoEngine.POST_TTS_AUTO_DISMISS_MS,
        val matchedIntentId: String? = null,
    ) : VoiceUiState()
    data class Error(val msg: String) : VoiceUiState()
}

/**
 * Resolved file or asset paths for a sherpa-onnx Whisper model (`tiny` or `base` INT8).
 */
data class WhisperModelPaths(
    val encoderPath: String,
    val decoderPath: String,
    val tokensPath: String,
    val fromAssets: Boolean,
)

/**
 * Resolved file or asset paths for a sherpa-onnx Piper/VITS TTS model (`ar` or `en`).
 */
data class VitsTtsModelPaths(
    val modelPath: String,
    val tokensPath: String,
    val dataDirPath: String = "",
    val lexiconPath: String = "",
    val fromAssets: Boolean,
)

/**
 * Production-Hardened Offline-First Single-Thread Voice I/O Engine
 * (`sherpa-onnx` + Silero VAD + Whisper INT8 STT + Piper/VITS TTS + Hands-Free KeywordSpotter `"Welayah"`)
 * with Strict Azan/Alarm Preemption and [VoiceModelManager] Readiness Verification.
 */
class VoiceIoEngine(
    context: Context,
    private val retrievalEngine: KbRetrievalEngine,
    private val commandDispatcher: VoiceCommandDispatcher,
    private val modelManager: VoiceModelManager = VoiceModelManager(context.applicationContext),
    private val learningStore: VoiceLearningStore = VoiceLearningStore(context.applicationContext),
) : Closeable {

    private val appContext: Context = context.applicationContext

    /**
     * Dedicated single-thread dispatcher for all sherpa-onnx JNI calls & audio pipeline work.
     */
    private val singleThreadExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NourVoiceIo-SingleThread").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
    }
    val inferenceDispatcher = singleThreadExecutor.asCoroutineDispatcher()
    private val engineScope = CoroutineScope(SupervisorJob() + inferenceDispatcher)

    /**
     * Synchronizes startListening(), stopListening(), and abortImmediately() transitions
     * and serializes inference pipeline execution on low-RAM tablets.
     */
    private val sessionControlLock = Any()
    private val pipelineMutex = Mutex()
    private val sessionGeneration = AtomicLong(0L)

    private val _uiState = MutableStateFlow<VoiceUiState>(VoiceUiState.Idle)
    val uiState: StateFlow<VoiceUiState> = _uiState.asStateFlow()

    private val _isWakeWordListening = MutableStateFlow(false)
    val isWakeWordListening: StateFlow<Boolean> = _isWakeWordListening.asStateFlow()

    val readinessState: StateFlow<VoiceReadinessState>
        get() = modelManager.readinessState

    // Atomic flags set by abortImmediately() and stopListening()
    private val abortedFlag = AtomicBoolean(false)
    private val stopCaptureRequested = AtomicBoolean(false)
    private val destroyedFlag = AtomicBoolean(false)

    @Volatile
    private var activePipelineJob: Job? = null

    @Volatile
    private var activeAudioRecord: AudioRecord? = null

    @Volatile
    private var activeKwsAudioRecord: AudioRecord? = null

    @Volatile
    private var activeAudioTrack: AudioTrack? = null

    @Volatile
    private var androidFallbackTts: TextToSpeech? = null

    @Volatile
    private var androidTtsReady: Boolean = false

    // Lazy sherpa-onnx native instances (accessed exclusively on inferenceDispatcher)
    private var sileroVad: Vad? = null
    private var whisperRecognizer: OfflineRecognizer? = null
    private var vitsTtsAr: OfflineTts? = null
    private var vitsTtsEn: OfflineTts? = null
    private var keywordSpotter: KeywordSpotter? = null
    private var loadedModelSignature: String = ""
    private var loadedWhisperLanguage: String = ""
    private var loadedKwsSignature: String = ""

    init {
        // 1. Automatically observe AlertState.current so any Azan or Alarm immediately preempts active voice I/O & KWS
        engineScope.launch {
            AlertState.current.collect { activeAlert ->
                if (activeAlert != null) {
                    abortImmediately()
                }
            }
        }
        // 2. Start background supervisor loop for hands-free wake-word ("Welayah") detection when enabled & idle
        startWakeWordMonitorLoop()
    }

    /**
     * Checks whether an Azan or Alarm is currently ringing.
     */
    fun isAzanOrAlarmActive(): Boolean = AlertState.current.value != null

    /**
     * Checks whether Media3 / YouTube / system audio or TTS is currently playing to guarantee
     * zero self-triggering of the wake-word spotter.
     */
    fun isMediaOrTtsPlaying(): Boolean {
        if (_uiState.value !is VoiceUiState.Idle) return true
        if (activeAudioTrack != null) return true
        if (MediaHub.youTubePlaying.value) return true
        val am = runCatching {
            appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        }.getOrNull()
        if (am?.isMusicActive == true) return true
        return false
    }

    /**
     * Synchronously stops and releases any active wake-word [AudioRecord] so push-to-talk,
     * wake-word transition, or Azan preemption experiences zero microphone lock contention.
     */
    private fun stopKwsCaptureSync() {
        val kwsRec = activeKwsAudioRecord
        activeKwsAudioRecord = null
        _isWakeWordListening.value = false
        if (kwsRec != null) {
            runCatching {
                if (kwsRec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    kwsRec.stop()
                }
            }
            runCatching { kwsRec.release() }
        }
    }

    /**
     * Convenience entrypoint invoked by the Compose Voice Mic button to begin a voice session.
     */
    fun startListening() = startListeningSession()

    /**
     * Immediately applies live changes to [AppSettings.voiceAssistantEnabled],
     * [AppSettings.voiceRecognitionMode], and [AppSettings.wakeWordEnabled] (triggered from either
     * the local Settings UI or remote `WebRemote` updates) without requiring an app restart.
     */
    fun onVoiceSettingsChanged(s: AppSettings = Store.settings.value) {
        if (destroyedFlag.get()) return
        if (!s.voiceAssistantEnabled) {
            abortImmediately()
        } else if (!s.wakeWordEnabled) {
            synchronized(sessionControlLock) {
                stopKwsCaptureSync()
            }
        }
    }

    /**
     * Gracefully stops active microphone capture so any speech already buffered by VAD
     * can finish transcribing, or returns to [VoiceUiState.Idle] if no speech was captured.
     */
    fun stopListening() {
        synchronized(sessionControlLock) {
            stopCaptureRequested.set(true)
            runCatching {
                activeAudioRecord?.let { record ->
                    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        record.stop()
                    }
                }
            }
        }
    }

    /**
     * Synchronously aborts any active microphone capture (both KWS and command STT),
     * in-flight STT inference, or TTS audio output, releases active [AudioRecord] / [AudioTrack] /
     * native `sherpa-onnx` handles, and resets [uiState] to [VoiceUiState.Idle].
     *
     * Invoked the instant an Azan or Alarm triggers in `AlertService` or when the user taps Cancel / Dismiss.
     */
    fun abortImmediately() {
        synchronized(sessionControlLock) {
            sessionGeneration.incrementAndGet()
            abortedFlag.set(true)
            stopCaptureRequested.set(true)

            // 0. Synchronously stop & release any active KWS AudioRecord
            stopKwsCaptureSync()

            // 1. Synchronously stop & release command AudioRecord capture
            val record = activeAudioRecord
            activeAudioRecord = null
            if (record != null) {
                runCatching {
                    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        record.stop()
                    }
                }
                runCatching { record.release() }
            }

            // 2. Synchronously pause, flush, stop & release AudioTrack TTS playback
            val track = activeAudioTrack
            activeAudioTrack = null
            if (track != null) {
                runCatching {
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING ||
                        track.playState == AudioTrack.PLAYSTATE_PAUSED
                    ) {
                        track.pause()
                        track.flush()
                        track.stop()
                    }
                }
                runCatching { track.release() }
            }

            // 3. Stop fallback Android TTS if active
            runCatching {
                androidFallbackTts?.stop()
            }

            // 4. Cancel in-flight coroutine job
            runCatching {
                activePipelineJob?.cancel(CancellationException("Aborted immediately"))
                activePipelineJob = null
            }

            // 5. Immediately reset UI state to Idle
            _uiState.value = VoiceUiState.Idle

            // 6. Schedule native sherpa-onnx handle release on the single-thread inference dispatcher
            //    when preempted by an Azan/Alarm to reclaim RAM on low-memory tablets.
            if (!destroyedFlag.get() && isAzanOrAlarmActive()) {
                engineScope.launch {
                    releaseNativeSherpaHandlesOnInferenceThread()
                }
            }
        }
    }

    /**
     * Starts a voice listening -> VAD -> Whisper STT -> KB Retrieval -> Command Dispatch -> Neural TTS cycle.
     *
     * Guarded by [sessionControlLock] and [pipelineMutex] to prevent race conditions.
     */
    fun startListeningSession(playWakeChime: Boolean = false) {
        if (destroyedFlag.get()) return
        Store.init(appContext)
        val settings = Store.settings.value
        val isArUi = settings.appLanguage == "ar"

        synchronized(sessionControlLock) {
            // Immediately release KWS microphone capture before starting command capture
            stopKwsCaptureSync()

            // 1. Strict Azan / Alarm Preemption Guard
            if (isAzanOrAlarmActive()) {
                Log.i(TAG, "Voice activation blocked: Azan or Alarm is currently active.")
                _uiState.value = VoiceUiState.Idle
                return
            }

            if (!settings.voiceAssistantEnabled) {
                _uiState.value = VoiceUiState.Error(
                    if (isArUi) "المساعد الصوتي معطّل في الإعدادات." else "Voice Assistant is disabled in Settings."
                )
                return
            }

            // 2. Explicit Kiosk-safe RECORD_AUDIO permission verification before touching AudioRecord
            if (!hasRecordAudioPermissionSafe()) {
                modelManager.refreshReadinessAsync()
                _uiState.value = VoiceUiState.Error(
                    if (isArUi) {
                        "يرجى منح إذن الميكروفون (RECORD_AUDIO) من لوحة الإعدادات لتفعيل الاستماع الصوتي."
                    } else {
                        "Microphone permission (RECORD_AUDIO) is required. Grant it in Settings / Diagnostics."
                    }
                )
                return
            }

            // Cancel any previous session before starting a fresh one
            activePipelineJob?.cancel()
            val currentGen = sessionGeneration.incrementAndGet()
            abortedFlag.set(false)
            stopCaptureRequested.set(false)

            activePipelineJob = engineScope.launch {
                pipelineMutex.withLock {
                    if (abortedFlag.get() || sessionGeneration.get() != currentGen || isAzanOrAlarmActive()) {
                        _uiState.value = VoiceUiState.Idle
                        return@withLock
                    }
                    if (playWakeChime) {
                        playWakeWordChimeOnInferenceThread()
                    }

                    try {
                        // Step 0: Verify VoiceReadinessState via VoiceModelManager before native initialization
                        val readiness = modelManager.verifyAndPrepareModels()
                        val resolved = modelManager.getCachedResolvedModels()

                        if (readiness is VoiceReadinessState.MissingPermission || !hasRecordAudioPermissionSafe()) {
                            _uiState.value = VoiceUiState.Error(
                                if (isArUi) {
                                    "يرجى منح إذن الميكروفون (RECORD_AUDIO)."
                                } else {
                                    "Microphone permission (RECORD_AUDIO) is required."
                                }
                            )
                            return@withLock
                        }

                        if (readiness is VoiceReadinessState.Error) {
                            _uiState.value = VoiceUiState.Error(readiness.message)
                            return@withLock
                        }

                        // Initialize only verified existing native models on the single-thread dispatcher
                        ensureModelsInitializedOnInferenceThread(resolved)

                        if (abortedFlag.get() || sessionGeneration.get() != currentGen || isAzanOrAlarmActive()) {
                            _uiState.value = VoiceUiState.Idle
                            return@withLock
                        }

                        // Guard: If Whisper STT models are missing on disk, report clearly without native crash
                        if (resolved?.whisperPaths == null || whisperRecognizer == null) {
                            val missingStt = resolved?.missingFiles
                                ?.filter { it.startsWith("whisper") || it.contains("silero_vad") }
                                .orEmpty()
                                .ifEmpty { listOf("whisper/tiny-encoder.int8.onnx", "whisper/tiny-decoder.int8.onnx", "whisper/tiny-tokens.txt") }
                            _uiState.value = VoiceUiState.Error(
                                if (isArUi) {
                                    "نماذج التعرف على الكلام (Whisper ONNX) غير متوفرة في مجلد voice_models: ${missingStt.joinToString(", ")}"
                                } else {
                                    "Offline Whisper STT models missing in voice_models/: ${missingStt.joinToString(", ")}"
                                }
                            )
                            delay(ERROR_AUTO_DISMISS_MS)
                            if (!abortedFlag.get() && _uiState.value is VoiceUiState.Error) {
                                _uiState.value = VoiceUiState.Idle
                            }
                            return@withLock
                        }

                        // Step 1: Capture 16kHz Mono PCM + Silero VAD segmentation
                        val speechSamples = captureSpeechWithVad(currentGen)
                        if (abortedFlag.get() || sessionGeneration.get() != currentGen || isAzanOrAlarmActive()) {
                            _uiState.value = VoiceUiState.Idle
                            return@withLock
                        }

                        if (speechSamples == null || speechSamples.size < MIN_SPEECH_SAMPLES) {
                            _uiState.value = VoiceUiState.Idle
                            return@withLock
                        }

                        // Step 2: Offline Whisper INT8 Speech-to-Text
                        _uiState.value = VoiceUiState.Processing
                        val transcript = transcribeWithWhisperOnInferenceThread(speechSamples)
                        if (abortedFlag.get() || sessionGeneration.get() != currentGen || isAzanOrAlarmActive()) {
                            _uiState.value = VoiceUiState.Idle
                            return@withLock
                        }

                        if (transcript.isNullOrBlank()) {
                            _uiState.value = VoiceUiState.Error(
                                if (isArUi) {
                                    "لم يتم التعرف على الكلام، يرجى المحاولة مرة أخرى."
                                } else {
                                    "Could not recognize speech. Please try again."
                                }
                            )
                            delay(ERROR_AUTO_DISMISS_MS)
                            if (!abortedFlag.get() && _uiState.value is VoiceUiState.Error) {
                                _uiState.value = VoiceUiState.Idle
                            }
                            return@withLock
                        }

                        // Step 3: Deterministic FTS5 Retrieval & Command Dispatch
                        processTranscriptOnInferenceThread(transcript)
                    } catch (_: CancellationException) {
                        _uiState.value = VoiceUiState.Idle
                    } catch (e: Throwable) {
                        Log.e(TAG, "Voice pipeline error", e)
                        if (!abortedFlag.get() && !isAzanOrAlarmActive()) {
                            _uiState.value = VoiceUiState.Error(
                                e.message ?: if (isArUi) "خطأ في محرك الصوت" else "Voice I/O pipeline error"
                            )
                            delay(ERROR_AUTO_DISMISS_MS)
                            if (!abortedFlag.get() && _uiState.value is VoiceUiState.Error) {
                                _uiState.value = VoiceUiState.Idle
                            }
                        } else {
                            _uiState.value = VoiceUiState.Idle
                        }
                    }
                }
            }
        }
    }

    /**
     * Runs the retrieval, command dispatch, and offline TTS synthesis pipeline directly from a text query
     * (used by the Admin/Settings Test Query Console) on the single-thread inference dispatcher.
     *
     * Works 100% independently of microphone permissions or Whisper STT `.onnx` models so technicians
     * can always verify `TextNormalizer` + `FTS5` + `VoiceCommandDispatcher` before pushing `.onnx` files.
     */
    fun speakOrDispatchTextQuery(
        rawQuery: String,
        speakResponse: Boolean = true,
        onCompleted: ((KbMatch?, DispatchResult) -> Unit)? = null,
    ) {
        if (destroyedFlag.get()) return
        if (isAzanOrAlarmActive()) {
            _uiState.value = VoiceUiState.Idle
            return
        }

        synchronized(sessionControlLock) {
            stopKwsCaptureSync()
            activePipelineJob?.cancel()
            val currentGen = sessionGeneration.incrementAndGet()
            abortedFlag.set(false)
            stopCaptureRequested.set(false)

            activePipelineJob = engineScope.launch {
                pipelineMutex.withLock {
                    var completedInvoked = false
                    try {
                        _uiState.value = VoiceUiState.Processing
                        val outcome = retrievalEngine.retrieveWithCandidates(
                            rawQuery = rawQuery,
                            learningStore = learningStore,
                        )
                        val match = outcome.bestMatch
                        val result = commandDispatcher.dispatch(match)
                        learningStore.logInteraction(
                            lang = outcome.detectedLang,
                            rawTranscript = rawQuery,
                            normalizedTranscript = outcome.normalizedQuery,
                            matchedIntentId = match?.intentId,
                            score = match?.score ?: 0f,
                            matchSource = outcome.matchSource,
                        )
                        completedInvoked = true
                        onCompleted?.invoke(match, result)

                        if (abortedFlag.get() || sessionGeneration.get() != currentGen || isAzanOrAlarmActive()) {
                            _uiState.value = VoiceUiState.Idle
                            return@withLock
                        }

                        when (result) {
                            is DispatchResult.Success -> {
                                val lang = match?.detectedLang ?: TextNormalizer.detectLanguage(result.spokenResponse)
                                if (speakResponse && result.spokenResponse.isNotBlank()) {
                                    // Prepare any available TTS models without requiring STT models or mic permission
                                    runCatching {
                                        modelManager.verifyAndPrepareModels()
                                        ensureModelsInitializedOnInferenceThread(modelManager.getCachedResolvedModels())
                                    }
                                    synthesizeAndPlayTtsOnInferenceThread(
                                        text = result.spokenResponse,
                                        sourceRef = result.sourceRef,
                                        lang = lang,
                                        query = rawQuery,
                                        suggestions = outcome.suggestions,
                                        normalizedQuery = outcome.normalizedQuery,
                                        matchSource = outcome.matchSource,
                                        isLowConfidence = outcome.isLowConfidence,
                                        matchedIntentId = match?.intentId,
                                    )
                                } else {
                                    _uiState.value = VoiceUiState.Idle
                                }
                            }

                            is DispatchResult.NoMatch -> {
                                if (speakResponse && outcome.suggestions.isNotEmpty()) {
                                    val isAr = outcome.detectedLang == "ar"
                                    val fallbackText = if (isAr) {
                                        "عذراً، لم يتضح الأمر تماماً. يمكنك اختيار الأمر المقصود أدناه."
                                    } else {
                                        "Sorry, I wasn't sure about that command. Tap a suggestion below."
                                    }
                                    synthesizeAndPlayTtsOnInferenceThread(
                                        text = fallbackText,
                                        sourceRef = "",
                                        lang = outcome.detectedLang,
                                        query = rawQuery,
                                        suggestions = outcome.suggestions,
                                        normalizedQuery = outcome.normalizedQuery,
                                        matchSource = outcome.matchSource,
                                        isLowConfidence = true,
                                    )
                                } else {
                                    _uiState.value = VoiceUiState.Idle
                                }
                            }

                            is DispatchResult.Error -> {
                                _uiState.value = VoiceUiState.Error(result.message)
                                delay(ERROR_AUTO_DISMISS_MS)
                                if (!abortedFlag.get() && _uiState.value is VoiceUiState.Error) {
                                    _uiState.value = VoiceUiState.Idle
                                }
                            }
                        }
                    } catch (_: CancellationException) {
                        if (!completedInvoked) {
                            onCompleted?.invoke(null, DispatchResult.NoMatch)
                        }
                        _uiState.value = VoiceUiState.Idle
                    } catch (e: Throwable) {
                        Log.e(TAG, "Error in speakOrDispatchTextQuery", e)
                        if (!completedInvoked) {
                            onCompleted?.invoke(null, DispatchResult.Error(e.message ?: "Query error"))
                        }
                        _uiState.value = VoiceUiState.Idle
                    }
                }
            }
        }
    }

    /**
     * Handles a one-tap user correction chip from [VoiceOverlay]:
     * 1. Immediately stops any playing TTS and respects Azan/Alarm preemption.
     * 2. Records/increments `normalizedQuery -> suggestion.intentId` in [VoiceLearningStore].
     * 3. Dispatches the selected candidate intent (`commandDispatcher.dispatch(suggestion.match)`)
     *    and speaks/displays [DispatchResult.Success] via [synthesizeAndPlayTtsOnInferenceThread]
     *    instead of dismissing silently.
     */
    fun applyCorrectionSuggestion(
        suggestion: VoiceCorrectionSuggestion,
        rawQuery: String,
        normalizedQuery: String,
    ) {
        if (destroyedFlag.get()) return
        if (isAzanOrAlarmActive()) {
            abortImmediately()
            return
        }

        val currentSpeaking = _uiState.value as? VoiceUiState.Speaking
        val effectiveNorm = normalizedQuery.ifBlank {
            val lang = TextNormalizer.detectLanguage(rawQuery)
            TextNormalizer.normalize(rawQuery, lang)
        }
        val detectedLang = suggestion.match.detectedLang.ifBlank {
            TextNormalizer.detectLanguage(rawQuery)
        }

        synchronized(sessionControlLock) {
            stopKwsCaptureSync()
            // Immediately stop any active TTS before synthesizing the corrected intent's response
            val track = activeAudioTrack
            activeAudioTrack = null
            if (track != null) {
                runCatching {
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING ||
                        track.playState == AudioTrack.PLAYSTATE_PAUSED
                    ) {
                        track.pause()
                        track.flush()
                        track.stop()
                    }
                }
                runCatching { track.release() }
            }
            runCatching { androidFallbackTts?.stop() }
            runCatching {
                activePipelineJob?.cancel(CancellationException("User selected correction suggestion"))
                activePipelineJob = null
            }

            val currentGen = sessionGeneration.incrementAndGet()
            abortedFlag.set(false)
            stopCaptureRequested.set(false)

            // Record correction, dispatch the selected intent, and speak/display DispatchResult.Success
            activePipelineJob = engineScope.launch {
                pipelineMutex.withLock {
                    if (abortedFlag.get() || sessionGeneration.get() != currentGen || isAzanOrAlarmActive()) {
                        _uiState.value = VoiceUiState.Idle
                        return@withLock
                    }
                    try {
                        if (effectiveNorm.isNotBlank()) {
                            learningStore.recordCorrection(
                                normalizedTranscript = effectiveNorm,
                                intentId = suggestion.intentId,
                                rawTranscript = rawQuery,
                                lang = detectedLang,
                                originalMatchedIntentId = currentSpeaking?.matchedIntentId,
                                originalScore = suggestion.score,
                            )
                        }
                        if (abortedFlag.get() || sessionGeneration.get() != currentGen || isAzanOrAlarmActive()) {
                            _uiState.value = VoiceUiState.Idle
                            return@withLock
                        }

                        val result = commandDispatcher.dispatch(suggestion.match)
                        if (abortedFlag.get() || sessionGeneration.get() != currentGen || isAzanOrAlarmActive()) {
                            _uiState.value = VoiceUiState.Idle
                            return@withLock
                        }

                        when (result) {
                            is DispatchResult.Success -> {
                                val responseLang = detectedLang.ifBlank {
                                    TextNormalizer.detectLanguage(result.spokenResponse)
                                }
                                if (result.spokenResponse.isNotBlank()) {
                                    runCatching {
                                        ensureModelsInitializedOnInferenceThread(modelManager.getCachedResolvedModels())
                                    }
                                    synthesizeAndPlayTtsOnInferenceThread(
                                        text = result.spokenResponse,
                                        sourceRef = result.sourceRef,
                                        lang = responseLang,
                                        query = rawQuery,
                                        suggestions = emptyList(),
                                        normalizedQuery = effectiveNorm,
                                        matchSource = KbRetrievalOutcome.MATCH_SOURCE_LEARNED_PHRASE,
                                        isLowConfidence = false,
                                        matchedIntentId = suggestion.intentId,
                                    )
                                } else {
                                    _uiState.value = VoiceUiState.Idle
                                }
                            }

                            is DispatchResult.Error -> {
                                _uiState.value = VoiceUiState.Error(result.message)
                                delay(ERROR_AUTO_DISMISS_MS)
                                if (!abortedFlag.get() && _uiState.value is VoiceUiState.Error) {
                                    _uiState.value = VoiceUiState.Idle
                                }
                            }

                            DispatchResult.NoMatch -> {
                                _uiState.value = VoiceUiState.Idle
                            }
                        }
                    } catch (_: CancellationException) {
                        _uiState.value = VoiceUiState.Idle
                    } catch (e: Throwable) {
                        Log.w(TAG, "Failed dispatching correction intent ${suggestion.intentId}", e)
                        _uiState.value = VoiceUiState.Idle
                    }
                }
            }
        }
    }

    private suspend fun processTranscriptOnInferenceThread(transcript: String) {
        val outcome = retrievalEngine.retrieveWithCandidates(
            rawQuery = transcript,
            learningStore = learningStore,
        )
        if (abortedFlag.get() || isAzanOrAlarmActive()) {
            _uiState.value = VoiceUiState.Idle
            return
        }

        val match = outcome.bestMatch
        learningStore.logInteraction(
            lang = outcome.detectedLang,
            rawTranscript = transcript,
            normalizedTranscript = outcome.normalizedQuery,
            matchedIntentId = match?.intentId,
            score = match?.score ?: 0f,
            matchSource = outcome.matchSource,
        )

        val result = commandDispatcher.dispatch(match)
        if (abortedFlag.get() || isAzanOrAlarmActive()) {
            _uiState.value = VoiceUiState.Idle
            return
        }

        when (result) {
            is DispatchResult.Success -> {
                val lang = match?.detectedLang ?: TextNormalizer.detectLanguage(result.spokenResponse)
                synthesizeAndPlayTtsOnInferenceThread(
                    text = result.spokenResponse,
                    sourceRef = result.sourceRef,
                    lang = lang,
                    query = transcript,
                    suggestions = outcome.suggestions,
                    normalizedQuery = outcome.normalizedQuery,
                    matchSource = outcome.matchSource,
                    isLowConfidence = outcome.isLowConfidence,
                    matchedIntentId = match?.intentId,
                )
            }

            is DispatchResult.NoMatch -> {
                val isAr = outcome.detectedLang == "ar"
                val fallbackText = when {
                    outcome.suggestions.isNotEmpty() && isAr ->
                        "عذراً، لم يتضح الأمر تماماً. اختر الأمر المقصود أدناه."
                    outcome.suggestions.isNotEmpty() ->
                        "Sorry, I wasn't sure about that command. Tap a suggestion below."
                    isAr ->
                        "عذراً، هذا الأمر غير مدرج في قاعدة المعرفة المعتمدة."
                    else ->
                        "Sorry, that command is not in the verified offline knowledge base."
                }
                synthesizeAndPlayTtsOnInferenceThread(
                    text = fallbackText,
                    sourceRef = "",
                    lang = if (isAr) "ar" else "en",
                    query = transcript,
                    suggestions = outcome.suggestions,
                    normalizedQuery = outcome.normalizedQuery,
                    matchSource = outcome.matchSource,
                    isLowConfidence = true,
                )
            }

            is DispatchResult.Error -> {
                _uiState.value = VoiceUiState.Error(result.message)
                delay(ERROR_AUTO_DISMISS_MS)
                if (!abortedFlag.get() && _uiState.value is VoiceUiState.Error) {
                    _uiState.value = VoiceUiState.Idle
                }
            }
        }
    }

    private fun hasRecordAudioPermissionSafe(): Boolean {
        return modelManager.hasRecordAudioPermission() &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Captures 16kHz Mono 16-bit PCM audio from [AudioRecord] in 512-sample chunks (`32ms`),
     * feeds chunks into Silero VAD (`silero_vad.onnx`) or energy-envelope VAD fallback,
     * updates [VoiceUiState.Listening] with normalized RMS amplitude, and stops automatically
     * once trailing silence exceeds [VAD_SILENCE_TIMEOUT_MS] or total duration reaches [MAX_CAPTURE_DURATION_MS].
     */
    @SuppressLint("MissingPermission")
    private suspend fun captureSpeechWithVad(expectedGen: Long): FloatArray? {
        val isArUi = Store.settings.value.appLanguage == "ar"

        // Explicit permission check immediately prior to AudioRecord instantiation
        if (!hasRecordAudioPermissionSafe()) {
            _uiState.value = VoiceUiState.Error(
                if (isArUi) {
                    "تم رفض الوصول إلى الميكروفون (RECORD_AUDIO)."
                } else {
                    "Microphone permission (RECORD_AUDIO) denied."
                }
            )
            return null
        }

        val minBufBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(VAD_WINDOW_SAMPLES * 4)

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufBytes * 2,
            )
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException creating AudioRecord", se)
            _uiState.value = VoiceUiState.Error(
                if (isArUi) "لا يوجد إذن لاستخدام الميكروفون." else "Microphone permission denied by system."
            )
            return null
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to create AudioRecord", e)
            return null
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record.release() }
            return null
        }

        activeAudioRecord = record
        val vad = sileroVad
        runCatching { vad?.reset() }

        val shortChunk = ShortArray(VAD_WINDOW_SAMPLES)
        val floatChunk = FloatArray(VAD_WINDOW_SAMPLES)
        val accumulatedSamples = ArrayList<Float>(SAMPLE_RATE_HZ * 6)

        var speechStarted = false
        var lastSpeechTimestampMs = System.currentTimeMillis()
        val sessionStartMs = lastSpeechTimestampMs

        try {
            record.startRecording()
            _uiState.value = VoiceUiState.Listening(amplitude = 0f)

            while (
                engineScope.isActive &&
                !abortedFlag.get() &&
                !stopCaptureRequested.get() &&
                sessionGeneration.get() == expectedGen &&
                !isAzanOrAlarmActive()
            ) {
                val readCount = record.read(shortChunk, 0, VAD_WINDOW_SAMPLES)
                if (readCount <= 0) {
                    delay(10)
                    continue
                }

                var sumSq = 0.0
                for (i in 0 until readCount) {
                    val norm = (shortChunk[i] / 32768.0f).coerceIn(-1.0f, 1.0f)
                    floatChunk[i] = norm
                    sumSq += norm * norm
                }
                val rms = sqrt(sumSq / readCount).toFloat()
                val visualAmplitude = (rms * 4.5f).coerceIn(0f, 1f)
                _uiState.value = VoiceUiState.Listening(amplitude = visualAmplitude)

                val nowMs = System.currentTimeMillis()

                if (vad != null) {
                    val chunkToFeed = if (readCount == VAD_WINDOW_SAMPLES) {
                        floatChunk
                    } else {
                        floatChunk.copyOf(readCount)
                    }
                    vad.acceptWaveform(chunkToFeed)
                    val detectedNow = vad.isSpeechDetected()
                    if (detectedNow) {
                        speechStarted = true
                        lastSpeechTimestampMs = nowMs
                    }
                    while (!vad.empty()) {
                        val segment = vad.front()
                        vad.pop()
                        for (s in segment.samples) {
                            accumulatedSamples.add(s)
                        }
                    }
                    if (speechStarted) {
                        for (i in 0 until readCount) {
                            accumulatedSamples.add(floatChunk[i])
                        }
                    }
                } else {
                    val isSpeechFrame = rms >= ENERGY_VAD_SPEECH_THRESHOLD
                    if (isSpeechFrame) {
                        speechStarted = true
                        lastSpeechTimestampMs = nowMs
                    }
                    if (speechStarted) {
                        for (i in 0 until readCount) {
                            accumulatedSamples.add(floatChunk[i])
                        }
                    }
                }

                if (speechStarted && (nowMs - lastSpeechTimestampMs) >= VAD_SILENCE_TIMEOUT_MS) {
                    runCatching {
                        vad?.flush()
                        while (vad != null && !vad.empty()) {
                            val seg = vad.front()
                            vad.pop()
                            for (s in seg.samples) accumulatedSamples.add(s)
                        }
                    }
                    break
                }

                if (!speechStarted && (nowMs - sessionStartMs) >= INITIAL_NO_SPEECH_TIMEOUT_MS) {
                    break
                }

                if ((nowMs - sessionStartMs) >= MAX_CAPTURE_DURATION_MS) {
                    break
                }
            }
        } finally {
            runCatching {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
            }
            runCatching { record.release() }
            if (activeAudioRecord === record) {
                activeAudioRecord = null
            }
        }

        if (abortedFlag.get() || sessionGeneration.get() != expectedGen || isAzanOrAlarmActive() || accumulatedSamples.isEmpty()) {
            return null
        }

        val out = FloatArray(accumulatedSamples.size)
        for (i in accumulatedSamples.indices) {
            out[i] = accumulatedSamples[i]
        }
        return out
    }

    /**
     * Decodes a 16kHz float PCM buffer using `sherpa-onnx` [OfflineRecognizer] (Whisper tiny/base INT8).
     * Must be called on [inferenceDispatcher].
     */
    private fun transcribeWithWhisperOnInferenceThread(samples: FloatArray): String? {
        val recognizer = whisperRecognizer ?: return null
        if (abortedFlag.get() || isAzanOrAlarmActive()) return null

        return try {
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE_HZ)
                recognizer.decode(stream)
                val result = recognizer.getResult(stream)
                result.text.trim().takeIf { it.isNotEmpty() }
            } finally {
                stream.release()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Whisper OfflineRecognizer decoding failed", e)
            null
        }
    }

    /**
     * Synthesizes [text] in [lang] (`"ar"` or `"en"`) using `sherpa-onnx` [OfflineTts] (Piper/VITS ONNX)
     * and plays the resulting waveform via [AudioTrack] in small interruptible chunks so
     * [abortImmediately] halts playback within milliseconds when an Azan triggers.
     */
    private suspend fun synthesizeAndPlayTtsOnInferenceThread(
        text: String,
        sourceRef: String,
        lang: String,
        query: String = "",
        suggestions: List<VoiceCorrectionSuggestion> = emptyList(),
        normalizedQuery: String = "",
        matchSource: String = KbRetrievalOutcome.MATCH_SOURCE_FTS5,
        isLowConfidence: Boolean = false,
        matchedIntentId: String? = null,
    ) {
        if (abortedFlag.get() || isAzanOrAlarmActive() || text.isBlank()) {
            _uiState.value = VoiceUiState.Idle
            return
        }

        val dismissDelayMs = if (suggestions.isNotEmpty()) {
            POST_TTS_WITH_SUGGESTIONS_AUTO_DISMISS_MS
        } else {
            POST_TTS_AUTO_DISMISS_MS
        }

        _uiState.value = VoiceUiState.Speaking(
            text = text,
            sourceRef = sourceRef,
            lang = lang,
            query = query,
            isTtsFinished = false,
            suggestions = suggestions,
            normalizedQuery = normalizedQuery,
            matchSource = matchSource,
            isLowConfidence = isLowConfidence,
            autoDismissMs = dismissDelayMs,
            matchedIntentId = matchedIntentId,
        )

        val ttsEngine = if (lang == "ar") {
            vitsTtsAr ?: vitsTtsEn
        } else {
            vitsTtsEn ?: vitsTtsAr
        }

        var playedWithSherpa = false
        if (ttsEngine != null) {
            val generated = runCatching {
                ttsEngine.generate(text = text, sid = 0, speed = 1.0f)
            }.onFailure { e ->
                Log.w(TAG, "sherpa-onnx OfflineTts generation failed, trying system offline TTS fallback", e)
            }.getOrNull()

            if (generated != null && generated.samples.isNotEmpty() && !abortedFlag.get() && !isAzanOrAlarmActive()) {
                playFloatSamplesOnAudioTrack(
                    samples = generated.samples,
                    sampleRate = generated.sampleRate.coerceAtLeast(16000),
                )
                playedWithSherpa = true
            }
        }

        // Fallback to offline Android TextToSpeech if VITS .onnx models are not yet on disk
        if (!playedWithSherpa && !abortedFlag.get() && !isAzanOrAlarmActive()) {
            speakWithAndroidOfflineTtsFallback(text, lang)
        }

        // Hold the result card cleanly after TTS finishes (6s when correction chips are shown, else 3s),
        // unless preempted immediately by Azan, user tap, or Dismiss.
        if (!abortedFlag.get() && !isAzanOrAlarmActive()) {
            _uiState.value = VoiceUiState.Speaking(
                text = text,
                sourceRef = sourceRef,
                lang = lang,
                query = query,
                isTtsFinished = true,
                suggestions = suggestions,
                normalizedQuery = normalizedQuery,
                matchSource = matchSource,
                isLowConfidence = isLowConfidence,
                autoDismissMs = dismissDelayMs,
                matchedIntentId = matchedIntentId,
            )
            delay(dismissDelayMs)
        }

        _uiState.value = VoiceUiState.Idle
    }

    /**
     * Plays normalized float32 samples (`-1.0f..1.0f`) through [AudioTrack] as 16-bit PCM mono
     * in 64ms slices so [abortImmediately] can interrupt playback instantaneously on Azan trigger.
     */
    private fun playFloatSamplesOnAudioTrack(
        samples: FloatArray,
        sampleRate: Int,
    ) {
        if (samples.isEmpty() || abortedFlag.get() || isAzanOrAlarmActive()) return

        val pcmShorts = ShortArray(samples.size)
        for (i in samples.indices) {
            val clamped = samples[i].coerceIn(-1.0f, 1.0f)
            pcmShorts[i] = (clamped * 32767.0f).toInt().toShort()
        }

        val minBufBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(sampleRate / 5)

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val track = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioTrack.Builder()
                    .setAudioAttributes(audioAttrs)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(minBufBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBufBytes,
                    AudioTrack.MODE_STREAM,
                )
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to create AudioTrack for TTS", e)
            return
        }

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track.release() }
            return
        }

        activeAudioTrack = track
        try {
            track.play()
            val chunkShorts = (sampleRate / 15).coerceAtLeast(1024) // ~66ms slice
            var offset = 0
            while (offset < pcmShorts.size && !abortedFlag.get() && !isAzanOrAlarmActive()) {
                val toWrite = minOf(chunkShorts, pcmShorts.size - offset)
                val written = track.write(pcmShorts, offset, toWrite)
                if (written <= 0) break
                offset += written
            }
            if (!abortedFlag.get() && !isAzanOrAlarmActive()) {
                runCatching { track.stop() }
            }
        } finally {
            runCatching { track.release() }
            if (activeAudioTrack === track) {
                activeAudioTrack = null
            }
        }
    }

    private suspend fun speakWithAndroidOfflineTtsFallback(text: String, lang: String) {
        if (abortedFlag.get() || isAzanOrAlarmActive()) return
        val tts = ensureAndroidTtsInitialized() ?: run {
            delay(1800)
            return
        }

        val targetLocale = if (lang == "ar") Locale("ar") else Locale.US
        runCatching { tts.language = targetLocale }

        suspendCancellableCoroutine<Unit> { cont ->
            val utteranceId = "nour_voice_${UUID.randomUUID()}"
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    if (cont.isActive) cont.resume(Unit)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onError(id: String?, errorCode: Int) {
                    if (cont.isActive) cont.resume(Unit)
                }
            })

            cont.invokeOnCancellation {
                runCatching { tts.stop() }
            }

            if (abortedFlag.get() || isAzanOrAlarmActive()) {
                if (cont.isActive) cont.resume(Unit)
                return@suspendCancellableCoroutine
            }

            val res = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (res == TextToSpeech.ERROR && cont.isActive) {
                cont.resume(Unit)
            }
        }
    }

    private suspend fun ensureAndroidTtsInitialized(): TextToSpeech? {
        androidFallbackTts?.let { existing ->
            if (androidTtsReady) return existing
        }
        return withContext(kotlinx.coroutines.Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                var instance: TextToSpeech? = null
                instance = TextToSpeech(appContext) { status ->
                    if (status == TextToSpeech.SUCCESS && instance != null) {
                        androidFallbackTts = instance
                        androidTtsReady = true
                        if (cont.isActive) cont.resume(instance)
                    } else {
                        runCatching { instance?.shutdown() }
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        }
    }

    /**
     * Initializes `silero_vad.onnx`, Whisper INT8 (`OfflineRecognizer`), and bilingual Arabic/English
     * Piper/VITS (`OfflineTts`) models exclusively on [inferenceDispatcher], ONLY after
     * [VoiceModelManager] has verified that the corresponding `.onnx` files exist and are non-empty.
     */
    private fun ensureModelsInitializedOnInferenceThread(resolved: ResolvedVoiceModels?) {
        if (resolved == null) return

        val whisperLang = Store.settings.value.whisperLanguageCode()
        val whisperThreads = minOf(4, Runtime.getRuntime().availableProcessors().coerceAtLeast(2))

        val signature = buildString {
            append(resolved.vadModelFile?.absolutePath.orEmpty())
            append('|')
            append(resolved.whisperPaths?.encoderPath.orEmpty())
            append('|')
            append(whisperLang)
            append('|')
            append(resolved.ttsArPaths?.modelPath.orEmpty())
            append('|')
            append(resolved.ttsEnPaths?.modelPath.orEmpty())
        }
        if (signature == loadedModelSignature &&
            (sileroVad != null || whisperRecognizer != null || vitsTtsAr != null || vitsTtsEn != null)
        ) {
            return
        }

        // If the user switched Recognition Mode (Auto / Arabic Priority / English Priority),
        // release the existing Whisper recognizer so it re-initializes with the new language lock.
        if (whisperRecognizer != null && loadedWhisperLanguage != whisperLang) {
            runCatching { whisperRecognizer?.release() }
            whisperRecognizer = null
        }

        val assets = appContext.assets

        // 1. Initialize Silero VAD with tightened end-of-speech silence duration (0.48s)
        val vadFile = resolved.vadModelFile
        if (sileroVad == null && vadFile != null && vadFile.isFile && vadFile.length() > 0L) {
            runCatching {
                val cfg = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = vadFile.absolutePath,
                        threshold = 0.5f,
                        minSilenceDuration = SILERO_MIN_SILENCE_SEC,
                        minSpeechDuration = 0.20f,
                        windowSize = VAD_WINDOW_SAMPLES,
                        maxSpeechDuration = 15.0f,
                    ),
                    sampleRate = SAMPLE_RATE_HZ,
                    numThreads = 1,
                    provider = "cpu",
                )
                sileroVad = Vad(assetManager = null, config = cfg)
                Log.i(TAG, "Initialized Silero VAD from ${vadFile.absolutePath} (minSilence=${SILERO_MIN_SILENCE_SEC}s)")
            }.onFailure { e ->
                Log.w(TAG, "Failed to initialize Silero VAD from ${vadFile.absolutePath}", e)
            }
        }

        // 2. Initialize Whisper tiny/base INT8 OfflineRecognizer with multi-threaded CPU decoding
        //    and configurable language mode ("" = Auto Bilingual, "ar" = Arabic Priority, "en" = English Priority)
        val whisperPaths = resolved.whisperPaths
        if (whisperRecognizer == null && whisperPaths != null && verifyWhisperFilesExist(whisperPaths)) {
            runCatching {
                val whisperCfg = OfflineWhisperModelConfig(
                    encoder = whisperPaths.encoderPath,
                    decoder = whisperPaths.decoderPath,
                    language = whisperLang,
                    task = "transcribe",
                )
                val modelCfg = OfflineModelConfig(
                    whisper = whisperCfg,
                    tokens = whisperPaths.tokensPath,
                    numThreads = whisperThreads,
                    provider = "cpu",
                    modelType = "whisper",
                )
                val recCfg = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE_HZ, featureDim = 80),
                    modelConfig = modelCfg,
                    decodingMethod = "greedy_search",
                )
                whisperRecognizer = OfflineRecognizer(
                    assetManager = if (whisperPaths.fromAssets) assets else null,
                    config = recCfg,
                )
                loadedWhisperLanguage = whisperLang
                Log.i(
                    TAG,
                    "Initialized Whisper INT8 OfflineRecognizer (${whisperPaths.encoderPath}, threads=$whisperThreads, lang='${whisperLang.ifEmpty { "auto" }}')"
                )
            }.onFailure { e ->
                Log.w(TAG, "Failed to initialize Whisper OfflineRecognizer", e)
            }
        }

        // 3. Initialize Arabic & English Piper/VITS OfflineTts only if verified on disk
        val ttsAr = resolved.ttsArPaths
        if (vitsTtsAr == null && ttsAr != null && verifyVitsFilesExist(ttsAr)) {
            vitsTtsAr = initVitsTts(ttsAr, assets)
        }
        val ttsEn = resolved.ttsEnPaths
        if (vitsTtsEn == null && ttsEn != null && verifyVitsFilesExist(ttsEn)) {
            vitsTtsEn = initVitsTts(ttsEn, assets)
        }

        loadedModelSignature = signature
    }

    private fun verifyWhisperFilesExist(paths: WhisperModelPaths): Boolean {
        if (paths.fromAssets) return true
        val enc = File(paths.encoderPath)
        val dec = File(paths.decoderPath)
        val tok = File(paths.tokensPath)
        return enc.isFile && enc.length() > 0L &&
            dec.isFile && dec.length() > 0L &&
            tok.isFile && tok.length() > 0L
    }

    private fun verifyVitsFilesExist(paths: VitsTtsModelPaths): Boolean {
        if (paths.fromAssets) return true
        val model = File(paths.modelPath)
        val tokens = File(paths.tokensPath)
        return model.isFile && model.length() > 0L &&
            tokens.isFile && tokens.length() > 0L
    }

    private fun verifyKwsFilesExist(paths: KwsModelPaths): Boolean {
        if (paths.fromAssets) return true
        val enc = File(paths.encoderPath)
        val dec = File(paths.decoderPath)
        val joiner = File(paths.joinerPath)
        val tok = File(paths.tokensPath)
        val kw = File(paths.keywordsFilePath)
        return enc.isFile && enc.length() > 0L &&
            dec.isFile && dec.length() > 0L &&
            joiner.isFile && joiner.length() > 0L &&
            tok.isFile && tok.length() > 0L &&
            kw.isFile && kw.length() > 0L
    }

    /**
     * Pre-flight validator and UTF-8 BOM sanitizer for `keywords.txt`.
     *
     * `sherpa-onnx` C++ calls `exit(-1)` (`EXIT_SELF status=255`) if `keywords.txt` contains a UTF-8 BOM
     * or any token not present in `tokens.txt`. This helper:
     * 1. Parses `tokens.txt` into a `HashSet<String>` (stripping any `\uFEFF` BOM).
     * 2. Reads `keywords.txt` line by line (stripping `\uFEFF`, ignoring blank lines and `#` comments).
     * 3. Validates every whitespace-separated token prior to `:`, `#`, or `@` against `tokens.txt`.
     * 4. Drops any line with unknown tokens (logging a warning) and returns `null` if 0 valid lines remain.
     * 5. Writes the sanitized lines (UTF-8 without BOM, Unix `\n` endings) to
     *    `File(appContext.cacheDir, "kws_validated_keywords.txt")` and returns its `absolutePath`.
     */
    private fun prepareValidatedKeywordsFile(kwsPaths: KwsModelPaths): String? {
        return try {
            val tokensLines = if (kwsPaths.fromAssets) {
                appContext.assets.open(kwsPaths.tokensPath).bufferedReader(Charsets.UTF_8).use { it.readLines() }
            } else {
                val tokFile = File(kwsPaths.tokensPath)
                if (!tokFile.isFile || tokFile.length() == 0L) return null
                tokFile.readLines(Charsets.UTF_8)
            }

            val validTokens = HashSet<String>(tokensLines.size * 2)
            for (rawLine in tokensLines) {
                val cleaned = rawLine.removePrefix("\uFEFF").trim()
                if (cleaned.isEmpty()) continue
                val firstToken = cleaned.split(WHITESPACE_REGEX).firstOrNull()?.removePrefix("\uFEFF").orEmpty()
                if (firstToken.isNotEmpty()) {
                    validTokens.add(firstToken)
                }
            }
            if (validTokens.isEmpty()) {
                Log.e(TAG, "KWS tokens.txt is empty or unreadable at ${kwsPaths.tokensPath}")
                return null
            }

            val keywordLines = if (kwsPaths.fromAssets) {
                appContext.assets.open(kwsPaths.keywordsFilePath).bufferedReader(Charsets.UTF_8).use { it.readLines() }
            } else {
                val kwFile = File(kwsPaths.keywordsFilePath)
                if (!kwFile.isFile || kwFile.length() == 0L) return null
                kwFile.readLines(Charsets.UTF_8)
            }

            val sanitizedLines = ArrayList<String>(keywordLines.size)
            for ((index, rawLine) in keywordLines.withIndex()) {
                val line = rawLine.replace("\uFEFF", "").trim()
                if (line.isEmpty() || line.startsWith("#")) continue

                val metaBoundary = line.indexOfFirst { it == ':' || it == '#' || it == '@' }
                val tokenPart = (if (metaBoundary >= 0) line.substring(0, metaBoundary) else line).trim()
                if (tokenPart.isEmpty()) {
                    Log.w(TAG, "Skipping KWS keyword line ${index + 1} with empty token section: '$line'")
                    continue
                }

                val tokens = tokenPart.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
                val unknownTokens = tokens.filter { it !in validTokens }
                if (unknownTokens.isNotEmpty()) {
                    Log.w(
                        TAG,
                        "Dropping KWS keyword line ${index + 1} due to unknown token(s) $unknownTokens not in tokens.txt: '$line'"
                    )
                    continue
                }

                sanitizedLines.add(line)
            }

            if (sanitizedLines.isEmpty()) {
                Log.e(
                    TAG,
                    "Zero valid keyword lines remained after validating ${kwsPaths.keywordsFilePath} against ${kwsPaths.tokensPath}; aborting KWS init to prevent native exit(-1)."
                )
                return null
            }

            val validatedFile = File(appContext.cacheDir, "kws_validated_keywords.txt")
            val content = sanitizedLines.joinToString(separator = "\n", postfix = "\n")
            validatedFile.writeBytes(content.toByteArray(Charsets.UTF_8))
            validatedFile.absolutePath
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to validate KWS keywords file ${kwsPaths.keywordsFilePath}", e)
            null
        }
    }

    /**
     * Lazily initializes `KeywordSpotter` on [inferenceDispatcher] using official `sherpa-onnx`
     * [OnlineTransducerModelConfig], [OnlineModelConfig], [FeatureConfig], and [KeywordSpotterConfig].
     * Re-initializes automatically if [sensitivity] (`keywordsThreshold`) or model paths change.
     */
    private fun ensureKwsInitializedOnInferenceThread(
        kwsPaths: KwsModelPaths,
        sensitivity: Float,
    ): KeywordSpotter? {
        if (!verifyKwsFilesExist(kwsPaths)) return null
        val safeKeywordsPath = prepareValidatedKeywordsFile(kwsPaths) ?: return null
        val clampedThreshold = sensitivity.coerceIn(
            AppSettings.MIN_WAKE_WORD_SENSITIVITY,
            AppSettings.MAX_WAKE_WORD_SENSITIVITY,
        )
        val thresholdKey = String.format(Locale.US, "%.2f", clampedThreshold)
        val kwFileMtime = runCatching { File(kwsPaths.keywordsFilePath).lastModified() }.getOrDefault(0L)
        val signature = buildString {
            append(kwsPaths.encoderPath)
            append('|')
            append(kwsPaths.decoderPath)
            append('|')
            append(kwsPaths.joinerPath)
            append('|')
            append(kwsPaths.tokensPath)
            append('|')
            append(kwsPaths.keywordsFilePath)
            append('@')
            append(kwFileMtime)
            append('|')
            append(thresholdKey)
        }

        if (keywordSpotter != null && loadedKwsSignature == signature) {
            return keywordSpotter
        }

        runCatching { keywordSpotter?.release() }
        keywordSpotter = null

        return runCatching {
            val transducerCfg = OnlineTransducerModelConfig(
                encoder = kwsPaths.encoderPath,
                decoder = kwsPaths.decoderPath,
                joiner = kwsPaths.joinerPath,
            )
            val onlineModelCfg = OnlineModelConfig(
                transducer = transducerCfg,
                tokens = kwsPaths.tokensPath,
                numThreads = 1,
                provider = "cpu",
            )
            val kwsCfg = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE_HZ, featureDim = 80),
                modelConfig = onlineModelCfg,
                keywordsFile = safeKeywordsPath,
                keywordsScore = KWS_KEYWORDS_SCORE,
                keywordsThreshold = clampedThreshold,
            )
            KeywordSpotter(
                assetManager = null,
                config = kwsCfg,
            ).also { spotter ->
                keywordSpotter = spotter
                loadedKwsSignature = signature
                Log.i(
                    TAG,
                    "Initialized sherpa-onnx KeywordSpotter ($safeKeywordsPath, threshold=$thresholdKey)"
                )
            }
        }.onFailure { e ->
            Log.w(TAG, "Failed to initialize sherpa-onnx KeywordSpotter", e)
        }.getOrNull()
    }

    /**
     * Evaluates whether the background wake-word loop is allowed to hold the microphone right now.
     *
     * Strict Safety & Zero Self-Triggering Rules:
     * - Must be enabled in [AppSettings] (`voiceAssistantEnabled && wakeWordEnabled`).
     * - Must be in [VoiceUiState.Idle] (never while Listening, Processing, Speaking, or Error).
     * - Never while an Azan or Alarm is ringing ([isAzanOrAlarmActive]).
     * - Never while Media3 / YouTube / TTS audio is actively playing ([isMediaOrTtsPlaying]).
     * - Must have `RECORD_AUDIO` permission granted.
     */
    private fun shouldRunWakeWordLoop(): Boolean {
        if (destroyedFlag.get()) return false
        val s = Store.settings.value
        if (!s.voiceAssistantEnabled || !s.wakeWordEnabled) return false
        if (_uiState.value !is VoiceUiState.Idle) return false
        if (isAzanOrAlarmActive()) return false
        if (isMediaOrTtsPlaying()) return false
        if (!hasRecordAudioPermissionSafe()) return false
        return true
    }

    /**
     * Launches the background supervisor loop that monitors `wakeWordEnabled` and appliance idle state
     * to run 16kHz mono PCM streaming into `KeywordSpotter` for `"Welayah"`.
     */
    private fun startWakeWordMonitorLoop() {
        CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default).launch {
            var initialModelScanAttempted = false
            while (isActive && !destroyedFlag.get()) {
                if (!shouldRunWakeWordLoop()) {
                    if (activeKwsAudioRecord != null || _isWakeWordListening.value) {
                        stopKwsCaptureSync()
                    }
                    delay(350L)
                    continue
                }

                var resolved = modelManager.getCachedResolvedModels()
                if (resolved == null && !initialModelScanAttempted) {
                    initialModelScanAttempted = true
                    runCatching { modelManager.verifyAndPrepareModels() }
                    resolved = modelManager.getCachedResolvedModels()
                }

                val kwsPaths = resolved?.kwsPaths
                if (kwsPaths == null || !verifyKwsFilesExist(kwsPaths)) {
                    if (_isWakeWordListening.value) {
                        stopKwsCaptureSync()
                    }
                    delay(1200L)
                    continue
                }

                if (pipelineMutex.isLocked || !shouldRunWakeWordLoop()) {
                    delay(250L)
                    continue
                }

                val keywordDetected = try {
                    withContext(inferenceDispatcher) {
                        if (!pipelineMutex.tryLock()) {
                            false
                        } else {
                            try {
                                runKwsStreamingSessionOnInferenceThread(kwsPaths)
                            } finally {
                                pipelineMutex.unlock()
                            }
                        }
                    }
                } catch (_: CancellationException) {
                    false
                } catch (e: Throwable) {
                    Log.w(TAG, "Wake-word monitor iteration error", e)
                    false
                }

                if (keywordDetected && !destroyedFlag.get() && !isAzanOrAlarmActive()) {
                    // Immediately transition to command capture with a subtle wake chime
                    startListeningSession(playWakeChime = true)
                    delay(400L)
                } else {
                    delay(300L)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun create16kMonoAudioRecord(): AudioRecord? {
        if (!hasRecordAudioPermissionSafe()) return null
        val minBufBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(KWS_CHUNK_SAMPLES * 4)

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufBytes * 2,
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to create 16kHz AudioRecord for KWS", e)
            return null
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record.release() }
            return null
        }
        return record
    }

    /**
     * Streams 16kHz mono PCM from [AudioRecord] into `KeywordSpotter.createStream()` in 100ms chunks
     * (`1600` samples) exclusively on [inferenceDispatcher].
     *
     * @return `true` if a non-empty wake word was spotted, `false` if paused or pre-empted.
     */
    @SuppressLint("MissingPermission")
    private suspend fun runKwsStreamingSessionOnInferenceThread(kwsPaths: KwsModelPaths): Boolean {
        if (!shouldRunWakeWordLoop()) return false
        val activeThreshold = Store.settings.value.wakeWordSensitivity
        val kws = ensureKwsInitializedOnInferenceThread(kwsPaths, activeThreshold) ?: return false

        val record = create16kMonoAudioRecord() ?: return false
        var stream: OnlineStream? = null
        var keywordTriggered = false

        activeKwsAudioRecord = record
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                return false
            }

            stream = kws.createStream()
            _isWakeWordListening.value = true

            val shortChunk = ShortArray(KWS_CHUNK_SAMPLES) // 100ms @ 16kHz = 1600 samples
            while (shouldRunWakeWordLoop() && activeKwsAudioRecord === record) {
                // Re-initialize KWS if the technician adjusted wakeWordSensitivity in Settings/Admin
                if (kotlin.math.abs(Store.settings.value.wakeWordSensitivity - activeThreshold) >= 0.005f) {
                    break
                }

                val readCount = record.read(shortChunk, 0, KWS_CHUNK_SAMPLES)
                if (!shouldRunWakeWordLoop() || activeKwsAudioRecord !== record) {
                    break
                }
                if (readCount <= 0) {
                    delay(15L)
                    continue
                }

                val floatChunk = FloatArray(readCount)
                for (i in 0 until readCount) {
                    floatChunk[i] = shortChunk[i] / 32768.0f
                }

                val activeStream = stream ?: break
                activeStream.acceptWaveform(floatChunk, SAMPLE_RATE_HZ)
                while (kws.isReady(activeStream)) {
                    kws.decode(activeStream)
                }
                val detectedKeyword = kws.getResult(activeStream).keyword.trim()
                if (detectedKeyword.isNotEmpty()) {
                    Log.i(TAG, "Hands-free wake word spotted: '$detectedKeyword'")
                    runCatching { kws.reset(activeStream) }
                    keywordTriggered = true
                    break
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "KWS streaming session interrupted", e)
        } finally {
            runCatching { stream?.release() }
            stopKwsCaptureSync()
        }

        return keywordTriggered
    }

    /**
     * Plays a brief, subtle two-note BrandGold harmonic chime (110ms total) when the `"Welayah"`
     * wake word triggers before opening the command `AudioRecord`.
     */
    private fun playWakeWordChimeOnInferenceThread() {
        if (abortedFlag.get() || isAzanOrAlarmActive()) return
        val sampleRate = SAMPLE_RATE_HZ
        val durationSamples = (sampleRate * 0.11f).toInt() // 110ms brief chime
        val samples = FloatArray(durationSamples)
        val half = durationSamples / 2
        for (i in 0 until durationSamples) {
            val freq = if (i < half) 587.33 else 880.0 // D5 -> A5 gentle chime
            val env = sin(PI * i.toDouble() / durationSamples.toDouble())
            samples[i] = (sin(2.0 * PI * freq * i.toDouble() / sampleRate.toDouble()) * env * 0.18).toFloat()
        }
        playFloatSamplesOnAudioTrack(
            samples = samples,
            sampleRate = sampleRate,
        )
    }

    private fun initVitsTts(paths: VitsTtsModelPaths, assets: AssetManager): OfflineTts? {
        return runCatching {
            val vitsCfg = OfflineTtsVitsModelConfig(
                model = paths.modelPath,
                lexicon = paths.lexiconPath,
                tokens = paths.tokensPath,
                dataDir = paths.dataDirPath,
                noiseScale = 0.667f,
                noiseScaleW = 0.8f,
                lengthScale = 1.0f,
            )
            val ttsCfg = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = vitsCfg,
                    numThreads = 1,
                    provider = "cpu",
                ),
                maxNumSentences = 1,
            )
            OfflineTts(
                assetManager = if (paths.fromAssets) assets else null,
                config = ttsCfg,
            ).also {
                Log.i(TAG, "Initialized OfflineTts (${paths.modelPath})")
            }
        }.onFailure { e ->
            Log.w(TAG, "Failed to initialize OfflineTts for ${paths.modelPath}", e)
        }.getOrNull()
    }

    private fun releaseNativeSherpaHandlesOnInferenceThread() {
        stopKwsCaptureSync()
        runCatching { keywordSpotter?.release() }
        keywordSpotter = null
        loadedKwsSignature = ""
        runCatching { sileroVad?.release() }
        sileroVad = null
        runCatching { whisperRecognizer?.release() }
        whisperRecognizer = null
        runCatching { vitsTtsAr?.release() }
        vitsTtsAr = null
        runCatching { vitsTtsEn?.release() }
        vitsTtsEn = null
        loadedModelSignature = ""
        loadedWhisperLanguage = ""
    }

    /**
     * Releases all active audio streams, native `sherpa-onnx` handles, and background executors.
     */
    fun onDestroy() {
        if (!destroyedFlag.compareAndSet(false, true)) return
        abortImmediately()
        engineScope.launch {
            releaseNativeSherpaHandlesOnInferenceThread()
            runCatching { androidFallbackTts?.stop() }
            runCatching { androidFallbackTts?.shutdown() }
            androidFallbackTts = null
            androidTtsReady = false
        }.invokeOnCompletion {
            runCatching { engineScope.cancel() }
            runCatching { singleThreadExecutor.shutdownNow() }
        }
    }

    override fun close() {
        onDestroy()
    }

    companion object {
        private const val TAG = "VoiceIoEngine"
        const val SAMPLE_RATE_HZ = 16000
        const val VAD_WINDOW_SAMPLES = 512 // 32ms @ 16kHz (required by Silero VAD)
        const val KWS_CHUNK_SAMPLES = 1600 // 100ms @ 16kHz (streaming chunk for KeywordSpotter)
        private const val KWS_KEYWORDS_SCORE = 1.5f
        private const val MIN_SPEECH_SAMPLES = SAMPLE_RATE_HZ / 4 // 250ms minimum utterance
        private const val SILERO_MIN_SILENCE_SEC = 0.48f // Tightened 0.45–0.55s end-of-speech detector
        private const val VAD_SILENCE_TIMEOUT_MS = 520L  // Fast auto-stop for short 1–3 word commands
        private const val INITIAL_NO_SPEECH_TIMEOUT_MS = 5_000L
        private const val MAX_CAPTURE_DURATION_MS = 12_000L
        const val POST_TTS_AUTO_DISMISS_MS = 3_000L
        const val POST_TTS_WITH_SUGGESTIONS_AUTO_DISMISS_MS = 6_000L
        private const val ERROR_AUTO_DISMISS_MS = 6_000L
        private const val ENERGY_VAD_SPEECH_THRESHOLD = 0.018f
        private val WHITESPACE_REGEX = Regex("\\s+")
    }
}
