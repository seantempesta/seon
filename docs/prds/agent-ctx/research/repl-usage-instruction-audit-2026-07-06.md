---
type: research
status: active
tags: [research, agent]
---

# REPL-usage instruction concentration — audit + design (2026-07-06)

Audit + design only. No `src/` edits. The owner's ask: the instructions that
teach an agent HOW TO USE the eval/REPL environment are "fragile context spread
all over the place," and we keep adding to the scatter. Concentrate them into
ONE known, programmatic, single-source home so a future update is one edit and
nothing drifts.

## TL;DR — the decision

- **There is ALREADY a de-facto single home for the doctrine:**
  `seon.agent.ctx/system-text` (ctx.cljs:1086-1429) — a byte-stable `def` string,
  the LLM `system`-role message via `seon.ai/effective-system-prompt`. Roughly
  **60% of it is REPL-usage mechanics** (eval rules, transcript-is-eval'able,
  `#code` heredoc, result vars, def persistence, errors-as-values, "you write
  forms not results"). The other ~40% is behavioral doctrine (messaging,
  lifecycle, planning, finishing, kb-storage). So the concentration target
  EXISTS — it just isn't *labeled into sections*, and a few REPL facts leak
  OUTSIDE it (the `/repl` skill, `format-eval-row` docstrings, per-verb echoes).
- **Recommendation: restructure `system-text` into explicitly-labeled
  subsections and pull the strays IN — do NOT build a new "repl-usage" block.**
  A new block would (a) duplicate the framing already in system-text, (b) cost a
  second byte-stable prefix segment, and (c) split the one authority in two —
  exactly the parallel-system trap. Keep ONE authority; make it *structured*.
- **Make it programmatic** by (i) referencing the glyph vocabulary as the named
  `def`s that already exist (`seon.agent.ctx/result-marker` = `⟹` etc. — the
  af08 unit is wiring these; this builds ON it), and (ii) keeping the `/repl`
  skill as the DEEP-DIVE (parinfer repair, `:read` errors) that the always-on
  block links to by name, not a parallel teaching.
- **Hairiness: 2.** It is a text-restructure + a handful of deletions + swapping
  three literal `;=>`/`⟹` glyphs for the named constant. Land it as a
  **tracked follow-up AFTER af08** (the ⟹-marker unit) merges — it depends on
  those constants existing, and it should NOT block the bench handoff.

---

## 1 — AUDIT: every agent-facing surface that teaches eval/REPL mechanics

"Agent-facing" = renders into a live pod agent's prompt. Four surfaces carry
REPL-usage instruction today.

### 1a — `seon.agent.ctx/system-text` (ctx.cljs:1086-1429) — THE always-on block

A byte-identical `def` (not a fn) emitted as the LLM `system` message. This is
almost certainly "the system entity block" the owner means, though note a
terminology subtlety: it is NOT a `:seon.agent.ctx/block` entity and is NOT
seed-copied — it is the *hardcoded mechanics*, deliberately separate from the
CONTEXT blocks (soul/AGENTS.md are `file-block`s; the shared-instructions and
skills-catalog ARE blocks). system-text is wired via
`seon.ai/effective-system-prompt`, not via `default-ctx-blocks`. Its own
docstring says "Usage teaching lives in the rendered namespace sources … never
here" — **this is already stale**: system-text is in fact where the bulk of
usage teaching lives.

Paragraph-by-paragraph classification (R = REPL-usage mechanics; B = behavioral
doctrine that legitimately stays; label = the subsection it becomes):

