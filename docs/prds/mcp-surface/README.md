---
type: prd
status: active
tags: [prd, mcp, repl, flow, runtime]
---

# Seon MCP Surface

Seon should expose two primary MCP tools: one evaluation interface that can
address either the unrestricted JVM io-prepl or the selected cluster's shared
SCI context, and one structured observation interface for root-scoped cluster
inventory, core readiness, current problems, and bounded Flow state. Commit
`816cbac0f` already built most of the evaluation contract; this PRD preserves
that work and concentrates new design on honest status. The central rule is
that every response carries the exact root, cluster, namespace, and evaluation
mode or observation scope that actually ran, while a missing observation is
reported as unknown rather than promoted to health.

| Capability | BUILT | MISSING | PROPOSED |
|---|---|---|---|
| Evaluation addressing | One `eval_clj` accepts `root`, `cluster`, `namespace`, and `mode` (`jvm` or `door`); only `code` is required. | Transport failures do not always echo mode/namespace, and raw-JVM blast radius and timeout semantics are understated. | Keep this one tool and its defaults; make the executed coordinates and non-cancellation explicit in every result. |
| JVM evaluation | Stateful io-prepl session, exact one-form read, namespace selection, endpoint-replacement handling, ambiguity refusal. | A bridge timeout closes the session but does not cancel code already running in the JVM. | Retain unrestricted JVM evaluation by explicit owner intent; never claim timeout means cancellation. |
| SCI evaluation | Door mode uses the cluster's shared ctx, admission caps, print grammar, contracts, and time limit; it mutates that ctx and creates no run or receipts. | Door mode is unavailable above a live REPL when the cluster layer did not stand. | Preserve the flat error value and never fall back to JVM mode. |
| Inventory | `runtime_status` uses the operator's root-scoped `source-observations` census and reports advertisement/process-record registrations as text. | It omits the operator's full cluster-truth/roster projection, stopped branches, and observation failures. A process record alone has no prepl endpoint, so missing-advertisement recovery still needs another live advertisement for that JVM. | One public, read-only operator observation value feeds both `bin/seon status` rendering and structured MCP inventory. |
| Core health | A reachable JVM can expose the partial instance, and a ready instance has `seon.cluster/readiness` plus `seon.problems/problems`. | MCP does not compose them; prepl refusal can currently leave no layer signal and still print `alive`. | Report process, prepl, boot readiness, roster/database observation, and problem counts separately; never synthesize health from silence. |
| Flow state | Flow supports `datafy`, `ping`, and per-proc ping; Seon retains the cluster graph, per-agent graphs, work launcher, fault fan-out, and bounded `:ping-map-fn` projections. | MCP exposes none of it; the existing fleet render pings every armed agent and is not a bounded MCP response. | Add opt-in cluster Flow summary and an optional one-agent drill using Flow's own maps and vocabulary. |
| Output bounds | Eval defaults to 4,000 estimated tokens, caps at 16,000, retains the terminal event, records truncation, limits transport events to 256, and retains at most three first-party exception frames. | Status is prose-only, has no declared output-budget input, and the exception-frame source-root roster is duplicated. | Return structured status under the same budget, page cluster rows, preserve explicit truncation, and consume the program graph's source-root authority. |

## Origin and scope

The owner asked for three things:

1. one evaluation interface reaching both the raw JVM and any cluster's SCI
   context, addressed by operator root and cluster without making routine calls
   verbose;
2. inventory and status across clusters; and
3. health of core services plus Flow state.

This is a rethink of the current server, not a replacement design. The current
owner is `script/seon/dev/mcp.clj`; its evaluation expansion landed in
`816cbac0f`. The status design must strengthen the operator and runtime owners
already present rather than create an MCP-only discovery, health registry, or
Flow monitor.

## Grounding ledger

- Clojure `b18d3adc5b5f4d5d0ccea966203fb67a614d5c3d`: io-prepl reads,
  evaluates, and returns structured events at
  `reference-code/clojure/src/clj/clojure/core/server.clj:228-296`.
- SCI `6de15683b7520cc973bc9c136aec7ad3f9b3788c`: the selected cluster ctx
  and door evaluation are assembled at `src/seon/cluster.clj:1365-1370` and
  `script/seon/dev/mcp.clj:786-816`.
- core.async `dc35f3e0d7bc2eef502e77982f48641f025c8051`: `ping` returns only procs
  that reply within its timeout, with state projected by `:ping-map-fn`
  (`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:136-155,191-193`;
  implementation at `flow/impl.clj:76-86,271-279`).
