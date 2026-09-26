---
type: landing
lane: fix-dogfood-api-opus, resumed by fix-dogfood-api-opus-2
created: 2026-09-26
status: 18 commits landed; root merge entrances committed (5f53079fc), unadopted; reach executor (2) awaiting ruling
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

## Resumed by fix-dogfood-api-opus-2 (2026-09-26 ~14:40Z)

### Broken or unverified first
- **Default died of OutOfMemoryError at ~14:25Z** (`data/clusters/default/logs/seon.log` ends: "Testing
  seon.cluster.registry-test … java.lang.OutOfMemoryError: Java heap space"), while a test request was
  running in it. Seconds before, this lane's SCI probe `(defn ctx-keys … {:malli/schema [:=> [:cat [:map
  [:my.program/context :my.program/context]]] …]} …)` on branch `fdao2-probe` timed out at 30 s: its
  auto-check generates `:my.program/context` values (SCI contexts, connections). Whether it contributed is
  unproven; a contracted function whose input is a context should not be generated at all (the declared
  default supplies it). Root restarted default (pid 7000, 14:29Z). Filed here, not yet as an issue.
- **Nothing below ran through the new entrances on default**: default runs archive 5bed888d0, older than
  5f53079fc. Proven at the REPL instead (JVM mode, scratch ns `fdao2.scratch`, read-only, the entrances'
  bodies on real data); validated real values against the new schemas; contracts compile.
- **Unverified member supply**: root's SCI context must carry `:seon.store/store` and
  `:seon.agent/routing` (read by `my.program/supplied-context` from the environment); `prepare-merge!`
  needs both (`:seon.agent/context-source`). If root's first `prepare-merge!` refuses on either, that is
  the supply gap to fix in `supplied-context`.
- **Task 2 is live-verified as already fixed by 266b52353** (probe branch, five forms: `doc` summary,
  `breaks` plan with its issue, the `ns-unmap` refusal on its own form, `(fc 1)` → `2`). Still open from
  that issue: the refusal is **stored as a core fault** (branch-errors signature `6e0c6d4e…`,
  `seon.db/transact!`) and its text carries "Contract finding: seon.db/transact! returned this error, and
  none of its declared error alternatives matches it." Model fix, not landed: the run's working value in
  `evaluate-sources` already settles each form's row (`with-settled`) and silently drops a refusal; decide
  there instead — a row `settled-value` refuses becomes that form's refusal through
  `sci.eval/refuse-install` and never reaches `settle-batch!`, so settlement is never refused by a
  declaration and `settle-batch-refusal!`'s ordinal search (`refused` reduce, `own?` cases) is deleted.
  Needs `refuse-install`'s input contract (sci/eval.clj:4543, hunks via root) widened from
  `:seon.test.accretion/install-refused-error` to also admit the writer's refusal.
- **Custody from root's row vs. cluster name**: `my.issue`'s home and `message/send!` conjure another
  branch's connection (`db/branch-connection`), which the 2026-09-26 law "Authority comes only from held
  custody" forbids. Not this lane's files.
- The 60 KB rendering of an ordinary JVM-mode refusal (`seon.db/db refused connection … got nil`) is ugly
  output (every explanation nested into requery forms).

### What landed
| Commit | What | ± |
|---|---|---|
| 5f53079fc | `my.program/proposals`, `prepare-merge!`, `accept-merge!`, `write-back!`; `seon.cluster.source/prepare-merge!` selection arity; `:my.program/{proposal,merge-proposal,accept-request,held-branch,custody-refused-error}`; propose-merge! content is a sentence | src+schema +159/−13, tests +25/−2 |
| 78f9d9802 | `evaluation-facts`: an `:error` outcome settles its shown refusal as the receipt's error (root's unapplied `tmp/turn-evaluation-facts-refusal-error.patch`, judged sound: one rule over the record's outcome, not an install case) | +6 |

Over the ~100-line guide with no deletions: about half is docstrings and contracts (prepare-merge!'s
output schema is inlined twice because `seon.source.edn` is not this lane's). The smaller alternative:
drop `proposals` (root reads `my.message/inbox` and the sender's row by hand) and the selection arity
(−45); both were asked for.

Authority: `accept-merge!` and `write-back!` refuse unless the context's connection holds
`(seon.cluster.branch/cluster-branch (:seon.cluster/name context))`; a branch agent gets
`:my.program/custody-refused-error` naming `:my.program/context` and the branch it holds.
`prepare-merge!` is not guarded: it prepares onto the head the caller holds, within its own custody.
Pending proposals = messages whose proposing branch is still linked in the roster; `:seon.turn/handled`
cannot say it, since root's wake turn marked 276dd9433eb3 handled without merging it.
Selection design: refusal, not automatic closure — a selected declaration reaching an unselected change
refuses naming the reachers and the unselected changes, so root sees exactly what merges.

### REPL evidence (default pid 7000, archive 5bed888d0, JVM mode, scratch ns)
- proposals query + roster filter on the live db: 0.74 ms →
  `[{:seon.message/id "276dd9433eb3", :seon.issue/id "7bae492de6db", :seon.store/branch :dogfood-repl-opus-12, :seon.agent/id "mcp/dogfood-repl-opus-12"}]`.
- three-way on `:dogfood-repl-opus-12` against the head: 691 ms; delta exactly
  `#{[:seon.test/sym my.plan-test/current-refuses-an-unknown-agent-by-name]}`, no conflict; its reach with
  that selection `#{}` (0.08 ms).
