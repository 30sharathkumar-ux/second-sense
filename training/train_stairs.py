"""
train_stairs.py — stairs-v5 Phase 2
MobileNetV3-Small transfer learning for stairs_up / stairs_down / none

README (10 lines)
──────────────────
1. Collect images with the app's data-collection screen (Phase 1).
2. Run:  adb pull /sdcard/Android/data/.../files/dataset ./dataset
3. Upload the dataset/ folder to your Google Drive.
4. Open this file in Google Colab (Runtime → Change runtime type → GPU T4).
5. Mount Drive and set DATASET_ROOT to your dataset/ path (cell 1).
6. Run all cells. Training takes ~5–10 min on a T4 GPU.
7. Download stairs_model.tflite and stairs_labels.txt from the output cell.
8. Copy both files into your Android project: app/src/main/assets/
9. Tune CONF_THRESHOLD in StairsClassifier.kt if you get false positives.
10. Aim for ≥ 50 images per class before training; 200+ per class is better.
"""

# ─────────────────────────────────────────────────────────────────────────────
# CELL 1 — Config (edit these)
# ─────────────────────────────────────────────────────────────────────────────
DATASET_ROOT   = "/content/drive/MyDrive/dataset"   # path to your dataset/ folder
OUTPUT_DIR     = "/content/stairs_output"            # where model files are saved
IMG_SIZE       = 224
BATCH_SIZE     = 32
EPOCHS         = 30
LR             = 3e-4
FINE_TUNE_AT   = 60    # unfreeze all layers after this epoch (set > EPOCHS to disable)
VAL_SPLIT      = 0.15  # held-out fraction (split by session, not by image)
LABELS         = ["none", "stairs_down", "stairs_up"]   # alphabetical → model output order
SEED           = 42

# ─────────────────────────────────────────────────────────────────────────────
# CELL 2 — Imports & mount Drive
# ─────────────────────────────────────────────────────────────────────────────
import os, random, re, math, warnings
import numpy as np
import tensorflow as tf
from tensorflow import keras
from tensorflow.keras import layers
from sklearn.metrics import confusion_matrix, classification_report
import matplotlib.pyplot as plt
warnings.filterwarnings("ignore")

# Mount Google Drive (skip if running locally)
try:
    from google.colab import drive
    drive.mount("/content/drive")
    print("Drive mounted ✓")
except ImportError:
    print("Not in Colab — skipping Drive mount")

os.makedirs(OUTPUT_DIR, exist_ok=True)
print(f"TF version: {tf.__version__}")
print(f"GPUs: {tf.config.list_physical_devices('GPU')}")

# ─────────────────────────────────────────────────────────────────────────────
# CELL 3 — Session-aware train/val split
#
# Images are named  <session>_<timestamp>.jpg
# We split by session ID so the same scene never appears in both splits.
# ─────────────────────────────────────────────────────────────────────────────

def collect_samples(dataset_root, labels):
    """Returns list of (path, label_index) tuples."""
    samples = []
    for idx, label in enumerate(labels):
        label_dir = os.path.join(dataset_root, label)
        if not os.path.isdir(label_dir):
            print(f"  WARNING: label directory not found: {label_dir}")
            continue
        for fname in os.listdir(label_dir):
            if fname.lower().endswith((".jpg", ".jpeg", ".png")):
                samples.append((os.path.join(label_dir, fname), idx))
    return samples

def session_split(samples, val_split, seed):
    """Split samples into train/val by session ID."""
    rng = random.Random(seed)
    # Extract unique session IDs
    sessions = set()
    for path, _ in samples:
        fname = os.path.basename(path)
        session_id = fname.split("_")[0]
        sessions.add(session_id)
    sessions = sorted(sessions)
    rng.shuffle(sessions)
    n_val = max(1, int(len(sessions) * val_split))
    val_sessions = set(sessions[:n_val])
    train, val = [], []
    for path, label in samples:
        fname = os.path.basename(path)
        sid = fname.split("_")[0]
        (val if sid in val_sessions else train).append((path, label))
    return train, val

samples = collect_samples(DATASET_ROOT, LABELS)
print(f"\nTotal samples: {len(samples)}")
for idx, label in enumerate(LABELS):
    n = sum(1 for _, l in samples if l == idx)
    print(f"  {label}: {n}")

train_samples, val_samples = session_split(samples, VAL_SPLIT, SEED)
print(f"\nTrain: {len(train_samples)}  Val: {len(val_samples)}")

# ─────────────────────────────────────────────────────────────────────────────
# CELL 4 — tf.data pipelines with augmentation
# ─────────────────────────────────────────────────────────────────────────────

