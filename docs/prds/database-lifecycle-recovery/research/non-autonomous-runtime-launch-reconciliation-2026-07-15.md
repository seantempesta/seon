---
type: research
status: active
tags: [database, pod, flow, research]
---

# Non-autonomous runtime launch reconciliation

## Question and current result

The next database-lifecycle prerequisite is one process-local launch
capability in `seon.client`, not an operator branch-pod command. The existing
startup path must attach the selected database, reconstruct the stored program,
publish instrumentation, and start read surfaces for every launch. Boot
reconciliation, recovery, genesis, agent hosting, provider/brand sync, and the
ticker run only when the closed launch capability grants autonomy.

The 2026-07-14 branch-local audit correctly identified the runtime split and
inverse owners. Current HEAD changes two premises: commit `f34b7bda` already
provides typed native writer create/release/delete and removes physical fork,
while `seon.runtime.admission` now owns committed program publication and hot
reload. The remaining prerequisite is entirely inside the pod startup and
teardown owners.

## Dependency ledger

| Dependency or mechanism | Selected version or SHA | Grounding source | Existing Seon owner or proof |
|---|---|---|---|
| Datahike | `6f90b339768b1a02066dce3b6fcc93a200758fcc` | `reference-code/datahike/src/datahike/connector.cljc:438-514`; `connections.cljc:11-37` | `seon.client/open-database-connection!`, `seon.db/attached?`, `seon.db.replica` |
| Konserve | `df6818d43ea3363a808cd051c0d68917f1b987a9` | `reference-code/konserve/src/konserve/node_filestore.cljs:137-170,609`; `impl/defaults.cljc:300-351` | Released transitively by Datahike after writer and secondary-index drain |
| ClojureScript | `1.12.145`; official tag `r1.12.145` at `bd23d9a2475d822ea8dfd65deaa6732428b9ed25` | Exact tag object in `reference-code/clojurescript`: `src/main/clojure/cljs/core.cljc:await`, `src/main/cljs/cljs/js.cljs:eval-str`; `src/seon/repl.cljs:84-143` | `repl/ensure-bootstrap!`, native `^:async`/`await`, Shadow hot reload |
| Malli | `0.20.0` | `reference-code/malli`; `seon.schema/register!` | Closed launch input and public lifecycle function contracts |
| `superv.async` | `3e6ed755f83634c9e9bbb58707f9446420d32ce9` | `deps.edn` `:cljs` override; Datahike connector `go-try-` release path | Datahike CLJS release completion; no second async lifecycle |
| `partial-cps` | `1e119b03ea908ad925b98f9ba0a26371c65441e3` | `deps.edn` `:cljs` override | CLJS `await` translation used by connection release |
| Runtime launch | repository HEAD | `src/seon/client.cljs:299-366,712-748,2120-2150,2300-2459` | One startup, committed publication, hot-reload rehost, web/provider/ticker activation |
| Runtime admission detach | `81129753` | `src/seon/runtime/admission.cljs:240-294`; focused admission proof 14 tests/77 assertions | Closes executable admission, reconciles active wrappers to the empty projection, publishes `:starting`, and retains failure for retry |
| Agent hosting | repository HEAD | `src/seon/agent/runtime.cljs:68-130`; `src/seon/agent/loop.cljs:650-759` | Wake-listener/input inverse and the one ticker |
| Read surfaces | repository HEAD; stop dependency `9428aebe` | `src/seon/web/serve.cljs:936-1038`; `src/seon/web/datastar.cljs`; `src/seon/db/replica.cljs:630-682` | HTTP stop first closes every Datastar feed and awaits server close; replica owns feed attach/detach |

Datahike release is asynchronous in CLJS. The final owning reference first
drains its writer, then closes secondary indices, then releases the Konserve
store, marks the shared connection released, and reports any aggregate error.
`stop-runtime!` must therefore await `datahike.api/release`; merely clearing
the ambient root would fabricate cleanup.

This slice does not change or depend on compiler-internal behavior. The exact
selected ClojureScript tag was read non-destructively from the vendored Git
object database; the older working-tree checkout is not evidence for the
selected release.

## Preimplementation source gaps

`start-runtime!` has one unconditional cold path. Before it can select runtime
behavior, `open-database-connection!` calls `db/ensure-provenance!` and
`install-runtime-schema!`, both writes. The rest of the cold path then applies
the manifest, repairs interrupted runs, creates root and the initial agent,
replays the program, resumes all durable agents, starts web, synchronizes AI
and brand data, and installs the ticker.

