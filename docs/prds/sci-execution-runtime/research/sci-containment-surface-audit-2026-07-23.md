---
type: research
status: active
tags: [research, agent, runtime, sci, containment]
---

# SCI containment-surface audit — 2026-07-23

## Scope and method

Read-only audit of branch `codex/runtime-reliability-refactor`. No files were edited, and no runtime mutations, builds, tests, or adversarial exploits were run.

The audit covered:

- every production SCI or equivalent agent-code environment on JVM and Bun;
- exact default, explicit, dynamic, and result bindings;
- source, symbol, value, and lookup ingress;
- R32 result-symbol lifecycle behavior;
- eval-pool and guarded-door coverage;
- remaining break-8-style data-to-code construction;
- non-production SCI harnesses that could be mistaken for production mechanisms.

“Verified” below means directly established from the checked-in source or generated artifact. “Suspected” means the mechanism exists but exploitability was not demonstrated live.

## Executive verdict

There is not one auditable agent SCI surface today. There are three distinct execution mechanisms:

1. **cluster JVM SCI:** one shared SCI base plus retained per-agent forks. Its explicit wrapper inventory is mostly intentional, but it contains accidental filesystem helpers, management bindings, regex-selected private `my.*` helpers, unguarded reconstruction, and a native JVM graduation escape.

2. **Malli’s hidden SCI evaluator:** Malli dynamically creates and forks independent SCI contexts for schema code. These contexts do not use Seon’s guard holder, policy, output cap, or eval pool.

3. **Maintained Bun execution child:** this is not SCI. Agent code is compiled with `cljs.js` and executed as native JavaScript against the child’s Bun/global runtime. The effective surface is the whole runtime artifact and JavaScript global environment, not the documented home aliases.

The strongest findings are:

- **Critical, verified:** graduated corpus source is compiled with host `clojure.core/eval` and installed behind an SCI var. Its body therefore has native JVM reach and is invisible to SCI interpreter-step accounting.
- **Critical, verified:** the maintained Bun agent path executes native JavaScript with inherited process, filesystem, network, module-loader, Bun, and artifact-global reach.
- **High, verified:** stored agent definitions and graduation tests execute outside the guard door and eval pool.
- **High, verified:** Malli constructs a second, unguarded SCI environment for schema code.
- **High, verified:** the JVM result surface does not implement R32; direct run-holding process batches discard their retained-value map and never bind `result/<id>` in SCI.
- **High, verified:** `seon.agent.ctx/read-file-text` and `list-skill-files` bypass the filesystem grant.
- **High, verified:** break #8’s data-to-code construction remains in several test fixtures.

The target contracts are unambiguous: every JVM eval enters the bounded pool (`docs/seon/architecture/agent-runtime.md:110-113`); every SCI invocation uses one guarded door (`docs/seon/architecture/agent-runtime.md:179-211`); R32 requires process-identity-backed result handles (`docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:421-426`); R33 requires one corpus-loaded SCI interpreter everywhere (`program-synthesis-2026-07-21.md:462-478`); R35 requires ordinary wire data or tracked handles (`program-synthesis-2026-07-21.md:492-505`); and R43 derives authorship from source-datom provenance (`program-synthesis-2026-07-21.md:826-835,1491-1499`).

## Complete environment-construction inventory

### 1. JVM shared SCI base

The only direct production Seon `sci/init` is `seon.host.context/build-base!` (`src/seon/host/context.clj:1281-1315`).

It supplies:

- registry/database `:load-fn`;
- interrupt-aware replacements in `clojure.core` and `clojure.string`;
- `java.util.Date`, `java.lang.Long`, and `Long`;
- an interrupt callback that reads the current fork’s `guard/holder`.

SCI merges these values with its defaults; it does not replace them (`reference-code/sci/src/sci/impl/opts.cljc:17-63,236-272`).

#### Dependency-owned default namespaces

The exact default namespace set on CLJ is:

- `clojure.lang`
- `clojure.core`
- `clojure.string`
- `clojure.set`
- `clojure.walk`
- `clojure.template`
- `clojure.repl`
- `clojure.edn`
- `sci.impl.records`
- `sci.impl.deftype`
- `sci.impl.protocols`

Source: `reference-code/sci/src/sci/impl/namespaces.cljc:2299-2350`.

No `:allow` or `:deny` option is supplied. All SCI-default core vars therefore remain reachable, including SCI-local `eval`, `load-string`, namespace operations, `intern`, `var-get`, and `requiring-resolve` (`reference-code/sci/src/sci/impl/namespaces.cljc:1610,1621-1624,1668,1699-1701,1744-1758,1797-1799,1815,1824`). These resolve inside SCI; they do not by themselves expose arbitrary JVM namespaces.

