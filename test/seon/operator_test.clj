(ns seon.operator-test
  "The in-JVM operator surface stays a thin, error-valued delegation."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.instrument :as mi]
            [seon.cluster :as cluster]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.db :as db]
            [seon.instrument]
            [seon.operator :as operator]
            [seon.operator.runtime :as runtime]
            [seon.operator.state :as operator.state]
            [seon.test-support :as test-support])
  (:import [java.util.concurrent CountDownLatch]))

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

(defn- custody-instance
  [cluster-name connection]
  {:seon.cluster.loop/cluster
   {:seon.env/environment
    (test-support/environment cluster-name connection)}})

(deftest cluster-address-admission-is-derived-from-schema-and-filesystem
  (let [root (owned-root)
        cluster-root (str (io/file root "data" "clusters"))]
    (try
      (doseq [cluster-name ["." ".." "nested/name" "nested\\name"]]
        (let [result (operator/start!
                      {:seon.boot/cluster-name cluster-name
                       :seon.boot/root cluster-root})]
          (is (= :seon.boot/refused (:seon.error/kind result)))
          (is (= cluster-name
                 (get-in result
                         [:seon.boot/offense
                          :seon.boot/value
                          :seon.boot/cluster-name])))))
      (is (= "store"
             (:seon.boot/cluster-name
              (cluster/resolve-bootstrap
               {:seon.boot/cluster-name "store"
                :seon.boot/root cluster-root}))))
      (.mkdirs (io/file cluster-root))
      (let [collision (io/file cluster-root "occupied")]
        (spit collision "not a cluster")
        (let [result (operator/start!
                      {:seon.boot/cluster-name "occupied"
                       :seon.boot/root cluster-root})]
          (is (= :seon.boot/refused (:seon.error/kind result)))
          (is (= :seon.cluster/non-cluster-target
                 (get-in result [:seon.boot/offense :seon.boot/rule])))
          (is (= (.toString (.toPath collision))
                 (get-in result
                         [:seon.boot/offense :seon.boot/cluster-dir])))
          (is (= "not a cluster" (slurp collision)))))
      (finally
        (test-support/delete-recursively! root)))))

(deftest development-connection-selects-the-sole-or-named-cluster
  (test-support/with-database
   (fn [connection-a]
     (test-support/with-database
      (fn [connection-b]
        (with-redefs [runtime/running-instances
                      (atom {"alpha" (custody-instance "alpha" connection-a)})]
          (is (identical? connection-a (operator/connection)))
          (is (int? (db/basis-t (db/db (operator/connection))))))
        (with-redefs [runtime/running-instances
                      (atom {"alpha" (custody-instance "alpha" connection-a)
                             "beta" (custody-instance "beta" connection-b)})]
          (is (identical? connection-b (operator/connection "beta")))))))))

(deftest development-connection-refuses-ambiguous-custody-with-the-list
  (test-support/with-database
   (fn [connection]
     (with-redefs [runtime/running-instances
                   (atom {"beta" (custody-instance "beta" connection)
                          "alpha" (custody-instance "alpha" connection)})]
       (let [result (operator/connection)]
         (is (= :seon.operator/ambiguous-cluster-custody
                (:seon.error/kind result)))
         (is (= ["alpha" "beta"]
                (get-in result
                        [:seon.error/data
                         :seon.operator/candidate-clusters])))
         (is (str/includes? (:seon.error/message result)
                            "[\"alpha\" \"beta\"]")))))))

