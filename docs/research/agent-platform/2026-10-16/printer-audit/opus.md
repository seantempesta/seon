---
title: Printer audit (Opus)
date: 2026-10-16
lane: printer-audit-opus
type: audit
status: complete
---

# Printer audit: Opus

Lane printer-audit-opus, 2026-10-16, read-only, HEAD `db7e1b49d`. Each finding is one map in
[opus-findings.edn](opus-findings.edn), with sites, who sees the output, what is wrong and the conversion. This page
gives the totals, the worst offenders, the printer's own allocation, the options the conversions need and the
static check that keeps the classes from coming back.

## Method

- **Render functions** come from the schemas. Every symbol value of `:seon.render/ai`, `:seon.render/html` or
  `:seon.render/form` in `resources/seon/schemas/*.edn` was collected by an EDN walk: 143 declarations, 126 distinct
  vars.
- **Call sites** come from clj-kondo analysis of `src` and `script` (`:analysis {:keywords true}`). Every
  `:var-usages` row targeting a render var, `seon.render/render-call`, a printer (`pr-str`, `prn-str`, `print-str`,
  `with-out-str`, `format`, `println`, `clojure.pprint/*`, `print-method`, JSON writers) or a clipper (`subs`, `take`,
  `take-last`, `bounded-count`) was listed with its line. Each listed line was then read and judged. Parsing `subs`
  (reader, edit, ref, forgive) and wire JSON (MCP, provider HTTP) were judged not to be display and are left out.
- **Output schemas** come from a JVM probe of each declared AI render's live `:malli/schema`: 24 of the 61 return
  `:string`.
- **Allocation** was measured with a JVM probe on default: `seon.render.value/prepare` with the agent profile and no
  SCI context, so no render pair runs and only the value printer is measured. A disposable alternative policy was
  computed in the same form for comparison.

`:verified` means I read the code, plus the probe where one is named. `:inferred` means the finding rests on
analysis or a signature alone.

## Totals

| Category | Findings | Sites |
|---|---|---|
| 1a. A non-render caller calls a declared render | 20 | 24 |
| 1b. A render calls another render by name | 12 | 20 |
| 1c. A render or tool calls the printer and returns its text | 4 | 6 |
| 2. `seon.render/render-call` outside the walk | 2 | 2 |
| 3a. A hand-written text or EDN writer, or a render that returns prose | 17 | about 70 |
| 3b. `pr-str` of a value into an error message | 1 (grouped) | 55 |
| 3c. `pr-str` or `str` of a value into hiccup or prose | 5 | 17 |
| 3d. `pr-str` used for storage, identity or URL encoding | 1 (grouped) | 63 |
| 3e. Numbers and dates laid out outside the printer | 1 (grouped) | 10 |
| 4. Clipping outside the printer, or a hardcoded size | 12 | about 30 |
| 5. Hand-built hiccup of a domain value | 2 | 96 leaves (most are labels) |
| 6. Parallel render paths for one value | 7 | — |
| 7. The printer's own internals | 7 | — |
| **Total** | **91** | |

**By namespace (findings whose subject lives there):**

- `seon.render.web`: 11
- `seon.render.transcript`: 10
- `seon.render.value`: 10
- `seon.context`: 7
- `seon.error`: 6, plus 16 sites in each of the grouped 3b and 3d findings
- `seon.repl`: 4
- `seon.problems`, `seon.cluster.message`, `seon.ai`, `seon.turn`, `seon.test.accretion`, `seon.health`,
  `seon.db`: 2 or 3 each
- About 15 more namespaces: 1 each
- The grouped findings F56 (3b) and F62 (3d) span 16 and 18 namespaces. The largest shares are `seon.turn`
  (11 + 3), `seon.program` (9), `seon.fs` (8 + 1), `seon.db` (7) and `seon.render.web` (8).

