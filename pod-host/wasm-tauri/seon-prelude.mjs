// seon-prelude.mjs — load-order prelude. Must execute BEFORE
// seon/main is imported. Sets the few Node-style globals that
// shadow-cljs-emitted CLJS code reads at namespace-load time.
//
// Loaded as a sibling --js-modules entry; eval-smoke.mjs lists it
// first in its import order so ES-module dep-graph ordering runs
// this file before seon/main.

// shadow.cljs.bootstrap.node has a top-level defonce that calls
// `(path/resolve js/__dirname "bootstrap")` even though we override
// :path when we call boot/init. Value doesn't matter; just needs
// to be a defined string so path.resolve doesn't throw at load.
globalThis.__dirname = '/';
globalThis.__filename = '/seon-pod.js';

// Process shim — shadow's runtime probes `process.env.NODE_DEBUG`
// and similar at load time. wasm-rquickjs provides a `process` shim
// but be defensive in case it's gated by feature flag.
if (typeof globalThis.process === 'undefined') {
  globalThis.process = { env: {}, argv: [], platform: 'wasi' };
}
