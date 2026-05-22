// pod.rs — wasmtime lifecycle + host capability impls for the seon-pod
// world (spec-05 §7.4 + §7.5).
//
// What's here in B-3:
//   - `bindgen!` invocation that maps the WIT world onto Rust async traits,
//     with `wasi:*` resources delegated to wasmtime-wasi's existing bindings.
//   - `SeonStore` (the wasmtime Store data) wiring WasiView + WasiHttpView.
//   - Stub host impls for `seon:pod/{fs, mcp, capability-prompt}` and
//     `wasi:logging/logging` — every method returns an error or empty value.
//     The shapes compile and link; real impls land in B-4 (capability),
//     B-5 (fs), and B-6 (mcp).
//   - `Pod` — the public type the rest of the host (Tauri, the MCP bridge,
//     unit tests) holds. Builder pattern matches spec §7.2.
//   - A `#[tokio::test]` that instantiates pod-build/.../seon_pod.wasm and
//     calls `get-ui-port` (B-3 green criterion).

use std::path::{Path, PathBuf};

use wasmtime::component::{Component, HasSelf, Linker, Resource, ResourceTable};
use wasmtime::{Config, Engine, Store};
use wasmtime_wasi::{WasiCtx, WasiCtxBuilder, WasiCtxView, WasiView};
use wasmtime_wasi_http::p2::bindings::http::types::ErrorCode;
use wasmtime_wasi_http::p2::body::HyperOutgoingBody;
use wasmtime_wasi_http::p2::types::{HostFutureIncomingResponse, OutgoingRequestConfig};
use wasmtime_wasi_http::p2::{
    default_send_request, HttpResult, WasiHttpCtxView, WasiHttpHooks, WasiHttpView,
};
use wasmtime_wasi_http::WasiHttpCtx;

use crate::http::HttpAllowlist;

// ---------------------------------------------------------------------------
// Generated bindings — see src-wit/seon-pod.wit for the source contract.
//
// `with:` delegates each `wasi:*` interface to wasmtime-wasi's existing
// generated bindings so we don't redefine `Descriptor`, `OutgoingRequest`,
// etc. Our custom interfaces (`seon:pod/{fs,mcp,capability-prompt,types}`)
// and `wasi:logging/logging` are generated fresh and impl'd below on
// `SeonStore`.
//
// `imports: { default: async | trappable }` — every import host fn becomes
// an async trait method that returns `wasmtime::Result<...>` (so a panic
// surfaces as a trap, not a hang).
// `exports: { default: async }` — `call_get_ui_port` and friends are async
// methods we can `.await`.
// ---------------------------------------------------------------------------
wasmtime::component::bindgen!({
    path: "../src-wit",
    world: "seon-pod",
    imports: { default: async | trappable },
    exports: { default: async },
    with: {
        "wasi:io":         wasmtime_wasi::p2::bindings::io,
        "wasi:filesystem": wasmtime_wasi::p2::bindings::filesystem,
        "wasi:clocks":     wasmtime_wasi::p2::bindings::clocks,
        "wasi:random":     wasmtime_wasi::p2::bindings::random,
        "wasi:sockets":    wasmtime_wasi::p2::bindings::sockets,
    },
});

// Pull the generated modules into scope for the impl blocks below. bindgen
// drops everything at module root, so refer to them directly.
use seon::pod::{capability_prompt, fs, mcp, types};
use wasi::logging::logging as wasi_logging;

// Re-export the WIT-generated data types so downstream crates (mcp-server-seon,
// the Tauri shell) consume them as `seon_tauri::pod::AgentState`, etc. The
// bindgen macro already aliases most types at module root via `pub type X =
// seon::pod::types::X;` — we only add what bindgen omitted.
pub use seon::pod::types::AgentState;

// ---------------------------------------------------------------------------
// SeonStore — wasmtime Store data type, implementing the host-side views
// for everything the wasm imports.
// ---------------------------------------------------------------------------

pub struct SeonStore {
    wasi:  WasiCtx,
    http:  WasiHttpCtx,
    table: ResourceTable,
    hooks: SeonHttpHooks,
}

/// Per-store hooks for `wasmtime-wasi-http`. Holds the outbound-HTTPS allowlist
/// and overrides [`WasiHttpHooks::send_request`] so denied hosts never reach
/// hyper. This is the URL-filtering hook referenced in
/// `docs/prds/webassembly-agents/research/capability-surface-2026-05-22.md`
/// §"Capability #2: Outbound HTTPS" §(B) item 1.
pub struct SeonHttpHooks {
    allowed: HttpAllowlist,
}

