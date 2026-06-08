---
type: research
status: active
tags: [research, agent, flow]
---

# MVP Agent Runtime — recon (2026-06-08)

> Recon of the agent-runtime subsystem (how a live agent actually runs) +
> the `seon.fs` capability, measured against the target MVP: ONE live
> node/CLJS agent, assigned to a namespace, running an LLM loop that
> (1) reads a shared read-only folder via node fs, (2) defines
> schemas/fns in its own namespace persisted as code-as-data,
> (3) messages other agents and receives replies, (4) resumes its
> predecessor's work on restart.

## TL;DR

The **V0 CLJS pod (`src/seon/*.cljs`, run via `node out/client/main.js`)
is by far the closest track to the MVP — most of it already exists and
runs.** A single agent boots, runs a real DeepSeek-backed LLM→parse→eval
loop, persists its fns/schemas/ns as `:seon.fn`/`:seon.schema`/`:seon.ns`
entities (detect-and-tee), resumes via eval-replay of the program graph,
and has a complete default-deny `seon.fs` allowlist (read/list/stat/walk).

**Three things are missing for the MVP, all on the V0 pod, all small-to-medium:**

1. **Multi-agent boot.** `start-agent!` mints exactly ONE agent-id and
   `set!`s a single root `db/*conn*`. There is no loop/registry to boot
   N agents in one pod. (`seon.agents/*ctx*` registry exists but isn't
   the boot driver.)
2. **Inter-agent messaging is a dead end.** The wake handler
   (`seon.handlers.wake/wake-on-message`) produces an `{:effects [{:seon.effect/fn
   'seon.effects/wake ...}]}` descriptor, but **NOTHING consumes that
   effect** — `seon.effects/wake` does not exist, and no dispatcher
   interprets `:effects`. There is also no `(seon.ai/message <id> "…")`
   send verb. Agent→agent message + reply does not work today.
3. **No "shared read-only folder" wiring.** `seon.fs` is default-deny;
   nothing in boot calls `(seon.fs/configure! {:seon.fs/allowed-roots […]
   :seon.fs/read-only? true})` or sets `SEON_FS_ROOT`. The capability is
   complete; only the boot-time grant + a data dir are missing.

The **Platform/V2 track (`client-runtime/`, `guest-cljs/`, `seon.server.*`)
cannot host the MVP soonest** — it has a multi-DB wire server and a wasm
guest skeleton but **no LLM loop, no run-turn!, no deepseek** anywhere
server-side or guest-side. The project's own plan (CUTOVER.md /
`platform-v2-node-first-plan-2026-06-03`) reframes V2.0 as "repoint the
*existing V0 agent loop* at a Node wire client" — i.e. V0's loop IS the
asset; the platform reuses it. So build the MVP on V0, keep the wire
seam in mind.

## What exists

