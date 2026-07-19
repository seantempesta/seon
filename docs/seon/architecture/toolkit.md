---
type: architecture
status: active
tags: [architecture, agent, capability, schema]
---

# Agent toolkit — editable composition over a protected substrate

> **Target design** (present tense). Exact function signatures, schemas, and
> docstrings are discoverable program facts. Implementation state, gaps, order,
> and evidence live only in [[roadmap]].

Seon gives an agent ordinary Clojure functions over namespaced data. Protected
`seon.*` namespaces own runtime safety, database access, filesystem and network
policy, lifecycle, evaluation, and web boundaries. The small `my.*` layer owns
editable domain composition and teaching examples. It does not duplicate the
protected implementation or invent a second tool protocol.

## Contract

- Every public function has a complete Malli input and output schema.
- Agent-facing API-like functions accept and return one namespaced map.
- Ordinary data-processing functions may use fully specified named positional
  arguments.
- Optional values are absent, never stored as nil.
- An agent or user failure returns an error value. Persisted forensic blame is
  `:seon.error/fault :agent` or `:core`; no second `kind` taxonomy decides blame.
- Generated identities come from `seon.db.id/allocate!`. Callers do not invent
  timestamp IDs; human-visible agent IDs are readable word IDs.
- `seon.db` is the sole database API. Reads use the injected frozen database
  value; writes cross the typed database protocol to the JVM writer.
- Exact contracts remain colocated with code and enter context through the
  program graph. This document owns namespace purpose and boundary, not a
  signature copy that can drift.
- Agent-callable eligibility is explicit colocated function metadata, persisted
  as the optional positive `:seon.fn/agent-facing?` program fact. Public source
  remains indexed for inspection, but compact cards and function menus include
  only eligible, non-private functions with complete schemas. Redefinition
  without the metadata retracts stale eligibility.

## Two layers

| Layer | Ownership | Mutation policy | Purpose |
|---|---|---|---|
| Protected substrate | `seon.*` | changed as core source, never redefined by an agent | enforce capabilities, schemas, bounds, database and runtime contracts |
| Editable composition | allowed application namespaces, conventionally `my.*` | ordinary cluster-shared program facts agents may extend | compose domain data, plan, canvas, skills, applications, and reusable helpers |

An agent's home requirements expose only the protected capabilities and `my.*`
namespaces appropriate to that agent. Root receives a complete curated
home-require scalar with lifecycle and navigation capabilities; ordinary agents
do not inherit those grants. A namespace's full source becomes context when it
is current. The entire toolkit is not rendered unconditionally every turn.

## Intended `my.*` corpus

| Namespace | Owner and purpose |
|---|---|
| `my.blob` | SHA-256-addressed large-content storage and bounded reads |
| `my.canvas` | the one agent-facing focal canvas and interaction API |
| `my.data` | small data transformation and presentation composition |
| `my.kb` | global knowledge-domain schemas, database examples, and recall composition |
| `my.ns` | discovery over namespace, function, schema, and test program facts |
| `my.plan` | per-agent plan tree and its derived document/render |
| `my.skills` | canonical skill facts plus explicit import/list/load/unload |
| `my.ui` | reusable Hiccup and control composition over the public render shapes |

Filesystem, search, shell, web, test-running, lifecycle, scheduling, and eval
capabilities remain protected `seon.agent.*`, `seon.test.*`, or other `seon.*`
namespaces selected by home requirements. A future editable wrapper enters the
table only with one real owner and an implementation PRD.

The per-agent home namespace `my.agent.<id>` is a safe starting namespace, not
a code silo or ownership scheme. An agent may author a coherent application in
`my.orders`, `my.customers`, `my.reporting`, or any other allowed namespace.
Those committed functions, schemas, tests, declarations, and require edges are
one shared program graph and become available to every child through program
deltas. Source-transaction provenance records the author independently from
the function's namespace.

As the program grows, an ordinary database ref may assign a resident agent
stewardship of a namespace. Stewardship means sustained attention, not
exclusive edit authority: every agent can call and improve shared functions.
An agent that finds a bug fixes it and adds the regression test immediately;
it never waits for or forwards the defect to the steward. The steward derives
callers, input shapes, failures, resource evidence, changes by other agents,
and tests from the shared graph and continuously improves the namespace behind
its published schemas. Every change uses the same validation, instrumentation,
and test gate. Namespace names never encode agent IDs, and a steward process
holds no private copy of the code.

Root uses `seon.agent/set-namespace!` to change that ref for an existing agent;
an ordinary agent may use it for itself or a descendant. The operation does
not rename or recreate the agent. If the assignment transaction is newer than
the latest successful eval transaction, the assigned namespace becomes the
next turn's current namespace. Subsequent `in-ns` movement remains ordinary
REPL history.

## Protected capabilities

### Database and program graph

`seon.db` owns query, pull, eager entity data, index cursors, ordinary database
values, native-shaped transaction reports, and CAS work fences. `seon.schema` owns registered shapes and the
Malli-to-Datahike bridge. `seon.eval`, `seon.ns`, and the program graph own code
lookup and evaluation. `my.kb`, `my.ns`, and `my.plan` compose those contracts;
they do not bypass them.

