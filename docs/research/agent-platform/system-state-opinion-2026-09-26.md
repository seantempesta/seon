---
type: research
status: analysis only; no source changed; probes read-only on default
created: 2026-09-26
source-pin: HEAD a5cb41426 (working tree; src/seon/schema.clj carried an unrelated uncommitted edit, so its line numbers may drift by a few)
default-probed: pid 94554, started 2026-09-26T00:40:37Z, loaded identity 6ab714b1-dabb-5427-85eb-60ceddec3173
scope: every place in src/, script/ and resources/seon/operator/ that holds mutable state across calls
lane: research-system-state-opinion (Opus); written before reading the Astra atom audit, compared in §10
---

# Where Seon's state should live: an opinion

Seon keeps durable and program state in Datahike and derived state in memo tables keyed by immutable values. Live host
objects (Flow graphs, channels, executors, threads, SCI contexts, connections) sit in places spread across one process root
(`seon.operator.runtime`), a few per-cluster maps and a few per-agent holders. The principal Clojure approach: an atom is
Clojure's reference type for an identity whose value changes over time and is read from other threads. Use one per scope
whose membership changes at run time, keyed by stable ids, and hold values everywhere else.

**Verdict.** Durable state is almost never hiding in atoms. One cache holds a durable decision: the refusal-recording
memo (`eval.clj:3249`) decides whether a fault was already recorded, which is an idempotence decision that belongs to the
fault writer. The one missing fact is the P3b admission decision. The real problem is narrower. The *agent lifecycle* concern lives in six
places with four separate claim protocols, and two of those places have the same scope and lifetime. None of these places
is validated or rendered as one value. I recommend architecture **C, arranged as a hierarchy**: facts for intent,
memoized functions for derived values, and exactly one roster atom per scope with dynamic membership (process → cluster
execution), each passed down explicitly and reachable from the process root only for status. The routing atom is that
roster for a cluster execution. It should absorb the context-state atom, not be deleted. An atom-free agent lifecycle
(P3b option (c)) is the wrong target.

## 1. Method and evidence

- Inventory: `rg -n "\(atom[ )]|\(volatile!|\(ref |defonce|\^:dynamic|memoize|cache/" src script` (566 hit lines), plus
  `AtomicLong|AtomicBoolean|AtomicReference|LongAdder|ConcurrentHashMap`, `Executors/|Thread/ofVirtual|\(Thread\.`,
  `d/listen`, `ThreadLocal`, and `resources/seon/operator/runtime.clj`, the process root, which sits outside the indexed
  roots. An awk pass attached each hit to its enclosing top-level form (204 constructor sites). I classified every site
  by hand, and read each long-lived site's constructor and readers.
- Live probes on default, JVM mode, one form each, read-only. They walked `seon.operator.runtime/running-instances`
  (identity-bounded, depth 9) and counted cache entries, Datahike listeners, threads and the routing value. Each probe
  took 3–6 ms. One helper def in `user` was unmapped in the next form. No default Var was redefined, and nothing was
  stopped or transacted.
- **Byte sizes are unavailable.** `clj-memory-meter` is on the classpath but cannot attach
  (`Can not attach to current VM`: the JVM runs without `-Djdk.attach.allowAttachSelf`). The sizes below are entry
  counts. Heap in use was 2471 MB at probe time, which is not attributable to any single place.
- **Default is behind HEAD.** It booted 34 s before R1 (`5d8e803f7`, 00:41:11Z) deleted the fault channel, committer
  graph and error fan-out. Its live instance still holds them, and they are consistent with its loaded `seon.flow`. The
  probe describes default's loaded program, not HEAD.

## 2. What default actually holds (measured)

One cluster (`"default"`) with one armed agent (`"root"`, entity 33229). Walking the instance value found:

| Kind | Count | Where (paths into the instance) |
|---|---|---|
| atoms (`clojure.lang.Atom`) | 12 | routing; context-state; the root entry's handle: context-state, routing, turn-backstop-state, projection-state (env), connection wrapper; view: registration, latest-packages, interest; cluster handle projection-state |
| `AtomicReference` | 1 | root entry `:seon.agent/turn-thread` |
| core.async channels | 16 | root mailbox, schedule channel, completion ×2, `flow/started` ×2, view render/runtime-eval/pages/completion, retired fault channel and fan-out report/error/monitor channels |
| executors | 5 (+2 wrappers) | cluster graph, root agent graph, retired fan-out graph, root compute pair; `projection-executor` wrappers |
| SCI contexts | 2 reachable roots | the view's ctx and the root entry's handle (base plus agent fork) |
| Datahike connections | 2 | store connection object; the root branch connection |
| Datahike listeners on the branch connection | 3 | `::sci.eval/program-identity` (`eval.clj:3088`), `:seon.agent/route` (`wake.clj:499`), one random-uuid key (the schedule proc, `schedule.clj:795`) |

