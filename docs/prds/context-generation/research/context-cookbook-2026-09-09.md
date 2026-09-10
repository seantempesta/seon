---
type: research
status: verified
tags: [agent-context, repl, render]
---

# Context cookbook — landed data shapes, 2026-09-09

Read AGENTS.md, the chart r2 and roadmap, its raw-data probe note, and turn PRD §18–§18d end to end. This replaces the earlier proposed-schema examples with executed canonical-fixture evidence. [Landing and verification](context-page-review-2026-09-09.md).

MCP JVM mode, cluster `cookbook-page`, explicit `(seon.operator/connection "cookbook-page")`, immutable basis **536870990**. Reads execute through the real agent SCI evaluation point with that database supplied. Every write below executes with `datahike.api/with` on a succession of immutable database values; no fixture or default write is committed. The source shown is exactly what an agent types. Read outputs are the production renderer's shown text; write outputs are the compact transaction report's exact `pr-str`. The nine writes use fixed message event ids for reproducibility.

The [whole reseeded prompt](context_cookbook_final_prompt_2026_09_09.txt), read end to end, is **9093 bytes**. Help is **2470 bare bytes**; its complete entry is **2554 bytes**. The exact multiline regression is **128 bytes**. Paid trial: **`:unavailable`**, provider credits unavailable; no paid retry.

Pull describes one known entity or known set, nested refs, and reverse refs. Q handles filters, joins, and aggregates; q with inner pull combines filtering and shaping. Finite pull selectors and positive datom patterns record index constraints; recursive/wildcard pulls and inner-pull/absence joins retain attribute-level evidence. The counts below include acquisition and rendering reads as well as the form: index-pattern presence does not make the entire evaluation exact. The dependency capture owner is `src/seon/db.clj:335–423`.

Dependency ledger: Datahike `transaction.cljc:640` identity upsert, `:738` nested maps, `:785` cardinality-one replacement, `:997/:1059` retractEntity/retract; `pull_api.cljc:304` reverse refs and component collections. First-party owners: `seon.db/transaction-result`, `seon.repl/source-text`, `seon.render.value/prepare`, and the schema-declared block pairs. The next read is db-after; tx-data already says what changed, so the report omits db-before/db-after.

## Help

The declared help pair returns bare lines.

```clojure
;; I should understand how this REPL works before I act.
(help)
```

6 form bytes; **2470 output UTF-8 bytes**. Complete evaluation evidence: {:attribute-level 4, :index-patterns 15}.

```clojure
The prompt shows your namespace my.agents.juniper and is drawn for you. Send only ;; thinking comments and forms.
Results are data: chain them with ->>, sort-by, filter, map, and get-in. Every function in the program is callable.
Forms are evaluated in order, and their results arrive in your NEXT turn. Act on a result only after you have seen it; do not complete a step in the same reply as the form that does the work.
A declared AI renderer prints its output directly. Other results use #:seon.repl: :value (or :error) is data, :out is printed text, and :result names the live value.
result/e... is a real symbol bound to the live value: evaluate it, pass it as an argument, or dig in with get-in and keys.
When unsure, inspect data first: (dir my.agents.juniper) lists your namespace's public functions and schema declarations; (doc seon.db/q) returns its docstring and contract.
Your plan is your instructions. Read its current step and completion criterion before acting; mark it complete only after seeing the result. Update an existing component by its identity or :db/id: a new identity-less nested map replaces it.
Read incoming messages with a reverse-ref pull on your agent. (my.message/send {:my.message/to "root" :my.message/content "..."}) sends; sending does not end your turn. Remove an entity and its incoming refs with (seon.db/transact! [[:db.fn/retractEntity lookup-ref]]); retract removes only the named fact.
Use pull for a known entity's shape, nested refs, and reverse refs such as :seon.message/_inbox; q for filters, joins, and aggregates; q with inner pull for filtering and shaping. (seon.db/transact! tx-data) writes. Your cluster database is supplied.
A defn with :malli/schema becomes a durable function. A deftest becomes a durable test. (my.test/run) runs yours.
A mistake returns :error data. Read the expected schema, offending value, and attribute candidates before retrying. Time is the transaction: a ref value "datomic.tx" names this write, for example (seon.db/transact! [{:my.note/id "observation" :my.note/agent [:seon.agent/id "juniper"] :my.note/content "Verified" :my.note/about "datomic.tx"}]); pull :db/txInstant through that ref.
Each reply is one turn. (seon.turn/turns-left) reads your remaining turns; settings contain the configured limit. (my.agent/done) ends your session early.
Tools: my.agent, my.background, my.edit, my.fs, my.message, my.note, my.plan, my.shell, my.test, my.turn, my.web. Inspect one with dir.
```

