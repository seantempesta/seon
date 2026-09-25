---
type: landing
lane: fix-custody-round4-opus
status: committed-unrun (A, B, C)
date: 2026-09-25
authority: docs/research/agent-platform/custody-and-ingestion-p1-review-2026-09-25.md (Astra de1075aa3) findings 1-3; takes over lane-fix-ingestion-p1s-2026-09-25.md round 4
---

# Lane fix-custody-round4 (2026-09-25)

Each owner that writes a branch it opened now binds that branch's custody once, around its
existing body (finding 2). The runner's own refused recording writes are stored, delivered
core faults, and declared caller mistakes still return (finding 3). Finding 1's agent half
(C) is **not done**: it waits on root's signal that impl-repl-s1-revise-2-opus has committed
the binding in `record-acquisition-refusals!`. Under the LOAD RULE no test request ran.

## Broken or unresolved first

- **No test ran.** Everything below is lint (0 errors), small read-only REPL probes and
  reading the code. Nothing was loaded into default. Default is `:faulted` and reports
  "JVM RESTART NEEDED: loaded dependencies changed (reference-code/sci)", from another lane's
  submodule edit. **RESTART NEEDED** before root's runs; it is not a reset.
- **C, finding 1 agent half: done (see C below); the original blocker note follows.** The `acquire-context!` wrapper
  (`src/seon/cluster/agent.clj`, the `acquired (db/call-with-custody …)` binding) stays until
  eval.clj binds custody at its recording owner. Removing it first would reopen the defect.
  The planned regression is below.
- **Line budget.** Source adds are 87 raw, but only 59 ignoring whitespace; most of the
  difference is re-indenting the wrapped bodies. C still needs its regression, about 35 test
  lines. I stopped here, as the assignment's ~60-line rule asks.
- **Finding 3's core-fault half has no regression, and none exists without a test-only seam.**
  Root ruled out Var redefinition. I probed for a real internal refusal the runner reaches
  without the caller making a mistake:
  - *Unlinking the recording branch mid-run.* Datahike refuses to delete a branch with an
    open connection: `:branch-has-active-connection`, `datahike/versioning.cljc:311`,
    datahike `4157916b`. Observed on default in a disposable isolated branch, which I then
    released and retired; the roster is clean.
  - *A completion value the writer refuses.* `commit-results!` writes through `record-tx`
    (runner.clj:1586). Members are keyed by symbol, not by a ref to the test row, and
    result strings are unbounded (`seon.test.edn:43`). So a concurrent change to the program
    cannot make the completion refuse.
  - *A released recording connection.* The writer refuses it as "not live" (db.clj:4814).
    But the caller owns that connection, so releasing it is the caller's mistake. `fault!`
    also cannot store into it, which makes it a panic in both modes, not a durable occurrence.

  The caller-mistake half is asserted (source_test: a foreign recording connection returns
  the refusal and does not panic). The core-fault route stays source-reviewed only.
- **`record-interrupted!` in `run-batch`'s catch is unchanged.** Its refusal is still only
  suppressed onto the rethrown primary throwable. That throwable already reaches the caller
  loudly. Routing it through `fault!` under `:panic` would replace the primary failure.
- **Exit-watcher panic.** Under `:panic`, a refused exit write now throws on the watcher's
  virtual thread, after the branch release, having already been stored and delivered.
  Nothing joins that thread. The fault record is the visible evidence, and the cleanup
  failure is still recorded in a `finally`.
- **Bound measured.** The disarm test now disarms inside one nested `with-database`. That
  isolated acquisition costs 51–59 ms plus 8–9 ms to release (three warm runs on default,
  pid 32831). The number is next to the 12,000 ms bound.

## A. Finding 2 (commit 3c4952503)

**Owner bindings.** Each body is bound once, to the connection it writes:

| Owner | Binding |
|---|---|
| `source/publish!` scratch | The whole scratch body: schema, population, issue and task ingestion, seal. Inherited from the stopped lane; verified. |
| `source/record-results-at-head!` | Both branches of the recording body: completion and admission. Inherited; verified. |
| `registry/record-fork!` | The fork's cluster and config write, which covers `ensure-cluster!` and `refork!`. Re-indented. |
| `agent/record-interruption!` | The recover transaction only. It is still reached after `stop-graph!`'s join, so join-before-record holds. |
| boot accretion | The provisional connection's `require-coherent-program!` and `accrete-schema-population!`. |
| boot `stand-cluster-runtime!` | Recovery, `seed-cluster-facts!` and `seed-root-agent!` in one binding. |

