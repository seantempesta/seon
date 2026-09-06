---
type: research
status: complete
tags: [research, plan, data-model, datahike]
---

# Plan component schema

## Finding

An agent already is the natural plan root. A separate plan entity would carry
no independent identity, lifecycle, ownership, or attributes: it would exist
only to connect the agent to items. The smallest intuitive model is therefore
an agent-owned component tree of steps plus one ordinary current-step ref.

The current model stores every item-to-agent backlink and every child-to-parent
backlink (`resources/seon/schemas/my.plan.item.edn:15-29`). Reads find an
agent's items through `:my.plan.item/agent`, and readiness recursively walks
`:my.plan.item/parent` (`src/my/plan.clj:24-57,304-315,650-680`). The agent also
stores `:my.plan/anchor`, whose schema description says it is the current
position (`resources/seon/schemas/my.plan.edn:1-5,49-58`). These are two reverse
ownership links plus an unclear name for one ordinary pointer.

Datahike component refs already encode ownership and recursive retraction.
Retracting a parent discovers its component-valued outgoing datoms and emits
`retractEntity` for the children
(`reference-code/datahike/src/datahike/db/transaction.cljc:830-839,997-1014`).
Cardinality-many values are sets, so authored order requires an explicit
position attribute; vector order cannot be inferred from the ref collection.

## Proposed stored schema

Keep the established item content and state attributes. Replace ownership
backlinks with forward component refs and introduce clear position and current
step names:

```clojure
;; On the existing agent entity; there is no plan-root entity.
[:seon.cluster.agent/id :seon.cluster.agent/id]
[:my.plan/steps
 {:optional true}
 [:set {:seon.db/component true} :seon.db/ref]]
[:my.plan/current-step {:optional true} :seon.db/ref]

;; One step entity.
[:map {:seon.db/attributes true}
 [:my.plan.item/id :my.plan.item/id]
 [:my.plan.item/position [:int {:min 0}]]
 [:my.plan.item/title :my.plan.item/title]
 [:my.plan.item/description {:optional true} :my.plan.item/description]
 [:my.plan.item/expected-result {:optional true}
  :my.plan.item/expected-result]
 [:my.plan.item/completed-at {:optional true} :my.plan.item/completed-at]
 [:my.plan.item/needs {:optional true} [:set :seon.db/ref]]
 [:my.plan.item/subjects {:optional true} [:set :seon.db/ref]]
 [:my.plan.item/steps
  {:optional true}
  [:set {:seon.db/component true} :seon.db/ref]]]
```

Names shown for new attributes are concrete proposals. `title`, `description`,
`expected-result`, `completed-at`, and `needs` retain their present meanings.
Absence of `completed-at` remains open work. `needs` remains a cross-tree ref,
not a component: dependency does not imply ownership. Sibling positions must
be unique within their parent; the transaction compiler validates that and
sorts by `[position id]` for a total stable order.

`current-step` must point to an open step reachable through this agent's
component tree. It is an ordinary ref because currentness neither owns nor
copies the step. Completing that step retracts only the current-step datom, as
today's completion clears `anchor` (`src/my/plan.clj:191-216`). The old
`:my.plan/anchor` key should not silently acquire the clearer new semantics;
the new key makes the relationship explicit and the old key is retired in the
same migration.

There is no `item/agent` or `item/parent`. Ownership derives by recursively
following `agent/steps` and `item/steps`; a parent derives from the reverse
component edge. Adding either backlink would store the same relationship
twice. Direct item identity lookup remains available through
`:my.plan.item/id`.

## Subject drift

Current `:my.plan.item/about` is not a ref. It is an EDN vector of symbols and
qualified keywords (`resources/seon/schemas/my.plan.item.edn:30-44`). Writers
validate each token by resolving it to a schema, function, or namespace entity,
then discard that resolution and store the token
(`src/my/plan.clj:99-128,151-165`). `ready-subjects` repeats the same resolution
on every read (`src/my/plan.clj:704-729`). Prose or UI that calls `about` a
relationship is therefore stale.

The component migration should store the resolved refs once under a new
`:my.plan.item/subjects` ref attribute. This changes the relationship from
token text to entity refs, so changing `about` in place would violate key
stability. If authored subject order has a demonstrated meaning, use component
subject occurrences with their own position; current consumers only deduplicate
resolved subjects, so a cardinality-many ref set is the minimal honest shape.

## Input, pull, and write examples

The convenient authored input remains nested and uses labels only inside the
one compiler transaction:

```clojure
[{:my.plan/label :ship
  :my.plan.item/title "Ship context refresh"
  :my.plan.item/description "Show locked and refreshed results."
  :my.plan.item/expected-result "One automatic refresh, no duplicate eval."
  :my.plan/children
  [{:my.plan/label :store
    :my.plan.item/title "Persist source and result refs"}
   {:my.plan.item/title "Verify the UI"
    :my.plan/after [:store]}]}]
```

The compiler resolves labels, allocates stable item identities, assigns sibling
positions `0..n`, turns children into component refs, turns `after` into
`needs`, resolves subject tokens to `subjects`, and commits one basis-fenced
transaction. A representative transaction shape is:

