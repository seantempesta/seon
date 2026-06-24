---
type: research
status: active
tags: [research, agent, database]
---

# Wire protocol → Transit envelope + namespaced keyword keys

Read-only design pass (baseline commit 052cf69, tree clean). Implement as a
DEDICATED unit; do NOT bundle. A prior flaky agent did + reverted a partial
version — start clean.

## Verdict: the Rust host is DEAD. Full Transit is unblocked.

Definitive evidence the `pod-host/` Rust host is NOT in the live path:

- The live `pod` is `node out/client/main.js` (`bin/seon:190`) — no cargo/wasm/host binary.
- `bin/seon` has ZERO cargo/rust/wasm/tauri/pod-host references; `start all` launches
  exactly 3 processes: cljs-watch → wire-server → pod.
- Bytes go pod↔JVM directly: `wire_node.cljs` `rpc` opens `net.createConnection` to the
  UDS; the JVM `start-req-server!` accepts it directly. No Rust intermediary.
- `pod-host/` holds only WASM-era artifacts (`wasm-tauri/`, `libdatahike-cljs/`,
  `datahike-harness/`). The "Rust reads basis-t/db-name" comments in `wire.clj`/
  `transit.clj`/`codec.clj` are STALE WASM-era docs (CLAUDE.md: NO WASM, abandoned
  Rust era). They must be corrected in this unit so they don't resurrect the
  CBOR-for-Rust argument.

→ The sole historical reason for CBOR no longer exists. **Recommend Option A: full
Transit envelope.**

## Current encoding (confirmed)

- **Framing:** 4-byte big-endian length header + **CBOR** payload, both sockets (req +
  pub/feed). JVM: Jackson `CBORFactory` via `seon.server.codec`. CLJS: hand-rolled
  `seon.store.internal.cbor` byte-matched to Jackson.
- **Envelope keys are plain STRINGS.** `codec/->java` flattens any keyword key to a
  string (`:ns/name`→`"ns/name"`); CLJS `enc-map` does `(name k)`. So keywords can't
  survive CBOR by construction.
- **Inner VALUES are Transit-JSON strings** embedded in CBOR string fields
  (`seon.server.transit` / `cognitect.transit :json`). `read-T` (JVM) has a
  Transit-then-EDN fallback the tests rely on (they pass raw EDN `"tx-data"`).
- transit-clj + transit-cljs are ALREADY deps on both sides.

## Option A — full Transit envelope (recommended)

Replace CBOR framing with Transit-JSON on both sides; envelope keys become real
namespaced keywords; inner values become native substructure (one decode yields the
whole map with real keywords/instants — no double-encoding, no EDN fallback, delete
the hand-rolled `cbor.cljs`).

Rejected: **B** (CBOR framing + Transit-tagged keys) — both CBOR codecs flatten
keywords by construction, so you'd Transit-encode keys inside CBOR = strictly uglier,
no upside now Rust is gone. **C** (namespaced string keys) — cosmetic, the
stringly-typed model the user rejected; this is what was reverted.

## OPEN DECISIONS (need owner sign-off — see below)

1. **Namespace home for transport control keys.** The plan's `:seon.wire/*` has NO
   backing code ns (the code is `seon.store.wire` + `seon.server.wire`), which
   violates the "keyword namespace = real code namespace" rule. Options:
   (a) **create a shared `seon.wire` .cljc protocol ns** (defines the keys/op vocab,
   required by both sides) → `:seon.wire/*` becomes legitimate + gives the protocol a
   canonical home [RECOMMENDED]; (b) reuse `:seon.store.wire/*` (the persisted write-id
   attr already uses it); (c) `:seon.wire/*` with no backing ns [rule violation —
   rejected].
2. **Unify the transport echo key.** Today transport `"request-id"` (string) ≠
   persisted attr `:seon.store.wire/write-id` (keyword). Recommend ONE keyword
   end-to-end (`:seon.store.wire/write-id`) for consistency — but it's the
   highest-risk rename (8 echo-suppression sites). Alternative: keep a distinct
   transport key to minimize blast radius.

## File + key map (Option A)

