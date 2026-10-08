---
title: S5-04 admission design, assembled from the ten S5-03 probes
type: research
status: proposed to the owner (Q7)
date: 2026-10-08
lane: admission-design
---

# S5-04 admission: captured writes, one analysis, ordered facts

What exists (HEAD `1d547edd1`): each form is read, evaluated and printed, then `seon.batch/derive-step`
(`src/seon/batch.clj:543`) runs `seon.turn.batch/derive-form` (`src/seon/turn/batch.clj:303`) with one clj-kondo call
(`analyze-form:226`), and `apply-pending` (`batch.clj:513`) validates that form's facts onto the working value with
`db/with` before the next form. The design keeps the data flow and moves the analysis: writes are captured as values
when they happen, the evaluation loop does no analysis and no `db/with`, and the pass analyzes once and applies facts
in form order before its one settlement. Approach: plain values on the existing `:seon.batch/write` and
`:seon.batch/entry`, the existing multi-request `seon.fn/runtime-analysis-batch` (`src/seon/fn.clj:945`), the
existing ordered reduce (`revalidate:683`) and fold (`fold-change:530`). No registry, atom, cache or printer.

## 1. The model

**Captured at each write, when it happens.** SCI's write observer fires once per write, after the root is set
(`reference-code/sci/src/sci/lang.cljc:71-75`, `:121-127`), so a failed init captures nothing. On each `:root`
`:bind` write, `seon.sci.arm/write-event` (`arm.clj:71`) already records symbol, Var, origin, kind, `:seon.batch/fn?`
and `:seon.batch/arm-error`; it adds the Var metadata subset the row reads (`:malli/schema :doc :arglists :private
:test`), the test bit, and `:seon.batch/resolver`, the namespace row `seon.turn.batch/namespace-entity` (`:58`)
returns for the evaluating namespace at that write (2.3 us, 3.1 KB). Arming stays at the write: `arm!` (`arm.clj:45`)
arms the contract authored on the Var at that write, compiled by `seon.instrument/compiled-wrapper` from the passed
contract (lane redef-arm), under `arming-projection request working` with `working` the pass-start value plus the
evaluation's schema delta. The derived row's contract facts use the same projection (redef-arm's `write-entity`
change), so a schema registered earlier in the pass, or in the same form, resolves in both. Expansions and the
`declared` prelude are already write-time values (`:expansion` writes, `derive-state`). Deletions (`:unmap`,
`:remove-ns`) and namespace changes stay ordered writes. Print-time writes join their form once `observe` binds the
evaluating context (behavior change B).

**Once per pass, after the last print.** One `runtime-analysis-batch` call over every defining request: one request per
form whose captured writes include a function or test root, with its own captured resolver row, the cumulative
`declared` and its expansions; one extra request per definition whose namespace differs from the form's, its text
sliced from the entry source at the write's origin; local usages in the same run (`:locals true`), so no definition
pays a second kondo run. Non-function defs make no request (their captured `fn?` says so). Then `derive-form` per
form against that shared analysis, reading only captured values, with `derive-step`'s per-form catch, so one bad
contract refuses only its form. Then the bounded per-form step that stays: `apply-pending`'s work (`form-facts` plus
`apply-facts`), in form order on the pass-start working value, for entity, removal and schema facts, because a later
form's removal check reads the referrers an earlier form defined and each refusal must stay its own form's error. This
is `revalidate`'s reduce, which exists. Then the one settlement, unchanged. A failed or refused form never certifies
anything: it has no captured bind (K1), or its derive or apply refusal is its own `:seon.batch/facts-error`.

## 2. The ten cases

