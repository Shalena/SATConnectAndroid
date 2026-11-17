package com.example.bleconnect

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val discoveredDevices = linkedMapOf<String, DeviceEntry>()

    private lateinit var statusView: TextView
    private lateinit var detailView: TextView
    private lateinit var scanButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var deviceList: LinearLayout

    private val bluetoothAdapter: BluetoothAdapter?
        get() = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var activeScanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var scanning = false
    private var connecting = false
    private var connected = false
    private var activeDeviceName = ""
    private var bluetoothGatt: BluetoothGatt? = null
    private var pendingPermissionAction: (() -> Unit)? = null

    private val scanTimeout = Runnable {
        if (stopScan()) {
            val detail = if (discoveredDevices.isEmpty()) {
                "No BLE devices found. Try scanning again."
            } else {
                "Select a nearby device to connect."
            }
            showState("Scan complete", detail)
            updateControls()
        }
    }

    private val connectionTimeout = Runnable {
        if (connecting) {
            closeGatt(disconnectFirst = true)
            showState("Connection timed out", "The device did not connect. Try again.", isError = true)
            updateControls()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            runOnUiThread {
                if (gatt !== bluetoothGatt) {
                    closeStaleGatt(gatt)
                    return@runOnUiThread
                }

                when {
                    status != BluetoothGatt.GATT_SUCCESS -> {
                        mainHandler.removeCallbacks(connectionTimeout)
                        connecting = false
                        closeGatt(disconnectFirst = false)
                        showState(
                            "Connection failed",
                            "GATT connection error (status $status). Check the device and try again.",
                            isError = true
                        )
                    }

                    newState == BluetoothProfile.STATE_CONNECTED -> {
                        mainHandler.removeCallbacks(connectionTimeout)
                        connecting = false
                        connected = true
                        showState("Connected", "Connected to $activeDeviceName.")
                    }

                    newState == BluetoothProfile.STATE_DISCONNECTED -> {
                        mainHandler.removeCallbacks(connectionTimeout)
                        connecting = false
                        closeGatt(disconnectFirst = false)
                        showState("Disconnected", "The BLE connection has ended.")
                    }

                    newState == BluetoothProfile.STATE_CONNECTING -> {
                        connecting = true
                        connected = false
                        showState("Connecting", "Connecting to $activeDeviceName...")
                    }

                    newState == BluetoothProfile.STATE_DISCONNECTING -> {
                        mainHandler.removeCallbacks(connectionTimeout)
                        connecting = false
                        connected = false
                        showState("Disconnecting", "Closing the connection to $activeDeviceName...")
                    }
                }
                updateControls()
            }
        }
    }

    private val deviceScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            runOnUiThread {
                try {
                    val device = result.device
                    val address = device.address
                    val name = device.name?.takeIf { it.isNotBlank() } ?: "Unnamed BLE device"
                    discoveredDevices[address] = DeviceEntry(device, name, result.rssi)
                    renderDeviceList()
                } catch (_: SecurityException) {
                    scanning = false
                    mainHandler.removeCallbacks(scanTimeout)
                    scanCallback = null
                    activeScanner = null
                    showState(
                        "Permission required",
                        "Bluetooth access was revoked. Grant nearby-device permission and scan again.",
                        isError = true
                    )
                    updateControls()
                }
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(0, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                scanning = false
                mainHandler.removeCallbacks(scanTimeout)
                scanCallback = null
                activeScanner = null
                showState("Scan failed", scanFailureMessage(errorCode), isError = true)
                updateControls()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createContentView()
        showState("Ready", "Scan for nearby BLE devices to get started.")
        updateControls()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(scanTimeout)
        mainHandler.removeCallbacks(connectionTimeout)
        stopScan()
        closeGatt(disconnectFirst = true)
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_BLE_PERMISSIONS) return

        val action = pendingPermissionAction
        pendingPermissionAction = null
        if (hasRequiredPermissions()) {
            action?.invoke()
        } else {
            showState(
                "Permission required",
                "Allow nearby-device access to scan for and connect to BLE devices.",
                isError = true
            )
        }
        updateControls()
    }

    private fun createContentView() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "BLE device connection"
            textSize = 24f
            setTextColor(Color.BLACK)
        })

        statusView = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.BLACK)
            setPadding(0, dp(18), 0, dp(4))
        }
        root.addView(statusView)

        detailView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.DKGRAY)
        }
        root.addView(detailView)

        progressBar = ProgressBar(this).apply {
            visibility = View.GONE
        }
        root.addView(progressBar)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        scanButton = Button(this).apply {
            text = "Scan for devices"
            setOnClickListener { requestBlePermissions(::startScan) }
        }
        buttonRow.addView(scanButton)

        disconnectButton = Button(this).apply {
            text = "Disconnect"
            setOnClickListener { disconnectFromDevice() }
        }
        buttonRow.addView(
            disconnectButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(8)
            }
        )
        root.addView(buttonRow)

        root.addView(TextView(this).apply {
            text = "Nearby devices"
            textSize = 16f
            setTextColor(Color.BLACK)
            setPadding(0, dp(4), 0, dp(8))
        })

        val scrollView = ScrollView(this)
        deviceList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(deviceList)
        root.addView(
            scrollView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
    }

    private fun requestBlePermissions(action: () -> Unit) {
        val missingPermissions = requiredPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isEmpty()) {
            action()
            return
        }

        pendingPermissionAction = action
        requestPermissions(missingPermissions.toTypedArray(), REQUEST_BLE_PERMISSIONS)
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasRequiredPermissions(): Boolean =
        requiredPermissions().all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    private fun startScan() {
        if (!hasRequiredPermissions()) {
            showState(
                "Permission required",
                "Allow nearby-device access before scanning.",
                isError = true
            )
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null) {
            showState("BLE unavailable", "This device does not support Bluetooth.", isError = true)
            return
        }

        try {
            if (!adapter.isEnabled) {
                showState("Bluetooth is off", "Turn on Bluetooth, then scan again.", isError = true)
                return
            }
            val scanner = adapter.bluetoothLeScanner
            if (scanner == null) {
                showState("BLE unavailable", "The BLE scanner is not available on this device.", isError = true)
                return
            }

            if (!stopScan()) return
            discoveredDevices.clear()
            renderDeviceList()
            activeScanner = scanner
            scanCallback = deviceScanCallback
            scanner.startScan(deviceScanCallback)
            scanning = true
            showState("Scanning", "Searching for nearby BLE devices...")
            updateControls()
            mainHandler.postDelayed(scanTimeout, SCAN_DURATION_MS)
        } catch (_: SecurityException) {
            scanning = false
            activeScanner = null
            scanCallback = null
            showState(
                "Permission required",
                "Bluetooth permission was denied or revoked. Grant access and try again.",
                isError = true
            )
            updateControls()
        } catch (_: IllegalStateException) {
            scanning = false
            activeScanner = null
            scanCallback = null
            showState("Scan unavailable", "The BLE scanner could not start. Try again shortly.", isError = true)
            updateControls()
        }
    }

    private fun stopScan(): Boolean {
        if (!scanning) return true

        val scanner = activeScanner
        val callback = scanCallback
        scanning = false
        mainHandler.removeCallbacks(scanTimeout)
        activeScanner = null
        scanCallback = null
        return try {
            if (scanner != null && callback != null) scanner.stopScan(callback)
            true
        } catch (_: SecurityException) {
            showState(
                "Permission required",
                "Bluetooth permission was revoked while scanning.",
                isError = true
            )
            updateControls()
            false
        }
    }

    private fun connectTo(entry: DeviceEntry) {
        requestBlePermissions {
            val adapter = bluetoothAdapter
            if (adapter == null) {
                showState("BLE unavailable", "This device does not support Bluetooth.", isError = true)
                return@requestBlePermissions
            }
            if (!adapter.isEnabled) {
                showState("Bluetooth is off", "Turn on Bluetooth, then connect again.", isError = true)
                return@requestBlePermissions
            }
            if (!stopScan()) return@requestBlePermissions

            closeGatt(disconnectFirst = true)
            activeDeviceName = entry.name
            connecting = true
            connected = false
            showState("Connecting", "Connecting to ${entry.name}...")
            updateControls()

            try {
                bluetoothGatt = entry.device.connectGatt(
                    this,
                    false,
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE
                )
                if (bluetoothGatt == null) {
                    connecting = false
                    showState("Connection failed", "Android could not start the GATT connection.", isError = true)
                    updateControls()
                    return@requestBlePermissions
                }
                mainHandler.postDelayed(connectionTimeout, CONNECTION_TIMEOUT_MS)
            } catch (_: SecurityException) {
                connecting = false
                showState(
                    "Permission required",
                    "Bluetooth connect permission was denied or revoked.",
                    isError = true
                )
                updateControls()
            } catch (_: IllegalArgumentException) {
                connecting = false
                showState("Connection failed", "The selected BLE device is no longer available.", isError = true)
                updateControls()
            }
        }
    }

    private fun disconnectFromDevice() {
        val gatt = bluetoothGatt ?: return
        try {
            gatt.disconnect()
            connecting = false
            connected = false
            mainHandler.removeCallbacks(connectionTimeout)
            showState("Disconnecting", "Closing the connection to $activeDeviceName...")
            updateControls()
        } catch (_: SecurityException) {
            showState(
                "Permission required",
                "Bluetooth connect permission was denied or revoked.",
                isError = true
            )
            updateControls()
        }
    }

    private fun closeGatt(disconnectFirst: Boolean) {
        val gatt = bluetoothGatt ?: return
        bluetoothGatt = null
        connecting = false
        connected = false
        mainHandler.removeCallbacks(connectionTimeout)
        try {
            if (disconnectFirst) gatt.disconnect()
        } catch (_: SecurityException) {
            showState(
                "Permission required",
                "Bluetooth connect permission was revoked while closing the connection.",
                isError = true
            )
        } finally {
            gatt.close()
        }
    }

    private fun closeStaleGatt(gatt: BluetoothGatt) {
        try {
            gatt.close()
        } catch (_: SecurityException) {
            // Stale callbacks must not replace the state of the current connection.
        }
    }

    private fun renderDeviceList() {
        deviceList.removeAllViews()
        if (discoveredDevices.isEmpty()) {
            deviceList.addView(TextView(this).apply {
                text = if (scanning) "Waiting for nearby BLE devices..." else "No devices discovered yet."
                textSize = 14f
                setTextColor(Color.GRAY)
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }

        discoveredDevices.values.forEach { entry ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val deviceText = TextView(this).apply {
                text = "${entry.name}\n${entry.device.address} - RSSI ${entry.rssi} dBm"
                textSize = 14f
                setTextColor(Color.DKGRAY)
            }
            row.addView(
                deviceText,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            row.addView(Button(this).apply {
                text = "Connect"
                isEnabled = !connecting && !connected
                setOnClickListener { connectTo(entry) }
            })
            deviceList.addView(row)
        }
    }

    private fun showState(title: String, detail: String, isError: Boolean = false) {
        statusView.text = title
        statusView.setTextColor(if (isError) Color.rgb(183, 28, 28) else Color.BLACK)
        detailView.text = detail
        detailView.setTextColor(if (isError) Color.rgb(183, 28, 28) else Color.DKGRAY)
        progressBar.visibility = if (scanning || connecting) View.VISIBLE else View.GONE
    }

    private fun updateControls() {
        scanButton.isEnabled = !scanning && !connecting && !connected
        disconnectButton.visibility =
            if (connecting || connected) View.VISIBLE else View.GONE
        disconnectButton.isEnabled = connecting || connected
        progressBar.visibility = if (scanning || connecting) View.VISIBLE else View.GONE
        renderDeviceList()
    }

    private fun scanFailureMessage(errorCode: Int): String = when (errorCode) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "A BLE scan is already running."
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
            "Android could not register this app for scanning. Try again."
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "BLE scanning is not supported on this device."
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "Android reported an internal scanning error. Try again."
        ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES ->
            "The Bluetooth controller is busy. Stop other scans and try again."
        ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY ->
            "Too many scans were started recently. Wait a moment and try again."
        else -> "Android could not complete the BLE scan (error $errorCode)."
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class DeviceEntry(
        val device: BluetoothDevice,
        val name: String,
        val rssi: Int
    )

    companion object {
        private const val REQUEST_BLE_PERMISSIONS = 1001
        private const val SCAN_DURATION_MS = 12_000L
        private const val CONNECTION_TIMEOUT_MS = 20_000L
    }
}
