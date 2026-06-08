---
type: issue
status: open
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

## Refs

- `src/seon/agent.cljs:1330` (`substrate-default-ctx`), the 6 section fns
- Registered `:seon.fn` entities carry `:seon.fn/arglists` + `:seon.fn/doc` (the
  derivation source).
- Surfaced while validating [[context-derived-not-stored]] (now resolved).
