"""Convert the pretrained needle checkpoint (flax pickle) to safetensors.

Downloads `needle.pkl` + the tokenizer from HuggingFace
(Cactus-Compute/needle) into checkpoints/, then writes
`needle.safetensors` + `config.json`.

The flax param tree is preserved verbatim in the safetensors key names
("/"-joined paths) with ONE transformation: flax `nn.scan` stacks every
per-layer param along a leading num_layers axis (variable_axes=
{"params": 0}, scan body auto-named `EncoderBlock_0`/`DecoderBlock_0`).
We split that axis so each layer's params get their own key:

    encoder/layers/EncoderBlock_0/self_attn/q_proj/kernel  (12, 512, 512)
      -> encoder/layers/{0..11}/self_attn/q_proj/kernel     (512, 512)

Dtype: the pkl stores float16; we save the bytes as stored. The reference
runtime (run.py load_checkpoint) casts everything to bfloat16 at load —
model.py does the same cast, so both sides see identical rounding.
"""

import json
import pickle

import numpy as np
from safetensors.numpy import save_file

from . import config

SCAN_BLOCKS = ("EncoderBlock_0", "DecoderBlock_0")


def download():
    """Fetch needle.pkl + tokenizer files from HF into checkpoints/."""
    import shutil

    from huggingface_hub import hf_hub_download

    ckpt_dir = config.checkpoints_dir()
    ckpt_dir.mkdir(parents=True, exist_ok=True)
    for fname in ["needle.pkl", "tokenizer/needle.model", "tokenizer/needle.vocab"]:
        dst = ckpt_dir / fname.split("/")[-1]
        if dst.exists():
            continue
        src = hf_hub_download(repo_id=config.HF_REPO, filename=fname)
        shutil.copy(src, dst)
        print(f"downloaded {dst}")


def flatten_tree(tree):
    """Flatten a nested param dict to {'a/b/c': ndarray}."""
    out = {}

    def walk(node, path):
        if isinstance(node, dict):
            for k, v in node.items():
                walk(v, path + [k])
        else:
            out["/".join(path)] = np.asarray(node)

    walk(tree, [])
    return out


def split_scanned(flat):
    """Split flax nn.scan's stacked leading num_layers axis into per-layer keys."""
    out = {}
    for key, arr in flat.items():
        parts = key.split("/")
        block = next((b for b in parts if b in SCAN_BLOCKS), None)
        if block is None:
            out[key] = arr
            continue
        i = parts.index(block)
        head, tail = parts[:i], parts[i + 1:]
        for layer in range(arr.shape[0]):
            out["/".join(head + [str(layer)] + tail)] = np.ascontiguousarray(arr[layer])
    return out


def convert():
    download()
    with open(config.pkl_path(), "rb") as f:
        data = pickle.load(f)

    flat = split_scanned(flatten_tree(data["params"]))
    save_file(flat, str(config.weights_path()))
    with open(config.model_config_path(), "w") as f:
        json.dump(data["config"], f, indent=2)

    n_params = sum(int(np.prod(a.shape)) for a in flat.values())
    print(f"wrote {config.weights_path()} ({len(flat)} tensors, {n_params:,} params)")
    print(f"wrote {config.model_config_path()}")


if __name__ == "__main__":
    convert()
