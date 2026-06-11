---
type: prd
status: active
tags: [prd, agent]
---

# Context v4 — REPL realism (2026-06-11)

Fresh-read spec. All design points below are **DECIDED (user,
2026-06-11)** unless explicitly marked DECIDE. Do not re-litigate.

Extends [[context-v3-code-first-2026-06-10]] (relevant = full source,
internal = hidden, teaching lives in code) to its conclusion: **the
prompt IS a REPL session.** One static system header, the loaded
namespaces as the body, a thin data inventory, the agent's own entity
as data, and one threaded chronological transcript of messages and
evals — ending in a status line and a clean prompt. Everything that
was handcrafted prompt prose either becomes code (docstrings in
rendered namespaces), data (rows the agent can pull), or dies.

Prerequisite reading: `src/seon/ctx.cljs` (the composer +
`substrate-default-ctx` + the current section fns), one recent prompt
blob (`ls -t logs/prompts/`, ~82–87k chars — e.g.
`logs/prompts/mtM-2606111151/ANk-2606111151.txt`),
[[gym-upgrade-prd-2026-06-11]] §2 (the referee: prompt-blob
predicates U1, structural turn-profile + cache-stability U3),
[[open-issues-prd-2026-06-11]] Tier 2 (rows this PRD absorbs),
[[live-tiles-prd-2026-06-11]] U5 (the tile `::ai` twin this PRD
references, never duplicates).

## 1. The cache contract

The layout is ordered **top→bottom = static→volatile**. Everything
above `<warnings>` is the provider-cacheable prefix: byte-identical
across consecutive turns unless the world actually changed (a schema
registered, a namespace modified, an instruction edited). Everything
from `<warnings>` down is the volatile tail. The transcript is
append-only within a session — cache-optimal by construction.

**Standing gym predicate (from V4-1 on, every unit, every scenario):**
the prefix above `<warnings>` is byte-identical across consecutive
turns unless the world changed. This is gym-upgrade §2.2(b)'s
cache-stability check with the boundary moved from `:transcript` up to
`:warnings` — V4 makes more of the prompt stable, so the predicate
gets stricter.

## 2. The layout (DECIDED, user 2026-06-11)

Top to bottom:

### 2.1 `<system>` — rewritten minimal, universal

Byte-identical for **every agent and every turn**. The agent id moves
OUT of the tag (today: `<system agent="mtM-…">`) to the status line
(§2.9) — the system block becomes one shared cacheable artifact across
the whole cluster.

Contents: ~5 short concept paragraphs, **provider-neutral — no model
or vendor words, ever**:

- **(a)** This is a live Clojure REPL session on one human's runtime;
  the REPL is the only tool.
- **(b)** THE LIVE CONTEXT SYSTEM — this whole prompt re-derives from
  the database every turn; sections are views of NOW; other agents'
  writes appear next turn.
- **(c)** EVAL MECHANICS — a reply is multiple Clojure forms plus `;;`
  comments, nothing else; there are no tool calls.
- **(d)** RESULT VARS — every eval's value is saved under its unique
  var; `*1` `*2` `*3` `*e` are available; NEVER re-run what's already
  computed; a clipped display is NOT a clipped value — dig into big
  values with ordinary Clojure.
- **(e)** THE RENDERING SYSTEM — `:seon.render/ai` +
  `:seon.render/html` twins are how you show your human things.
- **(f)** One sentence: `*conn*` is ambient — your universe binds it.
- **(g)** The namespaces below are real loaded code, RECENCY-ordered
  (most-recently-modified last), not dependency-ordered — the runtime
  loaded them correctly.

