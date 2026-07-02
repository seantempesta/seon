---
type: research
status: active
tags: [research, agent, flow, schema]
---

# Unified control-signal oracle — BUILT + offline-proven (2026-06-29)

## TL;DR

- The three diffusion control signals — **parse**, **eval**, **retrieve** —
  are now unified behind ONE dispatcher the worker calls once per denoise
  checkpoint: `seon.diffusion.oracle/refine` (`src/seon/diffusion/oracle.cljs`).
- One `refine` call returns ONE combined control set:
  `{::clamps ::renoise-spans ::injections ::legs}` — the spans to HOLD, the
  spans to RE-NOISE, the hallucinated-symbol corrections, and which legs ran.
- The three sets are a clean PARTITION: a clamp is a good form whose span
  overlaps NEITHER an injection (carries a hallucination → steer it) NOR a
  renoise span (broken syntax, or an eval-bad form). No region is double-covered.
- **Offline-proven** (NO GPU, NO embeddings): a single canvas with BOTH a
  syntax error AND a `transct!`-style hallucination → the combined set carries
  (a) the renoise span for the broken form, (b) the retrieval injection
  (`db/transct!` → `db/transact!` + span + spec_text), (c) the clamp spans for
  the clean forms only. Suite green: **832 tests / 3804 assertions, 0 failures**
  (`bin/test-cljs`; was 814/3708).
- **End-to-end AWAITS GPU.** The mid-denoise integration (worker calls `refine`
  once per checkpoint K, applies clamps + injections, re-noises the
  renoise_spans, resumes) is built Seon-side; it has not yet run against the
  live diffusion worker.

## The unified contract

`seon.diffusion.oracle/refine` — `{:=> [:cat ::checkpoint] ::control-set}`:

```clojure
;; in  — one mid-denoise checkpoint
{::canvas-text   "…"        ; the worker's current canvas string
 ::offset-map    [...]      ; OPTIONAL — the worker's token→char map (it maps our
                            ;   char spans back to token positions; Seon emits
                            ;   char spans directly, so it is carried, not used)
 ::aliases       {...}      ; OPTIONAL alias→ns override (else read from the ns form)
 ::k             5          ; OPTIONAL retrieval candidate cap
 ::eval-verdicts [...]      ; OPTIONAL span-keyed eval results (the node tier)
 ::db            <db>}      ; OPTIONAL graph db value (default seon.db/*conn*)

;; out — ONE combined control set
{::clamps        [{::span [s e] ::source "…"}]              ; HOLD — do NOT re-noise
 ::renoise-spans [{::span [s e] ::error-kind :eof ::source "…"}]  ; RE-NOISE
 ::injections    [<seon.diffusion.retrieval/injection>]     ; clamp-toward-real-API
 ::legs          [:parse :retrieve]}                        ; (+ :eval when folded)
```

