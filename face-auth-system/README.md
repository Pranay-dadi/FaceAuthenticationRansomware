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
```

Then open `android_app/` in Android Studio and run on an API-34 emulator.

---

## Repository layout

```
face-auth-system/
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
│   ├── build.gradle                 ← project-level Gradle
│   ├── settings.gradle
│   ├── gradle.properties
│   ├── gradle/wrapper/
│   │   └── gradle-wrapper.properties
│   └── app/
│       ├── build.gradle             ← app-level Gradle
│       ├── proguard-rules.pro
│       └── src/main/
│           ├── AndroidManifest.xml
│           ├── assets/
│           │   └── face_classifier.tflite   ← copied from tflite_export/
│           ├── java/com/faceauth/app/
│           │   ├── MainActivity.kt
│           │   ├── CameraActivity.kt
│           │   ├── AuthSuccessActivity.kt
│           │   ├── AuthFailActivity.kt
│           │   ├── ml/
│           │   │   ├── FaceClassifier.kt
│           │   │   └── FaceOverlayView.kt
│           │   └── util/
│           │       ├── AuthLogger.kt
│           │       └── FilesystemLocker.kt
│           └── res/
│               ├── layout/          ← 4 activity XML layouts
│               ├── drawable/        ← 7 vector drawables
│               └── values/          ← colors, dimens, strings, themes
│
├── dataset/
│   └── final/                       ← 1000 images across 2 classes × 3 splits
│       ├── train/  class_a(400)  class_b(400)
│       ├── val/    class_a(50)   class_b(50)
│       └── test/   class_a(50)   class_b(50)
│
├── README.md                        ← this file
└── REPORT_DISCUSSION.md             ← Part 04 failure modes analysis
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
Input: pixel values ∈ [0, 1] (normalised by data generator and Android app).  
Internal: multiplied by 255 → scaled to [−1, 1] by preprocess_input.

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
Frames where neither class crosses threshold are discarded and re-scanned.

### Results achieved

| Metric | Value |
|--------|-------|
| Test accuracy (argmax) | 100% |
| Test accuracy (threshold=0.85) | 100% |
| ROC-AUC | 1.0 |
| PR-AUC | 1.0 |
| class_a test accuracy | 100% |
| class_b test accuracy | 100% |

---

## Part 7 — TFLite export

| Format | Size | Notes |
|--------|------|-------|
| Float32 | 9.3 MB | Full precision reference |
| INT8 quantized | 2.6 MB | Used in Android app |

Quantization method: dynamic-range INT8 (`tf.lite.Optimize.DEFAULT`).  
No calibration dataset required. Verified with dummy inference (output sum = 1.0000).

---

## Part 8 — Android app

### Authentication paths

**Path A — Class A matched (authenticated user):**
1. CameraX captures live frames via front camera
2. ML Kit detects a face in the frame
3. TFLite classifier produces `P(class_a) > P(class_b)` with `P(class_b) < 0.85`
4. 5-frame majority vote confirms Class A
5. `AuthSuccessActivity` displays confidence %, timestamp, and auth event log

**Path B — Class B detected (target / unauthorised):**
1. Same camera pipeline
2. TFLite classifier produces `P(class_b) ≥ 0.85`
3. 5-frame majority vote confirms Class B
4. `FilesystemLocker.lockdown()` clears cache and session data
5. `AuthFailActivity` shows confidence %, timestamp, and live filesystem probe:

```
/data/data/        → PERMISSION DENIED
/data/system/      → PERMISSION DENIED
/data/local/       → PERMISSION DENIED
/proc/1/           → NOT FOUND (hidden by kernel)
/sys/kernel/       → PERMISSION DENIED
/data/data/com.faceauth.app → accessible (app sandbox only)
Cache cleared: YES · Session wiped: YES
```

This output is live — not mocked. It demonstrates Android's mandatory access control enforcing filesystem isolation when an unauthorised user is detected.

### Key Android components

| File | Responsibility |
|------|---------------|
| `CameraActivity.kt` | CameraX + ML Kit + YUV→Bitmap + majority vote |
| `FaceClassifier.kt` | TFLite inference, `buf.rewind()` critical fix |
| `FaceOverlayView.kt` | Real-time face bounding-box overlay |
| `AuthLogger.kt` | JSON event log persisted in `filesDir` |
| `FilesystemLocker.kt` | Cache wipe + filesystem probe demonstration |

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
# From the project root
python3.10 -m venv venv
source venv/bin/activate         # Linux/Mac
# venv\Scripts\activate          # Windows CMD
# venv\Scripts\Activate.ps1      # Windows PowerShell