- core.async.flow-monitor `fbff8424696c7080ee7dc27b55cde1659ec18d8f`:
  the dependency monitor runs a one-second polling ping loop and exposes
  inject/pause/resume controls (`reference-code/core.async.flow-monitor/src/clojure/core/async/flow_monitor.clj:71-103`).
  That is useful grounding and is not the MCP design.
- Operator discovery and truth are `source-observations`,
  `derive-cluster-truth`, and `status!` at
  `script/seon/fresh_operator.clj:816-894,925-1137,1955-2044`.
- The boot instance publishes each layer as it stands and retains a live REPL
  on later failure at `src/seon/cluster.clj:1311-1408,1410-1509`.
  `seon.cluster/readiness` derives ready-cluster facts and current problems at
  `src/seon/cluster.clj:1529-1572`.
- Current Flow ownership is the process work launcher
  (`src/seon/flow.clj:381-477`), cluster graph
  (`src/seon/cluster.clj:1100-1251`), and per-agent graph
  (`src/seon/cluster/agent.clj:240-264,337-398`). Existing fleet observation
  joins database facts and Flow pings in `src/seon/oversight.clj:87-170`.
- Filed MCP defects are
  `docs/seon/issues/mcp-eval-cannot-reach-every-jvm-cluster-and-namespace.md`,
  `mcp-parent-watchdog-can-follow-a-reused-pid.md`, and
  `mcp-frame-provenance-duplicates-the-program-source-root-roster.md`.

A read-only live probe of `default` on 2026-08-02 observed one ready cluster at
PID 4717, one agent, and two responsive cluster procs (`armer` and `render`),
both reporting Flow status `:running`. That proves the current raw surface can
read readiness and Flow ping state; it does not prove health under any failure
case.

## Decision 1 — addressing and defaults

**OWNER DECISION. Recommendation: retain the four current coordinates and
defaults.**

| Dimension | Contract | Why this default | Failure rule |
|---|---|---|---|
| `root` | Optional canonical operator-root path; default is the repository root used by `bin/seon`. | The common development deployment needs no coordinate. | Omission never triggers a machine-wide search for other roots. An explicit unknown/unreadable root returns its observation failure. |
| `cluster` | Optional cluster name; default is the MCP server's selected cluster (`SEON_CLUSTER_DIR` basename) or `default`. | This preserves the existing zero-coordinate probe and the operator's named default. | No fallback to another cluster. Zero candidates reports what exists; duplicate live identities for the selected root/name fail with all candidates. |
| `namespace` | Optional unqualified Clojure namespace symbol; default `user`. | It matches io-prepl convention and avoids assuming an agent namespace. | Invalid symbols fail before transport. JVM mode creates/refers the namespace; door mode addresses that namespace in the shared ctx. |
| `mode` | Optional enum `jvm` or `door`; default `jvm`. | JVM mode is the only target guaranteed to exist at REPL-first boot and is the diagnostic escape hatch. | Unknown mode fails before transport. Door unavailability returns a flat error value; it never falls back to JVM. |

The terse common case remains:

```json
{"code":"(+ 1 2)"}
```

It means the default root, selected/default cluster, namespace `user`, JVM
mode, default stateful session, 30-second bridge wait, and 4,000-token output
budget.

Trade-off: JVM-by-default preserves the boot-first debugging path but makes the
most powerful target the shortest call. The response echo and tool description
must therefore say `jvm` and “unrestricted” plainly. Requiring mode every time
would make the danger more visible but would directly reject the owner's
low-friction default.

## Decision 2 — two primary tools, not one tool per target

**OWNER DECISION. Recommendation: expose `eval_clj` and `runtime_status` as
the two primary tools.** Keep JVM and door evaluation as modes of `eval_clj`.
Inventory, health, and Flow observation belong to `runtime_status` because they
are one read-only observation task over the same root/cluster selection.

Separate `eval_jvm` and `eval_sci` tools would make blast radius more visible,
but they would duplicate root, cluster, namespace, session, timeout, output,
transport, and error behavior. That is exactly the drift `816cbac0f` removed.
Putting status into `eval_clj`, conversely, would force agents to author
privileged code for a routine read and would leave no bounded status contract.

`list_sessions` is bridge diagnostics, not a third system capability. Fold its
count and optional identifiers into `runtime_status`, then delete the separate
tool in the same slice. The trade-off is an immediate small caller migration in
exchange for avoiding a compatibility path and keeping the surface aligned
with the owner's three asks.

## Decision 3 — one structured status value

**OWNER DECISION. Recommendation: make `runtime_status` structured and select
one cumulative `view`: `inventory` (default), `health`, or `flow`.**

