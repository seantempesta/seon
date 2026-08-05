(ns seon.schedule-test
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.cluster.agent :as agent]
            [seon.cluster.wake :as wake]
            [seon.db :as db]
            [seon.flow :as seon.flow]
            [seon.schedule :as schedule]
            [seon.schema.datahike :as schema.datahike])
  (:import [java.time Instant]
           [java.util Date]
           [java.util.concurrent CountDownLatch TimeUnit]))

(defn- instant
  [text]
  (Date/from (Instant/parse text)))

(def ^:private schedule-attributes
  [:seon.ns/name
   :seon.cluster.agent/id
   :seon.cluster.agent/namespace
   :seon.fn/sym
   :seon.schedule/id
   :seon.schedule/cron
   :seon.schedule/timezone
   :seon.schedule.task/id
   :seon.schedule.task/owner
   :seon.schedule.task/function
   :seon.schedule.task/schedule
   :seon.schedule.fire/id
   :seon.schedule.fire/task
   :seon.schedule.fire/nominal-at
   :seon.schedule.fire/observed-at
   :seon.cluster.message/id
   :seon.cluster.message/to
   :seon.cluster.message/content
   :seon.cluster.message/at
   :seon.cluster.message/ordinal])

(defn- with-schedule-database
  [body]
  (let [configuration {:store {:backend :memory :id (random-uuid)}
                       :schema-flexibility :write
                       :keep-history? true}
        _ (d/create-database configuration)
        connection (atom (d/connect configuration))]
    (try
      (db/transact! @connection
                    (schema.datahike/malli->datahike-schema
                     schedule-attributes))
      (body configuration connection)
      (finally
        (d/release @connection)
        (d/delete-database configuration)))))

(defn- seed-task!
  [connection agent-id namespace-name task-id expression]
  (db/transact!
   connection
   [{:seon.ns/name namespace-name}
    {:seon.cluster.agent/id agent-id
     :seon.cluster.agent/namespace [:seon.ns/name namespace-name]}
    {:seon.fn/sym (str namespace-name "/run")}
    {:seon.schedule/id (str task-id "/schedule")
     :seon.schedule/cron expression
     :seon.schedule/timezone "UTC"}
    {:seon.schedule.task/id task-id
     :seon.schedule.task/owner [:seon.cluster.agent/id agent-id]
     :seon.schedule.task/function
     [:seon.fn/sym (str namespace-name "/run")]
     :seon.schedule.task/schedule
     [:seon.schedule/id (str task-id "/schedule")]}]))

(defn- graph-sink-step
  ([]
   {:ins {::episode "One owning-agent mailbox episode."}
    :workload :io})
  ([args]
   args)
  ([state _transition]
   state)
  ([state _input message]
   (.countDown ^CountDownLatch (::delivered state))
   [(assoc state ::message message) nil]))

(deftest nominal-instants-obey-gap-and-overlap-rules
  (testing "a nonexistent spring-forward minute is skipped"
    (is (= (instant "2025-03-10T06:30:00Z")
           (schedule/next-nominal-after
            {:seon.schedule/cron "30 2 * * *"
             :seon.schedule/timezone "America/New_York"
             :seon.schedule/reference-at (instant "2025-03-09T05:00:00Z")}))))
  (testing "a repeated fall-back minute has two distinct nominal instants"
    (let [first-at (instant "2025-11-02T05:30:00Z")]
      (is (= (instant "2025-11-02T06:30:00Z")
             (schedule/next-nominal-after
              {:seon.schedule/cron "30 1 * * *"
               :seon.schedule/timezone "America/New_York"
               :seon.schedule/reference-at first-at}))))))

