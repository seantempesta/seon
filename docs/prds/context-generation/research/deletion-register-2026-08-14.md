---
type: research
status: current
tags: [research, render, deletion, loc, one-renderer]
---

# The deletion register — what the one renderer removes

Purpose: the owner's thesis is that the one-renderer redesign must be
NET-DELETING — one well-thought-out system replacing bespoke machinery
everywhere. This register enumerates every mechanism the design retires,
with the file:line range and a measured line count, so the PRD can claim a
concrete net-LOC number and nothing survives by being forgotten.

Method: every row below was produced by OPENING the file at the working
tree of `context-generation-drive` on 2026-08-14 (evening, after the
`clip-ripout` boundary conversions) and reading the named range. Counts are
`end - start + 1` for whole-definition rows. Rows whose scope is a set of
arms INSIDE a surviving function are marked **(partial)** and carry the
anchor lines plus an explicit estimate — those are the only inexact
numbers here, and they are labeled everywhere they appear.

Dispositions:

- **DELETE** — the code goes and nothing replaces it. The behaviour was
  either wrong, dead, or subsumed by a stage that already exists.
- **REPLACE** — the code goes and its job moves to ONE shared mechanism,
  named in the successor column. The successor's own cost is counted
  separately in §11 so the arithmetic stays honest.
- **SHRINK** — the entry point survives; the count is the NET reduction.

## 0. What the PRD register claimed that is already gone

Re-verified at the bytes before counting, so the estimate does not
double-count landed work:

| PRD row | Claim | State at HEAD |
|---|---|---|
| #6 worst site | `src/my/note.clj:266` private `notes-limit 50` | GONE — `grep -rn "notes-limit" src/` is empty |
| #6 | `[clipped]` token inventor at `src/seon/render/ns.clj:234-240` | GONE — those lines are now `stored-arglists`; no `[clipped]` literal remains in `src/` |
| #6 | agent print output cut at `src/seon/sci/eval.clj:299-308` | GONE — `evaluation-output` (299-306) concatenates whole |
| #7 | CSS `max-height/overflow:hidden` | GONE (`d294ac876`) |

`src/seon/print.cljc:834-839` `bounded-text` **survives** and must: it is
the seam-B implementation reached by `admit-string` (841-852), the one
legal storage cap. What dies is its second caller inside `fit-text`.

---

## 1. `src/seon/render/ns.clj` — the private fit loop (675 lines total)

The namespace lens re-implements `fit` against a token budget, with three
hand-written detail tiers and its own English elision sentence.

