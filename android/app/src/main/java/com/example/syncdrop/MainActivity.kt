package com.example.syncdrop

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelUuid
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.ActivityCompat
import com.example.syncdrop.ui.theme.SyncdropTheme
import java.io.OutputStream
import java.util.UUID

class MainActivity : ComponentActivity() {

    companion object {
        private const val REQUEST_BLUETOOTH = 100

        val SERVICE_UUID: UUID =
            UUID.fromString("19b10000-e8f2-537e-4f6c-d104768a1214")

        val MESSAGE_UUID: UUID =
            UUID.fromString("19b10001-e8f2-537e-4f6c-d104768a1214")

        val FILE_UUID: UUID =
            UUID.fromString("19b10002-e8f2-537e-4f6c-d104768a1214")
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null

    // Transfer I/O
    private var outputStream: OutputStream? = null
    private var currentFileUri: Uri? = null
    private var transferStartTime: Long = 0L

    // Reactive Compose States
    private val serverState = mutableStateOf(ServerState.INITIALIZING)
    private val statusMessage = mutableStateOf("Initializing SyncDrop...")
    private val connectedDeviceName = mutableStateOf<String?>(null)
    private val transferState = mutableStateOf(TransferState())
    private val receivedMessages = mutableStateListOf<ReceivedMessage>()
    private val receivedFilesHistory = mutableStateListOf<ReceivedFileHistory>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            SyncdropTheme {
                SyncDropAppRoot(
                    serverState = serverState.value,
                    statusMessage = statusMessage.value,
                    connectedDeviceName = connectedDeviceName.value,
                    transferState = transferState.value,
                    messages = receivedMessages,
                    fileHistory = receivedFilesHistory,
                    onRestartServer = { restartBluetooth() },
                    onRequestPermissions = { requestBluetoothPermissions() }
                )
            }
        }

