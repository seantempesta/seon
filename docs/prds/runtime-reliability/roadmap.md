---
type: prd
status: active
tags: [prd, database, flow, agent]
---

# Runtime reliability refactor roadmap

## Goal

Make the proven CLJS pod reliable, fast, and easy to extend by giving every
state transition one explicit lifecycle, every durable write the minimum useful
provenance, and every reconciliation one exact transaction compiled from plain
Clojure data. The finish line is less code and fewer mechanisms: no cluster
boot during agent mint, no duplicate program scan, no ghost-pruning pass, no
config-heal pass, and no write when desired state is already present.

## Measured starting point

Three warm new-agent requests took 8.43–8.89 seconds. Roughly 7.8–8.1 seconds
was cluster maintenance rather than agent-specific work. Live profiling found:

- core function indexing: about 3.1 seconds for 1,044 rows;
- test indexing: about 0.6 seconds for 282 rows;
- schema indexing: about 0.004 seconds for 1,180 rows;
- the complete builders run again inside ghost pruning;
- replay: about 0.07 seconds;
- global instrumentation: about 0.10 seconds;
- actual agent-specific initialization: about 0.44–0.58 seconds.

The current shape is therefore a lifecycle bug first and a micro-optimization
problem second.

## State transitions to make explicit

| Transition | Durable work | Runtime work | Must not run |
|---|---|---|---|
| Cold cluster boot | Install missing schema; reconcile core and config deltas | Open DB; replay persisted code; instrument; install global services; resume agents | Duplicate snapshot/prune passes |
| Warm pod restart | Only nonempty core/config deltas | Rebuild process-local runtime; replay; instrument; install services; resume | Converged seed writes |
| Config change | Exact config delta | Normal listeners react | Core replay, program scan, instrumentation |
| Core hot reload | Exact changed core delta | Rewrap changed live functions | Cluster restart, agent mint |
| Agent mint | Agent and initial agent context | Establish namespace, wake, and host | Seed, prune, replay, global instrumentation/services |
| Agent resume | Normally none | Reconstruct one host from persisted state | Mint or overwrite initial state |
| Agent eval | Eval/result and newly authored program datoms | Evaluate and instrument only new/redefined functions | Whole-program reconciliation |

The detailed sequence and provenance questions live in
[[provenance-and-lifecycle-design]].

## Phase 0 — inventory and falsifiable baseline

- Enumerate every call made by boot, restart, mint, resume, hot reload, and eval.
- Record its inputs, queries, transactions, source reads, runtime effects, and
  downstream listener work.
- Measure cold boot, converged restart, five sequential mints, source reload,
  CPU, RSS, transaction count, and open-feed rendering.
- Add only temporary or log-derived timing needed to attribute costs; do not
  create persisted counters or status flags.

Exit: one effects matrix accounts for every observed write and process-global
side effect.

## Phase 1 — minimal provenance design

- Inventory the seven current transaction-context attributes and every query
  that consumes them.
- Start from two candidate schema primitives only: `:seon.actor/id` and
  `:seon.tx/actor`.
- Seed only actors justified by distinct queries. Current candidates are root,
  boot, and config; agents should be referenced directly if one ref schema can
  support both system actors and agent entities cleanly.
- Prove current and historical queries for boot, config, root, and agent
  assertions in the REPL against vendored Datahike semantics.
- Add another transaction attribute only when a named required query cannot be
  answered from transaction actor plus normal entity links.
- Explicitly defer credentials, principals, permissions, and authorization.

Exit: a small schema and query table explains every retained provenance fact.

## Phase 2 — lifecycle separation

- Make cluster boot/resume its own operation.
- Make agent mint a namespaced map-in/map-out operation used by the web action,
  programmatic spawn, and any CLI path.
- Make existing-agent resume distinct from mint.
- Keep common one-agent runtime setup in one helper without a `:mint?` mode.
- Install the server, database listeners, debug hooks, ticker, spawn hook, and
  provider synchronization once per runtime.

Exit: five warm mints perform no seed, prune, replay, global instrumentation,
or global-service calls and complete below one second at the current store size.

## Phase 3 — one program snapshot

- Group compiled vars and tests by source file.
- Read each source file once per compiled generation.
- Extract namespaces, functions, schemas, tests, source, and specifications into
  one deterministic desired value.
- Preserve stable creation metadata instead of manufacturing differences.
- Reuse the snapshot for core reconciliation and instrumentation selection.

Exit: an injected reader proves one read per file; unchanged source produces an
equal snapshot; the duplicate builder pass is gone.

## Phase 4 — exact transaction compilation

- Replace reassert-all reconciliation with pure functions over current and
  desired values.
- Handle additions, changed cardinality-one values, cardinality-many set
  differences, omitted managed attributes, components, and removed identities.
- Scope candidate facts using transaction actor provenance and known identity
  attributes; do not introduce entity ownership or kind attributes.
- Preserve datoms asserted by other actors on mixed-origin entities.
- Return empty transaction data when converged and do not call `transact!`.
- Use Datahike `with` only to falsify the compiled result in tests and REPL
  experiments.

Exit: exact structural tests pass and a converged warm restart advances no seed
transaction and emits no seed-driven SSE broadcast.

## Phase 5 — delete compensating mechanisms

- Use the exact core reconciliation to remove deleted and renamed core rows.
- Delete `prune-core-ghosts!` and the second full program build.
- Use exact config reconciliation to retract omitted config attributes.
- Delete singleton config healing and generic broad provenance scans.
- Remove stale comments and tests that encode the duplicate sequence.

Exit: deletion and rename drives work through ordinary reconciliation, and code
search finds one path for each behavior.

## Phase 6 — replay and instrumentation scope

- Replay persisted agent-authored code once when constructing a new runtime.
- Instrument core and replayed functions once during runtime boot.
- Instrument new/redefined agent functions at eval time.
- On hot reload, instrument changed or currently unwrapped live function
  objects without restoring the broken once-per-process gate.
- Keep async original-function detection and Malli wrapping idempotent.

Exit: instrumentation coverage is complete after boot and reload; minting an
agent invokes no global instrumentation.

## Phase 7 — seed transaction consolidation

- Calculate missing schema, core desired data, and config desired data before
  writing.
- Respect Datahike ordering where newly installed attributes must precede their
  use.
- Use one atomic transaction per genuinely distinct provenance actor when a
  delta exists.
- Never use Datahike's raw import/load path for ordinary seed state.

Exit: cold boot uses the minimum proven transaction count and converged restart
uses zero seed transactions.

## Phase 8 — system acceptance

- Cold boot and converged restart on the default cluster.
- Five sequential web-created agents with correct navigation.
- Durable multi-step planning across a pod restart.
- Schema-backed knowledge write and later retrieval across a restart.
- Core function edit, deletion, and rename through hot reload/reconciliation.
- Agent-authored function definition and instrumentation.
- Agent view, debug view, context blocks, transcript, canvas, buttons, and forms.
- Grown-store gzip SSE profiling for CPU, RSS, render caps, coalescing, and
  irrelevant-transaction suppression.
- Full CLJS suite plus focused structural tests; no context-wording snapshots.

Exit: no known lifecycle duplication, unexplained transaction churn, or
unbounded repeated render work remains.

## Commit discipline

Commit the inventory/design separately, then each behavior-preserving
extraction, lifecycle fix, reconciliation replacement, deletion, and live-proof
update. Never accumulate the whole refactor into one commit, and never keep an
old path beside its replacement after the replacement is proven.
