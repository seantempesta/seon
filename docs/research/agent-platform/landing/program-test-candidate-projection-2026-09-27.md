# program-test red: the candidate copy drops the run's own schema (2026-09-27)

Lane `probe-i-program-opus`, investigation only. Subject: red-triage-3 section I,
`seon.program-test/indexed-and-evaluated-declarations-are-the-same-entities` (test/seon/program_test.clj:1421).
Default ran b86a85c36. The probes ran on the disposable branch `:probe-i-program`, which is now retired. No source or
test file changed.

## Verdict

Neither suspect is the cause. 707663e43 changes only the final writer (`validate-write-report`). 8cedbee81 changes
only when a settlement is answered. The run never reaches the writer: it is refused while `defn left` is evaluated,
inside the install gate.

The producer is **47d9d4419** (09-26 19:19 -0600, 01:19 UTC, inside the red's 00:18–02:15 UTC window). It made
`seon.sci.eval/fork-cluster-ctx` call `acquire!` whenever the base context is acquired (src/seon/sci/eval.clj:3809).
The gate's disposable copy (`disposable-copy`, eval.clj:5008-5030) is such a fork. That acquisition swaps the
evaluating context's program and projection for those of the copy's committed basis, which does not yet hold the
run's earlier declarations.

## The chain, with the value at each step

1. `turn/evaluate-sources` gives each form the run as its settlement will leave it: the earlier rows are applied
   with `with-settled` (`db/with`, turn.clj:5075-5085), so `defn left`'s `database` is an **uncommitted** value
   that holds `:sample.s1/value`.
2. `gate-function-install` (turn.clj:3398-3414) calls `sci.eval/evaluate-candidate` with that database.
   `disposable-copy` then opens an isolated branch `:seon.cluster.registry/from (db/commit-id database)`
   (eval.clj:5020). For an uncommitted value, `db/commit-id` is its **basis** commit. The copy branch is therefore the
   committed value from before the run, without the run's rows.
3. `disposable-copy` supplies the base's `(context-projection ctx)`, and that projection holds the run's
   `register!`. `fork-cluster-ctx` then calls `acquire!` (eval.clj:3809). The base's acquired value is the
   uncommitted one, so `commit-id-of` = nil, and `acquired-database?` (eval.clj:3515-3524: "A value without committed
   identity never matches") answers false. `evaluate-native!` therefore regenerates the copy from the copy database
   and replaces its projection (`advance-context-projection! … (:seon.schema/projection generated)`, eval.clj:3648).
4. `install-candidate-function!` (eval.clj:4863-4870) reads `(context-projection copy)` and passes it to
   `projection-with-function-contract`. `projection-registry` throws, and the error names `:sample.s1/value`.

Stored frames of the refusal (fault 55809 on the probe branch, `:seon.turn.loop/failed-step
seon.schema/projection-registry`): `schema.clj:614 ← projection-with-declarations:2758 ←
projection-with-function-contract:3541 ← sci.eval/install-candidate-function!:4870 ←
sci.eval/evaluate-candidate:5067 ← turn/gate-function-install:3414 ← turn/evaluate-sources:5124 ← resume-turn:5322 ←
turn/turn:5716`.

## REPL proof (default, JVM mode, branch `:probe-i-program`)

- Repro as one hosted run (MCP SCI, branch): `(ns sample.s2 …) (seon.schema/register! :sample.s2/value [:int {:min
  -8 :max 8}]) (declare right) (defn left … [:=> [:cat :sample.s2/value] :int] …) (defn right …)`. Ordinal 0 carries
  "Schema reference :sample.s2/value is not declared in the candidate projection; it is named by sample.s2/left"
  and ordinals 1-4 are nil. This matches the red's shape.
- `register!` alone, in its own run, persists its row, and `carried-projection` holds it. `(#'seon.turn/settled-value
  db row)` for a schema row gives a value whose `carried-projection` holds the key (158 ms). The settlement step works.
- After the refused run, the hosted context's snapshot value is uncommitted (`basis-t` 536877381, above the committed
  536877380; `committed-value-identity` nil; `db/commit-id` 6ab88c31… = the run's basis). Its projection holds
  `:sample.s2/value`.
- `(#'seon.sci.eval/disposable-copy ctx that-value conn agent)` gave `{:copy-db-commit 6ab88c31…, :copy-db-s2 false,
  :copy-proj-s2 nil, :copy-snapshot-s2 false}` (about 150–200 ms, released after). The base projection held
  `[:int {:min -8, :max 8}]`; the copy lost it.

## Same producer, other reds

red-triage-3 "defect 2" is `cluster.host-test/a-run-reads-its-earlier-declarations`: "Unable to resolve symbol:
run-square", with suspects 47d9d4419/f7980f60c. It is the same mechanism for functions. The second `defn run-square`
is gated in a copy that re-acquired the pre-run program, so the first `run-square` and `run-square-test` are absent.
The evaluate-source NPE that coincides with it is expected to go with this fix; root's central run confirms or
refutes that.

## Smallest fix (owner: src/seon/sci/eval.clj)

A fork acquires only a value that is **newer than the base's own**. A fork onto the commit the base's acquired value
is based on already holds that program (plus the run's uncommitted rows), so it keeps the base's program and the
supplied projection. At eval.clj:3809:

```clojure
(when (let [snapshot @(::kernel/program-snapshot ctx)]
        (and (::acquisition snapshot)
             (not= (db/commit-id db) (db/commit-id (:seon.db/db snapshot)))))
  ...acquire! as now...)
```

Why the change is this small: for two committed values at one commit, `acquire!` already answers at once, so the
condition only changes the case where the base's value is uncommitted over `db`'s commit. That case is exactly the
gate copy. Turn forks after a settled or refused run fork onto a newer commit, so they still acquire (the refused
run's close commits: 536877380 > 536877379 above). An alternative is to scope the skip to `disposable-copy` with an
arm-request key, but that is a schema change to `fork-cluster-ctx`'s third arity for no gain.

## Regression (one, class-level)

The existing reds already assert the wanted behavior. host-test `a-run-reads-its-earlier-declarations` covers
functions and program-test :1421 covers schemas. The one missing class regression is a schema version of the
host-test, in test/seon/cluster/host_test.clj. One hosted run
`(seon.schema/register! :probe.run/v [:int {:min -8 :max 8}]) (defn run-v {:malli/schema [:=> [:cat :probe.run/v]
:int]} [x] x) (run-v 3)` shows `"3"` last and no refusal.

Focused tests for root: `--test seon.program-test/indexed-and-evaluated-declarations-are-the-same-entities`,
`--test seon.cluster.host-test/a-run-reads-its-earlier-declarations`, the new host-test, and
`seon.sci.eval-test`'s 47d9d4419 regression (a fork of an acquired base holds rows committed after it). That
regression must stay green, because the fix keeps its case: its fork is onto a newer commit.

## Out-of-scope findings (for their owners; not recorded as tasks, since this lane owns no task file)

1. **The copy's database lacks the run's rows even after the fix** (`:copy-db-s2 false`). A gate test that writes a
   fact under an attribute declared earlier in the same run would be refused on the copy branch. Owner:
   src/seon/sci/eval.clj `disposable-copy`. The fix would transact the run's applied rows onto the disposable branch.
   This is pre-existing, not a regression.
2. **A gate throw refuses the whole run, not its form.** The failure escapes `evaluate-sources` as a phase failure
   (`turn/phase`, turn.clj:3551-3567). It lands on ordinal 0 and every other ordinal is nil, which breaks the total-path
   law. This is the same class as red-triage-3 defect 2's "the turn step turns any such throw into the form's
   diagnostic". Owner: src/seon/turn.clj.
3. **A refused run leaves its uncommitted program in the agent's context.** After the refused run, the hosted
   context's snapshot and projection still hold `:sample.s2/value`, and nothing committed. The next run's acquisition
   (at a newer commit) replaces them, so it is bounded, but the context held a program that never existed on the
   branch. Owner: src/seon/sci/eval.clj / src/seon/turn.clj.
