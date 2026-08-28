---
type: issue
status: open
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
