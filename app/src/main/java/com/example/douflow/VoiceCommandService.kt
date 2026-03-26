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
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class VoiceCommandService : Service() {

    companion object {
        const val TAG = "VoiceCommandService"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "voice_command_channel"
        const val ACTION_LOG = "com.example.douflow.LOG"
        const val EXTRA_LOG = "msg"

        private val NEXT_KEYWORDS = setOf(
            "下一个",
            "下一条",
            "下一页",
            "next",
            "跳过"
        )
        private val PREV_KEYWORDS = setOf(
            "上一个",
            "上一条",
            "上一页",
            "previous",
            "返回"
        )
        private val PAUSE_KEYWORDS = setOf(
            "暂停",
            "pause",
            "点击"
        )

        private const val CHUNK_DURATION_MS = 100
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default)
    private val sherpaConfig by lazy { SherpaConfig.fromBuildConfig() }

    private var recognizer: OnlineRecognizer? = null
    private var onlineStream: OnlineStream? = null
    private var audioRecord: AudioRecord? = null
    private var listeningJob: Job? = null
    private var lastPartialText: String = ""
    private var handledInCurrentUtterance = false
    private var stoppedByError = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        initSherpa()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (recognizer != null && listeningJob == null) {
            listeningJob = serviceScope.launch(Dispatchers.IO) {
                listenLoop()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        VoiceServiceState.isRunning = false
        listeningJob?.cancel()
        listeningJob = null
        releaseAudioRecord()
        onlineStream?.release()
        onlineStream = null
        recognizer?.release()
        recognizer = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun initSherpa() {
        val modelDir = sherpaConfig.modelDir
        if (modelDir.isBlank()) {
            sendLog("[SHERPA] Missing sherpa.model.dir. Service stopped.")
            stopSelf()
            return
        }

        val modelFiles = listOf(
            "$modelDir/tokens.txt",
            "$modelDir/encoder-epoch-99-avg-1.int8.onnx",
            "$modelDir/decoder-epoch-99-avg-1.int8.onnx",
            "$modelDir/joiner-epoch-99-avg-1.int8.onnx"
        )

        val missingFile = modelFiles.firstOrNull { !assetExists(it) }
        if (missingFile != null) {
            sendLog("[SHERPA] Model asset not found: $missingFile")
            stopSelf()
            return
        }

        try {
            sendLog("[SHERPA] Loading model: $modelDir")
            recognizer = OnlineRecognizer(
                assets,
                OnlineRecognizerConfig(
                    featConfig = FeatureConfig(
                        sampleRate = sherpaConfig.sampleRate,
                        featureDim = sherpaConfig.featureDim,
                        dither = 0.0f
                    ),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = "$modelDir/encoder-epoch-99-avg-1.int8.onnx",
                            decoder = "$modelDir/decoder-epoch-99-avg-1.int8.onnx",
                            joiner = "$modelDir/joiner-epoch-99-avg-1.int8.onnx"
                        ),
                        tokens = "$modelDir/tokens.txt",
                        numThreads = sherpaConfig.numThreads,
                        provider = sherpaConfig.provider,
                        debug = false
                    ),
                    endpointConfig = EndpointConfig(
                        rule1 = EndpointRule(
                            mustContainNonSilence = false,
                            minTrailingSilence = sherpaConfig.rule1MinTrailingSilence,
                            minUtteranceLength = 0.0f
                        ),
                        rule2 = EndpointRule(
                            mustContainNonSilence = true,
                            minTrailingSilence = sherpaConfig.rule2MinTrailingSilence,
                            minUtteranceLength = 0.0f
                        ),
                        rule3 = EndpointRule(
                            mustContainNonSilence = false,
                            minTrailingSilence = 0.0f,
                            minUtteranceLength = sherpaConfig.rule3MinUtteranceLength
                        )
                    ),
                    enableEndpoint = sherpaConfig.enableEndpoint,
                    decodingMethod = sherpaConfig.decodingMethod,
                    blankPenalty = sherpaConfig.blankPenalty
                )
            )
            sendLog("[SHERPA] Ready.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Sherpa-ONNX", e)
            sendLog("[SHERPA] Initialization failed: ${e.message}")
            stopSelf()
        }
    }

    private suspend fun listenLoop() {
        val recognizer = recognizer ?: return
        val recorder = ensureAudioRecord()
        if (recorder == null) {
            sendLog("[SHERPA] Failed to initialize audio recorder.")
            stopSelf()
            return
        }

        val stream = recognizer.createStream()
        onlineStream = stream
        val chunkSize = sherpaConfig.sampleRate * CHUNK_DURATION_MS / 1000
        val shortBuffer = ShortArray(chunkSize)

        try {
            recorder.startRecording()
            sendLog("[SHERPA] Listening started.")

            while (listeningJob?.isActive == true && serviceScope.isActive) {
                val read = recorder.read(shortBuffer, 0, shortBuffer.size)
                if (read <= 0) {
                    if (!stoppedByError) {
                        stoppedByError = true
                        sendLog("[SHERPA] audioRecord.read returned $read")
                    }
                    continue
                }
                stoppedByError = false

                stream.acceptWaveform(shortBuffer.toFloatArray(read), sherpaConfig.sampleRate)

                while (recognizer.isReady(stream)) {
                    recognizer.decode(stream)
                }

                val currentText = recognizer.getResult(stream).text.trim()
                handlePartialResult(currentText)

                if (recognizer.isEndpoint(stream)) {
                    handleEndpointResult(currentText)
                    recognizer.reset(stream)
                    lastPartialText = ""
                    handledInCurrentUtterance = false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "listenLoop failed", e)
            sendLog("[SHERPA] Listening failed: ${e.message}")
        } finally {
            stream.release()
            if (onlineStream === stream) {
                onlineStream = null
            }
            releaseAudioRecord()
        }
    }

    private fun handlePartialResult(text: String) {
        if (text.isBlank() || text == lastPartialText) {
            return
        }

        lastPartialText = text
        sendLog("[PARTIAL] $text")

        if (!handledInCurrentUtterance && tryDispatchCommand(text, source = "partial")) {
            handledInCurrentUtterance = true
        }
    }

    private fun handleEndpointResult(text: String) {
        if (text.isBlank()) {
            sendLog("[RESULT] <empty>")
            return
        }

        sendLog("[RESULT] $text")
        if (!handledInCurrentUtterance) {
            handledInCurrentUtterance = tryDispatchCommand(text, source = "final")
        }
    }

    private fun tryDispatchCommand(text: String, source: String): Boolean {
        val normalized = normalizeText(text)
        return when {
            NEXT_KEYWORDS.any { normalized.contains(it) } -> {
                sendLog("[COMMAND/$source] next")
                sendAccessibilityBroadcast(DouyinAccessibilityService.ACTION_SWIPE_UP)
                true
            }

            PREV_KEYWORDS.any { normalized.contains(it) } -> {
                sendLog("[COMMAND/$source] previous")
                sendAccessibilityBroadcast(DouyinAccessibilityService.ACTION_SWIPE_DOWN)
                true
            }

            PAUSE_KEYWORDS.any { normalized.contains(it) } -> {
                sendLog("[COMMAND/$source] tap_center")
                sendAccessibilityBroadcast(DouyinAccessibilityService.ACTION_TAP_CENTER)
                true
            }

            else -> false
        }
    }

    private fun normalizeText(text: String): String {
        return text
            .lowercase(Locale.ROOT)
            .replace(" ", "")
            .replace("\n", "")
            .replace("\u3002", "")
            .replace("\uff0c", "")
            .replace(",", "")
            .replace("\uff01", "")
            .replace("!", "")
            .replace("\uff1f", "")
            .replace("?", "")
        }

    private fun ensureAudioRecord(): AudioRecord? {
        audioRecord?.let { return it }

        val minBufferSize = AudioRecord.getMinBufferSize(
            sherpaConfig.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize <= 0) {
            return null
        }

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sherpaConfig.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBufferSize * 2, sherpaConfig.sampleRate / 2)
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return null
        }

        audioRecord = recorder
        return recorder
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

    private fun ShortArray.toFloatArray(read: Int): FloatArray {
        return FloatArray(read) { index ->
            this[index] / 32768.0f
        }
    }

    private fun assetExists(path: String): Boolean {
        return try {
            assets.open(path).close()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun sendAccessibilityBroadcast(action: String) {
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
                description = "Sherpa-ONNX foreground service"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("DouFlow Sherpa Control")
                .setContentText("Streaming next / previous / pause commands")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build()
        } else {
            NotificationCompat.Builder(this)
                .setContentTitle("DouFlow Sherpa Control")
                .setContentText("Streaming next / previous / pause commands")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        }
    }
}
