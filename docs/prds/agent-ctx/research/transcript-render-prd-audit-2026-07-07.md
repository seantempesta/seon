---
type: research
status: active
tags: [research, agent]
---

# Adversarial audit — `transcript-render-prd.md`

Ordered by severity. Each finding: the PRD claim → the code reality (file:line)
→ what it means. Verdict at the bottom.

## TL;DR

The PRD is a good *aesthetic* capture of the mockup session but is NOT ready to
build. Its keystone value story ("read full → decay → recall from blob") is a
**fiction for large values** — the full value lives in a volatile globalThis
stash + a 16 KB DB projection, never a durable blob unless the agent explicitly
`my.blob/put!`s it, and it is *gone after a restart or cache-prune*. Its grammar
(`⟹ value ⟸` / `⋘…⋙ ❯` as bare lines) **breaks the eval'able-transcript north
star** the same PRD claims to preserve, and reopens the per-turn-header +
byte-stability question the current code deliberately closed. It leans on several
**verbs that don't exist** (`my.pdf/*`, `fs/view :whitespace`, a value `section`
verb), and its "generated-from-config instructions" keystone is under-argued
against the cheaper, already-shipped "constant interpolated into a template"
pattern. Multiple motivating "drift" facts are already fixed in the tree.

---

## BLOCKING inconsistencies

### B1 — "recall from the blob" is a fiction for large values (owner's suspicion: CONFIRMED)

- **PRD claim:** "The full value lives in the **blob** (out of the token budget)"
  (line 99); "Recall anytime via the handle … re-expanded from the blob" (110);
  "nothing is lost (the blob holds it)" (112). Implementation step 2: "Value =
  blob-backed projection (recent full → aged reference)."
- **Code reality:**
  - `:seon.eval/result-edn` is capped at **16384 chars at write** —
    `store-edn-cap` (`config/system.edn:52`, `src/seon/eval.cljs:2855-2871`). That
    docstring states the invariant plainly: the DB must never hold a multi-MB
    datom; "The FULL value remains available in-session as the live var
    `result/<id>` (globalThis live-result stash, `stash-result-raw!`) — that path
    is NOT capped."
  - The stash is **volatile globalThis** (`stash-result-raw!`,
    `src/seon/eval.cljs:1258-1271`), **pruned oldest past `result-vars-cap`**
    (`eval.cljs:977`), and **dies on restart** — `lookup-result` returns a
    "prior session … did not survive the process restart" miss
    (`eval.cljs:1317-1323`).
  - `my.blob` is **opt-in and durable but only on an explicit call** —
    `my.blob/put!` (`src/my/blob.cljs:205`). Nothing auto-promotes a read to a
    blob. The strongest proof is that `render-ai`'s own drill hint *teaches the
    agent to do it by hand*: `keep: (my.blob/put! result/<id>)`
    (`src/seon/render/value.cljs:504-514`).
- **What it means:** For a 42-page PDF or a big file, **only a 16 KB projection is
  persisted**; the whole value is in the volatile stash. After decay or a restart,
  re-referencing `result/<id>` returns the "prior session" miss, **not** the full
  value. The PRD's central gradient ("read full → decay → recall") only holds
  within one process, within the stash cap — exactly the case where you *don't*
  need recall. The PRD never states that (a) the agent must explicitly promote a
  value to survive, (b) decay+restart loses the stash, or (c) auto-blobbing every
  read is a new write-path/GC/cost decision (blobs are append-only, no GC —
  `blob.cljs:13`). This is the single load-bearing decision and it is **absent**.

### B2 — the `⟹`/`⋘` grammar breaks the eval'able-transcript north star it also claims

