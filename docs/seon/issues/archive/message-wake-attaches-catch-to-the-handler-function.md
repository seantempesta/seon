---
type: issue
status: superseded
severity: blocker
tags: [issue, agent, cljs]
---

# Attach message-wake failure handling to its Promise

## Problem

The scheduled message wake is missing the closing parenthesis for
`js/Promise.resolve`, so the compiled JavaScript attempts to read `.catch` from
the exception-handler function instead of attaching it to the Promise.

## Evidence

The first real user message logged an uncaught
`(function (exception) {...}).catch is not a function` immediately as its wake
ran. Source comparison with the adjacent `schedule-renew!` shows
`schedule-message-run!` closes `await`, the async function, and
`with-agent-repl`, but not `js/Promise.resolve` before the thread macro's
`.catch` form.

## Owner

`seon.agent.loop/schedule-message-run!` owns the one macrotask boundary for a
committed inbound message.

## Acceptance

- `.catch` is attached to the Promise returned by the agent-scoped wake.
- Focused loop tests compile and pass without an uncaught exception.
- A real inbound user message opens and drives its run without a pod-level core
  fault.

## Closed 2026-07-20

Verified stale by the issues triage (docs/prds/source-cleanup/research/issues-triage-2026-07-20.md §STALE): the described code or behavior no longer exists at HEAD; rg evidence in the triage doc.
