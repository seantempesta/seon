(ns seon.dev.branch-test
  (:require [babashka.fs :as fs]
            [babashka.process :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [seon.db.coordinate :as coordinate]
            [seon.db.protocol :as protocol]
            [seon.db.transport.uds :as uds]
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
                                    ::branch/name invalid}))))))

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
   :seon.dev.process/artifact-digest "application"})

(defn- signal-writer! [socket source-database]
  (let [database-id #uuid "ebc09a32-5450-4181-bc88-f053dcaf301f"
        source-head {::coordinate/database-id database-id
                     ::coordinate/branch :db
                     ::coordinate/commit-id
                     #uuid "01c71e5a-ca4f-4d21-a45c-d24a07db0b84"
                     ::coordinate/t 10}
        fork-head (assoc source-head ::coordinate/branch :trial)
        target-attachment (coordinate/attachment fork-head)
        branch-exists? (atom false)
        requests (atom [])
        handler
        (fn [message]
          (swap! requests conj message)
          (case (::protocol/operation message)
            :seon.db.protocol.operation/ensure-database
            (if (= "trial-route" (::protocol/database-name message))
              (if @branch-exists?
                {::protocol/success? true
                 ::protocol/database-name "trial-route"
                 ::coordinate/coordinate fork-head
                 ::protocol/backend (::protocol/backend source-database)
                 ::protocol/database-path (::protocol/database-path source-database)}
                {::protocol/success? false
                 ::protocol/error-kind protocol/branch-missing-error
                 ::protocol/error "branch absent"})
              {::protocol/success? true
               ::protocol/database-name (::protocol/database-name source-database)
               ::coordinate/coordinate source-head
               ::protocol/backend (::protocol/backend source-database)
               ::protocol/database-path (::protocol/database-path source-database)})

            :seon.db.protocol.operation/create-branch
            (let [adopted? @branch-exists?]
              (reset! branch-exists? true)
              {::protocol/success? true
               ::protocol/target-database-name "trial-route"
               ::protocol/target-attachment target-attachment
               ::protocol/coordinate fork-head
               ::protocol/backend (::protocol/backend source-database)
               ::protocol/database-path (::protocol/database-path source-database)
               ::protocol/created? (not adopted?)
               ::protocol/adopted? adopted?})

            :seon.db.protocol.operation/release-database
            {::protocol/success? true
             ::protocol/target-database-name "trial-route"
             ::protocol/target-attachment target-attachment
             ::protocol/released? true}

            :seon.db.protocol.operation/delete-branch
            (do
              (reset! branch-exists? false)
              {::protocol/success? true
               ::protocol/target-database-name "trial-route"
               ::protocol/target-attachment target-attachment
               ::protocol/source-head source-head
               ::protocol/released? false
               ::protocol/deleted? true})))
        server (uds/start-request-server!
                {::uds/socket-path socket ::uds/handler handler})]
    {:seon.dev.branch-test/server server
     :seon.dev.branch-test/branch-exists? branch-exists?
     :seon.dev.branch-test/requests requests}))

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
                 #'process/specs (fn [_ _] {process/pod-id pod})}
          (= cut :seon.dev.branch-test.cut/spawn-publication)
          (assoc #'process/spawn-detached!
                 (fn [configuration spec]
                   (spit marker "spawn")
                   (Thread/sleep 500)
                   (spawn configuration spec)))

          (= cut :seon.dev.branch-test.cut/cleanup-failure)
          (assoc #'process/stop!
                 (fn [_ id]
                   (throw (ex-info "injected retained-pod inverse failure"
                                   {:seon.dev.process/id id}))))

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
          source-database
          (get-in request [::branch/configuration
                           :seon.dev.config/launch-descriptor
                           ::launch/database])
          fixture (signal-writer! socket source-database)
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
          (is (false? @(::branch-exists? fixture))
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
                 (mapv ::protocol/operation @(::requests fixture)))))
        (finally
          (when (.isAlive ^java.lang.Process (:proc owner))
            (.destroyForcibly ^java.lang.Process (:proc owner)))
          (uds/close-request-server! (::server fixture))
          (fs/delete-tree directory {:force true}))))))

