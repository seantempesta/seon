---
title: "Card CO-2: A request answered from its record writes nothing"
type: reference
status: active
created: 2026-10-08
---

# CO-2 A request answered from its record writes nothing

Pattern P3. Lane: Sonnet. After F11 (an unchanged red is answered from its record).

## What exists
`seon.test/run` selects, then `selection-admission` and `admit-run` reserve the members in one transaction, then the
world is built lazily by the first admitted batch (`request-handle!`). When every member's recorded result still
holds (green today; red too after F11), nothing executes, yet the request still pays `selection-admission`
(10-16 ms per `:ping`) and the admission transaction ("an identical replay emits no evidence datoms", but it is a
writer round trip).

## Change
After selection, partition members into answered-from-record and to-execute (the same reuse rule `run` applies
today, read before admission on the request's held head). When to-execute is empty, return the run result from the
records with no admission and no world. The writer's fresh decision is kept for every member that executes.

## Functions
- CHANGE `seon.test/run` (and the private `run-request`): the empty-execution short path; contract unchanged.

## Proof
`seon.test-test/a-request-answered-from-its-record-writes-nothing` (new): run one green test twice on an unchanged
program; the second run's `(calls-of m 'seon.test/admit-run)` is 0, the branch's basis-t is unchanged, and the
result names the test as reused.

## Cost
Expected: selection on held values plus one record read per member, about 1 ms. Parent: 10-16 ms
`selection-admission` plus one transaction per eval request.

## Commit
`bin/lane commit <lane> -m "CO-2: a request answered from its record writes nothing" --test seon.test-test/a-request-answered-from-its-record-writes-nothing --test seon.test.admission-digest-test src/seon/test.clj test/seon/test_test.clj`

## Dependencies
CO-0, F11, CO-1 (same file, in order).