| # | Mechanism | file:lines | LOC | Disposition | Successor |
|---|---|---|---|---|---|
| 1.1 | `token-budget` + `within-budget?` | ns.clj:311-321 | 11 | DELETE | budget lives only at seam A |
| 1.2 | `omission-value` (a hand-built elision map) | ns.clj:323-333 | 11 | DELETE | `print/elision-node` |
| 1.3 | `omission-text` (elision phrasing #2) | ns.clj:343-352 | 10 | DELETE | the one elision face |
| 1.4 | `minimal-ai-text` (budget-floor tier) | ns.clj:478-488 | 11 | DELETE | — |
| 1.5 | `budgeted-ai` (linear inclusion loop) | ns.clj:490-506 | 17 | DELETE | seam-A member selection |
| 1.6 | `minimal-html-view` | ns.clj:609-618 | 10 | DELETE | — |
| 1.7 | `html-within-budget?` + `budgeted-html` | ns.clj:620-638 | 19 | DELETE | HTML has no budget (PRD §1) |
| 1.8 | `referenced-schema-cap 40` and its capped? plumbing (partial) | ns.clj:95; 167-169; 186-190; 219-220; 411-415; 450-453; 540-542 | ~20 | DELETE | walk membership; cap is not a render concern |
| 1.9 | `compact-ai-items` loses the capped arm (partial) | ns.clj:441-453 | -4 | SHRINK | — |
| 1.10 | `compact-ai-text` loses `included-count`/`omitted` (partial) | ns.clj:455-468 | -4 | SHRINK | — |
| 1.11 | `compact-html-view` loses the same (partial) | ns.clj:572-599 | -6 | SHRINK | — |
| 1.12 | `html-view` + `render-ai`/`render-html` arity plumbing (partial) | ns.clj:601-607, 662-675 | -3 | SHRINK | — |

**DELETE 109 · SHRINK 17.** What survives: `render-data`, the three
selectors, the Malli-grounded closure, the compact/full data faces.

---

## 2. `src/seon/render/transcript.clj` — the second fit loop, and the dead `:summary` tier (1009 lines total)

| # | Mechanism | file:lines | LOC | Disposition | Successor |
|---|---|---|---|---|---|
| 2.1 | `recent-entry-count 6` (a projection policy dial in code, self-confessed in its own comment) | transcript.clj:27-31 | 5 | DELETE | derivation from facts |
| 2.2 | `marker-text` (elision phrasing #3) | transcript.clj:742-745 | 4 | DELETE | the one elision face |
| 2.3 | `output-tokens` — takes the MAX of AI text and serialized-HTML estimates, so markup bytes evict prompt content (reaudit §2.10) | transcript.clj:792-797 | 6 | DELETE | seam A measures the AI text only |
| 2.4 | `fits?` | transcript.clj:799-801 | 3 | DELETE | — |
| 2.5 | `best-summary` — the `:summary` tier: re-tests the identical candidate and always fails once `:full` failed (reaudit §2.7) | transcript.clj:803-807 | 5 | DELETE | — |
| 2.6 | the `detail` parameter plumbing that made `:summary` look real: four render functions take `_detail` and ignore it, plus the `::detail` key and its data attribute (partial) | transcript.clj:583, 624, 635, 666, 692, 769, 805 | ~7 | DELETE | — |
| 2.7 | `minimum-token-budget` | transcript.clj:863-867 | 5 | DELETE | — |
| 2.8 | `transcript-unit` (derives a budget from admission caps) | transcript.clj:984-989 | 6 | DELETE | — |
| 2.9 | `bounded-scalar` (function-side bounding) | transcript.clj:560-564 | 5 | DELETE | values flow whole |
| 2.10 | `history-entries` budget derivation | transcript.clj:926-933 | 8 | DELETE | seam A |
| 2.11 | `history-entries` spend loop | transcript.clj:959-969 | 11 | DELETE | seam A member selection |
| 2.12 | `html-output` elision paragraph | transcript.clj:786-788 | 3 | DELETE | elision chips |
| 2.13 | `projection` — the budget convergence loop, incl. **line 832 `budget (max requested minimum)`, a render function raising the budget it was handed** (reaudit §2.8) | transcript.clj:809-861 | 53 → ~20 | SHRINK -33 | derivation survives; budget leaves |
| 2.14 | `ai-output` loses its elided arm (partial) | transcript.clj:747-755 | -3 | SHRINK | — |
| 2.15 | `html-entries` — the agent transcript as `[:pre [:code (::text entry)]]`, i.e. AI prose in a monospace box (reaudit §2.9) | transcript.clj:757-775 | 19 | REPLACE | the real `/html` projection per block + chips |
| 2.16 | `reasoning-disclosure`'s unconditional `"…"` append, which can claim omission where none exists | transcript.clj:704-706 | -1 | SHRINK | — |

**DELETE 68 · SHRINK 37 · REPLACE 19.**

---

## 3. `src/seon/print.cljc` — the emitter's bare-cut path and the `fit` convergence loop (949 lines total)

The worst new finding of the re-audit: the floor printer bare-truncates by
DEFAULT. `::length 32` / `::level 8` come from `seon.print.edn`, and with
them set the emitter writes literal `"..."` and `"#"` — no count, no path,
no requery. Exactly one call site nils them out (`render/value.clj:505-507`).

| # | Mechanism | file:lines | LOC | Disposition | Successor |
|---|---|---|---|---|---|
| 3.1 | `structural-cut?` | print.cljc:260-263 | 4 | DELETE | sampler depth bound |
| 3.2 | `visible-items` | print.cljc:265-270 | 6 | DELETE | sampler breadth bound |
| 3.3 | the eight bare-cut emitter arms: `"#"` at 385, 430, 487, 541, 557 and `"..."` at 392, 453, 497 with their guard lines (partial) | print.cljc:384-385, 390-392, 429-430, 451-453, 486-488, 495-497, 540-542, 555-557 | ~20 | DELETE | every cut is an elision value |
| 3.4 | `TextSink -fragment` emitting a raw `/ai` fragment below root (PRD row #2) | print.cljc:108-112 | 5 | DELETE | results are data |
| 3.5 | `projected-text` (`pr-str` then char-chop feed) | print.cljc:829-832 | 4 | DELETE | — |
| 3.6 | `render-elision-ai` (elision phrasing #1 — narrates a fabricated sentence when handed a bare marker, reaudit §2.2) | print.cljc:283-304 | 22 | REPLACE | one declared elision face |
| 3.7 | `::truncated-string` emit — `(pr-str (str value "…"))` puts the ellipsis INSIDE the quoted string, lying about the content | print.cljc:529-531 | 3 | REPLACE | elision value with the honest string |
| 3.8 | `soft-separator` — column wrap that breaks mid-structure; the main reason current output reads as soup | print.cljc:90-96 | 7 | REPLACE | archive `fits?`/`emit` inline-when-fits |
| 3.9 | `fit-entry` | print.cljc:790-798 | 9 | REPLACE | sample→emit |
| 3.10 | `fit-children` | print.cljc:800-827 | 28 | REPLACE | sample→emit |
| 3.11 | `fit-text` | print.cljc:854-864 | 11 | REPLACE | sampler string bound |
| 3.12 | `structural-elision` | print.cljc:866-877 | 12 | REPLACE | sampler |
| 3.13 | `fit-node` | print.cljc:879-906 | 28 | REPLACE | sampler |
| 3.14 | `fit` — the convergence loop that re-emits the ENTIRE tree per iteration and halves `string-limit` FIRST (destroys payloads, keeps scaffolding — exactly backwards) | print.cljc:908-943 | 36 | REPLACE | sample→emit, `dominant-string-entry` |
| 3.15 | `:?_current-ns_?/face` botch — the totality branch's OWN error value is permanently nil and non-conformant (reaudit §2.14) | print.cljc:572 | 1 | FIX | file immediately, independent of the waves |

**DELETE 39 · REPLACE 156 · FIX 1.** `admit-string` (841-852) and
`bounded-text` (834-839) stay: seam B.

---

## 4. `src/seon/render.clj` — `fit-terminal`, the profile fallback, the placeholder (982 lines total)

| # | Mechanism | file:lines | LOC | Disposition | Successor |
|---|---|---|---|---|---|
| 4.1 | `fit-terminal` — the SECOND fit pass, char-wise over already-fitted output; for HTML it chops `pr-str` of the hiccup (reaudit §2.5) | render.clj:517-532 | 16 | DELETE | one fit, in the printer |
| 4.2 | `default-agent-profile` — a namespace-load global (ledger-28 defect) | render.clj:65-66 | 2 | DELETE | profile rides the value |
| 4.3 | `request-profile`'s derivation fallback (the cluster lookup + config read at call time) (partial: 73-91, the diagnostic arm survives) | render.clj:68-103 | ~22 | DELETE | caller hands the profile |
| 4.4 | `renderer-failure` — the BANNED `renderer unavailable` placeholder plus its owner-message side channel | render.clj:762-802 | 41 | DELETE | the `seon.error` card family (PRD §2) |
| 4.5 | `valid-projection?` and its silent-return caller: **`(if (valid-projection? …) … node)` returns the UNPROJECTED node with no error and no fact** (PRD row #11) | render.clj:402-406, 468-474 | 5 + 7 | REPLACE | the stage-contract layer |
| 4.6 | `project-node*`'s substitution arms: a selected specialist's output replaces the VALUE in result position (PRD row #1 — 66.7% prose, up to 98.8% of queried data destroyed) (partial) | render.clj:456-474 | ~19 | REPLACE | values render as data; prose only under instruction entities |
| 4.7 | `floor-producer?` — a hardcoded two-symbol set gating argument shape (reaudit §2.3) | render.clj:172-176 | 5 | REPLACE | a declared fact, not a name set |
| 4.8 | hardcoded floor symbols in the chain | render.clj:296-298, 317-320 | 7 | REPLACE | same |

**DELETE 81 · REPLACE 43.**

---

## 5. `src/seon/render/value.clj` — the floor's second map face and its string-built layout (638 lines total)

The floor's schema-recognizing path builds `[:dl]`/`[:ol]` out of
`emit-text` STRINGS and discards the `HiccupSink` — the structural browser
is skipped precisely when a value matches a registered schema (reaudit
§2.9). The same path is PRD row #4: `:seon.schedule.fire/nominal-at`
reaches the agent as unqueryable `nominal-at:`.

| # | Mechanism | file:lines | LOC | Disposition | Successor |
|---|---|---|---|---|---|
| 5.1 | `attribute-label` (drops the namespace) | value.clj:365-372 | 8 | DELETE | one readable EDN face, namespaces intact |
| 5.2 | `map-components` | value.clj:374-391 | 18 | DELETE | the tee sinks |
| 5.3 | `components-text` (`label: value` prose) | value.clj:393-399 | 7 | DELETE | the tee sinks |
| 5.4 | `map-html` (string-fed `<dl>`) | value.clj:401-411 | 11 | DELETE | `HiccupSink` |
| 5.5 | `layout-emission` (discards the HiccupSink) | value.clj:413-438 | 26 | DELETE | `print/emit-both` |
| 5.6 | `render-ai-data`'s appended `" ; elided — this value is larger…"` (elision phrasing #5) | value.clj:545-547 | 3 | DELETE | elision value |
| 5.7 | `render-prepared`'s two caps-missing prose fallbacks (partial) | value.clj:618-624 | -4 | SHRINK | typed refusal |

**DELETE 73 · SHRINK 4.** `declared-attributes` (value.clj:215-251, 37
lines) and `layout-tree`/`ordered-map-node` **stay and grow**: they are the
successor for §8's hand-written cards — the ordered, identity-first
attribute list applied to the print tree rather than to strings.

---

## 6. `src/seon/render/walk.clj` — the prose assembler and the hardcoded placeholder (926 lines total)

| # | Mechanism | file:lines | LOC | Disposition | Successor |
|---|---|---|---|---|---|
| 6.1 | `prose` — the `/ai` assembly: hand-built `;;` headers, `;; d0 · [...]` provenance comments, a `;; branches-elided=N` footer (elision phrasing #4), a guidance sentence, a volatile-metadata marker | walk.clj:606-709 | 104 | REPLACE | the assembly stage (PRD §1 pipeline, flat event log) |
| 6.2 | the hardcoded `renderer unavailable` div and `"Renderer unavailable."` string | walk.clj:585-590 | 6 | DELETE | error card family |
| 6.3 | `marker (when (not= output :seon.render/html) …)` — the elision unit suppressed for HTML ONLY, so the prompt says "connections elided" and the page silently shows nothing (the absence-as-health class) | walk.clj:596-597 | 2 | DELETE | one elision, both faces |

**DELETE 8 · REPLACE 104.**

---

## 7. `src/seon/render/web.clj` — the parallel content path (2382 lines total)

| # | Mechanism | file:lines | LOC | Disposition | Successor |
|---|---|---|---|---|---|
| 7.1 | `session-timeline` — a SECOND content renderer with its own `db/pull`s, turn chrome, and result panes | web.clj:451-504 | 54 | DELETE | the transcript's `/html` projection |
| 7.2 | `with-session-timeline` — splices into the specialist's hiccup by `pop`/`conj` surgery | web.clj:506-510 | 5 | DELETE | blocks, not surgery |
| 7.3 | `history-metadata` — per-kind pulls to reconstruct turn boundaries | web.clj:400-445 | 46 | REPLACE | turn chrome from receipt/contribution facts, SHA-checked |
| 7.4 | `turn-groups` | web.clj:447-449 | 3 | DELETE | — |
| 7.5 | the SECOND Clojure lexer: `syntax-delimiters`, `token-end`, `quoted-end`, `line-end`, `highlighted-source` — re-classifying source the print faces already classify (`print.cljc:123`) | web.clj:316-388 | 73 | REPLACE | `seon.ui.clojure` revival (§11) |
| 7.6 | `code-toggle` emitting BOTH renderings and CSS-hiding one (partial: the raw toggle survives) | web.clj:390-398 | -4 | SHRINK | one rendering + toggle |
| 7.7 | `generic-entity` + `direct-attribute` + `ref-attribute?` + `many-attribute?` — a private EAV dump with its own cap and hand-built elision (elision phrasing #6, web.clj:746-751) | web.clj:686-754 | 69 | REPLACE | the floor + the walk |
| 7.8 | three more hardcoded `renderer unavailable` / pending placeholders | web.clj:579-581, 622-623, 849-850 | 7 | DELETE | error card family |
| 7.9 | `debug-ai-html` / `debug-prompt` assembling the prompt by string concat into a `<pre>` (partial) | web.clj:802-803, 827-836 | -3 | SHRINK | the formatted `/ai` face |

**DELETE 69 · REPLACE 188 · SHRINK 7.**

---

## 8. The hand-written `<dt>/<dd>` specialist cards

Each keys on literal map keys instead of calling `value/declared-attributes`
(value.clj:215), which already derives the ordered identity-first list and
is used only by the floor. Adding a schema attribute silently omits it from
both hand-written twins (reaudit §2.11). Forty schema rows pair a bespoke
`/ai` prose symbol with the ONE generic `/html` card.

| # | Mechanism | file:lines | LOC | Disposition | Successor |
|---|---|---|---|---|---|
| 8.1 | `seon.config/short-digest` — a 12-char `subs` clip whose HTML twin shows the FULL digest (the two projections disagree about one fact) | config.clj:47-50 | 4 | DELETE | — |
| 8.2 | `seon.config/render-ai` (prose sentence) | config.clj:52-66 | 15 | REPLACE | inline attribute face |
| 8.3 | `seon.config/render-html` (hand `<dl>`) | config.clj:68-91 | 24 | REPLACE | `declared-attributes` |
| 8.4 | `seon.cluster/render-ai` (PRD row #3 twin) | cluster.clj:152-168 | 17 | REPLACE | inline attribute face |
| 8.5 | `seon.cluster/render-html` | cluster.clj:170-187 | 18 | REPLACE | `declared-attributes` |
| 8.6 | `seon.effect/render-ai` | effect.clj:60-88 | 29 | REPLACE | inline attribute face |
| 8.7 | `seon.effect/render-html` | effect.clj:90-126 | 37 | REPLACE | `declared-attributes` + disclosure |
| 8.8 | `seon.db/render-transaction-ai` | db.clj:1908-1929 | 22 | REPLACE | data face |
| 8.9 | `seon.db/render-transaction-html` — dumps EVERY datom uncapped | db.clj:1931-1952 | 22 | REPLACE | `declared-attributes` + floor windows |
| 8.10 | `seon.cluster.run/render-ai` — the audit's named seam: a pull becomes a nine-arm English `cond` | run.clj:1869-1966 | 98 | REPLACE | disposition attribute + evidence data face |
| 8.11 | `seon.cluster.message/render-ai` — "Agent X said to Y: …" | message.clj:437-471 | 35 | REPLACE | attribute face |
| 8.12 | `seon.problems/stale-var-ai` — a pull becomes a reboot instruction | problems.clj:432-438 | 7 | REPLACE | attribute face |
| 8.13 | `seon.error` prose family: `notice-ai-prose` (530-631), `ai-prose` (633-671), `evidence-text` + `default-ai-prose` (1013-1036), `render-ai` (1044-1066), the ONE generic `render-html` card (1068-1094). `error.clj:1019` flattens arbitrary nested evidence with raw `pr-str` | error.clj:530-671, 1013-1094 | 215 | REPLACE (partial) | evidence-derived DATA faces; the reaudit corrects the PRD's "delete 20 narrating faces" — these are worth keeping as data-face candidates |

**DELETE 4 · REPLACE 539.** Note: the re-audit's census correction applies
here. The rot is concentrated in `render/ns.clj` and `render/transcript.clj`
(two full re-implementations of `fit`), not spread evenly across all 42
declared faces; the error/run/message families are evidence-derived and
convert rather than vanish. §11 prices the conversion.

---

## 9. Residual function-side bounding (re-audit §3)

| # | Mechanism | file:lines | LOC | Disposition | Successor |
|---|---|---|---|---|---|
| 9.1 | `excerpt-characters 120` + local `subs` + literal `"…"` — inside the DEFECT-DETECTION namespace | lint.clj:55, 318-322 | 6 | DELETE | elision value |
| 9.2 | `(take 12)` into an agent-facing attribute observation | db.clj:580 | 1 | DELETE | window + elision |
| 9.3 | `(take 6 missing)` in a config refusal | config.clj:556 (+arms) | ~4 | DELETE | elision value |
| 9.4 | `cluster/source.clj` half-migration: a correct elision value BESIDE hand-rolled `" … N more."` prose (elision phrasing #7) | source.clj:52-54 | 2 | DELETE | the elision value already there |
| 9.5 | `(take 3)` + `"N more in the complete gate-report blob."` | test/accretion.clj:325-330 | 6 | DELETE | elision value |
| 9.6 | three `(take 5 …)` cuts into gate-report reasons | test/runner.clj:1043, 1055, 1068 | 3 | DELETE | elision value |
| 9.7 | `attempt-without-private-provider-data` — a hand-maintained key blacklist for provider-private fields; the visibility rule lives in code, not schema | ai.clj:94-110 | 17 | REPLACE | a declared schema visibility property |
| 9.8 | human-facing sizes as raw CHARACTER counts (`tokens/estimate` exists precisely because characters lie) | ai.clj:884-886, 1170-1178 | 12 | REPLACE | `seon.ai.tokens/estimate` |
| 9.9 | `acquire-within-budget` — the distance-decrement loop: whole branches vanish with NO elision value | prompt.clj:224-272 | 49 | REPLACE | seam-A member-level selection (whole blocks in, or elide as chips with requery identity) |
| 9.10 | the prompt-tail reminder string append (partial) | prompt.clj:211-222 | -6 | SHRINK | assembly stage |

**DELETE 22 · REPLACE 78 · SHRINK 6.**
`bootstrap.clj:396-409` (the intent-admission loop) is **NOT** in the
register: it is config-derived and acquisition-scoped. The re-audit flagged
it for multiplicity only, and it becomes one caller of the seam-A selector
rather than a seventh budget loop.

---

## 10. The four independent elision phrasings, in one place

Because they are scattered above, they are worth reading together — one
fact, five hand-written English sentences plus two more found in passing:

| Phrasing | Where | LOC | Row |
|---|---|---|---|
| 1. `"… N more subtree; requery by …"` | print.cljc:283-304 | 22 | 3.6 |
| 2. `"N definitions omitted by the namespace render budget."` | ns.clj:343-352 | 10 | 1.3 |
| 3. `"N older transcript entries elided by the token budget."` | transcript.clj:742-745 | 4 | 2.2 |
| 4. `";; branches-elided=N elided-tokens=N"` | walk.clj:686-694 | 9 | 6.1 |
| 5. `" ; elided — this value is larger than the configured window"` | value.clj:545-547, 537-539 | 6 | 5.6 |
| 6. `"elided N reverse <attr> connections at the configured collection cap"` | web.clj:746-751 | 6 | 7.7 |
| 7. `" … N more."` beside a correct elision value | source.clj:52-54 | 2 | 9.4 |

All seven collapse into ONE declared elision face over the existing
`:seon.print/elided` value (schema stays — PRD §5).

---

## 11. Revivals and new work — the honest other side

Quarry root: `git show 9e44815f5:src-old/<path>`. Ranges below were read
from the extracted file.

### 11.1 Revive verbatim

| Item | Source | LOC |
|---|---|---|
| `seon.ui.clojure` — server-rendered Clojure highlighting, single-pass, total by contract (unterminated string degrades to EOF), morph-safe, dependency-free. The fresh tree has NO highlighter | `9e44815f5:src-old/seon/ui/clojure.cljc` | **192** |

Class rename `hljs-*` → `seon-print-*`; the degrade-to-plain fallback
routes through the strict dial. This is the successor for row 7.5 (73
lines of second lexer).

### 11.2 Revive adapted — `src-old/seon/render/value.cljc` (1927 lines total)

| Portion | Source lines | LOC | What it buys |
|---|---|---|---|
| opaque / datom / record detection | 292-330 | 39 | `#‹datahike/DB max-tx=42›`, `#datom[e a v]` honest tokens |
| sampler support: `counted-count`, `shared-keys`, `field-preference-tier`, `drillable-map-key?`, `map-key-projection`, `named-scalar-marker` | 547-651 | 105 | bounded small-value key preference (byte-stable); real `get-in` paths |
| `sample-seqish` / `sample*` / `sample` — guarded head+1 realization; a poisoned lazy seq degrades to an opaque marker naming the cause; `sample` PROMISES it never throws | 722-846 | 125 | the lazy-safety contract the current printer has nowhere |
| `leaf-marker?` / `truncated?` / `complete-sample?` | 847-876 | 30 | honest completeness reporting |
| `ind` / `datom-token` / `opaque-token` / `pruned-token` / `clipped-string-token` / `emit-leaf` / `map-parts` / `seqish-parts` / `emit-inline` / `fits?` / `emit` — the Oppen-style inline-when-fits printer | 877-1012 | 136 | the successor for `soft-separator` and the whole `fit` family |
| `top-type+size` + `dominant-string-entry` (shape-general 70%-payload rule) | 1013-1069 | 57 | degradation order that protects the PAYLOAD, not the scaffolding |
| `prepare-bounded-view` (drill hint: top-level type + count + live var) | 1070-1102 | 33 | the drill affordance the AI text currently lacks |
| **Revived subtotal** | | **525** | |

Deliberately NOT revived: `project-plain` / `sanitize-result-edn`
(443-520) — `seon.sci.admit` already owns admission; `clip-string`
(583-588) — the same bare `subs` being removed; the explicit-whitespace
block (652-721) — not in the PRD's revival list; the seven old render
dials — correctly deleted, stays deleted.

**Revival total: 192 + 525 = 717 lines.**

### 11.3 Genuinely new work (no prior art) — estimated, not measured

These are forward estimates, flagged as such; they are the only numbers in
this document not read off a file.

| Item | PRD ref | Est. LOC |
|---|---|---|
| Stage-contract layer + the panic seam + the catch-site graph query | §2, row #11 | ~120 |
| One declared elision face over the existing node (replacing 7 phrasings) | §1, row #5 | ~40 |
| Per-block output-byte chain hashes (invalidation) | §4, row #10 | ~60 |
| Turn chrome from contribution rows + SHA check (needs `:seon.context.contribution/characters`, already computed and dropped) | §1 | ~70 |
| Chat chips + inline pretty-data expansion | §1.5 | ~120 |
| Seam-A member-level selection (successor to prompt.clj's distance loop and the two private fit loops) | §1 | ~60 |
| One block identity derivation (replacing three) | row #12 | ~30 |
| Inline attribute faces in schema props for §8's twelve card pairs (EDN, not code) | §8.1 open question | ~80 |
| Form-aware fit (whole-form elision only) | row #8 | ~50 |
| **New subtotal** | | **~630** |

---

## 12. The arithmetic

Measured removals:

```
DELETE   (gone, no successor)
  ns.clj                                        109
  transcript.clj                                 68
  print.cljc                                     39
  render.clj                                     81
  value.clj                                      73
  walk.clj                                        8
  web.clj                                        69
  specialist cards (config short-digest)          4
  residual caps (§9)                             22
                                             ------
                                                473

REPLACE  (dies here; successor is shared machinery)
  print.cljc fit family + soft-separator        156
  render.clj chain / contract arms               43
  walk.clj prose                                104
  web.clj lexer                                  73
  web.clj generic-entity family                  69
  web.clj history-metadata                       46
  transcript.clj html-entries                    19
  specialist cards (§8.2-8.12)                  324
  error.clj prose family (§8.13)                215
  ai.clj blacklist + character counts             29
  prompt.clj acquire-within-budget               49
                                             ------
                                               1127

SHRINK   (net reduction at surviving entry points)
  ns.clj 17 · transcript.clj 37 · web.clj 7
  prompt.clj 6 · value.clj 4                     71
                                             ------
GROSS REMOVED                                  1671
```

Additions:

```
REVIVED   seon.ui.clojure (verbatim)             192
          archive sampler + printer              525
NEW       stage contracts, elision face, chain
          hashes, turn chrome, chips, seam A,
          block identity, attribute faces,
          form-aware fit  (ESTIMATE)            ~630
                                             ------
GROSS ADDED                                    ~1347
```

```
NET  =  1347 - 1671  =  -324 lines
```

**The design is net-deleting by roughly 320 lines of production
Clojure**, and that is the conservative reading, because:

- the ~630 new-work figure is an estimate biased HIGH (nine items, each
  rounded up to the nearest ten);
- §8's 539 replaced lines convert into schema PROPERTIES (`resources/`
  EDN), not `src/` code, so most of that 539 leaves `src/` entirely and
  only ~80 of the new estimate lands anywhere;
- test deletions are **not counted at all**. PRD §6 explicitly makes the
  existing tests that these properties subsume DELETABLE ("a smaller suite
  is a desired outcome"), including the two tests that currently LOCK IN
  defects: `test/seon/print_test.clj:240` (asserts only that the key name
  appears, so the `:?_current-ns_?` botch stays green) and
  `test/seon/print_test.clj:255-269` (locks in the HTML mid-string chop).
  A test-side audit would move the net materially further negative.

The honest counter-pressure: the estimate is net-deleting by a modest
margin, not dramatically. The dramatic win is not LOC — it is that **six
bounding owners become one seam, seven elision phrasings become one face,
two resolution chains become one, and two fit re-implementations become
zero.** If the PRD wants a bigger LOC claim it should come from the §8
conversion (539 lines of hand-written cards → schema props) and the test
audit, both of which are countable once the attribute-face open question
(PRD §8.1) is ruled.

## 13. Method notes and caveats

- Every non-partial LOC in §§1-9 is `end - start + 1` on a range read at
  the working tree of `context-generation-drive`, 2026-08-14 evening.
  Nothing was relayed from a lane.
- Rows marked **(partial)** count arms inside a surviving function:
  1.8, 1.9-1.12, 2.6, 2.14, 2.16, 3.3, 4.3, 4.6, 5.7, 7.6, 7.9, 8.13,
  9.3, 9.10. Their totals are estimates within ±3 lines each.
- §11.3 is forward estimation, not measurement, and is labeled so in the
  arithmetic.
- No production code was edited producing this register.

## 14. Sources

- [The one-renderer PRD](../plan/one-renderer-prd-2026-08-14.md) — §3 rip-out register, §4 revivals
- [Renderer re-audit](renderer-reaudit-2026-08-14.md) — §§2.1-2.15 new findings, §3 residue
- [Value-printer archaeology](value-printer-archaeology-2026-08-14.md) — the eight lost lessons, the synthesis shape
- [Render archaeology](render-archaeology-2026-08-14.md) — quarry root `9e44815f5:src-old/`
- [One-renderer gap census](one-renderer-gap-census-2026-08-14.md) — the 47-row register
