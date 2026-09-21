(ns seon.turn-work-cost-test
  (:require [clojure.test :refer [deftest is]]
            [seon.ai :as ai]
            [seon.db :as db]
            [seon.turn :as turn]
            [seon.turn-work-test :as work]))

(deftest generated-state-agrees-with-the-writer
  (#'work/with-database
   (fn [connection]
     (let [started (System/nanoTime)
           _ (#'work/configure-agent-bound! connection 3)
           prepared (System/nanoTime)
           database (db/db connection)
           generated (#'work/generated-database
                      database {::work/planned? true ::work/triggered? true
                                ::work/trigger-first? true ::work/closed? true
                                ::work/receipts [0 1]})
           built (System/nanoTime)
           request {:seon.agent/id "agent-a" :seon.db.process/id "process/one"}
           result (turn/next-agent-work generated request)
           read-at (System/nanoTime)]
       (println "Turn property immutable case milliseconds"
                {:seon.test/preparation (/ (double (- prepared started)) 1e6)
                 :seon.test/state-transactions (/ (double (- built prepared)) 1e6)
                 :seon.test/derivation (/ (double (- read-at built)) 1e6)})
       (is (= (db/basis-t database) (db/basis-t (db/db connection)))
           "An immutable trial cannot advance its prepared ancestor.")
       (is (identical? (db/carried-projection database)
                       (db/carried-projection generated)))
       (is (= 3 (with-redefs [ai/agent-overlay
                             (fn [& _] (throw (ex-info "Read unrelated agent settings." {})))]
                  (#'turn/max-episode-runs database "agent-a"))))
       (#'work/add-trigger! connection)
       (#'work/open-run! connection {:planned? true :triggered? true})
       (#'work/terminal-receipt! connection 0)
       (#'work/terminal-receipt! connection 1)
       (#'work/close-run! connection)
       (is (= result (turn/next-agent-work (db/db connection) request)))
       (is (= :open (:seon.turn.work/situation result)))))))