(deftest external-existence-survives-the-target-it-describes
  (let [repository-root (owned-root)
        managed-root (str (io/file repository-root "experiment"))]
    (try
      (operator/claim-root!
        {:seon.operator/repository-root repository-root
        :seon.operator/managed-root managed-root
        :seon.operator/ephemeral-owner
        (operator.state/current-process-identity)
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
        (is (= (operator.state/current-process-identity)
               (:seon.operator.claim/creator claim)))
        (is (false? (:seon.operator.claim/live? claim)))
        (is (= :file
               (get-in claim
                       [:seon.operator.claim/store :seon.store/backend])))
        (is (uuid? (get-in claim
                           [:seon.operator.claim/store :seon.store/id]))))
      (finally
        (test-support/delete-recursively! repository-root)))))

(deftest ephemeral-creator-is-explicit-stable-and-live-at-publication
  (let [repository-root (owned-root)
        managed-root (str (io/file repository-root "ephemeral"))
        owner (operator.state/current-process-identity)]
    (try
      (is (map? (operator/claim-root!
                 {:seon.operator/repository-root repository-root
                  :seon.operator/managed-root managed-root
                  :seon.operator/ephemeral-owner owner})))
      (is (map? (operator/claim-root!
                 {:seon.operator/repository-root repository-root
                  :seon.operator/managed-root managed-root})))
      (let [claim (-> (operator/existence
                       {:seon.operator/repository-root repository-root})
                      :seon.operator/roots first)]
        (is (= owner (:seon.operator.claim/creator claim)))
        (is (true? (:seon.operator.claim/ephemeral? claim))))
      (is (= :seon.operator/ephemeral-owner-not-alive
             (:seon.error/kind
              (operator/claim-root!
               {:seon.operator/repository-root repository-root
                :seon.operator/managed-root (str managed-root "-dead")
                :seon.operator/ephemeral-owner
                {:seon.boot/pid Long/MAX_VALUE
                 :seon.boot/start-instant (java.util.Date.)}}))))
      (finally
        (test-support/delete-recursively! repository-root)))))

(deftest root-claim-refuses-a-live-creator-and-supersedes-a-dead-one
  (let [repository-root (owned-root)
        managed-root (str (io/file repository-root "superseded"))
        durable-root (str (io/file repository-root "durable"))
        owner (.start (ProcessBuilder. ^java.util.List ["/bin/sleep" "60"]))]
    (try
      (let [owner-identity
            {:seon.boot/pid (.pid owner)
             :seon.boot/start-instant
             (operator.state/process-start-instant (.pid owner))}]
        (operator/claim-root!
         {:seon.operator/repository-root repository-root
          :seon.operator/managed-root durable-root})
        (is (false?
             (:seon.operator.claim/ephemeral?
              (operator/claim-root!
               {:seon.operator/repository-root repository-root
                :seon.operator/managed-root durable-root
                :seon.operator/ephemeral-owner
                (operator.state/current-process-identity)})))
            "a durable lifecycle cannot be relabeled ephemeral")
        (is (map? (operator/claim-root!
                   {:seon.operator/repository-root repository-root
                    :seon.operator/managed-root managed-root
                    :seon.operator/ephemeral-owner owner-identity})))
        (is (= :seon.operator/root-creator-mismatch
               (:seon.error/kind
                (operator/claim-root!
                 {:seon.operator/repository-root repository-root
                  :seon.operator/managed-root managed-root}))))
        (.destroyForcibly owner)
        (.waitFor owner)
        (let [claim (operator/claim-root!
                     {:seon.operator/repository-root repository-root
                      :seon.operator/managed-root managed-root})
              supersession (-> claim
                               :seon.operator.claim/supersessions
                               last)]
          (is (= (operator.state/current-process-identity)
                 (:seon.operator.claim/creator claim)))
          (is (false? (:seon.operator.claim/ephemeral? claim)))
          (is (false? (:seon.operator.claim/reap-on-owner-exit? claim)))
          (is (= owner-identity
                 (:seon.operator.claim/creator supersession)))
          (is (true? (:seon.operator.claim/ephemeral? supersession)))
          (is (inst? (:seon.operator.claim/superseded-at supersession)))))
      (finally
        (when (.isAlive owner) (.destroyForcibly owner))
        (test-support/delete-recursively! repository-root)))))

(deftest reaper-removes-dead-owner-roots-including-a-vanished-root
  (let [repository-root (owned-root)
        caller-root (str (io/file repository-root "caller"))
        abandoned-root (str (io/file repository-root "abandoned"))
        vanished-root (str (io/file repository-root "vanished"))
        owner (.start (ProcessBuilder. ^java.util.List ["/bin/sleep" "60"]))]
    (try
      (let [owner-identity
            {:seon.boot/pid (.pid owner)
             :seon.boot/start-instant
             (operator.state/process-start-instant (.pid owner))}]
        (operator/claim-root!
         {:seon.operator/repository-root repository-root
          :seon.operator/managed-root abandoned-root
          :seon.operator/ephemeral-owner owner-identity})
        (operator/claim-root!
         {:seon.operator/repository-root repository-root
          :seon.operator/managed-root vanished-root
          :seon.operator/ephemeral-owner owner-identity})
        (.mkdirs (io/file abandoned-root "data" "clusters" "default"))
        (spit (io/file abandoned-root "data" "clusters" "default" "proof")
              "abandoned")
        (.destroyForcibly owner)
        (.waitFor owner)
        (let [result (operator/reap-dead-roots!
                      {:seon.operator/repository-root repository-root
                       :seon.operator/managed-root caller-root
                       :seon.config.operator/event-silence-backstop-ms 1000})]
          (is (true? (:seon.operator.reap/complete? result)))
          (is (= #{(.getCanonicalPath (io/file abandoned-root))
                   (.getCanonicalPath (io/file vanished-root))}
                 (into #{} (map :seon.operator.claim/root)
                       (:seon.operator.reap/roots result))))
          (is (false? (.exists (io/file abandoned-root "data" "clusters"))))))
      (finally
        (when (.isAlive owner) (.destroyForcibly owner))
        (test-support/delete-recursively! repository-root)))))

