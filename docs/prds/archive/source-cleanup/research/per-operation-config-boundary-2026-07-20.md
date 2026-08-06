---
type: research
status: complete
tags: [research, database, architecture]
---

# Per-operation pinned configuration boundary (2026-07-20)

Implementation-ready grounding for the first configuration unit in
[[../roadmap]] Stage 4. This report narrows
[[config-authority-readiness-reconciliation-2026-07-20]] and corrects one
stale assumption in [[als-config-probe-2026-07-20]] against current source.
It owns no source change. U4 committed and released its paths during this
audit; the post-U4 reread and resulting execution-tier ruling are included.

## Verdict

The earliest unsettled Stage-4 configuration contract is one operation
acquisition, not a mutable process snapshot:

1. acquire one ordinary immutable database value;
2. pull and decode the complete `:seon.config` singleton **from that exact
   value**;
3. enter the existing transaction-context `AsyncLocalStorage` with both
   `:seon.db/db` and `:seon.config/configuration` through `.run`;
4. enter the same configuration through `error/with-configuration`;
5. run every descendant read, write, await, Promise continuation, and timer;
6. let the next independently entered operation acquire a new pair.

The configuration is pinned for the lifetime of the operation. A config
transaction during that lifetime must not mutate the running operation. The
next operation sees the new database value and therefore the new singleton.
This is the refresh mechanism; there is no refresh-in-place mechanism.

The source cut must cover every root boundary before deleting the boot
installer. Merely adding configuration to existing inner `with-tx-context`
maps is insufficient when the map's database value was acquired elsewhere or
when a compiled execution-child call skips program preparation.

## Snapshot, dependencies, and protected paths

Initial read snapshot: `e565c19684165297378307c63cbc27b9b9087500` on
2026-07-20. The checkout contained active uncommitted U4 work in `src/seon/host.clj`,
`src/seon/host/context.clj`, new `src/seon/host/record.clj`,
`src/seon/db/id.cljc`, their writer tests, and JVM drills. Those paths were
read but not edited.

Post-U4 snapshot: `b7808e357d81a52ee0b1851b25ed570f9fe21e8a` (`Record
host-tier evals through the one corpus mechanism`). Its commit records writer
261 tests / 2016 assertions, CLJS 1336 / 6170, the 20/20 kill/replay drill, and
the fresh U1.5 driver with post-kill definition reuse. The source paths were
clean and released; this report then reread their committed invocation, read,
write, generated-candidate, schema, and provenance contracts.

| Dependency or mechanism | Selected source | Contract used here |
|---|---|---|
| Bun async context | Bun 1.3.14, `node:async_hooks` compatibility surface | `.run` scopes awaited, chained, nested, and timer descendants without changing siblings or the caller; executable evidence is [[als-config-probe-2026-07-20]] |
| Node comparison | Node v26.4.0 | The same probe produced byte-identical `.run` inheritance and independently falsified cross-fiber `enterWith` refresh |
| ClojureScript | 1.12.145; vendored source `reference-code/clojurescript` at `946d75f3483c0c8e784e6668bff2c71a25619a77` | Native `^:async` functions and Promise continuations are the descendants the ALS scope must retain |
| Datahike | `reference-code/datahike` at `6f2569087ed31f53e751e7535ef4bf2527912046` | A database is an immutable ordinary value; `reference-code/datahike/src/datahike/api/impl.cljc:145` returns the connection's value and `api/types.cljc:32-52` names value and identity contracts |
| Aero | `reference-code/aero` at `c47a10fa5f6a52084d04769af06d5e04d6603e13` | Manifest resolution remains the one desired-state input; this unit consumes the reconciled singleton and performs no Aero read |
| Configuration schema | `src/seon/config.cljs:453-506,843-963` | `:seon.config/id "cluster"` identifies one closed, fully defaulted decoded singleton; database query/pull policies are pure functions of it |
| Database context | `src/seon/db/internal.cljs:17-78`; `src/seon/db.cljs:674-776` | `run-with-tx-context` merges an immutable delta and calls ALS `.run`; `read-db!`, read attribution, and read limits already consume the current context |
| Existing near-precedent | `src/seon/execution/runtime.cljs:583-605` | `eval-batch!` scopes agent, full configuration, and error policy, but explicitly replaces the inherited database with nil at lines 594-596; retain the scoping idiom, not that broken pair |
| U4 host work | `b7808e35`: `src/seon/host.clj`, `src/seon/host/context.clj`, `src/seon/host/record.clj`, and `src/seon/db/id.cljc` | The JVM host binds per-invocation agent identity and attaches the same user/process provenance to direct writer reads and writes, but its wrappers resolve a fresh head for each call and carry no full configuration or singleton-derived resource limits |

