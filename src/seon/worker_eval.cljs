(ns seon.worker-eval
  "LEAN, STANDALONE EVAL/CORRECTNESS oracle for CO-LOCATION on the diffusion
   GPU worker — the EVAL TIER sibling of the parse tier
   (`seon.worker-validator` / `bin/oracle-server`).

   ## What this answers

   Parse asks \"is the code-buffer structurally well-formed?\" (rewrite-clj spans).
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
   cache loader) + `cljs.analyzer` (warning capture) +
   `seon.eval.bootstrap-cache` (the shared LEAF cache loader — deliberately
   free of seon.db/seon.schema/pod state). NO `seon.eval`, NO datahike —
   those are pod-coupled (`src/seon/render/sci.cljs:69-70`,
   `src/seon/eval.cljs:36-58`); this is a separate leaf bundle so the
   self-host weight never bloats the lean parse bundle, and it must not drag
   the pod cage onto the worker image.

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
    [seon.repair.candidates :as candidates]
    [seon.eval.bootstrap-cache :as bootstrap-cache]
    [shadow.cljs.bootstrap.node :as boot]
    ["fs" :as fs]
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
  "Build + warm the self-host compile-state ONCE, install the warning sink.

   Loads the bootstrap analysis cache and stashes it in `!state`. Returns a
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
                         (bootstrap-cache/load-all! state bootstrap-path)
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

(defn- analyzer-error?
  "True if `x` is an ANALYZER/compile-time error (the form does not legally
   compile) rather than a runtime throw. cljs.js's `ana/error` tags such an
   ex-info `:cljs/analysis-error`; the syntax-check path (`compile-syntax-error`)
   instead stamps a `:clojure.error/phase`. A runtime JS Error has neither."
  [x]
  (or (ana/analysis-error? x)
      (contains? (ex-data x) :clojure.error/phase)))

