package com.example.syncdrop

import android.net.Uri

enum class ServerState {
    INITIALIZING,
    ADVERTISING,
    CONNECTED,
    TRANSFERRING,
    BLUETOOTH_OFF,
    PERMISSION_REQUIRED,
    ERROR
}

data class TransferState(
    val fileName: String = "",
    val fileSize: Long = 0L,
    val receivedBytes: Long = 0L,
    val mimeType: String = "application/octet-stream",
    val uri: Uri? = null,
    val isTransferring: Boolean = false,
    val isCompleted: Boolean = false,
    val isError: Boolean = false,
    val errorMessage: String? = null,
    val startTime: Long = 0L,
    val speedBps: Long = 0L
) {
    val progress: Float
        get() = if (fileSize > 0) (receivedBytes.toFloat() / fileSize.toFloat()).coerceIn(0f, 1f) else 0f

    val progressPercent: Int
        get() = (progress * 100).toInt()
}

data class ReceivedMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val senderName: String = "Web Client"
)

data class ReceivedFileHistory(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val size: Long,
    val mimeType: String,
    val uri: Uri?,
    val timestamp: Long = System.currentTimeMillis()
)
