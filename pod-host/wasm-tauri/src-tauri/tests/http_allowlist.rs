// http_allowlist.rs — Phase A smoke for the outbound-HTTPS allowlist override.
//
// Implements the unit-test variant from
// `docs/prds/agent-runtime/research/capability-surface-2026-05-22.md`
// §"Phase A: HTTPS allowlist override + smoke." A full end-to-end smoke
// (loading eval-smoke.wasm and invoking `(js/fetch ...)`) is intentionally
// out of scope here — that requires `bin/build-eval-smoke` to have produced
// the component artifact, which `cargo test` does not orchestrate. Instead we
// exercise `SeonHttpHooks::send_request` directly with a stubbed
// `hyper::Request`, asserting:
//   - host in allowlist  → `Ok(_)` (delegates to default_send_request)
//   - host NOT in list   → `Err(HttpError)` whose contained ErrorCode is
//                          `ErrorCode::HttpRequestDenied`
//
// Per research note §2.B.1, this is the URL-filtering hook the pod relies on
// to gate fetch() from CLJS before bytes hit hyper.

use std::time::Duration;

use bytes::Bytes;
use http_body_util::{BodyExt, Empty};
use wasmtime_wasi_http::p2::bindings::http::types::ErrorCode;
use wasmtime_wasi_http::p2::body::HyperOutgoingBody;
use wasmtime_wasi_http::p2::types::OutgoingRequestConfig;
use wasmtime_wasi_http::p2::WasiHttpHooks;

use seon_tauri::http::HttpAllowlist;
use seon_tauri::pod::SeonHttpHooks;

fn empty_body() -> HyperOutgoingBody {
    Empty::<Bytes>::new()
        .map_err(|_| unreachable!("Infallible"))
        .boxed_unsync()
}

fn request(uri: &str) -> hyper::Request<HyperOutgoingBody> {
    hyper::Request::builder()
        .method(hyper::Method::GET)
        .uri(uri)
        .body(empty_body())
        .expect("valid request")
}

fn config() -> OutgoingRequestConfig {
    OutgoingRequestConfig {
        use_tls:               true,
        connect_timeout:       Duration::from_secs(5),
        first_byte_timeout:    Duration::from_secs(5),
        between_bytes_timeout: Duration::from_secs(5),
    }
}

/// Host present in the allowlist → `send_request` returns Ok with a future,
/// indicating it would have delegated to hyper had the test polled.
#[tokio::test(flavor = "multi_thread")]
async fn allowed_host_returns_ok() {
    let mut allowed = HttpAllowlist::new();
    allowed.allow_host("httpbin.org");
    let mut hooks = SeonHttpHooks::new(allowed);

    let result = hooks.send_request(request("https://httpbin.org/get"), config());

    assert!(
        result.is_ok(),
        "allowed host should return Ok(future), got {:?}",
        result.err().map(|e| format!("{e}"))
    );
    // We don't `.await` the future — it would actually open a TCP connection.
    // The Ok branch is enough to confirm the gate let it through.
    drop(result);
}

/// Host NOT in the allowlist → `send_request` returns Err whose downcast is
/// `ErrorCode::HttpRequestDenied`. This is the "default-deny" contract the
/// research note specifies.
#[tokio::test(flavor = "multi_thread")]
async fn denied_host_returns_http_request_denied() {
    let mut allowed = HttpAllowlist::new();
    allowed.allow_host("httpbin.org");
    let mut hooks = SeonHttpHooks::new(allowed);

    let err = hooks
        .send_request(request("https://example.com/"), config())
        .err()
        .expect("denied host must return Err");

    let code: ErrorCode = err
        .downcast()
        .expect("HttpError must downcast to ErrorCode");
    assert!(
        matches!(code, ErrorCode::HttpRequestDenied),
        "expected HttpRequestDenied, got {code:?}"
    );
}

/// Empty allowlist denies everything — the default-deny posture at boot.
#[tokio::test(flavor = "multi_thread")]
async fn empty_allowlist_denies_all() {
    let mut hooks = SeonHttpHooks::new(HttpAllowlist::new());

    let err = hooks
        .send_request(request("https://httpbin.org/get"), config())
        .err()
        .expect("empty allowlist must deny");

    let code: ErrorCode = err.downcast().expect("downcast to ErrorCode");
    assert!(matches!(code, ErrorCode::HttpRequestDenied));
}
