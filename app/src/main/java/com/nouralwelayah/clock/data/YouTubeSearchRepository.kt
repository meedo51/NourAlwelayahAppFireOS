package com.nouralwelayah.clock.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Clean, playable YouTube video result parsed from YouTube's Innertube `videoRenderer` payload.
 */
data class YouTubeVideoItem(
    val videoId: String,
    val title: String,
    val channelName: String,
    val durationText: String,
    val thumbnailUrl: String = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
)

/**
 * Zero-Key YouTube Search Repository querying YouTube's Innertube search endpoint
 * (`POST https://www.youtube.com/youtubei/v1/search?prettyPrint=false`) with a standard
 * `WEB` client context (`clientName = "WEB"`, `clientVersion = "2.20260101.00.00"`).
 *
 * - Runs strictly on [Dispatchers.IO] using [OkHttpClient] with 10s connect/read timeouts.
 * - Parses `videoRenderer` items into [YouTubeVideoItem] using a lightweight, dependency-free
 *   JSON AST parser so it runs identically on Android and pure JVM unit tests.
 * - Filters out live-only ads, shorts shelves without valid 11-char `videoId`s, or malformed items,
 *   returning up to [MAX_RESULTS] (20) clean playable video results.
 */
object YouTubeSearchRepository {
    const val INNERTUBE_SEARCH_URL = "https://www.youtube.com/youtubei/v1/search?prettyPrint=false"
    const val CLIENT_NAME = "WEB"
    const val CLIENT_VERSION = "2.20260101.00.00"
    const val MAX_RESULTS = 20
    private const val TIMEOUT_SECONDS = 10L
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val VALID_VIDEO_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Queries YouTube Innertube for [query] in language [languageCode] (`"ar"` or `"en"`)
     * on [Dispatchers.IO] and returns up to [maxResults] playable [YouTubeVideoItem]s.
     */
    suspend fun search(
        query: String,
        languageCode: String = "en",
        maxResults: Int = MAX_RESULTS,
        client: OkHttpClient = httpClient,
    ): List<YouTubeVideoItem> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return@withContext emptyList()

