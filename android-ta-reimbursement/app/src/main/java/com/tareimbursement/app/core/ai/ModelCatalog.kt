package com.tareimbursement.app.core.ai

/** An open-source on-device model the app can download and run with MediaPipe. */
data class AiModel(
    val id: String,
    val displayName: String,
    val family: ModelFamily,
    val repo: String,
    val fileName: String,
    val sizeBytes: Long,
    /** SHA-256 of the file, checked after download (null when the hoster doesn't publish it without login). */
    val sha256: String?,
    val minRamGb: Int,
    /** Reads the bill image directly (multimodal) instead of only the OCR text. */
    val vision: Boolean,
    /** Needs a free Hugging Face account that accepted the model licence (token entered in Settings). */
    val gated: Boolean,
    val license: String,
) {
    val url: String get() = "https://huggingface.co/$repo/resolve/main/$fileName"
    val sizeLabel: String get() = if (sizeBytes >= 1_000_000_000) "%.1f GB".format(sizeBytes / 1e9) else "${sizeBytes / 1_000_000} MB"
}

object ModelCatalog {
    val QWEN_1_5B = AiModel(
        "qwen2.5-1.5b", "Qwen 2.5 1.5B (text, recommended)", ModelFamily.QWEN,
        "litert-community/Qwen2.5-1.5B-Instruct", "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        1_597_913_616L, "8d867a7c93a6acf2892f08e0174e2f6f351ad256b7e3cfb6d6cd9c89794b42e0",
        minRamGb = 6, vision = false, gated = false, license = "Apache-2.0",
    )
    val QWEN_0_5B = AiModel(
        "qwen2.5-0.5b", "Qwen 2.5 0.5B (text, small phones)", ModelFamily.QWEN,
        "litert-community/Qwen2.5-0.5B-Instruct", "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        546_660_344L, "e608953f169aeb1bd7b9155fec2559825e08453fc209b84eda3a781ed0452fd2",
        minRamGb = 4, vision = false, gated = false, license = "Apache-2.0",
    )
    val GEMMA_3N = AiModel(
        "gemma-3n-e2b", "Gemma 3n E2B (reads the bill image)", ModelFamily.GEMMA,
        "google/gemma-3n-E2B-it-litert-preview", "gemma-3n-E2B-it-int4.task",
        3_136_226_711L, null,
        minRamGb = 8, vision = true, gated = true, license = "Gemma Terms of Use",
    )
    val all = listOf(GEMMA_3N, QWEN_1_5B, QWEN_0_5B)

    fun byId(id: String?) = all.firstOrNull { it.id == id }

    /**
     * Auto-pick by phone: 8 GB+ RAM and a Hugging Face token → Gemma 3n (vision); 6 GB+ → Qwen 1.5B;
     * 4 GB+ → Qwen 0.5B; smaller phones keep the built-in reader only.
     */
    fun recommend(totalRamBytes: Long, hasHfToken: Boolean): AiModel? {
        // Phones report a little less than their marketed RAM (e.g. 7.4 GB for an "8 GB" phone).
        val gb = totalRamBytes / 1e9 + 0.8
        return when {
            gb >= GEMMA_3N.minRamGb && hasHfToken -> GEMMA_3N
            gb >= QWEN_1_5B.minRamGb -> QWEN_1_5B
            gb >= QWEN_0_5B.minRamGb -> QWEN_0_5B
            else -> null
        }
    }

    fun ramLabel(totalRamBytes: Long) = "%.0f GB".format(totalRamBytes / 1e9 + 0.4)
}
