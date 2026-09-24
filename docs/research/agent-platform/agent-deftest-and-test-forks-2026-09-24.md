---
type: research
status: evidence — read-only study; implementation frozen
created: 2026-09-24
scope: agent deftests, task done-when as deftests, cost of a throwaway test fork
pins: seon HEAD d5843c323 (line numbers); live JVM pid 90471; datahike c79cd03a; sci fcbd8862
---

# Agent deftests and throwaway test forks (2026-09-24)

This is read-only research on `refactor/agent-platform` at `d5843c323`. It does not
authorize implementation. Probes ran over MCP JVM mode against `default` (pid 90471,
started 2026-09-23T22:18:14Z, ready 7,552 ms), in session `deftest-forks` and the
throwaway namespace `research.deftest-forks`, which was removed afterwards. They used the
disposable branch `probe-deftest-0924` (created and retired with the MCP `branch` tool)
and short-lived `agent-*`/`agent-probe-*` branches, each of which was unlinked. The roster
check afterwards found none of them left. Allocation figures are
`ThreadMXBean.getThreadAllocatedBytes` deltas on the calling thread.

**What exists:** a test run is already "an isolated agent for one body":
`seon.test/run` → `member-result` → `acquire-context!` (branch + SCI fork + custody)
→ `runner/run-var!` (clojure.test report capture) → `commit-results!` → `release-context!`.
**Data flow:** captured commit → branch → forked ctx with custody on that branch's
connection → report maps → recorded result → unlink. **Principal approach:** keep that
one pipeline, let it take a proof *source* as well as an admitted test *row*, and express
task done-when as ordinary `deftest`s.

## 1. Numbers first

| Operation (warm unless noted) | ms | MB alloc | Source |
|---|---:|---:|---|
| `acquire-context!` isolated, **first** call | 302.0 | 629.2 | `cluster/agent.clj:779` |
| `acquire-context!` isolated, warm (n=3) | 51.5–61.9 | 72.1 | same |
| — `registry/branch!` (`d/branch!`, file store) | 21.8–25.6 | 12.9 | `cluster/registry.clj:193` |
| — `store/open-branch!` | 5.3–5.8 | 13 | `cluster/store.clj:539` |
| — `projection-state` + carry | 0.15 | 0 | `sci/eval.clj:173` |
| — `fork-cluster-ctx` on a **fresh** connection | 14.5–20.9 | 22–38 | `sci/eval.clj:2732` |
| — same fork, repeated on the same connection | 2.4–4.6 | 3.8 | same |
| — `sci.eval/acquire!` | 3.2–3.3 | 7 | `sci/eval.clj:2618` |
| `release-context!` (conn release + retire + state) | 8.3–8.8 | 0.26 | `cluster/agent.clj:746` |
| bare `sci.core/fork` | 0.0007–0.007 | 0.015 | SCI `core.cljc:345` |
| `datahike.api/with` one-datom tx on default's value | 0.10–0.39 | 0.21 | Datahike `api/impl.cljc:134` |
| `runner/ambient-snapshot`, taken twice per `run-var!` | 15.8–16.6 each | 71.6 each | `test/runner.clj:595` |
| **Private deftest on a throwaway fork, whole path** (§3b) | **≈130** | **≈266** | acquire 48.7 + eval 33.7 + run 37.7 + release 8.6 |
| `seon.test/run`, one member executed (`my.agents.root/largest-test`) | **3,319.5** | 411 | `test.clj:1621` |
| — member: acquire / resolve / run / release | 56.1 / 6.5 / 49.5 / 8.8 | | `test.clj:1447` |
| — `admit-run`: `runner/program-written-since?` | 1,750 / **1,596** | | `test/runner.clj:930` |
| — `commit-results!` → `blob/with-publication!` | 620 / 617 | | `test/runner.clj:1573` |
| `seon.test/run` again, reused (`:unchanged`, 0 executions) | 229.7 | 424 | |
| `my.test/check` refusal of an unrowed symbol (SCI) | 454 | 931 | `test.clj` `select` |
| First SCI eval on a new MCP branch (`deftest` + call) | 1,057 | 1,380 | `call-preparation/derive-snapshot` 784 ms |

Four costs dominate, and none of them is the fork itself.

1. **Run overhead.** Of the 3.3 s for one member, only 121 ms is the member: acquire,
   run and release together. `program-written-since?` spends 1.6 s scanning
   `(db/since (db/history db) basis-t)` across every program attribute. Blob publication
   of the results spends 0.6 s. Both exceed one second and belong to the owner of
   `seon.test.runner`. Measure them, then remove them.
