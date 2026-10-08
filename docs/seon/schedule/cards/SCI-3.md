---
title: "Card SCI-3 Delete the copy-induced re-analysis and the test-world interim"
type: reference
status: active
created: 2026-10-08
---

# SCI-3 Delete the copy-induced re-analysis

After SCI-1 and SCI-2. Task: `seon.sci.eval/an-inherited-test-never-sees-the-branch-redefinition-of-its-callee`
(also `seon.sci.eval/a-def-re-evaluated-in-a-fork-names-the-inherited-var-in-its-body`, which the SCI change makes
moot). Lane: Sonnet (deletion with named proofs).

## Deleted

- `seon.sci.eval/stale-reach`, `seon.sci.eval/reinterpret-reach!`, `seon.sci.eval/reinterpret-inherited-callers!`,
  `seon.sci.eval/inherited?` and their `declare`.
- The call in `seon.sci.eval/install-evaluated-rows!` (the `doseq` re-interpreting the reach of a definition that
  landed in a new Var: a definition never lands in a new Var now).
- The call in `seon.sci.arm/arm!` (the `reinterpret-inherited-callers!` arm and its docstring sentence).
- The pre-copy in `seon.sci.eval/install-selected!` ("Copy inherited Vars into this generation before analyzing any
  caller": the `doseq` of `sci/bind-root! ctx candidate @candidate` over `interpreted`).
- The interim in `seon.test/clean-request-handle` (commit 167e89b0d): the test world forks the agent's live base
  again instead of building it with `seon.sci.eval/base-ctx` from the branch's definitions.
- Task entries retired in `resources/seon/tasks/seon.sci.eval.edn`:
  `a-def-re-evaluated-in-a-fork-names-the-inherited-var-in-its-body`; the inherited-test task closes when its proof
  is green.

Kept: compiled-to-SCI interpretation of host-bound callers, program acquisition, arming, digest evidence and test
reach selection.

## Proof

`seon.repl-differential-test/an-inherited-caller-sees-a-same-pass-redefinition`,
`seon.repl-differential-test/another-writer-on-the-cluster-head-neither-hides-nor-freezes-a-callee`,
`seon.repl-differential-test/an-inherited-test-sees-the-branch-redefinition` extended with the model-agent step shape
the task records (main proof, agent branch redefinition, a second eval request; asserting the test's executed digest
and completion), `seon.sci.eval-test/a-branch-change-leaves-its-compiled-callers-compiled`. Plus a count: a
redefinition re-evaluates no caller source and an effectful initializer runs once (a `def` with a counter-bumping
initializer, then a caller's redefinition; the counter is 1).

## Cost

A redefinition no longer pays O(E + S) (reverse closure read, 11-17 ms at 50 callers, plus re-analysis of each
caller and test). Net source: about -150 lines in `seon.sci.eval`.

## Commit

`bin/lane commit LANE -m MSG --test seon.repl-differential-test --test seon.sci.eval-test src/seon/sci/eval.clj
src/seon/sci/arm.clj src/seon/test.clj test/seon/repl_differential_test.clj resources/seon/tasks/seon.sci.eval.edn`.
