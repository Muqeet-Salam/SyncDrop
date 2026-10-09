// ==========================================================================
// SyncDrop Web Client Application
// BLE Peer-to-Peer File Sharing & Communication
// ==========================================================================

// Core Bluetooth UUIDs - Must match Android GATT Server
const SERVICE_UUID = "19b10000-e8f2-537e-4f6c-d104768a1214";
const MESSAGE_UUID = "19b10001-e8f2-537e-4f6c-d104768a1214";
const FILE_UUID = "19b10002-e8f2-537e-4f6c-d104768a1214";
const CHUNK_SIZE = 512; // 512-byte payload per GATT chunk

// DOM Elements
const connectBtn = document.getElementById("connectBtn");
const disconnectBtn = document.getElementById("disconnectBtn");
const status = document.getElementById("status");
const connectionBadge = document.getElementById("connectionBadge");
const badgeText = document.getElementById("badgeText");
const gattStatusTag = document.getElementById("gattStatusTag");

const deviceInfo = document.getElementById("deviceInfo");
const deviceName = document.getElementById("deviceName");
const connectionStatus = document.getElementById("connectionStatus");

const messageControls = document.getElementById("messageControls");
const messageInput = document.getElementById("messageInput");
const sendBtn = document.getElementById("sendBtn");
const chatFeed = document.getElementById("chatFeed");
const chatEmptyState = document.getElementById("chatEmptyState");
const msgCountBadge = document.getElementById("msgCountBadge");

const fileControls = document.getElementById("fileControls");
const fileInput = document.getElementById("fileInput");
const sendFileBtn = document.getElementById("sendFileBtn");
const cancelTransferBtn = document.getElementById("cancelTransferBtn");
const fileStatus = document.getElementById("fileStatus");
const dropZone = document.getElementById("dropZone");
const filePreviewCard = document.getElementById("filePreviewCard");
const selectedFileName = document.getElementById("selectedFileName");
const selectedFileSize = document.getElementById("selectedFileSize");
const selectedFileType = document.getElementById("selectedFileType");
const clearFileBtn = document.getElementById("clearFileBtn");
const testChunkBtn = document.getElementById("testChunkBtn");
const pingBtn = document.getElementById("pingBtn");

const transferProgressBox = document.getElementById("transferProgressBox");
const transferPhaseLabel = document.getElementById("transferPhaseLabel");
const transferPercent = document.getElementById("transferPercent");
const progressBarFill = document.getElementById("progressBarFill");
const metricBytes = document.getElementById("metricBytes");
const metricSpeed = document.getElementById("metricSpeed");
const metricChunks = document.getElementById("metricChunks");
const metricEta = document.getElementById("metricEta");

const logStream = document.getElementById("logStream");
const activityCount = document.getElementById("activityCount");
const clearLogBtn = document.getElementById("clearLogBtn");
const toastContainer = document.getElementById("toastContainer");
const themeToggleBtn = document.getElementById("themeToggleBtn");

// Application State
let device = null;
let server = null;
let messageCharacteristic = null;
let fileCharacteristic = null;
let selectedFile = null;
let isTransferring = false;
let transferCancelled = false;
let messageCount = 0;
let totalLogEvents = 1;

// Sound Effects via Web Audio API
const audioCtx = window.AudioContext ? new (window.AudioContext || window.webkitAudioContext)() : null;

