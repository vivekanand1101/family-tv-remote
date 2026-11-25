#!/usr/bin/env python3
"""Minimal Jio STB UDP pairing/control probe.

Implements the local protocol recovered from the RTI driver and official
JioHome app. This development tool uses the transport validated against the
local JMSD200A box.
"""

from __future__ import annotations

import argparse
import json
import re
import secrets
import select
import socket
import sys
import time
from dataclasses import asdict, dataclass
from pathlib import Path

CONTROL_PORT = 4445
BEACON_PORT = 5775
DEFAULT_STATE = Path.home() / ".config" / "audio-tv-remote" / "jio-pairing.json"
CLIENT_ID_PATTERN = re.compile(r"^[a-z0-9]{6,32}$")


@dataclass
class PairingState:
    client_id: str
    dns_name: str = ""
    pair_code: str = ""

    @property
    def paired(self) -> bool:
        return bool(self.dns_name and self.pair_code)


def load_state(path: Path) -> PairingState:
    if path.exists():
        raw = json.loads(path.read_text(encoding="utf-8"))
        state = PairingState(
            client_id=str(raw.get("client_id", "")),
            dns_name=str(raw.get("dns_name", "")),
            pair_code=str(raw.get("pair_code", "")),
        )
        if CLIENT_ID_PATTERN.fullmatch(state.client_id):
            return state

    # A stable lower-case identifier is accepted; official JioHome commonly
    # uses the device's Android ID while the RTI driver uses a MAC-derived ID.
    return PairingState(client_id=secrets.token_hex(8))


def save_state(path: Path, state: PairingState) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(asdict(state), indent=2) + "\n", encoding="utf-8")
    path.chmod(0o600)


class JioUdpClient:
    def __init__(self, host: str, state: PairingState, timeout: float) -> None:
        self.host = host
        self.state = state
        self.timeout = timeout
        self.control_socket = self._bind(CONTROL_PORT)
        self.beacon_socket = self._bind(BEACON_PORT)

    @staticmethod
    def _bind(port: int) -> socket.socket:
        udp_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        udp_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        udp_socket.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        udp_socket.bind(("", port))
        udp_socket.setblocking(False)
        return udp_socket

    def close(self) -> None:
        self.control_socket.close()
        self.beacon_socket.close()

    def send(self, message: str) -> None:
        payload = message.encode("ascii")
        print(f"TX {self.host}:{CONTROL_PORT} {message}", flush=True)
        self.control_socket.sendto(payload, (self.host, CONTROL_PORT))

    def receive_until(self, wanted: set[str] | None = None) -> tuple[str, list[str]]:
        deadline = time.monotonic() + self.timeout
        received: list[str] = []
        final_kind = "timeout"

        while time.monotonic() < deadline:
            readable, _, _ = select.select(
                [self.control_socket, self.beacon_socket],
                [],
                [],
                min(0.5, max(0.0, deadline - time.monotonic())),
            )
            if not readable:
                continue
            udp_socket = readable[0]
            payload, address = udp_socket.recvfrom(65535)

            message = payload.decode("utf-8", errors="replace").strip()
            received.append(message)
            kind = classify(message)
            print(f"RX {address[0]}:{address[1]} [{kind}] {message}", flush=True)
            self.update_state(message)
            if wanted and kind in wanted:
                final_kind = kind
                break

        return final_kind, received

    def update_state(self, message: str) -> None:
        if message.startswith("PAIR:"):
            parts = message.split(":", 2)
            if len(parts) == 3:
                self.state.dns_name = parts[1]
        elif message.startswith("CONNECTED:"):
            parts = message.split(":", 2)
            if len(parts) == 3:
                self.state.dns_name = parts[1]
                self.state.pair_code = parts[2]
        elif message.startswith("beacon:") and not self.state.dns_name:
            parts = message.split(":", 2)
            if len(parts) >= 2:
                self.state.dns_name = parts[1]


