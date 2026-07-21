---
type: research
status: active
tags: [research, diffusion, agent, flow]
---

# Section / prefix KV caching for the DiffusionGemma transformers path

> Stop re-encoding the heavy, REPEATED context (soul, shared-instructions, the
> ~2400-tok skill, the ~2900-tok required-API block, namespace renders) on every
> generation. The workload is mostly repeated sections; the task varies. This doc
> grounds what transformers v5.11.0 + the DiffusionGemma path actually support, states
> the position-dependence problem precisely, surveys the position-independent-reuse
> SOTA (PromptCache / Block-Attention / EPIC / vLLM-APC / SGLang-RadixAttention), and
> gives a buildable, content-addressed per-BLOCK design keyed to Seon's existing
> `:seon.agent.ctx/block` structure. Every model claim cites
> `reference-code/transformers/…:LINE` (pinned v5.11.0, the deployed worker version).

## Status (2026-06-28)

- **Key-derivation half — DONE (pure Seon, no GPU).** `seon.agent.ctx/block-chain-keys`
  (`src/seon/agent/ctx.cljs`) computes the per-block chain-hash vector from a turn's
  ordered ctx blocks + `:seon.agent/id`, mirroring vLLM's APC chain hash
  (`reference-code/vllm/vllm/v1/core/kv_cache_utils.py:577-603` + the chain
  `:703-728`, cache_salt `:560-561`). Three invariant tests green in the cljs suite
  (`test/seon/ctx_test.cljs`: identical-seq→identical-keys, shared-prefix-diverges-at-
  first-change, salt-scopes-by-agent). See "Worker integration contract" at the end.
- **Worker-reuse half — BUILT (code), AWAITS-GPU-MEASUREMENT.** The encoder `Cache`
  lookup/crop/reuse keyed on these hashes is written in the worker (gitignored
  `tmp/flash-diffgemma/`): the `kv_reuse` payload path in `gpu_worker.py`
  (`_kv_reuse_generate` + the `mode="generate"` branch) over the LRU + walk in
  `diffgemma_common.py` (`KVPrefixCache`, `longest_prefix_hit`). `py_compile`-clean;
  the pure walk/LRU logic is unit-proven off-GPU (`test_kv_walk.py`, 13 units green,
  no torch). Default (`kv_reuse` unset) = stock generate, zero change. Writing the
  worker shifts `worker_sha`, so `verify_fresh` flags it (correct). Still needs the
  co-location image (§5 precondition) + the owner's two-request bit-exact + prefill-
  drop measurement to GREENLIGHT. **Two source-grounded corrections to the contract
  below (read §6):** (1) generate is fed the SUFFIX `input_ids[:, L:]`, not the full
  prompt (`generation_…:635-636` adds the cache length to `cur_len`); (2) the reuse
  path forces `cache_implementation="dynamic_full"` (a uniform full `DynamicCache`)
  so `crop()`/`get_seq_length()` are bit-exact for every layer — sidestepping the
  hybrid sliding-window rolling-buffer hazard. The worker also OWNS tokenization +
  offsets (the pod's char/4 estimate can't produce token offsets).

## TL;DR

1. **DiffusionGemma is an encoder–decoder diffuser, and the prompt encoder is
   CAUSAL.** The encoder builds a KV cache over the prompt; the decoder denoises the
   canvas with bidirectional self-attention + read-only cross-attention to that
   encoder KV cache (`modeling_diffusion_gemma.py:369-383`, `:1294-1340`). The encoder
   uses `create_causal_mask` / `create_sliding_window_causal_mask` (`:927-933`) and the
   block-AR loop APPENDS encoder KV incrementally per canvas (`generation_…:714-735`,
   `is_prefill` then `input_ids[:, -canvas_length:]` at `:941`). Incremental append is
   only valid under causal attention — so **the prompt encoding is position-causal, not
   bidirectional.** This is the single most important fact: it makes **exact
   full-prefix caching feasible with ZERO accuracy loss**, which the bidirectional case
   would forbid.

2. **The cheap, realistic win = full-prefix (block-chain) caching, and Seon is the
   ideal shape for it.** Because Seon already orders ctx blocks static→volatile (the
   `default-seed-blocks` comment literally says "everything through :namespaces is the
   cacheable prefix", `agent/ctx.cljs:1599+`), the repeated content IS a contiguous
   prefix. Encode the stable prefix's encoder KV once, slice/reuse it across turns and
   across the skill-A/B fan-out. transformers already exposes the primitives:
   `generate(..., past_key_values=pkv)` returns and accepts the encoder `Cache`
   (`generation_…:576,826,635-636`) and `DynamicCache.crop(max_length)` truncates a
   cache to a prefix boundary (`cache_utils.py:163,1337`). **For the skill-A/B pattern
   the skill's ~2400-tok KV is encoded ONCE and reused across N tasks** (a distinct
   radix branch per skill), exactly SGLang RadixAttention / vLLM automatic-prefix-caching
   — which we ADOPT (the block-hash + longest-prefix-match algorithm), not reinvent.

