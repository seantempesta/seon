---
type: research
status: complete
tags: [research, schema, runtime, rendering]
---

# JVM value-drill schema projection admission boundary (2026-07-20)

## Decision

Do **not** fix the first JVM map-drill failure by moving a convenient subset of
`:seon.config/*` registrations into `seon.config.schema`. That would make the
immediate unresolved `:seon.config/cap` and `:seon.config/singleton` references
compile while leaving the JVM browser's schema statuses dependent on whichever
namespaces happened to load in that process. The database already owns the
complete committed schema population; a partial portable bootstrap would hide
the real admission gap.

Also do **not** call `schema/activate-projection!` globally in the JVM host
today. The host declares JVM-private `:seon.host/*` request/session schemas that
are not part of the pod-indexed database program. Global activation replaces
the candidate population with the exact committed population; subsequent
`schema/valid-candidate-value?` checks for host startup/invoke frames would lose
those private declarations. The future promotion of the complete execution
contract to `.cljc` can remove that divergence, but Unit 1G must not silently
pull that cutover forward.

The smallest exact solution is a **retained immutable committed schema
projection in the existing JVM host lifecycle**, passed explicitly to the one
portable value producer:

1. move the pure “rows to projection” transform into `seon.schema.cljc`;
2. at `seon.host/start!`, query the complete committed `:seon.schema/form` and
   `:seon.fn/spec` rows through the existing retained writer session;
3. build one ordinary process-local Malli projection with the same
   `schema/build-projection` mechanism the pod and Bun child use;
4. retain it on the host with its database basis transaction;
5. pass that exact projection to `seon.render.value/drill-value`; and
6. after a successful host eval commits schema/function changes, reacquire and
   atomically publish a projection only when its basis transaction is newer.

The host's global candidate collector remains the admission surface for
JVM-private frame/session schemas and in-flight agent registrations. The
retained committed projection is the read surface for schema-aware value
browsing. This is the same candidate-versus-committed distinction already
owned by `seon.schema`, expressed explicitly rather than by a second registry
or a partial source bootstrap.

## Observed failure

The portable-owner work can now require `seon.render.value` on the JVM, but the
first schema-aware map drill attempts `schema/candidate-shapes`. With no active
projection, `seon.schema` falls back to building a projection from its
process-local candidate declarations. `seon.render.value` correctly references
the canonical `:seon.config/cap` and `:seon.config/singleton` schemas, while
`seon.config` exists only as `src/seon/config.cljs`; those forms were never
declared in the JVM process, so the fallback projection fails on unresolved
references.

The missing config forms are only the earliest visible edge. Even if they were
made portable, the JVM candidate set would still omit committed schemas from
core namespaces the host did not require and agent-authored registrations not
replayed into this process after restart. Returning an empty or locally loaded
schema-status set would be deterministic but false.

## Dependency ledger

| Mechanism | Current source | Grounded consequence |
|---|---|---|
| Canonical schema authority | `src/seon/schema.cljc:91-138,293-468` | Candidate declarations bootstrap loading; one immutable committed projection owns runtime reads after activation. `build-projection` is already the only compiler/index builder. |
| Pod admission | `src/seon/runtime/admission.cljs:196-290` | Queries every schema/form and function/spec row, parses them, builds one projection, then publishes it only after complete validation. |
| Bun program admission | `src/seon/execution.cljs:329-335,395-476`; `src/seon/eval.cljs:894-942` | Program acquisition includes all persisted schema and contract rows; the child builds and activates the complete projection before authored code loads. |
| JVM writer boundary | `src/seon/host/context.clj:179-317,1027-1038` | One retained physical connection and serialized request path already expose current-head query/transact. No new database client or connection is needed. |
| JVM host startup | `src/seon/host.clj:728-770` | `start!` creates the writer before the shared SCI base, graduation, contexts, and acceptor. Projection acquisition belongs between writer creation and readiness. |
| JVM eval commit | `src/seon/host.clj:316-435`; `src/seon/host/context.clj:1116-1145`; `src/seon/host/record.clj:408-455` | Schema rows and function rows commit through the one terminal transaction, but no committed projection is refreshed afterward. |
| JVM-private schemas | `src/seon/host.clj:96-166`; `src/seon/host/context.clj:78-177` | These declarations validate host process mechanics and are absent from the pod's indexed program. Replacing the global candidate registry with database-only forms would break current host admission. |
| Portable config precedent | `src/seon/client/schema.cljc` | A portable schema-only namespace is legitimate when both runtimes require those exact source declarations, but it is not a substitute for complete database projection admission. |
| Portable value owner | current uncommitted `.cljs` to `.cljc` promotion grounded by `64e19c31` | One producer can accept a process-local projection argument; no traversal or marker code is copied into the host. |
| Runtime overlap | `docs/prds/sci-execution-runtime/roadmap.md` U6 | SCI-var instrumentation remains later. This unit supplies the committed projection input U6 can consume; it does not instrument vars or promote the complete execution protocol. |

