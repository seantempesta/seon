(ns seon.wasm-eval-smoke
  "Milestone-2 smoke entry for the WASM pod — arbitrary CLJS eval.

   STATUS 2026-05-21: NEW. Sibling of wasm_smoke.cljs. Where M1 proves
   datahike-cljs loads under wasm-rquickjs / wasmtime, M2 proves the
   bootstrap-CLJS compiler (cljs.js + shadow.cljs.bootstrap.node) works
   inside the same container — the prerequisite for live agent eval
   inside the WASM pod.

   Deliberately narrow: requires ONLY cljs.js + the bootstrap loader.
   No seon.eval, seon.db, seon.error — those layer concerns (timeouts,
   error-shaping, agent ns setup) on top of cljs.js. If something fails
   here, it's bootstrap-or-cljs.js, not seon.

   Three functions installed on globalThis:
     seonEvalInitBootstrap : () -> Promise<string>            ; \"ok\" or error EDN
     seonEvalForm          : (form) -> Promise<string>        ; pr-str'd EDN
     seonEvalBatch         : (source) -> Promise<string>      ; pr-str'd EDN vector

   Bootstrap artifacts (out/bootstrap/*) are read via fs.readFile through
   wasi:filesystem/preopens. Run wasmtime with --dir=out/bootstrap::bootstrap
   so the loader finds `bootstrap/index.transit.json` etc.

   The loader does `path.resolve(:path, ...)`; we pass :path \"bootstrap\"
   so resolved paths match the preopen mount point.

   ## What's in the bundle besides cljs.js / boot

   - `cljs.test` — so deftest / is / run-tests work inside eval'd
     source. The agent writes test forms, evals via eval-batch, and
     reads pass/fail counts from the returned EDN.

   ## Why datahike.api is NOT required here (2026-05-29)

   `datahike.api` pulls in `cljs.core.async` transitively. core.async's
   dispatcher (`cljs.core.async.impl.dispatch`) drives task delivery via
   `goog.async.nextTick`, which under wasm-rquickjs resolves to
   `setImmediate` → `scheduleTimeout(cb, 0)` → `wstd::task::sleep(0)`.
   That registers a `wasi:clocks/monotonic-clock` pollable in wstd's
   reactor. Under wasmtime 44 + wstd 0.6.5, `wstd::runtime::block_on`
   parks in `block_on_pollables()` waiting on that timer pollable and its
   waker never fires — so `init-bootstrap`/`eval-form` hang at ~0% CPU
   (and a partially-drained variant trips `block_on`'s
   `unreachable!(\"ready list empty\")` panic — the require hard-panic).
   The only async machinery the bootstrap/require load path otherwise
   uses is microtask-based (`fs.readFile`'s `queueMicrotask`, native
   CLJS `^:async`/`await`), which the rquickjs runtime drains via
   `rt.idle()` WITHOUT any timer pollable — so removing core.async makes
   a fresh rebuild eval cleanly. Datahike-in-wasm needs a core.async-free
   delivery path (the seantempesta fork's Promise-wrap) before it can
   share this bundle; until then it stays out of eval-smoke."
  (:require
    [cljs.js :as cljs]
    [cljs.test]
    [cljs.tools.reader :as r]
    [cljs.tools.reader.reader-types :as rt]
    [shadow.cljs.bootstrap.node :as boot]))

(defonce !compile-state (atom nil))
(defonce !current-ns (atom 'cljs.user))

(defn ^:async init-bootstrap!
  "Load cljs.core + cljs.core$macros from the bootstrap directory.
   Returns \"ok\" on success or a pr-str'd EDN error map.

   Idempotent — second call is a no-op."
  []
  (if @!compile-state
    "ok"
    (try
      ;; Hoist `goog` / `cljs` / `shadow` from the IIFE closure to
      ;; globalThis. shadow-cljs wraps both `:node-script` and
      ;; `:node-library` outputs in an IIFE/UMD factory regardless of
      ;; `:compiler-options :output-wrapper false` — that flag only
      ;; controls Closure's own wrapper, not shadow's per-target
      ;; wrapper. Bootstrap-loaded JS runs via `goog.globalEval`
      ;; (= `(0,eval)(s)`) in GLOBAL scope and references these by
      ;; name, so we bridge them out via raw `js*`. Dev builds set
      ;; `global.$CLJS = global` for us; release does not.
      (js* "(typeof goog !== 'undefined') && (globalThis.goog = goog)")
      (js* "(typeof cljs !== 'undefined') && (globalThis.cljs = cljs)")
      (js* "(typeof shadow !== 'undefined') && (globalThis.shadow = shadow)")
      (let [state (cljs/empty-state)]
        (await (js/Promise.
                 (fn [resolve _reject]
                   (boot/init state
                              {:path "bootstrap"
                               :load-on-init '#{cljs.core}}
                              (fn [] (resolve nil))))))
        ;; Force-populate analyzer caches for EVERY ns shadow emitted
        ;; into the bootstrap output. Discover them by listing the
        ;; `ana/` dir at runtime so expanding `:bootstrap :entries`
        ;; (shadow-cljs.edn) automatically widens the agent's
        ;; analyzer-visible vocabulary — no hand-edited load-list.
        ;;
        ;; Without this, shadow's `boot/init` filter (`node.cljs:104`)
        ;; short-circuits on stubs with `:name` set (left by
        ;; `cljs/empty-state`'s `(dump-core)`), so unqualified refs to
        ;; core vars resolve to `cljs.user.X` (undefined → nil).
        (let [fs       (js/require "fs")
              ana-dir  "bootstrap/ana"
              suffix   ".transit.json"
              files    (array-seq (.readdirSync fs ana-dir))]
          (doseq [filename files
                  :when (and (string? filename)
                             (.endsWith filename suffix))]
            (let [ns-name (subs filename 0 (- (count filename) (count suffix)))
                  path    (str ana-dir "/" filename)
                  txt     (.readFileSync fs path "utf8")
                  data    (boot/transit-read txt)]
              (cljs/load-analysis-cache! state (symbol ns-name) data))))
        (when-not (and (some? (.-cljs js/global))
                       (some? (.-core (.-cljs js/global))))
          (throw (js/Error.
                   "bootstrap loader did not put cljs.core on globalThis")))
        (reset! !compile-state state)
        "ok")
      (catch :default e
        (pr-str {:status :fail
                 :phase  :init
                 :error  (or (.-message e) (str e))})))))

(defn ^:async eval-form!
  "Evaluate one form-string in the persistent compile-state. Returns a
   pr-str'd EDN map:

     {:ok true  :value-edn \"...\" :ns cljs.user}
     {:ok false :error \"...\"}

   Auto-initializes the bootstrap compiler on first call. Tracks
   ending ns per call so `(ns other)` switches persist. Persistence
   is per-process — wasmtime CLI's `--invoke` spins a fresh instance
   per call, so multi-call REPL semantics need a long-running host
   (the Tauri seon-pod, or a wasmtime embedding with shared store)."
  [form-str]
  (try
    (when-not @!compile-state
      (let [init-result (await (init-bootstrap!))]
        (when-not (= init-result "ok")
          (throw (js/Error. (str "auto-init failed: " init-result))))))
    (let [state @!compile-state
          ns-sym @!current-ns
          result (await
                   (js/Promise.
                     (fn [resolve _reject]
                       (cljs/eval-str state form-str 'seon.wasm-eval-smoke
                         {:eval          cljs/js-eval
                          :load          (partial boot/load state)
                          :ns            ns-sym
                          :context       :statement
                          :def-emits-var true
                          :analyze-deps  false}
                         (fn [r] (resolve r))))))]
      (if (:error result)
        (pr-str {:ok    false
                 :error (let [e (:error result)
                              cause (or (ex-cause e) e)]
                          (or (.-message cause) (str cause)))})
        ;; If the form evaluated to a Promise (top-level form was async
        ;; or returned a thenable), await it so the agent sees the
        ;; resolved value, not the Promise object. CLJS 1.12.145 native
        ;; ^:async fns return real Promises; this auto-unwrap matches
        ;; V0 pod's seon.eval/maybe-await-value behavior.
        (let [raw-val (:value result)
              final   (if (and (some? raw-val) (instance? js/Promise raw-val))
                        (await raw-val)
                        raw-val)]
          (reset! !current-ns (:ns result))
          (pr-str {:ok        true
                   :value-edn (pr-str final)
                   :ns        (:ns result)}))))
    (catch :default e
      (pr-str {:ok    false
               :error (or (.-message e) (str e))}))))

;; ============================================================
;; eval-batch! — multi-form runner. The agent emits one source string
;; containing N top-level forms; we read each, eval against the
;; persistent compile-state, collect per-form results. Errors land
;; as `:ok false :error <msg>` entries in the result vector;
;; subsequent forms still run (partial-success — matches the spec's
;; eval-batch! shape in V0 Node pod's seon.eval).
;; ============================================================

(defn ^:async eval-batch!
  "Read and evaluate every top-level form in `source`. Returns a
   pr-str'd EDN vector — one entry per form — of the same shape
   `eval-form!` produces (`{:ok bool :value-edn ... :error ... :ns ...}`
   on success, `{:ok false :error ...}` on failure).

   Auto-initializes the bootstrap compiler on first call. Top-level
   `;;` comments and bare prose tokens are skipped (matching V0
   `seon.repl/parse-forms`'s prose-symbol filter — bare unqualified
   symbols at the top level are dropped silently so LLM prose
   doesn't pollute the result vector).

   A reader error halts: we return whatever forms succeeded plus a
   final `:ok false :error <read-error>` entry. This is the
   forgiving-recovery point the spec's D11 will eventually formalize
   via rewrite-clj; for the M2 confidence-run surface, halting is
   enough — the agent sees both the partial successes and where it
   broke."
  [source]
  (try
    (when-not @!compile-state
      (let [init-result (await (init-bootstrap!))]
        (when-not (= init-result "ok")
          (throw (js/Error. (str "auto-init failed: " init-result))))))
    (let [rdr     (rt/string-push-back-reader source)
          results (atom [])]
      (loop []
        (let [form (try (r/read {:eof ::eof} rdr)
                        (catch :default e
                          (swap! results conj
                                 {:ok false
                                  :error (str "reader-error: "
                                              (or (.-message e) (str e)))})
                          ::eof))]
          (cond
            (= form ::eof) nil

            ;; Drop bare unqualified symbols — LLM prose tokenized by
            ;; the reader. Matches seon.repl/prose-symbol?.
            (and (symbol? form) (not (qualified-symbol? form))
                 (not (special-symbol? form)))
            (recur)

            :else
            (let [;; *print-meta* preserves `^:async`, `^:dynamic`,
                  ;; `^{:malli/schema ...}` etc. across the re-stringify.
                  ;; Without this, `(defn ^:async f [] (await …))` parses
                  ;; fine, then loses `^:async` during pr-str, then fails
                  ;; macroexpansion ("await can only be used in async
                  ;; contexts").
                  form-str (binding [*print-meta* true] (pr-str form))
                  edn      (await (eval-form! form-str))
                  parsed   (try (cljs.tools.reader/read-string edn)
                                (catch :default _ {:ok false
                                                   :error "result-edn-unparseable"
                                                   :raw edn}))]
              (swap! results conj parsed)
              (recur)))))
      (pr-str @results))
    (catch :default e
      (pr-str [{:ok false
                :error (or (.-message e) (str e))}]))))

;; Install on globalThis so the wasm-rquickjs wrapper can find them.
(set! (.-seonEvalInitBootstrap js/globalThis) init-bootstrap!)
(set! (.-seonEvalForm js/globalThis) eval-form!)
(set! (.-seonEvalBatch js/globalThis) eval-batch!)

(defn -main [& _args]
  ;; No-op. WIT host invokes the globalThis handles.
  nil)
