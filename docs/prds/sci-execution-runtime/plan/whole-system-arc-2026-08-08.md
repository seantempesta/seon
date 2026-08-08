---
type: prd
status: active
tags: [prd, runtime, agent, testing]
---

# The whole-system arc — "the system works, UI included" (owner-scoped 2026-08-08)

The graduation demo the owner will actually look at. Scope ruled
2026-08-08 night: the FULL SLATE is the goal, sequenced turns+pages
first, with the canvas as a centerpiece (all three options selected —
read as staging, not alternatives).

## The arc

One cluster, three agents, three namespaces, one human at the web UI.

**Stage 1 — turns + pages (the concurrency proof).**

1. Fresh boot from published `current-src`; root claims the human's
   opening message and delegates work to three agents, each owning a
   distinct namespace (real work items, not toys — per the standing
   rule: multi-step plans committed as facts, queried back later).
2. The three agents run CONCURRENT live turns — the first
   N-agents-live-in-one-cluster proof. Messages flow between them
   (delegation, a question, an answer) and appear in transcripts.
3. Each agent defines at least one CONTRACTED function that a later
   form (or another agent) calls — gated on the component-ref landing.
4. While turns run: the namespace pages update live over SSE (no
   reload), the debug pane shows the exact prompt bytes of an
   in-flight run, `/data` answers, and the reconnect-repaints property
   holds (close a tab mid-turn, reopen, current state).

**Stage 2 — canvas centerpiece.**

5. One agent builds an interactive `my.canvas` surface (a small live
   dashboard over its own namespace's facts — buttons/inputs that
   round-trip through messages). The human interacts with it; the
   agent responds in a subsequent turn.

**Stage 3 — full slate closure.**

6. A restart mid-arc: stop and reopen the cluster; every run recovers
   per the crash model (nothing re-executes; interrupted receipts
   honest); the agents' plans survive and continue — the
   planning+memory proof folded in.
7. The token sentinel holds throughout: every prompt within its
   expected band (the 24k opening prompt is the current known ceiling
   — improvement tracked, not gated here); no context collapse; no
   unbounded completions.

## Acceptance (what the owner looks at)

- The web UI, live, during stage 1-2: pages moving, debug honest,
  canvas interactive.
- The database, after: every claim above answerable by query (runs,
  receipts, messages, defined functions, canvas facts) — reproduce the
  arc's story from facts alone.
- The drive report: before/after against this spec, every deviation
  named.

## Preconditions (all in flight or landed)

- Component-ref ruling landed (agents define contracted functions) —
  lane running.
- Background bounds landed — lane running.
- Escalation single-owner landed — lane running.
- The arc itself runs as a driver+observer pair (the proven pattern),
  DeepSeek-flash, token sentinels armed.

## Explicitly out

The branch-work model (ingredients ledger only, no design); docs
indexing (owner-tabled 2026-08-08); rename waves (Phase 4 window);
messaging-wave implementation beyond what message flow already
supports.
