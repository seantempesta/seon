---
type: issue
status: open
severity: friction
tags: [issue, test, flow, wave/context-fixes]
---

# Juniper submission repeatedly refuses an existing turn

Observed 2026-09-09 on the transact-feedback scratch cluster, initial
05510a6d4 snapshot plus transaction validation changes. After installing
the canonical Juniper fixture with no-provider enabled, submitting a bad
transact through seon.context-blocks-fixture/submit! did not return within
the MCP's 60,000 ms bound. Its scratch log contained 183,246 lines naming
run-exists before the owning operator downed PID 51110. Disarming answered
but did not settle the submission. No provider request was involved.

The fixture's submit! (test/seon/context_blocks_fixture.clj:92) retries
virtual-turn! when it returns run-exists. When the referenced turn is already
closed, that branch skips its event wait and immediately retries. This is a
source observation, not proof of why the submitted turn identity remained
unchanged. The turn owner and fixture need one bounded reproduction that
records the requested turn identity, existing turn facts, and terminal result.

The transaction slice did not change these concurrently owned paths. The
same bad write through the actual SCI evaluation point returned immediately
with invalid-write and left the amount unchanged. Exact evaluation bytes and
the successful fresh-JVM repeat are in the
[landing note](../../prds/context-generation/research/transact-feedback-landing-2026-09-09.md).
