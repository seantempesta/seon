---
type: research
status: complete
tags: [research, prd, database, flow, agent]
---

# Execution artifact database dependency seam — 2026-07-16

## Result

The execution artifact currently pays for the complete CLJS Datahike stack even
though its database work crosses one native Bun session. The shortest
reachability falsifier is exact:

```text
seon.execution
  -> seon.db
     -> datahike.api, datahike.connector, datahike.db, datahike.index, ...
     -> seon.db.internal -> datahike.api, datahike.db.interface, ...
```

The existing unoptimized `:execution` loader imports 338 namespaces. Eighty-one
are Datahike, Konserve, persistent-sorted-set, superv.async, or partial-cps.
Those 81 live files contain 8,293,078 of 18,810,383 imported JavaScript bytes:
44.1% of the artifact's live unoptimized module bytes. This is a reachability
measurement, not a production RSS claim. A release build and live Bun child
measurement remain required after the source freeze.

The smallest safe transition seam is one build-selected **private
implementation dependency behind the real `seon.db` namespace**:

- `seon.db` remains the only application namespace. It continues to own every
  public function name, docstring, metadata, argument list, and Malli schema.
- `seon.db` requires one private logical namespace, `seon.db.impl`.
- `seon.db.impl.remote` owns the process session, request correlation, remote
  read/write/listen operations, and only ordinary-data transformations.
- `seon.db.impl.datahike` temporarily owns the existing CLJS database-value,
  entity, index, temporal, schema-installation, and local transaction
  operations needed by the legacy pod and replica.
- Shadow's build-scoped `:build-options :ns-aliases` maps the private
  `seon.db.impl` dependency to `seon.db.impl.remote` for `:execution` and
  `:acme-execution`, and to `seon.db.impl.datahike` only for builds that still
  prove a local Datahike requirement before the atomic replica deletion.

This is not a second public API. Callers and authored code still require and
fully qualify `seon.db`; schema keys remain `:seon.db/*`; the program graph
still indexes `seon.db/query`, not an implementation symbol. Once the replica
and its last direct database-value consumers are deleted, delete
`seon.db.impl.datahike`, remove the aliases, rename the remote implementation
to the one canonical private owner, and then collapse the private seam into
`seon.db` if it no longer earns its file boundary. The transition seam has an
explicit deletion gate and must not become permanent compatibility structure.

Do **not** alias `seon.db` itself. Shadow would rewrite callers to a different
namespace, changing analyzer symbols, public Vars, authored full
qualifications, and the bootstrap compiler's namespace identity. Do **not** use
a Closure define or runtime branch: namespace dependencies are resolved before
Closure optimization, so both implementations remain reachable. Do **not** add
modules: Shadow's Node target constructs one module from the main namespace and
resolves its full transitive closure.

The process launch has a second, independent reachability problem. The current
`spawn-child!` supplies `cmd`, IPC, stdout, and stderr but no `cwd` or `env`.
Bun therefore inherits the parent's working directory and the Bun VM's launch
environment—including dotenv-loaded values. The final child launch must pass
an explicit launch-frozen working directory and an allowlisted environment.
This reduces accidental filesystem, configuration, and credential reachability;
it is capability-footprint control, not a security sandbox.

## Dependency ledger

