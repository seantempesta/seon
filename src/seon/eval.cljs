(ns seon.eval
  "Agent eval surface (spec-02 §2.5 / spec-03 H-1a). SAFE BY DEFAULT —
   `eval` returns {:ok true :value v} or {:ok false :error <error-map>}.
   A throw, compile error, or async rejection — all return as values.
   The agent session continues.

   The unadorned name `eval` is the safe one; we do NOT ship a public
   strict variant. Callers that want raw throw semantics can drop down
   to `cljs.js/eval-str` themselves.

   `eval` shadows clojure.core/eval inside this namespace. That's
   deliberate — agents type `(seon.eval/eval ...)` from outside this
   ns. seon internals that need clojure.core's `eval` should import as
   `core/eval`.

   ## REPL semantics

   Vars defined in one eval persist for the next (compile-state is
   process-shared, defonce'd at boot in seon.client).

   `:ns` is tracked per-call: `cljs.js/eval-str` returns the ending ns,
   which we feed back as the next call's `:ns` parameter. That's how
   `(ns other-ns)` switches affect subsequent forms. Smart REPL default:
   unqualified vars resolve in the current ns.

   ## Probe-confirmed gotchas (cljs.js + bootstrap target)

   - **Bare value-def reads don't resolve across eval-str calls.**
     `(def x 42)` then `x` returns nil. Use atoms instead:
     `(def !x (atom 42))` + `@!x` works. Fns are unaffected — they
     cross namespaces fine. The agent's home ns is set up with atoms
     for `!session-id` / `!results` / `!current-ns` exactly because of
     this.
   - **`(in-ns 'foo)` is not bootstrapped.** Use `(ns foo)` to switch."
  (:refer-clojure :exclude [eval])
  (:require [cljs.js :as cljs]
            [clojure.string :as str]
            [shadow.cljs.bootstrap.node :as boot]
            [seon.db :as db]
            [seon.error :as error]))

;; ============================================================
;; Bootstrap init — load cljs.core + cljs.core$macros from the
;; :bootstrap shadow build into a fresh compile-state. ^:async so
;; callers can `(await ...)` it from straight-line agent code.
;; ============================================================

(defn ^:async init-bootstrap!
  "Initialize a fresh compile-state from out/bootstrap/. Returns the
   compile-state, ready for `eval` / `eval-batch!`. Stores cljs.core
   on globalThis (via goog.globalEval inside shadow's loader); without
   that, find-ns-obj fails on the first macro form and eval-str
   throws TypeError on findInternedVar."
  []
  (let [state (cljs/empty-state)]
    (await (js/Promise.
             (fn [resolve _reject]
               (boot/init state
                          {:path "out/bootstrap"
                           :load-on-init '#{cljs.core}}
                          (fn [] (resolve nil))))))
    (when-not (and (some? (.-cljs js/global))
                   (some? (.-core (.-cljs js/global))))
      (throw (js/Error.
               "bootstrap loader did not put cljs.core on globalThis")))
    state))

;; ============================================================
;; Core eval — one form. Safe by default.
;; ============================================================

(defn ^:async ^:private raw-eval
  "Internal — returns a Promise that resolves with {:value v :ns ns}
   or rejects with the error. The public `eval` catches both."
  [compile-state form-str ns-sym analyze-deps?]
  (js/Promise.
    (fn [resolve reject]
      (cljs/eval-str compile-state form-str 'seon.dynamic
        {:eval          cljs/js-eval
         :load          (partial boot/load compile-state)
         :ns            ns-sym
         :context       :statement
         :def-emits-var true
         :analyze-deps  analyze-deps?}
        (fn [{:keys [error value ns]}]
          (if error
            (reject error)
            (resolve {:value value :ns ns})))))))

(defn ^:async eval
  "Evaluate a string of CLJS in the agent's persistent compile-state.
   Returns:
     {:ok true  :value v :ns ns}              on success
     {:ok false :error <seon.error/->map>}    on any failure
   Never throws; never rejects.

   Opts (all optional):
     :ns            target namespace (default `cljs.user`). The
                    returned `:ns` is the ENDING ns — `(ns other)`
                    forms switch it. Callers that want REPL-style
                    ns-tracking feed `:ns` from one call into the
                    next call's `:ns` arg.
     :analyze-deps? whether cljs.js should recursively analyze refs
                    in the form (default `false`). The bootstrap
                    bundle only contains `cljs.core`, so any form
                    calling `seon.db/*` or other non-bundled nses
                    MUST run with this off — otherwise the analyzer
                    dies on `ns seon.db not available`. With it off,
                    the analyzer emits :undeclared-var warnings but
                    still emits JS that resolves at runtime via the
                    already-loaded globalThis vars (the `:client`
                    bundle's emission).

   For setup forms that need cljs.core's macro refers wired up via
   `(ns …)` analysis, pass `:analyze-deps? true` explicitly."
  ([compile-state form-str]
   (eval compile-state form-str nil))
  ([compile-state form-str {:keys [ns analyze-deps?]
                            :or   {ns            'cljs.user
                                   analyze-deps? false}}]
   (try
     (let [{:keys [value] :as result}
           (await (raw-eval compile-state form-str ns analyze-deps?))]
       {:ok true :value value :ns (:ns result)})
     (catch :default e
       {:ok false :error (error/->map e)}))))

;; ============================================================
;; Per-agent namespace setup. Run once per agent at boot. Primes the
;; agent's home ns with atoms + accessor fns. Probe-validated patterns:
;; atoms for state (bare value-def reads don't resolve cross-eval-str),
;; fns for read sugar.
;; ============================================================

;; ============================================================
;; Results store. Lives on globalThis so any value (including
;; non-readable CLJS objects like datahike DB tagged literals) can
;; be stashed and looked up. We don't go through pr-str/read-string
;; here — the value is the raw object.
;;
;; Key shape: "__seon_results_<eval-id>"
;; Agent reads via `(seon.agent.<id>/result :abc123)` which is
;; set up by setup-agent-ns! to do the same js/Reflect.get lookup.
;; ============================================================

(def ^:private results-key-prefix "__seon_results_")

(defn- result-key [eval-id]
  (str results-key-prefix eval-id))

(defn stash-result-raw!
  "Stash a raw value (any type) on globalThis keyed by the eval-id.
   No pr-str round-trip — value-type-agnostic. Soft-fails on impossible
   sets (logs + ignores)."
  [eval-id value]
  (try
    (js/Reflect.set js/globalThis (result-key eval-id) value)
    (catch :default e
      (js/console.warn "[seon.eval/stash-result-raw!] failed for"
                       (pr-str eval-id) "—"
                       (error/->message e)))))

(defn ^:async setup-agent-ns!
  "Create + initialize the agent's home namespace. Returns the agent-ns
   symbol (for convenience — same as the input). Idempotent: re-running
   resets atoms to initial values.

   After setup, agent code running in this ns has access to:
     !session-id  — atom holding the session-id string
     !current-ns  — atom holding the agent's current ns symbol
     (session-id) — sugar for @!session-id
     (result id)  — looks up the live value of a prior eval, keyed by
                    its 10-char id (string or keyword). Backed by
                    globalThis so any value type round-trips.

   Uses `:analyze-deps? true` so the `(ns …)` form analyzes cljs.core's
   refer map and wires up implicit macro refers (defn, str, atom, etc.)
   for subsequent forms in the new ns."
  [compile-state agent-ns-sym session-id]
  (let [setup-src
        (str "(ns " agent-ns-sym ")"
             "(def !session-id (atom " (pr-str session-id) "))"
             "(def !current-ns (atom '" agent-ns-sym "))"
             "(defn session-id [] @!session-id)"
             "(defn result [id]"
             "  (js/Reflect.get js/globalThis"
             "    (str " (pr-str results-key-prefix)
             "         (if (keyword? id) (name id) (str id)))))"
             ":seon.eval/setup-ok")
        r (await (eval compile-state setup-src
                       {:ns 'cljs.user
                        :analyze-deps? true}))]
    (when-not (:ok r)
      (throw (ex-info "setup-agent-ns! failed"
                      {:agent-ns agent-ns-sym
                       :error    (:error r)})))
    agent-ns-sym))

;; ============================================================
;; eval-batch! — the REPL harness primitive. Takes parsed pairs from
;; seon.repl/parse-forms; evaluates each in the agent's compile-state
;; with PARTIAL-FAILURE semantics (form N+1 always runs, even if N
;; failed); persists each as a :seon.eval entity; stashes the live
;; result in the agent's !results atom. Returns the ordered vector of
;; eval-id strings.
;;
;; Per spec-02 §2.5: every form is safe-by-default. The eval surface
;; never throws; the agent session is never killed by a bad form.
;; ============================================================

(defn- new-eval-id
  "10-char base62 id. Local copy of agent.cljs's new-id! to avoid the
   eval ↔ agent require cycle (agent rewrites land in H-1a too)."
  []
  (let [alphabet "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        rand-ch  #(nth alphabet (rand-int 62))]
    (apply str (repeatedly 10 rand-ch))))

(defn ^:async ^:private read-current-ns
  "Read @!current-ns from the agent's home ns. Returns the symbol, or
   `agent-ns-sym` if reading failed (fresh-boot fallback)."
  [compile-state agent-ns-sym]
  (let [src (str "@" agent-ns-sym "/!current-ns")
        r   (await (eval compile-state src {:ns agent-ns-sym}))]
    (if (:ok r) (:value r) agent-ns-sym)))

(defn ^:async ^:private update-current-ns!
  "Write @!current-ns = new-ns. Soft-fails (logs + ignores)."
  [compile-state agent-ns-sym new-ns]
  (let [src (str "(reset! " agent-ns-sym "/!current-ns '" new-ns ")")
        r   (await (eval compile-state src {:ns agent-ns-sym}))]
    (when-not (:ok r)
      (js/console.warn "[seon.eval/eval-batch!] update-current-ns! soft-fail:"
                       (-> r :error :seon.error/message)))))

(defn ^:async ^:private maybe-await-value
  "Agent-REPL ergonomic: if a form returns a Promise (because the form
   called a ^:async fn like `seon.db/transact!`), await it and return
   the resolved value. Agents don't write `await` — that's a
   CLJS-1.12.145 syntax they don't see. This makes calls to seon.db/*
   feel synchronous from inside agent forms.

   Returns {:ok true :value v} on resolution OR a non-Promise value;
           {:ok false :error <seon.error/->map>} on rejection."
  [v]
  (if (instance? js/Promise v)
    (try
      (let [resolved (await v)]
        {:ok true :value resolved})
      (catch :default e
        {:ok false :error (error/->map e)}))
    {:ok true :value v}))

(defn ^:async record-eval!
  "Transact one :seon.eval entity capturing this form's narration,
   source, and result. Soft-fails — a DB write failure is logged but
   doesn't abort the batch."
  [{:keys [eval-id session-id turn-n at narration source result]}]
  (let [tx-data [(cond-> {:seon.eval/id        eval-id
                          :seon.eval/session   [:seon.session/id session-id]
                          :seon.eval/at        at
                          :seon.eval/turn      turn-n
                          :seon.eval/narration (or narration "")
                          :seon.eval/source    source
                          :seon.eval/ok?       (boolean (:ok result))}
                   (:ok result)
                   (assoc :seon.eval/result-edn
                          (try (pr-str (:value result))
                               (catch :default _ (str (:value result)))))

                   (not (:ok result))
                   (assoc :seon.eval/error
                          (try (pr-str (:error result))
                               (catch :default _ (str (:error result))))))]
        r (await (db/transact! {:seon.db/tx-data tx-data}))]
    (when-not (:seon.db/ok? r)
      (js/console.warn "[seon.eval/eval-batch!] record-eval! tx failed:"
                       (-> r :seon.db/error :seon.error/message)
                       "— source:" source))))

(defn ^:async eval-batch!
  "Execute a sequence of parsed (narration, form) pairs as a REPL
   batch. Partial-failure: every pair gets its own try + record +
   stash; form N+1 always runs even if N failed.

   Per pair:
     1. Read current-ns from agent's atom (defaults to agent-ns-sym).
     2. (await (eval compile-state source current-ns))
     3. Update !current-ns from the result's :ns (the ending ns) —
        that's how (ns other) inside a form affects subsequent forms.
     4. Stash the live value in !results under the eval-id kw.
     5. Transact a :seon.eval entity (durable record).

   Args:
     compile-state — the bootstrap compile-state (defonce'd at boot)
     parsed        — vector of {:narration :source :form} maps
                     (from seon.repl/parse-forms)
     agent-ns-sym  — agent's home ns (e.g. 'seon.agent.seon)
     session-id    — the owning session id
     turn-n        — the turn counter

   Returns the ordered vector of eval-id strings."
  [compile-state parsed agent-ns-sym session-id turn-n]
  (let [eids (volatile! [])]
    (doseq [{:keys [narration source]} parsed]
      (let [eval-id     (new-eval-id)
            at          (js/Date.)
            current-ns  (await (read-current-ns compile-state agent-ns-sym))
            raw-result  (await (eval compile-state source
                                     {:ns current-ns
                                      :analyze-deps? false}))
            ;; Auto-await Promise return values so agent code calling
            ;; ^:async fns (seon.db/transact!, etc.) gets the resolved
            ;; value, not the Promise. If the Promise rejects, that
            ;; becomes the form's error.
            result
            (cond
              ;; Form itself failed (compile / read / runtime throw)
              (not (:ok raw-result)) raw-result
              ;; Form succeeded; the value might be a Promise.
              :else (let [r2 (await (maybe-await-value (:value raw-result)))]
                      (if (:ok r2)
                        {:ok true :value (:value r2) :ns (:ns raw-result)}
                        ;; Promise rejected — surface the rejection as the form's error
                        r2)))]
        ;; Track ending ns for REPL-style navigation. nil means the form
        ;; failed before producing one (compile error etc.); leave atom alone.
        (when (and (:ok result) (:ns raw-result))
          (await (update-current-ns! compile-state agent-ns-sym (:ns raw-result))))
        ;; Live-value stash — direct js/Reflect.set on globalThis, no
        ;; eval-str round-trip (so opaque values like datahike DB tagged
        ;; literals don't break the stash). Agent reads via (result :id).
        (when (:ok result)
          (stash-result-raw! eval-id (:value result)))
        ;; Durable record — always.
        (await (record-eval! {:eval-id    eval-id
                              :session-id session-id
                              :turn-n     turn-n
                              :at         at
                              :narration  narration
                              :source     source
                              :result     result}))
        (vswap! eids conj eval-id)))
    @eids))
