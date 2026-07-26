---
type: research
status: active
tags: [research, runtime]
---

# JVM package capability report

## Recommendation

Add one disposable, per-cluster **JVM package leaf host**, parallel to the Bun package leaf. Do not load cluster packages into the writer or run-holding process classpaths.

The the process retains the guarded SCI wrapper and a remote binding; the separate JVM leaf owns the package’s jars, Clojure namespaces, Java classes, static state, native libraries, and native calls. This preserves R16’s logical interface—`seon.packages.jvm.<pkg>` is the package leaf—while satisfying R26’s blast-radius rule.

```text
cluster JVM
  guarded corpus wrapper / remote stub
          │ existing typed invocation seam
          ▼
per-cluster JVM package leaf
  DynamicClassLoader + cluster deps + native call
```

The writer remains transactions/feed only. A run-holding process never executes third-party bytecode in its own process.

## What has already landed

The common package data model is substantially ready:

- [`seon.packages`](/Users/sean/src/seon/src/seon/packages.cljc:9) already models npm and JVM ledger rows, chooses Bun versus JVM by ecosystem attribute, emits byte-stable `package.json` and `deps.edn`, stamps corpus rows with `:seon.packages/package`, and plans install/remove transactions.
- Cluster creation already materializes native manifests at [`cluster.clj:75`](/Users/sean/src/seon/script/seon/dev/cluster.clj:75).
- Package-corpus provenance is a database join: program acquisition admits stamped rows and pulls their ledger ref at [`execution.cljs:343`](/Users/sean/src/seon/src/seon/execution.cljs:343).
- The planner already derives `seon.packages.js.* → :bun` and `seon.packages.jvm.* → :jvm`, treats package functions as native leaves, and permits a caller tier to satisfy them through remote bindings at [`plan.cljc:181`](/Users/sean/src/seon/src/seon/program/plan.cljc:181).
- The U2 registry already upgrades shared SCI vars in place and exposes registered wrappers to every live context at [`host/context.clj:457`](/Users/sean/src/seon/src/seon/host/context.clj:457).

What is not landed:

- There is no `jvm-wrapper-namespace?`.
- Install validation does not require npm rows to use `seon.packages.js.*` or deps rows to use `seon.packages.jvm.*`; current tests still use generic `seon.packages.csv`.
- Package acquisition explicitly accepts only the JS prefix at [`execution.cljs:774`](/Users/sean/src/seon/src/seon/execution.cljs:774).
- No JVM package host or package inventory producer exists.
- Tier inventories exist as planner inputs, but only fixtures currently construct them.
- The conversion wiki’s “no ingestion door yet” statement is stale; the archived issue records the landed provenance join and live JS proof. The enduring scar is that package attributes must be installed before queries name them.

## Process placement

Create an operator-owned `:seon.dev.process/jvm-packages` member per cluster.

It should:

- Have its own socket, heap, working directory, environment, and process identity.
- Be launched from the cluster’s effective package basis.
- Carry no writer/database session and no SCI agent contexts.
- Serve the existing bounded invocation envelope over the existing transport.
- Be disposable: deadline overrun, `System/exit`, native crash, poisoned classpath, or partial live-add means terminate and reconstruct it.
- Publish readiness only after dependency basis, wrapper export inventory, and generation agree.

The current operator already owns writer, cluster JVM, web-render, watcher, and pod processes in [`process.clj:26`](/Users/sean/src/seon/script/seon/dev/process.clj:26). The JVM package leaf should join that graph; process-owned ad hoc `ProcessBuilder` supervision would now be a second lifecycle mechanism.

A separate process is materially stronger than separate classloaders inside the run-holding process. Clojure namespaces, JVM system properties, native libraries, executor threads, and `DynamicClassLoader`’s static class cache remain process-global. Classloaders alone do not give honest cluster isolation.

## Dependency lifecycle

Use `clojure.repl.deps/add-libs`, not one-at-a-time `add-lib`, for a strictly additive delta. Use restart for every update or removal.

Clojure 1.12’s exact behavior is:

- `add-libs` requires `*repl*` to be true.
- It ignores requested direct libs already in the current basis.
- It resolves additions out of process through the Clojure CLI.
- It appends resolved paths with `DynamicClassLoader.addURL`.
- It merges added libs into the runtime basis and reloads data readers.
- Existing coordinates override requested coordinates during `resolve-added-libs`.
- Nothing unloads a jar or replaces an already-loaded class.