Every `seon.db/query` and `seon.db/pull` runs with hard synchronous work,
result-node, and shallow-weight ceilings at the maintained Datahike executor.
Namespaced request options can lower those bounds for a deliberately small
operation but cannot raise the application ceiling. Budget exhaustion is a
structured error value; a semantic query `:limit` remains ordinary result
semantics and is not mistaken for a work or materialization bound.

### Host and network effects

Filesystem, search, shell, fetch, and web search are protected capability
namespaces. Their own schemas and policy checks name allowed paths, domains,
deadlines, output bounds, and errors. An agent sees one only when its curated
home requirements expose it. A thin editable wrapper may compose returned data,
but never weakens the protected check.

### Lifecycle, messages, schedules, and sessions

Agent birth, run control, termination, cross-agent messaging, scheduling, and
browser-session navigation stay in the protected namespaces that own their
database facts. Roles are capability sets rather than stored entity kinds. Root
can discover the elevated functions it receives; each operation still enforces
its caller and data invariants.

### Tests

Code correctness uses the existing pod, database-server, and operator runners.
Agent/model behavior uses Inspect AI. A toolkit function may request the public
test operation, but it does not own another test registry, result history, or
runner.

## Compositional data shapes

Composition works because functions share registered shapes rather than because
the toolkit standardizes every domain into a generic wrapper:

- `:seon.db/ref` is the one entity reference shape.
- Namespaced request and response maps retain their domain meaning.
- Bounded collection responses name their items, cursor, and total/remaining
  information when known; partial data never appears complete.
- Failure responses carry an explicit `ok?` field in their owning namespace and
  a structured `:seon/error` value or bounded message.
- A large value remains addressable through its result symbol or blob hash, so
  a clipped view does not destroy the value.

Function schemas are the query substrate for discovery: input and output shapes
join functions to the data an agent holds. Namespace cards and current source
explain the contract close to the code. Manual architecture prose does not
enumerate every arity.

## Namespace responsibilities

### `my.plan`

`my.plan` owns the per-agent plan tree described in [[data-model]]. It stores
intent and dependencies as facts, derives roll-up and next work, and renders one
AI/HTML twin. Reconciliation accepts one EDN tree shape. It does not parse a
second markdown plan format or duplicate open plans with the same title.

### `my.kb`

`my.kb` teaches the ordinary schema/register/transact/query/pull path and owns
shared knowledge provenance shapes. Domain knowledge lives in the namespace
that owns its attributes. KB rows without an agent ownership ref are global to
the cluster; no inventory projection or stored entity kind is required.

### `my.ns`

`my.ns` derives compact namespace and function cards from committed program
facts. It helps an agent choose code to inspect without copying the program
graph into a second registry or permanently rendering every namespace.

### `my.canvas` and `my.ui`

`my.canvas` is the permanent agent-facing API for the focal shared value.
`my.ui` provides reusable Hiccup/control composition. Both produce ordinary
render data consumed by the one guarded render-unit engine in [[ui]]. Buttons,
inputs, selects, toggles, and forms call registered functions through the one
browser capability gate; neither namespace touches SSE connections.

### `my.skills`

`my.skills` stores canonical imported skill facts and supports explicit loading
through the ordinary block mechanism. Importing a corpus does not inject a
standing skills block. Default capability discovery remains namespace-led and
pull-first as described in [[context]].

### `my.blob`

`my.blob` is a content-addressed append-only file tier beside the cluster
database. A blob name is the SHA-256 hash of its bytes; the database projection
stores hash, estimated tokens, media hint, and recorded time. Reads offer bounded
line windows and honest totals; full retrieval is explicit. One process-local
storage view supplies a writable directory followed by ordered read-only bases.
A normal cluster has no bases. A lifecycle-managed branch writes its private
overlay while reading the source archive, and every read verifies that the bytes
still hash to their name.

The target does not claim zstd compression, garbage collection, remote
placement, or promotion materialization. Those policies belong to the database
and blob lifecycle PRDs. See [[data-model]] and [[observability]].

## Errors and bounds

Every capability bounds work at its owning edge: database reads and result
materialization, filesystem bytes, process output, network response bytes,
render tokens, and wall-clock duration. A bound returns an addressable partial
value or structured error; it never silently reports a partial result as the
whole.

`:seon.error/fault` is the persisted forensic blame axis:

- `:agent` means the agent-authored call, data, or function owns the correction.
- `:core` means the runtime, schema publication, or protected boundary owns it.

Optional diagnostic categories may refine a record, but do not replace this
axis. Core publication/readiness failures follow the configured escalation at
their transition; ordinary agent mistakes remain values and do not wedge the
pod.

## See also

- [[architecture]] — topology, vocabulary, and cross-cutting invariants.
- [[data-model]] — attributes, refs, plans, blobs, errors, and provenance.
- [[context]] — namespace-led discovery and the minimal context gradient.
- [[ui]] — render twins, canvas, controls, and the capability gate.
- [[agent-runtime]] — lifecycle capabilities and execution bounds.
- [[observability]] — result/blob truth and forensic records.
