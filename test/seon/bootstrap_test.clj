(ns seon.bootstrap-test
  "The live-fact opening generator and its agent-creation seam."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.bootstrap :as bootstrap]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as cluster.agent]
            [seon.cluster.work :as work]
            [seon.config :as config]
            [seon.db :as db]
            [seon.render.walk :as walk]
            [seon.sci.admit :as admit]
            [seon.test-support :as support]))

(def ^:private agent-id "bootstrap-agent")
(def ^:private namespace-name 'my.agents.bootstrap-agent)

(defn- seed-cluster! [connection cluster-name]
  (support/seed-cluster! connection cluster-name)
  (cluster/ensure-cluster-entity!
   connection cluster-name cluster/boot-process-identity))

(defn- generator-request [connection]
  {:seon.db/db @connection
   :seon.db/connection connection
   :seon.sci.eval/ctx (support/fork-cluster-ctx connection)
   :seon.render.walk/lookup [:seon.cluster.agent/id agent-id]
   :seon.sci.admit/caps (config/result-caps (config/defaults))
   :seon.sci.eval/time-limit-ms 5000
   :seon.config/on-core-error :record
   :seon.render/distance 3})

(deftest creation-opens-one-generated-system-run-with-a-real-task
  (support/with-database
    (fn [connection]
      (seed-cluster! connection "generated-bootstrap")
      (let [request {:seon.cluster.agent/id agent-id
                     :seon.cluster/name "generated-bootstrap"
                     :seon.ns/name namespace-name}
            process cluster/boot-process-identity
            result (cluster/ensure-entity! connection process request)
            run-id (bootstrap/run-id agent-id)
            run (db/pull @connection
                         '[:seon.cluster.run/id :seon.cluster.run/process
                           :seon.cluster.run/plan-digest
                           {:seon.cluster.run/forms
                            [:seon.cluster.run.form/ordinal
                             :seon.cluster.run.form/author
                             :seon.cluster.run.form/source]}
                           {:seon.cluster.run/trigger
                            [:seon.cluster.message/id
                             :seon.cluster.message/content]}]
                         [:seon.cluster.run/id run-id])]
        (is (= run-id (:seon.cluster.run/id result)))
        (is (= process (:seon.cluster.run/process run)))
        (is (nil? (:seon.cluster.run/plan-digest run))
            "a generated run has no frozen authored plan")
        (is (= [{:seon.cluster.run.form/ordinal 0
                 :seon.cluster.run.form/author :system
                 :seon.cluster.run.form/source
                 (str "; A new run just opened. Why am I awake — do I have messages?\n"
                      "(help)")}]
               (mapv #(dissoc % :db/id) (:seon.cluster.run/forms run))))
        (is (= (bootstrap/task-message-id agent-id)
               (get-in run [:seon.cluster.run/trigger
                            :seon.cluster.message/id])))
        (is (= (bootstrap/task-message)
               (get-in run [:seon.cluster.run/trigger
                            :seon.cluster.message/content])))
        (is (= {:seon.cluster.work/situation :resume
                :seon.cluster.run/id run-id
                :seon.cluster.agent/id agent-id
                :seon.cluster.run.form/ordinal 0}
               (work/next-agent-work
                @connection {:seon.cluster.agent/id agent-id
                             :seon.cluster.run/process process})))))))

(deftest drive-free-generation-is-pure-deterministic-and-pull-gated
  (support/with-database
    (fn [connection]
      (seed-cluster! connection "generated-proof")
      (cluster/ensure-entity!
       connection cluster/boot-process-identity
       {:seon.cluster.agent/id agent-id
        :seon.cluster/name "generated-proof"
        :seon.ns/name namespace-name})
      (let [pull (bootstrap/pull-result (generator-request connection))
            first-pass (walk/ordered-episode
                        (assoc pull :seon.repl/settled []))
            second-pass (walk/ordered-episode
                         (assoc pull :seon.repl/settled []))]
        (is (= first-pass second-pass)
            "same agent state derives byte-identical episode data")
        (is (= '(help) (:seon.repl/form (first first-pass))))
        (is (= 1 (count first-pass))
            "without the first real receipt no later form can be emitted")
        (is (not-any? #(= 'outside.pull (:seon.repl/subject %))
                      (:seon.repl/candidates pull))
            "membership comes only from the bounded pull")
        (let [opening-source (bootstrap/entry-source (first first-pass))
              situation (bootstrap/situation @connection agent-id)
              node (:seon.sci.admit/print-node
                    (admit/admit-value
                     {:seon.sci.admit/value situation
                      :seon.sci.admit/interrupt-fn (fn [])
                      :seon.sci.admit/caps
                      (config/result-caps (config/defaults))
                      :seon.config/on-core-error :record}))]
          (db/transact!
           connection
           [{:seon.cluster.eval/id
             (pr-str [(bootstrap/run-id agent-id) 0])
             :seon.cluster.eval/run
             [:seon.cluster.run/id (bootstrap/run-id agent-id)]
             :seon.cluster.eval/ordinal 0
             :seon.cluster.eval/at (java.util.Date.)
             :seon.cluster.eval/result-edn (pr-str node)}])
          (let [post-receipt-pull
                (bootstrap/pull-result (generator-request connection))
                listing-candidates
                (filter #(= :listing (second (:seon.repl/key %)))
                        (:seon.repl/candidates post-receipt-pull))
                next-entry
                (bootstrap/next-entry
                 (generator-request connection)
                 (bootstrap/run-id agent-id))]
            (is (= opening-source
                   (db/q '[:find ?source .
                           :in $ ?run-id
                           :where
                           [?run :seon.cluster.run/id ?run-id]
                           [?form :seon.cluster.run.form/run ?run]
                           [?form :seon.cluster.run.form/ordinal 0]
                           [?form :seon.cluster.run.form/source ?source]]
                         @connection (bootstrap/run-id agent-id)))
                "the first derived entry remains byte-identical in history")
            (is (every? #(= (first (:seon.repl/key %))
                            (:seon.repl/subject %))
                        listing-candidates)
                "listing subjects are their pulled stable identities")
            (is (map? next-entry)
                "the live post-receipt pull appends one next entry")
            (is (not= opening-source (bootstrap/entry-source next-entry)))
            (is (= next-entry
                   (bootstrap/next-entry
                    (generator-request connection)
                    (bootstrap/run-id agent-id)))
                "same post-receipt state derives byte-identical data")))))))

(deftest authored-plan-machinery-is-deleted
  (is (nil? (io/resource "seon/bootstrap.edn")))
  (doseq [old '[packaged-forms population-tx ordered-sources agent-sources
                plan-digest help-text]]
    (is (nil? (ns-resolve 'seon.bootstrap old)) (str old " is deleted"))))

(deftest first-agent-supervision-is-one-self-erasing-system-run
  (support/with-database
    (fn [connection]
      (seed-cluster! connection "supervision")
      (db/transact!
       connection
       (into (cluster.agent/creation-tx
              {:seon.cluster.agent/id "root"
               :seon.cluster/name "supervision"
               :seon.ns/name 'my.agents.root})
             (cluster.agent/creation-tx
              {:seon.cluster.agent/id "worker"
               :seon.cluster/name "supervision"
               :seon.ns/name 'my.agents.worker})))
      (let [tx (bootstrap/supervision-tx
                @connection cluster/boot-process-identity
                (java.util.Date.) "worker")]
        (is (seq tx))
        (db/transact! connection tx)
        (let [sources
              (db/q '[:find [?source ...]
                      :in $ ?run-id
                      :where
                      [?run :seon.cluster.run/id ?run-id]
                      [?form :seon.cluster.run.form/run ?run]
                      [?form :seon.cluster.run.form/source ?source]]
                    @connection (bootstrap/supervision-run-id))]
          (is (= 2 (count sources)))
          (is (some #(str/includes? % "my.message/send") sources))
          (is (some #(str/includes? % "seon.cluster.eval/result-edn") sources))
          (is (every? #(or (str/includes? % "run/complete")
                           (not (str/includes? % "my.message/send")))
                      sources))
          (is (empty? (bootstrap/supervision-tx
                       @connection cluster/boot-process-identity
                       (java.util.Date.) "worker"))))))))
