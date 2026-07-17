---
type: research
status: completed
tags: [research, database, agent, flow, schema]
---

# Universal current-program publication audit

## Decision in one sentence

Functions, namespace declarations, require edges, schemas, function contracts,
and tests are one database-wide current program. An accepted eval publishes its
changed program facts once in the same Datahike transaction as its eval record;
transaction provenance records who wrote them but never selects who may read or
execute them.

Every execution child starts from the immutable compiled package, acquires the
current runtime-authored program at the exact database value of its invocation,
activates the complete schema projection, and loads only the runtime-authored
namespace sections required by that program. It does not reinstall compiled
code, retransact schemas, replay evals, subscribe to a program broadcast, or
maintain a database replica.

## Dependency ledger

- Seon source inspected at `ab98d70f28705386caa82ae7c4eb25b1e74a0299`
  plus the shared working-tree changes visible on 2026-07-16.
- Maintained Datahike source: `reference-code/datahike` at
  `a464cd887458d2572414a6ea951c477b0981fdae`, selected as a local root in
  `deps.edn`.
- ClojureScript source: runtime version `1.12.145`; vendored source inspected at
  `reference-code/clojurescript` commit
  `946d75f3483c0c8e784e6668bff2c71a25619a77`.
- Existing Seon mechanisms: `seon.eval` detect-and-tee and namespace loader,
  `seon.execution` program acquisition/digest/install, `seon.execution.host`
  child replacement, `seon.schema` immutable projections,
  `seon.runtime.admission` committed publication, `seon.client` core desired
  graph, and the maintained Datahike transaction/listener path.

This was a source-only audit. No lifecycle, build, or test command was run.

## What is already correct

### One accepted eval already writes one shared program transaction

`build-tee-entities` emits ordinary identity-upsert rows for a literal `defn`,
`deftest`, namespace declaration, and changed schema. The identities are
`:seon.fn/sym`, `:seon.test/sym`, `:seon.ns/name`, and `:seon.schema/key`;
nested namespace maps preserve the normal Datahike ref relation
(`src/seon/eval.cljs:2432-2598`). The successful eval path freezes these rows,
records them through the authority once, and only publishes the process-local
projection after the database accepted the same transaction
(`src/seon/eval.cljs:4650-4739`).

That is already the desired publication seam. No new publication entity,
message, or per-agent copy is needed. The current identity attributes mean one
fully qualified name has one current value in the database, while Datahike
history and transaction metadata retain prior definitions and authorship.

The JVM authority serializes the actual `d/transact!` against one connection
and supplies the expected basis when the request carries an exact database
value (`src/seon/db/writer.clj:1140-1199`). The maintained Datahike writer calls
connection listeners with the accepted transaction report only after writer
dispatch returns (`reference-code/datahike/src/datahike/writer.cljc:357-381`).
Datahike's listener registry is merely a keyed process-local callback map
(`reference-code/datahike/src/datahike/core.cljc:199-217`); it is not a reason
to copy or replay the program into children.

### Complete schema activation is already declaration-order independent

`schema/build-projection` receives the complete map of canonical forms, builds
one Malli registry, validates every schema and function contract against that
registry, derives dependency indexes and the entity catalog, and returns one
immutable projection (`src/seon/schema.cljc:291-370`).
`activate-projection!` swaps that complete projection in one process-local
mutation (`src/seon/schema.cljc:372-384`).

`seon.runtime.admission` already acquires every current schema and function
contract without an author filter at one database value, builds the complete
projection, reconciles instrumentation, and only then admits the generation
(`src/seon/runtime/admission.cljs:185-250`, `266-386`). Agent-created schemas
therefore already become visible to the main runtime as shared current facts.

### The child already has the correct bounded source-loading mechanism

`eval/namespace-source` forms one loadable section per namespace: one namespace
head plus distinct current function and test source rows
(`src/seon/eval.cljs:774-804`). `load-authored-program!` first activates the
complete schema/contract projection, then passes the ordinary namespace source
map to `cljs.js`; its load function follows real `ns` requires recursively, so
ClojureScript owns dependency order, cycle detection, and load-once semantics.
Selected namespaces are evaluated as sections, not as an event log or a series
of individual historical forms (`src/seon/eval.cljs:950-1016`).

The existing proofs directly cover the intended seam:

- `test/seon/eval/require_test.cljs:115-156` proves a coordinate-acquired
  source map loads transitive authored dependencies without database access.
- `test/seon/eval/require_test.cljs:158-190` proves a missing dependency fails
  instead of falling back to ambient database state.
- `test/seon/execution_test.cljs:290-324` proves multiple selected functions
  load through their namespace sections.