## Identity

Pull: one known entity and its nested refs.

```clojure
;; I should know my identity, namespace, and its steward.
(seon.db/pull
  '[:seon.agent/id {:seon.agent/namespace [:seon.ns/name {:seon.ns/steward [:seon.agent/id]}]}]
  [:seon.agent/id "juniper"])
```

139 form bytes; **116 output UTF-8 bytes**. Complete evaluation evidence: {:attribute-level 2, :index-patterns 3}.

```clojure
#:seon.agent{:id "juniper", :namespace #:seon.ns{:name my.agents.juniper,
    :steward #:seon.agent{:id "juniper"}}}
```

## Plan

Pull: the plan component and its nested steps; the recursive selector has attribute-level evidence.

```clojure
;; I can add to an existing plan by its db/id; the step id is (seon.id/id title 8), so the same title names the same step.
;; (let [title "Verify customer totals"
;;       plan (get
;;              (seon.db/pull
;;                '[{:seon.agent/plan [:db/id {:my.plan/steps [:my.plan.item/position]}]}]
;;                [:seon.agent/id "juniper"])
;;              :seon.agent/plan)]
;;   (seon.db/transact!
;;     [{:db/id (:db/id plan),
;;       :my.plan/steps
;;       [{:my.plan.item/id (seon.id/id title 8),
;;         :my.plan.item/title title,
;;         :my.plan.item/position
;;         (inc (reduce max -1 (map :my.plan.item/position (:my.plan/steps plan))))}]}]))
;; I remove the step with retractEntity; retracting the component edge alone leaves the child.
;; (seon.db/transact!
;;   [[:db.fn/retractEntity [:my.plan.item/id (seon.id/id "Verify customer totals" 8)]]])
;; I should pull the plan component; its set is shown in position order, and get with a default could hide a refusal.
(seon.db/pull
  '[{:seon.agent/plan
     [:my.plan/objective
      {:my.plan/current-step [:my.plan.item/id]}
      {:my.plan/steps
       [:my.plan.item/id
        :my.plan.item/title
        :my.plan.item/done-when
        :my.plan.item/position
        {:my.plan.item/completed-tx [:db/txInstant]}
        {:my.plan.item/needs [:my.plan.item/id]}
        {:my.plan.item/steps ...}]}]}]
  [:seon.agent/id "juniper"])
```

418 form bytes; **1501 output UTF-8 bytes**. Complete evaluation evidence: {:attribute-level 3, :index-patterns 2}.

```clojure
#:seon.agent{:plan #:my.plan{:current-step #:my.plan.item{:id "juniper/query"},
    :objective "Which customer has the largest total? Add an order of 40 for them and tell me the new total.",
    :steps [#:my.plan.item{:done-when "I have read the order ids, customers, and amounts.",
        :id "juniper/query", :position 0, :title "Query the orders"} #:my.plan.item{:done-when
        "A grouped sum query identifies the customer and their total.", :id
        "juniper/aggregate", :needs #{#:my.plan.item{:id "juniper/query"}},
        :position 1, :title "Find the customer with the largest total"} #:my.plan.item{:done-when
        "The transaction result identifies the new order.", :id "juniper/transact",
        :needs #{#:my.plan.item{:id "juniper/aggregate"}}, :position 2, :title
        "Add an order of 40 for that customer"} #:my.plan.item{:done-when
        "A fresh grouped sum query includes the new order.", :id "juniper/requery",
        :needs #{#:my.plan.item{:id "juniper/transact"}}, :position 3, :title
        "Read the customer's new total"} #:my.plan.item{:done-when "The sent message contains the customer and verified new total.",
        :id "juniper/reply", :needs #{#:my.plan.item{:id "juniper/requery"}},
        :position 4, :title "Tell root the customer and new total"} #:my.plan.item{:done-when
        "All preceding plan items are complete.", :id "juniper/done", :needs
        #{#:my.plan.item{:id "juniper/reply"}}, :position 5, :title "Finish the session"}]}}
```

