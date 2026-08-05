---
type: research
status: proposed
tags: [research, naming, schema]
---

# Naming coherence audit — 2026-08-05

## Verdict

Seon's naming is substantially better than the suspected half-migration would
imply. The program graph is **not** halfway from `:seon.fn`/`:seon.ns`/
`:seon.schema`/`:seon.test` to `seon.code.*`. A later explicit owner ruling
abandoned that rename and kept the four established top-level families. Current
source follows that ruling. The live defect is stale maintained guidance which
again tells an implementer to perform the superseded migration. Completing that
migration would create a large, destructive database migration for no current
architectural gain; abandoning it costs two small documentation edits.

`:seon.code.def` is not a partial `seon.code.*` conversion. It is the separate
durable session-image shape for ordinary, uncontracted Clojure definitions. Its
namespace is defensible because `def` is Clojure's own construct and no proposed
replacement is better grounded. The one bad leaf is
`:seon.code.def/unrestorable`: it stores a reason string, so
`:seon.code.def/unrestorable-reason` is the honest name.

The highest-cost real naming risks are elsewhere:

- program-row data has an owner (`seon.program`) but no declared row, deletion,
  identity, or shape schemas; consumers therefore call it
  `:seon.sci.eval/program-row :map`;
- durable operator process records use an undeclared
  `:seon.dev.process/*` family and a string start instant while the runtime's
  declared process identity uses `:seon.boot/pid` plus an `:inst`
  `:seon.boot/start-instant`;
- live Datahike connections have two equivalent schema names, and immutable
  database values travel under an undeclared `:seon.db/db` map key even though
  their declared and dependency-grounded name is “database value”;
- four unrelated values are called “context” where the producer already has a
  more exact term; and
- today's proof and operator result maps contain important, weak `:map` or
  `[:vector :map]` contracts instead of globally named shapes.

Schedule/task/fire ownership, render composition, elision values, output sink
and projection-boundary facts, run supersession, revision/proof/adoption names,
and most database vocabulary are in good shape. The ordered plan below fixes
the cheap misleading names before later work makes their blast radius larger,
then isolates the changes that already require data migration.

## Scope and method

I read the requested authorities end to end before judging names:

- the vocabulary table and its surrounding interface/source-grounding rules in
  [AGENTS.md:650-727](../../../../AGENTS.md#L650);
- all 784 lines of
  [docs/conventions.md](../../../conventions.md); and
- every file under `resources/seon/schemas/`: 122 EDN resources containing
  1,561 top-level declarations.

I also read the active program rulings, the resolved earlier rename issue, the
complete session-curation PRD, the current program-state skill reference, and
the first-party producer and consumer sources cited below. The registry sweep
compared exact declaration forms, repeated local names, suffixes such as
`-data`/`-state`/`-entry`/`-descriptor`, weak top-level forms, and qualified map
keys that have no top-level declaration. Exact-shape comparison found many
legitimate primitive aliases and one material semantic duplicate: the live
connection schemas.

The missing-key scan found 100 distinct qualified keys used inside map schemas
without a top-level registry declaration. That number is a triage signal, not
100 asserted defects: it includes dependency keys such as
`:datahike/commit-id`, recursive `seon.print` implementation keys, and
process-local Flow channels. The findings below include only cases whose
producer and consumer establish a stable reusable shape.

### Dependency ledger

| Boundary | Selected source | Names it establishes | First-party seam |
|---|---|---|---|
| Datahike | `reference-code/datahike` at `c15272730e74fb3f8bba91f6361c268492a99ba7` | connection, immutable database value, connection ID, transaction data/report | `seon.db`, `seon.cluster.store`, source publication |
| cron-utils | `reference-code/cron-utils` at `a3d31f7445376b19d1337c604d3d3b7e986302cc` / 9.2.1 | cron expression, `Cron`, `ExecutionTime`, `ZoneId`-based execution | `seon.schedule` |
| SCI | `reference-code/sci` at `2db3358cba913b6fbbe49c7b5b34d7ac72715924` | `ctx`, `fork`, Vars | `seon.sci.eval`, session-image acquisition |
| Clojure | checked-in Clojure source and ordinary language terms | `def`, Var, namespace, source form | program rows and `:seon.code.def` |
| JDK | `ProcessHandle`, `Files/size`, `ZoneId` used directly by first-party owners | PID/start instant identity, logical file size, zone ID | cluster process and operator records/footprint |

The Datahike API calls `db` a function but calls its return an “underlying
immutable database value”
([reference-code/datahike/src/datahike/api/specification.cljc:314-328](../../../../reference-code/datahike/src/datahike/api/specification.cljc#L314)).
Its connection predicate and released sentinel are explicit
([reference-code/datahike/src/datahike/connector.cljc:72-105](../../../../reference-code/datahike/src/datahike/connector.cljc#L72)),
and its connection identity is derived from store ID plus branch, with the
writer backend appended when needed
([reference-code/datahike/src/datahike/store.cljc:44-55](../../../../reference-code/datahike/src/datahike/store.cljc#L44)).

### Migration classes

Every schema-name change updates program-graph data in `current-src`; existing
clusters remain sovereign older programs until explicitly reforked. The plan
therefore distinguishes four costs:

- **Documentation only:** no database or runtime value changes.
- **Program publication:** schema/contract/source facts change; republish
  `current-src` and refork test clusters, but no domain datom uses the old key.
- **Cluster data migration:** existing domain entities carry the old attribute;
  a new attribute plus an explicit data transition is required. The accretion
  rule forbids silently changing an existing key's meaning.
- **Operator-record migration:** durable EDN outside the database carries the
  old key or value shape; migrate the files atomically under the lifecycle
  lock rather than adding an indefinite compatibility reader.

## Ranked findings

### R1 — Critical confusion cost: the maintained vocabulary revives a superseded program-family rename

**What a reader will get wrong.** An implementer following the maintained
vocabulary table will plan four new attribute namespaces, duplicate the one
program graph, and migrate identity attributes in every stored cluster.

**Evidence.** The table says the owners “rename to `seon.code.fn`/`.ns`/
`.schema`/`.test`”
([AGENTS.md:662](../../../../AGENTS.md#L662)). The current ruling says the exact
opposite: the program facts stay top-level and that decision supersedes the
`seon.code.*` rename
([docs/prds/sci-execution-runtime/plan/README.md:127-134](../plan/README.md#L127)).
The earlier issue was resolved on 2026-07-29 after the architecture was made to
match the later ruling
([docs/seon/issues/archive/architecture-program-graph-owner-rename-is-stale.md:59-70](../../../seon/issues/archive/architecture-program-graph-owner-rename-is-stale.md#L59)).
One still-active successor plan also calls the rename “queued”
([docs/prds/sci-execution-runtime/plan/parse-primitives-plan-2026-07-29.md:519-526](../plan/parse-primitives-plan-2026-07-29.md#L519),
[docs/prds/sci-execution-runtime/plan/parse-primitives-plan-2026-07-29.md:650-656](../plan/parse-primitives-plan-2026-07-29.md#L650)).

Current code has five deliberately separate program identities:
`:seon.ns/name`, `:seon.fn/sym`, `:seon.schema/key`, `:seon.test/sym`, and
`:seon.code.def/id`
([src/seon/program.cljc:8-50](../../../../src/seon/program.cljc#L8)). There are no
live `:seon.code.fn/*`, `:seon.code.ns/*`, `:seon.code.schema/*`, or
`:seon.code.test/*` declarations or call sites.

**Ruling and name.** Abandon the rename. Keep the dependency- and
construct-grounded top-level owners `seon.fn`, `seon.ns`, `seon.schema`, and
`seon.test`. Repair the vocabulary row and the two stale sentences/decision in
the active parse plan as one documentation-only unit.

**Cost.** Abandonment is cheap now. Completion would touch the four identity
attributes, all refs to them, program analysis, queries, schemas, tests, docs,
skills, current-source publication, and every cluster's persisted program
facts. It would be expensive now and strictly more expensive later.

### R2 — High confusion and correctness cost: program rows exist without program-owned names

**What a reader or agent will get wrong.** The system says all program facts are
queryable, but a consumer sees an arbitrary map called
`:seon.sci.eval/program-row`. It cannot ask which declaration or deletion shape
is valid, and it may add another evaluator-local row contract instead of
strengthening the program owner.

**Evidence.** `seon.program` already owns the identity table, source attribute,
owned attributes, row canonicalization, and explicit deletion row
([src/seon/program.cljc:8-50](../../../../src/seon/program.cljc#L8),
[src/seon/program.cljc:368-423](../../../../src/seon/program.cljc#L368),
[src/seon/program.cljc:468-495](../../../../src/seon/program.cljc#L468)). Its
stable maps use keys such as `:seon.program/identity`, `/row`,
`/delete-identities`, `/source`, `/identity-attribute`, and
`/source-attribute`, but the registry's `seon.program` resource declares only
the declaration-refusal error
([resources/seon/schemas/seon.program.edn:1-8](../../../../resources/seon/schemas/seon.program.edn#L1)).
The evaluator consequently declares `:seon.sci.eval/program-row :map`
([resources/seon/schemas/seon.sci.eval.edn:31-46](../../../../resources/seon/schemas/seon.sci.eval.edn#L31),
[resources/seon/schemas/seon.sci.eval.edn:71-73](../../../../resources/seon/schemas/seon.sci.eval.edn#L71));
17 source/schema/test/doc files use that consumer-owned name.

The same weakness appears one stage earlier: source upserts call their input
`:seon.source/rows [:vector :map]`
([resources/seon/schemas/seon.source.edn:37-49](../../../../resources/seon/schemas/seon.source.edn#L37)),
even though `upsert!` explicitly accepts canonical scalar program rows
([src/seon/cluster/source.clj:220-274](../../../../src/seon/cluster/source.clj#L220)).
The manifest and file artifacts are also stable named values but several public
contracts still call them bare `:map`
([src/seon/fn.clj:763-835](../../../../src/seon/fn.clj#L763)).

Three canonical schema-row attributes are a related authority split. Registry
files use `:seon.schema/key`, `:seon.schema/form`, and
`:seon.schema.admission/source` but do not declare them
([resources/seon/schemas/seon.schema.edn:6-15](../../../../resources/seon/schemas/seon.schema.edn#L6));
`seon.schema` imperatively bootstraps their definitions in source
([src/seon/schema.clj:695-705](../../../../src/seon/schema.clj#L695)). The names
are good, but the requested “whole registry” is not actually the whole naming
authority.

**Proposed names.** Declare, at the program owner:

- `:seon.program/identity` — the existing `[identity-attribute value]` tuple;
- `:seon.program/declaration-row` — the union of the five existing declaration
  entity shapes;
- `:seon.program/deletion-row` — the existing explicit
  `/delete-identities` plus `/source` and optional `/ns` map;
- `:seon.program/row` — declaration or deletion row;
- `:seon.program/rows` — vector of program rows;
- `:seon.program/shape` — the existing identity/source/owned-attributes map;
- `:seon.fn.file/artifact` and `:seon.fn.manifest/manifest` — the producer's
  existing file-artifact and manifest terms.

Replace `:seon.sci.eval/program-row` with `:seon.program/row` and
`:seon.source/rows` with `:seon.program/rows`. Move the three canonical
schema-row declarations into their registry resource only after a small
bootstrap admission probe proves the self-description cycle remains valid.
These names come directly from the producer functions and their current data;
no third family is needed.

**Cost.** Program publication only, not domain-data migration. This is cheap
relative to the future cost: W3 editor work will otherwise consume and emit
more untyped program rows.

### R3 — High operational cost: process identity has three vocabularies and two value shapes

**What a reader or operator will get wrong.** “Process identity” may mean a
declared map with a Date, an operator record with `:seon.dev.process/*` string
fields, or the `<pid>-<start-millis>` run-holder string. Code can compare or
render the wrong representation and misjudge liveness.

**Evidence.** The runtime declares process identity as a map containing
`:seon.boot/pid` and `:seon.boot/start-instant`
([resources/seon/schemas/seon.cluster.process.edn:1-7](../../../../resources/seon/schemas/seon.cluster.process.edn#L1))
and produces that exact map from `ProcessHandle`
([src/seon/cluster/process.clj:12-28](../../../../src/seon/cluster/process.clj#L12)).
The protected operator owner instead produces undeclared
`:seon.dev.process/pid` and a **string** `:seon.dev.process/start-instant`
([resources/seon/operator/state.clj:20-43](../../../../resources/seon/operator/state.clj#L20));
the launcher validates and persists that family throughout its process record
([script/seon/fresh_operator.clj:104-115](../../../../script/seon/fresh_operator.clj#L104),
[script/seon/fresh_operator.clj:699-705](../../../../script/seon/fresh_operator.clj#L699)).
No `:seon.dev.process/*` key has a schema-registry declaration, despite those
EDN files being durable operator truth.

A third projection is named by the same phrase: `seon.cluster/process-identity`
returns the string `<pid>-<start-millis>` for
`:seon.cluster.run/process`
([src/seon/cluster.clj:1106-1116](../../../../src/seon/cluster.clj#L1106)),
while `seon.cluster.process/current-identity` returns the map above. The run
attribute itself is coherent: its presence names the process holding the run,
and its string shape is declared
([resources/seon/schemas/seon.cluster.run.edn:8-15](../../../../resources/seon/schemas/seon.cluster.run.edn#L8)).

**Proposed names and boundary.** Keep `:seon.cluster.run/process`; it is a
settled custody attribute and existing cluster data carries it. Rename only the
string-producing function to `run-process` or `run-process-holder`, making its
projection role explicit. For durable operator files declare
`:seon.operator.process-record/record`, `/generation`, `/root`, `/log`, and
optional `/cache-path`, while reusing the already declared
`:seon.boot/pid` and `:seon.boot/start-instant` inside the record. Persist the
start instant as `:inst`, matching `ProcessHandle` and the advertisement.

Do not rename `:seon.db.process/id`: it names a stable transaction-provenance
process such as `seon.db.process/config`, not an OS process
([resources/seon/schemas/seon.db.process.edn:1](../../../../resources/seon/schemas/seon.db.process.edn#L1),
[src/seon/config.clj:28-35](../../../../src/seon/config.clj#L28)). The namespace
qualification is doing useful work there.

**Cost.** The run-holder function rename is program publication only. The
operator record change touches 22 files and requires an atomic migration of
existing claim files under `data/operator/claims/processes`; it is an
operator-record migration, not a cluster database migration. Do it before root
maintenance adds more consumers.

### R4 — High cross-owner cost: a live Datahike connection and a database value change names at boundaries

**What a reader or agent will get wrong.** A caller may infer that a “branch
connection” is a different host object or custody class from a Datahike
connection, and request maps hide immutable database values behind the generic
key `db`.

**Connection evidence.** `:seon.db/connection` accepts a live unreleased
Datahike connection
([resources/seon/schemas/seon.db.edn:8-19](../../../../resources/seon/schemas/seon.db.edn#L8)).
`:seon.store/branch-connection` accepts the same value with the same live/
unreleased predicate semantics
([resources/seon/schemas/seon.store.edn:1-7](../../../../resources/seon/schemas/seon.store.edn#L1));
the store predicate simply delegates to `seon.db/connection?`
([src/seon/cluster/store.clj:34-55](../../../../src/seon/cluster/store.clj#L34)).
Fifty-five files use `:seon.store/branch-connection`, while the canonical
`:seon.db/connection` schema is effectively unused outside its declaration and
tests. `seon.db/*conn*` is normal Clojure dynamic-Var shorthand and accurately
holds that live connection
([src/seon/db.clj:65-67](../../../../src/seon/db.clj#L65)).

`:seon.store/connection` is a real distinction: it intentionally accepts the
Datahike `Connection` object whether live or released for lifecycle cleanup
([resources/seon/schemas/seon.store.edn:8-13](../../../../resources/seon/schemas/seon.store.edn#L8)).
If retained, its honest name is `:seon.store/connection-object`, not another
unqualified `connection` contract.

Two role keys need separate dispositions. `:seon.config/connection :any` is an
undeclared third spelling for the live connection and should disappear in
favor of `:seon.db/connection`
([resources/seon/schemas/seon.config.edn:5-16](../../../../resources/seon/schemas/seon.config.edn#L5)).
`:seon.boot/cluster-connection` is an honest role on a boot instance and may
survive into teardown after release; keep the role key but validate it as
`:seon.store/connection-object`
([resources/seon/schemas/seon.boot.edn:37-55](../../../../resources/seon/schemas/seon.boot.edn#L37)).

**Database-value evidence.** The registry correctly calls the immutable host
value `:seon.db/database-value` and projects Datahike's own `:db-name`, `:t`, and
`:datahike/commit-id`
([resources/seon/schemas/seon.db.edn:20-34](../../../../resources/seon/schemas/seon.db.edn#L20)).
Eleven map-schema slots across five registry files instead use undeclared
`:seon.db/db` while validating the value as `:seon.db/database-value`, for
example the SCI acquisition and invocation requests
([resources/seon/schemas/seon.sci.eval.edn:1-4](../../../../resources/seon/schemas/seon.sci.eval.edn#L1),
[resources/seon/schemas/seon.sci.eval.edn:47-64](../../../../resources/seon/schemas/seon.sci.eval.edn#L47)).
Forty-two source/schema/test files use the generic map key. The house database
vocabulary itself requires “database value”
([AGENTS.md:688-706](../../../../AGENTS.md#L688)).

**Proposed names.** Use `:seon.db/connection` for every live Datahike
connection. Rename the lifecycle-only union to
`:seon.store/connection-object`. Use `:seon.db/database-value` as both the map
key and its schema; positional locals should be `database`, matching current
`seon.db` source and Datahike's prose. No `connection-info`, `db-context`, or
other umbrella is warranted.

**Cost.** Broad program-publication units: approximately 55 files for the live
connection and 42 for the database-value key. Existing clusters contain the
old schema and function-contract facts but no domain entity stores either host
object or the `:seon.db/db` request key. Republish/refork is sufficient.

### R5 — High day-to-day confusion cost: “context” names four values whose producers already name them better

The settled uses should stay:

- SCI's `ctx` and `fork` match the dependency and are explicitly qualified as
  `:seon.sci.eval/ctx`
  ([resources/seon/schemas/seon.sci.eval.edn:6-10](../../../../resources/seon/schemas/seon.sci.eval.edn#L6));
- agent context is the rendered prompt plus its contributions, under
  `seon.context` and `:seon.cluster.prompt/rendered-context`
  ([resources/seon/schemas/seon.cluster.prompt.edn:1-7](../../../../resources/seon/schemas/seon.cluster.prompt.edn#L1)); and
- provider `context-window-tokens` is the industry term for a model limit
  ([resources/seon/schemas/seon.ai.model.edn:10](../../../../resources/seon/schemas/seon.ai.model.edn#L10)).

The following are misleading, ranked by mistake likelihood:

| Current name | What it actually holds | Proposed name | Evidence and persistence |
|---|---|---|---|
| `:seon.bootstrap.plan.form/context` | The help prose printed by the `help` macro | `:seon.bootstrap.plan.form/help-text` | The resource contains the guide text ([resources/seon/bootstrap.edn:1-35](../../../../resources/seon/bootstrap.edn#L1)) and the consumer function is already named `help-text` ([src/seon/bootstrap.clj:109-118](../../../../src/seon/bootstrap.clj#L109)). Stored bootstrap-form datoms make this a cluster-data migration/current-source refork. |
| `:seon.db/capture-context`, `db/*capture-context*` | An atom collecting Datahike read evidence | `:seon.db/read-evidence-sink`, `db/*read-evidence-sink*` | The schema's own error text says this is an atom collecting read evidence ([resources/seon/schemas/seon.sci.eval.edn:56-62](../../../../resources/seon/schemas/seon.sci.eval.edn#L56)); `seon.db` appends evidence to it ([src/seon/db.clj:69-71](../../../../src/seon/db.clj#L69), [src/seon/db.clj:155-180](../../../../src/seon/db.clj#L155)). Program publication only. |
| `effect/*context*` | The current capability request's durable identities and execution controls | `effect/*request-context*` | Its declared shape is already `:seon.effect/request-context` ([resources/seon/schemas/seon.effect.edn:51-67](../../../../resources/seon/schemas/seon.effect.edn#L51)), while the Var has the generic name ([src/seon/effect.clj:26-28](../../../../src/seon/effect.clj#L26)). Program publication only. |
| `:my.edit/context`, `/context-complete?` | A bounded UTF-8 source window around an edit | `:my.edit/source-window`, `/source-window-complete?` | The producer function is `context-window` and returns a substring of `source` ([src/seon/edit.clj:51-73](../../../../src/seon/edit.clj#L51)); the result contract exposes it as a string ([resources/seon/schemas/my.edit.edn:46-60](../../../../resources/seon/schemas/my.edit.edn#L46)). Program publication only. |

The read-evidence rename must also declare the data it collects. The public
producer and freshness predicate currently accept `[:vector :map]` even though
they destructure a stable set of Datahike dependency-plan, revision, source
position, and optional replay fields
([src/seon/db.clj:247-307](../../../../src/seon/db.clj#L247)). Use
`:seon.db/captured-read`, `:seon.db/read-evidence`, and the dependency's existing
`datahike.read/*` keys rather than another context-shaped wrapper.

The first rename is cheap now but more expensive after additional bootstrap
plans exist. The other three should land together only if their source owners
do not overlap; semantically they are independent atomic units.

### R6 — High future migration cost: schedule values use object/category names instead of dependency value names

**What a reader will get wrong.** `:seon.schedule/cron` sounds like the parsed
cron object, while it actually stores the expression string. `timezone` sounds
like a time-zone object or policy, while it stores the identifier passed to
`ZoneId/of`.

**Evidence.** The schema declares both as nonempty strings and stores them on
the schedule entity
([resources/seon/schemas/seon.schedule.edn:1-14](../../../../resources/seon/schemas/seon.schedule.edn#L1)).
The source consistently calls the first local `expression`, parses it into a
`Cron`, and calls the second through `ZoneId/of`
([src/seon/schedule.clj:28-53](../../../../src/seon/schedule.clj#L28),
[src/seon/schedule.clj:87-120](../../../../src/seon/schedule.clj#L87)). The
dependency calls its string parameter `expression` and returns a `Cron`
([reference-code/cron-utils/src/main/java/com/cronutils/parser/CronParser.java:87-96](../../../../reference-code/cron-utils/src/main/java/com/cronutils/parser/CronParser.java#L87)).

**Proposed names.** Rename the persisted attributes to
`:seon.schedule/expression` and `:seon.schedule/zone-id`. Keep
`next-nominal-after`, `latest-nominal-at-or-before`, fire `/nominal-at`, and
fire `/observed-at`; those names correctly distinguish the dependency-derived
scheduled instant from the process observation
([resources/seon/schemas/seon.schedule.fire.edn:1-15](../../../../resources/seon/schemas/seon.schedule.fire.edn#L1)).

Delete the unused weaker `:seon.schedule.fire/transaction-data [:vector :map]`;
`fire-call` already returns the canonical Datahike-grounded
`:seon.store/transaction-data`
([resources/seon/schemas/seon.schedule.fire.edn:16-25](../../../../resources/seon/schemas/seon.schedule.fire.edn#L16),
[src/seon/schedule.clj:155-170](../../../../src/seon/schedule.clj#L155)).

**Cost.** Expression and zone ID are actual cardinality-one attributes on
schedule entities, so this is a cluster-data migration. Land it before schedules
are populated broadly: transact new attributes from old values, update all
queries/contracts/tests/docs, verify, then retract the old attributes in the
same planned migration. The unused schema deletion is program publication only.

### R7 — Medium-high ownership cost: bootstrap namespace “designation” is on the wrong owner

**What a reader will get wrong.** `:seon.ns/name-designation` looks like a
general property of a namespace, but it exists only on bootstrap form entities
and tells the bootstrap consumer where to obtain that form's execution
namespace.

**Evidence.** The key is declared under `seon.ns` as `:agent` or `:user`
([resources/seon/schemas/seon.ns.edn:4-8](../../../../resources/seon/schemas/seon.ns.edn#L4)),
but every stored use is on the bootstrap form
([resources/seon/schemas/seon.bootstrap.plan.form.edn:2-13](../../../../resources/seon/schemas/seon.bootstrap.plan.form.edn#L2),
[resources/seon/bootstrap.edn:1-43](../../../../resources/seon/bootstrap.edn#L1)).
The consumer resolves the value into an actual `:seon.ns/name`: `:agent` means
the assigned agent namespace and `:user` means the symbol `user`
([src/seon/bootstrap.clj:175-222](../../../../src/seon/bootstrap.clj#L175)).

**Proposed name.** `:seon.bootstrap.plan.form/namespace-source`, preserving the
existing `:agent`/`:user` values. “Source” says where the consumer obtains the
namespace; “designation” adds no information and strands bootstrap policy under
the namespace entity owner.

**Cost.** Stored current-source/bootstrap-plan datoms make this a cluster-data
migration/refork. It is only six files today and should land with the bootstrap
`help-text` rename.

### R8 — Medium-high proof cost: curation's important intermediate values are named but not shaped

**What a reader or agent will get wrong.** A proof can claim to contain
receipts, a terminal state, and declaration equivalence while the contract
accepts arbitrary maps. The names sound stronger than the data contract.

**Evidence.** The curation registry declares `/receipts [:vector :map]`,
`/terminal :map`, and `/declarations [:vector :map]`
([resources/seon/schemas/seon.cluster.curate.edn:1-26](../../../../resources/seon/schemas/seon.cluster.curate.edn#L1)).
The producer already emits deterministic declaration maps with run ID, ordinal,
program identity, source attribute, and source
([src/seon/cluster/curate.clj:75-116](../../../../src/seon/cluster/curate.clj#L75));
it pulls a fixed proof-receipt projection
([src/seon/cluster/curate.clj:272-290](../../../../src/seon/cluster/curate.clj#L272));
and its terminal producer is explicitly `eval.drive/terminal-state`
([src/seon/cluster/curate.clj:119-123](../../../../src/seon/cluster/curate.clj#L119)).

**Proposed names.** Declare `:seon.cluster.curate/declaration`,
`:seon.cluster.curate/proof-receipt`, and
`:seon.eval.drive/terminal-state`, then define the existing plural keys in
terms of them. Use `:seon.cluster.eval/receipt` only if the projection is changed
to contain that full existing entity shape; do not lie by aliasing a partial
pull to it.

The curation names that do exist are good. `revision` deliberately reuses the
ordered reply-source shape
([resources/seon/schemas/seon.cluster.curate.edn:1-3](../../../../resources/seon/schemas/seon.cluster.curate.edn#L1),
[resources/seon/schemas/seon.cluster.reply.edn:1-6](../../../../resources/seon/schemas/seon.cluster.reply.edn#L1));
`proof-branch`, `adopted-commit-id`, `proof`, and `adoption` match the producer;
and adoption writes direct run-to-run `:seon.cluster.run/supersedes` refs
([src/seon/cluster/curate.clj:301-347](../../../../src/seon/cluster/curate.clj#L301)).
The editor is absent because W3 is not implemented, not because its data is
misnamed: the active PRD records editor plus trigger as the next wave
([docs/prds/sci-execution-runtime/plan/session-curation-prd-2026-08-04.md:223-233](../plan/session-curation-prd-2026-08-04.md#L223)).
W3 must declare its editor request and revision response before implementation.

**Cost.** Program publication only and cheap now. Tightening these shapes after
the editor exists would multiply the call sites and fixtures.

### R9 — Medium cost: three umbrella nouns hide existing producer terms

#### `:my.background/descriptor` is an effect-receipt projection

The schema contains only effect-receipt fields
([resources/seon/schemas/my.background.edn:1-19](../../../../resources/seon/schemas/my.background.edn#L1)).
`poll` calls the pulled value `receipt`, then changes the contract/result local
to `descriptor`
([src/my/background.clj:36-74](../../../../src/my/background.clj#L36)). The
system owner already calls the durable entity `:seon.effect/receipt`
([resources/seon/schemas/seon.effect.edn:13-50](../../../../resources/seon/schemas/seon.effect.edn#L13)).
Rename the partial agent-facing shape to `:my.background/receipt`; do not reuse
the full entity schema because this projection intentionally omits required
owner/run/opened fields. Program publication only.

#### `:seon.source/population-data` is a populate request fragment

`source/publish!` merges this map with the connection and source digest, then
passes the result to the function named by `:seon.source/populate`
([src/seon/cluster/source.clj:121-170](../../../../src/seon/cluster/source.clj#L121)).
The sole producer currently supplies a function manifest
([src/seon/cluster.clj:842-848](../../../../src/seon/cluster.clj#L842)), and the
consumer is `populate-source!`
([src/seon/cluster.clj:747-789](../../../../src/seon/cluster.clj#L747)).
Rename it `:seon.source/populate-request` and declare the open request shape
with connection, digest, and optional manifest. “Data” says nothing; “request”
is the producer/consumer boundary already prescribed by conventions. Program
publication only.

#### `:seon.code.def/unrestorable` stores a reason

The schema assigns the attribute a nonempty string
([resources/seon/schemas/seon.code.def.edn:19-33](../../../../resources/seon/schemas/seon.code.def.edn#L19)),
and the session-image contract explicitly calls it an “unrestorable reason”
([.agents/skills/data-oriented-clojure/references/program-state.md:39-53](../../../../.agents/skills/data-oriented-clojure/references/program-state.md#L39)).
Rename it `:seon.code.def/unrestorable-reason`. Existing session-image entities
carry the old attribute, so this is a cluster-data migration. Keep
`:seon.code.def/source`: every program family has a source-form attribute and
the restore owner consumes exactly that proven source.

### R10 — Medium-low cost: render profile and footprint names overpromise their data

#### Render profile

`token-budget`, `max-depth`, `max-children`, and `composition` are all consumed:
`seon.print/fit` uses the first three
([src/seon/print.cljc:750-785](../../../../src/seon/print.cljc#L750)), and the
value renderer uses `composition` to choose single-line or tabular emission
([src/seon/render/value.clj:240-256](../../../../src/seon/render/value.clj#L240)).
`blob-threshold` is copied from eval-result config into every render profile
([src/seon/render.clj:37-57](../../../../src/seon/render.clj#L37)) but has no
consumer; its only other source constructs legacy profile maps
([src/seon/render/value.clj:329-355](../../../../src/seon/render/value.clj#L329)).

Remove `:seon.render.profile/blob-threshold` from the profile until a render
consumer actually owns blob fitting. The data currently belongs to
`:seon.config.eval.result/blob-threshold`, where result storage uses it. Also
remove the vocabulary claim that the current profile fits blobs. This is
program publication only; the profile is derived, not stored.

#### Operator footprint

`footprint` recursively sums `Files/size`, so `/bytes` is logical file length,
not the “allocated bytes” promised by the docstring
([resources/seon/operator/state.clj:239-267](../../../../resources/seon/operator/state.clj#L239)).
Cleanup copies that number to `/reclaimed-bytes` without measuring a before/
after filesystem-space delta
([src/seon/operator.clj:176-203](../../../../src/seon/operator.clj#L176)).
Rename the first to `:seon.operator.footprint/file-bytes` and the second to
`:seon.operator.cleanup/removed-file-bytes`, unless the implementation changes
to measure allocated/reclaimed storage. The existing `/root`, `/usable-bytes`,
`/total-bytes`, `/usable-ratio`, and `/observed-at` fields accurately describe
their values.

The returned `:seon.operator.footprint/low-space?` is request-relative and is
not declared in the footprint schema
([src/seon/operator.clj:148-167](../../../../src/seon/operator.clj#L148),
[resources/seon/schemas/seon.operator.edn:39-46](../../../../resources/seon/schemas/seon.operator.edn#L39)).
Declare a `:seon.operator/footprint-observation` containing the measured
footprint plus `:seon.operator/low-space?`, rather than making a threshold
classification look intrinsic to the footprint.

Operator footprint/cleanup/log leaf keys and entire durable root/process claim
records are also absent from the registry; `:seon.operator/existence` and
`:seon.operator/log-result` are bare `:map`
([resources/seon/schemas/seon.operator.edn:39-62](../../../../resources/seon/schemas/seon.operator.edn#L39)).
Declare these existing shapes under their current producer namespaces in the
same operator-contract unit. The byte-field renames affect durable root-claim
EDN because the footprint and cleanup result are stored on the claim
([resources/seon/operator/state.clj:269-300](../../../../resources/seon/operator/state.clj#L269));
they are operator-record migrations.

## Today's new-work shape audit

| Shape | Assessment | Data owner and evidence | Action |
|---|---|---|---|
| schedule | Mostly good entity ownership; expression and zone ID are misnamed strings | Schedule owns expression/zone; task points to schedule ([resources/seon/schemas/seon.schedule.edn:1-14](../../../../resources/seon/schemas/seon.schedule.edn#L1)) | Rename persisted leaves per R6 |
| task | Good | Task owns stable ID and refs to owner agent, function, schedule ([resources/seon/schemas/seon.schedule.task.edn:1-13](../../../../resources/seon/schemas/seon.schedule.task.edn#L1)) | Keep |
| fire | Good | Fire owns identity, task ref, nominal instant, observation instant ([resources/seon/schemas/seon.schedule.fire.edn:1-15](../../../../resources/seon/schemas/seon.schedule.fire.edn#L1)) | Keep; delete unused transaction-data alias |
| render profile | Four of five policy fields are owned and consumed; blob threshold is stranded | Profile producer and fitter/emitter ([resources/seon/schemas/seon.render.profile.edn:1-21](../../../../resources/seon/schemas/seon.render.profile.edn#L1)) | Remove stranded field per R10 |
| elision value | Good and unusually precise | Carries omitted count/unit, total, path, next offset, profile, and requery identity or refusal ([resources/seon/schemas/seon.print.edn:214-263](../../../../resources/seon/schemas/seon.print.edn#L214)); producer fills the same fields ([src/seon/print.cljc:602-613](../../../../src/seon/print.cljc#L602)) | Keep |
| external sink + projection boundary | Good | Function metadata is lifted to queryable leaf facts and the path report queries those exact facts ([resources/seon/schemas/seon.fn.edn:5-6](../../../../resources/seon/schemas/seon.fn.edn#L5), [resources/seon/schemas/seon.fn.edn:30-35](../../../../resources/seon/schemas/seon.fn.edn#L30), [src/seon/fn.clj:292-353](../../../../src/seon/fn.clj#L292)) | Keep |
| root claims | Concept is correct and outside the database by design; durable record shape is undeclared | Atomic claim owner records root, store, lifecycle, clusters, process records, footprint, cleanup ([resources/seon/operator/state.clj:116-157](../../../../resources/seon/operator/state.clj#L116)) | Declare; canonicalize process record per R3 |
| process claims | Wrong namespace and duplicate identity keys/shapes | Operator record + runtime `ProcessHandle` owner (R3) | Migrate durable files per R3 |
| footprint | Right entity, two byte names overpromise and low-space classification is mixed in | R10 | Rename/declare per R10 |
| curation supersedes | Good | Cardinality-many ref is stored on the adopted run and adoption writes direct original refs ([resources/seon/schemas/seon.cluster.run.edn:15-15](../../../../resources/seon/schemas/seon.cluster.run.edn#L15), [src/seon/cluster/curate.clj:329-338](../../../../src/seon/cluster/curate.clj#L329)) | Keep |
| editor | Correctly absent: not built yet | W3 remains pending ([docs/prds/sci-execution-runtime/plan/session-curation-prd-2026-08-04.md:223-233](../plan/session-curation-prd-2026-08-04.md#L223)) | Declare request/response before implementation |
| revision | Good semantic alias over an existing ordered source vector | Curation revision aliases reply sources ([resources/seon/schemas/seon.cluster.curate.edn:1-3](../../../../resources/seon/schemas/seon.cluster.curate.edn#L1)) | Keep |
| proof | Names are good; three nested shapes are weak | R8 | Declare nested shapes now |

## Overloaded-word calibration

The systematic local-name sweep found expected repetitions such as `id`,
`request`, `value`, `path`, `source`, `result`, `process`, and `context`.
Namespaced keys make most of these harmless. The risk ranking is:

1. **Context:** four concrete wrong names in R5 plus three correct concepts
   which must remain distinct. Highest frequency and highest daily cognitive
   cost.
2. **Process:** OS identity map, run-holder string, operator process record, and
   transaction-provenance process. R3 removes the unqualified duplicate while
   keeping genuinely distinct qualified owners.
3. **Connection / database:** host values cross many owners and currently change
   schema/map-key names; R4 canonicalizes dependency vocabulary.
4. **Source:** `:seon.fn/source`, `:seon.ns/source`, `:seon.test/source`, and
   `:seon.code.def/source` all mean the owning declaration's exact source form;
   `:seon.source/*` means publication. Qualification is sufficient. Keep.
5. **Request/result/value/id:** repeated shapes describe different APIs and
   entities. Their family namespaces and named enclosing schemas prevent a real
   mistake. Keep.
6. **State:** the only material registry leaf is
   `:seon.cluster.work/form-state`, and it holds exactly the finite lifecycle
   state it promises
   ([resources/seon/schemas/seon.cluster.work.edn:8-33](../../../../resources/seon/schemas/seon.cluster.work.edn#L8)). Keep.

## What is already in good shape

- Database identity projections preserve Datahike's own `:db-name`, `:t`, and
  `:datahike/commit-id` rather than inventing a coordinate map
  ([resources/seon/schemas/seon.db.edn:20-34](../../../../resources/seon/schemas/seon.db.edn#L20)).
- `:seon.db/tx-data` is an honest alias to the store's canonical transaction
  data, and transaction reports use Datahike's `tx`, `tx-data`, `tempids`, and
  commit ID
  ([resources/seon/schemas/seon.db.edn:79-89](../../../../resources/seon/schemas/seon.db.edn#L79)).
- Exact duplicate primitive schemas such as identity strings, instants, counts,
  and booleans generally represent distinct domain attributes, not duplicate
  concepts. Shared meanings reuse existing schemas—for example byte counts use
  `:my.fs/byte-count` across shell and web results.
- `:seon.fn/sym` and `:seon.test/sym` intentionally share a representation but
  identify different queryable entities; collapsing them would lose data.
- `:seon.fn/roots` and `:seon.source/roots` share a vector shape but not a
  meaning: the source digest includes non-Clojure schema/bootstrap resources
  beyond the function analyzer roots
  ([src/seon/cluster.clj:792-804](../../../../src/seon/cluster.clj#L792)).
- `:seon.operator/repository-root` and `/managed-root` correctly reuse
  `:seon.boot/root` as a value schema while retaining their different roles in
  the request
  ([resources/seon/schemas/seon.operator.edn:1-18](../../../../resources/seon/schemas/seon.operator.edn#L1)).
- Curation's editor/revision/proof/adoption vocabulary matches the ruled
  workflow. The missing work is contract strength, not another set of nouns.

## Ordered rename and declaration plan

Each row is one atomic unit. Units that share persisted data are deliberately
not mixed with cheap source-only renames.

| Order | Atomic unit | Exact change | Blast radius | Existing data and gate | Timing |
|---:|---|---|---|---|---|
| 1 | Abandon stale `seon.code.*` migration | Correct the AGENTS vocabulary row and active parse-plan references; explicitly distinguish `:seon.code.def` session-image facts | 2 maintained docs; review archived/research references but do not rewrite history | No data. Markdown gate and active-doc search | **Cheap now; catastrophic if deferred and followed** |
| 2 | Declare program-owned shapes | Add program identity/declaration/deletion/row/rows/shape schemas; add manifest/artifact schemas; replace evaluator/source weak aliases; move canonical schema-row declarations from source to registry after probe | `resources/seon/schemas/seon.program.edn`, `seon.fn.edn`, `seon.sci.eval.edn`, `seon.source.edn`; `src/seon/program.cljc`, `src/seon/fn.clj`, `src/seon/sci/eval.clj`, `src/seon/cluster/source.clj`; approximately 17 direct program-row files plus tests/docs/skills | Program facts only. REPL admission-cycle probe, focused program/eval/source tests, republish current-src, refork proof cluster | **Cheap now; expensive after editor W3** |
| 3 | Strengthen proof values before W3 | Declare proof receipt, declaration, and eval-drive terminal-state shapes; require editor request/revision-response schemas in W3 | Curation/eval-drive schemas and source, curation tests, session-curation PRD | In-memory proof maps; program facts only. Focused proof/adoption tests and live proof branch/adoption query | **Cheap now; expensive after editor fan-out** |
| 4 | Canonicalize database value key | `:seon.db/db` → `:seon.db/database-value`; locals `db` → `database` only where they hold the value | Approximately 42 source/schema/test files; render, prompt, SCI eval, db read-evidence docs/skills | No domain datoms; program facts only. Focused db/render/SCI tests and a live identity projection | **Cheap semantically; broad mechanical edit now** |
| 5 | Canonicalize live connection | `:seon.store/branch-connection` → `:seon.db/connection`; lifecycle union → `:seon.store/connection-object` | Approximately 55 source/schema/test files; db/store/source/effect/render/loop/operator docs and skills | No host object can be stored as a domain datom; program facts only. Connection/release property tests plus live cluster start/stop/source publication | **Cheap now; broad and growing** |
| 6 | Name read-evidence data | `capture-context`/`*capture-context*` → `read-evidence-sink`/`*read-evidence-sink*`; declare captured-read and read-evidence vector shapes | 8 direct files; `seon.db`, SCI kernel/eval, render call cache, tests/schemas | In-memory only; program facts. Datahike query/pull evidence probe and render cache invalidation test | **Cheap now** |
| 7 | Remove other false “context” names | `effect/*request-context*`; `my.edit/source-window`; `my.edit/source-window-complete?` | Effect/Shell/SCI eval and edit owners, six direct edit files, tests/schemas/docs | In-memory only; program facts. Focused effect and edit properties | **Cheap now** |
| 8 | Rename umbrella result/request shapes | `my.background/receipt`; `seon.source/populate-request`; declare operator result/claim leaves without changing persisted names yet | Background, source publication, operator schemas/tests/docs | Background/source are program facts only; operator declarations describe existing files. Focused background/source/operator tests | **Cheap now** |
| 9 | Bootstrap persisted rename | `/context` → `/help-text`; `/name-designation` → `/namespace-source` | 9 help-context files and 6 designation files: bootstrap resource/source/schemas/tests/docs and current-source initialization | Stored bootstrap form datoms. Publish a new current-src and explicitly refork/reset target clusters; prove help output and ordered sources | **Cheap while only one shipped plan exists; expensive later** |
| 10 | Schedule persisted rename | `/cron` → `/expression`; `/timezone` → `/zone-id`; delete fire-local transaction-data alias | Schedule schemas/source/tests/docs, schedule Datalog queries, config/bootstrap data if any | Actual schedule entities carry old attrs. Explicit new-attribute transaction, query/readback, old-attribute retract; restart derivation/live fire proof | **Do before schedule population grows** |
| 11 | Operator process-record migration | `:seon.dev.process/*` → declared `:seon.operator.process-record/*`, reusing boot PID/start instant and `:inst`; rename `cluster/process-identity` projection function | 22 direct files across launcher/state/MCP/tests/docs; process claim filenames remain generation-based | Durable EDN claim files outside DB. Atomic rewrite under lifecycle lock, exact process identity readback, status/down/start proof; no compatibility reader left behind | **Do before maintenance portfolio adds consumers** |
| 12 | Session-definition reason rename | `/unrestorable` → `/unrestorable-reason` | Session-image schema, program owned attrs, loop/eval render/tests, architecture/docs/skills | Existing session-image datoms. New attribute plus explicit copy/retract and cold-restore proof | **Small population now; migration cost grows per session** |
| 13 | Footprint semantic rename | `/bytes` → `/file-bytes`; `/reclaimed-bytes` → `/removed-file-bytes`; separate footprint observation from low-space assessment | Operator state/source/schema/launcher/tests/docs and status/reset output | Durable root-claim footprint and cleanup EDN. Atomic record rewrite or explicit discard/re-observe under lock | **Moderate now; coordinate with process-record migration if same files, but commit separately** |
| 14 | Remove unused render-profile field | Drop profile `/blob-threshold` and correct the vocabulary claim, or first establish a real render consumer | Render profile schema/producer, render-value legacy helpers/tests, AGENTS/docs/skills | Derived values only; program facts. Render fit/emission properties and live page/context render | **Cheap; low urgency** |

Units 4 and 5 are mechanically broad and must be separate commits despite both
touching request maps. Unit 11 and unit 13 touch the same durable operator files
and therefore cannot run concurrently, but they remain separate semantic/data
migrations. Units 9, 10, and 12 must introduce new attributes; silently changing
the old attribute definition would violate the accretion contract.

## Graduation evidence for the rename wave

The complete later rename wave is done only when:

- active maintained docs contain no future `seon.code.fn`/`.ns`/`.schema`/
  `.test` instruction;
- every program declaration/deletion crossing validates against a
  `seon.program`-owned schema and no consumer declares `program-row :map`;
- the registry has declarations for the stable proof, read-evidence, operator,
  manifest, and population-request shapes identified here;
- a search finds no `:seon.dev.process/*`, `:seon.db/db`,
  `:seon.store/branch-connection`, false `capture-context`, or false
  bootstrap/edit context keys in current source, schemas, tests, active docs, or
  skills;
- current-src is republished, an explicitly reforked cluster proves the new
  program facts, migrated schedule/bootstrap/session datoms read back under only
  the new attributes, and operator claim files read back under only the new
  record schema; and
- schedule fire, session cold restore, proof/adoption, source publication,
  cluster start/stop, and rendered agent/page output are proven live at their
  respective boundaries.

## Ugly output encountered

The raw whole-registry dump was 43,931 tokens and was truncated, dominated by
the large recursive `:seon.print/node` form and repetitive generated error
shapes. That output is not a usable audit surface. The filtered semantic passes
(weak forms, exact duplicates, repeated local names, suffixes, and missing map
keys) were readable and are the evidence used above.

The repository MCP `runtime_status` call also returned only
`Could not locate seon/operator/state.bb, seon/operator/state.clj or
seon/operator/state.cljc on classpath`, even though the owner exists at
`resources/seon/operator/state.clj`. This did not block the naming audit;
`bin/seon status` successfully derived 11 live clusters from claims and
advertisements. The error face is concise, but it omits the searched classpath
and selected operator root, which makes the mismatch unnecessarily hard to
diagnose.
