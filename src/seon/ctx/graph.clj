(ns seon.ctx.graph
  "Pure indexed backward-walk, eval-frontier bridge, contract blame-cut, and chain-boundary witness
   projection helpers (Lever 2). Zero doc levels, zero rigid tier enums, zero truncation caps."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.render.data :as rdata]
            [seon.schema :as schema]
            [seon.rule :as rules]
            [seon.ctx.recall :as recall]))

(defn- dh-has? [& args] (apply recall/dh-has? args))

(defn- eav-vals [& args] (apply recall/eav-vals args))

(defn- eav1 [& args] (apply recall/eav1 args))

(defn- avet-eids [& args] (apply recall/avet-eids args))

(defn- doc-atoms-for-eid [& args] (apply recall/doc-atoms-for-eid args))

(defn- task-proof-var-eids
([db teid] (task-proof-var-eids db db teid))
([tdb bdb teid]
 (let [same? (identical? tdb bdb)
       direct (keep (fn [ve]
                      (if same? ve
                        (or (when-let [vn (eav1 tdb ve :seon.code.var/name)]
                              (first (avet-eids bdb :seon.code.var/name vn)))
                            ve)))
                    (eav-vals tdb teid :seon.task/test-vars))
       by-sym (when (dh-has? bdb :seon.code.var/name)
                (keep #(first (avet-eids bdb :seon.code.var/name %))
                      (eav-vals tdb teid :seon.task/tests)))]
   (vec (distinct (filter integer? (concat direct by-sym)))))))

(defn- resolve-ordered-eids [db lrefs]
  (vec (distinct
        (keep (fn [lr]
                (when (and (vector? lr) (= 2 (count lr)) (qualified-keyword? (first lr)))
                  (first (avet-eids db (first lr) (second lr)))))
              lrefs))))

(def ^:private test-harness-nss recall/test-harness-nss)

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

