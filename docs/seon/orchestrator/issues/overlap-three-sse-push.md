---
type: issue
status: superseded
severity: architectural
milestone: M4
tags: [issue, web, architecture]
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

## Solution Direction

[[prds/unified-namespace-flow/design]] Phase 6b unifies all three into one flow-based path:

- Namespace flow step emits `:render` events to an out-port channel
- `async/mult` fans render events to per-connection channel taps
- Browser connect = `async/tap` with `sliding-buffer 1` (latest state, Datastar patches DOM)
- Browser disconnect = `async/untap` (channel closed)
- Content-hash deduplication preserved in the per-connection rendering loop
- No atom watches for SSE — flow is the single push path

Buffer choice per connection type: `sliding-buffer 1` for browsers (latest state wins), `blocking-buffer 64` for developer REPLs (want every result). See [[prds/unified-namespace-flow/research/ctx-flow-sync]].

## Related

- [[components/web-layer]]
- [[components/context]]
- [[components/flow-topology]]
- [[prds/unified-namespace-flow/design]]

## Superseded (2026-06-28 audit)

ctx.clj/web/sse are JVM-paused; the active pod is a single web/datastar.cljs morph.
