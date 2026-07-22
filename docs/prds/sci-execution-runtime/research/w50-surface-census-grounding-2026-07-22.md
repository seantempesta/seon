---
type: research
status: active
tags: [research, architecture, agent, testing]
---

# W5-0 host-tier agent-surface census grounding (2026-07-22)

Read-only grounding complete. The only repository change from this pass is this
report. No production source, test, database, process, or live-cluster state was
changed.

## Executive verdict

The q34 premise is correct that the host tier is missing staple agent
capabilities, but the interpretation of the boot counters needs one correction:
`base-excluded=112` is **not a hand-written blacklist**. It is a computed,
source-text classifier over top-level `def`/`defn`/`defn-` blocks discovered
under `src/my`; it does not enumerate `seon.db`, `seon.agent.message`,
`seon.agent.lifecycle`, protected capability namespaces, or even every form in
the `my.*` files ([context.clj:1125](/Users/sean/src/seon/src/seon/host/context.clj:1125),
[context.clj:1137](/Users/sean/src/seon/src/seon/host/context.clj:1137),
[context.clj:1275](/Users/sean/src/seon/src/seon/host/context.clj:1275)). It is
therefore a computed **host-loader diagnostic**, not the W5-0 census's left side.

The census's left side should be the deliberate child agent surface already
represented in the program graph: compiled public first-party function rows
whose colocated var metadata projects `:seon.fn/agent-facing? true`. The compiler
macro computes every public first-party function in the runtime require closure
([indexing.clj:85](/Users/sean/src/seon/src/seon/client/indexing.clj:85)); the boot
indexer projects the positive marker into the `:seon.fn` row
([client.cljs:1491](/Users/sean/src/seon/src/seon/client.cljs:1491)); and the
agent's function-menu acquisition selects exactly marked, public function rows
([menu.cljs:305](/Users/sean/src/seon/src/seon/agent/ctx/menu.cljs:305),
[menu.cljs:423](/Users/sean/src/seon/src/seon/agent/ctx/menu.cljs:423)). Namespace
full-source policy is only a storage/render-enablement policy and explicitly does
not decide exposure ([resolve.cljc:838](/Users/sean/src/seon/src/seon/config/resolve.cljc:838),
[namespaces.cljs:111](/Users/sean/src/seon/src/seon/agent/ctx/namespaces.cljs:111)).

All four staple families can be installed through the one host wrapper registry,
because a registry entry may contain a compiled function or immutable value,
registration creates/upgrades shared SCI vars, and the registry-backed load
function installs them lazily into every fork
([context.clj:851](/Users/sean/src/seon/src/seon/host/context.clj:851),
[context.clj:879](/Users/sean/src/seon/src/seon/host/context.clj:879),
[context.clj:925](/Users/sean/src/seon/src/seon/host/context.clj:925)). That does
not make their implementations equally cheap:

- `seon.db` is a writer-session capability family; five host names exist today,
  but the deliberate child surface has fifteen functions and even overlapping
  names do not yet have full call-shape parity
  ([context.clj:962](/Users/sean/src/seon/src/seon/host/context.clj:962),
  [index_core_test.cljs:138](/Users/sean/src/seon/test/seon/index_core_test.cljs:138)).
- `seon.agent.message` is a writer-session capability family with host-side
  transaction composition, generated-id allocation, current-agent attribution,
  and time as its missing implementation
  ([message.cljs:398](/Users/sean/src/seon/src/seon/agent/message.cljs:398),
  [message.cljs:507](/Users/sean/src/seon/src/seon/agent/message.cljs:507)).
- The **agent-facing** `seon.agent.lifecycle` subset is also primarily a
  writer-session capability family, but it must port its durable transition and
  authorization logic; its non-agent-facing `resume!`/unhost operations remain
  process-hosting code and are not part of this left set
  ([lifecycle.cljs:86](/Users/sean/src/seon/src/seon/agent/lifecycle.cljs:86),
  [lifecycle.cljs:219](/Users/sean/src/seon/src/seon/agent/lifecycle.cljs:219),
  [lifecycle.cljs:389](/Users/sean/src/seon/src/seon/agent/lifecycle.cljs:389)).
- `my.blob` is the genuine platform port: four of its five agent-facing
  operations touch the configured archive, directly or transitively, and its
  internal owner imports Node crypto, filesystem, and path modules; `stat` alone
  can ride the writer capability without archive IO
  ([blob/internal.cljs:1](/Users/sean/src/seon/src/my/blob/internal.cljs:1),
  [blob.cljs:284](/Users/sean/src/seon/src/my/blob.cljs:284),
  [blob.cljs:307](/Users/sean/src/seon/src/my/blob.cljs:307),
  [blob.cljs:440](/Users/sean/src/seon/src/my/blob.cljs:440)).

The fastest W5-0 sequence is therefore: land the computed red gate first; close
the complete `seon.db` contract; capability-route messaging; capability-route
the agent-facing lifecycle transitions (with `complete` after messaging); port
the blob archive in parallel once the database wrapper contract is fixed; then
require the full computed gate and the db/blob/message/complete live drive green
before any child-retirement proof or W5 deletion. The program ledger already
names q34 as the W5-0 hard gate before any cutover drive
([program-synthesis:400](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:400),
[program-synthesis:771](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:771)).

