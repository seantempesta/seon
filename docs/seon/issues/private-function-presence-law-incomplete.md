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

## Acceptance

- Authored public function rows omit `:seon.fn/private?`; authored private rows
  carry exactly `true`.
- Default namespace entity-card AI and HTML renders omit private members.
- Explicit source/drill views continue to reach private function source.
- Recurring CLJS tests cover all three statements.