The implementation owner may edit only after the route-authority and reactive
router cuts release their overlapping web paths. The per-operation source unit
owns `src/seon/db.cljs`, `src/seon/db/internal.cljs`, and the explicit boundary
call sites enumerated below. It must not edit the released U4 JVM host paths
without an explicit Stage-4 handoff,
perform the later operation-context ALS rename, or absorb launch-descriptor,
reactive-policy, route-authority, or environment-gate work.

## Current failure, directly falsifiable

At the snapshot:

- `seon.db.internal` constructs `tx-context`, `agent-context`, and
  `read-evidence-context`; `enter-tx-context!` calls `.enterWith`;
- `seon.db/install-configuration-context!` calls that entry function once;
- `seon.client/start-runtime-impl!` installs the selected/retained
  configuration at boot (`client.cljs:2198-2200`);
- `agent.loop/install-ticker!` captures the same boot configuration in the
  interval closure (`loop.cljs:1225-1238`);
- most request, trigger, turn, and execution boundaries either enter no
  configuration or acquire a database value separately from it; and
- a compiled execution-child function can run without
  `prepare-eval-program!`, so it does not necessarily acquire the
  configuration that the old boundary inventory assumed it did; and
- `execution.runtime/eval-batch!` captures the inherited database, prepares
  configuration, then enters `{::db/db nil, :seon.config/configuration ...}`.
  Explicit arguments may keep individual eval reads pinned, but the ambient
  carrier itself no longer describes one operation basis.

The shortest current-state falsifier is already recorded: the boot-installed
configuration is absent in the MCP REPL fiber, and two fibers created before a
later `enterWith` retain their old stores. A second focused falsifier should
exercise a compiled child function with `::pin-database? true` that performs a
database read: today its invocation scope contains the pinned database but no
configuration, so the authority request carries the code default limits.

## One shared acquisition shape

The implementation needs one database-owned acquisition function rather than
private variations in client, web, ticker, turn, and execution code. It belongs
in `seon.db`, which already requires `seon.config`, owns ordinary database
values, and is the only application database API. `seon.config` remains pure
and must not require `seon.db`.

Recommended public data and function contracts:

```clojure
(schema/register!
 ::operation-acquisition
 [:map {:closed true}
  [::db :seon.db/db]
  [:seon.config/configuration :seon.config/singleton]])

(schema/register!
 ::operation-acquisition-response
 [:or ::operation-acquisition ::error])

(defn ^:async acquire-operation!
  "Acquire one database value and its complete decoded configuration."
  {:malli/schema [:=> [:cat] ::operation-acquisition-response]}
  [] ...)
```

`acquire-operation!` obtains one value through the existing `db` function,
calls the existing `entity` with that value and
`[:seon.config/id config/cluster-config-id]`, rejects a missing singleton as a
canonical core error value, decodes through `decode-edn-values`, and returns
the exact pair. It never reads a manifest, never caches, never transacts, and
never throws into an agent/runtime loop. The database value passed to `entity`
must be the same value returned in the response; an omitted database argument
would destroy the basis guarantee.

This replaces `client/acquire-configuration!`, whose current return value loses
the database value and whose exceptions force callers to reconstruct a pair.
The shared function is ordinary infrastructure, not agent-facing. Boundaries
whose larger acquisition already pulls the singleton from an explicit database
value may construct the same registered shape locally rather than pay a second
pull, but they must prove that both fields came from the same value.

The pair is also the admission shape for a root context. No production
`with-tx-context` call may add, replace, or clear `:seon.db/db` without adding
the configuration acquired from that same value. Nested code that truly uses
the outer operation removes its redundant database override and inherits both.
Code that intentionally moves to another database value performs another
operation acquisition. This invariant prevents a map merge from silently
combining database B with configuration A.

