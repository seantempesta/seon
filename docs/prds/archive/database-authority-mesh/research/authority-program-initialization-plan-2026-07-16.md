---
type: research
status: active
tags: [database, research, schema, cljs]
---

# Authority program initialization plan

## Decision

Promote the existing compiled-program reconciliation into database ensure. The
first Bun host that opens a cluster supplies the compiled package's current
program graph and required initial facts. The authority ensures the physical
database, compares that desired data with current facts, commits only the exact
delta, and returns the admitted database value. A fresh database therefore gets
the complete seed, a partial or older database gets a delta, and a converged
database gets no transaction.

This is one externally atomic initialization boundary, not necessarily one
Datahike transaction. Minimal provenance must exist before later transactions
can carry `:seon.db/user` and `:seon.db/process`; config also retains its own
process provenance. The database is not published to the initializing caller
until every required step succeeds.

Do not restore program replay. Each child starts with its compiled package,
activates the complete database schema/contract projection, and loads only
current runtime-authored namespace sections through `cljs.js`.

## Dependency ledger

| Dependency or mechanism | Selected source | Required behavior |
|---|---|---|
| Seon checkout | `ab98d70f28705386caa82ae7c4eb25b1e74a0299` plus the current shared execution changes | `index-core!`, `index-schemas`, the exact core-program delta, database ensure, and child namespace loading |
| Maintained Datahike | `reference-code/datahike` at `a464cd887458d2572414a6ea951c477b0981fdae` | `database-exists?`, `create-database :initial-tx`, one serialized writer, identity upsert, component retraction, immutable database values |
| ClojureScript | `reference-code/clojurescript` at `946d75f3483c0c8e784e6668bff2c71a25619a77` | `cljs.js/eval-str`, dependency analysis, recursive load, cycle detection, and load-once |
| Program graph | `:seon.ns/name`, `:seon.fn/sym`, `:seon.schema/key`, `:seon.test/sym` | Existing identity attributes and refs remain the model; no program kind, version row, or parallel registry |
| Provenance | transaction refs `:seon.db/user` and `:seon.db/process` | Boot-owned declarations are managed by current source-datom provenance; runtime-authored facts remain ordinary current database facts |
| Child loader | `seon.eval/namespace-source`, `authored-sources`, and `load-authored-program!` | One deduplicated source section per namespace; compiled code is never replayed |

Datahike applies `:initial-tx` only from `create-database`
(`reference-code/datahike/src/datahike/api/impl.cljc:50-66`). Seon calls create
only when the physical database is absent
(`src/seon/db/registry.clj:543-571`). The fixed connection initializer runs
before registry publication (`src/seon/db/registry.clj:612-702`), but its
current production composition installs only embeddings
(`src/seon/db/server.clj:272-285`).

## Existing mechanism to preserve

The desired compiled program is already derived once per boot:

- `index-core!` returns namespace rows followed by function rows from the
  compiled package (`src/seon/client.cljs:1667-1726`).
- `index-schemas` returns every registered canonical schema row
  (`src/seon/client.cljs:1728-1760`).
- `acquire-core-program!` reads the current functions, schemas, namespaces,
  require-edge components, boot-owned rows, and agent ids in one seven-member
  `execute-many` (`src/seon/client.cljs:1850-1873`).
- `compile-core-program-tx` derives additions, complete field repair, optional
  field retractions, exact require-edge replacement, and deletion of absent
  boot-owned declarations while preserving agent homes and runtime-authored
  declarations (`src/seon/client.cljs:1875-2091`).
- `commit-core-program!` submits no transaction for `[]` and retries only a
  stale database-value fence (`src/seon/client.cljs:2119-2140`).

This is already the correct empty/partial/converged algorithm. Do not add an
up-front database-empty query. A new Datahike database already contains system
and protocol schema facts, so raw emptiness is not meaningful. Absence of the
managed program identities naturally makes the exact diff return the full
desired population.

"Newer" must not mean `:db/txInstant` or a declaration's `created-at` value.
The existing diff correctly compares semantic current fields. For boot-owned
identities, the package being admitted is the desired definition. For
runtime-authored identities, transaction provenance determines ownership.
Preventing installation of an older package is a deployment/artifact policy
over the existing artifact digest, not database entity timestamp logic.

