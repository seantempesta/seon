---
type: issue
status: resolved
severity: blocker
tags: [issue, database, flow]
---

# Return Datahike transaction errors through the writer protocol

## Problem

`seon.db.writer/transaction-failure` rethrew ordinary Datahike transaction
validation failures from the asynchronous mutation callback. Datahike rolled
the transaction back, but the callback never delivered a response to the
request completion channel, so the caller waited indefinitely and the writer
appeared wedged.

## Evidence

A transaction that introduced an integer attribute and asserted a string for
it emitted Datahike's schema error from the async dispatch thread and left the
focused writer test waiting. The same malformed transaction now returns a
failed protocol response, advances no database coordinate, installs neither
the schema nor domain fact, and a later request succeeds.

## Owner

`seon.db.writer/transaction-failure` is the one boundary that converts
transaction exceptions into writer protocol data.

## Acceptance

- An ordinary Datahike validation failure returns a failed protocol response.
- The rejected transaction advances no coordinate and leaves no partial facts.
- The writer serves a subsequent request without restart.

## Resolution

Commit `fed32bb8` routes unclassified transaction exceptions and unrelated
generated-ID exceptions through the existing request failure response. The
schema/writer/generated-ID gate passes 36 tests and 321 assertions, including
atomic rollback and a successful later request.
