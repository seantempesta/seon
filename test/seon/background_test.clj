(ns seon.background-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.cluster.run :as run]
            [seon.cluster.work :as work]
            [seon.db :as db]
            [seon.render.walk :as walk]
            [seon.test-support :as support])
  (:import [java.util Date]))

(deftest terminal-background-results-open-one-result-only-run
  (support/with-database
    (fn [connection]
      (let [now (Date.)]
        (db/transact!
         connection
         [{:seon.cluster.agent/id "background-agent"}
          {:seon.fn/sym "my.example/background-call"}
          {:seon.cluster.run/id "origin-run"}
          {:seon.effect/id "background-effect"
           :seon.effect/run [:seon.cluster.run/id "origin-run"]
           :seon.effect/owner [:seon.fn/sym "my.example/background-call"]
           :seon.effect/form-ordinal 0
           :seon.effect/ordinal 0
           :seon.effect/request-edn "{}"
           :seon.effect/opened-at now
           :seon.effect/result-edn "{:my.example/value 7}"
           :seon.effect/result-size 21
           :seon.effect/duration-ms 3
           :seon.effect/settled-at now
           :seon.effect/to [:seon.cluster.agent/id "background-agent"]}])
        (is (= {:seon.cluster.work/situation :open
                :seon.cluster.agent/id "background-agent"}
               (work/next-agent-work
                @connection
                {:seon.cluster.agent/id "background-agent"
                 :seon.cluster.run/process "process"})))
        (db/transact!
         connection
         (run/open-tx
          {:seon.cluster.run/id "result-run"
           :seon.cluster.run/agent
           [:seon.cluster.agent/id "background-agent"]
           :seon.cluster.run/opened-at now}))
        (let [opened
              (db/pull
               @connection
               [{:seon.cluster.run/background-results
                 [:seon.effect/id]}]
               [:seon.cluster.run/id "result-run"])]
          (is (= #{"background-effect"}
                 (into #{}
                       (map :seon.effect/id)
                       (:seon.cluster.run/background-results opened))))
          (is (nil? (:seon.cluster.run/trigger opened))))))))

(deftest background-pane-is-behind-the-stable-prefix-boundary
  (support/with-database
    (fn [connection]
      (db/transact!
       connection
       [{:seon.cluster.agent/id "suffix-agent"}
        {:seon.cluster.run/id "suffix-run"}
        {:seon.fn/sym "my.example/background-call"}
        {:seon.effect/id "suffix-effect"
         :seon.effect/run [:seon.cluster.run/id "suffix-run"]
         :seon.effect/owner [:seon.fn/sym "my.example/background-call"]
         :seon.effect/form-ordinal 0
         :seon.effect/ordinal 0
         :seon.effect/request-edn "{}"
         :seon.effect/opened-at (Date.)
         :seon.effect/notify [:seon.cluster.agent/id "suffix-agent"]}])
      (let [text
            (walk/prose
             @connection
             [{:seon.render.walk/lookup
               [:seon.cluster.agent/id "suffix-agent"]
               :seon.render.walk/path []
               :seon.render.walk/found-depth 0
               :seon.render/distance 1
               :seon.render/output "stable agent context"}])
            marker (str/index-of text ";; Volatile context metadata")
            pane (str/index-of text ";; Background work:")]
        (is (number? marker))
        (is (number? pane))
        (is (< marker pane))))))