(defn- classify-error
  "Map a thrown error to `{:kind :compile|:throw|:interrupt :message s}`. cljs.js
   wraps BOTH an analyzer error AND a runtime eval throw in an OUTER
   `:cljs/analysis-error` ex-info whose `:cause` is the real error, so we walk
   the cause chain:

   - the DEEPEST cause carries the actionable message (e.g. the vm watchdog's
     \"Script execution timed out after Nms\");
   - a timeout ANYWHERE in the chain (message match or the Node
     `ERR_SCRIPT_EXECUTION_TIMEOUT` code) → `:interrupt`;
   - an analyzer error BELOW the outer wrapper (a compile error like
     too-many-args-to-def that THREW during analysis rather than warning) →
     `:compile` — the outer wrapper is always an analysis-error, so we test
     `(rest chain)`; a genuine runtime throw's cause is a raw JS Error and
     fails that test → `:throw`."
  [e]
  (let [chain     (cause-chain e)
        timed-out (some (fn [x]
                          (or (= "ERR_SCRIPT_EXECUTION_TIMEOUT" (some-> x .-code))
                              (re-find #"(?i)timed out|execution.*terminated"
                                       (or (some-> x ex-message) (str x)))))
                        chain)
        compile?  (some analyzer-error? (rest chain))
        ;; deepest non-blank message = the actionable one
        msg       (or (some (fn [x] (let [m (ex-message x)]
                                      (when (and m (seq m) (not= m "ERROR")) m)))
                            (reverse chain))
                      (ex-message e)
                      (str e))]
    {:seon.error/kind    (cond timed-out :interrupt
                               compile?  :compile
                               :else     :throw)
     :seon.error/message msg}))

(defn- eval-in-session
  "The ONE compile(+eval) core over the warm self-host state.

   Two modes, one code path:

   - `compile-only?` FALSE (the default) — compile AND execute under the vm
     watchdog (`budget-ms`), exactly the historical eval semantics;
   - `compile-only?` TRUE — the repair pipeline's TRIAL: the `:eval` fn is a
     no-op, so NOTHING executes (no side effects, no vm), but the analyzer
     still runs and the warning sink still captures undeclared-var /
     fn-arity / … verdicts. Analyzer state does accumulate defs from a
     trial — acceptable (a re-def by the winning real eval lands the same
     vars).

   Resolves a Promise of a plain clj map:

       {:seon.eval/ok? true  :seon.eval/raw-value v :seon.eval/warnings []}
       {:seon.eval/ok? false :seon/error {:seon.error/kind … :seon.error/message …}
        :seon.eval/warnings [s …] :seon.eval/thrown? bool}

   `:seon.eval/warnings` is the raw captured warning list (the repair
   pipeline parses undeclared names out of it); `:seon.eval/thrown?` marks a
   THROWN analysis/runtime error as opposed to a warnings-only failure."
  [code budget-ms {:keys [compile-only?]}]
  (js/Promise.
    (fn [resolve _reject]
      (let [state @!state]
        (cond
          (nil? state)
          (resolve {:seon.eval/ok? false
                    :seon.eval/warnings []
                    :seon.eval/thrown? true
                    :seon/error {:seon.error/kind :throw
                                 :seon.error/message "eval state not initialized"}})

          ;; Empty/blank code is a malformed request, NOT a passing eval. A
          ;; non-JSON or empty line otherwise reads as the empty form and
          ;; evals to nil → a SILENT ok:true FALSE PASS, which would let the
          ;; kill-gate wave garbage through. Reject it as a compile error.
          (str/blank? code)
          (resolve {:seon.eval/ok? false
                    :seon.eval/warnings []
                    :seon.eval/thrown? false
                    :seon/error {:seon.error/kind :compile
                                 :seon.error/message "empty or unparseable code"}})

          :else
          (do
            (vreset! !warnings [])
            (try
              (cljs/eval-str state code 'seon.worker-eval.user
                {:eval          (if compile-only?
                                  (fn [_] nil)   ; trial: analyze/compile, execute NOTHING
                                  (bounded-eval-fn (or budget-ms default-budget-ms)))
                 :context       :expr
                 :def-emits-var true
                 :analyze-deps  false
                 ;; the bootstrap loader — lets a session (require '[seon.schema
                 ;; :as schema]) etc. from the analysis cache, so EVERY phase
                 ;; gate can parse->EVAL (owner: no parse-only locks)
                 :load          (partial boot/load state)}
                (fn [{:keys [error value]}]
                  (let [warns @!warnings]
                    (cond
                      ;; A captured compile-error-class warning (undeclared-var,
                      ;; bad arity, def-vs-defn) means the form does NOT legally
                      ;; compile — classify it `:compile` FIRST, even though the
                      ;; emitted JS then ALSO throws at runtime (e.g. an
                      ;; undeclared var compiles to a reference that throws a
                      ;; ReferenceError). Checking `error` first would mask the
                      ;; analyzer verdict as a coarser `:throw`.
                      (seq warns)
                      (resolve {:seon.eval/ok? false
                                :seon.eval/warnings warns
                                :seon.eval/thrown? false
                                :seon/error {:seon.error/kind :compile
                                             :seon.error/message (str/join "; " warns)}})

                      error
                      (resolve {:seon.eval/ok? false
                                :seon.eval/warnings warns
                                :seon.eval/thrown? true
                                :seon/error (classify-error error)})

                      :else
                      (resolve {:seon.eval/ok? true
                                :seon.eval/warnings warns
                                :seon.eval/raw-value value})))))
              (catch :default e
                (resolve {:seon.eval/ok? false
                          :seon.eval/warnings @!warnings
                          :seon.eval/thrown? true
                          :seon/error (classify-error e)})))))))))

(defn ^:async eval-form
  "Compile + eval `code` under the warm self-host state.

   Bounded by
   `budget-ms`. Returns a Promise of a plain clj map:

       {:seon.eval/ok? true  :seon.eval/value <pr-str of the value>}
       {:seon.eval/ok? false
        :seon/error {:seon.error/kind :compile|:throw|:interrupt
                     :seon.error/message s}}

   `:compile` = the analyzer flagged an unresolved var / bad arity (the form
   does not legally compile); `:throw` = it compiled but threw at runtime;
   `:interrupt` = it exceeded the wall-clock budget (non-termination). A clean
   compile+eval → `{:seon.eval/ok? true …}`.

   PURE w.r.t. the worker's domain state (no DB, no pod). The only mutable
   touch is the per-eval warning sink, reset here under the sequential
   single-eval invariant."
  [code budget-ms]
  (let [{ok? :seon.eval/ok? v :seon.eval/raw-value :as res}
        (await (eval-in-session code budget-ms {}))]
    (if ok?
      {:seon.eval/ok? true
       :seon.eval/value (try (pr-str v) (catch :default _ "<unprintable>"))}
      (select-keys res [:seon.eval/ok? :seon/error]))))

