---
type: research
status: active
tags: [research, runtime]
---

# Vector order audit

## Question and result

This audit traces every known producer and consumer of the twelve
collection-shaped database attributes identified by the
`seon.schema/canonical-database-attributes` probe. It classifies the semantic
relationship represented by each attribute, not the incidental Clojure value
returned by pull.

The result is:

- `:seon/embedding` is **ORDERED**. Its positions are vector-space
  coordinates, and it must remain one tuple value.
- The other eleven attributes are **SET** under the requested taxonomy:
  preserving their transaction input order has no semantic value.
- Two of those eleven, `:seon.config/agent-context` and
  `:seon.config/root-context`, are more precisely zero-or-one relationships.
  A cardinality-one ref would express their invariant better than either an
  ordered tuple or an unordered collection.
- No attribute is **UNCLEAR**.
- Three attributes are queried by individual member and therefore cannot be
  converted to tuples without changing their readers:
  `:seon.error/frames`, `:seon.agent/ctx`, and
  `:seon.ns/require-edges`.
- No correct behavior among these twelve depends on Datahike's accidental
  ordering. Error-frame rendering and context assembly explicitly recover
  semantic order from stored attributes. The two singleton config relations
  have a latent integrity hazard if their maximum-one invariant is ever
  violated, but no intended ordering semantics.

Static source inspection also found a persisted vector outside the measured
twelve whose consumers do assume database round-trip order:
`:seon.agent.turn/evals`. That is a separate live risk and suggests that the
canonical-attribute probe should be rerun with the eval-receipt namespace
loaded.

## Dependency ledger and method

The relevant selected sources are:

- Malli `0.20.0`, selected at `deps.edn:6-7`.
- The maintained Datahike checkout selected through
  `reference-code/datahike` at `deps.edn:23-26`, commit
  `caf526850084a9d5846ccd9ea34251fe411e0d6b`.
- The maintained Proximum checkout selected through
  `reference-code/proximum` at `deps.edn:40-42`, commit
  `9846d3e79e1aee48474bc876d3d563d7137209c6`.

For each attribute, this audit searched its schema declaration, all literal
references, owning pull selectors, Datalog clauses, transaction-data
construction, and indirect readers of the pulled value. Classification is
based on the decisive consumer, not the declared collection form or attribute
name.

The ordering probe used `datahike.core/empty-db` and `datahike.api/with`, so it
performed no durable database write. It transacted deliberately permuted
scalar values and refs and then pulled them from the resulting immutable
database value.

## What Datahike actually preserves

The portable helper classifies every Malli `:vector`, `:set`, or `:sequential`
form as cardinality-many at `src/seon/db/internal.cljc:135-140`. The JVM schema
builder instead recognizes any collection whose inner schema is `:float` or
`:double` as a tuple and cardinality-one at
`src/seon/db/datahike/schema.clj:162-208`.

There is an important present-code qualification to the apparent disagreement.
The raw portable `form->cardinality` helper reports cardinality-many for
`:seon/embedding`, but the final portable `malli->datahike-attr` mapping
overrides `:db.secondary/only` float/double collections to tuple,
cardinality-one at `src/seon/db/internal.cljc:149-181`. Consequently, the
currently registered embedding, which is secondary-only, reaches the same
installed shape on both paths. The helper-level contract still disagrees, and
the JVM rule is broader: a non-secondary float vector would become a tuple on
the JVM but cardinality-many through the portable mapper.

For cardinality-many attributes, Datahike's pull implementation scans the EAVT
index for `[entity attribute]` at
`reference-code/datahike/src/datahike/pull_api.cljc:262-274` and accumulates the
matching datoms into a result vector at
`reference-code/datahike/src/datahike/pull_api.cljc:198-207` and
`reference-code/datahike/src/datahike/pull_api.cljc:240-243`. The EAVT
comparator orders by entity, attribute, value, and transaction at
`reference-code/datahike/src/datahike/datom.cljc:325-330`. A pulled vector is
therefore index-order materialization, not preservation of transaction input
order.

The probe made that distinction observable:

```clojure
{:scalar-input [9 1 5]
 :scalar-pull  [1 5 9]}
```

Fresh component refs can appear to retain insertion order when their entity
IDs happen to be allocated in the same encounter order. The falsifying probe
allocated three child entities first, then attached them to a parent in the
permutation `[-4 -2 -3]`:

```clojure
{:input-tempids [-4 -2 -3]
 :tempids       {-2 1, -3 2, -4 3, -1 4}
 :pulled-names  ["two" "three" "four"]}
```

The refs came back in ascending target entity-ID order, not the parent's input
order. This proves that same-transaction component creation can make a raw
pull look ordered by accident, while a different legal transaction shape
scrambles that apparent order.

## Classification summary

| Attribute | Classification | Individual-member query? | Decisive read |
|---|---|---:|---|
| `:seon.error/frames` | SET | Yes | `src/seon/agent/debug.cljs:482-510` |
| `:seon.agent/ctx` | SET | Yes | `src/seon/agent/ctx.cljc:1619-1633` |
| `:seon.config/root-context` | SET, zero-or-one | No | `src/seon/config.cljs:1121-1140` |
| `:seon.config/agent-context` | SET, zero-or-one | No | `src/seon/config.cljs:1121-1140` |
| `:seon.config/context-profiles` | SET | No | `src/seon/config.cljs:1160-1173` |
| `:seon.config/provider-descriptors` | SET | No | `src/seon/ai/core.cljc:410-422` |
| `:seon.config/model-variants` | SET | No | `src/seon/config.cljs:1175-1184` |
| `:seon.config.render-context/file-fingerprints` | SET | No | `src/seon/config.cljs:978-987` |
| `:seon.agent.web/allowed-domains` | SET | No | `src/seon/agent/web/core.cljc:46-54` |
| `:seon.fn/read-attrs` | SET | No | `src/seon/agent/ctx/canvas.cljc:150-152` |
| `:seon.ns/require-edges` | SET | Yes | `src/seon/agent/ctx/namespaces.cljc:169-201` |
| `:seon/embedding` | ORDERED | No | `src/seon/embed.clj:1220-1239` |

## Per-attribute evidence

### `:seon.error/frames` — SET

The stack itself is ordered, but the outer database relationship is not the
order authority. Each frame stores an explicit
`:seon.error.frame/index`; the schema declares that ordinal alongside the
component-ref collection at `src/seon/error.cljc:80-98`.

Writes:

- CLJS stack parsing walks the captured stack and assigns indices with
  `map-indexed` at `src/seon/error.cljc:319-337`.
- The JVM path does the same at `src/seon/error.cljc:345-357`.
- Error recording attaches the produced frames at
  `src/seon/error.cljc:751-752`.
- The transaction projection emits nested component data at
  `src/seon/error.cljc:519-546`; the persistence path is
  `src/seon/error.cljc:449-460`.

Reads:

- Debug acquisition pulls the frame components at
  `src/seon/agent/debug.cljs:367-382`.
- Compact rendering explicitly sorts by frame index before taking the first
  frame at `src/seon/agent/debug.cljs:356-365`.
- Full rendering explicitly sorts all frames by frame index before rendering
  them in stack order at `src/seon/agent/debug.cljs:482-510`.
- Warning acquisition queries the individual index-zero frame at
  `src/seon/agent/ctx/warnings.cljc:407-412`.

The decisive full-render reader proves that the semantic stack order is
recovered from the ordinal, so `[:set :seon.db/ref]` is the correct
relationship shape. The member query also prevents tuple conversion.

Raw pulls can be correct by accident when component tempids receive entity IDs
in frame insertion order. The empirical ref probe above proves that this is
fragile. Current error rendering is nevertheless correct for the right reason:
no located renderer relies on raw pull order.

### `:seon.agent/ctx` — SET

Context blocks carry explicit `:seon.agent.ctx/priority` and
`:seon.agent.ctx/name` fields at `src/seon/agent/ctx.cljc:69-82`.

Writes:

