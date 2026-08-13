---
type: issue
status: open
severity: friction
tags: [issue, agent, render, schema, wave/evolving-session-phases]
---

# Generation dependency analysis ignores keywords

## Problem

The generated-opening explained set grows from executed results for
schema-declared entity identities and Clojure symbols, but namespaced
KEYWORDS never enter the frontier or explained set — form dependency
analysis ignores them entirely. A later generated form can therefore use
`:seon.cluster.message/content` without that key ever having been
introduced or explained, breaking the "context teaches" property (ruling
29's fixed point) for the keyword half of the owner's linkage model:
symbols AND spec'ed keywords are the literal programmatic dependencies.

## Evidence

The 2026-08-13 probe
([explained-set-results-probe-2026-08-13.md](../../prds/sci-execution-runtime/research/explained-set-results-probe-2026-08-13.md)):
verdict PARTIALLY — identities and result symbols grow readiness;
keywords are invisible to the analysis (`src/seon/bootstrap.clj` frontier
construction). Isolated live probe plus `seon.bootstrap-test` 5/107/0.

## Owner

`seon.bootstrap` dependency analysis. Design note before implementing:
whether a registered schema key needs an explanation FORM (its `doc` face)
or counts as self-explaining when its declaration is in the acquired
registry is a semantics choice the evolving-session phases should settle —
record the chosen rule in the implementation PRD, then make the analysis
match it.

## Acceptance

- Parsed-form and admitted-result KEYWORDS participate in dependency
  analysis under the chosen rule.
- One regression: a generated form whose only unexplained dependency is a
  namespaced keyword is preceded by that key's explanation form (or is
  ready by the self-explaining rule, asserted explicitly).
