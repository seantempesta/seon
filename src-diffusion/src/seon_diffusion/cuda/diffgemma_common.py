# FROZEN (2026-07-05): quarantined RunPod CUDA artifact — superseded by
# seon_diffusion.control + the MLX worker. Revive by need; see cuda/__init__.py.
"""DiffusionGemma — shared helpers (the ONE canonical copy).

Leaf module, NO @Endpoint, no model load at import time. Holds the primitives the
worker's clamp/infill/trace modes reuse, grounded in the REAL transformers
source (`transformers/models/diffusion_gemma/generation_diffusion_gemma.py`).

THE CORRECTED MECHANISM (see model-mechanics-grounding-2026-06-28.md):
DiffusionGemma is NOT an absorbing-MASK diffuser. There is no mask token. The
`EntropyBoundSampler` random-inits the canvas (`initialize_canvas`, :390) and
re-noises every NON-accepted position with fresh uniformly-random vocab ids
(`renoise_canvas`, :446) — never a MASK sentinel. "Commit" is emergent: a
low-entropy position keeps getting accepted (`accept_canvas`, :402) step after
step, so it persists; it is never frozen.

Therefore the old `resolve_mask_id` + mask-hole `build_offset_map` are DELETED —
they were built on a premise the source contradicts. The clamp primitive is a
`LogitsProcessor` (`ClampLogitsProcessor` below), the documented seam
`generate()` applies every denoise step (`_denoising_step`, :1040).

torch / transformers are imported lazily so the pure char-span helpers
(`build_offset_map`, `span_to_positions`) stay usable — and `py_compile` clean —
without a GPU stack. On the worker the real base class is bound.
"""

try:  # bind the real base + torch on the worker; degrade gracefully elsewhere.
    import torch
    from transformers import LogitsProcessor
    _HAS_TORCH = True
except Exception:  # pragma: no cover — controller / py_compile path
    torch = None
    LogitsProcessor = object
    _HAS_TORCH = False


class ClampLogitsProcessor(LogitsProcessor):
    """Hold chosen canvas positions FIXED across every denoise step.

    Given `clamp_by_pos = {canvas_position(int) -> token_id(int)}`, each step force
    those positions' logits to a near-one-hot on their token (`clamp_high` on the
    chosen id, `clamp_low` on the rest of the vocab). This IS the clamp primitive
    for slotted-gen / infill / eval-renoise.

    Why it pins the position, all from generation_diffusion_gemma.py:
      - entropy at a near-one-hot position -> ~0, so `accept_canvas` (:402, entropy
        bound :439) ALWAYS accepts it (it is among the lowest-entropy positions,
        inside the cumulative bound);
      - `renoise_canvas` (:446-465) only renoises NON-accepted positions, so an
        always-accepted position is NEVER renoised;
      - the multinomial / argmax draw (:1045-1047) pick the clamp token.
      Net: the position holds its token across all steps.

    The external processor is applied FIRST, before the built-in
    `LinearTemperatureScheduleLogitsProcessor` (`_prepare_logits_processor`,
    :1176-1187). Dividing a near-one-hot by any positive temperature stays
    near-one-hot, so the clamp survives the schedule.

    `clamp_high=1e4` fits fp16 (max ~65504); `clamp_low=-1e9` casts to -inf in
    fp16 -> probability 0 (intended). Positions are CANVAS-LOCAL (0..canvas_len-1)
    within the block being denoised.
    """

    def __init__(self, clamp_by_pos, clamp_high=1e4, clamp_low=-1e9):
        self.clamp_by_pos = {int(p): int(t) for p, t in clamp_by_pos.items()}
        self._positions = sorted(self.clamp_by_pos)
        self._token_ids = [self.clamp_by_pos[p] for p in self._positions]
        self.clamp_high = clamp_high
        self.clamp_low = clamp_low

    def __call__(self, input_ids, scores, cur_step=None):
        # scores: (batch, canvas_length, vocab). `cur_step` is passed as a kwarg by
        # the loop (:1040) and is unused here — the clamp is step-invariant.
        if not self._positions:
            return scores
        pos = torch.as_tensor(self._positions, device=scores.device, dtype=torch.long)
        tok = torch.as_tensor(self._token_ids, device=scores.device, dtype=torch.long)
        scores[:, pos, :] = self.clamp_low          # floor the whole vocab row
        scores[:, pos, tok] = self.clamp_high        # raise the chosen id per position
        return scores


