---
type: research
status: active
tags: [research, runtime, architecture]
---

# State B merged cluster JVM boot design draft (2026-07-26)

This is the single boot-sequence draft for owner review. It describes the
target State B topology, not the current process layout and not an
implementation plan.

Every design choice introduced here is labeled **PROVISIONAL** and lists the
alternatives considered. Source-settled constraints are labeled **SETTLED**.
An unlabeled sentence reports source evidence or a consequence of an already
labelled choice; it is not a new ruling.

## Outcome

The target boot is one dependency-ordered construction with two modes:

- ordinary start attaches only to a completely applied cluster; it never
  derives initialization pages or reconciles a manifest; and
- explicit apply, including reset, installs the current release and selected
  manifest before taking the same attach path.

The canonical stage sequence is:

`process admission → desired-fact derivation → L6 writer refusal/open → apply
or applied-identity proof → sci ctx materialization/install → run-loop arming
→ web-render replica attach → readiness publication`.

That sequence corrects one physical impossibility in the requested starting
spine: configuration cannot be committed as database facts before a database
connection exists. It also preserves R45's settled split between derive-once,
install, and attach:

- **derive-once**: the build emits digest-bound program rows, base projection,
  and initialization pages once per release and selected manifest;
- **install**: explicit apply commits those pages and the manifest's desired
  facts into one cluster database; and
- **attach**: an ordinary process verifies the applied identity, materializes
  process-local objects, installs live components, and publishes readiness.

The sci base `ctx` is necessarily rematerialized once per process because it
contains mutable sci Vars and functions. Its load order and parsed forms may be
derived once into release data; the live `ctx` itself cannot be persisted.

## Vocabulary

- **Program graph** means the current `:seon.fn`, `:seon.ns`, and
  `:seon.schema` facts. The pending spelling is `seon.code.fn`,
  `seon.code.ns`, and `seon.code.schema`; evidence in this document uses the
  current attribute names.
- **Plan execution** uses `reduce` over ordered forms.
- **Run loop** names the database-interest-driven runtime. The
  `seon.agent.driver` symbol is cited only as the current source owner.
- Genuine effects and capability requests use the one **effect/request path**.
  Its final name is pending; this draft does not invent one.
- Sci terms retain sci's names: `ctx`, `fork`, and `:interrupt-fn`.
- Database terms retain Datahike's names: database value, basis transaction,
  commit ID, connection ID, store ID, branch, and transaction report.

## Dependency ledger

| Dependency or owner | Revision read | Evidence used |
|---|---|---|
| Seon working tree | `4dbaeda0ef905c07600637e86df5d5de8fc7e725` | `src/seon/host.clj`; `src/seon/launch.cljc`; complete `src/seon/config/resolve.cljc`; `script/seon/dev/process.clj`; `script/seon/dev/state.clj`; `bin/seon`; relevant `script/seon/dev/{cli,cluster,program_artifact}.clj`; current `src/seon/{client,web/server,agent/driver}.clj*`. |
| R45 pre-processing authority | working tree | `preprocessing-design-2026-07-23.md`: release-scoped derive-once, explicit per-cluster apply/install, start as verify/load/attach, process-local sci base, crash-recoverable initialization receipts. |
| Runtime plan | working tree, read-only | `plan/README.md` steps 5–6 and L6/L7/L18: one compile-time program producer, mandatory pages, one write connection per store, event-armed lease wakes, reset to current code/pages. |
| Integrant decision | Seon commit `bd803841971a158fdf810ab443e078cea7f9f822` | Conditional narrow adoption only with the topology merge; one root system plus one nested system per cluster; resolved-value `ig/assert-key` choke point; no custom suspend/resume; flat `refset` reset risk. |
| Integrant | `bcad6bcf35b62d3a32a453dc26b6d3a4d659dc01` | Refs resolve before `assert-key`; init is dependency ordered; halt is reverse ordered; partial system is carried in build exception data; `init-key` return is the only built-in readiness boundary. |
| Datahike | `caf526850084` | `create-writer :self` constructs a `LocalWriter` from one connection; connection ID is `[store-id branch]` for `:self`; distinct connections create distinct writers. N distinct stores in one process are legal. |
| Sci | `8fac6e88f32d` | A `ctx` contains live Vars/functions; `fork` shares existing Vars; `:interrupt-fn` is the one time-limit mechanism. |
| JVM render investigation | working tree | `jvm-render-design-2026-07-26.md`: authored work stays in the cluster JVM; web-render consumes ordinary data and an immutable database value; current remote-session implementation is not yet a replica; attach and bounded feed questions remain. |

