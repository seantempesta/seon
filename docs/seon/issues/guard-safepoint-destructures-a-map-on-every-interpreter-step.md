---
type: issue
status: open
severity: performance
tags: [issue, agent, runtime, flow]
---

# The guard safepoint destructures a map on every interpreter step

## Problem

`seon.host.guard/check-holder!` is the closure SCI calls at EVERY safepoint
(every interpreted fn entry and every `recur` iteration —
`reference-code/sci/src/sci/impl/fns.cljc:52`). It receives the holder MAP and
destructures it on each call:

```clojure
(defn- check-holder!
  [{::keys [^longs interpreter-step-counter ^objects control-cell] :as holder}]
  ...)

```

`src/seon/host/guard.clj[c]:194-205`. Two keyword lookups plus two casts run
per safepoint. The arrays it needs are already allocated once in
`guard/holder` (`src/seon/host/guard.cljc:53-55`) and never change identity —
the closure could simply close over them.

## Measured cost (JDK 26, this checkout, `-M:writer:host`)

A compiled 10 000 000-iteration loop calling the guard closure once per
iteration:

| safepoint | 10M iterations | per step |
|---|---|---|
| production `guard/check!` (map destructure) | 89.16 ms | 8.92 ns |
| identical semantics, closed-over arrays | 2.04 ms | 0.204 ns |
| no safepoint | ~0 ms (loop eliminated) | — |

**~44x.** In whole-evaluation terms:

| workload | today | guard share |
|---|---|---|
| SCI JVM 10M-iteration loop, no `:interrupt-fn` | 92.9 ms | — |
| same loop, production guard installed | 160.4 ms | 67.5 ms = **42%** |
| SCI JVM `fib 30`, production guard | 70.9 ms | ~25 ms = **35%** |

So roughly a third to a half of the JVM interpreter's wall time on
compute-bound agent code is this destructure, not SCI.

## CORRECTION 2026-07-25 — do not quote 44x, and the destructure is only half of it

The table above is a **microbenchmark**: it calls `guard/check!` directly in a
compiled loop, so it omits `sci.ctx-store/get-ctx`, and its 0.204 ns/step row is
below the cost of a single memory operation (a JIT-eliminated loop).

Re-measured **in situ**, through 3,000,001 real SCI fn entries
(`(loop [i 0 a 0] (if (< i 3000000) (recur (inc i) (+ a i)) a))`), JDK 26.0.1,
sci `reference-code/sci`, median of 5 after 3 warm runs, `-M:host`:

| interrupt-fn | median | per fn entry, over no-interrupt |
|---|---|---|
| none | 24.6 ms | — |
| **agent-fork shape** — `(guard/interrupt-fn holder)` = `::check!` = `(fn [] (check-holder! holder))`, installed by `context/fork-context` (`src/seon/host/context.clj:1423-1430`, `guard.cljc:49-55,194-205`) | **73.4 ms** | **16.3 ns** |
| **base-ctx shape** — `(sci.ctx-store/get-ctx)` → `::guard/holder` → `::guard/check!` → the same `check-holder!` (`src/seon/host/context.clj:1409-1412`) | 116.4 ms | 30.6 ns |
| closed-over `long-array` + one volatile read | 34.5 ms | 3.3 ns |

**There are two interrupt-fn shapes in the tree, and this issue conflates them.**
`build-base!` installs the ctx-store-lookup closure (`context.clj:1409-1412`), but
`fork-context` **overwrites** `:interrupt-fn` on every agent fork
(`context.clj:1427-1429`), so agent evals pay the middle row, not the top one. The
ctx-store row applies to evaluation in the base ctx itself (base build, portable-slice
load) and costs an extra **14.3 ns per entry** — a dynamic-var deref
(`reference-code/sci/src/sci/ctx_store.cljc:29-36`).

- agent path / no-interrupt = **2.98x**, not 44x;
- agent path / the proposed closed-over shape = **2.13x**;
- base path / no-interrupt = 4.73x.

The `44x` and `0.204 ns/step` rows above are a microbenchmark artifact; the
independent `u1-fuel-calibration-2026-07-23.md:64-71` figure of 29.857 ns/check over
one million warmed checks sits at the base-shape end. Treat **16 ns/entry as the
settled agent-path cost** and 3.3 ns/entry as the settled target; quote no ratio
above ~3x.

Consequence for any "compile agent code for speed" proposal: the check still costs
more than the compiled body it protects, so the check is the first fix, not a JIT.

## Expected owner

`src/seon/host/guard.cljc` — `holder`/`check-holder!`/`check!`. Secondary:
`src/seon/host/context.clj:1409-1412` should stop resolving the holder through
`sci.ctx-store` on every entry (it is already redundant for agent forks, which
overwrite `:interrupt-fn` at `context.clj:1427-1429`). The fix stays
inside the existing one mechanism: build `check!` as a closure over
`interpreter-step-counter` and `control-cell` directly instead of over the
holder map, keeping `check-holder!`'s semantics byte-for-byte (charge one
step, enforce the budget, then poll the installed interrupt predicate).

## Acceptance criteria

- `guard/check!` performs no map lookup; the two arrays are captured once in
  `guard/holder`.
- A recurring JVM guard test asserts the same budget/timeout behaviour as
  today (`test/seon/host_cancel_writer_test.clj` and the guard tests already
  own that class) — this is a representation change, not a policy change.
- A recorded measurement showing the per-step cost at or below ~1 ns, taken
  the same way as the table above, lands in the owning PRD's research
  directory.

## Evidence

Reproduction script used for the table:
`bench-guard` (scratchpad, 2026-07-25) — compiled loop body via
`clojure.core/eval`, holder reset with `::guard/mode :count` and
`Long/MAX_VALUE` budget so no policy fires, best-of-5 after 3 warm runs.