def load_image(path, label):
    raw = tf.io.read_file(path)
    img = tf.image.decode_jpeg(raw, channels=3)
    img = tf.image.resize(img, [IMG_SIZE, IMG_SIZE])
    img = tf.cast(img, tf.float32) / 255.0
    return img, label

def augment(img, label):
    # Random brightness ±20%
    img = tf.image.random_brightness(img, 0.20)
    # Random contrast 0.8–1.2
    img = tf.image.random_contrast(img, 0.8, 1.2)
    # Random horizontal flip (stairs are symmetric enough)
    img = tf.image.random_flip_left_right(img)
    # Small rotation ±10°  (via tf.keras layer applied to single image)
    angle_rad = tf.random.uniform([], -10 * math.pi / 180, 10 * math.pi / 180)
    img = tf.py_function(
        lambda i, a: _rotate(i.numpy(), a.numpy()), [img, angle_rad], tf.float32
    )
    img.set_shape([IMG_SIZE, IMG_SIZE, 3])
    # Gaussian blur (simulate motion blur) — approximate with avg pool
    if tf.random.uniform([]) < 0.3:
        img = tf.expand_dims(img, 0)
        img = tf.nn.avg_pool2d(img, ksize=3, strides=1, padding="SAME")
        img = tf.squeeze(img, 0)
    img = tf.clip_by_value(img, 0.0, 1.0)
    return img, label

def _rotate(img_np, angle_rad):
    from scipy.ndimage import rotate as scipy_rotate
    import numpy as np
    angle_deg = float(angle_rad) * 180.0 / math.pi
    rotated = scipy_rotate(img_np, angle_deg, axes=(0, 1), reshape=False, mode="nearest")
    return rotated.astype(np.float32)

def make_dataset(samples, augment_fn=None, shuffle=True):
    paths  = [s[0] for s in samples]
    labels = [s[1] for s in samples]
    ds = tf.data.Dataset.from_tensor_slices((paths, labels))
    ds = ds.map(load_image, num_parallel_calls=tf.data.AUTOTUNE)
    if augment_fn:
        ds = ds.map(augment_fn, num_parallel_calls=tf.data.AUTOTUNE)
    if shuffle:
        ds = ds.shuffle(buffer_size=len(samples), seed=SEED)
    ds = ds.batch(BATCH_SIZE).prefetch(tf.data.AUTOTUNE)
    return ds

train_ds = make_dataset(train_samples, augment_fn=augment, shuffle=True)
val_ds   = make_dataset(val_samples,   augment_fn=None,    shuffle=False)

# ─────────────────────────────────────────────────────────────────────────────
# CELL 5 — Class weights (handle imbalance)
# ─────────────────────────────────────────────────────────────────────────────

counts      = [sum(1 for _, l in train_samples if l == i) for i in range(len(LABELS))]
total       = sum(counts)
class_weight = {i: (total / (len(LABELS) * c)) if c > 0 else 1.0 for i, c in enumerate(counts)}
print("Class weights:", {LABELS[i]: f"{w:.2f}" for i, w in class_weight.items()})

# ─────────────────────────────────────────────────────────────────────────────
# CELL 6 — Model: MobileNetV3-Small + custom head
# ─────────────────────────────────────────────────────────────────────────────

base = keras.applications.MobileNetV3Small(
    input_shape=(IMG_SIZE, IMG_SIZE, 3),
    include_top=False,
    weights="imagenet",
    include_preprocessing=True,   # built-in [0,1]→[-1,1] rescaling
)
base.trainable = False  # freeze during warm-up

inputs = keras.Input(shape=(IMG_SIZE, IMG_SIZE, 3))
x = base(inputs, training=False)
x = layers.GlobalAveragePooling2D()(x)
x = layers.Dropout(0.3)(x)
x = layers.Dense(128, activation="relu")(x)
x = layers.Dropout(0.2)(x)
outputs = layers.Dense(len(LABELS), activation="softmax")(x)

model = keras.Model(inputs, outputs)
model.summary()

model.compile(
    optimizer=keras.optimizers.Adam(LR),
    loss="sparse_categorical_crossentropy",
    metrics=["accuracy"],
)

# ─────────────────────────────────────────────────────────────────────────────
# CELL 7 — Training
# ─────────────────────────────────────────────────────────────────────────────

callbacks = [
    keras.callbacks.EarlyStopping(monitor="val_loss", patience=6, restore_best_weights=True),
    keras.callbacks.ReduceLROnPlateau(monitor="val_loss", factor=0.5, patience=3, min_lr=1e-6),
    keras.callbacks.ModelCheckpoint(
        os.path.join(OUTPUT_DIR, "best_model.keras"),
        monitor="val_accuracy", save_best_only=True, verbose=1
    ),
]

