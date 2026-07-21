---
type: research
status: active
tags: [research, architecture, database]
---

# W1.5b session-open admission design — sol read-only pass (2026-07-21 night)

Decision-ready design authored by the read-only sol design lane;
orchestrator-accepted in full 2026-07-21 night. The mandatory pre-
implementation gates are §2.1 (pure encoding probe: every opening shape
under 4096 bytes in BOTH Transit codecs) and §9 risk 1 (at-capacity
rejection delivery falsifier) — both run BEFORE selector work.

# W1.5b Design — Database Session-Open Admission

Status: decision-ready, read-only; no repository files changed.

## 1. Recommendation

Add a client-first, Transit-encoded `session-open` request/response before any ordinary database request. The exchange uses the existing four-byte framing and a fixed 4 KiB bootstrap ceiling; after success, both peers switch that physical session to the negotiated frame ceiling. This strengthens the existing protocol and UDS mechanisms in place; it does not introduce another codec or transport. The repository explicitly sanctions new operations and Transit types as the extension path. ([program-synthesis-2026-07-21.md:462](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:462), [uds.cljc:1](/Users/sean/src/seon/src/seon/db/transport/uds.cljc:1))

The client sends first because that keeps the exchange in the existing correlated request/response model. A server-first offer would require offer/accept/ack—three frames—and a second message role not present in the current protocol. Existing requests carry an operation and request id, and responses correlate through that id. ([protocol.cljc:441](/Users/sean/src/seon/src/seon/db/protocol.cljc:441), [uds.cljc:275](/Users/sean/src/seon/src/seon/db/transport/uds.cljc:275))

Use a reserved request id, `"session/open"`. At capacity, the server can therefore send the complete correlated rejection immediately after `accept`, without first reading client bytes. This directly replaces today’s accept-then-close behavior. ([uds.cljc:985](/Users/sean/src/seon/src/seon/db/transport/uds.cljc:985))

## 2. Wire contract

### 2.1 Bootstrap framing

Define `protocol/session-open-maximum-frame-bytes = 4096`. Opening frames always use this fixed ceiling, independently of semantic input/output reservations. After admission, semantic frames use the negotiated session ceiling.

Also validate:

```clojure
(<= protocol/session-open-maximum-frame-bytes
    configured-maximum-frame-bytes
    protocol/maximum-frame-bytes)
```

The upper bound already exists, but the current config schema permits values as low as 1 and the resolver checks only the upper ceiling; W1.5b must add the lower-bound validation. ([config/resolve.cljc:53](/Users/sean/src/seon/src/seon/config/resolve.cljc:53), [config/resolve.cljc:922](/Users/sean/src/seon/src/seon/config/resolve.cljc:922), [protocol.cljc:98](/Users/sean/src/seon/src/seon/db/protocol.cljc:98))

Opening frames should bypass `maximum-input-bytes`, response-slot, and output-byte reservations: they are protocol-control overhead with their own 4 KiB hard bound. The current server reserves semantic frame bytes before payload allocation, so treating admission as an ordinary request could otherwise make a small configured input budget prevent every session from opening. ([uds.cljc:930](/Users/sean/src/seon/src/seon/db/transport/uds.cljc:930), [uds.cljc:1177](/Users/sean/src/seon/src/seon/db/transport/uds.cljc:1177))

The candidate maps’ actual Transit byte counts are **NOT GROUNDED** because the types do not exist yet and the read-only environment prevented a classpath-backed encoding probe. The implementation gate must encode every opening shape with both Transit implementations and prove each is below 4096 bytes. The current JVM and CLJS codecs are Transit JSON 1.0.333 and 0.8.280. ([deps.edn:38](/Users/sean/src/seon/deps.edn:38), [deps.edn:142](/Users/sean/src/seon/deps.edn:142))

### 2.2 Request

