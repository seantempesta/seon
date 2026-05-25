---
type: research
status: active
tags: [research, agent, database]
---

# Kabel + datahike replication vs sidecar-poc protocol

Date: 2026-05-24
Author: research agent (Opus 4.7 1M)
Scope: validate that `pod-host/sidecar-poc/`'s wire protocol covers the semantic ground that datahike's first-party cross-runtime replication (the `:kabel` writer + `konserve-sync` + `kabel.pubsub`) already covers, before committing to Phase 4-7 + migration.

## 0. TL;DR

- **We are NOT reinventing kabel.** Kabel is a generic peer/pubsub/RPC transport (WebSocket + middleware), not a database protocol. Datahike layers three separate concerns on top of kabel: (a) RPC for `transact!/create-database/delete-database` via `distributed-scope`, (b) **store replication** via `konserve-sync` (the connector's reader keeps a **local konserve copy** of the server's store), (c) **tx-report pubsub** via `kabel.pubsub` topic `:tx-report/scope-<store-id>`. Each maps to a separate sidecar concern — request socket, snapshot cache, pub socket.
- **`d/listen!` is a pure local atom-watch.** `datahike.core/listen!` stores the callback in `(:listeners (meta conn))`; the JVM `LocalWriter` commit loop and `KabelWriter` `finalize-and-return!` both walk that atom and call each callback with the live `tx-report`. There is no cross-process listen primitive in datahike — kabel + tx-broadcast fans out a wire payload, then each peer's local connection rebuilds a tx-report and fires local listeners. Our sidecar's `pub-socket → Rust broadcast → guest on-tx` chain is the same pattern, just over UDS+CBOR instead of WS+Fressian.
- **We DO have a real semantic gap: db-before/db-after.** Datahike's KabelWriter ships the full tx-report with `db-before` + `db-after` over the wire (serialized as `db->stored` maps, plus deferred-index reconstruction on the reader). Our pub event is `{event, basis-t, datoms-added, datoms-retracted}` — **no tx-data, no datoms, no db-after**. A guest receiving our event cannot replay it locally; it must re-query. For our current "JVM is the only DB, guests are dumb clients" model this is fine. **If we ever want a local materialised cache in the guest (`d/listen!`-style with `:db-after` queries), this is what we must add.**
- **Konserve-sync is the load-bearing piece we have no analog for.** Kabel replication only works because konserve-sync replicates the actual *store* (immutable index pages) under the tx-broadcast. The reader can call `dw/stored->db` and do queries locally. We don't replicate; we forward queries to the JVM. **This is by design** (per `datahike-wasm-writer-split-2026-05-24.md` §6.2), and our `SnapshotCache` is a different shape (per-query response cache, not per-page store mirror). Adopting konserve-sync would mean accepting a full konserve port to wasm/Rust, which is huge.
- **Recommendation: stay independent.** Our protocol is a deliberate, minimal subset suitable for a centralised JVM-writer + thin-guest topology. Kabel/konserve-sync target a peer-to-peer-ish replicated topology that does not fit a wasm sandbox. Adopt kabel only if/when we add a *second* JVM writer or want guests with offline-capable local stores. **Do** absorb three discrete semantic improvements (§6.3): request-id dedup on the pub stream, schema-altering-tx invalidation, and basis-t monotonicity asserts.

---

## 1. What kabel actually is

Kabel itself is not in `reference-code/`; the datahike repo embeds five `.cljc` files at `reference-code/datahike/src-kabel/datahike/kabel/` (commit `717a0d2`) which call into `kabel.peer`, `kabel.pubsub`, `kabel.middleware.fressian`, and `is.simm.distributed-scope`. From those call sites the model is reconstructable:

**Kabel = a peer middleware stack over WebSockets.** Construction (`tx_broadcast.cljc:12-13`, `connector.cljc:14-15`, `kabel.peer`):

```clojure
(peer/server-peer S http-kit-handler server-id
                  (comp (sync/server-middleware) remote-middleware)
                  datahike-fressian-middleware)
```

A peer is an atom containing:

- An ID (UUID).
- A WebSocket connection (server or client).
- A composed middleware stack. Each middleware is `(fn [peer-cfg] (fn [hare-msg next-mw] ...))` — message goes through every layer in/out.
- A pubsub registry: topic → strategy. `pub-sub-only-strategy` is "no handshake, just deliver each publish to subscribers" (tx_broadcast.cljc:51, 109).

**On the wire:** Fressian-encoded EDN-ish values, wrapped in WebSocket binary frames. Datahike supplies type handlers for `PersistentSortedSet/Leaf/Branch`, `Datom`, `DB`, `TxReport` (`fressian_handlers.cljc:124-340`). DB and TxReport are encoded via `dw/db->stored` — i.e. **the live DB is replaced with its on-disk konserve shape**, with index trees replaced by `{:deferred-type :persistent-sorted-set, :meta..., :address..., :count...}` (`fressian_handlers.cljc:130-145`). The reader looks up the local konserve store in a registry by `(:id store-config)` and either reconstructs the BTSet from the address (CLJS path) or returns deferred for later reconstruction (CLJ path) — see lines 187-222.

**Three composable concerns on top:**

1. **`kabel.pubsub`** — topic publish/subscribe. `register-topic!`, `publish!`, `subscribe!`, `unsubscribe!`. Used by datahike for tx-reports (`tx_broadcast.cljc:71-92`).
2. **`distributed-scope` (`is.simm.distributed-scope`)** — request/response RPC over the same peer. `register-remote-fn!` server side, `invoke-remote peer-id sym arg-map` client side. Returns a channel. Used by datahike for `dispatch / create-database / delete-database` (`handlers.cljc:266-268`, `writer.cljc:89-92`).
3. **`konserve-sync`** — replicates a konserve store key-by-key. `register-store! peer store-id store opts` server side, `subscribe-store! peer store-id store {:on-key-update ...}` client side (`connector.cljc:188-206`). Uses kabel's middleware (`sync/server-middleware`, `sync/client-middleware`) to ride the same WebSocket. The reader's local konserve is a near-copy of the server's; index pages are referenced by konserve UUID and lazily fetched on demand.

There is no built-in causal ordering, retry, or durable subscription in kabel itself — those are layered (or absent). Pub-sub-only-strategy means **late subscribers don't see history** — they only get publishes after they subscribe (`tx_broadcast.cljc:9-11`).

## 2. How datahike uses kabel

The `:kabel` writer is registered via `defmethod writer/create-writer :kabel` in `writer.cljc:281-285`. The writer-backend wires the three kabel layers together:

| Layer | Server (database owner) | Client (peer) |
|---|---|---|
| RPC (`distributed-scope`) | `register-global-handlers!` registers `'datahike.kabel/dispatch`, `'create-database`, `'delete-database` (`handlers.cljc:233-270`) | `KabelWriter -dispatch!` calls `(ds/invoke-remote peer-id 'datahike.kabel/dispatch {:store-id, :arg-map})` (`writer.cljc:89-92`) |
| Store replication (`konserve-sync`) | `sync/register-store! peer store-id store {:walk-fn dh-walker/datahike-walk-fn, :key-sort-fn (fn [k] (if (= k :db) 1 0))}` (`handlers.cljc:176-179`) — `:db` key sorted last so meta+index pages flush before the root pointer | `kp/subscribe-store! local-peer store-topic store {:on-key-update ...}` (`connector.cljc:188-206`); on `:db` key updates calls `kw/on-db-sync!` which reconstructs deferred indexes, builds a live DB via `dw/stored->db`, and `reset!`s `(:wrapped-atom conn)` |
| Tx-report pubsub | `tx-broadcast/publish-tx-report! peer store-id tx-report request-id` after each successful transact (`handlers.cljc:98-99`) | `tx-broadcast/subscribe-tx-reports! peer store-id (fn [{:keys [tx-report request-id]}] ...)`; deduplicated by `request-id` against the local pending set (`tx_broadcast.cljc:135-155`) |

**Tx flow for a remote client:**