```clojure
[{:db/id [:seon.cluster.agent/id "juniper"]
  :my.plan/steps ["step-ship"]
  :my.plan/current-step "step-store"}
 {:db/id "step-ship"
  :my.plan.item/id "ship-id"
  :my.plan.item/position 0
  :my.plan.item/title "Ship context refresh"
  :my.plan.item/description "Show locked and refreshed results."
  :my.plan.item/expected-result "One automatic refresh, no duplicate eval."
  :my.plan.item/steps ["step-store" "step-verify"]}
 {:db/id "step-store"
  :my.plan.item/id "store-id"
  :my.plan.item/position 0
  :my.plan.item/title "Persist source and result refs"}
 {:db/id "step-verify"
  :my.plan.item/id "verify-id"
  :my.plan.item/position 1
  :my.plan.item/title "Verify the UI"
  :my.plan.item/needs ["step-store"]}]
```

A bounded pull can root at the agent and recurse through the one children
attribute; callers sort each returned child collection by position and id:

```clojure
(seon.db/pull
 [{:my.plan/current-step [:my.plan.item/id]}
  {:my.plan/steps
   [:my.plan.item/id :my.plan.item/position :my.plan.item/title
    :my.plan.item/description :my.plan.item/expected-result
    :my.plan.item/completed-at
    {:my.plan.item/needs [:my.plan.item/id]}
    {:my.plan.item/subjects [:db/id]}
    {:my.plan.item/steps 8}]}]
 [:seon.cluster.agent/id "juniper"])
```

The numeric recursion bound is part of the read budget; unbounded recursive
pull is unnecessary. Ready and blocked remain derived Datalog values. Their
rules change from child-to-parent facts to forward component facts:
`[?parent :my.plan.item/steps ?child]`, with the root arm
`[?agent :my.plan/steps ?item]`. No flattened all-items collection is stored.

## AI output example

The renderer should teach the stored shape and show the useful current work,
not expose compiler labels:

```text
Juniper's plan — current step: Persist source and result refs

1. Ship context refresh
   Show locked and refreshed results.
   Done when: One automatic refresh, no duplicate eval.

   1.1 Persist source and result refs — ready, current
   1.2 Verify the UI — blocked by "Persist source and result refs"

Update the current step:
(seon.db/transact!
 [{:db/id [:seon.cluster.agent/id "juniper"]
   :my.plan/current-step [:my.plan.item/id "verify-id"]}])
```

HTML and AI renderers consume the same pulled tree and derived readiness sets.
The existing renderer currently formats flat ready/blocked/completion sections
and identifies currentness through `anchor` (`src/my/plan.clj:838-881,
890-1047`). The migration replaces that flat ownership query in place; it does
not add a second plan reader or a stored ready/status field.

## Failure cases the writer must refuse

- duplicate sibling positions under one parent;
- a component step reachable from two parents or two agents;
- `current-step` outside the owning agent's tree or already completed;
- `needs` pointing outside the same agent's tree, to itself, or creating a
  dependency cycle;
- a children cycle;
- a subject ref that does not resolve to an existing entity;
- deleting a step still named by `current-step` or another step's `needs`
  without updating those refs in the same transaction.

These checks belong in the existing `plan!` transaction compiler and direct
writer function. Datahike owns component lifecycle and referential retractions;
the plan owner decides semantic reachability, cycles, and current-step validity
against the mid-transaction database.

## Root review and live experiment, 2026-09-06

The ownership/position proposal is retained. The separate label/after/children
input example above is not the recommended new default: use the actual stored
steps keys, native transaction tempids, and ordinary needs refs. Do not add a
second authored grammar when direct nested transaction data already expresses
it. The refusal list is a research proposal, not an approved policy: cross-agent
prerequisites can be meaningful; no new blanket same-agent restriction should
be introduced as part of this rendering refactor.

A live pull rooted at Juniper's agent identity returned its current item and
both authored items. A pure 4 ms formatting probe produced:

```text
1. Make my plan and messages useful context — current
Inspect the facts connected to my agent entity. Compare the AI and HTML renderings, then improve the functions with Sean.
Done when: Two clear blocks: the work I am doing and the new messages I should respond to.

2. Try the assembled context in a live agent turn
After reviewing the blocks, test whether the agent can find its data and update its plan.
```

This uses actual stored content, not invented fixture prose. The probe ordered
by entity id solely because the current model lacks authored sibling position;
that is not the intended order authority. The proposed position attribute fixes
that missing fact. Nested component ownership is not installed yet.

## Named request-map lookup ruling, 2026-09-06

Agent-facing lookup and render functions default through one named, spec'ed
request map. For `my.plan/plan`, the required request members are
`:seon.db/db` and `:seon.cluster.agent/id`. Agent source calls `(my.plan/plan
{})`; the shared SCI call-preparation hook fills absent required entries from
the calling turn's environment. An explicit `(my.plan/plan
{:seon.cluster.agent/id "other"})` keeps the caller's value and receives only
the absent database entry. This is the existing required-map-entry preparation
mechanism in `src/seon/call_preparation.clj:1070-1105`, not a function-local
lookup or another default path.

Positional conveniences remain appropriate only when their position is useful
to the operation. Database and current-agent custody are named world values,
so a two-positional lookup arity is not retained. The render source calls the
same map API with `{}`; pure terminal formatters continue to accept already
derived plan data.

This lookup correction does not install the component schema above. The writer
contract for replacing the current flat `add!` and label/after/children
`plan!` inputs remains separate unresolved work. Existing plan facts and writer
semantics remain unchanged by the request-map lookup slice.