```clojure
{:seon.db.protocol/operation
 :seon.db.protocol.operation/session-open

 :seon.db.protocol/request-id
 "session/open"

 :seon.db.protocol/version
 13

 :seon.db.protocol/maximum-frame-bytes
 4194304}
```

Fields:

- `operation`: identifies the new admission operation using the existing operation vocabulary. ([protocol.cljc:20](/Users/sean/src/seon/src/seon/db/protocol.cljc:20))
- `request-id`: fixed correlation permits immediate at-capacity rejection without parsing the client.
- `version`: the database protocol compatibility discriminator; bump `current-version` from 12 to 13 because admission becomes mandatory. ([protocol.cljc:98](/Users/sean/src/seon/src/seon/db/protocol.cljc:98))
- `maximum-frame-bytes`: the client’s compiled maximum, not a config read. JVM host and Babashka clients therefore need no access to the cluster manifest.

Do not carry artifact digest, database name, backend, authentication, or capabilities. Artifact membership is already release identity, database selection happens after transport connection, and Datahike capabilities already have a dedicated operation. ([release.clj:591](/Users/sean/src/seon/script/seon/dev/release.clj:591), [db.cljs:521](/Users/sean/src/seon/src/seon/db.cljs:521), [db.cljs:531](/Users/sean/src/seon/src/seon/db.cljs:531))

### 2.3 Success response

```clojure
{:seon.db.protocol/success? true
 :seon.db.protocol/request-id "session/open"
 :seon.db.protocol/version 13

 :seon.db.protocol/configured-maximum-frame-bytes 1048576
 :seon.db.protocol/maximum-frame-bytes 1048576}
```

`configured-maximum-frame-bytes` is the writer’s launch-envelope value. `maximum-frame-bytes` is the agreed per-session ceiling:

```clojure
(min client-maximum-frame-bytes
     configured-maximum-frame-bytes)
```

The minimum rule is recommended. It allows the host to learn the ceiling from the session and guarantees neither peer sends a frame the other cannot accept. The resolver already prevents the writer configuration from exceeding the protocol ceiling. ([config/resolve.cljc:925](/Users/sean/src/seon/src/seon/config/resolve.cljc:925))

Alternative: exact-match rejection. Reject it because every non-pod client would need an independent config path—the precise host gap W1.5b is meant to remove. The host currently receives only writer socket/database coordinates. ([host/context.clj:208](/Users/sean/src/seon/src/seon/host/context.clj:208))

The server must not call `open-connection!`, expose the connection to the writer, or enable semantic reads until it has validated the request and completely written this response. Today `open-connection!` runs immediately after accept; that must move behind admission. ([uds.cljc:1065](/Users/sean/src/seon/src/seon/db/transport/uds.cljc:1065))

### 2.4 Incompatible peer

```clojure
{:seon.db.protocol/success? false
 :seon.db.protocol/request-id "session/open"
 :seon.db.protocol/error-kind
 :seon.db.protocol.error/incompatible-version
 :seon.db.protocol/error
 "The database protocol version is incompatible."
 :seon.error/kind :configuration
 :seon.db.protocol/version 13
 :seon.db.protocol/peer-version 12}
```

Versions must match exactly. After writing this bounded response, close the channel without creating a writer transport connection.

Alternative: supported-version ranges. Reject it for W1.5b because independently deployed peers are not supported, and the protocol version is already part of release identity. Range negotiation adds dormant compatibility branches with no rollout consumer. ([release.clj:600](/Users/sean/src/seon/script/seon/dev/release.clj:600))

### 2.5 At-capacity response

```clojure
{:seon.db.protocol/success? false
 :seon.db.protocol/request-id "session/open"
 :seon.db.protocol/error-kind
 :seon.db.protocol.error/connection-capacity
 :seon.db.protocol/error
 "The database writer is at its connection capacity."
 :seon.error/kind :configuration
 :seon.db.protocol/configuration-key
 :seon.config.database.transport/maximum-connections
 :seon.db.protocol/maximum-connections 12}
```

