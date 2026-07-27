---
type: issue
status: superseded
tags: [database, agent, issue]
severity: friction
---

# Transact output schema crashed child on ordinary error

## Overnight triage — 2026-07-23

**FOLD-INTO-UNIT — C1 codec.** The output union exists; the missing
instrumented refusal-to-success same-runtime proof is C1 boundary totality.

## Evidence

A live agent called `complete`, which transacts the run's terminal facts. The
writer returned an ordinary `:seon.error/message` map. `seon.db/transact!`
already documents and implements errors as values, but its Malli output named
only a successful transaction report. Instrumentation rejected the error map
as invalid output, recorded a core fault, and exited the execution child.

## Expected owner

The public `:seon.db/transact-response` schema is the union of Datahike's
transaction report data and Seon's ordinary database error data. Callers inspect
the returned value; instrumentation never turns a recoverable writer response
into a process crash.

## Acceptance

- Focused database facade tests cover a failed transaction under
  instrumentation and receive the original error map.
- A real agent can call `complete` without its execution child exiting.

## Grounded remaining boundary

Commit `5e3edf01` already made the public output a fixed transaction-report or
database-error union, but its test validates only a literal map. It does not
exercise the instrumented public function or prove same-child survival.
[[../../prds/source-cleanup/research/transact-response-union-boundary-2026-07-20]]
(`8862b604`) folds the missing acceptance into the Stage-5 database-result
owner: close and normalize the shared database error, retain the bare disjoint
transaction union, and add one instrumented writer-refusal followed by a
corrected transaction. Do not add `:seon.result/ok?` or restore the historical
transaction envelope. Closure still requires the frozen same-child failure
then `complete` proof specified by that report.

## Resolution

Superseded by the fresh-tree split in f25e34594: the cited State A owner is quarry or deleted, and the current B2/N3/N4 ledgers do not carry this defect forward.