| Lines | Gist (first words) | Class | Subsection |
|---|---|---|---|
| 1099-1105 | "You are at a live Clojure REPL … ClojureScript in Node, no JVM" | **R** | WHERE-YOU-ARE |
| 1107-1113 | "THE LIVE CONTEXT SYSTEM … re-derives every turn" | R (framing) | LIVE-CONTEXT |
| 1115-1125 | "THE TRANSCRIPT IS ONE EVAL'ABLE REPL SESSION" | **R** | TRANSCRIPT-IS-EVAL'ABLE |
| 1127-1132 | "EVAL MECHANICS. A form RUNS only if it starts with `(`" | **R** | WHAT-RUNS |
| 1134-1144 | "After your LAST form, STOP … runtime writes the values" | **R** | FORMS-NOT-RESULTS (+ shape table) |
| 1146-1155 | "THINK IN COMMENTS … backtick/fence derail the eval" | **R** | WRITING-FORMS |
| 1157-1168 | "RAW FOREIGN CODE — #code HEREDOC" + anchored-edits | **R** | CODE-HEREDOC |
| 1170-1174 | "REPORT THE VALUE YOUR LAST EVAL RETURNED" | **R** | REPORT-REAL-VALUES |
| 1176-1180 | "YOU WRITE FORMS, NEVER THEIR RESULTS … flagged as unverified narration" | **R** | FORMS-NOT-RESULTS |
| 1182-1192 | "RESULT VARS. Every eval's value is a live var result/<id>" | **R** | RESULT-VARS |
| 1194-1198 | "STATE ACROSS TURNS. defn/atom persist, bare def does not" | **R** | DEF-PERSISTENCE |
| 1200-1205 | "ERRORS ARE VALUES" | **R** | ERRORS-ARE-VALUES |
| 1207-1225 | "THE RENDERING SYSTEM … render twins, auto-run, SHOW-DON'T-TELL" | B (UI) | stays (or links ui-canvas) |
| 1227-1232 | "THE SHARED STORE … register! before transact, namespaced keys" | B (db) | stays |
| 1234-1270 | "THE NAMESPACES BELOW … curated, discover don't hallucinate; BUILD YOUR ENVIRONMENT; ids are live handles" | B (mixed) | stays |
| 1272-1428 | "STANDING TEACHINGS … kb-storage, planning, messaging, lifecycle, finishing" | B | stays |

**Finding:** the R rows (1099-1205 + 1182-1198) are contiguous-ish at the TOP of
system-text and already form a natural "REPL-usage" cluster. They just lack
`;;; ──` labels and are interleaved once (RESULT VARS sits after the rendering
para in reading order only because of history). Concentration = *label + reorder
these into one titled run*, not relocate them to a new home.

### 1b — The `/repl` skill (`seon-skills/repl/SKILL.md`) — the DEEP DIVE

Renders to agents when loaded (the default skill loadout is `[:repl]`,
config.cljs:1109, so it renders to EVERY default agent via the `:skills-catalog`
block, priority 12). Content: how the reader segments forms (rewrite-clj),
what EVALUATES vs is DROPPED, comment levels, the `#code` heredoc (FULL worked
example), what parinfer AUTO-FIXES vs what returns a `:read` error, how a failure
reaches you. This is the **mechanics-in-depth** companion — largely
NON-overlapping with system-text EXCEPT:

- **`#code` heredoc** is taught in BOTH (system-text 1157-1168 short form; skill
  30-62 full form) — DUPLICATION.
- **Comment levels** taught in BOTH (system-text 1146-1155; skill 25-28) —
  DUPLICATION (and both restate `docs/conventions.md:841`).
- **"what runs vs is dropped"** taught in BOTH (system-text 1127-1132; skill
  13-23) — DUPLICATION.

### 1c — Per-verb / per-fn docstrings that teach eval mechanics beyond their call

- `seon.agent.ctx/format-eval-row` (ctx.cljs:723-773) — its docstring states the
  canonical `;=>` result shape verbatim. This is the RUNTIME's own contract, not
  a teaching surface an agent reads as a card; it is correct that it lives with
  the emitter. **Leave it** (it is documentation of the emit, adjacent to the
  code — moving it would be the dumbass move).
- The five capability tools + `my.*` docstring result echoes (`;; =>` vs `;=>`
  vs `«…»`) — **already audited**, do NOT redo:
  `value-representation-consolidation-2026-07-06.md` (Option C+: standardize to
  single `;=>` + `«shape»` + a warn-lint) and `fabrication-complete-gate-2026-07-06.md`
  (§0: our `;; =>` examples model the fabrication shape). Those own the
  per-docstring RESULT-echo representation. This document owns the always-on
  LOOP-mechanics prose. The line between them: **a verb's own call example stays
  in its docstring; the shared RESULT-representation rule + glyph vocabulary +
  loop mechanics concentrate here** (see §4).

### 1d — Other blocks/sections carrying eval/REPL framing

- `seon.agent.ctx.transcript/masthead` (transcript.cljs:215-225) — "seon · <ns>
  · live REPL / flat time-ordered log, oldest-first, append below." Its own
  comment (208-213) is already correct doctrine: "the live-REPL-session framing
  lives ONCE in system-text (no re-teaching here)" — block-specific cues only.
  **This is the model to follow** — it already defers to system-text. Keep.
- `resume-marker-line` (transcript.cljs:227-233) — "session resumed … result/<id>
  vars are gone." Block-specific runtime cue, not general teaching. Keep.
