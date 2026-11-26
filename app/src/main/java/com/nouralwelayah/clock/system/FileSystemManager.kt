package com.nouralwelayah.clock.system

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Hardened File System Manager for the Nour Alwelayah Appliance.
 *
 * Manages the canonical directory tree inside `Context.getExternalFilesDir(null)/NourAlwelayah`:
 * - `alarms`
 * - `audios`
 * - `azan`
 * - `config`
 * - `images`
 * - `videos`
 * - `wallpaper`
 *
 * Automatically creates a root `.nomedia` marker to prevent Android OS media scanner indexing
 * and provides strict path-traversal validation utilities.
 */
object FileSystemManager {
    private const val TAG = "FileSystemManager"
    const val ROOT_DIR_NAME = "NourAlwelayah"
    const val NOMEDIA_FILE = ".nomedia"
    const val HIDDEN_CONFIG_DIR = "config"
    const val HIDDEN_VOICE_MODELS_DIR = "voice_models"

    val HIDDEN_SYSTEM_DIRS: Set<String> = setOf(
        HIDDEN_CONFIG_DIR,
        HIDDEN_VOICE_MODELS_DIR,
    )

    val REQUIRED_SUBDIRECTORIES: List<String> = listOf(
        "alarms",
        "audios",
        "azan",
        "config",
        "images",
        "videos",
        "voice_models",
        "wallpaper",
    )

    /**
     * Returns the base `/NourAlwelayah` directory inside `Context.getExternalFilesDir(null)`,
     * falling back to internal `Context.filesDir` only if external storage is unavailable.
     */
    fun getRootDirectory(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, ROOT_DIR_NAME)
    }

    /**
     * Initializes the full appliance directory hierarchy and writes the root `.nomedia` file.
     * Safe to invoke idempotently on every boot.
     */
    @Synchronized
    fun initialize(context: Context): File {
        val rootDir = getRootDirectory(context)
        if (!rootDir.exists() && !rootDir.mkdirs()) {
            Log.e(TAG, "Failed to create root appliance directory: ${rootDir.absolutePath}")
        }

        // Create .nomedia in the root directory to block Android OS MediaScanner indexing
        runCatching {
            val noMedia = File(rootDir, NOMEDIA_FILE)
            if (!noMedia.exists()) {
                noMedia.createNewFile()
            }
        }.onFailure { e ->
            Log.w(TAG, "Unable to create .nomedia file in ${rootDir.absolutePath}", e)
        }

        // Provision all predefined subdirectories
        REQUIRED_SUBDIRECTORIES.forEach { subName ->
            val subDir = File(rootDir, subName)
            if (!subDir.exists() && !subDir.mkdirs()) {
                Log.w(TAG, "Unable to create subdirectory: ${subDir.absolutePath}")
            }
        }

        return rootDir
    }

    /**
     * Returns a specific subdirectory inside `getExternalFilesDir(null)/NourAlwelayah/<subDir>`,
     * ensuring it exists and is validated against path traversal.
     */
    fun getSubdirectory(context: Context, subDir: String): File {
        val root = initialize(context)
        return resolveSafePath(root, subDir)
            ?: throw SecurityException("Rejected unsafe subdirectory path: $subDir")
    }

    /**
     * Validates a raw relative path string to prevent directory traversal (`..`), null-byte injection,
     * or escaping outside the canonical `baseDir`.
     *
     * @return The canonical [File] inside [baseDir] if valid, or `null` if traversal is detected.
     */
    fun resolveSafePath(baseDir: File, requestedPath: String): File? {
        if (!isSafeRelativePath(requestedPath)) {
            Log.w(TAG, "Blocked directory traversal attempt in path: \"$requestedPath\"")
            return null
        }

        return runCatching {
            val canonicalBase = baseDir.canonicalFile
            val sanitized = requestedPath
                .replace('\\', '/')
                .trimStart('/')
            val candidate = if (sanitized.isEmpty()) {
                canonicalBase
            } else {
                File(canonicalBase, sanitized).canonicalFile
            }

            val basePrefix = canonicalBase.path + File.separator
            if (candidate == canonicalBase || candidate.path.startsWith(basePrefix)) {
                candidate
            } else {
                Log.w(TAG, "Blocked canonical path escape: ${candidate.path} is outside ${canonicalBase.path}")
                null
            }
        }.getOrNull()
    }

    /**
     * Validates a path against `Context.getExternalFilesDir(null)/NourAlwelayah`.
     */
    fun validatePath(context: Context, requestedPath: String): File? =
        resolveSafePath(getRootDirectory(context), requestedPath)

    /**
     * Fast lexical check rejecting any path containing `..` segments or control characters.
     */
    fun isSafeRelativePath(rawPath: String): Boolean {
        if ('\u0000' in rawPath) return false
        val normalized = rawPath.replace('\\', '/')
        if (normalized.contains("..")) {
            val segments = normalized.split('/')
            if (segments.any { it == ".." || it.contains("..") }) {
                return false
            }
        }
        return true
    }

    /**
     * Checks whether a relative path or directory name targets a protected system directory
     * (`/config` or `/voice_models`) that must be hidden from consumer file tabs and web remotes.
     */
    fun isConfigPath(rawPath: String): Boolean = isHiddenSystemPath(rawPath)

    /**
     * Checks whether [rawPath] targets `/config` or `/voice_models`.
     */
    fun isHiddenSystemPath(rawPath: String): Boolean {
        val clean = rawPath.replace('\\', '/').trim('/')
        if (clean.isEmpty()) return false
        val firstSegment = clean.substringBefore('/').lowercase()
        return firstSegment in HIDDEN_SYSTEM_DIRS
    }
}
