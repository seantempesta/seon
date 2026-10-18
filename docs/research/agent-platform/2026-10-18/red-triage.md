---
title: "Red triage 2026-10-18: db-flow-test, turn.batch-test and the P5 blockers"
type: research
status: evidence plus two test-fix commits (aeb880c40, cc734f2dc); the db-flow classification is partial, see "Not done"
date: 2026-10-18
basis: "HEAD fd373b0c1 (and 39c9108d9 for later runs); bin/seon check --at HEAD, throwaway JVMs; stored first-red dates from docs/research/agent-platform/2026-10-16/red-census-2.md"
---
# Red triage (2026-10-18)

## Method limits (read first)

- **First red cannot be bisected below about 30 commits.** `bin/seon check --at REV` for any REV at or before 5313b890e (P6, 38 commits back) answers `test request refused: The attribute has no registered schema.` for every test, including ones that are green on HEAD. The boundary is d0b41b62e (boot adopts its publication...): older archives do not boot against the current shared caches. Scratch results: `tmp/triage-reds/chk-*.out`. So the "first red at" column is the earliest stored red from red-census-2 (2026-10-16, candidate and base named there), or "red at 331ed4a68 (P5)" where the census is silent. Stored runs record reds only.
- All six `seon.program-test` reds, the three `seon.turn.batch-test` reds and the db-flow reds below were confirmed red at HEAD by `check --at HEAD` this session. Reds that are green at HEAD now are listed at the end.
- Time-boxed: db-flow's 19 reds are classified from their failing assertion and existing tasks, not each diagnosed to a cause.

## Fixed in this lane (class b, retired assumption)

| test | first red | change | commit |
|---|---|---|---|
| `seon.program-test/a-schema-key-source-separates-takers-guards-and-members` | 5c78940d9 (2026-10-14; census first-observed 322f083f9 same day): `seon.ref/unresolved` became a taker of `:seon.ref/path` | expected `:takes` lists it | aeb880c40 |
| `seon.program-test/runtime-schema-declarations-project-namespaced-properties` | 2026-10-07 (census 76a490909) | a declaration row holds `:seon.render/ai` as the ref map `{:seon.fn/sym ..}` | aeb880c40 |
| `seon.ref-test/a-section-opening-with-a-list-has-no-lead` | TASK-CHAIN `2. Flow` now opens with a prose paragraph (edited by the T3a/T3b revisions, 2026-10-17), so it has a lead and no `:opening`; the test read a live design doc | cites `DH-4a.md#fork-change`, a section opening with a numbered list | cc734f2dc |
| `seon.supplied-documentation-test/docs-separate-runtime-keys-and-preserve-callable-examples` | `my.message/send` supplies `:seon.agent/routing` too | expected set includes it | cc734f2dc |

## seon.turn.batch-test (3 red at HEAD; root said 2)

| test | first red at | class | action | task |
|---|---|---|---|---|
| `a-namespace-row-is-its-ns-text` | e5c13285a (2026-10-16, program-facts P0 red proof; red at 6b63ce06f and 61b07bafc, so not the edamame change) | c, intentional red proof: the stored `:seon.ns/source` is reprinted (`seon.fn` `print/form-text (runtime-namespace-form ..)`), dropping docstring and comments | none here | `seon.turn.batch/a-repl-namespace-row-and-digest-are-red-on-head` exists, with both proofs |
| `a-repl-definition-digests-as-its-file-does` | e5c13285a, same cause (the ns text differs, so digests differ) | c | none here | same task |
| `a-renamed-refer-keeps-its-call-edge-in-the-record-stage` | e8b9de025 (2026-10-16): the record stage refuses `:rename` ("clj-kondo's analysis records no renamed local") | c, red proof of an unimplemented producer | none | `seon.fn/a-renamed-refer-drops-its-call-edge` exists |

`seon.program-test/indexed-and-evaluated-declarations-are-the-same-entities` fails the same way (one-line indexed ns source against a reprinted multi-line evaluated one, digests differ): class c, same task as the first two rows; it is red since 2026-10-07 in the census, the reprint is its cause.

## seon.cluster.db-flow-test (19 red with `--include-long` at HEAD; 5 without)

Run `9854331b7253`. Many carry a prior triage by the lane that filed their task; none was decided before.