impl SeonHttpHooks {
    /// Construct a new hooks instance with the given allowlist. Exposed for
    /// integration tests that exercise the override without spinning up a
    /// full pod.
    pub fn new(allowed: HttpAllowlist) -> Self {
        Self { allowed }
    }
}

impl WasiHttpHooks for SeonHttpHooks {
    fn send_request(
        &mut self,
        request: hyper::Request<HyperOutgoingBody>,
        config: OutgoingRequestConfig,
    ) -> HttpResult<HostFutureIncomingResponse> {
        let host = request.uri().host().unwrap_or("");
        if self.allowed.is_allowed(host) {
            Ok(default_send_request(request, config))
        } else {
            tracing::warn!(
                target: "seon::pod::http",
                host = %host,
                uri = %request.uri(),
                "outbound HTTPS denied — host not in allowlist",
            );
            eprintln!(
                "[pod http] DENY {} (host {:?} not in allowlist)",
                request.uri(),
                host
            );
            Err(ErrorCode::HttpRequestDenied.into())
        }
    }
}

impl SeonStore {
    fn new(allowed: HttpAllowlist, preopens: &[(PathBuf, String)]) -> wasmtime::Result<Self> {
        let mut builder = WasiCtxBuilder::new();
        builder.inherit_stdio();
        builder.inherit_env();
        for (host_path, guest_path) in preopens {
            std::fs::create_dir_all(host_path).map_err(|e| {
                wasmtime::Error::new(e).context(format!(
                    "failed to create preopen dir {}",
                    host_path.display()
                ))
            })?;
            builder
                .preopened_dir(
                    host_path,
                    guest_path,
                    wasmtime_wasi::DirPerms::all(),
                    wasmtime_wasi::FilePerms::all(),
                )
                .map_err(|e| {
                    e.context(format!(
                        "failed to preopen {} -> {}",
                        host_path.display(),
                        guest_path
                    ))
                })?;
        }
        Ok(Self {
            wasi:  builder.build(),
            http:  WasiHttpCtx::new(),
            table: ResourceTable::new(),
            hooks: SeonHttpHooks { allowed },
        })
    }
}

impl WasiView for SeonStore {
    fn ctx(&mut self) -> WasiCtxView<'_> {
        WasiCtxView { ctx: &mut self.wasi, table: &mut self.table }
    }
}

impl WasiHttpView for SeonStore {
    fn http(&mut self) -> WasiHttpCtxView<'_> {
        WasiHttpCtxView {
            ctx:   &mut self.http,
            table: &mut self.table,
            hooks: &mut self.hooks,
        }
    }
}

// ---------------------------------------------------------------------------
// Stub host impls. Spec-05 §7.2 invariant: pod boot is HOST-IMPORT-FREE —
// `seon.client/-main` through `get-ui-port` only touches wasi:filesystem
// (preopens), wasi:logging, wasi:random, wasi:clocks, wasi:sockets. Our
// custom `seon:pod/*` interfaces and `wasi:logging` are wired here so that
// (a) the linker has somewhere to bind them at instantiation, and (b) if
// the wasm tries to use them in V0.5 we fail loudly.
//
// Real impls: B-4 (capability), B-5 (fs), B-6 (mcp).
// ---------------------------------------------------------------------------

// `seon:pod/fs`
impl fs::Host for SeonStore {
    async fn read_file(&mut self, _path: String) -> wasmtime::Result<Result<Vec<u8>, fs::FsError>> {
        Ok(Err(fs::FsError::Io("seon:pod/fs.read-file not wired (B-5)".into())))
    }
    async fn write_file(
        &mut self,
        _path: String,
        _data: Vec<u8>,
    ) -> wasmtime::Result<Result<(), fs::FsError>> {
        Ok(Err(fs::FsError::Io("seon:pod/fs.write-file not wired (B-5)".into())))
    }
    async fn list_dir(
        &mut self,
        _path: String,
    ) -> wasmtime::Result<Result<Vec<String>, fs::FsError>> {
        Ok(Err(fs::FsError::Io("seon:pod/fs.list-dir not wired (B-5)".into())))
    }
    async fn exists(&mut self, _path: String) -> wasmtime::Result<bool> {
        Ok(false)
    }
}

// `seon:pod/mcp` — resource-bearing interface. Auto-impl Host since it just
// composes HostHandle.
impl mcp::Host for SeonStore {}

