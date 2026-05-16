---
type: research
status: active
tags: [research, agent, capability]
---

# Spec-rewrite cluster: Pod runtime

Research input for spec-01 rewrite — what the CLJS pod is, what choices are
locked vs open, and what the JVM↔pod boundary looks like. Track B is actively
building the pod-host bundle at `seon/pod-host/libdatahike-cljs/`; this doc
draws on what they've already validated.

## Findings (one per Q)

### Q1. datahike-cljs konserve backends

**Pick for V1: `:tiered` with `:memory` frontend + `:file` backend** (when the
pod has a `--volume` mount from the host) and a `:tiered` `:memory` + `:indexeddb`
shape as a fallback / browser-pod variant. **Plain `:memory` is the floor.**

Why tiered: konserve's `:indexeddb` (and likely future async-only backends) is
async-only, but datahike's `persistent-sorted-set` index calls
`konserve.core/get` synchronously. Track B's CLJS-2b spike documents this
verbatim at `pod-host/libdatahike-cljs/src/seon/podhost/libdatahike/cljs_spike_idb.cljs:21-25`
and resolves it with a `:tiered` store (`:memory` frontend, persistent backend).
Upstream datahike's `dev/sandbox.cljs` uses the same shape (cited in spec-01
line 855).

Evidence backends work under Node + Wasmer-Edge:
- `:memory` — trivial (CLJS-1, passing).
- `:file` (konserve.node-filestore) — Track B's CLJS-2a spike passes (init
  → process exit → read second process → same query result), and is
  explicitly designed for Wasmer `--volume HOST:GUEST` mount: the spike reads
  `SPIKE_FS_PATH` env so CLJS-3 can point it at `/data/spike`
  (`cljs_spike_fs.cljs:11-22`).
- `:indexeddb` (konserve.indexeddb) — works under Node only via `fake-indexeddb/auto`
  polyfill loaded in shadow-cljs `:prepend-js` *plus* `globalThis.window = globalThis`
  alias (`shadow-cljs.edn:31-35` — konserve.indexeddb reads `js/window.indexedDB`).
  Single-process only in Node (no cross-process persistence without a custom
  backing store).

**Recommendation:** V1 pod uses `:tiered` mem+file with the file path supplied
by the host adapter via `--volume`. `:indexeddb` is the browser-pod variant
(Decision 39's "Electron + browser" shapes). `:s3`/`:gcs` are server-pod
only (don't pull HTTP networking into the user-machine pod's capability
surface unnecessarily).

### Q2. Bundle build pipeline

**`:target :bootstrap` is the spec-locked answer (Decision 34, 42)**, but
Track B's current spikes use `:target :node-script` for ergonomic reasons — that
gap matters and needs to close before WASM-1.

What's there today (`shadow-cljs.edn`): all four spikes (`:spike`, `:spike-fs`,
`:spike-idb`, `:bench`, `:repl`) are `:target :node-script` with a `:main`
entry. That's right for CLJS-1/2/2b/2.5 (smoke + bench) and for the REPL
runtime — none of them need to compile user code at runtime.

What changes for the pod (per Decision 34 + the shadow-cljs research report
`docs/research/shadow-cljs-evaluation.md`):

- **Primary build = `:target :node-script` or `:target :esm`**, output =
  `bundle.js`. Includes: `cljs.js` (self-host compiler), `cognitect.transit`,
  `datahike.api` + `konserve.node-filestore` + `konserve.indexeddb`,
  `core.async` + `core.async.flow`, the WS client, `seon.agent.eval.pod`
  bootstrap, `seon.agent.message`/`form`/`event`/`var` CLJC modules
  (Decision 8).
- **Support build = `:target :bootstrap`**, output = a *directory* of
  per-namespace `.js` + `.transit.json` analyzer caches + `index.transit.json`.
  Covers cljs.core + the agent-loadable surface (`seon.db`, `seon.graph`,
  shadow.grove if UI ships).
- **`:js-provider :require`** for npm interop (Decision 42; works under
  EdgeJS per shadow-cljs evaluation §B5).

