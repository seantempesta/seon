---
type: research
status: active
tags: [research, web]
---

# Shadow-cljs dev port pinning vs pod detach on `bin/seon restart cljs-watch`

## TL;DR

- **The owner's premise is HALF right.** The shadow dev/HTTP port DOES rotate on
  restart (it walks `9630 → 9631 → …` when the port is momentarily still held),
  and we should pin it. But **pinning the port alone does NOT stop the running
  pod from detaching when you `bin/seon restart cljs-watch`.**
- The real cause of the detach is that `bin/seon restart cljs-watch` kills and
  re-spawns the **whole shadow server JVM**, which mints a **fresh random
  `server-token`** (`server.clj:332`) and a fresh `worker-client-id`. Both are
  **build-time-baked goog-defines** compiled into `out/client/main.js`
  (`shared.clj:196-197`, `worker/impl.clj:206-207`). The already-running pod
  still holds the **old** token; on reconnect the server replies `:access-denied`
  (`web/api.clj:97-118`), the client sets `::stale true` and **permanently stops
  reconnecting** (`shared.cljs:315-316, 342-344`). A pod restart stays mandatory
  after any server (=watch) restart, port pinned or not.
- **Proposed pin (do it anyway — it makes the port deterministic so external
  tooling/MCP and the *next* pod connect reliably):** add a top-level
  `:http {:port 9630 :strict true}` to `shadow-cljs.edn`, sibling of the existing
  `:nrepl {:port 7889}`.
- **Acme needs no port and cannot clash:** `bin/acme build` is a one-off
  `compile` (no worker), and the devtools websocket client is only injected when
  a worker exists (`node_script.clj:41-43`). The acme pod never opens a shadow
  websocket — it is inspected over its own HTTP 7980 + wire REPL 7981.
- **Reconnect-caveat verdict: BUILD-TIME-BAKED.** With the port pinned, after a
  `cljs-watch` *restart* the running pod does **NOT** reconnect on its own; a pod
  restart is still required. Pinning only removes the *port-walk* surprise, not
  the detach.

---

## 1. Which port does the `:node-script` pod connect to, and how it discovers it

The pod's devtools client (`shadow.cljs.devtools.client.node`) opens a WebSocket
to the **shadow server's HTTP port**, path `/api/remote-relay`, authenticated by
a query-param token:

- `node.cljs:52-57` — `start` calls `(env/get-ws-relay-url)` and opens
  `(ws. ws-url …)`.
- `env.cljs:96-109` — URL assembly:
  - `get-url-base` → `"http://" server-host ":" server-port` (line 99)
  - `get-ws-relay-path` → `"/api/remote-relay?server-token=" server-token` (line 106)
  - `get-ws-relay-url` → `ws://<server-host>:<server-port>/api/remote-relay?server-token=<server-token>` (line 108-109)
- `env.cljs:43` — `(goog-define server-port 8200)` — the port is a **compile-time
  goog-define** (default 8200, overwritten at build time, see §2).
- `env.cljs:55` — `(goog-define server-token "missing")` — the auth token, also a
  compile-time goog-define.
- `node.cljs:93` — the whole client only initializes
  `(when (pos? env/worker-client-id))` (default `0`, `env.cljs:33`). So the
  devtools client is **inert unless a watch worker injected a nonzero
  `worker-client-id`** — this is the acme gate, see §4.

Server side, the relay endpoint that the pod registers against:

- `web/api.clj:97-127` — `api-remote-relay` validates the token, and on success
  calls `(relay/connect …)`. That relay connection is what makes the pod show up
  as a **shadow runtime** (the thing MCP eval routes to).

So: **the pod connects to the shadow server's HTTP port** (the same port shadow's
dashboard/UI uses, default `9630`), NOT the nREPL (7889) and NOT a separate
"devtools port". There is one HTTP server per shadow **server**; the relay lives
in it.

## 2. Why the port rotates on a watch restart

`bin/seon restart cljs-watch` runs `clj -M:cljs watch client` (`bin/seon:181`).
That command starts a **brand-new shadow server JVM** every time:

- `cli_actual.clj:127-128` — `:watch` dispatches to `server/from-cli`.
- `server.clj:666-667` — `from-cli` calls `(start!)` when no in-JVM instance
  exists (always true for a fresh `clj -M:cljs` JVM).
