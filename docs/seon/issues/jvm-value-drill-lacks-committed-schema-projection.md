---
type: issue
status: open
severity: blocker
tags: [issue, agent, architecture, schema, rendering]
---

# Admit the committed schema projection before JVM value drill

## Problem

The portable value owner loads on the JVM, but its first schema-aware map drill
cannot build a projection because the JVM host has never admitted the complete
committed schema population. `seon.render.value` references canonical
`:seon.config/cap` and `:seon.config/singleton` forms; `seon.config` is
CLJS-only, so the host's load-order-derived candidate population cannot resolve
them.

The missing config declarations are only the first visible edge. A local
candidate projection would also omit committed schemas from unrequired core
namespaces and agent-authored schema rows after restart. Unit 1G and portable
schema-aware value graduation are blocked until JVM drill consumes database
schema truth rather than process load order.

## Evidence

Research report
[[../../prds/source-cleanup/research/jvm-value-drill-schema-projection-admission-boundary-2026-07-20]]
at `6177ae2e` traces the failure through the current source and records the
accepted B-prime boundary.

`src/seon/schema.cljc` uses module declarations as a candidate bootstrap and
falls back to building shape indexes from that candidate when no committed
projection is active. Pod admission in `seon.runtime.admission` and Bun program
loading in `seon.execution`/`seon.eval` acquire all persisted
`:seon.schema/form` and `:seon.fn/spec` rows before publishing their runtime
projection. `seon.host` has no equivalent acquisition or retained projection.

The direct JVM probe now succeeds at requiring the promoted portable value
namespace, then fails on a schema-aware map drill with unresolved config schema
references. Scalar/non-schema-aware paths do not falsify the problem because
they need not build the map-shape projection.

Two tempting fixes are explicitly rejected:

- moving only config registrations into portable source would repair the first
  reference while leaving schema statuses incomplete and load-order-dependent;
  and
- globally calling `schema/activate-projection!` with database rows would
  replace the candidate population and remove JVM-private `:seon.host/*` and
  host-context schemas that are not indexed by the pod program.

Merging those private forms into the database projection is also invalid: its
fingerprint and browser-visible schema population would cease to be the exact
committed generation.

## Owner

The existing schema and JVM host admission mechanisms own B-prime:

- `seon.schema.cljc` owns one portable pure transform from ordinary persisted
  schema/function-contract rows to `schema/build-projection`, plus
  projection-explicit candidate/match/explain operations;
- `seon.host.context` acquires both row sets against one immutable database
  value through its retained writer session;
- `seon.host/start!` builds and retains that projection with its basis
  transaction before publishing readiness;
- `seon.render.value/drill-value` receives the exact immutable projection as
  an explicit input; and
- after a successful host eval commits schema or function-contract rows, the
  host reacquires the complete population and atomically publishes it only if
  its basis transaction is not older than the retained generation.

The host's global candidate registry remains the admission surface for its
JVM-private operational schemas. The retained database projection is derived
process state used for database-schema reads; it is not another registry or a
stored projection.

Dependency order is:

1. portable value promotion may land only as a truthful mechanical checkpoint;
2. centralize row parsing and projection-explicit shape operations in
   `seon.schema.cljc`;
3. acquire, retain, and refresh the committed projection in the JVM host;
4. pass it explicitly to the portable value producer and prove cross-runtime
   parity; then
5. implement JVM live-value retention and Unit 1G sampling transport.

No HTTP route, UI, SCI instrumentation, raw-value fallback, config declaration
move, second registry, or copied schema-index algorithm belongs in this fix.

## Acceptance

- From a fresh JVM process that never requires `seon.config`, require the
  portable value owner, acquire committed rows, and drill a map without an
  unresolved schema reference.
- A committed schema whose namespace the JVM never loads appears in the map's
  ordered schema statuses. An agent-authored schema remains visible after host
  restart without replaying its registration form.
- Pod, Bun, and JVM projections built from the identical row fixture have the
  same fingerprint, ordered shape rows, validation result, explanation data,
  omission markers, result data, and printed bytes.
- Schema and function-contract rows are read from one immutable database value;
  malformed, unresolved, duplicate, or nilable forms fail host readiness
  before socket publication with no partial/candidate fallback.
- JVM-private host startup/invoke schemas continue validating but are absent
  from the committed browser projection and its fingerprint.
- A successful schema/function-changing eval keeps the old projection visible
  until its terminal transaction commits, then publishes the complete new
  projection atomically. A failed or rejected eval leaves it unchanged.
- When refreshes for basis transactions `t` and `t+1` finish out of order, the
  retained basis and fingerprint remain at `t+1`.
- Focused CLJS schema/admission/value tests and focused CLJ schema/host/value
  tests pass, followed by the relevant complete CLJS and writer gates at one
  source digest.
- Unit 1G does not begin and the JVM-host live-value issue does not close until
  the schema-aware cross-runtime drill proof passes.