| test | failing assertion at HEAD | first red | class | action / task |
|---|---|---|---|---|
| `a-bailed-attempt-says-why-as-data` | uncaught: `seon.render.ns/task-ai` refuses its result, `:seon.task/id` missing from a map (render_ns.clj:100) | not in census 2 (new since 10-16) | c, a render output contract against a task entity without id | none found: file (below) |
| `a-capped-attempt-with-changes-closes-with-a-problem-task-and-no-new-attempt` | 5494 ms over a 5000 ms bound; also an uncaught error | census named its relaunch form red since 2026-10-13; renamed by 63aac4169 | c (timing bound) plus d? | none; the bound needs the measured operation |
| `a-capped-chain-attempt-bails-to-root-once-and-root-runs-it-again` | `:622` the one problem task is named `unfinished-task-8495d20276c8`, but the attempt key is `0cac74340d3e` and the task handle `6ca55d82c612`: the name's id is neither (debug run) | after 63aac4169 (2026-10-16) | c or b: `seon.cluster.agent/write-problem!` names the task by `(or key h)` of the action row; which row supplies 8495d20276c8 is undiagnosed | `seon.cluster.agent/a-capped-chain-attempt-bails-to-root-...` is cited by `seon.cluster.agent.edn`; no task of its own |
| `an-outside-agents-send-back-ends-its-attempt-and-root-bails-it-once` | `:1141` same naming mismatch (`43d8a79aa72f` against key `8c042e0d368a`) | same | same as above | one shared cause, probably |
| `a-done-merge-step-merges-its-branch-onto-main` | `:2585` the refused merge keeps no merge branch (`kept` nil) then root's run of the step errors | census: 2026-10-15 (04d73ec5a) | c | `seon.cluster.agent/a-done-merge-steps-accepted-merge-is-unproven` |
| `a-failed-start-ends-its-attempt-and-the-next-plans` | `:1849` attempt reason nil | 2026-10-12 (059f0db1a) | b or c, undecided by its task (reactive start 88474708b may make an unstarted task the design) | `seon.cluster.agent/a-failed-start-ends-its-attempt-is-red-on-head` |
| `a-prove-step-whose-product-main-holds-merges-and-its-successor-forks-main` | `:1173` successor's need not done | census: green on 10-16, red again at HEAD; it is a flake or a regression of P4b/P5 | undecided | none: rerun alone 5x first |
| `a-refused-final-merge-bails-to-root-once-with-its-refusal` | `:2325` asserts the reason text contains the refusal type; reason is fixed text since 36c0cfbf2 and the type is data in `:seon.task/refusal` | 2026-10-15 | **b, retired assumption** (the same change already moved the sibling assertion at `:2593` to `:seon.task/refusal`); not edited here: the test is one of many in a file with 19 reds and no run proved a green fix | `seon.cluster.agent/a-refused-final-merge-bail-problem-task-names-no-refusal` |
| `a-refused-final-merge-keeps-its-merge-branch-and-holds-a-merge-step` | `:2417` a merge step's attempt starts by itself | new since 10-16 | c | none: file |
| `a-requested-end-whose-files-no-checkout-holds-merges-nothing` | `:1632` main holds a merge of the head | 2026-10-13 | c | `seon.cluster.agent/a-requested-end-without-a-checkout-still-merges-its-head` |
| `a-rerun-predecessor-of-a-held-merge-step-merges-and-the-step-stays-held` | `:2654` starts an attempt | new | c | none: file |
| `a-merge-step-green-at-its-start-ends-done-with-no-model-call` | uncaught: `my.root/run!` refused `:seon.task/assigned` nil | new (T1/T2 task writer) | b or c | none: file |
| `a-three-task-chain-ends-merged-with-no-hand-act` | event `chain-task-2-ended` not published in 240 s | new, c9f2a98a7 (T1) names it green on its landing | c | none: file |
| `a-two-step-chains-implement-step-forks-main-holding-the-prove-steps-product` | `:2072` an error signature is recorded | new | c | none: file |
| `a-two-task-chain-reaches-its-second-model-call` | `:988` repeated pass writes 3 extra task datoms (7 against 10) | new | c: a repeated pass is not idempotent | none: file |
| `abandoning-a-chain-with-a-held-merge-step-closes-its-attempts-and-ends-its-branches` | `:2483` tasks are not all `:cancelled` | new | c | none: file |
| `an-attempt-whose-proofs-never-ran-ends-stalled-and-bails` | `:2753` outcome not `:closed` | new | c | none: file |
| `an-error-end-copies-nothing-and-writes-roots-problem-task` | `:1436/1437` by-hand `merge!` answers no commit id and the intent stays `"Do e."` | 2026-10-12 (f1cca55b7); the test was edited by P4b a3ed65f73 for `:seon.source/commit-id` and still fails | c or b: the merge of a chain no longer carries a refinement | `seon.cluster.agent/an-error-end-copies-nothing-is-red-on-head` |
| `an-outside-task-takes-no-model-turn-and-is-worked-through-the-repl` | `:1081` second task is `:held`, expected `:running` | red at HEAD 2026-10-18 (context-fix lane) | b or c: tasks are written `:held` until `my.root/run!` | `seon.cluster.agent/an-outside-chains-next-task-stays-held-after-its-first-is-done`, `seon.task` |

