---
title: "Proof audit: new red-proof tests at their parent commit"
type: research
status: complete; read-only audit, no source or test edited, only this note written
---
# Proof audit: new red-proof tests at their parent commit

Lane `proof-audit`, 2026-10-14. Question: does each new deftest in ten recent commits fail on its parent (the code before the commit), with the commit's test files applied?

## Method

- `bin/seon check` takes a git revision (`--at REV`, which boots a throwaway JVM from REV's archive) or HEAD plus working-tree paths. It cannot check an arbitrary tree or a parent other than HEAD.
- For each commit C with parent P, a candidate commit object was built with `git commit-tree` from P's tree, with C's changed `test/` files written over it through a temporary index (the same technique as `candidate-revision!`). No ref moved, no working-tree file changed, no source was edited. The candidate's diff from P is only the test files.
- Each new deftest was then run with `bin/seon check --at <candidate> --test NS/NAME`.
- Control: each commit's own archive, `bin/seon check --at C` with all of its new tests. All ten controls are green, so the tests are valid on their own commits.
- Reds were read from the operator's diagnostic log (`data/clusters/default/logs/operator-check-*.edn`).

Two corrections to the method:

1. The first pass copied every changed `test/` file. For 130526a8b that carried the fix itself, because `test/seon/test_support.clj` is a test-tree file that holds the fix. The parent then reported GREEN. That result was an artifact of the construction and is withdrawn. The corrected run applies only the new test file: RED (below). Rule for future audits: copy only the file that holds the new deftest, and check whether a fix lives under `test/`.
2. The first attempt at 35cafc1dc (parent) timed out waiting for a free throwaway JVM slot (`No throwaway JVM slot freed within its bound`), because other lanes held the slots. It is not a result. Its retry is below.

## Results

Result key: **red-assert** = failed assertion at the parent, the behaviour is absent; **red-load** = the test or its namespace did not run because the parent lacks a var or fix the test calls; **red-other** = red for another reason, named. No test was green at its parent.

| Commit | Test | Parent | Control at C |
|---|---|---|---|
| 776a04249 | `my.edit-test/text-replaces-one-exact-occurrence` | red-load: `Syntax error compiling at (my/edit_test.clj:31:3)`, an unresolved `edit/text`, added by this commit as `my.edit/text`. No assertion ran. | green |
| f87629f9b | `seon.bootstrap-test/every-reference-in-help-resolves` | red-load: the throwaway JVM refused boot. Static analysis: `bootstrap_test.clj:19:40 :unresolved-var Unresolved var: bootstrap/help-section`. No test ran. | green |
| f87629f9b | `seon.bootstrap-test/help-answers-one-section-by-its-anchor` | red-load: same boot refusal. | green |
| 66de34302 | `seon.operator-test/a-throwaway-jvm-never-collects-its-store` | red-assert at `operator_test.clj:241`: expected `[:seon.config.maintenance/collect? false]`, actual `nil`. | green |
| de1f48c23 | `seon.turn.batch-test/a-repl-value-def-is-a-program-row-a-fresh-context-installs-in-order` | red-assert at `batch_test.clj:360` ("a later request installs the function reading the def"): expected `(= [] (install-errors later))`, actual non-empty. | green |
| de1f48c23 | `seon.turn.batch-test/a-value-def-over-a-function-replaces-its-row` | red-assert at `batch_test.clj:375`: expected the function's `:seon.fn/arglists` map, actual differs. | green |
| 5a08c13af | `seon.error.refusal-test/a-constructed-error-is-a-value-of-its-inputs-but-time` | red-assert at `refusal_test.clj:525` ("the boundary still attributes the occurrence"): expected `(not (contains? a :seon.error/world))`, actual `(not (not true))`. | green |
| 2edaf91eb | `seon.repl-test/a-def-note-follows-the-batchs-decision-and-the-outcome` | red-other: uncaught `NullPointerException` at `repl_test.clj:154` (the def-note is nil at the parent, and the assertion calls `str/starts-with?` on it). | green |
| 2edaf91eb | `seon.repl-test/the-def-note-follows-what-the-batch-classed-private` | red-assert at `repl_test.clj:186`: expected `(= noted? (some? note))`, actual `false` vs `true`. | green |
| 35cafc1dc | `seon.render-cache-test/a-turn-re-renders-only-the-context-units-whose-reads-changed` | red-assert at `render_cache_test.clj:171` ("only the revised unit renders again"): expected zero `seon.sci.kernel/invoke` calls on the second pass, actual 3. (Retry; the first attempt hit a slot timeout.) | green |
| 978f7122c | `seon.context-test/a-plain-eval-renders-each-must-read-from-its-world` | red-assert at `context_test.clj:322` ("the must-read is rendered beside its name"): expected `'x`, actual `nil`. | green |
| 249c18f9a | `seon.test-reaching-test/a-host-answer-on-an-unchanged-database-is-derived-once` | red-assert at `test_reaching_test.clj:499` ("the second answer for the same database reads no graph"): expected 0 reads, actual 361. | green |
| 130526a8b | `seon.test-support-test/a-cut-reply-is-answered-as-data` | red-other: uncaught `RuntimeException` "Map literal must contain an even number of forms" from `seon.test_support/reply` (`test_support.clj:911` at the parent). This is the bug the commit fixes. The earlier GREEN was the construction artifact described above. | green |

## Findings

- **Green at parent (proofs that prove nothing): none.** The only green was the 130526a8b candidate built with the whole test tree, and it was the construction artifact, not the parent's behaviour. With only the new test file it is red for the right reason.
- **Load-red only, no behavioural evidence: 776a04249 (1 test), f87629f9b (2 tests).** Both reds come from a missing var the commit adds, not from a failed assertion. That shows the parent lacks the var, not that the behaviour check can fail. Proving the behaviour needs the source fix, which read-only audit cannot apply.
- **A new test file that calls a missing var refuses the whole candidate boot,** not just its own tests (f87629f9b: the throwaway JVM's static analysis blocks boot on the unresolved var). The red is loud but tells the lane nothing about the test. Worth a task: static analysis of a candidate's test files should report the unresolved var against its test, not stop the boot.
- **Test-tree fixes can hide behind a green parent.** 130526a8b shows that a fix placed in `test/seon/test_support.clj` makes a parent green when the whole test tree is copied. Lanes proving a red-first change should check that the fix is in `src/` and that the red test file is the only test file they change.
- **The slot wait is shared.** Check slots are held by other lanes; a check can time out waiting for a slot (35cafc1dc's first attempt). That is not a proof outcome; retry it.
- **Foreign working-tree breakage (not touched).** During the audit the PostToolUse hook reported `test/seon/schema_description_test.clj:13:83 [error/syntax] Unmatched bracket` in the shared working tree. The file is not in any candidate and was not edited by this lane. Its owner should repair it.

## Artifacts

- Candidate commits (dangling git objects, no refs): 9dfcc6b55841 (776a04249), a732dca0b810 (f87629f9b), 0657dbceec31 (66de34302), e50cb7e2ccbe (de1f48c23), 3aac7ae7febc (5a08c13af), 20e20c9649c8 (2edaf91eb), b2773e2154d0 (35cafc1dc), d85805748d7a (978f7122c), b224b17a2a37 (249c18f9a), 586912d41df2 (130526a8b, test file only; the all-test-tree candidate 8acb1b413150 is the withdrawn one).
- Run outputs and summary: scratchpad `runs/` (`summary.txt`, `parent-*.txt`, `control-*.txt`).
