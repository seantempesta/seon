# FROZEN (2026-07-05): quarantined RunPod CUDA artifact — superseded by
# seon_diffusion.control + the MLX worker. Revive by need; see cuda/__init__.py.
import os, sys, json, asyncio, hashlib
from runpod_flash import Endpoint, GpuType, DataCenter, NetworkVolume, PodTemplate
from diffgemma_common import (
    ClampLogitsProcessor, TraceStreamer, build_offset_map, span_to_positions,
    good_clamp_for_renoise, step_stopping, KVPrefixCache, longest_prefix_hit,
    injection_clamps, choose_kv_route)


def _worker_sha():
    """Content hash of the ACTUAL worker source running in this container
    (gpu_worker.py + diffgemma_common.py bytes). Returned in EVERY response as
    `worker_sha` so a caller can PROVE which code produced a measurement — the
    guard against silently measuring a stale warm worker (`flash deploy` updates
    the endpoint but a warm worker keeps old code until it recycles). The local
    side computes the same hash over the same two files; mismatch => STALE, refuse
    to trust the numbers and force a fresh deploy."""
    h = hashlib.sha256()
    for f in (__file__, os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                     "diffgemma_common.py")):
        try:
            with open(f, "rb") as fh:
                h.update(fh.read())
        except Exception as e:
            h.update(f"MISSING:{f}:{e}".encode())
    return h.hexdigest()[:12]


WORKER_SHA = _worker_sha()

# Warm-worker model cache: persists across requests within one live worker, so a
# burst of calls loads the 50GB model ONCE. Scales to zero after idle_timeout.
_CACHE = {}
MID = "google/diffusiongemma-26B-A4B-it"

# Prefix-KV reuse store (the WORKER half of block-chain caching — the 62%-of-
# latency prefill win). Persists across requests within one warm worker, exactly
# like _CACHE; a fresh/recycled worker starts empty (a cold miss re-encodes, no
# correctness impact). Bounded LRU keyed on Seon's `::chain-hashes`; bookkeeping
# lives in diffgemma_common.KVPrefixCache, the torch glue (clone/crop/write-back)
# below. Capacity is a knob (each entry is one cropped encoder DynamicCache).
_KV_PREFIX = KVPrefixCache(capacity=int(os.environ.get("DIFFGEMMA_KV_CAPACITY", "64")))

# NetworkVolume: caches the ~50GB DiffusionGemma snapshot + pip wheels so cold
# starts mount it (seconds) instead of re-downloading (minutes). Idempotent by
# name+datacenter; survives `flash undeploy`. Volume AND endpoint MUST share
# EU-RO-1 (a volume is single-DC; the endpoint reading it pins to that DC).
_VOL = NetworkVolume(name="diffgemma-vol", size=200, datacenter=DataCenter.EU_RO_1)


def _load(tok, experts_impl=None):
    """Load (and cache) tokenizer + model — the ONE warm instance every mode uses.

    Prefer `sdpa` (2-4x faster than eager; eager was a torch-shim-era leftover to
    dodge flex_attention). If sdpa fails to load against this model, fall back to
    eager and RECORD it in `_CACHE["attn_impl"]` (surfaced in every result) — never
    silently keep eager.

    `experts_impl` (payload `experts_impl`) selects the MoE backend at LOAD via the
    recognized `experts_implementation=` from_pretrained kwarg (modeling_utils.py
    :1589-1590 → `config._experts_implementation`). UNSET (None) => the model's own
    default `grouped_mm` (modeling_utils.py:2049) — i.e. ZERO behavior change vs the
    pre-knob worker. The lever the torch-compile doc §15 proves: passing
    `"batched_mm"` makes the compiled decode forward use `batched_mm_experts_forward`
    (moe.py:118-179, pure repeat_interleave→bmm), which NEVER calls `_grouped_mm`/
    `_can_use_grouped_mm` — so the `find_spec` graph-break at moe.py:301 is off the
    traced graph AND batched_mm is CUDA-graph/reduce-overhead clean. The backend is
    part of the CACHE KEY: an A/B that flips it RELOADS (evicting first), because two
    50GB copies would OOM the A100-80, and a fresh model also drops any stale
    torch.compile graphs captured for the previous backend."""
    import time, gc
    from transformers import AutoTokenizer, DiffusionGemmaForBlockDiffusion
    t0 = time.time()
    if _CACHE.get("tok") is None:
        _CACHE["tok"] = AutoTokenizer.from_pretrained(MID, token=tok)
    if "model" not in _CACHE or _CACHE.get("experts_impl_key") != experts_impl:
        # Evict any previously-loaded backend FIRST so only one 50GB model is ever
        # resident (two would OOM the A100-80) and the old compile graphs are freed.
        if "model" in _CACHE:
            _CACHE.pop("model", None)
            gc.collect()
            try:
                import torch
                if torch.cuda.is_available():
                    torch.cuda.empty_cache()
            except Exception:
                pass
        # experts_implementation only passed when explicitly requested → unset keeps
        # the model's grouped_mm default (no behavior change).
        extra = {"experts_implementation": experts_impl} if experts_impl else {}
        attn, err = "sdpa", None
        try:
            _CACHE["model"] = DiffusionGemmaForBlockDiffusion.from_pretrained(
                MID, dtype="auto", device_map="auto", token=tok,
                attn_implementation="sdpa", **extra)
        except Exception as e:
            attn, err = "eager", f"{type(e).__name__}: {e}"[:200]
            _CACHE["model"] = DiffusionGemmaForBlockDiffusion.from_pretrained(
                MID, dtype="auto", device_map="auto", token=tok,
                attn_implementation="eager", **extra)
        _CACHE["attn_impl"] = attn
        _CACHE["attn_fallback_err"] = err
        _CACHE["experts_impl_key"] = experts_impl
    return _CACHE["tok"], _CACHE["model"], round(time.time() - t0, 1)


# --- Co-located oracle sidecars (the persistent-server perf path) ------------
# Spawn the bb PARSE server and the node EVAL server ONCE per warm worker,
# cached in _CACHE beside the model. Reached over a stdin/stdout pipe via the
# oracle_shim.Oracle (spawn-once, ~0.05ms warm parse / ~2.6ms warm eval) — NOT
# subprocess.run per call (~21ms bb cold start EVERY call, the spawn-per-call
# anti-pattern the colocation plan §3 kills). Image paths (co-location-image
# §5): bb reads bin/oracle-server (which puts <repo>/src on its classpath); the
# node eval bundle is out/worker-oracle-eval/main.js run with --serve.
_ORACLE_ARGV = {
    "parse": ["bb", os.environ.get("SEON_ORACLE_BB", "/opt/seon/bin/oracle-server")],
    "eval":  ["node", os.environ.get("SEON_ORACLE_EVAL_JS",
                                     "/opt/seon/oracle-eval.js"), "--serve"],
}
# The Node eval server signals readiness on STDERR after its self-host bootstrap
# cache loads (worker_eval.cljs:381). bb is synchronous-on-first-call (no
# sentinel). ready_sentinel gates the eval first-use; warmup primes V8/JIT.
_ORACLE_READY = {"parse": None, "eval": "ready"}


def _oracle(kind):
    """Return the persistent oracle sidecar for `kind` ∈ {"parse","eval"},
    spawning + warming it ONCE per warm worker and caching it in _CACHE beside
    the model. Subsequent calls reuse the SAME server (0.05ms/2.6ms warm pipe),
    never respawn.

    Hardening (colocation plan §3 items 2-3):
      - both servers cached independently under "oracle_parse"/"oracle_eval";
      - EVAL ready-wait: block on the `"ready\\n"` stderr sentinel before the
        first eval (cljs.js init is async — `worker_eval.cljs:381`);
      - V8 / native WARMUP eval at boot so the first REAL call is hot, not a
        JIT-cold outlier;
      - liveness respawn lives in the shim: a dead child (`Popen.poll()` not
        None) is lazily respawned on the next `.call()` (Oracle._ensure)."""
    from oracle_shim import Oracle
    key = f"oracle_{kind}"
    o = _CACHE.get(key)
    if o is None or not o.alive():
        o = Oracle(_ORACLE_ARGV[kind], ready_sentinel=_ORACLE_READY[kind])
        if _ORACLE_READY[kind] is not None:
            o.ready_after()        # block until the eval server prints "ready\n"
        # WARMUP: one throwaway call so the hot compile/eval path is JIT-warm
        # (eval) / classpath-loaded (parse) before the first real checkpoint.
        try:
            o.warmup(op=kind if kind == "parse" else "eval")
        except Exception:
            pass                    # warmup is best-effort; a real call retries
        _CACHE[key] = o
    return o


