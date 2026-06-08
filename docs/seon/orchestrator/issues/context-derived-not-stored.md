---
type: issue
status: resolved
tags: [issue, agent, flow]
severity: architectural
---

> **RESOLVED 2026-06-08 (commit `5f2a564`).** `assemble-ctx`→`assemble-context`,
> code-default section layout (no stored-`:seon.agent/ctx` dependency), agent
> path + inspector both call the one composer (byte-identical, verified live),
> prompt-text persists real bytes, eval-result rendering capped. Guards:
> `test/seon/agent_context_test.cljs` (5 tests/18 assertions). A whole-DB query
> over old bloated data OOM'd the pod mid-fix → see
> [[eval-memory-safety]] for the follow-up (store-time caps).

# Context depends on stored `:seon.agent/ctx` → silent empty context + path divergence

## Problem

The agent's context is assembled from a section list read out of **stored**
`:seon.agent/ctx` component entities (`agent/assemble-ctx`, `agent.cljs:1298`).
When those are absent (agent `LZL-2605271732` has none), the section list is
empty and the agent renders **~22 chars** of context — it runs blind. The same
default sections, when run, produce ~12.5K chars. Separately, the inspector left
pane renders context via a DIFFERENT fn (`render/assemble-ai-context`,
`inspector.cljs:114`, ~6129 chars) that does not read stored ctx — so the
webview shows a context the agent never receives. And `:seon.turn/prompt-text`
is persisted **empty** (no provenance of what was sent).

Verified live in the running pod, 2026-06-08.

## Root cause

Context depends on accumulated **stored state** instead of being a pure function
of the DB with a code default. Violates [[reactive-context]] ("derived by
default"). Two divergent paths exist because one trusts storage, one re-derives.

## Acceptance criteria

- One pure `assemble-context` fn of (db, agent-id); section layout from a CODE
  default (`substrate-default-ctx`), NOT stored `:seon.agent/ctx`.
- `render-prompt` (agent path) and the inspector left pane both call it —
  `assemble-ctx` vs `assemble-ai-context` collapsed to one.
- The turn persists the EXACT rendered prompt on `:seon.turn/prompt-text`
  (non-empty when sections have content).
- Regression test: an agent with NO stored `:seon.agent/ctx` still gets the full
  default context.
- Guard test: agent-path ≡ inspector-path ≡ persisted prompt-text.
- Full suite green, 0 warnings.

## Refs

- PRD: `docs/prds/agent-runtime/v2-context-render-prd-2026-06-08.md`
- `src/seon/agent.cljs:1298` (`assemble-ctx`), `:1330` (`substrate-default-ctx`),
  `:628` (`render-prompt`)
- `src/seon/render.cljs` (`assemble-ai-context`)
- `src/seon/web/inspector.cljs:114` (left pane)
- Component: [[reactive-context]]

## Related (separate issues, smaller, after the core)

- `:seon.test` entity kind missing — tests not persisted as data.
- map-in calling-convention primer — agent failed 4/7 evals calling `db/query`
  positionally.
- `current-ns-section` renders 0 for this agent — investigate.