Do **not** introduce an unqualified `configuration` cache, a second ALS, or a
map with partial policy keys. The live probe proved that a partial map fails
the `:seon.config/singleton` input schema. Optionality is absent, never nil.

## Boundary inventory at the snapshot

The unit distinguishes root operation entries from nested scopes. A root must
acquire or receive a certified pair and enter it. Nested scopes inherit through
the existing merge and need no configuration-specific read.

| Root operation | Current source | Exact Stage-4 change |
|---|---|---|
| Cold boot reconcile, recovery, initial agent, restore completion, and publication | `client.cljs:1935-1964,2078-2091,2120-2265` | After the database session opens, acquire one pair. Enter it around each boot/config operation together with its explicit root user/process. Remove the boot installer only after all boot descendants have explicit `.run` scope. A selected manifest's decoded singleton is not yet certified to the post-reconcile database value; reacquire after reconciliation before recovery/initial-agent/publication. |
| Attached-runtime start | `client.cljs:2136-2163` | Replace the configuration-only read with one pair and scope web start plus attached-runtime resumption/publication descendants. Do not assume the caller inherited the old boot store. |
| Shadow build failure/completion | `client.cljs:625-709` | Replace configuration-only acquisition with one pair; wrap the entire failure/publication chain in both transaction context and `error/with-configuration`. Each build notification acquires anew. Reload ticker installation must not recapture this configuration. |
| Web request | `web/serve.cljs:1800-1825` → `web/router.cljs:470-486` | After the route-authority/reactive-router cuts, make the single asynchronous request dispatcher acquire one pair and run the selected Ring handler inside both contexts. All seeded and bootstrap handlers then inherit one request basis. Readiness/static responses may remain pre-database only if the route cut proves they perform no database/config operation. Never reuse the router projection's accepted database value as the request value. |
| Inbound wake, renew, and re-drive callback | `agent/loop.cljs:705-718` plus trigger callbacks at `734-989` | `with-agent-repl` must take a certified acquisition (or acquire one before entering), then enter agent, pinned database, configuration, and REPL provenance together. A transaction-listener callback's notifying `db-after` may serve as the operation database value only when the callback also pulls the singleton from that same value. |
| Loop iteration / turn | `agent/loop.cljs:382-575`, `agent/turn.cljs:945-1020` | `acquire-loop-state` already returns one pinned database value; extend its existing batched projection to return the full decoded singleton from that value. Enter the pair at the iteration boundary before beat, render, LLM/eval, and writes. Remove the beat scope's redundant database-only override at `loop.cljs:422-427`. `run-turn-body!` receives the pair; it must not independently reacquire or rely on the outer wake store. |
| Retired execution-child recovery | `agent/loop.cljs:346-380` | Acquire one pair before `recovery/recover!` and enter it with the agent's user/process. The later failure reconciliation gets its own new loop-state pair. |
| Scheduled fire | `agent/loop.cljs:1051-1094`; `agent/schedule.cljs:346-378` | `acquire-agent-state`/schedule facts must return configuration decoded from their explicit database value. Enter the pair before opening and evaluating the scheduled turn. |
| Ticker pass | `agent/loop.cljs:1200-1241` | Change `install-ticker!` to capture no singleton. Each `run-tick!` invocation acquires a fresh pair and scopes overdue close, stale close, and schedule fire. `error/with-configuration` uses that acquired singleton. The interval may retain only process-local timing/handle data. |
| Agent runtime resume/rehost | `agent/runtime.cljs:80-134` | Acquire one pair before the initial pull and scope the complete resume, trigger installation, and re-drive. The inner agent scope then inherits database/configuration instead of creating identity-only work. |
| Execution parent invocation | `execution/host.cljs` invocation preparation and `execution.cljs:486-545` | The parent already pins an invocation database value. Either its enclosing operation pair supplies configuration or invocation preparation pulls the singleton from that exact value. Do not add a second latest-head read between plan and send. |
| Bun execution-child invocation | `execution.cljs:878-985` | **Correction to the older inventory:** before invoking either a compiled or authored function, pull/decode the singleton from `:seon.db/db` on the invocation. Enter read-evidence, agent, pinned database, configuration, and error policy around the call. `prepare-eval-program!` may reuse that configuration but is not the acquisition owner because compiled functions can skip it. |
| Execution-child `eval-batch!` | `execution/runtime.cljs:583-605` | Retain the existing agent/config/error scoping idiom, but delete the `::db/db nil` override. Assert that the configuration supplied by the child invocation is the one acquired from the inherited pinned database value; do not reacquire inside the batch. Explicit database arguments may still fence individual work, but the ambient pair remains coherent. |