#### Default and added classes

SCI’s JVM baseline includes:

- `java.lang.AssertionError`
- `java.lang.Exception`
- `java.lang.IllegalArgumentException`
- `clojure.lang.Delay`
- `clojure.lang.ExceptionInfo`
- `clojure.lang.LineNumberingPushbackReader`
- `clojure.lang.LazySeq`
- `java.lang.String`
- `java.io.StringWriter`
- `java.io.StringReader`
- `java.lang.Integer`
- `java.lang.Number`
- `java.lang.Double`
- `java.lang.ArithmeticException`
- `java.lang.Object`
- `sci.lang.IVar`
- `sci.lang.Type`
- `sci.lang.Var`

Source: `reference-code/sci/src/sci/impl/opts.cljc:68-118`.

Seon additionally exposes `Date` and `Long` as open class mappings (`src/seon/host/context.clj:1297-1307`). A bare class mapping permits reflective access to unlisted public members unless its member section is closed (`reference-code/sci/src/sci/impl/interop.cljc:146-164`). This makes JVM-specific methods, including mutable `Date` operations and `Long` static methods, reachable.

Classification:

- SCI language/default classes: intended dependency language surface, although the actual set should be pinned in the computed manifest.
- `Date`/`Long`: verified intentional legacy widening, inconsistent with R35 portability; remove after narrow time/number owners replace their use.

#### Interrupt-aware overrides

The base replaces these core functions:

- `range`, `repeat`, `cycle`, `iterate`
- `doall`, `dorun`
- `count`, `into`, `reduce`
- JVM `re-find`, `re-matcher`, `re-matches`, `re-seq`

It also replaces `clojure.string/replace`, `replace-first`, and `split` (`reference-code/sci/src/sci/interrupt.cljc:289-315`).

These are intentional containment bindings.

### 2. JVM retained and disposable forks

Each retained agent context is exactly a `sci/fork` of the base plus a fresh guard holder and interrupt function (`src/seon/host/context.clj:1317-1324`).

Contexts are cached per agent by:

- UDS startup: `src/seon/host.clj:106-134`;
- cluster JVM driver startup: `src/seon/agent/driver/host.clj:127-143`.

Fresh authored namespaces receive:

- `[clojure.string :as str]`
- `[clojure.set :as set]`
- `[clojure.edn :as edn]`
- `[clojure.walk :as walk]`
- `[seon.db :as db]`
- `[seon.schema :as schema]`
- `[seon.ai.tokens :as tokens]`

Source: `src/seon/host/context.clj:1047-1083`.

Disposable forks are created for:

- preflight analysis: `src/seon/host/preflight.clj:45-60,99-121`;
- pinned authored materialization: `src/seon/host/context.clj:1477-1527`;
- graduation nursery materialization: `src/seon/host/graduate.clj:170-184`.

### 3. JVM registry injection

The process-local registry maps a namespace to shared SCI vars. Host vars receive `:sci/built-in true`; agent corpus vars do not. Registration uses `sci/new-var`; installation uses `sci/add-namespace!`; upgrades alter the shared var root (`src/seon/host/context.clj:440-517`).

`:sci/built-in` prevents agent redefinition. It does not restrict invocation.

The shared load function:

1. installs registered vars if the namespace is registered;
2. otherwise queries `:seon.ns/source` at the current database value and returns it for SCI evaluation.

Source: `src/seon/host/context.clj:521-556`.

The fallback is intended corpus loading, but the load function does not locally prove R43 provenance or an invocation-role admission before returning source.

### 4. Malli’s hidden SCI contexts

`seon.schema/predicate-sci-options` supplies:

- alias `m → malli.core`;
- `malli.core/{properties,type,children,entries}`;
- every qualified predicate/function entry passed in through `predicate-functions`;
- the obsolete `:preset :termination-safe`.

Source: `src/seon/schema.cljc:72-89`.

Malli dynamically resolves `sci.core/init`, `fork`, and `eval-string*`; initializes a cached base per options value; evaluates an alias form; then forks and evaluates every schema code value (`reference-code/malli/src/malli/sci.cljc:4-16`, `reference-code/malli/src/malli/core.cljc:2881-2900`).

Seon passes these options during candidate/default compilation, projection construction, and validator/explainer construction (`src/seon/schema.cljc:318-322,604-607,860-862,1732-1749`).

This is a second production SCI builder with:

- no Seon guard holder;
- no interpreter-step policy;
- no wall deadline owner;
- no output cap;
- no eval-pool admission;
- no invocation receipt.

SCI removed `:preset :termination-safe` because it could not guarantee termination (`reference-code/sci/CHANGELOG.md:633-637,784-788`). The option therefore supplies no current containment.