The current operator graph is watcher → writer and host, then pod, then
web-render; readiness is a mixture of log marker, socket probe, and HTTP
probe. `src/seon/host.clj` separately opens a remote writer session, starts
sci evaluation, and arms the run loop. State B deletes those separate
composition owners only when the merged Integrant series replaces them.

## Settled constraints

1. **SETTLED — Integrant is conditional, not preparatory scaffolding.** It
   lands only in the same series that deletes the standalone writer, host, and
   web-render composition code. Wrapping the current 61-line host is rejected.
2. **SETTLED — multiplicity is root plus nested systems.** One root Integrant
   system owns process-wide resources; each selected cluster has one nested
   system. A flat `refset` graph is rejected because halting one cluster could
   transitively halt shared resources.
3. **SETTLED — one write connection per store.** A second write connection to
   the same store must refuse before Datahike creates another `LocalWriter`.
   Distinct stores may each have one connection in the same JVM.
4. **SETTLED — pages are mandatory.** The step-5 JVM compile-time indexer is
   the one first-party producer of program rows and initialization pages.
   Missing or digest-mismatched pages refuse loudly. The current
   `seon.db.protocol/initialization-pages` runtime derivation branch is target
   deletion, not a recovery path.
5. **SETTLED — ordinary start never installs.** R45 assigns page installation,
   configuration reconciliation, and initial-agent creation to explicit
   apply. Start verifies, loads, attaches, and advertises.
6. **SETTLED — reset is replacement, not migration.** A reset destroys one
   cluster database and installs current code and current pages. No migration
   path is part of State B.
7. **SETTLED — run-loop wakes remain event-armed.** Database `listen!` drives
   committed changes. Lease passage requires the existing declared
   `arm-lease-wake!` event; a general polling clock is forbidden. A protective
   timeout firing is a core bug report.
8. **SETTLED — no custom Integrant suspend/resume initially.** Halt/init and
   late-bound Vars are the development loop until measurement proves one
   resource needs custom resume.
9. **SETTLED — one validation choke point.** `ig/assert-key` validates every
   fully resolved component input using the owning namespace's Malli schema.
   A central duplicate schema registry is rejected.

## Provisional decision register

### P1 — Where manifest reconciliation occurs

**PROVISIONAL CHOICE:** before store open, resolve the selected manifest into a
closed desired-fact value only. Commit it after the L6 refusal gate and after
the initialization schema exists. On ordinary start, compare identities and
do not reconcile.

Alternatives:

- commit configuration before store open — impossible because no database
  connection exists;
- reconcile on every start — rejected by R45 because start would become an
  install/derivation path; or
- include the complete desired config population directly in build-time
  initialization pages — possible, but it would need parity proof with the
  one provenance-scoped `seon.runtime.state/reconcile!` owner and would change
  current apply semantics.

### P2 — The L6 refusal primitive

**PROVISIONAL CHOICE:** acquire one exclusive, kernel-released lock keyed by
the canonical file-store path before `d/connect`, retain it for exactly the
connection lifetime, and include the process record's `(pid, start-instant)`
identity in diagnostics. Each distinct store path has a distinct lock, so N
stores in one JVM remain legal.

Alternatives:

- add an equivalent exclusive-open primitive inside maintained
  Datahike/Konserve — architecturally stronger and worth preferring if the
  dependency has a natural store-open hook;
- rely only on `bin/seon` process ownership — insufficient because the L6
  falsifier starts two JVMs directly; or