3. **Position-independent PER-BLOCK reuse (a block cached once, reassembled at varying
   positions) is NOT needed for Seon and NOT cleanly feasible on this model.** The
   reason it's not NEEDED: Seon fixes block ORDER, so a section never appears at a
   varying position (the RAG problem PromptCache/Block-Attention exist to solve) — the
   skill *slot* is at a fixed position; only its *content* varies, which full-prefix
   radix caching already handles. The reason it's not cleanly FEASIBLE: causal KV makes
   a block's K/V depend on its entire preceding prefix; isolating a block (Block-Attention)
   sacrifices cross-block attention and needs **fine-tuning** to recover accuracy; EPIC's
   recompute-the-boundary fix + RoPE re-rotation is doable but the model is MoE with
   MIXED RoPE per layer-type (full layers: proportional rope, `partial_rotary_factor=0.25`,
   θ=1e6; sliding: default θ=1e4 — `configuration_…:130-133`) and is untrained for
   block-diagonal attention → out-of-distribution, accuracy unknown. **Verdict: ship
   full-prefix caching; treat per-block PIC as a deferred, gym-gated research arm.**

4. **Where it pays the most: the eval-renoise RESUME loop** (the
   `eval-renoise-worker-build` doc flagged "KV-cache reuse is NOT wired"). That loop
   calls `generate()` K-step-at-a-time and re-encodes the WHOLE prompt every resume.
   Caching the committed-prefix encoder KV across the outer loop turns N re-encodes into
   1 — a compounding win precisely in the regime the buzzsaw lives in. Same for
   multi-turn agents and short generations (spec-slot, infill), where prefill is a large
   fraction of wall time.

5. **The one hard gate: the encoder `Cache` cannot ride a JSON worker payload.** Today
   the worker is HTTP/JSON, so every call re-encodes (worker-build §nuance-4). Prefix
   reuse REQUIRES the **in-process co-location** image (Seon calls `generate` directly,
   holds the `Cache` in Python memory) — which is already on the roadmap for other
   reasons (`custom-image-and-seon-colocation`). No co-location → no KV reuse. This is
   the architectural precondition, not an optimization detail.

---

## 1. What transformers v5.11.0 + the diffusion path support TODAY

### 1.1 The architecture (encoder builds the prompt KV; decoder reads it)

`DiffusionGemmaForBlockDiffusion` is two stacks:

- **Encoder** (`DiffusionGemmaEncoderTextModel.forward`, `modeling_…:895-960`) embeds
  the prompt tokens and runs causal self-attention, writing K/V into `past_key_values`
  via `past_key_values.update(...)` per layer (`:344`). Mask = `create_causal_mask` +
  `create_sliding_window_causal_mask` (`:927-933`). `is_causal = use_bidirectional_attention
  != "all"` (`:281`) — default `None` ⇒ causal. **The encoder produces the reusable
  prefix KV.**
- **Decoder** (`DiffusionGemmaDecoderModel`, `:1167-1290`) takes the random-init canvas,
  runs **bidirectional** self-attention over the canvas (`is_causal=False` always,
  `:383`) and **read-only cross-attention** into the encoder KV cache — "from the
  decoder's perspective it is a read-only encoder KV cache; it does NOT update the cache
  in forward" (`:374-376`). The decoder mask (`:1294-1340`) = bidirectional over the
  canvas + full attention to all non-pad encoder positions.

So the expensive, repeated work is **encoder prefill over the whole prompt**, redone on
every `generate()` (`generation_…:714-735`, prefill branch). The denoise loop
(`max_denoising_steps` × `max_new_canvases` decoder forwards) does NOT re-touch the
prompt — it cross-attends the already-built encoder cache. **Caching the encoder prefix
KV is therefore a clean, isolated target.**

### 1.2 The block-AR loop proves causal, append-only prefix KV

Per canvas (`:713-735`): on prefill, encode ALL `input_ids`; thereafter encode only the
newly-committed `input_ids[:, -canvas_length:]` (`:941`) and APPEND to the same cache
(`encoder_position_ids` advances at `:1121`, `past_key_values = encoder_outputs.past_key_values`
at `:734`). Incremental append without recomputing earlier KV is correct **only** if
earlier positions don't attend forward — i.e. causal. This is independent confirmation
that the prompt encoding is position-causal: **a prefix's KV is fixed regardless of what
follows it.** That property is the entire license for exact prefix caching.

