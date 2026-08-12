"""
P2P file-sharing client.
"""

import asyncio
import sys

from .file_transfer import send_file
from .protocol import (
    hello_message,
    receive_message,
    send_message,
)
from .utils import (
    generate_device_id,
    get_device_name,
)


DEVICE_ID = generate_device_id()
DEVICE_NAME = get_device_name()


class P2PClient:

    def __init__(
        self,
        host: str,
        port: int,
    ):
        self.host = host
        self.port = port

        self.reader = None
        self.writer = None

    async def connect(self) -> None:
        """Connect to a peer."""

        print(
            f"Connecting to "
            f"{self.host}:{self.port}..."
        )

        self.reader, self.writer = (
            await asyncio.open_connection(
                self.host,
                self.port,
            )
        )

        print("Connected.")

        # Introduce ourselves.
        await send_message(
            self.reader,
            self.writer,
            hello_message(
                DEVICE_ID,
                DEVICE_NAME,
            ),
        )

        response = await receive_message(
            self.reader,
            self.writer,
        )

        if response.get("type") != "HELLO_ACK":
            raise RuntimeError(
                "Invalid HELLO response."
            )

        print(
            f"Connected to: "
            f"{response.get('device_name')}"
        )

        print(
            f"Remote device ID: "
            f"{response.get('device_id')}"
        )

    async def send_file(
        self,
        filepath: str,
    ) -> None:
        """Send a file to the connected peer."""

        if self.reader is None or self.writer is None:
            raise RuntimeError(
                "Client is not connected."
            )

        await send_file(
            self.reader,
            self.writer,
            filepath,
        )

    async def disconnect(self) -> None:
        """Disconnect from the peer."""

        if self.writer is None:
            return

        try:

            await send_message(
                self.reader,
                self.writer,
                {
                    "type": "DISCONNECT"
                },
            )

        except Exception:
            pass

        self.writer.close()

        try:
            await self.writer.wait_closed()
        except Exception:
            pass

        self.reader = None
        self.writer = None

        print("Disconnected.")


async def main():

    if len(sys.argv) < 2:

        print(
            "Usage:"
        )

        print(
            "python -m bluetooth.client "
            "<SERVER_IP> [FILE]"
        )

        return

    server_ip = sys.argv[1]

    client = P2PClient(
        host=server_ip,
        port=8765,
    )

    try:

        await client.connect()

        if len(sys.argv) >= 3:

            filepath = sys.argv[2]

            await client.send_file(
                filepath
            )

        else:

            print(
                "\nConnected successfully."
            )

            print(
                "No file specified."
            )

            print(
                "You can provide a file path "
                "as the second argument."
            )

            await asyncio.sleep(5)

    except ConnectionRefusedError:

        print(
            "Could not connect to the server."
        )

        print(
            "Make sure server.py is running."
        )

    except FileNotFoundError as exc:

        print(
            f"File not found: {exc}"
        )

    except Exception as exc:

        print(
            f"Error: {exc}"
        )

    finally:

        await client.disconnect()


if __name__ == "__main__":

    try:
        asyncio.run(main())

    except KeyboardInterrupt:
        print("\nClient stopped.")