- `inventory` returns every observed cluster row under one selected root,
  including stopped persisted branches, process records, unreadable records,
  orphan JVMs, advertisements, registrations, branch/roster evidence, exact
  process identity, prepl endpoint, URL, and inconsistencies.
- `health` adds live cluster boot/readiness evidence and counts by current
  `seon.problems` family. It may cover every row on the requested page or one
  explicitly selected cluster. Web readiness means the served instance and
  advertised URL exist; it does not claim an HTTP self-probe occurred.
- `flow` requires `cluster`. Without `agent`, it adds the process work launcher,
  cluster graph, fault committer graph, armed-agent count, current-run count,
  and error-drop count. With `agent`, it additionally pings exactly that
  agent's graph. It never pings the entire agent fleet implicitly.

All three views start from one public, read-only operator observation value;
health and Flow enrich only its reachable rows through their existing runtime
owners. The operator remains the only owner of advertisement, process-record,
process-identity, root, roster, and cluster-truth derivation. MCP does not call
private Vars and does not maintain its own `alive` classifier. Operator repair
may retain `bin/seon status`'s existing reconciliation step before rendering;
MCP consumes the shared read-only truth and must not delete or rewrite an
advertisement merely because a caller asked for status.

Trade-off: one cumulative status tool keeps discovery and drill-down coherent,
but its schema has more conditional parameters than separate inventory,
health, and Flow tools. The `view` enum and validation make those conditions
visible, while separate tools would duplicate root/cluster discovery and make
cross-view disagreement possible.

### Inventory failure semantics

The status response carries observations, not one health boolean:

| Situation | Required report |
|---|---|
| Store/roster unreachable | Preserve the cluster/process observations; set the operator's roster-readable observation false and include the bounded error. Never report an empty roster. |
| Prepl refused or timed out | Process liveness remains whatever exact `(pid, start-instant)` proved; prepl reachability is false/unknown; boot, database, and Flow are unobserved. Never call the cluster `alive` as a substitute for those missing observations. |
| Stale advertisement | Report the advertisement and `:seon.fresh-operator/stale-advertisement` inconsistency with process-alive false. Do not probe or repair through it. |
| Degraded boot | Report prepl reachable and boot not ready, plus the instance attributes/layers that actually stand. Later layers are not-started or unobserved, not unhealthy. The precise boot cause is unavailable today unless another source retained it; do not invent one. |
| Ready cluster with current problems | Report readiness plus counts keyed by the existing `:seon.problems/*` family keys. Empty counts mean healthy facts only when the query actually ran. |

The current MCP classifier violates this rule: `cluster-layer-states` omits a
row when the prepl/layer probe fails, while `advertisement-row` treats only
literal `false` as degraded and maps absent `nil` to `alive`
(`script/seon/dev/mcp.clj:299-342`). Slice 2 must remove that failure class,
not add another special case.

## Decision 4 — Flow state is a bounded observation, not a monitor

**OWNER DECISION. Recommendation: use Flow's `datafy` and `ping` directly,
with a 20 ms default `ping_timeout_ms`, and require an explicit agent id for an
agent-graph drill.**

What is genuinely observable now:

- `datafy` returns graph procs, conns, executors, and datafied channels
  (`flow/impl.clj:87-91`).
- `ping` returns each responding proc's `::flow/pid`, `::flow/status`
  (`:running` or `:paused`), transform count, datafied ins/outs, and bounded
  `::flow/state` from its `:ping-map-fn` (`flow/impl.clj:271-279`).
- The expected proc ids come from the datafied graph. A missing reply therefore
  means only “no reply within the requested timeout”; it does not mean unknown
  pid, dead proc, busy proc, or healthy proc.
- Seon's proc descriptions already project bounded state: turn passes/current
  run, armer passes/armed count, fault commits/panics, and work-launcher
  capacity (`src/seon/cluster/agent.clj:181-187,452-460`;
  `src/seon/flow.clj:139-170,553-600`).
- Flow report/error channels are live lossy observation channels. The fault
  path commits durable fault facts; past report/error history cannot be
  reconstructed by draining those channels.

The response must pair expected proc ids with replies so non-responders are
explicit. Preserve Flow's keys in replies rather than translating them into a
new proc-status vocabulary. Channel output is limited to buffer count,
capacity, and closed state; opaque channel objects, closures, connections, and
executor objects never enter MCP content.

The existing 20 ms timeout is grounded in `seon.oversight`'s measured
microsecond parked replies (`src/seon/oversight.clj:34-39`). The trade-off is
that scheduler contention or an in-flight transform can yield more unknown
replies; the alternative one-second dependency default makes every missing
reply cost the full second. The caller may raise the timeout, and the response
must echo it. A timeout is a bounded observation window, not a failure verdict.