| Owner | Selected source | Grounded fact |
|---|---|---|
| Execution build | `shadow-cljs.edn:125-151` | `:execution` and `:acme-execution` are `:node-script` builds whose entry is `seon.execution/-main`; both currently have one transitive source closure. |
| Execution entry | `src/seon/execution.cljs:1-12` | The child directly requires `seon.db`, and `seon.eval` creates additional paths to the same namespace. |
| Public database namespace | `src/seon/db.cljs:57-77` at the audited checkout | One namespace statically requires eight Datahike namespaces plus `seon.db.internal`; it also owns the new native session client through `seon.db.transport.uds`. |
| Database internals | `src/seon/db/internal.cljs:1-25` | Portable AsyncLocalStorage, validation, and transaction normalization share a namespace with three direct Datahike dependencies. Only 12 direct Datahike call sites keep this otherwise portable owner heavy. |
| Native session | `src/seon/db/transport/uds.cljs` | The selected Bun client needs Transit, `seon.db.protocol`, `seon.platform`, and `seon.schema`; it has no Datahike, Konserve, PSS, superv.async, or partial-cps dependency. |
| Protocol | `src/seon/db/protocol.cljc` | Request constructors, validators, coordinates, bounds, and ordinary response shapes are portable data and remain shared. |
| Child launch | `src/seon/execution/host.cljs:84-89,267-287` at the audited checkout | The `Bun.spawn` wrapper forwards no `cwd` and no `env`; every child therefore inherits both defaults. |
| Bun spawn contract | `reference-code/bun` at `be77b652884b16a103cfaa4af3c1102f72f2dcd3` | Types specify omitted `cwd` as `process.cwd()` and omitted `env` as the environment captured when Bun launched (`packages/bun-types/bun.d.ts:6714-6720,6752-6759`). The implementation leaves cwd empty to inherit and materializes the VM dotenv environment when no override exists (`js_bun_spawn_bindings.rs:368-388,911-934,1114-1122`). An explicit env object replaces rather than merges the environment; Bun appends only its IPC channel variables (`:596-617,966-1024`). |
| Shadow compiler | `reference-code/shadow-cljs` at `4e72595f57618f5c43388ad13d5136cd3bede566` | Build configuration copies `:build-options :ns-aliases` into resolution (`shadow/build.clj:286-305,438-446`); namespace parsing rewrites dependencies, requires, uses, and renamed Vars (`shadow/build/ns_form.clj:722-770`). |
| Shadow reachability | Same source | Each module resolves every dependency of its declared entries (`shadow/build/modules.clj:239-255`); the Node target creates one module from the main namespace (`shadow/build/node.clj:79-94,101-104`). |
| Datahike | `reference-code/datahike` at `670cd1ada40462cb5927f0dc687f6b3a95f9e13f` | Local CLJS APIs bring the owned index, query, entity, writer, Konserve, PSS, supervision, and partial-CPS graph. The authority keeps those dependencies on the JVM; the agent child does not need them. |
| Prior contract | [[remote-seon-db-contract-freeze-2026-07-16]] | Final Bun values are asynchronous ordinary data; database values, entities, Datoms, temporal wrappers, and query execution remain inside the authority. |

The selected build classpath uses ClojureScript `1.12.145`, the maintained
Shadow fork at `4e72595f`, Datahike `670cd1ad`, Konserve `b5c99bc0`,
persistent-sorted-set `e1a17bbe`, superv.async `3e6ed755`, and partial-cps
`1e119b03`. The npm Shadow CLI shim is `3.4.10`, but the JVM compiler source
that implements namespace aliases is the maintained fork named above.

## Exact source refactor

### 1. Freeze the public face

Keep in `src/seon/db.cljs`:

- the namespace docstring and every surviving public Var;
- all `:seon.db/*` Malli registrations;
- public function metadata, schemas, argument lists, and agent-facing names;
- portable request normalization, result unwrapping, error-as-data policy, and
  coordinate handling; and
- thin delegation to the selected private implementation.

Before moving bodies, persist an analyzer-derived parity fixture containing the
public Var symbols, argument lists, `:malli/schema`, async metadata, and
`:seon.fn/agent-facing?` metadata. Also persist the registered schema key/form
set. Source movement is accepted only if both sets are identical except for
the deletions already approved by the remote contract.

The dynamic `seon.db/*conn*` Var must not be copied into either implementation.
It remains at its public symbol until the replica deletion removes it. A legacy
implementation operation that needs it receives the selected value from the
facade. This avoids a second mutable connection owner and preserves existing
bindings during the transition.

### 2. Separate portable internals from Datahike internals

Keep `src/seon/db/internal.cljs` as the Datahike-free internal owner for:

- AsyncLocalStorage transaction, agent, and operation-capture scopes;
- pure invocation normalization and transaction validation;
- ordinary error/result shaping; and
- pure schema-form and transaction-data transformations.

Move its direct `datahike.api`, `datahike.db.interface`, and
`datahike.impl.entity` uses into `seon.db.impl.datahike`. The current direct
Datahike uses are few enough to falsify the split mechanically: the audited
checkout has 12 in `seon.db.internal` and 35 in `seon.db`. After extraction,
neither `seon.db` nor `seon.db.internal` may contain a Datahike require or
compiled dependency.

Do not replace these calls with a generic operation keyword dispatcher. The
existing `seon.db.protocol` already owns remote operation data; inventing a
second in-process command language would add validation, dispatch, and naming
without reducing dependencies. Use ordinary private functions grouped by the
actual capabilities: session lifecycle, reads, transaction submission,
interests, and—temporarily—local database-value operations.