(deftest fire-identity-is-idempotent-across-rederivation
  (with-schedule-database
    (fn [configuration connection]
      (seed-task! @connection "owner" 'my.agents.owner "minute-task"
                  "* * * * *")
      (let [observed-at (instant "2025-04-05T12:34:45Z")]
        (is (= 1 (schedule/fire-due! @connection "owner" observed-at)))
        (d/release @connection)
        (reset! connection (d/connect configuration))
        (is (= 0 (schedule/fire-due! @connection "owner" observed-at)))
        (is (= 1
               (db/q '[:find (count ?fire) .
                       :where [?fire :seon.schedule.fire/id _]]
                     @@connection)))
        (is (= 1
               (db/q '[:find (count ?message) .
                       :where
                       [?message :seon.cluster.message/to ?owner]
                       [?owner :seon.cluster.agent/id "owner"]]
                     @@connection)))))))

(deftest scheduled-fire-wakes-only-the-owning-agent-route
  (with-schedule-database
    (fn [_configuration connection]
      (seed-task! @connection "owner" 'my.agents.owner "routed-task"
                  "* * * * *")
      (db/transact!
       @connection
       [{:seon.ns/name 'my.agents.other}
        {:seon.cluster.agent/id "other"
         :seon.cluster.agent/namespace [:seon.ns/name 'my.agents.other]}])
      (let [owner-eid (db/q '[:find ?agent .
                              :where
                              [?agent :seon.cluster.agent/id "owner"]]
                            @@connection)
            other-eid (db/q '[:find ?agent .
                              :where
                              [?agent :seon.cluster.agent/id "other"]]
                            @@connection)
            owner-channel (async/chan (async/sliding-buffer 1))
            other-channel (async/chan (async/sliding-buffer 1))
            armer-channel (async/chan (async/sliding-buffer 1))
            render-channel (async/chan (async/sliding-buffer 1))
            fault-channel (async/chan (async/sliding-buffer 1))
            delivered (CountDownLatch. 1)
            owner-graph
            (flow/create-flow
             {:procs
              {::mailbox
               {:proc
                (seon.flow/var-process
                 #'agent/mailbox-step :io
                 {:seon.cluster.wake/channel owner-channel})}
               ::sink
               {:proc
                (seon.flow/var-process
                 #'graph-sink-step :io
                 {::delivered delivered})}}
              :conns
              [[[::mailbox ::agent/episode] [::sink ::episode]]]})
            schedule-channel (async/chan (async/sliding-buffer 1))
            schedule-graph
            (flow/create-flow
             {:procs
              {::schedule
               {:proc
                (seon.flow/var-process
                 #'schedule/schedule-step :io
                 {:seon.cluster.loop/cluster
                  {:seon.store/branch-connection @connection}
                  :seon.cluster.agent/id "owner"
                  :seon.schedule/channel schedule-channel})}}
              :conns []})]
        (try
          (flow/start owner-graph)
          (flow/resume owner-graph)
          (wake/route!
           {:seon.cluster.wake/connection @connection
            :seon.cluster.wake/channels
            (constantly {owner-eid owner-channel other-eid other-channel})
            :seon.cluster.wake/fenced? (constantly false)
            :seon.cluster.wake/armer-channel armer-channel
            :seon.cluster.wake/render-channel render-channel
            :seon.cluster.wake/fault-channel fault-channel
            :seon.cluster.wake/key ::route})
          (flow/start schedule-graph)
          (flow/resume schedule-graph)
          (is (.await delivered 5 TimeUnit/SECONDS)
              "the ordinary message wake entered the owning agent graph")
          (is (= 1
                 (db/q '[:find (count ?fire) .
                         :where [?fire :seon.schedule.fire/id _]]
                       @@connection)))
          (is (nil? (async/poll! other-channel)))
          (is (nil? (async/poll! fault-channel)))
          (finally
            (wake/unlisten! {:seon.cluster.wake/connection @connection
                             :seon.cluster.wake/key ::route})
            (flow/stop schedule-graph)
            (flow/stop owner-graph)
            (doseq [channel [owner-channel other-channel armer-channel
                             render-channel fault-channel schedule-channel]]
              (async/close! channel))))))))
