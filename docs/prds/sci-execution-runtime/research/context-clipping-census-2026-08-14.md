---
type: research
status: open
date: 2026-08-14
wave: context-generation-drive
tags: [research, render, config, web, class/n1]
---

# Context-clipping census — every bound applied outside `seon.print/fit`

Owner directive: "find all the hacks where we are clipping context outside of
the value render function." This is a READ-ONLY census. Nothing in `src/` or
`resources/` was edited by this lane; three lanes are live in the render files
and this document is their rip-out input.

## The law, and the compliant machinery it names

AGENTS.md §2.4: outward values cross ONE total render contract; output is
bounded through the one `seon.print/fit` owner; omitted detail is an ELISION
VALUE — ordinary data carrying count, path, and requery identity — never bare
truncation; a floor hit is counted, never silent.

Read end to end before classifying anything below:

- `src/seon/print.cljc:774-780` — `enrich-elisions` replaces every admission
  marker with a declared structural elision value.
- `src/seon/print.cljc:784-821` — `fit-children`: computes `retained`,
  `total`, and emits `elision-node` with `preserve-requery` so the omitted
  tail names a requery identity.
- `src/seon/print.cljc:823-846` — `fit-string` / `fit-projected`: the ONLY two
  places in the system where `subs` on outward text is legal, because the
  result is an `elision-node` carrying `retained`, `(- original retained)`,
  and `original`.
- `src/seon/print.cljc:893-928` — `fit`: measures with
  `seon.ai.tokens/estimate` against `:seon.render.profile/token-budget` and
  halves string, then child, then depth limits. Every cut is a node, never a
  string operation on the emitted text.
- `src/seon/render/value.clj:135-175` — `window`: a stable structural page
  reporting `offset`, `shown`, `total`, `more?`, `beyond-end?`. Loss is
  described, not performed silently.
- `src/seon/sci/admit.clj:236-244` — the admission string cap emits
  `::print/truncated-string` WITH `::print/length`, which
  `enrich-node` (`src/seon/print.cljc:763-765`) turns into a counted elision.

The shape of a legal bound is therefore: **a declared config/profile fact
decides the size, and the omission is emitted as data with a count and a way
back to the rest.** Everything below is judged against exactly that.

## Findings, ranked by blast radius

Blast radius order: agent-context paths (what a model reads and cannot
re-request) first; operator/diagnostic paths second; web-only presentation
third.

### Tier 1 — agent context is clipped, loss is silent or uncounted

| # | Site | Verbatim | What is clipped | Loss | Disposition |
|---|---|---|---|---|---|
| 1 | `src/my/note.clj:266` (with `:22`) | `(take notes-limit)` where `(def ^:private notes-limit 50)` | An agent's own notes list. Note 51+ vanish from `my.note` listing. | **SILENT** — no `omitted`, no total, no marker. The agent cannot know notes exist. | Emit an elision value beside the page (`my.plan.clj:810-818` is the in-repo template: `omitted` + `:my.plan/older-completions`) and declare the 50 as a config fact. |
| 2 | `src/my/message.clj:68-70` (with `:22`) | `(str (subs content 0 (dec preview-limit)) "…")`, `(def ^:private preview-limit 160)` | Every message body in an agent's message listing. | Marked with a bare `…`; no original length, no total, no requery identity. The agent sees a cut but not how much or how to get it. | Replace with an elision value (retained/omitted/total + the `:my.message/id` requery identity that is already in the row); declare 160 as a config fact or derive it from the render profile. |
| 3 | `src/seon/sci/eval.clj:299-308` | `(subs text 0 limit)` | Everything the agent's form PRINTED (`*out*`), capped by `:seon.config.eval.result/max-string`. | **SILENT** — the cap is a declared config fact (good) but the return is a bare prefix string with NO marker at all. Printed output just stops. | Route the captured output through the floor so the cut becomes a counted elision. The cap is already declared; only the honesty is missing. |
| 4 | `src/seon/effect.clj:49-56` → `src/seon/ai/tokens.cljc:159-173` | `(tokens/clip-str payload preview-tokens)` → `(str (subs text 0 character-limit) "…")` | The payload preview of every effect receipt an agent reads (`payload-face`). | Marked `…`, uncounted. The width comes from `(:seon.print/width (print/default-options))` — a print DEFAULT repurposed as a context budget, not a render profile. | Delete `clip-str` from the production path; render the payload through the floor with the receipt's own identity as requery root. `clip-str` itself has exactly one production caller — the whole helper can go. |
| 5 | `src/seon/render/ns.clj:234-240` | `(str (subs text 0 (max 0 (- limit (count marker)))) " [clipped]")`, called at `:393` and `:409` with a hard `78` | Namespace-page function docstrings and summaries — agent-facing `/ai` program context. | Marked with an invented `[clipped]` token, uncounted, unrequeryable, and it first rewrites any real `…` to `...` so the elision vocabulary is destroyed on the way through. | Delete `soft-clip`. Same file already does this correctly at `:337-341` and `:587-609` (see compliant section). The `78` is a hand constant, not a profile fact. |
| 6 | `src/seon/render/agent.clj:42-43` | `[:p (first (str/split-lines text))]` | The agent status section: everything after line 1 of `agent-ai` is dropped in the HTML projection. | **SILENT** structural drop — no marker, and the two projections disagree about what the same block contains. | Render the full text, or emit the remainder behind an elision value. A projection that shows strictly less than its sibling with no marker is the §2.4 violation in its purest form. |

