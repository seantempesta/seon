---
type: orchestrator
status: active
tags: [index, agent]
---

# Integrate-back — stable worktree → main after the runtime-reliability refactor

The autosuggest lane is pinned to `b4efd4f5` in the worktree
`/Users/sean/src/seon-stable` (branch `repl-autosuggest/stable`) so the
concurrent runtime-reliability refactor cannot destabilize it. Improvements
made here must be cherry-picked back onto the post-refactor main.

## Cherry-pick list (commits after the `b4efd4f5` pin)

- **`dd8bc8a7` — fix(ctx): compact cards render fully-qualified keywords,
  never `::`.** `src/seon/agent/ctx/namespaces.cljs` +
  `test/seon/agent/ctx/namespaces_test.cljs`. `abbrev-ns-kws` rewrote
  `:seon.db/tx-data` → `::tx-data` in compact cards; a card is read from the
  AGENT'S ns, so `::` resolves to the wrong ns and models fumbled it into
  malformed `::seon.db/tx-data`. Neutered to a no-op + dropped the conditional
  `::` in `compact-schema-line`. **`namespaces.cljs` is in the refactor
  agent's active territory** — reconcile against their version before applying;
  the change is small and localized (two helpers + their docstrings).

## Already in the shared codebase (pre-pin, no action)

- `3e0e0bff` — schema-reference closure (`ctx/referenced-schema-block`),
  non-nilable schema gate, `my.plan` directive error messages,
  `seon.ai/generate-plan`. Committed on the shared branch before the worktree
  pin, so the refactor agent already has it.
