---
type: issue
status: open
severity: friction
tags: [issue, agent, render, architecture]
---

# Render effect notices as ordinary values

## Problem

Background-work guidance, pending/result notices, and long foreground-effect
notices are returned as `;;`-prefixed strings. These are system notices in the
display stream, so owner decision 11 forbids modeling them as comments.

## Evidence

`seon.effect/context-suffix` constructs every notice with a `;;` prefix at
`src/seon/effect.clj:690-779`; `seon.render.walk/prose` appends that suffix at
`src/seon/render/walk.clj:643-650` instead of reaching effect receipts through
their declared render functions. The superseding ruling is decision 11 in
[messaging, state, and reply-norm design](../../prds/sci-execution-runtime/research/messaging-state-design-notes-2026-08-03.md).

The strict-dogfood audit on 2026-08-12 also classifies this as a ruling-28
violation. Pending and completed work are database facts, but this function
assembles a prompt-only tail rather than letting the neighborhood walk and
declared effect renders produce ordinary history entries.

## Owner

`seon.effect` owns the derived effect-state projection and its guidance.

## Acceptance

Pending work, completed background results, long foreground effects, and await
guidance render as ordinary computed data or printed values without comment
prefixes. Absence still omits the projection, and no new stored notice path is
introduced.