## Dependency and first-party mechanism ledger

| Dependency or mechanism | Selected source | Why it governs this census |
|---|---|---|
| SCI | `reference-code/sci` at `8fac6e88f32d53a5fd82ebe80640881e317b84fd`; host is a local-root dependency | The host composes SCI onto the writer dependency basis ([deps.edn:53](/Users/sean/src/seon/deps.edn:53)); the active checkout correction is recorded in the program ledger ([program-synthesis:1308](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:1308)). SCI `fork` copies the environment atom while retaining shared values ([sci/core.cljc:318](/Users/sean/src/seon/reference-code/sci/src/sci/core.cljc:318)); `add-namespace!` merges an exact namespace map into a context ([sci/core.cljc:651](/Users/sean/src/seon/reference-code/sci/src/sci/core.cljc:651)); a missing require calls the configured `:load-fn` ([load.cljc:198](/Users/sean/src/seon/reference-code/sci/src/sci/impl/load.cljc:198)). |
| Writer/Datahike basis | `:writer` alias; maintained checkout `c1c4c29382257317cd34e160df11985cb384f8a6` | The checkout revision is recorded by the adjacent supervision grounding ([wps-supervision-grounding:37](/Users/sean/src/seon/docs/prds/sci-execution-runtime/research/wps-supervision-grounding-2026-07-22.md:37)); the writer alias selects that maintained local Datahike source ([deps.edn:17](/Users/sean/src/seon/deps.edn:17)); the host alias composes onto that exact basis ([deps.edn:53](/Users/sean/src/seon/deps.edn:53)). |
| Child program-surface enumeration | `seon.client.indexing/public-fn-vars` -> `seon.client/core-vars` -> `seon.client/index-core!` | The macro is analyzer-derived and restricted to public first-party functions in the calling runtime's transitive require closure ([indexing.clj:10](/Users/sean/src/seon/src/seon/client/indexing.clj:10), [indexing.clj:85](/Users/sean/src/seon/src/seon/client/indexing.clj:85)); `core-vars` is explicitly the complete computed vector, not a curated table ([client.cljs:1113](/Users/sean/src/seon/src/seon/client.cljs:1113)). |
| Child execution bundle | `seon.execution.runtime` | The execution child directly requires every `my.*` toolkit namespace plus message, lifecycle, database, filesystem, shell, search, and web capability namespaces ([runtime.cljs:6](/Users/sean/src/seon/src/seon/execution/runtime.cljs:6)). |
| Host capability provisioning | `seon.host.context/register-host-wrappers!` plus `registry-load-fn` | This is the one registry path; host-authored wrappers are read-only SCI built-ins ([context.clj:894](/Users/sean/src/seon/src/seon/host/context.clj:894)), and first require links its shared vars into the requesting context ([context.clj:925](/Users/sean/src/seon/src/seon/host/context.clj:925)). |
| Existing scan-test idiom | `seon.test.source-scan` and the q23 internal-boundary gate | The helper recursively discovers Clojure source and isolates namespace forms ([source_scan.cljs:7](/Users/sean/src/seon/test/seon/test/source_scan.cljs:7), [source_scan.cljs:19](/Users/sean/src/seon/test/seon/test/source_scan.cljs:19)); the conformance test computes violations, rejects unallowlisted rows, and rejects stale exceptions ([internal_require_boundary_test.cljs:25](/Users/sean/src/seon/test/seon/internal_require_boundary_test.cljs:25), [internal_require_boundary_test.cljs:72](/Users/sean/src/seon/test/seon/internal_require_boundary_test.cljs:72)). |

## 1. How the host base produces 166/172/6/112

### The exact pipeline

`seon.host/start!` calls `context/build-base!` once, retains its report, and the
ready line prints `loaded/pure-blocks`, `failed`, and `excluded` directly from
that report ([host.clj:258](/Users/sean/src/seon/src/seon/host.clj:258),
[host.clj:331](/Users/sean/src/seon/src/seon/host.clj:331)). `build-base!`:

1. creates the wrapper registry and registers current host capabilities;
2. initializes SCI with only interrupt-aware `clojure.core` and
   `clojure.string` as eager namespace maps plus the registry-backed load
   function;
3. calls `load-portable-slice!`; and
4. stamps every loaded base var read-only before forks can use it
   ([context.clj:1373](/Users/sean/src/seon/src/seon/host/context.clj:1373)).

The portable loader recursively discovers every `.clj`, `.cljc`, and `.cljs`
file under `src/my`, sorts the paths, parses each namespace and its require
edges, extracts only top-level `def`, `defn`, and `defn-` forms, and
topologically orders source units by intra-corpus requires
([context.clj:1125](/Users/sean/src/seon/src/seon/host/context.clj:1125),
[context.clj:1137](/Users/sean/src/seon/src/seon/host/context.clj:1137),
[context.clj:1170](/Users/sean/src/seon/src/seon/host/context.clj:1170),
[context.clj:1182](/Users/sean/src/seon/src/seon/host/context.clj:1182)).

