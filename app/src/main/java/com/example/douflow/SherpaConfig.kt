package com.example.douflow

data class SherpaConfig(
    val modelDir: String,
    val sampleRate: Int = 16_000,
    val featureDim: Int = 80,
    val numThreads: Int = 2,
    val provider: String = "cpu",
    val decodingMethod: String = "greedy_search",
    val enableEndpoint: Boolean = true,
    val rule1MinTrailingSilence: Float = 2.4f,
    val rule2MinTrailingSilence: Float = 0.35f,
    val rule3MinUtteranceLength: Float = 8.0f,
    val blankPenalty: Float = 0.0f
) {
    companion object {
        fun fromBuildConfig(): SherpaConfig {
            return SherpaConfig(
                modelDir = BuildConfig.SHERPA_MODEL_DIR.trim()
            )
        }
    }
}