### Tier 2 — operator and diagnostic paths, declared cap but dishonest cut

| # | Site | Verbatim | What is clipped | Loss | Disposition |
|---|---|---|---|---|---|
| 7 | `src/seon/cluster.clj:1995-1999`, used at `:2010-2022` and `:2113-2124` | `(str (subs value 0 (dec limit)) "…")` | Core fault messages and fault-carried message content, both on the durable transaction path and on the stderr `SEON CORE FAULT` line. | Marked `…`; the cap IS a declared fact (`:seon.config.eval.result/blob-threshold`), but the original length is discarded, so a truncated fault cannot be recognized as truncated by a query. | Keep the config fact; make the cut an elision value carrying `original` so a fault's completeness is queryable. This is the one that hurts most in diagnosis — a fault is exactly the value you cannot re-request. |
| 8 | `src/seon/flow.clj:1093-1096` | `(subs text 0 (min 160 (count text)))` | The name of a value lost by the fault committer's own last-resort path. | **SILENT** and the `160` is a bare magic number in the one mechanism that exists to report that reporting failed. | Declare the bound or route through the floor. A silent cut inside the last-resort reporter is the recurring "absence of signal reads as health" class. |
| 9 | `src/seon/ai.clj:706-708` and `src/seon/ai.clj:1386-1387` | `(subs payload 0 (min 500 (count payload)))` / `(subs text 0 (min 500 (count text)))` | The provider's error body on `::unparseable-body` and `::provider-error`. | **SILENT**, magic `500`, twice, and both land in `:seon.error/data` that an agent and the operator both read when a provider misbehaves. | One declared cap, one elision value. Two copies of the same constant in one namespace is also the §2.5 duplicate-mechanism smell. |
| 10 | `src/seon/test/runner.clj:48-57`, used at `:61` and `:104` | `(str (subs text 0 (- max-chars (count suffix))) suffix)` with `suffix "\n... additional failure output elided by bin/test"` | Test failure output in the one correctness gate's report. | Marked in prose, uncounted, and the cap borrows `:seon.config.eval.result/blob-threshold` — a BLOB routing threshold reused as a display bound. | Lowest priority (gate-internal, not agent context), but the borrowed dial should become its own declared fact, and the marker should carry the omitted character count so a reader knows whether to go get the rest. |
| 11 | `src/seon/edit.clj:443` | `:my.edit/actual-window (subs actual 0 bounded-end)` | The mismatch window `my.edit` shows an agent when a replace target does not match. | Bounded window is the intent (this is a WINDOW, not a truncation), but no `total`/`omitted` accompanies it. | Lightest touch: attach the window's own offset/total the way `seon.render.value/window` does. Classify as accretion, not a rip-out. |
| 12 | `src/seon/sci/reader.cljc` private 1 MiB source cap | see `docs/seon/issues/sci-reader-hides-a-production-source-cap.md` | Model reply source and evaluation source. | Silent, private constant. | Already filed; this census confirms it is a member of the same class and it belongs in the same regression. |

