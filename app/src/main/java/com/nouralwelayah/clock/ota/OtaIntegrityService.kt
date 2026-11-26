package com.nouralwelayah.clock.ota

import android.content.Context
import android.util.Log
import com.nouralwelayah.clock.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Locale

/**
 * Sidecar integrity metadata stored alongside resumable OTA downloads (`ota-v<versionCode>.integrity.json`).
 *
 * Records:
 * - [versionCode]: Target OTA package versionCode.
 * - [expectedFullSha256]: Lowercase hex SHA-256 of the complete `.apk` from `latest.json`.
 * - [expectedTotalBytes]: Total byte size of the complete `.apk` (`-1` if not yet known).
 * - [chunkSizeBytes]: Fixed chunk boundary size in bytes (default [OtaIntegrityHealthCheck.DEFAULT_CHUNK_SIZE_BYTES], 8 MB).
 * - [chunkHashes]: Map of `chunkIndex -> lowercase sha256 hex` computed as each complete chunk is written to disk.
 */
data class OtaIntegrityMetadata(
    val versionCode: Long,
    val expectedFullSha256: String,
    val expectedTotalBytes: Long,
    val chunkSizeBytes: Int = OtaIntegrityHealthCheck.DEFAULT_CHUNK_SIZE_BYTES,
    val chunkHashes: Map<Int, String> = emptyMap(),
) {
    fun toJsonString(): String {
        val cleanSha = expectedFullSha256.trim().lowercase(Locale.ROOT)
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"versionCode\": ").append(versionCode).append(",\n")
        sb.append("  \"expectedFullSha256\": \"").append(cleanSha).append("\",\n")
        sb.append("  \"expectedTotalBytes\": ").append(expectedTotalBytes).append(",\n")
        sb.append("  \"chunkSizeBytes\": ").append(chunkSizeBytes).append(",\n")
        sb.append("  \"chunkHashes\": {")
        val sortedEntries = chunkHashes.entries.sortedBy { it.key }
        if (sortedEntries.isNotEmpty()) {
            sb.append("\n")
            sortedEntries.forEachIndexed { index, (idx, hash) ->
                val cleanHash = hash.trim().lowercase(Locale.ROOT)
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                sb.append("    \"").append(idx).append("\": \"").append(cleanHash).append("\"")
                if (index < sortedEntries.lastIndex) sb.append(",")
                sb.append("\n")
            }
            sb.append("  }")
        } else {
            sb.append("}")
        }
        sb.append("\n}")
        return sb.toString()
    }

    companion object {
        private val REGEX_VERSION_CODE = Regex(""""versionCode"\s*:\s*(-?\d+)""")
        private val REGEX_EXPECTED_SHA = Regex(""""expectedFullSha256"\s*:\s*"([^"]*)"""")
        private val REGEX_EXPECTED_BYTES = Regex(""""expectedTotalBytes"\s*:\s*(-?\d+)""")
        private val REGEX_CHUNK_SIZE = Regex(""""chunkSizeBytes"\s*:\s*(-?\d+)""")
        private val REGEX_CHUNK_HASHES_BLOCK = Regex(""""chunkHashes"\s*:\s*\{([^}]*)\}""")
        private val REGEX_CHUNK_ENTRY = Regex(""""(\d+)"\s*:\s*"([^"]+)"""")

        fun fromJsonString(json: String): OtaIntegrityMetadata? = runCatching {
            val versionCode = REGEX_VERSION_CODE.find(json)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
            if (versionCode <= 0L) return null
            val expectedFullSha256 = REGEX_EXPECTED_SHA.find(json)?.groupValues?.getOrNull(1)
                ?.trim()?.lowercase(Locale.ROOT).orEmpty()
            val expectedTotalBytes = REGEX_EXPECTED_BYTES.find(json)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: -1L
            val chunkSizeBytes = (REGEX_CHUNK_SIZE.find(json)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: OtaIntegrityHealthCheck.DEFAULT_CHUNK_SIZE_BYTES).coerceAtLeast(1024)

            val chunkMap = mutableMapOf<Int, String>()
            val blockBody = REGEX_CHUNK_HASHES_BLOCK.find(json)?.groupValues?.getOrNull(1).orEmpty()
            REGEX_CHUNK_ENTRY.findAll(blockBody).forEach { match ->
                val idx = match.groupValues.getOrNull(1)?.toIntOrNull()
                val hash = match.groupValues.getOrNull(2)?.trim()?.lowercase(Locale.ROOT).orEmpty()
                if (idx != null && idx >= 0 && hash.isNotEmpty()) {
                    chunkMap[idx] = hash
                }
            }

            OtaIntegrityMetadata(
                versionCode = versionCode,
                expectedFullSha256 = expectedFullSha256,
                expectedTotalBytes = expectedTotalBytes,
                chunkSizeBytes = chunkSizeBytes,
                chunkHashes = chunkMap,
            )
        }.getOrNull()

        fun readFromFile(file: File): OtaIntegrityMetadata? {
            if (!file.isFile || file.length() <= 0L) return null
            return runCatching {
                fromJsonString(file.readText(Charsets.UTF_8))
            }.getOrNull()
        }

        fun writeToFile(file: File, metadata: OtaIntegrityMetadata) {
            runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.writeText(metadata.toJsonString(), Charsets.UTF_8)
                if (file.exists()) {
                    file.delete()
                }
                if (!tmp.renameTo(file)) {
                    tmp.copyTo(file, overwrite = true)
                    tmp.delete()
                }
            }
        }
    }
}

