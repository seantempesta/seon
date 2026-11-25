(ns seon.ctx.graph
  "Pure indexed backward-walk, eval-frontier bridge, contract blame-cut, and chain-boundary witness
   projection helpers (Lever 2). Zero doc levels, zero rigid tier enums, zero truncation caps."
  (:require [datahike.api :as d]
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

(defn proof-backward-rows
  "Walks backwards from an agent's active open task proofs (`:seon.task/{proofs,test-vars,tests}`)
   and annotated `:seon.task/{refs,ref-order}`:
   `proof deftest -> :var/calls (target fns) -> :var/{input,output} (schemas) -> exact doc atoms`.
   Returns `[eid rfn pri tx]` rows for `:rule.ctx/proof-backward-walk`."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db [:maybe :keyword]] [:vector [:tuple :int :any :int :int]]]
   :cost/ticks 15000000}
  [main-db branch-db agent-id]
  (if-not (and agent-id (or main-db branch-db))
    []
    (let [bdb (or branch-db main-db)
          mdb (or main-db branch-db)
          ag (rdata/pull-agent-entity mdb bdb agent-id)
          tids (vec (distinct (concat (rdata/agent-task-ids bdb ag) (rdata/agent-task-ids mdb ag))))
          best (volatile! {})
          add! (fn [eid rfn pri tx]
                 (when (integer? eid)
                   (let [cur (get @best eid)]
                     (when (or (nil? cur) (> (long pri) (long (nth cur 2))))
                       (vswap! best assoc eid [eid rfn (long pri) (long (or tx 0))])))))]
      (doseq [tid tids
              :let [tdb (if (first (avet-eids bdb :seon.task/id tid)) bdb mdb)
                    teid (first (avet-eids tdb :seon.task/id tid))]
              :when (and teid (not (true? (eav1 tdb teid :seon.task/done?))))]
        ;; 1. Explicit task refs in appearance order (docs, vars, schemas, nss)
        (let [ord-eids (resolve-ordered-eids bdb (eav1 tdb teid :seon.task/ref-order))
              all-refs (vec (distinct (concat ord-eids (eav-vals tdb teid :seon.task/refs))))]
          (doseq [[idx reid] (map-indexed vector all-refs)]
            (add! reid :seon.rule/nil (max 74 (- 91 (min 15 idx))) (:max-tx bdb))))
        ;; 2. Walk backwards from proof deftest vars
        (let [proof-eids (task-proof-var-eids bdb teid)]
          (doseq [pe proof-eids]
            (add! pe :seon.rule/nil 93 (:max-tx bdb))
            (let [called (eav-vals bdb pe :seon.code.var/calls)]
              (doseq [ve called]
                (add! ve :seon.rule/nil 92 (:max-tx bdb))
                (doseq [de (doc-atoms-for-eid bdb ve)]
                  (add! de :seon.rule/nil 86 (:max-tx bdb)))
                (doseq [se (concat (eav-vals bdb ve :seon.code.var/input)
                                   (eav-vals bdb ve :seon.code.var/output))]
                  (add! se :seon.rule/nil 89 (:max-tx bdb))
                  (doseq [de (doc-atoms-for-eid bdb se)]
                    (add! de :seon.rule/nil 84 (:max-tx bdb)))))))))
      (vec (sort-by (fn [[e _ pri _]] [(- (long pri)) (long e)]) (vals @best))))))

(defn eval-frontier-rows
  "Connects an agent's live eval frontier (`:seon.agent.eval/{called-vars,result-schemas}`) to the next
   unseen schemas, bridge functions, and exact doc atoms along the graph toward the task's proofs.
   Returns `[eid rfn pri tx]` rows for `:rule.ctx/eval-frontier-bridge`."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db [:maybe :keyword]] [:vector [:tuple :int :any :int :int]]]
   :cost/ticks 15000000}
  [main-db branch-db agent-id]
  (if-not (and agent-id branch-db (dh-has? branch-db :seon.agent.eval/agent))
    []
    (let [ev-dts (d/datoms branch-db :avet :seon.agent.eval/agent agent-id)
          recent-eids (->> ev-dts (sort-by #(- (long (:tx % 0)))) (take 6) (mapv :e))
          called-eids (vec (distinct (mapcat #(eav-vals branch-db % :seon.agent.eval/called-vars) recent-eids)))
          res-sch-eids (vec (distinct (mapcat #(eav-vals branch-db % :seon.agent.eval/result-schemas) recent-eids)))
          ;; Target proof-graph eids to identify bridge functions cleanly
          goal-eids (into #{} (map first) (proof-backward-rows main-db branch-db agent-id))
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
        ;; Bridge functions that consume this returned schema AND sit in the task's backward proof graph!
        (doseq [consumer (avet-eids branch-db :seon.code.var/input se)
                :when (contains? goal-eids consumer)]
          (add! consumer 90)))
      (vec (sort-by (fn [[e _ pri _]] [(- (long pri)) (long e)]) (vals @best))))))

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
   entry schema `A0` -> exit schema `Ak`, 1 concrete witness (preferring `:examples` on `f1`, else `seed=42` sample),
   unseen ordered doc atoms, and Known-Set back-refs `(see Turn k / result/e<n>)`."
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
       {:steps (vec var-syms)
        :input-schema in-sch
        :output-schema out-sch
        :sample-in sample-in
        :sample-out sample-out
        :known-refs known-refs
        :doc-atoms unseen-docs}
       {:seon/schema :seon.graph/chain-boundary}))))