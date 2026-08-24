# Face Authentication System — Synthetic Dataset v1.0

> Assignment: Adversarial ML — Part 1 (Dataset) + Part 2 (Classifier + Android App)

---

## Repository structure
face-auth-system/
├── dataset_generation/ ← synthetic generator + augmentation
├── model_training/ ← MobileNetV2 training + TFLite export
├── android_app/ ← Android authentication app
└── dataset/
└── final/ ← complete dataset (both classes, all splits)
├── train/
│ ├── class_a/ (400 images)
│ └── class_b/ (400 images)
├── val/
│ ├── class_a/ (50 images)
│ └── class_b/ (50 images)
└── test/
├── class_a/ (50 images)
└── class_b/ (50 images)


## Dataset commit tag
git tag v1.0-dataset

---

## 1. Input images

| Class | Role | Description |
|-------|------|-------------|
| `class_a` | Authenticated user | Team member self-portrait, frontal, neutral expression, indoor lighting |
| `class_b` | Target / unauthorised | Reference image of the target person from a single public-domain source |

Each class starts from **exactly one** source image. All 500+ samples per class are
generated synthetically from that single image.

---

## 2. Single-image synthetic generation algorithm

### Overview

Standard data augmentation pipelines assume a large seed corpus.
This pipeline inverts that: **one image → 500+ diverse samples** by combining
three complementary strategies.

Source image (1×)
│
▼
[Stage 1] Geometric pose simulation → 60 pose configs × 9 lighting modes = 540 base
│
▼
[Stage 2] Scale × lighting grid → 7 scales × 5 lighting modes = 35 more
│
▼
[Stage 3] Random multi-op combinations → until N = 500 is reached
│
▼
Synthetic set (500 images / class)
│
▼
[Stage 4] Conventional augmentation → fill to 500, create 80/10/10 split


### Stage 1 — Pose variation

Two transforms are composed:

**a. In-plane rotation (roll)** — `cv2.getRotationMatrix2D` with border reflection.

**b. Out-of-plane simulation (yaw + pitch)** — perspective warp using
`cv2.getPerspectiveTransform`. The four corner source points are displaced
proportionally to `tan(angle) × perspective_factor`, compressing one side of the
face to simulate head turning.

This is an approximation of 3-DOF rotation without requiring a 3D face model.

### Stage 2 — Illumination variation

PIL `ImageEnhance` and channel arithmetic implement eight lighting modes:

| Mode | Effect |
|------|--------|
| `bright` | Brightness ×[1.2, 1.8] |
| `dark` | Brightness ×[0.3, 0.7] |
| `contrast` | Contrast ×[0.4, 2.0] |
| `warm` | R +[20,40], B −[15,30] |
| `cool` | B +[20,40], R −[15,30] |
| `shadow` | Directional linear gradient mask (4 directions, strength 0.3–0.6) |
| `overexposed` | High brightness + low contrast |
| `underexposed` | Low brightness + high contrast |

### Stage 3 — Scale variation

Zoom in/out via `cv2.resize` with centre-crop (scale > 1) or reflection padding
(scale < 1) to maintain a fixed 224×224 output.

### Stage 4 — JPEG + blur simulation

Random JPEG re-encoding at quality [60, 95] introduces compression blocks.
Gaussian blur with kernel ∈ {3, 5} simulates camera defocus.

---

## 3. Synthetic generation hyperparameters

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

## 4. Conventional augmentation pipeline

Applied **after** synthetic generation, using `augment_dataset.py`.

| Transform | Probability | Parameter range |
|-----------|-------------|-----------------|
| Horizontal flip | 0.50 | — |
| Brightness jitter | 0.70 | factor ∈ [0.60, 1.40] |
| Rotation | 0.60 | angle ∈ [−15°, +15°] |
| Gaussian noise | 0.50 | σ ∈ [5, 25] |
| Contrast jitter | 0.40 | factor ∈ [0.70, 1.50] |
| Saturation jitter | 0.30 | factor ∈ [0.70, 1.30] |

