(ns seon.operator-test
  "The in-JVM operator surface stays a thin, error-valued delegation."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [seon.cluster :as cluster]
            [seon.cluster.registry :as registry]
            [seon.instrument :as instrument]
            [seon.operator :as operator]
            [seon.operator.runtime :as runtime]
            [seon.test-support :as test-support]))

(defn- caught
  [f]
  (try
    (f)
    nil
    (catch Throwable error
      error)))

(defn- owned-root
  []
  (let [root (str "tmp/operator-test/" (random-uuid))]
    (.mkdirs (io/file root))
    (.getCanonicalPath (io/file root))))

(deftest external-existence-survives-the-target-it-describes
  (let [repository-root (owned-root)
        managed-root (str (io/file repository-root "experiment"))]
    (try
      (operator/claim-root!
       {:seon.operator/repository-root repository-root
        :seon.operator/managed-root managed-root
        :seon.operator/ephemeral? true
        :seon.boot/cluster-name "dead-target"})
      (is (false? (.exists (io/file managed-root))))
      (let [claim (-> (operator/existence
                       {:seon.operator/repository-root repository-root})
                      :seon.operator/roots first)]
        (is (= (.getCanonicalPath (io/file managed-root))
               (:seon.operator.claim/root claim)))
        (is (= #{"dead-target"} (:seon.operator.claim/clusters claim)))
        (is (true? (:seon.operator.claim/ephemeral? claim)))
        (is (true? (:seon.operator.claim/reap-on-owner-exit? claim)))
        (is (false? (:seon.operator.claim/live? claim)))
        (is (= :file
               (get-in claim
                       [:seon.operator.claim/store :seon.store/backend])))
        (is (uuid? (get-in claim
                           [:seon.operator.claim/store :seon.store/id]))))
      (finally
        (test-support/delete-recursively! repository-root)))))

(deftest cleanup-is-complete-truthful-and-never-follows-a-symlink
  (let [repository-root (owned-root)
        managed-root (str (io/file repository-root "managed"))
        cluster-root (io/file managed-root "data" "clusters")
        sentinel-root (io/file repository-root "sentinel")
        sentinel (io/file sentinel-root "survives.txt")
        link (io/file cluster-root "scratch-link")]
    (try
      (.mkdirs (io/file cluster-root "default" "logs"))
      (.mkdirs (io/file cluster-root "store"))
      (spit (io/file cluster-root "default" "logs" "seon.log") "evidence")
      (spit (io/file cluster-root "store" "object.ksv") "stored")
      (.mkdirs sentinel-root)
      (spit sentinel "alive")
      (java.nio.file.Files/createSymbolicLink
       (.toPath link) (.toPath (.getCanonicalFile sentinel-root))
       (make-array java.nio.file.attribute.FileAttribute 0))
      (operator/claim-root!
       {:seon.operator/repository-root repository-root
        :seon.operator/managed-root managed-root})
      (let [observed (operator/observe-footprint!
                      {:seon.operator/repository-root repository-root
                       :seon.operator/managed-root managed-root
                       :seon.config.maintenance/min-usable-bytes 1
                       :seon.config.maintenance/min-usable-ratio 0.0})
            result (operator/cleanup-root!
                    {:seon.operator/repository-root repository-root
                     :seon.operator/managed-root managed-root})]
        (is (pos? (:seon.operator.footprint/file-bytes observed)))
        (is (= (dissoc observed :seon.operator/low-space?)
               (-> (operator/existence
                    {:seon.operator/repository-root repository-root})
                   :seon.operator/roots first
                   :seon.operator.claim/footprint)))
        (is (true? (:seon.operator.cleanup/complete? result)))
        (is (empty? (:seon.operator.cleanup/remaining result)))
        (is (false? (.exists cluster-root)))
        (is (= "alive" (slurp sentinel))))
      (finally
        (test-support/delete-recursively! repository-root)))))

(deftest live-log-inode-is-bounded-and-archived
  (let [root (owned-root)
        log-dir (io/file root "logs")
        log-file (io/file log-dir "seon.log")]
    (try
      (.mkdirs log-dir)
      (spit log-file (apply str (repeat 32 "x")))
      (let [result (operator/rotate-logs!
                    {:seon.boot/log-dir (.getCanonicalPath log-dir)
                     :seon.config.maintenance/log-max-bytes 16
                     :seon.config.maintenance/log-retained-files 1})]
        (is (true? (:seon.operator.log/rotated? result)))
        (is (zero? (.length log-file)))
        (is (= 32 (.length (io/file (str (.getCanonicalPath log-file) ".1"))))))
      (finally
        (test-support/delete-recursively! root)))))

(deftest start-refusals-are-flat-and-never-stop-the-running-instance
  (let [stop-calls (atom [])
        existing {:seon.boot/config
                  {:seon.boot/cluster-name "already-running"}}
        failure-data {:seon.error/kind :seon.boot/refused
                      :seon.boot/offense
                      {:seon.boot/cluster-name "already-running"}
                      :seon.boot/instance existing}]
    (with-redefs [cluster/start!
                  (fn [_]
                    (throw (ex-info "The cluster already has an instance."
                                    failure-data)))
                  cluster/stop! (fn [instance] (swap! stop-calls conj instance))]
      (let [result (operator/start!
                    {:seon.boot/cluster-name "already-running"})]
        (is (= :seon.boot/refused (:seon.error/kind result)))
        (is (= "The cluster already has an instance."
               (:seon.error/message result)))
        (is (identical? existing
                        (get-in result
                                [:seon.error/data :seon.boot/instance])))
        (is (empty? @stop-calls)
            "a refused or degraded boot is left up for diagnosis")))))

(deftest lifecycle-verbs-only-call-their-delegates
  (let [request {:seon.boot/cluster-name "second"
                 :seon.boot/root "tmp/operator-test"}
        original {:seon.boot/config request}
        replacement {:seon.boot/config request :seon.boot/ready-ms 1}
        calls (atom [])]
    (with-redefs [cluster/start!
                  (fn [value]
                    (swap! calls conj [:start value])
                    replacement)
                  cluster/stop!
                  (fn [value]
                    (swap! calls conj [:stop value])
                    nil)
                  cluster/refork!
                  (fn [value]
                    (swap! calls conj [:refork value])
                    {:seon.store/branch :cluster-second
                     :seon.cluster/created? true})]
      (is (identical? replacement (operator/start! request)))
      (is (nil? (operator/stop! original)))
      (is (identical? replacement (operator/restart! original)))
      (is (= {:seon.store/branch :cluster-second
              :seon.cluster/created? true}
             (operator/refork! original)))
      (is (= [[:start request]
              [:stop original]
              [:stop original]
              [:start request]
              [:refork original]]
             @calls)))))

(deftest status-banner-and-census-derive-current-runtime-values
  (let [instances-before @runtime/running-instances
        stores-before @runtime/root-store-holder
        store-a {:seon.operator-test/store :a}
        store-b {:seon.operator-test/store :b}
        ready-a {:seon.boot/cluster-name "a"
                 :seon.boot/pid 1
                 :seon.boot/prepl-port 1001
                 :seon.cluster.agent/count 0
                 :seon.problems/problems {}}
        ready-b (assoc ready-a
                       :seon.boot/cluster-name "b"
                       :seon.boot/prepl-port 1002)]
    (try
      (reset! runtime/running-instances
              {"b" {:seon.boot/advertisement
                    {:seon.boot/cluster-name "b"
                     :seon.boot/prepl-host "127.0.0.1"
                     :seon.boot/prepl-port 1002
                     :seon.boot/pid 1
                     :seon.boot/start-instant (java.util.Date. 0)}}
               "a" {:seon.boot/advertisement
                    {:seon.boot/cluster-name "a"
                     :seon.boot/prepl-host "127.0.0.1"
                     :seon.boot/prepl-port 1001
                     :seon.boot/pid 1
                     :seon.boot/start-instant (java.util.Date. 0)}}})
      (reset! runtime/root-store-holder
              {"a" {:seon.store/store store-a}
               "b" {:seon.store/store store-b}})
      (with-redefs [cluster/mcp-runtime-observation
                    (fn [cluster-name]
                      {:seon.dev.mcp/cluster cluster-name
                       :seon.dev.mcp/health :observed
                       :seon.dev.mcp/flow
                       {:seon.oversight/plumbing cluster-name}
                       :seon.dev.mcp/readiness
                       (if (= "a" cluster-name) ready-a ready-b)})
                    cluster/banner
                    (fn [ready] (str "ready " (:seon.boot/cluster-name ready)))
                    registry/roster
                    (fn [store]
                      (if (= store store-a)
                        #{:current-src :cluster-a}
                        #{:current-src :cluster-b}))]
        (testing "status is ordered and freshly derived"
          (is (= ["a" "b"]
                 (mapv :seon.boot/cluster-name
                       (:seon.operator/clusters (operator/status))))))
        (testing "banner delegates each derived readiness value"
          (is (= "ready a\n\nready b" (operator/banner))))
        (testing "clusters joins only advertisements and registry facts"
          (let [census (operator/clusters)]
            (is (= ["a" "b"]
                   (mapv :seon.boot/cluster-name
                         (:seon.operator/advertisements census))))
            (is (= #{:current-src :cluster-a :cluster-b}
                   (:seon.operator/branches census))))))
      (finally
        (reset! runtime/running-instances instances-before)
        (reset! runtime/root-store-holder stores-before)))))

(deftest publication-delegates-complete-and-incremental-requests
  (let [calls (atom [])
        published {:seon.source/branch :current-src
                   :seon.source/commit-id (random-uuid)
                   :seon.source/digest
                   "0000000000000000000000000000000000000000000000000000000000000000"
                   :seon.source/built? true}]
    (with-redefs [cluster/refresh-source!
                  (fn
                    ([root]
                     (swap! calls conj [root])
                     published)
                    ([root paths]
                     (swap! calls conj [root paths])
                     published))]
      (is (= published (operator/publish! {:seon.boot/root "root"})))
      (is (= published
             (operator/publish!
              {:seon.boot/root "root"
               :seon.operator/changed-paths ["src/seon/operator.clj"]})))
      (is (= [["root"] ["root" ["src/seon/operator.clj"]]] @calls)))))

(deftest public-contracts-refuse-invalid-input-and-output
  (let [delegate-calls (atom 0)]
    (try
      (instrument/apply! {:seon.config/on-core-error :panic})
      (with-redefs [cluster/start!
                    (fn [_]
                      (swap! delegate-calls inc)
                      :not-an-instance)]
        (testing "invalid input is refused before delegation"
          (let [failure (caught #(operator/start!
                                 {:seon.boot/cluster-name 42}))]
            (is (= :seon.instrument/contract-violated
                   (:seon.error/kind (ex-data failure))))
            (is (= 0 @delegate-calls))))
        (testing "invalid delegate output is refused at the public boundary"
          (let [failure (caught #(operator/start!
                                 {:seon.boot/cluster-name "valid"}))]
            (is (= :seon.instrument/contract-violated
                   (:seon.error/kind (ex-data failure))))
            (is (= 1 @delegate-calls)))))
      (finally
        (instrument/remove!)))))
