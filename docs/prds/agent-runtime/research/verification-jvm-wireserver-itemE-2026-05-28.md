---
type: research
status: draft
tags: [research, agent, database]
---

# Verification: JVM wire-server round-trip + Item E agent routing

Skeptical verification (side-effect, not return-value). All claims tested
against the live REPL with raw-conn queries. Done 2026-05-28.

## TL;DR — per-claim verdict

| Claim | Verdict | Evidence |
|-------|---------|----------|
| Wire-server starts in-process on a `:memory` conn + test sockets | VERIFIED | `store-config` / `ensure-db!` / `start-req-server!` / `start-pub-server!` all resolve and start cleanly |
| transact frame round-trips to the conn (side effect) | VERIFIED | Raw-conn `d/q` after a wire `transact` returns the transacted datom |
| q frame returns correct results | VERIFIED | `q` op returns the same rows as the raw conn |
| listen/broadcast is REAL (not a stub) | VERIFIED — REAL | Pub subscriber received 2 `"tx"` events for 2 transacts |
| Rewritten `facts_test.clj` fixture passes | VERIFIED (after fix) | 16/16 pass; surfaced + fixed a real `:memory` store-isolation bug |
| Item E: same-session agents share, cross-session isolated | VERIFIED | Raw-conn queries on each session's actual conn |
| Item E: unknown-agent error path is a clean throw (no NPE) | VERIFIED | `ExceptionInfo` with `:known-agents`, not NPE |
| Item E: real `bin/mcp-server` dispatch routes correctly | VERIFIED | Reconstructed wrapped string `load-string`'d → correct session |
| Item E: `remove-db!` auto-drops the session's agents | VERIFIED | Removing session A dropped both its agents; session B agent survived |

Nothing was found hollow except the `:memory` fixed-store-uuid leak (below),
which I fixed in the fixture.

## Wire-server findings

### Start API (as ACTUALLY found in src)

The `clojure -M:writer` subprocess alias is GONE (folded into
`src/seon/server` in Wave 4a). The in-process start sequence, lifted from
`seon.server.wire/-main` (`src/seon/server/wire.clj:500-512`):

```clojure
(def cfg  (#'wire/store-config {:backend "memory"}))   ; private, line 45
(def conn (#'wire/ensure-db! cfg))                      ; private, line 74
(def pub  (bcast/start-pub-server! pub-sock))           ; public,  broadcast.clj:28
(def req  (#'wire/start-req-server! conn req-sock))     ; private, line 460
```

- `store-config` and `ensure-db!` and `start-req-server!` are all `defn-`
  (private) — the fixture reaches them with `#'`. Gemini flagged this as
  brittle; valid, but acceptable for a test seam. A public
  `start-test-server!` in `wire` would be cleaner.
- Test client is REAL: `seon.server.client/connect`, `call!`,
  `start-pub-collector!` (`src/seon/server/client.clj`) — the fixture and
  my round-trip both use them.

### Round-trip evidence (side effect, not return value)

In-process: started writer on `:memory` + unique test sockets, sent a
schema `transact`, then `{:person/name "alice"}` `transact`, then queried
the **raw conn directly** (bypassing the wire):

- `transact` returned `ok=true`, `datoms-added=2`.
- Raw-conn `d/q '[:find ?n :where [?e :person/name ?n]]` contained
  `"alice"` — the datom actually landed, not just a happy return map.

### listen / broadcast — REAL, not a stub

`seon.server.broadcast/broadcast!` (`broadcast.clj:17`) writes a CBOR frame
to every subscriber `OutputStream` and drops dead subscribers.
`wire/handle-op "transact"` calls `(bcast/broadcast! event)` after every
commit (`wire.clj:286`). I subscribed with `client/start-pub-collector!`,
did 2 transacts, and the subscriber's atom collected **2 `"tx"` events**.
The "subscribe-tx is a no-op stub" note from the sidecar P3 era is STALE —
on this Path-B wire-server, broadcast is fully wired.

(Note: `seon.server.session/::pub-chan` is still always `nil` — that slot
is the separate Wave-4 per-session core.async fanout, distinct from the
process-global `broadcast/subscribers` set the wire-server actually uses.)

## The one bug the fixture surfaced (and I fixed)

`wire/store-config` hardcodes a FIXED store uuid for `:memory`
(`#uuid "00000000-...0001"`, `wire.clj:48`). Every `ensure-db!` with
`:memory` therefore connects to the SAME process-global in-memory store.
The fixture spawns a "fresh" writer per test, but `d/release` does not
clear the shared store, so datoms leak across tests.

