(ns seon.ctx.recall
  "Extracted child namespace of `seon.ctx.graph`."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.render.data :as rdata]
            [seon.schema :as schema]
            [seon.rule :as rules]
            [seon.vector :as svec]
            [seon.store :as store]))

(defn dh-has?
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :keyword] :boolean]}
  [db a] (boolean (and db (contains? (:schema db) a))))

(defn eav-vals
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :any :keyword] [:maybe [:vector :any]]]}
  [db e a]
  (when (and (integer? e) (dh-has? db a))
    (mapv :v (d/datoms db :eavt e a))))

(defn eav1
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :any :keyword] :any]}
  [db e a]
  (when (and (integer? e) (dh-has? db a))
    (:v (first (d/datoms db :eavt e a)))))

(defn avet-eids
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :keyword :any] [:maybe [:vector :int]]]}
  [db a v]
  (when (and (some? v) (dh-has? db a))
    (mapv :e (d/datoms db :avet a v))))

(defn doc-atoms-for-eid
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :any] [:vector :int]]}
  [db eid]
  (let [fwd-var (eav-vals db eid :seon.code.var/doc-refs)
        fwd-sch (eav-vals db eid :seon.schema/doc-refs)
        rev-doc (avet-eids db :seon.code.doc/references eid)]
    (vec (distinct (filter #(and (integer? %) (some? (eav1 db % :seon.code.doc/id)))
                           (concat fwd-var fwd-sch rev-doc))))))

(def test-harness-nss
  #{'seon.test-fixture})

(def ^:private lexical-stopwords
  #{"the" "and" "for" "with" "that" "this" "from" "into" "when" "where" "what" "have" "has"
    "fix" "add" "update" "make" "use" "using" "all" "any" "new" "one" "two" "not" "are" "can"})

(defn tokenize-goal
  {:malli/schema [:=> [:cat [:maybe :string]] [:maybe [:vector :string]]], :cost/ticks 500000}
  [s]
  (when (and (string? s) (not (str/blank? s)))
    (->> (re-seq #"[a-zA-Z0-9]{3,}" (str/lower-case s))
         (remove lexical-stopwords)
         distinct
         vec)))

(defn recall-matching-schemas!
  "O(k log N) indexed `:avet` lookup of schemas explicitly named in `goal-txt` (plus `<scope>/entity` conventions), avoiding any O(N) `:aevt` schema scan."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/fn :any [:maybe :string]] :any]}
  [bdb add! scopes goal-txt]
  (when (dh-has? bdb :seon.schema/key)
    (let [kw-strs (re-seq #":([a-zA-Z][a-zA-Z0-9._?!*+-]*/[a-zA-Z0-9._?!*+-]+)" (or goal-txt ""))
        explicit-ks (into [] (comp (map #(keyword (second %)))
                                   (mapcat (fn [k]
                                             (let [ns-s (namespace k)]
                                               (if (and ns-s (not= "entity" (name k)))
                                                 [k (keyword ns-s "entity")]
                                                 [k])))))
                          kw-strs)
        scope-ks (map (fn [sc] (keyword (str sc) "entity")) (take 2 scopes))
        resolved (into [] (comp (distinct)
                                (keep (fn [k]
                                        (when-let [se (first (avet-eids bdb :seon.schema/key k))]
                                          [se (if (= "entity" (name k)) 86 83)]))))
                       (concat explicit-ks scope-ks))]
    (doseq [[se pri] (take 8 resolved)]
      (add! se pri)))))

(defn add-turn0-var-neighborhood!
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/fn :int] :any]}
  [bdb add! ve]
  (doseq [se (concat (eav-vals bdb ve :seon.code.var/input) (eav-vals bdb ve :seon.code.var/output))]
    (add! se 82))
  (doseq [de (take 2 (doc-atoms-for-eid bdb ve))] (add! de 81))
  (let [callers (remove #(or (= % ve) (contains? test-harness-nss (eav1 bdb % :seon.code.var/ns)))
                        (avet-eids bdb :seon.code.var/calls ve))
        t-callers (take 3 (filter #(true? (eav1 bdb % :seon.code.var/test?)) callers))
        p-callers (take 4 (remove #(true? (eav1 bdb % :seon.code.var/test?)) callers))]
    (doseq [tc t-callers] (add! tc 84))
    (doseq [pc p-callers] (add! pc 80))))

(defn semantic-recall-rows!
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :keyword :any :seon.host/fn] :any]}
  [mdb bdb agent-id task-ents add!]
  (when (and (dh-has? bdb :seon.vector/coarse) (dh-has? bdb :seon.vector/of))
    (let [min-sc (double (or (store/config-val bdb agent-id :seon.config/semantic-min-score) 0.45))
          qvec (some (fn [{:keys [tdb eid]}]
                       (or (svec/vector-of bdb eid)
                           (when (and tdb (not (identical? tdb bdb))) (svec/vector-of tdb eid))
                           (when (and mdb (not (identical? mdb bdb))) (svec/vector-of mdb eid))))
                     task-ents)]
      (when (seq qvec)
        (let [hit? (volatile! false)]
          (doseq [[of-attr k pri] [[:seon.code.var/name 6 85]
                                   [:seon.code.doc/id 4 85]
                                   [:seon.schema/key 4 84]]
                  [e sc] (svec/near bdb qvec k {:of of-attr})
                  :when (>= (double sc) min-sc)]
            (vreset! hit? true)
            (add! e pri))
          @hit?)))))
