---
type: issue
status: open
severity: friction
tags: [issue, render, repl, architecture]
---

# Return walk state and failures without comment notices

## Problem

The public walk wrapper returns errors and REPL-state metadata as strings that
begin with `;;`. These are system-authored displayed results, not agent-written
source comments.

## Evidence

`src/seon/render.clj:375-377` creates the comment-prefixed walk error and
`:404-408` creates the comment-prefixed REPL-state suffix. The superseding
ruling is decision 11 in
[messaging, state, and reply-norm design](../../prds/sci-execution-runtime/research/messaging-state-design-notes-2026-08-03.md).

## Owner

`seon.render` owns the public `walk` call and the final returned value.

## Acceptance

`seon.render/walk` returns ordinary structured or printable values for its
state and failures. The REPL displays the call and then that value, with no
comment-prefixed system notice or comment-only output entry.
