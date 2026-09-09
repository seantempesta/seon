---
type: research
status: working
tags: [agent-context, repl, render]
---

# Context cookbook — executed 2026-09-09

Read end to end: AGENTS.md (its opening copies turn PRD §10), the data chart r2 including its roadmap, raw-data-forms-probe-2026-09-09.md, and turn PRD §18–§18c. The plan README and working edge ground this slice.

Surface: MCP, cluster `default`, JVM mode, explicit `(seon.operator/connection "default")`; the connection and immutable read basis are supplied to bare reads. Basis 536871325. No database write was committed. Counts measure exact `pr-str` results, without a REPL envelope; they are not yet provider-prompt byte counts. The checked-in probe and EDN retain the complete forms and results.

The target schema is absent on default. The writes below actually ran through `datahike.api/with` on a speculative value derived from default, after the probe installed the explicitly listed proposed attributes there. These establish dependency semantics, not production schema validation, wake delivery, or live target-schema adoption. Event ids are fixed probe inputs; real messages mint fresh event ids.

## Read choice and evidence

Pull describes one known entity or a known set, including nested and reverse refs. Use q for value filters, joins, and aggregates; combine q with inner pull when both filtering and shaping. The live reverse message pull recorded index patterns and returned the incoming messages in one form. Pattern-only aggregate q also recorded index patterns. Inner pull and not recorded attribute-level evidence: correct but coarse. Explicit finite selectors recorded index patterns; wildcard/recursive selectors cannot make that claim. Source: src/seon/db.clj:335–423. Index-pattern presence means constraints at each pattern, not a fully joined result dependency. An invalid read is a refusal, never an empty healthy block.

## Dependency ledger

Datahike transaction.cljc:640 (identity upsert), :738 (nested maps), :785 (cardinality-one replacement), :997 and :1059 (retractEntity versus retract); pull_api.cljc:304 (reverse refs and component collections). First-party idioms: seon.plan/render-plan-html resolves the plan owner; seon.agent/settings! consumes the full system transaction report; seon.db:335–423 captures read patterns. `datahike.api` has no entid function: use an identity pull.

## Transaction report implementation

The transact-feedback lane released `src/seon/db.clj` in `24953d294`. Its existing
transaction owner now projects the agent call to `{:seon.db/tx t :seon.db/datoms
[[e a v added?] ...]}`. It resolves entity identities against db-after, then
db-before for deleted identities, recursively following identity refs with cycle
protection. Tempids are already resolved in tx-data. The next read is the db-after;
tx-data already says what changed. Neither database snapshot belongs in shown
text. Explicit-connection system callers retain the full report they consume.

SCI call preparation initially supplied the connection and selected that system
arity. As `seon.db/db` already does, the explicit arity now also accepts a typed
connection error and propagates it. Its distinct input contract preserves the
one-argument call; the canonical regression executes the actual SCI boundary.
`:seon.db/datoms` now accepts both native datom maps and the owner-requested
four-member transaction vectors; native datom reads retain their values.
Fast gate: **7 tests / 77 assertions**. Isolated gate: **7 tests / 81 assertions**.
Combined platform with the error slice: **83 tests / 490 assertions**, all green.
The live production projection over `datahike.api/with` returned **267 bytes**;
[the result and unchanged default basis are recorded](context_cookbook_results_2026_09_09.edn).

## Returned flat errors

The evaluation recognizes a returned `:seon.error/kind` as its error. The value
renderer invokes the existing schema-selected AI pair, fits once through its
existing profile, and stores that shown text. REPL history emits it under
`:seon.repl/error`; the live result retains the complete diagnostic. Throwable
triage keeps its existing concise error format.

The canonical bad transaction produced exactly **131 UTF-8 bytes**:

```text
Expected: [:string {:min 1, :description "The current human-readable work title."}]
Got: 42
Attribute: :my.plan.item/title at [0 3]
```

Fast regression: 38 tests / 170 assertions; isolated gate: **38 tests / 174
assertions**, green. The bad transaction leaves its title unchanged. Default's
hot-reloaded, re-armed evaluation point independently rendered the pure validator's
returned diagnostic to the same 131 bytes without a database write;
[the exact response is retained](context_cookbook_error_2026_09_09.edn).

