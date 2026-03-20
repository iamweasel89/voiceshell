package io.github.iamweasel89.voiceshell

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import io.github.iamweasel89.voiceshell.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), VoiceRemoteService.ConnectionListener {

    private lateinit var binding: ActivityMainBinding
    private var voiceService: VoiceRemoteService? = null
    private var serviceRunning = false
    private var bound = false

    private val voiceTextWatcher = VoiceTextWatcher()
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
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            voiceService?.connectionListener = null
            voiceService = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serviceRunning = savedInstanceState?.getBoolean(STATE_SERVICE) ?: false

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toggle.setOnClickListener {
            if (serviceRunning) stopRemote() else requestStart()
        }

        binding.voiceInput.addTextChangedListener(voiceTextWatcher)

        if (serviceRunning) {
            binding.toggle.text = getString(R.string.stop)
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

    override fun onResume() {
        super.onResume()
        binding.voiceInput.post {
            binding.voiceInput.requestFocus()
            if (serviceRunning) showKeyboard()
        }
    }

    override fun onConnectionChanged(connected: Boolean, message: String?) {
        if (!serviceRunning) return
        val color = if (connected) R.color.status_connected else R.color.status_disconnected
        val label = when {
            connected -> getString(R.string.status_connected)
            message != null -> getString(R.string.status_disconnected) + " ($message)"
            else -> getString(R.string.status_disconnected)
        }
        binding.status.setTextColor(ContextCompat.getColor(this, color))
        binding.status.text = label
    }

    private fun requestStart() {
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
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
        voiceTextWatcher.reset()
        binding.toggle.text = getString(R.string.stop)
        binding.status.text = getString(R.string.status_connecting)
        binding.status.setTextColor(ContextCompat.getColor(this, R.color.status_disconnected))
        binding.voiceInput.post {
            binding.voiceInput.requestFocus()
            showKeyboard()
        }
    }

    private fun stopRemote() {
        serviceRunning = false
        voiceTextWatcher.reset()
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
        binding.status.text = getString(R.string.status_idle)
        binding.status.setTextColor(ContextCompat.getColor(this, R.color.status_disconnected))
    }

    private fun showKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.voiceInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private inner class VoiceTextWatcher : TextWatcher {
        private var lastWords: List<String> = emptyList()

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

        override fun afterTextChanged(s: Editable?) {
            if (!serviceRunning) return
            val raw = s?.toString() ?: ""
            val words = raw.trim().split(wordSplitRegex).filter { it.isNotEmpty() }

            when {
                words.size > lastWords.size -> {
                    voiceService?.sendPayload(words.last())
                }
                words.size == lastWords.size && words.isNotEmpty() && words.last() != lastWords.last() -> {
                    voiceService?.sendPayload(DELTA_PREFIX + words.last())
                }
            }
            lastWords = words
        }

        fun reset() {
            lastWords = emptyList()
        }
    }

    companion object {
        private const val STATE_SERVICE = "service_running"
        private const val DELTA_PREFIX = "\u0394"
    }
}