Each tools.reader-selected host block is classified `:excluded` when its source
contains any textual marker in one regex: async metadata, `await`, JS interop,
selected database calls, or `blob/`; otherwise the loader evaluates it in SCI
and records either `:loaded` or `:failed`
([context.clj:1156](/Users/sean/src/seon/src/seon/host/context.clj:1156),
[context.clj:1287](/Users/sean/src/seon/src/seon/host/context.clj:1287)). A failed
row is reclassified as excluded only when SCI supplied an unresolved symbol and
that simple name matches an excluded definition in the **same namespace**
([context.clj:1334](/Users/sean/src/seon/src/seon/host/context.clj:1334),
[context.clj:1343](/Users/sean/src/seon/src/seon/host/context.clj:1343)). Final
counters are frequencies over those rows; `pure-blocks` means every row not
classified excluded, so `172 = 166 loaded + 6 failed`, while `112` is separate
([context.clj:1365](/Users/sean/src/seon/src/seon/host/context.clj:1365)).

### Computed rule, not hand list — but not sufficient

The include/exclude decision is computed from discovered files, parsed
definition blocks, parsed require edges, and a source-text predicate; no
namespace or function allow/deny list supplies the 112 rows
([context.clj:1127](/Users/sean/src/seon/src/seon/host/context.clj:1127),
[context.clj:1141](/Users/sean/src/seon/src/seon/host/context.clj:1141),
[context.clj:1156](/Users/sean/src/seon/src/seon/host/context.clj:1156)). It meets
the narrow “computed, not hand list” ownership rule, but it is not a semantic
portability proof: it ignores `defonce` and other top-level form types, classifies
by textual substrings rather than resolved dependencies, and scans only
`src/my` ([context.clj:1125](/Users/sean/src/seon/src/seon/host/context.clj:1125),
[context.clj:1137](/Users/sean/src/seon/src/seon/host/context.clj:1137),
[context.clj:1156](/Users/sean/src/seon/src/seon/host/context.clj:1156)).

### The six failures on current source

A read-only invocation of the existing `build-base!` with an unconnected writer
reproduced exactly `{:files 13, :pure-blocks 172, :loaded 166, :failed 6,
:excluded 112}`. The failure rows and their source causes are:

| Failed block | Loader error | Grounded cause |
|---|---|---|
| `my.blob.internal/configure-storage-view!` | `Unable to resolve symbol: !storage-view` | `!storage-view` is a `defonce`, but the loader extracts only `def`/`defn`/`defn-`; the selected function dereferences it ([blob/internal.cljs:18](/Users/sean/src/seon/src/my/blob/internal.cljs:18), [blob/internal.cljs:25](/Users/sean/src/seon/src/my/blob/internal.cljs:25), [context.clj:1137](/Users/sean/src/seon/src/seon/host/context.clj:1137)). |
| `my.blob.internal/validated-storage-view` | `Unable to resolve symbol: normalize-storage-view` | `normalize-storage-view` contains Node path method interop and is classified excluded by the regex; `validated-storage-view` calls it from a textually “pure” block ([blob/internal.cljs:37](/Users/sean/src/seon/src/my/blob/internal.cljs:37), [blob/internal.cljs:58](/Users/sean/src/seon/src/my/blob/internal.cljs:58), [context.clj:1156](/Users/sean/src/seon/src/seon/host/context.clj:1156)). |
| `my.blob.internal/materialize-retained!` | `Unable to resolve symbol: materialize-retained-with-effects!` | The helper contains JS error-property interop and is excluded; its thin caller remains a candidate and then fails ([blob/internal.cljs:391](/Users/sean/src/seon/src/my/blob/internal.cljs:391), [blob/internal.cljs:462](/Users/sean/src/seon/src/my/blob/internal.cljs:462), [blob/internal.cljs:468](/Users/sean/src/seon/src/my/blob/internal.cljs:468)). |
| `my.plan.internal/plan-ai` | `Unable to resolve symbol: tokens/bounded-pr-str` | The real source calls `seon.ai.tokens/bounded-pr-str`, while the host registry publishes only `estimate`, `estimate-chars`, and `clip-str` for that namespace ([plan/internal.cljc:2112](/Users/sean/src/seon/src/my/plan/internal.cljc:2112), [tokens.cljc:226](/Users/sean/src/seon/src/seon/ai/tokens.cljc:226), [context.clj:1028](/Users/sean/src/seon/src/seon/host/context.clj:1028)). |
| `my.blob/observe-retained` | `Unable to resolve symbol: internal/observe-retained` | The parent block delegates directly to an internal namespace function, but the internal implementation's JS error-property interop makes that provider block nonportable under the regex ([blob.cljs:207](/Users/sean/src/seon/src/my/blob.cljs:207), [blob/internal.cljs:284](/Users/sean/src/seon/src/my/blob/internal.cljs:284), [blob/internal.cljs:299](/Users/sean/src/seon/src/my/blob/internal.cljs:299)). |
| `my.plan/program-without-coordinator-home` | `Unable to resolve symbol: home/home-ns` | The function calls `seon.agent.home/home-ns`; synthetic namespace forms retain only require targets present in the toolkit or wrapper registry, and the registry does not publish `seon.agent.home` ([plan.cljc:1133](/Users/sean/src/seon/src/my/plan.cljc:1133), [plan.cljc:1145](/Users/sean/src/seon/src/my/plan.cljc:1145), [context.clj:1228](/Users/sean/src/seon/src/seon/host/context.clj:1228), [context.clj:947](/Users/sean/src/seon/src/seon/host/context.clj:947)). |

