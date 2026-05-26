//! Phase 3 — wasm guest hosting.
//!
//! Loads a compiled wasm32-wasip2 component (the sidecar-guest produced by
//! wasm-rquickjs), instantiates it, and exposes one entry point: `run_smoke`.
//! The guest's `seon:sidecar/db` imports are satisfied by forwarding into
//! the existing Phase-2 [`crate::DbHandle`] — every `q` / `transact` / `pull`
//! call from inside wasm round-trips to the JVM writer via the same WriterClient.
//!
//! Subscriptions are recorded for Phase 4; Phase 3 has no host→guest delivery.

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, Mutex as StdMutex};

use anyhow::Result;
use ciborium::Value as Cbor;
use tokio::runtime::Handle;
use tokio::sync::broadcast;
use wasmtime::component::{Component, HasSelf, Linker, ResourceTable};
use wasmtime::{Config, Engine, Store};
use wasmtime_wasi::{DirPerms, FilePerms, WasiCtx, WasiCtxBuilder, WasiCtxView, WasiView};
use wasmtime_wasi_http::p2::body::HyperOutgoingBody;
use wasmtime_wasi_http::p2::types::{HostFutureIncomingResponse, OutgoingRequestConfig};
use wasmtime_wasi_http::p2::{
    default_send_request, HttpResult, WasiHttpCtxView, WasiHttpHooks, WasiHttpView,
};
use wasmtime_wasi_http::WasiHttpCtx;

use crate::{DbHandle, TxEvent, WireDatom};

// ---------------------------------------------------------------------------
// Generated bindings for the sidecar-guest world. See ../wit/sidecar.wit.
// `wasi:*` imports delegate to wasmtime-wasi's existing generated bindings.
// `seon:sidecar/db` is host-impl'd below on [`GuestStore`].
// ---------------------------------------------------------------------------
wasmtime::component::bindgen!({
    path: "wit",
    world: "sidecar-guest",
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

use seon::sidecar::db as db_iface;
use wasi::logging::logging as wasi_logging;

/// Per-instance store data. Owns WASI + http contexts (wasm-rquickjs's
/// default `normal` feature tier includes `fetch`, which pulls wasi:http
/// from the runtime; we have to wire it on the linker side too), the
/// resource table, and a clone of the [`DbHandle`] used to satisfy
/// `seon:sidecar/db` imports.
pub struct GuestStore {
    wasi:  WasiCtx,
    http:  WasiHttpCtx,
    table: ResourceTable,
    hooks: NoopHttpHooks,
    db:    DbHandle,
    /// Subscription handle counter — bump per `subscribe-tx`.
    sub_counter: Arc<AtomicU32>,
    /// Map: handle -> broadcast receiver. Each `subscribe-tx` produces a
    /// fresh `Receiver` from the shared `broadcast::Sender` on the DbHandle.
    /// `next-tx-event(handle)` blocks on `recv().await`. We wrap in StdMutex
    /// because we need `&mut Receiver` from `&mut self` — the inner await
    /// happens against a temporarily-taken Receiver to satisfy the borrow
    /// checker across await points.
    subs: Arc<StdMutex<HashMap<u32, broadcast::Receiver<TxEvent>>>>,
    /// Per-instance agent-id passed in by the host. Empty if not set.
    /// Used as a default for own-tx tagging if request-id is absent.
    agent_id: Arc<StdMutex<String>>,
}

/// Default-allow wasi:http hook. wasm-rquickjs imports wasi:http via its
/// default `normal` feature tier; the guest never actually fetches anything
/// in Phase 3, but the linker has to be satisfied. If a guest ever does
/// outbound, this passes through.
pub struct NoopHttpHooks;

impl WasiHttpHooks for NoopHttpHooks {
    fn send_request(
        &mut self,
        request: hyper::Request<HyperOutgoingBody>,
        config: OutgoingRequestConfig,
    ) -> HttpResult<HostFutureIncomingResponse> {
        Ok(default_send_request(request, config))
    }
}

/// One WASI preopen entry. host_path on the outside (real filesystem) becomes
/// guest_path inside the wasm guest. `read_only=true` constrains DirPerms +
/// FilePerms to READ only — write attempts return EROFS/permission errors
/// inside the guest.
#[derive(Clone, Debug)]
pub struct MountSpec {
    pub host_path: PathBuf,
    pub guest_path: String,
    pub read_only: bool,
}

impl MountSpec {
    pub fn ro(host_path: impl Into<PathBuf>, guest_path: impl Into<String>) -> Self {
        Self {
            host_path: host_path.into(),
            guest_path: guest_path.into(),
            read_only: true,
        }
    }
    pub fn rw(host_path: impl Into<PathBuf>, guest_path: impl Into<String>) -> Self {
        Self {
            host_path: host_path.into(),
            guest_path: guest_path.into(),
            read_only: false,
        }
    }
}

impl GuestStore {
    #[allow(dead_code)]
    fn new(db: DbHandle) -> Self {
        Self::build(db, &[], &[]).expect("default GuestStore build")
    }

    /// Build a GuestStore with env vars and an optional set of preopened
    /// directories. Each MountSpec becomes a WASI preopen; `read_only` mounts
    /// get `DirPerms::READ + FilePerms::READ`, RW mounts get `::all()` for
    /// both. Returns an error if any host_path can't be opened (e.g. missing
    /// directory — the caller is expected to mkdir -p RW mounts beforehand).
    fn build(
        db: DbHandle,
        env_vars: &[(String, String)],
        mounts: &[MountSpec],
    ) -> Result<Self> {
        let mut builder = WasiCtxBuilder::new();
        builder.inherit_stdio();
        for (k, v) in env_vars {
            builder.env(k, v);
        }
        for m in mounts {
            let (dperms, fperms) = if m.read_only {
                (DirPerms::READ, FilePerms::READ)
            } else {
                (DirPerms::all(), FilePerms::all())
            };
            builder
                .preopened_dir(&m.host_path, &m.guest_path, dperms, fperms)
                .map_err(|e| anyhow::anyhow!(
                    "preopened_dir failed: host={:?} guest={:?} ro={}: {}",
                    m.host_path, m.guest_path, m.read_only, e
                ))?;
            tracing::info!(
                host = %m.host_path.display(),
                guest = %m.guest_path,
                ro = m.read_only,
                "wasi preopen mounted"
            );
        }
        Ok(Self {
            wasi:  builder.build(),
            http:  WasiHttpCtx::new(),
            table: ResourceTable::new(),
            hooks: NoopHttpHooks,
            db,
            sub_counter: Arc::new(AtomicU32::new(1)),
            subs: Arc::new(StdMutex::new(HashMap::new())),
            agent_id: Arc::new(StdMutex::new(String::new())),
        })
    }
}

impl WasiView for GuestStore {
    fn ctx(&mut self) -> WasiCtxView<'_> {
        WasiCtxView { ctx: &mut self.wasi, table: &mut self.table }
    }
}

impl WasiHttpView for GuestStore {
    fn http(&mut self) -> WasiHttpCtxView<'_> {
        WasiHttpCtxView {
            ctx:   &mut self.http,
            table: &mut self.table,
            hooks: &mut self.hooks,
        }
    }
}

