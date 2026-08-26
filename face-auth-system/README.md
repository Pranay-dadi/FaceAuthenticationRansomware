# FaceAuth — Single-Image Synthetic Face Authentication System

> CS402M Computer Systems Security · Adversarial ML Assignment
> Dataset tag: `v1.0-dataset`

---

## Quick-start (copy-paste order)

```bash
# 1. Clone the repo
git clone https://github.com/YOUR_USERNAME/face-auth-system.git
cd face-auth-system

# 2. Create Python environment
python3.10 -m venv venv
source venv/bin/activate          # Windows: venv\Scripts\activate

# 3. Install dependencies
pip install -r dataset_generation/requirements.txt
pip install -r model_training/requirements.txt

# 4. Place your two source images
cp /path/to/your_face.jpg   dataset_generation/class_a.jpg
cp /path/to/target_face.jpg dataset_generation/class_b.jpg

# 5. Generate dataset → train → export → copy to Android
cd dataset_generation
python generate_synthetic.py --source_a class_a.jpg --source_b class_b.jpg --num_images 500
python augment_dataset.py --target 500
python verify_dataset.py
cd ../model_training
python train_classifier.py --dataset_dir ../dataset_generation/dataset/final --output_dir ./model_output
python export_tflite.py --model ./model_output/best_phase2.keras --out ./tflite_export
cp tflite_export/face_classifier.tflite ../android_app/app/src/main/assets/

# 6. Start the payload server before running the Android demo
cd ..
python payload_server.py
```

Then open `android_app/` in Android Studio and run on an API-34 emulator.

---

## Repository layout

```
face-auth-system/
│
├── payload_server.py                ← C2 server: delivers AES key on the fly
│
├── dataset_generation/
│   ├── class_a.jpg                  ← YOUR source image (authenticated user)
│   ├── class_b.jpg                  ← TARGET source image
│   ├── requirements.txt
│   ├── generate_synthetic.py        ← Stage 1–3: pose, lighting, scale
│   ├── augment_dataset.py           ← Stage 4: conventional augmentation + split
│   └── verify_dataset.py            ← sanity check + contact sheet
│
├── model_training/
│   ├── requirements.txt
│   ├── train_classifier.py          ← MobileNetV2 two-phase training
│   ├── evaluate_model.py            ← confusion matrix, ROC, PR curves
│   ├── export_tflite.py             ← INT8 quantized TFLite export
│   └── diagnose_tflite.py           ← preprocessing range diagnostic
│
├── android_app/
│   ├── build.gradle
│   ├── settings.gradle
│   ├── gradle.properties
│   ├── gradle/wrapper/gradle-wrapper.properties
│   └── app/
│       ├── build.gradle
│       ├── proguard-rules.pro
│       └── src/main/
│           ├── AndroidManifest.xml           ← includes INTERNET permission
│           ├── assets/
│           │   └── face_classifier.tflite    ← copied from tflite_export/
│           ├── java/com/faceauth/app/
│           │   ├── MainActivity.kt           ← creates demo files on startup
│           │   ├── CameraActivity.kt
│           │   ├── AuthSuccessActivity.kt
│           │   ├── AuthFailActivity.kt       ← orchestrates 4-stage response
│           │   ├── ml/
│           │   │   ├── FaceClassifier.kt
│           │   │   └── FaceOverlayView.kt
│           │   ├── security/
│           │   │   ├── DemoFileCreator.kt    ← creates sensitive demo files
│           │   │   ├── PayloadFetcher.kt     ← pulls encryption key from C2
│           │   │   └── FileEncryptor.kt      ← AES-256-CBC (key-less until fetch)
│           │   └── util/
│           │       ├── AuthLogger.kt
│           │       └── FilesystemLocker.kt
│           └── res/
│               ├── layout/                   ← 4 activity XML layouts
│               ├── drawable/                 ← 7 vector drawables
│               ├── xml/
│               │   └── network_security_config.xml  ← allows HTTP to 10.0.2.2
│               └── values/
│
├── dataset/
│   └── final/                       ← 1000 images across 2 classes × 3 splits
│       ├── train/  class_a(400)  class_b(400)
│       ├── val/    class_a(50)   class_b(50)
│       └── test/   class_a(50)   class_b(50)
│
├── README.md
└── REPORT_DISCUSSION.md
```

