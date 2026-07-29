---
type: issue
status: open
severity: friction
tags: [issue, agent-runtime, run-loop, generate-code]
---

# An agent can be assigned its own red form, and the loop delivers it

## Problem

Owner routing falls back to the run's AUTHOR when a red form's namespace has no
assigned agent — which is total and correct as ownership. But the loop then
emits `problems/assignment-value` for that problem unconditionally, so when the
author is the failing agent itself the terminal transaction commits a message
FROM an agent TO the same agent, and the ordinary wake delivers it. The agent
wakes to a message asking it to repair a problem it just created, with no new
information between the two turns.

The generate-code v0 plan calls self-delegation "an explicit refusal rule"
(§1.1, D2). No such rule exists in code: `seon.cluster.message/delivery` checks
only that the recipient exists, and `problems/form-problem` never compares the
owner with the author.

## Evidence

`test/seon/gen/loop_test.clj` during development, before `my.message/decline`
was bound into the agent context. Beta's own run failed at form 0 (the decline
call it could not resolve); that form had no namespace declaration, so the
reader attributed `user`, no agent owns `user`, and the author fallback made
BETA the owner of beta's own red form:

```text
assignment-[789 "beta"]  from beta  to beta  about problem-["<beta-run>" 0]
```

Two such self-assignments were committed in one drive.

## Expected owner

`seon.problems/form-problem` (which derives the owner) or `seon.cluster.loop`'s
terminal assembly (which decides whether to emit the assignment).

## Acceptance criteria

An author-owned red form produces no message. The problem still DERIVES — it is
the author's to fix and it keeps the plan unsettled exactly as any other
unsettled form does — but nothing is delivered, because a message an agent
sends itself carries no information the agent's own context does not already
have. One regression asserts that a red form whose owner is its author commits
zero message rows while remaining visible in the settlement derivation.
