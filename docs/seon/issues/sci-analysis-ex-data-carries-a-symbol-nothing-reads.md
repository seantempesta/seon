---
type: issue
status: open
severity: cleanup
tags: [issue, dependency, agent]
---

# Read the symbol SCI already puts in analysis ex-data

## Problem

Our SCI fork carries a commit that attaches the offending symbol to the
ex-data of an "Unable to resolve symbol" analysis error. Nothing in Seon
reads it. The agent-facing error value still exposes the symbol only inside
a message string, so recovering it means parsing prose — which is exactly
what the commit was written to avoid.

It is the one Seon-authored SCI commit with no caller: five of six are
load-bearing, this one is carried maintenance cost for nothing.

## Evidence

`reference-code/sci/src/sci/impl/resolve.cljc:333` attaches
`{:phase "analysis" :sci.impl/symbol sym}` (fork commit `8fac6e8`, "Add
unresolved symbol to analysis error data").

`:sci.impl/symbol` has zero hits in `src/` and `test/`.

`src/seon/sci/eval.clj:373-377` already stores the raw `ex-data` on the
error value, so the key is present in what Seon captures and simply never
projected.

Seon's own `:unresolved-symbol` (`src/seon/fn/analyzer.clj`,
`test/seon/fn/analyzer_test.clj:178`, `test/seon/cluster/loop_test.clj:71`)
comes from the static analyzer, not from SCI — a different path for the same
concept.

Full sweep:
`docs/prds/sci-execution-runtime/research/upstream-delta-sweep-2026-07-31.md`.

## Owner

`seon.sci.eval` — the eval boundary that builds the flat error value an
agent sees.

## Acceptance

- An agent whose eval references an unresolved symbol receives that symbol
  as a value on the error, not only inside a message string.
- A test asserts the symbol is present as data, without matching message
  prose.
- Or the SCI commit is dropped from the fork as unused — but it may not stay
  in both states.
