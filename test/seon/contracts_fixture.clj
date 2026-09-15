(ns seon.contracts-fixture
  (:require [clojure.test :refer [is]]
            [seon.config :as config]
            [seon.context-blocks-fixture :as fixture]
            [seon.db :as db]
            [seon.env :as env]
            [seon.program :as program]
            [seon.render :as render]
            [seon.schema.datahike :as schema.datahike]
            [seon.sci.eval :as sci-eval]
            [seon.test-support :as support]))

(def run5-definition
  "Exact accepted run-5 definition, read from default on 2026-09-15."
  "(defn largest-customer
  {:malli/schema [:=> [:cat [:vector :example/order-row]]
                  [:map [:customer :example/customer] [:total :example/amount]]]}
  [rows]
  (if (seq rows)
    (->> rows
         (group-by :example/customer)
         (map (fn [[c rs]] {:customer c :total (reduce + (map :example/amount rs))}))
         (sort-by :total >)
         first)
    {:customer \"\" :total 0}))")

(def run5-lazy-call
  "Exact failing run-5 call, read from default on 2026-09-15."
  "(let [rows (seon.db/q '[:find ?o ?c ?a
                        :where [?e :example/order ?o] [?e :example/customer ?c] [?e :example/amount ?a]])
      order-rows (map (fn [[o c a]] {:example/order o :example/customer c :example/amount a}) rows)]
  (largest-customer order-rows))")

(defn request [connection ctx source]
  (let [effective (config/defaults)]
    {:seon.sci.eval/ctx ctx :seon.db/db (db/db connection)
     :seon.db/connection connection :seon.agent/id "contracts-plan"
     :seon.cluster.eval/ns [:seon.ns/name 'my.agents.juniper]
     :seon.cluster.eval/source source
     :seon.sci.eval/time-limit-ms 10000
     :seon.sci.admit/caps (config/result-caps effective)
     :seon.render/profile (render/agent-render-profile effective)
     :seon.schema/projection (:seon.schema/projection (env/of ctx))
     :seon.config/on-core-error :panic}))

(defn submit [connection ctx _ source]
  (let [evaluated (sci-eval/evaluate-for-install (request connection ctx source))]
    (when-let [row (:seon.program/row evaluated)]
      (is (:db-after (db/transact! connection [(program/canonical-row row)])))
      (sci-eval/install-evaluated-rows!
       {:seon.sci.eval/ctx ctx :seon.db/db @connection
        :seon.sci.eval/installations
        [{:seon.program/row row :seon.sci.eval/evaluation evaluated}]}))
    [evaluated (:seon.sci.admit/value evaluated)]))

(defn with-grammar-agent [body]
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "contracts-plan")
     (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "contracts-plan"
                    :seon.config/manifest {:seon.config.ai/no-provider true
                                           :seon.config/on-core-error :panic}})
     (db/transact! connection [{:seon.agent/id "contracts-plan"
                               :seon.agent/namespace {:seon.ns/name 'my.agents.juniper}}])
     (let [ctx (support/fork-cluster-ctx connection)]
       (body connection ctx nil)))))

(defn install-orders! [connection ctx]
  (with-open [reader (java.io.PushbackReader. (java.io.StringReader. fixture/schema-source))]
    (loop []
      (let [form (read {:eof ::eof} reader)]
        (when-not (= ::eof form)
          (let [[result] (submit connection ctx nil (pr-str form))]
            (is (nil? (:seon.cluster.eval/error result))
                (:seon.cluster.eval/error result)))
          (recur)))))
  (let [projection (:seon.schema/projection (env/of ctx))]
    (is (:db-after
         (db/transact! connection
                       (schema.datahike/malli->datahike-schema-in
                        projection (schema.datahike/database-attributes-in projection))))))
  (is (:db-after (db/transact! connection fixture/orders))))

(defn with-agent [body]
  (with-grammar-agent
   (fn [connection ctx routing]
     (install-orders! connection ctx)
     (body connection ctx routing))))
