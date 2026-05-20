// placeholder.mjs — stand-in for `seon/out/client/main.js` until B-5 wires
// the real CLJS release bundle.
//
// Used by Lane B chunks B-1..B-4 (Cargo workspace, bin/build-pod end-to-end,
// wasmtime instantiation, Tauri shell launch) so they don't block on Lane A.
// At B-5 the real seon release bundle takes over: `bin/build-pod` builds
// seon, emits `seon/out/client/main.mjs` (an ESM shim that imports the CJS
// bundle and re-exports off globalThis), and feeds THAT to wasm-rquickjs.
// Pointed at this file via `wasm-rquickjs --js placeholder.mjs` until then.
//
// Names match the WIT world's exports (src-wit/seon-pod.wit). wasm-rquickjs
// maps WIT kebab-case → ESM camelCase, so `get-ui-port` ↔ `getUiPort`.

export const getUiPort = () => 42;

export const evalForm = (agentId, form, ns) => ({
  tag: "ok",
  val: { evalId: "placeholder", ok: true, valueEdn: "nil", error: null },
});

export const query = (agentId, datalog) => ({
  tag: "err",
  val: { tag: "runtime-error", val: "placeholder.mjs has no DB" },
});

export const triggerTurn = (agentId) => ({
  tag: "err",
  val: { tag: "runtime-error", val: "placeholder.mjs has no agent loop" },
});

export const injectMessage = (agentId, content, role) => ({
  tag: "ok",
  val: "placeholder-message-id",
});

export const inspectAgent = (agentId) => ({
  tag: "ok",
  val: {
    agentId,
    turnCount: 0,
    state: "idle",
    cancelled: false,
    renderedCtx: "placeholder context",
  },
});

export const interrupt = (agentId) => ({ tag: "ok", val: null });

export const shutdown = () => undefined;
