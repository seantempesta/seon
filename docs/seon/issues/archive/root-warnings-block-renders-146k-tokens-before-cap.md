---
type: issue
status: superseded
tags: [agent, database, issue]
severity: friction
---

# Root warnings block renders 146k tokens before its cap clips it

## Evidence

Live default cluster 2026-07-21 (~05:00Z): root's rendered context shows
the warnings block clipped as "⟨block clipped to 1024 of 146161 tokens⟩".
The visible pre-clip content is a raw dump of function-catalog rows
(`["seon.derive/schedule-breaker-tripped?" seon.derive "[:=> …" true
false ""]` …) — a `seon.warn` check is emitting entire acquisition
tables (`::function-rows` 991 rows, `::schema-forms`/`::schema-provenance`
2045 each at that database value) into its affected/explain output
instead of a bounded affected list.

## Impact

The 1024 token-cap contains the prompt damage, but every root render
derives and serializes ~146k tokens of warning text to throw it away,
and whatever check is dumping tables produces garbage even in its
retained first 1024 tokens (the visible clip is schema noise, not an
actionable warning).

## Expected owner

`seon.warn` — the specific check whose `:seon.warn/affected` /
`:seon.warn/explain` scales with the function/schema catalog (candidates:
the schema-mismatch / fn-schema checks over `::function-rows`). Checks
must render bounded affected lists (name + one line), never the table.

## Acceptance

Root's warnings block renders under its cap without clipping on a
database with ~1000 function rows, and its first lines are actionable
warnings, not catalog dumps.

## Resolution

Superseded by the fresh-tree split in f25e34594: the cited State A owner is quarry or deleted, and the current B2/N3/N4 ledgers do not carry this defect forward.
