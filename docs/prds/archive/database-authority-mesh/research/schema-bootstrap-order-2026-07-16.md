---
type: research
status: active
tags: [research, database, schema, cljs]
---

# Complete schema bootstrap before behavior

## Decision

Schema forms are namespace-independent ordinary data. Their source declarations
remain colocated with the namespace that owns the meaning, but neither Malli nor
Datahike requires those declarations to be validated or installed in namespace
load order.

On a cold start Seon should:

1. collect the complete canonical Malli form map without resolving references;
2. build and validate one Malli projection against that complete map;
3. transact every canonical schema fact and every applicable Datahike attribute
   declaration before initial program/configuration facts;
4. reread the committed database value, build the matching projection, apply
   instrumentation, and only then admit agent, web, or scheduled behavior.

This makes the observed render/context cycle a cold-start ordering defect, not a
domain dependency. `seon.render.schema` should be deleted after its four forms
return to their semantic owners. It exists only because incremental
`schema/register!` currently requires referenced forms to have loaded first.

There are two meanings of "all schemas" that must not be collapsed:

- every registered Malli form is canonical database data, including entity,
  request, response, function-boundary, and view shapes;
- only forms that describe persisted attributes become native Datahike attribute
  declarations.

Blindly converting every scalar-looking Malli form would install request fields
and other non-database vocabulary into Datahike. The full Malli set is always
transacted. The persisted-attribute subset is derived once from the complete
entity schema graph plus Datahike transaction/protocol attributes, then installed
in full before initial facts.

## Dependency ledger

| Dependency | Source used | Relevant mechanism |
|---|---|---|
| Seon schema authority | `src/seon/schema.cljc`, `src/seon/schema/internal.cljc` | candidate forms, complete projection, active registry |
| Seon publication | `src/seon/client.cljs`, `src/seon/runtime/admission.cljs`, `src/seon/eval.cljs` | schema facts, database publication, admission, authored-program load |
| Seon writer | `src/seon/db/writer.clj` and writer integration tests | canonical forms and native Datahike schema derivation |
| Malli | `reference-code/malli`, commit `80138076960e7820523b4cb932c5b5d1936d4e7f` | immutable fast/composite registries and explicit registry options |
| maintained Datahike | `reference-code/datahike`, checkout `a464cd887458d2572414a6ea951c477b0981fdae` | ordered transaction processing and schema datoms |
| Shadow CLJS | `reference-code/shadow-cljs`, commit `4e72595f57618f5c43388ad13d5136cd3bede566` | `:node-script` entries, dev preloads, and appended main call |

The database-authority roadmap names an older maintained-Datahike SHA. The
current vendored checkout above should be reconciled with the roadmap before an
implementation commit claims a pinned dependency.

## What `schema/register!` does today

`seon.schema` owns one process-local atom containing candidate forms and an
optional active projection. A stable Malli registry facade reads the active
projection when present and otherwise reads candidate forms. `relink-registry!`
installs that facade as Malli's process-global default.

`schema/register!` currently performs four operations in order:

1. require a qualified, multi-segment CLJS schema keyword;
2. prove the form round-trips through EDN;
3. compile the new form against only the candidate forms already collected and
   run the non-nilable check;
4. add the form to the candidate map.

Step 3 creates the cold load-order requirement. `assert-compilable-schema!`
explicitly rejects an unresolved qualified keyword and instructs the caller to
register it first. `register-all!` only loops over `register!`, so it preserves
the same ordering constraint.

The correct mechanism already exists immediately below it. `build-projection`
constructs a Malli `fast-registry` from the complete form map, compiles every
schema and function contract against that same registry, derives dependency and
entity indexes, and returns one immutable projection. `activate-projection!`
publishes that exact object atomically. Forward references and cycles among
otherwise valid schema forms therefore already work at projection-build time.

The smallest change is to make `register!` a declaration collector: retain the
keyword/namespace and EDN round-trip checks, but move reference compilation and
the reference-sensitive non-nilable check to complete projection construction.
Invalid or missing references then fail once, before database publication or
behavioral admission, rather than according to file evaluation order.

## Existing cold-start workaround

`seon.agent.ctx` references render schemas, while `seon.render` also owns render
behavior. `seon.render.schema` was introduced as a dependency-light third
namespace containing four shared forms so either consumer can load cold. Other
source comments similarly register `:seon.ns/name` and `:seon.agent/id` in the
"first" child namespace that needs them.

