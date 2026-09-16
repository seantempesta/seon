---
type: research
status: measured on an isolated scratch cluster; the two new regressions await the cold gate
created: 2026-09-16
tags: [research, steward, issue, agent, flow, wake, arming, measurement]
---

# `start!` arms its worker and the assignment is its wake (2026-09-16)

Lane: `start-arms`. Subject: the blocker
[`a-worker-started-while-the-cluster-runs-is-never-armed`](../../../seon/issues/a-worker-started-while-the-cluster-runs-is-never-armed.md),
filed by the issue-context trials lane (evidence:
[issue-context-trials-2026-09-16](issue-context-trials-2026-09-16.md)).

## The two halves, as declarations

Neither half is a new code path. Both are one schema property each, derived
by one query, consumed at one seam.

| half | declaration | derivation | consumer |
|---|---|---|---|
| (a) creation arms | `:seon.agent/id` carries `:seon.wake/arms true` (`resources/seon/schemas/seon.agent.edn`) | `seon.cluster.wake/arming-attributes` | `seon.cluster.wake/route!` offers the cluster's ONE armer a payload-free wake on every assertion |
| (b) assignment wakes | `:seon.issue/agent` carries `:seon.db/index true :seon.wake/listen true :seon.wake/opens-turn? true` (`resources/seon/schemas/seon.issue.edn`) | the existing `wake-attributes` / `turn-opening-attributes` | `seon.turn/unanswered-wakes`, unchanged |

`:seon.wake/arms` is declared in `resources/seon/schemas/seon.wake.edn`. It is
deliberately NOT `:seon.wake/listen`: nothing is routed to a mailbox and the
datom's value is not an agent reference, because the armer derives
`(agents in facts) − (armed set)`. It therefore needs no `:avet` index.

`src/seon/turn.clj` was NOT edited. Half (b) needed no change there: every
wake derivation is already generic over the declared listened set.

### The armer docstring was already true — of nothing

`seon.cluster.agent/armer-step` claimed "The wake set grows by
`:seon.agent/id` — a committed agent creation IS an arm wake". No attribute
declared it, so the armer ran only when `route!` found a wake-matching datom
whose recipient had no routing entry — the created-**and-addressed**-in-one-
commit belt. `start!` writes no such datom, so the worker waited for the next
boot. Both docstrings now name the declaration that makes the claim hold.

## The intermediate state that broke a peer's fixture (reported by the gate)

The first version put the missing-arming refusal into
`seon.cluster.wake/declarations-refusal`. That function is consumed by
`seon.turn/opening-deferred?` (`src/seon/turn.clj:2729`), which defers EVERY
opening when it is non-nil. On the shared default JVM — whose
`seon.test-support/database-base` delay was realized before these schema
edits and therefore predates `:seon.wake/arms` — `next-agent-work` answered
nil and `seon.cluster.turn-test/a-whole-turn-runs-from-trigger-to-closed-run`
degraded to 1 pass / 4 fail / 1 error.

That was the absence-as-health class in a new costume, and it was mine.
Arming is a REGISTRATION property of the listener, not a turn-bound property,
so it moved to its own `seon.cluster.wake/arming-refusal`, raised only by
`route!`. `declarations-refusal` is byte-for-byte its earlier three branches.

In-process verdict after the split, default JVM, hot-reloaded Var:

```
(seon.test/run (#'seon.test/resolve-test
                'seon.cluster.turn-test/a-whole-turn-runs-from-trigger-to-closed-run)
               (seon.operator/connection "default"))
;; => 6 pass / 0 fail / 0 error
```

## The foreign defect this uncovered (fixed at the root)

A new worker's opening could not be stored at all. `seon.error/faults-form`
pulled on `(faults-input unit)`, which is nil for an agent with no
`:seon.error/of-steward` datom — every brand-new agent. `seon.db/pull`'s
declared contract refused, the throw escaped the renderer, the fault
committer recorded `:seon.render/unknown` and interrupted the generating
turn. Measured on the scratch cluster: the first worker closed its opening
turn with ZERO evaluations and one fault message.

`src/seon/error.clj:1666` now guards the absent entity. `src/seon/error.clj`
had no uncommitted foreign edits (`git status --porcelain` clean), so it was
not a protected path; the fix is listed below and the class is filed as
[`a-new-agents-opening-is-interrupted-by-its-own-empty-fault-block`](../../../seon/issues/a-new-agents-opening-is-interrupted-by-its-own-empty-fault-block.md).

## Live proof — scratch cluster `start-arms`, NOT default

`default` cannot adopt this change. `bin/seon init --dev default` refused:

```
Cluster `default` predates the incompatible schema change for `:seon.issue/agent`
and cannot be reopened in place.
```