| Case | Today | Designed | Evidence | Proof (`seon.cluster.turn-test/…`) |
|---|---|---|---|---|
| def → redef → use | per-form derive reads `@v`/`meta` right after the form; a changed-arity redefinition is armed with the earlier contract | rows from captured metadata; arm from the authored contract; fold by identity keeps the later row | `redef-design.md` results 1-6 | `a-pass-records-the-definition-each-form-observed` |
| macro, then use | works through per-form analysis with expansions and `declared` | same inputs in the one call; exact call span (`end-col` exclusive) | `macro-design.md` probes 1-6 | `a-pass-admits-a-macro-before-its-use` |
| unmap, remove-ns | removal refused against the working value; a refused core `ns-unmap` is silent | removals ordered in the end apply; refusal delivered on the removing form | `removal-design.md` table, findings 1-2 | `a-pass-records-unmap-and-namespace-removal-in-order` |
| ns and alias change | analysis uses the row after the form; batch uses one row per namespace run | captured row per write; runs split by `(juxt :namespace-name :seon.fn/namespace-row)`; earlier use refused with its reason | `alias-design.md` probes 1-10 | `a-pass-analyzes-aliases-at-their-observed-position` |
| nested, cross-namespace | correct; cross-ns definition analyzed twice, `captured-locals` runs kondo again | cross-ns slice is a request in the one call; locals from the same run | `nested-design.md` table, costs | `a-pass-joins-nested-and-cross-namespace-definitions-once` |
| private def | custody correct; non-function def pays a wasted analysis; value over a function keeps the stale row | `fn?` captured, no request; value def retires the row | `private-design.md` table, finding 1 | `a-pass-preserves-private-definition-custody` |
| schema, then use | gate works; same-form schema + contracted fn not admitted | contract facts from the arming projection; schema facts ordered in the end apply | `schema-design.md` probes 1-6 | `a-pass-admits-a-schema-before-its-use` |
| failed def, dependent | K1-K3 handled per form | unchanged: no capture for K1, captures of an errored form (K3) derive, per-form catch (K2) | `failed-def-design.md` table, probes 1-7 | `a-failed-definition-does-not-certify-a-later-dependent-form` |
| lazy realization | print-time write dropped by `own?` (`observe.clj:35`); entity stale or missing | observed under `sci.ctx-store/with-ctx`; joins its form | `lazy-design.md` probes 1-4 | `a-pass-captures-definitions-realized-after-the-form-body` |
| prose before def | after prose the reader restarts at 1:1; the write joins nothing, silently | reader at the text's own line and column (lane reader-pos); an unjoined write is delivered | `prose-design.md` table, cause | `a-pass-keeps-original-definition-spans-after-prose` |

No case needs analysis before a later form. Removals and schemas need ordered fact application, which the end apply
gives; a later form's evaluation reads none of it (agent code never sees in-pass facts: `redef-design.md` result 4,
`schema-design.md` probe 2).

## 3. Behavior changes, each with its regression

| Change | Today | After | Regression row |
|---|---|---|---|
| A. A value def over a function retires the old row | `(defn fl [] 1)` then `(def fl 5)` keeps `(defn fl [] 1)` stored; only `:rebound` withdraws (`turn/batch.clj:344`) | a `:value` join for a symbol the pass or the store holds as a function row yields `[:withdrawn]`, a removal | `a-pass-preserves-private-definition-custody`: `fl` has no row, its private entry says `:not-a-function` |
| B. Print-time lazy writes are observed | write dropped by `own?`; Var live, program stale | `observe` runs the thunk under `with-ctx` of the evaluating context; `own?` keeps excluding foreign contexts | `a-pass-captures-definitions-realized-after-the-form-body` rows 1 and 3 (red today) |
| C. An unjoined definition write is delivered | a function write with a complete origin that joins no definition is private `:no-definition-at-origin`, said nowhere | the same private entry also becomes a `:seon.batch/notes` member on its form, delivered beside the value | `a-pass-keeps-original-definition-spans-after-prose`, plus one row with a reader-relative origin |
| D. An earlier use across an alias change is refused with its reason | refused by the writer's digest check, message names no cause; same-form `(do (defn h ..) (alias ..))` admitted with a false edge | analysis under the captured row; when the captured and final rows differ in a part `used-resolver-context` keeps, the private entry carries `:seon.batch/reason :resolver-retargeted` and the alias or refer names, delivered | `a-pass-analyzes-aliases-at-their-observed-position` rows 1-2 |

## 4. Decisions for the owner

1. **A write to an attribute registered earlier in the same pass.** Today the agent's `transact!` is refused
   (`undeclared attribute`) and the next pass accepts it (`schema-design.md` probe 2): the attribute reaches the
   agent's connection only through the settlement. *Early* would need a second machinery transaction mid-pass, which
   breaks "one transaction per pass". **Recommend next-pass**, with the refusal naming the key (its hint lists the
   wrong set today, task `seon.db/a-refused-write-names-the-undeclared-key-among-the-registered-candidates`).
2. **A definition realized by a later form** (`(def lz (map … (defn f …)))`, then `(vec lz)`). The write's source is in
   an earlier form. Joining it to the authoring form needs that form's ordinal from the origin. **Recommend private,
   with the stated reason `:defined-outside-this-form`**: rare input, no wrong source is ever attached, and the
   agent is told (behavior C delivers it).
3. **Both uses across an alias change (option C).** Storing the earlier use needs a resolver context held on the
   declaration, a writer rule change, and write-back still cannot reproduce the Var from text. **Recommend not**:
   the later use is stored, the earlier one stays live in SCI and is refused with its reason (D).

## 5. Defects the design depends on

