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
  (:require [datahike.api :as d]))

(def ^:private def-heads '#{def defn defn- defmacro defonce deftest defmulti defrecord deftype defprotocol})

(defn def-form?
  "A top-level form that defines a var: (defn f ...), (deftest t ...), ..."
  [form]
  (and (seq? form) (symbol? (first form)) (contains? def-heads (symbol (name (first form))))))

(defn program-ns?
  "Is ns-sym a program namespace in db?"
  [db ns-sym]
  (some? (d/q '[:find ?e . :in $ ?n :where [?e :ns/name ?n]] db ns-sym)))

(defn ensure-ns!
  "The namespace, created with clojure.core referred when it is new."
  [ns-sym]
  (or (find-ns ns-sym)
      (let [n (create-ns ns-sym)]
        (binding [*ns* n]
          (refer-clojure)
          (refer 'clojure.test :only '[deftest is are testing]))
        n)))

(defn load-src!
  "Evaluate one var's source in its namespace; answers nil, or an error map."
  [ns-sym src]
  (try (binding [*ns* (ensure-ns! ns-sym)] (eval (read-string src)) nil)
       (catch Throwable e {:ns ns-sym :message (ex-message e)})))

(defn load-all!
  "Load every :var/src in db, namespace by namespace. Answers {:loaded n :errors [...]}."
  [db]
  (let [rows (d/q '[:find ?v ?src :where [?e :var/name ?v] [?e :var/src ?src]] db)
        errs (vec (keep (fn [[v src]] (load-src! (symbol (namespace v)) src)) (sort-by (comp str first) rows)))]
    {:loaded (count rows) :errors errs}))

(defn load-changes!
  "Load the :var/src that resolved tx-data ops assert, read against db (after them), and
  unmap every var whose :var/name they retract (a resumed branch that predates a var).
  Answers the error maps (empty when all loaded)."
  [db ops]
  (doseq [[op _ a v] ops
          :when (and (= op :db/retract) (= a :var/name) (symbol? v))
          :let [n (some-> (namespace v) symbol find-ns)]
          :when n]
    (ns-unmap n (symbol (name v))))
  (vec (for [[op e a src] ops
             :when (and (= op :db/add) (= a :var/src))
             :let [v (d/q '[:find ?v . :in $ ?e :where [?e :var/name ?v]] db e)]
             :when v
             :let [err (load-src! (symbol (namespace v)) src)]
             :when err]
         err)))

(defn record-tx
  "The fact recording a def evaluated in a program namespace: its var and source."
  [the-var form]
  (let [m (meta the-var) nsym (ns-name (:ns m)) vsym (symbol (str nsym) (str (:name m)))]
    [{:var/name vsym :var/ns [:ns/name nsym]
      :var/src (binding [*print-length* nil *print-level* nil *print-meta* false] (pr-str form))}]))
