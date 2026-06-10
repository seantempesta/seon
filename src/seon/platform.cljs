(ns seon.platform
  "Runtime host detection. Returns `:node` when running under Node.js
   (Lane A dev path), `:wasi` when running inside wasm-rquickjs on
   wasmtime (Lane B prod path, V0.5).

   The detection is a feature-sniff for `process.versions.node` —
   present in real Node, absent under wasm-rquickjs (which doesn't
   expose Node's process shim).

   Used by `seon.agent.fs` to dispatch between Node native APIs and WASI
   filesystem interfaces. Consumers can also call `(host)` from ctx
   examples to tell the agent what file-access surface it actually
   has on a given host.")

(defn host
  "Return the runtime host as a keyword.
     :node — plain Node.js
     :wasi — wasm-rquickjs under wasmtime (V0.5 + V1+ prod)

   Detection: presence of `globalThis.process.versions.node`. Safe to
   call from any thread / any moment after JS boot."
  {:malli/schema [:=> [:cat] [:enum :node :wasi]]}
  []
  (let [proc (.. js/globalThis -process)
        versions (some-> proc .-versions)
        node-ver (some-> versions .-node)]
    (if (some? node-ver) :node :wasi)))

(defn node?
  "True when running under plain Node.js."
  {:malli/schema [:=> [:cat] :boolean]}
  []
  (= :node (host)))

(defn wasi?
  "True when running inside wasm-rquickjs / wasmtime."
  {:malli/schema [:=> [:cat] :boolean]}
  []
  (= :wasi (host)))