function playTone(type) {
    if (!audioCtx) return;
    try {
        if (audioCtx.state === 'suspended') audioCtx.resume();
        const osc = audioCtx.createOscillator();
        const gain = audioCtx.createGain();
        osc.connect(gain);
        gain.connect(audioCtx.destination);
        const now = audioCtx.currentTime;

        if (type === 'connect') {
            osc.frequency.setValueAtTime(440, now);
            osc.frequency.exponentialRampToValueAtTime(880, now + 0.15);
            gain.gain.setValueAtTime(0.12, now);
            gain.gain.exponentialRampToValueAtTime(0.01, now + 0.2);
            osc.start(now);
            osc.stop(now + 0.2);
        } else if (type === 'complete') {
            osc.frequency.setValueAtTime(523.25, now);
            osc.frequency.setValueAtTime(659.25, now + 0.08);
            osc.frequency.setValueAtTime(783.99, now + 0.16);
            gain.gain.setValueAtTime(0.15, now);
            gain.gain.exponentialRampToValueAtTime(0.01, now + 0.35);
            osc.start(now);
            osc.stop(now + 0.35);
        } else if (type === 'send') {
            osc.frequency.setValueAtTime(700, now);
            gain.gain.setValueAtTime(0.08, now);
            gain.gain.exponentialRampToValueAtTime(0.01, now + 0.1);
            osc.start(now);
            osc.stop(now + 0.1);
        } else if (type === 'error') {
            osc.frequency.setValueAtTime(220, now);
            osc.frequency.setValueAtTime(160, now + 0.1);
            gain.gain.setValueAtTime(0.2, now);
            gain.gain.exponentialRampToValueAtTime(0.01, now + 0.25);
            osc.start(now);
            osc.stop(now + 0.25);
        }
    } catch (e) {
        console.warn("Audio feedback error:", e);
    }
}

// Initialize Lucide Icons
function refreshIcons() {
    if (window.lucide) {
        window.lucide.createIcons();
    }
}

// Log to Activity Log stream
function appendLog(category, message) {
    const timeStr = new Date().toTimeString().split(" ")[0];
    const logItem = document.createElement("div");
    logItem.className = "log-item";

    let tagClass = "tag-sys";
    let tagText = "SYS";
    if (category === "ble") { tagClass = "tag-ble"; tagText = "BLE"; }
    else if (category === "file") { tagClass = "tag-file"; tagText = "FILE"; }
    else if (category === "ok") { tagClass = "tag-ok"; tagText = "OK"; }
    else if (category === "err") { tagClass = "tag-err"; tagText = "ERR"; }

    logItem.innerHTML = `
        <span class="log-time">${timeStr}</span>
        <span class="log-tag ${tagClass}">${tagText}</span>
        <span class="log-msg">${escapeHtml(message)}</span>
    `;

    logStream.appendChild(logItem);
    logStream.scrollTop = logStream.scrollHeight;
    totalLogEvents++;
    if (activityCount) {
        activityCount.textContent = `${totalLogEvents} events logged`;
    }
}

// Show Toast Notification
function showToast(message, type = "info") {
    const toast = document.createElement("div");
    toast.className = `toast toast-${type}`;
    let iconName = "info";
    if (type === "success") iconName = "check-circle-2";
    if (type === "error") iconName = "alert-triangle";

    toast.innerHTML = `
        <i data-lucide="${iconName}" class="mini-icon"></i>
        <span>${escapeHtml(message)}</span>
    `;

    toastContainer.appendChild(toast);
    refreshIcons();

    setTimeout(() => {
        toast.style.opacity = "0";
        toast.style.transform = "translateY(10px)";
        setTimeout(() => toast.remove(), 300);
    }, 4000);
}

// Update Status Message and UI Badge
function updateStatus(msg, state = "normal") {
    const contentSpan = status.querySelector(".status-text-content");
    if (contentSpan) {
        contentSpan.textContent = msg;
    } else {
        status.textContent = msg;
    }

    if (state === "connecting") {
        connectionBadge.className = "connection-badge status-connecting";
        badgeText.textContent = "Connecting...";
    } else if (state === "connected") {
        connectionBadge.className = "connection-badge status-connected";
        badgeText.textContent = "Connected";
    } else if (state === "disconnected") {
        connectionBadge.className = "connection-badge status-disconnected";
        badgeText.textContent = "Disconnected";
    }
}

