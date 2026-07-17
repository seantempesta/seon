---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, database, flow]
---

# Prove database workflow answers from retained query evidence

## Problem

The database-workflow scorer tried to prove how an answer was computed by
capturing every database operation performed during an eval. That created a
second execution-evidence system beside the database's ordinary eval and turn
facts, retained duplicate query results, and coupled eval recording to a
process-local observer and blob publication.

## Evidence

The accepted source audit in
[[../../prds/database-authority-mesh/research/eval-native-result-database-value-cut-2026-07-16]]
found that the operation-capture path obscured the native `seon.db` contract:
successful writes already return transaction reports, failed database calls
already return direct error values, and eval result data already records what
the agent observed. The additional observer, canonical serializer, blob
attribute, projection validator, and scorer vocabulary were parallel
infrastructure rather than database authority.

## Owner

`seon.eval` owns one eval receipt pipeline. It records ordinary eval data and
returns native transaction reports on success or direct database errors on
failure. `seon.db` remains the sole application database API and no eval-local
database-operation recorder exists.

## Acceptance

- Production eval source contains no operation capture, operation blob, or
  compact success/error envelope.
- An eval receipt accepts one ordinary `:seon.db/db` value and an optional
  `:seon.db/expected-db` write fence.
- Focused tests prove native allocation and transaction reports, direct
  database errors, and the runtime database-value handoff.
- The composition door derives bounded eval result evidence from ordinary
  database facts and does not reconstruct a second database execution trace.

## Resolution

The operation-capture and persistence path was deleted in favor of the native
database result contract. The obsolete boot schema attribute and its focused
capture assertion were deleted, and the architecture now names ordinary eval
result data as the one evidence source. The source change and focused proof are
recorded by the database-authority-mesh implementation commit that archives
this issue.