/**
 * Health check outcome exposed to `AdminDashboard.kt` and logs after startup OTA verification.
 */
sealed class OtaIntegrityStatus {
    /** No stored `.apk` or `.part` files exist (or only orphans were cleaned). */
    data class Clean(val orphansRemoved: Int = 0) : OtaIntegrityStatus()

    /** Stored `.apk` or `.apk.part` chunks were all verified against their SHA-256 sidecar hashes. */
    data class Verified(
        val versionCode: Long,
        val verifiedChunks: Int,
        val verifiedBytes: Long,
        val isCompleteApk: Boolean,
    ) : OtaIntegrityStatus()

    /** Corrupted `.apk` or `.apk.part` chunks were detected and truncated/removed cleanly. */
    data class Repaired(
        val corruptedChunksRemoved: Int,
        val truncatedToBytes: Long = 0L,
        val corruptedApksRemoved: Int = 0,
        val orphansRemoved: Int = 0,
    ) : OtaIntegrityStatus()
}

/**
 * Startup OTA Integrity Health Check Engine for `com.nouralwelayah.clock`.
 *
 * Runs asynchronously on [Dispatchers.IO] during app startup without blocking the UI thread:
 * 1. **Orphan Cleanup:** Deletes any leftover `.part`, `.apk`, or `.integrity.json` files whose
 *    `versionCode <= currentVersionCode`.
 * 2. **Completed `.apk` Verification:** Computes full SHA-256 on any stored `update_<versionCode>.apk`
 *    and compares against its `.integrity.json` sidecar (`expectedFullSha256`). If it fails or has
 *    no matching metadata, deletes the corrupted `.apk` immediately and logs a warning.
 * 3. **Stored `.apk.part` Chunk Verification:** Re-hashes every stored chunk in `ota-v<versionCode>.apk.part`
 *    and compares each chunk against its recorded SHA-256 in `ota-v<versionCode>.integrity.json`.
 *    If any chunk fails verification (silent bit-rot or interrupted write corruption), truncates the
 *    `.part` file back to the last valid chunk boundary (or deletes both `.part` and `.integrity.json`
 *    if chunk 0 is corrupt) so the next resumable download cleanly re-fetches only the corrupted portion.
 */
object OtaIntegrityHealthCheck {
    private const val TAG = "OtaIntegrityHealthCheck"
    const val DEFAULT_CHUNK_SIZE_BYTES: Int = 8 * 1024 * 1024 // 8 MB chunks
    private const val HASH_BUFFER_SIZE = 65_536

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow<OtaIntegrityStatus>(OtaIntegrityStatus.Clean())
    val status: StateFlow<OtaIntegrityStatus> = _status.asStateFlow()

