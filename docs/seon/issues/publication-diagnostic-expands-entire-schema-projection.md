---
type: issue
status: open
severity: friction
tags: [issue, operator, render, wave/error-face-budget]
---

# Publication diagnostic expands the schema projection

Observed 2026-09-08 while running
`bin/seon init --dev default --changed test/seon/test_runner_test.clj`.
The command reached development SCI acquisition and reported
`The cluster rejected the prepl operation.` Its diagnostic then expanded
nested `datalog.parser.pull.PullSpec` values and schema identity maps.
The captured stdout/stderr reached **294,696,139 bytes** before the lane
terminated its own CLI process (PID 16808); the lane did not stop the shared cluster.

This output is unreadable and delays observing the actual refusal. The
operator error face must report the diagnostic cause and bounded evidence
through the existing value renderer. Reproduce with a refused development
adoption and assert that the CLI terminates with a readable refusal; do not
add a second clipping mechanism. The underlying acquisition refusal is a
separate fact and was not diagnosed by this observation.

Evidence is recorded with
[the test-fast landing note](../../prds/context-generation/research/test-fast-landing-2026-09-08.md).
The oversized scratch log is disposable; its measured size is retained here.

## 2026-09-08 typed adoption refusal repair

Acquisition now retains row identity and cause without embedding the
exception's execution projection. The boot refusal helper also excludes
the projection from its offense. The actual operator transport owner,
`script/seon/fresh_operator.clj`, reports typed boot refusals directly rather
than printing their entire prepl event history and submitted source form.
No additional presentation clipping was introduced.

The canonical acquisition plus diagnostic gate passed 3 tests / 19
assertions. A 99,000-byte input projection yields a 1,380-byte row
diagnostic; the real-prepl operator regression measures 159 bytes. A live
source-changed refusal after acquisition measured 151 terminal bytes.
These prove the typed adoption-refusal path; arbitrary untyped prepl
exceptions still use the general transport diagnostic.
See [the adoption landing note](../../prds/context-generation/research/adoption-rows-landing-2026-09-08.md).
