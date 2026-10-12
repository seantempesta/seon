---
title: "M3 notes: the merge in main's writer, and the Datahike join it exposed"
type: research
status: lane m3, 2026-10-12. M3's slice is written and green on its own tests; it is NOT committed, because the
  schema half of it turns `seon.cluster.host-test/a-hosted-run-lands-on-its-branch-and-the-parent-head-is-unchanged`
  red through a Datahike query defect the slice does not touch. Options at the end.
basis: HEAD 5640aef92 plus the slice (claimed by lane m3); every run below is `bin/seon check` on a throwaway JVM
  booted from HEAD plus the named paths.
---

# M3 notes

## What the slice does (uncommitted, claimed by lane m3)

- `:seon.task/merge-base` (`resources/seon/schemas/seon.task.edn`), an optional member of
  `:seon.task/assignment-row`. Root writes it on main once the branch exists:
  `seon.cluster.agent/write-merge-base!` (called by `start-attempt!` after `fork-attempt!`) stores the branch's
  retained base commit (`seon.cluster.registry/base-commit-id`), so the first merge has today's base.
  The hybrid's data row says `write-attempt!` and `seon.task/assign!` write it; neither can: `write-attempt!`
  runs before the fork, and the fork commit cannot name itself. The fork is the first moment the commit exists.
- `seon.cluster.squash/merge-tx [database a commit projection]`: inside main's writer, the three-way from the
  assignment's merge base to the source commit against the writer's own value, plus
  `[:db/add a :seon.task/merge-base commit]`; [] when the base already is `commit`. The base is read from the
  datoms because the in-transaction value of a store without schema entities has no carried projection (the
  `seon.db/pull` there refuses `seon.db/projection-fallback`); the projection is main's, passed by the caller,
  as `copy!` passed it.
- `seon.cluster.squash/merge! [connection a branch meta]`: transacts `[[:db.fn/call merge-tx a C projection]]`
  with the provenance metadata, then answers the writer's conflicts by deriving the same three-way again from
  the report's `:db-before` (per transaction, never substituted by the writer's batching,
  `datahike.writer/create-thread`). Conflict tasks are written after the transaction (M4 replaces them).
