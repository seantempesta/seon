// seon-tauri library crate.
//
// Wasmtime-side lifecycle + host capability impls + WIT bindings for the
// seon-pod world. The Tauri binary in src/main.rs consumes this library.
//
// Spec-05 §7 lays out the module structure:
//   - pod.rs        wasmtime lifecycle, `bindgen!` invocation, host impls
//   - http.rs       OutgoingHost allowlist gate (B-5+ full impl)
//   - capability.rs native prompt impl (B-4+)
//   - fs.rs / mcp.rs ad-hoc host trait impls (B-5+)
//
// B-3 lands pod.rs (wasmtime + stub host impls) + http.rs (skeleton).

pub mod http;
pub mod pod;
