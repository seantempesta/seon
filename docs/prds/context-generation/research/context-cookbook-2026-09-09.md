---
type: research
status: working
tags: [agent-context, repl, render]
---

# Context cookbook — executed 2026-09-09

Read end to end: AGENTS.md (its opening copies turn PRD §10), the data chart r2 including its roadmap, raw-data-forms-probe-2026-09-09.md, and turn PRD §18–§18c. The plan README and working edge ground this slice.

Surface: MCP, cluster `default`, JVM mode, explicit `(seon.operator/connection "default")`; each read receives `database = @connection`. Basis 536871165. No database write was committed. Counts measure exact `pr-str` results, without a REPL envelope; they are not yet provider-prompt byte counts. The checked-in probe and EDN retain the complete forms and results.

The target schema is absent on default. The writes below actually ran through `datahike.api/with` on a speculative value derived from default, after the probe installed the explicitly listed proposed attributes there. These establish dependency semantics, not production schema validation, wake delivery, or live target-schema adoption. Event ids are fixed probe inputs; real messages mint fresh event ids.

## Read choice and evidence

Pull describes one known entity or a known set, including nested and reverse refs. Use q for value filters, joins, and aggregates; combine q with inner pull when both filtering and shaping. The live reverse message pull recorded index patterns and returned the incoming messages in one form. Pattern-only aggregate q also recorded index patterns. Inner pull and not recorded attribute-level evidence: correct but coarse. Explicit finite selectors recorded index patterns; wildcard/recursive selectors cannot make that claim. Source: src/seon/db.clj:335–423. Index-pattern presence means constraints at each pattern, not a fully joined result dependency. An invalid read is a refusal, never an empty healthy block.

## Dependency ledger

Datahike transaction.cljc:640 (identity upsert), :738 (nested maps), :785 (cardinality-one replacement), :997 and :1059 (retractEntity versus retract); pull_api.cljc:304 (reverse refs and component collections). First-party idioms: seon.plan/render-plan-html resolves the plan owner; seon.agent/settings! consumes the full system transaction report; seon.db:335–423 captures read patterns. `datahike.api` has no entid function: use an identity pull.

## Transaction report proposal — pending db.clj ownership

`src/seon/db.clj` is held by transact-feedback. The executable `report-face` hunk in the adjacent probe is the proposed agent-facing projection: `{:seon.db/tx t :seon.db/datoms [[e a v added?] ...]}`. It resolves e to an installed unique-identity lookup ref, consulting db-before for deleted identities; tempids are already resolved in tx-data. The next read is the db-after; tx-data already says what changed. Neither db-before nor db-after belongs in shown text. Keep the full report for system callers such as seon.agent/settings! that still consume db-after. The first measurements below print numeric targets inside ref-valued identities; the final probe follows those identities recursively with cycle protection and records its actual result at the end.

## Verification boundary

The first batch exceeded the MCP 20-second request bound but completed and wrote its evidence file; a second session returned `(+ 1 1)` in 1 ms. No alternate transport was used. These probes committed no database writes. Default was never stopped, restarted, or reforked. Schema-dependent blocks and reseeding require the chart data lane's landing and the owner's batched reset; RESET NEEDED when that schema commit is known.

The later platform gate on HEAD `3d13aa0f7` failed two blob-reachability tests:
83 tests / 490 assertions, two failures, both confirmed in isolated workers.
A HEAD-only fast snapshot (`--paths AGENTS.md`, no snapshot differences) reproduced
the same failures: 12 tests / 62 assertions. This excludes the cookbook changes;
the exact boundary is [recorded as an issue](../../../seon/issues/platform-blob-reachability-fails-at-3d13aa0f7.md).
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

## Identity

```clojure
;; I should know who I am and where my forms run.
(seon.db/pull database (quote [:seon.agent/id #:seon.agent{:namespace [:seon.ns/name #:seon.ns{:steward [:seon.agent/id]}]}]) [:seon.agent/id "juniper"])
```

