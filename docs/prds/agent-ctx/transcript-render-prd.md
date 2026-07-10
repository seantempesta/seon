---
type: prd
status: active
tags: [prd, agent, flow]
---

# Transcript render redesign — one config-driven surface

The agent's transcript is how it reads reality: the forms it wrote and the
results the runtime computed. Today that surface is rendered by several
loosely-coupled sites, and its value display fights three goals at once —
anti-fabrication, readability, and precise editing. This PRD consolidates the
whole surface into **one config-driven render path** with a **bare-`⟹` inline
result grammar** and **one central, display-only decay** — so results are
unmistakable, functions stay simple (they emit full values; the center clips),
and a formatting change moves everything the agent sees at once.

Designed live 2026-07-07 (mockups: `scratchpad/transcript-designs.html`) and
hardened against an adversarial code audit
(`research/transcript-render-prd-audit-2026-07-07.md`) whose blocking findings
the owner rulings below resolve. This is the durable capture.

## Settled model (owner rulings 2026-07-07)

- **Results are EPHEMERAL — REPL semantics.** A result lives in the volatile
  session stash (`globalThis.result.<id>`); the handle `result/<id>` recalls
  it *within the session*. Across a restart it's gone — fine, like any REPL.
  There is NO auto-persistence and NO "recall from blob." **Blobs are
  INTENTIONAL persistence** the agent opts into (`my.blob/put!`); the DB
  `:seon.eval/result-edn` is a **debug/forensics** projection, not agent
  recall. (Resolves audit B1 by design — recall-forever was never the goal.)
- **Decay is DISPLAY-ONLY, and the churn is intentional.** Recent evals render
  full so the agent has the full picture; the *display* compresses over the
  decay window (paying cache-churn tokens on purpose); past a threshold it
  **freezes byte-identical and caches**. The value never changes — only its
  render resolution. (Reframes audit B3: byte-stability governs *aged* rows;
  recent churn is a deliberate cost.)
- **One central clip — functions emit FULL output.** The single decay/render
  path is the ONLY place that reduces resolution. Verbs never produce
  clipped-at-different-resolutions output. (Agent-driven paging like
  `fs/view :from-line` / `my.blob/text` is the agent *choosing* a slice, not a
  verb clipping the display.)
- **Bare `⟹` inline, not `; ⟹`.** The `:seon.render/ai` result is
  `(form) ⟹ <value> ⟸ result/<id>` — everything after `⟹` (through `⟸ handle`)
  is the result; the next line is proper Clojure (the agent's comments and
  forms). Accepted tradeoff: the transcript is no longer re-evaluable Clojure —
  clarity wins, because `; ⟹` is already confusing agents. (Resolves audit B2.)

## Why now — the problems this closes

- **Scatter/drift.** A8 landed the `⟹` `result-marker` constant, but rendering
  still lives across `format-eval-row`, the cite-card, and the value sampler;
  a change means editing several sites (the cite-card `=>` drift this session
  is the proof — since fixed to reference the constant).
- **Fabrication by mimicry.** `; ⟹ value` looked like a comment; agents write
  comments, so they copied it, fabricated results, and `complete`d on them (T4:
  6/24). A result must look *alien* to what an agent types — hence bare `⟹`,
  reserved and neutralized.
- **Imprecise editing.** For whitespace-sensitive code the agent needs to see
  exact bytes (tabs vs spaces, indent, trailing ws) to build an exact
  `replace!` find.
- **Per-function clipping sprawl.** Every verb inventing its own truncation is
  the convolution to avoid — one central decay instead.

## Core principle — one path, config is the single source of truth

**All transcript/value rendering flows through the one guarded walker
(`seon.render.value/sample`) and the one eval-row renderer
(`seon.agent.ctx/format-eval-row`); every formatting choice is a knob in
`config/system.edn`'s `:seon.config/render`; reserved glyphs are named
constants referenced by the emit site, the neutralizer, AND the agent teaching
— so nothing drifts.** Anti-drift is achieved by the *shipped constant-into-
template* pattern (A8's `result-marker`), extended to the other glyphs — NOT by
generating agent prose from config (the audit flagged that as over-engineered;
a hand-written rule that interpolates the constants gives the same no-drift for
far less machinery).

Consequence — experimentation is: flip a knob / change a constant →
`bin/seon restart pod` → drive a live agent → read the `/ai` twin (exactly what
it saw). One path is the experiment surface; no hunt across sites.

## The grammar (recommended defaults — all knobs)

Reserved RUNTIME glyphs (named constants; the runtime writes them; any one an
agent types is neutralized): `⋘ … ⋙` status · `❯` prompt (last symbol) ·
`⟹` result-open · `⟸` result-close (carries the handle). Plus the live
value-vocabulary (existing, NOT reserved): `⟨N tok⟩` size · `‹partial›` ·
`#‹…›` opaque · `{…N keys}` elided · `result/<id>` handle.

```
⋘ <turn-ts> · turn N · <turn-ctx-tok> · <ns> ⋙ ❯
; the agent's reasoning — a comment before each form
(a-form) ⟹ <value> ⟸ result/<id>
(another-form) ⟹ <value> ⟸ result/<id>
```

Rules:

- **Bare `⟹`, inline.** The result follows `⟹` on the form's line; `⟸ result/id`
  closes it. Everything between is the runtime's; the agent NEVER writes a line
  containing a reserved glyph. Every line it authors is a `(form)` or `;`
  comment.
- **Value size → layout (a knob, not a per-verb decision).** A short value
  stays inline. A large *recent* value may span lines *between* `⟹` and
  `⟸ result/id` (the close fences it, so multi-line is unambiguous); as it ages
  the central decay shrinks it to a one-line `{…N keys} ⟨N tok⟩ ⟸ result/id`
  reference, then freezes. The verb emitted the full value; the render decides
  resolution by age.
- **Per-turn status = the readline.** One `⋘…⋙ ❯` line per turn carries the
  turn's FIXED values (its timestamp, its turn index, the context-token count
  *recorded at that turn*, the ns) — fixed ⇒ byte-identical when aged ⇒
  cacheable. This deliberately reintroduces a per-turn marker (the current code
  has none); it is byte-stable because the values are the turn's, never
  live-recomputed.
