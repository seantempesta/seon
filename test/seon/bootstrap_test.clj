(ns seon.bootstrap-test
  "The live-fact opening generator and its agent-creation seam."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.bootstrap :as bootstrap]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as cluster.agent]
            [seon.turn :as turn]

            [seon.db :as db]
            [seon.test-support :as support]))

(def ^:private agent-id "bootstrap-agent")
(def ^:private namespace-name 'my.agents.bootstrap-agent)

(defn- seed-cluster! [connection cluster-name]
  (support/seed-cluster!
   connection cluster-name
   {:seon.config.bootstrap/beyond-closure-token-budget 1024})
  (cluster/ensure-cluster-entity!
   connection cluster-name cluster/boot-process-identity))

(deftest creation-opens-one-zero-form-generated-run-with-a-real-task
  (support/with-database
    (fn [connection]
      (seed-cluster! connection "generated-bootstrap")
      (let [request {:seon.agent/id agent-id
                     :seon.cluster/name "generated-bootstrap"
                     :seon.ns/name namespace-name}
            process cluster/boot-process-identity
            result (cluster/ensure-entity! connection process request)
            run-id (bootstrap/run-id agent-id)
            run (db/pull @connection
                         '[:seon.turn/id
                           :seon.turn.work/situation
                           :seon.turn/reply-size
                           {:seon.cluster.eval/_run
                            [:seon.cluster.eval/ordinal
                             :seon.cluster.eval/author
                             :seon.cluster.eval/source]}
                           {:seon.turn/trigger
                            [:seon.message/id
                             :seon.message/content]}]
                         [:seon.turn/id run-id])]
        (is (= run-id (:seon.turn/id result)))

        (is (nil? (:seon.turn/reply-size run))
            "a generated run has no frozen authored plan")
        (is (= :generate (:seon.turn.work/situation run)))
        ;; ONE ENTITY PER (run, ordinal): the evaluations point AT the run,
        ;; and the run keeps no component mirror of that back-edge.
        (is (empty? (:seon.cluster.eval/_run run))
            "creation admits no evaluation outside the generator")
        (is (= (bootstrap/task-message-id @connection agent-id)
               (get-in run [:seon.turn/trigger
                            :seon.message/id])))
        (is (= (bootstrap/task-message)
               (get-in run [:seon.turn/trigger
                            :seon.message/content])))
        (is (= {:seon.turn.work/situation :generate
                :seon.turn/id run-id
                :seon.agent/id agent-id}
               (turn/next-agent-work
                @connection {:seon.agent/id agent-id
                             :seon.db.process/id process})))))))

(deftest first-agent-supervision-is-one-self-erasing-system-run
  (support/with-database
    (fn [connection]
      (seed-cluster! connection "supervision")
      (support/transacted!
              connection
              (into (cluster.agent/creation-tx
                     {:seon.agent/id "root"
                      :seon.cluster/name "supervision"
                      :seon.ns/name 'my.agents.root})
                    (cluster.agent/creation-tx
                     {:seon.agent/id "worker"
                      :seon.cluster/name "supervision"
                      :seon.ns/name 'my.agents.worker})))
      (let [tx (bootstrap/supervision-tx
                @connection cluster/boot-process-identity
                "worker")]
        (is (seq tx))
        (support/transacted! connection tx)
        (let [sources
              (db/q '[:find [?source ...]
                      :in $ ?run-id
                      :where
                      [?run :seon.turn/id ?run-id]
                      [?form :seon.cluster.eval/run ?run]
                      [?form :seon.cluster.eval/source ?source]]
                    @connection (bootstrap/supervision-run-id))]
          (is (= 2 (count sources)))
          (is (some #(str/includes? % "my.message/send") sources))
          (is (some #(str/includes? % "seon.eval/shown") sources))
          (is (every? #(or (str/includes? % "run/complete")
                           (not (str/includes? % "my.message/send")))
                      sources))
          (is (empty? (bootstrap/supervision-tx
                       @connection cluster/boot-process-identity
                       "worker"))))))))
