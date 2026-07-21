---
type: research
status: completed
tags: [research, database, agent]
---

# Human-readable ids: package, Datahike, and tokenizer evidence

## TL;DR

Datahike has no fixed-width preference and no built-in maximum for string
identity values. It compares indexed strings lexicographically, and its
persistent sorted-set index splits by entry count rather than serialized byte
size. A joined-word agent id is ordinary indexed string data. It costs more
storage and cache memory in direct proportion to the larger payload, but it
does not create a worse index shape.

Use maintained generators instead of making Seon own a vocabulary, alphabet,
or random sampler. The scope is unconditional:

| Identity policy | Persistent identity scope | CLJS package | JVM package | Example grammar |
|---|---|---|---|---|
| Human-readable | Only newly allocated `:seon.agent/id` | npm `human-id` 4.2.0 | Maven `com.github.kkuegler:human-readable-ids-java` 0.4 | `dry-jokes-hunt` / `funny-firefox-48` |
| Compact | Every other generated identity | npm `@paralleldrive/cuid2` 3.3.0 | Maven `io.github.thibaultmeyer:cuid` 2.0.5 | `q66ljwup2b5r` |
| Reserved | The literal root agent | none | none | `root` |

“Every other” includes plan, message, run, turn, eval, schedule, and future
generated persistent identity attributes. Stable configuration literals
are reconciled known ids rather than allocated ids. External protocol ids are
stored as protocol facts; they are not an exception that mints another Seon
identity grammar.

The two runtimes do not need to emit the same values. They need the same Seon
operation, return shape, syntax guarantees, and database collision behavior.
All four package adapters belong behind one canonical `seon.db.id` namespace
in one `.cljc` file. Put the JVM dependencies in `deps.edn` and the Node
dependencies in `package.json`; do not duplicate npm dependency declaration in
`shadow-cljs.edn`. Store the package output directly: do not prepend an
identity-kind or generator marker. Word output has two hyphens, the
legacy timestamp form has one, and the compact grammar has none.

There must not be a public function that merely returns a candidate. Replace
the current pre-commit `seon.db/new-id!` model with one public, fully specified
`seon.db.id/allocate!` operation that allocates one or more ids as part of the
caller's atomic domain transaction. A package proposes a candidate; the sole
database writer is the collision authority; the operation returns an id only
after the transaction commits.

Owner integration tightened the collision rule after this package/index study:
within one logical database/branch, the serialized writer also rejects a
generated value already present under any registered generator-managed identity
attribute. It derives that small attribute set from schema metadata and uses
indexed AVET lookups; it does not store a global-id entity. Datahike's native
constraint remains per attribute, while Seon's one allocator supplies the
requested cross-generated-attribute uniqueness.

That distinction is essential because Datahike intentionally treats an
existing `:db.unique/identity` in an entity map or tempid assertion as an
upsert. The current `fresh?` query followed by an entity-map transaction is
not a safe allocator. The writer must use a concrete fresh eid and an explicit
identity assertion so a collision becomes `:transact/unique`, then retry the
whole uncommitted domain transaction with another package candidate. Retry is
bounded to sixteen generation rounds and only applies when structured
Datahike error attribute/value fields exactly match a generated candidate.
Every agent retry reruns a pure transaction builder so home namespaces,
symbols, refs, and nested values all follow the replacement id. Unrelated
unique conflicts return immediately.

The word form is also acceptable in model context. Across 10,000 deterministic
samples, npm `human-id` averaged 5.34 `o200k_base` tokens, 5.63 public
DeepSeek-V3-tokenizer tokens, and 5.87 DiffusionGemma tokens. The current
timestamp id averaged 7.06, 7.12, and 13.01 respectively. Words are not
universally single tokens, but the short three-part package output is at least
as token-efficient in the measured model families while being far easier for
people to recognize.

Do not store a date in the new id. Creation time is already the earliest
identity-assertion transaction and its `:db/txInstant`. Project and render that
fact when useful.

## Settled package decision

Human-readable allocation is an exception made solely for `:seon.agent/id`
because people recognize and discuss agents. No other generated identity gets
word output. The `root` value is reserved and enters through known-id
reconciliation, never either package adapter.

### CLJS: `human-id` 4.2.0

