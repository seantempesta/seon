---
type: issue
status: open
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