### 3. Select the private implementation at compile time

Use Shadow's existing namespace-alias mechanism on the private dependency only.
Conceptually:

```clojure
;; seon.db remains the real public namespace.
(:require [seon.db.impl :as impl])

;; execution build configuration only
:build-options
{:ns-aliases {seon.db.impl seon.db.impl.remote}}
```

The legacy build mapping names `seon.db.impl.datahike`. Put the mapping in the
shared flavor/build configuration owner so default and ACME artifacts cannot
drift. Both execution flavors must select remote explicitly. A build with no
selection must fail resolution; do not leave a concrete fallback file at
`src/seon/db/impl.cljs`, because an omitted downstream mapping would silently
reintroduce the heavy implementation.

Shadow installs configured aliases before module dependency resolution and
rewrites the private require to the selected source. Therefore the unselected
Datahike implementation is absent from the execution build graph rather than
merely hidden behind a runtime conditional.

### 4. Delete the transition

The local implementation is allowed only while a named legacy consumer still
requires an actual CLJS Datahike value. Its deletion gate is the atomic removal
of:

- `src/seon/db/replica.cljs`;
- local connection and `RemoteWriter` ownership;
- feed/replay/listener synthesis;
- public database-value, entity, index traversal, and temporal-wrapper faces;
  and
- direct local callers proved by the exhaustive consumer inventory.

At that boundary, remove the Datahike implementation and every non-JVM CLJS
dependency on Datahike/Konserve/PSS/superv.async/partial-cps. Do not retain the
alias structure merely because it works.

### 5. Freeze cwd and environment at launch

The artifact manifest already publishes a content-addressed runtime root and
verifies its bootstrap digest. Carry that absolute runtime root into the
validated launch descriptor instead of recovering it from the mutable parent
process. Also carry one absolute working directory for the cluster/workspace.
They are different facts:

- **runtime root** owns immutable `out/bootstrap` and shipped source/assets;
- **working directory** owns intentionally relative workspace/tool behavior.

`native-spawn!` must pass both explicitly:

```clojure
#js {:cmd    #js [absolute-bun executable-artifact encoded-startup]
     :cwd    launch-working-directory
     :env    (clj->js allowlisted-environment)
     :ipc    ipc-handler
     :stdout "pipe"
     :stderr "pipe"}
```

Resolve the Bun executable to an absolute path in the parent—prefer the running
Bun process's executable for same-runtime children. Bun disables executable
path lookup when an explicit environment omits `PATH`; requiring an absolute
runtime avoids granting the child the parent's full command search path merely
to start it.

The current artifact has three environment reads before an authored function
runs:

- `SEON_RUNTIME_ROOT` selects `out/bootstrap` through
  `seon.eval/init-bootstrap!` → `seon.platform/artifact-path`;
- `SEON_EVAL_RESULT_VARS_CAP` initializes a process-local eval retention
  constant; and
- another eval constant calls the config-view fallback before a database value
  exists, which may read `SEON_CONFIG` and every `#env`/legacy environment
  input resolved by that manifest.

Only the first is an artifact-location requirement. The latter two are stale
pod boot semantics and would make the child's behavior depend on unrelated
parent configuration. Move the small eval limits into the closed child startup
or the coordinate-pinned database program/config acquisition before claiming
the allowlist is complete. The database socket, database name, backend,
attachment, build ID, artifact path, artifact digest, agent ID, and invocation
coordinate already arrive as validated launch/startup data. Consequently, the
final baseline is:

- allow `SEON_RUNTIME_ROOT` with the manifest's verified absolute value;
- do not pass `SEON_DB_SOCK`, `SEON_REQ_SOCK`, `SEON_PUB_SOCK`,
  `SEON_LAUNCH_DESCRIPTOR`, `SEON_CONFIG`, port/process/log variables,
  `SEON_CLUSTER_DIR`, render/eval dials, or the parent's `.env` wholesale;
- do not pass `PATH` when the Bun executable is absolute; and
- do not pass `HOME`, `TMPDIR`, proxy, locale, or TLS variables unless one
  measured enabled capability proves it requires them.

