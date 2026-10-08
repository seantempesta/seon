# S5-03f: private definitions (design proof, notes only)

Question: can the one end-of-pass analysis (S5-04) keep a private definition's custody: the private function stays
usable in its world with its admitted private flag, and a non-function private def stays live context state with no
program entity? Proof for the later implementation: `seon.cluster.turn-test/a-pass-preserves-private-definition-custody`
(not written here).

Probed at HEAD 91b6b5706 on agent branch `s5-03f` (SCI, scratch namespaces `scratch.priv`, `scratch.other`) plus
read-only JVM measurement of `seon.fn/runtime-analysis-batch` against default's database. `runtime_status` shows
default `:faulted` (open fault `seon.turn.world/record-fault`, three occurrences, task 3d2fb1b41dc8; four
over-bound warnings on `seon.fn/analyzed-files`, `seon.cluster.source/publish!`, `seon.cluster/adopt-entities!`,
`seon.fn/index!`). All outside this slice, already on the health list.

## What exists (code)

- `turn.batch/write-entity` (`batch.clj:241`) classifies each final root write after the form's one analysis:
  not `(or (fn? @v) (:test (meta v)))` gives `:seon.batch/private {:join :value :reason :not-a-function}`; a nested
  definition capturing locals gives `:unanalyzed :captures-locals`; else the analysis row. The row is computed
  (join, `captured-locals`, possibly a second analysis) BEFORE the `cond` tests `fn?`, so a non-function def pays for
  an analysis whose result is dropped.
- The private FLAG of a row is the analyzer's, from the source text: `fn.clj:746`
  `:seon.fn/private? (boolean (::analyzer/private entry))`. It is not read from the Var.
- `fn?` and `:test` are read from the live Var root at derive time (`@v`).
- The reply carries `:seon.batch/private` entries (`turn/batch.clj:821`), but the MCP reply shows the persistence
  note from `seon.repl/def-note` (`repl.clj:140`), which reads the SOURCE text for a leading `def`.

## Observed (finite forms through the one entrance, agent s5-03f)

| Form | Live world | Program entity |
|---|---|---|
| `(defn- hidden [x] (* 2 x))`, `(defn pub [x] (inc (hidden x)))` | `(pub 3)` = 7 | `hidden` private? true; `pub` private? false; `pub` calls `[defn inc scratch.priv/hidden]` (edge to the private row) |
| `(defn ^:private meta-private ..)` | works | private? true |
| `(def ^:private state (atom 0))`, `(def ^:private cfg {:a 1})`, `(defn- bump! [] (swap! state inc))` | `bump!` = 1, state 1; a later run reads `@state` = 2 and `cfg` `{:a 1}` | `state`, `cfg`: no row (pull nil) |
| `(def ^:private fv (fn [x] (+ x 10)))`, `(def ^:private pv (partial + 1))`, `(defn use-fv [x] (fv (pv x)))` | `(use-fv 1)` = 12 | `fv`, `pv` HAVE rows (private? true, source is the `def` text); `use-fv` calls them |
| `(defmacro defpriv ..)`, `(defpriv gen-p)` expanding to `defn-` | works | `gen-p` private? true, source `(defpriv gen-p)` |
| `(defn- flip [] 2)` after `(defn flip [] 1)` | `(:private (meta #'flip))` true | private? true (the row replaced) |
| `(def ^:private later 5)` then `(defn later [] 1)` | public Var | private? false |
| `scratch.other/caller` calls `(scratch.priv/hidden 5)`, `(def viaq (scratch.priv/pub 1))` | 10 and 3, no refusal, no diagnostic | `caller` has a `calls` edge to the private `hidden` row |
| `(defn ^{:private (> 1 2)} falsy-meta [] 1)` | Var `:private` false | private? **true** |
| `(defn meta-later [] 2)` then `(alter-meta! #'meta-later assoc :private true)` | Var `:private` true | private? **false** |
| `(defn fl [] 1)` then `(def fl 5)` (same pass, and again as two separate passes with `fl2`) | live `fl` = 5 | row of `fl` stays: source `(defn fl [] 1)` (**stale**) |
| `(def vf 5)` then `(defn vf [] 6)` | live fn | row appears, correct |

Same-run read: `(seon.db/pull (seon.db/db) ..)` in the SAME run as the defining form returned nil for `falsy-meta`;
the next run saw the row. That is the committed head versus the pass working value (S5-03a owns it); it does not
affect private custody because the world is the SCI Var.

## Costs (JVM, default database read-only, namespace `seon.id`, median of 7 after 3 warmups)

| Analysis input | ms | MB allocated |
|---|---|---|
| `(defn- zz-p [x] (inc x))` | 20.4 | 8.0 |
| `(defn zz-q [x] (inc x))` | 27.7 | 8.0 |
| `(def ^:private zz-s (atom 0))` | 18.8 | 8.0 |
| `(def ^:private zz-f (fn [x] (inc x)))` | 19.5 | 8.1 |

