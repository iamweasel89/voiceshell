package io.github.iamweasel89.voiceshell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
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
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        mainHandler.removeCallbacks(reconnectRunnable)
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
        const val DEFAULT_WS_URL = "ws://100.107.205.27:8080"
        private const val CHANNEL_ID = "voiceshell_voice"
        private const val NOTIFICATION_ID = 42
    }
}
