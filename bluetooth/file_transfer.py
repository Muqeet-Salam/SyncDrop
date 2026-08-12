"""
File transfer implementation.

Files are transferred in chunks.

The protocol is:

    FILE_REQUEST
        ↓
    FILE_RESPONSE
        ↓
    FILE_DATA
        ↓
    TRANSFER_COMPLETE
"""

import asyncio
import os
import struct
from pathlib import Path

from .protocol import (
    ProtocolError,
    file_request,
    file_response,
    transfer_complete,
)
from .utils import (
    CHUNK_SIZE,
    calculate_sha256,
    generate_transfer_id,
    safe_filename,
)


FILE_CHUNK_HEADER = 4


async def send_file(
    reader: asyncio.StreamReader,
    writer: asyncio.StreamWriter,
    filepath: str | Path,
) -> str:
    """
    Send a file to the connected peer.

    Returns:
        transfer_id
    """

    filepath = Path(filepath)

    if not filepath.exists():
        raise FileNotFoundError(filepath)

    if not filepath.is_file():
        raise ValueError(f"{filepath} is not a file.")

    filename = filepath.name
    filesize = filepath.stat().st_size

    print(f"Preparing file: {filename}")
    print(f"Size: {filesize} bytes")

    checksum = calculate_sha256(filepath)

    transfer_id = generate_transfer_id()

    request = file_request(
        transfer_id=transfer_id,
        filename=filename,
        filesize=filesize,
        checksum=checksum,
    )

    from .protocol import send_message, receive_message

    await send_message(reader, writer, request)

    response = await receive_message(reader, writer)

    if response.get("type") != "FILE_RESPONSE":
        raise ProtocolError("Expected FILE_RESPONSE.")

    if response.get("transfer_id") != transfer_id:
        raise ProtocolError("Transfer ID mismatch.")

    if not response.get("accepted"):
        print("Peer rejected the file transfer.")
        return transfer_id

    print(f"Sending {filename}...")

    bytes_sent = 0

    with open(filepath, "rb") as file:
        while True:
            chunk = file.read(CHUNK_SIZE)

            if not chunk:
                break

            # Chunk length
            writer.write(struct.pack("!I", len(chunk)))

            # Chunk data
            writer.write(chunk)

            await writer.drain()

            bytes_sent += len(chunk)

            percentage = (
                (bytes_sent / filesize) * 100
                if filesize > 0
                else 100
            )

            print(
                f"\rProgress: {percentage:6.2f}%",
                end="",
                flush=True,
            )

    print()

    await send_message(
        reader,
        writer,
        transfer_complete(
            transfer_id,
            checksum,
        ),
    )

    print("Waiting for receiver confirmation...")

    return transfer_id


async def receive_file(
    reader: asyncio.StreamReader,
    writer: asyncio.StreamWriter,
    download_directory: str | Path = "received",
) -> Path:
    """
    Receive a file from a peer.

    The FILE_REQUEST must already be received by the caller.
    """

    from .protocol import send_message

    request = await receive_pending_request(reader, writer)

    if request["type"] != "FILE_REQUEST":
        raise ProtocolError("Expected FILE_REQUEST.")

    transfer_id = request["transfer_id"]
    filename = safe_filename(request["filename"])
    filesize = request["filesize"]
    expected_checksum = request["checksum"]

    download_directory = Path(download_directory)
    download_directory.mkdir(
        parents=True,
        exist_ok=True,
    )

    destination = download_directory / filename

    # Avoid overwriting an existing file.
    if destination.exists():
        destination = unique_filename(destination)

    print("\nIncoming file:")
    print(f"  Name: {filename}")
    print(f"  Size: {filesize} bytes")
    print(f"  SHA256: {expected_checksum}")

    await send_message(
        reader,
        writer,
        file_response(
            transfer_id=transfer_id,
            accepted=True,
        ),
    )

    bytes_received = 0

    with open(destination, "wb") as file:
        while bytes_received < filesize:

            header = await reader.readexactly(FILE_CHUNK_HEADER)

            chunk_size = struct.unpack(
                "!I",
                header,
            )[0]

            if chunk_size <= 0:
                raise ProtocolError("Invalid chunk size.")

            chunk = await reader.readexactly(chunk_size)

            file.write(chunk)

            bytes_received += chunk_size

            percentage = (
                bytes_received / filesize * 100
                if filesize > 0
                else 100
            )

            print(
                f"\rReceiving: {percentage:6.2f}%",
                end="",
                flush=True,
            )

    print()

    # Wait for TRANSFER_COMPLETE.
    completion = await receive_pending_request(
        reader,
        writer,
    )

    if completion["type"] != "TRANSFER_COMPLETE":
        raise ProtocolError(
            "Expected TRANSFER_COMPLETE."
        )

    actual_checksum = calculate_sha256(destination)

    if actual_checksum != expected_checksum:
        raise ProtocolError(
            "SHA-256 verification failed."
        )

    print("Transfer verified successfully.")
    print(f"Saved to: {destination}")

    return destination


async def receive_pending_request(
    reader: asyncio.StreamReader,
    writer: asyncio.StreamWriter,
) -> dict:
    """Receive the next protocol message."""

    from .protocol import receive_message

    return await receive_message(
        reader,
        writer,
    )


def unique_filename(path: Path) -> Path:
    """Generate a unique filename if a file already exists."""

    counter = 1

    while True:
        candidate = path.with_name(
            f"{path.stem}_{counter}{path.suffix}"
        )

        if not candidate.exists():
            return candidate

        counter += 1