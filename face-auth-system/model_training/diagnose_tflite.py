"""
Run from face-auth-system/model_training/
This tells you:
  - What class indices the model uses
  - Whether [0,1] or [-1,1] is the correct Android input range
  - Whether the model actually classifies correctly
"""

import numpy as np
import cv2
import tensorflow as tf
from pathlib import Path
from tensorflow.keras.preprocessing.image import ImageDataGenerator


def infer(interp, img_array_float32):
    inp = interp.get_input_details()[0]
    out = interp.get_output_details()[0]
    interp.set_tensor(inp["index"], img_array_float32)
    interp.invoke()
    return interp.get_tensor(out["index"])[0].copy()


def load_img(path):
    img = cv2.imread(str(path))
    if img is None:
        raise FileNotFoundError(path)
    img = cv2.resize(img, (224, 224))
    return cv2.cvtColor(img, cv2.COLOR_BGR2RGB).astype(np.float32)


def test(interp, img_rgb_float, label):
    # Range A: [0, 1] — what Android currently sends
    inp_01 = img_rgb_float / 255.0
    out_01 = infer(interp, inp_01[np.newaxis])

    # Range B: [-1, 1] — raw MobileNetV2 input
    inp_m1 = inp_01 * 2.0 - 1.0
    out_m1 = infer(interp, inp_m1[np.newaxis])

    print(f"\n  [{label}]")
    print(f"    input [0,1]  → idx0={out_01[0]:.6f}  idx1={out_01[1]:.6f}"
          f"  winner=idx{np.argmax(out_01)}")
    print(f"    input [-1,1] → idx0={out_m1[0]:.6f}  idx1={out_m1[1]:.6f}"
          f"  winner=idx{np.argmax(out_m1)}")
    return out_01, out_m1


def main():
    tflite_path = "./tflite_export/face_classifier.tflite"

    print("=" * 62)
    print("  TFLite model diagnostic")
    print("=" * 62)

    # ── Load model ──────────────────────────────────────────────
    interp = tf.lite.Interpreter(model_path=tflite_path)
    interp.allocate_tensors()
    inp_d = interp.get_input_details()[0]
    out_d = interp.get_output_details()[0]
    print(f"\n  input  shape : {inp_d['shape']}  dtype={inp_d['dtype']}")
    print(f"  output shape : {out_d['shape']}  dtype={out_d['dtype']}")

    # ── Class index mapping ─────────────────────────────────────
    gen = ImageDataGenerator(rescale=1. / 255).flow_from_directory(
        "../dataset_generation/dataset/final/train",
        target_size=(224, 224), batch_size=1, class_mode="categorical"
    )
    ci = gen.class_indices
    print(f"\n  class_indices : {ci}")
    print(f"  class_a → output index {ci['class_a']}")
    print(f"  class_b → output index {ci['class_b']}")
    CLASS_A_IDX = ci["class_a"]
    CLASS_B_IDX = ci["class_b"]

    # ── Source images ───────────────────────────────────────────
    print("\n" + "=" * 62)
    print("  Source image results")
    print("=" * 62)
    for lbl, path in [
        ("class_a.jpg", "../dataset_generation/class_a.jpg"),
        ("class_b.jpg", "../dataset_generation/class_b.jpg"),
    ]:
        try:
            img = load_img(path)
            test(interp, img, lbl)
        except FileNotFoundError:
            print(f"\n  [{lbl}] — FILE NOT FOUND, skipping")

    # ── Test-set samples ────────────────────────────────────────
    print("\n" + "=" * 62)
    print("  Test-set sample results")
    print("=" * 62)
    for cls in ["class_a", "class_b"]:
        samples = sorted(
            Path(f"../dataset_generation/dataset/final/test/{cls}").glob("*.jpg")
        )[:3]
        for s in samples:
            test(interp, load_img(s), f"test/{cls}/{s.name}")

    # ── Verdict ─────────────────────────────────────────────────
    print("\n" + "=" * 62)
    print("  VERDICT — read this and follow the matching fix below")
    print("=" * 62)
    print(f"""
  1. CLASS INDICES
     In FaceClassifier.kt set:
       CLASS_A_IDX = {CLASS_A_IDX}
       CLASS_B_IDX = {CLASS_B_IDX}

  2. PREPROCESSING RANGE
     Look at the class_a.jpg row above.
     - If [0,1]  input gives idx{CLASS_A_IDX} the higher score  → Android sends [0,1]  ✓
     - If [-1,1] input gives idx{CLASS_A_IDX} the higher score  → Android must send [-1,1]

  3. IF BOTH RANGES GIVE idx{CLASS_B_IDX} for class_a.jpg
     → The model labels are swapped; class_a was stored as index {CLASS_B_IDX}
     → Set CLASS_A_IDX={CLASS_B_IDX}  CLASS_B_IDX={CLASS_A_IDX} in FaceClassifier.kt
    """)


if __name__ == "__main__":
    main()