**The routing atom** (`agent.clj:595`, created at `cluster.clj:3484`) held these keys:
`:seon.agent/armed {"root" entry}`, `:seon.agent/channels {33229 mailbox}`, `:seon.flow/graph` (the cluster graph, put
there by a join), `::arming {}` and `:seon.agent/fault-channel`. The last key is the retired pre-R1 fault channel, open
with 0 queued. No HEAD source writes that key. At HEAD only four tests do (`agent_call_edges_test.clj:69`,
`evaluate_source_test.clj:48`, `run4_install_test.clj:57`, `concurrency_test.clj:184`). The armed entry holds 10 keys:
`:seon.agent/eid`, `:seon.agent/id`, `:seon.agent/turn-backstop-state` (atom), mailbox, `:seon.flow/executor`,
`:seon.flow/graph`, `:seon.flow/started`, schedule channel, completion, and `:seon.turn.loop/cluster`, a 37-key handle
carrying 5 more atoms, the connection, both SCI contexts, the work launcher and the executor.

**The same concern, measured across places.** The lifecycle of "which agents run here, with which graph, mailbox and
context" is held in:

1. the routing atom: armed entries, mailbox-by-eid, cluster graph, `::arming` and `::stopping` claims;
2. the **context-state atom**, which is *the same atom* in the cluster handle and in the root entry's handle (identity
   probe `true`). It holds 1 entry, keyed `[:cluster-default "root"]`, with 16 keys (connection, db, base-ctx, ctx,
   projection, environment, routing, ownership flags). It has its own claim protocol, `acquire-once!` (`agent.clj:892`);
3. the per-agent `turn-backstop-state` atom and `turn-thread` `AtomicReference` (`agent.clj:1176`, `:1195`);
4. the Datahike connection's `:listeners` atom, which carries `route!` and outlives no graph only because
   `disarm-agents!` remembers to call `wake/unlisten!` (`cluster.clj:3578ff`);
5. `running-instances`, whose instance carries the same routing a third time (as `:seon.agent/routing`, again under
   `:seon.render.web/view`, and in the handle, all identical) and has its own stopping claim under `locking`
   (`boot.clj:478-483`);
6. `sci.eval/loaded-identity`, which gates every detecting execution.

Four claim protocols serve that concern: routing `::arming` and `::stopping` delays (`agent.clj:1119-1127`,
`:1343-1350`), `acquire-once!` on context-state, and `locking running-instances` in boot. Because routing and
context-state are two places with two ownership rules (a four-way `or` picks the routing at `agent.clj:1067-1071`, and a
separate isolate rule picks the contexts at `:996`), `arm-graph!` has to refuse "the routing is not the one the agent's
execution owns" (`agent.clj:1161-1166`). That refusal exists only because two places can disagree.

Other process-wide counts: `program-identity-cache` 256 entries (its bound is 256), `projection-cache` 24,
`value-projection-cache` 4, `base-context-cache` 4, `arity-base-cache` 4, `arity-commit-cache` 5, `reach-lineage` 10,
`reach-holders` 1, `refusal-recording-cache` 2, `packaged-populations` 1, `!authored-shapes` 2,
`launched-source-digests` 75, `profile/live` 2172 weak cells, `!fallback-counts` 1, and `held-flocks`,
`root-store-holder` and `running-instances` at 1 each. The JVM had 37 live threads (22 ForkJoin workers, 7
`async-mixed`, 6 prepl connections). The MCP server process holds 20 io-prepl sessions in its own `clj-sessions` atom
(`script/seon/dev/mcp.clj:29`).

Two program identities disagree on the running instance. The instance's `:seon.source/commit-id` is `6ab710a0…` (a boot
stamp), while `loaded-identity` is `6ab714b1…` (the current one). Both are places answering "which program is loaded".

## 3. Inventory

Categories: **(1)** durable fact → Datahike; **(2)** derived value → memo of an immutable value; **(3)** live host object
with no data form; **(4)** per-call accumulation → reduce/loop or a local volatile; **(5)** process configuration.
**A** marks ambient passing: a dynamic Var or ThreadLocal carrying the world, whatever category it carries. **N** marks
something that is not state (load-order indirection, generators, constants).

### 3.1 Process scope (top-level, lives for the JVM)

