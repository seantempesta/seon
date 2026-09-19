---
type: issue
status: open
severity: friction
created: 2026-09-19
tags: [testing, tooling, schema, error]
---

# Schema edit admission cannot load after error predicate retirement

A1's prospective addition of optional `:seon.test/changed` to
`resources/seon/schemas/seon.test.selection.edn` was refused by the edit hook
at HEAD `bc8152438`, before the file changed:

```text
Schema population refused :seon.boot/cluster-name (unregistered-predicate).
Predicate seon.cluster/cluster-name? is owned by namespace seon.cluster;
load or reload that namespace before schema admission.
```

The hook launches its own `seon.schema.admission` process
(`bin/seon-hook`, `run-schema-admission`); reloading the development JVM does
not establish that process's predicates. The identical additive schema edit
passes in A1's isolated checkout before the error-family cut. The error
owner's landing note already records the current namespace-load refusal at
`src/seon/fn.clj:1387`, where `error/error?` was removed. This note records
the additional editing-surface symptom; it does not claim that the generic
predicate should be restored.

Verification owed after the external error-consumer sweep: admit this schema
edit through the normal hook and run the canonical fixture. A1 neither edits
the held error owners nor reloads the owner's default cluster.