The current test proves accounting integrity and source-order preservation, but
does not require zero failures or compare the ledger with the child agent
surface ([host_toolkit_writer_test.clj:31](/Users/sean/src/seon/test/seon/host_toolkit_writer_test.clj:31)).

## 2. The child tier's authoritative exposed surface

Three distinct mechanisms must not be conflated:

- `seon.execution.runtime` determines what first-party namespaces are compiled
  into the execution child; it explicitly requires the complete `my.*` toolkit
  and protected staple/capability namespaces
  ([runtime.cljs:6](/Users/sean/src/seon/src/seon/execution/runtime.cljs:6)).
- `seon.agent.home/home-ns-require-specs` determines only the aliases/refers
  installed in an agent's home namespace: message, lifecycle refers, schema,
  database, and plan ([home.cljs:90](/Users/sean/src/seon/src/seon/agent/home.cljs:90)).
  Authored namespaces derive the smaller real-require alias subset from that
  same data ([eval.cljs:1681](/Users/sean/src/seon/src/seon/eval.cljs:1681)).
- The namespace policy determines which framework namespaces have complete
  source stored so a context block can select them; it explicitly does not
  decide rendering or callability ([resolve.cljc:838](/Users/sean/src/seon/src/seon/config/resolve.cljc:838),
  [namespaces.cljs:111](/Users/sean/src/seon/src/seon/agent/ctx/namespaces.cljs:111)).

The authoritative census input is the **program-graph function population with
the positive `:seon.fn/agent-facing?` fact**. `public-fn-vars` computes the
complete compiled public first-party population, `var->fn-row` carries the
positive marker, and the child menu queries that same fact rather than a
separate catalog ([indexing.clj:85](/Users/sean/src/seon/src/seon/client/indexing.clj:85),
[client.cljs:1502](/Users/sean/src/seon/src/seon/client.cljs:1502),
[menu.cljs:423](/Users/sean/src/seon/src/seon/agent/ctx/menu.cljs:423)). Existing
indexer tests already distinguish “public and indexed” from “deliberately
agent-facing” and pin the exact `seon.db` marked set
([index_core_test.cljs:102](/Users/sean/src/seon/test/seon/index_core_test.cljs:102),
[index_core_test.cljs:138](/Users/sean/src/seon/test/seon/index_core_test.cljs:138)).

The gate should therefore read these rows (or, in an isolated source-only unit
test, compute the same positive metadata projection with the real reader). It
should not treat the default namespace policy, home aliases, all SCI bindings,
or all `src/my` definitions as the left set.

## 3. Current host capability registration and reach

`register-host-capabilities!` is private but is called unconditionally when the
base is built ([context.clj:947](/Users/sean/src/seon/src/seon/host/context.clj:947),
[context.clj:1383](/Users/sean/src/seon/src/seon/host/context.clj:1383)). On
current source it registers these families:

- `seon.ai.provider`: `provider-locality`, `frontier-provider?`;
- `seon.db`: `query`, `query-with-evidence`, `pull`, `transact!`, and host-only
  `head`;
- `seon.db.id`: `candidate-manifest`, `generator-policy-query`;
- `seon.db.protocol`: query/pull operation and result/success keys;
- `seon.schema`: `validate`, `register!`, `schema-definition`;
- `seon.ai.tokens`: `estimate`, `estimate-chars`, `clip-str`;
- `seon.content-hash`: `sha-256`;
- `seon.time`: `iso-string`;
- parser/repair helpers, skill-file helpers, and the canvas signal helper
  ([context.clj:955](/Users/sean/src/seon/src/seon/host/context.clj:955),
  [context.clj:999](/Users/sean/src/seon/src/seon/host/context.clj:999),
  [context.clj:1028](/Users/sean/src/seon/src/seon/host/context.clj:1028),
  [context.clj:1041](/Users/sean/src/seon/src/seon/host/context.clj:1041),
  [context.clj:1053](/Users/sean/src/seon/src/seon/host/context.clj:1053),
  [context.clj:1073](/Users/sean/src/seon/src/seon/host/context.clj:1073),
  [context.clj:1108](/Users/sean/src/seon/src/seon/host/context.clj:1108)).

No current registration publishes `my.blob`, `seon.agent.message`, or
`seon.agent.lifecycle`; the registry seeding function ends after the canvas
helper ([context.clj:1108](/Users/sean/src/seon/src/seon/host/context.clj:1108)).

The registry can carry all four namespaces mechanically. Its wrapper union
accepts either a compiled function or a value
([context.clj:110](/Users/sean/src/seon/src/seon/host/context.clj:110)); new
namespaces become requireable through the existing load function
([context.clj:925](/Users/sean/src/seon/src/seon/host/context.clj:925)); and the
eval invocation binds the exact agent id around the host batch, while database
wrappers derive the same `:seon.db/user` and `:seon.db/process` provenance from
that binding ([invoke.clj:122](/Users/sean/src/seon/src/seon/host/invoke.clj:122),
[context.clj:59](/Users/sean/src/seon/src/seon/host/context.clj:59),
[context.clj:69](/Users/sean/src/seon/src/seon/host/context.clj:69)).