- `test/seon/execution_test.cljs:378-394` proves one namespace remains one
  deduplicated compile unit.
- `test/seon/execution_test.cljs:215-235` proves canonical program identity is
  independent of query result order.

This is the mechanism universal publication should feed. Do not add a second
topological sorter or replay every stored eval form.

### A changed program already has a safe fresh-child path

Each child retains one bootstrap compiler and one loaded program digest. A
matching digest can lazily load another selected namespace; a different digest
returns `::reload-required?` rather than mixing two current programs in the
same JavaScript globals (`src/seon/execution.cljs:559-607`). The Bun host kills
that stale child, starts a fresh compiled-package child, and retries the same
exact invocation once (`src/seon/execution/host.cljs:437-482`).

This is the right refresh model. An invocation that began at database value T
continues using the program at T. The next invocation carries the latest cached
database value, observes a new program digest, and naturally replaces the
child. Dormant children do no work. No program-specific selective database
interest is needed for correctness.

## The current visibility defect

The database facts are shared, but `seon.execution` currently turns provenance
into access control. These are all of the author filters that prevent one
agent's child from seeing or compiling another agent's accepted current code:

| Current query | Blocking clauses | Effect |
|---|---|---|
| `authored-namespace-query` | `?tx :seon.db/user ?author` plus matching `?agent-id` | Another agent's current namespace declaration is absent. |
| `authored-require-edge-query` | same author match | Another agent's current dependency edges are absent. |
| `authored-function-query` | function source author and namespace-link transaction author must both match | Another agent's function is absent; a valid shared function can also disappear when its namespace relation was written by a different accepted transaction. |
| `authored-test-query` | test source author and namespace-link transaction author must both match | Another agent's tests are absent. |
| `schema-query` | boot schemas or the current agent's REPL schemas only | Schemas written by another agent are absent from that child's complete projection. |
| `function-contract-query` | source and spec transactions must both be boot or both belong to the current agent | Another agent's contract is absent, and legitimate provenance changes can make a current contract disappear. |
| `invocation-source-query` | requested source must belong to the invoking agent | A parent cannot invoke a shared function for another agent even though the symbol has one current database value. |

All seven are in `src/seon/execution.cljs:215-328`. Their arguments are then
propagated into one query member per agent in `prepare-invocations!` and seven
members per child in `acquire-program!` (`src/seon/execution.cljs:474-544`).
The existing regression explicitly names the obsolete behavior
`authored-program-acquisition-is-one-agent-owned-snapshot` and asserts seven
members (`test/seon/execution_test.cljs:244-288`). The preparation regression
also asserts one source query per agent (`test/seon/execution_test.cljs:396-425`).

Remove these filters. Keep `::agent-id` on invocations because it selects the
supervised process, current runtime scope, run fence, and transaction
provenance; it must stop selecting program membership.

The author filters in `seon.agent.ctx.canvas` are different. They choose the
current agent's automatically selected canvas function and most recently
touched inputs (`src/seon/agent/ctx/canvas.cljs:22-44`). They do not determine
whether an explicitly selected symbol can be compiled. Preserve them unless
the product decision is to make automatic canvas ownership global too.

## Smallest universal current-program projection

Keep the compiled package out of the runtime source projection. It is already
loaded and identified by the execution artifact digest. The runtime program is
the current non-boot source population plus the complete current schema and
function-contract population.

The smallest low-risk change is to retain the existing canonicalizer and query
shapes but remove only `:seon.db/user` joins and `?agent-id` inputs:

1. Current namespace sources whose source transaction used
   `:seon.db.process/repl`.
2. Current namespace require edges whose current edge transaction used REPL.
3. Current function sources whose source transaction used REPL, joined to the
   namespace by `:seon.fn/ns` without constraining who wrote that relation.
4. Current test sources whose source transaction used REPL, joined the same
   way.
5. Every current `:seon.schema/key`/`:seon.schema/form` row.
6. Every current `:seon.fn/sym`/`:seon.fn/spec` row.

The separate home-namespace query becomes unnecessary for visibility: home
namespace facts are ordinary `:seon.ns` rows, and a function/test row already
causes `canonical-program` to create its namespace row. If a home namespace
needs a synthesized head, use its persisted require edges exactly as
`namespace-source` already does.

This six-member first cut is intentionally conservative: it changes semantics
without simultaneously redesigning query weight. After parity, collapse the
four source queries into one bounded namespace pull with reverse
`:seon.fn/_ns` and `:seon.test/_ns` selectors, leaving one separate all-schema
query for schema keys that intentionally have no namespace ref. Measure the
two-member pull against the six-member parallel acquisition before selecting
it; do not assume fewer frames beats smaller independently cached queries.

