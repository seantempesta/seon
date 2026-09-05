---
type: issue
status: open
severity: blocker
tags: [issue, sci, wave/per-run-fork-context]
---

# Make namespace removal rebuild contracted definitions only

## Problem

`ns-unmap` and `remove-ns` persist incompatible namespace-removal meanings.
Neither produces the designed cold-path result in which the durable contracted
definition returns and the uncontracted session definition stays removed.

## Evidence

Agent B declared `streams.victim2/contracted` as a contracted function and
`streams.victim2/ephemeral` as an uncontracted Var. A live-only SCI evaluator
`ns-unmap` removed both immediately and made B's next form a lint-rejection,
but left both database rows intact; a restart resurrected both.

An actual run then applied `ns-unmap` to both names. Its two nil receipts
settled and both the `:seon.fn/sym` and `:seon.code.def/id` facts disappeared,
so neither can rebuild.

In the `remove-ns` variant, A removed B's namespace between B's barrier and
next form. B failed to resolve `contracted`, while A closed successfully.
After settlement the contracted program row still existed, and session rows
included the ephemeral value plus an unrestorable contracted shadow. The
uncontracted value therefore remains eligible for cold restore too.

The exact receipt and row queries are in
[concurrency streams crossed](../../prds/sci-execution-runtime/research/concurrency-streams-crossed-2026-08-04.md).

## Owner

The per-run candidate-context namespace delta and durable placement gate.

## Acceptance

- A foreign run cannot delete another namespace's contracted program row.
- The removal delta records an uncontracted removal so session-image restore
  does not resurrect it.
- During the originating run, removal is isolated to its candidate context.
- After adoption/rebuild, the contracted definition resolves and the
  uncontracted name does not.
- Deterministic regressions cover both `ns-unmap` and `remove-ns`.