An even smaller later cut may put the verified runtime root directly in the
closed execution startup value and let bootstrap initialization accept it,
removing the last baseline `SEON_*` environment read. That is worthwhile only
if it deletes the shared `SEON_RUNTIME_ROOT` convention rather than adding a
second artifact-root mechanism.

Provider credentials are conditional capabilities, not baseline execution
environment. When the agent loop moves into this child, project only the one
secret selected by its configured provider—for example the resolved DeepSeek,
Anthropic, or explicitly named OpenAI-compatible key. Do not forward every
credential in the parent dotenv file. Diffusion and embedding credentials stay
absent unless those separately enabled capabilities actually execute in this
process. Custom proxy/TLS variables follow the same explicit-capability rule.
This does not make agent-authored JavaScript trustworthy; it only ensures a
child cannot accidentally read credentials unrelated to its work.

The allowlist is derived once when the immutable launch is configured, copied
per spawn, and never read lazily from `process.env` after child ownership
begins. Tests must compare the child's exact observed key/value set, allowing
only Bun's automatically appended `NODE_CHANNEL_FD` and
`NODE_CHANNEL_SERIALIZATION_MODE` IPC variables.

## Why the alternatives lose

### Alias the public namespace

Rejected. `:ns-aliases {seon.db seon.db.remote}` would rewrite every require and
renamed Var to the implementation namespace. Authored code and persisted
program-graph facts use the stable `seon.db/*` symbol family. Runtime self-host
compilation also resolves actual namespace objects, not the intent behind a
Shadow-only alias. This changes the interface to save implementation work.

### Closure define or runtime feature test

Rejected. A `goog-define`, `exists? js/Bun`, or `if` chooses code after the
namespace form has already made both dependency graphs reachable. It may allow
Closure to remove some statements in a particular optimization mode, but it
does not make source reachability, analysis cache, development output, or
bootstrap behavior deterministic. `:simple` must not be treated as a package
boundary.

### Another Shadow module

Rejected. Shadow resolves each module from its entries and their dependencies;
the Node target requires exactly one module. Even in a multi-module target, a
module partitions a resolved graph—it does not choose between two
implementations of one required namespace.

### Stub Datahike namespaces

Rejected. Stubbing `datahike.api` and its transitive namespaces duplicates a
third-party API, retains dead local branches, makes accidental invocation fail
late, and creates a compatibility surface larger than the private selection it
tries to avoid.

### Duplicate `seon.db.remote` public API

Rejected. It would split docstrings, schemas, metadata, instrumentation,
authored requires, tests, and program-graph identities. The private
implementation boundary exists specifically to prevent this.

## Risks and shortest falsifiers

| Risk | Shortest falsifier |
|---|---|
| A public Var or schema changes during extraction. | Compare the analyzer-derived public Var/metadata set and registered schema key/form set before and after the refactor. |
| An implementation uses `::` and accidentally creates `:seon.db.impl.*/*` data. | Static scan implementation sources; all boundary keys must be explicitly `:seon.db/*`, `:seon.db.protocol/*`, or another established owner. Add closed-schema round trips. |
| Public `*conn*` and implementation state become two owners. | Assert there is exactly one `seon.db/*conn*` definition and no implementation connection atom other than the remote session owner. |
| A downstream execution flavor omits the remote mapping. | Make the logical `seon.db.impl` namespace intentionally absent and assert every maintained execution build resolves it to remote. Verify default and ACME build descriptors carry the same semantic selection. |
| Self-host authored code resolves implementation symbols. | Compile and invoke an authored form using both `[seon.db :as db]` and `seon.db/query`; inspect the stored/analyzed symbol and result. Neither may name `seon.db.impl.*`. |
| Remote code accidentally calls a local database-value function. | The remote implementation returns or throws one immediate core-boundary failure in tests; execution fixtures exercise every retained public operation over a fake/native session. No fallback to local Datahike exists. |
| Namespace aliases behave differently in dev and release. | Generate Shadow reachability evidence for both modes. The maintained Shadow source applies aliases during shared configuration before dependency resolution, but the repository must prove both artifacts. |
| Artifact bytes fall but RSS does not. | Measure child RSS/PSS and cold/warm latency independently; do not infer runtime memory from bundle bytes. |
| A child inherits an unrelated credential or mutable cwd. | Spawn a probe child from a parent containing sentinel environment secrets and a changed runtime cwd. Assert the child sees only the allowlist plus Bun IPC variables and reports the descriptor-selected cwd. |
| Removing `PATH` prevents startup. | Require an absolute Bun executable in host configuration and prove spawn succeeds with an environment containing no `PATH`. |
| The child opens another artifact tree. | Change the parent's `SEON_RUNTIME_ROOT` after host configuration; the child must still load the launch-frozen root and match its bootstrap/artifact digests. |
| Provider configuration broadens every child's credentials. | For each provider fixture, assert exactly its selected secret is present; a database-only/authored-render child sees none. |
| Eval namespace load silently revives manifest/env configuration. | Start with sentinel `SEON_CONFIG`, `SEON_EVAL_RESULT_VARS_CAP`, and render variables; prove startup/config acquisition supplies the selected limits and the child does not read the sentinels. |

