"""
Dataset verification script.
Checks image counts, class balance, split integrity, and renders a contact sheet.
"""

import cv2
import numpy as np
import matplotlib.pyplot as plt
import matplotlib.gridspec as gridspec
import json, argparse, os
from pathlib import Path
from collections import defaultdict


def count_images(base_dir: Path) -> dict:
    counts = defaultdict(lambda: defaultdict(int))
    for split in ["train", "val", "test"]:
        split_dir = base_dir / split
        if not split_dir.exists():
            print(f"  WARNING: {split_dir} not found")
            continue
        for cls_dir in split_dir.iterdir():
            if not cls_dir.is_dir():
                continue
            n = sum(1 for f in cls_dir.glob("*.jpg"))
            counts[split][cls_dir.name] = n
    return dict(counts)


def verify_images(base_dir: Path) -> dict:
    """Check that all images can be loaded and are 224×224."""
    issues = []
    total  = 0
    bad    = 0
    for img_path in base_dir.rglob("*.jpg"):
        total += 1
        img = cv2.imread(str(img_path))
        if img is None:
            issues.append(f"Cannot load: {img_path}")
            bad += 1
        elif img.shape[:2] != (224, 224):
            issues.append(f"Wrong size {img.shape[:2]}: {img_path}")
            bad += 1
    return {"total": total, "bad": bad, "issues": issues[:10]}


def sample_contact_sheet(base_dir: Path, output_path: Path, n_per_class: int = 8):
    """Render a contact sheet of sample images for each split × class."""
    splits  = ["train", "val", "test"]
    classes = ["class_a", "class_b"]

    fig = plt.figure(figsize=(20, 12))
    fig.patch.set_facecolor("#111111")
    title = fig.suptitle("Dataset Sample Contact Sheet",
                          color="white", fontsize=16, y=0.98)

    gs = gridspec.GridSpec(len(splits) * 2, n_per_class + 1,
                           hspace=0.05, wspace=0.04)

    for si, split in enumerate(splits):
        for ci, cls in enumerate(classes):
            row = si * 2 + ci
            # Row label
            ax_label = fig.add_subplot(gs[row, 0])
            ax_label.set_facecolor("#111111")
            ax_label.axis("off")
            label = f"{split}\n{cls}"
            ax_label.text(0.5, 0.5, label, color="white",
                          ha="center", va="center", fontsize=9)

            cls_dir = base_dir / split / cls
            if not cls_dir.exists():
                continue

            img_paths = sorted(cls_dir.glob("*.jpg"))[:n_per_class]
            for ii, p in enumerate(img_paths):
                ax = fig.add_subplot(gs[row, ii + 1])
                img = cv2.imread(str(p))
                if img is not None:
                    ax.imshow(cv2.cvtColor(img, cv2.COLOR_BGR2RGB))
                ax.axis("off")
                # Colour-code border
                colour = "#4CAF50" if cls == "class_a" else "#F44336"
                for spine in ax.spines.values():
                    spine.set_edgecolor(colour)
                    spine.set_linewidth(2)

    plt.savefig(str(output_path), dpi=120, bbox_inches="tight",
                facecolor="#111111")
    plt.close()
    print(f"  Contact sheet saved → {output_path}")


def print_report(counts: dict, verify: dict):
    print("\n" + "=" * 55)
    print("  DATASET VERIFICATION REPORT")
    print("=" * 55)

    total_ok = True
    for split in ["train", "val", "test"]:
        c = counts.get(split, {})
        ca = c.get("class_a", 0)
        cb = c.get("class_b", 0)
        flag = ""
        if ca < 1 or cb < 1:
            flag = " ← WARNING: empty split"
            total_ok = False
        print(f"  {split:<8}  class_a={ca:>4}  class_b={cb:>4}{flag}")

    print(f"\n  Total images : {verify['total']}")
    print(f"  Corrupt/bad  : {verify['bad']}")
    if verify["issues"]:
        print("  Issues (first 10):")
        for iss in verify["issues"]:
            print(f"    {iss}")

    # Expected 80/10/10
    total_a = sum(counts.get(s, {}).get("class_a", 0) for s in counts)
    total_b = sum(counts.get(s, {}).get("class_b", 0) for s in counts)
    tr_a    = counts.get("train", {}).get("class_a", 0)
    if total_a > 0:
        actual_train = tr_a / total_a * 100
        print(f"\n  class_a train ratio: {actual_train:.1f}%  (target 80%)")

    print(f"\n  {'✓ PASSED' if total_ok and verify['bad'] == 0 else '✗ ISSUES FOUND'}")
    print("=" * 55)


def main():
    ap = argparse.ArgumentParser(description="Verify final dataset")
    ap.add_argument("--dataset_dir", default="./dataset/final")
    ap.add_argument("--output_dir",  default="./dataset/reports")
    args = ap.parse_args()

    base = Path(args.dataset_dir)
    out  = Path(args.output_dir)
    out.mkdir(parents=True, exist_ok=True)

    print("Counting images …")
    counts = count_images(base)

    print("Verifying image integrity …")
    verify = verify_images(base)

    print_report(counts, verify)

    print("Generating contact sheet …")
    sample_contact_sheet(base, out / "contact_sheet.png")

    # Save JSON report
    report = {"counts": {k: dict(v) for k, v in counts.items()},
              "integrity": verify}
    (out / "verify_report.json").write_text(json.dumps(report, indent=2))
    print(f"  JSON report saved → {out / 'verify_report.json'}")


if __name__ == "__main__":
    main()