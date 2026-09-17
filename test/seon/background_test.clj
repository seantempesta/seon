(ns seon.background-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.turn :as turn]

            [seon.db :as db]
            [seon.config :as config]
            [seon.render.walk :as walk]
            [seon.test-support :as support])
  (:import [java.util Date]))

(deftest terminal-background-results-open-one-result-only-run
  (support/with-database
    (fn [connection]
      (config/apply! {:seon.boot/cluster-name "default" :seon.db/connection connection})
      (let [now (Date.)]
        (support/transacted!
                connection
                [{:seon.agent/id "background-agent"}
                 {:seon.turn/id "origin-run" :seon.turn/agent [:seon.agent/id "background-agent"] :seon.turn/opened-tx "datomic.tx" :seon.turn/closed-tx "datomic.tx"}
                 {:seon.effect/id "background-effect"
                  :seon.effect/run [:seon.turn/id "origin-run"]
                  :seon.effect/owner [:seon.fn/sym "clojure.core/identity"]
                  :seon.effect/form-ordinal 0
                  :seon.effect/ordinal 0
                  :seon.effect/request-edn "{}"
                  :seon.effect/opened-at now
                  :seon.effect/result-edn "{:my.example/value 7}"
                  :seon.effect/result-size 21
                  :seon.effect/duration-ms 3
                  :seon.effect/settled-at now
                  :seon.effect/to [:seon.agent/id "background-agent"]}])
        (is (= {:seon.turn.work/situation :open
                :seon.agent/id "background-agent"}
               (turn/next-agent-work
                @connection
                {:seon.agent/id "background-agent"
                 :seon.db.process/id "process"})))
        (support/transacted!
                connection
                (turn/open-tx
                 {:seon.turn/id "result-run" :seon.turn/agent [:seon.agent/id "background-agent"] :seon.turn/opened-tx "datomic.tx"}))
        (let [opened
              (db/pull
               @connection
               [:seon.turn/trigger]
               [:seon.turn/id "result-run"])]
          (is (= #{"background-effect"}
                 (into #{}
                       (map :seon.effect/id)
                       (:seon.effect/_to (db/pull @connection '[{:seon.effect/_to [:seon.effect/id]}] [:seon.agent/id "background-agent"])))))
          (is (nil? (:seon.turn/trigger opened))))))))