## Verification boundary

The first batch exceeded the MCP 20-second request bound but completed and wrote its evidence file; a second session returned `(+ 1 1)` in 1 ms. No alternate transport was used. These probes committed no database writes. Default was never stopped, restarted, or reforked. Schema-dependent blocks and reseeding require the chart data lane's landing and the owner's batched reset; RESET NEEDED when that schema commit is known.

The later platform gate on HEAD `3d13aa0f7` failed two blob-reachability tests:
83 tests / 490 assertions, two failures, both confirmed in isolated workers.
A HEAD-only fast snapshot (`--paths AGENTS.md`, no snapshot differences) reproduced
the same failures: 12 tests / 62 assertions. This excludes the cookbook changes;
the exact boundary is [recorded as an issue](../../../seon/issues/archive/platform-blob-reachability-fails-at-3d13aa0f7.md).
The earlier platform gate on `eec1ca7c3` was green. After the owning lane's
`24953d294` fixture correction, the platform gate with the final print, value,
identity, and namespace paths passed: **83 tests / 490 assertions**, zero
failures or errors. The earlier failure is retained as dated evidence.

### Identity and namespace code slice

Identity now emits the finite raw pull from the cookbook. The namespace pair
emits `(dir <namespace>)` even when empty, then the existing schema-derived count
query when there are stored attributes. No second opening mechanism was added.
The canonical system-turn regression executes these generated forms through real
SCI and verifies the stored opening and retained read evidence.

Fast gate: `seon.render.ns-test seon.help-test`, 10 tests / 118 assertions, green.
Isolated gate with only `src/seon/cluster/agent.clj`, `src/seon/render/ns.clj`,
`test/seon/render/ns_test.clj`, and `test/seon/help_test.clj`: 10 tests / 122
assertions, green. Live default JVM calls after hot reload and re-arming returned
the raw identity pull and `(dir my.agents.juniper)` followed by the count query.
This proves those loaded functions; it does not rewrite default's stored opening.

### Agent-source formatting gate

`seon.repl/source-text` prints reader quotes and explicit keyword keys; identity
and namespace renderers use it directly. The cookbook probe uses the same
function and re-executed all 32 records at basis 536871325. A live hot-reloaded,
re-armed identity call emitted the identical pull bytes. Clojure pprint
`pprint_base.clj:197` and `dispatch.clj:430–470` own printing and reader macros.
Fast gate: **24 tests / 179 assertions**; isolated gate: **24 / 183**; platform
green. HEAD-only probing reproduced the stale namespace declaration provenance
and partial cluster fixtures; this slice corrects their setup and asserts write
success before reading. The unrelated `help_trial_2026_09_09.clj` edit is excluded.

### Current provider prompt

The complete [provider prompt](context_cookbook_prompt_2026_09_09.txt) was acquired
through `seon.render/acquire-context!` and read end to end: **9,757 UTF-8 bytes**,
nine stored evaluations. It contains the previous opening, repeated help/inbox,
and `(+ 1 1)`. Old shown text still contains the earlier help markers and call
shapes, as required by immutable history; hot-reloading renderers does not rewrite
those evaluations. This is a baseline observation, not the final reseeded trial.
No provider call was made. The canonical trial's six-source fixture precondition
does not hold, and the chart schema work remains an explicit scope decision.
The [capture script](context_cookbook_probe_2026_09_09.clj) and
[render measurements](context_cookbook_render_2026_09_09.edn) retain the evidence.

## Agent-source recheck

All source forms below use reader quotes, explicit keyword keys, and supplied database custody. The production `seon.repl/source-text` uses Clojure's `pprint/code-dispatch`; the probe calls that same function. Each of the 23 bare reads was executed beside its explicit-database form at the same immutable basis; all returned equal values. Each of the nine printed transactions parsed to identical transaction data and then ran through `datahike.api/with`. Writes below are the agent's source, never committed to default. The new report timestamps come from those actual speculative transactions. [Complete recheck evidence](context_cookbook_rechecked_2026_09_09.edn).

## Identity

Pull: one known entity and its nested refs.

```clojure
;; I should know who I am and where my forms run.
(seon.db/pull
  '[:seon.agent/id {:seon.agent/namespace [:seon.ns/name {:seon.ns/steward [:seon.agent/id]}]}]
  [:seon.agent/id "juniper"])
```

