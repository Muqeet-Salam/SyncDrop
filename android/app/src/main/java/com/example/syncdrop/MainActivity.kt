package com.example.syncdrop

import android.Manifest
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
import android.bluetooth.BluetoothAdapter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.ParcelUuid
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import java.util.UUID
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore

class MainActivity : ComponentActivity() {

    companion object {

        private const val REQUEST_BLUETOOTH = 100

        private val SERVICE_UUID =
            UUID.fromString(
                "19b10000-e8f2-537e-4f6c-d104768a1214"
            )

        private val MESSAGE_UUID =
            UUID.fromString(
                "19b10001-e8f2-537e-4f6c-d104768a1214"
            )

        private val FILE_UUID =
            UUID.fromString(
                "19b10002-e8f2-537e-4f6c-d104768a1214"
            )
    }

    private lateinit var bluetoothAdapter: BluetoothAdapter

    private var advertiser: BluetoothLeAdvertiser? = null

    private var gattServer: BluetoothGattServer? = null

    private var receivingFile = false
    private var receivedFileName = ""
    private var receivedFileSize = 0L
    private var receivedBytes = 0L

    private var outputStream: java.io.OutputStream? = null

    private var receivedFileUri: android.net.Uri? = null

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        statusText = TextView(this).apply {

            text = "SyncDrop\nStarting..."

            textSize = 22f

            setPadding(
                40,
                100,
                40,
                40
            )
        }

        setContentView(statusText)