def _gen_overrides(model, payload):
    """Build generate() kwargs for the denoise-dynamics tuning knobs from payload.
    All are recognized DiffusionGemmaGenerationConfig fields, folded in by
    `_prepare_generation_config` -> `generation_config.update(**kwargs)` (:874):

      - max_denoising_steps (int)  — the inner-loop step CAP
        (`reversed(range(1, max_denoising_steps+1))`, :757).
      - entropy_bound (float)      — wrapped into EntropyBoundSamplerConfig (:316);
        HIGHER => more positions accepted per step => higher tokens_per_forward
        (accept_canvas docstring :321-323, bound :439). THE commit-rate dial.
      - t_min / t_max (float)      — LinearTemperatureScheduleLogitsProcessor (:128).
      - stability_threshold (int) + confidence_threshold (float) — early-stop via
        StableAndConfidentStoppingCriteria (:1220-1224); pass BOTH to enable, omit
        to run the full step budget.
    """
    ov = {}
    for k in ("max_denoising_steps", "t_min", "t_max",
              "stability_threshold", "confidence_threshold"):
        if k in payload:
            ov[k] = payload[k]
    if "entropy_bound" in payload:
        mod = sys.modules.get(type(model).__module__)
        cfg_cls = getattr(mod, "EntropyBoundSamplerConfig", None) if mod else None
        if cfg_cls is not None:
            ov["sampler_config"] = cfg_cls(entropy_bound=float(payload["entropy_bound"]))
    return ov


def _effective_entropy_bound(model, payload):
    if "entropy_bound" in payload:
        return float(payload["entropy_bound"])
    gc = getattr(model, "generation_config", None)
    sc = getattr(gc, "sampler_config", None) if gc is not None else None
    return getattr(sc, "entropy_bound", None) if sc is not None else None


def _canvas_len(model):
    return int(getattr(model.config, "canvas_length", 256))


def _vocab(model):
    tc = getattr(model.config, "text_config", model.config)
    return int(getattr(tc, "vocab_size", 262144))


def _seed_canvas(model, clamp_by_pos, base=None):
    """Warm-start a full-canvas `decoder_input_ids` (1, canvas_len): random ids
    (matching the sampler's own `initialize_canvas`, :390) with the clamp positions
    pre-set. Grounded at `_prepare_denoiser_inputs` :985 (pops `decoder_input_ids`
    as the starting canvas). Optional — the ClampLogitsProcessor pins from step 1
    regardless; seeding just lets the FIRST decoder forward already see the clamps.

    `base` (optional, a prior committed canvas of token ids) is the resume basis:
    when given, the seed STARTS from the prior canvas (good positions keep their
    committed token) and only the clamp positions are overwritten — the non-clamp,
    non-base positions stay as the prior draft rather than being re-randomized.
    Default `base=None` = the original fresh-random behavior (unchanged callers)."""
    import torch
    L, V = _canvas_len(model), _vocab(model)
    dev = model.device
    if base is not None:
        ids = [int(x) for x in base][:L]
        seed = torch.tensor([ids], device=dev, dtype=torch.long)
        if seed.shape[-1] < L:                     # pad short base with fresh random
            pad = torch.randint(low=0, high=V, size=(1, L - seed.shape[-1]), device=dev)
            seed = torch.cat([seed, pad], dim=-1)
    else:
        seed = torch.randint(low=0, high=V, size=(1, L), device=dev)
    for pos, tid in clamp_by_pos.items():
        seed[0, int(pos)] = int(tid)
    return seed


def _ids(tkz, text, device):
    """Tokenize `text` (no special tokens) to a (1, n) long tensor on `device` —
    the spec_text → encoder-input-ids primitive for the W1/W2 inject routes."""
    import torch
    return torch.tensor([tkz(text, add_special_tokens=False)["input_ids"]],
                        device=device, dtype=torch.long)


def _cache_extensible(cache):
    """W1-extensibility test: a held encoder cache can take an incremental
    suffix-forward append ONLY if it is a uniform full DynamicCache — every layer
    non-sliding (the kv_reuse `dynamic_full` shape). A hybrid sliding cache's
    rolling-buffer layers (`DynamicSlidingWindowLayer.is_sliding=True`,
    cache_utils.py:196) cannot append at an arbitrary boundary cleanly — the same
    hazard `_kv_reuse_generate` forces `dynamic_full` to avoid. None / non-cache
    => not extensible (→ W2 re-prefill)."""
    if cache is None:
        return False
    try:
        from transformers.cache_utils import DynamicCache
        if not isinstance(cache, DynamicCache):
            return False
        layers = getattr(cache, "layers", None) or []
        return len(layers) > 0 and all(not getattr(l, "is_sliding", False)
                                       for l in layers)
    except Exception:
        return False


def _held_inject_cache(payload):
    """The encoder cache a W1 incremental append extends — the co-location seam.

    Phase-1 JSON path: a `Cache` cannot ride a JSON payload (kv-section §5), and
    the worker holds none across a stateless call, so this returns None and the
    route falls to W2 (re-prefill). When Seon CO-LOCATES (calls generate
    in-process) it can hand the held prompt cache here — OR, composed with
    kv_reuse, the kv_reuse-produced prefix cache (looked up by `chain_hashes`)
    becomes the W1 base. Documented seam; default None keeps the JSON path safe."""
    h = payload.get("inject_kv_chain_hash")
    if h is not None:
        entry = _KV_PREFIX.get(str(h))             # composed-with-kv_reuse base
        if entry is not None:
            return _clone_cache(entry[0])
    return None


def _encoder_inject_kwargs(tkz, model, base_inp, spec_texts, route, held):
    """Encoder generate-kwargs realizing the chosen W1/W2/W3 route — merges the
    `spec_text` into the encoder KV so the decoder cross-attends the real
    signature. `base_inp` = the apply_chat_template dict (input_ids + attention_mask).

      W1 → past_key_values = clone(held); input_ids = spec ids (SUFFIX-forward: the
           encoder prefills only the spec ids and APPENDS to the held prompt cache —
           generation_diffusion_gemma.py:635-636,720-734,941).
      W2 → input_ids = prompt ids ++ spec ids; attention_mask = ones (re-prefill).
      W3 → input_ids = prompt only (clamp-only; no spec in the KV).

    Returns the dict to fold into gen_kwargs. Falls back to W3-shape when the route
    wants spec but there is none / no held cache (defensive — choose_kv_route
    already prevents this)."""
    import torch
    spec = "\n".join(spec_texts) if spec_texts else ""
    if route == "W1" and spec and held is not None:
        return {"past_key_values": held,
                "input_ids": _ids(tkz, spec, model.device)}
    if route == "W2" and spec:
        full = torch.cat([base_inp["input_ids"], _ids(tkz, spec, model.device)], dim=-1)
        return {"input_ids": full, "attention_mask": torch.ones_like(full)}
    return {"input_ids": base_inp["input_ids"],
            "attention_mask": base_inp["attention_mask"]}


def _run(model, gen_kwargs):
    import time
    g0 = time.time()
    out = model.generate(**gen_kwargs)
    return out, round(time.time() - g0, 2)


def _resolve_clamps(tkz, payload):
    """Normalize a payload's clamp spec -> {canvas_position(int) -> token_id(int)}.
    Accepts explicit `clamps` ({pos: id}) OR `clamp_text` ({pos: str}, mapped to the
    string's FIRST token id — single-token convenience; a multi-token scaffold must be
    lowered to explicit `clamps` by the caller). Returns {} when neither is given."""
    clamps = payload.get("clamps")
    if clamps is None and payload.get("clamp_text"):
        clamps = {int(p): tkz(s, add_special_tokens=False)["input_ids"][0]
                  for p, s in payload["clamp_text"].items()}
    if not clamps:
        return {}
    return {int(p): int(t) for p, t in clamps.items()}


def _run_with_optional_stop(model, gen_kwargs, K):
    """Run generate(), optionally stopping at denoise step K via StepCountStopping
    (keeps max_denoising_steps=N intact — the regime-safe checkpoint, see
    diffgemma_common.step_stopping). Returns (out, gen_s, steps_fired_or_None)."""
    if K:
        with step_stopping(model, int(K)) as crit:
            out, gen_s = _run(model, gen_kwargs)
        return out, gen_s, crit.count
    out, gen_s = _run(model, gen_kwargs)
    return out, gen_s, None


def _tpf(out):
    return (out.tokens_per_forward.tolist()
            if getattr(out, "tokens_per_forward", None) is not None else None)


# --- Prefix-KV reuse: the torch glue (clone / crop / write-back) -------------
# Grounded in transformers v5.11.0 (the deployed version), reference-code/
# transformers/src/transformers:
#   - DynamicCache.crop(max_length)  cache_utils.py:1337 -> delegates per layer to
#     DynamicLayer.crop :163, which slices keys/values to `[..., :max_length, :]`.
#     IN-PLACE, returns None; a no-op when get_seq_length() <= max_length (:170).
#   - generate(input_ids, past_key_values=...)  generation_diffusion_gemma.py:546;
#     at :635-636 `cur_len += past_key_values.get_seq_length()`, and the prefill
#     encoder encodes the PASSED `input_ids` (_prepare_encoder_inputs :941,
#     `unprocessed_input_ids = input_ids` on prefill). So with a length-L cache we
#     pass the SUFFIX `input_ids[:, L:]` (NOT the full prompt — that would double-
#     count): encoder_position_ids = arange(L, P) (:633), attention_mask = ones(1,P)
#     (:647) covers prefix+suffix, the encoder prefills ONLY [L:] and APPENDS to the
#     reused prefix exactly as the block-AR loop appends per canvas (:734).
#   - The encoder cache is the HYBRID `DynamicCache(config=...)` (:925 ->
#     cache_utils.py:1437): sliding + full layers (sliding_window=512, 5:1). The §1.4
#     "full-attention layers only" note is therefore a CORRECTNESS caveat for prompts
#     longer than the sliding window, not just memory — see _kv_reuse_generate's
#     docstring + the owner bit-exact assertion.


