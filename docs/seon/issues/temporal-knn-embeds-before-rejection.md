---
type: issue
status: open
severity: friction
tags: [issue, database, flow]
---

# Reject temporal KNN before embedding

## Problem

An exact earlier transaction cannot yet use Datahike's containing-commit
secondary index, but the current provider-to-KNN continuation computes the
query embedding before the KNN phase resolves the coordinate and rejects it.
The response is correct while an avoidable remote provider call has already
consumed latency and capacity.

## Evidence

- `seon.db.writer/execute-knn-provider!` runs before
  `seon.db.writer/execute-knn!`; only the latter owns `pinned-database`.
- The temporal writer integration proof observes two embedding calls but only
  one KNN call for one supported full-commit request plus one rejected earlier
  cut.
- Retaining a materialized database value across a slow provider call would
  trade the wasted call for excess native-resource lifetime, so moving the
  current resolver naively is not acceptable.

## Owner

The existing provider-to-KNN executor continuation and Datahike's attached
commit resolver. Validation should use the stored commit's transaction bound
without restoring secondary indexes or retaining a database value across the
provider call.

## Acceptance

- An earlier exact `t` returns the existing protocol error without invoking the
  embedding provider or KNN.
- A full historical commit invokes the provider and preserves native secondary
  index acceleration.
- Missing, sibling, and force-discarded commits still return stale-coordinate
  errors without provider work.
- Validation does not block the selector/control path, add a second coordinate,
  or retain a materialized value while awaiting the provider.
