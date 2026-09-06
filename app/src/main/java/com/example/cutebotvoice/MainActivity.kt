package com.example.cutebotvoice

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException
import java.util.UUID

// micro:bit UART service UUIDs. NOTE: the micro:bit uses its OWN naming, which is
// the OPPOSITE of the standard Nordic NUS convention:
//   6e400002 = TX  (micro:bit -> phone, INDICATE only, NOT writable)
//   6e400003 = RX  (phone -> micro:bit, WRITE)  <- the phone writes to THIS one
private val NUS_SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
private val NUS_WRITE_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
private val NUS_NOTIFY_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var listenButton: Button
    private lateinit var stopButton: Button
    private lateinit var langButton: Button

    private var bluetoothGatt: BluetoothGatt? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    // Vosk speech recognition (offline, English + Dutch models bundled).
    private var modelEn: Model? = null
    private var modelNl: Model? = null
    private var currentModel: Model? = null
    private var speechService: SpeechService? = null
    private var listeningContinuously: Boolean = false
    private var lastSentCommand: String? = null
    private var lastSentAt: Long = 0L
    private var useDutch: Boolean = true  // grandson is Dutch; default to Dutch

    private val bluetoothAdapter: BluetoothAdapter?
        get() = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        listenButton = findViewById(R.id.listenButton)
        stopButton = findViewById(R.id.stopButton)
        langButton = findViewById(R.id.langButton)

        LibVosk.setLogLevel(LogLevel.WARNINGS)

        listenButton.setOnClickListener { onListenClicked() }
        stopButton.setOnClickListener { writeCommand("4") }
        langButton.setOnClickListener { toggleLanguage() }

        requestBluetoothPermissions()
        initModels()
    }

    // --- Vosk models + speech ---

    private fun initModels() {
        // Unpack both models. Dutch is the default (grandson is Dutch); English is
        // available via the language toggle. Both share the same command map.
        StorageService.unpack(
            this, "model-nl", "model-nl",
            { m ->
                modelNl = m
                if (useDutch) {
                    currentModel = m
                    updateStatus(getString(R.string.status_ready))
                }
            },
            { e ->
                Log.e("Cutebot", "Failed to unpack Dutch model", e)
                updateStatus(getString(R.string.status_model_error))
            }
        )
        StorageService.unpack(
            this, "model-en-us", "model-en-us",
            { m ->
                modelEn = m
                if (!useDutch) {
                    currentModel = m
                    updateStatus(getString(R.string.status_ready))
                }
            },
            { e ->
                Log.e("Cutebot", "Failed to unpack English model", e)
            }
        )
    }

    private fun toggleLanguage() {
        useDutch = !useDutch
        currentModel = if (useDutch) modelNl else modelEn
        updateStatus(getString(if (useDutch) R.string.lang_dutch else R.string.lang_english))
    }

    private fun onListenClicked() {
        if (listeningContinuously) {
            stopListening()
            updateStatus(getString(R.string.status_listening_stopped))
            return
        }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            requestPermission(Manifest.permission.RECORD_AUDIO, REQUEST_RECORD_AUDIO)
            return
        }
        startListening()
    }

    private fun startListening() {
        val m = currentModel
        if (m == null) {
            updateStatus(getString(R.string.status_model_error))
            return
        }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            listeningContinuously = false
            return
        }
        try {
            val rec = Recognizer(m, 16000.0f)
            speechService = SpeechService(rec, 16000.0f)
            speechService?.startListening(recognitionListener)
            listeningContinuously = true
            updateStatus(getString(R.string.status_listening))
        } catch (e: IOException) {
            Log.e("Cutebot", "Failed to start speech service", e)
            updateStatus(getString(R.string.status_model_error))
        }
    }

    private fun stopListening() {
        listeningContinuously = false
        speechService?.stop()
        speechService = null
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String) {
            val text = extractText(hypothesis) ?: return
            updateStatus(getString(R.string.status_heard, text))
            handleCommand(text)
        }

        override fun onResult(hypothesis: String) {
            val text = extractText(hypothesis) ?: return
            updateStatus(getString(R.string.status_heard, text))
            handleCommand(text)
        }

        override fun onFinalResult(hypothesis: String) {
            val text = extractText(hypothesis) ?: return
            updateStatus(getString(R.string.status_heard, text))
            handleCommand(text)
        }

        override fun onError(e: Exception) {
            Log.e("Cutebot", "Speech error", e)
            updateStatus(getString(R.string.speech_error_internal, 0))
        }

        override fun onTimeout() {
            // Vosk times out after silence; restart if still in continuous mode.
            if (listeningContinuously) {
                restartListeningSoon()
            }
        }
    }

    // Vosk returns JSON like {"text": "left"}. Extract the "text" field.
    private fun extractText(hypothesis: String): String? {
        val start = hypothesis.indexOf("\"text\"")
        if (start < 0) return null
        val colon = hypothesis.indexOf(':', start)
        if (colon < 0) return null
        val quote = hypothesis.indexOf('"', colon + 1)
        if (quote < 0) return null
        val end = hypothesis.indexOf('"', quote + 1)
        if (end < 0) return null
        return hypothesis.substring(quote + 1, end)
    }

    private fun restartListeningSoon() {
        listenButton.postDelayed({ startListening() }, 400)
    }

    // --- command map (matches the micro:bit firmware) ---

    private fun handleCommand(text: String) {
        val lower = text.lowercase()
        val command = when {
            lower.contains("forward") || lower.contains("vooruit") -> "1"
            lower.contains("left") || lower.contains("links") -> "2"
            lower.contains("right") || lower.contains("rechts") -> "3"
            lower.contains("stop") || lower.contains("halt") || lower.contains("stop") -> "4"
            else -> return
        }
        // Debounce: skip if the same command was sent within the last 1.5s.
        val now = System.currentTimeMillis()
        if (command == lastSentCommand && now - lastSentAt < 1500) {
            return
        }
        lastSentCommand = command
        lastSentAt = now
        writeCommand(command)
    }

    @SuppressLint("MissingPermission")
    private fun writeCommand(command: String) {
        if (!hasBluetoothConnectPermission()) {
            updateStatus(getString(R.string.status_not_connected))
            return
        }
        val characteristic = txCharacteristic
        if (characteristic == null) {
            updateStatus(getString(R.string.status_not_connected))
            return
        }
        val payload = (command + "\n").toByteArray()
        val gatt = bluetoothGatt
        if (gatt == null) {
            updateStatus(getString(R.string.status_not_connected))
            return
        }
        val written = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = gatt.writeCharacteristic(characteristic, payload, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            Log.d("Cutebot", "writeCharacteristic status=$status")
            status == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            characteristic.value = payload
            @Suppress("DEPRECATION")
            val ok = gatt.writeCharacteristic(characteristic)
            Log.d("Cutebot", "writeCharacteristic (legacy) returned=$ok")
            ok
        }
        if (written) {
            updateStatus(getString(R.string.status_sent, command))
        } else {
            updateStatus(getString(R.string.status_write_failed, command))
        }
    }

    // --- BLE ---

    @SuppressLint("MissingPermission")
    private fun requestBluetoothPermissions() {
        if (!bluetoothAdapter!!.isEnabled) {
            promptEnableBluetooth()
            return
        }
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (!hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (!hasPermission(Manifest.permission.BLUETOOTH)) {
                needed.add(Manifest.permission.BLUETOOTH)
            }
            if (!hasPermission(Manifest.permission.BLUETOOTH_ADMIN)) {
                needed.add(Manifest.permission.BLUETOOTH_ADMIN)
            }
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_BLUETOOTH)
        } else {
            startScan()
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            hasPermission(Manifest.permission.BLUETOOTH)
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermission(permission: String, requestCode: Int) {
        ActivityCompat.requestPermissions(this, arrayOf(permission), requestCode)
    }

    private fun promptEnableBluetooth() {
        AlertDialog.Builder(this)
            .setTitle(R.string.bluetooth_disabled_title)
            .setMessage(R.string.bluetooth_disabled_message)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!hasBluetoothConnectPermission()) {
            requestBluetoothPermissions()
            return
        }
        if (!bluetoothAdapter!!.isEnabled) {
            promptEnableBluetooth()
            return
        }
        updateStatus(getString(R.string.status_scanning))
        bluetoothAdapter?.bluetoothLeScanner?.startScan(scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: return
            if (name.contains("micro:bit", ignoreCase = true) ||
                name.contains("BBC micro", ignoreCase = true)
            ) {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(this)
                updateStatus(getString(R.string.status_found_connecting, name))
                device.connectGatt(this@MainActivity, false, gattCallback)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d("Cutebot", "onConnectionStateChange status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    bluetoothGatt = gatt
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    bluetoothGatt = null
                    txCharacteristic = null
                    runOnUiThread { updateStatus(getString(R.string.status_disconnected)) }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d("Cutebot", "onServicesDiscovered status=$status")
            val service: BluetoothGattService? = gatt.getService(NUS_SERVICE_UUID)
            if (service == null) {
                runOnUiThread { updateStatus(getString(R.string.status_nus_not_found)) }
                return
            }
            // Phone writes to the RX characteristic (UUID ending in 0x3), which is
            // the WRITE characteristic. 0x2 is INDICATE-only (micro:bit -> phone).
            txCharacteristic = service.getCharacteristic(NUS_WRITE_UUID)
            if (txCharacteristic == null) {
                runOnUiThread { updateStatus(getString(R.string.status_tx_not_found)) }
                return
            }
            Log.d("Cutebot", "TX characteristic properties=${txCharacteristic!!.properties}")
            runOnUiThread { updateStatus(getString(R.string.status_connected_speak)) }
        }
    }

    private fun updateStatus(message: CharSequence) {
        runOnUiThread { statusText.text = message }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        when (requestCode) {
            REQUEST_BLUETOOTH -> {
                if (allGranted) startScan() else updateStatus(getString(R.string.status_bluetooth_permission_denied))
            }
            REQUEST_RECORD_AUDIO -> {
                if (allGranted) {
                    listeningContinuously = true
                    startListening()
                } else {
                    updateStatus(getString(R.string.status_microphone_permission_denied))
                }
            }
        }
    }

    override fun onDestroy() {
        stopListening()
        modelEn?.close()
        modelNl?.close()
        modelEn = null
        modelNl = null
        currentModel = null
        bluetoothGatt?.close()
        bluetoothGatt = null
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_RECORD_AUDIO = 1
        private const val REQUEST_BLUETOOTH = 2
    }
}