### 1.3 Multi-turn cache reuse is already a first-class output

`generate()` returns `past_key_values` and accepts it back (`generation_…:266,826` and
`:576,635-636`): "It can be passed to subsequent calls to `generate` to speed up
generation, in multi-turn sessions." So the *mechanism* to skip re-encoding a committed
prefix EXISTS — it is just not wired for our cross-call/section case, and it cannot cross
the JSON boundary (§5). What's missing is (a) in-process holding of the cache and (b) a
content-addressed lookup so a NEW prompt that shares a prefix with an OLD one reuses it.

### 1.4 Cache primitives we can build on (no reinvention)

- `DynamicCache.crop(max_length)` (`cache_utils.py:163,1337`) truncates the per-layer K/V
  to the first `max_length` positions — **the slice primitive** for "reuse the cache up to
  block boundary b, drop the rest."
- `DynamicCache.batch_repeat_interleave(repeats)` (`:177,1342`) — fan a single cached
  prefix into a batch (drive B re-noise variants / B tasks sharing one skill prefix in one
  `generate`).
- The hybrid/sliding cache structure: when a `config` is passed, sliding-attention layers
  only retain `min(seq_len, sliding_window)` = last 512 positions
  (`DynamicCache` docstring `:1398-1399`; `sliding_window=512`, 5:1 sliding:full,
  `configuration_…:102,118-121`). **Implication:** only the **full-attention** layers
  (every 6th + the forced-last, `:124-128`) hold the long-range prefix KV, so prefix
  caching's memory cost and its benefit both concentrate on those ~1/6 of layers. The
  sliding layers re-encode cheaply from the local window anyway.

### 1.5 What is NOT supported today

No prefix-hash store, no longest-prefix match, no PromptCache/PML, no position-independent
reuse, no cross-`generate()` content-addressing. `generate` will accept a hand-built
`Cache`, but YOU must produce the right one for the new prompt. That assembly is the design
in §3.

---

## 2. The position-dependence problem, stated precisely + the SOTA

### 2.1 Why a section's KV is not freely relocatable

Two coupled position dependencies:

1. **RoPE** rotates Q/K by absolute position before the dot product
   (`apply_rotary_pos_emb`, `modeling_…:204`, applied at `:329,337,431,439`). A token's
   cached K at position p is "rotated to p." Reusing it at a different position q requires
   re-rotating by (q−p). DiffusionGemma complicates this: **per-layer-type RoPE** — full
   layers use `rope_type="proportional"`, `partial_rotary_factor=0.25`, θ=1e6; sliding use
   `rope_type="default"`, θ=1e4 (`configuration_…:130-133`). Only 25% of the head dims are
   rotated on full layers. Re-rotation is therefore **layer-type-aware and partial**, not a
   single global shift.
