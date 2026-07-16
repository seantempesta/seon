---
type: issue
status: resolved
severity: blocker
tags: [issue, database]
---

# Embedding reused the transaction executor job ID

## Problem

After a committed transaction, background embedding admission reused the
public transaction request ID as its executor job ID. While the mutation job
was still running, executor duplicate admission joined that mutation and then
discarded the joined result, so the embedding update could be silently skipped.

## Resolution

Embedding is one derived internal job and now uses the existing composite job
identity `[request-id :embedding]`. The public request ID remains unchanged for
correlation and receipts.

## Proof

The request-receipt regression now runs transactions through the mutation
dispatcher while the provider is blocked. The primary commit and independent
writes return, and the distinct embedding job is observed. The focused gate
passes 7 tests/45 assertions.
