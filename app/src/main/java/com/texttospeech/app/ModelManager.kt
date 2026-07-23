package com.texttospeech.app

import android.content.Context
import android.content.res.AssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads and manages the Piper TTS model.
 *
 * Strategy:
 *   • espeak-ng-data (355 files, ~5 MB) — bundled inside the APK as assets
 *     (extracted from the sherpa-onnx tarball during CI build, zero network cost).
 *   • tokens.txt — same, bundled as assets/piper_tokens.txt.
 *   • ONNX model (~62 MB) — downloaded once from HuggingFace at first launch,
 *     with resume support so a interrupted download continues where it left off.
 *
 * This avoids any dependency on GitHub CDN at runtime.
 */
class ModelManager(private val context: Context) {

    companion object {
        const val MODEL_NAME = "vi_VN-vais1000-medium.onnx"

        /** ONNX download URL — HuggingFace (accessible globally). */
        private const val ONNX_URL =
            "https://huggingface.co/csukuangfj/vits-piper-vi_VN-vais1000-medium/resolve/main/$MODEL_NAME"

        /** Known ONNX size (for progress when Content-Length is absent). */
        const val ONNX_BYTES = 62_000_000L

        /** Minimum acceptable ONNX size — detects a truncated download. */
        private const val MIN_ONNX_BYTES = 50_000_000L

        /** Asset paths — populated by CI before the Gradle build. */
        private const val ASSET_ESPEAK = "piper_espeak_ng_data"
        private const val ASSET_TOKENS = "piper_tokens.txt"
    }

    val modelDir  = File(context.filesDir, "piper_vi_medium")
    val espeakDir = File(modelDir, "espeak-ng-data")

    // ─── State ───────────────────────────────────────────────────────────────

    fun isModelReady(): Boolean =
        File(modelDir, MODEL_NAME).let { it.exists() && it.length() >= MIN_ONNX_BYTES } &&
        File(modelDir, "tokens.txt").exists() &&
        espeakDir.isDirectory && (espeakDir.list()?.isNotEmpty() == true)

    fun deleteModel() { modelDir.deleteRecursively() }

    // ─── Download ────────────────────────────────────────────────────────────

    /**
     * Sets up the model:
     *   1. Copies espeak-ng-data + tokens.txt from APK assets to filesDir (fast, no network).
     *   2. Downloads the ONNX model from HuggingFace if not already present (~62 MB).
     *      Supports resuming an interrupted download via HTTP Range header.
     *
     * @param onProgress callback(bytesDownloaded, totalBytes)
     * @throws Exception on unrecoverable failure — caller decides whether to clean up
     */
    suspend fun downloadModel(onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        modelDir.mkdirs()

        // 1. Copy bundled assets (instant, no network)
        copyBundledAssets()

        // 2. Download ONNX with resume support
        val onnxDest = File(modelDir, MODEL_NAME)
        if (onnxDest.length() >= MIN_ONNX_BYTES) {
            // Already have a valid ONNX — assets were re-copied above, we're done
            onProgress(ONNX_BYTES, ONNX_BYTES)
            return@withContext
        }

        downloadOnnxWithResume(onnxDest, onProgress)

        // 3. Verify integrity
        val onnxSize = onnxDest.length()
        if (onnxSize < MIN_ONNX_BYTES) {
            throw Exception("Model bị lỗi khi tải (${onnxSize / 1_000_000} MB / 62 MB). Thử lại.")
        }

        onProgress(ONNX_BYTES, ONNX_BYTES)
    }