// ---------------------------------------------------------------------------
// seon:sidecar/db — host impl. Forwards each call into the JVM writer via
// the shared DbHandle.
// ---------------------------------------------------------------------------

/// Pull `"result"` out of a writer response and pretty-print it as an EDN-ish
/// string suitable for return to the wasm guest. We DON'T try to faithfully
/// reproduce Clojure's `pr-str` here — the guest just gets a readable
/// representation. A real protocol would have the JVM writer return EDN
/// strings to start with; this is a PoC.
fn cbor_to_edn(v: &Cbor) -> String {
    match v {
        Cbor::Null => "nil".into(),
        Cbor::Bool(b) => b.to_string(),
        Cbor::Integer(i) => {
            let n: i128 = (*i).into();
            n.to_string()
        }
        Cbor::Float(f) => f.to_string(),
        Cbor::Text(s) => format!("\"{}\"", s.replace('\\', "\\\\").replace('"', "\\\"")),
        Cbor::Bytes(b) => format!("#bytes[{}]", b.len()),
        Cbor::Array(xs) => {
            let parts: Vec<String> = xs.iter().map(cbor_to_edn).collect();
            format!("[{}]", parts.join(" "))
        }
        Cbor::Map(items) => {
            let parts: Vec<String> = items
                .iter()
                .map(|(k, v)| format!("{} {}", cbor_to_edn(k), cbor_to_edn(v)))
                .collect();
            format!("{{{}}}", parts.join(", "))
        }
        Cbor::Tag(t, inner) => format!("#tag-{}({})", t, cbor_to_edn(inner)),
        _ => "<?>".into(),
    }
}

