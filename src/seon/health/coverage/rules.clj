(ns seon.health.coverage.rules
  "Closed-world Datalog rule static audit (`check-rule-bindings-and-refs`) and
   `:ephemeral-tail` 2,000-token eviction simulator (`audit-tail-eviction`)."
  (:require [clojure.set :as set]
            [datahike.api :as d]
            [seon.health.coverage :as cov]
            [seon.render.fit :as fit]
            [seon.render.tails :as rtails]
            [seon.rule :as rules]
            [seon.rule.seed :as rseed]))

(def supported-query-inputs
  "Exact `:in` input symbols resolved by `seon.rule/resolve-query-inputs`."
  #{(quote $) (quote $main) (quote $branch) (quote $code) (quote $task)
    (quote $wm) (quote ?agent) (quote ?agent-eid) (quote ?hierarchy) (quote %)})

(defn- doc-id-literal [cl]
  (when (and (vector? cl) (>= (count cl) 4) (= :seon.code.doc/id (nth cl 2)) (string? (nth cl 3)))
    (nth cl 3)))

(defn- extract-doc-ref-checks [where-clauses]
  (let [out (volatile! [])]
    (letfn [(walk [node]
              (cond
                (doc-id-literal node)
                (vswap! out conj {:kind :required :ids [(doc-id-literal node)]})

                (and (seq? node) (#{(quote or) (quote or-join)} (first node)))
                (let [branches (if (= (quote or-join) (first node)) (drop 2 node) (rest node))
                      branch-doc-ids (mapv (fn [b]
                                             (vec (keep doc-id-literal (tree-seq coll? seq b))))
                                           branches)]
                  (if (every? seq branch-doc-ids)
                    (vswap! out conj {:kind :any-of :ids (vec (distinct (apply concat branch-doc-ids)))})
                    (doseq [b branches] (walk b))))

                (and (seq? node) (#{(quote and) (quote not) (quote not-join)} (first node)))
                (doseq [sub (if (= (quote not-join) (first node)) (drop 2 node) (rest node))]
                  (walk sub))

                :else nil))]
      (doseq [cl where-clauses] (walk cl)))
    @out))

(defn- extract-where-fn-syms [where-clauses]
  (into #{}
        (keep (fn [n]
                (when (and (seq? n) (qualified-symbol? (first n)))
                  (first n))))
        (tree-seq coll? seq where-clauses)))

(defn- extract-wm-writes [rule]
  (into #{}
        (keep (fn [a]
                (when (and (vector? a) (= 3 (count a)) (keyword? (second a)))
                  (second a))))
        (:seon.rule/assert rule)))

(defn- extract-wm-reads [where-clauses ins]
  (if-not (contains? (set ins) (quote $wm))
    #{}
    (reduce (fn [acc n]
              (cond
                (and (vector? n) (>= (count n) 3) (= (quote $wm) (first n)))
                (conj acc (if (keyword? (nth n 2)) (nth n 2) :*))
                (and (seq? n) (some #(= (quote $wm) %) n))
                (let [ks (filter keyword? n)] (if (seq ks) (into acc ks) (conj acc :*)))
                :else acc))
            #{} (tree-seq coll? seq where-clauses))))

(defn- rule-fn-exists? [live-vars sym]
  (or (contains? live-vars sym)
      (boolean (when-let [ns-obj (find-ns (symbol (namespace sym)))]
                 (ns-resolve ns-obj (symbol (name sym)))))))

(defn check-rule-bindings-and-refs
  "Validates all enabled `:seon.rule/*` entities against `seon.rule/resolve-query-inputs`,
   existing `:seon.code.doc/id` entities in `db`, callable `:where` helper fns, and `$wm` producer/consumer closure."
  {:malli/schema [:function
                  [:=> [:cat [:maybe :seon.host/db]] :map]
                  [:=> [:cat [:maybe :seon.host/db] [:maybe [:sequential :map]]] :map]]
   :cost/ticks 40000000 :cost/bytes 33554432}
  ([db] (check-rule-bindings-and-refs db nil))
  ([db explicit-rules]
   (let [all-rs (vec (or explicit-rules (rules/load-rules db (rseed/starter-rules))))
         enabled (filterv #(not (false? (:seon.rule/enabled? % true))) all-rs)
         dh (or (:schema db) {})
         doc-ids (if (and db (contains? dh :seon.code.doc/id))
                   (into #{} (map :v) (d/datoms db :aevt :seon.code.doc/id))
                   nil)
         live-vars (if (and db (contains? dh :seon.code.var/name))
                     (into #{} (map :v) (d/datoms db :aevt :seon.code.var/name))
                     #{})
         wm-producers (reduce (fn [m r]
                                (reduce #(update %1 %2 (fnil conj #{}) (:seon.rule/id r))
                                        m (extract-wm-writes r)))
                              {} enabled)
         allowed-wm (set/union cov/known-runtime-wm-seeders (set (keys wm-producers)))
         wm-consumers (volatile! {})
         probs (volatile! [])
         add! (fn [cls rid subj msg]
                (vswap! probs conj {:class cls :severity :error :rule rid :subject subj :message msg}))]
     (doseq [r enabled
             :let [rid (:seon.rule/id r)
                   pq (rules/parse-query (:seon.rule/query r))
                   ins (vec (or (seq (:in pq)) [(quote $)]))
                   ws (or (:where pq) [])
                   wreads (extract-wm-reads ws ins)]]
       ;; 1. Validate :in bindings against resolve-query-inputs
       (doseq [sym ins
               :when (not (contains? supported-query-inputs sym))]
         (add! :unknown-rule-input rid (str rid " :in " sym)
               (str "Rule " rid " binds unsupported :in symbol " sym " (not in seon.rule/resolve-query-inputs).")))
       ;; 2. Validate :seon.code.doc/id string literals exist in DB
       (when doc-ids
         (doseq [{:keys [kind ids]} (extract-doc-ref-checks ws)]
           (if (= kind :any-of)
             (when-not (some doc-ids ids)
               (add! :missing-rule-doc-ref rid (str rid "->" (first ids))
                     (str "Rule " rid " references none of existing :seon.code.doc/id targets " (pr-str ids) ".")))
             (doseq [did ids :when (not (contains? doc-ids did))]
               (add! :missing-rule-doc-ref rid (str rid "->" did)
                     (str "Rule " rid " references missing :seon.code.doc/id " (pr-str did) "."))))))
       ;; 3. Validate $wm consumer closure
       (doseq [a wreads :when (and (keyword? a) (not= :* a))]
         (vswap! wm-consumers update a (fnil conj #{}) rid)
         (when-not (contains? allowed-wm a)
           (add! :orphan-wm-consumer rid (str rid "<-" a)
                 (str "Rule " rid " reads unproduced $wm attribute " a "."))))
       ;; 4. Validate qualified helper fns called in :where
       (when (seq live-vars)
         (doseq [fsym (extract-where-fn-syms ws)
                 :when (not (rule-fn-exists? live-vars fsym))]
           (add! :missing-rule-fn rid (str rid "->" fsym)
                 (str "Rule " rid " calls missing helper fn " fsym " in :where.")))))
     (let [consumers @wm-consumers
           ps (vec (sort-by (juxt #(name (:class %)) :subject) @probs))
           unref (vec (sort (set/difference (set (keys wm-producers)) (set (keys consumers)))))]
       {:ok? (empty? ps)
        :rules-checked (count enabled)
        :counts (frequencies (map :class ps))
        :problems ps
        :wm-producers wm-producers
        :wm-consumers consumers
        :unreferenced-wm-producers unref}))))

(defn- mk-tail-cand [id schema pri boost data]
  (cond-> {:entity id :schema schema :zone :ephemeral-tail :priority pri :source :rule :data data}
    boost (assoc :rank/boost boost :params {:rank/boost boost})))

(defn- sample-tail-scenarios [db]
  (let [doc-pull (fn [did]
                   (or (when (and db (contains? (:schema db) :seon.code.doc/id)
                                  (first (d/datoms db :avet :seon.code.doc/id did)))
                         (dissoc (d/pull db (quote [:seon.code.doc/id :seon.code.doc/path :seon.code.doc/heading :seon.code.doc/body])
                                         [:seon.code.doc/id did]) :db/id))
                       {:seon.code.doc/id did :seon.code.doc/path "docs/primers.md"
                        :seon.code.doc/heading "## Primer" :seon.code.doc/body (apply str (repeat 40 "Keep edits small and verified. "))}))
        eval1 {:seon.agent.eval/handle (quote result/e10) :seon.agent.eval/turn 10
               :seon.agent.eval/code "(user/health)" :seon.agent.eval/value "{:ok? true}" :seon.agent.eval/ms 12.0}
        eval2 {:seon.agent.eval/handle (quote result/e9) :seon.agent.eval/turn 9
               :seon.agent.eval/code "(user/edit-fn! 'seon.web/invoke-call! ...)" :seon.agent.eval/value "{:ok? true}" :seon.agent.eval/ms 25.0}
        eval3 {:seon.agent.eval/handle (quote result/e8) :seon.agent.eval/turn 8
               :seon.agent.eval/code "(user/run-tests! 'seon.error-test)" :seon.agent.eval/value "{:ok? false :failed 1}" :seon.agent.eval/ms 140.0}
        err-ent {:seon.error/id (random-uuid) :seon.error/kind :model/api-error :seon.error/severity :error
                 :seon.error/source :model :seon.error/status :open :seon.error/message "HTTP 400 context overflow"}
        fix-ent {:seon.fix/id :fix/context-overflow :seon.fix/error-class :model/api-error
                 :seon.fix/symptom "Repeated 400 retry" :seon.fix/root-cause "Missing non-retryable 400 check"
                 :seon.fix/change-summary "Halt on 400 in 1 turn"}
        test-ent {:seon.test/digest "dg-fail-1" :seon.test/var (quote seon.reconciler-test/status-derivation-test)
                  :seon.test/kind :deftest :seon.test/pass? false :seon.test/ms 18.0 :seon.test/report "expected :waiting got :runnable"}
        var-ent (fn [i] {:seon.code.var/name (symbol "seon.reconciler" (str "helper-" i))
                         :seon.code.var/ns (quote seon.reconciler)
                         :seon.code.var/doc "Pure reconciler policy helper."
                         :seon.code.var/arglists (quote ([state]))})]
    {:quiet [(mk-tail-cand :tail/eval-10 :seon.agent.eval/entity 90 nil eval1)
             (mk-tail-cand :tail/doc-proofs :seon.code.doc/entity 89 nil (doc-pull "docs/primers.md#proofs"))]
     :busy [(mk-tail-cand :tail/open-err :seon.error/entity 99 0.25 err-ent)
            (mk-tail-cand :tail/fix :seon.fix/entity 98 nil fix-ent)
            (mk-tail-cand :tail/fail-test :seon.test/entity 97 0.20 test-ent)
            (mk-tail-cand :tail/branch-health :seon.code.var/entity 96 0.25 (var-ent 0))
            (mk-tail-cand :tail/doc-history :seon.code.doc/entity 95 nil (doc-pull "docs/primers.md#history-and-time-travel"))
            (mk-tail-cand :tail/doc-contracts :seon.code.doc/entity 92 nil (doc-pull "docs/primers.md#contracts"))
            (mk-tail-cand :tail/eval-10 :seon.agent.eval/entity 90 nil eval1)
            (mk-tail-cand :tail/eval-9 :seon.agent.eval/entity 90 nil eval2)
            (mk-tail-cand :tail/eval-8 :seon.agent.eval/entity 90 nil eval3)
            (mk-tail-cand :tail/frontier-1 :seon.code.var/entity 76 nil (var-ent 1))
            (mk-tail-cand :tail/frontier-2 :seon.code.var/entity 76 nil (var-ent 2))
            (mk-tail-cand :tail/frontier-3 :seon.code.var/entity 76 nil (var-ent 3))]}))

(defn audit-tail-eviction
  "Simulates `:ephemeral-tail` Upgrade-Ladder fitting under `:quiet` and `:busy` turns against the
   2,000-token tail budget (`seon.render.fit/fit-projections`), ensuring critical signals (`priority >= 96`)
   survive while lower-priority items degrade/evict cleanly."
  {:malli/schema [:function
                  [:=> [:cat [:maybe :seon.host/db]] :map]
                  [:=> [:cat [:maybe :seon.host/db] [:maybe :map]] :map]]
   :cost/ticks 60000000 :cost/bytes 67108864}
  ([db] (audit-tail-eviction db nil))
  ([db opts]
   (let [t0 (System/nanoTime)
         budget (long (or (:tail-token-budget opts) 2000))
         scenarios (or (:scenarios opts) (sample-tail-scenarios db))
         fit-opts {:target :wire :strategy :depth-first :token-budget budget
                   :main-db db :fallback-renderers rtails/fallback-entity-renderers}
         run-one (fn [items]
                   (let [pri-by-e (into {} (map (juxt :entity :priority)) items)
                         res (fit/fit-projections db items fit-opts)
                         sel (:selected res)
                         drp (:dropped res)
                         hi-dropped (filterv #(>= (long (or (get pri-by-e (:entity %)) 0)) 96) drp)]
                     {:candidates (count items)
                      :selected (count sel)
                      :dropped (count drp)
                      :tokens (long (:tokens res 0))
                      :budget budget
                      :within-budget? (<= (long (:tokens res 0)) budget)
                      :selected-ids (mapv :entity sel)
                      :dropped-ids (mapv :entity drp)
                      :high-pri-dropped (mapv :entity hi-dropped)}))
         res-map (into {} (map (fn [[k items]] [k (run-one items)])) scenarios)
         ms (/ (Math/round (/ (double (- (System/nanoTime) t0)) 1e4)) 100.0)
         ok? (every? (fn [[_ r]] (and (:within-budget? r) (empty? (:high-pri-dropped r)))) res-map)]
     {:ok? ok?
      :tail-budget budget
      :ms ms
      :scenarios res-map})))