- `server.clj:431` + `:410-414` — `start!` → `check-for-other-instance!` probes
  `http://localhost:<port>` and **throws** if another shadow is already running
  in this project. So watch never *attaches* to an existing server; it owns its
  own.

The HTTP port is chosen by `start-http`:

```clojure
;; server.clj:180-198
(loop [port (or port 9630)]            ; :http {:port …} or default 9630
  (let [srv (try (http-server/start (assoc http-config :port port) handlers)
              (catch Exception e
                (cond
                  strict           (throw e)                         ; :strict true → fail loud
                  (instance? BindException e) (log/warn ::tcp-port-unavailable {:port port})
                  :else (do (log/warn-ex e ::http-startup-ex) (throw e)))))]
    (or srv (recur (inc port)))))       ; port taken → try port+1
```

- `server.clj:221-223` — `http-config` = `(merge {:host "0.0.0.0"} (:http config))`.
- `config.clj:92-94` — the default config has **no `:http` key**, so `port` is
  `nil` → `(or port 9630)` starts at **9630** and **walks `+1` on every
  `BindException`** (line 198).

**Why it rotates:** on a restart the old watch JVM may not have fully released
the listening socket (slow JVM shutdown hook / OS `TIME_WAIT`) when the new JVM
binds. The new server hits `BindException` on 9630 → `(recur (inc port))` → binds
**9631**. The walk is silent (only a `::tcp-port-unavailable` warn). It is
**per-server** (one HTTP port per server), not per-build. Our config currently
pins only `:nrepl {:port 7889}` (`shadow-cljs.edn:9`) and leaves `:http`
unpinned, so the HTTP/relay port is exactly the value that walks.

The bound port is then baked into the bundle at build time:

- `worker/impl.clj:170-175` — `worker-info` captures `:host (:host http)` and
  `:port (:port http)` (the *actual* bound HTTP port).
- `shared.clj:196-197` — `repl-defines` sets the
  `shadow.cljs.devtools.client.env/server-port` closure-define to that `port`.
- `worker/impl.clj:206-207` — same mechanism bakes `worker-client-id` and
  `server-token`.

So a rebuilt `out/client/main.js` carries the *new* port + *new* token — but the
**already-running pod has the OLD bundle in memory** and never sees the update.

## 3. How to pin it

**Port — pinnable.** Add a top-level `:http` map to `shadow-cljs.edn` (sibling of
`:nrepl`). `:strict true` turns the silent walk into a loud failure, so a
restart that races the old socket fails fast instead of landing on a surprise
port:

```clojure
;; shadow-cljs.edn (top level, next to :nrepl {:port 7889})
:http {:port 9630 :strict true}
```

`9630` is shadow's own default and is otherwise unused by seon (pod HTTP 7890,
nREPL 7889/7888, wire REPL 7891, acme 7980/7981). Pick any free dedicated port;
9630 keeps it aligned with shadow's conventions and any editor tooling that
assumes it.

**Token — NOT pinnable.** There is **no config key** for the relay token.
`server.clj:332` unconditionally overrides whatever you put in `:http`:

```clojure
:http (assoc http :server-token (str (UUID/randomUUID)))   ; fresh per server start
```

The written `.shadow-cljs/server.token` file (`server.clj:362-363`) is
write-only — never read back at startup. So a new server always means a new
token, and you cannot make a restarted watch present the same token the running
pod baked in. This is the load-bearing reason §5's verdict is "pod restart still
required".

## 4. Both paths: seon `:client` vs acme

**Seon `:client` (a watch):** `bin/seon:181` = `clj -M:cljs watch client`. This is
a worker build → `worker-info` exists → the devtools client + nonzero
`worker-client-id` are injected (`node_script.clj:41-43`), so the pod opens the
relay websocket on the server's HTTP port. This is the path that detaches. Pin
its HTTP port per §3.

**Acme (a one-off compile):** `bin/acme build` runs a `compile` of `:acme-client`
(per the `shadow-cljs.edn:79-91` comment and the acme harness docs), **not a
watch**. Critical gate:

```clojure
;; node_script.clj:41-43 — devtools/repl client injected ONLY when a worker exists
(cond->
  (:worker-info state)
  (shared/inject-node-repl config)
  …)
```

