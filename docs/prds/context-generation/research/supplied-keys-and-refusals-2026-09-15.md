---
type: research
status: active
tags: [agent, docs, schema]
---

# Supplied keys and actionable refusals — 2026-09-15

Implementation commit: `f879b0ea6` on `steward-platform`.

Owned files committed:

- `src/seon/call_preparation.clj`
- `src/seon/instrument.clj` (report functions only)
- `src/seon/sci/eval.clj` (documentation functions)
- `src/seon/repl.clj` (directory columns)
- `resources/seon/schemas/seon.repl.edn`
- `test/seon/supplied_documentation_test.clj`
- `test/seon/sci/documentation_test.clj`
- `docs/seon/issues/default-component-probe-times-out-after-adoption.md`
- `docs/prds/context-generation/research/supplied-keys-and-refusals-2026-09-15.md`
- `docs/prds/context-generation/research/supplied-keys-probe-2026-09-15.clj`
- `docs/prds/context-generation/research/supplied-keys-evidence-2026-09-15.edn`

## Result

Agent input projections omit runtime-supplied map entries and accrete
`:supplied`. Directory columns/rows retain their structure; full schema
definitions remain under `:schemas`. Docstring examples are unchanged.
Invalid supplied entries say to remove them. Missing supplied entries in
an evaluation environment carry `:seon.instrument/missing-supplied-key`
and identify a runtime fault. Lookup-ref refusals identify the actual shape
and direct the agent to pass the string id. Valid caller-provided defaults
retain call preparation's existing semantics.

Read end to end: AGENTS.md; the requested call-preparation docstrings and
required-map-entry rule; instrument.clj; the documentation functions and
doc/dir macros in sci/eval.clj; my/message.clj and my/note.clj. Read the
active roadmap and consulted its working edge. Applied repl,
data-oriented-clojure, clojure-testing, and data-modeling skills.

## Dependency and ownership ledger

- SCI `:call-preparation-hook`: `reference-code/sci/src/sci/core.cljc:309`.
  The existing hook receives the caller context and evaluated arguments.
- Supplier facts and required map entries: `src/seon/call_preparation.clj`
  `row-query`, `map-entry-query`, `required-map-entry-query`, `plan-for`.
  The new contracted query shares the first two queries and matches BOTH
  the key and complete value-shape fingerprint. The required-map-entry
  rule still decides whether an entire map argument can be omitted.
- Malli missing-key evidence: `reference-code/malli/src/malli/core.cljc:1298`.
  Structured `:in`, `:type`, `:schema`, and `:value` drive diagnostics.
  No production regex or message parsing.
- Existing semantic explanation: `src/seon/error.clj:715`. The report
  refines its Fix text and actual shape; error.clj is unchanged.
- Directory columns belong to `src/seon/repl.clj:21`, requiring an
  additional renderer edit and an optional field in
  `resources/seon/schemas/seon.repl.edn`.
- Archaeology: `dda9f0366` (armed database guards), `d4f8a536c` (refusal
  coordinates), and `6acd8818e` (program-switch checkpoint). This change
  extends their owners.

## Live evidence and boundary

Default PID 23729, PREPL 54412. Initial runtime status observed all three
plumbing procs replying. The final audit used database basis 536879829.
This is a JVM proof of hook-loaded definitions with the database projection
explicitly handed, not a browser-paint or full adoption-convergence claim.
No default lifecycle operation occurred.

The historical query completed in 10,574 ms. Unscoped documentation and
capture calls timed out at 20,000 and 60,000 ms; the publication log also
reported its bound. The scoped documentation probe completed in 11,269 ms
and the final capture in 12,519 ms. The existing issue
`docs/seon/issues/default-component-probe-times-out-after-adoption.md`
records these observations. No cause is inferred.

The reproducible [probe](supplied-keys-probe-2026-09-15.clj) and complete
[evidence](supplied-keys-evidence-2026-09-15.edn) accompany this note.
Run load-file inside `seon.schema/call-with-projection`, using
`seon.schema/projection-from-database` from the selected connection.
The probe reads the stored run-9 source and invokes its invalid request
with normal runtime values; validation prevents a message write.

## Schema audit

