---
title: "Card RP-6a: Derive, fold and record once, after the last form"
type: reference
status: active
created: 2026-10-08
---

# RP-6a Derive, fold and record once, after the last form

Ledger row RP-6a (alias S5-04h2, group B). Design: `redo/pass-and-recording.md` §2 ("Nothing per form touches the
database. After the last form: one clj-kondo analysis, a per-form derive from the captured values, a fold by identity
to the final state, and ONE recording transaction"), §6 ("per-form `db/with`: 64 ms mean per following form → 0").
Lane: Sonnet. Cited at HEAD `e7f120672`. Two lettered slices, each ~15 minutes: **RP-6a1** moves derive and fold to
the end of the eval request; **RP-6a2** makes the analysis one call for all forms and adds the FINISH stage's findings.

## What exists

`seon.batch/form-step` (`src/seon/batch.clj:587-614`) runs, per form: read, `apply-pending` of the previous form's
facts through `(:seon.batch/apply request)` (= `seon.turn.batch/apply-facts`, a `seon.db/with` on the working value,
`src/seon/turn/batch.clj:528-541`), evaluate, place, publish, freeze, and `derive-step` (`:543-585`), which derives the
form's change and its facts on the working value and leaves them pending for the next form. After the loop a last
`apply-pending` folds the final pending change (`:735`), and `settle` records.

## RP-6a1: derive and fold after the last form

### Files

- `src/seon/batch.clj`
- `resources/seon/schemas/seon.batch.edn` (`:entry` gains `:seon.batch/form-writes`)
- `test/seon/repl_differential_p8_test.clj`

### Functions

- CHANGE `seon.batch/freeze-step` (private, `:480`): also stores the form's own writes on its entry,
  `:seon.batch/form-writes (vec (:seon.batch/form-writes batch))` (they are the batch-level
  `:seon.batch/form-writes` collected for this form, `:196-201`).
- CHANGE `seon.batch/form-step` (private, `:587`): delete both `apply-pending` calls (`:600` and `:614`) and the
  `derive-step` call (`:609`). A form step is read → evaluate → place → publish → freeze.
- ADD `seon.batch/derive-all` (private)
  `{:malli/schema [:=> [:cat :seon.batch/request :seon.batch/folding] :seon.batch/folding]}` `[request batch]`.
  When `(:seon.batch/derive request)` is present, reduce over `(:seon.batch/entries batch)` in order with the derive
  state `(:seon.batch/derive-state batch)`: for each entry, skip one whose `:seon.batch/facts-refused` is set (keep
  today's `facts-error` assoc for it, `:548-551`); otherwise compute `declared` with `(:seon.batch/declared request)`,
  `analyzed` with `(:seon.batch/analyze request)` on the basis (`(:seon.batch/basis batch)`: no working value exists
  any more), and `(:seon.batch/derive request) request state analyzed entry (:seon.batch/form-writes entry)`; assoc the
  change's notes and private onto the entry as `derive-step` does today (`:568`, `:577`), assoc
  `:seon.batch/change` when the change is non-empty, and fold it with
  `((:seon.batch/fold request) request program change)`. A throw while deriving one entry becomes a batch diagnostic
  (`(diagnostic :derive ordinal t)`, `:153`), never that form's error. Clock it as `:derive`.
- CHANGE `seon.batch/evaluate-batch` (`:687`): after the loop, replace `(guard :apply #(apply-pending request % false))`
  (`:735`) with `(guard :derive #(derive-all request %))`.
- DELETE `seon.batch/derive-step` (`:543`). `apply-pending` stays until RP-6b deletes it with the apply stage (it now
  has no caller; RP-6b removes it and the stage keys).

### Data

Unchanged batch shape; each entry gains `:seon.batch/form-writes`.

### Proof

`seon.repl-differential-p8-test/an-eval-request-derives-after-its-last-form` (new). In JVM mode, read the armed call
count of `seon.db/with` (`seon.profile/cells` / `observation`, as RP-5's proof reads `invoke-kondo`) before and after an
SCI eval request `"(ns rp6a.a) (defn f1 [] 1) (defn f2 [] (f1)) (defn f3 [] (f2))"` from the agent: the count grows by 0
(HEAD: 2, one apply per following form). Assert data: all three rows are stored with their `:seon.fn/calls` edges
(`(seon.db/pull (seon.db/db) [:seon.fn/calls] [:seon.fn/sym 'rp6a.a/f3])` contains `rp6a.a/f2`).

### Reaching tests

`seon.repl-differential-p8-test`, `seon.turn-test`, `seon.turn.batch-test`, `seon.cluster.evaluate-source-test`,
`seon.cluster.host-test`, `seon.repl-differential-test`, `seon.mcp-entrance-test`.

### Commit

`bin/lane commit <lane> -m "RP-6a1: an eval request derives and folds its program after the last form" --test seon.repl-differential-p8-test --test seon.turn-test --test seon.turn.batch-test --test seon.cluster.evaluate-source-test --test seon.cluster.host-test --test seon.repl-differential-test src/seon/batch.clj resources/seon/schemas/seon.batch.edn test/seon/repl_differential_p8_test.clj`

## RP-6a2: one analysis for the eval request; findings at FINISH

### Files

- `src/seon/turn/batch.clj`
- `src/seon/batch.clj`
- `src/seon/repl.clj` (`findings` joins the program findings)
- `test/seon/repl_differential_p8_test.clj`

### Functions

- ADD `seon.turn.batch/analyze-all`
  `{:malli/schema [:=> [:cat :seon.batch/request :seon.db/database-value [:vector :seon.batch/entry] [:map-of :seon.cluster.eval/ordinal [:vector :qualified-symbol]]] [:map-of :seon.cluster.eval/ordinal :map]]}`
  `[request basis entries declared-by-ordinal]`: concatenates every entry's `analysis-requests` (RP-5) into one vector,
  remembering each entry's first request index; makes ONE `#'sfn/runtime-analysis-batch` call and one
  `analyzed-entities` (`:66`); answers per ordinal `{:analysis a :requests all :entities es :base i}`, where `i` is the
  entry's first request index. An entry with no requests has no key.
- CHANGE `seon.turn.batch/write-entity` and `entry-coords` (`:182`): read the entry's own request through `:base`:
  the entry's first row is `(first (nth (:seon.fn/source-spans analysis) base))`, entity keys are
  `[(+ base k) offset name]`.
- CHANGE `seon.batch/derive-all` (from RP-6a1): compute `declared` for every entry first (it threads in order and does
  not need analysis), call `(:seon.batch/analyze-all request)` once, then derive each entry with its slice.
  `seon.turn.batch/program-request` and siblings set `:seon.batch/analyze-all #'analyze-all`;
  `:seon.batch/analyze` (RP-5's per-entry stage) is deleted from the request and the schema.
- CHANGE `seon.turn.batch/settle` (`:729`): `commit!` keeps the writer report's findings:
  answer `{:seon.batch/applied true :seon.batch/tx-count n :seon.batch/after (:db-after r)}` plus
  `:seon.batch/findings (:seon.db/findings r)` when present (RP-1w puts them on the report). Add
  `[:seon.batch/findings {:optional true} [:vector :seon.program.finding/any]]` to the commit schema
  (`:seon.batch/commit`, `seon.batch.edn` `:200-207`).
- FINISH: CHANGE `seon.batch/evaluate-batch`'s settle guard (`:740-760`): attribute each finding to the entry of the
  form that wrote its subject (the entry whose change holds the subject in its entities or removals; a finding naming
  no subject of this eval request goes to the last entry) as `:seon.batch/findings` on that entry. Add
  `[:seon.batch/findings {:optional true} [:vector :seon.program.finding/any]]` to `:entry`.
- CHANGE `seon.repl/findings` (`src/seon/repl.clj:344`): its answer also holds the emission's
  `:seon.batch/findings`, so `:seon.repl/findings` (`:121`, `response-order`) shows them; the reply-building path
  passes the entry's findings into the emission (`rg -n 'emission' src/seon/turn/batch.clj src/seon/mcp.clj` finds
  where an entry becomes an emission).

### Proof

- `seon.repl-differential-p8-test/an-eval-request-analyzes-once` (new): the `invoke-kondo` count of RP-5's proof grows
  by exactly 1 for `"(ns rp6a.b) (defn f1 [] 1) (defn f2 [] (f1)) (defn f3 [] (f2))"` (RP-5 alone: 3).
- `seon.repl-differential-p8-test/a-dangling-caller-is-a-finding-beside-the-value` (new): after `pa`/`pb` as in
  RP-1, the reply of `"(ns-unmap *ns* 'pa)"` carries `:seon.repl/findings` whose data includes a
  `:seon.program.finding/dangling` naming `rp1.a/pb` (read with `support/reply`). The same deftest deletes an uncalled `lonely` and asserts its reply has no `:seon.repl/findings`.

### Commit

`bin/lane commit <lane> -m "RP-6a2: one analysis per eval request; the writer's findings reach the reply beside their form" --test seon.repl-differential-p8-test --test seon.turn-test --test seon.turn.batch-test --test seon.repl-test --test seon.cluster.host-test src/seon/turn/batch.clj src/seon/batch.clj src/seon/repl.clj resources/seon/schemas/seon.batch.edn test/seon/repl_differential_p8_test.clj`

## Cost

Expected from the work: per eval request, one analysis whose text follows the defining forms (redesign §6: 11.5 / 13.9 /
16.3 ms at 1 / 5 / 20 defining forms against 13 / 62.6 / 232.5 ms for N calls), zero `seon.db/with` during the forms
(64 ms mean per following form today), derive and fold over the captured writes (microseconds per write). Measured
parent: `defn` eval request 126-131 ms / 159 MB, five `defn`s 243-256 ms / 355-370 MB (redesign §1; RP-0's table names
the owners). Report the O probe's three rows before and after each slice; a regression is reported with its numbers.

## Deleted

`derive-step`; the per-form apply calls (the function itself in RP-6b); RP-5's per-entry `:seon.batch/analyze` stage.

## Dependencies

RP-1, RP-4, RP-5 (ledger), and EN-8a (one RECORD owner: the MCP SCI path must record through `settle` so the FINISH
findings reach it). Group B.

## Reset needs

None.
