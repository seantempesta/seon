---
type: research
status: measured on default's running commit; probe agent unmap-probe-1 (branch retired)
---

# ns-unmap! cost, 2026-10-12

## Measurement

Probe: as agent `unmap-probe-1` in SCI mode, `(in-ns 'probe.unmap)` and `(defn f [] 1)` in one request; then `(my.program/ns-unmap! 'probe.unmap/f)` in a second request, wrapped in `seon.profile/measure` around `#'seon.mcp/eval-clj` from JVM mode as root. The context argument is supplied by the world.

| Run | Request ms | Request alloc MB | Calls | Reads | Transactions | Measure-window ms |
|---|---|---|---|---|---|---|
| 1 (no ranked fields kept) | 638.6 | 373.9 | 17,498 | 205 | 2 | 643 |
| 2 (ranked fields kept) | 349.7 | 148.5 | 15,201 | 178 | 2 | 357 |

Run 2 stages (ms): evaluate 234.2, settle 42.7, test-step 38.8, facts 21.0, unattributed 10.9, read 0.1, custody 0.4. The measure window's own allocation read 8 MB because the eval runs on another thread; the request figure and the JVM window (shared, includes unrelated work) are the only allocation readings.

Top 15 by self (run 2): `seon.await/await-port-operations` 347 ms (1 call, a wait), `seon.schema/call-with-projection-state` 346 ms (4), `seon.db/transact-call` 161 ms (2), `seon.db/vector-query-call` 116 ms (100), `seon.fn/gate-edges` 48 ms (4), `seon.db/pull-logical` 13 ms (74), `seon.fn/gate-set-in` 9 ms (4), `seon.test/digest-differences` 5 ms, `seon.db/with-declarations` 3 ms (254), then several at 2 ms or less.

Top 15 by calls (run 2): `seon.db/database-value?` 4,353; `seon.render.value/more?` 1,345; `seon.schema.datahike/query-variable?` 1,293; `seon.db/transaction-report-datom?` 1,265; `seon.schema/projection-cache-value` 1,125; `seon.profile/cost-scope` 1,001; `seon.db/connection-object?` 913; `seon.db/schema-database` 654; `seon.db/query-binding-values` 577; `seon.print/node-child?` 530; `seon.render.value/extras` 513; `seon.schema.datahike/vector-shaped-attr-in?` 511; `seon.env/environment?` 487; `seon.render.value/flat-width` 390; `seon.cluster.store/file-lock-object?` 317.

## Reading the code path

`my.program/ns-unmap!` calls `retract-operation!`, which calls `deletion-report` once outside the transaction. Then `db/transact!` runs a `:db.fn/call` that calls `deletion-report` again on the transaction's database value. Each `deletion-report` calls `breaks` once for the subject and once for every owned declaration. `breaks` reads callers, references, test subjects, gating tests (`function/gating-tests`), render referrers, and call sites. For a single function subject the owned set is just the subject, so the work should be proportional to that function's referrers, not the whole program.

## Findings

- The 674 MB figure from the program-context notes is not reproduced: two runs measured 373.9 MB and 148.5 MB. Allocation varies between runs; the request figure counts only the request thread, so treat it as a lower bound.
- The subject's report is computed twice per call (before the transaction and again inside the `:db.fn/call`). That duplicates the reach walk and is the first thing to cut.
- Calls (15,000 to 17,500) and reads (about 180 to 200) are far above what one function's referrers should need. The per-call work scales with the schema and the whole program graph, not with the one subject.
- Self time is dominated by a wait (`seon.await/await-port-operations`), so CPU is not the cost; allocation and call count are.

## Likely owner

`my.program/retract-operation!`, which runs the duplicate `deletion-report` pass; the work it repeats is `my.program/breaks` (through `my.program/deletion-report`). Confirm with a heap profile of one ns-unmap! request before the fix.

Probe branch `:agent-836d7b9028f8` (agent `unmap-probe-1`) retired: `:my.agent/retired? true`.