## Verification and graduation evidence

No build was run during this audit because the checkout was at a coordinated
source-freeze checkpoint. Once the editing lanes are coherent, capture one
before/after evidence set from the same revision, Bun version, machine, and
artifact mode.

### Reachability and artifact size

1. Build `:execution` and `:acme-execution` from clean flavor-owned Shadow
   caches.
2. Generate Shadow's release report data using
   `shadow.cljs.build-report`. Its `extract-report-data` records each source's
   requires, JavaScript size, optimized byte contribution, module entries, and
   module gzip size. Compare source sets and optimized/gzip bytes, not only the
   small Node loader file.
3. For the unoptimized diagnostic artifact, enumerate the loader's
   `SHADOW_IMPORT` entries and sum exactly those runtime files. Do not sum the
   whole cache directory, which can retain stale unreferenced outputs.
4. Require zero execution-build sources whose provided namespace starts with
   `datahike.`, `konserve.`, `org.replikativ.persistent-sorted-set`,
   `superv.async`, or `is.simm.partial-cps`.
5. Require JVM writer builds to retain their Datahike graph and pass their
   existing authority tests.

The audited baseline command shape was deliberately read-only:

```bash
sed -n 's/^SHADOW_IMPORT("\(.*\)");/\1/p' out/execution/main.js
```

Map those names into the execution flavor's `cljs-runtime` directory before
summing bytes. The current baseline is 338 live imports / 18,810,383 bytes,
with 81 heavy database-stack imports / 8,293,078 bytes.

### Behavior

- All existing `seon.db` schema and public-interface parity fixtures pass.
- Focused execution tests prove open, concurrent multiplexed reads,
  execute-many, transact recovery, interest delivery, timeout/cancel, close,
  source compile, and ordinary result bounds without importing Datahike.
- Legacy client and replica tests remain green only until their atomic deletion
  checkpoint. No test is allowed to make the execution artifact fall back to a
  local database.
- An authored source fixture requires `seon.db`, runs a coordinate-pinned read,
  awaits it, and returns ordinary data. Analyzer and durable program facts keep
  the `seon.db` symbol identity.
- A production Bun child completes ready, one cold authored invocation, one
  warm invocation, cancellation, and clean shutdown against the real authority.
- A production child starts with an absolute Bun executable, explicit cwd, no
  `PATH`, and the exact environment allowlist; sentinel parent secrets are
  absent. Runtime-root/bootstrap identity remains stable after parent env/cwd
  mutation.

### Runtime density

Measure 1, 8, and 32 identical idle children after readiness, then after one
cold and one warm invocation:

- macOS: sample each child PID with `ps -o rss=` at a fixed quiescence window;
- Linux: record `VmRSS` plus proportional/private evidence from
  `/proc/<pid>/smaps_rollup` when available;
- record median and p95 ready latency, cold invocation latency, warm invocation
  latency, per-child RSS/PSS, aggregate children RSS/PSS, and idle reclaim; and
- run at least 20 repetitions per point, alternating baseline and candidate to
  reduce thermal/cache ordering bias.

Use the same immutable execution artifact digest and database coordinate for
both sides. Report absolute values and deltas. The expected direction is lower
cold load work and lower per-child retained memory, but no graduation claim is
valid until measured.

## Graduation decision

Graduate this seam only when both execution flavors have zero heavy database
stack reachability, the public/schema/self-host identity fixtures are exact,
the native session behavior is green, and measured child density does not
regress. The final program still graduates only after the local implementation,
replica, feed/replay, Node runtime, and obsolete `*conn*`/database-value surface
are deleted and the full Bun-authority system passes live cluster proof.
