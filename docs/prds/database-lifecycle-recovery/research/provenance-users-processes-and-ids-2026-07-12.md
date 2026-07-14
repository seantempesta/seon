---
type: research
status: completed
tags: [research, database, flow, agent]
---

# Transaction users, processes, and universal ids

> **Supersession note (2026-07-12):** The provenance findings remain
> authoritative. The compact Base58 generator, crypto sampler, and `new-id!`
> implementation recommendations below are comparison evidence only and are
> superseded by
> [[human-readable-word-ids-datahike-and-tokenization-2026-07-12]]: readable
> package output for `:seon.agent/id` alone, compact package output for every
> other actual generated persistent identity, and one atomic
> `seon.db.id/allocate!` operation.

## TL;DR

The replacement for the current seven-field transaction context is two small,
orthogonal facts:

- `:seon.db/user` is a ref to the existing entity whose execution submitted
  the transaction. An agent points directly at its `:seon.agent/id` entity, a
  human at its `:seon.user/id` entity, and core work at the existing root agent.
  Do not create `:seon.db.user/id` or duplicate agent identity.
- `:seon.db/process` is a ref to a stable logical process entity: boot, config,
  or REPL. Root is the user for boot/config; an agent or human is the user for
  its REPL work.

No security meaning is implied. The writer may later authenticate a user and
stamp the trusted ref at the boundary, but this refactor records provenance,
not permissions.

Do not persist turn, eval, replay, test-run, session, resume, or generic origin
metadata. The resulting domain entities and their refs record the durable
facts. The existing “turn replay” surface only lists transaction ids; it cannot
and must not promise to replay arbitrary eval side effects. Turn/eval remain
fiber-local execution context where runtime code needs them.

The current id format also needs replacement. Date-only or second-only ids are
not identities: a second creation in the same period resolves to the same
Datahike unique identity and silently upserts the existing entity. The current
three random letters provide only 140,608 choices per minute, producing about
a 3.46% collision chance at 100 ids of one identity attribute in a minute.

This audit evaluated a 12-character cryptographically sampled, letter-first
Base58 id as a compact safety baseline:

```text
first: ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz
rest:  123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz
```

It has about 70.05 bits of entropy and a collision probability of about
`4.08e-10` after one million ids for the same identity attribute. It is two
characters shorter, avoids ambiguous `0/O/I/l`, is URL-safe, cannot collide
under ClojureScript munging, and is a valid segment of `my.agent.<id>`.
Transaction id and `:db/txInstant` remain the factual time/order coordinates.
The owner subsequently prioritized recognizable random-word ids and allowed one
schema'd generator to emit mutually disjoint encodings for different
requirements. The final choice is therefore delegated to
[[human-readable-word-ids-datahike-and-tokenization-2026-07-12]]; the Base58
analysis remains the comparison baseline, not the ratified format.

## Decisions incorporated

- Use `:seon.db`, not an actor subsystem, for transaction provenance.
- `:seon.db/user` accepts normal Datahike refs. Agents are the user entities;
  they do not receive a second identity attribute.
- Root is a database user. Boot and config describe the logical process that
  calculated a transaction.
- REPL is the logical process for interactive human/root/agent evaluation and
  its resulting facts.
- Record resulting facts, not an imperative trace of processing branches.
- Authentication and authorization remain out of scope.
- An eval may have arbitrary external side effects. Restoring database/runtime
  facts never means it is safe to execute the eval again.

## Current implementation evidence

### Transaction metadata today

`seon.db.internal/merge-tx-context-into-opts` currently copies an arbitrary
AsyncLocalStorage map into `:tx-meta` and derives `:seon.db/origin`
(`src/seon/db/internal.cljs:1029-1096`). The registered metadata set is:

```clojure
#{:seon.db/agent-id
  :seon.db/session-id
  :seon.db/turn-id
  :seon.db/eval-id
  :seon.db/origin
  :seon.db/replay?
  :seon.db/resume-marker?}
```

This conflates runtime execution context with persisted facts. In particular:

- `eval-id` suppresses eager schema tee while an eval batch is active;
- `replay?` suppresses tee work while runtime definitions are restored;
- `test-run` changes runtime behavior;
- none of those facts needs to be stored on every transaction.