Use [`human-id`](https://www.npmjs.com/package/human-id) with:

- one adjective, one noun, and one verb;
- `"-"` as the package separator;
- lowercase output.

The exact npm version observed on 2026-07-12 is 4.2.0 with integrity:

```text
sha512-K3GbkIWqyvvlpfhBPlbEvD97TtqBpAYA4kt+cn2lD2x2HuohzZCibcA2nOlnJT6exqvJLggoB5nv2dNf192nEA==
```

Why this is the primary choice:

- MIT license;
- zero runtime dependencies;
- package owns both the vocabulary and generation;
- 13 npm releases since 2018;
- latest release published 2026-06-04;
- 15,139,374 npm downloads in the measured month ending 2026-07-11;
- active, non-archived repository with 240 stars at measurement time;
- exact default pools are 200 adjectives, 300 nouns, and 250 verbs, with no
  duplicates inside a positional pool and 15,000,000 possible tuples.

The pinned source shows the pools and generator directly
([source](https://github.com/RienNeVaPlus/human-id/blob/f22412ac6d18c29df74f802e7479aae391204ea4/index.ts)).
It uses `Math.random` and does not expose an RNG injection hook. That is
acceptable here: this id is a recognizable database identity, not a security
token, and database uniqueness plus retry is the correctness mechanism.

The package describes its vocabulary as family-friendly, but its policy is
not equivalent to “only cheerful words”; examples in the current lists include
`beers`, `attack`, and `sin`. Seon must not silently filter or fork that list.
If the package's vocabulary policy becomes unacceptable, change packages as a
reviewed dependency decision. Runtime filtering would make the effective space
and distribution undocumented and would recreate vocabulary ownership inside
Seon.

### JVM: `human-readable-ids-java` 0.4

Use Maven
[`com.github.kkuegler:human-readable-ids-java:0.4`](https://central.sonatype.com/artifact/com.github.kkuegler/human-readable-ids-java/0.4)
through its `RandomHumanReadableIdGenerator`. Its package-owned form is
adjective, animal, and a number from 0 through 99.

Why this is the best practical published JVM fit from the surveyed packages:

- Apache-2.0 license;
- zero runtime dependencies;
- package owns vocabulary and generation;
- five Maven Central releases since 2019;
- release 0.4 was published 2024-02-23;
- default `SecureRandom` source, although cryptographic randomness is not a
  Seon requirement;
- lowercase ASCII package dictionaries with 131 adjectives and 102 animals;
- 1,336,200 possible outputs.

The exact release source is tagged at
[`v0.4`](https://github.com/kkuegler/human-readable-ids-java/tree/19fac8626426f30728dd214c28a950c94038f2f6).
The package is materially smaller and less adopted than npm `human-id`: the
repository had 8 stars and 10 forks at measurement time. It is suitable for
operator-scale identities only, with mandatory writer retry; it is not a
high-volume id space.

Prefer `RandomHumanReadableIdGenerator` to the package's
`PermutationBasedHumanReadableIdGenerator`. The permutation implementation
keeps process-local mutable queues to reduce immediate component reuse. That
state disappears on restart and is not database uniqueness. A uniform package
candidate plus one database retry rule is simpler to reason about.

The JVM vocabulary also contains negative adjectives. The same dependency
ownership rule applies: accept the package's output policy or replace the
package; do not maintain a Seon-side exclusion list.

### Why the runtime outputs may differ

Cross-platform byte-for-byte generation would require one of three bad
tradeoffs: vendoring a common word list, reimplementing one package on the
other platform, or routing all candidate generation through a new service.
None improves a database identity.

The invariant belongs at the Seon boundary:

- the public operation is `seon.db.id/allocate!` on both platforms;
- its request and response are the same named Malli schemas;
- allocation declarations name the fully namespaced identity attribute;
- registered identity-attribute schema metadata selects the private package
  adapter, so a caller cannot choose the wrong generator;
- newly allocated agent ids contain two hyphens and use only lowercase ASCII letters,
  digits, and hyphens;
- valid ids remain safe in URLs and ClojureScript namespace segments;
- the database writer detects and retries collisions;
- existing ids remain valid after a package upgrade or runtime move.

The package adapter is a private reader-conditional detail inside
`src/seon/db/id.cljc`. There is no public CLJS generator beside a public JVM
generator, and no `.cljs`/`.clj` sibling pair.

## One collision-safe API, not two stages

### Canonical owner

The implementation owner should be:

```text
src/seon/db/id.cljc  ->  seon.db.id
```

Its reader-conditional adapter table is fixed:

| Registered `:seon.db.id/generator` | Allowed identity attributes | CLJS private adapter | JVM private adapter |
|---|---|---|---|
| `:seon.db.id.generator/human-readable` | `:seon.agent/id` only | `human-id` 4.2.0 | `human-readable-ids-java` 0.4 |
| `:seon.db.id.generator/compact` | Every other allocated persistent identity attribute | `@paralleldrive/cuid2` 3.3.0 | `io.github.thibaultmeyer:cuid` 2.0.5 |

Schema registration must reject human-readable generator metadata on any
attribute other than `:seon.agent/id`. The allocator must reject a generated
identity attribute with missing or conflicting metadata. It must not silently
default to the human-readable adapter. Root has no generator registration.

That file owns:

- named Malli request and response schemas;
- syntax schemas for legacy, word, and compact ids;
- resolution from registered identity-attribute metadata to a private
  generator adapter;
- the single public allocation operation;
- private reader-conditional package adapters;
- collision classification and retry policy;
- complete candidate-dependent transaction building and rebuilding.

It should depend downward on the existing database commit internals. Do not
make `seon.db.id` require a facade that then re-exports `seon.db.id`, creating a
cycle. Callers require the canonical namespace directly. Removing
`seon.db/new-id!` rather than retaining a compatibility alias keeps one way to
create new identities.

### Operation semantics

The final schema names and builder representation can be settled in the
implementation PRD. The semantic request must carry:

- one or more fully namespaced allocation declarations;
- the fully namespaced identity attribute for each allocation;
- a fully namespaced allocation key for each result;
- one fully specified pure transaction-builder function that receives the
  candidate map and returns the complete domain transaction request, including
  transaction metadata.

The request must not carry any word/compact generator selector.
`seon.db.id/allocate!` looks up the identity attribute's registered schema
metadata and selects the private adapter owned by that attribute. An
unregistered identity attribute, an attribute without allocation policy, or
conflicting policy metadata is a schema/configuration error before commit.
This makes it impossible for a caller to mint a compact `:seon.agent/id` or a
word-shaped message id.

The metadata itself must be fully namespaced and specified in `seon.db.id`.
For example, `:seon.db.id/generator` can identify a private adapter with a
fully namespaced value such as `:seon.db.id.generator/human-readable`. It is a
property of the registered identity-attribute schema, alongside
`:seon.db/identity`; it is never copied onto a domain entity or repeated in an
allocation call.

The builder executes locally inside `seon.db.id/allocate!`; it never crosses
the wire. Its input maps allocation keys to candidates, and its output uses the
normal fully namespaced `seon.db/transact!` request shape. It must be pure over
the candidate map and immutable caller inputs because a collision can invoke
it again. Capture a required instant or other stable fact before allocation;
do not read time, perform I/O, or mutate state inside the retryable builder.

The response returns:

- only ids that committed;
- the corresponding eids or lookup refs needed by the caller;
- the normal compact transaction result;
- the same error-as-value contract as `seon.db/transact!`.

Supporting several declarations matters. Current message creation can create a
message id and multiple plan ids in one transaction, and run creation combines
a new run identity with CAS references. A one-at-a-time allocator would either
break that atomicity or create reservation entities. Both are worse than a
single allocation-aware transaction.

### Rebuild candidate-dependent data, do not replace strings

Every retry discards the complete transaction request built for the rejected
candidate and invokes the transaction builder again. String substitution is
not sufficient. An agent id can determine:

- the `:seon.agent/id` identity assertion;
- the `my.agent.<id>` home namespace symbol;
- candidate-derived symbols, keywords, lookup refs, routes, source forms, and
  nested values;
- the response's agent id and derived namespace.

If `dry-jokes-hunt` collides and the next candidate is
`calm-otters-build`, no datom, symbol, source string, or returned value may
still contain the rejected candidate. The failed transaction committed
nothing; the newly built transaction must be internally consistent on its own.

### Compose with the normal writer path

Do not add a second `"allocate"` RPC. Extend the normal transaction request
with allocation declarations and keep the existing write path:

1. `seon.db.id/allocate!` privately asks the platform package for candidate
   values according to each registered identity attribute.
2. It calls the pure transaction builder with that complete candidate map.
3. The normal wire transaction carries the resulting domain facts,
   transaction metadata, the generated-candidate manifest, and its existing
   write id.
4. The JVM sole writer assigns concrete fresh eids and expands identity
   creation into explicit assertions before calling Datahike.
5. A matching generated-candidate collision discards the entire uncommitted
   attempt, obtains replacement candidates, rebuilds the complete transaction,
   and retries within the fixed attempt bound.
6. Any other validation, schema, or transaction failure is returned normally;
   it is not mislabeled as a collision.
7. The operation returns ids only after the writer acknowledges the commit and
   the pod's read-your-own-write fence reaches that basis.

This reuses the existing machinery in `SeonWireWriter`: write-id commit
ambiguity resolution, read-your-own-write materialization, transaction
reports, and listeners (`src/seon/store/wire.cljs:346-418`). If the reply is
lost after commit, the write-id check must return the already committed ids;
it must not generate a second entity.

The candidate generator does not need to be globally serialized. The commit
does. Several callers may propose the same value; the sole writer establishes
which transaction owns it and makes every other attempt retry.

### Retry only the generated candidate conflict

Immediately before Datahike, the serialized writer queries each candidate
against every registered generator-managed identity attr and compares
candidates within the request. An exact hit is a retryable generated-candidate
conflict even when it belongs to a different generated identity attr. This
preflight is inside the serialized writer boundary, so no other commit can land
between the check and transaction.

Datahike reports a concrete-eid uniqueness failure with structured exception
data containing `:error :transact/unique`, the conflicting `:attribute`, and
the attempted `:datom`
([source](https://github.com/seantempesta/datahike/blob/6e2d9beeb5002ba025e2f3aa69cd9111afd7abf8/src/datahike/db/transaction.cljc#L460-L467)).
The allocation attempt already has a manifest of every generated
`[identity-attribute candidate]` pair. A Datahike failure is retryable only when:

- its Datahike error is exactly `:transact/unique`;
- the writer resolves its attribute to the same fully namespaced identity
  attribute in the manifest;
- the failed datom's value exactly equals that allocation's candidate.

A uniqueness conflict on any other domain attribute is a real caller/domain
failure. Return it unchanged after one attempt. Do not regenerate ids, hide the
conflict, or parse an exception message to guess.

The current wire path stringifies Datahike `ex-data` and preserves only a broad
error kind (`src/seon/server/wire.clj:663-675`,
`src/seon/store/wire.cljs:373-377`). The normal transaction response must gain
a fully namespaced structured projection of the Datahike error kind,
identity-attribute keyword, and attempted value. This remains the existing
transaction RPC; it is not a second allocation protocol.

### Bounded retry and exhaustion

Use a fixed internal bound of 16 candidate-generation rounds per allocation
call. The bound is owned and specified by `seon.db.id`; callers cannot raise it.
Every generation round counts, including a package that repeats a previously
rejected candidate.

Keep rejected `[identity-attribute candidate]` pairs in a local set for the
duration of the call. If a package repeats one, count the round but do not
rebuild or resend a transaction already known to conflict. Also reject two
allocations that propose the same value anywhere in one request before the
writer call.

After 16 unsuccessful rounds, return the normal error-as-value envelope with a
fully namespaced exhaustion reason such as `:seon.db.id.error/exhausted`, the
affected identity attributes, and the attempt count. No domain transaction
committed, no id is returned, and there is no fallback to legacy ids, UUIDs,
longer output, or the other generator policy.

At 100,000 occupied values, even the smaller JVM human-readable space has a
7.48% next-draw conflict probability; under a healthy distribution, sixteen
independent conflicts are below `1e-18`. Reaching exhaustion therefore
indicates a broken/repeating adapter, incorrect conflict classification, or a
pool far outside its intended scale. Failing loudly is safer than an unbounded
loop.

### Allocation and reconciliation are different operations

Configuration needs intentional upsert/reconciliation of known identities so
boot can restore a desired subset to a good state. Human “new agent” creation
needs a genuinely new identity. Do not overload one with the other:

- allocation creates a new entity and must fail/retry on identity collision;
- reconciliation applies desired facts to a known lookup ref and may upsert
  intentionally;
- neither stores a reservation, collision flag, package name, or generator
  policy on the entity.

The stored fact is the identity value. Generator policy and dependency version
are schema/code/configuration facts, not domain attributes and not caller
choices.

## What Datahike actually does

### Strings have no database width class

Datahike's predicate for `:db.type/string` is simply `string?`; it declares no
length cap
([source](https://github.com/seantempesta/datahike/blob/6e2d9beeb5002ba025e2f3aa69cd9111afd7abf8/src/datahike/schema.cljc#L20-L33)).
The current exact-width `:seon.db/id` in `src/seon/schema.cljc:126-135` is a
Seon Malli rule, not a Datahike limitation.

Strict syntax is still useful at the application boundary:

- ids can appear directly in `/agent/{id}`;
- ids can be one segment of `my.agent.<agent-id>`;
- the ClojureScript munge mapping remains injective because valid ids reject
  underscore;
- slashes, dots, colons, Unicode lookalikes, and escaped material are absent;
- legacy, word, compact, `root`, and `proc:` forms can be distinguished.

Do not validate membership in a package word list. That would copy the
dependency's vocabulary into Seon's schema and make preserved ids invalid when
the dependency changes. Validate syntax, then rely on the unique identity
attribute for existence and uniqueness.

### Unique identity means AVET

Datahike expands `:db.unique/identity` to include `:db/index`, so identity
datoms participate in AVET
([source](https://github.com/seantempesta/datahike/blob/6e2d9beeb5002ba025e2f3aa69cd9111afd7abf8/src/datahike/db/utils.cljc#L302-L308)).
AVET compares attribute, value, entity, and transaction in that order
([source](https://github.com/seantempesta/datahike/blob/6e2d9beeb5002ba025e2f3aa69cd9111afd7abf8/src/datahike/datom.cljc#L339-L344)).
String values use ordinary lexicographic comparison
([source](https://github.com/seantempesta/datahike/blob/6e2d9beeb5002ba025e2f3aa69cd9111afd7abf8/src/datahike/datom.cljc#L249-L292)).

There is no hash-id fast path and no fixed-width comparator. The first package
word distributes values naturally. A format marker or duplicated date prefix
would create a common prefix without adding a fact the database lacks.

### The tree splits by entry count

The active persistent-set index uses a default branching factor of 512
([Datahike construction](https://github.com/seantempesta/datahike/blob/6e2d9beeb5002ba025e2f3aa69cd9111afd7abf8/src/datahike/index/persistent_set.cljc#L470-L508)).
The exact persistent-sorted-set dependency partitions by the number of entries
relative to that factor, not by serialized bytes
([source](https://github.com/replikativ/persistent-sorted-set/blob/e1a17bbe767c7801e67407c81f64efabfd2f1601/src-clojure/org/replikativ/persistent_sorted_set.clj#L108-L126),
[bulk construction](https://github.com/replikativ/persistent-sorted-set/blob/e1a17bbe767c7801e67407c81f64efabfd2f1601/src-clojure/org/replikativ/persistent_sorted_set.clj#L186-L245)).

Longer strings therefore enlarge serialized nodes and cache memory linearly;
they do not lower fanout by bytes or intrinsically add tree levels. The store
cache is also bounded by cached entry count, not cached bytes
([source](https://github.com/seantempesta/datahike/blob/6e2d9beeb5002ba025e2f3aa69cd9111afd7abf8/src/datahike/index/persistent_set.cljc#L456-L470)).

### History multiplies the payload

The cluster enables `:keep-history? true`. An indexed identity assertion is
represented in current EAVT, AEVT, and AVET and in the corresponding temporal
indexes. Datahike performs those six index updates directly
([source](https://github.com/seantempesta/datahike/blob/6e2d9beeb5002ba025e2f3aa69cd9111afd7abf8/src/datahike/db/transaction.cljc#L469-L505)).

That is why additional identity payload produces close to six bytes of store
growth per additional ASCII byte before node/serializer effects. It is a good
reason to reserve word ids for agents alone, where people receive the
recognition benefit.

### Entity-map collision is an upsert

Datahike's `upsert-eid` scans AVET and resolves an entity map carrying an
existing identity to the existing eid
([source](https://github.com/seantempesta/datahike/blob/6e2d9beeb5002ba025e2f3aa69cd9111afd7abf8/src/datahike/db/transaction.cljc#L548-L622)).
An explicit `:db/add` with a tempid performs the same identity resolution
([source](https://github.com/seantempesta/datahike/blob/6e2d9beeb5002ba025e2f3aa69cd9111afd7abf8/src/datahike/db/transaction.cljc#L1186-L1196)).

The concrete-eid path differs: asserting a unique value that already belongs
to another eid raises `:transact/unique`
([source](https://github.com/seantempesta/datahike/blob/6e2d9beeb5002ba025e2f3aa69cd9111afd7abf8/src/datahike/db/transaction.cljc#L460-L470)).
The writer-side expansion in the allocation operation must deliberately use
that path.

The present `seon.agent/create!` first queries `fresh?` and then transacts an
entity map (`src/seon/agent.cljs:420-475`). That shape is correct for an
idempotent “ensure this known agent” operation, but not for minting a new random
identity. Splitting those semantics is part of the refactor.

## Measured storage cost

The local measurement used Seon's pinned Datahike fork at
`6e2d9beeb5002ba025e2f3aa69cd9111afd7abf8`, Konserve 0.9.353, file-backed
storage, and history enabled. Each clean database installed one string
`:db.unique/identity` attribute and committed 10,000 unique entities in one
transaction. Package collisions were redrawn before commit so each row had the
same entity and datom population.

| Identity distribution | Store size | Delta from legacy |
|---|---:|---:|
| Current timestamp form | 1.819 MiB | 0 KiB |
| npm `human-id` word form | 1.977 MiB | 162.5 KiB |
| JVM `human-readable-ids-java` form | 1.862 MiB | 43.9 KiB |

The npm word form adds about 0.16 MiB per 10,000 agent identities in this
isolated history-enabled store. That is immaterial for agents. Applying word
ids to every message, eval, turn, and run would provide no operator benefit and
would multiply the cost across much larger populations.

## Collision analysis

### Spaces

| Generator | Space | Entropy |
|---|---:|---:|
| Current same-minute random prefix | 140,608 | 17.10 bits |
| npm `human-id` default | 15,000,000 | 23.84 bits |
| JVM `human-readable-ids-java` default | 1,336,200 | 20.35 bits |

These are recognition spaces, not “collisions are impossible” spaces. The
database retry is required, not optional.

There are two useful probabilities which should not be confused:

- birthday probability: whether any collision occurred while drawing the
  whole population without retry;
- next-draw retry probability: occupied values divided by total values.

| Existing population | npm next draw retries | JVM next draw retries |
|---:|---:|---:|
| 1,000 | 0.00667% | 0.07484% |
| 10,000 | 0.06667% | 0.74839% |
| 100,000 | 0.66667% | 7.48391% |

At 1,000 generated npm ids, the birthday probability that at least one raw
candidate repeated is about 3.28%, but the next allocation retries only about
once per 15,000 candidate draws. At 10,000 committed ids, the expected npm
draw count for the next allocation is 1.00067. Database retry makes a modest
recognition space practical for a modest agent population.

The JVM space is less comfortable: its birthday probability reaches about
31.20% by 1,000 raw draws. Its per-allocation retry cost remains small at agent
scale, but it must never be reused for high-volume records.

Datahike collision uniqueness is scoped to the identity attribute. Seon's
allocator adds cross-generated-attribute uniqueness by querying each candidate
against the registry-derived generator-managed attr set at the serialized
writer. It does not duplicate the same string onto a second unique attribute or
create a universal identity entity. The guarantee is scoped to one logical
database/branch; independently diverged branches are distinguished by their
full coordinates.

## Tokenization measurement

### Method

Each row used 10,000 deterministic samples from seed `20260712`. Counts exclude
special tokens and encode the isolated id exactly as shown. Measured
tokenizers:

- OpenAI `o200k_base` and `cl100k_base` through `tiktoken`;
- public `deepseek-ai/DeepSeek-V3` snapshot
  `e815299b0bcbac849fa540c768ef21845365c9eb`;
- local DiffusionGemma/Gemma snapshot
  `9cbf2942911ed1cde01044b45a220f395c0a2d2a`.

Seon's configured API model is `deepseek-v4-pro`, but no exact public v4-pro
tokenizer artifact was available. The V3 row is a provider-family proxy, not a
billing claim. The `Seon` column uses the integer-floor behavior of the
canonical `seon.ai.tokens/estimate` heuristic.

### Mean tokens per id

| Candidate | Seon | `o200k` | `cl100k` | DeepSeek proxy | DiffusionGemma |
|---|---:|---:|---:|---:|---:|
| Current timestamp form | 3.00 | 7.06 | 7.18 | 7.12 | 13.01 |
| npm `human-id` 4.2.0 | 3.82 | 5.34 | 5.41 | 5.63 | 5.87 |
| JVM `human-readable-ids-java` 0.4 | 3.32 | 5.96 | 5.98 | 6.14 | 6.79 |

The canonical Seon heuristic penalizes the larger word surface because it is
only a coarse size estimate. Actual BPE tokenizers merge familiar word pieces
and often merge a preceding hyphen, while the Gemma tokenizer splits much of
the timestamp form.

For the valid package output `rare-geckos-jam`, tokenizers disagreed but all
remained compact:

```text
o200k:    rare | -ge | ck | os | -j | am
DeepSeek: rare | -ge | ck | os | -j | am
Gemma:    rare | - | ge | ck | os | - | jam
```

The product reason for words remains human recognition. The measurements show
that this does not impose a token penalty in the active package shape; they do
not justify optimizing package vocabulary around one model's merge table.

Do not add tokenizer distributions to the ordinary test suite. Provider
artifacts change and are design evidence, not a stable runtime contract.

### Representative Seon contexts

The same 10,000 samples were then measured inside three exact surfaces:
`[:seon.agent/id "<id>"]`, `my.agent.<id>`, and `/agent/<id>`. Compact rows use
a deterministic sample of the shared lowercase CUID2 grammar. They are a
controlled tokenizer comparison only; compact ids never become agent ids under
the settled policy.

| Surface | Id form | `o200k` | `cl100k` | DeepSeek proxy | DiffusionGemma |
|---|---|---:|---:|---:|---:|
| `[:seon.agent/id "…"]` | Legacy | 14.06 | 14.18 | 15.12 | 22.01 |
| `[:seon.agent/id "…"]` | npm word | 12.34 | 12.41 | 13.63 | 14.87 |
| `[:seon.agent/id "…"]` | JVM word | 12.96 | 12.98 | 14.14 | 15.79 |
| `[:seon.agent/id "…"]` | Compact | 14.69 | 14.85 | 15.85 | 17.36 |
| `my.agent.…` | Legacy | 9.29 | 9.39 | 10.41 | 17.01 |
| `my.agent.…` | npm word | 7.69 | 7.79 | 9.19 | 9.87 |
| `my.agent.…` | JVM word | 8.35 | 8.34 | 9.70 | 10.79 |
| `my.agent.…` | Compact | 9.95 | 10.10 | 11.23 | 12.36 |
| `/agent/…` | Legacy | 9.44 | 9.51 | 9.48 | 16.01 |
| `/agent/…` | npm word | 7.94 | 8.00 | 8.28 | 8.87 |
| `/agent/…` | JVM word | 8.59 | 8.55 | 8.78 | 9.79 |
| `/agent/…` | Compact | 10.12 | 10.24 | 10.27 | 11.36 |

Surrounding syntax does not reverse the isolated-id result. Both word packages
beat the legacy form in every measured agent surface and tokenizer. The npm
word form saves roughly 1.2–1.8 tokens in the three non-Gemma families and
about 7.14 DiffusionGemma tokens per rendered occurrence relative to legacy.

## Compact identities are mandatory outside agents

Every generated persistent identity other than `:seon.agent/id` uses the
compact adapter behind the same `seon.db.id/allocate!` operation. This includes
plan, message, run, turn, eval, schedule, and future generated identity
attributes. The retiring session id is transaction metadata, not a persistent
identity attribute, so it is outside this allocation surface. There is no
per-caller choice and no later migration option left open by this design.

The CLJS adapter is
[`@paralleldrive/cuid2`](https://www.npmjs.com/package/@paralleldrive/cuid2)
3.3.0 with its supported length option set to 12 and no Seon prefix:

- MIT license;
- 20 npm releases since 2022;
- 71,075,284 downloads in the measured month ending 2026-07-11;
- active repository and package-owned generation;
- output begins with a lowercase letter and otherwise uses lowercase base36,
  so no custom alphabet, underscore, slash, or ClojureScript munge ambiguity;
- the same grammar is valid as the name portion of `result/<eval-id>`; eval ids
  use this general compact profile rather than a result-specific generator;
- the 12-position grammar has about 61.57 bits;
- its no-hyphen grammar is disjoint from both word output and legacy ids.

The JVM adapter is Maven
[`io.github.thibaultmeyer:cuid:2.0.5`](https://central.sonatype.com/artifact/io.github.thibaultmeyer/cuid/2.0.5),
calling `CUID.randomCUID2(12)`:

- MIT license;
- zero runtime dependencies;
- eight Maven Central releases since 2022;
- 2.0.5 published 2025-11-11;
- active, non-archived repository with 52 stars at measurement time;
- package-owned CUID2 alphabet and generation;
- configurable output length without a custom alphabet;
- generated output starts with a lowercase letter and continues with lowercase
  base36, matching the CLJS grammar.

The exact release source is tagged at
[`release/2.0.5`](https://github.com/thibaultmeyer/cuid-java/tree/8b4f1fda7f00007df8bb4a1c7c27c17d74ac97e8).
Its CUID2 method chooses the first lowercase letter, mixes time, package entropy,
counter, and machine fingerprint, then emits a base36 hash at the requested
length
([source](https://github.com/thibaultmeyer/cuid-java/blob/8b4f1fda7f00007df8bb4a1c7c27c17d74ac97e8/src/main/java/io/github/thibaultmeyer/cuid/CUID.java#L48-L77)).
A local 10,000-id probe of `randomCUID2(12)` found every value matched
`[a-z][a-z0-9]{11}` and every value was distinct. That is package evidence,
not a replacement for database uniqueness.

The JS and JVM CUID2 implementations need not produce identical values. They
share the exact syntax accepted by Seon and the same collision-safe allocation
contract. Each non-agent generated identity attribute registers
`:seon.db.id.generator/compact`; callers never request a generator variant.

`nanoid` was not selected for the default compact adapter because its standard
URL alphabet contains both hyphen and underscore. ClojureScript munges hyphen
to underscore, so accepting both would make namespace mapping non-injective;
choosing a custom Nano ID alphabet would put alphabet ownership back in Seon.

## Schema and migration

### Compatibility union, narrow requirements

Change the current exact-width `:seon.db/id` to a compatibility union that
accepts:

- preserved legacy timestamp ids;
- syntax-valid two-hyphen agent ids from either platform package;
- syntax-valid no-hyphen compact ids.

Then use narrower attribute requirements:

- `:seon.agent/id` accepts `root`, legacy agent ids, and word ids;
- every other generated persistent identity attribute accepts legacy and
  compact ids;
- generic transport uses the broad union;
- no schema enumerates the current package vocabulary.

The generator policy is registered metadata on the identity attribute, not an
allocation input and not a stored entity-kind attribute. The fully namespaced
identity attribute states both what is being identified and which private
adapter allocates it. The value's syntax reveals the encoding when diagnostics
need it.

### Migration rules

- Keep `root` as the reserved root-user id; reject it from allocation and create
  it only through known-id reconciliation.
- Preserve all existing identity values, URLs, and agent namespaces.
- Register the human-readable adapter only on `:seon.agent/id`.
- Register the compact adapter on every other core identity attribute whose
  values are generated, including plan, message, run, turn, eval, schedule,
  and any other identity attributes found by the caller audit.
- Require future generated identity attributes to register compact metadata;
  reject allocation when metadata is absent.
- Stop minting the old timestamp form after all generated-id callers migrate.
- Do not rewrite old ids merely for visual consistency.
- Remove date parsing after readers project creation transaction time.
- Keep externally assigned UUIDs or vendor ids on their protocol attributes;
  do not treat them as Seon-generated identity exceptions.
- Pin dependency versions and lockfiles. A dependency upgrade is reviewed by
  inspecting its source/list changes and rerunning collision, syntax, and
  tokenizer measurements.
- Do not add package name, package version, RNG, or collision count to an
  entity. Those describe processing, not the resulting fact.

## Surveyed alternatives

| Candidate | Decision | Reason |
|---|---|---|
| EFF/BIP39 lists plus Seon sampler | Reject | Seon would own copied vocabulary and generation |
| `id-agent` | Reject | model-tokenizer-optimized vocabulary weakens human recognition |
| Java `Haikunator` 2.0.1 | Reject | stable but last Central release was 2018 |
| `de.adrianlange:readable-ids` | Reject | core is only 0.0.1 and the English dictionary module is not on Maven Central |
| `at.pichl:humanid` | Reject | close JS port but not published on Maven Central and contains duplicated pools |
| Clojure Haikunator ports | Reject | very low adoption and older releases than the selected Java artifact |
| separate humanhash alias | Reject | creates two names and makes alias collision a second problem |

The selected JVM option is not as strong an ecosystem choice as npm
`human-id`; the table records that honestly. It is the best published,
dependency-owned Java fit found, and the one database allocation contract
contains its smaller collision space.

## Implementation and verification plan

1. Add npm `human-id` 4.2.0 and `@paralleldrive/cuid2` 3.3.0 to
   `package.json`; add Maven `com.github.kkuegler:human-readable-ids-java` 0.4
   and `io.github.thibaultmeyer/cuid` 2.0.5 to `deps.edn`; update the existing
   lockfile through the normal package manager.
2. Add the single `src/seon/db/id.cljc` owner with reader-conditional private
   adapters, one public allocation request/response schema, one pure
   transaction-builder contract, and the fixed sixteen-round bound.
3. Extend the existing transaction request with allocation declarations,
   generated-candidate manifest, structured unique-conflict data, and
   writer-side concrete-eid expansion. Do not add a second wire operation.
4. Preserve existing write-id ambiguity handling, read-your-own-write fencing,
   listeners, transaction metadata, and error-as-value behavior.
5. Register word-generator policy on `:seon.agent/id`, then make new-agent
   creation name that attribute and rebuild all candidate-derived agent facts
   through the transaction builder. Keep known-id config reconciliation
   explicitly separate.
6. Register compact-generator policy on every other generated persistent
   identity attribute and migrate every old generator call, including plan,
   message, run, turn, eval, and schedule identities. Remove the retiring
   session-id generator with its transaction-metadata path rather than modeling
   it as a persistent identity. Callers name attributes; they never choose
   generator policy.
7. Delete `seon.db/new-id!`, caller wrappers, and date parsing after all callers
   use the canonical owner. Do not leave aliases or a parallel “v2” namespace.
8. Update the architecture data-model and runtime docs with the settled API and
   provenance projection.

Verification should assert properties, not exact random strings or context
wording:

- public args and returns pass their named Malli schemas;
- all map keys and allocation identity attributes are fully namespaced;
- registered attribute metadata, not caller input, selects the generator;
- an allocation against an identity attribute without exactly one registered
  generator policy fails before commit;
- package output passes URL, reader, ClojureScript munge, and syntax schemas;
- legacy ids continue to read and route;
- a fake adapter that always repeats one known-conflicting candidate performs
  sixteen generation rounds, at most one writer attempt, commits no domain
  facts, and returns `:seon.db.id.error/exhausted`;
- a fake adapter that returns a conflicting agent candidate and then a fresh
  one causes the pure builder to run again; the committed entity, home
  namespace, symbols, refs, nested values, and returned result contain only the
  fresh candidate;
- a `:transact/unique` failure on an unrelated unique domain attribute is
  returned after one attempt and never invokes id retry;
- collision classification uses structured attribute/value equality against
  the generated-candidate manifest, never exception-message text;
- an entity-map collision test documents Datahike's intentional upsert;
- multiple allocations and their domain facts commit atomically;
- a lost transaction reply cannot create a second entity;
- restart and agent resume preserve the committed id;
- creation date is read from transaction history rather than parsed from id.

## Final decision

Joined random words are safe for Datahike and work unusually well with the
measured tokenizers. The non-hacky design is not a clever custom encoding. It
is:

1. npm `human-id` for the active CLJS adapter;
2. Maven `human-readable-ids-java` for the JVM adapter;
3. npm `@paralleldrive/cuid2` and Maven `io.github.thibaultmeyer:cuid` for the
   compact adapters;
4. human-readable output only for `:seon.agent/id`, compact output for every
   other generated persistent identity, and reserved `root` outside allocation;
5. one `seon.db.id.cljc` owner and one collision-safe allocation operation;
6. normal sole-writer atomic transactions with concrete-eid uniqueness;
7. bounded, exactly classified collision retry that rebuilds all
   candidate-dependent transaction data;
8. syntax compatibility across platforms, without output parity;
9. identity-attribute metadata owns private generator selection;
10. no format markers, caller-selected generator variants, vendored vocabulary,
   custom sampler, public pre-commit generator, or stored generation metadata.

## Research provenance

This study inspected Seon's current schemas, id callers, wire transaction
path, and pinned Datahike source; the exact package sources and registries; and
the pinned tokenizer artifacts listed above. Collision probabilities, package
pool audits, file-store size, Clojure reader/munge safety, the JVM CUID2 grammar
probe, and 10,000-sample token distributions were measured locally on
2026-07-12. No external-LLM response was used.
