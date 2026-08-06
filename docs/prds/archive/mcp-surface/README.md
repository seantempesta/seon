---
type: prd
status: archived
tags: [prd, mcp, repl, flow, runtime]
---

# Seon MCP Surface

Seon should expose one evaluation interface that addresses either the
unrestricted JVM io-prepl or a selected cluster's shared SCI context, one
structured observation interface for root-scoped inventory, health, and Flow
state, and one producer-neutral value drill shared by both. Commit `816cbac0f`
already built most of the evaluation addressing contract; this PRD preserves
it and concentrates new design on honest status and one result path. Every
response carries the coordinates and target that actually ran, absence of an
observation is never promoted to health, and every large value goes through
Seon's existing admission, print, render-value, and blob owners rather than an
MCP-only string budget.

| Capability | BUILT | MISSING | PROPOSED |
|---|---|---|---|
| Evaluation addressing | One `eval_clj` accepts `root`, `cluster`, `namespace`, and `mode` (`jvm` or `door`); only `code` is required. | Transport failures do not always echo mode/namespace, and raw-JVM blast radius and timeout semantics are understated. | Keep this one tool and its defaults; make the executed coordinates and non-cancellation explicit in every result. |
| JVM evaluation | Stateful io-prepl session, exact one-form read, namespace selection, endpoint-replacement handling, ambiguity refusal. | A bridge timeout closes the session but does not cancel code already running in the JVM. | Retain unrestricted JVM evaluation by explicit owner intent; never claim timeout means cancellation. |
| SCI evaluation | Door mode uses the cluster's shared ctx, admission caps, print grammar, contracts, and time limit; it mutates that ctx and creates no run or receipts. | Door mode is unavailable above a live REPL when the cluster layer did not stand. | Preserve the flat error value and never fall back to JVM mode. |
| Inventory | `runtime_status` uses the operator's root-scoped `source-observations` census and reports advertisement/process-record registrations as text. | It omits the operator's full cluster-truth/roster projection, stopped branches, and observation failures. A process record alone has no prepl endpoint, so missing-advertisement recovery still needs another live advertisement for that JVM. | One public, read-only operator observation value feeds both `bin/seon status` rendering and structured MCP inventory. |
| Core health | A reachable JVM can expose the partial instance, and a ready instance has `seon.cluster/readiness` plus `seon.problems/problems`. | MCP does not compose them; prepl refusal can currently leave no layer signal and still print `alive`. | Report process, prepl, boot readiness, roster/database observation, and problem counts separately; never synthesize health from silence. |
| Flow state | `runtime_status` reports the selected cluster graph through Flow ping data, pairs every expected proc with its reply, and reports a missing reply as `unknown`. The 20 ms window is a database-backed config fact. | Process work-launcher and fault-committer summaries plus explicit one-agent drill remain outside tier 3B. | Add those bounded observations without implicitly pinging the agent fleet. |
| Output and drill | `eval_clj` installs the shared projector as the cluster io-prepl `valf`; one print-node artifact derives semantic drill data and result EDN, oversized results use the existing blob threshold, `get_value` drills by EDN path/offset, and `/data` selects the same digest. The MCP-only token/character/event/frame cutoffs are deleted. | Root-wide status is still bridge-composed rather than projected through a selected value host, and MCP artifacts have no durable reference fact before explicit store collection. | Complete the status value-host boundary and retention proof; do not add another value cache or budget. |

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

## Implementation status — 2026-08-02

The first ratified implementation slice landed in `debe583d0`. A reachable
advertisement with no cluster-layer reply now reports `unknown`, remains
addressable by the raw prepl, and is never promoted to `alive`. Evaluation
transport and timeout errors now echo the attempted mode and namespace. The
focused gate passed 21 tests / 152 assertions / 0 failures / 0 errors.