- **PRD claim:** results must look **"alien"** — "A result must look *alien* to
  what an agent types" (line 32); grammar shows **bare** lines `⟹ <value> ⟸
  result/<id>` (70, 79) and `⋘ … ⋙ ❯` (74); rule: "the agent NEVER writes a line
  starting with a reserved glyph. Every line it authors is a `(form)` or a `;`
  comment" (83-85) — i.e. the *runtime* writes bare glyph-led lines.
- **Code reality:** the runtime today writes a **commented** result line: `; ⟹
  <value> ; result/<id>` (`format-eval-row`, `src/seon/agent/ctx.cljs:887-893`),
  and the leading `;` is **load-bearing**: "`; ⟹` is a COMMENT — the value is
  runtime history, never a form to re-run … re-evaluating the whole transcript
  runs ONLY the forms" (`ctx.cljs:755-757`; transcript ns docstring
  `src/seon/agent/ctx/transcript.cljs:15-18`: "Re-evaluating the forms … reproduces
  the agent's state — the context IS a replayable program (the north star)").
- **What it means:** A **bare** `⟹`-led or `⋘`-led line is **not valid Clojure** —
  re-evaluating the transcript throws on the first one, killing the replayable-
  program north star. The PRD wants BOTH "results look alien (not comment-shaped)"
  AND "context is eval'able Clojure" (it implies the latter with "every line it
  authors is a `(form)` or a `;` comment"). **These are in direct tension and the
  PRD never reconciles them.** Note also that the alien-glyph anti-fabrication is
  *already shipped*: `⟹` is a reserved constant that the neutralizer rewrites in
  agent text (`reserved-glyph-re`, `ctx.cljs:665-675`). What remains "comment-
  shaped" is the deliberate leading `;`. The PRD conflates "the marker drifted"
  (A8 fixed) with "it looks like a comment" (true, on purpose, for eval'ability).

### B3 — the per-turn status line contradicts the byte-stability law in the same breath

- **PRD claim:** "One `⋘…⋙ ❯` line **per turn** carries date · turn · **context
  tokens** · current ns" (line 89) — immediately followed by "**No per-turn
  container headers** (aged events must render byte-identically — the #62 byte-
  stability law)" (90).
- **Code reality:** the current transcript has **no per-turn headers by design**
  ("Turn boundaries are NOT containers — there are no per-turn headers",
  `transcript.cljs:10-11`, `696-708`). The single live `now` is the bottom
  readline, "the ONLY line in the whole transcript that reads the live `now`
  (below the cache breakpoint — busting here is free)" (`transcript.cljs:523-524`).
  There is **no stored, byte-stable per-turn context-token count**.
- **What it means:** A `⋘ turn N · <ctx-tok> ⋙` line embedded per-turn in the body
  **is** a per-turn header (contradicting the very next clause) AND its "context
  tokens" is a live/derived quantity — embedding it in aged body text **busts the
  prompt-cache prefix every turn**, the exact failure #62 forbids. The PRD does not
  say whether the timestamp/tokens are the turn's *fixed* stored values or live.
  Unresolvable as written.

---

## REAL gaps

### G1 — the HTML "interactive twin" is a whole unbuilt feature, smuggled in as "same walker"

- **PRD claim:** "`:seon.render/html` … interactive, expandable, copyable …
  expands handles to `/value`" (167-168); "No second render path (the seon
  one-walker invariant …)" (172); step 5 "The interactive expand/copy/drill
  surface wired to real handles (stash for session, blob for durable)."
- **Code reality:** `render-html-data` returns a **static DATA CONTRACT** (the
  `sample` skeleton), NOT an interactive expand-to-full value
  (`src/seon/render/value.cljs:572-595`). The ns comment is explicit that
  expansion is "a fresh server `/call` — see the U coordination ask in the PRD
  note" (`value.cljs:561-570`) — i.e. the live value-fetch-by-handle endpoint + UI
  is owned by another lane and **unbuilt** (no such endpoint in `src/seon/web/*.cljs`).
- **What it means:** "Drill to full value" needs a live endpoint reading the
  volatile stash (works only this process) **or** the value having been blobbed
  (opt-in, see B1). Neither exists. Framing it as a "small addition / same walker"
  is dishonest scoping — it is a cross-lane feature (endpoint + UI + the B1
  durability question) and is not sequenced against lane U.

### G2 — verbs the PRD leans on don't exist

- `my.pdf/read` / `my.pdf/section` (implied by "a 42-page PDF" recall story):
  **do not exist** — no `src/my/pdf*`, no pdf verb anywhere. FICTION.
- `fs/view :whitespace :visible|:raw` (lines 121-128): **not a real option** —
  `:seon.agent.fs/view-request` has only `path`/`from-line`/`max-lines`/`encoding`
  (`src/seon/agent/fs.cljs:220-225`). It is a *proposed new knob*, presented mixed
  in with real ones.
- a value "`section`"/"recall" verb (110, 128): **no such verb** — "recall" in the
  tree is SEON_EMBED semantic recall or the plan resume-tail, and `fs/view` reads
  files on disk, not a result value. "Recall via the handle" is only re-reference,
  and only while the stash is live (B1).
- `#code/text` (implied): **VALID** — `::lang` is an open keyword, unknown langs
  round-trip un-highlighted (`src/seon/code.cljc:12-14`). This one is fine.

### G3 — "decays via the existing schedule to a `«shape» ⟨N tok⟩ ⟸ handle` reference" is not what the schedule produces

- **PRD claim:** "the display decays (the existing age schedule) to a single-line
  `«shape» ⟨N tok⟩ ⟸ result/<id>` reference" (106-108).
- **Code reality:** the active decay is `::result-decay` (default 3 levels
  16384→1500→200, `transcript.cljs:82-85`; wired via `decay-cap-for-offset`,
  `transcript.cljs:766-778`). It produces a **char-clipped prefix + a
  `result/<id>` pointer** (`clip-result-body`/`cap-result-body`,
  `eval.cljs:2939-2966`), NOT a `«shape» ⟨N tok⟩` structural reference. The
  *tier-eviction* windowing that could remove old evals entirely is **INERT by
  default** — `::tiers` defaults to `[]` and `clip-events-by-tiers` renders ALL
  events when empty (`transcript.cljs:74, 166-167`).
- **What it means:** "the existing age schedule" does the *char-clip* half but not
  the *shape-reference* form the PRD draws, and the eviction half is off. The PRD
  presents a new rendering as already-existing behavior.

---

## UNDERSPECIFIED

### U1 — "config-generated instructions" (the keystone) is under-argued and likely over-engineered

- **PRD claim:** the always-on teaching is **generated** from `:seon.config/render`
  via a `render-grammar-instructions` fn, and "Drift is impossible" (134-147);
  supersedes A9's hand-migration "now generated" (147).
- **Code reality:** the *already-shipped* anti-drift pattern is a **named constant
  referenced everywhere** — `result-marker` is referenced by the emit site, the
  neutralizer, and system-text's teaching, "never a bare literal, so the marker is
  one swappable edit" (`ctx.cljs:616-628`). The A9 migration doc
  (`research/instruction-surface-migration-2026-07-06.md`) already does exactly
  this: interpolate the constant into a hand-written template.
- **What it means:** "one swappable edit, no drift" is **already achieved** by
  interpolating constants into a template — you do not need to *generate prose from
  a config map*. Turning knobs like `:content-layout :structured` or a decay
  schedule into "token-lean, correct agent prose" is a real NLG problem; the PRD
  shows no example output and never weighs it against the cheaper template. The
  keystone is the least-grounded claim. Recommend: hand-written template with
  constants interpolated (A9's approach), not a prose generator.

### U2 — extending the neutralizer to `❯` risks false positives; glyph-in-value is unaddressed

- **PRD claim:** "The neutralizer keys on the reserved-glyph constants (any `⋘ ⋙ ❯
  ⟹ ⟸` in agent narration → `[unverified narration]`)" (180-181).
- **Code reality:** `reserved-glyph-re` keys on `⟹` only and runs ONLY on
  `:seon.eval/narration` + `:seon.eval/source` via a provenance gate
  (`ctx.cljs:665-710`).
- **What it means:** `❯` is a common shell-prompt glyph an agent may legitimately
  paste (a pasted shell transcript, or prose *about* the prompt) → false neutralize.
  Separately, a *value* that legitimately contains `⟹`/`⟸` (e.g. reading this very
  PRD) renders on the `; ⟹` line and muddies "only the runtime writes ⟹" for the
  html/value round-trip; the PRD doesn't address reserved glyphs appearing inside
  values.

### U3 — the implementation plan is not actionable at its load-bearing step

- Step 2 ("Value = blob-backed projection") depends entirely on the B1 decision
  (auto-blob every read? keep opt-in?) which is **never made**. An implementing
  agent would guess — either auto-blob everything (expensive, no GC) or opt-in
  (recall-after-restart silently fails). Step 5 depends on G1's unbuilt cross-lane
  endpoint. Step 3 (the keystone generator) specifies only "add knobs" for its
  inputs and never the generated content. The plan is an ordered *wish list*, not a
  buildable sequence.

---

## NITS / stale facts

- **N1 — the motivating drift is already fixed.** The PRD's "Why now" cites the
  cite-card emitting `=>` at `transcript.cljs:642` (lines 24-27). The current
  `cite-line` uses `ctx/result-marker` (⟹), not `=>`
  (`transcript.cljs:631-646`). That specific drift is closed; the urgency framing
  is out of date.
- **N2 — "supersedes A9" is imprecise.** A9 is a broad REPL-concentration +
  system-text restructure pass (migration doc), of which the marker rule is one
  sliver. Claiming to supersede A9 wholesale risks an implementer dropping A9's
  other content (async rule, skill back-pointers). Say "supersedes A9's *marker-
  rule* migration."
- **N3 — `:inline-cap` vs "recent large values render full" (117, 232)** is a clear
  rule only if "recent" is crisply defined (offset 0-1 per the decay default). The
  PRD leaves "when exactly does a value get its own line(s) vs stay inline"
  (probe #2) to the reader — implementable, but currently a taste call, not a rule.

---

## VERDICT

**Not-yet.** The scope is **insufficient** and the plan is **not fully thought
through**. Two of the three headline ideas rest on things the code does not do:
the blob-recall gradient (B1) and the alien-bare-glyph grammar (B2), and the
status line reopens a law the code closed (B3). Before an agent builds this, the
owner must resolve, concretely:

1. **B1 — the durability decision.** Does a read auto-promote to a durable blob
   (new write path, GC/cost policy) or stay opt-in (then the PRD must drop
   "recall from the blob" and say recall is session-only)? Everything downstream
   depends on this one answer.
2. **B2 — comment-shaped vs alien.** Pick one: keep `; ⟹` (eval'able transcript,
   accept comment-shape, lean on the already-shipped glyph-reservation for anti-
   fabrication) OR go bare-glyph (abandon the replayable-program north star,
   explicitly). You cannot have both.
3. **B3 — the status line.** Either it is the single bottom readline (fixed +
   live-`now` only, as today) or it is per-turn — in which case every field must
   be the turn's *fixed stored* value and "context tokens" cannot appear (no
   byte-stable source). Decide.
4. **G1/G2 — scope honesty.** Cut or explicitly cross-lane-sequence the html twin;
   stop citing `my.pdf/*`, `fs/view :whitespace`, and a value `section` verb as
   though they exist — each is a new build.
5. **U1 — the keystone.** Confirm whether "generated instructions" means the
   already-shipped constant-into-template interpolation (cheap, sufficient) or an
   actual config→prose generator (unproven, likely over-engineered). Show one line
   of generated output before committing to the latter.

The *consolidation* half of the PRD (route the cite-card + `format-eval-row` +
value render through one path referencing the glyph constants; a config-round-trip
test) is sound, already partly done, and worth keeping. The *decaying-blob* and
*bare-glyph* halves need the owner rulings above first.

## Re-audit (rewrite)

Go/no-go confirmation pass on the rewritten `transcript-render-prd.md` against
the live code (2026-07-07). The three prior blocking holes were owner-ruled;
this pass confirms the rewrite reflects the rulings and is feasible, and hunts
for anything the rewrite newly broke.

### Prior blockers — CLOSED, and grounded in code

- **B1 (ephemeral REPL semantics).** Matches code exactly:
  `seon.eval/stash-result-raw!` (volatile globalThis stash),
  `lookup-result` (honest "prior session … did not survive" miss),
  `result-vars-cap` prune. The rewrite's "recall within-session, gone on
  restart, no auto-blob; `:seon.eval/result-edn` is debug-only" is a faithful
  description of the runtime. ✓
- **B2 (bare `⟹`, not `; ⟹`).** Feasible. `format-eval-row`
  (`ctx.cljs:880-893`) today emits `; ⟹ <value> ; result/<id>`; the rewrite's
  `(form) ⟹ <value> ⟸ result/<id>` is a bounded change (drop the leading `;`,
  add a `result-close` constant for `⟸`, inline the result on the form's line).
  Settled tradeoff (no longer eval'able) is stated. ✓
- **B3 (per-turn status uses FIXED turn values).** The load-bearing fact holds:
  **`:seon.agent.turn/prompt-chars` is a real stored per-turn datom**
  (`turn.cljs:77`, written at `:298` as `(count prompt-text)`). So a per-turn
  `⋘ … · <turn-ctx-tok> · … ⋙` masthead reads each turn's FIXED stored value
  (÷4 → tokens per the token-reporting rule) and is byte-stable when aged — the
  audit's "no byte-stable source" objection is resolved by this datom. The
  rewrite states the reintroduced marker is byte-stable *because* the values are
  the turn's, not live. ✓
- **HTML twin DEFERRED** cleanly; `render-html-data`'s static data-contract is
  left untouched. Anti-drift via constants-into-template (not a prose
  generator) — matches the shipped `result-marker` pattern (`ctx.cljs:616-628`).
  ✓

### Feasibility of the rewrite's new asks

- **Config knobs** (`:whitespace`, `:content-layout`, line-numbers, tabs,
  trailing-ws): `:seon.config/render` exists with the value-* caps
  (`config.cljs:103`, `system.edn:51`); the new knobs are purely additive and
  the aero default-value-transformer means `{}` reproduces today's bytes — so
  the defaults are safe and the "open knobs" are genuinely non-blocking. ✓
- **Constants for the result-grammar glyphs** (`⟸ ⋘ ⋙ ❯` beside `result-marker`
  /`result-close`): feasible; the emit sites are `format-eval-row`, `cite-line`
  (`transcript.cljs:645`, already on `ctx/result-marker`), the coalesced line,
  and the neutralizer — all reachable. ✓
- **Strip the literal `⟹` echoes:** grounded — **89** literal `⟹` today (PRD
  says ~82; stale but immaterial), concentrated in `my.kb*`, `my.data`,
  `my.blob`, `my.skills` docstrings and the skill `.md` files (13 in
  `seon-skills/datahike/SKILL.md`). Scope is concrete. ✓
- **Extend `seon.dev.docstring`:** it is a real, extensible lint harness
  (`check-source`/`check-file`, structural regexes, `hidden-ns?`/`test-ns?`
  skips). ✓

### NEW issues the rewrite introduced (must-fix — small, no redesign)

- **F1 — the `«»` grounding is factually wrong, and a naive lint would collide.**
  PRD (line 170, line 87) states the value-vocabulary `«…»` "shape" is a
  currently-hardcoded glyph in `render/value.cljs`. It is **not**: `render/value.cljs`
  has NO `«»` at all — its value vocab is `#‹…›` (opaque), `⟨N tokens⟩` (size),
  `‹partial view›` (hint), and `{…N keys}`/`[…N items]` (pruned). `«»` IS used,
  but in **`seon.agent.ctx.inventory`** (`«v v»` value lists, `:48/128/138`) and
  **`my.plan.internal`** (`«title»` plan anchors, `:452/456`) — unrelated
  surfaces. Consequences: (a) emitting `«shape» ⟨N tok⟩` at the decay small-cap
  is a **new render behavior**, not a sweep of an existing literal (step 2
  half-acknowledges this with "if it doesn't" — make it explicit); (b) the
  no-literal lint MUST be scoped to the **result-grammar reserves
  (`⟹ ⟸ ⋘ ⋙ ❯`)**, NOT the value-vocab glyphs `«» ⟨⟩ ‹›`, or it will flag the
  legitimate inventory/plan/value uses.
- **F2 — the docstring lint must be INVERTED, not merely "extended."**
  `seon.dev.docstring/wrong-echo-re` today **mandates** that a worked-example
  echo use `; ⟹` (flagging `;=>`/`;;=>`). The rewrite now forbids `⟹` literals
  in static text — the opposite polarity. Step 6 must explicitly remove/invert
  that require-`⟹` rule, else the lint self-contradicts (requires `⟹` while
  forbidding it). Also: the harness is **warn-only by design** (a blocking lint
  "would wedge the shared multi-agent tree", ns docstring `:29-30`) — keep the
  new rule warn-only unless the owner explicitly opts into blocking; the PRD's
  "cannot be committed / dev hook rejects it" (line 191) overstates the current
  contract.

### Not-broken (checked, clean)

Bare-`⟹` inline change, per-turn masthead byte-stability, neutralizer
narration-channel scoping (already provenance-gated to `:seon.eval/narration`
+ `:seon.eval/source`; the rewrite itself flags the `⋘/⋙`/`❯` false-positive
risk and scopes to narration — good), cite-card already on the constant, the
decay default `[0→16384, 2→1500, 5→200]` matching the PRD prose, and the "A9
marker-rule sliver only" precision fix. No new contradiction beyond F1/F2.

### Verdict

**READY** — buildable as written with defaults; the owner rulings are all
faithfully reflected and each load-bearing claim is grounded in a real datom /
fn. Apply the two small corrections (F1 the `«»` grounding + lint scope; F2 the
docstring-lint inversion + keep warn-only) during build — both are doc/scoping
fixes, neither reopens a settled ruling nor changes the plan's shape.
