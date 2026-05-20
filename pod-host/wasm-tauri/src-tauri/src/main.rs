// seon-tauri — V0.5 desktop host (spec-05 Lane B).
//
// B-1 skeleton. The real shape lands in:
//   - B-3: pod.rs (wasmtime lifecycle) + http.rs (OutgoingHost allowlist gate)
//   - B-4: this file becomes the sync `main()` with off-thread pod-start
//          (spec-05 §7.2 — three concurrency rules)

fn main() {
    eprintln!("seon-tauri B-1 stub — Tauri shell lands in B-4");
}