// Format bytes helper
function formatBytes(bytes, decimals = 1) {
    if (bytes === 0) return '0 Bytes';
    const k = 1024;
    const dm = decimals < 0 ? 0 : decimals;
    const sizes = ['Bytes', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(dm)) + ' ' + sizes[i];
}

function escapeHtml(str) {
    if (!str) return "";
    return str.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

// ==========================================================================
// Bluetooth Connection Lifecycle
// ==========================================================================

connectBtn.addEventListener("click", connectBluetooth);
if (disconnectBtn) {
    disconnectBtn.addEventListener("click", disconnectBluetooth);
}

async function connectBluetooth() {
    if (!navigator.bluetooth) {
        updateStatus("Web Bluetooth is not supported in this browser. Please use Chrome, Edge, or Opera over HTTPS/localhost.", "disconnected");
        appendLog("err", "Web Bluetooth API unavailable in this browser.");
        showToast("Web Bluetooth API not supported", "error");
        return;
    }

    try {
        updateStatus("Requesting SyncDrop BLE device...", "connecting");
        appendLog("ble", "Scanning for GATT Service UUID " + SERVICE_UUID);

        // Request device with SyncDrop Service UUID
        device = await navigator.bluetooth.requestDevice({
            filters: [
                {
                    services: [SERVICE_UUID]
                }
            ]
        });

        const name = device.name || "SyncDrop Android";
        deviceName.textContent = name;
        deviceInfo.classList.remove("hidden");
        connectBtn.classList.add("hidden");
        if (disconnectBtn) disconnectBtn.classList.remove("hidden");

        updateStatus(`Connecting to GATT server on ${name}...`, "connecting");
        appendLog("ble", `Found device: ${name}. Initiating GATT connection...`);

        // Connect GATT Server
        server = await device.gatt.connect();
        connectionStatus.textContent = "Connected";
        connectionStatus.className = "detail-value status-pill online";
        updateStatus(`Connected to ${name}`, "connected");
        gattStatusTag.textContent = "GATT Active";
        gattStatusTag.style.color = "var(--accent-emerald)";

        // Get SyncDrop Service
        appendLog("ble", "Discovering primary service...");
        const service = await server.getPrimaryService(SERVICE_UUID);

        // Get Characteristics
        messageCharacteristic = await service.getCharacteristic(MESSAGE_UUID);
        fileCharacteristic = await service.getCharacteristic(FILE_UUID);

        appendLog("ok", "Discovered Message & File characteristics successfully.");
        playTone("connect");
        showToast(`Connected to ${name}!`, "success");

        // Show Controls
        messageControls.classList.remove("hidden");
        fileControls.classList.remove("hidden");

        // Read initial greeting if available
        await readMessage();

        // Listen for disconnect
        device.addEventListener("gattserverdisconnected", handleDisconnect);

    } catch (error) {
        console.error("Bluetooth connection error:", error);
        updateStatus("Connection failed: " + error.message, "disconnected");
        appendLog("err", "Connection failed: " + error.message);
        playTone("error");
        showToast(error.message, "error");
        resetConnectionUI();
    }
}

function disconnectBluetooth() {
    if (device && device.gatt && device.gatt.connected) {
        appendLog("ble", "User requested disconnection.");
        device.gatt.disconnect();
    } else {
        handleDisconnect();
    }
}

function handleDisconnect() {
    connectionStatus.textContent = "Disconnected";
    connectionStatus.className = "detail-value status-pill";
    updateStatus("SyncDrop disconnected.", "disconnected");
    gattStatusTag.textContent = "GATT Idle";
    gattStatusTag.style.color = "var(--text-muted)";
    appendLog("ble", "Device disconnected.");
    playTone("error");
    showToast("Bluetooth device disconnected", "info");
    resetConnectionUI();
}

function resetConnectionUI() {
    connectBtn.classList.remove("hidden");
    if (disconnectBtn) disconnectBtn.classList.add("hidden");
}

// ==========================================================================
// Messaging & Chat System
// ==========================================================================

sendBtn.addEventListener("click", async () => {
    const message = messageInput.value.trim();
    if (!message) return;
    await sendMessage(message);
});

messageInput.addEventListener("keydown", async (e) => {
    if (e.key === "Enter" && !e.shiftKey) {
        e.preventDefault();
        const message = messageInput.value.trim();
        if (message) await sendMessage(message);
    }
});

async function sendMessage(message) {
    if (!messageCharacteristic) {
        showToast("Please connect to a device first", "error");
        return;
    }

    try {
        const encoder = new TextEncoder();
        const data = encoder.encode(message);
        await messageCharacteristic.writeValue(data);

        addChatMessage(message, "sent");
        messageInput.value = "";
        playTone("send");
        appendLog("ble", `Sent message: "${message.substring(0, 40)}${message.length > 40 ? '...' : ''}"`);
        updateStatus("Message sent to Android.", "connected");
    } catch (error) {
        console.error("Send message error:", error);
        appendLog("err", "Failed to send message: " + error.message);
        showToast("Failed to send: " + error.message, "error");
        playTone("error");
    }
}

async function readMessage() {
    if (!messageCharacteristic) return;
    try {
        const value = await messageCharacteristic.readValue();
        const decoder = new TextDecoder("utf-8");
        const message = decoder.decode(value);

        if (message && message.trim().length > 0) {
            appendLog("ble", `Received from Android: "${message}"`);
            addChatMessage(message, "received");
        }
    } catch (error) {
        console.warn("Read message notice:", error.message);
    }
}

function addChatMessage(text, direction = "sent") {
    if (chatEmptyState) chatEmptyState.classList.add("hidden");

    const bubble = document.createElement("div");
    bubble.className = `chat-bubble ${direction}`;
    const timeStr = new Date().toTimeString().split(" ")[0].substring(0, 5);

    bubble.innerHTML = `
        <div class="bubble-text">${escapeHtml(text)}</div>
        <div class="bubble-meta">
            <span>${direction === 'sent' ? 'You' : (deviceName.textContent || 'Android')}</span>
            <span>•</span>
            <span>${timeStr}</span>
        </div>
    `;

    chatFeed.appendChild(bubble);
    chatFeed.scrollTop = chatFeed.scrollHeight;

    messageCount++;
    if (msgCountBadge) {
        msgCountBadge.textContent = messageCount;
        msgCountBadge.classList.remove("hidden");
    }
}

// ==========================================================================
// File Management & Drag and Drop
// ==========================================================================

dropZone.addEventListener("click", () => fileInput.click());

dropZone.addEventListener("dragover", (e) => {
    e.preventDefault();
    e.stopPropagation();
    dropZone.classList.add("drag-active");
});

dropZone.addEventListener("dragleave", (e) => {
    e.preventDefault();
    e.stopPropagation();
    dropZone.classList.remove("drag-active");
});

dropZone.addEventListener("drop", (e) => {
    e.preventDefault();
    e.stopPropagation();
    dropZone.classList.remove("drag-active");

    if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
        handleFileSelected(e.dataTransfer.files[0]);
    }
});