Datahike already makes a transaction an entity. `flush-tx-meta` converts each
metadata entry into a datom on the current transaction
(`reference-code/datahike/src/datahike/db/transaction.cljc:802-820`), and
`transact-tx-data` supplies `:db/txInstant`
(`reference-code/datahike/src/datahike/db/transaction.cljc:1105-1133`). Every
application datom carries that transaction eid in its fourth field.

### Live default-store inventory

Read-only queries against the live default store on 2026-07-12 found 15,864
current datoms. Transaction metadata counts were:

| Attribute | Transactions |
|---|---:|
| `:seon.store.wire/write-id` | 74 |
| `:seon.db/origin` | 54 |
| `:seon.db/agent-id` | 17 |
| `:seon.db/session-id` | 0 |
| `:seon.db/turn-id` | 0 |
| `:seon.db/eval-id` | 0 |
| `:seon.db/replay?` | 0 |
| `:seon.db/resume-marker?` | 0 |

The 54 origins were 28 core-seed, 9 config, and 17 agent transactions. This is
direct evidence that five persisted fields carry no facts in the current
store, not merely a code-reading inference.

Current identity datoms grouped by origin were:

| Identity attribute | Origin | Entities |
|---|---|---:|
| `:seon.fn/sym` | core-seed | 935 |
| `:seon.ns/name` | core-seed | 138 |
| `:seon.schema/key` | core-seed | 1,159 |
| `:seon.test/sym` | core-seed | 263 |
| `:my.kb.shared/id` | core-seed | 1 |
| `:seon.user/id` | core-seed | 1 |
| `:seon.route/name` | config | 6 |
| `:my.skills/name` | config | 6 |
| `:seon.config/id` | config | 1 |
| `:seon.agent/id` | agent | 4 |
| `:seon.ns/name` | agent | 4 |

Core and config therefore have genuinely independent desired sets. They can
share the root user while retaining distinct process refs.

History contained 936 added core function identities and one retraction, while
935 remained current. A two-database current/history join recovered exactly
those 935 current, historically core-asserted entities. This proves stale
candidate discovery does not need a manifest or entity-owner attribute.

The current store also exposes why current-datom provenance alone is
insufficient. Three namespaces originally asserted by core currently have
their identity datom re-anchored by an untagged transaction:

```text
:seon.ns
:seon.agent.turn
:seon.ai.typeahead
```

Two entities currently contain both core-seed and untagged datoms. Provenance
is per datom, and historical identity assertion is the stable way to determine
whether an existing entity entered a desired set. Exact reconciliation must
then compare known attributes rather than infer an entity owner from its latest
transaction.

## Minimal target data model

### Existing users are referenced directly

Register one transaction attribute:

```clojure
(schema/register! :seon.db/user :seon.db/ref)
```

Its values are ordinary refs:

```clojure
[:seon.agent/id "root"]       ; core/root work
[:seon.agent/id agent-id]     ; work submitted by an agent
[:seon.user/id "user"]        ; work submitted by the current human
```

This is the same heterogeneous-ref pattern already used by
`:seon.agent.message/from` and `/to`. `:seon.db/ref` accepts eids, tempids, and
lookup refs; a ref does not require every target to share one identity
attribute (`src/seon/schema.cljc:111-124`).

The user means “the durable entity whose execution submitted these facts.” It
does not mean owner, role, principal, credential, or permission.

An agent-creation transaction should name the actual submitter:

- a child mint names its parent agent;
- a human new-agent request names the human user;
- root bootstrap names root;
- the newly created agent does not claim to have created itself merely because
  initialization has already entered its runtime namespace.

This exposes a current lifecycle bug: `init-agent!` enters the new agent's
`with-agent` scope before `agent/create!`. Runtime namespace selection and
database-user provenance need separate scopes.

### Processes are refs, not operation labels

Register a process identity and transaction ref:

```clojure
(schema/register! :seon.db.process/id
  [:keyword {:seon.db/identity true}])

(schema/register! :seon.db/process :seon.db/ref)
```

Seed only:

```clojure
{:seon.db.process/id :seon.db.process/boot}
{:seon.db.process/id :seon.db.process/config}
{:seon.db.process/id :seon.db.process/repl}
```

These are stable logical processes, not OS PIDs or per-restart instances.
They support two necessary facts:

```text
root        --runs--> boot   --asserted--> compiled core desired facts
root        --runs--> config --asserted--> configured desired facts
agent/human --runs--> repl   --asserted--> interactive resulting facts
```

