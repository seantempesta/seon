---
type: issue
status: closed
severity: blocker
tags: [issue, database, schema, pod]
---

# Fresh database initialization ignored entity schema attributes

## Evidence

The first autonomous experimental cluster reached the existing JVM writer but
failed before readiness. Its fresh database contained the compiled schema
program, yet `:seon.config/id` was not installed as a unique attribute before
the pod acquired the config singleton by lookup ref. The pod exited with no
local fallback and the default cluster remained healthy.

The initialization owner compiled only the manually selected
`:seon.db/attributes` vector. It already had a pure derivation for attributes
referenced by canonical `{:seon.db/entity true}` schema forms, but that result
was not part of initialization admission.

## Resolution

Fresh and populated initialization now unions the explicit dataless attributes
with attributes referenced by the compiled entity schema forms, then compiles
one declaration set before program and initial-data facts transact. There is
no cluster-specific seed and no second schema inventory.

Focused writer initialization proof passes 4 tests and 20 assertions. The new
regression initializes with an empty explicit attribute vector, installs an
identity attribute from an entity schema form, and resolves the initial entity
through its lookup ref.

## Acceptance

- A fresh database installs attributes referenced by canonical entity schemas.
- Initial data may use those identity attributes in the same atomic program
  transaction.
- Explicit dataless attributes remain supported.
- Repeated converged initialization remains write-free.
