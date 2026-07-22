---
type: prd
status: active
tags: [prd, architecture]
---

# NS-0.5c — repl.parse rename and my.plan seam repairs

## Grounding preamble (mandatory)

Read the actual source of every file you touch before editing. Report:
(a) a better seam if found; (b) the owners' exact terms. **Stopping
early to report is FREE.** If source contradicts this spec, stop and
report.

OWNER HANDOFF (2026-07-22 morning): the repl-autosuggest boundary is
released — this unit may now touch the repl/plan surfaces that were
parked. Authority: the accepted review
`docs/prds/sci-execution-runtime/research/ns05-ns5-design-review-2026-07-21.md`
§1 (repl.internal + my.plan.internal dispositions). Its counts predate
this tranche — re-derive every consumer list from current source.

## Goal

1. **Rename `seon.repl.internal` → `seon.repl.parse`** wholesale
   (`git mv src/seon/repl/internal.cljc src/seon/repl/parse.cljc`) —
   it is the ONE text→forms parser, a de-facto public contract wearing
   an `.internal` label (review-confirmed). Rewire every consumer
   (review counted 10 in src — recount; tests too). Registered `::`
   keys follow the ns; rg for literal `:seon.repl.internal/` keywords
   and any quoted symbols. Do NOT split the parser into pieces.
2. **Repair `my.plan`'s public boundary** (the internal STAYS
   internal): per the review's per-consumer plan —
   `repl.autocomplete` gets its render entrypoint through `my.plan`
   (its profile stores an internal render symbol today);
   `agent.loop`'s `maybe-consult!` call becomes a real `my.plan`
   public entrypoint; `ai.generate-code`'s failure/request-key helpers
   become locally owned or a deliberately named public plan boundary;
   `execution.runtime`'s redundant internal require is deleted (it
   already requires `my.plan`; NOTE: that file is W5 death row —
   minimal edit only). Check the review's deeper finding: public
   schemas + generate-code exchange `:my.plan.internal/namespace-steps`
   and `/ready-steps` — if that cross-namespace vocabulary is real in
   current source, extract ONLY that narrow generated-plan surface to
   `my.plan.generation` per the review; if the exchange has since
   changed, report instead.
3. **The internal-require gate's two dated allowlist rows come OUT**
   (`test/seon/internal_require_boundary_test.cljs`) — the gate then
   enforces zero exceptions; its stale-row check would fail if you
   forget, but remove them deliberately and say so.

## Owned paths (touch nothing else)

- `src/seon/repl/internal.cljc` → `src/seon/repl/parse.cljc` + every
  consumer's require/alias/key lines (enumerate the full list in your
  summary — src and test)
- `src/my/plan.cljc`, `src/my/plan/internal.cljc` (public entrypoints
  + optional `src/my/plan/generation.cljc`), and the four consumer
  files named above (seam edits only)
- `test/seon/internal_require_boundary_test.cljs` (allowlist rows out)
- test files for the renamed/repaired surfaces (enumerate)

Protected: everything else. `my.*` names are the agent-facing teaching
surface — new public entrypoints get correct docstrings and Malli
schemas (they RENDER into agent context; docstrings are true
current-state). The diffusion tree consumers (`retrieval`, `oracle`,
`worker-validator`) rewire their requires like any consumer. No
commits, no lifecycle ops.

## Gates

Focused repl/plan/autocomplete/loop/generate-code selectors, then FULL
`bin/test-cljs` once (baseline 1508/7276 — record after) and
`bin/test-writer` once (host/context loads repl surfaces — baseline
369/2781). The internal-require gate green with ZERO allowlist rows is
the unit's signature proof.
