---
title: "Program facts from text, in both modes: the hybrid"
type: research
status: hybrid of the two independent plans (Fable); phases proposed, none implemented
date: 2026-10-16
basis: "HEAD 1396fdda4; plan A docs/research/agent-platform/2026-10-16/program-facts/opus.md (1396fdda4); plan B astra.md (ccc29bc4a); dig digs/program-facts.md (4683a909d); default running 44fbe9fbd, :faulted (29 open faults, 412 errored receipts), read-only JVM probes only"
---

# Program facts from text: the hybrid

Phase status: P0 proposed · P1 proposed · P2 proposed · P3 proposed · P4 proposed. (Each implementing commit
replaces its word here: `in progress <lane>`, `done <commit>`.)

## 1. The contract

A declaration's facts come from **the text that was evaluated**, analysed by clj-kondo exactly as a file is
(`seon.fn/analysis-entities-by-file`): the namespace's `ns` form text, the directives evaluated in that namespace
since (their text), the `declare` prelude of its known names (`seon.fn.analyzer/program-prelude`), then the form. The
runtime contributes one value, in both modes: **what the evaluation bound** (`:seon.batch/bound`, the qualified
names it bound, unmapped or removed, in order). A Var is recorded when the text names it and the evaluation bound
it; one that only one of them names is not a program fact. The record stage, `seon.turn.batch/record-form`, is a
pure function of `(ns-text, directive-texts, prelude, form-text, bound)`; SCI and JVM mode differ only in the
producer of `bound`, passed as a stage value, which is the one-path law ("an entrant needing another reader or
evaluator passes that stage as a value to the same pipeline"). One owner per field: text decides what is declared,
the evaluation decides what was bound, the context that evaluated a value definition keeps its live value, the
file (through the namespace) decides where a declaration lives. Nothing is reconstructed from SCI's resolver
tables, and no live value is cached by content.

Measured on main for this contract (JVM probe, 2026-10-16, 134 ms): 11,147 function rows, every `:seon.fn/defined-by`
a `clojure.core` definer (0 from a non-core macro); 0 rows whose `:seon.fn/file` differs from their namespace's; 11
namespaces with no file.

## 2. Decision table

| Point | A (Opus) | B (Astra) | Ruling and reason |
|---|---|---|---|
| Source of truth for a declaration | the evaluated form text in its namespace's text, analysed as a file is | the source that defined it, analysed in the resolver context at the occurrence | **A.** Same analysis, but A's inputs are text only, so one function serves SCI and the host JVM (owner: both modes). B's "transient resolver input at the occurrence" is SCI's `seon.sci.eval/reader-context`, which the JVM does not have; the directive texts in evaluation order carry the same information (A's probe: clj-kondo resolves `str/join` through a top-level `require` in the text) and give B's experiment 2 (alias retargeting between forms) the right answer per form. |
| The join of an evaluation's write to an analysed definition | by `[form ordinal, qualified name]`, confirmed by a before/after interns delta; delete `seon.turn.batch/join-write` and the span machinery | keep the exact-origin span join; B's experiment 3 (`(if false (def not-run 7))`) "would regress" under a design deleting `join-write` | **A, by name, confirmed by `bound`.** B's objection does not hold against A: analysis names `not-run`, the evaluation bound nothing, so nothing is recorded (A §2 states it). The span join exists for macro-generated definitions and SCI origins; main holds 0 such declarations (probe above), and the JVM has no origin. Same name twice in one form: the last analysed occurrence is the declaration (its final root write). Nested definitions keep `seon.turn.batch/captured-locals` (the analysis row's own span, not SCI's). |
| Producer of "what was bound" | `ns-interns` delta (2 ms for 108 interns) | SCI's existing write observations | **Both, as one value.** In SCI `bound` derives from the existing observer's writes (`:op :bind`, `:kind :root`, final write per Var: today's `finals` in `seon.turn.batch/derive-form`); in JVM mode from `clojure.core/ns-interns` keys plus root identity before and after the form (`:seon.batch/observe` for `jvm-request`). The record stage never sees which. No fork change is needed now; the observer's `:origin` and expansion patches become unread and are removed in a root restart batch. |
| Namespace facts | only from the `ns` text; directives pending in the context, folded into the text by a declaration that uses them (rewrite-clj) | the same rule ("when a declaration actually uses a new resolution, the source owner incorporates that requirement into the namespace source"), with `seon.fn.analyzer/namespace-context` owning the grammar | **Agreed; A's mechanics.** Keeps the owner's rule (3da13c834, `seon.turn.batch-test/a-defn-keeps-its-namespace-row`). The fold reuses `seon.sci.eval/declared-source` (rewrite-clj, already appends libspecs to the `:require` clause). Pending directive texts live in the agent's context private layer, which root's JVM-mode world also carries (`seon.mcp` builds the JVM world with `(:seon.sci.eval/ctx (agent/acquire-context! handle agent-id))`), so one place serves both modes. Unused directives never become facts; a restart loses them, as a Clojure REPL does. |
| `seon.sci.eval/binding-rows` | delete (SCI → rows direction); keep `row-bindings` (rows → SCI) | "still has namespace installation callers; not an unconditional deletion" | **A.** Verified: its callers are `seon.sci.eval/namespace-context-row` and `seon.turn.batch` (`namespace-entity`, `stored-namespace-row`), all deleted in P2. `row-bindings` (acquisition installs a namespace from its row) stays. |
| Value definitions' live value | the evaluating context keeps its Var; test step forks the agent's context; delete the kept-value family and the compartment term of the overlay key | delete `::kept-value`; a consumer needing the bindings forks the live context; an independent execution runs the init once | **Agreed.** B's experiment 5 (`(def cell (atom 0))` twice: live 0, acquired 1) is the regression row. Program contexts become content-keyed again (`seon.sci.eval/derive-overlay-plan` drops `owner`). Risk both name: a test green only on private state; the merge's own run is a derived context and catches it. |
| `seon.sci.eval/install-evaluated-rows!` | with the evaluating context, advance its acquired basis when the settlement's `:db-before` is the basis it acquired; delete `installation-covers-program-change?` and the evaluated install path (~180 lines) | convert the six fixture callers to the evaluating context; keep `install-row!`; the defect is "multiple install interfaces", not double evaluation | **Both.** After P1–P2 the settlement's program facts are a function of this context's own text, so basis equality is the whole coverage check (A); the six callers (`seon.turn`, `seon.test-test`, `seon.contracts-fixture`, `seon.sci.eval-test`, `seon.sci.documentation-test`, `my.test-test`) convert in the same commit (B); `install-row!` keeps its 7 callers (B is right that it is not on the deletion list; A agrees). B's correction of the dig stands: with `agent-ctx`, HEAD already only records (`record-settled-rows!`), so ordinary settlement evaluates no def twice; the second evaluation is the overlay install of a later context, which the kept-value cache was papering over. |
| Per-row `:seon.fn/file` and `:seon.fn/form-span` | retire: file through the namespace, spans from the file's analysis cached by file digest (A P3) | retire after every reader uses a file-artifact location query (B P4a/P4b, census 42 files, 17 in src/script) | **Deferred: not a phase of this round.** B's census is right (42 / 17, verified; A's "24 sites in 9 files" counts only `form-span` in src/script: 29 in 9). The one carry rule `seon.turn.batch/file-attributes` in `entity-facts` is ten lines, the defect it caused (7f2558dbe) has its regression, new REPL rows already find their file through `seon.cluster.source/namespace-path`, and main shows 0 disagreements. A 42-file conversion plus a schema norm buys no task turns (owner 2026-10-16: metric first). Filed as a task, below, with A's proof name, to run when a defect or the bench names it. |
| `seon.program/source-resolver-names` (edamame reparse for the digest) | optional P6: replace with clj-kondo's `:alias`/`:refer` only if a recompute over main shows 0 changed digests and the probe shows a gain | delete it; collect lexical usage once in analysis | **A's gate.** A qualifier spelled only inside a quote (`'str/join`) is a usage edamame sees and clj-kondo does not record, so digests can move; the recompute over main decides, not the plan. Not a phase. |
| The second derivation at settle (`seon.turn.batch/settle` re-derives `program-facts` on the head) | name it, measure in P4, task `seon.turn/a-definition-is-settled-twice` | P5: profile first, remove repeated whole-population derivations where the profile proves redundancy | **Both, outside this round.** Each phase here carries its own end-to-end cost proof and commits no regression; the settle derivation is its own task once P1–P3 make per-form facts a pure function of text. |
| JVM mode records | P5 after an owner ruling; recommended yes | the record stage is mode-agnostic by construction | **A phase (P4), proven on disposable branches; the switch on default is root's** (§6). |

What was wrong in each plan. A: `join-write`'s deletion is right but its replacement must not read SCI alone
(the interns delta A measured works in both modes; this hybrid names the JVM producer). A's file-attribute phase
counts only `form-span` and under-scopes the conversion. B: `binding-rows` is deletable (callers verified); the
objection to deleting `join-write` conflates "record every analysed definition" with "record what was analysed
and bound"; B's live-resolver-at-occurrence input is SCI-only and fails the owner's both-modes requirement. Both
plans measured the same costs on the same path (A: 871.7 ms / 953.7 MB for six forms on a new branch; B: 387.5 ms /
438.7 MB JVM-window for three forms, 2,237.8 ms / 2,257.9 MB for twelve definitions, `seon.db/vector-query-call` 198
calls / 756 ms); neither claims the plan alone makes the first request sub-second, and this hybrid does not either.

## 3. What is deleted, in the phase that converts its last caller

| Phase | Deleted | Replaced by |
|---|---|---|
| P1 | `seon.turn.batch/join-write`, `synthetic`, `contains-pos?`, `form-relative-expansions`, `entry-coords`, `local-offset`; the `:seon.batch/expansions` input of `seon.fn/runtime-analysis-batch` and `seon.fn.analyzer/expansion-hooks` (its two callers); the reasons `:no-definition-at-origin`, `:ambiguous`, `:no-complete-span`, `:rebound` | `record-form`: analysis rows of the form joined by qualified name to `bound`; one private reason `:not-declared-by-text` (plus `:captures-locals`, `:no-source`) |
| P2 | `seon.turn.batch/namespace-entity`, `live-bindings`, `stored-namespace-row`, `declared-bindings`, the SCI path of `namespace-facts`, the SCI undo in `refuse-cycle!` (`sci/install-namespace-bindings!`); `seon.sci.eval/binding-rows`, `namespace-context-row`, `namespace-source`; `seon.fn/runtime-namespace-form`, `runtime-require-specs` | the stored `:seon.ns/source` text plus the context's pending directive texts; `seon.sci.eval/declared-source` folds used libspecs; attributes derived by `seon.fn.analyzer/namespace-context` |
| P3 | `seon.sci.eval/compartment`, `agent-value-definition?`, `value-key`, `keep-value!`, `kept-value`, `keep-evaluated-values!`, `evaluated-value-bytes`, `agent-value-definitions`, the `owner` term of `derive-overlay-plan`, the kept branch of `install-function-from-database!`, `transfer-evaluated-roots!`, `installation-covers-program-change?`, `same-declaration-source?`, `declaration-source-value`, the evaluated path of `install-evaluated-rows!`; `test/seon/sci/program_change_report_test.clj` with its subject | basis equality in `record-settled-rows!`; the evaluating context's own Var; the test step forking the agent's context |
| P4 | nothing | `jvm-request` gains `:seon.batch/observe` (interns + root delta) and `:seon.batch/derive record-form` |
| restart batch (root) | the SCI fork's `:origin` on def writes and the analyzer-expansion-hooks patch | unread after P1; a fork bump rides a scheduled restart |

Estimated net: about 550 source lines deleted, about 120 added (A's and B's counts overlap on P2–P3: B's 14
functions / 225 lines are a subset). A line estimate, not a measurement.

## 4. Phases

Every phase: one loadable slice, HEAD boots after it, each deletion in the commit that converts its last caller
(tests included), a RED proof (red on HEAD, green after) and a cost proof: `seon.profile/measure` around
`seon.mcp/call` of `eval_clj` on parent and change in one quiet JVM, on two fixed probes: **probe-6** (A §7: `ns`,
REPL `require`, `defn`, `defmacro`, custom-macro def, `def` atom, new branch; parent 871.7 ms / 953.7 MB on
8347b2d94) and **probe-12** (B experiment 6: twelve contracted `defn`s on a continuing agent; parent 2,237.8 ms /
2,257.9 MB JVM-window). A measured regression on either is not committed.

| Phase | Lane, files | Change | RED proof | Cost proof |
|---|---|---|---|---|
| **P0 red proofs** (~15 min) | Sonnet; `test/seon/turn/batch_test.clj` only | New rows, each red on HEAD with its reason: `a-namespace-row-is-its-ns-text` (an evaluated `(ns x (:require [clojure.set :as s]))` stores `:seon.ns/source` byte-equal to the read text; red: reprinted). `a-repl-definition-digests-as-its-file-does` (one declaration at the REPL and the same text indexed from a file: one digest; red: ns reprint). `a-var-the-text-does-not-declare-is-private` (`(defmacro mydef ...) (mydef gen 3)`: `mydef` stored, `gen` private `:not-declared-by-text`, no `gen` entity; red: stored as `clojure.core/def`). `the-record-stage-is-one-function-of-text` (a table over `seon.turn.batch/record-form` with `[ns-text directives form-text bound]` rows: defn, redefinition, record, protocol, value def, `declare`, `(if false (def not-run 7))`, custom macro, alias used, alias unused, self-require, alias retargeted between two forms, `::alias/key`, renamed refer; red: no such function). `an-identical-value-redefinition-keeps-no-stale-value` (B experiment 5: after `(def cell (atom 0))` twice, a fresh `seon.sci.eval/base-ctx` reads the second init's value, 0; red: 1 from the kept value). | none | none |
| **P1 one record stage from text** (~45 min) | Opus; `src/seon/turn/batch.clj`, `src/seon/fn.clj`, `src/seon/fn/analyzer.clj`, `src/seon/batch.clj` (the `:seon.batch/bound` member) | Define `record-form`; `derive-form` builds `bound` from the observer's final root writes and calls it; join by qualified name; nested definitions through `captured-locals` unchanged; private reasons collapse. Delete the P1 row of §3. | `the-record-stage-is-one-function-of-text`, `a-var-the-text-does-not-declare-is-private`; `seon.turn.batch-test`, `seon.turn-test`, `seon.fn-test/static-index-and-runtime-admission-agree-on-analysis-attributes`, `seon.fn-test/a-fold-derived-definition-has-static-index-edge-parity` green. Retires tasks `seon.turn.batch/an-unjoined-definition-write-is-silent`, `seon.sci.eval/analysis-order-differs-from-evaluation-order`. | probe-6 and probe-12, parent vs change; expect `derive` down (no expansion bookkeeping, no synthetic coordinates). |
| **P2 the namespace is its `ns` text** (~45 min) | Opus; `src/seon/turn/batch.clj`, `src/seon/sci/eval.clj`, `src/seon/fn.clj`, `src/seon/fn/analyzer.clj` | `:seon.ns/source` written only by an evaluated `ns` form's read text, by the fold of used pending libspecs (`declared-source`), and by file indexing; namespace attributes derived from the text by `seon.fn.analyzer/namespace-context`; pending directive texts kept per namespace in the context's private layer (dropped by `ns-unalias`); the cycle refusal fires at the fold, mutating nothing. Delete the P2 row of §3. Prove on a branch of the live store that recomputing every function's `seon.db/definition-digest-of` under the text-derived context changes 0 digests on main. | `a-namespace-row-is-its-ns-text`, `a-repl-definition-digests-as-its-file-does` (covers `seon.program-test/indexed-and-evaluated-declarations-are-the-same-entities`, red on HEAD per task `seon.program/an-evaluated-namespace-keeps-its-read-source-and-call-edges`); kept green: `a-defn-keeps-its-namespace-row`, `a-repl-require-is-written-back-in-the-ns-form-and-reindexes-equal`, `a-retargeted-alias-leaves-no-stale-digest-for-the-test-world`, `seon.program-test/a-require-a-definition-does-not-use-leaves-its-digest-unchanged`, `seon.fn-test/an-alias-an-earlier-form-requires-resolves-in-later-forms-of-the-batch`, `seon.cluster.write-back-test/a-require-added-at-the-repl-merges-and-writes-back-its-ns-form`, `.../a-declaration-naming-an-unrequired-namespace-merges-with-its-ns-form-requiring-it`. Retires `seon.sci.eval/repl-namespace-changes-drift-from-the-ns-text`, `seon.turn.batch/a-repl-namespace-row-keeps-neither-doc-nor-source`, `seon.program/write-back-keeps-the-evaluated-order-of-ns-requires`; new rows for `my.program/alias-and-require-as-retarget-silently` and `my.program/a-repl-alias-stays-on-the-namespace-row-after-ns-unalias`. | probe-6 (the `require` form and the `defn` using it), parent vs change; the digest recompute's time on main. |
| **P3 the live value stays in its context** (~45 min) | Opus; `src/seon/sci/eval.clj`, `src/seon/turn.clj` (`test-step`, the `install-evaluated-rows!` call), `src/seon/cluster/agent.clj` (`acquire-context!` for the test step); test fixtures `test/seon/test_test.clj`, `test/seon/contracts_fixture.clj`, `test/seon/sci/eval_test.clj`, `test/seon/sci/documentation_test.clj`, `test/my/test_test.clj` | `fork-for-turn` keeps the context's own evaluated Vars whose settled digest is the head's; the test step forks the agent's evaluating context (`sci.core/fork`), not a context re-derived for the head; `install-evaluated-rows!` with an evaluating context advances its acquired basis when the report's `:db-before` basis is the one it acquired, and does nothing else; the fixture callers evaluate in the context they install into. Delete the P3 row of §3. | `an-identical-value-redefinition-keeps-no-stale-value`; kept green: `a-value-def-init-runs-once-for-every-later-context`, `an-identical-value-def-in-two-agents-holds-two-values`, `seon.sci.eval-test/a-live-context-preserves-definition-value-class-and-metadata`, `seon.sci.eval-test/a-context-definition-survives-its-symbols-first-install`, `seon.sci.fork-isolation-test`; new row `identical-branches-share-one-program-context` (two branches with one value def hold one `seon.sci.eval/program-context` value; red on HEAD: the compartment splits them). | probe-6's `test-step` (parent 212.8 ms) and `settle` (217.3 ms) stages and probe-12 whole, parent vs change. |
| **P4 JVM mode records through the same stage** (~30 min) | Sonnet with the brief; `src/seon/turn/batch.clj` (`jvm-request`), `test/seon/turn/batch_test.clj` | `jvm-request` gains `:seon.batch/observe` (keys of `ns-interns` of the form's namespaces and `all-ns`, root identity per Var, before and after; `bound` from the delta) and `:seon.batch/derive record-form` with the host `read+string` text. Proven only on disposable branches (`my.agent/create-branch!`), never on default's main. | `the-record-stage-is-one-function-of-text` run with JVM-produced `bound` (same table, same expected changes); new row `a-defn-recorded-in-jvm-and-sci-mode-is-one-row` (one `defn` evaluated by root in both modes on two disposable branches: equal rows, equal digests). | a JVM-mode `defn` request, parent (no facts) vs change (facts): the added cost is the record stage alone, proportional to the form. |

After P4 the bench (`cards/README-bench.md`) decides whether the deferred items become phases; none is scheduled by
this document.

## 5. Deferred, filed as tasks (not phases)

- `seon.turn.batch/a-declarations-file-is-its-namespaces` — retire per-row `:seon.fn/file` and `:seon.fn/form-span`
  (A P3, B P4a/P4b; proof `write-back-finds-a-declaration-through-its-namespace-file`, A's name; 42 reader files; a
  schema norm in a restart batch). Opens when a file-link defect recurs or the bench names write-back cost.
- `seon.program/one-parser-for-the-digest` — replace `source-resolver-names` only after a recompute over main shows 0
  changed digests and a measured gain (A P6).
- `seon.turn/a-definition-is-settled-twice` (exists) — the settle-time re-derivation, after P1–P3 make per-form facts
  pure.
- `seon.sci.eval/an-identical-value-redefinition-keeps-the-previous-live-value-in-acquisition` (B filed it on its
  branch, handle `01837b74c8bf`) — resolved by P3; root adopts it to main so P3's commit retires it.

## 6. Open question for root

Whether root's JVM-mode definitions on default record as main's facts once P4 is proven on branches. Both plans
recommend yes (default's loaded Vars and its entities stop drifting; write-back owns the file update); it changes the
practice "never load uncommitted source into default's JVM" into "a JVM-mode definition on default is a recorded,
write-back-able change", which is the owner's call, not a code question.

## 7. Evidence

- JVM, read-only, default (44fbe9fbd), 2026-10-16: `:seon.fn/defined-by` counts over every `:seon.fn/sym` row:
  `defn-` 4,288, `defn` 1,946, `def` 957, `defonce` 80, `defmacro` 11, `defrecord` 8, `deftype` 4, non-`clojure.core`
  definers 0; rows with `:seon.fn/file` 11,147; rows whose file differs from their namespace's 0; namespaces without
  a file 11. 134 ms, result `result/e18203f74a8b4`.
- Callers verified at HEAD by `rg`: `seon.sci.eval/binding-rows` 2 (both deleted in P2); `install-evaluated-rows!` 6
  files; `install-row!` 7; `fork-for-turn` 13; `:seon.fn/form-span` or `:seon.fn/file` 42 files (17 src/script).
- `seon.mcp` JVM-mode world carries the agent's SCI context (`acquire-context!`), the place P2's pending directives
  live in both modes.
- Both plans' probes are cited by their result handles in their own documents; none was re-run here.
