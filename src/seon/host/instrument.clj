(ns seon.host.instrument
  "Reconcile Malli instrumentation over JVM-host SCI vars.

   One fair generation-admission barrier keeps evaluations on a complete
   committed-projection/wrapper generation. Evaluations take read admission;
   projection refresh, wrapper reconciliation, and startup replay take write
   admission. The apply ledger is process-local derived state."
  (:require [malli.core :as m]
            [sci.core :as sci]
            [seon.error.instrument :as error.instrument]
            [seon.host.context :as context]
            [seon.schema :as schema])
  (:import [java.util.concurrent.locks ReentrantReadWriteLock]))

(set! *warn-on-reflection* true)

(schema/register! ::state 'some?)

(defn state
  "Create one instrumentation owner for a host generation."
  {:malli/schema [:=> [:cat :map] ::state]}
  [{::context/keys [registry projection-state]
    :keys [seon.host/contexts]}]
  {::registry registry
   ::contexts contexts
   ::projection-state projection-state
   ::generation-admission (ReentrantReadWriteLock. true)
   ::apply-ledger (atom {})})

(defn call-with-read-admission
  "Call `f` while its committed wrapper generation remains current."
  {:malli/schema [:=> [:cat ::state 'fn?] :any]}
  [instrument-state f]
  (let [lock (.readLock ^ReentrantReadWriteLock
                        (::generation-admission instrument-state))]
    (.lock lock)
    (try (f) (finally (.unlock lock)))))

(defn call-with-write-admission
  "Call `f` while new evaluations cannot enter the wrapper generation."
  {:malli/schema [:=> [:cat ::state 'fn?] :any]}
  [instrument-state f]
  (let [lock (.writeLock ^ReentrantReadWriteLock
                         (::generation-admission instrument-state))]
    (.lock lock)
    (try (f) (finally (.unlock lock)))))

(defn- root-original [root]
  (or (some-> root meta ::original-root) root))

(defn- decorated-report [sym]
  (fn [report-type data]
    (error.instrument/report-fn report-type (assoc data :fn-name sym))))

(declare reconcile-var!)

(defn- install-watch! [instrument-state sym sci-var]
  (add-watch
   sci-var ::root-redefinition
   (fn [_ _ _ new-root]
     ;; `bindRoot` notifies synchronously. A watch must never turn a valid
     ;; redefinition into a second failure path; the ledger retains any
     ;; impossible projection/wrapper mismatch for the owning apply gate.
     (try
       (let [current @(::projection-state instrument-state)]
         (when-let [projection (::context/projection current)]
           (reconcile-var! instrument-state projection sym sci-var new-root)))
       (catch Throwable throwable
         (swap! (::apply-ledger instrument-state)
                assoc sci-var
                {::symbol sym
                 ::watch-error throwable})))))
  nil)

(defn- marked-root [wrapped original sym fingerprint]
  (with-meta wrapped
    (assoc (merge (meta original) (meta wrapped))
           ::original-root original
           ::symbol sym
           ::projection-fingerprint fingerprint
           :seon.fn/source-fingerprint
           (:seon.fn/source-fingerprint (meta original)))))

(defn source-fingerprint-matches?
  "True when a function root carries the requested source fingerprint."
  {:malli/schema [:=> [:cat 'fn? :string] :boolean]}
  [root source-fingerprint]
  (= source-fingerprint
     (:seon.fn/source-fingerprint (meta (root-original root)))))

(defn- desired-root
  [projection sym current-root]
  (let [contracts (:seon.schema.projection/function-contracts projection)
        registry (:seon.schema.projection/registry projection)
        fingerprint (:seon.schema.projection/fingerprint projection)
        contract (get contracts sym)
        current-meta (meta current-root)
        already-current?
        (and contract
             (= sym (::symbol current-meta))
             (= fingerprint (::projection-fingerprint current-meta)))]
    (cond
      already-current? current-root
      contract
      (let [original (root-original current-root)
            wrapped (m/-instrument
                     {:schema contract :report (decorated-report sym)}
                     original
                     {:registry registry})]
        (marked-root wrapped original sym fingerprint))
      :else (root-original current-root))))

(defn- reconcile-var!
  ([instrument-state projection sym sci-var]
   (reconcile-var! instrument-state projection sym sci-var @sci-var))
  ([instrument-state projection sym sci-var current-root]
   (let [fingerprint (:seon.schema.projection/fingerprint projection)
         desired (desired-root projection sym current-root)
         instrumented? (some? (::projection-fingerprint (meta desired)))]
     (install-watch! instrument-state sym sci-var)
     ;; Desired generation reaches the ledger before bindRoot notifies its
     ;; watch. The marked desired root makes that nested notification a no-op.
     (swap! (::apply-ledger instrument-state)
            assoc sci-var
            {::symbol sym
             ::projection-fingerprint (when instrumented? fingerprint)})
     (when-not (identical? current-root desired)
       (sci/alter-var-root sci-var (constantly desired))))))

(defn reconcile-ephemeral-vars!
  "Instrument detached invocation vars without retaining watches or ledger rows."
  {:malli/schema [:=> [:cat ::state [:map-of :symbol 'some?]] :map]}
  [instrument-state vars-by-symbol]
  (let [current @(::projection-state instrument-state)]
    (if-let [fault (::context/fault current)]
      {:seon/error fault}
      (let [projection (::context/projection current)]
        (doseq [[sym sci-var] vars-by-symbol]
          (let [current-root @sci-var
                desired (desired-root projection sym current-root)]
            (when-not (identical? current-root desired)
              (sci/alter-var-root sci-var (constantly desired)))))
        {::projection-fingerprint
         (:seon.schema.projection/fingerprint projection)
         ::instrumented
         (count
          (filter (fn [[_ sci-var]]
                    (some? (::projection-fingerprint (meta @sci-var))))
                  vars-by-symbol))}))))

(defn- registry-var [registry sym]
  (get-in @registry
          [(symbol (namespace sym)) ::context/vars (symbol (name sym))]))

(defn- target-vars [instrument-state projection extra-contexts]
  (let [registry (::registry instrument-state)
        contexts (into (vals @(::contexts instrument-state)) extra-contexts)]
    (reduce-kv
     (fn [targets sym _contract]
       (let [shared (registry-var registry sym)
             private (keep #(sci/resolve % sym) contexts)]
         (assoc targets sym
                (vec (distinct (cond-> private shared (conj shared)))))))
     {}
     (:seon.schema.projection/function-contracts projection))))

(defn- previously-targeted [instrument-state]
  (reduce-kv
   (fn [targets sci-var {::keys [symbol]}]
     (update targets symbol (fnil conj []) sci-var))
   {}
   @(::apply-ledger instrument-state)))

(defn apply-projection!
  "Reconcile all shared and live private SCI vars to one projection."
  {:malli/schema
   [:function
    [:=> [:cat ::state :map] :map]
    [:=> [:cat ::state :map [:sequential :any]] :map]]}
  ([instrument-state projection]
   (apply-projection! instrument-state projection []))
  ([instrument-state projection extra-contexts]
   (let [current-targets (target-vars instrument-state projection extra-contexts)
         all-targets (merge-with into (previously-targeted instrument-state)
                                 current-targets)]
     (doseq [[sym vars] all-targets
             sci-var (distinct vars)]
       (reconcile-var! instrument-state projection sym sci-var))
     {::projection-fingerprint
      (:seon.schema.projection/fingerprint projection)
      ::instrumented
      (count (filter (comp some? ::projection-fingerprint val)
                     @(::apply-ledger instrument-state)))})))

(defn reconcile-current-context!
  "Reconcile a replayed private context before it becomes session-visible."
  {:malli/schema [:=> [:cat ::state :any] :map]}
  [instrument-state ctx]
  (let [current @(::projection-state instrument-state)]
    (if-let [fault (::context/fault current)]
      {:seon/error fault}
      (apply-projection! instrument-state (::context/projection current) [ctx]))))

(defn refresh-and-reconcile!
  "Refresh and reconcile one committed generation under write admission."
  {:malli/schema [:=> [:cat ::state ::context/writer ::context/committed-basis]
                  :map]}
  [instrument-state writer committed-basis]
  (call-with-write-admission
   instrument-state
   (fn []
     (let [refreshed
           (context/refresh-committed-projection!
            writer (::projection-state instrument-state) committed-basis)]
       (if (:seon/error refreshed)
         refreshed
         (do (apply-projection! instrument-state
                                (::context/projection refreshed))
             refreshed))))))