;; JSON-boundary helpers (shared by every op's result builder).
(defn- gobj-set [o k v] (aset o k v) o)
(defn- gobj-get [o k] (when (some? o) (aget o k)))

;; ============================================================
;; op:"run-tests" — run the session's deftest vars.
;;
;; The session's code evals into `cljs.user` (eval-str's third arg is the
;; source NAME; cljs.js's eval ns is the `:ns` opt, defaulting to
;; cljs.user); the compiled deftest emits `var.cljs$lang$test =
;; <test-body-fn>` on the def's VALUE
;; (reference-code/clojurescript/src/main/clojure/cljs/compiler.cljc:901),
;; and the ns object lives on the shared vm global (`globalThis.cljs.user`)
;; because bounded evals run in the current global context. The RUNNER itself is evaluated IN-SESSION (not
;; host-side) so every cljs.test value it touches belongs to the ONE
;; bootstrap-loaded cljs.test instance the deftests reference — host-side
;; keyword/map access on bootstrap-runtime values would cross two cljs.core
;; instances and silently fail equality.
;; ============================================================

(def ^:private session-ns-path
  "The munged JS global path of the session eval ns."
  ["cljs" "user"])

(defn- session-ns-obj
  "The session ns JS object on the vm global, or nil before any eval."
  []
  (reduce (fn [o k] (when (some? o) (gobj-get o k))) js/globalThis session-ns-path))

(defn- test-var-names
  "Munged JS names of the session defs carrying `cljs$lang$test`, optionally
   filtered by `vars` (demunged \"name\" or \"ns/name\" strings)."
  [vars]
  (let [ns-obj (session-ns-obj)
        wanted (when (seq vars)
                 (into #{} (map (fn [s]
                                  (let [i (.lastIndexOf s "/")]
                                    (if (>= i 0) (subs s (inc i)) s))))
                       vars))]
    (if (nil? ns-obj)
      []
      (->> (js/Object.keys ns-obj)
           (filter (fn [k]
                     (let [v (gobj-get ns-obj k)]
                       (and (some? v) (some? (gobj-get v "cljs$lang$test"))))))
           (filter (fn [k] (or (nil? wanted) (contains? wanted (str (demunge k))))))
           vec))))

(defn- test-runner-source
  "The CLJS source of the in-session test runner over the munged `names`.

   ONE top-level form (a multi-form string containing `set!` emits broken
   JS under eval-str — verified live), evaluated AFTER a separate
   `(require '[cljs.test])` call. Swaps `cljs.test/report` — the compiled
   `is` calls `cljs.test.report` DIRECTLY (decompiled live; `do-report` is
   bypassed) — for a capturing fn (counters + failure maps read INSIDE the
   session runtime), restores it in a `finally`, and returns a
   `js/JSON.stringify` STRING — the one value shape that crosses the
   bootstrap/host boundary losslessly. Async deftests are reported as
   errors (the strictly-sequential line server cannot await a CPS test)."
  [names]
  (str
    "(let [ns-obj (reduce (fn [o k] (when o (aget o k))) js/globalThis " (pr-str session-ns-path) ")\n"
    "      names " (pr-str names) "\n"
    "      reports (volatile! [])\n"
    "      orig cljs.test/report]\n"
    "  (cljs.test/set-env! (cljs.test/empty-env))\n"
    "  (set! cljs.test/report (fn [m] (vswap! reports conj m) nil))\n"
    "  (let [results\n"
    "        (try\n"
    "          (vec (for [n names]\n"
    "                 (let [f (aget ns-obj n)\n"
    "                       t (aget f \"cljs$lang$test\")\n"
    "                       before (count @reports)\n"
    "                       err (try (let [r (t)]\n"
    "                                  (when (cljs.test/async? r)\n"
    "                                    \"async deftest not supported by op:run-tests\"))\n"
    "                                (catch :default e (str e)))\n"
    "                       ms (subvec @reports before)]\n"
    "                   {:var (str (demunge n))\n"
    "                    :pass (count (filter #(= :pass (:type %)) ms))\n"
    "                    :fail (vec (filter #(= :fail (:type %)) ms))\n"
    "                    :error (cond-> (vec (filter #(= :error (:type %)) ms))\n"
    "                             err (conj {:message err}))})))\n"
    "          (finally (set! cljs.test/report orig) (cljs.test/clear-env!)))]\n"
    "    (js/JSON.stringify\n"
    "      (clj->js {:pass (reduce + 0 (map :pass results))\n"
    "                :fail (reduce + 0 (map (comp count :fail) results))\n"
    "                :error (reduce + 0 (map (comp count :error) results))\n"
    "                :failures (vec (for [r results, m (concat (:fail r) (:error r))]\n"
    "                                 {:var (:var r)\n"
    "                                  :message (str (when-let [msg (:message m)] (str msg \" \"))\n"
    "                                                (when (contains? m :expected)\n"
    "                                                  (str \"expected: \" (pr-str (:expected m))\n"
    "                                                       \" actual: \" (pr-str (:actual m)))))}))}))))\n"))

