import socket

BLUETOOTH_ADDRESS = "50:2E:91:5C:09:E9"
RFCOMM_CHANNEL = 3

server = socket.socket(
    socket.AF_BLUETOOTH,
    socket.SOCK_STREAM,
    socket.BTPROTO_RFCOMM,
)

server.bind((BLUETOOTH_ADDRESS, RFCOMM_CHANNEL))
server.listen(1)

print("Bluetooth RFCOMM server started.")
print(f"Address: {BLUETOOTH_ADDRESS}")
print(f"RFCOMM channel: {RFCOMM_CHANNEL}")
print("Waiting for connection...")

client, address = server.accept()

print(f"Connected to: {address}")

try:
    while True:
        data = client.recv(1024)

        if not data:
            break

        print("Received:", data.decode(errors="replace"))

        client.sendall(b"Hello from SyncDrop server!")

finally:
    client.close()
    server.close()
    print("Connection closed.")