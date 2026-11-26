package com.nouralwelayah.clock.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.nouralwelayah.clock.data.AppFolders
import com.nouralwelayah.clock.kiosk.KioskPolicy
import com.nouralwelayah.clock.system.FileSystemManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Production readiness state for the offline `sherpa-onnx` Voice Assistant pipeline.
 */
sealed class VoiceReadinessState {
    data object Ready : VoiceReadinessState()
    data object Initializing : VoiceReadinessState()
    data class MissingModels(val missingFiles: List<String>) : VoiceReadinessState()
    data object MissingPermission : VoiceReadinessState()
    data class Error(val message: String) : VoiceReadinessState()
}

/**
 * Resolved file or asset paths for an optional sherpa-onnx KeywordSpotter model (`voice_models/kws/`):
 * - `encoder.int8.onnx`
 * - `decoder.int8.onnx`
 * - `joiner.int8.onnx`
 * - `tokens.txt`
 * - `keywords.txt`
 */
data class KwsModelPaths(
    val encoderPath: String,
    val decoderPath: String,
    val joinerPath: String,
    val tokensPath: String,
    val keywordsFilePath: String,
    val fromAssets: Boolean = false,
)

/**
 * Snapshot of resolved on-disk model paths after [VoiceModelManager] completes dual-source resolution.
 *
 * [kwsPaths] is optional: when `voice_models/kws/` is not installed, [missingFiles] remains unaffected
 * and manual microphone push-to-talk continues to operate normally.
 */
data class ResolvedVoiceModels(
    val voiceModelsDir: File,
    val vadModelFile: File?,
    val whisperPaths: WhisperModelPaths?,
    val ttsArPaths: VitsTtsModelPaths?,
    val ttsEnPaths: VitsTtsModelPaths?,
    val kwsPaths: KwsModelPaths? = null,
    val missingFiles: List<String>,
) {
    val hasSttModels: Boolean
        get() = vadModelFile != null && whisperPaths != null

    val hasKwsModels: Boolean
        get() = kwsPaths != null

    val hasAllModels: Boolean
        get() = missingFiles.isEmpty() &&
            vadModelFile != null &&
            whisperPaths != null &&
            ttsArPaths != null &&
            ttsEnPaths != null
}

/**
 * Resilient Dual-Source Model & Asset Loader for the Nour Alwelayah Offline Voice Assistant.
 *
 * Resolution Strategy:
 * 1. **Primary (External Storage)**: Checks live external storage at
 *    `FileSystemManager.getSubdirectory(context, "voice_models")`
 *    (`<externalFilesDir>/NourAlwelayah/voice_models/`, plus shared `/sdcard/NourAlwelayah/voice_models/`
 *    when All-Files-Access is granted) so technicians can provision `.onnx` weights via `adb push`
 *    or OTA without bloating the APK.
 * 2. **Fallback (Bundled APK Assets)**: Any file or directory (`silero_vad.onnx`, Whisper INT8
 *    encoder/decoder/tokens, Arabic & English Piper/VITS `.onnx`/`tokens.txt`/`espeak-ng-data`, and
 *    optional `kws/` wake-word files) not present in external storage is checked in `assets/voice_models/`
 *    and atomically extracted to external/internal storage on [Dispatchers.IO].
 * 3. **Crash Prevention**: Exposes [readinessState] (`StateFlow<VoiceReadinessState>`),
 *    [missingModelFiles], and [kwsReady] so [VoiceIoEngine] never invokes native `sherpa-onnx`
 *    constructors when required binary files are missing.
 */
class VoiceModelManager(context: Context) {

    private val appContext: Context = context.applicationContext
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val resolveMutex = Mutex()

    private val _readinessState = MutableStateFlow<VoiceReadinessState>(VoiceReadinessState.Initializing)
    val readinessState: StateFlow<VoiceReadinessState> = _readinessState.asStateFlow()

    private val _missingModelFiles = MutableStateFlow<List<String>>(emptyList())
    val missingModelFiles: StateFlow<List<String>> = _missingModelFiles.asStateFlow()