The post-U4 reread makes the JVM disposition concrete. `host/run-invocation!`
receives the invocation's `:seon.db/db` (`host.clj:447-521`), but currently uses
it only in terminal result/error frames. The sci `seon.db/query`,
`query-with-evidence`, `pull`, and `transact!` wrappers each call
`context/resolve-head!` themselves (`context.clj:242-293,330-380`), so two
reads in one host eval may observe different database values and neither is
necessarily the invocation value. Their query/pull requests carry provenance
but no singleton-derived `datahike.resource/*` limits. U4 correctly closes
recording and provenance; it does not close per-operation basis/configuration.

The JVM host is therefore part of this Stage-4 contract, but not part of the
JavaScript ALS implementation. Recommended boundary: add the full decoded
`:seon.config/configuration` to the already-closed execution invocation message
beside `:seon.db/db`; the CLJS parent sends the certified pair it already owns.
On the JVM worker thread, bind one immutable dynamic operation map containing
agent id, invocation database, and configuration around `run-invocation!`.
Host context reads use the bound database instead of `resolve-head!`, apply the
same query/pull policy fields to protocol requests, and writes use the bound
database as their expected work value while retaining U4's provenance and
idempotency receipt behavior. This is the same ordinary data contract across a
different runtime, not a JVM imitation of `AsyncLocalStorage`.

This extension requires an explicit post-U4 path handoff for `host.clj`,
`host/context.clj`, the shared invocation schemas, and their conformance/writer
tests. If the orchestrator rules that JVM-host policy propagation belongs to a
separately named immediate subunit, Stage 4 still cannot claim the
per-operation contract complete until that subunit lands and proves parity.

## Nested scopes that inherit unchanged

These are not independent configuration acquisitions:

- `agent/turn.cljs:468-471` adds the current turn ID;
- `eval.cljs:4457-4469` adds the commit observer;
- `eval.cljs:4693-4701` adds the test-runner recursion flag;
- `eval.cljs:5277-5286` re-establishes eval identity, provenance, and namespace;
- `execution/runtime.cljs:430,583` reads the outer pinned value;
- `instrument.cljc:136-149` injects agent, configuration, and namespace;
- `agent/run.cljs:641-648` changes only the message's acting agent;
- `web/serve.cljs:1484-1500` changes only the resume target agent; and
- state reconciliation and agent/context renderers consume the outer scope.

Their focused tests should prove merge inheritance, but adding a database or
configuration read at each site would duplicate the mechanism and risk mixed
bases.

Two current “nested” sites need a source correction rather than unconditional
inheritance: the loop beat at `agent/loop.cljs:422-427` and typeahead projection
at `ai/typeahead.cljs:824-827` each override only `::db/db`. If their supplied
value is the root operation's value, remove the override. If it can differ,
their caller must supply the complete acquisition pair. The same audit applies
to any post-U4 call site found by the source sweep.

## Schema and API disposition

Stage 4 registers only the acquisition response needed to make basis pairing
explicit. It does **not** register or rename the final closed ambient carrier.
That carrier belongs to the later
[[als-tx-meta-unification-boundary-2026-07-20]] source-atomic cut after the
post-U4 inventory.

The current `::db/tx-context :map` remains temporarily permissive so existing
nested keys continue to merge. Existing public context functions lack complete
Malli schemas; do not partially “fix” that by inventing a temporary closed
Stage-4 carrier. The later operation-context unit registers the final supported
key set and atomically renames all carrier APIs. Stage 4 must nevertheless use
only registered namespaced values:

