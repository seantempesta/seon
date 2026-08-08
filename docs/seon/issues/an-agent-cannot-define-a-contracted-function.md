---
type: issue
status: open
severity: blocker
tags: [issue, runtime, agent, schema, live-drive]
---

# Let an agent define a contracted function

## Problem

An agent cannot `defn`. The moment its definition carries a `:malli/schema` —
which durable defns REQUIRE — `seon.program/declaration-row` violates its own
output contract and the form errors, so the very next form that calls the
function fails as an unresolved symbol.

This is the first thing a healthy agent tries to do and it is the first thing
that fails. It was invisible while the agent's context was empty; it surfaced
on the first turn that had a real prompt.

## Evidence

Cluster `default` (pid 79576), 2026-08-08, run
`d95c5c42-7307-4484-9a32-86e30bf0e29b`, triggered by human message
`inbound-536871139-0`. Four forms, four receipts, two errors, and the two
errors are this defect and its consequence:

| Ordinal | Form (head) | Outcome |
|---:|---|---|
| 0 | `(seon.db/q '[:find ?a ?v :in $ ?e :where [?e ?a ?v]] 25564)` | read the message that woke it — fine |
| 1 | `(defn live-drive-marker …{:malli/schema [:=> [:cat] [:map …]]}…)` | **`seon.program/declaration-row violated its contract (invalid-output)`** |
| 2 | `(live-drive-marker)` | `Unable to resolve symbol: live-drive-marker` |
| 3 | `(my.run/complete "…")` | completed |

The complaint, verbatim:

```text
seon.program/declaration-row violated its contract (invalid-output):
{:seon.ns/name [{:value nil, :message "missing required key"}],
 :seon.schema.admission/source [{:value nil, :message "missing required key"}],
 :seon.schema/key [{:value nil, :message "missing …
```

Three required keys are absent from the row the function builds:
`:seon.ns/name`, `:seon.schema.admission/source`, and `:seon.schema/key`.

Five occurrences of this signature exist on the cluster; two of them predate
any agent turn, so the boot path hits it as well (the observer lane recorded
two at boot, alongside the separate bootstrap-placeholder failures).

Two of the five render as `… 1 more subtree; requery refused: no stable
identity was supplied at path [] offset 0 with
:seon.render.profile/unspecified`, so the reader is told a subtree exists and
then refused it — a second, separate face defect on the same error.

## Owner

`seon.program/declaration-row` and whatever assembles its input on the agent
`defn` path.

## Acceptance

- An agent defines a function with a complete `:malli/schema` and the form
  settles a receipt with the Var, not a contract violation.
- The next form in the same run resolves and calls it.
- Zero `declaration-row` contract violations on a fresh boot.
- One class regression drives an agent `defn` with a `:malli/schema` through a
  real turn and asserts the definition is callable in the following form.

## Note for whoever fixes this

The 2026-08-08 re-drive is the reproduction: submit a message asking root to
define a schema-carrying function and call it. Nothing else is needed — this
fires on the first attempt.
