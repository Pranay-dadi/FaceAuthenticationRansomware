"""
Export best_phase2.keras → face_classifier.tflite
Dynamic-range INT8 quantization — no calibration dataset needed.

Usage:
  python export_tflite.py \
    --model ./model_output/best_phase2.keras \
    --out   ./tflite_export
"""

import tensorflow as tf
import numpy as np
import json, argparse
from pathlib import Path


def export(model_path: str, out_dir: str) -> str:
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)

    # ── Load model ───────────────────────────────────────────────
    print(f"Loading model from: {model_path}")
    model = tf.keras.models.load_model(model_path)

    print(f"\nInput  shape : {model.input_shape}")
    print(f"Output shape : {model.output_shape}")
    print(f"Parameters   : {model.count_params():,}")

    # ── Export 1: Float32 (reference) ────────────────────────────
    print("\n[1] Exporting float32 TFLite …")
    conv_f32 = tf.lite.TFLiteConverter.from_keras_model(model)
    tflite_f32 = conv_f32.convert()
    f32_path = out / "face_classifier_float32.tflite"
    f32_path.write_bytes(tflite_f32)
    print(f"    Size : {len(tflite_f32) // 1024} KB  →  {f32_path}")

    # ── Export 2: Dynamic-range INT8 (for Android) ───────────────
    print("\n[2] Exporting INT8 dynamic-range quantized TFLite …")
    conv_q = tf.lite.TFLiteConverter.from_keras_model(model)
    conv_q.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_q = conv_q.convert()
    q_path = out / "face_classifier_quantized.tflite"
    q_path.write_bytes(tflite_q)
    print(f"    Size : {len(tflite_q) // 1024} KB  →  {q_path}")

    # ── Copy quantized model as the canonical Android model ───────
    android_path = out / "face_classifier.tflite"
    android_path.write_bytes(tflite_q)
    print(f"\n✓  Android model : {android_path}")

    # ── Verify with TFLite interpreter ────────────────────────────
    print("\n[Verifying] Running test inference …")
    interp = tf.lite.Interpreter(model_path=str(android_path))
    interp.allocate_tensors()

    inp_det = interp.get_input_details()[0]
    out_det = interp.get_output_details()[0]

    print(f"    Input  : shape={inp_det['shape']}  dtype={inp_det['dtype']}")
    print(f"    Output : shape={out_det['shape']}  dtype={out_det['dtype']}")

    # Dummy inference — should produce two probabilities summing to ~1
    dummy = np.random.rand(1, 224, 224, 3).astype(np.float32)
    interp.set_tensor(inp_det["index"], dummy)
    interp.invoke()
    result = interp.get_tensor(out_det["index"])
    print(f"    Output (dummy input) : {result}  sum={result.sum():.4f}")
    assert abs(result.sum() - 1.0) < 0.01, "Softmax probabilities do not sum to 1!"
    print("    ✓ Inference OK")

    # ── Save model metadata ───────────────────────────────────────
    info = {
        "model_file":        "face_classifier.tflite",
        "input_shape":       inp_det["shape"].tolist(),   # numpy → plain list
        "input_dtype":       str(inp_det["dtype"]),
        "output_shape":      out_det["shape"].tolist(),   # numpy → plain list
        "output_dtype":      str(out_det["dtype"]),
        "class_map": {
            "output_index_0": "class_a — authenticated user",
            "output_index_1": "class_b — target / unauthorised",
        },
        "class_b_confidence_threshold": 0.85,
        "quantization":      "dynamic-range INT8",
        "float32_size_kb":   int(len(tflite_f32) // 1024),   # numpy int32 → int
        "quantized_size_kb": int(len(tflite_q)   // 1024),   # numpy int32 → int
        "base_model":        "MobileNetV2",
        "input_normalisation": "pixel / 255.0 → [0.0, 1.0]",
    }
    info_path = out / "model_info.json"
    info_path.write_text(json.dumps(info, indent=2))
    print(f"\n✓  Model info : {info_path}")

    print("\n" + "=" * 55)
    print("  NEXT STEP — copy to Android assets:")
    print(f"  cp {android_path} \\")
    print("     ../android_app/app/src/main/assets/face_classifier.tflite")
    print("=" * 55)

    return str(android_path)


def main():
    ap = argparse.ArgumentParser(description="Export Keras model to TFLite")
    ap.add_argument("--model", default="./model_output/best_phase2.keras",
                    help="Path to trained Keras model (.keras)")
    ap.add_argument("--out",   default="./tflite_export",
                    help="Output directory for TFLite files")
    args = ap.parse_args()

    if not Path(args.model).exists():
        raise FileNotFoundError(
            f"Model not found: {args.model}\n"
            "Run train_classifier.py first."
        )

    export(args.model, args.out)


if __name__ == "__main__":
    main()