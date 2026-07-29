---
type: research
status: active
tags: [research, config]
---

# Config and Aero quarry (2026-07-29)

## Verdict

The fresh config design has preserved the right storage model and lost the
operator workflow that made it usable.

The nightmare is several problems at once, but they are not equally important:

1. **The primary regression is the missing selection/apply/repair workflow.**
   Fresh boot always applies `config/default.edn`; it cannot select the already
   implemented override reader, and there is no public operation that lets an
   owner deliberately re-apply desired state to repair a cluster.
2. **The primary maintenance defect is repeated dial registration.** One dial
   is independently listed for leaf validation, manifest admission, effective
   projection, database installation, defaults, and tests. The held
   dial-authority chunk-1 analysis already owns this diagnosis and its
   schema-loader derivation; this report does not redesign it.
3. **Putting schema resource EDN under `src/` makes the repository look like it
   has dozens of config files.** That is a real organization defect, but those
   files are schema declarations, not cluster configuration. Moving them to
   `resources/seon/schema/` fixes the category error without changing the
   sealed classpath-resource contract.
4. **There are visibly two config generations in one `config/` directory.**
   Fresh owns `config/default.edn`; the quarry operator still owns
   `config/system.edn` and its Aero variants. Until the quarry dies, a reader
   cannot tell which files configure the current runtime by looking at the
   directory.

The clean result is not a restoration of the old system. It is the fresh
database-owned model with the old workflow put back in front of it:

- one selected manifest input;
- one compile/validate boundary;
- one exact, provenance-scoped `apply!`;
- selected startup applies before runtime consumers arm;
- config-free reopen preserves the database;
- explicit apply repairs drift and overrides stuck config-owned facts; and
- schema declarations remain classpath EDN, but live under `resources/`, not
  beside source code and not in the config directory.

## Dependency ledger and scope

| Dependency or mechanism | Revision/source | What this report uses |
|---|---|---|
| Aero | `1.1.6`, vendored at `reference-code/aero` commit `c47a10fa5f6a52084d04769af06d5e04d6603e13` | `reader` multimethod, `#env`, `#envf`, `#long`, `#boolean`, `#include`, shallow `#merge`, `#profile`, and `read-config` |
| Old config compiler | final quarry sources in `src-old/seon/config.cljs` and `src-old/seon/config/resolve.cljc` | manifest reading, resolution, typed readers, context composition, flat singleton construction |
| Old exact reconciler | `src-old/seon/runtime/state.cljs` and `src-old/seon/client.cljs:1590-1644` | provenance-scoped desired-state diff, drift repair, zero-write convergence |
| Old operator | `script/seon/dev/config.clj:180-284`, `script/seon/dev/cli.clj:363-503` | manifest selection, frozen payload, explicit live apply, writer replacement |
| Fresh config | `src/seon/config.cljc`, `src/seon/schema/config.edn`, `config/default.edn` | closed gate, defaults, singleton desired row, database reconcile, effective reads |
| Fresh schema EDN | `src/seon/schema/edn.clj`, sealed by `8c7691810` and implemented by `b432bd07f` | one global classpath population, duplicate refusal, one admission gate |
| Current gaps | `docs/seon/issues/a-dial-exists-has-no-single-authority.md` and `docs/seon/issues/boot-cannot-select-a-config-manifest.md` | chunk-1 dial sync inventory and the boot-selection falsifier |

The earlier Integrant/Aero system at the parent of `6c1079c8d` is a separate
generation. Its `resources/system.edn` built live components through Integrant
and added Aero readers for `#ig/ref` and `#ig/refset`. It did not establish the
later config-as-database-facts contract. Its useful lesson is one validated
construction input; its post-database configuration authority and central
component-schema map should not return. The relevant quarry for this task is
the later June/July Aero manifest plus database reconcile workflow.

## The old way

### One selected manifest family

The final quarry contract was one manifest selected for one operation:

- `config/system.edn` was the shipped complete development manifest.
- `config/acme.edn`, `config/test.edn`, and the `config/minimal*.edn` family
  were separate per-cluster/test files.
- `SEON_CONFIG` selected the file. An explicit operator path outranked the
  inherited environment value.
- A fresh database selected `config/system.edn`; a born database with no
  explicit selection did not reconcile ambient source configuration.
- A selected file was resolved once into ordinary data, validated, hashed, and
  frozen into a launch descriptor. The pod received the resolved value and did
  not reread Aero during apply.

