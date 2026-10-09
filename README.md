# SyncDrop

SyncDrop is a peer-to-peer file-sharing project with an Android BLE GATT
server, a Web Bluetooth client, and a Python asyncio transport prototype.

## Features

- Android app advertises a SyncDrop BLE service.
- Browser client discovers the Android app with Web Bluetooth.
- Text messages can be written to and read from the Android device.
- Files are transferred in chunks through the BLE file characteristic.
- Python peers can transfer files over the asyncio stream protocol.
- Received Python transfers are saved under `received/` and verified with
	SHA-256 checksums.

## Repository Layout

```text
android/       Android application written in Kotlin and Jetpack Compose
bluetooth/     Python peer-to-peer protocol and file-transfer prototype
web/           Web Bluetooth client
received/      Default destination for files received by Python
Jenkinsfile    CI pipeline for web checks and the Android debug build
```

## Android App

### Requirements

- Android Studio or a JDK/Android SDK installation
- Android SDK platform 37 and build tools configured for Gradle
- An Android device with Bluetooth Low Energy support

Build the debug APK from the repository root:

```bash
cd android
./gradlew assembleDebug
```

The APK is generated at
`android/app/build/outputs/apk/debug/app-debug.apk`. Install it on an Android
device, enable Bluetooth, and grant the requested Bluetooth permissions. The
app then starts advertising the SyncDrop GATT service.

## Web Bluetooth Client

Web Bluetooth is supported by Chromium-based browsers such as Chrome and
Edge. Open `web/index.html` from a secure context, such as `localhost` or an
HTTPS origin. For a quick local server:

```bash
cd web
python3 -m http.server 8000
```

Visit <http://localhost:8000> in the browser, click **Connect Bluetooth
Device**, and select the Android device. After connecting, the page exposes
message and file-transfer controls.

The web client and Android app must use these matching UUIDs:

| Service | UUID |
| --- | --- |
| SyncDrop service | `19b10000-e8f2-537e-4f6c-d104768a1214` |
| Message characteristic | `19b10001-e8f2-537e-4f6c-d104768a1214` |
| File characteristic | `19b10002-e8f2-537e-4f6c-d104768a1214` |

## Python File-Transfer Prototype

Run commands from the repository root so the `bluetooth` package imports
resolve correctly. Python 3.10 or newer is recommended.

Start the server:

```bash
python3 -m bluetooth.server
```

From another machine on the same network, send a file to the server:

```bash
python3 -m bluetooth.client <SERVER_IP> <FILE_PATH>
```

The prototype listens on TCP port `8765` and writes received files to
`received/`. The client can also be run without a file to verify the handshake:

```bash
python3 -m bluetooth.client <SERVER_IP>
```

## Protocol

Python messages use a 4-byte network-order length prefix followed by a UTF-8
JSON object. A file transfer follows this sequence:

```text
HELLO -> HELLO_ACK
FILE_REQUEST -> FILE_RESPONSE
length-prefixed file chunks
TRANSFER_COMPLETE
```

The receiver sanitizes filenames, avoids overwriting existing files, and
checks the final SHA-256 digest.

## Testing

Run the Python unit tests from the repository root:

```bash
python3 -m unittest discover
```

Build the Android application with:

```bash
cd android
./gradlew test assembleDebug
```

## Current Status

The Android/Web Bluetooth BLE flow and Python asyncio stream flow are separate
prototypes. The Python server currently uses TCP streams on port `8765`; it is
not yet a native Bluetooth RFCOMM transport. The files in `bluetooth/` contain
the shared protocol and transfer logic intended to support that future
transport.
