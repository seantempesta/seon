---
type: research
status: active
tags: [research, agent]
---

# Full-context prompt audit — the assembled agent context (2026-06-26)

Audit of the WHOLE assembled prompt the agent actually receives, not just the system
role. Primary artifact: the real generated prompt
`logs/turns/vFA-2606261800/3-LNf-2606261800/prompt.txt` (the newest, lean config —
SOUL absent), cross-checked against source (`seon.ctx/system-text` in
`src/seon/ctx.cljs`, `seon.ctx.namespaces`, `my.kb.shared`). Generation is treated as
SUSPECT throughout. Line citations are to that prompt file unless prefixed otherwise.

This document is a normal markdown doc, so it uses code fences/backticks freely; the
"no fences, no backticks in `;`-prose" rule applies only to the agent-facing prose I
**recommend**, which I render plainly and call out.

---

## TL;DR

- **The whole prompt is 112,765 chars / ~28,200 tokens. `:namespaces` is 91,653 chars
  / ~22,900 tokens — 81% of everything.** Inside it, `seon.db`'s dumped source is
  **70,003 chars / ~17,500 tokens by itself — 62% of the ENTIRE prompt.** The
  system message (15,453 chars / ~3,860 tok) is the second-largest section and the
  only one worth prose-editing; everything else is small.
- **THE lever is moving `seon.db` source OUT of `:namespaces` into a curated, tested
  `seon.db.examples` ns.** Replacing the 70k db-source dump with a ~7k examples ns
  takes the prompt from ~28.2k → **~12.4k tokens (−56%)**. db source stays indexed
  and searchable, just not dumped. This dwarfs every system-text edit combined.
- **GENERATION BUG #1 (high): every full-source namespace is rendered TWICE over.**
  Each `:full` ns shows its complete file source AND then an appended `[fn …]`/`[schema
  …]` member list of the same fns/schemas. In `seon.db` that redundant tail is ~9,671
  chars; in `my.kb` the provenance schemas appear as `register!` calls (L323–329) and
  again as `[schema …]` rows (L331–339).
- **GENERATION BUG #2 (high): the agent's OWN namespace is ABSENT.** Both the system
  message (L150–152) and the namespaces header (L268–269) promise "YOUR OWN namespace
  renders in FULL — your live workspace, the most important thing." The agent's home
  ns is `my.agent.vFA-2606261800` (transcript L2290, readline L2314) — and it is NOT
  in the rendered namespaces at all (empty ns → `render-one` omits it). The prompt's
  most-emphasized promise is unmet in this real render.
- **Biggest cross-section overlap: the eval loop is taught in THREE places** — system
  message (L18–29, L31–42, L143–148), the transcript masthead (L2280–2288), and again
  in fragments. The transcript SHOWS the loop live; the system message should NAME it
  once and stop re-explaining it. Other cross-section overlaps: db cheat-sheet (system
  msg) vs db source dump (`:namespaces`); "consult stored data first" (system msg ×4)
  vs the `:inventory` section that IS that data; errors-are-values (system msg) vs the
  `:warnings` section that shows one live.
- **Render-twins block (system msg L92–98): CUT.** It's pipeline theory assuming
  canvas/section/hiccup vocabulary the agent lacks at boot; the `:live-tile` section
  already SHOWS the tile and teaches how to change it. Move the mechanic there.
- **THREE content gaps the system message should fill (NEW vs current, flagged in
  §7):** (a) turn-position/sliding-window clarity — present but buried + the readline
  shows two unexplained counters (`turn 3 · loop 2/20`); (b) an explicit instruction
  to USE the live tile to communicate and narrate multi-turn work — currently nowhere;
  (c) progress bars from itemized lists (todos) on the tile — currently nowhere.
- **Live proof the steering isn't landing:** the agent broke its own turn with a loose
  backtick (transcript L2300 `` `:dentist` ``) — exactly what system-msg L53–58 warns
  against — producing the READ ERROR in `:warnings`. The no-backtick rule needs more
  prominence, not more words.

---

## Section size profile (measured)

| Section | chars | ~tokens | % of prompt | render state |
|---|---:|---:|---:|---|
| system (role) | 15,453 | 3,863 | 13.7% | present |
| `:namespaces` | 91,653 | 22,913 | 81.3% | present — **the bloat** |
| `:your-entity` | 759 | 189 | 0.7% | present |
| `:live-tile` | 1,104 | 276 | 1.0% | present |
| `:warnings` | 503 | 125 | 0.4% | present (reactive) |
| `:inventory` | 571 | 142 | 0.5% | present |
| `:transcript` | 2,471 | 617 | 2.2% | present |
| **total** | **112,765** | **~28,191** | 100% | |