- Config resolution constructs ordered intermediate context rows at
  `src/seon/config/resolve.cljc:1508-1522`, merges and sorts root blocks at
  `src/seon/config/resolve.cljc:1524-1542`, and creates agent/root/profile
  context entities at `src/seon/config/resolve.cljc:1544-1571`.
- Config reconciliation transacts those entities through
  `src/seon/client.cljs:1801-1840`.
- Agent creation builds and attaches its initial context components at
  `src/seon/agent.cljs:384-437`, `src/seon/agent.cljs:522-531`, and
  `src/seon/agent.cljs:560-580`.
- Pod-side replacement retracts and installs the exact collection at
  `src/seon/agent/ctx/admin.cljs:16-19`, called at
  `src/seon/agent/ctx/admin.cljs:84-128`.
- JVM-side replacement does the equivalent at
  `src/seon/host/context.clj:456-504`.
- Namespace-driven updates reach the same owner through
  `src/seon/ai/generate_code.cljs:251-278` and
  `src/my/ns.cljs:194-200`.

Reads:

- The central `agent-blocks` reader decodes the pulled relation and explicitly
  sorts blocks by priority and name at `src/seon/agent/ctx.cljc:1619-1633`.
- Profile selection merges relations and sorts them again at
  `src/seon/agent/ctx.cljc:1786-1803`; prompt assembly consumes that sequence
  at `src/seon/agent/ctx.cljc:1820-1854`.
- Driver acquisition and selection are at
  `src/seon/agent/ctx/driver.cljs:208-215`,
  `src/seon/agent/ctx/driver.cljs:313-315`,
  `src/seon/agent/ctx/driver.cljs:369-380`, and
  `src/seon/agent/ctx/driver.cljs:512-519`.
- The admin and JVM readers independently sort by the same explicit fields at
  `src/seon/agent/ctx/admin.cljs:21-34` and
  `src/seon/host/context.clj:440-454`.
- Name-based lookups, rather than positional reads, occur at
  `src/seon/agent/ctx/namespaces.cljc:551-555`,
  `src/seon/agent/ctx/canvas.cljc:86-98`,
  `src/seon/ai/generate_code.cljs:251-259`, and
  `src/my/ns.cljs:163-174`.
- Config context readers also sort after pull at
  `src/seon/config.cljs:1121-1132` and
  `src/seon/config.cljs:1160-1173`.
- `src/my/skills.cljc:256-266` queries individual context-block members.

The relation is a set; priority and name determine the agent-facing sequence.
The member query rules out a tuple without a reader rewrite.

The existing issue
`docs/seon/issues/context-block-order-is-static.md` does **not** share this
root cause. It concerns the static priority/name policy versus a desired
derived stability or hysteresis policy. Every relevant reader intentionally
sorts after pull, so preserving database insertion order would neither change
nor solve that issue.

### `:seon.config/agent-context` — SET, more precisely zero-or-one

The schema permits at most one component ref at
`src/seon/config/resolve.cljc:1018-1019`.

Writes:

- Resolution always emits exactly `[agent-context]` with the fixed agent
  context identity at `src/seon/config/resolve.cljc:1544-1555`.
- The shared config writer attaches and reconciles the singleton at
  `src/seon/client.cljs:1811-1840`.

Reads:

- The accessor takes the sole relation member with `first`, then sorts the
  member's context blocks by their explicit fields at
  `src/seon/config.cljs:1121-1132`.
- Root/agent selection uses the resulting named values at
  `src/seon/config.cljs:1134-1140`.

No Datalog clause matches an individual member. Input order cannot matter for
a legal value containing no more than one member, so the requested
classification is SET. Cardinality-one ref is the more accurate target.
If corrupt data introduced multiple children, `first` would select the
lowest-EID child rather than a declared preference. That is an invariant
failure, not an ordered-collection contract.

### `:seon.config/root-context` — SET, more precisely zero-or-one

The schema permits at most one component ref at
`src/seon/config/resolve.cljc:1020-1021`.

Writes:

- Resolution always emits the one fixed root context child at
  `src/seon/config/resolve.cljc:1556-1559`.
