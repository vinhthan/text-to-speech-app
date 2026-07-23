package com.texttospeech.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads and manages the Piper TTS model files from HuggingFace.
 *
 * Model: csukuangfj/vits-piper-vi_VN-vais1000-medium
 * Structure after download:
 *   filesDir/piper_vi_medium/
 *     ├── vi_VN-vais1000-medium.onnx   (~60MB, main model)
 *     ├── tokens.txt                   (~3KB)
 *     └── espeak-ng-data/              (~3MB, 200+ small files)
 */
class ModelManager(private val context: Context) {

    companion object {
        const val HF_REPO      = "csukuangfj/vits-piper-vi_VN-vais1000-medium"
        const val MODEL_NAME   = "vi_VN-vais1000-medium.onnx"
        private const val HF_API_URL = "https://huggingface.co/api/models/$HF_REPO"
        private const val HF_RAW_URL = "https://huggingface.co/$HF_REPO/resolve/main"

        // Minimum acceptable ONNX size — detects a truncated download
        private const val MIN_ONNX_BYTES = 50_000_000L   // 50 MB

        // Files to skip when downloading
        private val SKIP_FILES = setOf(
            ".gitattributes", "MODEL_CARD", "vits-piper.py", "vits-piper.sh",
            "vi_VN-vais1000-medium.onnx.json"
        )
    }

    val modelDir  = File(context.filesDir, "piper_vi_medium")
    val espeakDir = File(modelDir, "espeak-ng-data")

    // ─── State ───────────────────────────────────────────────────────────────

    fun isModelReady(): Boolean =
        File(modelDir, MODEL_NAME).let { it.exists() && it.length() >= MIN_ONNX_BYTES } &&
        File(modelDir, "tokens.txt").exists() &&
        espeakDir.isDirectory && (espeakDir.list()?.isNotEmpty() == true)

    fun modelSizeBytes(): Long = File(modelDir, MODEL_NAME).length()

    fun deleteModel() { modelDir.deleteRecursively() }

    // ─── Download ────────────────────────────────────────────────────────────

    /**
     * Downloads the complete model from HuggingFace.
     * Each file is retried up to 3 times on failure.
     * @param onProgress callback(bytesDownloaded, totalEstimatedBytes)
     * @throws Exception if download ultimately fails — caller cleans up
     */
    suspend fun downloadModel(onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        modelDir.mkdirs()

        // 1. Get file list from HuggingFace model API (with retry)
        val fileList = retryIO(tag = "fetchFileList") { fetchFileList() }

        // 2. Partition: large ONNX first, then everything else
        val onnxFile   = fileList.firstOrNull { it.endsWith(MODEL_NAME) }
            ?: throw Exception("Không tìm thấy file model $MODEL_NAME trên HuggingFace")
        val otherFiles = fileList.filter { it != onnxFile && it !in SKIP_FILES }

        // Estimated total: ONNX ~62MB + rest ~3MB
        val estimatedTotal = 65_000_000L
        var downloaded = 0L

        // 3. Download ONNX model (large file — retry individually)
        val onnxDest = File(modelDir, MODEL_NAME)
        retryIO(tag = MODEL_NAME) {
            onnxDest.delete()   // remove partial file before each attempt
            downloaded = downloadFile(
                urlStr      = "$HF_RAW_URL/$onnxFile",
                dest        = onnxDest,
                prevBytes   = downloaded,
                onBytes     = { bytes -> downloaded += bytes; onProgress(downloaded, estimatedTotal) },
                readTimeoutMs = 300_000   // 5-min timeout for the large model file
            )
        }

        // Verify ONNX is complete
        val onnxSize = onnxDest.length()
        if (onnxSize < MIN_ONNX_BYTES) {
            throw Exception("File model bị không đầy đủ (${onnxSize / 1_000_000}MB < 50MB). Vui lòng thử lại.")
        }

        // 4. Download supporting files (tokens.txt + espeak-ng-data/*)
        for (filePath in otherFiles) {
            val dest = File(modelDir, filePath)
            dest.parentFile?.mkdirs()
            retryIO(tag = filePath) {
                dest.delete()
                downloaded = downloadFile(
                    urlStr    = "$HF_RAW_URL/$filePath",
                    dest      = dest,
                    prevBytes = downloaded,
                    onBytes   = { bytes -> downloaded += bytes; onProgress(downloaded, estimatedTotal) }
                )
            }
        }

        // Signal 100% complete
        onProgress(estimatedTotal, estimatedTotal)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Retries [block] up to 3 times with exponential back-off (2 s, 4 s).
     * Throws the last exception if all attempts fail.
     */
    private fun <T> retryIO(tag: String, block: () -> T): T {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                lastError = e
                if (attempt < 2) Thread.sleep(2000L * (attempt + 1))
            }
        }
        throw lastError ?: Exception("Lỗi không xác định khi tải: $tag")
    }

    /**
     * Fetches the model file list from the HuggingFace model API.
     * Returns paths relative to the repo root (e.g. "espeak-ng-data/vi").
     */
    private fun fetchFileList(): List<String> {
        val conn = openUrl(HF_API_URL)
        val json = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        val obj = JSONObject(json)
        val siblings = obj.getJSONArray("siblings")
        return (0 until siblings.length()).map { i ->
            siblings.getJSONObject(i).getString("rfilename")
        }
    }

    /**
     * Downloads [urlStr] to [dest].
     * @param prevBytes bytes already counted before this call (used for retry accounting)
     * @param readTimeoutMs per-read idle timeout; default 90 s
     * @return [prevBytes] unchanged — progress accounting is via [onBytes] side-effect
     */
    private fun downloadFile(
        urlStr: String,
        dest: File,
        prevBytes: Long,
        onBytes: (Long) -> Unit,
        readTimeoutMs: Int = 90_000
    ): Long {
        val conn = openUrl(urlStr, readTimeoutMs)
        try {
            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        output.write(buf, 0, n)
                        onBytes(n.toLong())
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        return prevBytes   // unused; progress tracked via onBytes
    }

    /**
     * Opens a URL following HTTP redirects across different hosts.
     * Handles both absolute and relative Location headers.
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