**Already with lane one-render** (listed here, not skipped): F01–F06, F21, F30, F35, F37, F38, F39–F42, F54, F58,
F60, F61, F64, F75, F77, F78 and F84, 25 findings in all. They cover:

- the direct `exchange-ai` and `exchange-html` calls;
- the duplicate `seon.repl/render-ai` and `seon.repl/render-html` pair;
- the `render-call`s in `must-read-ai` and in `seon.render.web/render-source-call`;
- the history elision by `:seon.config.context/reference-tokens`;
- the hand reply writer;
- the transcript text assembly.

## The ten worst offenders

Ranked by how many viewers see the output, times how far it is from the one path.

1. **`seon.repl/reply-map` and `response-entries` (F39).** Every MCP reply and every history unit is a
   `#:seon.repl{…}` string glued from parts printed by three entries under three budgets:
   - `value/text` for `:out`, with its own "spent" arithmetic;
   - `core-text` for `:findings`, `:repairs`, `:interrupted` and `:alloc-mb`, under the fixed core budget;
   - `print/string-literal` for `:note`.

   It also repairs comment lines by itself. One map value printed once would replace all of it.
2. **`seon.render.value/core-text` used as a general printer (F44, F43, F88).** The core printer has a fixed
   1000-token budget, no render pair and no database. It is called in 21 non-edge sites, including inside the error,
   context, test and accretion renders, so each leaf gets a budget of its own. Its last resort, `bounded-text`, is a
   real second printer: `pr-str` of a hand-trimmed datum, then `subs` to 400 characters and `…`.
3. **History elision by age in `seon.context/exchange-ai` (F64, one-render).** A second elision mechanism with its
   own token dial. It builds its own reference value and prints it with `core-text`.
4. **Transcript text assembly (F03–F05, F35, F42, F54, one-render).** Custody prose, the `pr-str` of a read form,
   the `message/format-ai` sentence and the joined entries are all built by hand. Exchanges are rendered by name, and
   runs through `seon.turn/render-ai`, which is not a declared pair.
5. **The error prose faces (F48, F22, F81).** Eight prose renders print evidence as `attribute=pr-str` lines.
   `render-faults-ai` calls `render-ai`, `faults-form` and `repl/source-text` (`clojure.pprint`) by name. Errors
   have five AI derivations.
6. **The debug and pages layer in `seon.render.web` (F09–F17, F38, F57).**
   - It calls `error/render-html` directly six times, and `health/render-html`, `task/chain-html` and
     `sent/view-html` once each.
   - It has its own `render-call`.
   - It writes eight `pr-str`s of subjects, lookup refs, bases and database identities into `[:code]`.
7. **The 24 string-returning AI renders (F55, F45–F47, F49, F52, F53).** Messages, runs, effects, instructions, the
   background, rejections, models and accretion refusals all build sentences with `pr-str` inside. The value printer
   cannot cut what is already text, except as one string.
8. **`seon.db/render-transaction-html` (F50).** A second HTML value printer, `transaction-value-html`, with
   `SimpleDateFormat` instants, recursive `[:dl]` and no bound.
9. **`pr-str` into error messages (F56).** 55 sites in 16 namespaces print values into `:seon.error/message`,
   unbounded and with no elision. `seon.turn/settle-batch-refusal!` also unbinds `*print-length*` around a whole
   proposals vector.
10. **The root-only render dispatch (F89).** This one causes most of the rest. `make-node` offers only the root to
    its render (rule 10). Because of that, every page and every composite render prints its children itself:
    category 1b, the HTML leaves through `core-text`, and the hand hiccup.

## The printer's own allocation (owner request)

**The model the owner stated:** the form runs; its render, if one is declared, runs (lazily for HTML); and the AI
result goes through the one value printer. A render returns data, never clipped text. The printer gives a
good-faith picture of what a value is, with no schema branches, and does not give equal shares that clip a useful
docstring.

### What the printer does today