The final selection implementation is
`script/seon/dev/config.clj:180-266`. It orders explicit path, inherited
`SEON_CONFIG`, a retained resolved value for launch reconstruction, and the
shipped file; it separately computes whether the database should actually be
reconciled. Tests at `test-old/seon/dev/cli_test.clj:890-984` pin the essential
semantics: first boot selects the shipped manifest, an existing database gets
no ambient apply, and an explicit relative path is rooted at the checkout.

The retained `data/clusters/<name>/config/applied.edn` later became a second
authority and was a defect, discussed below. The behavior worth preserving is
the distinction between **selection** and **config-free reopen**, not that
file.

### How Aero readers and profiles actually evolved

Aero `1.1.6` supplies the mechanics directly:

- `#env` and `#envf` read environment values;
- `#long`, `#double`, `#keyword`, and `#boolean` coerce values;
- `#include` resolves a file relative to its source;
- `#merge` is `(apply merge values)`, therefore shallow;
- `#or` selects the first truthy resolved value; and
- `#profile` selects a branch from `:profile`, whose default is `:default`.

The dependency source is
`reference-code/aero/src/aero/core.cljc:30-102,180-280,414-433`.

The Seon workflow had two distinct profile periods:

1. `525bd0f0e` introduced `SEON_CONFIG` plus Aero. `89ca7b69f` made
   `SEON_PROFILE`/`#profile` a per-cluster/test seam. The early
   `config/system.edn` held a map such as
   `#profile {:default [...] :minimal []}`.
2. `87274cecb` deliberately deleted profile selection. A profile name hid the
   actual loadout, so each variant became a separate manifest file selected by
   `SEON_CONFIG`. Final quarry comments say “no `#profile` — one file, one
   shape.”

The final reader kept the useful parts:

- `#include` plus `#merge` composed variant files over `system.edn`;
- `#long #or [#env NAME default]` captured typed environment overrides;
- Seon overrode Aero's `env`/`envf` readers so compilation used an explicitly
  supplied environment map, not a later ambient read; and
- the resolved ordinary value crossed the runtime boundary.

That explicit environment capture is important. Environment values were
inputs to compilation, not runtime configuration.

### The custom merge repair

Aero's shallow merge was correct for a flat scalar override and wrong for one
nested old manifest section. `config/acme.edn` supplied only
`:seon.eval/home-requires` inside `:seon.config/agent-context`; shallow merge
replaced the entire base context map, dropping `:seon.agent/ctx`. A schema
default then silently restored the legacy sixteen-block tree, so ACME ran the
wrong context for a day.

Commit `fac50bef` repaired the one reader seam.
`src-old/seon/config/resolve.cljc:1382-1478` made a sparse agent-context value
patch the base, while a value explicitly carrying `:seon.agent/ctx` remained a
complete replacement. Other top-level keys retained Aero's shallow semantics.

This was a real defect and a warning: do not restore nested magical merge
semantics. Fresh dials are flat, so stock shallow overlay is sufficient.

### Compile, then reconcile facts

The old operation was not “read config everywhere.” Its good path was:

```text
selected path + explicit environment + hardware observations
  -> Aero resolves tags/includes
  -> closed Malli manifest validation
  -> resolve-config-singleton
  -> canonical ordinary desired population
  -> provenance-scoped exact reconcile
  -> runtime acquires database facts
```

`resolve-config-singleton` flattened sections into the identified
configuration singleton. `src-old/seon/client.cljs:1590-1627` then built one
desired population from routes, the singleton, and the optional cluster AI
row. It called the existing exact reconciler with:

- managed process provenance `boot` and `config`;
- managed identity attributes derived from desired rows; and
- transaction provenance `{user root, process config}`.

The exact reconciler:

- added missing desired entities;
- replaced changed values;
- retracted omitted managed attributes and stale managed entities;
- left facts from other provenance outside its scope untouched; and
- emitted no transaction once current state equaled desired state.

`test-old/seon/runtime/state_test.cljs:478-585` pins these semantics, including
preservation of an entity born outside managed provenance. The fresh
`seon.reconcile` has retained this good core:
`test/seon/reconcile_test.clj:83-139` proves zero-write reapply, hand-edit
repair, and stale managed identity retraction.

### Explicit apply was the repair and override operation

