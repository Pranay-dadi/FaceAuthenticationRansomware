"""
Detailed evaluation: confusion matrix, ROC curve, per-class accuracy,
threshold sweep, and misclassified sample visualisation.
Run AFTER train_classifier.py has produced best_phase2.keras.
"""

import tensorflow as tf
import numpy as np
import matplotlib.pyplot as plt
import matplotlib.gridspec as gridspec
import seaborn as sns
import json, argparse, cv2
from pathlib import Path
from sklearn.metrics import (confusion_matrix, classification_report,
                              roc_curve, auc, precision_recall_curve)
from tensorflow.keras.preprocessing.image import ImageDataGenerator

CLASS_B_THRESHOLD = 0.85
CLASS_NAMES       = ["class_a", "class_b"]


def load_test_generator(dataset_dir: str, batch_size: int = 32):
    gen = ImageDataGenerator(rescale=1.0 / 255)
    return gen.flow_from_directory(
        str(Path(dataset_dir) / "test"),
        target_size=(224, 224),
        batch_size=batch_size,
        class_mode="categorical",
        shuffle=False,
    )


def predict_all(model, test_gen):
    probs  = model.predict(test_gen, verbose=1)   # (N, 2)
    y_true = test_gen.classes
    y_prob_b  = probs[:, 1]                       # class_b probability
    # Apply threshold: class_b only if conf >= threshold
    y_pred_thresh = np.where(y_prob_b >= CLASS_B_THRESHOLD, 1, 0)
    y_pred_argmax = np.argmax(probs, axis=1)      # argmax (no threshold)
    return probs, y_true, y_pred_thresh, y_pred_argmax, y_prob_b


def plot_confusion_matrix(y_true, y_pred, title: str, out_path: Path):
    cm = confusion_matrix(y_true, y_pred)
    fig, ax = plt.subplots(figsize=(7, 6))
    sns.heatmap(cm, annot=True, fmt="d", cmap="Blues",
                xticklabels=CLASS_NAMES, yticklabels=CLASS_NAMES,
                annot_kws={"size": 16}, ax=ax)
    ax.set_xlabel("Predicted", fontsize=13)
    ax.set_ylabel("True",      fontsize=13)
    ax.set_title(title,        fontsize=14)
    plt.tight_layout()
    plt.savefig(str(out_path), dpi=150)
    plt.close()
    print(f"  Saved: {out_path}")


def plot_roc(y_true, y_prob_b, out_path: Path):
    fpr, tpr, thresholds = roc_curve(y_true, y_prob_b)
    roc_auc = auc(fpr, tpr)

    fig, axes = plt.subplots(1, 2, figsize=(14, 5))

    # ── Left: ROC curve ──────────────────────────────────────────
    axes[0].plot(fpr, tpr, color="darkorange", lw=2,
                 label=f"ROC curve (AUC = {roc_auc:.4f})")
    axes[0].plot([0, 1], [0, 1], color="navy", lw=1, linestyle="--")
    axes[0].set_xlabel("False Positive Rate")
    axes[0].set_ylabel("True Positive Rate")
    axes[0].set_title("ROC Curve — class_b detection")
    axes[0].legend(loc="lower right")
    axes[0].grid(True, alpha=0.3)

    # ── Right: TPR / TNR vs threshold ────────────────────────────
    # sklearn prepends a synthetic point at index 0 to fpr, tpr, thresholds.
    # All three arrays have the same length N.
    # Skip index 0 on all three so arrays align: thresholds[1:] vs tpr[1:]
    t  = thresholds[1:]
    tp = tpr[1:]
    fp = fpr[1:]

    if len(t) > 0:
        axes[1].plot(t, tp,     label="TPR (sensitivity)", color="green")
        axes[1].plot(t, 1 - fp, label="TNR (specificity)", color="blue")
        axes[1].axvline(x=CLASS_B_THRESHOLD, color="red", linestyle="--",
                        label=f"Chosen threshold = {CLASS_B_THRESHOLD}")
        axes[1].set_xlabel("Classification threshold (class_b prob)")
        axes[1].set_ylabel("Rate")
        axes[1].set_title("TPR / TNR vs Threshold")
        axes[1].legend()
        axes[1].grid(True, alpha=0.3)
        axes[1].set_xlim([0, 1])
        axes[1].set_ylim([0, 1.05])
    else:
        axes[1].text(0.5, 0.5, "Perfect classifier — single threshold point",
                     ha="center", va="center", transform=axes[1].transAxes)
        axes[1].set_title("TPR / TNR vs Threshold")

    plt.tight_layout()
    plt.savefig(str(out_path), dpi=150)
    plt.close()
    print(f"  Saved: {out_path}")
    return roc_auc


def plot_precision_recall(y_true, y_prob_b, out_path: Path):
    precision, recall, _ = precision_recall_curve(y_true, y_prob_b)
    pr_auc = auc(recall, precision)

    plt.figure(figsize=(7, 5))
    plt.plot(recall, precision, color="purple", lw=2,
             label=f"PR AUC = {pr_auc:.4f}")
    plt.xlabel("Recall")
    plt.ylabel("Precision")
    plt.title("Precision-Recall Curve — class_b")
    plt.legend()
    plt.grid(True, alpha=0.3)
    plt.tight_layout()
    plt.savefig(str(out_path), dpi=150)
    plt.close()
    print(f"  Saved: {out_path}")
    return pr_auc


