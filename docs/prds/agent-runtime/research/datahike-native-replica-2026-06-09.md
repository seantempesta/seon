---
type: research
status: active
tags: [research, database, agent]
---

# Datahike native writer/reader split — can pod readers lazily follow the JVM writer without snapshots? (2026-06-09)

## TL;DR

- **VERDICT: YES — natively, and the user's hunch is correct.** Datahike (including the fork the pod runs, `seantempesta/datahike@01ba3f18`) is architected around a **Distributed Index Space (DIS)**: a single writer commits content-addressed index nodes + an atomically-renamed root pointer into a shared konserve store, and any number of readers open the SAME store and **lazily fetch index nodes on demand** through an LRU-cached `IStorage.restore` (`k/get` per node, memory ∝ working set, never a snapshot). The reader-follows-writer mechanism is `deref-conn`: when the conn's writer is non-streaming (i.e., remote), **every `@conn` re-reads the branch root from the store and reconstitutes a fresh immutable db value** (`connector.cljc:69-78`). No polling loop, no tx shipping, no snapshot — the store IS the replication channel.
- **Store-level sharing from Node is viable BY DESIGN, not by accident.** Both platforms serialize with the same fressian byte format (JVM `clojure.data.fressian`, CLJS `fress`; same serializer byte `1`, konserve `serializers.cljc`), datahike registers paired CLJ/CLJS read-handlers for the same `"datahike.index.PersistentSortedSet"`/Leaf/Branch/Datom fressian tags (`persistent_set.cljc:440-`), and konserve's Node filestore implements the SAME on-disk blob layout as the JVM filestore (shared `konserve.impl.defaults` + `storage-layout`, sync `readFileSync` channel). The JVM wire-server's cluster store is `:backend :file` (`src/seon/server/store.clj:82,120`) — exactly the shareable backend. The pod already runs file-backed datahike with sync reads today (`src/seon/client.cljs:457-461`), proving the Node sync-read path works.
- **What's missing in the fork is ONE small piece: a CLJS remote-writer backend.** The fork ships only `:self` (CLJS) and `:datahike-server` (JVM-only, `src/datahike/http/writer.clj`). The JVM impl is ~30 lines: a `PWriter` whose `-dispatch!` forwards the op and whose `-streaming?` is `false`. We write the same thing over our EXISTING UDS `transact` wire op (`:seon-wire` writer backend, pod-side, ~40 lines) — that single piece flips `deref-conn` into follow-the-store mode and reuses everything else as-is.
- **Read-your-own-writes is FREE in this design** — no basis-t handoff machinery needed. The JVM writer's commit loop delivers the transact callback only AFTER `w/commit!` flushes nodes + root to the store (`writer.cljc:108-134`), our wire `transact` handler replies after `d/transact` returns (`src/seon/server/wire.clj:353-373`), and the pod's next `@conn` re-reads the (already-updated) root. The sibling research's 2.2b RYOW gap (multi-agent-state-isolation-2026-06-09.md §1.2) was an artifact of the tx-feed-replica design; the DIS design doesn't have it.
- **The tx-feed-replay replica (option 3) and full-snapshot design are both dominated** by DIS: replay needs a new paginated datom-log wire op (doesn't exist today), costs O(history) at boot, and ends at the same full-DB-in-RAM state a snapshot would. **Query-subscriptions-only (option 4) is the right OFF-MACHINE endgame** (and the reactive layer + server-side `db-filter`/`q-filtered` ops already point there) but breaks the db-VALUE read API (d/datoms walks, d/filter closures) and is a weeks-scale port — wrong for the MVP-demo timeline.
- **Recommendation in 5 lines:** (1) keep the JVM wire-server as sole writer on the `:file` cluster store, unchanged; (2) pod connects to the SAME store dir with `{:writer {:backend :seon-wire}}` — new ~40-line CLJS `PWriter` that forwards `transact!` over the existing UDS `transact` op, `-streaming? false`; (3) all reads stay the local sync db-value API (d/q, d/datoms, d/filter, pull) with lazy LRU node fetch — zero changes to context assembly/agent-view; (4) change notification = existing `subscribe-tx` feed → adapter fires the pod's `listen!` handlers for OTHER writers' txs (own txs already fire locally in `writer/transact!`); (5) align the JVM writer to the same fork sha to kill version-skew risk. Smallest first slice: a two-process probe (JVM transacts → Node derefs and queries → JVM transacts again → Node sees it) using a stub non-streaming writer — proves every load-bearing claim in <1 day.

---

## Ground truth: the sources read

- Fork the pod actually runs: `~/.gitlibs/libs/org.replikativ/datahike/01ba3f18bf08da2c093eb0972ec1f272b817f23d` (= `seantempesta/datahike@01ba3f18`, 2026-05-20, per `deps.edn` `:override-deps`). All `src/datahike/...` cites below are into that checkout.
- Upstream vendored: `reference-code/datahike` at `replikativ@717a0d27` (2026-05-17) — same era; `doc/distributed.md` is the architecture statement.
- konserve 0.9.346 (the version BOTH the fork's deps.edn and the JVM resolve): inspected from the jar (`konserve/serializers.cljc`, `konserve/node_filestore.cljs`, `konserve/impl/defaults.cljc`); scratch extraction in `tmp/konserve-inspect/`.
- Seon wire-server: `src/seon/server/{wire,boot,broadcast,reactive,store}.clj`.
- No external `agy` call was made: the deciding question ("what can OUR fork do") is answered entirely by vendored source, and the recommended design does not adopt `datahike-server`/kabel, so their maturity is moot.

## Q1 — Datahike's real distributed primitives

### The architecture statement (doc/distributed.md)

`reference-code/datahike/doc/distributed.md:10-47` describes exactly the topology we want, as the *intended* design:

> "Distributed Index Space (DIS) … New index nodes are written to the shared storage backend … A new root pointer is published atomically … Readers pick up the new snapshot on next access — no active connections needed … **All readers continue to access data locally** from the distributed storage … they only contact it [the writer] to submit transactions."

Single-writer/multi-reader is the stated model (`distributed.md:38-47`): configure `:writer {:backend :datahike-server :url …}` and "all operations changing a database … are sent to the server while all other calls are executed locally."

### How a reader actually follows the writer (connector.cljc)

The whole mechanism is 10 lines — `deref-conn`, `src/datahike/connector.cljc:69-78`:

```clojure
(defn deref-conn [^Connection conn]
  ...
  (if (not (w/streaming? (get @wrapped-atom :writer)))
    (let [store  (:store @wrapped-atom)
          stored (k/get store (:branch (:config @wrapped-atom)) nil {:sync? true})]
      (dsi/stored->db stored store))
    @wrapped-atom))
```

- `LocalWriter` (`:self`) reports `-streaming? true` (`writer.cljc:180`) → `@conn` is a plain atom deref (today's pod behavior).
- ANY non-streaming writer (the JVM `:datahike-server` writer reports `false`, `http/writer.clj:30`) → **every deref re-reads the branch root key (`:db`) from konserve and reconstitutes the db value** via `stored->db` (`writing.cljc:198`). The stored root is a tiny map of index ROOT ADDRESSES (`eavt-key`/`aevt-key`/`avet-key` + temporal keys), schema, max-tx/max-eid, config — kilobytes, not datoms.
- There is no polling and no push: freshness is sampled at deref time. The conn's wrapped atom is never updated by remote txs; it's just a handle for store + config + writer.

### How lazy the reads are (persistent_set.cljc)

The fork's index is persistent-sorted-set 0.4.122 with konserve-backed `IStorage`:

- `CachedStorage` (`src/datahike/index/persistent_set.cljc:324-370`): `restore` = LRU lookup → on miss, `(k/get store address nil {:sync? true})` for ONE node blob, insert into LRU. `store` = content-addressed UUID per node (`gen-address`, :347 onward), write-through cache.
- LRU threshold = `:store-cache-size`, **default 1000 nodes** (`config.cljc:24`), branching factor 512 (`persistent_set.cljc:387`). The cache lives on the store map (`create-storage`, :376) and is created once per `add-cache-and-handlers` (`store.cljc:24`) — **shared across all derefs of the conn**, so the working set stays warm across successive db values.
- A fresh db value from `stored->db` is three BTSet shells holding only root ADDRESSES (the CLJS fressian read-handler constructs `(BTSet. nil count cmp meta nil @storage address settings)` — `persistent_set.cljc:492+`). Queries walk the tree and `restore` nodes on demand.

**Answer to the memory question: a reader holds root pointers + an LRU of recently-touched nodes. Memory ∝ working set, bounded by `:store-cache-size`. No snapshot, ever.** This is the Datomic-peer segment-cache model, natively — exactly the "hundreds of lightweight agents" shape (and `:store-cache-size` is per-connection config, tunable down for dense multi-pod deployments).

Crucially for the pod's sync read model: `restore` uses `{:sync? true}` `k/get`, which on Node is `fs.readFileSync` through konserve's sync `FileChannel` (`konserve/node_filestore.cljs:22-105`). **Lazy fetch is synchronous on Node** — `d/q`, `d/datoms` walks, `d/pull`, entity API, and `d/filter` CLJS-closure predicates all stay synchronous db-value operations. The 2.2 finding ("reads must stay local + sync") is satisfied with zero API change. The pod has been running exactly this code path (file backend, sync lazy reads) since the agent conn moved off `:memory` (`src/seon/client.cljs:393,457-461`).

### What writer backends exist in OUR fork

- `:self` — local in-process writer, both platforms (`writer.cljc:162`).
- `:datahike-server` — remote HTTP writer, **JVM only** (`src/datahike/http/writer.clj:32`; `.clj`, not `.cljc`).
- The kabel/konserve-sync streaming writer described in upstream `distributed.md:75-232` is **NOT in this fork** (no `src/datahike/kabel/`) — and we don't want it anyway (it ships store deltas to a client-local store; our pods share the filesystem, so DIS direct is strictly simpler).
- `connect` dispatches on `[:writer :backend]` (`connector.cljc:240-248`), so a custom backend needs exactly two `defmethod`s: `datahike.writer/create-writer` and `datahike.connector/-connect*` (the latter can delegate straight to `-connect-impl*`, as `:datahike-server` does at `http/writer.clj:77-79`). The whole `DatahikeServerWriter` is ~30 lines — the template for our `:seon-wire` writer.

## Q2 — Store-level sharing from Node: viable, by design

Three layers, all verified compatible:

1. **Blob layout.** Node filestore and JVM filestore are two backends over the SAME `konserve.impl.defaults` + `konserve.impl.storage-layout` engine (header bytes + meta + value, `.ksv` files, write-to-`.new`-then-atomic-rename — `defaults.cljc:84-111`, `node_filestore.cljs:330-336` citing fs.rename atomicity). The cluster store on disk (`data/clusters/default/store/*.ksv`) is this format.
2. **Serializer.** konserve's default serializer byte `1` = `FressianSerializer` on BOTH platforms — JVM via `clojure.data.fressian`, CLJS via `fress` (pkpkpk), one `defrecord` with reader conditionals (`konserve/serializers.cljc:29-60`, `byte->serializer` map :84-87). Same bytes, both directions.
3. **Datahike type handlers.** `add-konserve-handlers` registers fressian read/write handlers for `"datahike.index.PersistentSortedSet"`, `…Leaf`, `…Branch`, and Datom with PAIRED `:clj`/`:cljs` implementations reading the SAME tag stream (`persistent_set.cljc:430-` — the CLJS handler literally constructs the BTSet equivalent of the JVM PersistentSortedSet from the same `{:meta :address :count}` payload).

So **JVM-writes/Node-reads on one file store is a designed-for configuration**, not a hack — it is precisely upstream's "shared filesystem" DIS deployment (`distributed.md:47`). The wire stays the channel for WRITES and for push notification; it stops being the read channel.

Backends the fork ships for Node: `:file` (via `konserve.node-filestore`, registered by requiring it — `doc/cljs-support.md:21-39`, and the pod already requires it, `client.cljs:30-35`), `:memory`, `:tiered`, `:indexeddb` (browser). The JVM side's `:sqlite`/JDBC session stores are NOT Node-readable (no JDBC) — **DIS sharing requires the `:file` backend**, which is what the default cluster store uses (`server/store.clj:82` `data/...../store` tree, `:keep-history? true :schema-flexibility :write` base config :120-121 — matching the pod's own conn config shape, so the `ensure-stored-config-consistency` check at `connector.cljc:126-163` should pass; `:allow-unsafe-config true` is the documented escape hatch if writer-config comparison bites).

Cross-process safety facts:

- Index node blobs are content-addressed and **immutable once written** — the only mutated key is the branch root (`:db`), updated by write-`.new`-then-rename, which is atomic on POSIX. A concurrent reader gets either the old or the new root, never a torn one.
- konserve blob locking (`:lock-blob?`) is optional config (`defaults.cljc:266-268`); reads don't contend with the writer beyond the filesystem.
- **The one real hazard is GC**: `gc-storage!` deletes old index nodes; a reader holding an OLD db value (e.g., across an agent's long await) could hit `:node-not-found` (`persistent_set.cljc:352-355`) for collected nodes. Policy for MVP: don't run GC on the cluster store; later, GC with a grace window ≥ the max age of any held db value.
- **Version skew**: `version-check` (`connector.cljc:89-125`) requires reader lib versions ≥ writer's stored versions (datahike, persistent-sorted-set, konserve). Today the JVM `:writer` alias pins mvn `0.8.1671` while the pod runs the fork (based past `0.8.1681`) — reader-newer is the allowed direction, but the clean fix is to run the JVM wire-server on the SAME fork sha (it's `.cljc`; one deps line). konserve is already aligned at 0.9.346 on both sides.

## Q3 — The tx-feed replay replica (no snapshot): honest comparison

Mechanics: bootstrap by replaying the datom log over `subscribe-tx`/`next-tx-event` from tx-0, materialize via `datahike.db/init-db`, stay current on the feed.

- **Wire support today: none for backfill.** The op set is `ping, ensure-db, q, transact, transact-batch, pull, entity-pull, pull-many, schema, reverse-schema, db-filter, q-filtered, filter-release` (`server/wire.clj:218-536`) plus `subscribe-tx / next-tx-event / unsubscribe-tx` (`server/boot.clj:87-130`). The feed is a bounded per-handle queue fed by the live `::raw-broadcast` listener — it starts NOW, it cannot serve history. A paginated log op would be new code (feasible — the store is `:keep-history? true`, the JVM can `d/q` the temporal index in `[?tx :db/txInstant]` pages — but new code).
- **Memory: full replica in pod RAM**, identical end-state to the rejected snapshot — just amortized over the replay. The user's objection to snapshots was about shipping the whole DB to every agent; replay ships the whole DB *plus* per-tx framing. As docs get indexed into the substrate (the overnight-demo goal), boot cost grows O(history) per agent — directly hostile to "hundreds of lightweight agents."
- **It also inherits every gap the sibling research flagged** for 2.2b: RYOW basis-t handoff, gapless ordering, reconnect catch-up, tempid resolution (multi-agent-state-isolation-2026-06-09.md §1.2 items 1-4). All of these are *engineering we'd have to build*; in the DIS design they're free or absent.
- Where it WOULD win: pods on a different machine with no shared filesystem. That's not the MVP deployment (one Mac, UDS sockets).

**Verdict: dominated by DIS on this machine. Don't build it.**

## Q4 — Query-subscriptions-only (no local db at all)

The lightest-agent end: every read is a registered reactive query, JVM evaluates, results pushed (the engine exists — `server/reactive.clj`: pattern-indexed subscriptions, `register-subscription!`, `on-tx!` re-running affected queries; plus server-side `db-filter`/`q-filtered` handle-based filtered reads already on the wire, `wire.clj:511-536`).

What breaks, concretely (per the 2.2 read-model survey):

- Context assembly does direct `d/datoms` index walks and ad-hoc `d/q`/`d/pull` against ONE consistent basis — as subscriptions, every section becomes a server-registered query, and cross-section basis consistency needs a "render at basis-t" contract the reactive layer doesn't have yet.
- `d/filter` with CLJS closure predicates cannot cross the wire at all; predicates would have to be rewritten as data (query clauses) or named server-side fns. (`q-filtered` shows the server-side shape, but the migration of agent-view's closures is real work.)
- Agent-authored arbitrary queries (the demo is "agents answer arbitrary questions by digging the DB") fit fine — `q` over the wire exists — but they'd be ASYNC, and the pod's whole eval/context path treats reads as sync.

Sizing against the goals: this is the correct **off-machine, thousands-of-agents endgame**, and DIS doesn't block it — section fns that become registered queries later subtract load from the pod without changing the substrate. But as the MVP path it means porting every db-value consumer first (weeks, not days). **Park it as the named migration path; don't gate the demo on it.**

Note the synergy rather than rivalry: under DIS the JVM still does the heavy lifting it's good at (serialization of writes, durability, GC, reactive re-evaluation for PUSH use-cases), while reads that are cheap-and-local (lazy node fetch over warm LRU) stay on the pod. "Heavy lifting on the JVM" ≠ "every read is an RPC".

## Q5 — Read-your-own-writes, per option

- **DIS (`:seon-wire` writer): automatic.** Chain: pod `transact!` → wire op → JVM `d/transact` → LocalWriter commit loop runs `w/commit!` (flush nodes + root to store) and only THEN delivers the callback (`writer.cljc:108-134`) → wire handler builds the reply from the resolved report (`wire.clj:353-373`, reply already carries `:basis-t`, `:tempids`, datom counts) → pod promise resolves → next `@conn` re-reads the root, which is ALREADY the post-tx root. Straight-line `transact!`-then-`query` agent code (e.g. `start-session!`) just works. Tempids: served by the JVM, returned in the ack — also already on the wire.
- **Tx-feed replica: must be built** (apply own ack datoms before resolving, or basis-t sync barrier) — sibling research §1.2 item 1.
- **Query-subscriptions-only: must be built** (every read RPC needs `:min-basis-t` ≥ the last ack'd tx, or the server pins reads to the writer thread's post-commit db).

One DIS nuance to verify in the probe, not assume: the ack returns when the JVM's `d/transact` future resolves; datahike's commit loop may BATCH multiple queued txs into one root flush (`writer.cljc:111`), but callbacks are delivered per-tx only after the flush covering them — so the invariant holds. The probe's step 3 (transact on JVM, deref on Node, compare `:max-tx`) falsifies this directly.

## Q6 — Recommendation: the leverage-first architecture

**Reused as-is (no changes):**

- JVM wire-server = the single writer on the `:file` cluster store (`server/store.clj` config; `wire.clj` `transact`/`transact-batch` ops with basis-t + tempids in the ack).
- Datahike fork internals: `deref-conn` non-streaming follow (`connector.cljc:69-78`), `stored->db`, `CachedStorage` lazy LRU restore, cross-platform fressian handlers, `connections` registry, sync Node filestore.
- Existing `subscribe-tx`/`next-tx-event` feed (`boot.clj:87-130`) — repurposed from "replication channel" to "change NOTIFICATION channel" (its natural size: events trigger refresh/listeners; datoms in the event are a convenience, not the source of truth).
- The pod's entire read API: `seon.db` over db VALUES — d/q, d/datoms, d/pull, entity, d/filter closures — untouched.
- The reactive query-subscription layer (`reactive.clj`) stays for push-rendered surfaces (inspector, future off-pod consumers); not on the agent read path yet.

**The ONE new piece: `:seon-wire` writer backend, pod-side (~40 lines CLJS + ~30-line listener adapter).**

```clojure
;; shape, mirroring http/writer.clj
(defrecord SeonWireWriter [wire-client conn]
  w/PWriter
  (-dispatch! [_ {:keys [op args]}]   ; 'transact! → wire "transact" op
    (let [p (promise-chan)] (-> (wire/transact! wire-client ...) (.then #(put! p (ack->tx-report %)))) p))
  (-shutdown [_])
  (-streaming? [_] false))            ; ← flips deref-conn into follow-the-store mode
(defmethod w/create-writer :seon-wire [cfg conn] (->SeonWireWriter ... conn))
(defmethod connector/-connect* :seon-wire [config opts] (connector/-connect-impl* config opts))
```

Pod connects with `{:store {:backend :file :path "data/clusters/default/store" :id <store-id>} :keep-history? true :schema-flexibility :write :writer {:backend :seon-wire}}` (+ `:allow-unsafe-config true` if the stored-config comparison objects). The listener adapter: `subscribe-tx` events whose origin ≠ this pod fire the conn's `listen!` callbacks (own txs already fire in `writer/transact!`, `writer.cljc:247`); event payload already carries datoms + basis-t for handler inputs.

**Ordered slices (MVP timeline, days):**

1. **Probe (≤1 day, falsifies everything load-bearing):** standalone Node script with the fork + a stub non-streaming writer (`-streaming? false`, `-dispatch!` throws): open `data/clusters/<probe>/store` written by a JVM REPL; `@conn` + `d/q` sees JVM datoms; JVM transacts; Node derefs again and sees the new datom + new `:max-tx`; `d/datoms`/`d/filter` walk works sync. Failure modes to watch: `version-check` raise (fix: run JVM on fork sha), `config-does-not-match-stored-db` (fix: `:allow-unsafe-config`), fressian read error on any blob (would falsify Q2 — escalate, don't patch).
2. `SeonWireWriter` + connect path in the pod; route `seon.db/transact!` writes through it; delete the pod-local agent store for the substrate DB (one shared DB, per the sibling research's shared-substrate recommendation).
3. Feed→listen! adapter + a deref-refresh policy (deref per render/turn is fine; optionally cache the db value keyed by root `k/get` result between feed events to skip redundant `stored->db`).
4. Align JVM writer deps to the fork sha; document the no-GC-on-cluster-store policy.

**Pivot triggers (per the provisional-choices rule):** if the probe shows fressian/layout incompatibility on real cluster-store blobs, or sync `k/get` latency makes context assembly visibly slow (measure: it's one `readFileSync` of ~node-size per cold node, LRU-warm after), fall back to the already-approved 2.2b tx-feed replica — and if agents go off-machine, the migration path is option 4 (sections as registered queries), not snapshots.

## Smell reports (out of scope, flagged)

- `deps.edn` pins JVM `:writer` alias datahike at mvn `0.8.1671` while the pod runs the fork past `0.8.1681` — works only because reader-newer is the permitted skew direction; should converge on the fork sha regardless of this design.
- `ensure-stored-config-consistency`'s remote-writer allowance condition reads `(if-not (= dc/self-writer config) ...)` (`connector.cljc:135`) — comparing the WHOLE config to the writer default looks like an upstream bug (probably meant `(:writer config)`); harmless for us via `:allow-unsafe-config`, but worth an upstream note if the probe trips on it.
