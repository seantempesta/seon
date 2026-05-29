// agent.mjs — ESM shim for the client-runtime CLJS guest agent.
//
// Two responsibilities:
// 1. Import the WIT-bound `seon:client-runtime/db@0.1.0` host fns and stash them on
//    `globalThis.__seon_client_runtime_db` so the CLJS overlay (seon.client-runtime.wit)
//    finds them via its globalThis fallback path (the overlay's primary
//    `js/require` path doesn't work under wasm-rquickjs because the WIT
//    module isn't a CommonJS module).
// 2. Import the seon CLJS bundle (registered as the virtual module
//    `client-runtime/main`), which installs `globalThis.clientRuntimeAgentRun` and
//    `globalThis.clientRuntimeAgentSmoke` as a side effect.
//
// Exports:
//   - runAgent(agentId, role, durationMs) -> Promise<string>  ; WIT run-agent
//   - runSmoke() -> Promise<string>                            ; WIT run-smoke

// Step 1 — import the host imports first.
import {
  q,
  transact,
  pull,
  entityPull,
  pullMany,
  schema,
  reverseSchema,
  dbFilter,
  qFiltered,
  filterRelease,
  subscribeTx,
  unsubscribeTx,
  nextTxEvent,
} from "seon:client-runtime/db@0.1.0";

// Stash on globalThis. The overlay's wit-mod fallback reads from
// `globalThis.__seon_client_runtime_db`, so this must be assigned before any CLJS
// code that requires seon.client-runtime.wit runs.
globalThis.__seon_client_runtime_db = {
  q,
  transact,
  pull,
  "entity-pull":     entityPull,
  "pull-many":       pullMany,
  schema,
  "reverse-schema":  reverseSchema,
  "db-filter":       dbFilter,
  "q-filtered":      qFiltered,
  "filter-release":  filterRelease,
  "subscribe-tx":    subscribeTx,
  "unsubscribe-tx":  unsubscribeTx,
  "next-tx-event":   nextTxEvent,
};

// Minimal Node globals shadow-cljs's :node-script wrapper probes at load.
globalThis.__dirname = "/";
globalThis.__filename = "/agent.js";
if (typeof globalThis.process === "undefined") {
  globalThis.process = { env: {}, argv: [], platform: "wasi" };
}
// Bridge wasi env into process.env so the agent can read SEON_AGENT_ID etc.
// wasm-rquickjs's `process` shim normally does this when the `process` feature
// is enabled, but our --no-default-features build disables that to avoid the
// wasi:logging linker pull. The host already wires wasi:cli/environment via
// WasiCtxBuilder.env(), so we read it here through a minimal probe.
//
// Heuristic: if wasm-rquickjs's process shim populated env, use it; otherwise
// fall back to the empty map. The shim layers correctly when `process`
// feature is on.
if (
  !globalThis.process.env ||
  Object.keys(globalThis.process.env).length === 0
) {
  // Try a runtime-provided wasi env getter if exposed.
  try {
    const wasiEnv = globalThis.__wasi_get_environment;
    if (typeof wasiEnv === "function") {
      globalThis.process.env = wasiEnv() || {};
    }
  } catch (e) {
    // ignore
  }
}

// Step 2 — import the CLJS bundle. ES dep-graph order guarantees the
// globalThis assignments above run first.
import "client-runtime/main";

export const runAgent = async (agentId, role, durationMs) => {
  if (typeof globalThis.clientRuntimeAgentRun !== "function") {
    return `{:ok false :error "globalThis.clientRuntimeAgentRun missing, got ${typeof globalThis.clientRuntimeAgentRun}"}`;
  }
  try {
    return await globalThis.clientRuntimeAgentRun(agentId, role, durationMs);
  } catch (e) {
    const msg = e && e.message ? e.message : String(e);
    return `{:ok false :error ${JSON.stringify(msg)}}`;
  }
};

export const runSmoke = async () => {
  if (typeof globalThis.clientRuntimeAgentSmoke !== "function") {
    return `{:ok false :error "globalThis.clientRuntimeAgentSmoke missing"}`;
  }
  try {
    return await globalThis.clientRuntimeAgentSmoke();
  } catch (e) {
    const msg = e && e.message ? e.message : String(e);
    return `{:ok false :error ${JSON.stringify(msg)}}`;
  }
};