Current projections supply cached core predicate functions rather than materialized agent corpus functions (`src/seon/schema.cljc:275-288,1180-1239`). Thus the hidden context is a verified guard bypass; broad agent-predicate reach is the R33 target, not yet a verified current binding.

### 5. Maintained Bun execution child

The maintained Bun child constructs no SCI context. It creates a persistent `cljs/empty-state`, exposes loaded namespace roots, loads the bootstrap caches, and executes forms with `cljs/eval-str` plus `cljs/js-eval` (`src/seon/eval.cljs:385-448,1155-1203`).

The child’s effective surface includes:

- normal CLJS language forms and JavaScript interop;
- bootstrap entries `cljs.core`, `cljs.test`, `clojure.set`, `clojure.string`, `clojure.walk`, `seon.schema`, `malli.core`, `malli.registry`, and `cljs.analyzer.api` (`shadow-cljs.edn:354-395`);
- every transitive bootstrap analyzer cache;
- every live execution-artifact namespace root exposed on the global object (`src/seon/eval.cljs:385-435`);
- any qualified member resolvable through the general runtime lookup (`src/seon/eval.cljs:472-501`);
- database-loaded corpus namespaces (`src/seon/eval.cljs:857-920`);
- JavaScript globals such as process, module/native loaders, timers, network APIs, and Bun APIs;
- the synthetic `globalThis.result` namespace.

The child is spawned without an explicit `cwd` or `env` restriction (`src/seon/execution/host.cljs:509-533`); the subprocess owner only sets those options when callers provide them (`src/seon/subprocess.cljs:134-158`). It therefore inherits its process environment and working directory.

The exact audit-time artifact inventory in `out/execution/program-inventory.edn:1` was:

- 1,275 first-party public functions;
- 1,249 first-party private functions;
- 5,703 dependency terminals;
- 8,227 total functions across 367 namespaces.

The inventory is derived from Shadow’s selected analyzer closure (`script/seon/dev/program_artifact.clj:109-126,311-315`) and structurally classified (`script/seon/dev/program_inventory.clj:35-74`). The runtime global resolver does not enforce the public/private/dependency distinction.

Classification:

- canonical home aliases and corpus functions: intended agent surface;
- direct `seon.db`/`seon.schema` globals: current core capability surface, not target containment;
- private first-party and dependency terminals: accidental;
- raw JavaScript/Bun globals: critical accidental capability escape under R26/R33.

### 6. Other environments

- `seon.repl/!compile-state` retains a pod-global development `cljs.js` state initialized through the same bootstrap (`src/seon/repl.cljs:78-114`). It has pod-process rather than per-agent-child blast radius.
- The B2 experimental SCI child at `tmp/sci-probe/exec-src/seon/execution/sci_runtime.cljs:104-141` binds `js/globalThis :allow :all`, every database-indexed function that resolves through the compiled artifact, authored-source loading, and raw `result` vars. No operator flavor selects it (`shadow-cljs.edn:403-420`).
- The diffusion worker is a separate `cljs.js` generated-code environment with a V8 timeout, not SCI (`src/seon/diffusion/worker/eval.cljs:111-167,221-313`). It is not the ordinary agent corpus environment.

## Exact explicit JVM registry inventory

Everything below is registered as a host-authored read-only SCI var in `src/seon/host/context.clj:558-928`.

### Intended agent-facing toolkit and capabilities

| Namespace | Exact bindings | Classification |
|---|---|---|
| `seon.db` | `current-agent-id`, `db`, `as-of`, `since`, `history`, `cas-assert`, `transact!`, `query`, `query-with-evidence`, `read-attribute-dependencies`, `pull`, `pull-many`, `entity`, `installed-schema`, `execute-many`, `index-page`, `current-tx-context` | Guarded core capability. The 16 leaf functions come from `src/seon/db.cljc:203-219`; `current-tx-context` is added at `context.clj:598-605`. `transact!` is intentional, not the break-8 defect. |
| `seon.agent.message` | `user`, `agent`, `message-transaction-for` | Intended capability (`src/seon/agent/message.cljc:28-39`; `context.clj:627-638`). |
| `seon.agent.lifecycle` | `wait`, `complete`, `pause`, `resume`, `terminate` | Intended capability (`src/seon/agent/lifecycle.cljc:36-47`). |
| `seon.agent.fs` | `configure!`, `grants`, `read-file`, `write-file`, `edit-file`, `list-dir`, `stat`, `file-exists?`, `home-dir`, `walk-dir`, `view`, `replace!`, `insert!` | Capability family; `configure!` is a management binding and conditional finding. |
| `seon.agent.shell` | `grants`, `run`, `py-run`, `run-bg!`, `list-jobs`, `job-status`, `job-output`, `job-stop!` | Intended external capability. |
| `seon.agent.web` | `grants`, `fetch`, `search` | Intended external capability. |
| `my.blob` | `put!`, `get`, `concat!`, `text`, `stat` | Intended toolkit capability. |
| `seon.schema` | `validate`, `register!`, `schema-definition` | Intended schema capability. |
| `seon.embed` | `enabled?`, `search-pull` | Disabled stubs; intended current surface. |
| `my.plan` | `active!`, `blocked!`, `document`, `done!`, `drop!`, `list-open`, `move!`, `needs!`, `next`, `plan!`, `reconcile!`, `reopen!`, `status`, `step!`, `tree` | Intended toolkit. |
| `my.kb` | `recall`, `remember` | Intended toolkit. |
| `my.kb.shared` | `instructions` | Intended toolkit. |
| `my.skills` | `list`, `load`, `unload` | Intended toolkit. |

