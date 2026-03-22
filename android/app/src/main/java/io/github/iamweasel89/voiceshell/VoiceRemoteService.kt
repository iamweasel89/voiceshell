package io.github.iamweasel89.voiceshell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.util.Log
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicBoolean

class VoiceRemoteService : Service() {

    interface ConnectionListener {
        fun onConnectionChanged(connected: Boolean, message: String?)

        fun onVoiceDebugMayHaveChanged() {}
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val okHttp = OkHttpClient.Builder()
        .pingInterval(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    @Volatile
    private var webSocket: WebSocket? = null
    private val connecting = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null
    private var destroyed = false

    private val reconnectRunnable = Runnable {
        if (!destroyed && webSocket == null) connectWebSocket()
    }

    var connectionListener: ConnectionListener? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var speechRecognitionActive = false
    private var sessionEmittedWords: List<String> = emptyList()
    private val wordSplitRegex = Regex("\\s+")

    @Volatile
    var speechRecognitionAvailable: Boolean = false
        private set

    /** Between [startListening][RecognitionListener.onReadyForSpeech] and end of that cycle. */
    @Volatile
    var isRecognizerInSession: Boolean = false
        private set

    @Volatile
    var lastRecognizerError: String? = null
        private set

    private val startListeningRunnable = Runnable {
        if (destroyed || !speechRecognitionActive) return@Runnable
        beginListeningCycle()
    }

    inner class LocalBinder : Binder() {
        fun getService(): VoiceRemoteService = this@VoiceRemoteService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        speechRecognitionAvailable = SpeechRecognizer.isRecognitionAvailable(this)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "VoiceShell:VoiceRemote"
        ).apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        destroyed = false
        wakeLock?.acquire(10 * 60 * 60 * 1000L)
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
        connectWebSocket()
        mainHandler.post { startContinuousRecognition() }
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        speechRecognitionActive = false
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.removeCallbacks(startListeningRunnable)
        mainHandler.post {
            runCatching { speechRecognizer?.stopListening() }
            runCatching { speechRecognizer?.destroy() }
            speechRecognizer = null
            isRecognizerInSession = false
        }
        connectionListener = null
        webSocket?.close(1000, "stop")
        webSocket = null
        okHttp.dispatcher.cancelAll()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        connecting.set(false)
        super.onDestroy()
    }

    fun sendPayload(text: String) {
        logVoiceEvent("WORD_SENT", text)
        webSocket?.send(text)
    }

    /** Recreate the engine and resume the listen loop (e.g. after ERROR_CLIENT or stuck state). */
    fun restartSpeechRecognition() {
        mainHandler.post {
            logVoiceEvent("RECOGNITION_RESTART", "explicit")
            if (destroyed) return@post
            runCatching { speechRecognizer?.stopListening() }
            runCatching { speechRecognizer?.destroy() }
            speechRecognizer = null
            isRecognizerInSession = false
            sessionEmittedWords = emptyList()
            lastRecognizerError = null
            notifyDebugChanged()
            if (speechRecognitionActive) {
                ensureRecognizerCreated()
                scheduleStartListening(120)
            }
        }
    }

    private fun startContinuousRecognition() {
        if (!speechRecognitionAvailable) {
            lastRecognizerError = "Speech recognition not available on this device"
            notifyDebugChanged()
            return
        }
        speechRecognitionActive = true
        ensureRecognizerCreated()
        scheduleStartListening(0)
    }

    private fun ensureRecognizerCreated() {
        if (speechRecognizer != null) return
        val sr = SpeechRecognizer.createSpeechRecognizer(this)
        sr.setRecognitionListener(recognitionListener)
        speechRecognizer = sr
    }

    private fun scheduleStartListening(delayMs: Long) {
        mainHandler.removeCallbacks(startListeningRunnable)
        if (delayMs <= 0L) {
            mainHandler.post(startListeningRunnable)
        } else {
            mainHandler.postDelayed(startListeningRunnable, delayMs)
        }
    }

