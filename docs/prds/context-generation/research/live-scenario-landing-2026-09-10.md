---
type: research
status: active
tags: [agent, context, test]
---

# Live scenario landing — 2026-09-10

The shared Juniper fixture now carries chart §17's seven-step scenario and
thirty-turn budget. Provider calls remain disabled; the paid run belongs to
the orchestrator.

Read end to end: AGENTS.md; chart PRD sections 16 and 17;
test/seon/context_blocks_fixture.clj; test/seon/loop_proof_test.clj;
docs/prds/context-generation/research/help_trial_2026_09_09.clj;
src/my/plan.clj, src/my/note.clj, src/my/test.clj, src/my/message.clj,
and src/my/agent.clj. Also read the live installer and help trial test.
The active roadmap entry and latest working-edge section agree that this
fixture precedes the paid scenario.

Dependency ledger: Datahike's :db.fn/call receives the writer's database
(reference-code/datahike/src/datahike/db/transaction.cljc:1152); fixture/seed!
uses this existing seam to replace the disposable facts. SCI init/fork
(reference-code/sci/src/sci/core.cljc:330,345) supplies the real context used
by test-support/fork-cluster-ctx and the existing virtual-turn proof.
No new execution mechanism or schema was introduced. my.test/run expands
its zero-argument form; my.message/send accepts namespaced request keys;
my.agent/done receives call-prepared custody in SCI, as the existing virtual
proof demonstrates. Plan and note writes use the existing transaction forms.

## Live evidence

Inherited default PID 23557 was alive and MCP runtime_status answered.
Unrelated working-tree edits were preserved. The initial MCP JVM pull
confirmed the old six-step plan and limit 20 before editing.

MCP JVM evaluation loaded the committed installer path and called
(juniper-fixture-2026-09-06/install! "default"). It returned turn
aa071259cfd8 in 14,145 ms. This proof uses explicitly loaded fixture Vars
and reseeded database facts in the existing default JVM; no restart or
refork was performed.

(juniper-fixture-2026-09-06/prompt "default") supplied the exact bytes below.
The plan has seven positioned items, the preceding-item needs chain,
juniper/read current, and root's exact message as its objective. Help and
the final criterion name (my.agent/done). The settings block shows
max-episode-runs 30 and no-provider true. The first generated settings
observation shows 30 turns left; later live turns may consume that budget.

GET `http://127.0.0.1:7994/ns/my.agents.juniper/debug?prompt=true` returned
HTTP 200, 74,833 bytes. HTML entity decoding found all seven IDs:
read 12, define 9, test 9, save 9, add 9, again 9, report 5 occurrences.
This is HTTP response evidence, not a browser paint claim.

## Verification

Initial working-tree fast run: 13 tests, 201 assertions, 2 failures and
1 error (exit 1). The error identified an additional fixture consumer in
test/seon/data_shapes_test.clj still using the removed first-step id.
That consumer now uses the new ids and derives membership counts and the
appended position from fixture/authored-plan. Two executable cookbook
probes also needed the id rename. These unprotected consumer edits follow
the assignment's instruction to update every consumer.

The two failures were the schema-redeclare child JVM rejecting incremental
publication: `Attribute :seon.test/usage expected :boolean, got
:seon.error/unknown.`, path `[0 :seon.test/usage]`, cause
`:malli.core/missing-key`, outer rule
`:seon.cluster.source/incremental-source-refused`. This matches the existing
[publication issue](../../../seon/issues/incremental-publication-refuses-test-usage.md).
The first isolated gate passed schema adoption in 200,714 ms, eliminating
that working-tree boundary without editing its owner. It reported 13 tests,
208 assertions, zero failures and one error: the old data-shapes reference
captured before its correction. The final snapshot includes that correction.
No protected publication or renderer owner was edited to bypass this boundary.

`rg` over all .clj files under test, src, and docs now finds no old plan
ids or old instruction text. Historical .edn, .txt, and Markdown evidence
still records the old prompt; those dated observations were not rewritten.
The active chart also retains old ids in its earlier plan examples,
outside this lane's section 17 status-line allowance. This is recorded in
[the chart-example issue](../../../seon/issues/chart-plan-examples-retain-old-juniper-step-ids.md).

Final path-limited gate: **13 tests, 216 assertions, zero failures and
zero errors**, exit 0. Coordinator and tests took 218 seconds; the
schema-adoption child took 153,278 ms. The successful gate removed its
isolated root. The last targeted fast iteration passed 6 data-shapes tests
and 42 assertions, zero failures/errors. `bin/test --platform` passed
**84 tests, 505 assertions, zero failures/errors**, exit 0, with 79 seconds
in coordinator/tests. It removed its successful root. No full suite ran.

The final gate command was:

```sh
bin/test --paths test/seon/context_blocks_fixture.clj test/seon/loop_proof_test.clj test/seon/help_trial_test.clj test/seon/data_shapes_test.clj docs/prds/context-generation/research/context_cookbook_landed_2026_09_09.clj docs/prds/context-generation/research/context_cookbook_probe_2026_09_09.clj docs/prds/context-generation/research/live-scenario-landing-2026-09-10.md -- seon.loop-proof-test seon.help-trial-test seon.data-shapes-test seon.schema-redeclare-test
```

