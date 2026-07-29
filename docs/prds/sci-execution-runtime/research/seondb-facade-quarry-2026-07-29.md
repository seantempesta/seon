---
type: research
status: active
tags: [research, database]
---

# `seon.db` facade quarry and fresh read design

This report binds rulings 22a and 24 to source evidence. Renders call
`seon.db`, never `datahike.api` directly. The facade described here is a
return to a repeatedly implemented Seon mechanism, cut down for the fresh
in-process CLJ runtime; it is not a new database abstraction.

The owner direction is literal:

> we've done it like 3 times — the CLJS one is async; look at the dual pattern
> of positional args allowing an ignored conn and we auto-insert the latest db.

The quarry supports that direction with one qualification required by the
fresh multi-cluster architecture: the ambient input cannot be a process-global
connection. At render-pass entry the owning cluster binds one immutable latest
database value. Omitted reads insert that value. Explicit database values
continue to win. That preserves the old call contract while making one render
pass basis-consistent and cluster-local.

## Verdict

The owner-reviewed contract must prove the complete read surface before any
implementation slice begins:

- `query`, named as Seon named it in every mature generation;
- `pull`;
- `pull-many`;
- `entity`, returning eager ordinary data rather than Datahike's lazy entity;
- bounded `datoms`, including an honest AVET range interest;
- `with-db`, the system-side binding of one immutable database value;
- `with-read-evidence`, the system-side capture around a render pass; and
- the private alignment, evidence, bounded realization, and range-matching
  functions those reads require.

Implementation may still land in dependency-ordered slices. Learning and the
public contract may not defer any of these five reads.

Both reads have the mined positional pairs:

```clojure
(db/query query-form & inputs)             ; missing sole db source is inserted
(db/query query-form db & inputs)          ; explicit source remains in position

(db/pull selector eid)                     ; bound latest db is inserted
(db/pull db selector eid)                  ; explicit immutable db

(db/pull-many selector eids)               ; bound latest db is inserted
(db/pull-many db selector eids)            ; explicit immutable db

(db/entity eid)                            ; eager '[*] projection
(db/entity db eid)                         ; explicit immutable db

(db/datoms options)                        ; bounded index page/range
(db/datoms db options)                     ; explicit immutable db
```

`query`, `pull`, and `pull-many` call the maintained fork's executing evidence
APIs on every invocation. `entity` composes from `pull '[*]`.
`datoms` uses the maintained bounded index operations and emits its own
conservative automatic range evidence. Evidence capture is not an optional
sibling API that renderers can forget to use.

## Scope and method

The quarry read:

- all 954 lines of `src-old/seon/db.cljc`, including the pinned
  per-query capture at lines 320-348;
- the complete `src-old/seon/db/` file inventory, its two localized
  authorities, the facade leaves, fiber/session context, JVM host, protocol
  shapes, and the writer's interest implementation at lines 2756-3205;
- the complete `git log --follow` histories of `src/seon/db.clj`,
  `src/seon/db.cljs`, and `src-old/seon/db.cljc`, with representative source
  read at each structural generation;
- the historical facade compatibility, source-audit, and Datahike-by-example
  reports;
- the relevant resolved/superseded issue archive and the fixing commits; and
- every current `datahike.api` require and executable use under `src/`.

This is analysis and REPL design only. No fresh `src/` or `test/` file was
edited.

## Dependency ledger

| dependency or Seon mechanism | selected revision | source read |
|---|---|---|
| Datahike | `9a7a9ef10a954c32075e60d929f9101a9ac8abd9` | `reference-code/datahike/src/datahike/api/specification.cljc:440-771`, `query.cljc:115-170,2480-2520,2880-2960`, `pull_api.cljc:420-465` |
| Datahike query evidence | same revision | `q-with-evidence`, `query-dependency-plan`, `dependency-plan-attributes` |
| Datahike pull evidence | same revision | `pull-dependency-plan`, `pull-with-evidence`, `pull-many-with-evidence` |
| Datahike lazy entity | same revision | `impl/entity.cljc:17-39,53-184,205-218` |
| Datahike indexes | same revision | `api/types.cljc:174-207`, `db.cljc:281-305`, `test/index_test.cljc:192-237` |
| fresh value projection | current tree | `src/seon/render/value.cljc:165-209` |
| old facade | tree `src-old/` | all of `src-old/seon/db.cljc` |
| old capture context | tree `src-old/` | `db/leaf.cljc`, `fiber.cljs:20-72`, `session.cljs:41-67,570-607`, `host.clj:900-982` |
| old wake routing | tree `src-old/` | `db/writer.clj:2756-3205` |
| fresh wake design | 2026-07-29 | `agent-flow-render-falsification-2026-07-29.md` §§1 and 5.4 |
| fresh render callers | current tree | `src/seon/render/{agent,block,root,value,walk,web}.*` |
| fresh database resource owner | current tree | `src/seon/cluster/store.clj` |
| fresh error value | current tree | `resources/seon/schema/error.edn:9-36`, `src/seon/error.clj` |

The Datahike fork is not an incidental API dependency. Its maintained
`q-with-evidence` and `pull-with-evidence` are the source of truth for absence
dependencies, reverse pull dependencies, rules, multi-source queries, and
conservative `:all`. The facade must not reparse Datalog or pull patterns to
invent another dependency graph.

## Chunk 1 — facade generations

| generation | primary surface | database omission | async boundary | failure/result shape |
|---|---|---|---|---|
| A, JVM Datalevin/relay | `q`, pull family, transaction, stats; later named-database relay | none at first; later name/registry routing | CLJ reads direct, writer flow for mutation | throws and writer replies; no one stable public error contract |
| B, local CLJS Datahike | request-map transaction/query/pull/entity/listen | optional map `conn`, default `*conn*` | reads sync; mutations/listeners async | writes gain `ok?` envelope; reads still bare/throwing |
| C, positional CLJS | Datahike-shaped query/pull/entity plus map forms | explicit value wins; otherwise dereference `*conn*` | same local split | writes envelope; arbitrary read values bare |
| D, normalized-read invalidation | C plus captured normalized reads | same | same | same; replay supplies invalidation metadata |
| E, remote CLJS authority | database acquisition, reads, writes, schema, bulk, index, listener/control | authority session resolves latest | every authority call async | bare semantic success or flat error value |
| F, ordinary descriptor/evidence | E plus temporal descriptors, latest cache, parsed dependency plans | insert one latest descriptor at the parsed missing source | async over UDS | bare arbitrary read success still collides with flat error |
| G, portable `.cljc` | one portable facade over CLJ/CLJS leaves | explicit descriptor or leaf/current latest | CLJ sync, CLJS async | same facade projection; evidence active in CLJS, JVM host capture no-op |

### Generation A — first JVM API layer, February to July

The first tracked facade was created by `1c7da7f5d` on 2026-02-21. Its public
surface was:

```clojure
(transact! conn tx-data)
(transact! conn tx-data tx-meta)
(q query & inputs)
(pull db selector eid)
(pull-many db selector eids)
(entity db eid)
(stats db)
```

It wrapped Datalevin, not Datahike, and returned dependency writer-flow output
for writes while reads were direct. Failures threw. It established the first
important precedent: ordinary application namespaces called one `seon.db`
surface rather than importing the database dependency everywhere.

That namespace then absorbed connection routing, relay behavior, schema
validation, database-name selection, and flow lifecycle. Immediately before
its archival in `6c1079c8d`, the surface had become:

