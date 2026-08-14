---
type: prd
status: draft
tags: [prd, agent, context, architecture]
---

# The One Renderer — draft PRD (2026-08-14, for the owner's markup)

The owner's directive, stated three times and now the ruling frame:
**everything that reaches the AI context or the browser is DATA that
passed through a declared render function OR hit the render floor, and
ONE universal print system bounds and faces it for both `/ai` and
`/html`. That system is the workhorse of the whole product; energy goes
into making IT — and only it — a single, well-thought-out renderer.**
Every defect found by today's three audits was some path routing
*around* that owner. This PRD names the target, what rips out, what
iterates, and what the archaeology must confirm before anything is
rebuilt. It builds on the landed floor-first ruling (ledger 27), the
two-seams bounding ruling (ledger 32), the chat/debug projections
(ledger 30/31), and the results-as-data audit's nine-seam rip-out list.

## 0. Scope correction (owner, same day): the ENTIRE pipeline

The owner's clarification, governing everything below: this PRD covers
the **complete content rendering system for AI and HTML outputs, start
to finish — one coherent pipeline where nothing can go sideways.** The
diagnosis, in the owner's words: all the hacks are the same problem —
there was no well-thought-out system, it kept blowing up and returning
thousands of lines of garbage, and the holes got papered over instead
of fixed. So the unit of design is the WHOLE PIPELINE, not the seams:

```text
value produced (whole)
  → storage admission (declared caps, honest elisions)      [seam 2]
  → derivation (history unit / walk membership, from facts)
  → projection selection (:seon.render/ai | /html | /form)
  → producer or floor (declared face, or derived data face)
  → fit (the ONE bounding: profile budget, form-aware,
         elision values)                                     [seam 1]
  → face assembly (prompt bytes | page hiccup)
  → delivery (provider wire | SSE morphs)
```

Every stage: total (never throws into the pipeline), honest (typed
loud error values, never silence), bounded (declared budgets), and
contract-checked at its boundary. A value that skips a stage is
unconstructable, not discouraged.

