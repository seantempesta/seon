---
type: issue
status: open
severity: blocker
tags: [issue, flow, cljs]
---

# Changed-test hooks can queue stale runs behind an active owner

## Problem

Concurrent edit hooks can wait invisibly behind the current changed-test owner
instead of coalescing their paths into the current immutable selection or
failing promptly. Once the active run exits, an old hook can acquire ownership
and execute a second full gate against source that newer edits have already
superseded. This wastes the one canonical CLJS artifact boundary and reports a
result whose requested edit coordinate is stale.

## Evidence

On 2026-07-15, changed-test process `43142` and its `bin/test-cljs` child
`43305` owned a full pod gate. Later edit-hook owners `44891` and `45887` were
reparented to PID 1 and remained queued for roughly two minutes without a child
or visible progress. They were terminated while idle to prevent them from
starting stale sequential full runs after the active owner released its lock.

This is distinct from
[[changed-test-interruption-orphans-test-runner]]: those queued owners had not
started a child pipeline, so descendant cleanup could not make their admission
or freshness semantics correct.

## Owner

The one `seon.dev.changed-test` command-admission boundary and direct edit hook.
Do not add another test queue or runner. A waiting request must either merge
its paths into the one current immutable run coordinate or return a prompt,
explicit busy result that lets the next edit request carry current source.

## Acceptance

- Start one deliberately blocked changed-test run, then trigger two later edit
  hooks with distinct paths; neither later owner waits invisibly.
- Each later request either coalesces into the current immutable selection
  before execution or exits promptly with an explicit busy result.
- Releasing the first run cannot launch a queued command whose source/request
  coordinate predates a newer edit.
- The retained report identifies the admitted request coordinate and whether
  it executed, coalesced, or failed busy.
- Default and downstream artifact flavors use the same admission mechanism.