Each image in the final dataset has at least one augmentation applied (enforced).
Multiple transforms are composed in a single forward pass.

---

## 5. Dataset split

| Split | Ratio | class_a | class_b |
|-------|-------|---------|---------|
| train | 80% | 400 | 400 |
| val | 10% | 50 | 50 |
| test | 10% | 50 | 50 |
| **Total** | | **500** | **500** |

`random_state = 42` for reproducibility. Stratified by class.

---

## 6. Model architecture and training hyperparameters

### Base model
MobileNetV2 pretrained on ImageNet (224×224×3 input).

### Classification head

GlobalAveragePooling2D
→ Dropout(0.30)
→ Dense(128, activation='relu', L2=1e-4)
→ Dropout(0.15)
→ Dense(2, activation='softmax')


### Phase 1 — frozen base
| Hyperparameter | Value |
|---------------|-------|
| Learning rate | 1e-3 |
| Epochs | 10 |
| Batch size | 32 |
| Optimizer | Adam |
| Loss | Categorical cross-entropy |

### Phase 2 — fine-tune (unfreeze layer ≥ 100)
| Hyperparameter | Value |
|---------------|-------|
| Learning rate | 1e-5 |
| Epochs | 20 |
| Fine-tune from layer | 100 / 154 |
| Batch size | 32 |

### Callbacks
- `ModelCheckpoint` — saves best val_accuracy
- `EarlyStopping` — patience 5
- `ReduceLROnPlateau` — factor 0.5, patience 3

### Inference threshold
Class B is flagged **only if** `P(class_b) ≥ 0.85`.

---

## 7. Reproducing the dataset

```bash
# Install dependencies
cd dataset_generation
pip install -r requirements.txt

# Generate 500 synthetic images per class
python generate_synthetic.py \
  --source_a class_a.jpg \
  --source_b class_b.jpg \
  --num_images 500

# Conventional augmentation + 80/10/10 split
python augment_dataset.py --target 500

# Verify
python verify_dataset.py
```

---

## 8. Report discussion — failure modes and distributional shift

See `REPORT_DISCUSSION.md` for the full Part 04 answer.

---

## License

Dataset and code produced for academic assessment purposes only.
The target face images are used solely for binary classification research
within the scope of this assignment. Not for commercial use.

# Part 04 — Report Discussion
## Failure modes of synthetic training data and distributional shift mitigation

---

### 1. Failure modes introduced by training on synthetic data

#### 1.1 Domain gap (covariate shift)

The most fundamental failure mode is the mismatch between the synthetic training
distribution and the real inference distribution. Our generator applies
deterministic transformations (affine warps, PIL colour shifts, Gaussian noise)
to a single photograph. The resulting samples share the same underlying texture,
skin-pore detail, and compression artefacts as the source image. A real camera
capture of the same person under true environmental variation (different sensor,
different lens flare, real motion blur, genuine 3D head pose) will produce
statistics that lie outside the training manifold, causing elevated uncertainty
or misclassification.

#### 1.2 Limited intra-class identity variation

Starting from a single image means we can only simulate the appearance of one
expression, one hairstyle, one set of accessories (glasses, beard, etc.) and
one head-pose within the plausible range of the perspective warp. Events like
ageing, haircutting, or putting on glasses produce a face that the model has
never seen a real example of. The model may generalise poorly to these shifts
even though it performs well on the test set derived from the same synthetic
distribution.

#### 1.3 Synthetic artefact over-fitting

Pose variation via perspective warp introduces geometric distortions that do not
appear in real photographs (e.g. loss of detail in compressed side regions,
reflection-padding borders occasionally entering the crop). The model may learn
to use these artefacts as class-discriminating cues rather than genuine facial
identity features. This is invisible from test accuracy because the test set
shares the same artefacts.

#### 1.4 Background entanglement

