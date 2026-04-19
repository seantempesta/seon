---
type: milestone
status: partial
order: 1
---
# M1: Reliable Runtime

When this milestone is crossed, the system is a trustworthy execution environment. Agents run in isolated JVMs that cannot corrupt each other. All cross-boundary communication routes through a single flow topology. Datalevin survives application crashes. Startup is deterministic -- every component has a health gate, and the system either starts fully or fails with clear diagnostics. The pool self-heals without human intervention.

An operator can kill the Seon JVM, restart it, and find all data intact. An agent can crash without affecting any other agent. The flow topology is the sole routing backbone -- nothing bypasses it.

## The Scenario

An agent working on `seon.trading.signals` hits an infinite loop. Its JVM pegs a CPU core and stops responding to health checks.

1. The pool's health monitor detects the unresponsive JVM after the grace period expires.
2. The pool disposes of the dead JVM and marks the agent run as crashed in the runtime registry.
3. A pre-warmed replacement JVM is already available. The pool's auto-replenishment had kept the target size filled.
4. The operator (or orchestrator) relaunches the agent. It acquires a fresh JVM, connects to Datalevin over TCP, and picks up where it left off -- its session history is in the database, not in the dead process.
5. Meanwhile, `seon.health.metrics` and `seon.web` continued running without interruption. They never knew the trading agent died, because their flow processes are independent.

```clojure
;; The operator checks system health after the crash
(user/status)
;; => {:datalevin {:ok true, :mode :adopted, :pid 12345}
;;     :flow {:ok true, :processes 8}
;;     :pool {:ok true, :available 2, :in-use 1}
;;     :web {:ok true, :port 8080}}

```

The crash was contained. No data was lost. No other process was affected.

## What This Requires

**Flow as sole routing backbone.** Every cross-boundary call -- database writes, REPL evals, inter-namespace function calls -- routes through `topology/request!`. No side channel. The promise-register/inject/step/reply-router/deliver pattern is the only way data crosses process boundaries.

**Datalevin as a separate process.** The database server runs in its own JVM on port 8898. Seon connects as a TCP client. Killing Seon does not kill Datalevin. Restarting Seon adopts the existing database. LMDB locks are managed by the server, not by clients.

**Per-DB connection locking.** The connection manager uses `ConcurrentHashMap` with double-checked locking so that two threads never race to open the same database simultaneously. This prevents the LMDB corruption race that occurs when concurrent first-opens hit `open-kv` before the `VERSION` file is written.

**Deterministic two-phase startup.** Phase 1 brings up foundations (Datalevin, schema registry, connection manager) with no flow dependency. Phase 2 starts the flow topology with a sync barrier (`flow/ping` within 5 seconds), then the web server, code graph, and instrumentation. Failure at any point produces a clear diagnostic, not a half-started system.

**Pool self-healing.** Pre-warmed JVMs with correct memory settings. Health checks with grace period. Automatic disposal of dead JVMs. Rate-limited auto-replenishment. The pool converges to its target size without human intervention.

**Atom watches must route through flow.** Currently, ctx persistence and SSE push fire as atom watches -- side effects invisible to the flow topology. For M1 to be truly crossed, state changes in ctx must emit flow signals so that other processes can observe and react to them.

## What Already Exists

- [[vision/capabilities/agent-isolation]] -- complete. Each agent gets an isolated JVM with nREPL and Datalevin connection. TCP routing via harness. Nippy wire protocol.
- [[vision/capabilities/flow-topology]] -- complete. `topology/request!` is the universal entry point. Infrastructure flow handles writer, reader, REPL eval, reply-router.
- [[vision/capabilities/database-platform]] -- complete. Datalevin as separate JVM. Connection manager with per-DB locking. Two-phase startup with adoption.
- [[vision/capabilities/pool-self-healing]] -- complete. Health checks, auto-replenishment, rate limiting, correct JVM opts.
- [[vision/capabilities/mcp-resilience]] -- complete. Async dispatch, cancellation, non-blocking init.
- [[vision/capabilities/self-monitoring]] -- complete. Readiness gates, post-start observation, circuit breakers.
- [[vision/capabilities/resilient-writes]] -- partial. Per-batch error isolation in graph ingest, timeouts, retry. DB writer step-fn lacks circuit breaker.

## What Remains Honest

- [[orchestrator/issues/atom-watches-bypass-flow]] -- ctx persistence and SSE push fire outside the flow topology. State changes are invisible to other flow processes.
- [[orchestrator/issues/state-three-mechanisms]] -- ctx registry, runtime registry, and flow/ping each hold partial truths about namespace state. They do not sync.
- [[orchestrator/issues/no-broadcast-signals]] -- no mechanism for namespaces to emit events that other namespaces can subscribe to. Cross-namespace coordination is blocked.
- [[orchestrator/issues/no-custom-namespace-behavior]] -- all namespaces get the same behavioral mold. No extension points for custom request handling or derived state.
- [[orchestrator/issues/coupling-circular-deps]] -- three pairs of namespaces use `requiring-resolve` to mask circular dependencies.
- [[orchestrator/issues/dead-agent-helpers]] -- dead code in agent/helpers.clj.
- [[orchestrator/issues/raw-datalevin-conn]] -- agent_runner.clj bypasses `seon.db` for bootstrap connection.

The infrastructure works. Agents are isolated, the database survives crashes, the pool self-heals. The gap is that the flow topology is not yet the *sole* routing backbone -- atom watches and multiple state registries create invisible side channels.

## How to Verify

```clojure
;; System boots cleanly with all health gates passing
(user/status)
;; Every key shows :ok true

;; Flow topology handles all cross-boundary calls
(topology/request! {:seon.flow/target :seon.flow/writer
                    :seon.flow/op :transact
                    :seon.db/db-name :seon
                    :seon.db/tx-data [{:seon.test/id "verify"}]})
;; Returns successfully -- write went through flow

;; Kill and restart Seon, verify Datalevin survives
;; (shell: pkill -f seon.runner, then ./bin/run)
(d/q '[:find ?e :where [?e :seon.test/id "verify"]]
     (d/db (db/conn :seon)))
;; Data is still there

;; Pool recovers from agent death
(let [jvm (pool/acquire!)]
  (.destroyForcibly (:process jvm)))
;; Wait for health check cycle
(pool/status)
;; Pool has replenished to target size

```

**M1 is fully crossed when:** `(user/status)` shows all green AND there are zero code paths that write to Datalevin or push to SSE outside of `topology/request!`.

## Dependencies

M1 is the foundation. Nothing comes before it. Everything comes after it. Without a reliable runtime, trustworthy data (M2), uniform conventions (M3), and discoverable code (M4) have no stable platform to build on.
