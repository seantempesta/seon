// smoke.mjs — ESM shim for M1 cljs.js smoke. wasm-rquickjs wraps this
// file; the actual bundle is out/smoke/main.js (CJS).
//
// seon/wasm_smoke.cljs assigns smoke! to globalThis.seonSmoke as a
// load-time side-effect. Importing main.js triggers that assignment.
//
// Returns: pr-str'd EDN string of {:status :pass :datoms 6 :rows ...}
// or {:status :fail ...}.

import '../../out/smoke/main.js';

export const smoke = async () => {
  if (typeof globalThis.seonSmoke !== 'function') {
    return `{:status :fail :reason "globalThis.seonSmoke missing, got ${typeof globalThis.seonSmoke}"}`;
  }
  return await globalThis.seonSmoke();
};
