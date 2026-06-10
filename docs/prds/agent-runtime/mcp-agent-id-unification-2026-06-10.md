---
type: prd
status: draft
tags: [prd, agent]
---

# MCP agent-id unification — substrate `:seon.agent/id` is THE id (2026-06-10)

User ruling (2026-06-10): ONE naming convention for addressing agent
runtimes, no parallel id schemes. The MCP eval `agent_id` parameter, the
shadow runtime probe, and the cluster store's agent entities must all
speak the SAME id: the substrate `:seon.agent/id`.

## 1. The two conventions today (verified, with anchors)

### 1.1 Substrate agent ids

- `:seon.agent/id` is the identity attr of agent entities:
  `src/seon/agent.cljs:124` —
  `(schema/register! :seon.agent/id [:and {:seon.db/identity true} :seon.db/id])`.
- Minted by `seon.db/new-id!` (`src/seon/db.cljs:190-202`): 14-char
  `<3-letter-random>-<YYMMDDHHmm>`, e.g. `iCg-2606101519`.
- Minted FRESH at every pod boot: `src/seon/client.cljs:1388`
  (`agent-id (db/new-id!)` inside `start-agent!`). This is the open
  identity-durability finding — agents accumulate one per boot
  (main PRD §7 item 8, `cljs-finish-clj-pivot-plan-2026-06-09.md:296-299`).
- Everything keys off it: lookup refs `[:seon.agent/id id]`
  (`agent.cljs:146,535,562,703`), messaging from/to refs
  (`agent.cljs:743,975-976`), home-ns derivation
  (`agent.cljs:448-450`, `(home-ns "seon") => 'seon.agent.seon`,
  pending rename to `my.agent.<id>` per main PRD line 496).

### 1.2 MCP runtime-addressing ids

- `bin/mcp-server-cljs:56-57` — `agent-sessions` atom
  (agent-id string → `{:nrepl-session :client-id :build}`) plus a
  HARDWIRED `agent-build-id ":node-agent"`.
- Resolution = probe loop, `bin/mcp-server-cljs:261-279`
  (`resolve-agent-client-id!`): enumerate
  `(shadow/repl-runtimes :node-agent)`, pin each client-id
  (`pin-session!`, lines 248-259), eval
  `(seon.dev.node-agent/agent-id)` (line 275), string-match.
  `ensure-agent-session!` (281-295) caches; `execute-agent-eval`
  (378-399) retries through crash+respawn; the eval tool's `agent_id`
  param rides this (line 488; dispatch at 401-405).
- The runtime side: `src/seon/dev/node_agent.cljs:23-29` — a private
  `!agent-id` atom answered by `agent-id`; set from `--agent-id` argv
  or `SEON_AGENT_ID` env (lines 40-50, `-main` 52-68). Ids here are
  HAND-ASSIGNED strings (`a1`, `wire`) with no relation to the store.
- The duplication: `src/seon/store/internal/wire_node.cljs:49-52`
  keeps a SECOND private `!agent-id` atom (defaulting `"wire"`) plus
  its own `agent-id` fn, AND requires `seon.dev.node-agent`
  (lines 29-31) purely to call `node-agent/set-agent-id!` in `-main`
  (line 193) so the probe finds it. `set-agent-id!`
  (`node_agent.cljs:31-38`) exists ONLY for this mirroring. The reorg
  PRD batch 2 (`reorg-cleanup-prd-2026-06-10.md:78-79`) had directed
  "inline the one agent-id fn"; the rename instead kept the require —
  the deviation this spec resolves (§4).

### 1.3 Live evidence — the current scheme is broken TODAY

Observed 2026-06-10 against the running system:

- `runtime_status` → `builds: [{:build :client, :runtimes 1}]`. No
  `:node-agent` worker, no `:wire-node` worker (`bin/seon`'s
  `cljs-watch` runs `clj -M:cljs watch client` only, `bin/seon:57`).
  shadow's `repl-runtimes` is PER-BUILD-WORKER
  (`reference-code/shadow-cljs/src/main/shadow/cljs/devtools/api.clj:223-229`),
  so the resolver's `(shadow/repl-runtimes :node-agent)` returns nil →
  every `agent_id` eval, including `agent_id="wire"`, fails with
  "No live runtime" unless someone manually starts extra watchers.
- The POD already loads the probe ns: require chain
  `seon.client:132` → `seon.store.wire:36` →
  `seon.store.internal.wire-node:31` → `seon.dev.node-agent`. Live
  eval on the `:client` runtime:
  `(seon.dev.node-agent/agent-id)` → `nil` (ns present, id unset).
  So the pod — the ONE process actually hosting agents — is the one
  runtime the scheme cannot address: wrong build enumerated, and its
  probe atom is never set.
- Hazard note: because wire-node is now compiled into `:client`, the
  pod also carries wire-node's `!agent-id` atom defaulting `"wire"` —
  a latent false-positive if any future probe consulted
  `wire-node/agent-id` instead of node-agent's.