## Messages

Reverse-ref pull: the known agent's inbox and sender shapes.

```clojure
;; I should follow incoming messages with a reverse-ref pull on myself.
(seon.db/pull
  '[{:seon.message/_inbox
     [:seon.message/id :seon.message/content {:seon.message/from [:seon.agent/id]}]}]
  [:seon.agent/id "juniper"])
```

155 form bytes; **199 output UTF-8 bytes**. Complete evaluation evidence: {:attribute-level 2, :index-patterns 3}.

```clojure
#:seon.message{:_inbox [#:seon.message{:content "Which customer has the largest total? Add an order of 40 for them and tell me the new total.",
      :from #:seon.agent{:id "root"}, :id "7e09bc39"}]}
```

## Settings

Effective settings: query the cluster configuration and pull the agent overlay; derive turns-left from turn facts.

```clojure
;; I should read effective settings; my overrides win over cluster defaults, and turns left is derived.
(seon.agent/effective-settings)
```

31 form bytes; **696 output UTF-8 bytes**. Complete evaluation evidence: {:attribute-level 5, :index-patterns 9}.

```clojure
[#:seon.config.ai{:api-key-variable "DEEPSEEK_API_KEY", :chars-per-token-prior
    3.2, :endpoint "https://api.deepseek.com/chat/completions", :max-tokens
    65536, :model "deepseek-v4-flash", :no-provider true, :prompt-token-budget
    32768, :thinking :disabled, :timeout-ms 180000} #:seon.config.ai.retry{:base-delay-ms
    500, :jitter-fraction 0.25, :maximum-delay-ms 4000, :maximum-retries
    2, :maximum-total-delay-ms 3000, :multiplier 2.0} #:seon.config.eval{:time-limit-ms
    10000} #:seon.config.run{:max-episode-runs 20} #:seon.config.agent{:turn-completion-backstop-ms
    600000} #:seon.config.ai.backup{:model "deepseek/deepseek-v4-flash-20260731"}
  #:my.agent{:turns-left 19}]
```

## Notes

Reverse-ref pull: this agent's notes, including an empty result.

```clojure
;; I should read my saved notes; an empty read will observe the first note I add.
(seon.db/pull
  '[{:my.note/_agent [:my.note/id :my.note/content {:my.note/about [:my.plan.item/id]}]}]
  [:seon.agent/id "juniper"])
```

133 form bytes; **3 output UTF-8 bytes**. Complete evaluation evidence: {:attribute-level 2, :index-patterns 3}.

```clojure
nil
```

## Namespace

Dir: namespace declarations as data; inspect unknown attribute candidates before choosing keys.

```clojure
;; I should inspect what I have defined and which schemas I declared.
(dir my.agents.juniper)
```

23 form bytes; **385 output UTF-8 bytes**. Complete evaluation evidence: {:attribute-level 2, :index-patterns 3}.

```clojure
[#:seon.schema{:form ":int", :key :example/amount} #:seon.schema{:form ":string",
    :key :example/customer} #:seon.schema{:form "[:string #:seon.db{:identity true}]",
    :key :example/order} #:seon.schema{:form "[:map #:seon.db{:attributes true} [:example/order :example/order] [:example/amount :example/amount] [:example/customer :example/customer]]",
    :key :example/order-row}]
```

## Namespace counts

Q: aggregate the facts under the declared attributes.