`prepare-invocations!` can improve immediately: deduplicate requested symbols
across all plans and issue one source query at the selected database value.
Map the resulting symbol digest back onto plans in caller order. Agent ID is no
longer part of the source identity key.

The program digest continues to hash canonical namespace rows, complete schema
forms, and complete function contracts. That makes it database-wide and equal
for every child at the same database value.

## Conflict and protection semantics

### Runtime-authored conflicts

The identity attributes already define the rule: the last accepted transaction
for the same fully qualified `:seon.fn/sym`, `:seon.test/sym`,
`:seon.ns/name`, or `:seon.schema/key` is the current value. Datahike serializes
the transactions, the eval record and program rows commit together, and the
invocation's source digest rejects a stale selected function. Provenance still
answers who supplied each current or historical definition.

Do not add per-agent copies or suffix symbols with agent IDs. If accidental
same-symbol replacement later proves too permissive, the compatible stronger
rule is an expected-current-value CAS on the existing identity, not separate
program populations.

### Compiled-core protection is currently incomplete

`reject-core-overrides` protects only a `:seon.fn` row whose current source was
written by boot; namespace, schema, test, and require-edge rows pass through
(`src/seon/eval.cljs:2859-2925`). With a universal program, a runtime eval must
not be able to change a compiled namespace declaration or compiled schema and
thereby alter every child.

Generalize the existing acquisition-and-filter step to the same identity
families managed by the compiled package:

- reject replacement of a current boot-owned `:seon.fn/sym`;
- reject replacement of a current boot-owned `:seon.ns/name` source or require
  edge set;
- reject replacement of a current boot-owned `:seon.schema/key`; and
- keep tests runtime-authored unless compiled tests are intentionally shipped.

Return an ordinary error naming the protected identity rather than merely
warning and silently dropping part of a supposedly accepted program change.
New symbols in runtime-authored namespaces remain valid shared program facts.

## Cold start and schema installation

The observed circular cold-start failure is an initialization-order problem,
not a Datahike namespace rule. Schema keywords are globally named data and a
complete schema population is declaration-order independent. However, today's
`schema/register!` immediately calls `assert-compilable-schema!`, which requires
every referenced keyword to have been registered earlier in that JavaScript
module load (`src/seon/schema.cljc:222-278` and
`src/seon/schema/internal.cljc:118-148`). That is why source currently contains
comments and leaf namespaces describing “first-loading” schema ownership, for
example `src/seon/agent/ctx.cljs:68-74` and
`src/seon/agent/ctx/render_fns.cljs:40-47`.

Transacting all schema facts before runtime admission is correct, but it cannot
by itself repair a JavaScript namespace that throws while the compiled package
is still being initialized and before a database session exists. Remove the
incremental compilation requirement:

1. `register!` collects readable canonical forms and performs only checks that
   do not require the referenced population.
2. The compiled package produces its complete canonical form map.
3. `schema/build-projection` validates the entire map once, independent of
   namespace load order.
4. The authority admits the complete compiled schema/program delta before
   agents or web work are admitted.
5. Every child starts with that compiled baseline, then activates the complete
   database schema/contract projection before loading runtime-authored namespace
   sections.

The database side is already close. `install-runtime-schema!` sends all
canonical schema rows in one authority transaction
(`src/seon/client.cljs:865-882`), and the writer derives the necessary Datahike
declarations in that same commit. `compile-core-program-tx` already computes an
exact desired delta and promises `[]` on a converged restart
(`src/seon/client.cljs:1875-1912`); `commit-core-program!` submits no transaction
when the delta is empty (`src/seon/client.cljs:2119-2140`).

Therefore this is not “start every child at transaction 0.” It is:

- initialize a fresh database once with the minimal provenance facts required
  for transaction metadata;
- transact the complete compiled schema/program and required initial data
  before executable admission, in as few provenance-correct transactions as
  the data populations require;
- on reopen, acquire and diff the existing current facts, submitting no
  transaction when converged; and
- give every child an ordinary exact database value at or after that accepted
  initialization.

Remove the unconditional full-schema transaction from every cold reopen by
folding schema admission into the existing coordinate-pinned core-program
delta. Do not optimize away distinct config provenance or other genuinely
different desired populations merely to force all startup data into one
physical transaction.

## Child refresh semantics

Next-invocation synchronization is sufficient and preferable:

1. Agent A commits a function/schema/test/namespace change once.
2. The authority advances the database and the existing small head event moves
   each persistent session's cached latest database value.