`bin/seon config apply <path>` deliberately selected a manifest and invoked
the same database reconciliation used by cold boot.

The important behaviors were:

- a manifest value overrode prior config-owned database state;
- reapplying repaired hand drift;
- omitted values in the managed desired population retracted stale state;
- a converged second apply produced zero operations and did not advance the
  basis;
- unrelated agents, messages, plans, and other provenance survived; and
- the resolved payload, rather than a path promise, crossed into the pod.

That is what made config an unlock mechanism. If committed config facts put
the runtime in a bad state, selecting a corrected manifest restored the
declared config population. The operation did not require editing source or
resetting the whole database.

The workflow matured in three steps:

- `d3e20f87d` added explicit `--config` selection and
  `config apply <path>`.
- `2f3488067`/`b1337b41f` stopped widening a config apply into full artifact
  rebuild and process reconciliation. The live boundary applied desired state
  directly.
- `174145414` let an explicit apply change boot-critical writer settings. The
  operator froze one candidate, compared it with the loaded generation, asked
  the pod to drain admission, replaced only the writer, reattached, proved
  equality, and resumed. The commit's live proof changed heap
  `4096 -> 3072 -> 4096` MiB while the pod PID stayed stable.

The third mechanism belongs to the old multi-process topology and should not
be ported. Its surviving requirement is simpler: in the one-JVM fresh system,
a selected startup must apply database configuration **after store open and
before flows/web/model consumers arm**. That gives a broken cluster a repair
path without reconstructing a remote writer protocol.

### What the old workflow got right

- One obvious selected manifest family, not a per-feature file forest.
- A manifest was desired-state input, never the runtime authority.
- Environment and hardware observations were captured once into resolved data.
- Unknown manifest keys failed loudly.
- Runtime code read database facts after acquisition.
- Exact, provenance-scoped apply repaired drift and was basis-stable when
  converged.
- Explicit apply was an administrative recovery operation.
- Fresh boot and config-free reopen were different operations.
- Per-cluster/test variants required no source edits.

## The old workflow's real defects

The defects below are the ones history and the issue archive actually found.
They are reasons to simplify the workflow, not reasons to discard it.

| Defect | Evidence and fix | Lesson |
|---|---|---|
| Aero profile names hid effective loadout | `87274cecb` deleted `SEON_PROFILE`/`#profile` and moved variants to separate selected files | Do not restore profiles; select one inspectable file |
| Shallow nested merge silently dropped context | `fac50bef`; ACME ran the legacy tree for a day | Keep fresh manifest values flat; no custom deep/special merge |
| Defaults still lived in code beside the manifest | `src-old/seon/config/resolve.cljc:1968-2135` contains many `(get section key literal)` fallbacks; `protective-runtime-literals-bypassed-config.md` was fixed piecemeal by `34f0373e8` | One complete, provenanced defaults document; no numeric fallbacks in consumers |
| Adding a config concern was multi-site ceremony | The old namespace docstring admitted four mechanical steps; later config growth also spread registrations and consumers | Derive manifest/effective/entity shapes from one dial declaration |
| Explicit apply initially widened into a full rebuild/restart | `config-apply-rebuilds-unchanged-runtime.md`; fixed by `2f3488067`, live proof kept watcher/writer/pod PIDs unchanged for non-operational config | `apply` must remain one database transition |
| Empty cardinality-many values caused metadata-only transactions forever | `reconcile-empty-many-metadata-only-transaction.md`; fixed by `2f3488067` | Canonicalize desired values before exact comparison |
| Config and schema population shared one provenance process | `config-reconcile-shared-the-schema-population-process.md`; one config row planned 465 operations | Boot/schema/config need distinct provenance scopes |
| Generic lookup-ref recognition treated every two-vector as a lookup ref | `config-apply-instrumentation-rejected-two-member-vectors.md`; real apply returned HTTP 422 | Keep strict validation, fix the generic boundary rather than weaken config |
| Logical EDN-slot values were validated against storage strings before encoding | `transaction-validation-precedes-edn-slot-encoding-blocks-fresh-config-reconcile.md`; fixed by `a4b8b9d48` and `e35e2344e` | One logical-to-storage normalization before validation and transact |
| A retained resolved manifest became a second state authority | `page-plan-config-digest-drift-blocks-fresh-default-apply.md`; reset retained `applied.edn`, producing two manifest digests; fixed by `07af12c73` | The database is the only applied-state authority; retain an immutable operation payload only while an operation is in flight |
| Build hooks re-resolved or consumed config at the wrong runtime boundary | `page-plan-hook-used-writer-only-manifest-reader.md`; repaired through `407533985` | Resolve once in the operator/compiler boundary; consumers receive ordinary data |
| Fresh autonomous clusters failed to select the shipped manifest | `shared-writer-cluster-did-not-select-fresh-config.md`; selection was centralized | Every cluster start uses one selection owner |
| Exact configuration referenced schemas missing from the published population | `fresh-page-apply-omits-web-config-schemas.md`; the fix derived owner closure instead of adding a hand list | The complete schema population must be admitted independently of which values a manifest supplies |
| Config apply accumulated a one-off stored-state migration | `configured-plan-surface-absent-from-live-program.md` used explicit apply to CAS-repair obsolete copied plan render symbols | Apply is a useful recovery choke point, but fresh clusters reset to current code; do not turn config into a migration registry |

