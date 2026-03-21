package io.github.iamweasel89.voiceshell.ime

import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.view.inputmethod.InputConnection
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
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
                    mainHandler.post {
                        Toast.makeText(this@VoiceShellImeService, "RX: '$text'", Toast.LENGTH_SHORT).show()
                    }

                    if (shouldIgnoreMessage(text.trim())) {
                        mainHandler.post {
                            Toast.makeText(this@VoiceShellImeService, "IGNORED: $text", Toast.LENGTH_SHORT).show()
                        }
                        return
                    }

                    mainHandler.post {
                        val ic = currentInputConnection ?: return@post

                        val word = text.trim()
                        if (word.isEmpty()) return@post

                        Toast.makeText(this@VoiceShellImeService, "INSERT: '$word'", Toast.LENGTH_SHORT).show()
                        ic.commitText("$word ", 1)

                        val committedFullLength = word.length + 1
                        val readBack = ic.getTextBeforeCursor(committedFullLength, 0) ?: return@post
                        if (readBack.length < committedFullLength) {
                            committedWordLengths.addLast(committedFullLength)
                            return@post
                        }
                        val insertedSegment = readBack.subSequence(
                            readBack.length - committedFullLength,
                            readBack.length
                        ).toString()
                        if (insertedSegment != "$word ") {
                            committedWordLengths.addLast(committedFullLength)
                            return@post
                        }

                        when (insertedSegment.trim().lowercase()) {
                            CMD_DELETE_LAST_WORD -> {
                                Toast.makeText(
                                    this@VoiceShellImeService,
                                    "CMD: delete last word",
                                    Toast.LENGTH_SHORT
                                ).show()
                                val deleteCmdLen = insertedSegment.length
                                ic.deleteSurroundingText(deleteCmdLen, 0)
                                deletePreviousWord(ic)
                            }
                            CMD_CLEAR_ALL_1, CMD_CLEAR_ALL_2 -> {
                                Toast.makeText(
                                    this@VoiceShellImeService,
                                    "CMD: clear all",
                                    Toast.LENGTH_SHORT
                                ).show()
                                val beforeLen =
                                    ic.getTextBeforeCursor(FIELD_TEXT_MAX_CHARS, 0)?.length ?: 0
                                val afterLen =
                                    ic.getTextAfterCursor(FIELD_TEXT_MAX_CHARS, 0)?.length ?: 0
                                ic.deleteSurroundingText(beforeLen, afterLen)
                                committedWordLengths.clear()
                            }
                            else -> {
                                committedWordLengths.addLast(committedFullLength)
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

    /**
     * Deletes one word immediately before the cursor: skips trailing whitespace, then removes
     * the contiguous non-whitespace run (or word) before that.
     */
    private fun deletePreviousWord(ic: InputConnection) {
        val buf = ic.getTextBeforeCursor(4096, 0)?.toString() ?: return
        if (buf.isEmpty()) return
        var i = buf.length - 1
        while (i >= 0 && buf[i].isWhitespace()) i--
        if (i < 0) return
        val wordEndExclusive = i + 1
        while (i >= 0 && !buf[i].isWhitespace()) i--
        val wordStart = i + 1
        val len = wordEndExclusive - wordStart
        if (len > 0) ic.deleteSurroundingText(len, 0)
    }

    private fun circleDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    companion object {
        private const val WS_URL = "ws://100.107.205.27:8080"
        private const val CMD_DELETE_LAST_WORD = "\u0443\u0431\u0435\u0440\u0438\u0020\u0441\u043b\u043e\u0432\u043e"
        private const val CMD_CLEAR_ALL_1 = "\u0443\u0431\u0435\u0440\u0438\u0020\u0432\u0441\u0451"
        private const val CMD_CLEAR_ALL_2 = "\u0443\u0431\u0435\u0440\u0438\u0020\u043f\u043e\u043b\u043d\u043e\u0441\u0442\u044c\u044e"
        /** Upper bound for [InputConnection.getTextBeforeCursor] / [getTextAfterCursor] when clearing the field. */
        private const val FIELD_TEXT_MAX_CHARS = 512_000
    }
}
