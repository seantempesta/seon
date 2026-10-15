---
title: "Why run 10 was not done at start after merge-main, and why its turns ran hundreds of tests"
type: research
status: complete (lane chain-step-cost, 2026-10-15, read-only JVM probes on default at 806a97324)
created: 2026-10-15
---

# chain-step-cost: why run 10's Implement task was not done at start after merge-main

Lane `chain-step-cost`, 2026-10-15, default at 806a97324 (restarted, pid 30409). Chain `:chain-f1f6dc8e6652`,
attempt `attempt/e93ca95c9fdb`, proof
`seon.error-test/an-unresolved-alias-name-is-answered-with-its-nearest-core-name`. All probes read-only in JVM mode,
on the branch through `seon.cluster.registry/on-branch`.

## Broken

1. **The branch's own change is computed by a history walk from a t on another branch's counter.** After
   `seon.merge/merge-main!` the branch's merge base (its base ref, `seon.cluster.registry/base-commit-id`) is main's
   commit `6ad046d3` (basis-t 536872709, main's counter). `seon.context/change` (the done rule's identities) and
   `seon.step/changed` pass that basis-t to `seon.program/changed-identities` over the *branch's* history, whose own t
   runs 536872008 (fork, `:seon.branch/base-t`) to 536873134. Every branch transaction above 536872709, the
   merge-main transaction included, counts as the branch's change; branch edits between 536872008 and 536872709 are
   silently missed. `seon.program/changed-identities`'s docstring requires "a basis-t on `database`'s own lineage":
   the callers break that.