The worst old complexity came late, when the manifest was tied to launch
envelopes, page-plan digests, a retained `applied.edn`, writer replacement, and
two runtimes. The one-JVM fresh topology deletes those causes. It should keep
the compiler and exact reconcile, not port the envelope machinery.

## The current fresh way

### Honest inventory

| Surface | Current fact |
|---|---|
| `config/default.edn` | The only fresh defaults document: 192 lines, 23 static keys plus computed concurrency in code. It carries units and calibration provenance. |
| Other `config/*.edn` files | Quarry Aero manifests still used by old `bin/seon` paths. They are not read by fresh `seon.cluster`. Their proximity to `default.edn` obscures ownership. |
| `src/seon/config.cljc` | 230 lines. Plain EDN reader, closed per-key validation, default overlay, desired singleton/digest construction, `apply!`, and database `effective` read. |
| `src/seon/schema/config.edn` | 349 lines. Thirty dial entries are separately repeated in `:seon.config/manifest` and `:seon.config/effective`; the entity map adds identity and digest. |
| Other dial leaf files | `schema/config.edn`, `schema/flow.edn`, `schema/admit.edn`, and `schema/ai.edn` own current dial leaf declarations, as enumerated by the held chunk-1 analysis. |
| Whole schema resource population | 31 files, 2,421 lines, 144,837 bytes, 457 unique schema keys, zero duplicate keys at this checkout. |
| `src/seon/schema/edn.clj` | 312-line classpath resource loader and admission gate. It merges the global population and rejects duplicates, unresolved references, unregistered predicates, and dishonest generators. |
| Boot selection | `src/seon/cluster.clj:933-939` always calls `config/apply!` with `(config/defaults)`. |
| Override reader | `config/read-manifest` exists at `src/seon/config.cljc:165-174`, overlays a plain EDN map on defaults, and has no caller in fresh `src/`. |
| Bootstrap request | `src/seon/schema/boot.edn` has no manifest selection field. |
| Reconcile behavior | `seon.config/apply!` already calls the fresh exact reconciler; `test/seon/reconcile_test.clj` proves convergence and drift repair. The missing part is access to that operation, not its core. |

### What fresh got right and must stay

- `config/default.edn` is the defaults document and records units/provenance.
- Configuration is committed as database facts.
- Runtime consumers read database values, never files.
- The singleton has a real cluster identity and applied manifest digest.
- `seon.reconcile` is exact, provenance-scoped, idempotent, and preserves
  outside facts.
- Unknown keys and invalid values fail at a closed validating gate.
- Schema EDN is one global population with one admission gate for classpath and
  runtime producers.
- Schema file boundaries have no runtime semantics and duplicate declarations
  fail loudly.
- Dials are honest named facts; required values do not silently fall back in
  leaf consumers.

### The held dial-authority work

`docs/seon/issues/a-dial-exists-has-no-single-authority.md` chunk 1 identifies
the independent sync sites and rules that the schema loader derives the closed
manifest, effective, and config entity maps from registered dial leaves.

That work is held pending this report and should resume unchanged in purpose.
This report adds only one boundary condition: its derived dial population is
the schema/admission half of config. It must not become a second manifest
selector, defaults reader, or apply mechanism.

### One fresh semantic contradiction to resolve

