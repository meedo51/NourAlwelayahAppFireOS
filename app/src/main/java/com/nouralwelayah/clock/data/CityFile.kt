package com.nouralwelayah.clock.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/**
 * The city list in PrayerClock/config/cities.txt, like the firmware's /config/cities.txt (cities.cpp):
 * one city per line "name ; latitude ; longitude ; time zone", # = comment. Created with the example cities
 * when missing; cities picked with Online are added (or updated, same name). Without "All files access" the file
 * is in the app's private storage. Time zone: IANA name, a firmware list name ("Riyadh / Makkah (+3)") or a UTC
 * offset ("+3:30", "5.5"); POSIX rules (firmware only) count as bad lines here.
 */
object CityFile {
    private const val TAG = "CityFile"
    private const val MAX = 2000

    private val _cities = MutableStateFlow(BUILTIN_CITIES)
    /** The cities of the file (the built-in ones until it is read). */
    val cities: StateFlow<List<City>> = _cities
    /** Lines ignored by the last read. */
    @Volatile var badLines = 0; private set

    private val HEADER = """
        # Prayer clock city list - one city per line:
        #     name ; latitude ; longitude ; time zone
        # Latitude: North +, South -.   Longitude: East +, West -.
        # Time zone can be:
        #   a zone name from the built-in list   e.g. Asia/Baghdad
        #   a fixed UTC offset                   e.g. +3:30   -5   5.5
        #   a POSIX rule (daylight saving)       e.g. EET-2EEST,M3.5.0/0,M10.5.0/0
        #     (POSIX signs are reversed: EET-2 means UTC+2)  - ESP32 clocks only, not the app
        # Lines starting with # are ignored. Use English city names.
        # Cities found with the Online button are added here automatically.
    """.trimIndent()

    fun file(c: Context): File = File(AppFolders.dir(c, Folder.CONFIG), "cities.txt")
    private fun tmp(c: Context) = File(AppFolders.dir(c, Folder.CONFIG), "cities.tmp")

    /** Path for messages: "/config/cities.txt" (shared folder) or "app storage". */
    fun label(c: Context): String = if (AppFolders.hasAccess(c)) "/config/cities.txt" else "cities.txt (app storage)"

    /** Creates the file with the example cities if it is missing (or restores it after a cut save). */
    @Synchronized
    private fun ensure(c: Context): File {
        val f = file(c)
        if (f.exists()) return f
        val t = tmp(c)
        if (t.exists() && t.renameTo(f)) { Log.i(TAG, "restored cities.txt from cities.tmp"); return f }
        runCatching { f.writeText(HEADER + "\n" + BUILTIN_CITIES.joinToString("") { line(it.name, it.lat, it.lon, it.zone) + "\n" }) }
            .onSuccess { AppFolders.scan(c, f); Log.i(TAG, "created ${f.path}") }
        return f
    }

    /** Reads the file again (off the main thread is best; it is small). */
    @Synchronized
    fun reload(c: Context): List<City> {
        val f = ensure(c)
        val out = ArrayList<City>()
        var bad = 0
        runCatching {
            f.readLines().forEachIndexed { i, raw ->
                if (out.size >= MAX) return@forEachIndexed
                val (city, why) = parse(raw)
                if (city != null) out += city
                else if (why.isNotEmpty()) { bad++; Log.w(TAG, "line ${i + 1} ignored ($why): ${raw.trim()}") }
            }
        }.onFailure { Log.w(TAG, "cannot read ${f.path}", it) }
        badLines = bad
        val list = if (out.isEmpty() && !f.exists()) BUILTIN_CITIES else out
        _cities.value = list
        return list
    }

    enum class Saved { FAILED, ADDED, UPDATED }

