---
type: research
status: complete
tags: [research, context, render, database]
---

# Rebirth capability proof — 2026-08-13

## Verdict

The rebirth mechanism is real but does not yet meet rulings 45–47 in full.
From one commit, two branches independently produced byte-identical raw and
rendered empty-history episodes. The active history dropped from **5,602** to
**640 estimated tokens**: 4,962 tokens removed, an **88.6% reduction**. The
reborn run carries actual `:seon.cluster.run/supersedes` refs to all six lived
runs, so the compact history is adoptable while the old runs remain queryable.

What is missing is exactly the cognition the rulings require. Rebirth retained
current messages and the agent situation, and removed delta scaffolding,
prose-only rationale, and deliberate errors. It did not render the agent's
current namespace results. The six-item plan survived correctly as current
facts, but its compact current-state render could not be published because a
qualified render-function symbol reached Datahike without the declared storage
encoding.

The complete machine evidence is
[`tmp/rebirth/scratch-root-4/rebirth-evidence.edn`](../../../../tmp/rebirth/scratch-root-4/rebirth-evidence.edn),
produced by [`tmp/rebirth/probe.clj`](../../../../tmp/rebirth/probe.clj).

## Authority and method

I read each named authority end to end before interpreting the result:

- [`tmp/rebirth/README.md`](../../../../tmp/rebirth/README.md);
- [`tmp/rebirth/probe.clj`](../../../../tmp/rebirth/probe.clj);
- rulings 45–50 in
  [`docs/prds/sci-execution-runtime/plan/self-generating-context-prd-2026-08-11.md`](../plan/self-generating-context-prd-2026-08-11.md);
- the acceptance framing in
  [`docs/prds/sci-execution-runtime/research/rebirth-systems-sweep-2026-08-12.md`](rebirth-systems-sweep-2026-08-12.md); and
- the cleared blocker evidence in
  [`prds/context-generation/research/live-pull-attribution-2026-08-13.md`](live-pull-attribution-2026-08-13.md).

I also read the complete named source call shapes used by the probe:
`seon.cluster/start!` and `ensure-entity!`; `seon.cluster.run` open, claim,
plan, generated-run, append, completion, and supersedes transitions;
`seon.cluster.loop/turn`; `seon.bootstrap/run-id` and `next-entry`; and
`seon.render.transcript`. The repaired harness carries the cluster's explicit
environment/projection, uses the process entity created by HEAD, declares
persistent attributes through entity/attributes shapes, and uses ordinary
`seon.db` calls inside the agent session.

The decisive command was:

```bash
clojure -M:test -e '(load-file "tmp/rebirth/probe.clj") (rebirth.probe/-main "tmp/rebirth/scratch-root-4")'
```

It exited `0` at basis transaction `536871057` and wrote the evidence file.

## Side-by-side result

| Property | Lived | Reborn from current facts + empty history |
|---|---:|---:|
| Rendered history entries | 43 | 11 |
| Estimated tokens | 5,602 | 640 |
| Delta queries | 3 | 0 |
| Deliberate unresolved-symbol errors | 1 | 0 |
| Prose-only rationale passages | 3 | 0 |
| Current plan fact | Present | Present in database, absent from rendered history |
| Authored namespace results | Present as authored forms/results | Absent |
| Supersedes refs | Not applicable | Six lived runs |

Both branch derivations agreed:

- raw history determinism: **pass**;
- rendered history determinism: **pass**; and
- combined verdict: **deterministic**.

The final current plan fact is titled “Prove rebirth compacts fact-backed
state.” Items `i1`–`i4` are done with their completion instants, `i5` is active,
and `i6` is pending. Thus the state itself survived without replay. What failed
was publishing the declared function that should have rendered `i5` and `i6`,
the two latest completions (`i4`, `i3`), and an elided/requeryable count of the
two older completions.

## Rulings 45–47 acceptance

