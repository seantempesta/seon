(ns env-probes.probe-a-env-on-fork
  "Probe A — environment-on-fork carriage across thread hops.

  Falsifies (or confirms) the sealed seon.env claim that an environment value
  attached to a per-turn `sci/fork` travels WITH interpreted code across any
  thread, so that work handed to a raw Thread or a virtual-thread executor
  from INSIDE evaluated code still resolves ITS fork's environment, and two
  concurrent forks can never observe each other's.

  Three arms, all run concurrently over N forks with repetition:

  A1 `:interrupt-fn` (sci-native ctx carriage). Each fork gets its own
     `:interrupt-fn` on the ctx. `fns/fun` lifts it off the ctx captured at
     fn-creation time (reference-code/sci/src/sci/impl/fns.cljc:40,64,152),
     so every recorded entrance proves WHICH fork's ctx the running closure
     is evaluating against, on whatever thread it runs.

  A2 the environment value itself. Each fork carries `:seon/environment` and
     a per-fork host function that reads it off the fork ctx AT CALL TIME —
     the provider shape the P17 hook will take (research verdict (b), third
     bullet: install the leaf per fork as a closure when no Var-identity hook
     applies).

  A3 the shared call-preparation hook. ONE host Var installed on the BASE ctx
     and ONE hook function shared by every fork; the hook is read from the
     RUNTIME ctx inside the node body
     (reference-code/sci/src/sci/impl/analyzer.cljc, commit a072c8e) and
     supplies `(:seon/environment ctx)` as the call's argument. Nothing is
     per-fork except the ctx, so this is the claim in its strongest form.

  A4 negative control — `sci.ctx-store/get-ctx`
     (reference-code/sci/src/sci/ctx_store.cljc:9-13) is a Clojure dynamic
     var. Expected to resolve on the evaluating thread and to FAIL off it.
     Its failure is the audited ambient defect, reproduced deliberately.

  Load-only; no cluster, no database. See tmp/env-probes/RUN.md."
  (:require [clojure.pprint :as pprint]
            [sci.core :as sci]
            [sci.ctx-store :as ctx-store]
            [seon.sci.eval :as sci.eval])
  (:import [java.util.concurrent Callable ExecutorService Executors Future]))

(def ^:private probe-ns (sci/create-ns 'probe))

;; The one shared Var the call-preparation hook prepares arguments for.
(def ^:private hook-target (atom nil))

(defn- this-thread
  []
  (let [t (Thread/currentThread)]
    {:probe/thread-name (.getName t)
     :probe/thread-id (.threadId t)
     :probe/virtual? (.isVirtual t)}))

(defn- capture
  [f]
  (let [started (System/nanoTime)]
    (try
      (assoc (this-thread) :probe/value (f)
             :probe/started-ns started :probe/ended-ns (System/nanoTime))
      (catch Throwable failure
        (assoc (this-thread)
               :probe/started-ns started :probe/ended-ns (System/nanoTime)
               :probe/error (str (class failure) ": "
                                 (ex-message failure)))))))

(defn- hold
  "Sleep briefly so concurrent forks are provably inside their work together."
  []
  (Thread/sleep 5)
  :held)

(defn- on-raw-thread
  "Run `f` on a fresh platform Thread, join, return its captured result."
  [f]
  (let [result (atom nil)
        thread (Thread. ^Runnable (fn [] (reset! result (capture f)))
                        (str "env-probe-raw-" (rand-int 1000000)))]
    (.start thread)
    (.join thread)
    @result))

(defn- on-virtual-thread
  "Run `f` on a virtual thread, await it, return its captured result."
  [f]
  (with-open [^ExecutorService executor
              (Executors/newVirtualThreadPerTaskExecutor)]
    (.get ^Future (.submit executor ^Callable (fn [] (capture f))))))

(defn- ctx-store-environment
  "Read the environment through sci's process-global ctx store (the control)."
  []
  (try
    (:probe/fork-id (:seon/environment (ctx-store/get-ctx)))
    (catch Throwable failure
      {:probe/ctx-store-error (ex-message failure)})))

(defn- environment-report
  "The host leaf whose one argument the call-preparation hook supplies."
  [environment]
  {:probe/env-id (:probe/fork-id environment)
   :probe/env-token (:probe/token environment)})

(defn call-preparation-hook
  "ONE hook value, shared by every fork, reading the RUNTIME ctx it is given."
  [ctx sci-var args]
  (if (identical? sci-var @hook-target)
    [(:seon/environment ctx)]
    args))

(defn base-ctx
  "One shared base ctx with the host thread-crossing helpers installed."
  []
  (let [ctx (sci.eval/build-base-ctx)
        env-via-hook (sci/new-var 'env-via-hook environment-report
                                  {:ns probe-ns})]
    (reset! hook-target env-via-hook)
    (sci/add-namespace!
     ctx 'probe
     {'on-raw-thread
      (sci/new-var 'on-raw-thread on-raw-thread {:ns probe-ns})
      'on-virtual-thread
      (sci/new-var 'on-virtual-thread on-virtual-thread {:ns probe-ns})
      'thread (sci/new-var 'thread this-thread {:ns probe-ns})
      'here (sci/new-var 'here (fn [f] (capture f)) {:ns probe-ns})
      'hold (sci/new-var 'hold hold {:ns probe-ns})
      'env-via-hook env-via-hook
      'ctx-store-env-id
      (sci/new-var 'ctx-store-env-id ctx-store-environment {:ns probe-ns})})
    ctx))

;; ONE source form, evaluated identically in every fork. Nothing here names a
;; fork: whatever distinguishes the results came from the ctx.
(def probe-source
  '(let [closure (fn []
                   (probe/hold)
                   {:probe/env-id (probe/env-id)
                    :probe/env-token (probe/env-token)
                    :probe/hook (probe/env-via-hook)
                    :probe/thread (probe/thread)})]
     {:probe/same-thread (probe/here closure)
      :probe/raw-thread (probe/on-raw-thread closure)
      :probe/virtual-thread (probe/on-virtual-thread closure)
      :probe/ctx-store-same-thread (probe/ctx-store-env-id)
      :probe/ctx-store-virtual-thread
      (probe/on-virtual-thread probe/ctx-store-env-id)}))

(defn- build-fork
  "One fork carrying its own environment, interrupt-fn sink, and providers."
  [base fork-id]
  (let [token (str (random-uuid))
        sink (atom [])
        holder (volatile! nil)
        ctx (-> (sci/fork base)
                (assoc :seon/environment {:probe/fork-id fork-id
                                          :probe/token token}
                       ;; A1: sci lifts this off the ctx captured at fn
                       ;; creation, so it reports the evaluating fork.
                       :call-preparation-hook call-preparation-hook
                       :interrupt-fn
                       (fn []
                         (let [t (Thread/currentThread)]
                           (swap! sink conj
                                  [fork-id (.threadId t) (.isVirtual t)])))))]
    (vreset! holder ctx)
    ;; A2: providers read the environment off the fork ctx at call time.
    (sci/add-namespace!
     ctx 'probe
     {'env-id (sci/new-var 'env-id
                           (fn [] (:probe/fork-id
                                   (:seon/environment @holder)))
                           {:ns probe-ns})
      'env-token (sci/new-var 'env-token
                              (fn [] (:probe/token
                                      (:seon/environment @holder)))
                              {:ns probe-ns})})
    {:probe/fork-id fork-id
     :probe/token token
     :probe/ctx ctx
     :probe/sink sink}))

