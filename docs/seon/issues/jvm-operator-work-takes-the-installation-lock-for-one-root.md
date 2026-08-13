---
type: issue
status: open
severity: friction
tags: [issue, operator, class/n4, wave/operator-lock-contention]
---

# The JVM operator takes the installation lock for one root's own work

## Problem

`with-operator-lock` now derives the launcher's lifecycle lock from the
selected root, so two isolated `--root` operator commands no longer serialize
([archived issue](archive/an-isolated-operator-root-locks-the-shared-repository-root.md)).
The JVM-side owner still has the same shape in four places: `cleanup-root!`,
`cleanup-cluster!`, `collect!`, and `refork!` each take
`state/with-control-lock!` — the INSTALLATION-wide lock — for work that
touches exactly one managed root, which they already receive as
`:seon.operator/managed-root`.

So a cluster in root A running database/blob collection serializes against a
refork in root B, for no shared state. Only `reap-dead-roots!` genuinely spans
roots and belongs on that lock.

## Evidence

`src/seon/operator.clj:231,519,753,796` pass `repository-root` to
`state/with-control-lock!` while the enclosed `*-under-lock!` body operates on
`managed-root` alone. Compare `resources/seon/operator/state.clj`, where
`root-lifecycle-lock-path` is the per-root lock and `with-control-lock!`'s
docstring now says it is for cross-root work only.

## Owner

`src/seon/operator.clj`.

## Why it was not fixed with the launcher

The obvious change — take the managed root's lifecycle lock instead — would
DEADLOCK the common path. `bb -m seon.fresh-operator` holds that exact lock
for the whole command and then evaluates `refork-under-lock!` /
`cleanup-root-under-lock!` in a child JVM, which is why the private
`*-under-lock!` variants exist at all. A correct fix has to make the child's
custody of the parent's lock explicit rather than implied by a function name,
which is a design decision, not a rename.

## Acceptance criteria

- One root's cleanup, collection, and refork do not serialize against another
  root's, whether invoked from a cluster's own JVM or through the launcher.
- A child JVM's inherited custody of its parent's lifecycle lock is expressed
  in the call, not encoded in a `-under-lock!` suffix a caller must know.
- A regression drives one root's collection concurrently with another root's,
  and asserts neither waits on the other.

## Owner ruling (2026-08-08 night)

Keep serial, explicitly accepted: single-root operator work serializes on
the installation lock, with an honest annotation at the `-under-lock!`
sites naming this note. Revisit only when the parallel suite's
four-worker load measures real contention here. The per-root redesign
(lock-handle ownership transfer to children) is the recorded end state
if that day comes.

## Re-grounded evidence — 2026-08-13

**STILL-REAL at `06e654c76` as an unimplemented owner-ruling/documentation
gap, not as authorization for the per-root redesign.** The accepted serial
behavior remains at `src/seon/operator.clj:297-307,593-602,825-845,884-893`.
No one of those sites names this ruling or explains the deliberate exception.
At the same time, `resources/seon/operator/state.clj:402-408` says the
installation-wide lock is only for cross-root work and that one-root lifecycle
transitions take the per-root lock.

The current source therefore contradicts both itself and the recorded ruling.
Closure requires the promised honest annotation/contract reconciliation; a
contention-driven lock redesign remains deferred until the owner's measured
four-worker condition is met.
