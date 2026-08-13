---
type: issue
status: resolved
severity: friction
tags: [issue, render, repl, architecture]
---

# Return walk state and failures without comment notices

## Problem

The public walk wrapper returns errors and REPL-state metadata as strings that
begin with `;;`. These are system-authored displayed results, not agent-written
source comments.

## Evidence

`src/seon/render.clj:671-685` creates the comment-prefixed REPL-state suffix;
`src/seon/render.clj:687-785` appends it to the separately assembled walk prose
and converts every boundary failure into another text notice. The superseding
ruling is decision 11 in
[messaging, state, and reply-norm design](../../prds/sci-execution-runtime/research/messaging-state-design-notes-2026-08-03.md).

The strict-dogfood audit on 2026-08-12 also classifies the suffix as a ruling-28
violation: namespace, basis, and transaction time are facts or explicit render
inputs, but the wrapper bypasses declared render selection and writes their
context line itself.

## Owner

`seon.render` owns the public `walk` call and the final returned value.

## Acceptance

`seon.render/walk` returns ordinary structured or printable values for its
state and failures. The REPL displays the call and then that value, with no
comment-prefixed system notice or comment-only output entry.

## Resolution

Resolved by `4bc8104d8`. `walk-error` now prints a flat
`:seon.render/walk-failed` value, REPL state is an ordinary map, and successful
walks print a map containing their selected units and metadata. No public
return constructor prefixes those values with comment syntax.