```clojure
(transact! db-name tx-data ...)
(query db-name query-form & inputs)
(pull-by-name db-name selector eid)
(pull-many-by-name db-name selector eids)
(entity-by-name db-name eid)
(pause-writer!)
(resume-writer!)
```

Unregistered namespaces threw `ex-info`; deprecated direct-mode and
connection-manager entry points were retained as no-ops. This generation
proved the value of the facade and the cost of letting lifecycle, routing,
schema policy, and reads accrete into the same namespace.

### Generation B — local CLJS Datahike facade, May to June

`a00a22086` created `src/seon/db.cljs` on 2026-05-17 with:

- `transact!`;
- `query`;
- `pull`;
- `entity`;
- `listen!`; and
- `unlisten!`.

It held `*conn*` as the local Datahike connection. Reads dereferenced the
connection and were synchronous. `ed72acb0c` made writes and listener
registration native `^:async` Promise operations. `976fed5f` added the closed
transaction envelope:

```clojure
{:seon.db/ok? true  :seon.db/tx-report report}
{:seon.db/ok? false :seon.db/error error}
```

The call contract was initially map-in. This generation also grew the
Malli-to-Datahike bridge, pre-transaction validation, transaction-context
metadata, schema guards, identifiers, inventory, and listener plumbing.

### Generation C — positional dual forms and ambient insertion, June

This is the generation the owner direction points at.

`4056df8be` added Datahike-shaped positional reads while retaining request
maps. Initially the database slot was explicit:

```clojure
(query query-form db & inputs)
(pull db selector eid)
(entity db eid)
```

`7da843c4d` did the same for positional transactions. The implementation used
one pure variadic body for overlapping query forms because fixed plus variadic
Malli instrumentation had already proved unsafe in CLJS.

The ambient forms were then added before the end of June:

```clojure
(query query-form & non-db-inputs)
(pull selector eid)
(entity eid)
(transact! tx-data)
```

For a query, the second argument was treated as an explicit database only when
it satisfied the database-value predicate. Otherwise it was preserved as the
first ordinary `:in` input and the facade dereferenced `*conn*` to inject a
database. Pull and entity dispatched by arity. Request maps could carry either
`:seon.db/db` or an optional `:seon.db/conn`; the connection was a way to
obtain a database value and was not itself passed to `d/q` or `d/pull`.

This is the precise meaning of the old “ignored conn” pattern: connection was
selection/context, while the read dependency consumed an immutable database
value. The mature remote facade later accepted no useful client connection at
all; the authority session selected latest. Fresh should retain the semantic
distinction, not the dead argument.

The generation's exact read behavior was:

- explicit database value wins;
- omitted database dereferences the bound connection once for that call;
- positional order mirrors Datahike;
- map arity distinguishes a request by the presence of `:seon.db/query`;
- `pull` returns a map or nil;
- `entity` returns a touched eager map rather than a lazy Datahike Entity; and
- reads could still throw, while writes returned an envelope.

### Generation D — normalized reads and observed-attribute invalidation, July

`e2c3170ea` captured normalized read descriptions; `9d405344e` replayed those
reads for invalidation; and `365052f0c` routed render units by observed
attributes. This was useful but not the final dependency mechanism. Replaying
read descriptions and observing returned values cannot soundly capture an
attribute whose absence affects a result.

The maintained Datahike fork then acquired parsed dependency plans.
`8b8596a51` carried those plans through query responses, `a7192f859` sent them
directly to interests, and `835faf9e2` captured them across renders. That is
the mechanism fresh reuses.

### Generation E — remote CLJS database authority, July 16

`fbc40f480` replaced local CLJS reads with one remote JVM authority session.
Every authority-crossing function became `^:async`, including `query`, `pull`,
and `entity`. The surface expanded to include:

- session open/close and attachment;
- database acquisition and release;
- temporal database values;
- transactions and compare-and-set fences;
- query, pull, pull-many, and entity;
- installed schema;
- execute-many;
- bounded index pages and KNN;
- listener registration and cancellation; and
- branch-head and transaction resolution.

The async character was caused by the pod-to-JVM Unix-domain-socket crossing.
It was never inherent in Datalog reads. The fresh runtime is one JVM with
in-process Datahike values, so retaining `^:async`, Promise handling, request
identities, connection pools, or transport recovery would be porting the
deleted system.

### Generation F — ordinary database descriptors, latest cache, and evidence

`2391007c0` changed the remote public database from a tier-local object to an
ordinary descriptor. `67e867bd0` cached the latest descriptor so an omitted
read did not require a preliminary round trip. Explicit descriptors still
won; latest was selected once at operation entry.

The final query omission algorithm was stronger than the June
`db-value?`-of-second-argument heuristic. `aligned-dependency-arguments`
compared Datahike's parsed input count with the supplied arguments:

1. equal counts mean the sources are explicit;
2. exactly one missing input plus exactly one parsed database source means
   insert the implicit database at that source's argument position; and
3. every other mismatch widens dependency analysis to `:all` and is rejected
   by execution.

This preserves scalar, tuple, collection, relation, and rule inputs without
guessing from their shapes. It also supports a database source not in
position zero.

### Generation G — portable `.cljc` facade, July 22 to the tree split

`f6d843ee7` made the final facade a portable core with one bound leaf per tier.
The public surface in `src-old/seon/db.cljc` was:

| family | functions and arities |
|---|---|
| context | `current-agent-id`, `with-read-evidence`, `with-agent`, `without-agent`, `with-tx-context` |
| session | `open-session!`, `close-session!`, `attached?`, `db` `[]/[request]`, `release` |
| pure database transforms | `as-of [db point]`, `since [db point]`, `history [db]`, `cas-assert` |
| mutation | `transact!` request, tx-data-only, or explicit database plus tx-data |
| query | `query [request-or-query & inputs]`, `query-with-evidence [request]` |
| pull | `pull [request]/[selector eid]/[db selector eid]`; `pull-many` in the same three shapes |
| entity/schema | `entity [request-or-eid]/[db eid]`; `installed-schema []/[request-or-db]` |
| bulk/index | `execute-many`, `index-page`, `knn-search!` |
| observation/control | `listen!` one/two/three logical forms, `unlisten!`, `cancel!` |
| codecs/schema bridge | `malli->datahike-schema`, transaction schema projection, slot encoders/decoders |

CLJ calls were synchronous; CLJS calls retained `^:async`. The `.cljc` entry
functions were the reader-conditional seam and dispatched through
`src-old/seon/db/leaf.cljc`.

The final JVM leaf in `host.clj` is also a warning: its
`with-read-evidence` simply called the thunk and its evidence recorder was a
no-op. Evidence capture was live only in the CLJS fiber/session. Fresh CLJ
cannot reuse that host implementation; it must return the capture mechanism
to the JVM along with the now-local reads.

## Chunk 2 — what the `src-old/seon/db/` family actually owned

The directory is not one reusable facade. It is a family of historical
responsibilities:

| files | historical responsibility | fresh disposition |
|---|---|---|
| `leaf.cljc`, `fiber.cljs`, `session.cljs`, `host.clj` | tier leaf, async context, session/cache/pool, host client | mine binding and capture semantics only |
| `internal.cljc`, `datahike/schema.clj` | invocation normalization, schema bridge, validation, codecs | do not port into the read slice |
| `protocol.cljc` | versioned remote request/response and database descriptor wire shapes | delete with the external crossing |
| `transport/uds.*`, `server.clj` | socket framing, client/server lifecycle, remote control | condemned by localized authority; no fresh read dependency |
| `writer.clj`, `executor.clj` | remote authority, query jobs, writes, read budgets, interests | mine the interest algorithm; do not port the authority |
| `registry.clj`, `backend.clj`, `branch.cljc`, `process.cljc` | named database/branch/process management | fresh `seon.cluster.store`/registry owners remain separate |
| `program.clj`, `id*` | program initialization and identity allocation | unrelated to render reads |
| `restore*` | restore administration and schemas | unrelated to render reads |

`src-old/seon/db/AGENTS.md` explicitly calls the fiber/session child
machinery dying and forbids porting it. `src-old/seon/db/transport/AGENTS.md`
does the same for the CLJS UDS leaf. The fresh facade returns by keeping the
small application read seam, not by relocating this directory.

## Chunk 3 — evidence capture, exactly

### Old capture shape

The final `record-query-evidence!` at `src-old/seon/db.cljc:320-340` read
`:datahike.read/dependency-plan` from the response. For every database source
argument it recorded:

```clojure
{:seon.db/db database
 :seon.db/source-argument-position position
 :datahike.read/dependency-plan plan}
```

If the plan was `:all`, it recorded every database input position. Otherwise
it used the source positions named by the plan. Pull and pull-many used the
same entry at primary source position zero.

The CLJS fiber's `run-with-read-evidence`:

1. allocated an invocation-local atom;
2. bound that atom through `AsyncLocalStorage`;
3. ran and awaited the thunk;
4. appended entries as reads completed; and
5. returned the thunk value with a distinct vector of evidence entries.

No rendered rows were inspected. This is load-bearing: a query that returns
nothing and a pull of an absent attribute still carry the dependency that can
make them non-empty later.

### How the old interest owner consumed it

`src-old/seon/db/writer.clj:2857-2899` checked that evidence belonged to the
same live branch and reduced each plan through:

```clojure
(d/dependency-plan-attributes plan source-argument-position)
```

`:all` absorbed sets. The reverse index narrowed candidate interests by
changed attribute, then exact datom matching confirmed delivery. The fresh
falsifier keeps that two-stage routing, same-signature installation no-op,
and fail-open `:all`. Plan-to-attribute projection is memoized by normalized
query form, so two registered passes executing the same query do not repeat
the pure dependency-plan reduction.

Fresh deliberately changes two historical policies:

- an empty dependency set is valid and means a static render; and
- one in-process cluster/store/branch needs no old `by-scope` routing key.

### Fresh capture contract

Evidence collection belongs inside `seon.db`, not beside it. The render owner
should call:

```clojure
(db/with-db @connection
  #(db/with-read-evidence
     #(render-pass ...)))
```

`@connection` is dereferenced once before binding. Every omitted read in the
pass therefore sees one immutable database value and one basis transaction.
An explicit database argument still overrides the binding for that call.

`with-read-evidence` returns:

```clojure
{:seon.db/value render-pass-value
 :seon.db/read-evidence
 [{:seon.db/db database-value
   :seon.db/source-argument-position 0
   :datahike.read/dependency-plan plan}
  ...]}
```

The vector is stable first-observation order with exact duplicate entries
removed. It is invocation-local process state, not durable data and not
derived state stored in the database.

Every `query`, `pull`, `pull-many`, `entity`, and `datoms` call checks one
dynamic, invocation-local capture context. When the render proc binds that
context, reads append evidence and the proc may install the reduced interest
under its `[agent-id registration-name]` reference. With no binding, the
exact same functions are plain one-off calls: they return their values,
allocate no collector, and register nothing. The facade neither accepts an
agent/registration argument nor knows the interest registry. Registration is
a property of the calling pass, never a per-call mode selected by an agent.

If alignment or database execution fails, the facade records `:all` before
returning its read error. If the enclosing projection/render fails, the render
proc—which owns that result schema—replaces the collected evidence with
`:all`. `with-read-evidence` does not invent a generic predicate for every
possible render result. A failed render must never retain its previous narrow
interest. The resolved issue
`failed-page-render-retains-stale-dependencies.md` proved the wedge: the
repairing transaction can be filtered out forever unless failure widens.

## Chunk 4 — what worked and what bit

### Keep

| historical result | why it worked |
|---|---|
| one facade over the dependency | callers can be intercepted for evidence, validation, resource policy, and future dependency changes |
| Datahike-shaped positional reads | low ceremony and familiar argument order |
| explicit and omitted database forms | tests/time travel can pin a value; ordinary render code stays concise |
| latest selected once per operation | prevents a multi-read operation from changing basis mid-call |
| parsed source-position insertion | handles interleaved query inputs and multi-source forms without shape guessing |
| eager pull/entity values | no lazy dependency object escapes its owning database value |
| dependency plans returned by the executing read | captures absence, rules, reverse pull, and conservative openness |
| invocation-local evidence collection | no global render dependency registry and no caller-authored attribute list |
| one pure variadic query implementation | avoided CLJS Malli wrapper corruption on overlapping logical arities |
| failures as ordinary values | nothing throws into the agent/run loop |

### Do not repeat

| issue or fix | failure mode | fresh consequence |
|---|---|---|
| `arbitrary-database-results-collide-with-error-shape.md` | a legitimate query/pull map can exactly equal the flat error shape | wrap collision-capable success in a closed result union |
| `instrumented-query-lost-one-argument-accessor.md` / `daf8f41c3` | overlapping fixed and variadic Malli implementations lost `(query form)` | one variadic query implementation and one honest logical contract |
| `query-contract-required-a-source-the-function-did-not.md` / `4af318a6b` | schema required at least one input while omission allowed none | query inputs use `:*`, then semantic alignment decides validity |
| `pull-contract-omitted-explicit-database-arity.md` / `31b4a8230` | implementation accepted the three-argument form but schema did not | schemas enumerate both pull arities |
| `public-pull-map-used-transport-field-names.md` / `921d185a3` | public request keys drifted into protocol vocabulary | no protocol-shaped read request maps; the closed datoms index options are dependency input, not transport |
| `invalid-database-request-was-core-fault.md` / `1c6718875` | malformed/unawaited input was classified as a core fault | validation errors return a caller-correctable flat error value |
| `database-failures-lost-seon-error-kind.md` | protocol projection dropped the failure's kind | use the one fresh closed `:seon.error/value` unchanged |
| `datahike-read-dependencies-miss-valid-query-and-pull-inputs.md` | incomplete dependency analysis missed valid missing/reverse/nested cases | call maintained Datahike `*-with-evidence`; never implement a Seon analyzer |
| `failed-page-render-retains-stale-dependencies.md` | a failed recomputation retained stale narrow dependencies | failure atomically installs `:all`; later success may narrow |
| `read-side-attribute-admission-fails-open.md` | `:all` is sound for wake interest but unsafe as an admission allow-list | evidence is for invalidation only, not read authorization |
| `complete-one-arity-called-invalid-public-arity.md` and multi-arity issues | a declared arity family did not match the instrumented callable | contracts describe the actual implementation form and all logical calls |
| early positional transaction metadata | a convenience arity did not match Datahike's transaction argument map | transactions are outside this slice; do not infer their design from old convenience |
| process-global `*conn*` | one ambient connection cannot name one of many clusters | bind a database value at the cluster/render boundary |
| final JVM host evidence no-op | portable facade existed but CLJ capture did not | fresh CLJ capture is a first-class acceptance gate |

