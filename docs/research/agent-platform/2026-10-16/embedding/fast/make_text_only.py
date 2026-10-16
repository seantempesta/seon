"""Write a text-only EmbeddingGemma 2 MLX checkpoint (drops vision + audio encoders), optionally quantized.
Usage: python -I make_text_only.py SRC_DIR DST_DIR [--bits 8] [--group 64]
SRC_DIR is the mlx-community bf16 checkpoint; DST_DIR gets model.safetensors (language_model.* only), config.json
(text_config [+ quantization]) and the tokenizer/sentence-transformers files."""
import sys, json, shutil, argparse
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
import mlx.core as mx, mlx.nn as nn
from mlx.utils import tree_flatten
from gemma_text import Encoder
ap = argparse.ArgumentParser(); ap.add_argument("src"); ap.add_argument("dst"); ap.add_argument("--bits", type=int); ap.add_argument("--group", type=int, default=64)
a = ap.parse_args(); src, dst = Path(a.src), Path(a.dst); dst.mkdir(parents=True, exist_ok=True)
enc = Encoder.load(src, compiled=False); cfg = json.load(open(src / "config.json"))
out = {"model_type": "embedding_gemma2_text_only", "text_config": cfg["text_config"]}
if a.bits:
    nn.quantize(enc.model, group_size=a.group, bits=a.bits); out["quantization"] = {"group_size": a.group, "bits": a.bits}
w = {"language_model." + k: v for k, v in tree_flatten(enc.model.parameters())}
mx.save_safetensors(str(dst / "model.safetensors"), w, metadata={"format": "mlx"})
json.dump(out, open(dst / "config.json", "w"), indent=2)
for f in ("tokenizer.json", "tokenizer_config.json", "config_sentence_transformers.json"): shutil.copy(src / f, dst / f)
print(dst, sum(v.nbytes for v in w.values()) / 1e6, "MB", len(w), "tensors")
