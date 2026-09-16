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

### Batch 54 B round: one red, and the third missing half

`an-agent-created-while-the-cluster-runs-is-armed-by-its-own-creation` was
GREEN on the cold gate — half (a) holds. The second regression ERRORed: its
`:armed` await fired. The cause was real and mine.

**An agent committed before the armer is reading was never armed.** The
fixture creates the worker (`start!`) and only then stands up the cluster, so
there was no wake to receive. Boot survived this only because
`src/seon/cluster.clj:2965` calls `armer-step` directly and synchronously
after the graph starts — a prime living OUTSIDE the proc, which every other
constructor of an armer graph has to remember.

Root fix: the armer primes ITSELF at `::flow/resume`, the transition that
makes it live (a proc starts paused and `flow/resume` moves it to running —
`reference-code/core.async/.../flow/impl.clj:209-217`). The prime is an
`offer!` into its own sliding-1 in-port and the pass derives from facts, so
it is unconditional and idempotent, and a pause/resume cycle re-primes for
the same reason. Boot's direct call stays: it is not a second arming path
but how boot PUBLISHES READINESS — a returned instance is already armed,
which an asynchronous prime cannot promise.

Two fixture defects fell out of the same round:

- The regression awaited the opening's closure and then the answer, giving
  the second await its own clock and racing an assertion between them. It
  now makes ONE bounded await on the one terminal fact — the assignment
  answered — and asserts the opening's inability to answer as a property of
  the entity (no provider attempt), not of when it reads.
- It started the worker with `:seon.issue/budget 1`. Measured: the generated
  opening CONSUMES the episode bound, so budget 1 leaves zero ordinary turns
  and the worker can never answer its own assignment. That contradicts
  `seon.turn/episode-runs`' own docstring and is filed as
  [`a-workers-generated-opening-spends-its-issue-budget`](../../../seon/issues/a-workers-generated-opening-spends-its-issue-budget.md);
  the regression uses budget 3 and says why. Nothing was submitted to the
  worker: with budget 3 its own loop reaches an accepted reply on the
  no-provider path and answers the assignment.

In-process on default (fresh base carrying `:seon.wake/arms` and the
`:seon.issue/agent` index, converged adoption), `remaining-ms 120000`:

```
an-agent-created-while-the-cluster-runs-is-armed-by-its-own-creation
  7 pass / 0 fail / 0 error   (11.8 s, 19.9 s)
starting-an-issue-leaves-one-unanswered-wake-its-first-reply-answers
  14 pass / 0 fail / 0 error  (15.2 s)
```

**Verification boundary.** Both now run green in process on default against
a fresh base carrying this change (figures above), and half (a) is green on
the cold gate (batch 54 B). The self-prime and the two fixture corrections
have NOT yet been through a cold gate. Timing is the residual risk: test 1
took 11.8 s and 19.9 s on two runs of a loaded shared JVM, against a 20 s
`event-backstop-seconds` — but that bound is per await, and no single await
in either test is near it.

## Files touched

- `resources/seon/schemas/seon.wake.edn` — `:seon.wake/arms`
- `resources/seon/schemas/seon.agent.edn` — `:seon.agent/id` is an arming attribute
- `resources/seon/schemas/seon.issue.edn` — `:seon.issue/agent` is a listened, turn-opening, indexed attribute
- `src/seon/cluster/wake.clj` — `arming-attributes`, `arming-refusal`, `route!` dispatch and its docstring
- `src/seon/cluster/agent.clj` — armer docstrings name the declaration
- `src/seon/error.clj` — `faults-form` guards an absent fault entity
- `src/seon/cluster/agent.clj` — the armer primes itself at `::flow/resume`
- `test/seon/cluster/agent_arming_test.clj` — new