Pinned dependency versions remain Clojure `1.12.0`, Malli `0.20.0`, and the
checked-out `seon.schema.cljc` mechanism at repository head. No dependency API
needs invention.

## Why option A is incomplete

Moving **all** pure config schema declarations from `config.cljs` into a new
`seon.config.schema.cljc` could be a valid later source-organization change,
provided `config.cljs` requires it and deletes every original registration.
It would not duplicate defaults or behavior. It is not the Unit 1G fix:

- config is only one referenced schema family;
- schema-aware matching must include the complete database population, not a
  build-time list of namespaces shared with the host;
- agent-authored schemas are database facts and can change without rebuilding
  the host; and
- adding more portable declaration namespaces whenever another unresolved
  reference appears recreates load order as schema authority.

A narrow file containing only `cap` and `singleton` would be worse: singleton
references most config families, so either the file grows into a near-complete
copy/move or compilation fails at the next edge. No defaults or config
accessors belong in such a schema file, but moving declarations still does not
close committed-population parity.

## Why global option B is unsafe today

The pod can globally activate its committed projection because the indexed
CLJS program contains its complete application declarations. The JVM host's
process-global `seon.schema` candidate also contains operational schemas that
only `seon.host` and `seon.host.context` declare. `activate-projection!`
atomically replaces both candidate forms and active forms with the supplied
projection. Activating only database rows would therefore remove
`:seon.host/start-request`, `:seon.host/invoke`, context writer/session shapes,
and other JVM-local forms from candidate validation.

Merging those private forms into the “committed” projection is also wrong: it
would no longer be the database generation, its fingerprint would differ from
the pod/child generation, and process load order would again affect value
schema matching.

The explicit retained projection avoids both errors. It is an immutable
compiled view of database facts used only where database schema truth is the
input. JVM-private mechanics continue using the candidate registry until the
execution contract is genuinely portable and indexed.

## One portable rows-to-projection transform

`runtime.admission/committed-projection` currently owns a pure transform from
ordinary `[key form-string]` and `[symbol form-string]` rows to
`schema/build-projection`, but it lives in a CLJS runtime coordinator. The Bun
child repeats the same parsing while loading a program. Move that pure operation
to `seon.schema.cljc`, for example as one map-in/map-out function accepting:

```clojure
{:seon.schema/schema-rows [[schema-key form-string] ...]
 :seon.schema/function-contract-rows [[symbol-string form-string] ...]}

```

It parses with the existing platform reader, canonicalizes by key/symbol, and
calls `build-projection`. Pod admission, Bun loading, and JVM host admission
all consume it. Database queries and lifecycle mutation remain in their
runtime owners. This removes duplicated row parsing without moving I/O into
`seon.schema`.

## Host projection lifecycle

### Startup

`seon.host/start!` already creates one retained writer before it builds the
shared base or publishes the socket. Add one context operation that:

1. resolves one immutable database value;
2. executes the schema and function-contract queries against that same value;
3. returns the database value plus ordinary rows; and
4. builds the projection through the portable schema transform.

If acquisition or compilation fails, host startup fails before the UDS socket
is ready. It must not fall back to local candidates or an empty projection.

Retain an atom containing the database value and projection on the host. The
projection is process-local derived state, reconstructable from the database,
not a second schema authority.

### Schema-aware drill

Change the portable producer to accept the exact immutable projection as an
explicit input. Its schema-status work uses projection-scoped candidate,
matching, validation, and explanation functions in `seon.schema`; it never
consults ambient candidate load order. The pod/Bun caller passes its admitted
current projection, while the JVM passes the retained committed projection.

If the existing `candidate-shapes`/`matching-shapes` convenience functions do
not accept a projection, expose projection-first pure variants from
`seon.schema` and make the ambient functions delegate to them. The algorithm
and caches remain in one namespace; do not copy index traversal into
`seon.render.value` or the host.

### Accepted program change

The host terminal transaction is the durable boundary. When it successfully
tees any schema or function-contract change, reacquire the complete committed
rows and build the replacement projection after commit. Publish it atomically
only if its database basis transaction is not older than the retained one.
Concurrent agent sessions may finish refreshes out of order; comparing basis
transactions prevents a late older read from regressing the process.

An unchanged eval does no projection query. A failed/rejected registration
keeps the prior projection. A refresh failure surfaces as a core error and
prevents schema-aware drill from claiming the new generation; it must not
publish a partial merge.

## Cycle and load-order proof

The intended graph is:

```text
seon.schema.cljc
  -> Malli + platform EDN reader

seon.render.value.cljc
  -> seon.schema.cljc
  -> seon.ai.tokens.cljc

seon.host.context.clj
  -> writer protocol
  -> seon.schema.cljc (pure rows-to-projection only)

seon.host.clj
  -> host.context
  -> seon.render.value

```

`seon.schema` performs no database read and requires neither host, config, nor
rendering. `seon.render.value` requires no CLJS config behavior on the JVM.
Host context returns ordinary rows/projections and does not require host.
There is no reverse edge.

