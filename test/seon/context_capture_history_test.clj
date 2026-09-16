(ns seon.context-capture-history-test
  (:require [clojure.test :refer [deftest is]]
            [seon.cluster.agent :as agent]
            [seon.context :as context]
            [seon.db :as db]
            [seon.test-support :as support]
            [seon.turn :as turn]))

(deftest retracted-capture-retains-the-exact-prompt-in-history
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "capture-history")
     (support/transacted!
      connection
      (agent/creation-tx {:seon.agent/id "capture-history"
                          :seon.ns/name 'my.agents.capture-history
                          :seon.cluster/name "capture-history"}))
     (support/transacted!
      connection
      (turn/open-tx {:seon.turn/id "capture-history-turn"
                     :seon.turn/agent [:seon.agent/id "capture-history"]
                     :seon.turn/opened-tx "datomic.tx"}))
     (let [prompt "my.agents.capture-history=> (+ 1 2)\n3\n"
           transaction (context/capture-tx
                        {:seon.turn/id "capture-history-turn"
                         :seon.cluster.prompt/rendered-context
                         {:seon.db/db (db/db connection)
                          :seon.cluster.prompt/text prompt
                          :seon.context/contributions []}})
           capture-id (:seon.context.capture/id (first transaction))
           captured (:db-after (support/transacted! connection transaction))
           capture-ref [:seon.context.capture/id capture-id]
           selected (db/pull captured
                             [:db/id :seon.context.capture/prompt
                              :seon.ai.tokens/characters]
                             capture-ref)
           capture-eid (:db/id selected)
           captured-t (db/basis-t captured)]
       (is (integer? capture-eid))
       (is (= prompt (:seon.context.capture/prompt selected)))
       (is (= (count prompt) (:seon.ai.tokens/characters selected)))
       (support/transacted! connection [[:db/retractEntity capture-ref]])
       (let [after (db/db connection)]
         (is (nil? (db/q '[:find ?e . :in $ ?id
                           :where [?e :seon.context.capture/id ?id]]
                         after capture-id)))
         (is (= prompt
                (:seon.context.capture/prompt
                 (db/pull (db/as-of after captured-t)
                          [:seon.context.capture/prompt] capture-ref)))
             "as-of reconstructs the exact recorded prompt after retraction")
         (is (= #{[prompt true] [prompt false]}
                (set (db/q '[:find ?text ?added :in $ ?e
                             :where [?e :seon.context.capture/prompt ?text _ ?added]]
                           (db/history after) capture-eid)))
             "history records both the observed prompt and its retraction"))))))