A plain `compile` has **no `worker-info`** → `inject-node-repl` is skipped →
`worker-client-id` stays at its goog-define default `0` → the devtools client's
`(when (pos? env/worker-client-id))` guard (`node.cljs:93`) is false → **the acme
pod never opens a shadow websocket at all.** Its `:devtools {:enabled true}` in
the build config is inert under `compile`. This matches the harness design (acme
is inspected over HTTP 7980 + wire REPL 7981, "not MCP").

**Conclusion for clash-safety:** acme connects to no shadow server, needs no
pinned port, and **cannot clash** with the `:client` server's pinned 9630.
Pinning `:client`'s HTTP port is safe for acme. (If acme were ever switched to a
*watch*, it would start its own second server and `check-for-other-instance!`
would force a different port — that is the future concern flagged in the
`shadow-cljs.edn` comment, not a problem today.)

## 5. CRITICAL caveat: build-time-baked vs runtime-discovered — reconnect verdict

**Verdict: BUILD-TIME-BAKED. After a `cljs-watch` restart on the same pinned
port, the running pod does NOT reconnect on its own — a pod restart is still
required.**

Reasoning from the reconnect logic:

1. `server-port` and `server-token` are **goog-defines** (`env.cljs:43,55`)
   compiled into `out/client/main.js` (`shared.clj:196-197`,
   `worker/impl.clj:206-207`). A running Node process holds the values from the
   build that produced *its* bundle; rewriting the on-disk file does nothing to
   the live process.
2. The client *does* auto-reconnect in general — `shared.cljs:319-374`
   (`remote-close` → `schedule-connect!` after 5 s; idle-fn at `:440-450`). So a
   transient blip self-heals.
3. **But** on a watch *restart* the new server has a new random token. Two
   failure shapes, both terminal:
   - **Port pinned (same port):** the pod connects, presents the **stale token**,
     the server returns `:access-denied` (`web/api.clj:106-118`). The client
     marks itself stale: `shared.cljs:315-316` sets `::stale true`, and both
     `attempt-connect!` (`:342-344`) and `schedule-connect!` (`:364`) refuse to
     reconnect once `stale`. **Permanent give-up.**
   - **Port not pinned (walked away):** nothing listens on the old baked port →
     `remote-error` increments `::ws-errors`; after `> 3` errors
     (`shared.cljs:324-326`) the client logs "giving up trying to connect" and
     stops. **Also permanent give-up.**
4. `worker-client-id` self-heals on a *fresh relay connect* via the
   `on-welcome` `request-clients` handler (`shared.cljs:548-552`) — but that path
   never runs here because the connection is rejected (token) or refused (port)
   before welcome.

Therefore pinning the port **changes the failure mode** (deterministic
`access-denied` instead of a port-walk) but **does not eliminate the pod
restart**. The only way to "restart the build" *without* a pod restart is to keep
the shadow **server** alive — i.e. don't kill the JVM; rely on hot-reload, and
treat a full `bin/seon restart cljs-watch` as inherently requiring a paired pod
restart. A future option (not in scope here) is to split cljs-watch into a
long-lived `clj -M:cljs server` (stable token + pinned port) plus a worker you
can bounce; that is the only architecture in which "restart the watch" leaves the
pod attached, and even it has a `worker-client-id`-staleness wrinkle for REPL
routing.

---

## Proposed config diff (PROPOSE ONLY — do not apply unilaterally)

```clojure
;; shadow-cljs.edn — add as a top-level key, sibling of :nrepl
 :nrepl {:port 7889}

+;; Pin the shadow HTTP/relay port so it does not silently walk 9630→9631…
+;; on a restart race (start-http loop, server.clj:180-198). :strict fails
+;; loud instead of walking. NOTE: this does NOT keep a running pod attached
+;; across a `restart cljs-watch` — the server-token is re-minted per server
+;; start (server.clj:332) and is build-time-baked, so a pod restart is still
+;; required after any watch restart. The pin makes the port deterministic for
+;; tooling + the next pod.
+ :http  {:port 9630 :strict true}
```

`deps.edn` needs **no change** — the `:cljs` alias only supplies the build
classpath; port config lives entirely in `shadow-cljs.edn`.
