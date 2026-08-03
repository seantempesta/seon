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

- Every public function has a complete Malli input and output schema. A
  durable agent definition requires a parseable `:malli/schema` at the
  definition-admission choke point; only scratch namespaces are exempt, and
  steering teaches register-first.
- `:any` is banned in agent contracts; a genuinely polymorphic slot references
  a named registered predicate schema (see [[data-model]]).
- Agent-facing API-like functions accept and return one namespaced map.
- Ordinary data-processing functions may use fully specified named positional
  arguments.
- Optional values are absent, never stored as nil.
- An agent or user failure returns an error value. Core failures become
  `:seon.error/fact` entities with process, proc, operation, connection, and
  optional run or agent provenance; no stored blame discriminator restates
  what those facts already establish.
- Generated identities come from `seon.db.id/allocate!`. Callers do not invent
  timestamp IDs; human-visible agent IDs are readable word IDs.
- `seon.db` is the sole core database namespace. Reads and writes accept an
  explicit immutable database value or connection, or elide custody inside a
  guarded agent evaluation and use that agent's cluster. The store and
  registry namespaces retain only physical connection, branch, and flock
  custody; system listeners remain outside the agent surface.
- Exact contracts remain colocated with code and enter context through the
  program graph. This document owns namespace purpose and boundary, not a
  signature copy that can drift.
- Every public function is agent-callable by definition. Function menus and
  program export derive the public, non-private function surface directly;
  complete schemas determine which functions can render compact contracts.
  Persisted `:refer` edges select exactly their named public schema-complete
  functions, while `:as` edges select the public schema-complete namespace
  surface.

## Two layers

| Layer | Ownership | Mutation policy | Purpose |
|---|---|---|---|
| Protected substrate | `seon.*` | changed as core source, never redefined by an agent | enforce capabilities, schemas, bounds, database and runtime contracts |
| Editable composition | allowed application namespaces, conventionally `my.*` | ordinary cluster-shared program facts agents may extend | compose domain data, plan, canvas, skills, applications, and reusable helpers |

