package com.nouralwelayah.clock.ota

import com.nouralwelayah.clock.BuildConfig
import com.nouralwelayah.clock.ui.formatApplianceDeviceNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile

class OtaIntegrityHealthCheckTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `verified part file with all valid chunk hashes returns Verified status`() {
        val otaDir = tempFolder.newFolder("ota_verified")
        val targetVersionCode = 20005L
        val chunkSize = 4096 // 4 KB test chunk size

        // Create 3 complete chunks (12 KB total)
        val rawBytes = ByteArray(chunkSize * 3) { idx -> ((idx * 31 + 7) and 0xFF).toByte() }
        val partFile = OtaIntegrityHealthCheck.partFileFor(otaDir, targetVersionCode)
        val sidecarFile = OtaIntegrityHealthCheck.sidecarFileFor(otaDir, targetVersionCode)
        partFile.writeBytes(rawBytes)

        val fullSha = OtaUpdateManager.computeSha256Hex(partFile)
        OtaIntegrityHealthCheck.updateSidecarForPartFile(
            partFile = partFile,
            sidecarFile = sidecarFile,
            versionCode = targetVersionCode,
            expectedFullSha256 = fullSha,
            expectedTotalBytes = rawBytes.size.toLong() * 2, // 24 KB total expected, 12 KB downloaded so far
            chunkSizeBytes = chunkSize,
        )

        val status = OtaIntegrityHealthCheck.inspectDirectory(
            otaDir = otaDir,
            currentVersionCode = 10001L,
        )