The session-consolidation slice landed in `db6b46321`. `runtime_status` now
returns root-scoped session identifiers in its structured inventory value, and
`list_sessions` was deleted atomically from discovery and dispatch. Calling its
old name returns an unknown-tool error. The focused gate passed 22 tests / 156
assertions / 0 failures / 0 errors.

The next slices are blocked at owners outside the MCP lane, not at the bridge:

- Raw JVM value projection must be installed as the cluster io-prepl `valf`.
  Clojure `prepl` assigns the raw return to `*1` before emitting it, while
  `io-prepl` applies `valf` afterward
  (`reference-code/clojure/src/clj/clojure/core/server.clj:239-253,270-281`).
  Wrapping the submitted form would replace `*1` with the projection and break
  stateful session semantics. The live server is started by protected owner
  `src/seon/cluster.clj`; this lane did not edit it.
- The `/data` route already exists in `src/seon/render/route.clj`; its actual
  root selection is private `data-response` in `src/seon/render/web.clj`.
  Ownership of that selector has been granted, but its typed artifact contract
  still requires the protected global `resources/seon/schema.edn` authority.
- Complete inventory must come from a public read-only value in
  `script/seon/fresh_operator.clj`. Its current `source-observations`,
  `derive-cluster-truth`, `cluster-truth`, and `status!` functions are private,
  so the bridge cannot consume the complete roster/branch/error truth without
  violating this PRD's owner rule.
- The ratified Flow ping window has no config leaf or shipped decision yet.
  Adding the fact and its consumer crosses `resources/seon/schema.edn`,
  `config/default.edn`, and protected Flow/cluster owners.

Complete inventory, health, and Flow remain pending behind those owner seams;
the independent session data has already moved into `runtime_status`, so no
compatibility `list_sessions` path remains.

## Tier 3B implementation — 2026-08-03

The value-chain owner seams above are now implemented. `seon.sci.admit`
retains and exposes the one print node, derives its semantic value publicly,
and emits result EDN under canonical print bindings. `seon.render.value`
stores only that node with cap and optional diagnostic facts. The cluster
io-prepl installs the projector as `valf`, after Clojure has assigned the raw
result to `*1`; the tooling bridge merely marks which returns need projection.
Oversized results settle in the selected cluster's existing Konserve blob tier
and return their digest, while a storeless JVM returns the same digest and an
explicit non-retrievable remainder statement. `/data?value=…` and `get_value`
load the same artifact.

The bridge now advertises exactly `eval_clj`, `runtime_status`, and
`get_value`; session rows live in status and `list_sessions` remains deleted.
Its token, character, event-count, and exception-frame truncation owners and
tests are deleted. Runtime status adds readiness/problem and selected cluster
Flow observations. The Flow ping window is
`:seon.config.flow/ping-timeout-ms`, default 20 ms with provenance; expected
procs missing from the reply map are `unknown`.

Live proof in isolated root `tmp/mcp-value-chain-live` produced a 102,984-byte
artifact for `(vec (range 2000))`, returned digest
`482e503f48849b53e9241c98c5d151e3b29cdc6a303eca8b179e091af13ea2f5`,
drilled offset 7 as `[7 8 9 10 11 12 13 14]`, rendered the same digest at
`/data` as `showing 8–15 of 2000`, and then read raw `*1` as
`[100 101 102]`. The root was brought down after proof.

Remaining surface work is outside tier 3B: extract the complete public
operator inventory value, add work-launcher/fault-committer and explicit
one-agent Flow detail, project root-wide status through its selected value
host, and close the separately tracked parent-watchdog lifetime defect.

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
- The output defect is
  `docs/seon/issues/mcp-truncates-instead-of-using-the-value-system.md`.
  Admission returns a bounded semantic value, tagged print data, and explicit
  capped evidence (`src/seon/sci/admit.clj:421-471`); `seon.print/emit-both`
  derives text and hiccup from that print data (`src/seon/print.cljc:522-535`).
  `seon.render.value` supplies windows, path/offset links, and stable node ids
  (`src/seon/render/value.clj:19-203`), while `seon.blob` stores and verifies
  UTF-8 content by SHA-256 in an already-open Datahike connection's Konserve
  store (`src/seon/blob.clj:11-54`).