| Defect | Owner | Order |
|---|---|---|
| Reader restarts line and column after prose (`seon.sci.forgive/reader-at`, `forgive.clj:129`) | lane reader-pos, uncommitted at `1d547edd1` | **first**: without it every definition after a region's first form is unjoined |
| Redefinition armed with the earlier contract (`seon.sci.arm/a-redefinition-in-one-pass-is-armed-with-the-earlier-contract`); same-form schema projection (`seon.turn.batch/a-function-using-a-schema-registered-in-the-same-form-is-not-admitted`) | lane redef-arm, uncommitted | **first**: "arm from the authored contract" is that fix |
| Quadratic `runtime-analysis-batch`: per-form newline recount of the accumulated source (1,600 forms 2.5 s, 6.3 GB) | lane s1-03, uncommitted chunked reducer in `fn.clj` | **first**: the one call must be linear |
| Declared-var edge, same pass (`seon.turn.batch/a-declared-var-call-stores-no-edge`) | S5-04 | **within**: one analysis holds the later definition, so `program-targets` keeps the edge (`fn.clj:1415`); proof row in the failed-def test |
| Later-callee edge across passes (`seon.fn/a-dependent-keeps-no-edge-to-a-callee-defined-after-it`, unfiled) | separate task | **after**: needs re-analysis of dependents on a callee's definition, whole-program work per change; its own design |
| Batched analysis uses one namespace row per run (`seon.fn/batched-analysis-uses-one-namespace-row-per-run`) | S5-04a | within |
| Refused core `ns-unmap` silent (`seon.turn:5025` admits not-admitted only for a bind); `remove-ns!` misses a same-pass namespace | S5-04e (delivery); `my.program` task (decision against the head) | within; after |

Unfiled names root should file (`seon.turn.batch.edn` is held by redef-arm): `seon.turn.batch/a-value-def-over-a-function-keeps-the-stale-function-row`,
`seon.sci.observe/a-definition-realized-while-showing-a-lazy-value-is-observed`,
`seon.turn.batch/an-unjoined-definition-write-is-silent`, `seon.turn.batch/a-write-defined-by-an-earlier-form-is-private-without-a-reason`,
`seon.fn/a-dependent-keeps-no-edge-to-a-callee-defined-after-it`, `seon.fn/runtime-analysis-batch-recounts-the-accumulated-source`,
`seon.turn/a-refused-core-ns-unmap-is-silent`, `my.program/remove-ns-refuses-a-namespace-defined-earlier-in-the-same-pass`.

## 6. Cost model (measured in the probes, warm, default's JVM)

| Work | Today | Designed |
|---|---|---|
| analysis, N defining forms | N calls: 13.0 / 62.6 / 122.1 / 232.5 ms at N = 1/5/10/20; 200 forms 2,204 ms, 1,045 MB | one call: 11.5 / 13.9 / 13.7 / 16.3 ms; 200 forms 61 ms, 135 MB (linear after s1-03) |
| non-function def | 19 ms, 8 MB wasted analysis each | 0 |
| cross-namespace definition | second analysis, 17-50 ms, 8-51 MB | a request in the one call (two namespaces: 52 vs 66 ms) |
| nested definition | `captured-locals` kondo run, 7 ms, 3.8 MB | 0 (locals in the same run, cost not measurable) |
| `apply-facts` | 64 ms mean (97 calls), 111 ms clean, per form followed by another | same count, at the end; slice S5-04i removes all but the writer's one validation |
| arming a redefinition | 9-11k calls, 12-31 ms (call-preparation snapshot of a working value changed per form) | fresh-name cost, 2-3 ms, 223-347 calls: working no longer changes between forms |
| capture | — | 2.3 us, 3.1 KB per root bind (namespace row) |
| `register!` | 0.67-2.9 s per key (`seon.schema/schema-registration-rechecks-the-whole-program-per-key`) | unchanged; the dominant cost of a schema pass, outside S5-04 |

A five-`defn` pass today pays about 5 × 20 ms analysis + 4 × 64 ms apply ≈ 360 ms of Seon work, over the 300 ms
definition budget; after S5-04g about 14 ms + 256 ms; after S5-04i about 14 ms plus the settlement's one validation.
Allocation per pass was not isolable in the shared JVM (`redef-design.md` Costs); each slice reports its O row.

## 7. S5-04 slices (ledger format; group B, serial; all after redef-arm, reader-pos and s1-03 release)

