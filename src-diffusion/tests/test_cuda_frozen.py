"""The CUDA quarantine stays frozen: present, compilable, NOT imported."""

import py_compile
import sys
from pathlib import Path

CUDA = Path(__file__).parents[1] / "src" / "seon_diffusion" / "cuda"


def test_frozen_files_present_and_compile():
    for name in ("gpu_worker.py", "diffgemma_common.py", "oracle_shim.py"):
        f = CUDA / name
        assert f.exists()
        assert f.read_text().startswith("# FROZEN")
        py_compile.compile(str(f), doraise=True)


def test_living_surface_does_not_import_cuda():
    import seon_diffusion  # noqa: F401
    assert not any(m.startswith("seon_diffusion.cuda.") or m == "seon_diffusion.cuda"
                   for m in sys.modules)
