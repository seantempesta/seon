"""Convert a CLM torch checkpoint (``CLM_v0.1-8B.pt``) to safetensors for MLX.

    seon-clm-convert ~/.cache/clm/CLM_v0.1-8B.pt      # -> ~/.cache/clm/CLM_v0.1-8B.safetensors

The only step that needs torch. Tensors keep the head's own parameter names under
``state.`` / ``action.``; ``cfg``, ``projection_dim`` and ``logit_scale`` travel as
JSON in the ``clm`` metadata entry.
"""
from __future__ import annotations

import argparse
import json
import os


def convert(src: str, dst: str | None = None) -> str:
    import torch
    from safetensors.numpy import save_file
    ck = torch.load(src, map_location="cpu", weights_only=False)
    tensors = {f"{side}.{k}": v.float().numpy()
               for side in ("state", "action") for k, v in ck[f"{side}_head"].items()}
    meta = {"cfg": dict(ck["cfg"]), "projection_dim": ck.get("projection_dim"),
            "logit_scale": float(torch.as_tensor(ck["logit_scale"]).float()), "source": os.path.basename(src)}
    dst = dst or os.path.splitext(src)[0] + ".safetensors"
    save_file(tensors, dst, metadata={"clm": json.dumps(meta, default=str)})
    return dst


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("src")
    ap.add_argument("dst", nargs="?")
    a = ap.parse_args()
    print(convert(a.src, a.dst))


if __name__ == "__main__":
    main()