impl mcp::HostHandle for SeonStore {
    async fn new(&mut self, _spec: mcp::McpSpec) -> wasmtime::Result<Resource<mcp::Handle>> {
        // Stub state pushed into the table. Subsequent send/close fail.
        let rep = self.table.push(McpHandleState::Stub)?;
        Ok(Resource::new_own(rep.rep()))
    }
    async fn send(
        &mut self,
        _self_: Resource<mcp::Handle>,
        _request: String,
    ) -> wasmtime::Result<Result<String, mcp::McpError>> {
        Ok(Err(mcp::McpError::Closed))
    }
    async fn close(&mut self, _self_: Resource<mcp::Handle>) -> wasmtime::Result<()> {
        Ok(())
    }
    async fn drop(&mut self, rep: Resource<mcp::Handle>) -> wasmtime::Result<()> {
        // Resource<mcp::Handle> can be downcast to the stub state we pushed.
        let typed: Resource<McpHandleState> = Resource::new_own(rep.rep());
        let _ = self.table.delete(typed);
        Ok(())
    }
}

#[derive(Debug)]
enum McpHandleState {
    Stub,
}

// `seon:pod/capability-prompt`
impl capability_prompt::Host for SeonStore {
    async fn ask(
        &mut self,
        _req: capability_prompt::Request,
    ) -> wasmtime::Result<capability_prompt::Decision> {
        Ok(capability_prompt::Decision::Deny)
    }
}

// `seon:pod/types` is pure data (no fns); the generated Host trait is empty.
impl types::Host for SeonStore {}

// `wasi:logging/logging`
impl wasi_logging::Host for SeonStore {
    async fn log(
        &mut self,
        level: wasi_logging::Level,
        context: String,
        message: String,
    ) -> wasmtime::Result<()> {
        eprintln!("[pod {level:?}] {context}: {message}");
        Ok(())
    }
}

// ---------------------------------------------------------------------------
// Pod — the public lifecycle handle held by the rest of the host.
// ---------------------------------------------------------------------------

/// Builder for [`Pod`]. Spec §7.2 — minimum WASI grants by default; lifted
/// dogfooding mode (spec §14.3) extends the host capability surface after
/// construction (B-5+).
pub struct PodBuilder {
    wasm_path:     PathBuf,
    async_support: bool,
    preopens:      Vec<(PathBuf, String)>,
    allowed:       HttpAllowlist,
}

impl PodBuilder {
    pub fn new(wasm_path: impl Into<PathBuf>) -> Self {
        Self {
            wasm_path:     wasm_path.into(),
            async_support: true,
            preopens:      Vec::new(),
            allowed:       HttpAllowlist::new(),
        }
    }

    pub fn with_async_support(mut self, enabled: bool) -> Self {
        self.async_support = enabled;
        self
    }

    /// Preopen `host_path` and expose it to the pod at `guest_path`. The
    /// directory is created if it doesn't already exist. Per spec-05 §14
    /// the V0.5 locked-down boot uses two preopens: `~/.seon/db -> /db`
    /// and `~/seon-dev-share -> /share`.
    pub fn with_preopen_dir(
        mut self,
        host_path: impl Into<PathBuf>,
        guest_path: impl Into<String>,
    ) -> Self {
        self.preopens.push((host_path.into(), guest_path.into()));
        self
    }

    pub fn with_http_allow_host(mut self, host: impl Into<String>) -> Self {
        self.allowed.allow_host(host);
        self
    }

    pub async fn start_async(self) -> wasmtime::Result<Pod> {
        let mut config = Config::new();
        // `async_support` is the default in wasmtime 44; the setter is
        // deprecated. We hold onto the builder flag in case it ever needs
        // to be disabled for a sync-only embedding test.
        let _ = self.async_support;
        config.wasm_component_model(true);

        let engine = Engine::new(&config)?;
        let component = Component::from_file(&engine, &self.wasm_path)
            .map_err(|e| e.context(format!(
                "loading wasm component from {}",
                self.wasm_path.display()
            )))?;

        let mut linker: Linker<SeonStore> = Linker::new(&engine);
        // Standard WASI (filesystem, clocks, random, sockets, cli, io, ...).
        wasmtime_wasi::p2::add_to_linker_async(&mut linker)?;
        // wasi:http (outgoing-handler + types).
        wasmtime_wasi_http::p2::add_only_http_to_linker_async(&mut linker)?;
        // Our custom interfaces + wasi:logging (host-getter is identity since
        // every Host trait is impl'd directly on SeonStore).
        types::add_to_linker::<_, HasSelf<SeonStore>>(&mut linker, |s| s)?;
        fs::add_to_linker::<_, HasSelf<SeonStore>>(&mut linker, |s| s)?;
        mcp::add_to_linker::<_, HasSelf<SeonStore>>(&mut linker, |s| s)?;
        capability_prompt::add_to_linker::<_, HasSelf<SeonStore>>(&mut linker, |s| s)?;
        wasi_logging::add_to_linker::<_, HasSelf<SeonStore>>(&mut linker, |s| s)?;

        let store_data = SeonStore::new(self.allowed, &self.preopens)?;
        let mut store = Store::new(&engine, store_data);

        let bindings = SeonPod::instantiate_async(&mut store, &component, &linker).await?;

        Ok(Pod { engine, store, bindings })
    }
}

