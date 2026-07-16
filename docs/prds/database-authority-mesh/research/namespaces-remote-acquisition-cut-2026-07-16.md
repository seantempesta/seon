---
type: research
status: complete
tags: [research, database, agent, flow]
---

# Namespaces remote acquisition cut — 2026-07-16

## Decision

`namespaces-block` becomes an asynchronous database acquisition followed by
the existing pure selection and formatting code. Every read is pinned to the
compiled child prompt's coordinate `C`. The child receives eager ordinary
maps; no Datahike database value, entity, pull object, history value, or local
query facade enters formatting.

The acquisition is data-dependent and deliberately is not a global prompt
batch. It first reads the agent's namespace dials and current namespace, then
reads the current namespace's persisted require edges, then uses one
`pull-many` plus one bounded transaction query for exactly the selected
namespaces. Referenced schemas expand in bounded query frontiers because the
next keys are known only after Malli parses the preceding definitions. This
removes the present namespace, test, and schema N+1 reads without inventing a
cache or copying Datahike into Bun.

A literal two-file source edit is not sufficient. Full namespace rendering in
`namespaces.cljs` calls `ctx/render-namespace`, and both full and compact
rendering call `ctx/referenced-schema-block`; those functions currently hide
database reads in `ctx.cljs`. The atomic implementation therefore needs one
narrow change in `ctx.cljs`: retain its existing formatting and schema-walk
logic, but accept ordinary namespace rows and schema definitions. Reimplementing
those functions in `namespaces.cljs` would violate the one-mechanism rule.

`render_fns.cljs` is the only derived-block dependency in this cut. Its
auto-run discovery becomes pure over the function rows already acquired for
the current namespace. Renderer recency (`renderer-touch` and
`last-updated-surface`) belongs to the canvas acquisition and must not be
silently absorbed here.

## Dependency ledger

| Owner | Selected revision | Source-grounded constraint |
|---|---|---|
| Seon | current shared checkout on 2026-07-16 | `namespaces-block` selects current + direct requires + `::full-source`, orders by the `:seon.ns/name` datom transaction, and delegates full/schema formatting to `ctx.cljs`. |
| Datahike | `670cd1ada40462cb5927f0dc687f6b3a95f9e13f` (`reference-code/datahike`) | `pull-many` parses one selector once and returns input-aligned eager results, including `nil` for missing refs. Queries accept native work/result/weight bounds. Exact committed query results and identical in-flight work are shared by committed coordinate identity. |
| Seon database protocol | current protocol version 6 | `query`, `pull`, `pull-many`, installed schema, history queries, cancellation, and `execute-many` already return ordinary values at one complete coordinate. Execute-many members run independently on bounded JVM read workers and retain vector positions. |
| Compiled child prompt owner | [[compiled-child-prompt-owner-2026-07-16]] | One supervised per-agent Bun child owns async acquisition, pure formatting, deadlines, and authored execution at `C`; there is no fixed global member catalog. |
| Authored program loader | `src/seon/execution.cljs` | Authored invocations acquire source identity and load the agent program only when an authored target is actually invoked. Current acquisition is bounded to 2,048 rows and 3 MiB, and its result must match `C`. |
| Shadow CLJS | `4e72595f57618f5c43388ad13d5136cd3bede566` | The compiled child artifact reaches the prompt and namespace owners through ordinary namespace requires; no manual module list is needed. |
| Bun | `be77b652884b16a103cfaa4af3c1102f72f2dcd3` | The per-agent OS process supplies the runaway-loop and CPU-failure boundary; killing one child does not kill the parent, JVM, or sibling agents. |

First-party evidence read:

- `src/seon/agent/ctx/namespaces.cljs`;
- `src/seon/agent/ctx/render_fns.cljs`;
- `src/seon/agent/ctx.cljs` (`current-ns`, `pull-ns-data`, schema closure,
  `render-one-ns-ai`, and `render-namespace`);
- `src/seon/eval.cljs` (`persisted-require-edges`);
- `src/seon/execution.cljs` (coordinate-pinned source and program loading);
- `src/seon/db.cljs`, `src/seon/db/protocol.cljc`, and
  `src/seon/db/writer.clj`; and
- `test/seon/agent/ctx/namespaces_test.cljs` and
  `test/seon/agent/ctx/render_fns_test.cljs`.

Datahike evidence read:

- `reference-code/datahike/src/datahike/pull_api.cljc`: `pull-many` resolves
  the ordered refs, parses the selector once, and performs one pull traversal;
