(ns seon.cluster.acquisition-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.cluster]
            [seon.cluster.agent :as agent]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.config :as config]
            [seon.id :as id]
            [seon.schema :as schema]
            [seon.db :as db]
            [seon.env :as env]
            [seon.sci.eval :as sci]
            [seon.sci.eval-test]
            [seon.test-support :as support]
            [seon.turn :as turn]))

(defn- measured
  {:malli/schema [:=> [:cat [:fn ifn?]] :map]}
  [f]
  (let [runtime (Runtime/getRuntime)
        before (- (.totalMemory runtime) (.freeMemory runtime))
        start (System/nanoTime) value (f)]
    {:seon.proof/value value :seon.proof/ms (/ (- (System/nanoTime) start) 1e6)
     :seon.proof/heap-delta-bytes (- (- (.totalMemory runtime) (.freeMemory runtime)) before)}))

(defn- evaluate!
  {:malli/schema [:=> [:cat :seon.agent/execution-handle :string] :seon.sci.eval/evaluation]}
  [handle source]
  (let [result (support/evaluate
                {:seon.sci.eval/ctx (:seon.sci.eval/ctx handle)
                 :seon.db/db (db/db (:seon.db/connection handle))
                 :seon.agent/id (:seon.agent/id handle)
                 :seon.cluster.eval/ns [:seon.ns/name 'seon.sci.eval-test]
                 :seon.cluster.eval/source source
                 :seon.sci.eval/time-limit-ms 5000
                 :seon.sci.admit/caps (config/result-caps config/defaults)})]
    (when (:seon.cluster.eval/error result)
      (throw (ex-info "Evaluation refused." result)))
    result))

(defn- declare!
  {:malli/schema [:=> [:cat :seon.agent/execution-handle :string] :nil]}
  [handle source]
  (let [entity (:seon.program/row (evaluate! handle source))
        connection (:seon.db/connection handle)]
    (when-not entity (throw (ex-info "Expected an analyzed declaration." {})))
    ;; The fixture writer binds the connection it names.
    (support/transacted! connection (#'turn/row-tx (db/db connection) {} entity)))
  nil)

(defn- with-published-store
  "The canonical operator population supplies a real held store, not a fabricated handle."
  {:malli/schema [:=> [:cat [:fn ifn?]] :nil]}
  [body]
  (let [root (str (.getCanonicalPath (io/file "tmp")) "/c2-fixture-" (id/id))]
    (support/populate-published-operator-root!
     root {:seon.test/fixture-observation
           "Acquisition must own and release a store branch; the ordinary fixture exposes only a connection and re-identifies only current-src."})
    (with-open [held (support/closeable
                      (store/open-store! {:seon.store/dir (str root "/data/store")})
                      store/release-store!)]
      (registry/branch!
       {:seon.store/store @held
        :seon.store/branch (registry/cluster-branch "acquisition")
        :seon.cluster.registry/from :current-src})
      (with-open [connection (support/closeable
                              (store/open-branch! @held (registry/cluster-branch "acquisition"))
                              store/release-branch!)]
        (let [database @@connection
              projection (schema/projection-from-database database)
              state (sci/projection-state database projection)]
          (db/carry-connection-projection-state! @connection state)
          (schema/call-with-projection-state
           state #(body @connection @held)))))
    (support/delete-recursively! root))
  nil)

(deftest ^{:seon.test/long "Measured 10,051 ms for canonical file-store population, two branch programs, two analyzed declarations and complete release."
           :seon.test/long-ms 15000}
  acquisition-owns-only-its-isolated-resources
  (with-published-store
   (fn [connection held]
    (db/call-with-custody {:seon.db/connection connection}
     (fn []
     (support/seed-cluster! connection "acquisition")
     (let [branch (get-in @connection [:config :branch])]
       (doseq [agent-id ["acquisition-a" "acquisition-b" "acquisition-c"]]
         (support/transacted! connection
           (agent/creation-tx {:seon.agent/id agent-id
                              :seon.agent/branch (if (= agent-id "acquisition-a") :acquisition-owned-a branch)
                              :seon.ns/name 'seon.sci.eval-test
                              :seon.cluster/name "acquisition"}))))
     (let [ctx (support/fork-cluster-ctx connection "acquisition")
           handle {:seon.db/connection connection :seon.cluster/name "acquisition"
                   :seon.sci.eval/ctx ctx :seon.agent/routing (agent/routing)
                   :seon.sci.admit/caps (config/result-caps config/defaults)
                   :seon.config.eval/time-limit-ms 5000
                   :seon.env/environment (env/of ctx) :seon.store/store held}
           handles (atom [])]
       (try
         (let [started (System/nanoTime)
               acquisition (measured #(agent/acquire-context! handle "acquisition-a"))
               a (:seon.proof/value acquisition)
               _ (swap! handles conj a)
               b (agent/acquire-context! handle "acquisition-b")
               c (agent/acquire-context! handle "acquisition-c")
               owned (:seon.agent/branch a)
               before (registry/roster held)]
           (println "C2-ACQUIRE" (:seon.proof/ms acquisition) "ms"
                    (:seon.proof/heap-delta-bytes acquisition) "observed heap delta bytes")
           (is (= {:seon.agent/branch owned :seon.agent/mode :isolated}
                  (:seon.sci.admit/value (evaluate! a "(my.agent/branch)"))))
           (is (= :live (:seon.agent/mode
                         (:seon.sci.admit/value (evaluate! b "(my.agent/branch)")))))
           (is (contains? before owned))
           (is (:seon.agent/owns-branch? a))
           (is (:seon.agent/owns-connection? a))
           (is (not (identical? connection (:seon.db/connection a))))
           (is (identical? connection (:seon.db/connection b)))
           (is (identical? (:seon.db/connection a)
                          (:seon.db/connection (env/of (:seon.sci.eval/ctx a)))))
           (declare! a "(defn ^{:malli/schema [:=> [:cat :map] :boolean]} cut? [evaluation] true)")
           (is (true? (:seon.sci.admit/value (evaluate! a "(cut? {})"))))
           (is (false? (:seon.sci.admit/value (evaluate! b "(cut? {})"))))
           (is (false? (:seon.sci.admit/value (evaluate! c "(failed? {})"))))
           (declare! b "(defn ^{:malli/schema [:=> [:cat :map] :boolean]} failed? [evaluation] true)")
           (doseq [agent-id ["acquisition-b" "acquisition-c"]]
             (let [live (agent/acquire-context! handle agent-id)]
               (is (true? (:seon.sci.admit/value (evaluate! live "(failed? {})"))))))
           (is (false? (:seon.sci.admit/value (evaluate! a "(failed? {})"))))
           (let [installs (atom 0)
                 original sci/install-row!
                 repeat-acquisition
                 (with-redefs [sci/install-row! (fn [request] (swap! installs inc) (original request))]
                   (measured #(agent/acquire-context!
                               (agent/acquire-context! handle "acquisition-c") "acquisition-c")))]
             (is (zero? @installs))
             (println "C2-UNCHANGED" (:seon.proof/ms repeat-acquisition) "ms" @installs "installed entities"))
           (println "C2-VISIBILITY" (/ (- (System/nanoTime) started) 1e6) "ms")
           (let [release (measured #(agent/release-context! a))]
             (println "C2-RELEASE" (:seon.proof/ms release) "ms"))
           (reset! handles [])
           (is (not (contains? (registry/roster held) owned)))
           (is (some? (db/pull (db/db connection) '[:seon.agent/id] [:seon.agent/id "acquisition-b"])))
           (agent/release-context! b)
           (is (some? (db/commit-id (db/db connection))))
           (let [roster (registry/roster held)
                 failure-start (System/nanoTime)
                 failure (try
                           (with-redefs [store/open-branch!
                                         (fn [_ _] (throw (ex-info "forced open failure" {:seon.proof/forced true})))]
                             (agent/acquire-context! handle "acquisition-a" {:seon.agent/isolate? true}))
                           (catch clojure.lang.ExceptionInfo failure (ex-data failure)))]
             (println "C2-FAILURE-UNWIND" (/ (- (System/nanoTime) failure-start) 1e6) "ms")
             (is (:seon.proof/forced failure))
             (is (= roster (registry/roster held)))))
         (finally
           (doseq [handle @handles] (agent/release-context! handle))
           nil))))))))