Actual JVM result (112 UTF-8 bytes; evidence [:index-patterns]):

```clojure
#:seon.agent{:id "juniper", :namespace #:seon.ns{:name my.agents.juniper, :steward #:seon.agent{:id "juniper"}}}
```

## Plan

```clojure
;; I should read the plan component, not select its attributes on the agent; its steps are a set, so I order them by position.
(update (:seon.agent/plan (seon.db/pull database (quote [#:seon.agent{:plan [:my.plan/objective #:my.plan{:current-step [:my.plan.item/id]} #:my.plan{:steps [:my.plan.item/id :my.plan.item/title :my.plan.item/expected-result :my.plan.item/position :my.plan.item/completed-at #:my.plan.item{:needs [:my.plan.item/id]}]}]}]) [:seon.agent/id "juniper"])) :my.plan/steps (fn* [p1__3449884#] (vec (sort-by :my.plan.item/position p1__3449884#))))
```

Actual JVM result (1392 UTF-8 bytes; evidence [:index-patterns]):

```clojure
#:my.plan{:objective "Which customer has the largest total? Add an order of 40 for them and tell me the new total.", :current-step #:my.plan.item{:id "juniper/query"}, :steps [#:my.plan.item{:id "juniper/query", :title "Query the orders", :expected-result "I have read the order ids, customers, and amounts.", :position 0} #:my.plan.item{:id "juniper/aggregate", :title "Find the customer with the largest total", :expected-result "A grouped sum query identifies the customer and their total.", :position 1, :needs [#:my.plan.item{:id "juniper/query"}]} #:my.plan.item{:id "juniper/transact", :title "Add an order of 40 for that customer", :expected-result "The transaction result identifies the new order.", :position 2, :needs [#:my.plan.item{:id "juniper/aggregate"}]} #:my.plan.item{:id "juniper/requery", :title "Read the customer's new total", :expected-result "A fresh grouped sum query includes the new order.", :position 3, :needs [#:my.plan.item{:id "juniper/transact"}]} #:my.plan.item{:id "juniper/reply", :title "Tell root the customer and new total", :expected-result "The sent message contains the customer and verified new total.", :position 4, :needs [#:my.plan.item{:id "juniper/requery"}]} #:my.plan.item{:id "juniper/done", :title "Finish the session", :expected-result "All preceding plan items are complete.", :position 5, :needs [#:my.plan.item{:id "juniper/reply"}]}]}
```

## Settings

```clojure
;; I should pull my overrides; omitted settings inherit defaults, and turns left is derived rather than a stored attribute.
(seon.db/pull database (quote [#:seon.agent{:settings [:seon.config.ai/model :seon.config.ai/no-provider :seon.config.eval/time-limit-ms :seon.config.run/max-episode-runs]}]) [:seon.agent/id "juniper"])
```

Actual JVM result (135 UTF-8 bytes; evidence [:index-patterns]):

```clojure
#:seon.agent{:settings {:seon.config.ai/no-provider true, :seon.config.eval/time-limit-ms 10000, :seon.config.run/max-episode-runs 20}}
```

## Runtime target

```clojure
;; I should inspect unknown-attribute candidates before guessing a runtime attribute.
(seon.db/pull database (quote [#:seon.agent{:runtime [#:seon.runtime{:turn [:seon.turn/id]} #:seon.runtime{:listens [:seon.listen/attribute]}]}]) [:seon.agent/id "juniper"])
```

Actual JVM result (467 UTF-8 bytes; evidence [:attribute-level]):

```clojure
{:seon.db/invalid-read true, :seon.error/kind :seon.db/invalid-read, :seon.error/message "Bad entity attribute :seon.agent/runtime at (resolve-datom db 35984 :seon.agent/runtime nil nil), not defined in current schema", :seon.error/data #:seon.db{:operation :datahike.pull/result, :exception-class "clojure.lang.ExceptionInfo", :dependency-data {:error :transact/schema, :attribute :seon.agent/runtime, :context (resolve-datom db 35984 :seon.agent/runtime nil nil)}}}
```