```clojure
;; I should count the facts under my declared attributes.
(seon.db/q
  '[:find ?attribute (count ?entity) :in $ [?attribute ...] :where [?entity ?attribute _]]
  [:example/amount :example/customer :example/order])
```

155 form bytes; **62 output UTF-8 bytes**. Complete evaluation evidence: {:attribute-level 2, :index-patterns 3}.

```clojure
[[:example/order 4] [:example/customer 4] [:example/amount 4]]
```

## Runtime

Pull: the runtime component, turn transaction refs, trigger, and listens; the wildcard trigger makes evidence attribute-level.

```clojure
;; I should follow my runtime's owner ref before pulling its turns, trigger, and listens.
(seon.db/pull
  '[{:seon.agent/runtime
     [{:seon.runtime/turns
       [:seon.turn/id
        {:seon.turn/opened-tx [:db/txInstant]}
        {:seon.turn/closed-tx [:db/txInstant]}]}
      {:seon.runtime/trigger [*]}
      {:seon.runtime/listens [:seon.listen/attribute :seon.listen/entity :seon.listen/value]}]}]
  [:seon.agent/id "juniper"])
```

344 form bytes; **631 output UTF-8 bytes**. Complete evaluation evidence: {:attribute-level 3, :index-patterns 2}.

```clojure
#:seon.agent{:runtime #:seon.runtime{:trigger {:db/id 36693, :seon.message/content
      "Which customer has the largest total? Add an order of 40 for them and tell me the new total.",
      :seon.message/from #:db{:id 36299}, :seon.message/id "7e09bc39", :seon.message/inbox
      #:db{:id 36503}, :seon.message/to #:db{:id 36503}}, :turns [#:seon.turn{:closed-tx
        #:db{:txInstant #inst "2026-09-10T01:48:57.500-00:00"}, :id "2b4e991b596f",
        :opened-tx #:db{:txInstant #inst "2026-09-10T01:48:57.500-00:00"}}
      #:seon.turn{:id "93959e14d8d4", :opened-tx #:db{:txInstant #inst "2026-09-10T01:48:57.805-00:00"}}]}}
```

## History

Q joins the runtime's turns to evaluations; map selects stored source and ordinal. The prompt itself is the history block, whose AI concern emits nothing.

```clojure
;; I should inspect my stored evaluations only when needed; the prompt already is my history.
(->> (seon.eval/of-agent) (mapv #(select-keys % [:seon.cluster.eval/ordinal :seon.cluster.eval/source])))
```

105 form bytes; **2130 output UTF-8 bytes**. Complete evaluation evidence: {:attribute-level 3, :index-patterns 3}.

```clojure
vector 9 items, depth 0 [#:seon.cluster.eval{:ordinal 0, :source "(help)"}
  #:seon.cluster.eval{:ordinal 1, :source "(seon.db/pull\n  '[:seon.agent/id {:seon.agent/namespace [:seon.ns/name {"… 67 more characters of 139; bounded by :seon.render.profile/token-budget; requery refused: the value has no result handle or entity identity at path [1 :seon.cluster.eval/source] offset 72 with :seon.render.profile/agent}
  #:seon.cluster.eval{:ordinal 2, :source "(seon.db/pull\n  '[{:seon.agent/plan\n     [:my.plan/objective\n      {:my."… 346 more characters of 418; bounded by :seon.render.profile/token-budget; requery refused: the value has no result handle or entity identity at path [2 :seon.cluster.eval/source] offset 72 with :seon.render.profile/agent}
  #:seon.cluster.eval{:ordinal 3, :source "(seon.db/pull\n  '[{:seon.message/_inbox\n     [:seon.message/id :seon.mes"… 83 more characters of 155; bounded by :seon.render.profile/token-budget; requery refused: the value has no result handle or entity identity at path [3 :seon.cluster.eval/source] offset 72 with :seon.render.profile/agent}
  #:seon.cluster.eval{:ordinal 4, :source "(seon.agent/effective-settings)"}
  #:seon.cluster.eval{:ordinal 5, :source "(seon.db/pull\n  '[{:my.note/_agent [:my.note/id :my.note/content {:my.no"… 61 more characters of 133; bounded by :seon.render.profile/token-budget; requery refused: the value has no result handle or entity identity at path [5 :seon.cluster.eval/source] offset 72 with :seon.render.profile/agent}
  #:seon.cluster.eval{:ordinal 6, :source "(dir my.agents.juniper)"} #:seon.cluster.eval{:ordinal
    7, :source "(seon.db/q\n  '[:find ?attribute (count ?entity) :in $ [?attribute ...] :"… 83 more characters of 155; bounded by :seon.render.profile/token-budget; requery refused: the value has no result handle or entity identity at path [7 :seon.cluster.eval/source] offset 72 with :seon.render.profile/agent}
  … 1 more children of 9; bounded by :seon.render.profile/token-budget; requery refused: the value has no result handle or entity identity at path [] offset 8 with :seon.render.profile/agent]
```

