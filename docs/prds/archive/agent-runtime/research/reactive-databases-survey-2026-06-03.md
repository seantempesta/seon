---
type: research
status: draft
tags: [research, database, agent, flow]
---

# Reactive Databases, Incremental View Maintenance, and the React/Central-Store Analogy — Survey for Seon's Reactive Agent Topology

> **Terminology note.** This is a point-in-time research artifact; it predates the
> canonical [glossary](../glossary.md). Where it says *scout* read *subagent/agent*,
> *orchestrator* read *agent*, *read-set* read *patterns*. Verbatim external quotes
> (e.g. Convex's "read set") keep their original words.

## TL;DR

The thing Seon wants to build — "agents are entities in one immutable central store, each holds a
standing reactive query, a write wakes only the agents whose query depends on the changed data, they
recompute a summary, and the orchestrator + UI re-render off those summaries" — is a **known,
well-understood architecture** that shows up independently in three communities that rarely talk to
each other:

1. **Reactive databases** (Convex, RethinkDB, Meteor, Firestore, ElectricSQL) — server tracks a
   query's *read-set* and re-runs the query when a write overlaps it.
2. **Incremental View Maintenance / dataflow** (Differential Dataflow, Materialize, DBSP, and — the
   single closest prior art — **3DF / declarative-dataflow**, a reactive *Datalog* engine built on
   differential dataflow and explicitly designed to sit on top of *Datomic*).
3. **Fine-grained UI reactivity** (Solid signals, MobX, React/Redux) — a derivation records which
   observables it reads *during execution* and that read-set becomes its subscription.

The keystone mechanism is identical across all three and is exactly what Seon needs: **a computation's
read-set, captured automatically by recording what it touched while it ran, becomes its wake-up set.**
You do not declare dependencies; you observe them. Convex calls it the "read set"; Solid/MobX call it
"dependency tracking by recording reads inside a tracking scope"; differential dataflow makes it
structural (the dataflow graph edges *are* the dependencies). Posh/re-posh already do exactly this for
DataScript: a tx-listener checks incoming datoms against patterns derived from each subscribed query.

**The three designs Seon should steal from, in priority order:**

1. **Convex** — the closest *operational* match. Read-set tracking + OCC + a single "subscription
   manager" that walks the transaction log once per commit and asks "does this commit overlap any
   subscription's read-set?" This is precisely "which agents wake up." Convex even solves the
   consistency problem we have (all subscriptions advance to the *same logical timestamp*).
2. **3DF / declarative-dataflow (Göbel)** — the closest *conceptual* match: reactive Datalog queries
   over a Datomic-shaped fact store, returning *diffs*. If we ever need true incremental query results
   (not just "this agent is dirty, re-run it"), this is the blueprint, and it was literally built for
   Datomic.
3. **Solid/MobX signals** — the proof that *automatic read-set capture* is the right primitive, plus
   the **glitch-free staged-update algorithm** (topological sort / stale-then-ready) we need to keep
   the cascade (agent A → agent B → orchestrator → UI) from showing intermediate inconsistent states.

**The single hardest unsolved problem** (shared by every system below): *the "which queries are
affected by this change" problem at Datalog generality.* The cheap version — pattern-match the changed
datoms' attributes against each agent's declared read-set and re-run dirty agents fully — is what
Convex/Posh/Meteor do and is almost certainly what Seon should ship first. The expensive version —
incrementally compute the *new result diff* without re-running — is the differential-dataflow/3DF
problem and is genuinely hard (it needs a stateful operator graph maintaining per-operator indexes and
a partial-order timestamp scheme). Recommendation: ship the cheap version, keep 3DF as the escape
hatch for the few agents whose queries are too expensive to re-run wholesale.

---

## 1. Immutable / fact-based / bitemporal databases and their reactivity story

### 1.1 Datomic — transaction report queue (our actual substrate's ancestor)

Datomic is the direct ancestor of datahike, and its reactivity primitive is the **transaction report
queue**. This maps almost 1:1 onto datahike's tx-listeners.

> "Any peer process in the system can request a transaction report queue of every transaction against a
> particular database. The queue delivers a report for every transaction submitted while a peer is
> connected to the database, even those submitted by other peers."
> — <https://blog.datomic.com/2013/10/the-transaction-report-queue.html>

Each report carries the full context Seon needs for reactive evaluation:

- `:tx-data` — all datoms added/retracted by this transaction (the change set).
- `:db-before` — the database value immediately before the tx.
- `:db-after` — the database value as of tx completion.

The canonical reactive pattern (waf/push-demo, thegeez's "Gin Rummy with Datomic") is exactly Seon's
intended topology: *"all the listening components on the server are notified of the update via the
transaction report queue, and ... a message will be sent via SSE."*
(<https://github.com/waf/push-demo>, <https://thegeez.net/2014/06/12/gin_datomic.html>)

**Critical gotcha to copy into Seon's design:** the queue is *unfiltered* — it delivers *every* tx, and
*"when you are done monitoring the queue, remove it by calling `remove-tx-report-queue` otherwise it
will continue to accumulate transaction reports and consume memory."* The reactive layer is responsible
for the "which subscribers care" filtering; the DB just fire-hoses every change. **This is the single
most important architectural fact for Seon:** datahike (like Datomic) gives you *change detection for
free* but gives you *zero* "which query is affected" routing. That routing is the thing we have to
build. (Memory/CLAUDE.md note: this is consistent with Seon's "tx-listener delivers fully-namespaced
maps, section functions decide relevance" model.)

### 1.2 Convex — the closest operational prior art (STUDY CLOSELY)

Convex is a commercial reactive database whose entire value proposition is *exactly* Seon's thesis:
"you modify the data and everything re-renders," implemented server-side with rigor. Its internals are
publicly documented and are the best blueprint available.

**Read-set tracking (the keystone):**

> "Queries automatically track which pieces of data they have read, known as their 'read sets.' That
> lets Convex immediately detect when any of the data a query depends on changes."
> — <https://docs.convex.dev/understanding/>

> "The engine automatically tracks which documents a query function reads during its execution. If a
> mutation changes any of those documents, the engine marks the query as 'dirty' and prepares a
> refresh." — <https://makersden.io/blog/convex-architecture-deep-dive-reactive-database-functions-sync>

**The transaction model (read-set / write-set / OCC) — verbatim from Convex's own writeup:**

Each transaction has three parts (<https://stack.convex.dev/how-convex-works>):

- **Begin timestamp** — selects which snapshot the transaction reads from.
- **Read set** — *"records all index ranges and document lookups performed."*
- **Write set** — *"the transaction accumulates them in its write set, which contains a map of each ID
  to the new value proposed by the transaction."* (Writes buffered, not applied, until commit.)

The **committer** is the *sole writer* to an append-only transaction log:

> 1. Assigns a commit timestamp larger than all previous commits.
> 2. "Checks whether writes between the begin and commit timestamps overlap with the transaction's read
>    set."
> 3. If no overlap → commits as if it ran at the later timestamp.
> 4. If overlap → OCC conflict, retry at a new begin timestamp.

**The subscription manager — this IS Seon's "which agents wake up" component:**

> "The subscription manager aggregates all active client subscriptions and walks the transaction log
> once per commit. When a new transaction overlaps with a subscription's read set, the subscription
> becomes stale. ... Rather than each client scanning independently, the subscription manager
> efficiently determines whether the entry overlaps with any active subscription's read set by
> consolidating work across all sessions."
> — <https://stack.convex.dev/how-convex-works>

Note the design choice: *one walk of the log per commit*, checking all subscriptions, rather than each
subscriber polling. For Seon, replace "subscription" with "agent's standing query" and "client" with
"agent"/"UI" and you have the architecture verbatim.

**Determinism requirement (matters for re-run correctness):** Convex requires query/mutation functions
to be deterministic (V8 sandbox, no `fetch`/IO in mutations) *because* the engine re-runs them:
*"it wouldn't be safe to retry the mutation, which would then send the email twice."* Seon's analog:
an agent's reactive-query recompute must be a pure function of `db-after` so it can be re-run safely on
any wake. Side effects (LLM calls, external writes) need the same care Convex gives mutations.

**Consistency — Convex solves the exact cascade-coherence problem Seon has:**

> "All database reads inside a single query call are performed at the same logical timestamp, and the
> system updates all subscriptions to the same logical moment in time in the database."
> — <https://docs.convex.dev/understanding/> + <https://stack.convex.dev/how-convex-works>

> "All queries in the client's query set are at the same timestamp" — preventing cross-query
> inconsistency. Convex provides *"true serializability"* via OCC/MVCC.
> — <https://docs.convex.dev/database/advanced/occ>

This is the answer to Seon's "agent A's write wakes agent B wakes orchestrator — does the UI ever see a
half-updated world?" Convex's answer: advance every subscriber to the *same* logical timestamp
atomically; never render a mix of timestamps.

### 1.3 XTDB — bitemporal, but reactivity is not native

XTDB is bitemporal (user-assigned *valid time* + system *transaction time*), document-centric,
Datalog-queryable, Clojure-native — the closest cousin to datahike in the immutable-DB space. But
**reactivity is explicitly a non-feature / open research area**:

> "XTDB does not natively implement 'non-oblivious retroactivity' (i.e. persisted queries and cascading
> corrections), although this is an important area of investigation for event sourcing applications,
> temporal constraints, and reactive bitemporal queries."
> — <https://v1-docs.xtdb.com/resources/faq/>

Takeaway for Seon: even the most directly comparable bitemporal Clojure DB punts on exactly the
problem we're solving. There is no off-the-shelf reactive-Datalog answer in this lineage — we build it
on tx-listeners, same as Datomic users do.

### 1.4 RethinkDB — the canonical "reactive database" (changefeeds)

RethinkDB is the system that coined the modern framing. Its model: a query that *stays alive*.

> "Changefeed queries start out like any regular query, but instead of the query going over the
> collection once and then returning its results, the query remains alive, and while the query is alive
> it returns any new results as they are entered into the database."
> — <https://rethinkdb.com/docs/changefeeds>

The inversion is the philosophical point Seon shares:

> "Instead of polling for changes, the developer can tell RethinkDB to continuously push updated query
> results to applications in realtime ... data pushing updates rather than the application pulling
> them."

Change format is a delta: `{old_val, new_val}` (insert → `old_val=null`, delete → `new_val=null`).
**Honest limitation to note:** *"changefeeds are unidirectional with no acknowledgement returned from
clients, they cannot guarantee delivery"* — and supporting them required rewriting the query engine,
distributed layer, caching, and storage from scratch in C++. Lesson: *true* live-query results (not
just "something changed, re-run") are invasive to retrofit. This is an argument for Seon's "dirty +
re-run" approach over "maintain incremental result."

### 1.5 Meteor — oplog tailing + Minimongo (the "re-run the query selector" model)

Meteor's LiveQuery is the most directly copyable *cheap* design, because it solves "which queries are
affected" with **query-selector re-evaluation against the change**, not incremental maintenance:

> "OplogObserveDriver needs to understand MongoDB selectors, field specifiers, modifiers, and sort
> specifiers deeply because it must understand how write operations in the oplog interact with queries.
> To handle these structures, OplogObserveDriver uses Meteor's implementation of the MongoDB query
> engine, Minimongo."
> — <https://github.com/meteor/docs/blob/master/long-form/oplog-observe-driver.md>

So: tail the oplog (= datahike tx-listener), and for each live query, use the query engine itself to
decide whether the changed doc enters/leaves/updates the result set. Minimongo doubles as the
client-side cache. **Seon analog:** the tx-listener gets `:tx-data`; for each agent's standing Datalog
query, decide cheaply whether the changed datoms *could* affect it (attribute/pattern match — what Posh
does), then re-run the affected ones against `db-after`. Meteor's *poll-and-diff* fallback (re-run
whole query, diff results) is the brute-force baseline; oplog tailing is the optimization.

### 1.6 Firebase/Firestore — live queries (managed, opaque)

Firestore live queries push result-set deltas to clients over a persistent connection; the "which
queries are affected" routing is server-internal and proprietary (index-range-based, similar in spirit
to Convex's index-range read-sets). Useful as evidence that the pattern is mainstream, but it offers no
copyable mechanism. (General reference: stack.convex.dev real-time DB comparisons,
<https://stack.convex.dev/best-real-time-databases-compared>.)

### 1.7 ElectricSQL — Postgres sync via "Shapes" (partial-replica live queries)

Electric is a Postgres sync engine. Its reactive primitive is the **Shape**:

> "The core primitive for controlling sync in Electric is the shape, which is a partial replica of a
> table that includes the subset of rows matching a user-defined WHERE clause ... a bit like a live
> query for syncing." — <https://electric-sql.com/blog/2024/07/17/electric-next>

Mechanism: *"consumes the logical replication stream and fans out data into Shapes, which Clients then
consume."* So change detection = Postgres logical replication (WAL); routing = match each WAL change
against each Shape's WHERE clause; delivery = HTTP. This is the same shape as Seon: one change stream,
fan out to many standing filtered queries. (v1.0: <https://electric-sql.com/blog/2025/03/17/electricsql-1.0-released>.)

### 1.8 PostgreSQL LISTEN/NOTIFY + logical replication (the build-it-yourself baseline)

Postgres gives two reactive primitives, and the contrast is instructive for Seon's delivery
guarantees:

- **LISTEN/NOTIFY** — pub/sub channels, but *"at-most-once delivery (requires active connections when
  notifications occur), payloads limited to 8000 bytes, no persistence for missed messages."* Do **not**
  model Seon's agent-wakeup on at-most-once delivery.
- **Logical replication / WAL** — *"holds all events that change data ... INSERTs, UPDATEs, DELETEs,"*
  durable, replayable. This is the robust CDC substrate; it's what Electric and Debezium build on.
- (<https://datacater.io/blog/2021-09-02/postgresql-cdc-complete-guide.html>,
  <https://blog.sequinstream.com/a-developers-reference-to-postgres-change-data-capture-cdc/>)

**Seon lesson:** datahike's tx-log / `since`/`as-of` is our WAL equivalent — durable and replayable,
which is what lets a sleeping agent *catch up* on missed transactions when it wakes (see §5). Don't
build wakeups on a NOTIFY-style ephemeral bus; build them on the replayable log.

---

## 2. Incremental View Maintenance (IVM) and Differential Dataflow

This section answers Seon's keystone question directly: *given a change set and a set of standing
queries, how do you determine which results changed WITHOUT re-running every query?*

### 2.1 The IVM problem, stated

> "Incremental view maintenance optimizes how databases refresh materialized views ... Rather than
> recomputing entire datasets when source data changes, IVM processes only the affected portions,
> called deltas. ... QΔ built by our algorithm is faster than Q by a factor of O(|DB|/|ΔDB|)" —
> potentially *"10 million times"* faster when the change is small relative to the data.
> — <https://materializedview.io/p/everything-to-know-incremental-view-maintenance>

The classical technique is **delta queries via relational/bag algebra**: translate a query into
relational-algebra operators, then derive, for each operator, how an insert/delete on its inputs
propagates to its output. For Datalog specifically this is **semi-naive evaluation** — when computing a
recursive query incrementally, an operator *"only needs to revisit previously received input records
with the same keys as newly arriving inputs."* The catch, stated plainly:

> "[The bag-algebra approach] does not work well for complex and computationally expensive queries,
> especially with recursive or nested structures."

### 2.2 Differential Dataflow (McSherry) — the deep dive

Differential dataflow is the most general known solution, and it is the conceptual core of Materialize
and 3DF. The central idea: **never store collections; store *differences*, indexed by a partial-order
timestamp.**

> "Rather than maintaining a dataset at a computation point, it maintains a collection of differences
> from which the data can be efficiently updated."
> — <http://www.frankmcsherry.org/differential/dataflow/2015/04/07/differential.html>

The defining equation (verbatim):

```
Collection[t] = sum_{s <= t} Difference[s]

```

and the inverse, which is how an operator computes *only* its output change:

```
Difference[t] = Collection[t] - sum_{s < t} Difference[s]

```

**How it answers "which outputs changed without recomputing everything":** the computation is a
**dataflow graph** of data-parallel operators (`map`, `filter`, `join`, `group`, `reduce`). Each
operator maintains indexed state keyed by its key function. When a change arrives:

> "If I have a collection of one billion records, and I am presented with a single change: 'add/remove
> record', I only need to apply the key function to record [and revisit] previously received input
> records with the same keys as newly arriving inputs."

So the graph *structurally encodes the dependencies* (an edge means "downstream depends on upstream"),
and each operator's per-key index means a change only touches the groups sharing its key. There is no
separate "which query is affected" lookup — affectedness *is* graph reachability, and the per-operator
indexes prune within each operator.

**The partial-order / multidimensional-timestamp trick** is what makes it handle *iterative* queries
(transitive closure, recursion) incrementally. Timestamps are `(epoch, iteration)` pairs ordered by a
*partial* order, so a late-arriving input change at `(1,0)` is reconciled against `(0,0)` without
invalidating the whole iteration history at `(0,1032)`. This is Naiad/Timely's contribution
(McSherry, Murray, Abadi, Isard, Isaacs at MSR-SV; open-sourced as Timely + Differential Dataflow in
Rust). (<https://cacm.acm.org/research/incremental-iterative-data-processing-with-timely-dataflow/>)

**Honest cost (from McSherry himself):** determining *which downstream indices* require updating
*"remains complex — requiring something like a timely dataflow system."* I.e. the machinery is real and
heavy. This is the central trade Seon faces.

### 2.3 Materialize and DBSP — IVM productized

- **Materialize** is differential dataflow wearing a SQL hat: *"leverages differential dataflow
  directly ... maintains views continuously and incrementally — as underlying data changes, affected
  results update automatically with millisecond latency."*
- **DBSP** (Budiu et al., VLDB 2023, <https://www.vldb.org/pvldb/vol16/p1601-budiu.pdf>) is a cleaner
  theoretical foundation (Feldera is built on it): a small algebra (a handful of stream operators) into
  which *all* of SQL — including grouping, aggregation, and recursion — compiles, with a mechanical
  procedure to turn any query into its incremental version. *"A SQL-to-DBSP compiler translates standard
  SQL queries into DBSP circuits and implements an algorithm to generate a streaming version of any SQL
  query."* The four-operator DSP framing (integration/differentiation over streams) is the elegant part
  — if Seon ever wants a *principled* incremental Datalog, DBSP's algebra is the cleanest reference.

### 2.4 3DF / declarative-dataflow — THE closest prior art for Seon (Datalog + diffs + Datomic)

This is the most important system in the entire survey for Seon, and it is the least famous. Nikolas
Göbel's **3DF / declarative-dataflow** is a *reactive Datalog query engine built on differential
dataflow, explicitly designed to sit on top of Datomic/Kafka.* It is, almost exactly, "Seon's reactive
agent topology" minus the agents.

> "3DF is a pub/sub system in which subscriptions can be arbitrary Datalog expressions. Subscribers
> register queries with the broker, and data sources (such as Kafka, Datomic, or any other
> source-of-truth) publish new data to it. All subscriber queries affected by incoming data will be
> notified with a diff, describing how their results have changed."
> — search synthesis of <https://github.com/comnik/declarative-dataflow> and
>   <https://github.com/sixthnormal/clj-3df>

> "The Datalog implementation is modeled after Datomic's query language and aims to support the same set
> of features, doing this efficiently thanks to being built on top of differential dataflows."

How a Datalog query becomes a dataflow (Göbel, <https://www.nikolasgoebel.com/2018/09/13/incremental-datalog.html>):
each clause compiles to operators — a triple-pattern `[?e :name ?name]` becomes a filtered scan
(`HasAttr`), shared `?e` variables become `Join`s, `:find` becomes `Project`:

```clojure
{:Project
  [{:Join [{:HasAttr [?e :name ?name]}
           {:HasAttr [?e :age ?age]} ?e]}
   [?e ?name ?age]]}

```

> "Differential dataflow ... allows us to express incremental computations ... using the familiar
> language of `map`, `filter`, `join`, `group`, and friends."

The pub/sub contract is *exactly* what Seon's orchestrator/UI want:

> "Instead of polling for changes, clients can register their information interests and then
> continuously receive updates as new data enters the system."

**Why this matters so much:** Seon's "agent registers a standing reactive query and gets woken with the
relevant change" is the 3DF model with an LLM bolted onto the subscriber. 3DF proves the model is
coherent *for Datalog over Datomic-shaped facts specifically*. If Seon ever needs incremental *results*
(diffs) rather than dirty-and-re-run, 3DF/declarative-dataflow is the codebase to read first, and
clj-3df is the Clojure client. The cost is also clear: it's a whole differential-dataflow runtime in
Rust — heavy, and (per the project's experimental status) not a turnkey dependency.

### 2.5 The cheap alternative actually used by reactive DBs: read-set / pattern overlap

Crucially, **almost none of the *production* reactive databases in §1 do true IVM.** Convex, Meteor,
Firestore, Posh — they all use the *cheap* approximation:

- Capture each query's read-set (or query pattern).
- On a change, test overlap between the change and each read-set.
- Re-run the *whole* query for the (usually few) overlapping subscriptions.

This is O(re-run the dirty queries) not O(diff), and it's correct, simple, and what ships. IVM/3DF is
the optimization you reach for only when a single query is too expensive to re-run on every relevant
write. **For Seon: start cheap (read-set overlap + full re-run per dirty agent); reserve differential
dataflow for the pathological agent.**

---

## 3. FRP and the React/central-store analogy made precise

The user's mental model — "React with a central data store: you modify the data and everything
re-renders" — is correct, and the precise mechanism by which the *good* versions of it avoid
re-rendering everything is **the same read-set-by-recording-reads trick** as Convex. This is the
section that ties the DB world to the UI world.

### 3.1 React + Redux (the coarse baseline)

Redux is the literal "one central store, modify it, everything re-renders" model. But vanilla Redux is
*coarse*: a dispatch notifies *every* connected component, and each must run a selector + equality check
to decide whether to re-render. There is **no automatic dependency tracking** — you hand-write
`mapStateToProps`/selectors to declare what each component reads. This is the *declare-your-deps*
approach. It's the architecture Seon explicitly wants to *improve on* by deriving the read-set
automatically. (Seon's CLAUDE.md "reactive context — derived by default" is essentially "be Solid, not
coarse Redux.")

### 3.2 Solid.js signals — automatic dependency tracking (THE keystone mechanism)

Solid is the cleanest demonstration of the technique Seon should adopt for capturing a query's
read-set.

> "Solid automatically tracks the dependencies of an effect, so you do not need to manually specify
> them ... No dependency array needed — Solid automatically tracks which signals you read."
> — <https://docs.solidjs.com/concepts/effects>

The exact mechanism — *recording reads during execution inside a tracking scope*:

> "When a signal is called within a tracking scope, the signal adds the dependency to a list of
> subscribers ... when a component accesses the value of reactive primitives, the framework records
> this as a dependency. The getter must be called to read the value, and Solid tracks which
> computations depend on it at runtime."
> — <https://docs.solidjs.com/advanced-concepts/fine-grained-reactivity>

So: there is a "currently-running computation" pointer; every signal *read* registers the current
computation as a subscriber. When the computation finishes, its subscription set = exactly the signals
it touched. **This is identical to Convex's read-set capture, just in-process and synchronous.**

**Direct map to Seon (this is the load-bearing analogy):** if a datahike Datalog query is executed with
an instrumented `db` value that *records which datoms/attributes the query touched*, then that recorded
read-set IS the agent's wake-up set — *derived automatically rather than declared*. This is precisely
the user's intuition made mechanical. Posh already does a coarse version (pattern match on attributes,
§3.5); a Solid-grade version would record the actual datoms read. The open question for Seon is how
fine-grained datahike's query engine lets you instrument the read (per-datom vs per-attribute vs
per-index-range — Convex uses index *ranges*, a good middle ground).

### 3.3 MobX — same auto-tracking, plus the glitch-free algorithm

MobX uses the identical read-recording technique and documents the *staged update algorithm* Seon needs
for cascade coherence.

Auto-tracking via a derivation stack:

> "Every observable that is accessed will register itself as a dependency of the topmost function of the
> derivation stack." After the run, MobX *"captures the list of accessed observables and diffs it
> against previous dependencies. Any removed items will be unobserved ... and any added observables will
> be observed until the next computation."*
> — <https://medium.com/hackernoon/becoming-fully-reactive-an-in-depth-explanation-of-mobservable-55995262a254>

The principle (quote this in Seon's design doc):

> "A minimal, consistent set of subscriptions can only be achieved if subscriptions are determined at
> run-time."

That sentence is the entire argument for deriving an agent's wake-set from what its query *actually
read* rather than from a hand-declared list of attributes — the declared list will drift, over- and
under-subscribe.

### 3.4 Reactive Extensions (Rx) — the push-stream lineage

Rx (Observables) is the other FRP tradition: changes as *push streams* with composable operators
(`map`/`filter`/`merge`/`combineLatest`). It's the conceptual ancestor of changefeeds (§1.4) and of
core.async-flow-style pipelines. Less relevant to read-set capture, but relevant to how Seon *delivers*
wakeups: a per-agent observable of "transactions touching my read-set" is the Rx framing of the
tx-listener fan-out, and core.async flow (Seon's chosen backbone) is the same idea.

### 3.5 Posh / re-posh — the technique ALREADY applied to Datalog (read this first)

This is the existing Clojure implementation of exactly the §3.2 mechanism over a DataScript (Datomic-
shaped) DB, and it's the most directly liftable design for Seon:

> "Every time a transaction hits the database, it fires any attached listeners. Posh attaches such a
> listener that looks at the datoms to see if any of them match patterns associated with each of the
> queries to which you're subscribed. This allows Posh to intelligently determine which queries are
> affected by incoming data changes."
> — <https://github.com/denistakeda/re-posh>

This is the cheap (§2.5) approach in Clojure-Datalog form: derive a *pattern* from each subscribed
query, check incoming datoms against the patterns, re-run matched queries. **Seon should read Posh's
pattern-extraction code directly** — it's the reference implementation of "Datalog query → wake-set
predicate." (Note: Posh's pattern matching is an *over*-approximation — it can wake a query that turns
out unaffected — which is the safe direction.)

---

## 4. Glitches, consistency, and update cycles

Seon has a cascade — *agent A writes → wakes agent B → B writes a summary → wakes orchestrator →
orchestrator re-renders → UI updates* — and must never let the orchestrator/UI observe an intermediate,
inconsistent snapshot. Every mature reactive system solves this the same two ways.

### 4.1 The glitch / diamond problem, stated

> "In reactive programming, breadth-first search propagation can cause a node to be set twice, with the
> first time setting it to an inconsistent state (a 'glitch'), particularly in diamond dependency
> patterns. Topological sort is needed to prevent the so-called 'diamond' problem where a node has
> multiple dependency paths converging on the same upstream node (d → [b, c], b → a, c → a)."
> — Weststrate, <https://medium.com/@mweststrate/topological-sort-is-needed-to-prevent-the-so-called-diamond-problem-...>;
>   objc.io "Understanding Reactive Glitches", <https://talk.objc.io/episodes/S01E76-understanding-reactive-glitches>

If `d` depends on both `b` and `c`, and both derive from `a`, a naive propagation can recompute `d`
once with the new `b` but stale `c`. Seon's diamond: two agents both depending on the same datom, and
the orchestrator depending on both summaries.

### 4.2 Solution A — topological sort (process in dependency order, set each node once)

> "Using topological sorting for change propagation guarantees that a node will only be set once, and
> there will never be inconsistent states." — topologica / Weststrate (same sources)

MobX's concrete version is the **stale → ready two-phase algorithm**, worth copying:

> 1. Observable sends a **stale** notification to observers; affected derivations recursively propagate
>    it.
> 2. Observable stores the new value, sends a **ready** notification (indicating whether it changed).
> 3. "Derivations wait until receiving ready notifications for all their stale notifications before
>    recomputing."
> 4. Only changed values trigger downstream recomputation.
>
> Result: *"it is simply impossible to ever observe stale derivations ... all reactions happen
> synchronously and glitch-free."*
> — <https://medium.com/hackernoon/becoming-fully-reactive-an-in-depth-explanation-of-mobservable-...>

Phase 1 (mark everything reachable as stale) before phase 2 (recompute in order) is exactly what keeps
the orchestrator from rendering after B updated but before A's effect on B settled.

### 4.3 Solution B — batching / transactions / supersteps (snapshot the whole round)

The other half: don't emit *any* downstream update until the current round of writes is fully applied.

- **MobX transactions/actions:** *"If several mutations are applied in immediate succession, re-evaluate
  all derivations after all changes ... Transactions postpone all ready notifications until the
  transaction block has completed."*
- **React batching** and **Solid's batched updates** coalesce multiple state changes in an event into a
  single render pass.
- **BSP supersteps** (the Pregel/Naiad model): all messages from round *N* are delivered together at the
  start of round *N+1*; no node sees a partial round. Differential dataflow's `(epoch, iteration)`
  timestamps are the database-grade version of this.
- **Convex's "same logical timestamp"** (§1.2) is the database version: every subscriber is advanced to
  *one* committed timestamp; you never render a mixture.

### 4.4 The synthesis for Seon's cascade

The robust design is **both**: a write opens a "reactive round," the round runs the affected agents in
topological order of their read/write dependencies (Solution A), buffering all summary writes, and the
orchestrator/UI only observe the round's *final* committed snapshot at a single datahike `tx` /
timestamp (Solution B + Convex's logical-timestamp idea). Because datahike is immutable and gives every
tx a `db-after` value, Seon has a natural "logical timestamp" to render against — render the UI/
orchestrator strictly against a *named db value*, never against "the live conn," and advance that
named value once per settled round. **The cycle question Seon must also answer (which none of the UI
systems face): agent A wakes B which wakes A — Seon needs a cycle/fixpoint or epoch-cap policy that the
acyclic-DAG UI frameworks don't provide. Differential dataflow's iteration timestamps are the only
surveyed system that handles genuine cycles, which is another point in 3DF's favor.**

---

## 5. Implications for Seon

Mapping each external mechanism onto Seon's pieces (datahike tx-listeners, `as-of`/`since`, filtered
DBs, agents-as-entities, summaries-as-render-functions).

### 5.1 Direct mechanism mappings

| Seon piece | Closest external mechanism | Notes |
| --- | --- | --- |
| datahike tx-listener (`:tx-data`, `db-before`, `db-after`) | Datomic tx-report-queue; Meteor oplog; Postgres WAL | Free change detection, **zero** "which query" routing. We build the routing. |
| Agent's standing reactive query | Convex query (read-set); RethinkDB changefeed; 3DF Datalog subscription; Posh subscription | 3DF is the only one that is *Datalog over Datomic-shaped facts* — closest. |
| "Which agents wake up" | Convex **subscription manager** (walk log once/commit, overlap read-sets); Posh pattern-match on datoms | Convex = the architecture; Posh = the Clojure-Datalog reference impl to read. |
| Agent read-set capture (auto, not declared) | Solid/MobX dependency-tracking-by-recording-reads; Convex index-range read-sets | Instrument datahike query execution to record touched attrs/datoms/index-ranges. |
| Agent recompute → summary write | Convex deterministic re-run of dirty query; Meteor re-run selector | Recompute must be a pure fn of `db-after` to be safely re-runnable. LLM side-effects need mutation-style care. |
| Summaries-as-render-functions (orchestrator/UI) | Solid effects; Convex subscriptions; re-posh subs | Render strictly against a *named db value* (a tx/timestamp), never the live conn. |
| Sleeping agent catch-up on missed txs | datahike `since`/`as-of`; Postgres logical replication replay | The replayable log is why a woken agent can reconstruct what it missed — don't use ephemeral NOTIFY-style delivery. |
| Scope / cross-agent visibility | Datomic *filtered DB* / Convex read-set per query; Electric Shape WHERE-clause | A section/query that doesn't filter by `:seon.agent/id` sees the whole substrate (matches CLAUDE.md reactive-context note). Filtered db = per-agent scope. |
| Cascade coherence (A→B→orchestrator→UI) | MobX stale/ready + transactions; Convex same-logical-timestamp; BSP supersteps | Run a round in topological order, buffer writes, advance the rendered db value once. |
| Incremental *result* diffs (if ever needed) | Differential dataflow / 3DF / DBSP | The expensive escape hatch for the few too-costly-to-re-run agents. |

### 5.2 The two-or-three designs to steal from (opinionated)

1. **Convex — steal the whole operational shape.** Read-set per standing query + a single subscription
   manager that walks each new transaction once and asks "does this overlap any read-set?" + advance all
   subscribers to one logical timestamp. Map "subscription" → "agent's standing query," "client" → "agent
   or UI." Convex even pre-solves our cascade-coherence and deterministic-re-run problems. *Read
   <https://stack.convex.dev/how-convex-works> end to end; it is the single best document for this
   project.*

2. **Posh / re-posh — steal the implementation pattern for Datalog wake-sets.** It's literally "attach a
   tx-listener to a Datomic-shaped DB, derive a pattern from each subscribed Datalog query, match
   incoming datoms, re-run matched queries." This is the cheap, ship-it version of Convex's read-set
   overlap, already written in Clojure for DataScript. Lift the pattern-extraction logic.

3. **Solid/MobX signals — steal the auto-read-set principle and the glitch-free algorithm.** The
   principle: *subscriptions must be determined at run-time by recording reads*, not declared (this is
   the user's React intuition done right, and it beats coarse Redux). The algorithm: stale→ready
   two-phase + transactional batching to keep the cascade glitch-free. (And keep **3DF** in the back
   pocket as the differential-dataflow escape hatch — it's the one surveyed system built for *Datalog
   over Datomic*, and the only one that handles genuine *cyclic/iterative* reactivity.)

### 5.3 The single hardest unsolved problem

**Capturing an accurate, minimal read-set for a datahike Datalog query, automatically, at the right
granularity — and deciding how much to approximate.** Everything downstream (wake-set, scope, cascade
ordering) depends on this. The spectrum:

- **Coarse (Posh):** read-set = the *attributes/patterns* the query mentions. Over-approximates (wakes
  some agents unnecessarily) but trivial to compute statically from the query, and *safe*. **Ship this
  first.**
- **Medium (Convex):** read-set = the *index ranges* actually scanned at run-time. Requires instrumenting
  the query engine's index access. More precise, still re-runs the whole query on overlap. The right
  target once the coarse version is proven.
- **Fine (Solid / true IVM / 3DF):** read-set = exact datoms, and you compute the *result diff* without
  re-running. Maximal precision, maximal complexity (a differential-dataflow operator graph). Only worth
  it for an agent whose query is individually too expensive to re-run.

The honest risk: datahike's query engine may not expose a clean hook for run-time read-set capture
(index-range or datom-level), forcing Seon to either (a) live with the coarse static-attribute
approximation, or (b) run queries through an instrumented `db` wrapper that records reads — feasibility
of which is a concrete REPL experiment to run against datahike before committing to the medium tier.
**That feasibility probe (can we record what a datahike query reads?) is the first thing to verify in
the REPL**, because it decides whether Seon gets Convex-grade precision or stays at Posh-grade
approximation.

### 5.4 Secondary open problems (flagged, not blocking)

- **Cyclic reactivity / fixpoint:** A wakes B wakes A. UI frameworks assume a DAG and would loop or
  glitch. Need an epoch cap or fixpoint detection. Only differential dataflow (iteration timestamps)
  surveyed here handles this natively.
- **Effectful recompute:** an agent's recompute calls an LLM (expensive, non-idempotent). Convex forbids
  effects in re-runnable functions for exactly this reason. Seon needs a split: cheap pure
  "am-I-affected / what's-my-new-summary-input" recompute (safe to re-run) vs. the expensive LLM step
  (guarded, deduplicated, like a Convex *action* rather than a *query*).
- **Delivery guarantees on wakeup:** build on the replayable tx-log (`since`/`as-of`), not on an
  at-most-once notify bus, so a momentarily-busy/sleeping agent never silently misses a relevant change.

---

## Sources

- Datomic tx-report-queue — <https://blog.datomic.com/2013/10/the-transaction-report-queue.html>;
  <https://docs.datomic.com/transactions/transaction-processing.html>;
  <https://github.com/waf/push-demo>; <https://thegeez.net/2014/06/12/gin_datomic.html>
- Convex — <https://stack.convex.dev/how-convex-works>; <https://docs.convex.dev/understanding/>;
  <https://docs.convex.dev/database/advanced/occ>; <https://stack.convex.dev/sync>;
  <https://makersden.io/blog/convex-architecture-deep-dive-reactive-database-functions-sync>;
  <https://stack.convex.dev/real-time-database>; <https://stack.convex.dev/best-real-time-databases-compared>
- XTDB — <https://v1-docs.xtdb.com/resources/faq/>; <https://v1-docs.xtdb.com/concepts/bitemporality/>;
  <https://biffweb.com/p/xtdb-compared-to-other-databases/>
- RethinkDB — <https://rethinkdb.com/docs/changefeeds>; <https://rethinkdb.com/faq>
- Meteor — <https://github.com/meteor/docs/blob/master/long-form/oplog-observe-driver.md>;
  <https://docs.montiapm.com/academy/live-queries>
- Firestore / comparison — <https://stack.convex.dev/best-real-time-databases-compared>
- ElectricSQL — <https://electric-sql.com/blog/2024/07/17/electric-next>;
  <https://electric-sql.com/blog/2025/03/17/electricsql-1.0-released>; <https://electric-sql.com/docs/llms/_intro_redux>
- Postgres CDC / LISTEN-NOTIFY — <https://datacater.io/blog/2021-09-02/postgresql-cdc-complete-guide.html>;
  <https://blog.sequinstream.com/a-developers-reference-to-postgres-change-data-capture-cdc/>
- IVM overview — <https://materializedview.io/p/everything-to-know-incremental-view-maintenance>
- Differential / Timely Dataflow — <http://www.frankmcsherry.org/differential/dataflow/2015/04/07/differential.html>;
  <https://cacm.acm.org/research/incremental-iterative-data-processing-with-timely-dataflow/>;
  <https://dl.acm.org/doi/10.1145/2983551>
- Materialize / DBSP — <https://www.vldb.org/pvldb/vol16/p1601-budiu.pdf>; <https://arxiv.org/pdf/2203.16684>;
  <https://github.com/MaterializeInc/materialize/>; <https://materialize.com/blog/ivm-database-replica/>
- 3DF / declarative-dataflow (Göbel) — <https://www.nikolasgoebel.com/2018/09/13/incremental-datalog.html>;
  <https://github.com/comnik/declarative-dataflow>; <https://github.com/sixthnormal/clj-3df>
- Solid.js — <https://docs.solidjs.com/advanced-concepts/fine-grained-reactivity>;
  <https://docs.solidjs.com/concepts/signals>; <https://docs.solidjs.com/concepts/effects>
- MobX — <https://mobx.js.org/understanding-reactivity.html>;
  <https://medium.com/hackernoon/becoming-fully-reactive-an-in-depth-explanation-of-mobservable-55995262a254>
- Posh / re-posh — <https://github.com/denistakeda/re-posh>; <https://cljdoc.org/d/re-posh/re-posh/0.3.0/doc/readme>
- Glitches / topological sort — <https://medium.com/@mweststrate/topological-sort-is-needed-to-prevent-the-so-called-diamond-problem-with-deps-d-b-c-b-275e9278898e>;
  <https://talk.objc.io/episodes/S01E76-understanding-reactive-glitches>;
  <https://github.com/datavis-tech/topologica>
