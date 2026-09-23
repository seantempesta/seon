---
type: reference
status: active
tags: [reference, flow]
---

# Degraded start and scratch-JVM recovery

Use this runbook when a scratch cluster fails during startup, especially while
other lanes are changing the shared tree. Do not restart, stop or mutate
another lane's cluster to obtain a cleaner signal; lanes never stop, refork or
reset `default` (`AGENTS.md:405-406`). A boot longer than ten seconds needs the
owner's explicit authorization (`AGENTS.md:83-84`).

## 1. Separate launch failure from degraded boot

`seon.cluster.boot/start!` opens the `io-prepl` listener first, then publishes
the instance into `seon.operator.runtime/running-instances` after every layer
it stands (`src/seon/cluster/boot.clj:288-341`). A later failure throws with
the value that stood under `:seon.boot/instance`; the REPL, the registry entry
and (once the store stands) the advertisement survive. Only a failure before
the first publication, or a store held elsewhere, unwinds the listener and the
registry entry (`:332-337`).

Start diagnosis with the exception's `:seon.boot/instance`. If an `io-prepl`
in that JVM is reachable, ask the owning function for the same answer:

```clojure
(seon.cluster.boot/readiness
 (get @seon.operator.runtime/running-instances "scratch-name"))
```

`readiness` (`src/seon/cluster/boot.clj:343-358`) returns the advertisement,
`:seon.boot/missing-layers` (each required layer absent from the instance), the
agent count, `seon.problems/problems` and `:seon.boot/ready-ms`. The registry
atom is `resources/seon/operator/runtime.clj:11`.

Read the missing layers in boot order
(`stand-boot-layers!`, `src/seon/cluster/boot.clj:146-246`, then
`stand-cluster-runtime!`, `:48-128`):

| First missing layer | Failure boundary |
|---|---|
| `:seon.boot/prepl-server` | launch or layer-0 REPL failed; nothing was published |
| `:seon.store/store` | process-root store acquisition failed |
| `:seon.store/branch` | source publication, reachability permit, or cluster branch creation failed |
| `:seon.boot/cluster-connection` | branch open failed |
| `:seon.source/commit-id` | the source commit could not be read from the base or the cluster row |
| `:seon.boot/config-result` | coherent-program validation, schema accretion, run recovery, or config application failed; use the exception cause to select among them |
| `:seon.sci.eval/ctx` | cluster/root-agent convergence, host arming, base-context fork, or work-launcher start failed |
| `:seon.flow/work-launcher` | environment construction after the context failed |
| `:seon.flow/graph` | `arm-agents!` failed |
| `:seon.render.web/served` | web serve failed |

`:seon.boot/ready-ms` is published only after the whole sequence returns
(`src/seon/cluster/boot.clj:329-331`). Do not infer a higher layer from a pid
or open socket alone.

## 2. Inspect the advertisement before touching lifecycle

The per-cluster advertisement is `<bootstrap-root>/<cluster-name>/prepl.edn`
(`cluster-paths`, `src/seon/cluster.clj:732-749`); the operator reads
`<operator-root>/data/clusters/*/prepl.edn` (`script/seon/operator.clj:106-119`).
Boot writes it once the store stands (`src/seon/cluster/boot.clj:164-167`),
and `serve!` rewrites it with the web URL (`src/seon/cluster.clj:2977`). JVM
process identity is only `(pid, start-instant)`
(`src/seon/cluster/process.clj:1-7`).

For the shared default root, use:

```bash
bin/seon status
```

`bin/seon` accepts `--root PATH`, which must name an existing directory, and
runs `seon.operator` with that operator root (`bin/seon:7-26`). Advertisement
discovery, the exact-root process scan and every lifecycle command are
root-scoped (`script/seon/operator.clj:106-131`). The process scan matches the
exact `-Dseon.operator.root=<root>` JVM argument, never a command substring
(`:121-131`).

A file's mere presence is not proof of a live cluster: an advertisement counts
only when its `(pid, start-instant)` matches a live process
(`matching-handle`, `script/seon/operator.clj:93-104`;
`seon.cluster/read-advertisement`, `src/seon/cluster.clj:3542`). A JVM that the
scan finds but whose endpoint is unreadable makes `status` fail with "An
exact-root JVM is alive but its endpoint is unavailable."
(`script/seon/operator.clj:211-218`). The scratch cluster did not survive only
when status names no live advertisement for it and no such exact-root JVM
remains.

## 3. Avoid the stale-JVM trap

`bin/seon start <name>` does **not** promise a new JVM. When the operator
finds any live process under its root, it asks that JVM to start the cluster;
only a root with no live process launches `clojure -M:dev:test` in a new
process (`request!`, `script/seon/operator.clj:1281-1285`; `launch-child!`,
`:501-568`, called by `launch!` `:570`).

A long-lived JVM can therefore still hold old Var roots even when the checkout
is correct. A failure in a newly added cluster does not prove the current file
still fails. For boot-sensitive proof, use a lane-owned operator root with no
live process, after the owner authorizes the boot's duration:

```bash
operator_root="$PWD/tmp/my-lane-operator-root"
mkdir -p "$operator_root"
bin/seon --root "$operator_root" start scratch-name
```

The launched JVM runs the checkout's code (`.directory` is the repository
root, `script/seon/operator.clj:524`); the operator root holds only its
`data/` tree, logs and an optional `.env` (`:279-294`, `:505-507`). Use the
same `--root` option with `status`, `logs scratch-name` and
`stop scratch-name` so discovery and cleanup stay inside that root. MCP
discovery defaults to the project root and lists another root only when it is
passed explicitly (`script/seon/dev/mcp.clj:87-94`).

## 4. Fall back to an isolated in-memory JVM

If shared-tree churn prevents boot from reaching the mechanism under test, stop
claiming live-cluster proof. When the question is pure Datahike planning or
another cluster-independent transformation, use a separate `clojure -M:dev`
JVM and an immutable in-memory value:

```clojure
(require '[datahike.db :as db]
         '[datahike.query :as query])

(def planner-db (db/empty-db {}))
(#'query/create-plan-via-ir planner-db clauses #{} nil nil)
```

This is the retained planner falsifier and property fixture
(`test/seon/datahike_fork_test.clj:16-54`). It proves the pure mechanism
without claiming store, facts, flow, web, or boot integration. If the named
exit requires one of those layers, record the exact failed boundary and report
it; an in-memory fallback is not a substitute for the later live gate.
