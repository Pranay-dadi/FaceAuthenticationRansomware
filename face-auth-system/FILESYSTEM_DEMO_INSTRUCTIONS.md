# Filesystem Before/After Encryption — Step-by-Step Demo Guide

---

## Architecture of the on-the-fly pull

```
Host machine                         Android Emulator
─────────────────                    ─────────────────────────────────────
payload_server.py                    FaceAuth app
  │                                    │
  │  Holds:                            │  On startup:
  │   • AES-256 key (generated fresh)  │   • Creates 5 plaintext demo files
  │   • IV                             │   • No encryption key in APK
  │   • target extensions              │
  │   • ransom note text               │  On Class B detected:
  │                                    │   1. Contacts 10.0.2.2:8888/payload
  └──── HTTP GET /payload ────────────►│   2. Receives key + parameters
        HTTP 200 + JSON payload         │   3. Encrypts demo files
                                        │   4. Reports back via POST /report
  ◄──── POST /report ──────────────────┘
  Logs which files were encrypted
```

**The APK contains NO key, NO target list, NO ransom note.**
Without the server, the encryption code is completely inert.

---

## Terminal windows needed

Open **4 terminal windows** side by side:

| Window | Purpose |
|--------|---------|
| A | Run `payload_server.py` |
| B | Run ADB commands (before) |
| C | Android Studio / build |
| D | Run ADB commands (after) |

---

## Part 1 — Start the payload server

**Window A:**

```bash
cd face-auth-system
source venv/bin/activate
python payload_server.py
```

You will see:

```
=======================================================
  FaceAuth Payload Server
=======================================================
  Listening : http://0.0.0.0:8888
  Android   : http://10.0.2.2:8888/payload

  Session AES-256 key (for recovery):
  Key : <base64-encoded-key>
  IV  : <base64-encoded-iv>

  Waiting for Android app to fetch payload...
=======================================================
```

**Keep this running for the entire demo.** The key shown here is what you would use to decrypt the files after the demo (for recovery).

---

## Part 2 — Install and launch the app

**Window C:**

```bash
# Build and install
cd face-auth-system/android_app
./gradlew installDebug

# OR use Android Studio Run button
```

Launch the app on the emulator. You will briefly see:

```
FaceAuth v1.0  ·  5 demo files ready
```

This confirms the demo files were created.

---

## Part 3 — Inspect the filesystem BEFORE authentication

**Window B:**

```bash
# Step 1: List all demo files
adb shell run-as com.faceauth.app ls -la files/secure_docs/
```

Expected output:

```
-rw------- u0_a123 u0_a123  823 2026-08-23 18:52 employee_records.txt
-rw------- u0_a123 u0_a123  401 2026-08-23 18:52 system_config.json
-rw------- u0_a123 u0_a123  612 2026-08-23 18:52 access_log.log
-rw------- u0_a123 u0_a123  298 2026-08-23 18:52 financial_report_q3.csv
-rw------- u0_a123 u0_a123  445 2026-08-23 18:52 passwords_backup.txt
```

```bash
# Step 2: Read a plaintext file — should be fully readable
adb shell run-as com.faceauth.app cat files/secure_docs/employee_records.txt
```

Expected output:

```
EMPLOYEE RECORDS — CONFIDENTIAL
Generated: 2026-08-23 18:52:31
========================================
ID    | Name              | Department  | Salary
------|-------------------|-------------|--------
E001  | Alice Johnson     | Engineering | 92,000
...
```

```bash
# Step 3: Read the config file
adb shell run-as com.faceauth.app cat files/secure_docs/system_config.json
```

Expected output:

```json
{
  "app_version": "1.0.0",
  "database_host": "db.internal.company.com",
  "api_key": "sk-live-aBcDeFgHiJkLmNoPqRsTuVwXyZ123456",
  ...
}
```

```bash
# Step 4: Save the full directory listing for comparison
adb shell run-as com.faceauth.app find files/secure_docs/ -type f \
  -exec stat -c "%n %s bytes" {} \; > /tmp/before_encryption.txt
cat /tmp/before_encryption.txt
```

---

## Part 4 — Trigger Class B authentication

