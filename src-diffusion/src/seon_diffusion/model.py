"""DiffusionGemma model layer — an adapter over mlx_vlm's DiffusionGemma.

History (Round 9, typeahead-hole-filling research 2026-07-10): this file
used to be a from-scratch MLX port of the DiffusionGemma forward. Its
ENCODER corrupted beyond ~8-10k context (the decoder was proven correct
by a transplant test: mlx_vlm's prefill cache + our decoder retrieved
needles perfectly at 10k), and mlx_vlm 0.6.4 ships DiffusionGemma with a
correct, ~4x-faster chunked prefill. The transformer forward is not our
IP — the oracle loop / driver FSM / glyph protocol are — so the model
surface here (load_model / new_cache / encode / decode / cfg) is now
implemented over mlx_vlm.models.diffusion_gemma, keeping the loop logic
in generate.py / control.py / cursor.py unchanged. The old forward lives
in git history (this file, before 2026-07-10).

mlx_vlm seams used (read mlx_vlm/models/diffusion_gemma/{diffusion_gemma,
language}.py before touching this):

- Model.make_cache() — per-layer KV caches: KVCache for full-attention
  layers, RotatingKVCache(sliding_window) for sliding layers. The list is
  MUTATED in place by the encoder (update_and_fetch appends), so cache
  identity is stable across encode calls.
- Model.diffusion_prefill_cache(ids, cache=..., prefill_step_size,
  chunk_prefill) — encoder prefill; chunked when the prompt exceeds the
  step size (language.py evals + clears the Metal cache per chunk).
- Model.diffusion_update_cache(ids, cache=...) — the incremental
  commit-encode: the encoder APPENDS to the same cache, and RoPE offsets
  come from the cache offset (language.py `_cache_offset`), so appends
  are positionally correct. This is the harvest path.
- Model.diffusion_decoder_logits(code_buffer_ids, cache, self_conditioning) —
  one denoiser forward, read-only w.r.t. the cache (decoder=True skips
  update_and_fetch); code_buffer RoPE offset = cache offset; returns fp32
  softcapped logits [B, L, V].
- Model.prefers_logits_self_conditioning — True for the 8-bit checkpoint
  (QuantizedEmbedding), so `self_conditioning` is the raw logits of the
  previous forward (exactly what the denoise loops pass).
"""

from dataclasses import dataclass
from pathlib import Path

import mlx.core as mx

# mlx_vlm's DEFAULT_PREFILL_STEP_SIZE (generate/dispatch.py)
PREFILL_STEP = 2048


@dataclass
class DGConfig:
    """The config slice the denoise loops consume."""
    code_buffer_length: int
    vocab_size: int


class _LayerCache:
    """Dict-face ("k"/"v") over one mlx_vlm KV cache layer, so the loops'
    `mx.eval(cache[0]["k"])` sync points keep working unchanged."""

    __slots__ = ("_c",)

    def __init__(self, c):
        self._c = c

    def __getitem__(self, key):
        if key == "k":
            return self._c.keys
        if key == "v":
            return self._c.values
        raise KeyError(key)


class Cache(list):
    """The loop-facing cache: a list of per-layer dict-faces + the raw
    mlx_vlm cache list (`.vlm`) that the adapter feeds back to mlx_vlm."""

    def __init__(self, vlm_cache):
        super().__init__(_LayerCache(c) for c in vlm_cache)
        self.vlm = vlm_cache

    @property
    def offset(self):
        """Tokens encoded so far (every layer cache tracks the same total)."""
        c = self.vlm[0]
        if getattr(c, "keys", None) is None:
            return 0
        off = c.offset
        return int(mx.max(off).item()) if isinstance(off, mx.array) else int(off)


class DiffusionGemmaVLM:
    """The model surface generate/control/cursor consume, over mlx_vlm."""

    def __init__(self, vlm_model):
        self.vlm = vlm_model
        cfg = vlm_model.config
        # `canvas_length` is mlx_vlm's OWN field name (the checkpoint's
        # config.json vocabulary) — a third-party boundary the seon-side
        # canvas->code_buffer rename must not cross (live-hit 33ee4673:
        # every worker call AttributeError'd).
        self.cfg = DGConfig(code_buffer_length=cfg.canvas_length,
                            vocab_size=cfg.text_config.vocab_size)
        if not vlm_model.prefers_logits_self_conditioning:
            # Non-quantized embeddings want self-conditioning EMBEDDINGS;
            # the loops pass logits. Only the 8-bit checkpoint is supported.
            raise ValueError(
                "checkpoint does not prefer logits self-conditioning "
                "(unquantized embeddings?) — this adapter passes the loops' "
                "raw logits straight through and supports only the 8-bit "
                "QuantizedEmbedding checkpoint")

    def new_cache(self):
        return Cache(self.vlm.make_cache())

    def encode(self, ids, cache, past_len=0):
        """Causal encode of `ids` (prompt or committed text) into `cache`.

        First call (past_len=0) is the prefill — chunked when the prompt
        exceeds PREFILL_STEP. Later calls (past_len>0) are incremental
        appends via diffusion_update_cache (the harvest path)."""
        if past_len != cache.offset:
            raise ValueError(
                f"encode past_len={past_len} != cache offset {cache.offset} "
                "— the caller's position bookkeeping is out of sync")
        if past_len == 0:
            n = ids.shape[1]
            self.vlm.diffusion_prefill_cache(
                ids, cache=cache.vlm,
                prefill_step_size=PREFILL_STEP,
                chunk_prefill=n > PREFILL_STEP)
        else:
            self.vlm.diffusion_update_cache(ids, cache=cache.vlm)

    def decode(self, code_buffer_ids, cache, code_buffer_start, self_conditioning_logits=None):
        """One denoiser forward: code_buffer + read-only cache -> fp32 logits."""
        if code_buffer_start != cache.offset:
            raise ValueError(
                f"decode code_buffer_start={code_buffer_start} != cache offset "
                f"{cache.offset} — the caller's position bookkeeping is out "
                "of sync")
        return self.vlm.diffusion_decoder_logits(
            code_buffer_ids, cache=cache.vlm,
            self_conditioning=self_conditioning_logits)


def load_model(snapshot_path):
    """Load the mlx-community snapshot via mlx_vlm; return the adapter."""
    from mlx_vlm.utils import load_model as vlm_load_model
    return DiffusionGemmaVLM(vlm_load_model(Path(snapshot_path)))