The historical compatibility matrix said a successful semantic value could be
returned bare and a failure could be a flat error. The later collision issue
falsified that law for `query`, `pull`, and `entity`. Fresh should preserve the
flat error value but put an explicit closed discriminator around the read
result.

## Chunk 5 — fresh CLJ-only design

### Namespace and dependency direction

The facade is `src/seon/db.clj`. It may require:

- `datahike.api` for `q-with-evidence`, `pull-with-evidence`, and
  `dependency-plan-attributes`;
- the maintained Datahike query input/source helpers used by the old
  alignment algorithm;
- `seon.schema`/`seon.schema.edn` for schemas; and
- the registered `:seon.error/value`.

No application renderer imports `datahike.api`. `seon.cluster.store` remains
the platform owner of creation, connection, transaction, branch, and release
mechanics. The first facade does not become a second store owner.

The namespace is `.clj`, synchronous, and has no reader conditionals,
`^:async`, `await`, Promise, core.async, socket, timeout, session, or
request-id code.

### Database binding

`with-db` binds a database **value**, not a connection:

```clojure
(with-db database thunk)
```

Its contract requires a Datahike database value and a zero-argument thunk.
The render proc owns dereferencing its cluster connection once. This is both
“latest auto-inserts” and “one pass has one basis.”

An omitted read outside `with-db` returns:

```clojure
{:seon.db/success? false
 :seon.error/value
 {:seon.error/kind :seon.db/missing-database
  :seon.error/message "No database value is bound for this read."
  :seon.error/data {}}}
```

It never searches a registry, chooses a cluster, or falls back to a global.

### Query alignment

`query` is one variadic implementation:

```clojure
(defn query [query-form & inputs] ...)
```

Before execution it uses the mined count-and-source-position algorithm:

1. parse the query input count and database source bindings;
2. if supplied input count equals declared input count, preserve inputs
   exactly;
3. if supplied count is one less and there is exactly one database source,
   insert the bound database at that source's declared argument position;
4. otherwise return the closed read-failure envelope with
   `:seon.error/kind :seon.db/invalid-read`;
5. call `(apply d/q-with-evidence query-form aligned-inputs)`; and
6. record the returned plan once for every database source position.

This deliberately replaces the June “is the second argument a database
value?” heuristic with the final facade's parsed alignment. An ordinary query
input is allowed to be map-shaped or even database-shaped when its parsed
binding is not a source.

Multiple source variables are supported only when all source inputs are
explicit. Ambient insertion never guesses which of several sources is the
cluster database.

### Pull

`pull` has exactly two arities:

```clojure
([selector eid] ...)
([database selector eid] ...)
```

The first uses the bound database. The second uses the explicit value. Both
call `d/pull-with-evidence`, record source position zero, and return an eager
map or nil inside the closed read result.

### Pull-many

`pull-many` has the same positional pair:

```clojure
([selector eids] ...)
([database selector eids] ...)
```

Both call `d/pull-many-with-evidence` once, not `mapv` over facade `pull`.
Datahike returns one input-aligned vector and one shared parsed dependency
plan. The facade records one primary-source entry. Missing entities remain
`nil` in position; no filtering or reordering occurs.

### Entity

`entity` supports the old positional pair but deliberately ports Generation
G's semantics, not Datahike's return object:

```clojure
([eid] ...)
([database eid] ...)
```

It is exactly eager `pull '[*]`, including `d/pull-with-evidence` and its
conservative `:all` attribute plan. The facade then passes the ordinary map
through the calling pass's existing bounded value-admission discipline. It
also passes the cluster's configured result-weight limit into Datahike's pull
options, so an oversize projection becomes the closed read failure instead of
being constructed without bound. A one-off call uses the same cluster-bound
default. The limit is a configuration fact, not a new per-entity magic number.

This conclusion follows from the prototype in Chunk 7:

- a wrapper can record keyed lookup precisely, including absent attributes
  and later navigation through a ref;
- `seq`, `count`, printing, equality helpers, and other map operations cause
  Datahike's `touch`, whose open attribute set must widen to `:all`;
- every returned ref needs another wrapper, so the wrapper becomes a second
  lazy entity implementation; and
- fresh `seon.render.value/opaque?` correctly classifies Datahike's raw entity
  as a runtime handle.

Refusing the public `entity` function would fail ruling 24. Returning the raw
entity would leak lazy database access across the evidence/basis and ordinary
value boundaries. Eager bounded projection is the only option that supports
the API while preserving those boundaries. Callers needing precise wake
interest use explicit `pull`; `entity` is intentionally conservative.

### Datoms

Raw `d/datoms` is lazy and its datom objects are opaque to the fresh value
renderer. Fresh `datoms` therefore has two positional arities around one
closed bounded options value:

```clojure
([options] ...)
([database options] ...)
```

The options schema admits:

- Datahike's eager `SIndexPageArgs`, retaining its `index`, `components`,
  `direction`, `limit`, cursor, and result-weight vocabulary; or
- a bounded accretion of `SIndexRangeArgs` requiring `attrid`, inclusive
  `start`, inclusive `end`, and `limit`.

The return is an eager vector of closed ordinary datom maps using the mined
Generation G writer projection:

```clojure
{:seon.db/e entity-id
 :seon.db/a attribute
 :seon.db/v value
 :seon.db/tx transaction-id
 :seon.db/added? boolean}
```

The facade may call the maintained fork's internal index functions directly;
the owner explicitly sanctioned that dependency boundary. It never exposes a
lazy sequence. Its dependency exception boundary encloses bounded realization
and datom-map projection, so a delayed index failure still becomes the closed
read failure.

Evidence is automatic:

- an AVET bounded range emits its concrete attribute plus an inclusive value
  interval pattern;
- a page whose prefix exposes a concrete attribute emits at least that
  attribute dependency;
- an EAVT entity-only prefix, an open index scan, or a shape whose attribute
  cannot be proven widens to `:all`; and
- no renderer authors these patterns.

The exact range matcher must test both the transaction report's new value and
the old value found by a point lookup in `db-before`. The maintained report
does not include the displaced cardinality-one value. Looking only at
`:tx-data` misses an entity leaving the range and is unsound.

### Result and error shape

The result is explicit because arbitrary database data can look exactly like
an error:

```clojure
{:seon.db/success? true
 :seon.db/value ordinary-read-value}

{:seon.db/success? false
 :seon.error/value flat-registered-error}
```

The facade catches dependency exceptions. It classifies from structured
`ex-data`, never message text:

- malformed query/pull/pull-many/entity/datoms inputs become caller-correctable
  `:seon.db/invalid-read`;
- absence of an ambient database becomes `:seon.db/missing-database`;
- a Datahike refusal retaining its own useful kind/data remains that
  registered error value; and
- an unclassifiable dependency failure becomes
  `:seon.db/unknown-failure`, subject to the one existing core-error policy at
  its owning boundary.

The read functions themselves return values. They do not transact error
facts, log, or decide whether the process panics.

### Full Malli contract set

The owning schema file should register these named shapes before function
instrumentation:

```clojure
:seon.db/database
[:fn {:error/message "a Datahike database value"}
 seon.db/database?]

:seon.db/query-form
[:fn {:error/message "a Datahike query form"}
 seon.db/query-form?]

:seon.db/query-input
[:fn {:error/message "an ordinary Datahike query input"}
 seon.db/query-input?]

:seon.db/read-value
[:fn {:error/message "a fully realized ordinary database read value"}
 seon.db/read-value?]

:seon.db/captured-value
[:fn {:error/message "a value returned by the captured computation"}
 seon.db/captured-value?]

:seon.db/thunk
[:fn {:error/message "a zero-argument function"}
 seon.db/thunk?]

:seon.db/pull-selector
[:fn {:error/message "a Datahike pull selector"}
 seon.db/pull-selector?]

:seon.db/entity-ref
[:fn {:error/message "a Datahike entity id or lookup ref"}
 seon.db/entity-ref?]

:seon.db/entity-refs
[:vector :seon.db/entity-ref]

:seon.db/index-component
[:fn {:error/message "an ordinary Datahike index component"}
 seon.db/index-component?]

:seon.db/index-components
[:vector {:max 4} :seon.db/index-component]

:seon.db/index-cursor
[:tuple :seon.db/index-component :seon.db/index-component
 :seon.db/index-component :int :boolean]

:seon.db/index-page-options
[:map {:closed true}
 [:seon.db/index [:enum :eavt :aevt :avet]]
 [:seon.db/components :seon.db/index-components]
 [:seon.db/direction [:enum :forward :reverse]]
 [:seon.db/limit [:int {:min 1}]]
 [:seon.db/cursor {:optional true} :seon.db/index-cursor]
 [:seon.db/max-result-weight {:optional true} [:int {:min 1}]]]

:seon.db/index-range-options
[:map {:closed true}
 [:seon.db/attr :keyword]
 [:seon.db/start :seon.db/index-component]
 [:seon.db/end :seon.db/index-component]
 [:seon.db/limit [:int {:min 1}]]
 [:seon.db/max-result-weight {:optional true} [:int {:min 1}]]]

:seon.db/datoms-options
[:or :seon.db/index-page-options :seon.db/index-range-options]

:seon.db/datom
[:map {:closed true}
 [:seon.db/e :seon.db/index-component]
 [:seon.db/a :keyword]
 [:seon.db/v :seon.db/index-component]
 [:seon.db/tx [:int {:min 1}]]
 [:seon.db/added? :boolean]]

:seon.db/datoms
[:vector :seon.db/datom]

:seon.db/success? :boolean

:seon.db/read-success
[:map {:closed true}
 [:seon.db/success? [:= true]]
 [:seon.db/value :seon.db/read-value]]

:seon.db/read-failure
[:map {:closed true}
 [:seon.db/success? [:= false]]
 [:seon.error/value :seon.error/value]]

:seon.db/read-result
[:or :seon.db/read-success :seon.db/read-failure]

:seon.db/pull-value
[:fn {:error/message "nil or one fully realized ordinary pull map"}
 seon.db/pull-value?]

:seon.db/pull-many-value
[:vector :seon.db/pull-value]

:seon.db/pull-success
[:map {:closed true}
 [:seon.db/success? [:= true]]
 [:seon.db/value :seon.db/pull-value]]

:seon.db/pull-many-success
[:map {:closed true}
 [:seon.db/success? [:= true]]
 [:seon.db/value :seon.db/pull-many-value]]

:seon.db/datoms-success
[:map {:closed true}
 [:seon.db/success? [:= true]]
 [:seon.db/value :seon.db/datoms]]

:seon.db/pull-result
[:or :seon.db/pull-success :seon.db/read-failure]

:seon.db/pull-many-result
[:or :seon.db/pull-many-success :seon.db/read-failure]

:seon.db/datoms-result
[:or :seon.db/datoms-success :seon.db/read-failure]

:seon.db/plan-evidence-entry
[:map {:closed true}
 [:seon.db/db :seon.db/database]
 [:seon.db/source-argument-position [:int {:min 0}]]
 [:datahike.read/dependency-plan :datahike/SReadDependencyPlan]]

:seon.db/value-range
[:map {:closed true}
 [:seon.db/start :seon.db/index-component]
 [:seon.db/end :seon.db/index-component]
 [:seon.db/start-inclusive? [:= true]]
 [:seon.db/end-inclusive? [:= true]]]

:seon.db/datom-pattern
[:map {:closed true}
 [:seon.db/a :keyword]
 [:seon.db/value-range {:optional true} :seon.db/value-range]]

:seon.db/index-evidence-entry
[:map {:closed true}
 [:seon.db/db :seon.db/database]
 [:seon.db/dependencies [:or [:= :all] [:set :keyword]]]
 [:seon.db/patterns [:vector :seon.db/datom-pattern]]]

:seon.db/read-evidence-entry
[:or :seon.db/plan-evidence-entry :seon.db/index-evidence-entry]

:seon.db/read-evidence
[:vector :seon.db/read-evidence-entry]

:seon.db/read-capture
[:map {:closed true}
 [:seon.db/value :seon.db/captured-value]
 [:seon.db/read-evidence :seon.db/read-evidence]]
```

`:seon.db/read-value` and `:seon.db/captured-value` are named predicate
schemas at genuinely polymorphic boundaries. They are not anonymous `:any`.
The former accepts only fully realized ordinary values plus nil. The latter
validates the value against the caller-owned render-pass result schema rather
than replacing that schema with a second database-owned copy. `thunk?`
requires a function whose declared arglists admit zero arguments. Query forms,
selectors, entity refs, index components, database values, plans, source
positions, and errors all have their actual named schemas.

The public function contracts are:

```clojure
with-db
[:=> [:cat :seon.db/database :seon.db/thunk]
 :seon.db/captured-value]

with-read-evidence
[:=> [:cat :seon.db/thunk]
 :seon.db/read-capture]

query
[:=> [:catn
      [:seon.db/query :seon.db/query-form]
      [:seon.db/inputs [:* :seon.db/query-input]]]
 :seon.db/read-result]

pull
[:function
 [:=> [:cat :seon.db/pull-selector :seon.db/entity-ref]
  :seon.db/pull-result]
 [:=> [:cat :seon.db/database :seon.db/pull-selector
       :seon.db/entity-ref]
  :seon.db/pull-result]]

pull-many
[:function
 [:=> [:cat :seon.db/pull-selector :seon.db/entity-refs]
  :seon.db/pull-many-result]
 [:=> [:cat :seon.db/database :seon.db/pull-selector
       :seon.db/entity-refs]
  :seon.db/pull-many-result]]

entity
[:function
 [:=> [:cat :seon.db/entity-ref] :seon.db/pull-result]
 [:=> [:cat :seon.db/database :seon.db/entity-ref]
  :seon.db/pull-result]]

datoms
[:function
 [:=> [:cat :seon.db/datoms-options] :seon.db/datoms-result]
 [:=> [:cat :seon.db/database :seon.db/datoms-options]
  :seon.db/datoms-result]]
```

`query` has one variadic implementation and one contract, so the zero-input
ambient form is admitted by `:*`. Every other read has two non-overlapping
fixed arities, both declared. The facade translates its fully namespaced
index option keys to Datahike's bare dependency argument keys only at the
dependency call.

### Evidence and errors interact atomically

The render owner consumes a capture, not evidence piecemeal:

1. a successful render reduces all evidence plans to an attribute set;
2. a successful render with no reads installs the empty/static interest;
3. a database read failure has already recorded `:all`;
4. the render proc replaces any evidence with `:all` when its own projection
   result is a failure;
5. an unchanged evidence signature is a no-op;
6. a later success can replace `:all` with exact evidence; and
7. output digest and interest update belong to one proc-state transition.