## Runtime current

```clojure
;; I should find my open turns by filtering for the absence of closed-at.
(seon.db/q (quote [:find [(pull ?t [:seon.turn/id :seon.turn/opened-at #:seon.turn{:trigger [:seon.cluster.message/id]}]) ...] :where [?t :seon.turn/agent [:seon.agent/id "juniper"]] (not [?t :seon.turn/closed-at])]) database)
```

Actual JVM result (2 UTF-8 bytes; evidence [:attribute-level]):

```clojure
[]
```

## Messages

```clojure
;; I should follow incoming messages with a reverse-ref pull on myself.
(get (seon.db/pull database (quote [#:seon.cluster.message{:_to [:seon.cluster.message/id :seon.cluster.message/content #:seon.cluster.message{:from [:seon.agent/id]}]}]) [:seon.agent/id "juniper"]) :seon.cluster.message/_to [])
```

Actual JVM result (520 UTF-8 bytes; evidence [:index-patterns]):

```clojure
[#:seon.cluster.message{:id "juniper/largest-customer", :content "Which customer has the largest total? Add an order of 40 for them and tell me the new total.", :from #:seon.agent{:id "root"}} #:seon.cluster.message{:id "c5666920b0de", :content "The turn :seon.agent/turn-completion-backstop failed with :seon.agent/turn-completion-backstop. Inspect error 89109b89-80d1-4026-9e6f-d2018dc6b570; the proc survived and no work was re-executed. Signature: 743ae70c02e40db2ac7f3ebe12b7e7a4044ba9944cd8e6eb1a2cfc27f4298eb6."}]
```

## History

```clojure
;; I should inspect stored source and shown text only when needed; my prompt already contains my history.
(seon.db/q (quote [:find ?ordinal ?source :where [?t :seon.turn/agent [:seon.agent/id "juniper"]] [?e :seon.cluster.eval/run ?t] [?e :seon.cluster.eval/ordinal ?ordinal] [?e :seon.cluster.eval/source ?source]]) database)
```

Actual JVM result (318 UTF-8 bytes; evidence [:index-patterns]):

```clojure
#{[3 "(my.message/inbox)"] [0 "(help)"] [1 "(my.agent/identity)"] [1 "(my.message/inbox)"] [5 "(seon.db/q (quote [:find ?attribute (count ?entity) :in $ [?attribute ...] :where [?entity ?attribute _]]) [:example/amount :example/customer :example/order])"] [0 "(+ 1 1)"] [4 "(my.agent/settings)"] [2 "(my.plan/items)"]}
```

## Faults root

```clojure
;; I should fix faults routed to me as steward before other work.
(seon.db/q (quote [:find [(pull ?f [:seon.error/kind :seon.error/message :seon.instrument/fn]) ...] :where [?f :seon.error/steward [:seon.agent/id "root"]]]) database)
```

Actual JVM result (2 UTF-8 bytes; evidence [:attribute-level]):

```clojure
[]
```

## Faults juniper

```clojure
;; I should distinguish faults routed to me from faults that happened to me.
(seon.db/q (quote [:find [(pull ?f [:seon.error/kind :seon.error/message :seon.instrument/fn]) ...] :where [?f :seon.error/steward [:seon.agent/id "juniper"]]]) database)
```

Actual JVM result (2 UTF-8 bytes; evidence [:attribute-level]):

```clojure
[]
```

## Notes

```clojure
;; I should read my saved notes, including an empty result so later notes can refresh this read.
(get (seon.db/pull database (quote [#:my.note{:_agent [:my.note/id :my.note/content #:my.note{:about [:my.plan.item/id]}]}]) [:seon.agent/id "juniper"]) :my.note/_agent [])
```

Actual JVM result (2 UTF-8 bytes; evidence [:index-patterns]):

```clojure
[]
```

## Namespace