Those placements encode compiler evaluation order rather than semantic
ownership. Once collection is independent of validation order:

- `:seon.render.canvas/hiccup` and `:seon.render.canvas/content` belong with the
  canvas data owner;
- `:seon.render/ai` and `:seon.render/html` belong with render data;
- the first-loading registrations and comments can return to their actual data
  owners; and
- `seon.render.schema` has no independent responsibility and should be deleted.

This is not a second schema registry or a generated compatibility namespace.
There remains one form map and one active projection.

## How the complete set is acquired

### Compiled pod and execution-child host forms

The current `seon.client` composition root requires a large namespace closure
specifically so top-level schema declarations run before `-main`. Shadow's
`:node-script` target creates one module whose entry is the main namespace and
appends the call to the configured main function after the compiled module.
Development `:preloads` are merely prepended module entries. They do not form a
separate semantic phase before the dependencies of behavior namespaces.

Therefore a preload cannot collect declarations from a namespace without
evaluating that namespace. A generated schema namespace would either create a
dependency cycle (`owner -> seon.schema -> generated namespace -> owner`) or
become a second extraction authority.

The simple compiled-artifact mechanism is:

- Shadow evaluates the required namespace closure, whose top-level effects are
  schema declarations and ordinary definitions;
- the first operation in `-main` snapshots all candidate forms and builds the
  complete projection;
- no connection, timer, agent, HTTP listener, or other behavior starts at
  namespace top level;
- startup proceeds only after complete projection validation succeeds.

This uses Shadow's actual module semantics and the forms produced after CLJC
reader-conditionals, without a parser, macro registry, preload convention, or
generated manifest. A build-time manifest is justified only if a future
dependency performs unavoidable behavior during namespace evaluation; current
Seon code should instead move that behavior behind `-main`.

### Authored namespaces loaded after connection

`seon.eval/load-authored-program!` already demonstrates the desired seam. It
receives ordinary schema forms and function contracts acquired from the
database, builds and activates the complete projection, and only then loads the
selected authored namespaces through `cljs.js`. That sequence should remain.

The small host/protocol schema needed to connect and acquire the committed
program must remain compiled into the artifact. It is bootstrap vocabulary, not
a competing application registry. Once acquired, the database-specific
committed form map replaces the candidate view as the active projection.

## Datahike installation today

Datahike represents schema as ordinary transaction data. During ordered
transaction processing, a schema datom updates the in-memory schema used to
validate later datoms. Consequently, declarations can precede initial facts in
one transaction and the facts are validated against them atomically.

Datahike's database-creation `:initial-tx` is implemented as create, connect,
transact, and release. Its own source warns that the shorthand should have been
avoided. Seon correctly uses it only for the fixed protocol receipt schema. The
evolving Seon program schema belongs in a normal writer transaction after the
database opens.

Current Seon behavior is only partly eager:

- `install-runtime-schema!` transacts `index-schemas`, so every collected Malli
  form becomes a durable `:seon.schema` fact;
- `derive-transaction-schema` installs native Datahike declarations only for
  attributes used by the current transaction, generated values, or already
  installed admitted attributes;
- the writer integration test deliberately proves that a schema-only
  `:writer.schema/lazy` form remains absent from Datahike's installed schema
  until its first use.

Thus the canonical forms are initially persisted, but the native Datahike
attribute set is still lazy. That test and writer policy must change to satisfy
the requested design.

## One source for the persisted-attribute subset

Do not restore or expand `agent-bootstrap-attrs`; its manually maintained vector
is already disconnected from the production installation path. Do not attempt
Malli-to-Datahike conversion on every form, because many valid non-database
forms happen to be scalar.

Derive persisted attributes from the canonical graph:

1. select complete entity map schemas marked `{:seon.db/entity true}`;
2. collect their map-entry attribute keywords, following referenced shared map
   shapes where the Malli/Datahike bridge supports them;
3. add the fixed transaction metadata and protocol receipt attributes owned by
   their existing namespaces;
4. require every collected attribute to have one canonical form and convert it
   with the existing `malli-form->datahike-attribute` bridge;
5. reject conflicting declarations before transacting anything.