---

## Part 1 — Input images

| Class | Role | Requirement |
|-------|------|-------------|
| `class_a.jpg` | Authenticated user | Frontal face, neutral expression, good lighting, ≥ 256×256 px |
| `class_b.jpg` | Target / unauthorised | Same quality requirements |

Each class starts from **exactly one** source image. All 500+ samples are generated synthetically from that single photograph.

---

## Part 2 — Single-image synthetic generation algorithm

### Pipeline overview

```
Single source image (1×)
        │
        ▼
[Stage 1] Geometric pose simulation
          5 pitch × 5 yaw × 3 roll angles × 8 lighting modes = 600 combinations
        │
        ▼
[Stage 2] Scale × lighting grid
          7 scale factors × 5 lighting modes = 35 more
        │
        ▼
[Stage 3] Random multi-operation compositions
          Random 1–4 operations from {pose, light, scale, jpeg, blur}
          until N = 500 reached
        │
        ▼
[Stage 4] Conventional augmentation
          Flip · Brightness · Rotation · Noise · Contrast · Saturation
          → fill to 500 per class → 80/10/10 split
```

### Stage 1 — Geometric pose simulation

**In-plane rotation (roll):**
`cv2.getRotationMatrix2D(centre, angle_z, 1.0)` rotates about the image centre with border reflection padding.

**Out-of-plane simulation (yaw + pitch):**
`cv2.getPerspectiveTransform(src_pts, dst_pts)` displaces four corner points by
`dx = w × tan(|yaw|) × 0.28` and `dy = h × tan(|pitch|) × 0.18`,
compressing one side of the face to simulate 3-DOF head rotation without a 3D model.

### Stage 2 — Illumination variation (8 modes)

| Mode | Operation |
|------|-----------|
| `bright` | Brightness × [1.2, 1.8] |
| `dark` | Brightness × [0.3, 0.7] |
| `contrast` | Contrast × [0.4, 2.0] |
| `warm` | R += [20,40]; B -= [15,30] |
| `cool` | B += [20,40]; R -= [15,30] |
| `shadow` | Directional linear gradient mask, strength [0.3, 0.6], 4 directions |
| `overexposed` | High brightness + low contrast |
| `flat` | No change (identity) |

### Stage 3 — Scale variation

`cv2.resize` to `s × 224` then centre-crop (s > 1) or reflection-pad (s < 1) back to 224 × 224.

### Stage 4 — JPEG + blur simulation

JPEG re-encoding at quality ∈ [60, 95] introduces compression block artefacts.
Gaussian blur with kernel ∈ {3, 5} px simulates camera defocus.

---

## Part 3 — Synthetic generation hyperparameters

| Parameter | Value |
|-----------|-------|
| `POSE_RANGE_X` (pitch) | ±15° |
| `POSE_RANGE_Y` (yaw) | ±20° |
| `POSE_RANGE_Z` (roll) | ±10° |
| `BRIGHTNESS_RANGE` | [0.30, 1.80] |
| `CONTRAST_RANGE` | [0.40, 2.00] |
| `SCALE_RANGE` | [0.75, 1.30] |
| `JPEG_QUALITY_RANGE` | [60, 95] |
| `BLUR_KERNELS` | {3, 5} px |
| `OUTPUT_SIZE` | 224 × 224 |
| `RANDOM_SEED` | 42 |
| `NUM_IMAGES_PER_CLASS` | 500 |

---

## Part 4 — Conventional augmentation pipeline

| Transform | Probability | Range |
|-----------|-------------|-------|
| Horizontal flip | 0.50 | — |
| Brightness jitter | 0.70 | factor ∈ [0.60, 1.40] |
| Rotation | 0.60 | angle ∈ [−15°, +15°] |
| Gaussian noise | 0.50 | σ ∈ [5, 25] |
| Contrast jitter | 0.40 | factor ∈ [0.70, 1.50] |
| Saturation jitter | 0.30 | factor ∈ [0.70, 1.30] |

At least one augmentation is guaranteed per image (enforced in code).
Multiple transforms are composed in a single forward pass.