- `reference-code/datahike/src/datahike/query.cljc`: bounded `q` and
  `q-with-evidence`, exact committed-coordinate result caching, shared
  in-flight computation, and request cancellation; and
- Datahike query tests for `or-join`, `not-join`, collection inputs, and
  resource exhaustion.

## Ordinary input to formatting

Use the existing attribute names. Do not introduce a second model for a
namespace, require edge, function, schema, or test. The acquired value is an
ordinary namespaced map with these projections:

```clojure
{:seon.db/coordinate C
 :seon.agent/id id
 :seon.agent.ctx.render-fns/current-ns current-ns
 :seon.agent.ctx.namespaces/full-source #{...}
 :seon.agent.ctx.namespaces/with-tests #{...}
 :seon.agent.ctx.namespaces/current-full? true
 :seon.agent.ctx.namespaces/current-tests? true
 :seon.execution/namespace-rows
 [{:seon.ns/name namespace-keyword
   :seon.db/tx transaction-id
   :seon.ns/source source-or-nil
   :seon.ns/require-edges [{:seon.ns.require/target ...}]
   :seon.fn/_ns [{:seon.fn/sym ...}]
   :seon.schema/_ns [{:seon.schema/key ...}]
   :seon.test/_ns [{:seon.test/sym ...}]}]
 :seon.execution/schema-forms [[schema-key persisted-form]]}

```

`:seon.db/tx` reuses the existing transaction projection; it is transient
ordinary formatting data, not a new database attribute. It preserves the
current recency order without storing derived state. Missing current
namespaces retain the existing synthesized tail ordering and workspace stub.

The namespace pull selector contains the union already used by the full and
compact renderers:

- `:seon.ns/name`, `:seon.ns/source`, and persisted
  `:seon.ns/require-edges` with their existing edge attributes;
- reverse `:seon.fn/_ns` rows with symbol, argument lists, doc, source, spec,
  privacy, function fact, and schema error;
- reverse `:seon.schema/_ns` rows with key and form; and
- reverse `:seon.test/_ns` rows with symbol, source, last pass, last failure,
  and failure summary.

One selector prevents the current full/compact/test split from fetching the
same namespace several times. Optional uninstalled attributes are naturally
absent from pull results; query construction must not use `get-else` on a
never-installed optional attribute.

## Exact acquisition at `C`

### 1. Agent dials and current namespace

Issue one namespace-owned `execute-many` with two independent members:

1. pull the agent by `[:seon.agent/id id]`, including its four existing
   namespace dial attributes and `:seon.agent/ctx` blocks with `:db/id`,
   `:seon.agent.ctx/name`, and the same four attributes; and
2. query the latest successful eval namespace for this agent, breaking equal
   timestamps by the `:seon.eval/at` datom transaction.

The exact bounded form is the existing run -> turn -> eval join with an
anti-join that rejects any later successful eval:

```clojure
[:find ?ns .
 :in $ ?aid
 :where
 [?agent :seon.agent/id ?aid]
 [?run :seon.agent.run/agent ?agent]
 [?turn :seon.agent.turn/run ?run]
 [?turn :seon.agent.turn/evals ?eval]
 [?eval :seon.eval/ok? true]
 [?eval :seon.eval/at ?at ?eval-tx]
 [?eval :seon.eval/ns ?ns]
 (not-join [?agent ?at ?eval-tx]
   [?later-run :seon.agent.run/agent ?agent]
   [?later-turn :seon.agent.turn/run ?later-run]
   [?later-turn :seon.agent.turn/evals ?later-eval]
   [?later-eval :seon.eval/ok? true]
   [?later-eval :seon.eval/at ?later-at ?later-tx]
   (or-join [?at ?eval-tx ?later-at ?later-tx]
     [(> ?later-at ?at)]
     (and [(= ?later-at ?at)] [(> ?later-tx ?eval-tx)])))]

```

Set `max-results` to 1 and give the member explicit work and result-weight
limits. Datahike resource exhaustion is an error, never a clipped answer. The
shortest implementation probe is equal timestamps, a later transaction with
an earlier timestamp, no successful eval, and a large history. If the
anti-join does not return the same intended latest value as the current
sort, the cut is not ready; do not fall back to silently clipping all eval
rows.

Resolve the `:namespaces` block and the block -> agent -> default precedence
with the existing `resolve-cfg` logic over the two ordinary maps. When no
successful eval exists, use the existing `home/home-ns` fallback.

### 2. Persisted require edges

