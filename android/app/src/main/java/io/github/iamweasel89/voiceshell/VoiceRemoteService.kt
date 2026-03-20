package io.github.iamweasel89.voiceshell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicBoolean

class VoiceRemoteService : Service() {

    interface ConnectionListener {
        fun onConnectionChanged(connected: Boolean, message: String?)

        fun onVoiceOverlayDebugMayHaveChanged() {}
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

    private var overlayRoot: FrameLayout? = null
    private var overlayEdit: EditText? = null
    private var windowManager: WindowManager? = null
    private val voiceTextWatcher = VoicePayloadTextWatcher()

    @Volatile
    var overlayAttached: Boolean = false
        private set

    @Volatile
    var overlayEditHasFocus: Boolean = false
        private set

    @Volatile
    var overlayImeVisible: Boolean = false
        private set

    inner class LocalBinder : Binder() {
        fun getService(): VoiceRemoteService = this@VoiceRemoteService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "VoiceShell:VoiceRemote"
        ).apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        destroyed = false
        wakeLock?.acquire(10 * 60 * 60 * 1000L)
        startForeground(NOTIFICATION_ID, buildNotification())
        connectWebSocket()
        mainHandler.post { attachVoiceOverlayIfAllowed() }
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.post { detachVoiceOverlay() }
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
        webSocket?.send(text)
    }

    /** Request focus on the service overlay [EditText] and show the IME (e.g. Gboard). */
    fun requestOverlayFocusAndShowIme() {
        mainHandler.post {
            val edit = overlayEdit ?: return@post
            edit.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
            notifyOverlayDebugMayHaveChanged()
        }
    }

    private fun attachVoiceOverlayIfAllowed() {
        if (destroyed) return
        if (!Settings.canDrawOverlays(this)) {
            overlayAttached = false
            notifyOverlayDebugMayHaveChanged()
            return
        }
        if (overlayRoot != null) return

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val root = LayoutInflater.from(this).inflate(
            R.layout.overlay_voice_capture,
            null,
            false
        ) as FrameLayout
        val edit = root.findViewById<EditText>(R.id.overlay_voice_input)
        voiceTextWatcher.reset()

        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            title = getString(R.string.notification_title)
        }

        edit.addTextChangedListener(voiceTextWatcher)
        edit.setOnFocusChangeListener { _, hasFocus ->
            overlayEditHasFocus = hasFocus
            notifyOverlayDebugMayHaveChanged()
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            overlayImeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            notifyOverlayDebugMayHaveChanged()
            insets
        }

        try {
            wm.addView(root, params)
        } catch (_: WindowManager.BadTokenException) {
            overlayRoot = null
            overlayEdit = null
            overlayAttached = false
            notifyOverlayDebugMayHaveChanged()
            return
        }

        overlayRoot = root
        overlayEdit = edit
        overlayAttached = true
        edit.post {
            edit.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
            notifyOverlayDebugMayHaveChanged()
        }
        notifyOverlayDebugMayHaveChanged()
    }

    private fun detachVoiceOverlay() {
        val root = overlayRoot ?: return
        val edit = overlayEdit
        edit?.removeTextChangedListener(voiceTextWatcher)
        edit?.setOnFocusChangeListener(null)
        try {
            windowManager?.removeView(root)
        } catch (_: IllegalArgumentException) {
        }
        overlayRoot = null
        overlayEdit = null
        overlayAttached = false
        overlayEditHasFocus = false
        overlayImeVisible = false
        voiceTextWatcher.reset()
        notifyOverlayDebugMayHaveChanged()
    }

    private fun notifyOverlayDebugMayHaveChanged() {
        mainHandler.post {
            connectionListener?.onVoiceOverlayDebugMayHaveChanged()
        }
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

    private inner class VoicePayloadTextWatcher : TextWatcher {
        private var lastWords: List<String> = emptyList()
        private val wordSplitRegex = Regex("\\s+")

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

        override fun afterTextChanged(s: Editable?) {
            if (destroyed) return
            val raw = s?.toString() ?: ""
            val words = raw.trim().split(wordSplitRegex).filter { it.isNotEmpty() }

            when {
                words.size > lastWords.size -> {
                    sendPayload(words.last())
                }
                words.size == lastWords.size && words.isNotEmpty() && words.last() != lastWords.last() -> {
                    sendPayload(DELTA_PREFIX + words.last())
                }
            }
            lastWords = words
        }

        fun reset() {
            lastWords = emptyList()
        }
    }

    companion object {
        const val DEFAULT_WS_URL = "ws://100.107.205.27:8080"
        private const val CHANNEL_ID = "voiceshell_voice"
        private const val NOTIFICATION_ID = 42
        private const val DELTA_PREFIX = "\u0394"
    }
}
