(ns probe.adapter
  "B1 — the sci engine behind the EXISTING eval boundary contract.

   Research harness only, never production code. This is the thinnest
   adapter that satisfies `seon.eval`'s engine seam over a sci context:

   - `eval*` mirrors `seon.eval/eval`'s envelope: it never throws and
     returns {:seon.eval/ok? true :seon.eval/value v
              :seon.eval/ending-ns sym}
     or      {:seon.eval/ok? false :seon/error {...}
              :seon.eval/ending-ns sym}.
   - `maybe-await-value` mirrors the one-eval-await-persist contract:
     budget/defer wrappers, Promise auto-await bounded by
     `race-timeout`, timeout hands back ::pending-promise, rejection
     becomes an errors-as-values map.
   - `race-timeout`/`timed-out?`/`budget`/`defer` are direct ports of
     the production owners (seon.eval lines 126-275) because they are
     ENGINE-INDEPENDENT — a production cutover would reuse the
     originals verbatim; the port exists only so this harness has no
     production requires.
   - `result-var-ref?` / `result-miss-message` port the graceful-miss
     contract for dead `result/<id>` references.
   - authored-source loading flows through sci's `:load-fn` (the
     `guarded-load*` seam analog); the admitted binding table is the
     ctx `:namespaces` map (the admission analog).
   - a per-eval wall-clock deadline is enforced IN-PROCESS through
     sci's `:interrupt-fn` + `sci.interrupt/interrupt!` — the
     self-host child documents the opposite (a tight CPU loop cannot
     be cancelled)."
  (:require
   [clojure.string :as str]
   [cljs.tools.reader :as tools-reader]
   [cljs.tools.reader.reader-types :as reader-types]
   [malli.core :as m]
   [sci.core :as sci]
   [sci.impl.types :as sci-types]
   [sci.interrupt :as interrupt]
   [seon.ai.tokens :as tokens]
   [seon.schema :as schema]))

;;; ------------------------------------------------- ported value wrappers

(def default-timeout-ms 10000)

(deftype Budgeted [ms value])

(defn budget
  "Port of seon.eval/budget — one deadline attached to one value."
  [ms inner]
  (if (instance? Budgeted inner)
    (->Budgeted ms (.-value inner))
    (->Budgeted ms inner)))

(deftype Deferred [promise])

(defn defer
  "Port of seon.eval/defer — explicit opt-out of auto-await."
  [v]
  (let [v (if (instance? Budgeted v) (.-value v) v)]
    (if (instance? js/Promise v)
      (->Deferred v)
      v)))

(defonce ^:private timeout-sentinel #js {:_seon_eval_timeout true})

(defn timed-out?
  "Identity check for race-timeout's sentinel (never shape)."
  [v]
  (identical? v timeout-sentinel))

(defn race-timeout
  "Port of seon.eval/race-timeout. Written with explicit Promise
   combinators (no ^:async) to sidestep the compiled-CLJS
   async-try-expression trap the feasibility probe documented."
  ([inner ms] (race-timeout inner ms (fn [] nil)))
  ([inner ms on-timeout]
   (let [!timer (volatile! nil)
         timer  (js/Promise.
                 (fn [resolve _]
                   (vreset!
                    !timer
                    (js/setTimeout
                     (fn []
                       (resolve timeout-sentinel)
                       (try (on-timeout)
                            (catch :default _ nil)))
                     ms))))]
     (-> (js/Promise.race #js [inner timer])
         (.finally (fn [] (js/clearTimeout @!timer)))))))

;;; --------------------------------------------------- error -> value map

(defn ->error-map
  "Minimal seon.error/->map analog: kind + message (+ sci location)."
  [e]
  (let [msg  (or (some-> e .-message) (str e))
        data (ex-data e)
        unresolved? (str/includes? msg "Unable to resolve symbol")
        missing-ns? (str/includes? msg "Could not find namespace")]
    (cond-> {:seon.error/kind (cond unresolved? :compile
                                    missing-ns? :compile
                                    :else :eval)
             :seon.error/message msg}
      (:line data)   (assoc :seon.error/line (:line data))
      (:column data) (assoc :seon.error/column (:column data)))))

;;; ------------------------------------------------- result/<id> contract

(def result-vars-cap 40)

(defn result-var-ref?
  "Port of seon.eval/result-var-ref? — exactly one bare result/<id>."
  [form-str]
  (let [s (str/trim (str form-str))]
    (boolean
     (and (seq s)
          (try
            (let [rdr (reader-types/string-push-back-reader s)
                  sym (tools-reader/read {:eof ::eof :read-cond :allow} rdr)
                  nxt (tools-reader/read {:eof ::eof :read-cond :allow} rdr)]
              (and (symbol? sym)
                   (= "result" (namespace sym))
                   (= ::eof nxt)))
            (catch :default _ false))))))

(defn result-miss-message
  [ref-sym]
  (str ref-sym " isn't live (a prior session, or pruned past the last "
       result-vars-cap
       " results) — re-run its form to recompute it. "
       "Only recent results stay referenceable as `result/<id>` vars."))

;;; --------------------------------------------------------- sci context

(defn- fake-transact!
  "seon.db/transact! stand-in: ^:async db verb returning a Promise of
   the standard envelope, like the real child's admitted binding."
  [!facts tx]
  (js/Promise.resolve
   {:seon.db/ok? true
    :tx-data tx
    :db-after {:t (count (swap! !facts conj tx))}}))

(defn make-ctx
  "A sci context armed the way the child's admitted binding table would
   be: compiled Seon fns, the schema registry surface, malli, db-shaped
   async host verbs, js interop, authored-source loading, and the
   in-process interrupt deadline.

   Returns {:probe/ctx ctx :probe/!deadline atom :probe/!authored atom}."
  ([] (make-ctx {}))
  ([{:probe/keys [authored-sources]}]
   (let [!deadline (atom nil)
         !authored (atom (or authored-sources {}))
         !facts    (atom [])
         ctx (sci/init
              {:classes {'js js/globalThis :allow :all}
               :interrupt-fn
               (fn []
                 (when-let [dl @!deadline]
                   (when (> (js/Date.now) dl)
                     (interrupt/interrupt! "eval budget exceeded"))))
               :load-fn
               (fn [{:keys [libname]}]
                 (when-let [src (get @!authored libname)]
                   {:source src}))
               :namespaces
               {'clojure.core interrupt/clojure-core
                'result {}
                'seon.ai.tokens {'estimate tokens/estimate
                                 'chars->tokens tokens/chars->tokens
                                 'estimate-chars tokens/estimate-chars
                                 'chars-per-token tokens/chars-per-token}
                'seon.schema {'register! schema/register!
                              'current-keys schema/current-keys
                              'valid-candidate-value?
                              schema/valid-candidate-value?}
                'malli.core {'validate m/validate
                             'explain m/explain
                             'schema m/schema}
                'seon.db {'transact! (partial fake-transact! !facts)
                          'query (fn [_q] (js/Promise.resolve @!facts))}
                'seon.agent.message {'user (fn [s] {:probe/message s})}
                'my.plan {'plan! (fn [& _] {:probe/plan true})}}})]
     {:probe/ctx ctx
      :probe/!deadline !deadline
      :probe/!authored !authored})))

