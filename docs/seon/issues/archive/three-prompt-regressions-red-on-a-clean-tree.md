---
type: issue
status: resolved
severity: friction
tags: [issue, prompt, render, test, wave/live-drive-context]
---

# Three prompt regressions are red on a clean tree

## Problem

`bin/test seon.cluster.prompt-test` on a clean tree at `c61fe946b`
(2026-08-28, no working-tree edits) fails three tests:

- `identical-context-reuses-retained-ai-render-bytes`
- `prompt-budget-compacts-then-refuses-at-distance-zero`
- `prompt-is-derived-append-only-repl-history`

`seon.cluster.prompt-test` is not in the platform tier, and the
bare gate's reachability selection evidently has not run these since
the change that broke them landed, so the live prompt path's own
regressions went red with no gate noticing — the absent-signal class
at the suite level.

## Owner

`seon.cluster.prompt` / `seon.render` history since the last known
green of this namespace. Diagnosis must decide per test whether the
production behavior regressed or the expectation is stale under a
ruled change (retained-bytes reuse and append-only history are both
touched by the current evals/results-as-data design direction) — the
fix for a stale expectation is the expectation.

## Acceptance

`bin/test seon.cluster.prompt-test` green on a clean tree, with any
behavior change named against its ruling.

## Resolution (2026-08-28)

Root cause: the fixture class, not production — clip-ripout's required
`:seon.config.eval.result/max-source` was missing from the prompt-test
caps map (the earlier three-vs-seven attribution was an artifact of a
truncated log; the clean tree failed seven, all this class). One line
added; `bin/test seon.cluster.prompt-test` fully green. Other test
files still carry caps maps without `max-source`; they cross no
validated boundary today and light up loudly if one ever does.
