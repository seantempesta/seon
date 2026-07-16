---
type: issue
status: closed
severity: high
tags: [issue, database, flow]
---

# Accept Datahike's complete attribute domain in index cursors

## Resolution

The protocol cursor now accepts any keyword for `:seon.db/a`, matching
Datahike, page datoms, query dependency evidence, and datom interest patterns.
The Transit regression uses an unqualified attribute together with a byte
value, proving the complete request survives framing and remains valid.

## Original problem

The page datom schema correctly accepted any Datahike keyword attribute, but
the continuation cursor still required a qualified keyword. An unqualified
attribute could therefore be returned as a valid page datom and immediately
make the containing response invalid at Seon's protocol boundary.
