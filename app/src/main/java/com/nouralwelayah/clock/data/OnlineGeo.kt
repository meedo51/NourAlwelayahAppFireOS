package com.nouralwelayah.clock.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A place found online: name, coordinates and its time zone (IANA id, "" if unknown). */
data class GeoPlace(val name: String, val label: String, val lat: Double, val lon: Double, val zone: String)

/**
 * City search on the internet (the firmware's "Online" button, which uses radar.io with an API key).
 * 1. Open-Meteo geocoding (free, no key, gives the time zone). "City, country" narrows by the part after
 *    the comma (country, region or 2-letter code). https://open-meteo.com/en/docs/geocoding-api (GeoNames)
 * 2. If that finds nothing: OpenStreetMap Nominatim (any spelling, Arabic names, villages; no time zone).
 * Call off the main thread. Throws with a readable message when the search fails.
 */
object OnlineGeo {
    private const val AGENT = "PrayerClock-Android/1.0"

    fun search(query: String, count: Int = 10): List<GeoPlace> {
        val q = query.trim()
        require(q.length >= 2) { "Enter at least 2 letters of the city name" }
        val name = q.substringBefore(',').trim()
        val hint = q.substringAfter(',', "").trim().lowercase()

        var list = runCatching { openMeteo(name, if (hint.isEmpty()) count else 30) }.getOrElse { e ->
            if (e is NoNet) throw e else emptyList<Pair<GeoPlace, String>>()
        }
        if (hint.isNotEmpty()) {
            // "sa" = a country code; longer = part of the region or country name
            val narrowed = list.filter { (p, cc) -> if (hint.length == 2) hint == cc else hint in p.label.lowercase() }
            if (narrowed.isNotEmpty()) list = narrowed
        }
        if (list.isNotEmpty()) return list.take(count).map { it.first }
        return nominatim(q, count)                    // other spellings, Arabic, addresses
    }

    private class NoNet : Exception("No internet connection")

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 10000
        c.setRequestProperty("User-Agent", AGENT)
        c.setRequestProperty("Accept-Language", "en")
        return try {
            if (c.responseCode != 200) error("Search service error: HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: java.io.IOException) {
            throw NoNet()
        } finally { c.disconnect() }
    }

    /** Places with their lowercase country code. */
    private fun openMeteo(name: String, count: Int): List<Pair<GeoPlace, String>> {
        val text = get("https://geocoding-api.open-meteo.com/v1/search?name=" + URLEncoder.encode(name, "UTF-8") +
            "&count=$count&language=en&format=json")
        val a = JSONObject(text).optJSONArray("results") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            val n = o.optString("name")
            val label = listOf(n, o.optString("admin1"), o.optString("country")).filter { it.isNotBlank() }.distinct().joinToString(", ")
            GeoPlace(n, label, o.optDouble("latitude"), o.optDouble("longitude"), o.optString("timezone")) to
                o.optString("country_code").lowercase()
        }
    }

    private fun nominatim(q: String, count: Int): List<GeoPlace> {
        val text = get("https://nominatim.openstreetmap.org/search?q=" + URLEncoder.encode(q, "UTF-8") +
            "&format=json&addressdetails=1&limit=$count&accept-language=en")
        val a = JSONArray(text)
        return (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            val ad = o.optJSONObject("address")
            val n = listOf("city", "town", "village", "hamlet", "suburb", "municipality", "county")
                .firstNotNullOfOrNull { k -> ad?.optString(k)?.takeIf { it.isNotBlank() } }
                ?: o.optString("name").ifBlank { o.optString("display_name").substringBefore(',') }
            val label = listOfNotNull(n, ad?.optString("state")?.takeIf { it.isNotBlank() },
                ad?.optString("country")?.takeIf { it.isNotBlank() }).distinct().joinToString(", ")
            GeoPlace(n, label, o.optString("lat").toDoubleOrNull() ?: 0.0, o.optString("lon").toDoubleOrNull() ?: 0.0, "")
        }
    }
}