- commit a database lease before opening — circular and unsafe, because
  acquiring the lease itself requires the second connection the gate must
  prevent.

This is the only proposed pre-database coordination mechanism. Whether the
owner accepts this narrow exception to “all coordination is committed facts”
is an explicit owner question below.

### P3 — Existing-store open versus fresh-store creation

**PROVISIONAL CHOICE:** one writer component owns both cases after P2:

- ordinary start opens an existing database and refuses if the complete
  applied identity is absent or mismatched; and
- apply/reset supplies the mandatory first initialization page to the existing
  create/ensure path, then continues the remaining receipt-tracked pages.

Alternatives:

- separate create and connect Integrant keys — clearer mechanically but risks
  two database construction paths; or
- let ordinary start create an absent database — rejected because start would
  silently become apply.

### P4 — Applied identity completion

**PROVISIONAL CHOICE:** the existing
`:seon.db.initialization/id "database"` entity is the one install receipt.
Its status becomes `complete` only after all mandatory pages, manifest
reconciliation, launch/config proof, and initial-agent creation succeed. The
release digest, config-manifest digest, initialization fingerprint, page
count, and the completion transaction's basis transaction and commit ID are
sufficient completion evidence; the latter two come from the transaction
report and are not duplicated onto the receipt. No separate ready flag is
added.

Alternatives:

- preserve today's stamp after those operations but leave page completion as a
  separate earlier status — less refactoring, but an observer could mistake
  page completion for complete install; or
- create another boot ledger entity — rejected as a second authority.

### P5 — Shared sci base and per-cluster program installation

**PROVISIONAL CHOICE:** the root system rematerializes one release-scoped base
`ctx` from digest-verified first-party program rows and the precomputed load
order. Each nested cluster system verifies that those rows are installed in
its program graph, acquires that cluster's divergent program facts at one
database value, and installs the cluster program projection used to populate
fresh `fork`s. Agent-authored definitions replay lazily into a fresh fork at
run acquisition, not during process boot.

Alternatives:

- one complete base `ctx` per cluster — semantically simple but repeats the
  unchanged release population N times;
- one process-wide `ctx` containing every cluster's divergence — rejected
  because cluster-private definitions would share live Vars; or
- persist an analyzed sci snapshot — impossible with sci's live Var and
  closure representation.

“Install” here means install process-local live values from verified program
facts. It does not commit another copy of the program graph.

### P6 — Run-loop arming contract

**PROVISIONAL CHOICE:** the run-loop component's init call returns only after
its database listener is registered and one synchronous recovery scan has
observed a database value at or after the installed program basis. The
returned component owns the listener and all explicit lease-wake
cancellations; halt removes them before the writer closes.

Alternatives:

- return immediately and scan asynchronously — exposes a false-ready window;
- poll for pending runs — rejected by L7; or
- make Integrant supervise each run — rejected because durable run process,
  epoch CAS, leases, and receipts already own recovery.

### P7 — Web-render attachment in State B

**PROVISIONAL CHOICE:** “web-render replica attach” is a process-wide root
component that attaches one immutable replica session per nested cluster only
after that cluster's install identity is complete and run loop is armed. The
HTTP component resolves current cluster slots through the root registry; it
does not capture a writer connection. Authored code never runs in web-render.

Alternatives:

- retain the current remote `db.host/writer-session` as the first State B
  slice and call it an attachment, not a replica — implementable sooner but
  does not satisfy the recorded replica target;
- place trusted web rendering inside each nested cluster system — makes
  one-cluster reset simpler but duplicates process-wide HTTP resources; or
- make web-render a separate JVM as in the architecture target — preserves
  process isolation, but conflicts with the Integrant merge report's proposed
  root HTTP component unless the root configuration is split across two JVM
  systems.

The process placement is therefore not silently decided by this draft; the
owner must reconcile the Integrant merge decision with the still-current
three-process architecture.

### P8 — Readiness publication

