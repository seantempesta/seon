(ns seon.stage
  "Stage the LIVE runtime onto the branch (owner, 2026-10-20: REPL semantics, no replay;
  git add -A of the runtime). Each form an agent evaluates is recorded on its branch as it
  runs (seon.repl/wrap-record: the compiler's account of it), so what is left for staging
  is what only the live runtime knows: a var unmapped from a program namespace, and a
  multimethod's method removed. Their definitions stop pointing to a form, so the forms
  they came from leave the program when nothing else of theirs is live (an unmap merges as
  a delete). Nothing the agent evaluated is ever evaluated again. Scratch namespaces, the
  eval history and the agent's turn facts never become program."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [datahike.api :as d] [seon.agent :as agent]))

(defn- program-nss
  "The program namespaces of db (those with an :ns/name) loaded in this runtime."
  {:malli/schema [:=> [:cat :seon.host/db] [:sequential :symbol]]}
  [db]
  (sort (filter find-ns (d/q '[:find [?n ...] :where [_ :ns/name ?n]] db))))

(defn uncontracted
  "The functions of the program namespaces that declare no contract (seon.contract/uncontracted;
  main's own gaps included, callers subtract what was there before the attempt)."
  {:malli/schema [:=> [:cat :seon.host/db] [:set :qualified-symbol]]}
  [db] (set (contract/uncontracted (program-nss db))))

(defn stage-tx
  "tx-data making branch db's definitions match this live runtime: each var recorded with a
  form in a loaded namespace and no longer interned there, and each recorded method its
  multimethod no longer has, stops pointing to its form."
  {:malli/schema [:=> [:cat :seon.host/db] :seon.db/tx-data]}
  [db]
  (vec (concat
         (for [dt (d/datoms db :aevt :var/form)
               :let [sym (:var/name (d/entity db (:e dt))) n (find-ns (symbol (namespace sym)))]
               :when (and n (not (get (ns-interns n) (symbol (name sym)))))]
           [:db/retract (:e dt) :var/form (:v dt)])
         (for [dt (d/datoms db :aevt :method/form)
               :let [m (d/entity db (:e dt)) v (find-var (:method/multi m))]
               :when (and v (instance? clojure.lang.MultiFn @v)
                          (not (contains? (methods @v) (read-string (:method/dispatch m)))))]
           [:db/retract (:e dt) :method/form (:v dt)]))))

(defn stage!
  "Stage the live runtime onto this agent's branch (one transaction). Answers the tx-data
  written."
  {:malli/schema [:=> [:cat] :seon.db/tx-data]}
  []
  (let [tx (stage-tx (agent/db))]
    (when (seq tx) (agent/transact! tx))
    tx))