    private fun beginListeningCycle() {
        if (destroyed || !speechRecognitionActive) return
        if (!speechRecognitionAvailable) return
        ensureRecognizerCreated()
        val sr = speechRecognizer ?: return
        sessionEmittedWords = emptyList()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    2000L
                )
            }
        }
        runCatching {
            sr.startListening(intent)
        }.onFailure {
            lastRecognizerError = it.message ?: "startListening failed"
            notifyDebugChanged()
            logVoiceEvent("RECOGNITION_RESTART", "delayMs=500 after startListening failed")
            scheduleStartListening(500)
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            logVoiceEvent("RECOGNITION_START", "")
            isRecognizerInSession = true
            lastRecognizerError = null
            notifyDebugChanged()
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            isRecognizerInSession = false
            lastRecognizerError = speechErrorToString(error)
            notifyDebugChanged()
            val delay = when (error) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 600L
                SpeechRecognizer.ERROR_CLIENT -> {
                    mainHandler.post {
                        runCatching { speechRecognizer?.destroy() }
                        speechRecognizer = null
                        ensureRecognizerCreated()
                    }
                    250L
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    speechRecognitionActive = false
                    0L
                }
                else -> 120L
            }
            if (speechRecognitionActive && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                logVoiceEvent("RECOGNITION_RESTART", "delayMs=$delay after error=$error")
                scheduleStartListening(delay)
            }
        }

        override fun onResults(results: Bundle?) {
            isRecognizerInSession = false
            val text = bestHypothesis(results)
            logVoiceEvent("FINAL_RESULT", text)
            emitWordsFromHypothesis(text)
            sessionEmittedWords = emptyList()
            notifyDebugChanged()
            if (speechRecognitionActive) {
                logVoiceEvent("RECOGNITION_RESTART", "delayMs=80 after final result")
                scheduleStartListening(80)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = bestHypothesis(partialResults)
            logVoiceEvent("PARTIAL_RESULT", text)
            emitWordsFromHypothesis(text)
            notifyDebugChanged()
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun emitWordsFromHypothesis(hypothesis: String) {
        if (destroyed) return
        val raw = hypothesis.trim()
        val words = if (raw.isEmpty()) {
            emptyList()
        } else {
            raw.split(wordSplitRegex).filter { it.isNotEmpty() }
        }
        when {
            words.size > sessionEmittedWords.size -> {
                sendPayload(words.last())
            }
            words.size == sessionEmittedWords.size &&
                words.isNotEmpty() &&
                words.last() != sessionEmittedWords.last() -> {
                sendPayload(DELTA_PREFIX + words.last())
            }
        }
        sessionEmittedWords = words
    }

    private fun bestHypothesis(bundle: Bundle?): String {
        val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?: return ""
        if (matches.isEmpty()) return ""
        val scores = bundle.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
        if (scores != null && scores.size == matches.size) {
            val idx = scores.indices.maxByOrNull { scores[it] } ?: 0
            return matches[idx]
        }
        return matches[0]
    }

    private fun speechErrorToString(error: Int): String {
        val name = when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
            SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
            SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
            else -> "UNKNOWN"
        }
        return "$name ($error)"
    }

    private fun notifyDebugChanged() {
        mainHandler.post {
            connectionListener?.onVoiceDebugMayHaveChanged()
        }
    }

    private fun logVoiceEvent(event: String, data: String) {
        Log.d(LOG_TAG, "[${System.currentTimeMillis()}] $event: $data")
    }

    private fun connectWebSocket() {
        if (destroyed || webSocket != null) return
        if (!connecting.compareAndSet(false, true)) return

        val request = Request.Builder().url(DEFAULT_WS_URL).build()
        okHttp.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    connecting.set(false)
                    if (destroyed) {
                        webSocket.close(1000, null)
                        return
                    }
                    this@VoiceRemoteService.webSocket = webSocket
                    notifyConnection(true, null)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (this@VoiceRemoteService.webSocket === webSocket) {
                        this@VoiceRemoteService.webSocket = null
                    }
                    connecting.set(false)
                    notifyConnection(false, reason)
                    scheduleReconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (this@VoiceRemoteService.webSocket === webSocket) {
                        this@VoiceRemoteService.webSocket = null
                    }
                    connecting.set(false)
                    notifyConnection(false, t.message)
                    scheduleReconnect()
                }
            }
        )
    }

    private fun scheduleReconnect() {
        if (destroyed) return
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, 2000)
    }

    private fun notifyConnection(connected: Boolean, message: String?) {
        mainHandler.post {
            connectionListener?.onConnectionChanged(connected, message)
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val LOG_TAG = "VoiceRemoteService"
        const val DEFAULT_WS_URL = "ws://100.104.249.65:8080"
        private const val CHANNEL_ID = "voiceshell_voice"
        private const val NOTIFICATION_ID = 42
        private const val DELTA_PREFIX = "\u0394"
    }
}
