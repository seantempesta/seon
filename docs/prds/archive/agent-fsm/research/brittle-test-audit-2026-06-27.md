---
type: research
status: active
tags: [research, agent]
---

# Brittle-test audit — context/render/system-text exact-text pins (2026-06-27)

## TL;DR

Read every non-`.disabled` test ns under `test/` that touches a
context / render / prompt / system-text surface (the owner's list plus a
full-tree grep sweep). **Verdict: the suite is already disciplined.**
Nearly every render/context test carries an explicit "assert MECHANISM,
not exact strings" docstring/comment and anchors on contract tokens
(attr keywords, fn syms), byte-identity, or appear/vanish — exactly the
KEEP shapes.

- **Clean REMOVE siblings found: 0** (beyond the two already being
  removed by another agent: `seon.teachings-test` and the `:your-entity`
  tests in `ctx_test.cljs`). The teachings "re-run every docstring
  example" pattern is **unique** — no other test replays context-surface
  examples. No deftest exists whose primary purpose is to pin the exact
  prose of the agent prompt / system-text / a context section /
  namespace block.
- **BORDERLINE: 8 deftests** — each is a strong behavioral/mechanism
  test that contains ONE incidental exact-prose assertion of a render
  surface (a welcome-tile phrase, a synthesized system-notice phrase, or
  a paused-JVM-track empty-state phrase). Removing the whole deftest
  loses real coverage; the fix (where the owner wants one) is to swap the
  single prose `includes?` for a structural/contract-token check, NOT to
  delete the test. Listed below for case-by-case judgement.
- **KEEP: ~90+ context/render deftests** across ctx_test, render_test,
  render/{value,chat,live-tile,code}, agent_render_namespace_test,
  index_core_test, warn_test, debug_test, inspector_chips_test,
  web/reactive/transform_test, web/tile_test, repl_parity_test,
  derive_test, ai/{openai-compat,anthropic}_test, kb_test, todo_test,
  handlers/test_test, ctx/history_test — all behavior/mechanism/
  byte-identity/appear-vanish.

Honesty note: the owner asked me to "find the siblings." The evidence is
that they largely **don't exist as clean REMOVE candidates** — the
de-pinning discipline already landed across the suite. I am not inflating
a REMOVE list to satisfy the request. The BORDERLINE list is where the
remaining (small) brittleness lives.

---

## REMOVE (whole-deftest exact-text / replay over a refactoring surface)

| test-ns | deftest | file:line | offending assertion | reason |
|---|---|---|---|---|
| _(none beyond the two already excluded)_ | — | — | — | The teachings replay-harness pattern is unique; no other deftest pins agent-prompt / system-text / context-section prose as its purpose. |

Already being removed by another agent (NOT re-listed here, per
instructions): `seon.teachings-test` (whole ns — replays every docstring
example) and the `:your-entity` removed-section tests in
`test/seon/ctx_test.cljs`.

---

## BORDERLINE (strong test, ONE incidental prose pin — owner judges)

Ranked most-prose-pinny first. For each, the test's PRIMARY assertions
are behavioral/mechanism (KEEP-worthy); only the noted line pins exact
render-surface wording. Recommended fix = replace that one assertion with
a structural/contract-token check, not delete the deftest.

| test-ns | deftest | file:line | offending assertion | reason |
|---|---|---|---|---|
| `seon.render.live-tile-test` | `welcome-generic-without-purpose-or-name` | `test/seon/render/live_tile_test.cljs:127` | `(re-find #"finding my purpose" (hiccup-strings hiccup))` | The deftest's ONLY assertion pins the exact welcome fallback phrase. Purest prose-pin found. Behavioral intent ("generic fallback renders, non-blank") could be asserted structurally. |
| `seon.repl.context-test` | `for-data-test` | `test/seon/repl/context_test.clj:118` | `(str/includes? result "No matching renderers")` | Pins exact empty-state prose of a render surface. PAUSED JVM track (lower stakes). |
| `seon.render.code-test` | `render-ns-docs-no-functions-test` | `test/seon/render/code_test.clj:202` | `(re-find #"No functions found" doc)` | Pins exact empty-state prose of the doc-render surface. PAUSED JVM track. |
| `seon.render.chat-test` | `provider-failure-renders-a-system-line` | `test/seon/render/chat_test.cljs:409` | `(str/includes? content "resume on your next message")` | Pins the exact synthesized system-notice phrase. PRIMARY contract (exactly the :error turn surfaces, in time order) is strong behavioral; the phrase pin is incidental. |
| `seon.render.live-tile-test` | `welcome-compact-shows-purpose-id-and-truthful-twin` | `test/seon/render/live_tile_test.cljs:142` | `(re-find #"core default" ai)` + `(not (re-find #"haven't wired" ai))` | "core default" is welcome-twin prose; the rest of the deftest anchors on contract token `:seon.render.live-canvas/content` (fine) and the agent id (fine). |
| `seon.render.live-tile-test` | `welcome-emits-tagged-blocks-and-twin` | `test/seon/render/live_tile_test.cljs:111` | `(re-find #"update this panel" ai)` | Prose pin of the panel line's twin (note: the hiccup side uses the `canvas/panel-line` CONST — good; only the `ai` twin pins the lowercased phrase). Rest of deftest = zoom-block/greeting/date mechanism (KEEP). |
| `seon.render.live-tile-test` | `welcome-compact-shows-the-last-reply` | `test/seon/render/live_tile_test.cljs:387` (via `render_test.cljs` sibling) | `(re-find #"reply!" ...)` negative + reply-string positive | Reply string is fixture-supplied (behavioral). The `#"reply!"` exclusion is a structural-ish guard. Marginal — listed for completeness. |
| `seon.web.inspector-chips-test` | `default-header-is-user-meaningful-no-machinery` / `system-toggle-reveals-the-machinery-row` | `test/seon/web/inspector_chips_test.cljs:108,207` | enumerates chip labels by name (`datoms/fns/schemas/tests/findings`) | Matches the "pin exact labels of a tunable surface" pattern. BUT it is fundamentally an appear/vanish + toggle-behavior test, and the labels are the feature's own contract tokens, not refactoring-prone prose. Leans KEEP. |