**Resolved 2026-07-29.** Omission from a sparse overlay inherits the shipped
decision. `:seon.config/absent` is the one explicit retraction form for an
optional config attribute; the compiler refuses it for required attributes
and removes it before deriving the effective map and desired database row.
`test/seon/config_test.clj` pins the report's concrete
`:seon.config.error/escalate-to` case: omission retains `"root"`, while the
marker retracts it without storing nil or the marker.

Fresh comments say an optional dial with a shipped default can be explicitly
absent, but the current reader cannot express that:

- `read-manifest` does `(merge (defaults) override)`;
- `desired-rows` merges defaults again; and
- EDN has no deletion marker.

For example, `:seon.config.error/escalate-to` has shipped default `"root"`.
Omitting it from an override restores `"root"` rather than representing
absence. This is not a reason to add nil. The manifest compiler needs one
explicit retraction form if defaulted optional dials must be removable, or the
documentation/schema must stop claiming that state. Decide this at the
manifest compiler, once.

## Precise diagnosis

### Is EDN under `src/` the nightmare?

**Partly, as organization and mental model; not as runtime design.**

The sealed schema contract explicitly says these are classpath resources and
file boundaries are editorial only
(`src/seon/schema/edn.clj:11-40`). `src` happens to be a classpath root, so the
loader works, but “resource data” under `src/seon/schema/` looks like
configuration interspersed with code. The checkout already has
`resources/` on the default classpath and the build copies both roots
(`deps.edn:8`, `build.clj:148`).

Move the files. Do not collapse them into `config/default.edn` and do not turn
them back into load-time `register!` calls.

### Is the number of registration sites the nightmare?

**Yes. This is the largest code-maintenance defect.**

One dial is declared and repeated across leaf schema, three composite schema
maps, defaults, population fences, and consumers. The chunk-1 issue shows the
third observed hand sync. `5f875c780` is the concrete failure: the no-auth leaf
existed but the closed manifest omitted it, so a supported local provider was
unconfigurable.

Consumer reads remain semantic and cannot be generated away. The first five
structural copies should collapse to one dial declaration plus one defaults
value/provenance decision.

### Is absence of apply/override the nightmare?

**Yes. This is the largest operator regression.**

Fresh already has `read-manifest`, `desired-rows`, `apply!`, and exact
reconciliation, but no route from `start!` or an operator command to a selected
manifest. The usable operation is stranded behind internal functions. This is
why drives use `with-redefs` or override `ai/targets`.

The system therefore has configuration machinery without a configuration
workflow.

### Is manifest selection the nightmare?

**Yes, and it compounds the missing apply.**

The boot issue is exact:

- `start!` always applies defaults;
- boot overrides are closed and carry no manifest path; and
- `read-manifest` has no caller.

It also violates the intended config-free reopen semantics: every fresh start
reconciles defaults, so a deliberate database edit or previously applied
variant is overwritten on restart.

### Is the file count itself the nightmare?

**No.**

There is one fresh config document, but it shares a directory with eight quarry
variant manifests. Separately, there are 31 schema resource files. Conflating
those two populations makes the repository look far worse than the runtime
contract.

The fix is ownership and placement, not a single 2,421-line schema monolith.

## Recommended reconciled design

### One manifest compiler

Keep `config/default.edn` as the complete shipped defaults document. A selected
user/cluster file is a sparse overlay. One function compiles:

```text
shipped defaults
  + selected sparse overlay
  + explicitly captured environment/hardware inputs
  -> closed validation
  -> canonical effective map
  -> canonical digest
  -> one desired config row
```

There is one selected file per operation. Separate files replace profiles.
No runtime component reads the file, the environment, or the defaults
document after the database opens.

### Bring back only the useful Aero subset

Aero was not the old system's essential property; the compiler/apply boundary
was. Aero is nevertheless a good implementation for the input language if its
scope stays narrow:

- keep `#include`, stock shallow `#merge`, `#or`, and typed scalar readers;
- capture `#env` from the explicit environment map supplied to compilation;
- keep variants as separate selected files;
- do not restore `#profile`;
- do not restore Seon's custom nested agent-context merge;
- do not let Aero exist as a post-database reader; and
- freeze the fully resolved ordinary value and digest before opening/altering
  runtime state.

Because the fresh manifest is a flat dial map, shallow merge is honest. If the
team does not need include/env syntax immediately, plain EDN can remain; that
choice must not delay restoring selection and apply. Aero is a convenient
front end, not a second authority.

