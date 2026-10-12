# triage-act patches for files held by root-drift

Held: `resources/seon/tasks/seon.turn.edn`, `resources/seon/tasks/seon.test.edn`, `src/seon/turn.clj`, `src/seon/test.clj`. Apply when root-drift releases them, one commit with `--test seon.task-ingestion-test`.

## Retire in `seon.turn.edn` (re-verified at the code)

- `a-test-step-results...`: `the-test-step-results-never-reach-the-eval-reply` is FIXED. `seon.turn/step` offers `:seon.turn.loop/tests` on every reply (`src/seon/turn.clj`, the `async/offer! reply` form), `seon.cluster.agent/evaluate-source!` selects `:seon.turn.loop/tests` and `:seon.turn.loop/stages` on its outcome branch (the branch every settled answer takes), and `seon.cluster.turn-test/a-defn-breaking-a-reaching-test-shows-it-red-on-the-next-call` asserts `:seon.turn.loop/reaching :seon.turn.loop/red` from the reply. Delete the entry.
- `a-landing-whose-outcome-write-is-refused-is-never-silent` is OBSOLETE: `seon.turn/land-end!`, `seon.turn/landing-tx` and `seon.cluster.source/land!` are defined nowhere in `src`, `src-clm` or `test`. Delete the entry.
- `a-refused-refusal-settlement-throws-past-the-reply` is a DUPLICATE: its detail is merged into `seon.cluster.agent/an-escaped-pass-closes-the-reply-without-its-fault` (commit 87e1476b8). Delete the entry.
- `an-executed-test-request-allocates-seven-hundred-megabytes-in-the-step` is a DUPLICATE of `seon.test/a-one-test-proof-check-allocates-a-gigabyte`: delete it after the merge below.

## Retire in `seon.test.edn`

- `reds-on-head-9d6b392b6-in-task-reaching-tests` is OBSOLETE: its must-read `seon.task/steps!` is defined nowhere, and the HEAD it names is history. Delete the entry.
- `reds-on-head-178aa24e0-in-db-program-schema-tests` is a DUPLICATE of `red-tests-on-head-are-unclassified`. Append to that task's intent: "The same class was found at HEAD 178aa24e0 (run 8c1ff443a361): seon.db-test/database-operations-preserve-refusals-at-their-declared-boundary and query-and-pull-append-evidence-only-when-captured (over its 5 s bound); seon.program-test x6 (a-declaration-added-after-the-first-call-is-a-cache-miss, indexed-and-evaluated-declarations-are-the-same-entities, opening-basis-divergence-is-only-claimed-when-it-is-measurable, runtime-schema-declarations-project-namespaced-properties, typed-cross-namespace-deletion-retracts-function-and-test, declaring-an-attribute-on-a-program-entity-schema-is-sufficient); seon.schema-test x4 (a-refused-registration-is-an-error-value-never-a-throw, an-incremental-projection-equals-its-full-build over its bound, canonical-reference-values-use-the-pull-collection-grammar, every-predicate-schema-declares-what-it-accepts)." Then delete the entry.
- `a-named-check-on-a-branch-carrying-platform-edits-allocates-gigabytes` is a DUPLICATE of `a-one-test-proof-check-allocates-a-gigabyte`. Append to that task's intent, and add `my.test/check seon.test/reaching` to its must-read: "Measured again on default 9a23b8ff2 (2026-10-10), agent eval-fixes, its branch carrying the edit hook's interpreted copies of `seon.sci.eval` and `seon.test`: (my.test/check {:seon.test/identities <17 tests>}) took 18,045 ms and allocated 17,731 MB, executing 76 tests (the 17 named plus the step's reaching tests), GC 12.4 s; the same 17 on a fresh agent took 7,735 ms and 1,157 MB (run f11a3891e603), and every later evaluation on that branch paid a 10 to 18 s test step. Done also when an evaluation that changed no definition runs no reaching test and a named check's cost is proportional to the named tests." And the turn duplicate: "Measured through the turn pass on default (2026-10-08, seon.pass-probe/window as agent impl-f4b): a pass evaluating a form costs 61-112 MB and 85-120 ms with the proofs request answered from the record; a pass whose change makes a proof execute costs 865 MB and 586 ms (seon.test/run 440 ms, select 76 ms), about 700 MB over the unchanged pass for one test world. Done also when an executed one-test request allocates under 100 MB (seon.pass-probe/window on parent and change)." Then delete the entry.

## Tasks not given a red proof because their owner file is held

Each is still VALID per task-triage-1.md (73d3c4d6f); the proof goes in `test/seon/turn_test.clj` or `test/seon/cluster/turn_test.clj` (both held) and the typed intent in `seon.turn.edn`:

- `a-reload-between-forms-is-undetected` (117): `seon.sci.eval/call-detecting-program-change` is used by `seon.test` only.
- `a-form-without-source-is-shown-as-a-fault` (150): `src/seon/turn.clj` passes `(or source "")` as `:seon.cluster.eval/source`.
- `receipt-attributes-are-a-hand-list` (119): `seon.turn/receipt-terminal-attributes`.
- `ai-attempt-stores-printed-copies-of-settings-and-usage` (145): `:seon.ai.attempt/settings-edn` and `usage-edn` store `pr-str` copies.