class TraceStreamer:
    """Optional per-denoise-step trace via the `generate()` streamer seam.

    `generate()` calls `streamer.put(prompt_ids)` once, `streamer.put_draft(...)`
    every denoising step (generation_diffusion_gemma.py :781-785), and
    `streamer.end()` at the end (:826). This is the SUPPORTED hook — no subclassing
    of `generate`/the sampler is needed.

    `put_draft` is given `value=argmax_canvas.cpu()` UNLESS `self._takes_logits` is
    truthy, in which case it is given `logits=self_conditioning_logits.cpu()` (the
    step's processed logits). With logits we can record BOTH the decoded canvas
    (argmax) AND per-position entropy from one tensor.

    MEASUREMENT (the owner's "how many steps / how many commit per forward"):
      - `n_stable` per step = canvas positions whose argmax == the PREVIOUS draft's
        argmax. As denoising proceeds this rises toward canvas_length. The model's
        final output IS `argmax_canvas` (generation_diffusion_gemma.py :753, :792),
        and the StableAndConfidentStoppingCriteria (:480) tracks exactly this
        argmax-stability, so it is the faithful per-step "committed" proxy.
      - `committed_per_step` (in `summary()`) = the step-to-step delta of `n_stable`
        — the full per-step commit trajectory (can dip negative: a settled position
        re-noises, the paper's ~7.5 re-mask dynamic).
      - The model's OWN authoritative metric is `out.tokens_per_forward` =
        num_valid_tokens / decoder_forward_passes (:854); `denoise_steps` here =
        the number of forward passes, so the two cross-check.

    Cost: with entropy, generate() copies the full (1, canvas_len, vocab) logits to
    CPU every step (~hundreds of MB) — heavy, hence OPTIONAL / flag-gated. Without
    entropy it only copies the tiny argmax canvas, so the stability trajectory is
    cheap (no big tensor copy, no decode unless watched).
    """

    def __init__(self, tokenizer, watch_positions=None, with_entropy=False,
                 entropy_bound=None, max_steps=None):
        import time
        self._tok = tokenizer
        self._watch = [int(p) for p in watch_positions] if watch_positions is not None else None
        self._with_entropy = bool(with_entropy)
        self._takes_logits = bool(with_entropy)   # read via getattr by generate() (:783)
        self._entropy_bound = entropy_bound
        self._max_steps = max_steps
        self._t0 = time.time()
        self._block = -1
        self._prev_argmax = None
        self.steps = []

    def _now(self):
        import time
        return round(time.time() - self._t0, 3)

    def put(self, value):
        # called with the prompt (first) and each finalized canvas — marks a block.
        self._block += 1
        self._prev_argmax = None   # stability resets per block

    def put_draft(self, value=None, logits=None):
        if self._max_steps is not None and len(self.steps) >= self._max_steps:
            return
        rec = {"block": self._block, "t_s": self._now()}
        if logits is not None:
            lg = logits[0].float()                       # (canvas_length, vocab)
            argmax = lg.argmax(dim=-1)                    # (canvas_length,)
            probs = torch.softmax(lg, dim=-1)
            ent = -(probs * torch.log(probs + 1e-9)).sum(dim=-1)  # (canvas_length,)
            rec["mean_entropy"] = round(float(ent.mean()), 4)
            if self._entropy_bound is not None:
                rec["n_low_entropy"] = int((ent < self._entropy_bound).sum())
            if self._watch is not None:
                rec["watch_entropy"] = {p: round(float(ent[p]), 4) for p in self._watch}
                rec["watch_tokens"] = {p: int(argmax[p]) for p in self._watch}
        elif value is not None:
            argmax = value[0]
        else:
            argmax = None
        if argmax is not None:
            if self._prev_argmax is not None:
                rec["n_stable"] = int((argmax == self._prev_argmax).sum())
            rec["n_pos"] = int(argmax.shape[0])
            self._prev_argmax = argmax
            # decode only when watched / entropy mode (keeps cheap trace cheap).
            if self._with_entropy or self._watch is not None:
                rec["canvas"] = self._tok.decode(argmax.tolist(), skip_special_tokens=False)
        self.steps.append(rec)

    def end(self):
        pass

    def summary(self):
        """Top-level measurement digest: total denoise steps + the per-step commit
        trajectory (deltas of `n_stable`) the owner asked to see in full."""
        stable = [s.get("n_stable") for s in self.steps if "n_stable" in s]
        committed = [stable[0]] + [stable[i] - stable[i - 1]
                                   for i in range(1, len(stable))] if stable else []
        return {
            "denoise_steps": len(self.steps),
            "n_stable_per_step": stable,
            "committed_per_step": committed,
            "final_stable": stable[-1] if stable else None,
            "mean_entropy_per_step": [s.get("mean_entropy") for s in self.steps
                                      if "mean_entropy" in s] or None,
        }