---

## Part 5 — Dataset split

| Split | Ratio | class_a | class_b | Total |
|-------|-------|---------|---------|-------|
| train | 80% | 400 | 400 | 800 |
| val | 10% | 50 | 50 | 100 |
| test | 10% | 50 | 50 | 100 |
| **Total** | | **500** | **500** | **1000** |

`random_state = 42`. Stratified by class. Verified with `verify_dataset.py` (0 corrupt images).

---

## Part 6 — Model architecture

### Base model
MobileNetV2 pretrained on ImageNet-1k (224 × 224 × 3 input, 154 layers, 2.26M parameters).

### Classification head
```
MobileNetV2 (frozen in Phase 1, top 54 layers unfrozen in Phase 2)
    → GlobalAveragePooling2D          [1280-dim feature vector]
    → Dropout(0.30)
    → Dense(128, relu, L2=1e-4)
    → Dropout(0.15)
    → Dense(2, softmax)               [class_a prob, class_b prob]
```

### Preprocessing
The model embeds `mobilenet_v2.preprocess_input` as a Keras layer.
Input: pixel values ∈ [0, 1]. Internal: ×255 → [−1, 1] by preprocess_input.

### Training protocol

**Phase 1 — frozen base (train head only)**

| Hyperparameter | Value |
|---------------|-------|
| Learning rate | 1e-3 |
| Batch size | 32 |
| Epochs (max) | 10 |
| Optimizer | Adam (β₁=0.9, β₂=0.999) |
| Loss | Categorical cross-entropy |
| Early stopping patience | 5 (monitor: val_accuracy) |
| LR scheduler | ReduceLROnPlateau (factor=0.5, patience=3) |

**Phase 2 — fine-tune top layers (unfreeze layers 100–154)**

| Hyperparameter | Value |
|---------------|-------|
| Learning rate | 1e-5 |
| Fine-tune from layer | 100 / 154 |
| Batch size | 32 |
| Epochs (max) | 20 |
| Early stopping patience | 5 |

### Inference threshold
Class B is flagged **only if** `P(class_b) ≥ 0.85`.

### Results achieved

| Metric | Value |
|--------|-------|
| Test accuracy (argmax) | 100% |
| Test accuracy (threshold=0.85) | 100% |
| ROC-AUC | 1.0 |
| PR-AUC | 1.0 |

---

## Part 7 — TFLite export

| Format | Size |
|--------|------|
| Float32 | 9.3 MB |
| INT8 quantized | 2.6 MB |

Quantization: dynamic-range INT8 (`tf.lite.Optimize.DEFAULT`). Verified: output sum = 1.0000.

---

## Part 8 — Android app + on-the-fly encryption

### Security architecture

```
Host machine (payload_server.py)       Android Emulator (FaceAuth app)
══════════════════════════════         ════════════════════════════════════
Holds at runtime:                      On startup:
  • AES-256 key (fresh per session)      • Creates 5 plaintext demo files
  • IV                                   • APK contains NO encryption key
  • Target file extensions
  • Ransom note text                   On Class B detected (P ≥ 0.85):
                                         1. HTTP GET /payload → key received
◄── POST /report (file list) ─────────   2. Encrypts demo files with key
GET /payload ──────────────────────►     3. Reports file list back to server
HTTP 200 + JSON payload                  4. Shows before/after filesystem
```

**The APK contains zero encryption keys.** The encryption code is completely inert without a live payload server — an accurate model of real ransomware C2 architecture.

### Authentication paths

**Path A — Class A matched (authenticated user):**
1. CameraX captures live frames
2. ML Kit detects a face
3. TFLite classifier: `P(class_a) > P(class_b)` with `P(class_b) < 0.85`
4. 5-frame majority vote confirms Class A
5. `AuthSuccessActivity`: confidence %, timestamp, auth event log

**Path B — Class B detected (target / unauthorised) — 4-stage response:**

| Stage | Action |
|-------|--------|
| Stage 1 | Snapshot filesystem BEFORE encryption — list all demo files |
| Stage 2 | Contact `http://10.0.2.2:8888/payload` — receive AES-256 key + IV on the fly |
| Stage 3 | Encrypt all `.txt .json .log .csv` files with fetched key; write ransom note |
| Stage 4 | Android filesystem probe — all system paths return `PERMISSION DENIED` |