Program replay itself is observational on success, but its broken-namespace
diagnostic writes a database log row. A non-autonomous reconstruction must
still surface that failure without writing it back to the historical branch.

Hot reload stores no launch authority. Every successful committed publication
currently rehosts every durable agent and installs the ticker. The capability
must live in the existing process-lifetime `!state` atom so a reload cannot
upgrade a forensic pod to autonomous behavior.

The inverse primitives exist but are not composed. `web.serve/stop!` closes the
HTTP server and detaches its route listener; `agent.loop` can remove one wake
listener/input and its ticker; `replica/detach!` closes feed resources; and
Datahike release closes the local connection/store. The exact set of hosted
agent ids is already process-local loop input, so a bulk inverse belongs there
instead of re-querying durable agents and guessing which ones were hosted.

## Implementation boundary

1. Add one closed `{autonomous? boolean}` launch capability, defaulting to
   autonomous for all existing callers. Store it process-locally on first
   attachment and reject a conflicting repeated launch.
2. Parameterize the one database opener with whether startup writes are
   permitted. Every launch pings, ensures/attaches the writer route, connects,
   validates allocation/preconditions, and attaches the replica. Only an
   autonomous launch installs provenance and runtime schema.
3. Keep bootstrap compilation, database program replay, committed publication,
   instrumentation, and web start common. Suppress replay database logging in
   non-autonomous mode while retaining console/error evidence.
4. Put manifest reconciliation, crash recovery, genesis, durable-agent resume,
   provider/brand synchronization, and ticker installation behind the one
   capability.
5. Gate successful Shadow reload rehosting/ticker installation on the retained
   capability. Publication and heartbeat remain common process mechanics.
6. Add one `stop-runtime!` ordered as web/route listeners, ticker and every
   hosted agent, replica/feed, executable admission/projection, and awaited
   Datahike release. Clear the ambient connection and launch capability only
   after release settles.

## Implemented result

`seon.client` now retains one closed process-local launch capability and one
serialized lifecycle phase across hot reload. The existing caller default is
autonomous. Non-autonomous cold launch still attaches the writer route and
replica, validates inherited database preconditions, reconstructs and
publishes the committed program, and starts the web read surface. It performs
none of the provenance/schema/config/recovery/genesis/host/provider/brand or
ticker effects. Broken stored namespaces continue replay, report to the
process console, and leave the database coordinate unchanged.

An attached repeated start is an idempotent readiness/status refresh: it
validates the exact retained capability, reads resumable ids, and reattaches
the replica and web owners without replay, publication, boot writes, or agent
hosting. Cold and attached starts both hold the same `starting` transition, so
start and stop cannot concurrently publish a false running phase.

The ordered inverse is now web/SSE server, ticker, every process-local hosted
agent, replica feed, runtime admission plus active instrumentation projection,
and awaited Datahike connection release. Each owner is idempotent. A failure at
any step retains the capability, ambient connection, and `cleanup-required`
phase; retry begins the same inverse again. Only a completely absent runtime
is already stopped. A runtime that claims `running` without a releasable
connection cannot fabricate successful cleanup.

`seon.db/release-connection!` is the sole Seon wrapper over maintained
Datahike's asynchronous release. It requires an explicit connection and has no
positive `:seon.fn/agent-facing?` metadata. Whole-surface indexing documents
it, while the agent toolkit excludes it under the existing positive-only
policy.

The retained combined checkpoint compiles 506 files with zero warnings and
passes the lifecycle, runtime-admission, and instrumentation-delta namespaces:
38 tests/316 assertions. The lifecycle namespace contributes 17 tests/122
assertions, including a real in-memory Datahike replay whose failed namespace
does not advance basis t, an actual admission detach/empty-projection
activation before release, exact stop ordering, same-tick transition refusal,
and failure/retry proof at web, ticker, host, replica, admission, and Datahike
release boundaries.

## Falsifiable evidence

- A mocked non-autonomous cold start reaches attach, replay, publication, and
  web start but none of the known write/host/provider/ticker functions.
- The same start reports no resumed or created agents.
- A successful Shadow reload under retained non-autonomy publishes but neither
  rehosts agents nor installs a ticker.
- Teardown invokes inverse owners in the declared order and awaits connection
  release before clearing the attachment.
- Existing autonomous startup remains the default and its focused tests remain
  green.
- The full affected CLJS gate compiles and passes; branch-qualified replica
  and operator composition remain a subsequent audited boundary.
