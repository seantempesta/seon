---
type: research
status: active
tags: [research, pod, wasm, mcp, capability]
---

# Capability surface for the WASM agent pod

Source-grounded design for giving a wasm-contained CLJS agent pod three
external capabilities — **external MCP calls**, **outbound HTTPS**, and
**local filesystem access** — and the scoping/policy layer that decides
what each agent gets.

This doc is the input to Phase 5/6/7 of
[[../platform]]. It is NOT a status doc; it's a map of where the wires
go.

## TL;DR

- **HTTPS is already half-built.** The eval-smoke WASM imports
  `wasi:http/types@0.2.x` via wasm-rquickjs's `node-http` feature
  (`/Users/sean/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/wasm-rquickjs-0.2.4/skeleton/src/builtin/node_http.rs:5`).
  The Tauri host already wires `wasmtime_wasi_http::p2::add_only_http_to_linker_async`
  (`/Users/sean/src/seon/pod-host/wasm-tauri/src-tauri/src/pod.rs:292`)
  and has an `HttpAllowlist` stub
  (`/Users/sean/src/seon/pod-host/wasm-tauri/src-tauri/src/http.rs:22`).
  What's missing is the URL-filtering hook (override
  `WasiHttpView::send_request`) and the CLJS-side `fetch`-or-`node:https` wrapper.
- **Filesystem is already in the build.** Both
  `wasi:filesystem/preopens@0.2.3` and `wasi:filesystem/types@0.2.3`
  are imported by every wasm-rquickjs component
  (`/Users/sean/src/seon/pod-host/wasm-tauri/eval-smoke-build/src/modules/mod.rs:21-22`).
  The Tauri host already accepts `(host_path, guest_path)` preopen pairs
  (`pod.rs:259-266`). The `seon:pod/fs` interface in `seon-pod.wit:77-92`
  is a SECOND host-mediated path for paths outside preopens — useful for
  prompt-on-demand grants, but not needed for the common case.
- **External MCP requires a host bridge.** The pod cannot spawn
  processes — `child_process` is a no-op stub in wasm-rquickjs
  (`/Users/sean/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/wasm-rquickjs-0.2.4/skeleton/src/builtin/child_process.js:1-8`)
  and the wasi-preview-2 surface has no `wasi:process`. The host has to
  own MCP server lifecycles and expose a WIT interface the pod calls.
  The drafted `seon:pod/mcp` interface in `seon-pod.wit:94-113` is
  *close* but needs corrections (see §3).