### Tier 3 — CSS clipping (web-only, but content-hiding is clipping)

CSS that hides content is clipping outside the render function exactly as much
as `subs` is: the render function produced honest, complete data and the
stylesheet threw part of it away with no marker and no scrollbar. All line
references are `resources/public/css/input.css` (`output.css` is generated).

| # | Site | Verbatim | What is hidden | Loss | Disposition |
|---|---|---|---|---|---|
| 13 | `:1153-1158` (`.seon-rank-rail`, `.seon-rank-deep`) | `max-height: 10rem; overflow: hidden;` | Walk units on the namespace/agent rank pages. Measured live 2026-08-14: 55 of 138 units clipped on `/` and `/agent/root`; 11 of 38 on a drive agent page, worst case ~80% of a unit's content unreachable. | **SILENT** — no scrollbar (`overflow-y: hidden`), no affordance, no count. | Already filed as a blocker: `docs/seon/issues/walk-units-hide-their-overflow-instead-of-eliding-it.md`. The fix is to bound the CONTENT in the renderer with an elision value, not the BOX in CSS. |
| 14 | `:473-477` (`.seon-card-compact`) | `max-height: 10rem; overflow: hidden;` | Any agent-authored card face taller than 10rem in the compact/grid presentation. | **SILENT**. The comment is explicit that it clips ("whichever bound is hit first clips"). | Same class as #13. The expanded variant is unbounded, so the compact face should carry a counted elision plus an expand affordance rather than a hidden overflow. |
| 15 | `:549-558` (`.seon-card-reply`) | `-webkit-line-clamp: 3; line-clamp: 3; overflow: hidden;` | The agent's last reply on the default root canvas, past 3 lines. | **SILENT**. Line-clamp draws its own ellipsis in some engines and nothing in others; either way there is no count and no link to the whole reply. | Same class. The reply already has a durable identity — clamp with a marker that links to it. |
| 16 | `:536-542` (`.plan-title`, `.plan-goal`) | `-webkit-line-clamp: 2 / 1; overflow: hidden;` | Plan item titles and goals. | **SILENT** past 2 / 1 lines. | Same class, lower severity (the tree below carries the detail). |
| 17 | `:512-515` (`.agent-activity > summary`) and `:1017-1021` (`.seon-attempt-reasoning > summary`) | `white-space: nowrap; overflow: hidden; text-overflow: ellipsis;` | The one-line summary of a `<details>`. | **NOT a content loss** — both have an open/expanded state that reveals the full text (`.agent-activity[open] > summary { white-space: normal; }`; `.seon-attempt-reasoning-body pre { white-space: pre-wrap; }`). | **Compliant-by-disclosure.** Classify separately from the hiding clips: nothing is unreachable. Keep. |
| 18 | `:1131-1152`, `:544-546` (`.seon-rank-layout`, `.seon-rank-primary`, `.plan-tree`) | `max-height: …; overflow: auto;` | Nothing. | **NO LOSS** — `overflow: auto` scrolls. | Keep. This is the correct CSS shape and the exact one-word difference (`auto` vs `hidden`) that separates #13/#14 from a legal bound. |
| 19 | Debug pane `:1200+` (`.seon-body:has(.seon-debug)`, `.seon-main:has(.seon-debug)`) | `overflow: hidden` on the page chrome | Nothing — scrolling is deliberately delegated to the two projection pane bodies. | **NO LOSS**, layout only. | Keep. Noted separately per the brief: this is layout ownership, not clipping. |