(defn- check-arm
  "Compare one thread-arm result against the fork's expected environment."
  [fork-id token where result]
  (let [{:probe/keys [value error thread-name thread-id virtual?]} result
        {actual-id :probe/env-id actual-token :probe/env-token
         hook :probe/hook inner :probe/thread} value]
    (cond
      error {:probe/where where :probe/failure :error :probe/detail error}

      (not= fork-id actual-id)
      {:probe/where where :probe/failure :wrong-fork-id
       :probe/expected fork-id :probe/actual actual-id
       :probe/thread thread-name}

      (not= token actual-token)
      {:probe/where where :probe/failure :wrong-token
       :probe/expected token :probe/actual actual-token
       :probe/thread thread-name}

      (not= fork-id (:probe/env-id hook))
      {:probe/where where :probe/failure :hook-wrong-fork-id
       :probe/expected fork-id :probe/actual (:probe/env-id hook)
       :probe/thread thread-name}

      (not= token (:probe/env-token hook))
      {:probe/where where :probe/failure :hook-wrong-token
       :probe/expected token :probe/actual (:probe/env-token hook)
       :probe/thread thread-name}

      (not= thread-id (:probe/thread-id inner))
      {:probe/where where :probe/failure :thread-identity-disagreement
       :probe/outer thread-id :probe/inner (:probe/thread-id inner)}

      :else {:probe/where where :probe/ok true
             :probe/thread-name thread-name :probe/thread-id thread-id
             :probe/virtual? virtual?})))

