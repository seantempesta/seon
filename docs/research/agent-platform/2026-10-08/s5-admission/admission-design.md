---
title: S5-04 admission design, assembled from the ten S5-03 probes
type: research
status: superseded 2026-10-08: evidence for redo/pass-and-recording.md
date: 2026-10-08
lane: admission-design
---

> **Superseded 2026-10-08.** Both plan reviews ordered the admission boundary redone (its K2 contract refusal, `rebase` refusal path and guarded-deletion refusal contradict the REPL law and the installed P4). The redesign is `../redo/pass-and-recording.md`; its §7 rows are held in [the agent-loop ledger](../../../../seon/schedule/agent-loop-ledger.md) as P8 (blocked-on-redo). The ten case notes in this directory stay as evidence.

# S5-04 admission: serial REPL semantics, captured writes, one analysis, final state

What exists (HEAD `f4086e6fc`): each form is read, evaluated and printed, then `seon.batch/derive-step`
(`src/seon/batch.clj:543`) runs `seon.turn.batch/derive-form` (`src/seon/turn/batch.clj:303`) with one clj-kondo call
(`analyze-form:226`), and `apply-pending` (`batch.clj:513`) validates that form's facts onto a working value with
`db/with` before the next form. The design keeps that data flow and moves the work: writes are captured as values when
they happen, the evaluation loop does no analysis and no `db/with`, and the pass analyzes once and stores the final
state in its one settlement. Approach: plain values on the existing `:seon.batch/write`, the existing multi-request
`seon.fn/runtime-analysis-batch` (`src/seon/fn.clj:945`), the existing fold (`fold-change:530`) and the existing
settle-then-`rebase` refusal path (`settle:716`, `revalidate:683`). No registry, atom, cache or printer.

## 1. The model

**The law (owner, 2026-10-08): forms execute serially and behave as in a normal REPL.** Every form sees the effects of
every earlier form in the pass. Nothing an earlier form did is refused because a later form changed it. Each evaluation
keeps its own value and observation. The stored program after the pass is the final state of each namespace, as a
REPL's namespace is: one entity per name, the last definition of it. The only refusals are those a normal REPL also
gives: a form that fails to compile or run (an unresolved symbol, a call to an unbound Var, a malformed schema or
contract at its own form), and another writer's concurrent change (detect and push back, `changed-since-basis:550`).

**Captured at each write, when it happens.** SCI's write observer fires once per write, after the root is set
(`reference-code/sci/src/sci/lang.cljc:71-75`, `:121-127`), so a failed init captures nothing. On each `:root` `:bind`
write, `seon.sci.arm/write-event` (`arm.clj:71`) already records symbol, Var, origin, kind, `:seon.batch/fn?` and
`:seon.batch/arm-error`; it adds the Var metadata subset the row reads (`:malli/schema :doc :arglists :private :test`)
and `:seon.batch/resolver`, the namespace row `seon.turn.batch/namespace-entity` (`:58`) returns for the evaluating
namespace at that write (2.3 us, 3.1 KB). Arming stays at the write and arms the contract authored at it (landed,
`b391c0ef9`); the row's contract facts use the projection the write was armed under (landed, `ca00cfd7e`). Expansions
and the `declared` prelude are already write-time values. Print-time writes join their form: `observe` runs under the
evaluating context (landed `b5ff51e4c`).