1. Client calls `d/transact! conn ...`. `datahike.core/transact!` finds `:writer` in the connection and calls `(writer/dispatch! writer {:op 'transact! :args [arg-map]})` (`writer.cljc:226-250`).
2. `KabelWriter -dispatch!`: `ds/invoke-remote peer-id 'datahike.kabel/dispatch {:store-id, :arg-map}` (writer.cljc:89-92). Returns the full tx-report over the wire (Fressian-encoded — DB-before/DB-after as `db->stored` maps with deferred indexes).
3. **Two-phase return:** the writer holds the tx-report in `:pending-txs` keyed by `expected-max-tx`. It releases only after `konserve-sync` delivers the new `:db` key (writer.cljc:99-131). So `d/transact!` blocks until the *store* has replicated, not just the RPC.
4. When sync arrives: `kw/on-db-sync!` reconstructs the live DB and updates `(:wrapped-atom conn)` (writer.cljc:204-238). Then `finalize-and-return!` reconstructs the tx-report's `db-before/db-after` via the local store, fires local listeners (`@listeners` set, writer.cljc:115-119), and resolves the result channel.
5. Tx-broadcast also fires concurrently. The client deduplicates its own tx via `request-id` (`tx_broadcast.cljc:144-155`). **Other clients** receive the tx-report via the pubsub topic, reconstruct local DBs from their own konserve replicas, and fire their own `d/listen!` callbacks.

The architecture is a **two-stream replication**: pubsub carries tx-reports (semantic deltas), konserve-sync carries store pages (concrete bytes). Pubsub is fast and live; konserve-sync is authoritative. The writer waits for both.

## 3. `d/listen!` anatomy in datahike-jvm

`datahike.core/listen!` is a pure local atom watch (`core.cljc:206-217`):

```clojure
(defn listen!
  ([conn callback] (listen! conn (rand) callback))
  ([conn key callback]
   {:pre [(conn? conn) (atom? (:listeners (meta conn)))]}
   (swap! (:listeners (meta conn)) assoc key callback)
   key))
```

The `:listeners` atom lives in `(meta conn)` and is created in `->Connection (atom db :meta {:listeners (atom {})})` (`connector.cljc:84`, `kabel/connector.cljc:251`).

**Where the callback fires:**

- `:self` (local) writer: in `datahike.writer/transact!` after dispatch returns, before delivering the promise (`writer.cljc:247-248`):
  ```clojure
  (doseq [[_ callback] (some-> (:listeners (meta connection)) (deref))]
    (callback tx-report))
  ```
  Same pattern in `merge-db!` (`writer.cljc:274-275`). Listeners receive the **live** tx-report (live `db-before` + `db-after` because the writer is in-process).
- `:kabel` (remote) writer: in `KabelWriter -dispatch!`, the `finalize-and-return!` closure (writer.cljc:115-121):
  ```clojure
  (doseq [callback @listeners]
    (try (callback final-tx-report)
      (catch ... e (log/error "Error in listen! callback" e))))
  ```
  Note: this listener set is on the **writer** (`KabelWriter.listeners`), not the connection's `(:listeners (meta conn))`. **This is asymmetric with the local writer.** A caller doing `(d/listen! conn ...)` against a KabelWriter would set the *conn meta* atom, but the writer fires the *writer* atom — so the canonical `d/listen!` doesn't fire on a `:kabel` writer unless someone bridges them. There's `add-listener!`/`remove-listener!` helpers in `kabel/writer.cljc:244-253` but they're not called from `datahike.core/listen!`. **This looks like an open bug or intentional split — flagged in §7.**
- `:datahike-server` HTTP RPC: `listen!` is **explicitly unsupported**. From `doc/distributed.md:250`: *"All functionality except `listen!` and `with` is supported"*. The HTTP server has no push channel; clients would have to poll.

**Tx-report shape** (Datahike, ServerSide, in-memory): `{:db-before <DB>, :db-after <DB>, :tx-data [Datom...], :tempids {tempid -> eid}, :tx-meta {:db/txInstant ..., :db/commitId ...}}` (cf. `doc/distributed.md:401-510` for the wire shape). Over kabel, `db-before`/`db-after` are converted via `dw/db->stored` (fressian_handlers.cljc:283-290) — index trees become deferred-address stubs; readers reconstruct via the local konserve store.

