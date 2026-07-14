---
type: prd
status: planned
tags: [prd, agent, research]
---

# Inspect and autocomplete evidence roadmap

## Outcome

Inspect produces reproducible evidence for ordinary system work, long-term
planning, autocomplete/data quality, and large-planner/small-executor tool use;
reviewed ACME refinements improve the canonical `my.*` surface rather than
creating a benchmark-only context or runtime.

## Current state

Offline Inspect tests, Docker task support, provider egress, long-term planning
arms, selected autocomplete behavior, and prior lane audits exist. Inspect is
not yet content-pinned, live cluster leases are incomplete, preserved
autocomplete evidence is not one canonical database-derived export, and the
active ACME tool-refinement lane has not yet been handed back for review.

## Ordered work

1. Ground exact Inspect/provider/Docker/autocomplete sources and preserve or
   supersede every old-lane dataset, score, transcript, and unique behavior.
2. Content-pin Inspect and finish one ownership-fenced operator lease for live
   CLJ/CLJS samples without hard-coded ports or bespoke lifecycle scripts.
3. Define one database-derived, versioned autocomplete/tool-use export with
   task, sample, scorer, model, provider, config, source, and cluster provenance.
4. Review the ACME lane commit-by-commit; integrate only canonical `my.*`
   schemas/functions, generated default context, tests, and reproducible
   evidence after default-cluster proof.
5. Run deterministic/offline gates, then simple-model and planner/executor
   trials that drive tool refinement from failures rather than prompt padding.

## Graduation

- A fresh environment reproduces every accepted score from content-pinned
  sources and complete provenance.
- Live samples acquire/release one fenced cluster lease and exercise both CLJ
  and CLJS through current MCP/operator boundaries.
- Autocomplete and tool-use evidence comes from one canonical export; no
  scratch scorer or hidden context is required.
- A large planner can encode a durable plan for a smaller executor, and the
  executor completes representative read/process/write/user tasks with honest
  evidence and recovery.
- Simpler-model failures result in clearer schemas/functions; the default
  context remains generated from the real tool surface.
