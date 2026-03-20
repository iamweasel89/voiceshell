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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import io.github.iamweasel89.voiceshell.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), VoiceRemoteService.ConnectionListener {

    private lateinit var binding: ActivityMainBinding
    private var voiceService: VoiceRemoteService? = null
    private var serviceRunning = false
    private var bound = false

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serviceRunning = savedInstanceState?.getBoolean(STATE_SERVICE) ?: false

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toggle.setOnClickListener {
            if (serviceRunning) stopRemote() else requestStart()
        }

        binding.debugRestartRecognition.setOnClickListener {
            voiceService?.restartSpeechRecognition()
            binding.root.postDelayed({ updateDebugPanel() }, 150)
        }

        if (serviceRunning) {
            binding.toggle.text = getString(R.string.stop)
        }

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

    override fun onVoiceDebugMayHaveChanged() {
        updateDebugPanel()
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
        binding.toggle.text = getString(R.string.stop)
        binding.statusDot.setTextColor(ContextCompat.getColor(this, R.color.status_dot_connecting))
        binding.statusLabel.text = getString(R.string.status_connecting)
        binding.root.post { updateDebugPanel() }
    }

    private fun stopRemote() {
        serviceRunning = false
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
        val svc = voiceService
        if (serviceRunning && svc != null) {
            binding.debugRecognitionValue.text = when {
                !svc.speechRecognitionAvailable -> getString(R.string.debug_speech_unavailable)
                svc.isRecognizerInSession -> getString(R.string.debug_speech_listening)
                else -> getString(R.string.debug_speech_idle)
            }
            binding.debugErrorValue.text = svc.lastRecognizerError?.let { err ->
                getString(R.string.debug_last_error, err)
            } ?: getString(R.string.debug_no_error)
        } else {
            binding.debugRecognitionValue.text = getString(R.string.debug_speech_stopped)
            binding.debugErrorValue.text = getString(R.string.debug_no_error)
        }
    }

    companion object {
        private const val STATE_SERVICE = "service_running"
    }
}