The server sends this immediately under the bootstrap ceiling, then closes. It does not call `open-connection!` and does not count the rejection session as admitted. The current cap comparison already occurs directly after `accept`, making this a replacement of the close branch rather than another admission mechanism. ([uds.cljc:989](/Users/sean/src/seon/src/seon/db/transport/uds.cljc:989))

Only one at-capacity rejection session should be active at a time; temporarily remove `OP_ACCEPT` while its tiny frame drains, then restore accept interest. This prevents clients that do not read from accumulating an unbounded set of rejection sockets. The selector already centrally owns interest changes and output completion. ([uds.cljc:84](/Users/sean/src/seon/src/seon/db/transport/uds.cljc:84), [uds.cljc:958](/Users/sean/src/seon/src/seon/db/transport/uds.cljc:958))

## 3. Post-open frame enforcement

Every database-session read and write must receive the negotiated ceiling explicitly:

- JVM `write-frame!`, `read-frame`, and `message-frame` gain ceiling-taking arities; default arities retain the protocol ceiling for non-database stream users. ([uds.cljc:218](/Users/sean/src/seon/src/seon/db/transport/uds.cljc:218), [uds.cljc:235](/Users/sean/src/seon/src/seon/db/transport/uds.cljc:235), [uds.cljc:249](/Users/sean/src/seon/src/seon/db/transport/uds.cljc:249))
- The CLJS parser carries its current ceiling in parser state, and `encode-frame` takes the session ceiling. Today both close over a private constant. ([uds.cljs:19](/Users/sean/src/seon/src/seon/db/transport/uds.cljs:19), [uds.cljs:104](/Users/sean/src/seon/src/seon/db/transport/uds.cljs:104), [uds.cljs:168](/Users/sean/src/seon/src/seon/db/transport/uds.cljs:168))
- `connect-stream!` remains at the protocol ceiling because it serves execution streams, not the database request socket. ([uds.cljs:623](/Users/sean/src/seon/src/seon/db/transport/uds.cljs:623))

An honest client rejects an oversized outbound request locally, naming `:seon.config.database.transport/maximum-frame-bytes`. An oversized server response is replaced with a small correlated failure naming the same key instead of taking the current encode-failed close path. ([uds.cljc:799](/Users/sean/src/seon/src/seon/db/transport/uds.cljc:799))

An oversized inbound length cannot expose its request id because the payload is deliberately not allocated. The server should therefore send a reserved session-control failure and close; CLJS must recognize that control failure and reject all pending requests with its structured data. The present parser terminates on an invalid length, so this extends that one terminal transition. ([uds.cljs:132](/Users/sean/src/seon/src/seon/db/transport/uds.cljs:132), [uds.cljs:310](/Users/sean/src/seon/src/seon/db/transport/uds.cljs:310))

## 4. Compatibility and rollout

No dual-accept window. Protocol 13 requires `session-open`; protocol 12 does not. All repository peers and release fixtures move together.

A new client against an old server sends an unknown operation and receives the existing correlated protocol rejection; an old client against a new server has its first ordinary request rejected as “session open required.” Invalid operations already flow through canonical protocol failure construction. ([writer.clj:3694](/Users/sean/src/seon/src/seon/db/writer.clj:3694), [writer.clj:4094](/Users/sean/src/seon/src/seon/db/writer.clj:4094))

Update the three release fixtures that currently pin version 12, plus the direct protocol/transport assertions. The localized runbook names this as the established version-bump pattern. ([sci-execution-runtime/AGENTS.md:42](/Users/sean/src/seon/docs/prds/sci-execution-runtime/AGENTS.md:42), [protocol_test.clj:37](/Users/sean/src/seon/test/seon/db/protocol_test.clj:37))

## 5. Client inventory

Production semantic request-socket clients are:

1. Pod and Bun execution children through `seon.db/connect-selection!`; execution children receive the same database selection in their startup value. ([db.cljs:495](/Users/sean/src/seon/src/seon/db.cljs:495), [execution/host.cljs:481](/Users/sean/src/seon/src/seon/execution/host.cljs:481))
2. SCI host pool members through `seon.host.context/open-member!`. ([host/context.clj:319](/Users/sean/src/seon/src/seon/host/context.clj:319))
3. Babashka branch lifecycle calls. ([branch.clj:170](/Users/sean/src/seon/script/seon/dev/branch.clj:170))
4. Babashka restore-state lifecycle calls. ([restore_state.clj:211](/Users/sean/src/seon/script/seon/dev/restore_state.clj:211))

The operator also makes raw request-socket readiness and unmanaged-listener probes. Those should use the session-open exchange and treat either success or a structured connection-capacity rejection as proof of a compatible live writer. A bare TCP/UDS connect would no longer prove protocol readiness. ([process.clj:647](/Users/sean/src/seon/script/seon/dev/process.clj:647), [process.clj:866](/Users/sean/src/seon/script/seon/dev/process.clj:866))

`detach.py` and `socket-line!` address the containment control socket, not the database request socket, and remain unchanged. ([detach.py:146](/Users/sean/src/seon/script/seon/dev/detach.py:146), [process.clj:1174](/Users/sean/src/seon/script/seon/dev/process.clj:1174))

## 6. Ownership map

| Owner | Strengthening |
|---|---|
| `db/protocol.cljc` | Bump to version 13; add `session-open` operation, opening request/success/failure schemas, constructors, fixed request id, bootstrap ceiling, and precise error kinds. Existing operation/validation unions remain the sole contract owner. ([protocol.cljc:176](/Users/sean/src/seon/src/seon/db/protocol.cljc:176), [protocol.cljc:725](/Users/sean/src/seon/src/seon/db/protocol.cljc:725)) |
| `db/transport/uds.cljc` | Add JVM session-open client state, ceiling-taking framing arities, and opening/rejecting/open server phases inside the existing selector and accept loop. ([uds.cljc:218](/Users/sean/src/seon/src/seon/db/transport/uds.cljc:218), [uds.cljc:985](/Users/sean/src/seon/src/seon/db/transport/uds.cljc:985)) |
| `db/transport/uds.cljs` | Make `connect!` resolve only after admission succeeds, then retain the negotiated ceiling in its existing session closure. ([uds.cljs:272](/Users/sean/src/seon/src/seon/db/transport/uds.cljs:272), [uds.cljs:538](/Users/sean/src/seon/src/seon/db/transport/uds.cljs:538)) |
| `db/server.clj` | Add both remaining envelope attributes to `request-server-option-attributes`; this is the one launch-envelope translation seam. ([server.clj:71](/Users/sean/src/seon/src/seon/db/server.clj:71), [server.clj:98](/Users/sean/src/seon/src/seon/db/server.clj:98)) |
| `db/writer.clj` | Accept both options in `::request-server-options`, retain configured frame bytes in runtime, and advertise that value rather than `protocol/maximum-frame-bytes`. ([writer.clj:116](/Users/sean/src/seon/src/seon/db/writer.clj:116), [writer.clj:3714](/Users/sean/src/seon/src/seon/db/writer.clj:3714), [writer.clj:4254](/Users/sean/src/seon/src/seon/db/writer.clj:4254)) |
| `db.cljs` | Treat transport admission as part of physical connection opening and verify the later capabilities response agrees with the session’s configured ceiling and version. ([db.cljs:495](/Users/sean/src/seon/src/seon/db.cljs:495), [db.cljs:521](/Users/sean/src/seon/src/seon/db.cljs:521)) |
| `host/context.clj` | Have each `open-member!` retain a negotiated JVM UDS session instead of a naked `SocketChannel`; preserve structured cap failures in `pool-error`. ([host/context.clj:319](/Users/sean/src/seon/src/seon/host/context.clj:319), [host/context.clj:463](/Users/sean/src/seon/src/seon/host/context.clj:463)) |
| `config/resolve.cljc` | Add the bootstrap lower-bound check and flip both keys into `enforced-keys`. ([config/resolve.cljc:163](/Users/sean/src/seon/src/seon/config/resolve.cljc:163), [config/resolve.cljc:1132](/Users/sean/src/seon/src/seon/config/resolve.cljc:1132)) |
| BB operator clients | Route branch, restore, readiness, and unmanaged-listener checks through the one JVM session-open client. ([branch.clj:170](/Users/sean/src/seon/script/seon/dev/branch.clj:170), [restore_state.clj:211](/Users/sean/src/seon/script/seon/dev/restore_state.clj:211), [process.clj:647](/Users/sean/src/seon/script/seon/dev/process.clj:647)) |