fn cbor_field<'a>(v: &'a Cbor, k: &str) -> Option<&'a Cbor> {
    if let Cbor::Map(items) = v {
        for (kk, vv) in items {
            if let Cbor::Text(s) = kk {
                if s == k {
                    return Some(vv);
                }
            }
        }
    }
    None
}

fn is_ok(resp: &Cbor) -> bool {
    matches!(cbor_field(resp, "ok"), Some(Cbor::Bool(true)))
}

fn err_string(resp: &Cbor) -> String {
    cbor_field(resp, "error")
        .and_then(|v| if let Cbor::Text(s) = v { Some(s.clone()) } else { None })
        .unwrap_or_else(|| format!("writer error: {}", cbor_to_edn(resp)))
}

// wasi:logging/logging — wasm-rquickjs's console.* funnels here.
impl wasi_logging::Host for GuestStore {
    async fn log(
        &mut self,
        level: wasi_logging::Level,
        context: String,
        message: String,
    ) -> wasmtime::Result<()> {
        eprintln!("[guest {:?}] {}: {}", level, context, message);
        Ok(())
    }
}

/// Pull "result" out of a writer response as an EDN-ish string.
fn resp_result_edn(resp: &Cbor) -> String {
    cbor_field(resp, "result")
        .map(cbor_to_edn)
        .unwrap_or_else(|| "nil".to_string())
}

impl db_iface::Host for GuestStore {
    async fn q(
        &mut self,
        query: String,
        args: Vec<String>,
        basis_t: i64,
    ) -> wasmtime::Result<Result<String, db_iface::DbError>> {
        let arg_cbors: Vec<Cbor> = args.into_iter().map(Cbor::Text).collect();
        let resp_res = if basis_t > 0 {
            self.db.q_at(&query, arg_cbors, basis_t).await
        } else {
            self.db.q(&query, arg_cbors).await
        };
        match resp_res {
            Ok(resp) if is_ok(&resp) => Ok(Ok(resp_result_edn(&resp))),
            Ok(resp) => Ok(Err(db_iface::DbError::InvalidQuery(err_string(&resp)))),
            Err(e) => Ok(Err(db_iface::DbError::Internal(format!("q failed: {e}")))),
        }
    }

    async fn transact(
        &mut self,
        tx_data: String,
        tx_meta: String,
        request_id: String,
    ) -> wasmtime::Result<Result<String, db_iface::DbError>> {
        let tx_meta_opt = if tx_meta.is_empty() { None } else { Some(tx_meta.as_str()) };
        let req_id_opt = if request_id.is_empty() { None } else { Some(request_id.as_str()) };
        match self.db.transact_full(&tx_data, tx_meta_opt, req_id_opt).await {
            Ok(resp) if is_ok(&resp) => {
                let bt = cbor_field(&resp, "basis-t").map(cbor_to_edn).unwrap_or("nil".into());
                let added = cbor_field(&resp, "datoms-added").map(cbor_to_edn).unwrap_or("0".into());
                let retr = cbor_field(&resp, "datoms-retracted").map(cbor_to_edn).unwrap_or("0".into());
                Ok(Ok(format!(
                    "{{:basis-t {} :datoms-added {} :datoms-retracted {}}}",
                    bt, added, retr
                )))
            }
            Ok(resp) => Ok(Err(db_iface::DbError::Internal(err_string(&resp)))),
            Err(e) => Ok(Err(db_iface::DbError::Internal(format!("transact failed: {e}")))),
        }
    }