pip install --upgrade pip
pip install -r dataset_generation/requirements.txt
pip install -r model_training/requirements.txt

# Verify
python -c "import cv2; print('cv2 OK', cv2.__version__)"
python -c "import mediapipe; print('mediapipe OK')"
python -c "import tensorflow as tf; print('TF OK', tf.__version__)"
```

All three must print without errors.

---

### Phase 2 — Prepare source images

Place exactly two images in `dataset_generation/`:

```bash
dataset_generation/
    class_a.jpg    ← your own face, frontal, well-lit, ≥ 256×256 px
    class_b.jpg    ← target face, same quality requirements
```

---

### Phase 3 — Generate synthetic dataset

```bash
cd dataset_generation

python generate_synthetic.py \
  --source_a class_a.jpg \
  --source_b class_b.jpg \
  --output_dir ./dataset/synthetic \
  --num_images 500
```

Expected: `✓ class_a: 500 images` and `✓ class_b: 500 images`

```bash
python augment_dataset.py \
  --synthetic_dir ./dataset/synthetic \
  --output_dir ./dataset/final \
  --target 500
```

Expected: `Split counts: {'train': 400, 'val': 50, 'test': 50}` for each class.

```bash
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

Runtime: 30–90 min on CPU, 5–15 min on GPU.  
Look for `best_phase1.keras` and `best_phase2.keras` in `model_output/`.

Optional — detailed evaluation:

```bash
python evaluate_model.py \
  --model ./model_output/best_phase2.keras \
  --dataset_dir ../dataset_generation/dataset/final \
  --output_dir ./model_output/eval_report
```

---

### Phase 5 — Export to TFLite

```bash
python export_tflite.py \
  --model ./model_output/best_phase2.keras \
  --out   ./tflite_export
```

Expected output ends with `✓ Inference OK` and size ~2.6 MB.

Copy to Android assets:

```bash
cp tflite_export/face_classifier.tflite \
   ../android_app/app/src/main/assets/face_classifier.tflite

ls -lh ../android_app/app/src/main/assets/face_classifier.tflite
# Must be ~2–3 MB, not 0 bytes
```

---

### Phase 6 — Verify class label order (critical)

```bash
python3 -c "
from tensorflow.keras.preprocessing.image import ImageDataGenerator
g = ImageDataGenerator(rescale=1./255).flow_from_directory(
    '../dataset_generation/dataset/final/train',
    target_size=(224,224), batch_size=1, class_mode='categorical')
print(g.class_indices)
"
```

Expected: `{'class_a': 0, 'class_b': 1}`

If you see `class_a: 1`, open `FaceClassifier.kt` and swap:
```kotlin
const val CLASS_A_IDX = 1   // was 0
const val CLASS_B_IDX = 0   // was 1
```

---

### Phase 7 — Android Studio setup

1. Open **Android Studio → File → Open**
2. Select `face-auth-system/android_app/` (not the parent folder)
3. Wait for Gradle sync to complete (~500 MB download on first run)
4. If sync fails: **File → Sync Project with Gradle Files**

---

### Phase 8 — Create Android emulator

1. **Tools → Device Manager → Create Device**
2. Select **Phone → Pixel 6 → Next**
3. Select **API 34 (Android 14) — Google Play** (download if needed)
4. Click **Next → Show Advanced Settings:**
   - Front Camera → `Emulated`
   - RAM → `2048 MB`
5. Click **Finish**
6. Click ▶ to start the emulator — wait for the home screen

---

### Phase 9 — Generate launcher icon

Without this the build fails with `@mipmap/ic_launcher not found`:

1. Right-click `app/src/main/res` in the Project panel
2. **New → Image Asset**
3. Leave defaults → **Next → Finish**

---

### Phase 10 — Build and install

```
Build → Clean Project
Build → Rebuild Project
Run → Run 'app'   (or Shift+F10)
```

Grant camera permission when prompted on first launch.

---

### Phase 11 — Demonstrate Path A (Class A success)

Inject the source image into the emulator's virtual camera:

1. Click `⋮` (three dots) on the emulator toolbar
2. **Camera → Virtual Scene → Add image (+)**
3. Browse to `dataset_generation/class_a.jpg`
4. In the app tap **Begin Authentication**
5. Point the virtual camera at the injected image poster

