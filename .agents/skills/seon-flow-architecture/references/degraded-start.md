# Degraded start and scratch-JVM recovery

Use this runbook when a scratch cluster fails during startup, especially while
other lanes are changing the shared tree. Do not restart or mutate another
lane's cluster to obtain a cleaner signal.

## 1. Separate launch failure from a degraded tower

`seon.cluster/start!` opens and advertises `io-prepl` first. Every later tower
layer republishes the instance as it stands; a later failure throws with that
value under `:seon.boot/instance`, while the REPL, advertisement, and registry
entry survive (`src/seon/cluster.clj:1388-1485`).

Start diagnosis with the exception's `:seon.boot/instance`. If another
`io-prepl` in that JVM is reachable, the same value is in the private registry:

```clojure
(let [instance
      (get @@#'seon.cluster/running-instances "scratch-name")]
  (select-keys
   instance
   [:seon.boot/advertisement
    :seon.store/store
    :seon.boot/cluster-connection
    :seon.boot/config-result
    :seon.cluster.agent/routing
    :seon.render.web/served
    :seon.boot/ready-ms]))
```

The double deref is intentional: `#'.../running-instances` is a Var whose
value is the registry atom
(`src/seon/cluster.clj:185-211`;
`docs/prds/sci-execution-runtime/research/repl-workflows-2026-07-29.md`
§6).

Read absence from the bottom upward:

| Last evidence present | Failure boundary |
|---|---|
| no advertisement | namespace/JVM launch or layer-0 REPL failed; layer 0 unwinds completely |
| advertisement, no `:seon.store/store` | REPL stands; process-root store acquisition failed |
| store, no `:seon.boot/cluster-connection` | ancestor, branch creation, or branch open failed |
| connection, no `:seon.boot/config-result` | coherent-program validation, schema accretion, recovery, or config application failed; use the exception cause to select among them |
| config result, no `:seon.sci.eval/ctx` | cluster/root-agent fact convergence, cold program acquisition, or durable session-image restoration failed |
| SCI ctx, no routing/served value | work-launcher install, agent arm, or web serve failed |
| `:seon.boot/ready-ms` | the complete tower returned |

This table follows the actual publish points and order at
`src/seon/cluster.clj:1289-1386,1388-1485`; context construction and
session-image restoration are at `src/seon/sci/eval.clj:1142-1228`. Do not
infer a higher layer from a pid or open socket alone.

## 2. Inspect the advertisement before touching lifecycle

The per-cluster advertisement is
`<bootstrap-root>/<cluster-name>/prepl.edn`
(`src/seon/cluster.clj:135-152`). JVM process identity is only
`(pid, start-instant)` (`src/seon/cluster/process.clj:2-28`); the advertisement
adds cluster name and the bound REPL endpoint, and the final tower adds the web
URL (`src/seon/cluster.clj:1371-1386,1434-1452`).

For the shared default root, use:

```bash
bin/seon status
```

`bin/seon` accepts `--root PATH` and enters `seon.fresh-operator` with that
canonical operator root (`bin/seon:4-18`; the old `bin/seon-fresh`
compatibility alias is deleted). Process records, advertisements,
discovered JVMs, roster reads, and anchor selection are root-scoped
(`script/seon/fresh_operator.clj:96-120,789-866,1041-1113,1593-1627`).
Cross-root JVMs are excluded before probing and therefore cannot become an
anchor for `start` (`script/seon/fresh_operator.clj:808-866,1139-1148`).

The fresh status path reconciles observations, checks `(pid, start-instant)`
liveness, and separately reports detached operator JVMs with no live
advertisement (`script/seon/fresh_operator.clj:604-619,789-866,1927-2016`).
Therefore the
scratch cluster did not survive only when:

1. its row is absent or stale;
2. no other row names that cluster; and
3. the status footer reports `orphan seon JVMs: none`.

A file's mere presence is not proof of a live cluster. Seon's own
`read-advertisement` rejects stale process identity for this reason
(`src/seon/cluster.clj:1679-1703`).

## 3. Avoid the stale-JVM trap

`bin/seon start <name>` does **not** promise a new JVM. When the operator finds
any live advertisement under its root, it evaluates `seon.cluster/start!` in
that already-running JVM; only an empty root launches
`clojure -M:dev` in a new process
(`script/seon/fresh_operator.clj:381-424,433-473,495-522`).

A long-lived JVM can therefore still hold old Var roots even when the checkout
is correct. A failure in a newly added cluster does not prove the current file
still fails. For boot-sensitive proof, use a lane-owned operator root with no
live advertisements. The verified filesystem shape is a directory under
repository-local `tmp/` whose code/config entries are symlinks to the current
checkout and whose `data/` is its own
(`docs/prds/sci-execution-runtime/research/checkpoint-audit-2026-07-29.md`
“Isolation and apparatus”; the existing
`tmp/seam-reaudit4-operator-root` demonstrates the layout).

One current-source setup is:

```bash
operator_root="$PWD/tmp/my-lane-operator-root"
mkdir -p "$operator_root"
for path in bb.edn bin config deps.edn reference-code resources script src test
do
  ln -s "$PWD/$path" "$operator_root/$path"
done

bin/seon --root "$operator_root" start scratch-name
```

`bin/seon --root` requires an existing root and forwards it as the fresh
operator's explicit root (`bin/seon:7-18`;
`script/seon/fresh_operator.clj:44-52`). An operator root with no reachable
anchor selects the new-JVM branch (`script/seon/fresh_operator.clj:1593-1688`).
Use the same `--root` option with `status`, `logs scratch-name`, and
`stop scratch-name` so discovery and cleanup stay inside that root. A
custom-root cluster is intentionally invisible to the default MCP discovery
path
(`docs/prds/sci-execution-runtime/research/repl-workflows-2026-07-29.md`
§1).

## 4. Fall back to an isolated in-memory JVM

If shared-tree churn prevents the tower from reaching the mechanism under
test, stop claiming live-cluster proof. When the question is pure Datahike
planning or another cluster-independent transformation, use a separate
`clojure -M:dev` JVM and an immutable in-memory value:

```clojure
(require '[datahike.db :as db]
         '[datahike.query :as query])

(def planner-db (db/empty-db {}))
(#'query/create-plan-via-ir planner-db clauses #{} nil nil)
```

This is the retained planner falsifier and property fixture
(`test/seon/datahike_fork_test.clj:12-49`). It proves the pure mechanism without
claiming store, facts, flow, web, or boot integration. If the named exit
requires one of those layers, record the exact failed boundary and wait for the
protected lane; an in-memory fallback is not a substitute for the later live
gate.
