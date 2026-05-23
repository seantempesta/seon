---
type: research
status: active
tags: [research, pod, cljs, agent]
---

# Shadow-cljs node-script runtime registration — why MCP eval breaks on pod restart

## TL;DR

**Root cause:** the `out/client/main.js` bundle was produced by `clj -M:cljs compile client` (one-shot), not `clj -M:cljs watch client`. Shadow only injects the WebSocket-based devtools client (`shadow.cljs.devtools.client.node`) when the build is compiled by a **running watcher worker** (`:worker-info` is present in build state). Without it, the pod's Node process has no code that connects to shadow's relay → shadow's worker has zero `:runtimes` → every `(shadow/nrepl-select :client)` eval prints "No available JS runtime." `SHADOW_NODE_EVAL` is present because it's part of shadow's standard `:node-script` boot helper, but nothing in the bundle calls it through the relay — it's a no-op without the websocket client.

**Recommended fix:** keep the pod as a standalone Node process, but compile it via `clj -M:cljs watch client` (so the websocket devtools client is injected), then start the pod separately as today. Optionally cache the compiled artifact and short-circuit the watcher: shadow's only requirement is that the watcher process is *running* when MCP evals are sent, because that's the relay the pod connects to. As long as `bin/seon restart pod` only restarts the node process (not the watcher), the new pod's websocket client will reconnect to the same watcher within ~1s and shadow's `add-runtime` will fire — but only if `:repl {:runtime-select :latest}` is set in shadow-cljs config, otherwise the **stale `:default-runtime-id` from the dead runtime will block new evals**. Add that config flag.

Secondary fix: drop the cached `:runtime-id` in the MCP server by recreating sessions on `client-not-found`, and surface a clear "no runtime — is the pod running?" message when `(shadow/repl-runtimes :client)` returns `[]`.

---

## Findings

### Q1. What does `:devtools {:enabled true}` actually do for `:target :node-script`?

It does **almost nothing on its own**. `:devtools :enabled` is checked by `shared/inject-node-repl` (`shadow/build/targets/shared.clj:234-241`), which:

1. Merges `repl-defines` (closure-defines that tell `shadow.cljs.devtools.client.env` what host/port/build-id to dial) into compiler options.
2. **Prepends `shadow.cljs.devtools.client.node` to the `:main` module's `:entries`** — this is the namespace that opens the WebSocket and registers `SHADOW_NODE_EVAL` as the eval handler with shadow's relay.

**But `inject-node-repl` is only called from `node_script.clj:42-43`**:

```clojure
(cond->
  (:worker-info state)
  (shared/inject-node-repl config)
  ...)
```

`:worker-info` is set in `shadow/cljs/devtools/server/worker/impl.clj:170-197` — it's a record of the running watch worker, attached only when the build state is created by a worker. Plain `compile` (via `api/compile* → util/new-build → build/configure`, `api.clj:293-299`) does NOT carry `:worker-info`. So `clj -M:cljs compile client` produces a bundle without `shadow.cljs.devtools.client.node` even with `:devtools {:enabled true}`.

This matches the grep result: `node_modules/ws` is absent and `shadow.cljs.devtools.client` shows 0 references in `out/client/main.js`. `SHADOW_NODE_EVAL` IS present (line 87) but it's just `global.SHADOW_NODE_EVAL = function(...) { ... }` — the node bootstrap helper shadow always emits via `shadow.build.node`. Nothing in the bundle calls it through any relay.

### Q2. Canonical pattern for making `:node-script` REPL-eval-able

Shadow's own answer is **`:target :node-script` + `clj -M:cljs watch <build-id>`**. The watcher both compiles AND injects the websocket client. There is no init hook you can call from `:main` to manually wire this up — `shadow.cljs.devtools.client.node` is namespaced as `:preloads`-style (it has a top-level `(when (pos? env/worker-client-id) ...)` form at `client/node.cljs:93-192` that auto-registers when the namespace loads). You'd have to require it explicitly AND the `closure-defines` for `env/worker-client-id` would need to be baked in at compile time. The watcher does both.

`:node-repl` (different target) is what `(shadow/node-repl)` uses — it spawns a node process itself and is for interactive `node-repl` sessions, not long-running pods. Not what we want.

`node.cljs` vs `node_repl.cljs` vs `node_esm.cljs`: all three implement `IEvalJS` / `IHostSpecific` against `cljs-shared/Runtime`. `node.cljs` uses `ws` package, `node_esm.cljs` uses dynamic ws import for ESM, `node_repl.cljs` is the variant for the interactive `node-repl` target. For our `:node-script` build we want `node.cljs`.

### Q3. Does shadow's nREPL session auto-rebind when a new runtime connects?

**No, not by default.** Three relevant places:

- `worker/impl.clj:771-790` `add-runtime`: when a new websocket client connects, `:default-runtime-id` is set ONLY if `:runtimes` was empty, OR the previous default was nil, OR `:react-native`, OR system-config has `:repl {:runtime-select :latest}`. **Otherwise the dead old client-id stays the default.**
- `repl_impl.clj:140-171`: the REPL eval loop reads `(or (:runtime-id repl-state) (default-runtime-id))`. Once a session evaluated once, its `:runtime-id` is sticky — only the `::runtime-disconnect` notify path clears it (`:178-187`).
- The notify is set up at `:150-152` as a one-shot `:request-notify` query for the specific `runtime-id`. If the runtime disappears before the notify arrives (or the notify is consumed by a parallel session), subsequent evals send to a dead `:to runtime-id` and get `:client-not-found` back, which DOES dissoc the cached id.

**Implication for MCP:** even after the pod restarts and a new runtime connects, the **stale session's cached `runtime-id`** points to the dead client. The first eval will get `:client-not-found`, clear it, and the second eval will pick `:default-runtime-id`. But `default-runtime-id` is still the dead one until shadow's relay notices the disconnect AND `:runtime-select :latest` is set.

### Q4. `client.node/init` semantics

There is no explicit `init` — `shadow.cljs.devtools.client.node` registers itself at namespace-load time via top-level forms (`client/node.cljs:93-192`):

```clojure
(when (pos? env/worker-client-id)
  (extend-type cljs-shared/Runtime ...)
  (cljs-shared/add-plugin! ...)
  (cljs-shared/init-runtime! client-info start send stop))
```

`env/worker-client-id` is a closure-define injected by `repl-defines` (shared.clj:175+). When the namespace is `:require`d into the bundle, the top-level `when` runs at module-load time → opens a websocket to `(env/get-ws-relay-url)` and registers the runtime.

For our case, requiring the namespace is enough — but the closure-defines also need to be set, which only `inject-node-repl` does.

### Q5. Recommended pattern for OUR setup

**Use the watcher to compile.** The pod is independent of the watcher process lifecycle (it just dials `localhost:9630` via the websocket); the watcher is only needed:

1. At compile time, to inject the devtools client.
2. At runtime, to be the relay the pod's websocket dials.

If the watcher dies, the pod loses its REPL connection but keeps running. When the watcher comes back up the pod's websocket will reconnect on its next retry (shadow's `cljs-shared` reconnects automatically). So the dev workflow becomes:

- Terminal 1 (long-lived): `clj -M:cljs watch client` — compiles with devtools client, hosts relay on :9630, nREPL on :7889.
- Terminal 2 (the pod, restartable): `node out/client/main.js` — connects to :9630 as a runtime, registers, accepts evals.

This is already documented in `seon.client` namespace docstring lines 10-15 — but the actual build pipeline used `compile` not `watch`. **The fix is a process/dev-loop change, not a code change.**

---

## Concrete patches

### Patch 1 — make the pod always compile under watch (mandatory)

Either:

- **Always** run `clj -M:cljs watch client` (don't use `compile` for the pod), or
- Document in `docs/cljs-dev-loop.md` that `compile` produces a non-REPL-able bundle and the pod will refuse MCP eval; only use `compile` for the WASM smoke builds.

If the user wants a release-style bundle that's still REPL-able, the answer is **no — shadow ties the devtools client to the worker explicitly**. The only escape hatch is to manually require `shadow.cljs.devtools.client.node` in `seon.client` AND inject the `closure-defines` for `env` (server-host, server-port, build-id, worker-client-id, etc) yourself. This is hacky and fragile — don't do it.

### Patch 2 — `shadow-cljs.edn`: enable `:latest` runtime selection

```clojure
{:nrepl {:port 7889}
 :repl  {:runtime-select :latest}   ;; <— add
 :source-paths ...}
```

This makes `add-runtime` always set `:default-runtime-id` to the newest connected runtime. With this set, after `bin/seon restart pod`, the next MCP eval that picks default (not cached) will route to the new pod.

Source ref: `worker/impl.clj:787-788` reads `(get-in worker-state [:system-config :repl :runtime-select])`.

### Patch 3 — `bin/mcp-server-cljs`: detect stale runtime and recreate session

In `execute-eval` (`mcp-server-cljs:259-265`), check the result for evidence of a dead runtime and transparently retry with a fresh session:

```clojure
(defn execute-eval [{:keys [code session_id timeout_ms]}]
  (let [port (require-port!)
        timeout (or timeout_ms default-timeout-ms)
        {:keys [sid session-info]} (get-or-create-session! session_id)
        nrepl-sid (:nrepl-session session-info)
        result (nrepl-eval port nrepl-sid code timeout)]
    (if (and (seq (:err result))
             (or (str/includes? (:err result) "No available JS runtime")
                 (str/includes? (:err result) "previously used runtime disappeared")))
      ;; Stale session — recreate and retry once.
      (do (swap! sessions dissoc sid)
          (let [{:keys [sid session-info]} (get-or-create-session! session_id)
                nrepl-sid' (:nrepl-session session-info)
                result' (nrepl-eval port nrepl-sid' code timeout)]
            (if (str/includes? (or (:err result') "") "No available JS runtime")
              (mcp-error (str "No CLJS runtime connected to shadow watcher. "
                              "Is the pod running? Try: `node out/client/main.js` "
                              "(in a project shell with the build already compiled). "
                              "Then retry."))
              (render-eval-result result' sid))))
      (render-eval-result result sid))))
```

This makes the failure mode self-healing for the common case (pod just restarted, MCP still has old session) AND surfaces a clear actionable message when no runtime is present at all.

### Patch 4 — `execute-runtime-status` already gives the answer; expose it on error

Already good — `runtime_status` returns `:runtimes (count ...)`. Just include this info in the error message of Patch 3 so the agent sees `runtimes=0` without a separate tool call.

### Patch 5 — optional: brief retry window for "pod is restarting"

Wrap the recreate-retry in Patch 3 in a 2s retry loop (sleep 200ms × 10) so `bin/seon restart pod` → next MCP eval succeeds without the agent having to retry manually. The pod's websocket reconnect is typically <500ms after process boot.

---

## Gotchas / non-obvious behaviour

1. **`:devtools {:enabled true}` does nothing without a worker.** The config flag is checked, but the prepend to `:entries` only fires inside the worker path. Counterintuitive; the docs don't call this out.
2. **`SHADOW_NODE_EVAL` being in the bundle is a red herring.** It's the eval helper, not the relay client. Its presence does NOT mean the runtime is REPL-able.
3. **Sticky `:runtime-id` per nREPL session.** Even after enabling `:runtime-select :latest`, an MCP session that has evaluated once is bound to its first runtime-id until `:client-not-found` clears it. The first eval after a pod restart will appear to fail (it tries the dead id, gets `:client-not-found` which clears + prompts but returns nothing useful), and the second eval will succeed. Patch 3's retry hides this.
4. **The watcher relay listens on the port `env/server-port` was baked with at compile time.** If you change shadow's HTTP port (default :9630) between watch sessions and the pod was compiled against the old port, the pod will dial the wrong port. Restart of the pod (which is also a recompile-trigger if you change ports) typically fixes this — but it's a subtle failure mode if someone moves the port.
5. **`(:repl client-info)` opt-out.** Some runtimes set `{:repl false}` in their client-info to opt out of being picked as the default. Our `node.cljs` doesn't — `client-info` at line 48-50 only sets `:host` and `:desc`. So the pod will be picked. Just be aware that adding a second runtime (e.g., another shadow build pointed at the same project) would compete for `:default-runtime-id`.
6. **`shadow.cljs.devtools.client.node` requires the `ws` npm package** (`(:require ["ws" :as ws])`, line 3). Watcher's `:node-script` build will install/resolve this through node module resolution — `ws` is a shadow-cljs dependency so it's expected to be in `node_modules`. Confirm with `ls node_modules/ws` after `npm install`/`yarn` post-watch-start.
7. **Reload-on-save and pod restart are different things.** Shadow's autoload reloads CLJS code into the existing runtime via the websocket. Pod restart (new Node process) requires the websocket to re-register. The MCP server doesn't distinguish; from its view both are "runtime might have changed."
8. **Don't reach for `:node-repl` target as an escape hatch.** It spawns its own node process; not compatible with our "standalone production-shaped pod" design constraint.

---

## File references

- `/Users/sean/src/shadow-cljs/src/main/shadow/build/targets/node_script.clj:32-48` — configure, conditional `inject-node-repl`
- `/Users/sean/src/shadow-cljs/src/main/shadow/build/targets/shared.clj:234-241` — `inject-node-repl` prepends client.node + defines
- `/Users/sean/src/shadow-cljs/src/main/shadow/cljs/devtools/client/node.cljs:48-192` — runtime registration top-level forms
- `/Users/sean/src/shadow-cljs/src/main/shadow/cljs/devtools/server/worker/impl.clj:771-799` — `add-runtime`, `:default-runtime-id` selection, `runtime-select :latest`
- `/Users/sean/src/shadow-cljs/src/main/shadow/cljs/devtools/server/repl_impl.clj:140-187` — REPL eval loop, sticky `:runtime-id`, "No available JS runtime" message
- `/Users/sean/src/shadow-cljs/src/main/shadow/cljs/devtools/api.clj:293-316` — `compile*` (no worker-info), `397-409` `nrepl-select`
- `/Users/sean/src/seon/shadow-cljs.edn:38-50` — `:client` build config
- `/Users/sean/src/seon/src/seon/client.cljs:10-19` — documented dev loop (already correct; usage drifted)
- `/Users/sean/src/seon/bin/mcp-server-cljs:259-265` — `execute-eval` retry surface
- `/Users/sean/src/seon/out/client/main.js:87` — `global.SHADOW_NODE_EVAL` (helper present, ws client absent)