2. **The ambient drift check.** `run-var!` calls `ambient-snapshot` before and after
   (`test/runner.clj:350`). Together that is 32 ms and 143 MB for a body that takes about
   2 ms. The check walks wrapper membership, registrations and SCI sizes. Its cost is
   proportional to the program, not to the test.
3. **`d/branch!` at about 22 ms.** This is the file-store write of the new head and
   roster. It is the Datahike floor for anything that must *write*.
4. **`fork-cluster-ctx` on a fresh connection, about 15–20 ms.** The time goes to a
   `db/pull '[*]` on a new database value (6.3 ms and 16 MB on a cold cache) and to
   reinstalling the contract of every interpreted agent function (`sci/eval.clj:2780`).
   Only one such function is installed today, `my.agents.root/largest`. The fork itself
   costs 0.03 ms.

**Floors.** A proof that is a function of one database value (requirement 3.13) needs
`sci/fork` plus the body. That is well under 1 ms, or about 0.1 ms more with `d/with` if
it wants to try a speculative write. A proof that must hold a real connection pays
branch + open + fork + acquire + release, about 40 ms. Removing the drift snapshot and
the history scan brings the whole agent path to about 100 ms per test. The first
acquisition after a head write (302 ms, 629 MB) is a separate cold cost. It was not
attributed here and needs its own profile.

## 2. Census: every way tests run today

| # | Path | Entry (file:line) | Fork? | Status |
|---|---|---|---|---|
| 1 | **`seon.test/run`**: select, admit, run each member on its own branch, record, unlink | `src/seon/test.clj:1621`, member `:1447` | branch + SCI fork per member | **Live. This is the one owner.** |
| 2 | `my.test/run`, `my.test/check` (agent face) | `src/my/test.clj:5,28,43` | via #1 | Live. Thin. Rows only. |
| 3 | `bin/test-check`: one #1 request over prepl; `--gate` = save gate | `bin/test-check` | via #1 / #5 | Live |
| 4 | `bin/test --platform`: nuke a fresh root, then `test-check --isolated` | `bin/test:19` | whole JVM | Live, root-only |
| 5 | `candidate-gate!` → `reaching-run` (save gate, `refresh-source!`) | `src/seon/cluster.clj:2669,2648` | named candidate branch, then #1 | Live |
| 6 | `prepare-merge!` gate: writes the delta onto `merge-*`, then #5 | `src/seon/cluster/source.clj:736,778` | via #5 | Live |
| 7 | `plan/run-issue-tests!` (issue `:seon.issue/tests`) | `src/seon/plan.clj:690` | via #1 | Live |
| 8 | **Entry gate** `evaluate-candidate` → `run-candidate-test!` | `src/seon/sci/eval.clj:3746,3699` | **SCI fork only; runs on the agent's own live connection** | Live. **Duplicates #1 without branch isolation.** |
| 9 | Accretion auto-check (contract-generated property check at entry) | `src/seon/test/accretion.clj:72` | candidate ctx | Live. A different subject (contracts), so it stays. |
| 10 | `runner/run-vars!` / `run-var!`: report capture, drift, counters | `src/seon/test/runner.clj:350,392`; `sci/eval.clj:3670,3686` | none; the caller forks | Live. The shared capture owner. |
| 11 | `test-support/with-database`, `with-branched-database`, `fork-cluster-ctx` | `test/seon/test_support.clj:643,627,287` (219 test files reference it) | **a second branch inside a member that already has one** | Live. **Duplicate acquisition** (test-worlds note). |
| 12 | `:seon.test-support/fresh-store?` empty store for store-global subjects | `test_support.clj:592` | new store | Live, legitimately separate |
| 13 | Task done-when forms, evaluated one MCP eval at a time on `exp-base-check` | `resources/seon/tasks/seon.task.edn:6`; decoration note §87 | hand-made branch | **No code owner. A manual ritual.** |
| 14 | `my.plan.item/done-when` (prose) + `done-query` | `src/seon/plan.clj:727,1417` | none | Live. A plan checklist, not a proof. |
| 15 | Agent calls its own `(my-check)` in SCI | alias `sci/eval.clj:276-277` | none | **Broken: failures vanish (§3a)** |

**Dead:** nothing is dead in `src/`. Worker pools, slots and published test bases are
gone (`bin/test` header). **Duplicated:** #8 and #11 each run a second isolation
mechanism beside #1. #13 re-creates #1 by hand.

## 3. Can an agent `(deftest my-check (is …))` and run it on a throwaway fork?

### 3a. In its own SCI context: it runs, but failures are lost