fileInput.addEventListener("change", (e) => {
    if (e.target.files && e.target.files.length > 0) {
        handleFileSelected(e.target.files[0]);
    }
});

clearFileBtn.addEventListener("click", (e) => {
    e.stopPropagation();
    clearSelectedFile();
});

function handleFileSelected(file) {
    selectedFile = file;
    selectedFileName.textContent = file.name;
    selectedFileSize.textContent = formatBytes(file.size);
    selectedFileType.textContent = file.type ? file.type.split("/")[1] || file.type : "binary";

    dropZone.classList.add("hidden");
    filePreviewCard.classList.remove("hidden");
    fileStatus.textContent = `Ready to send: ${file.name} (${formatBytes(file.size)})`;
    appendLog("file", `Selected file: ${file.name} (${formatBytes(file.size)})`);
    refreshIcons();
}

function clearSelectedFile() {
    selectedFile = null;
    fileInput.value = "";
    dropZone.classList.remove("hidden");
    filePreviewCard.classList.add("hidden");
    fileStatus.textContent = "";
}

// Send File Flow
sendFileBtn.addEventListener("click", sendFile);
if (cancelTransferBtn) {
    cancelTransferBtn.addEventListener("click", () => {
        transferCancelled = true;
        appendLog("file", "Transfer cancelled by user.");
    });
}