    private val REGEX_PART = Regex("""^ota-v(\d+)\.apk\.part$""")
    private val REGEX_APK = Regex("""^(?:update_|ota-v)(\d+)\.apk$""")
    private val REGEX_SIDECAR = Regex("""^ota-v(\d+)\.integrity\.json$""")

    fun sidecarFileFor(otaDir: File, versionCode: Long): File =
        File(otaDir, "ota-v$versionCode.integrity.json")

    fun partFileFor(otaDir: File, versionCode: Long): File =
        File(otaDir, "ota-v$versionCode.apk.part")

    fun apkFileFor(otaDir: File, versionCode: Long): File =
        File(otaDir, "update_$versionCode.apk")

    private fun safeLogI(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    private fun safeLogW(message: String, error: Throwable? = null) {
        runCatching {
            if (error != null) Log.w(TAG, message, error) else Log.w(TAG, message)
        }
    }

    /**
     * Launches [run] asynchronously on [Dispatchers.IO] so `MainActivity.onCreate()` /
     * `PrayerClockApp.onCreate()` never blocks the main thread.
     */
    fun runAsync(context: Context) {
        val appCtx = context.applicationContext
        scope.launch {
            runCatching {
                run(appCtx)
            }.onFailure { e ->
                safeLogW("Startup OTA integrity health check failed: ${e.message}", e)
            }
        }
    }

    /**
     * Synchronously scans the application's OTA storage directories (`cacheDir/ota` and `filesDir/ota`)
     * and returns the resulting [OtaIntegrityStatus].
     */
    fun run(context: Context): OtaIntegrityStatus {
        val currentVersionCode = OtaUpdateManager.getCurrentVersionCode(context)
        val dirs = listOfNotNull(
            File(context.cacheDir, "ota"),
            context.filesDir?.let { File(it, "ota") },
        ).distinctBy { it.absolutePath }

        var lastStatus: OtaIntegrityStatus = OtaIntegrityStatus.Clean()
        for (dir in dirs) {
            if (dir.exists()) {
                val dirStatus = inspectDirectory(dir, currentVersionCode)
                if (dirStatus !is OtaIntegrityStatus.Clean || lastStatus is OtaIntegrityStatus.Clean) {
                    lastStatus = dirStatus
                }
            }
        }
        _status.value = lastStatus
        return lastStatus
    }

    /**
     * Pure directory-level integrity scan & repair (testable directly on JVM unit tests with `tempDir`).
     */
    fun inspectDirectory(
        otaDir: File,
        currentVersionCode: Long = BuildConfig.VERSION_CODE.toLong(),
    ): OtaIntegrityStatus {
        if (!otaDir.exists() || !otaDir.isDirectory) {
            val clean = OtaIntegrityStatus.Clean(0)
            _status.value = clean
            return clean
        }

        var orphansRemoved = 0
        var corruptedApksRemoved = 0
        var corruptedChunksRemoved = 0
        var truncatedToBytes = 0L
        var verifiedVersionCode = -1L
        var verifiedChunks = 0
        var verifiedBytes = 0L
        var verifiedCompleteApk = false

        // 1. Orphan cleanup: remove any .part / .apk / .integrity.json with versionCode <= currentVersionCode
        //    or unrecognized temporary files
        val initialFiles = otaDir.listFiles().orEmpty()
        for (file in initialFiles) {
            if (!file.isFile) continue
            if (file.name.endsWith(".tmp")) {
                if (file.delete()) orphansRemoved++
                continue
            }
            val vCode = extractVersionCode(file.name)
            if (vCode != null && vCode <= currentVersionCode) {
                safeLogI(
                    "Orphan cleanup: deleting obsolete OTA artifact ${file.name} (versionCode=$vCode <= installed=$currentVersionCode)"
                )
                if (file.delete()) orphansRemoved++
            }
        }

        // 2. Completed .apk verification
        val remainingAfterOrphans = otaDir.listFiles().orEmpty()
        val apkFiles = remainingAfterOrphans.filter { it.isFile && REGEX_APK.matches(it.name) }
        for (apkFile in apkFiles) {
            val vCode = extractVersionCode(apkFile.name) ?: continue
            val sidecarFile = sidecarFileFor(otaDir, vCode)
            val meta = OtaIntegrityMetadata.readFromFile(sidecarFile)
            val expectedSha = meta?.expectedFullSha256?.trim().orEmpty()

            if (meta == null || expectedSha.isEmpty() || apkFile.length() <= 0L) {
                safeLogW(
                    "Deleting unverified or metadata-missing OTA APK ${apkFile.name} (metaPresent=${meta != null})"
                )
                apkFile.delete()
                corruptedApksRemoved++
                continue
            }

            if (meta.expectedTotalBytes > 0L && apkFile.length() != meta.expectedTotalBytes) {
                safeLogW(
                    "Deleting size-mismatched OTA APK ${apkFile.name}: actual=${apkFile.length()}, expected=${meta.expectedTotalBytes}"
                )
                apkFile.delete()
                corruptedApksRemoved++
                continue
            }

            val actualSha = OtaUpdateManager.computeSha256Hex(apkFile)
            if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                safeLogW(
                    "Deleting corrupted OTA APK ${apkFile.name}: expected SHA-256=$expectedSha, actual=$actualSha"
                )
                apkFile.delete()
                corruptedApksRemoved++
            } else {
                safeLogI("Verified completed OTA APK ${apkFile.name} (SHA-256=$actualSha)")
                verifiedVersionCode = vCode
                verifiedChunks = meta.chunkHashes.size.coerceAtLeast(1)
                verifiedBytes = apkFile.length()
                verifiedCompleteApk = true
            }
        }

        // 3. Stored .apk.part chunk-by-chunk verification & surgical truncation
        val partFiles = otaDir.listFiles().orEmpty().filter { it.isFile && REGEX_PART.matches(it.name) }
        for (partFile in partFiles) {
            val vCode = extractVersionCode(partFile.name) ?: continue
            val sidecarFile = sidecarFileFor(otaDir, vCode)
            val meta = OtaIntegrityMetadata.readFromFile(sidecarFile)

            if (meta == null || partFile.length() <= 0L) {
                safeLogW(
                    "Deleting orphaned or empty partial OTA file ${partFile.name} without valid integrity sidecar"
                )
                val approxChunks = if (partFile.length() > 0L) 1 else 0
                partFile.delete()
                sidecarFile.delete()
                corruptedChunksRemoved += approxChunks.coerceAtLeast(1)
                truncatedToBytes = 0L
                continue
            }

            val chunkSize = meta.chunkSizeBytes.coerceAtLeast(1024)
            val partLen = partFile.length()

            // IfexpectedTotalBytes is known and partFile exceeds it, it is corrupt beyond boundary
            val totalFullChunksOnDisk = (partLen / chunkSize).toInt()
            val hasTrailingPartialChunk = (partLen % chunkSize) > 0L
            val totalChunksOnDisk = totalFullChunksOnDisk + if (hasTrailingPartialChunk) 1 else 0

            var firstInvalidChunkIndex = -1

            // Verify every complete chunk [0 until totalFullChunksOnDisk] against its recorded hash in meta.chunkHashes
            for (chunkIdx in 0 until totalFullChunksOnDisk) {
                val expectedChunkHash = meta.chunkHashes[chunkIdx]
                if (expectedChunkHash.isNullOrBlank()) {
                    safeLogW(
                        "Missing recorded SHA-256 for chunk #$chunkIdx in ${sidecarFile.name}; marking chunk #$chunkIdx invalid"
                    )
                    firstInvalidChunkIndex = chunkIdx
                    break
                }
                val offset = chunkIdx.toLong() * chunkSize.toLong()
                val actualChunkHash = computeRegionSha256Hex(partFile, offset, chunkSize.toLong())
                if (!actualChunkHash.equals(expectedChunkHash, ignoreCase = true)) {
                    safeLogW(
                        "Corrupted chunk #$chunkIdx detected in ${partFile.name}: expected=$expectedChunkHash, actual=$actualChunkHash"
                    )
                    firstInvalidChunkIndex = chunkIdx
                    break
                }
            }

            // If all full chunks passed, check whether a trailing partial chunk exists
            if (firstInvalidChunkIndex == -1 && hasTrailingPartialChunk) {
                val trailingIdx = totalFullChunksOnDisk
                val tailOffset = trailingIdx.toLong() * chunkSize.toLong()
                val tailLength = partLen - tailOffset
                val recordedTailHash = meta.chunkHashes[trailingIdx]
                val isFinalCompleteFile = meta.expectedTotalBytes > 0L && partLen == meta.expectedTotalBytes
                if (isFinalCompleteFile && !recordedTailHash.isNullOrBlank()) {
                    val actualTailHash = computeRegionSha256Hex(partFile, tailOffset, tailLength)
                    if (!actualTailHash.equals(recordedTailHash, ignoreCase = true)) {
                        safeLogW(
                            "Corrupted final tail chunk #$trailingIdx in ${partFile.name}: expected=$recordedTailHash, actual=$actualTailHash"
                        )
                        firstInvalidChunkIndex = trailingIdx
                    }
                } else {
                    // Trailing incomplete chunk from an interrupted write not aligned to chunkSize:
                    // truncate back to the last verified full chunk boundary so resume starts at a 100% verified offset
                    safeLogI(
                        "Trimming unverified trailing partial chunk #$trailingIdx ($tailLength bytes) in ${partFile.name} back to verified chunk boundary"
                    )
                    firstInvalidChunkIndex = trailingIdx
                }
            }

            if (firstInvalidChunkIndex == -1) {
                // Every chunk on disk is verified
                verifiedVersionCode = vCode
                verifiedChunks = totalChunksOnDisk
                verifiedBytes = partLen
                safeLogI(
                    "Verified $totalChunksOnDisk chunk(s) ($partLen bytes) in ${partFile.name}"
                )
            } else if (firstInvalidChunkIndex == 0) {
                // Chunk 0 is corrupt (or file only had an unverified sub-chunk < chunkSize): delete both .part and .integrity.json
                val removedCount = totalChunksOnDisk.coerceAtLeast(1)
                safeLogW(
                    "Chunk 0 failed integrity check in ${partFile.name}; deleting ${partFile.name} and ${sidecarFile.name} ($removedCount chunk(s) removed)"
                )
                partFile.delete()
                sidecarFile.delete()
                corruptedChunksRemoved += removedCount
                truncatedToBytes = 0L
            } else {
                // Truncate .part back to the last valid chunk boundary (firstInvalidChunkIndex * chunkSize)
                val validBoundaryBytes = firstInvalidChunkIndex.toLong() * chunkSize.toLong()
                val removedChunks = (totalChunksOnDisk - firstInvalidChunkIndex).coerceAtLeast(1)
                safeLogW(
                    "Truncating ${partFile.name} from $partLen bytes to last valid chunk boundary $validBoundaryBytes bytes " +
                        "(removed $removedChunks corrupted/unverified chunk(s) starting at chunk #$firstInvalidChunkIndex)"
                )
                RandomAccessFile(partFile, "rw").use { raf ->
                    raf.setLength(validBoundaryBytes)
                    raf.fd.sync()
                }
                val prunedHashes = meta.chunkHashes.filterKeys { it < firstInvalidChunkIndex }
                OtaIntegrityMetadata.writeToFile(
                    sidecarFile,
                    meta.copy(chunkHashes = prunedHashes)
                )
                corruptedChunksRemoved += removedChunks
                truncatedToBytes = validBoundaryBytes
            }
        }

        // 4. Clean up any dangling .integrity.json sidecars that have neither a .part nor an .apk file
        for (file in otaDir.listFiles().orEmpty()) {
            if (!file.isFile || !REGEX_SIDECAR.matches(file.name)) continue
            val vCode = extractVersionCode(file.name) ?: continue
            val hasPart = partFileFor(otaDir, vCode).exists()
            val hasApk = apkFileFor(otaDir, vCode).exists()
            if (!hasPart && !hasApk) {
                file.delete()
            }
        }

        val finalStatus = when {
            corruptedChunksRemoved > 0 || corruptedApksRemoved > 0 -> OtaIntegrityStatus.Repaired(
                corruptedChunksRemoved = corruptedChunksRemoved,
                truncatedToBytes = truncatedToBytes,
                corruptedApksRemoved = corruptedApksRemoved,
                orphansRemoved = orphansRemoved,
            )
            verifiedVersionCode > 0L -> OtaIntegrityStatus.Verified(
                versionCode = verifiedVersionCode,
                verifiedChunks = verifiedChunks,
                verifiedBytes = verifiedBytes,
                isCompleteApk = verifiedCompleteApk,
            )
            else -> OtaIntegrityStatus.Clean(orphansRemoved = orphansRemoved)
        }
        _status.value = finalStatus
        return finalStatus
    }