    private val _kwsReady = MutableStateFlow(false)
    val kwsReady: StateFlow<Boolean> = _kwsReady.asStateFlow()

    @Volatile
    private var latestResolvedModels: ResolvedVoiceModels? = null

    init {
        refreshReadinessAsync()
    }

    /**
     * Checks whether [Manifest.permission.RECORD_AUDIO] is currently granted.
     * If running as Device Owner in Kiosk mode, attempts silent auto-grant via [KioskPolicy].
     */
    fun hasRecordAudioPermission(): Boolean {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }
        if (KioskPolicy.tryAutoGrantRecordAudioPermission(appContext)) {
            return ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        }
        return false
    }

    /**
     * Returns the most recently resolved model paths snapshot, or `null` if resolution has not run yet.
     */
    fun getCachedResolvedModels(): ResolvedVoiceModels? = latestResolvedModels

    /**
     * Launches an asynchronous model resolution and readiness check on [Dispatchers.IO].
     */
    fun refreshReadinessAsync() {
        managerScope.launch {
            verifyAndPrepareModels()
        }
    }

    /**
     * Synchronously (in a suspending manner on [Dispatchers.IO]) executes the dual-source
     * resolution strategy, extracts any bundled `assets/voice_models/` files if needed,
     * and updates [readinessState] and [kwsReady].
     */
    suspend fun verifyAndPrepareModels(): VoiceReadinessState = withContext(Dispatchers.IO) {
        resolveMutex.withLock {
            _readinessState.value = VoiceReadinessState.Initializing
            try {
                val primaryDir = FileSystemManager.getSubdirectory(
                    appContext,
                    FileSystemManager.HIDDEN_VOICE_MODELS_DIR,
                )
                if (!primaryDir.exists() && !primaryDir.mkdirs()) {
                    val err = "Unable to create voice_models directory at ${primaryDir.absolutePath}"
                    Log.e(TAG, err)
                    val state = VoiceReadinessState.Error(err)
                    _readinessState.value = state
                    return@withLock state
                }

                // 0. If shared storage (/sdcard/NourAlwelayah/voice_models) has files pushed via USB/ADB,
                //    include it as an external search root alongside primaryDir.
                val sharedDir = if (AppFolders.hasAccess(appContext)) {
                    File(AppFolders.root(), FileSystemManager.HIDDEN_VOICE_MODELS_DIR).takeIf { it.isDirectory }
                } else {
                    null
                }
                val searchRoots = listOfNotNull(primaryDir, sharedDir).distinctBy { dir ->
                    runCatching { dir.canonicalPath }.getOrElse { dir.absolutePath }
                }

                val assets = appContext.assets

                // 1. Extract any bundled assets/voice_models/* into primaryDir if not already on external storage
                extractBundledVoiceAssetsIfNeeded(assets, primaryDir, searchRoots)

                // 2. Resolve each required component from disk
                val missing = mutableListOf<String>()

                // 2A. Silero VAD (silero_vad.onnx)
                val vadFile = resolveExistingFile(searchRoots, "silero_vad.onnx")
                if (vadFile == null) {
                    missing.add(CANONICAL_VAD_FILE)
                }

                // 2B. Whisper INT8 STT (encoder, decoder, tokens)
                val whisperResult = resolveWhisperFromDisk(searchRoots)
                val whisperPaths = whisperResult.first
                missing.addAll(whisperResult.second)

                // 2C. Arabic Piper/VITS TTS (tts_ar/model.onnx, tts_ar/tokens.txt, tts_ar/espeak-ng-data)
                val ttsArResult = resolveVitsTtsFromDisk(searchRoots, "ar")
                val ttsArPaths = ttsArResult.first
                missing.addAll(ttsArResult.second)

                // 2D. English Piper/VITS TTS (tts_en/model.onnx, tts_en/tokens.txt, tts_en/espeak-ng-data)
                val ttsEnResult = resolveVitsTtsFromDisk(searchRoots, "en")
                val ttsEnPaths = ttsEnResult.first
                missing.addAll(ttsEnResult.second)

                // 2E. Optional Wake-Word KeywordSpotter ("Welayah") under voice_models/kws/
                //     Does NOT add to `missing` so manual mic usage is never blocked when KWS is absent.
                val kwsPaths = resolveKwsFromDisk(searchRoots)

                val resolved = ResolvedVoiceModels(
                    voiceModelsDir = primaryDir,
                    vadModelFile = vadFile,
                    whisperPaths = whisperPaths,
                    ttsArPaths = ttsArPaths,
                    ttsEnPaths = ttsEnPaths,
                    kwsPaths = kwsPaths,
                    missingFiles = missing.toList(),
                )
                latestResolvedModels = resolved
                _missingModelFiles.value = resolved.missingFiles
                _kwsReady.value = resolved.hasKwsModels

                val hasMicPermission = hasRecordAudioPermission()

                val nextState = when {
                    resolved.missingFiles.isNotEmpty() -> VoiceReadinessState.MissingModels(resolved.missingFiles)
                    !hasMicPermission -> VoiceReadinessState.MissingPermission
                    else -> VoiceReadinessState.Ready
                }
                _readinessState.value = nextState
                nextState
            } catch (e: Throwable) {
                Log.e(TAG, "Error verifying voice models", e)
                _kwsReady.value = false
                val errState = VoiceReadinessState.Error(e.message ?: "Failed to verify voice models")
                _readinessState.value = errState
                errState
            }
        }
    }

    /**
     * Checks `assets/voice_models/` and extracts any bundled model files to [primaryDir]
     * if they do not already exist in [searchRoots].
     */
    private fun extractBundledVoiceAssetsIfNeeded(
        assets: AssetManager,
        primaryDir: File,
        searchRoots: List<File>,
    ) {
        // Silero VAD
        if (resolveExistingFile(searchRoots, "silero_vad.onnx") == null &&
            assetFileExists(assets, "$ASSET_ROOT/silero_vad.onnx")
        ) {
            extractSingleAsset(
                assets = assets,
                assetPath = "$ASSET_ROOT/silero_vad.onnx",
                destFile = File(primaryDir, "silero_vad.onnx"),
            )
        }

        // Whisper INT8 models (root encoder.int8.onnx/decoder.int8.onnx/tokens.txt or whisper/<prefix>-*.onnx)
        if (resolveWhisperFromDisk(searchRoots).first == null) {
            val rootEnc = "$ASSET_ROOT/encoder.int8.onnx"
            val rootDec = "$ASSET_ROOT/decoder.int8.onnx"
            val rootTok = "$ASSET_ROOT/tokens.txt"
            if (assetFileExists(assets, rootEnc) &&
                assetFileExists(assets, rootDec) &&
                assetFileExists(assets, rootTok)
            ) {
                extractSingleAsset(assets, rootEnc, File(primaryDir, "encoder.int8.onnx"))
                extractSingleAsset(assets, rootDec, File(primaryDir, "decoder.int8.onnx"))
                extractSingleAsset(assets, rootTok, File(primaryDir, "tokens.txt"))
            } else {
                val prefixes = listOf("tiny", "base", "whisper-tiny", "whisper-base")
                for (prefix in prefixes) {
                    val encAsset = "$ASSET_ROOT/whisper/$prefix-encoder.int8.onnx"
                    val decAsset = "$ASSET_ROOT/whisper/$prefix-decoder.int8.onnx"
                    val tokAsset = "$ASSET_ROOT/whisper/$prefix-tokens.txt"
                    if (assetFileExists(assets, encAsset) &&
                        assetFileExists(assets, decAsset) &&
                        assetFileExists(assets, tokAsset)
                    ) {
                        val whisperDir = File(primaryDir, "whisper")
                        extractSingleAsset(assets, encAsset, File(whisperDir, "$prefix-encoder.int8.onnx"))
                        extractSingleAsset(assets, decAsset, File(whisperDir, "$prefix-decoder.int8.onnx"))
                        extractSingleAsset(assets, tokAsset, File(whisperDir, "$prefix-tokens.txt"))
                        break
                    }
                }
            }
        }

        // Arabic & English Piper/VITS TTS models
        for (lang in listOf("ar", "en")) {
            if (resolveVitsTtsFromDisk(searchRoots, lang).first == null) {
                val assetBase = "$ASSET_ROOT/tts_$lang"
                val modelAsset = "$assetBase/model.onnx"
                val tokensAsset = "$assetBase/tokens.txt"
                if (assetFileExists(assets, modelAsset) && assetFileExists(assets, tokensAsset)) {
                    val ttsDir = File(primaryDir, "tts_$lang")
                    extractSingleAsset(assets, modelAsset, File(ttsDir, "model.onnx"))
                    extractSingleAsset(assets, tokensAsset, File(ttsDir, "tokens.txt"))
                    if (assetFileExists(assets, "$assetBase/lexicon.txt")) {
                        extractSingleAsset(assets, "$assetBase/lexicon.txt", File(ttsDir, "lexicon.txt"))
                    }
                    extractAssetDirectoryRecursive(
                        assets = assets,
                        assetDirPath = "$assetBase/espeak-ng-data",
                        destDir = File(ttsDir, "espeak-ng-data"),
                    )
                }
            }
        }

        // Optional Wake-Word KeywordSpotter ("Welayah") models under assets/voice_models/kws/
        if (resolveKwsFromDisk(searchRoots) == null) {
            val kwsAssetBase = "$ASSET_ROOT/kws"
            val encAsset = "$kwsAssetBase/encoder.int8.onnx"
            val decAsset = "$kwsAssetBase/decoder.int8.onnx"
            val joinerAsset = "$kwsAssetBase/joiner.int8.onnx"
            val tokAsset = "$kwsAssetBase/tokens.txt"
            val kwAsset = "$kwsAssetBase/keywords.txt"
            if (assetFileExists(assets, encAsset) &&
                assetFileExists(assets, decAsset) &&
                assetFileExists(assets, joinerAsset) &&
                assetFileExists(assets, tokAsset) &&
                assetFileExists(assets, kwAsset)
            ) {
                val kwsDir = File(primaryDir, "kws")
                extractSingleAsset(assets, encAsset, File(kwsDir, "encoder.int8.onnx"))
                extractSingleAsset(assets, decAsset, File(kwsDir, "decoder.int8.onnx"))
                extractSingleAsset(assets, joinerAsset, File(kwsDir, "joiner.int8.onnx"))
                extractSingleAsset(assets, tokAsset, File(kwsDir, "tokens.txt"))
                extractSingleAsset(assets, kwAsset, File(kwsDir, "keywords.txt"))
            }
        }
        syncBundledKeywordsIfChanged(assets, primaryDir, searchRoots)
    }

    /**
     * Ensures `<primaryDir>/kws/keywords.txt` stays synchronized with the APK's bundled
     * `assets/voice_models/kws/keywords.txt` across app updates, while respecting any explicit
     * technician override placed in shared storage (`/sdcard/NourAlwelayah/voice_models/kws/keywords.txt`).
     */
    private fun syncBundledKeywordsIfChanged(
        assets: AssetManager,
        primaryDir: File,
        searchRoots: List<File>,
    ) {
        val kwAssetPath = "$ASSET_ROOT/kws/keywords.txt"
        if (!assetFileExists(assets, kwAssetPath)) return

        val primaryCanonical = runCatching { primaryDir.canonicalPath }.getOrElse { primaryDir.absolutePath }
        val hasSharedOverride = searchRoots.any { root ->
            val rootCanonical = runCatching { root.canonicalPath }.getOrElse { root.absolutePath }
            if (rootCanonical == primaryCanonical) {
                false
            } else {
                val sharedKw = File(root, "kws/keywords.txt")
                sharedKw.isFile && sharedKw.length() > 0L
            }
        }
        if (hasSharedOverride) {
            return
        }

        val kwsDir = File(primaryDir, "kws")
        val targetKwFile = File(kwsDir, "keywords.txt")
        try {
            val bundledBytes = assets.open(kwAssetPath).use { it.readBytes() }
            if (bundledBytes.isEmpty()) return

            val needsUpdate = !targetKwFile.isFile ||
                targetKwFile.length() != bundledBytes.size.toLong() ||
                !targetKwFile.readBytes().contentEquals(bundledBytes)

            if (needsUpdate) {
                if (!kwsDir.exists() && !kwsDir.mkdirs()) return
                val tmpFile = File(kwsDir, "keywords.txt.tmp")
                FileOutputStream(tmpFile).use { output ->
                    output.write(bundledBytes)
                    output.fd.sync()
                }
                if (targetKwFile.exists()) targetKwFile.delete()
                if (!tmpFile.renameTo(targetKwFile)) {
                    tmpFile.copyTo(targetKwFile, overwrite = true)
                    tmpFile.delete()
                }
                Log.i(TAG, "Synchronized bundled kws/keywords.txt -> ${targetKwFile.absolutePath}")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to sync bundled kws/keywords.txt", e)
        }
    }

    private fun resolveExistingFile(roots: List<File>, relativePath: String): File? {
        for (root in roots) {
            val candidate = FileSystemManager.resolveSafePath(root, relativePath)
            if (candidate != null && candidate.isFile && candidate.length() > 0L) {
                return candidate
            }
        }
        return null
    }

    /**
     * Resolves Whisper INT8 encoder, decoder, and tokens from [roots], returning either
     * a complete [WhisperModelPaths] or the exact list of missing Whisper filenames.
     */
    private fun resolveWhisperFromDisk(roots: List<File>): Pair<WhisperModelPaths?, List<String>> {
        var foundEncoder: File? = null
        var foundDecoder: File? = null
        var foundTokens: File? = null

        for (root in roots) {
            val candidateDirs = listOf(File(root, "whisper"), root)
            for (dir in candidateDirs) {
                if (!dir.isDirectory) continue
                val files = dir.listFiles().orEmpty()
                if (foundEncoder == null) {
                    foundEncoder = files.firstOrNull { f ->
                        f.isFile && f.length() > 0L &&
                            f.name.endsWith(".onnx", ignoreCase = true) &&
                            f.name.contains("encoder", ignoreCase = true)
                    }
                }
                if (foundDecoder == null) {
                    foundDecoder = files.firstOrNull { f ->
                        f.isFile && f.length() > 0L &&
                            f.name.endsWith(".onnx", ignoreCase = true) &&
                            f.name.contains("decoder", ignoreCase = true)
                    }
                }
                if (foundTokens == null) {
                    foundTokens = files.firstOrNull { f ->
                        f.isFile && f.length() > 0L &&
                            f.name.endsWith("tokens.txt", ignoreCase = true)
                    }
                }
            }
        }

        val missing = mutableListOf<String>()
        if (foundEncoder == null) missing.add(CANONICAL_WHISPER_ENCODER)
        if (foundDecoder == null) missing.add(CANONICAL_WHISPER_DECODER)
        if (foundTokens == null) missing.add(CANONICAL_WHISPER_TOKENS)

        return if (foundEncoder != null && foundDecoder != null && foundTokens != null) {
            WhisperModelPaths(
                encoderPath = foundEncoder.absolutePath,
                decoderPath = foundDecoder.absolutePath,
                tokensPath = foundTokens.absolutePath,
                fromAssets = false,
            ) to emptyList()
        } else {
            null to missing
        }
    }

    /**
     * Resolves Piper/VITS TTS model, tokens, and `espeak-ng-data` for [lang] (`"ar"` or `"en"`)
     * from [roots], returning either a valid [VitsTtsModelPaths] or the list of missing files.
     */
    private fun resolveVitsTtsFromDisk(
        roots: List<File>,
        lang: String,
    ): Pair<VitsTtsModelPaths?, List<String>> {
        var foundModel: File? = null
        var foundTokens: File? = null
        var foundLexicon: File? = null
        var foundEspeakDir: File? = null

        for (root in roots) {
            val subDir = File(root, "tts_$lang")
            if (!subDir.isDirectory) continue
            val files = subDir.listFiles().orEmpty()
            if (foundModel == null) {
                foundModel = files.firstOrNull { f ->
                    f.isFile && f.length() > 0L && f.name.endsWith(".onnx", ignoreCase = true)
                }
            }
            if (foundTokens == null) {
                foundTokens = files.firstOrNull { f ->
                    f.isFile && f.length() > 0L && f.name.endsWith("tokens.txt", ignoreCase = true)
                }
            }
            if (foundLexicon == null) {
                foundLexicon = files.firstOrNull { f ->
                    f.isFile && f.length() > 0L && f.name.equals("lexicon.txt", ignoreCase = true)
                }
            }
            if (foundEspeakDir == null) {
                foundEspeakDir = File(subDir, "espeak-ng-data").takeIf {
                    it.isDirectory && !it.listFiles().isNullOrEmpty()
                } ?: File(root, "espeak-ng-data").takeIf {
                    it.isDirectory && !it.listFiles().isNullOrEmpty()
                }
            }
        }

        val missing = mutableListOf<String>()
        if (foundModel == null) missing.add("tts_$lang/model.onnx")
        if (foundTokens == null) missing.add("tts_$lang/tokens.txt")
        if (foundEspeakDir == null && foundLexicon == null) {
            missing.add("tts_$lang/espeak-ng-data")
        }

        return if (foundModel != null && foundTokens != null && (foundEspeakDir != null || foundLexicon != null)) {
            VitsTtsModelPaths(
                modelPath = foundModel.absolutePath,
                tokensPath = foundTokens.absolutePath,
                dataDirPath = foundEspeakDir?.absolutePath.orEmpty(),
                lexiconPath = foundLexicon?.absolutePath.orEmpty(),
                fromAssets = false,
            ) to emptyList()
        } else {
            null to missing
        }
    }

    /**
     * Resolves optional Wake-Word (`"Welayah"`) `KeywordSpotter` transducer files under `voice_models/kws/`:
     * - `encoder.int8.onnx` (or `*encoder*.onnx`)
     * - `decoder.int8.onnx` (or `*decoder*.onnx`)
     * - `joiner.int8.onnx` (or `*joiner*.onnx`)
     * - `tokens.txt`
     * - `keywords.txt`
     *
     * Returns `null` if `voice_models/kws/` or any of the 5 files are absent without blocking manual mic usage.
     */
    private fun resolveKwsFromDisk(roots: List<File>): KwsModelPaths? {
        var foundEncoder: File? = null
        var foundDecoder: File? = null
        var foundJoiner: File? = null
        var foundTokens: File? = null
        var foundKeywords: File? = null

        for (root in roots) {
            val kwsDir = File(root, "kws")
            if (!kwsDir.isDirectory) continue
            val files = kwsDir.listFiles().orEmpty()

            if (foundEncoder == null) {
                foundEncoder = File(kwsDir, "encoder.int8.onnx").takeIf { it.isFile && it.length() > 0L }
                    ?: files.firstOrNull { f ->
                        f.isFile && f.length() > 0L &&
                            f.name.endsWith(".onnx", ignoreCase = true) &&
                            f.name.contains("encoder", ignoreCase = true)
                    }
            }
            if (foundDecoder == null) {
                foundDecoder = File(kwsDir, "decoder.int8.onnx").takeIf { it.isFile && it.length() > 0L }
                    ?: files.firstOrNull { f ->
                        f.isFile && f.length() > 0L &&
                            f.name.endsWith(".onnx", ignoreCase = true) &&
                            f.name.contains("decoder", ignoreCase = true)
                    }
            }
            if (foundJoiner == null) {
                foundJoiner = File(kwsDir, "joiner.int8.onnx").takeIf { it.isFile && it.length() > 0L }
                    ?: files.firstOrNull { f ->
                        f.isFile && f.length() > 0L &&
                            f.name.endsWith(".onnx", ignoreCase = true) &&
                            f.name.contains("joiner", ignoreCase = true)
                    }
            }
            if (foundTokens == null) {
                foundTokens = File(kwsDir, "tokens.txt").takeIf { it.isFile && it.length() > 0L }
                    ?: files.firstOrNull { f ->
                        f.isFile && f.length() > 0L &&
                            f.name.endsWith("tokens.txt", ignoreCase = true)
                    }
            }
            if (foundKeywords == null) {
                foundKeywords = File(kwsDir, "keywords.txt").takeIf { it.isFile && it.length() > 0L }
                    ?: files.firstOrNull { f ->
                        f.isFile && f.length() > 0L &&
                            f.name.endsWith("keywords.txt", ignoreCase = true)
                    }
            }
        }

        return if (
            foundEncoder != null &&
            foundDecoder != null &&
            foundJoiner != null &&
            foundTokens != null &&
            foundKeywords != null
        ) {
            KwsModelPaths(
                encoderPath = foundEncoder.absolutePath,
                decoderPath = foundDecoder.absolutePath,
                joinerPath = foundJoiner.absolutePath,
                tokensPath = foundTokens.absolutePath,
                keywordsFilePath = foundKeywords.absolutePath,
                fromAssets = false,
            )
        } else {
            null
        }
    }

    private fun assetFileExists(assets: AssetManager, assetPath: String): Boolean {
        return try {
            assets.open(assetPath).use { true }
        } catch (_: Throwable) {
            false
        }
    }

    private fun extractSingleAsset(
        assets: AssetManager,
        assetPath: String,
        destFile: File,
    ): Boolean {
        val parent = destFile.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false
        val tmpFile = File(parent, "${destFile.name}.extract_tmp")
        return try {
            assets.open(assetPath).use { input ->
                FileOutputStream(tmpFile).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            if (destFile.exists()) destFile.delete()
            if (!tmpFile.renameTo(destFile)) {
                tmpFile.copyTo(destFile, overwrite = true)
                tmpFile.delete()
            }
            Log.i(TAG, "Extracted bundled voice asset $assetPath -> ${destFile.absolutePath}")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to extract voice asset $assetPath", e)
            tmpFile.delete()
            false
        }
    }

    private fun extractAssetDirectoryRecursive(
        assets: AssetManager,
        assetDirPath: String,
        destDir: File,
    ) {
        val children = runCatching { assets.list(assetDirPath) }.getOrNull() ?: return
        if (children.isEmpty()) {
            // Leaf file if openable
            if (assetFileExists(assets, assetDirPath)) {
                extractSingleAsset(assets, assetDirPath, destDir)
            }
            return
        }
        if (!destDir.exists()) destDir.mkdirs()
        for (child in children) {
            val childAssetPath = "$assetDirPath/$child"
            val grandChildren = runCatching { assets.list(childAssetPath) }.getOrNull()
            if (!grandChildren.isNullOrEmpty()) {
                extractAssetDirectoryRecursive(assets, childAssetPath, File(destDir, child))
            } else if (assetFileExists(assets, childAssetPath)) {
                val target = File(destDir, child)
                if (!target.isFile || target.length() == 0L) {
                    extractSingleAsset(assets, childAssetPath, target)
                }
            }
        }
    }

    companion object {
        private const val TAG = "VoiceModelManager"
        const val ASSET_ROOT = "voice_models"

        const val CANONICAL_VAD_FILE = "silero_vad.onnx"
        const val CANONICAL_WHISPER_ENCODER = "whisper/tiny-encoder.int8.onnx"
        const val CANONICAL_WHISPER_DECODER = "whisper/tiny-decoder.int8.onnx"
        const val CANONICAL_WHISPER_TOKENS = "whisper/tiny-tokens.txt"
    }
}