- probe branch (`fc` calls `fh`): delta `#{fc fh}`; selection `#{fc}` → reach `#{fc}` (refuses, 269 ms
  cold); `#{fh}` → `#{}` (7 ms); `#{absent/x}` → not a change (refuses).
- `seon.contracts-compile-test/check` over the five touched files against the checkout projection: 0
  findings, 518 ms (it first caught an unregistered error member, fixed with `:my.program/held-branch`).
  `valid-candidate-value?` true for a real proposal, a custody refusal, a selection refusal and a
  merge proposal.

### Root types, at its own REPL (MCP `eval_clj` mode `sci`, no branch), after adopting 5f53079fc
```clojure
(my.program/proposals)
(def proposal (my.program/prepare-merge! {:seon.store/branch :dogfood-repl-opus-12 :seon.issue/id "7bae492de6db"}))
(select-keys proposal [:seon.test/passed? :seon.source/tally :seon.test.run/id])
(def accepted (my.program/accept-merge! proposal))
(my.program/write-back! {:seon.source/accepted accepted :seon.fn/root "/Users/sean/src/seon"})
```
Then unlink the proposing branch with the MCP `branch` tool (`retire dogfood-repl-opus-12`); a REPL
entrance for retiring a hosted branch does not exist yet. To merge only part of a branch, add
`:seon.program/identities #{[:seon.test/sym my.plan-test/current-refuses-an-unknown-agent-by-name]}` to
the `prepare-merge!` map. Expected risks: `accept-merge!` refuses if the merged test names a task the
cluster has not admitted (`seon.issue/candidate-dangling`; issue 7bae492de6db was authored on the
branch); write-back refuses `:file-changed` if `test/my/plan_test.clj` differs from the analyzed bytes.

### Root runs (this resumption)
```
bin/test-check default --test seon.cluster.host-test/root-reads-a-branch-agents-proposal-and-only-roots-custody-accepts
bin/test-check default --test seon.cluster.host-test/a-refused-install-is-its-receipts-error
bin/test-check default --test seon.cluster.write-back-test/an-agent-addition-merges-and-writes-back-both-declarations
bin/test-check default --ns seon.cluster.merge-test
bin/test-check default --ns seon.cluster.source-test
bin/test-check default --test seon.contracts-compile-test/every-declared-contract-compiles-against-the-packaged-projection
bin/test-check default --test seon.schema.edn-test/every-packaged-schema-resource-reads-as-edn
```
Branch `fdao2-probe` retired.

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
