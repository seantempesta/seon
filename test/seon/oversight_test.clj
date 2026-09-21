(ns seon.oversight-test
  "The fleet story over real booted Flow graphs and a real root page."
  (:require [clojure.core.async :as async]
            [clojure.datafy :as datafy]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.db :as db]
            [seon.bootstrap :as bootstrap]
            [seon.cluster :as cluster]
            [seon.cluster.boot :as boot]
            [seon.cluster.agent :as agent]
            [seon.oversight :as oversight]
            [seon.render.hiccup :as hiccup]
            [seon.test-support :as support])
  (:import [java.net URI]
           [java.time Duration]
           [java.net.http HttpClient HttpRequest HttpResponse
            HttpResponse$BodyHandlers]))

(set! *warn-on-reflection* true)

(deftest a-dead-procs-missing-ping-is-unknown-never-healthy
  (is (= {:seon.oversight/proc :dead
          :seon.oversight/ping :unknown}
         (oversight/proc-ping :dead nil))))

(deftest a-sliding-wake-reports-every-overwritten-signal
  (let [channel (agent/wake-channel)]
    (try
      (is (true? (async/offer! channel :first)))
      (is (true? (async/offer! channel :second)))
      (is (= {:type 'CountedSlidingBuffer
              :count 1
              :capacity 1
              :dropped 1}
             (:buffer (datafy/datafy channel))))
      (finally
        (async/close! channel)))))

(deftest absent-pongs-never-become-evidence-of-work
  (doseq [[observations expected]
          [[{} "unknown"]
           [{:seon.oversight/turn-passes 0} "parked"]
           [{:seon.turn/id "open-turn"} "mid-turn"]
           [{:seon.turn/id "open-turn"
             :seon.oversight/turn-passes 0} "mid-turn"]]]
    (let [unit {:seon.render/value
                {:seon.oversight/agents
                 [(merge {:seon.agent/id "observed"
                          :seon.turn.work/episode-runs 0}
                         observations)]
                 :seon.oversight/plumbing
                 [(oversight/proc-ping :delayed nil)]}}
          ai (oversight/ai-story unit)
          html (hiccup/->string (oversight/html-table unit))]
      (is (str/starts-with? ai (str "observed: " expected)))
      (is (str/includes? html (str "data-state=\"" expected "\"")))
      (is (str/includes? html ":delayed unknown"))
      (is (not (str/includes? html "mid-pass"))))))

(defn- await-fact
  "Return the first truthy `probe` result published by a database value."
  [connection probe]
  (let [events (async/promise-chan)
        key (keyword (str (ns-name *ns*)) (str (gensym "fact-")))]
    (d/listen connection key
              (fn [report]
                (when-let [value (probe (:db-after report))]
                  (async/offer! events value))))
    (try
      (when-let [value (probe @connection)]
        (async/offer! events value))
      (support/await-event! events "database fact")
      (finally
        (d/unlisten connection key)))))

(defn- with-cluster
  "Boot one real scratch cluster, await bootstrap, and always stop it."
  [name body]
  (let [root (str "tmp/oversight-test/" name)]
    (support/delete-recursively! root)
    (support/populate-published-root! root)
    (let [instance (boot/start! {:seon.boot/cluster-name name
                                    :seon.boot/root root})]
      (try
        (await-fact
         (:seon.boot/cluster-connection instance)
         (fn [db]
           (db/q '[:find ?closed-at .
                  :in $ ?run-id
                  :where
                  [?run :seon.turn/id ?run-id]
                  [?run :seon.turn/closed-tx ?closed-at]]
                db (bootstrap/run-id "root"))))
        (body instance)
        (finally
          (boot/stop! instance))))))

(defn- fetch-root
  "Fetch the booted cluster's root page through its real socket."
  [instance]
  (let [request (-> (HttpRequest/newBuilder
                     (URI/create
                      (:seon.render.web/url
                       (:seon.render.web/served instance))))
                    (.GET)
                    (.timeout (Duration/ofSeconds support/event-backstop-seconds))
                    (.build))]
    (.send (HttpClient/newHttpClient)
           request
           (HttpResponse$BodyHandlers/ofString))))