**PROVISIONAL CHOICE:** every component's init function returns only when its
own contract is ready. A final root readiness component atomically publishes a
closed data value naming process generation, selected clusters, each cluster's
applied identity and database value, run-loop listener identity, replica
database value, and HTTP endpoint. The operator consumes that value directly;
log text is diagnostic only.

Alternatives:

- a generation-scoped readiness EDN file beside the process record — easiest
  evolution from current state files;
- a generation-scoped local control socket returning the same closed value —
  stronger request/response semantics but a new operator protocol; or
- retain `HOST READY`, socket existence, and HTTP polling — rejected as the
  primary contract because the interfaces themselves do not publish their
  dependency completion.

The publication carrier remains open. The readiness value's fields and Malli
schema are part of P8, not settled names.

### P9 — One core-error dial at boot

**PROVISIONAL CHOICE:** the resolved `:seon.config/on-core-error` value governs
every post-open boot failure. The owning stage records one bounded
`:seon.error/fault :core` before returning failure:

- `:crash` records, then terminates the process;
- `:gate` records and refuses the affected cluster or process readiness; and
- `:log` records and keeps the process alive with that cluster slot
  unavailable and a bounded production fallback where one is defined.

Before the database can open, the same selected dial controls the boundary but
cannot persist into that database: the launcher emits one bounded loud
diagnostic and either terminates/refuses readiness (`:crash`/`:gate`) or keeps
the root alive with the cluster unavailable (`:log`).

Alternatives:

- hard-code development versus production behavior outside the dial —
  rejected as a second policy; or
- let every component choose whether to throw, degrade, or retry — rejected as
  per-site judgment.

Integrant build exceptions remain an internal construction signal. The root
boundary extracts and halts the partial system before applying P9.

### P10 — Reset orchestration

**PROVISIONAL CHOICE:** reset marks one root registry slot unavailable, halts
only that nested system, deletes that cluster database and generated package
skeleton, installs the current release through stages 1–4, initializes a fresh
nested system through stages 5–7, then atomically replaces the slot. Shared
executors, root development endpoint, other clusters, and the HTTP process
component remain live.

Alternatives:

- restart the whole JVM — simpler first implementation but discards the
  settled nested-system isolation benefit; or
- migrate the existing database — rejected by L18.

### P11 — Hot reload and restart boundary

**PROVISIONAL CHOICE:** hot reload may rebind late-bound trusted Vars and
re-run the one program admission/publication path. It may not alter store
identity, writer connection settings, root/nested component topology,
initialization pages, the release base `ctx`, or an applied release identity.
Those changes require nested halt/init or reset. Integrant
`suspend!`/`resume` remains unused.

Alternatives:

- rebuild the entire Integrant system on every source edit — correct but too
  broad and loses the existing late-bound development loop; or
- hot-swap store and boot-critical config in place — rejected because
  Datahike consumes writer settings during connection construction and the
  applied identity would lie.

### P12 — Write-back-to-disk hook

**PROVISIONAL CHOICE:** no boot stage writes agent-authored namespaces to
disk. A future explicit projection operation may read committed
`:seon.ns`/`:seon.fn`/`:seon.schema` facts, emit ordinary source files
atomically, and make those files inputs to the next build's one compile-time
indexer. The next release would then install the indexer's result through the
same page path.

Alternatives:

- write files in the terminal eval transaction path — couples durable facts
  to a non-transactional side effect and creates a crash gap;
- write files during boot — makes start mutate source and violates R45; or
- never project to disk — leaves agent-authored code outside the next
  first-party build.

This draft deliberately does not choose namespace eligibility, path mapping,
conflict policy, overwrite semantics, formatting, test gate, Git operation, or
base-versus-divergence reconciliation. Those are the open design.

## Canonical stage contracts

The stages below are the one written sequence. A mode marked **attach** runs on
every ordinary start. A mode marked **install** runs only under explicit apply
or reset.

### Stage 0 — Process admission and root construction

**Mode:** attach and install.

**Reads**