- **wasm-rquickjs does NOT support async WIT imports.** Confirmed at
  `/Users/sean/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/wasm-rquickjs-0.2.4/src/imports.rs:240-244` —
  `Err(anyhow!("Async imported functions are not supported yet"))`.
  This is the load-bearing constraint shaping every host bridge: from
  CLJS's perspective every WIT-imported call is synchronous. The host
  can still do async work (wasmtime's async store runs them on tokio)
  — but the guest sees a blocking call. For long-running operations
  (HTTP fetch, MCP request) we need either (a) the wstd/wasi-http
  pattern that yields under the hood while looking sync to JS, or (b)
  a two-call pattern (`mcp.send_async() -> id; mcp.poll(id) -> result`).
- **Capability scoping fits cleanly into the existing `PodBuilder`.**
  `with_http_allow_host` and `with_preopen_dir` are already the shape
  (`pod.rs:259-271`); we extend with `with_mcp_server(name, command,
  args)` and `with_capability_prompt_handler(fn)` for runtime grants.

---

## Current substrate baseline

Build script: `/Users/sean/src/seon/pod-host/wasm-tauri/build-eval-smoke`.

### Cargo features in the compiled wasm

`build-eval-smoke:87-89` invokes cargo with:

```
--no-default-features --features="lite,node-http,crypto,zlib,encoding"

```

From the wasm-rquickjs skeleton manifest
(`/Users/sean/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/wasm-rquickjs-0.2.4/skeleton/Cargo.toml_:9-21`):

- `lite = ["fetch"]` — `fetch` brings in `golem-wasi-http` (Cargo.toml_:36),
  which the `http` builtin uses for `fetch()` and `Request`/`Response`
  (`http.rs:8-17`). This adds `wasi:http/types` + `wasi:io/streams`
  imports to the wasm.
- `node-http` — gates the `node_http` builtin
  (`builtin/mod.rs:43-44`). Provides `import 'node:http'`, which is
  what CLJS uses today via `(:require [cljs.js])` indirectly (and is
  what's wired up for the in-pod loopback server per `seon-pod.wit:173`).
  `node_http.rs:5` imports `wasip2::http::types as wasi_http` and uses
  `outgoing_handler::handle` (line in `node_http.rs:200+`).
- `crypto`, `zlib`, `encoding` — pure-Rust algos, no extra WASI imports.
- `lite` is deliberately MINUS `logging` (which would require a
  `wasi:logging/logging` host impl that stock wasmtime CLI lacks) —
  see `m2-findings-2026-05-21.md` landmine #6.

The `wasip2` crate version is `1.0.2+wasi-0.2.9`
(`/Users/sean/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/wasip2-1.0.2+wasi-0.2.9/Cargo.toml:16`).
The COMPONENT'S declared imports are 0.2.3 because the WIT vendored at
`pod-host/wasm-tauri/src-wit-eval-smoke/deps/*/[…].wit` is 0.2.3
(verified by `grep -n "package wasi"` over those files: all return
`wasi:…@0.2.3`). WASI promises SemVer compat across 0.2.x — wasmtime
42's wasi:http is `0.2.6` per
`/Users/sean/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/wasmtime-wasi-http-42.0.1/wit/*.wit` —
and minor mismatch is OK at link time. This is a latent landmine if
either side bumps to 0.3 without coordination.

### WIT imports actually compiled into the eval-smoke component

From the generated wrapper crate
`/Users/sean/src/seon/pod-host/wasm-tauri/eval-smoke-build/src/modules/mod.rs:12-24`:

```
wasi:io/poll@0.2.3
wasi:clocks/monotonic-clock@0.2.3
wasi:clocks/wall-clock@0.2.3
wasi:io/error@0.2.3
wasi:io/streams@0.2.3
wasi:filesystem/types@0.2.3
wasi:filesystem/preopens@0.2.3
wasi:random/random@0.2.3

```

Plus the `wasi:http` imports that the `node-http` feature pulls in via
`wasip2` regardless of WIT (NOT visible in the wrapper-crate-generated
modules table — they come from `wit-bindgen-rt` re-exports inside the
`fetch` / `node-http` Rust code itself).

### How a WIT import becomes a JS-callable symbol

Critical mechanism, found at
`/Users/sean/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/wasm-rquickjs-0.2.4/src/imports.rs:104-114`:
each imported WIT interface becomes a NAMED ESM MODULE in QuickJS. The
JS module name is the fully-qualified WIT interface id, e.g.
`"wasi:filesystem/preopens@0.2.3"`. Functions inside the interface
become camelCase ESM exports —
`/Users/sean/src/seon/pod-host/wasm-tauri/eval-smoke-build/src/modules/wasi_filesystem_0_2_3_preopens.rs:21-34`:

```rust
impl rquickjs::module::ModuleDef for JsPreopensModule {
    fn declare(decl: &rquickjs::module::Declarations) -> rquickjs::Result<()> {
        decl.declare("getDirectories")?;

```

So **the way CLJS will call a custom host import is `import { getDirectories } from "wasi:filesystem/preopens@0.2.3"`** (or any equivalent QuickJS-resolvable form). For our `seon:pod/mcp` interface that means CLJS would do:

```javascript
import { send } from "seon:pod/mcp@0.1.0";  // not quite — see §3, mcp uses resources

```

### Async imports are NOT supported

`/Users/sean/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/wasm-rquickjs-0.2.4/src/imports.rs:240-244`:

```rust
FunctionKind::AsyncFreestanding
| FunctionKind::AsyncMethod(_)
| FunctionKind::AsyncStatic(_) => {
    Err(anyhow!("Async imported functions are not supported yet"))?
}

```

This means every WIT import must be `func(...) -> result<T, E>` (sync), even though the host's underlying execution can be tokio-async via wasmtime's async store. Workarounds:

1. **The wstd / wasi-io pattern.** WASI's `outgoing-handler::handle` returns a `future-incoming-response` resource that the guest polls via `wasi:io/poll`. This is what `node_http.rs` does
   (`/Users/sean/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/wasm-rquickjs-0.2.4/skeleton/src/builtin/node_http.rs:30-49`)
   — `RequestState::Started { future_response: wasi_http::FutureIncomingResponse, ... }`. The Rust side runs `wstd`'s block_on, which cooperates with QuickJS's microtask loop. Net effect: JS sees a Promise that resolves.
2. **Two-call pattern.** `send_async() -> request-id; poll(request-id) -> option<result>`. Simpler to model, but the JS side has to write its own polling loop.

The wstd pattern is what makes fetch work today and is the right model for MCP.

### Existing Tauri host wiring (the foundation)

`/Users/sean/src/seon/pod-host/wasm-tauri/src-tauri/src/pod.rs`:

- `bindgen!` at `pod.rs:43-55` generates Rust async traits for every
  WIT import on `SeonStore`, delegating wasi:* to wasmtime's bindings.
- `SeonStore` (line 73) holds `WasiCtx`, `WasiHttpCtx`, `ResourceTable`,
  and an `HttpAllowlist`.
- `impl fs::Host for SeonStore` at `pod.rs:145-165` is a **stub** —
  every method returns `FsError::Io("not wired (B-5)".into())`.
- `impl mcp::HostHandle for SeonStore` at `pod.rs:171-193` is **stub** —
  `send` returns `McpError::Closed`.
- `impl capability_prompt::Host` at `pod.rs:201-208` returns
  `Decision::Deny` for every prompt.
- The linker at `pod.rs:288-299` wires all five interface families:
  `wasmtime_wasi::p2::add_to_linker_async`,
  `wasmtime_wasi_http::p2::add_only_http_to_linker_async`,
  `types::add_to_linker`, `fs::add_to_linker`, `mcp::add_to_linker`,
  `capability_prompt::add_to_linker`, `wasi_logging::add_to_linker`.

So the END-STATE wiring is there. The bodies of the host impls need to
be filled in (B-5 = fs, B-6 = mcp) and the WIT interfaces themselves
need corrections (§3.E).

---

## Capability #1: External MCP calls

### (A) Required WASI/WIT capabilities

There is **no** WASI capability for process spawning in 0.2.x. The
`wasi:process` proposal exists upstream but is not in the preview-2
release. Therefore:

- **No `wasi:process/*` imports needed** — would be wrong anyway, the
  spawn happens on the host.
- **Custom WIT interface required.** Today drafted at
  `seon-pod.wit:94-113` as `seon:pod/mcp`:

  ```wit
  interface mcp {
    variant mcp-error { spawn-failed(string), closed, io(string) }
    record mcp-spec {
      name: string, command: string, args: list<string>,
      env: list<tuple<string, string>>,
    }
    resource handle {
      constructor(spec: mcp-spec);
      send:  func(request: string) -> result<string, mcp-error>;
      close: func();
    }
  }

  ```

  This says "the guest constructs an mcp handle by passing a full
  spawn spec, then calls send/close on it." See (E) for why that's
  wrong.

- **Confirmed: no host-side spawn from inside the wasm.**
  `child_process.js:14-18` defines `createNotSupportedError` —
  every method that would shell out throws `ENOSYS`.

### (B) What's in the build today vs what to add

- **In:** The `seon:pod/mcp` interface is DECLARED in WIT
  (`seon-pod.wit:186`), the host has stub bindings
  (`pod.rs:169-198`), the linker imports it (`pod.rs:297`). What's NOT
  in: any real process management, any wasm-side smoke. The `eval-smoke`
  world (`eval-smoke.wit`) does NOT include the mcp interface — it's
  in the `seon-pod` world only.
- **Add:**
  1. A proper host MCP-server-lifecycle manager (spawns the stdio
     child, keeps stdin/stdout pumps).
  2. A reworked WIT interface that REGISTERS servers up-front
     (host-policy decision, not guest-policy) and lets the guest
     reference them by name only.
  3. CLJS-side wrapper namespace `seon.mcp` that imports the WIT
     module and emits the JSON-RPC envelope.

### (C) End-to-end call path

```
[CLJS agent code]
  (seon.mcp/call {:server "gh" :tool "list_issues" :args {...}})
   │ — pure CLJS, builds JSON-RPC envelope
   ▼
[seon.mcp namespace]
  (let [req-edn   {:jsonrpc "2.0" :id (random-uuid) :method "tools/call" ...}
        req-json  (.stringify js/JSON (clj->js req-edn))
        resp-json (.send mcp-mod "gh" req-json)]   ; sync — see §3.A
    (-> resp-json (js/JSON.parse) (js->clj)))
   │ — calls into the JS module imported from WIT
   ▼
[QuickJS resolved module "seon:pod/mcp@0.1.0"]
  export function send(server, request) → ...
   │ — generated by wasm-rquickjs imports.rs:104-114
   ▼
[Wasm component import: seon:pod/mcp.send]
   │ — call crosses the wasm/host boundary
   ▼
[Tauri host: impl mcp::Host for SeonStore]
  async fn send(&mut self, server: String, request: String) -> ...
   ├── lookup server in self.mcp_registry (HashMap<String, McpProc>)
   ├── write request + "\n" to McpProc.stdin
   ├── async read next \n-delimited line from McpProc.stdout
   └── return Ok(line)
   │ — wstd block_on bridges async-to-sync for the wasm side
   ▼
[Stdio child process: e.g. `npx @modelcontextprotocol/server-github`]
  reads JSON-RPC on stdin, writes JSON-RPC on stdout

```

### (D) Capability scoping

Today: host stub returns `Decision::Deny` for `spawn-mcp` prompts
(`pod.rs:201-208`). After B-6:

- **Registry-based.** The `PodBuilder` (`pod.rs:240`) gains
  `with_mcp_server(name, McpSpec { command, args, env })`. Only
  registered servers can be `send`ed-to. Spec lists which MCPs the
  user (host operator) has approved — agent picks BY NAME.
- **Prompt fallback (optional).** For dynamically-discovered MCPs
  the agent can use `seon:pod/capability-prompt::ask(spawn-mcp(...))`
  to request a runtime grant. Host shows the user the command +
  args; on `Allow` the spec is added to the registry, on `Deny` the
  follow-up `mcp::send` fails with a clear error.

### (E) Open problems and required corrections to `seon-pod.wit`

1. **The `mcp::Handle` resource constructor is wrong direction.**
   `seon-pod.wit:108-112` lets the GUEST pass a full spawn spec
   (`command`, `args`, `env`). That gives the guest ambient authority
   to start arbitrary processes — exactly what the capability model
   forbids. The HOST should own the registry. Proposed correction:

   ```wit
   interface mcp {
     variant mcp-error {
       no-such-server(string),     // server not in host registry
       not-allowed,                 // capability-prompt denied
       io(string),
       protocol-error(string),
     }

     // No resource — servers are global, named, host-managed.
     // Sync from CLJS's perspective; host runs the stdio pump on tokio.
     send: func(server-name: string, request-json: string)
       -> result<string, mcp-error>;

     // Optional: enumerate servers the host has admitted.
     list-servers: func() -> list<string>;
   }

   ```

   Drop the `resource handle` (the guest doesn't need lifecycle —
   the host owns it). Drop `spawn-mcp` from `capability-prompt`
   (`seon-pod.wit:131`) IF we keep the registry strict; or keep it
   for ad-hoc runtime grants where the guest proposes a server
   spec and the host's prompt UI decides.

2. **Sync call shape requires careful blocking.** `mcp::send` per
   imports.rs:240-244 cannot be `async func`. The host body returns
   immediately after writing-and-blocking-read; under wasmtime's
   async store with wstd it cooperatively yields without burning
   a thread. Confirmed pattern: that's exactly what wasi-http does
   for outgoing requests (no async func declared, yet end-to-end
   it's tokio under the hood).

3. **Streaming MCP responses (server→client notifications) need
   a different shape** — MCP servers can send unsolicited
   notifications over stdout. The current `send -> result<string>`
   only covers request/response. For V0.5 we ignore notifications
   (drop them in the host). V0.6 could add a `poll-notification:
   func(server-name) -> option<string>` that returns the head of a
   per-server notification queue.

4. **Concurrency.** Two agents in the same pod calling `send` to the
   same server should be serialized at the host (writes to a single
   stdin must not interleave). The host uses one tokio `Mutex` per
   `McpProc`. Documented; not enforced by WIT shape.

---

## Capability #2: Outbound HTTPS

### (A) Required WASI/WIT capabilities

- `wasi:http/types@0.2.x` — resource definitions for `outgoing-request`,
  `incoming-response`, `fields`, `outgoing-body`, etc. Generated by
  wit-bindgen from
  `pod-host/wasm-tauri/src-wit/deps/http/types.wit`.
- `wasi:http/outgoing-handler@0.2.x` — `handle(request, options) ->
  result<future-incoming-response, error-code>`. The host services
  this. Referenced in
  `/Users/sean/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/wasm-rquickjs-0.2.4/skeleton/src/builtin/node_http.rs:7`
  via `use wasip2::http::outgoing_handler`.
- `wasi:io/streams@0.2.x` — for body chunks (write request body, read
  response body).
- `wasi:io/poll@0.2.x` — for waiting on `future-incoming-response`.

### (B) What's in the build today vs what to add

**Already in:**

- `node-http` Cargo feature pulls `wasip2::http::*` into the compiled
  wasm
  (`skeleton/src/builtin/node_http.rs:5,7`).
- `fetch` Cargo feature (transitively via `lite`) provides a global
  `fetch()` JS function backed by `golem-wasi-http`
  (`skeleton/src/builtin/http.rs:8-13`).
- Tauri host wires `wasmtime_wasi_http::p2::add_only_http_to_linker_async`
  at `pod.rs:292`.
- Tauri host has `HttpAllowlist` struct
  (`http.rs:21-40`) and `with_http_allow_host` builder method
  (`pod.rs:268-271`).
- Wasmtime CLI gates the same surface behind `-S http=y`
  (`build-eval-smoke:110`).

**Missing:**

1. **The URL-filtering hook.** Today `SeonStore`'s
   `impl WasiHttpView` (`pod.rs:123-131`) inherits the default
   `send_request` from
   `/Users/sean/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/wasmtime-wasi-http-42.0.1/src/types.rs:150-156` —
   which dispatches every URL unfiltered. We need an override:

   ```rust
   fn send_request(
       &mut self,
       request: hyper::Request<HyperOutgoingBody>,
       config: OutgoingRequestConfig,
   ) -> HttpResult<HostFutureIncomingResponse> {
       let host = request.uri().host().unwrap_or("");
       if !self.allowed.lock().unwrap().contains(host) {
           return Err(types::ErrorCode::HttpRequestDenied.into());
       }
       Ok(default_send_request(request, config))
   }

   ```

   The trait method exists for exactly this purpose
   (`types.rs:148-156`).

2. **CLJS-side wrapper.** The simplest path is `(js/fetch url opts)` —
   the global is already wired in via the `fetch` Cargo feature. For
   Node-style streaming the agent can use `(js/require "node:https")`.
   No new WIT needed.

### (C) End-to-end call path

```
[CLJS agent]
  (seon.http/get "https://api.deepseek.com/v1/chat/completions" {...})
   │ — pure CLJS
   ▼
[seon.http]
  (-> (js/fetch url (clj->js opts))
      (.then (fn [resp] (.json resp)))
      (.then js->clj))
   │ — built-in fetch from `fetch` feature
   ▼
[wasm-rquickjs http.rs — fetch implementation]
  uses golem-wasi-http (built on wasip2::http::outgoing_handler)
   │
   ▼
[Wasm import: wasi:http/outgoing-handler@0.2.x::handle]
   │ — crosses wasm/host boundary
   ▼
[wasmtime-wasi-http host bindings]
  routes through WasiHttpView::send_request
   │
   ▼
[SeonStore::send_request override]
  ┌── self.allowed.contains(host) ? ───► default_send_request (hyper)
  └── else                              ► Err(HttpRequestDenied)

```

### (D) Capability scoping

Three layers:

1. **`-S http=y` (wasmtime CLI gate).** Binary on/off for the entire
   wasi-http import family. Set by `build-eval-smoke:110`. Required;
   not sufficient.
2. **`HttpAllowlist`** (`http.rs:21-40`). HashSet of bare hostnames.
   Seeded at boot via `PodBuilder::with_http_allow_host("api.deepseek.com")`
   (`pod.rs:268`); extended at runtime via the `capability-prompt`
   path: agent calls `seon:pod/capability-prompt::ask(fetch-url(...))`,
   on `Allow` the host adds the host string to its allowlist.
3. **Per-agent scoping.** Today `HttpAllowlist` is per-`SeonStore`,
   i.e. per-pod. For multi-tenant pods we'd want
   `HashMap<AgentId, HashSet<Host>>` — agent passes its own id with
   every fetch. Future work; not blocking.

Path normalization for hostname matching is trivial (exact-match hash
lookup). No glob/wildcard in V1; user grants individual hosts. Wildcard
support is a V2 nice-to-have but worth flagging because most agent
workflows would benefit (e.g. `*.github.com`).

### (E) Open problems and unknowns

1. **Version skew.** Our vendored WIT declares `wasi:http@0.2.3`
   (`src-wit/deps/http/proxy.wit:1`). wasmtime 42's wasi-http
   declares `0.2.6`
   (`/Users/sean/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/wasmtime-wasi-http-42.0.1/wit/proxy.wit:5`).
   wasm-rquickjs's internal wasip2 is `0.2.9`
   (`wasip2-1.0.2+wasi-0.2.9/Cargo.toml:16`). They link because
   WASI's component-model resolver treats 0.2.x as
   SemVer-compatible. The danger zone is the 0.2 → 0.3 jump (which
   reshapes wasi:http around the Preview 3 future-tier). Pin our
   vendored WIT to the 0.2.x latest that wasmtime ships at the
   time we land Phase 5.
2. **Headers / forbidden-header policy.** `wasmtime_wasi_http`
   enforces a default forbidden-header list
   (`types.rs:166-168`). Agent code calling `fetch` with
   `Authorization` may need explicit allow — investigate before
   shipping.
3. **TLS root certs.** wasi-http relies on the host's `rustls` /
   `webpki-roots`; works out of the box for public hosts. If the
   user needs to pin custom CAs (corp proxy), that's a host-side
   `Pod::with_tls_root_cert` builder method — not in scope for
   Phase 5 but worth a placeholder.
4. **Sync from CLJS.** Per
   `wasm-rquickjs-0.2.4/src/imports.rs:240-244`, async WIT imports
   are NOT supported. But `fetch` works! That's because fetch is
   NOT a WIT-import-generated symbol — it's a JS function provided
   by the http builtin (`http.rs` + `http.js`) that internally
   handles the future-incoming-response polling on the Rust side
   via `wstd::runtime::AsyncPollable`
   (`node_http.rs:11`). For any custom WIT-import-based capability
   we need the same wstd pattern, NOT a raw `async func` in WIT.

---

## Capability #3: Local filesystem access

### (A) Required WASI/WIT capabilities

- `wasi:filesystem/preopens@0.2.x` — `get-directories() -> list<tuple<descriptor, string>>`.
  Pre-mounted by the host at boot. Already imported by every
  wasm-rquickjs component
  (`eval-smoke-build/src/modules/mod.rs:22`).
- `wasi:filesystem/types@0.2.x` — `descriptor` resource +
  `open-at`, `read-via-stream`, `write-via-stream`,
  `set-times-at`, etc. Already imported
  (`mod.rs:21`).
- (Optional) `seon:pod/fs` — host-mediated path-string fs for paths
  OUTSIDE the preopen mounts. Drafted at `seon-pod.wit:77-92`.
  Useful for prompt-on-demand access without re-mounting; not strictly
  required for Phase 5.

### (B) What's in the build today vs what to add

**Already in:**

- WIT imports declared and compiled
  (`mod.rs:9-10`, `21-22`).
- Tauri host already accepts preopens
  (`pod.rs:259-266`). Calling
  `Pod::new(wasm).with_preopen_dir("/Users/sean/.seon/cache", "/cache").start_async()`
  is enough to expose `/cache` to the pod.
- wasmtime CLI form: `--dir /Users/sean/.seon/cache::cache`
  (matches the syntax in `build-eval-smoke:104` which mounts the
  bootstrap dir at guest path "bootstrap").
- CLJS-side filesystem access works today via `(js/require "node:fs")` —
  wasm-rquickjs's `fs` builtin
  (`skeleton/src/builtin/fs.rs:34-90`) routes Node-style calls
  through `wasip2::filesystem`. Tested by
  `wasm_eval_smoke.cljs:86-97` which reads `bootstrap/ana/*.transit.json`
  via `fs.readdirSync` + `fs.readFileSync`.

**Missing:**

1. **A canonical project layout for guest paths.** Today only
   `/bootstrap` is mounted (for analyzer caches). For Phase 5 we
   add (per `platform.md:339-343`):
   - `/cache` — writable, content-addressed package store
     (host: `~/.seon/cache`).
   - `/workspace` — writable, agent's source workspace (host:
     `~/seon-workspace/<agent-id>`).
   - `/source` — read-only mirror of project source files (host:
     `~/src/seon`). Letting the agent introspect its own
     substrate.
2. **A `seon.fs` CLJS namespace.** Wraps `node:fs/promises` with
   path-validation (only the allowed preopen paths). Like the
   existing `seon.fs` on the JVM side but for the pod
   (`platform.md:50-58` — "default-deny `seon.fs` allowlist").
3. **(Optional) `seon:pod/fs` host impl.** Today stubbed at
   `pod.rs:145-165`. Real impl would route to the user's
   home / arbitrary paths AFTER `capability-prompt`. Not needed for
   the common case where preopens cover the workspace.

### (C) End-to-end call path

Within a preopened mount, the path is **pure WASI** — no custom
WIT, no host bridging:

```
[CLJS agent]
  (seon.fs/read-file "/workspace/notes.md")
   │
   ▼
[seon.fs]
  (.. (js/require "node:fs/promises") (readFile "/workspace/notes.md" "utf8"))
   │
   ▼
[wasm-rquickjs fs.rs builtin]
  resolves path against wasip2::filesystem::preopens::get_directories()
   │ — same code path as fs.rs:90-120 (longest-prefix match)
   ▼
[Wasm import: wasi:filesystem/types::descriptor::open-at]
   │ — generated by wit-bindgen → wasmtime-wasi
   ▼
[wasmtime-wasi host]
  delegates to the OS via std::fs against the preopen's host_path

```

For paths OUTSIDE preopens, the path goes through `seon:pod/fs::read-file`
instead — host implementation checks `capability-prompt`, then either
opens the real path or returns `permission-denied`. Code path goes via
the WIT-imported JS module `"seon:pod/fs@0.1.0"` exactly as described
in §2.C for MCP.

### (D) Capability scoping

Layered:

1. **Preopen list.** What's mounted IS the filesystem the pod sees.
   Set at pod boot, immutable for the pod's lifetime. Equivalent
   of "you can read /workspace and /cache, nothing else exists."
2. **DirPerms / FilePerms.** wasmtime exposes per-preopen permission
   bits (`pod.rs:96-97` uses `DirPerms::all() | FilePerms::all()`).
   For Phase 5 we narrow:
   - `/source` → `DirPerms::READ | FilePerms::READ` (read-only)
   - `/cache` → `DirPerms::all() | FilePerms::all()` (writable)
   - `/workspace` → `DirPerms::all() | FilePerms::all()` (writable)
3. **`seon:pod/fs` for ad-hoc paths.** Host impl prompts via
   `capability-prompt::ask(read-file(...))`, on `Allow` opens the
   real path, on `Deny` returns `FsError::PermissionDenied`. This
   is the escape hatch for "agent wants to read
   `~/Documents/foo.pdf`" without re-mounting.

### (E) Open problems and unknowns

1. **Symlinks across mount boundaries.** wasi:filesystem treats
   symlinks as data (returns the target string). The Node fs shim
   tries to dereference them
   (`fs.rs:177-178` maintains an `EMULATED_SYMLINKS` table).
   If a preopen contains a symlink to OUTSIDE the preopen, the
   open call fails — but in a way that may confuse agents. Document
   in `seon.fs`.
2. **No `fs.watch`.** wasi:filesystem has no notify/inotify
   primitive. Agents that want to "watch for changes" must poll.
   For agent dev loops this is fine; for live editors it's a gap.
   Phase 7+ concern.
3. **Per-agent cache scoping.** The `~/.seon/cache` shared model
   (`platform.md:348-350` decision pending) means one agent's
   evicted CLJS dep can affect another agent's compile state.
   Content-addressed paths mitigate but don't fully solve. Open
   product decision.
4. **The default preopen is `/bootstrap` (read-only by virtue of
   our intent — but `DirPerms::all()` is currently granted at
   `pod.rs:96`).** Tighten before Phase 7.

---

## Cross-cutting: capability scoping and host policy

### Where each grant lives

| Capability | Host config knob | Runtime escalation path |
|---|---|---|
| HTTPS host | `PodBuilder::with_http_allow_host(host)` (`pod.rs:268`) | `capability-prompt::ask(fetch-url(...))` |
| Preopen directory | `PodBuilder::with_preopen_dir(host_path, guest_path)` (`pod.rs:259`) | None — preopens are immutable |
| Out-of-preopen path | NEW `PodBuilder::with_fs_allow_path(host_path)` | `capability-prompt::ask(read-file(...))` |
| MCP server | NEW `PodBuilder::with_mcp_server(name, spec)` | `capability-prompt::ask(spawn-mcp(...))` (optional) |
| Logging | Inherent — `wasi:logging/logging` host impl at `pod.rs:214-224` echoes to stderr | None |

### The `capability-prompt` interface

`seon-pod.wit:115-148` — already drafted. Variants
(`request::read-file`, `write-file`, `list-dir`, `fetch-url`,
`spawn-mcp`, `network-host`) cover the cases we care about.
`Decision::allow | deny | allow-remember` — V0.5 only honors
`allow|deny` per the WIT comment at `seon-pod.wit:119`.

The threat model comment at `seon-pod.wit:121-125` is correct: this
is **information**, not a security boundary against an adversarial
agent. wasmtime's WIT type system IS the boundary — the agent
literally cannot reach beyond the imports. The prompt's job is to
let the user (the agent's principal) understand what's about to
happen.

### Lifted dogfooding mode

`platform.md:293` and `pod.rs:230-238`'s builder docstring reference
"lifted mode" — a dev-time bypass where the allowlists are
effectively `*`. Implementation suggestion: a single boolean on
`SeonStore` (`lifted: bool`) checked by every host method before
consulting the per-capability allowlist. Off by default; on via
`PodBuilder::with_lifted_mode()` and probably an env-var
`SEON_POD_LIFTED=1` so a typo can't escape into production.

---

## Proposed phased rollout

Order matters because each phase builds on the last.

1. **Phase A: HTTPS allowlist override + smoke.** ~1 day.
   - Implement `SeonStore::send_request` override per §2.B.1.
   - Add unit test: allowlist `{"httpbin.org"}`, fetch
     `httpbin.org/get` succeeds, fetch `example.com` fails with
     `HttpRequestDenied`.
   - Smallest, highest-leverage; unblocks Phase 5 dep install.
2. **Phase B: Preopen layout + `seon.fs` CLJS wrapper.** ~1 day.
   - Define the three preopens (`/workspace`, `/cache`,
     `/source`). Tighten `DirPerms` per directory.
   - CLJS namespace `seon.fs` that wraps `node:fs/promises` with
     path-validation. (Same shape as the JVM `seon.fs` — the
     allowlist mirrors the preopen list.)
   - Tests: writes to `/cache` succeed, writes to `/source` fail.
3. **Phase C: MCP host bridge — registry-based.** ~3 days.
   - REVISE `seon-pod.wit::mcp` per §3.E.1: drop the resource
     constructor, add `send(server-name, request) -> result<string, mcp-error>`.
   - Host impl: spawn stdio child at registry-add time, keep
     `tokio::sync::Mutex<McpProc>` per server with stdin/stdout
     channels. `send` writes a line + reads a line.
   - `PodBuilder::with_mcp_server` extends the registry.
   - Smoke: register a tiny "echo" MCP test fixture, call
     `seon.mcp/call`, assert round-trip.
4. **Phase D: Ad-hoc fs grants via `seon:pod/fs` + `capability-prompt`.** ~2 days.
   - Replace the stub host impls
     (`pod.rs:145-165`, `201-208`) with real ones that consult an
     allowlist or call the prompt handler.
   - `PodBuilder::with_capability_prompt_handler(fn)` for the
     host to install a UI (Tauri dialog, terminal y/n, etc.).
5. **Phase E: Lifted mode (dev only) + Tauri UI for prompts.** ~2 days.
   - Bypass switch for development.
   - Tauri dialog for prod prompts.

After A and B the agent can call `npmjs.org` + write to `/cache` —
enough to land Phase 5 of `platform.md` (dynamic CLJS deps). MCP
(C) and ad-hoc fs (D) come after, in parallel with Phase 6.

---

## Open questions for the human

1. **MCP discovery model.** When the agent wants a new MCP server,
   does it (a) ask the host to spawn an arbitrary command via
   `capability-prompt`, (b) only pick from a host-curated registry
   the user added during Tauri setup, or (c) both? Recommendation:
   (b) for V0.5, add (a) in V0.6. The Claude Desktop precedent is
   (b)-only.
2. **HTTPS wildcard support.** Agent says `(seon.http/allow-host
   "github.com")` — does that include subdomains? Today's exact-match
   says no. GitHub's API actually uses `api.github.com`, so even
   `github.com` exact-match wouldn't help. Probably need
   suffix-match: `*.github.com` matches `api.github.com` AND
   `raw.githubusercontent.com`? Latter is a different apex domain
   — so even suffix-match isn't enough. Probably want a coarse
   "allow github API" preset that grants several hosts at once.
3. **`/source` mount: which directory?** Reading the pod's OWN
   source (`~/src/seon`) feels right for development, but in a
   shipped product the seon source isn't on disk in a known
   location — it's bundled into the wasm. Two options: (a) ship
   the source as a virtual filesystem inside the wasm (like
   `--js-modules` does for the bootstrap), (b) make `/source`
   optional / dev-only. Recommend (b).
4. **Capability persistence across pod restarts.** The
   `allow-remember` variant
   (`seon-pod.wit:119`) — does that grant survive Tauri restart?
   Probably yes (write to `~/.seon/capabilities.edn`), but that's
   one more file in the policy surface to audit.
5. **MCP server output buffer size limits.** Some MCPs return
   multi-MB responses (the GitHub server can dump entire diffs).
   Without a cap the host's stdout-read can balloon. Default 16MB
   per response? Configurable per server?

---

## Reference

- Roadmap that this fills in: [[../platform]] (especially the
  Capability Gates table at lines 327-344 and Phases 5/6/7).
- M2 landmines you'll hit if you ignore the build flags: [[m2-findings-2026-05-21]].
- Drafted WIT world: `pod-host/wasm-tauri/src-wit/seon-pod.wit`.
- The eval-smoke WIT world that ships today: `pod-host/wasm-tauri/src-wit-eval-smoke/eval-smoke.wit`.
- Tauri host scaffold: `pod-host/wasm-tauri/src-tauri/src/pod.rs`.
- Build orchestrators: `pod-host/wasm-tauri/build-eval-smoke`, `pod-host/wasm-tauri/build-pod`.
- wasm-rquickjs upstream: `~/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/wasm-rquickjs-0.2.4/`.
- wasmtime-wasi-http: `~/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/wasmtime-wasi-http-42.0.1/`.