- `copy!`, `squash-merge!`, `selected-coordinates` deleted; `squash-merge` loses its selection argument; every
  caller converted (`land-ends!`, `ended-problem!`'s text, `seon.task/create!`'s docstring, registry_test,
  db_flow_test, squash_test, `script/astra_cost_handoff.clj`, the AW1 task's intent).
- Owner fix found by the count proof: `squash-merge` treated a coordinate both sides moved to the SAME value as
  unchanged, so an `:add` count with base 1, branch 2, main 2 merged to 2, not the hybrid's 3. A count the branch
  moved now always adds its delta (`counted` in `squash-merge`). The existing `:add` rows (natural identity on
  both sides 2, rule table 6, base-retains-count 2) are unchanged.
- Task U1 retired (entry deleted; its `:needs` in M4, `seon.fault` U7 and `seon.context` M14 removed); its four
  deftests stay untagged as regressions. U7 is not done: its `seon.fault/record!` change is outside M3's files.

## Proofs

Red on HEAD (the four deftests appended to HEAD's squash_test: error, `:seon.task/merge-base` undeclared), green
with the slice: `seon.cluster.squash-test/a-repeated-merge-adds-nothing`, `a-root-edit-after-capture-keeps-roots-value`,
`a-reverted-branch-value-reverts-on-main`, `an-occurrence-counted-on-the-branch-adds-once-on-main`; cost row
`a-merge-stays-within-its-cost-bound` (merge-tx ≤ 20 ms). With the slice, the whole of `seon.cluster.squash-test`,
`seon.cluster.registry-test`, and 21 of `seon.cluster.db-flow-test`'s vars are green. Over
`db-flow`, `registry`, `host`, `turn-continue`, `message`, `agent` and `wake` (127 executed, 17 long excluded),
HEAD and the slice have the identical 23 reds.

## The blocker: one more attribute in an entity schema empties a join

`seon.cluster.host-test/a-hosted-run-lands-on-its-branch-and-the-parent-head-is-unchanged` is green on HEAD and
red with the slice, and red with `resources/seon/schemas/seon.task.edn` alone. Bisected:

| Candidate | Result |
|---|---|
| HEAD | green |
| HEAD + a description edit in seon.task.edn | green |
| HEAD + the `:merge-base` attribute declared, no entity schema naming it | green |
| HEAD + the attribute and its `:seon.task/assignment-row` member | red |

The failing read, on the hosted branch's value right after the form's write:

```clojure
(datahike.api/q '[:find ?r :where [?n :seon.task/intent _ ?tx] [?tx :seon.db/receipt ?r]] database) ;=> #{}
```

while each clause alone answers, and the indexes agree:
`[:find ?tx :where [?n :seon.task/intent _ ?tx]]` gives `536870939`; `(d/datoms database :eavt 536870939)` holds
`[536870939 :seon.db/receipt 58571]`, and so does `:aevt :seon.db/receipt`. The same empty answer comes with
`datahike.query/*disable-planner*` bound true, so the plan is not the only cause: both engines lose a join from
a transaction-position variable to an entity position once the installed schema holds this attribute. `seon.db/q`
answers nil the same way. Every reader joining a datom's transaction to transaction metadata is exposed
(receipts, `:seon.db.transaction/*`, `:seon.squash/*` provenance), on any landing that installs an attribute an
entity schema names, not only this one.

Not yet known: whether any new installed attribute triggers it or only this one (the third row may not install
the attribute at all: an attribute no entity schema names may not reach Datahike), and where in
`datahike.query` the binding is lost. The next probe is the same query on a fresh store with one extra
attribute, then `datahike.query/explain` read whole (the printer cut it in the probe).

Proposed task (for `resources/seon/tasks/seon.db.edn`, held by lane followups):

```clojure
#:seon.task{:name "a-join-on-a-transaction-variable-survives-a-new-attribute"
            :kind :function
            :intent "`datahike.api/q` of `[:find ?r :where [?n :seon.task/intent _ ?tx] [?tx :seon.db/receipt ?r]]` answers #{} on a branch whose schema gained `:seon.task/merge-base` as a member of `:seon.task/assignment-row`, while each clause alone answers and EAVT and AEVT both hold the receipt datom; `datahike.query/*disable-planner*` does not change it. Find where `datahike.query` loses the transaction-position binding and fix it in the fork. Red proof: `seon.cluster.host-test/a-hosted-run-lands-on-its-branch-and-the-parent-head-is-unchanged` with lane m3's schema change (docs/research/agent-platform/2026-10-12/m3-notes.md)."
            :must-read #{datahike.api/q datahike.query/explain datahike.query/q seon.db/q}
            :evidence #{"docs/research/agent-platform/2026-10-12/m3-notes.md"}}
```

## Cost (profile/measure, warm, same fixture: a task with an assignment, its intent changed on the branch)

| Measured | ms |
|---|---|
| HEAD `copy!` end to end (6 runs) | 46.5 cold, then 21.1, 14.1, 15.6, 16.5, 11.4 |
| slice `merge!` end to end (6 runs) | 63.3 cold, then 26.4, 18.2, 24.3, 20.4, 23.4 |
| `merge-tx`'s three-way on main's value | 7.3 cold, then 0.50–0.58 |
| opening one commit (`seon.db/commit-database`) | 0.10–0.22 |
| a plain transaction of the same three datoms | 10.4–32.0 |

The merge-tx work is under 1 ms warm; write validation of a task with an open assignment dominates both
before and after. `merge!` costs about 5 ms more than `copy!`: the second three-way for the answered conflicts,
two commit opens, and the moved base's datom.

## Options

1. **Commit M3 now, naming the host test red by exposure** (recommended): the slice is correct and proven; the
   defect is Datahike's and fires on any landing that installs such an attribute. File the task above at high
   priority for an Opus or Astra lane on `reference-code/datahike` (a fork bump rides root's restart batch). Cost:
   one green test goes red until the fork fix; the receipt join is wrong on main after root's restart.
2. **Hold M3 until the Datahike fix lands**: nothing goes red; M3 and everything after it waits on an unknown
   fix time.
3. **This lane digs into `datahike.query` first**: likely the fastest path to a fix if the cause is shallow;
   it holds every file M3 claims (agent.clj among them) while it runs.