**RESET NEEDED for `default`** at the commit landing this lane. The branch
publication to `current-src` completed; only the in-place development
adoption refused. That refusal is itself a defect — the Datahike fork
supports adding an index monotonically with an atomic AVET backfill
(`reference-code/datahike/src/datahike/schema.cljc:277-283`), while Seon's
`seon.cluster/declaration-changes` compares the two declaration maps with
`=` and calls any difference incompatible. Filed as
[`adoption-refuses-a-monotonic-index-addition-datahike-supports`](../../../seon/issues/adoption-refuses-a-monotonic-index-addition-datahike-supports.md).

Proof therefore ran on an isolated operator root
(`tmp/start-arms-root`, cluster `start-arms`, published from this working
tree, downed and deleted afterwards). Provider calls disabled
(`:seon.config.ai/no-provider true`). Nothing called `arm!`.

Declarations installed in the live cluster:

```clojure
(seon.cluster.wake/arming-attributes db)  ;; => #{:seon.agent/id}
(seon.cluster.wake/wake-attributes db)
;; => #{:seon.effect/to :seon.issue/agent :seon.message/inbox :seon.schedule.fire/agent}
```

`seon.issue/start!` on the real indexed issue
`a-search-contract-predicate-cannot-be-made-durable`, budget 3:

```clojure
(seon.cluster.agent/armed routing "d6e6f1b063d3")   ;; => armed entry, no hand arm
```

Turn 0 stored its opening — 11 evaluations, in order `(help)`, the identity
pull, `(seon.plan/plan {})`,
`(my.issue/status {:seon.issue/id "a-search-contract-predicate-cannot-be-made-durable"})`,
inbox, settings, notes, `(dir seon.instrument)`, runtime, and the fault read
form that the `error.clj` fix restored.

Wake accounting, measured:

| moment | `(seon.turn/unanswered-wakes db agent {})` |
|---|---|
| immediately after `start!` | `[{:db/id 42660 :seon.wake/attribute :seon.issue/agent :seon.wake/t 536870955}]` |
| after the opening turn closed | the same one — system turn 0 answers nothing |
| after the first accepted reply (`bdc51a5b1939`, virtual turn) | `[]` |

`next-agent-work` between the opening and the reply was
`{:seon.turn.work/situation :open}` — the worker derived a turn about its
issue from the assignment alone, with no synthetic message. The trials had
to arm by hand and send one fixed message per session; neither is needed.

The first probe, on `a-failed-turn-wakes-itself-through-its-own-fault-message`
with budget 1, is what surfaced the `error.clj` defect: armed and turned
(opening turn opened and closed), zero evaluations, one `:seon.render/unknown`
fault. Both probe workers died with the scratch root.

## Regressions

`test/seon/cluster/agent_arming_test.clj`, canonical fixture
(`seon.test-support/with-database` + `seed-cluster!`), real armer graph and
real `wake/route!`, every await bounded by
`seon.test-support/event-backstop-seconds`:

1. `an-agent-created-while-the-cluster-runs-is-armed-by-its-own-creation` —
   `seon.cluster/ensure-entity!` against a running cluster graph; awaits the
   routing entry AND the agent's own stored evaluation with its turn closed.
   Nothing in it calls `arm!`.
2. `starting-an-issue-leaves-one-unanswered-wake-its-first-reply-answers` —
   asserts the declaration facets of `:seon.issue/agent`, then exactly one
   unanswered wake carried by the issue entity, still one after the opening
   closes, and none after the first accepted reply.

**Verification boundary.** These two have NOT run green in a JVM. The shared
default JVM's fixture base was realized before these schema edits, so no
in-process run there can see `:seon.wake/arms` or the `:seon.issue/agent`
index; rebuilding that delay would break every other lane's in-process runs.
What ran in-process is the peer regression above (green) and the two
`error.clj` consumers. Everything else is the live scratch-cluster evidence
and the cold gate, requested at
`tmp/orchestrator/gate-requests/start-arms.txt`.

## Files touched

- `resources/seon/schemas/seon.wake.edn` — `:seon.wake/arms`
- `resources/seon/schemas/seon.agent.edn` — `:seon.agent/id` is an arming attribute
- `resources/seon/schemas/seon.issue.edn` — `:seon.issue/agent` is a listened, turn-opening, indexed attribute
- `src/seon/cluster/wake.clj` — `arming-attributes`, `arming-refusal`, `route!` dispatch and its docstring
- `src/seon/cluster/agent.clj` — armer docstrings name the declaration
- `src/seon/error.clj` — `faults-form` guards an absent fault entity
- `test/seon/cluster/agent_arming_test.clj` — new