- The current chain has two material limits. `node-id` hashes
  `[agent-id, root-selector, get-in-path]`, not content, and stores no reverse
  mapping (`src/seon/render/value.clj:19-33`). `/data` selects only the schema
  or an entity and returns HTML; it cannot select a result blob
  (`src/seon/render/web.clj:1188-1211`). The existing oversized-result blob
  contains admitted tagged print data, not an independently navigable raw JVM
  object (`src/seon/cluster/loop.clj:501-524`). This PRD extends those owners;
  it does not claim the complete MCP drill already exists.

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
mode, default stateful session, and 30-second bridge wait. Output size is not a
caller dial; the selected cluster's effective value-system configuration is
the authority.

Trade-off: JVM-by-default preserves the boot-first debugging path but makes the
most powerful target the shortest call. The response echo and tool description
must therefore say `jvm` and “unrestricted” plainly. Requiring mode every time
would make the danger more visible but would directly reject the owner's
low-friction default.

## Decision 2 — two task tools plus one shared value drill

**OWNER DECISION. Recommendation: expose `eval_clj`, `runtime_status`, and
`get_value`.** Keep JVM and door evaluation as modes of `eval_clj`. Inventory,
health, and Flow observation belong to `runtime_status` because they are one
read-only observation task over the same root/cluster selection. `get_value`
is the one generic read of a value reference returned by either producer.

Separate `eval_jvm` and `eval_sci` tools would make blast radius more visible,
but they would duplicate root, cluster, namespace, session, timeout, output,
transport, and error behavior. That is exactly the drift `816cbac0f` removed.
Putting status into `eval_clj`, conversely, would force agents to author
privileged code for a routine read. Making drill a parameter on `eval_clj`
would falsely imply re-evaluation and would not naturally serve status values;
making it a status mode would make evaluation results depend on the status
tool. A small third tool is the honest separation: two producers, one shared
value reader, and no second value mechanism.

`list_sessions` is bridge diagnostics, not a fourth system capability. Fold its
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

For root-wide inventory, omission of `cluster` still means every row. The
selected/default cluster in that root is separately the value host whose
effective caps, render window, connection, and blob threshold project the
response; the envelope echoes it as `value_cluster`. MCP never silently borrows
a ready sibling when that host is unavailable. For cluster-scoped health/Flow,
the selected cluster is both subject and value host. This keeps one config
authority without making root inventory silently adopt whichever cluster
happened to answer first.

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

## Decision 5 — one value path, with retrievable overflow

**OWNER DECISION. Recommendation: delete MCP's output-token, character,
transport-event, and exception-frame caps. Both producer tools return one
shared value projection, and `get_value` drills it.** The only size decisions
are the selected cluster's effective `:seon.sci.admit/caps`,
`:seon.render.value/max-collection`, and
`:seon.config.eval.result/blob-threshold`. Those are already one configured
family; MCP adds no constants and accepts no `max_output_tokens`, `limit`, or
`after` parameter.

The result path is:

1. Admit the complete producer result once. The semantic projection and tagged
   print data retain `:seon.print/elided` markers and
   `:seon.sci.admit/capped?` evidence. Admission caps are the outer safety
   boundary: content elided there is deliberately not retained, but its absence
   is explicit.
2. Serialize one value artifact containing the admitted semantic projection,
   tagged print data, and capped evidence. This small owning-format addition is
   necessary because today's result blob stores only tagged print data, which
   can be rendered but cannot honestly support a semantic `get-in` path.
3. Compare that artifact with the existing blob-threshold fact. Inline values
   return the print text directly. Oversized values go through `seon.blob` and
   return a render-value window plus digest, serialized size, capped evidence,
   and node id. The window is structurally reduced with tagged elision until
   its serialized value payload is at or below that *same* threshold; a single
   huge scalar may therefore yield only its face/size plus the reference.
   Bytes omitted from the inline MCP response remain in the blob.