- The shared config reconciliation path is
  `src/seon/client.cljs:1811-1840`.

Reads:

- The same accessor uses `first` and then explicitly sorts nested blocks at
  `src/seon/config.cljs:1121-1132`.
- Root-first selection is by named field, not collection position, at
  `src/seon/config.cljs:1134-1140`.

No individual-member query exists. As with agent context, legal data has no
order to preserve and a cardinality-one ref would state the invariant better.

### `:seon.config/context-profiles` — SET

Writes:

- The manifest accepts profile maps at
  `src/seon/config/resolve.cljc:824-825` and
  `src/seon/config/resolve.cljc:1275-1276`.
- Resolution sorts profile keys only to make transaction data deterministic,
  then creates identified child entities at
  `src/seon/config/resolve.cljc:1561-1571`.
- The parent collection schema is at
  `src/seon/config/resolve.cljc:1023-1042`.
- The common config path builds the singleton at
  `src/seon/client.cljs:1811-1819`, invokes reconciliation at
  `src/seon/client.cljs:1830-1840`, and performs exact changed-attribute
  replacement at `src/seon/runtime/state.cljs:247-261`.

Reads:

- Autocomplete pulls profiles by selector and looks them up by identity at
  `src/seon/repl/autocomplete.cljs:156-174`.
- The owning accessor reindexes profile children by profile identity; only
  each profile's inner context blocks are sorted at
  `src/seon/config.cljs:1160-1173`.

No parent-member Datalog query exists. The outer relationship is a set of
identified profiles.

### `:seon.config/provider-descriptors` — SET

This attribute does not encode a fallback or precedence chain.

Writes:

- Its component schema is at `src/seon/config/resolve.cljc:1054-1060`.
- Resolution uses the explicit manifest collection when present, otherwise
  `(vals hosted-provider-descriptors)`, at
  `src/seon/config/resolve.cljc:1990-1993`. Map values do not establish a
  supported precedence contract.
- It uses the shared config reconciliation path at
  `src/seon/client.cljs:1811-1840` and
  `src/seon/runtime/state.cljs:247-261`.

Reads:

- AI acquisition pulls descriptor components at
  `src/seon/ai/core.cljc:228-244`.
- Resolution treats the stored collection as a dataset, reindexes it by
  provider identity, and merges identity maps at
  `src/seon/ai/core.cljc:410-422`.
- Provider lookup is an exact identity lookup at
  `src/seon/ai/provider.cljc:338-341`.
- Model resolution selects the exact configured provider ID at
  `src/seon/ai/core.cljc:445-458`.

There is no first/last/indexed access, sequence fold, or element Datalog query.
The outer relationship is a set of identified descriptor rows. Reordering it
cannot alter provider fallback because no such chain is read from it.

### `:seon.config/model-variants` — SET

Writes:

- The manifest declares a map keyed by variant identity at
  `src/seon/config/resolve.cljc:774-783` and
  `src/seon/config/resolve.cljc:1278`.
- Resolution sorts keys for deterministic transaction data and emits
  identified child entities at `src/seon/config/resolve.cljc:2214-2221`.
- The component collection schema is at
  `src/seon/config/resolve.cljc:1043-1053`.
- The common writer is `src/seon/client.cljs:1811-1840` and
  `src/seon/runtime/state.cljs:247-261`.

Reads:

- AI acquisition pulls the components at `src/seon/ai/core.cljc:228-244`.
- The config accessor reindexes them by variant identity at
  `src/seon/config.cljs:1175-1184`.
- AI resolution consumes the resulting identity map at
  `src/seon/ai/core.cljc:326-332`.
- Agent selection and fallback are keyed lookups at
  `src/seon/agent.cljs:357-369` and
  `src/seon/ai/core.cljc:459-466`.

There is no element query or positional read. The relation is a set.

### `:seon.config.render-context/file-fingerprints` — SET

Writes:

- Fingerprint components and their path identity are declared at
  `src/seon/config/resolve.cljc:62-78`.
