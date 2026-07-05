"""Parity check: same deterministic decoder forward on dg_mlx (8-bit),
compared against the torch/MPS bf16 reference logits in parity_ref.npz.

8-bit quantization means logits won't match bitwise; what we require:
- high argmax agreement across the 256 canvas positions
- reference argmax token inside the MLX top-5 nearly everywhere
- high per-position logit correlation
"""

import glob

import numpy as np
import mlx.core as mx

from dg_mlx.model import load_model

ref = np.load("parity_ref.npz")
prompt_ids, canvas, ref_logits = ref["prompt_ids"], ref["canvas"], ref["logits"][0]

S = glob.glob(
    "/Users/sean/.cache/huggingface/hub/"
    "models--mlx-community--diffusiongemma-26B-A4B-it-8bit/snapshots/*"
)[0]
m = load_model(S)

cache = m.new_cache()
m.encode(mx.array(prompt_ids)[None, :], cache, past_len=0)
logits = m.decode(mx.array(canvas), cache, canvas_start=len(prompt_ids))
mlx_logits = np.array(logits[0].astype(mx.float32))  # [256, V]

ref_arg = ref_logits.argmax(-1)
mlx_arg = mlx_logits.argmax(-1)
agree = (ref_arg == mlx_arg).mean()

top5 = np.argsort(-mlx_logits, axis=-1)[:, :5]
in_top5 = np.array([ref_arg[i] in top5[i] for i in range(len(ref_arg))]).mean()

corrs = [np.corrcoef(ref_logits[i], mlx_logits[i])[0, 1] for i in range(0, 256, 8)]

print(f"argmax agreement:        {agree:.3f}  ({int(agree * 256)}/256 positions)")
print(f"ref argmax in MLX top-5: {in_top5:.3f}")
print(f"logit corr (32 pos):     mean {np.mean(corrs):.4f}  min {np.min(corrs):.4f}")
print("ref  argmax head:", ref_arg[:16].tolist())
print("mlx  argmax head:", mlx_arg[:16].tolist())
