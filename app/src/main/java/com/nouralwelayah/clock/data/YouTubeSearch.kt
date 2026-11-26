package com.nouralwelayah.clock.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One search result: a video or a playlist. [id] = video id or playlist id. */
data class YtResult(val id: String, val title: String, val channel: String, val playlist: Boolean,
                    val duration: String = "", val thumb: String = "") {
    /** The thumbnail: from the search, else YouTube's standard one for a video. */
    val thumbUrl: String get() = thumb.ifEmpty { if (playlist) "" else "https://i.ytimg.com/vi/$id/mqdefault.jpg" }
}

/**
 * YouTube search with the YouTube Data API v3 and the user's own API key (Settings > YouTube search).
 * A search costs 100 of the 10,000 free units a day; the lengths 1 more. Call off the main thread.
 * Throws with a readable message (bad key, allowance used up, no internet).
 */
object YouTubeSearch {
    private const val API = "https://www.googleapis.com/youtube/v3"

    fun search(key: String, query: String, max: Int = 20): List<YtResult> {
        require(key.isNotBlank()) { "No YouTube API key yet: Settings > YouTube search" }
        val q = query.trim()
        require(q.isNotEmpty()) { "Type what to search for" }
        val j = get("$API/search?part=snippet&type=video,playlist&maxResults=$max&safeSearch=moderate&q=" +
            URLEncoder.encode(q, "UTF-8") + "&key=" + URLEncoder.encode(key.trim(), "UTF-8"))
        val items = j.optJSONArray("items") ?: return emptyList()
        val list = (0 until items.length()).mapNotNull { i ->
            val o = items.getJSONObject(i)
            val idObj = o.optJSONObject("id") ?: return@mapNotNull null
            val sn = o.optJSONObject("snippet") ?: return@mapNotNull null
            val pl = idObj.optString("kind") == "youtube#playlist"
            val id = if (pl) idObj.optString("playlistId") else idObj.optString("videoId")
            if (id.isEmpty()) return@mapNotNull null
            val th = sn.optJSONObject("thumbnails")?.let { t -> (t.optJSONObject("medium") ?: t.optJSONObject("default"))?.optString("url") }.orEmpty()
            YtResult(id, unescape(sn.optString("title")), unescape(sn.optString("channelTitle")), pl, thumb = th)
        }
        // the lengths of the videos (1 unit for all of them)
        val ids = list.filter { !it.playlist }.joinToString(",") { it.id }
        if (ids.isEmpty()) return list
        val lengths = runCatching {
            val v = get("$API/videos?part=contentDetails&id=$ids&key=" + URLEncoder.encode(key.trim(), "UTF-8")).optJSONArray("items")
            (0 until (v?.length() ?: 0)).associate { i ->
                val o = v!!.getJSONObject(i)
                o.optString("id") to duration(o.optJSONObject("contentDetails")?.optString("duration").orEmpty())
            }
        }.getOrDefault(emptyMap())
        return list.map { if (it.playlist) it.copy(duration = "Playlist") else it.copy(duration = lengths[it.id].orEmpty()) }
    }

    private fun get(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 10000
        try {
            val code = c.responseCode
            val text = (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val j = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            if (code >= 400) {
                val reason = j.optJSONObject("error")?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason").orEmpty()
                val msg = j.optJSONObject("error")?.optString("message").orEmpty()
                error(when {
                    reason == "quotaExceeded" || reason == "dailyLimitExceeded" -> "Today's YouTube search allowance is used up (it resets at midnight, Pacific time)"
                    reason == "keyInvalid" || "API key not valid" in msg -> "The YouTube API key is not valid - check it in Settings > YouTube search"
                    reason == "accessNotConfigured" || "has not been used" in msg || "disabled" in msg ->
                        "Turn on \"YouTube Data API v3\" for this key's project in the Google Cloud console"
                    reason == "keyExpired" -> "The YouTube API key has expired"
                    else -> "YouTube search failed (HTTP $code${if (msg.isNotEmpty()) ": $msg" else ""})"
                })
            }
            return j
        } catch (e: java.io.IOException) {
            error("YouTube search needs the internet")
        } finally { c.disconnect() }
    }

    /** ISO 8601 "PT1H2M3S" -> "1:02:03". */
    private fun duration(iso: String): String {
        val m = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""").find(iso) ?: return ""
        val h = m.groupValues[1].toIntOrNull() ?: 0; val mi = m.groupValues[2].toIntOrNull() ?: 0; val s = m.groupValues[3].toIntOrNull() ?: 0
        return if (h > 0) "%d:%02d:%02d".format(h, mi, s) else "%d:%02d".format(mi, s)
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">")
}
