"""
Bluetooth P2P file-sharing server.

The transport layer is currently represented using asyncio streams.
Once the Windows Bluetooth transport is selected, the Bluetooth
connection can feed into the same protocol and file-transfer functions.
"""

import asyncio
from dataclasses import dataclass

from .file_transfer import receive_file
from .protocol import (
    ProtocolError,
    hello_response,
    receive_message,
    send_message,
)
from .utils import (
    generate_device_id,
    get_device_name,
)


HOST = "0.0.0.0"
PORT = 8765


DEVICE_ID = generate_device_id()
DEVICE_NAME = get_device_name()


@dataclass
class ConnectedPeer:
    device_id: str
    device_name: str
    reader: asyncio.StreamReader
    writer: asyncio.StreamWriter


connected_peers: dict[str, ConnectedPeer] = {}


async def handle_client(
    reader: asyncio.StreamReader,
    writer: asyncio.StreamWriter,
) -> None:
    """Handle one connected peer."""

    address = writer.get_extra_info("peername")

    print(f"\nNew connection: {address}")

    peer = None

    try:
        # First message must be HELLO.
        message = await receive_message(
            reader,
            writer,
        )

        if message.get("type") != "HELLO":
            raise ProtocolError(
                "First message must be HELLO."
            )

        remote_device_id = message.get("device_id")
        remote_device_name = message.get("device_name")

        if not remote_device_id:
            raise ProtocolError(
                "Missing device ID."
            )

        peer = ConnectedPeer(
            device_id=remote_device_id,
            device_name=remote_device_name or "Unknown",
            reader=reader,
            writer=writer,
        )

        connected_peers[remote_device_id] = peer

        print(
            f"Connected peer: "
            f"{peer.device_name} "
            f"({peer.device_id})"
        )

        # Send HELLO_ACK.
        await send_message(
            reader,
            writer,
            hello_response(
                DEVICE_ID,
                DEVICE_NAME,
            ),
        )

        print(
            f"Active peers: {len(connected_peers)}"
        )

        # Keep listening for requests.
        while True:

            message = await receive_message(
                reader,
                writer,
            )

            message_type = message.get("type")

            if message_type == "FILE_REQUEST":

                # We already have the FILE_REQUEST.
                # receive_file expects to read it itself, so
                # this request is handled inline instead.
                await handle_file_request(
                    reader,
                    writer,
                    message,
                )

            elif message_type == "PING":

                await send_message(
                    reader,
                    writer,
                    {
                        "type": "PONG"
                    },
                )

            elif message_type == "DISCONNECT":

                print(
                    f"{peer.device_name} disconnected."
                )
                break

            else:

                print(
                    f"Unknown message type: "
                    f"{message_type}"
                )

    except asyncio.IncompleteReadError:

        print(
            f"Connection closed by {address}"
        )

    except ProtocolError as exc:

        print(
            f"Protocol error from {address}: "
            f"{exc}"
        )

    except ConnectionError as exc:

        print(
            f"Connection error from {address}: "
            f"{exc}"
        )

    except Exception as exc:

        print(
            f"Unexpected error from {address}: "
            f"{exc}"
        )

    finally:

        if peer is not None:
            connected_peers.pop(
                peer.device_id,
                None,
            )

        writer.close()

        try:
            await writer.wait_closed()
        except Exception:
            pass

        print(
            f"Active peers: "
            f"{len(connected_peers)}"
        )


async def handle_file_request(
    reader: asyncio.StreamReader,
    writer: asyncio.StreamWriter,
    request: dict,
) -> None:
    """
    Handle an already-received FILE_REQUEST.

    The request is passed into the file receiver by manually
    processing the transfer.
    """

    from pathlib import Path
    from .protocol import file_response, transfer_complete
    from .utils import calculate_sha256, safe_filename, CHUNK_SIZE
    import struct

    transfer_id = request["transfer_id"]
    filename = safe_filename(request["filename"])
    filesize = request["filesize"]
    expected_checksum = request["checksum"]

    directory = Path("received")
    directory.mkdir(
        parents=True,
        exist_ok=True,
    )

    destination = directory / filename

    counter = 1

    while destination.exists():

        destination = (
            directory
            / f"{Path(filename).stem}_{counter}"
            f"{Path(filename).suffix}"
        )

        counter += 1

    print(
        f"\nReceiving file: {filename}"
    )

    await send_message(
        reader,
        writer,
        file_response(
            transfer_id,
            True,
        ),
    )

    received = 0

    with open(destination, "wb") as file:

        while received < filesize:

            header = await reader.readexactly(4)

            chunk_size = struct.unpack(
                "!I",
                header,
            )[0]

            if chunk_size <= 0:
                raise ProtocolError(
                    "Invalid chunk size."
                )

            chunk = await reader.readexactly(
                chunk_size
            )

            file.write(chunk)

            received += chunk_size

            percentage = (
                received / filesize * 100
                if filesize
                else 100
            )

            print(
                f"\rProgress: {percentage:.2f}%",
                end="",
                flush=True,
            )

    print()

    completion = await receive_message(
        reader,
        writer,
    )

    if completion.get("type") != "TRANSFER_COMPLETE":
        raise ProtocolError(
            "Expected TRANSFER_COMPLETE."
        )

    actual_checksum = calculate_sha256(
        destination
    )

    if actual_checksum != expected_checksum:

        print(
            "ERROR: SHA-256 verification failed."
        )

        await send_message(
            reader,
            writer,
            {
                "type": "TRANSFER_ERROR",
                "transfer_id": transfer_id,
                "error": "Checksum mismatch",
            },
        )

        destination.unlink(
            missing_ok=True
        )

        return

    print(
        f"File received successfully: "
        f"{destination}"
    )

    print(
        f"SHA256: {actual_checksum}"
    )


async def start_server() -> None:
    """Start the server."""

    server = await asyncio.start_server(
        handle_client,
        HOST,
        PORT,
    )

    addresses = ", ".join(
        str(sock.getsockname())
        for sock in server.sockets
    )

    print("=" * 60)
    print("P2P FILE SHARING SERVER")
    print("=" * 60)

    print(f"Device: {DEVICE_NAME}")
    print(f"Device ID: {DEVICE_ID}")
    print(f"Listening on: {addresses}")

    print("\nWaiting for peers...")

    async with server:
        await server.serve_forever()


if __name__ == "__main__":

    try:
        asyncio.run(start_server())

    except KeyboardInterrupt:
        print("\nServer stopped.")