- Resolution deduplicates and sorts paths before vectorizing them at
  `src/seon/config/resolve.cljc:1293-1315`, computes their hashes at
  `src/seon/config/resolve.cljc:1317-1325`, and attaches them at
  `src/seon/config/resolve.cljc:2224-2226`.
- The shared config reconciliation path is
  `src/seon/client.cljs:1811-1840` and
  `src/seon/runtime/state.cljs:247-261`.

Reads:

- The config accessor linearly locates the unique child by its path identity
  at `src/seon/config.cljs:978-987`.
- The render-side equivalent also selects by path at
  `src/seon/render/configuration.cljc:40-47`.
- File context rendering compares the expected and current fingerprint for
  that path at `src/seon/agent/ctx.cljc:183-220`.

No collection position or member Datalog query is used. Sorting the producer
only stabilizes transaction data; the database relationship is a set keyed by
path.

### `:seon.agent.web/allowed-domains` — SET

Writes:

- Config resolution copies the manifest collection with `vec` at
  `src/seon/config/resolve.cljc:2146`.
- It reaches the database through the common singleton reconciliation at
  `src/seon/client.cljs:1811-1840` and
  `src/seon/runtime/state.cljs:247-261`.

Reads:

- The client acquires the complete web config at
  `src/seon/client.cljs:610-627`.
- `web-policy` projects the allowed-domain collection at
  `src/seon/config.cljs:919-936`.
- Pod and JVM leaves pass that value through at
  `src/seon/agent/web/pod.cljs:107-112` and
  `src/seon/agent/web/host.clj:43-46`.
- The decisive authorization check uses membership via `some`, not precedence,
  at `src/seon/agent/web/core.cljc:46-54`; policy validation passes the same
  collection at `src/seon/agent/web/core.cljc:130-141`.

There is no element database query. Domain order is irrelevant, so the schema
should be a set.

### `:seon.fn/read-attrs` — SET

Writes:

- Host recording derives the value as a Clojure set at
  `src/seon/host/record.clj:195-207`.
- Exact replacement retracts the old values and adds sorted members only for
  deterministic transaction data at `src/seon/host/record.clj:209-221`.
- The tee path invokes that replacement at
  `src/seon/host/record.clj:453-456`.

Reads:

- Canvas acquisition pulls candidate read attributes at
  `src/seon/agent/ctx/canvas.cljc:23-35`.
- It removes invalid values and deduplicates before vectorizing at
  `src/seon/agent/ctx/canvas.cljc:100-114`.
- The decisive cross-function accumulation is explicitly set union at
  `src/seon/agent/ctx/canvas.cljc:150-152`.
- Timestamp derivation uses membership, not iteration order, at
  `src/seon/agent/ctx/canvas.cljc:116-124`; selected values are returned as a
  set at `src/seon/agent/ctx/canvas.cljc:170-178`.
- Surface rendering similarly accumulates a set at
  `src/seon/render/surface.cljc:58-75`, and the context driver copies the
  derived set at `src/seon/agent/ctx/driver.cljs:520-529`.

No individual-member Datalog clause was found. The declaration should be a
set.

### `:seon.ns/require-edges` — SET

Writes:

- Source analysis already declares and derives require edges as a set at
  `src/seon/ns/source.cljc:25-32` and `src/seon/ns/source.cljc:61-91`.
- Client namespace transaction data sorts the set only for determinism at
  `src/seon/client.cljs:1083-1098`.
- The database program compares sets and writes a sorted projection at
  `src/seon/db/program.clj:229-244`.
- Agent-home derivation produces a set at
  `src/seon/agent/home.cljc:196-213` and vectorizes it for transaction data at
  `src/seon/agent/home.cljc:215-237`; creation and recovery transact it at
  `src/seon/agent.cljs:430-437`, `src/seon/agent.cljs:446-466`, and
  `src/seon/agent.cljs:1274-1299`.
- Host recording derives a set and exactly replaces sorted members at
  `src/seon/host/record.clj:249-273`, invoked at
  `src/seon/host/record.clj:468-475`.
