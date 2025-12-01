"""本地调试用的简易 HTTP 服务器，负责打印设备上传的生命体征数据。

用 PowerShell 启动示例：
	python server.py --host 10.242.98.103 --port 8080
"""

from __future__ import annotations

import argparse
import logging
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Tuple

import json
from json import JSONDecodeError


class LoggingRequestHandler(BaseHTTPRequestHandler):
	"""HTTP handler that dumps request details to stdout."""

	server_version = "MessageSendTestServer/1.0"

	def log_message(self, format: str, *args) -> None:  # type: ignore[override]
		logging.info("%s - %s", self.client_address[0], format % args)

	def do_POST(self) -> None:  # noqa: N802 (BaseHTTPRequestHandler naming)
		body = self._read_body()
		self._log_payload(body)
		self._send_ok()

	def do_PUT(self) -> None:  # noqa: N802
		body = self._read_body()
		self._log_payload(body)
		self._send_ok()

	def do_GET(self) -> None:  # noqa: N802
		self._log_payload(b"")
		self._send_ok()

	def _read_body(self) -> bytes:
		length_header = self.headers.get("Content-Length")
		if not length_header:
			return b""
		try:
			length = int(length_header)
		except ValueError:
			return b""
		return self.rfile.read(length)

	def _log_payload(self, body: bytes) -> None:
		if not body:
			logging.info("No payload received")
			return
		try:
			decoded = body.decode("utf-8")
		except UnicodeDecodeError:
			logging.info("Raw payload (unable to decode as UTF-8): %s", body)
			return
		try:
			data = json.loads(decoded)
		except JSONDecodeError:
			logging.info("Raw payload: %s", decoded)
			return
		self._log_vitals(data)

	def _log_vitals(self, data: dict) -> None:
		user_id = data.get("userid", "unknown")
		device_id = data.get("deviceId", self.client_address[0])
		timestamp = data.get("timestamp", "unknown")

		def fmt(key: str, label: str, unit: str = "") -> str:
			value = data.get(key)
			if value is None:
				return f"{label}: N/A"
			suffix = f" {unit}" if unit else ""
			return f"{label}: {value}{suffix}"

		spo2_wave = data.get("boWaveSamples")
		if isinstance(spo2_wave, list):
			wave_summary = f"Blood Oxygen Waveform Samples: {len(spo2_wave)} points"
		else:
			wave_summary = "Blood Oxygen Waveform Samples: N/A"

		lines = [
			f"Vitals Report (user: {user_id}, device: {device_id}, timestamp: {timestamp})",
			fmt("hr", "Heart Rate", "bpm"),
			fmt("hr2", "Heart Rate Secondary", "bpm"),
			fmt("bp", "Blood Pressure", "mmHg"),
			fmt("bo", "Blood Oxygen Saturation", "%"),
			fmt("temp", "Body Temperature", "degC"),
			fmt("ecg", "Electrocardiogram"),
			wave_summary,
			fmt("respWave", "Respiration Waveform"),
		]

		known_keys = {"userid", "deviceId", "timestamp", "hr", "hr2", "bp", "bo", "temp", "ecg", "boWave", "respWave"}
		known_keys.add("boWaveSamples")
		extra_pairs = {k: v for k, v in data.items() if k not in known_keys}
		if extra_pairs:
			lines.append("Additional Fields:")
			for key, value in extra_pairs.items():
				lines.append(f"  {key}: {value}")

		logging.info("\n".join(lines))

	def _send_ok(self) -> None:
		payload = json.dumps({"status": "ok"}).encode("utf-8")
		self.send_response(200)
		self.send_header("Content-Type", "application/json; charset=utf-8")
		self.send_header("Content-Length", str(len(payload)))
		self.end_headers()
		self.wfile.write(payload)


def parse_args() -> Tuple[str, int]:
	parser = argparse.ArgumentParser(description="Log incoming HTTP requests from BLE device uploads.")
	parser.add_argument("--host", default="10.242.98.103", help="IP address to bind (default: 10.242.98.103)")
	parser.add_argument("--port", type=int, default=8080, help="TCP port to listen on (default: 8080)")
	args = parser.parse_args()
	return args.host, args.port


def main() -> None:
	logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
	host, port = parse_args()
	server = ThreadingHTTPServer((host, port), LoggingRequestHandler)
	logging.info("Server listening on http://%s:%d", host, port)
	try:
		server.serve_forever()
	except KeyboardInterrupt:
		logging.info("Shutting down server...")
	finally:
		server.server_close()


if __name__ == "__main__":
	main()
