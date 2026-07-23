---
type: issue
status: active
tags: [issue, runtime, rendering]
---

# Private-function presence law is incomplete outside core indexing

## Evidence

R39 makes `:seon.fn/private? true` presence mean private and absence mean
public. The first-party boot index now follows that law, but two separate
owners still preserve the prior shape:

- `seon.analyzer-info/var-projection` and the authored eval tee always carry
  and store a boolean, so public authored rows retain
  `:seon.fn/private? false`.
- `seon.render.handlers.ns` renders every pulled namespace member, including
  private rows, while the agent-context namespace/menu/canvas projections
  already filter on privacy.

These are not P1b build-indexing paths. Editing them here would widen the
artifact-inventory unit into the authored analyzer and generic entity-card
renderer owners.

## Fresh-boot blocker — 2026-07-23

Commit `a332ecb5f` landed first-party private function rows while the paged
initialization lane was waiting on the shared `src/seon/client.cljs` owner.
After paged initialization completed and acquire succeeded on the fresh
`initpage` cluster, SCI reconstruction failed on private
`seon.db.protocol/ordinary-wire-value?`:

```text
Unable to resolve symbol: seon.db.protocol/ordinary-wire-value?
```

The reproduced operator evidence is
`tmp/orchestrator/initpage-up.log`; the pod log is
`logs/operator/pod/23bb55c9-8dfd-4e0c-b1b2-2a739d4e2ca4.log`. The failure is
after the former 4 MiB initialization request and schema-admission boundaries:
all initialization pages were accepted, the completion marker allowed acquire,
and startup advanced into SCI program reconstruction.

The P1b private-corpus owner must make a private row available to same-namespace
SCI callers without making the function public or removing its source. The
paging lane must not rename the predicate, weaken its schema, or filter the row
to recover readiness.

## Acceptance

- Authored public function rows omit `:seon.fn/private?`; authored private rows
  carry exactly `true`.
- Default namespace entity-card AI and HTML renders omit private members.
- Explicit source/drill views continue to reach private function source.
- A fresh reset reaches pod readiness with named private predicates resolvable
  to same-namespace callers.
- Recurring CLJS tests cover all three statements.