Conclusion: the "two conventions" are really one working convention
(substrate ids) and one bit-rotted probe rig that addresses processes
which no longer run. Unification is also a repair.

## 2. The unified scheme

### 2.1 One id, one grammar

`agent_id` in `mcp__seon_cljs__eval` IS the substrate `:seon.agent/id`
— the same string in the cluster store, in message from/to refs, in
the (pending) `my.agent.<id>` home-ns, and in the inspector URL.
`mcp eval agent_id="iCg-2606101519"` reaches the process hosting THAT
agent entity.

Hand-assigned ids are RETIRED as agent ids. Non-agent infrastructure
runtimes (wire-node today; replica peers, the future web-UI reader
process per main PRD §7 item 10d) remain REPL-addressable under a
namespaced process-name grammar: `proc:<name>` (e.g. `proc:wire`).
Justification for namespacing rather than deleting: MCP eval must
still reach infra runtimes, and one resolver + one registry + one
string param is the whole point — but letting `wire` squat in the
agent-id namespace invites collision with real (or future
human-aliased) agent ids and makes "is this an agent?" ambiguous. The
`proc:` prefix is impossible to mint via `new-id!` (which never emits
`:`), so the two populations are disjoint by construction while
sharing one resolver, one probe, one cache.

### 2.2 The probe contract

A runtime answers the probe with the VECTOR of ids it hosts, not a
single hand-set id:

```clojure
(seon.dev.runtime-id/hosted)  ;; => ["iCg-2606101519" "Kpx-2606101522"]
                              ;; or ["proc:wire"]
```

Resolution = membership: agent-id ∈ hosted-set. This is what makes the
scheme correct in BOTH topologies:

- Today (one pod, N interleaved agents on cluster "default"): the pod
  answers with every agent it booted. `mcp eval agent_id=<any of them>`
  pins the pod runtime.
- Target (main PRD §7 item 10, one Node process per agent): each
  process loads the dev `:client` build, registers with the ONE shadow
  watcher, and answers with its singleton id. The same resolver,
  unchanged, now gives true per-agent process addressing — fulfilling
  §7 item 9(a)'s access matrix.

### 2.3 Where the probe fn lives (post-reorg ns map)

New tiny ns: **`seon.dev.runtime-id`** (`src/seon/dev/runtime_id.cljs`),
ZERO requires — one defonce atom + `host!` / `unhost!` / `hosted`.

Why there and not elsewhere:

- `seon.repl` owns process-lifetime REPL state (compile-state defonce,
  `client.cljs:68`) and is the conceptual home — but it drags the
  bootstrap compiler, which the slim `:wire-node` build must not load.
- `seon.db.internal` / `seon.store.*` — wire-node deliberately depends
  only on transit/cbor; the store layer answering a dev-REPL probe
  inverts the layering.
- The reorg end-state map (`reorg-cleanup-prd-2026-06-10.md:44`) puts
  `seon.dev.*` in the dev/test-build-only layer — and MCP addressing
  IS dev-only (shadow websocket is dev-only, main PRD §7 item 9d;
  release builds have no shadow client to address). A zero-require ns
  compiled into `:client` costs one atom; the "verify not in :client
  where avoidable" note is satisfied in spirit — it is avoidable to
  exclude, but at the price of preload gymnastics for no gain.
- If/when a `seon.repl` face/internal split produces a slim face, the
  ns can fold in; not worth blocking on.

### 2.4 The resolver (bin/mcp-server-cljs)

- Enumerate `(shadow/active-builds)` and `repl-runtimes` per build —
  delete the `agent-build-id ":node-agent"` hardwiring (line 57). The
  pin must carry the build the runtime registered under, so resolution
  returns `{:build b :client-id cid}` and `pin-session!` pins with
  that build.
- Probe symbol flips to `(seon.dev.runtime-id/hosted)`; match is
  `(some #{agent-id} hosted)`. Runtimes that error on the eval (ns not
  loaded — e.g. a release-ish probe build) are skipped, exactly as a
  non-matching id is today.
- `agent-sessions` (line 56) survives unchanged as the CACHE keyed by
  the unified id — it is not a second id scheme, just memoized
  resolution; the crash+respawn re-resolve loop
  (`execute-agent-eval`, 378-399) is untouched.
- Cost note: resolution is O(runtimes) evals per cache miss. Fine at
  demo scale; if N grows, probe results can be batch-collected in one
  CLJ-side eval that fans out — an optimization, not a design change.

## 3. Migration steps (≤7 files, ordered)

Sequencing principle: ship the ANSWERING side first (additive — old
probe keeps working for any old process), flip the ASKING side last.

1. **NEW `src/seon/dev/runtime_id.cljs`** — defonce `!hosted` (set of
   strings), `host!`, `unhost!`, `hosted` (returns vector). Zero
   requires.
2. **`src/seon/client.cljs`** — `start-agent!` calls
   `(runtime-id/host! agent-id)` right after the mint (line 1388);
   same call in the create-agent path (`web.serve/set-create-agent-fn!`
   wiring, line 1517) for additional in-pod agents; `rearm-user-triggers!`
   (line 1304) re-hosts `live-agent-ids` on reload so a hot-reloaded
   pod stays addressable for PRIOR boots' agents it re-arms.
