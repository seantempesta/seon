---
type: landing
status: landed c9870081f; heap gate passed on pid 55322; second retainer not reproduced
created: 2026-09-23
---
# Default heap retention: callables pinned by the projection holder

## What actually retains old worlds (measured, pid 31476)

Diagnosis candidates 1 and 2 from
`docs/research/agent-platform/oom-and-store-damage-2026-09-23.md` were
measured before any edit. Probe files are in `tmp/opus-leak-fix/`
(`census.clj`, `chain.clj`, `walk.clj`, `p3.clj`), loaded into throwaway
namespaces through MCP `eval_clj`. No Var in default was redefined.

- **Candidate 1 (Malli generations through reused schemas) is real but small.**
  `opus-leak-chain/run-distinct 40` ran 40 incremental generations from a live
  cached projection, each adding a distinct key. Reachable option-registry
  generations went 7 → 46, and heap after `System/gc` went 1839 → 1841 MB.
  That is about 50 KB per generation, at 2.25 ms per generation. Re-changing
  the same key 20 times (`run 20 :seon.turn/id`) held 7 generations and the
  heap flat. A retained generation pins only its lazy registry's changed
  schemas and its structurally shared retained map. It does not pin a whole
  compiled population. No schema.clj change was made (see below).
- **Candidate 2 (wrapper memo in the projection holder) is the retainer of
  databases and SCI contexts.** `compiled-wrapper` memoized each wrapper under
  `[::wrapper function-symbol authored original policy]` in the long-lived
  projection's `:seon.schema.projection/compiled` atom. `arm-var!`'s
  supplied-projection path did the same under `::declared-wrapper`.
  `wrap-interpreted` arms fresh SCI callables per context acquisition, and
  their `original` closes over that context and its database value.
  - Census of the four cached projections' holders: 20,229 entries, of which
    1,814 were `::wrapper` and 1,260 were `::declared-wrapper`. They covered
    1,304 distinct originals for 1,176 symbols, and `seon.test.runner` Vars
    had 8 originals each (one per reload).
  - `family-reach` from the `::wrapper` entries, with the projections as stop
    nodes, reached 30 `datahike.db.DB`, 24 `sci.impl.opts.Ctx`, 28,220+
    Datoms (truncated at 3 M objects) and 254 k map-validator closures.
  - `entry-attribution` named `my.agents.root/largest`
    (`clojure.lang.AFunction$1`, 2–3 DBs and 2 Ctx each) and a
    `seon.schema_test` fixture's `my.agent/branch` (18 DBs, 9 Ctx).
  - A full reach from the two projection LRUs (20 s bound, truncated) found
    4.7 M Datoms, 30 DBs, 26 Ctx and 16 lazy registries.

## Change

`src/seon/instrument.clj`:
- `compiled-wrapper` no longer memoizes into the projection. It compiles for
  its caller and returns. Malli's validators stay cached on the schema
  objects.
- `arm-var!`'s supplied-projection path keeps one `[projection wrapper]` slot
  per installation. The slot also holds the "not validated" answer (nil
  wrapper), so `validates-loaded-contract?` runs once per projection change,
  as before.

Cost: memory now follows O(live installations × one projection reference),
not O(context acquisitions + reloads). A compile costs about 100 µs (measured
on `seon.db/pull` without a holder, 200 iterations) and now happens once per
installation per projection change. A declared-path call now does one atom
deref and `identical?`, where it used to do a `swap!` on the projection atom.
The simplest alternative considered was keying the memo without `original`.
It was rejected because the wrapper must call its own original.

Why no schema.clj change: the scoped-registry redo (tmp/sol-leak-fix/wip.patch)
added `:seon.db/component-schema` property edges to the reference graph. The
packaged `:my.plan.item/item ↔ :my.plan.item/steps` component is cyclic through
exactly that property. With the WIP loaded, the WIP's
`seon.print/node-face-validator*` delay failed with "canonical schema
reference cycle [:my.plan.item/item :my.plan.item/steps :my.plan.item/item]".
That in turn made every MCP eval and adoption result unrenderable on pid
28202. Correct scoping would require `internal.cljc`'s owned-storage check to
take the generation registry from its request, not from `(m/options compiled)`.
That file is outside this lane. With about 50 KB per generation measured, it
is not the heap driver.

## Regression

`test/seon/schema/registry_retention_test.clj`: a callable armed through
`wrap-interpreted` under a live projection closes over a fresh world. After it
is dropped and at most three `System/gc`, its WeakReference is cleared while
the projection is still alive.
- The first red run (`30e406682e38`, fail 1) does not count as proof. Its GC
  loop, `(and (.get reference) ...)`, held the world in the `and` local while
  it collected. The loop now tests `(some? (.get reference))`.
- The red mechanism was shown directly instead. In a throwaway namespace, an
  entry of the pre-fix shape `[:seon.instrument/wrapper sym authored original
  nil]` was put in a live projection's holder. The world stayed reachable after
  three `System/gc` (`:pre-fix-shape-world-alive true`).
- GREEN on the fixed loaded code: `clojure.test/run-test-var` in default's JVM
  gave 1 test, pass 3, fail 0, 1,281 ms. The time is mostly
  `build-projection`, since there is no current projection outside the runner.
  A direct probe with the live cached projection also freed the world
  (`:world-alive false`).