def _clone_cache(cache):
    """Deep-copy a DynamicCache (and its GPU tensors) so a reused prefix cache is
    never mutated by generate's in-place append. torch tensors deepcopy on the same
    device. Used both to hand a CLONE to generate (the stored prefix stays pristine)
    and to snapshot write-back boundaries before cropping."""
    import copy
    return copy.deepcopy(cache)


def _clone_crop(cache, length):
    """A CLONE of `cache` cropped to `length` tokens (DynamicCache.crop, in-place on
    the clone). The write-back primitive: snapshot the grown encoder cache at a block
    boundary without disturbing the longer cache it came from."""
    c = _clone_cache(cache)
    c.crop(int(length))
    return c


def _block_token_offsets(tkz, blocks):
    """Per-block END token offset within the prompt token sequence, by cumulative
    tokenization of the concatenated block texts (add_special_tokens=True so the one
    leading BOS is shared by every prefix -> offsets live in the same space as the
    full-prompt tokenization). Returns a list parallel to `blocks`.

    Tokenization is not strictly concatenative (BPE can merge across a block seam),
    so a boundary is SNAPPED to a token boundary — which only ever SHORTENS the
    reused prefix by a token or two at the cut, never lengthens it. That is
    conservative: the kept prefix is still byte-exact, so reuse stays bit-correct;
    we simply re-encode a hair more of the suffix. The worker owns this (the pod's
    char/4 estimate can't produce real token offsets), guaranteeing the offsets
    align with the tokenizer generate() actually runs."""
    ends, acc = [], ""
    for b in blocks:
        acc += b
        ends.append(len(tkz(acc, add_special_tokens=True)["input_ids"]))
    return ends


def _kv_reuse_generate(payload, tok, info):
    """mode="generate" with `kv_reuse: true` — the §6 prefix-KV-reuse path.

    Contract inputs (payload):
      - `blocks`        : the turn's ctx block texts in prompt order (static->
                          volatile). Their concatenation IS the prompt (each
                          `:seon.render/text` carries its own separators).
      - `chain_hashes`  : Seon's `::chain-hashes`, parallel to `blocks` (opaque
                          keys; the worker never recomputes the SHA).
      - `block_offsets` : OPTIONAL token END offsets (override the worker's own
                          cumulative tokenization) — a list of ints or [start,end].

    Mechanism (all line cites = reference-code/transformers v5.11.0):
      1. Tokenize the assembled prompt -> full_ids (P tokens). Derive each block's
         token END offset (worker-owned; the pod can't produce real token offsets).
      2. WALK chain_hashes top->down vs the LRU (longest_prefix_hit) -> the longest
         cached prefix boundary index i -> reuse length L = ends[i].
      3. If L>0: hand generate a CLONE of the cached cache (stored stays pristine)
         and `input_ids = full_ids[:, L:]` (the SUFFIX — generate adds the cache
         length to cur_len at generation_diffusion_gemma.py:635-636, so passing the
         full prompt would double-count). The encoder prefills ONLY [L:] and appends
         to the reused prefix (:734, exactly the block-AR per-canvas append :784).
         Else cold-encode the whole prompt.
      4. WRITE BACK: snapshot the grown encoder cache (out.past_key_values) cropped
         to every block boundary, keyed by its chain hash, for next time.

    Exactness: the cache is forced to `cache_implementation="dynamic_full"` (a
    uniform full DynamicCache, generation_diffusion_gemma.py:923-925) so crop() and
    get_seq_length() are bit-exact for EVERY layer — sidestepping the hybrid
    sliding-window rolling-buffer hazard (a sliding layer holds only the last
    `sliding_window` positions, so cropping it to a prefix boundary is ill-defined).
    The §1.4 "store full-attention layers only" note is a later MEMORY refinement
    (sliding layers then re-encode), not needed for Phase-1 correctness; here sliding
    layers simply store full length (masking still restricts what they ATTEND to).

    Default (`kv_reuse` unset) never reaches here — zero change to the stock path."""
    import torch, traceback
    try:
        tkz, model, load_s = _load(tok, experts_impl=payload.get("experts_impl"))
        info["load_s"], info["attn_impl"] = load_s, _CACHE.get("attn_impl")

        blocks = payload["blocks"]
        chain_hashes = [str(h) for h in payload["chain_hashes"]]
        if len(blocks) != len(chain_hashes):
            info["kv_reuse_error"] = (
                f"blocks({len(blocks)}) and chain_hashes({len(chain_hashes)}) "
                "must be parallel")
            return info

        assembled = payload.get("prompt") or "".join(blocks)
        full_ids = tkz(assembled, add_special_tokens=True,
                       return_tensors="pt")["input_ids"].to(model.device)
        P = int(full_ids.shape[-1])

        # Per-block token END offsets — worker-owned (or payload override). Clamp to
        # [1, P] so a boundary is always a valid in-bounds token index.
        if payload.get("block_offsets"):
            raw = payload["block_offsets"]
            ends = [int(o[-1]) if isinstance(o, (list, tuple)) else int(o) for o in raw]
        else:
            ends = _block_token_offsets(tkz, blocks)
        ends = [max(1, min(P, int(e))) for e in ends]

        # WALK: longest cached prefix boundary. L must leave >=1 suffix token for
        # generate's input_ids, so cap reuse at P-1 even on a full-prompt hit.
        hit_i = longest_prefix_hit(chain_hashes, _KV_PREFIX.keys())
        L = ends[hit_i] if hit_i >= 0 else 0
        if L >= P:
            L = P - 1
        reused_hash = chain_hashes[hit_i] if (hit_i >= 0 and L > 0) else None

        gen_kwargs = dict(max_new_tokens=payload.get("max_new_tokens", 256),
                          **_gen_overrides(model, payload))
        streamer = _trace_payload(payload, tkz, model, None)
        if streamer is not None:
            gen_kwargs["streamer"] = streamer

        if reused_hash is not None:
            entry = _KV_PREFIX.get(reused_hash)        # (cache, token_len), LRU-touch
            past = _clone_cache(entry[0])              # clone: stored prefix stays pristine
            past.crop(L)                                # defensive no-op (entry len == L)
            gen_kwargs["input_ids"] = full_ids[:, L:]   # the SUFFIX only (:635-636)
            gen_kwargs["past_key_values"] = past
            suffix_len = P - L
        else:
            # Cold: full encode. Force a UNIFORM full cache so write-back crops are
            # bit-exact (no sliding rolling-buffer). Can't combine with past (:641).
            gen_kwargs["input_ids"] = full_ids
            gen_kwargs["cache_implementation"] = "dynamic_full"
            suffix_len = P

        out, gen_s = _run(model, gen_kwargs)
        enc_cache = out.past_key_values                 # the grown encoder cache
        comp = out.sequences[0][suffix_len:]            # canvases appended after the suffix

        # WRITE BACK every block boundary not already cached: a cropped CLONE of the
        # grown encoder cache (causal => positions [0,end_j) KV are final regardless
        # of the canvases appended after the prompt, §1.2). LRU-bounded.
        written, evicted = [], []
        if enc_cache is not None:
            for j, h in enumerate(chain_hashes):
                if h in _KV_PREFIX:
                    continue
                ev = _KV_PREFIX.put(h, _clone_crop(enc_cache, ends[j]), ends[j])
                written.append(j)
                evicted.extend(ev)

        info.update({
            "kv_reuse": True,
            "prompt_tokens": P,
            "kv_hit_block": hit_i if reused_hash is not None else None,
            "kv_reused_tokens": L if reused_hash is not None else 0,
            "kv_suffix_tokens": suffix_len,
            "kv_reuse_frac": round(L / P, 3) if (reused_hash is not None and P) else 0.0,
            "kv_blocks_written": len(written),
            "kv_blocks_evicted": len(evicted),
            "kv_cache_size": len(_KV_PREFIX),
            "block_ends": ends,
            "text": tkz.decode(comp, skip_special_tokens=True),
            "completion_tokens": int(out.sequences.shape[-1]) - suffix_len,
            "gen_s": gen_s,
            "tokens_per_forward": _tpf(out),
        })
        if streamer is not None:
            info.update(streamer.summary())
            info["trace"] = streamer.steps
    except Exception as e:
        info["kv_reuse_error"] = f"{type(e).__name__}: {e}"[:300]
        info["trace_err"] = traceback.format_exc()[-1500:]
    return info


