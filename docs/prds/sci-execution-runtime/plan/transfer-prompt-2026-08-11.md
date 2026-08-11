---
type: prd
status: active
tags: [prd, runtime, agent, render]
---

# Transfer prompt — 2026-08-11 (the REPL-transcript design session)

You are inheriting Seon after a two-day arc that achieved the program's
founding milestone and sealed a major design. Your job is a DESIGN
SESSION WITH THE OWNER first, implementation second. Read this whole
document, then the named authorities, before proposing anything. The
owner iterates in chat; do not launch lanes until the design questions
below are settled (standing rule: iterate with the owner to a simple
design, THEN launch once).

## Standing stance (unchanged)

Verify every load-bearing claim live before building on it. Distinguish
PROVEN-LIVE / CLAIMED / UNKNOWN. Read whole documents, never grep a
spec. Rotate ideas 90° before implementing. One mechanism, no hand
lists, no regex without permission, errors as values, everything
queryable, loud failures, fast by default (a 3s+ surface or a
redundant-query storm is a BUG to investigate on sight), long waits are
bugs, no per-value printer customization, grounded names only.

## What is PROVEN (2026-08-10/11, all observer- or gate-verified)

- **THE MILESTONE**: a real DeepSeek model authored a contracted
  function end to end (`my.agents.root/token-pressure`), called it,
  queried its own contract back; its authored `:seon.render/ai`
  producer was auto-selected by contract at top level. Independent
  observer confirmed from its own Datalog
  ([driver](../research/model-authoring-drive-2026-08-10.md),
  [observer](../research/model-authoring-observer-2026-08-10.md)).
- **The suite**: 80.5 min / 90 reds → parallel runner (nine
  root-isolated workers, derived split) → **default complete tier
  6:29** (owner ceiling 5–8 min MET), 53 long tests each declared with
  its measured justification, ranked make-faster queue recorded
  ([triage](../research/long-tier-triage-2026-08-11.md),
  [base-reuse](../research/base-reuse-measurement-2026-08-11.md) — note
  its simplicity ledger: two conversions taken, three rejected as
  faster-but-more-complicated; that discipline is standing).
- **The UI**: proven live end to end in a real browser; /data 130 ms;
  namespace pages ~1-3 s cold / ~17 ms warm HTML; print faces legible
  ([ui-truth](../research/ui-truth-2026-08-10.md)).
- **The warm AI walk FAILS the owner's cheapness gate**: 122 ms and 146
  queries/797 pulls at an UNCHANGED basis (all outputs reuse; the
  TRAVERSAL is the waste); the same context is answerable in 4 queries
  / 46 ms ([measurement](../research/warm-walk-measurement-2026-08-10.md)).
  Zero re-render at unchanged basis is a HARD GATE in both projections.

## The sealed design (read these END TO END, in order)

1. [agent-interface-economy-2026-08-10.md](agent-interface-economy-2026-08-10.md)
   — the 13 verbatim-grounded owner rulings.
