// server.rs — the rmcp-backed MCP server. Each `#[tool]` method dispatches
// to a Pod WIT export.
//
// Spec-05 §8.2 names six WIT-backed tools + one local synthesis tool
// (`list-agents`). B-6 lands all six WIT-backed ones; `list-agents` is
// deferred to a follow-up (needs an agent-discovery mechanism that doesn't
// exist in the WIT world yet — the inspect-agent surface takes an id).

use std::sync::Arc;

use rmcp::ServerHandler;
use rmcp::handler::server::router::tool::ToolRouter;
use rmcp::handler::server::wrapper::Parameters;
use rmcp::model::{CallToolResult, Content, ServerInfo, ServerCapabilities, Implementation};
use rmcp::{tool, tool_handler, tool_router};
use schemars::JsonSchema;
use serde::{Deserialize, Serialize};
use tokio::sync::Mutex;

use seon_tauri::pod::{MessageRole, Pod};

#[derive(Clone)]
pub struct SeonServer {
    pod:          Arc<Mutex<Pod>>,
    tool_router:  ToolRouter<Self>,
}

#[tool_handler(router = self.tool_router)]
impl ServerHandler for SeonServer {
    fn get_info(&self) -> ServerInfo {
        ServerInfo::new(ServerCapabilities::builder().enable_tools().build())
            .with_server_info(Implementation::from_build_env())
            .with_instructions(
                "Seon WASM-pod bridge (V0.5). Each tool takes `agent_id` as \
                 its first argument so a single pod can host many agents.",
            )
    }
}

impl SeonServer {
    pub fn new(pod: Arc<Mutex<Pod>>) -> Self {
        Self { pod, tool_router: Self::tool_router() }
    }
}

// ---------------------------------------------------------------------------
// Tool param structs.
// ---------------------------------------------------------------------------

#[derive(Debug, Serialize, Deserialize, JsonSchema)]
pub struct EvalParams {
    /// Agent whose namespace + DB scopes the eval.
    pub agent_id: String,
    /// Clojure form to evaluate.
    pub code: String,
    /// Namespace to eval inside. Defaults to `seon.agent.<agent_id>`.
    #[serde(default)]
    pub ns: Option<String>,
}

#[derive(Debug, Serialize, Deserialize, JsonSchema)]
pub struct ChatParams {
    pub agent_id: String,
    /// Free-form text sent as a `:user` message.
    pub text: String,
}

#[derive(Debug, Serialize, Deserialize, JsonSchema)]
pub struct QueryParams {
    pub agent_id: String,
    /// Datalog query as a `pr-str`-shaped Clojure data literal.
    pub datalog: String,
}

#[derive(Debug, Serialize, Deserialize, JsonSchema)]
pub struct AgentIdParams {
    pub agent_id: String,
}

// ---------------------------------------------------------------------------
// Tool methods. Each one acquires the pod mutex (serializes wasmtime calls;
// wasmtime Store is single-threaded), dispatches the WIT export, and emits a
// human-readable text content block plus a structured JSON result.
// ---------------------------------------------------------------------------