- the operator-published launch descriptor;
- selected cluster IDs, canonical database paths, backend/branch data, process
  generation, resolved-manifest path and digest, artifact and sidecar digests;
- stage-1 boot-critical values, including writer queue settings and HTTP bind
  data; and
- code-owned component schemas.

**Commits**

- No cluster database facts. The current operator durably publishes its
  process record outside the database before the workload is treated as owned.

**Failure**

- Invalid descriptors, missing artifacts, digest mismatches, and component
  input failures refuse before any component starts. P9 applies, with no
  database fault available yet.
- `ig/assert-key` validates resolved inputs immediately before each init.

**Ready**

- Stage 0 is ready when the root configuration is expanded, every selected
  nested configuration is closed data, and no live handle has been created.
  The Integrant builder consumes it.

**Mid-stage kill**

- Leaves no cluster facts and no live Datahike connection. The supervisor
  consumes or replaces the process generation using `(pid, start-instant)`
  identity.

### Stage 1 — Resolve the manifest to desired facts

**Mode:** install derives the desired set; attach reads only the manifest
identity carried by the launch.

**Reads**

- the already selected and digest-verified manifest;
- hardware observations and environment data explicitly captured by the
  operator;
- the mandatory page-plan and base-projection sidecars; and
- the release identity.

**Commits**

- None. `seon.config.resolve/resolve-config-singleton`, routes, skills, and
  optional AI row become a closed desired-fact value. This is the correction
  to the phrase “reconcile into facts before store open”: desired facts can be
  derived here, but not transacted.

**Failure**

- A malformed manifest, changed manifest digest, invalid liveness relation, or
  page/manifest identity mismatch refuses before store open under P9.

**Ready**

- Install mode is ready when one validated desired population and one
  digest-verified mandatory page vector exist.
- Attach mode is ready when the launch carries the exact config-manifest
  digest expected in the database's applied identity.
- Stage 2 consumes these values.

**Mid-stage kill**

- Leaves no cluster facts. The deterministic desired value and immutable
  sidecars are recomputed/reloaded by the survivor.

### Stage 2 — Enforce L6 and open or create the store

**Mode:** attach and install.

**Reads**

- canonical database path, database name, backend, store/branch configuration,
  writer settings, and the process identity;
- install mode additionally reads the first mandatory initialization page.

**Commits**

- Attach mode commits no domain facts.
- Install mode may commit the first schema page through the existing
  create/ensure path because a file database cannot be created by a bare open.
  The initialization receipt becomes `in-progress`.

**Failure**

- P2 refuses a second write connection before `create-writer :self`.
- An existing store with conflicting immutable configuration refuses.
- Attach mode refuses an absent database and points to explicit apply.
- Any post-create failure records a core fault if the schema/fault path is
  available; otherwise P9's pre-open diagnostic applies.

**Ready**

- The writer component is ready only when it holds exactly one connection and
  its one `LocalWriter`, the L6 ownership primitive is retained, and the
  database value is readable.
- Stage 3 consumes the connection and database value.

**Mid-stage kill**

- The proposed kernel ownership releases with the process.
- Attach mode leaves the existing database unchanged.
- Install mode leaves either no database or an `in-progress`
  initialization receipt. The survivor resumes explicit apply; it never
  treats that database as attachable.

### Stage 3 — Install pages and reconcile facts, or prove installed identity

**Mode:** install performs the writes; attach performs proof only.

**Reads**

- the database value from stage 2;
- mandatory initialization pages, release digest, config-manifest digest, and
  initialization fingerprint;
- install mode's desired config/routes/skills/AI population; and
- the launch envelope for boot-critical parity.

**Commits**

- Install mode commits every remaining initialization page, including the
  first-party program graph.
- It runs the one provenance-scoped `seon.runtime.state/reconcile!`, creates
  the initial agent through the existing owner, proves installed versus launch
  configuration, installs the empty/current divergence projection, and
  completes P4's applied identity.
- Attach mode commits nothing; it reads the complete receipt and compares all
  identity fields with the launch.

**Failure**

