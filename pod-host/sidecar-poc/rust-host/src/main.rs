//! Sidecar Rust host — Phase 2.
//!
//! Spawns the JVM writer subprocess, connects to its two UDS sockets
//! (req/resp + pub), wraps the req/resp client in a tokio mpsc actor, maintains
//! a basis-t-keyed snapshot cache, and broadcasts tx events to any in-process
//! subscribers (Phase 4 will hook wasm guests onto this).
//!
//! REPL CLI on stdin:
//!     ping
//!     q <edn-query>
//!     transact <edn-tx-data>
//!     pull <edn-selector> <edn-eid>
//!     bench reads <n>           ; cold + warm bench, n reads
//!     bench writes <n>          ; n sequential transacts
//!     stats
//!     quit

use anyhow::{anyhow, bail, Context, Result};
use clap::Parser;
use dashmap::DashMap;
use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use std::process::Stdio;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::io::{AsyncReadExt, AsyncWriteExt, BufReader, AsyncBufReadExt};
use tokio::net::UnixStream;
use tokio::process::{Child, Command};
use tokio::sync::{broadcast, mpsc, oneshot, Mutex};
use tokio::time::sleep;

mod guest;

// ---------------- CBOR value type ----------------

/// CBOR maps come in with string keys; we keep them in a BTreeMap of
/// ciborium::Value to preserve fidelity. Requests/responses are always top-level
/// maps with string keys, but values can be any CBOR.
type Cbor = ciborium::Value;

fn cbor_map_get<'a>(v: &'a Cbor, k: &str) -> Option<&'a Cbor> {
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

fn cbor_as_i64(v: &Cbor) -> Option<i64> {
    if let Cbor::Integer(i) = v {
        i128::from(*i).try_into().ok()
    } else {
        None
    }
}

fn make_map(pairs: Vec<(&str, Cbor)>) -> Cbor {
    Cbor::Map(
        pairs
            .into_iter()
            .map(|(k, v)| (Cbor::Text(k.to_string()), v))
            .collect(),
    )
}

// ---------------- Length-framed CBOR I/O ----------------

async fn write_frame<W: AsyncWriteExt + Unpin>(w: &mut W, value: &Cbor) -> Result<()> {
    let mut buf = Vec::with_capacity(256);
    ciborium::into_writer(value, &mut buf).context("cbor encode")?;
    let len = buf.len() as u32;
    w.write_all(&len.to_be_bytes()).await?;
    w.write_all(&buf).await?;
    w.flush().await?;
    Ok(())
}

async fn read_frame<R: AsyncReadExt + Unpin>(r: &mut R) -> Result<Option<Cbor>> {
    let mut len_buf = [0u8; 4];
    match r.read_exact(&mut len_buf).await {
        Ok(_) => {}
        Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(e) => return Err(e.into()),
    }
    let len = u32::from_be_bytes(len_buf) as usize;
    if len > 64 * 1024 * 1024 {
        bail!("frame too large: {}", len);
    }
    let mut payload = vec![0u8; len];
    r.read_exact(&mut payload).await?;
    let value: Cbor = ciborium::from_reader(&payload[..]).context("cbor decode")?;
    Ok(Some(value))
}

// ---------------- Writer client actor ----------------

#[derive(Debug)]
struct WriterRequest {
    body: Cbor,
    reply: oneshot::Sender<Result<Cbor>>,
}

#[derive(Clone)]
struct WriterClient {
    tx: mpsc::Sender<WriterRequest>,
}

impl WriterClient {
    async fn call(&self, body: Cbor) -> Result<Cbor> {
        let (reply_tx, reply_rx) = oneshot::channel();
        self.tx
            .send(WriterRequest {
                body,
                reply: reply_tx,
            })
            .await
            .map_err(|_| anyhow!("writer actor dropped"))?;
        reply_rx
            .await
            .map_err(|_| anyhow!("writer actor reply dropped"))?
    }
}

async fn run_writer_actor(stream: UnixStream, mut rx: mpsc::Receiver<WriterRequest>) {
    let (rd, wr) = stream.into_split();
    let mut rd = BufReader::new(rd);
    let mut wr = wr;
    while let Some(req) = rx.recv().await {
        let res: Result<Cbor> = async {
            write_frame(&mut wr, &req.body).await?;
            let resp = read_frame(&mut rd)
                .await?
                .ok_or_else(|| anyhow!("writer closed connection"))?;
            Ok(resp)
        }
        .await;
        let _ = req.reply.send(res);
    }
}

// ---------------- Pub subscriber ----------------

/// One wire-shape datom: `[e a v t op]`. `a` is the attribute keyword as a
/// string (e.g. `"person/name"`); `v` is whatever CBOR-native value the JVM
/// writer encoded. `op` is `true` for :db/add, `false` for :db/retract.
#[derive(Debug, Clone)]
#[allow(dead_code)]
pub struct WireDatom {
    pub e: i64,
    pub a: String,
    pub v: Cbor,
    pub t: i64,
    pub added: bool,
}

#[derive(Debug, Clone)]
#[allow(dead_code)]
pub struct TxEvent {
    pub basis_t: i64,
    pub basis_t_before: i64,
    pub db_name: String,
    pub datoms_added: i64,
    pub datoms_retracted: i64,
    /// Full datoms in this commit, in wire shape. Empty if the writer
    /// shipped a pre-gap-1 event (compatibility); current writers always
    /// populate this.
    pub tx_data: Vec<WireDatom>,
    /// `tx-meta` map verbatim (CBOR). Includes `db/txInstant` and
    /// `db/commitId` for datahike-issued txes.
    pub tx_meta: Cbor,
    /// Optional caller-supplied request-id for own-tx dedup. None if the
    /// originating transact didn't include one. Gap-2 will start populating
    /// these end-to-end; gap-1 ships the field through.
    pub request_id: Option<String>,
}

fn decode_wire_datom(v: &Cbor) -> Option<WireDatom> {
    let xs = if let Cbor::Array(xs) = v { xs } else { return None };
    if xs.len() < 5 {
        return None;
    }
    let e = cbor_as_i64(&xs[0])?;
    let a = if let Cbor::Text(s) = &xs[1] { s.clone() } else { return None };
    let v = xs[2].clone();
    let t = cbor_as_i64(&xs[3])?;
    let added = matches!(xs[4], Cbor::Bool(b) if b) || matches!(xs[4], Cbor::Bool(true));
    Some(WireDatom { e, a, v, t, added })
}

async fn run_pub_subscriber(stream: UnixStream, tx: broadcast::Sender<TxEvent>) {
    let (rd, _wr) = stream.into_split();
    let mut rd = BufReader::new(rd);
    loop {
        match read_frame(&mut rd).await {
            Ok(Some(ev)) => {
                let basis_t = cbor_map_get(&ev, "basis-t").and_then(cbor_as_i64).unwrap_or(0);
                let basis_t_before = cbor_map_get(&ev, "basis-t-before")
                    .and_then(cbor_as_i64)
                    .unwrap_or(0);
                let db_name = cbor_map_get(&ev, "db-name")
                    .and_then(|v| if let Cbor::Text(s) = v { Some(s.clone()) } else { None })
                    .unwrap_or_else(|| "default".to_string());
                let added =
                    cbor_map_get(&ev, "datoms-added").and_then(cbor_as_i64).unwrap_or(0);
                let retracted = cbor_map_get(&ev, "datoms-retracted")
                    .and_then(cbor_as_i64)
                    .unwrap_or(0);
                let tx_data: Vec<WireDatom> = match cbor_map_get(&ev, "tx-data") {
                    Some(Cbor::Array(xs)) => xs.iter().filter_map(decode_wire_datom).collect(),
                    _ => Vec::new(),
                };
                let tx_meta = cbor_map_get(&ev, "tx-meta").cloned().unwrap_or(Cbor::Null);
                let request_id = cbor_map_get(&ev, "request-id").and_then(|v| {
                    if let Cbor::Text(s) = v {
                        Some(s.clone())
                    } else {
                        None
                    }
                });
                let evt = TxEvent {
                    basis_t,
                    basis_t_before,
                    db_name,
                    datoms_added: added,
                    datoms_retracted: retracted,
                    tx_data,
                    tx_meta,
                    request_id,
                };
                tracing::debug!(
                    basis_t = evt.basis_t,
                    datoms = evt.tx_data.len(),
                    "tx event received"
                );
                // best-effort fanout
                let _ = tx.send(evt);
            }
            Ok(None) => {
                tracing::warn!("pub socket closed by writer");
                return;
            }
            Err(e) => {
                tracing::warn!(error=%e, "pub subscriber error");
                return;
            }
        }
    }
}

// ---------------- Snapshot cache ----------------

#[derive(Clone)]
struct CacheEntry {
    basis_t: i64,
    /// If true, the entry was inserted for an explicit `(d/as-of db basis-t)`
    /// query — its answer is immutable forever and survives writer tx
    /// invalidation. Unpinned entries (current-basis reads) get dropped
    /// when a newer basis arrives.
    pinned: bool,
    response: Cbor,
}

struct SnapshotCache {
    /// Key: CBOR-encoded request bytes (the request map). Value: cached response.
    /// We additionally tag with the basis_t at which it was observed.
    map: DashMap<Vec<u8>, CacheEntry>,
    /// Highest basis_t observed.
    high_water: AtomicU64,
    hits: AtomicU64,
    misses: AtomicU64,
    invalidations: AtomicU64,
}

