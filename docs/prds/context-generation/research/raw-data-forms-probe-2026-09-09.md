# Raw transaction-data forms against the juniper plan — probe, 2026-09-09

Live probe in cluster `default` through `mcp__seon__eval_clj` (`jvm` mode).
Every write used `datahike.api/with` on a database value obtained from
`(seon.operator/connection "default")`; **nothing was committed**. Semantics
are cited against `reference-code/datahike/src/datahike/db/transaction.cljc`.

Fixture at probe time (`datahike.api/pull`):

- agent `[:seon.agent/id "juniper"]` = eid **35984**
- `:seon.agent/plan` → plan entity **36396** (no identity attribute of its own),
  `:my.plan/objective "Which customer has the largest total? ..."`,
  six `:my.plan/steps` (36397–36402: `juniper/query`, `juniper/transact`,
  `juniper/aggregate`, `juniper/reply`, `juniper/requery`, `juniper/done`),
  `:my.plan/current-step` → `juniper/query`.
- `:my.plan.item/id` is a unique identity (`resources/seon/schemas/my.plan.item.edn:4`,
  `:seon.db/identity true`); `:my.plan/steps` is a cardinality-many component
  ref (`resources/seon/schemas/my.plan.edn`, `:steps` `[:set {:seon.db/component true} :seon.db/ref]`).

## The mechanism, from source

- `explode` (`transaction.cljc:738`) turns each map entry into `[:db/add eid a v]`;
  a map value on a ref attribute becomes a NESTED ENTITY carrying the reverse ref
  (`:757-758`: `(assoc v (dbu/reverse-ref a-ident) eid)`). A nested map with no
  unique-identity attribute therefore gets a FRESH tempid.
- `upsert-eid` (`:640`) resolves an entity map to an existing eid only through
  `:db.unique/identity` attributes present in the map.
- `transact-add` (`:785`) sets `upsert? (not (dbu/multival? db a))` (`:795`).
  For a cardinality-ONE attribute, `transact-report` (`:584`) calls
  `with-datom-upsert` (`:538`), which REPLACES the old datom in place in the
  indexes (`di/-upsert ... old-datom`, `:562-571`). No retraction datom is put
  into `:tx-data`, and `retract-components` (`:830`) is NOT called.
- `retract-components` runs only from `:db.fn/retractAttribute` (`:1072-1076`)
  and `retract-entity` (`:997-1014`). Plain `:db/retract` returns `[report []]`
  (`:1059-1071`) — **no component cascade**.

## 1. Nested map on the cardinality-one component ref REPLACES the plan

```clojure
(datahike.api/with db
  [{:seon.agent/id "juniper"
    :seon.agent/plan {:my.plan/steps [{:my.plan.item/id "orders/probe"
                                       :my.plan.item/title "Probe step"
                                       :my.plan.item/position 9}]}}])
```

`:tx-data`

```clojure
[[536871103 :db/txInstant #inst"2026-09-09T20:58:37Z" true]
 [36572 :my.plan.item/id "orders/probe" true]
 [36572 :my.plan.item/title "Probe step" true]
 [36572 :my.plan.item/position 9 true]
 [36571 :my.plan/steps 36572 true]
 [35984 :seon.agent/plan 36571 true]]
```

After: the agent's plan is the NEW entity 36571 with exactly one step
(`{:seon.agent/plan {:db/id 36571 :my.plan/steps [{:my.plan.item/id "orders/probe"}]}}`),
while pulling 36396 directly still returns the objective and all six original
steps. **Replaced, old plan orphaned, silently** — note there is not even a
`false` datom in `:tx-data` for the displaced `[35984 :seon.agent/plan 36396]`,
because the card-one path is an in-index upsert (`:538-583`, `:584-624`).
Confirms the caller's probe.

## 2. `{:db/id <plan eid> :my.plan/steps [...]}` ADDS one step

```clojure
(datahike.api/with db
  [{:db/id 36396
    :my.plan/steps [{:my.plan.item/id "orders/probe"
                     :my.plan.item/title "Probe step"
                     :my.plan.item/position 9}]}])
```

`:tx-data`

```clojure
[[536871107 :db/txInstant #inst"2026-09-09T20:59:12Z" true]
 [36577 :my.plan.item/id "orders/probe" true]
 [36577 :my.plan.item/title "Probe step" true]
 [36577 :my.plan.item/position 9 true]
 [36396 :my.plan/steps 36577 true]]
```