Pull the current namespace by `[:seon.ns/name current-ns]` with only name and
the existing require-edge selector. This is a real dependency: the required
names cannot be known before this result. A missing namespace returns `nil` and
the fresh workspace behavior remains intact.

Run the existing `required-ns-selections` reduction over those ordinary edge
maps. Preserve exactly:

- `:as-alias` contributes no callable card;
- alias, bare require, `:refer :all`, or absent refers selects the whole public
  callable surface;
- several explicit refers union; and
- hidden/internal and `-test` targets are excluded.

The selected names are current namespace, direct require targets, and
`::full-source` pins only. There is no all-namespace scan.

### 3. Selected namespace rows

Issue one `execute-many` at `C` after the selected names are known:

1. `pull-many` the ordered lookup refs
   `[:seon.ns/name namespace-keyword]` using the single selector above; and
2. query only those names and their name-datom transactions:

```clojure
[:find ?name ?tx
 :in $ [?name ...]
 :where
 [?namespace :seon.ns/name ?name ?tx]]

```

The input name vector is distinct and deterministically sorted before the
request. `pull-many` position alignment preserves missing entries. Join the
transaction rows by `:seon.ns/name`, synthesize the missing current namespace
at the tail, then apply the existing stable `seon.*` prefix and recency body
ordering.

This is intentionally two dependent requests after initial discovery, not a
round-robin channel or global batch. The dependency is in the data. Within
each request the JVM executes independent members in parallel on the same
immutable Datahike value.

### 4. Referenced schema definitions

After namespace rows arrive, use the existing Malli-based
`schema-form-refs`/`normalize-schema-form` logic to obtain initial schema keys
from selected function specs and owned schema forms. Query one whole unseen
frontier at a time:

```clojure
[:find ?requested ?form
 :in $ [?requested ...]
 :where
 [?schema :seon.schema/key ?requested]
 [?schema :seon.schema/form ?form]]

```

Parse each returned form, add its unseen references, and repeat at the same
`C`. Keep the existing cycle detection, sorted output, own-key traversal but
omission, 40-emitted-definition cap, and explicit cap notice. Every query has
a maximum of 40 requested keys and native work/result/weight bounds.

This replaces one `entity-lazy` lookup per key with one query per dependency
depth. It is preferable to sending every schema in the database on every
prompt. Benchmark the bounded frontier against one all-schema query using the
actual corpus; change only if total latency and bytes prove the all-schema
read better. Any reused rows live only in the current prompt's ordinary input;
there is no new process cache.

## Pure code retained

Keep these current semantics and, where already possible, the current
functions:

- `hidden-ns-name?`, `my-ns-name?`, `test-ns-name?`, `included-ns?`,
  `seon-framework-ns?`, `full?`, and `resolve-cfg`;
- require-edge selection, include-set derivation, prefix/body ordering, test
  selection, workspace fallback, and namespace header;
- compact callable/schema formatting, runtime-object omission, quoting,
  demarcation, and output ordering;
- `ctx.cljs` Malli reference discovery, normalization, closure traversal, full
  namespace formatting, test status, and schema definition text, after their
  database arguments are replaced by the ordinary maps above; and
- `render_fns.cljs` `output-twin-keys`, public/private filtering, pin removal,
  twin block construction, clipping, wrong-shape handling, and error text.

The pure render entry accepts the ordinary acquisition value and returns the
same string. It must not call `db/query`, `db/pull`, `db/entity`,
`db/entity-lazy`, `db/history`, `db/basis-t`, or dereference `db/*conn*`.

## Auto-run functions and authored loading

Auto-run discovery does not need another query. Filter the current namespace's
acquired `:seon.fn/_ns` rows with the existing `output-twin-keys`, privacy, and
pin rules. The ordinary derived blocks retain their existing names,
priorities, `::fn-sym`, and AI/Hiccup slots.

Run compiled targets directly in the child. On the first actually selected
agent-authored target, use the existing `prepare-invocations!` source identity
and `ensure-program!` path at `C`; do not load authored code during namespace
discovery and do not acquire the global authored program for prompts with no
authored target. Later authored targets in the same child reuse the existing
compiler state only when the program digest matches. Authored targets remain
sequential inside one agent child; different agent children execute in
parallel.

The target input contains ordinary values only: the existing agent id,
`:seon.render/at` equal to `(:seon.db.coordinate/t C)`, and any ordinary render
node data. Do not pass `:seon.db/db`. Database calls made by an authored target
are asynchronous `seon.db` calls that inherit `C` from the child's existing
`db/with-tx-context` scope.

