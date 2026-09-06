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
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.UUID

// Nordic UART Service (NUS) UUIDs, the service the micro:bit BLE UART advertises.
// 0x2 = TX (phone -> micro:bit). 0x3 = RX (micro:bit -> phone, notify only).
private val NUS_SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
private val NUS_TX_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
private val NUS_RX_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

class MainActivity : AppCompatActivity() {

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var statusText: TextView
    private lateinit var listenButton: Button

    private var bluetoothGatt: BluetoothGatt? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var listeningContinuously: Boolean = false

    private val bluetoothAdapter: BluetoothAdapter?
        get() = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        listenButton = findViewById(R.id.listenButton)

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(recognitionListener)

        listenButton.setOnClickListener { onListenClicked() }

        requestBluetoothPermissions()
    }

    // --- speech recognition ---

    private fun onListenClicked() {
        if (listeningContinuously) {
            stopListening()
            updateStatus(getString(R.string.status_listening_stopped))
            return
        }
        when {
            !hasPermission(Manifest.permission.RECORD_AUDIO) -> {
                requestPermission(Manifest.permission.RECORD_AUDIO, REQUEST_RECORD_AUDIO)
            }
            SpeechRecognizer.isRecognitionAvailable(this) -> {
                listeningContinuously = true
                startListening()
            }
            else -> {
                updateStatus(getString(R.string.speech_recognizer_unavailable))
            }
        }
    }

    private fun startListening() {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            listeningContinuously = false
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Keep listening until the user stops speaking; we restart in onResults.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
        }
        speechRecognizer.startListening(intent)
        updateStatus(getString(R.string.status_listening))
    }

    private fun stopListening() {
        listeningContinuously = false
        speechRecognizer.stopListening()
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            // Common recoverable errors: ERROR_NO_MATCH, ERROR_SPEECH_TIMEOUT, ERROR_NETWORK_TIMEOUT.
            // Fatal errors: ERROR_INSUFFICIENT_PERMISSIONS, ERROR_CLIENT, ERROR_RECOGNIZER_BUSY.
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                    updateStatus(getString(R.string.speech_error_no_match))
                    if (listeningContinuously) restartListeningSoon()
                }
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_SERVER -> {
                    updateStatus(getString(R.string.speech_error_network))
                    listeningContinuously = false
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    updateStatus(getString(R.string.speech_error_permission))
                    listeningContinuously = false
                }
                SpeechRecognizer.ERROR_CLIENT,
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    updateStatus(getString(R.string.speech_error_internal, error))
                    listeningContinuously = false
                }
                else -> {
                    updateStatus(getString(R.string.speech_error_internal, error))
                    if (listeningContinuously) restartListeningSoon()
                }
            }
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()
            if (text != null) {
                updateStatus(getString(R.string.status_heard, text))
                handleCommand(text)
            } else {
                updateStatus(getString(R.string.speech_error_no_match))
            }
            if (listeningContinuously) restartListeningSoon()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: return
            updateStatus(getString(R.string.status_heard, text))
            // Do not issue motor commands on partial results; only final onResults.
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun restartListeningSoon() {
        // Avoid tight loop on recognizer busy.
        listenButton.postDelayed({ startListening() }, 400)
    }

    // --- command map (matches the micro:bit firmware) ---

    private fun handleCommand(text: String) {
        val lower = text.lowercase()
        val command = when {
            lower.contains("forward") || lower.contains("vooruit") -> "1"
            lower.contains("left") || lower.contains("links") -> "2"
            lower.contains("right") || lower.contains("rechts") -> "3"
            lower.contains("stop") || lower.contains("halt") -> "4"
            else -> return
        }
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
            // API 33+ writeCharacteristic returns a status Int; 0 means success.
            gatt.writeCharacteristic(characteristic, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = payload
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
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
            val service: BluetoothGattService? = gatt.getService(NUS_SERVICE_UUID)
            if (service == null) {
                runOnUiThread { updateStatus(getString(R.string.status_nus_not_found)) }
                return
            }
            // Phone writes to the TX characteristic (UUID ending in 0x2).
            txCharacteristic = service.getCharacteristic(NUS_TX_UUID)
            if (txCharacteristic == null) {
                runOnUiThread { updateStatus(getString(R.string.status_tx_not_found)) }
                return
            }
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
        speechRecognizer.destroy()
        bluetoothGatt?.close()
        bluetoothGatt = null
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_RECORD_AUDIO = 1
        private const val REQUEST_BLUETOOTH = 2
    }
}