    async fn transact_batch(
        &mut self,
        tx_data_list: Vec<String>,
        tx_meta_list: Vec<String>,
        request_ids: Vec<String>,
    ) -> wasmtime::Result<Result<String, db_iface::DbError>> {
        // WIT can't carry `option<list<string>>` cleanly through wasm-rquickjs,
        // so the contract is: an empty list means "omit". A non-empty list
        // must have length equal to tx-data-list. Per-entry "absent" is the
        // empty string "" (matches the same convention as transact's
        // tx_meta="" and request_id="").
        let n = tx_data_list.len();
        let tx_meta_opt: Option<Vec<Option<String>>> = if tx_meta_list.is_empty() {
            None
        } else if tx_meta_list.len() == n {
            Some(tx_meta_list.into_iter()
                 .map(|s| if s.is_empty() { None } else { Some(s) })
                 .collect())
        } else {
            return Ok(Err(db_iface::DbError::Protocol(format!(
                "transact-batch: tx-meta-list length {} != tx-data-list length {}",
                tx_meta_list.len(), n))));
        };
        let rid_opt: Option<Vec<Option<String>>> = if request_ids.is_empty() {
            None
        } else if request_ids.len() == n {
            Some(request_ids.into_iter()
                 .map(|s| if s.is_empty() { None } else { Some(s) })
                 .collect())
        } else {
            return Ok(Err(db_iface::DbError::Protocol(format!(
                "transact-batch: request-ids length {} != tx-data-list length {}",
                request_ids.len(), n))));
        };
        match self.db.transact_batch(tx_data_list, tx_meta_opt, rid_opt).await {
            Ok(resp) if is_ok(&resp) => {
                // Build an EDN-ish map carrying applied/total/failed-at + the
                // per-tx reports. Mirrors the wire shape so the CLJS overlay
                // can read it back with edn/read-string.
                let applied = cbor_field(&resp, "applied").map(cbor_to_edn).unwrap_or("0".into());
                let total = cbor_field(&resp, "total").map(cbor_to_edn).unwrap_or("0".into());
                let reports_edn = cbor_field(&resp, "reports").map(cbor_to_edn).unwrap_or("[]".into());
                let failed_at = cbor_field(&resp, "failed-at");
                let mut out = format!(
                    "{{:applied {} :total {} :reports {}",
                    applied, total, reports_edn
                );
                if let Some(fa) = failed_at {
                    out.push_str(&format!(" :failed-at {}", cbor_to_edn(fa)));
                    if let Some(err) = cbor_field(&resp, "error") {
                        out.push_str(&format!(" :error {}", cbor_to_edn(err)));
                    }
                }
                out.push('}');
                Ok(Ok(out))
            }
            Ok(resp) => Ok(Err(db_iface::DbError::Internal(err_string(&resp)))),
            Err(e) => Ok(Err(db_iface::DbError::Internal(format!("transact-batch failed: {e}")))),
        }
    }

    async fn pull(
        &mut self,
        selector: String,
        eid: String,
        basis_t: i64,
    ) -> wasmtime::Result<Result<String, db_iface::DbError>> {
        // The new wire shape passes eid as an EDN string. The writer reads it
        // via `read-edn-eid` — accepts ints or lookup-refs.
        match self.db.pull_edn(&selector, &eid, basis_t).await {
            Ok(resp) if is_ok(&resp) => Ok(Ok(resp_result_edn(&resp))),
            Ok(resp) => Ok(Err(db_iface::DbError::NotFound(err_string(&resp)))),
            Err(e) => Ok(Err(db_iface::DbError::Internal(format!("pull failed: {e}")))),
        }
    }

    async fn entity_pull(
        &mut self,
        reference: String,
        selector: String,
        depth: i32,
        basis_t: i64,
    ) -> wasmtime::Result<Result<String, db_iface::DbError>> {
        match self.db.entity_pull(&reference, &selector, depth, basis_t).await {
            Ok(resp) if is_ok(&resp) => Ok(Ok(resp_result_edn(&resp))),
            Ok(resp) => Ok(Err(db_iface::DbError::NotFound(err_string(&resp)))),
            Err(e) => Ok(Err(db_iface::DbError::Internal(format!("entity-pull: {e}")))),
        }
    }

    async fn pull_many(
        &mut self,
        selector: String,
        eids: Vec<String>,
        basis_t: i64,
    ) -> wasmtime::Result<Result<String, db_iface::DbError>> {
        match self.db.pull_many(&selector, eids, basis_t).await {
            Ok(resp) if is_ok(&resp) => Ok(Ok(resp_result_edn(&resp))),
            Ok(resp) => Ok(Err(db_iface::DbError::Internal(err_string(&resp)))),
            Err(e) => Ok(Err(db_iface::DbError::Internal(format!("pull-many: {e}")))),
        }
    }

    async fn schema(&mut self) -> wasmtime::Result<Result<String, db_iface::DbError>> {
        match self.db.schema().await {
            Ok(resp) if is_ok(&resp) => Ok(Ok(resp_result_edn(&resp))),
            Ok(resp) => Ok(Err(db_iface::DbError::Internal(err_string(&resp)))),
            Err(e) => Ok(Err(db_iface::DbError::Internal(format!("schema: {e}")))),
        }
    }

    async fn reverse_schema(&mut self) -> wasmtime::Result<Result<String, db_iface::DbError>> {
        match self.db.reverse_schema().await {
            Ok(resp) if is_ok(&resp) => Ok(Ok(resp_result_edn(&resp))),
            Ok(resp) => Ok(Err(db_iface::DbError::Internal(err_string(&resp)))),
            Err(e) => Ok(Err(db_iface::DbError::Internal(format!("reverse-schema: {e}")))),
        }
    }