### Directly reachable support bindings

These exist to support toolkit or runtime code, but the registry has no “support-only” visibility class, so an agent may require and call them directly:

- `seon.ai.provider/provider-locality`
- `seon.ai.provider/frontier-provider?`
- `seon.agent.home/home-ns`
- `seon.db.id/allocate!`
- `seon.db.id/candidate-manifest`
- `seon.db.id/generator-policy-query`
- `seon.db.protocol/query-operation`
- `seon.db.protocol/pull-operation`
- `seon.db.protocol/success?`
- `seon.db.protocol/result`
- `seon.ai.tokens/estimate`
- `seon.ai.tokens/estimate-chars`
- `seon.ai.tokens/clip-str`
- `seon.content-hash/sha-256`
- `seon.time/iso-string`
- `seon.repl.parse.repair/rank-candidates`
- `seon.repl.parse/read-forms`
- `seon.render.canvas/field-signal`

Source: `src/seon/host/context.clj:573-579,639-645,732-827,914-927`.

Most are harmless pure support. Their direct reachability is nevertheless unintentional surface area because existence in the implementation registry currently equals agent authorization.

### Verified accidental bindings

`seon.agent.ctx/read-file-text` and `list-skill-files` accept arbitrary paths and use `io/file`, `slurp`, and directory listing directly (`src/seon/host/context.clj:828-874`). They do not use `seon.agent.fs`’s root, read-only, or lock checks.

Blast radius: any process-readable UTF-8 file and skill-shaped directory names visible to the cluster JVM.

### Conditional management exposure

`seon.agent.fs/configure!` replaces allowed roots and read-only state unless `SEON_FS_LOCK` is set (`src/seon/agent/fs/leaf.clj:117-137`).

The normal operator sets:

- `SEON_FS_ROOT=<repository root>`
- `SEON_FS_READ_ONLY=1`
- `SEON_FS_LOCK=1`

at `script/seon/dev/config.clj:578-580`, so the binding is inert in normal operation. An unlocked custom host allows an agent to widen the root and clear read-only state.

Classification: verified dangerous-by-configuration management binding; not presently exploitable through the normal operator.

### Dynamic `my.*` base

`load-portable-slice!` discovers every `.clj`, `.cljs`, or `.cljc` file under `src/my`, reads every top-level definition, and selects it using the `pure-block?` source regex (`src/seon/host/context.clj:930-999,1094-1190`).

The current recorded ledger is:

- 11 files;
- 273 definitions;
- 162 selected and loaded;
- 111 excluded;
- zero failed.

Source: `docs/prds/sci-execution-runtime/roadmap.md:575-594`.

This includes private helpers. There is no checked-in exact binding manifest; the full names exist only after evaluation. Public computed examples include:

- `my.canvas/{button,form,input,select,toggle}`
- `my.data/{group-sum,max-by,sum-by}`
- `my.ui/{badge,bullets,kv-table,progress,section,status-line,table}`

Source: `test/seon/host_surface_writer_test.clj:169-219`.

The selection rule excludes only textual evidence such as `^:async`, `await`, JS syntax, direct `db/*`, or `blob/*` references (`src/seon/host/context.clj:975-980`). An aliased or indirect effect can pass. The exact individual accidental set is therefore **suspected**, while the unsound admission mechanism is **verified**.

## Eval-time ingress

### Verified source ingress

