(ns seon.dev.branch-test
  (:require [babashka.fs :as fs]
            [babashka.process :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [malli.core :as m]
            [seon.db.branch :as db.branch]
            [seon.db.protocol :as protocol]
            [seon.dev.artifact :as artifact]
            [seon.dev.branch :as branch]
            [seon.dev.config :as config]
            [seon.dev.process :as process]
            [seon.dev.process-test :as process-test]
            [seon.dev.state :as state]
            [seon.launch :as launch])
  (:import [java.util.concurrent TimeUnit]))

(deftest public-name-derives-one-source-prefixed-target
  (let [root (System/getProperty "user.dir")
        configuration (config/load! root)
        request (branch/request {::branch/configuration configuration
                                 ::branch/name "proof"})
        source-process-dir (:seon.dev.config/process-dir configuration)
        source-log-dir (:seon.dev.config/log-dir configuration)]
    (is (= "default-proof" (::branch/runtime-cluster request)))
    (is (= "default-proof" (::branch/target-database-name request)))
    (is (= :seon.branch/default-proof (::branch/target-branch request)))
    (is (= (str (fs/path source-process-dir "branches"
                         "default-proof.edn"))
           (::branch/lifecycle-path request)))
    (is (= (str (fs/path source-process-dir "branch-processes"
                         "default-proof"))
           (::branch/process-dir request)))
    (is (= (str (fs/path source-log-dir "branches" "default-proof"))
           (::branch/log-dir request)))
    (is (= 0 (::branch/http-port request)))
    (is (= (str (fs/path source-process-dir "branch-ports"
                         "default-proof.port"))
           (::branch/http-port-file request)))
    (is (= (str (fs/path root "data/branches/default-proof/blobs"))
           (::branch/writable-blob-dir request)))
    (doseq [invalid ["" "../proof" "Proof" "proof/name" "-proof" "proof-"]]
      (is (thrown? Exception
                   (branch/request {::branch/configuration configuration
                                    ::branch/name invalid}))))
    (is (= ::branch/status
           (last (:malli/schema (meta #'branch/status)))))
    (is (= [:vector ::branch/status]
           (last (:malli/schema (meta #'branch/inventory)))))))

(defn- signal-source-config [directory socket]
  (-> ((deref #'process-test/signal-fixture-config) directory)
      (assoc :seon.dev.config/request-socket socket)
      (assoc-in [:seon.dev.config/launch-descriptor
                 ::launch/writer-owner ::launch/request-socket-path]
                socket)))

(defn- signal-request [directory socket]
  {::branch/configuration (signal-source-config directory socket)
   ::branch/lifecycle-path
   (str (fs/path directory "retained" "trial.edn"))
   ::branch/runtime-cluster "trial"
   ::branch/target-database-name "trial-route"
   ::branch/target-branch :trial
   ::branch/process-dir (str (fs/path directory "trial-process"))
   ::branch/log-dir (str (fs/path directory "trial-logs"))
   ::branch/http-port 0
   ::branch/http-port-file (str (fs/path directory "trial-http.port"))
   ::branch/writable-blob-dir (str (fs/path directory "trial-blobs"))})

(defn- signal-pod-spec [request delay]
  {:seon.dev.process/id process/pod-id
   :seon.dev.process/argv
   ["python3" "-c" (deref #'process-test/pod-fixture-source)
    (::branch/http-port-file request) (str delay)]
   :seon.dev.process/environment (into {} (System/getenv))
   :seon.dev.process/dependencies []
   :seon.dev.process/http-port-file (::branch/http-port-file request)
   :seon.dev.process/readiness :seon.dev.process.readiness/pod
   :seon.dev.process/ready-timeout-ms 310000
   :seon.dev.process/shutdown-grace-ms 100
   :seon.dev.process/artifact-digest "application"})

(defn- signal-writer-path [directory]
  (str (fs/path directory "writer-state.edn")))

(defn- signal-writer! [directory]
  (let [path (signal-writer-path directory)]
    (state/write-edn! path {::branch-exists? false ::requests []})
    path))

(defn- database-value [database-name head]
  {:db-name database-name
   :store-id [(::db.branch/store-id head)
              (::db.branch/name head)
              :seon.db.id.writer/serialized]
   :t (::db.branch/basis-t head)
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id (::db.branch/commit-id head)})

(defn- signal-writer-call! [directory descriptor message]
  (let [path (signal-writer-path directory)
        lock-config {:seon.dev.config/process-dir directory}
        source-database (::launch/database descriptor)
        source-head {::db.branch/store-id
                     #uuid "ebc09a32-5450-4181-bc88-f053dcaf301f"
                     ::db.branch/name :db
                     ::db.branch/commit-id
                     #uuid "01c71e5a-ca4f-4d21-a45c-d24a07db0b84"
                     ::db.branch/basis-t 10}
        fork-head (assoc source-head ::db.branch/name :trial)
        target-connection-id [(::db.branch/store-id fork-head)
                           (::db.branch/name fork-head)]]
    (state/with-lock
     lock-config :signal-writer 5000
     (fn []
       (let [writer-state (state/read-edn path)
             branch-exists? (::branch-exists? writer-state)
             [next-branch-exists? response]
             (case (::protocol/operation message)
               :seon.db.protocol.operation/ensure-database
               [branch-exists?
                (if (= "trial-route" (::protocol/database-name message))
                  (if branch-exists?
                    {::protocol/success? true
                     ::protocol/database-name "trial-route"
                     :seon.db/db (database-value "trial-route" fork-head)
                     ::protocol/backend (::protocol/backend source-database)
                     ::protocol/database-path
                     (::protocol/database-path source-database)}
                    {::protocol/success? false
                     ::protocol/error-kind protocol/branch-missing-error
                     ::protocol/error "branch absent"})
                  {::protocol/success? true
                   ::protocol/database-name
                   (::protocol/database-name source-database)
                   :seon.db/db
                   (database-value (::protocol/database-name source-database)
                                   source-head)
                   ::protocol/backend (::protocol/backend source-database)
                   ::protocol/database-path
                   (::protocol/database-path source-database)})]

               :seon.db.protocol.operation/create-branch
               [true
                {::protocol/success? true
                 ::protocol/target-database-name "trial-route"
                 ::protocol/target-connection-id target-connection-id
                 ::protocol/branch-head fork-head
                 ::protocol/backend (::protocol/backend source-database)
                 ::protocol/database-path
                 (::protocol/database-path source-database)
                 ::protocol/created? (not branch-exists?)
                 ::protocol/adopted? branch-exists?}]

               :seon.db.protocol.operation/release-database
               [branch-exists?
                {::protocol/success? true
                 ::protocol/released? true}]

               :seon.db.protocol.operation/delete-branch
               [false
                {::protocol/success? true
                 ::protocol/target-database-name "trial-route"
                 ::protocol/target-connection-id target-connection-id
                 ::protocol/source-head source-head
                 ::protocol/released? false
                 ::protocol/deleted? true}])
             response (assoc response ::protocol/request-id
                             (::protocol/request-id message))]
         (state/write-edn!
          path
          {::branch-exists? next-branch-exists?
           ::requests (conj (::requests writer-state) message)})
         response)))))

(defn- signal-writer-state [fixture]
  (state/read-edn fixture))

(defn- with-signal-writer [directory f]
  (with-redefs-fn
    {#'branch/writer-call! (partial signal-writer-call! directory)}
    f))

(defn run-branch-signal-fixture!
  "Open one real retained pod and hold at the selected signal cut."
  [directory socket cut]
  (let [request (signal-request directory socket)
        manifest {:seon.dev.artifact/application-digest "application"}
        delay (if (#{:seon.dev.branch-test.cut/readiness
                     :seon.dev.branch-test.cut/cleanup-failure} cut)
                300
                0)
        pod (signal-pod-spec request delay)
        marker (str (fs/path directory "signal-cut"))
        spawn @#'process/spawn-detached!
        write-edn! state/write-edn!
        redefinitions
        (cond-> {#'branch/source-manifest! (constantly manifest)
                 #'branch/writer-call!
                 (partial signal-writer-call! directory)
                 #'process/specs (fn [_ _] {process/pod-id pod})}
          (= cut :seon.dev.branch-test.cut/spawn-publication)
          (assoc #'process/spawn-detached!
                 (fn [configuration spec]
                   (spit marker "spawn")
                   (Thread/sleep 500)
                   (spawn configuration spec)))

          (= cut :seon.dev.branch-test.cut/cleanup-failure)
          (assoc #'process/stop!
                 (fn [configuration id & _]
                   (when (process/read-process configuration id)
                     (throw
                      (ex-info "injected retained-pod inverse failure"
                               {:seon.dev.process/id id})))))

          (#{:seon.dev.branch-test.cut/before-spawn
             :seon.dev.branch-test.cut/ready-publication} cut)
          (assoc #'state/write-edn!
                 (fn [path value]
                   (let [written (write-edn! path value)]
                     (when (= (if (= cut :seon.dev.branch-test.cut/before-spawn)
                                :seon.dev.branch.phase/pod-starting
                                :seon.dev.branch.phase/ready)
                              (::branch/phase value))
                       (spit marker "publication")
                       (Thread/sleep 500))
                     written))))]
    (with-redefs-fn redefinitions #(branch/open! request))))

(defn- signal-owner! [root expression]
  (shell/process
   {:out :string :err :string
    :cmd ["bb" "--config" (str (fs/path root "bb.edn"))
          "--deps-root" root "-e" expression]}))

(defn- await-path! [path]
  (loop [remaining 500]
    (when (and (pos? remaining) (not (fs/exists? path)))
      (Thread/sleep 10)
      (recur (dec remaining))))
  (fs/exists? path))

(defn- await-process! [configuration]
  (loop [remaining 500]
    (or (process/read-process configuration process/pod-id)
        (when (pos? remaining)
          (Thread/sleep 10)
          (recur (dec remaining))))))

(defn- await-target-config! [request]
  (loop [remaining 500]
    (let [record (state/read-edn (::branch/lifecycle-path request))]
      (or (when-let [descriptor (::branch/launch-descriptor record)]
            (config/select-launch-descriptor
             (::branch/configuration request) descriptor))
          (when (pos? remaining)
            (Thread/sleep 10)
            (recur (dec remaining)))))))

(deftest real-sigint-closes-an-invocation-owned-branch-and-pod
  (doseq [cut [:seon.dev.branch-test.cut/before-spawn
               :seon.dev.branch-test.cut/spawn-publication
               :seon.dev.branch-test.cut/readiness]]
    (let [root (System/getProperty "user.dir")
          directory (str (fs/path root "tmp" (str "branch-sigint-"
                                                    (random-uuid))))
          _ (fs/create-dirs directory)
          socket (str (fs/path directory "writer.sock"))
          request (signal-request directory socket)
          fixture (signal-writer! directory)
          expression
          (str "(require '[seon.dev.branch-test :as t]) "
               "(t/run-branch-signal-fixture! " (pr-str directory) " "
               (pr-str socket) " " (pr-str cut) ")")
          owner (signal-owner! root expression)]
      (try
        (let [target-config (await-target-config! request)
              marker (fs/path directory "signal-cut")
              process-record
              (if (= cut :seon.dev.branch-test.cut/readiness)
                (await-process! target-config)
                (do
                  (is (await-path! marker)
                      (str (name cut) " reached its real signal cut"))
                  (process/read-process target-config process/pod-id)))]
          (when (= cut :seon.dev.branch-test.cut/readiness)
            (is (some? process-record) "readiness cut published the real pod"))
          (shell/sh {:cmd ["/bin/kill" "-INT"
                           (str (.pid ^java.lang.Process (:proc owner)))]})
          (is (.waitFor ^java.lang.Process (:proc owner)
                        10 TimeUnit/SECONDS))
          (is (= 130 (:exit @owner)))
          (is (false? (::branch-exists? (signal-writer-state fixture)))
              "the exact invocation-owned native branch is deleted")
          (is (nil? (process/read-process target-config process/pod-id)))
          (is (not (fs/exists? (::branch/lifecycle-path request))))
          (when process-record
            (is (not (state/process-identity-alive? process-record))
                "the invocation-owned pod cannot survive under PID 1"))
          (is (= [protocol/ensure-database-operation
                  protocol/create-branch-operation
                  protocol/ensure-database-operation
                  protocol/release-database-operation
                  protocol/delete-branch-operation]
                 (mapv ::protocol/operation
                       (::requests (signal-writer-state fixture))))))
        (finally
          (when (.isAlive ^java.lang.Process (:proc owner))
            (.destroyForcibly ^java.lang.Process (:proc owner)))
          (fs/delete-tree directory {:force true}))))))

(deftest real-sigint-does-not-claim-a-converged-branch-pod
  (let [root (System/getProperty "user.dir")
        directory (str (fs/path root "tmp" (str "branch-sigint-reuse-"
                                                  (random-uuid))))
        _ (fs/create-dirs directory)
        socket (str (fs/path directory "writer.sock"))
        request (signal-request directory socket)
        fixture (signal-writer! directory)
        owner (atom nil)]
    (try
      (run-branch-signal-fixture!
       directory socket :seon.dev.branch-test.cut/initial-open)
      (let [target-config (await-target-config! request)
            original (process/read-process target-config process/pod-id)
            expression
            (str "(require '[seon.dev.branch-test :as t]) "
                 "(t/run-branch-signal-fixture! " (pr-str directory) " "
                 (pr-str socket) " "
                 (pr-str :seon.dev.branch-test.cut/ready-publication) ")")
            child (signal-owner! root expression)]
        (reset! owner child)
        (is (await-path! (fs/path directory "signal-cut")))
        (shell/sh {:cmd ["/bin/kill" "-INT"
                         (str (.pid ^java.lang.Process (:proc child)))]})
        (is (.waitFor ^java.lang.Process (:proc child)
                      10 TimeUnit/SECONDS))
        (is (= 130 (:exit @child)))
        (let [retained (state/read-edn (::branch/lifecycle-path request))
              current (process/read-process target-config process/pod-id)]
          (is (::branch-exists? (signal-writer-state fixture)))
          (is (= :seon.dev.branch.state/open (::branch/desired-state retained)))
          (is (= (:seon.dev.process/pid original)
                 (:seon.dev.process/pid current)))
          (is (state/process-identity-alive? current)
              "a converged pod remains owned by its earlier invocation"))
        (with-signal-writer
          directory
          #(branch/close!
            {::branch/configuration (::branch/configuration request)
             ::branch/lifecycle-path (::branch/lifecycle-path request)})))
      (finally
        (when-let [child @owner]
          (when (.isAlive ^java.lang.Process (:proc child))
            (.destroyForcibly ^java.lang.Process (:proc child))))
        (fs/delete-tree directory {:force true})))))

(deftest failed-pod-unwind-retains-the-exact-native-branch
  (let [root (System/getProperty "user.dir")
        directory (str (fs/path root "tmp" (str "branch-sigint-failure-"
                                                  (random-uuid))))
        _ (fs/create-dirs directory)
        socket (str (fs/path directory "writer.sock"))
        request (signal-request directory socket)
        fixture (signal-writer! directory)
        expression
        (str "(require '[seon.dev.branch-test :as t]) "
             "(t/run-branch-signal-fixture! " (pr-str directory) " "
             (pr-str socket) " "
             (pr-str :seon.dev.branch-test.cut/cleanup-failure) ")")
        owner (signal-owner! root expression)]
    (try
      (let [target-config (await-target-config! request)
            pod (await-process! target-config)]
        (is (some? pod))
        (shell/sh {:cmd ["/bin/kill" "-INT"
                         (str (.pid ^java.lang.Process (:proc owner)))]})
        (is (.waitFor ^java.lang.Process (:proc owner)
                      10 TimeUnit/SECONDS))
        (let [result @owner
              retained (state/read-edn (::branch/lifecycle-path request))
              current (process/read-process target-config process/pod-id)]
          (is (= 130 (:exit result)))
          (is (str/includes? (:err result)
                             "Failed to unwind every startup resource"))
          (is (::branch-exists? (signal-writer-state fixture))
              "an uncertain process inverse forbids branch deletion")
          (is (= :seon.dev.branch.state/open (::branch/desired-state retained)))
          (is (= (:seon.dev.process/pid pod)
                 (:seon.dev.process/pid current)))
          (is (state/process-identity-alive? current)))
        (with-signal-writer
          directory
          #(branch/close!
            {::branch/configuration (::branch/configuration request)
             ::branch/lifecycle-path (::branch/lifecycle-path request)})))
      (finally
        (when (.isAlive ^java.lang.Process (:proc owner))
          (.destroyForcibly ^java.lang.Process (:proc owner)))
        (fs/delete-tree directory {:force true})))))

(deftest retained-open-retries-exact-create-and-close-uses-fresh-target-head
  (let [root (System/getProperty "user.dir")
        directory (fs/path root "tmp" (str "br-" (random-uuid)))
        _ (fs/create-dirs directory)
        socket (str (fs/path directory "writer.sock"))
        lifecycle-path (str (fs/path directory "retained" "trial.edn"))
        process-dir (str (fs/path directory "trial-process"))
        log-dir (str (fs/path directory "trial-logs"))
        http-port-file (str (fs/path directory "trial-http.port"))
        writable-blob-dir (str (fs/path directory "trial-blobs"))
        source-config
        (-> (config/load! root)
            (assoc :seon.dev.config/request-socket socket)
            (assoc-in [:seon.dev.config/launch-descriptor
                       ::launch/writer-owner ::launch/request-socket-path]
                      socket))
        source-database
        (get-in source-config [:seon.dev.config/launch-descriptor
                               ::launch/database])
        database-id #uuid "ebc09a32-5450-4181-bc88-f053dcaf301f"
        source-head {::db.branch/store-id database-id
                     ::db.branch/name :db
                     ::db.branch/commit-id
                     #uuid "01c71e5a-ca4f-4d21-a45c-d24a07db0b84"
                     ::db.branch/basis-t 10}
        fork-head (assoc source-head ::db.branch/name :trial)
        advanced-head
        (assoc fork-head
               ::db.branch/commit-id
               #uuid "0b1d206c-186b-44c8-801c-ab33d76e33ef"
               ::db.branch/basis-t 11)
        target-head (atom fork-head)
        branch-exists? (atom false)
        create-attempts (atom 0)
        create-mode (atom :drop)
        release-busy-attempts (atom 0)
        requests (atom [])
        process-events (atom [])
        stop-classification (atom :seon.dev.process.classification/absent)
        block-next-stop? (atom false)
        stop-entered (promise)
        release-stop (promise)
        write-edn! state/write-edn!
        target-name "trial-route"
        target-connection-id [(::db.branch/store-id fork-head)
                           (::db.branch/name fork-head)]
        request
        {::branch/configuration source-config
         ::branch/lifecycle-path lifecycle-path
         ::branch/runtime-cluster "trial"
         ::branch/target-database-name target-name
         ::branch/target-branch :trial
         ::branch/process-dir process-dir
         ::branch/log-dir log-dir
         ::branch/http-port 0
         ::branch/http-port-file http-port-file
         ::branch/writable-blob-dir writable-blob-dir}
        stop-request
        {::branch/configuration source-config
         ::branch/lifecycle-path lifecycle-path}
        handler
        (fn [message]
          (swap! requests conj message)
          (some->
           (case (::protocol/operation message)
            :seon.db.protocol.operation/ensure-database
            (if (= target-name (::protocol/database-name message))
              (if @branch-exists?
                {::protocol/success? true
                 ::protocol/database-name target-name
                 :seon.db/db (database-value target-name @target-head)
                 ::protocol/backend (::protocol/backend source-database)
                 ::protocol/database-path (::protocol/database-path
                                            source-database)}
                {::protocol/success? false
                 ::protocol/error-kind protocol/branch-missing-error
                 ::protocol/error "branch absent"})
              {::protocol/success? true
               ::protocol/database-name (::protocol/database-name source-database)
               :seon.db/db
               (database-value (::protocol/database-name source-database)
                               source-head)
               ::protocol/backend (::protocol/backend source-database)
               ::protocol/database-path (::protocol/database-path source-database)})

            :seon.db.protocol.operation/create-branch
            (let [_attempt (swap! create-attempts inc)
                  retained (state/read-edn lifecycle-path)]
              (is (= message (::branch/create-request retained))
                  "exact create intent is durable before writer mutation")
              (reset! branch-exists? true)
              (case @create-mode
                :drop nil
                :created-mismatch
                {::protocol/success? true
                 ::protocol/target-database-name target-name
                 ::protocol/target-connection-id target-connection-id
                 ::protocol/branch-head advanced-head
                 ::protocol/backend (::protocol/backend source-database)
                 ::protocol/database-path (::protocol/database-path
                                            source-database)
                 ::protocol/created? true
                 ::protocol/adopted? false}
                {::protocol/success? true
                 ::protocol/target-database-name target-name
                 ::protocol/target-connection-id target-connection-id
                 ::protocol/branch-head @target-head
                 ::protocol/backend (::protocol/backend source-database)
                 ::protocol/database-path (::protocol/database-path
                                            source-database)
                 ::protocol/created? (= :created @create-mode)
                 ::protocol/adopted? (= :adopt @create-mode)}))

            :seon.db.protocol.operation/release-database
            (if (pos? @release-busy-attempts)
              (do
                (swap! release-busy-attempts dec)
                {::protocol/success? false
                 ::protocol/error-kind
                 :seon.db.protocol.error/database-in-use
                 ::protocol/error
                 "The target database is still acquired by live connections."})
              {::protocol/success? true
               ::protocol/released? false})

            :seon.db.protocol.operation/delete-branch
            (do
              (is (= @target-head (::protocol/expected-target-head message))
                  "close fences deletion with the freshly ensured target head")
              (reset! branch-exists? false)
              {::protocol/success? true
               ::protocol/target-database-name target-name
               ::protocol/target-connection-id target-connection-id
               ::protocol/source-head source-head
               ::protocol/released? false
               ::protocol/deleted? true}))
           (assoc ::protocol/request-id (::protocol/request-id message))))
        manifest {:seon.dev.artifact/application-digest "application"}]
    (try
      (with-redefs [artifact/read-manifest (fn [_] manifest)
                    branch/writer-call! (fn [_ message] (handler message))
                    process/specs
                    (fn [configuration _]
                      (if (true? (get-in configuration
                                         [:seon.dev.config/launch-descriptor
                                          ::launch/runtime
                                          :seon.client/launch-capability
                                          :seon.client/autonomous?]))
                        {process/watcher-id {:seon.dev.process/id
                                             process/watcher-id}
                         process/writer-id {:seon.dev.process/id
                                            process/writer-id}}
                        {process/pod-id {:seon.dev.process/id process/pod-id}}))
                    process/read-process
                    (fn [_ id]
                      (when (#{process/watcher-id process/writer-id} id)
                        {:seon.dev.process/id id}))
                    process/ready? (fn [_ _ _] true)
                    process/converged? (fn [_ _] false)
                    process/ensure!
                    (fn
                      ([_ spec]
                       (swap! process-events conj
                              [:ensure (:seon.dev.process/id spec)]))
                      ([selected spec acquire-owned!]
                       (if (process/converged? selected spec)
                         (swap! process-events conj
                                [:ensure (:seon.dev.process/id spec)])
                         (acquire-owned!
                          (:seon.dev.process/id spec)
                          #(swap! process-events conj
                                  [:ensure (:seon.dev.process/id spec)])))))
                    process/clean-or-force!
                    (fn [{:seon.dev.process/keys [operation targets]}]
                      (swap! process-events conj
                             [:clean-or-force operation targets])
                      (when (compare-and-set! block-next-stop? true false)
                        (deliver stop-entered true)
                        @release-stop)
                      (if (= :seon.dev.process.classification/containment-uncertain
                             @stop-classification)
                        (throw
                         (ex-info
                          "injected retained pod containment uncertainty"
                          {:seon.dev.process/classification
                           @stop-classification
                           :seon.dev.process/results []}))
                        (let [result
                              {:seon.dev.process/operation operation
                               :seon.dev.process/classification
                               @stop-classification
                               :seon.dev.process/budget-ms 1
                               :seon.dev.process/elapsed-ms 0
                               :seon.dev.process/results
                               [(cond->
                                 {:seon.dev.process/id process/pod-id
                                  :seon.dev.process/classification
                                  @stop-classification}
                                  (= :seon.dev.process.classification/forced
                                     @stop-classification)
                                  (assoc
                                   :seon.dev.process/reason
                                   :seon.dev.process.reason/incomplete-application))]}]
                          result)))
                    process/stop!
                    (fn [_ id]
                      (swap! process-events conj [:stop id]))
                    process/ownership-conflicts (fn [_ _] [])]
        (is (thrown-with-msg?
             Exception #"rejected a branch lifecycle request"
             (branch/open! request))
            "writer loss after mutation retains the exact create intent")
        (is (some? (state/read-edn lifecycle-path)))
        (reset! create-mode :adopt)
        (let [opened (branch/open! request)]
          (is (= :seon.dev.branch.phase/ready (::branch/phase opened)))
          (is (= fork-head
                 (get-in opened [::branch/launch-descriptor
                                 ::launch/database
                                 ::db.branch/head]))
              "launch retains the immutable creation cut")
          (is (= 2 @create-attempts))
          (is (= [[:clean-or-force
                   :seon.dev.process.operation/retained-restart
                   #{process/pod-id}]
                  [:ensure process/pod-id]]
                 @process-events)))
        (reset! target-head advanced-head)
        (let [current (branch/current-descriptor! stop-request)]
          (is (= advanced-head
                 (get-in current
                         [::launch/database ::db.branch/head])))
          (is (= fork-head
                 (get-in (state/read-edn lifecycle-path)
                         [::branch/launch-descriptor ::launch/database
                          ::db.branch/head]))
              "fresh observation does not rewrite the immutable launch cut"))
        (reset! branch-exists? false)
        (is (thrown-with-msg?
             Exception #"rejected a branch lifecycle request"
             (branch/current-descriptor! stop-request)))
        (reset! branch-exists? true)
        (reset! target-head (assoc advanced-head ::db.branch/name :stale))
        (is (thrown-with-msg?
             Exception #"different target connection-id"
             (branch/current-descriptor! stop-request)))
        (reset! target-head advanced-head)

        (doseq [path [process-dir log-dir writable-blob-dir]]
          (fs/create-dirs path))
        (spit http-port-file "7891\n")
        (reset! process-events [])
        (reset! stop-classification :seon.dev.process.classification/clean)
        (let [stopped (branch/stop! stop-request)]
          (is (= :seon.dev.branch.state/open (::branch/desired-state stopped)))
          (is (= :seon.dev.branch.phase/branch-retained (::branch/phase stopped)))
          (is (= :seon.dev.process.classification/clean
                 (get-in stopped
                         [::branch/stop-result
                          :seon.dev.process/classification])))
          (is @branch-exists?)
          (is (fs/exists? lifecycle-path))
          (is (every? fs/exists?
                      [process-dir log-dir http-port-file writable-blob-dir])
              "retained stop preserves pod files and branch blob evidence")
          (is (= stopped (branch/stop! stop-request))
              "a proved retained stop is an idempotent durable observation")
          (is (= [[:clean-or-force
                   :seon.dev.process.operation/retained-restart
                   #{process/pod-id}]]
                 @process-events)
              "an idempotent stop does not invoke containment again"))

        (reset! stop-classification :seon.dev.process.classification/absent)
        (branch/open! request)
        (reset! process-events [])
        (reset! stop-classification
                :seon.dev.process.classification/containment-uncertain)
        (is (thrown-with-msg?
             Exception #"retained pod containment uncertainty"
             (branch/stop! stop-request)))
        (let [retained (state/read-edn lifecycle-path)]
          (is (= :seon.dev.branch.state/open (::branch/desired-state retained)))
          (is (= :seon.dev.branch.phase/stopping-pod (::branch/phase retained)))
          (is (nil? (::branch/stop-result retained)))
          (is @branch-exists?))
        (reset! stop-classification :seon.dev.process.classification/forced)
        (let [recovered (branch/stop! stop-request)]
          (is (= :seon.dev.branch.phase/branch-retained
                 (::branch/phase recovered)))
          (is (= :seon.dev.process.classification/forced
                 (get-in recovered
                         [::branch/stop-result
                          :seon.dev.process/classification])))
          (is @branch-exists?)
          (is (fs/exists? lifecycle-path))
          (is (every? fs/exists?
                      [process-dir log-dir http-port-file writable-blob-dir]))
          (is (= recovered (branch/stop! stop-request))
              "a proved forced stop also returns its durable evidence")
          (is (= 2 (count @process-events))
              "forced-stop retry does not invoke containment a third time"))
        (reset! target-head fork-head)
        (reset! process-events [])
        (reset! stop-classification
                :seon.dev.process.classification/containment-uncertain)
        (let [request-count (count @requests)]
          (is (thrown-with-msg?
               Exception #"retained pod containment uncertainty"
               (branch/open! request)))
          (let [retained (state/read-edn lifecycle-path)]
            (is (= :seon.dev.branch.state/open
                   (::branch/desired-state retained)))
            (is (= :seon.dev.branch.phase/stopping-pod
                   (::branch/phase retained)))
            (is (nil? (::branch/stop-result retained))))
          (is (= request-count (count @requests))
              "uncertain open replacement performs no writer request")
          (is (= [[:clean-or-force
                   :seon.dev.process.operation/retained-restart
                   #{process/pod-id}]]
                 @process-events)
              "uncertain open replacement never reaches ensure"))
        (reset! stop-classification
                :seon.dev.process.classification/forced)
        (reset! process-events [])
        (let [recovered (branch/open! request)]
          (is (= :seon.dev.branch.phase/ready (::branch/phase recovered)))
          (is (= :seon.dev.process.classification/forced
                 (get-in recovered
                         [::branch/stop-result
                          :seon.dev.process/classification]))))
        (reset! process-events [])
        (reset! stop-classification
                :seon.dev.process.classification/containment-uncertain)
        (let [request-count (count @requests)]
          (is (thrown-with-msg?
               Exception #"retained pod containment uncertainty"
               (branch/restart! request)))
          (let [retained (state/read-edn lifecycle-path)]
            (is (= :seon.dev.branch.state/open
                   (::branch/desired-state retained)))
            (is (= :seon.dev.branch.phase/stopping-pod
                   (::branch/phase retained)))
            (is (nil? (::branch/stop-result retained))))
          (is (= request-count (count @requests))
              "uncertain restart performs no writer request")
          (is (= [[:clean-or-force
                   :seon.dev.process.operation/retained-restart
                   #{process/pod-id}]]
                 @process-events)
              "uncertain restart never opens the replacement"))
        (reset! stop-classification
                :seon.dev.process.classification/absent)
        (reset! process-events [])
        (reset! block-next-stop? true)
        (let [restarted (future (branch/restart! request))]
          (is (true? (deref stop-entered 1000 false)))
          (let [concurrent-open (future (branch/open! request))]
            (is (= ::blocked (deref concurrent-open 100 ::blocked))
                "open cannot interleave between restart stop and reconcile")
            (deliver release-stop true)
            (let [restarted-record (deref restarted 5000 ::timeout)]
              (is (= :seon.dev.branch.phase/ready
                     (::branch/phase restarted-record)))
              (is (= :seon.dev.process.classification/absent
                     (get-in restarted-record
                             [::branch/stop-result
                              :seon.dev.process/classification]))))
            (is (= :seon.dev.branch.phase/ready
                   (::branch/phase (deref concurrent-open 5000 ::timeout)))))
          (is (= 2 @create-attempts)
              "pod-only restart never recreates the native branch")
          (is (= [[:clean-or-force
                   :seon.dev.process.operation/retained-restart
                   #{process/pod-id}]
                  [:ensure process/pod-id]
                  [:clean-or-force
                   :seon.dev.process.operation/retained-restart
                   #{process/pod-id}]
                  [:ensure process/pod-id]]
                 @process-events)))
        (let [inventory-path
              (str (fs/path (#'branch/branch-record-directory source-config)
                            "trial.edn"))
              retained (state/read-edn lifecycle-path)]
          (state/write-edn! inventory-path retained)
          (let [inventory (branch/inventory source-config)
                status (first inventory)]
            (is (m/validate [:vector ::branch/status] inventory))
            (is (= "trial" (::branch/runtime-cluster status)))
            (is (= fork-head (::branch/branch-head-at-launch status)))
            (is (= (::branch/launch-descriptor retained)
                   (::branch/launch-descriptor status)))
            (is (m/validate ::branch/status status))
            (is (= :seon.dev.target.status/down
                   (:seon.dev.target/status status))))
          (fs/delete-if-exists inventory-path))
        (doseq [path [process-dir log-dir writable-blob-dir]]
          (fs/create-dirs path))
        (spit http-port-file "7891\n")
        (reset! target-head advanced-head)
        (reset! process-events [])
        (reset! stop-classification
                :seon.dev.process.classification/containment-uncertain)
        (let [request-count (count @requests)]
          (is (thrown-with-msg?
               Exception #"retained pod containment uncertainty"
               (branch/close!
                {::branch/configuration source-config
                 ::branch/lifecycle-path lifecycle-path})))
          (let [retained (state/read-edn lifecycle-path)]
            (is (= :seon.dev.branch.state/closed
                   (::branch/desired-state retained)))
            (is (= :seon.dev.branch.phase/stopping-pod
                   (::branch/phase retained)))
            (is (nil? (::branch/stop-result retained))))
          (is (= request-count (count @requests))
              "uncertain close cannot ensure, release, or delete")
          (is @branch-exists?)
          (is (= [[:clean-or-force
                   :seon.dev.process.operation/retained-close
                   #{process/pod-id}]]
                 @process-events)))
        (reset! stop-classification
                :seon.dev.process.classification/forced)
        (reset! release-busy-attempts 2)
        (let [closed
              (branch/close!
               {::branch/configuration source-config
                ::branch/lifecycle-path lifecycle-path})]
          (is (= :seon.dev.branch.phase/closed (::branch/phase closed)))
          (is (= advanced-head (::branch/target-head closed)))
          (is (= :seon.dev.process.classification/forced
                 (get-in closed [::branch/stop-result
                                 :seon.dev.process/classification])))
          (is (= [[:clean-or-force
                   :seon.dev.process.operation/retained-close
                   #{process/pod-id}]
                  [:clean-or-force
                   :seon.dev.process.operation/retained-close
                   #{process/pod-id}]]
                 @process-events))
          (is (false? @branch-exists?))
          (is (not (fs/exists? lifecycle-path)))
          (is (every? #(not (fs/exists? %))
                      [process-dir log-dir http-port-file writable-blob-dir]))
          (is (= [protocol/ensure-database-operation
                  protocol/create-branch-operation
                  protocol/create-branch-operation
                  protocol/ensure-database-operation
                  protocol/ensure-database-operation
                  protocol/ensure-database-operation
                  protocol/ensure-database-operation
                  protocol/release-database-operation
                  protocol/release-database-operation
                  protocol/release-database-operation
                  protocol/delete-branch-operation]
                 (mapv ::protocol/operation @requests))))

        (reset! process-events [])
        (reset! target-head fork-head)
        (reset! create-mode :created)
        (with-redefs [state/write-edn!
                      (fn [path value]
                        (if (= :seon.dev.branch.phase/ready
                               (::branch/phase value))
                          (throw (ex-info "injected ready publication failure"
                                          {}))
                          (write-edn! path value)))]
          (is (thrown-with-msg?
               Exception #"injected ready publication failure"
               (branch/open! request))))
        (is (= [[:clean-or-force
                 :seon.dev.process.operation/retained-restart
                 #{process/pod-id}]
                [:ensure process/pod-id]
                [:stop process/pod-id]]
               @process-events)
            "a newly started pod is drained before exact branch cleanup")
        (is (false? @branch-exists?))
        (is (not (fs/exists? lifecycle-path)))

        (reset! process-events [])
        (reset! create-mode :created)
        (branch/open! request)
        (with-redefs [process/converged? (fn [_ _] true)
                      state/write-edn!
                      (fn [path value]
                        (if (= :seon.dev.branch.phase/ready
                               (::branch/phase value))
                          (throw (ex-info "injected reused publication failure"
                                          {}))
                          (write-edn! path value)))]
          (is (thrown-with-msg?
               Exception #"injected reused publication failure"
               (branch/open! request))))
        (is (= [[:clean-or-force
                 :seon.dev.process.operation/retained-restart
                 #{process/pod-id}]
                [:ensure process/pod-id]
                [:ensure process/pod-id]]
               @process-events)
            "a converged pod is not stopped by another invocation's failure")
        (is @branch-exists?)
        (is (= :seon.dev.branch.phase/pod-starting
               (::branch/phase (state/read-edn lifecycle-path))))
        (branch/close! {::branch/configuration source-config
                        ::branch/lifecycle-path lifecycle-path})

        (reset! process-events [])
        (reset! target-head fork-head)
        (reset! create-mode :created-mismatch)
        (is (thrown-with-msg?
             Exception #"does not match retained intent"
             (branch/open! request))
            "a newly-created response cannot move beyond the retained fork")
        (is (empty? @process-events))
        (is (some? (state/read-edn lifecycle-path)))
        (reset! create-mode :adopt)
        (branch/close! {::branch/configuration source-config
                        ::branch/lifecycle-path lifecycle-path}))
      (finally
        (deliver release-stop true)
        (fs/delete-tree directory)))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'seon.dev.branch-test)]
    (when (pos? (+ fail error)) (System/exit 1))))