- `bin/test-check` could not run the green, because of the store fault below.
  `seon.instrument-test` and `seon.fault-test` were not run on the fixed code.
- No `:malli/schema` was touched (`compiled-wrapper`'s and `arm-var!`'s
  contracts are unchanged), so no contract compile was needed.

## Heap gate (pid 55322, running from the checkout)

`(seon.fs/source-directory)` returned `/Users/sean/src/seon`. Each cycle was
run by `tmp/opus-leak-fix/gate.sh`, holding the adoption token throughout:
1. Edit a comment in the regression file.
2. `bin/seon init --dev default --changed test/seon/schema/registry_retention_test.clj`.
3. `bin/test-check default --policy named --ns seon.schema.registry-retention-test --ns seon.fault-test`.
4. `jcmd 55322 GC.run`, then `GC.heap_info`.

| Cycle | Adopt | Request | Run | Old gen after GC |
|---|---:|---:|---|---:|
| baseline | — | — | — | 472 MB |
| 1 | 2,498 ms | 6,868 ms | `0e4f9fb5454d` executed 4, pass 31 | 704 MB |
| 2 | 2,222 ms | 5,522 ms | `e04c9b9057a7` executed 4, pass 31 | 672 MB |
| 3 | 2,336 ms | 5,250 ms | `2e12843b43c2` executed 4, pass 31 | 680 MB |

The heap is flat after the first cycle. Census after cycles 1 and 3: 1 distinct
projection registry, 4 reachable option-registry generations, and 0 wrapper
entries in projection holders (before the fix, pid 31476 had 3,074).
An earlier run on pid 54364 does not count: that JVM ran from the
`data/source/c9870081f…` archive, so its adoptions loaded nothing.

## Second retainer (8.4 GB byte[] on pid 31476): not reproduced

- `tmp/orchestrator/heap-2026-09-23/histo-0835.txt` shows 4,230,472 byte[]
  (8.46 GB) against 4,225,552 Strings. That fits roughly 2 KB Strings plus
  about 6.4 GB of humongous (≥4 MB) arrays.
- Two adoptions on pid 55322 were measured by GC plus class histogram before
  and after. Neither grew byte[]:
  - a `src/seon/schema.clj` comment: 2,660 ms, byte[] 219.6 → 227.0 MB;
  - a real description fix in `resources/seon/schemas/seon.profile.edn`:
    5,949 ms, byte[] about 217 MB, old 928 → 968 MB.
- Analysis classifies `:all` only on a `.clj-kondo` change
  (`seon.cluster.source/classify-paths`). The 58.8 s full-refresh path did not
  recur on the current publication code.
- **One candidate GC root is proven: a long-lived prepl session's `*1`/`*2`/`*3`.**
  Clojure's prepl `set!`s them after every eval
  (`reference-code/clojure/src/clj/clojure/core/server.clj:236-238`), and
  Seon's `resources/seon/operator/prepl.clj` runs that prepl for every MCP
  session. Probe:
  - Session `opus-leak-star1` returned a 64 MB vector of byte arrays.
  - From another session, a WeakReference to that vector survived two
    `System/gc`.
  - After three more evals in `opus-leak-star1`, it was collected.
  - Pid 31476 carried 14 idle MCP sessions (runtime_status). Each one retains
    its last three results and `*e`.
- That these sessions held the 8.4 GB is NOT proven. Pid 31476 is gone, and
  `Thread.threadLocals` is closed to reflection (`java.base does not opens
  java.lang`), so a live walk cannot reach session bindings.
- If it recurs, the evidence to take is `jcmd <pid> GC.heap_dump` before any
  restart. A candidate fix belongs to the MCP owner: close idle sessions, or
  stop retaining `*2`/`*3`. It has not been made.

## Timings over one second

| Operation | Wall | Justification |
|---|---:|---|
| `bin/seon init --dev default --changed instrument.clj + test` | 35,952 ms | DEFECT: the whole-source refresh reran kondo analysis (`refresh-source!` x3, 13.5 s inclusive earlier). It is proportional to the program, not the change. Owned by the publication lane's existing issue. |
| `bin/seon init --dev default --changed` test file only | 4,647 ms | Same refresh path. |
| Named request, retention test only (red) | 13,546 ms | DEFECT over 10 s: it built the projection from the snapshot and ran up to three full GCs. The test now uses `schema/current-projection`. |
| Named request, fault-test (pre-fix baseline) | 3,117–8,288 ms | Request overhead (branch, context acquisition, recording). Owned by test-overhead. |
| Heap-gate adopt (per cycle) | 2.2–2.5 s | Partial publication of one test file plus reload. It is not sub-second: `refresh-source!` and read-evidence work sit in the publication lane's issue. |
| Heap-gate named request | 5.3–6.9 s | Branch, context acquisition, 4 executed tests and recording. The retention test's own `build-projection` fallback costs about 1.3 s of it. Owned by test-overhead. |
| `seon.profile.edn` adoption | 5,949 ms | Schema resource publication. Profile shows a concurrent agent turn (`seon.turn/call-turn` 43 s) in the same window. |
| Probe: reach over the projection LRUs | 20,178 ms | Diagnostic, bounded at 20 s. |
| Probe: attribute-vars walk | over 120 s | DEFECT in my probe: reflective and unbounded. It was aborted by poisoning its own throwaway cache. The bounded version replaced it. |