1. Model reply source enters `sci/eval-string*` at `src/seon/host/eval.clj:204-251`.
2. The host prepends an `(in-ns '...)` source prefix at `src/seon/host/eval.clj:395-451`.
3. Stored `:seon.fn/source` is replayed during restore at `src/seon/host/context.clj:1349-1387`.
4. Stored `:seon.ns/source` is returned by the load function at `src/seon/host/context.clj:521-556`.
5. Pinned authored namespaces are replayed in a disposable fork at `src/seon/host/context.clj:1477-1527`.
6. Bun corpus namespace source enters `cljs/eval-str` at `src/seon/eval.cljs:857-920`.
7. Bun home-require vectors are decoded and printed into an evaluated namespace form (`src/seon/agent/home.cljc:116-193`; `src/seon/eval.cljs:1742-1751`).
8. Bun namespace movement and augmentation generate source from previously parsed symbols (`src/seon/eval.cljs:3093-3160,4669-4742`).

Namespace interpolation currently uses `str` for already-parsed symbols at several JVM and Bun sites. Reader-symbol escaping was not proven exploitable; this is a suspected construction smell rather than a demonstrated injection.

### Verified value ingress

- Authored JVM invocation arguments remain values and enter through `(apply @function-var arguments)` inside `sci.ctx-store/with-ctx` (`src/seon/host/invoke.clj:63-88`). This is the correct pattern.
- Host functions and immutable values enter through `sci/new-var`/`sci/add-namespace!` (`src/seon/host/context.clj:461-517`).
- Bun result values enter by direct property assignment to `globalThis.result` plus analyzer metadata, not source printing (`src/seon/eval.cljs:1494-1552`).
- A pending Bun Promise is temporarily retained until settlement; the settled value then passes admission (`src/seon/eval.cljs:4308-4334,4482-4501`).
- Normal results pass `admit-retained-value`; opaque values become small rejection descriptors rather than being retained (`src/seon/render/value.cljc:310-328,362-408`).

### Late symbol-to-host lookup

On Bun, `lookup-value` turns any qualified symbol into a compiled runtime value by walking the namespace object (`src/seon/eval.cljs:472-501`). This is used by authored program invocation and other symbol-selected consumers.

Database-selected route, render, or prompt symbols can therefore become native calls outside SCI. The mechanism is verified. A concrete malicious route-row exploit was not attempted, so exploitability through every consumer remains suspected.

### Break #8 class

Break #8 was correctly diagnosed: canonical transaction data was `pr-str`-inserted into executable SCI source, so symbol values were analyzed as code (`docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:1248-1252`).

The fixed authored-invocation fixture moved canonical schema seeding outside SCI, but the same construction class remains in tests that create `(seon.db/transact! {:seon.db/tx-data ...})` source using `pr-str`:

- `test/seon/host_interrupt_writer_test.clj:132-143`
- `test/seon/host_cancel_writer_test.clj:171-203`
- `test/seon/host_graduate_writer_test.clj:125-159`
- `test/seon/host_instrument_writer_test.clj:268-299`
- `test/seon/host_hostile_battery_writer_test.clj:257-276`
- `test/seon/host_authored_invocation_writer_test.clj:103-115`
- `test/seon/host_registry_writer_test.clj:434-465`

These are test-only today, but future symbol-valued fixture data recreates the exact failure.

No ordinary production path was found that inserts arbitrary transaction data into evaluated source. Production code/data separation is substantially better: authored arguments and result values enter as values.

## R32 result-symbol discipline

R32 is not implemented.

### Bun path

Bun binds admitted successful values at `globalThis.result.<munged-id>` and adds a matching analyzer definition (`src/seon/eval.cljs:1427-1552`). Runtime and analyzer entries are pruned together.

The supervisor tracks recent eval IDs in the process-local `!host` state and rejects a known cross-tier reference (`src/seon/execution/host.cljs:319-390,820-851,977-1026`).

This is internally coherent but lacks:

- database lifecycle facts;
- run-holding process `(pid,start-instant)` identity;
- durable tier ownership;
- transactional removal on process restart;
- support for opaque tier-local objects, which are replaced by descriptors.

### JVM path

The JVM retains admitted values in `::session/live-values` (`src/seon/host/sample.clj:177-208`).

It does not:

- create a `result` SCI namespace;
- bind `result/<id>`;
- record eval-id ownership as database facts;
- associate the value with a process identity;
- keep direct run-holding process values across batches.

The direct run-holding process constructs a fresh live-values atom for each batch (`src/seon/agent/driver/host.clj:145-162,272-290`). Values retained during that batch are unreachable immediately after it returns. This is a verified structural gap; a live later-batch `result/<id>` probe was not run.

Opaque JVM values are either rejected from retention or projected as text. Host eval recursively falls back to `pr-str` for non-Transit leaves (`src/seon/host/eval.clj:117-151`). That prevents raw objects crossing, but it is neither an R32 handle nor R41’s loud development failure.

### Required one mechanism

The result owner must be database data keyed by:

- eval ID;
- exact process identity `(pid,start-instant)`;
- execution tier;
- live generation.

Successful result receipt and ownership registration belong to one transaction boundary. Process reset/restart retracts that instance’s ownership rows. Lookup then has three outcomes:

- current local instance: use its live slot;
- another live instance: invoke/sample through the one typed wire family;
- missing or stale instance: return steering data to re-derive.

Both JVM and Bun must consume the same ownership facts until Bun agent execution is deleted.

## Guarded-door and eval-pool coverage

| Path | Pool | Guard | Verdict |
|---|---:|---:|---|
| UDS JVM agent eval | Yes | Yes | Covered |
| Direct cluster JVM eval | Yes | Yes | Covered after context restoration |
| JVM authored function/render call | Yes | Yes | Covered |
| Pinned materialization during authored call | Outer pool | Outer guard | Covered transitively |
| Preflight analysis during eval | Outer pool | Outer guard | Covered transitively |
| Shared JVM base/toolkit construction | No | No | Trusted startup SCI use; violates literal “every invocation” but not agent input |
| Stored agent-definition restoration | No | No | **Finding** |
| Graduation nursery reconstruction | No | No | **Finding** |
| Graduation inline `:test` call | No | No | **Finding** |
| Native graduated function body | Pool yes | SCI guard cannot observe body | **Critical finding** |
| Malli schema SCI | Caller-dependent | No Seon guard | **Finding** |
| Error suggestion `sci/eval-form` | Caller-dependent | No independent owner | Diagnostic bypass |
| Bun program load/eval | No JVM pool | No SCI guard | **Critical architecture gap** |
| B2 experimental SCI | No common pool | Ad hoc deadline only | Non-production bypass |
| Diffusion generated code | Worker/V8 timeout | No SCI guard | Separate mechanism |

Production UDS work enters the pool at `src/seon/host/invoke.clj:220-268`. Process holding the run work enters it at `src/seon/agent/driver/host.clj:272-290`. Both reach `guard/call!` through `execute-invocation!` (`src/seon/host/invoke.clj:106-194`).

The retained context holder is inert until `guard/reset!` installs an enforcing policy (`src/seon/host/guard.cljc:47-72,210-221`). Restoration calls raw SCI before that reset.

The guard policy contains `:plan`, but `execute-invocation!` chooses only `:agent-eval` or `:authored-render` (`src/seon/host/invoke.clj:45-61,106-118`). The plan budget is acquired but currently unselectable.

## Ranked findings and fixes

### F1 — Critical, verified: native JVM corpus execution

`compiled-var` evaluates recorded corpus source with host `clojure.core/eval` (`src/seon/host/graduate.clj:149-195`). Graduation compiles before its differential gate completes (`src/seon/host/graduate.clj:263-285`), and startup rebuild compiles every row whose stored tier/fingerprint says graduated without re-running that gate (`src/seon/host/graduate.clj:306-329`).

The resulting native function is installed into the SCI registry (`src/seon/host/graduate.clj:221-235,288-302`). The outer call still crosses `guard/call!`, but its body is native JVM code. SCI interpreter-step checks cannot observe its loops, allocations, reflection, I/O, or host Clojure core calls.

A direct agent self-promotion through transaction data was not live-proven. The absence of a startup trust recheck and native execution of any accepted graduated row are verified.

**Fix:** delete native corpus compilation as an execution mode. Graduation may cache parsed/analyzed SCI data keyed by source/projection generation, but agent source remains SCI and every test/body invocation crosses the same door.

### F2 — Critical, verified: maintained Bun agent code has native-global reach

The maintained child executes agent code as native JavaScript, exposes artifact namespaces globally, and inherits process environment/cwd.

**Fix:** complete R26/U9 deletion of Bun agent evaluation. Bun remains only a disposable JavaScript-package leaf invoked through typed capabilities. Namespace allowlists cannot contain native CLJS/JavaScript interop.

### F3 — Critical, verified: the compiled Bun artifact is an accidental binding set

At audit time, 8,227 compiled functions across 367 namespaces were present, including 1,249 private first-party functions and 5,703 dependency terminals. Runtime lookup does not enforce their intended role.

**Fix:** compiled-artifact inventory is an input to provenance, placement, and role classification, never an export list. The SCI builder binds only language core, admitted toolkit/capability wrappers, and corpus functions selected at the acquired basis.

### F4 — High, verified: stored corpus reconstruction bypasses pool and door

`restore-context-defs!` calls raw replay (`src/seon/host/context.clj:1326-1345,1371-1387`). UDS and run-holding process startup invoke it before readiness/pool admission (`src/seon/host.clj:106-134`; `src/seon/agent/driver/host.clj:127-143`).

