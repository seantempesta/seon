(ns seon.health.branch
  "Extracted child namespace of `seon.health.contracts`."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.schema :as schema]
            [seon.health.static :as static]
            [seon.health.contracts :as contracts]
            [seon.store :as store]))

(defn broken-doc-refs-for-nss
  "O(|vars in ns-syms|) indexed check for backticked qualified docstring symbol refs using point `:avet` lookups."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :symbol]] [:vector :map]]
   :cost/ticks 15000000}
  [db ns-syms]
  (if-not (and db (seq ns-syms) (contains? (:schema db) :seon.code.var/name) (contains? (:schema db) :seon.code.var/doc))
    []
    (let [br (get-in db [:config :branch] :main)
          pat #"`(seon\.[a-z0-9._-]+/[a-zA-Z0-9*+!_?<>=.-]+)`"
          parse-qsym (fn [^String s] (let [i (.indexOf s "/")] (when (pos? i) (symbol (subs s 0 i) (subs s (inc i))))))
          missing-in (fn [doc]
                       (when (and (string? doc) (str/includes? doc "`seon."))
                         (keep (fn [[_ ss]]
                                 (when-let [sym (parse-qsym ss)]
                                   (when-not (first (d/datoms db :avet :seon.code.var/name sym)) sym)))
                               (re-seq pat doc))))
          out (volatile! [])]
      (doseq [nsym (distinct ns-syms)
              :when (and nsym (not (str/ends-with? (name nsym) "-test")))]
        (doseq [dt (d/datoms db :avet :seon.code.var/ns nsym)
                :let [e (:e dt)
                      doc (:v (first (d/datoms db :eavt e :seon.code.var/doc)))]
                :when (and doc (str/includes? (str doc) "`seon."))]
          (when-let [ms (seq (missing-in (str doc)))]
            (when-let [vsym (:v (first (d/datoms db :eavt e :seon.code.var/name)))]
              (doseq [sym ms]
                (vswap! out conj {:class :broken-doc-ref :severity :error :kind :static :ns nsym :var vsym
                                  :subject (str vsym "->" sym) :branch br
                                  :message (str "Docstring of " vsym " references missing var `" sym "`. Update the docstring or restore the var.")})))))
        (when (contains? (:schema db) :seon.code.ns/doc)
          (when-let [ne (:e (first (d/datoms db :avet :seon.code.ns/name nsym)))]
            (when-let [ndoc (:v (first (d/datoms db :eavt ne :seon.code.ns/doc)))]
              (doseq [sym (missing-in (str ndoc))]
                (vswap! out conj {:class :broken-doc-ref :severity :error :kind :static :ns nsym
                                  :subject (str nsym "#ns->" sym) :branch br
                                  :message (str "Namespace docstring of " nsym " references missing var `" sym "`. Update the docstring.")}))))))
      (vec (sort-by :subject @out)))))

(defn impure-generatable-fns-for-nss
  "O(|vars in ns-syms|) indexed check for generatable non-edge fns calling `^:seon.flow/edge` targets."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :symbol]] [:vector :map]]
   :cost/ticks 15000000}
  [db ns-syms]
  (if-not (and db (seq ns-syms)
               (contains? (:schema db) :seon.code.var/schema)
               (contains? (:schema db) :seon.code.var/flow-edge?)
               (contains? (:schema db) :seon.code.var/refs))
    []
    (let [br (get-in db [:config :branch] :main)
          edge-cache (java.util.HashMap.)
          edge? (fn [sym]
                  (let [v (.get edge-cache sym)]
                    (if (some? v)
                      v
                      (let [res (boolean (when-let [e (:e (first (d/datoms db :avet :seon.code.var/name sym)))]
                                           (:v (first (d/datoms db :eavt e :seon.code.var/flow-edge?)))))]
                        (.put edge-cache sym res)
                        res))))]
      (->> (for [nsym (distinct ns-syms)
                 :when (and nsym (not (str/ends-with? (name nsym) "-test")))
                 dt (d/datoms db :avet :seon.code.var/ns nsym)
                 :let [e (:e dt)
                       sch (:v (first (d/datoms db :eavt e :seon.code.var/schema)))]
                 :when (and sch (not (:v (first (d/datoms db :eavt e :seon.code.var/flow-edge?))))
                            (schema/generatable-function-schema? sch))
                 :let [vsym (:v (first (d/datoms db :eavt e :seon.code.var/name)))
                       bad-refs (->> (d/datoms db :eavt e :seon.code.var/refs) (map :v) (filter edge?) sort distinct vec)]
                 :when (and vsym (seq bad-refs))]
             {:class :impure-generatable-fn :severity :error :kind :static :ns nsym :var vsym
              :subject (str vsym) :branch br
              :message (str "Pure generatable fn " vsym " references ^:seon.flow/edge var(s) " (str/join ", " bad-refs)
                            ". Mark " vsym " ^:seon.flow/edge or inject pure values instead.")})
           (sort-by :subject) vec))))

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

