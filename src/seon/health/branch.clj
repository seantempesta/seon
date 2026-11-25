(ns seon.health.branch
  "Extracted child namespace of `seon.health.contracts`."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.health.static :as static]
            [seon.health.contracts :as contracts]
            [seon.store :as store]))

(defn touched-ns-var-delta
  "O(|vars in nss|) `{:ovm :tvm :retracted-syms :added-edge-syms :var-retracted? :edge-added? :refs-changed?}`."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db [:sequential :symbol]] :map]
   :cost/ticks 15000000}
  [ours-db theirs-db nss]
  (let [scan (fn [db]
               (if-not (and db (contains? (:schema db) :seon.code.var/ns))
                 {:vm {} :all #{}}
                 (let [dh (:schema db)
                       has-e? (contains? dh :seon.code.var/flow-edge?)
                       has-r? (contains? dh :seon.code.var/refs)
                       has-t? (contains? dh :seon.code.var/test?)
                       fnss (static/foreign-nss db)
                       vm (transient {})
                       all (transient #{})]
                   (doseq [nsym nss
                           :let [skip-ns? (or (str/ends-with? (name nsym) "-test") (contains? fnss nsym))]
                           dt (d/datoms db :avet :seon.code.var/ns nsym)
                           :let [e (:e dt)
                                 nm (:v (first (d/datoms db :eavt e :seon.code.var/name)))]
                           :when nm]
                     (conj! all nm)
                     (when-not (or skip-ns? (and has-t? (true? (:v (first (d/datoms db :eavt e :seon.code.var/test?))))))
                       (assoc! vm nm {:seon.code.var/name nm
                                      :seon.code.var/ns nsym
                                      :seon.code.var/flow-edge? (boolean (and has-e? (:v (first (d/datoms db :eavt e :seon.code.var/flow-edge?)))))
                                      :seon.code.var/refs (if has-r? (into #{} (map :v) (d/datoms db :eavt e :seon.code.var/refs)) #{})})))
                   {:vm (persistent! vm) :all (persistent! all)})))
        o (scan ours-db) t (scan theirs-db)
        ovm (:vm o) tvm (:vm t)
        ret (vec (remove #(contains? (:all t) %) (:all o)))
        edge-add (vec (keep (fn [[nm m]]
                              (when (and (:seon.code.var/flow-edge? m)
                                         (not (:seon.code.var/flow-edge? (get ovm nm))))
                                nm))
                            tvm))]
    {:ovm ovm :tvm tvm :retracted-syms ret :added-edge-syms edge-add
     :var-retracted? (boolean (seq ret))
     :edge-added? (boolean (seq edge-add))
     :refs-changed? (boolean (some (fn [[nm m]]
                                     (let [om (get ovm nm)]
                                       (or (nil? om)
                                           (and (:seon.code.var/flow-edge? om) (not (:seon.code.var/flow-edge? m)))
                                           (not= (:seon.code.var/refs om) (:seon.code.var/refs m)))))
                                   tvm))}))

(defn caused-by-branch-impl
  "O(|touched-nss| + |retracted| + |added-edges|) implementation of `seon.health/caused-by-branch`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] :map [:maybe :any] :map] [:vector :seon.health/problem]]}
  [ours-db theirs-db changed-schema-forms touched-nss {:keys [cache schema-violations-fn blocking-fn ubd-fn mns-fn]}]
  (let [no-code? (and (some? touched-nss) (empty? touched-nss))
        ocid (when (instance? datahike.db.DB ours-db) (d/commit-id ours-db))
        tcid (when (instance? datahike.db.DB theirs-db) (d/commit-id theirs-db))
        sid (get-in ours-db [:config :store :id])]
    (if (and (empty? changed-schema-forms)
             (or no-code? (and (some? ocid) (= ocid tcid))))
      []
      (let [schema-errs (schema-violations-fn ours-db changed-schema-forms)
            get-base-vmap (delay
                            (let [compute (fn []
                                            (let [osnap (static/var-snapshot ours-db {:schemas? false})]
                                              {:vmap (:vmap osnap)
                                               :blocks (into #{} (map (juxt :class :subject :var)) (blocking-fn ours-db (:vmap osnap)))}))]
                              (if ocid (store/lru-get! cache 8 [:vmap-v1 sid ocid] compute) (compute))))
            get-base-full (delay
                            (let [compute (fn []
                                            (let [osnap (static/var-snapshot ours-db {:schemas? true})]
                                              {:vmap (:vmap osnap)
                                               :blocks (into #{} (map (juxt :class :subject :var)) (blocking-fn ours-db (:vmap osnap)))
                                               :bref (into #{} (map :subject) (contracts/broken-symbol-refs ours-db osnap))
                                               :dref (into #{} (map :subject) (contracts/broken-doc-refs ours-db nil osnap))
                                               :imp (into #{} (map :subject) (contracts/impure-generatable-fns ours-db nil osnap))}))]
                              (if ocid (store/lru-get! cache 8 [:v2 sid ocid] compute) (compute))))]
        (if no-code?
          (vec (concat schema-errs
                       (remove #(contains? (:bref @get-base-full) (:subject %)) (contracts/broken-symbol-refs theirs-db))))
          (let [tseq (when (some? touched-nss) (vec (distinct (remove nil? touched-nss))))
                delta (when (seq tseq) (touched-ns-var-delta ours-db theirs-db tseq))
                ret-syms (:retracted-syms delta)
                edge-syms (:added-edge-syms delta)
                refs-chg? (if delta (:refs-changed? delta) true)
                need-full-tsnap? (or (nil? tseq) (seq changed-schema-forms))
                tsnap (when need-full-tsnap? (static/var-snapshot theirs-db {:schemas? true}))
                new-blocks (if refs-chg?
                             (let [base (if need-full-tsnap? @get-base-full @get-base-vmap)
                                   tvmap (if tsnap
                                           (:vmap tsnap)
                                           (merge (apply dissoc (:vmap base) (keys (:ovm delta))) (:tvm delta)))]
                               (remove #(contains? (:blocks base) ((juxt :class :subject :var) %))
                                       (blocking-fn theirs-db tvmap)))
                             [])
                base-ubd (into #{} (map :subject) (ubd-fn ours-db tseq))
                new-ubd (remove #(contains? base-ubd (:subject %)) (ubd-fn theirs-db tseq))
                base-mns (into #{} (map :subject) (mns-fn ours-db tseq))
                new-mns (remove #(contains? base-mns (:subject %)) (mns-fn theirs-db tseq))
                new-bref (cond
                           need-full-tsnap?
                           (remove #(contains? (:bref @get-base-full) (:subject %)) (contracts/broken-symbol-refs theirs-db tsnap))
                           (seq ret-syms)
                           (let [ob (into #{} (map :subject) (contracts/broken-symbol-refs ours-db nil {:target-syms ret-syms}))]
                             (remove #(contains? ob (:subject %)) (contracts/broken-symbol-refs theirs-db nil {:target-syms ret-syms})))
                           :else [])
                dedup (fn [xs] (vals (into {} (map (juxt :subject identity)) xs)))
                new-dref (if need-full-tsnap?
                           (remove #(contains? (:dref @get-base-full) (:subject %)) (contracts/broken-doc-refs theirs-db nil tsnap))
                           (let [bd1 (into #{} (map :subject) (contracts/broken-doc-refs ours-db nil nil {:target-nss tseq}))
                                 nd1 (remove #(contains? bd1 (:subject %)) (contracts/broken-doc-refs theirs-db nil nil {:target-nss tseq}))
                                 nd2 (when (seq ret-syms)
                                       (let [bd2 (into #{} (map :subject) (contracts/broken-doc-refs ours-db nil nil {:target-syms ret-syms}))]
                                         (remove #(contains? bd2 (:subject %)) (contracts/broken-doc-refs theirs-db nil nil {:target-syms ret-syms}))))]
                             (dedup (concat nd1 nd2))))
                new-imp (if need-full-tsnap?
                          (remove #(contains? (:imp @get-base-full) (:subject %)) (contracts/impure-generatable-fns theirs-db nil tsnap))
                          (let [bi1 (into #{} (map :subject) (contracts/impure-generatable-fns ours-db nil nil {:target-nss tseq}))
                                ni1 (remove #(contains? bi1 (:subject %)) (contracts/impure-generatable-fns theirs-db nil nil {:target-nss tseq}))
                                ni2 (when (seq edge-syms)
                                      (let [bi2 (into #{} (map :subject) (contracts/impure-generatable-fns ours-db nil nil {:edge-syms edge-syms}))]
                                        (remove #(contains? bi2 (:subject %)) (contracts/impure-generatable-fns theirs-db nil nil {:edge-syms edge-syms}))))]
                            (dedup (concat ni1 ni2))))
                new-contracts (contracts/missing-contract-violations ours-db theirs-db tseq)]
            (vec (concat schema-errs new-blocks new-ubd new-mns new-bref new-dref new-imp new-contracts))))))))