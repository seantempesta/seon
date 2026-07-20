---
type: issue
status: resolved
tags: [agent, runtime, issue]
severity: friction
---

# Stream reply tail was silently unexecuted

## Problem

Stream mode executes only the first complete form in a provider reply, but the
recorded transcript did not say that later complete forms were skipped. The
reply blob preserved the bytes while the agent-facing history made the tail
look completed or merely conversational.

## Evidence

The Stage 1.6 corrective-steering audit records gap G3 at `reply-program`:
truncation retained entries through the first form and discarded every later
parsed entry without adding durable narration to the retained eval row.

## Owner

`seon.agent.turn/reply-program` owns the stream-mode parsed-entry boundary.

## Acceptance

- The retained eval row states the number of later complete forms that were
  not executed and directs the agent to resend the next form.
- Incomplete tail text is not counted as an executable form.
- Single-form replies gain no extra narration.
- Provider reply and blob bytes remain unchanged.
- Focused turn tests pass.

## Resolution

Commit `8e008470` annotates only the retained parsed form's narration with the
number of later complete forms not executed and the directive to resend the
next form. It never rewrites the provider reply or blob. Independent focused
proof passed 15 tests / 40 assertions with zero failures and zero errors.