Build cadence — three regimes, ranked by recommendation:
1. **JVM-hosted shadow watcher, per-user bootstrap dir, debounced rebuild on
   `:seon.agent.form` tx.** Agent transacts a new form → tx-listener calls
   the JVM-side shadow compile-sources → emits new per-ns JS + ana → relays
   to the pod's `:load` callback → pod's `cljs.js/eval-str` picks it up on
   next `(require ...)`. The user's `data/agents/<user-id>/*.cljs` source
   dir is a shadow `:source-paths` entry, watched in-place. Rebuild is
   incremental (~200ms for a single namespace). **This is what
   shadow-cljs-evaluation §"How it works in the spec-01 shape" prescribes.**
2. Pod-disconnect rebuild — too coarse; loses interactivity.
3. Continuous from a separate process — duplicates shadow's watcher
   responsibility and forks the resolution table.

**Uncertainty:** `core.async.flow` + `datahike` under `:target :bootstrap` is
flagged in spec-01 WASM-2 as *not yet validated*. Track B has them running
under `:target :node-script`; the `:bootstrap` validation is the spec WASM-2
spike. (unverified: whether the existing `cljs-datahike` runtime patches in
`repl.cljs:46-68` survive the bootstrap pipeline — they `set!` library vars at
load time, and bootstrap's namespace-loading order may not run them before
`empty-index` is called.)

### Q3. WebSocket + Transit-msgpack

**Critical correction:** Transit-cljs does **NOT** support `:msgpack`.
Verified directly against
`~/.m2/repository/com/cognitect/transit-cljs/0.8.264/transit-cljs-0.8.264.jar`
→ `cognitect/transit.cljs:202-211`: `(writer …)` accepts only `:json` and
`:json-verbose`. Msgpack exists in transit-clj (JVM, java.io.OutputStream
interop) but is not implemented for the JS port. **Spec-01 line 5
("Wire format: Transit") is correct; "transit-msgpack" in the task framing
is impossible CLJS-side.** Pick: `:json` on the wire.

If we genuinely need msgpack later (bandwidth or speed), the path is a CLJS
msgpack codec layered under our own protocol-bytes (the `msgpack-lite` /
`@msgpack/msgpack` npm package via `:js-provider :require`), not via Transit.
**Recommend: stay on Transit-`:json` for V1; revisit only if profile shows
encode/decode cost.**

**WebSocket client pick: thin wrapper over native `js/WebSocket`.** Wasmer
EdgeJS provides a native WebSocket built on Wasmer-runtime networking (gated by
`--net=ipv4:allow=<host>:<port>`). A 30-line wrapper that wires
`onmessage` → Transit-reader → core.async chan, and `core.async chan →
Transit-writer → send()` keeps the surface minimal and avoids pulling
`chord`/`haslett` (both nontrivial deps that historically assumed Pedestal-era
APIs). For multiplexing — multiple in-flight relay requests sharing one
socket — use a small request-id map (existing pattern in `seon.flow.topology`
on the JVM side; mirror it).

(unverified: `cljs-http` does only HTTP, confirmed — wrong tool. `chord` and
`haslett` both wrap `js/WebSocket`; their value is reconnect logic + chan
plumbing, which we can write tighter for our shape.)

### Q4. Local-LLM-from-pod

**V1: parked. Online LiteLLM proxy only (per Decision 21).** Pod calls
`seon.llm/complete` → relay → JVM-side `seon.agent.llm/complete` →
litellm-clj → provider. No direct pod-to-LLM path in V1.

