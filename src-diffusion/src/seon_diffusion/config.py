"""The sole config surface for seon_diffusion (src-inspect-ai convention).

Repo-root resolution: walk UP from this file looking for `bin/oracle-server`
(the sentinel), so the package works from any cwd. Env overrides exist for
each co-located artifact; they are INSTANCE selectors, never behavior config.
"""

import os
from pathlib import Path


def repo_root():
    p = Path(__file__).resolve()
    for parent in p.parents:
        if (parent / "bin" / "oracle-server").exists():
            return parent
    raise RuntimeError("seon repo root not found (no bin/oracle-server above "
                       f"{p}); set SEON_ORACLE_BB / SEON_EVAL_BUNDLE explicitly")


def oracle_bb():
    return os.environ.get("SEON_ORACLE_BB") or str(repo_root() / "bin" / "oracle-server")


def eval_bundle():
    return os.environ.get("SEON_EVAL_BUNDLE") or str(
        repo_root() / "out" / "worker-oracle-eval" / "main.js")


def model_snapshot():
    """Newest local HF snapshot of the 8-bit MLX checkpoint."""
    override = os.environ.get("SEON_DG_SNAPSHOT")
    if override:
        return override
    import glob
    pat = os.path.expanduser(
        "~/.cache/huggingface/hub/"
        "models--mlx-community--diffusiongemma-26B-A4B-it-8bit/snapshots/*")
    snaps = sorted(glob.glob(pat))
    if not snaps:
        raise RuntimeError(f"no MLX checkpoint under {pat}; set SEON_DG_SNAPSHOT")
    return snaps[-1]