- Writer initialization normalizes members and retracts obsolete refs at
  `src/seon/db/writer.clj:1816-1829`.

Reads:

- Database-program acquisition pulls and normalizes edges into sets at
  `src/seon/db/program.clj:64-69` and
  `src/seon/db/program.clj:138-174`.
- Namespace context enumerates individual EAVT datoms at
  `src/seon/agent/ctx/namespaces.cljc:422-465` and reduces them without
  sequence semantics at `src/seon/agent/ctx/namespaces.cljc:169-201`;
  presentation is explicitly sorted at
  `src/seon/agent/ctx/namespaces.cljc:569-578` and
  `src/seon/agent/ctx/namespaces.cljc:917-920`.
- Menu acquisition pulls edges and converts them to sets at
  `src/seon/agent/ctx/menu.cljc:243-276` and
  `src/seon/agent/ctx/menu.cljc:326-344`.
- The menu query matches an individual edge at
  `src/seon/agent/ctx/menu.cljc:425-434`.
- Generated planning filters and sorts the relation explicitly at
  `src/my/plan/internal.cljc:957-962`.
- `edges->require-info` constructs maps and sets at
  `src/seon/ns/source.cljc:115-128`.

The source model and all consumers agree that require edges are a set. The
individual-datom and individual-member readers make a tuple conversion
incompatible with the current query model.

### `:seon/embedding` — ORDERED

Writes:

- The schema registers a secondary-only float vector at
  `src/seon/embed.clj:174-175`.
- Gemini response extraction preserves the provider's positional coordinate
  sequence with `mapv` at `src/seon/embed.clj:538-550`.
- Single, batch, and backfill paths write that sequence unchanged at
  `src/seon/embed.clj:804-814`, `src/seon/embed.clj:1047-1055`, and
  `src/seon/embed.clj:1160-1167`.
- Writer-side commit handling is at `src/seon/db/writer.clj:1190-1210`.

Reads:

- Secondary-index configuration binds `:seon/embedding` to a 1536-dimensional
  cosine index at `src/seon/embed.clj:284-297`.
- K-nearest-neighbor search converts the query vector to a positional
  `float-array` and sends it to Proximum at `src/seon/embed.clj:1220-1239`.
- Datahike's secondary bridge converts the stored sequence to `float[]` at
  `reference-code/datahike/src-secondary/datahike/index/secondary/proximum.clj:40-51`
  and inserts that positional array at
  `reference-code/datahike/src-secondary/datahike/index/secondary/proximum.clj:289-304`.
- Proximum treats array indices as vector coordinates for HNSW cosine
  operations at `reference-code/proximum/src/proximum/hnsw.clj:386-405`,
  `reference-code/proximum/src/proximum/hnsw.clj:620-649`, and
  `reference-code/proximum/src/proximum/hnsw.clj:712-725`.

Changing coordinate order changes the vector and its cosine similarity.
This is genuinely ordered and already uses the correct tuple/cardinality-one
storage on the current JVM and final portable mapping. It has no
individual-member Datalog query and is marked secondary-only, so raw pull is
not its reader.

## Individual-member queries and migration cost

The three current member-oriented access paths are:

- `:seon.error/frames`: Datalog matches a frame member and its explicit index
  at `src/seon/agent/ctx/warnings.cljc:407-412`.
- `:seon.agent/ctx`: Datalog matches a block member at
  `src/my/skills.cljc:256-266`.
- `:seon.ns/require-edges`: Datalog matches one edge at
  `src/seon/agent/ctx/menu.cljc:425-434`, and namespace context also scans
  individual EAVT datoms at
  `src/seon/agent/ctx/namespaces.cljc:422-465`.

Those attributes cannot become `:db.type/tuple` without replacing the
element-level query model. They should not become tuples anyway: their
semantics are sets.

The other nine attributes have no located Datalog clause or index scan that
matches one collection member. That absence does not make tuple conversion a
drop-in migration for component-ref collections: nested pull and component
ownership still expect cardinality-many refs. Their semantic target should be
chosen directly:

- unordered component refs remain cardinality-many and should be declared with
  `[:set ...]`;