### Adjacent, already owned by another lane

- **The fence-splitting truncation.** The block-coverage lane owns this; cited,
  not duplicated. Note for that lane: `src/seon/cluster/reply.clj:98-116`
  (`unfenced`) is the correct half — it RETAINS outside-fence Markdown as
  prose comments rather than dropping it, and this census found no drop there.
  Whatever the lane is fixing sits downstream of that function, not in it.
- **Private render token dials.**
  `docs/seon/issues/render-token-budgets-are-private-dials-no-producer-supplies.md`
  — `::token-budget` in `src/seon/render/transcript.clj:813` and
  `src/seon/render/ns.clj:323-326` is not a config fact and no producer sets
  it, so the transcript's budget defaults to `0` and renders nothing but its
  elision marker while `ns`'s defaults to nil meaning unbounded. That is the
  same law from the other side: the bound is honest but the FACT is missing.
- **Prompt captures bypassing the blob splitter.**
  `docs/seon/issues/context-capture-prompts-bypass-the-blob-splitter.md` — not
  a clipping defect, but the same "one owner, consulted by everyone" shape.

## The compliant pattern, for contrast

These are the sites a rip-out must NOT touch, and the shapes every fix should
converge on.

1. **`src/seon/render/ns.clj:337-341, 470-478, 587-609`** — the namespace
   render budget. It emits a real elision node:
   `{:seon.print/face :seon.print/elided, :seon.print/omitted omitted,
   :seon.render.data/total (+ offset omitted)}` and the honest prose
   `"… omitted by the namespace render budget."` at `:364`. Count present,
   total present, face is the declared elision face. **LEGAL.** Note the
   irony: the same file's `soft-clip` (#5) is the illegal twin of this,
   twelve lines away.
2. **`src/seon/render/walk.clj:245-270`** — collection width from
   `:seon.config.eval.result/max-collection` (a declared config fact),
   with an explicit `elided?` unit naming the attribute and saying
   `"at the configured collection cap"`. **LEGAL.**
3. **`src/seon/render/walk.clj:494-509, 532-545`** — the distance cap unit
   ("elided connections at the requested distance cap") and `max-nodes` from
   config. **LEGAL.**
4. **`src/seon/render/transcript.clj:559-563, 603, 744`** — everything goes
   through `floor-text`, and the entry-count cut says
   `"N entries elided by the token budget."` **LEGAL** in mechanism (the
   budget FACT is the open issue, not the shape).
5. **`src/my/plan.clj:810-818`** — `(take limit ordered)` where `limit` comes
   from `completion-limit`, followed by `omitted (- total (count recent))` and
   an `:my.plan/older-completions` continuation. **LEGAL**, and it is the
   template finding #1 should be rewritten into.