def classify(message: str) -> str:
    lowered = message.lower()
    if lowered.startswith("beacon:"):
        return "beacon"
    if message.startswith("PAIR:"):
        return "pair"
    if message.startswith("CONNECTED:"):
        return "connected"
    if lowered.startswith("cancelled:") or lowered == "cancelled":
        return "cancelled"
    return "status"


def positive_timeout(value: str) -> float:
    timeout = float(value)
    if timeout <= 0:
        raise argparse.ArgumentTypeError("timeout must be positive")
    return timeout


def four_digit_pin(value: str) -> str:
    if not re.fullmatch(r"\d{4}", value):
        raise argparse.ArgumentTypeError("PIN must contain exactly four digits")
    return value


def keycode(value: str) -> int:
    parsed = int(value)
    if not 0 <= parsed <= 300:
        raise argparse.ArgumentTypeError("keycode must be between 0 and 300")
    return parsed


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", required=True, help="Jio STB address (development use only)")
    parser.add_argument("--state", type=Path, default=DEFAULT_STATE)
    parser.add_argument("--timeout", type=positive_timeout, default=15.0)
    parser.add_argument("--client-id", help="Override the persisted client identifier")

    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("listen", help="Listen for beacons/status without transmitting")
    commands.add_parser("connect", help="Request the one-time four-digit pairing code")

    pair = commands.add_parser("pair", help="Submit the four-digit code shown by the STB")
    pair.add_argument("pin", type=four_digit_pin)

    key = commands.add_parser("key", help="Send an Android keycode after pairing")
    key.add_argument("keycode", type=keycode)
    key.add_argument("--event", choices=["longpress"])

    text = commands.add_parser("text", help="Send text one character at a time, then Enter")
    text.add_argument("value")

    commands.add_parser("cancel", help="Send the recovered CANCELLED message")
    commands.add_parser("show-state", help="Print persisted non-secret identity and pair status")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    state = load_state(args.state)
    if args.client_id:
        candidate = args.client_id.lower().replace(":", "").replace("-", "")
        if not CLIENT_ID_PATTERN.fullmatch(candidate):
            raise SystemExit("--client-id must be 6-32 lower-case letters/digits")
        state.client_id = candidate

    if args.command == "show-state":
        save_state(args.state, state)
        print(json.dumps({"client_id": state.client_id, "dns_name": state.dns_name, "paired": state.paired}, indent=2))
        return 0

    client = JioUdpClient(args.host, state, args.timeout)
    try:
        if args.command == "listen":
            client.receive_until()
        elif args.command == "connect":
            client.send(f"CONNECT:{state.client_id}")
            kind, _ = client.receive_until({"pair", "connected", "cancelled"})
            if kind == "timeout":
                print("No reply. Confirm the STB is fully booted and on its home screen.", file=sys.stderr)
                return 2
        elif args.command == "pair":
            client.send(f"PAIRCODE:{state.client_id}:{args.pin}")
            kind, _ = client.receive_until({"connected", "cancelled"})
            if kind != "connected":
                print("Pairing was not confirmed by the STB.", file=sys.stderr)
                return 2
        elif args.command == "cancel":
            client.send("CANCELLED")
            client.receive_until({"cancelled"})
        elif args.command == "key":
            require_pairing(state)
            suffix = f":{args.event}" if args.event else ""
            client.send(f"KEYCODE:{state.client_id}:{state.pair_code}:{args.keycode}{suffix}")
            client.receive_until()
        elif args.command == "text":
            require_pairing(state)
            for character in args.value:
                client.send(f"KEYINPUTTEXT::{state.client_id}::{state.pair_code}::{character}")
                time.sleep(0.05)
            client.send(f"KEYCODE:{state.client_id}:{state.pair_code}:66")
            client.receive_until()
        else:
            raise AssertionError(args.command)
    finally:
        client.close()
        save_state(args.state, state)

    return 0


def require_pairing(state: PairingState) -> None:
    if not state.paired:
        raise SystemExit("No saved pairing. Run connect, then pair with the code shown on the TV.")


if __name__ == "__main__":
    raise SystemExit(main())
