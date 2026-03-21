package io.github.iamweasel89.voiceshell.ime

import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONException
import org.json.JSONObject
import kotlin.collections.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class VoiceShellImeService : InputMethodService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val okHttp = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var webSocket: WebSocket? = null
    private val connecting = AtomicBoolean(false)
    private val destroyed = AtomicBoolean(false)

    private var statusDot: View? = null

    /**
     * Lengths of each committed segment (word plus trailing space from [commitText]) in order, so
     * [deleteSurroundingText] can remove the last insertion(s) on "убери слово".
     */
    private val committedWordLengths = ArrayDeque<Int>()

    private val reconnectRunnable = Runnable {
        if (!destroyed.get() && webSocket == null) connectWebSocket()
    }

    override fun onCreate() {
        super.onCreate()
        destroyed.set(false)
        connectWebSocket()
    }

    override fun onDestroy() {
        destroyed.set(true)
        mainHandler.removeCallbacks(reconnectRunnable)
        webSocket?.close(1000, "destroy")
        webSocket = null
        okHttp.dispatcher.cancelAll()
        connecting.set(false)
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val root = layoutInflater.inflate(R.layout.ime_status_bar, null)
        statusDot = root.findViewById(R.id.status_dot)
        applyConnectionUi(webSocket != null)
        return root
    }

    private fun connectWebSocket() {
        if (destroyed.get() || webSocket != null) return
        if (!connecting.compareAndSet(false, true)) return

        val request = Request.Builder().url(WS_URL).build()
        okHttp.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    connecting.set(false)
                    if (destroyed.get()) {
                        webSocket.close(1000, null)
                        return
                    }
                    this@VoiceShellImeService.webSocket = webSocket
                    postConnectionState(true)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (this@VoiceShellImeService.webSocket === webSocket) {
                        this@VoiceShellImeService.webSocket = null
                    }
                    connecting.set(false)
                    postConnectionState(false)
                    scheduleReconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (this@VoiceShellImeService.webSocket === webSocket) {
                        this@VoiceShellImeService.webSocket = null
                    }
                    connecting.set(false)
                    postConnectionState(false)
                    scheduleReconnect()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val trimmed = text.trim()
                    if (shouldIgnoreMessage(trimmed)) return
                    mainHandler.post {
                        val ic = currentInputConnection ?: return@post
                        when (trimmed) {
                            CMD_DELETE_LAST_WORD -> {
                                val len = committedWordLengths.removeLastOrNull() ?: return@post
                                ic.deleteSurroundingText(len, 0)
                            }
                            CMD_CLEAR_ALL -> {
                                committedWordLengths.clear()
                                ic.performContextMenuAction(android.R.id.selectAll)
                                ic.commitText("", 1)
                            }
                            else -> {
                                val word = trimmed
                                if (word.isEmpty()) return@post
                                ic.commitText("$word ", 1)
                                committedWordLengths.addLast(word.length + 1)
                            }
                        }
                    }
                }
            }
        )
    }

    private fun scheduleReconnect() {
        if (destroyed.get()) return
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, 2000)
    }

    private fun postConnectionState(connected: Boolean) {
        mainHandler.post { applyConnectionUi(connected) }
    }

    private fun applyConnectionUi(connected: Boolean) {
        val dotColor = ContextCompat.getColor(
            this,
            if (connected) R.color.status_dot_connected else R.color.status_dot_disconnected
        )
        statusDot?.background = circleDrawable(dotColor)
    }

    /** `server.js` broadcasts JSON (e.g. `{"type":"clipboard",...}`); voice words are plain text. */
    private fun shouldIgnoreMessage(trimmed: String): Boolean {
        if (!trimmed.startsWith("{")) return false
        return try {
            JSONObject(trimmed)
            true
        } catch (_: JSONException) {
            false
        }
    }

    private fun circleDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    companion object {
        private const val WS_URL = "ws://100.107.205.27:8080"
        private const val CMD_DELETE_LAST_WORD = "убери слово"
        private const val CMD_CLEAR_ALL = "убери полностью"
    }
}