| Capability | Where (file:line) | State |
|---|---|---|
| Live agent boot (single) | `src/seon/client.cljs:712` `start-agent!`; `-main` at `:880` | WORKS — opens conn, bootstrap-CLJS, replay, `agent/boot!`, HTTP/SSE, handler seed |
| LLM loop turn | `src/seon/agent.cljs:721` `run-turn!`, `:787` `run-agentic-loop!` | WORKS — render-prompt → llm-fn → parse → eval-batch → close turn |
| LLM provider (DeepSeek) | `src/seon/ai/deepseek.cljs:156` `complete`, `:225` `agent-adapter` | WORKS — native `fetch`, AbortController timeout, `DEEPSEEK_API_KEY` env. Only provider wired (no anthropic). |
| System prompt (REPL contract) | `src/seon/ai/deepseek.cljs:67` `default-system-prompt` | WORKS — "emit Clojure forms directly, no fences" |
| Prompt/context assembly | `src/seon/agent.cljs:1298` `assemble-ctx` + 6 section fns (`:1068`–`:1267`) | WORKS — `:seon.agent/ctx` entities sorted by priority, each a `:seon.ctx/fn` symbol; sections: system, messages, current-ns, warnings, recent-evals, prompt |
| Eval engine (cljs.js bootstrap) | `src/seon/eval.cljs:876` `eval-batch!`, `:532` `setup-agent-ns!` | WORKS — per-form eval into `seon.agent.<id>` home ns, partial-failure (form N+1 always runs), per-form budget timeout |
| Code-as-data persistence | `src/seon/eval.cljs:715` `build-tee-entities` (detect-and-tee) | WORKS — defined fns/schemas/ns tee'd to `:seon.fn`/`:seon.schema`/`:seon.ns` entities (schemas `src/seon/agent.cljs:248`–`354`) |
| Message/turn/eval log (causality graph) | `src/seon/agent.cljs:208`–`235` schemas; `run-turn!` writes session→turn→{messages,evals} | WORKS — one nested pull walks the whole tree (`root-pull` `:938`) |
| Per-agent isolation (home ns) | `src/seon/agent.cljs:368` `home-ns`; `seon.db/with-agent` ALS scope | WORKS — `(home-ns "abc") => 'seon.agent.abc`; agent-id flows via AsyncLocalStorage |
| Per-agent runtime state var | `src/seon/agents.cljs:133` `*ctx*`, `:269` `start-agent!`, `:210` `run-as-agent` | EXISTS but UNWIRED into V0 boot — registry + ALS-bound atom; mirrors `*conn*`. Not the boot driver today. |
| Resume / bootstrap-from-DB | `src/seon/client.cljs:761` `replay-program-graph!` | WORKS — re-evals every persisted `:seon.ns`/`:seon.fn`/`:seon.schema` `:source` in tx order at boot. No-op vs empty/`:memory` conn until SQLite persistence flip. |
| User-message wake (intra-agent) | `src/seon/agent.cljs:430` `user-message-handler`, `:454` `install-user-trigger!` | WORKS — datahike tx-listener fires on new `:user` message, schedules `run-agentic-loop!` via setTimeout + `with-agent` re-entry. State-machine guard (`:idle`/`:running`). |
| `chat` / `replies-after` (user↔agent) | `src/seon/agent.cljs:524` `chat`, `:549` `replies-after` | WORKS — inject `:user` msg; poll `:assistant` replies |
| fs capability (allowlist) | `src/seon/fs.cljs` — `read-file:269`, `list-dir:306`, `stat:321`, `walk-dir:413`, `configure!:185` | WORKS — default-deny; path-traversal-proof `under-root?`; sync (so agent `let`-bindings get values not Promises); read-only flag; `SEON_FS_ROOT`/`SEON_FS_READ_ONLY` env bootstrap |
| Handler registry | `src/seon/handler.cljs:157` `register!`, `:202` `query-handlers` | WORKS as a REGISTRY — but no dispatcher installs from it (see gaps) |
| Wake-on-message handler | `src/seon/handlers/wake.cljs:82` `wake-on-message` | PARTIAL — returns `{:effects [{:seon.effect/fn 'seon.effects/wake …}]}`; effect is never interpreted |
| Platform multi-DB wire server | `src/seon/server/*` (reactive `:reactive.clj`, store, wire, registry) | WORKS for what it is — M3 subscriptions, Transit wire, JVM datahike; ~61 tests green. NO LLM loop. |
| Wasm guest skeleton | `guest-cljs/src/seon/client_runtime/*` (agent, db, fs, wit, als) | SKELETON — wire-only guest; no run-turn!/deepseek |

## What's missing for MVP

Ordered by dependency. All items are on the **V0 pod** unless noted.

1. **Boot N agents in one pod (multi-agent).**
   `start-agent!` (`client.cljs:712`) mints one `agent-id` and `set!`s one
   root `db/*conn*`. To run "ONE agent assigned to a namespace" repeatably
   and to support agent↔agent messaging you need either (a) accept the
   single-agent shape for the literal MVP, or (b) a small boot loop that
   `start-agent!`s several ids sharing the conn, each with its own
   `seon.agents/start-agent!` atom + `with-agent` scope. The pieces
   (`home-ns`, `with-agent`, `seon.agents` registry) all exist; the
   driver does not. **For a true single-agent MVP, this is optional.**

2. **Point fs at a shared read-only data dir.**
   No boot code calls `seon.fs/configure!`. Add one line in `start-agent!`
   (or set `SEON_FS_ROOT=<dir>` + `SEON_FS_READ_ONLY=1`). Then the agent's
   `(seon.fs/walk-dir {:seon.fs/path "<dir>" :seon.fs/match-ext ".md"})`
   and `read-file` work. **Smallest item — capability is done.**
   Caveat: the system prompt / `current-ns` section does not yet advertise
   `seon.fs/*` worked-examples to the agent, so the agent won't know the
   folder exists unless told. Add an fs hint to a section fn or the
   system prompt.

