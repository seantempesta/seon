---
type: landing
status: landed
lane: fix-fn-scan
created: 2026-09-24
report: docs/research/agent-platform/sci-context-efficiency-opus-2026-09-24.md (root cause 1, slice 1)
---

# Lane fix-fn-scan: ground the declared attributes (2026-09-24)

What exists: `seon.fn/declared-reference-rules` joins data rows to functions through
an attribute that an earlier clause binds. Datahike reads the whole store for that
pattern. The approach: keep the rules as the single definition, and ground
`?attribute` once per candidate attribute before querying, so each pattern reads one
index slice. Instances are selected by the installed `:db/valueType`.

## Broken or not proven

- **The change is not installed in the live default JVM.** Pid 90471 runs archive
  `ce7384682` with hook publication off. The installed proof below comes from a
  `git archive` snapshot of HEAD `fef94ab7a` plus this diff, booted under a scratch
  root (store indexed from zero, 483,257 datoms). The live-store numbers come from
  unarmed scratch-namespace copies of the new forms in pid 90471. No default Vars
  were redefined. **RESET NEEDED** (or `start --head`) to install the change on default.
- The probe's expected ~150 ms was not reached in the snapshot. One override took
  443–621 ms there, but the no-override derive also took 307–346 ms in that JVM
  (76–140 ms on default). The remaining override cost is interpreting affected rows
  (`install-row!`, 956 ms over 18 calls), which is outside this slice.
- `seon.fn-test/declared-function-values-contribute-edges-without-arities` is over
  its 5 s bound both here (9,326 ms) and on the parent code on default (8,539 ms,
  run `70718f31a7fd`). This slice did not cause it, and it is not fixed.
- On live data, rule 2 (declared `:seon.fn/reference-to` attributes) produces 0
  edges in both forms. Its equality is proven only on the empty set and by the
  fixture test.
- Semantics changed in one case: a rule that uses the value as an entity is no
  longer instantiated for a non-ref attribute. Before, a long-valued non-ref
  attribute whose value happened to equal a function's entity id would match. No
  such attribute exists today; the oracle test's set equality holds.
- Warm cache hits cost slightly more: 0.73–0.90 ms / 1.55 MB against 0.39–0.44 ms /
  0.49 MB, because three cached reads replace one. In exchange, the cache
  dependency is now 12 concrete attributes instead of `:all` for the rule query,
  so unrelated commits no longer invalidate it.
- The report's "53 MB per call" warm closure is unchanged. It comes from
  whole-program identity and file-reference materialization, plus `seon.db/q`
  decoding (report root causes 2 and 5).

## Root cause (verified at the dependency)

Datahike's `scan-datoms` (`reference-code/datahike/src/datahike/query/execute.cljc:487`)
has an index path for a variable attribute only when both `?a` and `?v` are bound
(`var-attr-avet-pairs`, `:456`). With `?a` bound and `?v` free, it slices all of
EAVT. A scalar input is substituted as a constant (`query.cljc:2276`). Binding the
target instead does not help: Astra's 803 ms result reproduced here as
810 ms / 2.94 GB. Issue: `docs/seon/issues/datahike-bound-attribute-variable-scans-the-store.md`.
Existing Seon-side sighting: `docs/seon/issues/a-collection-bound-edge-clause-costs-half-a-second-per-read.md`.

A ground pattern also seeks a pre-bound value in the attribute's index.
Instantiating a rule for the wrong value type threw `ClassCastException` in
fixture tests (Long → Symbol, Symbol → String). The first version, which used
value guards only, failed the snapshot test request this way. Hence the selection
by value type and the `(symbol? ?symbol)` guard, which keeps EDN-string values out
of the `:seon.fn/sym` index.

## Before / after

Cold means a fresh uncached value from `(d/db-with db [])`. Allocation is measured
per thread with `ThreadMXBean`.

| Probe | Before | After | Equal |
|---|---|---|---|
| `declared-reference-edges`, default live store (575,837 datoms) | 800–902 ms / 2.94 GB | 6.3 ms / 19 MB | 26 edges, equal; per-rule oracle equal |
| same, snapshot (armed) | 2,162 ms / 2.52 GB | 45 ms / 36 MB | 26 = oracle 26 |
| `reverse-closure` one seed, snapshot, cold | 2,456 ms / 2.61 GB | 178 ms / 123 MB | — |
| `derive-base-ctx` 1 override, default (parent code) | 910–933 ms / 3.30 GB | not installable | — |
| `derive-base-ctx` 1 override, snapshot | 2,686–3,024 ms / 2.91–3.03 GB | 443–621 ms / 435 MB | — |
| `derive-base-ctx` 0 overrides, snapshot | 324–346 ms / 278 MB | 307–320 ms / 274 MB | — |
| `currently-failing-functions`, default | 808–888 ms / 3.08–3.11 GB | 42 ms / 179 MB | equal (empty) |
| `functions-without-tests`, default | 2,111–2,379 ms / 9.96 GB | 147 ms / 439 MB | 116, equal |
| `unresolved-callers` rows (`[?attribute ...]` → scalar loop) | 3,868–4,262 ms / 4.94 GB | 340–394 ms / 670–685 MB | 56, equal |
| `assert-capability-contracts!` parent step (`[?edge ...]` → scalar) | 622 ms / 1.90 GB | 4.6 ms / 7.2 MB | 421, equal |
| `caller-files` referrers (`[?key ...]` and `[?attribute ...]`) | 18.6 ms | 21.6 ms (scalar) | not changed: the both-bound path is indexed |

Deleted seeds: with `seon.maintenance/collect!` and `seon.edit.jvm/edit` retracted,
the old and new edge sets are equal (21). The reverse closure over seeds [both
deleted, `seon.note/add-note!`, `no.such/fn`] is equal between them and equal to
installed `reverse-closure` (40 affected, 57 tests). For each of the 16 targets,
the ground rules with that target bound return exactly the oracle's callers, and
`call-edge` queries by symbol succeed.

## Tests (snapshot root, `bin/test-check --root <snap> default`)

- `seon.test.declared-reference-test` (the class regression: indexed edges equal
  the variable-attribute rules over the canonical population, under 1 s) plus the
  two touched `seon.fn-test` members: run `0333322d8f59`, 3 executed, 35 pass,
  1 fail (the pre-existing duration above).
- `--changed` for the five changed functions: run `114bc55bd034`, 56 executed,
  reds only in `my.*` / `seon.ai` / `seon.agent-*` members. Parent code in the same
  root: run `b495f26b3069`, 32 executed. Every parent red is red here, and each of
  the 15 additional reds was pending or unfinished in the parent run: the 121 s
  request limit ran out sooner on the slower parent. The outputs are in
  `tmp/fix-fn-scan/changed-run{,-parent}.txt`. Fixture refusals such as
  "required key `:seon.program/definition-digest`" are foreign.

## Changed paths and size

- `src/seon/fn.clj`: +78 / −23. New `ground-attribute-rules` and
  `ground-test-reach-rules`; `declared-reference-edges`, `currently-failing-functions`
  and `functions-without-tests` use them; `unresolved-callers` and
  `assert-capability-contracts!` read one scalar attribute per query; rule 4 gains a
  symbol guard.
- `test/seon/fn_test.clj`: +2 / −2. The two coverage assertions use the production
  ground rules.
- `docs/seon/issues/datahike-bound-attribute-variable-scans-the-store.md`: new.
- Net source +55 lines. The existing checkpoint WIP in `fn.clj` was untouched.

Snapshot root deleted after the proof. The scratch namespace `scratch.fix-fn-scan`
on default was removed.
