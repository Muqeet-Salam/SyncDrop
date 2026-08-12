"""
Utility functions used throughout the Bluetooth file-sharing system.
"""

import hashlib
import os
import platform
import socket
import uuid
from pathlib import Path


CHUNK_SIZE = 64 * 1024  # 64 KB


def generate_device_id() -> str:
    """Generate a persistent-style device identifier."""

    return str(uuid.uuid4())


def generate_transfer_id() -> str:
    """Generate a unique transfer ID."""

    return str(uuid.uuid4())


def get_device_name() -> str:
    """Return the local computer's hostname."""

    return socket.gethostname()


def get_platform_info() -> dict:
    """Return basic system information."""

    return {
        "system": platform.system(),
        "release": platform.release(),
        "machine": platform.machine(),
        "hostname": socket.gethostname(),
    }


def get_file_size(filepath: str | Path) -> int:
    """Return file size in bytes."""

    return os.path.getsize(filepath)


def calculate_sha256(
    filepath: str | Path,
    chunk_size: int = CHUNK_SIZE,
) -> str:
    """Calculate SHA-256 checksum of a file."""

    sha256 = hashlib.sha256()

    with open(filepath, "rb") as file:
        while True:
            chunk = file.read(chunk_size)

            if not chunk:
                break

            sha256.update(chunk)

    return sha256.hexdigest()


def safe_filename(filename: str) -> str:
    """
    Sanitize a received filename.

    Prevents path traversal such as:

        ../../important.txt
    """

    filename = os.path.basename(filename)

    if not filename:
        raise ValueError("Invalid filename.")

    return filename


def format_bytes(size: int) -> str:
    """Convert bytes to a human-readable string."""

    units = [
        "B",
        "KB",
        "MB",
        "GB",
        "TB",
    ]

    value = float(size)

    for unit in units:
        if value < 1024:
            return f"{value:.2f} {unit}"

        value /= 1024

    return f"{value:.2f} PB"