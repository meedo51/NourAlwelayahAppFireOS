package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager

data class FeatureConfig(
    var sampleRate: Int = 16000,
    var featureDim: Int = 80,
)

data class SileroVadModelConfig(
    var model: String = "",
    var threshold: Float = 0.5f,
    var minSilenceDuration: Float = 0.25f,
    var minSpeechDuration: Float = 0.25f,
    var windowSize: Int = 512,
    var maxSpeechDuration: Float = 5.0f,
)

data class VadModelConfig(
    var sileroVadModelConfig: SileroVadModelConfig = SileroVadModelConfig(),
    var sampleRate: Int = 16000,
    var numThreads: Int = 1,
    var provider: String = "cpu",
    var debug: Boolean = false,
)

data class SpeechSegment(
    val start: Int = 0,
    val samples: FloatArray = FloatArray(0),
)

class Vad(
    assetManager: AssetManager? = null,
    var config: VadModelConfig,
) {
    private var ptr: Long = 0L

    init {
        ptr = runCatching {
            if (assetManager != null) {
                newFromAsset(assetManager, config)
            } else {
                newFromFile(config)
            }
        }.getOrDefault(0L)
    }

    fun acceptWaveform(samples: FloatArray) {
        if (ptr != 0L) runCatching { acceptWaveform(ptr, samples) }
    }

    fun empty(): Boolean {
        return if (ptr != 0L) runCatching { empty(ptr) }.getOrDefault(true) else true
    }

    fun pop() {
        if (ptr != 0L) runCatching { pop(ptr) }
    }

    fun clear() {
        if (ptr != 0L) runCatching { clear(ptr) }
    }

    fun front(): SpeechSegment {
        return if (ptr != 0L) {
            runCatching {
                val obj = front(ptr)
                val start = (obj[0] as? Int) ?: 0
                val samples = (obj[1] as? FloatArray) ?: FloatArray(0)
                SpeechSegment(start = start, samples = samples)
            }.getOrDefault(SpeechSegment())
        } else {
            SpeechSegment()
        }
    }

    fun isSpeechDetected(): Boolean {
        return if (ptr != 0L) runCatching { isSpeechDetected(ptr) }.getOrDefault(false) else false
    }

    fun reset() {
        if (ptr != 0L) runCatching { reset(ptr) }
    }

    fun flush() {
        if (ptr != 0L) runCatching { flush(ptr) }
    }

    fun release() {
        if (ptr != 0L) {
            runCatching { delete(ptr) }
            ptr = 0L
        }
    }

    private external fun newFromAsset(assetManager: AssetManager, config: VadModelConfig): Long
    private external fun newFromFile(config: VadModelConfig): Long
    private external fun delete(ptr: Long)
    private external fun acceptWaveform(ptr: Long, samples: FloatArray)
    private external fun empty(ptr: Long): Boolean
    private external fun pop(ptr: Long)
    private external fun clear(ptr: Long)
    private external fun front(ptr: Long): Array<Any>
    private external fun isSpeechDetected(ptr: Long): Boolean
    private external fun reset(ptr: Long)
    private external fun flush(ptr: Long)

    companion object {
        init {
            runCatching { System.loadLibrary("sherpa-onnx-jni") }
        }
    }
}

data class OfflineWhisperModelConfig(
    var encoder: String = "",
    var decoder: String = "",
    var language: String = "",
    var task: String = "transcribe",
    var tailPaddings: Int = -1,
)

data class OfflineModelConfig(
    var whisper: OfflineWhisperModelConfig = OfflineWhisperModelConfig(),
    var tokens: String = "",
    var numThreads: Int = 1,
    var debug: Boolean = false,
    var provider: String = "cpu",
    var modelType: String = "",
)

data class OfflineRecognizerConfig(
    var featConfig: FeatureConfig = FeatureConfig(),
    var modelConfig: OfflineModelConfig = OfflineModelConfig(),
    var decodingMethod: String = "greedy_search",
    var maxActivePaths: Int = 4,
    var hotwordsFile: String = "",
    var hotwordsScore: Float = 1.5f,
    var ruleFsts: String = "",
    var ruleFars: String = "",
    var blankPenalty: Float = 0.0f,
)

data class OfflineRecognizerResult(
    val text: String = "",
    val tokens: Array<String> = emptyArray(),
    val timestamps: FloatArray = FloatArray(0),
    val lang: String = "",
    val emotion: String = "",
    val event: String = "",
)