#[tool_router(router = tool_router)]
impl SeonServer {
    /// Eval a Clojure form in the agent's home namespace.
    #[tool(
        name        = "eval",
        description = "Eval a Clojure form in the agent's home namespace. \
                       `ns` defaults to seon.agent.<agent_id>.",
    )]
    pub async fn eval(&self, Parameters(p): Parameters<EvalParams>) -> CallToolResult {
        let ns = p.ns.unwrap_or_else(|| format!("seon.agent.{}", p.agent_id));
        let mut pod = self.pod.lock().await;
        match pod.call_eval_form_async(&p.agent_id, &p.code, &ns).await {
            Ok(Ok(res)) => text_result(serde_json::json!({
                "eval_id":   res.eval_id,
                "ok":        res.ok,
                "value_edn": res.value_edn,
                "error":     res.error.map(|e| format!("{e:?}")),
            })),
            Ok(Err(host_err)) => text_error(format!("pod host-side error: {host_err}")),
            Err(trap) => text_error(format!("wasmtime trap: {trap}")),
        }
    }

    /// Inject a `:user` message into the agent's inbox; agent loop fires.
    #[tool(
        name        = "chat",
        description = "Inject a user message into the agent's inbox. The agent's \
                       turn loop fires asynchronously; use `inspect` to poll the \
                       rendered ctx.",
    )]
    pub async fn chat(&self, Parameters(p): Parameters<ChatParams>) -> CallToolResult {
        let mut pod = self.pod.lock().await;
        match pod
            .call_inject_message_async(&p.agent_id, &p.text, MessageRole::User)
            .await
        {
            Ok(Ok(message_id)) => text_result(serde_json::json!({ "message_id": message_id })),
            Ok(Err(err)) => text_error(format!("inject-message failed: {err}")),
            Err(trap) => text_error(format!("wasmtime trap: {trap}")),
        }
    }

    /// Run a Datalog query against the pod's DB, scoped to `agent_id`.
    #[tool(
        name        = "query",
        description = "Run a Datalog query against the pod's DB scoped to agent_id.",
    )]
    pub async fn query(&self, Parameters(p): Parameters<QueryParams>) -> CallToolResult {
        let mut pod = self.pod.lock().await;
        match pod.call_query_async(&p.agent_id, &p.datalog).await {
            Ok(Ok(qr)) => text_result(serde_json::json!({ "rows_edn": qr.rows_edn })),
            Ok(Err(db_err)) => text_error(format!("query db-error: {db_err:?}")),
            Err(trap) => text_error(format!("wasmtime trap: {trap}")),
        }
    }

    /// Kick one turn of the agent's loop without injecting a message.
    #[tool(
        name        = "trigger",
        description = "Kick one turn of the agent's loop without injecting a message.",
    )]
    pub async fn trigger(&self, Parameters(p): Parameters<AgentIdParams>) -> CallToolResult {
        let mut pod = self.pod.lock().await;
        match pod.call_trigger_turn_async(&p.agent_id).await {
            Ok(Ok(report)) => text_result(serde_json::json!({
                "turn_n":   report.turn_n,
                "forms":    report.forms,
                "eval_ids": report.eval_ids,
            })),
            Ok(Err(run_err)) => text_error(format!("trigger-turn run-error: {run_err:?}")),
            Err(trap) => text_error(format!("wasmtime trap: {trap}")),
        }
    }

    /// Snapshot of the agent (turn count, state, rendered ctx).
    #[tool(
        name        = "inspect",
        description = "Snapshot of the agent: turn count, idle/running state, \
                       cancellation flag, and the agent's rendered ctx string.",
    )]
    pub async fn inspect(&self, Parameters(p): Parameters<AgentIdParams>) -> CallToolResult {
        let mut pod = self.pod.lock().await;
        match pod.call_inspect_agent_async(&p.agent_id).await {
            Ok(Ok(snap)) => text_result(serde_json::json!({
                "agent_id":     snap.agent_id,
                "turn_count":   snap.turn_count,
                "state":        format!("{:?}", snap.state).to_ascii_lowercase(),
                "cancelled":    snap.cancelled,
                "rendered_ctx": snap.rendered_ctx,
            })),
            Ok(Err(err)) => text_error(format!("inspect-agent failed: {err}")),
            Err(trap) => text_error(format!("wasmtime trap: {trap}")),
        }
    }

    /// Cancel the agent's in-flight turn.
    #[tool(
        name        = "interrupt",
        description = "Cancel the agent's in-flight turn. No-op if the agent is idle.",
    )]
    pub async fn interrupt(&self, Parameters(p): Parameters<AgentIdParams>) -> CallToolResult {
        let mut pod = self.pod.lock().await;
        match pod.call_interrupt_async(&p.agent_id).await {
            Ok(Ok(())) => text_result(serde_json::json!({ "interrupted": p.agent_id })),
            Ok(Err(err)) => text_error(format!("interrupt failed: {err}")),
            Err(trap) => text_error(format!("wasmtime trap: {trap}")),
        }
    }
}

fn text_result(body: serde_json::Value) -> CallToolResult {
    let text = serde_json::to_string_pretty(&body).unwrap_or_else(|_| body.to_string());
    CallToolResult::success(vec![Content::text(text)])
}

fn text_error(msg: String) -> CallToolResult {
    CallToolResult::error(vec![Content::text(msg)])
}
