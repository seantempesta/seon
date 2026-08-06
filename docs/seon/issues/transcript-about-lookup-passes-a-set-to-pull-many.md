---
type: issue
status: open
severity: blocker
tags: [issue, render, database, errors, live-drive]
---

# Pass ordered entity ids to transcript `pull-many`

## Problem

The transcript renderer constructs `about-eids` as a set and passes that set
to `seon.db/pull-many`, whose public contract rejects it. Rendering therefore
commits core faults; those faults create messages that cause another render
and repeat the same failure until recurrence suppression intervenes.

## Evidence

`seon.render.transcript/about-identities` builds:

```clojure
(into #{} (keep #(get-in % [:seon.cluster.message/about :db/id])) messages)
```

and then calls `(db/pull-many db selector about-eids)` without converting the
ids to the dependency's accepted ordered input.

The 2026-08-06 exact root prompt contains five identical outside messages:

```text
A renderer in my.agents.root failed. seon.db/pull-many violated its contract
(invalid-input) ... {:value #{23654 23659 23639 23649}, :message "invalid type"}
```

The corresponding 200 root HTML page contains the same pull-many contract
error fourteen times. This is user-visible error amplification, not only a
failed render call.

## Owner

`seon.render.transcript/about-identities`, preserving one transcript path and
the public `seon.db/pull-many` contract.

## Acceptance

- Transcript about-identity resolution passes one deterministic accepted
  collection of entity ids to `pull-many`.
- A message set containing several `:seon.cluster.message/about` refs renders
  once without a core fault.
- Re-rendering after the result commits does not create any new error message
  or transaction.