3. Existing active invocations remain pinned to their original immutable
   database value and program.
4. The next invocation for any agent is prepared against the latest cached
   value and its universal current source digest.
5. A fresh child loads the accepted namespace sections. A retained child with
   another program digest returns `reload-required`; the existing host replaces
   it and retries once.

A selective database interest would only buy eager retirement of idle children.
It adds one interest per child, wake traffic, queries on program transactions,
and races with active invocations while providing no correctness improvement.
Add it only if measurement later shows the one-time next-invocation replacement
latency is user-visible. The Bun web UI follows the same rule for executable
render functions; its ordinary database render interests remain about changed
data, not source distribution.

## Database facts versus child-local artifacts

| Shared database truth | Rebuilt process-local artifact |
|---|---|
| Current namespace source and require-edge facts | `cljs.js` analyzer/compiler state |
| Current function source, metadata, and contract facts | JavaScript function objects on `globalThis` |
| Current test source and result facts | Loaded `cljs.test` vars and reporter state |
| Current canonical schema forms | Malli registry/projection object and wrappers |
| Transaction provenance and immutable database identity | Loaded-program digest and selected-symbol set |

Only the left column crosses the authority protocol. The right column belongs
to one child and is discarded with that process. None of it is transacted,
shared through memory, or reconstructed by replaying arbitrary evals.

## Migration order

1. Make `schema/register!` declaration collection independent of prior
   namespace load, retaining complete validation in `build-projection`; delete
   first-loader schema ownership comments and tests as the new cold-start proof
   replaces them.
2. Fold compiled schema installation into the existing exact core-program
   delta so a converged reopen performs no schema transaction.
3. Generalize the current core override acquisition/filter to namespace,
   function, and schema identities, returning one ordinary rejection value.
4. Remove every `?agent-id`/`:seon.db/user` program-membership clause listed
   above. Keep REPL-versus-boot source selection and provenance facts.
5. Change `prepare-invocations!` to one requested-symbol query and change
   `acquire-program!` to one universal canonical program per exact database
   value. Delete the home query and per-agent program keying.
6. Feed that value into the existing `schema/build-projection` plus
   `load-authored-program!` namespace-section loader. Do not replay compiled
   package namespaces or individual eval forms.
7. Preserve the existing digest mismatch -> fresh child -> one retry path; add
   no source interest or broadcast.
8. Delete obsolete “one-agent-owned program” tests and replace them with
   two-agent publication/visibility/conflict/cold-restart proofs.

## Required proofs

1. Agent A defines a function, schema, namespace require, and deftest in one
   accepted eval. Agent B's already-running child invokes the function on its
   next invocation after exactly one digest-driven child replacement; no
   per-agent transaction occurs.
2. Agent B can require Agent A's namespace and its transitive authored
   dependency. The child has no database replica and the loader performs no
   ambient database read.
3. Both children at the same database value compute the same universal program
   digest and schema projection fingerprint.
4. An active invocation at T remains on T while a program transaction lands at
   T+1; its next invocation sees T+1.
5. Two agents redefine the same runtime symbol in serialized transactions. The
   latter current source wins, history/provenance identifies both authors, and
   a prepared stale source digest is rejected.
6. Attempts to replace compiled function, namespace, or schema identities are
   rejected atomically; no partial eval program row becomes current.
7. Fresh database boot admits complete schemas and initial facts before agent
   work. A converged reopen emits zero schema/core-program transactions.
8. Forward-referencing schema forms build from one complete projection without
   changing namespace require order.
9. A namespace with several functions/tests is evaluated as one section;
   transitive dependencies load once through `cljs.js`.
10. With 1, 8, and 32 children, one program transaction causes no eager child
    wake traffic; record next-invocation replacement latency, frames, bytes,
    child RSS, and authority query cache/single-flight evidence.

## Consequential product choices for Sean

Only two choices remain consequential enough to ask rather than infer:

1. **Same-symbol collaboration:** recommended default is database current-value
   semantics—last accepted transaction wins, with exact-value/source-digest
   fences and full provenance/history. The stronger alternative is an explicit
   expected-current-source CAS that rejects accidental replacement. Neither
   option creates per-agent code copies.
2. **Core namespace extension:** recommended default is that runtime code may
   define new symbols only in runtime-owned namespaces; compiled namespaces and
   schema identities are closed to runtime edits. The more permissive option
   allows new symbols in compiled namespaces but still forbids replacement; it
   complicates namespace-section synthesis and package/runtime ownership.

Universal visibility, no per-agent retransact, next-invocation refresh, and
compiled-package baseline do not need further product decisions.