Plus the ONE taught query (carried over from today's system block):
how to pull any namespace's fns/schemas/source from the db by
`:seon.ns/name` / `:seon.fn/sym` — this single query replaces the
`<functions>` count index for every namespace NOT included as a tag
(§2.4).

Identity/personality is NOT here — it lives in the soul rows in
`<instructions>` (`my.soul`, committed eeeb562).

### 2.2 `<instructions>` — the user-editable layer

The `my.kb.instruction` rows (priority-ordered, identity-upsert
editable — unchanged) PLUS the `my.soul` identity row(s). One
runtime-editable layer for everything a user (or agent) may amend
without a rebuild. Semi-static: busts only on an instruction/soul
transact.

### 2.3 `<namespace name="…">` tags — THE BODY

Full namespace source, **one tag per ns** (boundaries explicit —
no more single `<exemplars>` blob), ordered
**most-recently-modified LAST** so the stable substrate set forms a
stable cache prefix and the churning ns (usually the agent's own)
sits nearest the tail.

This **dissolves `<exemplars>`** — exemplars were never a different
kind of thing; they are just namespaces. The exemplar-root concept
becomes the **namespace-SELECTION policy**:

- always the agent's `my.*` namespaces (which also dissolves
  `<namespace-context>` — the agent's own ns IS a namespace tag, full
  source, no special section);
- plus the substrate teaching set (today's `relevant-roots`:
  `seon.agent.search`, `seon.agent.todo`, `my.kb` + children + test
  siblings);
- recency-bumped: a modified ns moves toward the tail, selection
  itself unchanged.

**Growth of the selection set is decided by gym A/B** (paid sweep per
candidate addition, spend free per gym-upgrade §6.2) — this absorbs
the open-issues "relevant-roots growth to post-split faces" row
(+~47k chars/prompt candidates: `seon.db`, `seon.schema`,
`seon.repl`, `seon.agent`, …). DECIDE(user): the budget bound for
namespace selection — a max total chars for the namespace body, or
per-ns caps, or unbounded-until-measured.

### 2.4 `<store>` — thin data inventory

ONE line per entity kind: `kind · id-attr · row count` (per-turn
kinds — `:seon.eval`, `:seon.agent.message`, turns/sessions — stay
**uncounted** so counts don't bust the prefix every turn). **No
teaching prose.** Justification: consult-first — the store inventory
is the only surface that shows what prior agents actually stored;
everything else about it the agent learns from the rendered
namespaces and the one taught query.

This **kills `<schema-catalog>` and `<functions>` as sections.** The
per-attr shape detail moves to where it already lives: registered
schemas are `:seon.schema` rows (one pull away), and the `my.kb`
namespace tag teaches the shape conventions in source. The
`<functions>` count index is replaced by the taught query in
`<system>` (§2.1).

### 2.5 `<your-entity>` — the agent's own datoms

A literal pull of the agent's OWN entity, rendered as data (EDN):
purpose, tile wiring (`:seon.render.live-tile/content`), registered
sections (`:seon.agent/ctx`), lifecycle attrs. Replaces the
`:purpose` section AND `:your-sections`.

The point (user): the agent sees its own datoms, so
"`transact!` → changes next turn" is self-evident — editing your
purpose, wiring your tile, adding a section are all just writes to
the entity you are looking at. Show, don't tell, applied to identity.

### 2.6 `<your-tile>` — what your human currently sees

The `::ai` twin of the wired live tile (one render, two twins) plus
the wired fn pointer. Spec'd in [[live-tiles-prd-2026-06-11]] U5 —
**reference, don't duplicate**; this PRD only fixes its slot in the
layout (between `<your-entity>` and `<warnings>`).

### 2.7 `<warnings>` — unchanged

Mechanics unchanged (reactive, derived, vanishes when fixed). Placed
here — first element of the volatile tail.

Sections not named in this layout (today: `:open-todos`, priority 45)
keep their current slot in the volatile zone between `<warnings>` and
`<transcript>`, mechanics unchanged — no conversion in this PRD.

### 2.8 `<transcript>` — ONE threaded REPL stream

Messages AND evals in one chronological stream — **no separate
messaging section**, append-only tail, cache-optimal. REPL-real
rendering:

- message lines: `user> …` / `agent-<id>> …` (own outbound:
  `assistant> …` as today);
- each eval: `<ns>=> <form>`, then captured output, then the value
  line **carrying the eval's result VAR id** — the principle: the id
  is visible per eval, the full value retrievable by ordinary Clojure.
  DECIDE(build-time): the exact glyph/format of the value line
  (e.g. `#'res-<id> ⇒ <value>` vs `<value>  ; ⇒ res-<id>` — pick at
  V4-4 implementation, pin with a test).

**Session resume boundary:** one marker row per resume — "values
above are from a previous process — no longer dereferenceable".
Prior-session evals render WITHOUT result-var handles;
`(result old-id)` / old var access errors say "prior session".

**Eviction:** messages are EXEMPT (committed 8ab8cbd; each message
individually bounded by `message-render-cap`); eval rows evict
**oldest-first** within the transcript budget.

### 2.9 Status line + prompt

The final two lines of every prompt:

```text
;; ── <ns> · turn N · M since-user (cap C) · <user-localized time+tz> · inbox K · agent <id> ──
<ns>=>
```

The agent id lands here (moved out of `<system>`, §2.1). `inbox K` =
count of unanswered inbound messages. DECIDE(user): anything else in
the line.

## 3. Capabilities dissolution

The `<capabilities>` section (today ~214 lines of handcrafted worked
examples) **dissolves**:

- **API teaching → docstrings** of the included substrate namespace
  sources (code-first: the rendered `seon.agent.todo` / `my.kb` /
  `seon.agent.search` sources already demonstrate register!,
  map-in/map-out, envelopes, provenance — and the public faces'
  docstrings carry the call shapes as the selection set grows).
- **Irreducible prose** (register-before-transact, the deep-namespace
  attr rule) → the system prompt's concept paragraphs or a
  `my.kb.instruction` row — whichever surface the gym shows holds the
  behavior.

This **absorbs V3-D** (the datahike API block: datahike teaching rides
the included ns sources/docstrings — no separate var-metadata render
unit) and **V3-E's intent** (the namespaces ARE the show-don't-tell;
a separate demonstrated-evals section is no longer the mechanism —
the live threaded transcript and the rendered code do that work). See
[[v3e-demonstrated-evals-prd-2026-06-11]] — its goals are met by this
ladder; do not implement it as written.

## 4. Platform law (user, 2026-06-11)

From today's pod-boot incident: **registered schema forms are PURE
DATA** — no `[:fn …]`, no sci closures, nothing that needs evaluation
to reconstruct the schema from its stored `:seon.schema/source`. A
schema that can't round-trip as data is a boot hazard (the store is
read before any evaluator exists). Enforce at `register!` where
cheap; state in `seon.schema`'s docstring; violation = loud error,
not a silent accept.

