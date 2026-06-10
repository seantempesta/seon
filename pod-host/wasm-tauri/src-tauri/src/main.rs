// seon — Tauri desktop shell for the Seon mission-control UI (demo 2026-06-12).
//
// Launch sequence:
//   1. Show the splash window (defined in tauri.conf.json, serves ui/index.html).
//   2. Off the main thread: run `bin/seon start all` (idempotent, socket-gated;
//      starts cljs-watch → wire-server → pod, each gated on readiness).
//   3. Poll the pod gate (GET http://127.0.0.1:7890/agents → 200).
//   4. On the main thread: open the main window on the mission-control UI,
//      close the splash.
//
// On quit the stack is deliberately LEFT RUNNING — the daemons are spawned by
// bin/seon (nohup, not our children), so closing the window never kills the
// agents mid-demo. `bin/seon stop` stays a manual terminal act.

use std::io::{Read, Write};
use std::net::TcpStream;
use std::path::PathBuf;
use std::process::Command;
use std::time::Duration;

use tauri::Manager;

const MISSION_CONTROL_URL: &str = "http://127.0.0.1:7890/";
const GATE_ADDR: &str = "127.0.0.1:7890";
const GATE_PATH: &str = "/agents";
/// 240 × 500ms = up to 2 minutes for a cold start (cljs-watch first build).
const GATE_ATTEMPTS: u32 = 240;
const GATE_INTERVAL: Duration = Duration::from_millis(500);

/// Repo root: `SEON_ROOT` env override, else compile-time
/// CARGO_MANIFEST_DIR (…/pod-host/wasm-tauri/src-tauri) → three levels up.
/// Compile-time is fine for the demo: dev runs and the debug bundle both
/// live on this machine.
fn repo_root() -> PathBuf {
    if let Ok(root) = std::env::var("SEON_ROOT") {
        return PathBuf::from(root);
    }
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../..")
}

/// Run `bin/seon start all` and wait for it. Idempotent — a fast no-op when
/// the stack is already up. Failure is non-fatal: we still poll the gate
/// (the stack may already be running even if the script errored).
fn ensure_stack_up() {
    let root = repo_root();
    let script = root.join("bin/seon");
    match Command::new(&script)
        .args(["start", "all"])
        .current_dir(&root)
        .status()
    {
        Ok(status) => eprintln!("[seon-shell] bin/seon start all → {status}"),
        Err(e) => eprintln!("[seon-shell] failed to invoke {}: {e}", script.display()),
    }
}

/// One raw HTTP/1.0 probe of the pod gate. True iff the status line is 200.
/// (Raw TcpStream keeps the shell dependency-free — no HTTP client crate.)
fn gate_ready() -> bool {
    let addr = match GATE_ADDR.parse() {
        Ok(a) => a,
        Err(_) => return false,
    };
    let Ok(mut stream) = TcpStream::connect_timeout(&addr, Duration::from_millis(500)) else {
        return false;
    };
    let _ = stream.set_read_timeout(Some(Duration::from_secs(2)));
    let _ = stream.set_write_timeout(Some(Duration::from_secs(2)));
    let request = format!("GET {GATE_PATH} HTTP/1.0\r\nHost: {GATE_ADDR}\r\n\r\n");
    if stream.write_all(request.as_bytes()).is_err() {
        return false;
    }
    let mut buf = [0u8; 64];
    let Ok(n) = stream.read(&mut buf) else {
        return false;
    };
    String::from_utf8_lossy(&buf[..n]).contains(" 200 ")
}

fn set_splash_status(handle: &tauri::AppHandle, text: &str) {
    if let Some(splash) = handle.get_webview_window("splash") {
        let js = format!(
            "document.getElementById('status').textContent = {};",
            serde_json::to_string(text).unwrap_or_else(|_| "'…'".into())
        );
        let _ = splash.eval(&js);
    }
}

/// Open the main window on the mission-control UI and close the splash.
/// Must run on the main thread (macOS window-creation rule).
fn open_main_window(handle: &tauri::AppHandle) {
    let url: tauri::Url = MISSION_CONTROL_URL.parse().expect("static URL parses");
    match tauri::WebviewWindowBuilder::new(handle, "main", tauri::WebviewUrl::External(url))
        .title("Seon")
        .inner_size(1400.0, 900.0)
        .build()
    {
        Ok(_) => {
            if let Some(splash) = handle.get_webview_window("splash") {
                let _ = splash.close();
            }
        }
        Err(e) => {
            eprintln!("[seon-shell] failed to open main window: {e}");
            set_splash_status(handle, &format!("failed to open main window: {e}"));
        }
    }
}

fn main() {
    tauri::Builder::default()
        .setup(|app| {
            let handle = app.handle().clone();
            std::thread::spawn(move || {
                set_splash_status(&handle, "ensuring the stack is up (bin/seon start all)…");
                ensure_stack_up();

                set_splash_status(&handle, "waiting for the pod gate (/agents)…");
                let mut ready = false;
                for _ in 0..GATE_ATTEMPTS {
                    if gate_ready() {
                        ready = true;
                        break;
                    }
                    std::thread::sleep(GATE_INTERVAL);
                }

                if ready {
                    let h = handle.clone();
                    let _ = handle.run_on_main_thread(move || open_main_window(&h));
                } else {
                    set_splash_status(
                        &handle,
                        "pod gate never came up — check `bin/seon status` / logs/pod.log",
                    );
                }
            });
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running the Seon shell");
}
