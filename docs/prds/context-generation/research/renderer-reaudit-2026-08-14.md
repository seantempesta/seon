---
type: research
status: current
tags: [research, render, context, audit]
---

# Renderer re-audit — four independent lanes, 2026-08-14 (evening)

Purpose: the owner ordered a hard, sober audit of the whole render system
before any one-renderer wave starts, suspecting the earlier censuses
under-scoped the rot. Four independent read-only lanes swept the tree at
the current working state (branch `context-generation-drive`, after the
`clip-ripout` boundary-conversion commits): (1) every `:seon.render/ai`
face, (2) `seon.print` + the selection chain internals, (3) the HTML
seam, (4) a truncation-fingerprint grep over `src/`. Claims marked ✓
were re-verified at the bytes by the orchestrator.

The one-line verdict: **the written architecture holds at the floor and
fails at every mount point.** The ingredients the PRD needs all exist —
the tee'd two-sink emitter, the composing nested walk, the elision
value, the four-step chain — and the code defeats each one where it is
attached. This is re-plumbing, not invention.

## 1. What the PRD register already has right (confirmed)

- Rip-out #6 (function-side bounding) is largely LANDED by the in-flight
  clip-ripout work: the fingerprint lane found the tree far cleaner than
  the 16-violation census — ~24 residual violations across 9 files,
  mostly pre-law `def ^:private <n>` constants (§3 below).
- Rip-out #8 (`subs` inside the fit owner) confirmed live at
  `src/seon/print.cljc:835` ✓ (`bounded-text`, reached via
  `projected-text` which `pr-str`s non-string values).
- Rip-out #11 (silent unprojected-node return) confirmed at
  `src/seon/render.clj:468-474`.
- The exemplary sites the census praised are real:
  `seon.render.ns/budgeted-ai` fit mechanics, `my.plan` config-derived
  windows with elision values, `seon.render.value/prepare`.

## 2. What the earlier censuses missed — new findings

### 2.1 The floor printer itself bare-truncates by default (worst new find)

`seon.print` has TWO elision paths and the bad one is the default.
`default-options` derives `:seon.print/length 32` / `:seon.print/level 8`
from `resources/seon/schemas/seon.print.edn`; with those set, the
emitter emits literal `"..."` and `"#"` tokens — no count, no path, no
requery — at `print.cljc:383, 392, 430, 453, 488, 497, 542, 557`.
Exactly ONE call site nils them out (`render/value.clj:505-507`). Every
direct `emit-text` caller gets bare truncation:
`cluster.clj:278`, `transcript.clj:614`, `eval/drive.clj:137`,
`sci/eval.clj:1030`. The comment at `print.cljc:417-420` records this
class being diagnosed and fixed for map entries ONLY.

### 2.2 Bare elision markers render as fabricated sentences

`seon.sci.admit` emits `{:seon.print/face :seon.print/elided}` with no
other keys (`admit.clj:107-118, 134-140`), and the node grammar in
`seon.print.edn` blesses the bare form. `enrich-elisions` (the upgrade
to real elision values) has ONE caller (`render/value.clj:503`). Any
other path renders the bare marker through `render-elision-ai` defaults,
producing a confidently WRONG sentence ("… 1 more subtree … at path []
offset 0") — omitted count and path are fabrications.

### 2.3 Two resolution chains; the documented one is mostly three steps

- The four-step chain exists once, at `seon.render/producer`
  (`render.clj:301-320`), with honest ambiguity errors.
- Step 2 (owning-namespace contract fit) requires
  `:seon.render/namespace`, supplied by only FOUR call sites repo-wide ✓:
  `walk.clj:566, 573`, `transcript.clj:577`, `bootstrap.clj:223`. Web
  pages, `/data`, MCP, and every nested value never run it.
- The nested walk uses a DIFFERENT chain: `project-node*`
  (`render.clj:457-459`) is explicit → schema-attached only — no
  namespace fit, no per-node floor.
- Floor identity is name-based: `floor-producer?` (`render.clj:172-176`)
  is a hardcoded two-symbol set gating argument shape and evidence
  flags; `render.clj:296-298, 317-320` hardcode the floor symbols.

### 2.4 Nested composition exists but is mounted UNDER the floor

`project-node*` genuinely composes: it detects nested schema'd values in
the admitted tree, invokes their specialist, and splices the result as a
`:seon.print/projected` node both sinks render in place. But
`seon.render/project-node` has ONE production caller —
`render/value.clj:494` ✓, inside the floor. So composition happens only
when the top value had NO specialist; a selected specialist's output is
terminal (`render.clj:501-502`) and swallows the whole subtree.
`seon.error/render-ai` — the declared default across the error classes —
flattens arbitrary nested evidence with raw `pr-str` (`error.clj:1019`).

### 2.5 `fit` runs twice on the floor path, with different semantics

`prepare` applies structural fit (`value.clj:504`), then `fit-terminal`
(`render.clj:524`) fits AGAIN character-wise on the flattened output.
For HTML the second pass measures/chops `pr-str` of the hiccup
(`print.cljc:829-836` ✓), so an over-budget, correctly-elided HTML tree
degrades to a mid-string chop of its own markup inside an elision span.
`test/seon/print_test.clj:255-269` locks this behavior in.

### 2.6 Six bounding owners

`print/fit`; admission caps (`admit.clj:213`); the transcript's private
token loop (`transcript.clj:806`); `render/ns.clj`'s `within-budget?`
(`:318`); the prompt's distance-decrement loop (`prompt.clj:225-272`,
whole branches vanish with no elision value); and the emitter's
`::length`/`::level` path (§2.1). The bootstrap intent-admission loop
(`bootstrap.clj:396-409`) is a seventh budget loop, config-derived and
acquisition-scoped — flagged for multiplicity, not as a hack.