None exceeds “strengthen in place.” Adding another config reader to the host, another codec, a raw binary preface, or a compatibility server would exceed that boundary.

## 7. Host config-path disposition

The earlier frame-ceiling gap closes completely: every host pool member learns and retains its agreed ceiling during physical session open, and `uds/call!` uses that retained value. The host needs no config read, database lookup, environment variable, or side channel. ([host/context.clj:319](/Users/sean/src/seon/src/seon/host/context.clj:319))

Separate host pool-size, wait, deadline, and backoff literals remain scheduled for the W1 config-facts sweep; they are unrelated to frame negotiation and should not expand W1.5b. ([host/context.clj:189](/Users/sean/src/seon/src/seon/host/context.clj:189))

## 8. Tests and gates

### Contract and transport conformance

Extend:

- `test/seon/db/protocol_test.clj` and `.cljs`: exact version-13 opening shapes, invalid versions/ceilings, fixed request id, Transit round trips, and encoded size below 4096. ([protocol_test.clj:30](/Users/sean/src/seon/test/seon/db/protocol_test.clj:30))
- `test/seon/db/transport_uds_test.clj`: fragmented/coalesced session-open, min agreement, exact version rejection, non-open-first rejection, per-session ceiling enforcement, cap+1 steering, close-after-rejection, and no `open-connection!` call on either rejection. Existing tests already exercise fragmentation, reservations, bounded cleanup, and explicit connection caps. ([transport_uds_test.clj:360](/Users/sean/src/seon/test/seon/db/transport_uds_test.clj:360), [transport_uds_test.clj:1278](/Users/sean/src/seon/test/seon/db/transport_uds_test.clj:1278))
- `test/seon/db/transport_uds_test.cljs`: fake-Bun opening success/rejection, parser ceiling switch, local oversize rejection naming the frame key, control-failure settlement, and native JVM↔Bun admission. ([transport_uds_test.cljs:157](/Users/sean/src/seon/test/seon/db/transport_uds_test.cljs:157), [transport_uds_test.cljs:459](/Users/sean/src/seon/test/seon/db/transport_uds_test.cljs:459))
- `test/seon/db_session_test.cljs` and `db_remote_contract_test.cljs`: opening/reconnect waits for completed admission and preserves the negotiated ceiling. Their fixtures already replace `uds/connect!`, so they must return admitted-session-shaped data. ([db_session_test.cljs:100](/Users/sean/src/seon/test/seon/db_session_test.cljs:100), [db_remote_contract_test.cljs:189](/Users/sean/src/seon/test/seon/db_remote_contract_test.cljs:189))
- `test/seon/host_pool_writer_test.clj`: every new/replacement member opens through admission; cap rejection becomes a bounded host steering value containing the exact config key. ([host_pool_writer_test.clj:191](/Users/sean/src/seon/test/seon/host_pool_writer_test.clj:191))
- `test/seon/db/server_test.clj`: expected request-server options now include both values instead of asserting their absence. ([server_test.clj:119](/Users/sean/src/seon/test/seon/db/server_test.clj:119))
- `test/seon/dev/cli_test.clj`: replace `carried-config-changes-decline-with-w1-5-steering` with reconstruction assertions for both keys. ([cli_test.clj:915](/Users/sean/src/seon/test/seon/dev/cli_test.clj:915))