def build_offset_map(tkz, canvas_tokens):
    """CANONICAL char-span <-> canvas-token map.

    Maps EVERY canvas position (there are no holes — in DiffusionGemma every
    position always holds a real token id, random or persisted). Returns
    `(text, offset_map)` where `offset_map` is a list of `[pos, char_start,
    char_end]`: the half-open char range each token occupies in the cumulative
    per-token decode.

    `skip_special_tokens=False` is EXPLICIT and load-bearing: special tokens DO
    occupy canvas positions and char space, so dropping them would desync every
    downstream char range. This is the piece-wise decode; compare against a joint
    `tkz.decode(canvas_tokens)` to check fidelity.
    """
    text_parts = []
    offset_map = []
    cursor = 0
    for pos, tid in enumerate(canvas_tokens):
        piece = tkz.decode([tid], skip_special_tokens=False)
        cs = cursor
        ce = cursor + len(piece)
        offset_map.append([pos, cs, ce])
        text_parts.append(piece)
        cursor = ce
    return "".join(text_parts), offset_map


def span_to_positions(offset_map, span):
    """Map a CHAR span [s, e) (the oracle's coordinate system: a parse-forms :span,
    or a runtime symbol's substring range) to the canvas TOKEN positions it covers:
    every position whose [cs, ce) OVERLAPS [s, e).

    Overlap (not containment) because parser spans and BPE boundaries do not align —
    a symbol may share a piece with an adjacent paren or split across pieces. These
    positions are what a re-noise dial drops from the clamp set so the entropy bound
    re-decides them.
    """
    s, e = span
    return [pos for (pos, cs, ce) in offset_map if cs < e and ce > s]


def good_clamp_for_renoise(offset_map, seed_ids, spans):
    """THE eval-renoise dial. Given the prior partial canvas (`seed_ids`, one token id
    per canvas position) + the CHAR spans the parser/eval flagged as BAD, return:

      - `clamp_by_pos` = {position -> committed token id} for every GOOD (non-span)
        position. Feeding this to a `ClampLogitsProcessor` HOLDS the good committed
        text fixed across the resume denoise, while the bad span positions are left
        OUT of the clamp set so the entropy bound re-decides them (`accept_canvas`
        :431-442 / `renoise_canvas` :457-463 only churn non-accepted, non-clamped
        positions).
      - `bad_positions` = the sorted union of `span_to_positions` over every span —
        the positions left free to re-denoise.

    Pure char/int arithmetic (no torch) so it stays py_compile-clean off-GPU. If
    `spans` is empty, `bad_positions` is empty and EVERY position is clamped — a
    clamp-everything no-op (a sanity check that the seed round-trips unchanged), not
    a useful renoise.
    """
    bad = set()
    for span in spans:
        for pos in span_to_positions(offset_map, span):
            bad.add(int(pos))
    clamp_by_pos = {pos: int(tid) for pos, tid in enumerate(seed_ids) if pos not in bad}
    return clamp_by_pos, sorted(bad)