The current falsifier's reverse index then consumes the attributes. It does
not call `query-dependency-plan` a second time and does not inspect rendered
values.

### Deliberate drops

| old feature | fresh decision | reason |
|---|---|---|
| request-map read arities | drop initially | positional forms are the binding ruling and avoid public/protocol key drift |
| `*conn*` process global | drop | violates multi-cluster ownership and can change basis between reads |
| accepted optional client `conn` | drop | connection is a system resource; render code consumes a database value |
| CLJS `^:async`/Promise reads | drop | no external crossing remains |
| UDS protocol/session/pool/latest descriptor cache | drop | one JVM already owns the live connection and database value |
| `query-with-evidence` public sibling | drop | built-in capture prevents an untracked read path |
| normalized-read replay | drop | executing Datahike already returns a sound plan |
| returned `(e,a)` tracing | drop | misses absent attributes and empty results |
| raw lazy `d/entity` object | drop | wrapper prototype still widens on `seq` and becomes a second lazy entity implementation |
| eager `entity` facade | retain | Generation G's full pull supports the main API without leaking a tier-local object |
| `pull-many` | retain | maintained `pull-many-with-evidence` gives aligned results and one shared plan |
| raw lazy `d/datoms`/seek/rseek | drop | opaque datom objects and deferred reads can escape the pass basis |
| bounded `datoms` facade | retain | eager index pages/ranges have explicit caps and automatic precise-or-`:all` evidence |
| installed-schema/read admission | drop | not needed for render invalidation; `:all` is not an authorization set |
| transactions/listeners/branches/restore/KNN | drop from this facade slice | owned by other fresh mechanisms or not needed by renders |
| schema bridge and codecs | drop | database schema registration and external projection are separate owners |
| read resource timeouts/cancellation | drop | local synchronous reads have observable completion; no remote state justifies a clock |
| old `by-scope`, request ids, reconnect restoration | drop | old external session identity no longer exists |

## Chunk 6 — migration inventory

The inventory command was:

```sh
rg -n 'datahike\.api|\bd/' src/
```

There are 27 fresh source files requiring `datahike.api`. The migration is
grouped by owning wave so the render read seam can land without turning
`seon.db` into a store/lifecycle facade.

Ruling 24 does not change a present call-site owner: a second census found no
executable `d/entity`, `d/pull-many`, `d/datoms`, `d/index-range`,
`d/index-page`, `d/seek-datoms`, or `d/rseek-datoms` call under `src/`.
Those functions are full-contract learning and forward API support.
`src/seon/render/value.cljc:170-204` recognizes entity and datom objects only
to classify them as opaque; it is a design dependency, not a facade migration
call site.

### Owner lane R — render reads, first facade consumer

Every executable `q` and `pull` below routes through `seon.db` in the first
migration. Docstring-only mentions of `d/pull` are excluded.

| file | executable direct reads |
|---|---|
| `src/seon/render/agent.clj` | `pull` at 97, 220, 340; `q` at 108, 126, 136, 140, 144, 148, 154, 158 |
| `src/seon/render/block.clj` | `q` at 225, 1087; `pull` at 239, 511 |
| `src/seon/render/root.clj` | `q` at 79, 81, 82, 107, 139 |
| `src/seon/render/value.cljc` | `q` at 105 |
| `src/seon/render/walk.clj` | `q` at 221; `pull` at 285, 307, 325, 361 |
| `src/seon/render/web.clj` | `q` at 295, 666, 903; `pull` at 309, 807 |

`render/web.clj:700,907` also calls `d/transact`; those two writes do not
belong in the read-facade migration. They move through the existing store
transaction owner in a write-owner cleanup.

The first migration should prefer explicit forms at low-level pure helpers
that already receive `db`. The render-pass owner binds latest once, which
enables omitted forms for registered render functions and preserves a single
basis. This avoids a mechanical rewrite that silently discards already
explicit database values.

### Owner lane C — context, problems, and evaluator reads

These are application reads and should route through the same facade after
the render seam proves the contract:

| file | direct reads |
|---|---|
| `src/seon/config.cljc` | `pull` at 267 |
| `src/seon/context.clj` | `q` at 97, 151, 158, 189, 229, 235 |
| `src/seon/error.clj` | `q` at 652, 664, 676 |
| `src/seon/oversight.clj` | `q` at 72 |
| `src/seon/problems.clj` | `q` at 76, 96, 113, 128, 162, 180, 225, 241 |
| `src/seon/sci/eval.clj` | `pull` at 380, 401; `q` at 413, 424, 430, 458 |

`src/seon/oversight.clj:44` also calls
`d/committed-value-identity`. It belongs in this owner lane but not in the
initial five-read implementation wave; add a thin facade projection when the
consumer migration reaches it. `src/seon/error.clj:850` is only a `d/pull`
docstring reference, not a call.
These consumers require an explicit result-union migration; a blind namespace
alias change would make truthy result envelopes corrupt their predicates.

### Owner lane A — agent/cluster runtime reads

These are also application reads, but they sit on lifecycle and transaction
critical paths and should migrate only after the facade result contract is
settled:

| file | direct reads |
|---|---|
| `src/seon/cluster/agent.clj` | `q` at 110, 156, 326, 430 |
| `src/seon/cluster/loop.cljc` | `q` at 370, 447; `pull` at 454, 804, 1032 |
| `src/seon/cluster/message.cljc` | `q` at 81, 92, 130, 193, 221, 452 |
| `src/seon/cluster/run.cljc` | `pull` at 174, 209, 242, 249, 359, 462, 567, 585, 598, 703, 706; `q` at 205, 396, 572, 785, 792 |
| `src/seon/cluster/work.cljc` | `q` at 86, 107, 115, 194, 212, 220, 238, 249, 261, 268, 275, 285, 345, 379, 406, 416, 432, 590; `pull` at 305 |

The shared working tree has unrelated concurrent edits. This report
inventories the line locations at its final review but does not claim
ownership or modify them.

### Owner lane S — schema, reconciliation, and cluster boot reads

| file | direct reads or temporal transforms |
|---|---|
| `src/seon/schema.cljc` | `q` at 362, 372, 382, 394, 414, 424; `history` at 406 |
| `src/seon/reconcile.cljc` | `q` at 106, 126, 139; `history` at 117; `pull` at 337 |
| `src/seon/cluster.clj` | `q` at 303, 432, 544, 1059; `pull` at 320 |
| `src/seon/cluster/store.clj` | `q` at 396 |

The read operations should eventually route through `seon.db`. `history`
should wait for a separate pure database-value-transform slice rather than
expanding the render facade. `cluster.clj` and `reconcile.cljc` also contain
writes listed below.

### Owner lane W — writes outside the database resource leaf

These direct calls are not part of the read facade, but the `datahike.api`
census exposes them:

| file | direct writes |
|---|---|
| `src/seon/cluster.clj` | `transact` at 344, 347, 350, 451, 476, 486 |
| `src/seon/reconcile.cljc` | `transact` at 421 |
| `src/seon/fn.clj` | `transact` at 85, 87 |
| `src/seon/render/web.clj` | `transact` at 700, 907 |
| `src/seon/test/runner.clj` | `transact` at 201 |
| `src/seon/cluster/ancestor.clj` | `transact` at 215, 218 |

The existing fresh write door is `seon.cluster.store/transact!`. These callers
should be reviewed by that owner. This report does not design or authorize a
second `seon.db/transact!`.