This relies on entity schemas being complete descriptions of persisted entity
data. The implementation should first compare the derived set with all
attributes used by current seed/config transactions and focused production
fixtures. Any mismatch is an incomplete owning entity schema to repair, not a
reason to preserve a manual bootstrap list.

## Recommended cold-start sequence

The fixed receipt schema required by the wire protocol may still be present at
database creation. Everything else follows one admission rule:

1. evaluate the compiled namespace closure and collect the complete form map;
2. build the complete Malli projection; on failure, return a startup error and
   perform no database or behavioral work;
3. open the selected database and ensure the minimal provenance entities needed
   by transaction metadata;
4. submit one ordered program bootstrap transaction containing missing
   compatible Datahike attribute declarations first, then canonical schema,
   namespace, and function facts, followed by desired initial configuration and
   program facts;
5. reread the committed database value and acquire its complete forms and
   contracts;
6. build the matching projection, verify instrumentation, activate it, and only
   then admit the pod or child.

One physical transaction is desirable because Datahike already validates later
facts against earlier declarations and aborts the whole transaction on a
conflict. If provenance genesis must remain a preceding transaction so metadata
refs resolve, that does not weaken the rule: no application behavior is
admitted between genesis and the program bootstrap commit.

Restart is reconciliation, not reinstallation. An unchanged database produces
no bootstrap write and the same projection fingerprint.

## Risks that remain real

- **Top-level behavior:** collecting declarations by module evaluation is safe
  only when operational effects begin under `-main`. A source check should make
  that invariant explicit.
- **Hot reload:** complete cold bootstrap does not by itself make a failed
  partial reload transactional. Candidate forms may change while the old active
  projection remains. Reload must build a complete candidate and restore the
  prior candidate state on failure before database publication.
- **Bootstrap vocabulary:** database connection and acquisition need a small
  compiled protocol set before the database-derived projection exists. Treating
  it as fixed host vocabulary avoids an impossible acquire-before-connect
  cycle.
- **Multiple databases:** canonical forms may differ by database. Each process
  or database session must activate the projection for the database it serves;
  one mutable global projection cannot safely represent several databases in
  one child concurrently.
- **Incomplete entity schemas:** deriving native attributes exposes any
  persisted attribute missing from its owning entity schema. That is useful
  evidence of a modeling gap and must fail bootstrap clearly.

## Focused proof

The implementation should add narrowly selected evidence before a full suite:

1. Register A referencing not-yet-collected B, then B; collection and complete
   projection build succeed. Omitting B fails before connection/admission.
2. Permute declaration and namespace load order; forms, dependency indexes, and
   projection fingerprint are identical.
3. Cold-load context then render and render then context without
   `seon.render.schema`; both produce the same complete projection.
4. Build one real Shadow `:node-script` artifact with markers proving all
   declaration collection precedes projection construction and all behavior
   begins after it. Repeat with the downstream preload that contributes forms.
5. On a fresh database, install an otherwise unused persisted attribute during
   bootstrap; prove it is already present in Datahike schema before its first
   domain fact. Prove an ordinary request-schema field is not installed.
6. Submit an incompatible or missing persisted attribute; prove the complete
   bootstrap transaction aborts, initial facts are absent, and database head
   does not advance.
7. Restart unchanged; prove no schema/program transaction is emitted and the
   committed projection fingerprint is stable.
8. Prove the execution child activates database-acquired forms before loading
   authored source.

Focused owners are `seon.schema-test`, cold context/render tests,
`seon.db.writer-integration-test`, `seon.runtime.admission-test`, and the
authored-program load tests. The existing lazy-schema writer assertion should be
replaced, not retained as compatibility behavior.

## Recommendation

Implement this as a strengthening of the current one-source path:

- make `schema/register!` collect valid EDN declarations without resolving
  inter-schema references;
- make complete projection construction the sole Malli semantic validation
  point before any behavior;
- derive and eagerly transact the complete persisted Datahike attribute set
  from canonical entity schemas;
- combine declarations, canonical program facts, and desired initial facts in
  the normal writer bootstrap transaction;
- activate only the projection reacquired from the committed database value;
- delete `seon.render.schema`, first-loading comments/registrations, the unused
  manual bootstrap vector, and the lazy native-schema test.

No macro rewrite, preload registry, generated schema authority, second cache, or
parallel startup system is needed.