Graduation reconstruction and inline tests have the same problem (`src/seon/host/graduate.clj:170-210`).

**Fix:** one invocation owner handles reconstruction, agent eval, authored calls, inline tests, predicates, and plans. Add a closed `:program-reconstruction` class and submit it through the bounded pool.

### F5 — High, verified: hidden Malli SCI bypasses the Seon door

Malli initializes and forks SCI internally, while `:termination-safe` is obsolete.

**Fix:** contract-predicate compilation and invocation must receive an already-built, corpus-aware Seon SCI context and use the same invocation owner. Malli must not privately own context creation for agent-derived schema code.

### F6 — High, verified: raw filesystem helpers bypass grants

`seon.agent.ctx/read-file-text` and `list-skill-files` are directly callable and ignore `seon.agent.fs` grants.

**Fix:** route them through the single filesystem capability. If skills require a narrower operation, derive an allowed corpus-root request through that same leaf rather than exposing raw paths.

### F7 — High, verified mechanism: regex determines base reachability

The portable-base classifier is textual, admits private helpers, and is not based on R33 call-graph purity or R43 provenance.

**Fix:** delete `pure-block?` into `plan-execution`, as already ordered at `docs/prds/sci-execution-runtime/unified-plan-2026-07-23.md:235-242`. A binding is admitted only when its transitive graph and capability edges are known.

### F8 — High, verified: R32 ownership/lifecycle is missing

JVM handles disappear between direct run-holding process batches; Bun ownership is process-local; opaque objects become descriptors; no database lifecycle facts exist.

**Fix:** implement P6’s one database-backed result-symbol lifecycle registry (`docs/prds/sci-execution-runtime/unified-plan-2026-07-23.md:244-255`).

### F9 — High regression risk, verified test-only: break-8 splicing remains

Several tests still use evaluated SCI source as a transport for ordinary transaction data.

**Fix:** one writer-side canonical fixture initialization function. Static enforcement should reject arbitrary `(pr-str value)` concatenation feeding an eval owner.

### F10 — Medium, verified: filesystem management var is agent-callable

`seon.agent.fs/configure!` is safe only because the normal operator locks it.

**Fix:** remove management/configuration from the agent registry. Capability grants are acquired host configuration/database facts, not agent mutation.

### F11 — Medium, verified: JVM class surface is wider than portable requirements

`Date` and `Long` expose tier-specific reflective methods.

**Fix:** move needed time/numeric operations behind narrow portable owners and remove these classes. Any retained class entry should use closed explicit member maps.

### F12 — Medium, verified: plan policy cannot be selected

A plan budget exists, but invocation classification never produces `:plan`.

**Fix:** derive a closed invocation role from the admitted function/call-graph manifest. Missing or unknown roles fail closed; no class silently inherits another budget.

### F13 — Medium, verified: silent wire stringification obscures containment faults

Non-Transit leaves become `pr-str` text without a tracked handle or loud fallback.

**Fix:** one total boundary encoder returns either schema-projected ordinary data, an R32 handle, or an explicit codec error governed by R41’s development/production dial.

### F14 — Medium, suspected exploitability: database symbols select broad Bun calls

Generic runtime lookup is used by route/render/prompt consumers. R43 “core” provenance does not prove that a symbol is valid for a specific callable role.

**Fix:** execution-plan manifests carry role-specific admission and schemas. A route handler, renderer, predicate, or capability is callable only for the role its graph derives; generic `lookup-value` is not an authorization mechanism.

## Computed rule that keeps the surface auditable

One builder should produce both the SCI context and a deterministic ordinary binding manifest:

```clojure
{:seon.host.context/basis-transaction ...
 :seon.host.context/process-identity {:pid ... :start-instant ...}
 :seon.host.context/tier :jvm
 :seon.host.context/artifact-digest ...
 :seon.host.context/bindings
 [{:seon.host.binding/symbol 'my.plan/next
   :seon.host.binding/origin :toolkit
   :seon.host.binding/role :agent-toolkit
   :seon.host.binding/effect :read
   :seon.host.binding/schema ...
   :seon.host.binding/source-provenance ...}
  {:seon.host.binding/symbol 'my.agent.example/f
   :seon.host.binding/origin :corpus
   :seon.host.binding/role :pure-function
   :seon.host.binding/effect :pure
   :seon.host.binding/source-provenance ...}]
 :seon.host.context/classes [...]
 :seon.host.context/surface-digest ...}
```

Membership is computed from:

- the pinned SCI default namespace/class manifest;
- exact artifact inventory;
- corpus rows at the acquired database value;
- R43 source-datom provenance;
- R33 transitive program-graph purity/capability edges;
- function schemas and callable roles;
- installed capability inventory;
- selected execution-plan coverage.

