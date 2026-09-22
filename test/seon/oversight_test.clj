(ns seon.oversight-test
  "The fleet story over real booted Flow graphs and a real root page."
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [clojure.datafy :as datafy]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.db :as db]
            [seon.bootstrap :as bootstrap]
            [seon.cluster :as cluster]
            [seon.cluster.boot :as boot]
            [seon.cluster.agent :as agent]
            [seon.flow :as seon.flow]
            [seon.note :as note]
            [seon.render.web :as web]
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
                          :seon.oversight/procs []
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
                                   :seon.agent/routing
                                   (:seon.agent/routing instance)
                                   :seon.sci.admit/caps caps})
            value (:seon.render/value built)
            root (first (:seon.oversight/agents value))
            agent-procs (:seon.oversight/procs root)
            plumbing (:seon.oversight/plumbing value)
            declared-plumbing
            (set (keys (:procs (datafy/datafy
                                (:seon.flow/graph instance)))))]
        (testing "the routing every render path holds carries the cluster graph"
          (is (identical? (:seon.flow/graph instance)
                          (:seon.flow/graph
                           @(:seon.agent/routing instance)))))
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
                      :seon.oversight/procs []
                      :seon.turn/id "run-3"
                      :seon.turn.work/episode-runs 3}
                     {:seon.agent/id "agent-c"
                      :seon.oversight/procs []
                      :seon.oversight/turn-passes 0
                      :seon.turn.work/episode-runs 0}]
                    :seon.oversight/plumbing []}}))))
        (testing "the seeded block reaches the real root-page wire"
          (let [^HttpResponse response (fetch-root instance)
                body (.body response)]
            (is (= 200 (.statusCode response)))
            (is (str/includes? body "id=\"surface-fleet-oversight\""))
            (is (str/includes? body "data-fleet-oversight=\"agents\""))
            (is (str/includes? body "<td>root</td>"))
            (is (true? (str/includes? body "data-agent=\"root\" data-state=\""))
                "the HTTP render is a later observation, not the earlier unit's state")))
        (testing "the render proc's SSE package after a database wake carries the fleet"
          (let [view (:seon.render.web/view instance)
                registration (:seon.render.web/registration view)
                tap (async/chan (async/sliding-buffer 1))
                connection (:seon.boot/cluster-connection instance)]
            (#'web/register-tab! registration "root")
            (async/tap (:seon.render.web/pages-mult view) tap)
            (try
              (async/offer! (:seon.render.web/render-channel view)
                            {:seon.render.web/join true})
              (support/await-event! tap "the joined tab's first root package"
                                    #(contains? % "root"))
              (let [saved (note/add! "oversight-wake" "The fleet repaints."
                                     connection "root")
                    wake-basis (db/basis-t @connection)
                    packages
                    (support/await-event!
                     tap "a root package at or after the note's wake"
                     #(some-> (get % "root")
                              :seon.render.package/basis-transaction
                              (>= wake-basis)))
                    keyframe (String. ^bytes (:seon.render.package/keyframe-bytes
                                              (get packages "root"))
                                      "UTF-8")]
                (is (= "oversight-wake" (:my.note/id saved)))
                (is (str/includes? keyframe "surface-fleet-oversight"))
                (is (str/includes? keyframe "data-fleet-oversight=\"agents\""))
                (is (str/includes? keyframe "<td>root</td>"))
                (is (not (str/includes? keyframe "data-fleet-oversight=\"unavailable\""))))
              (finally
                (async/untap (:seon.render.web/pages-mult view) tap)
                (async/close! tap)
                (#'web/deregister-tab! registration "root")))))))))

(defn- probe-graph
  "Start one real Flow graph with one idle proc; the caller stops it."
  []
  (::seon.flow/graph
   (seon.flow/start-graph!
    {::seon.flow/graph-definition
     {:procs {:probe/idle
              {:proc (flow/process
                      (flow/map->step
                       {:describe (fn [] {:ins {:in "wake"}})
                        :transform (fn [state _ _] [state nil])}))}}}})))

(deftest oversight-reads-the-handed-routing-and-names-what-it-lacks
  (support/with-database
    (fn [connection]
      (let [db @connection
            graph (probe-graph)]
        (try
          (testing "a detached request has no live story"
            (is (nil? (oversight/unit {:seon.db/db db}))))
          (testing "the handed routing and its joined graph are the fleet"
            (let [routing (agent/routing)
                  _ (swap! routing assoc :seon.flow/graph graph)
                  built (oversight/unit {:seon.db/db db
                                         :seon.agent/routing routing})
                  value (:seon.render/value built)]
              (is (= [] (:seon.oversight/agents value))
                  "a present empty armed map is an empty fleet")
              (is (= [{:seon.oversight/proc :probe/idle
                       :seon.oversight/ping :reply}]
                     (mapv #(select-keys % [:seon.oversight/proc
                                            :seon.oversight/ping])
                           (:seon.oversight/plumbing value))))
              (is (= "No agent graphs are armed." (oversight/ai-story built)))))
          (testing "routing that cannot answer is unavailable, visibly"
            (doseq [[state missing]
                    [[{:seon.agent/armed {}} [:seon.flow/graph]]
                     [{:seon.flow/graph graph} [:seon.agent/armed]]
                     [{} [:seon.agent/armed :seon.flow/graph]]]]
              (let [built (oversight/unit {:seon.db/db db
                                           :seon.agent/routing (atom state)})
                    html (hiccup/->string (oversight/html-table built))]
                (is (= {:seon.oversight/missing missing}
                       (:seon.render/value built)))
                (is (str/starts-with? (oversight/ai-story built)
                                      "Live fleet state is unavailable"))
                (is (str/includes? html "data-fleet-oversight=\"unavailable\""))
                (is (not (str/includes? html "data-fleet-oversight=\"agents\""))
                    "missing state never renders as an empty fleet"))))
          (finally
            (flow/stop graph)))))))
