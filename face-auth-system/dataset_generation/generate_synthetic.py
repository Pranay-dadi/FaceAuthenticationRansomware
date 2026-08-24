"""
Single-Image Synthetic Face Generator
=====================================
Algorithm: Geometric pose simulation (affine + perspective warping) ×
           Illumination variation (PIL photometric transforms) ×
           Scale/crop augmentation.

Hyperparameters
---------------
POSE_RANGE_X  : pitch  ±15°
POSE_RANGE_Y  : yaw    ±20°
POSE_RANGE_Z  : roll   ±10°
BRIGHTNESS_RANGE : [0.30, 1.80]
CONTRAST_RANGE   : [0.40, 2.00]
SCALE_RANGE      : [0.75, 1.30]
JPEG_QUALITY     : [60, 95]
OUTPUT_SIZE      : 224×224 (MobileNetV2 standard)
RANDOM_SEED      : 42
"""

import cv2
import numpy as np
from PIL import Image, ImageEnhance
import mediapipe as mp
import os, random, math, json, argparse
from pathlib import Path
from tqdm import tqdm

RANDOM_SEED = 42
random.seed(RANDOM_SEED)
np.random.seed(RANDOM_SEED)

# ── Hyperparameters ────────────────────────────────────────────────
HP = {
    "POSE_RANGE_X":       (-15,  15),   # pitch degrees
    "POSE_RANGE_Y":       (-20,  20),   # yaw degrees
    "POSE_RANGE_Z":       (-10,  10),   # roll degrees
    "BRIGHTNESS_RANGE":   (0.30, 1.80),
    "CONTRAST_RANGE":     (0.40, 2.00),
    "SCALE_RANGE":        (0.75, 1.30),
    "JPEG_QUALITY_RANGE": (60,   95),
    "BLUR_KERNELS":       [3, 5],
    "OUTPUT_SIZE":        (224, 224),
    "RANDOM_SEED":        42,
}


