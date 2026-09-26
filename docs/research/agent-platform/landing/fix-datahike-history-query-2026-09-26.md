---
type: landing
status: committed (fork f3a3cb59 + gitlink); RESTART NEEDED; seon behaviour test owed to root
lane: fix-datahike-history-query-opus
created: 2026-09-26
authority: docs/seon/issues/a-history-query-drops-a-bound-input-by-clause-order.md
---

# Landing: a probe-driven read keeps the planned slice's bounds (fix-datahike-history-query-opus, 2026-09-26)

Datahike's executor narrows a scan by the values an upstream relation already bound (a seek per bound
entity or value, "probe-driven"). That seek replaced the planned index slice, but the slice's own value
bounds were never re-checked, so a ground value or inclusive range vanished. The fix re-applies those
bounds on the probe-driven read, inside the fork, with no Seon change.

## Broken / pending first

- **RESTART NEEDED.** Default (8e217a254) loads Datahike from `reference-code/datahike/src`. It still runs
  the defective engine until root restarts it. This lane neither stopped nor restarted default.
- The Seon behaviour proof has not been run. Root runs, after the restart:
  `bin/test-check default --test seon.turn-test/settlement-refuses-a-divergent-program-change-since-opening`
- The "after" results for the Seon-world probes below need the restarted JVM. They are listed as
  commands, not as passes.
- The fork's own query tests have 5 unrelated reds, identical at `4157916b` and `f3a3cb59`: four in
  `query-test` (`:58/:69/:75/:152`) and `query-cache-test/config-mismatch-does-not-open-or-alter-cache-generation`.
  They are already recorded in `docs/seon/issues/datahike-pull-find-dependencies-widen-to-all.md`.
- Planner estimate oddity, not fixed: on default the AVET estimate for `[?run :seon.turn/id "<one id>"]`,
  a unique attribute with a constant value, is 485 rows, not 1 (485514 datoms / 1000). That estimate is
  why default takes the probe path where a small database does not. It is only an estimate, and after
  this fix it affects cost, not results.

## Minimal reproduction (default JVM, scratch namespace `scratch.fix-datahike-history`, raw `datahike.api`)

`tmp/fix-datahike-history-opus/minimal.clj` sets up an in-memory store with `:keep-history? true`:

- 10 entities with `:run/kind "x"` (indexed).
- One entity with `:run/kind "y"`, referenced by one receipt `:rcpt/run`.

The query cache is off (`*query-result-cache?* false`).

```clojure
[:find ?r ?run :in $ ?k :where [?r :rcpt/run ?run] [?run :run/kind ?k]]   ; ?k = "x"
;; history => #{[14 13]}   (the receipt of the "y" run; correct #{})
;; the literal form [?run :run/kind "x"] => #{[14 13]}; the reordered clauses => #{[14 13]}
;; plan: SCAN aevt [?r :rcpt/run ?run] (est 1), then SCAN avet [?run :run/kind "x"] (est 10)
```

The same defect loses an inclusive range pushdown (`tmp/fix-datahike-history-opus/pushdown.clj`):
`[?r :rcpt/run ?run] [?run :run/n ?n] [(>= ?n 5)]` on history answers `#{[14 1]}`. The strict `>` form
stays correct because strict predicates were already re-checked.

A current (non-history) value fails the same way once the attribute is large enough to pass the
2,500x seek break-even. At 3,000 entities the range query answers `#{[3005 1]}` on the current value
(`tmp/fix-datahike-history-opus/cur3000.clj`).