- Missing pages never fall back to runtime derivation.
- A page receipt mismatch, incomplete status, launch/config divergence, or
  program fingerprint mismatch records one core fault and refuses readiness
  under P9.
- Attach refusal names the applied and launch digests and the exact
  `bin/seon cluster apply <name>` remedy.

**Ready**

- One complete applied identity and an immutable database value at or after
  its completion transaction.
- The nested sci-program component and web replica consume that exact
  database value, not an ambient re-read.

**Mid-stage kill**

- Each transaction is atomic. A survivor observes the prior complete desired
  set, one additional complete page, or the final complete applied identity;
  never a partial transaction.
- `in-progress` resumes only through explicit apply. `complete` admits the
  ordinary attach path.

### Stage 4 — Materialize and install the sci program

**Mode:** attach and install converge here.

**Reads**

- the digest-verified release program rows and precomputed load order;
- the stage-3 database value's program graph and divergence facts;
- process-wide schema registry, executor, semaphore, and time-limit
  configuration; and
- the preproved projection pure data.

**Commits**

- No database facts during ordinary materialization.
- A detected source/projection disagreement is a core fault, not a cache
  rewrite.

**Failure**

- Missing program facts, incomplete Malli contracts, base fingerprint
  mismatch, divergence mismatch, or a failed `ctx` load records one core fault
  and refuses the cluster under P9.
- There is no native-code fallback for agent-authored functions and no
  alternate effect/request path.

**Ready**

- The root release base `ctx` exists once.
- The nested cluster program is installed against the stage-3 database value,
  instrumentation is reconciled from the program graph, and a fresh `fork`
  can acquire the cluster's current namespace program at a named basis.
- The run-loop component consumes this installed program.

**Mid-stage kill**

- Loses only process-local Vars, compiled validators, wrappers, and `ctx`
  objects. Program facts and applied identity remain committed. The survivor
  rematerializes them; it does not replay historical evals.

### Stage 5 — Arm the run loop

**Mode:** attach and install.

**Reads**

- the stage-3 database value;
- installed sci program, one effect/request path, LLM transport, writer
  connection, allocator, and process identity;
- pending message and recoverable-run queries; and
- committed lease instants.

**Commits**

- Arming itself commits no ready flag.
- Work found by the initial scan commits through existing run, turn, receipt,
  and process/epoch CAS transactions.

**Failure**

- Listener-registration or initial-scan failure records one core fault and
  refuses readiness under P9.
- Agent/model failures remain flat values and terminal facts; they do not fail
  boot.
- Lease clocks are exact declared-transition arms. A protective backstop
  firing records a core bug; it is never the primary wake mechanism.

**Ready**

- Per P6, the listener is registered, one initial recovery scan has completed,
  and halt owns listener removal plus lease-wake cancellation.
- The cluster registry and web-render attach stage consume the live component.

**Mid-stage kill**

- Any completed run/receipt transaction survives. In-memory scans,
  in-flight sets, listener handles, and lease arms disappear.
- The survivor re-registers, scans committed pending work, and resumes from
  process/epoch CAS plus terminal receipts. No side channel needs repair.

### Stage 6 — Attach web-render replica and HTTP routing

**Mode:** attach and install.

**Reads**

- complete applied identity and stage-3 database value;
- the live cluster registry slot and database-interest source;
- trusted render functions and web-render config facts; and
- current committed render snapshots where the ruled materialization design
  requires them.

**Commits**

- Replica attachment and HTTP routing commit no boot facts.
- Later authored-render materialization may commit its separately ruled
  no-history result fact; that is runtime work, not readiness bookkeeping.

**Failure**

- Replica identity mismatch, interest-attach failure, or HTTP component
  failure records one core fault when the writer is available and follows P9.
- A production-degraded cluster remains unavailable through the registry; no
  request silently falls back to executing authored code in web-render.

**Ready**

- The replica has published an immutable database value at or after stage 3,
  subscriptions can observe committed changes, trusted rendering succeeds for
  a bounded probe value, and the HTTP route resolves the current cluster slot.
