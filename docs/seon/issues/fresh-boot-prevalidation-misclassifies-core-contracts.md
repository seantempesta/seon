---
type: issue
status: active
severity: blocker
tags: [issue, schema, runtime, database]
---

# Fresh boot prevalidation misclassifies core contracts

## Problem

A fresh pod boot validates the compiled core program before the writer asserts
it. That validation passed synthetic `[identity form]` pairs through
`seon.runtime.admission/committed-projection`, whose missing-provenance rule
correctly classifies such rows as agent-authored. The strict contract walker
then rejects documented core opaque-boundary schemas such as `:seon.db/db`.

This is a caller mismatch: desired core program data is not a committed-row
acquisition result and has no asserting transaction yet.

## Evidence

- `src/seon/client.cljs` builds the desired boot program before the
  `ensure-database` request.
- `seon.schema/projection-from-rows` deliberately defaults two-field rows to
  agent-authored.
- Fresh-cluster pod boot failed on the legal `:seon.db/db` `:any` store-id
  element before readiness.
- The writer's actual initialization transaction is stamped with root/boot
  provenance.

## Acceptance

- Desired core program prevalidation uses the core projection compiler without
  fabricating asserting-transaction provenance.
- Missing provenance at the committed-row acquisition boundary remains
  fail-closed as agent-authored.
- A real fresh writer initialization projects asserted core rows as
  core-admitted.
- A fresh isolated cluster reaches pod readiness.