What is worth adding is one read-only observation function at the existing
cluster/agent/flow owners that projects those live values. What is not worth
adding is a background pinger, snapshot facts, a new monitor server, a Flow
registry, or an MCP copy of `core.async.flow-monitor`. The dependency monitor's
one-second polling loop and pause/resume/inject UI are the wrong lifecycle and
blast radius for an on-demand read surface.

## Decision 5 — output and context budget

**OWNER DECISION. Recommendation: keep eval's existing encoder and apply the
same declared `max_output_tokens` range (default 4,000; maximum 16,000) to
status.**

Eval's current policy is right: retain the terminal event, cap individual event
text, drop surplus nonterminal events with a count, and emit explicit retained
versus total character metadata. Door mode may first cap the SCI value under
its own admission contract; the bridge cap is a second presentation bound, not
a replacement.

Status adds three structural bounds:

- cluster rows sort by the concrete tuple `[cluster, pid, start-instant]` and
  page with `limit` (default 50, maximum 200) plus an `after` tuple;
- health returns problem-family counts, not every problem fact; raw details
  remain available through explicit JVM eval, the database, and web UI; and
- Flow returns process/cluster/fault graphs plus at most one selected agent
  graph, never every agent graph.

Every result includes `truncated?`, returned/total counts when known, and the
next `after` tuple when another page exists. The encoded response never exceeds
the requested bridge budget. The trade-off is extra calls for a very large
root or a per-agent fleet audit; the alternative is silently consuming the
calling agent's context with operational detail.

Exception projection must close
`mcp-frame-provenance-duplicates-the-program-source-root-roster.md`: the MCP
bridge consumes the program graph's one source-root authority without loading
the application runtime. Retaining `max-exception-frames = 3` is otherwise the
right default.

## Security and blast radius

Raw JVM mode can do anything the Seon JVM process can do: mutate Vars and
process-local atoms, transact against reachable connections, read or write
files, open sockets or processes, stop clusters, and terminate the JVM. It is a
development/operator capability, not a security boundary. This is intentional.

It must never silently:

- change `jvm` to `door` or `door` to `jvm`;
- choose another root, cluster, or namespace after selection fails;
- treat an unreachable prepl or missing pong as health;
- claim that closing the MCP socket session cancelled raw JVM evaluation; or
- omit the executed root, cluster, mode, namespace, and session from any
  success, evaluation error, timeout, or transport error.

Door mode has a different but still material blast radius. It evaluates under
SCI's time limit and admission rules, but it mutates the one shared per-cluster
ctx: a `def` becomes visible to agents in that cluster. It creates no run,
forms, receipts, or terminal transaction. Those two properties remain in the
tool description and response metadata.

Authentication, authorization, or remote exposure of this unrestricted tool
is outside this PRD. Nothing in this design authorizes broadening its transport
reach.

## Worked call shapes

### Common raw JVM probe

```json
{"code":"(seon.cluster/readiness (get @@(ns-resolve 'seon.cluster 'running-instances) \"default\"))"}
```

### Explicit root, cluster, namespace, and JVM mode

```json
{
  "code": "(keys @@(ns-resolve 'seon.cluster 'running-instances))",
  "root": "/srv/seon-a",
  "cluster": "acme",
  "namespace": "user",
  "mode": "jvm"
}
```

### SCI door probe

```json
{
  "code": "(def probe :installed)",
  "root": "/srv/seon-a",
  "cluster": "acme",
  "namespace": "my.agents.acme",
  "mode": "door"
}
```

The response identifies `mode=door`, states that the shared ctx was the target,
and carries no run or receipt identity.

### Named stateful JVM session and larger output budget

```json
{
  "code": "*1",
  "cluster": "default",
  "mode": "jvm",
  "session_id": "investigation-7",
  "max_output_tokens": 8000
}
```

### Root-wide inventory

```json
{"view":"inventory"}
```

### Continue a large inventory

```json
{
  "view": "inventory",
  "limit": 50,
  "after": ["cluster-049", 4812, "2026-08-02T14:11:12.123Z"]
}
```

### Selected-cluster health

```json
{
  "view": "health",
  "root": "/srv/seon-a",
  "cluster": "acme",
  "max_output_tokens": 4000
}
```

### Cluster Flow summary

```json
{
  "view": "flow",
  "cluster": "default",
  "ping_timeout_ms": 20
}
```

### One agent's Flow drill

```json
{
  "view": "flow",
  "cluster": "default",
  "agent": "root",
  "ping_timeout_ms": 100
}
```