async function sendFile() {
    const file = selectedFile || (fileInput.files ? fileInput.files[0] : null);

    if (!file) {
        fileStatus.textContent = "Please select or drag a file to send.";
        showToast("Please choose a file first", "info");
        return;
    }

    if (!messageCharacteristic || !fileCharacteristic) {
        fileStatus.textContent = "Not connected to SyncDrop device.";
        showToast("Device not connected via Bluetooth", "error");
        return;
    }

    if (isTransferring) return;

    try {
        isTransferring = true;
        transferCancelled = false;
        sendFileBtn.classList.add("hidden");
        if (cancelTransferBtn) cancelTransferBtn.classList.remove("hidden");
        transferProgressBox.classList.remove("hidden");

        // 1. Send File Metadata over Message Characteristic
        transferPhaseLabel.textContent = "Sending File Metadata...";
        progressBarFill.style.width = "2%";
        transferPercent.textContent = "0%";

        const metadata = {
            type: "file",
            name: file.name,
            size: file.size,
            mime: file.type || "application/octet-stream"
        };

        const metadataString = JSON.stringify(metadata);
        const encoder = new TextEncoder();
        await messageCharacteristic.writeValue(encoder.encode(metadataString));

        appendLog("file", `Sent metadata for ${file.name} (${file.size} bytes)`);

        // Small pause to allow Android MediaStore buffer preparation
        await new Promise(r => setTimeout(r, 80));

        // 2. Read file as ArrayBuffer & Stream Chunks
        transferPhaseLabel.textContent = "Streaming Chunks via BLE...";
        const buffer = await file.arrayBuffer();
        const bytes = new Uint8Array(buffer);
        const totalChunks = Math.ceil(bytes.length / CHUNK_SIZE);

        let offset = 0;
        let chunkNumber = 0;
        const startTime = Date.now();

        while (offset < bytes.length) {
            if (transferCancelled) {
                throw new Error("Transfer cancelled by user.");
            }

            const end = Math.min(offset + CHUNK_SIZE, bytes.length);
            const chunk = bytes.slice(offset, end);

            // Write chunk to file characteristic
            await fileCharacteristic.writeValue(chunk);

            offset = end;
            chunkNumber++;

            // Calculate progress & metrics
            const progress = Math.round((offset / bytes.length) * 100);
            const elapsedSec = (Date.now() - startTime) / 1000;
            const speedBps = elapsedSec > 0 ? (offset / elapsedSec) : 0;
            const remainingBytes = bytes.length - offset;
            const etaSec = speedBps > 0 ? Math.round(remainingBytes / speedBps) : 0;
            const etaFormatted = `${Math.floor(etaSec / 60).toString().padStart(2, '0')}:${(etaSec % 60).toString().padStart(2, '0')}`;

            // Update UI
            progressBarFill.style.width = `${progress}%`;
            transferPercent.textContent = `${progress}%`;
            metricBytes.textContent = `${formatBytes(offset)} / ${formatBytes(bytes.length)}`;
            metricSpeed.textContent = `${formatBytes(speedBps)}/s`;
            metricChunks.textContent = `${chunkNumber} / ${totalChunks}`;
            metricEta.textContent = etaFormatted;
            fileStatus.textContent = `Streaming ${file.name} (${progress}%)`;
        }

        // 3. Complete
        transferPhaseLabel.textContent = "Transfer Completed!";
        progressBarFill.style.width = "100%";
        transferPercent.textContent = "100%";
        fileStatus.textContent = `File successfully sent: ${file.name}`;
        appendLog("ok", `Completed file transfer for ${file.name} (${formatBytes(file.size)})`);
        playTone("complete");
        showToast(`Sent ${file.name} successfully!`, "success");

        setTimeout(() => {
            transferProgressBox.classList.add("hidden");
            clearSelectedFile();
        }, 4000);

    } catch (error) {
        console.error("File transfer error:", error);
        transferPhaseLabel.textContent = "Transfer Failed";
        fileStatus.textContent = "Transfer error: " + error.message;
        appendLog("err", `File transfer failed: ${error.message}`);
        playTone("error");
        showToast(`Transfer failed: ${error.message}`, "error");
    } finally {
        isTransferring = false;
        sendFileBtn.classList.remove("hidden");
        if (cancelTransferBtn) cancelTransferBtn.classList.add("hidden");
    }
}

