---
type: issue
status: open
severity: friction
created: 2026-09-17
tags: [issue, test-fixture, instrumentation, documentation]
---

# Documentation fixture omits interpreted contract configuration

Batch 122 B reports two failures in
`seon.sci.documentation-test/a-contract-mistake-carries-the-same-documentation-as-doc`.
The error identifies `seon.cluster.message/send!`; the expected documentation
is for `my.message/send`. The request's panic setting does not configure the
cluster database from which the interpreted function's arming policy derives.
Without that cluster configuration the interpreted wrapper stays unarmed,
and the JVM owner's contract catches the malformed recipient instead.

The proposed correction seeds the cluster through `seed-cluster!` with panic
mode before context acquisition, carries explicit custody, and asserts the
outer function identity as well as exact documentation equality. The public
message docstring owns the subject/assignment/sender grammar once.

Verification remains open: the path-isolated fast launcher exits 64 before
starting any JVM because HEAD lacks a source-matching published graph.
The orchestrator must prepare the HEAD baseline before this lane can rerun
its focused namespace. No test pass or production arming change is claimed.

Evidence, exact command and source grounding are in
[the landing note](../../prds/steward-platform/research/message-wake-model-2026-09-17.md#batch-122-b--message-documentation-2026-09-17).
Acceptance: the canonical armed SCI regression refuses at `my.message/send`,
carries exactly the same documentation as `doc`, includes the same example
in shown text, and writes no message.