(defn- add-proof-targets! [bdb add! tscope ref-set pe]
  (add! pe :seon.rule/nil 93 (:max-tx bdb))
  (doseq [ve (eav-vals bdb pe :seon.code.var/calls)
          :let [vns (eav1 bdb ve :seon.code.var/ns)]
          :when (not (contains? test-harness-nss vns))]
    (let [focal? (or (nil? tscope) (= 'seon tscope) (contains? ref-set ve) (var-in-scope? tscope vns))
          v-pri (if focal? 92 80) s-pri (if focal? 89 79) d-pri (if focal? 86 78)]
      (add! ve :seon.rule/nil v-pri (:max-tx bdb))
      (doseq [de (doc-atoms-for-eid bdb ve)] (add! de :seon.rule/nil d-pri (:max-tx bdb)))
      (doseq [se (concat (eav-vals bdb ve :seon.code.var/input) (eav-vals bdb ve :seon.code.var/output))]
        (add! se :seon.rule/nil s-pri (:max-tx bdb))
        (doseq [de (doc-atoms-for-eid bdb se)] (add! de :seon.rule/nil (dec d-pri) (:max-tx bdb))))
      (when (and focal? (symbol? vns) (not= 'seon vns) (empty? (eav-vals bdb ve :seon.code.var/calls)))
        (doseq [sv (avet-eids bdb :seon.code.var/ns vns)
                :when (and (not= sv ve) (not (true? (eav1 bdb sv :seon.code.var/private?))))]
          (add! sv :seon.rule/nil 83 (:max-tx bdb))
          (doseq [se (concat (eav-vals bdb sv :seon.code.var/input) (eav-vals bdb sv :seon.code.var/output))]
            (add! se :seon.rule/nil 81 (:max-tx bdb))))))))

(defn proof-backward-rows
  "Walks backwards from an agent's active open task proofs (`:seon.task/{proofs,test-vars,tests}`),
   reverse `:seon.task/needs` parent tasks, and annotated `:seon.task/{refs,ref-order}`:
   `proof deftest -> :var/calls -> :var/{input,output} -> exact doc atoms` (plus sibling public vars for out-degree-0 stubs)."
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
            (doseq [pe (concat (avet-eids bdb :seon.task/needs tid)
                               (when (not= bdb mdb)
                                 (keep #(when-let [ptid (eav1 mdb % :seon.task/id)]
                                          (or (first (avet-eids bdb :seon.task/id ptid)) %))
                                       (avet-eids mdb :seon.task/needs tid))))]
              (add! pe :seon.rule/nil 94 (:max-tx bdb)))
            (let [ord-eids (resolve-ordered-eids bdb (eav1 tdb teid :seon.task/ref-order))
                  all-refs (vec (distinct (concat ord-eids (eav-vals tdb teid :seon.task/refs))))
                  ref-set (set all-refs)
                  tscope (or (eav1 tdb teid :seon.task/scope) (:seon.agent/scope ag))
                  proof-eids (task-proof-var-eids tdb bdb teid)]
              (doseq [[idx reid] (map-indexed vector all-refs)]
                (add! reid :seon.rule/nil (max 74 (- 91 (min 15 idx))) (:max-tx bdb)))
              (when (and (= :template/refine-task-proofs (eav1 tdb teid :seon.task/template)) (empty? proof-eids) (symbol? tscope) (not= 'seon tscope))
                (when-let [nse (first (avet-eids bdb :seon.code.ns/name tscope))] (add! nse :seon.rule/nil 91 (:max-tx bdb)))
                (when-let [tnse (first (avet-eids bdb :seon.code.ns/name (symbol (str tscope "-test"))))] (add! tnse :seon.rule/nil 87 (:max-tx bdb)))
                (doseq [ve (avet-eids bdb :seon.code.var/ns tscope) :when (not (true? (eav1 bdb ve :seon.code.var/private?)))]
                  (add! ve :seon.rule/nil 88 (:max-tx bdb))
                  (doseq [de (doc-atoms-for-eid bdb ve)] (add! de :seon.rule/nil 84 (:max-tx bdb)))
                  (doseq [se (concat (eav-vals bdb ve :seon.code.var/input) (eav-vals bdb ve :seon.code.var/output))]
                    (add! se :seon.rule/nil 86 (:max-tx bdb)))))
              (doseq [pe proof-eids] (add-proof-targets! bdb add! tscope ref-set pe))))
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

(defn- tokenize-goal [& args] (apply recall/tokenize-goal args))

(defn- entity-max-tx [db e]
  (if (and db (integer? e))
    (reduce (fn [m dt] (max m (long (or (:tx dt) 0)))) 0 (d/datoms db :eavt e))
    0))

(defn- edited-branch-var-eids [main-db branch-db]
  (if (or (nil? main-db) (nil? branch-db) (identical? main-db branch-db))
    []
    (let [fkeys (keep #(eav1 branch-db % :seon.code.form/key) (rules/edited-form-eids main-db branch-db))]
      (vec (distinct (mapcat #(avet-eids branch-db :seon.code.var/form %) fkeys))))))

(defn edited-var-neighborhood-rows
  "For every var edited on `branch-db` vs `main-db`, surfaces its I/O schemas, callers, reaching tests, and sibling vars sharing both input and output schemas."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db [:maybe :keyword]] [:vector [:tuple :int :any :int :int]]]
   :cost/ticks 15000000}
  [main-db branch-db _agent-id]
  (let [ed-vars (edited-branch-var-eids main-db branch-db)]
    (if (empty? ed-vars)
      []
      (let [best (volatile! {})
            add! (fn [eid pri]
                   (when (integer? eid)
                     (let [cur (get @best eid)]
                       (when (or (nil? cur) (> (long pri) (long (nth cur 2))))
                         (vswap! best assoc eid [eid :seon.rule/nil (long pri) (entity-max-tx branch-db eid)])))))]
        (doseq [ve ed-vars
                :let [ins (eav-vals branch-db ve :seon.code.var/input)
                      outs (eav-vals branch-db ve :seon.code.var/output)
                      out-set (set outs)]]
          (doseq [se (concat ins outs)] (add! se 87))
          (doseq [caller (avet-eids branch-db :seon.code.var/calls ve)
                  :when (and (not= caller ve)
                             (not (contains? test-harness-nss (eav1 branch-db caller :seon.code.var/ns))))]
            (add! caller (if (true? (eav1 branch-db caller :seon.code.var/test?)) 88 85)))
          (when (and (seq ins) (seq out-set))
            (doseq [in-e ins
                    sib (avet-eids branch-db :seon.code.var/input in-e)
                    :when (and (not= sib ve)
                               (some out-set (eav-vals branch-db sib :seon.code.var/output)))]
              (add! sib 84))))
        (vec (sort-by (fn [[e _ pri _]] [(- (long pri)) (long e)]) (vals @best)))))))

(defn- recall-matching-schemas! [& args] (apply recall/recall-matching-schemas! args))

(defn- add-turn0-var-neighborhood! [& args] (apply recall/add-turn0-var-neighborhood! args))

(defn referenced-doc-rows
  "Surfaces `:seon.code.doc/entity` atoms referencing `agent-id`'s pinned entities or branch-edited vars."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db [:maybe :keyword]] [:vector [:tuple :int :any :int :int]]]
   :cost/ticks 10000000}
  [main-db branch-db agent-id]
  (let [bdb (or branch-db main-db)
        mdb (or main-db branch-db)]
    (if-not (and agent-id bdb (dh-has? bdb :seon.code.doc/references))
      []
      (let [ag (rdata/pull-agent-entity mdb bdb agent-id)
            pinned (resolve-ordered-eids bdb (:seon.agent/pinned ag))
            ed-vars (edited-branch-var-eids mdb bdb)
            targets (vec (distinct (concat pinned ed-vars)))]
        (if (empty? targets)
          []
          (let [docs (into #{}
                           (mapcat (fn [t]
                                     (concat (avet-eids bdb :seon.code.doc/references t)
                                             (when (and mdb (not (identical? mdb bdb)) (dh-has? mdb :seon.code.doc/references))
                                               (avet-eids mdb :seon.code.doc/references t)))))
                           targets)]
            (mapv (fn [d] [d :seon.rule/nil 83 (entity-max-tx bdb d)]) (sort docs))))))))

(defn failing-test-target-rows
  "When a test fails on the branch, surfaces the production var(s) directly called by the failing test var."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db [:maybe :keyword]] [:vector [:tuple :int :any :int :int]]]
   :cost/ticks 10000000}
  [main-db branch-db agent-id]
  (let [bdb (or branch-db main-db)
        mdb (or main-db branch-db)]
    (if-not (and agent-id bdb
                 (dh-has? bdb :seon.code.var/calls)
                 (dh-has? bdb :seon.test.run/failed-digests)
                 (or (first (d/datoms bdb :aevt :seon.test.run/failed-digests))
                     (and mdb (not (identical? mdb bdb)) (dh-has? mdb :seon.test.run/failed-digests)
                          (first (d/datoms mdb :aevt :seon.test.run/failed-digests)))))
      []
      (let [ae (or (first (avet-eids bdb :seon.agent/id agent-id))
                   (when (and mdb (not (identical? mdb bdb))) (first (avet-eids mdb :seon.agent/id agent-id))))
            br (when ae (or (eav1 bdb ae :seon.agent/branch) (eav1 mdb ae :seon.agent/branch)))
            failed-dgs (when br
                         (let [collect (fn [db]
                                         (when (and db (dh-has? db :seon.test.run/branch) (dh-has? db :seon.test.run/failed-digests))
                                           (for [dt (d/datoms db :avet :seon.test.run/branch br)
                                                 :when (false? (eav1 db (:e dt) :seon.test.run/pass?))
                                                 dg (eav-vals db (:e dt) :seon.test.run/failed-digests)]
                                             dg)))]
                           (into #{} (concat (collect bdb) (when (not (identical? mdb bdb)) (collect mdb))))))]
        (if (empty? failed-dgs)
          []
          (let [prods (into #{}
                            (for [dg failed-dgs
                                  :let [tve (or (first (avet-eids bdb :seon.test/digest dg))
                                                (when (not (identical? mdb bdb)) (first (avet-eids mdb :seon.test/digest dg))))
                                        var-e (when tve (or (eav1 bdb tve :seon.test/var-ref) (eav1 mdb tve :seon.test/var-ref)))]
                                  :when var-e
                                  prod (eav-vals bdb var-e :seon.code.var/calls)
                                  :when (not (contains? test-harness-nss (eav1 bdb prod :seon.code.var/ns)))]
                              prod))]
            (mapv (fn [p] [p :seon.rule/nil 89 (entity-max-tx bdb p)]) (sort prods))))))))

(defn- semantic-recall-rows! [& args] (apply recall/semantic-recall-rows! args))

(defn goal-lexical-rows
  "General Turn-0 recall: for scoped open non-template tasks (with no proofs yet), uses O(1) indexed `:avet` lookups to recall named entity schemas, lexically relevant public vars in scope (plus 1-hop I/O schemas, callers & reaching tests for top matches; falling back to top 4 public vars only on Turn 0 before any evals/pins), and sibling `-test` ns; for vague unscoped goals, recalls matching docs & vars."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db [:maybe :keyword]] [:vector [:tuple :int :any :int :int]]]
   :cost/ticks 20000000}
  [main-db branch-db agent-id]
  (if-not (and agent-id (or main-db branch-db))
    []
    (let [bdb (or branch-db main-db)
          mdb (or main-db branch-db)
          ag (rdata/pull-agent-entity mdb bdb agent-id)
          tids (vec (distinct (concat (rdata/agent-task-ids bdb ag) (rdata/agent-task-ids mdb ag))))
          ag-sc (let [sc (:seon.agent/scope ag)] (when (and (symbol? sc) (not= 'seon sc)) sc))
          task-ents (vec (keep (fn [tid]
                                 (let [tdb (if (first (avet-eids bdb :seon.task/id tid)) bdb mdb)
                                       te (first (avet-eids tdb :seon.task/id tid))]
                                   (when (and te (not (true? (eav1 tdb te :seon.task/done?)))
                                              (not (true? (eav1 tdb te :seon.task/template?))))
                                     {:eid te :tdb tdb
                                     :scope (let [s (eav1 tdb te :seon.task/scope)] (when (and (symbol? s) (not= 'seon s)) s))
                                     :title (eav1 tdb te :seon.task/title)
                                     :plan (eav1 tdb te :seon.task/plan)
                                     :has-proofs? (boolean (seq (task-proof-var-eids tdb bdb te)))})))
                               tids))
          scopes (vec (distinct (remove nil? (cons ag-sc (map :scope task-ents)))))
          any-proof? (boolean (some :has-proofs? task-ents))
          has-pins? (boolean (seq (:seon.agent/pinned ag)))
          has-evals? (boolean (first (avet-eids bdb :seon.agent.eval/agent agent-id)))
          goal-txt (str (:seon.agent/goal ag) " " (str/join " " (map #(str (:title %) " " (:plan %)) task-ents)))
          low-goal (str/lower-case (or goal-txt ""))
          toks (tokenize-goal goal-txt)
          score-txt (fn [s]
                      (if (str/blank? (str s)) 0
                          (let [low (str/lower-case (str s))]
                            (reduce + 0 (map #(if (str/includes? low %) 1 0) toks)))))
          best (volatile! {})
          add! (fn [eid pri]
                 (when (integer? eid)
                   (let [cur (get @best eid)]
                     (when (or (nil? cur) (> (long pri) (long (nth cur 2))))
                       (vswap! best assoc eid [eid :seon.rule/nil (long pri) (entity-max-tx bdb eid)])))))]
      (cond
        ;; 1. Scoped open non-template task (no proofs yet): O(1) indexed recall of named schemas, matching scope vars + 1-hop neighborhood, and sibling -test ns!
        (and (seq scopes) (not any-proof?) (or (seq task-ents) (and (empty? (rules/edited-form-eids mdb bdb)) (not (str/blank? (str (:seon.agent/goal ag)))))))
        (do
          (recall-matching-schemas! bdb add! scopes goal-txt)
          (doseq [sc (take 2 scopes)]
            (when-let [tnse (first (avet-eids bdb :seon.code.ns/name (symbol (str sc "-test"))))]
              (add! tnse 79))
            (let [pub-vars (filterv #(not (true? (eav1 bdb % :seon.code.var/private?)))
                                    (avet-eids bdb :seon.code.var/ns sc))
                  scored (->> pub-vars
                              (map (fn [ve]
                                     (let [vn (eav1 bdb ve :seon.code.var/name)
                                           vlocal (when vn (str/lower-case (name vn)))
                                           vfull (when vn (str/lower-case (str vn)))
                                           exact? (boolean (or (and vfull (str/includes? low-goal vfull))
                                                               (and vlocal (>= (count vlocal) 4) (str/includes? low-goal vlocal))))
                                           nm-sc (if vlocal (score-txt vlocal) 0)
                                           doc-sc (score-txt (eav1 bdb ve :seon.code.var/doc))
                                           sc-val (+ (if exact? 10 0) (* 2 nm-sc) doc-sc)]
                                       [ve sc-val exact?])))
                              (sort-by (fn [[ve sc-val _]] [(- sc-val) ve])))
                  matched (filterv #(pos? (second %)) scored)
                  chosen (cond
                           (seq matched) (take 6 matched)
                           (and (not has-pins?) (not has-evals?)) (take 4 scored)
                           :else [])]
              (doseq [[ve sc-val exact?] chosen]
                (add! ve (cond exact? 89 (>= sc-val 2) 85 :else 78)))
              (doseq [[ve _ _] (take 2 (filter #(>= (second %) 2) matched))]
                (add-turn0-var-neighborhood! bdb add! ve))))
          (vec (sort-by (fn [[e _ pri _]] [(- (long pri)) (long e)]) (vals @best))))

        ;; 2. Unscoped vague goal/task: recall top matching docs, vars, and named schemas!
        (and (empty? scopes) (not any-proof?) (seq toks))
        (do
        (recall-matching-schemas! bdb add! nil goal-txt)
        (when-not (semantic-recall-rows! mdb bdb agent-id task-ents add!)
          (when (dh-has? bdb :seon.code.doc/id)
            (doseq [[e _] (->> (d/datoms bdb :aevt :seon.code.doc/id)
                               (keep (fn [dt]
                                       (let [e (:e dt)
                                             id-sc (score-txt (:v dt))
                                             hd-sc (score-txt (eav1 bdb e :seon.code.doc/heading))
                                             sc (+ (* 2 id-sc) (* 2 hd-sc))]
                                         (when (>= sc 2) [e sc]))))
                               (sort-by (fn [[e sc]] [(- sc) e]))
                               (take 4))]
              (add! e 84)))
          (when (dh-has? bdb :seon.code.var/name)
            (doseq [[e _] (->> (d/datoms bdb :aevt :seon.code.var/name)
                               (keep (fn [dt]
                                       (let [e (:e dt)
                                             nm-sc (score-txt (str (:v dt)))
                                             sc (if (pos? nm-sc) (+ (* 2 nm-sc) (score-txt (eav1 bdb e :seon.code.var/doc))) 0)]
                                         (when (>= sc 2) [e sc]))))
                               (sort-by (fn [[e sc]] [(- sc) e]))
                               (take 6))]
              (add! e 83))))
        (vec (sort-by (fn [[e _ pri _]] [(- (long pri)) (long e)]) (vals @best))))

        :else []))))