## Public and internal API shape

Keep `:seon.db.protocol.operation/ensure-database` as the one physical database
operation. Do not add a sibling initialize operation that creates an
ensure-then-initialize visibility window.

The initializing Bun host extends its existing `seon.db/open-session!` request
with one optional initialization map:

```clojure
{:seon.db/socket-path "..."
 :seon.db/database-name "default"
 :seon.db/backend :file
 :seon.db/database-path "..."
 :seon.db/initialization
 {:seon.execution/artifact-digest "<sha256>"
  :seon.db/program [{:seon.ns/name :seon.db
                     :seon.ns/source "(ns seon.db)"}
                    {:seon.fn/sym "seon.db/query"
                     :seon.fn/ns [:seon.ns/name :seon.db]
                     :seon.fn/source "..."}
                    {:seon.schema/key :seon.db/db
                     :seon.schema/form "..."}]
  :seon.db/initial-data [{:seon.user/id "user"}
                         {:my.kb.shared/id "shared"}]}}

```

The names describe existing concepts: compiled artifact identity, program graph
entity maps, and initial database facts. The program maps retain their existing
identity attributes and `:seon.fn/ns`/`:seon.test/ns` refs. There is no program
snapshot entity, kind field, namespace-order row, or stored derived digest.
The artifact digest is request evidence and admission fencing; the program
graph remains the durable authority.

Internally, `ensure-database` passes the initialization map to the registry
initializer. On an already registered physical database, the writer must still
run the idempotent initialization reconciliation for a new artifact digest;
otherwise a long-lived writer could never admit a rebuilt Bun package. Serialize
that work per physical database with the writer's existing single-write
authority. Equal concurrent requests converge on the same diff. Requests with
different artifact digests are ordered; each caller receives the database value
that admitted its own desired program or an ordinary rejection.

Only the cluster-owning Bun host supplies initialization. Execution children
open ordinary sessions after host admission and never seed, reconcile, or carry
initial data. Host startup ordering is already the owner of child admission.

## Dependency-ordered implementation

### 1. Finish declaration-order-independent schema construction

Owner: `src/seon/schema.cljc`, `src/seon/schema/internal.cljc`, and focused
schema tests.

Make `schema/register!` collect qualified, EDN-round-trippable declarations
without resolving references against the partially loaded JavaScript package.
Keep complete reference, non-nilability, entity, and function-contract
validation in `schema/build-projection`. Until the compiled package can produce
its complete schema map reliably, no authority initialization payload is
trustworthy.

Exit: namespace load order permutations produce the same complete projection;
a missing referenced schema fails before database admission.

### 2. Extract the desired program graph builder from boot orchestration

Owner: `src/seon/client.cljs` and a small pure owner colocated with the program
graph builder rather than a second registry.

Keep `index-core!` and `index-schemas` as the one compiled-package inspection
path. Add one pure function returning the initialization map from their rows,
`seed-core!`, and the current artifact digest. Compute it once before opening
the initializing session. Do not read the database and do not serialize native
compiler, Bun, or Datahike values.

Exit: the same compiled artifact produces byte-stable canonical program and
initial-data values independent of map/query order and wall clock. Remove
`created-at` from desired equality or normalize it before hashing; it is not
program identity.

### 3. Extend ensure/open request data without a second operation

Owners: `src/seon/db/protocol.cljc`, `src/seon/db.cljs`,
`src/seon/db/internal.cljs`, and their exact protocol/session tests.

Add the optional initialization map to the existing open-session and
ensure-database requests. Transit validation permits only ordinary namespaced
data. The database session still caches the returned ordinary `:seon.db/db`
value. Non-initializing child requests remain byte-for-byte the small existing
selection request.

Exit: request round-trip preserves refs, symbols, keywords, schema form strings,
and artifact digest; native values fail validation; an ordinary child request
contains no initialization data.

### 4. Move exact program reconciliation into the authority

Owners: new internal authority code under `src/seon/db/`, then
`src/seon/db/writer.clj`, `src/seon/db/registry.clj`, and
`src/seon/db/server.clj`.