class OfflineStream(internal var ptr: Long) {
    fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
        if (ptr != 0L) runCatching { acceptWaveform(ptr, samples, sampleRate) }
    }

    fun release() {
        if (ptr != 0L) {
            runCatching { delete(ptr) }
            ptr = 0L
        }
    }

    private external fun acceptWaveform(ptr: Long, samples: FloatArray, sampleRate: Int)
    private external fun delete(ptr: Long)

    companion object {
        init {
            runCatching { System.loadLibrary("sherpa-onnx-jni") }
        }
    }
}

class OfflineRecognizer(
    assetManager: AssetManager? = null,
    val config: OfflineRecognizerConfig,
) {
    private var ptr: Long = 0L

    init {
        ptr = runCatching {
            if (assetManager != null) {
                newFromAsset(assetManager, config)
            } else {
                newFromFile(config)
            }
        }.getOrDefault(0L)
    }

    fun createStream(): OfflineStream {
        val streamPtr = if (ptr != 0L) runCatching { createStream(ptr) }.getOrDefault(0L) else 0L
        return OfflineStream(streamPtr)
    }

    fun decode(stream: OfflineStream) {
        if (ptr != 0L && stream.ptr != 0L) {
            runCatching { decode(ptr, stream.ptr) }
        }
    }

    fun getResult(stream: OfflineStream): OfflineRecognizerResult {
        if (ptr != 0L && stream.ptr != 0L) {
            runCatching {
                val obj = getResult(ptr, stream.ptr)
                val text = (obj.getOrNull(0) as? String).orEmpty()
                return OfflineRecognizerResult(text = text)
            }
        }
        return OfflineRecognizerResult()
    }

    fun release() {
        if (ptr != 0L) {
            runCatching { delete(ptr) }
            ptr = 0L
        }
    }

    private external fun newFromAsset(assetManager: AssetManager, config: OfflineRecognizerConfig): Long
    private external fun newFromFile(config: OfflineRecognizerConfig): Long
    private external fun delete(ptr: Long)
    private external fun createStream(ptr: Long): Long
    private external fun decode(ptr: Long, streamPtr: Long)
    private external fun getResult(ptr: Long, streamPtr: Long): Array<Any>

    companion object {
        init {
            runCatching { System.loadLibrary("sherpa-onnx-jni") }
        }
    }
}

data class OfflineTtsVitsModelConfig(
    var model: String = "",
    var lexicon: String = "",
    var tokens: String = "",
    var dataDir: String = "",
    var dictDir: String = "",
    var noiseScale: Float = 0.667f,
    var noiseScaleW: Float = 0.8f,
    var lengthScale: Float = 1.0f,
)

data class OfflineTtsModelConfig(
    var vits: OfflineTtsVitsModelConfig = OfflineTtsVitsModelConfig(),
    var numThreads: Int = 1,
    var debug: Boolean = false,
    var provider: String = "cpu",
)

data class OfflineTtsConfig(
    var model: OfflineTtsModelConfig = OfflineTtsModelConfig(),
    var ruleFsts: String = "",
    var ruleFars: String = "",
    var maxNumSentences: Int = 1,
    var silenceScale: Float = 0.2f,
)

class GeneratedAudio(
    val samples: FloatArray,
    val sampleRate: Int,
)

class OfflineTts(
    assetManager: AssetManager? = null,
    var config: OfflineTtsConfig,
) {
    private var ptr: Long = 0L

    init {
        ptr = runCatching {
            if (assetManager != null) {
                newFromAsset(assetManager, config)
            } else {
                newFromFile(config)
            }
        }.getOrDefault(0L)
    }

    fun generate(
        text: String,
        sid: Int = 0,
        speed: Float = 1.0f,
    ): GeneratedAudio {
        if (ptr != 0L) {
            runCatching {
                val obj = generateImpl(ptr, text, sid, speed)
                val samples = (obj.getOrNull(0) as? FloatArray) ?: FloatArray(0)
                val sampleRate = (obj.getOrNull(1) as? Int) ?: 22050
                return GeneratedAudio(samples = samples, sampleRate = sampleRate)
            }
        }
        return GeneratedAudio(samples = FloatArray(0), sampleRate = 22050)
    }

    fun release() {
        if (ptr != 0L) {
            runCatching { delete(ptr) }
            ptr = 0L
        }
    }

    private external fun newFromAsset(assetManager: AssetManager, config: OfflineTtsConfig): Long
    private external fun newFromFile(config: OfflineTtsConfig): Long
    private external fun delete(ptr: Long)
    private external fun generateImpl(ptr: Long, text: String, sid: Int, speed: Float): Array<Any>

    companion object {
        init {
            runCatching { System.loadLibrary("sherpa-onnx-jni") }
        }
    }
}