- The final root readiness component consumes the replica database value and
  endpoint.

**Mid-stage kill**

- Loses only replica/session, subscriptions, request executors, and sockets.
  The cluster database, applied identity, run facts, and rendered snapshots
  survive.
- The survivor attaches from the latest complete database value and repaints.

### Stage 7 — Publish readiness

**Mode:** attach and install.

**Reads**

- every preceding component's returned ready value;
- process generation and `(pid, start-instant)` identity;
- each selected cluster's applied identity, database value, run-loop
  listener, replica database value, and HTTP endpoint.

**Commits**

- No new cluster “ready” datom. Currency is the live process generation plus
  the component-returned evidence, not a stale boolean.
- P8's carrier atomically publishes the closed readiness value to the
  operator.

**Failure**

- Publication failure follows P9. A backstop may bound an otherwise
  unobservable external publication failure, but firing records a core bug
  with the exact unsettled component evidence.

**Ready**

- Development: all selected clusters and required process-wide components are
  ready.
- Production under P9 `:log`: the root may publish ready-with-unavailable
  cluster slots only if the closed value names each refusal; it may not claim
  those clusters ready.
- The operator, development endpoints, and HTTP readiness handler consume the
  same value rather than independently guessing.

**Mid-stage kill**

- The readiness publication is absent or belongs to the dead generation.
  The supervisor never adopts it for the survivor.
- All durable recovery state remains the database facts established before
  publication.

## Kill consistency across the sequence

After stage 2, every recoverable transition is represented by committed facts:
initialization receipt, applied identity, program graph, config singleton,
runs, process/epoch ownership, leases, and receipts. Process-local live
handles are always disposable. The only non-database lifetime facts are the
operator's process record, readiness carrier, and P2's proposed pre-open
exclusive lock; all three are generation-scoped or kernel-released and never
substitute for application coordination.

The survivor's rule is therefore:

- absent or `in-progress` initialization → explicit apply;
- complete identity mismatch → loud refusal with exact apply remedy;
- complete matching identity → rematerialize and attach;
- live work → query runs/receipts and resume; and
- dead readiness generation → ignore and republish after complete attach.

## Cluster reset variant

Reset is not a separate boot algorithm. Under P10 it runs the same stages with
an explicit destructive prelude:

- make the selected cluster slot unavailable;
- reverse-halt its nested Integrant system;
- release its writer connection and L6 ownership;
- delete only that explicit cluster database and generated package skeleton;
- select the current release, manifest, and mandatory pages; then
- run stages 1–7 and replace the registry slot only after readiness.

If killed after deletion but before applied completion, the next invocation
finds an absent or `in-progress` database and continues explicit apply. It
does not restore old code, migrate data, or expose the slot.

The flat-edge acceptance test from the Integrant decision remains mandatory:
reset cluster A while cluster B, root executors, development endpoint, and HTTP
component remain live; no shared root resource may be in A's nested halt set.

## Hot reload versus restart

Under P11:

| Change | Hot reload | Nested halt/init | Reset/current pages |
|---|---:|---:|---:|
| Trusted function body behind a late-bound Var | yes, after owning admission/proof | no | no |
| Agent-authored program fact | acquired by later fresh `fork`; no retained ctx | no | no |
| First-party program graph or schema/index output | no | no | yes |
| Release base `ctx` load plan or capability bindings | no | yes for process/root, subject to topology | possibly, when program pages changed |
| Runtime config fact whose consumer reads database state | explicit reconcile, then consumer refresh | only if its component owns a live handle affected by it | no |
| Writer connection setting, backend, store path, or branch | no | yes | reset when the selected cluster database is replaced |
| HTTP handler Var | yes | no | no |
| HTTP bind, executor ownership, or replica construction | no | yes | no |
| Integrant component graph or lifecycle method | no | yes | no |

No source edit may silently change the applied release identity. If the
compile-time indexer emits different pages, L18 requires reset to current code
and pages for the clusters in this program.

## Write-back-to-disk projection hook

