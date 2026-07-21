---
type: prd
status: draft
tags: [prd, agent]
---

# `seon.agent.mcp` — agents calling the user's MCP servers (2026-06-11)

Board P10/#25, user-wanted. **Doc-only PRD; no code in this unit.**

## 1. Goal

An agent can DISCOVER and CALL tools from the MCP servers the user has
already configured — "agents can do anything the user can." The user's
machine already runs (verified 2026-06-10, live config read):

- `~/Library/Application Support/Claude/claude_desktop_config.json` →
  `context7` (library docs), `puppeteer` (browser), `spotify` (API) —
  all stdio.
- `<repo>/.mcp.json` → `seon`, `seon_cljs` (our OWN servers — see the
  recursion guard, §6).

So the day-one demo is real: an agent asked "what's playing on
Spotify?" or "fetch the React docs for hooks" answers by calling the
user's existing servers — no seon-side integration code per service.

We already speak MCP as a SERVER (`bin/mcp-server-cljs` — JSON-RPC 2.0
over stdio: `initialize` / `tools/list` / `tools/call`). This unit is
the asymmetric twin: seon as a CLIENT. Same protocol, opposite end of
the pipe, and it lives in the pod (CLJS/Node), not bb.

Scope: **stdio transport first.** HTTP/SSE (remote servers, OAuth) is
explicitly later — the SDK choice below keeps that door open without
rework.

Non-goals: exposing MCP tools to the LLM as native tool-calls (agents
call via eval — that IS the design, see §5 audit); MCP resources and
prompts (tools only, v1); writing new MCP servers.

## 2. Configuration — where server definitions come from

Two candidate sources, considered:

1. **Read the user's existing config files** (claude_desktop_config /
   `.mcp.json` / `~/.claude.json` projects map). Pro: zero duplicate
   config, "what Claude can reach, seon can reach" is literally true.
   Con: those files carry SECRETS in `env` blocks (API keys for
   spotify-class servers); their schema is Anthropic's, not ours, and
   drifts; auto-trusting every server in them violates default-deny.
2. **Store-resident `:seon.mcp/*` entities.** Pro: fits
   everything-is-data — server defs are queryable, the catalog section
   can derive from them, config survives restarts via the cluster
   store. Con: **secrets must never be datoms.** The store is rendered
   into every agent's context, queryable by every agent in the
   cluster, durable, and replicated over DIS — an API key transacted
   once is leaked to every future context render. Datom-resident env
   maps are disqualified outright.

**DECISION (recommended): hybrid — defs as data, secrets by
reference, allowlist seon-owned.**

- Server DEFINITIONS (name, transport, command, args) are seon-owned
  config in the `seon.agent.fs` style: a `!config` atom +
  `configure!`, default-deny (empty config = no servers callable).
- `import!` is the convenience path: reads the user's existing config
  files (paths above, probed in order), parses `mcpServers`, and
  returns the discovered definitions **as data for the human to
  approve** — importing populates the def list but does NOT enable
  anything; enabling is the explicit allowlist step (§5). One file
  read, no background watching.
- **Secrets stay in the source files / process env.** A definition's
  env block is stored as `{:seon.agent.mcp/env-from :claude-desktop}`
  (a POINTER to the config file it came from); the env map is re-read
  from the file at SPAWN time and handed to `child_process` only.
  Secrets exist in pod process memory during the child's life and
  nowhere in the store, the context, or any envelope. Error envelopes
  must never echo the env (redaction test required).
- Store-residency for the non-secret metadata (server name, tool
  catalog snapshot, call stats) is a LATER option once it earns a
  derived section — not v1 plumbing.

## 3. API sketch

One ns `src/seon/agent/mcp.cljs`, written to the
`seon.agent.search` wrapper doctrine verbatim: one package → one thin
ns; map-in/map-out with registered `::request`/`::response` schemas;
ERRORS ARE VALUES (every fn resolves to an `::ok?` envelope, never
throws/rejects); lazy `js/require`; `:any` only at the npm boundary.