Name overlap is not yet contract parity:

- host `query` accepts `[query-form & arguments]`, whereas the child also
  accepts its request-map form with database, args, and resource options
  ([context.clj:966](/Users/sean/src/seon/src/seon/host/context.clj:966),
  [db.cljs:990](/Users/sean/src/seon/src/seon/db.cljs:990));
- host `pull` accepts only `[selector entity-id]`, while the child has request,
  two-argument, and explicit-database three-argument forms
  ([context.clj:973](/Users/sean/src/seon/src/seon/host/context.clj:973),
  [db.cljs:1018](/Users/sean/src/seon/src/seon/db.cljs:1018));
- host `transact!` registers one argument, while the child accepts its validated
  request/raw-data and explicit-database forms
  ([context.clj:976](/Users/sean/src/seon/src/seon/host/context.clj:976),
  [db.cljs:909](/Users/sean/src/seon/src/seon/db.cljs:909)); and
- the host publishes `head`, not the child name and arities of `seon.db/db`
  ([context.clj:979](/Users/sean/src/seon/src/seon/host/context.clj:979),
  [db.cljs:793](/Users/sean/src/seon/src/seon/db.cljs:793)).

## 4. Honest dispositions for the four staple families

### Disposition summary

| Family and child marked surface | Family disposition | Specific blocker and required host work |
|---|---|---|
| `seon.db`: `current-agent-id`, `db`, `as-of`, `since`, `history`, `cas-assert`, `transact!`, `query`, `query-with-evidence`, `pull`, `pull-many`, `entity`, `installed-schema`, `execute-many`, `index-page` | **Needs a capability shim over the host writer session; partially present.** | The child namespace is `.cljs` and owns a multiplexed async UDS session ([db.cljs:1](/Users/sean/src/seon/src/seon/db.cljs:1)); the host already owns synchronous UDS writer calls and wrappers for four overlapping child names plus `head` ([context.clj:638](/Users/sean/src/seon/src/seon/host/context.clj:638), [context.clj:962](/Users/sean/src/seon/src/seon/host/context.clj:962)). Add the missing names and exact child arities/options; reuse pure database-value transformations for `as-of`/`since`/`history` and the pure CAS vector shape shown in the child ([db.cljs:805](/Users/sean/src/seon/src/seon/db.cljs:805), [db.cljs:825](/Users/sean/src/seon/src/seon/db.cljs:825)). `entity` is `pull '[*]`; `pull-many`, schema, execute-many, and index-page already have writer protocol operations in the child surface and need host protocol wrappers ([db.cljs:1054](/Users/sean/src/seon/src/seon/db.cljs:1054), [db.cljs:1083](/Users/sean/src/seon/src/seon/db.cljs:1083), [db.cljs:1093](/Users/sean/src/seon/src/seon/db.cljs:1093), [db.cljs:1108](/Users/sean/src/seon/src/seon/db.cljs:1108), [db.cljs:1164](/Users/sean/src/seon/src/seon/db.cljs:1164)). |
| `my.blob`: `put!`, `get`, `concat!`, `text`, `stat` | **Platform-bound; needs a real JVM archive port, with `stat` separately writer-routeable.** | The internal owner imports Node crypto/fs/path and keeps a process-local storage view ([blob/internal.cljs:7](/Users/sean/src/seon/src/my/blob/internal.cljs:7), [blob/internal.cljs:18](/Users/sean/src/seon/src/my/blob/internal.cljs:18)). `put!` and `concat!` invoke Node publication effects ([blob.cljs:284](/Users/sean/src/seon/src/my/blob.cljs:284), [blob.cljs:347](/Users/sean/src/seon/src/my/blob.cljs:347)); `get` resolves archive files ([blob.cljs:307](/Users/sean/src/seon/src/my/blob.cljs:307)); `text` reads archive content and may query projection metadata ([blob.cljs:363](/Users/sean/src/seon/src/my/blob.cljs:363)). The port must preserve storage-view selection, SHA-256 addressing, durable publication/fsync/rename behavior, paging, and projection writes; merely registering names would expose no implementation. `stat` queries only the database projection and can be implemented immediately over the writer session ([blob.cljs:404](/Users/sean/src/seon/src/my/blob.cljs:404), [blob.cljs:440](/Users/sean/src/seon/src/my/blob.cljs:440)). |
| `seon.agent.message`: `user`, `agent` | **Needs a capability shim over the host writer session, not base loading as-is.** | The public functions are thin wrappers over `message!` ([message.cljs:495](/Users/sean/src/seon/src/seon/agent/message.cljs:495)); the write boundary derives the current agent, acquires sender/recipient/hop data, allocates generated ids, builds one message/plan transaction, and commits it ([message.cljs:398](/Users/sean/src/seon/src/seon/agent/message.cljs:398), [message.cljs:466](/Users/sean/src/seon/src/seon/agent/message.cljs:466)). The current sources are `.cljs`, use native async/await and JS dates, and their internal acquisition uses `execute-many` plus bounded queries ([message/internal.cljs:94](/Users/sean/src/seon/src/seon/agent/message/internal.cljs:94), [message/internal.cljs:136](/Users/sean/src/seon/src/seon/agent/message/internal.cljs:136)). The host port can use its bound `*agent-id*`, writer reads/writes, `seon.db.id` allocation data, and JVM instants; it must preserve admission refusal or establish that dispatch admission makes the duplicate check unnecessary, because current `message!` checks process-local admission before writing ([message.cljs:440](/Users/sean/src/seon/src/seon/agent/message.cljs:440)). |
| `seon.agent.lifecycle`: `wait`, `complete`, `pause`, `resume`, `terminate` | **Agent-facing subset needs capability shims over the writer session plus a port of durable transition builders; non-agent-facing hosting functions are platform-bound but outside the left set.** | The five marked functions derive caller/target authority and manipulate open-run facts through fenced transactions ([lifecycle.cljs:219](/Users/sean/src/seon/src/seon/agent/lifecycle.cljs:219), [lifecycle.cljs:399](/Users/sean/src/seon/src/seon/agent/lifecycle.cljs:399), [lifecycle.cljs:515](/Users/sean/src/seon/src/seon/agent/lifecycle.cljs:515)). `complete` also checks the latest test result, optionally composes a message, allocates its id, and closes the run atomically ([lifecycle.cljs:252](/Users/sean/src/seon/src/seon/agent/lifecycle.cljs:252), [lifecycle.cljs:303](/Users/sean/src/seon/src/seon/agent/lifecycle.cljs:303), [lifecycle.cljs:322](/Users/sean/src/seon/src/seon/agent/lifecycle.cljs:322)). Pause/resume bank and restore deadline budget with database CAS fences ([run.cljs:797](/Users/sean/src/seon/src/seon/agent/run.cljs:797), [run.cljs:838](/Users/sean/src/seon/src/seon/agent/run.cljs:838)). Current code is `.cljs`, uses JS dates and process admission, so it is not base-loadable unchanged ([lifecycle.cljs:10](/Users/sean/src/seon/src/seon/agent/lifecycle.cljs:10), [lifecycle.cljs:443](/Users/sean/src/seon/src/seon/agent/lifecycle.cljs:443)). `resume!`, `unhost!`, and `unhost-all!` manipulate process-local loop hosting and are deliberately not marked agent-facing ([lifecycle.cljs:86](/Users/sean/src/seon/src/seon/agent/lifecycle.cljs:86), [lifecycle.cljs:100](/Users/sean/src/seon/src/seon/agent/lifecycle.cljs:100)). |

