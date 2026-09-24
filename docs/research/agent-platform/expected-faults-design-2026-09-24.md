---
type: design
status: proposed
created: 2026-09-24
scope: How a test declares that a core fault it provokes is expected, derived from the assertions that ran rather than kept in a list.
---
# Expected faults in tests, derived from assertions (2026-09-24)

**What exists.** `seon.fault/fault!` (src/seon/fault.clj:179) records a core fault and returns a receipt,
`:seon.fault/recorded` (resources/seon/schemas/seon.fault.edn:18). Under `:panic`, the thrown panic carries
that receipt in its `ex-data`. The receipt holds `:seon.error.occurrence/id`, `:seon.error/operation` and
`:seon.error/layer`. The runner (src/seon/test/runner.clj:373) binds `clojure.test/report` for each run and
captures every `:pass`, `:fail` and `:error` event. **Approach:** a fault is expected when a passing
assertion held its receipt. This is how `thrown?` already treats an exception as expected. Any fault on the
member that no passing assertion held counts as a test error.

## The rule (option a, chosen)

A member's **consumed faults** are the set of `:seon.error.occurrence/id` values found in the `:actual` of
its `:pass` reports. An id counts when either of these holds:

1. **Predicate form.** A value among the evaluated arguments of `:actual` validates as
   `:seon.fault/recorded`. clojure.test builds this `:actual` as `(cons 'pred values)`
   (`assert-predicate`, reference-code/clojure/src/clj/clojure/test.clj:436–450). For example,
   `(is (get receipt :seon.fault/committed?))` passes with the receipt among its values.
2. **`thrown?` form.** The `:actual` is a Throwable whose cause chain carries a receipt. `thrown?` reports
   the caught exception as `:actual` (test.clj:504–516), and `seon.fault/carried-receipt` (fault.clj:172)
   already reads that chain.

A member's **observed faults** are the occurrences recorded on the member's branches, read between fork
and release. The unexpected faults are the observed ones minus the consumed ones. Each unexpected fault
adds 1 to `:seon.test/error-count`. Its failure message gives the occurrence id, operation, message and
origin branch.

What this rule gives:
- **No list, no marker.** Expectation comes from which assertions ran and passed. The receipt is
  recognised by its declared schema name, never by a symbol, and nothing enters `test-marker-attributes`
  (src/seon/program.cljc:209).
- **Proof that the test looked.** An `is` that fails, or never runs, consumes nothing. So a provoked fault
  whose assertion goes red stays unexpected and is reported beside the failed assertion.
- **Exact identity.** The key is one occurrence id: a fault's content signature on a branch. A second,
  different fault in the same body is still unexpected, even if it has the same error schema or operation.
- **SCI tests work unchanged.** In SCI, `clojure.test/is`, `assert-expr` and `report` resolve to Vars with
  the host method set and `report` is a `clojure.lang.MultiFn` (probe below). An SCI test's `is` therefore
  reaches the same bound reporter that `run-vars!` installs, and receipts are ordinary host maps.

**Declared sacrifice.** Occurrences deduplicate by content signature and increment
`:seon.error.occurrence/count`. Consuming an id therefore covers every repeat of that same signature within
the member. Counting repeats would need assertions to be distinguishable per receipt, and receipt values
are equal for equal faults. Nothing smaller achieves that.

## Rejected alternatives

- **(b) Metadata on the deftest naming the error schema**, e.g. `:seon.test/expected-fault`.
  - It is a new key that has to be lifted through the marker seam: the exact list the owner refused.
  - It matches by class, so any unrelated fault with that schema passes.
  - It holds even when the body never observed the fault, so a test that stops provoking it stays green.
  - It declares nothing that the assertion does not already hold.
- **(c) `thrown?` alone.** It does not cover faults recorded under `:record`, or faults recorded on
  another thread such as a proc or a `future`, where the body only reads the receipt. The predicate form of
  rule 1 covers those. Rule 2 is the clojure.test idiom for the rest.

## Data flow

```
body --fault!--> occurrence on branch B (+ receipt) --> body asserts on receipt
  --> :pass report, :actual holds receipt --> runner capture: consumed ids (captured result)
member-result, before release: occurrences on member's branch set since basis
  minus consumed --> each remainder: +1 error-count, message names the occurrence
```