1. In the emulator: click the `⋮` button → **Camera → Virtual Scene**
2. Click `+` and add `class_b.jpg` from `dataset_generation/`
3. In the app tap **Begin Authentication**
4. Point the virtual camera at the Class B poster

**Watch Window A** — when the payload is fetched you will see:

```
=======================================================
  ⚡  PAYLOAD DELIVERED  (fetch #1)
  Key  : <first 24 chars>...
  IV   : <first 16 chars>...
  From : 10.0.2.2
=======================================================
```

**Watch the app** — you will see four stages complete:

```
Stage 1/4 — Scanning filesystem...
Stage 2/4 — Contacting payload server (http://10.0.2.2:8888)...
Stage 2/4 — PAYLOAD RECEIVED    ← key arrived from server
Stage 3/4 — Encrypting files...
Stage 3/4 — ENCRYPTION COMPLETE  ← 5 files encrypted
Stage 4/4 — Android Filesystem Probe
```

The app also shows an inline before/after panel with file names and sizes.

---

## Part 5 — Inspect the filesystem AFTER encryption

**Window D:**

```bash
# Step 1: List files again — names now have .enc extension
adb shell run-as com.faceauth.app ls -la files/secure_docs/
```

Expected output:

```
-rw------- u0_a123 u0_a123  848 2026-08-23 18:53 employee_records.txt.enc
-rw------- u0_a123 u0_a123  416 2026-08-23 18:53 system_config.json.enc
-rw------- u0_a123 u0_a123  624 2026-08-23 18:53 access_log.log.enc
-rw------- u0_a123 u0_a123  320 2026-08-23 18:53 financial_report_q3.csv.enc
-rw------- u0_a123 u0_a123  464 2026-08-23 18:53 passwords_backup.txt.enc
-rw------- u0_a123 u0_a123  389 2026-08-23 18:53 README_ENCRYPTED.txt
```

**Observations:**
- All `.txt`, `.json`, `.log`, `.csv` files are now `.enc`
- The ransom note `README_ENCRYPTED.txt` has been created
- Original plaintext files are gone (overwritten with zeros then deleted)

```bash
# Step 2: Try to cat an encrypted file — shows binary garbage
adb shell run-as com.faceauth.app cat files/secure_docs/employee_records.txt.enc
```

Expected output (unreadable binary):

```
<binary garbage — AES ciphertext — not human readable>
```

```bash
# Step 3: Hex dump to confirm it is real ciphertext (not just renamed)
adb shell run-as com.faceauth.app \
  xxd files/secure_docs/employee_records.txt.enc | head -8
```

Expected output:

```
00000000: a3f7 2b19 c841 9e02 7d35 8814 ffa0 2c61  ..+..A..}5....,a
00000010: 18b3 4d72 ee29 10c8 7f44 a1b9 3c05 8d27  ..Mr.)...D..<..'
00000020: 9d62 1e77 2a4e 31b4 6089 c3f5 0247 8e91  .b.w*N1.`....G..
...
```

```bash
# Step 4: Read the ransom note — this IS readable
adb shell run-as com.faceauth.app \
  cat files/secure_docs/README_ENCRYPTED.txt
```

Expected output:

```
=== ACADEMIC DEMONSTRATION ===
Your files have been encrypted by FaceAuth Security Demo.
This is a CS402M assignment demonstration only.
Unauthorised user detected via MobileNetV2 face classifier.
Encryption: AES-256-CBC  Key: fetched from payload server
Contact: [your-email]@university.edu to recover files.
==============================
```

```bash
# Step 5: Diff the before and after file listing
adb shell run-as com.faceauth.app find files/secure_docs/ -type f \
  -exec stat -c "%n %s bytes" {} \; > /tmp/after_encryption.txt

