---
type: reference
status: active
tags: [reference, agent]
---

# SCI interrupt spike (runnable)

Self-contained proof that **SCI + a wall-clock `:interrupt-fn` aborts a synchronous infinite
loop in-process on Node/CLJS** — the freeze class that can lock up the Seon pod. Full findings,
verdict, and the "what to look for" guide are in the validation doc:
[[sci-interrupt-validation-2026-06-21]] (`docs/prds/agent-runtime/sci-interrupt-validation-2026-06-21.md`).

## Run it

From this directory:

```bash
clojure -M -m shadow.cljs.devtools.cli compile spike
node out/spike.js
```

`compile` produces a `:none` build — the same optimization level the pod's dev/watch build uses.
To also check `:simple` (release without `:advanced`'s DCE/renaming), see the note in
`shadow-cljs.edn`.

## What a passing run shows

- TEST 1 — `(loop [] (recur))` is aborted at ~the budget (~250ms); the falsification probe
  confirms the loop never yielded the event loop; the script CONTINUES (no hang).
- TEST 2 — a hostile `(try … (catch :default _ :swallowed))` and a throwing `finally` both fail
  to swallow the interrupt (`:swallowed? false`).
- TEST 3 — per-render SCI eval overhead (~0.2ms warm) is ~1000x under a 250ms tile budget.
- TEST 4 — the residual class SCI canNOT bound: a native host loop and a native regex ReDoS run
  unbounded (the interrupt-fn never fires inside host code). This is the only class that would
  still need process/worker isolation.

Pins SCI `0.13.53` (vendored at `reference-code/sci`, tag `v0.13.53`).