The query covers every installed `my.*` schema's required map entries by
joining value shapes to supplier rows. Sixteen schemas match: fourteen
request schemas and two data shapes (`my.plan/agent-state` and
`my.plan/component-view`). All 44 public `my.*` function rows were inspected;
22 declare supplied request entries. Joining input references to these
schemas found **zero unmarked functions**. The positional-slot query found
**zero my.* functions with supplied positional values** at this basis.
None were changed. `my.plan/items-request` has no current public function
consumer in this audit.

The resource cross-check covered all 17 `resources/seon/schemas/my*.edn`
files. The matching required request entries live in my.agent, my.message,
my.note, and my.plan. my.turn's namespace render unit has an OPTIONAL
`:seon.db/db`, which the preparation rule does not supply; its
`:delivered-to` is an output value. Neither is mislabeled as supplied.

## Tests and landing

Fast iteration passed 10 tests / 110 assertions. The isolated gate passed
34 tests / 229 assertions, zero failures/errors, exit 0 (root `run.zAh2g2`,
HEAD `806659e06`). It ran `seon.supplied-documentation-test`,
`seon.sci.documentation-test`, and `seon.instrument-test` with only this
lane's paths overlaid. The runner printed a separate persistence failure:
`persistent results NOT recorded: :seon.test.runner/persistent-results-recording-failed Branch head changed before force-branch!.`
Its successful root was removed. These are passing executable tests, not
confirmed shared result facts. The existing
`docs/seon/issues/test-results-persistence-can-time-out-during-development-adoption.md`
tracks this boundary; its runner/source owners are protected here.

The subsequent bare `bin/test --platform` passed 85 tests / 514 assertions,
zero failures/errors, exit 0 (root `run.AYilnc`). Its snapshot included the
shared tree's concurrent edits, as requested; it is distinct from the
path-isolated subject gate. The runner removed both successful roots. All
of this lane's earlier fast snapshots were also removed, and no owned
background shell or scratch cluster remains.

New regressions use the canonical database population,
real acquired SCI contexts, and armed contracts. Examples additionally seed
the canonical cluster/config path. The supplied-key refusal runs with a
turn-scoped effect environment. No mocks or hand-rostered schema.
Existing documentation tests were updated for the accreted field and the
current Example grammar.

Concurrent edits were preserved. Instrumentation edits are restricted to
report construction and its query require; arm-var!/apply! and protected
source/test owners were not edited.

## Exact before/after output and audit rows

The following historical bytes come from run 9, not a reconstructed old
implementation. The old `:my.message/message` contract in the refusal is
the adoption-contract-freshness lane's separate subject.

### Run-9 source — evaluation 332ad81ea88e

```clojure
(my.message/send
  {:my.message/to "root"
   :my.message/about [:seon.message/id "f500b1f2"]
   :my.message/content "Largest customer by order total is Ada. Original total: 115 (a1=60 + a2=55). I added an order a3 of 40 for Ada. New total: 155. Verified by calling largest-customer on the freshly queried orders => {:customer \"Ada\", :total 155}."})
```

### Refusal before — exact shown text, 2,225 UTF-8 bytes

```text
my.message/send violated its contract (:malli.core/invalid-input).
Contract violation in my.message/send arguments: expected [:cat :my.message/message], received [{:my.message/to "root", :my.message/about [:seon.message/id "f500b1f2"], :my.message/content "Largest customer by order total is Ada. Original total: 115 (a1=60 + a2=55). I added an order a3 of 40 for Ada. New total: 155. Verified by calling largest-customer on the freshly queried orders => {:customer \"Ada\", :total 155}.", :seon.db/connection #datahike/Connection[#uuid "224560ae-b0ba-3d5c-b6db-6d7415ad66e7" :cluster-default], :seon.agent/id "juniper"}]. The call was stopped before the function ran. 
{:seon.error/doc {:summary "Send a message now and return the written message with its identity.", :body "Returns the stored :seon.message/id, :seon.message/content and endpoint refs.\nSending inside let or do delivers even when its return value is discarded.\nSupply :my.message/about to answer a message and remove its inbox edge.", :example "(my.message/send {:my.message/to \"root\" :my.message/content \"The verification passed.\"})", :arglists ([request]), :in [:cat [:map [:my.message/to :my.message/to] [:my.message/content :my.message/content] [:my.message/about {:optional true} :my.message/about] [:seon.db/connection :seon.db/connection] [:seon.agent/id :seon.agent/id]]], :out [:or [:map {:seon.db/attributes true, :seon.render/ai seon.cluster.message/render-ai, :seon.render/html seon.cluster.message/render-html, :seon.render/form seon.render.transcript/message-form} [:seon.message/id :seon.message/id] [:seon.message/to :seon.message/to] [:seon.message/content :seon.message/content] [:seon.message/from {:optional true} :seon.message/from] [:seon.message/caused-by {:optional true} :seon.message/caused-by] [:seon.message/about {:optional true} :seon.message/about] [:my.message/reason {:optional true} :my.message/reason] [:seon.message/inbox {:optional true} :seon.message/inbox] [:seon.message/read-tx {:optional true} :seon.message/read-tx]] [:map [:seon.error/kind :seon.error/kind] [:seon.error/message :seon.error/message] [:seon.error/doc {:optional true} :seon.error/doc] [:seon.error/data {:optional true} :seon.error/data]]]}}
```

