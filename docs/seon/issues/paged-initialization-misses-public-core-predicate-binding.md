---
type: issue
status: active
tags: [issue, runtime, schema]
---

# Paged initialization misses a public core-predicate binding

## Evidence

After paged initialization accepted every page and the completion marker
allowed acquisition, fresh pod startup failed during SCI program
reconstruction:

```text
Unable to resolve symbol: seon.db.protocol/ordinary-wire-value?
```

The reproduced operator evidence is
`tmp/orchestrator/initpage-up.log`; the pod log is
`logs/operator/pod/3086e8fc-d146-4138-b96c-4d8d83405eea.log`.

This is not an R39 private-function failure.
`seon.db.protocol/ordinary-wire-value?` is a public `defn` and is registered as
a core predicate in `seon.db.protocol`. The build inventory also classifies it
as a public export. The failure therefore belongs to the protected schema
projection or SCI predicate-binding acquisition path exposed after paging, not
to private corpus-row publication.

## Expected owner

The schema acquisition path must make every registered public core predicate
binding available before SCI reconstructs schemas that name it. Do not add a
second predicate registry, rename the predicate, or weaken its schema.

## Acceptance

- A fresh paged reset reaches pod readiness.
- `seon.db.protocol/ordinary-wire-value?` resolves while its registered schema
  is reconstructed.
- A recurring reset-boundary proof covers the real initialization and
  acquisition path.
