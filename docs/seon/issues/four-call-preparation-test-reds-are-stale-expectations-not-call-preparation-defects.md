---
type: issue
status: open (triaged by lane red-fix2 on HEAD 04d6075ab; no code changed)
severity: friction
created: 2026-10-19
tags: [issue, test, call-preparation]
---

# Four `seon.call-preparation-test` reds: three stale test premises, one unbound probe namespace

Stored runs `b14ae39e3efb` (`operator-check-20261019T224338.248.edn`, candidate `4c2abe147`) and `5a499bc6c317`
(`operator-check-20261019T224724.233.edn`): the same four red, all before closed-runtime P1 (`04d6075ab`).
No production call-preparation code is at fault in any of them.

## 1. `a-compiled-first-party-call-is-prepared` (call_preparation_test.clj, "explicit caller wins, at a map key")

Assertion: `(= [{:seon.db/connection 1}] (:seon.error/offending refusal))`. Actual: the reference map
`{:seon.error.reference/count 1 :class "clojure.lang.PersistentArrayMap" :digest "1ec0f34e..."}`.
Cause: a contract refusal holds a refused collection as its reference (`seon.error.refusal/value-reference`), by
`e52ae96c5` (2026-10-14, "A contract refusal holds the refused argument once, a collection as its reference").
The test predates it; the expectation was already stale on 2026-09-09 (listed in `turn-consumer-fixtures-read-retired-result-storage.md`).
Fix at the test: assert the reference (`seon.error.refusal/value-reference` of the supplied map, or its `count` and
`class`), which is the law the producer now keeps. No producer change.

## 2. `a-new-branch-at-a-derived-commit-reads-the-held-snapshot` (:546, :548)

Assertion: the second fixture branch's `:datahike/commit-id` equals the first fixture's, "both start at the executing
handle's commit". Actual: ids share their time prefix and differ after it.
Cause (probed): Datahike's `branch!` keeps the commit id (JVM probe on an in-memory store: main and branch connections
report one commit id). The divergence is `seon.cluster.registry/fork!`, which after `branch!` transacts the branch's fork
entity (`branch-fork-entity`: fork id, target, base-t) on the new branch, so every isolated branch is one commit past
its `from`, with its own id. Introduced by `d9eb15c05`/`aa7ffb17f` (2026-10-10, merge base survives collection). The
task `seon.call-preparation/a-sibling-branch-at-the-same-commit-reads-that-commit` guesses the fork defect F55
(branch loaded by name); that guess is refuted: the plain branch is fine.
Fix at the test: what two sibling branches share is the program, not the head commit. Branch the two fixtures'
snapshots from the commit the fork entity sits on (`:seon.cluster.registry/from`), or assert `held?` through the
shared commit tier keyed by program content, not by `(:meta @connection)`. Retire the task text's F55 branch.

## 3 and 4. `an-acquired-cluster-context-prepares-its-calls`, `an-acquired-context-supplies-a-leading-database-to-a-first-party-read`

Errors: SCI "Unable to resolve symbol: seon.call-preparation-test/probe-received-database?" (and
`probe-received-connection?`, `probe-current-database`).
Cause: `probe-ctx` binds the probes with `(sci/init {:namespaces {'seon.call-preparation-test probe-vars}})`;
these two tests instead evaluate in `seon.sci.eval/cluster-ctx` carried through `seon.env/carry-state`, whose
program holds no `seon.call-preparation-test` namespace (a host-bound test helper is not first-party program). Same
class as the interpreted-red class after `1c68e9f5d`. Already owned by
`seon.call-preparation/an-acquired-context-test-resolves-its-probes` (it names both tests); first recorded red
`6a421d96e` (check run `eb451aab3a4d`), not bisected earlier.
Fix at the test: define the probes in the acquired context the way an agent would (evaluate the `defn`s there with
`seon.sci.eval`) or call the compiled Vars; no symbol resolution special case.

## Proof when fixed

`bin/seon check --at HEAD --test seon.call-preparation-test/<each var>` green for the four vars.