These mechanics are visible in vendored [`clojure.repl.deps`](/Users/sean/src/seon/reference-code/clojure/src/clj/clojure/repl/deps.clj:22) and [`tools.deps/resolve-added-libs`](/Users/sean/src/seon/reference-code/tools.deps/src/main/clojure/clojure/tools/deps.clj:719). Clojure’s official documentation likewise describes `add-lib`, `add-libs`, and `sync-deps` as Clojure 1.12 REPL facilities. [Official REPL reference](https://clojure.org/reference/repl_and_main)

Recommended transition:

1. Derive the complete candidate `deps.edn` from ledger facts.
2. Run `tools.deps/create-basis` against that candidate.
3. Compare it with the running basis.
4. Permit live addition only when the candidate is a strict superset and every existing selected coordinate is unchanged.
5. Call `add-libs` once with the new direct coordinates under `binding [*repl* true]`.
6. Reconcile wrappers and inventory.
7. On any ambiguity or failure, restart from the complete manifest.

Updates, removals, changed transitive selection, exclusions, repositories, local roots, or changed wrapper implementation require drain → terminate → rebuild basis → relaunch. `sync-deps` adds no useful semantics here: it is still append-only and depends on the process’s original basis configuration.

The leaf must explicitly install and verify a `DynamicClassLoader` context loader at startup. The earlier claim that `clojure -M -m` guarantees one is unsupported: Clojure explicitly creates it in the REPL entry path, whereas `-m` does not pass through that code.

## Manifest and root-dependency authority

Keep the cluster manifest minimal and native:

```clojure
{:deps
 {org.example/library {:mvn/version "1.2.3"}}}
```

Do not admit cluster-controlled `:aliases`, `:paths`, `:jvm-opts`, `:override-deps`, or launch configuration. Coordinate maps should be validated against supported Maven/Git/local coordinate shapes; local roots, if allowed at all, must remain below the cluster package root.

The effective launch basis is a controlled composition of:

- a new minimal root-owned package-host alias containing the Seon codec/server kernel and protected pins; and
- the cluster manifest as additions only.

It must not compose onto `:writer`: that would load Datahike, Konserve, Proximum, writer flags, and third-party package jars into one dependency closure. Conversely, cluster rows must be rejected when they directly name Clojure, Seon kernel libraries, or maintained shared forks. Trusted root `:override-deps` remain final for protected transitive libraries. If a package genuinely requires an incompatible protected version, return steering; do not silently override the root authority.

## Wrapper registration and corpus flow

Use one atomic corpus mechanism:

1. Require deps requests to use `seon.packages.jvm.<pkg>`.
2. Parse the wrapper through the ordinary corpus/analyzer path, producing namespace, function, schema, test, require-edge, and program-edge rows.
3. Stamp every row with the ledger ref using the existing `stamp-corpus-rows`.
4. Commit ledger and corpus rows through the writer in the normal provenance-bearing transaction.
5. Reconstruct the JVM leaf from the committed manifest and wrapper generation.
6. Register process side remote implementations through the existing U2 registry, preserving corpus `:arglists`, documentation, schemas, and effect metadata.
7. Re-registration alters existing SCI var roots, so live contexts see the new generation without rebuilding their private contexts.
8. Removal retracts stamped corpus entities and the ledger entity, removes inventory exports, then restarts the leaf.

The package host should remain eval-free. Agent-authored composition stays in guarded run-holding process SCI; the leaf exposes a sealed package-call dispatcher. That dispatcher must validate the installed ledger namespace, generation, package coordinate, and declared export—it must not expose an unrestricted arbitrary-class/reflection API as an agent binding.

## Planner integration

A JVM package row must not enter inventory merely because it exists in the ledger. The callable condition is:

```text
matching ledger row
+ matching package-stamped corpus wrapper
+ accepted function contract/effect terminal
+ successfully resolved basis
+ ready leaf exporting that function generation
```

For each exported `seon.packages.jvm.<pkg>/fn`:

- The program edge already yields a terminal whose required binding defaults to that function symbol.
- The JVM package leaf publishes it in `:seon.execution.inventory/bindings`.
- Processes holding runs with the installed wire stub publish it in `:seon.execution.inventory/remote-bindings`.
- The inventory digest covers package ledger generation, wrapper/program generation, effective basis digest, and exported function set.
- Process PID should not affect the inventory digest; an equivalent restart should preserve capability identity.
- The planner then constrains the native leaf to JVM while allowing a run-holding process to execute the surrounding graph through the remote binding.
- P5 should provision/verify the leaf before entering `:evaling`; P6 owns the general local-implementation versus wire-stub installer. The package unit should extend those contracts, not recreate routing.

## Security and isolation posture

This provides crash and classpath containment, not a security sandbox.

Required controls:

- Default-closed or allowlisted install policy.
- No credentials inherited unless explicitly needed.
- Cluster-local working directory and writable paths.
- Bounded heap, output, frame size, call deadline, and restart backoff.
- No database connection or writer credentials.
- No public arbitrary `eval`, `require`, classpath mutation, or reflection operation.
- Native results cross only as ordinary wire data; nonserializable state follows the existing tier-local/handle policy.
- A deadline breach kills the whole leaf; interrupting an unknown third-party thread is not trusted containment.

## Size

Overall: **L**, naturally split into four bounded units:

- **S–M:** prefix/coordinate validation, JVM corpus admission, tests.
- **M:** controlled basis construction, explicit `DynamicClassLoader`, live-add/restart classification.
- **M:** operator-owned JVM package leaf and hostile-process battery.
- **M:** wrapper export inventory, U2 registry stubs, P5/P6 planner integration.

## UNCLEARs and required probes

1. **Exact Clojure 1.12.0 source checkout:** the repository pins `org.clojure/clojure 1.12.0`, while the vendored Clojure checkout is later master `b18d3adc…`. The relevant APIs are marked “added 1.12” and match official documentation, but the unit should mirror/read the exact 1.12.0 tag before implementation.

2. **Startup loader:** launch a minimal `-M -m` process and record the context-loader chain before and after explicit installation. Acceptance: `add-libs` works on the service thread and a second worker thread.

3. **Monotonic basis test:** add a library whose transitive closure overlaps an existing dependency. Acceptance: live add occurs only if every prior selected coordinate remains byte-for-byte identical; otherwise restart is selected.

4. **Protected fork test:** request a direct or transitive conflicting Datahike/Konserve/Clojure coordinate. Acceptance: the cluster package cannot replace root authority, and the rejection names the conflicting protected lib.

5. **Inventory producer contract:** no production producer currently exists. Prove one package terminal appears native in the leaf inventory, remote in the cluster JVM inventory, disappears on removal, and changes the planner cache key on wrapper/basis generation change.

6. **Crash battery:** `System/exit`, infinite native call, partial live-add failure, and invalid wrapper export. Acceptance: writer and run-holding process survive, calls settle as values, the operator replaces the leaf, and durable package/corpus facts remain intact.

No files were written; no builds or REPL probes were run.
