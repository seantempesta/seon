---
type: issue
status: open
severity: cleanup
tags: [issue, agent, index]
---

# Deprecated skills and context functions remain eligible for program indexing

## Problem

Five `my.skills` and `seon.agent.ctx` functions are explicitly deprecated in
their docstrings, but remain public first-party functions. The build-derived
program index therefore exposes them as callable function rows and potential
context/autocomplete distractors. Some context functions also still have
source and test callers, so neither blind deletion nor ignoring the stale
deprecation claim is sound.

## Evidence

- `src/my/skills.cljs` defines both functions publicly and begins both
  docstrings with `DEPRECATED`.
- `src/seon/agent/ctx.cljs` does the same for `file-block-ai`,
  `file-block-html`, and `file-block`. Unlike the skills functions,
  `file-block` and its view slots remain wired into current client/context code
  and focused tests, making their documented lifecycle conflict source-proven.
- `src/seon/indexing.clj::public-fn-vars` intentionally indexes every public
  first-party function in the build require closure. It has no deprecation
  concept, so both functions remain eligible by construction.
- `src/seon/client.cljs` persists every macro-selected var as a `:seon.fn` row;
  the row schema has no lifecycle/deprecation attribute that downstream card,
  autocomplete, or embedding consumers could use consistently.
- `tool-surface-overhaul-2026-07-12.md` observed the skills functions in the
  exported index as stale-card distractors and later confirmed they still
  rendered as index cards.

## Owner

`src/my/skills.cljs` and `src/seon/agent/ctx.cljs` own whether each function is
canonical or retired. The one analyzer/program-graph indexing mechanism owns
any general callability rule; downstream renderers and exporters must not
maintain symbol blacklists.

## Acceptance

- Current callers, manifest symbols, and database render nodes are audited for
  all five functions. A function that is actually canonical has its stale
  deprecation claim removed with current ownership documented; a retired one
  has callers/data migrated to the canonical context/canvas mechanism and is
  deleted in place.
- If deprecation must be represented generally, it is explicit source metadata
  projected through the one `:seon.fn` program graph with a registered schema
  and a single documented eligibility rule. The rule distinguishes callable
  agent surface from historical/debug visibility without symbol-specific
  filters.
- After reconciliation, no function that remains deprecated appears in
  agent-callable cards, autocomplete distractors, or embedding inputs. No stale
  `:seon.fn` row survives boot reconciliation.
- Focused index/reconciliation tests cover deletion or lifecycle filtering,
  and a live database query plus rendered-context/export proof confirms the
  functions are absent from the callable surface.