4. `get_value` loads and verifies the artifact by digest, applies
   `seon.render.data/at` to the requested `get-in` path, windows at `offset`
   using `:seon.render.value/max-collection`, and emits text through the same
   print grammar. Missing paths and missing blobs are flat error values.

This distinguishes two claims that must not be conflated: the entire *admitted
projection* beyond the inline window is retrievable; content intentionally
elided by admission caps is not. The latter is safe, recorded elision rather
than MCP data loss.

With today's configured threshold of 4,096 serialized characters, either tool
returns at most that much inline value payload plus a fixed coordinate/reference
envelope. Changing that bound means changing the existing config fact for the
cluster, not an MCP schema. Status and eval therefore have the same answer to
“too big.”

### Smallest honest owner adaptations

- **`seon.render.value`:** add the closed value-artifact and a pure projection
  operation used by renderers and non-agent callers. MCP supplies a normal
  `:seon.render/unit` with the configured caps/options and
  `:seon.render.value/root [:seon.blob/digest digest]`. No agent id is needed:
  `node-id` already admits an absent id, and rooting the address at the content
  digest makes the resulting `seon-value-*` stable for the same content and
  path. The returned reference still includes the full digest because the
  current 24-character node id is one-way and cannot retrieve anything.
- **`seon.render.data`:** retain `parse-cursor` and `at` as the one `get-in`
  vocabulary. Add no MCP cursor implementation.
- **`seon.blob`:** continue to own content-addressed put/get and digest
  verification. Its implementation needs a live Datahike connection only to
  reach the already-open Konserve store; if callers need the process-root main
  connection rather than a branch connection, broaden that contract here, not
  in MCP. It does not acquire a store, invent a cache, or open around the
  process-root flock.
- **`seon.render.web` and `/data`:** add a blob-digest root selector and pass
  the loaded value artifact through the same data/value floor. The browser URL
  remains `/data?value=<digest>&path=<EDN>&offset=N`; it is a second consumer
  of the same operation, not the implementation behind MCP.
- **`seon.cluster`:** raw io-prepl currently applies `pr-str` before MCP sees a
  return or tap value, and `:out`/`:err` bypass io-prepl's `valf`. Replace only
  that output adapter with a Seon accept function which delegates reading,
  evaluation, namespace, `*1/*2/*3`, and exception semantics to
  `clojure.core.server/prepl`, then hands its event value to the shared
  projector while the raw object still exists. This is necessary; the bridge
  cannot reconstruct a chopped or already-stringified host object. The prepl
  client thread may perform the blob I/O; SCI `:compute` evaluation and
  `seon.sci.admit` remain free of blocking writes.

Raw `:out`/`:err` can produce arbitrarily many chunks before the terminal
event. If a finite event envelope cannot be formed with today's one-shot
admission walk, the smallest required extension is an incremental collector in
`seon.sci.admit` that stops retaining at the same node/collection caps and
emits the same tagged elision/capped evidence. It must not be recreated in the
prepl adapter or MCP bridge.

Status remains structurally selective before projection: health returns
problem-family counts rather than every fact, and Flow returns the shared
graphs plus at most one explicitly selected agent graph. That is semantic
query shape, not a competing output budget. The complete resulting map then
uses the same value path as eval.

Exception projection closes
`mcp-frame-provenance-duplicates-the-program-source-root-roster.md` by deriving
first-party provenance from the program graph. It does not retain the current
independent three-frame cap; the shared admission projection records any
elision.

### When the value system has not stood