(defn- ^:async run-tests
  "`op:\"run-tests\"` — run the session's deftest vars, machine-readable,
   never throws. Resolves the JSON-ready JS result object."
  [{:keys [id vars]}]
  (let [base #js {:op "run-tests" :tier "test"}
        _    (when (some? id) (gobj-set base "id" id))
        names (test-var-names vars)]
    (if (empty? names)
      (doto base
        (gobj-set "ok" true) (gobj-set "pass" 0) (gobj-set "fail" 0)
        (gobj-set "error" 0) (gobj-set "failures" #js []))
      (let [_ (await (eval-in-session "(require '[cljs.test])" default-budget-ms {}))
            {ok? :seon.eval/ok? v :seon.eval/raw-value err :seon/error}
            (await (eval-in-session (test-runner-source names) default-budget-ms {}))]
        (if (and ok? (string? v))
          (let [summary (.parse js/JSON v)
                fails   (gobj-get summary "fail")
                errs    (gobj-get summary "error")]
            (doto base
              (gobj-set "ok" (and (zero? fails) (zero? errs)))
              (gobj-set "pass" (gobj-get summary "pass"))
              (gobj-set "fail" fails)
              (gobj-set "error" errs)
              (gobj-set "failures" (gobj-get summary "failures"))))
          ;; the runner itself failed — surface as one error row, never throw
          (doto base
            (gobj-set "ok" false) (gobj-set "pass" 0) (gobj-set "fail" 0)
            (gobj-set "error" 1)
            (gobj-set "failures"
                      #js [#js {:var "<runner>"
                                :message (str (:seon.error/message err))}])))))))

