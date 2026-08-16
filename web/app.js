const connectBtn = document.getElementById("connectBtn");

const status = document.getElementById("status");

const deviceInfo = document.getElementById("deviceInfo");
const deviceName = document.getElementById("deviceName");
const connectionStatus =
    document.getElementById("connectionStatus");

const sendBtn =
    document.getElementById("sendBtn");

const messageInput =
    document.getElementById("messageInput");

const messageControls =
    document.getElementById("messageControls");

const fileControls =
    document.getElementById("fileControls");

const fileInput =
    document.getElementById("fileInput");

const sendFileBtn =
    document.getElementById("sendFileBtn");

const fileStatus =
    document.getElementById("fileStatus");

let device = null;
let server = null;
let messageCharacteristic = null;
let fileCharacteristic = null;

// Must match Android
const SERVICE_UUID =
    "19b10000-e8f2-537e-4f6c-d104768a1214";

const MESSAGE_UUID =
    "19b10001-e8f2-537e-4f6c-d104768a1214";

const FILE_UUID =
    "19b10002-e8f2-537e-4f6c-d104768a1214";

const CHUNK_SIZE = 512; 


// Connect button
connectBtn.addEventListener(
    "click",
    connectBluetooth
);


// Send button
sendBtn.addEventListener(
    "click",
    async () => {

        const message =
            messageInput.value.trim();

        if (!message) {
            return;
        }

        await sendMessage(message);
    }
);

sendFileBtn.addEventListener(
    "click",
    sendFile
);

const testChunkBtn =
    document.getElementById("testChunkBtn");

testChunkBtn.addEventListener(
    "click",
    sendTestChunk
);


async function connectBluetooth() {

    if (!navigator.bluetooth) {

        status.textContent =
            "Web Bluetooth is not supported.";

        return;
    }

    try {

        status.textContent =
            "Searching for SyncDrop devices...";


        // Ask Chrome for a SyncDrop device
        device =
            await navigator.bluetooth.requestDevice({

                filters: [
                    {
                        services: [SERVICE_UUID]
                    }
                ]

            });


        deviceName.textContent =
            device.name || "Unknown device";

        deviceInfo.classList.remove("hidden");


        status.textContent =
            "Connecting...";


        // Connect to GATT server
        server =
            await device.gatt.connect();


        connectionStatus.textContent =
            "Connected";


        status.textContent =
            "Connected to SyncDrop.";


        // Find SyncDrop service
        const service =
            await server.getPrimaryService(
                SERVICE_UUID
            );


        // Find message characteristic
        messageCharacteristic =
            await service.getCharacteristic(
                MESSAGE_UUID
            );
        
        fileCharacteristic =
            await service.getCharacteristic(
                FILE_UUID
            );

        console.log(
            "File characteristic:",
            fileCharacteristic
        );

        console.log(
            "Message characteristic:",
            messageCharacteristic
        );


        // Show message controls
        messageControls.classList.remove(
            "hidden"
        );

        fileControls.classList.remove(
            "hidden"
        );


        // Read message from Android
        await readMessage();


        device.addEventListener(
            "gattserverdisconnected",
            handleDisconnect
        );


    } catch (error) {

        console.error(error);

        status.textContent =
            "Connection failed: " +
            error.message;
    }
}


async function readMessage() {

    try {

        const value =
            await messageCharacteristic.readValue();


        const decoder =
            new TextDecoder("utf-8");


        const message =
            decoder.decode(value);


        console.log(
            "Message from Android:",
            message
        );


        status.textContent =
            "Android says: " + message;


    } catch (error) {

        console.error(error);

        status.textContent =
            "Failed to read message: " +
            error.message;
    }
}


async function sendMessage(message) {

    try {

        const encoder =
            new TextEncoder();


        const data =
            encoder.encode(message);


        await messageCharacteristic.writeValue(
            data
        );


        console.log(
            "Sent:",
            message
        );


        status.textContent =
            "Sent: " + message;


        // Clear input
        messageInput.value = "";


    } catch (error) {

        console.error(error);

        status.textContent =
            "Failed to send message: " +
            error.message;
    }
}

async function sendTestChunk() {

    if (!fileCharacteristic) {
        console.error("File characteristic not available.");
        return;
    }

    try {

        const testData = new TextEncoder().encode(
            "Hello file chunk!"
        );

        await fileCharacteristic.writeValue(
            testData
        );

        console.log(
            "Test chunk sent:",
            testData.length,
            "bytes"
        );

        fileStatus.textContent =
            "Test chunk sent successfully.";

    } catch (error) {

        console.error(error);

        fileStatus.textContent =
            "Failed to send test chunk: " +
            error.message;
    }
}

async function sendFile() {

    const file = fileInput.files[0];

    if (!file) {
        fileStatus.textContent =
            "Please choose a file.";
        return;
    }

    if (!messageCharacteristic || !fileCharacteristic) {
        fileStatus.textContent =
            "Not connected to SyncDrop.";
        return;
    }

    try {

        // -----------------------------
        // 1. Send file metadata
        // -----------------------------

        const metadata = {
            type: "file",
            name: file.name,
            size: file.size,
            mime: file.type || "application/octet-stream"
        };

        const metadataString =
            JSON.stringify(metadata);

        const encoder =
            new TextEncoder();

        await messageCharacteristic.writeValue(
            encoder.encode(metadataString)
        );

        console.log(
            "File metadata sent:",
            metadataString
        );


        // -----------------------------
        // 2. Read file
        // -----------------------------

        const buffer =
            await file.arrayBuffer();

        const bytes =
            new Uint8Array(buffer);


        // -----------------------------
        // 3. Send chunks
        // -----------------------------

        let offset = 0;

        let chunkNumber = 0;

        while (offset < bytes.length) {

            const end =
                Math.min(
                    offset + CHUNK_SIZE,
                    bytes.length
                );

            const chunk =
                bytes.slice(offset, end);


            await fileCharacteristic.writeValue(
                chunk
            );


            offset = end;

            chunkNumber++;


            const progress =
                Math.round(
                    (offset / bytes.length) * 100
                );


            fileStatus.textContent =
                `Sending ${file.name}: ${progress}%`;


            console.log(
                `Chunk ${chunkNumber}:`,
                chunk.length,
                "bytes"
            );
        }


        // -----------------------------
        // 4. Done
        // -----------------------------

        fileStatus.textContent =
            `File sent: ${file.name}`;

        console.log(
            "File transfer complete."
        );

    } catch (error) {

        console.error(error);

        fileStatus.textContent =
            "File transfer failed: " +
            error.message;
    }
}


function handleDisconnect() {

    connectionStatus.textContent =
        "Disconnected";


    status.textContent =
        "SyncDrop disconnected.";
}