2. **`seon.test/change-base` ignores the merge base entirely.** Its private `seon.test/fork-commit` walks commits to
   the nearest ancestor written on another branch: merge-main transacts on the branch itself, so this stays the
   original fork commit `6acf6fed` forever. `seon.test/branch-changes` / `seon.test/branch-tests` (the prove pass and
   the turn's test step) therefore treat every declaration main moved in as the branch's change.
3. **Not reused: executed.** The 7 test runs recorded on the branch after the merge (`670baa665e5d` ...
   `7ba6d419889d`, t 536873100-536873127) hold 61-64 members each, 437 members, 1 reused
   (`:seon.test.member/reused-run`). Main's tests at main's own reach were run again on the branch.

## Q1. Why the proof was not green at start

The proof was green; the task was refused for other reasons. `seon.context/step-verdict` on the branch (run
`7ba6d419889d`): status `:working`, `:seon.program/identities` 685, members 1951, refusals:

- `:seon.source/stray-red-error`: 60 red tests "defined or changed on the branch" (`seon.request-cost-test/*`,
  `seon.render.web-test/*`, `seon.ref-test/*`, ...).
- `:seon.source/uncontracted-error`: 32 functions (`seon.babashka-load-test/*`, `seon.check-corpus/*`,
  `seon.batch/defining`, ...).

None is the branch's own: against the branch's real change (below) both lists are empty. Re-judging the same
`judged` value with `:seon.program/identities` = the branch's own 24 identities: `seon.cluster.source/done-refusals`
answers `[]`, so the task **would have ended done at start** (as run 8 did before merge-main).

## Q2. Why a turn that defined nothing runs hundreds of tests

The prove pass and the turn's test step take `seon.test/branch-tests` (via `seon.turn/run-branch`), whose
`seon.test/branch-delta` starts from `seon.test/change-base` = the original fork commit (broken 2). Measured on the
branch:

| base for `seon.test/branch-delta` | delta | tests in it | warm cost |
|---|---|---|---|
| `seon.test/fork-commit` (original fork `6acf6fed`, today) | 628 | 198 | 1089 ms |
| base ref (merge base `6ad046d3`, walk from its t, broken 1) | 91 | 11 | 296 ms |
| proposed: walk from the fork's base-t, compare digests at the merge base | 24 | 1 | 47 ms, 82 MB |

The 198 tests plus those reaching 430 changed functions are what the agent page listed; they were executed, not
reused (broken 3). The request's own changes (`seon.test/request-changes`) are correct; the branch-wide producer is
the one at fault. The block's changes / merge list (`seon.context/change`) share broken 1: 685 identities against 24.

## Q3. What holds the 2.5 GB

Not answered by measurement. The 4.4 GB `data/heap/java_pid84529.hprof` is this morning's OOM (an earlier JVM), and
parsing it with the local MemoryAnalyzer.app needs a heap larger than default's on this 16 GB machine: not cheap now.
What was measured instead is the allocation the wrong change set drives, per call, warm:
`seon.context/step-verdict` 397 ms and 1.15 GB allocated, 12,426 index reads (cold: 1.7 s, 7.9 GB); the two
`branch-delta` calls above 2.3 s and 8.4 GB cold. Every turn of a merged-forward branch pays this, plus ~437 executed
tests, which explains the pressure. `seon.cache` is within its cap (188 MB weighted now; `:seon.db/projection` 102 MB
for 89 entries is its largest kind). Open hypothesis for the live 2.5 GB (needs the dump read off-box): Datahike
index nodes of many distinct commits (249 branches) kept reachable by materialized `commit-as-db` values.

## Smallest fix (one derivation, every consumer)

The branch's own change is git's `diff(merge-base, HEAD)`: the candidates the branch's own history touched since its
fork (`:seon.branch/base-t`, a t on its own lineage), kept only where the digest differs from the merge base value.

```clojure
(defn own-changed-identities
  "The declarations `head`, a branch, holds at another digest than `merge-base` (its base ref's value), among those its
  own history changed since `fork-t` (its fork entity's `:seon.branch/base-t`): git's diff of the merge base and HEAD.
  After a forward merge, what main moved in is at the merge base's digest and is not the branch's."
  {:malli/schema [:=> [:cat :seon.db/basis-t :seon.db/database-value :seon.db/database-value
                       [:map [:seon.program/max-datoms :seon.program/max-datoms]]]
                  [:or :seon.program/identity-set :seon.program/digest-map-refusal]]}
  [fork-t merge-base head bounds]
  (let [touched (changed-identities fork-t head bounds)
        scoped (when (set? touched) (assoc bounds :seon.program/identities touched))
        [before after] (when scoped (map #(digest-map % scoped) [merge-base head]))]
    (cond (not (set? touched)) touched
          (:seon.error/at before) before
          (:seon.error/at after) after
          :else (into #{} (remove #(= (get before %) (get after %))) touched))))
```

Owner: `seon.program` (program.cljc, not a critical-path file). Callers to convert in one commit (all critical-path
files, so this lane leaves them to the chain lane):

- `seon.context/change`: replace `(program/changed-identities (db/basis-t base) database ...)` with
  `own-changed-identities` of the fork's base-t (`seon.cluster.registry/fork-of`) and the base value.
- `seon.test/change-base` + private `seon.test/fork-commit`: for a work branch use the base ref
  (`seon.cluster.registry/base-commit-id`), and `seon.test/branch-delta` takes the fork t for its walk; delete
  `seon.test/fork-commit` and its `:seon.test/commit-facts` cache kind (1,127 misses now).
- `seon.step/changed` and the walk at `seon.cluster.agent` (its `program/changed-identities (db/basis-t base) at-end`).
- `seon.merge/merge-main!` already walks `ours` from the fork's base-t (`seon.cluster.source/branch-base-t`): it can
  call the same function for its `ours`.

Red proof to write with it (data, `seon.merge-test`): fork a branch, define one function on it, advance main by an
unrelated definition, `seon.merge/merge-main!`, then assert `(seon.step/changed branch :seon.fn/sym)` is exactly the
branch's one function (today: it also holds main's definition). The live probe for the measured before/after is
the table above, re-run with `seon.profile/measure` on `seon.context/step-verdict` for `attempt/e93ca95c9fdb`.

Follow-up to file with the fix: broken 3 (why main's results at the same reach digest were not carried,
`seon.test/carried-results`), once the change set is right and only reaching tests remain.
