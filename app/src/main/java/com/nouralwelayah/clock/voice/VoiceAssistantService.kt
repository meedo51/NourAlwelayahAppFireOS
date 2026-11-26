package com.nouralwelayah.clock.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Status of an OTA / manual Knowledge Base (`nour_kb.db`) synchronization operation.
 */
data class KbSyncResult(
    val success: Boolean,
    val messageEn: String,
    val messageAr: String,
    val metadata: KbMetadata? = null,
)

/**
 * Process-wide holder and coordinator for the Offline Voice Assistant pipeline:
 * - [KbDatabaseManager]
 * - [KbRetrievalEngine]
 * - [VoiceCommandDispatcher]
 * - [VoiceModelManager]
 * - [KbOtaSyncManager]
 * - [VoiceIoEngine]
 */
object VoiceAssistantService {

    @Volatile
    private var dbManager: KbDatabaseManager? = null

    @Volatile
    private var retrievalEngine: KbRetrievalEngine? = null

    @Volatile
    private var commandDispatcher: VoiceCommandDispatcher? = null

    @Volatile
    private var modelManager: VoiceModelManager? = null

    @Volatile
    private var otaSyncManager: KbOtaSyncManager? = null

    @Volatile
    private var voiceIoEngine: VoiceIoEngine? = null

    @Volatile
    private var learningStore: VoiceLearningStore? = null

    private val _metadata = MutableStateFlow<KbMetadata?>(null)
    val metadata: StateFlow<KbMetadata?> = _metadata.asStateFlow()

    /**
     * Custom callback hook for external Ktor / OTA knowledge base synchronization.
     */
    @Volatile
    var customKbSyncHook: (suspend (Context, KbDatabaseManager) -> KbSyncResult)? = null

    @Synchronized
    fun getDatabaseManager(context: Context): KbDatabaseManager {
        return dbManager ?: KbDatabaseManager(context.applicationContext).also { dbManager = it }
    }

    @Synchronized
    fun getRetrievalEngine(context: Context): KbRetrievalEngine {
        return retrievalEngine ?: KbRetrievalEngine(getDatabaseManager(context)).also { retrievalEngine = it }
    }

    @Synchronized
    fun getDispatcher(context: Context): VoiceCommandDispatcher {
        return commandDispatcher ?: VoiceCommandDispatcher(
            context = context.applicationContext,
            retrievalEngine = getRetrievalEngine(context),
        ).also { commandDispatcher = it }
    }

    @Synchronized
    fun getModelManager(context: Context): VoiceModelManager {
        return modelManager ?: VoiceModelManager(context.applicationContext).also { modelManager = it }
    }

    @Synchronized
    fun getOtaSyncManager(context: Context): KbOtaSyncManager {
        return otaSyncManager ?: KbOtaSyncManager(
            context = context.applicationContext,
            dbManager = getDatabaseManager(context),
        ).also { otaSyncManager = it }
    }

    @Synchronized
    fun getLearningStore(context: Context): VoiceLearningStore {
        return learningStore ?: VoiceLearningStore(context.applicationContext).also { learningStore = it }
    }

    @Synchronized
    fun getVoiceIoEngine(context: Context): VoiceIoEngine {
        return voiceIoEngine ?: VoiceIoEngine(
            context = context.applicationContext,
            retrievalEngine = getRetrievalEngine(context),
            commandDispatcher = getDispatcher(context),
            modelManager = getModelManager(context),
            learningStore = getLearningStore(context),
        ).also { voiceIoEngine = it }
    }

    /**
     * Synchronously aborts any active Voice I/O session (AudioRecord, Whisper STT, or AudioTrack TTS)
     * if [VoiceIoEngine] has been initialized. Invoked by `AlertService` on Azan/Alarm start.
     */
    fun abortVoiceIoImmediately() {
        voiceIoEngine?.abortImmediately()
    }