The Seon-shaped world (`tmp/fix-datahike-history-opus/repro3.clj`) applies the test's writes to
default's db with `d/with`. The query from `declaration-written-by-run?` answers 38544/39431
(run a's receipt) for run b with the planner on, and nil with `*disable-planner*`. The narrowest form
is `[?r :seon.eval/shown _ ?tx] [?r :seon.cluster.eval/run ?run] [?run :seon.turn/id "zz-run-b"]`,
which answers 51 receipts on history, 2,091 on `as-of`, and 0 on the current value.

## Root cause (fork `4157916b`, `src/datahike/query/execute.cljc`)

- `build-ground-filter` (`:352-367`) leaves out whatever the planned index's slice bounds enforce: the
  ground value on AVET (`:358`) and the ground entity on EAVT (`:360`).
- When an upstream relation binds the entity, `pattern-probe-set` returns an entity probe. The read then
  seeks per entity instead of using the planned AVET slice:
  - Non-temporal, past the 2,500x break-even: `scan-datoms` `:584` and `execute-group-direct` via
    `probe-driven-iterable`. That seek matches only e and a (`:215`).
  - Temporal, whenever the probe set is smaller than the estimate (`:1943-1944`): the temporal seek
    passed the planned AVET index with entity bounds (`:1962`). It therefore sliced the whole attribute,
    and `probe-set` was cleared (`:1987`).
- So nothing re-checks the value. The join by `?run` still enforces the entity, which is why the result
  is "every run".
- Upstream main `249930ac` has the same code (`build-ground-filter` at its `:516`, and the temporal seek
  at its `:2583`). No upstream fix exists. The issue's attribution to `620721d6`/`f4614c9d` was wrong and
  is corrected in the issue.

## Fix

Fork commit `f3a3cb59380c8b1e9ea0a3d6074c0863012cf0dd`, pushed to `seantempesta/datahike` main. It is
+37/−3 in `execute.cljc` and adds a 51-line test.

- `slice-bounds-filter` builds the constraint the planned index's bounds enforce, mirroring
  `compute-slice-bounds`: a ground entity on EAVT, and a ground value or pushdown range on AVET, in the
  index's own value order (`datom/cmp-nil`).
- Only the probe-driven reads apply it: the seek regime in `scan-datoms`, and the probe-driven slice in
  `execute-group-direct`, where it is folded into `ground-filter`. Ordinary index scans are unchanged.
- The temporal entity seek now reads EAVT.

I rejected an index-independent `build-ground-filter` that checks everything on every scan. It was
correct, but the direct cost measured 5.4 ms against 0.75 ms per 50k datoms filtered, about 25% of a
16 ms scan. That exceeds the 20% regression bar, so the check runs only on the bypassing read.

## Evidence

- The fork regression `datahike.test.query-planner-test/test-probe-driven-read-keeps-the-planned-value-bounds`
  covers current, history and as-of values: ground value, reversed clauses, literal value, the positive
  `"y"` case, `>=`, `<=`, and `<`.
  - At `4157916b` (a `git archive` snapshot in `tmp/fix-datahike-history-opus/fork-head`) it fails 14
    of 21 assertions: 4 on current, 5 on history, 5 on as-of.
  - At `f3a3cb59` all 21 pass.
- Fork query suite, 20 namespaces (planner, planner-temporal, query, cache, not, or, rules, pull,
  time-variance, valid-time, index, and others): 235 tests, 1,327 assertions. The 5 reds are the same
  pre-existing ones on both revisions, so the fix introduces nothing new. The run took 47.5 s wall in
  one datahike-only JVM, because the fork's tests need their own classpath.
- Lint: clj-kondo on `execute.cljc` reports 7 errors now against 8 at HEAD, all pre-existing
  macro-generated symbols. No new findings.

## Same-shape queries in Seon src (compiled vs `*disable-planner*` legacy engine, default db, pre-fix)

| Query | Before (compiled / legacy) | After |
|---|---|---|
| `seon.turn/declaration-written-by-run?` (`src/seon/turn.clj:1159`), Seon-world `d/with` | 39431 / nil (disagree) | root, after restart |
| `seon.program` agent-symbol query (`src/seon/program.cljc:112`, `$` const `:seon.fn.file/relative-root "src"`) | 0 / 0 (agree) | root, after restart |
| `seon.error` digest-at-latest (`src/seon/error.clj:1305`, history, `[(<= ?tx ?latest)]`) | 1 / 1 (agree) | root, after restart |

I read these sites and none has the defective shape, a ground value or range on an indexed attribute
scanned after its entity is bound:

- `src/seon/test.clj`: `:577`, `:645`, `:770`, `:800` and `definition-digests` (`:558`).
- `src/seon/reconcile.cljc:110`
- `src/seon/cluster/source.clj:351`
- `src/seon/test/runner.clj:736` (`reach-facts`) and `:805`
- `src/seon/maintenance.clj:584`
- `src/seon/cluster/registry.clj:410`
- `src/seon/issue.clj:673`
- `src/seon/turn.clj:299` (a pull)
- `seon.cluster.wake/agent-wake-datoms` (datoms, not a query)

The fix is in the engine, so every query of this class is covered, including current-value queries
past the break-even.

Root reruns the sweep after the restart:
`(load-file "tmp/fix-datahike-history-opus/repro3.clj")` then `(load-file "tmp/fix-datahike-history-opus/sweep.clj")`
in a scratch namespace. Expected: `:agree true` for all three.

## Commits and paths

- Fork: `f3a3cb59` in `reference-code/datahike`:
  - `src/datahike/query/execute.cljc`
  - `test/datahike/test/query_planner_test.clj`
- Seon: gitlink bump for `reference-code/datahike` (4157916b → f3a3cb59), this landing, and the
  corrected issue `docs/seon/issues/a-history-query-drops-a-bound-input-by-clause-order.md`.
- No `src/` change in Seon, so the pre-commit contract and schema static checks do not apply.
- Net Seon source lines: 0. The fork has +34 source lines and +51 test lines.
- Released: `reference-code/datahike` and its gitlink.
