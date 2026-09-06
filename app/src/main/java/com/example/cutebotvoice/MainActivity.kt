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
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
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

private const val TAG = "Cutebot"
private const val REQUEST_RECORD_AUDIO = 1
private const val REQUEST_BLUETOOTH = 2

private const val SCAN_TIMEOUT_MS = 15_000L
private const val COMMAND_DEBOUNCE_MS = 400L
private const val SPEECH_SAMPLE_RATE = 16000.0f

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var listenButton: Button
    private lateinit var stopButton: Button
    private lateinit var langButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var isWriting = false
    private val writeQueue = ArrayDeque<ByteArray>()

    // Vosk speech recognition (offline). Dutch is loaded immediately; English is
    // lazy-loaded the first time the user toggles to it, to reduce startup cost.
    private var modelEn: Model? = null
    private var modelNl: Model? = null
    private var currentModel: Model? = null
    private var speechService: SpeechService? = null
    private var listeningContinuously: Boolean = false
    private var lastSentCommand: String? = null
    private var lastSentAt: Long = 0L
    private var useDutch: Boolean = true

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
        langButton.setOnClickListener { onToggleLanguage() }

        initModels()
        requestBluetoothPermissions()
    }

    // --- lifecycle ---

    override fun onResume() {
        super.onResume()
        if (bluetoothGatt == null) {
            requestBluetoothPermissions()
        }
    }

    override fun onPause() {
        super.onPause()
        stopScan()
        stopListening()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopListening()
        closeModels()
        disconnectGatt()
        super.onDestroy()
    }

    // --- Vosk models + speech ---

    private fun initModels() {
        // Load Dutch immediately because it is the default. English is loaded
        // lazily on first toggle to reduce cold-start time and memory pressure.
        loadDutchModel()
    }

    private fun loadDutchModel() {
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
                Log.e(TAG, "Failed to unpack Dutch model", e)
                updateStatus(getString(R.string.status_model_error))
            }
        )
    }

    private fun loadEnglishModel(onReady: () -> Unit) {
        val existing = modelEn
        if (existing != null) {
            onReady()
            return
        }
        updateStatus(getString(R.string.status_loading_english))
        StorageService.unpack(
            this, "model-en-us", "model-en-us",
            { m ->
                modelEn = m
                onReady()
            },
            { e ->
                Log.e(TAG, "Failed to unpack English model", e)
                updateStatus(getString(R.string.status_model_error))
            }
        )
    }

    private fun onToggleLanguage() {
        useDutch = !useDutch
        if (useDutch) {
            currentModel = modelNl
            updateStatus(getString(R.string.lang_dutch))
        } else {
            loadEnglishModel {
                currentModel = modelEn
                updateStatus(getString(R.string.lang_english))
            }
        }
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
            speechService?.stop()
            val rec = Recognizer(m, SPEECH_SAMPLE_RATE)
            speechService = SpeechService(rec, SPEECH_SAMPLE_RATE)
            speechService?.startListening(recognitionListener)
            listeningContinuously = true
            updateListenButtonState()
            updateStatus(getString(R.string.status_listening))
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start speech service", e)
            updateStatus(getString(R.string.status_model_error))
        }
    }

    private fun stopListening() {
        listeningContinuously = false
        speechService?.stop()
        speechService = null
        updateListenButtonState()
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String) {
            val text = extractText(hypothesis) ?: return
            updateStatus(getString(R.string.status_heard, text))
            // Do not send motor commands on partial results; wait for final.
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
            if (listeningContinuously) {
                restartListeningSoon()
            }
        }

        override fun onError(e: Exception) {
            Log.e(TAG, "Speech error", e)
            updateStatus(getString(R.string.speech_error_internal, 0))
            if (listeningContinuously) {
                restartListeningSoon()
            }
        }

        override fun onTimeout() {
            if (listeningContinuously) {
                restartListeningSoon()
            }
        }
    }

    private fun restartListeningSoon() {
        handler.removeCallbacks(restartListeningRunnable)
        handler.postDelayed(restartListeningRunnable, 400)
    }

    private val restartListeningRunnable = Runnable { startListening() }

    // Vosk returns JSON like {"text": "left"}. Extract the "text" field safely.
    private fun extractText(hypothesis: String): String? {
        return try {
            val text = JSONObject(hypothesis).optString("text", "")
            text.ifEmpty { null }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Vosk hypothesis: $hypothesis", e)
            null
        }
    }

    // --- command map (matches the micro:bit firmware) ---

    private fun handleCommand(text: String) {
        val lower = text.lowercase()
        val command = findCommand(lower) ?: return

        // Debounce: skip if the same command was sent within the last 0.4s.
        val now = System.currentTimeMillis()
        if (command == lastSentCommand && now - lastSentAt < COMMAND_DEBOUNCE_MS) {
            return
        }
        lastSentCommand = command
        lastSentAt = now
        writeCommand(command)
    }

    private fun findCommand(text: String): String? {
        // Match whole words only to avoid false positives (e.g. "bright" matching "right").
        // Order matters: check longer/narrower forms before shorter ones where they share
        // a prefix (e.g. "achteruit" vs "achter").
        val patterns = listOf(
            listOf("forward", "vooruit") to "1",
            listOf("left", "links") to "2",
            listOf("right", "rechts") to "3",
            listOf("stop", "halt") to "4",
            listOf("back", "achteruit") to "5",
            listOf("achter") to "5"
        )
        for ((words, command) in patterns) {
            for (word in words) {
                if (containsWholeWord(text, word)) {
                    return command
                }
            }
        }
        return null
    }

    private fun containsWholeWord(text: String, word: String): Boolean {
        val regex = ("""\b""" + Regex.escape(word) + """\b""").toRegex()
        return regex.containsMatchIn(text)
    }

    @SuppressLint("MissingPermission")
    private fun writeCommand(command: String) {
        if (!hasBluetoothConnectPermission()) {
            updateStatus(getString(R.string.status_not_connected))
            return
        }
        val gatt = bluetoothGatt
        val characteristic = writeCharacteristic
        if (gatt == null || characteristic == null) {
            updateStatus(getString(R.string.status_not_connected))
            return
        }
        val payload = (command + "\n").toByteArray()
        synchronized(writeQueue) {
            writeQueue.add(payload)
            if (!isWriting) {
                flushWriteQueue(gatt, characteristic)
            }
        }
        // Optimistic feedback: the write was enqueued/initiated successfully.
        updateStatus(getString(R.string.status_sent, command))
    }

    @SuppressLint("MissingPermission")
    private fun flushWriteQueue(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val next: ByteArray
        synchronized(writeQueue) {
            next = writeQueue.removeFirstOrNull() ?: run {
                isWriting = false
                return
            }
            isWriting = true
        }

        val properties = characteristic.properties
        val (writeType, supported) = when {
            (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 ->
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE to true
            (properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ->
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT to true
            else -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT to false
        }
        if (!supported) {
            Log.e(TAG, "Write characteristic does not support WRITE or WRITE_NO_RESPONSE")
            updateStatus(getString(R.string.status_tx_not_found))
            synchronized(writeQueue) { isWriting = false }
            return
        }

        val written = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = gatt.writeCharacteristic(characteristic, next, writeType)
            Log.d(TAG, "writeCharacteristic status=$status")
            status == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = writeType
            @Suppress("DEPRECATION")
            characteristic.value = next
            @Suppress("DEPRECATION")
            val ok = gatt.writeCharacteristic(characteristic)
            Log.d(TAG, "writeCharacteristic (legacy) returned=$ok")
            ok
        }
        if (!written) {
            Log.w(TAG, "Failed to enqueue BLE write")
            updateStatus(getString(R.string.status_write_failed, String(next, Charsets.UTF_8).trim()))
            synchronized(writeQueue) { isWriting = false }
        }
    }

    // --- BLE ---

    @SuppressLint("MissingPermission")
    private fun requestBluetoothPermissions() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
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
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            promptEnableBluetooth()
            return
        }
        updateStatus(getString(R.string.status_scanning))

        // Filter by the NUS service UUID so the OS only wakes us for micro:bits.
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(NUS_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        adapter.bluetoothLeScanner?.startScan(listOf(filter), settings, scanCallback)

        handler.removeCallbacks(scanTimeoutRunnable)
        handler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS)
    }

    private fun stopScan() {
        handler.removeCallbacks(scanTimeoutRunnable)
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Log.w(TAG, "stopScan permission missing", e)
        }
    }

    private val scanTimeoutRunnable = Runnable {
        stopScan()
        updateStatus(getString(R.string.status_scan_timeout))
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name
            if (name != null &&
                (name.contains("micro:bit", ignoreCase = true) ||
                    name.contains("BBC micro", ignoreCase = true))
            ) {
                stopScan()
                updateStatus(getString(R.string.status_found_connecting, name))
                device.connectGatt(this@MainActivity, false, gattCallback)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    bluetoothGatt = gatt
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    disconnectGatt()
                    runOnUiThread { updateStatus(getString(R.string.status_disconnected)) }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "onServicesDiscovered status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Service discovery failed: $status")
                disconnectGatt()
                updateStatus(getString(R.string.status_nus_not_found))
                return
            }
            val service: BluetoothGattService? = gatt.getService(NUS_SERVICE_UUID)
            if (service == null) {
                updateStatus(getString(R.string.status_nus_not_found))
                return
            }
            // Phone writes to the RX characteristic (UUID ending in 0x3), which is
            // the WRITE characteristic. 0x2 is INDICATE-only (micro:bit -> phone).
            val characteristic = service.getCharacteristic(NUS_WRITE_UUID)
            if (characteristic == null) {
                updateStatus(getString(R.string.status_tx_not_found))
                return
            }
            writeCharacteristic = characteristic
            Log.d(TAG, "Write characteristic properties=${characteristic.properties}")
            updateStatus(getString(R.string.status_connected_speak))
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val success = status == BluetoothGatt.GATT_SUCCESS
            Log.d(TAG, "onCharacteristicWrite status=$status")
            if (!success) {
                updateStatus(getString(R.string.status_write_failed, "BLE"))
            }
            val g = bluetoothGatt
            val c = writeCharacteristic
            if (g != null && c != null) {
                flushWriteQueue(g, c)
            } else {
                synchronized(writeQueue) { isWriting = false }
            }
        }
    }

    private fun disconnectGatt() {
        bluetoothGatt?.close()
        bluetoothGatt = null
        writeCharacteristic = null
        synchronized(writeQueue) {
            writeQueue.clear()
            isWriting = false
        }
    }

    // --- UI helpers ---

    private fun updateStatus(message: CharSequence) {
        runOnUiThread { statusText.text = message }
    }

    private fun updateListenButtonState() {
        runOnUiThread {
            listenButton.text = getString(
                if (listeningContinuously) R.string.stop_listening else R.string.listen
            )
        }
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
                    startListening()
                } else {
                    updateStatus(getString(R.string.status_microphone_permission_denied))
                }
            }
        }
    }

    private fun closeModels() {
        modelEn?.close()
        modelNl?.close()
        modelEn = null
        modelNl = null
        currentModel = null
    }
}