```clojure
;; I should inspect the declarations owned by my namespace before choosing attributes.
(seon.db/pull database (quote [:seon.ns/name #:seon.schema{:_ns [:seon.schema/key :seon.schema/form]} #:seon.fn{:_ns [:seon.fn/sym :seon.fn/doc]}]) [:seon.ns/name (quote my.agents.juniper)])
```

Actual JVM result (425 UTF-8 bytes; evidence [:index-patterns]):

```clojure
{:seon.ns/name my.agents.juniper, :seon.schema/_ns [#:seon.schema{:key :example/order, :form "[:string #:seon.db{:identity true}]"} #:seon.schema{:key :example/amount, :form ":int"} #:seon.schema{:key :example/customer, :form ":string"} #:seon.schema{:key :example/order-row, :form "[:map #:seon.db{:attributes true} [:example/order :example/order] [:example/amount :example/amount] [:example/customer :example/customer]]"}]}
```

## Namespace counts

```clojure
;; I should count facts with q; a pull describes entities but does not aggregate them.
(seon.db/q (quote [:find ?a (count ?e) :in $ [?a ...] :where [?e ?a _]]) database [:example/order :example/customer :example/amount])
```

Actual JVM result (62 UTF-8 bytes; evidence [:index-patterns]):

```clojure
[[:example/amount 4] [:example/customer 4] [:example/order 4]]
```

## Root agents

```clojure
;; I should select all agents with q and shape each record with inner pull.
(seon.db/q (quote [:find [(pull ?a [:seon.agent/id #:seon.agent{:plan [#:my.plan{:current-step [:my.plan.item/title]}]}]) ...] :where [?a :seon.agent/id]]) database)
```

Actual JVM result (128 UTF-8 bytes; evidence [:attribute-level]):

```clojure
[#:seon.agent{:id "juniper", :plan #:my.plan{:current-step #:my.plan.item{:title "Query the orders"}}} #:seon.agent{:id "root"}]
```

## Orders

```clojure
;; I should read order ids, customers, and amounts before completing the query step.
(seon.db/q (quote [:find ?id ?customer ?amount :where [?e :example/order ?id] [?e :example/customer ?customer] [?e :example/amount ?amount]]) database)
```

Actual JVM result (66 UTF-8 bytes; evidence [:index-patterns]):

```clojure
#{["c1" "Cy" 40] ["b1" "Bea" 100] ["a1" "Ada" 60] ["a2" "Ada" 55]}
```

## Customer totals

```clojure
;; I should group by customer and sum amounts rather than add the rows myself.
(seon.db/q (quote [:find ?customer (sum ?amount) :where [?e :example/customer ?customer] [?e :example/amount ?amount]]) database)
```

Actual JVM result (35 UTF-8 bytes; evidence [:index-patterns]):

```clojure
[["Ada" 115] ["Bea" 100] ["Cy" 40]]
```

## Speculative writes

## Component ordering implementation, first code slice

`src/seon/render/value.clj` sorts a cardinality-many component collection when
all maps carry the same numeric namespaced `position` key. A partial collection
keeps its order; an ordinary vector outside a component keeps its order. The
renderer derives component membership from the handed database schema. The
canonical fixture regression covers vector and set inputs and both exclusions.

`SEON_TEST_WORKERS=3 bin/test-fast --paths src/seon/render/value.clj test/seon/render/value_test.clj -- seon.render.value-test`:
24 tests / 113 assertions, green.
The corresponding `bin/test --paths … -- seon.render.value-test` isolated gate:
24 tests / 117 assertions, green. `bin/test --paths … --platform`: exit 0.

Live proof on default used a hot-reloaded `seon.render.value` namespace, followed
by `seon.instrument/apply!` with the database projection. This is not an adoption
claim: the edit-hook publication had timed out. Before and after shown text is
373 UTF-8 bytes; positions changed from `0,2,1,4,3,5` to `0,1,2,3,4,5`.
The observation pulled only item identity and position through the plan
component, then called the real value renderer with the effective render profile.