**The failure policy (R41 applied to rendering): PANIC HARD IN
DEVELOPMENT.** In dev, ANY render-path contract violation — a producer
returning the wrong shape, a stage handed a value it cannot face, a
budget applied outside fit, an unregistered value where a registered
one is promised — PANICS at the stage boundary with the producer,
value, and contract named. There is no degraded output in dev: no
`renderer unavailable` placeholder (that div is a swallow wearing a
label — BANNED as a dev output), no partial page, no silently smaller
result. In production the system NEVER crashes (R41's other half): the same
violation renders as ONE polite, concise, HUMAN-DESIGNED error face —
a small declared card (the `seon.error` card family is the seat):
one plain sentence of what failed, the identity to requery or report,
consistent styling, deduplicated when repeated — never a stack trace,
never an EDN dump, never garbage, never absence. **And it is the EXISTING error mechanism, not a new system** (owner,
same day): a production render failure is one ordinary `:seon.error`
FACT (the evidence-complete diagnostic constructor — layer, producer,
offending value, contract, requery identity), which then renders like
every other value through the pipeline itself: the HUMAN face is the
designed card above; the AGENT face is the flat error value in its
context — data it can query, diagnose, and act on. Agents never crash
in production and can always fix their own fuckups: when the failing
producer is agent-authored, the error value names it, the agent
redefines it, and green-to-install gates the fix — the self-repair
loop closes with zero new machinery. Dev-panic is for US (defects
cannot slip past development); the error-fact-with-faces is for
production humans AND agents. Three faces, one fact, one pipeline —
the pipeline's own failures flow through it as values.
"Thousands of lines of garbage" becomes impossible because every
stage's output is checked and bounded before the next stage sees it,
and the end-to-end invariants (whole-prompt accounting exact to the
token; page lint clean; honesty falsifiers) are suite properties.

## 1. The target architecture (one page)

**Values flow whole.** A producer — any function whose output reaches
an agent or a page — returns its complete value. No producer bounds,
truncates, summarizes, or narrates. (Ledger 32.)

**One render pipeline, three projections.** Every outward value crosses
`seon.render` exactly once, where candidates select a declared producer
(`:seon.render/ai`, `:seon.render/html`, `:seon.render/form`) or fall
to the derived floor (`seon.render.value`): registered shapes render as
readable, identity-first data faces; unregistered values as fitted
printing. The floor is GOOD — decency never requires a declaration
(ledger 27); declared producers exist for genuinely special surfaces
(errors, messages, turn headers), not as the price of readability.

**Results are data.** In result position the pipeline renders the
VALUE — never an `/ai` prose producer substituted for it (the
results-as-data audit's root defect, `seon.render/project-node*`).
`/ai` prose belongs to declared instruction entities alone; after the
rip-out, "prose on purpose" = "is an instruction entity", a one-line
predicate.

**Bounding at exactly two seams.** (1) The render boundary:
`seon.print`/the floor applying the profile budget (estimated tokens),
emitting elision values — count, path, requery identity — for BOTH
projections; fit is form-aware inside code content (whole-form elision;
a mid-form or mid-fence cut is unconstructable inside the owner).
(2) Declared storage-admission caps (`seon.sci.admit` config facts),
also emitting honest elisions. `bounded-text` is internal machinery of
these two seams; producers calling any bounding primitive is the
defect, asserted by graph query.

**One derivation, three faces.** The agent's history unit — the ordered
form+value entries derived from message/run-form/receipt facts, never
stored as a transcript — feeds: the model's `/ai` context; the CHAT
view (`/html` projection per block; entries with no real html face
become chips that expand inline to pretty-printed, highlighted data);
and the DEBUG view (always the `/ai` content, pretty — character-
faithful modulo whitespace and color, raw-bytes toggle underneath).
Same block identities everywhere, so live morphs serve every face.

**Honesty is tested, not promised.** The debug face's character-content
equality; the chat chip's completeness (nothing dropped, everything
expandable); the lint tool (`seon.render.lint`) judging pages as hiccup
data with policy echoed in every report; the whole-prompt accounting
exact to the token (proven at 3-token error in Drive 1).

## 2. What this dissolves (the rip-out register)

| # | Today's mechanism | Disposition |
|---|---|---|
| 1 | `project-node*` substituting `/ai` producers in result position | DELETE — values render as data (audit seam 1) |
| 2 | `seon.print` sink emitting raw `/ai` fragments below root | DELETE (audit seam 2) |
| 3 | Narrating producers: `run/render-ai`, `stale-var-ai`, `message/render-ai` prose, error narration, cluster/config twins | REPLACE with attribute faces (audit seams 3-7) |
| 4 | The floor's second map face (`nominal-at:` unqueryable spellings) | DELETE — one readable EDN face, namespaces intact (audit seam 8) |
| 5 | Two elision representations | UNIFY on the elision value (audit seam 9) |
| 6 | 12 producer-side bounding sites (census tier 1-2) | DELETE the bounds; values flow to the boundary (ledger 32; in flight) |
| 7 | CSS content hiding | DONE — `d294ac876` |
| 8 | `subs` inside `fit-projected` splitting fences | FIX inside the owner — form-aware fit (in flight) |
| 9 | Per-shape hand `/html` declarations demanded for decency | DISSOLVED by the floor (landed `0f1374d5c`; placeholder residue in flight) |
| 10 | Retained render packages serving stale producers | Cache invalidation on producer change (filed) |

## 3. What iterates (not from scratch — the archaeology gates these)

- **Form-aware fit**: the owner learns code-content awareness (whole
  forms as elision units). Archaeology: the first implementation
  rendered REPL transcripts for months — find its fit/pprint/truncation
  code and its lessons BEFORE writing this.
- **Pretty-data + highlighting**: server-side single-scan tokenizer
  emitting the existing `seon-print-*` classes (design doc decision 3).
  Archaeology: the old system had context cards, compact-card
  rendering, docstring faces — did it already own a Clojure
  tokenizer/pretty-printer worth reviving?
- **The chat page**: transcript `/html` projection with chips (ledger
  30/31). Archaeology: the old agent view / canvas / diffusion-era
  transcript UI — what worked, what was deliberately torn down, why.
- **Turn segmentation**: contribution rows + stored SHA check (design
  doc decision 2; one accretive key `:seon.context.contribution/characters`).

## 4. What stays exactly as is

`seon.render` candidates selection; the declared-producer mechanism;
the elision-value schema; `seon.ai.tokens/estimate` as the size unit;
the render profile as the database-derived fit policy; block identity
and Datastar morphs; the capture facts as the honesty baseline.

## 5. Unconstructability locks (each lands with its wave)

1. Producers bound nothing — graph query over bounding callers; the two
   seams are the only `bounded-text` callers.
2. Fit never splits a form/fence — adversarial budget regression.
3. Result positions read back through the reader — the results-as-data
   fraction becomes a suite property (target: 100% minus declared
   instruction entities).
4. Rendered pages lint clean — `seon.render.lint/check` wired as a
   regression over the standard pages with subject-present enforcement.
5. Debug face character-equality; chat chip completeness.

## 5.5 The property suite (owner requirement, same day)

The design defines its tests, and the tests are LOUD DRIFT ALARMS —
property tests over the well-defined pipeline, never fragile unit
assertions. Banned outright: exact-string expectations ("these words in
the help text"), pinned counts, golden HTML files. The properties are
generative wherever a value is the input, because registered schemas
make generators free (`mg/generate` per shape; the green-to-install
auto-check machinery is the precedent), and test.check SHRINKS every
failure to the minimal reproducing input.

Per-stage properties (each a seeded generative property, adversarial
budgets/profiles included):

1. **Totality**: any generated registered value (and adversarial
   unregistered ones) renders through the whole pipeline without a
   throw — the output is a face or ONE error fact, never an exception,
   never absence.
2. **Honesty/round-trip**: a data face's text reads back through the
   reader equal to the value modulo declared elisions; every elision is
   a real elision value carrying count + requery identity; re-querying
   the identity reaches the elided content.
3. **Boundedness**: for any generated value and any budget, output ≤
   budget (estimated tokens) AND no fence/form is ever split — whole-
   form elision only.
4. **No producer bounds**: the graph-query census (producers contain no
   bounding calls; the two seams are the only bounded-text callers) —
   subject-present by construction.
5. **Results are data**: every generated result position's rendering
   reads back through the reader; prose appears only under declared
   instruction entities.
6. **Accounting exactness**: assembled prompt estimate == sum of
   per-contribution costs == what the wire sends (the 3-token proof
   becomes a property).
7. **Page lint**: for generated agent histories, the rendered pages
   pass `seon.render.lint/check` (no placeholders, no splits, no
   duplicated subtrees, no soup) — the UI's generative test: generate
   arbitrary histories from schemas, render the full page, lint it,
   SHRINK failures to the minimal history that reproduces.
8. **Face equivalences**: chat entries ∪ chips == debug entries ==
   capture content (completeness); debug pretty face character-equal to
   `/ai` content modulo whitespace; block identities stable across
   faces.
9. **Failure faces**: for generated defective producers (wrong shape,
   throwing, over-budget), dev mode panics naming the stage; prod mode
   yields exactly one error fact whose three faces all render.

The gap census (§7b) includes the TEST AUDIT: which existing tests
these properties SUBSUME (delete them — a smaller suite is a desired
outcome), which assert the old lenient/fragile shapes (rewrite to
wanted-behavior properties), and which stand. One property per defect
class, claimed by a recurring surface, per the standing suite law.

## 6. Open questions for the owner

1. The narrated producers (rip-out #3) currently carry SOME curation
   (which attributes lead). Attribute faces are data — is the floor's
   identity-first ordering enough, or do these shapes keep tiny
   declared faces that ORDER but never prose?
2. The `/form` projection (third face) is declared but thin — does it
   join this wave (each entry carrying its regenerating form) or wait
   for the drive series to demand it?
3. Chat default for the drive demos: chat view or debug view as the
   agent page default while the chat face matures?

## 7. Archaeology verdict (landed — [full report](../research/render-archaeology-2026-08-14.md))

The owner's suspicion was CORRECT: the first implementation had the
coherent pipeline this PRD re-derives, and the fresh tree's piecemeal
growth lost it. Quarry root: `git show 9e44815f5:src-old/<path>`.

**Revive verbatim:** `seon/ui/clojure.cljc` — the 192-line server-side
Clojure tokenizer (total by contract, morph-safe, dependency-free);
the fresh tree has NO highlighter. Only change: `hljs-*` →
`seon-print-*` classes, and its degrade-to-plain fallback routes
through the strict dial.

**Revive adapted:** the two-phase **sample → emit** bounding
architecture (bound the structure, THEN print — nothing oversized is
ever cut), the Oppen-style `fits?`/`emit` printer, the capped Writer,
`dominant-string-entry` (shape-general 70%-payload rule); the
per-block **output-byte chain hashes** (producer change → text change →
hash change → invalidation from that block forward — rip-out #10's
structural fix); the drill protocol (closed request map, four-axis
bounds, clients may only narrow, indexed non-drillable keys);
`strict-fail!`'s catch-site order (error FACT classified agent-vs-core
→ dev panic → prod face, siblings untouched) and `missing-render`
(name the unresolved symbol so defining it self-heals — the correct
treatment of the banned placeholder). `render/chat.cljc` bubbles.

**Revived RULINGS (constraints, code stays dead):** the `/ai` context
is a FLAT interleaved event log — no turn containers in the model's
face (turn sections are HTML-face chrome only); reserved runtime
glyphs as single-source defs (`⟹` results deliberately NOT
comment-shaped — agents were observed fabricating results as `; ⟹`
comments); error-run coalescing with a teaching line; byte-stability
above the cache breakpoint.

**Honest negatives (the revival must invert):** form-aware fit never
existed (the old `clip-string` was the same bare `subs`); the strict
dial DEFAULTED OFF (absence-as-health inside the guard itself —
revive with panic-on by default in dev); eleven catch sites bypassed
the dial (so no-silent-swallowing must be a GRAPH QUERY over catch
sites, property 9's census, never a convention); the seven old render
dials are correctly deleted — today's single profile wins.

## 7b. Gap-census mandate (running; report links land here)

Two research lanes: (a) the OLD implementation's render/print/transcript
system mined from git history — inventory of what existed, quality
verdict, what to revive verbatim vs lessons-only; (b) the current-tree
gap census against §1 — every seam that must change, sized, with its
owning file and blast radius. No build work begins on §3 items until
(a) reports, per the quarry-first law: the best version of this system
may already exist in the archive.