| Ruling | Verdict | Evidence and exact owner |
|---|---|---|
| 45 — rebirth capability | **Partial; overall fail.** | Passes valid compact derivation, current-message reads, collapsed deltas, absent old errors/scaffolding, deterministic forks, and real supersedes adoption. Fails “authored journey present as RESULTS”: no `dir`/`docs`, function, schema, or green-test result appears. Owner: `seon.bootstrap/pull-result` + `next-entry`, composed with `seon.render.walk/ordered-episode`. Filed as [`docs/seon/issues/rebirth-opening-omits-agent-authored-results.md`](../../../seon/issues/rebirth-opening-omits-agent-authored-results.md). |
| 46 — facts close gaps | **Observed omission passes; causal property not yet proven.** | The rebirth does not reteach the authored lesson, and the program graph contains three contracted functions plus a test at 1/0/0. But because the generated episode omits the namespace results entirely, this proof cannot distinguish “known from facts” from “candidate never generated.” Closing the ruling-45 issue must preserve the no-reteach result and prove that the green facts caused the skip. Same bootstrap/walk owner. |
| 47 — facts + declared render survive; prose dies | **Partial; overall fail.** | Passes durable current state and intentional prose death: all eight plan attributes are installed, all three evolution transactions commit, and no rationale sentence survives. Fails the declared-render half: terminal schema publication hands `my.agents.rebirth/render-plan-ai` to string-backed `:seon.render/ai`. Owner: `seon.cluster.run/row-tx` inside `receipt-settle-call`, using the one `seon.schema.datahike` encoder after the candidate projection exists. Filed as [`docs/seon/issues/agent-schema-render-symbol-bypasses-transaction-encoding.md`](../../../seon/issues/agent-schema-render-symbol-bypasses-transaction-encoding.md). |

Ruling 50 was outside this probe's effect-free scenario and receives no pass
claim here.

## Verbatim lived-vs-reborn history

The two adjacent transcripts below are the exact
`:seon.render.history/bytes` sequences used for token measurement. No receipt
or line was summarized.

### Lived — 5,602 estimated tokens