### Base-loadable as-is verdict

No complete staple family is base-loadable as-is. The loader only attempts
`src/my`, so it never considers the three `seon.*` families
([context.clj:1125](/Users/sean/src/seon/src/seon/host/context.clj:1125)); and
`my.blob` closes over a Node-only internal namespace
([blob/internal.cljs:7](/Users/sean/src/seon/src/my/blob/internal.cljs:7)). Some
individual mechanics are portable data transformations—database temporal-map
updates, CAS-vector construction, message title/hop transformations, and run
close transaction data—but exposing a family requires either registered host
wrappers or a real platform port
([db.cljs:805](/Users/sean/src/seon/src/seon/db.cljs:805),
[message/internal.cljs:12](/Users/sean/src/seon/src/seon/agent/message/internal.cljs:12),
[message/internal.cljs:32](/Users/sean/src/seon/src/seon/agent/message/internal.cljs:32),
[run.cljs:514](/Users/sean/src/seon/src/seon/agent/run.cljs:514)).

## 5. Cheapest computed conformance gate

### Location

Put the host-side set-difference gate in a dedicated JVM test namespace,
`test/seon/host_surface_writer_test.clj`, run by `bin/test-writer`. Host
conformance and registry tests already live on that runner, and the existing
toolkit test can build a base with an unconnected writer because wrapper
registration itself is process-local
([host_toolkit_writer_test.clj:6](/Users/sean/src/seon/test/seon/host_toolkit_writer_test.clj:6),
[host_toolkit_writer_test.clj:31](/Users/sean/src/seon/test/seon/host_toolkit_writer_test.clj:31)).

### Set shape

The test should compute:

```clojure
missing = child-agent-facing-symbols - keys(host-dispositions)
```

with these inputs:

1. `child-agent-facing-symbols`: every public source function carrying positive
   `:seon.fn/agent-facing?` metadata, using the real Clojure reader and the same
   first-party source roots as the program indexer. In an integration form of
   the gate, read the already-derived `:seon.fn` program rows instead. The
   source marker and its program-row projection are the same authority
   ([indexing.clj:85](/Users/sean/src/seon/src/seon/client/indexing.clj:85),
   [client.cljs:1502](/Users/sean/src/seon/src/seon/client.cljs:1502)).
2. `host-dispositions`: for every left symbol, fork the real built base, require
   its namespace, and classify the exact SCI var as:
   - `:resolved-base` when it exists without a registry entry;
   - `:capability-routed` when the exact `(namespace, name)` is present in the
     one wrapper registry and resolves after require; or
   - `:excluded` only when the colocated source metadata carries a structured,
     nonblank reason and a designed alternative.
   SCI's registry load path and namespace installation are the real host
   mechanisms ([context.clj:900](/Users/sean/src/seon/src/seon/host/context.clj:900),
   [context.clj:925](/Users/sean/src/seon/src/seon/host/context.clj:925)).