Actual read result: **112 UTF-8 bytes**; evidence [:index-patterns].

```clojure
#:seon.agent{:id "juniper", :namespace #:seon.ns{:name my.agents.juniper, :steward #:seon.agent{:id "juniper"}}}
```

## Plan

Pull: the agent's plan component and nested steps.

```clojure
;; I should read the plan component, not select its attributes on the agent; its steps are a set, so I order them by position.
(update
  (:seon.agent/plan
    (seon.db/pull
      '[{:seon.agent/plan
         [:my.plan/objective
          {:my.plan/current-step [:my.plan.item/id]}
          {:my.plan/steps
           [:my.plan.item/id
            :my.plan.item/title
            :my.plan.item/expected-result
            :my.plan.item/position
            :my.plan.item/completed-at
            {:my.plan.item/needs [:my.plan.item/id]}]}]}]
      [:seon.agent/id "juniper"]))
  :my.plan/steps
  #(vec (sort-by :my.plan.item/position %)))
```

Actual read result: **1392 UTF-8 bytes**; evidence [:index-patterns].

```clojure
#:my.plan{:objective "Which customer has the largest total? Add an order of 40 for them and tell me the new total.", :current-step #:my.plan.item{:id "juniper/query"}, :steps [#:my.plan.item{:id "juniper/query", :title "Query the orders", :expected-result "I have read the order ids, customers, and amounts.", :position 0} #:my.plan.item{:id "juniper/aggregate", :title "Find the customer with the largest total", :expected-result "A grouped sum query identifies the customer and their total.", :position 1, :needs [#:my.plan.item{:id "juniper/query"}]} #:my.plan.item{:id "juniper/transact", :title "Add an order of 40 for that customer", :expected-result "The transaction result identifies the new order.", :position 2, :needs [#:my.plan.item{:id "juniper/aggregate"}]} #:my.plan.item{:id "juniper/requery", :title "Read the customer's new total", :expected-result "A fresh grouped sum query includes the new order.", :position 3, :needs [#:my.plan.item{:id "juniper/transact"}]} #:my.plan.item{:id "juniper/reply", :title "Tell root the customer and new total", :expected-result "The sent message contains the customer and verified new total.", :position 4, :needs [#:my.plan.item{:id "juniper/requery"}]} #:my.plan.item{:id "juniper/done", :title "Finish the session", :expected-result "All preceding plan items are complete.", :position 5, :needs [#:my.plan.item{:id "juniper/reply"}]}]}
```

## Settings

Pull: the agent's override component.

```clojure
;; I should pull my overrides; omitted settings inherit defaults, and turns left is derived rather than a stored attribute.
(seon.db/pull
  '[{:seon.agent/settings
     [:seon.config.ai/model
      :seon.config.ai/no-provider
      :seon.config.eval/time-limit-ms
      :seon.config.run/max-episode-runs]}]
  [:seon.agent/id "juniper"])
```

Actual read result: **135 UTF-8 bytes**; evidence [:index-patterns].

```clojure
#:seon.agent{:settings {:seon.config.ai/no-provider true, :seon.config.eval/time-limit-ms 10000, :seon.config.run/max-episode-runs 20}}
```

## Runtime target

Pull: the intended component shape; this installed-schema refusal is not an empty runtime.

```clojure
;; I should inspect unknown-attribute candidates before guessing a runtime attribute.
(seon.db/pull
  '[{:seon.agent/runtime
     [{:seon.runtime/turn [:seon.turn/id]} {:seon.runtime/listens [:seon.listen/attribute]}]}]
  [:seon.agent/id "juniper"])
```

Actual read result: **467 UTF-8 bytes**; evidence [:attribute-level].

```clojure
{:seon.db/invalid-read true, :seon.error/kind :seon.db/invalid-read, :seon.error/message "Bad entity attribute :seon.agent/runtime at (resolve-datom db 35984 :seon.agent/runtime nil nil), not defined in current schema", :seon.error/data #:seon.db{:operation :datahike.pull/result, :exception-class "clojure.lang.ExceptionInfo", :dependency-data {:error :transact/schema, :attribute :seon.agent/runtime, :context (resolve-datom db 35984 :seon.agent/runtime nil nil)}}}
```

