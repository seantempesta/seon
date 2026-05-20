// http.rs — outbound HTTPS allowlist (spec-05 §7.5).
//
// V0.5 design: the host implements `wasmtime_wasi_http::p2::WasiHttpView`
// AND overrides the generated `outgoing_handler::Host::handle` to gate by
// authority before delegating to wasmtime's default sender. This file holds
// the allowlist data structure and helpers; the actual `OutgoingHost` impl
// lives next to the wasmtime store-state in `pod.rs` (it has to share the
// `SeonStore` type with the rest of the host traits, so co-locating with
// the state declaration is cleaner).
//
// B-3 lands the type. B-5 wires the real gate + the `capability-prompt`
// fallback for unknown hosts.

use std::collections::HashSet;

/// Outbound-HTTPS allowlist. Boot-time seeds (DeepSeek, Vertex auth, etc.)
/// go in via [`HttpAllowlist::allow_host`]; runtime "user said allow"
/// decisions extend the set through the same path. The lifted dogfooding
/// mode (spec §14.3) installs an alternate handler that bypasses this
/// check entirely — but the allowlist data structure itself stays.
#[derive(Debug, Default)]
pub struct HttpAllowlist {
    /// Bare hostname (no port) → permitted. Empty in V0.5 locked-down boot;
    /// callers seed via `with_http_allow_host` in the Pod builder.
    hosts: HashSet<String>,
}

impl HttpAllowlist {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn allow_host(&mut self, host: impl Into<String>) {
        self.hosts.insert(host.into());
    }

    pub fn contains(&self, host: &str) -> bool {
        self.hosts.contains(host)
    }
}
