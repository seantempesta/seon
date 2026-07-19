---
type: research
status: active
tags: [research, agent, database, cljs, web]
---

# Overnight integrated graduation plan

## Outcome

Graduate one Seon system whose normal product path and Inspect AI path are the
same path: deterministic startup and reload, fail-loud development, isolated
Bun execution children, one JVM database authority, database-backed agents that
move through and share namespaces, namespace-targeted launch and messaging,
restart and recovery, reactive browser behavior, immutable packaging, and
measured modest-hardware performance.

The plan may improve when source or live evidence exposes a simpler or stronger
design. It may not add a benchmark-only runtime, parallel database API, second
renderer, compatibility namespace, transcript-regex correctness authority, or
stored projection that duplicates derivable database facts.

## Dependency ledger

- ClojureScript `1.12.145`, the shadow-cljs npm `3.4.10` CLI shim, and maintained
  Shadow source commit `615430b3`; runtime compilation and
  namespace movement are owned by `reference-code/clojurescript/`,
  `reference-code/shadow-cljs/`, `src/seon/eval.cljs`, and
  `src/seon/agent/turn.cljs`.
- Datahike and Proximum revisions selected by the root `deps.edn`; database
  values, basis transactions, commit IDs, schema, transactions, and indexed
  reads are owned by `reference-code/datahike/`, `reference-code/proximum/`,
  `src/seon/db/`, and the database-authority-mesh dependency ledger.
- Bun is the pod and execution-child runtime selected by the packaged artifact;
  spawning, supervision, HTTP/SSE, and process evidence are grounded in
  `reference-code/bun/`, `src/seon/execution.cljs`, `src/seon/client.cljs`, and
  the operator.
- Inspect AI and Inspect Evals are pinned by
  `src-inspect-ai/evaluation-sources.lock.json` and sourced from
  `reference-code/inspect-ai/` and `reference-code/inspect-evals/`.
- Current first-party namespace evidence is
  `src-inspect-ai/src/seon_inspect/milestone.py`, which already scores namespace
  movement and later-turn database recall from database-derived eval rows.
- Current agent creation and messaging owners are `src/seon/agent.cljs`,
  `src/seon/agent/message.cljs`, and their focused tests. `:seon.agent/id`
  remains immutable identity; `:seon.ns/name` remains namespace identity.

Before changing a dependency boundary, record its selected revision and exact
source lines in this ledger or the owning successor PRD.

## Current evidence

- The last complete checkpoint before the most recent focused changes passed
  ClojureScript 1,140 tests/5,078 assertions, JVM writer 219/1,821, and operator
  278/1,570.
- Focused execution proof at `fdbac56e` passes 30 tests/118 assertions.
- Source-free release `/Users/sean/seon-release-fdbac56e` built with application
  digest `2cdc32903ca304013b56a6b688adfd06c8962e1177162cdc7290d625bdd79519`.
- Selected core failures have persisted database evidence before execution-child
  exit while the pod remained available for replacement children.
- The apparent reload loss of the `:seon.db/db` Malli schema was caused by a
  diagnostic call to the execution-child-only `load-authored-program!` inside
  the pod. That call intentionally replaces the process-local schema
  projection; clean cold start and ordinary watched reload retain
  `:seon.db/db`. The remaining atomic-publication concern is exact child
  artifact admission and Shadow partial-import failure, not schema loss during
  the supported reload path.
- Three cold starts and three full supervised restarts against the same
  database completed ready with application digest `209b23e8…` and the
  `:seon.db/db` schema resolvable.
- Focused ticker/configuration proof passes agent-loop 17 tests/71 assertions,
  client initialization 7/23, runtime admission 16/94, and instrumentation
  delta 11/129. A deterministic live watchdog rejection persisted error entity
  `5907` with `:seon.error/fault :core` before the pod exited. The operator
  retained the watcher and writer and restored only the unexpectedly drained
  pod through the normal `up` path.

## Execution ledger

### 1. Deterministic startup, reload, and fail-loud development