### Owner lane P — legitimate Datahike platform leaves

These operations should not be routed through the render read facade:

| file | direct platform operations |
|---|---|
| `src/seon/cluster/store.clj` | create at 85, 254; connect at 86, 302, 368; database value at 306; existence at 292; release at 308, 334; branches at 357; temporal transforms at 107-109; the owning `transact` at 447 |
| `src/seon/cluster/registry.clj` | branches at 108; `branch!` at 205; `delete-branch!` at 321; storage GC at 338 |
| `src/seon/cluster/export.clj` | branches at 140; create at 145; connect at 146, 162; branch at 149; release at 159, 167, 169 |
| `src/seon/cluster/ancestor.clj` | release at 223 of the connection it opens |
| `src/seon/cluster.clj` | release at 1158, pending consolidation with the store owner |
| `src/seon/cluster/wake.cljc` | `listen` at 132 and `unlisten` at 171 for the one wake owner |

Keeping Datahike inside these leaves is not an exception to ruling 22a.
Renders and application reads use the facade; the database resource owner must
still call its dependency.

## Implementation order after owner review

No implementation should begin until the owner rules on the closed read
result shape. That is the only design choice here that intentionally corrects
rather than literally repeats the last public facade.

After approval:

1. register the database/read/evidence schemas;
2. implement synchronous `with-db`, `with-read-evidence`, `query`, `pull`,
   `pull-many`, eager `entity`, and bounded `datoms` with built-in evidence;
3. prove explicit and omitted forms, nonzero query source position,
   multi-source refusal, error-shaped success data, absent pull attributes,
   pull-many alignment, entity ref walking, bounded index ranges,
   empty/static capture, and failure widening;
4. migrate the six render files atomically with the result-union handling;
5. connect the capture to the falsifier's interest reduction and reverse
   index, including `db-before` checks for range exits;
6. prove one render pass observes one basis and a repairing transaction wakes
   a previously failed render; and
7. prove two agents share Datahike's result cache and attribute bucket while
   retaining distinct registration references; then schedule the remaining
   application-read lanes.

The shortest acceptance falsifiers are:

- query a missing attribute, capture it, transact that attribute, and observe
  the render wake;
- return a legitimate map exactly shaped like `:seon.error/value` and prove it
  remains successful data;
- bind cluster A, concurrently bind cluster B, and prove omitted reads never
  cross;
- advance the connection between two omitted reads inside one pass and prove
  both reads used the database value bound at pass entry;
- run a query whose sole database source is not argument position zero and
  prove insertion and evidence position agree;
- walk an entity through a ref, prove eager ordinary data and conservative
  interest, and prove no Datahike Entity escapes;
- update an AVET value from inside to outside a registered range and prove the
  `db-before` lookup wakes it while an unrelated attribute does not;
- run `pull-many` with a missing middle ref and prove alignment plus one plan;
- run the same query and basis in two agent passes and observe first
  `miss-owner`, second `hit`, one memoized plan derivation, one attribute
  bucket with two references, and both registrations waking;
- fail after a narrow capture, prove installed interest is `:all`, repair via
  a disjoint transaction, and prove the later success narrows; and
- inspect the fresh `src/seon/render/` tree and prove it contains no
  `datahike.api` require or qualified call.

## Fix-history index

The commits below are the compact trail for the behaviors this design mines:

| commit | finding |
|---|---|
| `1c7da7f5d` | first JVM API layer |
| `a00a22086` | first CLJS Datahike facade |
| `ed72acb0c` | CLJS async transactions/listeners |
| `976fed5f` | transaction envelope, no throw into eval |
| `4056df8be` | positional query/pull/entity |
| `7da843c4d` | positional transaction |
| `05c867ae7` | temporal database values |
| `e2c3170ea` / `9d405344e` | normalized read capture and replay |
| `365052f0c` | routing by observed attributes |
| `fbc40f480` | remote authority session; reads become async |
| `2391007c0` | ordinary database descriptor |
| `67e867bd0` | cached latest database value |
| `4af318a6b` | query schema matches zero-or-more inputs |
| `31b4a8230` | explicit pull database arity documented |
| `921d185a3` | public pull keys restored |
| `daf8f41c3` | one-argument instrumented query restored |
| `5e3edf016` | transaction failures remain ordinary values |
| `8b8596a51` | Datahike dependency plans carried through queries |
| `a7192f859` | plans sent directly to interests |
| `835faf9e2` | read evidence captured across renders |
| `3169c1967` | facade exposes dependency projection |
| `f6d843ee7` | portable core and tier leaves |
| `f25e34594` | old tree archived; fresh tree becomes project |

## Chunk 7 — ruling 24 full-surface REPL falsifiers

The executable probe used the maintained Datahike revision from the dependency
ledger, a fresh canonical in-memory Seon database, and the already recorded
old reverse-index prototype in `tmp/agent-render-falsify/interest.clj`.
Synthetic `:probe/*` attributes isolated the mechanisms from product data.
Every result below came from one ordinary CLJ process; no fresh `src/` code
was defined or edited.

### Entity: lazy incremental reads are observable but do not survive the boundary

Datahike's `Entity` holds an immutable database value, eid, touched flag, and
cache (`impl/entity.cljc:17-20,53`). A keyed lookup searches exactly
`[eid attribute]`, including an absent attribute
(`impl/entity.cljc:165-184`). A ref returns another lazy Entity
(`impl/entity.cljc:22-29`). Reverse lookup searches the forward ref
(`impl/entity.cljc:31-39`). In contrast, `seq` and `count` call `touch`, which
scans every current EAVT datom for the entity
(`impl/entity.cljc:132-151,205-212`).

The wrapper falsifier implemented `ILookup` by recording the requested
attribute before delegating, recursively wrapped returned entity refs, and
implemented `Seqable`/`Counted` by widening the collector:

```clojure
(valAt [_ attribute]
  (swap! reads #(if (= :all %) :all (conj % attribute)))
  (traced-value (get entity attribute) reads))

(seq [_]
  (reset! reads :all)
  (seq entity))
```

One renderer-like walk read message content, followed `:probe/from` to the
agent's id, and tested one absent attribute. The exact output was:

```clojure
{:walk {:content "hello"
        :agent-id "agent-a"
        :missing ::missing}
 :keyed-evidence
 #{:probe/content :probe/from :probe/id :probe/missing}
 :after-seq-evidence :all
 :raw-opaque? true}
```

Installing the keyed set in the old reverse candidate index and changing
`:probe/content` returned `[["agent-a" :entity-render]]`; changing
`:probe/noise` returned `[]`. The interest machinery can therefore consume
incremental wrapper evidence. That does not make the wrapper the right public
value:

| option | prototype result | ruling |
|---|---|---|
| wrapper around raw Entity | exact for `get`, forward-ref navigation, and absent keys; reverse keys also require canonicalization to their forward attribute; `seq`/`count` widen | reject: every lazy ref needs wrapping, map operations are an open escape set, and this recreates Datahike Entity |
| eager bounded `'[*]` projection | ordinary map; Datahike plan attributes are `:all` | choose for `entity`: sound, basis-closed, value-renderable, conservative |
| refuse entity and require pull | most precise, simplest mechanism | reject as the public answer: ruling 24 requires the main API; retain explicit `pull` as the precision path |

The fresh value renderer independently returned `true` from
`opaque?` for the raw Entity (`src/seon/render/value.cljc:170-204`).
The executing eager alternative returned:

```clojure
{:value {:db/id 2330
         :probe/content "hello"
         :probe/from {:db/id 2328}
         :probe/id "message"}
 :attributes :all}
```

Thus `entity` is supported, but raw lazy entity is deliberately not.

### Datoms: a precise range needs `db-before`

The maintained AVET implementation slices from resolved start through resolved
end (`db.cljc:281-298`). Its tests prove both endpoints inclusive
(`test/index_test.cljc:222-231`). The probe:

```clojure
(d/index-range db {:attrid :probe/rank :start 20 :end 39})
```

returned 20 datoms, first value 20 and last value 39.

The initial range matcher checked only values present in the transaction
report. It returned zero wakes when 20 entities moved from values 20–39 to
120–139: the report names the asserted replacement, not the displaced old
cardinality-one value. The sound matcher was:

```clojure
(some
 (fn [datom]
   (when (= attribute (datom-attribute report datom))
     (let [old-values
           (map :v
                (d/datoms
                 (:db-before report)
                 {:index :eavt
                  :components [(:e datom) (:a datom)]}))]
       (or (in-range? (:v datom) start end)
           (some #(in-range? % start end) old-values)))))
 (:tx-data report))
```

This is one EAVT point read in `db-before` per candidate `(e,a)` in the
transaction report, memoizable within that report. It catches insertion into,
mutation within, mutation out of, and retraction from the inclusive range.

The precision experiment used 60 actual transaction reports: 20 rank changes
leaving the registered range, 20 rank changes outside it, and 20 unrelated
noise changes.

| evidence policy | wakes | true wakes | false wakes | precision |
|---|---:|---:|---:|---:|
| fail-open `:all` | 60 | 20 | 40 | 33.3% |
| attribute `:probe/rank` only | 40 | 20 | 20 | 50.0% |
| attribute plus inclusive range and `db-before` | 20 | 20 | 0 | 100.0% |

Range evidence is therefore worthwhile and honest when the attribute and
bounds are concrete. The facade must fail open to `:all` for an entity-only
EAVT prefix or any scan shape the shared attribute index cannot route
soundly. Attribute-only evidence remains a valid conservative middle case.

### Pull-many: one read, one plan, aligned values

The composition prototype was one dependency call:

```clojure
(d/pull-many-with-evidence
 db
 '[:probe/id :probe/content]
 [[:probe/id "message"]
  [:probe/id "missing"]
  [:probe/id "agent-a"]])
```

It returned:

```clojure
{:result [{:probe/id "message" :probe/content "hello"}
          nil
          {:probe/id "agent-a"}]
 :attributes #{:probe/id :probe/content}}
```

The vector exactly equaled the three individual pull results, including the
middle `nil`; each individual plan projected to the same attribute set.
`pull-many` therefore composes trivially from pull capture semantics while
using the fork's more efficient single operation and one shared plan.

### Dual use and two-agent dedupe

The prototype's only call-mode seam was:

```clojure
(def ^:dynamic *capture-context* nil)

(defn capture! [entry]
  (when *capture-context*
    (swap! *capture-context* conj entry)))

(defn facade-query [query database & inputs]
  (let [response (apply d/q-with-evidence query database inputs)]
    (capture! (evidence-entry query response))
    (:datahike.query/result response)))
```

Calling it outside a binding returned 40 and mutated no capture. Binding a
collector in each of two simulated agent passes returned the same value and
one evidence entry per pass. There was no registration or agent argument in
the read.

After `clear-query-cache!`, two passes used the same query, ordinary inputs,
and immutable database value:

```clojure
{:agent-cache-outcomes
 [:datahike.cache.outcome/miss-owner
  :datahike.cache.outcome/hit]
 :plan-derivations 1
 :memo-entries 1
 :attribute-buckets
 {:probe/rank
  (["agent-a" :rank-render]
   ["agent-b" :rank-render])}
 :interest-count 2
 :relevant-wakes
 (["agent-a" :rank-render]
  ["agent-b" :rank-render])
 :irrelevant-wakes []}
```

Datahike owns computation dedupe. Its cache key includes admitted connection
generation and commit ID plus the normalized query computation
(`query.cljc:2428-2440,2658-2685,3070-3110,4636-4688`). A 301-call warm sample
on the same query and basis measured 22.792 µs p50; a repeated warmed process
measured 16.542 µs. The independent query-invalidation probe measured
21.666–25.459 µs p50. This is the owner's 22 µs class, not a Seon result
cache to build.

Seon owns only interest dedupe. The memo is bucketed by normalized query form
and guarded by returned dependency plan plus source position, so dynamic
rules or a changed plan cannot reuse an unsound projection. Identical calls
derive plan attributes once. The reverse index stores one attribute key and
the two tiny `[agent-id registration-name]` references; it does not duplicate
the query, result, plan, or attribute bucket per agent.

## Port recommendation — Generation G

Port **Generation G**, commit `f6d843ee7`, as the fresh facade generation.
It is the only generation that already combines the mature positional forms,
parsed query-source alignment, executing-read dependency plans, eager
entity/pull values, pull-many, ordinary database values, synchronous CLJ
metadata, and one portable interception point. Generation C proves the
original dual-call ergonomics but predates sound plans. Generations D and E
reconstruct reads or route them through condemned machinery. Generation F
introduces the right plans; G is its complete facade accretion.

“Port G” means the following exact quarry, not the old subsystem:

| exact old source | ported result |
|---|---|
| `src-old/seon/db.cljc:506-553` | parsed query input/source alignment and plan-to-attribute projection |
| `src-old/seon/db.cljc:595-704` | query, pull, pull-many, and eager entity public semantics |
| `src-old/seon/db.cljc:782-814` | bounded eager index-page result shape, generalized into bounded `datoms` |
| `src-old/seon/db/fiber.cljs:30-48` | capture-if-bound, plain-call-if-absent, distinct invocation-local evidence; translated synchronously to CLJ |
| `src-old/seon/db/writer.clj:1044-1130` | direct `pull-with-evidence`/`pull-many-with-evidence` calls and ordinary datom projection |
| `src-old/seon/db/writer.clj:2756-3205` | plan reduction, shared reverse attribute index, exact matching, and candidate routing |

The fresh port calls maintained Datahike internals directly. It does **not**
port `leaf.cljc`, the JVM host evidence no-op, AsyncLocalStorage, sessions,
protocol, UDS transport, database descriptors, pools, request ids, timeouts,
writer jobs, or branch-scope restoration. The CLJS async variant is quarry
evidence for the capture lifetime only; no async residue enters fresh CLJ.

Ruling 24 adds two owner-reviewed accretions to G without selecting a second
generation:

- the public `datoms` name wraps G's bounded index-page discipline plus an
  inclusive AVET range and the proven `db-before` matcher; and
- CLJ implements the capture behavior G only had in its CLJS fiber, because
  G's JVM host no-op is the one known defect in the otherwise selected
  generation.

The resulting boundary is:

```text
cluster connection
    → one immutable latest database value at pass entry
        → seon.db query / pull / pull-many / entity / datoms
            → ordinary value + executing-read or index evidence
                → closed read result
                    → optional pass-bound capture
                        → memoized attributes/ranges
                            → one shared reverse bucket
                                → per-agent registration references
```

This ports the facade that worked best with the maintained fork while deleting
the system around it. Datahike dedupes computation; the mined reverse index
dedupes candidate interests; the facade remains one synchronous CLJ read
surface for both one-off and registered calls.