(deftest exact-stop-refuses-a-changed-generation-without-signaling-the-pid
  (let [repository-root (owned-root)
        identity (operator.state/current-process-identity)
        generation (random-uuid)
        record (merge identity
                      {:seon.operator.process-record/generation generation
                       :seon.operator.process-record/root repository-root})]
    (try
      (operator.state/write-process-claim! repository-root record)
      (let [failure
            (caught
             #(operator.state/stop-recorded-process-under-lock!
               repository-root
               (assoc record :seon.boot/start-instant (java.util.Date. 0))
               [] 10))]
        (is (= :seon.operator/process-claim-mismatch
               (:seon.error/kind (ex-data failure))))
        (is (operator.state/process-identity-alive? identity)))
      (finally
        (test-support/delete-recursively! repository-root)))))

(deftest reaper-refuses-an-observed-process-without-an-exact-claim
  (let [repository-root (owned-root)
        caller-root (str (io/file repository-root "caller"))
        managed-root (str (io/file repository-root "refused"))
        owner (.start (ProcessBuilder. ^java.util.List ["/bin/sleep" "60"]))]
    (try
      (let [owner-identity
            {:seon.boot/pid (.pid owner)
             :seon.boot/start-instant
             (operator.state/process-start-instant (.pid owner))}]
        (operator/claim-root!
         {:seon.operator/repository-root repository-root
          :seon.operator/managed-root managed-root
          :seon.operator/ephemeral-owner owner-identity})
        (.mkdirs (io/file managed-root "data" "clusters"))
        (.destroyForcibly owner)
        (.waitFor owner)
        (with-redefs [operator.state/observed-property-processes
                      (fn []
                        [{:seon.operator.state/root
                          (.getCanonicalPath (io/file managed-root))
                          :seon.boot/pid (:seon.boot/pid
                                          (operator.state/current-process-identity))
                          :seon.boot/start-instant
                          (:seon.boot/start-instant
                           (operator.state/current-process-identity))}])]
          (let [result (operator/reap-dead-roots!
                        {:seon.operator/repository-root repository-root
                         :seon.operator/managed-root caller-root
                         :seon.config.operator/event-silence-backstop-ms 1000})]
            (is (= :seon.operator/reap-incomplete
                   (:seon.error/kind result)))
            (is (= :seon.operator.reap/unclaimed-process
                   (-> result :seon.error/data
                       :seon.operator.reap/result
                       :seon.operator.reap/refused first
                       :seon.operator.reap/reason)))
            (is (.exists (io/file managed-root "data" "clusters"))))))
      (finally
        (when (.isAlive owner) (.destroyForcibly owner))
        (test-support/delete-recursively! repository-root)))))

(deftest cleanup-is-complete-truthful-and-never-follows-a-symlink
  (let [repository-root (owned-root)
        managed-root (str (io/file repository-root "managed"))
        cluster-root (io/file managed-root "data" "clusters")
        store-dir (io/file managed-root "data" "store")
        store-lock (io/file managed-root "data" "store.lock")
        blob-staging (io/file managed-root "data" "blob-staging")
        sentinel-root (io/file repository-root "sentinel")
        sentinel (io/file sentinel-root "survives.txt")
        link (io/file cluster-root "scratch-link")]
    (try
      (.mkdirs (io/file cluster-root "default" "logs"))
      (.mkdirs store-dir)
      (.mkdirs blob-staging)
      (spit (io/file cluster-root "default" "logs" "seon.log") "evidence")
      (spit (io/file store-dir "object.ksv") "stored")
      (spit store-lock "locked")
      (spit (io/file blob-staging "pending") "staged")
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
        (is (false? (.exists store-dir)))
        (is (false? (.exists store-lock)))
        (is (false? (.exists blob-staging)))
        (is (= "alive" (slurp sentinel))))
      (finally
        (test-support/delete-recursively! repository-root)))))