3. **Inter-agent message SEND + WAKE (the real gap).**
   - **No send verb.** There is no `(seon.ai/message <agent-id> "…")`.
     `chat` (`agent.cljs:524`) injects a `:user` message for ONE agent
     and is the user→agent path, not agent→agent. Need a thin fn that
     transacts a `:seon.message` with `:seon.message/to [[:seon.agent/id
     target]]` (+ `:seon.message/from` the sender) — the schema for
     `:seon.message/to` already exists (`wake.cljs:31`).
   - **No effect interpreter.** `wake-on-message` emits
     `{:seon.effect/fn 'seon.effects/wake …}` but `seon.effects/wake`
     does not exist and **no dispatcher consumes `:effects`**. The handler
     registry (`seon.handler`) is populated at boot but its docstring
     says the dispatcher "lives in `seon.runtime`" — `seon.runtime.cljs`
     does not exist on the CLJS side. So a `:seon.message/to` datom wakes
     no one.
   - **Two implementation options:** (a) Easiest/consistent with what
     already works: register a SECOND per-target tx-listener (like
     `install-user-trigger!`) that fires `run-agentic-loop!` when a
     `:seon.message/to <me>` datom lands — skip the handler/effect
     machinery entirely. (b) Build the missing dispatcher that reads the
     `seon.handler` registry, matches datoms, resolves the `:seon.effect/fn`
     symbol, and runs it. Option (a) is the MVP-fast path; the handler
     registry is then unused-but-harmless until a dispatcher is built.
   - **Reply path:** once the target wakes and its loop runs, it transacts
     an `:assistant` (or `:seon.message/to` back at the sender) message.
     The sender reads replies via `replies-after` or its own messages
     section. The reply mechanism reuses existing machinery once wake works.

4. **Persistence for cross-restart resume (depends on conn backend).**
   `replay-program-graph!` works, but on a `:memory` datahike conn there
   is nothing to replay after a process exit. Confirm the agent conn is
   opened against an on-disk store (the memory note flags a pending
   "SQLite persistence flip"; `open-agent-conn!` in `client.cljs` is the
   place to check). Resume of the *program graph* (fns/schemas/ns) works
   via replay; resume of *conversation* requires the same durable conn.

5. **(Nice-to-have, not blocking) Agents catalog / orchestrator ns.**
   The vision's "`require seon.agents` → catalog of all schemas by
   namespace" does NOT exist as described. `seon.agents.cljs` is the
   per-agent runtime-state var (`*ctx*`), a different thing. A
   schemas-by-namespace catalog can be derived from the already-persisted
   `:seon.schema`/`:seon.ns`/`:seon.fn` entities (the `current-ns-section`
   already does a slice of this). Defer for MVP.

## Recommendation: build the MVP on the V0 pod

**Host the MVP agent on the V0 CLJS pod, not the platform track.** Rationale:

- V0 already runs the entire loop end-to-end (boot → DeepSeek → parse →
  eval → tee → render → wake). The MVP is ~3 small additions (fs grant,
  agent→agent message+wake, confirm durable conn), not a new system.
- The platform track has the multi-tenant DB plumbing but **zero LLM
  loop** — porting run-turn!/deepseek/eval-batch there is strictly more
  work than the V0 deltas above.
- The project's own cutover plan (`client-runtime/docs/CUTOVER.md`,
  superseded by `platform-v2-node-first-plan-2026-06-03`) explicitly says
  V2.0 = "repoint the **existing V0 agent loop** at a Node wire client."
  So V0's loop is the durable asset regardless of track. Building the MVP
  on V0 is directly on the path to V2, not a throwaway.

**Concrete MVP punch list (V0 pod):**

1. In `start-agent!`: `(seon.fs/configure! {:seon.fs/allowed-roots [data-dir]
   :seon.fs/read-only? true})` and surface an fs hint to the agent.
2. Add `seon.ai/message` (or `seon.agent/message`) send verb writing
   `:seon.message/to` + `:seon.message/from`.
3. Add a per-agent `:seon.message/to`-trigger listener (mirror
   `install-user-trigger!`) that wakes `run-agentic-loop!` — OR build the
   real `:effects` dispatcher + `seon.effects/wake`. Prefer the listener
   for MVP.
4. (If multiple agents are required for the "message other agents" demo)
   add a boot loop to `start-agent!`/`-main` to bring up ≥2 agents on the
   shared conn.
5. Verify `open-agent-conn!` uses an on-disk store so restart→replay has
   data; otherwise the resume criterion only holds within a process.

## Loose ends / code smells noted (not fixed — recon only)

- **Orphaned effect contract.** `wake-on-message` (`wake.cljs:82`)
  produces `:effects` that no code reads; `seon.effects/wake` is
  referenced by symbol but undefined. Either an interpreter is missing or
  this handler is dead. Flag for the implementing agent — pick the listener
  path or finish the dispatcher; don't leave both half-built.
- **`seon.runtime` (CLJS) referenced but absent.** `handler.cljs` and
  `handlers/wake.cljs` docstrings point at `seon.runtime` for the
  dispatcher / wake interpreter; no such CLJS namespace exists. Naming
  drift between design docs and live code.
- **`seon.fs/mtime` typed `:any`** (`fs.cljs:92`) with a comment that
  `:inst` "varies across CLJS reader registries." Per project rules `:any`
  is a smell; left as-is since it's a known boundary issue, but worth a
  follow-up to pin the type.
- **`home-dir` returns `[:maybe :string]`** (`fs.cljs:353`) — `[:maybe X]`
  is banned by project convention (use `{:optional true}` / absence).
  Minor; flagged.