class StepCountStopping:
    """A `DiffusionGemmaAdaptiveStopping`-shaped criterion that fires (returns an
    all-True BoolTensor) after exactly `stop_step` denoise steps, so generation STOPS
    at step K WITHOUT shrinking `max_denoising_steps`.

    Why a step counter and not `max_denoising_steps=K`: shrinking the cap COMPRESSES
    the temperature ramp (`temperature = t_min + (t_max-t_min)*(cur_step/max_denoising_steps)`,
    :311) — a different generation regime, not a peek at step K. Keeping N intact and
    stopping externally means the K steps performed are cur_step = N, N-1 .. N-K+1, i.e.
    the natural high-temp head of the real N-step schedule.

    Contract (DUCK-TYPED, not subclassed — the loop never isinstance-checks it, and
    avoiding the heavy `from transformers.models...` import keeps this module
    py_compile-clean off-GPU; the real ABC is :466):
      - `__call__(argmax_canvas, logits, **kwargs) -> BoolTensor(shape=(batch,))` — the
        loop OR-accumulates the result into `finished_denoising` (:1059) and breaks the
        inner loop when all rows are True (:782). Called ONCE per `_denoising_step`, so
        counting calls == counting denoise steps.
      - `reset()` — called once per canvas before its inner loop (:993). We zero the
        counter so the K-cap applies per canvas (denoise_to_step is single-canvas, so
        this is just hygiene; with >1 canvas it would stop at step K of EACH).

    `count` (calls seen) is read back after generate() to report the steps actually
    fired (which equals K unless a built-in criterion fired first — but we REPLACE the
    builtin via `step_stopping`, so for our runs count == K).
    """

    def __init__(self, stop_step):
        self.stop_step = int(stop_step)
        self.count = 0

    def __call__(self, argmax_canvas, logits, **kwargs):
        self.count += 1
        fire = self.count >= self.stop_step
        return torch.full((logits.shape[0],), bool(fire),
                          device=logits.device, dtype=torch.bool)

    def reset(self):
        self.count = 0


def step_stopping(model, stop_step):
    """Context manager: REPLACE `model._prepare_diffusion_stopping_criteria` so the
    next `generate()` uses a `StepCountStopping(stop_step)` instead of the config-built
    `StableAndConfidentStoppingCriteria`. `generate()` calls
    `self._prepare_diffusion_stopping_criteria(generation_config)` once at setup (:1207),
    so an instance-attribute override is picked up; we restore on exit.

    REPLACE (not compose): we want EXACTLY K natural steps, so the builtin early-stop is
    disabled for the K-step window. Yields the live criterion so the caller can read
    `.count`. KEEP the default DynamicCache (do NOT set cache_implementation='static'):
    a static cache compiles the criterion (:1258-1263) and a Python counter won't
    survive torch.compile (the default non-compiled path is `is_compiling=False`, :692).
    """
    from contextlib import contextmanager

    @contextmanager
    def _cm():
        crit = StepCountStopping(stop_step)
        had_own = "_prepare_diffusion_stopping_criteria" in model.__dict__
        orig = model.__dict__.get("_prepare_diffusion_stopping_criteria")
        model._prepare_diffusion_stopping_criteria = (lambda generation_config, _c=crit: _c)
        try:
            yield crit
        finally:
            if had_own:
                model._prepare_diffusion_stopping_criteria = orig
            else:
                model.__dict__.pop("_prepare_diffusion_stopping_criteria", None)

    return _cm()


