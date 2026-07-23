---
type: issue
status: open
tags: [issue, config, database]
severity: blocker
---

# Config schema alias blocks fresh cluster open

## Evidence

On 2026-07-23, the isolated `u5web` operator reached writer, host, and pod
reconciliation after the `UnixPath` fix. The pod then failed opening a fresh
database because the writer rejected this registered config schema:

```text
A canonical schema form cannot be stored by Datahike.
{:seon.db.writer/attribute :seon.config.render-context/sha-256,
 :seon.db.writer/schema-form :seon.content-hash/digest}
```

Selecting a minimal manifest containing only the nine U5 web-render facts
produced the same failure, proving it occurs during complete schema
installation rather than from declared render-context values. Evidence is in
`tmp/orchestrator/u5-gate-live-up.log` and the referenced pod log.

U5 did not weaken schema installation, modify the render-context owner, or
launch internal processes outside the supervisor.

## Acceptance

- The canonical schema projection resolves the digest alias to a
  Datahike-storable scalar form.
- A fresh named cluster opens with both `config/system.edn` and a minimal
  manifest.
- `bin/seon up` proceeds through pod readiness to the dependent JVM web-render
  member.