The disposable branch `probe-deftest-0924` was used, in SCI mode through MCP:

```clojure
(do (ns probe.deftest-0924 (:require [clojure.test :refer [deftest is]]))
    (deftest my-check (is (= 2 (+ 1 1))) (is (= 3 (+ 1 1)) "deliberately red"))
    (my-check))
;; => text "nil", :outcome :ok, 838 ms, 1.38 GB; no report, no failure text
```

A second probe, `(binding [clojure.test/report (fn [m] (swap! reports conj (:type m)))]
[(is (= 3 2)) (is (= 2 2))])`, returned `:is-returns [false true]` and `:reports []`. The
agent's `binding` does not reach the reporter that `is` calls, so a red `is` goes to the
host `clojure.test/report` and never reaches the agent's envelope. `with-out-str` around
`test-var` also captured `""`. **Defect:** an agent that writes a deftest and calls it
sees `nil` whether it passed or failed.

**What was stored:** nothing. The branch database had no `:seon.test/sym` row and no
`:seon.ns/name` for `probe.deftest-0924`, which matches the MCP tool's documentation
("private SCI state, never program rows"). `(my.test/check {:seon.test/changed
['probe.deftest-0924/my-check]})` refused with `:seon.test/identity-unresolved`
("Changed identity has no definition, history or incoming edge") after 454 ms and 931 MB.
**`seon.test/run` accepts only admitted rows** (`resolve-test`, `test.clj:1264`, which
requires `:seon.test/source`, `:seon.program/analyzed-source-digest` and an admission
source).

### 3b. The same deftest *source* on a throwaway fork, through the owners: it works

JVM form, disposable: an isolated `acquire-context!` from default's commit, then
`sci.eval/evaluate` on the deftest source in the child ctx, then `sci.eval/run-test`,
then `release-context!`. The body used an elided `(seon.db/db)`:

```clojure
(deftest proof-a (is (= 2 (+ 1 1))) (is (= {:a 1} {:a 2}) "deliberately red")
  (is (seq (seon.db/q '[:find ?n :where [_ :seon.ns/name ?n]] (seon.db/db)))))
```

The measured costs were acquire 48.7 ms / 65 MB, eval 33.7 ms / 43 MB, run
37.7 ms / 158 MB (repeat 34.0 ms / 152 MB) and release 8.6 ms. The result came back as
data: `:seon.test/pass-count 2 :fail-count 1`, and
`:seon.test.failure/reports [{:type :fail :expected "(= {:a 1} {:a 2})" :actual "(not (=
{:a 1} {:a 2}))" :message "deliberately red" :seon.test/failure-identity "8c20…"}]`. The
elided `(seon.db/db)` reached the fork, since the third assertion passed. The report was
ugly in one place: `:reported-file "AFn.java" :line 154` is a JVM frame, not the proof's
source line.

**So the pipeline exists. The only missing entrance is "source instead of row".**

## 4. Design: done-when is a vector of deftests, run through the one pipeline

**Today's `:seon.task/done-when`** is a vector of forms that must all evaluate truthy
(`resources/seon/tasks/seon.task.edn:6`). Each form `f` is exactly
`(deftest done-i (is f))`, but a bare truthy form loses everything `is` gives: the
expected/actual report, `testing` contexts, several assertions per claim, `thrown?`,
properties and the declared bound. **Author deftests directly.**

```clojure
{:seon.task/id :seon.task.task/stale-docs-detector
 :seon.task/intent "…"
 :seon.task/done-when
 ['seon.task-test/stale-docs-is-a-set                    ; an existing program test (row)
  "(deftest flow-docs-reviewed
     (is (set? (seon.task/stale-docs (seon.db/db) 'seon.flow)))
     (is (every? qualified-symbol? (seon.task/stale-docs (seon.db/db) 'seon.flow))))"]}
```

A symbol names an admitted test row, which runs unchanged through #1. A string is a
**proof**: task-local deftest source that is never a row.

**One pipeline.** `seon.test/run` gains one optional request member,
`:seon.test/proofs [{:seon.test/source s :seon.cluster.eval/ns ns}]`. `member-result`
already performs the fork → run → unlink sequence (`test.clj:1447`). For a proof, the
`resolve-test` step becomes `evaluate` of the source in the member's child ctx followed
by `sci/resolve`, which is exactly the §3b sequence:

1. **Fork.** `acquire-context!` isolated from the request's captured commit
   (`test.clj:1464`): a branch, an SCI fork, and custody on the child connection.
