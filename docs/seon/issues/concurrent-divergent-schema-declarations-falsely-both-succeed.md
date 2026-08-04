---
type: issue
status: open
severity: blocker
tags: [issue, schema, sci, concurrency, durability]
---

# Refuse the losing concurrent divergent schema declaration

## Problem

Two runs can concurrently declare different forms for the same new schema key,
receive two success-shaped receipts, and persist only one form. The losing run
is told its declaration succeeded even though it never became a database fact.

## Evidence

Runs `streams-schema2-run-a` and `streams-schema2-run-b` concurrently registered
`:streams.collision/divergent` as `:string` and `:int`. Both ordinal-1 receipts
returned the keyword and settled at transactions `536871181` and `536871182`.
Schema history contained only `[":string" 536871181 true]`; the current row was
`:string`. No refusal fact explained why B's `:int` declaration was absent.

The identical-definition control behaved idempotently: both receipts settled,
and history contained one `[:string {:min 1}]` assertion. Exact queries are in
[concurrency streams crossed](../../prds/sci-execution-runtime/research/concurrency-streams-crossed-2026-08-04.md).

## Owner

The isolated registration delta plus terminal program-row admission boundary.

## Acceptance

- Concurrent identical declarations are idempotent and produce one schema row.
- Concurrent divergent declarations produce one admitted winner and one flat,
  attributable refusal.
- No successful receipt can name a schema form absent from history/current
  facts.
- A deterministic two-run regression covers both identical and divergent
  declarations.
