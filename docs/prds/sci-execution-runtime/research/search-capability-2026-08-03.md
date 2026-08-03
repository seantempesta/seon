---
type: research
status: active
tags: [research, database, datahike, sci]
---

# Search capability without embeddings

## Owner-ruling addendum — Apache Lucene adopted (2026-08-03 night)

**OWNER RULING: the measured “no Lucene; predicate scans suffice” verdict
below is superseded. Seon adopts Apache Lucene full-text search as the one
set-up-once substrate for agent search, schema-reuse teaching, and future
consumers.** The old analysis remains below as the measured baseline and the
record of the recommendation the owner replaced; it no longer sequences
implementation.

Lucene is a derived index, never truth. Each cluster owns exactly one index at
`data/clusters/<name>/derived/lucene`, built from its database value and tagged
with the basis transaction it reflects. The existing Datahike `listen!`
transaction chain offers the complete transaction report to the search proc;
the proc advances only from an exact `db-before` basis. Because its input is a
sliding-one channel, a coalesced report is expected: a basis gap rebuilds from
`db-after` rather than attempting partial recovery. A missing directory or an
index commit whose recorded basis differs from the current database value also
rebuilds. No fact, recovery input, or source population is read from Lucene.

The exact selected coordinates in `deps.edn` are:

- `org.apache.lucene/lucene-core` `10.5.0`;
- `org.apache.lucene/lucene-analysis-common` `10.5.0`; and
- `org.apache.lucene/lucene-queryparser` `10.5.0`.

