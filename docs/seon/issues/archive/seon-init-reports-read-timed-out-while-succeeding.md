---
type: issue
status: superseded
severity: friction
tags: [issue, operator, render]
---

# `bin/seon init` reports only `✗ Read timed out` while the operation succeeds

## Evidence

Two lanes independently on 2026-08-03:

- `keyword-edges-gate`: shared-root init printed only `✗ Read timed out`;
  the equivalent publication was in fact proceeding (the root's writer was
  separately dead, but the message said nothing about either fact).
- `operator-stale-vars`: `bin/seon init` twice printed only
  `✗ Read timed out` even though the operation completed; the same
  publication run in-JVM succeeded clearly in 27 seconds.

## Observed mismatch

The Babashka client's prepl read timeout fires while the in-JVM operation
continues and succeeds. The client then prints a failure-shaped line for a
success, with no indication the work is still running or where to look.
This is a lying operator face: a timeout used as the primary signal for an
observable in-process operation (the timeout-as-design-smell rule — the
completion IS observable, the client just stops listening).

## Expected owner

`script/seon/fresh_operator.clj` client read path. The
[operator-integration PRD](../../prds/operator-integration/README.md)
slice 2 (bin/seon becomes a thin client calling `seon.operator` verbs) is
the natural fix point: `publish!` returns a completion, and the client
waits on the verb's answer rather than a socket-read clock. If slice 2 is
not imminent, the interim fix is honest wording plus following the
operation to completion.

## Acceptance

A slow-but-successful `bin/seon init` prints the success result; a genuine
failure prints the flat error; no success path ever prints `✗`.

## Superseded by

The later blocker investigation isolated the silence-backstop cause and
closed it with recurring and live proof in
[`bin/seon init` dies at the silence backstop because publication emits no events](init-publication-silent-beyond-backstop.md).
The separate failure-face problem remains tracked by
[A failed `bin/seon init` dumps the entire prepl event history instead of the cause](../init-failure-dumps-entire-prepl-event-history.md).
