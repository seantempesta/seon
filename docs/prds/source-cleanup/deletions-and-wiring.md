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

## file-block — RULED 2026-07-20: KEEP as the general mechanism

`seon.agent.ctx/file-block{,-ai,-html}` had zero usage after the
identity-file-block seeding deletion (`c35677fa`); false DEPRECATED
markers were fixed in `f5c145ed`. Owner ruling: KEEP — it is the GENERAL
mechanism for users to load any files into named, prioritized context
blocks via the config manifest (SOUL.md/AGENTS.md are just two such
declarations).

Proof the manifest path already works end to end (no decode fix needed):

- `resolve-agent-context` preserves a block map carrying
  `:seon.agent.ctx/file-path` + the two render symbols verbatim (loose
  `[:vector :map]` leaf; `file-path` is a registered attribute, so
  seed-copy transacts it and the wildcard `{:seon.agent/ctx [*]}` prompt
  pull hands it to the slot fns in the execution child).
- Behavioral test:
  `seon.ctx-test/manifest-file-block-renders-fresh-and-omits-when-absent`
  — decode preserved, present → renders priority-ordered, edited →
  fresh re-read, absent → omitted (no fallback).
- Live: `bin/seon config apply` of a manifest declaring a `:notes`
  file block (priority 30) seeded a fresh default-cluster agent whose
  `ctx-preview` showed `┌─ notes ─` between `:namespaces` (20) and
  `:canvas` (35); an edit landed on the next render; deleting the file
  removed the section. Cluster restored to `config/system.edn` after.
- A commented-out example of the general shape lives in the CONTEXT
  TREE section of `config/system.edn`. Issue note:
  `docs/seon/issues/archive/file-block-mechanism-unused-keep-or-delete.md`.
