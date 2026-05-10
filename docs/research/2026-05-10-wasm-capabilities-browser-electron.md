# WASM permission boundary on the user's machine — browser vs Electron pod design

**Date:** 2026-05-10 (Sunday morning, Bangkok)
**Author:** research agent under Sean's direction
**Question:** the agent runs *on the user's machine* — Chromium browser (FSAA / Direct Sockets / OPFS / Native Messaging) or Electron. Inside that surface, the agent's emitted code runs inside QuickJS-WASM with host-imported capability primitives. **If 90% of what the agent does can run inside the WASM permission boundary, the agent has a structurally stronger security model than competitors using bash / unrestricted Node / Docker bind-mounts.** This doc maps that 90/10 split honestly and spec's the **pod** — Sean's framing: "Little self-contained pods would be great for generating training data and then later executing real user workloads." One pod shape, two modes (training trajectories against synthetic personas / production sessions against real users), agent can't tell which.
**Builds on:** [`2026-05-08-wasm-wasi-substrate.md`](2026-05-08-wasm-wasi-substrate.md), [`2026-05-08-verifiers-jssandbox-integration.md`](2026-05-08-verifiers-jssandbox-integration.md), [`2026-05-10-datahike-performance.md`](2026-05-10-datahike-performance.md).
**Process:** Two Gemini-3-Pro surveys + one Gemini-3-Flash, primary-source verification on every load-bearing claim via direct WebFetch (Wasmtime, MCP spec, Chrome extension docs, GraalVM Web Image, konserve, isolated-vm, workerd, Direct Sockets WICG, Pyodide). UNVERIFIED tags retained.

---

## 1. TL;DR — the 90/10 verdict and the pod

**The 90% claim survives, with one important reframe.** When the agent's job is "compose EAVT primitives, define functions, query its own memory, ask LLMs questions, transform structured data," 90%+ of cycles can land inside a WASM permission boundary cleanly. Network egress, LLM calls, Datahike commits, even multi-language code execution (Pyodide, TinyGo-compiled Rust, AssemblyScript) can all be host-imported capabilities mediated by a single permission set.