Remaining schema scope is an owner decision under AGENTS.md's design gate:
current-schema raw forms now, the complete schema work here, or target patches
pending the data lane. The absent schema is an implementation prerequisite;
the concurrent db.clj transaction work remains protected.

The follow-up preserves the set type when the live input is a set and avoids
traversing an uncounted component collection to discover positions. Its regression
uses a lazy tail that throws if visited. Final value-renderer isolated gate:
24 tests / 120 assertions, green, with `SEON_TEST_WORKERS=3`. The platform failure
above was independently reproduced at HEAD without this slice's files.

The first set regression used names whose lexical order matched their positions;
a live probe with `cookbook/a` at 2 and `cookbook/b` at 1 falsified its coverage.
The print fitter and emitter sorted the set again. The corrected regression puts
lexical and position order in opposition. The value renderer now marks its
ordered set node, and both existing print stages preserve that order. Ordinary
unordered sets still use canonical lexical order. Default's hot-reloaded and
re-armed functions returned **118 bytes**, `cookbook/b` before `cookbook/a`,
with both `:position-order?` and `:set-preserved?` true;
[the actual result is retained](context_cookbook_set_2026_09_09.edn).
The print regression fixture also used the retired serialized-result field;
it now takes `:seon.sci.admit/print-node` from `admit-value`, as the existing
cross-process print test already does. No production admission behavior changed.
Corrected combined fast gate: **42 tests / 198 assertions**, green. Isolated
`seon.render.value-test seon.print-test` gate: **42 tests / 202 assertions**,
green. The final platform gate above includes the set-node schema and both
print stages. All commands set `SEON_TEST_WORKERS=3`.

## Add a step

```clojure
;; I should upsert by the plan's identity; an identity-less nested map would silently replace the component.
(seon.db/transact! [#:my.plan{:agent [:seon.agent/id "juniper"], :steps [#:my.plan.item{:id "juniper/verify", :title "Verify the new total", :done-when "The fresh sum includes the new order.", :position 6}]}])
```

Executed in the speculative chain: `(datahike.api/with database [#:my.plan{:agent [:seon.agent/id "juniper"], :steps [#:my.plan.item{:id "juniper/verify", :title "Verify the new total", :done-when "The fresh sum includes the new order.", :position 6}]}])`.

Actual proposed report projection (493 UTF-8 bytes):

```clojure
#:seon.db{:tx 536871168, :datoms [[536871168 :db/txInstant #inst "2026-09-09T21:23:29.420-00:00" true] [[:my.plan.item/id "juniper/verify"] :my.plan.item/id "juniper/verify" true] [[:my.plan.item/id "juniper/verify"] :my.plan.item/title "Verify the new total" true] [[:my.plan.item/id "juniper/verify"] :my.plan.item/done-when "The fresh sum includes the new order." true] [[:my.plan.item/id "juniper/verify"] :my.plan.item/position 6 true] [[:my.plan/agent 35984] :my.plan/steps 43901 true]]}
```

## Complete a step

```clojure
;; I have seen the query result; with no clock function, I record completion as a ref to datomic.tx.
(seon.db/transact! [[:db/add [:my.plan.item/id "juniper/query"] :my.plan.item/completed-tx "datomic.tx"]])
```

Executed in the speculative chain: `(datahike.api/with database [[:db/add [:my.plan.item/id "juniper/query"] :my.plan.item/completed-tx "datomic.tx"]])`.

Actual proposed report projection (183 UTF-8 bytes):

```clojure
#:seon.db{:tx 536871169, :datoms [[536871169 :db/txInstant #inst "2026-09-09T21:23:29.421-00:00" true] [[:my.plan.item/id "juniper/query"] :my.plan.item/completed-tx 536871169 true]]}
```

## Make current

