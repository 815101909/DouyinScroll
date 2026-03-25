package com.example.douflow

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.alibaba.idst.nui.AsrResult
import com.alibaba.idst.nui.CommonUtils
import com.alibaba.idst.nui.Constants
import com.alibaba.idst.nui.INativeNuiCallback
import com.alibaba.idst.nui.KwsResult
import com.alibaba.idst.nui.NativeNui
import org.json.JSONObject
import java.io.File
import java.util.Locale

class VoiceCommandService : Service(), INativeNuiCallback {

    companion object {
        const val TAG = "VoiceCommandService"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "voice_command_channel"
        const val ACTION_LOG = "com.example.douflow.LOG"
        const val EXTRA_LOG = "msg"

        private const val RESTART_DELAY_MS = 800L
        private val RESTART_TOKEN = Any()
        private val NEXT_KEYWORDS = setOf(
            "\u4e0b\u4e00\u4e2a",
            "\u4e0b\u4e00\u6761",
            "\u4e0b\u4e00\u9875",
            "next",
            "\u8df3\u8fc7"
        )
        private val PREV_KEYWORDS = setOf(
            "\u4e0a\u4e00\u4e2a",
            "\u4e0a\u4e00\u6761",
            "\u4e0a\u4e00\u9875",
            "previous",
            "\u8fd4\u56de"
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val sdkConfig by lazy { VoiceSdkConfig.fromBuildConfig() }

    private var nuiInstance: NativeNui? = null
    private var audioRecord: AudioRecord? = null
    private var sdkInitialized = false
    private var dialogRunning = false
    private var debugPath: String = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        initVoiceSdk()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (sdkInitialized) {
            startDialogLoop("service_start")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        VoiceServiceState.isRunning = false
        dialogRunning = false
        mainHandler.removeCallbacksAndMessages(RESTART_TOKEN)
        releaseAudioRecord()
        nuiInstance?.runCatching {
            cancelDialog()
            release()
        }?.onFailure {
            Log.w(TAG, "release sdk failed", it)
        }
        nuiInstance = null
        sdkInitialized = false
        super.onDestroy()
    }

    private fun initVoiceSdk() {
        if (!sdkConfig.isConfigured()) {
            sendLog("[SDK] Missing voice.sdk.apiKey. Service stopped.")
            stopSelf()
            return
        }

        if (!CommonUtils.setTargetDataDir(sdkConfig.workspaceDirName)) {
            sendLog("[SDK] Failed to set workspace directory.")
            stopSelf()
            return
        }

        if (!CommonUtils.copyAssetsData(this)) {
            sendLog("[SDK] Failed to copy SDK assets.")
            stopSelf()
            return
        }

        debugPath = File(externalCacheDir ?: cacheDir, "voice-sdk-debug").apply {
            mkdirs()
        }.absolutePath

        val nativeNui = NativeNui()
        val result = nativeNui.initialize(
            this,
            buildInitParams(),
            Constants.LogLevel.LOG_LEVEL_VERBOSE,
            true
        )
        if (result == Constants.NuiResultCode.SUCCESS) {
            nuiInstance = nativeNui
            sdkInitialized = true
            sendLog("[SDK] Initialized. Listening started.")
            startDialogLoop("sdk_initialized")
        } else {
            nativeNui.release()
            sendLog("[SDK] Initialization failed: $result")
            stopSelf()
        }
    }

    private fun startDialogLoop(reason: String) {
        if (!sdkInitialized || dialogRunning) return
        val instance = nuiInstance ?: return
        if (!ensureAudioRecord()) {
            sendLog("[SDK] Failed to initialize audio recorder.")
            stopSelf()
            return
        }

        val paramsResult = instance.setParams(buildRecognitionParams())
        if (paramsResult != Constants.NuiResultCode.SUCCESS) {
            sendLog("[SDK] Failed to set params: $paramsResult")
        }

        val startResult = instance.startDialog(Constants.VadMode.TYPE_P2T, buildDialogParams())
        if (startResult == Constants.NuiResultCode.SUCCESS) {
            dialogRunning = true
            sendLog("[SDK] Recognition session started.")
            Log.i(TAG, "startDialog success, reason=$reason")
        } else {
            dialogRunning = false
            sendLog("[SDK] Failed to start recognition: $startResult")
            scheduleRestart("start_failed")
        }
    }

    private fun scheduleRestart(reason: String) {
        if (!sdkInitialized) return
        dialogRunning = false
        mainHandler.removeCallbacksAndMessages(RESTART_TOKEN)
        mainHandler.postAtTime(
            { startDialogLoop(reason) },
            RESTART_TOKEN,
            SystemClock.uptimeMillis() + RESTART_DELAY_MS
        )
    }

    private fun buildInitParams(): String {
        return JSONObject().apply {
            put("device_id", sdkConfig.deviceId(this@VoiceCommandService))
            put("url", sdkConfig.url)
            put("apikey", sdkConfig.apiKey)
            put("save_wav", false)
            put("debug_path", debugPath)
            put(
                "log_track_level",
                Constants.LogLevel.toInt(Constants.LogLevel.LOG_LEVEL_NONE).toString()
            )
            put("service_mode", Constants.ModeFullCloud)
        }.toString()
    }

    private fun buildRecognitionParams(): String {
        val nlsConfig = JSONObject().apply {
            put("sample_rate", sdkConfig.sampleRate)
            put("sr_format", "pcm")
            put("semantic_punctuation_enabled", false)
            put("max_sentence_silence", 500)
            put("multi_threshold_mode_enabled", true)
            put("heartbeat", true)
            put("punctuation_prediction_enabled", true)
            if (sdkConfig.model.isNotBlank()) {
                put("model", sdkConfig.model)
            }
        }
        return JSONObject().apply {
            put("nls_config", nlsConfig)
            put("service_type", Constants.kServiceTypeSpeechTranscriber)
        }.toString()
    }

    private fun buildDialogParams(): String {
        return JSONObject().apply {
            put("apikey", sdkConfig.apiKey)
        }.toString()
    }

    private fun ensureAudioRecord(): Boolean {
        if (audioRecord != null) return true
        val minBuffer = AudioRecord.getMinBufferSize(
            sdkConfig.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return false

        val bufferSize = maxOf(minBuffer, sdkConfig.sampleRate / 1000 * 20 * 2 * 4)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sdkConfig.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return false
        }
        audioRecord = recorder
        return true
    }

    private fun releaseAudioRecord() {
        audioRecord?.runCatching {
            if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                stop()
            }
            release()
        }
        audioRecord = null
    }

    override fun onNuiEventCallback(
        event: Constants.NuiEvent,
        resultCode: Int,
        arg2: Int,
        kwsResult: KwsResult?,
        asrResult: AsrResult?
    ) {
        Log.i(TAG, "onNuiEventCallback event=$event resultCode=$resultCode arg2=$arg2")
        when (event) {
            Constants.NuiEvent.EVENT_TRANSCRIBER_STARTED -> {
                sendLog("[SDK] Transcriber started.")
            }

            Constants.NuiEvent.EVENT_VAD_START -> {
                sendLog("[SDK] Voice activity detected.")
            }

            Constants.NuiEvent.EVENT_ASR_PARTIAL_RESULT -> {
                extractText(asrResult)?.takeIf { it.isNotBlank() }?.let {
                    sendLog("[PARTIAL] $it")
                }
            }

            Constants.NuiEvent.EVENT_SENTENCE_END,
            Constants.NuiEvent.EVENT_ASR_RESULT -> {
                val text = extractText(asrResult).orEmpty()
                sendLog("[RESULT] $text")
                processVoiceCommand(listOf(text))
            }

            Constants.NuiEvent.EVENT_TRANSCRIBER_COMPLETE -> {
                dialogRunning = false
                sendLog("[SDK] Transcriber completed.")
                scheduleRestart("transcriber_complete")
            }

            Constants.NuiEvent.EVENT_VAD_TIMEOUT,
            Constants.NuiEvent.EVENT_ASR_ERROR,
            Constants.NuiEvent.EVENT_DIALOG_ERROR,
            Constants.NuiEvent.EVENT_MIC_ERROR,
            Constants.NuiEvent.EVENT_ONESHOT_TIMEOUT -> {
                dialogRunning = false
                val detail = extractText(asrResult).orEmpty()
                val suffix = if (detail.isBlank()) "" else " $detail"
                sendLog("[SDK] $event code=$resultCode$suffix")
                scheduleRestart("retry_event")
            }

            else -> Unit
        }
    }

    override fun onNuiNeedAudioData(buffer: ByteArray, len: Int): Int {
        val recorder = audioRecord ?: return -1
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            return -1
        }
        return recorder.read(buffer, 0, len)
    }

