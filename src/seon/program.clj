(ns seon.program
  "The program is entities. Every var of a program namespace (one with an :ns/name in
  the db) has its source as :var/src, and each process runs the program its db holds:
  - root and the fork server load main's program at boot and every :var/src that main's
    commits change. So an agent forked after a merge inherits the merged code compiled,
    and loads nothing.
  - an agent records each def it evaluates in a program namespace as a fact on its
    branch (read -> evaluate -> record), and loads :var/src that reaches its branch from
    main (merge-main!).
  Source is evaluated natively in its namespace (Jolt compiles it)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-] :as contract] [datahike.api :as d]))

(def ^:private def-heads '#{def defn defn- defmacro defonce deftest defmulti defrecord deftype defprotocol})

(defn def-form?
  "A top-level form that defines a var: (defn f ...), (deftest t ...), ..."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [form]
  (and (seq? form) (symbol? (first form)) (contains? def-heads (symbol (name (first form))))))

(defn program-ns?
  "Is ns-sym a program namespace in db?"
  {:malli/schema [:=> [:cat :seon.host/db :symbol] :boolean]}
  [db ns-sym]
  (some? (d/q '[:find ?e . :in $ ?n :where [?e :ns/name ?n]] db ns-sym)))

(defn ensure-ns!
  "The namespace, created with clojure.core referred when it is new."
  {:malli/schema [:=> [:cat :symbol] :seon.host/namespace]}
  [ns-sym]
  (or (find-ns ns-sym)
      (let [n (create-ns ns-sym)]
        (binding [*ns* n]
          (refer-clojure)
          (refer 'clojure.test :only '[deftest is are testing]))
        n)))

(defn aliases!
  "Give each namespace of nsyms the aliases db records for it (:ns/alias \"[a target]\"),
  requiring the target first, so a definition that uses an alias loads (lane M1, core patch
  04). Answers the error maps of targets that could not be required."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :symbol]] [:vector :seon.program/error]]}
  [db nsyms]
  (vec (for [n (distinct nsyms)
             s (d/q '[:find [?s ...] :in $ ?n :where [?e :ns/name ?n] [?e :ns/alias ?s]] db n)
             :let [[a target] (read-string s)
                   err (try (when-not (find-ns target) (require target))
                            (binding [*ns* (ensure-ns! n)] (alias a target))
                            nil
                            (catch Throwable e {:ns n :message (str "alias " s ": " (or (ex-message e) (str (type e))))}))]
             :when err]
         err)))

(defn load-src!
  "Evaluate one var's source in its namespace and arm its contract (seon.contract/arm!):
  answers nil, or an error map (G1's proto.program/load-src!)."
  {:malli/schema [:=> [:cat :symbol :string] [:maybe :seon.program/error]]}
  [ns-sym src]
  (let [v (try (binding [*ns* (ensure-ns! ns-sym)] (eval (read-string src)))
               (catch Throwable e {:ns ns-sym :message (or (ex-message e) (str (type e)))}))]
    (cond
      (and (map? v) (:ns v) (contains? v :message)) v
      (var? v) (let [r (contract/arm! v)]
                 (when (contract/error? r) {:ns ns-sym :message (:seon.error/message r)}))
      :else nil)))

(defn load-all!
  "Load every :var/src in db: each namespace's aliases, then macros, then functions and
  values, then tests (a test namespace may sort before its subject), by name within each.
  Answers {:loaded n :errors [...]}."
  {:malli/schema [:=> [:cat :seon.host/db] :seon.program/load-report]}
  [db]
  (let [rows (d/q '[:find ?v ?src ?e :where [?e :var/name ?v] [?e :var/src ?src]] db)
        rank (fn [[_ _ e]] (case (:var/kind (d/entity db e)) :macro 0 :test 2 1))
        alias-errs (aliases! db (d/q '[:find [?n ...] :where [?e :ns/alias] [?e :ns/name ?n]] db))
        errs (vec (keep (fn [[v src]] (load-src! (symbol (namespace v)) src)) (sort-by (juxt rank (comp str first)) rows)))]
    {:loaded (count rows) :errors (into alias-errs errs)}))

(defn load-changes!
  "Load the :var/src that resolved tx-data ops assert, read against db (after them), and
  unmap every var whose :var/name they retract; aliases they assert come first. Answers
  the error maps (empty when all loaded)."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :seon.db/op]] [:vector :seon.program/error]]}
  [db ops]
  (doseq [[op _ a v] ops
          :when (and (= op :db/retract) (= a :var/name) (symbol? v))
          :let [n (some-> (namespace v) symbol find-ns)]
          :when n]
    (ns-unmap n (symbol (name v))))
  (into (aliases! db (for [[op e a _] ops :when (and (= op :db/add) (= a :ns/alias))] (:ns/name (d/entity db e))))
        (for [[op e a src] ops
              :when (and (= op :db/add) (= a :var/src))
              :let [v (d/q '[:find ?v . :in $ ?e :where [?e :var/name ?v]] db e)]
              :when v
              :let [err (load-src! (symbol (namespace v)) src)]
              :when err]
          err)))

(defn load-diff!
  "Make this process run db-to's program where it holds db-from's: each definition whose
  recorded content differs is loaded (or unmapped when db-to has none). An agent resuming
  a kept branch was forked holding main's program. Answers the load errors."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db] [:vector :seon.program/error]]}
  [db-from db-to]
  (let [srcs (fn [db] (into {} (d/q '[:find ?v ?s :where [?e :var/name ?v] [?e :var/src ?s]] db)))
        from (srcs db-from) to (srcs db-to)]
    (doseq [[v _] from :when (not (contains? to v))
            :let [n (find-ns (symbol (namespace v)))] :when n]
      (ns-unmap n (symbol (name v))))
    (vec (keep (fn [[v src]] (when (not= src (get from v)) (load-src! (symbol (namespace v)) src)))
               (sort-by (comp str key) to)))))

(defn var-kind
  "What a defined var holds, as recorded: :fn (a function, which needs a contract), :test,
  :macro or :value (G1's)."
  {:malli/schema [:=> [:cat :seon.host/var] [:enum :fn :test :macro :value]]}
  [v]
  (let [m (meta v)]
    (cond (:test m) :test (:macro m) :macro (contract/function-var? v) :fn :else :value)))

(defn record-tx
  "The fact recording a def evaluated in a program namespace: its var, source, kind and
  declared contract (as text), so a merge can refuse an uncontracted function. A
  redefinition without a contract retracts the one recorded before (db: the branch). G1's
  proto.program/record-tx."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/var :any] :seon.db/tx-data]}
  [db the-var form]
  (let [m (meta the-var) nsym (ns-name (:ns m)) vsym (symbol (str nsym) (str (:name m)))
        pr (fn [x] (binding [*print-length* nil *print-level* nil *print-meta* false] (pr-str x)))
        c (contract/contract the-var)
        old (when-not c (d/q '[:find [?e ?c] :in $ ?v :where [?e :var/name ?v] [?e :var/contract ?c]] db vsym))]
    (cond-> [(cond-> {:var/name vsym :var/ns [:ns/name nsym] :var/src (pr form) :var/kind (var-kind the-var)}
               c (assoc :var/contract (pr c)))]
      old (conj [:db/retract (first old) :var/contract (second old)]))))