Do not seed `web`, `scheduler`, `replay`, `test-run`, or a generic operation
taxonomy. Web actions select the actual user and the boot/config/REPL path that
performs the write rather than creating a process for every adapter. Normal
post-genesis production writes carry both user and process refs.

### Genesis bootstrap

The provenance attributes themselves must exist before they can describe a
transaction. The initial Datahike schema/root/process installation is therefore
one explicitly un-attributed genesis boundary; do not invent a recursive owner
system to explain it.

A pure `datahike.api/with` experiment proved that metadata tempids can
technically self-reference root/process entities created later in the same
transaction once metadata schema already exists. That subtle ordering is not
the public design: it still needs a prior schema transaction and falsely claims
root authored itself.

Genesis creates only the minimal root identity/ref targets. Root identity
presence is a genesis test, never an “initialized root” test. The immediately
following normal boot/config transitions exact-reconcile root's complete
configured attributes/components and program facts. They cannot be skipped by
the bare identity, so a crash after genesis resumes deterministically and fills
the missing desired state.

## Proven queries

### Current facts from one process

Known identity/attribute clauses bound the scan; do not ask for every datom
whose fourth field is one of a process's transactions because Datahike has
EAVT, AEVT, and AVET indexes, not a standalone transaction-first index
(`reference-code/datahike/src/datahike/db.cljc:902-932`).

```clojure
[:find ?e ?sym ?tx
 :in $ ?process-id
 :where
 [?p :seon.db.process/id ?process-id]
 [?tx :seon.db/process ?p]
 [?e :seon.fn/sym ?sym]
 [?e :seon.fn/source _ ?tx]]
```

### Current entities historically introduced by a process

This is the deletion/reconciliation candidate query. It constrains history by
one known identity attribute and joins the same eid/value against current
state, so a deleted identity or a later reuse on another eid is not mistaken
for a current candidate.

```clojure
[:find ?e ?id
 :in $ $history ?process-id ?identity-attr
 :where
 [$ ?p :seon.db.process/id ?process-id]
 [$history ?tx :seon.db/process ?p]
 [$history ?e ?identity-attr ?id ?tx true]
 [$ ?e ?identity-attr ?id]]
```

The fresh Datahike proof created `p1` and `p2` in boot, retracted `p2`, and
returned only `p1` from this query. Against the live store, the equivalent old
origin query returned 935 core function identities, 141 core namespace
identities, and 6 config route identities.

### Facts submitted by an agent or human

No union identity is needed. Bind whichever existing identity attribute the
caller possesses:

```clojure
[:find ?e ?a ?v ?tx
 :in $ ?user-identity-attr ?user-id
 :where
 [?user ?user-identity-attr ?user-id]
 [?tx :seon.db/user ?user]
 [?e ?a ?v ?tx]]
```

For bounded production reads, replace the generic `?a` with the attributes the
consumer actually needs. Canvas function discovery, for example, binds
`:seon.fn/source`; plan authorship binds `:my.plan/id` and the relevant datom.

### Mixed-user updates

A fresh proof wrote one cardinality-one value through boot, changed it through
config, and changed it through an agent. Current state correctly pointed to the
agent/repl assertion; history preserved both additions and the retractions
caused by later transactions. This confirms:

- provenance belongs to each datom/transaction, not the whole entity;
- the current datom answers who last asserted the current value;
- history answers which process previously introduced a desired fact;
- a generic permanent entity owner is unnecessary.

## Why turn and eval do not belong in transaction metadata

The current `seon.agent.debug/turn` query uses `:seon.db/turn-id` only to list
every transaction carrying that scalar. It does not replay those transactions
and cannot safely replay an eval's external effects. Persisted turn metadata
would also be misleading for asynchronous work that outlives the turn's
runtime scope.

The durable graph already contains:

- turn → run → agent refs;
- turn → eval component refs;
- eval → agent ref;
- eval source/result/error facts;
- message sender/recipient refs;
- the transaction id on every asserted datom.

The transaction that persists an eval and its program-graph tee is shared, so
one can join them directly:

```clojure
[:find ?eval-id ?fn-sym
 :where
 [?eval :seon.eval/id ?eval-id ?tx]
 [?fn :seon.fn/source _ ?tx]
 [?fn :seon.fn/sym ?fn-sym]]
```