# ============================================================================
# Prefix-KV reuse — the WORKER half of the block-chain cache (the 62%-of-latency
# prefill win). PURE bookkeeping (no torch): the walk over a turn's chain hashes
# + the LRU store. The torch-heavy glue (clone / crop / suffix-forward / write-
# back) lives in gpu_worker.py; this leaf owns the algorithm so it's py_compile-
# clean and unit-testable off-GPU.
#
# Seon's `seon.agent.ctx/block-chain-keys` (src/seon/agent/ctx.cljs, commit
# 14e8acb0) is the KEYING half: a pure fn (ordered ctx blocks, agent-id) ->
# `::chain-hashes`, one 64-hex SHA-256 per block where hash i fingerprints the
# byte-exact block prefix 0..i, salted at the root by the agent id. Mirrors
# vLLM's automatic-prefix-cache chain hash (reference-code/vllm/vllm/v1/core/
# kv_cache_utils.py:577-603 + the chain :703-728, cache_salt :560-561). The
# worker treats those hex strings as OPAQUE KEYS — it never recomputes the hash,
# so there is no key-shape coupling to drift: an exact prefix match is exact
# string equality, which (content-addressed) means a byte-identical token prefix
# -> a bit-exact reuse boundary. A block edit changes its hash and every hash
# after it, so the chain diverges at the first changed block = vLLM's longest-
# prefix match. See research/kv-section-caching-design-2026-06-28.md §6.
# ============================================================================

import collections


def longest_prefix_hit(chain_hashes, cached_keys):
    """Longest cached prefix boundary for a turn's per-block chain hashes.

    `chain_hashes` is the turn's `::chain-hashes` (prompt order, static->volatile);
    `chain_hashes[i]` fingerprints the byte-exact block prefix 0..i. `cached_keys`
    is the set of chain hashes currently held in the KV LRU (a key present means we
    hold the encoder KV after encoding the prompt through that block). Returns the
    INDEX of the longest cached prefix boundary, or -1 when nothing hits.

    We scan high->low and return the FIRST hit (== the longest), the §6 contract's
    "walk top->down ... longest hash that hits". Returning the max present index is
    correct AND optimal even if a lower boundary was LRU-evicted: boundary i's cache
    is self-contained encoder KV for tokens [0, end_i), so it reuses regardless of
    whether shorter boundaries also survive. A divergence at block k makes
    `chain_hashes[k:]` all miss (the chain breaks), so the longest hit is k-1 — the
    shared static prefix. PURE (set membership only), no torch."""
    keys = cached_keys if isinstance(cached_keys, (set, frozenset, dict)) else set(cached_keys)
    for i in range(len(chain_hashes) - 1, -1, -1):
        if chain_hashes[i] in keys:
            return i
    return -1


class KVPrefixCache:
    """Bounded LRU `{chain_hash -> (encoder DynamicCache cropped to that block
    boundary, token_len)}` for prefix-KV reuse — the §6 store.

    This class owns ONLY the bookkeeping (the OrderedDict + LRU eviction); it holds
    no torch and imports none, the same separation vLLM draws between its
    kv_cache_manager radix/LRU and the paged GPU allocator (kv_cache_manager.py).
    The VALUES are GPU `DynamicCache` objects produced by the worker glue
    (clone+crop in gpu_worker.py); this store just keys them by chain hash and
    evicts the coldest when over capacity.

    `capacity` bounds the number of cached boundaries (each one a cropped cache —
    full prefix KV, dominated by the full-attention layers per §1.4). LRU so a hot
    static prefix (soul..:namespaces) stays resident across turns while one-off
    volatile-tail boundaries age out."""

    def __init__(self, capacity=64):
        self.capacity = int(capacity)
        self._d = collections.OrderedDict()   # chain_hash -> (cache, token_len)

    def __contains__(self, key):
        return key in self._d

    def __len__(self):
        return len(self._d)

    def keys(self):
        """The set of cached chain hashes — the `cached_keys` arg of
        [[longest_prefix_hit]]."""
        return set(self._d.keys())

    def get(self, key):
        """Return `(cache, token_len)` for a hit (and mark it most-recently-used),
        or None on a miss."""
        if key not in self._d:
            return None
        self._d.move_to_end(key)              # LRU touch
        return self._d[key]

    def put(self, key, cache, token_len):
        """Store `cache` (an encoder DynamicCache cropped to `token_len`) under its
        chain hash; evict the coldest entries past capacity."""
        if key in self._d:
            self._d.move_to_end(key)
        self._d[key] = (cache, int(token_len))
        evicted = []
        while len(self._d) > self.capacity:
            ek, _ = self._d.popitem(last=False)   # evict coldest
            evicted.append(ek)
        return evicted