(defn broken-symbol-refs-for-syms
  "O(|schemas|) check flagging `:seon.render/hiccup` refs targeting any deleted var in `syms`."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :symbol]] [:vector :map]]
   :cost/ticks 10000000}
  [db syms]
  (if-not (and db (seq syms) (contains? (:schema db) :seon.schema/form) (contains? (:schema db) :seon.code.var/name))
    []
    (let [br (get-in db [:config :branch] :main)
          sset (into #{} (remove #(first (d/datoms db :avet :seon.code.var/name %))) syms)]
      (if (empty? sset)
        []
        (->> (for [dt (d/datoms db :aevt :seon.schema/form)
                   :let [f (:v dt)
                         r (when (and (vector? f) (map? (second f))) (:seon.render/hiccup (second f)))]
                   :when (contains? sset r)
                   :let [sk (:v (first (d/datoms db :eavt (:e dt) :seon.schema/key)))]
                   :when sk]
               {:class :broken-symbol-ref :severity :error :kind :static :ns (symbol (namespace r)) :var r
                :subject (str sk "->" r) :branch br
                :message (str "Schema " sk " declares :seon.render/hiccup " r " which does not exist.")})
             (sort-by :subject) vec)))))

(defn broken-doc-refs-for-retracted-syms
  "Scans `:seon.code.var/doc` and `:seon.code.ns/doc` only for backticked refs to deleted `syms` (0 var-snapshot)."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :symbol]] [:vector :map]]
   :cost/ticks 15000000}
  [db syms]
  (if-not (and db (seq syms) (contains? (:schema db) :seon.code.var/doc) (contains? (:schema db) :seon.code.var/name))
    []
    (let [br (get-in db [:config :branch] :main)
          missing (vec (distinct (remove #(first (d/datoms db :avet :seon.code.var/name %)) syms)))
          needles (mapv (fn [s] [s (str "`" s "`")]) missing)
          out (volatile! [])]
      (when (seq needles)
        (doseq [dt (d/datoms db :aevt :seon.code.var/doc)
                :let [doc (str (:v dt))]
                :when (str/includes? doc "`seon.")
                [sym needle] needles
                :when (str/includes? doc needle)
                :let [vsym (:v (first (d/datoms db :eavt (:e dt) :seon.code.var/name)))]
                :when (and vsym (not (str/ends-with? (str (namespace vsym)) "-test")))]
          (vswap! out conj {:class :broken-doc-ref :severity :error :kind :static
                            :ns (symbol (namespace vsym)) :var vsym
                            :subject (str vsym "->" sym) :branch br
                            :message (str "Docstring of " vsym " references missing var `" sym "`. Update the docstring or restore the var.")}))
        (when (contains? (:schema db) :seon.code.ns/doc)
          (doseq [dt (d/datoms db :aevt :seon.code.ns/doc)
                  :let [doc (str (:v dt))]
                  :when (str/includes? doc "`seon.")
                  [sym needle] needles
                  :when (str/includes? doc needle)
                  :let [nsym (:v (first (d/datoms db :eavt (:e dt) :seon.code.ns/name)))]
                  :when (and nsym (not (str/ends-with? (name nsym) "-test")))]
            (vswap! out conj {:class :broken-doc-ref :severity :error :kind :static :ns nsym
                              :subject (str nsym "#ns->" sym) :branch br
                              :message (str "Namespace docstring of " nsym " references missing var `" sym "`. Update the docstring.")}))))
      (vec (sort-by :subject @out)))))

(defn impure-callers-of-edge-syms
  "O(|direct callers|) indexed `:avet :seon.code.var/refs` check for pure generatable callers of newly `^:seon.flow/edge` vars."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :symbol]] [:vector :map]]
   :cost/ticks 10000000}
  [db edge-syms]
  (if-not (and db (seq edge-syms)
               (contains? (:schema db) :seon.code.var/refs)
               (contains? (:schema db) :seon.code.var/schema)
               (contains? (:schema db) :seon.code.var/flow-edge?))
    []
    (let [br (get-in db [:config :branch] :main)
          by-caller (reduce (fn [acc esym]
                              (reduce (fn [m dt] (update m (:e dt) (fnil conj #{}) esym))
                                      acc (d/datoms db :avet :seon.code.var/refs esym)))
                            {} (filter #(when-let [e (:e (first (d/datoms db :avet :seon.code.var/name %)))] (true? (:v (first (d/datoms db :eavt e :seon.code.var/flow-edge?))))) (distinct edge-syms)))]
      (->> (for [[e bad-set] by-caller
                 :let [sch (:v (first (d/datoms db :eavt e :seon.code.var/schema)))]
                 :when (and sch (not (:v (first (d/datoms db :eavt e :seon.code.var/flow-edge?))))
                            (schema/generatable-function-schema? sch))
                 :let [vsym (:v (first (d/datoms db :eavt e :seon.code.var/name)))
                       nsym (when vsym (symbol (namespace vsym)))]
                 :when (and vsym (not (str/ends-with? (name nsym) "-test")))]
             {:class :impure-generatable-fn :severity :error :kind :static :ns nsym :var vsym
              :subject (str vsym) :branch br
              :message (str "Pure generatable fn " vsym " references ^:seon.flow/edge var(s) " (str/join ", " (sort bad-set))
                            ". Mark " vsym " ^:seon.flow/edge or inject pure values instead.")})
           (sort-by :subject) vec))))

(defn caused-by-branch-impl
  "O(|touched-nss| + |retracted| + |added-edges|) implementation of `seon.health/caused-by-branch`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] :map [:maybe :any] :map] [:vector :map]]}
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
                           (let [ob (into #{} (map :subject) (broken-symbol-refs-for-syms ours-db ret-syms))]
                             (remove #(contains? ob (:subject %)) (broken-symbol-refs-for-syms theirs-db ret-syms)))
                           :else [])
                dedup (fn [xs] (vals (into {} (map (juxt :subject identity)) xs)))
                new-dref (if need-full-tsnap?
                           (remove #(contains? (:dref @get-base-full) (:subject %)) (contracts/broken-doc-refs theirs-db nil tsnap))
                           (let [bd1 (into #{} (map :subject) (broken-doc-refs-for-nss ours-db tseq))
                                 nd1 (remove #(contains? bd1 (:subject %)) (broken-doc-refs-for-nss theirs-db tseq))
                                 nd2 (when (seq ret-syms)
                                       (let [bd2 (into #{} (map :subject) (broken-doc-refs-for-retracted-syms ours-db ret-syms))]
                                         (remove #(contains? bd2 (:subject %)) (broken-doc-refs-for-retracted-syms theirs-db ret-syms))))]
                             (dedup (concat nd1 nd2))))
                new-imp (if need-full-tsnap?
                          (remove #(contains? (:imp @get-base-full) (:subject %)) (contracts/impure-generatable-fns theirs-db nil tsnap))
                          (let [bi1 (into #{} (map :subject) (impure-generatable-fns-for-nss ours-db tseq))
                                ni1 (remove #(contains? bi1 (:subject %)) (impure-generatable-fns-for-nss theirs-db tseq))
                                ni2 (when (seq edge-syms)
                                      (let [bi2 (into #{} (map :subject) (impure-callers-of-edge-syms ours-db edge-syms))]
                                        (remove #(contains? bi2 (:subject %)) (impure-callers-of-edge-syms theirs-db edge-syms))))]
                            (dedup (concat ni1 ni2))))
                new-contracts (contracts/missing-contract-violations ours-db theirs-db tseq)]
            (vec (concat schema-errs new-blocks new-ubd new-mns new-bref new-dref new-imp new-contracts))))))))