        requestBluetoothPermissions()
    }

    private fun requestBluetoothPermissions() {

        if (
            android.os.Build.VERSION.SDK_INT >=
            android.os.Build.VERSION_CODES.S
        ) {

            if (
                ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_ADVERTISE
                ) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {

                ActivityCompat.requestPermissions(
                    this,

                    arrayOf(
                        Manifest.permission.BLUETOOTH_ADVERTISE,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ),

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

        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == REQUEST_BLUETOOTH) {

            if (
                grantResults.isNotEmpty() &&
                grantResults.all {
                    it == PackageManager.PERMISSION_GRANTED
                }
            ) {

                startBluetooth()

            } else {

                statusText.text =
                    "Bluetooth permission denied."
            }
        }
    }

    private fun startBluetooth() {

        val bluetoothManager =
            getSystemService(
                BLUETOOTH_SERVICE
            ) as BluetoothManager

        bluetoothAdapter =
            bluetoothManager.adapter

        if (!bluetoothAdapter.isEnabled) {

            statusText.text =
                "Please turn Bluetooth ON."

            return
        }

        startGattServer()

        startAdvertising()
    }

    private fun startGattServer() {

        if (
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val bluetoothManager =
            getSystemService(
                BLUETOOTH_SERVICE
            ) as BluetoothManager

        gattServer =
            bluetoothManager.openGattServer(
                this,
                gattCallback
            )

        val service =
            BluetoothGattService(
                SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )

        val messageCharacteristic =
            BluetoothGattCharacteristic(
                MESSAGE_UUID,

                BluetoothGattCharacteristic.PROPERTY_READ or
                        BluetoothGattCharacteristic.PROPERTY_WRITE,

                BluetoothGattCharacteristic.PERMISSION_READ or
                        BluetoothGattCharacteristic.PERMISSION_WRITE
            )

        service.addCharacteristic(
            messageCharacteristic
        )

        val fileCharacteristic =
            BluetoothGattCharacteristic(
                FILE_UUID,

                BluetoothGattCharacteristic.PROPERTY_WRITE,

                BluetoothGattCharacteristic.PERMISSION_WRITE
            )

        service.addCharacteristic(
            fileCharacteristic
        )

        val added =
            gattServer?.addService(service)

        if (added == null) {

            statusText.text =
                "Failed to create GATT service."

            return
        }

        statusText.text =
            "SyncDrop\nGATT server started"
    }

    private val gattCallback =
        object : BluetoothGattServerCallback() {

            override fun onConnectionStateChange(
                device: android.bluetooth.BluetoothDevice?,
                status: Int,
                newState: Int
            ) {
                runOnUiThread {
                    statusText.text =
                        "Connection state changed\nstatus=$status\nstate=$newState"
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

                if (characteristic == null) {
                    return
                }

                val data = value ?: ByteArray(0)

                if (characteristic.uuid == MESSAGE_UUID) {

                    val message =
                        data.toString(Charsets.UTF_8)

                    if (
                        message.startsWith("{") &&
                        message.endsWith("}")
                    ) {

                        val json =
                            try {
                                org.json.JSONObject(message)
                            } catch (e: Exception) {
                                null
                            }

                        if (
                            json != null &&
                            json.optString("type") == "file"
                        ) {

                            startFileTransfer(message)

                        } else {

                            runOnUiThread {
                                statusText.text =
                                    "Received:\n$message"
                            }
                        }

                    } else {

                        runOnUiThread {
                            statusText.text =
                                "Received:\n$message"
                        }
                    }
                }

                else if (characteristic.uuid == FILE_UUID) {

                    receiveFileChunk(data)
                }

                if (responseNeeded && device != null) {

                    if (
                        ActivityCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        return
                    }

                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        null
                    )
                }
            }

            override fun onCharacteristicReadRequest(
                device: android.bluetooth.BluetoothDevice?,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic?
            ) {

                if (characteristic?.uuid != MESSAGE_UUID) {
                    return
                }

                if (device == null) {
                    return
                }

                if (
                    ActivityCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return
                }

                val message = "Hello from SyncDrop"

                val response =
                    message.toByteArray(Charsets.UTF_8)

                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    response
                )

                runOnUiThread {
                    statusText.text =
                        "Read request received"
                }
            }
        }

    private fun startAdvertising() {

        if (
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        advertiser =
            bluetoothAdapter.bluetoothLeAdvertiser

        if (advertiser == null) {

            statusText.text =
                "BLE advertising not supported."

            return
        }

        val settings =
            AdvertiseSettings.Builder()
                .setAdvertiseMode(
                    AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
                )
                .setTxPowerLevel(
                    AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
                )
                .setConnectable(true)
                .build()

        /*
         * Do NOT include the device name.
         *
         * We already discovered that the Redmi
         * rejected the larger advertisement.
         */
        val data =
            AdvertiseData.Builder()
                .addServiceUuid(
                    ParcelUuid(SERVICE_UUID)
                )
                .build()

        advertiser?.startAdvertising(
            settings,
            data,
            advertiseCallback
        )
    }

    private val advertiseCallback =
        object : AdvertiseCallback() {

            override fun onStartSuccess(
                settingsInEffect: AdvertiseSettings?
            ) {

                runOnUiThread {

                    statusText.text =
                        "SyncDrop\nBLE Advertising"
                }
            }

            override fun onStartFailure(
                errorCode: Int
            ) {

                runOnUiThread {

                    statusText.text =
                        "BLE advertising failed\nError: $errorCode"
                }
            }
        }

    override fun onDestroy() {

        super.onDestroy()

        if (
            android.os.Build.VERSION.SDK_INT <
            android.os.Build.VERSION_CODES.S ||
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ) == PackageManager.PERMISSION_GRANTED
        ) {

            advertiser?.stopAdvertising(
                advertiseCallback
            )
        }

        gattServer?.close()
    }

    private fun startFileTransfer(metadata: String) {

        try {

            val json =
                org.json.JSONObject(metadata)

            receivedFileName =
                json.getString("name")

            receivedFileSize =
                json.getLong("size")

            receivedBytes = 0L

            val values = ContentValues().apply {
            put(
                MediaStore.Downloads.DISPLAY_NAME,
                receivedFileName
            )

            put(
                MediaStore.Downloads.MIME_TYPE,
                json.optString(
                    "mime",
                    "application/octet-stream"
                )
            )

            put(
                MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS +
                        "/SyncDrop"
            )

            put(
                MediaStore.Downloads.IS_PENDING,
                1
            )
        }

        val resolver = contentResolver

        val uri =
            resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            )

            ?: throw Exception(
                "Could not create download file"
            )

        receivedFileUri = uri

        outputStream =
            contentResolver.openOutputStream(uri)
                ?: throw Exception(
                    "Could not open output stream"
                )

            receivingFile = true

            runOnUiThread {
                statusText.text =
                    "Receiving file:\n" +
                    "$receivedFileName\n" +
                    "0 / $receivedFileSize bytes"
            }

        } catch (e: Exception) {

            receivingFile = false
            outputStream = null

            runOnUiThread {
                statusText.text =
                    "Failed to start file:\n${e.message}"
            }
        }
    }

    private fun receiveFileChunk(
        data: ByteArray
    ) {

        if (!receivingFile) {
            return
        }

        try {

            outputStream?.write(data)

            receivedBytes += data.size

            if (receivedBytes >= receivedFileSize) {

                finishFileTransfer()

                return
            }

            runOnUiThread {

                statusText.text =
                    "Receiving file:\n" +
                    "$receivedFileName\n" +
                    "$receivedBytes / $receivedFileSize bytes"
            }

        } catch (e: Exception) {

            outputStream?.close()
            outputStream = null

            receivingFile = false

            runOnUiThread {
                statusText.text =
                    "File write failed:\n${e.message}"
            }
        }
    }

    private fun finishFileTransfer() {

        try {

            outputStream?.flush()
            outputStream?.close()

            outputStream = null

            receivedFileUri?.let { uri ->

                val values = ContentValues().apply {
                    put(
                        MediaStore.Downloads.IS_PENDING,
                        0
                    )
                }

                contentResolver.update(
                    uri,
                    values,
                    null,
                    null
                )
            }

            receivingFile = false

            runOnUiThread {

                statusText.text =
                    "File received!\n" +
                    "$receivedFileName\n" +
                    "$receivedBytes bytes\n" +
                    "Saved to Downloads/SyncDrop"
            }

        } catch (e: Exception) {

            outputStream?.close()
            outputStream = null

            receivingFile = false

            runOnUiThread {

                statusText.text =
                    "Failed to finish file:\n" +
                    e.message
            }
        }
    }
}