2. **Inject.** The body's elided `seon.db` arities reach the child. Proven in §3b and by
   `run-candidate-test!`'s comment (`sci/eval.clj:3714`).
3. **Run.** `runner/run-var!` under `bounded-result` (5 s default bound, actual-exit
   join, `test.clj:133`).
4. **Record.** The key is `(proof digest, program digest)`. The proof digest is
   `seon.id/digest` of `[ns source]`. The program digest is `runner/program-digest` of the
   captured commit (`test/runner.clj:963`), or better the per-member reach digest
   (`test.clj:1210`), so an unrelated write does not invalidate the result. The result is
   recorded on the task's recording connection as a task-proof result, not as
   `:seon.test/sym` evidence, so it is never coverage and never merges (requirement 3b.2).
5. **Unlink.** `release-context!` runs after exit, as it does today.

**Failures come back as data to re-prompt the agent.** The captured result already
carries `:seon.test.failure/reports` with type, expected, actual, message, `testing`
contexts and a signature (`test/runner.clj:189`). The re-prompt renders those maps
through the result's AI renderer. Do not add a second message format. For schema claims,
write `(is (nil? (malli.error/humanize (malli.core/explain S x))))`: the `actual` is then
`(not (nil? {…humanized explanation…}))`, so the Malli explanation arrives inside the
ordinary report without a custom `assert-expr`. That form was not probed live. The
task's state stays derived: settled means every proof has recorded green for the
attempt's program digest. A proof must also be red on the base for its own reason
(3.9/3.10), which is the same request run once on the base commit.

**Proof versus guarantee.** They differ in *where the source lives*, not in mechanism:

| | Proof (done-when string) | Guarantee (promoted deftest) |
|---|---|---|
| Source lives | in the task value | an admitted `:seon.test/sym` row in a namespace |
| Selected by | the task, explicitly | reach from changed functions (`select`, `test.clj:576`) |
| Counts as coverage / merges | never | yes, gated like any test |
| Result key | (proof digest, program digest) | `:seon.test/sym` + reach digest |
| Becomes a guarantee by | root promoting the same text: ordinary entry of the deftest into the namespace (`:seon.task/guarantees`, synthesis note:77) | n/a |

**Fixing the agent's own `(my-check)` (§3a).** Advertise one function,
`(my.test/prove "(deftest …)")` or `(my.test/prove ['sym …])`. It is `seon.test/run` with
`:seon.test/proofs` on the agent's handle and returns the captured result. A bare
`(my-check)` should stop pretending: either make `is` report into the agent's evaluation,
or, more simply, say in the `deftest` docstring the agent sees that it runs through
`my.test/prove`. This is unverified: whether SCI can be made to bind the host reporter is
an open probe.

**What disappears:**

- The hand-run done-when ritual (#13): one MCP eval per form on a hand-made branch.
- The entry-gate test runner `run-candidate-test!` (#8, about 30 lines,
  `sci/eval.clj:3699`), which runs gate tests on the agent's **live** connection without
  a branch. Its caller passes the candidate's source as a proof to #1 instead. This needs
  its own design review, because the entry gate must refuse before evaluation.
- The nested `with-database` branch inside members (#11). The member's branch already is
  the throwaway world. That is a sweep across 219 files and should be its own shrink
  assignment.
- The vector-of-truthy-forms `done-when` schema (`experiments/tasks_v02.clj.txt:26`).
- The costs named in §1: the `program-written-since?` history scan, blob publication on
  every result commit, and the double `ambient-snapshot`. They go to their owners, not
  into this design.

**Estimated lines.** `resolve-test`/`member-result` accept a proof source (+25).
Proof-result schema and recording keyed by the two digests (+30, reusing
`commit-results!`). `my.test/prove` (+15). Task schema change (±5). Deletion of
`run-candidate-test!`/`candidate-test-result` (−40). **Net about +35 in `src/`.** No new
runner, registry or executor. **Measured target** per proof, once the §1 costs are fixed:
about 100 ms warm on a branch, and under 1 ms for db-value proofs on `sci/fork` without a
branch.

## 5. Limits of this evidence

- The timings come from one live JVM with concurrent lanes. Repeats are n=2–4 and no
  medians are reported.
- The 302 ms / 629 MB first acquisition is unattributed.
- The `seon.test/run` profile totals are inclusive, and the profiler recorded them during
  concurrent work.
- §3b called the owners directly from the JVM. It did not go through a turn, did not
  record the result, and did not exercise the bound on a runaway body.
- Neither the Malli-in-`is` form nor an SCI reporter binding was probed.
- Nothing in `src/`, `test/`, `resources/` or `script/` was edited.