(deftest cluster-cleanup-uses-one-stop-retire-delete-and-collect-composition
  (let [repository-root (owned-root)
        managed-root (str (io/file repository-root "managed-cluster"))
        cluster-name "doomed"
        cluster-root (io/file managed-root "data" "clusters")
        cluster-dir (io/file cluster-root cluster-name)
        sentinel-root (io/file repository-root "cluster-sentinel")
        sentinel (io/file sentinel-root "survives.txt")
        foreign-claim
        (io/file repository-root "data" "operator" "claims" "roots"
                 "unrelated-invalid.edn")
        calls (atom [])]
    (try
      (.mkdirs cluster-dir)
      (.mkdirs sentinel-root)
      (spit sentinel "alive")
      (.mkdirs (.getParentFile foreign-claim))
      (spit foreign-claim "{:unrelated true}\n")
      (java.nio.file.Files/createSymbolicLink
       (.toPath (io/file cluster-dir "outside"))
       (.toPath (.getCanonicalFile sentinel-root))
       (make-array java.nio.file.attribute.FileAttribute 0))
      (operator/claim-root!
       {:seon.operator/repository-root repository-root
        :seon.operator/managed-root managed-root
        :seon.boot/cluster-name cluster-name})
      (let [operation-store
            (store/open-store!
             {:seon.store/dir (str (io/file managed-root "data" "store"))})
            instance {:seon.boot/config
                      {:seon.boot/cluster-name cluster-name}}
            sweeps (atom [3 0])]
        (try
          (with-redefs [runtime/running-instances (atom {cluster-name instance})
                        cluster/stop!
                        (fn [value] (swap! calls conj [:stop value]))
                        registry/retire-branch!
                        (fn [request] (swap! calls conj [:retire request]))
                        registry/collect!
                        (fn [store _]
                          (swap! calls conj [:collect store])
                          (let [swept (first @sweeps)]
                            (swap! sweeps next)
                            swept))
                        registry/roster (fn [_] #{})]
            (let [result
                  (operator/cleanup-cluster!
                   {:seon.operator/repository-root repository-root
                    :seon.operator/managed-root managed-root
                    :seon.boot/cluster-name cluster-name
                    :seon.store/store operation-store})]
              (is (true?
                   (:seon.operator.cluster-cleanup/complete? result)))
              (is (true?
                   (:seon.operator.cluster-cleanup/live-instance-stopped?
                    result)))
              (is (= 3
                     (get-in
                      result
                      [:seon.operator.cluster-cleanup/collection
                       :seon.operator.collect/swept-objects])))
              (is (= [:stop :retire :collect :collect]
                     (mapv first @calls)))
              (is (false? (.exists cluster-dir)))
              (is (= "alive" (slurp sentinel)))))
          (finally
            (store/release-store! operation-store))))
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

(deftest process-census-derives-exact-identity-classifications
  (let [observed-at (java.util.Date. 1785945600000)
        started-at (java.util.Date. 1785942000000)
        generation (random-uuid)
        claim-id (random-uuid)
        root (.getCanonicalPath (io/file "tmp/operator-census"))
        observations
        {:seon.operator.state/observed-at observed-at
         :seon.operator.state/roots
         [{:seon.operator.claim/id claim-id
           :seon.operator.claim/root root
           :seon.operator.claim/creator
           {:seon.boot/pid 41 :seon.boot/start-instant started-at}
           :seon.operator.claim/reap-on-owner-exit? true}]
         :seon.operator.state/processes
         [{:seon.operator.state/root root
           :seon.operator.state/generation generation
           :seon.boot/pid 42
           :seon.boot/start-instant started-at
           :seon.operator.state/alive? true
           :seon.operator.state/responsive? false
           :seon.operator.state/advertisements
           [{:seon.operator.state/name "default"}]}]
         :seon.operator.state/unclaimed
         [{:seon.operator.state/root root
           :seon.boot/pid 43
           :seon.boot/start-instant started-at}]
         :seon.operator.state/claim-errors []}]
    (with-redefs [operator.state/census-observations (constantly observations)]
      (let [result
            (operator/census-processes!
             {:seon.operator/repository-root "."
              :seon.operator/managed-root root})
            process (first (:seon.operator.process-census/processes result))]
        (is (true? (:seon.operator.process-census/complete? result)))
        (is (= generation (:seon.dev.process/generation process)))
        (is (= 42 (:seon.dev.process/pid process)))
        (is (true? (:seon.operator.process-census/alive? process)))
        (is (false? (:seon.operator.process-census/responsive? process)))
        (is (= ["default"]
               (:seon.operator.process-census/advertisements process)))
        (is (= [42]
               (mapv :seon.dev.process/pid
                     (:seon.operator.process-census/unresponsive result))))
        (is (= [43]
               (mapv :seon.dev.process/pid
                     (:seon.operator.process-census/unclaimed result))))
        (is (= claim-id
               (get-in result
                       [:seon.operator.process-census/roots 0
                        :seon.operator.claim/id])))))))

(deftest process-census-refuses-unreadable-external-claims-as-data
  (let [claim-error
        {:seon.error/kind :seon.operator/unreadable-claim
         :seon.error/message "Unreadable claim."
         :seon.error/data {:seon.operator.claim/path "claim.edn"}}
        observations
        {:seon.operator.state/observed-at (java.util.Date. 1785945600000)
         :seon.operator.state/roots []
         :seon.operator.state/processes []
         :seon.operator.state/unclaimed []
         :seon.operator.state/claim-errors [claim-error]}]
    (with-redefs [operator.state/census-observations (constantly observations)]
      (let [result
            (operator/census-processes!
             {:seon.operator/repository-root "."
              :seon.operator/managed-root "tmp/operator-census"})]
        (is (= :seon.operator/process-census-incomplete
               (:seon.error/kind result)))
        (is (= [claim-error]
               (get-in result
                       [:seon.error/data
                        :seon.operator.process-census/result
                        :seon.operator.process-census/claim-errors])))))))

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
                    nil)]
      (is (identical? replacement (operator/start! request)))
      (is (nil? (operator/stop! original)))
      (is (identical? replacement (operator/restart! original)))
      (is (= [[:start request]
              [:stop original]
              [:stop original]
              [:start request]]
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

(deftest collection-reports-and-verifies-the-exact-store
  (let [repository-root (owned-root)
        managed-root (.getCanonicalPath
                      (io/file repository-root "managed"))]
    (try
      (let [result
            (operator/collect!
             {:seon.operator/repository-root repository-root
              :seon.operator/managed-root managed-root})]
        (is (uuid? (:seon.operator.collect/store-id result)))
        (is (= managed-root
               (:seon.operator.collect/managed-root result)))
        (is (= [:db]
               (mapv :seon.store/branch
                     (:seon.operator.collect/branches result))))
        (is (every? uuid?
                    (map :seon.source/commit-id
                         (:seon.operator.collect/branches result))))
        (is (<= (:seon.operator.collect/objects-after result)
                (:seon.operator.collect/objects-before result)))
        (is (<= (:seon.operator.collect/bytes-after result)
                (:seon.operator.collect/bytes-before result)))
        (is (zero?
             (:seon.operator.collect/verification-pass-swept result)))
        (is (true? (:seon.operator.collect/complete? result))))
      (finally
        (test-support/delete-recursively! repository-root)))))

(deftest scheduled-collection-yields-the-installation-control-lock
  (let [repository-root (owned-root)
        managed-root (.getCanonicalPath
                      (io/file repository-root "managed"))
        collection-entered (CountDownLatch. 1)
        release-collection (CountDownLatch. 1)
        collect-calls (atom 0)]
    (try
      (with-redefs
       [registry/collect!
        (fn [_ _]
          (when (= 1 (swap! collect-calls inc))
            (.countDown collection-entered)
            (test-support/await-event!
             release-collection
             :release-held-collection))
          0)]
        (let [collection
              (future
                (operator/collect!
                 {:seon.operator/repository-root repository-root
                  :seon.operator/managed-root managed-root}))]
          (try
            (test-support/await-event!
             collection-entered
             :collection-holds-its-root-lock)
            (let [claim
                  (operator.state/with-lifecycle-lock!
                   {:seon.operator.lock/path
                    (operator.state/control-lock-path repository-root)
                    :seon.operator.lock/command "test root claim"
                    :seon.operator.lock/timeout-ms 1000}
                   #(operator.state/claim-root-under-lock!
                     repository-root managed-root nil "during-collection"))]
              (is (= #{"during-collection"}
                     (:seon.operator.claim/clusters claim))))
            (finally
              (.countDown release-collection)))
          (let [result
                (test-support/await-event!
                 collection
                 :collection-completes-after-release)]
            (is (true? (:seon.operator.collect/complete? result))))))
      (finally
        (.countDown release-collection)
        (test-support/delete-recursively! repository-root)))))

(deftest collection-dry-run-returns-the-bounded-physical-inventory
  (let [repository-root (owned-root)
        managed-root (.getCanonicalPath
                      (io/file repository-root "managed"))
        commit-id (random-uuid)
        calls (atom [])]
    (try
      (with-redefs
       [registry/collect!
        (fn [_ remove-before options]
          (swap! calls conj [remove-before options])
          {:seon.cluster.registry/branches
           [{:seon.store/branch :db
             :seon.source/commit-id commit-id}]
           :seon.cluster.registry/retained-files 2
           :seon.cluster.registry/candidate-files 3
           :seon.cluster.registry/file-bytes 700
           :seon.cluster.registry/candidate-bytes 400
           :seon.cluster.registry/mark-duration-ms 17})]
        (let [result
              (operator/collect!
               {:seon.operator/repository-root repository-root
                :seon.operator/managed-root managed-root
                :seon.operator.collect/dry-run? true})]
          (is (true? (:seon.operator.collect/dry-run? result)))
          (is (= [{:seon.store/branch :db
                   :seon.source/commit-id commit-id}]
                 (:seon.operator.collect/branches result)))
          (is (= 2 (:seon.operator.collect/retained-files result)))
          (is (= 3 (:seon.operator.collect/candidate-files result)))
          (is (= 400 (:seon.operator.collect/candidate-bytes result)))
          (is (= 17 (:seon.operator.collect/mark-duration-ms result)))
          (is (= 32
                 (:seon.operator.collect/projected-duration-ms result)))
          (is (= 5 (:seon.operator.collect/objects-before result)))
          (is (= (:seon.operator.collect/objects-before result)
                 (:seon.operator.collect/objects-after result)))
          (is (= (:seon.operator.collect/bytes-before result)
                 (:seon.operator.collect/bytes-after result)))
          (is (zero? (:seon.operator.collect/swept-objects result)))
          (is (zero? (:seon.operator.collect/reclaimed-bytes result)))
          (is (true? (:seon.operator.collect/complete? result)))
          (is (= 1 (count @calls)))
          (is (instance? java.util.Date (ffirst @calls)))
          (is (true?
               (:seon.operator.collect/dry-run? (second (first @calls)))))))
      (finally
        (test-support/delete-recursively! repository-root)))))

(deftest collection-refuses-a-nonzero-verification-pass-with-partial-evidence
  (let [repository-root (owned-root)
        managed-root (.getCanonicalPath
                      (io/file repository-root "managed"))
        sweeps (atom [2 1])]
    (try
      (with-redefs [registry/collect!
                    (fn [_ _]
                      (let [swept (first @sweeps)]
                        (swap! sweeps next)
                        swept))]
        (let [result
              (operator/collect!
               {:seon.operator/repository-root repository-root
                :seon.operator/managed-root managed-root})
              partial-result
              (get-in result
                      [:seon.error/data
                       :seon.operator.collect/result])]
          (is (= :seon.operator/collection-incomplete
                 (:seon.error/kind result)))
          (is (= 2 (:seon.operator.collect/swept-objects partial-result)))
          (is (= 1
                 (:seon.operator.collect/verification-pass-swept
                  partial-result)))
          (is (false?
               (:seon.operator.collect/complete? partial-result)))))
      (finally
        (test-support/delete-recursively! repository-root)))))

(deftest public-contracts-refuse-invalid-input-and-output
  (let [delegate-calls (atom 0)
        start-filter (mi/-filter-var #{#'operator/start!})]
    (try
      (mi/clj-collect! {:ns ['seon.operator]})
      (mi/instrument!
       {:scope #{:input :output}
        :filters [start-filter]
        :report ((ns-resolve 'seon.instrument 'throwing-report) nil)})
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
        (mi/unstrument! {:filters [start-filter]})))))