| Site | Cat | One right owner | Verdict |
|---|---|---|---|
| `seon.operator.runtime/running-instances` (`resources/seon/operator/runtime.clj:11`) | 3 | the process root | **keep**: the one process roster. Its stopping claim should use the same swap-vals!-delay idiom, not `locking` (`boot.clj:409`, `:478`, `:591`) |
| `runtime/root-store-holder` (`:13`), `held-flocks` (`:15`), `root-executor-pair` (`:17`) | 3 | process root | keep |
| `sci.kernel/deadline-timer` (`kernel.clj:49`), `process-guard` (`:97`) | 3 | process root executors | move beside `root-executor-pair`: a process executor defined in a reloadable namespace |
| `web.jvm/client`, `ai/…HttpClient` (`web/jvm.clj:23`, `ai.clj:1294`) | 3 | the namespace (a thread-safe, effectively immutable client) | keep |
| `store/branch-open-monitor` (`store.clj:538`) | 3 | store owner | leave to the lock audit |
| `cluster/mcp-projection` ThreadLocal (`cluster.clj:244`) | 3 (host adapter) | the prepl adapter | keep: Clojure's prepl callback boundary has no request argument to carry it (Astra H06) |
| `sci.eval/loaded-identity` (`eval.clj:1366`) | 3/5 | process root | move into `seon.operator.runtime`. It is JVM identity like `running-instances`. Delete the instance's boot-time `:seon.source/commit-id` copy, or rename it boot identity |
| `cluster/launched-source-digests` (`cluster.clj:2336`), `source/launched-dependencies` (`source.clj:157`) | 5 | process root, or a fact on the `:seon.db.process` row if history is wanted | keep. These are explicit launch observations |
| `profile/live` weak set of LongAdders (`profile.clj:52`) | 3 | profile owner | keep. JDK accumulators are the right cell |
| `boot/uncaught-handler` (`boot.clj:383`) | 5 | process root | keep |
| `schema/!fallback-counts` (`schema.clj:1079`) | 2 (diagnostic of an ambient fallback) | none | **delete** with the C5 sweep: the fallback it counts is itself the defect |
| `program/!authored-shapes` (`program.cljc:273`) | 2 | memo keyed by `declaration-stamp` | keep |
| 9 top-level LRUs: `db` projection, value-projection, arity-base, arity-commit (`db.clj:1270`, `:1276`, `:3783`, `:3788`); `sci.eval` program-identity, base-context (`eval.clj:3005`, `:3009`); `schema.edn/packaged-populations` (`edn.clj:379`); `test.runner` reach-holders, reach-lineage (`runner.clj:874`, `:879`) | 2 | the owning function's memory (core.cache) | **keep**, with one exception: `reach-holders` caches `(atom nil)` holders (`runner.clj:918`) and `reach-lineage` records a last-read basis, which puts mutable places inside a memo. Revise them to cache index values keyed by parents |
| `sci.eval/refusal-recording-cache` (`eval.clj:3249`, used by `record-refusals-once!`) | **1** (an effect memo: "these refusals were already recorded") | the fault/occurrence writer, deciding inside its transaction | **move** the decision to facts. Eviction or restart repeats writes, so the cache is a second idempotence authority (Astra C12) |
| `analyzer/roots-of` memoize (`analyzer.clj:227`), `markdown/vault-vocabulary` (`script/seon/dev/markdown.clj:517`) | 2 | memo | keep; `roots-of` is unbounded but small |
| `render.web/generator-server` (starts a real http-kit server inside a `delay`), `generator-mult` (`web.clj:115`, `:136`), `sci.eval/generator-ctx` (`eval.clj:183`) | 3 (generator scaffolding) | the generator, built per use | move out of production namespaces. A socket server in a schema generator is a live object with no owner or stop |
| constant delays: `env` declared-members/turn-members (`env.clj:180`, `:197`), `print/shipped-option-defaults` (`print.cljc:175`), `render/default-agent-profile` (`render.clj:96`), `schema/first-party-source-roots` (`schema.clj:1118`), storage/value schema delays (`schema.clj:53`, `:57`), `operator/shipped-default-decisions` (`script/seon/operator.clj:18`) | 5 | load-time constants | keep |
| 54 `(delay (requiring-resolve …))` Vars (45 `defonce`, 9 `def`) across 16 files | N | — | not state; each one is a namespace-cycle symptom (out of scope) |
| 19 dynamic Vars: `db/*conn*`, `*read-database*`, `*receipt*`, `*read-evidence-sink*` (`db.clj:147-162`); `schema/*candidate-forms-overlay*`, `*projection*`, `*projection-state*`, `*packaged-forms*`, `*registration-admission-source*`, `*contract-validation-fold-size*`, `*candidate-visit!*` (`schema.clj:1033-1037`, `:1723`, `:3719`); `effect/*request-context*`; `test/*member*`; `instrument/*compiling-contract*`; `cluster/*source-progress!*`, `*boot-progress!*`; `render/*walk-context*`; `sci.eval/*expected-identities*`; `dev.mcp/*debug*` | A (3: `*conn*`; 2: projections; 4: sinks and progress callbacks; 5: `*debug*`) | the call's arguments | convert through the queued C5 sweep; `*expected-identities*` stays until detection carries its expectation as a value |
| MCP process: `clj-sessions`, `reload-state`, `window-bases` (`mcp.clj:29-37`), handler atom (`:987`) | 3 | the MCP process's own root | keep. It is another process with its own roster |

