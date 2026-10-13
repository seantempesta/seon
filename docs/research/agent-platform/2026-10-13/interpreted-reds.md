---
title: "Interpreted reds before chain 5"
type: research
status: complete; lane interp-reds classification
basis: HEAD 606110768; compiled run 0641b8bb4778, interpreted on agent branches interp-reds-b and interp-reds-c
---

# Interpreted reds before chain 5 (lane interp-reds, 2026-10-13)

The seven tests found red when the gate ran them on a branch (default runs c067c98250eb and d302515d3574, no
longer in the store: main holds zero test runs after the Run 20 setup) were rerun both ways on HEAD 606110768:

- interpreted: `my.test/check` naming them, agent branch `interp-reds-b` (each member `:seon.test.member/evaluator
  :interpreted`);
- compiled: `bin/seon check --at HEAD --include-long --test ...` (throwaway JVM, run 0641b8bb4778: 7 executed,
  pass 49 / fail 3 / error 3, 4 red).

**The premise held for three of seven.** Four are red compiled too, so they are plain reds on HEAD, not
interpreted reds.

| Test | Compiled (0641b8bb4778) | Interpreted (interp-reds-b) | Class | Cause | Disposition |
|---|---|---|---|---|---|
| `seon.fn-test/defining-forms-share-one-form-local-kondo-batch` | green | 4 pass / 1 fail (kondo called 0 times) | A: Var redefinition | `with-redefs [analyzer/analyze ...]` in an SCI body rebinds the context's Var; compiled `seon.fn/analyze-forms` calls the JVM Var, so the counter never moves | fixed: counts `seon.fn.analyzer/analyze` entries with `seon.profile/measure` + `seon.profile/calls-of` |
| `seon.fn-test/my-web-cascade-class-warns-without-vetoing-publication` | green | 2 / 1 (0 findings, 44 expected) | A | same redefinition; compiled `seon.fn/build-manifest` analyzed the real file (no findings) | fixed: synthetic analysis handed straight to `seon.fn/publication-findings` and `seon.fn/assert-clean-analysis!` |
| `seon.fn-test/publication-veto-is-exactly-the-cant-load-finding-classes` | green | 0 / 7 | A | same | fixed: same owners, data in, veto or warning out |
| `seon.fn-test/the-indexer-emits-no-attribute-the-program-entity-schema-drops` | **red** (contract refusal) | red (same refusal) | B: red on HEAD | the test passes a projection as `schema-targets` (argument 3 of `seon.fn/analysis-entities-by-file`, `[:map-of :qualified-keyword [:set :qualified-symbol]]`); its signature moved in 55af9e04b and the test kept the old argument | fixed: passes `program-targets` over `population-targets` as `seon.fn/build-artifact` does |
| `seon.analysis-identity-test/shifted-findings-update-only-positions-at-one-ten-and-one-hundred-definitions` | **red** | red | B | creates its root under `java.io.tmpdir`, then cleans it with `seon.test-support/delete-recursively!`, which refuses anything outside `tmp/` | fixed: `seon.test-support/temp-root` + `seon.test-support/delete-root!`, the pair made for this |
| `seon.program-test/indexed-and-evaluated-declarations-are-the-same-entities` | **red** (2 fails) | red (same 2) | B | evaluated `sample.s1` ns source reformatted over three lines (other digest); evaluated `sample.s1/left` lacks the `sample.s1/right` call edge | task `seon.program/an-evaluated-namespace-keeps-its-read-source-and-call-edges`, test tagged |
| `seon.reset-edges-test/source-publication-refuses-an-outside-caller-and-keeps-its-head` | **red**: `seon.fn/unresolved-macro-refusal` on fixture ns `reset.publication.target` (no kondo analysis); 12.5 s vs 5 s bound | red: NPE in `seon.fn/lint-entities` (finding with no row), 14.8 s | B (+ C) | compiled: fixture analysis gap; interpreted: `seon.test-support/source-manifest`'s cache is a fresh SCI atom, so the whole checkout is analyzed and hits the known NPE `seon.fn/a-finding-without-a-row-throws-in-lint-entities` | task `seon.fn/the-outside-caller-publication-test-analyzes-the-whole-checkout`, test tagged |

## Classes

- **A: the test redefines a Var that compiled code calls (3 rows).** This is the only interpreted-only class here.
  It is not a host-bound test that should have run compiled. Host-bound status is per declaration
  (`:seon.fn/host-bound?`, `seon.fn/host-bound-callers`), and these bodies are ordinary interpretable code. They
  are red because they break the testing law ("never `alter-var-root`, `with-redefs` ... over any Var"). An SCI
  `with-redefs` reaches only the context's binding; the platform runs compiled on every branch and never sees it. So
  `seon.test/resolve-test`, the code that picks interpreted or compiled, is right as it is: running these
  compiled on a branch would test main's code, not the branch's. The fix belongs to the test: give the owner its
  input as data, or read the armed call counters. 158 `with-redefs` remain in `test/` (45 in
  `seon.cluster.turn-test`). Each one goes red interpreted as soon as a branch change reaches it and the
  redefined Var is called from compiled code. That is the next sweep.
- **B: red on HEAD under both evaluators (4 rows).** These are not interpreted reds. The gate saw them first
  because nobody runs them compiled on default: main held no runs. Two were fixed here, two are filed with
  their test as proof.
- **C: costs that differ by evaluator.** Test-support state held in an atom or delay (`manifest-cache`) is
  per-SCI-context when interpreted, so it is rebuilt cold on every branch run (13.8 s here).

## Proof

Red interpreted on HEAD (interp-reds-b): kondo-batch 4/1, cascade 2/1, veto 0/7, indexer 0/0/1, analysis-identity
15/0/1. Green interpreted after (agent branch interp-reds-c, the changed deftests evaluated in `seon.fn-test`):
kondo-batch passed (reaching red []), cascade 3/0, veto 8/0, indexer 7/0. Green compiled: this commit's
`bin/lane commit --test` check.

Context finding: in that probe, a REPL `(require '[seon.profile :as profile])` followed by a deftest using
`profile/` left the test row uninstallable. The reply carried only "Program row could not be installed; correct
the reported acquisition evidence." (`seon.sci.eval/acquisition-refusal`). The message names neither the alias nor
the row.
