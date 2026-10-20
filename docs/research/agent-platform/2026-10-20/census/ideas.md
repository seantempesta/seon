---
title: "Ideas worth carrying to the Jolt architecture (census-static)"
type: research
---

# Ideas worth carrying to the Jolt architecture (census-static, 2026-10-20)

Owner guidance (2026-10-20): "port the best ideas and not necessarily the exact code as we want to adapt it and keep things simple." This page is that: the ideas the Seon code implements that an agent needs to finish a task in few turns, ranked by how much they matter to that, each with the guarantee it gives, the deftests that prove it (the tests are the spec to carry over, not the code), the simplest form it can take on Jolt, and where today's Seon code is more complicated than the idea.

How to read the numbers. Line counts are stored definition lines (`namespaces.edn`). "Jolt status" is NEEDS.md's. Every test named below exists in the stored program on default (verified by script against `tests.edn`; see the last section). **None of them has a stored passing result on default**: default holds 3 stored test runs and one partial operator log (41 reds). A named test is the *specification* to re-run against the Jolt form, not evidence that the Seon form is green today. Where a named test appears in the stored red list it says so.

Ranking logic (owner's metric, turns to green per task): first what the agent sees before its first form, then what it learns from each form it submits, then what refuses and judges its work, then what the system does between turns, then infrastructure.

---

## 1. The task's generated context is the whole briefing

**Guarantee.** At the start of a turn the agent is shown its task's plan, each named proof with its status and every failing assertion as text, every backticked reference in the plan resolved (symbol to doc and source, a missing symbol to a problem on the task), every change that will merge with its test status, its cost against main, and, when the done rule refuses, the forms that act on the refusal. It never has to search to find where to start.

**Proving tests.**
`seon.context-test/a-red-proof-shows-every-failing-assertion-with-its-claim-as-text`,
`seon.context-test/every-reference-a-task-makes-is-in-the-block`,
`seon.context-test/the-block-resolves-the-names-a-task-references`,
`seon.context-test/a-task-naming-a-missing-symbol-shows-a-problem`,
`seon.context-test/an-updated-plan-is-the-next-blocks-task-with-its-references-resolved`,
`seon.context-test/a-plans-small-program-function-shows-its-source-and-a-large-one-its-doc`,
`seon.context-test/the-block-shows-every-change-that-will-merge-with-its-test-status`,
`seon.context-test/a-done-refusal-leads-the-turn-with-the-forms-that-act-on-it`,
`seon.context-test/broken-tests-show-with-their-failures`,
`seon.context-test/an-ended-summary-with-no-evidence-is-unknown-never-zero`,
`seon.context-test/a-warm-model-request-allocates-in-proportion-to-what-it-shows`.

**Simplest Jolt form.** One pure function `(context task db) -> vector of blocks (data)` over the task map, its proofs' last results, `resolve-ref` over the plan's backticked symbols, and the branch diff. `proto.agents.context` (72 lines, NEEDS#9) already does plan, proofs, red assertions, symbol resolution and last evals; the missing pieces are NEEDS#15's: needs-neighbours, namespace, token budget, prompt. Each block is a map the printer prints; a viewer (model or HTML) differs only in the printer's output.

**Where the Seon code is more complicated.** `seon.context` is 1,076 lines in 72 functions, `seon.render.transcript` another 1,115. Most of the extra is render-cache units keyed by what each render read, history units shown by reference past a dial, "must-read" renders and SCI-context reads: all consequences of rendering lazily inside a viewer's SCI context. A function that is a pure fold over data needs none. Jolt status: partial.

---

## 2. Proofs run by themselves; a red assertion is data; green at the start ends the task

**Guarantee.** After every submission that evaluated a form, the task's proofs (and the tests that reach what changed) run on the agent's branch and the agent reads their result with its next context; an assertionless test is a failure, not a pass; a task whose proofs are already green at its start ends with no model call.

**Proving tests.**
`seon.test-runner-test/captures-counts-and-failure-identities-per-test`,
`seon.test-runner-test/assertionless-test-is-an-attributed-failure`,
`seon.test-runner-test/result-facts-live-on-the-test-entity-and-reruns-replace-them`,
`seon.test-runner-test/repeated-identical-errors-have-one-whole-face`,
`seon.cluster.db-flow-test/a-merge-step-green-at-its-start-ends-done-with-no-model-call`,
`seon.cluster.db-flow-test/a-pending-proof-opens-the-first-model-turn`,
`seon.turn.test-step-verdict-test/the-step-verdict-covers-every-test-of-the-member-list`,
`seon.mcp-entrance-test/an-agent-runs-its-own-test-on-its-own-branch`,
`seon.mcp-entrance-test/a-test-written-at-the-repl-reaches-the-function-it-calls`.

**Simplest Jolt form.** After each eval record, `run-tests-forked` runs every program test in a fork of the agent (5-10 ms per NEEDS#24/#25, measured at current scale) and returns `{sym {:pass n :fail [assertion maps]}}`. No reach index, no evidence reuse: the fork is the isolation. Revisit only past about 500 tests (NEEDS-OD 9). "Green at start" is `(every? green? (proofs task))` evaluated by the reconciler before it launches an agent.

**Where the Seon code is more complicated.** `seon.test` (3,398 lines) + `seon.test.runner` (2,749) + `seon.test.cache` + `seon.test.platform`: reach-digest reuse, admission refusals, input roots, worker JVMs, claim/terminate bookkeeping and `requiring-resolve` plumbing (`seon.test.runner` has 6 lookup functions and 7 `try` functions; `seon.test` 8 and 17). All of it exists to avoid running a test twice; forking per run makes running a test cheap enough that the machinery disappears. Jolt status: done (R1).

---

## 3. The agent's orientation text is checked against the program

**Guarantee.** `(help)` and every step template name only forms that are installed, every reference in them resolves, and a section answers by its anchor; the instruction an agent reads cannot drift from the code it describes.

**Proving tests.**
`seon.bootstrap-test/every-reference-in-help-resolves`,
`seon.bootstrap-test/help-names-only-installed-keys`,
`seon.bootstrap-test/help-references-follow-the-program-they-cite`,
`seon.bootstrap-test/help-answers-one-section-by-its-anchor`,
`seon.bootstrap-test/help-and-every-step-template-describe-one-chain-branch-and-plan-updates`,
`seon.help-test/help-names-only-installed-forms`,
`seon.help-test/help-teaches-the-pending-state-and-that-tests-run-by-themselves`,
`seon.help-test/help-teaches-the-chain-block`.

**Simplest Jolt form.** One Markdown file (`help.md`, NEEDS#15) whose backticked symbols go through the reference resolver (idea 7) in one test: "every backticked symbol in help.md resolves in the running agent". No rendering code at all.

**Where the Seon code is more complicated.** `seon.bootstrap` is 159 lines but its render, evidence ("help-evidence-tracks-its-code-version") and per-agent situation capture are tied to SCI. The idea is the single resolve-all test. Jolt status: partial (no prompt text yet).

---

## 4. One reply is one data map; the path is total; the cost is on every reply

**Guarantee.** Every submission returns the evaluated value (or an error map with no value), the printed text, the result handle, timing and allocation, errors and late diagnostics beside the value; a failure of a later stage is attached beside the value and the value still returns; one recording transaction is durable before the reply.

**Proving tests.**
`seon.repl-test/a-response-carries-errors-as-data-and-late-diagnostics-beside-the-value`,
`seon.repl-test/a-failed-evaluation-says-error-in-the-one-map`,
`seon.repl-test/an-error-response-carries-no-value`,
`seon.repl-test/an-interrupted-evaluation-says-so-as-readable-data`,
`seon.repl-test/printed-output-is-separate-from-the-value`,
`seon.repl-test/a-reply-within-every-declared-bound-adds-nothing-and-an-exceeded-bound-is-a-findings-entry`,
`seon.mcp-entrance-test/an-sci-reply-is-what-the-run-saved`,
`seon.mcp-entrance-test/a-thrown-call-carries-its-whole-request`,
`seon.mcp-entrance-test/a-reply-keeps-its-duration-after-an-earlier-run`.

**Simplest Jolt form.** `proto.agent/eval-forms` (NEEDS#11, 89-265 us round trip, 1.4-1.7 ms with recording): read forms with edamame positions, `eval` each natively, build `{:value :shown :handle :ms :alloc-bytes :errors}`, one `d/transact` before returning. The "late diagnostics" and "findings" are keys, not a second channel.

**Where the Seon code is more complicated.** `seon.batch` (928 lines) abstracts every stage as a value so an entrant can supply its own reader or evaluator (SCI, JVM, a file); `seon.turn.batch` (1,066) records program facts per form by joining clj-kondo analysis to forms by span; `seon.repl` (545) generates bytes for a prompt format. Jolt has one evaluator and one reader, so the stage-as-value indirection and the span join go. Note `seon.batch/compiled-function?` and `seon.repl` each call a name lookup (`find-ns`, `requiring-resolve`) that the idea does not need. Jolt status: done.

---

## 5. Refusals say what failed and what to do

**Guarantee.** An error is one flat map (type, operation, message, the failing member path, the offending value held once as a reference, optional fix) and its text names the schema it expected, not the category of failure; the same function builds every error, so the shape cannot drift.

**Proving tests.**
`seon.error.refusal-test/error-constructor-builds-the-declared-alternative`,
`seon.error.refusal-test/a-constructed-error-is-a-value-of-its-inputs-but-time`,
`seon.error.refusal-test/a-large-argument-is-held-by-reference-in-the-chain`,
`seon.error.refusal-test/error-data-of-any-shape-is-referenced-without-the-stack-or-its-leaves`,
`seon.refusal-grammar-test/structured-refusals-share-one-actionable-rendering`,
`seon.refusal-grammar-test/collection-refusals-name-the-failing-member-and-element-form`,
`seon.refusal-grammar-test/reference-expectations-describe-the-schema-not-the-failure-category`,
`seon.instrument-test/a-missing-key-refusal-holds-values-and-names-the-key`,
`seon.ref-test/an-unresolved-reference-says-what-failed-and-what-to-do`.

**Simplest Jolt form.** `proto.contract/refusal` plus `seon.error.problem/problem-sentence` (pure over Malli, NEEDS#26; already used): one constructor `(error type op message data)`, one problem sentence per Malli explain entry. The tests above are about the sentence, not the construction machinery.

**Where the Seon code is more complicated.** `seon.error` is 2,587 lines (140 functions), `seon.error.refusal` 731 more with world references and stack frames, writer-owned recurrence counting and escalation. BAGGAGE already drops refusal construction with world references. The agent-visible idea is the sentence and the one flat shape. Jolt status: done.

---

## 6. The value printer: one budget, whole when small, cut with a runnable continuation

**Guarantee.** Every shown value is printed by one function under one budget; an ordinary value prints whole; a cut value says its size in estimated tokens and prints the plain `clojure.core` form (`drop`, `take`, `get-in`, `subs`) that reads the rest; the text stays inside its charge; a renderer that throws never replaces the value.

**Proving tests.**
`seon.render.value-law-test/the-text-stays-inside-its-charge-and-budget`,
`seon.render.value-law-test/escaped-strings-stay-printable-under-a-small-budget`,
`seon.render.value-test/every-cut-prints-the-plain-clojure-form-that-reads-its-rest`,
`seon.render.value-test/ordinary-values-print-whole-at-the-agent-budget`,
`seon.render.value-test/a-map-beyond-the-budget-costs-the-budget-not-its-size`,
`seon.render.value-test/large-values-have-bounded-work-in-both-faces`,
`seon.render.value-test/an-agent-facing-cut-reports-its-size-in-estimated-tokens`,
`seon.printer-elision-test/elision-forms-name-only-clojure-core`,
`seon.printer-elision-test/a-cut-set-or-map-continues-where-it-stopped`,
`seon.print-test/a-small-nested-form-is-never-cut-inside`,
`seon.print-test/p-total-generated-grammar-emits-and-readable-faces-round-trip`.

**Simplest Jolt form.** `proto/seon-min` (939 lines, fipp only; 924/924 corpus prints identical, laws pass). The laws and cut-continuation tests carry over as they are. Render pairs stay out until OD2.

**Where the Seon code is more complicated.** `seon.render.value` (1,666 lines, 96 functions, 27 weak contracts, 5 lookup functions) keeps database-aware printing, render-pair dispatch, a fallback chain (a recovery path beside the printer for printer defects) and HTTP expanders. All listed in BAGGAGE as staying behind. Jolt status: done.

---

## 7. References in prose are found by one rule and resolved to what the agent needs

**Guarantee.** A backticked symbol resolves to its doc and source (or contract) at the level its position implies, a keyword to its schema, a file with `#heading` to the section with its headings below and size, `reference-code/<project>` to its pinned revision; an unresolved reference carries the nearest names and is a problem on its owner, never a silent miss.

**Proving tests.**
`seon.ref-test/all-prose-uses-one-reference-rule`,
`seon.ref-test/one-text-yields-names-paths-and-line-citations-by-the-same-rules`,
`seon.ref-test/a-code-span-carries-its-reference-level`,
`seon.ref-test/a-missing-heading-names-the-nearest-headings`,
`seon.ref-test/a-missing-file-names-the-nearest-files`,
`seon.ref-test/an-unresolved-name-carries-the-names-its-namespace-declares-nearest-it`,
`seon.ref-test/a-reference-code-section-carries-its-pin`,
`seon.ref-test/a-var-with-no-docstring-cited-for-its-summary-is-a-problem-on-the-var`,
`seon.ref-test/fenced-code-and-prose-spans-are-not-references`,
`seon.ref-test/a-referents-own-reference-is-a-summary-and-a-read-never-its-content`.

**Simplest Jolt form.** NEEDS#14 estimates 200 lines: a text scan for backticked spans and links (fenced code excluded), a classifier (qualified symbol, qualified keyword, relative path with optional `#anchor`), and a resolver per kind over the running program, the schema registry and the file system; a heading scan replaces commonmark. The proofs above are the table test.

**Where the Seon code is more complicated.** `seon.ref` is 818 lines with 6 lookup functions (`find-ns` x5, `find-var` x2, `requiring-resolve`) because it resolves names through SCI and the dependency cache; a name table built from the running program replaces them. Jolt status: partial (symbols only).

---

## 8. The forgiving reader: prose is a comment, delimiters are repaired where unambiguous

**Guarantee.** A model reply that mixes prose and code reads as the code it contains (prose becomes comments, tool markup is dropped); a stray closer is dropped and missing closers are inserted before the next column-zero line; valid Clojure is never changed; every repair is reported, and forgiving forgiven text changes nothing.

**Proving tests.**
`seon.sci.forgive-test/valid-clojure-is-unchanged`,
`seon.sci.forgive-test/forgiving-forgiven-text-changes-nothing`,
`seon.sci.forgive-test/the-repairs-account-for-every-change`,
`seon.sci.forgive-test/an-unbalanced-text-names-each-repair`,
`seon.sci.forgive-test/a-stray-closer-keeps-tokens-apart`,
`seon.sci.forgive-test/a-markdown-reply-reads-only-its-forms`,
`seon.sci.forgive-test/a-form-read-mid-text-has-the-texts-own-line-and-column`,
`seon.sci.forgive-test/tool-markup-in-a-reply-is-prose`,
`seon.sci.forgive-test/a-reply-reads-in-allocation-linear-in-its-length`.

**Simplest Jolt form.** NEEDS#12: one linear scan of about 120 lines, run before `edamame` reads forms with positions. Two of the tests are laws (identity on valid text, idempotence); the rest are example rows. R1 reached 1.17 turns per task without it, so keep it to the scan.

**Where the Seon code is more complicated.** `seon.sci.forgive` (445 lines) feeds a stepper that reads each form once with the read supplied as a value to `seon.batch`; `seon.sci.reader` (828 lines) is the SCI-flavoured reader. Neither is needed with a native reader. Jolt status: partial (fences and prose-to-comment exist, no repair).

---

## 9. One done rule judges every task, and the merge uses the same list

**Guarantee.** `done-refusals` is the only judge of "done": the proofs are green, there is no regression of main, no stray red among the tests the branch changed, no contract gap; the merge gate evaluates the same member list, so what the agent sees, what root advances on and what merges are one derivation. A never-started test is evidence of nothing, not green.

**Proving tests.**
`seon.cluster.source-test/done-refusals-judges-every-task-alike`,
`seon.cluster.source-test/done-and-merge-judge-one-member-list`,
`seon.cluster.source-test/done-and-merge-judge-a-red-reaching-test-the-same-way`,
`seon.cluster.source-test/done-and-merge-judge-a-reaching-test-with-no-current-result-the-same-way`,
`seon.cluster.source-test/done-refusals-judges-a-never-started-member-as-evidence`,
`seon.cluster.source-test/an-uncovered-refusal-names-the-red-tests-reaching-it`,
`seon.cluster.source-test/an-undefined-slot-is-not-a-complete-contract`,
`seon.context-test/a-chain-of-one-is-judged-by-the-done-rule`.

**Simplest Jolt form.** `proto.agents.reconcile/done-refusals` (done, R1): a pure function of (task proofs and their results, the branch's changed tests and their results, the contract gaps of the changed functions) returning a vector of refusal maps; the empty vector is done. The merge task calls the same function.

**Where the Seon code is more complicated.** `seon.cluster.source` is 2,413 lines, 131 functions, 24 `try` functions, 11 `requiring-resolve` lookups (the 10-19 inventory's cycle 1: it reaches `seon.test` lazily for `reaching-selection`, `admit-run`). The member list is a reach-based selection cached by call-graph digest ("unchanged-call-graph-reuses-the-merges-member-list", "a-hub-change-defers-the-tests-above-its-src-callers-to-the-platform-run"). With "run every test in a fork" the member list is just all tests, and `done-refusals` takes it as a value, so the cycle disappears. Seon's own HEAD shows the cost of the reach version: a hub change runs about 167 tests per request (commit db683577f). Jolt status: partial.

---

## 10. Contracts at every boundary, armed from the definition

**Guarantee.** Every function with a Malli `:=>` contract validates its inputs on entry and output on exit, so no caller re-checks a shape; a violation is a flat error naming the failing member path and holding the argument once; an uncontracted `defn` still evaluates and returns a growing warning; a contract that cannot be checked is itself a minimal error.

**Proving tests.**
`seon.instrument-test/a-wrong-shaped-call-throws-a-flat-error-value`,
`seon.instrument-test/a-collection-violation-names-its-member-path-and-holds-the-argument-once`,
`seon.instrument-test/a-refusal-holds-the-refused-argument-once-as-a-reference`,
`seon.instrument-test/a-contract-that-cannot-be-checked-is-a-minimal-declared-error`,
`seon.instrument-test/a-public-multi-arity-does-not-reenter-its-instrumented-var`,
`seon.instrument-test/adoption-disarms-a-retired-contract`,
`seon.uncontracted-warning-test/an-uncontracted-defn-reply-warns-with-the-growing-count`,
`seon.uncontracted-warning-test/an-any-slot-defn-reply-names-the-slot`,
`seon.contract-coverage-test/a-contracted-function-has-no-gaps`.

**Simplest Jolt form.** `proto.contract` (290 lines, NEEDS#3, 0.20-0.26 us per checked call): `defn` checks from its definition with direct linking safe; agent `defn` without a schema is warned and refused at merge. The warning-count and no-gap tests are the table.

**Where the Seon code is more complicated.** `seon.instrument` is 1,265 lines (7 lookup functions: `find-ns`, `find-var`, `ns-interns`) because it arms wrappers on exact Vars under a projection, preserves wrappers across reloads and detects replaced roots; there are 1,092 uncontracted and 830 weak-contract functions in the program, so the census itself shows the back-fill never closed. With contracts checked at definition on Jolt, wrapper preservation, replaced-roots and adoption disappear. Jolt status: done.

---

## 11. Between turns: an unanswered wake is the only reason to run an agent

**Guarantee.** Work is derived from facts: an agent has a turn exactly when it has a wake it has not answered; two wakes in one transaction are one turn; a wake arriving mid-turn opens the next turn; an unassigned agent gets no model turn; the turn bound counts turns since the latest outside wake; the derivation is total over every state; comment-only input is recorded but is never work.

**Proving tests.**
`seon.turn-work-test/the-derivation-is-total-over-every-state`,
`seon.turn-work-test/answeredness-is-the-turns-own-transaction`,
`seon.turn-work-test/two-wakes-in-one-transaction-are-one-turn`,
`seon.turn-work-test/a-wake-arriving-mid-turn-opens-the-next-turn`,
`seon.turn-work-test/an-unassigned-agent-with-a-wake-gets-no-model-turn`,
`seon.turn-work-test/the-turn-bound-is-turns-since-the-latest-outside-wake`,
`seon.turn-work-test/only-a-turn-whose-reply-came-from-a-model-attempt-answers`,
`seon.turn-work-test/comment-only-input-is-recorded-but-never-becomes-eval-work`,
`seon.turn-work-test/a-reply-with-no-evaluated-form-continues-however-many-come-in-a-row`.

**Simplest Jolt form.** The `:router` proc of F1 indexes subscriptions (data) by attribute and entity and wakes agents (BAGGAGE: replaces `seon.cluster.wake`); the derivation `(work agent db)` is a pure function of the agent's unanswered wakes and its last turn. These tests are its spec; the model call in R1's turn loop is the step it feeds.

**Where the Seon code is more complicated.** `seon.turn` is 5,513 lines, 218 functions, 42 uncontracted, 11 lookup functions (4 `context-*` lazies for a cycle with `seon.context`), writer-owned transitions, interrupted-run closing at boot and episodes. `seon.cluster.wake-test` is five of the stored reds (wake delivery timeouts). Only the derivation is the idea; the rest is what an SCI-hosted, restartable turn proc needs. Jolt status: partial.

---

## 12. Tasks are data, tasks chain by `needs`, one reconciler closes the gap

**Guarantee.** A task is one entity (ns/name, plan, instructions, proofs, needs, environment); a needs cycle is refused at the writer; a leaf is ready when its needs are done; only a merge completes a leaf; the reconciler's transition table gives every phase and event exactly one row and is exhaustive, deterministic and convergent; applying desired state twice writes nothing.

**Proving tests.**
`seon.task-test/a-needs-cycle-is-refused-at-the-writer`,
`seon.task-test/the-ready-leaves-are-those-whose-needs-are-done`,
`seon.task-test/only-a-merge-completes-a-leaf`,
`seon.task-test/three-tasks-with-tempids-are-written-linked-in-one-transaction`,
`seon.task-test/ready-answers-in-under-10-ms-for-fifty-leaves`,
`seon.task-test/task-members-have-defaults-and-kinds`,
`seon.cluster.reconcile-test/the-transition-table-is-exhaustive-deterministic-and-convergent`,
`seon.cluster.reconcile-test/every-agent-phase-and-event-has-exactly-one-row`,
`seon.cluster.reconcile-test/reconcile-closes-each-gap-once`,
`seon.cluster.reconcile-test/an-impossible-task-state-is-refused-naming-its-constraint`,
`seon.cluster.db-flow-test/a-filed-tasks-design-prove-implement-chain-merges-with-no-hand-act`,
`seon.cluster.db-flow-test/a-capped-chain-attempt-bails-to-root-once-and-root-runs-it-again`,
`seon.step-test/problems-are-the-references-that-do-not-resolve`,
`seon.step.design-test/handoff`.

**Simplest Jolt form.** Done: `proto.agents.task` (269), `reconcile` (285, 13 deftests), `root` (271), `template` (101); R1 ran 50 tasks in 3.9-4.3 s and restarts from facts (NEEDS#7). The transition-table tests port as written.

**Where the Seon code is more complicated.** `seon.task` is 1,845 lines (134 functions) because it carries both the installed chain model (`chain!`, `:seon.task/next`, branch relink) and the target needs model. The target-only form is what Jolt has. `seon.reconcile` has 17 uncontracted of 25 functions; the config diff it implements is a different reconciler from the task one (`seon.cluster.reconcile`, 190 lines, 4 reaching tests). Jolt status: done.

---

## 13. Merge is a three-way over program keys that only a green gate advances

**Guarantee.** A merge takes the whole delta since the merge base; a value changed on both sides resolves by its declared rule or stays a conflict recorded as data on the merge branch; disjoint set additions union; a retraction on the branch retracts on an unchanged main only; a repeated merge adds nothing; program facts from the agent's run never copy, tasks and attempts copy by identity; root can merge main forward into a branch.

**Proving tests.**
`seon.merge-test/a-merge-carries-the-whole-delta`,
`seon.merge-test/a-repeated-merge-adds-nothing`,
`seon.merge-test/a-value-changed-on-both-sides-resolves-by-the-declared-rule`,
`seon.merge-test/a-conflict-keeps-the-merge-branch-with-mains-version-and-an-unmerged-entry`,
`seon.merge-test/disjoint-set-additions-merge-as-their-union`,
`seon.merge-test/a-retraction-on-the-branch-retracts-on-an-unchanged-main-only`,
`seon.merge-test/a-natural-identity-born-on-both-sides-maps-to-mains-eid`,
`seon.merge-test/the-merge-base-is-the-merge-requests`,
`seon.merge-test/program-and-runtime-facts-never-copy`,
`seon.merge-forward-test/a-forward-merge-is-a-merge-commit-on-mains-head`,
`seon.merge-forward-test/after-a-forward-merge-the-branch-changed-only-its-own-declarations`,
`seon.context-test/a-merge-branchs-unmerged-entry-shows-in-the-block-until-resolved`.

**Simplest Jolt form.** NEEDS#28 (about 500 lines, M1 in flight): `proto.merge/three-way` is a pure function over keyed facts plus Datahike `merge-db`; harvest of the agent's facts at retirement. The tests are the semantic table (two-sided change, union, retraction, idempotence).

**Where the Seon code is more complicated.** `seon.merge` is 608 lines in 26 functions; `seon.cluster.source` adds publication into scratch branches, the declaration gate and reach-based contributions. Seon's three-way also decides which test results the merge may reuse ("a-merge-reuses-the-chain-branchs-green-at-an-unchanged-reach"), absent when every test runs in a fork. Jolt status: partial.

---

## 14. Turns to green, charged to the producer of what the agent was shown

**Guarantee.** Each ended attempt is graded once on main; the report gives median and p90 turns to green by commit, split by step and by model; every wasted next form is charged to the producer of what was shown, so a fix lands at the render that misled and not at the agent.

**Proving tests.**
`seon.mine-test/the-report-gives-the-median-and-p90-of-turns-to-green-by-commit`,
`seon.mine-test/an-ended-attempt-is-graded-once-on-main`,
`seon.mine-test/each-waste-rule-charges-the-producer-of-what-was-shown`,
`seon.mine-test/the-first-write-turn-is-the-first-call-that-recorded-a-definition`,
`seon.mine-test/the-report-splits-its-windows-by-step`,
`seon.mine-test/a-task-names-its-step-by-its-proofs-and-target`.

**Simplest Jolt form.** NEEDS#33: attempts already hold `turns`, `model-calls`, `tokens`, `outcome`; the report is one Datalog query plus median/p90. Waste charging needs the context blocks to carry their producer's symbol (a key on each block). 80 lines.

**Where the Seon code is more complicated.** `seon.mine` is 648 lines (61 functions) reconstructing "what the agent was shown" from stored renders per step; if a context block is data with its producer named, it is read, not reconstructed. Jolt status: partial.

---

## 15. Write-back: accepted entities become exact-span edits to files, proved by reload

**Guarantee.** A merged function replaces its exact source span and nothing else (every unselected byte preserved, spans in UTF-8 bytes); added helpers are written before their callers; a new namespace gets its `ns` form; a file kondo reports an error in refuses the whole write-back and installs no file; a sibling change already in the file is kept and the same declaration conflicts; the entry for a filed task is retired by the merge that did it.

**Proving tests.**
`seon.edit-test/form-edit-preserves-every-unselected-byte`,
`seon.edit-test/form-edit-records-its-span-in-utf8-bytes`,
`seon.edit-test/form-failures-never-fall-back-to-text`,
`seon.edit-test/exact-edit-counts-before-changing`,
`seon.edit-test/selectors-refuse-ambiguity-and-use-parsed-dispatch`,
`seon.cluster.write-back-test/an-agent-replacement-merges-and-writes-back-its-exact-span`,
`seon.cluster.write-back-test/an-agent-addition-merges-and-writes-back-both-declarations`,
`seon.cluster.write-back-test/an-added-helper-is-written-before-the-replaced-function-calling-it`,
`seon.cluster.write-back-test/a-function-in-a-new-namespace-writes-back-with-its-ns-form`,
`seon.cluster.write-back-test/a-write-back-whose-second-file-cannot-be-staged-installs-no-file`,
`seon.cluster.write-back-test/a-sibling-change-landed-in-the-file-is-kept-and-the-same-declaration-conflicts`,
`seon.cluster.write-back-test/a-file-clj-kondo-reports-an-error-in-refuses-at-prepare-naming-the-finding`.

**Simplest Jolt form.** NEEDS#21 (about 250 lines): edamame for spans, rewrite-clj for the replace (byte-identical roundtrip measured on a 16 KB real file), then `jolt.process` for `git commit`; the above tests are the spec. Missing today.

**Where the Seon code is more complicated.** Write-back is spread across `seon.edit` (472 lines, 25 uncontracted), `seon.fs` + `seon.fs.jvm` (1,344), `seon.cluster.source`, `my.program/write-branch!` and digest-fenced protected handlers; the protected-handler layer exists because agents had no native file access. Jolt status: missing.

---

## 16. Estimate first; an overrun is a failing test

**Guarantee.** Every ordinary operation states its expected cost (ms, allocated MB, calls) up front; one table test measures each end to end and is red above its estimate; a regression against the last run is red.

**Proving tests.**
`seon.cost-estimate-test/ordinary-operations-cost-no-more-than-their-estimates`,
`seon.cost-estimate-test/no-ordinary-operation-grows-past-its-accepted-overrun`,
`seon.cost-estimate-test/first-defn-profile`,
`seon.sentinel-test/sentinel-summary-reduces-probes-to-baseline-figures`,
`seon.sentinel-test/a-sentinel-run-leaves-the-stores-branches-as-it-found-them`,
`seon.profile-test/a-call-shows-its-reads-allocation-and-steps-in-its-cell-and-its-request`.

**Simplest Jolt form.** NEEDS#6: a deftest holding a table `{operation estimate}` and a measurement through the real entrance (clock + allocation counters Jolt exposes); each reply already carries `:ms :alloc-bytes`. Per-call profile counters (+100 lines) only if the table cannot attribute an overrun.

**Where the Seon code is more complicated.** `seon.profile` is 874 lines of per-function JDK accumulators per thread, per-thread allocation attribution (removed by the architecture, BAGGAGE) and rankings; `seon.cost.growth` (169 lines) measures cost growth generatively. Keep the table and the sentinel; the rest is the shared-heap attribution problem. Jolt status: missing (bench scripts exist, no deftest).

---

## 17. One model call, one attempt, failure is a value

**Guarantee.** A model step is one call with one deadline: no retry, no backoff, no failover; a missing credential is an unsent error value; a stream that ends mid-body keeps every character that arrived; a reasoning-only or truncated response is a named error with its evidence; a tool call named eval is the reply's code; usage is normalized.

**Proving tests.**
`seon.ai-test/a-missing-credential-is-an-unsent-error-value`,
`seon.ai-test/a-stream-that-ends-mid-body-keeps-every-character-that-arrived`,
`seon.ai-test/a-reasoning-only-response-is-a-named-evidenced-error`,
`seon.ai-test/a-stream-that-ends-before-any-text-is-named-truncation-not-bad-json`,
`seon.ai-test/an-empty-normal-stop-is-a-reply-but-malformed-json-is-a-provider-refusal`,
`seon.ai-test/a-tool-call-named-eval-is-the-replys-code`,
`seon.ai-test/a-payment-required-provider-returns-its-error-value`.

**Simplest Jolt form.** NEEDS#32: `proto.agents.model` (97 lines, `:openai-compat` over jolt-lang/http-client + OpenSSL, measured on DeepSeek) extended with the usage split, reasoning field and cache-hit tokens (+150 lines). The stream and error cases above are the table.

**Where the Seon code is more complicated.** `seon.ai` is 1,357 lines (23 uncontracted): provider descriptor entities in the database, prompt-cache boundary plumbing and an agent overlay. A provider is a map in config on Jolt.

---

## 18. The MCP entrance: two tools, string in, string out

**Guarantee.** An outside agent reaches the system through `eval_clj` and `runtime_status`; each tool's input schema derives from its argument schema; an unknown tool, a non-JSON line or invalid arguments answer an error result, never a crash; the entrant is recorded on each evaluation.

**Proving tests.**
`seon.mcp-entrance-test/tools-derive-each-input-schema-from-its-argument-schema`,
`seon.mcp-entrance-test/an-unknown-tool-answers-an-error-result`,
`seon.mcp-entrance-test/a-request-that-is-not-json-answers-an-error-result`,
`seon.mcp-entrance-test/invalid-arguments-answer-an-error-result`,
`seon.mcp-entrance-test/entrant-origin-is-recorded-on-each-evaluation`,
`seon.dev.mcp-bridge-test/a-call-with-no-live-jvm-names-the-start-remedy`,
`seon.dev.mcp-bridge-test/an-unparseable-line-answers-a-parse-error`.

**Simplest Jolt form.** NEEDS#35 (about 130 lines): a stdio JSON-RPC loop over `clojure.data.json` (P5 measured on Jolt) with `initialize`, `tools/list`, `tools/call` mapped to the same function agents call.

**Where the Seon code is more complicated.** Two layers: `seon.mcp` (508 lines) in the JVM and the babashka bridge `seon.dev.mcp` that finds the live JVM and calls `((requiring-resolve 'seon.mcp/call) ...)`, caching tool lists "for a jvm-down start". A Jolt binary starts in 64-172 ms; the bridge is not needed.

---

## 19. Dials are facts; the shipped default is one decision per attribute

**Guarantee.** Every size, count, byte budget, timeout and threshold is a dial; the effective configuration is independent of map construction order; absence is a decision, never a stored value; an invalid declared value is refused and an extra key ignored; an apply submits only the fact difference.

**Proving tests.**
`seon.config-test/effective-config-is-independent-of-map-construction-order`,
`seon.config-test/explicit-absence-is-a-decision-never-a-stored-value`,
`seon.config-test/compiler-ignores-extra-keys-and-refuses-invalid-declared-values`,
`seon.config-test/config-writer-submits-only-the-fact-difference`,
`seon.config-test/every-agent-dial-has-a-declared-settings-entity`,
`seon.config-test/a-sparse-apply-changes-only-its-keys-and-keeps-no-provider`.
(Stored red: `seon.config-test/the-default-document-has-one-canonical-complete-location` and `config-loads-the-packaged-population-in-a-fresh-jvm`.)

**Simplest Jolt form.** NEEDS#31 (+100 lines): aero reads `#profile #env #include #ref` (P4 measured); Malli validates; the attributes are `:seon.config.agent/max-running`, `max-turns` already.

**Where the Seon code is more complicated.** `seon.config` (962 lines) is a compiler plus a database reconcile boundary with inherited config identities and branch-lacking-overlay installs: all of that follows from running many clusters in one JVM.

---

## 20. Datahike attributes are derived from the Malli entity schemas

**Guarantee.** A registered entity schema yields its Datahike attribute declarations (value type, cardinality, identity, component) with one canonical round trip; an unstorable declaration returns a value rather than throwing; a `[:vector]` attribute is refused by name with the fix.

**Proving tests.**
`seon.schema.datahike-test/registered-shape-round-trips-through-datahike`,
`seon.schema.datahike-test/literal-schemas-derive-their-native-datahike-value-type`,
`seon.schema.datahike-test/edn-backed-attributes-have-one-canonical-datahike-round-trip`,
`seon.schema.datahike-test/canonical-population-native-parity`,
`seon.schema.datahike-test/unstorable-declarations-return-values-without-the-throwing-wrapper`.

**Simplest Jolt form.** D1's 234-line `proto.schema.datahike`, 96 attributes derived once at boot in 1.2 ms (BAGGAGE). Owner decision OD7 (derive, or keep hand-written attributes) should be taken against these tests.

**Where the Seon code is more complicated.** `seon.schema.datahike` (1,068 lines) carries vector cells (about 600 lines), union EDN codecs, projection caches; `seon.schema` has 99 uncontracted-or-weak functions out of 165. Jolt status: partial.

---

## Not carried (owner decides or the architecture removes the need)

- **Render functions per schema** (`seon.render.*`, NEEDS#16, OD2): Jolt prints every value as data; a future schema-specific render is a function applied before `print-value`. Tests that would define it: `seon.render.entity-pairs-test/*`, `seon.render.floor-test/*`.
- **Messages between agents** (`my.message`, `seon.cluster.message`, OD8): no task needs one yet. Spec if wanted: `seon.cluster.message-test/*` (22 tests) and `my.message-test/*` (11).
- **Embeddings** (`seon.embed`, OD6), **blobs** (`seon.blob`, OD5), **error recording once per signature** (`seon.fault`, OD4), **schema name versioning** (`seon.schema.admission`, OD1).
- **Replaced outright:** SCI contexts and runtimes, analysis caches, reach index and overlay, `seon.cache` derived kinds, per-thread allocation attribution, cluster and registry plumbing, the http-kit adapter. Their test namespaces (`seon.sci.*-test`, `seon.test.reach-test`, `seon.cluster.registry-test`, `seon.cache-test`, `seon.call-preparation-test`, ...) are deleted with them, not ported: see `tests.edn` filtered on the replaced namespaces' test namespaces.

## Cross-cutting findings that change how much to port

1. **The stored proof set is not evidence.** 3,428 of 3,471 tests have no stored result on default. Before ports are judged "proved by the Seon tests", run them: the cheapest route is to run the tests named above on the Jolt form, which is the point of carrying them as the spec.
2. **A port is mostly tests.** Each idea is one to three small functions plus 6-14 deftests that pin its behavior. The 179 distinct deftests cited here are the carry-over.
3. **Most "more complicated" code has one cause**: Seon's agents share one JVM, so every function has to be reachable from a viewer's SCI context, cached by content, re-rooted on reload and attributed per thread. The Jolt architecture removes the cause; the guards, lookups and caches that answer it are not ported.

## Verification of this page

`verify-ideas.clj` looked up every backticked `ns-test/name` symbol in `tests.edn`: 183 cited, 179 resolve exactly to a stored deftest, 4 are namespace wildcards (`seon.render.entity-pairs-test/*`, `seon.render.floor-test/*`, `seon.cluster.message-test/*`, `my.message-test/*`). Of the 179: 178 have no stored result (never run on default), 1 is in the stored red list (`seon.config-test/the-default-document-has-one-canonical-complete-location`, idea 19).