npm dep: **`@modelcontextprotocol/sdk`** (Client +
StdioClientTransport), installed `--save` like `@vscode/ripgrep`.
Decision rationale: we COULD hand-roll the client (the protocol is
newline-JSON-RPC and `bin/mcp-server-cljs` proves we know it), but the
SDK gives version negotiation, framing edge cases, and the
StreamableHTTP/SSE transports later for free — the wrapper doctrine
exists precisely so an npm dep is cheap. Fallback if the SDK fights
CLJS interop: hand-rolled stdio client (~150 lines, we have the
reference implementation in-repo); the public API below is identical
either way, so the choice is revisable inside unit 1.

```clojure
;; All sync ops on local state are plain fns; anything that touches a
;; child process is ^:async (the seon.agent.search precedent — agents
;; await the outermost form; the fs sync-rationale comment explains
;; why we do NOT bury promises in let-bindings of worked examples).

(seon.agent.mcp/configure!  {:seon.agent.mcp/servers [...]
                             :seon.agent.mcp/allowed-servers #{"spotify"}})
;; => the new config map (fs/configure! contract)

(await (seon.agent.mcp/import! {}))            ;; optional :seon.agent.mcp/path
;; => {:seon.agent.mcp/ok? true
;;     :seon.agent.mcp/discovered
;;     [{:seon.agent.mcp/server "spotify"
;;       :seon.agent.mcp/transport :stdio
;;       :seon.agent.mcp/command "npx" :seon.agent.mcp/args [...]
;;       :seon.agent.mcp/env-from :claude-desktop} ...]}
;; populates defs; NOTHING is callable until allowed-servers names it.

(seon.agent.mcp/list-servers {})
;; => {:seon.agent.mcp/ok? true
;;     :seon.agent.mcp/servers
;;     [{:seon.agent.mcp/server "spotify"
;;       :seon.agent.mcp/allowed? true
;;       :seon.agent.mcp/status :running | :stopped | :failed} ...]}

(await (seon.agent.mcp/list-tools {:seon.agent.mcp/server "spotify"}))
;; => {:seon.agent.mcp/ok? true
;;     :seon.agent.mcp/tools
;;     [{:seon.agent.mcp/tool "playPause"
;;       :seon.agent.mcp/description "..."
;;       :seon.agent.mcp/input-schema <JSON-schema as EDN>} ...]
;;     :seon.agent.mcp/truncated? false}   ;; tool-count + per-desc caps

(await (seon.agent.mcp/call-tool!
         {:seon.agent.mcp/server    "spotify"
          :seon.agent.mcp/tool      "playPause"
          :seon.agent.mcp/arguments {}          ;; tool's own schema — the
                                                ;; ONE deliberately open map
          :seon.agent.mcp/timeout-ms 30000}))   ;; optional, default below
;; => {:seon.agent.mcp/ok? true
;;     :seon.agent.mcp/content [{:seon.agent.mcp/type :text
;;                               :seon.agent.mcp/text "..."} ...]
;;     :seon.agent.mcp/is-error? false}        ;; MCP-level tool error ≠
;;                                             ;; transport failure
;; or {:seon.agent.mcp/ok? false
;;     :seon.agent.mcp/error "<guiding message>"
;;     :seon.agent.mcp/raw-error "<SDK/process detail>"}
```

Envelope notes:

- `:seon.agent.mcp/arguments` is schema'd `:map` (not deeply): the
  tool's input schema belongs to the FOREIGN server — third-party
  boundary, the sanctioned `:any`-class exception. We surface the
  server's own JSON schema in `list-tools` so the agent (an LLM —
  reading JSON schemas is what it does) constructs arguments itself.
- MCP distinguishes "tool ran and reported an error" (`isError` in the
  result) from transport/protocol failure. Both are values:
  `ok? true + is-error? true` vs `ok? false`. Distinct guiding
  messages per failure mode (server not allowed / not configured /
  spawn failed / timeout / protocol error), raw detail preserved —
  the search-ns contract.
- **Timeouts/budgets (a hung server must not wedge the agent loop):**
  the deepseek pattern (`seon.ai.deepseek` `!timeout-ms` +
  AbortController) applied per call — default 30s, per-call override,
  resolved as `{ok? false, :seon.agent.mcp/timeout? true}`. Spawn gets
  its own shorter budget (~10s to `initialize`). Per-result caps like
  search: total content bytes (~256KB) and a `truncated?` flag —
  a tool that returns a webpage must not flood the transcript.