Port `compile-core-program-tx` as a pure CLJ/CLJC transformation over desired
program maps plus ordinary acquired rows. Preserve all current semantics:

- compare complete function, namespace, schema, and require-edge fields;
- install missing rows and repair changed boot-owned rows;
- retract optional fields and old component require-edge entities explicitly;
- retract absent boot-owned namespace/function/schema/test identities;
- never sweep agent home namespaces;
- preserve runtime-authored current facts unless the settled compiled-identity
  protection policy rejects the initialization before writing; and
- refuse any removal when a desired identity population is unexpectedly empty.

Use direct Datahike queries inside the authority owner against one immutable
database value. Compile one transaction and commit it through the one writer.
The JVM returns ordinary transaction/database data; Datahike connections,
database records, reports, and indexes remain internal.

Exit: fresh, partial, drifted, and converged databases produce respectively a
full delta, minimal delta, repair delta, and `[]`.

### 5. Compose provenance, native schema, program, and initial facts

Owners: `src/seon/db/server.clj`, `src/seon/db/writer.clj`, existing provenance
and Malli-to-Datahike bridge owners.

Initialization order is:

1. Datahike create installs only the protocol-native schema needed to open.
2. Ensure minimal root/boot/config process facts exist.
3. Validate the complete Malli schema/program projection.
4. Derive and install the complete native Datahike attribute subset required by
   compiled schema and initial data.
5. Commit the exact compiled program plus required initial-data delta with boot
   provenance.
6. Restore/install embeddings through the existing initializer.
7. Return the admitted current `:seon.db/db` value.

Config routes, skills, and config singleton remain a distinct
`:seon.db.process/config` reconciliation because their desired input is an
explicit manifest and their provenance is semantically different. They may run
inside the same externally atomic host admission sequence, but must not be
folded into boot provenance merely to reduce transaction count.

Exit: no query, child, web UI, or autonomous agent is admitted between steps;
converged reopen creates no schema/program/initial-data transaction.

### 6. Wire host admission and delete Bun-side reconciliation

Owner: `src/seon/client.cljs` startup only.

Pass the one precomputed initialization map to `open-database-session!`. After
the authority returns success, continue recovery, initial-agent reconciliation,
runtime admission, web readiness, and child hosting from the returned database
value.

Delete in the same cut:

- `install-runtime-schema!` and its unconditional reopen transaction;
- `acquire-core-program!`, the seven Bun-side core-program queries, and
  `commit-core-program!`;
- the Bun-side `core-program-tx` wrapper;
- the unconditional `seed-core!` transaction in `boot-seed!`; and
- obsolete connection-era/index-core fixtures that exercise those deleted
  owners.

Keep `index-core!`, `index-schemas`, and `seed-core!` only as pure desired-data
builders, renaming or colocating only if their ownership becomes clearer.
`boot-seed!` should shrink to the explicit config reconciliation or disappear
if startup can call that owner directly.

Exit: repository search finds one compiled program reconciliation owner under
`src/seon/db/`, one package index builder, and no unconditional runtime-schema
or core-seed write on reopen.

### 7. Preserve child whole-namespace application

Owners: `src/seon/execution.cljs` and `src/seon/eval.cljs` only for proof and
any required request plumbing.

Do not change `namespace-source`, `authored-sources`, or
`load-authored-program!`. The child receives the admitted database value,
acquires the current universal runtime program, activates the complete schema
projection, and evaluates selected namespace sections. `cljs.js` owns require
ordering, recursive dependency loading, cycles, and load-once. A changed
program digest retains the existing fresh-child plus one retry behavior.

Do not restore historical `topo-sort-nses`, `replay-program-graph!`, standalone
schema call replay, per-form replay, or a program broadcast.

Exit: compiled dispatch performs no authored-program read; selected authored
dispatch loads one section per reachable namespace and never evaluates compiled
package source.

## Failure and atomicity semantics

- Invalid schema or program data fails before a program write.
- A stale database value causes bounded reacquire, pure recompile, and retry;
  it never rebuilds the package desired data.
- Any non-stale Datahike failure returns the existing ordinary protocol error
  and withholds successful ensure/open admission.
