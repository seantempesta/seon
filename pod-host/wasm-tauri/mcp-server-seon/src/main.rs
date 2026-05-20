// mcp-server-seon — MCP-over-stdio bridge to the seon-pod WASM (spec-05 §8.2).
//
// V0.5 / B-6 shape: this binary embeds `seon_tauri::pod::Pod` in-process.
// Claude Code spawns it per `.mcp.json` and the binary routes each
// `mcp__seon__<tool>` call to the matching WIT export. Each tool's first
// argument is `agent_id` so a single pod can host many agents.
//
// Why in-process and not over the Tauri-side UDS the spec originally drew?
// B-4 (Tauri shell) hasn't landed, so there's no UDS to talk to yet. The
// in-process variant is exactly the same surface (the WIT export signatures),
// just dialed directly into the pod from inside the binary. When Tauri lands
// + Lane A's seon bundle arrives, we add a `--connect <uds>` mode that swaps
// the in-process Pod for a UDS client.
//
// Run shape:
//   mcp-server-seon --pod-wasm path/to/seon_pod.wasm
//
// The wasm path defaults to seon/pod-build/target/wasm32-wasip2/release/seon_pod.wasm
// (B-2's output) so `bin/build-pod --placeholder && cargo run -p mcp-server-seon`
// just works.

mod server;

use std::path::PathBuf;
use std::sync::Arc;

use anyhow::{Context, Result};
use clap::Parser;
use rmcp::ServiceExt;
use rmcp::transport::io::stdio;
use tokio::sync::Mutex;
use tracing_subscriber::EnvFilter;

use seon_tauri::pod::Pod;

use crate::server::SeonServer;

#[derive(Debug, Parser)]
#[command(name = "mcp-server-seon")]
#[command(about = "MCP-over-stdio bridge to the seon-pod WASM (V0.5)")]
struct Args {
    /// Path to the seon_pod.wasm artifact (B-2 output).
    #[arg(long, env = "SEON_POD_WASM")]
    pod_wasm: Option<PathBuf>,

    /// Preopen `host:guest` directory pair (repeatable). Defaults to
    /// `~/.seon/db:/db` if no entries are passed.
    #[arg(long = "preopen", value_name = "HOST:GUEST")]
    preopens: Vec<String>,

    /// Outbound-HTTPS host to allow (repeatable). Defaults to
    /// `api.deepseek.com` if no entries are passed.
    #[arg(long = "allow-host", value_name = "HOST")]
    allow_hosts: Vec<String>,
}

fn default_pod_wasm() -> PathBuf {
    // mcp-server-seon's CARGO_MANIFEST_DIR is seon/mcp-server-seon/;
    // the placeholder pod artifact sits one level up.
    std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .expect("workspace parent")
        .join("pod-build/target/wasm32-wasip2/release/seon_pod.wasm")
}

fn parse_preopen(s: &str) -> Result<(PathBuf, String)> {
    let (host, guest) = s
        .split_once(':')
        .with_context(|| format!("--preopen expects HOST:GUEST, got `{s}`"))?;
    let host = shellexpand::tilde(host).into_owned();
    Ok((PathBuf::from(host), guest.to_string()))
}

#[tokio::main(flavor = "multi_thread")]
async fn main() -> Result<()> {
    // MCP is JSON over stdio — all logs go to stderr, never stdout.
    tracing_subscriber::fmt()
        .with_writer(std::io::stderr)
        .with_env_filter(
            EnvFilter::try_from_env("SEON_MCP_LOG")
                .unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .init();

    let args = Args::parse();

    let pod_wasm = args.pod_wasm.unwrap_or_else(default_pod_wasm);
    if !pod_wasm.exists() {
        anyhow::bail!(
            "pod wasm not found at {} — run `bin/build-pod --placeholder` (or pass --pod-wasm)",
            pod_wasm.display()
        );
    }

    let preopens: Vec<(PathBuf, String)> = if args.preopens.is_empty() {
        vec![(
            PathBuf::from(shellexpand::tilde("~/.seon/db").into_owned()),
            "/db".into(),
        )]
    } else {
        args.preopens
            .iter()
            .map(|s| parse_preopen(s))
            .collect::<Result<Vec<_>>>()?
    };

    let allow_hosts: Vec<String> = if args.allow_hosts.is_empty() {
        vec!["api.deepseek.com".into()]
    } else {
        args.allow_hosts.clone()
    };

    tracing::info!(
        ?pod_wasm,
        preopens = ?preopens,
        allow_hosts = ?allow_hosts,
        "starting seon pod"
    );

    let mut builder = Pod::new(&pod_wasm);
    for (host_path, guest_path) in &preopens {
        builder = builder.with_preopen_dir(host_path, guest_path);
    }
    for host in &allow_hosts {
        builder = builder.with_http_allow_host(host);
    }
    let pod = builder
        .start_async()
        .await
        .map_err(|e| anyhow::anyhow!("Pod::start_async failed: {e}"))?;

    let server = SeonServer::new(Arc::new(Mutex::new(pod)));

    tracing::info!("MCP server ready on stdio");
    let (stdin, stdout) = stdio();
    let running = server
        .serve((stdin, stdout))
        .await
        .context("MCP handshake failed")?;
    running
        .waiting()
        .await
        .context("MCP server loop exited with error")?;

    Ok(())
}