(deftest real-sigint-does-not-claim-a-converged-branch-pod
  (let [root (System/getProperty "user.dir")
        directory (str (fs/path root "tmp" (str "branch-sigint-reuse-"
                                                  (random-uuid))))
        _ (fs/create-dirs directory)
        socket (str (fs/path directory "writer.sock"))
        request (signal-request directory socket)
        source-database
        (get-in request [::branch/configuration
                         :seon.dev.config/launch-descriptor ::launch/database])
        fixture (signal-writer! socket source-database)
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
          (is @(::branch-exists? fixture))
          (is (= :seon.dev.branch.state/open (::branch/desired-state retained)))
          (is (= (:seon.dev.process/pid original)
                 (:seon.dev.process/pid current)))
          (is (state/process-identity-alive? current)
              "a converged pod remains owned by its earlier invocation"))
        (branch/close! {::branch/configuration (::branch/configuration request)
                        ::branch/lifecycle-path (::branch/lifecycle-path request)}))
      (finally
        (when-let [child @owner]
          (when (.isAlive ^java.lang.Process (:proc child))
            (.destroyForcibly ^java.lang.Process (:proc child))))
        (uds/close-request-server! (::server fixture))
        (fs/delete-tree directory {:force true})))))

(deftest failed-pod-unwind-retains-the-exact-native-branch
  (let [root (System/getProperty "user.dir")
        directory (str (fs/path root "tmp" (str "branch-sigint-failure-"
                                                  (random-uuid))))
        _ (fs/create-dirs directory)
        socket (str (fs/path directory "writer.sock"))
        request (signal-request directory socket)
        source-database
        (get-in request [::branch/configuration
                         :seon.dev.config/launch-descriptor ::launch/database])
        fixture (signal-writer! socket source-database)
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
          (is @(::branch-exists? fixture)
              "an uncertain process inverse forbids branch deletion")
          (is (= :seon.dev.branch.state/open (::branch/desired-state retained)))
          (is (= (:seon.dev.process/pid pod)
                 (:seon.dev.process/pid current)))
          (is (state/process-identity-alive? current)))
        (branch/close! {::branch/configuration (::branch/configuration request)
                        ::branch/lifecycle-path (::branch/lifecycle-path request)}))
      (finally
        (when (.isAlive ^java.lang.Process (:proc owner))
          (.destroyForcibly ^java.lang.Process (:proc owner)))
        (uds/close-request-server! (::server fixture))
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
        source-head {::coordinate/database-id database-id
                     ::coordinate/branch :db
                     ::coordinate/commit-id
                     #uuid "01c71e5a-ca4f-4d21-a45c-d24a07db0b84"
                     ::coordinate/t 10}
        fork-head (assoc source-head ::coordinate/branch :trial)
        advanced-head
        (assoc fork-head
               ::coordinate/commit-id
               #uuid "0b1d206c-186b-44c8-801c-ab33d76e33ef"
               ::coordinate/t 11)
        target-head (atom fork-head)
        branch-exists? (atom false)
        create-attempts (atom 0)
        create-mode (atom :drop)
        requests (atom [])
        process-events (atom [])
        write-edn! state/write-edn!
        target-name "trial-route"
        target-attachment (coordinate/attachment fork-head)
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
        handler
        (fn [message]
          (swap! requests conj message)
          (case (::protocol/operation message)
            :seon.db.protocol.operation/ensure-database
            (if (= target-name (::protocol/database-name message))
              (if @branch-exists?
                {::protocol/success? true
                 ::protocol/database-name target-name
                 ::coordinate/coordinate @target-head
                 ::protocol/backend (::protocol/backend source-database)
                 ::protocol/database-path (::protocol/database-path
                                            source-database)}
                {::protocol/success? false
                 ::protocol/error-kind protocol/branch-missing-error
                 ::protocol/error "branch absent"})
              {::protocol/success? true
               ::protocol/database-name (::protocol/database-name source-database)
               ::coordinate/coordinate source-head
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
                 ::protocol/target-attachment target-attachment
                 ::protocol/coordinate advanced-head
                 ::protocol/backend (::protocol/backend source-database)
                 ::protocol/database-path (::protocol/database-path
                                            source-database)
                 ::protocol/created? true
                 ::protocol/adopted? false}
                {::protocol/success? true
                 ::protocol/target-database-name target-name
                 ::protocol/target-attachment target-attachment
                 ::protocol/coordinate @target-head
                 ::protocol/backend (::protocol/backend source-database)
                 ::protocol/database-path (::protocol/database-path
                                            source-database)
                 ::protocol/created? (= :created @create-mode)
                 ::protocol/adopted? (= :adopt @create-mode)}))

            :seon.db.protocol.operation/release-database
            {::protocol/success? true
             ::protocol/target-database-name target-name
             ::protocol/target-attachment target-attachment
             ::protocol/released? true}

            :seon.db.protocol.operation/delete-branch
            (do
              (is (= @target-head (::protocol/expected-target-head message))
                  "close fences deletion with the freshly ensured target head")
              (reset! branch-exists? false)
              {::protocol/success? true
               ::protocol/target-database-name target-name
               ::protocol/target-attachment target-attachment
               ::protocol/source-head source-head
               ::protocol/released? false
               ::protocol/deleted? true})))
        server (uds/start-request-server!
                {::uds/socket-path socket ::uds/handler handler})
        manifest {:seon.dev.artifact/application-digest "application"}]
    (try
      (with-redefs [artifact/read-manifest (fn [_] manifest)
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
                    process/stop!
                    (fn [_ id] (swap! process-events conj [:stop id]))
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
                                 ::coordinate/coordinate]))
              "launch retains the immutable creation cut")
          (is (= 2 @create-attempts))
          (is (= [[:ensure process/pod-id]] @process-events)))
        (let [restarted (branch/restart! request)]
          (is (= :seon.dev.branch.phase/ready (::branch/phase restarted)))
          (is (= 2 @create-attempts)
              "pod-only restart never recreates the native branch")
          (is (= [[:ensure process/pod-id]
                  [:stop process/pod-id]
                  [:ensure process/pod-id]]
                 @process-events)))
        (let [inventory-path
              (str (fs/path (#'branch/branch-record-directory source-config)
                            "trial.edn"))
              retained (state/read-edn lifecycle-path)]
          (state/write-edn! inventory-path retained)
          (let [status (first (branch/inventory source-config))]
            (is (= "trial" (::branch/runtime-cluster status)))
            (is (= fork-head (::branch/coordinate-at-launch status)))
            (is (= (::branch/launch-descriptor retained)
                   (::branch/launch-descriptor status)))
            (is (= :seon.dev.target.status/down
                   (:seon.dev.target/status status))))
          (fs/delete-if-exists inventory-path))
        (doseq [path [process-dir log-dir writable-blob-dir]]
          (fs/create-dirs path))
        (spit http-port-file "7891\n")
        (reset! target-head advanced-head)
        (let [closed
              (branch/close!
               {::branch/configuration source-config
                ::branch/lifecycle-path lifecycle-path})]
          (is (= :seon.dev.branch.phase/closed (::branch/phase closed)))
          (is (= advanced-head (::branch/target-head closed)))
          (is (= [[:ensure process/pod-id]
                  [:stop process/pod-id]
                  [:ensure process/pod-id]
                  [:stop process/pod-id]]
                 @process-events))
          (is (false? @branch-exists?))
          (is (not (fs/exists? lifecycle-path)))
          (is (every? #(not (fs/exists? %))
                      [process-dir log-dir http-port-file writable-blob-dir]))
          (is (= [protocol/ensure-database-operation
                  protocol/create-branch-operation
                  protocol/create-branch-operation
                  protocol/ensure-database-operation
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
        (is (= [[:ensure process/pod-id]
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
        (is (= [[:ensure process/pod-id]
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
        (uds/close-request-server! server)
        (fs/delete-tree directory)))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'seon.dev.branch-test)]
    (when (pos? (+ fail error)) (System/exit 1))))
