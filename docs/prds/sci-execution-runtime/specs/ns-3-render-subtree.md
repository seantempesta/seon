---
type: prd
status: active
tags: [prd, architecture]
---

# NS-3 — render-slot subtree renames

## Grounding preamble (mandatory)

Don't make things up: read the actual source of every file you touch and
every interface you connect to before editing. As you work, answer two
questions and report them in your summary: (a) now that you've read the
source, is there a better seam than the one this spec names? (b) what do
the existing owners call each thing — use their exact terms, never a
new synonym. **Stopping early to report a concern or a better seam is
FREE** — the session resumes with full context, and seam corrections are
exactly what we want. If anything in this spec contradicts what you find
in the source, stop and report rather than improvising.

## Goal

Make the tree say render-slot dispatch: the six per-entity-family
`render-ai`/`render-html` namespaces move under `seon.render.handlers.*`
(today `seon.handlers.*` reads as HTTP handlers next to `seon.web`), and
the web→render layering inversion `seon.web.view-unit` (consumed only by
`seon.render`/`seon.render.surface`) moves to `seon.render.view-unit`.
Owner decision D1, approved. Design authority (read it):
`docs/prds/sci-execution-runtime/research/namespace-hierarchy-design-2026-07-21.md`
§5 and §10 (NS-3).

Moves (real moves via `git mv`, all callers updated in the same change,
no compat namespace, no shims):

1. `seon.handlers.{eval,fn,message,ns,schema,test}` →
   `seon.render.handlers.{eval,fn,message,ns,schema,test}`
   (`src/seon/handlers/*.cljs` → `src/seon/render/handlers/*.cljs`)
2. `seon.web.view-unit` → `seon.render.view-unit`
   (`src/seon/web/view_unit.cljs` → `src/seon/render/view_unit.cljs`)

## Verified current state (grounded 2026-07-21; supersedes the design doc's drifted NS-3 counts)

- **Quoted-symbol references, not just requires** (a plain require-rg
  misses these): render slots reference handler fns as quoted symbols —
  `src/seon/agent.cljs:222,240,268,277`
  (`'seon.handlers.eval/render-ai` etc., 4 slot pairs),
  `src/seon/agent/message.cljs:58-59`,
  `src/seon/test/runner.cljs:171-172`. These symbols flow into
  registered facts, so a stale one fails at render time, not compile
  time — every one must be renamed, and your rg proof must cover
  `seon\.handlers` as a bare token (symbols, strings, docstrings), not
  only require forms.
- Requirers of `seon.handlers.*` in src: `agent.cljs`, `agent/ctx.cljs`,
  `agent/ctx/transcript.cljs`, `agent/message.cljs`, `client.cljs`,
  `render.cljs` (docstring mentions at :338, :363, :836),
  `test/runner.cljs`, `ui/clojure.cljs` (comment at :168). Tests:
  `test/seon/handlers/eval_test.cljs`, `test/seon/handlers/test_test.cljs`,
  `test/seon/schema_test.cljs`, `test/seon/db/protocol_test.clj`
  (inspect what that writer-side test actually references and report).
- `src/seon/handlers/test.cljs:23` registers
  `:seon.handlers.test/status-request` — a fn-boundary open-map shape
  over `:seon.test/*` fields, NOT entity-marked; it renames with the
  namespace safely. Re-verify while editing; if you find any
  entity-marked registration in the moved files, STOP AND REPORT (that
  would be a data migration, out of this unit's scope).
- `src/seon/handlers/eval.cljs:144,147` uses literal
  `:seon.handlers.eval/url` / `:seon.handlers.eval/root-id` keys,
  destructured locally via `::keys` at :154 — transient locals that
  rename with the ns; confirm no other consumer reads them.
- `seon.web.view-unit` consumers: `src/seon/render.cljs`,
  `src/seon/render/surface.cljs`, `test/seon/web/view_unit_test.cljs`.
- Because renamed registration symbols/facts can persist in an existing
  cluster database, this unit is a rename+reset boundary (owner
  no-lock-in ruling): the ORCHESTRATOR handles the cluster reset and
  live proof after integration — not you.

## Work

- Move the seven files with `git mv`; update every ns form, alias,
  quoted symbol, literal qualified keyword, and load-bearing
  docstring/comment reference (`render.cljs` docstrings teach
  `seon.handlers.*` as the first-party idiom — they must teach the new
  names; `ui/clojure.cljs:168`'s comment likewise).
- Test files move to mirror their namespaces
  (`test/seon/handlers/*` → `test/seon/render/handlers/*`,
  `test/seon/web/view_unit_test.cljs` →
  `test/seon/render/view_unit_test.cljs`) with requires updated.
- Keep alias names natural so call sites stay minimal (e.g.
  `[seon.render.handlers.eval :as handlers.eval]` or whatever the
  current alias is — preserve existing aliases where they still read
  correctly; report if you choose differently).

## Owned paths (touch nothing else)

- `src/seon/handlers/` (six files, moved) →
  `src/seon/render/handlers/`
- `src/seon/web/view_unit.cljs` → `src/seon/render/view_unit.cljs`
- Reference-only edits (requires/aliases/quoted symbols/literal
  keys/docstring mentions ONLY, no other changes): `src/seon/agent.cljs`,
  `src/seon/agent/ctx.cljs`, `src/seon/agent/ctx/transcript.cljs`,
  `src/seon/agent/message.cljs`, `src/seon/client.cljs`,
  `src/seon/render.cljs`, `src/seon/render/surface.cljs`,
  `src/seon/test/runner.cljs`, `src/seon/ui/clojure.cljs`
- The test files enumerated above.

Protected: everything else. Do not run `bin/seon up`/`restart`/`down`,
do not commit, do not touch git state beyond `git mv` for the moved
files. Leave your diff in the working tree for orchestrator review; the
orchestrator runs the cluster reset + live render proof after
integration.

## Gates (run them; report honest results)

- `bin/test-cljs` focused render/handlers/transcript/schema selectors
  while iterating, then the FULL suite once at the end (the quoted
  symbols cross agent/render/test-runner seams).
- rg proof in your summary: zero remaining `seon.handlers` and
  `seon.web.view-unit` tokens anywhere in `src/ test/` (any context —
  requires, symbols, keywords, strings, comments).
- If a compile/test fails on infrastructure (shadow cache lock,
  concurrent build weirdness) rather than an assertion, wait briefly
  and retry once before reporting.

Behavior must be identical — rename only, zero semantic change.