```text
my.agents.rebirth=> ; A new run just opened. Why am I awake — do I have messages?
(help)
{:seon.cluster.agent/id "rebirth", :seon.cluster.agent/namespace-ref [:seon.ns/name
    my.agents.rebirth], :seon.cluster.agent/unread-message-count 0, :seon.cluster.run/turns-remaining
  99, :seon.cluster.agent/protocol-namespaces [my.message my.run seon.bootstrap
    seon.db], :seon.cluster.agent/open-run-ref [:seon.cluster.run/id "bootstrap:rebirth"],
  :seon.cluster.run/trigger [:seon.cluster.message/id "bootstrap-task:rebirth"]}

my.agents.rebirth=> (my.message/read "bootstrap-task:rebirth")
From outside this cluster to rebirth: Define a durable contracted function named largest that returns the row with the greatest :example/amount, or {} for empty input. Call it once, query its stored :seon.fn/spec, then complete with a short reply naming what you built and its contract.

my.agents.rebirth=> (my.message/read "rebirth-artifacts")
Agent root said to rebirth: Declare the fact-backed plan and its artifacts.

my.agents.rebirth=> (my.message/read "rebirth-render-declarations")
Agent root said to rebirth: Declare the namespace and current-plan renders.

my.agents.rebirth=> (my.message/read "rebirth-message-1")
Agent root said to rebirth: Create the initial plan items.

my.agents.rebirth=> (my.message/read "rebirth-message-2")
Agent root said to rebirth: Refine the plan and complete early items.

my.agents.rebirth=> (my.message/read "rebirth-message-3")
Agent root said to rebirth: Complete the centerpiece and leave follow-up work.

my.agents.rebirth=> (my.message/read "rebirth-artifacts-run-15-message-0")
Agent rebirth said to root: Declared the plan facts, current-state functions, and a green usage test.

my.agents.rebirth=> (my.message/read "rebirth-plan-turn-1-3-message-0")
Agent rebirth said to root: Created the six-item fact-backed plan.

my.agents.rebirth=> (my.message/read "rebirth-plan-turn-2-3-message-0")
Agent rebirth said to root: Refined the plan and completed i1 and i2.

my.agents.rebirth=> (my.message/read "rebirth-plan-turn-3-5-message-0")
Agent rebirth said to root: Completed i3 and i4; i5 is active and i6 remains.

my.agents.rebirth=> ; The durable shape comes first. Prose-only rationale: I want the future agent to remember that status transitions mattered more than chronology.
(seon.schema/register! :probe.rebirth.plan/id [:string {:seon.db/identity true}])
:probe.rebirth.plan/id

my.agents.rebirth=> (seon.schema/register! :probe.rebirth.plan/title :string)
:probe.rebirth.plan/title

my.agents.rebirth=> (seon.schema/register! :probe.rebirth.plan/agent :seon.db/ref)
:probe.rebirth.plan/agent

my.agents.rebirth=> (seon.schema/register! :probe.rebirth.plan/items [:vector {:seon.db/component true} :seon.db/ref])
:probe.rebirth.plan/items

my.agents.rebirth=> (seon.schema/register! :probe.rebirth.plan.item/id [:string {:seon.db/identity true}])
:probe.rebirth.plan.item/id

my.agents.rebirth=> (seon.schema/register! :probe.rebirth.plan.item/text :string)
:probe.rebirth.plan.item/text

my.agents.rebirth=> (seon.schema/register! :probe.rebirth.plan.item/status [:enum :pending :active :done])
:probe.rebirth.plan.item/status

my.agents.rebirth=> (seon.schema/register! :probe.rebirth.plan.item/completed-at :inst)
:probe.rebirth.plan.item/completed-at

my.agents.rebirth=> (seon.schema/register! :probe.rebirth.plan.item/attributes [:map {:seon.db/attributes true} [:probe.rebirth.plan.item/id :probe.rebirth.plan.item/id] [:probe.rebirth.plan.item/text :probe.rebirth.plan.item/text] [:probe.rebirth.plan.item/status :probe.rebirth.plan.item/status] [:probe.rebirth.plan.item/completed-at {:optional true} :probe.rebirth.plan.item/completed-at]])
:probe.rebirth.plan.item/attributes

my.agents.rebirth=> (seon.schema/register! :probe.rebirth.plan/storage [:map {:seon.db/entity true} [:probe.rebirth.plan/id :probe.rebirth.plan/id] [:probe.rebirth.plan/title :probe.rebirth.plan/title] [:probe.rebirth.plan/agent :probe.rebirth.plan/agent] [:probe.rebirth.plan/items [:vector [:map [:probe.rebirth.plan.item/id :probe.rebirth.plan.item/id] [:probe.rebirth.plan.item/text :probe.rebirth.plan.item/text] [:probe.rebirth.plan.item/status :probe.rebirth.plan.item/status] [:probe.rebirth.plan.item/completed-at {:optional true} :probe.rebirth.plan.item/completed-at]]]]])
:probe.rebirth.plan/storage

my.agents.rebirth=> (seon.schema/register! :my.agents.rebirth/namespace-value '[:map [:seon.ns/name [:= my.agents.rebirth]] [:seon.ns/doc {:optional true} :seon.ns/doc]])
:my.agents.rebirth/namespace-value

my.agents.rebirth=> (defn ^{:malli/schema [:=> [:cat [:map [:probe.rebirth.plan/id :probe.rebirth.plan/id] [:probe.rebirth.plan/title :probe.rebirth.plan/title] [:probe.rebirth.plan/agent :probe.rebirth.plan/agent] [:probe.rebirth.plan/items [:vector [:map [:probe.rebirth.plan.item/id :probe.rebirth.plan.item/id] [:probe.rebirth.plan.item/text :probe.rebirth.plan.item/text] [:probe.rebirth.plan.item/status :probe.rebirth.plan.item/status] [:probe.rebirth.plan.item/completed-at {:optional true} :probe.rebirth.plan.item/completed-at]]]]]] [:vector [:map [:probe.rebirth.plan.item/id :probe.rebirth.plan.item/id] [:probe.rebirth.plan.item/text :probe.rebirth.plan.item/text] [:probe.rebirth.plan.item/status :probe.rebirth.plan.item/status] [:probe.rebirth.plan.item/completed-at {:optional true} :probe.rebirth.plan.item/completed-at]]]]} current-items [plan] (->> (:probe.rebirth.plan/items plan) (remove #(= :done (:probe.rebirth.plan.item/status %))) (sort-by :probe.rebirth.plan.item/id) vec))
#'my.agents.rebirth/current-items

my.agents.rebirth=> (defn ^{:malli/schema [:=> [:cat [:map [:probe.rebirth.plan/id :probe.rebirth.plan/id] [:probe.rebirth.plan/title :probe.rebirth.plan/title] [:probe.rebirth.plan/agent :probe.rebirth.plan/agent] [:probe.rebirth.plan/items [:vector [:map [:probe.rebirth.plan.item/id :probe.rebirth.plan.item/id] [:probe.rebirth.plan.item/text :probe.rebirth.plan.item/text] [:probe.rebirth.plan.item/status :probe.rebirth.plan.item/status] [:probe.rebirth.plan.item/completed-at {:optional true} :probe.rebirth.plan.item/completed-at]]]]]] :seon.render/ai]} render-plan-ai [plan] (let [items (:probe.rebirth.plan/items plan) remaining (current-items plan) completed (->> items (filter #(= :done (:probe.rebirth.plan.item/status %))) (sort-by :probe.rebirth.plan.item/completed-at #(compare %2 %1))) recent (take 2 completed) older (drop 2 completed)] (str "Plan " (:probe.rebirth.plan/title plan) ".\nRemaining:\n" (apply str (map #(str "- " (:probe.rebirth.plan.item/id %) " — " (:probe.rebirth.plan.item/text %) "\n") remaining)) "Recent completions:\n" (apply str (map #(str "- " (:probe.rebirth.plan.item/id %) " — " (:probe.rebirth.plan.item/text %) "\n") recent)) (when (seq older) (str "... " (count older) " older completions; requery with (db/q '[:find ?id ?at :where [?item :probe.rebirth.plan.item/id ?id] [?item :probe.rebirth.plan.item/status :done] [?item :probe.rebirth.plan.item/completed-at ?at]] (db/db)).")))))
#'my.agents.rebirth/render-plan-ai

my.agents.rebirth=> (clojure.test/deftest ^{:seon.test/usage true} plan-current-state-test (clojure.test/is (= ["remaining"] (mapv :probe.rebirth.plan.item/id (current-items {:probe.rebirth.plan/id "sample" :probe.rebirth.plan/title "sample" :probe.rebirth.plan/agent 1 :probe.rebirth.plan/items [{:probe.rebirth.plan.item/id "done" :probe.rebirth.plan.item/text "done" :probe.rebirth.plan.item/status :done :probe.rebirth.plan.item/completed-at #inst "2026-08-12T00:00:00.000-00:00"} {:probe.rebirth.plan.item/id "remaining" :probe.rebirth.plan.item/text "remaining" :probe.rebirth.plan.item/status :pending}]})))))
#'my.agents.rebirth/plan-current-state-test

my.agents.rebirth=> (seon.test/run #'plan-current-state-test)
#:seon.test{:sym "my.agents.rebirth/plan-current-state-test", :pass-count
  1, :fail-count 0, :error-count 0, :run-basis-t 536871000, :run-at #inst "2026-08-13T03:54:58.411-00:00"}

my.agents.rebirth=> (my.run/complete "Declared the plan facts, current-state functions, and a green usage test.")
#:my.run{:disposition :completed, :result "Declared the plan facts, current-state functions, and a green usage test.",
  :delivered-to "root"}

my.agents.rebirth=> ; The render declarations name qualified functions as ordinary schema data.
(defn ^{:malli/schema [:=> [:cat :my.agents.rebirth/namespace-value] :seon.render/ai]} render-namespace-ai [unit] (str "Namespace " (:seon.ns/name unit) " owns a fact-backed plan, current-items, render-plan-ai, and plan-current-state-test."))
#'my.agents.rebirth/render-namespace-ai

my.agents.rebirth=> (seon.schema/register! :probe.rebirth.plan/plan '[:map {:seon.db/entity true :seon.render/ai my.agents.rebirth/render-plan-ai} [:probe.rebirth.plan/id :probe.rebirth.plan/id] [:probe.rebirth.plan/title :probe.rebirth.plan/title] [:probe.rebirth.plan/agent :probe.rebirth.plan/agent] [:probe.rebirth.plan/items [:vector [:map [:probe.rebirth.plan.item/id :probe.rebirth.plan.item/id] [:probe.rebirth.plan.item/text :probe.rebirth.plan.item/text] [:probe.rebirth.plan.item/status :probe.rebirth.plan.item/status] [:probe.rebirth.plan.item/completed-at {:optional true} :probe.rebirth.plan.item/completed-at]]]]])
Execution error () at (REPL:1).
Bad entity value my.agents.rebirth/render-plan-ai at [:db/add 28282 :seon.render/ai my.agents.rebirth/render-plan-ai], value does not match schema definition. Must be conform to: string?

my.agents.rebirth=> ; I am choosing six explicit items because the hidden rationale should not survive unless modeled.
(seon.db/q {:query '[:find ?id ?tx ?user-id :in $current $delta ?agent-id :where [$current ?agent :seon.cluster.agent/id ?agent-id] [$delta ?message :seon.cluster.message/to ?agent ?tx] [$current ?message :seon.cluster.message/id ?id] [$delta ?tx :seon.db/user ?user] [$current ?user :seon.cluster.agent/id ?user-id]] :args [(seon.db/db) (seon.db/since (seon.db/db) 536871009) "rebirth"]})
#{["rebirth-message-1" 536871024 "root"]}

my.agents.rebirth=> (my.message/read "rebirth-message-1" (seon.db/db))
#:seon.cluster.message{:content "Create the initial plan items.", :at #inst "2026-08-12T02:00:02.000-00:00",
  :to [:seon.cluster.agent/id "rebirth"], :from [:seon.cluster.agent/id "root"],
  :id "rebirth-message-1", :ordinal 2000}

my.agents.rebirth=> (seon.db/transact! [{:probe.rebirth.plan/id "rebirth-plan" :probe.rebirth.plan/title "Prove rebirth compacts state" :probe.rebirth.plan/agent [:seon.cluster.agent/id "rebirth"] :probe.rebirth.plan/items [{:probe.rebirth.plan.item/id "i1" :probe.rebirth.plan.item/text "Audit current my.plan" :probe.rebirth.plan.item/status :active} {:probe.rebirth.plan.item/id "i2" :probe.rebirth.plan.item/text "Capture lived deltas" :probe.rebirth.plan.item/status :pending} {:probe.rebirth.plan.item/id "i3" :probe.rebirth.plan.item/text "Author durable artifacts" :probe.rebirth.plan.item/status :pending} {:probe.rebirth.plan.item/id "i4" :probe.rebirth.plan.item/text "Prove current-state rendering" :probe.rebirth.plan.item/status :pending} {:probe.rebirth.plan.item/id "i5" :probe.rebirth.plan.item/text "Compare deterministic rebirths" :probe.rebirth.plan.item/status :pending} {:probe.rebirth.plan.item/id "i6" :probe.rebirth.plan.item/text "Record implementation deltas" :probe.rebirth.plan.item/status :pending}]}])
#datahike.db.TxReport{:db-before #object[datahike.db.DB], :db-after #object[datahike.db.DB],
  :tx-data [(536871030 :db/txInstant #inst "2026-08-13T03:55:09.975-00:00"
      536871030 true) (28328 :probe.rebirth.plan/id "rebirth-plan" 536871030
      true) (28328 :probe.rebirth.plan/title "Prove rebirth compacts state"
      536871030 true) (28328 :probe.rebirth.plan/agent 27836 536871030 true)
    (28329 :probe.rebirth.plan.item/id "i1" 536871030 true) (28329 :probe.rebirth.plan.item/text
      "Audit current my.plan" 536871030 true) (28329 :probe.rebirth.plan.item/status
      :active 536871030 true) … 21 more children of 28; requery refused: the value has no durable blob or entity identity at path [2 1] offset 7 with :seon.render.profile/agent],
  :tempids #:db{:current-tx 536871030}, :tx-meta #:db{:txInstant #inst "2026-08-13T03:55:09.975-00:00",
    :commitId #uuid "6a7d401d-1c1c-57c2-9e0b-567cba90a4e9"}}

my.agents.rebirth=> (my.run/complete "Created the six-item fact-backed plan.")
#:my.run{:disposition :completed, :result "Created the six-item fact-backed plan.",
  :delivered-to "root"}

my.agents.rebirth=> ; The prose-only insight this turn is that i4 is the conceptual center
; this sentence intentionally has no fact.
(seon.db/q {:query '[:find ?id ?tx ?user-id :in $current $delta ?agent-id :where [$current ?agent :seon.cluster.agent/id ?agent-id] [$delta ?message :seon.cluster.message/to ?agent ?tx] [$current ?message :seon.cluster.message/id ?id] [$delta ?tx :seon.db/user ?user] [$current ?user :seon.cluster.agent/id ?user-id]] :args [(seon.db/db) (seon.db/since (seon.db/db) 536871024) "rebirth"]})
#{["rebirth-message-2" 536871034 "root"]}

my.agents.rebirth=> (my.message/read "rebirth-message-2" (seon.db/db))
#:seon.cluster.message{:content "Refine the plan and complete early items.",
  :at #inst "2026-08-12T02:00:03.000-00:00", :to [:seon.cluster.agent/id
    "rebirth"], :from [:seon.cluster.agent/id "root"], :id "rebirth-message-2",
  :ordinal 3000}

my.agents.rebirth=> (seon.db/transact! [{:probe.rebirth.plan/id "rebirth-plan" :probe.rebirth.plan/title "Prove rebirth compacts fact-backed state"} {:probe.rebirth.plan.item/id "i1" :probe.rebirth.plan.item/text "Audit current my.plan facts, status fields, timestamps, and renders" :probe.rebirth.plan.item/status :done :probe.rebirth.plan.item/completed-at #inst "2026-08-12T01:00:00.000-00:00"} {:probe.rebirth.plan.item/id "i2" :probe.rebirth.plan.item/status :done :probe.rebirth.plan.item/completed-at #inst "2026-08-12T02:00:00.000-00:00"} {:probe.rebirth.plan.item/id "i3" :probe.rebirth.plan.item/status :active}])
#datahike.db.TxReport{:db-before #object[datahike.db.DB], :db-after #object[datahike.db.DB],
  :tx-data [(536871040 :db/txInstant #inst "2026-08-13T03:55:14.837-00:00"
      536871040 true) (28328 :probe.rebirth.plan/title "Prove rebirth compacts fact-backed state"
      536871040 true) (28329 :probe.rebirth.plan.item/text "Audit current my.plan facts, status fields, timestamps, and renders"
      536871040 true) (28329 :probe.rebirth.plan.item/status :done 536871040
      true) (28329 :probe.rebirth.plan.item/completed-at #inst "2026-08-12T01:00:00.000-00:00"
      536871040 true) (28330 :probe.rebirth.plan.item/status :done 536871040
      true) (28330 :probe.rebirth.plan.item/completed-at #inst "2026-08-12T02:00:00.000-00:00"
      536871040 true) (28331 :probe.rebirth.plan.item/status :active 536871040
      true)], :tempids #:db{:current-tx 536871040}, :tx-meta #:db{:txInstant
    #inst "2026-08-13T03:55:14.837-00:00", :commitId #uuid "6a7d4022-56dc-5b3d-b272-4607c9c28101"}}

my.agents.rebirth=> (my.run/complete "Refined the plan and completed i1 and i2.")
#:my.run{:disposition :completed, :result "Refined the plan and completed i1 and i2.",
  :delivered-to "root"}

my.agents.rebirth=> ; My final prose-only reasoning is that a declared render is the compaction algorithm. It should disappear.
(seon.db/q {:query '[:find ?id ?tx ?user-id :in $current $delta ?agent-id :where [$current ?agent :seon.cluster.agent/id ?agent-id] [$delta ?message :seon.cluster.message/to ?agent ?tx] [$current ?message :seon.cluster.message/id ?id] [$delta ?tx :seon.db/user ?user] [$current ?user :seon.cluster.agent/id ?user-id]] :args [(seon.db/db) (seon.db/since (seon.db/db) 536871034) "rebirth"]})
#{["rebirth-message-3" 536871044 "root"]}

my.agents.rebirth=> (my.message/read "rebirth-message-3" (seon.db/db))
#:seon.cluster.message{:content "Complete the centerpiece and leave follow-up work.",
  :at #inst "2026-08-12T02:00:04.000-00:00", :to [:seon.cluster.agent/id
    "rebirth"], :from [:seon.cluster.agent/id "root"], :id "rebirth-message-3",
  :ordinal 4000}

my.agents.rebirth=> (seon.db/transact! [{:probe.rebirth.plan.item/id "i3" :probe.rebirth.plan.item/status :done :probe.rebirth.plan.item/completed-at #inst "2026-08-12T03:00:00.000-00:00"} {:probe.rebirth.plan.item/id "i4" :probe.rebirth.plan.item/status :done :probe.rebirth.plan.item/completed-at #inst "2026-08-12T04:00:00.000-00:00"} {:probe.rebirth.plan.item/id "i5" :probe.rebirth.plan.item/text "Byte-compare two empty-history derivations" :probe.rebirth.plan.item/status :active} {:probe.rebirth.plan.item/id "i6" :probe.rebirth.plan.item/status :pending}])
#datahike.db.TxReport{:db-before #object[datahike.db.DB], :db-after #object[datahike.db.DB],
  :tx-data [(536871050 :db/txInstant #inst "2026-08-13T03:55:19.941-00:00"
      536871050 true) (28331 :probe.rebirth.plan.item/status :done 536871050
      true) (28331 :probe.rebirth.plan.item/completed-at #inst "2026-08-12T03:00:00.000-00:00"
      536871050 true) (28332 :probe.rebirth.plan.item/status :done 536871050
      true) (28332 :probe.rebirth.plan.item/completed-at #inst "2026-08-12T04:00:00.000-00:00"
      536871050 true) (28333 :probe.rebirth.plan.item/text "Byte-compare two empty-history derivations"
      536871050 true) (28333 :probe.rebirth.plan.item/status :active 536871050
      true)], :tempids #:db{:current-tx 536871050}, :tx-meta #:db{:txInstant
    #inst "2026-08-13T03:55:19.941-00:00", :commitId #uuid "6a7d4027-8d26-5915-9b23-e77e5a098937"}}

my.agents.rebirth=> missing-plan-helper
Execution error (ExceptionInfo) at sci.impl.utils/throw-error-with-location (utils.cljc:67).
Unable to resolve symbol: missing-plan-helper

my.agents.rebirth=> (current-items :wrong-shape)
[]

my.agents.rebirth=> (my.run/complete "Completed i3 and i4; i5 is active and i6 remains.")
#:my.run{:disposition :completed, :result "Completed i3 and i4; i5 is active and i6 remains.",
  :delivered-to "root"}
```

