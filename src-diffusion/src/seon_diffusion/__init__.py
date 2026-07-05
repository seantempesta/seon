"""seon_diffusion — the verified canvas: oracle-guided DiffusionGemma
generation on the local MLX model, integrated with seon's co-located
oracles (bb parse/lint/phase, stateful node eval session).

Perf convention (owner): report in TOKENS/SECOND, always.
"""

from .generate import GenConfig, generate
from .model import DiffusionGemmaMLX, load_model
from .control import generate_guided
from .repair import try_repair
from .oracle import Oracle, EvalSession

__all__ = ["GenConfig", "generate", "DiffusionGemmaMLX", "load_model",
           "generate_guided", "try_repair", "Oracle", "EvalSession"]