impl SnapshotCache {
    fn new() -> Self {
        Self {
            map: DashMap::new(),
            high_water: AtomicU64::new(0),
            hits: AtomicU64::new(0),
            misses: AtomicU64::new(0),
            invalidations: AtomicU64::new(0),
        }
    }

    fn key_for(req: &Cbor) -> Result<Vec<u8>> {
        let mut buf = Vec::new();
        ciborium::into_writer(req, &mut buf)?;
        Ok(buf)
    }

    fn get(&self, key: &[u8]) -> Option<CacheEntry> {
        let r = self.map.get(key).map(|e| e.clone());
        if r.is_some() {
            self.hits.fetch_add(1, Ordering::Relaxed);
        } else {
            self.misses.fetch_add(1, Ordering::Relaxed);
        }
        r
    }

    fn insert(&self, key: Vec<u8>, entry: CacheEntry) {
        self.map.insert(key, entry);
    }

    /// On new commit, drop unpinned (current-basis) entries older than
    /// `new_basis_t`. Pinned entries — those explicitly requested at a
    /// specific basis-t via `(d/as-of db bt)` — are RETAINED across tx
    /// commits because their answer at that basis is immutable forever.
    fn on_tx(&self, new_basis_t: i64) {
        let prev = self.high_water.swap(new_basis_t as u64, Ordering::Relaxed);
        if (new_basis_t as u64) > prev {
            let mut dead = 0usize;
            self.map.retain(|_, v| {
                let keep = v.pinned || v.basis_t >= new_basis_t;
                if !keep {
                    dead += 1;
                }
                keep
            });
            if dead > 0 {
                self.invalidations.fetch_add(dead as u64, Ordering::Relaxed);
            }
        }
    }

    fn stats(&self) -> CacheStats {
        CacheStats {
            entries: self.map.len(),
            hits: self.hits.load(Ordering::Relaxed),
            misses: self.misses.load(Ordering::Relaxed),
            invalidations: self.invalidations.load(Ordering::Relaxed),
            high_water: self.high_water.load(Ordering::Relaxed),
        }
    }
}

#[derive(Debug, Serialize, Deserialize)]
struct CacheStats {
    entries: usize,
    hits: u64,
    misses: u64,
    invalidations: u64,
    high_water: u64,
}

// ---------------- Latency tracker (Phase D' rerun) ----------------

/// Records per-op latency samples in microseconds. Each op-bucket is a
/// std::sync::Mutex<Vec<u32>>; locking is held only for a push. For p50/p95/p99
/// we sort once at report time.
struct LatencyTracker {
    /// q hit (snapshot cache served from Rust, no JVM round trip)
    q_hit: std::sync::Mutex<Vec<u32>>,
    /// q miss (cache miss; full JVM round trip + decode)
    q_miss: std::sync::Mutex<Vec<u32>>,
    /// transact (always a JVM round trip + broadcast)
    tx: std::sync::Mutex<Vec<u32>>,
}

impl LatencyTracker {
    fn new() -> Self {
        Self {
            q_hit: std::sync::Mutex::new(Vec::with_capacity(8192)),
            q_miss: std::sync::Mutex::new(Vec::with_capacity(8192)),
            tx: std::sync::Mutex::new(Vec::with_capacity(8192)),
        }
    }

    fn record(bucket: &std::sync::Mutex<Vec<u32>>, dur: Duration) {
        let us = dur.as_micros().min(u32::MAX as u128) as u32;
        if let Ok(mut g) = bucket.lock() {
            g.push(us);
        }
    }

    fn percentiles(samples: &mut Vec<u32>) -> (u32, u32, u32, u32, u32, u32) {
        // returns (count, p50, p95, p99, min, max) — values in microseconds.
        if samples.is_empty() { return (0,0,0,0,0,0); }
        samples.sort_unstable();
        let n = samples.len();
        let idx = |p: f64| -> usize {
            let i = ((n as f64) * p).floor() as usize;
            i.min(n - 1)
        };
        (n as u32,
         samples[idx(0.50)],
         samples[idx(0.95)],
         samples[idx(0.99)],
         samples[0],
         samples[n - 1])
    }

    fn report(&self) -> String {
        let mut q_hit = self.q_hit.lock().unwrap().clone();
        let mut q_miss = self.q_miss.lock().unwrap().clone();
        let mut tx = self.tx.lock().unwrap().clone();
        let (h_n, h_p50, h_p95, h_p99, h_min, h_max) = Self::percentiles(&mut q_hit);
        let (m_n, m_p50, m_p95, m_p99, m_min, m_max) = Self::percentiles(&mut q_miss);
        let (t_n, t_p50, t_p95, t_p99, t_min, t_max) = Self::percentiles(&mut tx);
        format!(
"--- Latency (microseconds) ---
q-hit  : n={:>6}  p50={:>8}  p95={:>8}  p99={:>8}  min={:>8}  max={:>8}
q-miss : n={:>6}  p50={:>8}  p95={:>8}  p99={:>8}  min={:>8}  max={:>8}
tx     : n={:>6}  p50={:>8}  p95={:>8}  p99={:>8}  min={:>8}  max={:>8}",
            h_n, h_p50, h_p95, h_p99, h_min, h_max,
            m_n, m_p50, m_p95, m_p99, m_min, m_max,
            t_n, t_p50, t_p95, t_p99, t_min, t_max,
        )
    }
}

// ---------------- Transact batcher ----------------
//
// Opportunistic coalescer. Each guest's transact! produces one mpsc message;
// the batcher loop drains pending messages within a small window (try_recv +
// micro-sleep up to `BATCH_MAX_WINDOW`) and ships a single `transact-batch`
// op to the JVM writer.
//
// Ordering guarantee: tokio mpsc preserves enqueue order, the JVM writer
// applies the batch in array order, so per-batch ordering is FIFO.  Cross-
// guest ordering is whatever the global mpsc enqueue order is (i.e. whoever
// landed in the channel first commits first), which matches the pre-batcher
// behavior since both used a single in-flight pipe to the writer.
//
// Partial failure: the writer's `transact-batch` returns a partial-success
// response with `applied: k` reports + `failed-at: k` if entry k threw.
// Reports 0..k-1 succeed; their oneshots get the success payload. Entry k's
// oneshot gets the wire error. Entries k+1..N get a "skipped-after-batch-
// failure" error so the caller knows their tx did NOT apply.
//
// Tuning constants:
//   BATCH_MAX_SIZE   — cap on how many tx-datas we ship per batch (writer
//                      processes sequentially so very large batches just
//                      starve other writers from getting a turn).
//   BATCH_MAX_WINDOW — wall-clock max we'll wait for additional tx-datas
//                      to arrive after the first one. Too low = no batching
//                      under light load; too high = added latency per
//                      single tx.

const BATCH_MAX_SIZE:   usize        = 32;
// 2ms window: long enough to coalesce concurrent writes under realistic
// agent workloads (~5-10 tx/sec/agent), short enough to not noticeably
// inflate p99 latency vs the JVM commit floor (~30-150ms per tx). Tuned
// 2026-05-25 empirically; 500us was too tight to engage at typical
// inter-writer cadence.
const BATCH_MAX_WINDOW: Duration     = Duration::from_millis(2);

#[derive(Debug)]
struct TransactItem {
    tx_data_edn:    String,
    tx_meta_edn:    Option<String>,
    request_id:     Option<String>,
    reply:          oneshot::Sender<Result<Cbor>>,
}

#[derive(Clone)]
struct TransactBatcher {
    tx: mpsc::Sender<TransactItem>,
}

impl TransactBatcher {
    async fn submit(
        &self,
        tx_data_edn: String,
        tx_meta_edn: Option<String>,
        request_id:  Option<String>,
    ) -> Result<Cbor> {
        let (reply_tx, reply_rx) = oneshot::channel();
        self.tx
            .send(TransactItem {
                tx_data_edn,
                tx_meta_edn,
                request_id,
                reply: reply_tx,
            })
            .await
            .map_err(|_| anyhow!("transact batcher dropped"))?;
        reply_rx
            .await
            .map_err(|_| anyhow!("transact batcher reply dropped"))?
    }
}

/// Build a `transact-batch` request CBOR from a slice of items.
fn build_batch_request(items: &[TransactItem]) -> Cbor {
    let tx_data_list: Vec<Cbor> = items.iter().map(|it| Cbor::Text(it.tx_data_edn.clone())).collect();

    // tx-meta-list: nullable per entry. Only emit field if at least one is present.
    let has_any_meta = items.iter().any(|it| it.tx_meta_edn.is_some());
    let has_any_rid  = items.iter().any(|it| it.request_id.is_some());

    let mut pairs: Vec<(&str, Cbor)> = vec![
        ("op", Cbor::Text("transact-batch".into())),
        ("tx-data-list", Cbor::Array(tx_data_list)),
    ];
    if has_any_meta {
        let meta_list: Vec<Cbor> = items.iter()
            .map(|it| match &it.tx_meta_edn {
                Some(m) => Cbor::Text(m.clone()),
                None    => Cbor::Null,
            })
            .collect();
        pairs.push(("tx-meta-list", Cbor::Array(meta_list)));
    }
    if has_any_rid {
        let rid_list: Vec<Cbor> = items.iter()
            .map(|it| match &it.request_id {
                Some(r) => Cbor::Text(r.clone()),
                None    => Cbor::Null,
            })
            .collect();
        pairs.push(("request-ids", Cbor::Array(rid_list)));
    }
    make_map(pairs)
}

