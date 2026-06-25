---
type: research
status: active
tags: [research, agent]
---

# Section-quoting unification — survey + proposal

## TL;DR

The agent context is meant to read as one eval'able Clojure source: every
non-form line is a `;;` (or `;;;`) comment. But the work of turning
prose/markdown/content into `;;`-prefixed text is done by **at least eight
independent, hand-rolled mechanisms** scattered across `seon.ctx` and the
`seon.ctx.*` section namespaces. They each re-implement some slice of "put
`;;` (or `;;;`) in front of every line", with subtly different rules
(line-marker stripping, blank-line handling, `;;` vs `;;;`, trailing-space
trimming). Only ONE of them — `seon.ctx/comment-lines` +
`strip-comment-prefix` — is a reusable prefixer, and it is `private`, so no
other ns can call it. The new `file-section` (Part A of this task) added a
ninth: `comment-markdown`.

**Proposal:** promote a single PUBLIC section-quoting utility to `seon.ctx`
(`quote-lines` for `;;` body text, `runtime-lines` for `;;;`
runtime-structure text, and a `section-bracket` for the `;;; ┌─ … ─`
demarcation), each with one explicit blank-line + marker-stripping
convention, and route every section through it. Delete the eight ad-hoc
copies. This is the "don't be a dumbass / one mechanism" rule applied to
section quoting.

This document is **Part B survey + proposal only** — the merge of the
other mechanisms is an owner-reviewed follow-up, NOT shipped here. Part A
(folding the file-section loader into `seon.ctx`, killing the soul-named
code) is the only code change that shipped with this doc.

## Inventory — every place that emits `;;`/`;;;`-prefixed text