## 4. Context integration — how agents learn the tools exist

Options considered:

1. **Static turn-0 section listing all tools of all servers.** Rejected:
   tool catalogs are huge (puppeteer alone ~20 tools with verbose JSON
   schemas; this repo's deferred-tool list is the cautionary tale) and
   mostly irrelevant per turn — pure budget burn.
2. **Derived one-liner section** ("MCP servers: spotify ✓, puppeteer ✓,
   context7 ✗ not-allowed") — cheap, self-healing, vanishes when no
   servers configured.
3. **Nothing beyond the ns source** — the V3 rule already renders
   toolbelt namespaces (`seon.agent.*`) at FULL SOURCE, so the
   docstrings of `list-servers`/`list-tools`/`call-tool!` teach the
   discovery move by existing.

**DECISION (recommended): 3 now, plus a V3-E demonstrated eval when
servers are configured.** `seon.agent.mcp` joins the relevant-set
table in the context-v3 PRD; its ns-doc carries the worked
discover→inspect→call recipe (the search→read recipe precedent).
Under V3-E (show-don't-tell), when `list-servers` is non-empty, turn-0's
transcript opens with a REALLY-EXECUTED
`(seon.agent.mcp/list-servers {})` eval — names only, ~1 line per
server, never the tool catalogs. `list-tools` is the agent's own move,
on demand, landing in the transcript as data exactly once per server
per session (the transcript IS the cache). No new mechanism; budget
cost ≈ tens of chars when unconfigured, one short eval when
configured.

## 5. Safety

- **Capability allowlist, default-deny — the `seon.agent.fs` model.**
  `:seon.agent.mcp/allowed-servers` (set of server names) gates every
  spawn and call; empty = nothing callable, with the same guiding
  "ask your human to grant access via configure!" denial message.
  Optional finer grain `:seon.agent.mcp/allowed-tools`
  (`{"puppeteer" #{"screenshot" "navigate"}}`) — absent = all tools of
  an allowed server. Importing never auto-allows (§2).
- **The honesty note (same as the sandbox and fs):** this is a SOFT
  boundary against LLM-emitted accidents, not a security boundary —
  agent eval can `(js/require "node:child_process")` and spawn
  anything; isolation comes from process boundaries and the wire
  capability surface (per the settled sandbox decision). We harden
  what we can: hallucinated server names and disallowed tools land on
  a denial envelope, not on a spawned process.
- **Recursion guard:** `import!` marks the repo's own `.mcp.json`
  `seon`/`seon_cljs` entries `:seon.agent.mcp/self? true` and refuses
  to allow them — a pod agent calling the pod's own MCP server is a
  deadlock-shaped loop (the server evals back into the runtime that
  is awaiting it). Override requires editing the config by hand, which
  is the point.
- **Secrets redaction:** env maps never appear in any envelope, log
  line, or datom (§2); a unit test asserts spawn-failure envelopes for
  a server with env carry no env values.
- **Human-visible audit — free.** Agents call MCP via eval, so every
  call IS a transcript eval: the form, the envelope, the timing all
  land in the agent's eval log in the store, rendered in the inspector
  like any other eval. No separate audit mechanism, by construction —
  this is the strongest argument for eval-as-the-interface over
  native LLM tool-calls.

## 6. Lifecycle — child processes in the pod

- **Long-lived, lazily spawned.** First `list-tools`/`call-tool!`
  against an allowed server spawns it (SDK StdioClientTransport owns
  the `child_process`), runs `initialize`, caches the client in a
  `!clients` atom keyed by server name. Subsequent calls reuse it —
  per-call spawn would pay multi-second startup (npx-launched servers)
  on every call and break stateful servers (puppeteer's browser).
- **Crash → envelope now, respawn next call.** Child exit/stderr-EOF
  marks the entry `:failed`; the in-flight call resolves
  `ok? false` with the exit detail; the NEXT call re-spawns once. No
  supervision loop, no retry storms — the agent (and its human) see
  the failure as a value and decide. `stop!`/`stop-all!` for explicit
  teardown; pod shutdown kills children (they inherit the process
  group; verify orphan behavior in unit 1 — the bb server's
  parent-watchdog is the precedent for why this matters).
- **Config change → targeted restart.** `configure!` with a changed
  definition stops that server's client; next call respawns with the
  new command/env.
- **Future process-per-agent:** clients are pod-local (per-Node-
  process) state, like the compile-state — NOT shared over the wire.
  When agents become one-process-each, each agent's pod spawns its own
  children for the servers it uses; the allowlist config rides
  whatever per-agent config mechanism lands then. Duplicate children
  per agent is accepted v1 cost (same trade as duplicate compile
  state); a shared MCP-broker process is a later optimization, not a
  v1 abstraction. Nothing in the public API names a process, so the
  move is free.

## 7. Unit breakdown (each ≤7 files) + gym sketch

### Unit M1 — stub fixture + core client + call path

- `test/fixtures/mcp-stub-server.mjs` (new) — ~80-line plain-Node
  stdio MCP server: tools `echo` (returns its argument), `slow`
  (sleeps N ms — the timeout oracle), `boom` (returns `isError`),
  plus a crash-on-demand tool. No deps; the fixture for every test
  below AND the gym scenario.
- `src/seon/agent/mcp.cljs` (new) — schemas, `!config`/`!clients`,
  `configure!`, `list-servers`, `list-tools`, `call-tool!`,
  spawn/initialize, timeout, caps, allowlist gate, recursion guard.
- `test/seon/agent/mcp_test.cljs` (new) — against the stub: default-
  deny; allow→list-tools→call echo roundtrip; `slow` → timeout
  envelope (not a wedge); `boom` → `ok? true is-error? true`; crash →
  envelope + respawn-on-next-call; env-redaction.
- `package.json` + lockfile (`@modelcontextprotocol/sdk`).

Live proof: from a live agent eval, allow the stub + call `echo`,
envelope observed in the inspector transcript.

### Unit M2 — `import!` + real-server proof

- `src/seon/agent/mcp.cljs` — `import!` (claude-desktop + `.mcp.json`
  parsing, `env-from` pointers, `self?` marking), `stop!`/`stop-all!`.
- `test/seon/agent/mcp_test.cljs` — import parsing against fixture
  JSON files (`test/fixtures/mcp-configs/*.json`, new); self-guard;
  no-auto-allow.
- Live proof (the demo): import the user's real config, allow
  `context7` (no secrets) — agent resolves a library and fetches docs
  via `call-tool!`, observed in the transcript. Spotify/puppeteer as
  user-driven stretch proofs.

### Unit M3 — context integration + gym scenario

- Relevant-set entry for `seon.agent.mcp` (context-v3 table) + ns-doc
  recipe polish; the V3-E `list-servers` demonstrated eval (lands with
  or after V3-E's machinery — coordinate with that lane).
- `test/seon/gym/scenarios/mcp-call-user-tool.edn` (new) — fixture
  config points at the stub server, pre-allowed; turn 1: "use my
  echo tool to repeat 'hello from MCP'". Predicates:
  `:first-eval-matches` `seon\.agent\.mcp/(list-tools|call-tool!)`
  (discovery before/with the call), a check that the call-tool! eval
  envelope has `ok? true`, judge grades the reply contains the echoed
  text. Negative twin (cheap, same fixture): server NOT allowed →
  agent surfaces the denial and asks the human, rather than
  hallucinating output.

Full suite once per unit (test-cadence rule); gym scenario paid-run
once at M3 ship.

## 8. Open questions for the user

1. **Default import sources** — claude-desktop + repo `.mcp.json`
   only, or also `~/.claude.json` per-project `mcpServers`? (More
   sources = more parsing surface; all are one `import!` flag.)
2. **Allow-grant UX** — is `configure!` from a human-driven eval
   enough for v1, or do you want the grant to be store-resident
   `:my.*` data so it survives restarts without re-running configure!
   in a boot overlay? (Defs-as-data minus secrets is compatible with
   §2; it's a v1.5 step, not a redesign.)
3. **HTTP/SSE priority** — any remote MCP server you actually want
   soon, or is stdio-only fine until one shows up?