- The registry publishes no newly opened connection until initialization
  succeeds. If genesis committed but a later step failed, release the
  connection and leave the durable partial database unadvertised; the next
  ensure reruns the idempotent diff and completes it.
- Existing sessions on a long-lived writer remain pinned to their immutable
  values during a package upgrade. The accepted program transaction advances
  the database once; existing latest-value events then expose it naturally.
- A child invocation already running at an older database value completes at
  that value. Its next invocation uses the admitted latest value and existing
  digest replacement path.
- Initialization never overwrites a runtime-authored current identity silently.
  Before implementation, settle whether a collision with a compiled namespace,
  function, or schema is rejected atomically or preserved. The recommended
  policy is rejection for compiled identities and runtime extension in `my.*`;
  this is a user-visible decision gate, not an implementation detail.

## Focused proof plan

### Pure authority delta

Create a CLJ test namespace for the moved pure compiler. Cover:

1. no current program rows returns the complete desired population;
2. identical current rows returns `[]`;
3. one missing function returns only that function and required namespace ref;
4. drifted source/spec/schema/generator/require edges return exact repair ops;
5. absent boot-owned identities retract while runtime-authored identities and
   agent homes survive;
6. empty desired namespace/function/schema populations refuse removal; and
7. desired input order does not change tx-data.

### Registry and real writer

Extend `seon.db.registry-routing-test` and the real UDS writer integration:

- initialization runs before first connection publication;
- physical create uses initial native schema once;
- same artifact plus converged database performs no write;
- a newer/different artifact against an already registered route reruns exact
  reconciliation;
- concurrent equal requests converge;
- conflicting artifact requests are serialized and each response names its
  admitted database value;
- failed initialization publishes no connection; and
- native branches remain observational and never run main initialization.

### Bun request and startup

Use exact CLJS selectors to prove:

- open-session carries initialization only for the host;
- package desired data is computed once across a stale-value retry;
- converged startup submits no schema/program/core-seed transaction;
- failure prevents recovery, web readiness, and child start; and
- config retains its separate process provenance.

### Child application

Retain and run:

```bash
bin/test-cljs --test=seon.execution-test/authored-loader-loads-each-selected-namespace-once
bin/test-cljs --test=seon.execution-test/ordinary-namespace-source-preserves-one-compile-unit
bin/test-cljs --test=seon.eval.require-test/coordinate-program-loads-transitive-authored-source-without-a-db
bin/test-cljs --test=seon.eval.require-test/absent-authored-dependency-does-not-fall-back-to-a-db

```

Replace stale `seon.index-core-test` connection-era cases with authority tests;
do not adapt their removed `open-agent-conn!` fixture.

## Live acceptance proof

At the coordinated source-freeze checkpoint:

1. Start a fresh named cluster through `bin/seon` and capture its first admitted
   database value.
2. Query attribute presence for `:seon.ns/name`, `:seon.fn/sym`,
   `:seon.schema/key`, root/user/process identities, and required initial facts.
3. Verify the first agent cannot run before those facts and native schema are
   present.
4. Stop and restart unchanged. Observe the same logical populations, no
   schema/program/initial-data transaction, and no program replay log.
5. Rebuild with one changed compiled function and one added schema. Restart
   against the same database and observe one exact program transaction, the
   changed function/schema facts, and preservation of runtime-authored facts.
6. Have Agent A commit a runtime namespace, function, schema, and test once.
   On Agent B's next invocation, observe one digest-driven child replacement,
   transitive whole-namespace loading, and successful invocation with no
   per-agent transaction.
7. Kill a child during loading. Verify the JVM authority and sibling children
   remain available; the supervisor replaces only that child.

Record transaction ids, database values before/after, tx datoms, child restart
count, program acquisition count, elapsed initialization time, and peak RSS.
Graduation requires the fresh, converged, upgrade, cross-agent, and child-crash
cases to pass with one program authority and zero form replay.

## Explicit non-goals

- No database-wide event replay or per-agent publication transaction.
- No stored namespace order, program kind, package-version entity, or second
  schema registry.
- No compiled source evaluation in children.
- No Chronicle Queue, shared-memory database replica, or Bun-side Datahike.
- No attempt to merge config provenance into boot provenance.
- No micro-optimization before the one authority boundary is correct and
  measured.