Arbitrary domain writes made during an eval are the resulting facts. If a
domain needs a durable relation to a turn, it should model that domain relation
explicitly. A broad “all effects caused by this eval” promise is false for
filesystem, network, shell, and other side effects.

Keep current turn/eval in runtime AsyncLocalStorage for logging, eval tee
gating, cancellation, and error handling, but whitelist only user/process into
transaction metadata.

## Exact migration from current metadata

| Current metadata | Replacement |
|---|---|
| `:seon.db/agent-id` | `:seon.db/user` ref to the agent |
| `:seon.db/turn-id` | Runtime-only; remove debug transaction-list promise |
| `:seon.db/eval-id` | Runtime-only; join eval and tee facts by transaction |
| `:seon.db/session-id` | Delete; no active writer or reader |
| `:seon.db/replay?` | Runtime-only replay guard |
| `:seon.db/resume-marker?` | Delete; no active writer or reader |
| origin `:core-seed` | user root + process boot |
| origin `:config` | user root + process config |
| origin `:agent` | user agent + process REPL |
| origin `:user` | user human + process REPL |
| origin `:system` | actual user + process REPL when it commits resulting facts |
| origin `:replay` | Runtime-only; resulting error/log facts remain |
| origin `:test-run` | Runtime-only branch; any resulting write uses its actual user + REPL |
| `:seon.store.wire/write-id` | Keep; transport request/commit correlation |

Reader migrations:

- `my.plan.internal`, `seon.ai.typeahead`, `seon.ui.agent-view`,
  `seon.agent.ctx.render-fns`, and `seon.agent.debug` join
  transaction → `:seon.db/user` → `:seon.agent/id`.
- `seon.eval` core override/self-tee checks join the source datom's transaction
  to process boot.
- `seon.warn`, store inventory, and authorship views use user/process current/
  history queries. Config/core reconciliation enumerates its explicit population
  attributes and never treats the last process as authority.
- `seon.web.debug` routes invalidation from changed datoms and renderer read
  dependencies. User/process metadata must not stand in for dependency
  tracking or force global fan-out.
- `seon.agent.debug/turn` reports stored turn/eval/blob facts; it drops the
  claim that an arbitrary transaction list is replayable.

Writer migrations:

- Separate runtime agent namespace scope from durable database-user scope in
  `seon.db.internal`.
- Replace arbitrary tx-context copying with an explicit metadata whitelist.
- Boot/config establish root user plus their process ref.
- Turn/eval/replay/test markers remain in execution context only.
- Mint records the requesting human/root/parent agent, not the unborn child.
- Delete `derive-origin`, `managed-origins`, `managed-identities`, and the
  origin enum only after every reader has moved atomically.

## Universal id audit

### Current scheme and failure mode

`seon.db/new-id!` currently returns
`<3-letter-random>-<YYMMDDHHmm>` and `:seon.db/id` requires exactly 14
characters (`src/seon/db.cljs:305-340`, `src/seon/schema.cljc:126-135`). The
same generator supplies agent, run, turn, eval, message, schedule, and plan
identities.

Within one minute, the timestamp is constant and only `52^3 = 140,608`
possible prefixes remain. Birthday collision probabilities are approximately:

| Same identity attr in one minute | Collision probability |
|---:|---:|
| 10 | 0.0320% |
| 100 | 3.459% |
| 1,000 | 97.13% |

This is not theoretical for a universal generator: eval, message, and plan ids
can be created far faster than agent ids.

Datahike does not reject a duplicate unique identity as a generated-id
collision. `upsert-eid` looks up the unique value in AVET and resolves the map
to the existing eid
(`reference-code/datahike/src/datahike/db/transaction.cljc:548-624`). A
collision can therefore merge facts into the wrong entity.

Date-only has one possible id per day and second-only one per second. A second
creation in the period deterministically aliases the first. A process-local
counter requires persistence and coordination across pods/restarts; a
database-backed counter adds a serialized write before every id. Neither is a
simplification.

### Compact baseline evaluated

Generate 12 characters with Node `crypto.randomInt`:

- character 1 from the 49 Base58 letters;
- characters 2–12 from the 58-character Base58 alphabet.

The space is `49 * 58^11 = 1,224,345,556,008,111,351,808`, about 70.05 bits.
Birthday collision probability is about:

| Cumulative ids for one identity attr | Collision probability |
|---:|---:|
| 1,000 | `4.08e-16` |
| 1,000,000 | `4.08e-10` |
| 1,000,000,000 | 0.0408% |

Each identity attribute has its own Datahike uniqueness domain, so the relevant
population is per attribute, not the sum of every id-bearing entity.

### Namespace and URL proof

Agent ids become ClojureScript namespaces through
`(symbol (str "my.agent." agent-id))`. Raw base64url was rejected after proof:

```text
my.agent.A-b  -> my.agent.A_b
my.agent.A_b  -> my.agent.A_b
```

ClojureScript maps `-` to `_` during munging
(`reference-code/clojurescript/src/main/cljs/cljs/core.cljs:376-401`), so two
different base64url ids could share one runtime namespace. A digit-first
namespace segment can also emit an invalid JavaScript dot-property path.

Letter-first Base58 contains no munged character. Direct
`cljs.compiler/munge` proofs for the minimum/maximum/adversarial strings were
identity transformations:

```text
my.agent.A11111111111 -> my.agent.A11111111111
my.agent.z99999999999 -> my.agent.z99999999999
```

The format also matches the existing web route's alphanumeric safe-id grammar,
contains neither the runtime `proc:` separator nor the cluster `/` separator,
and is safe in lookup refs, URLs, symbols, and rendered text.

### Time and ordering

Time does not need to be encoded for correctness. It is duplicated and less
precise than the facts already stored:

- a datom's transaction eid provides creation/update order;
- the transaction's `:db/txInstant` provides time;
- domain entities with meaningful event time already carry their event fact.

`seon.db/id->time-str` has no code or test caller and can be deleted if the
final recognizable format omits time. The owner is open to that result because
operator tooling can join creation datoms to `:db/txInstant`.

### Store compatibility

Do not rewrite existing identities merely to change representation. Rewriting
breaks bookmarks, external handles, runtime advertisements, and human notes
without improving stored facts. During migration, make the shared shape a
precise union of the final new grammar(s) and the exact 14-character legacy
grammar; do not accept arbitrary strings between them. The Malli→Datahike bridge
continues to map the alternatives to `:db.type/string`.

There remains one public generator and validator. If the final study recommends
both recognizable and compact encodings, their grammars must be disjoint and
selected through one fully schema'd requirement/profile argument—not separate
caller-owned functions.

Production update sites are:

- `src/seon/schema.cljc` — shared id shape;
- `src/seon/db.cljs` — crypto generator; delete date/random-letter helpers and
  `id->time-str`;
- `src/seon/agent/ctx/render_fns.cljs` — retain the `"root"` exception around
  the shared id shape;
- `src/seon/dev/runtime_id.cljc`, `src/seon/web/datastar.cljs`, and comments in
  agent/run/client — update the documented grammar;
- `docs/conventions.md` and the agent-facing database documentation;
- structural tests for exact new/legacy lengths, alphabet, namespace munging,
  and runtime-id parsing.

## Recommended implementation order

1. Split runtime execution context from explicitly persisted metadata without
   changing old metadata yet.
2. Register `:seon.db/user`, `:seon.db/process`, and
   `:seon.db.process/id`; prove the same current/history queries in the CLJS
   pod test fixture.
3. Establish the root/process bootstrap and correct mint attribution.
4. Add the new user/process metadata while old fields still exist, then migrate
   every reader atomically.
5. Move reconciliation/core/config queries to the process-scoped known-attr
   queries and exact transaction compilation.
6. Remove turn/eval/session/replay/resume/origin persistence and their schemas.
7. After the word-ID study, replace `new-id!` behind its one public API, accept
   exact legacy ids, and verify home namespace,
   runtime resolution, web routes, agent creation, run/eval/message/plan writes,
   and restart/resume against both old and new ids.
8. Delete compatibility code only after a live query confirms no reader still
   names the legacy metadata attrs.

## Decisions after the initial audit

- Persist REPL as the third process identity.
- Use one explicitly un-attributed genesis transaction rather than circular
  root self-authorship.
- Preserve exact legacy ids without rewriting stored entities.
- Keep one public ID generator even if schema'd requirements select mutually
  disjoint readable/compact encodings.
- Ratify the new grammar only after the Datahike/tokenization word-ID study.
