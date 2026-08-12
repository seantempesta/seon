---
type: research
status: complete
tags: [research, audit, database, sci, runtime, testing]
---

# Adversarial pass — 2026-08-03 landing wave

## Scope and dependency ledger

This independent pass distrusts the landing reports and judges commits
`b2f6ab14d`, `e6f92b09b` with SCI fork `2db3358c`, `59e4a85ef`,
`744ed9ef1`, `6ee80305c`, and suite-speed commits `c2857ae5c`,
`0cc33e9dd`, `68b14fd68`, `7d1a34b4b`.

Pinned sources read at the seams:

- SCI `2db3358cba91`, especially
  `reference-code/sci/src/sci/impl/namespaces.cljc:843-861` and
  `evaluator.cljc:260-280`;
- Datahike `0e8601d7f2f6`, especially
  `writing.cljc:489-520,672-711` and `versioning.cljc:237-321`;
- Malli `80138076960e`, whose maps are open by
  default at `reference-code/malli/README.md:294-300`;
- Konserve `737697d9205e`, whose memory store was
  measured directly for the fixture probe; and
- Clojure `b18d3adc5b5f4d5d0ccea966203fb67a614d5c3d`, including the process-global
  prepl server registry at `reference-code/clojure/src/clj/clojure/core/server.clj:24-43,85-123`.

All database experiments used isolated in-memory stores or operator roots below
`tmp/`. SCI mutation probes ran through an interpreted eval in a real isolated
cluster context. No production source was edited.

## Codec verdicts

### 1. All four `seon.db` read paths — CONFIRMED

A synthetic mixed-union attribute
`[:or :string :qualified-symbol]` stored `example.renderer/render` through
`seon.db/transact!`. The exact read result was:

```clojure
{:q {:value example.renderer/render, :class clojure.lang.Symbol}
 :pull {:value example.renderer/render, :class clojure.lang.Symbol}
 :entity {:value example.renderer/render, :class clojure.lang.Symbol}
 :datoms {:value example.renderer/render, :class clojure.lang.Symbol}}
```

The recursive map decoder is `src/seon/db.clj:178-205`; query decoding enters
at `:424-464`, pull at `:477-524`, entity at `:564-584`, and datoms at
`:586-613`.

### 2. Direct Datahike and the migration window — CONFIRMED RISK, CURRENTLY DORMANT

The identical database value read through `datahike.api` returned strings on
all observed surfaces:

```clojure
{:q-scalar ["example.renderer/render" java.lang.String]
 :q-tuple-child ["example.renderer/render" java.lang.String]
 :q-pull ["example.renderer/render" java.lang.String]
 :pull ["example.renderer/render" java.lang.String]
 :entity ["example.renderer/render" java.lang.String]
 :datoms-v ["example.renderer/render" java.lang.String]}
```

The current production schema census returned
`{:union-count 24, :schema-key-count 460, :installed-unions ()}`. Therefore
the exact live production direct call-site count that can read an installed
union-capable attribute today is **zero**: no fallback mixed-union attribute is
installed. The risk is nevertheless real during the unfinished 34-namespace
migration: the first such attribute becomes string-valued for any direct
Datahike reader while `seon.db` readers decode it. This evidence is added to
[[seon-db-is-not-the-one-database-namespace]].

### 3. Query tuple and pull-expression decoding — CONFIRMED

Both `:find ?value .` / tuple position and `:find (pull ?entity [...])`
returned `clojure.lang.Symbol`. Query variable-to-attribute inference and
recursive pull-map decoding are owned together at `src/seon/db.clj:216-240`
and `:424-464`; neither query result shape bypassed the codec.

## Compiled Var metadata verdicts

### 4. Every constructed compiled-Var path — CONFIRMED

In a real cluster SCI context, direct symbol, `(var seon.db/q)`, `apply`, and a
Var captured in a collection were tried with both `alter-meta!` and
`reset-meta!`. All eight returned the same flat value and none threw into the
loop:

```clojure
#:seon.error{:kind :seon.sci.eval/evaluation-failed,
             :message "Compiled Var #'seon.db/q metadata is read-only from SCI. Define an SCI-local Var to own mutable metadata."}
```

SCI performs the host-Var test before either mutation at
`reference-code/sci/src/sci/impl/namespaces.cljc:843-861` and installs those
implementations at `:1713-1718,2049-2052`.

### 5. Compiled Var reachability and remaining surfaces — CONFIRMED REACHABLE, MUTATION CLOSED

`(get (ns-publics 'seon.db) 'q)` still returned a real `clojure.lang.Var`.
SCI reported `(var? v)` false, but metadata, deref, and invocation remained
available. Mutation attempts produced flat failures:

```clojure
{:.setMeta "Method setMeta on class clojure.lang.Var not allowed!"
 :.bindRoot "Method bindRoot on class clojure.lang.Var not allowed!"
 :.setValidator "Method setValidator on class clojure.lang.Var not allowed!"
 :.doReset "Method doReset on class clojure.lang.Var not allowed!"
 :alter-var-root "No implementation of method: :get-validator of protocol: #'sci.impl.vars/IVar found for class: clojure.lang.Var"
 :set-validator! :unresolved}
```

Reflection is blocked because Seon's SCI class table admits only Throwable and
Error roots (`src/seon/sci/eval.clj:156-211`); SCI refuses instance methods
whose runtime class has no allowed target at
`reference-code/sci/src/sci/impl/evaluator.cljc:260-280`. Interning the object
only stored the host Var as the value of a new SCI Var and did not mutate it.

### 6. An agent's own SCI Var — CONFIRMED

An interpreted `(defn own-meta-target "Original doc." [] :ok)` followed by
`alter-meta!` returned:

```clojure
{:call :ok, :doc "Changed doc.", :owned true,
 :class "class sci.lang.Var", :var? true}
```

The refusal is correctly limited to compiled Vars.

## History verdicts

### 7. Non-temporal reads and boot integration — API CONFIRMED, INTEGRATION REFUTED

On a low-level history-off store, all three Seon reads were flat and no
Datahike temporal exception was logged by schema admission or registry blob
collection:

```clojure
{:history {:seon.error/kind :seon.db/non-temporal-database}
 :as-of {:seon.error/kind :seon.db/non-temporal-database}
 :since {:seon.error/kind :seon.db/non-temporal-database}
 :registry-collected 1}
```

The preflight is `src/seon/db.clj:629-650`; schema and registry consumers use it
at `src/seon/schema.clj:528-535` and
`src/seon/cluster/registry.clj:301-319`.

The complete history-off tower still failed before READY. With temporal
first-assertion evidence absent, schema admission classified the canonical
`:seon.ai/usage` row as agent-authored, then refused its `:any`. Exact boundary:

```text
seon.schema.internal/contract-error!: :seon.ai/usage uses :any in agent-authored contract
```

Thus the migration silenced repeated dependency exceptions, but a history-off
cluster is not yet usable. The existing
[[history-off-is-not-a-creation-seam-toggle]] remains open.

### 8. Persisted reopen and conflict — CONFIRMED

Reopening the same physical store with no explicit policy derived false from
the persisted database record. A conflicting explicit true was refused before
connect:

```clojure
{:reopen-keep-history? false
 :conflict {:seon.error/kind :seon.cluster.store/refused,
            :requested true, :stored false}
 :connect-calls 0}
```

Datahike persists the config with only conditional temporal roots at
`reference-code/datahike/src/datahike/writing.cljc:672-711`; its branch
operation copies that stored database record at
`versioning.cljc:237-289`.

## Open-map verdict

### 9. Multiple matches and production selection — MATCHER CONFIRMED, CONSUMERS REFUTED

Shapes A (`x`) and B (`x`, optional `y`) were tested with `{x 1, y 2}`:

```clojure
{:closed-validation {:shape-a false, :shape-b true}
 :open-validation {:shape-a true, :shape-b true}
 :matching-shapes
 [{:seon.schema/key :seon.adversarial/shape-a,
   :seon.schema/required-attrs #{:seon.adversarial/x}}
  {:seon.schema/key :seon.adversarial/shape-b,
   :seon.schema/required-attrs #{:seon.adversarial/x}}]}
```

`matching-shapes-in` correctly returns both. Its deterministic order is
descending required-attribute count, then schema-key string
(`src/seon/schema.clj:2236-2238,2304-2324`). No literal
`(first (matching-shapes ...))` exists, but `src/seon/render.clj:294-305` and
`src/seon/render/walk.clj:153-159` use `some`, which is equivalent first-match
selection. Both are findings; see
[[render-resolution-silently-chooses-one-overlapping-schema]].

## Config verdict

### 10. Positional schema extraction sweep — REFUTED

The fixed config test selects vector entries, but the sweep found one remaining
production extraction at `src/seon/cluster/loop.clj:201-216`: `(drop 2
(schema/schema-definition entity))` with no properties-map guard.

The current census returned
`{:actual-count 58, :honest-count 58, :missing (), :extra ()}` because all six
present definitions happen to carry a properties map. A valid properties-free
Malli map loses its first entry. See
[[schema-map-extraction-still-depends-on-position-two]].

## S. Suite-speed fixture verdicts

### S1. Concurrent branch datom isolation — CONFIRMED