| # | Mechanism | Lives in | What it does | How it differs |
|---|-----------|----------|--------------|----------------|
| 1 | `comment-lines` + `strip-comment-prefix` | `seon.ctx` (`defn-`, ~ln 416-450) | The closest thing to a shared `;;`-prefixer: strips a leading `;`/`⚠`/`↻`/`=>` marker per line, then re-emits `;; <text>`. Drops blank lines; preserves `↻`/`⚠` glyph lines. | The ONLY reusable one — but PRIVATE, so nothing outside `seon.ctx` can call it. Removes blank lines (not blank-preserving). |
| 2 | `error-lines` | `seon.ctx` (`defn-`, ~ln 452-477) | Renders an error body as the REPL FAILURE shape: first line `;;=> ✗ <headline>`, continuation lines `;; <text>` with indentation PRESERVED (caret alignment). | Its own marker-stripping regex set (`;+`, `ERROR`, `⚠`/`✗`); keeps interior indentation (the others trim). |
| 3 | `comment-markdown` (was `seon.ctx.doc/comment-markdown`) | `seon.ctx` (`defn-`, file-section utility) | Markdown file text → `;; <line>`, blank lines → bare `;;` (byte-stable, no trailing space). | Blank-line PRESERVING (#1 drops them); no marker stripping (raw markdown). The Part-A fold. |
| 4 | `section-bracket-ai` | `seon.ctx` (`defn-`, ~ln 1751) | Wraps each rendered child section in `;;; ┌─ <name> ─` / `;;; └─ end <name> ─`. The self-demarcating boundary that replaced per-section `;; ── x ──` headers. | `;;;` runtime-structure glyphs (`┌─`/`└─`), not body text. The ONE section-demarcation primitive. |
| 5 | `system-text` | `seon.ctx` (`def`, ~ln 766+) | The whole hardcoded system block, authored as a giant string literal where EVERY line is manually typed with a leading `";;; "` (or `";; "` for the worked DB examples). | Hand-typed `;;;` per line in source — no fn at all. ~250 lines of literal `;;;` strings. |
| 6 | `namespaces-section` header + `;; ── namespace x ──` blocks | `seon.ctx.namespaces` (~ln 211, 234, 252) | The namespaces body: a `;;`-commented header string + a `;; ── namespace x ──` block label per ns (emitted by `render-namespace`). | Hand-typed `;;` header; `;; ── x ──` block labels are a SECOND demarcation convention competing with #4's `;;; ┌─ … ─`. |
| 7 | `inventory-section` | `seon.ctx.inventory` (~ln 43, 178-183) | Stored-data inventory: a `;;`-commented header + one `;; <kind>: …` line per kind, each built with `(str ";; " …)`. | Inline `(str ";; " …)` per line; no shared helper. |
| 8 | `your-entity-section` | `seon.ctx.your-entity` (~ln 37-60) | The agent's pulled entity map as `;;` comment lines via `(map #(str ";; " %) …)`. | Inline `(map #(str ";; " %))`; its own header strings. |
| 9 | `warnings-section` | `seon.ctx.warnings` (via `seon.warn`) | Current problems as a `;;; ── WARNINGS ──` comment-block. | `;;;` block; built in `seon.warn`'s check registry, a THIRD home. |
| 10 | transcript lines: masthead, `;;; ◀/▶` message lines, `;;; ── turn N ──` headers, `format-eval-row`'s `;;`/`;;=>` rows, `resume-marker` | `seon.ctx.transcript` (masthead ~ln 70, message line ~ln 159, turn header ~ln 316, resume ~ln 86) + `seon.ctx/format-eval-row` | The transcript's many runtime-structure lines, each hand-built with `(str ";;; " …)` / `(str ";; " …)`. `format-eval-row` (in `seon.ctx`) calls #1 (`comment-lines`) for the preamble but hand-rolls the `;;=>` result/error lines. | The richest set; mixes `;;`, `;;;`, `;;=>`, `;;; ◀`, `;;; ▶`. Uses #1 for ONE part (preamble) and hand-rolls the rest. |

### Cross-cutting observations

- **Two competing section-demarcation conventions live side by side:** the
  new `;;; ┌─ <name> ─ … └─ end <name> ─` brackets (#4, the ROOT
  renderer's per-child wrap) AND the older `;; ── namespace x ──` /
  `;;; ── WARNINGS ──` / `;; ── stored data inventory ──` block labels
  (#6, #9, inventory). Several of the in-body `── x ──` labels are now
  redundant with the outer bracket (the keystone commit already removed
  the `;; ── your entity ──` and `;; ── stored data inventory ──` and
  `;; ── relevant context ──` headers — see `ctx_test` comments — but
  `namespaces` still emits `;; ── namespace x ──` per ns and `warnings`
  still emits `;;; ── WARNINGS ──`).
- **`;;` vs `;;;` is used inconsistently as a "body vs runtime-structure"
  signal** but the rule is nowhere stated or enforced: `system-text` and
  the transcript runtime lines use `;;;`; body content (`comment-lines`,
  `comment-markdown`, inventory, your-entity) uses `;;`; warnings uses
  `;;;` for a body block. A reader cannot rely on the level meaning
  anything.
- **Blank-line handling diverges:** #1 DROPS blank lines, #3 PRESERVES
  them as bare `;;`. For a byte-stable cache prefix the preserve-bare-`;;`
  rule is the correct one (no trailing space, stable between renders);
  #1's drop-blanks is a latent inconsistency.
- **Marker-stripping diverges:** #1, #2, and #10 each carry their own
  regex for "strip a leading `;`/`⚠`/`↻`/`=>`/`ERROR`"; #3 strips nothing.
- **The one reusable prefixer is private.** `comment-lines` /
  `strip-comment-prefix` are `defn-` in `seon.ctx`, so the section nses
  (`seon.ctx.inventory`, `seon.ctx.your-entity`, `seon.ctx.namespaces`)
  CAN'T call them — they re-roll `(str ";; " …)` instead. That privacy is
  the structural reason the duplication exists.

## Proposal — ONE section-quoting utility, three primitives

Promote a single, PUBLIC, documented set of primitives in `seon.ctx`
(the composer already owns section schemas + the file-section utility, so
it is the right home), and route every mechanism above through them. No
new namespace — a UTILITY in `seon.ctx`, mirroring how Part A folded the
file-section loader in.

### The three primitives

```clojure
;; 1. Body text → `;;` comment lines. ONE explicit convention:
;;    - blank source line  → bare ";;"  (no trailing space, byte-stable)
;;    - non-blank line      → ";; " + (strip-leading-marker line)
;;    - a `↻`/`⚠` breadcrumb line keeps its glyph (";; ↻ …")
;;    `:preserve-indent?` opt for the error/caret case (#2).
(defn quote-lines
  "Render `text` as reader-valid `;;` comment lines — the ONE body-text
   quoter every section routes through."
  ([text] (quote-lines text {}))
  ([text {:keys [::preserve-indent? ::strip-markers?] …}] …))

;; 2. Runtime-structure text → `;;;` lines. Same body rules, three
;;    semicolons. The `;;;` level now MEANS "runtime authored this,
;;    not the agent" — enforced in one place.
(defn runtime-lines [text] …)

;; 3. Section demarcation — the ONE bracket (subsumes #4 and retires the
;;    in-body `── x ──` labels #6/#9/inventory).
(defn section-bracket [section-name body] …)   ; ";;; ┌─ name ─ … └─ end name ─"
```

### Migration sketch (the follow-up, NOT shipped here)

1. **Make the existing `comment-lines`/`strip-comment-prefix` public and
   rename to `quote-lines`/`strip-comment-prefix`**, adopting the
   blank-PRESERVING rule (#3's, the cache-correct one) and an
   `::preserve-indent?` option to absorb #2 (`error-lines`). Keep the
   `↻`/`⚠` glyph carve-out.
2. **Delete `comment-markdown` (#3)** — `(quote-lines md-text)` replaces
   it; the file-section's `file-section-ai` calls `quote-lines`.
3. **Rewrite `error-lines` (#2)** as a thin caller:
   `(quote-lines body {::preserve-indent? true})` + the `;;=> ✗` headline
   prefix it adds itself (the headline shape is error-specific, stays).
4. **Convert the section nses (#6/#7/#8) to call `quote-lines`** instead of
   inline `(str ";; " …)` / `(map #(str ";; " %))`. They already require
   `seon.ctx` for the shared read API, so no new cycle.
5. **Route runtime-structure lines (#5 system-text authoring helper, #9
   warnings, #10 transcript masthead/turn/message lines) through
   `runtime-lines`** where they are computed; `system-text` stays a
   literal `def` (it's a fixed artifact, not computed) but any FUTURE
   computed `;;;` block uses `runtime-lines`.
6. **Retire the in-body `── x ──` labels (#6 namespaces, #9 warnings,
   inventory)** in favor of the outer `section-bracket` (#4) — the
   keystone already did this for your-entity/inventory/relevant; finish
   the job for namespaces + warnings so there is ONE demarcation
   convention, not two.
7. **Pin one `;;` vs `;;;` rule** in a docstring on `runtime-lines`:
   `;;;` = runtime-authored structure (headers, brackets, result lines,
   message lines); `;;` = body content (the agent's own comments,
   docstrings, rendered file/inventory/entity text). Enforced by which
   primitive a caller picks.

### Why this is safe / cheap

- Every section already produces a string; this swaps the
  string-BUILDING, not the section contract or the render dispatch.
- The byte-stable-prefix cache contract is HELPED, not risked: one
  blank-line rule (preserve bare `;;`, no trailing space) replaces two
  divergent ones.
- It is an atomic in-place refactor (the whole repo is a feature branch),
  not a `*-v2` parallel path.

## Scope note

Part A SHIPPED with this doc: the file-section loader is folded into
`seon.ctx` as the `file-section` utility (path attr `:seon.ctx/file-path`,
slots `file-section-ai`/`file-section-html`, plus `identity-files-text`);
`src/seon/ctx/doc.cljs`, `test/seon/ctx/doc_test.cljs`, `src/my/soul.cljs`,
and `test/my/soul_test.cljs` are DELETED; no namespace or file named
soul/SOUL/AGENTS/doc remains. Part B (this unification) is the
owner-reviewed follow-up and is NOT executed here.

---

## EXECUTION MAP (complete inventory + OWNER-LOCKED convention, 2026-06-25)

Exhaustive read-only sweep found **13** quoting mechanisms (the original survey's
10 + `live-tile`, `relevant-source`, and split header/body cases).

**LOCKED convention (owner decision — overrides the `;;`-body proposal above):**
- **Body lines = SINGLE `;`** (owner chose single over `;;` for token-minimal).
- **Section header/footer = `;;; ┌─ <name> ─` / `;;; └─ end <name> ─`** — the ONE
  demarcation primitive (today's `section-bracket-ai`, ctx.cljs:1753).
- **ONE public body primitive in `seon.ctx`** (`quote-lines` → single `;`,
  blank → bare `;` no trailing space for byte-stability, optional `:strip-markers?`
  to drop leading `;`/`⚠`/`↻`/`=>`); everything routes through it.

| # | Mechanism | file:fn | now | disposition (single-`;` convention) |
|---|---|---|---|---|
| 1 | `comment-markdown` | ctx.cljs:160 | `;;` body, blank-preserving | PROMOTE → public `quote-lines`, switch to single `;` |
| 2 | `comment-lines`+`strip-comment-prefix` | ctx.cljs:550 | `;;`, DROPS blanks | DELETE → merge into `quote-lines` (adopt blank-preserve) |
| 3 | `error-lines` | ctx.cljs:586 | `;;`+`;;=>` | KEEP thin → `quote-lines` + the `;=>` result headline |
| 4 | `section-bracket-ai` | ctx.cljs:1753 | `;;; ┌─/└─` | KEEP — THE canonical demarcation |
| 5 | `system-text` literal | ctx.cljs:900-1163 | hand-typed `;;;` per line | **CONVERT to single `;` body** (owner called this out — NOT keep) + bracket for demarcation |
| 6 | `namespaces-header` | namespaces.cljs:210 | `;;` body | route → `quote-lines` (single `;`) |
| 6b | `render-one-ns-ai` labels | ctx.cljs:1352,1366 | `;; ── x ──` | DELETE (redundant with #4) |
| 7 | `inventory-section` body | ctx/inventory.cljs:183 | inline `(str ";; " …)` | route → `quote-lines` |
| 8 | `your-entity-section` body | ctx/your_entity.cljs:58 | inline `(map #(str ";; " %))` | route → `quote-lines` |
| 9 | `render-warnings` | warn.cljs:1006,1022,1061 | mixed `;;;`/`;;` + `── WARNINGS ──` | DELETE the label; route body → `quote-lines` |
| 10 | `transcript-section` | ctx/transcript.cljs:86,316-317,… | `;;;`/`;;`/`;;=>` + in-body `── x ──` | DELETE 5 in-body labels; route body → `quote-lines`; turn/session markers → bracket or single-`;` |
| 11 | `live-tile-section` body | ctx/live_tile.cljs:88 | inline `(map #(str ";; " %))` | route → `quote-lines` |
| 12 | `relevant-source-section` body | ctx/relevant.cljs:42,64 | inline `(str ";; " …)` | route → `quote-lines` |

**The 5 redundant in-body `── x ──` demarcation labels to DELETE** (Convention B,
superseded by the `;;; ┌─/└─` bracket): ctx.cljs:1352, ctx.cljs:1366,
warn.cljs:1061, transcript.cljs:316-317, transcript.cljs:86.

**Inconsistencies to fix:** blank-line handling (#1 preserves as bare comment, #2
DROPS — adopt preserve, byte-stable); marker-stripping (3 different regex sets →
one `:strip-markers?` option); the duplicate inline `(str ";; " …)` /
`(map #(str ";; " %))` patterns (#7,#8,#11,#12 → all call `quote-lines`).

**`render`-side note:** the section renderer (`section-bracket-ai`) already brackets
EVERY section uniformly at render time — so the per-section in-body labels are pure
duplication. Keep the bracket, delete the labels.

NOTE the `transcript.cljs` line refs may have shifted (P1 rewrote it); the #22
executor greps for the real sites. system-text is the owner's headline gripe —
convert it to single `;` body.
