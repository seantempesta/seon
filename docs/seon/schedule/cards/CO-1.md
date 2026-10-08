---
title: "Card CO-1: An unchanged branch's change set is a lookup"
type: reference
status: active
created: 2026-10-08
---

# CO-1 An unchanged branch's change set is a lookup

Pattern P1. Ledger W-25 (part). Lane: Sonnet. Parent measured 2026-10-08 on default PID 78227, archive `ade6e3059`
(pre-rip-context; this owner is untouched by it).

## What exists
`seon.turn/run-reaching` calls `seon.test/changes` on every eval request. `changes` compares every definition digest
of the published base commit's world with the candidate (`digest-differences`) and re-derives the same answer
while neither changed. `clean-request-handle` then calls `digest-differences` a second time for the overlay.
Measured on agent `cost-cards-probe` (18 changed identities): `changes` 16-24 ms, 48 MB (thread), 367 armed calls,
6 reads per call; `digest-differences` 2 calls, 31-34 ms per `:ping`.

## Change
Key `changes`'s value in `seon.cache` (new kind `::changes`) by `[base-commit-id (seon.test.runner/program-revisions-digest candidate)]`.
`program-revisions-digest` measured 0.11 ms, 0 reads. A nil digest (no revision authority) derives as today; the
base commit id is immutable. Hand the derived differences to `clean-request-handle` through the request
(`:seon.test/changed` already travels there) so `digest-differences` runs at most once per request.

## Functions
- CHANGE `seon.test/changes`: lookup before `digest-differences`; its contract unchanged.
- CHANGE `seon.test/clean-request-handle` (private): reads the request's held differences when present.

## Proof
`seon.test-test/an-unchanged-branch-derives-its-changes-once` (new): two `changes` calls on one unchanged branch;
with CO-0, `(calls-of m 'seon.test/digest-differences)` is 1 for the first and 0 for the second; after one `defn`
on the branch it is 1 again and the value names the new identity (the key really follows the program).

## Cost
Expected from the work: a hit is one revision digest over the program attributes (O(attributes), ~0.1 ms); a miss
is today's comparison once. Parent: 47-58 ms per request on a changed branch. 10× over 0.1 ms on a hit is a defect.

## Commit
`bin/lane commit <lane> -m "CO-1: an unchanged branch's change set is a lookup" --test seon.test-test/an-unchanged-branch-derives-its-changes-once --test seon.test-reaching-test/reach-digests-follow-only-changed-closures src/seon/test.clj test/seon/test_test.clj`

## Dependencies
CO-0. `src/seon/test.clj` order: after fix-branch-schema (F12) releases it.