    /**
     * Downloads the ONNX file, resuming from the current file size if possible.
     * On each retry: tries to resume first; if the server doesn't support Range,
     * falls back to a full download.  Does NOT delete the partial file before
     * retrying — that preserves the resume capability.
     */
    private fun downloadOnnxWithResume(onnxDest: File, onProgress: (Long, Long) -> Unit) {
        retryIO("onnx") {
            val resumeFrom = onnxDest.length()
            val conn       = openUrl(ONNX_URL, readTimeoutMs = 300_000, rangeStart = resumeFrom)
            val isResume   = conn.responseCode == HttpURLConnection.HTTP_PARTIAL

            if (!isResume && resumeFrom > 0) {
                // Server doesn't support Range → start fresh
                onnxDest.delete()
            }

            val offset        = if (isResume) resumeFrom else 0L
            val serverLen     = conn.contentLengthLong
            val total = when {
                isResume  && serverLen > 0 -> resumeFrom + serverLen   // full size
                !isResume && serverLen > 0 -> serverLen
                else -> ONNX_BYTES
            }
            var downloaded = offset

            try {
                conn.inputStream.use { input ->
                    // append=true keeps bytes already on disk when resuming
                    FileOutputStream(onnxDest, isResume).use { output ->
                        val buf = ByteArray(64 * 1024)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            output.write(buf, 0, n)
                            downloaded += n
                            onProgress(downloaded, total)
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    // ─── Asset copy ──────────────────────────────────────────────────────────

    /**
     * Copies espeak-ng-data and tokens.txt from APK assets to [modelDir].
     * Skips files that are already present (idempotent, fast on subsequent calls).
     */
    private fun copyBundledAssets() {
        val assets = context.assets

        // tokens.txt
        val tokensDest = File(modelDir, "tokens.txt")
        if (!tokensDest.exists()) {
            assets.open(ASSET_TOKENS).use { it.copyTo(tokensDest.outputStream()) }
        }

        // espeak-ng-data/ (recursive)
        if (!espeakDir.isDirectory || espeakDir.list()?.isEmpty() != false) {
            copyAssetDir(assets, ASSET_ESPEAK, espeakDir)
        }
    }

    private fun copyAssetDir(assets: AssetManager, assetPath: String, destDir: File) {
        destDir.mkdirs()
        for (child in assets.list(assetPath) ?: return) {
            val childAsset = "$assetPath/$child"
            val childDest  = File(destDir, child)
            try {
                // Open as a file — throws if it's actually a directory
                assets.open(childAsset).use { it.copyTo(childDest.outputStream()) }
            } catch (_: Exception) {
                // It's a sub-directory — recurse
                copyAssetDir(assets, childAsset, childDest)
            }
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Retries [block] up to 3 times with 2 s / 4 s back-off. */
    private fun <T> retryIO(tag: String, block: () -> T): T {
        var lastErr: Exception? = null
        repeat(3) { attempt ->
            try { return block() } catch (e: Exception) {
                lastErr = e
                if (attempt < 2) Thread.sleep(2000L * (attempt + 1))
            }
        }
        throw lastErr ?: Exception("Lỗi không xác định: $tag")
    }

    /**
     * Opens a URL following HTTP redirects (absolute and relative Location headers).
     * If [rangeStart] > 0, adds a Range header to resume a partial download.
     */
    private fun openUrl(urlStr: String, readTimeoutMs: Int = 90_000, rangeStart: Long = 0): HttpURLConnection {
        var current = urlStr
        repeat(10) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 Android")
            if (rangeStart > 0) conn.setRequestProperty("Range", "bytes=$rangeStart-")
            conn.connectTimeout = 30_000
            conn.readTimeout    = readTimeoutMs
            conn.connect()
            return when (conn.responseCode) {
                HttpURLConnection.HTTP_OK,
                HttpURLConnection.HTTP_PARTIAL -> conn
                HttpURLConnection.HTTP_MOVED_PERM,
                HttpURLConnection.HTTP_MOVED_TEMP,
                307, 308 -> {
                    val loc = conn.getHeaderField("Location")
                        ?: throw Exception("Redirect without Location header")
                    conn.disconnect()
                    current = if (loc.startsWith("http://") || loc.startsWith("https://")) loc
                              else URL(URL(current), loc).toString()
                    return@repeat
                }
                else -> throw Exception("HTTP ${conn.responseCode} for $current")
            }
        }
        throw Exception("Too many redirects for $urlStr")
    }
}