// ==========================================================================
// Diagnostic Tools
// ==========================================================================

testChunkBtn.addEventListener("click", sendTestChunk);

async function sendTestChunk() {
    if (!fileCharacteristic) {
        showToast("Connect to a Bluetooth device first", "error");
        return;
    }

    try {
        const testData = new TextEncoder().encode("Hello file chunk!");
        await fileCharacteristic.writeValue(testData);
        appendLog("ok", `Test packet sent (${testData.length} bytes) to File Characteristic`);
        showToast("Test packet sent successfully!", "success");
        playTone("send");
    } catch (error) {
        console.error("Test chunk error:", error);
        appendLog("err", "Test packet failed: " + error.message);
        showToast("Test packet failed: " + error.message, "error");
        playTone("error");
    }
}

if (pingBtn) {
    pingBtn.addEventListener("click", async () => {
        if (!messageCharacteristic) {
            showToast("Connect to a device first", "error");
            return;
        }
        await readMessage();
        showToast("Read response requested from device", "info");
    });
}

// ==========================================================================
// Tabs & UI Navigation
// ==========================================================================

document.querySelectorAll(".tab-btn").forEach(btn => {
    btn.addEventListener("click", () => {
        document.querySelectorAll(".tab-btn").forEach(b => b.classList.remove("active"));
        document.querySelectorAll(".tab-pane").forEach(p => p.classList.add("hidden"));

        btn.classList.add("active");
        const targetTab = btn.getAttribute("data-tab");
        const pane = document.getElementById(targetTab);
        if (pane) pane.classList.remove("hidden");

        if (targetTab === "messagesTab" && msgCountBadge) {
            msgCountBadge.classList.add("hidden");
            messageCount = 0;
        }
    });
});

if (clearLogBtn) {
    clearLogBtn.addEventListener("click", () => {
        logStream.innerHTML = "";
        totalLogEvents = 0;
        activityCount.textContent = "0 events logged";
        appendLog("sys", "Log cleared.");
    });
}

// ==========================================================================
// Theme Management
// ==========================================================================

function initTheme() {
    const savedTheme = localStorage.getItem("syncdrop-theme") || "dark";
    if (savedTheme === "light") {
        document.documentElement.setAttribute("data-theme", "light");
    } else {
        document.documentElement.removeAttribute("data-theme");
    }
}

if (themeToggleBtn) {
    themeToggleBtn.addEventListener("click", () => {
        const current = document.documentElement.getAttribute("data-theme");
        if (current === "light") {
            document.documentElement.removeAttribute("data-theme");
            localStorage.setItem("syncdrop-theme", "dark");
        } else {
            document.documentElement.setAttribute("data-theme", "light");
            localStorage.setItem("syncdrop-theme", "light");
        }
    });
}

// Initialize on Load
window.addEventListener("DOMContentLoaded", () => {
    initTheme();
    refreshIcons();
    const initTimeEl = document.getElementById("initTime");
    if (initTimeEl) {
        initTimeEl.textContent = new Date().toTimeString().split(" ")[0];
    }
});