- `:seon.db/db` → `:seon.db/db`;
- `:seon.config/configuration` → `:seon.config/singleton`;
- `:seon.agent/id` → `:seon.agent/id`;
- `:seon.db/user` and `:seon.db/process` → `:seon.db/ref`;
- `:seon.eval/ns` → `:seon.eval/ns`;
- `:seon.agent.turn/current-id`, `:seon.db/on-commit!`, and
  `:seon.test.runner/running?` retain their current nested-scope ownership and
  are inventoried again before the later closed schema lands.

The acquisition function returns the existing canonical `::db/error` branch
on database, missing-singleton, or decoding failure. Callers whose failure is a
core startup/admission fault may convert that value at their existing boundary;
agent-facing operations return it unchanged.

## Source-atomic implementation order

1. Retain `b7808e35` as the U4 baseline and obtain an explicit handoff before
   changing its released host paths.
2. Finish Stage-4 route authority and reactive-router cuts, or receive an
   explicit handoff for the single request-dispatch seam. Do not edit
   `web/router.cljs` concurrently with those owners.
3. Add the registered `seon.db/acquire-operation!` pair and focused basis/error
   tests without changing ambient behavior.
4. Convert execution-child invocation first, because it is the shortest
   independent proof that a pinned database and its singleton survive an
   isolated process operation for both compiled and authored functions.
5. Convert turn/loop/wake/schedule/ticker boundaries, eliminating the ticker's
   captured singleton.
6. Convert client boot, attached start, and Shadow callbacks; reacquire after
   config reconciliation rather than treating desired manifest data as a
   database-basis result.
7. Convert the single post-route-cut web dispatcher.
8. Search every `with-tx-context`, `with-agent`, `without-agent`,
   `error/with-configuration`, and configuration acquisition. Classify every
   remaining site as root or inheriting nested scope.
9. Only when that search is complete, delete
   `db/install-configuration-context!`, its client call, and
   `internal/enter-tx-context!`. Search `src/` for zero `enterWith`.
10. Run focused and live falsifiers. Close
    [[../../../seon/issues/bun-enterwith-toplevel-segfault]] with the deletion
    proof. Leave [[../../../seon/issues/als-unify-tx-meta]] open for the later
    carrier convergence.

Steps 3-9 are one coherent source unit. A commit that deletes the installer
while one root boundary still relies on it is invalid; a commit that keeps the
installer after all roots are covered retains an unbounded stale snapshot and
the Bun bug class.

## Completed post-U4 reread

The required reread completed against `b7808e35`:

1. `host.clj` accepts one pinned database value in each invocation and returns
   it on terminal frames, but `run-invocation!` does not bind it for sci reads;
2. `host/context.clj` binds `*agent-id*`, applies identical user/process refs to
   reads and transaction metadata, and preserves exactly-once receipts, but
   resolves head independently in every query, pull, and transaction wrapper;
3. `host/record.clj` is pure transaction-data construction and adds no ambient
   state or configuration read;
4. `db/id.cljc` adds the public pure `candidate-manifest` seam; generated
   candidates remain protocol data stripped before Datahike and add no new
   transaction-metadata key;
5. the U4 conformance and real-writer tests prove recording, provenance,
   generated IDs, and replay, but do not assert invocation-database identity or
   resource-policy fields on host reads; and
6. the complete CLJS carrier search still finds the three ALS constructions,
   boot `enterWith`, configuration-only client acquisition, database-only
   nested overrides, and the execution-runtime nil database override listed in
   this report.

Implementation must repeat the search at its actual HEAD because Stage-4 route
and reactive cuts precede the web boundary. The durable U4 contract and gap no
longer require another design audit.

## Focused falsifiers

The unit is rejected unless focused tests prove all of the following:

1. `acquire-operation!` sends the singleton pull with the exact database value
   it returns. Missing or failed singleton acquisition returns canonical error
   data and opens no operation scope.
2. Two concurrent `.run` operations with different database values,
   configurations, and agents retain their own values through an await,
   `.then`, nested async function, and timer. Neither leaks to its caller.
3. A nested transaction-context delta inherits the outer database and
   configuration byte-for-byte. A production source sweep finds no context map
   that associates, replaces, or clears `:seon.db/db` without the matching
   configuration; focused loop, typeahead, and execution tests prove no mixed
   pair is observed.
