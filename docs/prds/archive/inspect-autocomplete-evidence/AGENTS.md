---
type: orchestrator
status: active
tags: [orchestrator, prd, agent, research]
---

# Inspect and autocomplete evidence — working context

This PRD owns reproducible agent/model evaluation, autocomplete evidence, and
reviewed integration of the separately owned ACME tool-refinement lane. Read
[[roadmap]], architecture laws/toolkit/observability/agent-runtime, current
`src-inspect-ai/` authorities, and exact dependency source before changing
anything.

Begin with an immutable source/version ledger for Inspect AI, provider SDKs,
Docker tasks, scorers, autocomplete data/export code, and the canonical `my.*`
tool surface. Read actual implementations in `reference-code/`; never infer
Inspect solver/scorer behavior from docs or old lane prose.

The harness measures Seon through production behavior; it is not another agent
runtime or lifecycle operator. Preserve raw tasks, samples, transcripts,
scores, model/provider/config, cluster coordinate, and source identity. Do not
touch the active ACME agent worktree until its owner hands back commits and
evidence. Do not read, stage, move, or delete the protected shared-schema file.

Research belongs in `research/`; current gaps, order, and proof belong in
[[roadmap]]. Paid calls happen only after deterministic and offline gates.
