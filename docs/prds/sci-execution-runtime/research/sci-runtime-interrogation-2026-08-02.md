---
type: research
status: active
tags: [prd, sci, cluster, database, runtime]
---

# SCI and cluster runtime interrogation — 2026-08-02

## Status and verdict so far

This is the first evidence checkpoint. Sections C and D still need the isolated
two-cluster escape probes; the source and live evidence below is already enough
to reject SCI namespace acquisition as a runtime-isolation boundary.

The current runtime has one live mutable SCI context per cluster, but it also
installs every loaded first-party namespace as raw compiled JVM Vars. An agent
can reach private Vars and dereference process-global atoms through that
surface. On the live `default` cluster the door reached the root store holder,
the flock registry, the cluster graph and routing atom, the installed work
launcher, and the raw `clojure.lang.Var` for
`seon.cluster.store/transact!`. Direct arbitrary Java class interop remained
unresolved in the same context. **Confidence: high** for those exact surfaces;
mutation and cross-cluster consequences remain under isolated probe.

## Dependency ledger and method

- SCI revision: root gitlink and checkout both select
  `6de15683b7520cc973bc9c136aec7ad3f9b3788c`. Context construction and fork:
  `reference-code/sci/src/sci/core.cljc:312-331`; symbol value resolution:
  `reference-code/sci/src/sci/impl/analyzer.cljc:2296-2325`; SCI dynamic
  bindings: `reference-code/sci/src/sci/core.cljc:138-157` and
  `reference-code/sci/src/sci/impl/vars.cljc:115-184`.
- Datahike revision: root gitlink and checkout both select
  `256b714d97a0e8f952b01a47c693eff2976ccee7`. Current Seon branch connections
  are process-local Datahike connection values; reads and writes are local
  synchronous calls.
- Core.async revision: root gitlink and checkout both select
  `dc35f3e0d7bc2eef502e77982f48641f025c8051`. Current per-agent graph and work
  submission owners are `src/seon/cluster/agent.clj:240-264` and
  `src/seon/flow.clj:479-499`.
- First-party owners: context acquisition/evaluation
  `src/seon/sci/eval.clj:156-228,908-1234,1393-1593`; cluster tower and
  process-global owners `src/seon/cluster.clj:158-271,1309-1406`; evaluation
  submission `src/seon/cluster/loop.cljc:546-562,1375-1408`.
- Live target: `default`, PID 4717, prepl `127.0.0.1:63956`, observed through
  `mcp__seon__eval_clj`. All default-cluster probes here were read-only.
  Anything that can mutate will run under a lane-owned operator root below
  `tmp/`.
- Tree caveat: 29 first-party files were already modified by another lane.
  Claims cite and probe the live loaded code as well as the current file tree;
  no production path is owned or edited by this audit.

The shortest falsifier is an actual door evaluation using `#'` against a
private first-party Var. That distinguishes what an agent reaches from what a
host JVM REPL reaches.

## A. Context lifetime and `fork`

### Construction and storage

`stack-tower!` creates the cluster context after schema accretion, recovery,
config application, cluster/root-agent facts, and before work-launcher install
or agent arming. It stores the returned map directly on the cluster instance at
`:seon.sci.eval/ctx` (`src/seon/cluster.clj:1337-1383`). `cluster-ctx` creates
one `build-base-ctx`, calls `acquire!`, adds the basis-aware schema projection
atom, restores the session image, and returns that same context
(`src/seon/sci/eval.clj:1211-1234`). **Confidence: high.**

The live JVM form was:

```clojure
(let [instances @@(ns-resolve 'seon.cluster (symbol "running-instances"))
      instance (get instances "default")
      cluster (:seon.cluster.loop/cluster instance)]
  {:cluster-names (sort (keys instances))
   :ctx-ident (System/identityHashCode (:seon.sci.eval/ctx instance))
   :ctx-env-ident (System/identityHashCode (:env (:seon.sci.eval/ctx instance)))
   :same-connection?
   (identical? (:seon.boot/cluster-connection instance)
               (:seon.store/branch-connection cluster))})
```

