---
type: issue
status: resolved
severity: cleanup
tags: [issue, agent, schema]
---

# Make the preflight repair test declare its schema dependencies

## Resolution

The ambient-schema fixture no longer exists. Commit `97654066` deleted
`test/seon/eval/preflight_repair_test.cljs` while unifying operation-owned
runtime configuration. Restoring that test would restore its retired fresh
embedded-database harness rather than close a dependency in current code.

Maintained preflight behavior is isolated in
`test/seon/eval/repair_batch_test.cljs`: it initializes the real bootstrap
compile state and exercises the pure preflight eligibility boundary without a
fresh database or process-global schema registration side effects. The stale
fixture issue is therefore closed by deletion and replacement coverage.

## Original problem

The former focused `seon.eval.preflight-repair-test` selector relied on schema
registrations contributed by unrelated test namespaces. Its fresh database
setup could fail with unregistered provenance or `:seon.repair/*` attributes
before admission or repair behavior was exercised.