/// Convert a per-tx batch report (an entry of the writer's `"reports"`
/// array) into a single-transact-shaped response that callers can treat
/// identically to a pre-batching `transact` reply: `{ok true, basis-t,
/// basis-t-before, tempids, tx-data, tx-meta, datoms-added, ...}`.
fn report_to_single_response(report: &Cbor) -> Cbor {
    // The per-entry report already has the same keys; just inject ok=true.
    if let Cbor::Map(items) = report {
        let mut out: Vec<(Cbor, Cbor)> = vec![
            (Cbor::Text("ok".into()), Cbor::Bool(true)),
        ];
        for (k, v) in items {
            // Strip the per-entry "index" field — it's batch-specific bookkeeping.
            if let Cbor::Text(s) = k {
                if s == "index" { continue; }
            }
            out.push((k.clone(), v.clone()));
        }
        Cbor::Map(out)
    } else {
        Cbor::Null
    }
}

/// Histogram of observed batch sizes. Index 0 = singletons, index k = batches
/// of size k+1. Reported alongside cache stats so tuning is visible.
static BATCH_HIST: [AtomicU64; BATCH_MAX_SIZE] = {
    // const-fn initializer for an array of AtomicU64.
    const Z: AtomicU64 = AtomicU64::new(0);
    [Z; BATCH_MAX_SIZE]
};

fn batch_hist_report() -> String {
    let mut total: u64 = 0;
    let mut sized: u64 = 0;
    let cells: Vec<String> = (0..BATCH_MAX_SIZE).filter_map(|i| {
        let n = BATCH_HIST[i].load(Ordering::Relaxed);
        if n > 0 {
            total += n;
            sized += n * (i as u64 + 1);
            Some(format!("size {}: {}", i + 1, n))
        } else { None }
    }).collect();
    if cells.is_empty() { return "batch hist: (no transacts)".into(); }
    let avg = sized as f64 / total as f64;
    format!("batch hist: total-batches={} avg-size={:.2}  | {}",
            total, avg, cells.join(", "))
}

async fn run_transact_batcher(
    writer: WriterClient,
    mut rx: mpsc::Receiver<TransactItem>,
    cache: Arc<SnapshotCache>,
    latency: Arc<LatencyTracker>,
) {
    loop {
        // Block on first request.
        let first = match rx.recv().await {
            Some(it) => it,
            None     => return, // channel closed; sender dropped
        };
        let mut batch: Vec<TransactItem> = Vec::with_capacity(BATCH_MAX_SIZE);
        batch.push(first);

        // Brief drain window: try_recv until empty OR we hit BATCH_MAX_SIZE OR
        // the wall-clock window expires. We sleep once for the full window
        // rather than poll — fairness vs other tokio tasks.
        let deadline = Instant::now() + BATCH_MAX_WINDOW;
        loop {
            if batch.len() >= BATCH_MAX_SIZE { break; }
            match rx.try_recv() {
                Ok(it) => batch.push(it),
                Err(mpsc::error::TryRecvError::Empty)        => {
                    if Instant::now() >= deadline { break; }
                    // micro-yield to give other tasks a chance to enqueue
                    tokio::task::yield_now().await;
                }
                Err(mpsc::error::TryRecvError::Disconnected) => break,
            }
        }

        let batch_size = batch.len();
        BATCH_HIST[batch_size.saturating_sub(1).min(BATCH_MAX_SIZE - 1)]
            .fetch_add(1, Ordering::Relaxed);
        let t0 = Instant::now();

        if batch_size == 1 {
            // Fast path — for a singleton, skip the batch wire shape and just
            // do a normal single `transact`. Avoids the empty-batch overhead.
            let it = batch.into_iter().next().unwrap();
            let mut pairs: Vec<(&str, Cbor)> = vec![
                ("op",      Cbor::Text("transact".into())),
                ("tx-data", Cbor::Text(it.tx_data_edn)),
            ];
            if let Some(m) = it.tx_meta_edn { pairs.push(("tx-meta",    Cbor::Text(m))); }
            if let Some(r) = it.request_id  { pairs.push(("request-id", Cbor::Text(r))); }
            let req = make_map(pairs);
            let resp_res = writer.call(req).await;
            let elapsed = t0.elapsed();
            LatencyTracker::record(&latency.tx, elapsed);
            if let Ok(ref resp) = resp_res {
                if let Some(bt) = cbor_map_get(resp, "basis-t").and_then(cbor_as_i64) {
                    cache.on_tx(bt);
                }
            }
            let _ = it.reply.send(resp_res);
            continue;
        }

        // Multi-entry batch.
        let req = build_batch_request(&batch);
        let resp_res = writer.call(req).await;
        let elapsed = t0.elapsed();
        // Record one latency sample per batched tx (the per-tx wall time
        // they each waited).
        for _ in 0..batch_size {
            LatencyTracker::record(&latency.tx, elapsed);
        }

        let resp = match resp_res {
            Ok(r)  => r,
            Err(e) => {
                // Hard failure — wire-level. Fan the error to all waiters.
                for it in batch {
                    let _ = it.reply.send(Err(anyhow!("batcher wire error: {}", e)));
                }
                continue;
            }
        };

        // Walk the response. Successful batch has "reports" of length applied;
        // partial-failure has "reports" of length k + "failed-at": k +
        // "error", "error-kind".
        let reports = match cbor_map_get(&resp, "reports") {
            Some(Cbor::Array(rs)) => rs.clone(),
            _ => {
                for it in batch {
                    let _ = it.reply.send(Err(anyhow!(
                        "batcher: writer response missing 'reports' field: {}",
                        cbor_to_string(&resp)
                    )));
                }
                continue;
            }
        };
        let failed_at = cbor_map_get(&resp, "failed-at").and_then(cbor_as_i64);
        let err_msg = cbor_map_get(&resp, "error").and_then(|v| {
            if let Cbor::Text(s) = v { Some(s.clone()) } else { None }
        }).unwrap_or_else(|| "batcher entry failed without error message".to_string());

        // Push the latest basis-t into the cache invalidation path. Use the
        // last successful report's basis-t (highest).
        if let Some(last_ok) = reports.last() {
            if let Some(bt) = cbor_map_get(last_ok, "basis-t").and_then(cbor_as_i64) {
                cache.on_tx(bt);
            }
        }

        // Hand reports out in order; mark stragglers with skipped error.
        let mut report_iter = reports.into_iter();
        for (idx, it) in batch.into_iter().enumerate() {
            if let Some(rep) = report_iter.next() {
                let single = report_to_single_response(&rep);
                let _ = it.reply.send(Ok(single));
            } else if Some(idx as i64) == failed_at {
                let _ = it.reply.send(Err(anyhow!("transact failed in batch: {}", err_msg)));
            } else {
                let _ = it.reply.send(Err(anyhow!(
                    "transact skipped after batch failure at entry {} (msg: {})",
                    failed_at.unwrap_or(-1), err_msg
                )));
            }
        }
    }
}

// ---------------- High-level Db API (used by REPL, will also be used by WIT host) ----------------

#[derive(Clone)]
pub struct DbHandle {
    writer: WriterClient,
    batcher: TransactBatcher,
    cache: Arc<SnapshotCache>,
    tx_events: broadcast::Sender<TxEvent>,
    latency: Arc<LatencyTracker>,
}

impl DbHandle {
    pub fn subscribe_tx(&self) -> broadcast::Receiver<TxEvent> {
        self.tx_events.subscribe()
    }

    pub async fn ping(&self) -> Result<Cbor> {
        let req = make_map(vec![("op", Cbor::Text("ping".into()))]);
        self.writer.call(req).await
    }

    /// Run a query. Read-through cache keyed by the encoded request.
    /// Cache stores `response.result` along with basis-t; on a hit we still
    /// re-serve the original "basis-t" + "result" response shape.
    pub async fn q(&self, query_edn: &str, args: Vec<Cbor>) -> Result<Cbor> {
        let req = make_map(vec![
            ("op", Cbor::Text("q".into())),
            ("query", Cbor::Text(query_edn.into())),
            ("args", Cbor::Array(args)),
        ]);
        // Cache key uses the full request including args; basis-t isn't in the
        // request itself but we tag the cached entry with basis-t at insertion.
        let key = SnapshotCache::key_for(&req)?;
        let t0 = Instant::now();
        if let Some(entry) = self.cache.get(&key) {
            LatencyTracker::record(&self.latency.q_hit, t0.elapsed());
            return Ok(entry.response.clone());
        }
        let resp = self.writer.call(req).await?;
        LatencyTracker::record(&self.latency.q_miss, t0.elapsed());
        if let Some(bt) = cbor_map_get(&resp, "basis-t").and_then(cbor_as_i64) {
            self.cache.insert(
                key,
                CacheEntry {
                    basis_t: bt,
                    pinned: false,
                    response: resp.clone(),
                },
            );
        }
        Ok(resp)
    }