It returned one cluster, context identity `1277044602`, env-atom identity
`1419615542`, and `:same-connection? true`. This is host-JVM observation of
the stored instance, not itself an agent-door reachability claim.

### What `acquire!` installs

`build-base-ctx` installs SCI's interrupt-aware `clojure.core` and
`clojure.string`, two schema functions, the complete public `clojure.test`
surface copied into SCI Vars, `my.run`, `my.message`, bootstrap REPL functions,
and only `Throwable`/`Error` class roots (`src/seon/sci/eval.clj:156-228`).

`acquire!` then:

1. derives one schema projection from the cluster database value;
2. finds core-provenanced namespace rows and intersects them with the JVM's
   loaded namespaces;
3. installs each such namespace with `(ns-interns host-namespace)`, i.e. raw
   compiled `clojure.lang.Var` objects;
4. installs database-derived `doc`; and
5. installs agent-authored namespace bindings, contracted functions, and tests
   from program rows in dependency order through SCI's interpreted path
   (`src/seon/sci/eval.clj:908-1145`).

Receipts and eval values are not acquisition inputs. Session-image values and
proven-pure source forms are installed afterward (`src/seon/sci/eval.clj:
1142-1234`). **Confidence: high.**

### What `sci/fork` copies, and whether it is an isolation boundary

The dependency implementation is exactly:

```clojure
(defn fork [ctx]
  (update ctx :env (fn [env] (atom @env))))
```

(`reference-code/sci/src/sci/core.cljc:326-331`). This is a shallow copy of
the env atom's current value. Existing mutable Var objects remain shared, so
`bindRoot` on an existing SCI Var is visible through both contexts; only env
structure added after the fork is private. The current Seon runtime uses a
fork only for namespace-unmap evaluation and installs the exact namespace state
after the terminal transaction (`src/seon/sci/eval.clj:1451-1466` and
`:789-900`). It is not the per-agent or per-evaluation boundary. **Confidence:
high from source; a direct shared-Var identity/mutation probe is pending in the
isolated root.**

### Concurrency and interleaving risk

Each agent has its own two-proc graph (`src/seon/cluster/agent.clj:240-264`). A
turn submits its evaluation through the process work launcher
(`src/seon/cluster/loop.cljc:546-562`), whose bounded compute executor may run
several tasks concurrently (`src/seon/cluster.clj:158-182`;
`src/seon/flow.clj:401-430,479-499`). All agents in the cluster pass the same
ctx (`src/seon/cluster/loop.cljc:1382-1408`). Therefore evaluations are not
strictly serialized per cluster; only each individual agent's turn proc is
serial. The interrupt state and SCI dynamic bindings are thread-local, but env
and Var-root mutations are shared.

Probable failure modes are lost/overwritten definitions of the same symbol,
one evaluation observing another's partial namespace/Var mutations, and an
`ns-unmap` fork installing stale exact namespace state over concurrent changes.
Those consequences are source-grounded but not all live-falsified yet.
**Confidence: high that concurrency exists; medium on the exact race outcomes
until the isolated interleaving probe.**

## B. Reachability — first live census

Every form below ran through the live SCI door, not the host JVM REPL.

### Private Vars and process-global owners: reachable

Form:

```clojure
{:root-store-holder-keys
 (sort (keys @@#'seon.cluster/root-store-holder))
 :held-flock-paths
 (sort (keys @@#'seon.cluster.store/held-flocks))}
```

Result:

```clojure
{:root-store-holder-keys ("/Users/sean/src/seon/data/clusters/store")
 :held-flock-paths ("/Users/sean/src/seon/data/clusters/store.lock")}
```

Thus private Var quoting, double dereference of private atom Vars, the
process-root store holder, and the store flock registry are reachable. The
owners are `src/seon/cluster.clj:188-271` and
`src/seon/cluster/store.clj:198-246`. **Confidence: high.**