`:my.plan/steps` is cardinality-many, so `upsert?` is false (`:795`) and the
datom is ADDED: the plan now has **seven** steps, and
`(datahike.api/datoms d :eavt 35984 :seon.agent/plan)` is unchanged
(`[[35984 :seon.agent/plan 36396]]`). This IS the correct form for adding one
step to the existing plan.

### Why the caller's probe pulled 0 steps

Not a component retraction and not the `with` result — a PULL SELECTOR error.
Measured on the same `:db-after`:

```clojure
(datahike.api/pull d [:my.plan/steps] [:seon.agent/id "juniper"])   ;=> nil
;; :my.plan/steps lives on the PLAN entity, not on the agent.
(count (get-in (datahike.api/pull d [{:seon.agent/plan [{:my.plan/steps [:my.plan.item/id]}]}]
                                 [:seon.agent/id "juniper"])
               [:seon.agent/plan :my.plan/steps]))                  ;=> 7
;; the same nested pull against the PRE-`with` db value               ;=> 6
```

A flat `:my.plan/steps` selector on the agent returns `nil` → `count` 0. The
other 0-step trap is pulling the pre-`with` `db` instead of `(:db-after r)`
(that returns 6, not 0).

A nested map whose `:my.plan.item/id` ALREADY exists upserts into that item
rather than adding a step (`upsert-eid`, `:640`):

```clojure
(datahike.api/with db [{:db/id 36396 :my.plan/steps [{:my.plan.item/id "juniper/query"
                                                      :my.plan.item/title "RENAMED"}]}])
;; :tx-data => [[... :db/txInstant ...] [36397 :my.plan.item/title "RENAMED" true]]
;; step count stays 6
```

## 3. A unique identity on the plan DOES make the agent-keyed form upsert

Tested live without any permanent schema change, by adding a temporary unique
attribute inside the `with` chain (the attribute exists only in that database
value):

```clojure
(let [s (:db-after (datahike.api/with db [{:db/ident :my.plan.probe/agent
                                           :db/valueType :db.type/ref
                                           :db/cardinality :db.cardinality/one
                                           :db/unique :db.unique/identity}]))
      b (:db-after (datahike.api/with s [{:db/id 36396 :my.plan.probe/agent 35984}]))]
  (datahike.api/with b
    [{:my.plan.probe/agent [:seon.agent/id "juniper"]
      :my.plan/steps [{:my.plan.item/id "orders/probe"
                       :my.plan.item/title "Probe step"
                       :my.plan.item/position 9}]}]))
```

`:tx-data`

```clojure
[[536871116 :db/txInstant #inst"2026-09-09T21:01:10Z" true]
 [36589 :my.plan.item/id "orders/probe" true]
 [36589 :my.plan.item/title "Probe step" true]
 [36589 :my.plan.item/position 9 true]
 [36396 :my.plan/steps 36589 true]]
```

The entity map resolved to the EXISTING plan 36396 (`upsert-eid`, `:640-714`),
steps became the seven `["juniper/query" ... "orders/probe"]`, and
`:seon.agent/plan` still points at 36396 — no new plan, no orphan. So yes: give
the plan an identity and the agent-keyed map form becomes an upsert INTO the
plan instead of a replacement of it.

## 4. Completing a step works as written

```clojure
(datahike.api/with db [[:db/add [:my.plan.item/id "juniper/query"]
                        :my.plan.item/completed-at (java.util.Date.)]])
;; :tx-data => [[536871110 :db/txInstant #inst"2026-09-09T21:00:23Z" true]
;;              [36397 :my.plan.item/completed-at #inst"2026-09-09T21:00:23Z" true]]
;; pull => {:my.plan.item/id "juniper/query"
;;          :my.plan.item/completed-at #inst"2026-09-09T21:00:23Z"}
```

Confirmed. The lookup ref resolves through the item's unique identity; the
card-one attribute upserts in place.

## 5. Removing a step: only `retractEntity` cleans up

With the probe item at eid 36582 in the base value:

```clojure
[[:db/retract 36396 :my.plan/steps [:my.plan.item/id "orders/probe"]]]
;; :tx-data => [[... :db/txInstant ...] [36396 :my.plan/steps 36582 false]]
;; step count 6, but the ITEM SURVIVES:
;; (datoms :eavt 36582) => [[36582 :my.plan.item/id "orders/probe"]
;;                          [36582 :my.plan.item/position 9]
;;                          [36582 :my.plan.item/title "Probe step"]]
```