Findings: (1) the analysis cost does not depend on privacy or on the definition kind (about 19-28 ms, 8 MB for a
tiny form), so private adds no cost to the end analysis. (2) For a non-function def the per-form analysis is pure
waste today (about 19 ms and 8 MB per such def, row dropped by the `fn?` test). Per-form `:ms` in the replies reads
0-1 ms for all kinds (armed calls 8 for a `defn`, `defn-`, fn-valued `def`; 6 for a non-function `def`), so the
analysis is not what the reply clock shows; the numbers above are direct owner timings, not pass clocks.

## The smallest seam (answers Q7 for this case)

No analysis is needed before a later form: the live private function and the private state are SCI Var roots, which
the pass never defers. The custody facts are all write-time or end-analysis facts:

1. **Classification bit at the bind write.** `fn?` / `:test` must be captured when the bind is observed (a boolean
   on the existing `:bind` write), not read from `@v` after the pass. With one end analysis `@v` shows only the
   final root, so `(defn fl [] 1)` then `(def fl 5)` in one pass would otherwise classify both as the final value or
   both as functions. The captured bit also lets S5-04 leave a non-function def OUT of the analysis request list,
   removing the wasted analysis above. This adds one boolean to the existing write shape; no registry.
2. **Private flag from the end analysis, unchanged.** The row's `:seon.fn/private?` already arrives per definition
   from the single analysis output. Nothing earlier is needed; a same-pass public caller of a private function gets
   its edge from the same analysis source (verified: `pubB` calls `privA`).
3. **A value-over-function rebind must retire the row.** Today `:withdrawn` fires only for `:rebound` joins; a
   `:value` join leaves the previous function row. The pass fold (`fold-change`'s `[:private ..]` assoc) already
   knows the sym's prior entry in the pass; the retirement rule is part of the S5-04 fold, tested with the
   assertion below. This is a BEHAVIOR CHANGE and needs its own regression.

Neither adds an atom, evaluator or analysis before a later form. The private flag from Var metadata (instead of
kondo) is a possible alternative for the two mismatches above; it is a decision beyond this plan and left to root.

## Assertions the later test makes (data only)

In one pass: `(defn- helper ..)`, `(defn api ..)` calling it, `(def ^:private st (atom 0))`, a call that mutates it,
`(def ^:private fn-def (fn ..))`, `(defn fl [] 1)` then `(def fl 5)`. Then: the evaluations' values (the private
function and `@st` read in a later run); the helper row private? true and `api` private? false with the calls
edge to the helper; `st` has no row; the fn-valued def has a row; `fl` has no row (retired); the evaluation's
`:seon.batch/private` entries carry `:not-a-function` for `st` and `fl`. Existing common comparison:
`seon.cluster.turn-test/contracted-redefinition-exactly-replaces-the-entity`.

## Falsifiers and open risks

- If implementation reads `fn?` after the pass instead of at the bind, the rebind case above flips; the test row for
  `fl` falsifies it.
- If the private flag must follow the Var (`alter-meta!` after the bind is not an observed write), the row cannot
  learn it; either accept source-derived privacy (current) or observe meta changes. Not needed by the table row.
- Allocation of the whole pass was not measured; the MB figures are the analysis owner alone.

## Findings for root to file

- `seon.turn.batch/a-value-def-over-a-function-keeps-the-stale-function-row`: `(defn fl2 [] 1)` then `(def fl2 5)` in
  separate passes on a branch leaves `[:seon.fn/sym scratch.priv/fl2]` with source `(defn fl2 [] 1)` while the Var
  is 5, and the second pass renders the Var as a function (`:seon.render.var/contract :missing` example). Cause:
  `write-entity` answers `:join :value` for it, and only `:rebound` withdraws (`turn/batch.clj:344`). Proof test is the
  assertion above.
- `seon.repl/a-fn-valued-def-is-told-nothing-is-persisted`: `(def ^:private fnA (fn [x] (inc x)))` replies "Nothing
  defined with def is persisted" while the pass admitted a program row for it (`fv`, `fnA`, `pv` rows exist).
  `def-note` sniffs the source text for `def`; the pass already knows the truth in `:seon.batch/private`
  (`:not-a-function`). Related existing tasks `seon.repl/a-jvm-def-is-told-it-lives-in-sci`,
  `seon.repl/every-def-repeats-the-same-persistence-note`.
- `seon.fn/private-flag-disagrees-with-the-var-for-computed-metadata`: `(defn ^{:private (> 1 2)} falsy-meta [] 1)`
  has Var `:private` false and row `:seon.fn/private?` true (`fn.clj:746` reads kondo's flag); the reverse for
  `alter-meta!` after the defn. Low severity.
- Observed, not a finding: SCI lets a function in another namespace call a private function by qualified symbol
  (`scratch.other/caller` ran `scratch.priv/hidden`, no diagnostic); CLAUDE.md allows reaching any function on
  purpose.

## Design check

The plan's premise holds for this case: one end analysis and the existing private/value classification keep custody,
provided the fn/test bit is captured at the bind and a value rebind retires a function row. Awkward parts found:
classification reads the live Var late, the old row survives a value rebind, and a non-function def pays an analysis
it throws away. No design decision beyond the plan was taken; Q7 owner approval still gates S5-04.

Probe state: branch agent `s5-03f` holds scratch definitions in `scratch.priv` and `scratch.other` only; one stray
read-only form without the agent argument ran on default and defined nothing. No RESET NEEDED.