        val hl = if (languageCode.trim().equals("ar", ignoreCase = true)) "ar" else "en"
        val requestJson = buildSearchRequestPayload(cleanQuery, hl)
        val request = Request.Builder()
            .url(INNERTUBE_SEARCH_URL)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("X-YouTube-Client-Name", "1")
            .header("X-YouTube-Client-Version", CLIENT_VERSION)
            .header(
                "User-Agent",
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
            )
            .post(requestJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("YouTube search failed (HTTP ${response.code})")
            }
            val bodyString = response.body?.string().orEmpty()
            if (bodyString.isBlank()) {
                throw IOException("Empty response from YouTube search")
            }
            parseSearchResponseJson(bodyString, maxResults)
        }
    }

    /**
     * Convenience wrapper returning a [Result] around [search].
     */
    suspend fun searchVideos(
        query: String,
        languageCode: String = "en",
        maxResults: Int = MAX_RESULTS,
        client: OkHttpClient = httpClient,
    ): Result<List<YouTubeVideoItem>> = runCatching {
        search(query, languageCode, maxResults, client)
    }

    /**
     * Builds the Innertube JSON request body for `youtubei/v1/search`.
     */
    internal fun buildSearchRequestPayload(query: String, hl: String): String {
        val escapedQuery = escapeJsonString(query)
        val escapedHl = escapeJsonString(if (hl == "ar") "ar" else "en")
        return """{"context":{"client":{"clientName":"$CLIENT_NAME","clientVersion":"$CLIENT_VERSION","hl":"$escapedHl"}},"query":"$escapedQuery"}"""
    }

    /**
     * Parses an Innertube `youtubei/v1/search` JSON response string and extracts up to [maxResults]
     * clean, playable [YouTubeVideoItem]s from `videoRenderer` nodes.
     *
     * Handles:
     * - Standard `title.runs[].text` and `title.simpleText` (both Arabic and English)
     * - Channel names from `ownerText.runs[].text`, `longBylineText.runs[].text`, or `shortBylineText.runs[].text`
     * - Duration from `lengthText.simpleText` or `lengthText.runs[].text`
     * - Filters out items without a valid 11-character `videoId`, blank titles, ad-only renderers,
     *   or unplayable live/upcoming items lacking a duration badge.
     */
    fun parseSearchResponseJson(
        jsonString: String,
        maxResults: Int = MAX_RESULTS,
    ): List<YouTubeVideoItem> {
        val trimmed = jsonString.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) return emptyList()

        val root = runCatching { MiniJsonParser(trimmed).parseValue() as? Map<*, *> }.getOrNull()
            ?: return emptyList()

        val results = mutableListOf<YouTubeVideoItem>()
        val seenIds = HashSet<String>()

        fun collectRenderers(node: Any?) {
            if (results.size >= maxResults || node == null) return
            when (node) {
                is Map<*, *> -> {
                    // Skip ad slots and promoted renderers
                    if (node.containsKey("promotedSparklesWebRenderer") ||
                        node.containsKey("adSlotRenderer") ||
                        node.containsKey("searchPyvRenderer")
                    ) {
                        return
                    }
                    val videoRenderer = node["videoRenderer"] as? Map<*, *>
                    if (videoRenderer != null) {
                        parseVideoRenderer(videoRenderer)?.let { item ->
                            if (seenIds.add(item.videoId)) {
                                results.add(item)
                            }
                        }
                    }
                    for ((key, child) in node) {
                        if (results.size >= maxResults) break
                        // Skip reels/shorts shelves that don't use standard playable videoRenderer or duplicate
                        if (key == "reelShelfRenderer" || key == "shortsLockupViewModel") continue
                        collectRenderers(child)
                    }
                }
                is List<*> -> {
                    for (element in node) {
                        if (results.size >= maxResults) break
                        collectRenderers(element)
                    }
                }
            }
        }

        collectRenderers(root)
        return results
    }

    private fun parseVideoRenderer(renderer: Map<*, *>): YouTubeVideoItem? {
        val rawId = (renderer["videoId"] as? String)?.trim().orEmpty()
        if (!VALID_VIDEO_ID_REGEX.matches(rawId)) return null

        // Filter out upcoming premieres or unplayable placeholders
        if (renderer.containsKey("upcomingEventData")) return null

        // Check badges for live-only or ad indicators
        val badges = renderer["badges"] as? List<*>
        if (badges != null) {
            for (b in badges) {
                val badgeMap = (b as? Map<*, *>)?.get("metadataBadgeRenderer") as? Map<*, *> ?: continue
                val style = (badgeMap["style"] as? String).orEmpty()
                val label = (badgeMap["label"] as? String).orEmpty()
                if (style.contains("BADGE_STYLE_TYPE_AD", ignoreCase = true) ||
                    label.equals("Ad", ignoreCase = true) ||
                    label.equals("إعلان", ignoreCase = true)
                ) {
                    return null
                }
            }
        }

        val title = extractRunsOrSimpleText(renderer["title"]).trim()
        if (title.isEmpty()) return null

        val channelName = extractRunsOrSimpleText(renderer["ownerText"])
            .ifBlank { extractRunsOrSimpleText(renderer["longBylineText"]) }
            .ifBlank { extractRunsOrSimpleText(renderer["shortBylineText"]) }
            .trim()

        val durationText = extractRunsOrSimpleText(renderer["lengthText"]).trim()
        // Exclude live-only streams or non-VOD items without a duration badge
        if (durationText.isEmpty()) return null

        return YouTubeVideoItem(
            videoId = rawId,
            title = title,
            channelName = channelName,
            durationText = durationText,
            thumbnailUrl = "https://i.ytimg.com/vi/$rawId/hqdefault.jpg",
        )
    }

    private fun extractRunsOrSimpleText(field: Any?): String {
        val map = field as? Map<*, *> ?: return (field as? String).orEmpty()
        val simple = map["simpleText"] as? String
        if (!simple.isNullOrBlank()) return simple

        val runs = map["runs"] as? List<*> ?: return ""
        val sb = StringBuilder()
        for (run in runs) {
            val runMap = run as? Map<*, *> ?: continue
            val text = runMap["text"] as? String
            if (!text.isNullOrEmpty()) {
                sb.append(text)
            }
        }
        return sb.toString()
    }

    private fun escapeJsonString(input: String): String {
        val sb = StringBuilder(input.length + 8)
        for (ch in input) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                else -> if (ch.code < 0x20) {
                    sb.append("\\u%04x".format(ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        return sb.toString()
    }

    /**
     * Self-contained recursive-descent JSON parser supporting objects, arrays, strings (with full
     * Unicode `\uXXXX` and surrogate-pair decoding for Arabic/English text), numbers, booleans, and null.
     */
    private class MiniJsonParser(private val src: String) {
        private var pos: Int = 0
        private val len: Int = src.length

        fun parseValue(): Any? {
            skipWhitespace()
            if (pos >= len) throw IllegalArgumentException("Unexpected end of JSON")
            return when (src[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                else -> parseNumber()
            }
        }

        private fun skipWhitespace() {
            while (pos < len) {
                when (src[pos]) {
                    ' ', '\t', '\n', '\r' -> pos++
                    else -> return
                }
            }
        }

        private fun parseObject(): Map<String, Any?> {
            pos++ // '{'
            skipWhitespace()
            if (pos < len && src[pos] == '}') {
                pos++
                return emptyMap()
            }
            val map = LinkedHashMap<String, Any?>()
            while (pos < len) {
                skipWhitespace()
                if (pos >= len || src[pos] != '"') {
                    throw IllegalArgumentException("Expected string key at position $pos")
                }
                val key = parseString()
                skipWhitespace()
                if (pos >= len || src[pos] != ':') {
                    throw IllegalArgumentException("Expected ':' at position $pos")
                }
                pos++ // ':'
                val value = parseValue()
                map[key] = value
                skipWhitespace()
                if (pos < len && src[pos] == ',') {
                    pos++
                    continue
                } else if (pos < len && src[pos] == '}') {
                    pos++
                    return map
                } else {
                    throw IllegalArgumentException("Expected ',' or '}' at position $pos")
                }
            }
            throw IllegalArgumentException("Unterminated JSON object")
        }

        private fun parseArray(): List<Any?> {
            pos++ // '['
            skipWhitespace()
            if (pos < len && src[pos] == ']') {
                pos++
                return emptyList()
            }
            val list = ArrayList<Any?>()
            while (pos < len) {
                list.add(parseValue())
                skipWhitespace()
                if (pos < len && src[pos] == ',') {
                    pos++
                    continue
                } else if (pos < len && src[pos] == ']') {
                    pos++
                    return list
                } else {
                    throw IllegalArgumentException("Expected ',' or ']' at position $pos")
                }
            }
            throw IllegalArgumentException("Unterminated JSON array")
        }

        private fun parseString(): String {
            pos++ // opening '"'
            val sb = StringBuilder()
            while (pos < len) {
                val c = src[pos++]
                if (c == '"') {
                    return sb.toString()
                }
                if (c == '\\') {
                    if (pos >= len) throw IllegalArgumentException("Unterminated escape")
                    when (val esc = src[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > len) throw IllegalArgumentException("Invalid unicode escape")
                            val hex = src.substring(pos, pos + 4)
                            pos += 4
                            sb.append(hex.toInt(16).toChar())
                        }
                        else -> sb.append(esc)
                    }
                } else {
                    sb.append(c)
                }
            }
            throw IllegalArgumentException("Unterminated string literal")
        }

        private fun parseBoolean(): Boolean {
            return if (src.startsWith("true", pos)) {
                pos += 4
                true
            } else if (src.startsWith("false", pos)) {
                pos += 5
                false
            } else {
                throw IllegalArgumentException("Invalid boolean at $pos")
            }
        }

        private fun parseNull(): Any? {
            if (src.startsWith("null", pos)) {
                pos += 4
                return null
            }
            throw IllegalArgumentException("Invalid null at $pos")
        }

        private fun parseNumber(): Number {
            val start = pos
            while (pos < len && src[pos] in "0123456789+-.eE") {
                pos++
            }
            val token = src.substring(start, pos)
            if (token.isEmpty()) throw IllegalArgumentException("Invalid number at $start")
            return token.toLongOrNull() ?: token.toDouble()
        }
    }
}
