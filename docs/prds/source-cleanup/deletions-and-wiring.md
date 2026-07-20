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

**`src/seon/agent/ctx/usage.cljs` (70 lines) — well written, wire it in.**
Registered schemas, errors-as-values (nil on absent/garbage EDN), correct
per-provider normalization with the semantics documented (DeepSeek
`prompt_tokens` includes cached; Anthropic `input_tokens` excludes, cache
fields add). Two fixes while wiring: it uses `[:maybe ::usage-edn]` /
`[:maybe ::usage]` in the fn schema (banned; model absence as absent key /
omitted projection) and it must gain a consumer + test.

Wiring recommendation: a derived line in the existing transcript/usage
surface — render `extract` of the turn's persisted
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

## Open questions for the owner

1. Usage wiring surface: inline line in the transcript block (recommended)
   or a separate ctx block family with its own priority?
2. `docs/prds/namespace-ui/design-system.md` is superseded by deletion of
   its implementation — archive the PRD folder or leave as history?
