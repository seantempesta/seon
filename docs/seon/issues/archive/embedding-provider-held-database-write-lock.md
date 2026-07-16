---
type: issue
status: completed
tags: [issue, database, flow]
---

# Embedding provider held the database write lock

## Failure

`seon.db.writer/transact-once!` called the blocking Gemini transaction transform
inside `(locking connection)`. One provider request or retry therefore prevented
an unrelated write to the same database from reaching Datahike, despite the
embedding namespace claiming provider work happened outside transaction
ownership.

## Resolution

The writer now commits the primary transaction and durable request receipt
without invoking the provider. It derives numeric entity IDs from Datahike's
committed transaction report and submits them to bounded per-database background
execution only after the primary result is fixed. Queue saturation, provider
failure, retry, or delayed backfill cannot change the primary response.

After provider work, the background owner resolves the same exact Datahike
connection generation and recomposes the complete current entity. A changed
document discards the stale vector; an unchanged document commits its source
hash and vector in a later ordinary transaction. Datahike's existing writer
admission supplies the release fence, so the fix adds no global registry lock.

Embedding batches now use one process-wide bounded executor rather than a new
six-thread pool per call. Large calls submit bounded windows so they cannot fill
and reject their own shared queue. Normal server shutdown drains accepted
requests, stops the embedding executor, and closes the shared Gemini client.

## Proof

The deterministic regression blocks the background provider, proves the primary
response has already returned, commits an unrelated transaction to the same
database, then releases the provider. The focused request-receipt gate passes 5
tests and 31 assertions. The focused executor, embedding, receipt, and writer
integration gates pass 30 tests and 198 assertions.