4. A configured query and pull limit appears on the authority request; an
   explicit operation limit still wins.
5. A config transaction does not alter an already-entered operation. A new
   operation acquires the new singleton and sends the new limit.
6. A compiled pinned-database execution-child call and an authored call both
   observe the singleton from their invocation database; neither falls back to
   code defaults merely because program preparation was skipped.
7. The JVM-host invocation validator requires the full configuration. Two sci
   reads and one write use the invocation database value, query/pull requests
   carry its configured limits, and U4's exact user/process provenance plus
   idempotency receipt behavior remain unchanged.
8. Turn, wake, scheduled fire, retired-child recovery, ticker, resume, Shadow
   callback, boot, and web request tests each capture one pair at their root and
   prove representative inner reads inherit it.
9. `install-ticker!` retains no configuration; consecutive ticks can observe
   different committed singleton values.
10. Config reconciliation followed by recovery/initial-agent work reacquires
   from the reconciled database value. The desired manifest map alone does not
   masquerade as acquired runtime configuration.
11. A source sweep finds no `enterWith`,
    `install-configuration-context!`, boot configuration install, ticker
    configuration capture, or private configuration-only acquisition that
    loses its database basis.

Extend existing owners rather than adding a runner:

- `test/seon/db_remote_contract_test.cljs` — acquisition basis, read limits,
  concurrent/nested ALS;
- `test/seon/execution/runtime_test.cljs` and execution integration drivers —
  compiled/authored child parity;
- `test/seon/agent_loop_test.cljs`, turn tests, and schedule tests — loop,
  wake, scheduled, recovery, and ticker boundaries;
- `test/seon/client_initialization_test.cljs` — boot/reconcile/reload order;
- `test/seon/host_conformance_writer_test.clj` and
  `test/seon/host_registry_writer_test.clj` — JVM invocation basis, policy,
  provenance, receipt, and recording parity;
- router/serve focused tests after the route cut — one acquisition per request;
  and
- `test/seon/instrument_inject_test.cljs` — explicit argument precedence over
  context injection remains unchanged.

## Live falsifiers and graduation evidence

At one frozen source HEAD and a ready default cluster:

1. record operation A's database basis transaction and configured query limit;
2. while A remains open, apply a different singleton query limit through the
   ordinary explicit config operation;
3. prove A still sends its original limit and database value;
4. begin operation B and prove its database basis is newer and its authority
   request carries the new limit without restart;
5. run overlapping operations for two agents and prove database,
   configuration, identity, read attribution, and write provenance do not
   cross;
6. run one compiled execution-child database read and one authored eval read
   and prove both use their invocation's singleton limits;
7. issue a real web request after the route cut and prove its handler's read
   uses the request pair;
8. let two ticker passes straddle a live config apply and prove only the second
   observes the update;
9. run one committed U4 JVM-host sci invocation and verify the final ruling for
   its read policies plus exact user/process provenance; and
10. inspect current-generation logs for no unhandled rejection, ambient
    leakage, missing singleton, or instrumentation failure.

Then run the focused CLJS owners and include this source unit in the program's
frozen CLJS, writer, and operator gates plus default/ACME live graduation. The
Stage-4 row remains open until route, reactive, launch/config, environment, and
ACME acceptance gates also pass; this report closes only the first dependency
contract.

## Overlap with later operation-context ALS

This unit establishes the final **population** of the carrier: pinned database
value, full configuration, identity, and provenance at every root. It does not
establish the final carrier API.

After Stage 4 and the post-U4 reread, the Stage-5 unit described by
[[als-tx-meta-unification-boundary-2026-07-20]] atomically:

- folds `agent-context` into one `operation-context` ALS;
- keeps `read-evidence-context` separate;
- registers the final closed operation map;
- renames every old context API without compatibility aliases;
- makes `without-agent` dissociate only identity while preserving the acquired
  database/configuration pair; and
- projects only registered durable transaction metadata onto writer requests.

Combining the units is allowed only under one owner and the union of both proof
matrices. Otherwise, propagation lands first. There is no valid intermediate
state in which some root boundaries use a new operation carrier while others
still depend on the old boot `enterWith` snapshot.