### 2.7 The transcript's `:summary` tier is a provable no-op

All four text producers ignore their `detail` argument
(`transcript.clj:584, 625, 636, 667`), so `:summary` output is
byte-identical to `:full` and `best-summary` (`transcript.clj:804`)
re-tests the identical candidate and always fails once `:full` failed.
There is no summarization — only clipping to elided — behind an API
shaped like curation. The namespace docstring claims otherwise.

### 2.8 The transcript renderer overrides its consumer's budget

`transcript.clj:832`: `budget (max requested minimum)` — a render
function raising the budget it was handed.

### 2.9 The HTML seam is largely a wrapper around AI text

- The agent transcript — the main content of every agent page — is the
  AI prose in a monospace box: `[:pre [:code (::text entry)]]`
  (`transcript.clj:774` ✓), with `floor-text` calling `value/render-ai`
  for BOTH outputs (`transcript.clj:550`); there is no `floor-html`.
- `web.clj:499` pastes `:seon.render.history/printed-value` (AI text)
  into HTML; `web.clj:803, 835` assemble the debug prompt by string
  concat into a `<pre>`.
- The floor's own schema-recognizing layout path (`layout-emission`,
  `value.clj:413-438`) builds `[:dl]`/`[:ol]` out of `emit-text` STRINGS
  and discards the `HiccupSink` — the structural browser is skipped
  precisely when a value matches a registered schema.

### 2.10 HTML markup bytes evict prompt content

`transcript.clj:792-799`: `output-tokens` takes the MAX of the AI text
estimate and the serialized-HTML estimate, and `fits?` drives the
entry-inclusion loop that decides what enters the MODEL's context.
Markup overhead (`<pre>`, `data-transcript-*`, surface-id digests)
silently evicts transcript entries from the prompt — presentation
reaching into acquisition.

### 2.11 The HTML/AI curation asymmetry is structural

AI has eight error prose specialists covering ~40 classes; HTML has ONE
generic card (`error.clj:1068`) declared across all 327 error-class
sites. Forty schema rows literally pair a bespoke `/ai` prose symbol
with the generic `/html` card. Specialists hand-write `<dt>/<dd>` rows
keyed on literal map keys (`config.clj:76-90`, `cluster.clj:182-187`,
`effect.clj:104-112`, `db.clj:1944-1952` — the last dumps EVERY datom
uncapped) instead of calling `value/declared-attributes` (`value.clj:215`),
which already derives the ordered identity-first attribute list and is
used only by the floor. Adding a schema attribute silently omits it from
both hand-written twins.

### 2.12 `web.clj` runs a parallel content path

- `session-timeline` (`web.clj:451-510`): a second content renderer with
  private `db/pull`s, spliced into the specialist's hiccup by
  `pop`/`conj` surgery (`with-session-timeline`, `web.clj:506-510`);
  `transcript.clj:1008` does the mirror `conj` into `agent-html`'s
  vector.
- A second Clojure lexer (`web.clj:316-398`) re-classifies source the
  print faces already classify (`print.cljc:123`), and `code-toggle`
  emits both renderings and CSS-hides one.
