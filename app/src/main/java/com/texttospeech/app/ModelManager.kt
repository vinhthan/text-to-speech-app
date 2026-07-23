package com.texttospeech.app

import android.content.Context
import android.content.res.AssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages the Piper TTS model files.
 *
 * All model files are bundled inside the APK as assets (extracted from the
 * sherpa-onnx tarball during CI build) — no network download required at runtime.
 *
 *   • espeak-ng-data  (~5 MB, 355 files) → assets/piper_espeak_ng_data/
 *   • tokens.txt                          → assets/piper_tokens.txt
 *   • ONNX model      (~62 MB)            → assets/piper_model.onnx
 *
 * On first launch [setupModel] copies everything from assets to filesDir once.
 * Subsequent launches skip files that already exist (idempotent).
 */
class ModelManager(private val context: Context) {

    companion object {
        const val MODEL_NAME = "vi_VN-vais1000-medium.onnx"

        /** Minimum acceptable ONNX size on disk — detects a truncated copy. */
        private const val MIN_ONNX_BYTES = 50_000_000L

        /** Approximate ONNX size — used as the total for progress callbacks. */
        const val ONNX_BYTES = 62_000_000L

        private const val ASSET_ONNX   = "piper_model.onnx"
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

    // ─── Setup ───────────────────────────────────────────────────────────────

    /**
     * Copies all model files from APK assets into [modelDir] (first launch only).
     * Subsequent calls skip files that are already present.
     *
     * @param onProgress callback(bytesCopied, totalBytes) — reports ONNX copy progress
     * @throws Exception if any asset is missing (APK was built without CI bundle step)
     */
    suspend fun setupModel(onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        modelDir.mkdirs()
        val assets = context.assets

        // 1. tokens.txt (tiny — no progress needed)
        val tokensDest = File(modelDir, "tokens.txt")
        if (!tokensDest.exists()) {
            assets.open(ASSET_TOKENS).use { it.copyTo(tokensDest.outputStream()) }
        }

        // 2. espeak-ng-data/ (355 files, ~5 MB — fast)
        if (!espeakDir.isDirectory || espeakDir.list()?.isEmpty() != false) {
            copyAssetDir(assets, ASSET_ESPEAK, espeakDir)
        }

        // 3. ONNX (~62 MB) — copy with progress so the UI can show a bar
        val onnxDest = File(modelDir, MODEL_NAME)
        if (onnxDest.length() < MIN_ONNX_BYTES) {
            onnxDest.delete()
            assets.open(ASSET_ONNX).use { input ->
                onnxDest.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var copied = 0L
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        output.write(buf, 0, n)
                        copied += n
                        onProgress(copied, ONNX_BYTES)
                    }
                }
            }
            // Verify the copy is complete
            val written = onnxDest.length()
            if (written < MIN_ONNX_BYTES) {
                onnxDest.delete()
                throw Exception("ONNX copy incomplete ($written bytes). APK may be corrupt.")
            }
        }

        onProgress(ONNX_BYTES, ONNX_BYTES)
    }

    // ─── Asset helpers ───────────────────────────────────────────────────────

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
}
