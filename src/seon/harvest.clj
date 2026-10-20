(ns seon.harvest
  "Stage the LIVE runtime onto the branch (owner, 2026-10-20: REPL semantics, no replay;
  git add -A of the runtime). Each def an agent evaluates is already recorded on its
  branch as it runs; staging records what only the live runtime knows, so the branch's
  facts are the program this agent holds:
  - a var unmapped from a program namespace is retracted (an unmap merges as a delete);
  - a plain def is carried as its VALUE when the value reads back equal from EDN, so
    `(def limit (do (fire!) 42))` merges as `(def limit 42)`; any other plain def, and a
    var with no recorded source, is flagged: an unstaged local change, never merged;
  - each program namespace's aliases become :ns/alias facts.
  Nothing the agent evaluated is ever evaluated again. Scratch namespaces, the eval
  history and the agent's turn facts never become program."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [datahike.api :as d] [seon.agent :as agent] [seon.program :as program]))

(defn- pr-edn {:malli/schema [:=> [:cat :any] :string]} [v]
  (binding [*print-length* nil *print-level* nil *print-meta* false] (pr-str v)))

(defn- program-nss
  "The program namespaces of db (those with an :ns/name) loaded in this runtime."
  {:malli/schema [:=> [:cat :seon.host/db] [:sequential :symbol]]}
  [db]
  (sort (filter find-ns (d/q '[:find [?n ...] :where [_ :ns/name ?n]] db))))

(defn- program-vars
  "Every var interned now in the program namespaces of db."
  {:malli/schema [:=> [:cat :seon.host/db] [:sequential :seon.host/var]]}
  [db]
  (for [n (program-nss db) v (sort-by str (vals (ns-interns (find-ns n))))] v))

(defn uncontracted
  "The functions of the program namespaces that declare no contract (main's own gaps
  included; callers subtract what was there before the attempt)."
  {:malli/schema [:=> [:cat :seon.host/db] [:set :qualified-symbol]]}
  [db]
  (into #{} (for [v (program-vars db) :when (and (= :fn (program/var-kind v)) (nil? (:malli/schema (meta v))))] (symbol v))))

(defn- edn-value?
  "Is v plain data that EDN prints and reads back equal (so a plain def can be stored as
  data): nil, booleans, numbers, strings, keywords, symbols, and lists, vectors, maps and
  sets of them."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [v]
  (boolean (cond (record? v) false
                 (or (nil? v) (boolean? v) (number? v) (string? v) (keyword? v) (symbol? v)) true
                 (map? v) (every? (fn [[k x]] (and (edn-value? k) (edn-value? x))) v)
                 (coll? v) (every? edn-value? v)
                 :else false)))

(defn stage-tx
  "tx-data making branch db's program facts match this live runtime (see the ns doc)."
  {:malli/schema [:=> [:cat :seon.host/db] :seon.db/tx-data]}
  [db]
  (let [recorded (into {} (d/q '[:find ?v ?e :where [?e :var/name ?v]] db))
        nss (set (program-nss db))
        live (into {} (map (juxt symbol identity)) (program-vars db))]
    (vec (concat
           ;; unmapped: recorded in a loaded program namespace, gone from the runtime
           (for [[sym e] (sort-by (comp str key) recorded)
                 :when (and (nss (symbol (namespace sym))) (not (live sym)))]
             [:db/retractEntity e])
           (for [[sym v] (sort-by (comp str key) live)
                 :let [e (recorded sym) ent (when e (d/entity db e))
                       nsr [:ns/name (symbol (namespace sym))]]
                 :when (or (nil? e) (= :value (program/var-kind v)))
                 tx (cond
                      (and (= :value (program/var-kind v)) (bound? v) (edn-value? @v))
                      (let [src (pr-edn (list 'def (symbol (name sym)) @v))]
                        (cond-> []
                          (not= src (:var/src ent)) (conj {:var/name sym :var/ns nsr :var/src src :var/kind :value})
                          (:var/flagged ent) (conj [:db/retract e :var/flagged true])))
                      (:var/flagged ent) []
                      :else [{:var/name sym :var/ns nsr :var/kind (program/var-kind v) :var/flagged true}])]
             tx)
           ;; aliases: "[alias target]" per program namespace
           (for [n (sort nss)
                 :let [ne (d/q '[:find ?e . :in $ ?n :where [?e :ns/name ?n]] db n)
                       have (set (:ns/alias (d/entity db ne)))
                       want (into #{} (for [[a target] (ns-aliases (find-ns n))] (pr-edn [a (ns-name target)])))]
                 op (concat (for [s (sort want) :when (not (have s))] [:db/add ne :ns/alias s])
                            (for [s (sort have) :when (not (want s))] [:db/retract ne :ns/alias s]))]
             op)))))

(defn harvest!
  "Stage the live runtime onto this agent's branch (one transaction). Answers the tx-data
  written."
  {:malli/schema [:=> [:cat] :seon.db/tx-data]}
  []
  (let [tx (stage-tx (agent/db))]
    (when (seq tx) (agent/transact! tx))
    tx))