## 4. Sidecar protocol anatomy (recap)

Two UDS sockets between Rust host (or smoke client) and JVM writer (PROTOCOL.md):

| Socket | Direction | Frames |
|---|---|---|
| `seon-poc-req.sock` | client → writer, req/resp | length-prefixed CBOR, one req → one resp |
| `seon-poc-pub.sock` | writer → all subscribers | length-prefixed CBOR, server-push |

- Request ops: `ping`, `q`, `transact`, `pull`, `schema`. EDN-string-in, structured-out (see PROTOCOL.md:88-98 — "deliberate PoC simplification").
- Pub event: `{event "tx", basis-t N, datoms-added N, datoms-retracted N}` (writer.clj:136-139, broadcast.clj:17-26). No tx-data, no datoms, no db-before/db-after.
- Rust host: one broadcast subscriber rebroadcasts onto `tokio::sync::broadcast<TxEvent>`; `SnapshotCache` invalidates by basis-t monotonicity (main.rs:147-182, 242-251).
- wasm guest gets a host-import `subscribe-tx` callback via WIT (Phase 3 stub; Phase 4 fans events into the guest).

## 5. Side-by-side semantic comparison

| Feature | Kabel + datahike | Sidecar PoC | Notes |
|---|---|---|---|
| Transport | WebSocket (kabel) | Unix domain socket (length-framed) | UDS is faster + simpler; loses cross-host. Acceptable: our deployment is one host. |
| Serialization | Fressian + Datahike type handlers | CBOR + ad-hoc walker `cbor-safe` (writer.clj:91-111) | CBOR is interoperable; we lose Datom/DB native shapes — they arrive as `[e a v tx added]` vectors and EDN-printed strings respectively. ✅ for our model (guest re-queries; doesn't need a local DB), ⚠️ if we ever want guest-side index reconstruction. |
| RPC request/response | `distributed-scope` over kabel middleware | Direct req/resp on UDS, one in-flight per conn | ✅ covered (and simpler). |
| Transactor model | `:kabel` writer → server `:self` writer (`handlers.cljc:160`); JVM-side transactor unchanged | JVM writer directly uses `d/transact` on the conn | ✅ same shape. |
| Multi-tenant database routing | `store-registry` map `store-id → {:conn, :peer}`, looked up per request (`handlers.cljc:45-65, 91`) | Single connection in `state` atom (writer.clj:24, 208) | ❌ single-DB only. Easy to extend if we ever need it. |
| Pub/sub for tx-reports | `kabel.pubsub` topic `:tx-report/scope-<store-id>`, `pub-sub-only-strategy` (no replay) | Pub UDS socket, all subscribers see all events (broadcast.clj:17-26) | ✅ same semantics. Neither replays history; late subscribers start from "now". |
| Tx-report contents | Full: `:db-before`, `:db-after`, `:tx-data` (all datoms), `:tempids`, `:tx-meta {:db/txInstant, :db/commitId}` | Minimal: `{event, basis-t, datoms-added, datoms-retracted}` | ❌ **MAJOR GAP** if we want listener-driven local materialisation. ✅ sufficient for cache-invalidation. |
| Request-id deduplication on pub stream | `make-tx-report-handler` filters by `request-id ∈ @pending-request-ids` (`tx_broadcast.cljc:135-155`) — own-tx skipped, dispatched-via-RPC only | None | ⚠️ when a guest both submits a tx AND subscribes, it sees its own commit twice (once via the response, once via the pub event). For cache invalidation that's a no-op; for `d/listen!` semantics it would double-fire. **Add this when guests do listen.** |
| `d/listen!` callbacks at the writer | `KabelWriter.listeners` atom fires inside `finalize-and-return!` (`kabel/writer.cljc:114-119`) | Out of scope for the writer; Rust host does its own broadcast subscribe; wasm guest does Phase-4 fanout | ✅ structurally equivalent — both are local callbacks fed by a wire-delivered tx event. |
| `d/listen!` callbacks at the connection (meta atom) | `(swap! (:listeners (meta conn)) ...)` — fires in `datahike.core/transact!` for the local writer (`writer.cljc:247-248`); **does not fire for the kabel writer** | N/A — guests don't hold a Datahike connection, they hold a WriterClient handle | ⚠️ kabel-side appears to have a split (writer-side vs conn-meta listeners) that may be a bug; not our problem. |
| Causal ordering across reconnects | Basis-t monotonic by construction (single writer); konserve-sync handshake compares timestamps and resends only newer keys (`connector.cljc:67-77`, `populate-tiered-from-cache!`) | basis-t monotonic by construction; Rust host's `high_water` atomic enforces monotonicity for cache (main.rs:201, 242-251); **no reconnect catch-up** — on disconnect, missed events are lost | ⚠️ if a Rust host or guest disconnects from the pub socket and reconnects, missed tx events between t1 and t2 are not redelivered. Mitigation: any cache-invalidation listener should also drop the cache on reconnect (defensive). |
| Catch-up after disconnection | Konserve-sync handshake re-syncs missed pages on reconnect (the konserve key timestamps are the source of truth) | None — pub socket reconnect is lossy | ⚠️ same as above. |
| Schema delivery / schema-altering tx | Schema is part of the DB; `dw/stored->db` reconstructs it on each sync (connector.cljc:248); when schema changes, the new `:db` key has the new schema and `on-db-sync!` does `dq/propagate-query-cache current-state live-db modified-attrs` to selectively invalidate (`kabel/writer.cljc:222-227`) | JVM writer holds canonical schema; Rust cache is keyed by request-CBOR bytes and dropped wholesale on basis-t bump. wasm guest re-queries; no local schema cache | ✅ for our cache (wholesale drop is correct on any tx including schema). ⚠️ if guests ever cache schema. |
| Branch awareness | Connector reads `:branch` from config (defaults `:db`), subscribes specifically to that branch's key (`connector.cljc:117, 191-206`) | Single hard-coded branch (`:db`) implicit in `d/transact` | ❌ no branch support. Not a Phase-4 requirement per the PRD. Add when needed. |
| Reconnect to writer | Connector retries via `is-tiered?` path with cached `:db` from IndexedDB (`connector.cljc:170-176`) | Rust host's `wait_for_socket` poll-then-connect with 60s timeout (main.rs:710-723); no auto-reconnect after established | ⚠️ writer restart = host crash. Phase-2 README notes "process supervision" as a follow-up. |
| Per-attribute listeners | Not in datahike (`listen!` is whole-conn) | Not in sidecar | ✅ symmetric. |
| Multi-branch listeners | Each conn is one branch; one listener atom per conn | N/A | ✅ symmetric. |
| tx-meta delivery | `:tx-meta` included in tx-report (`db/txInstant`, `db/commitId`) | Not delivered (writer.clj:142 sends `tempids` only) | ❌ missing. `tempids` we do send; `db/txInstant`/`db/commitId` we don't. **Add when guests need them.** |
| Tempids back to client | Yes (`tempids` in tx-report) | Yes (`tempids` in transact response, writer.clj:142) | ✅. |
| db-before / db-after in tx-report | Yes — full DB, serialized as stored format | No — only `basis-t` integer | ❌ **MAJOR GAP** as above. |
| Store replication to clients | Yes — `konserve-sync` replicates all reachable konserve pages reachable from `:db`. Clients can run queries locally. | No — clients re-query the JVM (with a Rust-side response cache for repeated reads) | **DELIBERATE.** Per the wasm-writer-split research, we explicitly chose centralised reads over a wasm konserve port. |

Legend: ✅ covered, ⚠️ partial / gotcha, ❌ missing.

## 6. Verdict

### 6.1 Can we replace our protocol with kabel?

**Mechanically, no.** Kabel requires:

- A WebSocket transport (not UDS). Wrapping kabel over UDS would mean re-implementing the WS framing or running a localhost WS — both extra cost vs raw UDS for no semantic gain.
- Fressian. We use CBOR by design (interop with Rust `ciborium`, Python `pydatahike`, and the WIT bridge which prefers binary). Fressian on the wasm side would need a CLJS fressian build + Datahike type handlers compiled to wasm.
- A full `kabel.peer` + middleware stack in every participant. The Rust host doesn't host a kabel peer; it would have to embed one (Java-side) or speak the kabel-pubsub wire format (not standardised outside the Clojure impl).
- Konserve-sync to do the heavy lifting. Without it the kabel writer can't work (the connector waits for initial sync at `connector.cljc:217-220` and reconstructs DBs from the local konserve store).

The reader-side replication (konserve-sync) is the load-bearing piece, and it requires the participant to embed konserve. That's a non-starter for wasm guests we want to stay thin.

### 6.2 Should we?

**No, not for the current topology.** The sidecar-poc target is: one JVM writer, N thin wasm guests, no local DB in the guest. Kabel's architecture targets: one (or many) JVM/JS peers, each with a local konserve store, peer-to-peer-ish syncing. The semantics overlap, the implementations don't.

The only scenarios where kabel adoption would pay off:

1. Cross-host deployment (guests on a different machine than the writer) — kabel's WS transport solves this; our UDS doesn't.
2. Offline-capable guests with local query — needs konserve replication anyway.
3. Multiple JVM writers federated across a fleet — kabel's peer-to-peer is the right primitive.

None of those are on the agent-runtime roadmap.

### 6.3 If not, what semantics must we add to match kabel's coverage?

Concrete patch list against the sidecar-poc, ranked by importance for Phase 4-7:

1. **Request-id on pub events** (for `d/listen!` semantics in guests). When a guest's transact returns, the writer should include a `request-id` in both the response and the corresponding pub event. The Rust host or guest can suppress its own publish so the guest's listener doesn't double-fire. This mirrors `tx_broadcast.cljc:144-155`. Wire change: add `request-id` to the transact request (client-generated UUID), echo it in response, include it in the pub event. Small change.
2. **`tx-data` (datoms) on the pub event** (for guests doing meaningful work in listeners). Currently we ship counts; ship the actual datom vector. Without this, a listener can only invalidate caches, not react to specific changes. CBOR encoding of the datoms is straightforward — we already CBOR the query-result datoms via `cbor-safe`. Wire change: add `"tx-data": [[e a v tx added] ...]` to the pub event. Medium-size diff.
3. **`tx-meta` on the pub event** (for `:db/txInstant`, `:db/commitId`). Cheap. Same place as (2). Useful for any time-based or audit-trail listener logic.
4. **Catch-up on subscribe** (the kabel gap we'd actively close). Kabel's pub-sub-only-strategy doesn't replay either, but konserve-sync covers it for them. We can offer an explicit `{op: "tx-log-since", basis-t: N}` request that returns all txes from `N+1` to current; subscribers call this on (re)connect. Datahike has `d/tx-range` (`api.cljc`) that gives this. Medium diff, big robustness payoff.
5. **Reconnect/resubscribe in the Rust host.** Wrap the pub-socket connection in a retry loop. On reconnect, call (4) to catch up. Then guests don't need to know reconnects happen.
6. **`db-before`/`db-after` basis-t (not the full DB) on the pub event.** Kabel ships full DBs because the reader has a local store to reconstruct from. We don't. But shipping the `:db-before`/`:db-after` `basis-t` (two integers) lets a guest correlate tx events against query basis-t for stale-read detection. Cheap.
7. **Schema-altering tx flag** on the pub event. When the tx changes `:db/ident`/`:db/valueType`/etc., flag it so caches with attribute-shape assumptions can do full clears. Datahike does the equivalent in `kabel/writer.cljc:222-227` via `propagate-query-cache modified-attrs`. Small diff.
8. **Multi-DB routing.** Add `store-id` to every request and pub event; route at the writer via a registry. Mirrors `handlers.cljc:91-106`. Defer until we actually need a second DB.
9. **Branch awareness.** Defer until needed.

Patches 1, 2, 3 are obvious wins and small. Patch 4 is the highest-robustness investment. Patches 5-7 are quality-of-life. 8-9 are deferred.

## 7. Open questions / didn't-have-time-to-verify

- **Is the `KabelWriter.listeners` atom an asymmetry bug?** `datahike.core/listen!` writes to `(:listeners (meta conn))`, but `KabelWriter -dispatch!` reads from `@listeners` (writer-local). I see `kw/add-listener!`/`remove-listener!` helpers but no call site that bridges `core/listen!` → `add-listener!`. Either there's an indirection I missed (maybe in the `kabel/connector.cljc` post-`->Connection` block — but I didn't see it), or `d/listen!` against a kabel-backed connection is silently a no-op. Worth flagging upstream if confirmed; not our problem.
- **Konserve-sync wire format.** I didn't read konserve-sync source (not on disk). The Fressian handlers tell us the *payload* shape but not the *protocol* (how keys are announced, how diffs are computed, handshake timestamps). For our purposes the answer "it replicates konserve key-by-key with timestamps" is sufficient.
- **`pydatahike` CBOR conventions.** We claimed in PROTOCOL.md:30 that we follow pydatahike's CBOR conventions. I didn't verify against `reference-code/datahike/pydatahike/` source; if we ever want JVM↔Python interop this is worth a re-read.
- **Tx-broadcast `pending-request-ids` lifetime.** The dedup set grows unboundedly unless something prunes it. Kabel's handler `swap!`s the id off (`tx_broadcast.cljc:148`), so the publish-after-RPC must arrive after the RPC return. If the order ever inverts the dedup leaks. For our sidecar with strict commit→publish ordering (broadcast.clj called inside the transact response path, writer.clj:140) this is fine, but worth a comment if we add patch (1) above.

## 8. References

Datahike (commit `717a0d2`, branch `main`):

- `reference-code/datahike/src-kabel/datahike/kabel/writer.cljc:71-285` — KabelWriter record, dispatch, on-db-sync, listener management, defmethod create-writer :kabel
- `reference-code/datahike/src-kabel/datahike/kabel/tx_broadcast.cljc:24-155` — tx-report topic naming, publish/subscribe, request-id dedup
- `reference-code/datahike/src-kabel/datahike/kabel/handlers.cljc:45-348` — store-registry, global-dispatch-handler, register-global-handlers!, register-store-for-remote-access!
- `reference-code/datahike/src-kabel/datahike/kabel/connector.cljc:83-289` — connect-kabel flow: store create → konserve-sync subscribe → initial sync wait → conn creation → writer wireup → ongoing sync handler
- `reference-code/datahike/src-kabel/datahike/kabel/fressian_handlers.cljc:124-340` — read/write handlers for PersistentSortedSet/Leaf/Branch/Datom/DB/TxReport over kabel
- `reference-code/datahike/src/datahike/core.cljc:206-224` — `listen!`/`unlisten!` — pure local atom watch on `(:listeners (meta conn))`
- `reference-code/datahike/src/datahike/writer.cljc:15-285` — PWriter protocol, LocalWriter, default-write-fn-map, create-writer multimethod, `transact!` (which fires conn-meta listeners after dispatch)
- `reference-code/datahike/src/datahike/connector.cljc:84` — `->Connection (atom db :meta {:listeners (atom {})})`
- `reference-code/datahike/doc/distributed.md` — narrative description of DIS, RPC, kabel streaming writer, HTTP server (note line 250: HTTP `:remote-peer` does not support `listen!`)

Sidecar PoC:

- `pod-host/sidecar-poc/PROTOCOL.md` — full wire format
- `pod-host/sidecar-poc/jvm-writer/src/seon/sidecar/writer.clj:113-200` — request handlers, transact path, pub broadcast call
- `pod-host/sidecar-poc/jvm-writer/src/seon/sidecar/broadcast.clj:17-51` — pub-socket fanout
- `pod-host/sidecar-poc/rust-host/src/main.rs:147-251` — pub subscriber, TxEvent, SnapshotCache invalidation
- `pod-host/sidecar-poc/README.md` — phase status (P1-P3 GREEN; P4 listen equivalence pending)

Parent research:

- `docs/prds/agent-runtime/research/datahike-wasm-writer-split-2026-05-24.md` §6.2-§6.3 — Gemini consultations that originally raised kabel and the writer-split decision
