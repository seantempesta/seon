---
type: issue
status: open
severity: cleanup
tags: [issue, agent, schema]
---

# Make the preflight repair test declare its schema dependencies

## Problem

The focused `seon.eval.preflight-repair-test` selector relies on schema
registrations contributed by unrelated test namespaces in a complete bundle.
Its fresh database setup can therefore fail before admission or eval behavior
is exercised.

## Evidence

`tmp/test-cljs-20260715-014221-52362.log` starts
`seon.eval.preflight-repair-test` before the admission namespace and reports
`:seon.db/genesis-schema-unregistered` for the core provenance identities.
Later repair datoms also report unregistered `:seon.repair/*` attributes. The
admission/current-namespace gate is independently green in
`tmp/test-cljs-20260715-014326-55059.log`, so this is a focused-fixture closure
defect rather than an attachment-detach regression.

## Owner

`test/seon/eval/preflight_repair_test.cljs` and the exact schema-owning
namespaces its fresh connection requires. The focused runner should not widen
to unrelated tests to supply process-global registration side effects.

## Acceptance

The test namespace explicitly loads or seeds every canonical schema owner it
uses, then its standalone focused selector opens a fresh database and passes
without depending on another test namespace's module-load order.
