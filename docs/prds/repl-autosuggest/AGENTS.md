---
type: orchestrator
status: active
tags: [orchestrator, prd, agent]
---

# REPL autosuggest — integrated lane index

## Current state

As of 2026-07-14, model-training work remains paused behind runtime and data
correctness. The runtime-reliability branch reviewed the stable lane and
integrated only the provider-aware Inspect egress, declared OpenAI dependency,
first-class long-term-planning task, same-title `my.plan/plan!` guard, and
EDN-only plan reconciliation. The branch's compact-context commits, removed
markdown path, local ACME state, and `src-needle` scratch were deliberately not
imported. The untracked research file in this folder belongs to another agent.

## Settled constraints

- Runtime facts and current code win over old training exports and diary
  sections in this PRD.
- Training/evaluation data must pass the real read/eval boundary. A recorded
  successful no-op is not gold data.
- Context uses the one `seon.agent.ctx/render-context` producer. Profiles may
  select/cap existing blocks; they do not rewrite block content.
- `my.plan` has one EDN tree representation. Markdown reconciliation remains
  deleted.
- Function cards use real qualified keywords and valid forms. Do not restore
  `::` abbreviation or an ellipsis body that models can imitate as code.
- Inspect AI is the only agent/model harness. No gym or parallel scorer stack.

## Verification

Use the tests and Inspect task paths recorded in the active runtime roadmap.
Do not run or update ACME until its current owner releases it. Any future model
work begins by revalidating data quality against the refactored runtime.

## Entry points

- `root-cause-fixes-2026-07-13.md` — correctness findings that remain useful.
- `design.md` and `roadmap.md` — experiment design and dated history.
- `INTEGRATE-BACK.md` in the stable worktree — reviewed commit inventory.
- `docs/prds/runtime-reliability/AGENTS.md` — current integrated authority.
