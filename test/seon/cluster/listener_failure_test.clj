(ns seon.cluster.listener-failure-test
  "N3: a failed Datahike listener hand-off is one stored fault on the captured
  cluster world, never recursive, and retires the registration only while its
  identity still matches (`seon.cluster.store/listen-failures!`, the fork's
  `datahike.writer/notify-listeners!`)."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.blob :as blob]
            [seon.cluster.store :as store]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.flow :as flow]
            [seon.test-support :as test-support]))

(defn- with-world
  "Call `f` with the cluster's world, which holds its own work launcher (the
  failed hand-off is recorded through it, `seon.flow/hand-off-fault!`), and
  stop the launcher after, even when `f` throws."
  [connection cluster-name f]
  (test-support/apply-config! connection cluster-name {})
  (let [effective (config/effective (db/db connection) cluster-name)
        members {:seon.boot/cluster-name cluster-name
                 :seon.db/connection connection
                 :seon.sci.admit/caps (config/result-caps effective)}
        launcher (flow/start-work-launcher!
                  {:seon.env/environment (env/environment members)
                   ::flow/configuration (select-keys effective flow/flow-workload-attributes)})]
    (try
      (f (env/environment (assoc members :seon.flow/work-launcher launcher)))
      (finally (flow/stop-work-launcher! launcher)))))

(defn- faults
  "Every stored occurrence: its count and its whole evidence text."
  [connection]
  (let [database (db/db connection)]
    (mapv (fn [occurrence]
            (let [digest (get-in occurrence [:seon.error.occurrence/data-blob
                                             :seon.error.occurrence/blob-digest])]
              {:count (:seon.error.occurrence/count occurrence)
               :evidence (str (pr-str occurrence)
                              (when digest (blob/get connection digest)))
               ;; the cause chain's link data as data, read from the inline evidence
               :linked (when-not (:seon.error/capped? occurrence)
                         (into {} (keep :seon.error/data)
                               (:seon.error/chain (edn/read-string {:default tagged-literal}
                                                                   (:seon.error/data-edn occurrence)))))}))
          (db/q '[:find [(pull ?occurrence [*]) ...]
                  :where [_ :seon.error/occurrences ?occurrence]]
                database))))

(defn- recorded
  "Committed fault deliveries: the sum of occurrence counts."
  [connection]
  (reduce + 0 (map :count (faults connection))))

(defn- commit!
  "One committed report through the production config writer."
  [connection cluster-name limit]
  (test-support/apply-config! connection cluster-name {:seon.config.db/write-time-limit-ms limit}))

(defn- await-recorded!
  [connection n]
  (test-support/await-event! connection (str n " recorded fault deliveries")
                             (fn [_] (= n (recorded connection))))
  (faults connection))

(deftest a-failed-listener-is-one-stored-fault-and-retires-only-its-own-registration
  (test-support/with-database
   (fn [connection]
     (with-world connection "listener-failure" (fn [environment]
     (let [cluster-name "listener-failure"
           baseline (recorded connection)
           registry (:listeners (meta connection))
           failing (fn [_] (throw (IllegalStateException. "hand-off refused")))]
       (store/listen-failures! environment "listener-failure-test")
       (d/listen connection ::failing failing)
       (commit! connection cluster-name 5001)
       (let [[fault] (filter #(str/includes? (:evidence %) "hand-off refused")
                             (await-recorded! connection (inc baseline)))]
         (testing "one fault names the listener, its commit and the cause"
           (is (some? fault))
           (is (str/includes? (:evidence fault) (str ::failing)))
           (is (uuid? (:seon.cluster.store/commit-id (:linked fault))) "the failed commit, as data")
           (is (str/includes? (:evidence fault) "hand-off refused")))
         (testing "the matching registration is retired, so the fault's own commit cannot recurse"
           (is (not (contains? @registry ::failing)))))
       (commit! connection cluster-name 5002)
       (is (= (inc baseline) (recorded connection))
           "a later commit records nothing: the failed listener is gone")
       (let [replacement (fn [_] nil)
             replacing (fn [_]
                         (d/listen connection ::replaced replacement)
                         (throw (UnsupportedOperationException. "failed after repair")))]
         (d/listen connection ::replaced replacing)
         (commit! connection cluster-name 5003)
         (await-recorded! connection (+ 2 baseline))
         (is (identical? replacement (get @registry ::replaced))
             "a replacement registered under the failed key survives retirement")
         (is (= (+ 2 baseline) (recorded connection)) "no fault recorded itself again")
         (d/unlisten connection ::replaced))))))))