Path A (pod calls user's local Ollama) is **technically feasible** under
Wasmer — the capability flag is `--net=ipv4:allow=127.0.0.1:11434`
(`wasmer-substrate-evaluation.md` §C.4 + §H confirm `--net=ipv4:allow=127.0.0.1:*`
is the exact rule syntax). But it punches a hole in Decision 21's "JVM does
multi-provider fan-out + audit + rate-limit" — the audit log misses local-LLM
calls, and rate-limiting drops. Better shape when we want it: **JVM-side
LiteLLM gets an Ollama provider too** (LiteLLM supports Ollama natively), and
the pod still goes through the JVM. Same Decision 21 surface, Ollama added as
another provider slug. No pod-side network capability needed.

Path B (LLM inside the pod via llama.cpp-WASM) — heavy, model load times
break the pod lifecycle assumptions, and Wasmer-Edge's memory ceiling
(~256 MB per pod budget in spec-01) is far below any useful model size.
**Flag as not-feasible until both Wasmer heap budgets and a useful sub-256MB
model exist.**

**Recommend:** V1 assumes online proxy. Offline goal lands by adding
**Ollama as a LiteLLM provider in `seon.agent.llm`**, not by giving pods
network capability.

### Q5. claude-code pod eval surface

**Track B has already scaffolded this.** `pod-host/libdatahike-cljs/REPL-WORKFLOW.md`
documents an MCP server that exposes `mcp__seon_cljs__eval` (and
`create_session` / `list_sessions` / `stop_session` / `reload_deps` /
`runtime_status`) — schema-compatible with the existing JVM-side
`mcp__seon__eval`. Wiring (`REPL-WORKFLOW.md:25-44`):
`shadow-cljs watch` writes nREPL port to `.shadow-cljs/nrepl.port`; the MCP
server discovers it on every call; piggyback sessions pivot into the
`:repl` build target so each eval lands inside the live Node runtime that
holds open datahike connections.

This solves the eval surface for the libdatahike-cljs spike — but **for the
real pod (sidecar + bencode bridge per spec-01 WASM-1)**, the path differs:

- The eval **transport** in the real pod is bencode `op: eval` over the
  same sidecar socket the relay uses, not piggyback over shadow-cljs nREPL.
  Spec-01 line 324 + chunks WASM-1 / WASM-4 already nail this.
- The eval **handler** inside the pod is `cljs.js/eval-str` against the
  bootstrap compile-state-ref, with the `:load` callback going back over the
  relay to the JVM-side shadow build for fresh per-namespace assets.
- **Orchestrator path:** `mcp__seon__eval session-id="<pod-id>" code="…"`
  — same MCP tool, the JVM-side handler routes by session-id. When the
  session-id maps to a pod, the JVM forwards bencode `op: eval` over the
  pod's sidecar socket. When it maps to a JVM nREPL session, today's flow.
- **Until the real-pod transport exists:** keep
  `mcp__seon_cljs__eval` as the way to drive the bench/REPL runtime
  (independent socket, no JVM-side mediation), and **plan to retire it** once
  WASM-1's bencode bridge lands. Don't grow the dual-MCP surface.

(unverified: whether Track B's MCP server can run against the real WASM-1
sidecar without modification. Likely yes — if the sidecar exposes nREPL-bencode
the same shape shadow's runtime nREPL does, the discovery + piggyback flow is
the same. Track B is the right reviewer.)

## Draft spec section — "Pod runtime"

Folds into spec-01 (replaces / merges into §"Layer C — Pod compute (CLJS in
QuickJS)" + Phase WASM bring-up references).

---

### Pod runtime

A **pod** is the per-user CLJS execution environment for an Seon agent.
Concretely: a Node sidecar process (`sidecar.mjs`) wrapping a `QuickJSContext`
that hosts a primed ClojureScript bundle. The sidecar is launched as a
subprocess of `wasmer run wasmer/edgejs --safe` so the OS-level capability
surface is gated by Wasmer flags, not by the pod's own code (Decision 37).
One pod per user (Decision 17); pods are claimed via `seon.flow.pool/claim!`
and live for the user's session.

The pod runs the agent's `core.async.flow` loop (Decision 25/26): per-turn,
read message → tool-call orchestration → LLM call (relayed to JVM) → tool
results → next turn. All durable state advances by datahike tx (Decision 28);
the in-pod `*ctx*` atom is a cached projection of `:seon.agent.state/*`
(Decision 11/27).

**Initial state load.** At pod spawn, the host adapter calls
`mem.init-from-jvm pod-id user-id` over the relay. JVM ships current
state of the user's namespace as a stream of datoms (chunked Transit-`:json`
frames over the WebSocket); pod hydrates a local datahike connection backed
by a `:tiered` store — `:memory` frontend, `:file` backend pointed at the
Wasmer-mounted `/data` volume (Decision 39, validated by Track B
CLJS-2a). For browser/Electron shapes, the backend swaps to `:indexeddb`
(same `:tiered` shape; konserve.indexeddb registered side-effect, polyfill
`fake-indexeddb/auto` for Node testing only). Once hydrated, pod subscribes
to JVM's tx-bus over the same socket; each commit replays as `transact!`
against the local replica.