Our generator does not replace or randomise the background. Both classes are
trained with whatever background appeared in the single source image. The model
may learn to discriminate on background texture rather than face identity,
performing perfectly on the synthetic set but failing when the subject appears
against a different background at inference.

#### 1.5 Texture frequency bias

Generative transformations that apply multiplicative brightness and contrast
adjustments preserve the high-frequency texture of the source image exactly.
Real image sensors introduce sensor noise, demosaicing artefacts, and
sharpening kernels that alter the texture power spectrum differently in each
capture. The synthetic distribution underrepresents these HF texture shifts.

#### 1.6 Class-B confidence leakage below threshold

Because Class B was generated from a single photograph with the same pipeline
as Class A, the intra-class variation of Class B is artificially uniform.
A real target photographed under unseen conditions may produce a confidence
well below the 0.85 threshold even though they are the target, causing a
false authentication (false accept). This is the most security-critical failure.

---

### 2. Mitigation steps taken

#### 2.1 Diverse multi-modal augmentation pipeline

We apply eight distinct illumination modes rather than a single brightness
jitter, covering warm/cool colour casts, directional shadows, overexposure, and
underexposure. Combined with five pose-angle axes and a compression artefact
stage, the synthetic distribution covers a wider area of appearance space than
single-axis augmentation approaches.

#### 2.2 Transfer learning from ImageNet (MobileNetV2)

The frozen MobileNetV2 backbone provides feature representations learned from
~1.3 M real photographs spanning thousands of object categories including faces,
people, and diverse lighting conditions. These pre-trained features are robust
to real-world texture variation in a way that features learned purely from
500 synthetic images would not be. Transfer learning effectively supplements the
synthetic data with ImageNet's real-domain knowledge.

#### 2.3 Two-phase fine-tuning

We unfreeze only the top 54 layers (100–154) of MobileNetV2 during Phase 2,
retaining the robust low-level feature detectors (edges, textures) learned on
real data in the earlier layers. This prevents catastrophic forgetting of
real-domain features while adapting the high-level face-discriminative layers
to the task.

#### 2.4 Conservative confidence threshold (0.85)

Setting the Class-B acceptance threshold at 0.85 (rather than the default 0.50)
creates a conservative security margin. A model that is uncertain about Class B
(e.g. due to domain shift at inference) will produce a lower confidence and
correctly abstain from flagging rather than triggering a false positive. This
directly mitigates the class-leakage failure mode described in §1.6.

#### 2.5 Temporal majority-vote smoothing

The Android app accumulates three consecutive frame predictions and requires a
majority before committing to a decision. This filters single-frame prediction
instabilities caused by transient lighting changes (e.g. the subject moving
through shadow) that would cause isolated misclassifications in single-frame
inference.

#### 2.6 Dropout regularisation

Dropout at rates 0.30 and 0.15 prevents the model from memorising
artefact-specific features in the synthetic training set. A model that cannot
rely on any single neuron is less likely to overfit to synthetic-specific
texture signatures.

#### 2.7 On-device face detection before classification

ML Kit's face detector first localises the face bounding box. The classifier
receives only the cropped face region (scaled to 224×224 with a 20% margin),
not the full camera frame. This prevents the model from using background cues
and focuses inference on the facial region, reducing the background
entanglement failure mode.

#### 2.8 Recommendations for further mitigation (not implemented)

- **Few-shot real-capture fine-tuning**: capturing 5–10 real photographs of
  Class A on the target device at enrolment time and performing lightweight
  fine-tuning would close most of the domain gap.
- **GAN-based generation** (e.g. DreamBooth + Stable Diffusion): produces
  photorealistic samples with genuine sensor noise and varied backgrounds,
  substantially reducing the covariate shift.
- **Test-time augmentation (TTA)**: averaging predictions across flipped,
  brightness-jittered, and rotated versions of the query frame at inference
  improves robustness.
- **Liveness detection**: adding a blink/motion liveness check prevents
  replay attacks using a printed photograph of the target.

  