Read in `seon.render.value`: `classify`, `member-rank`, `held-order`, `string-shown`, `sibling-level`,
`admit-member`, `fill-strings` and `select-nodes`.

- **Breadth before depth.** Every member of one level is admitted before any member of the next.
  - A map of at most 64 keys (`sorted-bound`) has all its members one level down.
  - A larger map's, a sequence's or a set's members go one level deeper per doubling of their position
    (`sibling-level`).
- **Strings in chunks.** A string is admitted at a 32-character chunk (`chunk-width`), unless the cut would cost
  more than the rest. It then grows one doubling chunk per level, in the same queue as every structural expansion.
  Leftover budget fills cut strings last, shallowest first.
- **Ranking inside a map.** Identity attributes come first. Then come scalars, strings of 32 characters or less
  (`scalar-bound`) and short scalar pairs. Then long strings, then collections. Within a rank, keys go
  alphabetically for hash maps of at most 64 keyword keys.
- **Other fixed numbers:**
  - `name-bound` 256 and `nest-cap` 6;
  - `tuple-bound` 8;
  - `scalar-bound` 32 for big numbers;
  - `text-limits` and `core-profile` 1000 for the core.

### Measured on default

JVM mode, the agent profile and no render pairs. Tokens are `seon.ai.tokens/estimate`.

| Value | Budget | Whole | Today: string characters cut / members cut | Alternative (see below) |
|---|---|---|---|---|
| Function entity `seon.render.value/prepare` | 512 | 1,229 | 1,443 / 12 | 7 collections collapsed, 1 string cut |
| Same | 1,024 | 1,229 | 474 / 6 (the source, `…+474`) | 1 collection collapsed, 0 strings cut, 832 tokens |
| Function entity `seon.sci.eval/shown-result` | 1,024 | ≈3,700 | doc (1,050 characters) cut to 259 (`…+791`); source `…+6786` | doc whole; `:keywords`, `:calls`, `:call-arities` and `:arities` collapsed first |
| Task with a 938-character plan | 256 | 400 | 481 / 0 | 1 collection (references) collapsed, plan cut once |
| Stored error entity | 256 | 580 | 0 / 4 | 1 collection collapsed, 7 strings trimmed |
| Query `[:find ?s ?d]` over `:seon.fn/doc` (4,956 tuples) | 6,144 | 422,365 | 22,095 / 5,021. Of the 453 tuple lines, 257 have content and 99 of those have the doc cut. **196 are empty `[…2]` placeholders: 428 tokens, 7% of the budget, no information.** | 71 tuples, every doc whole, then `…4885` |
| Must-read section `docs/seon/architecture/README.md` | 1,024 / 6,144 | 690 | fits whole | fits whole |

### What this shows

- **Docstrings lose to small fields.** For `shown-result` at 1,024 tokens, the two 64-hex digests, the
  `:seon.fn/keywords` set (292 tokens whole) and the `:calls` and `:call-arities` sets are admitted at earlier
  levels than the 1,050-character docstring. The docstring stops at 259 characters. This is exactly the
  "equal lengths of each attribute" the owner described.
- **Large sequences fill with placeholders.** A large sequence ends in a run of collapsed members, `[…2]` repeated
  196 times, and that is garbage by the owner's standard. The doubling rule puts the tail one level deeper per
  doubling, so once the budget is spent each late member still costs its brackets.
- **Small values are fine.** Values that fit (the must-read, any entity under its budget) print whole today.
  Allocation only matters past the budget, but those are the cases an agent has to dig in.
- **What the probe left out.**
  - It did not run the real render pairs. With pairs, the function entity's render picks members first, so the
    numbers above show the printer's own behavior on render output that is large.
  - The must-read probe used a section that fits. I did not find a live must-read over budget in the time
    available, so that row is not evidence of a cut.

### Schema- or kind-specific branches in the printer

