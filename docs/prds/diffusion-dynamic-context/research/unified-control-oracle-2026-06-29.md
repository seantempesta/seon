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

## Worker mid-denoise integration (AWAITS GPU)

At each denoise checkpoint K the worker calls `refine` ONCE with the current
`canvas_text` (+ its `offset_map`). It then APPLIES the combined set in one pass:
**clamp** the `clamps` spans (freeze those token positions — the good forms do
not get re-noised), **steer** each `injection` (force its span toward
`replacement` and append `spec_text` to the encoder KV so the decoder
cross-attends the real signature), and **re-noise** the `renoise_spans` (the
broken-syntax forms, plus any eval-bad form) back to a higher noise level. Then
it resumes denoising from K+1. Seon drives, the worker stays stateless, the pod
stays loopback-only. The whole round-trip is built and offline-proven; running it
against the live diffusion worker is the remaining end-to-end step.

## Entry points

- `src/seon/diffusion/oracle.cljs` — the dispatcher + `to-wire`.
- `src/seon/diffusion/retrieval.cljs` — the retrieve leg (injections).
- `src/seon/worker_eval.cljs` — the eval tier (separate node bundle).
- `src/seon/repl/internal.cljc` — `parse-forms` (now emits `:span` on `:form`).
- `bin/oracle-server` — the bb parse-tier server (`op:"refine"`).
- `test/seon/diffusion/oracle_test.cljs` — the offline proof.