Inside `:namespaces`, per dumped namespace:

| ns block | chars | note |
|---|---:|---|
| `seon.db` | 70,003 | 60,332 source + 9,671 redundant member rows |
| `seon.agent.todo` | 10,669 | full source + member rows |
| `my.kb.shared` | 6,560 | full source + member rows |
| `my.kb` | 3,136 | full source + duplicate schema rows |
| `my.contact` | 260 | schema rows only (runtime-created) |
| `warntest.dom` | 194 | schema rows only |
| `warntest.ent` | 163 | schema rows only |
| `my.workout` | 96 | schema rows only |
| `my.book` | 89 | schema rows only |
| `my.agent.vFA-…` | **0** | **MISSING — see GEN BUG #2** |

`db + todo` (the full-source whitelist) = 80,672 chars / ~20,200 tok = 72% of the
prompt. **Absent sections this turn** (correctly, by design): `:soul` (SOUL.md off in
lean config), `:shared-instructions` (singleton seeded empty), `:open-todos` (agent
completed its one todo), `:relevant-source` (env-gated off). The cache-boundary marker
sits at L2229 — the whole 91k `:namespaces` body is in the byte-stable cacheable
prefix (relevant to §6's token-cost nuance).

---

## 0. GENERATION INTEGRITY (generation treated as suspect)

### GI-1 — Double-render of full-source namespaces (HIGH, ~9.7k+ chars wasted)

Every `:full` ns block = complete file SOURCE **then** an appended member list. The
source already contains every `defn` and `register!` verbatim, so the member rows are
pure duplication. Evidence:

- `seon.db`: source body ~60,332 chars, then `[fn seon.db/…]` rows begin at L2016 and
  run to the block end — ~9,671 chars of fn-signature + `:spec` + clipped-docstring
  lines, every one of which is already in the source above.
- `my.kb`: `(schema/register! ::source-path :string)` … (L323–329, real source) then
  `[schema :my.kb/source-path] :string` … (L331–339) — the same five schemas twice.
- `my.kb.shared`: full source (L342–461) then `[fn …]`/`[schema …]` rows (L463–480).

Root cause: `seon.ctx/render-namespace` at `:full` detail emits source + members
(by design per its docstring). The fix: **for `:full` detail, suppress the appended
member list** (source already shows them); keep member rows only for `:signature`
detail (where there is no source). Independently saves ~9.7k in db plus ~2–3k across
the other full nses, and removes a genuinely confusing "why is this here twice" for
the agent. The fn member rows also TRUNCATE mid-form (L463: `[:catn [:my.kb.shared/db
…`) — acceptable for a signature view, noise next to full source.

### GI-2 — The agent's own namespace is missing (HIGH, broken promise)

System message L150–152 and namespaces-header L268–269 both promise the agent's own
namespace renders FULL as "the most important thing." The agent's home ns is
`my.agent.vFA-2606261800` (transcript `; in my.agent.vFA-2606261800` L2290; readline
L2314). It does **not** appear among the rendered ns blocks (the headers present are
`my.kb`, `my.kb.shared`, `my.workout`, `seon.agent.todo`, `seon.db`, `warntest.dom`,
`warntest.ent`, `my.book`, `my.contact`). Cause: the home ns has no defined
fns/schemas yet, so `render-one` (`ctx/namespaces.cljs` L216–219) omits empty full
nses. Effect: the prompt loudly tells the agent "your workspace is shown below, it's
the most important thing" and then doesn't show it. **Fix:** for the agent's CURRENT
ns specifically, render at least a one-line presence stub (e.g. `; namespace
my.agent.X — your workspace (empty; define here)`) so the promise is kept, OR soften
the header so it doesn't claim a workspace that isn't there. The first is better — the
agent should always see where "here" is.

### GI-3 — System message renders cleanly (CLEAN)

The system role (prompt L1–263) is byte-identical to `system-text` source and to the
standalone `tmp/system-message-review-2026-06-26.txt` (15,453 vs 15,452 chars; the
1-char delta is the trailing newline). No truncation, no malformed escapes, ordering
intact. The system-message-level problems below are CONTENT problems, not render bugs.

### GI-4 — Failed-eval shown in two sections (LOW, defensible)

The backtick parse failure appears both in `:warnings` (L2261–2265, aggregated
"failed-evals since last user message") and inline in `:transcript` (L2304–2305, the
`;=>` READ ERROR on the form). This is arguably fine — the warning is the cross-agent
summary, the transcript is the inline detail — but the warning re-states "Errors are
values — (result <eval-id>) holds the full error data," which the system message
already teaches (overlap O-5). Keep the warning as a pointer; don't re-teach the
concept in it.

### GI-5 — Readline shows two unexplained turn counters (MEDIUM)

The readline (L2314) reads `… · turn 3 · loop 2/20 · running · …`. The system message
only ever mentions "loop K/cap" (L1137). The agent is shown both `turn 3` and `loop
2/20` with no explanation of the difference between an absolute turn count and the
loop's K/cap sliding window. Either label them or drop one. (Ties to §7a.)

### GI-6 — `[schema …]` member-row format diverges from real source syntax (LOW)

Member rows render as `[schema :my.kb/confidence] [:enum :verified :inferred]` —
a bracket-pair pseudo-syntax that is neither a real form nor a `;`-comment. In an
"everything is eval'able Clojure or a `;` comment" context this is a third syntax the
agent must decode. If GI-1's fix removes member rows under full source, this only
remains in `:signature` views; consider rendering those as commented signatures
instead of bare bracket pairs.

---

## 1. SECTION-BY-SECTION AUDIT

### 1.1 system (role) — 15,453 chars / ~3,863 tok

The full claim inventory (59 claims) and within-section repetition report for the
system message are in §3–§4 (kept intact from the original system-text audit). Summary
here: the section is internally coherent and renders cleanly, but (a) repeats the
"write form → read `;=>` value → report the real value" cycle 4× internally, (b)
carries a DB cheat-sheet that duplicates the db source dumped in `:namespaces` and the
forthcoming `seon.db.examples`, (c) carries the render-twins theory block that belongs
with `:live-tile`, and (d) re-teaches things the live sections below already SHOW
(stored data, todos, the transcript loop). Verdict: keep as the home for *concepts and
mechanics*, strip everything that a live section demonstrates, target ~10.3k chars.

### 1.2 `:namespaces` — 91,653 chars / ~22,913 tok (THE BLOAT)

**What it is:** real loaded code, full source for `my.*`, the current ns, third-party,
and a 2-ns whitelist (`seon.db`, `seon.agent.todo`); everything else dropped (indexed
and searchable). Header (L268–274) restates the system message's namespaces blurb.

**Claim inventory of the header (L268–274):** (i) shown in full = your ns + db + todo
as examples; (ii) the rest is not dumped, indexed + one search away; (iii) ordered by
recency. All three duplicate system-msg L150–161 (overlap O-2).

**Cut/keep:**
- **MOVE `seon.db` source out (−70,003 chars).** See §6. This is the entire ballgame.
- **Fix GI-1** (drop redundant member rows under full source): −9.7k in db alone (moot
  if db source leaves) and ~2–3k across remaining full nses.
- **Fix GI-2** (render the empty home ns as a stub).
- **Keep** the recency ordering, the my.* full renders, the curated-drop model — these
  are sound. Keep the header but let it own the "shown-in-full / aliased / recency"
  facts so the system message can drop them (O-2).

**Render-integrity:** GI-1, GI-2, GI-6 all live here. The `warntest.*` nses (L2198–
2211) are test-fixture noise leaking into a real agent prompt — confirm they should be
indexed/rendered at all in a non-test cluster (possible stale data; flag, don't
assume).

### 1.3 `:your-entity` — 759 chars / ~189 tok

**Claim inventory:** (i) this is your own entity, re-pulled every turn; (ii) the pull
form that produced it; (iii) transact-by-lookup-ref to change it, with the exact form;
(iv) "write notes and standing instructions to yourself here; this map IS you"; (v)
your purpose is UNSET — derive it and transact it; (vi) the entity map itself
(`{:db/id 1582, :seon.agent/id "vFA-…"}`).

**Cut/keep:** strong, lean section — KEEP as-is. It SHOWS the lookup-ref transact live,
so the system message should NOT also teach "you can transact onto your entity" — it
should just NAME that your entity renders here (overlap O-4). The "this map IS you /
write self-notes here" instruction is the right home for self-note guidance; system
message's "write notes to yourself" in BUILD-YOUR-ENVIRONMENT can defer to it.

**Render-integrity:** clean.

### 1.4 `:live-tile` — 1,104 chars / ~276 tok

**Claim inventory:** (i) this is your live tile = what your human currently sees;
(ii) the wired fn (`seon.render.live-canvas/welcome`, the core default); (iii) the
rendered welcome-card text the human sees now; (iv) how to change it — redefine the
wired fn, or transact `:seon.render.live-canvas/content` (a qualified fn symbol or
literal hiccup) onto your entity.

**Cut/keep:** KEEP — this is the live SHOW of the tile mechanism and the correct home
for tile mechanics. It makes the system-message render-twins block (L92–98) redundant
(overlap O-3): move whatever the agent needs about `:seon.render/ai`/`:html` here, not
the system role.

**GAP (NEW, §7b/§7c):** the section explains what the tile IS and how to change it, but
does NOT instruct the agent to USE it — to narrate multi-turn work, post progress, show
status. (Ironically the default card text even promises "I'll update this panel as I
work" — a promise nothing tells the agent to keep.) No mention of progress bars from
todos. Add the *instruction* to system-msg (§7), keep the *rendering* here.

**Render-integrity:** clean.

### 1.5 `:warnings` — 503 chars / ~125 tok

**Claim inventory:** one reactive failed-evals warning: errors are values, `(result
<eval-id>)` holds the full data, the fix form, the affected eval id + the actual parse
error. Reactive — clears when fixed.

**Cut/keep:** KEEP the mechanism (excellent reactive design). Trim the re-teaching of
"errors are values" to a pointer (the system message owns that concept — O-5). See
GI-4.

**Render-integrity:** functions correctly; the warning fired truthfully (the agent did
emit a bad form). Note the warning text itself spans a raw newline mid-sentence
(L2264–2265) from the embedded error string — readable but slightly ragged.

### 1.6 `:inventory` — 571 chars / ~142 tok

**Claim inventory:** (i) header: stored data the cluster holds NOW, one line per KIND
with attr names + live counts + «sample values», samples are illustrative not answers,
"Consult BEFORE researching or registering"; (ii) the two live lines (`my.book`,
`my.contact`).

**Cut/keep:** KEEP — lean, reactive, exactly the "discoverable not dumped" surface. It
IS the live `store-inventory`. So the system message must STOP re-teaching
`store-inventory` four times (overlap O-6 / §3 R6) and instead point here once.

**Render-integrity:** clean. Note it shows only post-bootstrap user data (correct);
the `{:seon.db/system? true}` superset is on demand.

### 1.7 `:transcript` — 2,471 chars / ~617 tok

**Claim inventory:** (i) masthead: this is your live REPL backed by the DB, always
current, re-derives every turn, flat time-ordered event log oldest-first, **you write
forms + `;` comments, the runtime shows the value next line as `;=>`, that's how
results arrive on the turn after — just write the form, read its `;=>` next turn**;
(ii) the actual event history (messages ◀/▶, evals, `;=>` values, `result/<id>`
handles); (iii) the readline masthead (`turn 3 · loop 2/20 · running · ts · agent id`)
and the `my.agent.X=>` cursor.

**Cut/keep:** KEEP the live history and readline — this is the steering surface. But
the masthead (L2280–2288) **re-explains the entire eval loop a third time** (overlap
O-1, the single largest non-db redundancy). The transcript SHOWS the loop; the masthead
should be a one-line orientation ("your live REPL; append below"), not a re-teach of
`;=>` timing. Move the loop explanation to ONE home (system message §2 of the proposed
outline) and let the transcript demonstrate it.

**Render-integrity:** the readline two-counter ambiguity (GI-5). Otherwise the
transcript faithfully shows the backtick failure and recovery — good live data.

### 1.8 Absent sections (noted, not audited)

`:soul`, `:shared-instructions`, `:open-todos`, `:relevant-source` are all correctly
ABSENT this turn (lean config / empty singleton / completed todos / env-gated off).
Their reactive vanish-when-empty behavior is working as designed. Note for the
cross-section work: when `:open-todos` IS present, it will overlap the system message's
todo teaching (O-7).

---

## 2. CROSS-SECTION OVERLAP REPORT (highest-value)

The whole prompt — across all sections — should say each thing once. Rule of thumb:
**let the live section SHOW it; have the system message only NAME the concept.**

| # | Concept | Stated in | Should live ONCE in |
|---|---|---|---|
| O-1 | The eval loop (write form → `;=>` value next turn → act on real value) | system msg L18–29, L31–42, L143–148 **+ transcript masthead L2280–2288** | System msg, ONE "The loop" section. Transcript masthead → one orientation line; it SHOWS the loop. |
| O-2 | Namespaces "shown in full / db+todo aliased / recency" | system msg L150–161 **+ namespaces header L268–274** | Namespaces header (it sits on the thing). System msg keeps only "discover, don't hallucinate, here's how (grep/render-namespace)." |
| O-3 | Render twins / tile mechanics (`:ai`+`:html`, how to set the tile) | system msg L92–98 **+ `:live-tile` L2247–2256** | `:live-tile` section. CUT the system-msg theory block (§5). |
| O-4 | "Transact onto your entity / ids are live handles / self-notes" | system msg L100–107, L163–174 **+ `:your-entity` L2231–2244** | `:your-entity` SHOWS it. System msg names "your entity renders below; transact to it by lookup ref" once. |
| O-5 | Errors are values | system msg L85–90 **+ `:warnings` L2261 + transcript `;=>` errors + db cheat-sheet L118–119** | System msg "The shared database" section. `:warnings` and the envelope cheat-sheet reference it, don't re-teach. |
| O-6 | "Consult stored data first / `store-inventory`" | system msg L109, L155–159, L177–187 (×2), L235–243 **+ `:inventory` section IS the data + `my.kb` ns doc L311–315** | System msg once ("your stored data renders in `:inventory`; consult it first"). `:inventory` SHOWS it; `my.kb` doc shows the consult idiom. |
| O-7 | Todo discipline (one per step, ✉ on inbound, complete! as you land) | system msg L197–205 **+ `seon.agent.todo` source dumped in `:namespaces` L487–518 + `:open-todos` section when present** | The `seon.agent.todo` source (whitelisted full) is the exemplar; `:open-todos` shows live state. System msg keeps a 2-line "minted ✉ todo on human msg; one per step" pointer, not the full arc. |
| O-8 | DB query/pull/transact usage (cheat sheet) | system msg L113–148 **+ `seon.db` source dump L719–2197 + (new) `seon.db.examples`** | `seon.db.examples` (tested copyable). System msg keeps only the 2 laws + envelope concept. (§5/§6.) |
| O-9 | "Messages render as markdown to the human" | system msg L58–59 **+ system msg L206–208** (within-section, R5) | One line in "Think in comments." |
| O-10 | The transcript is a replayable eval'able session | system msg L18–29 **+ transcript masthead L2281–2288** | System msg once; transcript IS the session. |

The pattern: the system message currently both NAMES and re-EXPLAINS things that the
six live sections beneath it demonstrate in situ. Every O-row above is a place where
the live section is the better teacher because it shows real, current data.

---

## 3. WITHIN-SYSTEM-MESSAGE REPETITION (the system role, internal)

These are repetitions *inside* the system role (independent of the cross-section ones).

- **R1 — the forms/values loop, 4× internally** (L18–29, L31–42, L61–65, L143–148).
  Collapse to ONE "The loop" section. Largest internal cut (~600 chars). Folds with O-1.
- **R2 — errors-are-values, 3×** (L85–90, L118–119, L231–234). Home: "The shared
  database" section; keep "confirm `ok? true`" once. Folds with O-5.
- **R3 — namespaces blurb vs header** (L150–161 ↔ header L268–274). Header wins. O-2.
- **R4 — "current ns renders full / namespaces are workspaces", 2×** (L150–152,
  L168–170). Home: BUILD-YOUR-ENVIRONMENT.
- **R5 — "messages render as markdown", 2×** (L58–59, L206–208). Home: "Think in
  comments." O-9.
- **R6 — `store-inventory`, 4–5×** (L109/113, L155–159, L177–187 ×2, L235–243). Home:
  the knowledge section, once. Folds with O-6.
- **R7 — `result/<id>` reuse forward-ref** (L28–29 → L67–77). Place RESULT VARS right
  after the loop; the "(below)" pointer becomes adjacent.
- **R8 — "discover, never hallucinate" ↔ "consult stored knowledge first"** (L155 +
  L155–161 vs L177–187). Two faces of one idea; split how-to-find-code (grep) from
  how-to-find-facts (inventory), cross-reference rather than re-argue.

---

## 4. LOW-VALUE CUT LIST (system message)

- **Render-twins block (L92–98) — CUT** (§5). ~430 chars; move to `:live-tile`.
- **DB cheat sheet (L113–148) — MOVE to `seon.db.examples`** (§6). ~1,900 chars out of
  the role; it's triply redundant (db source dumped below + the new examples ns).
- **WRITE FORMS; READ VALUES teaching (L143–148) — DELETE**, folded into the loop
  section (R1/O-1). ~430 chars.
- **REPORT THE VALUE block (L61–65) — COMPRESS** into the loop section as one clause.
  ~250 chars.
- **Messages-render-as-markdown teaching (L206–208) — DELETE** (R5/O-9). ~150 chars.
- **Confirm-`ok?` bullet (L231–234) — DELETE standalone** (R2/O-5), keep one clause.
  ~200 chars.
- **Namespaces blurb (L150–161) — COMPRESS ~60%** (R3/O-2); keep discover + how.
  ~500 chars.
- **Messaging verb expansion (L216–230) — COMPRESS** to verb list + tight steering
  bullets. ~400 chars.
- **KEEP** the Correct/Wrong example (L44–49, high-value, move into the loop section)
  and STATE-ACROSS-TURNS (L79–83, the def-vs-atom gotcha is real and non-obvious).

Net system-message reduction ~4,700–5,100 chars before prose tightening → ~10.3k chars.

---

## 5. THE RENDER-TWINS BLOCK — diagnose + fix

Block (L92–98): "render twins `:seon.render/ai` (text for you) + `:seon.render/html`
(hiccup for their screen) … a *section* can carry an `:seon.render/html` twin …".

**Why it's confusing / low-value at boot:**
1. **Assumes vocabulary not yet established** — "tile", "section", "hiccup",
   `:seon.render/ai`/`:html` are all introduced for the first time here, before the
   agent has seen any of them.
2. **It's pipeline theory, not an action** — "one render, two surfaces … one section
   row serving both" describes how the system is built, with no verb for the agent.
3. **It's on-demand knowledge** — that a section can carry an HTML panel matters only
   when the agent builds a rich panel, not at boot.
4. **The live section already teaches it better** — `:live-tile` (L2247–2256) SHOWS
   the tile and gives the actionable move ("transact `:seon.render.live-canvas/content`
   — a qualified fn symbol or literal hiccup"). The hiccup-splice tip
   (system-msg L249–252) belongs with it.

**Fix: CUT from the system role; relocate the mechanic to `:live-tile`** (and a worked
hiccup example to `seon.db.examples`/a render example). If a residual pointer is
wanted, one plain line (no fences/backticks) suffices: *Your human sees a separate
visual render of this same data; build a tile or panel only when a task calls for it —
the live-tile section shows how.*

---

## 6. MOVE-TO-EXAMPLES (db source → `seon.db.examples`) — THE lever, with token math

**Problem:** `seon.db`'s source dump is 70,003 chars / ~17,500 tok — 62% of the whole
prompt — and the db cheat-sheet in the system message (L113–148) plus the (forthcoming)
`seon.db.examples` make it triply redundant for the agent's actual needs. The agent
needs *how to query/pull/transact*, not `seon.db`'s 1,295-line implementation.

**Move:** create `seon.db.examples` (tested, copyable, whitelisted full-render) holding
the worked ops the cheat-sheet currently inlines (ADD/register, UPSERT, REMOVE/retract,
the three `:find` shapes + `:in`, PULL/ENTITY, WILDCARD+components) plus a few idioms
(lookup-ref, ref-join, errors-as-values branch). Drop `seon.db` from the full-source
whitelist → it stays INDEXED and grep-able (via `seon.agent.search`) and readable on
demand (`render-namespace`), just not dumped. The system message keeps only the two
laws (register-before-transact, ≥2-segment namespaces) + the envelope concept, and
points at `seon.db.examples`.

**Token math:**

| Change | chars | running total |
|---|---:|---:|
| current prompt | — | 112,765 (~28,191 tok) |
| − `seon.db` source dump | −70,003 | 42,762 |
| + `seon.db.examples` (~7,000, whitelisted full) | +7,000 | 49,762 |
| − system-msg DB cheat sheet (moves to examples) | −1,900 | 47,862 |
| − system-msg reorg (R1/R2/R5 dedup + render-twins) | −3,250 | 44,612 |
| **result** | | **~44,612 chars / ~11,150 tok (−60%)** |

If `seon.agent.todo` source is ALSO curated out (keep the live `:open-todos` + a short
exemplar in examples) that's a further −10,669 chars → ~34k chars / **~8.5k tok
(−70%)**. Fixing GI-1 (no member-row double-render under full source) trims another
~2–3k from the remaining full nses. **The single db-source move alone (−56%) is worth
more than every other recommendation in this document combined.**

**Honest nuance:** the `:namespaces` body is inside the byte-stable cache prefix
(L2229), so with prompt caching the 70k isn't re-billed at full price every turn. But
it still (a) pressures the context window and degrades comprehension ("distance not
volume" — the agent must wade past 70k of db internals to reach its own data), (b)
costs cache writes and is fragile (any higher-priority edit busts the prefix), and
(c) isn't cached on every provider. The move wins on all three regardless.

---

## 7. CONTENT THE SYSTEM MESSAGE MUST COVER (NEW vs current)

### 7a. Turn position + sliding window — PARTIALLY PRESENT, needs prominence

- **Current:** the sliding-window rule IS explained (system-msg L1134–1138 / claim 58),
  but as the second-to-last bullet in a long standing-teachings list. The live position
  is in the readline (`turn 3 · loop 2/20`, L2314).
- **Gap:** (1) buried — turn economy is load-bearing and should be its own short
  section, not a tail bullet; (2) the readline shows TWO counters (`turn 3` AND `loop
  2/20`) with no explanation of the difference (GI-5) — the system message only ever
  names "loop K/cap."
- **Recommend:** a dedicated "Turns are precious" section stating the rule once (base
  cap, +1 turn per inbound message, wrap up near the cap); and either label or drop the
  redundant `turn N` counter on the readline so `loop K/cap` is unambiguous. (Readline
  format = transcript/UI lane; the rule = system message.)

### 7b. Use the live tile to communicate + narrate multi-turn work — NEW (absent)

- **Current:** nothing instructs the agent to USE the tile. `:live-tile` explains what
  it is and how to set it; the default card even says "I'll update this panel as I
  work" — but no instruction tells the agent to do so. The agent only ever uses
  `message/user`.
- **Recommend (NEW, system message):** one short instruction — communicate with your
  human through TWO surfaces: `message/user` for replies/answers, and your live tile
  for status and for narrating work that spans turns or takes time (what you're doing
  now, where you are). The tile is always visible; use it to show progress without
  spamming messages. (The *rendering* stays the `:live-tile`/UI lane's job; the system
  message teaches the instinct to reach for it.)

### 7c. Progress bars from itemized lists — NEW (absent), cross-lane

- **Current:** nothing. Todos render as a list; nothing suggests surfacing completion
  visually.
- **Recommend (NEW, cross-lane):** when you hold an itemized list (e.g. todos), surface
  progress on your tile as a bar derived from completion (done/total) so your human
  sees momentum at a glance. The UI lane provides/derives the bar component; the system
  message teaches the agent to reach for it. Pair it with 7b (the tile is where
  multi-step progress lives).

---

## 8. PROPOSED CONSOLIDATED OUTLINE (whole prompt, after de-duplication)

### 8a. System message — proposed sections (render order; `##` headings render as

markdown in `;`-prose; NO fences/backticks in the prose, per conventions §"Comment
levels")

1. **You are a live ClojureScript REPL** — identity + env (CLJS/Node, full `js/`, no
   JVM). (current L1–8, unchanged)
2. **The loop — write forms, read values next turn** — the ONE eval-loop home: forms
   run on a new-line `(`, bare literals are notes, `;=>` lands next turn, act on the
   real value (incl. report-the-real-number), STOP after the last form, the
   Correct/Wrong example. Folds R1/O-1/O-10. The transcript masthead drops to one line.
3. **Reusing values — result/<id>** — RESULT VARS + clipped-display + opaque
   placeholders. (R7)
4. **Think in comments** — `;`-narration, no loose backticks/fences (raise prominence —
   it failed live, GI/L2300), markdown-OK-in-message-strings. (R5/O-9)
5. **State across turns** — defn/atom persist, bare `def` doesn't survive read-back.
6. **The live context system** — re-derives every turn, reactive, other agents' writes,
   ids are live handles; NAME the live sections ("your entity, tile, stored data,
   warnings, and transcript render below"). (absorbs O-4/O-6 naming)
7. **The shared database** — one DB, `*conn*` ambient, the two laws, reads-sync /
   `transact!`-envelope / errors-are-values; pointer to `seon.db.examples` + db source
   on demand. Cheat-sheet body removed. (O-8/O-5/R2)
8. **Build your environment** — create ns / schema / colocate fns; `my.*`/`my.kb.*`/
   `seon.*`; write the tool you need; switching ns makes it your full-rendered
   workspace; self-notes go on your entity. (R4, defers self-note detail to `:your-
   entity`)
9. **Find what's not shown** — discover, don't hallucinate; grep + `render-namespace`
   for code, `:inventory`/`store-inventory` for stored data. (O-2/O-6/R6/R8)
10. **Store and grade what you learn** — consult-first, store-what-you-verify, grade
    with `:my.kb/source` + `:my.kb/confidence`.
11. **Communicate — messages and your tile** — `message/user` for answers + the live
    tile for status/narration of multi-turn work (NEW 7b); progress bars from todos
    (NEW 7c). Messaging verbs + lifecycle (`message/agent`, `wait`, `complete`,
    no-self-message, nothing-to-do → no forms, always wakeable), compressed.
12. **Todos — one per step** — mint before, complete as you land, ✉ on inbound; defer
    the full arc to the `seon.agent.todo` exemplar + `:open-todos`. (O-7)
13. **Turns are precious** — answer-if-present-else-query + the sliding-window cap,
    promoted to its own section. (NEW prominence 7a)

Removed from the system role: the render-twins theory (→ `:live-tile`), the DB cheat
sheet (→ `seon.db.examples`), and every re-teach of what a live section shows.

### 8b. Context sections — what each should contain after de-dup

- **`:namespaces`** — full source for `my.*` + current ns (render the empty home-ns
  stub, GI-2) + third-party; **db source REMOVED** (→ examples, indexed/searchable);
  **member-row double-render REMOVED under full source** (GI-1); header owns
  "shown-full/aliased/recency."
- **`seon.db.examples`** (new, whitelisted full) — the tested copyable DB ops + idioms.
- **`:your-entity`** — unchanged; sole home for "transact onto your entity / self-notes."
- **`:live-tile`** — unchanged + ADD the tile mechanics relocated from render-twins, and
  it's the rendering home for the NEW narrate/progress-bar instruction (system msg
  teaches the instinct, this renders it).
- **`:warnings`** — unchanged mechanism; trim "errors are values" re-teach to a pointer.
- **`:inventory`** — unchanged; it IS the stored-data surface the system message points
  at.
- **`:transcript`** — unchanged history; masthead → one orientation line (loop home
  moves to system msg); fix the two-counter readline ambiguity (GI-5).

---

## 9. VALUE CHECK

After these changes, everything the agent NEEDS survives exactly once, with the live
sections doing the teaching they're best at. The eval loop, comment discipline,
`result/<id>` reuse, the def-vs-atom gotcha, the reactive-context model, the DB laws +
errors-are-values, build-your-environment, discovery, knowledge capture, messaging +
lifecycle, and turn economy each land in one named system-message home; the DB ops,
tile mechanics, stored data, todos, and the transcript loop are SHOWN by the live
sections the system message merely names. The prompt drops from ~28.2k to ~11–12k
tokens (−56–60%), driven overwhelmingly by the db-source move, with the system-message
reorg a distant second.

**Flagged as uncertain (decide, don't assume):**
- **GI-2 fix** — confirm whether the empty home ns should render a stub vs. softening
  the header; I recommend the stub so "here" is always visible.
- **`warntest.*` nses** in a real prompt — likely stale test fixtures leaking in;
  verify they belong before treating their presence as normal.
- **`seon.agent.todo` source** — keep as the exemplar (it teaches the store/retrieve
  pattern well) or also curate out for the extra −10.7k? Depends on how much the live
  `:open-todos` + an examples-ns snippet can carry. I lean keep-but-trim.
- **The `:test`-example principle** — `seon.agent.todo` already demonstrates `:test`
  in situ; the system-message teaching may be redundant with the exemplar. One tight
  sentence or drop — owner's call.
- **SOUL.md overlap** — when SOUL returns, its "Growth"/"Build" prose duplicates
  system-msg "Build your environment." Decide which file owns "grow the runtime for
  them" before re-enabling SOUL.

No NEEDED claim is dropped; the only deletions are restatements and orphaned theory,
which relocate rather than vanish. The three NEW items (turn-position prominence, use-
the-tile-to-narrate, progress bars) are genuinely absent today and are the only net
ADDITIONS recommended.
