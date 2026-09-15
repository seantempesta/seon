---
type: issue
status: open
severity: friction
tags: [issue, render, web, class/n11, wave/transcript-deletion]
---

# Delete the separate agent transcript assembler

## Problem

The provider prompt now comes from `seon.render.walk/history`, but the agent
entity's HTML projection still points at the older
`seon.render.transcript/render-session-html` mechanism. That mechanism performs
its own candidate queries, ordering, synthetic-form construction, fitting, and
session assembly. The agent and the human therefore do not consume two fits of
one retained render artifact.

The old assembler also preserves the exact class ruling 28 excludes: it creates
display entries for comment-only form rows that have no receipt and for
undisposed runs by inventing a `db/pull` form that was never executed.

## Evidence

`resources/seon/schemas/seon.cluster.agent.edn:1-9` gives the agent's AI
projection to `seon.cluster.agent/render-situation-ai` but its HTML projection
to `seon.render.transcript/render-session-html`.

The separate candidate mechanism is current production code:

- `src/seon/render/transcript.clj:127-160` selects comment-only form rows with
  no matching eval receipt and counts them as history;
- `src/seon/render/transcript.clj:203-259` merges those rows with messages,
  receipts, and undisposed runs through a hand-maintained entry-kind roster;
- `src/seon/render/transcript.clj:436-443` creates an undisposed-run entry;
- `src/seon/render/transcript.clj:637-669` invents a `system=> (db/pull ...)`
  display and selects rendering by entry kind;
- `src/seon/render/transcript.clj:879-939` independently synthesizes message
  and run forms, serializes entry bytes, and applies its own token fit; and
- `src/seon/render/transcript.clj:971-979` exposes that mechanism through the
  schema-declared HTML session render.

The remaining non-test direct session-assembler caller is
`src/seon/eval/drive.clj:287-298`, for a drive report.
`src/seon/render/web.clj:284` uses only the independent reasoning-disclosure
helper rather than the assembler. Provider context instead reaches
`src/seon/render/web.clj:997-1044`, which calls `seon.render.walk/history`.
Thus W2 removed the old assembler from the provider path but did not delete the
mechanism or its active agent-HTML route.

## Owner

The agent schema's three render projections and the render proc's retained
history are the one owner. `seon.render.transcript` may retain narrowly owned
value helpers only when the walk's declared render selection calls them; it
must not own a second session derivation.

## Acceptance

- Agent AI, HTML, root preview, and debug surfaces consume the same retained
  history entries, differing only by render profile and projection.
- No production function selects transcript candidates or orders entry kinds
  outside the schema-derived neighborhood walk.
- No displayed form exists without either a declared `:seon.render/form`
  projection over a reached fact or the exact source and terminal receipt of an
  executed form. Comment-only/no-receipt and invented undisposed-run entries
  are unrepresentable.
- The old transcript assembler and its direct drive/session callers are
  deleted, with one recurring same-entry-identity proof across AI and HTML.

## Re-verified at HEAD (2026-09-15)

OPEN, UNVERIFIABLE. Audited HEAD `7e35df213` no longer routes the agent schema to `render-session-html`: `resources/seon/schemas/seon.agent.edn:13-16` selects `render-identity-ai/html`. However `src/seon/render/transcript.clj:141`, `:669`, `:811`, and `:909` retain candidate/history and session helpers. `history-entries` now filters actual evaluations and uses `seon.repl/text` (`:831-848`), so the historical invented-form claim is not a current reproduction. Needed: an armed cross-surface history identity test and current caller-reach proof before declaring all separate assembly dead. No such test was run in this triage. Downgraded to friction because the specified agent-HTML route is gone and no present live-run blocker was reproduced.

surface: render-debug-page

Owner correction: UNVERIFIABLE-WITHOUT-GATE (seon.render.transcript-test, seon.render.history-test). No new JVM may be launched; the required canonical regression remains pending.
