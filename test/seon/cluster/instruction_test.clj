(ns seon.cluster.instruction-test
  "Computed cluster context membership and idempotent entity initialization."
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [seon.cluster :as cluster]
            [seon.cluster.instruction :as instruction]
            [seon.test-support :as test-support]))

(def ^:private getting-started-text
  (str "This is a live Clojure REPL. Everything above is the output of "
       "`(seon.render/walk)` — run it yourself with `:depth`/`:root` to "
       "see more. Your reply is read as forms and evaluated in your "
       "namespace. A `defn` with `:malli/schema` becomes permanent; "
       "anything else is scratch. Talk to other agents with "
       "`(my.message/send! …)`. Prose lines are kept as `;;` comments."))

(defn- cluster-toolkit
  [db cluster-name]
  (set
   (d/q '[:find [?namespace-name ...]
          :in $ ?cluster-name
          :where
          [?cluster :seon.cluster/name ?cluster-name]
          [?cluster :seon.cluster/toolkit ?namespace]
          [?namespace :seon.ns/name ?namespace-name]]
        db cluster-name)))

(deftest source-has-one-owner-editable-getting-started-row
  (is (= [{:seon.cluster.instruction/id :getting-started
           :seon.cluster.instruction/text getting-started-text}]
         (instruction/seed-rows)))
  (test-support/with-database
    (fn [connection]
      (d/transact
       connection
       [{:seon.cluster.instruction/id :getting-started
         :seon.cluster.instruction/text "Owner revision."}
        {:seon.cluster.instruction/id :reply-grammar
         :seon.cluster.instruction/text "Superseded."}])
      (d/transact connection
                  (#'cluster/instruction-row-changes
                   @connection (instruction/seed-rows)))
      (is (= #{:getting-started}
             (set
              (d/q '[:find [?id ...]
                     :where [_ :seon.cluster.instruction/id ?id]]
                   @connection))))
      (is (= "Owner revision."
             (d/q '[:find ?text .
                    :where
                    [?instruction :seon.cluster.instruction/id
                     :getting-started]
                    [?instruction :seon.cluster.instruction/text ?text]]
                  @connection))))))

(deftest cluster-toolkit-exactly-converges-to-the-computed-rule
  (test-support/with-database
    (fn [connection]
      (test-support/seed-cluster! connection "toolkit")
      (let [computed (set (instruction/toolkit-namespaces @connection))
            removed (first (sort computed))]
        (is (seq computed) "The canonical corpus must expose a toolkit.")
        (is (= computed (cluster-toolkit @connection "toolkit")))
        (d/transact
         connection
         (cond-> [{:seon.ns/name 'my.stale.toolkit}
                  {:seon.cluster/name "toolkit"
                   :seon.cluster/toolkit
                   [:seon.ns/name 'my.stale.toolkit]}]
           removed
           (conj [:db/retract
                  [:seon.cluster/name "toolkit"]
                  :seon.cluster/toolkit
                  [:seon.ns/name removed]])))
        (is (not= computed (cluster-toolkit @connection "toolkit")))
        (cluster/ensure-cluster-entity!
         connection "toolkit" "instruction-test-process")
        (is (some? (d/q '[:find ?process .
                          :where
                          [?process :seon.db.process/id
                           "instruction-test-process"]]
                        @connection)))
        (is (= computed (cluster-toolkit @connection "toolkit")))))))

(deftest ensure-entity-creates-once-and-resumes-untouched
  (test-support/with-database
    (fn [connection]
      (test-support/seed-cluster! connection "ensure")
      (let [first-request {:seon.cluster.agent/id "alice"
                           :seon.cluster/name "ensure"
                           :seon.ns/name 'my.agents.alice}
            resumed-request (assoc first-request
                                   :seon.ns/name 'my.agents.replacement)]
        (is (nil? (:seon.error/kind
                   (cluster/ensure-entity!
                    connection cluster/boot-process-identity first-request))))
        (let [before (d/pull @connection '[*]
                             [:seon.cluster.agent/id "alice"])]
          (is (nil? (:seon.error/kind
                     (cluster/ensure-entity!
                      connection cluster/boot-process-identity
                      resumed-request))))
          (is (= before
                 (d/pull @connection '[*]
                         [:seon.cluster.agent/id "alice"])))
          (is (nil? (d/q '[:find ?namespace .
                           :in $ ?namespace-name
                           :where
                           [?namespace :seon.ns/name ?namespace-name]]
                         @connection
                         'my.agents.replacement))))))))