EVERY AGENT MAY CALL EVERY FUNCTION (ruling #20). There is no per-agent
grant, curated home-require scalar, or execution allowlist: the cluster's
program graph is one live graph and any function in it is callable by any
agent in that cluster. What differs per agent is only what is RENDERED —
a namespace's full source becomes context when it is current, and the
entire toolkit is never rendered unconditionally every turn. Rendering is
a context-economy decision; it never gates execution.

## Intended `my.*` namespaces

| Namespace | Owner and purpose |
|---|---|
| `my.blob` | SHA-256-addressed large-content storage and bounded reads |
| `my.canvas` | **[TARGET]** generalized focal-canvas and interaction API |
| `my.data` | small data transformation and presentation composition |
| `my.fs` | guarded filesystem reads and writes over approved paths |
| `my.kb` | global knowledge-domain schemas, database examples, and recall composition |
| `my.ns` | discovery over namespace, function, schema, and test program facts |
| `my.plan` | per-agent plan tree and its derived document/render |
| `my.shell` | guarded process execution with bounded input, output, and duration |
| `my.skills` | canonical skill facts plus explicit import/list/load/unload |
| `my.ui` | reusable Hiccup and control composition over the public render shapes |
| `my.web` | guarded fetch, search, and browser-facing web operations |

Agent-facing tools are flat `my.*` namespaces. Pure functions return ordinary
values for the run loop to interpret. Functions whose result is a durable fact
return transaction intent that the owning system boundary commits. Only a
genuine capability request—filesystem, shell, web, model, or database
effect—enters `seon.effect/request!` with one request identity for validation,
policy, receipt, and leaf dispatch.

The default namespace `my.agents.<id>` is a safe starting namespace for a
temporary or undifferentiated agent, not
a code silo or ownership scheme. An agent may author a coherent application in
`my.orders`, `my.customers`, `my.reporting`, or any other allowed namespace.
Those committed functions, schemas, tests, declarations, and require edges are
one shared program graph and become available to every execution scope through
program deltas. Source-transaction provenance records the author independently
from the function's namespace.

An ordinary database ref assigns one owner agent to each namespace. Every agent
may call shared functions, but an agent that needs a symbol changed in another
namespace sends its owner a durable message and receives the commit or
rejection by reply. A message addressed to an unowned namespace creates an
agent and assigns that namespace on demand. The owner derives callers, input
shapes, failures, resource evidence, incoming requests, and tests from the
shared graph and improves the namespace behind its published schemas. Every
change uses the same validation, instrumentation, and test gate. Namespace
names never encode agent IDs, and an owner process holds no private copy of the
code. Ownership is a collaboration protocol, not a call-time security boundary.

Namespace creation and assignment are ordinary transactions at the cluster
agent boundary. Reassignment changes the unique namespace ref; it does not
rename or recreate the agent. The assigned namespace is the default for
subsequent eval admission, while later `in-ns` movement remains ordinary
durable REPL history.

## Protected capabilities

### Portable family cores and platform leaves

A protected capability family has one portable `.cljc` core. Its public entry
functions own call shapes, schemas, validation, response interpretation, and
effect metadata. Exactly one leaf per tier supplies native work and ambient
invocation data. Direct JavaScript or Java interop stays within that leaf or
another tier-local function; portable logic receives ordinary values.

`seon.effect/request!` is the single guarded entry for every
agent-facing tool family. There is no second guarded entry, per-family
request identity, or direct leaf binding. The flat `my.*` function supplies
ordinary request data; `effect/request!` carries the one identity through
admission, execution, receipt, and result.

The four effect classes describe replay behavior at the entry boundary:

- `:pure` is referentially transparent for its arguments, including a read at
  an explicit immutable database value.
- `:read` observes mutable external state without changing it and is safe to
  run again without a receipt.
- `:idempotent` mutates through a durable receipt. The effect owner assigns
  one request identity; explicitly reusing it addresses the same recorded
  request.
- `:external` mutates without a durable receipt, so recovery never assumes that
  replay is safe.

The effect request identity is the only request identity across admission,
leaf execution, transport, receipt, and result; families do not mint another
operation ID. The exact durable request and replay schemas remain target work
owned by the effect boundary; architecture does not invent attribute names
before that schema is admitted.

### Database and program graph

`seon.db` owns `q`, `pull`, `pull-many`, eager entity and datom data, ordinary
database values, time-travel reads, and native-shaped transaction reports.
Each function preserves Datahike's positional and argument-map forms, using
the dependency's own keys, and returns a flat error value on failure.
Datahike `:db.fn/cas` is reserved for facts two processes race to win exactly
once: plan freeze from absent to digest, and run claim from no process to the
process record (CAS-on-absence). `seon.schema` owns
registered shapes and the
Malli-to-Datahike bridge. `seon.eval`, `seon.ns`, and the program graph own code
lookup and evaluation. `my.kb`, `my.ns`, and `my.plan` compose those contracts;
they do not bypass them.

Every `seon.db/q` and `seon.db/pull` runs with hard synchronous work,
result-node, and shallow-weight ceilings at the maintained Datahike executor.
Namespaced request options can lower those bounds for a deliberately small
operation but cannot raise the application ceiling. Budget exhaustion is a
structured error value; a semantic query `:limit` remains ordinary result
semantics and is not mistaken for a work or materialization bound.

### Host and network effects

`my.fs`, `my.shell`, and `my.web` are the public filesystem, process, fetch,
and search namespaces. Their schemas and protected policy leaves name allowed
paths, domains, deadlines, output bounds, and errors. Every agent can CALL these; what a capability
itself permits (paths, domains, deadlines, bounds) is enforced by the
protected policy leaves at the guarded door, not by hiding the function
from some agents. Composition never weakens `effect/request!`.

### Packages

Each cluster keeps ecosystem dependencies in native manifests under its
`packages/` tree: npm dependencies in `package.json` and JVM dependencies in
`deps.edn`. A package's wrapper namespace is its platform leaf. JavaScript
wrappers are named `seon.packages.js.<pkg>` and JVM wrappers are named
`seon.packages.jvm.<pkg>`; the prefix is the computed build and locality rule,
so loaders and builds select a tier by namespace rather than by a hand list or
stored locality flag.

Package wrappers expose native libraries as ordinary data-shaped leaf calls.
Portable call surfaces remain in capability family cores above them, with
effect metadata per entry function. A package host therefore installs a leaf
through the same capability seam; it does not introduce a second registry,
envelope, or routing protocol.

### Agents, runs, and messages

Agent creation and run transitions stay in the protected cluster namespaces
that own their facts. Agent-facing message and run functions return pure values
that the loop interprets; they do not transact themselves. Every agent may call
every function in the cluster program graph, while the persistence and effect
boundaries enforce their own data contracts.

### Tests

Code correctness uses the existing cluster JVM, writer, and operator runners.
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
- Failure responses carry the flat error keys and may also carry an explicit
  `ok?` field in their owning namespace.
- A large value remains addressable through its result symbol or blob hash, so
  a clipped view does not destroy the value.
- A tier-local interop value remains addressable through its result symbol in
  that process. Crossing a capability seam, database, or durable receipt
  requires serializable data. An unserializable value at that boundary returns
  the flat error shape with steering to keep it local, extract data, or call the
  owning capability; it is never stringified, printed, or silently dropped.
- Live result symbols are tracked, not merely remembered: a lifecycle registry
  of database facts keyed by process-instance identity records which tier
  holds each live handle. When that platform resets or restarts, its handles
  are wiped in the same recovery, so a later reference fails as a loud
  steering error toward re-derivation instead of going silently stale.

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

`my.ns` derives compact function cards from committed program facts and updates
the one namespaces context block's explicit detail selections. `functions`
inspects one namespace without changing context; `full!` selects its complete
indexed source and `compact!` keeps its public schema/function card visible
without bodies. Both selection operations preserve every other block dial and
return errors for unknown or stale program rows. No operation copies the
program graph into a second registry or renderer.

### [TARGET] `my.canvas` and generalized `my.ui` controls

`my.canvas` selects the focal shared value and generalized `my.ui` controls
produce ordinary render data. Actions are pure values interpreted by the run
loop or genuine capability requests; no effectful eval helper mutates page
state. Exact constructors and the action route are named only when their
schemas and route contract are settled.

### `my.skills`

`my.skills` stores canonical imported skill facts and supports explicit loading
through the ordinary block mechanism. Importing skill source does not inject a
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

Protective limits are circuit breakers, not throughput governors. Each is a
schema'd configuration fact with explicit units, calibration provenance, and a
default far above legitimate measured work. Runtime code has no alternate
numeric limit. Firing records a fault and returns the flat steering shape,
naming the governing config key; it never silently sleeps, queues without
bound, or drops work. See [[laws]].

Capability failures use one flat public shape: required
`:seon.error/message` and `:seon.error/kind`, plus optional structured
`:seon.error/data`. A family may retain its own `ok? false` field and request
identity fields alongside those keys, but does not nest the error under
`:seon/error` or invent a parallel envelope.

The durable error fact records the producing process and may record the flow
proc, operation, connection, run, agent, and instrumented function. Those
connections answer who owns the correction without a parallel blame axis.
Core publication and readiness failures follow the configured escalation at
their transition; ordinary agent mistakes remain values and do not wedge the
cluster JVM.

## See also

- [[architecture]] — topology, vocabulary, and cross-cutting invariants.
- [[data-model]] — attributes, refs, plans, blobs, errors, and provenance.
- [[context]] — namespace-led discovery and the minimal context gradient.
- [[ui]] — render twins, canvas, controls, and the capability gate.
- [[agent-runtime]] — lifecycle capabilities and execution bounds.
- [[observability]] — result/blob truth and forensic records.
