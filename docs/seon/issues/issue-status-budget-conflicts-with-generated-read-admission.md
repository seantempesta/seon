---
type: issue
status: resolved
severity: blocker
created: 2026-09-17
tags: [issue, turn, agent, database]
---

# Issue status needs budget evidence that generated reads reject

## Problem

The S7 T3 ruling requires `my.issue/status` to carry remaining provider-turn
budget and to refresh through the existing system-turn since-diff. The
existing `seon.turn/generated-read-fault` refuses a generated read whose
evidence names turn-taking attributes. Reading the existing budget owner
therefore cannot satisfy both contracts unchanged.

## Evidence

Read-only JVM probe on default PID 53320, 2026-09-16 at 17:58 UTC:
`seon.turn/turns-left` for Juniper returned 29 and captured five reads.
Passing `seon.db/read-evidence` of those reads to
`seon.turn/generated-read-fault` returned
`:seon.turn/generated-read-depends-on-turns`, naming exactly
`#{:seon.turn/agent :seon.turn/attempts :seon.turn/id :seon.turn/reply-size}`.
The retained probe is in
[the S7 probe file](../../prds/steward-platform/research/issue-task-loop-s7-probe-2026-09-17.clj).

This differs from the resolved
[false dependency issue](issue-status-generated-read-depends-on-turn-taking.md):
these dependencies are real, not an unknown attribute set treated as every
inert attribute.

## Owner

The owner’s C5 ruling confirms status as issue data. S7 admits the existing
`my.issue/status` read for an assigned issue through the existing generated-read
guard. The since-diff still compares shown values; an unchanged system pass
must append nothing. Other generated reads retain the turn-activity refusal.
The virtual-turn regression now checks status stability, exhaustion, and resume;
the final in-process regression passed 49 assertions with zero failures or
errors. The real default worker also recorded the two changed status evaluations
and exhaustion through the same read. The stale source-adoption marker remains
a separate boundary documented in the S7 landing note.

## Acceptance

The canonical virtual-turn regression stores issue status including remaining
budget after an ordinary turn, then a second unchanged system pass appends
nothing. Conversational turn behavior retains its ruled semantics. The full
S7 acceptance still requires exhaustion, one root message, and resume.
