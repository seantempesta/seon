---
type: research
status: active
tags: [research, agent]
---

# SCI interrupt — validation & how to test

This is the **downstream-testable** record of the fix approach for the tile/eval lockup: an
agent-authored function that does not terminate (an infinite loop, runaway recursion, or a
synchronous block) freezes the entire single-threaded Seon pod — heartbeat, HTTP, SSE, and every
other agent — until a manual restart.

## Status (read first)

- The approach is **proven** (the spike below) and **designed** (the PRD,
  [[tile-isolation-prd-2026-06-21]]).
- The product code fix is **NOT yet implemented.** There is no running feature to exercise. What
  a downstream client can do today: (1) **reproduce the proof** by running the spike, and
  (2) **review the design** in the PRD.
- When the implementation lands (SCI-bounded `render-agent-tile` behind a flag), this doc gets a
  "test the feature" section. Until then, "test it" means "reproduce the proof".

## The finding (what was proven)

A **synchronous** infinite loop cannot be interrupted from within the same JS thread — `try/catch`
never runs and the existing async eval timeout (`Promise.race` vs `js/setTimeout`) never fires
because the blocked event loop never schedules the timer. The fix is to run agent-authored tile
functions under **SCI** (the Small Clojure Interpreter), which controls its own eval loop and
fires a caller-supplied `:interrupt-fn` at the top of every interpreted `recur` iteration. A
wall-clock deadline in that `:interrupt-fn` aborts the loop in-process — no worker, no
serialization, the database value is passed by reference.

### Spike results (real runs on Node v24, SCI `0.13.53`)

| Check | `:none` (dev/watch) | `:simple` (release w/o `:advanced`) |
|-------|---------------------|-------------------------------------|
| `(loop [] (recur))` aborted | ✅ at 251ms vs 250ms budget, proper `:sci.impl/interrupt` marker | ✅ at 251ms, identical |
| Loop genuinely synchronous (no event-loop yield) | ✅ macrotask + microtask both unfired | ✅ identical |
| Un-catchable by hostile `catch` / throwing `finally` | ✅ `:swallowed? false` | ✅ identical |
| `js/Date.now` readable inside the blocked loop on Node | ✅ | ✅ |
| Per-render overhead | ~0.2ms warm median (~1000x under a 250ms budget) | ~0.2ms, identical |
| Compile | n/a | ✅ clean, 0 warnings |

`:advanced` is intentionally **not** validated — it is the only level that does the dead-code
elimination and global/property renaming that historically breaks SCI and malli in this codebase,
and the current target is the dev (`:none`) build (and `:simple` if a minified release is ever
wanted).

### What SCI does NOT cover (the residual class)

The interrupt fires only on **interpreted** entry. If a tile function calls into a **native host
loop** (a JS `while(true)`) or a **native regex** with catastrophic backtracking (ReDoS), the
interrupt never fires and the thread still blocks (the spike measured a 1.5s host loop and a ~33s
ReDoS, both unbounded). Bounding that class needs a killable process/worker (the PRD's Layer 2,
deferred). If the tile contract is "interpreted DB-query → hiccup" — the stated intent — Layer 1
covers essentially the whole realistic surface.

## How a downstream client tests it (reproduce the proof)

The spike is self-contained and committed at
`docs/prds/agent-runtime/spikes/sci-interrupt/` (see its `README.md`). It does not touch the live
pod or the shared build.

```bash
cd docs/prds/agent-runtime/spikes/sci-interrupt
clojure -M -m shadow.cljs.devtools.cli compile spike   # :none build (what the pod uses)
node out/spike.js
```

A passing run prints four labelled tests. The decisive line is TEST 1: a true synchronous
`(loop [] (recur))` is aborted within ~the deadline and the script CONTINUES (the process does not
hang), with the falsification probe confirming the loop never yielded the event loop. TEST 2 shows
the interrupt survives a hostile `catch`/`finally`. TEST 3 is the overhead budget. TEST 4 shows
the native-host residual class that Layer 1 cannot bound.

To also confirm `:simple`: add `:compiler-options {:optimizations :simple}` to the `:spike` build
in `shadow-cljs.edn`, then `release spike` instead of `compile spike`, and run the output — the
results are identical to `:none`.

## Related

- Design / decision / migration plan: [[tile-isolation-prd-2026-06-21]]
- The rejected reactive stopgap (external watchdog + crash-marker recovery), for comparison:
  [[tile-lockup-safety-2026-06-21]]
- Vendored SCI source (for review): `reference-code/sci` (tag `v0.13.53`).