```clojure
;; I should make the aggregate step current after completing the query.
(seon.db/transact! [[:db/add [:my.plan/agent [:seon.agent/id "juniper"]] :my.plan/current-step [:my.plan.item/id "juniper/aggregate"]]])
```

Executed in the speculative chain: `(datahike.api/with database [[:db/add [:my.plan/agent [:seon.agent/id "juniper"]] :my.plan/current-step [:my.plan.item/id "juniper/aggregate"]]])`.

Actual proposed report projection (162 UTF-8 bytes):

```clojure
#:seon.db{:tx 536871170, :datoms [[536871170 :db/txInstant #inst "2026-09-09T21:23:29.422-00:00" true] [[:my.plan/agent 35984] :my.plan/current-step 36399 true]]}
```

## Remove a step

```clojure
;; I should use retractEntity to remove the item and incoming refs; retracting only the component edge leaves an orphan.
(seon.db/transact! [[:db.fn/retractEntity [:my.plan.item/id "juniper/verify"]]])
```

Executed in the speculative chain: `(datahike.api/with database [[:db.fn/retractEntity [:my.plan.item/id "juniper/verify"]]])`.

Actual proposed report projection (498 UTF-8 bytes):

```clojure
#:seon.db{:tx 536871171, :datoms [[536871171 :db/txInstant #inst "2026-09-09T21:23:29.423-00:00" true] [[:my.plan.item/id "juniper/verify"] :my.plan.item/done-when "The fresh sum includes the new order." false] [[:my.plan.item/id "juniper/verify"] :my.plan.item/id "juniper/verify" false] [[:my.plan.item/id "juniper/verify"] :my.plan.item/position 6 false] [[:my.plan.item/id "juniper/verify"] :my.plan.item/title "Verify the new total" false] [[:my.plan/agent 35984] :my.plan/steps 43901 false]]}
```

## Send a message

```clojure
;; I should include an event identity when I transact a message to root.
(seon.db/transact! [#:seon.message{:id "c00cb001", :to [:seon.agent/id "root"], :from [:seon.agent/id "juniper"], :content "The query found Ada totals 115."}])
```

Executed in the speculative chain: `(datahike.api/with database [#:seon.message{:id "c00cb001", :to [:seon.agent/id "root"], :from [:seon.agent/id "juniper"], :content "The query found Ada totals 115."}])`.

Actual proposed report projection (384 UTF-8 bytes):

```clojure
#:seon.db{:tx 536871172, :datoms [[536871172 :db/txInstant #inst "2026-09-09T21:23:29.424-00:00" true] [[:seon.message/id "c00cb001"] :seon.message/id "c00cb001" true] [[:seon.message/id "c00cb001"] :seon.message/to 35918 true] [[:seon.message/id "c00cb001"] :seon.message/from 35984 true] [[:seon.message/id "c00cb001"] :seon.message/content "The query found Ada totals 115." true]]}
```

## Answer a message

```clojure
;; I should link the reply to the question and mark that question handled in the same transaction.
(seon.db/transact! [#:seon.message{:id "c00cb002", :to [:seon.agent/id "root"], :from [:seon.agent/id "juniper"], :content "I will add 40 and read the new total.", :about [:seon.message/id "c00cb000"]} [:db/add [:seon.message/id "c00cb000"] :seon.message/read-tx "datomic.tx"]])
```

Executed in the speculative chain: `(datahike.api/with database [#:seon.message{:id "c00cb002", :to [:seon.agent/id "root"], :from [:seon.agent/id "juniper"], :content "I will add 40 and read the new total.", :about [:seon.message/id "c00cb000"]} [:db/add [:seon.message/id "c00cb000"] :seon.message/read-tx "datomic.tx"]])`.

Actual proposed report projection (522 UTF-8 bytes):