- `my.kb.shared/instructions-block` (kb/shared.cljs:89-115) — the cluster-wide
  human/agent instruction singleton (priority 10 block). Seeds EMPTY
  (`seed-tx-data`, kb/shared.cljs:53-61); the ns doc EXPLICITLY says the four
  shipped behavioral teachings live in system-text, NOT here. So it carries NO
  REPL mechanics today (correct) — it is a runtime append surface, not a
  doctrine home. **Not a concentration target; leave.**
- `default-ctx-blocks` (config.cljs:135-176) wires the blocks by symbol;
  system-text is NOT among them (it is the system message). Confirms system-text
  is the single hardcoded-mechanics artifact.

### 1e — The glyph vocabulary (the ⟹ / result-claim machinery)

- `result-marker` (ctx.cljs:616-628) = `"⟹"`, documented as "the SINGLE SOURCE
  OF TRUTH … the emit site (format-eval-row + transcript), the neutralizer
  (reserved-glyph-re), and system-text's teaching all reference THIS def — never
  a bare `⟹` literal."
- `unverified-narration-marker` (630-635), `result-claim-re` (637-645),
  `bare-result-claim-re` (647-663), `neutralize-result-claims` (665-697).

**LIVE DRIFT FOUND (the sharpest single finding):** `result-marker`'s docstring
CLAIMS everything references it, but:
- `format-eval-row` emits the glyph as a **literal** `"; ⟹ "` (ctx.cljs:876) and
  `"; ⟹ ✗ "` (error-lines, ctx.cljs:720) — NOT via `result-marker`.
- `system-text` still TEACHES the OLD glyph **`;=>`** throughout (lines 1117,
  1123, 1171, 1183) — it has NOT been updated to `⟹`.

So the runtime now emits `; ⟹ <value>` while the always-on teaching says the
agent will see `;=>`. This is precisely the scatter/drift the owner is pointing
at, and it is the reason concentration must reference the CONSTANT, not literals.
(The af08 unit is landing the `⟹` mechanism — the constant exists but the emit
sites + teaching are mid-migration. This concentration builds directly on af08:
once af08 wires the emit sites to `result-marker`, the teaching in the
concentrated block reads the SAME constant, so glyph = one swappable edit.)

## 2 — TABULATION: topic × where it lives (overlaps / contradictions / gaps)

| Topic | system-text | /repl skill | format-eval-row doc | transcript masthead | glyph defs | Verdict |
|---|---|---|---|---|---|---|
| "you're in a REPL" framing | ✓ 1099-1113 | ✓ title | — | ✓ 223 | — | masthead defers correctly; skill title mild overlap |
| How forms are written (`(` runs, prose is `;`) | ✓ 1127-1155 | ✓ 13-28 | — | — | — | **DUPLICATION** (skill = depth) |
| What is DROPPED (bare literal/value) | ✓ 1129-1132 | ✓ 20-23 | — | — | — | **DUPLICATION** |
| How results appear (`;=>` line) | ✓ 1117/1123/1171 (says `;=>`) | — | ✓ (says `;=>`) | — | `result-marker`=`⟹` | **CONTRADICTION** — teaching `;=>`, runtime emits `⟹` |
| ⟹ / glyph vocabulary | teaches `;=>` only | — | literal `; ⟹` | — | `result-marker` def unreferenced | **DRIFT** — 3 unsynced copies |
| `#code` heredoc | ✓ 1157-1168 (short) | ✓ 30-62 (full) | — | — | — | **DUPLICATION** (keep skill full, trim system-text to pointer) |
| Anchored-edit candidates recovery | ✓ 1166-1168 | partial (fs) | — | — | — | OK — belongs with fs; system-text has 1-liner |
| Completion / fabrication ("write forms not results") | ✓ 1134-1144, 1176-1180 | — | — | — | neutralizer enforces | OK — one home; enforced downstream |
| async/await | **GAP** — not in system-text | — | — | — | — | **GAP**: only in `/clojurescript` skill (not default-loaded) + AGENT.md (not agent-facing). Agents get no always-on await rule |
| def persistence across turns | ✓ 1194-1198 | — | — | — | — | OK — one home |
| result/<id> vars | ✓ 1182-1192 | — | (mentioned) | ✓ 231 | — | OK — one home + block cues |
| errors-are-values | ✓ 1200-1205 | ✓ 103-105 (envelope) | — | — | — | mild overlap; both fine |
| Comment levels | ✓ 1146-1155 | ✓ 25-28 | — | — | — | **DUPLICATION** + both restate conventions.md:841 |