### Reborn — 640 estimated tokens

```text
my.agents.rebirth=> (my.message/read "bootstrap-task:rebirth")
From outside this cluster to rebirth: Define a durable contracted function named largest that returns the row with the greatest :example/amount, or {} for empty input. Call it once, query its stored :seon.fn/spec, then complete with a short reply naming what you built and its contract.

my.agents.rebirth=> (my.message/read "rebirth-artifacts")
Agent root said to rebirth: Declare the fact-backed plan and its artifacts.

my.agents.rebirth=> (my.message/read "rebirth-render-declarations")
Agent root said to rebirth: Declare the namespace and current-plan renders.

my.agents.rebirth=> (my.message/read "rebirth-message-1")
Agent root said to rebirth: Create the initial plan items.

my.agents.rebirth=> (my.message/read "rebirth-message-2")
Agent root said to rebirth: Refine the plan and complete early items.

my.agents.rebirth=> (my.message/read "rebirth-message-3")
Agent root said to rebirth: Complete the centerpiece and leave follow-up work.

my.agents.rebirth=> (my.message/read "rebirth-artifacts-run-15-message-0")
Agent rebirth said to root: Declared the plan facts, current-state functions, and a green usage test.

my.agents.rebirth=> (my.message/read "rebirth-plan-turn-1-3-message-0")
Agent rebirth said to root: Created the six-item fact-backed plan.

my.agents.rebirth=> (my.message/read "rebirth-plan-turn-2-3-message-0")
Agent rebirth said to root: Refined the plan and completed i1 and i2.

my.agents.rebirth=> (my.message/read "rebirth-plan-turn-3-5-message-0")
Agent rebirth said to root: Completed i3 and i4; i5 is active and i6 remains.

my.agents.rebirth=> ; Reborn from current facts with empty history.
(help)
{:seon.cluster.agent/id "rebirth", :seon.cluster.agent/namespace-ref [:seon.ns/name
    my.agents.rebirth], :seon.cluster.agent/unread-message-count 0, :seon.cluster.run/turns-remaining
  93, :seon.cluster.agent/protocol-namespaces [my.message my.run seon.bootstrap
    seon.db], :seon.cluster.agent/open-run-ref [:seon.cluster.run/id "rebirth-proof-a"]}
```

## Genuine gaps filed

- [`docs/seon/issues/agent-schema-render-symbol-bypasses-transaction-encoding.md`](../../../seon/issues/agent-schema-render-symbol-bypasses-transaction-encoding.md)
  owns the ruling-47 publication failure.
- [`docs/seon/issues/rebirth-opening-omits-agent-authored-results.md`](../../../seon/issues/rebirth-opening-omits-agent-authored-results.md)
  owns the ruling-45 namespace-results omission and the causal ruling-46
  follow-through.

No production or test source was edited, and the issue index was not edited.
