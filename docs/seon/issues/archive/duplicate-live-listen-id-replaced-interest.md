---
type: issue
status: closed
severity: high
tags: [issue, database, flow]
---

# Reject a duplicate live database interest ID

## Resolution

Installing an interest now checks the physical connection's existing live
interest map under the interest lock before resolving a database value, opening
a committed-report source, or changing the scope, source, and attribute
indexes. A duplicate returns the existing request-conflict protocol error and
leaves the original interest as the sole owner.

Focused proof rejects a second listen with the same request ID and a different
Datom pattern, compares all retained interest indexes before and after, delivers
the next matching event to the original interest, then proves unlisten and
physical disconnect retain no interest or source state.

## Original problem

An acknowledged listen leaves the general active-request map because its reply
is complete, while its request ID correctly remains live in the physical
connection's interest map. A second listen could therefore pass ordinary
request admission and replace that map entry without removing the original
reverse-index owner, retaining an undeliverable interest.