- [x] Reproduce the missing `:seon.db/db` schema from a clean start and identify
  it as an unsupported execution-child loader call that replaced the pod's
  process-local schema projection; prove the supported cold/reload path retains
  the schema.
- [ ] Make schema and program publication atomic: ready with the complete
  admitted program or recorded core fault plus process exit. Maintained Shadow
  now reports caught Node import failures truthfully, and admission permits the
  next build to recover from `:unavailable`. Live proof rejected a guarded
  `seon.log` import, performed no rehost/ticker install, then committed all 754
  functions and returned the same pod to ready on the repaired next build.
  Exact execution-child artifact admission remains.
- [ ] **IN PROGRESS:** apply the database-selected core-fault policy
  consistently at ticker, reload, publication, render, selected-call, and
  top-level child boundaries. The ticker now retains the already-acquired
  configuration and has exact persist-before-exit and pod-only recovery proof.
  The failed-import run exposed the same missing scope in Shadow failure
  notification; reload now acquires the database configuration before recording
  either build failure or publication failure, with live crash proof next.
- [x] Prove three cold starts and three pod restarts against the same database.
- [ ] Inject one deterministic core failure at each affected process boundary;
  prove the database record precedes exit and the supervisor restores only the
  replaceable process.

Exit: no alive-but-unready pod, repeated ticker fault, incomplete application,
missing schema, or silently rendered core failure.

### 2. One complete restart-safe agent lifecycle

- [ ] Create one agent, deliver a message, and move through `my.units`,
  `my.convert`, and its home namespace using normal CLJS namespace operations.
- [ ] Register schemas, define functions and tests, transact data, and query it
  in a later turn from one immutable database value at a time.
- [ ] Redefine the function in place and prove a fresh child uses the latest
  admitted namespace source without replaying historical forms.
- [ ] Reject invalid source from the admitted program while retaining its failed
  eval evidence and a functioning repair path.
- [ ] Stop the child, restart the pod, resume the same agent, plan, namespace,
  messages, and database facts, and continue successfully.

Exit: the live journey and focused tests agree on namespace, program, database,
and recovery semantics.

### 3. Namespace-targeted agents and messaging

- [ ] Source-ground the smallest database representation connecting an agent to
  the `:seon.ns/name` it is asked to steward. Do not rename `:seon.agent/id` or
  treat stewardship as code ownership.
- [ ] Extend the existing `start!` and `delegate!` lifecycle requests with the
  optional namespace through one atomic child-birth/message transaction.
- [ ] Resolve a message addressed to a namespace to its active steward; when no
  steward exists, atomically create one and deliver the message.
- [ ] Prove two concurrent assignments produce one active steward, while every
  agent remains free to inspect and repair every namespace.
- [ ] Prove reassignment changes ordinary database facts without duplicating
  agents, program entities, runs, turns, plans, or messages.
- [ ] Prove newly committed functions, schemas, and tests become available to
  every relevant fresh child through the one program mechanism.

Exit: root can launch, find, message, stop, resume, and reassign agents by
namespace while immutable agent IDs preserve history and refs.

### 4. Live multi-agent application journey

- [ ] Root delegates `my.orders`, `my.customers`, and `my.fulfillment` work to
  separate agents.
- [ ] Agents exchange database-backed messages with explicit from/to refs.
- [ ] One agent uses functions written by another; a different agent finds and
  fixes a defect in that namespace without creating a parallel function.
- [ ] Kill one execution child during work, record the failed turn/eval evidence,
  replace it once, and continue from database state.
- [ ] Restart the pod between phases and complete the integrated application.

Exit: database queries prove agent, parent, run, turn, message, namespace,
function, schema, test, transaction, and resumed-plan relationships; transcript
prose is supporting evidence only.

### 5. Inspect AI graduation

- [ ] Pass the complete offline `src-inspect-ai` tests and oracle liveness proof.
- [ ] Pass the fixed live namespace and later-turn database-memory scenarios.
- [ ] Pass generated namespace and database variants without adding scorer
  exceptions for model answers.
