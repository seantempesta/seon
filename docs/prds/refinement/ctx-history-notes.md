# Ctx History - Implementation Notes

## Datalevin Research Findings

- Datalevin has NO built-in temporal/history support (unlike Datomic's `as-of`, `since`, `history`).
- `d/listen!` exists for tx callbacks but we don't need it -- ctx watches handle change detection.
- LMDB performance is excellent for many small appending transactions. No batching needed for delta writes.
- Store deltas as EDN strings with `:db/unique :db.unique/identity` on a composite ID for upsert.

## Gotchas

1. **Navigation vs recording loop**: When `go-back!` modifies the ctx atom via `swap!`, the history watch will fire. You MUST suppress recording during navigation or you'll create infinite loops / record the undo itself as a new delta. Use an `::navigating?` atom flag in the registry entry.

2. **Reserved keys**: The `::seon.ctx/history` metadata key injected into ctx must work with the existing `::reserved-keys` system. It should be a reserved key set at create time and updated by the history system, but NOT validated against per-key Malli specs (it's internal metadata).

3. **Thread safety**: `go-back!`/`go-forward!` need to atomically read the history sidecar, compute the new state, and swap the ctx atom. Use `swap!` on both atoms carefully. Consider whether the history sidecar should use a single atom with both deltas and cursor to avoid split-brain.

4. **Compaction entity cleanup**: When compacting old deltas into a new base, remember to retract the old delta entities from Datalevin. Use `[:db/retractEntity eid]` or delete by unique ID.

5. **Delta schema for generative testing**: The `::delta` schema uses `[:map-of :keyword :any]` for added/retracted. This generates well but the `:any` values may include non-serializable objects in generative tests. The `filter-serializable` function in `ctx.clj` handles this for persistence, but be aware during testing.