The corrected isolated fast run passed schema adoption, loop, and help
but exposed the data-shapes probe's derived position as an Integer where
Datahike requires Long (13 tests, 205 assertions, zero failures, one error).
The position now explicitly converts the count to Long. The then-running
gate was stopped through its own launcher (exit 143) because its snapshot
predated that correction; its holderless root was removed.

## Files touched

- test/seon/context_blocks_fixture.clj
- test/seon/loop_proof_test.clj
- test/seon/help_trial_test.clj
- test/seon/data_shapes_test.clj
- docs/prds/context-generation/research/context_cookbook_landed_2026_09_09.clj
- docs/prds/context-generation/research/context_cookbook_probe_2026_09_09.clj
- docs/prds/context-generation/research/live-scenario-landing-2026-09-10.md
- docs/prds/context-generation/plan/agent-data-chart-prd-2026-09-09.md (section 17 status line only)
- docs/seon/issues/chart-plan-examples-retain-old-juniper-step-ids.md

The trial harness already checks order + query/read and derives the opening
from the generator, so its source and the live installer need no change.
Only this lane's scratch files and holderless failed/interrupted roots were
removed. All owned test shells exited; default remained running with
provider calls disabled. The new chart-example issue is left for owner
triage; the lane did not edit the shared issue index.

## Exact provider prompt

UTF-8 bytes: 10466. SHA-256: `910b3f2df82b375609b4254e015b6148dedae1b09f3f0a685505839a0739063a`.
The fenced payload excludes the newline added before the closing fence.