;;; --------------------------------------------------------- eval seam

(defn- ns->sym [ns-obj]
  (when ns-obj (sci-types/getName ns-obj)))

(defn- ensure-ns!
  "Make `ns-sym` exist in the ctx (in-ns creates it; the surrounding
   eval-string* binding restores the caller's current ns after)."
  [ctx ns-sym]
  (or (sci/find-ns ctx ns-sym)
      (do (sci/eval-string* ctx (str "(in-ns '" ns-sym ")"))
          (sci/find-ns ctx ns-sym))))

(defn eval*
  "The engine seam: eval `form-str` in the sci ctx, starting in
   `:probe/starting-ns`, bounded by `:probe/deadline-ms` (wall-clock,
   enforced through :interrupt-fn on interpreted fn/loop entry).
   Returns the production eval envelope; never throws."
  [{:probe/keys [ctx !deadline] :as _harness} form-str
   {:probe/keys [starting-ns deadline-ms]}]
  (let [ns-sym (or starting-ns 'user)
        ns-obj (ensure-ns! ctx ns-sym)
        result-ref? (result-var-ref? form-str)]
    (when deadline-ms
      (reset! !deadline (+ (js/Date.now) deadline-ms)))
    (try
      (let [{:keys [val ns]} (sci/eval-string+ ctx form-str {:ns ns-obj})]
        {:seon.eval/ok? true
         :seon.eval/value val
         :seon.eval/ending-ns (or (ns->sym ns) ns-sym)})
      (catch :default e
        (if (and result-ref?
                 (str/includes? (or (some-> e .-message) "")
                                "Unable to resolve symbol"))
          {:seon.eval/ok? true
           :seon.eval/value (result-miss-message (str/trim (str form-str)))
           :seon.eval/ending-ns ns-sym}
          {:seon.eval/ok? false
           :seon/error (->error-map e)
           :seon.eval/ending-ns ns-sym}))
      (finally
        (reset! !deadline nil)))))

(defn maybe-await-value
  "Port of seon.eval/maybe-await-value over the same wrappers. Always
   returns a Promise of the envelope (the production fn is ^:async)."
  [runtime-value]
  (let [budgeted? (instance? Budgeted runtime-value)
        v  (if budgeted? (.-value runtime-value) runtime-value)
        ms (if budgeted? (.-ms runtime-value) default-timeout-ms)]
    (cond
      (instance? Deferred v)
      (js/Promise.resolve
       {:seon.eval/ok? false :seon.eval/pending-promise (.-promise v)})

      (instance? js/Promise v)
      (-> (race-timeout v ms)
          (.then
           (fn [raced]
             (if (timed-out? raced)
               {:seon.eval/ok? false :seon.eval/pending-promise v}
               {:seon.eval/ok? true :seon.eval/value raced})))
          (.catch
           (fn [e]
             {:seon.eval/ok? false :seon/error (->error-map e)})))

      :else
      (js/Promise.resolve {:seon.eval/ok? true :seon.eval/value v}))))

(defn full-eval
  "eval* + maybe-await-value — the one-eval-await-persist composition
   the eval-batch path performs for every form. Returns a Promise of
   the final envelope (ending-ns carried through)."
  [harness form-str opts]
  (let [r (eval* harness form-str opts)]
    (if (:seon.eval/ok? r)
      (-> (maybe-await-value (:seon.eval/value r))
          (.then (fn [awaited]
                   (assoc awaited :seon.eval/ending-ns
                          (:seon.eval/ending-ns r)))))
      (js/Promise.resolve r))))

;;; ----------------------------------------------------- result binding

(defn bind-result-var!
  "Bind a live value at result/<id> (sci var intern — the sci analog of
   the analyzer def + globalThis slot pair)."
  [{:probe/keys [ctx]} id v]
  (sci/intern ctx 'result (symbol (str id)) v))
