// eval-smoke.mjs — ESM shim for M2 self-hosted-CLJS smoke.
//
// wasm-rquickjs wraps this file (--js); the actual bundle is
// out/eval-smoke/main.js (CJS, :node-script + :simple).
//
// seon.wasm-eval-smoke installs `seonEvalInitBootstrap` and
// `seonEvalForm` on globalThis as a load-time side-effect of importing
// main.js. We re-export them under the kebab→camel mapping that
// wasm-rquickjs expects: WIT `init-bootstrap` ↔ JS `initBootstrap`,
// WIT `eval-form` ↔ JS `evalForm`.
//
// Both functions return Promises that resolve to pr-str'd EDN strings.
// wasm-rquickjs handles the Promise → WIT-string bridge via its async
// runtime (wstd). Native CLJS async/await (1.12.145+) means the
// underlying eval path uses microtasks, not core.async setTimeout
// parking — wstd handles microtasks cleanly.

// IMPORTANT: prelude must import first. ES-module dep-graph order
// guarantees that's the execution order, so the globals it sets
// (__dirname, process) are in place before seon/main loads. Inline
// `globalThis.X = ...` at the top of THIS file does NOT work — ES
// imports are hoisted above top-level code.
import 'seon/prelude';

// `seon/main` is registered as an embedded virtual module via
// `--js-modules seon/main=<path>` on wasm-rquickjs generate. The
// underlying file (out/eval-smoke/main.js) gets copied into the
// generated wrapper crate at build time, then loaded by QuickJS's
// BuiltinLoader at runtime. Self-contained — no WASI filesystem
// access required to find the bundle itself.
import 'seon/main';

export const initBootstrap = async () => {
  if (typeof globalThis.seonEvalInitBootstrap !== 'function') {
    return `{:ok false :error "globalThis.seonEvalInitBootstrap missing, got ${typeof globalThis.seonEvalInitBootstrap}"}`;
  }
  return await globalThis.seonEvalInitBootstrap();
};

export const evalForm = async (form) => {
  if (typeof globalThis.seonEvalForm !== 'function') {
    return `{:ok false :error "globalThis.seonEvalForm missing, got ${typeof globalThis.seonEvalForm}"}`;
  }
  return await globalThis.seonEvalForm(form);
};

export const evalBatch = async (source) => {
  if (typeof globalThis.seonEvalBatch !== 'function') {
    return `{:ok false :error "globalThis.seonEvalBatch missing, got ${typeof globalThis.seonEvalBatch}"}`;
  }
  return await globalThis.seonEvalBatch(source);
};