(deftest ^{:seon.test/fixture-observation "The test joins real booted flow observations to facts and verifies their delivery over the running HTTP surface."} ^{:seon.test/long
           "Boots a real cluster and fetches its root page to cover live fleet integration."}
  a-booted-cluster-tells-its-live-fleet-story
  (with-cluster
    "booted"
    (fn [instance]
      (let [db @(:seon.boot/cluster-connection instance)
            caps (:seon.sci.admit/caps
                  (:seon.turn.loop/cluster instance))
            built (oversight/unit {:seon.db/db db
                                   :seon.sci.admit/caps caps})
            value (:seon.render/value built)
            root (first (:seon.oversight/agents value))
            agent-procs (:seon.oversight/procs root)
            plumbing (:seon.oversight/plumbing value)
            declared-plumbing
            (set (keys (:procs (datafy/datafy
                                (:seon.flow/graph instance)))))]
        (testing "the unit joins live ping data to the immutable facts"
          (is (some? built))
          (is (= `oversight/ai-story (:seon.render/ai built)))
          (is (= `oversight/html-table (:seon.render/html built)))
          (is (= ["root"]
                 (mapv :seon.agent/id
                       (:seon.oversight/agents value))))
          (is (not-any? #(contains? % :seon.oversight/state)
                        (concat (:seon.oversight/agents value) plumbing))
              "the process-local story carries presence, not an enum")
          (is (nat-int? (:seon.turn.work/episode-runs root))
              "a new outside wake may already have reset the episode count")
          (doseq [occupancy (keep root [:seon.oversight/mailbox
                                       :seon.oversight/turn-buffer])]
            (is (<= 0 (:seon.oversight/count occupancy)
                    (:seon.oversight/capacity occupancy)))
            (is (nat-int? (:seon.oversight/dropped occupancy))
                "every lossy wake buffer reports its overwritten count"))
          (is (= declared-plumbing
                 (into #{} (map :seon.oversight/proc) plumbing)))
          (is (= #{:seon.agent/mailbox
                   :seon.agent/turn
                   :seon.agent/schedule}
                 (into #{} (map :seon.oversight/proc) agent-procs))
              "every proc declared by the agent blueprint is visible")
          (is (every? #(case (:seon.oversight/ping %)
                         :reply (int? (:seon.oversight/passes %))
                         :unknown (not (contains? % :seon.oversight/passes))
                         false)
                      agent-procs)
              "an agent proc missing the bounded pong is unknown")
          (is (every? #(case (:seon.oversight/ping %)
                         :reply (int? (:seon.oversight/passes %))
                         :unknown (not (contains? % :seon.oversight/passes))
                         false)
                      plumbing)
              "a missing pong is unknown even after the opening completed"))
        (testing "both typed outputs share the fleet value"
          (let [expected (cond (:seon.turn/id root) "mid-turn"
                               (some? (:seon.oversight/turn-passes root)) "parked"
                               :else "unknown")
                html (hiccup/->string
                      (oversight/html-table built))]
            (is (str/starts-with? (oversight/ai-story built) (str "root: " expected)))
            (is (str/includes? html "data-fleet-oversight=\"agents\""))
            (is (str/includes? html "<td>root</td>"))
            (is (str/includes? html (str "data-state=\"" expected "\"")))
            (is (str/includes? html "plumbing passes"))))
        (testing "the prose grammar carries run and episode position"
          (is (= "agent-b: mid-turn on turn run-3, 3rd turn since the outside wake; agent-c: parked"
                 (oversight/ai-story
                  {:seon.render/value
                   {:seon.oversight/agents
                    [{:seon.agent/id "agent-b"
                      :seon.turn/id "run-3"
                      :seon.turn.work/episode-runs 3}
                     {:seon.agent/id "agent-c"
                      :seon.oversight/turn-passes 0
                      :seon.turn.work/episode-runs 0}]}}))))
        (testing "the seeded block reaches the real root-page wire"
          (let [^HttpResponse response (fetch-root instance)
                body (.body response)]
            (is (= 200 (.statusCode response)))
            (is (str/includes? body "id=\"surface-fleet-oversight\""))
            (is (str/includes? body "data-fleet-oversight=\"agents\""))
            (is (str/includes? body "<td>root</td>"))
            (is (true? (str/includes? body "data-agent=\"root\" data-state=\""))
                "the HTTP render is a later observation, not the earlier unit's state")))))))

(deftest a-database-without-a-cluster-handle-omits-the-block
  (support/with-database
    (fn [connection]
      (let [source {:seon.db/db @connection}]
        (is (nil? (oversight/unit source)))))))
