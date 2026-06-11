---
type: prd
status: active
tags: [prd, agent]
---

# Context v4 — REPL realism (2026-06-11)

Fresh-read spec. All design points below are **DECIDED (user,
2026-06-11)** unless explicitly marked DECIDE. Do not re-litigate.
Revised same day after user round-2 review (r2) — r2 decisions
noted inline.

## 0. The governing rule (user, r2): SIMPLER

**This context system must be MUCH simpler than v3. Do not carry bad
ideas forward.** No fragile namespace lists, no budgets (Clojure code
is small — budgets return only on measured need), no structural
coupling anywhere (especially the gym) that breaks every time the
context changes. When a mechanism needs a list, find the structural
rule instead. When a section needs justification gymnastics, kill it
or shrink it.

Extends [[context-v3-code-first-2026-06-10]] (relevant = full source,
internal = hidden, teaching lives in code) to its conclusion: **the
prompt IS a REPL session.** One static system header, the soul, ALL
the loaded namespaces as the body, the agent's own entity as a map,
and one threaded chronological transcript of messages and evals —
ending in a status line and a clean prompt. Everything that was
handcrafted prompt prose either becomes code (docstrings and
`;;`-commented tutorial evals), data (the agent's own entity), or
dies — including the store inventory, which is a fn the startup
tutorial demonstrably RUNS (§2.4), not a section.

Prerequisite reading: `src/seon/ctx.cljs` (the composer +
`substrate-default-ctx` + the current section fns), one recent prompt
blob (`ls -t logs/prompts/`, ~82–87k chars — e.g.
`logs/prompts/mtM-2606111151/ANk-2606111151.txt`),
[[gym-upgrade-prd-2026-06-11]] §2 (the referee: prompt-blob
predicates U1; U3's structural gates are REMOVED per r2),
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

**Gym relationship (r2 — DECOUPLED):** the gym does NOT assert context
structure. No layout predicates, no section-name coupling, no standing
structural gates — those broke every time the context improved (the
fragility the user called out). The gym's whole job is (a) agent
BEHAVIOR — LLM-judge interpretation plus mechanical store/outcome
checks, and (b) CAPTURE — the prompt blobs (what the agent actually
saw) and the db state per eval, so "why didn't it do X" is always
answerable from the record. Cache-prefix stability may be reported as
informational telemetry; it never gates a scorecard. (Gym U3's
standing structural gates: REMOVED by user decision, r2.)

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
  var; NEVER re-run what's already computed; a clipped display is NOT
  a clipped value — dig into big values with ordinary Clojure.
  (`*1` `*2` `*3` `*e`: PARKED, r2 — low priority; trivial to add
  later since values are already stored per eval-id.)
- **(e)** THE RENDERING SYSTEM — `:seon.render/ai` +
  `:seon.render/html` twins are how you show your human things.
- **(f)** THE SHARED STORE — all agents are wired to ONE shared
  datahike (datomic-like) database; `*conn*` is ambient (your
  universe binds it). How to use it is NOT explained here — `seon.db`
  is an included namespace; its docstrings carry the API.
- **(g)** The namespaces below are real loaded code, RECENCY-ordered
  (most-recently-modified last), not dependency-ordered — the runtime
  loaded them correctly.
- **(h)** THINK IN COMMENTS — `;;` comment lines BEFORE each form are
  where reasoning goes; the format teaching (forms + comments only,
  nothing else) stays sharp.

**The teaching split (r2): CONCEPTS in the system prompt, USAGE in
code.** The system prompt explains the important concepts — the
paragraphs above — and nothing else. The old "one taught query" and
the functions index are dead (the user judged them poor explanations
of the harness). Usage teaching rides two show-don't-tell surfaces:
(a) the rendered namespace sources, whose docstrings and `;;` comments
ARE the API documentation (they're in the prompt); (b) the **startup
evals as a tutorial** — see §3.1 for the worked examples this PRD
requires.

Identity/personality is NOT here — it lives in the soul rows in
`<instructions>` (`my.soul`, committed eeeb562).

### 2.2 `<instructions>` — the user-editable layer (r2: soul ONLY)

**`my.kb.instruction` DIES (user, r2).** Per-agent self-instructions
live on the agent's OWN entity (§2.5) — rendered every turn, "it's
just context." This section carries ONLY the `my.soul` rows (identity
and repl-mechanics; user-editable store rows, committed eeeb562) —
one swappable layer, busts only on a soul transact.

**DECIDE(user): where the four cluster-wide teachings go**
(consult-before-research, store-proactively, reply-once,
namespace-map): (a) fold into the soul text — one user-editable blob;
RECOMMENDED: behavioral policy is exactly what a user might swap — or
(b) become docstrings on the relevant substrate fns. Until decided,
the rows keep rendering from their current source.

**Demo note:** the committed demo script's "instruction-edit kicker"
beat referenced `my.kb.instruction` — the soul-edit replaces it.

### 2.3 `<namespace name="…">` tags — THE BODY

Full namespace source, **one tag per ns** (boundaries explicit —
no more single `<exemplars>` blob), ordered
**most-recently-modified LAST** so the stable substrate set forms a
stable cache prefix and the churning ns (usually the agent's own)
sits nearest the tail.

This **dissolves `<exemplars>`** — exemplars were never a different
kind of thing; they are just namespaces. **Selection (r2): ONE
structural rule, NO lists** —

> Include ALL namespaces EXCEPT `*.internal` ones and third-party
> packages. In practice: every `seon.*` and `my.*` namespace whose
> name does not end in `.internal`.

New namespaces auto-include the moment they exist — no
`relevant-roots`, no exemplar-root set, no per-list maintenance. This
also dissolves `<namespace-context>` (the agent's own ns is just a
tag like any other) and DELETES the budget question: **no namespace
bounds or budgets for now (user, r2) — Clojure code is small;
budgets return only on measured need.** The old relevant-roots-growth
A/B is absorbed trivially: everything is in.

### 2.4 The store inventory — NOT a section (r3): a fn + a startup eval

**`<store>` is DEAD too (user, r3).** The inventory is a pre-written
substrate fn (e.g. `seon.db/store-inventory` — one line per entity
kind: kind · id-attr · row count) that the creation turn RUNS as a
real startup eval (§3.1's tutorial). Everything connects: the fn's
SOURCE is visible (it lives in an included namespace), the CALL is
visible (in the transcript), the RESULT is visible (the value line) —
the agent knows exactly where the numbers come from and that
re-running the fn is how you get fresh ones. No section, no special
mechanism: it's just a query, demonstrated.

Staleness trade (stated, accepted): the startup eval's counts age
within a session; "re-run the query for current numbers" IS the REPL
model being taught. If the gym shows consult-first degrading on stale
counts, the escalation is a per-WAKE eval (fresh at every wake, still
in-transcript) — never a resurrected section.

This **kills `<schema-catalog>`, `<functions>`, AND `<store>` as
sections.** Anything not visible in an included source is one
ordinary datahike query away — `seon.db`'s docstrings show how, and
the startup eval demonstrates it.

### 2.5 `<your-entity>` — the agent's own entity, as a MAP

A pull of the agent's OWN entity rendered as a **pretty-printed map
with keys and values** (r2: a map, NOT raw datoms): purpose, tile
wiring (`:seon.render.live-tile/content`), registered sections,
lifecycle attrs, and **any self-instructions the agent has written to
itself**. Replaces the `:purpose` section AND `:your-sections` AND
(r2) the per-agent role of `my.kb.instruction`.

The section's rendered header SAYS the second half out loud: *this is
your entity — transact to it and the change appears here next turn;
write notes and instructions to yourself here.* It's just context.
Editing your purpose, wiring your tile, instructing your future self
are all writes to the map you are looking at. Show-don't-tell applied
to identity — and the startup evals (tiles-PRD U4) demonstrate the
exact transact-by-lookup-ref move at creation.

### 2.6 `<your-tile>` — what your human currently sees

The `::ai` twin of the wired live tile (one render, two twins) plus
the wired fn pointer. Spec'd in [[live-tiles-prd-2026-06-11]] U5 —
**reference, don't duplicate**; this PRD only fixes its slot in the
layout (after `<your-entity>`, before `<warnings>`).

### 2.7 `<warnings>` — unchanged

Mechanics unchanged (reactive, derived, vanishes when fixed). First
element of the volatile tail, after `<your-tile>`.

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

## 3. The teaching model — tutorial evals + commented code (r2)

The combo of raw namespace code and the initial evals carries the
usage teaching, **written like a tutorial**: `;;` comments before each
form explain what's happening and model think-in-comments. These
examples are NORMATIVE — implementing units write to this register.

### 3.1 The startup evals ARE the tutorial

The creation turn (tiles-PRD U4) is real evals in the agent's log,
written so reading them teaches the three core moves:

```clojure
;; I am an entity in the shared store — everything about me is data,
;; and I change myself by transacting to my own lookup ref.
;; First: wire my live tile (what my human sees) to the default
;; welcome. Any fn returning {:seon.render/html … :seon.render/ai …}
;; can go here — when I have something better to show, I write a fn
;; and point this attr at it.
(seon.db/transact!
  [{:seon.db/ref [:seon.agent/id "kXQ-2606101814"]
    :seon.render.live-tile/content 'seon.render.live-tile/welcome}])
;; => {:seon.db/ok? true}

;; Second: my todo view. A context section is just a query rendered
;; every turn — register it once, and the section appears whenever
;; the query has rows, vanishes when it doesn't.
(seon.agent/add-section!
  {:seon.ctx/id :open-todos
   :seon.ctx/render 'seon.agent.todo/open-todos-section})
;; => {:seon.ctx/ok? true}

;; Third: what's already in the shared store? Other agents stored
;; knowledge here before me — checking BEFORE researching is how I
;; avoid paying for answers that already exist. (The fn's source is
;; in seon.db below — it's an ordinary query; I re-run it whenever I
;; need current numbers.)
(seon.db/store-inventory)
;; => [{:kind :my.kb.codebase  :id-attr :my.kb.codebase/question :rows 14}
;;     {:kind :seon.workout    :id-attr :seon.workout/date       :rows 9}
;;     …]

;; Anything I store this way survives restarts; anything I only
;; compute does not. When in doubt, transact it.
```

A fresh agent reading its own log sees: writes-to-self by lookup ref,
the value-or-fn render pattern, sections-as-queries, and the
persistence rule — without one line of prose teaching.

### 3.2 Docstrings read as usage docs

Included namespaces are the API reference. The bar, by example
(`seon.db/transact!`-style):

```clojure
(defn transact!
  "Commit tx-data to the shared store. Maps with namespaced keys only;
   every attr must be registered (seon.schema/register!) BEFORE first
   use — unregistered attrs are rejected with a fix-example, nothing
   partial commits. Returns {:seon.db/ok? true …} or an error envelope
   {:seon.db/ok? false :seon.db/error …} — it NEVER throws.

   ;; store a fact about an existing entity by lookup ref:
   (transact! [{:seon.db/ref [:my.kb.doc/path \"README.md\"]
                :my.kb.doc/title \"Seon\"}])"
  …)
```

One docstring = contract + envelope behavior + a commented call. An
agent that reads the source needs no capabilities section.

## 3b. Capabilities dissolution

The `<capabilities>` section (today ~214 lines of handcrafted worked
examples) **dissolves**:

- **API teaching → docstrings** of the included substrate namespace
  sources (code-first: the rendered `seon.agent.todo` / `my.kb` /
  `seon.agent.search` sources already demonstrate register!,
  map-in/map-out, envelopes, provenance — and the public faces'
  docstrings carry the call shapes as the selection set grows).
- **Irreducible prose** (register-before-transact, the deep-namespace
  attr rule) → the system prompt's concept paragraphs or a soul row
  (r2 — `my.kb.instruction` is dead) — whichever surface the gym
  shows holds the behavior.

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
gym-scored before the next — **BEHAVIORALLY** (r2): LLM-judge +
mechanical store/outcome checks + prompt-blob capture; NO structural
section assertions (§1). Byte-level checks (cross-agent identity,
prefix stability) live in UNIT TESTS where they pin the unit's own
claim — never as standing gym gates. Paid re-runs per unit, **spend
free**. Correctness > benchmark continuity — numbers re-baseline per
unit and the scorecard log says so.

### V4-0 — instructions simplification (r2, NEW — small, first)

- **Converts:** `<instructions>` → soul rows only; `my.kb.instruction`
  ns + seeds deleted (pending the DECIDE on the four teachings' home —
  if soul, fold them into the soul text in this unit); `<your-entity>`
  header gains the write-notes-to-yourself sentence when V4's
  `<your-entity>` lands (until then, the existing purpose section
  stays).
- **Files (≤6):** delete `src/my/kb/instruction.cljs` + its seeds in
  `client.cljs`, `src/my/soul.cljs` (absorb teachings if DECIDE=soul),
  `src/seon/ctx.cljs` (instructions-section reads soul only), tests.
- **Falsification:** prompt blob carries the teachings exactly once
  (from soul); S-32 consult-first paid run stays green (the teaching
  moved, not died); demo kicker rehearses as a soul edit.

### V4-1 — `<system>` rewrite + agent-id-to-tail

- **Converts:** `:system` (and moves the id into the prompt/status
  line, partially fronting V4-5).
- **Files (≤7):** `src/seon/ctx.cljs` (`system-section`,
  `prompt-section` id line), `test/seon/agent_context_test.cljs`,
  gym scenario predicate additions.
- **Gym predicates (behavioral only):** `:prompt-every-turn` on a
  system-paragraph sentinel; `:prompt-excludes` any provider/vendor
  word.
- **Falsification (unit tests, not gym gates):** diff two different
  agents' turn-0 blobs — the `<system>` block must be byte-equal;
  grep blobs for the agent id above the status line — zero hits
  outside `<your-entity>`/transcript content.

### V4-2 — namespace tags + recency ordering (exemplars dissolve)

- **Converts:** `:exemplars` → `<namespace name="…">` tags; absorbs
  `:namespace-context` (own ns = a tag).
- **Files (≤7):** `src/seon/ctx.cljs` (`exemplars-section` →
  `namespaces-section`, delete `namespace-context-section`),
  `src/seon/client.cljs` (boot indexer must persist a last-modified
  signal for ordering — `:seon.ns` row tx time suffices),
  `test/seon/agent_context_test.cljs`, gym scenarios.
- **Selection (§2.3, r2):** ONE rule — all `seon.*` + `my.*` except
  `*.internal`; no lists, no budgets.
- **Gym predicates (behavioral only):** `:prompt-includes`
  `<namespace name="seon.agent.todo">`; `:prompt-excludes`
  `<exemplar`.
- **Falsification (unit tests):** a new namespace defined at runtime
  appears as a tag next turn with NO config change; a `*.internal` ns
  never appears; modify a ns between turns — next blob shows it LAST
  and the prefix above the moved tag is byte-identical.

### V4-3 — catalogs → `store-inventory` fn + startup eval (RISKIEST — gym gates HARD)

- **Converts:** `:schema-catalog` + `:functions-catalog` → DELETED
  sections; replaced by `seon.db/store-inventory` (a pre-written
  substrate fn, source visible in the included `seon.db` tag) RUN as
  a real eval in the creation turn (§2.4, §3.1 — r3).
- **Files (≤7):** `src/seon/db.cljs` (`store-inventory` + its
  tutorial-grade docstring), `src/seon/ctx.cljs` (delete the two
  catalog sections), `src/seon/client.cljs` (the startup-eval form
  joins tiles-PRD U4's creation turn), `test/`, s32/s12 scenario
  EDNs (predicates re-anchored on the eval result in the transcript).
- **Risk:** consult-first (P8's proven 5/5 behavior) currently leans
  on the schema-catalog's attr listing. Now the agent sees the
  inventory RESULT in its transcript (kinds + counts) and gets shapes
  from the namespace sources / a re-run. **Gate:** S-32 + S-12 paid
  runs must stay green on consult-first under the anchored predicates
  (`:my\.kb\.codebase/`-class, gym §3.2) BEFORE V4-4 starts; if
  consult-rate drops: first escalation = run the inventory eval per
  WAKE (fresh numbers, still in-transcript); second = richer
  inventory rows (attr names). NEVER a resurrected section.
- **Falsification:** a stub run scripted to consult must find the
  kind via the startup eval's result + the namespace sources alone;
  `:prompt-excludes` `<schema-catalog>`, `<functions>`, `<store>`.

### V4-4 — transcript threading + REPL rendering + resume marker + result vars

- **Converts:** `:transcript` (threading is already merged today;
  this unit lands the REPL-real rendering, the per-eval result-var
  id, and the resume boundary. `*1`-family: PARKED per r2 — the
  system prompt must not mention it until it exists).
- **Files (≤7):** `src/seon/ctx.cljs` (`format-eval-row`,
  `format-message-row`, `transcript-section`), `src/seon/eval.cljs`
  (result-var rendering; `(result old-id)`
  prior-session error wording), `test/seon/agent_context_test.cljs`,
  `test/seon/resume_replay_test.cljs`, gym scenario (resume).
- **Gym predicates:** `:prompt-includes` the `<ns>=>` eval prefix and
  a result-var id for a known eval; resume scenario (gym U11 shape):
  post-restart blob carries the boundary marker, prior evals carry NO
  var handles, `(result old-id)` errors with "prior session";
  eviction predicate — eval flood never evicts a message (pinned
  test exists: `transcript-eviction-keeps-messages-under-eval-flood`).
- **Falsification:** dereference a rendered eval-id var — must return
  its stored value (a REPL that displays vars it can't deref is the
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
- **Falsification (unit test):** the status line is the ONLY place
  the turn number/time appear — grep the blob above `<warnings>` for
  timestamps.
- **DECIDE(user):** anything else in the line.

### V4-6 — capabilities dissolution

- **Converts:** `:capabilities` → gone (§3/§3b): docstring/teaching
  audit of the rendered namespaces + at most one new soul row for
  irreducible prose + the §2.1 system paragraphs already landed in
  V4-1.
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
| The four cluster-wide teachings' home: soul text (recommended) vs docstrings | user | V4-0 |
| Eval result-var glyph/format on the value line | build-time | V4-4, pinned by test |
| Status-line extras beyond §2.9 | user | V4-5 |

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
referee for every rung — AFTER the r2 gym simplification (rip out
structural gates; behavior + capture only) lands;
[[live-tiles-prd-2026-06-11]] U5 owns `<your-tile>`'s mechanics — this
PRD only places it. Concurrent lanes (boot-fix, tiles T2, gym U3)
touch `render/live_tile.cljs`, `render.cljs`, `client.cljs`,
`inspector.cljs`, `chat.cljs`, the gym driver — V4 units that share
files (`client.cljs` in V4-2) coordinate at land time.
