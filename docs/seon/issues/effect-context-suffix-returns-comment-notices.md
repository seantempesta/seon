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
`src/seon/effect.clj:618-634`; `seon.render.walk/prose` appends that suffix to
the displayed walk. The superseding ruling is decision 11 in
[messaging, state, and reply-norm design](../../prds/sci-execution-runtime/research/messaging-state-design-notes-2026-08-03.md).

## Owner

`seon.effect` owns the derived effect-state projection and its guidance.

## Acceptance

Pending work, completed background results, long foreground effects, and await
guidance render as ordinary computed data or printed values without comment
prefixes. Absence still omits the projection, and no new stored notice path is
introduced.