### Start semantics

The operator surface should match the already ruled B2 shape:

```text
bin/seon start [cluster] [--config <path>]
bin/seon config apply <cluster> <path>
```

- Bare first start selects the shipped default manifest.
- A named first start uses the same shipped defaults unless `--config` selects
  another file.
- A config-free reopen performs **no config transaction** and uses committed
  facts.
- `start --config` opens store/schema, applies the selected desired state, and
  only then arms flows, model calls, web serving, and other consumers.
- A selected manifest path is a tiny bootstrap operation input, not a
  database-owned dial.

Applying before consumers arm is the clean unlock path: even when the prior
database configuration prevents the full runtime from becoming ready, the
closed bootstrap/store/config tower can replace it first.

### Apply semantics

Both selected startup and explicit operator apply call the same
`seon.config/apply!`.

The operation:

- compiles and validates the selected manifest once;
- opens/acquires the target cluster under its existing single-writer fence;
- exact-reconciles only the config-managed singleton/population;
- overwrites changed config-owned facts;
- retracts explicitly removed managed values;
- repairs hand drift;
- leaves facts outside config provenance untouched;
- returns operations and the resulting database value/commit evidence; and
- writes nothing when converged.

A live cluster may execute the transition in-process. A cluster that cannot
arm should be repairable by a store+facts-only start/apply path using the same
connection and function, not a second offline writer or a source reset.

Bootstrap-only values remain deliberately tiny: store path/backend, prepl
bind, and log directory. Changing those means selecting startup inputs and
restarting the process; they are not smuggled into the database dial surface.

### Schema EDN placement

Move:

```text
src/seon/schema/*.edn
```

to:

```text
resources/seon/schema/*.edn
```

This preserves the exact loader resource name `seon/schema`, file/jar
enumeration, build packaging, sealed admission gate, and source-independent
schema facts. It also makes the repository categories legible:

- `config/` — desired cluster configuration inputs;
- `resources/seon/schema/` — canonical program/schema resource data;
- `src/seon/` — executable implementation.

Do **not** merge all schema EDN into one file. Keep approximately one file per
owning code namespace or cohesive domain so schema changes remain local and
parallel edits do not collide. Merge only tiny files that already have the same
code owner; never merge merely to reduce the count. File boundaries are
editorial, so this decision has no runtime consequence.

### Dial authority

After this report, resume the held chunk-1 implementation:

- one registered dial leaf is sufficient for manifest admission and database
  installation;
- generated manifest entries are optional sparse overrides;
- defaults stay a separate intentional value/provenance document;
- consumers continue to name the semantic values they read; and
- the current population-fence lists delete when derivation makes their
  failure class unrepresentable.

This removes the real registration nightmare without coupling manifest
selection to schema loading.

## Acceptance properties for the replacement

1. A fresh cluster with no explicit path applies `config/default.edn` once.
2. A born cluster with no selection performs zero config writes.
3. `start cluster --config path` applies the selected overlay before any
   runtime consumer arms.
4. `config apply cluster path` repairs a hand-edited dial and a second apply is
   basis-stable.
5. An apply never rebuilds source, resets the database, or replaces unrelated
   processes.
6. Facts outside config-managed provenance survive exact apply.
7. Unknown keys and invalid values refuse before a transaction.
8. One added dial registration is immediately manifest-admissible and
   database-installable, without composite-map or fence edits.
9. The selected path and environment are absent from runtime reads after
   compilation; the database facts reproduce the effective configuration.
10. Schema EDN loads identically from source classpath and packaged jar after
    moving to `resources/seon/schema/`.
11. No `SEON_PROFILE`, custom nested merge, retained `applied.edn`, launch
    envelope, or page-plan digest mechanism returns.
12. A cluster whose old dials prevent normal arming can still be repaired by a
    selected apply through the bootstrap/store/config tower.

## Final recommendation

Do not keep patching `config/default.edn`, composite schema maps, and boot
callers independently.

First restore the one selected-manifest and explicit-apply workflow around the
existing fresh `config/apply!`. Then land the already analyzed schema-loader
dial derivation. Move schema resources out of `src/` as an organization-only
change and keep their admission contract intact.

That yields the useful old experience—one manifest, override, apply, repair—
without restoring its profiles, nested merge trap, code defaults, retained
manifest authority, build coupling, or multi-process writer replacement.