2. **Causal content dependence (the deeper one).** Even with RoPE solved, in causal
   attention the hidden state at position i — hence its K/V — is a function of *all* tokens
   j ≤ i. So a section's KV depends not just on its own text but on **everything before
   it.** A section's cache is bit-exact only when its entire preceding prefix is identical.
   This is exactly why naive reuse "only works at the same position with the same prefix"
   (PromptCache: *"attention states are position-dependent … the attention states of a
   text segment can only be reused if the segment appears at the same position"* —
   [arxiv 2311.04934](https://arxiv.org/abs/2311.04934)).

### 2.2 The SOTA for position-independent section reuse (web)

| Technique | Idea | Catch for us |
|---|---|---|
| **PromptCache** ([2311.04934](https://arxiv.org/abs/2311.04934), MLSys'24) | PML "prompt modules" each get **fixed, pre-assigned position IDs**; precompute each module's KV in isolation; reuse at those positions. 8× GPU TTFT, 60× CPU. | Each module is encoded in ISOLATION → loses cross-module attention. Fixed position-ID budget is awkward for variable namespace renders. Reportedly small quality hit; **untrained on DiffusionGemma's block-diagonal regime.** |
| **Block-Attention** ([2409.15355](https://arxiv.org/html/2409.15355)) | Each retrieved block computes KV **independently** (block-diagonal mask); only the last block attends across. **RoPE re-encoding is "straightforward"**; recovers full-attn accuracy on RAG **after fine-tuning**. | Needs **fine-tuning** the model to tolerate block-diagonal attention. We don't own the checkpoint's training. High lift. |
| **EPIC / LegoLink** ([2410.15332](https://arxiv.org/abs/2410.15332), ICML'25) | Position-Independent Caching: concatenate cached block KV regardless of prefix, then **recompute a small subset of boundary tokens** to fix the "attention-sink" at each block start. Up to 8× TTFT, 7× throughput, "negligible accuracy loss" **without fine-tuning**. | The most promising training-free option. Still recomputes per-block boundaries + needs RoPE re-rotation; unvalidated on a diffusion encoder + MoE + mixed RoPE. A research arm, not a Phase-1. |
| **KVLink** ([2502.16002](https://arxiv.org/html/2502.16002v2)) | Trainable connector tokens between independently-encoded segments. | Training again. Out of scope. |
| **vLLM APC** ([docs](https://docs.vllm.ai/en/stable/design/prefix_caching/)) / **SGLang RadixAttention** ([medium](https://medium.com/byte-sized-ai/prefix-caching-sglang-vs-vllm-token-level-radix-tree-vs-block-level-hashing-b99ece9977a1)) | Exact **prefix** reuse: hash each block as `hash(block_tokens, all_prior_tokens)` (vLLM) or a token radix-trie (SGLang). Automatic, **zero accuracy loss**. | This is **prefix** caching, not position-independent. It is EXACTLY what Seon needs (§3) because Seon fixes block order. We adopt the algorithm; we do not need vLLM's serving (ruled out for control). |

**Feasibility verdict for DiffusionGemma:**

- **Full-prefix / radix block caching: FEASIBLE, exact, low-risk.** Causal encoder makes it
  correct; `crop()` + `past_key_values` make it buildable; Seon's fixed block order makes
  it near-optimal. **Do this.**
- **Position-independent per-block (PromptCache/Block-Attention/EPIC): DEFER.** Not needed
  (fixed order ⇒ no varying-position problem) and not clean (MoE + mixed/partial RoPE +
  untrained block-diagonal regime). If full-prefix ever proves insufficient, EPIC/LegoLink
  (training-free, recompute-boundary) is the one to prototype — behind a gym A/B that proves
  the accuracy hold.

---

## 3. The content-addressed per-BLOCK KV cache, keyed to Seon's structure

### 3.1 Seon's context is already the right shape

The prompt is a priority-sorted concatenation of `:seon.agent.ctx/block` maps
(`agent/ctx.cljs`: `context-root` → `agent-blocks` sorts by `:seon.agent.ctx/priority`
→ `render-context-ai` joins). `default-seed-blocks` (`:1599+`) already orders them
static→volatile and DOCUMENTS the cache contract:

```
:soul (5) :agents (8) :shared-instructions (10) :skills-catalog (12)
:namespaces (20)            ← end of the stable, cacheable prefix
:live-tile (35) :warnings (40) :open-todos … :inventory … :transcript   ← volatile tail
```

The code even notes a loaded skill body "rides the volatile band so load/unload never
busts this slot" and that a file save "busts only this block (and below)." **The block IS
the cacheable unit.** We give each block a stable token span and a chain hash.

### 3.2 The design (one mechanism: a block-chain radix cache over encoder KV)

1. **Tokenize per block, in priority order.** Rendering already produces each block's
   byte-stable `:seon.render/ai` string. Tokenize each block independently to get its
   token-id list and a running token offset `[start,end)` per block (the same offset-map
   discipline the worker already uses for canvas spans).
2. **Chain-hash each block boundary** (vLLM's scheme): `h_0 = hash(block_0_token_ids)`;
   `h_i = hash(h_{i-1}, block_i_token_ids)`. `h_i` identifies "the exact token prefix
   through block i." Store it on the block as a derived value (NOT persisted — derive at
   render, per the reactive-context rule).
3. **Cache store = `{chain_hash → (encoder Cache cropped to that boundary, token_len)}`**,
   held in the **co-located worker process** (a small LRU; the cache tensors live on GPU
   for the full-attn layers, optionally offloaded for sliding). A radix/trie keyed by the
   block sequence gives SGLang-style automatic sharing: `soul→agents→shared-instructions`
   is a common ancestor; `…→skills-catalog[A]` and `…→skills-catalog[B]` are two branches
   that both reuse the ancestor and each cache their own subtree — **the skill-A/B win
   falls out for free.**
4. **On a new render:** walk blocks top→down, look up `h_i`; the **longest matching chain
   hash** = the reusable prefix boundary `b` (token length L). Take that cached `Cache`,
   `crop(L)` if needed, and call `generate(input_ids=full_prompt_ids,
   past_key_values=cached_cache_with_L_positions, ...)` so the encoder prefill encodes
   **only tokens [L:]** (the divergent suffix), appending to the reused prefix exactly as
   the block-AR loop already appends per canvas (§1.2). Then, on the way out, write back the
   new boundaries' caches for next time.
5. **Cache invalidation is automatic and derived.** A block edit changes its token ids →
   changes `h_i` and every `h_{>i}` → those boundaries simply miss and re-encode; the stable
   head still hits. No "mark dirty," no stored invalidation — same self-healing property the
   reactive-context doctrine wants. This matches the existing comment ("a save busts only this
   block and below").

### 3.2b Don't reinvent — the chain-hash + extra-keys is verbatim vLLM (vendored)

The Phase-2 store is not a new algorithm; it is vLLM's automatic-prefix-cache, which is
vendored at `reference-code/vllm/`. Its block hash (`vllm/v1/core/kv_cache_utils.py:577-603`)
is exactly the chain hash of §3.2:

```python
hash_function((parent_block_hash, curr_block_token_ids_tuple, extra_keys))   # :603
```

with `NONE_HASH` seeding the root (`:95-114`). Two features to lift straight from there:

- **`extra_keys` / `cache_salt`** (`:539-574`, `:560-561`; design doc
  `vllm/docs/design/prefix_caching.md:87`): a salt folded into the FIRST block's hash so
  caches are only shared within a chosen scope. **Use it to scope by `:seon.agent/id` (or
  cluster)** so one agent's cached prefix can't be reused by another unless we explicitly
  want cross-agent sharing — clean fit with Seon's per-agent context and the cross-agent
  publish gate.
- **The chain-hash radix store + LRU eviction** (`hash_request_tokens`,
  `vllm/v1/core/kv_cache_manager.py`) — we copy the bookkeeping, not the paged-GPU-block
  allocator (we hold whole `DynamicCache`s, not vLLM's 16-token paged blocks).

Further serving features worth a later look, all vendored (`reference-code/vllm/`), NONE
needed for Phase 1 — flagged so we adopt rather than invent if/when memory pressure shows up:

- **KV offload connectors** (`vllm/distributed/kv_transfer/kv_connector/v1/`:
  `simple_cpu_offload_connector.py`, `offloading_connector.py`, `lmcache_connector.py`) —
  spill cold prefix caches to CPU/LMCache instead of evicting. Matches our LRU-cap +
  offload note (§1.4); the full-attn-only prefix is small enough that CPU offload is cheap.
- **Chunked prefill** — encode a long stable prefix in fixed chunks (bounds the prefill
  step's peak memory; the cache append is already chunk-friendly per §1.2).

These are the "proper serving" levers; the discipline is to pull them from the vendored
vLLM/SGLang source (block hash, salt, offload connector), not hand-roll equivalents.

### 3.3 Block-ordering tweak that maximizes hits (the skill case)

Today a *loaded* skill body sits in the volatile band so load/unload doesn't bust the
catalog slot. For caching we want the opposite when a skill is loaded **for the duration of
a task**: put the loaded skill body (and the ~2900-tok required-API block) in the **stable
prefix**, above `:namespaces`, because they are byte-stable across that task's N turns. Then
the ~2400+2900 tok of skill+API KV is encoded once per task and reused every turn. The radix
store means switching skills between tasks just selects a different branch — no re-encode of
the shared ancestor. (This is a block-priority change in `default-seed-blocks` + the gym to
confirm it doesn't perturb behavior, not a mechanism change.)

---

## 4. Expected win (regimes + the formula; numbers MUST be measured)

Let prompt = stable-prefix fraction `P` (tokens) + volatile-tail `1−P`. Let encoder
prefill be fraction `X` of total `generate()` wall time at our sizes; the denoise loop is
the remaining `1−X` and is UNAFFECTED (it only cross-attends the cache). Full-prefix caching
replaces the prefill over `P` with a cache slice (≈free) and re-encodes only `1−P`:

- **Per-turn latency saved ≈ X · P** of total `generate()` time (cache-hit on the whole
  stable head). For an agent prompt that is ~80–90% repeated context, `P≈0.85`.
- **Skill-A/B fan-out (N tasks, 1 skill):** the skill's prefill is paid **once**, amortized
  → ~`X · (skill_tokens / prompt_tokens) · (N−1)/N` saved across the batch. With a 2400-tok
  skill on a ~12k prompt over N=20 tasks, that's the skill's encode cost driven to ~0.
- **Eval-renoise resume loop (the big one):** each resume re-encodes the full prompt today
  (worker-build §4). With the committed-prefix cache fed back, K outer iterations pay prefill
  ONCE instead of K times → ≈`X · (K−1)/K` of the loop's prefill removed. This compounds with
  the canvas-AR cache append already in `generate`.

`X` is the unknown and **decides everything.** For a long single generation (many canvases ×
48 steps) prefill is a small slice → modest win. For SHORT generations (spec-slot, infill —
one canvas) and for resume loops, prefill dominates → large win. **Measure `X` first** (§5
Phase 0); do not quote a tok/s number until the GPU says so.

Memory cost: only full-attention layers store the long prefix (§1.4); for a ~10k stable
prefix that is a bounded, offload-able tensor set, easily LRU-capped.

---

## 5. Buildable plan (ordered) + the riskiest assumption

**Precondition (architectural):** in-process **co-location** of Seon + the worker (the
`Cache` can't cross JSON — worker-build §4; `custom-image-and-seon-colocation`). Until Seon
calls `generate` in the same process and can hold the `Cache`, none of this is reusable.
This is a gate, not a step.

- **Phase 0 — MEASURE `X` (riskiest assumption first).** On the live worker at our REAL
  sizes (≈10k stable + ≈2k volatile), time: (a) encoder prefill alone vs (b) full
  `generate()`, for a short (1-canvas) and a long generation, and for a resume call. **The
  riskiest assumption is "prefill is a meaningful fraction of latency at our sizes."** If
  `X < ~5%`, stop — caching isn't worth the complexity. If `X` is 20–60% (expected for short
  gens + resume), proceed. Cheap: add a `time.perf_counter()` around the prefill encode in
  the worker; no new mechanism.
- **Phase 1 — full-prefix cache, single stable head, in-process.** Hold ONE per-agent
  encoder `Cache` for the stable prefix (soul→namespaces). Each turn: tokenize blocks,
  chain-hash, if the stable head matches the held cache `crop`/reuse it and prefill only the
  volatile tail; else re-encode and replace. Validate with `crop()` round-trips against a
  full re-encode (assert identical `sequences` for a fixed seed). Exact, lowest-risk slice of
  the win.
- **Phase 1.5 — wire the eval-renoise resume loop** to feed the committed-prefix `Cache` back
  across K-step `generate()` calls (closes the worker-build §4 gap). This is where the buzzsaw
  feels it.
- **Phase 2 — the radix/trie store (multi-branch).** Generalize Phase 1's single head to a
  `{chain_hash → cropped Cache}` LRU radix so skill-A and skill-B (and multiple agents) share
  the common ancestor and branch cheaply. Add the block-priority tweak (§3.3) to lift the
  loaded skill body + required-API block into the stable prefix. Every change is a gym A/B
  (scenario × git-sha) proving identical outputs + lower latency.
- **Phase 3 — DEFER / research only: position-independent per-block (EPIC/LegoLink).** Only if
  Phases 1–2 leave meaningful repeated re-encode AND a section genuinely needs to live at a
  varying position. Prototype EPIC's recompute-boundary + per-layer-type RoPE re-rotation,
  gate hard on a gym accuracy A/B. Expect this to be unnecessary given fixed block order.

**Riskiest assumptions, ranked:** (1) prefill is a worthwhile latency fraction at our sizes
(Phase 0 kills or greenlights everything); (2) `DynamicCache.crop()` + re-fed
`past_key_values` reproduces a from-scratch prefill bit-for-bit on the *encoder* path (the
block-AR loop already appends incrementally, so this is very likely — but assert it); (3)
co-location lands (without it, KV reuse is impossible over JSON). Per-block PIC accuracy is a
Phase-3 risk we are deliberately not taking yet.

---

## 6. Worker integration contract — BUILT (code), awaits GPU measurement

> **STATUS (2026-06-29): the worker half is WRITTEN** — `kv_reuse` path in
> `tmp/flash-diffgemma/gpu_worker.py` (`_kv_reuse_generate`) over `KVPrefixCache` +
> `longest_prefix_hit` in `diffgemma_common.py`. `py_compile`-clean; the walk + LRU
> are unit-proven off-GPU (`test_kv_walk.py`, 13 green, no torch). Two corrections
> to the original wording below, both grounded in transformers v5.11.0 source —
> apply them, the prose under them is the intent:
>
> 1. **Feed generate the SUFFIX, not the full prompt.** `generate(input_ids,
>    past_key_values=pkv)` adds the cache length to `cur_len`
>    (`generation_diffusion_gemma.py:635-636`) and the prefill encoder encodes the
>    PASSED `input_ids` (`_prepare_encoder_inputs:941`). So with a length-`L` cache
>    we pass `full_prompt_ids[:, L:]` — `encoder_position_ids = arange(L, P)`,
>    `attention_mask = ones(1,P)` covers prefix+suffix, the encoder prefills ONLY
>    `[L:]` and APPENDS to the reused prefix (the per-canvas append at `:734,:784`).
>    Passing the full prompt would double-count positions.
> 2. **Force `cache_implementation="dynamic_full"`.** The default encoder cache is a
>    HYBRID `DynamicCache(config=...)` (`:925` → `cache_utils.py:1437`): sliding +
>    full layers, sliding layers holding only the last `sliding_window=512`
>    positions in a rolling buffer. `crop(L)`/`get_seq_length()` on such a layer are
>    ill-defined for a prefix boundary. A uniform full cache (`:923-925`) makes both
>    bit-exact for every layer. Cost: sliding layers store full length (masking
>    still bounds what they ATTEND to); the §1.4 "store full-attention layers only"
>    memory optimization is deferred (it needs the sliding layers to re-encode,
>    which the single-forward `generate` can't do per-layer — an EPIC-style refinement).
> 3. **The worker OWNS tokenization + offsets.** The pod's `chars/4` estimate cannot
>    produce real token offsets, so the worker tokenizes the assembled prompt and
>    derives each block's token END offset by cumulative tokenization
>    (`_block_token_offsets`), snapping a boundary to a token boundary (only ever
>    SHORTENS the reused prefix — conservative, still bit-exact). Seon sends the
>    block TEXTS + `::chain-hashes`; the worker treats the hashes as OPAQUE keys
>    (never recomputes the SHA), so a match is exact string equality.
>
> **The riskiest assumption (§5 #2) is still GPU-only:** that `crop()` + a re-fed
> `past_key_values` reproduces a from-scratch prefill BIT-FOR-BIT on the encoder. The
> owner asserts it (runbook): request-2 `sequences == ` request-1 full re-encode for a
> fixed seed, then measures the prefill-time drop.

### 6a. CPU PROXY — the GENERAL mechanism is PROVEN on CPU ($0, no A100, 2026-06-29)

The general transformers-API half of that assumption is testable on ANY tiny CPU
causal LM — and now is. `tmp/flash-diffgemma/test_kv_reuse_cpu_proxy.py` (gitignored,
`tmp/`; gpt2 fp32 on CPU, `torch 2.12.1`, `transformers 5.12.1`) builds a 2-segment
input `[A|B]` and runs the EXACT moves `_kv_reuse_generate` makes — `crop`,
`past_key_values=` threading, SUFFIX-not-full `input_ids[:, L:]` — importing the
worker's OWN `longest_prefix_hit` / `KVPrefixCache` (torch-free) so it tests the
SHIPPED walk against a real cropped `DynamicCache`, not a reimplementation. Measured,
reproducible (atol=1e-5, rtol=1e-4):

| Claim | Result | max abs diff |
|---|---|---|
| **(1) logits parity** — `logits_full[:, LA:]` vs suffix-fed reuse | `allclose=True` | **1.221e-04** |
| **(2) crop → reuse** vs a full `[A\|B]` encode then crop-to-`LA` | `allclose=True`, **bit-exact** | **0.000e+00** |
| (2) `crop()` returns `None` (in-place), slices seq_len 32→17, no-op when `max_len ≥ seq_len` | confirmed | — |
| **(3) greedy token-id parity** (teacher-forced argmax over the B positions) | **15/15 identical** | 0 |
| **(4) worker walk** — `longest_prefix_hit` 1 / 0 (edited block) / −1 (miss); real cropped-cache reuse vs full | `True`, **bit-exact** | **0.000e+00** |

**Verdict: the general crop + `past_key_values` + suffix-forward mechanism is SOUND**
— on a uniform full-attention cache (the easy case) it reproduces a from-scratch
prefill. Note (2): cropping a full `[A|B]` cache to `LA` and re-feeding it is
**0.000e+00** (literally the same K/V tensors the full forward computed); the 1.221e-04
on path (1) is only the fp32 reassociation of encoding `A` SEPARATELY vs as a prefix —
still well inside tolerance. If reuse had diverged even here the worker would be
fundamentally broken; it does not.

**What this DE-RISKS:** the transformers-API correctness #5 leans on (the cites in §6
1–3) is no longer "the owner's GPU assertion" — it's proven. **What STILL needs the
A100:** only the DiffusionGemma-specific **HYBRID (sliding+full) cache** bit-exactness
— i.e. that the `cache_implementation="dynamic_full"` mitigation (§6 #2) actually
yields a uniform full cache whose `crop`/`get_seq_length` are bit-exact per layer on
the real model. gpt2 is uniform-full by construction, so it cannot exercise the
sliding-window rolling-buffer hazard; that one remains the owner's two-request seed
assertion (runbook). (ENV note: gpt2 `model.generate()` / long incremental-forward
loops SIGBUS-crash on this macOS torch-CPU build — unrelated to KV correctness; the
test runs `torch.set_num_threads(1)` and uses single stable forwards + teacher-forced
argmax instead.)

The keying half is built (`seon.agent.ctx/block-chain-keys`). It is a PURE fn of
(`::blocks`, `:seon.agent/id`): given the turn's `:seon.render/text`-bearing context
blocks in prompt order (static→volatile, the `default-seed-blocks` ordering) it returns
`::chain-hashes` — one hex-sha256 per block, where hash `i` fingerprints the byte-exact
block prefix `0..i`, salted at the root by the agent id. Identical block prefixes (same
agent) yield identical hashes; the first changed block breaks the chain and every hash
from there on diverges — vLLM's longest-prefix-match, mirrored from
`kv_cache_utils.py:577-603,703-728`. The hashes are derived at render, never persisted
(reactive-context rule): a block edit just changes its token ids → its hash → a miss.

**What the worker must do** once Seon co-locates and calls `generate` in-process
(§5 precondition):

1. Seon sends, alongside the assembled prompt, the `::chain-hashes` vector AND each
   block's token offset `[start,end)` in the prompt (the worker already tracks canvas
   spans the same way; the Seon block text is the byte-stable contribution, the worker
   tokenizes it deterministically so block boundaries land on token boundaries).
2. Worker holds an LRU `{chain_hash → (encoder DynamicCache cropped to that boundary,
   token_len)}` (full-attention layers only — §1.4). On a new turn it walks the hashes
   top→down and takes the LONGEST hash that hits = reusable prefix boundary `b`
   (token length `L`).
3. Worker `crop(L)`s that cached `Cache` and calls `generate(input_ids=full_prompt_ids,
   past_key_values=cached_cache, ...)` so the encoder prefills ONLY tokens `[L:]` (the
   divergent suffix), appending to the reused prefix exactly as the block-AR loop already
   appends per canvas (§1.2). Then it writes back the new boundaries' cropped caches under
   their hashes for next time.
4. Invalidation is automatic: a missed hash simply re-encodes from the last hit; no
   "mark dirty", no stored invalidation state. The salt means agent A's cached prefix is
   never served to agent B unless we deliberately key cross-agent.

The keys are deterministic across pod/worker restarts (the chain root is a fixed
constant, NOT `os.urandom` — unlike vLLM's per-process `NONE_HASH`), so a warm worker's
cache survives a Seon restart and vice-versa. Nothing else in this doc changes; the
worker side is a pure consumer of `::chain-hashes`.

---

## Source index

- DiffusionGemma encoder causal mask: `modeling_diffusion_gemma.py:281,927-933`; decoder
  bidirectional + read-only cross-attn: `:369-383,1294-1340`. Incremental encoder append:
  `generation_diffusion_gemma.py:714-735,941,1121`. `past_key_values` in/out:
  `generation_…:266,576,635-636,826`. RoPE: `modeling_…:204,329,431`; per-layer-type RoPE
  params + sliding/full layer pattern: `configuration_…:99-135,118-128`; `sliding_window=512`,
  `canvas_length=256`: `:102,196`. Cache slice primitives: `cache_utils.py:163,177,1337,1342`;
  hybrid sliding-cache memory note: `:1398-1399`.
- Seon ctx blocks: `src/seon/agent/ctx.cljs` (`default-seed-blocks:1599+`, `context-root`,
  the static→volatile ordering + "cacheable prefix" comments); render assembly:
  `src/seon/render.cljs` (priority sort/join).
- Worker KV-reuse gap: `research/eval-renoise-worker-build-2026-06-28.md` §nuance-4. Source
  grounding: `research/transformers-diffusion-source-grounding-2026-06-28.md`. Co-location:
  `research/custom-image-and-seon-colocation-2026-06-28.md`.
- Vendored serving source (adopt, don't reinvent): vLLM block hash + chain hash + salt/extra-keys
  `reference-code/vllm/vllm/v1/core/kv_cache_utils.py:95-114,539-603`; radix/LRU
  `vllm/v1/core/kv_cache_manager.py`; KV offload connectors
  `vllm/distributed/kv_transfer/kv_connector/v1/{simple_cpu_offload,offloading,lmcache}_connector.py`;
  prefix-cache design `vllm/docs/design/prefix_caching.md`.
- Web SOTA: PromptCache [2311.04934](https://arxiv.org/abs/2311.04934); Block-Attention
  [2409.15355](https://arxiv.org/html/2409.15355); EPIC/LegoLink
  [2410.15332](https://arxiv.org/abs/2410.15332); KVLink
  [2502.16002](https://arxiv.org/html/2502.16002v2); vLLM automatic prefix caching
  [docs](https://docs.vllm.ai/en/stable/design/prefix_caching/); SGLang RadixAttention vs
  vLLM block-hash [byte-sized-ai](https://medium.com/byte-sized-ai/prefix-caching-sglang-vs-vllm-token-level-radix-tree-vs-block-level-hashing-b99ece9977a1).
</content>
</invoke>
