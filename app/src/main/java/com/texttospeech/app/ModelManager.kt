package com.texttospeech.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads and manages the Piper TTS model.
 *
 * Source: sherpa-onnx GitHub releases — single tar.bz2 (67 MB compressed).
 * Extraction is streamed: the tarball is never saved to disk in full.
 *
 * Final layout on device (filesDir/piper_vi_medium/):
 *   ├── vi_VN-vais1000-medium.onnx   (~60 MB)
 *   ├── tokens.txt
 *   └── espeak-ng-data/  (355 files for all espeak-ng languages)
 */
class ModelManager(private val context: Context) {

    companion object {
        const val MODEL_NAME = "vi_VN-vais1000-medium.onnx"

        /** Single tarball — replaces 362 individual HuggingFace requests. */
        private const val TARBALL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-vi_VN-vais1000-medium.tar.bz2"

        /** Known compressed size in bytes — used when Content-Length is absent. */
        private const val TARBALL_BYTES = 67_154_040L

        /** Top-level directory inside the tarball to strip from paths. */
        private const val TAR_PREFIX = "vits-piper-vi_VN-vais1000-medium/"

        /** Minimum acceptable ONNX size — detects a truncated extraction. */
        private const val MIN_ONNX_BYTES = 50_000_000L
    }

    val modelDir  = File(context.filesDir, "piper_vi_medium")
    val espeakDir = File(modelDir, "espeak-ng-data")

    // ─── State ───────────────────────────────────────────────────────────────

    fun isModelReady(): Boolean =
        File(modelDir, MODEL_NAME).let { it.exists() && it.length() >= MIN_ONNX_BYTES } &&
        File(modelDir, "tokens.txt").exists() &&
        espeakDir.isDirectory && (espeakDir.list()?.isNotEmpty() == true)

    fun deleteModel() { modelDir.deleteRecursively() }

    // ─── Download + extraction ────────────────────────────────────────────────

    /**
     * Downloads the model tarball and extracts it on-the-fly (no temporary file).
     * Retries the entire operation up to 3 times on failure.
     *
     * @param onProgress callback(compressedBytesRead, totalCompressedBytes)
     * @throws Exception on unrecoverable failure — caller cleans up
     */
    suspend fun downloadModel(onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        modelDir.mkdirs()

        retryIO("tarball") {
            val conn = openUrl(TARBALL_URL, readTimeoutMs = 600_000)   // 10-min cap
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: TARBALL_BYTES
            var downloaded = 0L

            try {
                // Wrap HTTP stream in a progress-counting shim, then pipe through
                // BZip2 decompressor and TAR reader — no temp file needed.
                val counting = object : FilterInputStream(conn.inputStream) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val n = super.read(b, off, len)
                        if (n > 0) { downloaded += n; onProgress(downloaded, total) }
                        return n
                    }
                }

                BZip2CompressorInputStream(counting).use { bzIn ->
                    TarArchiveInputStream(bzIn).use { tarIn ->
                        var entry = tarIn.nextTarEntry
                        while (entry != null) {
                            // Strip top-level directory (e.g. "vits-piper-.../tokens.txt" → "tokens.txt")
                            val rel = entry.name.removePrefix(TAR_PREFIX)
                            if (rel.isNotEmpty()) {
                                val dest = File(modelDir, rel)
                                if (entry.isDirectory) {
                                    dest.mkdirs()
                                } else {
                                    dest.parentFile?.mkdirs()
                                    dest.outputStream().use { out -> tarIn.copyTo(out) }
                                }
                            }
                            entry = tarIn.nextTarEntry
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
        }

        // Verify the main model file is intact
        val onnxSize = File(modelDir, MODEL_NAME).length()
        if (onnxSize < MIN_ONNX_BYTES) {
            throw Exception("Model bị lỗi khi giải nén (${onnxSize / 1_000_000} MB < 50 MB). Thử lại.")
        }

        onProgress(TARBALL_BYTES, TARBALL_BYTES)   // clamp to 100 %
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Retries [block] up to 3 times with 2 s / 4 s back-off. */
    private fun <T> retryIO(tag: String, block: () -> T): T {
        var lastErr: Exception? = null
        repeat(3) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                lastErr = e
                if (attempt < 2) Thread.sleep(2000L * (attempt + 1))
            }
        }
        throw lastErr ?: Exception("Lỗi không xác định: $tag")
    }

    /**
     * Opens a URL following HTTP redirects (absolute and relative Location headers).
     * [readTimeoutMs] is the per-read idle timeout; set it large for big files.
     */
    private fun openUrl(urlStr: String, readTimeoutMs: Int = 90_000): HttpURLConnection {
        var current = urlStr
        repeat(10) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 Android")
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
                    current = if (loc.startsWith("http://") || loc.startsWith("https://")) {
                        loc
                    } else {
                        URL(URL(current), loc).toString()
                    }
                    return@repeat
                }
                else -> throw Exception("HTTP ${conn.responseCode} for $current")
            }
        }
        throw Exception("Too many redirects for $urlStr")
    }
}
