import socket
import sys
import threading
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from radio_simulator import (  # noqa: E402
    FaultPlan,
    ICOM_MODE_TO_BYTE,
    ProtocolSimulator,
    RadioState,
    SimulatorTcpServer,
    YAESU_MODE_TO_BYTE,
    decode_icom_frequency,
    decode_yaesu_frequency,
    encode_icom_frequency,
    encode_yaesu_frequency,
)


class RadioSimulatorTest(unittest.TestCase):
    def test_yaesu_models_handshake_and_writes(self):
        for model in ("ft817", "ft857"):
            with self.subTest(model=model):
                state = RadioState()
                simulator = ProtocolSimulator(model, state, FaultPlan(), verbose=False)
                read = simulator.feed(b"\0\0\0\0\x03")[0].chunks[0]
                self.assertEqual(145_900_000, decode_yaesu_frequency(read[:4]))
                self.assertEqual(YAESU_MODE_TO_BYTE["USB"], read[4])

                self.assertEqual([b"\0"], simulator.feed(encode_yaesu_frequency(145_925_000) + b"\x01")[0].chunks)
                self.assertEqual(145_925_000, state.frequency_hz)
                simulator.feed(b"\x08\0\0\0\x07")
                self.assertEqual("FM", state.mode)

    def test_all_icom_models_handshake_read_and_write(self):
        for model, address in (("ic705", 0xA4), ("ic9700", 0xA2), ("ic910", 0x60)):
            with self.subTest(model=model):
                state = RadioState()
                simulator = ProtocolSimulator(model, state, FaultPlan(), verbose=False)

                connect = bytes([0xFE, 0xFE, address, 0xE0, 0x07, 0xFD])
                self.assertEqual(0xFB, simulator.feed(connect)[0].chunks[0][4])
                set_frequency = bytes([0xFE, 0xFE, address, 0xE0, 0x05]) + encode_icom_frequency(145_925_000) + b"\xFD"
                self.assertEqual(0xFB, simulator.feed(set_frequency)[0].chunks[0][4])
                self.assertEqual(145_925_000, state.frequency_hz)
                read_frequency = bytes([0xFE, 0xFE, address, 0xE0, 0x03, 0xFD])
                response = simulator.feed(read_frequency)[0].chunks[0]
                self.assertEqual(145_925_000, decode_icom_frequency(response[5:10]))

                set_mode = bytes([
                    0xFE, 0xFE, address, 0xE0, 0x06, ICOM_MODE_TO_BYTE["FM"], 0x01, 0xFD
                ])
                self.assertEqual(0xFB, simulator.feed(set_mode)[0].chunks[0][4])
                self.assertEqual("FM", state.mode)
                read_mode = bytes([0xFE, 0xFE, address, 0xE0, 0x04, 0xFD])
                mode_response = simulator.feed(read_mode)[0].chunks[0]
                self.assertEqual(ICOM_MODE_TO_BYTE["FM"], mode_response[5])

    def test_faults_are_consumed_and_can_fragment_responses(self):
        simulator = ProtocolSimulator(
            "ic705",
            RadioState(),
            FaultPlan({"read_frequency": ["malformed", "partial:2"]}),
            verbose=False,
        )
        command = b"\xFE\xFE\xA4\xE0\x03\xFD"
        malformed = simulator.feed(command)[0]
        self.assertEqual(b"\xFA", malformed.chunks[0][5:6])
        fragmented = simulator.feed(command)[0]
        self.assertGreater(len(fragmented.chunks), 1)
        self.assertEqual(b"\xFE\xFE", fragmented.chunks[0])

    def test_icom_selected_and_unselected_vfo_do_not_swap_while_keyed(self):
        state = RadioState(ptt=True, selected_tx=False)
        simulator = ProtocolSimulator("ic705", state, FaultPlan(), verbose=False)
        prefix = b"\xFE\xFE\xA4\xE0\x25"

        simulator.feed(prefix + b"\x00" + encode_icom_frequency(145_925_000) + b"\xFD")
        simulator.feed(prefix + b"\x01" + encode_icom_frequency(435_125_000) + b"\xFD")

        self.assertEqual(145_925_000, state.frequency_hz)
        self.assertEqual(435_125_000, state.tx_frequency_hz)
        selected = simulator.feed(prefix + b"\x00\xFD")[0].chunks[0]
        unselected = simulator.feed(prefix + b"\x01\xFD")[0].chunks[0]
        self.assertEqual(145_925_000, decode_icom_frequency(selected[6:11]))
        self.assertEqual(435_125_000, decode_icom_frequency(unselected[6:11]))

    def test_rigctld_stateful_commands_and_abnormal_response(self):
        state = RadioState()
        simulator = ProtocolSimulator(
            "rigctld",
            state,
            FaultPlan({"read_mode": ["malformed"]}),
            verbose=False,
        )
        self.assertEqual(b"145900000\n", simulator.feed(b"\\get_freq\n")[0].chunks[0])
        self.assertEqual(b"RPRT 0\n", simulator.feed(b"\\set_freq 145925000\n")[0].chunks[0])
        self.assertEqual(145_925_000, state.frequency_hz)
        self.assertEqual(b"garbage\n", simulator.feed(b"\\get_mode\n")[0].chunks[0])
        self.assertEqual(b"USB\n0\n", simulator.feed(b"\\get_mode\n")[0].chunks[0])

    def test_tcp_server_accepts_fragmented_request(self):
        simulator = ProtocolSimulator("rigctld", RadioState(), FaultPlan(), verbose=False)
        with SimulatorTcpServer(("127.0.0.1", 0), simulator, once=False) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                with socket.create_connection(server.server_address, timeout=1) as client:
                    client.sendall(b"\\get_")
                    client.sendall(b"freq\n")
                    self.assertEqual(b"145900000\n", client.recv(128))
            finally:
                server.shutdown()
                thread.join(timeout=1)

    def test_all_models_ptt_on_off_and_rejected_keying(self):
        for model in ("ft817", "ft857", "ic705", "ic9700", "ic910", "rigctld"):
            with self.subTest(model=model):
                state = RadioState()
                simulator = ProtocolSimulator(
                    model, state, FaultPlan({"ptt_on": ["nak"]}), verbose=False
                )
                if model == "rigctld":
                    key, unkey = b"\\set_ptt 1\n", b"\\set_ptt 0\n"
                elif model.startswith("ft"):
                    key, unkey = b"\0\0\0\0\x08", b"\0\0\0\0\x88"
                else:
                    address = {"ic705": 0xA4, "ic9700": 0xA2, "ic910": 0x60}[model]
                    prefix = bytes([0xFE, 0xFE, address, 0xE0, 0x1C, 0x00])
                    key, unkey = prefix + b"\x01\xFD", prefix + b"\x00\xFD"
                simulator.feed(key)
                self.assertFalse(state.ptt)
                simulator.feed(key)
                self.assertTrue(state.ptt)
                simulator.feed(unkey)
                self.assertFalse(state.ptt)


if __name__ == "__main__":
    unittest.main()
