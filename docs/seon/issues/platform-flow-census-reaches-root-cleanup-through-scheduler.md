---
type: issue
status: open
severity: blocker
created: 2026-09-16
tags: [issue, testing, flow]
---

# Platform admission refuses the Flow census through scheduler reach

The preparation-cost slice's `bin/test --paths src/seon/test/runner.clj
src/seon/test/arm.clj test/seon/test_preparation_test.clj --platform` at snapshot
HEAD `1304a404b32b407fb74caaadc4c3d71faaaffbb2` selected 96 platform tests, then
refused before any test began. Kind: `:seon.test.runner/destructive-platform-test`.
Exact reported path:

```text
seon.flow-configuration-test/every-built-graph-proc-declares-a-specific-workload
seon.cluster.agent/graph-definition
seon.schedule/schedule-step
seon.schedule/fire-due!
seon.operator/reap-dead-roots!
seon.operator/cleanup-root-under-lock!
```

The census namespace declares platform membership. The existing selector
follows both calls and references, and rejects any platform test reaching
the declared destructive owners. This observation is an admission refusal,
not evidence that the census executed cleanup or deleted anything.

All four source/test files named by that path are byte-identical between
the preparation baseline `5ae8dcdafed230128aa20bc2ccee73a6faf1d8cf` and the
platform HEAD (verified with `git diff` restricted to those files). The
preparation slice changes neither these files nor the selection checker.
The older resolved platform-restoration note concerns different failures;
this is the current refusal evidence, not a reopening of its arity defect.

Evidence and exact gate phases:
[preparation landing note](../../prds/steward-platform/research/test-preparation-costs-2026-09-16.md),
`tmp/test-preparation-costs/platform.log`, and
`tmp/test-preparation-costs/platform-refusal.edn`.

Owner: the platform declaration and test-selection policy. Acceptance: reconcile
the declaration with the existing destructive-reach rule, then run the complete
platform tier without weakening isolation or claiming an unexecuted tier green.
The preparation assignment expressly forbids changing selection or tiers, so
this issue is outside that slice.

## B1b observation, 2026-09-21

The same platform-declaration/destructive-reach admission class now appears on
source-publication fixtures. Canonical `seon.test/run` of
`seon.test.runner-test/the-canonical-platform-tier-preserves-file-local-uncertainty`
recorded run `807cd921066f`: 6 pass, 0 fail, 1 error, basis 536871056,
completion 536871058. The guard names ten tests in `seon.cluster.source-test`,
`seon.cluster.source-lineage-test`, and `seon.cluster.source-evidence-test` reaching
`seon.test-support/populate-published-operator-root!`. The complete named refusal
is retained in [B1b raw results](../../prds/agent-platform/landing/b1b-results-2026-09-21.txt).

A separate direct call positively finds B1b `cold-start` in the declared platform
set and admits it, while refusing B1b `start-during-reset` with its exact path
through `with-published-root!` to that destructive helper. The cold helper does
not populate a published root. This is not a full platform green, nor a verified
claim that the ten source tests were already broken before B1b. Their declaration
and selection policy remain the orchestrator's integration decision; B1b did not
change their metadata or weaken the guard.

## Root checkpoint after B1b replacement

The actual `bin/test --platform` invocation selected92 eligible members and exited1
before execution on six source-publication tests. The earlier ten-name probe had
a different long-test eligibility scope. Exact names and live-runtime verification
are in [the integration note](../../prds/agent-platform/landing/b1b-integration-2026-09-21.md).
The guard remains intact; no platform green is claimed.
