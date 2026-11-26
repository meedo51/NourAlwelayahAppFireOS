package com.nouralwelayah.clock.system

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * First-boot Coroutine utility that extracts the bundled `assets/NourAlwelayah/` directory tree
 * into `Context.getExternalFilesDir(null)/NourAlwelayah` if the `assets_extracted` flag in
 * SharedPreferences is not yet set.
 */
object AssetExtractor {
    private const val TAG = "AssetExtractor"
    private const val PREFS_NAME = "prayer_clock"
    const val KEY_ASSETS_EXTRACTED = "assets_extracted"
    private const val ASSET_ROOT = "NourAlwelayah"

    private val mutex = Mutex()
    private val _isExtracting = MutableStateFlow(false)
    val isExtracting: StateFlow<Boolean> = _isExtracting.asStateFlow()

    /**
     * Returns `true` if the initial asset payload has already been extracted.
     */
    fun isExtracted(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ASSETS_EXTRACTED, false)
    }

    /**
     * Checks whether `assets_extracted` is set in SharedPreferences. If not, recursively copies
     * `assets/NourAlwelayah/` into `getExternalFilesDir(null)/NourAlwelayah` on [Dispatchers.IO].
     *
     * @return `true` if extraction ran (or was already completed), `false` if an unrecoverable error occurred.
     */
    suspend fun extractIfNeeded(context: Context): Boolean = mutex.withLock {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rootDir = withContext(Dispatchers.IO) {
            FileSystemManager.initialize(appContext)
        }

        if (prefs.getBoolean(KEY_ASSETS_EXTRACTED, false) && rootDir.exists()) {
            return@withLock true
        }

        _isExtracting.value = true
        try {
            withContext(Dispatchers.IO) {
                val assetManager = appContext.assets
                val topEntries = runCatching { assetManager.list(ASSET_ROOT) }.getOrNull().orEmpty()
                if (topEntries.isNotEmpty()) {
                    copyAssetTree(
                        assetManager = assetManager,
                        assetPath = ASSET_ROOT,
                        destRoot = rootDir,
                        relativePath = "",
                    )
                } else {
                    // Ensure all predefined directories exist even if no optional payload files are bundled in APK assets
                    FileSystemManager.REQUIRED_SUBDIRECTORIES.forEach { sub ->
                        val subAssetPath = "$ASSET_ROOT/$sub"
                        val children = runCatching { assetManager.list(subAssetPath) }.getOrNull().orEmpty()
                        if (children.isNotEmpty()) {
                            copyAssetTree(
                                assetManager = assetManager,
                                assetPath = subAssetPath,
                                destRoot = rootDir,
                                relativePath = sub,
                            )
                        }
                    }
                }
                prefs.edit().putBoolean(KEY_ASSETS_EXTRACTED, true).apply()
                Log.i(TAG, "Appliance asset payload extraction complete at ${rootDir.absolutePath}")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Asset extraction failed", e)
            false
        } finally {
            _isExtracting.value = false
        }
    }

    private fun copyAssetTree(
        assetManager: AssetManager,
        assetPath: String,
        destRoot: File,
        relativePath: String,
    ) {
        if (relativePath.isNotEmpty() && !FileSystemManager.isSafeRelativePath(relativePath)) {
            Log.w(TAG, "Skipping unsafe asset relative path: $relativePath")
            return
        }

        val children = runCatching { assetManager.list(assetPath) }.getOrNull().orEmpty()
        if (children.isEmpty()) {
            // It may be a file or an empty directory; attempt to open as a stream
            val targetFile = FileSystemManager.resolveSafePath(destRoot, relativePath) ?: return
            runCatching {
                assetManager.open(assetPath).use { input ->
                    targetFile.parentFile?.mkdirs()
                    if (!targetFile.exists()) {
                        val tempFile = File(targetFile.parentFile, ".${targetFile.name}.tmp")
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output, bufferSize = 64 * 1024)
                            output.fd.sync()
                        }
                        if (!tempFile.renameTo(targetFile)) {
                            tempFile.copyTo(targetFile, overwrite = true)
                            tempFile.delete()
                        }
                    }
                }
            }.onFailure {
                // Empty asset folder: ensure directory exists
                if (relativePath.isNotEmpty()) {
                    targetFile.mkdirs()
                }
            }
        } else {
            val targetDir = if (relativePath.isEmpty()) {
                destRoot
            } else {
                FileSystemManager.resolveSafePath(destRoot, relativePath) ?: return
            }
            targetDir.mkdirs()

            for (child in children) {
                if (child == "." || child == "..") continue
                val childAssetPath = "$assetPath/$child"
                val childRelative = if (relativePath.isEmpty()) child else "$relativePath/$child"
                copyAssetTree(
                    assetManager = assetManager,
                    assetPath = childAssetPath,
                    destRoot = destRoot,
                    relativePath = childRelative,
                )
            }
        }
    }
}