The corresponding Apache source is tag `releases/lucene/10.5.0`, peeled commit
`f6eaee8148b7569e83c433feacc4f624608188fd`, in the
[official Lucene repository](https://github.com/apache/lucene/tree/releases/lucene/10.5.0).
Vendoring the complete Lucene repository is impractical here: it is a large
multi-module build while this owner uses three published modules. Exact Maven
coordinates plus the exact source tag/commit give the reproducible pin without
pretending that an unused full checkout is maintained reference code.

## Declared index metadata addendum — 2026-08-03 late night

The owner-approved roster is now schema data. An attribute opts into Lucene by
declaring exactly one Malli property:

```clojure
[:string {:seon.search/index :text}]
[:string {:seon.search/index :symbol}]
```

`:text` is analyzed prose. `:symbol` first splits identifiers with
`seon.search/tokens`, so dots, slashes, hyphens, and other natural separators
become token boundaries before Lucene analysis. The property itself is declared
as `[:enum :text :symbol]` and therefore lifts as a keyword-valued Datahike
attribute. Commit `f08d79e96` established the general lift: a storable
namespaced Malli property is copied directly onto the canonical schema row. A
declaration carrying the property therefore becomes ordinary queryable data:

```clojure
{:seon.schema/key :seon.cluster.message/content
 :seon.search/index :text}
```

The roster is this query, not a field list in `seon.search`:

```clojure
[:find ?field ?mode
 :where
 [?schema :seon.schema/key ?field]
 [?schema :seon.search/index ?mode]]
```

Document family is also derived, not classified. It is the declared identity
attribute present on the same entity, found through schema rows carrying the
already-lifted `:seon.db/identity true`. Thus `:error/message` on a predicate
schema row belongs to the `:seon.schema/key` family, while
`:seon.error/message` on a durable fault belongs to `:seon.error/id`. No
keyword-namespace convention or identity-to-family map sits between those
facts. An open entity carrying more than one identity legitimately produces a
document under each identity family.

### Initial declaration roster

| Identity-attribute family | Declared fields | Agent question answered |
|---|---|---|
| `:seon.fn/sym` | `:seon.fn/sym` (`:symbol`), `:seon.fn/doc` (`:text`) | Which function name or documentation discusses this? |
| `:seon.ns/name` | `:seon.ns/name` (`:symbol`), `:seon.ns/doc` (`:text`) | Which namespace owns or explains this area? |
| `:seon.schema/key` | `:seon.schema/key` (`:symbol`), predicate `:error/message` properties (`:text`) | Which declared shape or predicate constraint matches this need? |
| `:seon.test/sym` | `:seon.test/sym` (`:symbol`) | Which recurring proof names this behavior? |
| `:seon.cluster.instruction/id` | `:seon.cluster.instruction/text` (`:text`) | Which cluster or agent instruction says this? |
| `:seon.cluster.message/id` | `:seon.cluster.message/content` (`:text`) | Which recorded message discusses this? |
| `:seon.error/id` | `:seon.error/message` (`:text`) | Which durable fault reports this problem? |

### Explicit holds

- Work-item goals wait for the work PRD because that domain has not yet settled
  its declaration or search question.
- Source bodies stay out. They are bulky retrieval targets, while declared
  calls, keywords, schemas, namespace ownership, and exact `doc`/`pull` remain
  the more honest structural discovery path.
- Blobs stay out. They are payload storage reached by digest, not another
  textual authority; indexing them would duplicate arbitrary large content and
  blur the field that asserted its meaning.
- Rendered output stays out. It is derived, may churn at presentation cadence,
  and would index a duplicate projection instead of its declaring facts.

### Re-measurement after data-backed declarations

The reproducible script now states its growth case and records the declared
roster plus indexed value/character counts. The exact command remains:

```bash
clojure -M:dev:test \
  -i docs/prds/sci-execution-runtime/research/scripts/search-lucene-measure-2026-08-03.clj \
  -m search-lucene-measure-2026-08-03
```

On OpenJDK `26.0.1` and Lucene `10.5.0`, with 1,000 deterministic synthetic
messages and ten deterministic synthetic instructions added before timing:

- the message field held 1,001 values / 148,920 characters and the instruction
  field held 11 values / 1,498 characters;
- three wiped-index rebuilds measured 265.67 ms minimum, 303.83 ms median, and
  699.18 ms p95/maximum;
- one message append advanced the exact report in 28.85 ms, and one instruction
  replacement advanced it in 18.75 ms; and
- 100 family-scoped message token queries measured 0.19 ms median / 0.32 ms
  p95, while the retained program query measured 0.53 ms median / 1.07 ms p95.

The immediately preceding same-machine fixed-program baseline was 258.53 ms
minimum / 285.72 ms median / 629.23 ms maximum for three builds and 25.47 ms
for one function update. Three rebuild samples are noisy, so the difference is
not presented as a fitted scaling curve. The honest result is narrower: the
index now includes cluster data as well as the program graph, and this explicit
1,010-row growth case kept rebuilds in the same sub-second band. Update timings
exclude the already-completed database transaction but include Lucene commit
and reader refresh.

### Tool and render feedback for the addendum

- The measurement's one bounded EDN map made the roster, corpus conditions,
  and latency distributions readable together; the previous number-only map
  made it too easy to forget what the index contained.
- Lucene's vector-provider `INFO` line is harmless but noisy relative to the
  focused result. The useful operational fact is already captured once as the
  128-bit preferred vector size.
- The live door query returned an honest empty roster before republishing and
  reforking because existing clusters are sovereign. That was useful feedback:
  a schema-resource edit is not a live database mutation, and the proof must
  name the fresh published cluster it exercised.

Both existing Datahike/Lucene examples were read end to end before this design:

- Scriptum at
  `reference-code/datahike/src-secondary/datahike/index/secondary/scriptum.clj`
  (Scriptum `0.1.27`, Lucene `10.3.2`) demonstrates transaction-coupled datom
  additions/retractions and deterministic document deletion; and
- JobTech Taxonomy API commit
  `19a5868d096e9ad174240c32ed50707b9c86d2eb`, especially
  `src/jobtech_taxonomy_api/db/search.clj` and `database_connection.clj`
  (Lucene `10.4.0`), demonstrates keeping search objects process-local and
  keying derived search state to database identity.

Seon adopted the ideas, not either bridge's code. Its document id is
`<fact-family>|<entity-id>|<field>`, so any relevant datom change deletes every
old document for that entity and reconstructs its current function symbol and
docstring, schema key, or test symbol from `db-after`. Namespace-name changes
rebuild because they affect documents reached through refs. Lucene objects do
not cross `seon.search`; the ordinary public `search` contract returns bounded
data or a flat error and scopes every query by declared fact family plus an
optional namespace prefix argument.

### Measurements

The reproducible measurement is
`docs/prds/sci-execution-runtime/research/scripts/search-lucene-measure-2026-08-03.clj`,
run on the full published test fixture with OpenJDK `26.0.1` and Lucene
`10.5.0`. Three wiped-index builds and 100 warmed queries per query shape
measured:

- full published-graph build: 243.87 ms minimum, 277.12 ms median, 627.55 ms
  p95/maximum;
- exact one-function incremental update, including commit + reader refresh but
  excluding the already-completed database transaction: 24.69 ms;
- scoped token query: 0.50 ms median, 1.04 ms p95, 2.00 ms maximum; and
- scoped substring query: 1.49 ms median, 2.02 ms p95, 2.73 ms maximum.

The database predicate-scan baseline below was 0.32 ms p50 / 0.43 ms p95 for
all docstrings and 2.31 ms p50 / 2.65 ms p95 for all source, with the wider
0.32–6 ms observed range across measured scans. Lucene therefore does not win
every small-corpus query; it is adopted because it supplies the owner-directed
full-text substrate and stable relevance/scoping contract while remaining in
the same latency band. The first `10.3.2` probe emitted an unsupported-Java-26
Vector API warning. Selecting current `10.5.0` removed it and reported the Java
vector incubator API active at 128 preferred bits.

## Verdict

Seon can offer useful, real search now without embeddings and without a second
index store. The right first implementation is one bounded, explicitly scoped
search function over an immutable database value:

- use the program graph's declared connections for structural questions
  (`:seon.fn/keywords`, `:seon.fn/calls`, schema refs, namespace refs);
- use AVET range reads for prefix search on indexed identities such as function
  symbols, namespace names, and schema keys; and
- use a predicate over a deliberately selected, small attribute slice for
  substring search in docstrings or source.

At the current published scale, the supposedly “slow” part is already cheap.
Scanning every function docstring for a case-insensitive substring measured
0.32 ms p50 / 0.43 ms p95; scanning every function source measured 2.31 ms p50
/ 2.65 ms p95. A derived trigram index is a valid later optimization and can
be made basis-exact, but the measured implementation took about 125 ms to
build in order to reduce a query to about 0.05 ms. It is not yet buying enough
to justify being the first mechanism.

The Apache Lucene claim in Datahike's README does not describe a built-in
Datahike API, Datahike Server, or a commercial service. It describes the
separate JobTech Taxonomy application. Independently, our Datahike pin already
contains an experimental Scriptum/Lucene secondary-index adapter. It is real
full-text search, but it is not active in Seon, is not `:db/fulltext`, stores
Lucene segments in a second filesystem tree outside Konserve, and cannot meet
the “derive from this database value at this basis” constraint as simply or as
strongly as the database-only design.

## Audit coordinates and dependency ledger

This audit used:

- Seon checkout HEAD `9fe36ee3` for committed first-party source, plus the
  published `current-src` artifact at commit ID
  `6a70edff-34b3-5e60-a45b-bba3708d6a74`
  (`data/clusters/build/current-src.edn:1`) for scale measurements;
- Datahike fork pin `0e8601d7f2f68c01070e13a95483bc82be04cabc`
  (`0.8.1732-97-g0e8601d7`), with upstream fetched through
  `09c3b27914db1a2e7531fbf0822330cf2ace6e1e` (`0.8.1770`);
- Scriptum `0.1.27`, the optional version selected at
  `reference-code/datahike/deps.edn:101`;
- Datahike's primary persistent-set and Konserve index owners at
  `reference-code/datahike/src/datahike/index/persistent_set.cljc` and
  `reference-code/datahike/src/datahike/index/interface.cljc`;
- first-party graph construction and query idioms at `src/seon/fn.clj`, the
  database namespace at `src/seon/db.clj`, the declarations under
  `resources/seon/schemas/`, and SCI acquisition at
  `src/seon/sci/eval.clj`; and
- the JobTech Taxonomy application at commit
  `19a5868d096e9ad174240c32ed50707b9c86d2eb`, read from its linked upstream
  [GitLab repository](https://gitlab.com/arbetsformedlingen/taxonomy-dev/backend/jobtech-taxonomy-api).

No embeddings or vector-index path is considered. That is an explicit product
constraint, and the current code/data churn makes a derived lexical structure
more honest than an asynchronously refreshed semantic representation.

## What Datahike offers at our pin

### There is no Datomic-style `:db/fulltext`

The schema validator's allowed attribute set includes `:db/index` and
`:db.secondary/only`, and the separate secondary-index declaration keys, but
not `:db/fulltext`
(`reference-code/datahike/src/datahike/schema.cljc:65-78`,
`reference-code/datahike/src/datahike/schema.cljc:185-187`). A transaction that
tries to declare legacy `:db/fulltext` is therefore not an available path in
this pin.

There are two different index facilities that must not be conflated:

1. The ordinary EAVT/AEVT/AVET persistent sorted-set indices.
2. The experimental `:db.secondary/*` protocol, including Scriptum.

### What ordinary `:db/index` enables

The primary indices are ordered as EAVT, AEVT, and AVET
(`reference-code/datahike/src/datahike/index/persistent_set.cljc:30-33`).
Datahike's search strategy chooses AVET only when attribute and value are bound
and the attribute is indexed; otherwise it chooses AEVT and filters the value
(`reference-code/datahike/src/datahike/db/search.cljc:138-157`). Thus:

- exact value lookup on an indexed attribute is a logarithmic seek plus the
  matching run;
- range search is a logarithmic seek plus returned matches;
- prefix search over a string identity is a range search using a lower and
  exclusive upper string bound; and
- substring, suffix, fuzzy, token, and relevance search receive no help from
  the B-tree unless another leading constraint first makes the candidate set
  small.

Insertion explicitly uses a persistent-set lookup with an `O(log n)` comment
(`reference-code/datahike/src/datahike/index/persistent_set.cljc:130-139`).
`index-range` refuses an attribute that is not indexed, then slices AVET from
`[attribute start]` to `[attribute end]`
(`reference-code/datahike/src/datahike/db.cljc:281-298`). The index protocol
also exposes `O(log n)` counted ranges when subtree counts exist
(`reference-code/datahike/src/datahike/index/interface.cljc:14-18`).

`datoms` is an exact bounded slice. `seek-datoms` starts at a lower-bound
pattern and continues to the end of the selected index
(`reference-code/datahike/src/datahike/db.cljc:246-263`). On the Konserve-backed
persistent set, the iterator restores the seek path and then only the nodes it
consumes; the reverse implementation documents this explicitly at
`reference-code/datahike/src/datahike/index/persistent_set.cljc:185-194`.
Consequently:

- `datoms` is the right primitive when the full leading prefix is known;
- `index-range` is the right primitive for an indexed attribute's bounded
  values;
- `seek-datoms` is useful for prefix/autocomplete only if the caller applies a
  bounded `take-while` immediately; realizing it without that bound means
  reading the rest of the index; and
- none of the three implements substring search.

Seon's database namespace currently exposes eager ordinary `datoms`
(`src/seon/db.clj:730-754`) and Datalog `q` (`src/seon/db.clj:539-591`), but it
does not yet expose Datahike's `seek-datoms` or `index-range`. Prefix search can
initially issue an equivalent bounded Datalog query, but faithfully exposing
those two Datahike reads through `seon.db` would be the direct reusable
primitive rather than inventing a private cursor API.

### Scriptum full-text is in the pin, but optional and external

The pin contains a functional, explicitly experimental secondary-index
protocol. Its documentation lists Scriptum as Lucene full-text search and says
all integrations are optional dependencies
(`reference-code/datahike/doc/secondary-indices.md:1-19`). The protocol calls
`-transact` synchronously for every covered datom
(`reference-code/datahike/src/datahike/index/secondary.cljc:15-46`). Scriptum
supports text or multi-field queries, a default limit of 1,000, entity bitmap
results, and relevance-ordered slices
(`reference-code/datahike/src-secondary/datahike/index/secondary/scriptum.clj:18-94`).

That source is present but not usable in Seon's current runtime classpath.
Scriptum appears only in Datahike's `:test` alias, while Seon's `deps.edn` does
not select it. Requiring
`datahike.index.secondary.scriptum` in `clojure -M:dev` failed because
`scriptum/core` was absent. Activating it would add Scriptum and Lucene rather
than reveal a capability Seon already ships.

The storage boundary is decisive:

- Scriptum commits and restores `{:path ... :branch ...}` while managing its
  own Lucene files, not the supplied Konserve store
  (`reference-code/datahike/src-secondary/datahike/index/secondary/scriptum.clj:98-126`).
- Datahike's own documentation says the segments live on the writer's local
  filesystem, are invisible to `d/gc-storage`, retain deleted bytes until a
  Lucene segment merge, and cannot be read by distributed readers
  (`reference-code/datahike/doc/secondary-indices.md:313-365`).
- A detached or historical Scriptum key map cannot be forked at the selected
  generation; the adapter refuses because opening the path would silently use
  the latest head
  (`reference-code/datahike/src-secondary/datahike/index/secondary/scriptum.clj:194-208`).

The current head can be transactionally current because the writer updates the
secondary synchronously and flushes it with the commit. That is weaker than
the required contract. A database value alone does not contain the search
structure, and an arbitrary historical/as-of database value does not identify
a safely openable Lucene generation. Search correctness therefore depends on
external path, branch, lock, backfill status, and file lifecycle in addition to
the supplied database value.

There are implementation risks beyond storage. A retraction deletes every
Lucene document for the entity ID, not merely the retracted attribute's
document (`reference-code/datahike/src-secondary/datahike/index/secondary/scriptum.clj:162-180`).
That may be repaired by other datoms in the same transaction, but it deserves
a falsifier before using multi-attribute full-text search.

### Upstream delta sweep

The secondary-index work entered Datahike in commit `ebbd623a` and release
`0.8.1664`; it is already in our pin. There is no missing upstream
`:db/fulltext` implementation to cherry-pick.

Upstream main is 38 commits beyond the fork's merge history at audit time.
Relevant later safety/query work includes:

- `3342c643` / `0.8.1734`: schema and search-cache correctness fixes;
- `fabf4b41` / `0.8.1746`: external-engine query specifications;
- `feac51e3`: do not answer from a secondary index while it is building; and
- `e17c70a0` / `0.8.1763`: refuse aggregate pushdown when the secondary path
  lacks equivalent meaning.

Those are adoptable secondary-index hardening, not a hidden Lucene feature.
If Seon ever adopts Scriptum, it should first forward-port the applicable
correctness commits and test them against this fork.

The upstream branch `upstream/feat/scriptum-konserve-gc` contains one relevant
unmerged commit, `b1abf55b`. It adds a declaration of whether a secondary index
is externally or Konserve backed and deliberately refuses GC for a hypothetical
Konserve-backed Scriptum index whose marking is unimplemented. Its own message
says the Konserve switch has not landed. It is a useful fail-loud guard, not a
Konserve `Directory`, segment replication, or a one-store solution.

## Resolving the README's GraphQL/Lucene claim

Datahike's README says “GraphQL interface with Apache Lucene full-text search”
under the Swedish Public Employment Service production example and links the
JobTech source directly (`reference-code/datahike/README.md:195-212`). Git
blame resolves the wording to Datahike commit `f62c6a74` on 2026-01-17, first
released in Datahike `0.7.1629`.

It is not `datahike-server`. The separate
[Datahike Server repository](https://github.com/replikativ/datahike-server) was
archived at commit `66493a2e` on 2023-11-12, depends on Datahike `0.5.1506`, and
contains no Lucene, Scriptum, full-text, or GraphQL source/dependency.

The linked JobTech application is the exact source of the claim:

- GraphQL is a Lacinia dependency and its own route
  (`deps.edn:9-10` in that repository and
  `src/clj/jobtech_taxonomy/api/routes/graphql.clj:1-14`).
- Lucene is an independent `lucene-suggest` dependency
  (`deps.edn:22`) introduced by application commit
  `c964bfd81215738dc3c1cbe00bbd85835a07ce61` on 2022-03-01, “Use lucene for
  autocomplete endpoint.” The repository does not attach a semantic release
  tag to that change; its deployment/environment tags are the only containing
  tags.
- The Lucene code creates a temporary `MMapDirectory` and deletes it at JVM
  shutdown (`src/clj/jobtech_taxonomy/api/db/search.clj:17-46`). It queries all
  concept documents from one Datahike database value, builds an
  `AnalyzingInfixSuggester`, and caches it by database identity/basis
  (`src/clj/jobtech_taxonomy/api/db/search.clj:85-107`,
  `src/clj/jobtech_taxonomy/api/db/database_connection.clj:29-44`).
- Search is served from the separate `/suggesters/autocomplete` REST endpoint,
  not through the GraphQL execution path
  (`src/clj/jobtech_taxonomy/api/routes/services.clj:133-145`). Results default
  to 100 (`src/clj/jobtech_taxonomy/api/db/search.clj:130-148`).

So the JobTech technique is separable from GraphQL and closer to Seon's desired
derivation rule than Scriptum: it rebuilds from a database value and keys the
cache by that value's database identity and max transaction. It still creates
a second filesystem index, pays a whole-dataset Lucene build for each uncached
basis, uses mmap/native resources, and needs cleanup and cache eviction. It is
an application autocomplete implementation, not a Datahike engine feature.

## What Seon already has

### Graph facts and first-party queries

The program graph is already a search index in the database:

- Function rows store exact symbol, namespace ref, exact source, optional
  docstring, arglists/spec/arities, call refs, and literal qualified keyword
  values (`resources/seon/schemas/seon.fn.edn:1-30`).
- Namespace rows store identity name, exact source, optional docstring, and
  require/alias/refer/import connections
  (`resources/seon/schemas/seon.ns.edn:1-23`).
- Schema rows store identity key and canonical form as ordinary values
  (`resources/seon/schemas/seon.schema.edn:1-13`).
- Test rows store identity symbol, exact source, call/keyword edges, namespace,
  and optional subject (`resources/seon/schemas/seon.test.edn:1-15`).

`src/seon/fn.clj:183-207` builds namespace facts from analyzer output;
`src/seon/fn.clj:219-237` builds first-party call edges; and
`src/seon/fn.clj:239-262` resolves analyzer-produced qualified keyword
references while discarding unqualified option/destructuring noise. Function
and test rows are assembled at `src/seon/fn.clj:292-347`.

Existing public searches already demonstrate the idiom:

- `tests-reaching` follows declared graph edges with Datalog rules
  (`src/seon/fn.clj:415-431`).
- `functions-using` asks for every function whose indexed source literally
  names a qualified keyword (`src/seon/fn.clj:433-451`).

Commit `bd4494239` proved publication of 2,213 rows and 14,083 keyword edges.
The later published graph measured here contains 14,054 keyword datoms. These
edges answer questions text search cannot answer reliably: “which declarations
name `:seon.db/database-value`?”, “which functions mention this schema key?”,
and, joined through namespace/function/schema refs, “which declarations in
this namespace subtree touch this declared attribute?” Their limitation is
honest: dynamically constructed and unqualified keywords are absent, and a
literal edge does not assert read versus write.

### `dir`, `doc`, and `seon.db`

The bootstrap's bare `dir` and `doc` delegate to Clojure REPL operations
(`src/seon/bootstrap.clj` at committed HEAD, lines 53-61), and the SCI context
installs them in every namespace (`src/seon/sci/eval.clj:217-232`). During
acquisition, `doc` is replaced with a public-function documentation projection
derived from exactly one database value (`src/seon/sci/eval.clj:823-866`).

These are good exact lookup/browse tools after an agent knows the namespace or
symbol. They do not discover a symbol from a concept, substring, schema key, or
unknown namespace. `seon.db/q` and `seon.db/datoms` provide the general fishing
gear, but asking an agent to guess the right graph attributes and joins is not
a search API.

### Indexed datoms versus opaque blobs

All current search-relevant program facts are ordinary datom values, not
external blobs:

| Fact | Present as a datom | AVET indexed today | Consequence |
|---|---:|---:|---|
| Function symbol | yes | yes, identity | exact and prefix range |
| Namespace name | yes | yes, identity | exact and prefix range |
| Schema key | yes | yes, identity | exact and prefix range |
| Test symbol | yes | yes, identity | exact and prefix range |
| Function/namespace docstring | yes | no | AEVT slice then predicate |
| Function/namespace/test source | yes | no | AEVT slice then predicate |
| Schema canonical form | yes | no | AEVT slice then predicate |
| Function/test keyword edge | yes, cardinality many | no | exact AEVT value filter/join |
| Function/test call edge | yes, refs | reference lookup path | graph traversal/join |

Identity and reference declarations cause ordinary Datahike indexing even
where the source Malli property does not spell `:db/index`. The large program
source strings are inline primary-index values today. This is not necessarily
the ideal storage design for unrelated reasons, but search does not currently
need to fetch a blob tier to inspect them.

## Honest current scale and measurements

The read-only probe used the already-open `current-src` connection and its
immutable database value. It did not transact, refork, or close another lane's
connection. Counts are datom counts, avoiding Datalog's distinct tuple
semantics:

| Published fact | Count | Text characters |
|---|---:|---:|
| Functions / function source | 2,160 | 1,574,560 |
| Function docstrings | 868 | 197,836 |
| Namespaces | 259 | — |
| Namespace source | 185 | 191,538 |
| Namespace docstrings | 146 | 117,086 |
| Schema keys/forms | 1,424 | 185,727 |
| Tests / test source | 894 | 1,222,094 |
| Literal keyword edges | 14,054 | — |

Hardware was this development machine and the live JDK 26 process. Each scan
was warmed 20 times, then measured for 200 fully realized executions. These
numbers include obtaining the Datahike AEVT/AVET slice but not transport or
rendering:

| Operation | Matches | p50 | p95 |
|---|---:|---:|---:|
| Case-insensitive `"database"` in all function docs | 86 | 0.325 ms | 0.429 ms |
| Case-insensitive `"database"` in all function source | 248 | 2.306 ms | 2.651 ms |
| Exact `:seon.db/database-value` over all keyword datoms | 1 | 0.169 ms | 0.208 ms |
| AVET prefix range `"seon.db/"` over function symbols | 58 | 0.0038 ms | 0.0068 ms |

The doc/source/keyword numbers execute the same AEVT slice and predicate that
a well-planned scoped Datalog query needs; they are not end-to-end `d/q`
numbers. A direct `d/q` probe was unavailable because the live process returned
`ClassNotFoundException: datahike.query$raw_q_mode$fn__51013`. That exact
boundary is recorded rather than attributing it to another lane without a
confirming probe. Given the measured primitive costs and these row counts, an
end-to-end scoped Datalog search is reasonably estimated at low single-digit
milliseconds, but must be remeasured through `seon.db/q` after that live-class
failure clears.

A simple immutable trigram prototype indexed function symbols/docs, namespace
names/docs, schema keys, and test symbols:

- 5,751 field rows and 487,599 input characters;
- 9,434 distinct trigrams and 355,185 row postings;
- build: 124.97 ms p50 / 143.70 ms p95 over 15 builds; and
- verified substring query: 0.044 ms p50 / 0.054 ms p95 over 500 queries.

This prototype deliberately excluded source text. Source-only scoped scanning
is already about 2.3 ms for functions; putting all source in a trigram map
would greatly increase postings and heap for little current benefit.

## Concrete API options

Every option below uses the same external contract. Search has no unscoped
default. The caller must specify:

```clojure
{:seon.search/query "database value"
 :seon.search/scopes #{:seon.search/functions}
 :seon.search/fields #{:seon.fn/sym :seon.fn/doc}
 :seon.search/namespace 'seon.db
 :seon.search/namespace-subtree? true
 :seon.search/match :substring
 :seon.search/limit 12}
```

Scopes should be declared values for functions, namespaces, schemas, tests,
and data. Program scopes translate to known graph attributes. Data search must
also name one or more installed attributes; it must never guess attribute
names or scan every string in the cluster by default. Namespace scoping means
the exact namespace plus its dot-delimited descendants, not a naming-based
classification of behavior.

The limit is required and additionally bounded by the existing presentation
budget at the caller boundary. A result is ordinary concise data: database
value identity/basis, entity identity, matched field, match reason, and one
bounded excerpt. Source bodies and whole entities are follow-up `pull`/`doc`
operations, not search results. Ordering is deterministic: exact before prefix
before substring, then shorter/name matches, then stable identity. No claimed
relevance score should be fabricated when the engine has none.

### Option 1 — structural and AVET search only

**Guarantee.** Exact answers for declared edges and values, plus prefix/range
search on indexed identities. Results derive solely from the supplied database
value and are correct for its basis. This makes the 14,054 keyword datoms and
call/schema connections directly discoverable.

**Cost and latency.** Minimal code over `seon.db/q`, `datoms`, and exposed
`index-range`; measured exact-edge scan 0.17 ms p50 and AVET prefix 0.004 ms
p50 before result projection.

**Risk / capability given up.** It cannot find a concept that appears only in
a docstring or source substring. On its own this is better navigation, not a
complete search capability.

### Option 2 — scoped Datalog/predicate scan, combined with Option 1

**Guarantee.** Adds case-normalized exact/prefix/substring matching over only
the explicitly selected program/data attributes. The database value remains
the only authority; no cache or side index can drift. Structural constraints
run before text predicates wherever the query planner can bind the candidate
entity/attribute first.

**Cost and latency.** No dependency and no index-build cost. Measured primitive
cost is 0.32 ms p50 for all function docs and 2.31 ms p50 for all function
source; estimated end-to-end is low single-digit milliseconds at current
scale. Returning only a requested limit keeps projection/render cost bounded.

**Risk / capability given up.** Predicate search is linear in the selected
text and has no stemming, typo tolerance, BM25, or phrase ranking. An
accidentally broad data scope could be expensive, which is why scope, fields,
attributes for data, and limit are mandatory. This is the recommended first
implementation.

### Option 3 — basis-keyed derived in-memory token/trigram index

**Guarantee.** Build the immutable index only from the supplied database value.
If cached, key it by exact committed database-value identity, never by branch
name or “latest”; a miss rebuilds, and eviction loses only speed. The result is
always reverified against the row text, so trigrams create candidates rather
than truth. There is no persisted second store.

**Cost and latency.** The measured names/docs prototype builds in about 125 ms,
contains 355,185 postings, and answers the sample in about 0.05 ms. Retained
heap was not measured; ordinary persistent maps/sets make several to tens of
megabytes plausible, so heap must be measured before caching many bases.

**Risk / capability given up.** More code, heap, cache lifecycle, and basis-key
correctness for a saving of roughly 0.3–2.3 ms at today's scale. Indexing source
would magnify build and memory cost. This is a clean optimization once recorded
search traffic proves scans material, not the starting point.

### Option 4 — Lucene, via Scriptum or a JobTech-style derived suggester

**Guarantee.** Lucene offers real analyzers, multi-field/token/phrase queries,
prefix autocomplete, and relevance ranking. JobTech's model can rebuild from a
database value; Scriptum can maintain the current writer head synchronously.

**Cost and latency.** Scriptum `0.1.27` selects Lucene `10.3.2`; the locally
resolved Lucene jars total about 7.6 MiB before Scriptum/Yggdrasil/Jackson,
native/mmap pages, heap, and index files. Scriptum adds work for every covered
datom and commits Lucene on database flush. No representative Seon Lucene build
or query latency was measured because the dependency is not shipped; claiming
a number would be fiction. At only 5,751 name/doc fields, Lucene's operational
constant dominates the few milliseconds it could save.

**Risk / capability given up.** Both integrations add a second filesystem
index. Scriptum also adds backfill states, writer-local search, branch locks,
segment GC/merge, and weak detached/historical-basis semantics. JobTech avoids
transaction maintenance but rebuilds a mmap index per uncached database basis.
Neither fits the one-Konserve-store, database-value-only guarantee today.
Adopting Lucene would buy ranking and language analysis while giving up the
simplest freshness and recovery model. It is honestly capable and honestly the
wrong trade at current scale.

## Recommendation

Implement one scoped search function using Option 1 plus Option 2. Make the
scope declaration the teaching device:

1. Require program scope or data attributes, fields, match mode, and limit.
2. Route exact keyword/call/schema questions to declared Datalog joins.
3. Route identity prefix to AVET range reads.
4. Route doc/source/form substring to an AEVT slice after namespace/entity
   constraints, then a normal string predicate.
5. Return concise identities, match fields/reasons, and bounded excerpts so the
   agent follows with `doc`, `pull`, `functions-using`, or a narrower search.

This is “teach the agent to fish” because the API teaches the agent to name
where it is looking instead of asking for a giant global answer. It also makes
wrong searches visible: schemas-only, docstrings-only, namespace-subtree, and
explicit data attributes are semantic input, not ranking hints.

Keep the derived trigram structure as the next optimization boundary. Add it
only when actual scoped scan latency or concurrency is material, rebuild it
from each exact database value, and treat a cache entry as disposable. Do not
adopt Scriptum/Lucene until a requirement for stemming, typo tolerance, phrase
ranking, or a much larger text population outweighs the second-store cost. If
that day arrives, first adopt/falsify the upstream secondary-index correctness
deltas and require exact historical-basis, branch, GC, and recovery proofs.

## Tool and render feedback

- The live MCP value renderer correctly kept the large database probe bounded
  and made the final count/latency maps readable. This was substantially better
  than printing thousands of datoms.
- `seon.db/commit-id` without an ambient connection returned a clear flat
  `:seon.db/missing-connection-binding` error. The published artifact supplied
  the current source commit ID without mutating the live cluster.
- Direct Datalog measurement failed with only
  `ClassNotFoundException: datahike.query$raw_q_mode$fn__51013`. That is ugly
  diagnostic output: it exposes a generated class name but not the missing
  classloader/source boundary or a useful recovery action. This report records
  the exact limit and labels Datalog latency as estimated rather than silently
  substituting a claim.
- An earlier aggregate probe similarly surfaced a generated
  `datahike/query$post_process_result$fn__...` class error. Generated class
  names should be accompanied by the owning query form and causal classloader
  evidence in the agent-facing error projection.