`to-wire` flattens it to the worker's `{op:"refine", legs, clamps, renoise_spans,
injections}` JS object — each `span` a `[start end]` array; each injection reuses
`seon.diffusion.retrieval/to-wire` so its `{op:"clamp", span, replacement,
spec_text}` shape is byte-identical to the standalone retrieval emit.

## How each leg maps in

- **PARSE** (`seon.repl.internal/parse-forms`, no-fence basis) — yields BOTH the
  GOOD form spans (clamp candidates) and the BROKEN-syntax spans (renoise). To
  carry good-form spans on the canvas basis, `parse-forms` now emits `:span
  [start end]` on every `:kind :form` entry — the same authoritative basis the
  `:read` entries already carry (it is the loop's `offset` + the token `:end`,
  not a re-derivation). This is the load-bearing change that lets clamps and
  renoise spans share one source of truth.
- **RETRIEVE** (`seon.diffusion.retrieval/retrieve-for-canvas`) — yields the
  hallucinated-symbol injections (`{span, replacement, spec_text}`). Reads
  `:seon.fn/sym` from the program graph; PURE over a db value.
- **EVAL** (`seon.worker-eval`, a SEPARATE node self-host bundle) — runs
  out-of-process, so its verdicts arrive as DATA via `::eval-verdicts`
  (span-keyed). The fold: a bad verdict (`:compile` undeclared-var/def-vs-defn/
  arity, `:throw`, `:interrupt`) becomes a renoise span UNLESS retrieval already
  named the real API for that span (the injection supersedes the re-noise). When
  no verdicts are supplied, `refine` runs PARSE + RETRIEVE only and says so in
  `::legs`.

## What one bb `refine` covers vs what needs the node/pod tiers

`bin/oracle-server` (the persistent babashka line-server) gains `op:"refine"`.
bb's classpath is the pure `.cljc` parser ONLY — no program graph, no shadow,
no node — so one bb `refine` call covers EXACTLY the **parse tier**:

- `clamps` — good, syntactically-complete form spans to HOLD;
- `renoise_spans` — broken-syntax spans to RE-NOISE;
- `injections: []`, `legs: ["parse"]`, plus a `note` stating the boundary.

bb CANNOT produce injections (retrieve reads `:seon.fn/sym` — not on bb's
classpath; that is the CLJS pod oracle's job) nor fold eval verdicts (the node
self-host bundle). Illustration on the crux canvas: bb clamps the
`(db/transct! …)` form (it has no graph to know it is a hallucination); the CLJS
`oracle/refine` reclassifies that exact span from a clamp into an injection. The
full three-leg `refine` runs in the pod (or is assembled by the Python `Oracle`
shim from the bb parse call + the node eval call + a pod/graph retrieve call).

## Offline proof (the crux)

`test/seon/diffusion/oracle_test.cljs` — seeds a `:memory` program graph with a
real `seon.db/transact!`, feeds:

```
(ns my.work (:require [seon.db :as db]))   ; clean → clamp
(defn good [x] (inc x))                     ; clean → clamp
(db/transct! {:seon.db/tx-data []})         ; hallucination → injection (NOT a clamp)
(defn broken [x                             ; unbalanced → renoise (eof)
```

and one `refine` call asserts the combined set: (a) the eof renoise span for the
broken form, (b) the `db/transct!` → `db/transact!` injection with span +
spec_text, (c) clamp spans for the ns + good defn ONLY — the hallucination form
and the broken form are excluded — plus the disjointness of all three span sets
and the `to-wire` object. Two more tests prove the eval fold (a clean form
retrieval can't fix is a clamp until a `:compile` verdict demotes it to renoise)
and that an injection supersedes an eval-renoise on the same span.

## Worker mid-denoise integration — injection-apply BUILT (awaits GPU)

At each denoise checkpoint K the worker calls `refine` ONCE with the current
`canvas_text` (+ its `offset_map`). It then APPLIES the combined set in one pass:
**clamp** the `clamps` spans (freeze those token positions — the good forms do
not get re-noised), **steer** each `injection` (force its span toward
`replacement` and append `spec_text` to the encoder KV so the decoder
cross-attends the real signature), and **re-noise** the `renoise_spans` (the
broken-syntax forms, plus any eval-bad form) back to a higher noise level. Then
it resumes denoising from K+1. Seon drives, the worker stays stateless, the pod
stays loopback-only.

**The worker `::injections` half is now BUILT** (code, `py_compile`-clean,
pure-unit-proven off-GPU; gitignored `tmp/flash-diffgemma/`):

- `diffgemma_common.injection_clamps(injections, offset_map, encode)` — PURE: maps
  each injection's CHAR `span` → canvas TOKEN positions (`span_to_positions` over
  the worker's `offset_map`), tokenizes `replacement`, and zips positions↔tokens
  into a `{position → token_id}` clamp set (steering the span via the EXISTING
  `ClampLogitsProcessor`), plus the de-duplicated `spec_text`s to extend the
  encoder KV with. Span longer than the replacement → trailing positions FREE;
  replacement longer than the span → overflow tokens dropped (fixed-width canvas
  slot) — honest, reported in `detail`.
- `diffgemma_common.choose_kv_route(...)` — the **W1/W2/W3** route selector:
  **W1** incremental (append `spec_text` to a HELD extensible encoder DynamicCache
  via a suffix-forward — `generate(past_key_values=held, input_ids=spec_ids)`,
  grounded `generation_diffusion_gemma.py:635-636,720-734,941`; the append is
  `DynamicLayer.update` `torch.cat(dim=-2)`, `cache_utils.py:126-150`); **W2**
  re-prefill (`input_ids = prompt ++ spec_text`) — **the realistic Phase-1
  default** (the JSON worker holds no encoder cache across calls, so it lands
  here); **W3** clamp-only (no `spec_text` in the KV — the guaranteed fallback).
  The decoder cross-attends ALL non-pad encoder positions
  (`modeling_diffusion_gemma.py:1294-1340`), so spec_text appended to the encoder
  KV is visible.
- `gpu_worker.py` — `mode="inject"` (standalone apply + the decisive
  `injections_held` assertion) and `injections` folded into `mode="resume_renoise"`
  (clamp-good + steer-injections + re-noise-bad in one pass). `_cache_extensible`
  capability-detects a uniform-full DynamicCache (every layer non-sliding —
  `DynamicSlidingWindowLayer.is_sliding`, `cache_utils.py:196`); `_held_inject_cache`
  is the co-location seam (default None → W2; composes with `kv_reuse` by taking the
  kv_reuse-produced prefix cache as the W1 base via `inject_kv_chain_hash`).
- Worker CODE changed → `worker_sha` shifts (to ≈`63c09bebadad`) → `verify_fresh`
  flags it (correct). Default (no `injections`) = the stock paths unchanged.

**Composes with `kv_reuse`:** the two are siblings that both produce the encoder
cache — `kv_reuse` (mode `generate`) reuses a STATIC-PREFIX cache to skip prefill;
`inject` EXTENDS the cache with `spec_text`. When co-located they compose (the
kv_reuse prefix cache becomes the W1 base); the JSON path holds no cache, so both
default safely (kv_reuse → cold encode; inject → W2 re-prefill).

The pure span→position→clamp + route selection is unit-proven
(`test_inject_apply.py`, 13 units, no torch). **The remaining end-to-end step is a
live GPU drive** (a seeded hallucination + an injection → assert the canvas commits
`replacement`, not the hallucinated symbol) — see [[owner-gpu-runbook]] step 3.

## The control LOOP — BUILT + offline-proven (2026-06-29)

`refine` is the per-checkpoint CALL; the orchestration AROUND it — refine →
apply → re-refine → converge — is the one thing every leg's unit tests did NOT
cover. It is now built + offline-proven in `src/seon/diffusion/loop.cljs`
(`seon.diffusion.loop`), NO GPU.

- **`checkpoint-policy`** (PURE, specced) — given one `::oracle/control-set`, the
  iteration index, a K-budget, and the previous control set, returns
  CONTINUE / CONVERGED / GIVE-UP:
  - **CONVERGED** — no `::oracle/renoise-spans` AND no `::oracle/injections`.
  - **GIVE-UP** — `iteration ≥ k-budget` (the HARD termination backstop) OR no
    progress (the error signature — renoise spans + injection (span,replacement)
    pairs — is identical to the previous iteration's; the worker can't move the
    canvas).
  - **CONTINUE** — errors remain, budget unspent, last step changed something.
- **`dry-run`** — the CPU loop. From a degraded canvas it `refine`s, consults
  the policy, and on CONTINUE MOCKS the worker DETERMINISTICALLY via
  `apply-control-set`: a CLAMP span is held verbatim, an INJECTION span is
  replaced by its `::retrieval/replacement`, a RENOISE span is replaced by the
  `::fills` fixture's canned correction for that broken source (a span with NO
  fill is left unchanged — genuinely unfixable). Regions covered by neither edit
  (clamps + gaps) are copied verbatim, so the next canvas differs ONLY at
  injection + renoise spans; the next `refine` recomputes spans in the fresh char
  basis. Returns the per-iteration `::trace` + the terminal `::verdict`/`::reason`.

### How the real worker substitutes for the mock APPLY

This harness IS the orchestration; the GPU worker only changes HOW spans get
re-filled. The mock APPLY's three transforms are exactly the worker's
`good_clamp_for_renoise` + clamp/infill surface (`diffgemma_common.py`): clamp
positions HELD, injection positions forced toward `replacement` (encoder-KV
`spec_text` appended), renoise positions left OUT of the clamp set so the entropy
bound re-decides them — the actual denoise step replacing the fixture fill. The
control flow, the convergence policy, and the span coordinate system
(`canvas_text`/`offset_map` basis, per closed-loop-span-alignment) are identical;
only the span→text function differs (a fixture lookup here, a denoise there).

### The three offline proofs (`test/seon/diffusion/loop_test.cljs`)

- **(a) CONVERGENCE** — a canvas with BOTH a hallucinated call (`db/transct!`)
  AND a broken trailing form, whose fixture fill reveals a SECOND hallucination
  (`db/quer`), drives the loop CONVERGED in 3 iterations; the trace SHRINKS
  renoise `1→0→0` and injections `1→1→0`.
- **(b) DETECTION** — a clean canvas converges at iteration 0 and STOPS (no
  apply, canvas returned unchanged).
- **(c) GIVE-UP / TERMINATION** — an unfixable canvas (no fill) terminates with
  GIVE-UP `:no-progress`, never exceeding the K-budget; a direct policy check
  proves the K-budget backstop (`:budget-exhausted`) independently. **No infinite
  loop.**

Suite green: **837 tests / 3836 assertions, 0 failures** (`bin/test-cljs`; was
832/3804). **End-to-end on the live diffusion worker AWAITS deploy** — the worker
swaps its denoise for the mock APPLY; nothing else in the loop changes.

### Bug found + fixed surfacing this harness

`seon.diffusion.retrieval/canvas-aliases` extracted the `(:require [ns :as a])`
alias via `(.indexOf (to-array spec) :as)` — JS `===`, which NEVER matches a CLJS
keyword VALUE, so it always returned `{}`. Every canvas-derived alias was lost,
so a RESOLVABLE qualified symbol (`db/query`) was flagged unresolved and
"corrected" to itself. Masked because no prior test exercised canvas-derived
alias resolution of a real qualified symbol (the retrieval/oracle tests only use
typos, which fail regardless, or explicit `::aliases`). Fixed with a CLJS-safe
`(some (fn [[i x]] (when (= :as x) i)) (map-indexed vector spec))` scan.

## Entry points

- `src/seon/diffusion/loop.cljs` — the policy + the dry-run loop + the mock APPLY.
- `src/seon/diffusion/oracle.cljs` — the dispatcher + `to-wire`.
- `src/seon/diffusion/retrieval.cljs` — the retrieve leg (injections; alias fix).
- `src/seon/worker_eval.cljs` — the eval tier (separate node bundle).
- `src/seon/repl/internal.cljc` — `parse-forms` (now emits `:span` on `:form`).
- `bin/oracle-server` — the bb parse-tier server (`op:"refine"`).
- `test/seon/diffusion/loop_test.cljs` — the LOOP's three offline proofs.
- `test/seon/diffusion/oracle_test.cljs` — the dispatcher's offline proof.