### Live drive

On an isolated cluster:

1. Apply `maximum-connections = N` and `maximum-frame-bytes = 65536`; prove writer generation changes while pod identity remains stable through the existing reconstruction path. ([program-synthesis-2026-07-21.md:722](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:722))
2. Open exactly N admitted sessions across pod/CLJS, SCI host/JVM, and BB/JVM clients; N+1 must receive the exact `connection-capacity` value naming `:seon.config.database.transport/maximum-connections`.
3. Close one admitted session and prove the next opener succeeds.
4. Through pod and host tiers, prove a legal frame near 65536 succeeds, an outbound oversize request fails locally with the frame key, and an oversized writer response becomes the bounded correlated failure rather than EOF.
5. Restore the original manifest and prove a subsequent converged apply reports `changed: false`, matching the established W1.2a idempotency gate. ([program-synthesis-2026-07-21.md:730](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:730))

Run full `bin/test-writer`, `bin/test-cljs`, and `bin/seon test operator`; all three are required because the clients span JVM writer/host, Bun, and Babashka. ([w1.5-enforcement-surfaces.md:80](/Users/sean/src/seon/docs/prds/sci-execution-runtime/specs/w1.5-enforcement-surfaces.md:80))

Final dispositions: both `maximum-frame-bytes` and `maximum-connections` flip from `:carried` to `:enforced`; no operational key remains carried after W1.5b. ([config/resolve.cljc:126](/Users/sean/src/seon/src/seon/config/resolve.cljc:126), [program-synthesis-2026-07-21.md:760](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:760))

## 9. Ranked risks

1. **At-capacity rejection delivery is the riskiest assumption.** The selector must deliver a small response without admitting a writer connection or accumulating rejection sockets; today it only closes immediately. ([uds.cljc:985](/Users/sean/src/seon/src/seon/db/transport/uds.cljc:985))  
   Cheapest falsifier: start the existing request server with cap 1, hold one admitted session, repeatedly connect cap+1 clients that fragment reads or temporarily do not read, and prove exact error delivery, bounded FD count, continued selector responsiveness, and immediate admission after the held session closes. The existing suite already has FD-count and bounded-cap scaffolding. ([transport_uds_test.clj:43](/Users/sean/src/seon/test/seon/db/transport_uds_test.clj:43), [transport_uds_test.clj:1278](/Users/sean/src/seon/test/seon/db/transport_uds_test.clj:1278))

2. Phase switching may mishandle coalesced opening and first semantic frames. Disable semantic reads while the opening response is pending and extend the existing fragmentation/coalescing tests. ([uds.cljc:914](/Users/sean/src/seon/src/seon/db/transport/uds.cljc:914), [transport_uds_test.clj:477](/Users/sean/src/seon/test/seon/db/transport_uds_test.clj:477))

3. A framing helper may accidentally retain the global constant, producing asymmetric enforcement. Remove database-session uses of the private constants by test-enforced call-site inventory; retain default arities only for non-database stream users. ([uds.cljc:171](/Users/sean/src/seon/src/seon/db/transport/uds.cljc:171), [uds.cljs:19](/Users/sean/src/seon/src/seon/db/transport/uds.cljs:19))

4. Correcting capabilities may expose callers that assumed the 4 MiB constant. Current pod code stores capabilities but does not validate the advertised ceiling; add an explicit equality assertion at session open. ([db.cljs:565](/Users/sean/src/seon/src/seon/db.cljs:565), [writer.clj:3714](/Users/sean/src/seon/src/seon/db/writer.clj:3714))

5. The chosen 4096-byte bootstrap margin is **NOT GROUNDED** until both actual codecs encode all final shapes. The mandatory pre-implementation probe is pure encoding—no live server required—and should fail the implementation before selector work begins if any shape exceeds the bound.