    /**
     * Applies live Voice Assistant & Wake-Word setting updates ([AppSettings.voiceAssistantEnabled],
     * [AppSettings.voiceRecognitionMode], [AppSettings.wakeWordEnabled]) immediately to the running
     * [VoiceIoEngine] without requiring an app restart.
     */
    fun onVoiceSettingsChanged(
        context: Context? = null,
        s: com.nouralwelayah.clock.data.AppSettings = com.nouralwelayah.clock.data.Store.settings.value,
    ) {
        val engine = voiceIoEngine
        if (engine != null) {
            engine.onVoiceSettingsChanged(s)
        } else if (!s.voiceAssistantEnabled) {
            abortVoiceIoImmediately()
        }
    }

    /**
     * Returns true if the Voice Assistant is currently listening, processing, or speaking.
     * Does not instantiate [VoiceIoEngine] if it has not been initialized yet.
     */
    fun isSessionActive(): Boolean {
        val state = voiceIoEngine?.uiState?.value ?: return false
        return state !is VoiceUiState.Idle && state !is VoiceUiState.Error
    }

    /**
     * Loads and caches the active [KbMetadata] from [KbDatabaseManager].
     */
    suspend fun refreshMetadata(context: Context): KbMetadata? {
        val meta = getDatabaseManager(context).getMetadata()
        _metadata.value = meta
        return meta
    }

    /**
     * Executes a deterministic test query against [KbRetrievalEngine] and [VoiceCommandDispatcher]
     * completely independently of microphone permissions or `.onnx` voice models.
     */
    suspend fun testQuery(
        context: Context,
        rawQuery: String,
        minScoreThreshold: Float = KbRetrievalEngine.DEFAULT_MIN_SCORE_THRESHOLD,
    ): Pair<KbMatch?, DispatchResult> {
        val engine = getRetrievalEngine(context)
        val dispatcher = getDispatcher(context)
        val store = getLearningStore(context)
        val match = engine.retrieve(rawQuery, minScoreThreshold, learningStore = store)
        val result = dispatcher.dispatch(match)
        return match to result
    }

    /**
     * Synchronizes the offline Knowledge Base (`nour_kb.db`) via [KbOtaSyncManager]
     * (or [customKbSyncHook] if registered) and refreshes live [metadata].
     */
    suspend fun syncKnowledgeBase(context: Context): KbSyncResult = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val manager = getDatabaseManager(appCtx)

        customKbSyncHook?.let { hook ->
            return@withContext runCatching {
                val res = hook(appCtx, manager)
                refreshMetadata(appCtx)
                res
            }.getOrElse { e ->
                val meta = refreshMetadata(appCtx)
                KbSyncResult(
                    success = false,
                    messageEn = "KB sync error: ${e.message}",
                    messageAr = "خطأ أثناء مزامنة قاعدة المعرفة: ${e.message}",
                    metadata = meta,
                )
            }
        }

        val syncState = getOtaSyncManager(appCtx).syncNow()
        val meta = refreshMetadata(appCtx)
        when (syncState) {
            is KbSyncState.Success -> KbSyncResult(
                success = true,
                messageEn = "KB updated to v${syncState.newVersion} (${meta?.intentCount ?: 0} intents).",
                messageAr = "تم تحديث قاعدة المعرفة إلى الإصدار v${syncState.newVersion} (${meta?.intentCount ?: 0} أمر).",
                metadata = meta,
            )

            is KbSyncState.UpToDate -> KbSyncResult(
                success = true,
                messageEn = "Offline KB is up to date (v${meta?.kbVersion ?: "?"} • ${meta?.intentCount ?: 0} intents).",
                messageAr = "قاعدة المعرفة محدّثة وعلى آخر إصدار (v${meta?.kbVersion ?: "?"} • ${meta?.intentCount ?: 0} أمر).",
                metadata = meta,
            )

            is KbSyncState.Error -> KbSyncResult(
                success = false,
                messageEn = "KB Sync Failed: ${syncState.msg}",
                messageAr = "فشلت مزامنة قاعدة المعرفة: ${syncState.msg}",
                metadata = meta,
            )

            else -> KbSyncResult(
                success = meta != null,
                messageEn = "KB status: $syncState",
                messageAr = "حالة قاعدة المعرفة: $syncState",
                metadata = meta,
            )
        }
    }
}
