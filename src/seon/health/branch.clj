(ns seon.health.branch
  "Extracted child namespace of `seon.health.contracts`."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.schema :as schema]
            [malli.core :as m]
            [seon.store :as store]
            [malli.registry :as mr]
            [seon.health.static :as static]))

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
  "O(|vars in nss|) `{:ovm :tvm :var-retracted? :edge-added? :refs-changed?}` matching `static/var-snapshot` filters."
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
        ovm (:vm o) tvm (:vm t)]
    {:ovm ovm :tvm tvm
     :var-retracted? (boolean (some #(not (contains? (:all t) %)) (:all o)))
     :edge-added? (boolean (some (fn [[nm m]] (and (:seon.code.var/flow-edge? m) (not (:seon.code.var/flow-edge? (get ovm nm))))) tvm))
     :refs-changed? (boolean (some (fn [[nm m]]
                                     (let [om (get ovm nm)]
                                       (or (nil? om)
                                           (and (:seon.code.var/flow-edge? om) (not (:seon.code.var/flow-edge? m)))
                                           (not= (:seon.code.var/refs om) (:seon.code.var/refs m)))))
                                   tvm))}))
