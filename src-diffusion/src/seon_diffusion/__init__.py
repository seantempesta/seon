"""seon_diffusion — the verified code_buffer: oracle-guided DiffusionGemma
generation on the local MLX model, integrated with seon's co-located
oracles (bb parse/lint/phase, stateful node eval session).

Perf convention (owner): report in TOKENS/SECOND, always.
"""

from .generate import GenConfig, generate
from .model import DiffusionGemmaVLM, load_model
from .control import generate_guided
from .oracle import Oracle, EvalSession

__all__ = ["GenConfig", "generate", "DiffusionGemmaVLM", "load_model",
           "generate_guided", "Oracle", "EvalSession"]
