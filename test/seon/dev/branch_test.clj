(ns seon.dev.branch-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is run-tests]]
            [seon.db.coordinate :as coordinate]
            [seon.db.protocol :as protocol]
            [seon.db.transport.uds :as uds]
            [seon.dev.artifact :as artifact]
            [seon.dev.branch :as branch]
            [seon.dev.config :as config]
            [seon.dev.process :as process]
            [seon.dev.state :as state]
            [seon.launch :as launch]))

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
                    (fn [_ spec]
                      (swap! process-events conj [:ensure
                                                  (:seon.dev.process/id spec)]))
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