Reading the table: 9 of the 19 are new since 10-16 (the P4b/P5 merge changes and the T1/T2 task writer, c9f2a98a7 and cb8274c69). Under P5 these block done. The shared structural fact is that the file asserts chain lifecycle mechanisms (automatic advance of a `:held` second task, attempt-keyed problem names, a merge step that auto-starts) that the reconciler work (T1-T3a) and `my.root/run!` changed; most are therefore class b or c on the decision "was `:held` until `run!` the design", which is the owner's task-model ruling, not a test-local one.

## Other requested reds

| test | status at HEAD | first red | class | action |
|---|---|---|---|---|
| `seon.program-test/a-declaration-added-after-the-first-call-is-a-cache-miss` | red: `attribute has no registered schema` for `:seon.program-test/declared-after` from `seon.program/derived-shape` | 2026-10-07 (76a490909) | c or b, undiagnosed: the test declares an attribute without registering a schema, which `seon.schema.datahike/malli->datahike-attr-in` now refuses | none: file |
| `seon.program-test/declaring-an-attribute-on-a-program-entity-schema-is-sufficient` | same error for `:sample/declared-schema` | 2026-10-07 | same | none: file (one task for both) |
| `seon.program-test/typed-cross-namespace-deletion-retracts-function-and-test` | `:1209` the function and test entities of `my.agents.registration-test/same-name` survive the deletion | 2026-10-07 | c, a real deletion defect or a retired assumption; undiagnosed | none: file |
| `seon.error-test/error-declared-schemas-persist-through-the-real-occurrence-owner` | red `:1308` pulled form does not validate the occurrence | before 225a66566 | c | none (listed in 2026-10-17 red-triage) |
| `seon.error-test/preparation-and-recording-preserve-every-instrumentation-arm` | red `:765` `(not (= :guard :output))` | before 225a66566 | c | none |
| `seon.error-test/the-elision-ai-render-returns-data` | **green at HEAD** in this run | proof added red by 0a43382db | resolved | none |
| `seon.instrument-test/a-sovereign-sci-fork-refuses-by-throwing-under-both-dials` | red `:452` no `:seon.instrument/interpreted-original` on the base's `seon.id/valid?` | unknown | c, plausibly the platform ruling (fd373b0c1, a branch re-roots no compiled `src` caller) | for lane platform-src; `seon.sci.eval` task from 6b63ce06f |
| `seon.instrument-test/registry-sized-contract-evidence-retains-the-offending-object` | red `:796` 32,279,728 bytes against a 16,777,216 ceiling | unknown | c, cost | none |
| `seon.cluster.evaluate-source-test`, 2 of 3 | `a-failed-read-is-its-forms-error-and-reading-continues-in-sci-and-jvm` (`[nil nil nil]` for `[2 nil 4]`) and `a-run-whose-open-is-refused-still-answers-its-values`; the third is green | 6a421d96e (2026-10-16) | c | `seon.turn.batch/a-jvm-batch-answers-the-forms-around-a-failed-read` exists |
| `seon.supplied-documentation-test`, 3 more | `doc-in-names-schemas-by-their-registered-keys` (`:gen/gen` or `:seon.schema/identity-projection` in a doc tree), `invalid-runtime-key-tells-the-agent-to-omit-it` and `lookup-ref-refusal-names-the-actual-and-accepted-shapes` (uncaught: "Value-admission caps require every declared configuration bound", `:seon.config.eval.result/max-bytes` missing for `supplied-docs`) | unknown | c (the seed config of the test cluster lacks a declared dial); not diagnosed | none: file |
| `seon.cluster.source-test`, three publication tests (41 to 51 s) | not rerun (each is a 40 s check) | 2026-10-10 (dd5aa34a6) | c, cost, 8x over bound | existing census note; none in tasks |
| `seon.cluster.agent-test`, 9 long tests | not rerun | cb8274c69 (T2) reported them | undetermined | see 2026-10-17 red-triage |
| `seon.sci.branch-execution-test`, 3 | **not red in this run** (non-long members green; the three may be declared long) | unknown | needs `--include-long` rerun by platform-src | `docs/seon/issues/affected-callers-fail-to-interpret-against-uninstalled-bindings.md` |
| `seon.context-test/a-chain-of-one-is-judged-by-the-done-rule` | 5 sequential `check --at HEAD` runs: 5 green, 0 red | n/a | **d**, not reproduced | none; no flake evidence |

## Not done

- The agent-test nine, the source-test three and the branch-execution three were not re-run at HEAD (about 40 s each plus the long-tier cost).
- db-flow reds are classified from assertions, not diagnosed. The most valuable single follow-up is the problem-task naming mismatch (two tests): print the action row's `:seon.task/assignment` at `seon.cluster.agent/write-problem!` and compare with `seon.task/next-attempt`'s key.
- Findings with no task yet, to be filed by root: the eleven rows marked "none: file" above; `bin/seon check --at REV` fails on every revision before d0b41b62e against the current shared caches, which blocks bisecting any older red.