A REPL-first JVM may have neither a cluster effective config nor a live store
connection. In that state there is no truthful threshold, admission-cap set,
or blob tier. MCP must not read a config file as a shadow runtime authority,
open the flocked store, retain values in a bridge atom, or restore the string
chop. Raw JVM code still executes and io-prepl still installs its raw return in
`*1`; the tool returns a small flat `:seon.dev.mcp/value-system-unavailable`
control error with the exact missing layers and session id instead of the
value. A later call in that same session may deliberately evaluate a smaller
diagnostic after the cluster value system stands. Door mode is already
unavailable in this state. Status returns only operator observations that fit
its fixed control envelope and marks runtime enrichment unobserved; it does
not claim a retrievable status value.

Trade-off: this preserves the owner's REPL-first escape hatch and never loses a
suffix while claiming success, but a cluster that failed before config/store
cannot return arbitrary values through MCP. Making that case feature-complete
would require a second bootstrap budget and storage mechanism, exactly the
parallel system this ruling rejects.

An MCP-created blob currently has no receipt or other database fact referencing
it, so an explicit whole-store `collect!` may sweep it. Recommendation: do not
invent a durable MCP receipt or pin registry in this surface; document the
reference as valid until explicit collection and return a loud missing-blob
error afterward. A stronger lifetime guarantee is an owner decision because it
would make read-only status persist facts and requires a retention policy.

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
- return a chopped success when the selected cluster's value system is
  unavailable; or
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

### Named stateful JVM session

```json
{
  "code": "*1",
  "cluster": "default",
  "mode": "jvm",
  "session_id": "investigation-7"
}
```

### Root-wide inventory

```json
{"view":"inventory"}
```

### Selected-cluster health

```json
{
  "view": "health",
  "root": "/srv/seon-a",
  "cluster": "acme"
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
 :seon.sci.admit/capped? false}
```

### Drill an oversized eval or status value

Both producer tools return the same reference shape:

```clojure
{:seon.render.value/node-id "seon-value-30b842f0a086b7480f62a7ab"
 :seon.blob/digest "8b4c...64-hex-characters...f271"
 :seon.render.value/size 184221
 :seon.dev.mcp/root "/srv/seon-a"
 :seon.dev.mcp/cluster "acme"}
```

The client passes those coordinates back to the generic reader. `path` is an
EDN vector so keyword, string, and integer `get-in` steps retain their types:

```json
{
  "root": "/srv/seon-a",
  "cluster": "acme",
  "digest": "8b4c...64-hex-characters...f271",
  "path": "[:seon.dev.mcp/clusters 3 :seon.dev.mcp/flow]",
  "offset": 8
}
```

`get_value` returns the same value envelope and may return the same digest with
a different path-derived node id and next offset. It never evaluates code.

The browser equivalent is:

```text
/data?value=8b4c...f271&path=%5B%3Aseon.dev.mcp%2Fclusters%203%5D&offset=8
```

### REPL-first value-system refusal

```clojure
{:seon.error/kind :seon.dev.mcp/value-system-unavailable
 :seon.error/message "The form ran, but no configured value/blob path stood."
 :seon.dev.mcp/root "/srv/seon-a"
 :seon.dev.mcp/cluster "acme"
 :seon.dev.mcp/session-id "default"
 :seon.dev.mcp/missing [:seon.sci.admit/caps
                        :seon.db/connection]}
```

The currently built bridge-only session call remains `list_sessions {}` until
the owner rules on its deprecation.

## Implementation slices

Each slice is independently landable and must retain the previous slice's
surface.

1. **Land the shared value artifact and drill.** In the existing value owners,
   add one admitted artifact carrying semantic projection, tagged print data,
   and capped evidence; root render units at the blob digest; teach `/data` the
   digest selector; add `get_value` over root, cluster, digest, EDN path, and
   offset. Prove inline and blob-backed values render identically, drill by
   keyword/string/index, survive MCP response bounding, and fail loudly after
   a missing blob. No MCP bridge chop is removed until its replacement exists.
