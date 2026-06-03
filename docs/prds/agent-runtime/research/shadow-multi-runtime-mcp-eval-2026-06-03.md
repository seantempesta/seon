---
type: research
status: draft
tags: [research, agent, flow]
---

# Shadow multi-runtime MCP eval — N Node agents, one never-restarted MCP server (2026-06-03)

> **Goal (Sean's words):** "multiple agents that all agents can use via MCP eval
> without having to restart the MCP servers." Concretely: one long-lived MCP
> eval endpoint must eval CLJS into ANY of N running Node agent runtimes,
> addressed by id, and keep working as those runtimes connect / disconnect /
> crash-restart — the MCP server and its shadow connection MUST NOT restart when
> agents churn. This doc makes that the spine.
>
> **Scope:** Friday proof-of-concept, Node-first (no wasm). Optimize SIMPLE +
> STABLE over elegant. Grounded entirely in the vendored shadow-cljs source at
> `reference-code/shadow-cljs` (read directly; live :9630 server left untouched).

## TL;DR

- **shadow's native multi-runtime model is exactly the right answer — do NOT
  hand-roll per-process socket-REPLs.** A `:node-script` build run as N separate
  Node processes registers as **N distinct addressable runtimes** under ONE
  shadow watch worker. Each is keyed by an integer `client-id` (the relay mints
  `(swap! id-seq-ref inc)` on every websocket connect — `relay/local.clj:157`).
  `:runtimes` is `{client-id → client-info}` (`worker/impl.clj:790`).
- **The per-id targeting primitive already exists and is stable:**
  `(shadow.cljs.devtools.api/nrepl-select :client {:runtime-id <client-id>})`.
  That `:runtime-id` is stored in the nREPL session's `*repl-state* :opts`
  (`nrepl_impl.clj:39`), read on **every** eval (`nrepl_impl.clj:163`), and
  forced as the `:to` target in `do-repl` (`repl_impl.clj:140-163`). A session
  pinned this way **never** falls back to `:default-runtime-id` — it always
  targets the chosen runtime. This is the eval-into-agent-k mechanism.
- **The stable anchor (what stays up) = the shadow watch server (one OS process:
  relay on :9630 + nREPL on :7889) + the MCP server's stdio process + ONE TCP
  connection per eval to that nREPL.** **What churns = the Node agent processes
  (runtimes).** When agent k dies and respawns it gets a *new* client-id; the
  MCP server re-resolves agent-id→client-id at eval time and re-pins — **no MCP
  restart, no shadow restart.** The only thing that must never go down is the
  watch server; if it dies, every runtime loses its relay (but the agents keep
  running and auto-reconnect when it returns — `shared.cljs` reconnect loop).
- **client-id is NOT stable across a process restart** (it's a fresh integer
  each connect). So the MCP server must **resolve agent-id → current client-id
  dynamically per eval**, via a small `{agent-id → client-id}` map kept on the
  shadow JVM, populated by each agent self-registering its own client-id on
  boot. The MCP `eval` tool gains one param: `agent_id`. (Full mechanism §4.)
- **dev vs release CONFIRMED:** only `watch`/`compile` *under a running worker*
  inject `shadow.cljs.devtools.client.node` (the websocket runtime client).
  `release` and bare `compile` do not → no REPL. For V2.0 Node agents we use
  **`watch`** (or pre-compiled-under-watch artifacts). Release builds are a
  V2.1/wasm concern only. (Re-confirmed against `node_script.clj` +
  `shared/inject-node-repl`; matches prior research `shadow-node-runtime-2026-05-23`.)
- **Cleanest setup = ONE build (`:client` / a dedicated `:agent` build), run N
  times.** Not N builds, not `:node-library`. One `watch` compiles once; N
  `node out/.../main.js` processes connect as N runtimes. (§3.)
- **JVM/DB-server REPL is separate and also no-restart**, via `session/!registry`
  (one REPL reaches every cluster). **BLOCKER confirmed:** the `:writer` alias
  the Rust host shells (`main.rs:1041 clojure -M:writer`) **does not exist in
  `deps.edn`** — the launch path is broken and must be fixed before P1. (§6.)
- **Honest unknowns needing a live probe** (could not be settled without
  touching :9630): (a) does a second/third Node process of `:client` actually
  appear as a distinct runtime in `(shadow/repl-runtimes :client)` here, or does
  some build-singleton assumption interfere; (b) does an agent self-register its
  client-id reliably on boot before the first MCP eval; (c) reconnect timing
  after `SIGKILL`. All three are 5-minute REPL checks once a window opens. (§8.)

---

## 1. shadow-cljs runtime model (Q1)

### How a `:node-script` process becomes an addressable runtime

1. **Compile-time injection.** `shared/inject-node-repl` prepends
   `shadow.cljs.devtools.client.node` to the `:main` module entries **and** bakes
   the closure-defines (`env/server-host`, `server-port`, `build-id`,
   `worker-client-id`, `proc-id`) — but **only when `:worker-info` is present**,
   i.e. the build was compiled by a running `watch` worker
   (`node_script.clj:42`, `shared.clj:234-241`). Bare `compile`/`release` skip it.
2. **Runtime registration.** `client/node.cljs:93` has a top-level
   `(when (pos? env/worker-client-id) … (cljs-shared/init-runtime! client-info …))`
   that runs at module load: it opens a websocket to `env/get-ws-relay-url`
   (the watch server's relay, :9630) and handshakes.
3. **client-id assignment.** The relay assigns a fresh integer client-id on
   connect: `relay/local.clj:157 (let [client-id (swap! id-seq-ref inc)] …
   (swap! state-ref assoc-in [:clients client-id] client-data))`.
4. **Worker bookkeeping.** On `:client-connect`, `add-runtime`
   (`worker/impl.clj:771-790`) does `(update :runtimes assoc client-id
   (assoc client-info :client-id client-id))`. **`:runtimes` is a map keyed by
   client-id** — so N node processes coexist as N entries.

### Can N processes of ONE build be N distinct runtimes? — YES (by construction)

`:runtimes` is keyed by the per-connection integer client-id, not by build-id.
Nothing in `add-runtime` dedups by host/proc. Three `node out/client/main.js`
processes → three websocket connects → three client-ids → three `:runtimes`
entries under the single `:client` worker. (Probe-confirm in §8 since we've never
run >1 concurrently here.)

### How a specific runtime is selected per eval

Two listing/selection fns (`api.clj:223-235`):

```clojure
(shadow/repl-runtimes :client)              ; => [{:client-id 1 :host :node :desc … :build-id …} …]
(shadow/repl-runtime-select :client 2)      ; sets worker's :default-runtime-id to 2 (process-global)

```

`repl-runtime-select` changes the **worker-global default** — wrong for our
multi-agent case (it's a single shared default; two MCP sessions would fight
over it). The right primitive is **per-nREPL-session pinning** via `nrepl-select`
opts (§4), which never touches the global default.

---

## 2. The no-restart guarantee (Q2)

### What stays up vs what churns

| Component | Lifecycle | Notes |
|---|---|---|
| **shadow watch server** (relay :9630 + nREPL :7889) | **stays up** — the one true anchor | If it dies, all runtimes lose the relay; agents keep running & auto-reconnect when it returns. This is the single process that must not churn. |
| **MCP server** (`bin/mcp-server-cljs`, stdio) | **stays up** | Stateless w.r.t. runtimes; opens a fresh TCP conn to :7889 per eval, reads port from `.shadow-cljs/nrepl.port` each call. |
| **MCP→nREPL connection** | per-eval, transient | `connect` → `send-and-collect` → close. Nothing pinned at the socket level. |
| **nREPL session** (the `clone`d session id + its `*repl-state*`) | **survives agent churn** | The session is server-side state on the watch JVM. It holds `:opts {:runtime-id k}`. It does NOT die when the runtime dies. |
| **Node agent runtimes** | **churn freely** | spawn / disconnect / crash-restart. Each restart = new client-id. |

### The eval path that survives a runtime restart

`do-cljs-eval` (`nrepl_impl.clj:135`) builds a fresh `do-repl` loop **per eval
message**, seeding `init-state {:runtime-id (get-in repl-state [:opts :runtime-id])}`.
Inside `do-repl` (`repl_impl.clj:140`):

```clojure
(let [runtime-id (or (:runtime-id repl-state)                ; ← our pinned id
                     (-> worker :state-ref deref :default-runtime-id))]  ; fallback
  (if-not runtime-id
    (repl-stderr … "No available JS runtime.")
    (>!! to-relay {:op :cljs-eval :to runtime-id :input {…}})))

```

If the pinned runtime-id points at a now-dead client, the relay replies
`:client-not-found` (`repl_impl.clj:178`) → `do-repl` prints "previously used
runtime disappeared" and **dissocs `:runtime-id`**. The MCP server's existing
`stale-runtime?` detector (`mcp-server-cljs:259-286`) catches both the loud and
silent shapes and triggers recovery.

### How re-targeting the *new* runtime works WITHOUT reconnecting the MCP server

Because client-id changes on restart, the recovery is **not** "reuse the old
session" — it's "re-resolve agent-id → new client-id and re-pin". Concretely
(the §4 design): the MCP server, on `stale-runtime?`, re-queries the agent
registry for agent k's *current* client-id, re-runs
`(shadow/nrepl-select :client {:runtime-id <new-id>})` on a fresh session, and
retries. All of this is plain nREPL evals over the same long-lived MCP stdio
process and the same watch server. **Nothing about the MCP server or shadow
restarts.**

### The ONLY failure mode that would force an MCP/shadow restart

The **watch server itself going down** (relay :9630 / nREPL :7889 dead). Then
every runtime's relay is gone and the MCP server can't reach any nREPL. Avoidance:

- Run the watch server under `bin/seon` supervision as its own registered
  process (`cljs-watch`), independent of agent lifecycles, so restarting agents
  never touches it.
- Never co-launch the watch server with an agent process. Agents are children of
  the supervisor, not of the watcher.
- The MCP server already re-reads `.shadow-cljs/nrepl.port` each call, so even a
  watcher restart (new port) self-heals on the next eval without an MCP restart —
  but that *does* drop all sessions/runtimes, so treat watcher restart as a
  cluster-wide event, not routine churn.

---

## 3. dev vs release + cleanest setup for N agents (Q3, Q4)

### Release strips the REPL — CONFIRMED

`inject-node-repl` is gated on `:worker-info`, set only on the `watch` path
(`node_script.clj:42`). `release` (and bare `compile`) produce a bundle with **no
`shadow.cljs.devtools.client.node`** and no relay defines → the process never
connects as a runtime → not eval-able. This matches the prior root-cause finding
(`shadow-node-runtime-2026-05-23` §Q1) and the existing `shadow-cljs.edn`
comment. **For V2.0 Node agents we always use `watch`.** Release/`:simple`
bundles (`:smoke`, `:eval-smoke`, `:guest-agent`) are wasm-pipeline artifacts —
irrelevant to the Node-agent REPL story. Release-without-REPL only matters at
V2.1 (wasm), where the in-guest REPL is a different mechanism entirely (see
`repl-access-design-2026-06-03`).

### Recommendation: ONE build, run N times

| Option | Verdict |
|---|---|
| **One build (`:agent`), N processes** | ✅ **Recommended.** One `watch` compiles once; N `node main.js` connect as N runtimes under one worker. Simplest, most stable, exactly shadow's model. |
| N builds (one per agent) | ✗ N watch workers, N nREPL pivots, N× compile cost, no shared hot-reload. Pure overhead. |
| `:node-library` | ✗ For embedding into a host JS app; same REPL-injection gating, no benefit, more wrapper friction. |
| `:node-repl` target | ✗ Spawns its *own* node process for interactive REPL; not for long-lived independent agent processes. |

Concrete config — a dedicated agent build (keeps `:client` free of test
preloads / overlay hazards):

```clojure
;; shadow-cljs.edn — add alongside existing builds
:agent
{:target    :node-script
 :output-to "out/agent/main.js"
 :main      seon.client-runtime.agent/-main
 :devtools  {:enabled true}      ; redundant for :node-script but explicit
 :compiler-options {:warnings-as-errors false
                    :externs ["externs/node_fs.js"]}}

```

(For Friday you can reuse `:client` directly — it already injects the REPL under
watch. A dedicated `:agent` build is the clean version; not required to demo.)

Commands (the whole bring-up is in §7):

```bash
clj -M:cljs watch agent           # compiles once + hosts relay+nREPL; stays up
node out/agent/main.js --agent-id a1 --db cluster/alpha &   # runtime 1
node out/agent/main.js --agent-id a2 --db cluster/alpha &   # runtime 2
node out/agent/main.js --agent-id a3 --db cluster/beta  &   # runtime 3

```

Note the **single global `:source-paths`** caveat in `shadow-cljs.edn`: the
guest overlay (`guest-cljs/src-overlay`) is listed first and shadows `seon.db`
for ALL builds that transitively require it. A dedicated `:agent` build that
requires the V0 agent loop must decide whether it wants the overlay `seon.db`
(wire-client) — for V2.0 it *does* (agents talk to the wire-server), so the
overlay is correct here. Confirm the `:agent` build's transitive `seon.db`
resolves to the overlay before relying on it.

---

## 4. The MCP layer — eval into agent k by id, one stable connection (Q5)

### Current state

`bin/mcp-server-cljs` (the `seon_cljs` MCP server in `.mcp.json`) already:

- discovers the watch nREPL port from `.shadow-cljs/nrepl.port` per call;
- `clone`s an nREPL session and pivots with `(shadow/nrepl-select :client)`
  (no runtime-id → uses worker default);
- relies on `:repl {:runtime-select :latest}` so a single restarted pod becomes
  the default;
- self-heals stale runtimes by dropping the session and re-pivoting.

This works for **ONE** runtime. It cannot address agent k of N — `:latest`
collapses all agents to "the most recently connected", and a bare pivot uses the
single worker-global default. **Per-id targeting needs the `:runtime-id` opt.**

### The mechanism — keep the tool shape, add `agent_id`, pin per session

shadow already supports per-message runtime targeting via the nREPL session's
`*repl-state* :opts :runtime-id`. The plumbing (verbatim chain):

1. `nrepl-select` passes `opts` to `*nrepl-init*` (`api.clj:406`).
2. `repl-init` stores `*repl-state* {:opts opts …}` (`nrepl_impl.clj:39`).
3. `do-cljs-eval` reads `:runtime-id (get-in repl-state [:opts :runtime-id])`
   into every eval's `init-state` (`nrepl_impl.clj:163`).
4. `do-repl` uses `(:runtime-id repl-state)` as `:to`, **never** consulting the
   global default while it's set (`repl_impl.clj:140-163`).

So the design is: **one nREPL session per agent, pinned to that agent's current
client-id.** The MCP server keeps a `{agent-id → {nrepl-session, client-id}}`
map (replacing the current single "default" session).

#### agent_id → client-id resolution (handles the unstable client-id)

client-id is a fresh integer per connect, so the MCP server must learn agent k's
*current* client-id. Two viable seams; the first is simplest and most stable:

- **(A) Agent self-registers on boot (recommended).** The agent's `-main`, once
  its websocket is up and it knows its own client-id (the welcome op sets it;
  `node.cljs:141` already logs `#<client-id> ready!`), evals a registration form
  into the shadow JVM that writes `{agent-id → client-id}` to a plain atom living
  in a tiny CLJ ns on the watch server's classpath
  (`seon.dev.agent-registry/!agents`). The MCP server reads that atom over nREPL
  to resolve. This is robust to restarts: a respawned agent overwrites its entry
  with the new client-id on boot. *Caveat:* the agent must register before the
  first eval targeting it — gate with a short retry (§8 probe (b)).

- **(B) MCP queries `repl-runtimes` and matches client-info.** If the agent
  process tagged its `client-info` with `:agent-id`, the MCP server could
  `(shadow/repl-runtimes :client)` and find the matching client-id. BUT the
  stock `client/node.cljs` `client-info` is fixed `{:host :node :desc …}` with
  **no injection hook** — adding `:agent-id` means either patching the vendored
  node client or wrapping `init-runtime!`. More invasive than (A). Defer.

Use **(A)**. It needs one ~10-line CLJ ns and one registration eval in the agent
boot. No shadow patching.

#### Tool shape (minimal delta to the existing server)

```text
mcp__seon_cljs__eval  { code, agent_id, timeout_ms }     # agent_id NEW, optional

```

`execute-eval` flow:

```clojure
(defn ensure-pinned-session! [port agent-id]
  ;; resolve current client-id for this agent from the shadow-JVM registry
  (let [cid (resolve-client-id! port agent-id)]   ; nREPL eval reading !agents atom
    (or (when-let [s (get @sessions agent-id)]
          (when (= cid (:client-id s)) s))         ; reuse if still same runtime
        (let [nsid (nrepl-clone-session port)]
          (nrepl-eval port nsid
            (str "(require '[shadow.cljs.devtools.api :as shadow]) "
                 "(shadow/nrepl-select :client {:runtime-id " cid "})")
            10000)
          (let [s {:nrepl-session nsid :client-id cid :created-at …}]
            (swap! sessions assoc agent-id s) s)))))

```

On `stale-runtime?` (the existing detector): drop that agent's pinned session,
re-resolve client-id (the restarted agent has re-registered its new id), re-pin,
retry — the same self-heal already in the file, now keyed by agent-id instead of
"default". **This is the entire eval-into-agent-k-without-MCP-restart story.**

Backward-compat: `agent_id` absent → fall back to today's `:runtime-select
:latest` behaviour (single-agent dev). `:runtime-select :latest` can stay in
`shadow-cljs.edn`; it only governs the *unpinned default*, which pinned sessions
ignore.

---

## 5. The JVM / DB-server REPL is separate (Q6)

The wire-server (`src/seon/server/`) is a **plain Clojure JVM** — its REPL is a
Clojure socket-REPL (`clojure.core.server`) or an nREPL, **not** shadow. It has
nothing to do with the shadow relay. Its no-restart property is structural:

- **One REPL reaches every cluster** via `seon.server.session/!registry`
  (`{db-name → {::conn …}}`) and `!agents` (`{agent-id → db-name}`)
  (`session.clj:134,178-218`). A human/MCP at that REPL calls `get-conn` /
  `resolve-agent` / `list-sessions` to inspect any cluster's datahike conn —
  no per-cluster connection, nothing to restart as clusters come and go.
- The existing JVM MCP server (`bin/mcp-server`) targets a fixed nREPL port
  (7888) on the long-lived seon.runner JVM — same no-restart shape: one stable
  port, sessions cloned per call.
- For V2 the wire-server is a *new* JVM; its socket-REPL is a third endpoint,
  designed in `repl-access-design-2026-06-03` §1 (localhost, flag-gated,
  port-file, off by default).

**BLOCKER (also flagged in repl-access-design §7):** the Rust host shells
`clojure -M:writer` (`main.rs:1041`) but **`:writer` is absent from `deps.edn`**
(re-verified: `grep writer deps.edn` → no match). The wire-server launch path is
currently broken. The JVM-REPL flag (a `-Dclojure.server.repl` JVM-opt) attaches
to whatever re-introduces that launch (a real `:writer` alias, or an in-process
`wire/start!`). **Must be fixed before P1.**

---

## 6. Verifying the existing plan (Q7)

| Prior claim | Holds against shadow's real model? |
|---|---|
| "hand-roll per-process socket-REPLs" (Sean's earlier lean) | **NO — superseded.** shadow's native multi-runtime (N runtimes under one worker, per-session `:runtime-id` pinning) is simpler and more stable than rolling a socket-REPL into each Node process. Use shadow. *This doc corrects that lean.* |
| `platform-v2-node-first-plan` "Per-agent REPL … each agent is its own Node process with its own REPL port" | **Partly.** True that each is its own process with independent failure domain (the valuable part). But the *eval transport* should NOT be a separate per-process REPL port — it's the shared shadow nREPL with per-session runtime pinning. One nREPL, N runtimes; not N nREPLs. |
| `repl-access-design` "socket-REPL not nREPL" for the JVM writer | **Holds** — that's the *JVM* side, unrelated to shadow. Good as written. |
| `repl-access-design` `:writer` alias missing | **Confirmed broken.** |
| `clusters-and-multi-db-wiring` `!registry` as the one-REPL multiplexer | **Holds** for the JVM side. |
| `:repl {:runtime-select :latest}` in `shadow-cljs.edn` | **Keep, but it's insufficient alone for N agents** — it only sets the *default* runtime. Pinned sessions (§4) are what give per-agent addressing. `:latest` remains the single-agent fallback. |
| Prior `shadow-node-runtime-2026-05-23` (dev-vs-release, self-heal) | **Holds and is the foundation.** This doc extends it from 1 runtime to N. |

**Net:** the architecture is sound; the one design change is **embrace shadow's
multi-runtime + per-session `:runtime-id` pinning** instead of per-process
REPLs, and add the agent self-registration atom + `agent_id` MCP param.

---

## 7. Friday-POC bring-up (Q8) — minimal ordered sequence

Prereqs to fix first (both are real blockers, both small):

- **F0a.** Add a `:writer` alias to `deps.edn` (or switch the host to in-process
  `wire/start!`) so the JVM wire-server actually launches. (§5.)
- **F0b.** Add `seon.dev.agent-registry` (a ~10-line CLJ ns on the `:cljs`
  classpath with `(defonce !agents (atom {}))`, `register!`, `lookup`) so agents
  can self-register client-ids. (§4A.)

Then:

1. **JVM multi-DB wire-server up** with a flag-gated socket-REPL:
   `clojure -M:writer -Dclojure.server.repl='{:port 7899 :accept clojure.core.server/repl}'`
   (after F0a). Verify `seon.server.session/list-sessions` reachable.
2. **shadow watch up** (the stable anchor), supervised independently:
   `bin/seon start cljs-watch` (`clj -M:cljs watch agent`, or `client` for the
   shortcut). Confirm `.shadow-cljs/nrepl.port` written, relay on :9630.
3. **Spawn 2–3 Node agents** (the churning part), one build run N times:
   `node out/agent/main.js --agent-id a1 --db cluster/alpha &` (×3, distinct
   agent-ids). Each connects to :9630, gets a client-id, self-registers
   `{agent-id → client-id}` into `seon.dev.agent-registry/!agents`.
4. **Verify N distinct runtimes:** from the seon_cljs MCP `runtime_status`, or
   `(shadow/repl-runtimes :client)` — expect 3 entries with 3 client-ids.
   *(This is probe (a) — the go/no-go that N processes = N runtimes here.)*
5. **MCP eval into each by id:** `mcp__seon_cljs__eval {agent_id "a1", code
   "(do (println :hello-from a1) :a1)"}`, repeat for a2, a3 — confirm each lands
   in the right process (println shows in the right agent's stdout; return value
   distinct). *This is the headline demo.*
6. **Churn test (the requirement):** `SIGKILL` agent a2's process; respawn it
   (`node … --agent-id a2 …`); **without restarting the MCP server or shadow**,
   `mcp__seon_cljs__eval {agent_id "a2", code "…"}` — first eval may hit the
   stale id, self-heal re-resolves the new client-id, second eval succeeds.
   Demonstrate a1/a3 evals never broke during a2's churn.
7. **Something visible:** have each agent transact a datom to its cluster DB via
   the wire-client on eval; observe from the JVM socket-REPL
   `(d/q … (d/db (get-conn …)))` that cluster/alpha has a1+a2's writes and
   cluster/beta has a3's — multi-DB isolation + per-agent eval, end to end.

Minimal. No wasm, no release builds, no per-process REPL ports.

---

## 8. Honest unknowns needing a live probe

Could not be settled from source alone (and the live :9630 server was off-limits
this session). Each is a <5-minute REPL check once a window opens:

- **(a) N processes = N runtimes here?** Source says yes (`:runtimes` keyed by
  per-connect client-id; no dedup). But we've only ever run ONE node runtime
  against this watcher. **Probe:** start 2 `node out/client/main.js`, eval
  `(shadow/repl-runtimes :client)`, expect 2 distinct client-ids. **Go/no-go for
  the whole multi-runtime approach.** If it surprises us (build singleton, relay
  rejecting a second client of the same build), fall back is a dedicated build
  per agent (uglier, but each is unambiguously its own worker).
- **(b) Self-registration race.** Does the agent know its client-id and register
  it before the first MCP eval targets it? The welcome op sets client-id
  (`node.cljs:141`) — but that's after websocket handshake, async to `-main`.
  **Probe:** measure boot→registered latency; gate MCP resolve with a short
  retry (200ms × N) if the registry lookup misses. Likely sub-second.
- **(c) Reconnect timing after SIGKILL.** How fast does a respawned agent
  re-handshake and re-register? Prior single-pod data was <500ms–1s. **Probe:**
  the step-6 churn test; confirm the self-heal retry window (currently 10×200ms =
  2s in `mcp-server-cljs`) covers it.
- **(d) Does a stale pinned `:runtime-id` reliably yield `:client-not-found`?**
  Source path is clear (`repl_impl.clj:178` dissocs on `:client-not-found`), and
  the existing `stale-runtime?` catches both loud+silent shapes — but verify the
  *pinned* case (not just the default-fallback case the current code was written
  for) trips the same detector. Likely yes; confirm in step 6.

### Version caveat (vendored master vs pinned 3.4.10)

The vendored `reference-code/shadow-cljs` is **untagged master**
(`git describe` → `2.11.23-959-g8236315a`), while `deps.edn` pins
`thheller/shadow-cljs 3.4.10`. The runtime API this design depends on —
`repl-runtimes`, `repl-runtime-select`, `nrepl-select` `opts {:runtime-id}`,
`:runtimes` map keyed by client-id, `*repl-state* :opts :runtime-id` threaded
through `do-cljs-eval`/`do-repl` — is **long-stable shadow API** present and
unchanged across these files for years; recent commits to the relevant files are
UI/browser-repl fixes, not runtime/nrepl-eval changes. **Low risk of gap**, but
the §8(a) probe also implicitly validates that 3.4.10's behaviour matches the
source we read.