**Overlaps/duplication:** form-vs-prose, dropped-literals, `#code`, comment
levels each taught in BOTH system-text and the `/repl` skill.
**Contradiction:** results shown as `;=>` in teaching, `⟹` in the runtime.
**Drift:** the `⟹` glyph exists in 3 unsynchronized forms (constant, literal
emit, `;=>` teaching).
**Gaps:** (1) NO always-on `^:async`/`await` rule — an agent that writes a bare
top-level `(await …)` gets a self-host throw with no context warning it (only
the non-default `/clojurescript` skill covers it); (2) system-text's OWN
docstring says usage teaching "never" lives there, which is false and misleads
the next editor.

## 3 — DESIGN: the concentration

### 3a — Home: EXTEND system-text, do NOT add a new block

**Recommendation: restructure the existing `system-text` `def` into
explicitly-labeled subsections; do not create a dedicated derived "repl-usage"
block.** Trade-offs:

- **Token budget:** system-text renders EVERY turn as the byte-stable system
  message (already in the cacheable prefix). A new block would ALSO render every
  turn — no saving — but would DUPLICATE the framing already there ("you are at a
  live REPL", "the transcript is eval'able") because a repl-usage block can't
  omit the frame the rest of system-text assumes. Net: a new block ADDS tokens
  and splits the authority. Extending adds ZERO tokens (same prose, now labeled).
- **Byte-stability / cache:** system-text is already a `def` with a hard
  no-timestamps/no-ids invariant — the ideal cache-prefix artifact. Keeping the
  content there preserves that. A new block sits at some priority in
  `default-ctx-blocks`; if placed after a volatile block it busts differently.
  One artifact = one cache story.
- **Seed-copy semantics:** blocks are seed-COPIED into each agent's
  `:seon.agent/ctx` at creation (`seed-default-ctx!`), so a doctrine EDIT would
  only reach agents created AFTER the edit — EXISTING agents keep the stale copy.
  system-text is read LIVE from the `def` every turn (never copied), so an edit
  reaches ALL agents next turn. **This is decisive: doctrine that must update
  uniformly belongs in system-text, not a seed-copied block.**

So: ONE authority, made *structured*. Add `;;; ── REPL USAGE ──` … `;;; ── end
REPL USAGE ──` sub-brackets (the `;;;` runtime-structure level, conventions.md)
grouping the R rows (§1a) into a titled run at the top, each with a short ALL-CAPS
lead the agent can scan.

### 3b — Single programmatic source: content-as-data, glyphs-as-constants

The owner wants "one edit propagates." Two structural moves:

1. **Glyphs are the named `def`s, referenced — never literals.** The concentrated
   block interpolates `result-marker` (and any siblings af08 introduces) into the
   teaching string, e.g. `(str "; each form's value on the next line as a `"
   result-marker " <value>` comment …")`. The SAME constant is interpolated at
   the emit site (`format-eval-row` — af08's job) and read by the neutralizer.
   Then the glyph is ONE edit that provably can't drift between what the runtime
   emits, what the sanitizer catches, and what the context teaches — which is
   exactly what `result-marker`'s docstring already PROMISES but the code does
   not yet DELIVER. **Do not duplicate the `⟹` literal anywhere.**
2. **The block stays a single `def` (or a small fn composing labeled
   sub-`def`s).** Option: split system-text into `repl-usage-text` +
   `behavioral-text` sub-defs concatenated by `system-text`, so the REPL half is
   one addressable unit with its own tests. This is optional polish — the
   minimum is the `;;;` labels. Do NOT invent a markdown source-of-truth that a
   build step renders; that is a second mechanism (the code-as-data rule: the
   `def` IS the source). Do NOT restate the rule in each verb docstring.

### 3c — What MOVES in vs what STAYS distributed (the line)

**MOVES INTO / STAYS IN the concentrated system-text REPL-USAGE section** (the
shared loop mechanics — true for EVERY form regardless of verb):
- where-you-are (REPL/CLJS/Node/no-JVM), transcript-is-eval'able, what-runs-vs-
  dropped, forms-not-results, result/<id> vars + the glyph vocabulary,
  def-persistence, errors-are-values, report-real-values, comment-levels (ONE
  authoritative sentence + a pointer to conventions.md), the `#code` ONE-liner +
  pointer to the `/repl` skill.