- Proof: two independent `ensure-db!` calls — a `:db/doc "probe-marker"`
  transacted on conn 1 was visible from conn 2.
- Symptom: `test-seed-installs` failed `(not (= 34 35))` — the extra fact
  was `test-record-fact-upsert`'s `:fact/id "test-upsert-1"` leaking in
  (test-order dependent, stable at +1 because `:fact/id` upsert dedups).

Fix (in `test/seon/server/facts_test.clj` `spawn-writer!`): build the cfg
inline with `{:store {:backend :memory :id (random-uuid)} ...}` instead of
calling `store-config`. Verified two unique-id stores are mutually
isolated. Fixture then went **16/16 green**.

This is a latent hazard for ANY future in-process test using
`store-config :memory` — they must NOT share the fixed uuid. Path B's
`seon.server.session/store/config-for` derives per-db-name configs and was
verified to give distinct conns per session (see Item E), so production
multi-session is fine; the trap is specifically `wire/store-config`'s
`:memory` branch.

## Item E findings (per-agent MCP eval routing)

Registry: `seon.server.session` holds `!registry` (db-name → conn) and
`!agents` (agent-id → db-name). `seon.session/with-agent` resolves
agent-id → conn and binds `*conn*` + `*current-agent-id*`.

### Isolation via raw-conn queries

Set up: agent-1 + agent-2 → session A (same), agent-3 → session B.
Transacted distinct `:note/text` per agent via `with-agent`, then queried
each session's **raw conn**:

- Session A raw conn: `{["from-agent-1"] ["from-agent-2"]}` — same-session
  agents SHARE.
- Session B raw conn: `{["from-agent-3"]}` ONLY — cross-session ISOLATED,
  no leak from A.

### Error paths (clean throws, no NPE)

- `(with-agent "no-such-agent" ...)` → `ExceptionInfo`
  `"Unknown agent-id: \"no-such-agent\". Register via
  seon.server.session/register-agent! first."` with `:known-agents` in
  ex-data. NOT an NPE.
- `register-agent!` against an unregistered db-name → `ExceptionInfo`
  `"Cannot register agent: db-name not in registry"` (guarded at
  `session.clj`/`server/session.clj:257`).
- `resolve-agent` for a dropped agent → `{}` (empty map, no throw).

### Real-dispatch proof (the ACTUAL bash path)

`bin/mcp-server`'s `wrap-with-agent` (lines 464-477) builds:
`(seon.session/with-agent-load-string "<id>" "<pr-str'd user code>")`
and the master nREPL `load-string`s it. I reconstructed that exact string
for `:seon.agent/agent-2`:

```
(seon.session/with-agent-load-string "agent-2" "(datahike.api/q '[...] (datahike.api/db seon.session/*conn*))")
```

`load-string`'d on the master REPL (exactly as nREPL does) → returned
session A's data `{["from-agent-1"] ["from-agent-2"]}`. The string-body
`load-string` form-by-form semantics (for multi-form alias resolution) is
intentional (`session.clj:123-141`).

### Cleanup

`remove-db!` on session A returned `{::removed? true}` and dropped BOTH
agent-1 and agent-2 from `!agents` (the `swap! !agents (remove ...)` at
`server/session.clj:222`), while agent-3 (session B) survived. `resolve-agent`
for a removed agent then returns `{}`. Verified, then restored the
pre-test registry snapshot so Path A (`:smoke/alice`, `:smoke/bob`,
`alice-1`, `bob-1`) is untouched.

## What's hollow

- Nothing functionally hollow on the verified surface. The `::pub-chan`
  slot in `seon.server.session` is a reserved-but-`nil` Wave-4 stub (per
  its own docstring) — it is NOT the broadcast mechanism, so its emptiness
  does not make listen/broadcast hollow.
- `wire/store-config :memory` fixed-uuid is a real footgun for in-process
  tests (now documented + worked around in the fixture). Worth a follow-up
  to either parameterize the `:memory` id or expose a public test-start.

## Wave 4b readiness

Strong. The rewritten in-process `facts_test.clj` is a clean **template**
for the remaining 5 server test files: build a unique-id `:memory` cfg,
`ensure-db!` + `start-req-server!` + `start-pub-server!`, drive via
`seon.server.client`, assert on raw-conn side effects. Item E routing,
isolation, error paths, real dispatch, and cleanup are all verified
working — Item E can be marked DONE with confidence.

Do NOT blindly copy `store-config` into the other fixtures — copy the
unique-uuid cfg pattern instead, or the same cross-test leak returns.
