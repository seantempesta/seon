---
type: issue
status: resolved
severity: blocker
created: 2026-09-17
resolved: 2026-09-17
tags: [test, database, read-evidence, recording]
---

# Test recording misreports a refused provenance read as an identity collision

`seon.test.runner/record-tx` pulled the run's provenance, then treated any
truthy result as an existing run. A flat `:seon.db/invalid-read` map therefore
became `:seon.test.run/immutable`, masking the actual database failure.

Batch 116 A recorded that immutable refusal after 96 platform tests
(`tmp/orchestrator/gate-results/batch-116.log:1449`, retained execution root
`tmp/test-runs/run.jHOFSg`). Recording executes in the live store holder,
separately from the execution snapshot. Contemporaneous probes
`tmp/orchestrator/config-loss-probe-4.edn` and `-5.edn` establish the
[refused-read incident](the-default-clusters-effective-configuration-lost-every-required-fact.md):
read-evidence capture attempted `assoc` on a sequence and returned a typed
read refusal. The database repair is `6a0f8a08a`.

The gate did not retain its compared values or alleged colliding ID, so its
exact historical input cannot be recovered. The coordinator mints a fresh
event ID through `seon.id/id`; neither the program digest nor the published
base ID is reused. There is no demonstrated ID-derivation collision.

The recorder now preserves the pull's refusal through the existing writer
boundary. A real provenance conflict reports stored and submitted values
and the transaction database's basis. The canonical regression
`seon.test-test/recording-distinguishes-run-replay-from-a-new-event` verifies
fresh runs, replay, immutable conflict, and unchanged propagation of a real
read refusal injected at the run lookup, with atomicity checks.

Verification counts, exact commands, and the cold-gate boundary are in the
[Stage 2 landing note](../../prds/steward-platform/research/test-system-stage2-2026-09-17.md).
