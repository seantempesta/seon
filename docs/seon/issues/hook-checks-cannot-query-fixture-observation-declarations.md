---
type: issue
status: open
severity: friction
tags: [issue, test, operator, wave/dev-tooling-face-hygiene]
---

# Preserve fixture observations before deferring expensive adoption checks

## Problem

The post-adoption check runs its selected tests in symbol order under one
120000 ms allowance. An expensive test can consume that allowance before
ordinary reaching tests report. The September 15 hook batch `6fedfed5`
converged but its check timed out on
`my.examples-test/public-docstring-examples-run-in-the-canonical-agent-context`.

## Evidence

At default basis 536872526, a live database query returned **zero** tests
carrying `:seon.test/fixture-observation`. The schema is installed, and
declarations exist in test metadata, for example
`test/seon/blob_test.clj:218`. Static indexing's `var-row`
(`src/seon/fn.clj:348`) copies usage, call edges and subjects but omits
fixture-observation metadata. Runtime `definition-row`
(`src/seon/sci/eval.clj:392`) also omits it. The examples test itself has no
observation declaration (`test/my/examples_test.clj:47`).

The examples test directly calls `seon.sci.eval/evaluate`; at basis
536872528 the existing reach query finds 19 cluster functions and 16 value
renderer functions. It does not reach every render function. No specific
incorrect edge was established. Full rows and the rejected candidate are
recorded in the [landing note](../../prds/context-generation/research/hook-publication-race-2026-09-15.md).

A candidate check passed nine canonical assertions only because its fixture
explicitly inserted the missing fact. It was not landed: that proof alone
would have hidden the production indexing omission.

## Owner

Record declared metadata in the existing program-row owners before changing
`src/seon/test.clj` to partition runnable and deferred observations. The
assignment expressly protects `src/seon/fn.clj`; this lane did not edit it.
This is related to, but distinct from, the existing
[runtime usage metadata issue](sci-test-declarations-drop-explicit-usage-metadata.md).

## Acceptance

Use real source declarations through indexing in the canonical regression.
The automatic check runs ordinary reaching tests, lists deferred observation
symbols and reasons, and emits a working exact `bin/test-check` command for
explicit execution with a declared bound. The CLI currently accepts only
root/cluster selection, so its explicit-test invocation must be implemented
with the check change. Do not treat absent observation facts as proof that
every selected test is cheap.
