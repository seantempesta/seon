# Lane fix-unloaded-host-bound-row (2026-09-24)

A branch acquisition interprets its changed rows plus their reverse closure
(`seon.sci.eval/acquire-program!`). `install-row!` refused every host-bound
row in that set, and a `:core` refusal refuses the whole acquisition, so every
test and evaluation on the branch refused. This lane changes the rule so a
host-bound row refuses the acquisition only when it overrides a compiled Var
(namespace loaded and the row's own definition changed). Otherwise it is bound
to a Var that throws the named refusal when called.

## Still broken (not this lane)

- **Interpretable affected callers fail to analyze** against uninstalled
  bindings: private helpers outside the closure, non-function Vars, and
  dependency aliases such as `async/chan`. Any of these still refuses a
  branch's whole acquisition. Filed as
  `docs/seon/issues/affected-callers-fail-to-interpret-against-uninstalled-bindings.md`.
- `seon.bootstrap-test` is still red for foreign reasons, the same before and
  after this change: fixture writes are refused with `:seon.error/unknown`
  where `:seon.program/definition-digest` should be, plus one bound overrun and
  one assertion.
- `seon.sci.branch-execution-test/branch-definitions-and-unchanged-callers-share-one-context`
  errors with "Effective configuration requires a matching cluster row with
  every required dial". Save-hook output on default showed
  `database-effective-config` refusing on a missing
  `:seon.config.agent/turn-completion-backstop-ms`, which is foreign.
- The save gate for this edit ran 65 reaching tests: 33 failed and 30
  errored. Most are fixture-write refusals and none mentions host-bound
  installation. The gate therefore stayed red and did not certify the change.

## Cause, verified on default

- The recorded failures (`tmp/fix-test-worlds/after/seon.db-test.txt`,
  `tmp/orchestrator/integration-2026-09-24/start-head.txt`, 27 receipts) read
  "must change through the loaded source files". That is the overridden
  branch: the row for `seon.cluster.reload-measure/-main` had been published,
  but that commit was not yet the loaded one. `(find-ns 'seon.cluster.reload-measure)`
  is nil in the JVM, so no compiled Var existed to override.
- The same refusal also fires for an unchanged caller. `seon.fn/reverse-closure`
  of `seon.render.hiccup/class-value` reaches 77 declarations, 21 of them
  host-bound, including `reload-measure/-main`, `seon.render.hiccup/->string`
  and `seon.cluster.boot/start!`. Before this change, each of them refused the
  branch.
- The first hypothesis ("unloaded namespace, so no compiled Var, so
  interpreted") was half right. On the current head the row equals the loaded
  commit and is not overridden, so nothing refuses at rest. The refusal needs
  either a publication that is ahead of adoption or a changed callee.

## Change

`src/seon/sci/eval.clj` (+37/-3):
- `install-refusing-root!` binds the symbol to an SCI Var whose function
  throws the `:seon.sci.eval/interpretation-error` as `ex-data`. Callers
  analyze normally; a call that reaches it refuses by name. The load result
  is `:unavailable` with the message.
- `install-row!` computes `unbound?` for each declaration. It is true for a
  `:core` row at acquisition (not an evaluation) that is host-bound and
  interpreted, and either sits in a namespace this JVM has not loaded or has
  its own definition equal to the loaded program's (`overridden-row?`).
- A changed host-bound row in a loaded namespace still refuses the
  acquisition by name (M9 and agent-test recipes, unchanged).
- Agent rows and evaluations still get the returned refusal. This matters
  because an agent's own namespace is never JVM-loaded.
- Nothing is loaded from files during acquisition, since no reload may run
  under an evaluation.

`test/seon/sci/branch_execution_test.clj` (+41):
`an-unoverridden-host-bound-declaration-refuses-by-name-only-when-called`.
It stamps new digests on `seon.render/invoke-producer` (interpretable; its
caller `seon.render/render-form-value` is host-bound and loaded) and on
`seon.cluster.reload-measure/measured-request` (host-bound, in an unloaded
namespace, reached by `-main`). It asserts all four declarations are in the
interpreted set and the refused set is empty, and that calling each
host-bound one throws with its own `:seon.sci.eval/refused-function`.

## Evidence (default, `bin/test-check`)

| Run | Result |
|---|---|
| before `--ns seon.bootstrap-test` | 31 pass / 4 fail / 4 error; host-bound message absent at that moment (no seed changed then) |
| regression with the new branch disabled (`(and false unbound?)`) | run be1649e9c626: 4 fail; refused `#{measured-request -main render-form-value}`, and calling returns no named refusal |
| regression with the fix | run fbb7f1c6e1a5: 9 pass, 4600 ms |
| host-bound set: regression, M9 override, M9 adoption, `a-refused-core-row-is-recorded-and-every-agent-still-arms` | run 3d85da78db53: all pass (the fifth member's error is the foreign config one above) |
| after `--ns seon.bootstrap-test` | 34 pass / 5 fail / 3 error; 0 host-bound or reload-measure lines |

Cost: the new work runs only for rows that are already host-bound and
interpreted: one `find-ns` and one `overridden-row?` pull per such row. No
per-acquisition cost was added for other rows. I had no parent/child timing
of the same acquisition, because the parent refuses the acquisition this
probe measures.

## Limits

- Adoption into default was delayed about 10 minutes because other lanes
  changed files during adoption ("Source changed during development
  adoption"). Every result above ran after `seon.sci.eval/install-refusing-root!`
  resolved in the JVM.
- The ten regression tests in `branch_execution_test` are all `:seon.test/long`,
  so `--ns` without `--include-long` executes 0. I ran them by name.

Net lines: +78 source/test, plus this note and one issue. RESET NEEDED: no.