```clojure
[[:db.fn/retractEntity [:my.plan.item/id "orders/probe"]]]
;; :tx-data => [[... :db/txInstant ...]
;;              [36582 :my.plan.item/id "orders/probe" false]
;;              [36582 :my.plan.item/position 9 false]
;;              [36582 :my.plan.item/title "Probe step" false]
;;              [36396 :my.plan/steps 36582 false]]
;; step count 6, (datoms :eavt 36582) => []
```

`:db/retract` on the component ref severs the edge and leaves an orphan item
(`transaction.cljc:1059-1071` returns `[report []]` — no `retract-components`).
`:db.fn/retractEntity` (`:997-1014`) retracts the item's own datoms AND every
incoming ref (the `v-datoms` sweep over `:db.type/ref` attributes, `:1000-1010`),
so the plan edge disappears with it. `:db.fn/retractEntity` on the item is the
correct removal; it also cascades to that item's own sub-steps
(`retract-components`, `:830-834`, invoked at `:1013`). `:db.fn/retractAttribute`
on `:my.plan/steps` (`:1072-1076`) would cascade-delete every step.

## 6. A raw message transacts, `:id` and `:at` are absent

```clojure
(datahike.api/with db [{:seon.cluster.message/to [:seon.agent/id "root"]
                        :seon.cluster.message/from [:seon.agent/id "juniper"]
                        :seon.cluster.message/content "probe"}])
;; :tx-data => [[536871115 :db/txInstant #inst"2026-09-09T21:01:24Z" true]
;;              [41326 :seon.cluster.message/to 35918 true]
;;              [41326 :seon.cluster.message/from 35984 true]
;;              [41326 :seon.cluster.message/content "probe" true]]
;; pull [:seon.cluster.message/id :seon.cluster.message/at ...]
;;   => {:db/id 41326 :seon.cluster.message/content "probe"}   ; no :id, no :at
```

The DATABASE accepts it: `:seon.cluster.message/id` is a unique identity
(`resources/seon/schemas/seon.cluster.message.edn:45`,
`[:string {:min 1, :seon.db/identity true}]`) but uniqueness is not a
requirement, and Datahike has no required-attribute enforcement here. The
ENTITY schema does require both: `:seon.cluster.message/message`
(`seon.cluster.message.edn:61-77`) lists `:id`, `:to`, `:content` and `:at`
NON-optionally (only `:ordinal`, `:from`, `:caused-by`, `:about`,
`:my.message/reason` are `{:optional true}`). So a raw message like this is a
valid set of datoms and an INVALID `:seon.cluster.message/message` value — any
renderer or reader validating against that map schema will refuse it, and every
`:at`-reading surface (`:pulled`, `:inbox-unit`) sees a missing key.

`:at` IS derivable from the transaction, since the wake/transaction basis is a
real entity:

```clojure
(datahike.api/q '[:find ?m ?content ?inst
                  :where
                  [?m :seon.cluster.message/content ?content ?tx]
                  [?tx :db/txInstant ?inst]
                  [(= ?content "probe")]]
                d)
;; => #{[41326 "probe" #inst"2026-09-09T21:01:24Z"]}
```

The four-element datom clause binds the transaction entity, and `:db/txInstant`
on it is the wall-clock instant of the assertion. The general derivation for an
agent's inbox:

```clojure
(datahike.api/q '[:find ?m ?at
                  :in $ ?agent
                  :where
                  [?m :seon.cluster.message/to ?agent ?tx]
                  [?tx :db/txInstant ?at]]
                db [:seon.agent/id "root"])
```

So `:seon.cluster.message/at` is a stored MIRROR of `:db/txInstant` for
ordinarily-transacted messages, derivable by query; it earns its storage only
where the message's own time differs from its assertion time (an inbound
message carrying an external timestamp).

## Conclusions for the design decision

1. Never write the agent-keyed nested-map form against a card-one component ref
   whose nested map has no identity: it silently mints a new component and
   orphans the old one, with no retraction in `:tx-data` to notice.
2. `{:db/id <plan eid> :my.plan/steps [...]}` is the correct add-a-step form
   today; the caller's 0-step reading was a flat pull selector on the agent.
3. Giving the plan a unique identity (`:my.plan/agent` as a unique-identity ref
   to the agent) makes the agent-keyed map an upsert INTO the plan — proven live
   with a temporary attribute. That is the change that makes the natural raw
   form safe.
4. `[:db/add [:my.plan.item/id ...] :my.plan.item/completed-at ...]` works.
5. Remove a step with `[:db.fn/retractEntity [:my.plan.item/id ...]]`;
   `:db/retract` of the component ref leaves an orphan.
6. Raw messages transact without `:id`/`:at` and violate the entity schema;
   `:at` is derivable from `:db/txInstant`.
