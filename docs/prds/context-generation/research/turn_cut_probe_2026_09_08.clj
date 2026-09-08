(ns turn-cut-probe-2026-09-08
  (:require [clojure.core.async :as async]
            [datahike.api :as d]
            [seon.ai :as ai]
            [seon.cluster.agent :as agent]
            [seon.db :as db]
            [seon.operator.runtime :as runtime]
            [seon.schema :as schema]
            [sci.core :as sci]))

(defn shared-install!
  "Probe installed function propagation through two ordinary source turns."
  []
  (let [instance (get @runtime/running-instances "default")
        cluster (:seon.cluster.loop/cluster instance)
        connection (:seon.db/connection cluster)
        routing (:seon.cluster.agent/routing instance)
        suffix (str (random-uuid))
        a (str "turn-cut-a-" suffix)
        b (str "turn-cut-b-" suffix)
        a-ns (symbol (str "my.agents." a))
        b-ns (symbol (str "my.agents." b))
        function-symbol (symbol (str a-ns) "shared-inc")
        provider-calls (atom 0)
        submit
        (fn [agent-id source]
          (let [changed (async/chan (async/sliding-buffer 1))
                listener (keyword (str "turn-cut-" (random-uuid)))
                _ (d/listen connection listener
                             (fn [_] (async/offer! changed true)))]
            (try
              (let [submitted (agent/submit-source!
                               {:seon.cluster.loop/cluster cluster
                                :seon.cluster.agent/routing routing
                                :seon.cluster.agent/id agent-id
                                :seon.cluster.reply/text source})]
                (when (:seon.error/kind submitted)
                  (throw (ex-info "Source submission refused." submitted)))
                (let [deadline (async/timeout 20000)
                      lookup [:seon.cluster.run/id (:seon.cluster.run/id submitted)]]
                  (loop []
                    (when-not (:seon.cluster.run/closed-at
                               (db/pull @connection [:seon.cluster.run/closed-at] lookup))
                      (let [[_ port] (async/alts!! [changed deadline])]
                        (when (= port deadline)
                          (throw (ex-info "Source turn did not close." submitted)))
                        (recur)))))
                (let [completion (:seon.cluster.loop/completion
                                  (agent/armed routing agent-id))
                      [permit port] (async/alts!! [completion (async/timeout 20000)])]
                  (when-not (= port completion)
                    (throw (ex-info "Source turn did not finish its proc pass." submitted)))
                  (async/offer! completion permit))
                (db/pull @connection
                         [:seon.cluster.run/id :seon.cluster.run/closed-at
                          {:seon.cluster.eval/_run
                           [:seon.cluster.eval/ordinal :seon.cluster.eval/result-edn
                            :seon.cluster.eval/error]}]
                         [:seon.cluster.run/id (:seon.cluster.run/id submitted)]))
              (finally
                (d/unlisten connection listener)
                (async/close! changed)))))]
    (schema/call-with-projection-state
     (:seon.sci.eval/projection-state cluster)
     (fn []
       (with-redefs [ai/complete (fn [_]
                                  (swap! provider-calls inc)
                                  {:seon.ai/text "(my.run/complete \"Virtual turn.\")"})]
         (let [created (db/transact!
                        connection
                        [{:seon.cluster.agent/id a
                          :seon.cluster.agent/namespace {:seon.ns/name a-ns}}
                         {:seon.cluster.agent/id b
                          :seon.cluster.agent/namespace {:seon.ns/name b-ns}}])]
           (when (:seon.error/kind created)
             (throw (ex-info "Probe agents could not be created." created)))
           (let [a-result (submit a "(defn shared-inc {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))\n(my.run/complete \"Installed.\")")
                 base-resolves? (boolean (sci/resolve (:seon.sci.eval/ctx cluster)
                                                    function-symbol))
                 b-result (submit b (str "(" function-symbol " 41)\n(my.run/complete \"Called.\")"))]
             {:probe/base-resolves? base-resolves?
              :probe/a a-result
              :probe/b b-result
              :probe/provider-stand-in-calls @provider-calls})))))))
