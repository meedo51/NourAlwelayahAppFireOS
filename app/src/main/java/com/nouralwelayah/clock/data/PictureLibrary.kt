package com.nouralwelayah.clock.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.File

/**
 * Clock screen pictures (tablet), like the firmware's [AZBG] /images and [CLKBG] /wallpaper folders.
 * In PrayerClock/images and PrayerClock/wallpaper (AppFolders); picked pictures are copied in.
 * Azan pictures: a name containing fajr / dhuhr / maghrib is only for that azan (random if several);
 * other names are general pictures, for a prayer without its own. Never the same picture twice in a row.
 */
object PictureLibrary {
    enum class Kind(val folder: String) { AZAN("azanpics"), WALLPAPER("wallpaper") }

    /** Azan picture name prefixes, index = azan (0 Fajr, 1 Dhuhr, 2 Maghrib). */
    val PRAYER_KEYS = listOf("fajr", "dhuhr", "maghrib")

    private fun folder(k: Kind) = if (k == Kind.AZAN) Folder.IMAGES else Folder.WALLPAPER
    private fun dir(c: Context, k: Kind) = AppFolders.dir(c, folder(k))

    /** Pictures only (the firmware's .bin pictures copied from its SD card are skipped). */
    fun list(c: Context, k: Kind): List<File> = AppFolders.files(c, folder(k)).filter(AppFolders::isImage)

    /** Which azan a picture belongs to (its name contains fajr / dhuhr / maghrib, like the firmware), -1 = general. */
    fun prayerOf(f: File): Int = f.name.lowercase().let { n -> PRAYER_KEYS.indexOfFirst { it in n } }

    fun shownName(f: File): String = f.name

    /** Copies a picked picture into the library. For an azan picture, prayer >= 0 ties it to that azan. */
    fun add(c: Context, k: Kind, uri: Uri, prayer: Int = -1): File? = runCatching {
        val shown = c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "picture.jpg"
        val clean = shown.replace(Regex("""[\\/:*?"<>|\x00-\x1f]"""), "_").trim().ifEmpty { "picture.jpg" }
        // no prayer given: a name containing fajr / dhuhr / maghrib is tied to that azan anyway
        val f = unique(dir(c, k), if (k == Kind.AZAN && prayer in 0..2) "${PRAYER_KEYS[prayer]}_$clean" else clean)
        val tmp = File(f.parentFile, ".${f.name}.part")
        c.contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        check(tmp.renameTo(f))
        AppFolders.scan(c, f)
        f
    }.getOrNull()

    /** Ties an azan picture to a prayer (-1 = all prayers) by renaming it. */
    fun setPrayer(f: File, prayer: Int): File {
        // the name without any prayer word (and the "_" this app puts after it)
        val base = PRAYER_KEYS.fold(f.name) { n, k -> n.replace(Regex("${k}[_ -]?", RegexOption.IGNORE_CASE), "") }
            .trim('_', ' ', '-').ifEmpty { "picture." + f.extension }
        val target = unique(f.parentFile!!, if (prayer in 0..2) "${PRAYER_KEYS[prayer]}_$base" else base)
        return if (f.renameTo(target)) target else f
    }

    fun delete(f: File) { f.delete() }

    private fun unique(d: File, name: String): File {
        val base = name.substringBeforeLast('.', name)
        val ext = if ('.' in name) "." + name.substringAfterLast('.') else ""
        var f = File(d, name)
        var n = 2
        while (f.exists()) f = File(d, "$base ($n)$ext").also { n++ }
        return f
    }

    /** A random picture: the prayer's own, else the general ones, else any (prayer -1 = any). */
    fun pick(c: Context, k: Kind, prayer: Int, last: File?): File? {
        val all = list(c, k)
        val own = all.filter { prayer >= 0 && prayerOf(it) == prayer }
        val general = all.filter { prayerOf(it) < 0 }
        val pool = own.ifEmpty { general.ifEmpty { all } }
        if (pool.size <= 1) return pool.firstOrNull()
        return pool.filter { it != last }.random()
    }

    /** Decodes a picture no bigger than needed for maxW x maxH (rotated as the camera took it). */
    fun decode(f: File, maxW: Int, maxH: Int): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { d, info, _ ->
                val s = maxOf(1, minOf(info.size.width / maxW.coerceAtLeast(1), info.size.height / maxH.coerceAtLeast(1)))
                if (s > 1) d.setTargetSampleSize(s)
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            var s = 1
            while (o.outWidth / (s * 2) >= maxW && o.outHeight / (s * 2) >= maxH) s *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
        }
    }.getOrNull()
}