2. [repl-transcript-context-prd-2026-08-10.md](repl-transcript-context-prd-2026-08-10.md)
   — the line-by-line design: page one ("How this works") is the core;
   worked examples are labeled IN/OUT exchanges with
   stored/injected/target provenance. Round-1 rulings are recorded in
   the file, including: `/` is the system view with root's message bar
   (root sees and can steer the user's view); debug = the AI context
   pane, the main view is the HTML side; demonstrated retrieval
   replaces schema walls; **NESTED-1 ruled 2026-08-11** (nested
   rendering = explicit-or-declared-schema only; the taught habit is
   "declare your shape's schema with its render producer"; the
   acquired-candidates mechanism is dropped).
3. Vocabulary (AGENTS.md table, ruled 2026-08-10): the agent's
   HISTORY of FORM + VALUE entries (never "session units"); bare
   injected `doc` AND `docs` (schema- and test-aware; varargs
   positional + fully-namespaced map arities; never single bare
   keyword args); "the agent's defs" (never "desk"); printed value
   (never "face" as API vocabulary).

## THE OPEN DESIGN QUESTION — bring the owner your A game here

The owner's words (2026-08-11, verbatim intent): "It seems like we
have to hand author the forms and my goal with the distance based walk
was to have the context generate itself. I need a system that will
work everywhere and allows dynamic discovery as the agents work and
define more data and references."

The tension: the PRD's worked examples read as a hand-authored
bootstrap script. The owner's goal is SELF-GENERATING context — the
distance/recency walk decides WHAT enters context, automatically
discovering new data, attributes, and references as agents define
them; nothing is a maintained list of forms.

The reconciliation to develop WITH the owner (his own HUMAN-2 ruling
generalized): **the walk chooses; the REPL shape presents.** For every
entity the walk selects by distance/recency, the history shows an
HONEST, RERUNNABLE READ — a synthesized form like `(docs 'my.fs)` or
`(my.message/read "id")` or a `seon.db/q` — whose printed value is the
walk's derivation for that entity. Only bootstrap's teaching forms are
authored (they execute for real, once, at creation); everything else
is the walk rendering itself AS the session. Dynamic discovery is then
free: a newly declared attribute/ref enters the neighborhood because
it IS the graph; the walk surfaces it as a fresh read the agent could
have typed. Test this frame against the owner's intent FIRST — do not
assume it; he may want less synthesis or a different presentation.

## THE CACHING/ORDERING SEMANTICS the owner wants designed

Owner verbatim intent: "we cache the results until the context changes
and maybe we integrate the context changes into the transcripts
ordering to keep token caches maxed."

Two layers, design both:

1. **Render caching**: every history entry's printed value is retained
   until the facts it derives from change (the retained-calls
   mechanism + the hard unchanged-basis gate; the warm-walk
   measurement proves the traversal must ALSO be bounded — the 4-query
   floor is the target shape).
2. **PREFIX-STABLE ORDERING for provider prompt caches**: REPL
   semantics naturally give this — earlier history entries NEVER
   mutate; when a value the agent saw changes, the change appears as a
   NEW entry appended later (a refreshed read, exactly as a real REPL
   shows a re-run query returning new data). The prompt's byte prefix
   stays stable across turns → provider prompt-cache hits stay maximal
   (DeepSeek `prompt_cache_hit_tokens`; the cache-economics thread,
   and the observed cache hits rising 512→3,584 across drive turns).
   Design the eviction/compaction story with the owner: when the
   history grows past the budget, old entries elide from the FRONT?
   compact into a summary entry? (elision values carry requery
   handles either way). This ordering rule may be the strongest
   argument FOR the transcript design — get the owner's ruling on its
   exact semantics.

## Open owner verdicts still pending (the PRD's option blocks)

HUMAN (message-read injection — recommended HUMAN-2), PROSE, OPENING
(which forms a fresh session shows), MINIMUM (experiment thresholds),
DEBUG-LIVE refinements under the "debug = AI pane" ruling, PREVIEW
content, the message-recipient ref schema addition (walk-reachable
arrivals — owner asked for it conceptually, one confirm outstanding).

## Running/queued work (check `bin/codex-agent status` + git log first)

- `nested-faces-fix` — the declared-face-at-depth defect under
  NESTED-1 (was running at handoff).
- Queued waves, dependency-ready: the remaining ten
  [parallel-only isolation-sensitive tests](../../../seon/issues/parallel-test-stress-exposes-eleven-isolation-sensitive-tests.md)
  (the scheduler-sensitivity pattern from the fixed eleventh is the
  template); the ranked suite make-faster queue (triage report §queue);
  the [interface-economy phases](agent-interface-economy-2026-08-10.md)
  W1 total-floor items not yet landed (anonymous cuts, elision
  never-longer-than-value, the unqualified-keys floor fix landed
  partially — verify); Phase 3 dynamic-var deletions (unchanged).
- The live default cluster may need a refork onto current HEAD before
  drives (many commits since its digest).

## How to work (the owner's standing instructions from this session)

Design in chat with him FIRST; he corrects fast and hates re-litigating
— bring options with recommendations, mark them, and record rulings in
the PRD the same beat. Explain mechanisms plainly (his "WTF" feedback
produced the audit that fixed the PRD — assume jargon is a defect).
Show inputs and outputs explicitly in every example. Use sol
(bin/codex-agent) lanes for implementation, opus agents for
browser/drive work, an independent observer for every live drive, an
independent critic before he reads any major document. Lanes commit
path-limited, report foreign breakage without blocking their own
commits, and every finding becomes an issue. Push at every coherent
checkpoint. UGLY OUTPUT IS A DEFECT. And his summary bar: sober truth,
led by what is broken, never the glow.
