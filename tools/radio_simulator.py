#!/usr/bin/env python3
"""Scriptable Yaesu CAT, Icom CI-V, and Hamlib rigctld radio simulator."""

from __future__ import annotations

import argparse
import json
import socketserver
import sys
import threading
import time
from collections import defaultdict, deque
from dataclasses import dataclass, field
from pathlib import Path
from typing import BinaryIO, Deque, Iterable


MODEL_CONFIG = {
    "ft817": ("yaesu", None, 2),
    "ft857": ("yaesu", None, 2),
    "ic705": ("icom", 0xA4, 1),
    "ic9700": ("icom", 0xA2, 1),
    "ic910": ("icom", 0x60, 1),
    "rigctld": ("rigctld", None, 1),
}

YAESU_MODE_TO_BYTE = {
    "LSB": 0x00,
    "USB": 0x01,
    "CW": 0x02,
    "CW-R": 0x03,
    "AM": 0x04,
    "FM": 0x08,
    "DIG": 0x0A,
    "PKT": 0x0C,
}
YAESU_BYTE_TO_MODE = {value: key for key, value in YAESU_MODE_TO_BYTE.items()}

ICOM_MODE_TO_BYTE = {
    "LSB": 0x00,
    "USB": 0x01,
    "AM": 0x02,
    "CW": 0x03,
    "RTTY": 0x04,
    "FM": 0x05,
    "WFM": 0x06,
    "CW-R": 0x07,
    "RTTY-R": 0x08,
    "DV": 0x17,
}
ICOM_BYTE_TO_MODE = {value: key for key, value in ICOM_MODE_TO_BYTE.items()}


class DisconnectClient(Exception):
    pass


@dataclass
class SimulatorReply:
    chunks: list[bytes] = field(default_factory=list)
    close: bool = False
    inter_chunk_delay: float = 0.02


@dataclass
class RadioState:
    frequency_hz: int = 145_900_000
    tx_frequency_hz: int = 435_100_000
    mode: str = "USB"
    tx_mode: str = "LSB"
    ptt: bool = False
    split: bool = False
    selected_tx: bool = False
    satellite_mode: bool = False
    ctcss_enabled: bool = False
    ctcss_tenths_hz: int = 0


class FaultPlan:
    """Consumes per-command actions from a JSON/CLI supplied queue."""

    def __init__(self, actions: dict[str, Iterable[str]] | None = None) -> None:
        self._actions: dict[str, Deque[str]] = defaultdict(deque)
        self._lock = threading.Lock()
        for command, values in (actions or {}).items():
            self._actions[command].extend(str(value) for value in values)

    @classmethod
    def load(cls, path: str | None, cli_faults: list[str]) -> "FaultPlan":
        actions: dict[str, list[str]] = defaultdict(list)
        if path:
            document = json.loads(Path(path).read_text(encoding="utf-8"))
            if not isinstance(document, dict):
                raise ValueError("fault script must be a JSON object")
            for command, values in document.items():
                if isinstance(values, str):
                    values = [values]
                if not isinstance(values, list):
                    raise ValueError(f"fault list for {command!r} must be a string or array")
                actions[str(command)].extend(str(value) for value in values)
        for value in cli_faults:
            command, separator, action = value.partition("=")
            if not separator or not command or not action:
                raise ValueError(f"invalid --fault {value!r}; expected COMMAND=ACTION")
            actions[command].append(action)
        return cls(actions)

    def take(self, command: str) -> str:
        with self._lock:
            queue = self._actions.get(command)
            if queue:
                return queue.popleft()
            wildcard = self._actions.get("*")
            if wildcard:
                return wildcard.popleft()
        return "ok"