P12 places the future hook after an agent-authored namespace is fully committed
to the program graph and before the next release's compile-time indexer reads
source. It is neither part of terminal transaction success nor part of boot.

The open flow is:

`committed agent-authored program facts → explicit disk projection (policy
unsettled) → next build's one indexer → mandatory release pages → explicit
cluster install/reset`.

The unanswered part is intentionally large: which namespaces qualify, how a
database identity maps to a file, how concurrent edits and a changed
first-party base reconcile, what validates/tests the projection, and what
operator or agent act authorizes it. Until ruled, the hook is a named boundary,
not a feature contract.

## Source disagreements found

### 1. Manifest reconciliation order

The requested spine places “config manifest reconcile into facts” before store
open. Current source and R45 place reconciliation inside explicit apply after
the initialization pages have made the database and its schema available.
P1 preserves a pre-open pure desired-fact derivation but defers the transaction
until stage 3.

### 2. Process topology at web-render attach

The architecture still says cluster JVM plus independent web-render JVM. The
Integrant decision adopts a root HTTP component when writer, run loop, and
web-render scaffolding merge into one JVM. P7 does not conceal that conflict;
the final process placement needs an owner ruling.

### 3. Mandatory pages versus current fallback

Plan step 5 requires missing pages to fail loudly and deletes runtime
derivation. Current `seon.db.protocol/initialization-pages` still calls
`derive-initialization-pages` when the pages key is absent. This draft treats
that branch as target deletion and never invokes it as recovery.

### 4. Readiness ownership

The owner ruling requires interfaces to express dependencies and publish
readiness. Integrant only treats `init-key` return as ready, while the current
operator separately probes log text, sockets, and HTTP. P6–P8 make returned
component values the source and leave only the publication carrier
provisional.

## Owner questions

1. Does the owner accept P1's correction: pre-open manifest work derives a
   closed desired-fact value, while the actual provenance-scoped reconcile
   transaction occurs only after the writer opens and mandatory schema pages
   exist?
2. Which L6 refusal primitive is ruled: P2's store-path kernel lock, a
   maintained Datahike/Konserve exclusive-open change, or another mechanism
   that can refuse a directly launched second JVM before `create-writer
   :self`?
3. If P2 remains external to the database, is that narrow pre-open lifetime
   fence an accepted exception to “all coordination is committed facts,” or
   must the dependency supply the fence?
4. Should the applied initialization status become complete only after pages,
   config reconcile, launch proof, and initial-agent birth as in P4, or should
   page completion and applied-release completion remain separately named
   statuses on the same entity?
5. Is P5's process-wide release base `ctx` plus per-cluster program
   installation the intended N-cluster sharing boundary?
6. Must run-loop init wait for both listener registration and one synchronous
   recovery scan as in P6, or is listener registration alone sufficient for
   readiness?
7. Is State B one JVM containing the root HTTP/web-render component, or does
   the independent web-render JVM remain authoritative? If independent, does
   each JVM own its own root Integrant system, and which interface carries
   cluster attachment data between them?
8. Does “web-render replica attach” require a real local replica in the first
   merged slice, or may the current remote immutable-session path survive
   temporarily under an explicitly non-replica name?
9. Which P8 readiness carrier should the interface publish: an atomic
   generation-scoped EDN record, a local control-socket response, or another
   already-owned operator surface?
10. Under `:seon.config/on-core-error :log`, may the root process publish
    ready-with-unavailable cluster slots, or must any selected-cluster failure
    withhold whole-process readiness?
11. Should reset keep the root JVM and other nested clusters live as in P10
    from the first implementation, or may the first State B reset restart the
    whole JVM while preserving the nested-system acceptance design?
12. For first-party program/schema changes, does L18 require reset of every
    selected cluster immediately, or may a cluster remain attached to its
    previous applied release until an explicit later reset?
13. What explicit operation owns the future write-back-to-disk projection,
    and which separate design should settle namespace eligibility, path,
    conflict, validation/test, and authorization policy before the next
    build's indexer can re-ingest it?