print("\n── Phase A: Warm-up (frozen base) ──")
history = model.fit(
    train_ds,
    validation_data=val_ds,
    epochs=min(EPOCHS, FINE_TUNE_AT),
    class_weight=class_weight,
    callbacks=callbacks,
)

if FINE_TUNE_AT <= EPOCHS:
    print("\n── Phase B: Fine-tuning (all layers unfrozen) ──")
    base.trainable = True
    model.compile(
        optimizer=keras.optimizers.Adam(LR / 10),
        loss="sparse_categorical_crossentropy",
        metrics=["accuracy"],
    )
    history_ft = model.fit(
        train_ds,
        validation_data=val_ds,
        epochs=EPOCHS,
        initial_epoch=min(EPOCHS, FINE_TUNE_AT),
        class_weight=class_weight,
        callbacks=callbacks,
    )

# ─────────────────────────────────────────────────────────────────────────────
# CELL 8 — Evaluation: confusion matrix, per-class recall, FP rate on "none"
# ─────────────────────────────────────────────────────────────────────────────

y_true, y_pred = [], []
for imgs, labels in val_ds:
    preds = model.predict(imgs, verbose=0)
    y_pred.extend(np.argmax(preds, axis=1))
    y_true.extend(labels.numpy())

y_true = np.array(y_true)
y_pred = np.array(y_pred)

cm = confusion_matrix(y_true, y_pred)
print("\n── Confusion Matrix ──")
print("     " + "  ".join(f"{l:>12}" for l in LABELS))
for i, row in enumerate(cm):
    print(f"{LABELS[i]:>10}  " + "  ".join(f"{v:>12}" for v in row))

report = classification_report(y_true, y_pred, target_names=LABELS, output_dict=True)
print("\n── Per-class recall ──")
for label in LABELS:
    print(f"  {label:>14}: recall={report[label]['recall']:.3f}  precision={report[label]['precision']:.3f}  f1={report[label]['f1-score']:.3f}")

none_idx = LABELS.index("none")
# False positive rate on "none": fraction of actual "none" classified as stairs
none_total = cm[none_idx].sum()
none_fp = none_total - cm[none_idx][none_idx]
none_fpr = none_fp / none_total if none_total > 0 else float("nan")
print(f"\n── False-positive rate on 'none': {none_fpr:.3f} ({none_fp}/{none_total} misclassified) ──")
print("  (target: < 0.05 for reliable assistive use)")

# ─────────────────────────────────────────────────────────────────────────────
# CELL 9 — Export float16 TFLite
# ─────────────────────────────────────────────────────────────────────────────

# Representative dataset for calibration
def representative_data():
    for imgs, _ in val_ds.take(20):
        for img in imgs:
            yield [tf.expand_dims(img, 0)]

converter = tf.lite.TFLiteConverter.from_keras_model(model)
converter.optimizations = [tf.lite.Optimize.DEFAULT]
converter.target_spec.supported_types = [tf.float16]
converter.representative_dataset = representative_data

tflite_model = converter.convert()

tflite_path = os.path.join(OUTPUT_DIR, "stairs_model.tflite")
with open(tflite_path, "wb") as f:
    f.write(tflite_model)
print(f"\nSaved: {tflite_path}  ({len(tflite_model)/1024:.1f} KB)")

labels_path = os.path.join(OUTPUT_DIR, "stairs_labels.txt")
with open(labels_path, "w") as f:
    f.write("\n".join(LABELS) + "\n")
print(f"Saved: {labels_path}")

# ─────────────────────────────────────────────────────────────────────────────
# CELL 10 — Quick TFLite sanity check on val set
# ─────────────────────────────────────────────────────────────────────────────

interp = tf.lite.Interpreter(model_path=tflite_path)
interp.allocate_tensors()
inp  = interp.get_input_details()[0]
outp = interp.get_output_details()[0]

y_tflite = []
for imgs, _ in val_ds:
    for img in imgs:
        arr = np.expand_dims(img.numpy().astype(np.float32), 0)
        interp.set_tensor(inp["index"], arr)
        interp.invoke()
        y_tflite.append(np.argmax(interp.get_tensor(outp["index"])))

acc_tflite = np.mean(np.array(y_tflite) == y_true)
print(f"\nTFLite val accuracy: {acc_tflite:.4f}")
print("(should be within ~0.01 of Keras val accuracy)")

# ─────────────────────────────────────────────────────────────────────────────
# CELL 11 — Download files (Colab only)
# ─────────────────────────────────────────────────────────────────────────────

try:
    from google.colab import files
    print("\nDownloading model files...")
    files.download(tflite_path)
    files.download(labels_path)
    print("Done — copy both files to:  app/src/main/assets/")
except ImportError:
    print(f"Files saved to: {OUTPUT_DIR}")
    print("Copy manually:")
    print(f"  {tflite_path}")
    print(f"  {labels_path}")