    async fn db_filter(
        &mut self,
        pred_query: String,
        args: Vec<String>,
    ) -> wasmtime::Result<Result<u32, db_iface::DbError>> {
        match self.db.db_filter(&pred_query, args).await {
            Ok(resp) if is_ok(&resp) => {
                let handle = cbor_field(&resp, "handle")
                    .and_then(crate::cbor_as_i64)
                    .unwrap_or(0) as u32;
                Ok(Ok(handle))
            }
            Ok(resp) => Ok(Err(db_iface::DbError::InvalidQuery(err_string(&resp)))),
            Err(e) => Ok(Err(db_iface::DbError::Internal(format!("db-filter: {e}")))),
        }
    }

    async fn q_filtered(
        &mut self,
        handle: u32,
        query: String,
        args: Vec<String>,
    ) -> wasmtime::Result<Result<String, db_iface::DbError>> {
        match self.db.q_filtered(handle, &query, args).await {
            Ok(resp) if is_ok(&resp) => Ok(Ok(resp_result_edn(&resp))),
            Ok(resp) => Ok(Err(db_iface::DbError::NotFound(err_string(&resp)))),
            Err(e) => Ok(Err(db_iface::DbError::Internal(format!("q-filtered: {e}")))),
        }
    }

    async fn filter_release(
        &mut self,
        handle: u32,
    ) -> wasmtime::Result<Result<bool, db_iface::DbError>> {
        match self.db.filter_release(handle).await {
            Ok(_) => Ok(Ok(true)),
            Err(e) => Ok(Err(db_iface::DbError::Internal(format!("filter-release: {e}")))),
        }
    }

    async fn subscribe_tx(
        &mut self,
        key: String,
    ) -> wasmtime::Result<Result<u32, db_iface::DbError>> {
        let id = self.sub_counter.fetch_add(1, Ordering::Relaxed);
        let rx = self.db.subscribe_tx();
        self.subs.lock().unwrap().insert(id, rx);
        tracing::info!(handle = id, %key, "guest registered tx subscription");
        Ok(Ok(id))
    }

    async fn unsubscribe_tx(
        &mut self,
        handle: u32,
    ) -> wasmtime::Result<Result<bool, db_iface::DbError>> {
        let removed = self.subs.lock().unwrap().remove(&handle).is_some();
        Ok(Ok(removed))
    }

    async fn next_tx_event(
        &mut self,
        handle: u32,
    ) -> wasmtime::Result<Result<db_iface::TxEvent, db_iface::DbError>> {
        // **Non-blocking.** wasm-rquickjs exposes host imports as
        // SYNCHRONOUS JS calls — calling next-tx-event from QuickJS blocks
        // the QuickJS event loop until this host fn returns. **Even brief
        // blocking host calls starve the agent's setTimeout-based main
        // loop**, because the wstd timer driver can't tick while the wasm
        // fiber is suspended on an async host call. So this fn must be
        // strictly non-blocking: try to dequeue one event; if none is
        // pending, return a `Protocol("no-event")` sentinel and let the
        // overlay's listener loop yield via setTimeout. From the guest's
        // POV the listener polls with whatever interval the overlay
        // chooses (we use 25ms to balance latency vs CPU).
        let mut rx = match self.subs.lock().unwrap().remove(&handle) {
            Some(r) => r,
            None => return Ok(Err(db_iface::DbError::NotFound(
                format!("no subscription with handle {}", handle)))),
        };
        let result = match rx.try_recv() {
            Ok(ev) => {
                self.subs.lock().unwrap().insert(handle, rx);
                Ok(Ok(tx_event_to_wit(&ev)))
            }
            Err(broadcast::error::TryRecvError::Empty) => {
                self.subs.lock().unwrap().insert(handle, rx);
                Ok(Err(db_iface::DbError::Protocol("no-event".into())))
            }
            Err(broadcast::error::TryRecvError::Lagged(n)) => {
                tracing::warn!(handle, dropped = n, "tx broadcast lagged");
                self.subs.lock().unwrap().insert(handle, rx);
                Ok(Err(db_iface::DbError::Internal(
                    format!("tx-broadcast lagged: dropped {} events", n))))
            }
            Err(broadcast::error::TryRecvError::Closed) => {
                Ok(Err(db_iface::DbError::Internal(
                    "tx-broadcast channel closed".into())))
            }
        };
        result
    }
}

