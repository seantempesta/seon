"""Parity reference: one deterministic decoder forward on torch/MPS bf16.

Fixed prompt + fixed canvas (seeded), no self-conditioning. Saves the fp32
logits to parity_ref.npz for dg_mlx to compare against.
"""

import numpy as np
import torch
from transformers import AutoTokenizer, DiffusionGemmaForBlockDiffusion

MID = "google/diffusiongemma-26B-A4B-it"

tok = AutoTokenizer.from_pretrained(MID)
chat = [{"role": "user", "content": "Why is the sky blue? Answer in two sentences."}]
enc = tok.apply_chat_template(chat, tokenize=True, add_generation_prompt=True)
ids = enc["input_ids"] if hasattr(enc, "keys") else enc
if isinstance(ids[0], list):
    ids = ids[0]

rng = np.random.RandomState(1234)
canvas = rng.randint(0, 262144, size=(1, 256)).astype(np.int64)

model = DiffusionGemmaForBlockDiffusion.from_pretrained(
    MID, dtype=torch.bfloat16, device_map="mps"
)
model.eval()

with torch.no_grad():
    out = model(
        input_ids=torch.tensor([ids], device="mps"),
        decoder_input_ids=torch.tensor(canvas, device="mps"),
    )
logits = out.logits.float().cpu().numpy()  # [1, 256, V]

np.savez_compressed(
    "parity_ref.npz",
    prompt_ids=np.array(ids, dtype=np.int64),
    canvas=canvas,
    logits=logits,
)
print("saved parity_ref.npz  logits", logits.shape,
      "range", float(logits.min()), float(logits.max()))
print("torch argmax head:", logits[0].argmax(-1)[:16].tolist())
