"""
Bluetooth device discovery.

Windows note:
The discovery mechanism is intentionally kept separate from the
network/file-transfer protocol. The first prototype can use a
Windows-compatible discovery backend, while the rest of the application
remains unchanged.
"""

import asyncio
from dataclasses import dataclass


@dataclass
class BluetoothDevice:
    name: str
    address: str


async def discover_devices(timeout: int = 8) -> list[BluetoothDevice]:
    """
    Discover nearby Bluetooth devices.

    Currently this provides a placeholder interface. The actual Windows
    Bluetooth discovery backend can be plugged in here.
    """

    print(f"Scanning for Bluetooth devices ({timeout}s)...")

    # TODO:
    # Implement Windows Bluetooth discovery.
    #
    # The rest of the project should only depend on the returned
    # BluetoothDevice objects.

    await asyncio.sleep(timeout)

    print("Scan complete.")

    return []


def print_devices(devices: list[BluetoothDevice]) -> None:
    """Display discovered devices."""

    if not devices:
        print("No Bluetooth devices found.")
        return

    print("\nDiscovered devices:")
    print("-" * 50)

    for index, device in enumerate(devices, start=1):
        print(f"{index}. {device.name}")
        print(f"   Address: {device.address}")


async def main():
    devices = await discover_devices()
    print_devices(devices)


if __name__ == "__main__":
    asyncio.run(main())