diff /tmp/before_encryption.txt /tmp/after_encryption.txt
```

Expected diff output:

```
< files/secure_docs/employee_records.txt 823 bytes
< files/secure_docs/system_config.json 401 bytes
< files/secure_docs/access_log.log 612 bytes
< files/secure_docs/financial_report_q3.csv 298 bytes
< files/secure_docs/passwords_backup.txt 445 bytes
---
> files/secure_docs/employee_records.txt.enc 848 bytes
> files/secure_docs/system_config.json.enc 416 bytes
> files/secure_docs/access_log.log.enc 624 bytes
> files/secure_docs/financial_report_q3.csv.enc 320 bytes
> files/secure_docs/passwords_backup.txt.enc 464 bytes
> files/secure_docs/README_ENCRYPTED.txt 389 bytes
```

---

## Part 6 — Check server-side logs

**Window A** (payload server terminal):

```bash
# In a new tab, query the server status
curl http://localhost:8888/status
```

Expected output:

```json
{
  "payload_fetches": 1,
  "fetch_times": ["18:53:22"],
  "encrypted_files": 5,
  "session_id": "aBcDeFgH"
}
```

```bash
# See which files were reported as encrypted
curl http://localhost:8888/files
```

Expected output:

```json
{
  "encrypted_files": [
    "/data/data/com.faceauth.app/files/secure_docs/employee_records.txt.enc",
    "/data/data/com.faceauth.app/files/secure_docs/system_config.json.enc",
    "/data/data/com.faceauth.app/files/secure_docs/access_log.log.enc",
    "/data/data/com.faceauth.app/files/secure_docs/financial_report_q3.csv.enc",
    "/data/data/com.faceauth.app/files/secure_docs/passwords_backup.txt.enc"
  ],
  "total": 5
}
```

---

## Part 7 — Verify server controls encryption

Demonstrate that without the server, nothing encrypts:

```bash
# Stop the payload server (Ctrl+C in Window A)
# Then reinstall a fresh app to get unencrypted files back
adb shell pm clear com.faceauth.app
adb shell am start -n com.faceauth.app/.MainActivity
```

Wait for demo files to be re-created, then trigger Class B again.

**App shows:**

```
Stage 2/4 — FAILED
Payload server unreachable. Encryption key not obtained. Files remain unencrypted.
Reason: Cannot reach payload server at 10.0.2.2:8888.
Is payload_server.py running?
```

**Files are untouched:**

```bash
adb shell run-as com.faceauth.app ls files/secure_docs/
# Still shows .txt files — not .enc — because no key was delivered
```

This proves the encryption code in the APK is **inert without the C2 server**.

---

## Part 8 — Recover the files (for demo reset)

Use the key printed by the payload server at startup to decrypt:

```python
# recovery.py  — run on host with the key from Window A
import base64, sys
from pathlib import Path
from Crypto.Cipher import AES  # pip install pycryptodome

KEY_B64 = "PASTE_KEY_FROM_PAYLOAD_SERVER_HERE"
IV_B64  = "PASTE_IV_FROM_PAYLOAD_SERVER_HERE"

key = base64.b64decode(KEY_B64)
iv  = base64.b64decode(IV_B64)

# Pull encrypted files from emulator first:
# adb shell run-as com.faceauth.app find files/secure_docs/ -name "*.enc" \
#   -exec sh -c 'cat "$1" > /sdcard/{}' _ {} \;
# adb pull /sdcard/files/  ./recovered/

enc_dir = Path("./recovered/secure_docs")
for enc_file in enc_dir.glob("*.enc"):
    ciphertext = enc_file.read_bytes()
    cipher     = AES.new(key, AES.MODE_CBC, iv)
    # Remove PKCS5 padding
    plaintext  = cipher.decrypt(ciphertext)
    pad_len    = plaintext[-1]
    plaintext  = plaintext[:-pad_len]
    out_path   = enc_file.with_suffix("")   # removes .enc
    out_path.write_bytes(plaintext)
    print(f"Recovered: {out_path.name}")
```

---

## Summary of what each command demonstrates

| Command | What it shows |
|---------|--------------|
| `ls -la files/secure_docs/` before | Plaintext files, human-readable names, normal sizes |
| `cat employee_records.txt` | File content is readable — sensitive data accessible |
| Trigger Class B | Payload fetched on the fly — key not in APK |
| `ls -la files/secure_docs/` after | All files renamed `.enc`, ransom note added |
| `cat employee_records.txt.enc` | Binary ciphertext — completely unreadable |
| `xxd employee_records.txt.enc` | Real AES ciphertext (high entropy, no patterns) |
| `curl localhost:8888/files` | Server received report of encrypted files |
| Stop server + retrigger | Files remain plaintext — code inert without C2 |
