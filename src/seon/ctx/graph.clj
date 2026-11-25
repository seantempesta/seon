(ns seon.ctx.graph
  "Pure indexed backward-walk, eval-frontier bridge, contract blame-cut, and chain-boundary witness
   projection helpers (Lever 2). Zero doc levels, zero rigid tier enums, zero truncation caps."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.render.data :as rdata]
            [seon.schema :as schema]))

(defn- dh-has? [db a] (boolean (and db (contains? (:schema db) a))))

(defn- eav-vals [db e a]
  (when (and (integer? e) (dh-has? db a))
    (mapv :v (d/datoms db :eavt e a))))

(defn- eav1 [db e a]
  (when (and (integer? e) (dh-has? db a))
    (:v (first (d/datoms db :eavt e a)))))

(defn- avet-eids [db a v]
  (when (and (some? v) (dh-has? db a))
    (mapv :e (d/datoms db :avet a v))))

(defn- doc-atoms-for-eid [db eid]
  (let [fwd-var (eav-vals db eid :seon.code.var/doc-refs)
        fwd-sch (eav-vals db eid :seon.schema/doc-refs)
        rev-doc (avet-eids db :seon.code.doc/references eid)]
    (vec (distinct (filter #(and (integer? %) (some? (eav1 db % :seon.code.doc/id)))
                           (concat fwd-var fwd-sch rev-doc))))))

(defn- task-proof-var-eids [db teid]
  (let [direct (concat (eav-vals db teid :seon.task/proofs)
                       (eav-vals db teid :seon.task/test-vars))
        by-sym (when (dh-has? db :seon.code.var/name)
                 (keep #(first (avet-eids db :seon.code.var/name %))
                       (eav-vals db teid :seon.task/tests)))]
    (vec (distinct (filter integer? (concat direct by-sym))))))

(defn- resolve-ordered-eids [db lrefs]
  (vec (distinct
        (keep (fn [lr]
                (when (and (vector? lr) (= 2 (count lr)) (qualified-keyword? (first lr)))
                  (first (avet-eids db (first lr) (second lr)))))
              lrefs))))