/// Convert an internal `TxEvent` into the WIT-bound record. CBOR-typed
/// values get printed to EDN strings on the way out — the WIT surface is
/// EDN-string in/out.
fn tx_event_to_wit(ev: &TxEvent) -> db_iface::TxEvent {
    let tx_data: Vec<db_iface::WireDatom> = ev.tx_data.iter().map(wire_to_wit_datom).collect();
    let tx_meta_str = cbor_to_edn(&ev.tx_meta);
    db_iface::TxEvent {
        basis_t:           ev.basis_t,
        basis_t_before:    ev.basis_t_before,
        db_name:           ev.db_name.clone(),
        datoms_added:      ev.datoms_added,
        datoms_retracted:  ev.datoms_retracted,
        tx_data,
        tx_meta:           tx_meta_str,
        request_id:        ev.request_id.clone().unwrap_or_default(),
    }
}

fn wire_to_wit_datom(d: &WireDatom) -> db_iface::WireDatom {
    db_iface::WireDatom {
        e:     d.e,
        a:     d.a.clone(),
        v:     cbor_to_edn(&d.v),
        t:     d.t,
        added: d.added,
    }
}

// ---------------------------------------------------------------------------
// Guest — the public type the rest of the host holds.
// ---------------------------------------------------------------------------

pub struct Guest {
    engine:   Engine,
    store:    Store<GuestStore>,
    bindings: SidecarGuest,
}

impl Guest {
    #[allow(dead_code)]
    pub async fn load(wasm_path: PathBuf, db: DbHandle) -> Result<Self> {
        Self::load_with_env_and_mounts(wasm_path, db, &[], &[]).await
    }

    #[allow(dead_code)]
    pub async fn load_with_env(
        wasm_path: PathBuf,
        db: DbHandle,
        env_vars: &[(String, String)],
    ) -> Result<Self> {
        Self::load_with_env_and_mounts(wasm_path, db, env_vars, &[]).await
    }

    pub async fn load_with_env_and_mounts(
        wasm_path: PathBuf,
        db: DbHandle,
        env_vars: &[(String, String)],
        mounts: &[MountSpec],
    ) -> Result<Self> {
        let mut config = Config::new();
        config.wasm_component_model(true);
        let engine = Engine::new(&config)?;

        tracing::info!(?wasm_path, "loading wasm guest component");
        let component = Component::from_file(&engine, &wasm_path)?;

        let mut linker: Linker<GuestStore> = Linker::new(&engine);
        wasmtime_wasi::p2::add_to_linker_async(&mut linker)?;
        // wasm-rquickjs's "normal" feature tier imports wasi:http; satisfy it.
        wasmtime_wasi_http::p2::add_only_http_to_linker_async(&mut linker)?;
        // Our db interface.
        db_iface::add_to_linker::<_, HasSelf<GuestStore>>(&mut linker, |s| s)?;
        wasi_logging::add_to_linker::<_, HasSelf<GuestStore>>(&mut linker, |s| s)?;

        let store_data = GuestStore::build(db, env_vars, mounts)?;
        let mut store = Store::new(&engine, store_data);
        let bindings = SidecarGuest::instantiate_async(&mut store, &component, &linker).await?;

        Ok(Self { engine, store, bindings })
    }

    /// Call the guest's `run-smoke` export. Returns the EDN-ish report string
    /// the guest assembled.
    pub async fn run_smoke(&mut self) -> Result<Result<String, String>> {
        let r = self.bindings.call_run_smoke(&mut self.store).await?;
        Ok(r)
    }

    /// Call the guest's `run-agent` export with an agent-id, role, and
    /// duration. Used by Phase D multi-agent smoke.
    pub async fn run_agent(
        &mut self,
        agent_id: &str,
        role: &str,
        duration_ms: u32,
    ) -> Result<Result<String, String>> {
        // Record agent-id on the store so it appears in tx-meta defaults.
        {
            let data = self.store.data_mut();
            *data.agent_id.lock().unwrap() = agent_id.to_string();
        }
        let r = self
            .bindings
            .call_run_agent(&mut self.store, agent_id, role, duration_ms)
            .await?;
        Ok(r)
    }
}

// Silence an unused-import warning when wasmtime feature combinations shift.
#[allow(dead_code)]
fn _engine_ty_check(g: &Guest) -> &Engine {
    &g.engine
}

#[allow(dead_code)]
fn _handle_unused(_h: Handle) {}