- `generic-entity` (`web.clj:707-754`): a private EAV dump with its own
  cap and hand-built elision.
- One distance-cap asymmetry: `walk.clj:596` ✓ suppresses the elision
  marker unit for HTML only — the prompt says "connections elided", the
  page silently shows nothing (the exact absence-as-health class).

### 2.13 The stylesheet is the tell

75 of 171 CSS classes in `resources/public/css/input.css` have no
producer (e.g. `seon-transcript-human/agent/peer/system`, `plan-tree`,
`seon-data-drill`). Someone designed the page ui.md describes; the
producers never caught up.

### 2.14 Live defect: the totality branch's own error value is broken

`print.cljc:572` ✓ contains `:seon.print/unknown-face
(:?_current-ns_?/face node)` — a botched alias substitution; the value
is permanently nil and non-conformant to
`:seon.print/unknown-face-error`. The guarding test
(`test/seon/print_test.clj:240`) only asserts the key NAME appears, so
it stays green. Filed for immediate fix regardless of the PRD waves.

### 2.15 Fit calibration split

`fit`'s token check (`print.cljc:931`) uses the shipped calibration
while `cluster/prompt.clj:196` threads the cluster's observed
calibration — the floor and the budget gate can disagree about what a
token is. Also: `fit` measures the TEXT twin even when producing HTML,
and its convergence loop re-runs a full `emit-text` per iteration.

## 3. Residual function-side bounding (post-clip-ripout residue)

~24 violations across 9 files; full table in the fingerprint lane
output. Worst first:

1. `render/lint.clj:320-322` + `:55` — local `subs` + literal `"…"` +
   `excerpt-characters 120`, inside the defect-detection namespace.
2. `render/ns.clj:95, 187, 414, 452, 541` — `referenced-schema-cap 40`
   layered over a namespace that already fits against a token budget;
   the constant leaks into agent-visible prose three times.
3. `render/transcript.clj:27-31` — `recent-entry-count 6`, a projection
   policy dial in code (self-confessed in its own comment).
4. `render/transcript.clj:704-706` — unconditional `"…"` append that
   can claim omission where none exists.
5. `test/accretion.clj:325-330`, `test/runner.clj:1043, 1055, 1068`,
   `db.clj:580` — silent `(take 3/5/12)` cuts into agent-facing output.
6. `cluster/source.clj:28-54` — half-migrated: a correct elision value
   BESIDE hand-rolled `" … N more."` prose.
7. `config.clj:47` — `subs` 12-char digest clip (HTML twin shows the
   full digest — the two projections disagree on the same fact).
8. `ai.clj:884-886, 1170-1178` — human-facing sizes as raw character
   counts; `tokens/estimate` exists precisely because characters lie.
9. `ai.clj:94-110` — hand-maintained key blacklist for provider-private
   attempt fields (best-behaved hack: it keeps the elision machinery
   honest, but the visibility rule lives in code, not schema).

Parallel AI-text assembly beside the engine (5 sites): the prompt-tail
reminder append (`cluster/prompt.clj:212`), `walk.clj:607+` (`prose`,
hand-built `;;` headers and a `;; branches-elided=N` footer),
`transcript.clj:741-747` (a fourth independent elision phrasing),
`web.clj:803` (debug prompt reassembly), `bootstrap.clj:396-409`.

## 4. Census corrections to the PRD register

- Register #6 should be marked mostly LANDED with the §3 residue as the
  remainder.
- Register #3's narrating-face count (20) overlaps the ~37 faces the
  face census graded genuinely curated — the error/run/message/agent
  prose families are evidence-derived and worth keeping as DATA-face
  candidates, not deleting wholesale. The rot is concentrated in
  `render/ns.clj` and `render/transcript.clj` (two full re-
  implementations of `fit`), not spread across all declared faces.
- NEW register candidates (not in the PRD): §2.1 emitter bare-truncation
  default; §2.2 bare-marker fabrication; §2.3 second chain + dead step
  2; §2.4 composition mounted under the floor; §2.5 double fit;
  §2.7 dead `:summary` tier; §2.8 budget override; §2.10 markup-evicts-
  prompt; §2.12 `session-timeline` splice + second lexer; §2.14
  `:?_current-ns_?` defect; §2.15 calibration split.

## 5. Source lanes

Four read-only lanes, this session, full outputs retained in the session
task directory; this document is their durable consolidation. Verified
rows marked ✓ were re-read at the bytes by the orchestrator on
2026-08-14 evening at the working tree of `context-generation-drive`.