### Demo files created on startup

```
/data/data/com.faceauth.app/files/secure_docs/
    employee_records.txt      — employee names, salaries
    system_config.json        — API keys, database credentials
    access_log.log            — system access events
    financial_report_q3.csv   — quarterly revenue data
    passwords_backup.txt      — service credentials
```

### Before encryption (ADB)

```bash
adb shell run-as com.faceauth.app ls -la files/secure_docs/
# employee_records.txt  823 bytes  PLAINTEXT
# system_config.json    401 bytes  PLAINTEXT

adb shell run-as com.faceauth.app cat files/secure_docs/employee_records.txt
# EMPLOYEE RECORDS — CONFIDENTIAL  [readable]
```

### After encryption (ADB)

```bash
adb shell run-as com.faceauth.app ls -la files/secure_docs/
# employee_records.txt.enc  848 bytes  ENCRYPTED
# system_config.json.enc    416 bytes  ENCRYPTED
# README_ENCRYPTED.txt      389 bytes  RANSOM NOTE

adb shell run-as com.faceauth.app xxd files/secure_docs/employee_records.txt.enc | head
# 00000000: a3f7 2b19 c841 9e02 7d35 8814 ...  [binary ciphertext]
```

### Payload server endpoints

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/payload` | GET | Deliver AES key + IV + parameters to app |
| `/status` | GET | Fetch count, session info |
| `/files` | GET | List reported encrypted files |
| `/report` | POST | App reports which files were encrypted |
| `/health` | GET | Simple health check |

### Key Android components

| File | Package | Responsibility |
|------|---------|---------------|
| `DemoFileCreator.kt` | `security` | Creates 5 plaintext demo files on app startup |
| `PayloadFetcher.kt` | `security` | HTTP GET to payload server; returns key + params |
| `FileEncryptor.kt` | `security` | AES-256-CBC encryption (algorithm only, no key) |
| `AuthFailActivity.kt` | root | Orchestrates 4-stage security response |
| `FaceClassifier.kt` | `ml` | TFLite inference with `buf.rewind()` fix |
| `AuthLogger.kt` | `util` | JSON event log in `filesDir` |
| `FilesystemLocker.kt` | `util` | Cache wipe + filesystem probe |

---

## Part 9 — Step-by-step implementation instructions

### Prerequisites

| Software | Version | Notes |
|----------|---------|-------|
| Python | 3.10 or 3.11 | Do NOT use 3.12 (TF not supported) |
| Android Studio | Hedgehog 2023.1.1+ | Includes JDK 17 |
| Git | Any recent | For publishing |

---

### Phase 1 — Python environment

```bash
python3.10 -m venv venv
source venv/bin/activate

pip install --upgrade pip
pip install -r dataset_generation/requirements.txt
pip install -r model_training/requirements.txt

python -c "import cv2; print('cv2 OK', cv2.__version__)"
python -c "import mediapipe; print('mediapipe OK')"
python -c "import tensorflow as tf; print('TF OK', tf.__version__)"
```

---

### Phase 2 — Prepare source images

```
dataset_generation/
    class_a.jpg    ← your own face, frontal, ≥ 256×256 px
    class_b.jpg    ← target face, same requirements
```

---

### Phase 3 — Generate synthetic dataset

```bash
cd dataset_generation
python generate_synthetic.py --source_a class_a.jpg --source_b class_b.jpg --num_images 500
python augment_dataset.py --target 500
python verify_dataset.py --dataset_dir ./dataset/final
```

Must print `✓ PASSED` before continuing.

---

### Phase 4 — Train the model

```bash
cd ../model_training
python train_classifier.py \
  --dataset_dir ../dataset_generation/dataset/final \
  --output_dir ./model_output
```

Optional evaluation:

```bash
python evaluate_model.py \
  --model ./model_output/best_phase2.keras \
  --dataset_dir ../dataset_generation/dataset/final \
  --output_dir ./model_output/eval_report