The decisive rule is:

> A symbol is reachable only when the acquired program graph derives a binding for it, its source-datom provenance is admitted, its callable role matches the invocation, and its capability edges are covered by the selected execution plan. Host existence, namespace prefix, database symbol shape, or compiled-artifact membership alone never grants reachability.

The same manifest must drive:

- base construction;
- per-agent fork restoration;
- load-function resolution;
- wrapper installation;
- class/member admission;
- instrumentation;
- execution planning;
- R32 result ownership;
- eval receipts.

At process start:

1. build and hash the manifest;
2. create the SCI base exclusively from it;
3. enumerate actual namespaces, vars, classes, aliases, and wrapper roles from the live context;
4. fail closed if enumeration differs;
5. record the surface digest, basis transaction, process identity, invocation class, and guard-policy keys with each invocation receipt.

Structural enforcement should allow:

- `sci/init`, `sci/fork`, `sci/new-var`, and `sci/add-namespace!` only in the environment builder;
- `sci/eval-*` or direct SCI function invocation only in the guarded invocation owner;
- no `clojure.core/eval` on corpus-derived forms;
- no `cljs/eval-str` for ordinary agent corpus execution;
- no host value inserted into executable source—values enter only as arguments, bindings, or handles;
- no support-only binding directly reachable from agent namespaces.

A context-generation diff then becomes a reviewable data diff rather than a side effect of source layout, dependency defaults, or whatever happens to be loaded.

## Direct raw SCI test/probe inventory

Direct SCI unit/probe use exists in:

- `bench/u1_guard_calibration.clj`
- `test/seon/host/guard_context_test.clj`
- `test/seon/host/guard_test.cljc`
- `test/seon/host/toolkit_bindings_test.clj`
- `test/seon/host_authored_invocation_writer_test.clj`
- `test/seon/host_cancel_writer_test.clj`
- `test/seon/host_error_sci_writer_test.clj`
- `test/seon/host_graduate_writer_test.clj`
- `test/seon/host_hostile_battery_writer_test.clj`
- `test/seon/host_instrument_writer_test.clj`
- `test/seon/host_interrupt_writer_test.clj`
- `test/seon/host_preflight_writer_test.clj`
- `test/seon/host_registry_writer_test.clj`
- `test/seon/host_shared_var_writer_test.clj`
- `test/seon/program_edge_test.cljc`
- `test/seon/render_portability_writer_test.clj`

Guard-specific tests intentionally construct local doors. Registry/instrumentation tests legitimately exercise lower-level owners but do not prove production door coverage. Tests that seed database fixtures through evaluated source should not be counted as containment proof.

## Verified versus suspected

### Verified

- one production JVM Seon `sci/init` plus retained/disposable forks;
- a second production SCI builder inside Malli;
- complete explicit JVM wrapper registry;
- 162 regex-selected portable `my.*` definitions, including private helpers;
- raw context filesystem helpers bypassing the fs grant;
- `configure!` management exposure, locked by the normal operator;
- native JVM graduation;
- unguarded restore, graduation reconstruction, and inline tests;
- maintained Bun agent execution is `cljs.js`, not SCI;
- Bun executes native JavaScript against inherited global/runtime state;
- exact current artifact inventory: 8,227 functions across 367 namespaces;
- JVM and Bun R32 lifecycle gaps;
- JVM direct run-holding process live-result loss;
- no JVM `result/<id>` SCI binding;
- unselectable plan guard policy;
- test-only break-8 splicing class;
- silent host wire stringification.

### Suspected or not live-proven

- direct agent promotion of an arbitrary function to a trusted graduated row;
- a concrete secrets/module/filesystem exploit executed in a Bun child;
- a concrete route-row exploit selecting a damaging host function;
- an exploitable namespace-symbol escape through generated `(ns ...)`/`(in-ns ...)` source;
- the exact individual private `my.*` bindings that are effectful despite passing `pure-block?`;
- future agent predicate execution inside Malli’s hidden context;
- the exact environment variables or secrets inherited by the Bun child.

## Final conclusion

The break-8 symbol was not evidence that `seon.db/transact!` itself is accidental; that function is an intentional database capability. Break #8 exposed a broader invariant failure: source and values do not yet have one enforced ingress owner.

The containment surface becomes auditable only when:

- Bun agent execution and native JVM graduation are removed;
- every remaining SCI evaluation, including reconstruction and predicates, crosses one invocation owner;
- one computed manifest is both the construction input and runtime audit record;
- support helpers and classes require explicit roles;
- and R32 handles are real database lifecycle facts rather than process-local conventions.

No files were changed.
