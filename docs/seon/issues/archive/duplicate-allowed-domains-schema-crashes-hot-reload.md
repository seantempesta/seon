---
type: issue
status: resolved
severity: blocker
tags: [issue, schema, pod]
---

# Duplicate allowed-domain schemas crash hot reload

## Problem

`:seon.agent.web/allowed-domains` is registered twice with incompatible
database shapes. `seon.config` registers a mixed EDN value whose bridge derives
cardinality one, while `seon.agent.web` registers a vector whose bridge derives
cardinality many. Initial namespace load leaves the latter installed; reloading
`seon.config` can replace the in-process canonical form with the former and
correctly fail database publication.

## Evidence

During a current-source hot reload, the pod recorded a core fault and exited:

```text
A canonical schema form conflicts with installed schema.
:seon.agent.web/allowed-domains expected cardinality one, actual cardinality many

```

The database remained readable. The watcher had published current client and
execution builds before the schema reconciliation failed, so this was a
deterministic registry/load-order conflict rather than database corruption.

## Owner

`seon.config` owns the early-loaded database configuration schema used by the
manifest compiler. It must register the one cardinality-many vector shape.
`seon.agent.web` references that registered attribute and must not register a
second copy.

## Acceptance

- One source registration defines `:seon.agent.web/allowed-domains` as a vector
  of strings and derives the installed cardinality-many schema.
- Reloading `seon.config` and `seon.agent.web` in either order cannot change the
  canonical schema form.
- Focused config, web, and client-publication tests pass.
- A supervised current-source restart and a later config hot reload keep the
  watcher, writer, and pod ready without an incompatible-schema core fault.

## Resolution

Commit `e205dc9e` makes `seon.config` register the canonical
cardinality-many string schema and proves that an absent allowlist reads as
`[]`. Commit `66998fa9` removes the duplicate registration from
`seon.agent.web`. The focused config/context/client gate passes 70 tests with
349 assertions, and the focused web gate passes 13 tests with 45 assertions.

A supervised restart reached watcher, writer, and pod readiness. Touching both
owning namespaces then completed a live config/web hot reload without a schema
publication fault and kept the pod ready. After a concurrent analyzer source
commit, a second canonical restart converged all three processes to current
artifacts and reported a clean, fully ready cluster. The database was never
reset or repaired because its installed cardinality-many schema was already
correct; only the duplicate in-process schema ownership was defective.
