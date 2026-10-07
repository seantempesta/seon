# S5-03b: macro defined, then used (admission design proof)

Lane s5-03b. Notes only; every probe ran on the lane's own SCI agent branch (agent `s5-03b`, namespace
`scratch.macro`) or as a read-only JVM call against default's analyzer with explicitly passed database value. HEAD
`cd8a481c2`. No production or test file changed.

## Owners read

- `src/seon/batch.clj:513` `apply-pending`, `:543` `derive-step`: validate the previous form's facts onto the working
  value only when another form follows.
- `src/seon/turn/batch.clj:303` `derive-form` (declared prelude from bind writes plus namespace unbound Vars),
  `:208` `form-relative-expansions`, `:226` `analyze-form`, `:515` `apply-facts`.
- `src/seon/fn.clj:944` `runtime-analysis-batch` (expansion hooks at each request's first row);
  `src/seon/fn/analyzer.clj:62` `expansion-hooks` (hook keyed by macro symbol, matched on the call node's COMPLETE
  `[row col end-row end-col]`); `src/seon/sci/observe.clj:36` expansion observer (writes `:seon.batch/op :expansion`
  with `:call-span [line column end-row end-col]` and the expansion, in evaluation order, own-context only).

## Probes and observed data

All through the one entrance (`eval_clj`, SCI), one run = one pass.

1. **Macro, then use in a later form** (`defmacro twice`, `defn use-it [] (twice 1)`, `(use-it)`; `defmacro mk`,
   `(mk made-fn)`, `(made-fn)`): values `1` and `:made`. Stored rows: `twice`/`mk` `:seon.fn/macro? true`,
   `defined-by clojure.core/defmacro`; `use-it` `calls [clojure.core/defn scratch.macro/twice]`; `made-fn`
   `source "(mk made-fn)"`, `defined-by clojure.core/defn`, `calls [clojure.core/defn scratch.macro/mk]`. Each form 0-4 ms
   in the reply.
2. **Macro and use in one form** `(do (defmacro m3 [] 7) (defn f3 [] (m3)))`: value 7, `f3 calls [defn m3]`,
   `m3` entity present. SCI evaluates the `do` sequentially, so same-form macro-then-use needs no admission.
3. **Macro redefined between two uses** (`v1` returning `:v1`, `(v1 a1)`, `v1` returning `:v2`, `(v1 a2)`): `[(a1) (a2)]`
   is `[:v1 :v2]`; both rows `calls [.. scratch.macro/v1]`; `v1` entity holds the later body. A third pass redefining
   `v1` to `:v3` and re-running `(v1 a1)` returned `:v3`; `a1` kept `:seon.program/definition-digest 71e486...` while
   `:seon.program/analyzed-source-digest` changed (`c4d419...`). The definition digest is of the authored call, not its
   expansion; macro drift reaches `a1` only through the `calls` edge to `v1`. Keep that in mind for S5-04.
4. **Failing macro expansion** (`(defmacro fail-mac [] (throw ..))`, `(defn uses-fail [] (fail-mac))`, `(defn after [] :ok)`):
   the use returns an error value with message `boom`; `fail-mac` and `after` are stored, `uses-fail` has no entity, and
   the next form is unaffected. No certificate or stale fact for the failed use.
5. **Expansion observer data** (plain `sci.core` context on the fork, `*expansion-observer*`): `(mk made-fn)` reports
   `call (mk made-fn)`, `meta {:line 1 :column 1}`, `exp (clojure.core/defn made-fn [] :made)`; the use `(twice 1)` inside
   `(defn use-it [] (twice 1))` reports `meta {:line 1 :column 17}`, `exp (do 1 1)`. The generated inner forms
   (`defn`, `fn`, the `defmacro` expansion itself) report without end positions in a bare context; production keeps
   only expansions with `line` and `end-row` and a resolved macro `:ns`, and `form-relative-expansions` further keeps only
   those whose span owns a `:bind` write (`:expanded-by`) and are not `clojure.*` macros.
6. **Falsifier of the end-of-pass seam: one `runtime-analysis-batch` over the pass** (JVM, read-only, default's db):
   - requests `(defmacro mk ..)`, `(mk made-fn)` with expansion `{:macro scratch.macro/mk :call-span [1 1 1 13]}`:
     the hook yields a `var-definition made-fn defined-by clojure.core/defn row 5 col 1 end-col 13`. With
     end-col `12` the hook does NOT match and the definition is silently absent. **The span must be exactly the node's
     complete span, `end-col` exclusive (12-character form, `13`)**; SCI's observer meta supplies exactly this.
   - with no expansions, no `made-fn` definition is found at all (the macro is opaque to clj-kondo).
   - four-form pass (`mk`, `(mk made-fn)`, `twice`, `use-it`): one batch call 16 ms and 6.9 MB steady (mean of 20 after
     5 warmups); four per-form calls on the same inputs 116 ms and 22.3 MB. The per-form shape grows with each
     form's `declared` prelude.

## Design: the smallest seam for this case

**No early admission is needed for a macro before its use.** Everything a later form needs from the macro is already in
the SCI world (the Var), and everything analysis needs is a value of the pass:

- `declared`: the symbols of earlier `:bind` writes, in order (already `derive-state`), which makes `scratch.macro/mk`
  resolvable and gives the use its `calls` edge to the macro symbol.
- the form's `:expansion` writes, carried as `:seon.batch/expansions` in form-relative coordinates
  (`form-relative-expansions`), which give kondo the exact generated definition at the exact call node.

Both are write-time observations. A single end-of-pass analysis can take them per form (declared snapshot and
expansions per request, as `runtime-analysis-batch` already accepts), proven above to find the generated `made-fn`
definition and the macro/use edges. The macro redefinition probe shows why the observation must be per occurrence, not
the final macro: `a1` and `a2` were expanded by different bodies at different times, and the hook is keyed by call
span, not by macro identity.

What still needs the working value is only the facts stage (`entity-facts`, `removal-facts`, the writer's validation),
for forms that read stored facts (schema before use, owned by S5-03g). For a macro case `apply-pending` has no work a
later form reads, so it should stay a bounded early admission only when a later form depends on stored facts. Its
cost stays as measured by S5-04; this case contributes no new reason for it.

### Contract for the observation (to approve)

Per defining form, immutable and captured at write time: `{:declared [qualified-symbol ...]` (those bound before the
form) `:expansions [{:macro :call-span :expansion} ...]}` in the form's own coordinates. These are the existing
`:seon.batch/write` `:expansion` shape and the existing `:seon.fn/declared` request member: no new key and no new
function. The proposed implementation packet is one function: `runtime-analysis-batch` called once for the pass over
the requests of forms that observed a definition (zero calls otherwise), with the per-form declared snapshots. Estimated
well under 150 lines, and a net deletion of `analyze-form`'s per-call setup.

### Proposed proof (S5-04 table row)

`seon.cluster.turn-test/a-pass-admits-a-macro-before-its-use` asserts, as data: the use's value; the expansion write's
call span equals the use's `:bind` origin span; `made-fn` row `defined-by clojure.core/defn`, source `(mk made-fn)`,
`calls` containing `scratch.macro/mk`; macro row `:seon.fn/macro? true` with its digest; the redefinition variant
(`[:v1 :v2]`, both rows call the later macro entity); the failing-expansion variant (error value, no row for the use,
next form stored). The test does not exist at HEAD and is not claimed green.

## Findings outside this slice

- `(declare zz)` then `(defn calls-zz [] (zz))` in one pass stores `calls [clojure.core/defn]` with no edge to
  `scratch.macro/zz` although `derive-form` adds declared Vars to the prelude "so a call to one is an edge". Likely the
  edge is dropped because no entity exists for `zz`; unverified. Could not record a durable task: `seon.task/create!`
  needs `:seon.task/ns`/`name`/`kind` and creating it on my branch would not reach `resources/seon/tasks`. Root: record
  against `seon.turn.batch/derive-form`.
- The generic query `[:find ?a (count ?e) :where [?e ?a] [(namespace ?a) ?n] [(contains? ..)]]` over the whole agent
  database took 2153 ms and 568,597 calls (`seon.schema.datahike/logical-attribute?` 568,485 times); a self-inflicted probe
  but the repeated-call shape is a finding for `seon.db/q`.
- Per-form analysis cost: 29 ms mean per call in the four-form probe, versus 16 ms for the whole pass in one call.

## Design check

The design held against the real code for the macro case: the expansion hook's exact-span matching is the only
fragile part (end-col off by one drops the definition silently, no diagnostic). A producer contract that the span's
`end-col` is exclusive, or a check that every owning `:bind` origin found its hook match, would make that loud; this is
for S5-04, not decided here. RESET NEEDED: none.