**Expected result:**
- Green bounding box appears
- Status: `✓ Authorised face detected`
- Navigates to **Authentication Successful** screen with confidence % and timestamp

---

### Phase 12 — Demonstrate Path B (Class B fail + filesystem lockdown)

1. Return to main screen (tap Done or Back)
2. Inject `dataset_generation/class_b.jpg` via emulator extended controls
3. Tap **Begin Authentication**

**Expected result:**
- Red bounding box appears
- Status: `⚠ Unauthorised face detected`
- When `P(class_b) ≥ 0.85`, navigates to **Authentication Unsuccessful** screen
- Screen shows filesystem probe results (all system paths: `PERMISSION DENIED`)
- Cache cleared and session wiped

---

### Phase 13 — Publish to GitHub

```bash
cd face-auth-system

git init
git add .

# Large dataset — use Git LFS if images > 100 MB total
git lfs install
git lfs track "*.jpg"
git add .gitattributes

git commit -m "Complete face authentication system: dataset + model + Android app"
git tag v1.0-dataset
git remote add origin https://github.com/YOUR_USERNAME/face-auth-system.git
git push -u origin main
git push origin v1.0-dataset
```

---

### Common errors and fixes

| Error | Cause | Fix |
|-------|-------|-----|
| `ModuleNotFoundError: mediapipe` | Wrong Python or venv not active | `source venv/bin/activate` |
| `FileNotFoundError: class_a.jpg` | Image not in `dataset_generation/` | Move `class_a.jpg` and `class_b.jpg` there |
| Gradle sync failed | No internet / wrong SDK path | **File → Project Structure → SDK Location** |
| `face_classifier.tflite` not found at runtime | Asset not copied | Re-run `cp` in Phase 5 |
| App crashes on camera open | Camera permission denied | Settings → Apps → FaceAuth → Permissions → Camera |
| Class A image gives 100% Class B | `CLASS_A_IDX` wrong or `buf.rewind()` missing | Check Phase 6; confirm `FaceClassifier.kt` has `buf.rewind()` |
| Emulator camera shows black screen | Camera set to `None` in AVD | AVD Manager → Edit → Front Camera → Emulated |
| TFLite model 0 bytes | Export ran before training finished | Re-run `export_tflite.py` |

---

## Part 10 — Failure modes and distributional shift (Part 04 answer)

### Failure modes introduced by synthetic training data

**1. Domain gap (covariate shift)**  
All 500 synthetic samples share the same underlying texture and skin-pore detail from the source photograph. Real camera captures under genuine environmental variation produce pixel statistics outside the training manifold.

**2. Limited intra-class identity variation**  
Starting from one image means one expression, one hairstyle, one set of accessories. Ageing, haircutting, or adding glasses produce faces the model has never seen a real example of.

**3. Synthetic artefact overfitting**  
Perspective warp introduces reflection-padded borders and compressed side regions absent from real photographs. The model may learn these as class-discriminating cues — invisible in test accuracy because the test set shares the same artefacts.

**4. Background entanglement**  
The generator does not replace the background. The model may discriminate on background texture rather than face identity.

**5. Texture frequency bias**  
Multiplicative brightness/contrast adjustments preserve the high-frequency texture of the source image exactly. Real sensors introduce demosaicing artefacts and sharpening kernels that alter the texture power spectrum.

**6. Class B confidence leakage below threshold**  
A real target photographed under unseen conditions may produce confidence below 0.85, causing a false accept — the most security-critical failure mode.

### Mitigation steps taken

| Mitigation | How it helps |
|-----------|-------------|
| 8 illumination modes | Covers warm/cool casts, directional shadows, over/underexposure |
| ImageNet transfer learning | 1.3M real-photo features supplement 500 synthetic images |
| Two-phase fine-tuning (unfreeze layer ≥ 100) | Preserves robust low-level features; avoids catastrophic forgetting |
| Conservative threshold τ = 0.85 | Uncertain Class B predictions abstain rather than flag |
| 5-frame temporal majority vote | Filters single-frame instabilities from transient lighting |
| Dropout at 0.30 and 0.15 | Prevents artefact-specific memorisation |
| ML Kit face detection gate | Rejects background-only frames; focuses classifier on face region |

---

## License

Dataset and code produced for academic assessment purposes only.  
Target face images used solely for binary classification research within CS402M.  
Not for commercial use.