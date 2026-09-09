(ns seon.render.episode-test
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.eval :as evaluation]
            [seon.render.walk :as walk]
            [seon.test-support :as support]))

(deftest saved-shown-text-is-a-settled-evaluation-without-a-print-node
  (support/with-database
   (fn [connection]
     (is (not (:seon.error/kind
               (db/transact!
                connection
                [{:seon.agent/id "episode-agent"}
                 {:seon.turn/id "episode-turn"
                  :seon.turn/agent [:seon.agent/id "episode-agent"]
                  :seon.turn/opened-at (java.util.Date. 0)}
                 {:seon.cluster.eval/id "episode-evaluation"
                  :seon.cluster.eval/run [:seon.turn/id "episode-turn"]
                  :seon.cluster.eval/ordinal 0
                  :seon.cluster.eval/source "(help)"
                  :seon.eval/value "#object[clojure.lang.Atom 0x1 {:status :ready}]"
                  :seon.cluster.eval/output "observed\n"}]))))
     (let [stored (first (evaluation/of-agent @connection "episode-agent"))
           candidate {:seon.repl/key :root
                      :seon.repl/subject 'my.agents.juniper
                      :seon.repl/entry {:seon.repl/form '(help)}}
           request {:seon.repl/root-key :root
                    :seon.repl/candidates [candidate]
                    :seon.repl/settled [(assoc stored :seon.repl/key :root)]
                    :seon.print/identity-attributes #{:seon.ns/name}}
           expected [(assoc (:seon.repl/entry candidate)
                            :seon.repl/key :root
                            :seon.repl/subject 'my.agents.juniper)]]
       (is (= "observed\n" (:seon.cluster.eval/output stored)))
       (is (= expected (walk/ordered-episode request)))
       (is (= expected (walk/ordered-episode
                       (assoc request :seon.repl/settled
                              [{:seon.repl/key :root
                                :seon.cluster.eval/error "Evaluation interrupted"}]))))
       (is (= expected (walk/ordered-episode
                       (assoc request :seon.repl/settled
                              [{:seon.repl/key :root
                                :seon.sci.admit/print-node "shown text"}]))))))))
