---
type: research
status: draft
tags: [research, agent]
---

# Eval segmenter — orphan-delimiter + empty eval rows at the source (2026-06-28)

## TL;DR

Every agent reply is split into top-level forms by `seon.repl.internal/parse-forms`
(`src/seon/repl/internal.cljc:401`). It is **already a proper reader** — a
rewrite-clj token-at-a-time scanner — **not** ad-hoc string splitting. So the
framing "replace ad-hoc splitting with a reader loop" is a mis-diagnosis: the
reader is fine. **The bug is the error-RECOVERY heuristic** `find-recovery-point`
(`internal.cljc:245-260`), which shreds a SINGLE model-emitted block that has ONE
delimiter error into `[bad-head :read] + [inner-maps :comment ×N] + [trailing-closer :read]`:

- the trailing closing delimiters (`}` / `]` / `}]`) re-parse as bare
  `Unmatched delimiter` reads → the **11 orphan-delimiter rows**;
- the inner `{…}` map lines, exposed at column-0 by recovery, are demoted to
  prose → empty-`:source ""` `:comment` rows → the **18 empty-source rows**.

29/68 (43%) of the drive's noise is these two symptoms of one cause. **Recommended:
a HYBRID source fix, confined to `seon.repl.internal` (one .cljc + its test):**

- **PRONG 1 (mandatory, low-risk):** never EMIT a `:read` entry whose source is
  only closing delimiters/whitespace — it is always a recovery artifact, the real
  error is the bad-head already recorded. Mirrors the proven render-lane
  `noise-eval?`. Kills the 11 orphans deterministically. **Validated against the
  full existing recovery corpus: zero change to any current case.**
- **PRONG 2 (recommended, moderate-risk → owner check-in):** narrow recovery
  anchors from `\n[;\(\[\{]` to `\n[;\(]` — recover only at a column-0 LIST `(`
  or comment `;`, never a `{`/`[`. Under forms-and-prose-only only a `(`-list is a
  real top-level form; a column-0 `{`/`[` is the broken form's body, not a new
  form. This keeps a broken `(db/transact! [ … ])` as **ONE honest `:read` error**
  instead of shredding it, removing the recovery-collateral empty-source rows.
  **Validated: collapses the shred case to one `:read`; zero change to the
  existing recovery corpus.**

**Approach B (swap to a `cljs.tools.reader` read-until-eof loop à la `cljs.js`)
is REJECTED** — it would regress capability (see §Approaches). `cljs.js` already
uses that loop on the EVAL side; it is the wrong tool for the SEGMENTER side.

## Current mechanism (model text → eval rows), with file:line

Live path (the drive ran exactly this):

1. `seon.agent.turn` calls `(repl/parse-forms reply-text)` —
   `src/seon/agent/turn.cljs:276`.
2. `seon.repl/parse-forms` is a re-export of `seon.repl.internal/parse-forms` —
   `src/seon/repl.cljs:63-66`.
3. `seon.repl.internal/parse-forms` — `src/seon/repl/internal.cljc:401-508`. A
   **rewrite-clj** token-at-a-time scanner: `try-parse-one-token` (`:336-369`)
   calls `rcp/parse-string` on `(subs text offset)`, reads ONE node, advances
   `offset` past its byte-faithful source, loops. (`rcp/parse-string` reads
   exactly one node — "Return a node for first source code element in string
   `s`", `reference-code/rewrite-clj/src/rewrite_clj/parser.cljc:34`; the
   vendored submodule is checked out at `v1.2.51-5-g60782e5`, matching
   `rewrite-clj 1.2.51` in `deps.edn:61`. `parse-string-all` at
   `parser.cljc:39` would instead wrap the WHOLE string as one node — not what
   the scanner wants, since it needs per-form offsets to recover.) It
   classifies each token:
   - `:whitespace`/`:comment` (`:363,:357`) — comments accumulate as `;;`
     narration preamble for the next form;
   - `:form` that is a list/seq → emit `:kind :form` (`:461-467`);
   - `:form` that is a bare atom / tagged literal → **dropped as prose**
     (`:483-484`); a top-level data literal `{…}`/`[…]`/`#{…}` → demoted to a
     `:kind :comment` carrying the one-line `demoted-literal-warning`
     (`:469-479`);
   - `:error` (rewrite-clj threw) → prose vs broken-code split (`:486-508`): a
     prose-token throw is dropped; a genuinely broken span becomes a
     `:kind :read :ok? false` entry whose `:source` is `text[offset → recovery]`.