data class OnlineTransducerModelConfig(
    var encoder: String = "",
    var decoder: String = "",
    var joiner: String = "",
)

data class OnlineModelConfig(
    var transducer: OnlineTransducerModelConfig = OnlineTransducerModelConfig(),
    var tokens: String = "",
    var numThreads: Int = 1,
    var debug: Boolean = false,
    var provider: String = "cpu",
    var modelType: String = "",
)

data class KeywordSpotterConfig(
    var featConfig: FeatureConfig = FeatureConfig(),
    var modelConfig: OnlineModelConfig = OnlineModelConfig(),
    var maxActivePaths: Int = 4,
    var numTrailingBlanks: Int = 1,
    var keywordsScore: Float = 1.0f,
    var keywordsThreshold: Float = 0.25f,
    var keywordsFile: String = "",
)

data class KeywordSpotterResult(
    val keyword: String = "",
    val tokens: Array<String> = emptyArray(),
    val timestamps: FloatArray = FloatArray(0),
)

class OnlineStream(internal var ptr: Long = 0L) {
    fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
        if (ptr != 0L) runCatching { acceptWaveform(ptr, samples, sampleRate) }
    }

    fun inputFinished() {
        if (ptr != 0L) runCatching { inputFinished(ptr) }
    }

    fun release() {
        if (ptr != 0L) {
            runCatching { delete(ptr) }
            ptr = 0L
        }
    }

    private external fun acceptWaveform(ptr: Long, samples: FloatArray, sampleRate: Int)
    private external fun inputFinished(ptr: Long)
    private external fun delete(ptr: Long)

    companion object {
        init {
            runCatching { System.loadLibrary("sherpa-onnx-jni") }
        }
    }
}

class KeywordSpotter(
    assetManager: AssetManager? = null,
    val config: KeywordSpotterConfig,
) {
    private var ptr: Long = 0L

    init {
        ptr = runCatching {
            if (assetManager != null) {
                newFromAsset(assetManager, config)
            } else {
                newFromFile(config)
            }
        }.getOrDefault(0L)
    }

    fun createStream(keywords: String = ""): OnlineStream {
        val streamPtr = if (ptr != 0L) runCatching { createStream(ptr, keywords) }.getOrDefault(0L) else 0L
        return OnlineStream(streamPtr)
    }

    fun decode(stream: OnlineStream) {
        if (ptr != 0L && stream.ptr != 0L) {
            runCatching { decode(ptr, stream.ptr) }
        }
    }

    fun reset(stream: OnlineStream) {
        if (ptr != 0L && stream.ptr != 0L) {
            runCatching { reset(ptr, stream.ptr) }
        }
    }

    fun isReady(stream: OnlineStream): Boolean {
        return if (ptr != 0L && stream.ptr != 0L) {
            runCatching { isReady(ptr, stream.ptr) }.getOrDefault(false)
        } else {
            false
        }
    }

    fun getResult(stream: OnlineStream): KeywordSpotterResult {
        if (ptr != 0L && stream.ptr != 0L) {
            runCatching {
                val obj = getResult(ptr, stream.ptr)
                val kw = (obj.getOrNull(0) as? String).orEmpty()
                return KeywordSpotterResult(keyword = kw)
            }
        }
        return KeywordSpotterResult()
    }

    fun release() {
        if (ptr != 0L) {
            runCatching { delete(ptr) }
            ptr = 0L
        }
    }

    private external fun newFromAsset(assetManager: AssetManager, config: KeywordSpotterConfig): Long
    private external fun newFromFile(config: KeywordSpotterConfig): Long
    private external fun delete(ptr: Long)
    private external fun createStream(ptr: Long, keywords: String): Long
    private external fun isReady(ptr: Long, streamPtr: Long): Boolean
    private external fun decode(ptr: Long, streamPtr: Long)
    private external fun reset(ptr: Long, streamPtr: Long)
    private external fun getResult(ptr: Long, streamPtr: Long): Array<Any>

    companion object {
        init {
            runCatching { System.loadLibrary("sherpa-onnx-jni") }
        }
    }
}