## Runtime current

Q plus inner pull: filter open turns and shape their refs; absence filtering and inner pull yield attribute-level evidence.

```clojure
;; I should find my open turns by filtering for the absence of closed-at.
(seon.db/q
  '[:find
    [(pull ?t [:seon.turn/id :seon.turn/opened-at {:seon.turn/trigger [:seon.cluster.message/id]}])
     ...]
    :where
    [?t :seon.turn/agent [:seon.agent/id "juniper"]]
    (not [?t :seon.turn/closed-at])])
```

Actual read result: **148 UTF-8 bytes**; evidence [:attribute-level].

```clojure
[#:seon.turn{:id "9fc9bc9ef8ad", :opened-at #inst "2026-09-09T22:21:44.463-00:00", :trigger #:seon.cluster.message{:id "juniper/largest-customer"}}]
```

## Messages

Reverse-ref pull: all messages addressed to this known agent, with sender shape.

```clojure
;; I should follow incoming messages with a reverse-ref pull on myself.
(get
  (seon.db/pull
    '[{:seon.cluster.message/_to
       [:seon.cluster.message/id
        :seon.cluster.message/content
        {:seon.cluster.message/from [:seon.agent/id]}]}]
    [:seon.agent/id "juniper"])
  :seon.cluster.message/_to
  [])
```

Actual read result: **193 UTF-8 bytes**; evidence [:index-patterns].

```clojure
[#:seon.cluster.message{:id "juniper/largest-customer", :content "Which customer has the largest total? Add an order of 40 for them and tell me the new total.", :from #:seon.agent{:id "root"}}]
```

## History

Q: join this agent's turns to their evaluations. The prompt itself is the history block.

```clojure
;; I should inspect stored source and shown text only when needed; my prompt already contains my history.
(seon.db/q
  '[:find
    ?ordinal
    ?source
    :where
    [?t :seon.turn/agent [:seon.agent/id "juniper"]]
    [?e :seon.cluster.eval/run ?t]
    [?e :seon.cluster.eval/ordinal ?ordinal]
    [?e :seon.cluster.eval/source ?source]])
```

Actual read result: **432 UTF-8 bytes**; evidence [:index-patterns].

```clojure
#{[3 "(my.message/inbox)"] [0 "(help)"] [1 "(seon.db/pull\n  '[:seon.agent/id {:seon.agent/namespace [:seon.ns/name {:seon.ns/steward [:seon.agent/id]}]}]\n  [:seon.agent/id \"juniper\"])"] [6 "(seon.db/q\n  '[:find ?attribute (count ?entity) :in $ [?attribute ...] :where [?entity ?attribute _]]\n  [:example/amount :example/customer :example/order])"] [5 "(dir my.agents.juniper)"] [4 "(my.agent/settings)"] [2 "(my.plan/items)"]}
```

## Faults root

Q plus inner pull: filter by repair steward and shape the fault. Root gets this read at turn 0.

```clojure
;; I should fix faults routed to me as steward before other work.
(seon.db/q
  '[:find
    [(pull ?f [:seon.error/kind :seon.error/message :seon.instrument/fn]) ...]
    :where
    [?f :seon.error/steward [:seon.agent/id "root"]]])
```

Actual read result: **2 UTF-8 bytes**; evidence [:attribute-level].

```clojure
[]
```

## Faults juniper

Q plus inner pull: an ordinary agent gets this block when a fault is routed to it as steward.

```clojure
;; I should distinguish faults routed to me from faults that happened to me.
(seon.db/q
  '[:find
    [(pull ?f [:seon.error/kind :seon.error/message :seon.instrument/fn]) ...]
    :where
    [?f :seon.error/steward [:seon.agent/id "juniper"]]])
```

Actual read result: **2 UTF-8 bytes**; evidence [:attribute-level].

```clojure
[]
```

## Notes

Reverse-ref pull: notes attached to this known agent.

```clojure
;; I should read my saved notes, including an empty result so later notes can refresh this read.
(get
  (seon.db/pull
    '[{:my.note/_agent [:my.note/id :my.note/content {:my.note/about [:my.plan.item/id]}]}]
    [:seon.agent/id "juniper"])
  :my.note/_agent
  [])
```