# ============================================================================
# Mid-denoise INJECTION-APPLY — the WORKER half of the unified oracle's
# `::injections` (the W1/W2/W3 routes). PURE bookkeeping (no torch): the
# span→position→replacement-clamp mapping + the KV-extend route selection. The
# torch-heavy glue (encoder suffix-forward / re-prefill / the seed) lives in
# gpu_worker.py; this leaf owns the algorithm so it's py_compile-clean and
# unit-testable off-GPU.
#
# An INJECTION (the oracle's `seon.diffusion.retrieval/to-wire` shape, carried
# through `seon.diffusion.oracle/refine` → `to-wire`) is
#   {span:[s,e], replacement:str, spec_text:str}
# — a hallucinated symbol (`db/transct!`) the program graph corrected to a REAL
# fn (`db/transact!`, `replacement`) plus that fn's signature/spec (`spec_text`,
# the encoder-KV content). Applying it mid-denoise is two moves:
#
#   1. CLAMP the span toward `replacement` — map the CHAR span to canvas TOKEN
#      positions (via the worker's `offset_map`, `span_to_positions`) and force
#      those positions toward the tokenized `replacement` (reusing the existing
#      `ClampLogitsProcessor`). This re-commits the symbol toward a name that
#      EXISTS.
#   2. EXTEND the encoder KV with `spec_text` so the decoder cross-attends the
#      real signature for the next denoise steps (the decoder cross-attends ALL
#      non-pad encoder positions — modeling_diffusion_gemma.py:1294-1340 — so
#      spec_text appended to the encoder KV is visible). Three routes:
#        W1 — INCREMENTAL: append spec_text to a HELD encoder DynamicCache via a
#             suffix-forward (`generate(past_key_values=held, input_ids=spec_ids)`
#             — the encoder prefills only the spec ids and APPENDS,
#             generation_diffusion_gemma.py:635-636,720-734,941; cache append is
#             `DynamicLayer.update` torch.cat dim=-2, cache_utils.py:126-150).
#             Valid ONLY when the held cache is extensible (a uniform full
#             DynamicCache — every layer non-sliding, the kv_reuse `dynamic_full`
#             shape; a hybrid sliding rolling-buffer layer is not).
#        W2 — RE-PREFILL: re-encode `prompt ++ spec_text` in one generate
#             (`input_ids = prompt ids ++ spec ids`). No held cache needed,
#             guaranteed correct — the realistic Phase-1 DEFAULT.
#        W3 — CLAMP-ONLY: no spec_text in the KV (the clamp alone still steers
#             the span). The guaranteed fallback (no spec_text, or KV-extend
#             disabled).
# ============================================================================


