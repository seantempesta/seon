---
type: prd
status: active
tags: [prd, architecture, agent]
---

# Deletions and wiring PRD

## Owner rulings (2026-07-20)

Delete `dev/storage-shootout.js` and the `reference-code/integrant`
submodule. For the two orphan namespaces: wire up what is useful, quality
permitting.

## Quality assessment of the orphans

**`src/seon/agent/ctx/usage.cljs` (70 lines) — useful, retain and wire it in.**
Registered schemas, errors-as-values (nil on absent/garbage EDN), correct
per-provider normalization with the semantics documented (DeepSeek
`prompt_tokens` includes cached; Anthropic `input_tokens` excludes, cache
fields add). Two fixes while wiring: it uses `[:maybe ::usage-edn]` /
`[:maybe ::usage]` in the fn schema (banned; model absence as absent key /
omitted projection) and it must gain a consumer + test. Tighten the provider
boundary at the same time: counts must be non-negative integers; malformed or
unknown provider shapes must omit the normalized projection and surface a
debug diagnostic rather than silently becoming plausible all-zero usage.

Wiring ruling: a derived line in the existing debug turn projection, with the
agent-page transcript showing compact actual total/cached/output values. Render
`extract` of the turn's persisted
`:seon.agent.turn/llm-usage` next to the token estimates the UI already
shows; omit when `extract` yields nothing. One render fn in the owning ctx
block; no new block family unless the owner wants usage as its own block.

**`src/seon/ui/components.cljc` (278 lines) — delete, do not wire.**
Readable but built for the removed "future adapter" era: it references the
superseded `docs/prds/namespace-ui/design-system.md`, duplicates styling
the live `seon.ui.*`/render fns evolved independently (its `card`,
`page-header`, `status-styles` have zero consumers while live equivalents
exist), and its `type-colors` keys ("LAUNCH"/"MESSAGE"/...) belong to a log
view that no longer exists. Wiring it now would mean refactoring healthy
live UI onto an unproven parallel component layer — the exact second-
renderer smell. Git preserves it if a design-system pass ever wants the
palette tables.

## Other deletions in scope

| Item | Verdict | Evidence |
|---|---|---|
| `dev/storage-shootout.js` | delete (ruled) | scratch benchmark |
| `reference-code/integrant` submodule | delete (ruled); also drop its `.gitmodules` entry | zero consumers after Integrant era removal |
| `test/seon/agent/ctx/canvas_test.cljs` direct `datahike.api` use (B9) | rewrite through `seon.db` / test fixtures | boundary violation |
| `seon/dev/docstring.clj:193` duplicated predicates | extract the two predicates from `seon.agent.ctx.namespaces` into the owning `.cljc`; docstring.clj requires it | documented deliberate dup |
| "tile"/"verbs" test fixture strings | rename (rides with stage 2) | vocabulary PRD |

## Acceptance

Three suites green; require-graph re-scan shows no orphan regressions;
usage line renders live for one real turn (and omits for a turn without
usage); submodule removal leaves `git submodule status` clean and
`bin/seon up` unaffected.

## Owner rulings 2026-07-20 (second round)

1. What `ctx.usage` actually does (owner asked): it parses the persisted
   per-turn `:seon.agent.turn/llm-usage` provider response EDN and
   normalizes it to a `{total, cached, output, provider-shape}` TOKEN
   triple (provider ground truth — DeepSeek `prompt_tokens` includes
   cached, Anthropic `input_tokens` excludes + cache fields add). It is
   tokens, not characters — it complements `seon.ai.tokens/estimate`
   (estimates) with provider-reported actuals. Owner direction: likely the
   debug turn projection plus compact agent-page usage. This is settled: do
   not delete the namespace merely because it was temporarily orphaned.
2. Archive the namespace-ui PRD folder (ruled).

## New owner question (from the docstring fix, 2026-07-20)

`seon.agent.ctx/file-block{,-ai,-html}` is a live MECHANISM with ZERO
usage: the only file-backed context-section capability, but no shipped
manifest declares one since the identity-file-block seeding was deleted
(`c35677fa`, soul-off default). False DEPRECATED markers are fixed
(`f5c145ed`); keep-or-delete remains open: keep = retain the only
file-backed-section capability for future manifests; delete = three fewer
unreferenced fns, restore from Git when a manifest wants files again.