3. **`src/seon/store/internal/wire_node.cljs`** — DELETE the
   `seon.dev.node-agent` require (lines 29-31), DELETE the duplicate
   `!agent-id` atom + `agent-id` fn (lines 49-52); `-main` calls
   `(runtime-id/host! (str "proc:" name))` where name comes from
   `--process-name` argv (default `"wire"`). This completes the reorg
   batch-2 directive properly: under the unified scheme there is
   nothing to inline — the borrowed fn is replaced, not copied.
4. **`src/seon/dev/node_agent.cljs`** — DELETE `set-agent-id!`
   (lines 31-38, its only caller died in step 3); `-main` switches to
   `runtime-id/host!`. Keep the ns itself until §7 item 10a's
   per-agent launcher is verified end-to-end (it is the only
   multi-runtime test rig); then take the reorg DECIDE
   (`reorg-cleanup-prd-2026-06-10.md:107`) as DELETE.
5. **`bin/mcp-server-cljs`** — the coordinated flip (§2.4): resolver
   enumerates active builds, probes `(seon.dev.runtime-id/hosted)`,
   membership match, build-aware pin; update the `agent_id` tool
   description (line 488: "the substrate :seon.agent/id, or
   proc:<name> for infrastructure runtimes"); fix the error hint
   (line 397-399) which still suggests `node out/node-agent/main.js`.
6. **`shadow-cljs.edn`** — comment-only: `:wire-node` build header
   says `agent_id "wire"` → `proc:wire`; `:node-agent` header notes
   the probe ns change.
7. **Docs** — main PRD §7 item 9(a) note + the CLAUDE.md Process
   Architecture access-matrix entry (item 9 says document when it
   lands); mark this PRD's status.

Operational risk at step 5: `bin/mcp-server-cljs` is read at MCP-server
launch (Claude Code session start). Running orchestrator sessions keep
the OLD probe until restart — their `agent_id` evals against
new-runtime-only processes fail. Mitigation: steps 1-4 are additive
(the old probe symbol still resolves to `nil`-answering or deleted fn —
for the deleted `set-agent-id!` nothing probes it), and TODAY the
`agent_id` path is already dead live (§1.3) — there are no working
sessions to break. Do the flip in one commit, restart Claude Code
sessions at the next natural break, verify with
`mcp eval agent_id=<live pod agent id>`.

## 4. What dies

- `seon.dev.node-agent/set-agent-id!` (`node_agent.cljs:31-38`) — the
  mirroring shim exists only because resolution was hardwired to one
  ns's atom.
- The `seon.dev.node-agent` require in wire-node
  (`wire_node.cljs:29-31`) — the earlier deviation from the reorg
  batch-2 "inline" directive, now resolved by replacement (§3 step 3).
- wire-node's duplicate `!agent-id` atom + `agent-id` fn
  (`wire_node.cljs:49-52`), including the hazardous `"wire"` default
  that the pod build currently carries (§1.3).
- The `agent-build-id ":node-agent"` hardwiring
  (`bin/mcp-server-cljs:57`).
- Bare hand-assigned agent ids (`a1`, `wire`) — replaced by substrate
  ids and `proc:<name>`.
- NO second registry exists to delete: the MCP server's
  `agent-sessions` atom is a resolution cache, re-keyed for free by
  the unified id.

## 5. Open questions / dependencies

- **Identity durability (DEPENDENCY, not decided here).** Main PRD §7
  item 8: every pod boot mints a NEW `:seon.agent/id`
  (`client.cljs:1388`), so the MCP address of "the agent" changes per
  restart and old agents accumulate. The unified scheme works either
  way (the probe is live — whatever ids the process hosts, it
  answers), but if identity becomes durable, `agent_id` becomes a
  STABLE address across restarts, which is what §7 item 9(a) really
  wants. Sequencing preference: land this unification first (it is
  agnostic), let the durability fix inherit stable MCP addressing for
  free.
- **Home-ns rename interaction (none structural).** `my.agent.<id>`
  derives from the same id; a dash-bearing id is a valid ns segment
  (`my.agent.iCg-2606101519`). The rename unit and this unit touch
  `client.cljs` and `agent.cljs` — coordinate lane ownership, nothing
  more.
- **Who re-hosts on agent stop?** When an agent process exits (target
  topology) the runtime disappears with it — nothing to do. In the
  one-pod era, a "stopped" agent entity (`:seon.agent/state` not
  idle/running) should arguably be `unhost!`-ed; cheap to wire into
  whatever stop path exists when one does. Until then, hosting a
  stale id only means an eval pins the pod — harmless.
- **Probe build for §7 item 10a verification.** When the per-agent
  launcher lands, processes register under the `:client` build worker;
  verify the resolver distinguishes N same-build runtimes by
  membership (it does by construction — each answers its own
  singleton) and then delete `seon.dev.node-agent`.