Actual read result: **2 UTF-8 bytes**; evidence [:index-patterns].

```clojure
[]
```

## Namespace

Pull: the namespace and its reverse declaration refs.

```clojure
;; I should inspect the declarations owned by my namespace before choosing attributes.
(seon.db/pull
  '[:seon.ns/name
    {:seon.schema/_ns [:seon.schema/key :seon.schema/form]}
    {:seon.fn/_ns [:seon.fn/sym :seon.fn/doc]}]
  [:seon.ns/name 'my.agents.juniper])
```

Actual read result: **425 UTF-8 bytes**; evidence [:index-patterns].

```clojure
{:seon.ns/name my.agents.juniper, :seon.schema/_ns [#:seon.schema{:key :example/order, :form "[:string #:seon.db{:identity true}]"} #:seon.schema{:key :example/amount, :form ":int"} #:seon.schema{:key :example/customer, :form ":string"} #:seon.schema{:key :example/order-row, :form "[:map #:seon.db{:attributes true} [:example/order :example/order] [:example/amount :example/amount] [:example/customer :example/customer]]"}]}
```

## Namespace counts

Q: count stored facts for the declared attributes.

```clojure
;; I should count facts with q; a pull describes entities but does not aggregate them.
(seon.db/q
  '[:find ?a (count ?e) :in $ [?a ...] :where [?e ?a _]]
  [:example/order :example/customer :example/amount])
```

Actual read result: **62 UTF-8 bytes**; evidence [:index-patterns].

```clojure
[[:example/customer 4] [:example/amount 4] [:example/order 4]]
```

## Root agents

Q plus inner pull: select all agents and shape their current work.

```clojure
;; I should select all agents with q and shape each record with inner pull.
(seon.db/q
  '[:find
    [(pull ?a [:seon.agent/id {:seon.agent/plan [{:my.plan/current-step [:my.plan.item/title]}]}])
     ...]
    :where
    [?a :seon.agent/id]])
```

Actual read result: **128 UTF-8 bytes**; evidence [:attribute-level].

```clojure
[#:seon.agent{:id "juniper", :plan #:my.plan{:current-step #:my.plan.item{:title "Query the orders"}}} #:seon.agent{:id "root"}]
```

## Orders

Q: join order identities, customers, and amounts.

```clojure
;; I should read order ids, customers, and amounts before completing the query step.
(seon.db/q
  '[:find
    ?id
    ?customer
    ?amount
    :where
    [?e :example/order ?id]
    [?e :example/customer ?customer]
    [?e :example/amount ?amount]])
```

Actual read result: **66 UTF-8 bytes**; evidence [:index-patterns].

```clojure
#{["c1" "Cy" 40] ["b1" "Bea" 100] ["a1" "Ada" 60] ["a2" "Ada" 55]}
```

## Customer totals

Q: group by customer and sum amounts.

```clojure
;; I should group by customer and sum amounts rather than add the rows myself.
(seon.db/q
  '[:find
    ?customer
    (sum ?amount)
    :where
    [?e :example/customer ?customer]
    [?e :example/amount ?amount]])
```

Actual read result: **35 UTF-8 bytes**; evidence [:index-patterns].

```clojure
[["Ada" 115] ["Bea" 100] ["Cy" 40]]
```

## Write examples

The proposed identity, transaction-time, message, and runtime attributes exist only in the speculative value. These are dependency-semantic probes, not a claim that the data lane's schema is installed.

## Add a step

```clojure
;; I should upsert by the plan's identity; an identity-less nested map would silently replace the component.
(seon.db/transact!
  [{:my.plan/agent [:seon.agent/id "juniper"],
    :my.plan/steps
    [{:my.plan.item/id "juniper/verify",
      :my.plan.item/title "Verify the new total",
      :my.plan.item/done-when "The fresh sum includes the new order.",
      :my.plan.item/position 6}]}])
```

Actual speculative transaction result: **514 UTF-8 bytes**.