```

---

### Phase 5 — Export to TFLite and copy to Android

```bash
python export_tflite.py \
  --model ./model_output/best_phase2.keras \
  --out   ./tflite_export

cp tflite_export/face_classifier.tflite \
   ../android_app/app/src/main/assets/face_classifier.tflite

ls -lh ../android_app/app/src/main/assets/face_classifier.tflite
# Must be ~2–3 MB
```

---

### Phase 6 — Verify class label order

```bash
python3 -c "
from tensorflow.keras.preprocessing.image import ImageDataGenerator
g = ImageDataGenerator(rescale=1./255).flow_from_directory(
    '../dataset_generation/dataset/final/train',
    target_size=(224,224), batch_size=1, class_mode='categorical')
print(g.class_indices)
"
```

Expected: `{'class_a': 0, 'class_b': 1}`. If reversed, swap `CLASS_A_IDX`/`CLASS_B_IDX` in `FaceClassifier.kt`.

---

### Phase 7 — Copy new security files into Android project

```bash
cd face-auth-system

# Create security package directory and xml directory
mkdir -p android_app/app/src/main/java/com/faceauth/app/security
mkdir -p android_app/app/src/main/res/xml

# Copy new files (downloaded from Claude)
cp DemoFileCreator.kt  android_app/app/src/main/java/com/faceauth/app/security/
cp PayloadFetcher.kt   android_app/app/src/main/java/com/faceauth/app/security/
cp FileEncryptor.kt    android_app/app/src/main/java/com/faceauth/app/security/

# Replace existing files
cp AuthFailActivity.kt android_app/app/src/main/java/com/faceauth/app/
cp MainActivity.kt     android_app/app/src/main/java/com/faceauth/app/
cp activity_auth_fail.xml android_app/app/src/main/res/layout/
cp AndroidManifest.xml    android_app/app/src/main/
cp network_security_config.xml android_app/app/src/main/res/xml/
```

---

### Phase 8 — Android Studio setup

1. **File → Open** → select `face-auth-system/android_app/`
2. Wait for Gradle sync (~500 MB on first run)
3. If sync fails: **File → Sync Project with Gradle Files**

---

### Phase 9 — Create Android emulator

1. **Tools → Device Manager → Create Device**
2. **Phone → Pixel 6 → Next**
3. **API 34 (Android 14) — Google Play**
4. Advanced Settings: Front Camera → `Emulated`, RAM → `2048 MB`
5. Click Finish → start emulator

---

### Phase 10 — Generate launcher icon

Right-click `app/src/main/res` → **New → Image Asset** → leave defaults → **Next → Finish**

---

### Phase 11 — Build and install

```
Build → Clean Project
Build → Rebuild Project
Run → Run 'app'
```

Grant camera permission on first launch.

---

### Phase 12 — Start the payload server (BEFORE demoing Path B)

Open a terminal on the host machine:

```bash
cd face-auth-system
source venv/bin/activate
python payload_server.py
```

You will see the session AES-256 key printed — **copy this for file recovery after the demo.**

---

### Phase 13 — Demonstrate Path A (Class A success)

1. Emulator extended controls `⋮` → **Camera → Virtual Scene → Add image (+)**
2. Browse to `dataset_generation/class_a.jpg`
3. Tap **Begin Authentication** in the app

**Expected:** green box → `✓ Authorised face detected` → Authentication Successful screen with timestamp.

---

### Phase 14 — Demonstrate Path B (filesystem before/after)

Open **two ADB terminals** side by side.

**Terminal BEFORE (run before tapping Authenticate):**

```bash
# List plaintext files
adb shell run-as com.faceauth.app ls -la files/secure_docs/

# Read a file — should be human-readable
adb shell run-as com.faceauth.app cat files/secure_docs/employee_records.txt
```

**Trigger Class B:**
1. Inject `class_b.jpg` via emulator extended controls
2. Tap **Begin Authentication**
3. Watch **Window A** (payload server) — `⚡ PAYLOAD DELIVERED` prints when key is sent
4. Watch the app — 4 stages complete: Scan → Fetch → Encrypt → Probe

**Terminal AFTER (run after screen shows Stage 3 complete):**

```bash
# Files now have .enc extension
adb shell run-as com.faceauth.app ls -la files/secure_docs/

