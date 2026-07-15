---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, flow]
---

# Reject partial database workflows in the milestone scorer

## Problem

The generated task required two schemas, five records in one transaction, a
later strict-threshold query, and the same answer in human and completion
reports. The scorer passed one matching schema registration, any transaction
and later query mentioning the measure, and one reply token equal to the answer.

## Owner

`seon_inspect.generators` owns deterministic host-only oracle data.
`seon_inspect.milestone.check_store_recall` owns the pure scorer over retained
eval evidence. The task prompt and sample membership remain frozen.

## Acceptance

Require both declared schema semantics before storage, every named record and
value in one successful transaction, a later query containing the measure,
strict predicate, and exact threshold, plus successful human and completion
calls containing the answer. Each partial shape has a focused false-positive
regression.

## Resolution

The generator now freezes records and threshold beside the existing attributes
and answer. The scorer enforces the complete observable workflow, and the dev
artifact/lock hashes were deliberately refreshed without changing prompt bytes
or sample id. The focused freeze, generator, scorer, catalog, and native-log
gate passes 151 tests.
