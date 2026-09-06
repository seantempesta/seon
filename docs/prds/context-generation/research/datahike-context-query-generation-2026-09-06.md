---
type: research
status: complete
tags: [research, agent, context, datahike]
---

# Datahike context-query generation — source review

Date: 2026-09-06  
Status: complete research; no production change

## Question and conclusion

An arriving message should not make context generation reacquire and render the
agent's whole reachable graph merely to discover the item that caused the run.
The database already records the precise connection needed for the common case:
the run's `:seon.cluster.run/trigger` is an indexed ref to the message, and the
message has a unique identity. Context generation can therefore read the exact
trigger and explicitly selected one-hop refs from the run's opening database
value. This is both cheaper and more honest than treating `since` as an inbox
API.

The smallest viable direction is ordinary, bounded `seon.db/q` and
`seon.db/pull` request data authored for an information need. Datahike already
owns parsing, planning, index choice, pull compilation, temporal wrappers,
resource limits, result caching, and read-dependency evidence. A new query DSL,
compiler, or registry would duplicate those mechanisms before the missing
semantic problem is understood.

Schema can mechanically prove identity attributes, cardinality, reference
direction, component status, and index availability. Facts can prove the
current trigger, prior run/evaluation relationships, a known transaction
change, and the database basis used for a recorded prompt. Neither schema nor
Datahike can infer which relationship is useful to the next model call, what a
concept means, or whether two differently rendered facts are redundant. Those
are context-policy decisions and must remain explicit.

## Dependency mechanisms already present

This review used the pinned Datahike checkout at gitlink
`cdcb5792db8bd599487f099437265d18a31164a5`.

### Queries and index selection

`q-with-evidence` normalizes the existing query options, executes the query,
and returns the result together with its dependency plan and resource evidence
(`reference-code/datahike/src/datahike/query.cljc:110-164`). The logical plan is
lowered to physical operations, and entity-bound patterns choose EAVT while an
indexed, attribute-and-value-bound pattern chooses AVET; an attribute-only
pattern chooses AEVT
(`reference-code/datahike/src/datahike/query/plan.cljc:31-44`). The planner
orders groups using estimated intermediate cardinality and dependency/cost
relationships (`reference-code/datahike/src/datahike/query/plan.cljc:1524-1663`).

This matters for messages. `:seon.cluster.message/id` is an identity attribute,
so an exact lookup ref is an AVET lookup followed by EAVT entity reads.
`:seon.cluster.message/to` is a ref but is not declared indexed
(`resources/seon/schemas/seon.cluster.message.edn:30-32,87-91`). A recipient
query therefore cannot assume AVET merely because its value is a ref; Seon's
schema bridge emits `:db/index true` only for the explicit `:seon.db/index`
property (`src/seon/schema/datahike.clj:221-260`), matching Datahike's index
eligibility check (`reference-code/datahike/src/datahike/db/utils.cljc:40-42`).
The current `unanswered-triggers` query grounds the recipient but reads the
attribute-wide `message/to` relation, then excludes messages already referenced
by a run (`src/seon/cluster/work.clj:619-650`). That query selects work; it need
not be repeated to render a run whose exact trigger is already recorded.

Datahike's parsed-query and physical-plan cache keys include the clauses, bound
variables, input cardinalities, and schema hash
(`reference-code/datahike/src/datahike/query.cljc:3460-3471`). Its result cache
can promote a result from an older database value only when source generations,
the conservative revision, and every concrete dependent attribute revision
match (`reference-code/datahike/src/datahike/query.cljc:2963-3049`). Seon's
`seon.db/q` calls `q-with-evidence` and appends this evidence to the active read
sink (`src/seon/db.clj:1118-1170`). These owners already provide safe reuse for
unchanged relevant attributes.

### Pulls

A pull selector is parsed once into an immutable `PullPlan`; the database-aware
arity also derives its attribute dependencies
(`reference-code/datahike/src/datahike/pull_api.cljc:55-105`). Explicit
attributes produce concrete dependencies. Wildcards, dynamic attributes, and
automatic component expansion widen the dependency to `:all`
(`reference-code/datahike/src/datahike/pull_api.cljc:107-186`). Forward pull
reads `[eid attr]` from EAVT; reverse pull reads `[attr eid]` from AVET, and
per-attribute limits are applied while datoms are consumed
(`reference-code/datahike/src/datahike/pull_api.cljc:304-390`). Pull and
pull-many accept the same work/result/result-weight boundaries and emit plan and
resource evidence (`reference-code/datahike/src/datahike/pull_api.cljc:490-600`).

