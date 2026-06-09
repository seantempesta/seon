---
type: research
status: active
tags: [research, database, agent]
---

# Upstream datahike KabelWriter (websocket streaming writer) vs the fork's DIS + custom PWriter (2026-06-09)

Companion to [[datahike-native-replica-2026-06-09]] (read that first — its DIS analysis is the baseline this doc scores against). Trigger: the user found upstream's `doc/cljs-support.md` KabelWriter section and asked whether the official streaming/websocket stack beats the prior recommendation (fork + custom non-streaming `:seon-wire` PWriter + shared file store).

## TL;DR

- **VERDICT: keep the prior recommendation (DIS + `:seon-wire` non-streaming writer) for the MVP. Kabel does NOT replace it — and the "fork vs upstream" framing dissolves on inspection: the fork IS upstream main (common base `015fb2a5`, fork adds only 3 small CLJS/build commits; upstream HEAD `717a0d27` adds 3 unrelated pydatahike/libdatahike commits). The entire kabel stack (`src-kabel/datahike/kabel/{writer,connector,handlers,tx_broadcast,fressian_handlers}.cljc`, 1470 lines) is ALREADY IN OUR FORK** — it's just not on the default classpath (`:test`-alias `extra-paths` only, `deps.edn:54`) and needs 3 extra alpha-stage deps (kabel 0.3.95, konserve-sync 0.1.15, distributed-scope 0.1.2, `deps.edn:78-81`). There is no migration cost and no "upstream superset": same store format, same CLJS coverage (fork's is a strict superset by one Promise-wrap commit), zero Seon API breakage either way.
- **Memory-model answer (the decisive one): kabel clients replicate the FULL DB, not a working set.** konserve-sync's datahike walker collects EVERY reachable BTSet node address from the `:db` root (`konserve-sync/walkers/datahike.cljc`, recursive `walk-node-async`) and the initial handshake transfers all of them into the client's LOCAL store; thereafter differential (timestamp-based, only changed nodes — `konserve_sync/core.cljc:141-142`). With the documented TieredStore (memory frontend), client RAM ∝ full DB; with a `:file` local tier (the CLJ integration test's client shape, `test/datahike/kabel/integration_test.clj:143`), client DISK ∝ full DB and RAM ∝ LRU. Either way every agent holds and receives a full copy + every update. DIS on a shared store: ONE disk copy, per-agent RAM ∝ LRU working set (`:store-cache-size`, default 1000 nodes), zero replication traffic. **For hundreds of same-machine agents, DIS dominates; kabel's replication model fits a FEW fat replicas (offline-capable laptop pod), not many thin agents.**
- **Node-compat verdict: transport yes, stack untested.** kabel's CLJS client explicitly supports Node (`kabel/client.cljs:11-13` patches `js/WebSocket` from the `websocket` npm package — which Seon does NOT currently have in package.json). The kabel connector supports a non-tiered local store (`connector.cljc:157-159`), so a Node client could use konserve-node-filestore as its local tier. BUT upstream's kabel CLJS tests are BROWSER-ONLY (`shadow-cljs.edn:24,34` — `browser-integration-test` via karma; the `:node-test` build excludes kabel), the feature is labeled beta (`distributed.md:77`), and external check found no Node production reports. Kabel-on-Node would be us pioneering.
- **`datahike.optimistic` DOES NOT EXIST** at this commit (no such namespace anywhere in fork or upstream; grep over `src/`, `src-kabel/`, `test/`). The cljs-support.md framing that reached us overstated; RYOW under kabel is instead handled inside KabelWriter: `-dispatch!` RPCs the tx and then BLOCKS until konserve-sync has replicated up to the expected `max-tx` before resolving (`src-kabel/datahike/kabel/writer.cljc:97-131`). So RYOW is built-in on BOTH paths (kabel: wait-for-sync; DIS: commit-before-ack + root re-read).
- **Sync reads survive under kabel** — `-streaming? true` (`writer.cljc:137-139`) means `@conn` is a plain atom deref; konserve-sync's `on-key-update` pushes each new stored-db into the conn's wrapped-atom via `on-db-sync!` (`writer.cljc:190-238`, `connector.cljc:188-206`). Queries, `d/datoms`, `d/filter` closures all run locally and synchronously against the replicated store. Both candidates preserve our read model.
- **Auth/internet story: kabel has NONE today.** The global dispatch handler trusts any peer that knows the store-id (`handlers.cljc:78-107`); the token auth in distributed.md applies to the HTTP server only. Internet-reachable agents over kabel = wss termination + an auth middleware we'd have to write. So "websockets!" does not buy a finished remote story — it buys a transport.
- **Recommendation in 3 lines:** (1) MVP unchanged — DIS + `:seon-wire` over the existing UDS wire (the in-flight 2.2c probe validates exactly this; its format-compat findings are 100% still load-bearing). (2) When agents go OFF-MACHINE: few-fat-replicas → adopt the kabel stack already sitting in our fork (add `src-kabel` to paths + 3 deps + `websocket` npm + a kabel server-peer in the JVM + auth middleware); many-thin-remote-agents → query-subscriptions/RPC (prior research's option 4), because full-DB replication per thin agent is the wrong shape. (3) First slice for the winning path = finish the 2.2c probe; first slice for the kabel option (when triggered) = run upstream's own `test/datahike/kabel/integration_test.clj` under the `:test` alias, then port its client side to a Node script.

---

## Ground truth

- Vendored upstream = `reference-code/datahike` at `717a0d27` (2026-05-17). Fork the pod runs = `seantempesta/datahike@01ba3f18` in `~/.gitlibs`. Ancestry verified: `git merge-base --is-ancestor 015fb2a5 717a0d27` → true; fork log = `015fb2a5` + `f092a63a` (selective Promise wrap, new `src/datahike/api/async.cljs`) + `f6ecf173` (cold `compile-java` for git consumers) + `01ba3f18` (silence 16 CLJS analyzer warnings). `diff -rq` fork-vs-upstream `src/`: 9 files differ + `api/async.cljs` fork-only — all attributable to those 3 commits plus upstream's 3 binding commits (#829-831).
- kabel 0.3.95 and konserve-sync 0.1.15 jars inspected from `~/.m2` (extraction scratch: `tmp/kabel-inspect/`).
- One consolidated `agy` query (raw response preserved in §7).

## 1. The upstream mechanism, end-to-end (file:line)

All paths below are `reference-code/datahike` unless noted; **identical files exist in the fork**.

**Client transact path** (`src-kabel/datahike/kabel/writer.cljc`):

1. `KabelWriter.-dispatch!` (:82-135) sends `{:store-id … :arg-map {:op 'transact! :args …}}` to the server peer via distributed-scope RPC (`ds/invoke-remote`, :89-92).
2. Server's `global-dispatch-handler` (`handlers.cljc:78-107`) looks the conn up in `store-registry` by store-id, forwards to the server conn's LOCAL writer (`writer/dispatch!`, :96), then publishes the tx-report on the kabel pubsub topic `:tx-report/scope-<store-id>` (`tx_broadcast.cljc:25-33,70-`).
3. Client registers the remote tx-report under its expected `max-tx` in `pending-txs` and WAITS until konserve-sync replication catches up (`writer.cljc:99-131`) — `on-sync-update!` (:173-188) releases waiters when the synced `:max-tx` ≥ expected. **This is the RYOW mechanism** — no `datahike.optimistic` namespace exists (verified by grep; the only "optimistic" hits in the repo are unrelated).

**Replication** (konserve-sync 0.1.15, jar): `subscribe-store!` (`transport/kabel_pubsub.cljc`) subscribes the client's LOCAL konserve store to the server's store topic. Differential sync is timestamp-based per KEY (`core.cljc:141-142` "only keys where server's timestamp is newer than client's are transferred"). What the keys ARE: the branch root (`:db`) + every reachable BTSet index-node blob, discovered by the datahike walker (`walkers/datahike.cljc` — recursive `walk-node-async` collecting `.-addresses` from branch nodes). So the client store converges on a complete copy of the live index; superseded nodes are not re-sent, new/changed nodes stream in per tx.

**Conn/deref model** (`src-kabel/datahike/kabel/connector.cljc`): `connect-kabel` (:83-276) creates the local store (tiered or plain, :143-159), registers fressian handlers for BTSet address reconstruction (:164), subscribes via konserve-sync (:188-206), waits for the initial `:db` sync (:212-221), builds a live DB via `stored->db` and a normal `->Connection` (:248-251). Ongoing: every synced `:db` update flows through `kw/on-db-sync!` (`writer.cljc:190-238`) which reconstructs the live DB, propagates the query cache with selective invalidation (:217-227), and `reset!`s the conn's wrapped-atom (:233). Because `-streaming?` is `true`, `deref-conn` takes the plain-atom branch — **`@conn` is sync, push-updated, never re-reads the root**. `listen!` fires via the writer's own listener set on tx completion (`writer.cljc:114-119, 244-253`) plus the tx-broadcast topic for cross-client notification.

**Server embed** (`distributed.md:89-145`, `handlers.cljc`): the JVM hosts a kabel `server-peer` over an http-kit websocket handler on its own port, middleware stack = konserve-sync server-middleware ∘ distributed-scope remote-middleware ∘ datahike-fressian middleware; then `register-global-handlers!` (create/delete/dispatch) + `register-store-for-remote-access!` per DB. The server conn is a normal local-writer conn on a `:file` store. **No authentication anywhere in this path** — possession of server URL + store-id suffices (`handlers.cljc:78-107` has no credential check; `:token` exists only on the HTTP server, `distributed.md:295-297`).

**TieredStore** (`doc/cljs-support.md:66-80`, `connector.cljc:44-77`): memory frontend + persistent backend (IndexedDB in the docs), write-through, reads from memory. On reconnect, `populate-tiered-from-cache!` walks the branch from the persistent tier into memory BEFORE the sync handshake so timestamps are accurate and only newer keys transfer. **Resident set: the memory frontend holds every synced key for the branch — i.e., the full DB in RAM.** Tiered is not required: the connector takes any store backend (`is-tiered?` false → plain `ready-store`, :157-159), and the JVM-client integration test uses `:file` for the client store (`test/datahike/kabel/integration_test.clj:143,283`) — that shape on Node = full DB on local disk, RAM ∝ `CachedStorage` LRU.

## 2. Node-compat

- **Transport: yes.** `kabel/client.cljs:11-13` (jar): `(when (on-node?) (set! js/WebSocket (.-w3cwebsocket (js/require "websocket"))))` — Node support via the `websocket` npm package (NOT in Seon's package.json today; would need adding). Binary frames forced to ArrayBuffer on Node (:23-26).
- **Local tier: yes.** konserve-node-filestore is registered by `datahike.nodejs` (`doc/cljs-support.md:21-39`) and the pod already uses it. A Node kabel client would use `:file` (or `:memory`) as its local store — IndexedDB is irrelevant off-browser.
- **Test coverage: browser-only for kabel.** `shadow-cljs.edn` builds: `:node-test` runs `datahike.test.nodejs-test` (core CLJS, no kabel); kabel CLJS tests exist solely as `:browser-integration-test`/`:browser-ci` (karma) over `datahike.kabel.browser-integration-test`. The JVM↔JVM path is tested (`integration_test.clj`, 629 lines, includes multi-tx ordering); **kabel-client-on-Node has zero upstream test coverage**.
- core.async: the whole kabel/konserve-sync/superv stack is core.async-heavy — fine for the Node pod, but **permanently incompatible with the wasm-guest rule** (wasm guests must stay wire-only / core.async-free — the wstd timer-parking hang); if containment lands on WASM, kabel cannot be the guest's channel.

## 3. Sync-read preservation

Yes, fully, on both candidates — but via different freshness models:

| | DIS + `:seon-wire` (prior rec) | KabelWriter |
|---|---|---|
| `@conn` | sync; RE-READS root from store per deref (`connector.cljc:69-78`, non-streaming branch) | sync; plain atom deref, PUSH-updated by konserve-sync (`kabel/writer.cljc:190-238`) |
| d/q, d/datoms, d/pull, entity | local, sync, lazy LRU node fetch | local, sync, against fully-replicated local store |
| d/filter CLJS closures | works (local db value) | works (local db value) |
| freshness | sampled at deref; push via our `subscribe-tx` feed | push; conn always current within sync lag |
| RYOW | free (commit-before-ack + root re-read) | built-in (wait-for-sync before resolving transact, `writer.cljc:97-131`) |
| listen! | adapter over our feed (prior rec slice 3) | writer listener set + tx-broadcast topic |

Both satisfy the context-assembly constraint (sync db-value reads). Kabel's push-updated conn is marginally nicer ergonomically; it is not a differentiator given the cost side.

## 4. Fork→upstream migration cost: ~zero, and moot

- Fork = upstream + {Promise-wrap `api/async.cljs`, build fix, warning silencing}. Upstream HEAD adds only pydatahike/libdatahike work. Same persistent-sorted-set, same konserve 0.9.346, same store layout — **upstream JVM opens `data/clusters/default/store` exactly as the fork does** (the `store-id-refactoring.md` breaking change targeted 0.7.0 and is long since landed in both; current configs already use konserve UUID `:id`).
- Upstream CLJS support is NOT a superset of the fork — the reverse: the fork adds the Promise-wrapped async API. Seon doesn't even use it (`client.cljs:29`, `db.cljs:208` require plain `datahike.api`).
- Seon pod API surface (grep over `src/seon/*.cljs`): `d/transact!`, `d/q`, `d/pull`, `d/entity`, `d/create-database`, `d/connect`, `d/db`, `d/database-exists?`, `d/get-config`, `d/history`, `d/listen`, `d/unlisten` — all present and identical in both trees. **Nothing in Seon breaks on upstream main; nothing in upstream main is missing from the fork.** The right move regardless: upstream the fork's 3 commits and converge, eliminating skew permanently.

## 5. Fit vs our existing UDS wire

If kabel were adopted for DB ops:

- (a) **Replace wire DB-ops with kabel, keep our wire for seon ops** — cleanest split IF kabel is in play: kabel owns transact + replication; our wire keeps compliance checks, graph queries, reactive query-subscriptions, eval plumbing. The wire's `transact`/`q`/`subscribe-tx` ops become dead weight for kabel-connected agents.
- (b) Keep both for DB ops — rejected: two write paths into one DB = two RYOW models and double the failure surface.
- (c) Tunnel kabel over our endpoint — rejected: kabel is peer/middleware-shaped, our wire is request/op-shaped; tunneling buys nothing over (a) with a second port.

But for the MVP the real answer is **(none yet)**: same-machine DIS makes the wire's `transact` op the writer channel (`:seon-wire` PWriter) and `subscribe-tx` the notification channel — kabel adds a server peer, a port, 3 alpha deps, an npm dep, and an unwritten auth layer to deliver a replication we don't need on one machine. RYOW: our agents' transact-then-query pattern is safe under both (see §3 table); under DIS no overlay is needed at all, so the nonexistence of `datahike.optimistic` costs nothing.

## 6. Recommendation (scored)

| Criterion | DIS + `:seon-wire` (fork ≈ upstream) | KabelWriter stack |
|---|---|---|
| Optionally-remote/internet agents (HARD req) | not by itself (needs shared store) → remote path = kabel later OR query-subscriptions | native websockets BUT no auth, beta, Node-untested |
| Hundreds of lightweight agents (memory) | RAM ∝ working set, 1 disk copy | full DB per client (RAM or disk) + full update fan-out |
| Days-not-weeks MVP | ~40 lines on existing wire; probe in flight | new server peer + port + 3 alpha deps + npm dep + auth + pioneering Node client |
| Maintenance | tiny custom writer; fork delta = 3 upstreamable commits | maintained-but-beta upstream stack (konserve-sync 0.1.x, distributed-scope 0.1.2 alpha) |
| 2.2c probe relevance | validates it directly | format-compat findings still transfer (same blobs replicate over kabel) |

**Winner for now: DIS + `:seon-wire`, unchanged from the prior research.** The genuinely new information is strategic, not tactical: **the official remote story already lives in our fork**, so "optionally remote later" no longer implies maintaining a private protocol — when an agent leaves the machine, we light up `src-kabel` (few fat replicas) or registered query-subscriptions (many thin agents), and the same JVM stays the single writer in every topology. That removes the last reason to consider snapshot shipping or a bespoke replication protocol.

**Smallest first slice (winning path):** complete the in-flight 2.2c probe (JVM transacts → Node `@conn` re-read sees it → `d/datoms`/`d/filter` sync walk) — nothing in this research changes its design or value. **Pre-positioning slice for the kabel option (cheap, optional, ~half a day):** run `test/datahike/kabel/integration_test.clj` under the fork's `:test` alias on the JVM to confirm the vendored stack is green on our resolver versions; defer any Node client work until an off-machine agent actually exists.

## 7. External maturity check (agy, raw, verbatim)

Query: maturity of kabel 0.3.95 / konserve-sync 0.1.15 / distributed-scope 0.1.2 / KabelWriter, esp. Node.

> ### (1) Kabel (`org.replikativ/kabel` v0.3.95)
> - **Actively Maintained?** **Yes.** Version 0.3.95 was released on **May 8, 2026**. Maintainers Christian Weilbach (`@whilo`) and Konrad Kühne continue to issue regular updates.
> - **Known Production Users?** There are no publicly listed third-party enterprise production users, but it is actively used in production-like setups internally as the default transport layer for other components in the replikativ stack (e.g., Datahike, `konserve-sync`).
> - **Node.js Client Support Status?** **Supported.** It runs cross-platform on the JVM, Browsers, React-Native, and Node.js (which it supports using the native `WebSocket` API or `w3c-websocket` polyfills).
>
> ### (2) Konserve-sync (`org.replikativ/konserve-sync` v0.1.15)
> - **Release Maturity:** **Pre-1.0 / Stable API.** Version 0.1.15 was released on **February 26, 2026**. While still pre-1.0, the core codebase is considered functionally complete for its specific scope of differential key-value store replication.
> - **Known Issues:** No critical bugs are active. The main implementation challenges reported by users involve `core.async` channel flow management (e.g., backpressure tuning) and handling potential "not found" errors on keys that have not yet completed initial synchronization when callbacks execute.
>
> ### (3) `is.simm/distributed-scope` (v0.1.2)
> - **Maturity:** **Alpha / Low-level utility.** Version 0.1.2 was released on **December 23, 2025**. It has a very small, specialized footprint and is primarily maintained by the author (`@whilo`) to serve as the RPC/lexical scoping layer for remote calls within Datahike.
>
> ### (4) Datahike's KabelWriter / CLJS Distributed Setup
> - **Beta Label?** **Yes.** It remains explicitly labeled as a **beta** feature in Datahike's distributed documentation.
> - **Production Use (specifically Node.js)?** **No reports of production use.** While Datahike's JVM backend has proven production instances, the `KabelWriter` / CLJS distributed sync is primarily used for browser-to-server synchronization prototyping. In Node.js environments, developers typically opt for direct backend configurations (e.g., `konserve-node-filestore` or other persistent key-value engines) rather than establishing a peer-to-peer WebSocket sync setup.

## Smells / corrections flagged

- The framing that reached us claimed "**datahike.optimistic** overlays pending txs" — no such namespace exists at `717a0d27` (or in the fork). RYOW lives inside `KabelWriter.-dispatch!`'s wait-for-sync. Correct any planning docs repeating the claim.
- `src-kabel` is on the `:test` alias only (`deps.edn:54`) — kabel is NOT part of the shipped datahike artifact; any adoption plan must add the source path + deps explicitly.
- Kabel path has no authentication (`handlers.cljc` dispatch trusts store-id possession) — must be treated as a LAN/trusted-network feature until an auth middleware exists; relevant to the "internet-reachable agents" hard requirement.
- `connector.cljc:31-42` `make-branch-walk-fn` carries an upstream TODO: the datahike walker hardcodes branch `:db`; non-default branches would walk the wrong root.
