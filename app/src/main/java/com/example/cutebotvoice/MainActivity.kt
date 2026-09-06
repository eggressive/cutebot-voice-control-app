package com.example.cutebotvoice

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
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
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.UUID

// Nordic UART Service (NUS) UUIDs, the service the micro:bit BLE UART advertises.
private val NUS_SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
private val NUS_TX_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
private val NUS_RX_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

class MainActivity : AppCompatActivity() {

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var statusText: TextView
    private lateinit var listenButton: Button

    private var bluetoothGatt: BluetoothGatt? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    private val bluetoothAdapter: BluetoothAdapter?
        get() = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        listenButton = findViewById(R.id.listenButton)

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(recognitionListener)

        listenButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    REQUEST_RECORD_AUDIO
                )
            } else {
                startListening()
            }
        }

        requestBluetoothPermissions()
    }

    // --- speech recognition ---

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        speechRecognizer.startListening(intent)
        statusText.text = "Listening..."
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            statusText.text = "Speech error: $error"
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: return
            statusText.text = "Heard: $text"
            handleCommand(text)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: return
            statusText.text = "Heard: $text"
            handleCommand(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
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

    private fun writeCommand(command: String) {
        val characteristic = txCharacteristic
        if (characteristic == null) {
            statusText.text = "Not connected to micro:bit"
            return
        }
        characteristic.value = (command + "\n").toByteArray()
        bluetoothGatt?.writeCharacteristic(characteristic)
        statusText.text = "Sent: $command"
    }

    // --- BLE ---

    @SuppressLint("MissingPermission")
    private fun requestBluetoothPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED
        ) needed.add(Manifest.permission.BLUETOOTH_SCAN)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_BLUETOOTH)
        } else {
            startScan()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        statusText.text = "Scanning for micro:bit..."
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
                statusText.text = "Found: $name, connecting..."
                device.connectGatt(this@MainActivity, false, gattCallback)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service: BluetoothGattService? = gatt.getService(NUS_SERVICE_UUID)
            if (service == null) {
                runOnUiThread { statusText.text = "NUS service not found" }
                return
            }
            txCharacteristic = service.getCharacteristic(NUS_RX_UUID)
            runOnUiThread { statusText.text = "Connected. Say a command." }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_BLUETOOTH -> startScan()
            REQUEST_RECORD_AUDIO -> startListening()
        }
    }

    override fun onDestroy() {
        speechRecognizer.destroy()
        bluetoothGatt?.close()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_RECORD_AUDIO = 1
        private const val REQUEST_BLUETOOTH = 2
    }
}