3. Assertions: `missing` is empty; every registered/rule disposition resolves
   to the claimed symbol or has a valid exclusion; and no disposition is stale
   (`keys(host-dispositions) - child-agent-facing-symbols` is empty).

Do **not** add a namespace/function allowlist to the test. If designed
exclusions are needed, keep their reason beside the function's positive
agent-facing metadata so source scanning derives them; a separate test table
would recreate the blackout-list problem. The q23 precedent is the right
testing shape: recursively scan source, compute violations, fail every
unaccounted row, and also fail stale exceptions
([source_scan.cljs:7](/Users/sean/src/seon/test/seon/test/source_scan.cljs:7),
[internal_require_boundary_test.cljs:72](/Users/sean/src/seon/test/seon/internal_require_boundary_test.cljs:72)). Use the real reader for function metadata rather than extending
`sanitized-ns-form`'s regex role; that helper owns namespace-form scanning, not
general Clojure parsing ([source_scan.cljs:19](/Users/sean/src/seon/test/seon/test/source_scan.cljs:19)).

### Why this is cheaper than gating the 166/172 ledger

Requiring `base-failed=0` or `base-excluded=0` would make private teaching and
platform implementation blocks part of the cutover contract even when they are
not deliberate agent functions. Conversely, accepting `166/172` misses every
protected namespace because the loader never scanned them. The positive
agent-facing program fact is already the semantic boundary used by child
discovery, so the direct set difference tests exactly the W5-0 contract without
a second catalog ([menu.cljs:305](/Users/sean/src/seon/src/seon/agent/ctx/menu.cljs:305),
[context.clj:1125](/Users/sean/src/seon/src/seon/host/context.clj:1125)).

Resolution is necessary but not sufficient. Keep the existing host
instrumentation/differential tests for behavior, and add focused parity tests
for every wrapper whose child and host call shapes differ; the current database
overlaps already demonstrate why symbol resolution alone can lie
([context.clj:962](/Users/sean/src/seon/src/seon/host/context.clj:962),
[db.cljs:909](/Users/sean/src/seon/src/seon/db.cljs:909),
[db.cljs:990](/Users/sean/src/seon/src/seon/db.cljs:990)).

## 6. W5-0 sequencing, risks, and cutover blockers

### Fastest dependency order

1. **Land the computed census test red first.** It establishes the complete
   symbol list and prevents a local four-family patch from hiding other missing
   agent-facing capabilities. The issue acceptance explicitly requires a
   source/live-host computed gate that turns red for a newly exposed function
   ([host-base-agent-surface-parity.md:35](/Users/sean/src/seon/docs/seon/issues/host-base-agent-surface-parity.md:35)).
2. **Complete `seon.db` host parity.** Messaging, lifecycle, blob projection,
   and most of the broader toolkit require database values, reads, writes, or
   generated-id allocation; current registry coverage is the reusable base but
   is incomplete and shape-divergent ([context.clj:962](/Users/sean/src/seon/src/seon/host/context.clj:962),
   [db.cljs:1054](/Users/sean/src/seon/src/seon/db.cljs:1054)).
3. **Capability-route `seon.agent.message`.** It depends on the completed
   database surface and immediately restores human delivery; its public API is
   only two thin functions over one validated write boundary
   ([message.cljs:495](/Users/sean/src/seon/src/seon/agent/message.cljs:495)).
4. **Capability-route agent-facing lifecycle.** Implement `wait`, `pause`,
   `resume`, and `terminate` from durable writer operations; implement
   `complete` after messaging because it may compose the result message into
   the same atomic close transaction ([lifecycle.cljs:303](/Users/sean/src/seon/src/seon/agent/lifecycle.cljs:303),
   [lifecycle.cljs:322](/Users/sean/src/seon/src/seon/agent/lifecycle.cljs:322)).
5. **Start the real `my.blob` JVM archive port as soon as the database wrapper
   contract is fixed; it can run parallel to steps 3-4.** Its filesystem and
   publication semantics are independent of message/lifecycle logic, while its
   projection calls depend on database parity
   ([blob.cljs:284](/Users/sean/src/seon/src/my/blob.cljs:284),
   [blob.cljs:404](/Users/sean/src/seon/src/my/blob.cljs:404)).
6. **Make the full computed gate green, then run the W5-0 live composition
   drive**: database write/read, blob put/read, `message/user`, wrong-arg and
   unresolved-symbol containment, and `lifecycle/complete` ending
   `:completed`. The issue requires db/blob/message/lifecycle end to end and the
   gate green before the retirement preflight
   ([host-base-agent-surface-parity.md:42](/Users/sean/src/seon/docs/seon/issues/host-base-agent-surface-parity.md:42),
   [host-base-agent-surface-parity.md:45](/Users/sean/src/seon/docs/seon/issues/host-base-agent-surface-parity.md:45)).
7. **Only then run the Stage-1.5 retirement proof and begin W5 deletion.** The
   accepted Stage-1.5 verdict requires the real child/JVM in-flight retirement
   and same-artifact route/browser/SSE proof before the first child-lane
   deletion ([stage15-gate-verdict:129](/Users/sean/src/seon/docs/prds/sci-execution-runtime/research/stage15-gate-verdict-2026-07-22.md:129)).

