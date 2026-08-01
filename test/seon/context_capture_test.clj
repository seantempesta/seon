(ns seon.context-capture-test
  "Projection boundaries for durable context-capture evidence."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.context :as context]
            [seon.render.hiccup :as hiccup]
            [seon.render.walk :as walk]
            [seon.test-support :as support]))

(def ^:private sentinel
  "PRIOR-PROMPT-SENTINEL must remain inspectable without becoming context.")

(def ^:private caps
  {:seon.config.eval.result/max-depth 8
   :seon.config.eval.result/max-collection 64
   :seon.config.eval.result/max-string 4096
   :seon.config.eval.result/max-nodes 4096})

(defn- capture-neighbourhood
  [db kind]
  (walk/neighborhood
   {:seon.db/db db
    :seon.render.walk/lookup [:seon.cluster.run/id "capture-run"]
    :seon.render/kind kind
    :seon.render/floor (if (= kind :seon.render/html)
                         'seon.render.block/data-panel
                         'seon.render.block/data-prose)
    :seon.sci.admit/caps caps
    :seon.render/distance 1}))

(defn- node-by-projection
  [root projection]
  (some #(when (= projection (:seon.render/projection %)) %)
        (tree-seq #(seq (:seon.render.walk/neighbours %))
                  :seon.render.walk/neighbours
                  root)))

(deftest captures-are-html-evidence-and-never-ai-context
  (support/with-database
    (fn [connection]
      (d/transact connection [{:seon.cluster.run/id "capture-run"}])
      (d/transact
       connection
       [{:seon.context.capture/id "capture-1"
         :seon.context.capture/run [:seon.cluster.run/id "capture-run"]
         :seon.context.capture/basis-t 42
         :seon.context.capture/prompt sentinel
         :seon.context.capture/contributions
         [{:seon.context.contribution/id "capture-1-0"
           :seon.context.contribution/position 0
           :seon.render.block/name :walk
           :seon.context.contribution/hash "capture-hash"
           :seon.context.contribution/tokens 12}]}])
      (let [db @connection
            ai-node (capture-neighbourhood db :seon.render/ai)
            ai-capture (node-by-projection ai-node
                                           'seon.context/capture-ai)
            ai-context (walk/prose db ai-node)
            html-node (capture-neighbourhood db :seon.render/html)
            capture-unit
            (some (fn [unit]
                    (when (= 'seon.context/capture-html
                             (get-in unit [:seon.render.walk/node
                                           :seon.render/projection]))
                      unit))
                  (walk/units html-node))
            html (some-> capture-unit :seon.render/output hiccup/->string)]
        (testing "the total walk still visits the capture on both projections"
          (is (some? ai-capture))
          (is (some? capture-unit)))
        (testing "the AI renderer nil-puns the prior prompt away"
          (is (nil? (:seon.render/output ai-capture)))
          (is (not (str/includes? ai-context sentinel))))
        (testing "the HTML renderer keeps the exact evidence behind a disclosure"
          (is (str/includes? html "<details"))
          (is (str/includes? html "Context capture at database basis 42"))
          (is (str/includes? html sentinel)))))))

(deftest one-walk-capture-transacts-without-a-legacy-band
  (support/with-database
    (fn [connection]
      (d/transact connection [{:seon.cluster.run/id "one-walk-run"}])
      (let [db @connection
            rendered
            {:seon.cluster.prompt/text "one fresh walk"
             :seon.context/contributions
             [{:seon.render.block/name :walk
               :seon.render/kind :seon.render/ai
               :seon.context.contribution/position 0
               :seon.context.contribution/text "one fresh walk"
               :seon.context.contribution/hash "one-walk-hash"
               :seon.context.contribution/tokens 3
               :seon.render/projection 'seon.render/walk}]
             :seon.db/db db}
            tx-data
            (context/capture-tx
             {:seon.cluster.run/id "one-walk-run"
              :seon.cluster.prompt/rendered-context rendered})]
        (d/transact connection tx-data)
        (let [capture
              (d/pull @connection
                      [{:seon.context.capture/contributions
                        '[*]}]
                      [:seon.context.capture/id
                       (str "one-walk-run-context-" (:max-tx db))])]
          (is (= 1 (count (:seon.context.capture/contributions capture))))
          (is (not (contains?
                    (first (:seon.context.capture/contributions capture))
                    :seon.context.contribution/band))))))))
