package com.voiceshell.ime

import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * Connects to a fixed WebSocket and types each incoming message into the focused field.
 */
class VoiceShellImeService : InputMethodService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val http = OkHttpClient()
    private var socket: WebSocket? = null
    private var statusView: TextView? = null

    private val reconnect = Runnable {
        openSocket()
    }

    override fun onCreateInputView(): View {
        val root = LayoutInflater.from(this).inflate(R.layout.ime_panel, null)
        statusView = root.findViewById(R.id.status)
        openSocket()
        return root
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(reconnect)
        socket?.cancel()
        socket = null
        statusView = null
        super.onDestroy()
    }

    private fun scheduleReconnect(delayMs: Long) {
        mainHandler.removeCallbacks(reconnect)
        mainHandler.postDelayed(reconnect, delayMs)
    }

    private fun openSocket() {
        socket?.cancel()
        setStatus(false)
        val request = Request.Builder().url(WS_URL).build()
        socket = http.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    mainHandler.post {
                        if (socket !== webSocket) return@post
                        setStatus(true)
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    postCommitWordFromWebSocket(webSocket, text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    postCommitWordFromWebSocket(webSocket, bytes.utf8())
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    mainHandler.post {
                        if (socket !== webSocket) return@post
                        socket = null
                        setStatus(false)
                        scheduleReconnect(2000L)
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    mainHandler.post {
                        if (socket !== webSocket) return@post
                        socket = null
                        setStatus(false)
                        scheduleReconnect(2000L)
                    }
                }
            },
        )
    }

    private fun postCommitWordFromWebSocket(webSocket: WebSocket, raw: String) {
        Handler(Looper.getMainLooper()).post {
            if (socket !== webSocket) return@post
            val word = raw.trim()
            if (word.isEmpty()) return@post
            val ic = currentInputConnection ?: return@post
            ic.commitText(word + " ", 1)
        }
    }

    private fun setStatus(online: Boolean) {
        val tv = statusView ?: return
        tv.setTextColor(if (online) Color.parseColor("#4fc3f7") else Color.parseColor("#666666"))
    }

    companion object {
        private const val WS_URL = "ws://100.107.205.27:8080"
    }
}