## Faults at root

Q with inner pull: filter by repair steward and shape matching faults. Root receives this at turn 0; an ordinary agent receives it only when a fault is routed to it.

```clojure
;; I should inspect faults routed to me as steward; fixing them is my job.
(seon.db/q
  '[:find
    [(pull ?f [:seon.error/kind :seon.error/message :seon.instrument/fn]) ...]
    :where
    [?f :seon.error/steward [:seon.agent/id "root"]]])
```

240 form bytes; **2 output UTF-8 bytes**. Complete evaluation evidence: {:attribute-level 3, :index-patterns 2}.

```clojure
[]
```

## Root's agents

Q with inner pull: select agents and shape their current work.

```clojure
;; I should review every agent and its current work with a filtered, shaped query.
(seon.db/q
  '[:find
    [(pull
       ?agent
       [:seon.agent/id {:seon.agent/plan [{:my.plan/current-step [:my.plan.item/title]}]}])
     ...]
    :where
    [?agent :seon.agent/id]])
```

271 form bytes; **136 output UTF-8 bytes**. Complete evaluation evidence: {:attribute-level 3, :index-patterns 2}.

```clojure
[#:seon.agent{:id "juniper", :plan #:my.plan{:current-step #:my.plan.item{:title
        "Query the orders"}}} #:seon.agent{:id "root"}]
```

## Customer totals

Q: group customer values and aggregate amounts.

```clojure
;; I should group customer values and sum amounts with q.
(seon.db/q
  '[:find
    ?customer
    (sum ?amount)
    :where
    [?e :example/customer ?customer]
    [?e :example/amount ?amount]])
```

135 form bytes; **35 output UTF-8 bytes**. Complete evaluation evidence: {:attribute-level 2, :index-patterns 3}.

```clojure
[["Ada" 115] ["Bea" 100] ["Cy" 40]]
```

## Add a step

Speculative write on the landed schema; the following read receives its db-after.

```clojure
;; I should update the existing component by db/id; an identity-less nested map silently replaces it.
(let [title "Verify customer totals"
      plan (get
             (seon.db/pull
               '[{:seon.agent/plan [:db/id {:my.plan/steps [:my.plan.item/position]}]}]
               [:seon.agent/id "juniper"])
             :seon.agent/plan)]
  (seon.db/transact!
    [{:db/id (:db/id plan),
      :my.plan/steps
      [{:my.plan.item/id (seon.id/id title 8),
        :my.plan.item/title title,
        :my.plan.item/position
        (inc (reduce max -1 (map :my.plan.item/position (:my.plan/steps plan))))}]}]))
```

512 form bytes; **385 output UTF-8 bytes**.

```clojure
#:seon.db{:tx 536870991, :datoms [[536870991 :db/txInstant #inst "2026-09-10T01:50:26.876-00:00" true] [[:my.plan.item/id "ba37cf26"] :my.plan.item/id "ba37cf26" true] [[:my.plan.item/id "ba37cf26"] :my.plan.item/title "Verify customer totals" true] [[:my.plan.item/id "ba37cf26"] :my.plan.item/position 6 true] [[:my.plan/agent [:seon.agent/id "juniper"]] :my.plan/steps 36735 true]]}
```