def _denoise_canvas(tkz, model, prompt, K, decoder_input_ids=None,
                    clamp_by_pos=None, gen_overrides=None):
    """ONE in-worker denoise pass to step K, returning (canvas_ids, canvas_text,
    offset_map, gen_s, tok_per_s). The shared primitive the refine_loop iterates
    — identical generation mechanics to mode="denoise_to_step"/"resume_renoise",
    but callable IN-PROCESS so the whole loop runs server-side (no JSON/API hop
    per iteration). `clamp_by_pos` (the renoise good-clamp) + `decoder_input_ids`
    (the resume seed) are None on the first pass (fresh denoise) and set on every
    resume pass."""
    import torch
    from transformers import LogitsProcessorList
    L = _canvas_len(model)
    inp = tkz.apply_chat_template(
        [{"role": "user", "content": prompt}], tokenize=True,
        add_generation_prompt=True, return_dict=True,
        return_tensors="pt").to(model.device)
    nprompt = int(inp["input_ids"].shape[-1])
    gen_kwargs = dict(max_new_tokens=L, **(gen_overrides or {}))
    if clamp_by_pos:
        gen_kwargs["logits_processor"] = LogitsProcessorList(
            [ClampLogitsProcessor(clamp_by_pos)])
    if decoder_input_ids is not None:
        gen_kwargs["decoder_input_ids"] = decoder_input_ids
        comp_slice = slice(-L, None)            # resume: canvas = last L (route-robust)
    else:
        gen_kwargs.update(**inp)                # fresh: prompt-prefixed, strip prompt
        comp_slice = slice(nprompt, None)
    out, gen_s, _ = _run_with_optional_stop(model, gen_kwargs, int(K) if K else None)
    comp = out.sequences[0][comp_slice]
    canvas_ids = [int(x) for x in comp.tolist()]
    canvas_text, offset_map = build_offset_map(tkz, canvas_ids)
    tps = round(L / gen_s, 1) if gen_s else None
    return canvas_ids, canvas_text, offset_map, gen_s, tps


def _trace_payload(payload, tok, model, watch_positions):
    """Build an optional TraceStreamer from the payload flag. `trace`:
      falsy            -> no trace (but step count still comes from tokens_per_forward)
      true / "canvas"  -> cheap per-step stability trace (argmax only, no logit copy)
      "entropy"        -> full trace incl. per-position entropy (heavy: full-logit
                          CPU copy each step)."""
    flag = payload.get("trace")
    if not flag:
        return None
    return TraceStreamer(tok, watch_positions=watch_positions,
                         with_entropy=(flag == "entropy"),
                         entropy_bound=_effective_entropy_bound(model, payload),
                         max_steps=int(payload.get("trace_max_steps", 64)))


