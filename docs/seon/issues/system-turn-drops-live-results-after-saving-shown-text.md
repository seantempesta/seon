---
type: issue
status: open
severity: friction
tags: [issue, render, sci, agent, wave/agent-context]
---

# Retain system-turn results in the agent's SCI context

## Evidence — 2026-09-10

Default's newly generated Juniper opening had saved evaluations but no live
objects reachable by the evaluation HTML entry. It therefore correctly showed
saved text and an unavailable-live-value annotation, even without a JVM restart.

`src/seon/turn.clj`, `system-turn`, computes each selected read through
`preview-sources`, then stores evaluations with `record-evaluated-tx`.
`preview-sources` explicitly forks without an evaluation identity;
`evaluate-sources` binds objects only when it has that identity. The preview
fork is discarded after the system turn saves its shown text. The ordinary
agent turn uses its persistent `:seon.sci.eval/agent-ctx` instead.

## Owner and acceptance

The turn/evaluation owner must retain the actual results of successfully
recorded system evaluations through the existing `seon.sci.eval/bind-result!`
mechanism. Do not rerun source during HTML rendering or reconstruct an object
from saved text. Prove on the canonical fixture that a system turn's handle
resolves to its actual result in the agent context, its HTML pair receives
that value, a preview alone installs no handle, and restart loses the object.

The REPL display change uses the live map when available and explicitly
labels saved-text fallback. It does not change system-turn execution.

## Context-renders dependency, 2026-09-14

The owner's change-only reread rule requires an executable full-value hint.
The current `system-turn` path still uses disposable `preview-sources`,
while `seon.cluster.agent/arm!` creates the retained agent context only when
the graph is armed. An opening can be stored before that call. Binding into
the cluster's base context would put agent result objects under the wrong
owner; manufacturing a handle from an evaluation id does not retain its
value. The existing agent-context acquisition must be available before a
stored opening and reused by `arm!`. This is a dependency of the requested
system-result and printer regressions, not an HTML rendering change.