## Complete a step

Speculative write on the landed schema; the following read receives its db-after.

```clojure
;; I have seen the result; with no clock function I record completion through datomic.tx.
(seon.db/transact!
  [[:db/add [:my.plan.item/id "juniper/query"] :my.plan.item/completed-tx "datomic.tx"]])
```

108 form bytes; **183 output UTF-8 bytes**.

```clojure
#:seon.db{:tx 536870992, :datoms [[536870992 :db/txInstant #inst "2026-09-10T01:50:26.877-00:00" true] [[:my.plan.item/id "juniper/query"] :my.plan.item/completed-tx 536870992 true]]}
```

## Make current

Speculative write on the landed schema; the following read receives its db-after.

```clojure
;; I should make the aggregate step current by the plan's owner identity.
(seon.db/transact!
  [[:db/add
    [:my.plan/agent [:seon.agent/id "juniper"]]
    :my.plan/current-step
    [:my.plan.item/id "juniper/aggregate"]]])
```

150 form bytes; **183 output UTF-8 bytes**.

```clojure
#:seon.db{:tx 536870993, :datoms [[536870993 :db/txInstant #inst "2026-09-10T01:50:26.878-00:00" true] [[:my.plan/agent [:seon.agent/id "juniper"]] :my.plan/current-step 36691 true]]}
```

## Remove a step

Speculative write on the landed schema; the following read receives its db-after.

```clojure
;; I should remove the child and incoming refs with retractEntity; retract alone removes only one fact.
(seon.db/transact!
  [[:db.fn/retractEntity [:my.plan.item/id (seon.id/id "Verify customer totals" 8)]]])
```

105 form bytes; **389 output UTF-8 bytes**.

```clojure
#:seon.db{:tx 536870994, :datoms [[536870994 :db/txInstant #inst "2026-09-10T01:50:26.879-00:00" true] [[:my.plan.item/id "ba37cf26"] :my.plan.item/id "ba37cf26" false] [[:my.plan.item/id "ba37cf26"] :my.plan.item/position 6 false] [[:my.plan.item/id "ba37cf26"] :my.plan.item/title "Verify customer totals" false] [[:my.plan/agent [:seon.agent/id "juniper"]] :my.plan/steps 36735 false]]}
```

## Send a message

Speculative write on the landed schema; the following read receives its db-after.

```clojure
;; I should set inbox as well as to so the new message wakes its recipient.
(seon.db/transact!
  [{:seon.message/id "c00cb001",
    :seon.message/to [:seon.agent/id "root"],
    :seon.message/inbox [:seon.agent/id "root"],
    :seon.message/from [:seon.agent/id "juniper"],
    :seon.message/content "Ada totals 115."}])
```

244 form bytes; **431 output UTF-8 bytes**.

```clojure
#:seon.db{:tx 536870995, :datoms [[536870995 :db/txInstant #inst "2026-09-10T01:50:26.880-00:00" true] [[:seon.message/id "c00cb001"] :seon.message/id "c00cb001" true] [[:seon.message/id "c00cb001"] :seon.message/to 36299 true] [[:seon.message/id "c00cb001"] :seon.message/inbox 36299 true] [[:seon.message/id "c00cb001"] :seon.message/from 36503 true] [[:seon.message/id "c00cb001"] :seon.message/content "Ada totals 115." true]]}
```

## Answer a message

Speculative write on the landed schema; the following read receives its db-after.

```clojure
;; I should link my answer and clear the question's inbox edge in the same transaction.
(let [question (-> (seon.db/pull
                     '[{:seon.message/_inbox [:seon.message/id]}]
                     [:seon.agent/id "juniper"])
                :seon.message/_inbox
                first
                :seon.message/id)]
  (seon.db/transact!
    [{:seon.message/id "c00cb002",
      :seon.message/to [:seon.agent/id "root"],
      :seon.message/inbox [:seon.agent/id "root"],
      :seon.message/from [:seon.agent/id "juniper"],
      :seon.message/content "Ada totals 115 before the additional order.",
      :seon.message/about [:seon.message/id question]}
     [:db/add [:seon.message/id question] :seon.message/read-tx "datomic.tx"]
     [:db/retract [:seon.message/id question] :seon.message/inbox [:seon.agent/id "juniper"]]]))
```