2. **Move evaluation output onto the value path.** Preserve `eval_clj` and
   io-prepl's evaluator/session behavior, but project raw JVM events before
   serialization and door results through the same artifact. Remove
   `max_output_tokens`, raw string chopping, event-count dropping, and the
   independent exception-frame cap together. Preserve explicit capped
   evidence; prove `*1/*2/*3`, namespace changes, door ctx mutation, no door
   run/receipts, and the REPL-first value-system refusal.
3. **Seal evaluation identity and provenance.** State raw capability and
   non-cancellation plainly; echo mode/namespace on every error; add exact
   default/no-fallback regressions; replace the duplicated source-root roster
   to close the frame-provenance issue.
4. **Unify read-only inventory.** Extract one public operator observation value
   used by `bin/seon status` and MCP; return structured, root-scoped inventory;
   distinguish refused/unobserved from ready; cover unreachable store, refused
   prepl, stale advertisement, degraded boot, stopped branch, unreadable process
   record, orphan JVM, and duplicate identity. Project it through the shared
   artifact with the explicitly echoed value host. This slice removes the
   current nil-means-`alive` defect.
5. **Compose bounded health.** For reachable instances, add boot readiness and
   problem-family counts. Every failed sub-observation becomes a bounded flat
   error value while the rest of the row survives. No aggregate `healthy?` flag
   and no stored status facts.
6. **Add bounded Flow observation.** Use `datafy` plus `ping` for the process
   work launcher, selected cluster graph, fault committer graph, and optionally
   one selected agent graph. Pair expected proc ids with replies, project only
   ordinary bounded values, and prove a busy/non-answering proc returns unknown
   without delaying the response beyond its declared ping window.
7. **Consolidate the public tool list.** Fold bridge session diagnostics into
   status, delete `list_sessions` in the same slice, and update MCP schemas and
   examples together.
8. **Harden bridge lifetime before graduation.** Close
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
2. Ratify two producer tools (`eval_clj`, `runtime_status`), one generic
   `get_value` drill, and eventual deletion of standalone `list_sessions`?
3. Ratify one cumulative status `view` enum (`inventory`, `health`, `flow`),
   with `flow` requiring a cluster and an explicit agent id for agent detail?
4. Ratify the existing measured 20 ms Flow ping window as the default, with
   missing replies reported only as unknown and a caller override available?
5. Ratify the shared operator observation value as read-only for MCP, while
   `bin/seon status` may retain its existing reconciliation before rendering?
6. Ratify the selected/default cluster as the explicit `value_cluster` for a
   root-wide status response, with no silent borrowing from a ready sibling?
7. Ratify refusal of arbitrary result delivery when a REPL-first JVM has no
   effective admission caps or live store connection, while preserving the raw
   result in that io-prepl session's `*1`?
8. Is an MCP blob reference valid-until-explicit-collection sufficient, as
   recommended, or should a later design persist generic value-reference facts
   and accept their retention/write cost even for read-only status?
9. Must the PID-reuse watchdog fix precede the surface's graduation, as
   recommended, or may it remain a separately tracked post-surface defect?

## Non-goals

- No implementation or production-code edit in this PRD.
- No MCP lifecycle controls: no start, stop, pause, resume, inject, restart,
  reset, refork, transact, or repair tool.
- No replacement for `bin/seon`, the web UI, `/data`, database queries, logs,
  run/receipt forensics, or raw REPL access; `/data` is only extended with the
  shared blob-digest root selector.
- No background health polling, heartbeat, status fact, metrics history,
  snapshot fact, second discovery roster, or MCP-specific Flow registry.
- No flow-monitor web server and no draining report/error channels for history.
- No implicit ping of every agent graph and no unbounded stack, problem, graph,
  channel, or session payload.
- No MCP-only token/character/event/frame budget, output suffix chop, value
  cache, pin registry, or alternate print grammar.
- No automatic search across arbitrary operator roots; `root` omission means
  the configured repository root only.
- No security hardening that weakens the owner's intentional unrestricted JVM
  capability, and no authorization to expose it beyond the current development
  boundary.