- [ ] Graduate plan persistence across pod restart with the same agent.
- [ ] Add and pass namespace-targeted launch, cross-agent reuse/repair, child
  crash/recovery, and pod restart scenarios using database-derived evidence.
- [ ] Retain native Inspect logs and append the scorecard with model provenance,
  mean, pass@k, latency, token usage, and classified infrastructure failures.
- [ ] Require three consecutive fixed-scenario passes and at least four of five
  generated variants per scenario; deterministic infrastructure must pass every
  run.

Exit: Inspect AI drives the real pod door and scores durable facts/evals rather
than a special harness runtime or transcript regex.

### 6. Browser and Datastar graduation

- [ ] Root launches and messages a namespace-targeted agent from the web UI.
- [ ] Verify reactive agent, status, message, plan, canvas, and error changes
  without page reload.
- [ ] Exercise button, input, select, toggle, validation, rapid submission,
  focus preservation, and database read-back.
- [ ] Verify gzip SSE reconnect and tool reconnect after child and pod restart.
- [ ] Prove identical active renders share computation where function,
  arguments, and database value match; a slow client does not block a fast one.
- [ ] Run multiple browser tabs and concurrent feeds without duplicate actions,
  stale output, Promise rendering, console errors, or leaked interests.

Exit: the real browser journey and server-side gzip client agree on one reactive
render/feed mechanism.

### 7. Complete correctness and distribution gates

- [ ] Complete ClojureScript suite.
- [ ] Complete JVM writer suite.
- [ ] Complete operator suite.
- [ ] Complete Inspect AI Python suite and live scenarios.
- [ ] Concurrent independent-cluster isolation and restart.
- [ ] ACME downstream application journey.
- [ ] Source-free immutable release, restart/read-back, unchanged recursive
  digest, and clean shutdown with no surviving child.
- [ ] Delete obsolete code and tests revealed by the integrated proof; do not
  preserve compatibility mechanisms.

Exit: one exact source revision passes every maintained gate and product journey.

### 8. Architecture-level performance and modest-hardware proof

- [ ] Measure direct JVM reads against Bun→JVM→Bun reads for cold and cached
  query, pull, entity, and index access.
- [ ] Measure identical queries over one database value from 1/2/4/8 children,
  including shared computation and serialization costs.
- [ ] Measure transaction latency, committed database-value propagation,
  execution-child cold/warm start, and program-delta acquisition.
- [ ] Measure Datastar first render, database-update render, and 1/10/50/100
  simultaneous feeds.
- [ ] Measure private memory, proportional set size, retained heap, idle CPU,
  event-loop delay, and reclamation after warm timeout and termination.
- [ ] Optimize only material measured architecture costs; rerun correctness and
  live gates after every accepted simplification.

Exit: the completed architecture has explicit latency and resource evidence on
modest hardware, with no micro-optimization displacing a correctness boundary.

## Scheduling clock

- **Ordered spine:** section 1, deterministic schema/program publication and
  fail-loud process behavior.
- **Integrated proof:** clean cold/restart repetitions plus deterministic fault
  record-before-exit and supervisor recovery.
- **Dependency-ready parallel portfolio:** Inspect offline harness verification,
  namespace/agent source audit, and browser scenario design may advance without
  editing the section-1 runtime owners.
- **Next refill:** after section 1 closes, section 2 becomes the implementation
  spine; the freed evidence lane begins the namespace-targeted lifecycle
  dependency ledger.
- **Final graduation gate:** sections 1–8 are checked against one exact source
  revision, including live Inspect, browser, downstream, package, and measured
  modest-hardware evidence.

## Progress rule

After every material commit, live discovery, returned lane, or complete gate:

1. update the relevant checkbox and exact evidence here;
2. record any changed dependency or acceptance boundary;
3. keep exactly one earliest unsettled contract in progress;
4. update the program roadmap if order or graduation evidence changed; and
5. commit the coherent documentation update with the owning source or proof.

Do not mark a section complete from focused tests alone. A complete section has
its named live evidence and leaves the next section dependency-ready.
