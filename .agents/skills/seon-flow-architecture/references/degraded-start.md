# Degraded start and scratch-JVM recovery

Use this runbook when a scratch cluster fails during startup, especially while
other lanes are changing the shared tree. Do not restart or mutate another
lane's cluster to obtain a cleaner signal.

## 1. Separate launch failure from a degraded tower

`seon.cluster/start!` opens and advertises `io-prepl` first. Every later tower
layer republishes the instance as it stands; a later failure throws with that
value under `:seon.boot/instance`, while the REPL, advertisement, and registry
entry survive (`src/seon/cluster.clj:845-924,926-1023`).

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
| connection, no `:seon.boot/config-result` | schema accretion, recovery, or config application failed; use the exception cause to select among them |
| config result, no routing/served value | root seed, work launcher, flow arm, or web serve failed |
| `:seon.boot/ready-ms` | the complete tower returned |

This table follows the actual publish points and order at
`src/seon/cluster.clj:845-924,926-1023`; do not infer a higher layer from a pid
or open socket alone.

## 2. Inspect the advertisement before touching lifecycle

The per-cluster advertisement is
`<bootstrap-root>/<cluster-name>/prepl.edn`
(`src/seon/cluster.clj:98-150`). A valid advertisement carries cluster name,
pid, process start instant, and the bound REPL endpoint; the final tower adds
the web URL (`src/seon/cluster.clj:963-986,909-924`).

For the shared default root, use:

```bash
bin/seon-fresh status
```

`bin/seon status` is currently excluded by the open blocker
`docs/seon/issues/bin-seon-status-cannot-load-datahike-through-babashka.md`;
until that issue is resolved, teach and use `bin/seon-fresh status`.

The fresh status path reads every `prepl.edn`, checks `(pid, start-instant)`
liveness, and separately reports detached operator JVMs with no live
advertisement
(`script/seon/fresh_operator.clj:192-230,237-269,553-577`). Therefore the
scratch cluster did not survive only when:

1. its row is absent or stale;
2. no other row names that cluster; and
3. the status footer reports `orphan seon JVMs: none`.

A file's mere presence is not proof of a live cluster. Seon's own
`read-advertisement` rejects stale process identity for this reason
(`src/seon/cluster.clj:1197-1221`).

## 3. Avoid the stale-JVM trap

`bin/seon start <name>` does **not** promise a new JVM. When the operator finds
any live advertisement under its root, it evaluates `seon.cluster/start!` in
that already-running JVM; only an empty root launches
`clojure -M:dev` in a new process
(`script/seon/fresh_operator.clj:381-424,433-473,495-522`).

A long-lived JVM can therefore still hold old Var roots even when the checkout
is correct. A failure in a newly added cluster does not prove the current file
still fails. For boot-sensitive proof, use a lane-owned operator root with no
live advertisements. The verified isolation shape is a directory under
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

bb --config "$PWD/bb.edn" --deps-root "$PWD" --classpath "$PWD/script" \
  -m seon.fresh-operator --seon-root "$operator_root" start scratch-name
```

The custom root is the fresh operator's explicit first argument
(`script/seon/fresh_operator.clj:29-47`), and an empty root selects its new-JVM
branch (`script/seon/fresh_operator.clj:495-522`). Use the same direct command
with `status`, `logs scratch-name`, and `stop scratch-name` so discovery and
cleanup stay inside that root. A custom-root cluster is intentionally invisible
to the default MCP discovery path
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