def injection_clamps(injections, offset_map, encode):
    """Map each injection's CHAR span to canvas TOKEN positions and tokenize its
    `replacement` → a `{position → token_id}` clamp set steering the span toward
    the real symbol, plus the `spec_text`s the W1/W2 route appends to the encoder
    KV. PURE — `encode` (str → [token_id, ...]) is injected so this stays
    torch-free and unit-testable off-GPU.

    Args:
      injections : [{span:[s,e], replacement:str, spec_text:str}, ...] — the
        oracle's `::injections` (retrieval `to-wire` shape). `spec_text` is also
        accepted spelled `spec-text` (defensive).
      offset_map : the worker's `[[pos, char_start, char_end], ...]` (from
        `build_offset_map` / a `denoise_to_step` checkpoint).
      encode     : str → list[int] token ids (the tokenizer call, add_special=False).

    Mapping rule (per injection): `span_to_positions` gives the canvas positions
    the char span overlaps (ascending). Zip them LEFT→RIGHT with the replacement's
    token ids and clamp `min(len(positions), len(replacement_ids))` of them. A
    span LONGER than the replacement leaves its trailing positions FREE to denoise
    (the leftover chars of the hallucinated token re-decide, guided by the
    spec_text now in the encoder KV); a replacement LONGER than the span DROPS its
    trailing tokens (the canvas slot is a fixed token width). This is a STEERING
    clamp, not exact infill — honest + reported in `detail`.

    Returns `{clamp_by_pos, spec_texts, detail}`:
      - clamp_by_pos : {position(int) → token_id(int)} merged over all injections
        (the oracle partitions injections so spans don't overlap; a later one
        wins on the off-chance two touch the same position).
      - spec_texts   : the de-duplicated, order-preserved encoder-KV strings.
      - detail       : per-injection `{span, replacement, positions,
        replacement_ids, n_clamped, free_positions, dropped_token_ids,
        spec_text_len}` for the result + the live assertion.
    """
    clamp_by_pos = {}
    spec_texts = []
    seen_specs = set()
    detail = []
    for inj in injections:
        span = [int(inj["span"][0]), int(inj["span"][1])]
        replacement = inj.get("replacement", "") or ""
        spec_text = inj.get("spec_text", inj.get("spec-text", "")) or ""
        positions = span_to_positions(offset_map, span)
        rep_ids = [int(t) for t in (encode(replacement) if replacement else [])]
        n = min(len(positions), len(rep_ids))
        for i in range(n):
            clamp_by_pos[int(positions[i])] = int(rep_ids[i])
        if spec_text and spec_text not in seen_specs:
            spec_texts.append(spec_text)
            seen_specs.add(spec_text)
        detail.append({
            "span": span,
            "replacement": replacement,
            "positions": [int(p) for p in positions],
            "replacement_ids": rep_ids,
            "n_clamped": n,
            "free_positions": [int(p) for p in positions[n:]],
            "dropped_token_ids": [int(t) for t in rep_ids[n:]],
            "spec_text_len": len(spec_text),
        })
    return {"clamp_by_pos": clamp_by_pos, "spec_texts": spec_texts, "detail": detail}


def choose_kv_route(force_route=None, *, has_spec_text, has_cache,
                    cache_extensible, extend_kv=True):
    """Pick the encoder-KV-extend route for an injection-apply → `(route, reason)`.

    - **W3** (clamp-only) when there is no `spec_text` to add, or KV-extend is
      explicitly disabled (`extend_kv=False`). The guaranteed fallback — the clamp
      alone still steers the span; the real signature just isn't injected.
    - **W1** (incremental) when a HELD encoder cache exists AND is extensible (a
      uniform full DynamicCache — the kv_reuse `dynamic_full` shape; see
      `gpu_worker._cache_extensible`). Cheapest: append spec_text to the held
      cache via a suffix-forward, no prompt re-encode.
    - **W2** (re-prefill) otherwise: re-encode `prompt ++ spec_text`. No held
      cache needed, guaranteed correct → the realistic Phase-1 DEFAULT (the JSON
      worker path never holds an encoder cache across calls, so it lands here).

    `force_route` ("W1"/"W2"/"W3", payload `kv_route`) overrides for an A/B."""
    if force_route in ("W1", "W2", "W3"):
        return force_route, "forced"
    if not extend_kv:
        return "W3", "extend_kv disabled"
    if not has_spec_text:
        return "W3", "no spec_text"
    if has_cache and cache_extensible:
        return "W1", "held extensible cache: incremental append"
    return "W2", ("re-prefill prompt+spec_text"
                  + ("" if not has_cache else "; held cache not extensible"))