    pub async fn pull(&self, selector_edn: &str, eid: Cbor) -> Result<Cbor> {
        let req = make_map(vec![
            ("op", Cbor::Text("pull".into())),
            ("selector", Cbor::Text(selector_edn.into())),
            ("eid", eid),
        ]);
        let key = SnapshotCache::key_for(&req)?;
        if let Some(entry) = self.cache.get(&key) {
            return Ok(entry.response.clone());
        }
        let resp = self.writer.call(req).await?;
        if let Some(bt) = cbor_map_get(&resp, "basis-t").and_then(cbor_as_i64) {
            self.cache.insert(
                key,
                CacheEntry {
                    basis_t: bt,
                    pinned: false,
                    response: resp.clone(),
                },
            );
        }
        Ok(resp)
    }

    pub async fn transact(&self, tx_data_edn: &str) -> Result<Cbor> {
        self.transact_full(tx_data_edn, None, None).await
    }

    /// Guest-driven batch. Ships N tx-datas in one wire call directly to the
    /// JVM writer's `transact-batch` op (bypasses the opportunistic
    /// `TransactBatcher` because the guest has already done the batching).
    /// Each list (`tx_meta_list`, `request_ids`) is None to omit, or Some(v)
    /// where v.len() == tx_data_list.len(). Per-entry None values inside a
    /// Some list are encoded as Cbor::Null.
    ///
    /// Snapshot-cache invalidation: walks every per-tx report in the
    /// response and calls `cache.on_tx(basis-t)` for each — same effect
    /// as N individual transact wire calls. Latency: one sample per
    /// batched tx (each entry "waited" the same wall-time).
    pub async fn transact_batch(
        &self,
        tx_data_list: Vec<String>,
        tx_meta_list: Option<Vec<Option<String>>>,
        request_ids:  Option<Vec<Option<String>>>,
    ) -> Result<Cbor> {
        if let Some(ms) = &tx_meta_list {
            if ms.len() != tx_data_list.len() {
                bail!("transact_batch: tx-meta-list length {} != tx-data-list length {}",
                      ms.len(), tx_data_list.len());
            }
        }
        if let Some(rs) = &request_ids {
            if rs.len() != tx_data_list.len() {
                bail!("transact_batch: request-ids length {} != tx-data-list length {}",
                      rs.len(), tx_data_list.len());
            }
        }
        let n = tx_data_list.len();
        let mut pairs: Vec<(&str, Cbor)> = vec![
            ("op", Cbor::Text("transact-batch".into())),
            ("tx-data-list",
             Cbor::Array(tx_data_list.into_iter().map(Cbor::Text).collect())),
        ];
        if let Some(ms) = tx_meta_list {
            pairs.push(("tx-meta-list",
                        Cbor::Array(ms.into_iter()
                                    .map(|x| x.map(Cbor::Text).unwrap_or(Cbor::Null))
                                    .collect())));
        }
        if let Some(rs) = request_ids {
            pairs.push(("request-ids",
                        Cbor::Array(rs.into_iter()
                                    .map(|x| x.map(Cbor::Text).unwrap_or(Cbor::Null))
                                    .collect())));
        }
        let req = make_map(pairs);
        let t0 = Instant::now();
        let resp = self.writer.call(req).await?;
        let elapsed = t0.elapsed();
        // One latency sample per batched tx.
        for _ in 0..n {
            LatencyTracker::record(&self.latency.tx, elapsed);
        }
        // Walk reports for cache invalidation.
        if let Some(Cbor::Array(reports)) = cbor_map_get(&resp, "reports") {
            for rep in reports {
                if let Some(bt) = cbor_map_get(rep, "basis-t").and_then(cbor_as_i64) {
                    self.cache.on_tx(bt);
                }
            }
        }
        Ok(resp)
    }

    /// Transact with optional tx-meta and request-id. Routes through the
    /// opportunistic batcher — singleton-fast-path commits as a normal
    /// `transact`; concurrent writers within `BATCH_MAX_WINDOW` coalesce
    /// into a single `transact-batch` wire call. The latency tracker and
    /// cache invalidation are updated by the batcher loop, not here.
    pub async fn transact_full(
        &self,
        tx_data_edn: &str,
        tx_meta_edn: Option<&str>,
        request_id: Option<&str>,
    ) -> Result<Cbor> {
        self.batcher.submit(
            tx_data_edn.to_string(),
            tx_meta_edn.map(|s| s.to_string()),
            request_id.map(|s| s.to_string()),
        ).await
    }

    /// q against a pinned basis-t snapshot.
    pub async fn q_at(&self, query_edn: &str, args: Vec<Cbor>, basis_t: i64) -> Result<Cbor> {
        let req = make_map(vec![
            ("op", Cbor::Text("q".into())),
            ("query", Cbor::Text(query_edn.into())),
            ("args", Cbor::Array(args)),
            ("basis-t", Cbor::Integer(basis_t.into())),
        ]);
        let key = SnapshotCache::key_for(&req)?;
        let t0 = Instant::now();
        if let Some(entry) = self.cache.get(&key) {
            LatencyTracker::record(&self.latency.q_hit, t0.elapsed());
            return Ok(entry.response.clone());
        }
        let resp = self.writer.call(req).await?;
        LatencyTracker::record(&self.latency.q_miss, t0.elapsed());
        // Tag the entry with the basis_t we REQUESTED at, not the writer's
        // response basis-t. The writer's response carries the CURRENT db's
        // basis-t (latest committed), but we asked for an `as-of` snapshot at
        // `basis_t`. Caching against the response's basis-t made the entry
        // appear "current-basis" — which on_tx's drop-everything-older path
        // happily evicted on the next commit. Fix: cache against the
        // requested basis-t.
        if basis_t > 0 {
            self.cache.insert(key, CacheEntry { basis_t, pinned: true, response: resp.clone() });
        } else if let Some(bt) = cbor_map_get(&resp, "basis-t").and_then(cbor_as_i64) {
            self.cache.insert(key, CacheEntry { basis_t: bt, pinned: false, response: resp.clone() });
        }
        Ok(resp)
    }

    /// Pull with eid as an EDN string (int or lookup-ref).
    pub async fn pull_edn(&self, selector_edn: &str, eid_edn: &str, basis_t: i64) -> Result<Cbor> {
        let mut pairs: Vec<(&str, Cbor)> = vec![
            ("op", Cbor::Text("pull".into())),
            ("selector", Cbor::Text(selector_edn.into())),
            ("eid", Cbor::Text(eid_edn.into())),
        ];
        let pinned = basis_t > 0;
        if pinned {
            pairs.push(("basis-t", Cbor::Integer(basis_t.into())));
        }
        let req = make_map(pairs);
        let key = SnapshotCache::key_for(&req)?;
        if let Some(entry) = self.cache.get(&key) {
            return Ok(entry.response.clone());
        }
        let resp = self.writer.call(req).await?;
        if let Some(bt) = cbor_map_get(&resp, "basis-t").and_then(cbor_as_i64) {
            self.cache.insert(key, CacheEntry { basis_t: bt, pinned, response: resp.clone() });
        }
        Ok(resp)
    }

    pub async fn entity_pull(
        &self,
        reference_edn: &str,
        selector_edn: &str,
        depth: i32,
        basis_t: i64,
    ) -> Result<Cbor> {
        let mut pairs: Vec<(&str, Cbor)> = vec![
            ("op", Cbor::Text("entity-pull".into())),
            ("ref", Cbor::Text(reference_edn.into())),
        ];
        if !selector_edn.is_empty() {
            pairs.push(("selector", Cbor::Text(selector_edn.into())));
        }
        if depth >= 0 {
            pairs.push(("depth", Cbor::Integer((depth as i64).into())));
        }
        let pinned = basis_t > 0;
        if pinned {
            pairs.push(("basis-t", Cbor::Integer(basis_t.into())));
        }
        let req = make_map(pairs);
        let key = SnapshotCache::key_for(&req)?;
        if let Some(entry) = self.cache.get(&key) {
            return Ok(entry.response.clone());
        }
        let resp = self.writer.call(req).await?;
        if let Some(bt) = cbor_map_get(&resp, "basis-t").and_then(cbor_as_i64) {
            self.cache.insert(key, CacheEntry { basis_t: bt, pinned, response: resp.clone() });
        }
        Ok(resp)
    }

    pub async fn pull_many(
        &self,
        selector_edn: &str,
        eids_edn: Vec<String>,
        basis_t: i64,
    ) -> Result<Cbor> {
        let eids_cbor: Vec<Cbor> = eids_edn.into_iter().map(Cbor::Text).collect();
        let mut pairs: Vec<(&str, Cbor)> = vec![
            ("op", Cbor::Text("pull-many".into())),
            ("selector", Cbor::Text(selector_edn.into())),
            ("eids", Cbor::Array(eids_cbor)),
        ];
        if basis_t > 0 {
            pairs.push(("basis-t", Cbor::Integer(basis_t.into())));
        }
        let req = make_map(pairs);
        self.writer.call(req).await
    }

    pub async fn schema(&self) -> Result<Cbor> {
        let req = make_map(vec![("op", Cbor::Text("schema".into()))]);
        self.writer.call(req).await
    }

    pub async fn reverse_schema(&self) -> Result<Cbor> {
        let req = make_map(vec![("op", Cbor::Text("reverse-schema".into()))]);
        self.writer.call(req).await
    }