Two fixture branches were acquired concurrently. After transacting a marker in
A, the probe returned:

```clojure
{:branch-a :seon.test-support.fixture/0
 :branch-b :seon.test-support.fixture/1
 :a-sees-row true, :b-sees-row false, :base-sees-row false}
```

Each lease forks from immutable `:db`, connects its own branch, releases, then
deletes it (`test/seon/test_support.clj:254-275`). Datahike copies the selected
stored head at `reference-code/datahike/src/datahike/versioning.cljc:237-289`.

### S2. One hundred acquire/release cycles — CONFIRMED BOUNDED

The probe measured the actual Konserve MemoryStore state after GC with
`clj-memory-meter`, not merely branch count:

```clojure
{:samples
 {0   {:keys 11, :bytes 8576400}
  1   {:keys 11, :bytes 8572360}
  10  {:keys 11, :bytes 8572360}
  25  {:keys 11, :bytes 8572360}
  50  {:keys 11, :bytes 8572360}
  75  {:keys 11, :bytes 8572360}
  100 {:keys 11, :bytes 8572360}}
 :branches #{:db}
 :leased-branch-keys (:fixture/0 :fixture/1)
 :commit-graph? false}
```

Growth was not monotonic; key count and measured bytes stabilized after the
first reuse. The fixture disables immutable commit records at
`test/seon/test_support.clj:85-112`; Datahike then writes only mutable branch
heads (`reference-code/datahike/src/datahike/writing.cljc:489-520`).

### S3. Schema evolution isolation — CONFIRMED

The same concurrent probe installed a synthetic schema only in A and returned
`{:a-sees-schema true, :b-sees-schema false, :base-sees-schema false}`.

### S4. Retained-root symlink reaping — CONFIRMED

A dead retained root contained a symlink whose target was tracked `README.md`.
`bin/test` announced that it reaped the root; the root disappeared, while the
target's SHA-256 remained
`82ef13ae78e3628788f1d3ff6dcdba83c774d49b644802dbb1fb826a17b198b1`
before and after. The reaper delegates to the no-follow filesystem
owner at `bin/test:133-188`; successful cleanup uses the same owner at
`:250-263`.

The selected test run later failed at another lane's uncommitted schema
provenance boundary (`expected max-tx 536870921, actual 536870922`). That is
outside the suite-speed range and did not block the completed reaper proof.

### S5. Coverage surface — CONFIRMED

`git diff c2857ae5c^..7d1a34b4b -- test/` found no deleted `deftest` or parity
test, no skip/focus metadata, no removed generative property, and no reduced
trial count. The message and reconcile properties remain at 60 trials. One
fixture assertion was replaced by a stronger two-branch assertion proving both
installation and absence after lease reuse. Six same-JVM session-image cases
moved from file stores to isolated fresh memory stores without changing their
assertions; the cross-process persistence case remains file-backed.

## Shared-surface census — ruling #51