4. `seon.eval/eval-batch!` (`src/seon/eval.cljs:2721`) drives the entries:
   - `:read` not repairable → `record-eval!` with an `:read` error
     (`eval.cljs:2912-2928`);
   - `:comment` → `record-eval!` with **`:source ""`**, `ok? true`, value nil
     (`eval.cljs:2935-2943`) — this is the empty-source row;
   - `:form` → normal eval (`eval.cljs:2976`).

So both noise classes are eval rows the agent re-reads next turn. The render-lane
fix (`seon.agent.ctx.transcript/coalesce-events`) DROPS them from DISPLAY, but the
durable cure is to stop EMITTING the entries — step 3.

## Root cause (precise)

`find-recovery-point` (`internal.cljc:245-260`) on a parse failure scans forward
for the next **column-0 anchor** matching `#"\n[;\(\[\{]"` (`:257`) — a `(`, `[`,
`{`, or `;` at line start — and records `text[offset → anchor]` as the bad span,
then resumes AT the anchor.

When a model emits a block with one delimiter mistake (LLMs do this constantly —
a dropped `]` or a stray `}`), the failure offset is the start of the block, and
recovery lands on the block's OWN inner lines:

- column-0 `{` lines (the transact's inner maps) become fresh parse starts → each
  reads cleanly as a top-level map → **demoted to a `:comment` (empty-`:source`)
  row**;
- the block's trailing closing delimiters (`}` / `]` / `}]`) become fresh parse
  starts → `rcp/parse-string "}"` throws `Unmatched delimiter: }` → recorded as a
  `:read` failure whose entire source is `"}"` → **orphan-delimiter row**.

Reproduced live (JVM, pure rewrite-clj — `parse-forms` is CLJC):

| input (model block with one delim error) | current `parse-forms` kinds |
|---|---|
| `(message/user "hi")\n}` | `[:form :read]` ← orphan `}` |
| `(let [inv (…)]\n(mapv … inv)\n}` | `[:read :form :read]` ← orphan `}` |
| `(db/transact! :seon [\n{:a 1}\n{:b 2}\n}]` | `[:read :comment :comment :read]` ← 2 empty + orphan |

Each orphan `:read` source is literally `"}"`, matching the drive's recorded
`"}\n"` / `"]\n"` rows. Each `:comment` records `:source ""` (eval.cljs:2941),
matching the 18 empty-source rows. **One broken block → a wall.**

A genuine bare top-level `{…}` the agent really typed (not recovery collateral)
parses cleanly via the data-literal demotion path (`internal.cljc:469-479`), not
via recovery — so it is NOT affected by either prong; its ⚠ warning is preserved
(the #52 feature still works).

## Approaches

### (A) Minimal drop-filter — PARTIAL, but it IS the right primitive for orphans

Drop content-free entries before they become eval rows. Two cuts:

- **A-closer:** suppress a `:read` whose `:source` trimmed is only `)`/`]`/`}`
  (regex `^[\s)\]}]+$`). A bare closer is ALWAYS a recovery artifact — never code
  the agent intended, and the real error (the unbalanced form that shed it) is
  already recorded as the bad-head `:read`. **It cannot hide a legitimately-failing
  form** (a form has a leading `(`/token; a pure-closer span has none). This is the
  exact `noise-eval?` predicate already live and proven in the render lane
  (`error-detection-infra-2026-06-28.md`), moved one stage upstream.
- **A-empty:** suppress a `:comment` whose narration is empty. (Today `parse-forms`
  never emits one — trailing comments are guarded by `(seq pending)` (`:446`) and
  demoted literals always carry the ⚠ — so this is a belt-and-suspenders guard,
  not a behavior change.)

A alone leaves the SHRED case as `[:read :comment :comment]` — the orphan is gone
but the two recovery-collateral demoted-map ⚠ rows remain (the bulk of the 18
empty-source rows). So A fixes the 11 orphans but not the 18 empties.

### (B) Reader-loop rewrite (`cljs.tools.reader` read-until-eof, à la `cljs.js`) — REJECTED

The canonical "read N top-level forms from one string" loop is
`cljs.js/analyze-str*` (`reference-code/clojurescript/src/main/cljs/cljs/js.cljs:671-705`):
`(rt/indexing-push-back-reader source 1 name)` then `(read eof rdr)`
(`js.cljs:61-63` → `cljs.tools.reader/read` with `:read-cond :allow :features
#{:cljs}`) in a trampoline until `(identical? eof form)`. The pod's own self-host
eval (`eval-str*`) already uses this on the EVAL side.

It is the WRONG tool for the SEGMENTER side. Swapping `parse-forms` to it would
**regress four load-bearing behaviors** that `parse-forms` exists to provide:

1. **Comments are discarded.** `cljs.tools.reader`'s `read` treats `;` as
   whitespace (`reader.clj:794,820` dispatch `read-comment`). seon NEEDS the `;`
   narration channel — the taught reasoning preamble attached as `:narration`.
2. **Read errors abort the whole stream.** `analyze-str*` `(catch :default …)`
   STOPS at the first read error (`js.cljs:693-700`). seon needs **per-form error
   isolation** — good forms before AND after a broken one still run and record
   (`read-failures-isolated` corpus). A read-loop gives all-or-nothing.
3. **No prose / data-literal demotion.** The reader READS `42` as a number and
   `{:a 1}` as a map — both become "forms". seon's forms-and-prose-only contract
   (#50/#52) deliberately drops bare atoms and demotes top-level data literals to
   prevent a fabricated `=> {…}` echo self-evaluating into a real `result/<id>`.
4. **Byte-faithful source isn't guaranteed per entry.** The proposed
   source-logging reader (`reader_types.clj:381`; `log-source*` `:316`; `:source`
   meta `:324`) attaches `:source` ONLY to IMeta values — a bare scalar/symbol
   can't carry meta, so per-entry byte-faithful source (load-bearing for resume
   re-eval, `internal.cljc:20`) breaks for non-collection entries.

rewrite-clj was chosen precisely because it preserves comments, whitespace, and
byte-faithful node strings, and surfaces a parse failure as a value to recover
from rather than a throw that aborts. **Keep it.** The cljs.js loop is the
EVAL-side reader; the bug is the recovery heuristic, not the reader.

### (C / RECOMMENDED) HYBRID — A-closer + narrowed recovery anchors

Keep rewrite-clj. Two surgical changes in `internal.cljc`:

- **PRONG 1 = A-closer** (above): suppress closer-only `:read` entries at emit.
- **PRONG 2 = narrow recovery anchors:** change `find-recovery-point`'s regex
  (`internal.cljc:257`) from `#"\n[;\(\[\{]"` to `#"\n[;\(]"`. Recover only at a
  column-0 LIST `(` or comment `;`. Rationale: under forms-and-prose-only, only a
  `(`-list is an intended top-level FORM; a column-0 `{`/`[` is almost always the
  broken form's body. Not anchoring on it keeps the broken block as ONE `:read`
  span (one honest "your transact is unbalanced" error) and stops the inner maps
  from being exposed as demoted-literal collateral.

  **Cost (flagged):** a GENUINE bare top-level `{…}` immediately AFTER a broken
  form gets absorbed into the error span instead of emitting its own ⚠ warning.
  This is acceptable — a bare top-level map is prose/non-evaluated anyway, and the
  agent's real signal is "fix the broken form above." Aligns with
  simple-core-over-edge-cases.

Together: a shredded broken block `[:read :comment :comment :read]` collapses to
`[:read]` — one honest error. The 11 orphans and the recovery-collateral subset of
the 18 empties both vanish at the source. Genuine bare-map demotions (clean-read
path) are untouched.

**Live validation (read-only, JVM, no src edits):**

- PRONG 1 over the full existing `recovery-cases` corpus + the multiline-good case:
  dropped 0 entries from every existing case; dropped exactly the 1 orphan from
  each of the 3 orphan cases.
- PRONG 2 recovery sim: `unbalanced-then-good` recovery offset 12→12,
  `mid-broken` 4→4 (identical — corpus preserved); SHRED 22→38 (whole block = one
  span); `broken-then-bare-map` 8→14 (the documented absorption cost).

## Recommendation

**Ship the HYBRID (C).** PRONG 1 is mandatory and low-risk — it is the proven
render-lane predicate moved upstream and provably touches no existing corpus case.
PRONG 2 is the root-cause fix for the empty-source collateral and is validated
against the corpus, but it alters recovery for ALL failures, so per the owner's
"do not do so lightly… no regressions": **gate PRONG 2 behind the full test plan
below and an owner check-in.** If the owner prefers the most conservative step
first, land PRONG 1 alone (kills the orphans, the larger eyesore), then PRONG 2 as
a follow-up.

What stays UNCHANGED: the eval-row schema, `record-eval!`, auto-await,
instrumentation, the `result/<id>` stash, the `:form`/`:read`/`:comment` entry
shapes, the forms-and-prose-only contract, the parinfer repair layer, narration
attachment. The change is confined to `seon.repl.internal` — the agent-reply
segmenter — and its CLJC test. No `seon.client` / `seon.eval` edit is required
(the dropped entries simply never reach `eval-batch!`).

## Top-3 risk cases the implementation MUST get right

1. **A real syntax error must still surface.** A genuinely broken FORM (leading
   `(`, e.g. `(+ 1 3x)` or an unbalanced `(db/transact! …`) must STILL record a
   `:read` failure the agent sees — PRONG 1 only drops spans that are *pure
   closers* (no leading `(`/token), and PRONG 2 only widens the bad span, never
   suppresses it. Falsify: assert each broken-form corpus case still yields a
   `:read` with non-blank source + `:error`.
2. **Incomplete final form (EOF mid-form).** `(db/transact! :seon [{:a 1}` with no
   closer → `Unexpected EOF` → ONE `:read` to end-of-text (not an orphan, not an
   empty). Must remain a single honest error after both prongs.
3. **Closer INSIDE a string/char must not be miscounted.** `(str "}")`,
   `(println \})`, `"a } b"` — rewrite-clj already handles these correctly (it is
   a real reader, not a brace-counter); PRONG 1's closer-only regex runs on the
   `:source` of an already-failed `:read` span, never on a valid form, so it can
   never strip a `}` that lives inside a string literal of a good form. Pin this
   so a future "optimize with brace-counting" refactor can't regress it.

## Test plan (the owner demands "careful tests… no regressions")

All tests live in `test/seon/repl/internal_test.cljc` (the existing parse-forms
corpus; CLJC so the JVM suite exercises it without the pod).

### Must-keep-passing (no-regression gate)

- `read-failures-isolated` (`internal_test.cljc:351`) — `recovery-cases`
  (`:326-349`): `(unbalanced\n(good)`→`[:read :form]`, `(good)\n(unbalanced`→
  `[:form :read]`, `(a)\n(broken\n(b)`→`[:form :read :form]`, `"unterminated`→
  `[:read]`, unknown-tag + odd-quote → contain `[:form :form]`. **Confirmed
  unaffected by both prongs in the read-only sim.**
- `multiline-form-is-one-eval` (`:307`) — a multiline `(db/transact! {…})` is ONE
  `:form`; a bare multiline `{…}` is ONE demoted `:comment` with ⚠.
- `forms-and-prose-only` (`:220`), `reader-macros-evaluate` (`:265`),
  `inline-backtick-prose` (`:286`), `prose-tokens-dropped-not-read-failures`
  (`:427`), `narration-attaches-to-failure-not-next-good` (`:374`),
  `narration-round-trips` (`:484`), `basic-shapes` (`:66`),
  `source-is-byte-faithful` (`:96`).
- Downstream: `test/seon/eval/repair_batch_test.cljs`, `test/seon/repair_test.cljc`
  (the repair layer consumes `:read` entries — confirm it still receives the
  genuine broken-form `:read`s it repairs; it never repaired a bare-closer).

### New cases (the bug + its boundaries) — add to `internal_test.cljc`

Input → expected kinds (assert behavior, not exact strings — per house rule):

| # | input | expected after C | proves |
|---|---|---|---|
| 1 | `(message/user "hi")\n}` | `[:form]` | trailing orphan dropped, good form kept |
| 2 | `(message/user "hi")\n]` | `[:form]` | orphan `]` too |
| 3 | `(a)\n}\n}\n}` | `[:form]` | multiple stacked orphans all dropped |
| 4 | `(let [inv (q)]\n(f inv)\n}` | `[:read :form]` (no trailing orphan) | PRONG 1 over a recovery tail |
| 5 | `(db/transact! :seon [\n{:a 1}\n{:b 2}\n}]` | `[:read]` | PRONG 2 collapses the shred |
| 6 | `(db/transact! :seon [{:a 1}` (EOF) | `[:read]`, non-blank source, `:error` | incomplete final form still ONE honest error (risk #2) |
| 7 | `(+ 1 3x)` | `[:read]` (non-blank, `:error`) | a real broken FORM still surfaces (risk #1) |
| 8 | `(str "}")` | `[:form]` | closer inside a string is NOT stripped (risk #3) |
| 9 | `{:a 1}` (clean bare map) | `[:comment]` with ⚠ | genuine demotion preserved (PRONG 2 doesn't touch clean-read demotions) |
| 10 | `(good)\n{:a 1}` (bare map after GOOD form) | `[:form :comment]` with ⚠ | bare map after a GOOD (not broken) form still demotes+warns |
| 11 | `(broken\n{:a 1}` (bare map after BROKEN form) | `[:read]` | the documented PRONG-2 absorption cost — assert it, so the trade-off is intentional + visible |

Add a focused `deftest segmenter-emits-no-content-free-rows`: for a corpus of
real broken blocks, assert NO entry has a `:read` source matching `^[\s)\]}]+$`
and NO `:comment` has empty narration.

### No-regression proof (end-to-end)

1. Full CLJS suite: `bin/test-cljs` (~160s; a fresh `:node-test` JVM — never
   overlap `run-tests` in the live pod). Green = unit no-regression.
2. Live re-drive (per the standing DeepSeek-on-acme directive): re-run the
   `drive-observe-2026-06-28.md` database-memory+planning task on a fresh acme
   agent, then re-compute the drive's eval tally
   (`{:ok-real :empty-src :other-fail :orphan-delim}`). **Win condition:
   `:orphan-delim` → 0 and `:empty-src` → ~0 (only genuine bare-map demotions
   remain), i.e. the ~43% noise floor collapses**, with `:ok-real` and the
   succeeded-task behavior unchanged. Live-prove by querying the store for the
   agent's eval sources (no closer-only `:source`, no recovery-collateral `""`).

## Smell flagged (out of scope)

`seon.client` carries a SEPARATE reader-free paren-balancer
(`extract-form-from-string` / `extract-form-at-line` / `extract-form-at-index`,
`src/seon/client.cljs:~1075-1125`) used to extract a specific top-level form by
line/index for program-graph source capture + ghost-var detection — NOT for
segmenting agent replies. It is a hand-rolled brace/string scanner (a latent
"brace-counter" smell), but it is a different concern and not on the agent-eval
path; noting it only so a future reader doesn't confuse it with the segmenter.
