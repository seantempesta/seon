---
type: issue
status: resolved
severity: friction
tags: [issue, agent, pod]
---

# Warnings context block is not installed by the manifest

## Observed

`config/system.edn` installed `seon.agent.ctx.warnings/core-faults-block` and
`instrumentation-gaps-block` but had NO block whose render is
`seon.agent.ctx.warnings/warnings-block`. Live check on the default cluster
(2026-07-20): neither root's nor a task agent's rendered block set contained
`:warnings`, so the entire `seon.warn/checks` registry (15 checks) rendered
into NO agent's context in the default configuration.

## Resolution (owner ruling 2026-07-20: drift, not intent)

The manifest installs the block again: `config/system.edn` adds

    {:seon.agent.ctx/name :warnings :seon.agent.ctx/priority 40
     :seon.agent.ctx/token-cap 1024
     :seon.render/ai seon.agent.ctx.warnings/warnings-block}

to `:seon.config/agent-context`'s `:seon.agent/ctx` — priority 40 places it
after `:canvas` (35) and directly ahead of root's derived fault blocks
(41–43). `config/acme.edn` inherits it through its `#merge` patch; the
`minimal*.edn` family deliberately declares its own minimal trees and stays
unchanged.

Apply semantics (verified in source and live): `bin/seon config apply`
reseeds the `:seon.config` singleton only; existing agents retain their
creation-time block copies (`seon.agent.ctx/initial-agent-context` runs only
at creation). The live root agent was retrofitted explicitly via the one
`seon.agent.ctx/install!` mechanism.

## Proof (2026-07-20, default cluster)

- Fresh minted agent `crisp-needles-travel` seeded
  `[:namespaces 20] [:canvas 35] [:warnings 40] [:plan 45] [:transcript 100]`.
- Omission contract: with zero firing checks, `ctx-preview` rendered blocks
  `[:system :namespaces :canvas :plan :transcript]` — no WARNINGS section.
- All 15 checks verified up to date: 13 provoked live (seeded defect rows;
  guidance rendered in the real `ctx-preview` context, current idioms:
  `seon.db/pull`/`query` top-level forms, `seon.agent.fs/grants`,
  `seon.agent/message!`, `seon.test.runner/run-vars`, `my.canvas/show!`);
  `check-parallel-attr` and `check-unmarked-entity-kinds` verified by direct
  pure-check call, including dev-only suppression from the agent render.
- Self-heal: retracting the seeded rows returned the agent to a
  no-WARNINGS-section render.
- Token cost via `seon.ai.tokens/estimate`: one firing cluster is 71–248
  tokens; the seeded all-13 burst is 1730 tokens, clipped by the block's
  1024 token-cap.
- `seon.warn`'s namespace docstring statement that the caller defaults
  ns-scope now matches reality again — the block is installed by default.

Tests: `seon.warn-test` (9/78), `seon.agent.ctx.warnings-test` (1/10), and
`seon.config-test` green after updating the two manifest-order expectations
in `test/seon/config_test.cljs` to include `[:warnings 40]`.
