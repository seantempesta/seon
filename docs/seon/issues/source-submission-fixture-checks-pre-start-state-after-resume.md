---
type: issue
status: resolved
severity: friction
created: 2026-09-17
tags: [issue, test, flow, agent]
---

# Source-submission fixture checks pre-start state after resume

The S3 four-namespace fast snapshot observed
`system-source-submission-uses-the-ordinary-durable-run` failing its assertion
that `source-agent` was unarmed. The assertion ran after `flow/resume`.

`seon.cluster.agent/armer-step` explicitly offers its own arm-prime on resume
and derives agents already present in the database (`src/seon/cluster/agent.clj:869`).
Therefore the assertion races correct production behavior. The fixture now
asserts the pre-start state before starting/resuming its armer graph. Its
source submission, real SCI evaluations, durable outcomes, and bounded waits
are unchanged. The regression passed in the final S3 four-namespace fast run
(161 tests, 1052 assertions; unrelated remaining failures recorded below).

Evidence and tally belong to the
[S3 landing note](../../prds/steward-platform/research/acquisition-by-provenance-s3-2026-09-16.md).