```clojure
#:seon.db{:tx 536871328, :datoms [[536871328 :db/txInstant #inst "2026-09-09T22:22:44.428-00:00" true] [[:my.plan.item/id "juniper/verify"] :my.plan.item/id "juniper/verify" true] [[:my.plan.item/id "juniper/verify"] :my.plan.item/title "Verify the new total" true] [[:my.plan.item/id "juniper/verify"] :my.plan.item/done-when "The fresh sum includes the new order." true] [[:my.plan.item/id "juniper/verify"] :my.plan.item/position 6 true] [[:my.plan/agent [:seon.agent/id "juniper"]] :my.plan/steps 45496 true]]}
```

## Complete a step

```clojure
;; I have seen the query result; with no clock function, I record completion as a ref to datomic.tx.
(seon.db/transact!
  [[:db/add [:my.plan.item/id "juniper/query"] :my.plan.item/completed-tx "datomic.tx"]])
```

Actual speculative transaction result: **183 UTF-8 bytes**.

```clojure
#:seon.db{:tx 536871329, :datoms [[536871329 :db/txInstant #inst "2026-09-09T22:22:44.429-00:00" true] [[:my.plan.item/id "juniper/query"] :my.plan.item/completed-tx 536871329 true]]}
```

## Make current

```clojure
;; I should make the aggregate step current after completing the query.
(seon.db/transact!
  [[:db/add
    [:my.plan/agent [:seon.agent/id "juniper"]]
    :my.plan/current-step
    [:my.plan.item/id "juniper/aggregate"]]])
```

Actual speculative transaction result: **183 UTF-8 bytes**.

```clojure
#:seon.db{:tx 536871330, :datoms [[536871330 :db/txInstant #inst "2026-09-09T22:22:44.430-00:00" true] [[:my.plan/agent [:seon.agent/id "juniper"]] :my.plan/current-step 45446 true]]}
```

## Remove a step

```clojure
;; I should use retractEntity to remove the item and incoming refs; retracting only the component edge leaves an orphan.
(seon.db/transact! [[:db.fn/retractEntity [:my.plan.item/id "juniper/verify"]]])
```

Actual speculative transaction result: **519 UTF-8 bytes**.

```clojure
#:seon.db{:tx 536871331, :datoms [[536871331 :db/txInstant #inst "2026-09-09T22:22:44.431-00:00" true] [[:my.plan.item/id "juniper/verify"] :my.plan.item/done-when "The fresh sum includes the new order." false] [[:my.plan.item/id "juniper/verify"] :my.plan.item/id "juniper/verify" false] [[:my.plan.item/id "juniper/verify"] :my.plan.item/position 6 false] [[:my.plan.item/id "juniper/verify"] :my.plan.item/title "Verify the new total" false] [[:my.plan/agent [:seon.agent/id "juniper"]] :my.plan/steps 45496 false]]}
```

## Send a message

```clojure
;; I should include an event identity when I transact a message to root.
(seon.db/transact!
  [{:seon.message/id "c00cb001",
    :seon.message/to [:seon.agent/id "root"],
    :seon.message/from [:seon.agent/id "juniper"],
    :seon.message/content "The query found Ada totals 115."}])
```

Actual speculative transaction result: **384 UTF-8 bytes**.

```clojure
#:seon.db{:tx 536871332, :datoms [[536871332 :db/txInstant #inst "2026-09-09T22:22:44.432-00:00" true] [[:seon.message/id "c00cb001"] :seon.message/id "c00cb001" true] [[:seon.message/id "c00cb001"] :seon.message/to 35918 true] [[:seon.message/id "c00cb001"] :seon.message/from 35984 true] [[:seon.message/id "c00cb001"] :seon.message/content "The query found Ada totals 115." true]]}
```

## Answer a message

```clojure
;; I should link the reply to the question and mark that question handled in the same transaction.
(seon.db/transact!
  [{:seon.message/id "c00cb002",
    :seon.message/to [:seon.agent/id "root"],
    :seon.message/from [:seon.agent/id "juniper"],
    :seon.message/content "I will add 40 and read the new total.",
    :seon.message/about [:seon.message/id "c00cb000"]}
   [:db/add [:seon.message/id "c00cb000"] :seon.message/read-tx "datomic.tx"]])
```

Actual speculative transaction result: **522 UTF-8 bytes**.

