---
type: issue
status: open
severity: friction
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

## Implemented change (pending wrapper verification)

The Item 1 commit that archives this note makes the retained `::check!` closure
capture the `long-array` and `object-array` once. The normal fn-entry path
therefore performs array access directly; the holder map is retained only for
the exceptional policy-trip path that builds the error value.

The shared base context now creates and retains a holder before `sci/init` and
installs `guard/interrupt-fn` directly. It no longer calls
`sci.ctx-store/get-ctx` or looks up the holder and check function on every
entry. Base-context evaluation and agent forks therefore use the same direct
closure shape.

On the JVM, the closure also calls
`LockSupport/parkNanos(1000)` after every 65,536 entries. The branch is
reader-conditional, so the portable ClojureScript guard retains its previous
behavior.

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

- The SCI-installed `::check!` closure performs no holder-map lookup; its two
  arrays are captured once in `guard/holder`.
- The recurring JVM guard tests preserve budget, timeout, output, retained-trip,
  and count behavior. A 65,536-entry regression exercises the fairness branch
  and preserves the exact entry count.
- The in-situ before/after measurement below records the real end-to-end SCI
  result. The earlier "~1 ns" target came from the eliminated compiled-loop
  microbenchmark and is not retained as an acceptance threshold.

## Evidence

### Implemented-path measurement

Measured on JDK 26.0.1 and Clojure 1.12.5 through 3,000,001 real SCI fn entries:

```clojure
(loop [i 0 a 0]
  (if (< i 3000000)
    (recur (inc i) (+ a i))
    a))
```

Each shape ran three warm iterations followed by five measured iterations in
one JVM; the reported value is the median. The committed-before source was
isolated under `tmp/guard-before/src` from `HEAD`, ahead of the same current
1.12.5 classpath. The working-tree source used an empty
`tmp/guard-after/src` first entry followed by `src`, preserving the command
shape:

```bash
clojure -Sdeps '{:paths ["tmp/guard-before/src" "src"]}' \
  -M:writer:host tmp/bench_guard_interrupt.clj
clojure -Sdeps '{:paths ["tmp/guard-after/src" "src"]}' \
  -M:writer:host tmp/bench_guard_interrupt.clj
```

| shape | five samples (ms) | median (ms) |
|---|---|---:|
| before, no interrupt function | 22.010, 23.486, 22.076, 28.958, 22.675 | 22.675 |
| before, agent-fork closure over holder map | 57.071, 64.182, 81.270, 58.143, 74.385 | 64.182 |
| before, base context-store lookup | 149.903, 146.490, 150.401, 149.127, 111.413 | 149.127 |
| after, no interrupt function | 25.418, 25.141, 22.342, 27.223, 27.065 | 25.418 |
| after, direct-array closure with 45 fairness parks | 41.604, 42.539, 45.285, 39.412, 40.940 | 41.604 |

The former base shape no longer exists in production. The base and agent-fork
paths both install `guard/interrupt-fn`, so the after row is their common hot
path. These absolute in-situ medians replace the stale 44x microbenchmark
claim; no speedup is inferred from the eliminated-loop probe.

### Verification

- `seon.host.guard-test`: 8 tests, 25 assertions, 0 failures, 0 errors.
- A direct combined `guard-test` + `guard-context-test` invocation ran 10 tests
  and 33 assertions with 0 failures and 1 unrelated error: the existing
  captured-output fixture reaches a null synchronization lock in the protected
  `src/seon/host/eval.clj:218`. This item did not change or work around that
  protected path.
- `bin/test-writer` initially stopped before test discovery because the
  dependency bump invalidated the compiled program artifact. Its coordinated
  post-rebuild result is reported with the Wave 0 integration evidence.

Historical reproduction script used for the eliminated-loop table:
`bench-guard` (scratchpad, 2026-07-25) — compiled loop body via
`clojure.core/eval`, holder reset with `::guard/mode :count` and
`Long/MAX_VALUE` budget so no policy fires, best-of-5 after 3 warm runs.