```text
my.agents.juniper=> ;; I should understand how this REPL works before I act.
(help)
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

my.agents.juniper=> ;; I should know my identity, namespace, and its steward.
(seon.db/pull
  '[:seon.agent/id {:seon.agent/namespace [:seon.ns/name {:seon.ns/steward [:seon.agent/id]}]}]
  [:seon.agent/id "juniper"])
#:seon.repl{:value #:seon.agent{:id "juniper", :namespace #:seon.ns{:name my.agents.juniper, :steward #:seon.agent{:id "juniper"}}}, :result result/ed2ebde8e1d8f, :ms 10}

my.agents.juniper=> ;; My plan is my instructions; a step is done when it has :completed-tx. (doc my.plan) shows how to add, complete, and remove steps; ids are (seon.id/id title 8).
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
#:seon.repl{:value #:seon.agent{:plan #:my.plan{:current-step #:my.plan.item{:id "juniper/read"}, :objective "Find the customer with the largest order total with a contracted function and a test, add an order of 40 for them, and tell me the customer and both totals.", :steps [#:my.plan.item{:done-when "A query over :example/order entities has returned their :example/order ids, :example/customer values, and :example/amount values.", :id "juniper/read", :position 0, :title "Read the orders"} #:my.plan.item{:done-when "A query finds the :seon.fn row for largest-customer with :seon.fn/spec present.", :id "juniper/define", :needs ["juniper/read"], :position 1, :title "Define `largest-customer` with a `:malli/schema` contract (rows → `{:customer :total}`)"} #:my.plan.item{:done-when "After (my.test/run), a query finds the :seon.test row's last result with a positive :seon.test/pass-count, zero :seon.test/fail-count, and zero :seon.test/error-count.", :id "juniper/test", :needs ["juniper/define"], :position 2, :title "Write a `deftest` over the fixture data and run it"} #:my.plan.item{:done-when "A query finds a :my.note entity linked to Juniper whose :my.note/content records the customer and original total returned by largest-customer.", :id "juniper/save", :needs ["juniper/test"], :position 3, :title "Run it and save the answer"} #:my.plan.item{:done-when "A query finds the new :example/order entity with that :example/customer and :example/amount 40.", :id "juniper/add", :needs ["juniper/save"], :position 4, :title "Add an order of 40 for that customer"} #:my.plan.item{:done-when "A query finds the :my.note entity's :my.note/content carrying the customer and both totals, with the new total verified by calling largest-customer on freshly queried orders.", :id "juniper/again", :needs ["juniper/add"], :position 5, :title "Run it again and record the new total"} #:my.plan.item{:done-when "A query finds the :seon.message from Juniper to root containing the customer and both verified totals; after (my.agent/done), session state shows the session closed.", :id "juniper/report", :needs ["juniper/again"], :position 6, :title "Report and finish"}]}}, :result result/ef845d902efe4, :ms 11}

my.agents.juniper=> ;; I should follow incoming messages with a reverse-ref pull on myself.
(seon.db/pull
  '[{:seon.message/_inbox
     [:seon.message/id :seon.message/content {:seon.message/from [:seon.agent/id]}]}]
  [:seon.agent/id "juniper"])
#:seon.repl{:value #:seon.message{:_inbox [#:seon.message{:content "Find the customer with the largest order total with a contracted function and a test, add an order of 40 for them, and tell me the customer and both totals.", :from #:seon.agent{:id "root"}, :id "23f9b3f4"}]}, :result result/eb983e404a3c3, :ms 8}

my.agents.juniper=> ;; I should read effective settings; my overrides win over cluster defaults, and turns left is derived.
(seon.agent/effective-settings)
#:seon.repl{:value [#:seon.config.ai{:model "deepseek-flash", :no-provider true} #:seon.config.ai.retry{:base-delay-ms 500, :jitter-fraction 0.25, :maximum-delay-ms 4000, :maximum-retries 2, :maximum-total-delay-ms 3000, :multiplier 2.0} #:seon.config.eval{:time-limit-ms 10000} #:seon.config.run{:max-episode-runs 30} #:my.agent{:turns-left 30}], :result result/ed99a007c24a1, :ms 131}

my.agents.juniper=> ;; I should read my saved notes; an empty read will observe the first note I add.
(get
  (seon.db/pull
    '[{:my.note/_agent [:my.note/id :my.note/content {:my.note/about [:my.plan.item/id]}]}]
    [:seon.agent/id "juniper"])
  :my.note/_agent
  [])
#:seon.repl{:value [], :result result/ed85aa589da77, :ms 9}

my.agents.juniper=> ;; I should inspect what I have defined and which schemas I declared.
(dir my.agents.juniper)
#:seon.repl{:value {:functions [], :schemas #:example{:amount :int, :customer :string, :order [:string #:seon.db{:identity true}], :order-row [:map #:seon.db{:attributes true} [:example/order :example/order] [:example/amount :example/amount] [:example/customer :example/customer]]}}, :result result/e646fecbddd61, :ms 11}

my.agents.juniper=> ;; I should count the facts under my declared attributes.
(seon.db/q
  '[:find ?attribute (count ?entity) :in $ [?attribute ...] :where [?entity ?attribute _]]
  [:example/amount :example/customer :example/order])
#:seon.repl{:value [[:example/customer 4] [:example/amount 4] [:example/order 4]], :result result/e0c5a34249067, :ms 135}

my.agents.juniper=> ;; I should follow my runtime's owner ref before pulling its turns, trigger, and listens.
(seon.db/pull
  '[{:seon.agent/runtime
     [{:seon.runtime/turns
       [:seon.turn/id
        {:seon.turn/opened-tx [:db/txInstant]}
        {:seon.turn/closed-tx [:db/txInstant]}]}
      {:seon.runtime/trigger
       [:seon.message/id :seon.message/content {:seon.message/from [:seon.agent/id]}]}
      {:seon.runtime/listens [:seon.listen/attribute :seon.listen/entity :seon.listen/value]}]}]
  [:seon.agent/id "juniper"])
#:seon.repl{:value #:seon.agent{:runtime #:seon.runtime{:trigger #:seon.message{:content "Find the customer with the largest order total with a contracted function and a test, add an order of 40 for them, and tell me the customer and both totals.", :from #:seon.agent{:id "root"}, :id "23f9b3f4"}}}, :result result/ed665fbea00e1, :ms 11}

my.agents.juniper=> ;; I should read effective settings; my overrides win over cluster defaults, and turns left is derived.
(seon.agent/effective-settings)
#:seon.repl{:value [#:seon.config.ai{:model "deepseek-flash", :no-provider true} #:seon.config.ai.retry{:base-delay-ms 500, :jitter-fraction 0.25, :maximum-delay-ms 4000, :maximum-retries 2, :maximum-total-delay-ms 3000, :multiplier 2.0} #:seon.config.eval{:time-limit-ms 10000} #:seon.config.run{:max-episode-runs 30} #:my.agent{:turns-left 30}], :result result/ea9a404e027f8, :ms 170}

my.agents.juniper=> ;; I should follow my runtime's owner ref before pulling its turns, trigger, and listens.
(seon.db/pull
  '[{:seon.agent/runtime
     [{:seon.runtime/turns
       [:seon.turn/id
        {:seon.turn/opened-tx [:db/txInstant]}
        {:seon.turn/closed-tx [:db/txInstant]}]}
      {:seon.runtime/trigger
       [:seon.message/id :seon.message/content {:seon.message/from [:seon.agent/id]}]}
      {:seon.runtime/listens [:seon.listen/attribute :seon.listen/entity :seon.listen/value]}]}]
  [:seon.agent/id "juniper"])
#:seon.repl{:value #:seon.agent{:runtime #:seon.runtime{:trigger #:seon.message{:content "Find the customer with the largest order total with a contracted function and a test, add an order of 40 for them, and tell me the customer and both totals.", :from #:seon.agent{:id "root"}, :id "23f9b3f4"}, :turns [#:seon.turn{:closed-tx #:db{:txInstant #inst "2026-09-10T20:24:00.932-00:00"}, :id "aa071259cfd8", :opened-tx #:db{:txInstant #inst "2026-09-10T20:24:00.932-00:00"}}]}}, :result result/e1ff55e8c37bc, :ms 8}
```