---

## KEEP (count + representative examples — not enumerated)

~90+ context/render deftests are legitimate per the owner's standing
rule. They were spot-verified to assert MECHANISM, not wording.
Representative anchors:

- **Byte-identity / determinism** — `ctx_test.cljs`
  `prompt-and-inspector-are-byte-identical`,
  `stable-volatile-split-determinism`, `off-path-is-byte-identical`;
  `ai/anthropic_test.cljs` cache-control split tests (wire-shape, supplies
  its own ctx). Assert the mechanism (one producer, two consumers; stable
  prefix invariant), not text.
- **Appear/vanish (reactive suppression)** — `ctx_test.cljs`
  `inventory-section-renders-stored-kinds-compact` /
  `relevant-source-section-*`; `warn_test.cljs` `render-warnings-empty-when-clean`
  plus every `*-self-heals-*`; `agent/todo_test.cljs`
  `block-is-empty-when-no-open-work` / `section-tolerates-absent-db`.
- **Structural invariants de-pinned from wording** — `ctx_test.cljs`
  `system-text-has-no-bare-margin-prose` (every line blank/indented/`;`/code-form),
  `file-section-present-file-yields-section-both-views` (every line is a
  `;` comment). These test SHAPE, explicitly not content.
- **Behavior / mechanism / logic** — `agent_render_namespace_test.cljs`
  (anchors on `(ns X` heads + cycle-once, not prose); `index_core_test.cljs`
  (real source vs `,,,` stub, derived counts never hardcoded);
  `render/value_test.cljs` (bounds/markers/paths); `web/reactive/transform_test.cljs`
  (URL decode round-trips + RCE refusal); `render_test.cljs(j)`
  (`humanize`/`find-renderer` pure-fn logic — exact strings there are the
  fn's transformation, i.e. its contract, not a render surface);
  `derive_test.cljs`, `repl_parity_test.cljs`, `ctx/history_test.clj`.
- **Contract-token anchors inside warn/debug** — `warn_test.cljs` pins
  `:seon.db/identity`, `{:seon.db/entity true}`, `seon.agent.fs/grants`
  (keywords/fn-names = contract tokens), and throw messages
  (behavioral) — never explanation prose. `debug_test.cljs` is filesystem
  round-trip behavior.

`humanize`/`greeting`/value-marker exact-string `(is (= "..." ...))`
assertions are **KEEP**: they test a pure function's output contract, not
a refactoring-prone render surface.

---

## Method notes

- Source of the classification criterion: owner rule — "tests should test
  BEHAVIOR and higher-level concepts, not exact text"; KEEP
  byte-identity/appear-vanish/structural-invariant/mechanism; REMOVE only
  exact-prose pins of prompt/system-text/context-section/namespace-block
  or example-replays.
- Searched the whole `test/` tree (cljs + clj + cljc, skipping
  `.disabled`) for: `system-text|render-context|render-prompt|ctx-preview`,
  `taught|teaching|run-example|replay`, `is (= "..."` over render output,
  and multiword prose inside `str/includes?`/`re-find`. Read in full every
  file on the owner's list plus chat/live-canvas/value/code/transform/kb/
  todo/openai-compat/anthropic/inspector-chips.
- One already-handled disabled sibling worth noting: `inspector_chips_test.cljs`
  `completed-agents-hidden-by-default-toggle-reveals` is already
  `#_`-discarded (5→3 state collapse) — no action needed.
