(ns seon.oversight-test
  "The fleet story over real booted Flow graphs and a real root page."
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.db :as db]
            [seon.bootstrap :as bootstrap]
            [seon.cluster :as cluster]
            [seon.oversight :as oversight]
            [seon.render.hiccup :as hiccup]
            [seon.test-support :as support])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse
            HttpResponse$BodyHandlers]))

(set! *warn-on-reflection* true)

(deftest a-dead-procs-missing-ping-is-unknown-never-healthy
  (is (= {:seon.oversight/proc :dead
          :seon.oversight/ping :unknown}
         (oversight/proc-ping :dead nil))))

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
    (cluster/refresh-source! root)
    (let [instance (cluster/start! {:seon.boot/cluster-name name
                                    :seon.boot/root root})]
      (try
        (await-fact
         (:seon.boot/cluster-connection instance)
         (fn [db]
           (db/q '[:find ?closed-at .
                  :in $ ?run-id
                  :where
                  [?run :seon.cluster.run/id ?run-id]
                  [?run :seon.cluster.run/closed-at ?closed-at]]
                db (bootstrap/run-id "root"))))
        (body instance)
        (finally
          (cluster/stop! instance))))))

(defn- fetch-root
  "Fetch the booted cluster's root page through its real socket."
  [instance]
  (let [request (-> (HttpRequest/newBuilder
                     (URI/create
                      (:seon.render.web/url
                       (:seon.render.web/served instance))))
                    (.GET)
                    (.build))]
    (.send (HttpClient/newHttpClient)
           request
           (HttpResponse$BodyHandlers/ofString))))

(deftest a-booted-cluster-tells-its-live-fleet-story
  (with-cluster
    "booted"
    (fn [instance]
      (let [db @(:seon.boot/cluster-connection instance)
            caps (:seon.sci.admit/caps
                  (:seon.cluster.loop/cluster instance))
            built (oversight/unit {:seon.db/db db
                                   :seon.sci.admit/caps caps})
            value (:seon.render/value built)
            root (first (:seon.oversight/agents value))
            plumbing (:seon.oversight/plumbing value)]
        (testing "the unit joins live ping data to the immutable facts"
          (is (some? built))
          (is (= `oversight/ai-story (:seon.render/ai built)))
          (is (= `oversight/html-table (:seon.render/html built)))
          (is (= ["root"]
                 (mapv :seon.cluster.agent/id
                       (:seon.oversight/agents value))))
          (is (not (contains? root :seon.cluster.run/id)))
          (is (contains? root :seon.oversight/turn-passes)
              "parked is implied by no run plus a responsive turn proc")
          (is (not-any? #(contains? % :seon.oversight/state)
                        (concat (:seon.oversight/agents value) plumbing))
              "the process-local story carries presence, not an enum")
          (is (= 1 (:seon.cluster.work/episode-runs root))
              "the autonomous bootstrap run belongs to this episode")
          (is (= {:seon.oversight/count 0
                  :seon.oversight/capacity 1}
                 (:seon.oversight/mailbox root)))
          (is (= {:seon.oversight/count 0
                  :seon.oversight/capacity 1}
                 (:seon.oversight/turn-buffer root)))
          (is (= #{:seon.cluster.agent/armer
                   :seon.render.web/render}
                 (into #{} (map :seon.oversight/proc) plumbing)))
          (is (every? #(int? (:seon.oversight/passes %)) plumbing)
              "the cluster graph's ordinary Flow count is the pass oracle"))
        (testing "both typed outputs share the fleet value"
          (is (= "root: parked"
                 (oversight/ai-story built)))
          (let [html (hiccup/->string
                      (oversight/html-table built))]
            (is (str/includes? html "data-fleet-oversight=\"agents\""))
            (is (str/includes? html "<td>root</td>"))
            (is (str/includes? html "<td>parked</td>"))
            (is (str/includes? html "data-state=\"parked\""))
            (is (str/includes? html "plumbing passes"))))
        (testing "the prose grammar carries run and episode position"
          (is (= "agent-b: mid-turn on run run-3, 3rd run this episode; agent-c: parked"
                 (oversight/ai-story
                  {:seon.render/value
                   {:seon.oversight/agents
                    [{:seon.cluster.agent/id "agent-b"
                      :seon.cluster.run/id "run-3"
                      :seon.cluster.work/episode-runs 3}
                     {:seon.cluster.agent/id "agent-c"
                      :seon.oversight/turn-passes 0
                      :seon.cluster.work/episode-runs 0}]}}))))
        (testing "the seeded block reaches the real root-page wire"
          (let [^HttpResponse response (fetch-root instance)
                body (.body response)]
            (is (= 200 (.statusCode response)))
            (is (str/includes? body "id=\"surface-fleet-oversight\""))
            (is (str/includes? body "data-fleet-oversight=\"agents\""))
            (is (str/includes? body "<td>root</td>"))
            (is (str/includes? body "<td>parked</td>"))))))))

(deftest a-database-without-a-cluster-handle-omits-the-block
  (support/with-database
    (fn [connection]
      (let [source {:seon.db/db @connection}]
        (is (nil? (oversight/unit source)))))))