        requestBluetoothPermissions()
    }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissions = arrayOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT
            )

            val missingPermissions = permissions.filter {
                ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }

            if (missingPermissions.isNotEmpty()) {
                serverState.value = ServerState.PERMISSION_REQUIRED
                statusMessage.value = "Bluetooth permissions required"
                ActivityCompat.requestPermissions(
                    this,
                    missingPermissions.toTypedArray(),
                    REQUEST_BLUETOOTH
                )
                return
            }
        }

        startBluetooth()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_BLUETOOTH) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startBluetooth()
            } else {
                serverState.value = ServerState.PERMISSION_REQUIRED
                statusMessage.value = "Bluetooth permissions were denied."
            }
        }
    }

    private fun restartBluetooth() {
        stopBluetooth()
        startBluetooth()
    }

    private fun startBluetooth() {
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null || bluetoothAdapter?.isEnabled == false) {
            serverState.value = ServerState.BLUETOOTH_OFF
            statusMessage.value = "Please turn Bluetooth ON."
            return
        }

        startGattServer()
        startAdvertising()
    }

    private fun stopBluetooth() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
        ) {
            advertiser?.stopAdvertising(advertiseCallback)
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        ) {
            gattServer?.close()
        }

        advertiser = null
        gattServer = null
    }

    private fun startGattServer() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        ) {
            return
        }

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        gattServer = bluetoothManager.openGattServer(this, gattCallback)

        val service = BluetoothGattService(
            SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val messageCharacteristic = BluetoothGattCharacteristic(
            MESSAGE_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(messageCharacteristic)

        val fileCharacteristic = BluetoothGattCharacteristic(
            FILE_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(fileCharacteristic)

        val added = gattServer?.addService(service)
        if (added == null) {
            serverState.value = ServerState.ERROR
            statusMessage.value = "Failed to create GATT service."
            return
        }

        statusMessage.value = "GATT server listening for connections..."
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(
            device: android.bluetooth.BluetoothDevice?,
            status: Int,
            newState: Int
        ) {
            runOnUiThread {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    serverState.value = ServerState.CONNECTED
                    val name = if (ActivityCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                    ) {
                        device?.name ?: device?.address ?: "Web Peer"
                    } else "Web Peer"
                    connectedDeviceName.value = name
                    statusMessage.value = "Connected to $name"
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    serverState.value = ServerState.ADVERTISING
                    connectedDeviceName.value = null
                    statusMessage.value = "Device disconnected. Ready for new connection."
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: android.bluetooth.BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (characteristic == null) return
            val data = value ?: ByteArray(0)

            if (characteristic.uuid == MESSAGE_UUID) {
                val message = data.toString(Charsets.UTF_8)

                if (message.startsWith("{") && message.endsWith("}")) {
                    val json = try {
                        org.json.JSONObject(message)
                    } catch (e: Exception) {
                        null
                    }

                    if (json != null && json.optString("type") == "file") {
                        startFileTransfer(json)
                    } else {
                        runOnUiThread {
                            receivedMessages.add(0, ReceivedMessage(text = message))
                            statusMessage.value = "Message received: $message"
                        }
                    }
                } else {
                    runOnUiThread {
                        receivedMessages.add(0, ReceivedMessage(text = message))
                        statusMessage.value = "Message: $message"
                    }
                }
            } else if (characteristic.uuid == FILE_UUID) {
                receiveFileChunk(data)
            }

            if (responseNeeded && device != null) {
                if (ActivityCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                ) {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        null
                    )
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: android.bluetooth.BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            if (characteristic?.uuid != MESSAGE_UUID || device == null) return

            if (ActivityCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            ) {
                return
            }

            val greeting = "Hello from SyncDrop Android"
            val response = greeting.toByteArray(Charsets.UTF_8)

            gattServer?.sendResponse(
                device,
                requestId,
                BluetoothGatt.GATT_SUCCESS,
                offset,
                response
            )

            runOnUiThread {
                statusMessage.value = "Sent greeting to $connectedDeviceName"
            }
        }
    }

    private fun startAdvertising() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        ) {
            return
        }

        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            serverState.value = ServerState.ERROR
            statusMessage.value = "BLE Advertising is not supported on this device."
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            runOnUiThread {
                serverState.value = ServerState.ADVERTISING
                statusMessage.value = "Broadcasting SyncDrop BLE service..."
            }
        }

        override fun onStartFailure(errorCode: Int) {
            runOnUiThread {
                serverState.value = ServerState.ERROR
                statusMessage.value = "BLE advertising failed (code $errorCode)"
            }
        }
    }

    // ==========================================================================
    // File Receiving Engine
    // ==========================================================================

    private fun startFileTransfer(json: org.json.JSONObject) {
        try {
            val fileName = json.getString("name")
            val fileSize = json.getLong("size")
            val mimeType = json.optString("mime", "application/octet-stream")
            transferStartTime = System.currentTimeMillis()

            val values = ContentValues().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SyncDrop")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                } else {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                }
            }

            val downloadsUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Downloads.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Files.getContentUri("external")
            }

            val uri = contentResolver.insert(downloadsUri, values)
                ?: throw Exception("Could not initialize download file entry.")

            currentFileUri = uri
            outputStream = contentResolver.openOutputStream(uri)
                ?: throw Exception("Could not open output stream for writing.")

            runOnUiThread {
                serverState.value = ServerState.TRANSFERRING
                transferState.value = TransferState(
                    fileName = fileName,
                    fileSize = fileSize,
                    receivedBytes = 0L,
                    mimeType = mimeType,
                    uri = uri,
                    isTransferring = true,
                    isCompleted = false,
                    isError = false,
                    startTime = transferStartTime
                )
                statusMessage.value = "Receiving file: $fileName"
            }

        } catch (e: Exception) {
            outputStream = null
            currentFileUri = null

            runOnUiThread {
                serverState.value = ServerState.ERROR
                transferState.value = TransferState(
                    isTransferring = false,
                    isError = true,
                    errorMessage = e.message
                )
                statusMessage.value = "Failed to start file transfer: ${e.message}"
            }
        }
    }

    private fun receiveFileChunk(data: ByteArray) {
        val current = transferState.value
        if (!current.isTransferring) return

        try {
            outputStream?.write(data)

            val updatedBytes = current.receivedBytes + data.size
            val elapsedSec = (System.currentTimeMillis() - current.startTime) / 1000.0
            val speed = if (elapsedSec > 0) (updatedBytes / elapsedSec).toLong() else 0L

            if (updatedBytes >= current.fileSize) {
                finishFileTransfer(updatedBytes)
                return
            }

            runOnUiThread {
                transferState.value = current.copy(
                    receivedBytes = updatedBytes,
                    speedBps = speed
                )
            }

        } catch (e: Exception) {
            outputStream?.close()
            outputStream = null

            runOnUiThread {
                serverState.value = ServerState.ERROR
                transferState.value = current.copy(
                    isTransferring = false,
                    isError = true,
                    errorMessage = e.message
                )
                statusMessage.value = "File write error: ${e.message}"
            }
        }
    }

    private fun finishFileTransfer(totalBytes: Long) {
        try {
            outputStream?.flush()
            outputStream?.close()
            outputStream = null

            currentFileUri?.let { uri ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }
                    contentResolver.update(uri, values, null, null)
                }
            }

            val finishedState = transferState.value.copy(
                receivedBytes = totalBytes,
                isTransferring = false,
                isCompleted = true,
                uri = currentFileUri
            )

            runOnUiThread {
                serverState.value = ServerState.CONNECTED
                transferState.value = finishedState

                // Add to history list
                receivedFilesHistory.add(
                    0,
                    ReceivedFileHistory(
                        name = finishedState.fileName,
                        size = finishedState.fileSize,
                        mimeType = finishedState.mimeType,
                        uri = currentFileUri
                    )
                )

                statusMessage.value = "Received ${finishedState.fileName} successfully!"
            }

        } catch (e: Exception) {
            outputStream?.close()
            outputStream = null

            runOnUiThread {
                serverState.value = ServerState.ERROR
                transferState.value = transferState.value.copy(
                    isTransferring = false,
                    isError = true,
                    errorMessage = e.message
                )
                statusMessage.value = "Failed to finalize file: ${e.message}"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBluetooth()
    }
}