- **None in selection or emission.** `classify` branches only on class. `member-rank` and `reference-value` use the
  database's general identity and ref schema, which falls under "references" in CLAUDE.md's list of general
  improvements.
- **Three schema-aware pieces live in the printer namespace (F90):**
  - `declared` looks up `:seon.schema/identity-projection`;
  - `walk-with-related` attaches an entity root's one-hop related values, which is a walk concern;
  - `render-database-identity-ai` is a schema-specific render.
- **The second printer inside the printer (F88)** is `datum`, `bounded-text`, `throwable-text` and `text-limits`.
  Its callers are `core-text` (42 call sites: 21 non-edge, 11 edge, 5 in scripts, 5 inside `seon.render.value`) and
  `seon.error/render-chain-ai`, which borrows `:frames`.

### Recommended allocation policy

The policy is "structure always, strings whole when they fit, large collections collapse first". It is general:
it branches on shape and size, never on schema.

1. **Structure first.** Every key of every map on the path to the root shows, and every collection shows its kind
   and count. That is the skeleton an agent needs in order to dig, and it is cheap.
2. **Then spend by the size of what would be cut, largest collection first.**
   - If the whole value fits, print it whole.
   - Otherwise collapse the largest nested collection to `[…N]` with its dig form, and repeat until the rest fits.
   - A collapsed collection costs its count and its form; a string is never collapsed while a collection could be.
3. **Strings print whole** while they fit what is left after structure. Only when every nested collection is
   already collapsed is the longest string cut, keeping its head, with its `subs` form. A docstring is never cut so
   that a digest can show.
4. **A large root sequence or set shows whole members in order** until the budget ends, then one `…N` and one
   `drop` form. There is never a run of collapsed members. Breadth across members of one sequence is worth less than
   whole members.
5. **All numbers are profile dials.** These are the budget, the minimum head shown for a cut string, and the
   largest map whose keys may be re-sorted. Today's `chunk-width`, `scalar-bound`, `name-bound`, `nest-cap`,
   `sorted-bound`, `tuple-bound` and `text-limits` become dials or disappear.
6. **One last resort.** `core-text` keeps the same selection under a structural profile; when that fails, the line
   is the class and identity. `bounded-text` and `datum` are deleted.

The alternative computed in the probe follows 2–4 roughly. It is a disposable fold, not the production
algorithm. It kept every docstring whole in the fn entity, task and query cases, at the same or fewer tokens.

## Options the conversions need

The printer and the render request lack these options today.

| Option | Need | Replaces |
|---|---|---|
| **O1. Nested dispatch** | Every reached keyword-lookup map and Var is offered to declared selection under the one budget. A render's own output is not re-offered. | Category 1b, `core-text` leaves in HTML renders, the hand hiccup in category 5, `with-related` in the printer |
| **O2. Leaf charged to the parent budget** | A value printed inside another unit spends the parent's remaining budget, with no budget of its own. | 21 non-edge `core-text` calls (F44) |
| **O3. Budget by position or age** | A history unit's budget comes from its position. Over it, the printer emits the whole-value reference elision: handle, estimated tokens and requery form. | `:seon.config.context/reference-tokens` and the hand reference in `exchange-ai` (F64) |
| **O4. Detail level and reference depth** | `:seon.render/distance` becomes a first-class render-request member, read by selection, for must-reads, `finding-ai` and the walk. | The `render-call` in `must-read-ai` (F37) and finding-level prose |
| **O5. Multi-part unit** | Several values, one of them already-printed text (a raw node), are printed as one map under one budget. | `seon.repl/reply-map` (F39), transcript joins (F42), `with-errors` and `floor-text` (F35) |
| **O6. Consumer** | `:seon.render/ai`, `:seon.render/html` and `:seon.render/form` exist. Missing are a line or log consumer (single-line composition already exists in the profile) and the MCP-versus-context difference. | `mcp-prose`, log `println`s of values |
| **O7. Declared display units** | Attribute schema properties (`:seon.config/display-unit` and `display-divisor` exist) are applied by the printer for every attribute. | `format` calls in `seon.ai`, `seon.agent`, `seon.context` and `seon.render.transcript` (F51, F53, F63) |
| **O8. Tail-first string cut** | For outputs and logs, the end is what matters. | `take-last 30` in `seon.test.platform/run-check!` (F67) |
| **O9. One entry** | `(print value options)` returns text, hiccup and size from one selection, through `shown-result`'s stage. | The seven public entries and three budgets (F91) |