| Process-level object | Ownership evidence | Cross-cluster effect of one cluster going insane |
|---|---|---|
| JVM heap, GC, and process | all instances inhabit `running-instances` in `resources/seon/operator/runtime.clj:11-22` | retained allocations or writer queues can exhaust the one heap and stall or kill every cluster; blocker filed |
| Store holder and lifetime flock | `resources/seon/operator/runtime.clj:13-15`; acquisition/refcount at `src/seon/cluster.clj:220-268` | protects against a second writer process; one release failure/lifecycle lock can delay siblings, and all branches share disk/fsync pressure |
| Root compute executor | `resources/seon/operator/runtime.clj:17-22`; consumed at `src/seon/flow.clj:424-446` | one compute storm can occupy every fixed platform thread; no per-cluster fairness |
| Root I/O executor | `resources/seon/operator/runtime.clj:17-22` | virtual threads avoid a fixed-pool starvation cliff, but an I/O flood still shares heap, sockets, carriers, and remote quotas; lowest executor risk |
| Installed work launcher | `src/seon/flow.clj:465-514`; reinstalled by `src/seon/cluster.clj:1368-1371` | cluster B replaces/stops A's launcher and can interrupt active A work or impose B's limits; blocker filed |
| Compiled JVM Vars | Flow steps retain Vars (`src/seon/flow.clj:82-126`); instrumentation mutates roots process-wide (`src/seon/instrument.clj:274-338`) | operator hot reload or instrumentation changes all clusters at once; agent SCI can read/call compiled Vars but the audited metadata and reflection mutations are closed |
| Malli default registry and schema candidate state | `src/seon/schema.clj:448-592`; shape cache at `:2175-2190` | host-side registration/relink and cache churn are process-wide; cluster SCI contexts carry explicit projections (`src/seon/sci/eval.clj:1369-1394`), limiting agent-to-sibling schema leakage |
| SCI deadline timer and stock core Vars | `src/seon/sci/eval.clj:266-318`; one base per cluster at `:1369-1394` | timer delay can postpone deadline flips process-wide, but armed state is ThreadLocal; stock compiled Vars are shared read-only, while SCI-local Vars remain cluster-local |
| Source refresh monitor/cache | `src/seon/cluster.clj:458-468,723-730` | one expensive publication serializes other init/refresh operations and shares cached immutable analysis; it does not mutate sovereign live cluster facts |
| Datahike connection registry | `reference-code/datahike/src/datahike/connections.cljc:1-73` | registry coordination is global; connections and writers remain keyed by store/branch, so a cluster's transaction order is isolated but heap/disk pressure is not |
| Branch-open and blob-collection monitors | `src/seon/cluster/store.clj:372-405`; `src/seon/cluster/registry.clj:286-325` | a slow lifecycle/collection operation serializes the same process-wide monitor, including unrelated roots; normal running reads do not pass through it |
| Clojure prepl server registry | `reference-code/clojure/src/clj/clojure/core/server.clj:24-43,85-123`; per-cluster socket at `src/seon/cluster.clj:1441-1458` | names and lifecycle share a process lock/map; each socket and session thread is per cluster. A privileged prepl can mutate compiled process state, but this is an operator surface, not agent-reachable |
| Wake listeners | registered per cluster connection at `src/seon/cluster/wake.clj:163-228` | a commit storm invokes only that connection's listener; delivery is nonblocking `offer!` and catch-contained. Sibling impact is indirect shared writer/disk/heap load |
| Web servers | each cluster creates its own server and virtual-thread executor at `src/seon/render/web.clj:1315-1374` | sockets and request workers are per cluster; handler Vars, heap, GC, and disk remain shared. One server's traffic does not directly own a sibling server |
| Running-instance registry | `resources/seon/operator/runtime.clj:11`; reservation/publication at `src/seon/cluster.clj:174-191,1464-1478` | process-root lifecycle corruption affects discovery and stop for all clusters; the namespace is absent from every SCI program graph, so agents cannot reach it directly |
| Opaque schema-generator samples | `src/seon/cluster.clj:71-75`, `src/seon/render/web.clj:88-114`, `src/seon/sci/eval.clj:141-146`, and `src/seon/flow.clj:55-78` | these are diagnostic rather than cluster runtime objects, but every generation shares one live socket/server/channel/context/executor; mutation or closure poisons later checks process-wide and server samples bind unowned ports. See [[opaque-contract-generators-share-live-process-objects]] and [[flow-generators-reuse-one-mutable-sample]] |

### Ranked top three

1. **Installed work-launcher replacement — blocker.** It is a direct,
   deterministic cross-cluster interruption path on ordinary sibling startup,
   not merely saturation risk. See
   [[process-work-launcher-is-replaced-by-every-cluster-start]].
2. **Shared unbounded heap/GC — blocker.** Sustained writer-queue exhaustion is
   measured, while the reassuring one-allocation recovery result explicitly
   does not cover retention. See
   [[cohosted-clusters-share-one-unbounded-agent-heap]].
3. **Root compute starvation — friction.** The shared fixed pool has no
   cluster-aware fairness and ruling #51 already names the gap. See
   [[root-compute-executor-has-no-per-cluster-fairness]].

## Calibration: what is genuinely in good shape

- The union read codec is total across the four promised `seon.db` surfaces
  and both query result shapes probed. Its remaining exposure is precisely the
  already-filed direct-Datahike migration window, not a codec hole.
- The compiled-Var metadata closure is strong: every construction path reaches
  the same refusal, host reflection is closed by the SCI class policy, failures
  flatten at the agent boundary, and SCI-local Vars preserve normal Clojure
  metadata semantics.
- Temporal reads now preflight the database representation once and schema/blob
  consumers no longer generate repeated Datahike exceptions. Persisted policy
  derivation and pre-connect conflict refusal are correct; only canonical-row
  provenance keeps history-off boot from graduating.
- `matching-shapes-in` itself is honest and deterministic: it reports all open
  schema matches. The defect is localized to consumers that silently select.
- The fixture redesign survived independent branch, schema, memory-growth,
  symlink, and coverage falsifiers. Distinct live branches have distinct
  writers/datoms/history, branch-name reuse bounds retained memory-store keys,
  and no tested coverage was weakened.
- Wake listeners, web servers, prepl sockets, SCI contexts, database branches,
  and agent graphs are genuinely per cluster. The census separates those
  healthy boundaries from the smaller set of dangerous process-root objects.
