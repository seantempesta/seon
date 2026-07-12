"""The sole config surface for seon_needle (src-diffusion convention).

Repo-root resolution: walk UP from this file looking for
`reference-code/needle` (the sentinel — the vendored reference source this
port is proven against), so the package works from any cwd. Env overrides
are INSTANCE selectors, never behavior config.
"""

import os
from pathlib import Path

HF_REPO = "Cactus-Compute/needle"


def repo_root():
    p = Path(__file__).resolve()
    for parent in p.parents:
        if (parent / "reference-code" / "needle").exists():
            return parent
    raise RuntimeError(
        "seon repo root not found (no reference-code/needle above "
        f"{p}); set SEON_NEEDLE_CKPT_DIR explicitly")


def package_root():
    """src-needle/ — the package's own tree (checkpoints live here)."""
    return Path(__file__).resolve().parents[2]


def checkpoints_dir():
    override = os.environ.get("SEON_NEEDLE_CKPT_DIR")
    return Path(override) if override else package_root() / "checkpoints"


def pkl_path():
    return checkpoints_dir() / "needle.pkl"


def weights_path():
    """The converted MLX weights (safetensors)."""
    return checkpoints_dir() / "needle.safetensors"


def model_config_path():
    return checkpoints_dir() / "config.json"


def tokenizer_path():
    return checkpoints_dir() / "needle.model"