        assertTrue("Expected Verified status but got $status", status is OtaIntegrityStatus.Verified)
        val verified = status as OtaIntegrityStatus.Verified
        assertEquals(targetVersionCode, verified.versionCode)
        assertEquals(3, verified.verifiedChunks)
        assertEquals((chunkSize * 3).toLong(), verified.verifiedBytes)
        assertFalse(verified.isCompleteApk)
        assertEquals((chunkSize * 3).toLong(), partFile.length())
    }

    @Test
    fun `corrupted byte in middle chunk truncates part file back to last valid chunk boundary`() {
        val otaDir = tempFolder.newFolder("ota_corrupt_mid")
        val targetVersionCode = 20006L
        val chunkSize = 4096 // 4 KB test chunk size

        // Create 3 complete chunks (0, 1, 2) = 12,288 bytes
        val rawBytes = ByteArray(chunkSize * 3) { idx -> ((idx * 17 + 3) and 0xFF).toByte() }
        val partFile = OtaIntegrityHealthCheck.partFileFor(otaDir, targetVersionCode)
        val sidecarFile = OtaIntegrityHealthCheck.sidecarFileFor(otaDir, targetVersionCode)
        partFile.writeBytes(rawBytes)

        val fullSha = OtaUpdateManager.computeSha256Hex(partFile)
        OtaIntegrityHealthCheck.updateSidecarForPartFile(
            partFile = partFile,
            sidecarFile = sidecarFile,
            versionCode = targetVersionCode,
            expectedFullSha256 = fullSha,
            expectedTotalBytes = (chunkSize * 4).toLong(),
            chunkSizeBytes = chunkSize,
        )

        // Flip a single byte inside chunk #1 (at byte offset chunkSize + 100)
        RandomAccessFile(partFile, "rw").use { raf ->
            val corruptOffset = chunkSize.toLong() + 100L
            raf.seek(corruptOffset)
            val original = raf.readByte()
            raf.seek(corruptOffset)
            raf.writeByte((original.toInt() xor 0xFF))
        }

        val status = OtaIntegrityHealthCheck.inspectDirectory(
            otaDir = otaDir,
            currentVersionCode = 10001L,
        )

        assertTrue("Expected Repaired status but got $status", status is OtaIntegrityStatus.Repaired)
        val repaired = status as OtaIntegrityStatus.Repaired
        // Chunks #1 and #2 should be removed, leaving only valid chunk #0 (4096 bytes)
        assertEquals(2, repaired.corruptedChunksRemoved)
        assertEquals(chunkSize.toLong(), repaired.truncatedToBytes)
        assertTrue(partFile.exists())
        assertEquals(chunkSize.toLong(), partFile.length())

        // Sidecar should now retain only chunk #0 hash
        val updatedMeta = OtaIntegrityMetadata.readFromFile(sidecarFile)
        assertNotNull(updatedMeta)
        assertEquals(setOf(0), updatedMeta!!.chunkHashes.keys)
    }

    @Test
    fun `corrupted byte in chunk 0 deletes both part file and sidecar metadata`() {
        val otaDir = tempFolder.newFolder("ota_corrupt_chunk0")
        val targetVersionCode = 20007L
        val chunkSize = 4096

        val rawBytes = ByteArray(chunkSize * 2) { idx -> (idx and 0xFF).toByte() }
        val partFile = OtaIntegrityHealthCheck.partFileFor(otaDir, targetVersionCode)
        val sidecarFile = OtaIntegrityHealthCheck.sidecarFileFor(otaDir, targetVersionCode)
        partFile.writeBytes(rawBytes)

        OtaIntegrityHealthCheck.updateSidecarForPartFile(
            partFile = partFile,
            sidecarFile = sidecarFile,
            versionCode = targetVersionCode,
            expectedFullSha256 = "abcdef1234567890",
            expectedTotalBytes = (chunkSize * 4).toLong(),
            chunkSizeBytes = chunkSize,
        )

        // Corrupt byte 12 inside chunk #0
        RandomAccessFile(partFile, "rw").use { raf ->
            raf.seek(12L)
            val b = raf.readByte()
            raf.seek(12L)
            raf.writeByte((b.toInt() xor 0xFF))
        }

        val status = OtaIntegrityHealthCheck.inspectDirectory(
            otaDir = otaDir,
            currentVersionCode = 10001L,
        )

        assertTrue("Expected Repaired status but got $status", status is OtaIntegrityStatus.Repaired)
        val repaired = status as OtaIntegrityStatus.Repaired
        assertEquals(2, repaired.corruptedChunksRemoved)
        assertEquals(0L, repaired.truncatedToBytes)
        assertFalse("Corrupted chunk 0 .part file must be deleted", partFile.exists())
        assertFalse("Sidecar must be deleted when chunk 0 is corrupt", sidecarFile.exists())
    }

    @Test
    fun `corrupted completed apk is deleted immediately on startup check`() {
        val otaDir = tempFolder.newFolder("ota_corrupt_apk")
        val targetVersionCode = 20008L
        val apkFile = OtaIntegrityHealthCheck.apkFileFor(otaDir, targetVersionCode)
        val sidecarFile = OtaIntegrityHealthCheck.sidecarFileFor(otaDir, targetVersionCode)

        val validBytes = "valid-apk-payload-bytes".toByteArray(Charsets.UTF_8)
        apkFile.writeBytes(validBytes)
        val expectedSha = OtaUpdateManager.computeSha256Hex(apkFile)

        OtaIntegrityMetadata.writeToFile(
            sidecarFile,
            OtaIntegrityMetadata(
                versionCode = targetVersionCode,
                expectedFullSha256 = expectedSha,
                expectedTotalBytes = validBytes.size.toLong(),
                chunkSizeBytes = 1024,
                chunkHashes = mapOf(0 to expectedSha),
            )
        )

        // Corrupt the completed APK file on disk
        apkFile.writeBytes("corrupted-apk-payload-bytes".toByteArray(Charsets.UTF_8))

        val status = OtaIntegrityHealthCheck.inspectDirectory(
            otaDir = otaDir,
            currentVersionCode = 10001L,
        )

        assertTrue("Expected Repaired status but got $status", status is OtaIntegrityStatus.Repaired)
        val repaired = status as OtaIntegrityStatus.Repaired
        assertEquals(1, repaired.corruptedApksRemoved)
        assertFalse("Corrupted .apk file should be deleted", apkFile.exists())
    }

    @Test
    fun `orphan files with versionCode less than or equal to installed versionCode are deleted`() {
        val otaDir = tempFolder.newFolder("ota_orphans")
        val installedCode = 10004L

        val oldPart = OtaIntegrityHealthCheck.partFileFor(otaDir, 10002L).apply { writeBytes(ByteArray(128)) }
        val sameApk = OtaIntegrityHealthCheck.apkFileFor(otaDir, 10004L).apply { writeBytes(ByteArray(128)) }
        val oldSidecar = OtaIntegrityHealthCheck.sidecarFileFor(otaDir, 10002L).apply { writeText("{}") }

        val status = OtaIntegrityHealthCheck.inspectDirectory(
            otaDir = otaDir,
            currentVersionCode = installedCode,
        )

        assertTrue("Expected Clean status but got $status", status is OtaIntegrityStatus.Clean)
        val clean = status as OtaIntegrityStatus.Clean
        assertEquals(3, clean.orphansRemoved)
        assertFalse(oldPart.exists())
        assertFalse(sameApk.exists())
        assertFalse(oldSidecar.exists())
    }

    @Test
    fun `buildConfig release fields and device number formatter are valid`() {
        assertTrue(BuildConfig.RELEASE_DATE.isNotBlank())
        assertTrue(BuildConfig.RELEASE_CHANGELOG.isNotBlank())
        assertEquals("NW-9ABCDEF0", formatApplianceDeviceNumber("123456789abcdef0"))
        assertEquals("NW-0000A1B2", formatApplianceDeviceNumber("a1b2"))
    }
}