# Try to read — binary garbage
adb shell run-as com.faceauth.app \
  xxd files/secure_docs/employee_records.txt.enc | head -5

# Ransom note is readable
adb shell run-as com.faceauth.app \
  cat files/secure_docs/README_ENCRYPTED.txt
```

**Server-side confirmation:**

```bash
curl http://localhost:8888/status
curl http://localhost:8888/files
```

---

### Phase 15 — Demonstrate server controls encryption

Stop the payload server (Ctrl+C), clear the app, and retrigger Class B:

```bash
adb shell pm clear com.faceauth.app
adb shell am start -n com.faceauth.app/.MainActivity
```

With the server down, the app shows:

```
Stage 2/4 — FAILED
Payload server unreachable. Encryption key not obtained. Files remain unencrypted.
```

Files stay plaintext — proving the APK is inert without the C2 server.

---

### Phase 16 — Publish to GitHub

```bash
cd face-auth-system
git init
git lfs install
git lfs track "*.jpg"
git add .gitattributes
git add .
git commit -m "Complete face authentication system: dataset + model + Android app + payload server"
git tag v1.0-dataset
git remote add origin https://github.com/YOUR_USERNAME/face-auth-system.git
git push -u origin main
git push origin v1.0-dataset
```

---

### Common errors and fixes

| Error | Cause | Fix |
|-------|-------|-----|
| `ModuleNotFoundError: mediapipe` | venv not active | `source venv/bin/activate` |
| `FileNotFoundError: class_a.jpg` | Image not in `dataset_generation/` | Move both images there |
| Gradle sync failed | No internet / wrong SDK | **File → Project Structure → SDK Location** |
| `face_classifier.tflite` not found at runtime | Asset not copied | Re-run Phase 5 `cp` command |
| Class A gives 100% Class B | `buf.rewind()` missing or wrong index | Check `FaceClassifier.kt` and Phase 6 |
| Emulator camera black | Camera set to None | AVD Manager → Edit → Front Camera → Emulated |
| `CLEARTEXT HTTP not permitted` | `network_security_config.xml` missing | Copy the XML file and check `AndroidManifest.xml` references it |
| Stage 2 fails — server unreachable | `payload_server.py` not running | Start it in a separate terminal (Phase 12) |
| Files not encrypted after Stage 3 | Server returned error | Check server terminal for error message |

---

## Part 10 — Failure modes and distributional shift (Part 04 answer)

### Failure modes introduced by synthetic training data

**1. Domain gap (covariate shift)**
All 500 synthetic samples share the same underlying texture from the source photograph. Real camera captures produce statistics outside the training manifold.

**2. Limited intra-class identity variation**
One expression, one hairstyle, one set of accessories. Ageing or changing appearance produces faces the model has never seen.

**3. Synthetic artefact overfitting**
Perspective warp introduces reflection-padded borders absent from real photographs. The model may learn these as discriminating cues.

**4. Background entanglement**
The generator does not replace the background. The model may discriminate on background texture rather than face identity.

**5. Texture frequency bias**
Brightness/contrast adjustments preserve high-frequency texture exactly. Real sensors introduce demosaicing artefacts not present in synthetic data.

**6. Class B confidence leakage below threshold**
A real target under unseen conditions may score below 0.85, causing a false accept — the most security-critical failure.

### Mitigation steps taken

| Mitigation | How it helps |
|------------|-------------|
| 8 illumination modes | Covers warm/cool casts, directional shadows, over/underexposure |
| ImageNet transfer learning | 1.3M real-photo features supplement 500 synthetic images |
| Two-phase fine-tuning (unfreeze ≥ layer 100) | Preserves robust low-level features |
| Conservative threshold τ = 0.85 | Uncertain Class B predictions abstain |
| 5-frame temporal majority vote | Filters single-frame instabilities |
| Dropout at 0.30 and 0.15 | Prevents artefact-specific memorisation |
| ML Kit face detection gate | Rejects background-only frames |
| Server-controlled encryption key | APK is inert without C2 — models real ransomware |

---

## License

Dataset and code produced for academic assessment purposes only.
Target face images used solely for binary classification research within CS402M.
Not for commercial use.