```clojure
#:seon.db{:tx 536871173, :datoms [[536871173 :db/txInstant #inst "2026-09-09T21:23:29.425-00:00" true] [[:seon.message/id "c00cb002"] :seon.message/id "c00cb002" true] [[:seon.message/id "c00cb002"] :seon.message/to 35918 true] [[:seon.message/id "c00cb002"] :seon.message/from 35984 true] [[:seon.message/id "c00cb002"] :seon.message/content "I will add 40 and read the new total." true] [[:seon.message/id "c00cb002"] :seon.message/about 43900 true] [[:seon.message/id "c00cb000"] :seon.message/read-tx 536871173 true]]}
```

## Change a setting

```clojure
;; I should address my settings component by identity so I preserve its other overrides.
(seon.db/transact! [{:seon.config/agent [:seon.agent/id "juniper"], :seon.config.eval/time-limit-ms 2500}])
```

Executed in the speculative chain: `(datahike.api/with database [{:seon.config/agent [:seon.agent/id "juniper"], :seon.config.eval/time-limit-ms 2500}])`.

Actual proposed report projection (175 UTF-8 bytes):

```clojure
#:seon.db{:tx 536871174, :datoms [[536871174 :db/txInstant #inst "2026-09-09T21:23:29.426-00:00" true] [[:seon.config/agent 35984] :seon.config.eval/time-limit-ms 2500 true]]}
```

## Declare a listen

```clojure
;; I should add an attribute pattern to my runtime listens.
(seon.db/transact! [#:seon.runtime{:agent [:seon.agent/id "juniper"], :listens [#:seon.listen{:attribute :example/amount}]}])
```

Executed in the speculative chain: `(datahike.api/with database [#:seon.runtime{:agent [:seon.agent/id "juniper"], :listens [#:seon.listen{:attribute :example/amount}]}])`.

Actual proposed report projection (219 UTF-8 bytes):

```clojure
#:seon.db{:tx 536871175, :datoms [[536871175 :db/txInstant #inst "2026-09-09T21:23:29.427-00:00" true] [43904 :seon.listen/attribute :example/amount true] [[:seon.runtime/agent 35984] :seon.runtime/listens 43904 true]]}
```

## Transact a note

```clojure
;; I should save the verified query result as a note linked to its step.
(seon.db/transact! [#:my.note{:id "juniper/orders-observed", :agent [:seon.agent/id "juniper"], :about [:my.plan.item/id "juniper/query"], :content "Read four orders; next compute customer totals."}])
```

Executed in the speculative chain: `(datahike.api/with database [#:my.note{:id "juniper/orders-observed", :agent [:seon.agent/id "juniper"], :about [:my.plan.item/id "juniper/query"], :content "Read four orders; next compute customer totals."}])`.

Actual proposed report projection (439 UTF-8 bytes):

```clojure
#:seon.db{:tx 536871176, :datoms [[536871176 :db/txInstant #inst "2026-09-09T21:23:29.428-00:00" true] [[:my.note/id "juniper/orders-observed"] :my.note/id "juniper/orders-observed" true] [[:my.note/id "juniper/orders-observed"] :my.note/agent 35984 true] [[:my.note/id "juniper/orders-observed"] :my.note/about 36397 true] [[:my.note/id "juniper/orders-observed"] :my.note/content "Read four orders; next compute customer totals." true]]}
```

## Target shapes after the speculative writes

These reads execute on the final `:db-after` of the same nine-write `with` chain; they do not claim that target schema or wake behavior is installed on default.

### Runtime after declaring a listen

```clojure
;; I should verify that my runtime contains the listen I added.
(seon.db/pull database (quote [#:seon.agent{:runtime [#:seon.runtime{:listens [:seon.listen/attribute]}]}]) [:seon.agent/id "juniper"])
```

Actual result, 91 UTF-8 bytes:

```clojure
#:seon.agent{:runtime #:seon.runtime{:listens [#:seon.listen{:attribute :example/amount}]}}
```

### Target messages, reverse pull

```clojure
;; I should read root's incoming messages through seon.message/_to.
(seon.db/pull database (quote [#:seon.message{:_to [:seon.message/id :seon.message/content #:seon.message{:from [:seon.agent/id]} #:seon.message{:about [:seon.message/id]}]}]) [:seon.agent/id "root"])
```