## Permanent static check

The check is a deftest over clj-kondo analysis, run in-process with `clj-kondo.core/run!` on `src` and `script`
with `{:analysis {:keywords true}}`. It fails on any of the four rules below.

It needs **no hand list of render functions**. The render set is derived each run from `seon.schema.edn/packaged-forms`:

```clojure
R = every symbol value of :seon.render/ai, :seon.render/html or :seon.render/form found by walking the packaged forms
```

The printer set is derived from one declared root, `seon.sci.eval/shown-result`:

```clojure
P = vars reachable from shown-result over :var-usages edges, excluding every var in R
```

Renders are invoked by resolved symbol, so no static edge reaches them, and P never absorbs a render.

| Rule | Fails when | Covers |
|---|---|---|
| **R1** | A `:var-usage` targets a var in R and its `:from-var` is not in P. A render calling a render fails too (1b), and so does a page (1a). | Category 1 |
| **R2** | A `:var-usage` targets `seon.render/render-call`, `seon.render/declared-projection` or `seon.render/selection` and its `:from-var` is not in P. | Category 2 |
| **R3** | A `:var-usage` targets `clojure.core/pr-str`, `prn-str`, `print-str`, `println-str` or `with-out-str`, any var in `clojure.pprint` or `fipp.*`, or `clojure.core/print-method`. The only exception is a var carrying `^{:seon.print/emitter true}`. That metadata is set at the owner on the printer's leaf emitter (`seon.print/literal`) and the one canonical EDN encoder; it is a declaration at the owner, not a list in the test. | Categories 3a–3d. A 3d site converts by calling the canonical encoder, which is an ordinary call and passes. |
| **R4** | A `clojure.core/subs`, `take`, `take-last` or `drop-last` whose count argument is a number literal, or a `def` of a number in a namespace that declares a var in R or P. Needs `:analysis {:arglists true}` plus a clj-kondo hook on those four symbols that registers a finding when the count node is a `:number` token. The hook is a few lines of `analyze-call`. | Category 4's hardcoded numbers |

**R1 and R2 pass trivially if P is computed wrongly.** The test should also assert that P contains
`seon.render.value/prepare` and `seon.render/render-call`, as a liveness check.

**First run.** Expected failures are about 90 sites for R1/R2, about 135 for R3 including 3d, and about 25 for R4.
It is red until the conversions land, so it should be added with the first conversion and its count tracked down to
zero, or each finding becomes its task with this test as the proof.

**Limits.**

- `str` and `format` applied to a non-string value (3c and 3e) cannot be caught from analysis without type
  inference. They stay with review and with R1 once O1 and O7 exist.
- Category 5 labels are legitimate and are not checked.

## Out-of-scope findings

- **A foreign Clojure file is unreadable on disk.** `test/seon/cluster/db_flow_test.clj` was modified before this
  lane started and does not parse ("opening ( with no matching )" at 2168:1, reported by the PostToolUse hook). This
  lane did not touch it; its owner should repair it.
- **`runtime_status` printed whole values.** On 2026-10-16 its reply included 27 full agent-error message strings,
  each about 300 characters, under `:seon.problems/agent-error-values`. The health AI render passes them through
  whole (F07 and F71 are its path).
- **Probe defect observed.** A JVM-mode form with an extra `)` returned the value and the reader error together:
  the error was correct and the value still returned, so nothing needs fixing.
