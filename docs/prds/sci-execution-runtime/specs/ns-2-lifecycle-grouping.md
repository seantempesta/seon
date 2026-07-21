---
type: prd
status: active
tags: [prd, architecture]
---

# NS-2 — lifecycle grouping renames

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

Group the process-lifecycle namespaces under their real families so the
tree says what each thing is. Three moves, all owner-approved (decisions
D3/D4/D8 in the design authority — read it):
`docs/prds/sci-execution-runtime/research/namespace-hierarchy-design-2026-07-21.md`
§6 and §10 (NS-2).

1. `seon.state` → `seon.runtime.state`
   (`src/seon/state.cljs` → `src/seon/runtime/state.cljs`)
2. `seon.indexing` → `seon.client.indexing`
   (`src/seon/indexing.clj` → `src/seon/client/indexing.clj`)
3. Merge `seon.agent.runtime` INTO `seon.agent.lifecycle`
   (delete `src/seon/agent/runtime.cljs`; its ~134 lines of
   resume/unhost functions and registrations move into
   `src/seon/agent/lifecycle.cljs`, which owns the adjacent
   pause/terminate concern; combined ≈530 lines).

These are real moves — all callers updated in the same change, no
compatibility namespace, no alias shims, no `-v2`. Git history is the
archive.

## Verified current state (grounded 2026-07-21, this session)

Fan-in, computed from ns forms:

- `seon.state`: src = `src/seon/client.cljs` only; tests =
  `test/seon/state_test.cljs`, `test/seon/client_initialization_test.cljs`.
- `seon.indexing`: consumed via `(:require-macros [seon.indexing …])`
  at `src/seon/client.cljs` (compile-time macro ns); tests/fixtures =
  `test/seon/index_core_test.cljs`, `test/acme/extra_fixture.cljs`.
- `seon.agent.runtime`: src = `src/seon/client.cljs`,
  `src/seon/web/serve.cljs`; tests = `agent_loop_test`,
  `web/serve_test`, `client_advertisement_test`,
  `execution/integration_driver`, `schema_test`,
  `client_initialization_test`, `runtime/admission_test`.
- `src/seon/state.cljs` has ZERO `:seon.db/entity`-marked
  registrations — its registered schemas are fn-boundary shapes that
  rename safely with the namespace. Same claim was verified for
  `seon.agent.runtime` (11 fn-boundary registrations, 0 entity-marked)
  in the design doc; re-verify while editing and STOP AND REPORT if you
  find any entity-marked (persisted-attribute) registration — that
  would make the rename a data migration, which this unit is not.

## Work

Per move: `rg -l` the old ns name over `src/ test/` (both `[seon.state`
require forms and literal `:seon.state/...` fully-qualified keyword
uses — `::`-auto-resolved keys follow the ns automatically, but literal
qualified keywords and string references do not); edit every require,
alias, and literal key reference; move the file with `git mv`; keep
alias names natural (e.g. `[seon.runtime.state :as state]` keeps
call sites unchanged where the alias was already `state`).

Test files move/rename to mirror their namespaces
(`test/seon/state_test.cljs` → `test/seon/runtime/state_test.cljs`,
etc.); merged `seon.agent.runtime` tests fold into the lifecycle test
namespace or keep their own file with updated requires — follow what
the existing test tree does for merged namespaces; report your choice.

For the merge (move 3): preserve every public fn, docstring, and
registration from `agent/runtime.cljs` verbatim inside
`agent/lifecycle.cljs` (grouped coherently, not appended blindly);
update the two src consumers' requires; delete the old file.

## Owned paths (touch nothing else)

- `src/seon/state.cljs` → `src/seon/runtime/state.cljs`
- `src/seon/indexing.clj` → `src/seon/client/indexing.clj`
- `src/seon/agent/runtime.cljs` (deleted) +
  `src/seon/agent/lifecycle.cljs`
- `src/seon/client.cljs`, `src/seon/web/serve.cljs` — ONLY their
  ns-form requires / require-macros lines and any literal renamed-key
  references; no other edits in these two files
- the test files enumerated above (moved/edited requires + literal keys)

Protected: everything else. Another agent lane is active in this same
checkout on unrelated paths (`src/seon/repair/`, `src/seon/diffusion/`)
— do not touch those, do not run `bin/seon up`/`restart`/`down`, do not
commit, do not touch git state beyond `git mv` for the moved files.
Leave your diff in the working tree for orchestrator review; the
orchestrator runs the live boot proof after integrating both lanes.

## Gates (run them; report honest results)

- `bin/test-cljs` FULL suite once at the end (the moves cross
  agent/loop/client seams, so focused selection is not sufficient);
  while iterating use focused selectors.
- `rg` proof: zero remaining references to `seon.state`,
  `seon.indexing`, `seon.agent.runtime` anywhere in `src/ test/`
  (requires, aliases, literal keywords, require-macros) — include the
  command + output in your summary.
- If a compile/test fails on infrastructure (shadow cache lock,
  concurrent build weirdness) rather than an assertion, wait briefly
  and retry once before reporting.

Behavior must be identical — this is a rename/merge with zero semantic
change.