pub struct Pod {
    #[allow(dead_code)]
    engine:   Engine,
    store:    Store<SeonStore>,
    bindings: SeonPod,
}

impl Pod {
    pub fn new(wasm_path: impl Into<PathBuf>) -> PodBuilder {
        PodBuilder::new(wasm_path)
    }

    /// Call the pod's `get-ui-port` export. Idempotent on the pod side;
    /// safe to call multiple times during host bring-up.
    pub async fn call_get_ui_port_async(&mut self) -> wasmtime::Result<u16> {
        self.bindings.call_get_ui_port(&mut self.store).await
    }

    /// Eval a Clojure form inside `agent_id`'s namespace.
    pub async fn call_eval_form_async(
        &mut self,
        agent_id: &str,
        form: &str,
        ns: &str,
    ) -> wasmtime::Result<Result<EvalResult, String>> {
        self.bindings.call_eval_form(&mut self.store, agent_id, form, ns).await
    }

    /// Datalog query against the pod's DB scoped to `agent_id`.
    pub async fn call_query_async(
        &mut self,
        agent_id: &str,
        datalog: &str,
    ) -> wasmtime::Result<Result<QueryResult, DbError>> {
        self.bindings.call_query(&mut self.store, agent_id, datalog).await
    }

    /// Kick one turn of `agent_id`'s loop without injecting a message.
    pub async fn call_trigger_turn_async(
        &mut self,
        agent_id: &str,
    ) -> wasmtime::Result<Result<TurnReport, RunError>> {
        self.bindings.call_trigger_turn(&mut self.store, agent_id).await
    }

    /// Post a message into `agent_id`'s inbox. Returns the message id.
    pub async fn call_inject_message_async(
        &mut self,
        agent_id: &str,
        content: &str,
        role: MessageRole,
    ) -> wasmtime::Result<Result<String, String>> {
        self.bindings
            .call_inject_message(&mut self.store, agent_id, content, role)
            .await
    }

    /// Snapshot of `agent_id` — turn count, state, rendered ctx, etc.
    pub async fn call_inspect_agent_async(
        &mut self,
        agent_id: &str,
    ) -> wasmtime::Result<Result<AgentSnapshot, String>> {
        self.bindings.call_inspect_agent(&mut self.store, agent_id).await
    }

    /// Cancel `agent_id`'s in-flight turn (no-op if idle).
    pub async fn call_interrupt_async(
        &mut self,
        agent_id: &str,
    ) -> wasmtime::Result<Result<(), String>> {
        self.bindings.call_interrupt(&mut self.store, agent_id).await
    }

    /// Best-effort `shutdown` — returns Ok even if the pod's own handler
    /// throws.
    pub async fn shutdown(&mut self) -> wasmtime::Result<()> {
        let _ = self.bindings.call_shutdown(&mut self.store).await;
        Ok(())
    }
}

#[allow(dead_code)]
fn placeholder_pod_path() -> PathBuf {
    // Resolves relative to seon/ (CARGO_MANIFEST_DIR is src-tauri/).
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .expect("workspace parent")
        .join("pod-build/target/wasm32-wasip2/release/seon_pod.wasm")
}

#[cfg(test)]
mod tests {
    use super::*;

    /// B-3 green: instantiate the B-2 placeholder pod and call get-ui-port.
    ///
    /// Skipped (with a clear message) when the wasm artifact is missing —
    /// run `bin/build-pod --placeholder` first.
    #[tokio::test(flavor = "multi_thread")]
    async fn placeholder_pod_returns_ui_port() {
        let wasm = placeholder_pod_path();
        if !wasm.exists() {
            eprintln!(
                "skipping: {} missing — run `bin/build-pod --placeholder` first",
                wasm.display()
            );
            return;
        }

        let mut pod = Pod::new(&wasm)
            .start_async()
            .await
            .expect("Pod::start_async should succeed against the placeholder pod");

        let port = pod
            .call_get_ui_port_async()
            .await
            .expect("call_get_ui_port_async should succeed");
        assert_eq!(port, 42, "placeholder.mjs returns 42 for get-ui-port");
    }
}
