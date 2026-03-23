package io.github.iamweasel89.voiceshell

import android.content.ComponentName
import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import io.github.iamweasel89.voiceshell.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), VoiceRemoteService.ConnectionListener {

    private lateinit var binding: ActivityMainBinding
    private var voiceService: VoiceRemoteService? = null
    private var serviceRunning = false
    private var bound = false

    private var sessionEmittedWords: List<String> = emptyList()
    private val wordSplitRegex = Regex("\\s+")

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) {
            startRemote()
        } else {
            updateUiForStopped()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as VoiceRemoteService.LocalBinder).getService()
            voiceService = s
            s.connectionListener = this@MainActivity
            updateDebugPanel()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            voiceService?.connectionListener = null
            voiceService = null
            bound = false
        }
    }

    private val keyboardWordWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

        override fun afterTextChanged(s: Editable?) {
            if (!serviceRunning) return
            emitWordsFromBuffer(s?.toString().orEmpty())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serviceRunning = savedInstanceState?.getBoolean(STATE_SERVICE) ?: false

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.hiddenInput.addTextChangedListener(keyboardWordWatcher)

        binding.toggle.setOnClickListener {
            if (serviceRunning) stopRemote() else requestStart()
        }

        binding.debugClearInput.setOnClickListener {
            sessionEmittedWords = emptyList()
            binding.hiddenInput.text?.clear()
            binding.hiddenInput.post { binding.hiddenInput.requestFocus() }
            binding.root.postDelayed({ updateDebugPanel() }, 50)
        }

        if (serviceRunning) {
            binding.toggle.text = getString(R.string.stop)
        }

        updateDebugPanel()
    }

    override fun onResume() {
        super.onResume()
        if (serviceRunning) {
            binding.hiddenInput.post { binding.hiddenInput.requestFocus() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SERVICE, serviceRunning)
    }

    override fun onStart() {
        super.onStart()
        if (serviceRunning && !bound) {
            bindService(
                Intent(this, VoiceRemoteService::class.java),
                connection,
                Context.BIND_AUTO_CREATE
            )
            bound = true
        }
    }

    override fun onStop() {
        if (bound) {
            voiceService?.connectionListener = null
            try {
                unbindService(connection)
            } catch (_: IllegalArgumentException) {
            }
            voiceService = null
            bound = false
        }
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && serviceRunning) {
            binding.hiddenInput.requestFocus()
            updateDebugPanel()
        }
    }

    override fun onConnectionChanged(connected: Boolean, message: String?) {
        if (!serviceRunning) return
        val dotColor = if (connected) R.color.status_dot_connected else R.color.status_dot_idle
        binding.statusDot.setTextColor(ContextCompat.getColor(this, dotColor))
        binding.statusLabel.text = when {
            connected -> getString(R.string.status_connected)
            message != null -> getString(R.string.status_disconnected) + " ($message)"
            else -> getString(R.string.status_disconnected)
        }
    }

    private fun requestStart() {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isNotEmpty()) {
            permissionLauncher.launch(need.toTypedArray())
        } else {
            startRemote()
        }
    }

    private fun startRemote() {
        val intent = Intent(this, VoiceRemoteService::class.java)
        ContextCompat.startForegroundService(this, intent)
        serviceRunning = true
        if (!bound) {
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
            bound = true
        }
        binding.toggle.text = getString(R.string.stop)
        binding.statusDot.setTextColor(ContextCompat.getColor(this, R.color.status_dot_connecting))
        binding.statusLabel.text = getString(R.string.status_connecting)
        binding.hiddenInput.post {
            binding.hiddenInput.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.hiddenInput, InputMethodManager.SHOW_IMPLICIT)
        }
        binding.root.post { updateDebugPanel() }
    }

    private fun stopRemote() {
        serviceRunning = false
        sessionEmittedWords = emptyList()
        binding.hiddenInput.text?.clear()
        if (bound) {
            voiceService?.connectionListener = null
            try {
                unbindService(connection)
            } catch (_: IllegalArgumentException) {
            }
            voiceService = null
            bound = false
        }
        stopService(Intent(this, VoiceRemoteService::class.java))
        updateUiForStopped()
    }

    private fun updateUiForStopped() {
        binding.toggle.text = getString(R.string.start)
        binding.statusDot.setTextColor(ContextCompat.getColor(this, R.color.status_dot_idle))
        binding.statusLabel.text = getString(R.string.status_idle)
        updateDebugPanel()
    }

    private fun updateDebugPanel() {
        if (serviceRunning) {
            binding.debugInputValue.text = when {
                binding.hiddenInput.hasFocus() -> getString(R.string.debug_input_focused)
                else -> getString(R.string.debug_input_waiting_focus)
            }
            binding.debugBufferValue.text = getString(
                R.string.debug_buffer_chars,
                binding.hiddenInput.text?.length ?: 0
            )
        } else {
            binding.debugInputValue.text = getString(R.string.debug_input_stopped)
            binding.debugBufferValue.text = getString(R.string.debug_buffer_empty)
        }
    }

    private fun emitWordsFromBuffer(full: String) {
        val raw = full.trim()
        val words = if (raw.isEmpty()) {
            emptyList()
        } else {
            raw.split(wordSplitRegex).filter { it.isNotEmpty() }
        }
        when {
            words.size > sessionEmittedWords.size -> {
                voiceService?.sendPayload(words.last())
            }
            words.size == sessionEmittedWords.size &&
                words.isNotEmpty() &&
                words.last() != sessionEmittedWords.last() -> {
                voiceService?.sendPayload(
                    VoiceRemoteService.WORD_DELTA_PREFIX + words.last()
                )
            }
        }
        sessionEmittedWords = words
    }

    companion object {
        private const val STATE_SERVICE = "service_running"
    }
}