### Refusal after — same invalid request, 342 UTF-8 bytes

```text
my.message/send refused request at [:my.message/about]: expected a string ([:string {:min 1}]), got a lookup-ref vector [:seon.message/id "f500b1f2"]. Fix: Pass the string id (the second element of the lookup-ref vector) at [:my.message/about]. Example: (my.message/send {:my.message/to "root" :my.message/content "The verification passed."})
```

### (dir my.message) before — 6ba8b3a95d1b, Tue Sep 15 11:30:37 CST 2026

```clojure
{:seon.repl/columns [:sym :arglists :doc :in :out], :seon.repl/rows [[my.message/decline ([request]) "Send a reason for declining an assignment to its sender." [:cat :my.message/decline-request] [:or :seon.message/message :seon.error/value]] [my.message/inbox ([request]) "Read messages in my inbox, oldest first." [:cat :my.message/inbox-request] [:or :my.message/inbox :seon.error/value]] [my.message/read ([request]) "Read one message by its identity." [:cat :my.message/read-request] [:or :seon.message/message :seon.error/value]] [my.message/send ([request]) "Send a message now and return the written message with its identity." [:cat :my.message/send-request] [:or :seon.message/message :seon.error/value]]], :seon.repl/schemas {:my.message/decline-request [:map [:my.message/to :my.message/to] [:my.message/about :my.message/about] [:my.message/reason :my.message/reason] [:seon.db/connection :seon.db/connection] [:seon.agent/id :seon.agent/id]], :my.message/inbox [:vector :my.message/inbox-entry], :my.message/inbox-request [:map [:seon.db/db :seon.db/database-value] [:seon.agent/id :seon.agent/id] [:seon.db/since {:optional true} :seon.db/basis-t]], :my.message/read-request [:map [:my.message/id :my.message/id] [:seon.db/db :seon.db/database-value]], :my.message/send-request [:map [:my.message/to :my.message/to] [:my.message/content :my.message/content] [:my.message/about {:optional true} :my.message/about] [:seon.db/connection :seon.db/connection] [:seon.agent/id :seon.agent/id]], :seon.error/value [:map [:seon.error/kind :seon.error/kind] [:seon.error/message :seon.error/message] [:seon.error/doc {:optional true} :seon.error/doc] [:seon.error/data {:optional true} :seon.error/data]], :seon.message/message [:map {:seon.db/attributes true, :seon.render/ai seon.cluster.message/render-ai, :seon.render/html seon.cluster.message/render-html, :seon.render/form seon.render.transcript/message-form} [:seon.message/id :seon.message/id] [:seon.message/to :seon.message/to] [:seon.message/content :seon.message/content] [:seon.message/from {:optional true} :seon.message/from] [:seon.message/caused-by {:optional true} :seon.message/caused-by] [:seon.message/about {:optional true} :seon.message/about] [:my.message/reason {:optional true} :my.message/reason] [:seon.message/inbox {:optional true} :seon.message/inbox] [:seon.message/read-tx {:optional true} :seon.message/read-tx]]}}
```

### (doc my.message/send) before — 30a922b341df, Tue Sep 15 11:30:51 CST 2026