**Two boot scopes, not one.** The provisional connection is released before the runtime
connection opens. `connection-id` dereferences the connection (db.clj:320), so a single
binding would dereference a released connection.

**Why boot's binding stops at seeding.** It does not cover arming, the flow launcher or
`serve!`. Flow procs run on `FutureTask`s that convey no bindings
(core.async `dc35f3e0d`, `flow/impl.clj:29`), but `future` and `thread` do convey them.
Widening the scope would change what later threads inherit. Astra named only `:61/:72/:74`.

**Tests:**
- The gate regression's candidate write calls the real `db/transact!` and asserts `:db-after`.
  This was the stopped lane's edit, kept.
- `source_test` `with-store`: every publication regression now asserts that the enclosing
  `with-database` custody's basis is unchanged, while `publish!` writes its own scratch and
  head.
- `agent_test`:
  - The interrupted-disarm regression now disarms inside a nested `with-database`, which is
    another branch's custody. It asserts that branch's basis is unchanged and that the turn is
    closed on the graph's branch.
  - Both release regressions lose their per-caller `call-with-custody` wrappers. They are
    redundant since 4ebea3977 (`transacted!` binds its own connection) and they masked the
    release path. Release and arm now run under the parent's custody.
- `registry_test`: the direct `db/transact!` on the forked branch uses `transacted!`. That was
  Astra's noted exception, `registry_test.clj:146`.

## B. Finding 3 (commit 4c8e3588e)

`seon.test/run` has one `own-write!` for admission (`admit-run`), completion
(`commit-results!`), stop (`release-not-started!`) and exit (`record-exit!`, on the watcher
thread).

**Returned as caller mistakes.** Two refusals are the caller's own mistake and are returned
as before:
- a refusal carrying `:seon.test/admission-refusal`, which is admission's declared evidence
  disagreement (test.clj:1066);
- the writer's foreign-connection refusal, `[:seon.error/data :seon.db/ambient-branch]`
  (db.clj:473), meaning the recording connection is outside the caller's custody.

**Everything else is a core fault.** It goes through `seon.fault/fault!` (fault.clj:179):
- The environment is built by `env/environment` from `recording`, `cluster` and `effective`.
- The throwable is an `ex-info` whose data is the full refusal and whose cause is the
  refusal's `:seon.error/throwable`, when it has one.
- `fault!` records and delivers the fault. It throws under `:panic`; under `:record` the
  refusal still flows into the existing `recording-refusal` path, so `passed?` stays false.
- The watcher's separate `fault/record!` closure is deleted (7 lines).

No new union, no retry.

## D. Duplicates deleted

| Wrapper or duplicate | Lines |
|---|---|
| boot: three per-call `call-with-custody` wrappers collapsed into one | −2 bindings |
| test.clj: the watcher-only `fault!` recorder, replaced by the one policy | −7 |
| agent_test release regression: redundant outer custody wrapper | −1 (plus parens) |
| agent_test concurrent arm/release: redundant custody wrapper | −1 |
| acquisition_test `declare!`: redundant `(call-with-custody {} …)` around `transacted!` (commit d5fbee2ce) | −1 |
| C: agent.clj `acquire-context!`'s acquisition wrapper | −2 |

**Kept, and why:**
- agent.clj's `(call-with-custody {} …)` around the branch-assignment write: it is the
  acquisition owner's own write.
- Whole-body custody in the fixtures of acquisition_test and entrance_supplier_test: they are
  the world's own work.
- Wrappers in files outside this row (cluster.clj gate, test.clj checkpoint/overlay,
  fault.clj, namespace_agent_loop_test, task_ingestion_test): these belong to other owners
  and were not touched.

## Size (whitespace-ignored in parentheses)

| | Added | Deleted | Net |
|---|---|---|---|
| Source | 87 (59) | 56 (28) | +31 |
| Tests | 42 | 26 | +16 |

## REPL evidence (default JVM, read-only; throwaway in-memory Datahike databases, released)

1. **The guard.** `seon.db/foreign-connection-error` under default's custody, writing a
   memory connection, returns the refusal "This write names branch :db, which is not the
   writing cluster's branch :cluster-default…". Under `call-with-custody` of that connection it
   returns `nil`. 30 ms.
2. **Classifier keys.** A real `db/transact!` under the foreign custody returns keys
   `:seon.db/transaction-refused`, `:seon.error/at`, `:seon.error/data`… It has
   `[:seon.error/data :seon.db/ambient-branch] = :cluster-default` and no
   `:seon.test/admission-refusal`. 4 ms.
