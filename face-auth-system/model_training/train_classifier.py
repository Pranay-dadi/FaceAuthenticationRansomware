"""
MobileNetV2 Binary Face Classifier
====================================
Architecture
------------
  Base  : MobileNetV2 (ImageNet pretrained, 154 layers)
  Head  : GlobalAveragePooling2D
          → Dropout(0.30)
          → Dense(128, relu, L2=1e-4)
          → Dropout(0.15)
          → Dense(2, softmax)

Training protocol
-----------------
  Phase 1 — freeze base, train head
    LR=1e-3, batch=32, epochs=10, optimizer=Adam
  Phase 2 — unfreeze from layer 100 onward
    LR=1e-5, batch=32, epochs=20, optimizer=Adam

Callbacks: EarlyStopping(patience=5), ReduceLROnPlateau, ModelCheckpoint

Class B confidence threshold at inference: 0.85
"""

import tensorflow as tf
from tensorflow.keras import layers, models, optimizers, regularizers
from tensorflow.keras.applications import MobileNetV2
from tensorflow.keras.callbacks import (ModelCheckpoint, EarlyStopping,
                                        ReduceLROnPlateau, TensorBoard)
from tensorflow.keras.preprocessing.image import ImageDataGenerator
import numpy as np, json, argparse, matplotlib.pyplot as plt
from pathlib import Path

HP = {
    "BASE_MODEL":        "MobileNetV2",
    "INPUT_SHAPE":       (224, 224, 3),
    "NUM_CLASSES":       2,
    "BATCH_SIZE":        32,
    # Phase 1
    "PHASE1_EPOCHS":    10,
    "PHASE1_LR":        1e-3,
    # Phase 2
    "PHASE2_EPOCHS":    20,
    "PHASE2_LR":        1e-5,
    "FINE_TUNE_AT":     100,        # unfreeze from this layer index
    # Regularisation
    "DROPOUT_RATE":     0.30,
    "L2_WEIGHT":        1e-4,
    # Early stopping
    "PATIENCE":         5,
    # Inference threshold for Class B
    "CLASS_B_THRESHOLD": 0.85,
    "PRETRAINED":        "imagenet",
    "RANDOM_SEED":       42,
}