6. **`src/seon/sci/admit.clj:236-244`** — the admission cap emits
   `::print/truncated-string` with `::print/length`, which `enrich-node`
   converts to a counted elision. **LEGAL** — this is why `admit` may `subs`
   and `eval` (#3) may not.
7. **`src/seon/shell/jvm.clj:299-317`** — `preview-bytes` and
   `inline-output-bytes` are both declared config facts, and the descriptor
   carries `:my.shell.output/bytes` (the true size),
   `:my.shell.output/preview-complete?` (whether it is a preview at all), and
   `:my.shell.output/blob` (the requery identity). **LEGAL, and the best
   example in the tree** of a bounded preview that stays honest.
8. **`src/seon/render/transcript.clj:701-707`** (`reasoning-disclosure`) —
   `(str first-line "…")` inside a `<summary>` whose `<details>` body carries
   the complete reasoning. **LEGAL by disclosure**, same as CSS #17.

## Not clipping — checked and cleared

To keep a future regression from over-firing, these `subs` uses are ordinary
string parsing, identity derivation, or byte arithmetic, and are not outward
bounding: `src/seon/config.clj:50` and `src/seon/render/value.clj:67` and
`src/seon/test/accretion.clj:180` and `src/seon/eval/drive.clj:378-391` and
`src/seon/bootstrap_drive.clj:315-397` (digest/uuid prefixes for identities);
`src/seon/schema.clj:705,761` and `src/seon/schema/edn.clj:174-261` and
`src/seon/schema/admission.clj:210` (path and munged-name parsing);
`src/seon/web/jvm.clj:244-266` (header/quote parsing);
`src/seon/render/hiccup.clj:301,317` (tag shorthand scan);
`src/seon/sci/reader.cljc:535-540` (source span extraction);
`src/seon/cluster/reply.clj:194-329` (prose/code boundary);
`src/seon/edit.clj:35-364` (edit windows and splice arithmetic);
`src/seon/maintenance.clj:341`, `src/seon/fn.clj:123`,
`src/seon/render/block.clj:82`, `src/seon/sci/admit.clj:267` (name derivation).

## Unconstructability endgame

The class regression should make clipping-outside-the-owner **unconstructable**,
not merely detected, and it should follow the template of the await/lock census
regressions (`docs/prds/sci-execution-runtime/research/unbounded-await-census-2026-08-13.md`)
— a query over facts we already have, failing loudly on a NEW member, and
reporting a typed refusal rather than silence when its subject is absent.

Three layers, in the order they should land:

**Layer 1 — a program-graph query, not a regex.** The banned substitutes are
named in §2.2, so the checker must not grep for `subs`. The system already has
`:seon.fn/calls` edges and the `:seon.fn/external-sink` /
`:seon.fn/projection-boundary` leaf facts, and `seon.fn/output-path-report`
already derives projected / bypass / unresolved paths
(`resources/seon/schemas/seon.fn.edn` ↔ `src/seon/fn.clj`). The regression is:
**every function reachable from a declared render producer or an agent-facing
`my.*` read must reach `seon.print/fit` or `seon.render.value/window` on its
outward path, or be declared a projection boundary.** A function that calls
`clojure.core/subs` on its outward path without reaching the owner is a BYPASS
path, which `output-path-report` is already shaped to report. This turns the
whole Tier 1/Tier 2 table into a derived list that cannot go stale — the
derive-or-die law applied to the census itself.

**Layer 2 — make the honest cut the only available one.** The reason `subs`
keeps reappearing is that there is no cheap function to call instead. The
rip-out should ship ONE agent-facing bounded-text function that takes a value,
a profile or config fact, and a requery identity, and returns an elision value
— then every Tier 1/Tier 2 site becomes a one-line call and the hand `subs`
has nothing to be convenient about. `seon.shell/output-descriptor` (compliant
#7) is the shape to generalize. No new mechanism: it is `fit` plus the
identity the caller already holds.

**Layer 3 — CSS is checked, not trusted.** The CSS clips cannot be caught by a
program-graph query. The live-browser check the walk-units issue already
performed is the regression: for every rendered block on `/` and
`/agent/{id}`, assert `scrollHeight <= clientHeight` OR the computed
`overflow-y` is `auto`/`scroll` OR the element carries a declared elision
marker. That assertion reports honestly when its subject is absent (zero
blocks found is a FAILURE, not a pass) — which is the specific trap §2.3 names
and the reason the 160px clip survived this long.

The health metric for this class is not "zero `subs` in `src/`" — it is **one
regression per layer, and a derived member list that a new bypass cannot join
quietly.**

## Issue updates

Fresh evidence was added to the overlapping open issues rather than filing
duplicates; `index.md` was not touched (owner-ranked, lanes do not edit it).

- `walk-units-hide-their-overflow-instead-of-eliding-it.md` — cross-referenced
  as census member #13, with siblings #14/#15/#16 named as the same class in
  the same stylesheet, and #17/#18 named as the compliant contrast.
- `sci-reader-hides-a-production-source-cap.md` — cross-referenced as census
  member #12.
- `render-token-budgets-are-private-dials-no-producer-supplies.md` —
  cross-referenced as the missing-FACT half of the same law.