Load order stops being semantic: requiring value before the writer connection
may collect unresolved candidate references, but no schema-aware read uses
that fallback. Startup builds the complete database projection before
readiness, and drill receives it explicitly.

## Exact ownership and protected paths

This admission prerequisite owns:

- `src/seon/schema.cljc` and its CLJS plus focused CLJ tests: one rows-to-
  projection transform and projection-explicit shape APIs;
- `src/seon/runtime/admission.cljs` tests/call site: consume the shared pure
  transform, no behavior change;
- the minimum `src/seon/execution.cljs` or `src/seon/eval.cljs` call site needed
  to consume the same transform rather than parse rows independently;
- `src/seon/host/context.clj`: one current-database-value acquisition of schema
  and contract rows through the retained writer;
- `src/seon/host.clj` and focused host writer tests: startup retention,
  basis-transaction fencing, and post-commit refresh; and
- the portable `src/seon/render/value.cljc` plus CLJS/JVM value tests only to
  pass the projection explicitly.

Protected:

- `src/seon/config.cljs`: no declaration move, default change, or accessor
  change is required for this issue;
- database writer/protocol query semantics: use the existing query surface;
- SCI instrumentation/U6, execution-protocol promotion, HTTP routes, UI, and
  live-value retention/eviction beyond the already ruled Unit 1G issue;
- `src/seon/repl/internal.cljc`, unrelated worker/AI paths, issue notes, and B2
  caches; and
- any host-local schema merge, second registry, schema snapshot stored as
  datoms, or fallback to process candidates for browser status.

Because `schema.cljc`, execution loading, and host admission are shared
mechanisms, the orchestrator must coordinate their owners and freeze source
for the cross-runtime proof.

## Dependency order and current portability diff

The current `.cljs` to `.cljc` promotion can commit **before** this admission
unit only as a clearly bounded mechanical checkpoint if all of these are true:

- the full existing CLJS value/config tests remain green;
- JVM require succeeds;
- platform-scalar and non-schema-aware work-bound tests pass; and
- its commit/hand-off explicitly says schema-aware JVM map drill remains
  blocked on this report and Unit 1G may not consume it yet.

It must not archive the JVM-host issue, claim cross-runtime projection parity,
or delete the failing schema-aware acceptance. If the current commit is
presented as complete portable drill graduation, hold it and land this
admission mechanism in the same unit. A small truthful mechanical commit is
safe because it changes no host behavior and makes the next boundary easier to
test; a falsely green graduation is not.

Ordered implementation:

1. Commit or hold the mechanical value promotion under the conditions above.
2. Add the pure rows-to-projection and projection-explicit shape APIs in
   `seon.schema.cljc`; migrate existing pod/Bun consumers.
3. Acquire and retain the complete projection before JVM host readiness.
4. Pass it explicitly into the portable drill producer.
5. Refresh after committed schema/function changes with basis-transaction
   monotonicity.
6. Run the full CLJ/CLJS population and drill parity gates.
7. Only then implement JVM live-value retention and Unit 1G frames.

## Shortest falsifiers

1. Start from a fresh process whose loaded JVM namespaces omit
   `seon.config`. Require portable value, acquire committed rows, and drill a
   map. Assert no unresolved config schema and the projection fingerprint
   equals a pod projection built from the identical row fixture.
2. Seed a committed schema whose owning namespace is never required by the
   JVM host. A matching value must report that schema; a local-candidate-only
   implementation must fail this test.
3. Seed an agent-authored schema, restart the JVM host without replaying its
   registration form, and prove schema-aware drill still sees it from database
   rows.
4. Keep a JVM-private `:seon.host/*` candidate schema. After committed
   projection admission, prove host startup/invoke validation still works and
   the private schema is absent from browser matching/fingerprint.
5. Give malformed, duplicate, unresolved, and nilable persisted forms. Host
   readiness must fail before socket publication; no partial projection or
   candidate fallback is visible.
6. Commit a new schema during one host eval. Before commit, the old projection
   remains visible; after successful commit plus refresh, the complete new
   projection appears atomically. A rejected eval leaves it unchanged.
7. Race refreshes from basis transactions `t` and `t+1` so the older build
   finishes last. The retained basis and fingerprint must stay at `t+1`.
8. Run one identical map through pod/Bun/JVM projection-explicit drill. Assert
   equal ordered statuses, explanation data, omission markers, result data,
   and printed bytes.
9. Prove schema row acquisition uses one immutable database value for both
   schema and function-contract queries; no mixed-head projection can build.
10. Focused CLJS schema/admission/value suites and focused CLJ schema/host/value
    suites pass, followed by the relevant complete CLJS and writer gates at
    one source digest.

## Earliest implementation boundary

The earliest dependency-ready boundary is **complete committed projection
admission for JVM value reads**, not portable config declarations: centralize
row parsing in `seon.schema.cljc`, retain one basis-fenced database projection
in the existing host lifecycle, and pass it explicitly to the one portable
drill producer. Unit 1G transport remains downstream.
