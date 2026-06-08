---
type: issue
status: active
tags: [issue, agent, flow]
severity: friction
---

# Agent flails on the API: promised "## What you can do" section is absent

## Problem

Observed by watching a live turn (agent `cUk-2606081347`, 2026-06-08, after the
context fix `5f2a564` so the agent actually receives context):

1. **The worked-examples section is missing.** The system prompt sticky says
   *"you'll see worked examples for in every turn's `## What you can do`
   section"* — but `substrate-default-ctx` (`agent.cljs:1330`) has NO such
   section (system, messages, current-ns, warnings, recent-evals, prompt). So the
   agent never sees the seon.db API shapes and guesses:
   - called `(seon.db/transact! [{…}])` positionally (it's map-in
     `{:seon.db/tx-data […]}`) — the error message teaches it, but it costs a
     turn;
   - hallucinated `seon.agent/current-agent-id` (it's `seon.db/current-agent-id`);
   - used unregistered `:seon.message/session`.
2. **Backticks in narration break the reader.** The agent wrote prose with
   `` `inline code` ``; the Clojure reader read the backtick as syntax-quote →
   `"Invalid character: ` found while reading keyword"` and several nil forms.
   The system prompt warns against code fences but not inline backticks, and the
   agent uses them anyway.

## Acceptance criteria

- A derived `capabilities-section` (the "## What you can do") added to
  `substrate-default-ctx`, rendering worked examples for the core seon.db API
  (`transact!`/`query`/`pull`/`entity`/`current-agent-id`) with their **map-in**
  shapes — derived from the registered `:seon.fn` arglists + docstrings (NOT a
  hardcoded blob; context is derived). Shows the agent the exact call shape.
- The section is bounded (don't dump every fn — the core API + the agent's own
  ns fns).
- Backtick robustness: either the reader tolerates backticks in `;`-narration, or
  the system prompt explicitly says "no backticks in narration either", or the
  parser strips/handles them. Pick the least-magic option.
- A test: the assembled context contains the worked-example call shapes; an agent
  with the section present gets `transact!`'s map-in shape in context.

## Status — capabilities-section SHIPPED (2026-06-08)

The worked-examples bug is fixed. The backtick robustness item is a flagged
recommendation (below) — implemented only the one-line system-prompt clause.

- Added `capabilities-section` (`src/seon/agent.cljs`, right after
  `system-section`) rendering the `## What you can do` block. DERIVED from the
  persisted core `:seon.fn` entities — for each of `capability-syms`
  (`seon.db/transact!` `query` `pull` `entity` `current-agent-id`) it pulls
  `[:seon.fn/sym :seon.fn/arglists :seon.fn/doc]` and renders the map-in call
  shape + a one-line doc, plus a fully-worked `transact!` (with
  `:seon.db/tx-data` vector) and `query` example. Bounded to the curated core
  API only (~1.4 KB live; the section keeps fns that resolve, skips absent
  ones). NOT an unbounded fn dump.
- Slotted into `substrate-default-ctx` at `:seon.ctx/priority 15`
  (`:system` 10 → **`:capabilities` 15** → `:messages` 20 …). Both the agent
  prompt path (`render-prompt`) and the inspector left pane go through the one
  composer `assemble-context`, so the section appears in both with no extra
  wiring.
- Added `seon.db/entity` + `seon.db/current-agent-id` to
  `seon.client/seed-core-fns!` `core-fn-curated` so they persist as `:seon.fn`
  entities (the derivation source — code-as-data, not a section special-case).
  Also corrected a pre-existing drift: the curated `seon.db/pull` arglist said
  `[pattern eid]` but the real fn + all examples are `[pull-pattern ref]` — the
  persisted arglist now matches the real call shape.
- System-prompt sticky (`seon.ai.deepseek/default-system-prompt`) already
  promised `## What you can do`; the heading now matches. Added one line
  forbidding inline backticks in narration (see recommendation).
- Live-verified (agent `cUk-2606081347`): `render-prompt` now contains the
  `## What you can do` block (~1.4 KB); total context 26.5 KB, bounded.
- Tests: `test/seon/agent-context-test.cljs` — section-in-default-ctx,
  worked-call-shapes-in-assembled-context, derived-from-`:seon.fn`-arglists,
  bounded; existing 5 tests stay green. 7 tests / 28 assertions, 0 failures.

## Backtick robustness — RECOMMENDATION (not magic-fixed)

Repro: `(seon.parse/parse-forms "Use the \`:seon.db/tx-data\` key.\n(+ 1 2)")`
→ the bad span becomes a `{:kind :read :ok? false}` entry with
`"Invalid character: \` found while reading keyword"`, and the *good* `(+ 1 2)`
form still parses. The error is `` `:keyword` `` (syntax-quote of a keyword,
then a stray `` ` `` while still reading the keyword token). Backticks **inside
`;`-comments parse fine** — the failure is only on prose lines the LLM did NOT
prefix with `;`. So the parser is already resilient (it recovers and keeps
going); the cost is a polluted eval log + a wasted form, not a broken turn.

Implemented (one-line, no-magic): added an explicit clause to
`default-system-prompt` — *do NOT use inline backticks in narration; a backtick
is the syntax-quote reader macro, so `` `:some/keyword` `` throws "Invalid
character: \` found while reading keyword". Write keywords plainly in `;`
comments.* This mirrors the existing no-code-fences guidance.

NOT implemented (flagged for the user to decide — would be parser magic):

- Stripping backticks from non-comment prose before reading. Risky: a lone
  `` ` `` is legitimately syntax-quote and the LLM may intend it in real code
  (e.g. macro authoring). A blanket strip would corrupt valid forms.
- Treating a `` `:keyword` `` token specially in `try-parse-one-token`. This is
  special-casing the reader — exactly the kind of magic the issue says to
  avoid. The recovery path already degrades gracefully.

Recommendation: keep the system-prompt clause; do NOT add parser special-casing
unless live agents keep tripping on it AND the system-prompt line proves
insufficient. The graceful-recovery behavior is the right default.

## Refs

- `src/seon/agent.cljs` — `capabilities-section`, `capability-syms`,
  `substrate-default-ctx` (`:capabilities` at priority 15).
- `src/seon/client.cljs` — `core-fn-curated` / `seed-core-fns!` (derivation
  source; now seeds `entity` + `current-agent-id`).
- `src/seon/ai/deepseek.cljs` — `default-system-prompt` (heading + backtick
  clause).
- `src/seon/parse.cljc` — `parse-forms` / `try-parse-one-token` (backtick
  recovery path).
- Registered `:seon.fn` entities carry `:seon.fn/arglists` + `:seon.fn/doc` (the
  derivation source).
- Surfaced while validating [[context-derived-not-stored]] (now resolved).
