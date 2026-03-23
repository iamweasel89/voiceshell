package io.github.iamweasel89.voiceshell.ime

import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.view.inputmethod.InputConnection
import android.os.Handler
import android.os.Looper
import android.util.Log
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

    private var editMode = false

    /**
     * Set after [deleteLastWordWithSpace] from single-word delete commands (\u0443\u0431\u0440\u0430\u0442\u044c, etc.).
     * Next utterance \u0432\u0441\u0451 / \u043f\u043e\u043b\u043d\u043e\u0441\u0442\u044c\u044e then runs [clearAllText]; cleared after clear, any other message, exit edit, or timeout.
     */
    private var lastEditVoiceActionWasDeleteWord = false

    private val clearLastDeleteFollowUpFlagRunnable = Runnable {
        lastEditVoiceActionWasDeleteWord = false
    }

    /** Lengths of each committed segment (word plus trailing space from [commitText]) in order. */
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
        mainHandler.removeCallbacks(clearLastDeleteFollowUpFlagRunnable)
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
                    Log.d(
                        "VoiceShellIME",
                        "\u0057\u0065\u0062\u0053\u006f\u0063\u006b\u0065\u0074\u0020\u006f\u0070\u0065\u006e\u0020\u0068\u0074\u0074\u0070\u003d" + response.code
                    )
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
                    Log.d(
                        "VoiceShellIME",
                        "\u0057\u0065\u0062\u0053\u006f\u0063\u006b\u0065\u0074\u0020\u0063\u006c\u006f\u0073\u0065\u0064\u0020\u0063\u006f\u0064\u0065\u003d" + code + "\u0020\u0072\u0065\u0061\u0073\u006f\u006e\u003d" + reason
                    )
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
                    mainHandler.post { Toast.makeText(this@VoiceShellImeService, "\u0052\u0058\u003a\u0020$text", Toast.LENGTH_SHORT).show() }

                    mainHandler.post {
                        val ic = currentInputConnection ?: return@post
                        var payloadWord = text
                        var isDeltaWord = false
                        val trimmedForJson = text.trim()
                        if (trimmedForJson.startsWith("{")) {
                            try {
                                val obj = JSONObject(trimmedForJson)
                                if (obj.optString("type") == "delta") {
                                    val word = obj.optString("word")
                                    if (word.isNotEmpty()) {
                                        payloadWord = word
                                        isDeltaWord = true
                                    }
                                } else {
                                    // Valid JSON but not voice delta (e.g. clipboard) — do not insert as text.
                                    return@post
                                }
                            } catch (_: JSONException) {
                                // Not JSON or malformed; treat as plain text below.
                            }
                        }
                        val trimmedInner = payloadWord.trim()
                        val normalized = trimmedInner.lowercase()
                        logVoiceShellMessage(
                            text,
                            normalized,
                            if (isDeltaWord) "\u0064\u0065\u006c\u0074\u0061" else describeNonIgnoredCase(trimmedInner, normalized)
                        )

                        when (normalized) {
                            CMD_ENTER_EDIT -> {
                                editMode = true
                                clearDeleteFollowUpFlag()
                                updateStatusDotColor()
                                return@post
                            }
                            CMD_EXIT_EDIT -> {
                                editMode = false
                                clearDeleteFollowUpFlag()
                                updateStatusDotColor()
                                return@post
                            }
                            else -> {
                                if (editMode) {
                                    if (lastEditVoiceActionWasDeleteWord) {
                                        when (normalized) {
                                            CMD_FOLLOW_CLEAR_AFTER_DELETE_1,
                                            CMD_FOLLOW_CLEAR_AFTER_DELETE_2 -> {
                                                toastClearAllDebugIfScopeKeywords(text, normalized)
                                                clearAllText(ic)
                                                clearDeleteFollowUpFlag()
                                                return@post
                                            }
                                            else -> {
                                                clearDeleteFollowUpFlag()
                                            }
                                        }
                                    }
                                    if (isKeywordClearAll(normalized)) {
                                        toastClearAllDebugIfScopeKeywords(text, normalized)
                                        clearAllText(ic)
                                        clearDeleteFollowUpFlag()
                                        return@post
                                    }
                                    when (normalized) {
                                        CMD_ERASE, CMD_REMOVE, CMD_CLEAR_IMP, CMD_REMOVE_IMP -> {
                                            deleteLastWordWithSpace(ic)
                                            scheduleDeleteFollowUpWindow()
                                            return@post
                                        }
                                        CMD_BACK -> {
                                            deleteLastWordWithSpace(ic)
                                            return@post
                                        }
                                        CMD_CLEAR_ALL_1, CMD_CLEAR_ALL_2, CMD_CLEAR_ALL_3, CMD_CLEAR_ALL_4 -> {
                                            toastClearAllDebugIfScopeKeywords(text, normalized)
                                            clearAllText(ic)
                                            clearDeleteFollowUpFlag()
                                            return@post
                                        }
                                        else -> {
                                            if (trimmedInner.isNotEmpty()) {
                                                if (isDeltaWord) {
                                                    applyDeltaWord(ic, trimmedInner)
                                                } else {
                                                    ic.commitText("$trimmedInner ", 1)
                                                    committedWordLengths.addLast(trimmedInner.length + 1)
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    if (trimmedInner.isNotEmpty()) {
                                        if (isDeltaWord) {
                                            applyDeltaWord(ic, trimmedInner)
                                        } else {
                                            ic.commitText("$trimmedInner ", 1)
                                            committedWordLengths.addLast(trimmedInner.length + 1)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        )
    }

    private fun logVoiceShellMessage(raw: String, normalized: String, caseMatch: String) {
        Log.d(
            "VoiceShellIME",
            "\u0072\u0061\u0077\u003d\u0022$raw\u0022\u0020\u006e\u006f\u0072\u006d\u003d\u0022$normalized\u0022\u0020\u0063\u0061\u0073\u0065\u003d$caseMatch"
        )
    }

    private fun describeNonIgnoredCase(trimmed: String, normalized: String): String {
        return when (normalized) {
            CMD_ENTER_EDIT -> "\u0065\u006e\u0074\u0065\u0072\u005f\u0065\u0064\u0069\u0074"
            CMD_EXIT_EDIT -> "\u0065\u0078\u0069\u0074\u005f\u0065\u0064\u0069\u0074"
            else -> when {
                editMode && lastEditVoiceActionWasDeleteWord &&
                    (normalized == CMD_FOLLOW_CLEAR_AFTER_DELETE_1 ||
                        normalized == CMD_FOLLOW_CLEAR_AFTER_DELETE_2) ->
                    "\u0063\u006c\u0065\u0061\u0072\u005f\u0061\u006c\u006c\u005f\u0066\u006f\u006c\u006c\u006f\u0077\u005f\u0064\u0065\u006c\u0065\u0074\u0065"
                editMode && isKeywordClearAll(normalized) ->
                    "\u0063\u006c\u0065\u0061\u0072\u005f\u0061\u006c\u006c\u005f\u006b\u0065\u0077\u006f\u0072\u0064"
                editMode -> when (normalized) {
                    CMD_ERASE, CMD_REMOVE, CMD_CLEAR_IMP, CMD_REMOVE_IMP ->
                        "\u0064\u0065\u006c\u0065\u0074\u0065\u005f\u006c\u0061\u0073\u0074\u005f\u0077\u006f\u0072\u0064"
                    CMD_BACK ->
                        "\u0064\u0065\u006c\u0065\u0074\u0065\u005f\u006c\u0061\u0073\u0074\u005f\u0077\u006f\u0072\u0064"
                    CMD_CLEAR_ALL_1, CMD_CLEAR_ALL_2, CMD_CLEAR_ALL_3, CMD_CLEAR_ALL_4 ->
                        "\u0063\u006c\u0065\u0061\u0072\u005f\u0061\u006c\u006c\u005f\u0063\u006d\u0064"
                    else ->
                        if (trimmed.isNotEmpty()) "\u0063\u006f\u006d\u006d\u0069\u0074\u005f\u0065\u0064\u0069\u0074" else "\u006e\u006f\u006f\u0070"
                }
                else ->
                    if (trimmed.isNotEmpty()) "\u0063\u006f\u006d\u006d\u0069\u0074\u005f\u0064\u0069\u0063\u0074" else "\u006e\u006f\u006f\u0070"
            }
        }
    }

    private fun scheduleReconnect() {
        if (destroyed.get()) return
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, 2000)
    }

    private fun scheduleDeleteFollowUpWindow() {
        lastEditVoiceActionWasDeleteWord = true
        mainHandler.removeCallbacks(clearLastDeleteFollowUpFlagRunnable)
        mainHandler.postDelayed(
            clearLastDeleteFollowUpFlagRunnable,
            DELETE_FOLLOW_UP_TIMEOUT_MS
        )
    }

    private fun clearDeleteFollowUpFlag() {
        lastEditVoiceActionWasDeleteWord = false
        mainHandler.removeCallbacks(clearLastDeleteFollowUpFlagRunnable)
    }

    private fun postConnectionState(connected: Boolean) {
        mainHandler.post { applyConnectionUi(connected) }
    }

    private fun applyConnectionUi(connected: Boolean) {
        if (!connected) {
            val dotColor = ContextCompat.getColor(this, R.color.status_dot_disconnected)
            statusDot?.background = circleDrawable(dotColor)
        } else {
            updateStatusDotColor()
        }
    }

    private fun updateStatusDotColor() {
        val dotColor = ContextCompat.getColor(
            this,
            if (editMode) R.color.status_dot_edit else R.color.status_dot_connected
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
     * Replace the last committed word (from [committedWordLengths]) with [word] plus trailing space.
     * Does not insert \u0394 into the field.
     */
    private fun applyDeltaWord(ic: InputConnection, word: String) {
        clearDeleteFollowUpFlag()
        when {
            committedWordLengths.isNotEmpty() -> {
                val n = committedWordLengths.removeLast()
                if (n > 0) {
                    ic.deleteSurroundingText(n, 0)
                }
            }
            else -> deleteLastWordWithSpace(ic)
        }
        ic.commitText("$word ", 1)
        committedWordLengths.addLast(word.length + 1)
    }

    private fun deleteLastWordWithSpace(ic: InputConnection) {
        val beforeCursor = ic.getTextBeforeCursor(100, 0) ?: return

        var end = beforeCursor.length
        while (end > 0 && beforeCursor[end - 1].isWhitespace()) {
            end--
        }
        var start = end
        while (start > 0 && !beforeCursor[start - 1].isWhitespace()) {
            start--
        }

        val wordLength = end - start
        if (wordLength > 0) {
            if (start == 0) {
                // Last word only: no chars before it; delete whole buffer before cursor (word + trailing ws).
                ic.deleteSurroundingText(beforeCursor.length, 0)
            } else {
                val spaceBefore = if (beforeCursor[start - 1].isWhitespace()) 1 else 0
                ic.deleteSurroundingText(wordLength + spaceBefore, 0)
            }
        }
    }

    private fun toastClearAllDebugIfScopeKeywords(raw: String, normalized: String) {
        val hasScope =
            normalized.contains("\u0432\u0441\u0451") ||
                normalized.contains("\u043f\u043e\u043b\u043d\u043e\u0441\u0442\u044c\u044e")
        if (!hasScope) return
        Toast.makeText(
            this,
            "raw: $raw\nnormalized: $normalized",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun clearAllText(ic: InputConnection) {
        val before = ic.getTextBeforeCursor(Int.MAX_VALUE, 0) ?: ""
        val after = ic.getTextAfterCursor(Int.MAX_VALUE, 0) ?: ""
        ic.setSelection(0, before.length + after.length)
        ic.commitText("", 1)
        committedWordLengths.clear()
    }

    /**
     * Phrases that name full clear (e.g. scope + delete verb) are not exact matches for
     * [CMD_ERASE]/[CMD_CLEAR_ALL_*]; handle them before commit or single-word delete.
     */
    private fun isKeywordClearAll(normalized: String): Boolean {
        val hasScope =
            normalized.contains("\u0432\u0441\u0451") ||
                normalized.contains("\u043f\u043e\u043b\u043d\u043e\u0441\u0442\u044c\u044e")
        if (!hasScope) return false
        return normalized.contains("\u043e\u0447\u0438\u0441\u0442\u0438") ||
            normalized.contains("\u0443\u0431\u0440\u0430") ||
            normalized.contains("\u0441\u0442\u0435\u0440\u0435") ||
            normalized.contains("\u0443\u0431\u0435\u0440\u0438")
    }

    private fun circleDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    companion object {
        private const val DELETE_FOLLOW_UP_TIMEOUT_MS = 5000L

        private const val WS_URL = "ws://100.104.249.65:8080"
        // Edit mode toggle commands
        private const val CMD_ENTER_EDIT =
            "\u0440\u0435\u0434\u0430\u043a\u0446\u0438\u044f" // "редакция"
        private const val CMD_EXIT_EDIT =
            "\u0433\u043e\u0442\u043e\u0432\u043e" // "готово"
        // Edit mode commands (single word)
        private const val CMD_ERASE =
            "\u0441\u0442\u0435\u0440\u0435\u0442\u044c" // "стереть"
        private const val CMD_BACK =
            "\u043d\u0430\u0437\u0430\u0434" // "назад"
        private const val CMD_REMOVE =
            "\u0443\u0431\u0440\u0430\u0442\u044c" // "убрать"
        private const val CMD_CLEAR_IMP =
            "\u043e\u0447\u0438\u0441\u0442\u0438" // "очисти"
        private const val CMD_REMOVE_IMP =
            "\u0443\u0431\u0435\u0440\u0438" // "убери"
        /** After delete-word, next utterance clears all instead of inserting. */
        private const val CMD_FOLLOW_CLEAR_AFTER_DELETE_1 =
            "\u0432\u0441\u0451" // "всё"
        private const val CMD_FOLLOW_CLEAR_AFTER_DELETE_2 =
            "\u043f\u043e\u043b\u043d\u043e\u0441\u0442\u044c\u044e" // "полностью"
        private const val CMD_CLEAR_ALL_1 =
            "\u043e\u0447\u0438\u0441\u0442\u0438\u0442\u044c \u0432\u0441\u0451"
        private const val CMD_CLEAR_ALL_2 =
            "\u0443\u0431\u0440\u0430\u0442\u044c \u0432\u0441\u0451"
        private const val CMD_CLEAR_ALL_3 =
            "\u0441\u0442\u0435\u0440\u0435\u0442\u044c \u0432\u0441\u0451"
        private const val CMD_CLEAR_ALL_4 =
            "\u0443\u0431\u0435\u0440\u0438 \u043f\u043e\u043b\u043d\u043e\u0441\u0442\u044c\u044e"
    }
}
