(ns seon.program-cost-test
  "Recording what an agent evaluates costs the form, never the program (next-review #6):
  an `ns` form's record is built from the form alone, whatever the branch already holds."
  (:refer-clojure :exclude [defn defn-])
  (:require [clojure.test :refer [deftest]] [seon.contract :refer [defn defn-]]
            [datahike.api :as d] [seon.db :as pdb] [seon.cost :as cost] [seon.program]))

(def ^:private ns-src "(ns app.pc-new\n  (:require [clojure.string :as s :refer [join]]))")

(defn program-of
  "recording's arguments for one `ns` form on a branch whose db holds n definitions."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :any :any :string :any]]}
  [n]
  (let [cfg {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write :keep-history? false}
        _ (d/create-database cfg)
        db (d/db-with (d/db-with @(d/connect cfg) pdb/schema)
                      (into [{:db/id "ns" :ns/name 'app.pc}]
                            (for [i (range n)] {:var/name (symbol "app.pc" (str "f" i)) :var/ns "ns" :var/src (str "(defn f" i " [x] x)")})))
        form (read-string ns-src)]
    (binding [*ns* (create-ns 'user)] (eval form))
    (in-ns 'seon.program-cost-test)
    [db form ns-src nil]))

(deftest recording-an-ns-form-costs-the-form-not-the-program
  (cost/proof
    {:of 'seon.program/recording :input 'seon.program-cost-test/program-of
     :n "definitions the branch's db already holds"
     :work (str "the form only: is it an ns form, find its namespace, read the name's metadata, is the "
                "namespace scratch, and build one entity map (tempid string, name, text) in a vector and "
                "the [name tx] pair. No query: nothing of the db is read")
     :growth :constant
     ;; the entity map, its vector and the answer's pair; the tempid string (~20 characters);
     ;; ten plain calls (the predicates and accessors); five contracted calls, armed as on an
     ;; agent (recording, ns-form?, scratch-ns?, record-ns-tx, tid), :fn/armed-call each
     :fixed {:ns (+ 144 (* 2 31) (* 20 30) (* 10 7.4) (* 5 61))
             :bytes (+ 144 (* 2 246) (* 20 36) (* 5 990))
             :calls 5}
     :sizes [100 1000 10000]}))