```clojure
#:seon.db{:tx 536871333, :datoms [[536871333 :db/txInstant #inst "2026-09-09T22:22:44.434-00:00" true] [[:seon.message/id "c00cb002"] :seon.message/id "c00cb002" true] [[:seon.message/id "c00cb002"] :seon.message/to 35918 true] [[:seon.message/id "c00cb002"] :seon.message/from 35984 true] [[:seon.message/id "c00cb002"] :seon.message/content "I will add 40 and read the new total." true] [[:seon.message/id "c00cb002"] :seon.message/about 45495 true] [[:seon.message/id "c00cb000"] :seon.message/read-tx 536871333 true]]}
```

## Change a setting

```clojure
;; I should address my settings component by identity so I preserve its other overrides.
(seon.db/transact!
  [{:seon.config/agent [:seon.agent/id "juniper"], :seon.config.eval/time-limit-ms 2500}])
```

Actual speculative transaction result: **196 UTF-8 bytes**.

```clojure
#:seon.db{:tx 536871334, :datoms [[536871334 :db/txInstant #inst "2026-09-09T22:22:44.435-00:00" true] [[:seon.config/agent [:seon.agent/id "juniper"]] :seon.config.eval/time-limit-ms 2500 true]]}
```

## Declare a listen

```clojure
;; I should add an attribute pattern to my runtime listens.
(seon.db/transact!
  [{:seon.runtime/agent [:seon.agent/id "juniper"],
    :seon.runtime/listens [{:seon.listen/attribute :example/amount}]}])
```

Actual speculative transaction result: **240 UTF-8 bytes**.

```clojure
#:seon.db{:tx 536871335, :datoms [[536871335 :db/txInstant #inst "2026-09-09T22:22:44.436-00:00" true] [45499 :seon.listen/attribute :example/amount true] [[:seon.runtime/agent [:seon.agent/id "juniper"]] :seon.runtime/listens 45499 true]]}
```

## Transact a note

```clojure
;; I should save the verified query result as a note linked to its step.
(seon.db/transact!
  [{:my.note/id "juniper/orders-observed",
    :my.note/agent [:seon.agent/id "juniper"],
    :my.note/about [:my.plan.item/id "juniper/query"],
    :my.note/content "Read four orders; next compute customer totals."}])
```

Actual speculative transaction result: **439 UTF-8 bytes**.

```clojure
#:seon.db{:tx 536871336, :datoms [[536871336 :db/txInstant #inst "2026-09-09T22:22:44.437-00:00" true] [[:my.note/id "juniper/orders-observed"] :my.note/id "juniper/orders-observed" true] [[:my.note/id "juniper/orders-observed"] :my.note/agent 35984 true] [[:my.note/id "juniper/orders-observed"] :my.note/about 45444 true] [[:my.note/id "juniper/orders-observed"] :my.note/content "Read four orders; next compute customer totals." true]]}
```

## Verify the speculative state

## Runtime after declaring a listen

Pull: verify the known entity's resulting shape.

```clojure
;; I should verify that my runtime contains the listen I added.
(seon.db/pull
  '[{:seon.agent/runtime [{:seon.runtime/listens [:seon.listen/attribute]}]}]
  [:seon.agent/id "juniper"])
```

Actual read result: **91 UTF-8 bytes**; evidence [:index-patterns].

```clojure
#:seon.agent{:runtime #:seon.runtime{:listens [#:seon.listen{:attribute :example/amount}]}}
```

## Target messages, reverse pull

Pull: verify the known entity's resulting shape.

```clojure
;; I should read root's incoming messages through seon.message/_to.
(seon.db/pull
  '[{:seon.message/_to
     [:seon.message/id
      :seon.message/content
      {:seon.message/from [:seon.agent/id]}
      {:seon.message/about [:seon.message/id]}]}]
  [:seon.agent/id "root"])
```

Actual read result: **287 UTF-8 bytes**; evidence [:index-patterns].

```clojure
#:seon.message{:_to [#:seon.message{:id "c00cb001", :content "The query found Ada totals 115.", :from #:seon.agent{:id "juniper"}} #:seon.message{:id "c00cb002", :content "I will add 40 and read the new total.", :from #:seon.agent{:id "juniper"}, :about #:seon.message{:id "c00cb000"}}]}
```

## Completion instant

Pull: verify the known entity's resulting shape.

