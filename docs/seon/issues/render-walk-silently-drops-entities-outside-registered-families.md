---
type: issue
status: open
severity: blocker
tags: [issue, render, context, architecture]
---

# The render walk silently drops entities outside a registered entity family

## Problem

W1 claims the neighbourhood traversal elides loudly everywhere: "Every
active bound emits an error-valued elision node"
(`src/seon/render/walk.clj:493-496`). Two of the walk's narrowing
filters emit nothing at all, and both were introduced by the same
commit (`071ca1e50`) that replaced the unbound
`[?source ?attribute ?target]` reverse read.

1. `concrete-entity` (`src/seon/render/walk.clj:245-255`) pulls only
   the attributes declared inside a `:seon.db/entity` map schema that
   the entity matches. Every other attribute the entity actually
   carries is dropped with no marker.
2. `reverse-refs` (`src/seon/render/walk.clj:279-331`) enumerates only
   ref attributes declared inside such a map, and then additionally
   drops any source entity that does not carry that family's
   `identity-attribute` (`:307-311`). Neither drop emits a node.

Falsified live (`clojure -M:dev:test`, in-memory database via
`seon.test-support/with-database` with three synthetic installed
attributes; probe retained at `tmp/audit-0731/probe5.clj`):

```text
;; an entity carrying :audit/marker "m1" and a :audit/points-at ref
;; to the cluster, rendered at distance 1 through the ai floor
=== the unregistered entity itself ===
#:db{:id 4358}

=== cluster neighbourhood: does the audit/points-at reverse edge appear? ===
{:seon.cluster/instructions [...], :seon.cluster/name "audit", ...}
  (:seon.cluster/config) ...
  (:seon.cluster/instructions) ...
  ;; :audit/points-at is absent; no elision node anywhere in the tree
```

An entity that exists and carries data renders to the agent as an empty
shell, and a real inbound connection is invisible. The previous
`[?source ?attribute ?target]` read found both. This is the worst
failure class for a context mechanism: the agent cannot see the gap it
is reasoning around, which is exactly the rule `prose` states it
observes for a failed node (`:634-637`).

Exposure is not hypothetical. Agents register their own `my.*` schemas
at runtime and transact facts against them; nothing requires an
agent-authored attribute to sit inside a `:seon.db/entity` map. A
mechanical sweep of one populated database found five installed ref
attributes invisible to `reverse-refs`
(`:seon.cluster.agent/blocks`, `:seon.cluster.run/forms`,
`:seon.db/process`, `:seon.db/trigger`, `:seon.db/user`) and ten
installed non-ref attributes invisible to `concrete-entity`
(probe retained at `tmp/audit-0731/probe3.clj`). Three of those are
apparatus by design and `:seon.cluster.run/forms` is compensated by the
form's own reverse edge, so today's first-party loss is small — the
defect is that the loss is structurally silent, not that it is currently
large.

The seeded property that should have caught this cannot:
`p1-membership-is-complete-or-loudly-elided`
(`test/seon/render/walk_test.clj`) generates only registered-family
agents, so its generator never produces the failing class, and its
predicate `(or (empty? missing) (elision? result))` accepts ANY elision
node anywhere in the tree as proof for ANY omission.

Related, same owner: `asked-for-run-edges`
(`src/seon/render/walk.clj:398-414`) returns every run an agent ever
asked for with no per-attribute bound, while every other reverse group
is bounded by `:seon.config.eval.result/max-collection`. Overflow falls
through to the node budget, which elides the rest of the neighbourhood
rather than that group.

## Acceptance

Every narrowing in the walk is either provably lossless or emits a node
carrying `:seon.error/value`, and the proof is a property whose
generator can produce the failing case.

- `concrete-entity` either pulls the entity's complete attribute set
  (`d/pull` `'[*]`, with family matching still driving projection
  choice) or emits one elision node naming the attributes it withheld.
- `reverse-refs` either covers every installed ref attribute — the
  installed schema is already the mechanical source, `(:schema db)`,
  and needs no family map — or emits one elision node per uncovered
  attribute group. The identity-attribute filter at `:307-311` either
  goes away or becomes loud.
- `asked-for-run-edges` is bounded by the same collection dial and
  elides with a marker, or its unboundedness is justified in the
  docstring against a named bound elsewhere.
- One property per direction, with generators that produce entities
  outside every registered family and inbound refs on undeclared
  attributes: rendered content plus emitted elisions must account for
  every attribute and every inbound ref the database holds. P1 is
  rewritten so the marker must name the omission it covers, not merely
  coexist with it.

## Evidence

`docs/prds/sci-execution-runtime/research/context-wave-audit-2026-07-31.md`
