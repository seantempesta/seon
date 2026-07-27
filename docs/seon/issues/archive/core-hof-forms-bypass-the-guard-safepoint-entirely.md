---
type: issue
status: superseded
severity: blocker
tags: [issue, runtime]
---

Terminology: the first measurement retains its historical labels; current terms are `:interrupt-fn`, `interrupt!`, `time-limit`, and every `fn` body entrance.

# Core HOF forms can run between `:interrupt-fn` calls

## Observed

SCI's `:interrupt-fn` fires at interpreted fn entry and `recur`
(`reference-code/sci/src/sci/impl/fns.cljc:52`). A form whose work happens
inside a *compiled host* higher-order function therefore records **zero**
`fn` body entrances, so `seon.host.guard/check-holder!`
(`src/seon/host/guard.cljc:194-205`) never runs.

Measured on JDK 26 (`clojure -M:writer:host`), counting `:interrupt-fn`
invocations directly:

| form | historical entry count |
|---|---|
| `(loop [i 0 a 0] (if (< i 1000000) (recur (inc i) (+ a i)) a))` | 1000001 |
| `(defn f [x] (+ x 1))` + 1e6 loop calling `f` | 2000001 |
| `(reduce + (map inc (range 1000000)))` | **0** |

With the historical production policy armed at `::guard/mode :enforce` and its
retired `interpreter-step-budget` set to **1000**, plus
`install-interrupted!` bound to `(.isInterrupted (Thread/currentThread))`:

```
after 1.5s with budget=1000 steps : STILL RUNNING (budget never charged)
1.5s after Thread.interrupt       : STILL RUNNING (interrupt never observed)

```

`(reduce + (map inc (range 400000000)))` is not stopped by the budget and not
stopped by the deadline watchdog's `Thread.interrupt`
(`src/seon/host/invoke.clj:37-44`), because the interrupt predicate is only
*polled* through `:interrupt-fn` at a `fn` body entrance that never occurs.

## CORRECTION 2026-07-25 — measured against the wrong base; the hole is much narrower

The table above was produced against a SCI ctx **without** sci's interrupt-aware
core overrides. The tree merges them: `src/seon/host/context.clj:1405-1406` passes
`:namespaces {'clojure.core interrupt/clojure-core 'clojure.string interrupt/clojure-string}`
to `sci/init`. `interrupt/clojure-core` replaces `range repeat cycle iterate doall
dorun count into reduce` plus `re-find re-matcher re-matches re-seq` on the JVM
(`reference-code/sci/src/sci/interrupt.cljc:289-306`), and `clojure-string` replaces
`replace replace-first split` (`:308-315`). Each fires the `:interrupt-fn` per element.

Re-measured 2026-07-25 on JDK 26.0.1 with **that** base, counting `:interrupt-fn`
invocations directly:

| form | fn entries |
|---|---|
| `(reduce + (map inc (range 1000000)))` | **1,999,999** (this issue reports 0) |
| `(count (filter even? (range 1000000)))` | 1,500,000 |
| `(sort (vec (range 300000)))` | 300,000 |
| `(frequencies (range 200000))` | 200,000 |
| `(clojure.string/join "," (range 100000))` | 100,000 |
| `(clojure.string/replace <200k chars> "a" "c")` | 100,000 |
| `(clojure.string/split <200k chars> #",")` | 300,000 |
| `(apply + (repeat 1000000 1))` | 1,000,000 |
| `(doall (map inc (range 100000)))` | 200,000 |
| `(alength (byte-array 100000000))` | **0** |

Idiomatic `reduce`/`map`/`filter`/`sort` agent code **is** metered, because any
consumer of an interrupt-aware `range`/`repeat` inherits the meter. This issue's
headline claim is false for the tree as configured.

**The real hole, which stands:** a single host call over data the agent already
holds — the last row. `(byte-array 100000000)` charges zero entries; measured
separately, `(alength (byte-array 200000000))` allocated 200,033,752 bytes in 1 ms
with 0 entries and outcome `:ok` under a 64 MB allocation cap and a 500 ms
`time-limit`. That is sci's own documented limit
(`reference-code/sci/doc/interrupt.md:52,84-87`: "For hard guarantees it is best to
run untrusted code in a separate process that can be killed"), not a Seon defect,
and it is the reason the process boundary is the only hard bound.

## Why it matters

This is not an edge case. `reduce`/`map`/`filter`/`into`/`sort` is the style
`CLAUDE.md` §Data-oriented Clojure rules requires agents to write.
`:interrupt-fn` is described as the in-process interruption mechanism; for
idiomatic agent code
there is no in-process bound at all. The only real kill is the process boundary.

## Acceptance criteria

- A form whose work is entirely inside compiled host HOFs is either charged
  against a bound or provably attributed to the process boundary as the only
  containment, stated as such in `src/seon/host/AGENTS.md`.
- Any design that deletes the deadline watchdog or merges the process into the
  process owning the Datahike connection must first answer this case, since the
  process kill is currently the sole backstop.

## Owner

`src/seon/host/guard.cljc` (bound), `src/seon/host/invoke.clj` (deadline).

## Related

- `docs/seon/issues/guard-safepoint-destructures-a-map-on-every-interpreter-step.md`

## Resolution

Superseded by the fresh-tree split in f25e34594: the cited State A owner is quarry or deleted, and the current B2/N3/N4 ledgers do not carry this defect forward.