**Dependency (not this design, lane-fix-fault-delivery item 1).** The member's branch set must include
**fixture branches acquired under the member**, not only the member's own `agent-<hash>`. Each
`test-support/with-database` call acquires its own branch off the captured commit
(test/seon/test_support.clj:662, :96). The disarm-backstop fault originates on that branch, which is
unlinked before the member ends. Today the only durable trace is the copy on `:cluster-default`
(fault.clj:143–145). No parent-branch fact exists: `seon.cluster.registry/branch-request` records only
`:from` (seon.cluster.registry.edn:5). So the containment fix has two parts:
- `:seon.test.member/branch` has to be written for every acquisition made from a member handle.
- `record!` has to copy a fault from such a branch to the member's branch, not to the cluster home.

Until then, the observed-fault read in slice 2 has no complete source. Item 1 should be designed with this
case in mind.

## Owner files and slices (each well under 100 lines)

| slice | owner | change | est. lines |
|---|---|---|---|
| 1 | `src/seon/fault.clj` holder | make `carried-receipt` public; its contract is unchanged | 1 |
| 1 | `src/seon/test/runner.clj` + `resources/seon/schemas/seon.test.runner.edn` | `:pass` arm of `capture-event!` (runner.clj:222) collects consumed ids using a compiled `m/validator` for `:seon.fault/recorded` over `(rest actual)` when `actual` is a seq, plus `carried-receipt` when it is a Throwable. `captured-results` emits the optional `:seon.test/consumed-faults [:set :seon.error.occurrence/id]` | ~25 + 1 schema |
| 1 | runner regression | one host test and one SCI test, each firing a real `fault!` on a `with-database` branch and asserting on the receipt. The captured result lists the id. An unasserted fault lists nothing | ~40 |
| 2 | `src/seon/test.clj` holder (`member-result`, test.clj:1452) | after the body exits and before `release!`, query occurrences whose `:seon.error.occurrence/branch` is in the member's branch set (item 1), subtract the consumed ids, and add errors the same way `with-cleanup-failure` does | ~30 |
| 2 | regression in `seon.test.one-request-test` | a provoked, unasserted fault makes the member red and names the occurrence. The asserted fault stays green. A fault whose assertion fails is both a failure and an error | ~35 |
| 3 | `test/seon/cluster/agent_test.clj` holder | the one-line worked example below. Tests that provoke a fault without asserting on it show up by name as slice-2 reds on the first run. Each is fixed by asserting on its receipt, not by an exemption | 1 + reds |

Cost: each `:pass` event runs one compiled validation per argument, and only when `actual` is a seq. It
runs one chain walk only when `actual` is a Throwable. Each member runs one query, which is O(occurrences
on its branches). No whole-program work.

## Worked example: `disarm-has-a-declared-loud-turn-completion-backstop`

The test is at test/seon/cluster/agent_test.clj:887. `agent/disarm!` fires the declared backstop through
`fault!`. Under `:panic` the future catches the panic, and the test takes
`receipt (:seon.fault/recorded (ex-data thrown))` (agent_test.clj:935). The test already asserts that the
fault committed:

```clojure
(is (true? (:seon.fault/committed? receipt)) (pr-str (ex-data thrown)))   ; :actual (true? true)
```

That `:actual` holds `true`, not the receipt. Slice 3 changes it to the equivalent predicate over the
receipt:

```clojure
(is (get receipt :seon.fault/committed?) (pr-str (ex-data thrown)))
;; :pass, :actual (get {:seon.error.occurrence/id "…" :seon.error/operation seon.cluster.agent/disarm! …}
;;                     :seon.fault/committed?)
```

The runner records the occurrence id as consumed. `member-result` finds that same id among the member's
branch faults, the one on the `with-database` branch. The id is consumed, so the member stays green. If a
change made `disarm!` also fault somewhere else, for example a `release-context!` failure in `finally`, that
second occurrence has a different id. It becomes an error that names its operation.

## Probe (MCP, root /Users/sean/src/seon, cluster default, read-only)

- **SCI mode:** `(resolve 'clojure.test/is)` → `#'clojure.test/is`, macro `true`. The `assert-expr` method
  keys are `:always-fail :default instance? thrown-with-msg? thrown?`. `(type clojure.test/report)` →
  `clojure.lang.MultiFn`. Timing: 8 ms.
- **JVM mode:** the same five `assert-expr` keys. Timing: 4 ms.

Identity of the SCI Var with the host Var is inferred, not proven. Slice 1's SCI regression is the proof.