```clojure
{:arglists ([request]), :body "Returns the stored :seon.message/id, :seon.message/content and endpoint refs.\nSending inside let or do delivers even when its return value is discarded.\nSupply :my.message/about to answer a message and remove its inbox edge.", :example "(my.message/send {:my.message/to \"root\" :my.message/content \"The verification passed.\"})", :in [:cat [:map [:my.message/to :my.message/to] [:my.message/content :my.message/content] [:my.message/about {:optional true} :my.message/about] [:seon.db/connection :seon.db/connection] [:seon.agent/id :seon.agent/id]]], :out [:or [:map {:seon.db/attributes true, :seon.render/ai seon.cluster.message/render-ai, :seon.render/form seon.render.transcript/message-form, :seon.render/html seon.cluster.message/render-html} [:seon.message/id :seon.message/id] [:seon.message/to :seon.message/to] [:seon.message/content :seon.message/content] [:seon.message/from {:optional true} :seon.message/from] [:seon.message/caused-by {:optional true} :seon.message/caused-by] [:seon.message/about {:optional true} :seon.message/about] [:my.message/reason {:optional true} :my.message/reason] [:seon.message/inbox {:optional true} :seon.message/inbox] [:seon.message/read-tx {:optional true} :seon.message/read-tx]] [:map [:seon.error/kind :seon.error/kind] [:seon.error/message :seon.error/message] [:seon.error/doc {:optional true} :seon.error/doc] [:seon.error/data {:optional true} :seon.error/data]]], :summary "Send a message now and return the written message with its identity."}
```

### doc after — returned data

```clojure
{:summary "Send a message now and return the written message with its identity.", :body "Returns the stored :seon.message/id, :seon.message/content and endpoint refs.\nSending inside let or do delivers even when its return value is discarded.\nSupply :my.message/about to answer a message and remove its inbox edge.", :example "(my.message/send {:my.message/to \"root\" :my.message/content \"The verification passed.\"})", :arglists ([request]), :in [:cat [:map [:my.message/to :my.message/to] [:my.message/content :my.message/content] [:my.message/about {:optional true} :my.message/about]]], :out [:or [:map {:seon.db/attributes true, :seon.render/ai seon.cluster.message/render-ai, :seon.render/html seon.cluster.message/render-html, :seon.render/form seon.render.transcript/message-form} [:seon.message/id :seon.message/id] [:seon.message/to :seon.message/to] [:seon.message/content :seon.message/content] [:seon.message/from {:optional true} :seon.message/from] [:seon.message/caused-by {:optional true} :seon.message/caused-by] [:seon.message/about {:optional true} :seon.message/about] [:my.message/reason {:optional true} :my.message/reason] [:seon.message/inbox {:optional true} :seon.message/inbox] [:seon.message/read-tx {:optional true} :seon.message/read-tx]] [:map [:seon.error/kind :seon.error/kind] [:seon.error/message :seon.error/message] [:seon.error/doc {:optional true} :seon.error/doc] [:seon.error/data {:optional true} :seon.error/data]]], :supplied [:seon.agent/id :seon.db/connection]}
```

### dir after — directory renderer

```clojure
#:seon.repl{:columns [:sym :arglists :doc :in :out :supplied], :rows [[my.message/decline ([request]) "Send a reason for declining an assignment to its sender." [:cat [:map [:my.message/to :my.message/to] [:my.message/about :my.message/about] [:my.message/reason :my.message/reason]]] [:or :seon.message/message :seon.error/value] [:seon.agent/id :seon.db/connection]] [my.message/inbox ([request]) "Read messages in my inbox, oldest first." [:cat [:map [:seon.db/since {:optional true} :seon.db/basis-t]]] [:or :my.message/inbox :seon.error/value] [:seon.agent/id :seon.db/db]] [my.message/read ([request]) "Read one message by its identity." [:cat [:map [:my.message/id :my.message/id]]] [:or :seon.message/message :seon.error/value] [:seon.db/db]] [my.message/send ([request]) "Send a message now and return the written message with its identity." [:cat [:map [:my.message/to :my.message/to] [:my.message/content :my.message/content] [:my.message/about {:optional true} :my.message/about]]] [:or :seon.message/message :seon.error/value] [:seon.agent/id :seon.db/connection]]], :schemas {:my.message/decline-request [:map [:my.message/to :my.message/to] [:my.message/about :my.message/about] [:my.message/reason :my.message/reason] [:seon.db/connection :seon.db/connection] [:seon.agent/id :seon.agent/id]], :my.message/inbox [:vector :my.message/inbox-entry], :my.message/inbox-request [:map [:seon.db/db :seon.db/database-value] [:seon.agent/id :seon.agent/id] [:seon.db/since {:optional true} :seon.db/basis-t]], :my.message/read-request [:map [:my.message/id :my.message/id] [:seon.db/db :seon.db/database-value]], :my.message/send-request [:map [:my.message/to :my.message/to] [:my.message/content :my.message/content] [:my.message/about {:optional true} :my.message/about] [:seon.db/connection :seon.db/connection] [:seon.agent/id :seon.agent/id]], :seon.error/value [:map [:seon.error/kind :seon.error/kind] [:seon.error/message :seon.error/message] [:seon.error/doc {:optional true} :seon.error/doc] [:seon.error/data {:optional true} :seon.error/data]], :seon.message/message [:map {:seon.db/attributes true, :seon.render/ai seon.cluster.message/render-ai, :seon.render/html seon.cluster.message/render-html, :seon.render/form seon.render.transcript/message-form} [:seon.message/id :seon.message/id] [:seon.message/to :seon.message/to] [:seon.message/content :seon.message/content] [:seon.message/from {:optional true} :seon.message/from] [:seon.message/caused-by {:optional true} :seon.message/caused-by] [:seon.message/about {:optional true} :seon.message/about] [:my.message/reason {:optional true} :my.message/reason] [:seon.message/inbox {:optional true} :seon.message/inbox] [:seon.message/read-tx {:optional true} :seon.message/read-tx]]}}
```