## 5. The unit ladder

Each unit converts/eliminates **EXACTLY ONE section**, and is
gym-scored before the next: prompt-blob predicates (gym U1) + the
cache-stability check (gym U3, §1's standing predicate) + S-32/S-12
paid re-run + a paid sweep — **spend free** (gym-upgrade §6.2:
accuracy is the only axis). Per unit: files ≤7, gym predicates,
falsification. Correctness > benchmark continuity — numbers
re-baseline per unit and the scorecard log says so.

### V4-1 — `<system>` rewrite + agent-id-to-tail

- **Converts:** `:system` (and moves the id into the prompt/status
  line, partially fronting V4-5).
- **Files (≤7):** `src/seon/ctx.cljs` (`system-section`,
  `prompt-section` id line), `test/seon/agent_context_test.cljs`,
  gym scenario predicate additions.
- **Gym predicates:** `:prompt-every-turn` on a system-paragraph
  sentinel; `:prompt-excludes` any provider/vendor word list; NEW
  standing cache-stability predicate (prefix above `<warnings>`
  byte-identical across consecutive turns, two agents' system blocks
  byte-identical to each other — cross-agent identity is the new
  claim).
- **Falsification:** diff two different agents' turn-0 blobs — the
  `<system>` block must be byte-equal; grep blobs for the agent id
  above the status line — zero hits outside `<your-entity>`/
  transcript content.

### V4-2 — namespace tags + recency ordering (exemplars dissolve)

- **Converts:** `:exemplars` → `<namespace name="…">` tags; absorbs
  `:namespace-context` (own ns = a tag).
- **Files (≤7):** `src/seon/ctx.cljs` (`exemplars-section` →
  `namespaces-section`, delete `namespace-context-section`),
  `src/seon/client.cljs` (boot indexer must persist a last-modified
  signal for ordering — `:seon.ns` row tx time suffices),
  `test/seon/agent_context_test.cljs`, gym scenarios.
- **Selection policy** (§2.3): agent's `my.*` + teaching set,
  recency-bumped; growth via gym A/B (absorbs relevant-roots row).
- **Gym predicates:** `:prompt-includes` `<namespace
  name="seon.agent.todo">`; `:prompt-excludes` `<exemplar` and
  `<namespace-context>`; ordering predicate — modify a ns between
  turns (gym U5 `:foreign-write`/churn), next blob shows it LAST;
  cache-stability standing.
- **Falsification:** edit the agent's home ns mid-run — the prefix
  above the moved tag must stay byte-identical (only the tail
  reorders); a `*.internal` ns must never appear as a tag.

### V4-3 — catalogs → `<store>` (RISKIEST — gym gates HARD)

- **Converts:** `:schema-catalog` + `:functions-catalog` → `<store>`
  (one conversion: the two catalogs are one surface, "what exists" —
  they die together into one inventory).
- **Files (≤7):** `src/seon/ctx.cljs` (new `store-section`, delete
  the two catalog sections), `test/seon/agent_context_test.cljs`,
  s32/s12 scenario EDNs (predicates re-anchored on the inventory
  line + the taught query).
- **Risk:** consult-first (P8's proven 5/5 behavior) currently leans
  on the schema-catalog's attr listing. The `<store>` line shows the
  KIND exists but not its attrs — the agent must take the one taught
  query to get shapes. **Gate:** S-32 + S-12 paid runs must stay
  green on consult-first under the anchored predicates
  (`:my\.kb\.codebase/`-class, gym §3.2) BEFORE V4-4 starts; if
  consult-rate drops, the unit stops and the inventory line gains
  attrs back incrementally (id-attr → +attr names) until the gym is
  green — never a silent regression.
- **Falsification:** a stub run scripted to consult must find the kind
  via `<store>` + the taught query alone; `:prompt-excludes`
  `<schema-catalog>` and `<functions>`; per-turn kinds show no count
  (cache-stability standing predicate catches a leaked count).

### V4-4 — transcript threading + REPL rendering + resume marker + result vars

- **Converts:** `:transcript` (threading is already merged today;
  this unit lands the REPL-real rendering, the per-eval result-var
  id, the resume boundary, and verifies `*1` `*2` `*3` `*e` actually
  resolve in the eval environment — §2.1(d) must not teach a lie).
- **Files (≤7):** `src/seon/ctx.cljs` (`format-eval-row`,
  `format-message-row`, `transcript-section`), `src/seon/eval.cljs`
  (result-var binding + `*1`-family if missing; `(result old-id)`
  prior-session error wording), `test/seon/agent_context_test.cljs`,
  `test/seon/resume_replay_test.cljs`, gym scenario (resume).
- **Gym predicates:** `:prompt-includes` the `<ns>=>` eval prefix and
  a result-var id for a known eval; resume scenario (gym U11 shape):
  post-restart blob carries the boundary marker, prior evals carry NO
  var handles, `(result old-id)` errors with "prior session";
  eviction predicate — eval flood never evicts a message (pinned
  test exists: `transcript-eviction-keeps-messages-under-eval-flood`).
- **Falsification:** eval `*1` immediately after a known eval — must
  return its value (a REPL that displays vars it can't deref is the
  failure); force eviction and assert the OLDEST eval went first.
- **DECIDE(build):** the exact result-var glyph (§2.8) — pick here,
  pin with a byte-level test.

### V4-5 — status line + prompt

- **Converts:** `:prompt` → the §2.9 two-line form (`inbox K`,
  `agent <id>`, user-localized time+tz; V4-1 already moved the id).
- **Files (≤7):** `src/seon/ctx.cljs` (`prompt-section`),
  `test/seon/agent_context_test.cljs`, gym scenarios.
- **Gym predicates:** `:prompt-every-turn` the `;; ──` line shape
  (regex); inbox count moves when a foreign message lands between
  turns (gym U5 `:foreign-write`).
- **Falsification:** the status line is the ONLY place the turn
  number/time appear — grep the blob above `<warnings>` for
  timestamps (cache-stability standing predicate is the structural
  version).
- **DECIDE(user):** anything else in the line.

### V4-6 — capabilities dissolution

- **Converts:** `:capabilities` → gone (§3): docstring/teaching audit
  of the rendered namespaces + at most one new `my.kb.instruction`
  row for irreducible prose + the §2.1 system paragraphs already
  landed in V4-1.
- **Files (≤7):** `src/seon/ctx.cljs` (delete
  `capabilities-section` + helpers), the substrate ns docstrings the
  audit strengthens (e.g. `src/seon/db.cljs` faces,
  `src/my/kb.cljs`), `test/seon/agent_context_test.cljs`, s32/s12
  EDNs.
- **Sequenced LAST** deliberately: by now the namespaces, store,
  your-entity, and threaded transcript carry the teaching load; this
  unit removes the redundancy and measures whether anything was
  load-bearing after all.
- **Gym predicates:** full paid trio (S-01/S-12/S-21/S-32) green
  with `:prompt-excludes <capabilities>`; register-before-transact
  behavior held (S-21's register-then-transact arc).
- **Falsification:** if any paid scenario goes red, the failing
  teaching is identified from the blob diff and moved to a docstring
  or instruction row — NOT restored as a prose section.

## 6. DECIDE register (the only open items)

| Item | Owner | Where it lands |
|---|---|---|
| Eval result-var glyph/format on the value line | build-time | V4-4, pinned by test |
| Status-line extras beyond §2.9 | user | V4-5 |
| Namespace-selection budget bound (total/per-ns/unbounded-until-measured) | user, informed by gym A/B | V4-2 |

## 7. Supersedes / absorbs

From [[open-issues-prd-2026-06-11]] Tier 2 (rows updated there to
point here):

- **capabilities XML wrapper** (uniformity canary) → superseded by
  V4-6: the section dissolves; no wrapper to add.
- **`relevant-roots` growth to post-split faces** → absorbed into
  V4-2's namespace-selection policy; growth decided by gym A/B.
- **V3-D datahike API block** → absorbed into V4-6/§3: datahike
  teaching rides the included ns sources and docstrings.
- **V3-E show-don't-tell** → intent absorbed (§3): the namespaces ARE
  the show-don't-tell; [[v3e-demonstrated-evals-prd-2026-06-11]] is
  superseded as an implementation plan.

Relationship to in-flight work: [[gym-upgrade-prd-2026-06-11]] is the
referee for every rung (U1 prompt-blob predicates and U3
cache-stability should land before V4-1 scores);
[[live-tiles-prd-2026-06-11]] U5 owns `<your-tile>`'s mechanics — this
PRD only places it. Concurrent lanes (boot-fix, tiles T2, gym U3)
touch `render/live_tile.cljs`, `render.cljs`, `client.cljs`,
`inspector.cljs`, `chat.cljs`, the gym driver — V4 units that share
files (`client.cljs` in V4-2) coordinate at land time.
