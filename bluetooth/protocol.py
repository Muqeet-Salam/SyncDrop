"""
Communication protocol for peer-to-peer file sharing.

Message format:

    4 bytes  -> message length
    N bytes  -> JSON message

Example:

{
    "type": "HELLO",
    "device_id": "...",
    "device_name": "Laptop"
}
"""

import asyncio
import json
import struct


HEADER_SIZE = 4
MAX_MESSAGE_SIZE = 10 * 1024 * 1024  # 10 MB


class ProtocolError(Exception):
    """Raised when an invalid protocol message is received."""


async def send_message(
    reader: asyncio.StreamReader,
    writer: asyncio.StreamWriter,
    message: dict,
) -> None:
    """
    Send a JSON message.

    The reader argument is included so the function has a consistent
    interface with receive_message, although it is not used here.
    """

    data = json.dumps(message).encode("utf-8")

    if len(data) > MAX_MESSAGE_SIZE:
        raise ProtocolError("Message is too large.")

    header = struct.pack("!I", len(data))

    writer.write(header)
    writer.write(data)

    await writer.drain()


async def receive_message(
    reader: asyncio.StreamReader,
    writer: asyncio.StreamWriter | None = None,
) -> dict:
    """Receive and decode a JSON message."""

    header = await reader.readexactly(HEADER_SIZE)

    message_length = struct.unpack("!I", header)[0]

    if message_length <= 0:
        raise ProtocolError("Invalid message length.")

    if message_length > MAX_MESSAGE_SIZE:
        raise ProtocolError("Message exceeds maximum allowed size.")

    data = await reader.readexactly(message_length)

    try:
        message = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ProtocolError("Invalid JSON message.") from exc

    if not isinstance(message, dict):
        raise ProtocolError("Protocol message must be a JSON object.")

    return message


def hello_message(device_id: str, device_name: str) -> dict:
    """Create a HELLO message."""

    return {
        "type": "HELLO",
        "device_id": device_id,
        "device_name": device_name,
    }


def hello_response(device_id: str, device_name: str) -> dict:
    """Create a response to a HELLO message."""

    return {
        "type": "HELLO_ACK",
        "device_id": device_id,
        "device_name": device_name,
    }


def file_request(
    transfer_id: str,
    filename: str,
    filesize: int,
    checksum: str,
) -> dict:
    """Create a file transfer request."""

    return {
        "type": "FILE_REQUEST",
        "transfer_id": transfer_id,
        "filename": filename,
        "filesize": filesize,
        "checksum": checksum,
    }


def file_response(
    transfer_id: str,
    accepted: bool,
) -> dict:
    """Accept or reject a file transfer."""

    return {
        "type": "FILE_RESPONSE",
        "transfer_id": transfer_id,
        "accepted": accepted,
    }


def transfer_complete(
    transfer_id: str,
    checksum: str,
) -> dict:
    """Notify peer that a transfer has completed."""

    return {
        "type": "TRANSFER_COMPLETE",
        "transfer_id": transfer_id,
        "checksum": checksum,
    }


def transfer_error(
    transfer_id: str,
    error: str,
) -> dict:
    """Notify peer about a transfer error."""

    return {
        "type": "TRANSFER_ERROR",
        "transfer_id": transfer_id,
        "error": error,
    }