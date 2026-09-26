---
type: landing
lane: fix-dogfood-api-opus
created: 2026-09-26
status: 16 commits landed; breaks slice held on a schema grant; reach executor (2) awaiting ruling
---
# fix-dogfood-api: the agent-facing my.* API on the dogfood path

## Broken or unverified first

- **Nothing in this lane is proven on a running default.** Default ran archives fbd0897df → 33ea69298 →
  a1b01c92a → 10d6fdff5 during the lane, all older than these commits (hook publication off). Each fix was
  reproduced through MCP (SCI on a branch as the hosted agent) *before*, probed at the REPL on real data in
  scratch namespaces (unarmed copies; each return validated against its declared schema), statically
  checked (contracts-compile 0 findings per slice; schema EDN read), and given an agent-way regression in
  `test/seon/cluster/host_test.clj`. None of those regressions has run. **Root runs** the list at the end.
- **Reach executor (item 2) is not fixed**; options sent to root. Two false-result paths exist today:
  the entry gate re-evaluates every gate test's source in the candidate SCI (`sci/eval.clj`
  `run-candidate-test!`), so file tests fail on unresolved aliases; and `seon.test/run` runs a `:core`
  test's compiled Var, which calls the *compiled* function, not a branch's `:agent` override of it.
- **breaks / ns-unmap! slice** is written but uncommitted (my.program.clj, host_test regression in
  `tmp/fix-dogfood-api/breaks-regression.clj`): `:seon.program/plan` requires `:seon.program/launch`
  (`seon.program.edn:237`), whose producer the slice deletes. Waits on the grant to make it optional.
- **In-run reads through `my.*`** (`tests-reaching` inside the same run) still read call preparation's
  connection-head supply (D7, `call_preparation.clj:1203`). The in-run *gate* is fixed; the reads are not.
- **Refused settlement** is still recorded as a stored core fault (`error/recording`), though it is a declared
  agent refusal, and Datahike's writer `:error` log still reaches MCP replies. Outside this lane's hunk.
- **Hosted agent ids** must become `mcp/<branch>` and a cluster row must claim no stewardship
  (ruled; pipeline lane's files). Until then, `mcp/default` on a second branch refuses ("the agent's cluster
  row names another branch").
- Found and filed by others, confirmed here: a renderer refusal renders through itself ~8 levels deep
  (`a-renderer-refusal-renders-through-the-same-renderer-until-the-depth-bound.md`); seqs print as
  `#object[...KeySeq]`; the hosted agent stays `agent-already-running` after a JVM replacement interrupts
  a run (branch had to be retired).

## Before (MCP SCI, branch of default, exact shown text)

| Form | Archive | Shown |
|---|---|---|
| `(my.test/check {})` | fbd0897df | render refusal nested ~8 deep; underlying (caught): `The test world's program overlay (11 entries) was refused: Nothing found for entity id [:seon.cluster.eval/id "49e526b52349"]` |
| `(my.test/run)` after `defn fda-sq` + `deftest fda-sq-test` (separate runs) | fbd0897df | `seon.sci.kernel/failure-value refused … Fix: Nothing found for entity id [:seon.cluster.eval/id "a5592b114dba"]` |
| `(my.program/tests-reaching {:seon.program/subject 'user/fda-sq})`, next run | fbd0897df | `{… :seon.program/gating [user/fda-sq-test]}` (works across runs) |
| one run: `defn fda-sq (* x x)` / `deftest` / `tests-reaching` / `defn fda-sq (* x x x)` / `(fda-sq 4)` | 33ea69298 | `#object[sci.lang.Var]`, `#object[sci.lang.Var]`, `my.program/locate refused … No program declaration names user/fda-sq`, `#object[sci.lang.Var]`, `64` |
| `(in-ns 'seon.id)` + `defn fda-probe`, then pull seon.id's row | 10d6fdff5 | `{:seon.ns/source "(in-ns 'seon.id)", :seon.schema.admission/source :agent}` (file, doc, source gone) |
| selection of `(my.test/check {})` on a branch (JVM `seon.test/select`, :incremental) | fbd0897df | 2057 members, all `:first-run` (235 ms); arm-first-loads! then compiles every test file → `boot_test.clj:147` |

Causes found at the producers:
1. `db/call-with-custody` kept `*receipt*` across a custody switch; `stamp-receipt` then wrote the
   evaluation's lookup ref onto the test world's overlay transaction (proven: `d/with` of the overlay → 67
   datoms; plus the stamp → the exact refusal). Fixed by root's fixture lane as 9a21c6cc1 (hunk from here).
2. `check {}` was `:incremental`; with no all-green run it widened to every test.
   `test/seon/cluster/boot_test.clj:147` also called the deleted `mcp/sci-evaluation-form`.
3. Within one run, every form read `(db/db connection)`; rows land only at settlement (`turn.clj`
   `evaluate-sources`), so the gate (`gate-function-install` → `seon.fn/gate-set`) saw none.
4. `sci/eval.clj` `ending-namespace-row` minted a declaration row for any namespace switch; settlement's
   exact upsert replaced a file namespace's row.

