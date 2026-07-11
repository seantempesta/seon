---
type: orchestrator
status: completed
tags: [orchestrator, agent, web, archive]
---

# agent-fsm — MERGED, this folder is history

**agent-fsm merged to main on 2026-07-02.** This PRD is DONE — nothing here
describes current status. The live work is the **agent-ctx chunk** on
`feature/agent-ctx` (`docs/prds/agent-ctx/CLAUDE.md`); the always-current system
description is `docs/seon/architecture/` (read [[architecture]] first). This file is only an
index into what agent-fsm shipped.

**Do not read this folder for current state.** Anything about what is running
now, which issues are open, or which surfaces are live is authoritative in the
leaf `src/seon/**/CLAUDE.md` files and `docs/seon/architecture/`, not here.
(Historical footgun: prior versions of this file described the route surface and
went stale. The route truth lives in ONE place — `src/seon/web/CLAUDE.md`
(post-cutover: `/` IS root's world/dashboard, the fleet roster is `/agents`,
`/world` is retired). Read that leaf file for routes, not this one.)

## What agent-fsm delivered

The agent runtime + FSM (loop/run/turn, lifecycle, isolation), the context
engine and `seon.render`, the config-driven agent-init (ONE manifest →
`seed-default-ctx!`), the compact-namespace-card render, the `my.*` toolkit
(`my.data`/`my.ui`/`my.tile`) proven composable, transcript eviction, and the
gym fitness function. Depth and live-proof are in the roadmap and research below.

## Pointers (depth)

- **[[roadmap]]** — the agent-fsm we-are-here at merge (2026-07-02), with
  file:line evidence for what shipped.
- **[[coordination]]** — the Core↔UI channel and lane table from the arc.
- **`research/`** — the live-drive evidence behind the arc's laws (toolkit
  reachability, canvas drives, namespaces trim, facet gaps, the overnight
  reports).
- **`docs/seon/architecture/`** — where the ideas agent-fsm built now live in
  present tense: [[architecture]], [[data-model]], [[agent-runtime]], [[ui]],
  [[toolkit]], [[observability]], [[library-grounding]].
