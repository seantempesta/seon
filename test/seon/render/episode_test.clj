(ns seon.render.episode-test
  (:require [clojure.test :refer [deftest is]]
            [seon.eval :as evaluation]
            [seon.render.walk :as walk]
            [seon.turn :as turn]
            [seon.test-support :as support]))

(deftest saved-shown-text-is-a-settled-evaluation-without-a-print-node
  (support/with-database
   (fn [connection]
     ;; Only seon.turn/open-tx writes the agent's runtime turn edge; a
     ;; hand-authored turn map is refused, and the refusal used to read here
     ;; as an agent with no stored evaluation.
     (support/transacted! connection (support/agent-tx @connection "episode-agent"))
     (support/transacted!
      connection
      (turn/open-tx {:seon.turn/id "episode-turn"
                     :seon.turn/agent [:seon.agent/id "episode-agent"]
                     :seon.turn/opened-tx "datomic.tx"
                     :seon.turn.work/situation :call}))
     (support/transacted!
      connection
      [{:seon.cluster.eval/id "episode-evaluation"
        :seon.cluster.eval/run [:seon.turn/id "episode-turn"]
        :seon.cluster.eval/ordinal 0
        :seon.cluster.eval/at (java.util.Date. 1786500000000)
        :seon.cluster.eval/source "(help)"
        :seon.eval/shown "#object[clojure.lang.Atom 0x1 {:status :ready}]"
        :seon.cluster.eval/output "observed\n"}])
     (let [stored (first (evaluation/of-agent @connection "episode-agent"))
           candidate {:seon.repl/key :root
                      :seon.repl/subject [:seon.ns/name 'my.agents.juniper]
                      :seon.repl/entry {:seon.repl/form '(help)}}
           request {:seon.repl/root-key :root
                    :seon.repl/candidates [candidate]
                    :seon.repl/settled [(assoc stored :seon.repl/key :root)]
                    :seon.print/identity-attributes #{:seon.ns/name}}
           expected [(assoc (:seon.repl/entry candidate)
                            :seon.repl/key :root
                            :seon.repl/subject [:seon.ns/name 'my.agents.juniper])]]
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
