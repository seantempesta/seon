(load-file "test/seon/context_blocks_fixture.clj")

(ns run7-wave-probe-2026-09-15
  (:refer-clojure :exclude [run!])
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [seon.context-blocks-fixture :as fixture]
            [seon.db :as db]
            [seon.eval :as evaluation]
            [seon.schema :as schema]
            [seon.operator.runtime :as runtime]))

(load-file "docs/prds/context-generation/research/help_trial_2026_09_09.clj")

(def sources
  ["(my.plan/complete! {:my.plan.item/id \"juniper/report\"})"
   "(seon.db/q '[:find ?id ?customer ?amount :where [?order :example/order ?id] [?order :example/customer ?customer] [?order :example/amount ?amount]])"
   "(defn largest-customer {:malli/schema [:=> [:cat [:or [:sequential [:tuple :string :string :int]] [:set [:tuple :string :string :int]]]] [:or [:map [:customer :string] [:total :int]] :seon.error/value]]} [rows] (if (seq rows) (->> rows (group-by second) (map (fn [[customer orders]] {:customer customer :total (reduce + (map #(nth % 2) orders))})) (sort-by :total >) first) {:seon.error/kind :orders/empty :seon.error/message \"No orders.\"}))"
   "(deftest totals (is (= {:customer \"Ada\" :total 115} (largest-customer [[\"a1\" \"Ada\" 60] [\"a2\" \"Ada\" 55] [\"b1\" \"Bea\" 100]]))))"
   "(my.test/run)"
   "(let [answer (largest-customer (seon.db/q '[:find ?id ?customer ?amount :where [?order :example/order ?id] [?order :example/customer ?customer] [?order :example/amount ?amount]]))] (my.note/add! {:my.note/id \"run7-original\" :my.note/content (pr-str answer)}) answer)"
   "(seon.db/transact! [{:example/order \"a3\" :example/customer \"Ada\" :example/amount 40}])"
   "(let [answer (largest-customer (seon.db/q '[:find ?id ?customer ?amount :where [?order :example/order ?id] [?order :example/customer ?customer] [?order :example/amount ?amount]]))] (my.note/add! {:my.note/id \"run7-comparison\" :my.note/content (str \"Ada original 115; new \" (:total answer))}) answer)"])

(defn- run-in-cluster!
  "Use a saved trial or run it once, then verify the seven-step live flow."
  [cluster-name trial-path proof-path]
  (when (.exists (io/file proof-path))
    (throw (ex-info "The proof path must be new." {})))
  (let [instance (get @runtime/running-instances cluster-name)
        handle (:seon.turn.loop/cluster instance)
        routing (:seon.agent/routing instance)
        connection (:seon.db/connection handle)
        _ (when-not (and handle routing connection)
            (throw (ex-info "A running cluster and routing are required." {:seon.cluster/name cluster-name})))
        _ (fixture/install-running! handle routing)
        trial (if (.exists (io/file trial-path))
                (edn/read-string (slurp trial-path))
                ((resolve 'help-trial-2026-09-09/run!) cluster-name trial-path))
        submit (fn [source]
                 (let [turn-id (fixture/submit! handle routing source)
                       item (last (evaluation/of-agent @connection "juniper"))]
                   {:seon.turn/id turn-id :seon.cluster.eval/source source
                    :seon.eval/shown (:seon.eval/shown item)
                    :seon.cluster.eval/error (:seon.cluster.eval/error item)}))
        steps (mapv submit sources)
        about (db/q '[:find ?id . :where [?step :my.plan.item/id "juniper/report"]
                       [?step :my.plan.item/subject ?message] [?message :seon.message/id ?id]] @connection)
        report (submit (str "(let [sent (my.message/send "
                            (pr-str {:my.message/to "root" :my.message/about about
                                     :my.message/content "Ada: original total 115; verified new total 155."})
                            ")] :sent)"))
        nested (submit "(let [] (my.agent/done) :discarded)")
        done (submit "(my.agent/done)")
        result {:seon.trial/score (:seon.trial/score trial)
                :seon.probe/evaluations (into steps [report nested done])
                :seon.probe/steps
                (db/q '[:find (pull ?step [:my.plan.item/id :my.plan.item/done-query
                                          {:my.plan.item/completed-tx [:db/id :db/txInstant]}])
                        :where [?step :my.plan.item/done-query _]] @connection)
                :seon.probe/messages
                (db/q '[:find (pull ?message [:seon.message/id :seon.message/content
                                              {:seon.message/about [:seon.message/id]}
                                              {:seon.message/inbox [:seon.agent/id]}])
                        :where [?agent :seon.agent/id "juniper"]
                               [?message :seon.message/from ?agent]] @connection)}]
    (spit proof-path (pr-str result))
    result))

(defn run!
  "Use the cluster's projection; optionally exclude a recorded 402 route."
  ([cluster-name trial-path proof-path]
   (run! cluster-name trial-path proof-path nil))
  ([cluster-name trial-path proof-path refused-trial-path]
   (let [handle (:seon.turn.loop/cluster (get @runtime/running-instances cluster-name))]
     (schema/call-with-projection-state
      (:seon.sci.eval/projection-state handle)
      (fn []
        (when refused-trial-path
          (let [previous (edn/read-string (slurp refused-trial-path))
                status (get-in previous [:seon.trial/completion :seon.error/data :seon.ai/status])
                model (get-in previous [:seon.trial/model :seon.ai.model/id])]
            (when-not (and (= 402 status) (string? model))
              (throw (ex-info "Exclusion requires a recorded HTTP 402 for this model." {})))
            (let [written (db/transact! (:seon.db/connection handle)
                                        [[:db.fn/retractEntity [:seon.ai.model/id model]]])]
              (when (:seon.error/kind written)
                (throw (ex-info (:seon.error/message written) written))))))
        (run-in-cluster! cluster-name trial-path proof-path))))))

(defn rescore!
  "Score saved provider bytes; never call the provider."
  [cluster-name input-path output-path]
  (when (.exists (io/file output-path))
    (throw (ex-info "The scored evidence path must be new." {})))
  (let [saved (edn/read-string (slurp input-path))
        handle (:seon.turn.loop/cluster (get @runtime/running-instances cluster-name))
        scored (schema/call-with-projection-state
                (:seon.sci.eval/projection-state handle)
                #((resolve 'help-trial-2026-09-09/assessment)
                  (db/db (:seon.db/connection handle)) (:seon.sci.eval/ctx handle)
                  (:seon.trial/completion saved) (:seon.trial/turns-left saved)))
        result (merge saved scored {:seon.trial/scored-from input-path})]
    (spit output-path (pr-str result))
    (:seon.trial/score result)))