    override fun onNuiAudioStateChanged(state: Constants.AudioState) {
        when (state) {
            Constants.AudioState.STATE_OPEN -> {
                if (!ensureAudioRecord()) {
                    sendLog("[SDK] Failed to open audio recorder.")
                    return
                }
                sendLog("[SDK] Audio recorder opened.")
                audioRecord?.runCatching {
                    if (recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        startRecording()
                    }
                }
            }

            Constants.AudioState.STATE_PAUSE -> {
                sendLog("[SDK] Audio recorder paused.")
                audioRecord?.runCatching {
                    if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        stop()
                    }
                }
            }

            Constants.AudioState.STATE_CLOSE -> {
                sendLog("[SDK] Audio recorder closed.")
                releaseAudioRecord()
            }
        }
    }

    override fun onNuiAudioRMSChanged(valume: Float) = Unit

    override fun onNuiVprEventCallback(event: Constants.NuiVprEvent) = Unit

    override fun onNuiLogTrackCallback(level: Constants.LogLevel, log: String) {
        Log.d(TAG, "sdk[$level] $log")
    }

    private fun extractText(asrResult: AsrResult?): String? {
        if (asrResult == null) return null
        val fullResponse = asrResult.allResponse.orEmpty()
        if (fullResponse.isNotBlank()) {
            runCatching {
                val payload = JSONObject(fullResponse).optJSONObject("payload")
                payload?.optString("result").orEmpty()
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return asrResult.asrResult?.takeIf { it.isNotBlank() }
    }

    private fun processVoiceCommand(results: List<String>) {
        for (text in results) {
            val normalized = normalizeText(text)
            when {
                NEXT_KEYWORDS.any { normalized.contains(it) } -> {
                    sendLog("[COMMAND] next")
                    sendSwipeBroadcast(DouyinAccessibilityService.ACTION_SWIPE_UP)
                    return
                }

                PREV_KEYWORDS.any { normalized.contains(it) } -> {
                    sendLog("[COMMAND] previous")
                    sendSwipeBroadcast(DouyinAccessibilityService.ACTION_SWIPE_DOWN)
                    return
                }
            }
        }
        sendLog("[COMMAND] ignored")
    }

    private fun normalizeText(text: String): String {
        return text
            .lowercase(Locale.ROOT)
            .replace(" ", "")
            .replace("\u3002", "")
            .replace("\uff0c", "")
            .replace(",", "")
            .replace("\uff01", "")
            .replace("!", "")
            .replace("\uff1f", "")
            .replace("?", "")
    }

    private fun sendSwipeBroadcast(action: String) {
        sendBroadcast(Intent(action).setPackage(packageName))
    }

    private fun sendLog(message: String) {
        Log.i(TAG, message)
        sendBroadcast(
            Intent(ACTION_LOG)
                .setPackage(packageName)
                .putExtra(EXTRA_LOG, message)
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voice Command Listener",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Voice SDK foreground service"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("DouFlow Voice Control")
                .setContentText("Listening for next/previous commands")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build()
        } else {
            NotificationCompat.Builder(this)
                .setContentTitle("DouFlow Voice Control")
                .setContentText("Listening for next/previous commands")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        }
    }
}
