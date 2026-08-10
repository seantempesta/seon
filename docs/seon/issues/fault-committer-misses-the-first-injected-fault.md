---
type: issue
status: open
severity: blocker
tags: [issue, testing, error, flow]
---

# Make the first injected core fault observable at the fault committer

## Problem

An injected proc Throwable can reach the development panic line without the
armed-cluster proof observing the corresponding durable fault/message event.
The fault path therefore has a red boundary before recurrence and overflow can
be assessed.

## Evidence

At clean commit `48eb25ab7`,
`seon.cluster.armed-test/an-escaped-throwable-becomes-a-fact-and-a-message`
printed `SEON CORE FAULT (dev panic): injected core fault` and then errored at
`armed_test.clj:403` because `await-fact` received no required event. This is
not the archived equality race in
[[archive/full-gate-has-a-one-in-three-flake-post-step-2]]: that test reached
the first fact/message and raced a later count; this run did not reach the
message lookup. Evidence: `tmp/full-gate-2026-08-10b.log:986-1038`.

## Owner

Suspected owner: the `fault-facts` lane's fault committer and its notification
transaction. That lane already owns repeated-signature and overflow durability;
this note adds the first-fault delivery boundary without claiming the same
cause.

## Acceptance

- One injected proc Throwable commits one queryable fault fact and the intended
  bounded notification before any recurrence policy applies.
- The proof waits on a durable fact transition and reports whether normalization,
  commit, or notification failed.
- Repetition is event-driven and does not revive the archived count race.