**Codec:** `server/codec.clj` (CBOR→Transit frame enc/dec, keep length-framing);
`store/internal/cbor.cljs` (DELETE / replace with Transit; source the `Buffer` handle
`rpc` needs from `node:buffer` once cbor.cljs is gone).

**JVM:** `server/wire.clj` (all `(get req "…")`/`(assoc m "…")`/`(ok {…})` → keywords;
remove inner `T`/`read-T` value wrapping; `datom->wire` keeps vector shape, a/v become
native), `server/boot.clj` (subscribe/next/unsubscribe handlers), `server/broadcast.clj`
(the `(get event "db-name")` routing lookup), `server/reactive.clj` (verify it doesn't
build wire envelopes; reads the keyword write-id attr already).

**CLJS:** `store/wire.cljs` (sender req map + all response/feed readers),
`store/internal/wire_node.cljs` (every op request map + reader across rpc/ping/ensure-db/
transact/q/pull/schema/knn-search/subscribe-tx/next-tx-event/unsubscribe-tx/routed),
`store/dev/replica_peer.cljs` (the SeonWireWriter req map + feed readers — MUST move in
lockstep; it's the regression oracle on the same socket).

**Tests (9, all build raw envelopes):** server/{protocol_integration,overlay_semantics,
transact_batch,wire_request_id,reactive,protocol_extensions,wire_props,wire_types}_test +
store/wire_test.cljs. Many pass EDN strings for `"tx-data"` via the read-T fallback →
migrate to native data.

**NOT in scope:** `db/relay.clj`, `flow/topology.clj` — `::request-id`/`::msg/id` on a
different in-JVM agent bus, not the UDS wire.

`"payload"` becomes redundant under full Transit (one decode) — drop it; `wire_node
transact` reads structured fields directly.

## Echo-suppression symmetry (the trap that already fired)

The write-id key has 8 coupled sites — ALL change in one commit: senders
`store/wire.cljs:242` + `wire_node.cljs:146` + `replica_peer.cljs:155`; threaded
`wire.clj` transact:424 + transact-batch:471; echoed `ok-event-from-report:339` +
`ok-response-from-report:404`; matched `store/wire.cljs:329` + `replica_peer.cljs:247`.
A partial rename = own writes stop being skipped → native listeners double-fire
(user-message triggers + inspector SSE fire twice). Verify via the replica-peer
oracle's `own-skips` count, not just "tests pass."

## Migration (one atomic protocol revision — no backward-compat window)

CBOR and Transit frames are not mutually decodable; a pod + wire-server on different
dialects fail every RPC. So codec swap + key rename + test migration are ONE revision,
then a coordinated full-stack restart.

1. Codec swap (codec.clj + cbor.cljs replacement) — symmetric.
2. Key rename all sites → keywords (chosen namespace); write-id unification across all 8.
   Remove inner-Transit value wrapping + EDN fallback.
3. Migrate all 9 test files to keyword envelopes + native tx-data. Green `bin/test-clj`
   + `bin/test-cljs` BEFORE touching the live stack.
4. `bin/seon restart all` (cljs-watch → wire-server → pod together; no compat window).
   Also restart any `bin/acme` isolated cluster.
5. Live verify: round-trip transact (RYOW deref reaches ack basis-t); `q`/`pull`
   basis-t; **echo-suppression own-skips via the replica-peer oracle** (own tx skipped,
   foreign tx fires handler once); pod `adapter-status ::own-skips` advances + no
   double-fired `seon.db/listen!`.

## Risks

1. Cross-process symmetry (write-id especially) — all sites one commit; verify own-skips.
2. replica_peer.cljs is a 2nd wire client + the proof oracle — move lockstep.
3. No backward-compat window — coordinated full-stack restart (+ acme).
4. EDN-string test inputs rely on the dead read-T fallback — migrate to native.
5. `knn-search "query"` (plain string) vs `q "query"` (Transit) — same key, two
   encodings today; under Transit both native — verify the knn handler.
6. Stale Rust docs in transit.clj/codec.clj/wire.clj — correct them here.
7. No automated cross-process echo-suppression test in `bin/test-cljs` — run the
   replica-peer oracle explicitly at cutover.