    pub async fn db_filter(&self, pred_query_edn: &str, args_edn: Vec<String>) -> Result<Cbor> {
        let args_cbor: Vec<Cbor> = args_edn.into_iter().map(Cbor::Text).collect();
        let req = make_map(vec![
            ("op", Cbor::Text("db-filter".into())),
            ("pred-query", Cbor::Text(pred_query_edn.into())),
            ("args", Cbor::Array(args_cbor)),
        ]);
        self.writer.call(req).await
    }

    pub async fn q_filtered(&self, handle: u32, query_edn: &str, args_edn: Vec<String>) -> Result<Cbor> {
        let args_cbor: Vec<Cbor> = args_edn.into_iter().map(Cbor::Text).collect();
        let req = make_map(vec![
            ("op", Cbor::Text("q-filtered".into())),
            ("handle", Cbor::Integer((handle as i64).into())),
            ("query", Cbor::Text(query_edn.into())),
            ("args", Cbor::Array(args_cbor)),
        ]);
        self.writer.call(req).await
    }

    pub async fn filter_release(&self, handle: u32) -> Result<Cbor> {
        let req = make_map(vec![
            ("op", Cbor::Text("filter-release".into())),
            ("handle", Cbor::Integer((handle as i64).into())),
        ]);
        self.writer.call(req).await
    }
}

// ---------------- JVM supervisor ----------------

struct JvmSupervisor {
    /// Kept alive so the Child's `kill_on_drop(true)` fires when the
    /// supervisor (and thus the World) is dropped.
    #[allow(dead_code)]
    child: Mutex<Option<Child>>,
}

impl JvmSupervisor {
    async fn spawn(
        writer_dir: &PathBuf,
        backend: &str,
        path: &str,
        req_sock: &str,
        pub_sock: &str,
    ) -> Result<Self> {
        // Clean any stale sockets before spawning
        let _ = tokio::fs::remove_file(req_sock).await;
        let _ = tokio::fs::remove_file(pub_sock).await;

        tracing::info!(?writer_dir, backend, path, req_sock, pub_sock, "spawning JVM writer");
        let mut cmd = Command::new("clojure");
        cmd.current_dir(writer_dir)
            .arg("-M:writer")
            .args([
                "--backend",
                backend,
                "--path",
                path,
                "--req-sock",
                req_sock,
                "--pub-sock",
                pub_sock,
            ])
            .stdout(Stdio::inherit())
            .stderr(Stdio::inherit())
            .kill_on_drop(true);
        let child = cmd.spawn().context("failed to spawn JVM writer")?;
        Ok(Self {
            child: Mutex::new(Some(child)),
        })
    }

    async fn wait_for_socket(path: &str, timeout: Duration) -> Result<()> {
        let start = Instant::now();
        loop {
            if tokio::fs::metadata(path).await.is_ok() {
                // It's a UDS — try a quick connect+disconnect to confirm it's accepting.
                if UnixStream::connect(path).await.is_ok() {
                    return Ok(());
                }
            }
            if start.elapsed() >= timeout {
                bail!("timeout waiting for socket {}", path);
            }
            sleep(Duration::from_millis(200)).await;
        }
    }

    #[allow(dead_code)]
    async fn shutdown(&self) -> Result<()> {
        if let Some(mut child) = self.child.lock().await.take() {
            tracing::info!("killing JVM writer");
            let _ = child.start_kill();
            let _ = child.wait().await;
        }
        Ok(())
    }
}

// ---------------- World + WorldRegistry ----------------
//
// A world = its own JVM writer, sockets, snapshot cache, broadcast channel,
// transact batcher. Multi-world isolation is by construction: zero shared
// mutable state across worlds, separate processes/sockets/files.
//
// The "default" world is just a world. It's spawned by the registry on first
// `get_or_spawn("default")` call. Single-guest, smoke, and REPL paths all
// route through the default world.

/// A world owns one JVM writer + its sockets/cache/broadcast. Cloning a
/// `DbHandle` (cheap — internally Arcs) gives any caller a per-world db
/// handle. Drop semantics: the underlying `JvmSupervisor` kills the child
/// JVM when the last Arc to the World goes away.
struct World {
    name: String,
    db: DbHandle,
    /// Kept alive for as long as the World exists. The Child is killed
    /// on Drop via `kill_on_drop(true)`.
    _supervisor: Arc<JvmSupervisor>,
    /// JoinHandles for the writer-actor + pub-subscriber + cache-listener
    /// + batcher tasks. We don't await them; they live until the JVM dies
    /// or the runtime shuts down. Held here so they're tied to the World
    /// lifetime (currently not awaited on drop — best-effort).
    #[allow(dead_code)]
    _tasks: Vec<tokio::task::JoinHandle<()>>,
}

impl World {
    /// Spawn a JVM writer for this world, connect to its sockets, build
    /// the batcher/cache/latency/broadcast plumbing, return the World.
    async fn spawn(
        name: String,
        writer_dir: &PathBuf,
        backend: &str,
        base_data_dir: &PathBuf,
        sock_base: &str,
    ) -> Result<Arc<Self>> {
        let store_path = base_data_dir.join("worlds").join(&name).join("store");
        // Ensure parent exists so the JVM's File.mkdirs has somewhere to plant.
        if let Some(p) = store_path.parent() {
            let _ = tokio::fs::create_dir_all(p).await;
        }
        let store_path_str = store_path
            .to_str()
            .ok_or_else(|| anyhow!("world store path not UTF-8: {:?}", store_path))?
            .to_string();
        let req_sock = format!("{}-{}-req.sock", sock_base, name);
        let pub_sock = format!("{}-{}-pub.sock", sock_base, name);

        let supervisor = JvmSupervisor::spawn(
            writer_dir,
            backend,
            &store_path_str,
            &req_sock,
            &pub_sock,
        )
        .await?;
        let supervisor = Arc::new(supervisor);

        tracing::info!(world = %name, "waiting for world sockets");
        JvmSupervisor::wait_for_socket(&req_sock, Duration::from_secs(60)).await?;
        JvmSupervisor::wait_for_socket(&pub_sock, Duration::from_secs(60)).await?;

        let req_stream = UnixStream::connect(&req_sock).await?;
        let pub_stream = UnixStream::connect(&pub_sock).await?;

        let (req_tx, req_rx) = mpsc::channel::<WriterRequest>(64);
        let writer_task = tokio::spawn(run_writer_actor(req_stream, req_rx));

        let (evt_tx, _evt_rx_keep) = broadcast::channel::<TxEvent>(256);
        let pub_task = tokio::spawn(run_pub_subscriber(pub_stream, evt_tx.clone()));

        let cache = Arc::new(SnapshotCache::new());

        // Cache-invalidation listener.
        let cache_task = {
            let cache = cache.clone();
            let mut rx = evt_tx.subscribe();
            let world_name = name.clone();
            tokio::spawn(async move {
                while let Ok(ev) = rx.recv().await {
                    cache.on_tx(ev.basis_t);
                    tracing::debug!(world = %world_name, basis_t = ev.basis_t, "pub tx invalidate");
                }
            })
        };

        let latency = Arc::new(LatencyTracker::new());
        let writer = WriterClient { tx: req_tx };

        // Per-world transact batcher.
        let (batch_tx, batch_rx) = mpsc::channel::<TransactItem>(256);
        let batcher_task = {
            let writer = writer.clone();
            let cache = cache.clone();
            let latency = latency.clone();
            tokio::spawn(run_transact_batcher(writer, batch_rx, cache, latency))
        };

        let db = DbHandle {
            writer,
            batcher: TransactBatcher { tx: batch_tx },
            cache: cache.clone(),
            tx_events: evt_tx,
            latency,
        };

        // Ping to confirm liveness.
        let pong = db.ping().await?;
        tracing::info!(world = %name, ?pong, "world writer ping ok");

        Ok(Arc::new(Self {
            name,
            db,
            _supervisor: supervisor,
            _tasks: vec![writer_task, pub_task, cache_task, batcher_task],
        }))
    }

    fn db(&self) -> DbHandle {
        self.db.clone()
    }
}

/// Registry that lazily spawns worlds by name. Concurrent `get_or_spawn`
/// calls for the same name are serialized by an inner mutex; the slow
/// spawn happens exactly once per name.
struct WorldRegistry {
    writer_dir: PathBuf,
    backend: String,
    base_data_dir: PathBuf,
    sock_base: String,
    worlds: tokio::sync::Mutex<std::collections::HashMap<String, Arc<World>>>,
}

impl WorldRegistry {
    fn new(
        writer_dir: PathBuf,
        backend: String,
        base_data_dir: PathBuf,
        sock_base: String,
    ) -> Self {
        Self {
            writer_dir,
            backend,
            base_data_dir,
            sock_base,
            worlds: tokio::sync::Mutex::new(std::collections::HashMap::new()),
        }
    }

    async fn get_or_spawn(&self, name: &str) -> Result<Arc<World>> {
        // Hold the registry lock across the spawn so a second concurrent
        // call for the same name waits and reuses the result. For multi-
        // world parallel spawn use the explicit `spawn_many` below.
        let mut worlds = self.worlds.lock().await;
        if let Some(w) = worlds.get(name) {
            return Ok(w.clone());
        }
        tracing::info!(world = %name, "spawning new world");
        let w = World::spawn(
            name.to_string(),
            &self.writer_dir,
            &self.backend,
            &self.base_data_dir,
            &self.sock_base,
        )
        .await?;
        worlds.insert(name.to_string(), w.clone());
        Ok(w)
    }

