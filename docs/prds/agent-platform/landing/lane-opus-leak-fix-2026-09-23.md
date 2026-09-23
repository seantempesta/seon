---
type: landing
status: fix adopted on pid 31476; heap gate pending default recovery
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

## Heap gate

PENDING. The first green request after adoption refused at context
acquisition with `Node not found in storage` (address
`6ab378a2-e7bb-47db-bf1a-b4ab302df8c8`, a node from about 06:58Z; full text in
`tmp/opus-leak-fix/green.txt`). At that point default had about 240 MB free,
with old 3.8 GB and humongous 6.4 GB. The 5 GB humongous `byte[]` rise
happened during another lane's full-source refresh (`seon.cluster/full-source-refresh!`
→ `seon.fn/analyze-rows` → kondo) at about 06:50Z. It stayed live after that
refresh ended. That is a separate retainer, not this lane's. RESET NEEDED;
reported to the orchestrator.

Pre-fix test-only cycles on pid 31476 (after `jcmd GC.run`):
1208 → 1200 → 1208 → 1200 MB old, then 1904 → 2008 → 2080 MB during
concurrent lanes.

## Timings over one second

| Operation | Wall | Justification |
|---|---:|---|
| `bin/seon init --dev default --changed instrument.clj + test` | 35,952 ms | DEFECT: the whole-source refresh reran kondo analysis (`refresh-source!` x3, 13.5 s inclusive earlier). It is proportional to the program, not the change. Owned by the publication lane's existing issue. |
| `bin/seon init --dev default --changed` test file only | 4,647 ms | Same refresh path. |
| Named request, retention test only (red) | 13,546 ms | DEFECT over 10 s: it built the projection from the snapshot and ran up to three full GCs. The test now uses `schema/current-projection`. |
| Named request, fault-test (pre-fix baseline) | 3,117–8,288 ms | Request overhead (branch, context acquisition, recording). Owned by test-overhead. |
| Probe: reach over the projection LRUs | 20,178 ms | Diagnostic, bounded at 20 s. |
| Probe: attribute-vars walk | over 120 s | DEFECT in my probe: reflective and unbounded. It was aborted by poisoning its own throwaway cache. The bounded version replaced it. |