3. **Environment.** `env/environment` over `{cluster-name, connection, caps}` gives
   `environment? true`. 3 ms.

## C, finding 1 agent half: committed with the wrapper deletion, after eval.clj e8cada147

`test/seon/cluster/agent_custody_test.clj`,
`an-acquisition-records-its-refusal-on-the-acquired-branch-under-another-custody` (+66 lines).

**Setup.** Two isolated children of the member's handle. Each gets the real poison row from
`eval_test`, `bad`, which calls `missing-dependency`, plus one good row.

**Two paths, each under the parent's custody:**
- **Agent entrance.** `acquire-context!` of the child's agent. It throws if it cannot record.
- **Lazy invocation.** A fresh `cluster-ctx` on the second child, then `kernel/invoke` of
  `good`, which returns 42. Acquisition runs at the first named call (kernel.clj:606).

**Assertions.** Each refusal is read back from its own child through
`:seon.error/occurrences` / `:seon.sci.eval/row-member`. The parent's basis is unchanged, and
no refusal was recorded on the parent.

**Why two branches.** `record-refusals-once!` records once per program identity and recording
target. A second path on the same branch would therefore record nothing new.

**Expected.** Both halves are green now that the recorder binds custody itself (e8cada147).

**Done after root's signal (e8cada147):** agent.clj's acquisition wrapper is deleted (+2/−3), in
the same commit as this test. **Unmeasured:** two fresh acquisitions. One complete acquisition was measured at
1,090 ms (branch_execution_test); the test runs under the default 5 s bound.

## Registry-test red (verify-1935 008, verify-1955 001): the cause is the fixture, not round 4

- **The symptom.** 12 errors, every one at `with-source-store`'s marker write
  (registry_test.clj:120). That is before any `record-fork!` and away from the line round 4
  changed.
- **Reproduced at the REPL without round-4 code**, on disposable file stores under
  `tmp/fix-custody-round4-opus/` (deleted):
  - The fixture declares only three schema rows.
  - Its store then declares a 3-form program, and `carried-projection` has 3 forms.
  - `transact-call` reads `(m/properties (mr/schema registry :seon.config.db/write-time-limit-ms))`
    (db.clj:4546) and compiles nil. That is the `{:schema nil :form nil}` Root saw.
- **Not a wrong-branch lookup.** The projection is the fresh store's own `:db` value.
- **Two alternatives, both refuted:**
  - Declaring the write bound alone: the validation bound then refuses by name.
  - The full canonical population: refused ("Render declarations name functions absent from
    the final program"), and 3.2 s.
- **Fix.** `probe-schema-rows` also seeds the writer's dial family, `seon.config.db`, which it
  selects from the projection's forms by the namespace db.clj reads, not from a hand list.
  That gives 7 rows.
- **Probe after the fix.** The marker write, `branch!` to `:current-src`, and `ensure-cluster!`
  through `record-fork!` all commit.
- **Regression.** The 12 fixture-using registry tests are the class regression.
- **Writer diagnostic, out of scope.** It is filed at
  `docs/seon/issues/a-program-without-the-writers-dials-crashes-every-write-as-an-unknown-outcome.md`
  (owner seon.db).

## Commits

- 3c4952503: custody, each owner binds its own branch work once (finding 2).
- 4c8e3588e: test run, one recording policy (finding 3).
- d5fbee2ce: acquisition test drops a redundant wrapper.

## Root runs (serially, after the JVM restart)

```
bin/test-check default --ns seon.cluster.source-test --include-long
bin/test-check default --ns seon.cluster.merge-test --include-long
bin/test-check default --ns seon.cluster.registry-test
bin/test-check default --ns seon.test.publication-test
bin/test-check default --test seon.cluster.agent-test/release-joins-every-owned-graph-before-it-closes-the-branch --include-long
bin/test-check default --test seon.cluster.agent-test/concurrent-arm-and-release-never-deadlock --include-long
bin/test-check default --test seon.cluster.agent-test/disarm-interrupts-an-in-flight-turn-and-records-it-interrupted --include-long
bin/test-check default --ns seon.test.one-request-test
bin/test-check default --ns seon.cluster.acquisition-test --include-long
bin/test-check default --ns seon.cluster.agent-custody-test   # after C lands
```

A boot under an outer custody has no committed regression. The ordinary external boot has no
ambient custody, so `bin/seon down && bin/seon start` proves only that boot did not regress.