def _reply_for_action(
    action: str,
    normal: bytes,
    malformed: bytes = b"\x00",
    nak: bytes = b"",
) -> SimulatorReply:
    if action == "ok":
        return SimulatorReply([normal] if normal else [])
    if action == "drop":
        return SimulatorReply()
    if action == "nak":
        return SimulatorReply([nak] if nak else [])
    if action == "malformed":
        return SimulatorReply([malformed])
    if action == "disconnect":
        return SimulatorReply(close=True)
    if action == "wrong_address":
        if len(normal) >= 4 and normal[:2] == b"\xFE\xFE":
            changed = bytearray(normal)
            changed[3] ^= 0x01
            return SimulatorReply([bytes(changed)])
        return SimulatorReply([malformed])
    if action.startswith("delay:"):
        delay_ms = int(action.partition(":")[2])
        time.sleep(max(0, delay_ms) / 1000)
        return SimulatorReply([normal] if normal else [])
    if action.startswith("partial:"):
        chunk_size = max(1, int(action.partition(":")[2]))
        return SimulatorReply(
            [normal[index : index + chunk_size] for index in range(0, len(normal), chunk_size)]
        )
    raise ValueError(f"unknown fault action {action!r}")


def encode_yaesu_frequency(frequency_hz: int) -> bytes:
    digits = f"{frequency_hz // 10:08d}"
    if len(digits) != 8:
        raise ValueError("Yaesu frequency is outside the 8-digit CAT range")
    return bytes(int(digits[index : index + 2], 16) for index in range(0, 8, 2))


def decode_yaesu_frequency(value: bytes) -> int:
    if len(value) != 4:
        raise ValueError("Yaesu CAT frequency needs four bytes")
    digits = "".join(f"{byte >> 4}{byte & 0x0F}" for byte in value)
    if any(nibble > 9 for byte in value for nibble in (byte >> 4, byte & 0x0F)):
        raise ValueError("invalid Yaesu frequency BCD")
    return int(digits) * 10


def encode_icom_frequency(frequency_hz: int) -> bytes:
    digits = f"{frequency_hz:010d}"
    if len(digits) != 10:
        raise ValueError("CI-V frequency is outside the 10-digit range")
    pairs = [int(digits[index : index + 2], 16) for index in range(0, 10, 2)]
    return bytes(reversed(pairs))


def decode_icom_frequency(value: bytes) -> int:
    if len(value) != 5:
        raise ValueError("CI-V frequency needs five bytes")
    if any(nibble > 9 for byte in value for nibble in (byte >> 4, byte & 0x0F)):
        raise ValueError("invalid CI-V frequency BCD")
    digits = "".join(f"{byte >> 4}{byte & 0x0F}" for byte in reversed(value))
    return int(digits)