## After (REPL-proven values; live proof pending adoption)

- Receipt fix on 33ea69298: `(my.test/run)` answered the test's result as data:
  `[{:seon.test/sym user/fda-sq-test, :seon.test/fail-count 1, :seon.test/failure-message "expected: (= 16 (fda-sq 4))\nactual: (not (= 16 64))", …}]`.
- `seon.test/changes` on the probe branch (scratch copy, 82 ms): `[[:seon.fn/sym user/fda-sq] [:seon.ns/name seon.id] [:seon.ns/name user] [:seon.test/sym user/fda-sq-test]]`
  (valid `:seon.test/changed`); `:named` selection of the branch's own identities: exactly
  `user/fda-sq-test` (`:reaches-changed`). With default's then-broken seon.id row included: 1730 members (root repaired the head).
- Working value (scratch): speculative test row 42 ms; contracted fn redefinition 65 ms warm (Datahike
  `with` + validator 58 ms; 508 ms cold); `gate-set` of the earlier function before `[]`, after
  `[user/fda-sq-test]`; the connection's commit unchanged.
- Identity-only namespace row: refused under the old `:seon.ns/ns` ("expected the required key
  :seon.program/definition-digest"), admitted under the new form (d/with + the writer's validate-report).
- Message home branch: root's row names `:cluster-default`; `db/branch-connection` resolves to the cluster's own connection.
- generator ctx: 20 fresh samples 0.53 ms, all distinct and `ctx?`.
- Unloadable namespace refusal: real `FileNotFoundException` → valid `:seon.test/not-runnable-error`.

## Commits (src+resources +443/−232, tests +185/−18)

| Commit | What | ± |
|---|---|---|
| b5741240e | boot_test uses the bridge's `run-form` | +4/−4 |
| 31db2ca69 | `check {}` derives the change from the overlay's digest diff (`digest-differences`, `seon.test/changes`), `:named` | +100/−44 |
| 24bff3388 | in-ns declares nothing: `ending-namespace-row` deleted (−15); `namespace-rows` never borrows the switch text; ns digest optional | +47/−37 |
| 544daefed | `phase`/`analyze-submitted` catch ExceptionInfo only | +5/−10 |
| d62d32bf7 | run's working value (`seon.db/with`, `prepared-write`/`write-refusal` extracted); `settled-namespaces` carry deleted | +107/−65 |
| ba730cf5f | `row-tx` asserts a declaration's absent namespace by identity | +15/−3 |
| eb093f9d6 | host_test patch from the pipeline lane | +10/−3 |
| 6ec238f5e | check regression; redundant time-limit hunk deleted | +15/−5 |
| fdd9926e8 | hosted agent is a cluster row; `send!` to the recipient's home branch; `my.program/propose-merge!` | +122/−40 |
| 548788a3e | member body thread binds its member's custody | +20/−2 |
| 266b52353 | refused settlement: identity-only receipts (seams patch), refusal on its own form | +65/−21 |
| d8e964992 | `generator-ctx` defonce deleted | +5/−9 |
| 8cf62cd13 | only an `:agent` row overrides the loaded program | +38/−3 |
| f0f1edb9e | dropped named reach refuses by name; unloadable test ns is a declared refusal | +51/−2 |
| 7c7edcbc7 | request's branch sweep submitted as detached io work | +23/−1 |
| 9f2302a4d | `settled-namespaces` schema retiring (step 1) | +1/−1 |

Deleted paths: `ending-namespace-row` (eval.clj, 14 lines), the `settled-namespaces` carry (turn.clj 4, eval.clj 6),
transact-call's inline preparation and catch (now shared, −41 in db.clj), `generator-ctx` (3), the interrupt
pass-throughs (4), host_test's time-limit hunk (5). Net growth is over the ~100-line guide in total but each
slice is small; the largest (fdd9926e8, +122) is option 2 as ruled plus its regression.

## Root runs

```
bin/test-check default --ns seon.cluster.host-test
bin/test-check default --test seon.turn-test/<the read-evidence test at :234>
bin/test-check default --ns seon.custody-stability-test
bin/test-check default --ns my.test-test
bin/test-check default --ns seon.cluster.merge-test
bin/test-check default --ns seon.cluster.turn-test      ;; evaluation-follows-the-readers-parse-time-namespace (in-ns rows)
bin/test-check default --ns seon.db-test                ;; transact-call extraction, seon.db/with
bin/test-check default --test seon.contracts-compile-test/every-declared-contract-compiles-against-the-packaged-projection
bin/test-check default --test seon.schema.edn-test/every-packaged-schema-resource-reads-as-edn
```
Then, after adoption, repeat the Before table through MCP on a fresh branch (expected: check selects only the
branch's test; in-run redefinition refused by name; seon.id's row unchanged after in-ns; a message to root
visible on `:cluster-default`). The datahike parity baseline (`test/seon/schema/datahike_parity.edn`) may need
the `:seon.ns/ns` form refresh.

Branches: `fix-dogfood-api`, `-3` retired; `-2` was removed by root's store rebuild; `-4` retired at the end.