(defn- evaluate-fork
  [{fork-id :probe/fork-id token :probe/token
    ctx :probe/ctx sink :probe/sink}]
  (let [main-thread (.threadId (Thread/currentThread))
        result (sci/eval-form ctx probe-source)
        arms (mapv (fn [[k where]]
                     (check-arm fork-id token where (get result k)))
                   [[:probe/same-thread :same-thread]
                    [:probe/raw-thread :raw-thread]
                    [:probe/virtual-thread :virtual-thread]])
        entrances @sink
        foreign (into #{} (comp (map first) (remove #(= fork-id %))) entrances)
        entrance-threads (into #{} (map (fn [e] (subvec e 1))) entrances)
        crossed (into #{} (remove #(= main-thread (first %))) entrance-threads)
        failures (filterv :probe/failure arms)]
    {:probe/fork-id fork-id
     :probe/main-thread-id main-thread
     :probe/arms arms
     :probe/interrupt-fn-entrances (count entrances)
     :probe/interrupt-fn-threads entrance-threads
     :probe/interrupt-fn-crossed-threads crossed
     :probe/foreign-fork-ids foreign
     :probe/ctx-store-same-thread (:probe/ctx-store-same-thread result)
     :probe/ctx-store-virtual-thread (:probe/ctx-store-virtual-thread result)
     :probe/off-thread-intervals
     (mapv (fn [k] (let [r (get result k)]
                     [(:probe/started-ns r) (:probe/ended-ns r)]))
           [:probe/raw-thread :probe/virtual-thread])
     :probe/failures
     (cond-> failures
       (seq foreign)
       (conj {:probe/fork-id fork-id
              :probe/failure :foreign-interrupt-fn-observation
              :probe/actual foreign})

       (empty? crossed)
       (conj {:probe/fork-id fork-id
              :probe/failure :no-off-thread-interrupt-entrance
              :probe/actual entrance-threads}))}))

(defn- run-round
  [base fork-count round]
  (let [forks (mapv #(build-fork base [round %]) (range fork-count))]
    (with-open [^ExecutorService executor
                (Executors/newVirtualThreadPerTaskExecutor)]
      (->> forks
           (mapv (fn [fork]
                   (.submit executor ^Callable (fn [] (evaluate-fork fork)))))
           (mapv (fn [^Future future] (.get future)))))))

(def ^:private closure-source
  '(fn [] {:probe/env-id (probe/env-id)
           :probe/hook (probe/env-via-hook)
           :probe/thread (probe/thread)}))

(defn cross-fork-carriage
  "Where does a closure BUILT in fork A resolve when fork B calls it?

  The research names this the containment obligation: a live fn object that
  escapes its fork carries its fork's ctx, so it keeps resolving fork A's
  environment no matter who calls it. Recorded as evidence, not as a
  pass/fail arm."
  [base]
  (let [a (build-fork base :fork-a)
        b (build-fork base :fork-b)
        closure (sci/eval-form (:probe/ctx a) closure-source)]
    (sci/add-namespace! (:probe/ctx b) 'probe
                        {'foreign (sci/new-var 'foreign closure
                                               {:ns probe-ns})})
    {:probe/fork-a-id (:probe/fork-id a)
     :probe/fork-b-id (:probe/fork-id b)
     :probe/called-from-host (closure)
     :probe/called-from-host-virtual-thread (on-virtual-thread closure)
     :probe/called-from-fork-b
     (sci/eval-form (:probe/ctx b) '(probe/foreign))
     :probe/fork-b-own (sci/eval-form (:probe/ctx b) (list closure-source))
     :probe/fork-a-interrupt-entrances (count @(:probe/sink a))
     :probe/fork-b-interrupt-entrances (count @(:probe/sink b))}))

(defn run
  "Run probe A. Returns a verdict value; no test framework." 
  ([] (run {:probe/fork-count 24 :probe/rounds 8}))
  ([{fork-count :probe/fork-count rounds :probe/rounds}]
   (let [base (base-ctx)
         results (into [] (mapcat #(run-round base fork-count %)) (range rounds))
         failures (into [] (mapcat :probe/failures) results)
         ctx-store-on-thread (mapv :probe/ctx-store-same-thread results)
         ctx-store-off-thread (mapv :probe/ctx-store-virtual-thread results)
         off-thread-errors
         (count (filter #(:probe/ctx-store-error (:probe/value %))
                        ctx-store-off-thread))
         crossed (into #{} (mapcat :probe/interrupt-fn-crossed-threads) results)
         events (->> (mapcat :probe/off-thread-intervals results)
                     (mapcat (fn [[a b]] [[a 1] [b -1]]))
                     (sort-by (juxt first second))
                     vec)
         peak-overlap (:peak (reduce (fn [acc [_ delta]]
                                       (let [live (+ (:live acc) delta)]
                                         {:live live
                                          :peak (max (:peak acc) live)}))
                                     {:live 0 :peak 0}
                                     events))]
     {:probe/name "A — environment on fork across thread hops"
      :probe/verdict (if (empty? failures) :pass :fail)
      :probe/fork-count fork-count
      :probe/rounds rounds
      :probe/evaluations (count results)
      :probe/arms-checked (reduce + (map (comp count :probe/arms) results))
      :probe/interrupt-fn-entrances
      (reduce + (map :probe/interrupt-fn-entrances results))
      :probe/off-thread-interrupt-fn-thread-sample (vec (take 6 crossed))
      :probe/peak-concurrent-off-thread-work peak-overlap
      :probe/ctx-store-control
      {:probe/on-thread-resolved
       (count (filter vector? ctx-store-on-thread))
       :probe/on-thread-sample (first ctx-store-on-thread)
       :probe/off-thread-total (count ctx-store-off-thread)
       :probe/off-thread-failures off-thread-errors
       :probe/off-thread-sample (first ctx-store-off-thread)}
      :probe/cross-fork-carriage (cross-fork-carriage base)
      :probe/failures failures})))

(defn -main [& _]
  (pprint/pprint (run)))