class ProtocolSimulator:
    def __init__(
        self,
        model: str,
        state: RadioState,
        faults: FaultPlan,
        echo: bool = False,
        verbose: bool = True,
    ) -> None:
        self.model = model
        self.protocol, self.civ_address, _ = MODEL_CONFIG[model]
        self.state = state
        self.faults = faults
        self.echo = echo
        self.verbose = verbose
        self._buffer = bytearray()

    def feed(self, data: bytes) -> list[SimulatorReply]:
        self._buffer.extend(data)
        replies: list[SimulatorReply] = []
        if self.protocol == "yaesu":
            while len(self._buffer) >= 5:
                command = bytes(self._buffer[:5])
                del self._buffer[:5]
                replies.append(self._handle_yaesu(command))
        elif self.protocol == "icom":
            while True:
                start = self._buffer.find(b"\xFE\xFE")
                if start < 0:
                    self._buffer.clear()
                    break
                if start:
                    del self._buffer[:start]
                end = self._buffer.find(b"\xFD", 4)
                if end < 0:
                    break
                frame = bytes(self._buffer[: end + 1])
                del self._buffer[: end + 1]
                replies.append(self._handle_icom(frame))
        else:
            while b"\n" in self._buffer:
                line, _, remaining = self._buffer.partition(b"\n")
                self._buffer = bytearray(remaining)
                line = line.rstrip(b"\r")
                if line:
                    replies.append(self._handle_rigctld(line.decode("ascii", errors="replace")))
        return replies

    def _log(self, command: str, detail: str, action: str) -> None:
        if self.verbose:
            print(f"{self.model}: {command} {detail} -> {action}", flush=True)

    def _apply(self, command: str, detail: str, normal: bytes, malformed: bytes, nak: bytes) -> SimulatorReply:
        action = self.faults.take(command)
        self._log(command, detail, action)
        return _reply_for_action(action, normal, malformed, nak)

    def _handle_yaesu(self, command: bytes) -> SimulatorReply:
        opcode = command[4]
        name = {
            0x01: "set_frequency",
            0x03: "read_frequency_mode",
            0x07: "set_mode",
            0x08: "ptt_on",
            0x88: "ptt_off",
            0x0A: "set_ctcss_mode",
            0x0B: "set_ctcss_tone",
            0xF7: "read_ptt",
        }.get(opcode, "unknown")
        action = self.faults.take(name)
        detail = command.hex(" ").upper()
        self._log(name, detail, action)

        if action != "nak":
            if opcode == 0x01:
                self.state.frequency_hz = decode_yaesu_frequency(command[:4])
            elif opcode == 0x07 and command[0] in YAESU_BYTE_TO_MODE:
                self.state.mode = YAESU_BYTE_TO_MODE[command[0]]
            elif opcode == 0x08:
                self.state.ptt = True
            elif opcode == 0x88:
                self.state.ptt = False
            elif opcode == 0x0A:
                self.state.ctcss_enabled = command[0] == 0x4A

        if opcode == 0x03:
            normal = encode_yaesu_frequency(self.state.frequency_hz) + bytes(
                [YAESU_MODE_TO_BYTE.get(self.state.mode, 0x01)]
            )
            malformed = b"\xFA\x00\x00\x00\x7F"
        elif opcode == 0xF7:
            if self.model == "ft817":
                normal = b"\x00" if self.state.ptt else b"\xFF"
            else:
                normal = b"\x00" if self.state.ptt else b"\x80"
            malformed = b"\x7F"
        else:
            normal = b"\x00"
            malformed = b"\xFF"
        return _reply_for_action(action, normal, malformed, b"")

    def _icom_frame(self, command: int, payload: bytes = b"") -> bytes:
        return bytes([0xFE, 0xFE, 0xE0, int(self.civ_address), command]) + payload + b"\xFD"

    def _icom_ack(self, ok: bool = True) -> bytes:
        return self._icom_frame(0xFB if ok else 0xFA)

    def _selected_frequency(self, selector: int | None = None) -> int:
        if selector is None:
            selected_tx = self.state.selected_tx
        else:
            selected_tx = self.state.selected_tx if selector == 0 else not self.state.selected_tx
        return self.state.tx_frequency_hz if selected_tx else self.state.frequency_hz

    def _selected_mode(self, selector: int | None = None) -> str:
        if selector is None:
            selected_tx = self.state.selected_tx
        else:
            selected_tx = self.state.selected_tx if selector == 0 else not self.state.selected_tx
        return self.state.tx_mode if selected_tx else self.state.mode

    def _handle_icom(self, frame: bytes) -> SimulatorReply:
        if len(frame) < 6 or frame[2] != self.civ_address:
            return SimulatorReply()
        payload = frame[4:-1]
        command = payload[0]
        data = payload[1:]
        name = "unknown"
        normal = self._icom_ack()
        malformed = self._icom_frame(command, b"\xFA")

        if command == 0x07:
            name = "connect" if not data else "select_vfo"
            if data:
                self.state.selected_tx = data[0] in (0x01, 0xD1)
        elif command == 0x03:
            name = "read_frequency"
            normal = self._icom_frame(command, encode_icom_frequency(self._selected_frequency()))
            malformed = self._icom_frame(command, b"\xFA\x00\x00\x00\x00")
        elif command == 0x04:
            name = "read_mode"
            normal = self._icom_frame(command, bytes([ICOM_MODE_TO_BYTE[self._selected_mode()], 0x01]))
            malformed = self._icom_frame(command, b"\x7F\x01")
        elif command == 0x05:
            name = "set_frequency"
        elif command == 0x06:
            name = "set_mode"
        elif command == 0x0F:
            name = "set_split"
        elif command == 0x16 and data[:1] == b"\x5A":
            name = "set_satellite_mode" if len(data) >= 2 else "read_satellite_mode"
            if len(data) == 1:
                normal = self._icom_frame(command, bytes([0x5A, int(self.state.satellite_mode)]))
        elif command == 0x1A and data[:1] == b"\x07":
            name = "set_satellite_mode" if len(data) >= 2 else "read_satellite_mode"
            if len(data) == 1:
                normal = self._icom_frame(command, bytes([0x07, int(self.state.satellite_mode)]))
        elif command == 0x16 and data[:1] == b"\x42":
            name = "set_ctcss_mode"
        elif command == 0x1B:
            name = "set_ctcss_tone"
        elif command == 0x25 and data:
            selector = data[0]
            name = "read_split_frequency" if len(data) == 1 else "set_split_frequency"
            if len(data) == 1:
                normal = self._icom_frame(
                    command, bytes([selector]) + encode_icom_frequency(self._selected_frequency(selector))
                )
        elif command == 0x26 and data:
            selector = data[0]
            name = "read_split_mode" if len(data) == 1 else "set_split_mode"
            if len(data) == 1:
                mode = ICOM_MODE_TO_BYTE[self._selected_mode(selector)]
                normal = self._icom_frame(command, bytes([selector, mode, 0x01]))
        elif command == 0x1C and data[:1] == b"\x00":
            name = "read_ptt" if len(data) == 1 else ("ptt_on" if data[1] else "ptt_off")
            if len(data) == 1:
                normal = self._icom_frame(command, bytes([0x00, int(self.state.ptt)]))

        action = self.faults.take(name)
        self._log(name, frame.hex(" ").upper(), action)
        if action != "nak":
            if command == 0x05 and len(data) >= 5:
                frequency = decode_icom_frequency(data[:5])
                if self.state.selected_tx:
                    self.state.tx_frequency_hz = frequency
                else:
                    self.state.frequency_hz = frequency
            elif command == 0x06 and data and data[0] in ICOM_BYTE_TO_MODE:
                if self.state.selected_tx:
                    self.state.tx_mode = ICOM_BYTE_TO_MODE[data[0]]
                else:
                    self.state.mode = ICOM_BYTE_TO_MODE[data[0]]
            elif command == 0x0F and data:
                self.state.split = data[0] == 1
            elif command in (0x16, 0x1A) and len(data) >= 2 and data[0] in (0x5A, 0x07):
                self.state.satellite_mode = data[1] == 1
            elif command == 0x16 and len(data) >= 2 and data[0] == 0x42:
                self.state.ctcss_enabled = data[1] == 1
            elif command == 0x25 and len(data) >= 6:
                frequency = decode_icom_frequency(data[1:6])
                selected_tx = self.state.selected_tx if data[0] == 0 else not self.state.selected_tx
                if selected_tx:
                    self.state.tx_frequency_hz = frequency
                else:
                    self.state.frequency_hz = frequency
            elif command == 0x26 and len(data) >= 2 and data[1] in ICOM_BYTE_TO_MODE:
                selected_tx = self.state.selected_tx if data[0] == 0 else not self.state.selected_tx
                if selected_tx:
                    self.state.tx_mode = ICOM_BYTE_TO_MODE[data[1]]
                else:
                    self.state.mode = ICOM_BYTE_TO_MODE[data[1]]
            elif command == 0x1C and len(data) >= 2 and data[0] == 0:
                self.state.ptt = data[1] == 1

        reply = _reply_for_action(action, normal, malformed, self._icom_ack(False))
        if self.echo and not reply.close:
            reply.chunks.insert(0, frame)
        return reply

    def _handle_rigctld(self, line: str) -> SimulatorReply:
        parts = line.split()
        raw_command = parts[0]
        command = raw_command.lstrip("\\")
        aliases = {
            "f": "get_freq",
            "F": "set_freq",
            "m": "get_mode",
            "M": "set_mode",
            "t": "get_ptt",
            "T": "set_ptt",
            "s": "get_split_vfo",
            "S": "set_split_vfo",
            "i": "get_split_freq",
            "I": "set_split_freq",
            "x": "get_split_mode",
            "X": "set_split_mode",
            "v": "get_vfo",
            "V": "set_vfo",
            "C": "set_ctcss_tone",
            "U": "set_func",
        }
        command = aliases.get(command, command)
        name = {
            "get_freq": "read_frequency",
            "set_freq": "set_frequency",
            "get_mode": "read_mode",
            "set_mode": "set_mode",
            "get_ptt": "read_ptt",
            "set_ptt": "ptt_on" if len(parts) > 1 and parts[1] != "0" else "ptt_off",
            "get_split_freq": "read_split_frequency",
            "set_split_freq": "set_split_frequency",
            "get_split_mode": "read_split_mode",
            "set_split_mode": "set_split_mode",
            "get_split_vfo": "read_split",
            "set_split_vfo": "set_split",
            "get_vfo": "read_vfo",
            "set_vfo": "select_vfo",
            "set_ctcss_tone": "set_ctcss_tone",
            "set_func": "set_ctcss_mode",
        }.get(command, "unknown")
        action = self.faults.take(name)
        self._log(name, line, action)

        normal = b"RPRT 0\n"
        malformed = b"garbage\n"
        if command == "get_freq":
            normal = f"{self.state.frequency_hz}\n".encode()
        elif command == "get_mode":
            normal = f"{self.state.mode}\n0\n".encode()
        elif command == "get_ptt":
            normal = f"{int(self.state.ptt)}\n".encode()
        elif command == "get_split_freq":
            normal = f"{self.state.tx_frequency_hz}\n".encode()
        elif command == "get_split_mode":
            normal = f"{self.state.tx_mode}\n0\n".encode()
        elif command == "get_split_vfo":
            normal = f"{int(self.state.split)}\nVFOB\n".encode()
        elif command == "get_vfo":
            normal = b"VFOB\n" if self.state.selected_tx else b"VFOA\n"
        elif command not in {
            "set_freq",
            "set_mode",
            "set_ptt",
            "set_split_freq",
            "set_split_mode",
            "set_split_vfo",
            "set_vfo",
            "set_ctcss_tone",
            "set_func",
        }:
            normal = b"RPRT -1\n"

        if action != "nak":
            try:
                if command == "set_freq":
                    self.state.frequency_hz = int(float(parts[1]))
                elif command == "set_mode":
                    self.state.mode = parts[1].upper()
                elif command == "set_ptt":
                    self.state.ptt = parts[1] != "0"
                elif command == "set_split_freq":
                    self.state.tx_frequency_hz = int(float(parts[1]))
                elif command == "set_split_mode":
                    self.state.tx_mode = parts[1].upper()
                elif command == "set_split_vfo":
                    self.state.split = parts[1] != "0"
                elif command == "set_vfo":
                    self.state.selected_tx = parts[1].upper() in {"VFOB", "SUB", "TX"}
                elif command == "set_ctcss_tone":
                    self.state.ctcss_tenths_hz = int(parts[1])
                elif command == "set_func" and parts[1].upper() == "TONE":
                    self.state.ctcss_enabled = parts[2] != "0"
            except (IndexError, ValueError):
                normal = b"RPRT -1\n"

        return _reply_for_action(action, normal, malformed, b"RPRT -1\n")


class SimulatorTcpServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, address: tuple[str, int], simulator: ProtocolSimulator, once: bool) -> None:
        self.simulator = simulator
        self.once = once
        super().__init__(address, SimulatorTcpHandler)


class SimulatorTcpHandler(socketserver.BaseRequestHandler):
    def handle(self) -> None:
        server = self.server
        assert isinstance(server, SimulatorTcpServer)
        if server.simulator.verbose:
            print(f"client connected: {self.client_address[0]}:{self.client_address[1]}", flush=True)
        try:
            while True:
                data = self.request.recv(4096)
                if not data:
                    break
                for reply in server.simulator.feed(data):
                    for chunk in reply.chunks:
                        self.request.sendall(chunk)
                        if len(reply.chunks) > 1:
                            time.sleep(reply.inter_chunk_delay)
                    if reply.close:
                        return
        finally:
            if server.simulator.verbose:
                print("client disconnected", flush=True)
            if server.once:
                threading.Thread(target=server.shutdown, daemon=True).start()


def parse_tcp_endpoint(value: str) -> tuple[str, int]:
    value = value.strip()
    if value.startswith("["):
        closing = value.find("]")
        if closing <= 1 or value[closing + 1 : closing + 2] != ":":
            raise ValueError("IPv6 endpoints must use [address]:port")
        host, port_text = value[1:closing], value[closing + 2 :]
    else:
        if value.count(":") != 1:
            raise ValueError("TCP endpoint must use host:port")
        host, port_text = value.rsplit(":", 1)
    port = int(port_text)
    if not host or not 1 <= port <= 65535:
        raise ValueError("TCP endpoint must use a non-empty host and port 1..65535")
    return host, port


