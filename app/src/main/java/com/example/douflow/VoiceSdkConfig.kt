package com.example.douflow

import android.content.Context
import android.provider.Settings

data class VoiceSdkConfig(
    val apiKey: String,
    val url: String,
    val model: String,
    val sampleRate: Int = 16_000,
    val workspaceDirName: String = "douflow_nui"
) {
    fun isConfigured(): Boolean = apiKey.isNotBlank()

    fun deviceId(context: Context): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() }
            ?: context.packageName
    }

    companion object {
        fun fromBuildConfig(): VoiceSdkConfig {
            return VoiceSdkConfig(
                apiKey = BuildConfig.VOICE_SDK_API_KEY.trim(),
                url = BuildConfig.VOICE_SDK_URL.trim(),
                model = BuildConfig.VOICE_SDK_MODEL.trim()
            )
        }
    }
}
