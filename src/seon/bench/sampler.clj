(ns seon.bench.sampler
  "Random unrigged benchmark task sampler and strict post-merge `:verify-spec` verifier."
  (:require [datahike.api :as d]
            [seon.store :as store :refer [db-schema]]
            [seon.fork.calls :as fcalls]
            [clojure.string :as str]
            [seon.render.fit :as fit]
            [seon.schema :as schema]
            [seon.code :as code]))

(defn- loose-map-return? [sch]
  (let [one? (fn [s] (and (vector? s) (= :=> (first s))
                          (let [r (last s)]
                            (or (= :map r) (= [:maybe :map] r)))))]
    (or (one? sch)
        (and (vector? sch) (= :function (first sch)) (some one? (rest sch))))))

(defn- has-host-arg? [sch]
  (boolean (some #(and (qualified-keyword? %) (= "seon.host" (namespace %)))
                 (tree-seq coll? seq sch))))

(defn- reexport-target-sym [db fkey]
  (when (and db fkey (contains? (db-schema db) :seon.code.form/key))
    (when-let [fsrc (:seon.code.form/src (d/pull db '[:seon.code.form/src] [:seon.code.form/key fkey]))]
      (when-let [m (re-find #":seon\.code/reexport\s+'([a-zA-Z0-9_.\-\!\?\*]+/[a-zA-Z0-9_.\-\!\?\*]+)" (str fsrc))]
        (symbol (second m))))))

(defn sample-random-tasks
  "Deterministically samples `n` diverse, verified, unrigged real tasks live from Datahike `db` (using `seed`)
   across 3 non-vacuous pools (pure loose-return contracts, namespaces with pure defns, and under-rendered schemas)
   each carrying a serializable `:verify-spec`."
  {:malli/schema [:=> [:cat :seon.host/db [:? [:maybe :map]]] [:vector :map]]
   :cost/ticks 25000000}
  ([db] (sample-random-tasks db nil))
  ([db opts]
   (let [o (if (map? opts) opts {})
         n (max 1 (long (or (:n o) 4)))
         seed (long (or (:seed o) 42))
         timeout-ms (long (or (:timeout-ms o) 240000))
         dh (db-schema db)
         det-shuffle (fn [xs] (vec (sort-by #(hash [seed (:id %)]) xs)))
         eav1 (fn [e a] (when (dh a) (:v (first (d/datoms db :eavt e a)))))
         loose-vars
         (when (and (dh :seon.code.var/name) (dh :seon.code.var/schema))
           (->> (d/datoms db :aevt :seon.code.var/schema)
                (keep (fn [dt]
                        (let [sch (:v dt)]
                        (when (and (loose-map-return? sch) (not (has-host-arg? sch)))
                          (let [e (:e dt)
                                vnm (eav1 e :seon.code.var/name)
                                vns (eav1 e :seon.code.var/ns)]
                            (when (and (qualified-symbol? vnm) (symbol? vns)
                                       (str/starts-with? (name vns) "seon.")
                                       (not (str/ends-with? (name vns) "-test"))
                                       (not (contains? '#{seon.main seon.fork} vns))
                                       (not (str/ends-with? (name vnm) "!"))
                                       (and (not (str/ends-with? (name vnm) "-proc")) (not (reexport-target-sym db (eav1 e :seon.code.var/form))))
                                       (not (eav1 e :seon.code.var/private?))
                                       (not (eav1 e :seon.code.var/flow-edge?))
                                       (not (eav1 e :seon.code.var/macro?))
                                       (not (eav1 e :seon.code.var/test?)))
                              (let [slug (str/replace (str (subs (name vns) 5) "-" (name vnm)) #"[^a-zA-Z0-9-]" "-")
                                    tid (keyword "rand" (str "contract-" slug))]
                                {:id tid :category :schema-proof
                                 :run-tests? true :merge? true :require-merge? true :timeout-ms timeout-ms
                                 :verify-spec {:kind :tighten-return-contract :var vnm}
                                 :task {:id tid :scope vns
                                        :title (str "Specify exact return contract for " vnm)
                                        :plan (str "Inspect `" vnm "`, its returned data shape, and its callers in `" vns "`. Tighten its return specification so its actual returned keys are explicit and verified by automated checks.")}})))))))
                (sort-by #(str (:id %))) vec))
         pure-nss
         (when (and (dh :seon.code.var/ns) (dh :seon.code.var/arglists))
           (reduce (fn [acc dt]
             (let [vns (:v dt)]
               (if (or (contains? acc vns)
                       (not (symbol? vns))
                       (not (str/starts-with? (name vns) "seon."))
                       (str/ends-with? (name vns) "-test")
                       (contains? '#{seon.main seon.fork} vns))
                 acc
                 (let [e (:e dt)
                       vnm (eav1 e :seon.code.var/name)]
                   (if (and (qualified-symbol? vnm)
                            (not (str/ends-with? (name vnm) "!"))
                            (not (eav1 e :seon.code.var/private?))
                            (not (eav1 e :seon.code.var/macro?))
                            (not (eav1 e :seon.code.var/test?))
                            (not (eav1 e :seon.code.var/flow-edge?))
                            (seq (eav1 e :seon.code.var/arglists)))
                     (conj acc vns)
                     acc)))))
           #{}
           (d/datoms db :aevt :seon.code.var/ns)))
         ns-tasks
         (when (seq pure-nss)
           (->> pure-nss
                (mapv (fn [nsym]
                        (let [tid (keyword "rand" (str "audit-" (str/replace (name nsym) "." "-")))]
                          {:id tid :category :ns-contract-audit
                           :run-tests? true :merge? true :require-merge? true :timeout-ms timeout-ms
                           :verify-spec {:kind :ns-test-or-example :ns nsym}
                           :task {:id tid :scope nsym
                                  :title (str "Add behavioral unit tests and contract examples in " nsym)
                                  :plan (str "Review the pure public functions in `" nsym "`, pick one lacking direct unit tests or concrete contract examples, add edge-case tests and examples for it, and prove everything green.")}})))
                (sort-by #(str (:id %))) vec))
         schema-tasks
         (when (dh :seon.schema/key)
           (->> (d/datoms db :aevt :seon.schema/key)
                (keep (fn [dt]
                      (let [e (:e dt) sk (:v dt)]
                        (when (and (qualified-keyword? sk) (= "entity" (name sk))
                                   (str/starts-with? (namespace sk) "seon.")
                                   (not (contains? #{:never :internal} (eav1 e :seon.schema/context-policy)))
                                   (< (count (fit/discover-renderers db sk)) 2))
                          (let [tid (keyword "rand" (str "render-" (str/replace (namespace sk) "." "-")))]
                            {:id tid :category :entity-renderers
                             :run-tests? true :merge? true :require-merge? true :timeout-ms timeout-ms
                             :verify-spec {:kind :entity-renderers :schema sk :min-renderers 2}
                             :task {:id tid :scope 'seon.render.entities
                                    :title (str "Add compact and detailed renderers for " sk)
                                    :plan (str "Inspect how `" sk "` is currently rendered across compact and detailed views. Ensure it has clean distinct compact and detailed renderers with unit tests proving both.")}})))))
                (sort-by #(str (:id %))) vec))
         pools (filterv seq [(det-shuffle loose-vars) (det-shuffle ns-tasks) (det-shuffle schema-tasks)])]
     (vec (take n (loop [ps pools acc []]
                    (if (or (>= (count acc) n) (empty? ps))
                      acc
                      (recur (filterv seq (map rest ps)) (into acc (keep first ps))))))))))

(defn ^:seon.flow/edge repl-sample-random-tasks
  "REPL helper (`user/sample-random-tasks`): samples `n` diverse real tasks from live Datahike."
  {:malli/schema [:function [:=> [:cat] [:vector :map]] [:=> [:cat [:maybe :map]] [:vector :map]]], :cost/ticks 20000000, :seon.eval/budget-override {:ticks 200000000, :bytes 536870912}}
  ([] (repl-sample-random-tasks nil))
  ([opts]
   (let [ctx (some-> (requiring-resolve 'seon.fork.calls/*agent-ctx*) deref)
         db (or (:db opts) (when-let [c (:conn ctx)] (or (store/head c :main) @c)))]
     (if db (sample-random-tasks db opts) []))))

(defn ^:seon.flow/edge verify-task-spec
  "Evaluates a serializable `:verify-spec` map against post-merge `:main` `db`, returning `{:ok? bool :reason str}`."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :map]] :map]
   :cost/ticks 25000000}
  [db spec]
  (if-not (and db (map? spec) (:kind spec))
    {:ok? true :kind :none}
    (case (:kind spec)
      :all
      (let [amtx (:after-main-tx spec)
            sub-res (mapv #(verify-task-spec db (cond-> % (and amtx (nil? (:after-main-tx %))) (assoc :after-main-tx amtx)))
                          (:specs spec))
            first-fail (some #(when (false? (:ok? %)) %) sub-res)]
        (if first-fail
          {:ok? false :kind :all :reason (:reason first-fail) :results sub-res}
          {:ok? true :kind :all :results sub-res}))

      :multi-ns-tests
      (verify-task-spec db {:kind :all
                            :after-main-tx (:after-main-tx spec)
                            :specs (mapv (fn [nsym] {:kind :ns-test-or-example :ns nsym}) (:namespaces spec))})

      :tighten-return-contract
      (let [check-one (fn [s]
                        (let [ve (:e (first (d/datoms db :avet :seon.code.var/name s)))
                              sch (when ve (:v (first (d/datoms db :eavt ve :seon.code.var/schema))))
                              still-loose? (or (nil? sch) (loose-map-return? sch))
                              stress (when (and (not still-loose?) (schema/generatable-function-schema? sch))
                                       (try (schema/stress-var db s {:samples 50 :seed 42}) (catch Throwable _ nil)))]
                          (cond
                            (nil? ve) {:ok? false :kind :tighten-return-contract :reason (str "Var not found: " s)}
                            still-loose? {:ok? false :kind :tighten-return-contract :reason (str s " still has loose return schema: " (pr-str sch))}
                            (and stress (false? (:ok? stress))) {:ok? false :kind :tighten-return-contract :reason (str "stress-var failed: " (:reason stress))}
                            :else {:ok? true :kind :tighten-return-contract :var s :schema sch})))
            vsym (:var spec)
            ve0 (:e (first (d/datoms db :avet :seon.code.var/name vsym)))
            fk0 (when ve0 (:v (first (d/datoms db :eavt ve0 :seon.code.var/form))))
            re-target (reexport-target-sym db fk0)
            r0 (check-one vsym)]
        (if (and (false? (:ok? r0)) re-target)
          (let [r1 (check-one re-target)]
            (if (:ok? r1) (assoc r1 :reexport-from vsym) r0))
          r0))

      :ns-test-or-example
      (let [nsym (:ns spec)
            after-tx (long (or (:after-main-tx spec) 0))
            ex-hits (for [dt (d/datoms db :avet :seon.code.var/ns nsym)
                          :let [e (:e dt)
                                sch-dt (first (d/datoms db :eavt e :seon.code.var/schema))]
                          :when (and sch-dt (> (long (:tx sch-dt 0)) after-tx)
                                     (str/includes? (pr-str (:v sch-dt)) ":examples"))]
                      (:v (first (d/datoms db :eavt e :seon.code.var/name))))
            reaching (set (code/reaching-tests db nsym))
            test-hits (for [dt (d/datoms db :aevt :seon.code.var/test?)
                            :when (and (true? (:v dt)) (> (long (:tx dt 0)) after-tx))
                            :let [e (:e dt)
                                  vname (:v (first (d/datoms db :eavt e :seon.code.var/name)))
                                  vns (:v (first (d/datoms db :eavt e :seon.code.var/ns)))
                                  refs (map :v (d/datoms db :eavt e :seon.code.var/refs))]
                            :when (or (= vns (symbol (str (name nsym) "-test")))
                                      (some #(= (name nsym) (namespace %)) refs)
                                      (contains? reaching vname))]
                        vname)]
        (if (or (seq ex-hits) (seq test-hits))
          {:ok? true :kind :ns-test-or-example :ns nsym :examples (vec ex-hits) :tests (vec test-hits)}
          {:ok? false :kind :ns-test-or-example :reason (str "No new unit test or contract examples recorded for " nsym " after tx " after-tx)}))

      :entity-renderers
      (let [sk (:schema spec)
            min-r (long (or (:min-renderers spec) 2))
            rends (fit/discover-renderers db sk)]
        (if (>= (count rends) min-r)
          {:ok? true :kind :entity-renderers :schema sk :count (count rends) :renderers (vec rends)}
          {:ok? false :kind :entity-renderers :reason (str sk " has only " (count rends) " renderer(s), need >= " min-r)}))

      {:ok? true :kind (:kind spec)})))