**Relay-writer wiring.** All writes route JVM-ward. `seon.db.relay` (a CLJS
namespace) exposes `(seon.db/transact! :seon.agent.user.<id> tx-data)` →
sends `{:op :transact :ns "…" :tx-data <transit>}` on the WebSocket → JVM
serializes via `seon.db.datahike.flow/request!` → writes to konserve → broadcasts
on the tx-bus. The pod's local replica picks up the broadcast and applies. The
relay also handles capability calls (`host.llm.complete`,
`host.cljs.get-resource`, `host.subprocess.spawn`, etc., per Decisions 21/38/45).
**Wire format throughout: Transit `:json` over WebSocket.** `transit-cljs` does
not support `:msgpack`; revisit only on profile evidence.

**Eval surface.** Two layers.

*In-session compilation.* The pod includes the CLJS self-host compiler
(`cljs.js`, ~3–5 MB) primed in the QuickJSContext (Decision 34). Agent code
calls `(cljs/eval-str compile-state-ref code …)` with a `:load` callback that
asks the JVM-side shadow build for per-namespace JS + analyzer transit
(`host.cljs.get-resource <ns>`). When the agent transacts a new
`:seon.agent.form`, a JVM-side tx-listener triggers shadow's
`build-api/compile-sources`; the resulting per-ns artifact is shipped to the
pod's `:load` cache for the next `(require …)` to pick up
(`shadow-cljs-evaluation.md` §"How it works"). Build target = shadow-cljs
`:target :bootstrap` for the support build + `:target :node-script` (or
`:esm`) for the primary `bundle.js` (Decisions 34 + 42). `:js-provider :require`
for npm interop.

*Claude-code (orchestrator) eval.* `mcp__seon__eval session-id="<pod-id>"
code="…"` routes via the JVM-side MCP handler: when the session-id resolves
to a pod, the JVM forwards bencode `op: eval` over the pod's sidecar socket
to the in-pod handler, which calls `cljs.js/eval-str` and returns the result
via the same socket. Same MCP tool for JVM nREPL sessions and pod sessions —
dispatch is by session-id, not by tool name. Track B's current
`mcp__seon_cljs__eval` (piggybacking shadow's nREPL into the libdatahike-cljs
build) is the validated transport for the spike and retires once the WASM-1
bencode bridge lands.

**V1 tool capability surface** (Decision 45 — user-explicit prompts; per-claim
flags built by the host adapter):

- **File read** (`host.fs.read <path>`) — JVM resolves vs. allow-list,
  reads, returns bytes via Transit. Pod gets no FS capability inside Wasmer
  (`--volume` only mounts the user's data dir as `/data`, used by datahike
  backend; nothing else).
- **File write** (`host.fs.write <path> <bytes>`) — same shape; prompts user.
- **LLM call** (`host.llm.complete`) — relayed to JVM litellm-clj
  (Decision 21). Includes online providers + Ollama-as-a-provider; the pod
  does not get direct network capability.
- **Schema register** (`host.schema.register`) — Decision 14/16, routes
  through `seon.schema/register!`, scope-limited to user's namespace.
- **Subprocess spawn** (`host.subprocess.spawn`) — Decision 38, JVM-side
  only; pod requests, host adapter prompts + shells out.
- **MCP-server connect** — Decision 38, JVM mediates stdio + SSE.

Capabilities are stored as data on `:seon.session/capabilities` (Decision 45);
revocation is a retraction.

**Remaining uncertainty** for the spec-rewrite:

- `core.async.flow` and `datahike` under `:target :bootstrap` is the spec
  WASM-2 spike — neither has been validated end-to-end against the bootstrap
  loader yet. Track B has them under `:target :node-script`; the migration
  is the next-after-CLJS-3 spike.
- The `cljs-datahike` runtime patches (REPL-WORKFLOW.md "Diagnosis sidebar"
  — `btset/from-opts` `:cmp` alias, `insert` rewrite) need to land
  upstream **or** be re-`set!`'d at pod boot in the bootstrap order. Today
  they live in `repl.cljs:46-68` as a top-level `defonce`; the bootstrap
  loader's namespace-init order needs to put them before any datahike index
  builds.
- Bundle size budget under EdgeJS-on-Wasmer: spec-01 names "~3 MB" but the
  shadow-cljs evaluation flags realistic bootstrap setups at 3–8 MB
  minified + gzipped once `cljs.core` + `datahike` + `core.async` + `transit`
  are included. WASM-2 measures this empirically.

---

End draft section.
