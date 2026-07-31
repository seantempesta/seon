(ns seon.cluster.prompt-test
  "Recurring acceptance for one fresh walk per prompt."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.cluster.agent :as agent]
            [seon.cluster.prompt :as prompt]
            [seon.test-support :as support])
  (:import [java.util Date]))

(def ^:private caps
  {:seon.config.eval.result/max-depth 12
   :seon.config.eval.result/max-collection 64
   :seon.config.eval.result/max-string 4096
   :seon.config.eval.result/max-nodes 4096})

(defn- planted
  [body]
  (support/with-database
    (fn [connection]
      (support/seed-cluster! connection "prompt-walk")
      (d/transact connection
                  (agent/creation-tx
                   {:seon.cluster.agent/id "walker"
                    :seon.cluster/name "prompt-walk"
                    :seon.ns/name 'my.agents.walker}))
      (d/transact connection
                  [{:seon.cluster.message/id "walk-message"
                    :seon.cluster.message/to
                    [:seon.cluster.agent/id "walker"]
                    :seon.cluster.message/content "inspect this walk"
                    :seon.cluster.message/at (Date. 1700000000000)}])
      (d/transact connection
                  {:tx-data
                   [{:seon.cluster.run/id "walk-run"
                     :seon.cluster.run/agent
                     [:seon.cluster.agent/id "walker"]
                     :seon.cluster.run/opened-at (Date. 1700000001000)}
                    {:seon.cluster.agent/id "walker"
                     :seon.cluster.agent/run
                     [:seon.cluster.run/id "walk-run"]}]
                   :tx-meta
                   {:seon.db/trigger
                    [:seon.cluster.message/id "walk-message"]}})
      (body connection))))

(def ^:private request
  {:seon.cluster.run/id "walk-run"
   :seon.cluster.agent/id "walker"
   :seon.sci.admit/caps caps})

(deftest prompt-is-one-fresh-labeled-walk
  (planted
   (fn [connection]
     (let [rendered (prompt/prompt @connection request)
           text (:seon.cluster.prompt/text rendered)
           contribution (first (:seon.context/contributions rendered))
           lines (str/split-lines text)]
       (is (= text (:seon.context.contribution/text contribution)))
       (is (= :walk (:seon.render.block/name contribution)))
       (is (= 1 (count (re-seq #";; \(seon\.render/walk" text)))
           "assembly opens exactly one walk")
       (is (re-find #";; path=\[.*depth=\d+ provenance=" text))
       (is (str/includes? text "inspect this walk")
           "the transcript is a branch inside the walk")
       (is (str/starts-with? (last lines) ";; REPL state namespace="))
       (is (str/includes? (last lines) "my.agents.walker"))
       (is (empty? (d/q '[:find ?block
                          :where
                          [?agent :seon.cluster.agent/id "walker"]
                          [?agent :seon.cluster.agent/blocks ?block]]
                        @connection))
           "creation stores no presentation blocks")))))

(deftest every-call-derives-the-current-basis
  (planted
   (fn [connection]
     (let [before (:seon.cluster.prompt/text
                   (prompt/prompt @connection request))]
       (d/transact connection
                   [{:seon.cluster.message/id "later"
                     :seon.cluster.message/to
                     [:seon.cluster.agent/id "walker"]
                     :seon.cluster.message/content "new durable fact"
                     :seon.cluster.message/at (Date. 1700000002000)}])
       (let [after (:seon.cluster.prompt/text
                    (prompt/prompt @connection request))]
         (is (not= before after))
         (is (str/includes? after "new durable fact")))))))

(deftest a-held-run-without-a-trigger-refuses
  (support/with-database
    (fn [connection]
      (support/seed-cluster! connection "no-trigger")
      (d/transact connection
                  (agent/creation-tx
                   {:seon.cluster.agent/id "walker"
                    :seon.cluster/name "no-trigger"
                    :seon.ns/name 'my.agents.walker}))
      (d/transact connection
                  [{:seon.cluster.run/id "walk-run"
                    :seon.cluster.run/agent
                    [:seon.cluster.agent/id "walker"]
                    :seon.cluster.run/opened-at (Date.)}])
      (testing "the custody invariant remains independent of presentation"
        (is (= :seon.cluster.prompt/no-trigger
               (:seon.cluster.prompt/rule
                (support/refusal-data
                 #(prompt/prompt @connection request)))))))))