- **Comments before forms**, preserved (the teaching asks for a `;` reasoning
  line before each form; batching multiple forms/turn is encouraged).

## Three projections of one value

One value (session stash), one walker, three renderings — today only `/ai` and
`/html` exist; this unit names the third and each one's optimization.

- **`:seon.render/value`** — the FULL value, un-decayed. What the handle
  resolves to *within the session* (the live stash value) and what an html
  expand shows. The sampler with bounds off.
- **`:seon.render/ai`** — the AGENT's transcript projection: display-decaying
  with age, optimized for USE not reading — line numbers, explicit whitespace,
  a prominent `result/<id>` handle, structure kept so `get-in`/`map`/`filter`
  resolve against the live value. Goal: the agent references and transforms the
  value instead of retyping it.
- **`:seon.render/html`** — the HUMAN's projection: interactive expand/copy/
  drill. **DEFERRED to a follow-up unit** — it needs a live value-by-handle
  endpoint + UI (a cross-lane feature), out of scope here; `render-html-data`'s
  existing skeleton contract is untouched.

All from the one sampler + one config: a knob change moves `/value`, `/ai`, and
(later) `/html` together.

## Explicit characters — for surgical edits

Config knobs on the same render fn, for content the agent edits:

- `:whitespace :visible|:raw` — render `·` (space) / `→` (tab) glyphs (visible
  mode makes tab/space inconsistency — a real Python bug — visible), or literal.
- `:content-layout :structured|:single-line` — line-numbered block, or one
  escaped line (`\n`/`\t` explicit, nothing hidden).
- `:line-numbers`, `:tabs :arrow|:literal`, `:trailing-ws :dot`.

Display precision ⟂ match precision: glyphs are display-only; the value behind
`result/<id>` is the real bytes; the agent writes real bytes via `#code`
heredoc (byte-faithful `#code/<lang>`; a plain `#code/text` block is the raw
form). The `:whitespace` rendering is a NEW capability of the value renderer for
string content (not an `fs/view` arg — it's central, so every string value
benefits), gated by the knob.

## Anti-drift: teaching references the constants

The always-on `system-text` grammar rule is a hand-written template that
*interpolates the glyph constants* (`result-marker`, `result-close`,
`status-open/close`, `prompt`) and names the current `:content-layout`/
`:whitespace` mode from config. Changing a constant or a mode updates the
render AND the teaching from the same source — drift is structurally prevented,
without a prose generator. (This subsumes A9's marker-rule sliver; A9's other
work — migrating the 4 trapped skill rules — is unaffected.)

## No mixed references — one constant per glyph, zero literals elsewhere

Scope note (grounded 2026-07-07): this section governs the **result-grammar
reserves only** — `⟹ ⟸ ⋘ ⋙ ❯`. The value-vocabulary glyphs
(`⟨N tokens⟩` size · `‹partial›` · `#‹…›` opaque · `{…N keys}` elided) are a
SEPARATE concern: they are emitted by `render/value.cljs` but also appear
legitimately elsewhere (e.g. `«»` is used in `inventory.cljs` /
`my.plan.internal` for unrelated purposes), so they are NOT reserved and NOT
linted. There are **~89 literal `⟹`** across ~20 files (the docstrings/skills
A8 standardized are all literals) — these drift if the glyph changes. Required,
in three parts:

1. **Every result-grammar glyph is a named constant; every EMIT site references
   it.** Add constants for `⟸` (`result-close`), `⋘`/`⋙`
   (`status-open/close`), `❯` (`prompt`) beside `result-marker`. Sweep
   `format-eval-row`, the cite-card, the coalesced line, and the neutralizer to
   reference them — no result-grammar literal in any code that outputs. (The
   value-vocab markers may be tidied into constants for their own sake, but
   that is optional and out of this section's scope.)
2. **Static agent-facing text carries NO result-grammar glyphs.** Docstrings,
   skills, `my.kb.shared` show the *call* and describe the return *in prose* —
   they never render a `⟹ …` example (static text can't reference a constant,
   so the only drift-free option is to not contain the glyph). The result
   grammar is taught in ONE place — the interpolated system-text rule — and
   seen live in the transcript (emitted from the constants). **This SUPERSEDES
   A8's docstring echo standardization: strip the ~89 echoes, do not
   standardize them** (which also removes the last examples that model the
   result format — the fabrication vector).
3. **A lint (warn-only) makes drift visible.** INVERT the existing
   `seon.dev.docstring/wrong-echo-re` — today it REQUIRES a `⟹` echo; flip it to
   FLAG any result-grammar-glyph literal (`⟹ ⟸ ⋘ ⋙ ❯`) outside the constant
   defs (NOT the value-vocab glyphs). Warn-only — the docstring harness is
   intentionally non-blocking; the dev hook surfaces the drift, it does not
   hard-reject.

## Consolidation — kill the scattered sites (a deliverable, not a side effect)

- Route `format-eval-row`, its failure/coalesced lines, the cite-card
  (`ctx/transcript.cljs`), and the value render through the one path + the one
  set of constants.
- The neutralizer keys on the reserved-glyph constants (any `⋘ ⋙ ❯ ⟹ ⟸` in
  agent narration → `[unverified narration]`). NOTE: `❯` and `⟹`/`⟸` are the
  high-signal reserves; audit for false positives on `⋘/⋙` (rare in prose) and
  scope the strip to the agent-narration channel only, never rendered chrome.
- A render-path grep for stray result markers must come back clean.

## Implementation plan (ordered)

1. **Config + constants.** Add `:seon.config/render` knobs (whitespace, layout,
   line-numbers, tabs, trailing-ws) + accessors in `seon.config`; add a constant
   for every RESULT-GRAMMAR glyph — `result-close`, `status-open`,
   `status-close`, `prompt` — beside `result-marker`, one source of truth each.
   (The value-vocab markers stay as-is; they are not reserved and not swept.)
2. **One render path.** Refactor `format-eval-row` + cite-card + the value
   sampler to consume config + constants: bare `⟹ … ⟸`, whitespace/layout/line
   numbers from knobs, size→layout by the existing decay offset (recent full →
   aged `{…N keys} ⟨N tok⟩` reference → frozen). Confirm the decay schedule
   already produces the reference form at the small caps; adjust the small-cap
   render to emit `{…N keys} ⟨N tok⟩` (not a raw char-clip) if it doesn't.
3. **Per-turn status.** The readline/masthead emits the fixed per-turn status;
   verify byte-stability across ages (a fixed eval at a fixed offset renders
   identically).
4. **Neutralizer.** Key on the constants; scope to the narration channel.
5. **Teaching template.** `system-text` interpolates the constants + names the
   config modes.
6. **No-mixed-references sweep.** Point every emit site at the constants; STRIP
   the ~89 literal `⟹` echoes from docstrings/skills/`my.kb.shared` (calls +
   prose returns, no glyph); INVERT `seon.dev.docstring/wrong-echo-re` to flag
   any RESULT-GRAMMAR-glyph literal (`⟹ ⟸ ⋘ ⋙ ❯`, NOT the value-vocab) outside
   the constant defs — warn-only.
7. **Tests.** A "constants drive everything" test (change `result-close` → the
   render row AND the system-text rule both reflect it); a grep/lint proof that
   NO result-grammar-glyph literal (`⟹ ⟸ ⋘ ⋙ ❯`) exists outside the constant
   defs.

Deferred to follow-ups: the interactive `/html` twin (endpoint + UI); any
`my.pdf/*`-style content verbs (not part of this unit — the mockups used them
illustratively).

## Verification

- **Constants-drive-both test.** Change a glyph constant / a layout knob;
  assert the rendered row AND the generated teaching both reflect it.
- **Byte-stability test.** A fixed eval at a fixed age renders byte-identically
  across turns; only recent (pre-threshold) rows churn.
- **Precise-edit proof (live).** A whitespace-inconsistent Python fixture: the
  agent sees the tab (visible mode), writes a `#code` find, `replace!` matches
  exactly.
- **Decay-display proof (live).** Read a large value; drive N turns; the display
  goes full → `{…N keys} ⟨N tok⟩ ⟸ result/id` → frozen; the handle resolves to
  the full value *within the session* and honestly misses after restart.
- **Fabrication re-check.** Reserved glyphs in agent narration → neutralized;
  no comment-shaped result anywhere to copy.

## Relationship to the arc

Builds on A8 (`result-marker` + `⟹` + neutralizer) and the complete-gate (both
committed) and the cite-card `⟹` fix (committed — the motivating drift is
already gone). Supersedes A9's marker-rule sliver only. Not a bench-handoff
blocker (T4 tools are separable), but it upgrades the surface every agent reads
every turn. Open owner picks (all knobs, tune live): the close glyph `⟸`
(vs `⟧`); the status one-line vs explicit-rule form; the default
`:content-layout`/`:whitespace` for code.