| Id | Change | Files | Proof tests | Budget | Deps | Group |
|---|---|---|---|---|---|---|
| S5-04a | `runtime-analysis-batch` partitions by `(juxt :namespace-name :seon.fn/namespace-row)`: one `ns` block where the row changes | `src/seon/fn.clj`, `test/seon/fn_test.clj` | new `seon.fn-test/a-batch-analyzes-each-request-under-its-own-namespace-row`; existing `…/an-alias-an-earlier-form-requires-resolves-in-later-forms-of-the-batch` | O | s1-03 released | B′ |
| S5-04b | Capture: `write-event` adds `:seon.batch/meta` and `:seon.batch/resolver` on root binds; `write-entity` reads captured `fn?`/meta, `analyze-form` the captured row (still per form) | `src/seon/sci/arm.clj`, `src/seon/sci/observe.clj`, `src/seon/turn/batch.clj`, `resources/seon/schemas/seon.batch.edn`, `test/seon/cluster/turn_test.clj` | new `…/a-pass-records-the-definition-each-form-observed`; `…/a-pass-analyzes-aliases-at-their-observed-position` (same-form `h` refused) | O | redef-arm released, S5-04a | B |
| S5-04c | Behavior A: a `:value` join over a function row withdraws it | `src/seon/turn/batch.clj`, `test/seon/cluster/turn_test.clj` | new `…/a-pass-preserves-private-definition-custody` | O | S5-04b | B |
| S5-04d | Behavior B: `observe` runs the thunk under `with-ctx`; a later-form write gets join reason `:defined-outside-this-form` (decision 2) | `src/seon/sci/observe.clj`, `src/seon/turn/batch.clj`, `resources/seon/schemas/seon.batch.edn`, `test/seon/cluster/turn_test.clj` | new `…/a-pass-captures-definitions-realized-after-the-form-body` | O | S5-04b, decision 2 | B |
| S5-04e | Behavior C and the silent unmap: an unjoined function write and any form's facts error are delivered as notes | `src/seon/turn/batch.clj`, `src/seon/turn.clj` (`:5025`), `resources/seon/schemas/seon.batch.edn`, `test/seon/cluster/turn_test.clj` | new `…/a-pass-keeps-original-definition-spans-after-prose`; removal row (d) of `…/a-pass-records-unmap-and-namespace-removal-in-order` | O/F | reader-pos released, S5-04b | B |
| S5-04f | Behavior D: `:resolver-retargeted` reason with the retargeted names | `src/seon/turn/batch.clj`, `resources/seon/schemas/seon.batch.edn`, `test/seon/cluster/turn_test.clj` | `…/a-pass-analyzes-aliases-at-their-observed-position` (all rows) | F | S5-04b, decision 3 | B |
| S5-04g1 | `derive-form` takes a supplied analysis; a new `pass-requests` builds the request vector (function/test forms only, cumulative `declared`, captured rows, expansions); `analyze-form` deleted. The withdraw check reads the pass's program map, not `working` | `src/seon/turn/batch.clj`, `test/seon/cluster/turn_test.clj` | `…/a-pass-admits-a-macro-before-its-use`, `…/a-failed-definition-does-not-certify-a-later-dependent-form` (rows 1-6 and the same-pass declare row) | O | S5-04c..f | B |
| S5-04g2 | `form-step` stops deriving and applying between forms; one end stage: one analysis, per-form derive with the per-form catch, ordered apply (`revalidate`'s reduce), settle | `src/seon/batch.clj`, `src/seon/turn/batch.clj` (`sci-stages`), `resources/seon/schemas/seon.batch.edn`, `test/seon/cluster/turn_test.clj` | `…/a-pass-records-unmap-and-namespace-removal-in-order`, `…/a-pass-admits-a-schema-before-its-use`, plus every row above | O/F | S5-04g1 | B |
| S5-04h | Cross-namespace slice as a request in the one call; locals from the same run (`seon.fn.analyzer/analysis-config :locals true`); delete `captured-locals`' run and the second analysis | `src/seon/turn/batch.clj`, `src/seon/fn/analyzer.clj`, `test/seon/cluster/turn_test.clj` | new `…/a-pass-joins-nested-and-cross-namespace-definitions-once` (asserts a cross-ns edge to a same-namespace callee) | O | S5-04g2 | B |
| S5-04i | Measured: drop the ordered apply; `form-facts` checks removals on the value with the change's entities applied; the writer validates the folded program once and `rebase` attributes a refusal per form | `src/seon/turn/batch.clj`, `src/seon/batch.clj`, `test/seon/cluster/turn_test.clj` | all ten rows; O row parent/change on a five-`defn` pass | O/F | S5-04g2 | B |

S5-04i changes one corner: a removal is checked against the referrers that survive the pass, not those alive at its
position (a referrer redefined later without the call no longer refuses it). The removal row asserts it.

## Limits

Design only; no code changed. Numbers are the probes' (HEADs `0e3c33020` to `d11dbe2b6`), not re-measured here.
Default is at `a4d438dc6`, `:faulted` (`seon.turn.world/record-fault`, task `3d2fb1b41dc8`), already on the health list.