(def ^:private test-harness-nss
  #{'seon.test-fixture})

(defn- var-in-scope? [scope var-ns]
  (and (symbol? scope) (symbol? var-ns)
       (let [ss (name scope) vs (name var-ns)]
         (or (= ss vs) (str/starts-with? vs (str ss "."))))))

(defn- fn-schema-io [fn-sch]
  (when (vector? fn-sch)
    (let [arrow (cond
                  (= :=> (first fn-sch)) fn-sch
                  (= :function (first fn-sch)) (first (filter #(and (vector? %) (= :=> (first %))) (rest fn-sch)))
                  :else nil)]
      (when arrow
        (let [tail (if (map? (second arrow)) (subvec arrow 2) (subvec arrow 1))
              cat-f (first tail)
              ret-f (second tail)
              in-f (if (and (vector? cat-f) (= :cat (first cat-f)) (= 2 (count cat-f)))
                     (second cat-f)
                     cat-f)]
          {:input-type in-f :output-type ret-f})))))

(defn proof-backward-rows
  "Walks backwards from an agent's active open task proofs (`:seon.task/{proofs,test-vars,tests}`),
   reverse `:seon.task/needs` parent tasks, and annotated `:seon.task/{refs,ref-order}`:
   `proof deftest -> :var/calls (in-scope target fns prioritized, test-harness skipped) -> :var/{input,output} -> exact doc atoms`.
   Returns `[eid rfn pri tx]` rows for `:rule.ctx/proof-backward-walk`."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db [:maybe :keyword]] [:vector [:tuple :int :any :int :int]]]
   :cost/ticks 15000000}
  [main-db branch-db agent-id]
  (if-not (and agent-id (or main-db branch-db))
    []
    (let [bdb (or branch-db main-db)
          mdb (or main-db branch-db)
          ag (rdata/pull-agent-entity mdb bdb agent-id)
          tids (vec (distinct (concat (rdata/agent-task-ids bdb ag) (rdata/agent-task-ids mdb ag))))]
      (if (empty? tids)
        []
        (let [best (volatile! {})
              add! (fn [eid rfn pri tx]
                     (when (integer? eid)
                       (let [cur (get @best eid)]
                         (when (or (nil? cur) (> (long pri) (long (nth cur 2))))
                           (vswap! best assoc eid [eid rfn (long pri) (long (or tx 0))])))))]
          (doseq [tid tids
                  :let [tdb (if (first (avet-eids bdb :seon.task/id tid)) bdb mdb)
                        teid (first (avet-eids tdb :seon.task/id tid))]
                  :when (and teid (not (true? (eav1 tdb teid :seon.task/done?))))]
            ;; 0. Reverse :seon.task/needs parent tasks (so Refiner/subtask agents always see their parent task!)
            (doseq [pe (concat (avet-eids bdb :seon.task/needs tid)
                               (when (not= bdb mdb)
                                 (keep #(when-let [ptid (eav1 mdb % :seon.task/id)]
                                          (or (first (avet-eids bdb :seon.task/id ptid)) %))
                                       (avet-eids mdb :seon.task/needs tid))))]
              (add! pe :seon.rule/nil 94 (:max-tx bdb)))
            ;; 1. Explicit task refs in appearance order (docs, vars, schemas, nss)
            (let [ord-eids (resolve-ordered-eids bdb (eav1 tdb teid :seon.task/ref-order))
                  all-refs (vec (distinct (concat ord-eids (eav-vals tdb teid :seon.task/refs))))
                  ref-set (set all-refs)
                  tscope (or (eav1 tdb teid :seon.task/scope) (:seon.agent/scope ag))]
              (doseq [[idx reid] (map-indexed vector all-refs)]
                (add! reid :seon.rule/nil (max 74 (- 91 (min 15 idx))) (:max-tx bdb)))
              ;; 2. Walk backwards from proof deftest vars (prioritizing in-scope & cited vars, skipping test-harness boilerplate)
              (let [proof-eids (task-proof-var-eids bdb teid)]
                (doseq [pe proof-eids]
                  (add! pe :seon.rule/nil 93 (:max-tx bdb))
                  (let [called (eav-vals bdb pe :seon.code.var/calls)]
                    (doseq [ve called
                            :let [vns (eav1 bdb ve :seon.code.var/ns)]
                            :when (not (contains? test-harness-nss vns))]
                      (let [focal? (or (nil? tscope) (= 'seon tscope)
                                       (contains? ref-set ve)
                                       (var-in-scope? tscope vns))
                            v-pri (if focal? 92 80)
                            s-pri (if focal? 89 79)
                            d-pri (if focal? 86 78)]
                        (add! ve :seon.rule/nil v-pri (:max-tx bdb))
                        (doseq [de (doc-atoms-for-eid bdb ve)]
                          (add! de :seon.rule/nil d-pri (:max-tx bdb)))
                        (doseq [se (concat (eav-vals bdb ve :seon.code.var/input)
                                           (eav-vals bdb ve :seon.code.var/output))]
                          (add! se :seon.rule/nil s-pri (:max-tx bdb))
                          (doseq [de (doc-atoms-for-eid bdb se)]
                            (add! de :seon.rule/nil (dec d-pri) (:max-tx bdb)))))))))))
          (vec (sort-by (fn [[e _ pri _]] [(- (long pri)) (long e)]) (vals @best))))))))

(defn eval-frontier-rows
  "Connects an agent's live eval frontier (`:seon.agent.eval/{called-vars,result-schemas}`) to the next
   unseen schemas, bridge functions, and exact doc atoms along the graph toward the task's proofs.
   Returns `[eid rfn pri tx]` rows for `:rule.ctx/eval-frontier-bridge`."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db [:maybe :keyword]] [:vector [:tuple :int :any :int :int]]]
   :cost/ticks 15000000}
  [main-db branch-db agent-id]
  (if-not (and agent-id branch-db (dh-has? branch-db :seon.agent.eval/agent))
    []
    (let [ev-dts (d/datoms branch-db :avet :seon.agent.eval/agent agent-id)]
      (if (empty? ev-dts)
        []
        (let [recent-eids (->> ev-dts (sort-by #(- (long (:tx % 0)))) (take 6) (mapv :e))
              called-eids (vec (distinct (mapcat #(eav-vals branch-db % :seon.agent.eval/called-vars) recent-eids)))
              res-sch-eids (vec (distinct (mapcat #(eav-vals branch-db % :seon.agent.eval/result-schemas) recent-eids)))]
          (if (and (empty? called-eids) (empty? res-sch-eids))
            []
            (let [goal-eids (if (seq res-sch-eids)
                              (into #{} (map first) (proof-backward-rows main-db branch-db agent-id))
                              #{})
                  best (volatile! {})
                  add! (fn [eid pri]
                         (when (integer? eid)
                           (let [cur (get @best eid)]
                             (when (or (nil? cur) (> (long pri) (long (nth cur 2))))
                               (vswap! best assoc eid [eid :seon.rule/nil (long pri) (long (or (:max-tx branch-db) 0))])))))]
              (doseq [ve called-eids]
                (doseq [de (doc-atoms-for-eid branch-db ve)] (add! de 87))
                (doseq [se (concat (eav-vals branch-db ve :seon.code.var/input)
                                   (eav-vals branch-db ve :seon.code.var/output))]
                  (add! se 86)))
              (doseq [se res-sch-eids]
                (add! se 88)
                (doseq [de (doc-atoms-for-eid branch-db se)] (add! de 85))
                (doseq [consumer (avet-eids branch-db :seon.code.var/input se)
                        :when (contains? goal-eids consumer)]
                  (add! consumer 90)))
              (vec (sort-by (fn [[e _ pri _]] [(- (long pri)) (long e)]) (vals @best))))))))))

(defn error-blame-rows
  "Contract blame localization for open `:seon.error/*` entities with `:seon.error/blame` linked to `agent-id`:
   scans `:aevt :seon.error/blame` first so non-blame error commits skip in O(1).
   - `:input-contract` -> violated `:schema-ref` + its exact doc atoms (skips callee var body!).
   - `:output-contract` / `:exception` / `:test-assertion` -> cut-point `:var-ref` + `:schema-ref` + var doc atoms."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db [:maybe :keyword]] [:vector [:tuple :int :any :int :int]]]
   :cost/ticks 10000000}
  [main-db branch-db agent-id]
  (if-not (and agent-id (or main-db branch-db))
    []
    (let [bdb (or branch-db main-db)
          mdb (or main-db branch-db)
          best (volatile! {})
          add! (fn [eid rfn pri tx]
                 (when (integer? eid)
                   (let [cur (get @best eid)]
                     (when (or (nil? cur) (> (long pri) (long (nth cur 2))))
                       (vswap! best assoc eid [eid rfn (long pri) (long (or tx 0))])))))
          scan-db
          (fn [db]
            (when (and db (dh-has? db :seon.error/blame))
              (doseq [dt (d/datoms db :aevt :seon.error/blame)
                      :let [ee (:e dt)
                            blame (:v dt)]
                      :when (and blame
                                 (= :open (eav1 db ee :seon.error/status))
                                 (= agent-id (eav1 db ee :seon.error/agent)))]
                (let [tx (long (:tx dt 0))
                      ve (eav1 db ee :seon.error/var-ref)
                      se (eav1 db ee :seon.error/schema-ref)]
                  (add! ee 'seon.render.entities.graph/render-blame-cut 98 tx)
                  (if (= :input-contract blame)
                    (when se
                      (add! se :seon.rule/nil 96 tx)
                      (doseq [de (doc-atoms-for-eid bdb se)] (add! de :seon.rule/nil 92 tx)))
                    (do
                      (when ve
                        (add! ve :seon.rule/nil 97 tx)
                        (doseq [de (doc-atoms-for-eid bdb ve)] (add! de :seon.rule/nil 92 tx)))
                      (when se (add! se :seon.rule/nil 93 tx))))))))]
      (scan-db mdb)
      (when (not= bdb mdb) (scan-db bdb))
      (vec (sort-by (fn [[e _ pri _]] [(- (long pri)) (long e)]) (vals @best))))))

(defn build-chain-boundary
  "Collapses an ordered function chain `var-syms` `[f1 ... fk]` into a `:seon.graph/chain-boundary` value map:
   entry schema `A0` -> exit schema `Ak` (with Malli primitive `:input-type`/`:output-type` fallback),
   1 concrete witness (preferring `:examples` on `f1`, else `seed=42` sample), unseen ordered doc atoms,
   and Known-Set back-refs `(see Turn k / result/e<n>)`."
  {:malli/schema [:=> [:cat :seon.host/db [:vector :qualified-symbol] [:? [:maybe :map]]] :seon.graph/chain-boundary]
   :cost/ticks 15000000}
  ([db var-syms] (build-chain-boundary db var-syms nil))
  ([db var-syms known-set]
   (let [ks (if (map? known-set) known-set {})
         eids (mapv #(first (avet-eids db :seon.code.var/name %)) var-syms)
         first-e (first eids)
         last-e (last eids)
         in-sch (when first-e
                  (when-let [se (first (eav-vals db first-e :seon.code.var/input))]
                    (eav1 db se :seon.schema/key)))
         out-sch (when last-e
                   (when-let [se (first (eav-vals db last-e :seon.code.var/output))]
                     (eav1 db se :seon.schema/key)))
         first-fn-sch (when first-e (eav1 db first-e :seon.code.var/schema))
         last-fn-sch (when last-e (eav1 db last-e :seon.code.var/schema))
         in-type (when (nil? in-sch) (:input-type (fn-schema-io first-fn-sch)))
         out-type (when (nil? out-sch) (:output-type (fn-schema-io last-fn-sch)))
         explicit-ex (when (and (vector? first-fn-sch) (= :=> (first first-fn-sch)) (map? (second first-fn-sch)))
                       (first (:examples (second first-fn-sch))))
         sample-in (cond
                     (some? (first explicit-ex)) (pr-str (first explicit-ex))
                     in-sch (when-let [s (first (schema/sample-schema db in-sch 1))] (pr-str s))
                     :else nil)
         sample-out (cond
                      (some? (second explicit-ex)) (pr-str (second explicit-ex))
                      out-sch (when-let [s (first (schema/sample-schema db out-sch 1))] (pr-str s))
                      :else nil)
         known-refs (vec (keep (fn [[sym eid]]
                                 (when-let [entry (when eid (get ks eid))]
                                   {:label (str sym) :turn (:turn entry) :handle (:handle entry)}))
                               (map vector var-syms eids)))
         doc-eids (vec (distinct (mapcat #(when % (doc-atoms-for-eid db %)) eids)))
         unseen-docs (vec (keep (fn [de]
                                  (when-not (contains? ks de)
                                    {:id (eav1 db de :seon.code.doc/id)
                                     :heading (eav1 db de :seon.code.doc/heading)}))
                                doc-eids))]
     (with-meta
       (cond-> {:steps (vec var-syms)
                :input-schema in-sch
                :output-schema out-sch
                :sample-in sample-in
                :sample-out sample-out
                :known-refs known-refs
                :doc-atoms unseen-docs}
         (some? in-type) (assoc :input-type in-type)
         (some? out-type) (assoc :output-type out-type))
       {:seon/schema :seon.graph/chain-boundary}))))
