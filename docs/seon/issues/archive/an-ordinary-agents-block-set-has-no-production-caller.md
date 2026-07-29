---
type: issue
status: resolved
severity: friction
tags: [issue, render, context, agent, tests]
---

# An ordinary agent's block set has no production caller

## Problem

`seon.render.agent/seed-tx` installs the ordinary-agent block set — identity,
execution, peers, and the namespace walk — that today's context wave was built
around. Nothing in `src/` or `script/` calls it. Its only callers are in
`test/seon/context_pilot_test.clj`.

Boot seeds the ROOT agent's blocks and no others, and the fresh tree has no
agent-creation route, so no agent in a running cluster has ever received this
block set. The wave's proof is a pilot test, which is the class the house rule
names as NOT COVERED: a live proof that ran once in a lane.

Separately, `root/seed-tx` and `agent/seed-tx` are byte-identical convergence
wrappers around `block/install-tx`. The convergence check — converged means zero
writes — belongs in `install-tx`, once.

## Evidence

`src/seon/render/agent.clj:220-234` defines `seed-tx`. Callers, across the whole
fresh tree:

- `test/seon/context_pilot_test.clj:62`
- `test/seon/context_pilot_test.clj:364`

`src/seon/cluster.clj:537-543` seeds root alone:

```clojure
(d/transact connection (cluster.agent/creation-tx
                        {:seon.cluster.agent/id root-agent-id
                         :seon.ns/name 'my.agents.root}))
(let [seed (root/seed-tx @connection root-agent-id)] …)
```

`cluster.agent/creation-tx` has exactly that one call site; `src/seon/render/web.clj`
exposes no agent-creation route.

The duplication: `src/seon/render/root.clj:258-274` and
`src/seon/render/agent.clj:220-234` differ only in the `blocks` var they close
over.

## Owner

`seon.cluster` (the creation path) and `seon.render.block/install-tx` (the
convergence rule).

## Acceptance

- Creating an agent seeds its block set on the production path, in the same
  commit that creates the agent, so an agent's context is never a function of
  which lane created it.
- The convergence check lives in `block/install-tx`; `root/seed-tx` and
  `agent/seed-tx` collapse into calls to it rather than two copies of it.
- A regression creates an agent through the production path and asserts its
  prompt carries the identity, execution, peers and namespace contributions —
  replacing the pilot test's hand-seeding as the proof of record.

Resolved by `df160158f`: `seon.cluster.agent/creation-tx` now installs the
ordinary-agent block set in the same transaction that creates the agent.