def plot_confidence_histogram(probs, y_true, out_path: Path):
    fig, axes = plt.subplots(1, 2, figsize=(14, 5))

    for ci, (cls, color) in enumerate(zip(CLASS_NAMES, ["steelblue", "tomato"])):
        mask = y_true == ci
        confs = probs[mask, ci]
        axes[ci].hist(confs, bins=40, color=color, alpha=0.75, edgecolor="black")
        axes[ci].set_title(f"{cls} — predicted confidence distribution")
        axes[ci].set_xlabel(f"P({cls})")
        axes[ci].set_ylabel("Count")
        axes[ci].axvline(x=0.5, color="gray", linestyle="--", label="0.5 boundary")
        if ci == 1:
            axes[ci].axvline(x=CLASS_B_THRESHOLD, color="red", linestyle="--",
                             label=f"threshold={CLASS_B_THRESHOLD}")
        axes[ci].legend()
        axes[ci].grid(True, alpha=0.3)

    plt.tight_layout()
    plt.savefig(str(out_path), dpi=150)
    plt.close()
    print(f"  Saved: {out_path}")


def plot_misclassified(model, test_gen, y_true, y_pred, probs, out_path: Path,
                       max_samples: int = 16):
    """Show misclassified examples as a grid."""
    wrong_idx = np.where(y_true != y_pred)[0]
    if len(wrong_idx) == 0:
        print("  No misclassifications found — perfect test accuracy!")
        return

    wrong_idx = wrong_idx[:max_samples]
    n_cols = 4
    n_rows = max(1, (len(wrong_idx) + n_cols - 1) // n_cols)

    fig, axes = plt.subplots(n_rows, n_cols, figsize=(14, n_rows * 3.5))
    fig.suptitle("Misclassified test samples", fontsize=14)
    axes = axes.flatten() if n_rows > 1 else [axes] if n_cols == 1 else axes.flatten()

    # Collect image paths from generator
    all_paths = list(test_gen.filenames)

    for plot_i, idx in enumerate(wrong_idx):
        ax = axes[plot_i]
        img_path = Path(test_gen.directory) / all_paths[idx]
        img = cv2.imread(str(img_path))
        if img is not None:
            ax.imshow(cv2.cvtColor(img, cv2.COLOR_BGR2RGB))
        true_lbl = CLASS_NAMES[y_true[idx]]
        pred_lbl = CLASS_NAMES[y_pred[idx]]
        conf_b = probs[idx, 1]
        ax.set_title(f"True: {true_lbl}\nPred: {pred_lbl} ({conf_b:.2f})",
                     fontsize=9, color="red")
        ax.axis("off")

    for ax in axes[len(wrong_idx):]:
        ax.axis("off")

    plt.tight_layout()
    plt.savefig(str(out_path), dpi=130)
    plt.close()
    print(f"  Saved: {out_path}")


def main():
    ap = argparse.ArgumentParser(description="Evaluate face classifier")
    ap.add_argument("--model",       default="./model_output/best_phase2.keras")
    ap.add_argument("--dataset_dir", default="./dataset/final")
    ap.add_argument("--output_dir",  default="./model_output/eval_report")
    args = ap.parse_args()

    out = Path(args.output_dir)
    out.mkdir(parents=True, exist_ok=True)

    print("Loading model …")
    model = tf.keras.models.load_model(args.model)

    print("Loading test generator …")
    test_gen = load_test_generator(args.dataset_dir)

    print("Running predictions …")
    probs, y_true, y_pred_thresh, y_pred_argmax, y_prob_b = predict_all(model, test_gen)

    # ── Text report ────────────────────────────────────────────
    print("\n── Classification report (argmax) ──")
    print(classification_report(y_true, y_pred_argmax, target_names=CLASS_NAMES))

    print(f"\n── Classification report (threshold={CLASS_B_THRESHOLD}) ──")
    print(classification_report(y_true, y_pred_thresh, target_names=CLASS_NAMES))

    # Per-class accuracy
    metrics = {}
    for ci, cls in enumerate(CLASS_NAMES):
        mask = y_true == ci
        acc_argmax = float(np.mean(y_pred_argmax[mask] == y_true[mask]))
        acc_thresh = float(np.mean(y_pred_thresh[mask] == y_true[mask]))
        metrics[f"{cls}_acc_argmax"]    = acc_argmax
        metrics[f"{cls}_acc_threshold"] = acc_thresh
        print(f"  {cls}  argmax={acc_argmax:.4f}  threshold={acc_thresh:.4f}")

    # ── Plots ──────────────────────────────────────────────────
    print("\nGenerating plots …")
    plot_confusion_matrix(y_true, y_pred_argmax,
                          "Confusion Matrix (argmax)",
                          out / "cm_argmax.png")
    plot_confusion_matrix(y_true, y_pred_thresh,
                          f"Confusion Matrix (threshold={CLASS_B_THRESHOLD})",
                          out / "cm_threshold.png")
    roc_auc = plot_roc(y_true, y_prob_b, out / "roc_curve.png")
    pr_auc  = plot_precision_recall(y_true, y_prob_b, out / "pr_curve.png")
    plot_confidence_histogram(probs, y_true, out / "confidence_hist.png")
    plot_misclassified(model, test_gen, y_true, y_pred_thresh,
                       probs, out / "misclassified.png")

    metrics["roc_auc"] = roc_auc
    metrics["pr_auc"]  = pr_auc
    metrics["class_b_threshold"] = CLASS_B_THRESHOLD

    (out / "eval_metrics.json").write_text(json.dumps(metrics, indent=2))
    print(f"\n✓ Evaluation complete.  All outputs → {out}")


if __name__ == "__main__":
    main()