class Trainer:

    def __init__(self, dataset_dir: str, output_dir: str):
        self.ds  = Path(dataset_dir)
        self.out = Path(output_dir)
        self.out.mkdir(parents=True, exist_ok=True)
        self.model = None
        self.h1 = None
        self.h2 = None

    def _make_generators(self):
        train_gen = ImageDataGenerator(
            rescale=1./255,
            horizontal_flip=True,
            rotation_range=15,
            brightness_range=[0.7, 1.3],
            zoom_range=0.10,
            width_shift_range=0.05,
            height_shift_range=0.05,
            fill_mode="reflect",
        ).flow_from_directory(
            str(self.ds/"train"),
            target_size=HP["INPUT_SHAPE"][:2],
            batch_size=HP["BATCH_SIZE"],
            class_mode="categorical",
            shuffle=True,
            seed=HP["RANDOM_SEED"],
        )

        val_gen = ImageDataGenerator(rescale=1./255).flow_from_directory(
            str(self.ds/"val"),
            target_size=HP["INPUT_SHAPE"][:2],
            batch_size=HP["BATCH_SIZE"],
            class_mode="categorical",
            shuffle=False,
        )

        test_gen = ImageDataGenerator(rescale=1./255).flow_from_directory(
            str(self.ds/"test"),
            target_size=HP["INPUT_SHAPE"][:2],
            batch_size=HP["BATCH_SIZE"],
            class_mode="categorical",
            shuffle=False,
        )
        return train_gen, val_gen, test_gen

    def _build(self) -> tf.keras.Model:
        base = MobileNetV2(input_shape=HP["INPUT_SHAPE"],
                           include_top=False,
                           weights=HP["PRETRAINED"])
        base.trainable = False

        inp = tf.keras.Input(shape=HP["INPUT_SHAPE"])
        # MobileNetV2 expects pixel values scaled to [-1, 1]
        x = tf.keras.applications.mobilenet_v2.preprocess_input(inp * 255.0)
        x = base(x, training=False)
        x = layers.GlobalAveragePooling2D()(x)
        x = layers.Dropout(HP["DROPOUT_RATE"])(x)
        x = layers.Dense(128, activation="relu",
                          kernel_regularizer=regularizers.l2(HP["L2_WEIGHT"]))(x)
        x = layers.Dropout(HP["DROPOUT_RATE"] / 2)(x)
        out = layers.Dense(HP["NUM_CLASSES"], activation="softmax")(x)

        return models.Model(inp, out, name="FaceClassifier")

    def _callbacks(self, tag: str):
        return [
            ModelCheckpoint(str(self.out/f"best_{tag}.keras"),
                            monitor="val_accuracy", save_best_only=True, verbose=1),
            EarlyStopping(monitor="val_accuracy", patience=HP["PATIENCE"],
                          restore_best_weights=True, verbose=1),
            ReduceLROnPlateau(monitor="val_loss", factor=0.5, patience=3,
                              min_lr=1e-8, verbose=1),
            TensorBoard(log_dir=str(self.out/"logs"/tag)),
        ]

    def phase1(self, train_gen, val_gen):
        print("\n── Phase 1: train head (frozen base) ──")
        self.model = self._build()
        self.model.compile(
            optimizer=optimizers.Adam(HP["PHASE1_LR"]),
            loss="categorical_crossentropy",
            metrics=["accuracy", tf.keras.metrics.AUC(name="auc")],
        )
        self.h1 = self.model.fit(train_gen, epochs=HP["PHASE1_EPOCHS"],
                                  validation_data=val_gen,
                                  callbacks=self._callbacks("phase1"), verbose=1)

    def phase2(self, train_gen, val_gen):
        print("\n── Phase 2: fine-tune top layers ──")
        self.model = tf.keras.models.load_model(str(self.out/"best_phase1.keras"))
        base = [l for l in self.model.layers
                if isinstance(l, tf.keras.Model)][0]
        base.trainable = True
        for layer in base.layers[:HP["FINE_TUNE_AT"]]:
            layer.trainable = False
        self.model.compile(
            optimizer=optimizers.Adam(HP["PHASE2_LR"]),
            loss="categorical_crossentropy",
            metrics=["accuracy", tf.keras.metrics.AUC(name="auc")],
        )
        self.h2 = self.model.fit(train_gen, epochs=HP["PHASE2_EPOCHS"],
                                  validation_data=val_gen,
                                  callbacks=self._callbacks("phase2"), verbose=1)

    def evaluate(self, test_gen) -> dict:
        print("\n── Evaluation on test set ──")
        m = tf.keras.models.load_model(str(self.out/"best_phase2.keras"))
        results = m.evaluate(test_gen, verbose=1)
        metrics = {"test_loss": float(results[0]),
                   "test_accuracy": float(results[1]),
                   "test_auc": float(results[2])}
        # per-class accuracy
        preds = m.predict(test_gen)
        y_pred = preds.argmax(axis=1)
        y_true = test_gen.classes
        for cls, idx in test_gen.class_indices.items():
            mask = y_true == idx
            metrics[f"{cls}_accuracy"] = float(np.mean(y_pred[mask] == y_true[mask]))
        print(json.dumps(metrics, indent=2))
        with open(self.out/"test_metrics.json","w") as f:
            json.dump({**metrics, **HP}, f, indent=2)
        return metrics

    def plot(self):
        fig, axes = plt.subplots(1, 2, figsize=(14, 5))
        for col, (tag, h) in enumerate([("Phase 1",self.h1),("Phase 2",self.h2)]):
            if h is None: continue
            axes[col].plot(h.history["accuracy"],     label="train acc")
            axes[col].plot(h.history["val_accuracy"], label="val acc")
            axes[col].set_title(f"{tag} — accuracy")
            axes[col].set_xlabel("Epoch"); axes[col].legend(); axes[col].grid(True)
        plt.tight_layout()
        plt.savefig(str(self.out/"training_curves.png"), dpi=150)
        print(f"✓ training_curves.png saved")

    def train(self):
        train_gen, val_gen, test_gen = self._make_generators()
        self.phase1(train_gen, val_gen)
        self.phase2(train_gen, val_gen)
        metrics = self.evaluate(test_gen)
        self.plot()
        with open(self.out/"hyperparameters.json","w") as f:
            json.dump(HP, f, indent=2)
        print(f"\n✓ Done — test accuracy: {metrics['test_accuracy']:.4f}")
        return metrics


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dataset_dir", default="./dataset/final")
    ap.add_argument("--output_dir",  default="./model_output")
    args = ap.parse_args()
    Trainer(args.dataset_dir, args.output_dir).train()

if __name__ == "__main__":
    main()