### Audited schemas

| Schema | Supplied keys |
|---|---|
| `:my.agent/settings-request` | `:seon.agent/id`, `:seon.db/connection` |
| `:my.message/decline-request` | `:seon.agent/id`, `:seon.db/connection` |
| `:my.message/inbox-request` | `:seon.agent/id`, `:seon.db/db` |
| `:my.message/read-request` | `:seon.db/db` |
| `:my.message/send-request` | `:seon.agent/id`, `:seon.db/connection` |
| `:my.note/add-request` | `:seon.agent/id`, `:seon.db/connection` |
| `:my.note/forget-request` | `:seon.agent/id`, `:seon.db/connection` |
| `:my.plan/add-request` | `:seon.agent/id`, `:seon.db/connection` |
| `:my.plan/agent-state` | `:seon.agent/id` |
| `:my.plan/complete-request` | `:seon.agent/id`, `:seon.db/connection` |
| `:my.plan/component-view` | `:seon.agent/id` |
| `:my.plan/current-request` | `:seon.agent/id`, `:seon.db/connection` |
| `:my.plan/item-request` | `:seon.db/db` |
| `:my.plan/items-request` | `:seon.db/db` |
| `:my.plan/request` | `:seon.agent/id`, `:seon.db/db` |
| `:my.plan/update-request` | `:seon.agent/id`, `:seon.db/connection` |

### Public functions verified

- `my.agent/done`: `:seon.agent/id`, `:seon.db/db`
- `my.agent/identity`: `:seon.agent/id`, `:seon.db/db`
- `my.agent/settings`: `:seon.agent/id`, `:seon.db/db`
- `my.agent/settings!`: `:seon.agent/id`, `:seon.db/connection`
- `my.message/decline`: `:seon.agent/id`, `:seon.db/connection`
- `my.message/inbox`: `:seon.agent/id`, `:seon.db/db`
- `my.message/read`: `:seon.db/db`
- `my.message/send`: `:seon.agent/id`, `:seon.db/connection`
- `my.note/add!`: `:seon.agent/id`, `:seon.db/connection`
- `my.note/forget!`: `:seon.agent/id`, `:seon.db/connection`
- `my.note/notes`: `:seon.agent/id`, `:seon.db/db`
- `my.plan/add!`: `:seon.agent/id`, `:seon.db/connection`
- `my.plan/blocked`: `:seon.agent/id`, `:seon.db/db`
- `my.plan/complete!`: `:seon.agent/id`, `:seon.db/connection`
- `my.plan/current`: `:seon.agent/id`, `:seon.db/db`
- `my.plan/current!`: `:seon.agent/id`, `:seon.db/connection`
- `my.plan/item`: `:seon.db/db`
- `my.plan/plan`: `:seon.agent/id`, `:seon.db/db`
- `my.plan/ready`: `:seon.agent/id`, `:seon.db/db`
- `my.plan/ready-subjects`: `:seon.agent/id`, `:seon.db/db`
- `my.plan/steps`: `:seon.agent/id`, `:seon.db/db`
- `my.plan/update!`: `:seon.agent/id`, `:seon.db/connection`