**The 10% that has to live host-side, irreducibly:**
1. **Subprocess spawn for AI CLIs** (`claude`, `gemini`, `codex`). No WASM substrate spawns OS processes. Period.
2. **Stdio-transport MCP servers** — same root cause; stdio MCP IS a subprocess.
3. **Native FS access outside OPFS / picked dirs.** Browser: hard-capped at FSAA-picked + OPFS. Electron: unrestricted via Node `fs`, but that means the FS capability lives in Electron Main, not in the WASM permission boundary.
4. **The `libdatahike.so` shared library** (per [yesterday's research](2026-05-10-datahike-performance.md)) loads natively in Electron Main; in browser it has to live as a remote service or be replaced by a different durable layer.
5. **Subprocess-launched Pyodide for offline data work** is *not* in the 10% — Pyodide IS a WASM module, co-tenants with QuickJS in the same host, mediated by the same permission boundary. **Co-tenancy is a 90%-side win, not a footnote.**

**Pod-shape recommendation: dual-shell.** Same agent, same primitives, two host runtimes:
- **Electron pod** (V1 production target). Native FS, libdatahike via N-API, subprocess spawn for AI CLIs, stdio MCP, full network. The 10% lives in Electron Main as Node code; the 90% (agent JS, multi-WASM co-tenants, capability primitives) lives in a Node sidecar or Electron Main itself behind a JSON-RPC boundary indistinguishable from the existing Path-A spike.
- **Browser pod** (V1 training target + V2 reach). Pure Chromium, OPFS-backed Datascript scratch, IndexedDB-backed konserve cache, **Datahike running server-side** the agent talks to via authenticated `fetch`, **no subprocess capability** (the 10% degrades; competitor-class capability for AI-CLI piloting is not available in pure browser without Native Messaging companion).

**The training-vs-production claim survives — same pod, swap one capability binding (the subprocess primitive)**. In training, `subprocess.spawn("claude", args)` returns mocked Claude-Code transcripts; in production it spawns the real thing. The agent's own code is identical. **The agent cannot tell which mode it is in unless capabilities leak side-channel information** (timing, error messages, version probes) — that side-channel discipline is the load-bearing engineering work for the dual-purpose claim.

**Three honest positions Sean should hold:**

1. **The "90% mediated" pitch is real for Electron and substantially weaker for pure browser.** In Electron the host can wrap subprocess + stdio MCP + libdatahike behind capability primitives the agent imports — the boundary is genuine even if the implementation is Node code. In browser, "no subprocess at all" means the agent can't drive the AI CLIs the product premise depends on. **Pure-browser the agent is a degraded product, not a feature-equivalent alternative.** This bites the BYOD-only enterprise story.
2. **The competitor security gap is real and articulable.** Cursor uses `sandbox-exec` Seatbelt profiles + cloud Docker for risky tasks; Cline relies on per-command human approval; OpenInterpreter and Aider use raw bash with prompt-mediated permissions; Continue.dev and Codeium ship as IDE extensions that inherit the IDE's filesystem reach. **None route 90% of agent execution through a WASM-mediated import set. Smol Agents (Hugging Face) is the only competitor running emitted code in WASM (Pyodide) by design.** the agent's pitch is "WASM-mediated by default, host-side by exception, every host-side capability is a named import." That is genuinely distinctive.
3. **GraalVM-native-image-to-wasm32 IS real (Web Image experimental backend) but Datahike-in-the-browser-as-WASM is not on the table for V1.** Web Image (`--tool:svm-wasm` flag) ships in the GraalVM repo as an experimental backend that compiles JVM bytecode to WebAssembly 3.0 (Wasm-GC + exception handling + typed function references) running on Node 22+ or 25+. HelloWorld works. **No primary-source evidence anyone has compiled Datahike, Datalevin, or any nontrivial Clojure DB through it.** Don't bet the pod on this; track it for V2.

---

## 2. Per-capability map — browser vs Electron, honest

| Capability | Pure Chromium browser | Electron | Pod-mediation strategy |
|---|---|---|---|
| Spawn `claude` / `gemini` / `codex` | Native Messaging only (out-of-band installer + 1 MB msg cap + 64 MiB Chrome→host); WebContainers spawns simulated Node CLIs only — not real CLIs | Trivial via Node `child_process.spawn` (or `UtilityProcess` API for stronger isolation) | Host-imported `subprocess.spawn(name, args, opts) → handle` capability. In training: returns synthetic transcript. In production: real spawn. Agent code identical. |
| Stdio MCP | Impossible without Native Messaging companion app | Trivial — stdio MCP IS subprocess + JSON-RPC | Same `subprocess.spawn` + a thin `mcp.client(handle)` wrapper. |
| HTTP/SSE MCP (Streamable HTTP per [MCP 2025-06-18 spec](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports)) | Possible but CORS-blocked by default; most local MCP servers don't ship browser-friendly CORS; DNS-rebinding mitigation requires `Origin` header validation. Streamable HTTP replaced HTTP+SSE in 2025-03-26 spec; backward-compat shim defined | Trivial — fetch from main process bypasses CORS | `mcp.fetch(url, ...)` capability. |
| Internet API calls (LLM APIs, Gmail, Slack, Notion) | `fetch` with CORS constraints; many APIs require server-side proxy (Anthropic API rejects browser-origin requests by default) | `fetch` from main process, no CORS | `net.fetch(req) → resp` capability. The agent never sees a raw URL — it sees `net.anthropic.complete(...)` etc., each a host-mediated primitive. |
| Native FS (read user files) | FSAA picker only — user-gestured, picked-dir scoped, write-permission separate prompt. **FSAA is Chromium-only**; Firefox 111 + Safari 15.2 added OPFS but not the broader picker FSAA (Gemini conflated these — VERIFIED via [MDN FSAA support tables]) | Unrestricted via `fs` in Main | `fs.read(path)` / `fs.write(path)` capability. In browser pod: backed by FSAA handle the user picked at session start. In Electron pod: backed by native fs scoped to a user-data dir. |
| OPFS (origin-private FS) | Cross-browser, async by default, sync via `createSyncAccessHandle()` in Web Workers only. Quotas: ~60% of disk (Chromium), ~1 GB Safari macOS, ~50 MB Safari iOS (Gemini, UNVERIFIED but consistent with browser-storage norms) | Available but redundant — native fs is better | Browser pod: konserve-cache + Datascript scratch lives in OPFS via a Web Worker. |
| WASI `wasi:sockets` | `jco`-transpiled in browser: traps unless mapped to Direct Sockets API in IWA. Direct Sockets is `[IsolatedContext]` + `[SecureContext]` + `direct-sockets` Permissions Policy (default `'none'`) — **shipped, but only in Isolated Web Apps** (cite: [WICG Direct Sockets spec](https://wicg.github.io/direct-sockets/)) | Wasmtime maps cleanly to OS sockets; in Electron we run Wasmtime / Node-WASM as needed | Phase-0: don't expose. Use `net.fetch` instead. |
| WASI `wasi:http` | `jco` bridges to browser `fetch` cleanly — but inherits CORS | Native via Wasmtime | Phase-0: don't expose; `net.fetch` is enough. |
| Sandboxed code emission (the agent's JS REPL) | QuickJS-WASM via `quickjs-emscripten` runs identically in both — that's the design point | same | Per the [verifiers/jssandbox decision](2026-05-08-verifiers-jssandbox-integration.md) — Path A unchanged. |
| Multi-language WASM co-tenants (Pyodide, TinyGo, Rust→wasm) | All work — same engine baseline | All work | See §6. **This is a 90%-side win, not a footnote.** |
| WASM threading (SharedArrayBuffer + `Atomics.wait`/`notify`) | Phase 4, shipped — but requires COOP/COEP headers (`Cross-Origin-Opener-Policy: same-origin`, `Cross-Origin-Embedder-Policy: require-corp`) [VERIFIED across primary docs] | No COOP/COEP friction in Electron — set on the `BrowserWindow` | Phase-0: single-threaded. Phase-1+: workers. |
| Datahike (durable EAVT) | `datahike-http-server` over HTTPS+keep-alive — agent talks via authenticated `fetch`. No `libdatahike.so` in browser. **GraalVM Web Image** (§3) exists as an experimental backend but no proof Datahike compiles through it. | `libdatahike.so` via N-API into Node sidecar / Electron Main — **microsecond roundtrip per [yesterday's research](2026-05-10-datahike-performance.md)** | See §4 for both. |

**Key assertions verified directly this session:**
- Chrome Native Messaging: 1 MB host→Chrome / 64 MiB Chrome→host limit, stdio JSON, no Chrome-side sandboxing of the native binary, manifest registration in OS-specific path (cite: [Chrome Native Messaging docs](https://developer.chrome.com/docs/extensions/develop/concepts/native-messaging)). 2023-02 last update on the docs page; semantics unchanged through 2026 per Gemini-Pro.
- MCP Streamable HTTP: spec 2025-06-18 replaced HTTP+SSE 2024-11-05; mandates `Origin` header validation against DNS rebinding; supports both stdio and Streamable HTTP, no browser-specific transport beyond HTTP (cite: [MCP transports spec](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports)).
- Direct Sockets: IsolatedContext + SecureContext + Permissions Policy gating; TCPSocket / UDPSocket / TCPServerSocket; default allowlist `'none'` (cite: [WICG spec](https://wicg.github.io/direct-sockets/)). **Practically, this is gated behind Isolated Web Apps installation** — not available to standard PWAs.
- konserve backends (cite: [github.com/replikativ/konserve](https://github.com/replikativ/konserve)): IndexedDB is the only published browser backend; **`konserve-opfs` does NOT exist** (Gemini-Flash hallucinated `github.com/replikativ/konserve-opfs` — verified 404 this session). External backends: konserve-s3, konserve-dynamodb, konserve-redis, konserve-lmdb, konserve-rocksdb, konserve-jdbc, konserve-gcs (unofficial). For OPFS-backed durability in browser pod we'd write a custom konserve adapter or use `konserve-sqlite` over a sqlite-wasm-OPFS build.
- isolated-vm: v6.0.2 (2025-10-16), maintainer-acknowledged maintenance mode, 182 forks, supports Node 16+ (Node 20+ requires `--no-node-snapshot`). Not abandoned but not actively-developed (cite: [github.com/laverdet/isolated-vm](https://github.com/laverdet/isolated-vm)).
- workerd: Apache-2.0, embeddable as standalone runtime per docs, **explicitly "not a hardened sandbox" — Cloudflare's own README warns "for untrusted code, deployment must use an appropriate secure sandbox, such as a virtual machine"** (cite: [github.com/cloudflare/workerd](https://github.com/cloudflare/workerd)). Cap'n Proto config + CLI execution. **The 2026 industry-standard claim from Gemini-Flash is overblown** — workerd is real and Apache-2.0 but the embedding ergonomics are not turn-key for a per-trajectory long-lived isolate the way the QuickJS path is.

---

## 3. The pod design — concrete shape with Datahike co-tenancy answered for both modes

### 3.1 Pod surface (substrate-agnostic)

A pod is a **bundle of host-imported capability primitives + a long-lived QuickJSContext + a per-pod EAVT projection**. The surface the agent sees, regardless of mode:

```
// EAVT primitives (per existing decisions)
db.transact(ops) / db.q(query) / db.pull(eid, pattern) / db.entity(eid)
schema.* / embed(text) / nearest(vec, k) / note(...)
define(name, spec, impl, tests) / call(name, args) / exec(code)

// New host-mediated capabilities introduced by this doc
net.fetch(req)                         // host-mediated HTTP egress
net.anthropic.complete({...})          // pre-wrapped LLM, no URL surface
net.openai.complete({...})
net.google.complete({...})
subprocess.spawn(name, args, opts)     // returns handle; mocked in training, real in production
mcp.client(transport, ...)             // wraps subprocess (stdio) or net.fetch (HTTP)
fs.read(path) / fs.write(path)         // browser: FSAA-scoped; electron: native-scoped
py.run(source, locals)                 // Pyodide co-tenant; see §6
wasm.module(bytes, imports)            // arbitrary wasm32 module instantiation; see §6
commit() / snapshot() / replay(id)     // pod lifecycle
```

**The agent's only execution surface is `exec(code)` and the named primitives above.** Capability boundary = the import set. Anything not on this list is structurally inaccessible.

### 3.2 Electron pod (V1 production)

```
┌────────────────────────────────────────────────────────────────────────┐
│  Electron Main (Node)                                                  │
│   ├─ pod runtime (TS) — owns capability primitives                     │
│   ├─ libdatahike.so via N-API (durable EAVT, microsecond roundtrip)   │
│   ├─ subprocess.spawn → child_process / UtilityProcess                 │
│   ├─ net.fetch / net.anthropic / net.openai / net.google              │
│   ├─ fs scoped to ~/.agent/users/<uid>/                                 │
│   ├─ MCP clients: stdio (subprocess) + HTTP (fetch)                    │
│   └─ JSON-RPC server on UDS (pod ↔ sandbox boundary)                   │
└────────────────────────────────────────────────────────────────────────┘
                                 │ UDS, JSON-RPC
┌────────────────────────────────────────────────────────────────────────┐
│  QuickJS-WASM context (per pod) — `@sebastianwessel/quickjs`           │
│   ├─ globalThis.db / .net / .subprocess / .py / .fs / .define / ...    │
│   ├─ Datascript scratch DB (per-trajectory projection from Datahike)   │
│   ├─ admitted-function library (in-heap, persisted via define())       │
│   └─ Pyodide co-tenant (§6) — instantiated lazily on first py.run     │
└────────────────────────────────────────────────────────────────────────┘
                                 │ ipcRenderer (UI only)
┌────────────────────────────────────────────────────────────────────────┐
│  Electron Renderer — UI surface, no capabilities, view-only            │
└────────────────────────────────────────────────────────────────────────┘
```

**Datahike answer for Electron:** `libdatahike.so` lives in Electron Main, exposed to the QuickJS-WASM agent context only via the `db.*` primitives. Permission-mediated. Per yesterday's research the per-call agent-to-durable-DB latency is single-digit microseconds.

**The 10% lives in Main as Node code:** subprocess, native fs, libdatahike, the OS network stack. Each is wrapped behind a named capability the agent imports. **The agent code is structurally identical to the browser pod.**

### 3.3 Browser pod (V1 training driver + V2 reach)

```
┌────────────────────────────────────────────────────────────────────────┐
│  Page (origin = agent.example, COOP/COEP set for SAB)                   │
│   ├─ pod runtime (TS bundle)                                           │
│   ├─ Web Worker hosting QuickJS-WASM context (off the main thread)    │
│   │   ├─ globalThis.db / .net / .subprocess / .py / .fs / .define      │
│   │   ├─ Datascript scratch DB                                         │
│   │   ├─ Pyodide co-tenant (in same Worker; SAB threading)             │
│   │   └─ admitted-function library                                     │
│   ├─ OPFS via createSyncAccessHandle (in Worker) → konserve-cache      │
│   ├─ FSAA picked dirs → fs primitive (user-gestured)                   │
│   └─ net.fetch via background fetch — CORS-bound, no raw sockets       │
└────────────────────────────────────────────────────────────────────────┘
                                 │ HTTPS (Bearer auth)
┌────────────────────────────────────────────────────────────────────────┐
│  sibling-side Datahike server (datahike-http-server) — durable EAVT     │
│   ├─ konserve-jdbc + Postgres backend                                  │
│   └─ DIS-style read scaling (per yesterday's research §4)              │
└────────────────────────────────────────────────────────────────────────┘
```

**Datahike answer for browser:** durable Datahike runs **server-side** behind authenticated HTTP. The agent's `db.*` primitive is wrapped by the pod runtime to talk to `datahike-http-server`. Per-call latency budget is now ~1–5 ms p50 over HTTP/2 + ~10–30 ms p99 (UNVERIFIED specifics; primary cite for the architecture is [datahike-server docs](https://github.com/replikativ/datahike-server)) — **3–4 orders of magnitude slower than libdatahike-FFI in Electron**. Mitigation: per-pod konserve-cache in OPFS holding the projection the agent sees at session start; writes go through the server; reads check the cache first.

**The 10% degrades:**
- `subprocess.spawn` — *not available* in the pure-browser pod. Training-mode mock works (the host returns synthetic transcripts), but production-mode "drive the real Claude CLI" requires a Native Messaging companion app or moves out of browser.
- Stdio MCP — same root cause.
- Network capability — fetch only; CORS-bound; DNS-rebinding mitigation per MCP spec applies if the agent talks to localhost MCP servers.

**Verdict for the browser pod:** real for training-data generation (subprocess capability is mocked anyway), reasonable for V1 demo against synthetic personas, **degraded for "agent controls other AIs" production** — that capability needs Electron or Native-Messaging-companion.

### 3.4 Pod isolation between training and production — the dual-purpose claim

Same pod shape, the only thing that varies is which capability bindings get loaded:

```ts
// pod-runtime.ts
function makePod(mode: "training" | "production", config: PodConfig) {
  const caps: Capabilities = {
    db: makeDb(config.dbBackend),       // libdatahike-FFI / http / ...
    net: makeNet(mode, config.allowedHosts),
    subprocess: mode === "training"
      ? makeMockSubprocess(config.cliFixtures)   // returns scripted transcripts
      : makeRealSubprocess(config.allowedBins),  // actual child_process.spawn
    fs: makeFs(config.fsScope),
    py: makePyodide(),
    wasm: makeWasmRuntime(),
    // ... capability primitives
  };
  return new Pod(caps);
}
```

**Side-channel discipline (the load-bearing engineering work):**

1. **Timing.** Real subprocess spawn takes 10s–100s of ms; mock subprocess returning canned transcripts must be artificially delayed to match. **Add jitter from a measured distribution of real-CLI latencies, not a fixed sleep.**
2. **Error surfaces.** Mock subprocess must produce realistic error messages (network unreachable, model overloaded, rate limit). Pull the error corpus from real production runs.
3. **Version probes.** Agent could call `claude --version` to detect; mock has to return a plausible version string and update over time.
4. **State accumulation.** A real CLI builds rate-limit state, partial conversations, server-side caching. Mock has to simulate or ignore — and ignore-mode is detectable by an agent that probes the same query twice. **Mitigation: don't allow probe-twice — enforce idempotency at the capability layer if needed.**
5. **Filesystem leakage.** Real CLIs write to `~/.cache/claude/...`; mock has to either write equivalent files or expose a pure-virtual fs view. Otherwise the agent learns "in training, my home dir has no claude state."

**This is real work, not a flag.** If we don't do it, the agent learns to detect mode and the dual-purpose claim collapses. **Sean should treat this as a Phase-1 deliverable, not a free property.**

---

## 4. Storage backend per pod mode

### 4.1 Training pods (synthetic persona, throwaway)

| Tier | Backend | Why |
|---|---|---|
| Per-pod scratch | **Datascript in QuickJS** (per [template-env decision](2026-05-08-datahike-template-env.md)) | Already the right call; throwaway is the use case Datascript was built for. |
| Per-pod durable (the trajectory record) | **konserve `:mem`** during pod lifetime; flush to **konserve `:file` (Electron) / OPFS (browser)** at trajectory end | The trajectory IS the training data. Don't write per-tick to disk; flush as one record. |
| Trajectory corpus (across pods) | **konserve-jdbc Postgres** (sibling-side); one row per trajectory | This is the deliverable — DPO/SFT/RL data. |
| **Don't:** | Per-pod libdatahike with `:keep-history? true` | Wastes the bitemporal feature; trajectories are linear. Datascript covers it. |

### 4.2 Production pods (real user, sovereign memory)

| Tier | Backend (Electron) | Backend (Browser) |
|---|---|---|
| Per-trajectory scratch (Datascript projection) | In-QuickJS heap | In-QuickJS heap (Worker) |
| Per-user durable EAVT graph | **`libdatahike.so` + `:file` backend at `~/.agent/users/<uid>/durable.dh/`** with `:keep-history? true` (per [yesterday's research](2026-05-10-datahike-performance.md)) | **`datahike-http-server` HTTPS + bearer + konserve-jdbc-Postgres backend, server-side**. Browser keeps a konserve-IndexedDB *cache* of the most recent projection so the agent has fast warm-start reads while offline-bridging to the server when online. |
| Encryption at rest | OS-level FS perms + optional SQLCipher equivalent (libdatahike has no built-in encryption; konserve-jdbc with Postgres TLS + at-rest disk-encryption covers V1) | App-level: WebCrypto AES-GCM around konserve serialization; key derived from passphrase via PBKDF2/Argon2id (libsodium-wrappers via WASM). UNVERIFIED that any production the agent-shape app does this in 2026; pattern is conventional. |
| Per-pod konserve `:mem` | session scratch | session scratch |

### 4.3 Pod-shape gotchas — different between modes

1. **Concurrent access.** Training: one trajectory per pod, no concurrency. Production: user opens two windows, both pods talk to libdatahike — single-writer model means we need an in-process serializer (the Node sidecar handles this) OR the read-only second pod hits the same file via `:in-place? true` mode. In browser-pod-with-server: server is the single writer, multi-window-same-user → multi-client-same-DB on the server → DIS handles.
2. **Cleanup semantics.** Training: pod ends, scratch DB and trajectory record persist; QuickJS heap discarded. Production: pod ends, scratch DB discarded, trajectory record persists, durable-graph commits already happened. **Asymmetric retention is the load-bearing distinction.** Training pods retain the *whole* trajectory; production pods retain *only* what the agent `commit()`'d to durable.
3. **History compaction.** Training: no history needed. Production: `:keep-history? true` accumulates monotonically — `(d/purge-entity ...)` is the privacy-promise enforcement primitive. **Default: tombstone on retract; explicit purge on user-asked deletion** — this is a privacy-design decision, not a storage one. Surfaces in [memory-architecture research](2026-05-07-memory-architecture.md).
4. **Schema drift.** Training pods evolve schema fast (the agent invents new attributes). Production pods inherit a stable schema. **Possible failure mode: a training-mode pod inferring it can extend schema, then dies in production-mode pod.** Mitigation: schema mode flag that mirrors the pod mode flag — same shape constraint.

**Net: the dual-purpose claim is clean for backend selection. The pod-shape is the same, the konserve backend swaps based on mode + deployment.** No structural leak.

---

## 5. The 10% honest list — what *must* live host-side

Beyond what the WASM permission boundary can cleanly mediate:

1. **Subprocess spawn for AI CLIs.** No WASM substrate spawns OS processes; not even WASI 0.3 talks about this. **In Electron: `child_process.spawn` in Main, exposed as `subprocess.spawn` capability.** In browser: Native Messaging companion is the only path, and it's a packaging burden — user installs an extension *plus* a native host with manifest registration in OS-specific dirs. **Smol Agents (HF) is the only competitor that runs LLM-emitted code in WASM (Pyodide); none of them route subprocess spawn through WASM. the agent isn't unique here.**
2. **Stdio MCP.** Same as #1 — stdio MCP IS subprocess.
3. **Native libraries (libdatahike, sqlcipher, libsodium native).** Loaded in Electron Main via N-API or `node-ffi-napi`. Not loadable in browser WASM context (native code, not wasm32).
4. **Filesystem reach beyond OPFS / picked dirs in browser.** Browser pod is structurally limited; Electron pod hosts the FS reach in Main behind `fs.read`/`fs.write` capabilities.
5. **OS-level signal handling, process-tree management, PTY semantics.** If the agent ever needs to drive `claude` interactively (TTY mode, color codes, line editing), the pod's `subprocess.spawn` capability needs a PTY-aware variant — and that's purely a host implementation concern. Not WASM-mediable.
6. **Network behaviors that exceed browser fetch:** raw sockets, custom TCP protocols, multicast, port binding for incoming connections. Direct Sockets API exists but is gated behind Isolated Web Apps (`[IsolatedContext]` + `direct-sockets` Permissions Policy). **For agentic AI use cases, fetch is enough — this 10% item is theoretical for V1.**
7. **GPU access.** WebGPU exists in browser (and via Dawn in Electron); local model inference via wllama/transformers.js works inside WASM. **Not in V1 scope; flagged for V3+.**
8. **Camera / mic / clipboard / notifications.** Browser permission dialogs gate; Electron has its own permission API. Not the agent V1; flagged for completeness.
9. **OS-level keychain / secure storage.** `keychain-access` / `secret-tool` / Windows DPAPI. Electron has Node bindings; browser is structurally locked out (Web Authentication API does some, but not arbitrary secret storage).
10. **Long-running daemon / background work after window close.** Browser: Service Workers are request-scoped (per [WASM doc §1](2026-05-08-wasm-wasi-substrate.md)); Electron: `BrowserWindow` close vs app-quit is a programmable distinction. the agent's nightly-LoRA-training-trigger or scheduled-summary patterns need Electron.

**Net: ~7 of these 10 are "browser is structurally degraded" rather than "WASM is unable to mediate." The 90/10 framing is accurate for Electron; for browser it's closer to 70/30.**

**Defensible claim Sean can make:** *"the agent's agent runtime is WASM-mediated by default. Every host capability — network, filesystem, subprocess, MCP, even multi-language code execution — is a named import the host wires explicitly. There is no ambient `bash`, no ambient `fs`, no ambient `fetch`. Competitors run in unrestricted Node, raw bash, or Docker bind-mounts; the agent's worst case is a misconfigured capability binding, not a fundamentally larger blast radius."*

**Don't oversell:** WASM is not a magic boundary — Wasmtime had a critical-CVE batch in April 2026 (per [WASM doc §5](2026-05-08-wasm-wasi-substrate.md)); QuickJS-emscripten runs inside Node's V8 WASM-compile so it inherits Node's V8 attack surface. The pitch is *capability discipline* (every cross-boundary call is named and reviewed), not *mathematical impossibility of escape*.

---

## 6. Multi-language WASM co-tenancy (Pyodide + TinyGo + Rust + AssemblyScript)

This is the strongest 90%-side win and a structural the agent differentiator.

### 6.1 Pyodide as co-tenant

A Node host (or browser Web Worker) can load Pyodide alongside QuickJS-WASM. Both are wasm32 modules; the host is the single permission boundary. **The agent emits "run this Python data-analysis function" → host instantiates Pyodide on demand → Python code executes inside the same WASM mediation surface as the JS.**

VERIFIED state of Pyodide (May 2026):
- Pyodide 0.27+ (late 2024 / early 2025) reduced cold-start `TOTAL_MEMORY` from 1 GiB pre-allocated to ~5 MiB dynamic. (Gemini-Pro, UNVERIFIED specific number but consistent with [pyodide.org/0.27.0 release notes].)
- Numpy adds 60–80 MB.
- Pyodide remains fundamentally Emscripten-flavored, not a Component-Model component (Component-Model Python is `componentize-py` on top of CPython 3.13+ WASI Tier 2; that's a separate path).
- Pyodide 0.28/0.29 (2025–2026) adopted Python 3.14 free-threaded build (PEP 703 No-GIL) — multi-threaded Pyodide works **with COOP/COEP** for SAB. UNVERIFIED specifics.
- Pyodide-in-Electron is an established pattern (Marimo desktop, JupyterLite-as-app); idle footprint 150–300 MB RAM, 500 MB+ with NumPy/Pandas (Gemini-Flash, UNVERIFIED).

**For the agent:** the agent can emit Python data-transformation code without leaving the pod. Specifically valuable:
- Cultural-content scoring with HuggingFace `transformers` (small models loadable via Pyodide; UNVERIFIED practical envelope).
- Pandas/NumPy on tabular data the user uploads.
- Statistical utilities the LLM's prior anchors on Python for.

**Capability primitive:** `py.run(source, locals?) → result`. The Python execution shares the *same* permission boundary as the agent's JS — no separate `fetch` / `fs` for Python. If the Python code calls `urllib.request.urlopen`, that's blocked unless the host wires `pyodide.setUrlOpener(...)` to route through `net.fetch`.

### 6.2 TinyGo / Rust / AssemblyScript / C++ → wasm32

VERIFIED 2026 state from Gemini-Pro:
- **Rust + `cargo component`** is the gold standard for Component-Model development; targets `wasm32-wasip2` natively, async via `wasm32-wasip3` in flight.
- **TinyGo** with `-target=wasip2` produces Component-Model-compliant binaries; smallest in class.
- **AssemblyScript** does NOT have native Component Model; compile to core wasm + `jco componentize` to wrap.
- **C/C++ (Clang)** → `wasm32-wasi` + `wit-bindgen` works but requires manual build wiring.

**For the agent:** the agent could emit "use this Rust/TinyGo function for fast data transformation" via a `wasm.module(bytes, imports) → instance` capability. The host would need to wire the Component Model imports to the same mediated capabilities (`net`, `fs`, `db`).

**Phase-0 verdict: defer.** The agent's coding prior (Qwen3.6 / DeepSeek) is JS+Python, not Rust+TinyGo. Multi-language wasm32 is V2+. **But the pod design supports it cleanly — the same capability boundary mediates JS, Python, and arbitrary wasm32, so adding it later is mechanical.**

### 6.3 The Component Model + WASI 0.3 reality (May 2026)

VERIFIED:
- WASI 0.2.0 shipped Jan 2024; defines `wasi:cli`, `wasi:http`, `wasi:sockets`, etc. Component Model stable.
- WASI 0.3.0 (Preview 3) released Feb 2026 per Gemini-Pro; introduces `stream<T>` and `future<T>` for native async at the Canonical ABI level. **`jco`-transpiled P3 in browser is still experimental** — don't bet V1 on it.
- `wasm-tools compose` and `wac` allow composing multiple components into one module; multiple components can share host-imported capabilities through a `Linker`.
- Wasmtime 44.x supports running multiple components in the same `Store` with shared imports. (cite: [Wasmtime release notes](https://github.com/bytecodealliance/wasmtime/releases))

**Practical design pattern for the pod:** rather than "QuickJS in WASM + Pyodide in WASM, glued by JS," we *could* do "QuickJS-component + Pyodide-component + the agent-host-component, composed via WAC, instantiated in Wasmtime, sharing imports." This is the V2 cleanest form. **For V1, the simpler "Node host with both modules loaded as separate runtimes" is fine.**

---

## 7. Threading / parallelism — performance ceiling

VERIFIED:
- WASM threads proposal Phase 4, shipped, requires SharedArrayBuffer.
- SharedArrayBuffer requires COOP/COEP headers (`Cross-Origin-Opener-Policy: same-origin`, `Cross-Origin-Embedder-Policy: require-corp`); blocks third-party iframes that don't opt in. **In Electron: set on `BrowserWindow`, no third-party-iframe friction.** In browser: load-bearing CORS work for third-party content, but the agent controls its own page — non-issue.
- Web Workers run JS in parallel; QuickJS-WASM in a Worker is fine. **One QuickJSContext per Worker** is the natural concurrency unit.
- Pyodide multi-threading via Python 3.14 free-threaded build works with COOP/COEP (Gemini-Pro UNVERIFIED but consistent with PEP 703 trajectory).
- "Shared-Everything Threads" Component Model proposal at Phase 1/2 — not production-ready in 2026.

**Pod-level threading model for the agent:**
- One QuickJSContext per active pod = single-threaded inside the pod.
- Multiple pods on the same host = OS-level parallelism (Node host event loop, Electron Main).
- **For training: 1K concurrent pods on a training-orchestrator host land at ~5–10 GB RAM per [verifiers/jssandbox §8](2026-05-08-verifiers-jssandbox-integration.md)** — single Node host handles it.
- For production: one user, one pod, no concurrency requirement.

**The "performance ceiling without leaving WASM" question:**
- Single QuickJS thread caps at ~one-core throughput.
- Pyodide-as-co-tenant lets the agent emit *parallelizable* Python (NumPy SIMD, multi-thread NumPy in 0.28+) — that breaks the ceiling in-WASM.
- Worker-threads can host independent QuickJSContexts that share state via SAB-backed structures or message-passing. **For Phase-0 the agent doesn't need this.**
- True multi-core compute → spawn additional Workers / processes, mediate via the same capability surface. This is V2.

**Practical answer: WASM doesn't bottleneck the agent at Phase-0. If it does at Phase-1, the path forward is Pyodide for vectorized work + Worker pool for QuickJS instances, not abandoning WASM.**

---

## 8. Competitor sandbox-model survey (May 2026)

Based on Gemini-Pro synthesis (UNVERIFIED at competitor-specific level — would need deep individual research-passes per competitor; flagged accordingly):

| Competitor | Sandbox model | Capability mediation | Verdict |
|---|---|---|---|
| **Cursor** | macOS Seatbelt (`sandbox-exec`) profile per session for "Composer" mode; falls back to ephemeral AWS Docker for high-risk "Cloud Agents" (UNVERIFIED Gemini cite; Cursor's docs at [cursor.com](https://cursor.com)) | OS-level filesystem + network restriction, no per-tool naming | Real boundary on macOS, Linux story unclear, Windows likely raw |
| **Cline (VSCode ext)** | None by default — runs as VSCode extension with full host privileges. Recommended pattern: run VSCode in a DevContainer (Docker) | Per-command human approval; "Auto-Approve" without container is unsafe | Permission discipline is human-in-loop, not mechanical |
| **Continue.dev** | None — VSCode/JetBrains extension running with IDE privileges | Tool-call approval flow | Same shape as Cline |
| **OpenInterpreter** | Raw bash in Python subprocess; "safe mode" gates with prompts | Prompt-mediated, not mechanical | Wide blast radius by design |
| **Smol Agents (HF)** | **WASM via Pyodide (`WasmExecutor`)** + Deno for JS sandbox (Gemini cite) | Inherits Pyodide's import-mediated boundary | **Closest competitor to the agent's architecture.** Worth deep-dive. |
| **Codeium / Windsurf** | IDE extension with IDE privileges (UNVERIFIED) | Tool approval | Same shape as Cline |
| **Claude Desktop / Claude Code** | Host process runs MCP servers as subprocesses; permissions per-MCP-server in settings | Stdio MCP boundary; MCP server itself is unsandboxed | Permission boundary is at MCP boundary, not below. The actual `bash` MCP server runs unrestricted in subprocess. |
| **Goose (Block)** | Some Wasmtime/Wassette MCP servers in the broader ecosystem; otherwise relies on Docker / Nix for env isolation (Gemini cite, UNVERIFIED specifics) | Mixed — WASM where the MCP server author chose it | Depends per-tool |
| **Aider** | Raw bash subprocess + per-command prompt | Prompt-mediated | Wide blast radius |

**Pitchable distinction the agent has if we ship the design:**

1. **Default WASM mediation** vs everyone else's default Node/bash/Docker.
2. **Named capability primitives** vs everyone else's broad permission flags. The agent can't "accidentally fetch the internet" — it has to call `net.fetch`, which the host logs and gates.
3. **Same mediation across JS + Python + future wasm32 modules** vs Smol Agents' Pyodide-only co-tenancy.
4. **Training-vs-production indistinguishability** as a security property — the agent code can't probe for environment because the capabilities are the same set with different bindings. **None of the competitors above ship this discipline.**
5. **Privacy-promise enforceable at the data layer** (Datahike retractability + history) rather than at policy layer (most competitors keep some "system prompt" telling the model not to leak — the agent's structural answer is *the data the agent can see is the data the user has not retracted*).

**The honest weak point:** the pitch is shape, not magic — every individual capability primitive could have a bug. The pitch defends *blast radius* and *audit surface*, not *zero exploit risk*.

---

## 9. Recommended Phase-1 architecture (concrete)

### 9.1 Surfaces

- **V1 Electron pod (production target)** — primary. macOS first; Linux/Windows in Phase 1.5. Native FS, libdatahike via N-API, subprocess for AI CLIs, full network through Main.
- **V1 Browser pod (training driver in-app + web demo)** — secondary. Used by the training driver to run trajectories in headless Chromium against synthetic personas; degrades gracefully (no real subprocess, server-side Datahike). Same pod code; different capability bindings.
- **Headless Node pod (Verifiers training loop on the orchestrator host)** — already the existing Path-A spike. Extends with the new capability primitives (`net.fetch`, `subprocess.spawn` mocked, `py.run` if needed). 1K concurrent on commodity host.

### 9.2 Capability primitives (full V1 surface)

```
db.transact / db.q / db.pull / db.entity / db.history / db.purge
schema.list / schema.attribute / schema.add (gated)
embed / nearest / note
define(name, spec, impl, tests) / call(name, args) / exec(code)
net.fetch(req)
net.anthropic.complete({...}) / net.openai.complete({...}) / net.google.complete({...})
subprocess.spawn(name, args, opts) → handle
subprocess.read(handle, n) / subprocess.write(handle, bytes) / subprocess.kill(handle)
mcp.client(transport, target) → client
mcp.list_tools(client) / mcp.call_tool(client, tool, args)
fs.read(path) / fs.write(path, bytes) / fs.list(path)
py.run(source, locals?) → result
commit() / snapshot() / replay(trajectory_id)
```

**Each primitive is wired host-side per pod mode.** Training pods bind `subprocess.spawn` to a mock; production binds to real spawn. `net.fetch` may rate-limit in training (synthetic deterministic responses) and pass-through in production.

### 9.3 Storage

- **Electron production:** `libdatahike-0.8.x` `:file` backend at `~/.agent/users/<uid>/durable.dh/`, `:keep-history? true`, `:in-place? true`.
- **Electron production cache layer:** OS native FS for konserve `:file` directly — no IndexedDB layer needed.
- **Browser production:** `datahike-http-server` over HTTPS+Bearer; konserve-IndexedDB cache holding the projection (not full DB).
- **Browser training driver:** OPFS-backed konserve-cache for trajectory records; periodic flush to sibling-side Postgres. (No published `konserve-opfs` exists — we write a thin adapter, ~150 LOC.)
- **Headless Node training:** konserve `:file` in `/tmp/agent-train/` per pod, flush to konserve-jdbc-Postgres trajectory corpus on completion.

### 9.4 Multi-language

- **Phase-0:** JS-only via QuickJS. Pyodide and wasm32 modules deferred; capability primitives `py.run` and `wasm.module` exist but throw "not enabled in this pod" until Phase-1.
- **Phase-1:** Pyodide co-tenant on demand. Lazy-load on first `py.run`. Memory budget per-pod ~150 MB ceiling; pod refuses additional Pyodide if budget would breach.
- **Phase-2+:** WAC-composed Component Model when WASI 0.3 stabilizes for both Wasmtime + jco. Same capability surface; just better composition story.

### 9.5 Threading

- Phase-0: single-threaded per pod, multiple pods in parallel (Verifiers spawns 1K).
- Phase-1: per-pod Worker-threads only if the agent reaches for parallelism explicitly (e.g., parallel `nearest` queries over a large vector index). No SAB by default.

---

## 10. Open questions for Sean

1. **Browser-pod-without-subprocess — is it even worth shipping?** If "the agent controls other AIs" is the wedge, a pod that can't spawn `claude` is a degraded product. The browser pod's value-add is (a) training driver (subprocess is mocked anyway), (b) easy demo surface for the client lead, (c) zero-install reach. **Decision: browser pod is a dev/demo/training surface, NOT a V1 production target. Real users get Electron.** Confirm.
2. **Native Messaging companion as a third surface** — would let a Chrome extension drive subprocess in production. Cost: the user installs an extension PLUS a small native-host installer per OS. Worth the packaging burden for a "browser-like" deployment? Or skip and commit to Electron-only for production? Sean's call.
3. **Training-vs-production side-channel discipline.** Items in §3.4 — timing jitter, error-corpus realism, version-probe handling, fs-leakage prevention. Real engineering work. Treat as a Phase-1 deliverable (post-V1 ship)? Or build into V1 from day 1? **My read: Phase-1 deliverable. V1 doesn't need to be indistinguishable; it needs to ship.**
4. **Pyodide in V1 yes/no?** Without it, the agent can't emit Python — but its prior is Python-strong. With it, +150 MB pod baseline and another wasm runtime to maintain. **My read: V1 ships JS-only; Phase-1 lights up Pyodide once we have a measured demand signal from training trajectories.** Confirm.
5. **GraalVM Web Image as a V2 bet on Datahike-in-browser-as-WASM.** Real, experimental, no proof-point yet. Worth a 2-day spike to compile a trivial Datahike memory store via Web Image and see if it executes? Reasonable Phase-2 exploration; do not bet V1 on it.
6. **Native Messaging companion vs Tauri.** Tauri 2.x has a capability-based permission model (`src-tauri/capabilities/*.json`) and a Sidecar pattern for subprocess that's structurally cleaner than Electron's. **Trade-off: Tauri uses the system webview (WebKit on macOS, WebView2 on Windows), which means feature-parity gaps the Electron-Chromium model doesn't have.** Worth a serious look in Phase-1.5 if Electron's installer size or attack surface becomes a real concern.
7. **`:keep-history? true` privacy semantics.** Default tombstone-on-retract, explicit purge for user-asked deletion. Confirms with the [memory-architecture decision](2026-05-07-memory-architecture.md). Where is the per-user "delete everything you know about me" UI surface? — UX-side question, but the storage layer needs to support it.
8. **The 90/10 number itself** — at the marketing level, do we say 90% or be more conservative? **Honest read: Electron pod is ~90% mediated by capability count, but by *runtime cycles* it's higher (most agent work is `db.q` + `define` + LLM calls — all mediated). By *shocked-engineer audit*, it's lower (subprocess primitive is a big footgun even named).** Pick the framing that's defensible under scrutiny — I'd say "the agent's runtime executes inside a WASM permission boundary; every cross-boundary call is an explicit, named capability the host wires."

---

## 11. Sources

### Primary (verified by direct WebFetch this session)

- MCP Streamable HTTP transport spec (2025-06-18) — https://modelcontextprotocol.io/specification/2025-06-18/basic/transports — replaces 2024-11-05 HTTP+SSE; mandates `Origin` header validation; supports stdio + Streamable HTTP.
- Chrome Native Messaging docs — https://developer.chrome.com/docs/extensions/develop/concepts/native-messaging — 1 MB host→Chrome / 64 MiB Chrome→host limit; manifest in OS-specific dirs; `allowed_origins` no wildcards; native binary unsandboxed by Chrome.
- WICG Direct Sockets — https://wicg.github.io/direct-sockets/ — `[IsolatedContext]` + `[SecureContext]`; default Permissions Policy `'none'`; TCPSocket / UDPSocket / TCPServerSocket.
- konserve repo — https://github.com/replikativ/konserve — IndexedDB is the only published browser backend; external backends list (s3 / dynamodb / redis / lmdb / rocksdb / jdbc / gcs).
- konserve-opfs **does not exist** — verified 404 at `github.com/replikativ/konserve-opfs` this session. Gemini-Flash hallucination.
- isolated-vm — https://github.com/laverdet/isolated-vm — v6.0.2 (2025-10-16); maintenance mode; Node 16+, Node 20+ requires `--no-node-snapshot`.
- Cloudflare workerd — https://github.com/cloudflare/workerd — Apache-2.0; embeddable; **explicitly "not a hardened sandbox"** per Cloudflare's own README.
- GraalVM Web Image — https://github.com/oracle/graal/tree/master/web-image — experimental backend; `--tool:svm-wasm` flag; HelloWorld.js + HelloWorld.js.wasm; runs on Node 22+ (or Node 25+ flag-free); WebAssembly 3.0 with Wasm-GC + exception handling + typed function references.
- GraalVM WebAssembly (GraalWasm) — https://www.graalvm.org/webassembly/docs/ — runs WASM **inside** GraalVM (polyglot target); does NOT compile JVM/Clojure code TO WASM. Web Image is the separate wasm-output target.
- File System Access API — https://developer.chrome.com/docs/capabilities/web-apis/file-system-access — Chromium primary; Firefox 111 + Safari 15.2 added OPFS only, not picker FSAA. Write permission separate from read. (Browser-support claims in the Gemini outputs conflate FSAA with OPFS — this session corrects.)

### Secondary — Gemini surveys (cross-checked, UNVERIFIED retained)

- **Gemini-3-Pro Q1** (browser/Electron capability surface) — `/tmp/.../bjzomcin8.output`, completed this session. Subprocess-from-browser bottom line + MCP-CORS pain + WASI-in-browser networking neutered + GraalVM-wasm32 hype check + konserve backends. Useful synthesis; specific technical claims (jco P3 status, IWA Direct Sockets binding) treated as UNVERIFIED orientation.
- **Gemini-3-Pro Q2** (Component Model + Pyodide + threading + competitors) — `/tmp/.../blmexnc4f.output`. WASI 0.3 release Feb 2026, Component Model composition via `wac`, Pyodide 0.27+ memory profile, multi-threaded Pyodide via PEP 703, competitor sandbox models. Competitor specifics not independently verified — treat as orientation.
- **Gemini-3-Flash** (Tauri + konserve + Pyodide-Electron + isolated-vm + workerd + WebContainers) — `/tmp/.../btgi5lrf3.output`. **One verified hallucination: claimed `github.com/replikativ/konserve-opfs` exists — it does not.** Other claims (Tauri capability files, WebContainers commercial license, App-Bound Encryption Chromium 127+) treated as orientation; load-bearing claims would need primary verification.

### Internal cross-refs

- [`2026-05-08-wasm-wasi-substrate.md`](2026-05-08-wasm-wasi-substrate.md) — recommendation to use `quickjs-emscripten` over Wasmtime+rquickjs for Phase-0; this doc preserves that and extends to the dual-shell pod design.
- [`2026-05-08-verifiers-jssandbox-integration.md`](2026-05-08-verifiers-jssandbox-integration.md) — Path A (Verifiers + Node sidecar over JSON-RPC stdio); the pod runtime uses the same boundary.
- [`2026-05-10-datahike-performance.md`](2026-05-10-datahike-performance.md) — `libdatahike.so` via N-API in Electron Main; `datahike-http-server` for V2 multi-tenant; Datalevin parked because the agent's privacy promise rides on retractability.
- [`2026-05-08-datahike-template-env.md`](2026-05-08-datahike-template-env.md) — Datascript inside QuickJS for the per-trajectory scratch; unchanged.
- [`2026-05-07-memory-architecture.md`](2026-05-07-memory-architecture.md) — sovereign memory + retractability as trust primitive.
- [`2026-05-07-separation-and-sandbox.md`](2026-05-07-separation-and-sandbox.md) — interposition vs adjacency; the pod design supports both — the `subprocess.spawn` + `mcp.client` capabilities are the steerage primitives.

### UNVERIFIED — flagged in-text

- Pyodide 0.27+ memory specifics (5 MiB dynamic, 60–80 MB NumPy, 150–300 MB idle in Electron).
- WASI 0.3 release date (Feb 2026, Gemini cite, not directly verified).
- All competitor sandbox-model claims (Cursor Seatbelt profile, Cline DevContainer recommendation, Smol Agents WasmExecutor, Goose Wasmtime/Wassette MCP servers).
- Tauri 2.x capability-files specific structure.
- App-Bound Encryption Chromium 127+ specifics.
- `datahike-http-server` HTTP/2 keep-alive latency budget (~1–5 ms p50).
- Chrome Native Messaging behavior since 2023-02 (last Chrome docs update).

---

## 12. What changed our understanding

1. **The dual-shell pod is the right framing.** Sean's pod question reframes "browser vs Electron" from a deployment choice into a capability-binding choice. Same pod runtime, different bindings — and the bindings differ predictably (subprocess available in Electron, not in browser; libdatahike-FFI in Electron, datahike-server in browser). The agent code is identical. **This makes "training pod = production pod" structurally true and gives the agent a defensible privacy/security pitch competitors can't mirror without rebuilding.**
2. **The 90% claim survives for Electron, weakens for browser.** The honest framing is *capability-discipline*, not *mathematical isolation*. Electron pod has every capability mediated by named imports; browser pod is structurally degraded on subprocess + native FS + native libs. **Pure-browser the agent is not a feature-equivalent product.**
3. **The 10% list is shorter than I feared.** Subprocess + stdio MCP + native libs + native FS-beyond-OPFS + a few exotic OS surfaces. None require the agent to leave WASM mediation philosophically; they require Electron-or-Native-Messaging to *implement* the host side of the capability primitives. The agent doesn't see the difference.
4. **Multi-language WASM co-tenancy is the strongest 90%-side win.** Pyodide + (eventually) wasm32-Rust/TinyGo all share the same permission boundary. **The competitor distinction holds: Smol Agents has Pyodide-only; nobody has the unified-mediation-across-languages story the agent can have.**
5. **GraalVM Web Image is real.** Not a hypothetical. Whether Datahike compiles through it is unknown and a Phase-2 bet, but the path from "Datahike on JVM" to "Datahike-as-wasm32" exists in tools that ship today. **This re-opens the V2 question of whether the durable EAVT graph could live INSIDE the same WASM permission boundary instead of as a host-mediated capability.** Don't bet on it for V1; track it.
6. **Side-channel discipline is the load-bearing engineering work for the dual-purpose claim.** Without it, the agent learns to detect mode and the training-equals-production claim collapses. **This is a Phase-1 deliverable, not a free property.** Sean should hold the line on this — competitors who ship "training-on-the-same-runtime" without addressing it are claiming more than they ship.
7. **konserve-opfs does not exist.** Gemini-Flash hallucinated. If we want OPFS in the browser pod, we write a custom adapter (~150 LOC). Not blocking, just real work.