`runtime_status` returns producer-native structured data. A sketch, not a
schema commitment:

```clojure
{:seon.dev.mcp/root "/Users/sean/src/seon"
 :seon.dev.mcp/view :flow
 :seon.dev.mcp/clusters
 [{:seon.fresh-operator/name "default"
   :seon.fresh-operator/process-alive? true
   :seon.fresh-operator/reachable? true
   :seon.boot/readiness {:seon.boot/ready-ms 1313}
   :seon.dev.mcp/problem-counts {}
   :seon.dev.mcp/flow
   {:seon.dev.mcp/expected-procs
    [:seon.cluster.agent/armer :seon.render.web/render]
    :seon.dev.mcp/replies
    {:seon.cluster.agent/armer
     #:clojure.core.async.flow{:status :running :count 0}}}}]
 :seon.dev.mcp/truncated? false}
```

The currently built bridge-only session call remains `list_sessions {}` until
the owner rules on its deprecation.

## Implementation slices

Each slice is independently landable and must retain the previous slice's
surface.

1. **Seal evaluation identity and provenance.** Preserve `eval_clj`; state raw
   capability and non-cancellation plainly; echo mode/namespace on every error;
   add exact default/no-fallback regressions; replace the duplicated source-root
   roster to close the frame-provenance issue.
2. **Unify read-only inventory.** Extract one public operator observation value
   used by `bin/seon status` and MCP; return structured, root-scoped inventory;
   distinguish refused/unobserved from ready; cover unreachable store, refused
   prepl, stale advertisement, degraded boot, stopped branch, unreadable process
   record, orphan JVM, and duplicate identity. This slice removes the current
   nil-means-`alive` defect.
3. **Compose bounded health.** For reachable instances, add boot readiness and
   problem-family counts. Every failed sub-observation becomes a bounded flat
   error value while the rest of the row survives. No aggregate `healthy?` flag
   and no stored status facts.
4. **Add bounded Flow observation.** Use `datafy` plus `ping` for the process
   work launcher, selected cluster graph, fault committer graph, and optionally
   one selected agent graph. Pair expected proc ids with replies, project only
   ordinary bounded values, and prove a busy/non-answering proc returns unknown
   without delaying the response beyond its declared ping window.
5. **Consolidate the public tool list.** Fold bridge session diagnostics into
   status, delete `list_sessions` in the same slice, and update MCP schemas and
   examples together.
6. **Harden bridge lifetime before graduation.** Close
   `mcp-parent-watchdog-can-follow-a-reused-pid.md` with a launcher identity or
   transport-lifetime event that cannot follow PID reuse. This is deliberately
   separate from surface semantics and may land in parallel, but the
   unrestricted raw tool does not graduate while its child lifetime can detach
   from the launcher.

Final graduation requires focused MCP tests, a direct stdio tools/list and
tools/call proof, and read-only live evidence for a ready default cluster plus
isolated failure fixtures for every inventory state. No production test may
read absence of a prepl reply, ping reply, database value, or error fact as
health.

## Owner ruling checklist

1. Ratify the current defaults: repository operator root, selected/default
   cluster, `user`, and unrestricted `jvm` mode?
2. Ratify two primary tools (`eval_clj`, `runtime_status`) and eventual
   deletion of standalone `list_sessions` in the same consolidation slice?
3. Ratify one cumulative status `view` enum (`inventory`, `health`, `flow`),
   with `flow` requiring a cluster and an explicit agent id for agent detail?
4. Ratify the existing measured 20 ms Flow ping window as the default, with
   missing replies reported only as unknown and a caller override available?
5. Ratify the shared operator observation value as read-only for MCP, while
   `bin/seon status` may retain its existing reconciliation before rendering?
6. Must the PID-reuse watchdog fix precede the surface's graduation, as
   recommended, or may it remain a separately tracked post-surface defect?

## Non-goals

- No implementation or production-code edit in this PRD.
- No MCP lifecycle controls: no start, stop, pause, resume, inject, restart,
  reset, refork, transact, or repair tool.
- No replacement for `bin/seon`, the web UI, `/data`, database queries, logs,
  run/receipt forensics, or raw REPL access.
- No background health polling, heartbeat, status fact, metrics history,
  snapshot fact, second discovery roster, or MCP-specific Flow registry.
- No flow-monitor web server and no draining report/error channels for history.
- No implicit ping of every agent graph and no unbounded stack, problem, graph,
  channel, or session payload.
- No automatic search across arbitrary operator roots; `root` omission means
  the configured repository root only.
- No security hardening that weakens the owner's intentional unrestricted JVM
  capability, and no authorization to expose it beyond the current development
  boundary.