    /** Adds the city, or replaces the line with the same name (firmware citiesSave). */
    fun save(c: Context, name: String, lat: Double, lon: Double, zone: String): Saved {
        val n = name.replace(';', ',').trim()
        if (n.isEmpty() || zone.isBlank()) return Saved.FAILED
        val r = rewrite(c, n, line(n, lat, lon, zone))
        reload(c)
        return when { r < 0 -> Saved.FAILED; r > 0 -> Saved.UPDATED; else -> Saved.ADDED }
    }

    /** Removes the line(s) with this name. */
    fun delete(c: Context, name: String): Boolean {
        val r = rewrite(c, name.trim(), null)
        reload(c)
        return r > 0
    }

    /** "Added to the cities list" etc. for messages. */
    fun savedText(r: Saved) = when (r) {
        Saved.ADDED -> "added to the cities list"
        Saved.UPDATED -> "updated in the cities list"
        Saved.FAILED -> "could not write the cities list"
    }

    private fun line(name: String, lat: Double, lon: Double, zone: String) =
        String.format(Locale.US, "%s; %.4f; %.4f; %s", name, lat, lon, zone)

    /** Copies the file line by line; lines with this name become [repl] (or go). Matches, or -1. */
    @Synchronized
    private fun rewrite(c: Context, name: String, repl: String?): Int {
        val f = ensure(c)
        val t = tmp(c)
        return runCatching {
            var found = 0
            val lines = if (f.exists()) f.readLines() else emptyList()
            val sb = StringBuilder()
            for (l in lines) {
                val city = parse(l).first
                if (city != null && city.name.equals(name, ignoreCase = true)) {
                    if (repl != null && found == 0) sb.append(repl).append('\n')
                    found++
                    continue
                }
                sb.append(l).append('\n')
            }
            if (repl != null && found == 0) sb.append(repl).append('\n')
            t.writeText(sb.toString())
            if (f.exists() && !f.delete()) error("cannot replace ${f.path}")
            if (!t.renameTo(f)) error("rename failed")
            AppFolders.scan(c, f)
            found
        }.getOrElse { Log.w(TAG, "cannot write the cities list", it); -1 }
    }

    /** One line -> the city, or null and why ("" for a comment / empty line). */
    private fun parse(raw: String): Pair<City?, String> {
        val l = raw.removePrefix("﻿").trim()                  // BOM from Notepad
        if (l.isEmpty() || l.startsWith("#")) return null to ""
        val p = l.split(';', limit = 4)
        if (p.size < 4) return null to "needs 4 parts separated by ;"
        val name = p[0].trim()
        val lat = p[1].trim().toDoubleOrNull()
        val lon = p[2].trim().toDoubleOrNull()
        val tz = p[3].trim()
        if (name.isEmpty()) return null to "empty name"
        if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null to "bad latitude / longitude"
        val zone = zoneOf(tz) ?: return null to "unknown time zone \"$tz\""
        return City(name.take(31), lat, lon, zone) to ""
    }

    /** Time zone text -> zone id ("Asia/Riyadh", "+03:30"), null if unknown (or a POSIX rule). */
    fun zoneOf(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        runCatching { if ('/' in t || t == "UTC") return ZoneId.of(t).id }
        com.nouralwelayah.clock.web.WebRemote.TZ_LIST.firstOrNull { it.first.equals(t, ignoreCase = true) }?.let { return it.second }
        var s = t
        if (s.startsWith("UTC", true) || s.startsWith("GMT", true)) s = s.drop(3).trim()
        if (s.isEmpty()) return "UTC"
        if (s.any { it !in "+-0123456789.: " }) return null
        return runCatching {
            val neg = s.startsWith("-")
            val b = s.trimStart('+', '-').trim()
            val mins = if (':' in b) b.substringBefore(':').toInt() * 60 + b.substringAfter(':').toInt()
                       else Math.round(b.toDouble() * 60).toInt()
            if (mins > 14 * 60) null else ZoneOffset.ofTotalSeconds((if (neg) -mins else mins) * 60).id
        }.getOrNull()
    }
}