754 form bytes; **655 output UTF-8 bytes**.

```clojure
#:seon.db{:tx 536870996, :datoms [[536870996 :db/txInstant #inst "2026-09-10T01:50:26.882-00:00" true] [[:seon.message/id "c00cb002"] :seon.message/id "c00cb002" true] [[:seon.message/id "c00cb002"] :seon.message/to 36299 true] [[:seon.message/id "c00cb002"] :seon.message/inbox 36299 true] [[:seon.message/id "c00cb002"] :seon.message/from 36503 true] [[:seon.message/id "c00cb002"] :seon.message/content "Ada totals 115 before the additional order." true] [[:seon.message/id "c00cb002"] :seon.message/about 36693 true] [[:seon.message/id "7e09bc39"] :seon.message/read-tx 536870996 true] [[:seon.message/id "7e09bc39"] :seon.message/inbox 36503 false]]}
```

## Change a setting

Speculative write on the landed schema; the following read receives its db-after.

```clojure
;; I should address my settings component by identity to preserve its other overrides.
(seon.db/transact!
  [{:seon.config/agent [:seon.agent/id "juniper"], :seon.config.eval/time-limit-ms 2500}])
```

109 form bytes; **196 output UTF-8 bytes**.

```clojure
#:seon.db{:tx 536870997, :datoms [[536870997 :db/txInstant #inst "2026-09-10T01:50:26.883-00:00" true] [[:seon.config/agent [:seon.agent/id "juniper"]] :seon.config.eval/time-limit-ms 2500 true]]}
```

## Declare a listen

Speculative write on the landed schema; the following read receives its db-after.

```clojure
;; I should add an attribute pattern to the runtime's listens.
(seon.db/transact!
  [{:seon.runtime/agent [:seon.agent/id "juniper"],
    :seon.runtime/listens [{:seon.listen/attribute :example/amount}]}])
```

142 form bytes; **240 output UTF-8 bytes**.

```clojure
#:seon.db{:tx 536870998, :datoms [[536870998 :db/txInstant #inst "2026-09-10T01:50:26.884-00:00" true] [36738 :seon.listen/attribute :example/amount true] [[:seon.runtime/agent [:seon.agent/id "juniper"]] :seon.runtime/listens 36738 true]]}
```

## Transact a note

Speculative write on the landed schema; the following read receives its db-after.

```clojure
;; I should save the observed total and link the note to the completed query step.
(seon.db/transact!
  [{:my.note/id "juniper/orders-observed",
    :my.note/agent [:seon.agent/id "juniper"],
    :my.note/about [:my.plan.item/id "juniper/query"],
    :my.note/content "Ada totals 115 before the additional order."}])
```

233 form bytes; **435 output UTF-8 bytes**.

```clojure
#:seon.db{:tx 536870999, :datoms [[536870999 :db/txInstant #inst "2026-09-10T01:50:26.885-00:00" true] [[:my.note/id "juniper/orders-observed"] :my.note/id "juniper/orders-observed" true] [[:my.note/id "juniper/orders-observed"] :my.note/agent 36503 true] [[:my.note/id "juniper/orders-observed"] :my.note/about 36687 true] [[:my.note/id "juniper/orders-observed"] :my.note/content "Ada totals 115 before the additional order." true]]}
```

## Reproduction

`context_page_probe_2026_09_09.clj` captures the prompt and executes the plan examples. `context_cookbook_landed_2026_09_09.clj` re-executes every read and the nine speculative writes. [Exact retained results](context_cookbook_landed_2026_09_09.edn) include source/output counts and read evidence. The earlier default-only observations remain in `context_cookbook_rechecked_2026_09_09.edn`; their proposed schema is historical.