;; ============================================================
;; op:"repair" — detect → candidates → compile-only trials → winner eval.
;;
;; The shared autofix design (docs/prds/agent-ctx/research/
;; form-autofix-system-2026-07-05.md): a fix applies only when EXACTLY ONE
;; candidate passes a compile-only trial; 2+ passers = ambiguous (hint, no
;; fix); trials execute NOTHING; the single winner is eval'd for real so
;; its defs land in the session. The candidate/distance/threshold/tier
;; intelligence is the SHARED `seon.repair.candidates` (one mechanism —
;; the pod's pre-flight gate rides the same code); this op supplies only
;; its OWN candidate sources (session defs + cached core + graph names)
;; and its compile-only trial.
;; ============================================================

(def ^:private default-repair-budget-ms
  "Whole-pipeline wall for one repair call — a slow fix is a worse product
   than a fast error (the research's <10ms/form target)."
  10)

(def ^:private max-repair-fixes
  "Chained-fix cap: at most this many DISTINCT undeclared vars per call."
  3)

(def ^:private undeclared-var-re
  "Matches the captured analyzer warning for an unresolved symbol."
  #"undeclared-var: Use of undeclared Var (\S+)")

(defn- undeclared-names
  "Distinct unresolved var NAMES parsed out of the captured warnings."
  [warnings]
  (into [] (distinct)
        (keep #(some-> (re-find undeclared-var-re %) second candidates/name-part)
              warnings)))

(defonce ^:private !core-names (atom nil))

(defn- core-names
  "All `cljs.core` public NAMES, from the loaded analysis cache — computed
   once per process (the analyzer state is HOST data, safe to read here)."
  []
  (or @!core-names
      (let [st    @!state
            names (when st
                    (mapv str (keys (get-in @st [:cljs.analyzer/namespaces
                                                 'cljs.core :defs]))))]
        (when (seq names) (reset! !core-names names))
        (or names []))))

(defn- session-def-names
  "NAMES already defined in the session eval ns (analyzer state)."
  []
  (when-let [st @!state]
    (mapv str (keys (get-in @st [:cljs.analyzer/namespaces
                                 'cljs.user :defs])))))

(defn- candidates-for
  "Ranked fix candidates (k ≤ 5) for the unresolved `from` over THIS
   worker's sources: session defs + cljs.core + `graph-names` (name
   parts). Ranking/threshold (Levenshtein ≤ ⌈n/3⌉, nearest first) is the
   SHARED `seon.repair.candidates/rank-candidates` — see its docstring
   for why ⌈n/3⌉ (the `transct!` → `tapset` deep-tier lesson)."
  [from graph-names]
  (candidates/rank-candidates
    from
    (concat (session-def-names) (core-names)
            (map candidates/name-part graph-names))))

(defn- ^:async trial
  "Compile-only trial of `code` → `{:repair/clean? :repair/undeclared
   :repair/thrown?}` (nothing executes)."
  [code]
  (let [{ok? :seon.eval/ok? warns :seon.eval/warnings thrown? :seon.eval/thrown?}
        (await (eval-in-session code nil {:compile-only? true}))]
    {:repair/clean?     (boolean ok?)
     :repair/thrown?    (boolean thrown?)
     :repair/undeclared (set (undeclared-names warns))
     :repair/warnings   (vec warns)}))

(defn- trial-passes?
  "A candidate trial PASSES when nothing threw, `from` is fixed, `to`
   resolves, and every remaining warning is an undeclared-var for some
   OTHER name (the chained-typo case) — any other warning class fails."
  [{:repair/keys [thrown? undeclared warnings]} from to]
  (and (not thrown?)
       (not (contains? undeclared from))
       (not (contains? undeclared to))
       (every? #(re-find undeclared-var-re %) warnings)))

(defn- pick-winner
  "The SHARED nearest-tier / unique-winner pick
   (`seon.repair.candidates/pick-winner`) wired to THIS worker's
   compile-only [[trial]]. Resolves `{:seon.repair/winner …}` /
   `{:seon.repair/ambiguous [..]}` / `{:seon.repair/none? true}` /
   `{:seon.repair/budget? true}`. `over?` is the budget check."
  [code from cands over?]
  (candidates/pick-winner
    {:seon.repair/cands cands
     :seon.repair/over? over?
     :seon.repair/passes?
     (fn ^:async candidate-passes? [c]
       (let [to (:seon.repair/to c)
             t  (await (trial (candidates/substitute-symbol code from to)))]
         (trial-passes? t from to)))}))

(defn- suggestions-js
  [cands]
  (clj->js (mapv (fn [{:seon.repair/keys [to distance]}]
                   {"sym" to "distance" distance})
                 cands)))

(defn- repair-fail-js
  [base reason cands]
  (doto base
    (gobj-set "ok" false)
    (gobj-set "reason" reason)
    (gobj-set "suggestions" (suggestions-js cands))))

(defn- ^:async repair
  "`op:\"repair\"` — the detect → candidates → compile-only trials →
   winner-eval pipeline over ONE form's source. Resolves the JSON-ready JS
   result object; ambiguity or exhaustion is a hint, never a guess."
  [{:keys [id code graph-names budget-ms]}]
  (let [base   #js {:op "repair" :tier "repair"}
        _      (when (some? id) (gobj-set base "id" id))
        budget (or budget-ms default-repair-budget-ms)
        start  (js/Date.now)
        over?  #(> (- (js/Date.now) start) budget)]
    (loop [code* code fixes []]
      (let [{:repair/keys [clean? undeclared] :as t} (await (trial code*))]
        (cond
          ;; clean — nothing (left) to repair; a winner eval'd = fixes recorded
          clean?
          (if (empty? fixes)
            (doto base (gobj-set "ok" true) (gobj-set "fixed_code" nil)
                       (gobj-set "fixes" #js []))
            (let [{ok? :seon.eval/ok? v :seon.eval/value err :seon/error}
                  (await (eval-form code* nil))]   ; the REAL eval — defs land
              (doto base
                (gobj-set "ok" true)
                (gobj-set "fixed_code" code*)
                (gobj-set "fixes" (clj->js (mapv (fn [{:seon.repair/keys [from to]}]
                                                   {"from" from "to" to})
                                                 fixes))))
              (if ok?
                (gobj-set base "value" v)
                (gobj-set base "eval_error"
                          #js {:kind    (name (:seon.error/kind err))
                               :message (:seon.error/message err)}))
              base))

          (over?)
          (repair-fail-js base "budget" [])

          ;; not an undeclared-var failure (or chained cap hit) — can't repair
          (or (empty? undeclared) (>= (count fixes) max-repair-fixes))
          (repair-fail-js base "no-candidate" [])

          :else
          (let [from  (first undeclared)
                cands (candidates-for from graph-names)
                pick  (await (pick-winner code* from cands over?))]
            (cond
              (:seon.repair/budget? pick)   (repair-fail-js base "budget" [])
              (:seon.repair/ambiguous pick) (repair-fail-js base "ambiguous"
                                                            (:seon.repair/ambiguous pick))
              (:seon.repair/none? pick)     (repair-fail-js base "no-candidate" cands)
              :else
              (let [to (:seon.repair/to (:seon.repair/winner pick))]
                (recur (candidates/substitute-symbol code* from to)
                       (conj fixes {:seon.repair/from from
                                    :seon.repair/to   to}))))))))))

;; ============================================================
;; Wire boundary — clj result → JSON-serializable JS object.
;; ============================================================

(defn- result->js
  "Flatten an [[eval-form]] result + the echoed `op`/`id` to a
   `JSON.stringify`-able JS value. Keyword `:kind` → its NAME string."
  [op id {ok? :seon.eval/ok? value :seon.eval/value error :seon/error}]
  (let [base #js {:op   op
                  :tier "eval"
                  :ok   (boolean ok?)}]
    (when (some? id) (gobj-set base "id" id))
    (if ok?
      (gobj-set base "value" value)
      (gobj-set base "error" #js {:kind    (name (:seon.error/kind error))
                                  :message (:seon.error/message error)}))
    base))

(defn- parse-request
  "Parse one stdin line into
   `{:op :id :code :budget-ms :vars :graph-names}`. Accepts a JSON OBJECT
   (`budget_ms`/`budget-ms` both honored; `vars` filters run-tests;
   `graph_names` feeds repair candidates) OR a bare JSON STRING (treated as
   `{op:\"eval\", code:<string>}`, matching the parse-server framing)."
  [line]
  (let [parsed (try (.parse js/JSON line) (catch :default _ nil))]
    (cond
      (string? parsed)
      {:op "eval" :id nil :code parsed :budget-ms nil}

      (and (object? parsed) (some? parsed))
      {:op          (or (gobj-get parsed "op") "eval")
       :id          (gobj-get parsed "id")
       :code        (or (gobj-get parsed "code") "")
       :budget-ms   (or (gobj-get parsed "budget_ms") (gobj-get parsed "budget-ms"))
       :vars        (some-> (gobj-get parsed "vars") vec)
       :graph-names (some-> (gobj-get parsed "graph_names") vec)}

      :else
      {:op "eval" :id nil :code "" :budget-ms nil})))

(defn handle-line
  "One request line → a Promise of one JSON result line (string).

   Ops: `eval` (the default — compile + bounded execute), `run-tests` (the
   session's deftest vars), `repair` (the autofix pipeline). Every op is
   machine-readable and never rejects."
  [line]
  (let [{:keys [op id code budget-ms] :as req} (parse-request line)]
    (case op
      "run-tests" (-> (run-tests req)
                      (.then (fn [out] (.stringify js/JSON out))))
      "repair"    (-> (repair req)
                      (.then (fn [out] (.stringify js/JSON out))))
      (-> (eval-form code budget-ms)
          (.then (fn [result]
                   (.stringify js/JSON (result->js op id result))))))))

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
  "Bundle entry — `--serve` line server, or one-shot stdin eval.

   Two modes:

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