### Cluster flow graph, routing, and process work launcher: reachable

Form:

```clojure
(let [instance (get @@#'seon.cluster/running-instances "default")
      graph (:seon.flow/graph instance)
      routing (:seon.cluster.agent/routing instance)]
  {:graph? (some? graph)
   :graph-class (str (class graph))
   :routing-class (str (class routing))
   :armed-agent-ids
   (sort (keys (:seon.cluster.agent/armed @routing)))
   :launcher-keys
   (sort (keys @@#'seon.flow/installed-work-launcher))})
```

Result: `:graph? true`, graph class
`clojure.core.async.flow.impl$create_flow$reify__9813`, routing class
`clojure.lang.Atom`, armed id `("root")`, and launcher keys including its
graph, active-work atom, and compute executor. **Confidence: high.**

### Compiled first-party Var objects: reachable

Form:

```clojure
{:var-object (str #'seon.cluster.store/transact!)
 :var-class (str (class #'seon.cluster.store/transact!))
 :deref-class (str (class @#'seon.cluster.store/transact!))}
```

Result:

```clojure
{:var-object "#'seon.cluster.store/transact!"
 :var-class "class clojure.lang.Var"
 :deref-class "class clojure.lang.AFunction$1"}
```

This proves access to the actual host Var object and its current function root.
Whether SCI's `alter-var-root`, JVM Var methods, or another compiled helper can
mutate that root is pending an isolated destructive probe. **Confidence: high
for reachability; open for mutation.**

### Direct arbitrary Java interop: unresolved in this context

The exact forms `(Thread/currentThread)`,
`(System/getProperty "java.version")`, and `(java.io.File. ".")` each returned
a flat `:seon.sci.eval/evaluation-failed` analysis error: respectively unable
to resolve `Thread/currentThread`, unable to resolve `System/getProperty`, and
unable to resolve classname `java.io.File`. `build-base-ctx` supplies only
`Throwable` and `Error` classes (`src/seon/sci/eval.clj:205-211`). **Confidence:
high for those three classes and forms; this is not yet a proof that no
reachable compiled host function can perform equivalent interop.**

## C. Custody under escape

Pending isolated two-cluster probes. Source establishes that `evaluate` wraps
reader, evaluation, admission/realization, and failure conversion in
`(binding [seon.db/*conn* ...])` (`src/seon/sci/eval.clj:1393-1593`). This
proves the intended lexical interval, not propagation into agent-created
threads or closures.

## D. The two runtimes

Pending the binding/closure probes. The source boundary already has one known
asymmetry: raw JVM Vars work in call position because a host Var is `IFn`, but
SCI's analyzer dereferences only SCI Vars in value position and otherwise
returns the raw object (`reference-code/sci/src/sci/impl/analyzer.cljc:
2296-2325`). That is the filed
`docs/seon/issues/host-bound-first-party-vars-break-in-value-position.md`.

## Recommendations — provisional

### Cluster custody

The simplest candidate is to make the cluster connection an explicit field of
the one system-owned evaluation request, then establish `seon.db/*conn*` only
inside `evaluate` on the actual compute worker, as current commit `643719904`
does. Every database entry should fail closed when that binding is absent.
This is simple only if no API accepts or exposes a connection value to agent
code. Escape cases still decide whether the recommendation is sufficient.

### Mutation and cross-cluster containment

Callability cannot serve as containment under ruling #20. The likely durable
constraint is instead: agent code may call every ordinary function, but no
first-party function may expose process-owned objects or perform a database or
runtime mutation except through the one guarded effect/persistence boundary,
which validates the ambient cluster identity and accepts only ordinary data.
Raw host Vars, contexts, connections, graphs, atoms, executors, stores, and
locks cannot be SCI-admissible return values. Cost: compiled host bindings that
currently expose internals must be replaced by interpreted program rows or
ordinary-data wrappers. Capability given up: direct REPL manipulation of core
runtime internals from agent code. Whether this is sufficient depends on the
isolated root proving all current escape routes.