### Capability-routeable versus genuine cutover blockers

- **Capability-routeable:** the database protocol surface, messaging, and the
  marked lifecycle transitions. They require real host implementations and
  contract tests, but no new provisioning mechanism; all install through the
  existing wrapper registry and writer session
  ([context.clj:879](/Users/sean/src/seon/src/seon/host/context.clj:879),
  [context.clj:947](/Users/sean/src/seon/src/seon/host/context.clj:947)).
- **Genuine platform blocker:** blob archive semantics. The host cannot satisfy
  `put!`/`get`/`concat!`/`text` by forwarding only to the database writer,
  because bytes live in the process-local archive and current publication is
  Node filesystem/crypto code ([blob/internal.cljs:7](/Users/sean/src/seon/src/my/blob/internal.cljs:7),
  [blob.cljs:1](/Users/sean/src/seon/src/my/blob.cljs:1)).
- **Genuine cutover blocker until green:** every unresolved positive
  agent-facing symbol, even if its eventual implementation is easy. The real
  drive already proved that missing message and completion symbols let an agent
  compute for eight contained turns yet fail the human task
  ([host-base-agent-surface-parity.md:13](/Users/sean/src/seon/docs/seon/issues/host-base-agent-surface-parity.md:13),
  [host-base-agent-surface-parity.md:17](/Users/sean/src/seon/docs/seon/issues/host-base-agent-surface-parity.md:17)).

### Principal risks for the W5-0 spec

1. **Using the host loader ledger as the census.** It omits all protected
   namespaces and overcounts private/platform implementation blocks
   ([context.clj:1125](/Users/sean/src/seon/src/seon/host/context.clj:1125),
   [context.clj:1137](/Users/sean/src/seon/src/seon/host/context.clj:1137)).
2. **Counting resolved names as semantic parity.** Existing `seon.db` overlaps
   already differ in name and arity, so W5-0 needs focused call-shape tests after
   the set gate ([context.clj:966](/Users/sean/src/seon/src/seon/host/context.clj:966),
   [db.cljs:1018](/Users/sean/src/seon/src/seon/db.cljs:1018)).
3. **Reimplementing provisioning per family.** SCI already provides one shared
   registry/load-fn path; separate blob/message/lifecycle binding tables would
   violate the settled owner rule and lose live-fork upgrades
   ([context.clj:879](/Users/sean/src/seon/src/seon/host/context.clj:879),
   [context.clj:925](/Users/sean/src/seon/src/seon/host/context.clj:925)).
4. **Silently dropping admission semantics.** Messaging and lifecycle currently
   refuse work when process admission is closed; either the host wrapper must
   reproduce that check or the W5-0 spec must prove the outer dispatch gate is
   the same invariant ([message.cljs:440](/Users/sean/src/seon/src/seon/agent/message.cljs:440),
   [lifecycle.cljs:443](/Users/sean/src/seon/src/seon/agent/lifecycle.cljs:443)).
5. **Porting blob names without durability equivalence.** The public contract
   promises content addressing and durable publication, while current internal
   code owns storage-view normalization and filesystem publication; a wrapper
   that only hashes or writes a regular file would not close parity
   ([blob.cljs:284](/Users/sean/src/seon/src/my/blob.cljs:284),
   [blob/internal.cljs:391](/Users/sean/src/seon/src/my/blob/internal.cljs:391)).

## W5-0 spec inputs distilled

- Left authority: computed positive `:seon.fn/agent-facing?` program rows, not
  namespace policy and not the portable-loader report
  ([client.cljs:1502](/Users/sean/src/seon/src/seon/client.cljs:1502),
  [menu.cljs:423](/Users/sean/src/seon/src/seon/agent/ctx/menu.cljs:423)).
- Right authority: live SCI resolution plus the one wrapper registry; structured
  colocated exclusion metadata only when a designed alternative exists
  ([context.clj:900](/Users/sean/src/seon/src/seon/host/context.clj:900),
  [context.clj:925](/Users/sean/src/seon/src/seon/host/context.clj:925)).
- Hard assertion: `left - right = #{}`, plus stale-disposition rejection using
  the q23 computed-scan idiom
  ([internal_require_boundary_test.cljs:72](/Users/sean/src/seon/test/seon/internal_require_boundary_test.cljs:72)).
- Staple order: database -> messaging -> lifecycle completion; blob port starts
  after the database contract and proceeds independently of message/lifecycle
  ([message.cljs:466](/Users/sean/src/seon/src/seon/agent/message.cljs:466),
  [lifecycle.cljs:322](/Users/sean/src/seon/src/seon/agent/lifecycle.cljs:322),
  [blob.cljs:404](/Users/sean/src/seon/src/my/blob.cljs:404)).
- Graduation gate: computed census green plus the host-tier db/blob/message/
  complete live drive before the Stage-1.5 retirement proof and any W5 deletion
  ([host-base-agent-surface-parity.md:35](/Users/sean/src/seon/docs/seon/issues/host-base-agent-surface-parity.md:35),
  [stage15-gate-verdict:131](/Users/sean/src/seon/docs/prds/sci-execution-runtime/research/stage15-gate-verdict-2026-07-22.md:131)).