Actual result, 287 UTF-8 bytes:

```clojure
#:seon.message{:_to [#:seon.message{:id "c00cb001", :content "The query found Ada totals 115.", :from #:seon.agent{:id "juniper"}} #:seon.message{:id "c00cb002", :content "I will add 40 and read the new total.", :from #:seon.agent{:id "juniper"}, :about #:seon.message{:id "c00cb000"}}]}
```

### Completion instant

```clojure
;; I should derive the completion instant from its transaction ref.
(seon.db/pull database (quote [:my.plan.item/id #:my.plan.item{:completed-tx [:db/txInstant]}]) [:my.plan.item/id "juniper/query"])
```

Actual result, 105 UTF-8 bytes:

```clojure
#:my.plan.item{:id "juniper/query", :completed-tx #:db{:txInstant #inst "2026-09-09T21:34:43.628-00:00"}}
```

### Removal verification

```clojure
;; I should verify the deleted item is absent, not merely detached from the plan.
(seon.db/pull database [:my.plan.item/id] [:my.plan.item/id "juniper/verify"])
```

Actual result, 3 UTF-8 bytes:

```clojure
nil
```

### Plan membership after removal

```clojure
;; I should see the original six steps and the new current step after removing my probe item.
(seon.db/pull database (quote [#:my.plan{:current-step [:my.plan.item/id]} #:my.plan{:steps [:my.plan.item/id :my.plan.item/position]}]) [:my.plan/agent [:seon.agent/id "juniper"]])
```

Actual result, 376 UTF-8 bytes:

```clojure
#:my.plan{:current-step #:my.plan.item{:id "juniper/aggregate"}, :steps [#:my.plan.item{:id "juniper/query", :position 0} #:my.plan.item{:id "juniper/transact", :position 2} #:my.plan.item{:id "juniper/aggregate", :position 1} #:my.plan.item{:id "juniper/reply", :position 4} #:my.plan.item{:id "juniper/requery", :position 3} #:my.plan.item{:id "juniper/done", :position 5}]}
```

### Handled question

```clojure
;; I should verify the question carries the transaction that handled it.
(seon.db/pull database (quote [:seon.message/id #:seon.message{:read-tx [:db/txInstant]}]) [:seon.message/id "c00cb000"])
```

Actual result, 95 UTF-8 bytes:

```clojure
#:seon.message{:id "c00cb000", :read-tx #:db{:txInstant #inst "2026-09-09T21:34:43.632-00:00"}}
```

### Settings preservation

```clojure
;; I should see my changed time limit alongside the untouched overrides.
(seon.db/pull database [:seon.config.eval/time-limit-ms :seon.config.ai/no-provider :seon.config.run/max-episode-runs] [:seon.config/agent [:seon.agent/id "juniper"]])
```

Actual result, 110 UTF-8 bytes:

```clojure
{:seon.config.eval/time-limit-ms 2500, :seon.config.ai/no-provider true, :seon.config.run/max-episode-runs 20}
```

### Saved note

```clojure
;; I should see my saved note linked to the query step.
(seon.db/pull database (quote [:my.note/id :my.note/content #:my.note{:about [:my.plan.item/id]}]) [:my.note/id "juniper/orders-observed"])
```

Actual result, 144 UTF-8 bytes:

```clojure
#:my.note{:id "juniper/orders-observed", :content "Read four orders; next compute customer totals.", :about #:my.plan.item{:id "juniper/query"}}
```

The report prototype now follows ref-valued identities recursively, with a visited-entity set for cycles. Actual executed result, 183 UTF-8 bytes:

```clojure
#:seon.db{:tx 536871194, :datoms [[536871194 :db/txInstant #inst "2026-09-09T21:34:50.015-00:00" true] [[:my.plan/agent [:seon.agent/id "juniper"]] :my.plan/current-step 36399 true]]}
```