**Agent-visible effects commit as they run.** The agent's own `transact!` already commits in its own transaction on its
custody connection, separate from the one machinery transaction (owner ruling). `seon.schema/register!` inside an
evaluation joins it (change E): it has consequences beyond the form, so it is durable before it answers. It commits the
schema entity and its attribute change (today's `schema-tx:411` data, `attribute-change-tx:388`) through the
evaluation's world writer on the evaluation's custody connection, both supplied by schema name as world members.
Arming then reads the connection's current value (`db/db conn`, its carried projection cached by commit id) instead of
the pass-start value plus the mutable candidate delta (`arm/arming-projection:18`), and the end stage derives against
the connection's value at the end of the pass. So a later form's `transact!` and `(seon.db/db)` see the attribute, and
the delta collector retires for evaluations; namespace loading keeps the collector. `register!` outside an evaluation
(boot, namespace load) is unchanged.

**Once per pass, after the last print.** One `runtime-analysis-batch` call over every defining request: one per form
whose captured writes include a function or test root, with its own captured resolver row, the cumulative `declared`
and its expansions; one per definition whose namespace differs from the form's, sliced from the entry source at the
write's origin; local usages from the same run (`:locals true`). Non-function defs make no request (captured `fn?`).
Each definition is analyzed under the row it was written under, so its edges are what its Var does; its digest is
`db/definition-digest-of` (`db.clj:5174`) under the namespace row the settlement stores, the final one, which is the
writer's existing rule, so the writer check is unchanged and no attribute is added. Then `derive-form` per form against
that analysis from captured values only, with `derive-step`'s per-form catch; `fold-change` folds by identity to the
final state (last definition wins, removals in order, a value def over a function retires it); the one settlement
validates the folded program at the writer once, and only a writer refusal re-derives form by form through the existing
`rebase`, attributing the refusal to its form. `apply-pending` and the per-form `db/with` are deleted.

## 2. The ten cases under the law

| Case | Today | Designed | Evidence | Proof (`seon.cluster.turn-test/…`) |
|---|---|---|---|---|
| def → redef → use | per-form derive right after the form; earlier-contract arming (fixed, `b391c0ef9`) | captured metadata; fold keeps the last row; both calls return their own values | `redef-design.md` results 1-6 | `a-pass-records-the-definition-each-form-observed` |
| macro, then use | works per form | same inputs in the one call; exact call span | `macro-design.md` probes 1-6 | `a-pass-admits-a-macro-before-its-use` |
| unmap, remove-ns | core `ns-unmap` with a surviving referrer refused silently; `remove-ns!` misses a same-pass namespace | final state stored: the removed name retracted, referrers kept, a finding names their edge to it (question 4.1); `remove-ns!` decides against the live namespace | `removal-design.md` table, findings 1-2 | `a-pass-records-unmap-and-namespace-removal-in-order` |
| ns and alias change | earlier use refused by the writer's digest check; same-form `h` admitted with a false edge | no refusal: every use admitted, edges under its captured row, digest under the final row | `alias-design.md` probes 1-10 | `a-pass-analyzes-aliases-at-their-observed-position` |
| nested, cross-namespace | correct; second analysis and an extra kondo run | requests in the one call | `nested-design.md` | `a-pass-joins-nested-and-cross-namespace-definitions-once` |
| private def | custody correct; stale row after a value rebind | `fn?` captured; value def retires the row | `private-design.md` | `a-pass-preserves-private-definition-custody` |
| schema, then use | gate works; agent write to the new attribute refused until the next pass | `register!` commits; write and read in the same pass succeed | `schema-design.md` probes 1-6 | `a-pass-admits-a-schema-before-its-use` |
| failed def, dependent | K1-K3 per form | unchanged: these are the REPL's own failures | `failed-def-design.md` | `a-failed-definition-does-not-certify-a-later-dependent-form` |
| lazy realization | observed under the evaluating context (landed `b5ff51e4c`) | joins its form; no further change | `lazy-design.md` probes 1-4 | `a-pass-captures-definitions-realized-after-the-form-body` |
| prose before def | reader restart (fixed, `e24fda839`); an unjoined write is silent | unjoined write delivered | `prose-design.md` | `a-pass-keeps-original-definition-spans-after-prose` |

Refusals the law keeps: K1 (unresolved symbol, unbound Var), K2 (a contract that does not arm, refusing by name until
redefined), a forward schema reference at its own form, a malformed `register!`, and `changed-since-basis`. None refuses
an earlier form because of a later one. A nested definition capturing locals stays live and private (its text alone
does not reproduce the Var); that is a classification, not a refusal.

## 3. Behavior changes and their regressions (each row in one pass)

| Change | Today | After | Regression row |
|---|---|---|---|
| A. Value def over a function retires its row | `(defn fl [] 1)` then `(def fl 5)` keeps the defn row (`seon.turn.batch/a-value-def-over-a-function-keeps-the-stale-function-row`) | the fold retires it | `a-pass-preserves-private-definition-custody`: `fl` has no row |
| B. Print-time lazy writes observed | landed `b5ff51e4c` (`seon.sci.observe/a-definition-realized-while-showing-a-lazy-value-is-observed`) | observed and joined | `a-pass-captures-definitions-realized-after-the-form-body` rows 1, 3 |
| C. Unjoined definition write delivered | silent (`seon.turn.batch/an-unjoined-definition-write-is-silent`) | a `:seon.batch/notes` member beside the value | `a-pass-keeps-original-definition-spans-after-prose` |
| D. Alias retarget: no refusal | earlier use refused (`seon.db/the-digest-refusal-names-no-cause`); `h` gets a false edge (`seon.turn.batch/analysis-uses-the-namespace-row-after-the-form`) | require `x` → string, `(defn u1 … (x/upper-case "a"))`, `(u1)`, `(alias 'x 'clojure.set)`, `(defn u2 … (x/union #{1} #{2}))`, `(u2)`: both calls correct; `u1` calls `clojure.string/upper-case`, `u2` calls `clojure.set/union`, both admitted; the namespace row is the final one | `a-pass-analyzes-aliases-at-their-observed-position` |
| E. Same-pass schema write | `transact!` refused (`undeclared attribute`) | `(register! :k [:int {:min 0}])`, `(transact! [{… :k 7}])`, read back: `7`, all three correct; `-1` refused by the schema, as in the next pass today | `a-pass-admits-a-schema-before-its-use` |
| F. Redefinition with changed arity | (fixed by `b391c0ef9`; pinned here) | `(defn a [x] (inc x))`, `(a 1)` → 2, `(defn a [x y] (+ x y 100))`, `(a 1 2)` → 103; stored `a` is the second, one `:seon.fn/source` datom | `a-pass-records-the-definition-each-form-observed` |
| G. Removal stores the final state | refusal (silent) for a surviving referrer | per question 4.1 | `a-pass-records-unmap-and-namespace-removal-in-order` |

## 4. Owner questions

Answered: same-pass schema write (allowed, change E) and alias retarget (no refusal, change D; option C not needed).

1. **Core `ns-unmap` or `remove-ns` with a surviving referrer** (`defn pa`, `defn pb` calling it, `ns-unmap pa`). A REPL
   unmaps; today the stored program refuses the removal (`my.program` deletion report) and says nothing. **Recommend
   the law**: store the final state (`pa` retracted, `pb` kept with its `calls` edge naming `pa`, as already happens for
   any call to an unstored Var) and deliver a finding naming `pb`. `my.program/ns-unmap!` keeps refusing before it
   mutates: it is the guarded tool an agent asks for, and nothing has happened when it refuses.
2. **Program reads of the same pass.** A later form's `(seon.db/pull … [:seon.fn/sym f])` does not see `f` defined
   earlier in the pass (`redef-design.md` result 4): program entities are the settlement's record, not the form's
   effect. **Recommend** program tools answer from the live world for names the pass defined (`doc` already reads SCI
   metadata; `remove-ns!` and `source` gain the same), and plain database reads keep reading the committed head. A
   per-form machinery commit (50-110 ms each) is the alternative; not recommended.
3. Standing from the first version: a definition realized by a later form is recorded private with the reason
   `:defined-outside-this-form` (`seon.turn.batch/a-write-defined-by-an-earlier-form-is-private-without-a-reason`,
   unfiled), since its text is in another form.

## 5. Prerequisites and defects

| Item | State | Order |
|---|---|---|
| Reader position after prose (`seon.sci.forgive/reader-at-restarts-line-and-column-numbering`) | landed `e24fda839` | done |
| Arm the authored contract; same-form schema projection (`seon.sci.arm/a-redefinition-in-one-pass-is-armed-with-the-earlier-contract`, `seon.turn.batch/a-function-using-a-schema-registered-in-the-same-form-is-not-admitted`) | landed `b391c0ef9`, `ca00cfd7e` | done |
| Linear `runtime-analysis-batch` | landed `94affe518` (1,600 forms 119 ms, 162 MB) | done |
| One namespace row per run (`seon.fn/batched-analysis-uses-one-namespace-row-per-run`) | open | S5-04a |
| Declared-var edge, same pass (`seon.turn.batch/a-declared-var-call-stores-no-edge`) | open | within: the one analysis holds the later definition, so `program-targets` (`fn.clj:1415`) keeps the edge |
| Callee defined in a later pass (`seon.fn/a-dependent-keeps-no-edge-to-a-callee-defined-after-it`) | open | after S5-04: re-analysis of dependents is its own design |
| Retarget divergence from Clojure (`seon.fn/alias-and-require-as-retarget-silently`); stale digests across passes (`seon.db/a-cross-pass-alias-retarget-leaves-dependant-digests-stale`) | open | after: a stored earlier use re-analyzes differently under the final row, as a REPL namespace would after a retarget; those tasks own it |
| Refused-write hint (`seon.db/a-refused-write-names-the-undeclared-key-among-the-registered-candidates`); registration refusal type (`seon.schema/a-registration-refusal-carries-an-error-type`) | open | independent |
| Silent core `ns-unmap` (`seon.turn/a-refused-core-ns-unmap-is-silent`) and same-pass `remove-ns!` (`my.program/remove-ns-refuses-a-namespace-defined-earlier-in-the-same-pass`) | recorded only on the probe branch; root to file | within S5-04i |

## 6. Cost model (measured in the probes, warm, default's JVM)

| Work | Today | Designed |
|---|---|---|
| analysis, N defining forms | N calls: 13.0 / 62.6 / 122.1 / 232.5 ms at N = 1/5/10/20 | one call: 11.5 / 13.9 / 13.7 / 16.3 ms |
| non-function def | 19 ms, 8 MB wasted analysis | 0 |
| cross-ns / nested definition | 17-50 ms second analysis; 7 ms, 3.8 MB locals run | requests in the one call |
| `apply-facts` per form followed by another | 64 ms mean (97 calls), 111 ms clean | 0; the writer's one validation of the folded program |
| arming a redefinition | 9-11k calls, 12-31 ms (snapshot of a working value changed per form) | fresh-name cost, 2-3 ms |
| capture | — | 2.3 us, 3.1 KB per root bind |
| `register!` | 0.67-2.9 s in the form (`seon.schema/schema-registration-rechecks-the-whole-program-per-key`) | the same plus one agent transaction (a small `transact!` measured 81 ms); arming after it pays one carried-projection build for the new commit, measured by slice S5-04g |

A five-`defn` pass today pays about 5 × 20 ms analysis + 4 × 64 ms apply ≈ 360 ms of Seon work, over the 300 ms
definition budget; designed, about 14 ms plus the settlement's validation. A schema pass stays over one second until
the `register!` task is fixed; S5-04 adds its transaction and must report it. Allocation per pass was not isolable in
the shared JVM; each slice reports its O row.

## 7. S5-04 slices (group B, serial)

| Id | Change | Files | Proof tests | Budget | Deps | Group |
|---|---|---|---|---|---|---|
| S5-04a | `runtime-analysis-batch` partitions by `(juxt :namespace-name :seon.fn/namespace-row)` | `src/seon/fn.clj`, `test/seon/fn_test.clj` | new `seon.fn-test/a-batch-analyzes-each-request-under-its-own-namespace-row`; existing `…/an-alias-an-earlier-form-requires-resolves-in-later-forms-of-the-batch` | O | none | B′ |
| S5-04b | Capture `:seon.batch/meta` and `:seon.batch/resolver` on root binds; `write-entity` reads captured `fn?`/meta, `analyze-form` the captured row | `src/seon/sci/arm.clj`, `src/seon/sci/observe.clj`, `src/seon/turn/batch.clj`, `resources/seon/schemas/seon.batch.edn`, `test/seon/cluster/turn_test.clj` | new `…/a-pass-records-the-definition-each-form-observed` (row F) | O | S5-04a | B |
| S5-04c | Change D: digest under the final namespace row, edges under the captured row | `src/seon/turn/batch.clj` (`analyzed-entities`), `test/seon/cluster/turn_test.clj` | new `…/a-pass-analyzes-aliases-at-their-observed-position` | O | S5-04b | B |
| S5-04d | Change A in the fold | `src/seon/turn/batch.clj`, `test/seon/cluster/turn_test.clj` | new `…/a-pass-preserves-private-definition-custody` | O | S5-04b | B |
| S5-04e | Reason `:defined-outside-this-form` (change B landed) | `src/seon/turn/batch.clj`, `resources/seon/schemas/seon.batch.edn`, `test/seon/cluster/turn_test.clj` | new `…/a-pass-captures-definitions-realized-after-the-form-body` | O | S5-04b | B |
| S5-04f | Change C: unjoined writes and facts errors of non-binding forms delivered as notes | `src/seon/turn/batch.clj`, `src/seon/turn.clj` (`:5025`), `resources/seon/schemas/seon.batch.edn`, `test/seon/cluster/turn_test.clj` | new `…/a-pass-keeps-original-definition-spans-after-prose` | O/F | S5-04b | B |
| S5-04g | Change E: `register!` in an evaluation commits through the world writer; arming reads the connection's value | `src/seon/schema.clj`, `src/seon/sci/arm.clj`, `src/seon/turn/batch.clj` (`schema-tx` moves to the schema owner), `resources/seon/schemas/seon.schema.edn`, `test/seon/cluster/turn_test.clj` | new `…/a-pass-admits-a-schema-before-its-use` (row E, the same-form variant, malformed and forward-use rows) | O/F | S5-04b | B |
| S5-04h1 | `derive-form` takes a supplied analysis; `pass-requests` builds the vector; `analyze-form` deleted | `src/seon/turn/batch.clj`, `test/seon/cluster/turn_test.clj` | `…/a-pass-admits-a-macro-before-its-use`, `…/a-failed-definition-does-not-certify-a-later-dependent-form` (with the same-pass declare row) | O | S5-04c..g | B |
| S5-04h2 | One end stage: analysis, per-form derive, fold to final state, settle; `apply-pending`, the per-form `db/with` and the evaluation delta deleted | `src/seon/batch.clj`, `src/seon/turn/batch.clj`, `src/seon/sci/arm.clj`, `resources/seon/schemas/seon.batch.edn`, `test/seon/cluster/turn_test.clj` | every row above, O row on a five-`defn` pass | O/F | S5-04h1 | B |
| S5-04i | Change G (after question 4.1): removals store the final state with a finding; `remove-ns!` and `source` read the live world for same-pass names (question 4.2) | `src/seon/turn/batch.clj` (`removal-facts`), `src/my/program.clj`, `test/seon/cluster/turn_test.clj` | new `…/a-pass-records-unmap-and-namespace-removal-in-order` | O/F | S5-04h2, questions 4.1-4.2 | B |
| S5-04j | Cross-namespace slice and locals in the one call; `captured-locals`' run and the second analysis deleted | `src/seon/turn/batch.clj`, `src/seon/fn/analyzer.clj`, `test/seon/cluster/turn_test.clj` | new `…/a-pass-joins-nested-and-cross-namespace-definitions-once` | O | S5-04h2 | B |

## Limits

Design only; no code changed. Numbers are the probes' (HEADs `0e3c33020` to `d11dbe2b6`), not re-measured here; the
`register!` commit and the post-commit projection build are unmeasured until S5-04g. Default is at `a4d438dc6`,
`:faulted` (`seon.turn.world/record-fault`, task `3d2fb1b41dc8`), already on the health list.