Seon's current walk deliberately compiles a concrete, schema-wide,
bidirectional selector for a bounded graph distance. It enumerates every
installed scalar and both directions of every ref, avoiding an `:all`
dependency but still asking a broad semantic question
(`src/seon/render/walk.clj:86-151`). The compiled selector is cached by schema
projection, database schema identity, distance, and fit limits, then one pull
acquires the neighborhood (`src/seon/render/walk.clj:337-383`). Query generation
should complement this general discovery path with precise questions, rather
than recreate pull compilation or replace the walk before measurements.

### Bounds are part of the request

Datahike separately counts execution work, top-level results, and shallow
result weight, returning structured budget errors
(`reference-code/datahike/src/datahike/resource.cljc:13-109`). Cache admission
and cached-result certification refuse a value whose bounded shallow weight
cannot be established without realizing an uncounted/lazy collection
(`reference-code/datahike/src/datahike/resource.cljc:120-185`).

`:limit` is not a work limit. A simple unordered planned query can push
`offset + limit` into execution, but ordered relation and legacy paths may
materialize and sort matches before applying the requested limit
(`reference-code/datahike/src/datahike/query.cljc:4174-4208,4425-4519`). Every
generated read therefore needs an explicit `:max-work`, `:max-results`, and
`:max-result-weight` chosen by its caller's existing fit policy. A refusal is
useful context evidence; silently returning an arbitrary prefix is not.

## The exact new-message read

Inbound message creation commits the message's unique id, recipient, content,
and time together (`src/seon/cluster/message.clj:255-309`). When a run opens, it
records a ref from the run to that exact message. `run/opening-db` derives the
opening transaction from the run's `opened-at` datom and returns an inclusive
`as-of` database value, so the trigger is present and later transactions are
absent (`src/seon/cluster/run.clj:258-279`).

For context generation inside that run, the query can first derive the trigger
id from the run id:

```clojure
{:query '[:find ?message-id .
          :in $ ?run-id
          :where
          [?run :seon.cluster.run/id ?run-id]
          [?run :seon.cluster.run/trigger ?message]
          [?message :seon.cluster.message/id ?message-id]]
 :args [opening-db run-id]
 :max-work max-work
 :max-results 1
 :max-result-weight max-result-weight}
```

Then it can pull only fields needed by the message renderer and explicitly
chosen one-hop identities:

```clojure
{:db opening-db
 :selector [:seon.cluster.message/id
            :seon.cluster.message/content
            :seon.cluster.message/at
            :seon.cluster.message/ordinal
            {:seon.cluster.message/from [:seon.cluster.agent/id]}
            {:seon.cluster.message/to [:seon.cluster.agent/id]}
            {:seon.cluster.message/caused-by
             [:seon.cluster.message/id]}]
 :eid [:seon.cluster.message/id message-id]
 :max-work max-work
 :max-results max-results
 :max-result-weight max-result-weight}
```

The first query may be unnecessary where the caller already holds the trigger
lookup ref. Handing that identity through is preferable to rediscovering it.
The transaction listener has even more precise transient evidence: on a
`:seon.cluster.message/to` datom, the datom entity is the new message and its
value is the recipient. The current wake route deliberately sends only a
payload-free wake through a sliding-one channel because the receiving pass
re-derives durable facts (`src/seon/cluster/wake.clj:166-253`). That design is
correct for recovery and coalescing. A transaction-report identity may be a
fast hint, but cannot become the sole authority: reports can coalesce and do not
survive process loss.

A related ref-follow question remains ordinary Datalog. For example, to obtain
the evaluations belonging to the run that processed a message:

```clojure
{:query '[:find ?run-id ?ordinal ?source
          :in $ ?message-id
          :where
          [?message :seon.cluster.message/id ?message-id]
          [?run :seon.cluster.run/trigger ?message]
          [?run :seon.cluster.run/id ?run-id]
          [?form :seon.cluster.run.form/run ?run]
          [?form :seon.cluster.run.form/ordinal ?ordinal]
          [?form :seon.cluster.run.form/source ?source]]
 :args [db message-id]
 :max-work max-work
 :max-results max-results
 :max-result-weight max-result-weight}
```

The form expresses semantic intent that schema alone cannot derive. Schema can
tell a structural generator that these attributes are refs and which are
cardinality-many; it cannot decide that evaluation source is informative while
another reachable edge is redundant.

## Temporal queries: three different questions

Datahike's `as-of`, `since`, and `history` wrappers filter the origin database's
indexes; they are views, not event queues. `as-of` includes its transaction
point, while `since` excludes it
(`reference-code/datahike/src/datahike/db.cljc:142-152`). The wrappers retain
the origin schema and index access and install a temporal search predicate
(`reference-code/datahike/src/datahike/db.cljc:610-701`). The planner builds
against the origin indexes and statistics but executes lookup resolution and
clauses against the actual temporal source
(`reference-code/datahike/src/datahike/query.cljc:4440-4501`).

Use each operator for its own question:

```clojure
;; What did the run's context derivation read at opening?
(db/q query (db/as-of current-db opening-tx) run-id)

;; Which surviving assertions are visible strictly after the prior context basis?
(db/datoms (db/since current-db prior-basis) :eavt)

;; What assertions and retractions occurred for one entity/attribute?
(db/datoms (db/history current-db) :eavt message-eid attribute)
```

`since` alone cannot return a complete current entity. Its non-historical view
filters to transactions after the point and assembles surviving assertions;
unchanged attributes and pure retractions are absent
(`reference-code/datahike/src/datahike/db.cljc:173-192`). To ask “which
currently present messages changed after basis,”
use two named database sources: one current source for present state and one
since source only as the change witness.

```clojure
{:query '[:find ?message ?id ?content
          :in $current $delta ?agent
          :where
          [$delta ?message :seon.cluster.message/content _ ?tx]
          [$current ?message :seon.cluster.message/to ?agent]
          [$current ?message :seon.cluster.message/id ?id]
          [$current ?message :seon.cluster.message/content ?content]]
 :args [current-db (db/since current-db prior-basis) agent-eid]
 :max-work max-work
 :max-results max-results
 :max-result-weight max-result-weight}
```

The current-side clauses make a retracted message/content disappear rather than
turning the old value into current context. If the information need includes
deletions, query `history` for datoms whose `added` flag is false and render the
retraction explicitly; a current/since join intentionally cannot report an
entity that is no longer present. Multi-source dependency evidence is tracked
per parsed source and argument position, widening to `:all` when a source or
clause cannot be understood
(`reference-code/datahike/src/datahike/query.cljc:2868-2933`).

Temporal caveats are material here:

- `:seon.cluster.message/content` is declared no-history, so old content is not
  reconstructable from database history after replacement or retraction
  (`resources/seon/schemas/seon.cluster.message.edn:6-10`). The recorded context
  prompt is the durable evidence for bytes previously rendered.
- Absence from a current query does not prove processing; it can mean a
  retraction, a failed delivery, or a mismatched snapshot. A run's trigger ref
  proves that a run claimed the message.
- `since` is strictly exclusive and `as-of` inclusive. Reusing the wrong basis
  produces a one-transaction gap or duplicate.
- A Date-valued message `:at` is authored data, not transaction order. The
  unanswered-work owner sorts with transaction id as an additional ordering
  input (`src/seon/cluster/work.clj:633-649`).
- A `history` query answers what changed, while `as-of` answers what state was
  readable. They should not be substituted for one another. Nested temporal
  wrappers can fall back to the legacy query engine
  (`reference-code/datahike/src/datahike/query.cljc:4440-4443`).

A short primer need not force all three operators into every generated form.
The shortest correct form is the one whose operator matches the question:
exact lookup for the arriving message, `as-of` for prior state, `since` for a
change witness, and `history` for retractions. Combining operators merely to
demonstrate breadth adds source and execution cost without adding information.

## What the facts prove

The following states must remain distinct:

| Claim | Current proof | What it does not prove |
|---|---|---|
| Received | A message row exists with `message/id`, `to`, and content; its transaction report proves the commit and exact changed datoms. | That a run claimed it, rendered it, or a model saw it. |
| Claimed for processing | A run exists whose indexed `run/trigger` ref points to the message. Run opening and trigger are committed atomically by the run owner (`src/seon/cluster/loop.clj:1266-1318`). | Terminal evaluation, successful response, or inclusion in context. |
| Processed | Run/form/evaluation facts show the relevant evaluation and its terminal disposition. | That the result was rendered into a particular prompt. |
| Rendered for a provider request | A context capture records the exact prompt, database basis, and ordered contribution hashes before the provider call (`src/seon/context.clj:154-200`; `src/seon/cluster/loop.clj:1494-1539`). | That the provider request was transmitted. Current contribution rows are named only `:walk` and do not identify which entity/query produced each segment. |
| Provider lifecycle observed | Attempt facts distinguish request transmitted, response started, and output observed (`resources/seon/schemas/seon.ai.edn:4-56`). | Internal model attention or semantic use of any prompt segment. |
| Presented in a browser | No durable browser acknowledgement fact exists. | Server package generation or an SSE write alone cannot prove DOM presentation. |

The exact prior prompt text and basis are durable, but exact prior *context
entries* are not. Contributions record position, block name, hash, and token
count; the current block name is broadly `:walk`
(`src/seon/context.clj:136-152`; `src/seon/cluster/prompt.clj:151-217`). A
generator therefore cannot truthfully exclude “concepts already shown” from
contribution rows alone. Parsing prompt text or treating hashes as semantic
identity would violate the facts-over-inference rule. This missing provenance
is an uncertainty to test before any novelty-aware generator is designed.

