"""
Conventional Augmentation Pipeline + 80/10/10 Split
=====================================================
Augmentation hyperparameters
-----------------------------
FLIP_PROB       : 0.50
BRIGHTNESS      : p=0.70, factor ∈ [0.60, 1.40]
ROTATION        : p=0.60, angle ∈ [-15°, +15°]
GAUSSIAN_NOISE  : p=0.50, σ ∈ [5, 25]
CONTRAST        : p=0.40, factor ∈ [0.70, 1.50]
SATURATION      : p=0.30, factor ∈ [0.70, 1.30]

Split: train=0.80, val=0.10, test=0.10, seed=42
"""

import cv2, numpy as np, random, json, argparse
from PIL import Image, ImageEnhance
from pathlib import Path
from sklearn.model_selection import train_test_split
from tqdm import tqdm

RANDOM_SEED = 42
random.seed(RANDOM_SEED)
np.random.seed(RANDOM_SEED)

AUG_HP = {
    "FLIP_PROB":      0.50,
    "BRIGHTNESS":     {"prob": 0.70, "range": (0.60, 1.40)},
    "ROTATION":       {"prob": 0.60, "range": (-15,  15)},
    "GAUSSIAN_NOISE": {"prob": 0.50, "std_range": (5, 25)},
    "CONTRAST":       {"prob": 0.40, "range": (0.70, 1.50)},
    "SATURATION":     {"prob": 0.30, "range": (0.70, 1.30)},
    "SPLIT":          {"train": 0.80, "val": 0.10, "test": 0.10, "seed": 42},
}


class ConvAugmentor:

    def flip(self, img):
        return cv2.flip(img, 1)

    def brightness(self, img):
        f = random.uniform(*AUG_HP["BRIGHTNESS"]["range"])
        pil = Image.fromarray(cv2.cvtColor(img, cv2.COLOR_BGR2RGB))
        return cv2.cvtColor(np.array(ImageEnhance.Brightness(pil).enhance(f)),
                            cv2.COLOR_RGB2BGR)

    def rotate(self, img):
        angle = random.uniform(*AUG_HP["ROTATION"]["range"])
        h,w = img.shape[:2]
        M = cv2.getRotationMatrix2D((w//2,h//2), angle, 1.0)
        return cv2.warpAffine(img, M, (w,h), borderMode=cv2.BORDER_REFLECT)

    def gaussian_noise(self, img):
        std = random.uniform(*AUG_HP["GAUSSIAN_NOISE"]["std_range"])
        noise = np.random.normal(0, std, img.shape).astype(np.float32)
        return np.clip(img.astype(np.float32)+noise, 0, 255).astype(np.uint8)

    def contrast(self, img):
        f = random.uniform(*AUG_HP["CONTRAST"]["range"])
        pil = Image.fromarray(cv2.cvtColor(img, cv2.COLOR_BGR2RGB))
        return cv2.cvtColor(np.array(ImageEnhance.Contrast(pil).enhance(f)),
                            cv2.COLOR_RGB2BGR)

    def saturation(self, img):
        f = random.uniform(*AUG_HP["SATURATION"]["range"])
        pil = Image.fromarray(cv2.cvtColor(img, cv2.COLOR_BGR2RGB))
        return cv2.cvtColor(np.array(ImageEnhance.Color(pil).enhance(f)),
                            cv2.COLOR_RGB2BGR)

    def augment(self, img: np.ndarray) -> np.ndarray:
        out = img.copy()
        applied = []
        if random.random() < AUG_HP["FLIP_PROB"]:
            out = self.flip(out); applied.append("flip")
        if random.random() < AUG_HP["BRIGHTNESS"]["prob"]:
            out = self.brightness(out); applied.append("brightness")
        if random.random() < AUG_HP["ROTATION"]["prob"]:
            out = self.rotate(out); applied.append("rotation")
        if random.random() < AUG_HP["GAUSSIAN_NOISE"]["prob"]:
            out = self.gaussian_noise(out); applied.append("noise")
        if random.random() < AUG_HP["CONTRAST"]["prob"]:
            out = self.contrast(out); applied.append("contrast")
        if random.random() < AUG_HP["SATURATION"]["prob"]:
            out = self.saturation(out); applied.append("saturation")
        if not applied:
            out = self.brightness(out)   # guarantee at least one aug
        return out

    def fill_to(self, images: list, target: int) -> list:
        pool = list(images)
        aug  = list(images)
        pbar = tqdm(total=target-len(aug), desc="Augmenting")
        while len(aug) < target:
            aug.append(self.augment(random.choice(pool)))
            pbar.update(1)
        pbar.close()
        random.shuffle(aug)
        return aug[:target]


def load_dir(directory: str) -> list:
    imgs = []
    for ext in ("*.jpg","*.jpeg","*.png"):
        for p in Path(directory).glob(ext):
            img = cv2.imread(str(p))
            if img is not None:
                imgs.append(cv2.resize(img,(224,224)))
    print(f"  Loaded {len(imgs)} images from {directory}")
    return imgs


def save_split(images, output_dir: Path, class_name: str):
    cfg  = AUG_HP["SPLIT"]
    seed = cfg["seed"]
    X    = images
    idx  = list(range(len(X)))

    tr_idx, tmp = train_test_split(idx, test_size=1-cfg["train"], random_state=seed)
    val_idx, te_idx = train_test_split(tmp, test_size=0.5, random_state=seed)

    splits = {"train": tr_idx, "val": val_idx, "test": te_idx}
    counts = {}
    for split, idxs in splits.items():
        d = output_dir / split / class_name
        d.mkdir(parents=True, exist_ok=True)
        for i, si in enumerate(tqdm(idxs, desc=f"  {split}/{class_name}")):
            cv2.imwrite(str(d/f"{class_name}_{split}_{i:05d}.jpg"),
                        X[si], [cv2.IMWRITE_JPEG_QUALITY, 95])
        counts[split] = len(idxs)
    return counts


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--synthetic_dir", default="./dataset/synthetic")
    ap.add_argument("--output_dir",    default="./dataset/final")
    ap.add_argument("--target",        type=int, default=500)
    args = ap.parse_args()

    aug = ConvAugmentor()
    out = Path(args.output_dir)

    for cls in ["class_a","class_b"]:
        print(f"\n{'='*50}\nProcessing {cls}\n{'='*50}")
        imgs   = load_dir(str(Path(args.synthetic_dir)/cls))
        filled = aug.fill_to(imgs, args.target)
        counts = save_split(filled, out, cls)
        print(f"  Split counts: {counts}")

    with open(out/"aug_hyperparams.json","w") as f:
        json.dump(AUG_HP, f, indent=2)
    print(f"\n✓ Dataset ready at {out}")

if __name__ == "__main__":
    main()