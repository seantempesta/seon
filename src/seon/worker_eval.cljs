(ns seon.worker-eval
  "LEAN, STANDALONE EVAL/CORRECTNESS oracle for CO-LOCATION on the diffusion
   GPU worker — the EVAL TIER sibling of the parse tier
   (`seon.worker-validator` / `bin/oracle-server`).

   ## What this answers

   Parse asks \"is the canvas structurally well-formed?\" (rewrite-clj spans).
   EVAL asks the next question: \"does the generated code actually RUN?\" — does
   the form COMPILE, and (for a self-contained form) EVALUATE without an
   unbound var / arity error / throw, inside a wall-clock budget. That verdict
   is what gates the denoise loop's SHORT-CIRCUIT (stop when correct) vs
   RENOISE (re-noise + keep denoising).

   ## Why cljs.js self-host (NOT SCI, NOT bb)

   The worker emits CLJS-flavored code — `^:async`/`await`, js interop
   (`(.json x)`, `(.-foo o)`), pod-shaped calls. bb-SCI evals *Clojure* and
   cljs-SCI is an interpreter with no `^:async`: both throw FALSE NEGATIVES on
   that exact surface, poisoning the control signal. cljs.js self-host is the
   real ClojureScript compiler (analyzer + emitter → real JS), so it is the
   only path that COMPILES async/interop faithfully. The cost is honest:
   self-host init loads the ~15MB bootstrap analysis cache once (heavy — see
   the measured numbers below), which is precisely why the eval tier is
   SPARSER than parse (checkpoint-class, not every-step).

   ## Dependency surface

   `cljs.js` (self-host compiler) + `shadow.cljs.bootstrap.node` (the analysis
   cache loader) + `cljs.analyzer` (warning capture). NO `seon.db`,
   NO `seon.eval`, NO `seon.schema`, NO pod state, NO datahike — those are
   pod-coupled (`src/seon/render/sci.cljs:69-70`, `src/seon/eval.cljs:36-58`).
   The two tiny bootstrap-cache helpers are copied from `seon.eval` BY DESIGN:
   this is a separate leaf bundle so the self-host weight never bloats the lean
   parse bundle, and it must not drag the pod cage onto the worker image.

   ## Non-termination is BOUNDED

   cljs.js compiles to NATIVE JS, so a runaway `(loop [] (recur))` is a tight
   `while(true)` — SCI's `:interrupt-fn` (interpreter-only) cannot touch it.
   We bound it with Node's `vm.runInThisContext(source, {timeout})`: the V8
   watchdog terminates a synchronous infinite loop and throws. The compiled JS
   still runs in the CURRENT global (where cljs.core lives), so fidelity is
   preserved — only the execution is fenced.

   ## Honest scope

   A self-host eval has its OWN namespace/var model — it does NOT have the
   pod's program-graph fns, the agent's prior defs, the live DB, or malli
   instrumentation. So this answers \"is this a RUNNABLE, self-contained,
   non-throwing CLJS form within a budget\" (unbound symbols, arity, type
   errors, divide-by-zero, non-termination) — the renoise control signal
   \"does it RUN\", NOT \"does it behave bit-identical to the instrumented
   pod\". Pod-semantics parity, if ever needed, is a REMOTE call back to the
   pod, not this in-loop local one.

   ## Wire contract

   Same `{op, …}` JSON-line API as the bb parse server, so the Python `Oracle`
   is runtime-agnostic (it picks the binary per op). Per line in:

       {\"op\":\"eval\",\"id\":7,\"code\":\"(defn mean [v] (/ (reduce + v) (count v)))\",\"budget-ms\":1000}

   Per line out:

       {\"op\":\"eval\",\"id\":7,\"tier\":\"eval\",\"ok\":true,\"value\":\"#'seon.worker-eval.user/mean\"}
       {\"op\":\"eval\",\"id\":8,\"tier\":\"eval\",\"ok\":false,\"error\":{\"kind\":\"compile\",\"message\":\"Use of undeclared Var ...\"}}
       {\"op\":\"eval\",\"id\":9,\"tier\":\"eval\",\"ok\":false,\"error\":{\"kind\":\"interrupt\",\"message\":\"Script execution timed out.\"}}

   A bare JSON-string line (the parse-server framing) is accepted too and
   treated as `{op:\"eval\", code:<string>}`, so one pipe carries either
   framing. The self-host init is async (loads the bootstrap cache), so
   `--serve` awaits a READY state before reading the first line, and evals run
   STRICTLY SEQUENTIALLY (one in-flight; a hang on one line cannot interleave
   the next)."
  (:require
    [clojure.string :as str]
    [cljs.js :as cljs]
    [cljs.analyzer :as ana]
    [shadow.cljs.bootstrap.node :as boot]
    ["fs" :as fs]
    ["path" :as path]
    ["vm" :as vm]
    ["readline" :as readline]))

;; ============================================================
;; Config
;; ============================================================

(def default-budget-ms
  "Wall-clock budget for one eval (compile + execute). Eval is heavier than
   parse — self-host compilation plus running the form — so the floor is
   generous (1s). The denoise loop overrides per request via `budget-ms`."
  1000)

(def ^:private default-bootstrap-path
  "Where the ~15MB analysis cache lives. `SEON_BOOTSTRAP` overrides (the
   worker image COPYs it next to the bundle); default is the CWD-relative
   build output."
  (or (some-> js/process .-env .-SEON_BOOTSTRAP)
      "out/bootstrap"))

;; ============================================================
;; Bootstrap analysis-cache load (copied leaf helpers — see ns docstring;
;; seon.eval's are pod-coupled, this bundle must stay DB-free).
;; ============================================================

(defn- bootstrap-cache-files
  "Enumerate `<bootstrap>/ana/*.transit.json` as `[ns-sym path]` pairs,
   cljs.core + cljs.core$macros sorted first (cosmetic)."
  [bootstrap-path]
  (let [path-mod (js/require "path")
        ana-dir  (.resolve path-mod bootstrap-path "ana")
        names    (.readdirSync fs ana-dir)
        suffix   ".transit.json"]
    (->> (array-seq names)
         (filter #(str/ends-with? % suffix))
         (map (fn [filename]
                (let [ns-name (subs filename 0 (- (count filename) (count suffix)))]
                  [(symbol ns-name) (.resolve path-mod ana-dir filename)])))
         (sort-by (fn [[ns-sym _]]
                    (case (str ns-sym)
                      "cljs.core"        0
                      "cljs.core$macros" 1
                      2))))))

(defn- load-all-analysis-caches!
  "Read every `<bootstrap>/ana/*.transit.json` and
   `cljs.js/load-analysis-cache!` it into `state`, so every bootstrap entry
   (cljs.core, clojure.string, malli, …) is resolvable by the analyzer
   without a hand-maintained load-list."
  [state bootstrap-path]
  (doseq [[ns-sym path] (bootstrap-cache-files bootstrap-path)]
    (let [txt  (.readFileSync fs path "utf8")
          data (boot/transit-read txt)]
      (cljs/load-analysis-cache! state ns-sym data))))

;; ============================================================
;; Warm self-host state + per-eval warning capture
;;
;; The worker is single-threaded and evals run STRICTLY SEQUENTIALLY, so a
;; process-global warning sink read/reset around each eval is safe — there is
;; no overlapping eval to race (same justification as render.sci's volatiles).
;; ============================================================

(defonce ^:private !state    (atom nil))
(def ^:private    !warnings (volatile! []))

(def ^:private error-warning-types
  "Analyzer warning types that mean the form does NOT correctly compile —
   an unresolved symbol or a bad call shape. These flip ok? false even when
   the emitted JS happens not to throw at runtime."
  #{:undeclared-var :undeclared-ns :undeclared-ns-form
    :invalid-arithmetic :fn-arity :invalid-protocol-symbol
    :protocol-invalid-method :multiple-variadic-overloads})

(defn- collect-warning!
  "Warning handler: stash the human message of any compile-error-class
   warning into `!warnings` for the in-flight eval."
  [warning-type env extra]
  (when (contains? error-warning-types warning-type)
    (let [msg (ana/error-message warning-type extra)]
      (vswap! !warnings conj
              (str (name warning-type) (when msg (str ": " msg)))))))

(defn init-state!
  "Build + warm the self-host compile-state ONCE (loads the bootstrap analysis
   cache), install the warning sink, and stash it in `!state`. Returns a
   Promise resolving to the ready state. This is the heavy, one-time cost."
  [bootstrap-path]
  (js/Promise.
    (fn [resolve reject]
      (try
        (let [state (cljs/empty-state)]
          (boot/init state
                     {:path bootstrap-path :load-on-init '#{cljs.core}}
                     (fn []
                       (try
                         (load-all-analysis-caches! state bootstrap-path)
                         ;; Route analyzer warnings through our per-eval sink
                         ;; instead of stderr; undeclared-var is the
                         ;; authoritative \"does not compile\" signal.
                         (set! ana/*cljs-warning-handlers* [collect-warning!])
                         (reset! !state state)
                         (resolve state)
                         (catch :default e (reject e))))))
        (catch :default e (reject e))))))

;; ============================================================
;; Bounded eval — cljs.js self-host, fenced by vm timeout
;; ============================================================

(defn- bounded-eval-fn
  "A cljs.js `:eval` fn that runs the compiled JS `source` in the CURRENT
   global context (where cljs.core lives) under a V8 watchdog `timeout`, so a
   synchronous infinite loop is TERMINATED + thrown rather than hanging the
   process. Returns the eval value, exactly like `cljs.js/js-eval`."
  [budget-ms]
  (fn [{:keys [source]}]
    (.runInThisContext vm source #js {:timeout budget-ms
                                      :displayErrors false})))

(defn- cause-chain
  "Walk `e` through its cause chain (cljs `ex-cause` covers both ExceptionInfo
   `:cause` and a JS Error's `.-cause`), bounded to avoid a cycle."
  [e]
  (loop [x e, acc [], guard 0]
    (if (or (nil? x) (> guard 16))
      acc
      (recur (ex-cause x) (conj acc x) (inc guard)))))

(defn- classify-error
  "Map a thrown error to `{:kind :throw|:interrupt :message s}`. cljs.js wraps
   an eval throw in a `:cljs/analysis-error` ex-info whose `:cause` is the REAL
   error, so we walk the cause chain: the DEEPEST cause carries the actionable
   message (e.g. the vm watchdog's \"Script execution timed out after Nms\"),
   and a timeout ANYWHERE in the chain (message match or the Node
   `ERR_SCRIPT_EXECUTION_TIMEOUT` code) classifies as `:interrupt`."
  [e]
  (let [chain     (cause-chain e)
        timed-out (some (fn [x]
                          (or (= "ERR_SCRIPT_EXECUTION_TIMEOUT" (some-> x .-code))
                              (re-find #"(?i)timed out|execution.*terminated"
                                       (or (some-> x ex-message) (str x)))))
                        chain)
        ;; deepest non-blank message = the actionable one
        msg       (or (some (fn [x] (let [m (ex-message x)]
                                      (when (and m (seq m) (not= m "ERROR")) m)))
                            (reverse chain))
                      (ex-message e)
                      (str e))]
    {:kind    (if timed-out :interrupt :throw)
     :message msg}))

(defn eval-form
  "Compile + eval `code` under the warm self-host state, bounded by
   `budget-ms`. Returns a Promise of a plain clj map:

       {:ok true  :value <pr-str of the value>}
       {:ok false :error {:kind :compile|:throw|:interrupt :message s}}

   `:compile` = the analyzer flagged an unresolved var / bad arity (the form
   does not legally compile); `:throw` = it compiled but threw at runtime;
   `:interrupt` = it exceeded the wall-clock budget (non-termination). A clean
   compile+eval → `{:ok true …}`.

   PURE w.r.t. the worker's domain state (no DB, no pod). The only mutable
   touch is the per-eval warning sink, reset here under the sequential
   single-eval invariant."
  [code budget-ms]
  (js/Promise.
    (fn [resolve _reject]
      (let [state @!state]
        (if (nil? state)
          (resolve {:ok false :error {:kind :throw :message "eval state not initialized"}})
          (do
            (vreset! !warnings [])
            (try
              (cljs/eval-str state code 'seon.worker-eval.user
                {:eval          (bounded-eval-fn (or budget-ms default-budget-ms))
                 :context       :expr
                 :def-emits-var true
                 :analyze-deps  false}
                (fn [{:keys [error value]}]
                  (let [warns @!warnings]
                    (cond
                      error
                      (resolve {:ok false :error (classify-error error)})

                      (seq warns)
                      (resolve {:ok false :error {:kind :compile :message (str/join "; " warns)}})

                      :else
                      (resolve {:ok true
                                :value (try (pr-str value)
                                            (catch :default _ "<unprintable>"))})))))
              (catch :default e
                (resolve {:ok false :error (classify-error e)})))))))))

;; ============================================================
;; Wire boundary — clj result → JSON-serializable JS object.
;; ============================================================

(defn- gobj-set [o k v] (aset o k v) o)
(defn- gobj-get [o k] (when (some? o) (aget o k)))

(defn- result->js
  "Flatten an [[eval-form]] result + the echoed `op`/`id` to a
   `JSON.stringify`-able JS value. Keyword `:kind` → its NAME string."
  [op id {:keys [ok value error]}]
  (let [base #js {:op   op
                  :tier "eval"
                  :ok   (boolean ok)}]
    (when (some? id) (gobj-set base "id" id))
    (if ok
      (gobj-set base "value" value)
      (gobj-set base "error" #js {:kind    (name (:kind error))
                                  :message (:message error)}))
    base))

(defn- parse-request
  "Parse one stdin line into `{:op :id :code :budget-ms}`. Accepts a JSON
   OBJECT `{op,id,code,budget-ms}` OR a bare JSON STRING (treated as
   `{op:\"eval\", code:<string>}`, matching the parse-server framing)."
  [line]
  (let [parsed (try (.parse js/JSON line) (catch :default _ nil))]
    (cond
      (string? parsed)
      {:op "eval" :id nil :code parsed :budget-ms nil}

      (and (object? parsed) (some? parsed))
      {:op        (or (gobj-get parsed "op") "eval")
       :id        (gobj-get parsed "id")
       :code      (or (gobj-get parsed "code") "")
       :budget-ms (gobj-get parsed "budget-ms")}

      :else
      {:op "eval" :id nil :code "" :budget-ms nil})))

(defn handle-line
  "One request line → a Promise of one JSON result line (string)."
  [line]
  (let [{:keys [op id code budget-ms]} (parse-request line)]
    (-> (eval-form code budget-ms)
        (.then (fn [result]
                 (.stringify js/JSON (result->js op id result)))))))

;; ============================================================
;; Subprocess entry.
;; ============================================================

(defn- serve!
  "Persistent line server (the co-location hot path). FIRST awaits the heavy
   self-host init (loads the bootstrap cache), THEN reads one `{op,…}` JSON
   line per stdin line and writes one JSON result line. Evals are chained so
   they run STRICTLY SEQUENTIALLY — one in-flight at a time."
  [bootstrap-path]
  (-> (init-state! bootstrap-path)
      (.then
        (fn [_]
          (let [rl    (.createInterface readline #js {:input (.-stdin js/process)})
                chain (atom (js/Promise.resolve nil))]
            ;; Signal readiness so a driver can begin sending lines.
            (.write (.-stderr js/process) "ready\n")
            (.on rl "line"
                 (fn [line]
                   (swap! chain
                          (fn [p]
                            (.then p (fn [_]
                                       (-> (handle-line line)
                                           (.then (fn [out] (println out)))
                                           (.catch (fn [e]
                                                     (println (.stringify js/JSON
                                                                #js {:op "eval" :tier "eval" :ok false
                                                                     :error #js {:kind "throw"
                                                                                 :message (str e)}}))))))))))))))
      (.catch (fn [e]
                (.write (.-stderr js/process) (str "init-failed: " e "\n"))
                (.exit js/process 1)))))

(defn -main
  "Bundle entry. Two modes:

   - `--serve` → [[serve!]] (persistent line server, the co-location hot
     path);
   - otherwise → read the ENTIRE stdin as the code string, eval it once, and
     write one JSON line (one-shot — convenient for tests; the self-host init
     cold cost is paid each spawn, so NOT the hot path).

   `--bootstrap <path>` overrides the analysis-cache directory (default
   `SEON_BOOTSTRAP` env or `out/bootstrap`)."
  [& args]
  (let [argv  (vec args)
        bp    (let [i (.indexOf (to-array argv) "--bootstrap")]
                (if (and (>= i 0) (< (inc i) (count argv)))
                  (nth argv (inc i))
                  default-bootstrap-path))]
    (if (some #{"--serve"} argv)
      (serve! bp)
      (let [code (try (.toString (.readFileSync fs 0) "utf8")
                      (catch :default _ ""))]
        (-> (init-state! bp)
            (.then (fn [_] (handle-line (.stringify js/JSON (str/trim code)))))
            (.then (fn [out] (println out)))
            (.catch (fn [e]
                      (println (.stringify js/JSON
                                 #js {:op "eval" :tier "eval" :ok false
                                      :error #js {:kind "throw" :message (str e)}})))))))))
