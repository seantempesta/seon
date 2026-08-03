---
type: issue
status: open
tags: [issue, database, error]
---

# `seon.db/q` silently returns nil when its db argument is an error value

## Evidence

Curriculum research probe, 2026-08-03
([bootstrap-curriculum-2026-08-03.md](../../prds/sci-execution-runtime/research/bootstrap-curriculum-2026-08-03.md)):
a `seon.db/q` call whose db argument was itself a flat `:seon.error` value
(from a failed upstream read) returned `nil` with no explanation — the
probe's entire first census came back as nils before the cause was found.

## Why this is wrong

Errors-as-values only works when a consumer handed an error value REFUSES
loudly. Silent nil on bad input is the exact tool smell the standing
"never ok on bad input" rule names: the failure is real, the signal is
destroyed, and every downstream conclusion is quietly poisoned. Ruling #41
places `seon.db` as the one database namespace with error-value semantics —
propagating or refusing an error-valued argument is part of that contract.

## Expected behavior

`seon.db/q` (and the sibling core reads — `pull`, `entity`, `datoms` —
audit the family) given an error value where a database value belongs
returns a flat error value naming the misuse and carrying the upstream
error, never nil. Same for a nil db where a value is required.

## Acceptance

A regression per family member: error-valued and nil db arguments return a
flat error naming the argument; the upstream error rides in the data.
