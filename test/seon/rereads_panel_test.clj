(ns seon.rereads-panel-test
  (:require [clojure.test :refer [deftest is]]
            [sci.core :as sci]
            [seon.cluster.agent :as agent]
            [seon.context-blocks-fixture :as fixture]
            [seon.db :as db]
            [seon.eval :as evaluation]
            [seon.render.transcript :as transcript]
            [seon.repl :as repl]
            [seon.rereads-test :as rereads]
            [seon.turn :as turn]))

(defn- stale-check [connection handle]
  (let [rendered (transcript/render-ledger
                  (merge handle {:seon.db/db @connection :seon.agent/id "juniper"
                                 :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms handle)}))]
    (filterv #(and (vector? %) (= "stale-but-unchanged" (:data-problem (second %))))
             (tree-seq coll? seq rendered))))

(defn- observed-bases [connection]
  (into {} (map (juxt :db/id :seon.cluster.eval/read-basis-transaction))
        (evaluation/of-agent @connection "juniper")))

(defn- refreshed-ids [before after]
  (filterv #(not= (get before %) (get after %)) (keys before)))

(deftest problems-count-every-silent-refresh-from-history
  (#'rereads/with-rereads
    (fn [connection handle routing request]
      (let [minimum "(seon.db/q '[:find (min ?amount) . :where [?e :example/amount ?amount]])"
            maximum "(seon.db/q '[:find (max ?amount) . :where [?e :example/amount ?amount]])"]
        (fixture/submit! handle routing (str minimum "\n" maximum))
        (agent/disarm! {:seon.agent/routing routing :seon.agent/id "juniper"})
        (turn/system-turn request)
        (let [initial (stale-check connection handle)
              initial-count (:data-problem-count (second (first initial)))
              expected-count (atom initial-count)]
          (is (= 1 (count initial)) "absence of the check must fail")
          (is (nat-int? initial-count))
          (dotimes [ordinal 2]
            (let [before (observed-bases connection)]
              (db/transact! connection [[:db/add [:example/order "a1"] :example/amount (+ 61 ordinal)]])
              (is (nil? (:seon.turn/id (turn/system-turn request))))
              (let [after (observed-bases connection)
                    refreshed (set (refreshed-ids before after))
                    sources (set (keep #(when (refreshed (:db/id %)) (:seon.cluster.eval/source %))
                                       (evaluation/of-agent @connection "juniper")))]
                (is (= (set (keys before)) (set (keys after))))
                (is (every? sources [minimum maximum]))
                (swap! expected-count + (count refreshed))
                (println {:seon.test/stale-but-unchanged (count refreshed)
                          :seon.test/sources sources})))
            (let [check (stale-check connection handle)]
              (is (= 1 (count check)))
              (is (= @expected-count
                     (:data-problem-count (second (first check)))))
              (is (boolean (some #{(str "stale-but-unchanged reads · " @expected-count)}
                                 (tree-seq coll? seq check))))))
          ;; The earlier min read is silent while the later max read changes.
          ;; Only emitted evaluations consume ordinals or result identities.
          (let [before (observed-bases connection)
                _ (db/transact! connection [[:db/add [:example/order "b1"] :example/amount 101]])
                result (turn/system-turn request)
                turn-eid (:db/id (db/pull @connection [:db/id] [:seon.turn/id (:seon.turn/id result)]))
                added (filter #(= turn-eid (get-in % [:seon.cluster.eval/run :db/id]))
                              (evaluation/of-agent @connection "juniper"))]
            (is (string? (:seon.turn/id result)))
            (is (= [maximum] (mapv :seon.cluster.eval/source added)))
            (is (= [0] (mapv :seon.cluster.eval/ordinal added)))
            (is (= 101 @(sci/resolve (agent/acquire-context! handle "juniper")
                                     (:seon.repl/handle (repl/entity-emission (first added))))))
            (is (= (+ @expected-count (count (refreshed-ids before (observed-bases connection))))
                   (:data-problem-count (second (first (stale-check connection handle))))))
            (is (nil? (:seon.turn/id (turn/system-turn request))))))))))
