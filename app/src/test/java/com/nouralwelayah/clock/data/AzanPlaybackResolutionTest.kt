package com.nouralwelayah.clock.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URLEncoder
import java.nio.file.Files

class AzanPlaybackResolutionTest {

    @Test
    fun `azanCandidatesFor prioritizes per-prayer sound then global azanDefault`() {
        val settings = AppSettings(
            azanSound = listOf("fajr_custom.mp3", "dhuhr_global.mp3", ""),
            azanDefault = "dhuhr_global.mp3",
        )

        // Fajr (0) has a per-prayer custom file, followed by global default fallback
        assertEquals(
            listOf("fajr_custom.mp3", "dhuhr_global.mp3"),
            settings.azanCandidatesFor(0)
        )
        // Dhuhr (1) uses global default
        assertEquals(
            listOf("dhuhr_global.mp3"),
            settings.azanCandidatesFor(1)
        )
        // Maghrib (2) is blank ("Same as Dhuhr"), so it resolves to global default
        assertEquals(
            listOf("dhuhr_global.mp3"),
            settings.azanCandidatesFor(2)
        )
        // 5-prayer index support: Asr (3) -> Dhuhr, Isha (4) -> Maghrib
        assertEquals(
            listOf("dhuhr_global.mp3"),
            settings.azanCandidatesFor(3)
        )
        assertEquals(
            listOf("dhuhr_global.mp3"),
            settings.azanCandidatesFor(4)
        )
    }

    @Test
    fun `fileForDirectories resolves Arabic filename with spaces and parentheses across paths and file URIs`() {
        val tempRoot = Files.createTempDirectory("nour_azan_test").toFile()
        val azanDir = File(tempRoot, "azan").apply { mkdirs() }
        val arabicFileName = "أذان - أباذر الحلواجي (النسخة الكاملة).mp3"
        val arabicFile = File(azanDir, arabicFileName)
        arabicFile.writeBytes(ByteArray(256) { 0x42 })

        try {
            val roots = listOf(tempRoot)
            val azanDirs = listOf(azanDir)

            // 1. Bare filename
            val resolvedBare = AzanLibrary.fileForDirectories(
                ref = arabicFileName,
                roots = roots,
                azanDirs = azanDirs,
            )
            assertNotNull("Expected bare Arabic filename to resolve", resolvedBare)
            assertEquals(arabicFile.canonicalPath, resolvedBare!!.canonicalPath)
            assertTrue(resolvedBare.exists() && resolvedBare.canRead() && resolvedBare.length() > 0L)

            // 2. Relative path "azan/<name>" and "/azan/<name>" and "/sdcard/NourAlwelayah/azan/<name>"
            val resolvedRel = AzanLibrary.fileForDirectories(
                ref = "azan/$arabicFileName",
                roots = roots,
                azanDirs = azanDirs,
            )
            assertNotNull("Expected relative azan/ path to resolve", resolvedRel)
            assertEquals(arabicFile.canonicalPath, resolvedRel!!.canonicalPath)

            val resolvedLeadingSlash = AzanLibrary.fileForDirectories(
                ref = "/azan/$arabicFileName",
                roots = roots,
                azanDirs = azanDirs,
            )
            assertNotNull("Expected /azan/ path to resolve", resolvedLeadingSlash)
            assertEquals(arabicFile.canonicalPath, resolvedLeadingSlash!!.canonicalPath)

            val resolvedSdcardPath = AzanLibrary.fileForDirectories(
                ref = "/sdcard/NourAlwelayah/azan/$arabicFileName",
                roots = roots,
                azanDirs = azanDirs,
            )
            assertNotNull("Expected /sdcard/NourAlwelayah/azan/ path to resolve", resolvedSdcardPath)
            assertEquals(arabicFile.canonicalPath, resolvedSdcardPath!!.canonicalPath)

            // 3. Full absolute path on disk
            val resolvedFull = AzanLibrary.fileForDirectories(
                ref = arabicFile.absolutePath,
                roots = roots,
                azanDirs = azanDirs,
            )
            assertNotNull("Expected full path to resolve", resolvedFull)
            assertEquals(arabicFile.canonicalPath, resolvedFull!!.canonicalPath)

            // 4. file:// URI (percent-encoded Arabic and spaces)
            val encodedPath = arabicFile.absolutePath.split("/").joinToString("/") { segment ->
                URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
            }
            val fileUriString = "file://$encodedPath"
            val resolvedUri = AzanLibrary.fileForDirectories(
                ref = fileUriString,
                roots = roots,
                azanDirs = azanDirs,
            )
            assertNotNull("Expected file:// URI to resolve", resolvedUri)
            assertEquals(arabicFile.canonicalPath, resolvedUri!!.canonicalPath)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `fileForDirectories rejects missing or zero-byte files`() {
        val tempRoot = Files.createTempDirectory("nour_azan_empty_test").toFile()
        val azanDir = File(tempRoot, "azan").apply { mkdirs() }
        val emptyFile = File(azanDir, "empty_azan.mp3")
        emptyFile.writeBytes(ByteArray(0))

        try {
            val roots = listOf(tempRoot)
            val azanDirs = listOf(azanDir)

            assertNull(
                "Zero-byte file must not be returned as playable",
                AzanLibrary.fileForDirectories("empty_azan.mp3", roots, azanDirs)
            )
            assertNull(
                "Non-existent file must return null",
                AzanLibrary.fileForDirectories("non_existent_azan.mp3", roots, azanDirs)
            )
            assertNull(
                "Blank reference must return null",
                AzanLibrary.fileForDirectories("   ", roots, azanDirs)
            )
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
