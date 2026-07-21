---
type: research
status: draft
tags: [research, agent, web]
---
# Observatory pattern survey for the CLJS inspector

## Context

There's prior JVM-side "agent observatory" work tracked in the codebase. Goal of this survey: identify patterns the new CLJS inspector (`src/seon/web/inspector.cljs`, shipped 2026-05-25) should adopt, defer, or improve on. **Patterns only — no JVM code lifted.** The CLJS inspector is built fresh against the v0 substrate (`inspect/ctx-preview`, `assemble-ai-context`, symbol-on-entity rendering).

## What I found

JVM observatory code is in transitional state:

- `docs/seon/vision/capabilities/agent-observatory.md` marks the capability `status: complete` (auto-derived from prior milestone work).
- `src/seon/web/routes.clj` and `src/seon/web/server.clj` both contain "Agent observatory module pending restoration against the new schema" comments — the JVM-side wiring is currently dormant on this branch.
- `docs/seon/issues/archive/observatory-sse-streaming.md` flags a known gap: the JVM observatory polls at 1s intervals instead of true SSE-append streaming.
- `docs/archive/agent-observatory/streaming-research.md` (per the issue ref) holds a streaming-design write-up.
- `data/namespaces/seon.observatory/` is a datahike store directory only; no source code there.

No live observatory source to read on this branch. Survey below is based on capability docs + issue analysis + comparison with the just-shipped CLJS inspector.

## Patterns surveyed (5)

### 1. SSE append streaming with batch windows

**Pattern (from streaming-research):** `mode append` instead of full re-render; `sliding-buffer(50)` for backpressure; 50–100ms batch windows; message IDs for reconnection replay.

**CLJS inspector today:** 100ms trailing coalesce per agent + Datastar `morph` (replace-by-id) of three fragments (header / AI pane / HTML pane). No append mode; we re-morph the entire pane on every push.

**Port or defer?** **Defer.** Replace-morph cost is fine for the current entity counts (~50–100 per agent). Append-mode + reconnect-replay only matter when conversations hit thousands of entities or when reconnects become common. The token-budget truncation already caps the rendered set. Revisit if perf or reconnect UX bites.

### 2. Auto-scroll behavior (pin-to-bottom unless user scrolled up)

**Pattern (inferred from JVM observatory):** Conversation log auto-scrolls to bottom on new entries; if the user has scrolled up to read earlier content, the auto-scroll pauses and a "↓ new content" pill appears in the corner.

**CLJS inspector today:** No auto-scroll. Both panes scroll independently; the user sees what was visible when the page first loaded unless they manually scroll. This is a real UX gap — sending a chat message and not seeing the response appear in view is jarring.

**Port or defer?** **Port soon.** Small: one `MutationObserver` clause that detects new content arriving and, IF `el.scrollHeight - el.scrollTop - el.clientHeight < 50px`, calls `el.scrollTop = el.scrollHeight`. The "↓ new" pill is optional polish. ~20 LOC.

### 3. Conversation grouping (cluster turn-related entities visually)

**Pattern (from capability doc):** "Full conversation rendering with tool calls and results." Implies turn-level grouping where the user message, the agent's narration, the forms it evaluated, and their results are visually clustered as one unit.

**CLJS inspector today:** Flat oldest-first list of renderable entities. The agent's narration is per-eval (one card per form). User can see the relationships but they're not visually grouped.

**Port or defer?** **Defer pending data model.** The `:seon.eval/from-message` ref (open from `loop-design.md` §turn-boundary) would give us grouping for free — group evals by their originating `:seon.message`. Once that ref exists at the write site, the inspector groups by `(d/q ... :seon.eval/from-message ...)`. Implementing visual grouping before the ref exists would require ad-hoc heuristics (cluster by tx-time gap or by `:seon.eval/turn-id` if it exists). Not worth the duplicate work.

### 4. Selection / filtering (click entity to see details)

**Pattern (inferred):** Click an agent in `/agents` to drill into per-agent view. Click an entity in the conversation list to see its full datom map.

**CLJS inspector today:** `/agents` → `/agent/<id>` drill-down works. No per-entity expand. The `<details>` on error envelopes is the only expansion; eval source is always shown in full (or truncated to 800 chars).

**Port or defer?** **Adopt lightly.** Add a `<details>` wrapper on each non-error entity card with the full pulled entity map (compact `pr-str`) so anyone debugging can see what's actually there. ~15 LOC. Don't add a separate detail-view page; keep everything in the single pane.

### 5. Live state indicators (state dots, pulse animations)

**Pattern (from capability doc):** "SSE-driven live updates as agents work" — implies visual indicators of active work (e.g. agent state pulsing when running, fading when idle).

**CLJS inspector today:** Header shows `● running` (amber) or `● idle` (muted), but no pulse animation. The dot color changes statically.

**Port or defer?** **Adopt — trivial.** Add `animate-pulse` Tailwind class to the dot when state is `:running`. ~1 line edit to `header-fragment` in `inspector.cljs`. Optional: subtle border-glow on the active pane when new content has landed in the last 500ms.

## Recommendations summary

| Pattern | Verdict | Effort |
|---|---|---|
| SSE append streaming | Defer until perf bites | — |
| Auto-scroll pin-to-bottom | **Port soon** | ~20 LOC |
| Conversation grouping | Defer pending `:seon.eval/from-message` | — |
| Per-entity `<details>` expansion | **Adopt lightly** | ~15 LOC |
| Pulse animation on running state | **Adopt — trivial** | ~1 line |

Total port-now cost: ~36 LOC, all in `inspector.cljs`. No new files, no schema changes.

## What the CLJS inspector ALREADY does better than the JVM observatory

- **Symbol-on-entity rendering** (`:seon.render/ai`/`:html`) makes both the LLM ctx and the human view literally the same query. The JVM observatory had to maintain a parallel "conversation rendering" projection.
- **Tx-meta-scoped `d/filter`** for per-agent visibility is one predicate, no schema fanout. The JVM observatory presumably scoped by querying per-agent entities directly.
- **No polling.** `d/listen!` push with 100ms coalesce vs the JVM's 1s poll cycle.
- **Phosphor Terminal theme** out of the gate — no design refresh needed later.

## Out of scope for this survey

- Multi-agent side-by-side comparison view (not in capability doc; possibly v2)
- Replay / time-travel UI over the bitemporal log (genuinely complex; defer)
- Mobile-friendly layouts (the existing two-pane grid breaks below ~900px wide)