Delivery failure also produces no successful message row: message delivery
returns rows and error values separately (`src/seon/cluster/message.clj:311-431`).
An expected id missing from current facts is not health and is not proof that an
empty inbox was delivered.

## Selecting a small set of context forms

Selection is a multi-objective problem, not “fewest forms” and not “shortest
characters.” Score candidates on separately reported dimensions:

1. **Semantic coverage:** does the set cover the current instruction, causally
   connected activity/results, unresolved failures, and the identities needed
   to interpret them?
2. **Redundancy:** do two forms return the same entities/facts or render the same
   concept? Exact entity/attribute provenance can establish overlap; textual
   similarity cannot substitute for it.
3. **Rendered prompt tokens:** what does the existing token estimator report
   after rendering and fit? Source characters are not output tokens.
4. **Database work:** what do Datahike's resource evidence and plan report for
   the actual database distribution? Result count alone does not bound work.
5. **Result weight:** can the retained value be certified inside the caller's
   existing fit bound?
6. **Source size:** how many characters/tokens must be taught or emitted for the
   query form? This matters to an agent authoring or reading it, but is distinct
   from execution and output costs.

A weighted selector may combine normalized measurements for an experiment, but
the raw dimensions and hard correctness constraints must remain visible. A
single scalar hides whether a winner saved ten source characters by dropping a
required failure fact or moved work from query execution into rendering.
Correctness and declared bounds are admission gates; weights rank only admitted
candidates. Minimum cover should mean the smallest admitted set covering
declared information needs, with redundancy derived from shared fact
provenance. It should not mean an arbitrary target number of forms.

Candidate families for the first comparison are deliberately small:

- exact current-trigger pull;
- one query following trigger → run → evaluations/results;
- one bounded current/since activity query when the prior capture basis exists;
- one explicit failure query, if failures are declared as required coverage;
- the existing bounded root walk as the completeness baseline.

An agent can author these as ordinary Clojure data using the current APIs. A
mechanical helper may later emit an exact-identity pull selector from installed
schema facts, but it must receive the root and desired ref directions. A generic
schema traversal cannot infer relevance and would reproduce the current broad
walk with a different name.

## Decisive experiment

Replay one real run against the same immutable opening database value with two
arms:

1. the current compiled root walk;
2. exact trigger lookup/pull plus the smallest declared activity and failure
   queries needed for the same information requirements.

Record, per arm: admitted/refused status; facts/entities and declared concepts
covered; Datahike work/result/result-weight evidence; dependency-plan
attributes; query source characters; rendered prompt tokens; render calls; and
cold/warm elapsed time. Include a large pre-existing inbox with one new trigger,
an older unanswered message, a caused-by chain, a completed prior run, an edited
or retracted fact, and a delivery refusal. Run the temporal candidates at the
exact prior capture basis. Verify that:

- the trigger item is present exactly once;
- an older pending item is not mislabeled as current;
- a retraction is either reported explicitly through history or absent from the
  current-state arm, never rendered as a current value;
- a delivery refusal is visible as an error, not an empty success;
- unchanged unrelated attributes permit cached reuse while a changed dependent
  attribute invalidates it;
- the bounded refusal remains evidence-complete.

The experiment should decide whether precise forms materially reduce work and
tokens without losing declared concept coverage. It should not set permanent
capacity constants. If concept equivalence cannot be judged from current
provenance, that result is evidence for the smallest missing fact at the
context-contribution boundary, not a reason to add a query language.

## Recommendation and unresolved questions

Use the run's exact trigger and opening database value for the arriving-message
context first. Keep `unanswered-triggers` as work selection, separate from
prompt generation. Express additional questions as bounded ordinary query/pull
maps and let Datahike own compilation, planning, temporal execution, caching,
and dependency evidence. Teach `as-of`, `since`, and `history` as separate
operators tied to state, delta, and retraction questions; introduce a
current/since two-source join only when current state plus a change witness is
actually required.

Do not yet add an index to `message/to`: the exact-trigger path avoids that scan
for the common context case, and the remaining work-selection distribution must
be measured first. Do not add a generator registry or novelty compiler. First
run the comparison above and determine whether current contribution provenance
can prove concept coverage.

Open uncertainties are:

- which information needs beyond the current trigger are required for the
  minimum useful context across actual tasks;
- whether context contribution facts need an entity/read identity to make
  novelty and redundancy queryable;
- how often recipient-wide unanswered-work selection, rather than context
  rendering, dominates database work;
- whether no-history message/form payloads require additional immutable capture
  provenance for historical explanation;
- which measured weighting policy best trades prompt tokens, execution work,
  result weight, and source size without obscuring correctness.

No live MCP probe was needed: the relevant index, temporal, cache, wake, and
context semantics are explicit in the pinned dependency and current first-party
source. No cluster or database was mutated.