### 3.2 Cluster/execution scope (built per instance, per test request, per isolated acquisition)

| Site | Cat | One right owner | Verdict |
|---|---|---|---|
| **routing** (`agent.clj:595`; built `cluster.clj:3484`, `test.clj:1810`, `agent.clj:1071`) | 3 | the cluster execution | **keep as the execution's one roster**; absorb context-state |
| **context-state** (`cluster.clj:3379`; isolate `agent.clj:996`; test `test.clj:1810`) | 3 | same scope and lifetime as routing | **merge into routing** (slice 1) |
| `render.web` view: `registration` (`cluster.clj:3507`) | 3 (open tabs' watched registrations) | render proc and web service | keep; list it under the roster for status |
| `render.web` view: `latest-packages`, `interest` (`cluster.clj:3502-3503`) | 2 (rederivable latest render and read-plan union) | the render proc's state | move into render proc state once the listener reads interest from there (slice 2) |
| env/projection-state atom (`env.clj:94`, replaced by `replace-environment!`) | 3/2 | the environment value, derived at acquisition | delete when each turn reacquires from the branch head (B2) |
| call-preparation state atom per cluster ctx (`call_preparation.clj:88`) | 2 | a memo of the projection (`schema/projection-cache-value` already exists, `schema.clj:419`) | convert |
| work launcher `active-work`, `io-submissions`, `accepting?` (`flow.clj:657-659`) | 3 | the launcher value | keep: in-flight futures read by `stop-work-launcher!` on another thread |
| projection compiled-cache holder (`schema.clj:406`) | 2 | memo whose lifetime is the projection value | keep |
| listener `:seon.agent/route` (`wake.clj:499`) | 3 | should be the cluster graph's armer proc lifecycle, as the schedule proc does (`schedule.clj:780-812`) | move (slice 2) |
| listener `::program-identity` (`eval.clj:3088`) | 2 | memo warm-up on commit | keep |
| `route!` `matchers`/`arming` volatiles (`wake.clj:497-498`) | 2 | memo of `(wake-matchers db)` by declaration content/revisions | convert to a memo read on each report's `:db-after`; this deletes the listener's rebuild ladder (Astra A095-A097) |

### 3.3 Agent scope (per armed agent, per SCI fork)

| Site | Cat | One right owner | Verdict |
|---|---|---|---|
| armed entry (an immutable map inside routing) | 3 | roster | keep |
| `::arming` / `::stopping` claim delays (`agent.clj:1119-1127`, `:1343-1350`) | 3 | roster | keep the idiom (Datahike's own `reserve-connection-opening!`, `reference-code/datahike/src/datahike/connections.cljc:37-80`, is the same swap-vals! reservation). Unify with `acquire-once!` into one claim helper |
| `turn-backstop-state` atom (`agent.clj:1176`), `:seon.agent/run-id` atom (`turn.clj:5470`) | 3 | one agent execution observation value | colocate with the turn thread; it must stay readable outside the proc while the proc is blocked |
| `turn-thread` `AtomicReference` (`agent.clj:1195`) | 3 | the same observation value | keep the monitor around publication and interrupt (`agent.clj:1241`); a bare swap could interrupt a reused proc thread |
| `CountedSlidingBuffer` dropped counters (`agent.clj:134`, `:580`) | 3 | the buffer | keep (an `AtomicLong` would also do) |
| SCI ctx mirrors `::kernel/installed-functions`, `::kernel/program-snapshot` (`eval.clj:329-330`, `:2925-2929`, `:2978-2980`, `:3148-3150`, `:3479-3481`) and `::base-bindings` (`:2923`, `:2975`) | 2 | derived from program rows by program identity | **delete**; the B2 target (`lane-b2-walk-flow-fork.md` §0) |
| `::result-objects` (`eval.clj:2976`) | 3 | the agent's private SCI world | keep (law: private objects stay in memory) |
| `::print-session` (`eval.clj:2982`, `:4054`) | 1/2 | initialised from facts (`session-print-options`) | verify whether agent changes write back; if not, derive it per turn |
| kernel arm map: `AtomicLong`/`AtomicBoolean` counters, `built-in-calls` atom, `outcome` volatile (`kernel.clj:226-233`) | 3/4 | the evaluation's arm (the timer thread writes `reached`) | keep |
| `flow/var-process` `requested` (`flow.clj:361`) | 3 (one-shot stop-request latch) | the proc launcher's fault boundary | keep: faults escape outside the transform and a blocked transform cannot take a state update (Astra A099) |
| `flow/submit!` `::status`, `::active?`, `::task` (`flow.clj:770-772`) | 3 | the submission | keep; could collapse to one `FutureTask` |
| render `shared-cache` scope atom (`render.clj:1737`) | 2 | memo by content digest and face | keep |
| effect counters (`eval.clj:4127`, `test.clj:209`) | 4 | the evaluation | keep as a local |

### 3.4 Per-call accumulation (category 4), grouped

37 local atoms, 42 local volatiles, 7 local `memoize`s, 2 clojure.test `ref`s (`runner.clj:385`, `:448`, required by
clojure.test's API) and 2 per-call listeners (`eval/drive.clj:61`, `agent.clj:762`, each unlistened in `finally`).
Largest clusters: `sci.eval/evaluate` (7 volatiles and 3 atoms, `eval.clj:4002-4127`), `render.web` page and debug
paths (11 capture atoms, `web.clj:457-2580`), `db/write-owned-values-error` (5 volatiles and a memoize,
`db.clj:3536-3644`), `test/member-result` (4 volatiles, `test.clj:1899-1928`). **Verdict: fine as a class.** A local
volatile in one thread is Clojure's sanctioned accumulator. Capture atoms that serve as callback sinks (`captured`,
`reads`, `calls`) are where C5 applies: return the evidence as values instead of collecting it through a bound sink.
`registry/collect-and-inventory!` (`registry.clj:468-469`) is a plain reduce.

## 4. Counts per category (long-lived places only; per-call listed in §3.4)

| Category | Sites | Of which should move or go |
|---|---|---|
| (1) durable fact | **1 held today** (refusal-recording memo); 1 missing (P3b admission); 1 unverified (`print-session`) | the fault writer decides recording; add admission as a writer-checked fact |
| (2) derived value | 21 (9 LRUs, 2 top-level memoizes, `!authored-shapes`, projection holder, call-prep state, 3 SCI-ctx mirror kinds, web interest and latest-packages, `route!` volatiles, render shared cache, reach holders) | 7 (3 mirror kinds, call-prep, interest, latest-packages, reach holders) |
| (3) live host object | 31 place kinds (4 process-root, 2 kernel, 2 HTTP clients, monitor, prepl ThreadLocal, loaded-identity, profile cells, 3 generator objects, routing, context-state, web registration, env-state, 3 launcher, 3 listeners, backstop and run-id, turn-thread, buffer counters, claims, result-objects, `var-process` latch, submission holders, kernel arm) | 8 (context-state merge, backstop/run-id/thread colocation, loaded-identity move, deadline timer move, 3 generator objects, route listener into proc lifecycle) |
| (4) per-call accumulation | 90 local sites | none as a class; C5 converts the sinks |
| (5) process configuration | 11 (8 constant delays, 2 launch observations, uncaught handler) | 0 |
| A ambient | 19 dynamic Vars | all but `*expected-identities*`, through the queued C5 sweep |
| N not state | 54 resolve delays, 4 generators, 3 predicate registrations, 2 constants | out of scope |

So at the level of long-lived places, the count is about 64 across a 90,130-line `src`/`script` tree. Everything durable
already lives in Datahike, and nearly every derived value already follows the "memoize of an immutable value" law. What
remains unowned is the live-object layer.

## 5. The one right owner, per category, and whether it can be centralized

- **(1) Durable facts → Datahike, via the writer.** Already central: one store, one writer. Two things remain: the
  admission fact, and moving the refusal-recording decision into the fault writer. The deciding test: *would a restart need it, and can it go stale?* "Agent X is admitted/retired" is a
  decision and must be a fact. "Agent X's graph is running in this JVM" is an observation of a live object. As a fact it
  goes stale the moment the JVM dies, which AGENTS forbids reading as health ("mirrors must be derived, drift-checked or
  explicitly historical"). Realization therefore stays out of the database; recovery already derives work from facts
  (the armer's derive-all pass, `agents-to-arm`, and boot's `recover-call`).
- **(2) Derived values → the function's own memo, keyed by commit id, cache-context revisions or content digest.** They
  should *not* be centralized into a system map. A cache is a function's memory, not system state. Putting it in the
  system value forces either passing the cache everywhere (bloating every signature) or reaching into a global (ambient).
  They should, however, be *enumerable*: one registration of each cache's name, bound and miss count so status can show
  them. AGENTS already asks to "count misses".
- **(3) Live host objects → their library's owner inside, one roster per dynamic scope outside.** A Flow graph owns its
  procs and channels (`create-flow` keeps its channels in its own atom, `reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:54`,
  `:168`: "the only connection to a running flow is via channels"). A Datahike connection owns its listeners
  (`reference-code/datahike/src/datahike/connector.cljc:102`), and Datahike keeps its own connection roster
  (`reference-code/datahike/src/datahike/connections.cljc:3`). SCI owns its `:env` atom (`reference-code/sci/src/sci/core.cljc:345-351`).
  Seon owns only the *roster*: which of these objects exist for which stable id. Centralize the rosters *hierarchically*
  (process → execution → entries). Do not flatten them into one atom.
- **(4) Per-call → the call.** Never centralize.
- **(5) Configuration → load-time constants, or cluster config facts.** Already fine.

## 6. The three architectures

### A. Everything durable or derivable to the database; live objects stay where they are

- **What changes:** almost nothing, because this is already the state of the code for (1) and (2). It adds the
  admission fact.
- **What it fails to fix:** the measured problem. The lifecycle concern stays spread over six places and four claim
  protocols, and the cross-atom refusal stays. Stale live shapes can survive in a place nothing validates: default's
  routing carries a key HEAD never writes.
- **Failure mode if pushed further**, by putting arming, mailboxes or claims into facts: every arm and stop becomes a
  transaction. The measured floor is about 20 ms per commit (open-items "commit floor"), so that is at least 20 ms per
  agent at boot, and a write per wake if delivery were a fact. Facts describing live objects are stale after any crash.
  Channels, executors and threads have no data form, so "everything in the DB" is impossible for them anyway.

### B. One central system value per JVM (Integrant/Component-style) holding every live object

- **Precedents.** Integrant's `init` returns the system map, `halt!` runs "in reverse dependency order", and both accept
  a subset of keys (github.com/weavejester/integrant). Component builds a `system-map`, starts in dependency order and
  stops in reverse, and advises that "no function should take the entire system as an argument" and "no component
  should be aware of the system which contains it" (github.com/stuartsierra/component).
- **Gains:** one observable value, a declared shutdown order, and one place status and the debug page render.
- **Costs and failure modes:**
  - *Static topology.* Both libraries key the system by configuration keys known at `init`. Seon's membership changes
    at run time: an agent arms on a wake, a branch hosts an agent, and every test member forks a world (`test.clj:1810`).
    Subset init/halt by config key is not arm/disarm by agent id, so we would rebuild the dynamic part ourselves.
  - *A flat global atom is ambient world.* One `swap!` domain for every cluster means unrelated arms contend and retry,
    and any code can reach in. Both violate "pass the world explicitly" and Component's own advice.
  - *Test isolation gets worse.* Tests already need their own execution value per request. A single process map makes
    "the test's world" a path in the production world.
  - *Blast radius.* A throwing swap function, or a swap that retries around a side effect, affects every cluster at once.
  - *Reload.* The root must live outside reloaded namespaces. `seon.operator.runtime` already does this deliberately,
    and a second root would violate "no second registry".

### C. Hybrid: A for data, B only for live objects, nothing else in atoms

- **Gains:** it keeps what is already right, (1) and (2), and gives the live layer one owner per scope. It matches
  Clojure's model: identities only where a changing set of live objects is read from foreign threads (the Datahike
  listener thread, web threads, MCP prepl threads), and values everywhere else.
- **Costs:** migrating the roughly 16 durable, derived and live places named in §4. Two cuts (the SCI mirrors and the ambient
  Vars) are already owned by B2 and C5. Wake delivery still needs one synchronous map read from the listener thread, so
  "no atom at all" is not achievable at the cluster scope without a Flow hop (see §8).
- **Failure modes:** rosters that drift from their contents (mitigated by one roster per scope and entries that are
  immutable maps); and a status render that reads a roster as health (mitigated by the existing law: a missing ping is
  unknown).

### Recommendation: C, hierarchical

Rule proposed for AGENTS/architecture (for root to word): *An atom (or other mutable reference) is admitted only as the
roster of a scope whose membership of live host objects changes at run time, keyed by stable ids (cluster name, branch,
agent id, entity id), one per scope. Its entries are immutable maps; claims are swap-vals! reservations of a delay. The
roster is passed down explicitly and reachable from the process root only for status and the debug page. Everything
else is a fact, a memo of an immutable value, Flow proc state, or a local within one call.* This is the "stated reason
no owner holds it" that the atoms law asks for (`AGENTS.md:29-30`, owner 2026-09-26). The reason, stated once for the
roster: its readers run on threads outside every graph (the Datahike listener, web and prepl threads), and Flow exposes
a running graph only through channels. A few other holders keep a reason of their own: a buffer's counter, an
evaluation's arm counters written by the timer thread, and a latch that must be readable while a proc is blocked.

Migration order (each a file-disjoint slice, each deleting what it replaces):

1. **Rule the question** (this document); P3b proceeds on option (b). Nothing in the hosted-agent design needs an
   atom-free lifecycle: a hosted branch execution gets its own roster exactly as an isolated acquisition or a test
   request does today (`agent.clj:1071`, `test.clj:1810`).
2. **Slice 1: one roster per execution.** Merge context-state into routing (§7).
3. **Slice 2: listener lifetime is proc lifetime.** Register `wake/route!` on the cluster graph armer proc's
   `::flow/resume` and unlisten on `::flow/stop`, the way the schedule proc already does (`schedule.clj:780-812`), and
   read render interest from that proc. This deletes `disarm-agents!`'s explicit `unlisten!` step and the "listener goes
   first" ordering it has to document.
4. **B2** deletes the SCI mirror atoms (installed-functions, program-snapshot, base-bindings) and the env replacement
   atom, per its own plan.
5. **C5** removes the ambient dynamic Vars, `!fallback-counts` and the registration-delta atom.
6. **Small moves:** `loaded-identity` and the deadline timer into `seon.operator.runtime`; the generator server, mult and
   ctx out of production namespaces; the turn backstop, run id and thread into one agent execution observation value
   (keeping the interrupt monitor); call-prep state onto `projection-cache-value`; `reach-holders` and `reach-lineage`
   cache values instead of atoms; the refusal-recording decision into the fault writer.
7. **Status renders the hierarchy:** process root → execution rosters → entries (ids, claims, mailbox buffer counts,
   graph ping) plus the cache registry (name, bound, entries, misses). This replaces oversight's and the web's ad hoc
   reads (routing is dereferenced 3× in `oversight.clj` and 8× in `render/web.clj`).

## 7. The smallest first slice: one roster per execution

**What exists.** Every execution handle carries two atoms of identical scope and lifetime: `:seon.agent/routing` (armed
graphs, mailboxes, claims) and `:seon.agent/context-state` (held contexts, keyed `[branch agent-id]`). They are chosen by
two different rules: a four-way `or` (`agent.clj:1067-1071`) and an isolate test (`:996`). They are released by two
different swaps: routing drops in `stop-graph!` (`:1409-1413`), context-state drops in `release-context!` (`:884-889`).

**The cut.** Held contexts become `:seon.agent/contexts` inside the routing value. `acquire-once!` reserves on that
sub-path with the same swap-vals!-delay reservation. The execution chooses one roster by one rule.

**Deletions (estimated from source; the implementing lane must price them at the REPL):**

| Deleted | Lines |
|---|---|
| `arm-graph!`'s "routing is not the one the agent's execution owns" refusal (`agent.clj:1161-1166`) | 6 |
| the separate `held-contexts` choice (`agent.clj:994-996`) and its carry into the environment (`:1047`, `:1066`) | ~5 |
| the second release swap in `release-context!` (`agent.clj:884-889`), which folds into the roster's own drop | ~6 |
| context-state constructors (`cluster.clj:3379`, `test.clj:1810`) | 2 |
| the four-way routing `or` collapses with the contexts choice into one (`agent.clj:1067-1071`) | ~4 |
| schema members for `:seon.agent/context-state` (`seon.agent.edn:6`, `:14`, `:122`; `my.program.edn:7`; `seon.env.edn:29-31`; `seon.turn.loop.edn:257`) | ~8 |

**Additions:** `acquire-once!` addresses a sub-path (+3); `my.agent/branch` (`my/agent.clj:73-76`) and
`test_support/held-handle` (`test_support.clj:255-266`) read `:seon.agent/contexts` from the roster (±0); the
`:seon.agent/routing` schema declares the contexts member (+3). **Estimate: about +10 / −35 source and schema.** One place
and one ownership rule per execution disappear, and an error class goes with them.

**Proof owed by the lane:** REPL first on a branch execution. Acquire, arm, release, and show the roster value before and
after each step. Then run `agent-custody-test`, `release-context-test`, `acquisition-test`, `entrance-supplier-test` and
`custody-stability-test` through `bin/test-check`. Time acquisition and arm on the parent and on the change (hot path).

## 8. What the routing/wake case becomes

- **Admission** (whether the agent may run) becomes a fact the run writer checks (P3b's finding, `system-run-call`,
  `turn.clj:727-750`). Retirement is one transaction.
- **Realization** (graph, executor, mailbox, completion, turn thread, backstop) stays an immutable entry in the
  execution's one roster, keyed by agent id, with the mailbox also indexed by entity id.
- **`wake/route!`** keeps its one synchronous lookup `(get (:seon.agent/channels @roster) eid)`. That is the right tool:
  the callback runs on Datahike's listener thread, outside every graph, and must never park. Datahike calls it with the
  whole report after each transaction (`reference-code/datahike/src/datahike/writer.cljc:390-414`), and Flow exposes a
  running graph only through channels (`flow/impl.clj:168`). Moving the eid→mailbox map into proc state would force a
  channel hop that carries recipient sets, which are not payload-free. A sliding buffer would then drop wakes, and
  making that safe needs either a union buffer or a since-basis re-derivation. That is more machinery for no stronger
  guarantee. Its *registration* moves under the armer proc's lifecycle (slice 2), so the listener cannot outlive the
  graph that consumes its armer wakes.
- **A branch-hosted agent** (P3b option (b)) arms with the existing `arm!` into its execution's own roster, as isolated
  and test executions already do. The branch's `route!` listener is registered with that roster's channels function.
  P3b's open point stands: on a host with no armer consumer, `route!`'s unknown-recipient path offers to a channel nobody
  reads, which is a silent loss. Under the rule above, that path must either be a consumer (the host runs
  `armer-step` directly, as boot already does at `cluster.clj:3556-3562`) or refuse and record a named fault. It must
  never be an unread channel.
- **Stop order** (listener first, then agent graphs, then the cluster graph, `cluster.clj:3578ff`) becomes Flow's: the
  armer proc's stop transition unlistens, and the roster's entries are joined as today.

## 9. Out-of-scope findings (no issue filed: this lane may commit only this file; root to file)

1. **A live instance keeps the shape of the code that built it, and nothing checks it after adoption.** Default's
   running instance holds the retired fault channel, committer graph and error fan-out (the committer proc pings
   `:running`) because it booted 34 s before R1. The instance value is built once at boot. `require :reload` neither
   unmaps deleted Vars nor rebuilds the instance, so adopting R1 into this JVM would leave the retired graph running with
   no owner. This is a hypothesis to verify with the first adoption across R1; it is not yet observed. Suggested issue
   class: "adoption leaves live objects built by retired code running".
2. **Two answers to "which program is loaded"**: the instance's `:seon.source/commit-id` (`6ab710a0…`) versus
   `sci.eval/loaded-identity` (`6ab714b1…`).
3. **Agent-facing code reaches the live context roster through the environment.** `my.program/supplied-context`
   selects `:seon.agent/context-state` from the environment (`my/program.clj:426`), and `my.agent/branch` dereferences it
   to find its own mode (`my/agent.clj:73-76`). An agent's SCI world can therefore reach every held context of its
   execution, other agents' included. The mode is a value the acquisition decided, and could be carried on the agent's
   own context instead.
4. **Ugly output:** a reader error on a malformed MCP JVM form returned a 51 KB windowed error value, with the whole
   edamame container stack. It should be one refusal naming the offset.
5. **A schema generator starts a real http-kit server** inside a production `delay` (`render/web.clj:115`), which nothing
   stops.

## 10. Comparison with the Astra atom audit

Read after §1–§9 were written (`atom-audit-2026-09-26.md`, basis `76302cccd`). The corrections it forced are folded into
§3–§6 above, each marked with Astra's row id.

**Where the two agree.**
- Routing, context-state and the isolated registry colocate into one execution lifecycle value, which is justified
  "while callbacks and synchronous management cross proc boundaries" (Astra A080/A086/A089, my §7). Both reject an
  atom-free lifecycle and a flat process-wide system map.
- The SCI program and binding mirrors, call-preparation state, web interest, the wake matchers and the reach-holder
  atoms are derived mirrors to delete. The launch observations, `loaded-identity` (A053), the profile cells, buffer
  counters and kernel arm counters are legitimate holders.
- Facts are for decisions, not for proof of runtime installation or exit (Astra "why tempting moves are wrong" §1, my §5).

**What Astra found that I missed or got wrong.**
- **C12**: the refusal-recording cache is an effect memo, a second idempotence authority. It is my only category-(1)
  site, and I had wrongly counted it as a derived cache.
- **A099**: the `var-process` stop latch must stay outside proc state. I had proposed moving it.
- **H06**: the prepl ThreadLocal is a host adapter for a callback with no request argument, not ambient world.
- **H05/A090/A108**: the turn thread, backstop and run id form one observation value, and the monitor around interrupt
  must survive.
- **High-risk, in A100**: the work launcher's `drained` can fire while a cancelled task's body is still in a host call.
  That is the existing issue `cancelled-future-is-not-body-exit-evidence.md`. The execution-value merge must not treat
  empty maps as exit.
- **A091**: boot's local `latest` stage duplicates the stage published in `running-instances`.
- Astra's census is complete where mine is grouped: 130 allocations plus 38 host holders, each with its own verdict and
  bundle ranking (R1–R13).

**What this document adds.**
- The architecture question the owner asked (A, B or C, with precedents and failure modes, §6), and the rule wording
  that states the atoms law's "reason" once per scope.
- **Live measurement on default**, where Astra's probe was degraded. The measured lifecycle concern spans six places with
  four claim protocols (§2). Default's routing still holds a retired fault channel, and the instance and
  `loaded-identity` disagree on the loaded program. Byte sizes remain unavailable because the JVM does not allow attach.
- The routing/wake reading for P3b (§8): keep the listener-thread lookup, move its registration into the armer proc's
  lifecycle, and never leave an unknown recipient on an unread armer channel.
- Findings outside Astra's scope: agent-facing `my.*` code reaching the live context roster, and a schema generator
  starting an unowned http-kit server.

**Disagreements left for root.**
- Astra moves the web tab registration into render proc state (A083). I keep it as a roster entry, because web threads
  register and deregister synchronously. Root should rule after the one-render-path design settles page delivery.
- Astra prices R5 (graph/context lifecycle) at 50–120 net lines deleted across five files. My slice 1 is the first loadable part of it, at about −25
  net, and I would land it before the rest of R5.