    /**
     * Synchronizes the `.integrity.json` sidecar for every complete chunk present in [partFile].
     * Called by [OtaUpdateManager] during and after streaming bytes into [partFile].
     */
    fun updateSidecarForPartFile(
        partFile: File,
        sidecarFile: File,
        versionCode: Long,
        expectedFullSha256: String,
        expectedTotalBytes: Long,
        chunkSizeBytes: Int = DEFAULT_CHUNK_SIZE_BYTES,
    ): OtaIntegrityMetadata {
        val existingMeta = OtaIntegrityMetadata.readFromFile(sidecarFile)
        val effectiveChunkSize = (existingMeta?.chunkSizeBytes ?: chunkSizeBytes).coerceAtLeast(1024)
        val hashes = existingMeta?.chunkHashes?.toMutableMap() ?: mutableMapOf()

        val fileLen = if (partFile.isFile) partFile.length() else 0L
        val fullChunks = (fileLen / effectiveChunkSize).toInt()

        for (chunkIdx in 0 until fullChunks) {
            if (!hashes.containsKey(chunkIdx)) {
                val offset = chunkIdx.toLong() * effectiveChunkSize.toLong()
                hashes[chunkIdx] = computeRegionSha256Hex(partFile, offset, effectiveChunkSize.toLong())
            }
        }

        // If the file has reached its known full size and has a final partial tail chunk, hash that tail chunk too
        val remainder = fileLen % effectiveChunkSize
        if (expectedTotalBytes > 0L && fileLen == expectedTotalBytes && remainder > 0L) {
            val tailIdx = fullChunks
            val offset = tailIdx.toLong() * effectiveChunkSize.toLong()
            hashes[tailIdx] = computeRegionSha256Hex(partFile, offset, remainder)
        }

        // Remove any stale chunk hashes beyond the current file length
        val maxValidIdx = if (expectedTotalBytes > 0L && fileLen == expectedTotalBytes && remainder > 0L) {
            fullChunks
        } else {
            fullChunks - 1
        }
        hashes.keys.retainAll { it <= maxValidIdx }

        val updated = OtaIntegrityMetadata(
            versionCode = versionCode,
            expectedFullSha256 = expectedFullSha256.trim().lowercase(Locale.ROOT),
            expectedTotalBytes = expectedTotalBytes,
            chunkSizeBytes = effectiveChunkSize,
            chunkHashes = hashes,
        )
        OtaIntegrityMetadata.writeToFile(sidecarFile, updated)
        return updated
    }

    /**
     * Computes the lowercase hexadecimal SHA-256 digest of a specific byte region `[offset, offset + length)` inside [file].
     */
    fun computeRegionSha256Hex(file: File, offset: Long, length: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val buffer = ByteArray(HASH_BUFFER_SIZE)
            var remaining = length
            while (remaining > 0L) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val read = raf.read(buffer, 0, toRead)
                if (read <= 0) break
                digest.update(buffer, 0, read)
                remaining -= read
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    internal fun extractVersionCode(fileName: String): Long? {
        REGEX_PART.matchEntire(fileName)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { return it }
        REGEX_APK.matchEntire(fileName)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { return it }
        REGEX_SIDECAR.matchEntire(fileName)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { return it }
        return null
    }
}

/**
 * Alias referencing [OtaIntegrityHealthCheck] for callers using `OtaIntegrityService`.
 */
typealias OtaIntegrityService = OtaIntegrityHealthCheck
