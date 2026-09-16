---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, flow, issue-family]
---

# A worker `seon.issue/start!` creates while the cluster runs is never armed

Observed 2026-09-16 on an isolated scratch cluster (`trials`, operator root
`tmp/issue-trials-root`, checkout `tmp/issue-trials-wt` at `db65dc542`) while
running the issue-context trials.

`seon.issue/start!` creates the worker agent, its plan and its first open turn
in one transaction and returns a healthy `seon.issue/status` map. Nothing then
runs. Measured immediately after `start!`:

```clojure
(seon.turn/next-agent-work db {:seon.agent/id "d4e6bed2793e"})
;; => {:seon.agent/id "d4e6bed2793e", :seon.turn.work/situation :generate,
;;     :seon.turn/id "c94a4b085fff"}
(count (:seon.agent/armed @(:seon.agent/routing instance)))
;; => 2   ; root and one agent that existed at boot; the new worker is absent
```

Work exists and is derivable; the agent simply has no flow graph. The armer
(`src/seon/cluster/agent.clj:850`) arms `(agents in facts) − (armed set)` when
it is woken, and `src/seon/cluster/wake.clj:476` wakes it only when a
wake-matching datom names an agent entity that has no channel yet. The
`start!` transaction writes no such datom, so the armer never runs a pass and
the worker waits for the next boot.

Calling `arm!` by hand unblocks it immediately — the same turn then produced
five evaluations:

```clojure
(seon.cluster.agent/arm! {:seon.turn.loop/cluster handle
                          :seon.agent/id "d4e6bed2793e"
                          :seon.agent/routing routing})
```

This is the project's recurring failure class: `start!` reports success and
`next-agent-work` reports work, and the absence of any execution reads as
health. Nothing observes that a created agent is unarmed.

Acceptance: creating an agent while the cluster runs arms it without a boot,
proven by a regression that transacts an agent plus an open turn against a
running cluster graph and awaits its first evaluation under the declared
event backstop. Either agent creation writes a datom the wake listener
matches, or `arm!` is part of the creation seam rather than a separate pass.

## Resolution (2026-09-16, lane `start-arms`)

Both halves are declarations, and `src/seon/turn.clj` was not touched.

- `:seon.agent/id` carries `:seon.wake/arms true`. `seon.cluster.wake/arming-attributes`
  derives the set and `wake/route!` offers the cluster's ONE armer a
  payload-free wake on every assertion, so an agent created while the cluster
  runs is armed by the same pass that arms boot-time agents. The armer's
  docstring already claimed this; nothing declared it.
- `:seon.issue/agent` carries `:seon.db/index true :seon.wake/listen true
  :seon.wake/opens-turn? true`, so the assignment datom IS the worker's first
  wake and no synthetic message is needed.
- `seon.cluster.wake/arming-refusal` is deliberately separate from
  `declarations-refusal`, which `seon.turn/opening-deferred?` consumes:
  folding it in made a missing declaration defer every opening instead.

Live proof on a scratch cluster: `start!` with no provider armed the worker
with no hand `arm!`, stored an 11-evaluation opening, left exactly one
unanswered `:seon.issue/agent` wake, and answered it with the worker's first
accepted reply. Regressions:
`test/seon/cluster/agent_arming_test.clj`. Measurements:
[start-arms-and-wakes-2026-09-16](../../prds/steward-platform/research/start-arms-and-wakes-2026-09-16.md).