- **NEW: add the missing always-on `^:async`/`await` rule** (one line: "await
  only inside an `^:async` fn; a returned Promise auto-resolves; never a bare
  top-level `(await …)`") — closes the §2 gap.

**STAYS DISTRIBUTED (correctly):**
- **A verb's own call example** stays in its docstring (`fs/replace!` shows its
  own arg shape; `my.kb.shared/instructions` shows its append shape). The block
  teaches the LOOP, not each verb.
- **The `/repl` skill stays the DEEP DIVE** — parinfer auto-fix rules, `:read`
  error taxonomy, rewrite-clj segmentation, the FULL `#code` worked example. The
  concentrated block links to it by name ("delimiter repair + `:read` errors:
  the `repl` skill"). Deduplicate: DELETE the skill's re-teach of comment-levels
  and what-runs-vs-dropped down to a one-line pointer back to system-text; keep
  the depth (parinfer/`:read`) that system-text does NOT cover.
- **`format-eval-row`'s docstring** stays with the emitter (documents the emit,
  not a teaching card).
- **transcript masthead / resume-marker** stay — block-specific runtime cues,
  already deferring to system-text.
- **The per-docstring RESULT-echo representation** (`;=>` vs `«shape»`) stays
  owned by `value-representation-consolidation-2026-07-06.md`. This concentration
  does NOT touch the 30 verb echoes — it owns the always-on prose + the glyph
  constant those echoes should eventually reference.

### 3d — Migration + anti-drift

1. **After af08 lands** (`result-marker` wired into the emit sites): update
   system-text to interpolate `result-marker` in place of the literal `;=>`
   strings (fixes the §1e contradiction in the same edit).
2. Add the `;;; ── REPL USAGE ──` sub-brackets around the R rows; reorder RESULT
   VARS/DEF-PERSISTENCE to sit inside that run.
3. Fix system-text's OWN docstring — delete the false "usage teaching … never
   here" sentence; replace with "the REPL-USAGE sub-section is the single home
   for loop mechanics; per-verb call examples live in docstrings; delimiter
   repair depth is the `repl` skill."
4. Trim the `/repl` skill's three duplicated topics to pointers.
5. Add the missing `^:async`/`await` line.
6. **Anti-drift structure (no hand-maintained list):** the single `def` + the
   glyph-as-constant IS the anti-drift mechanism — there is structurally one
   place to edit. OPTIONAL lint (mirrors the existing docstring/markdown dev
   linters, warn-only): flag a bare `⟹`/`;=>` literal in `src/**.cljs` OUTSIDE
   the `result-marker` def + system-text interpolation, suggesting the constant.
   This composes with the C+ echo-lint from the representation doc — coordinate,
   don't build a second linter.

### 3e — Hairiness + when to land

- **Hairiness: 2.** Pure prose restructure + ~4 deletions + swapping 3 literals
  for a constant + one new line. No new mechanism, no schema, no block.
- **Sequencing: land as a TRACKED FOLLOW-UP, AFTER af08 merges** (it depends on
  `result-marker` being wired at the emit sites) and can share the edit window
  with the `value-representation` C+ pass (both touch ctx.cljs + docstrings +
  a dev lint — do them together to avoid double-churning the same files). It
  should NOT block the bench handoff: the drift is cosmetic-to-agents today
  (they cope with either glyph), and the concentration is a maintainability win,
  not a capability fix. Register it as one row in
  `docs/seon/orchestrator/issues/dual-code-paths-registry.md`.

## Complexity artifacts found

- **One doctrine, one-and-a-half homes** — `system-text` (ctx.cljs:1086) holds
  ~60% REPL-usage teaching while its docstring claims usage teaching is "never
  here," and the `/repl` skill re-teaches 3 of its topics. Subsuming system:
  system-text itself. RECOMMENDATION: label into subsections + trim skill
  overlap to pointers (§3). ASK owner: approve extend-in-place over a new block?
- **The `⟹` glyph in 3 unsynchronized forms** — `result-marker` def (ctx.cljs:616,
  claims to be SoT) vs literal `"; ⟹ "` emit (876/720) vs `;=>` in system-text
  teaching (1117…). Subsuming system: `result-marker`. RECOMMENDATION: wire emit
  + teaching to the constant (af08 owns emit; this owns teaching). ASK owner:
  confirm this concentration lands ON af08, not in parallel.
- **Missing always-on `^:async`/`await` rule** — a real GAP: only the
  non-default `/clojurescript` skill + the non-agent-facing AGENT.md cover it.
  RECOMMENDATION: add one line to the concentrated section.