def _write_replies(stream: BinaryIO, replies: Iterable[SimulatorReply]) -> bool:
    for reply in replies:
        for chunk in reply.chunks:
            stream.write(chunk)
            stream.flush()
            if len(reply.chunks) > 1:
                time.sleep(reply.inter_chunk_delay)
        if reply.close:
            return False
    return True


def run_serial(simulator: ProtocolSimulator, port: str, baud: int, stop_bits: int) -> None:
    try:
        import serial  # type: ignore[import-not-found]
    except ImportError as error:
        raise SystemExit("serial mode requires pyserial: py -m pip install pyserial") from error
    with serial.Serial(
        port=port,
        baudrate=baud,
        bytesize=serial.EIGHTBITS,
        parity=serial.PARITY_NONE,
        stopbits=serial.STOPBITS_TWO if stop_bits == 2 else serial.STOPBITS_ONE,
        timeout=0.1,
        write_timeout=1,
    ) as stream:
        print(f"READY model={simulator.model} serial={port} baud={baud} 8N{stop_bits}", flush=True)
        while True:
            data = stream.read(4096)
            if data and not _write_replies(stream, simulator.feed(data)):
                return


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Simulate the radios supported by Look4Sat over raw CAT or rigctld.",
        epilog=(
            "Fault actions: ok, drop, nak, malformed, disconnect, wrong_address, "
            "delay:MILLISECONDS, partial:BYTES. Commands include connect, read_frequency, "
            "read_mode, set_frequency, set_mode, ptt_on, ptt_off, and split variants."
        ),
    )
    parser.add_argument("--model", choices=MODEL_CONFIG, default="ic9700")
    connection = parser.add_mutually_exclusive_group()
    connection.add_argument("--tcp", default=None, metavar="HOST:PORT")
    connection.add_argument("--serial", metavar="COM_PORT")
    parser.add_argument("--baud", type=int, default=4800)
    parser.add_argument("--frequency", type=int, default=145_900_000)
    parser.add_argument("--tx-frequency", type=int, default=435_100_000)
    parser.add_argument("--mode", default="USB")
    parser.add_argument("--tx-mode", default="LSB")
    parser.add_argument("--fault-script", metavar="FILE.json")
    parser.add_argument("--fault", action="append", default=[], metavar="COMMAND=ACTION")
    parser.add_argument("--echo", action="store_true", help="echo CI-V request frames before replies")
    parser.add_argument("--quiet", action="store_true")
    parser.add_argument("--once", action="store_true", help="exit after the first TCP client disconnects")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    protocol, _, stop_bits = MODEL_CONFIG[args.model]
    if protocol == "rigctld" and args.serial:
        raise SystemExit("rigctld simulation is TCP only")
    mode = args.mode.upper()
    tx_mode = args.tx_mode.upper()
    supported_modes = YAESU_MODE_TO_BYTE if protocol == "yaesu" else ICOM_MODE_TO_BYTE
    if protocol != "rigctld" and (mode not in supported_modes or tx_mode not in supported_modes):
        raise SystemExit(f"unsupported mode for {args.model}: {mode}/{tx_mode}")
    faults = FaultPlan.load(args.fault_script, args.fault)
    simulator = ProtocolSimulator(
        model=args.model,
        state=RadioState(
            frequency_hz=args.frequency,
            tx_frequency_hz=args.tx_frequency,
            mode=mode,
            tx_mode=tx_mode,
        ),
        faults=faults,
        echo=args.echo,
        verbose=not args.quiet,
    )
    if args.serial:
        run_serial(simulator, args.serial, args.baud, stop_bits)
        return 0

    endpoint = parse_tcp_endpoint(args.tcp or "127.0.0.1:4532")
    with SimulatorTcpServer(endpoint, simulator, args.once) as server:
        host, port = server.server_address[:2]
        print(f"READY model={args.model} tcp={host}:{port}", flush=True)
        try:
            server.serve_forever(poll_interval=0.1)
        except KeyboardInterrupt:
            pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