@Endpoint(
    name="diffgemma",
    gpu=GpuType.NVIDIA_A100_80GB_PCIe,   # 80GB → BF16 (~50GB) + long-context KV
    datacenter=DataCenter.EU_RO_1,        # MUST equal the volume's DC
    volume=_VOL,                          # mounted at /runpod-volume
    workers=(0, 1),                       # scale-to-zero; $0 when idle
    idle_timeout=600,                     # stay warm 10 min between bursts
    flashboot=True,
    template=PodTemplate(containerDiskInGb=120),
    # torch is force-stripped from Flash deps and comes ONLY from the custom
    # FLASH_GPU_IMAGE (Dockerfile). transformers is a pure-python wheel and also
    # lives in the image; listing it here is belt-and-suspenders, harmless.
    dependencies=["transformers==5.11.0", "accelerate", "sentencepiece", "pillow"],
    env={
        "HF_TOKEN": os.environ.get("HF_TOKEN", ""),
        "HF_HOME": "/runpod-volume/hf",
        "HF_HUB_CACHE": "/runpod-volume/hf/hub",
        "PIP_CACHE_DIR": "/runpod-volume/pipcache",
    },
    execution_timeout_ms=1_500_000,
)
def diffgemma(**payload):
    import time, traceback
    tok = os.environ.get("HF_TOKEN")
    mode = payload.get("mode", "generate")

    # Clean image: torch + transformers come pre-installed and matched from the
    # custom FLASH_GPU_IMAGE. No runtime pip, no torch._dynamo probe.
    import torch
    import transformers
    info = {
        "mode": mode,
        "worker_sha": WORKER_SHA,          # PROVE which code ran (stale-measurement guard)
        "transformers": transformers.__version__,
        "torch": torch.__version__,
        "cuda": torch.cuda.is_available(),
        "gpu": torch.cuda.get_device_name(0) if torch.cuda.is_available() else None,
        "vram_gb": round(torch.cuda.get_device_properties(0).total_memory / 1e9, 1)
                   if torch.cuda.is_available() else None,
    }

    # --- mode="probe": cheap imports + config, NO 50GB load ---------------------
    if mode == "probe":
        from transformers import AutoConfig, DiffusionGemmaForBlockDiffusion
        info["class_ok"] = bool(DiffusionGemmaForBlockDiffusion)
        try:
            cfg = AutoConfig.from_pretrained(MID, token=tok)
            info["config_ok"] = True
            info["model_type"] = getattr(cfg, "model_type", None)
        except Exception as e:
            info["config_ok"] = False
            info["config_err"] = f"{type(e).__name__}: {e}"[:200]
        return info

    # --- mode="introspect": reflect over the live model (mask-free oracle) ------
    # Pure reflection — resolves the field/kwarg names the grounding doc could only
    # confirm against 5.12.1, here on the 5.11.0 the worker actually loads. No
    # generate (the clamp feasibility test is its own `clamp_smoke` mode).
    if mode == "introspect":
        import inspect
        try:
            tkz, model, load_s = _load(tok)
            info["load_s"] = load_s
            mod = sys.modules.get(type(model).__module__)
            cfg = getattr(model, "config", None)

            # generate() signature — confirms the logits_processor / streamer /
            # decoder_input_ids seams exist on 5.11.0.
            try:
                info["generate_params"] = list(
                    inspect.signature(model.generate).parameters.keys())
            except (ValueError, TypeError):
                info["generate_params"] = "builtin/unknown"

            # Output object fields (THE original crash — confirm .sequences exists).
            out_cls = getattr(mod, "DiffusionGemmaGenerationOutput", None) if mod else None
            info["output_fields"] = (
                list(getattr(out_cls, "__dataclass_fields__", {}).keys())
                if out_cls is not None else "DiffusionGemmaGenerationOutput not found")

            # EntropyBoundSampler seam (accept/renoise) — confirm the mechanism.
            samp = getattr(mod, "EntropyBoundSampler", None) if mod else None
            info["sampler_located"] = samp is not None
            if samp is not None:
                for m in ("accept_canvas", "renoise_canvas", "initialize_canvas"):
                    try:
                        info[f"{m}_sig"] = str(inspect.signature(getattr(samp, m)))
                    except Exception as e:
                        info[f"{m}_sig"] = f"err: {e}"

            # Block / canvas config + generation defaults (entropy_bound, temp,
            # stopping). These set the denoise dynamics every mode runs under.
            info["canvas_cfg"] = {k: getattr(cfg, k) for k in
                                  ("canvas_length",) if cfg is not None and hasattr(cfg, k)}
            tc = getattr(cfg, "text_config", None)
            info["text_cfg"] = ({k: getattr(tc, k) for k in
                                 ("vocab_size", "use_bidirectional_attention",
                                  "pad_token_id", "eos_token_id", "bos_token_id")
                                 if hasattr(tc, k)} if tc is not None else None)
            gc = getattr(model, "generation_config", None)
            if gc is not None:
                info["gen_config"] = {k: getattr(gc, k) for k in
                                      ("max_denoising_steps", "t_min", "t_max",
                                       "stability_threshold", "confidence_threshold",
                                       "sampler_config")
                                      if hasattr(gc, k)}
                info["gen_config"] = {k: (v.__dict__ if hasattr(v, "__dict__") else v)
                                      for k, v in info["gen_config"].items()}

            # Encoder presence (W1 — retrieval/cross-attn injection feasibility).
            enc = (getattr(model, "encoder", None)
                   or getattr(getattr(model, "model", None), "encoder", None))
            info["has_encoder_attr"] = enc is not None

            # Think/reasoning-mode question (HONEST UNKNOWN until checked live):
            # dump the special tokens + rendered chat template, grep for markers.
            info["special_tokens_map"] = getattr(tkz, "special_tokens_map", None)
            info["additional_special_tokens"] = getattr(
                tkz, "additional_special_tokens", None)
            try:
                rendered = tkz.apply_chat_template(
                    [{"role": "user", "content": "hi"}], tokenize=False,
                    add_generation_prompt=True)
                info["chat_template_render"] = rendered[:600]
                low = rendered.lower()
                info["think_markers"] = [m for m in ("think", "reason", "scratchpad",
                                                     "<thinking>") if m in low]
            except Exception as e:
                info["chat_template_err"] = str(e)[:160]
        except Exception as e:
            info["introspect_error"] = f"{type(e).__name__}: {e}"
            info["trace"] = traceback.format_exc()[-1800:]
        return info

    # --- mode="clamp_smoke": THE DECISIVE TEST ----------------------------------
    # Clamp ~3 canvas positions to chosen token ids via ClampLogitsProcessor and
    # confirm they are held FIXED in the output while the rest denoise. Validates
    # the whole slotted-gen approach on the live model.
    if mode == "clamp_smoke":
        try:
            tkz, model, load_s = _load(tok)
            info["load_s"], info["attn_impl"] = load_s, _CACHE.get("attn_impl")

            # clamps: {canvas_position(int) -> token_id(int)}. Default: 3 positions
            # to 3 distinct single-token ids (resolved from `clamp_text` strings if
            # given, else explicit `clamps`, else a built-in default).
            clamps = payload.get("clamps")
            if clamps is None and payload.get("clamp_text"):
                clamps = {int(p): tkz(s, add_special_tokens=False)["input_ids"][0]
                          for p, s in payload["clamp_text"].items()}
            if clamps is None:
                clamps = {int(p): tkz(s, add_special_tokens=False)["input_ids"][0]
                          for p, s in {"5": "hello", "40": "world", "100": "diffusion"}.items()}
            clamps = {int(p): int(t) for p, t in clamps.items()}

            prompt = payload.get("prompt", "Write a short paragraph about rivers.")
            inp = tkz.apply_chat_template(
                [{"role": "user", "content": prompt}], tokenize=True,
                add_generation_prompt=True, return_dict=True,
                return_tensors="pt").to(model.device)
            nprompt = int(inp["input_ids"].shape[-1])

            clamp = ClampLogitsProcessor(clamps)
            from transformers import LogitsProcessorList
            streamer = _trace_payload(payload, tkz, model, sorted(clamps))
            gen_kwargs = dict(
                **inp,
                max_new_tokens=payload.get("max_new_tokens", _canvas_len(model)),
                logits_processor=LogitsProcessorList([clamp]),
                **_gen_overrides(model, payload))
            if payload.get("seed_canvas", True):
                gen_kwargs["decoder_input_ids"] = _seed_canvas(model, clamps)
            if streamer is not None:
                gen_kwargs["streamer"] = streamer

            out, gen_s = _run(model, gen_kwargs)
            seqs = out.sequences
            comp = seqs[0][nprompt:]                       # the canvas (canvas_len ids)

            # The decisive assertion: canvas position `pos` -> the forced id.
            held = {}
            for pos, tid in clamps.items():
                got = int(comp[pos])
                held[pos] = {
                    "forced_id": tid, "forced_tok": tkz.decode([tid]),
                    "got_id": got, "got_tok": tkz.decode([got]), "held": got == tid}
            info.update({
                "clamps": clamps,
                "all_held": all(h["held"] for h in held.values()),
                "positions": held,
                "prompt_tokens": nprompt,
                "completion_text": tkz.decode(comp, skip_special_tokens=True),
                "gen_s": gen_s,
                "tokens_per_forward": (out.tokens_per_forward.tolist()
                                       if getattr(out, "tokens_per_forward", None) is not None
                                       else None),
            })
            if streamer is not None:
                info.update(streamer.summary())
                info["trace"] = streamer.steps
        except Exception as e:
            info["clamp_smoke_error"] = f"{type(e).__name__}: {e}"
            info["trace_err"] = traceback.format_exc()[-1500:]
        return info

    # --- mode="infill": clamp a prefix + a suffix, let the middle denoise -------
    # e.g. prefix="(defn mean [xs] (/ ", suffix=" (count xs)))" -> middle should
    # denoise to "(reduce + xs)". Reuses ClampLogitsProcessor; bidirectional
    # attention is why the middle co-conditions on the SUFFIX (an AR model cannot).
    if mode == "infill":
        try:
            tkz, model, load_s = _load(tok)
            info["load_s"], info["attn_impl"] = load_s, _CACHE.get("attn_impl")
            prefix = payload["prefix"]
            suffix = payload.get("suffix", "")
            max_hole = int(payload.get("max_hole_tokens", 16))
            L = _canvas_len(model)

            pre_ids = tkz(prefix, add_special_tokens=False)["input_ids"]
            suf_ids = tkz(suffix, add_special_tokens=False)["input_ids"]
            total = len(pre_ids) + max_hole + len(suf_ids)
            if total > L:
                info["infill_error"] = (
                    f"prefix({len(pre_ids)})+hole({max_hole})+suffix({len(suf_ids)})"
                    f"={total} exceeds canvas_length {L}")
                return info

            # Canvas layout: [prefix | hole | suffix | free...]. Clamp prefix+suffix.
            clamp_by_pos = {}
            for i, tid in enumerate(pre_ids):
                clamp_by_pos[i] = tid
            suf_start = len(pre_ids) + max_hole
            for j, tid in enumerate(suf_ids):
                clamp_by_pos[suf_start + j] = tid
            hole_positions = list(range(len(pre_ids), len(pre_ids) + max_hole))

            prompt = payload.get(
                "prompt", "Complete the missing code so the function is correct.")
            inp = tkz.apply_chat_template(
                [{"role": "user", "content": prompt}], tokenize=True,
                add_generation_prompt=True, return_dict=True,
                return_tensors="pt").to(model.device)
            nprompt = int(inp["input_ids"].shape[-1])

            clamp = ClampLogitsProcessor(clamp_by_pos)
            from transformers import LogitsProcessorList
            streamer = _trace_payload(payload, tkz, model, hole_positions)
            gen_kwargs = dict(
                **inp, max_new_tokens=L,
                logits_processor=LogitsProcessorList([clamp]),
                decoder_input_ids=_seed_canvas(model, clamp_by_pos),
                **_gen_overrides(model, payload))
            if streamer is not None:
                gen_kwargs["streamer"] = streamer

            out, gen_s = _run(model, gen_kwargs)
            comp = out.sequences[0][nprompt:]              # the canvas (L ids)
            middle_ids = comp[len(pre_ids):len(pre_ids) + max_hole].tolist()
            middle_text = tkz.decode(middle_ids, skip_special_tokens=True)
            assembled = tkz.decode(comp[:suf_start + len(suf_ids)].tolist(),
                                   skip_special_tokens=False)

            pre_held = all(int(comp[i]) == tid for i, tid in enumerate(pre_ids))
            suf_held = all(int(comp[suf_start + j]) == tid for j, tid in enumerate(suf_ids))
            expect = payload.get("expect_contains")
            info.update({
                "prefix": prefix, "suffix": suffix,
                "prefix_tokens": len(pre_ids), "hole_tokens": max_hole,
                "suffix_tokens": len(suf_ids),
                "hole_positions": [hole_positions[0], hole_positions[-1]],
                "prefix_held": pre_held, "suffix_held": suf_held,
                "middle_text": middle_text,
                "assembled": assembled,
                "expect_contains": expect,
                "expect_met": (expect in middle_text) if expect else None,
                "gen_s": gen_s,
                "tokens_per_forward": (out.tokens_per_forward.tolist()
                                       if getattr(out, "tokens_per_forward", None) is not None
                                       else None),
            })
            if streamer is not None:
                info.update(streamer.summary())
                info["trace"] = streamer.steps
        except Exception as e:
            info["infill_error"] = f"{type(e).__name__}: {e}"
            info["trace_err"] = traceback.format_exc()[-1500:]
        return info

    # --- mode="denoise_to_step": EVAL-RENOISE half 1 — partial denoise to step K -
    # Run the REAL N-step schedule (max_denoising_steps untouched) but STOP at step K
    # via an external StepCountStopping, so Seon can parse/eval the PARTIAL canvas.
    # Returns the partial canvas decoded + per-position argmax + char->position
    # offset_map (so a parse/eval :span maps back to canvas positions) + the seed,
    # so the loop can resume via `resume_renoise`. Optional clamp scaffold (like
    # infill) via `clamps`/`clamp_text` to hold a fixed frame while the holes denoise.
    if mode == "denoise_to_step":
        try:
            tkz, model, load_s = _load(tok)
            info["load_s"], info["attn_impl"] = load_s, _CACHE.get("attn_impl")
            K = int(payload["denoise_steps"])          # stop AFTER K steps (N stays intact)
            clamps = _resolve_clamps(tkz, payload)     # optional scaffold ({} if none)
            L = _canvas_len(model)

            inp = tkz.apply_chat_template(
                [{"role": "user", "content": payload["prompt"]}], tokenize=True,
                add_generation_prompt=True, return_dict=True,
                return_tensors="pt").to(model.device)
            nprompt = int(inp["input_ids"].shape[-1])

            from transformers import LogitsProcessorList
            streamer = _trace_payload(payload, tkz, model, sorted(clamps) or None)
            gen_kwargs = dict(**inp, max_new_tokens=L, **_gen_overrides(model, payload))
            if clamps:
                gen_kwargs["logits_processor"] = LogitsProcessorList(
                    [ClampLogitsProcessor(clamps)])
                if payload.get("seed_canvas", True):
                    gen_kwargs["decoder_input_ids"] = _seed_canvas(model, clamps)
            if streamer is not None:
                gen_kwargs["streamer"] = streamer

            out, gen_s, steps_fired = _run_with_optional_stop(model, gen_kwargs, K)
            comp = out.sequences[0][nprompt:]          # the partial canvas (L ids)
            canvas_ids = [int(x) for x in comp.tolist()]
            canvas_text, offset_map = build_offset_map(tkz, canvas_ids)
            seed = gen_kwargs.get("decoder_input_ids")
            info.update({
                "denoise_steps_requested": K,
                "denoise_steps_fired": steps_fired,    # == K when our criterion ran (we REPLACE the builtin)
                "max_denoising_steps_kept": int(getattr(
                    model.generation_config, "max_denoising_steps", None) or 0),
                "clamps": clamps or None,
                "partial_text": tkz.decode(comp, skip_special_tokens=True),
                "canvas_text": canvas_text,            # piecewise decode (incl. special toks) — aligns with offset_map
                "argmax_per_position": canvas_ids,     # pass back as `seed_canvas` to resume_renoise
                "offset_map": offset_map,              # [[pos, char_start, char_end], ...]
                "seed_canvas_in": ([int(x) for x in seed[0].tolist()] if seed is not None else None),
                "gen_s": gen_s,
                "tokens_per_forward": _tpf(out),
            })
            if streamer is not None:
                info.update(streamer.summary())
                info["trace"] = streamer.steps
        except Exception as e:
            info["denoise_to_step_error"] = f"{type(e).__name__}: {e}"
            info["trace_err"] = traceback.format_exc()[-1500:]
        return info

    # --- mode="resume_renoise": EVAL-RENOISE half 2 — clamp GOOD, re-denoise BAD ---
    # The round-trip primitive. Given the prior partial canvas (`seed_canvas`, one
    # token id per position) + the CHAR spans the parser/eval flagged BAD
    # (`renoise_spans`), clamp every GOOD (non-span) committed position and leave the
    # bad span positions free to re-denoise. Returns the new partial/full canvas +
    # fresh offset_map (Seon orchestrates the next parse/eval between calls).
    if mode == "resume_renoise":
        try:
            tkz, model, load_s = _load(tok)
            info["load_s"], info["attn_impl"] = load_s, _CACHE.get("attn_impl")
            L = _canvas_len(model)
            seed_ids = [int(x) for x in payload["seed_canvas"]]
            if len(seed_ids) != L:
                info["resume_renoise_error"] = (
                    f"seed_canvas length {len(seed_ids)} != canvas_length {L}")
                return info
            spans = [[int(s), int(e)] for s, e in payload.get("renoise_spans", [])]

            # Map char spans -> canvas positions over the SEED's own offset map, then
            # build the clamp set of all GOOD positions (committed token held fixed).
            _seed_text, seed_offset = build_offset_map(tkz, seed_ids)
            good_clamp, bad_positions = good_clamp_for_renoise(seed_offset, seed_ids, spans)
            # Optional persistent scaffold (the mode's fixed frame) ALWAYS clamped,
            # overriding even a position that fell inside a renoise span.
            for p, t in _resolve_clamps(tkz, payload).items():
                good_clamp[int(p)] = int(t)
                if int(p) in bad_positions:
                    bad_positions.remove(int(p))

            # INJECTIONS (the unified oracle's `::injections`) — clamp each span
            # toward the real `replacement` (overriding good_clamp at those
            # positions; they leave the renoise set) and collect the `spec_text`s
            # the encoder KV is extended with. Same span→position mapping as
            # mode="inject", over the SEED's own offset_map.
            encode = lambda s: tkz(s, add_special_tokens=False)["input_ids"]
            ic = injection_clamps(payload.get("injections") or [], seed_offset, encode)
            for p, t in ic["clamp_by_pos"].items():
                good_clamp[int(p)] = int(t)
                if int(p) in bad_positions:
                    bad_positions.remove(int(p))
            spec_texts = ic["spec_texts"]
            held = _held_inject_cache(payload)
            route, route_reason = choose_kv_route(
                payload.get("kv_route"),
                has_spec_text=bool(spec_texts),
                has_cache=held is not None,
                cache_extensible=_cache_extensible(held),
                extend_kv=payload.get("extend_kv", True))

            inp = tkz.apply_chat_template(
                [{"role": "user", "content": payload["prompt"]}], tokenize=True,
                add_generation_prompt=True, return_dict=True,
                return_tensors="pt").to(model.device)

            from transformers import LogitsProcessorList
            clamp = ClampLogitsProcessor(good_clamp)
            streamer = _trace_payload(payload, tkz, model, bad_positions or None)
            # Seed: good positions = committed token, bad positions = fresh random
            # (re-noise) — _seed_canvas randomizes every NON-clamped position.
            gen_kwargs = dict(
                max_new_tokens=L,
                logits_processor=LogitsProcessorList([clamp]),
                decoder_input_ids=_seed_canvas(model, good_clamp),
                **_gen_overrides(model, payload))
            # Encoder KV per the W1/W2/W3 route (W3 = prompt only, the unchanged
            # default when there are no injections / no spec_text).
            gen_kwargs.update(_encoder_inject_kwargs(tkz, model, inp, spec_texts,
                                                     route, held))
            if streamer is not None:
                gen_kwargs["streamer"] = streamer

            K = payload.get("denoise_steps")           # optional: checkpoint again at K
            out, gen_s, steps_fired = _run_with_optional_stop(
                model, gen_kwargs, int(K) if K else None)
            comp = out.sequences[0][-L:]               # canvas = last L (route-robust)
            new_ids = [int(x) for x in comp.tolist()]
            new_text, new_offset = build_offset_map(tkz, new_ids)
            good_held = all(int(comp[p]) == t for p, t in good_clamp.items())
            info.update({
                "renoise_spans": spans,
                "n_clamped_good": len(good_clamp),
                "n_renoised_bad": len(bad_positions),
                "n_injections": len(payload.get("injections") or []),
                "route": route,                        # W1/W2/W3 (W3 when no injections)
                "route_reason": route_reason,
                "spec_texts": len(spec_texts),
                "bad_positions_span": ([bad_positions[0], bad_positions[-1]]
                                       if bad_positions else None),
                "good_held": good_held,                # the clamp invariant — must be True
                "denoise_steps_fired": steps_fired,
                "partial_text": tkz.decode(comp, skip_special_tokens=True),
                "canvas_text": new_text,
                "argmax_per_position": new_ids,        # the next seed for another round
                "offset_map": new_offset,
                "gen_s": gen_s,
                "tokens_per_forward": _tpf(out),
            })
            if streamer is not None:
                info.update(streamer.summary())
                info["trace"] = streamer.steps
        except Exception as e:
            info["resume_renoise_error"] = f"{type(e).__name__}: {e}"
            info["trace_err"] = traceback.format_exc()[-1500:]
        return info

    # --- mode="inject": apply the oracle's ::injections (the W1/W2/W3 routes) ----
    # The mid-denoise injection-apply: given the prior partial canvas's `offset_map`
    # (from a denoise_to_step checkpoint) + the unified oracle's `injections`
    # ({span, replacement, spec_text}), CLAMP each span toward the real `replacement`
    # (reusing ClampLogitsProcessor) AND extend the encoder KV with `spec_text` so
    # the decoder cross-attends the real signature — then resume denoising. This is
    # the worker half that makes the Seon-side buzzsaw drive the model toward names
    # that EXIST. Opt-in: only runs when `injections` is present.
    if mode == "inject":
        try:
            tkz, model, load_s = _load(tok, experts_impl=payload.get("experts_impl"))
            info["load_s"], info["attn_impl"] = load_s, _CACHE.get("attn_impl")
            L = _canvas_len(model)
            injections = payload.get("injections") or []

            # offset_map maps the oracle's CHAR spans → canvas TOKEN positions. It
            # comes from the prior denoise_to_step; if only the seed canvas is given,
            # derive it (same basis build_offset_map produced there).
            offset_map = payload.get("offset_map")
            seed_ids = payload.get("seed_canvas")
            if offset_map is None and seed_ids is not None:
                _t, offset_map = build_offset_map(tkz, [int(x) for x in seed_ids])
            if offset_map is None:
                info["inject_error"] = ("inject requires offset_map (or seed_canvas "
                                        "to derive it)")
                return info

            encode = lambda s: tkz(s, add_special_tokens=False)["input_ids"]
            ic = injection_clamps(injections, offset_map, encode)
            clamp_by_pos = dict(ic["clamp_by_pos"])
            # An optional persistent scaffold (resume good-clamp / fixed frame) sits
            # UNDER the injection clamps — an injection wins on a shared position.
            for p, t in _resolve_clamps(tkz, payload).items():
                clamp_by_pos.setdefault(int(p), int(t))

            spec_texts = ic["spec_texts"]
            held = _held_inject_cache(payload)
            route, route_reason = choose_kv_route(
                payload.get("kv_route"),
                has_spec_text=bool(spec_texts),
                has_cache=held is not None,
                cache_extensible=_cache_extensible(held),
                extend_kv=payload.get("extend_kv", True))

            inp = tkz.apply_chat_template(
                [{"role": "user", "content": payload["prompt"]}], tokenize=True,
                add_generation_prompt=True, return_dict=True,
                return_tensors="pt").to(model.device)

            from transformers import LogitsProcessorList
            streamer = _trace_payload(payload, tkz, model, sorted(clamp_by_pos) or None)
            gen_kwargs = dict(max_new_tokens=L, **_gen_overrides(model, payload))
            gen_kwargs.update(_encoder_inject_kwargs(tkz, model, inp, spec_texts,
                                                     route, held))
            input_len = int(gen_kwargs["input_ids"].shape[-1])
            if clamp_by_pos:
                gen_kwargs["logits_processor"] = LogitsProcessorList(
                    [ClampLogitsProcessor(clamp_by_pos)])
                # resume from the prior canvas when given (good positions persist),
                # else a fresh-random seed with the clamps pre-set.
                base = [int(x) for x in seed_ids] if seed_ids else None
                gen_kwargs["decoder_input_ids"] = _seed_canvas(model, clamp_by_pos, base)
            if streamer is not None:
                gen_kwargs["streamer"] = streamer

            K = payload.get("denoise_steps")
            out, gen_s, steps_fired = _run_with_optional_stop(
                model, gen_kwargs, int(K) if K else None)
            comp = out.sequences[0][-L:]                # the canvas (last L positions)
            new_ids = [int(x) for x in comp.tolist()]
            new_text, new_offset = build_offset_map(tkz, new_ids)

            # The decisive assertion: each clamped injection position committed its
            # forced replacement token; the span now reads the REAL symbol.
            committed = {}
            for d in ic["detail"]:
                rep_ids = d["replacement_ids"]
                positions = d["positions"][:d["n_clamped"]]
                got = [int(comp[p]) for p in positions]
                committed[str(d["span"])] = {
                    "replacement": d["replacement"],
                    "n_clamped": d["n_clamped"],
                    "free_positions": d["free_positions"],
                    "dropped_token_ids": d["dropped_token_ids"],
                    "got_text": tkz.decode(got, skip_special_tokens=False),
                    "held": got == rep_ids[:d["n_clamped"]],
                }
            info.update({
                "route": route,
                "route_reason": route_reason,
                "n_injections": len(injections),
                "n_clamped_positions": len(clamp_by_pos),
                "spec_texts": len(spec_texts),
                "injections_held": all(c["held"] for c in committed.values())
                                   if committed else None,
                "injections": committed,
                "input_tokens": input_len,
                "denoise_steps_fired": steps_fired,
                "partial_text": tkz.decode(comp, skip_special_tokens=True),
                "canvas_text": new_text,
                "argmax_per_position": new_ids,
                "offset_map": new_offset,
                "gen_s": gen_s,
                "tokens_per_forward": _tpf(out),
            })
            if streamer is not None:
                info.update(streamer.summary())
                info["trace"] = streamer.steps
        except Exception as e:
            info["inject_error"] = f"{type(e).__name__}: {e}"
            info["trace_err"] = traceback.format_exc()[-1500:]
        return info

    # --- mode="refine_loop": THE in-worker closed loop (the perf fix) -----------
    # The whole buzzsaw, SERVER-SIDE: denoise_to_step → local parse over the
    # PERSISTENT bb pipe (_oracle("parse"), ~0.05ms warm — NOT subprocess.run per
    # call) → if the parse flags broken spans, build the good-clamp and
    # resume_renoise those spans → re-parse → repeat, a tight Python loop bounded
    # by `max_iters`. This collapses BOTH round-trips the network driver pays: no
    # internet API hop (in-process forward) and no bb respawn (persistent shim).
    # Each iteration reports errors_before/after, tok_per_s, and oracle_ms (the
    # per-checkpoint pipe round-trip — O5), so the GPU session measures the
    # in-worker latency directly.
    #
    # VALIDATION-AS-EARLY-STOP (the owner's lever): the loop terminates the moment
    # the ORACLE validates the canvas — NOT on the model's confidence/step-count.
    # The gate is two tiers: parse-clean (well-formed) AND eval-clean (it actually
    # RUNS). "As soon as it parses, run it; if it runs, STOP — the model's
    # probability is irrelevant once we have PROOF it executes." `eval_gate` (dflt
    # on) adds the eval tier; with it off the loop short-circuits on parse-clean
    # alone (the prior behaviour). Parse errors carry SPANS → renoise just those;
    # an eval failure (def-vs-defn, undeclared var) has NO span → re-open the whole
    # canvas for the next iteration (the scaffold path does span-targeted
    # eval-renoise; the free refine_loop re-denoises fully).
    if mode == "refine_loop":
        import time, re
        try:
            tkz, model, load_s = _load(tok, experts_impl=payload.get("experts_impl"))
            info["load_s"], info["attn_impl"] = load_s, _CACHE.get("attn_impl")
            prompt = payload["prompt"]
            K = int(payload.get("denoise_steps", 16))
            max_iters = int(payload.get("max_iters", 4))
            eval_gate = bool(payload.get("eval_gate", True))
            gov = _gen_overrides(model, payload)
            oracle = _oracle("parse")          # spawn-once persistent bb pipe

            def _parse(canvas):
                t0 = time.perf_counter()
                r = oracle.call("parse-raw", canvas)   # canvas basis == offset_map basis
                return r, round((time.perf_counter() - t0) * 1000.0, 3)

            # The eval tier runs the canvas POD-FREE (no schema ns), so peel the
            # `(schema/register! …)` / `(register! …)` forms — that's the structural
            # tier's job; what remains is the self-contained runnable artifact.
            _REG = re.compile(r"\(\s*(?:[\w.\-]+/)?register!\b[^()]*(?:\([^()]*\)[^()]*)*\)")

            # T3 behavioral tests: [{call, expect}, …]. When present, the gate is
            # not just "it RUNS" but "it gives the RIGHT ANSWER" — the strongest
            # early-stop: the model's probability is irrelevant once the fn is
            # PROVEN correct against the spec. Empty => the gate stops at eval (T2).
            behavioral = payload.get("behavioral") or []

            def _eval_code(code):
                ev = _oracle("eval")
                t0 = time.perf_counter()
                v = ev.call("eval", code, **{"budget-ms": int(payload.get("eval_budget_ms", 1500))})
                return v, round((time.perf_counter() - t0) * 1000.0, 3)

            def _eval(canvas):
                """T2: does it RUN? (eval_ok, eval_kind|None, ms). eval_kind ∈
                {compile, throw, interrupt} on failure; None on success."""
                v, oms = _eval_code(_REG.sub("", canvas).strip())
                ok = bool(v.get("ok"))
                return ok, (None if ok else (v.get("error", {}) or {}).get("kind", "throw")), oms

            def _behavioral(canvas):
                """T3: does it give the RIGHT ANSWER? Append each {call,expect} to
                the runnable code, eval, compare the printed value. Returns
                (all_pass|None, detail|None, ms|None) — None when no tests (tier
                skipped, NOT a failure)."""
                if not behavioral:
                    return None, None, None
                code, detail, tot = _REG.sub("", canvas).strip(), [], 0.0
                for tc in behavioral:
                    call, expect = tc["call"], str(tc["expect"])
                    v, ms = _eval_code(code + "\n" + call)
                    tot += ms
                    got = str(v.get("value")) if v.get("ok") else None
                    detail.append({"call": call, "expect": expect, "got": got,
                                   "pass": bool(v.get("ok")) and got == expect})
                return all(d["pass"] for d in detail), detail, round(tot, 3)

            def _checkpoint(canvas_text):
                """The validation LADDER: parse (T0) → eval (T2) → behavioral (T3),
                stopping at the cheapest decisive tier. Returns a telemetry dict;
                later tiers run only when earlier ones pass."""
                r, p_ms = _parse(canvas_text)
                errs = r.get("errors", []) or []
                ck = {"errs": errs, "eval_ok": (None if errs else True), "eval_kind": None,
                      "behav_ok": None, "behav_detail": None,
                      "parse_ms": p_ms, "eval_ms": None, "behav_ms": None}
                if errs or not eval_gate:
                    return ck
                ck["eval_ok"], ck["eval_kind"], ck["eval_ms"] = _eval(canvas_text)
                if not ck["eval_ok"]:
                    return ck
                ck["behav_ok"], ck["behav_detail"], ck["behav_ms"] = _behavioral(canvas_text)
                return ck

            def _is_validated(ck):
                """Validated = parse-clean AND runs AND (no behavioral tests OR they
                all pass). behav_ok None = tier skipped (not a failure)."""
                return (not ck["errs"]) and bool(ck["eval_ok"]) and (ck["behav_ok"] is not False)

            def _iter_telemetry(ck):
                return {"errors_after": len(ck["errs"]),
                        "error_spans": [e["span"] for e in ck["errs"]],
                        "eval_ok": ck["eval_ok"], "eval_kind": ck["eval_kind"],
                        "behav_ok": ck["behav_ok"], "behav_detail": ck["behav_detail"],
                        "validated": _is_validated(ck),
                        "oracle_ms": ck["parse_ms"], "eval_ms": ck["eval_ms"],
                        "behav_ms": ck["behav_ms"]}

            # Iter 0: fresh denoise to step K, then walk the validation ladder.
            canvas_ids, canvas_text, offset_map, gen_s, tps = _denoise_canvas(
                tkz, model, prompt, K, gen_overrides=gov)
            ck = _checkpoint(canvas_text)
            errs, validated = ck["errs"], _is_validated(ck)
            iters = [{
                "iter": 0, "kind": "denoise", "denoise_steps": K,
                "errors_before": None,
                "canvas_text": canvas_text, "tok_per_s": tps, "gen_s": gen_s,
                **_iter_telemetry(ck),
            }]

            # Validation-gated renoise loop: while NOT validated AND iterations
            # remain, re-open the broken region and re-denoise. Parse errors →
            # renoise only the flagged spans (clamp the good); an eval-only failure
            # (parse clean, won't run) → re-open the whole canvas.
            for it in range(1, max_iters + 1):
                if validated:
                    break                          # the oracle PROVED it — STOP
                if errs:
                    spans = [[int(s), int(e)] for (s, e) in (e_["span"] for e_ in errs)]
                    good_clamp, bad_positions = good_clamp_for_renoise(
                        offset_map, canvas_ids, spans)
                    seed = _seed_canvas(model, good_clamp, base=canvas_ids)
                    reopen = "spans"
                else:                              # eval-fail, no span → full re-open
                    good_clamp, bad_positions, seed, reopen = {}, [], None, "eval-full"
                errors_before = len(errs)
                canvas_ids, canvas_text, offset_map, gen_s, tps = _denoise_canvas(
                    tkz, model, prompt, K, decoder_input_ids=seed,
                    clamp_by_pos=good_clamp, gen_overrides=gov)
                ck = _checkpoint(canvas_text)
                errs, validated = ck["errs"], _is_validated(ck)
                iters.append({
                    "iter": it, "kind": "renoise", "reopen": reopen,
                    "n_clamped_good": len(good_clamp),
                    "n_renoised_bad": len(bad_positions),
                    "errors_before": errors_before,
                    "canvas_text": canvas_text, "tok_per_s": tps, "gen_s": gen_s,
                    **_iter_telemetry(ck),
                })

            total_gen_s = round(sum(i["gen_s"] for i in iters), 3)
            L = _canvas_len(model)
            info.update({
                "denoise_steps": K,
                "max_iters": max_iters,
                "eval_gate": eval_gate,
                "behavioral_tests": len(behavioral),
                "iters_run": len(iters),
                "converged": validated,            # parse-clean AND runs AND (no tests OR they pass)
                "validated": validated,
                "final_errors": len(errs),
                "final_eval_ok": ck["eval_ok"],
                "final_eval_kind": ck["eval_kind"],
                "final_behav_ok": ck["behav_ok"],
                "final_behav_detail": ck["behav_detail"],
                "final_text": canvas_text,
                "iterations": iters,
                "tok_per_s": round(L * len(iters) / total_gen_s, 1) if total_gen_s else None,
                "total_gen_s": total_gen_s,
                "oracle_ms_mean": round(
                    sum(i["oracle_ms"] for i in iters) / len(iters), 3),
                "oracle_persistent": True,         # ONE spawn-once server, not respawn
            })
        except Exception as e:
            info["refine_loop_error"] = f"{type(e).__name__}: {e}"
            info["trace_err"] = traceback.format_exc()[-1500:]
        return info

    # --- mode="generate" + kv_reuse: prefix-KV reuse path (§6) -------------------
    # Opt-in flag; UNSET => fall straight through to the stock generate below, zero
    # behavior change. Changes worker code => worker_sha shifts => verify_fresh flags
    # it (correct).
    if payload.get("kv_reuse"):
        return _kv_reuse_generate(payload, tok, info)

    # --- mode="generate": full text generate (default) --------------------------
    try:
        tkz, model, load_s = _load(tok, experts_impl=payload.get("experts_impl"))
        info["load_s"], info["attn_impl"] = load_s, _CACHE.get("attn_impl")
        # PROVE which MoE backend is live (config is source of truth, not the kwarg):
        # grouped_mm (default) vs batched_mm (the find_spec/CUDA-graphs dodge, §15).
        info["experts_impl"] = getattr(model.config, "_experts_implementation", None)
        inp = tkz.apply_chat_template(
            [{"role": "user", "content": payload["prompt"]}], tokenize=True,
            add_generation_prompt=True, return_dict=True,
            return_tensors="pt").to(model.device)
        nprompt = int(inp["input_ids"].shape[-1])
        streamer = _trace_payload(payload, tkz, model, None)
        gen_kwargs = dict(**inp, max_new_tokens=payload.get("max_new_tokens", 256),
                          **_gen_overrides(model, payload))
        # COMPILE knob (torch-compile-speed-worker doc): a static cache flips
        # is_compiling (generation_diffusion_gemma.py:692) → _compile_functions.
        # The switch is the cache, nothing else. PIN max_length to avoid
        # recompile-on-grow (:1144) — same fixed value across calls = no recompile.
        if payload.get("compile"):
            # CORRECTED (dig 1a475ce9): `_prepare_generated_length` (:880) honors
            # max_length ONLY when max_new_tokens==256 (default). So OMIT
            # max_new_tokens here — else max_length is dropped → cache size
            # cur_len-128 → NEGATIVE for short prompts → step-sign arange error.
            gen_kwargs.pop("max_new_tokens", None)
            gen_kwargs["cache_implementation"] = "static"
            gen_kwargs["max_length"] = int(payload.get("max_length", 512))  # >256 + > max prompt
        info["compiled"] = bool(payload.get("compile"))
        if streamer is not None:
            gen_kwargs["streamer"] = streamer
        out, gen_s = _run(model, gen_kwargs)
        # DiffusionGemmaGenerationOutput (ModelOutput) — NOT a bare tensor:
        # .sequences is the (batch, seq) ids; .tokens_per_forward is a diffusion
        # diagnostic (generation_diffusion_gemma.py:242-269).
        seqs = out.sequences
        ncomp = int(seqs.shape[-1]) - nprompt
        info.update({
            "text": tkz.decode(seqs[0][nprompt:], skip_special_tokens=True),
            "prompt_tokens": nprompt, "completion_tokens": ncomp,
            "gen_s": gen_s,
            "tok_per_s": round(ncomp / gen_s, 1) if gen_s else None,
            "tokens_per_forward": (out.tokens_per_forward.tolist()
                                   if getattr(out, "tokens_per_forward", None) is not None
                                   else None),
        })
        if streamer is not None:
            info.update(streamer.summary())
            info["trace"] = streamer.steps
    except Exception as e:
        info["gen_error"] = f"{type(e).__name__}: {e}"[:300]
        info["trace_err"] = traceback.format_exc()[-1200:]
        # a failed compiled warmup leaves a half-built static cache that poisons
        # later calls (dig 1a475ce9) — clear it so the next call retries clean.
        try:
            if "model" in _CACHE:
                _CACHE["model"].__dict__.pop("_cache", None)
        except Exception:
            pass
    return info