    #[allow(dead_code)]
    async fn list(&self) -> Vec<Arc<World>> {
        self.worlds.lock().await.values().cloned().collect()
    }
}

// ---------------- CLI ----------------

#[derive(Parser, Debug)]
#[command(version, about)]
struct Args {
    /// Path to the JVM writer project (defaults relative to this binary)
    #[arg(long, default_value = "../jvm-writer")]
    writer_dir: PathBuf,

    /// Store backend: memory | file
    #[arg(long, default_value = "file")]
    backend: String,

    /// Base data dir. Each world gets its own subdir at
    /// `<data_dir>/worlds/<name>/store/`.
    #[arg(long, default_value = "../data")]
    data_dir: PathBuf,

    /// Base path/prefix for per-world UDS sockets. World `alpha` gets
    /// `<sock_base>-alpha-req.sock` and `<sock_base>-alpha-pub.sock`.
    #[arg(long, default_value = "/tmp/seon-poc")]
    sock_base: String,

    /// Run an automated smoke test after startup and exit.
    #[arg(long, default_value_t = false)]
    smoke: bool,

    /// Path to a wasm32-wasip2 guest component (sidecar-guest). When given,
    /// loads the guest, calls its `run-smoke` export, prints the result.
    #[arg(long)]
    guest_wasm: Option<PathBuf>,

    /// Phase D — multi-agent smoke. Spawns N=3 instances of the wasm guest
    /// at --guest-wasm with roles writer/reader/mixed, runs for
    /// --multi-duration-ms, and prints aggregate stats.
    #[arg(long, default_value_t = false)]
    multi_agent: bool,

    /// Phase D — duration of multi-agent smoke run (ms).
    #[arg(long, default_value_t = 300_000u32)]
    multi_duration_ms: u32,

    /// Phase D' — bench mode selector passed to the guest via
    /// SIDECAR_BENCH_MODE. "default" (existing Phase D workload) or
    /// "cache-friendly" (reader/mixed pin snapshots for batched reads).
    #[arg(long, default_value = "default")]
    bench_mode: String,

    /// Phase D' — number of pinned-snapshot read cycles per snapshot roll
    /// (cache-friendly mode). Passed to the guest via SIDECAR_CACHE_BATCH.
    #[arg(long, default_value_t = 100u32)]
    cache_batch: u32,

    /// Phase PF — multi-world smoke. Spawns N worlds in parallel, each
    /// with its own JVM writer + sockets + store, and runs --agents-per-world
    /// guest instances inside each. Use --worlds to name them.
    #[arg(long, default_value_t = false)]
    multi_world: bool,

    /// Comma-separated world names for --multi-world. Default: "alpha,beta".
    #[arg(long, default_value = "alpha,beta")]
    worlds: String,

    /// Agents per world for --multi-world. Each gets a writer/reader/mixed
    /// role cycling through the three. With 1 agent per world the role is
    /// "writer"; with 2 agents the second is "reader"; with 3+ the
    /// pattern is writer/reader/mixed/writer/...
    #[arg(long, default_value_t = 2u32)]
    agents_per_world: u32,
}

// ---------------- Pretty-print a Cbor value (for the REPL) ----------------

fn cbor_to_string(v: &Cbor) -> String {
    match v {
        Cbor::Null => "nil".into(),
        Cbor::Bool(b) => b.to_string(),
        Cbor::Integer(i) => {
            let n: i128 = (*i).into();
            n.to_string()
        }
        Cbor::Float(f) => f.to_string(),
        Cbor::Text(s) => format!("{:?}", s),
        Cbor::Bytes(b) => format!("#bytes[{}]", b.len()),
        Cbor::Array(xs) => {
            let parts: Vec<String> = xs.iter().map(cbor_to_string).collect();
            format!("[{}]", parts.join(" "))
        }
        Cbor::Map(items) => {
            let parts: Vec<String> = items
                .iter()
                .map(|(k, v)| format!("{} {}", cbor_to_string(k), cbor_to_string(v)))
                .collect();
            format!("{{{}}}", parts.join(", "))
        }
        Cbor::Tag(t, inner) => format!("#tag-{}({})", t, cbor_to_string(inner)),
        _ => "<?>".into(),
    }
}

// ---------------- Main ----------------

async fn smoke_test(db: &DbHandle) -> Result<()> {
    println!("--- smoke test ---");
    let t0 = Instant::now();
    let r = db.ping().await?;
    println!("ping ({:?}): {}", t0.elapsed(), cbor_to_string(&r));

    let t0 = Instant::now();
    let r = db
        .transact(
            "[{:db/ident :person/name :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
              {:db/ident :person/age  :db/valueType :db.type/long   :db/cardinality :db.cardinality/one}]",
        )
        .await?;
    println!("schema ({:?}): {}", t0.elapsed(), cbor_to_string(&r));

    let t0 = Instant::now();
    let r = db
        .transact("[{:person/name \"alice\" :person/age 33}]")
        .await?;
    println!("transact alice ({:?}): {}", t0.elapsed(), cbor_to_string(&r));

    // Cold + warm q
    let t0 = Instant::now();
    let r = db
        .q(
            "[:find ?n ?a :where [?e :person/name ?n] [?e :person/age ?a]]",
            vec![],
        )
        .await?;
    let cold = t0.elapsed();
    println!("q cold  ({:?}): {}", cold, cbor_to_string(&r));

    let t0 = Instant::now();
    let _ = db
        .q(
            "[:find ?n ?a :where [?e :person/name ?n] [?e :person/age ?a]]",
            vec![],
        )
        .await?;
    let warm = t0.elapsed();
    println!("q warm  ({:?})", warm);

    let t0 = Instant::now();
    let _ = db
        .q(
            "[:find ?n ?a :where [?e :person/name ?n] [?e :person/age ?a]]",
            vec![],
        )
        .await?;
    println!("q warm2 ({:?})", t0.elapsed());

    println!("cache stats: {:?}", db.cache.stats());

    // Listener wakeup: subscribe, then commit something, then time delivery.
    let mut rx = db.subscribe_tx();
    let t0 = Instant::now();
    let r = db
        .transact("[{:person/name \"bob\" :person/age 41}]")
        .await?;
    let commit_dur = t0.elapsed();
    let t1 = Instant::now();
    let ev = tokio::time::timeout(Duration::from_secs(2), rx.recv())
        .await
        .context("timeout waiting for pub event")??;
    let pub_dur = t1.elapsed();
    println!(
        "transact bob ({:?}): {} ; pub event delivered in {:?} after commit ack",
        commit_dur,
        cbor_to_string(&r),
        pub_dur,
    );
    println!(
        "  pub event: basis-t={} basis-t-before={} db-name={:?} added={} retracted={} datoms={} request-id={:?}",
        ev.basis_t, ev.basis_t_before, ev.db_name, ev.datoms_added, ev.datoms_retracted,
        ev.tx_data.len(), ev.request_id,
    );
    for d in &ev.tx_data {
        println!("    {} {} {} t={} added={}", d.e, d.a, cbor_to_string(&d.v), d.t, d.added);
    }

    // After tx, prior cache entry is invalidated.
    let t0 = Instant::now();
    let _ = db
        .q(
            "[:find ?n ?a :where [?e :person/name ?n] [?e :person/age ?a]]",
            vec![],
        )
        .await?;
    let post_invalidation = t0.elapsed();
    println!(
        "q after tx (should be cold) ({:?})",
        post_invalidation
    );
    println!("cache stats: {:?}", db.cache.stats());

    Ok(())
}

async fn run_repl(db: DbHandle) -> Result<()> {
    println!("REPL ready. Commands: ping | q <edn> | transact <edn> | pull <sel> <eid> | bench reads <n> | bench writes <n> | stats | smoke | quit");
    let stdin = tokio::io::stdin();
    let mut reader = BufReader::new(stdin).lines();
    while let Ok(Some(line)) = reader.next_line().await {
        let line = line.trim();
        if line.is_empty() {
            continue;
        }
        let (cmd, rest) = match line.split_once(' ') {
            Some((c, r)) => (c, r.trim()),
            None => (line, ""),
        };
        let result = match cmd {
            "ping" => db.ping().await.map(|r| cbor_to_string(&r)),
            "q" => db.q(rest, vec![]).await.map(|r| cbor_to_string(&r)),
            "transact" => db.transact(rest).await.map(|r| cbor_to_string(&r)),
            "pull" => {
                // very rough: split selector and eid by " | "
                let (sel, eid) = rest
                    .split_once(" | ")
                    .ok_or_else(|| anyhow!("usage: pull <selector> | <eid>"))?;
                db.pull(sel.trim(), Cbor::Text(eid.trim().into()))
                    .await
                    .map(|r| cbor_to_string(&r))
            }
            "stats" => Ok(format!("{:?}", db.cache.stats())),
            "smoke" => match smoke_test(&db).await {
                Ok(()) => Ok("smoke ok".to_string()),
                Err(e) => Err(e),
            },
            "bench" => match rest.split_once(' ') {
                Some(("reads", n)) => {
                    let n: usize = n.parse().context("bench reads <n>")?;
                    bench_reads(&db, n).await.map(|s| s)
                }
                Some(("writes", n)) => {
                    let n: usize = n.parse().context("bench writes <n>")?;
                    bench_writes(&db, n).await.map(|s| s)
                }
                _ => Err(anyhow!("usage: bench reads <n> | bench writes <n>")),
            },
            "quit" | "exit" => {
                println!("bye");
                return Ok(());
            }
            _ => Err(anyhow!("unknown cmd: {}", cmd)),
        };
        match result {
            Ok(s) => println!("=> {}", s),
            Err(e) => println!("!! {}", e),
        }
    }
    Ok(())
}