The current authored loader still acquires all of that agent's authored
functions plus global schema and function-contract rows before compiling a
target. That is acceptable for the first cut because it is lazy, bounded, and
already source-identity checked. A later optimization may acquire only the
target's reachable authored program, but it requires a separate exact loader
proof and must not delay removal of SCI from this render path.

## Reads deleted

The cut removes these current repeated reads from one namespace render:

- the all-namespace `[:find ?nm ?tx]` scan;
- agent `db/entity` plus `ns-block-entity`'s second agent entity walk;
- one existence entity plus full/compact pulls per selected namespace;
- the second pull for tests per full namespace;
- the compact renderer's existence check and pull per namespace;
- `ctx/pull-ns-data`'s existence check plus two pulls per full namespace;
- one entity lookup for every schema key in the transitive closure; and
- `render_fns/render-fn-rows`'s separate current-namespace query.

For `N` selected namespaces and `S` referenced schemas, the current path can
perform approximately `3N + S` namespace/schema reads in addition to agent,
current-namespace, and namespace-scan reads. The selected path performs two
small discovery requests, one selected-data request, and `D` bounded schema
queries where `D` is reference depth, not `S`. Exact frame counts and bytes
must be recorded because a small `N` may make framing visible even though the
new path scales much better.

## Parity, performance, and cancellation falsifiers

### Parity

1. At the same complete coordinate, default, current-full-off, explicit
   full-source, with-tests, current-tests-off, explicit refer, alias,
   `:refer :all`, multiple-refers, and `:as-alias` fixtures are byte-identical
   to `namespaces_test.cljs` expectations.
2. A missing fresh home namespace still renders the canonical workspace stub
   last; a missing non-current namespace renders the existing not-in-database
   note or is omitted exactly as today.
3. Direct, transitive, cyclic, missing, raw/register-form, own-key, and capped
   schema fixtures preserve current output and ordering.
4. Auto-run discovery returns the same public twin functions, pin exclusions,
   block names, priorities, and order. No authored program read occurs when
   no authored target is selected.
5. A first-ever authored render function loads and runs successfully at `C`;
   every nested database request carries `C`, and its result cannot observe a
   later transaction.

### Performance

Measure cold and warm runs with 1, 10, 50, and 200 selected namespaces and
schema graphs of depth 1, 4, and the 40-definition cap. Record:

- UDS frames and encoded bytes in each direction;
- Datahike queue time, run time, work, result rows/weight, cache outcome, and
  joined in-flight computation evidence;
- Bun acquisition, schema parsing, formatting, CPU, peak RSS, and event-loop
  delay; and
- end-to-end prompt latency with 1, 8, and 32 simultaneous agent children.

Reject the cut or change its query shapes if it scans all namespace rows,
silently clips a result, sends more data than the current path on realistic
fixtures without a compensating latency win, or serializes independent JVM
reads. Compare frontier schema acquisition with one bounded all-schema query;
the selected default is the one with lower end-to-end latency subject to a
strict byte/RSS bound, not a preconceived round-trip count.

### Cancellation and failure

1. Cancel during initial discovery, selected `pull-many`, every schema
   frontier, compiled rendering, and authored loading. Each active request is
   canceled by its existing request id, the session closes, and only that
   agent child retires.
2. Kill a child during a query and verify the JVM releases the physical
   session's database acquisition and query callers; sibling children and the
   parent remain live.
3. A resource-bound error becomes the existing local block error. It must not
   return partial namespaces, erase sibling prompt blocks, or retry at a newer
   coordinate.
4. A synchronous runaway loop is terminated by the child deadline and process
   exit. SCI is removed from this path only after the process-isolation parity
   proof passes; it is not retained as a second execution mechanism.

## Implementation boundary

The smallest coherent atomic source unit is:

1. make the selected `ctx.cljs` namespace/schema formatters pure over ordinary
   acquired maps;
2. replace `namespaces-block`'s database-reading head in place with the async
   coordinate-pinned acquisition above;
3. make auto-run discovery in `render_fns.cljs` consume the already acquired
   current namespace function rows and invoke targets through the child owner;
4. delete the superseded reads and SCI runner in the same refactor; and
5. run focused parity/performance/cancellation proof before the complete
   prompt graduation gate.

Do not add a compatibility namespace, local replica, synchronous IPC facade,
request replay table, all-prompt fixed member vector, round-robin dispatcher,
or new cache. If the pure `ctx.cljs` seam is not accepted into the atomic
unit, this slice is not ready to implement: duplicating its formatter is worse
than leaving the current owner intact.