```clojure
;; I should derive the completion instant from its transaction ref.
(seon.db/pull
  '[:my.plan.item/id {:my.plan.item/completed-tx [:db/txInstant]}]
  [:my.plan.item/id "juniper/query"])
```

Actual read result: **105 UTF-8 bytes**; evidence [:index-patterns].

```clojure
#:my.plan.item{:id "juniper/query", :completed-tx #:db{:txInstant #inst "2026-09-09T22:22:44.429-00:00"}}
```

## Removal verification

Pull: verify the known entity's resulting shape.

```clojure
;; I should verify the deleted item is absent, not merely detached from the plan.
(seon.db/pull [:my.plan.item/id] [:my.plan.item/id "juniper/verify"])
```

Actual read result: **3 UTF-8 bytes**; evidence [:index-patterns].

```clojure
nil
```

## Plan membership after removal

Pull: verify the known entity's resulting shape.

```clojure
;; I should see the original six steps and the new current step after removing my probe item.
(seon.db/pull
  '[{:my.plan/current-step [:my.plan.item/id]}
    {:my.plan/steps [:my.plan.item/id :my.plan.item/position]}]
  [:my.plan/agent [:seon.agent/id "juniper"]])
```

Actual read result: **376 UTF-8 bytes**; evidence [:index-patterns].

```clojure
#:my.plan{:current-step #:my.plan.item{:id "juniper/aggregate"}, :steps [#:my.plan.item{:id "juniper/query", :position 0} #:my.plan.item{:id "juniper/transact", :position 2} #:my.plan.item{:id "juniper/aggregate", :position 1} #:my.plan.item{:id "juniper/reply", :position 4} #:my.plan.item{:id "juniper/requery", :position 3} #:my.plan.item{:id "juniper/done", :position 5}]}
```

## Handled question

Pull: verify the known entity's resulting shape.

```clojure
;; I should verify the question carries the transaction that handled it.
(seon.db/pull
  '[:seon.message/id {:seon.message/read-tx [:db/txInstant]}]
  [:seon.message/id "c00cb000"])
```

Actual read result: **95 UTF-8 bytes**; evidence [:index-patterns].

```clojure
#:seon.message{:id "c00cb000", :read-tx #:db{:txInstant #inst "2026-09-09T22:22:44.434-00:00"}}
```

## Settings preservation

Pull: verify the known entity's resulting shape.

```clojure
;; I should see my changed time limit alongside the untouched overrides.
(seon.db/pull
  [:seon.config.eval/time-limit-ms :seon.config.ai/no-provider :seon.config.run/max-episode-runs]
  [:seon.config/agent [:seon.agent/id "juniper"]])
```

Actual read result: **110 UTF-8 bytes**; evidence [:index-patterns].

```clojure
{:seon.config.eval/time-limit-ms 2500, :seon.config.ai/no-provider true, :seon.config.run/max-episode-runs 20}
```

## Saved note

Pull: verify the known entity's resulting shape.

```clojure
;; I should see my saved note linked to the query step.
(seon.db/pull
  '[:my.note/id :my.note/content {:my.note/about [:my.plan.item/id]}]
  [:my.note/id "juniper/orders-observed"])
```

Actual read result: **144 UTF-8 bytes**; evidence [:index-patterns].

```clojure
#:my.note{:id "juniper/orders-observed", :content "Read four orders; next compute customer totals.", :about #:my.plan.item{:id "juniper/query"}}
```

## Positioned component proof

The renderer derives component membership from the handed database schema, sorts counted members when all share a numeric position key, and preserves set syntax through fitting and emission. An uncounted tail is not traversed to discover positions. The first regression's lexical order matched its position order; the live opposite-order probe falsified it. The corrected regression puts those orders in opposition. Live default returned 118 bytes, `cookbook/b` at 1 before `cookbook/a` at 2; [both ordering and set-preservation checks are true](context_cookbook_set_2026_09_09.edn). The real plan's six pulled positions changed from 0,2,1,4,3,5 to 0,1,2,3,4,5 at 373 shown bytes. The corrected isolated print/value gate passed 42 tests / 202 assertions; platform passed 83 / 490. The print fixture now reads the actual node from `admit-value`, matching its existing cross-process test.