async fn bench_reads(db: &DbHandle, n: usize) -> Result<String> {
    // Clear cache by tagging a fake "tx" forward — actually, just touch a unique
    // query so its first call is cold, then re-run n-1 times warm.
    let q = "[:find ?n :where [?e :person/name ?n]]";
    let t0 = Instant::now();
    let _ = db.q(q, vec![]).await?;
    let cold = t0.elapsed();
    let t0 = Instant::now();
    for _ in 0..n.saturating_sub(1) {
        let _ = db.q(q, vec![]).await?;
    }
    let warm = t0.elapsed();
    let warm_each = if n > 1 {
        warm / (n as u32 - 1)
    } else {
        Duration::ZERO
    };
    Ok(format!(
        "cold={:?} warm_total={:?} warm_each={:?} stats={:?}",
        cold,
        warm,
        warm_each,
        db.cache.stats()
    ))
}

/// Phase D — N=3 multi-agent smoke. Spawns three wasm guest instances
/// each with a distinct role (writer / reader / mixed) sharing the same
/// JVM writer + snapshot cache. Runs concurrently for `duration_ms`,
/// then prints aggregate stats.
async fn run_multi_agent(
    wasm_path: PathBuf,
    db: DbHandle,
    duration_ms: u32,
    bench_mode: String,
    cache_batch: u32,
) -> Result<()> {
    println!(
        "--- Phase D multi-agent smoke (N=3, duration_ms={}, bench_mode={}, cache_batch={}) ---",
        duration_ms, bench_mode, cache_batch
    );

    // Install Phase-D schema. Idempotent — second install errors silently.
    let task_schema = r#"[
        {:db/ident :task/id        :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
        {:db/ident :task/status    :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
        {:db/ident :task/created-by :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
        {:db/ident :task/created-ms :db/valueType :db.type/long   :db/cardinality :db.cardinality/one}
        {:db/ident :task/started-ms :db/valueType :db.type/long   :db/cardinality :db.cardinality/one}
        {:db/ident :task/done-ms   :db/valueType :db.type/long   :db/cardinality :db.cardinality/one}
        {:db/ident :result/of      :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
        {:db/ident :result/blob    :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
        {:db/ident :result/by      :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
    ]"#;
    match db.transact(task_schema).await {
        Ok(_) => println!("phase-D schema installed"),
        Err(e) => tracing::warn!(error=%e, "phase-D schema install failed (may already be installed)"),
    }

    let roles: Vec<(&str, &str)> = vec![
        ("agent-w", "writer"),
        ("agent-r", "reader"),
        ("agent-m", "mixed"),
    ];

    // Use futures::join_all rather than tokio::spawn so all three agents
    // run on the same task and the wasmtime async runtime sees them
    // co-scheduled deterministically. wasmtime async polling can be
    // sensitive to Send-bound concerns when stores cross threads via
    // tokio::spawn.
    use futures::future::join_all;
    let t_start = Instant::now();
    let agent_futures: Vec<_> = roles.iter().map(|(agent_id, role)| {
        let wasm_path = wasm_path.clone();
        let db = db.clone();
        let agent_id = agent_id.to_string();
        let role = role.to_string();
        let dur = duration_ms;
        let bench_mode = bench_mode.clone();
        let cache_batch = cache_batch;
        async move {
            let t0 = Instant::now();
            let mut g = guest::Guest::load_with_env(
                wasm_path,
                db,
                &[
                    ("SIDECAR_AGENT_ID".to_string(), agent_id.clone()),
                    ("SIDECAR_AGENT_ROLE".to_string(), role.clone()),
                    ("SIDECAR_AGENT_DURATION_MS".to_string(), dur.to_string()),
                    ("SIDECAR_BENCH_MODE".to_string(), bench_mode.clone()),
                    ("SIDECAR_CACHE_BATCH".to_string(), cache_batch.to_string()),
                ],
            )
            .await?;
            let loaded = t0.elapsed();
            println!("[{}/{}] guest loaded in {:?}", agent_id, role, loaded);
            let t0 = Instant::now();
            // Bound the wait. wasm-rquickjs's wstd runtime appears to keep
            // the export call alive after the JS Promise resolves (the
            // resource_drop_queue join doesn't always settle) — workaround:
            // give the agent `dur + 3s` to clean up, then drop the Guest.
            let bound = Duration::from_millis((dur as u64) + 3000);
            let r = tokio::time::timeout(bound, g.run_agent(&agent_id, &role, dur)).await;
            let run = t0.elapsed();
            match r {
                Ok(Ok(Ok(s))) => println!("[{}/{}] DONE in {:?}: {}", agent_id, role, run, s),
                Ok(Ok(Err(e))) => println!("[{}/{}] ERR in {:?}: {}", agent_id, role, run, e),
                Ok(Err(e)) => println!("[{}/{}] HOST-ERR in {:?}: {}", agent_id, role, run, e),
                Err(_) => println!("[{}/{}] TIMEOUT-CLEAN-EXIT in {:?}", agent_id, role, run),
            }
            drop(g);
            Ok::<_, anyhow::Error>(())
        }
    }).collect();
    let _ = join_all(agent_futures).await;
    let wall = t_start.elapsed();

    // Aggregate stats from the database.
    let pending_q = "[:find (count ?e) :where [?e :task/status :pending]]";
    let inprog_q  = "[:find (count ?e) :where [?e :task/status :in-progress]]";
    let done_q    = "[:find (count ?e) :where [?e :task/status :done]]";
    let total_q   = "[:find (count ?e) :where [?e :task/id _]]";
    let results_q = "[:find (count ?e) :where [?e :result/blob _]]";
    let p = db.q(pending_q, vec![]).await?;
    let ip = db.q(inprog_q, vec![]).await?;
    let d = db.q(done_q, vec![]).await?;
    let total = db.q(total_q, vec![]).await?;
    let rcount = db.q(results_q, vec![]).await?;

    println!("--- Phase D results ---");
    println!("wall time:   {:?}", wall);
    println!("tasks total: {}", cbor_to_string(&total));
    println!("  pending:     {}", cbor_to_string(&p));
    println!("  in-progress: {}", cbor_to_string(&ip));
    println!("  done:        {}", cbor_to_string(&d));
    println!("results:     {}", cbor_to_string(&rcount));
    println!("cache stats: {:?}", db.cache.stats());
    println!("{}", db.latency.report());
    println!("{}", batch_hist_report());

    Ok(())
}

async fn bench_writes(db: &DbHandle, n: usize) -> Result<String> {
    // Insert n distinct people.
    let t0 = Instant::now();
    for i in 0..n {
        let edn = format!("[{{:person/name \"bench-{}\" :person/age {}}}]", i, i % 100);
        let _ = db.transact(&edn).await?;
    }
    let total = t0.elapsed();
    let each = total / (n as u32).max(1);
    Ok(format!("total={:?} each={:?}", total, each))
}

/// Install the demo `:person/*` schema. Idempotent — duplicate attrs return
/// an error from the JVM writer, which we ignore.
async fn install_person_schema(db: &DbHandle) {
    let schema = "[{:db/ident :person/name :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
                   {:db/ident :person/age  :db/valueType :db.type/long   :db/cardinality :db.cardinality/one}]";
    match db.transact(schema).await {
        Ok(_)  => tracing::info!("guest schema installed (or already present)"),
        Err(e) => tracing::warn!(error=%e, "guest schema install failed (continuing — may already be installed)"),
    }
}

/// Install the Phase-D `:task/*` + `:result/*` schema. Idempotent.
async fn install_phase_d_schema(db: &DbHandle) {
    let task_schema = r#"[
        {:db/ident :task/id        :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
        {:db/ident :task/status    :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
        {:db/ident :task/created-by :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
        {:db/ident :task/created-ms :db/valueType :db.type/long   :db/cardinality :db.cardinality/one}
        {:db/ident :task/started-ms :db/valueType :db.type/long   :db/cardinality :db.cardinality/one}
        {:db/ident :task/done-ms   :db/valueType :db.type/long   :db/cardinality :db.cardinality/one}
        {:db/ident :result/of      :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
        {:db/ident :result/blob    :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
        {:db/ident :result/by      :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
    ]"#;
    match db.transact(task_schema).await {
        Ok(_)  => tracing::info!("phase-D schema installed"),
        Err(e) => tracing::warn!(error=%e, "phase-D schema install failed (continuing)"),
    }
}

/// Phase PF — multi-world smoke. Spawns N worlds in parallel, each with
/// `agents_per_world` guest instances. Returns when all worlds complete.
async fn run_multi_world(
    registry: Arc<WorldRegistry>,
    wasm_path: PathBuf,
    world_names: Vec<String>,
    agents_per_world: u32,
    duration_ms: u32,
    bench_mode: String,
    cache_batch: u32,
) -> Result<()> {
    println!(
        "--- Phase PF multi-world smoke (worlds={:?}, agents-per-world={}, duration_ms={}) ---",
        world_names, agents_per_world, duration_ms
    );

    let t_start = Instant::now();

    // Spawn all worlds in parallel. Each get_or_spawn holds the registry
    // mutex briefly during its own slot insert; the heavy work (JVM boot)
    // happens before that, so worlds boot concurrently.
    let spawn_futs: Vec<_> = world_names.iter().map(|name| {
        let registry = registry.clone();
        let name = name.clone();
        async move { registry.get_or_spawn(&name).await }
    }).collect();
    let worlds: Vec<Arc<World>> = futures::future::try_join_all(spawn_futs).await?;
    println!("all {} worlds booted in {:?}", worlds.len(), t_start.elapsed());

    // Install the phase-D schema in every world (per-world isolated DBs).
    for w in &worlds {
        install_phase_d_schema(&w.db()).await;
    }

    // Build (world, agent_id, role) tasks. Role cycles writer/reader/mixed.
    let roles = ["writer", "reader", "mixed"];
    let mut agent_specs: Vec<(Arc<World>, String, String)> = Vec::new();
    for w in &worlds {
        for i in 0..agents_per_world {
            let role = roles[(i as usize) % roles.len()].to_string();
            let agent_id = format!("{}-{}-{}", w.name, role, i);
            agent_specs.push((w.clone(), agent_id, role));
        }
    }

    use futures::future::join_all;
    let agent_futures: Vec<_> = agent_specs.into_iter().map(|(world, agent_id, role)| {
        let wasm_path = wasm_path.clone();
        let world_name = world.name.clone();
        let db = world.db();
        let dur = duration_ms;
        let bench_mode = bench_mode.clone();
        let cache_batch = cache_batch;
        async move {
            let t0 = Instant::now();
            let mut g = guest::Guest::load_with_env(
                wasm_path,
                db,
                &[
                    ("SIDECAR_WORLD".to_string(),              world_name.clone()),
                    ("SIDECAR_AGENT_ID".to_string(),           agent_id.clone()),
                    ("SIDECAR_AGENT_ROLE".to_string(),         role.clone()),
                    ("SIDECAR_AGENT_DURATION_MS".to_string(),  dur.to_string()),
                    ("SIDECAR_BENCH_MODE".to_string(),         bench_mode.clone()),
                    ("SIDECAR_CACHE_BATCH".to_string(),        cache_batch.to_string()),
                ],
            ).await?;
            let loaded = t0.elapsed();
            println!("[{}/{}/{}] guest loaded in {:?}", world_name, agent_id, role, loaded);
            let t0 = Instant::now();
            let bound = Duration::from_millis((dur as u64) + 3000);
            let r = tokio::time::timeout(bound, g.run_agent(&agent_id, &role, dur)).await;
            let run = t0.elapsed();
            match r {
                Ok(Ok(Ok(s))) => println!("[{}/{}/{}] DONE in {:?}: {}", world_name, agent_id, role, run, s),
                Ok(Ok(Err(e))) => println!("[{}/{}/{}] ERR in {:?}: {}", world_name, agent_id, role, run, e),
                Ok(Err(e)) => println!("[{}/{}/{}] HOST-ERR in {:?}: {}", world_name, agent_id, role, run, e),
                Err(_) => println!("[{}/{}/{}] TIMEOUT-CLEAN-EXIT in {:?}", world_name, agent_id, role, run),
            }
            drop(g);
            Ok::<_, anyhow::Error>(())
        }
    }).collect();
    let _ = join_all(agent_futures).await;
    let wall = t_start.elapsed();

    // Per-world aggregates (each world has independent DB state).
    println!("--- Phase PF per-world results ---");
    let pending_q = "[:find (count ?e) :where [?e :task/status :pending]]";
    let inprog_q  = "[:find (count ?e) :where [?e :task/status :in-progress]]";
    let done_q    = "[:find (count ?e) :where [?e :task/status :done]]";
    let total_q   = "[:find (count ?e) :where [?e :task/id _]]";
    let results_q = "[:find (count ?e) :where [?e :result/blob _]]";
    for w in &worlds {
        let db = w.db();
        let p     = db.q(pending_q, vec![]).await?;
        let ip    = db.q(inprog_q,  vec![]).await?;
        let d     = db.q(done_q,    vec![]).await?;
        let total = db.q(total_q,   vec![]).await?;
        let rc    = db.q(results_q, vec![]).await?;
        println!("[world={}] total={} pending={} in-progress={} done={} results={}",
                 w.name,
                 cbor_to_string(&total),
                 cbor_to_string(&p),
                 cbor_to_string(&ip),
                 cbor_to_string(&d),
                 cbor_to_string(&rc));
        println!("[world={}] cache: {:?}", w.name, db.cache.stats());
        println!("[world={}] {}", w.name, db.latency.report());
    }

    // Cross-contamination check: pick any two worlds' :task/id sets and
    // assert they are disjoint. Also verify each world only sees its own
    // agents (via :task/created-by ns prefix).
    if worlds.len() >= 2 {
        println!("--- cross-contamination check ---");
        for (i, wa) in worlds.iter().enumerate() {
            for wb in worlds.iter().skip(i + 1) {
                let ids_a = wa.db().q("[:find ?id :where [?e :task/id ?id]]", vec![]).await?;
                let ids_b = wb.db().q("[:find ?id :where [?e :task/id ?id]]", vec![]).await?;
                let set_a: std::collections::HashSet<String> = extract_task_id_set(&ids_a);
                let set_b: std::collections::HashSet<String> = extract_task_id_set(&ids_b);
                let inter: Vec<&String> = set_a.intersection(&set_b).collect();
                if inter.is_empty() {
                    println!("[{} ∩ {}] disjoint OK  (|{}|={}, |{}|={})",
                             wa.name, wb.name, wa.name, set_a.len(), wb.name, set_b.len());
                } else {
                    println!("[{} ∩ {}] CROSS-CONTAMINATION: {} shared ids ({:?})",
                             wa.name, wb.name, inter.len(), inter);
                }
            }
        }
    }

    println!("--- wall: {:?} ---", wall);
    Ok(())
}

/// Extract a `HashSet<String>` of task ids from a `q` response. The wire
/// shape is `{"result": [["id"], ...]}` — each row is a vector of one
/// string. Returns empty set on missing/malformed.
fn extract_task_id_set(resp: &Cbor) -> std::collections::HashSet<String> {
    let mut out = std::collections::HashSet::new();
    if let Some(Cbor::Array(rows)) = cbor_map_get(resp, "result") {
        for row in rows {
            if let Cbor::Array(cols) = row {
                if let Some(Cbor::Text(s)) = cols.first() {
                    out.insert(s.clone());
                }
            }
        }
    }
    out
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
        )
        .init();

    let args = Args::parse();
    let writer_dir = args
        .writer_dir
        .canonicalize()
        .unwrap_or(args.writer_dir.clone());

    let registry = Arc::new(WorldRegistry::new(
        writer_dir.clone(),
        args.backend.clone(),
        args.data_dir.clone(),
        args.sock_base.clone(),
    ));

    // Multi-world path.
    if args.multi_world {
        let wasm_path = args.guest_wasm.clone()
            .ok_or_else(|| anyhow!("--multi-world requires --guest-wasm"))?;
        let world_names: Vec<String> = args.worlds
            .split(',')
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty())
            .collect();
        if world_names.is_empty() {
            bail!("--worlds must list at least one world name");
        }
        run_multi_world(
            registry.clone(),
            wasm_path,
            world_names,
            args.agents_per_world,
            args.multi_duration_ms,
            args.bench_mode.clone(),
            args.cache_batch,
        ).await?;
        return Ok(());
    }

    // Single-world (default) path. The default world is just another world.
    let world = registry.get_or_spawn("default").await?;
    let db = world.db();
    install_person_schema(&db).await;

    // Phase 3 — wasm guest run (single-agent smoke), if requested.
    if let Some(wasm_path) = args.guest_wasm.clone() {
        if args.multi_agent {
            // Phase D — N=3 multi-agent inside ONE world.
            install_phase_d_schema(&db).await;
            run_multi_agent(
                wasm_path,
                db.clone(),
                args.multi_duration_ms,
                args.bench_mode.clone(),
                args.cache_batch,
            )
            .await?;
        } else {
            println!("--- Phase 3 wasm guest run ---");
            let t0 = Instant::now();
            let mut g = guest::Guest::load(wasm_path, db.clone()).await?;
            let load_dur = t0.elapsed();
            println!("guest loaded in {:?}", load_dur);
            let t0 = Instant::now();
            let r = g.run_smoke().await?;
            let run_dur = t0.elapsed();
            match r {
                Ok(s) => println!("guest run-smoke ok ({:?}): {}", run_dur, s),
                Err(e) => println!("guest run-smoke ERR ({:?}): {}", run_dur, e),
            }
            let v = db
                .q(
                    "[:find ?n ?a :where [?e :person/name ?n] [?e :person/age ?a]]",
                    vec![],
                )
                .await?;
            println!("post-guest q on host side: {}", cbor_to_string(&v));
        }
    }

    if args.smoke {
        smoke_test(&db).await
    } else if args.guest_wasm.is_none() {
        run_repl(db).await
    } else {
        // guest-wasm ran already (smoke or multi-agent); don't drop into the REPL.
        Ok(())
    }
}