- the two maximum-one config refs should become cardinality-one refs if the
  bridge can express their optionality without storing nil; and
- the embedding remains a tuple.

## Accidental-order dependency assessment

No correct behavior among the twelve relies on EAVT order:

- frames recover stack order from `:seon.error.frame/index`;
- context blocks recover prompt order from priority and name;
- identified config children are reindexed or selected by identity;
- allowed domains use membership;
- read attributes and require edges become sets;
- embeddings are stored as one tuple value.

The only apparent-order caveat is the zero-or-one config relations. Their
readers use `first`; if illegal multiple children existed, the selected child
would be an entity-ID accident. The fix is to enforce the cardinality-one
invariant, not preserve vector order.

## Other vector round-trip assumptions

### Confirmed: `:seon.agent.turn/evals`

This persisted component relationship is declared as a vector at
`src/seon/eval/receipt.cljc:40-41`. The writer appends one nested eval receipt
at a time in execution order at `src/seon/eval/receipt.cljc:69-88`.

At least two readers treat pull materialization as an ordered sequence:

- No-progress detection builds a `mapv` directly over pulled
  `:seon.agent.turn/evals` and compares vector equality at
  `src/seon/agent/loop/core.cljc:26-43`.
- Turn publication forwards `(mapv :seon.eval/id evals)` in pull order at
  `src/seon/agent/turn.cljs:758-760`; the receiving contract describes these
  as exact ordered eval identities at `src/my/plan.cljc:1179-1188`.

This is a real source-level assumption that receipt creation order survives a
cardinality-many ref round trip. It is currently correlated with ascending
receipt entity IDs, but the empirical probe proves that correlation is not a
database contract. The downstream generated-evidence Datalog query is itself
relational at `src/my/plan.cljc:476-485`, with acquisition at
`src/my/plan.cljc:630-660`, so preserving this order may require an explicit
ordinal rather than a tuple.

Other transcript readers avoid the trap by sorting evals using stored time and
entity ID at `src/seon/agent/ctx/transcript.cljc:730-739` and
`src/seon/agent/ctx/transcript.cljc:1209-1215`; autocomplete likewise sorts at
`src/seon/repl/autocomplete.cljs:583-590`.

Because this attribute was absent from the measured twelve, the registry probe
and namespace-loading assumptions need reconciliation before treating the
twelve-item count as exhaustive.

### Reviewed near misses

- `:seon.agent.turn/llm-attempts` is vector-shaped, but chronology readers
  explicitly sort by attempt ordinal at `src/seon/agent/turn.cljs:752-756`
  and `src/seon/agent/driver/host.clj:214-219`.
- Transcript tier/result-decay components are explicitly sorted by stored
  offsets at `src/seon/agent/ctx/transcript.cljc:118-151`.
- `:my.kb.shared/instructions` is sorted by timestamp and text after pull at
  `src/my/kb/shared.cljs:75-90`.
- `:my.blob/read-only-dirs` has order-sensitive `first` consumers at
  `src/my/blob/leaf.cljs:417-423` and
  `src/seon/dev/restore.clj:487-491`, but it is an ordinary nested
  launch/storage value, not a database cardinality-many attribute.

No other confirmed source location was found where a Clojure vector's
insertion order is assumed to survive a database cardinality-many round trip.

## Recommended schema actions

1. Change the nine genuinely unordered vector declarations to `[:set ...]`:
   frames, agent context blocks, context profiles, provider descriptors, model
   variants, file fingerprints, allowed domains, read attributes, and require
   edges.
2. Model root and agent config context as cardinality-one refs rather than
   collection-shaped maximum-one vectors.
3. Keep embedding as tuple/cardinality-one and reconcile the portable and JVM
   tuple trigger into one shared rule so raw helper output cannot disagree with
   installed schema.
4. Preserve the three member-query attributes as cardinality-many.
5. Audit and repair `:seon.agent.turn/evals` with an explicit semantic ordinal
   or an order-independent consumer contract, and rerun the canonical
   attribute inventory with its owner namespace loaded.