async def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "probe"
    if mode in ("probe", "introspect"):
        r = await diffgemma({"mode": mode})
    elif mode == "clamp_smoke":
        r = await diffgemma({"mode": "clamp_smoke", "trace": "canvas"})
    elif mode == "infill":
        r = await diffgemma({"mode": "infill",
                             "prefix": "(defn mean [xs] (/ ",
                             "suffix": " (count xs)))",
                             "max_hole_tokens": 16,
                             "expect_contains": "(reduce + xs)"})
    elif mode == "denoise_to_step":
        prompt = ("Write an idiomatic Clojure function `mean` that returns the "
                  "average of a vector of numbers. Reply with ONLY the code.")
        r = await diffgemma({"mode": "denoise_to_step", "prompt": prompt,
                             "denoise_steps": 8, "trace": "canvas"})
    elif mode == "resume_renoise":
        # Standalone round-trip smoke: denoise PARTIALLY, then re-noise an early char
        # span and resume. (In Seon the span comes from a real parse/eval failure.)
        prompt = ("Write an idiomatic Clojure function `mean` that returns the "
                  "average of a vector of numbers. Reply with ONLY the code.")
        first = await diffgemma({"mode": "denoise_to_step", "prompt": prompt,
                                 "denoise_steps": 6})
        seed = first.get("argmax_per_position")
        if not seed:
            r = {"resume_renoise_setup_failed": first}
        else:
            r = await diffgemma({"mode": "resume_renoise", "prompt": prompt,
                                 "seed_canvas": seed,
                                 "renoise_spans": [[0, 24]],
                                 "denoise_steps": 12, "trace": "canvas"})
    elif mode == "inject":
        # Standalone injection-apply smoke: partial-denoise a canvas, then steer a
        # seeded hallucination toward its real symbol + spec_text (in Seon the
        # injection comes from the oracle's retrieval leg). W3 by default (no held
        # cache over the JSON boundary); force W1/W2 via "kv_route".
        prompt = ("Write an idiomatic Clojure function `mean` that returns the "
                  "average of a vector of numbers. Reply with ONLY the code.")
        first = await diffgemma({"mode": "denoise_to_step", "prompt": prompt,
                                 "denoise_steps": 6})
        seed = first.get("argmax_per_position")
        omap = first.get("offset_map")
        canvas = first.get("canvas_text", "")
        if not seed:
            r = {"inject_setup_failed": first}
        else:
            # Steer a demo span (first 9 chars of the partial canvas) toward a real
            # symbol; on a live drive the span/replacement/spec_text come from refine.
            r = await diffgemma({
                "mode": "inject", "prompt": prompt,
                "seed_canvas": seed, "offset_map": omap,
                "injections": [{"span": [0, min(9, len(canvas))],
                                "replacement": "(defn mean",
                                "spec_text": "clojure.core/defn (name [params] body) — define a fn"}],
                "denoise_steps": 12, "trace": "canvas"})
    else:
        prompt = ("Write an idiomatic Clojure function `mean` that returns the "
                  "average of a vector of numbers. Reply with ONLY the code in a "
                  "```clojure block.")
        r = await diffgemma({"mode": "generate", "prompt": prompt,
                             "max_new_tokens": 256, "trace": "canvas"})
    print("RESULT:", json.dumps(r, indent=2, default=str))

if __name__ == "__main__":
    asyncio.run(main())
