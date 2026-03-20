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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.iamweasel89.voiceshell.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), VoiceRemoteService.ConnectionListener {

    private lateinit var binding: ActivityMainBinding
    private var voiceService: VoiceRemoteService? = null
    private var serviceRunning = false
    private var bound = false

    private val voiceTextWatcher = VoiceTextWatcher()
    private val wordSplitRegex = Regex("\\s+")
    private var imeVisible = false

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

        binding.voiceInput.setOnFocusChangeListener { _, _ -> updateDebugPanel() }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            updateDebugPanel()
            insets
        }

        binding.debugRequestFocus.setOnClickListener {
            binding.voiceInput.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.voiceInput, InputMethodManager.SHOW_IMPLICIT)
            updateDebugPanel()
            binding.root.postDelayed({ syncImeVisibleFromInsets(); updateDebugPanel() }, 100)
            binding.root.postDelayed({ syncImeVisibleFromInsets(); updateDebugPanel() }, 400)
        }

        if (serviceRunning) {
            binding.toggle.text = getString(R.string.stop)
        }

        syncImeVisibleFromInsets()
        updateDebugPanel()
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
        if (hasFocus) {
            syncImeVisibleFromInsets()
            updateDebugPanel()
        }
    }

    override fun onResume() {
        super.onResume()
        binding.voiceInput.post {
            binding.voiceInput.requestFocus()
            if (serviceRunning) showKeyboard()
            syncImeVisibleFromInsets()
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
        binding.statusDot.setTextColor(ContextCompat.getColor(this, R.color.status_dot_connecting))
        binding.statusLabel.text = getString(R.string.status_connecting)
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
        binding.statusDot.setTextColor(ContextCompat.getColor(this, R.color.status_dot_idle))
        binding.statusLabel.text = getString(R.string.status_idle)
    }

    private fun showKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.voiceInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun syncImeVisibleFromInsets() {
        val insets = ViewCompat.getRootWindowInsets(binding.root)
        imeVisible = insets?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }

    private fun updateDebugPanel() {
        val yes = getString(R.string.debug_yes)
        val no = getString(R.string.debug_no)
        binding.debugFocusValue.text = getString(
            R.string.debug_edit_focus,
            if (binding.voiceInput.hasFocus()) yes else no
        )
        binding.debugImeValue.text = getString(
            R.string.debug_soft_keyboard,
            if (imeVisible) yes else no
        )
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