class SyntheticFaceGenerator:

    def __init__(self, source_path: str, output_dir: str, num_images: int = 500):
        self.src   = Path(source_path)
        self.out   = Path(output_dir)
        self.n     = num_images
        self.out.mkdir(parents=True, exist_ok=True)

    # ── loaders ───────────────────────────────────────────────────
    def _load(self) -> np.ndarray:
        img = cv2.imread(str(self.src))
        if img is None:
            raise FileNotFoundError(self.src)
        return cv2.resize(img, HP["OUTPUT_SIZE"])

    # ── transforms ───────────────────────────────────────────────
    def _pose(self, img: np.ndarray,
              ax: float = 0, ay: float = 0, az: float = 0) -> np.ndarray:
        """Affine roll + perspective yaw/pitch simulation."""
        h, w = img.shape[:2]
        # Roll
        M = cv2.getRotationMatrix2D((w//2, h//2), az, 1.0)
        img = cv2.warpAffine(img, M, (w, h), borderMode=cv2.BORDER_REFLECT)
        # Yaw/pitch via perspective warp
        fy = math.tan(math.radians(abs(ay))) * 0.28
        fx = math.tan(math.radians(abs(ax))) * 0.18
        dx, dy = int(w*fy), int(h*fx)
        src_pts = np.float32([[0,0],[w,0],[0,h],[w,h]])
        if ay > 0:
            dst = np.float32([[0,0],[w-dx,dy//2],[0,h],[w-dx,h-dy//2]])
        elif ay < 0:
            dst = np.float32([[dx,dy//2],[w,0],[dx,h-dy//2],[w,h]])
        else:
            dst = src_pts.copy()
        if ax > 0:
            dst[0] += [0, dy]; dst[1] += [0, dy]
        elif ax < 0:
            dst[2] -= [0, dy]; dst[3] -= [0, dy]
        dst = np.clip(dst, 0, [w, h]).astype(np.float32)
        M2 = cv2.getPerspectiveTransform(src_pts, dst)
        return cv2.warpPerspective(img, M2, (w, h), borderMode=cv2.BORDER_REFLECT)

    def _light(self, img: np.ndarray, mode: str = "random") -> np.ndarray:
        pil = Image.fromarray(cv2.cvtColor(img, cv2.COLOR_BGR2RGB))
        if mode == "random":
            mode = random.choice(["bright","dark","contrast","warm",
                                   "cool","shadow","flat"])
        if mode == "bright":
            pil = ImageEnhance.Brightness(pil).enhance(random.uniform(1.2, 1.8))
        elif mode == "dark":
            pil = ImageEnhance.Brightness(pil).enhance(random.uniform(0.3, 0.7))
        elif mode == "contrast":
            pil = ImageEnhance.Contrast(pil).enhance(random.uniform(0.4, 2.0))
        elif mode == "warm":
            r,g,b = pil.split()
            r = r.point(lambda x: min(255, x+random.randint(20,40)))
            b = b.point(lambda x: max(0,   x-random.randint(15,30)))
            pil = Image.merge("RGB",(r,g,b))
        elif mode == "cool":
            r,g,b = pil.split()
            b = b.point(lambda x: min(255, x+random.randint(20,40)))
            r = r.point(lambda x: max(0,   x-random.randint(15,30)))
            pil = Image.merge("RGB",(r,g,b))
        elif mode == "shadow":
            W, H = pil.size
            direction = random.choice(["left","right","top","bottom"])
            strength  = random.uniform(0.3, 0.6)
            mask = Image.new("L",(W,H))
            px   = mask.load()
            for x in range(W):
                for y in range(H):
                    if   direction=="left":   v=int(255*strength*(1-x/W))
                    elif direction=="right":  v=int(255*strength*(x/W))
                    elif direction=="top":    v=int(255*strength*(1-y/H))
                    else:                     v=int(255*strength*(y/H))
                    px[x,y]=v
            pil = Image.composite(Image.new("RGB",(W,H),(0,0,0)), pil, mask)
        # "flat" → no change
        return cv2.cvtColor(np.array(pil), cv2.COLOR_RGB2BGR)

    def _scale(self, img: np.ndarray, factor: float = None) -> np.ndarray:
        if factor is None:
            factor = random.uniform(*HP["SCALE_RANGE"])
        h, w = img.shape[:2]
        nh, nw = int(h*factor), int(w*factor)
        resized = cv2.resize(img, (nw, nh))
        if factor >= 1.0:
            sy=(nh-h)//2; sx=(nw-w)//2
            return resized[sy:sy+h, sx:sx+w]
        py=(h-nh)//2; px=(w-nw)//2
        return cv2.copyMakeBorder(resized, py, h-nh-py, px, w-nw-px,
                                   cv2.BORDER_REFLECT)[:h,:w]

    def _jpeg(self, img: np.ndarray) -> np.ndarray:
        q = random.randint(*HP["JPEG_QUALITY_RANGE"])
        _, buf = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, q])
        return cv2.imdecode(buf, cv2.IMREAD_COLOR)

    def _blur(self, img: np.ndarray) -> np.ndarray:
        k = random.choice(HP["BLUR_KERNELS"])
        return cv2.GaussianBlur(img, (k,k), random.uniform(0.5,1.5))

    # ── main pipeline ─────────────────────────────────────────────
    def generate(self) -> list:
        src = self._load()
        imgs = [src, cv2.flip(src,1)]        # include original + flip

        # Step 1: systematic pose × lighting grid
        lighting_modes = ["bright","dark","contrast","warm","cool","shadow","flat"]
        for ax in np.linspace(*HP["POSE_RANGE_X"], 5):
            for ay in np.linspace(*HP["POSE_RANGE_Y"], 5):
                for az in np.linspace(*HP["POSE_RANGE_Z"], 3):
                    for mode in lighting_modes:
                        if len(imgs) >= self.n: break
                        imgs.append(self._light(self._pose(src, ax, ay, az), mode))

        # Step 2: scale × lighting
        for sc in np.linspace(*HP["SCALE_RANGE"], 7):
            for mode in lighting_modes:
                if len(imgs) >= self.n: break
                imgs.append(self._light(self._scale(src, float(sc)), mode))

        # Step 3: random combos to hit target
        ops_pool = ["pose","light","scale","jpeg","blur"]
        pbar = tqdm(total=self.n - len(imgs), desc="Random combos")
        while len(imgs) < self.n:
            v = src.copy()
            for op in random.sample(ops_pool, k=random.randint(1,4)):
                if op == "pose":
                    v = self._pose(v,
                        random.uniform(*HP["POSE_RANGE_X"]),
                        random.uniform(*HP["POSE_RANGE_Y"]),
                        random.uniform(*HP["POSE_RANGE_Z"]))
                elif op == "light": v = self._light(v)
                elif op == "scale": v = self._scale(v)
                elif op == "jpeg":  v = self._jpeg(v)
                elif op == "blur":  v = self._blur(v)
            imgs.append(v); pbar.update(1)
        pbar.close()
        return imgs[:self.n]

    def save(self, images: list, class_name: str):
        d = self.out / class_name
        d.mkdir(parents=True, exist_ok=True)
        for i, img in enumerate(tqdm(images, desc=f"Saving {class_name}")):
            cv2.imwrite(str(d/f"{class_name}_{i:05d}.jpg"), img,
                        [cv2.IMWRITE_JPEG_QUALITY, 95])
        with open(d/"metadata.json","w") as f:
            json.dump({"class":class_name,"count":len(images),"hyperparameters":HP}, f, indent=2)
        print(f"✓ {class_name}: {len(images)} images → {d}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--source_a", required=True)
    ap.add_argument("--source_b", required=True)
    ap.add_argument("--output_dir", default="./dataset/synthetic")
    ap.add_argument("--num_images", type=int, default=500)
    args = ap.parse_args()

    for cls, src in [("class_a", args.source_a), ("class_b", args.source_b)]:
        print(f"\n{'='*50}\nGenerating {cls}\n{'='*50}")
        g = SyntheticFaceGenerator(src, args.output_dir, args.num_images)
        imgs = g.generate()
        g.save(imgs, cls)

if __name__ == "__main__":
    main()