---
type: issue
status: open
severity: architectural
---
# Overlap: Three SSE Push Mechanisms

## Problem
Three systems push data to browsers through different paths with different semantics:

1. **ctx watch-based push** (`ctx.clj:285-355`) -- Bespoke SSE formatting, own client tracking, fires on atom watch. Tightly coupled to ctx atoms.
2. **render-handler poll** (`web/sse.clj`) -- Content-hash deduplication. Used by the web layer.
3. **flow-based SSE** (`web/sse/flow.clj`) -- Aggregates code change events. Flow-native but only handles one event type.

Different push semantics mean inconsistent update behavior, duplicated client tracking, and no unified way to push arbitrary data to the browser.

## Where
- `src/seon/ctx.clj:285-355` — atom watch-based push
- `src/seon/web/sse.clj` — content-hash poll-based push
- `src/seon/web/sse/flow.clj` — flow-based push

## Acceptance Criteria
- Single SSE push mechanism (preferably flow-based)
- All data pushed to browsers goes through one path
- Content-hash deduplication preserved
- Client tracking unified
- Tests pass

## Related
- [[components/web-layer]]
- [[components/context]]
- [[components/flow-topology]]
