(ns seon.dev.cli-test
  (:require [babashka.fs :as fs]
            [babashka.process :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.dev.artifact :as artifact]
            [seon.dev.branch :as branch]
            [seon.dev.cli :as cli]
            [seon.dev.process :as process]
            [seon.dev.restore :as restore]
            [seon.dev.restore-state :as restore-state]
            [seon.dev.state :as state]))

(defn- stop-result
  ([operation targets]
   (stop-result operation targets :seon.dev.process.classification/clean))
  ([operation targets classification]
   {:seon.dev.process/operation operation
    :seon.dev.process/classification classification
    :seon.dev.process/budget-ms 1000
    :seon.dev.process/elapsed-ms 10
    :seon.dev.process/results
    (mapv (fn [id]
            {:seon.dev.process/id id
             :seon.dev.process/classification classification})
          (filterv targets
                   [process/pod-id process/writer-id process/watcher-id]))}))

(defn- component-result
  ([id classification]
   {:seon.dev.process/id id
    :seon.dev.process/classification classification})
  ([id classification generation]
   (assoc (component-result id classification)
          :seon.dev.process/terminal
          {:seon.dev.process.containment/generation generation
           :seon.dev.process.containment/status
           :seon.dev.process.containment.status/drained
           :seon.dev.process.containment/trigger
           :seon.dev.process.containment.trigger/requested
           :seon.dev.process.containment/anchor-exit -9})))

(deftest stop-evidence-selects-only-bounded-correlation-fields
  (let [generation #uuid "00000000-0000-0000-0000-000000000001"
        digest (apply str (repeat 64 "a"))
        sentinel (apply str (repeat 2048 "UNBOUNDED-APPLICATION-RESULT"))
        pod
        (assoc (component-result process/pod-id
                                 :seon.dev.process.classification/forced
                                 generation)
               :seon.dev.process/reason
               :seon.dev.process.reason/incomplete-application
               :seon.dev.process/application-result {:sentinel sentinel}
               :seon.dev.process/application-error-message sentinel)
        writer
        (assoc (component-result process/writer-id
                                 :seon.dev.process.classification/forced
                                 generation)
               :seon.dev.process/reason
               :seon.dev.process.application-error/invalid-schema
               :seon.dev.process/application-capture
               {:seon.dev.process.application-capture/status
                :seon.dev.process.application-capture/captured
                :seon.dev.process.application-capture/sha256 digest
                :seon.dev.process.application-capture/bytes 123
                :seon.dev.process.application-capture/error sentinel})
        watcher (component-result process/watcher-id
                                  :seon.dev.process.classification/clean
                                  generation)
        extra (assoc watcher :seon.dev.process/reason
                     :seon.dev.process.reason/should-not-render)
        lines
        (#'cli/stop-evidence-lines
         {:seon.dev.process/operation :seon.dev.process.operation/restart
          :seon.dev.process/classification
          :seon.dev.process.classification/forced
          :seon.dev.process/results [pod writer watcher extra]})]
    (is (= ["restart: forced"
            (str "  pod: forced reason=incomplete-application"
                 " generation=" generation " trigger=requested")
            (str "  writer: forced reason=invalid-schema"
                 " generation=" generation " trigger=requested"
                 " capture=captured sha256=" digest " bytes=123")
            (str "  watcher: clean generation=" generation
                 " trigger=requested")]
           lines))
    (is (= 4 (count lines)))
    (is (not-any? #(str/includes? % sentinel) lines))
    (is (not-any? #(str/includes? % "should-not-render") lines))))

(deftest ready-output-renders-bounded-restart-component-evidence
  (let [target
        {:seon.dev.target/url "http://127.0.0.1:7890"
         :seon.dev.target/stop-results
         [(stop-result :seon.dev.process.operation/restart
                       (set process/target-processes)
                       :seon.dev.process.classification/forced)]}]
    (with-redefs-fn
      {#'cli/ordinary-agent-url
       (constantly "http://127.0.0.1:7890/agent/proof")}
      (fn []
        (is (= (str "\n◆ Seon is ready\n"
                    "  agent: http://127.0.0.1:7890/agent/proof\n"
                    "  root:  http://127.0.0.1:7890/\n"
                    "  data:  http://127.0.0.1:7890/data\n"
                    "  restart: forced\n"
                    "    pod: forced\n"
                    "    writer: forced\n"
                    "    watcher: forced\n")
               (with-out-str (#'cli/print-ready! target false))))))))

(deftest ready-url-selects-an-ordinary-agent-from-the-root-feed
  (let [requested (atom [])]
    (with-redefs-fn
      {#'shell/sh
       (fn [{:keys [cmd]}]
         (swap! requested conj cmd)
         {:exit 0
          :out (str "<a href=\"/agent/root\"></a>"
                    "<a href=\"/agent/root/debug\"></a>"
                    "<a href=\"/agent/quiet-rivers-turn\"></a>")
          :err ""})}
      (fn []
        (is (= "http://127.0.0.1:7890/agent/quiet-rivers-turn"
               (#'cli/ordinary-agent-url "http://127.0.0.1:7890")))))
    (is (= "http://127.0.0.1:7890/agent/root/feed"
           (last (first @requested))))))

(deftest ready-url-falls-back-to-the-valid-root-page
  (with-redefs-fn
    {#'shell/sh (constantly {:exit 22 :out "" :err "unavailable"})}
    (fn []
      (is (= "http://127.0.0.1:7890/"
             (#'cli/ordinary-agent-url "http://127.0.0.1:7890"))))))

(deftest failure-after-watcher-flush-unwinds-the-prepared-lifetime
  (let [root (fs/create-temp-dir {:prefix "seon-cli-watcher-unwind-"})
        configuration {:seon.dev.config/root (str root)
                       :seon.dev.config/cluster-dir
                       (str (fs/path root "data/clusters/default"))
                       :seon.dev.config/environment {}}
        requests (atom [])
        unwound (atom [])]
    (try
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"injected publication failure"
           (with-redefs-fn
             {#'process/clean-or-force!
              (fn [{:seon.dev.process/keys [operation targets] :as request}]
                (swap! requests conj request)
                (stop-result operation targets))
              #'process/stop!
              (fn [_ id] (swap! unwound conj id))
              #'process/prepare-watcher!
              (fn [_ start-owned!]
                (start-owned! process/watcher-id
                              (constantly :prepared-watcher)))
              #'artifact/build!
              (fn [_ prepare-client!]
                (prepare-client!)
                (throw (ex-info "injected publication failure" {})))}
             #(#'cli/reconcile-development! configuration))))
      (is (= [{:seon.dev.process/configuration configuration
               :seon.dev.process/operation
               :seon.dev.process.operation/rebuild-readers
               :seon.dev.process/targets
               #{process/pod-id process/watcher-id}}]
             @requests))
      (is (= [process/watcher-id] @unwound)
          "only startup ownership directly unwinds the prepared watcher")
      (finally (fs/delete-tree root {:force true})))))

(deftest reconcile-coordinates-readers-and-a-changed-writer-once
  (let [configuration {:seon.dev.config/cluster-dir "/cluster"}
        requests (atom [])
        manifest {:seon.dev.artifact/client-digest (apply str (repeat 64 "a"))
                  :seon.dev.artifact/application-digest
                  (apply str (repeat 64 "b"))
                  :seon.dev.artifact/changed
                  #{:seon.dev.artifact/writer :seon.dev.artifact/application}}]
    (with-redefs-fn
      {#'cli/assert-current-database-layout! identity
       #'process/with-startup-ownership
       (fn [_ transition]
         (transition (fn [_ acquire!] (acquire!))))
       #'process/clean-or-force!
       (fn [{:seon.dev.process/keys [operation targets] :as request}]
         (swap! requests conj request)
         (stop-result operation targets))
       #'process/stop!
       (fn [& _]
         (throw (ex-info "ordinary reconcile bypassed the coordinator" {})))
       #'artifact/build! (fn [_ _] manifest)
       #'process/admit-watcher-artifact! (fn [& _] nil)
       #'process/specs (fn [& _] {})
       #'process/start-order (fn [_] [])
       #'process/status
       (fn [& _]
         {:seon.dev.target/status :seon.dev.target.status/ready})}
      (fn []
        (let [target (#'cli/reconcile-development! configuration)]
          (is (= [:seon.dev.process.operation/rebuild-readers
                  :seon.dev.process.operation/rebuild-writer]
                 (mapv :seon.dev.process/operation
                       (:seon.dev.target/stop-results target)))))))
    (is (= [[:seon.dev.process.operation/rebuild-readers
              #{process/pod-id process/watcher-id}]
            [:seon.dev.process.operation/rebuild-writer
              #{process/writer-id}]]
           (mapv (juxt :seon.dev.process/operation
                       :seon.dev.process/targets)
                 @requests)))))

(deftest reconcile-recovers-only-definitely-dead-managed-processes
  (let [configuration {:seon.dev.config/cluster-dir "/cluster"}
        records {process/writer-id {:seon.dev.process/id process/writer-id
                                    :seon.dev.process/pid 41}
                 process/pod-id {:seon.dev.process/id process/pod-id
                                 :seon.dev.process/pid 42}}
        requests (atom [])
        recovered (stop-result :seon.dev.process.operation/recover
                               #{process/writer-id})]
    (with-redefs-fn
      {#'process/read-process (fn [_ id] (get records id))
       #'process/reported-process-status
       (fn [record]
         (if (= process/writer-id (:seon.dev.process/id record))
           :seon.dev.process.status/drained
           (if record :seon.dev.process.status/alive
               :seon.dev.process.status/absent)))
       #'process/clean-or-force!
       (fn [request]
         (swap! requests conj request)
         recovered)}
      (fn []
        (is (= recovered (#'cli/recover-dead-processes! configuration)))))
    (is (= [{:seon.dev.process/configuration configuration
             :seon.dev.process/operation :seon.dev.process.operation/recover
             :seon.dev.process/targets #{process/writer-id}}]
           @requests))))

(deftest reconcile-consumes-prior-stop-evidence
  (let [configuration {:seon.dev.config/cluster-dir "/cluster"}
        prior (stop-result :seon.dev.process.operation/restart
                           (set process/target-processes))
        manifest {:seon.dev.artifact/client-digest (apply str (repeat 64 "a"))
                  :seon.dev.artifact/application-digest
                  (apply str (repeat 64 "b"))
                  :seon.dev.artifact/changed #{:seon.dev.artifact/writer}}]
    (with-redefs-fn
      {#'cli/assert-current-database-layout! identity
       #'process/with-startup-ownership
       (fn [_ transition]
         (transition (fn [_ acquire!] (acquire!))))
       #'process/clean-or-force!
       (fn [& _]
         (throw (ex-info "prior stop evidence was ignored" {})))
       #'artifact/build! (fn [_ _] manifest)
       #'process/admit-watcher-artifact! (fn [& _] nil)
       #'process/specs (fn [& _] {})
       #'process/start-order (fn [_] [])
       #'process/status
       (fn [& _]
         {:seon.dev.target/status :seon.dev.target.status/ready})}
      (fn []
        (is (= [prior]
               (:seon.dev.target/stop-results
                (#'cli/reconcile-development! configuration [prior]))))))))

(deftest reconcile-recovers-a-process-that-exits-during-build
  (let [configuration {:seon.dev.config/cluster-dir "/cluster"}
        recovery (stop-result :seon.dev.process.operation/recover
                              #{process/writer-id})
        checks (atom [nil recovery])
        manifest {:seon.dev.artifact/client-digest (apply str (repeat 64 "a"))
                  :seon.dev.artifact/application-digest
                  (apply str (repeat 64 "b"))
                  :seon.dev.artifact/changed #{}}]
    (with-redefs-fn
      {#'cli/assert-current-database-layout! identity
       #'cli/recover-dead-processes!
       (fn [_]
         (let [[before after] (swap-vals! checks subvec 1)]
           (first before)))
       #'process/with-startup-ownership
       (fn [_ transition]
         (transition (fn [_ acquire!] (acquire!))))
       #'process/clean-or-force!
       (fn [{:seon.dev.process/keys [operation targets]}]
         (stop-result operation targets))
       #'artifact/build! (fn [_ _] manifest)
       #'process/admit-watcher-artifact! (fn [& _] nil)
       #'process/specs (fn [& _] {})
       #'process/start-order (fn [_] [])
       #'process/status
       (fn [& _]
         {:seon.dev.target/status :seon.dev.target.status/ready})}
      (fn []
        (is (= [:seon.dev.process.operation/rebuild-readers
                :seon.dev.process.operation/recover]
               (mapv :seon.dev.process/operation
                     (:seon.dev.target/stop-results
                      (#'cli/reconcile-development! configuration)))))))
    (is (empty? @checks))))

(deftest reconcile-after-reset-stops-only-the-unproven-watcher
  (let [configuration {:seon.dev.config/cluster-dir "/cluster"}
        reset-result (stop-result :seon.dev.process.operation/reset
                                  #{process/pod-id process/writer-id})
        requests (atom [])
        manifest {:seon.dev.artifact/client-digest (apply str (repeat 64 "a"))
                  :seon.dev.artifact/application-digest
                  (apply str (repeat 64 "b"))
                  :seon.dev.artifact/changed #{:seon.dev.artifact/writer}}]
    (with-redefs-fn
      {#'cli/assert-current-database-layout! identity
       #'process/with-startup-ownership
       (fn [_ transition]
         (transition (fn [_ acquire!] (acquire!))))
       #'process/clean-or-force!
       (fn [{:seon.dev.process/keys [operation targets] :as request}]
         (swap! requests conj request)
         (stop-result operation targets))
       #'artifact/build! (fn [_ _] manifest)
       #'process/admit-watcher-artifact! (fn [& _] nil)
       #'process/specs (fn [& _] {})
       #'process/start-order (fn [_] [])
       #'process/status
       (fn [& _]
         {:seon.dev.target/status :seon.dev.target.status/ready})}
      (fn []
        (#'cli/reconcile-development! configuration [reset-result])))
    (is (= [[:seon.dev.process.operation/rebuild-readers
             #{process/watcher-id}]]
           (mapv (juxt :seon.dev.process/operation
                       :seon.dev.process/targets)
                 @requests)))))

(defn- configuration [root]
  {:seon.dev.config/root root
   :seon.dev.config/environment {}})

(deftest test-targets-route-to-the-canonical-runners
  (let [root (str (fs/normalize (fs/absolutize ".")))
        config (configuration root)
        pod (str (fs/path root "bin/test-cljs"))
        database (str (fs/path root "bin/test-writer"))
        operator ["bb" "--config" (str (fs/path root "bb.edn"))
                  "--deps-root" root "-m" "seon.dev.test-runner"]]
    (is (= [[pod "--test=seon.db-test"]]
           (#'cli/test-commands config ["pod" "seon.db-test"])))
    (is (= [[pod "--no-build" "--test=seon.db-test/query-roundtrip"]]
           (#'cli/test-commands
             config ["pod" "--no-build" "seon.db-test/query-roundtrip"])))
    (is (= [[database "seon.db.registry-test"]]
           (#'cli/test-commands config ["database" "seon.db.registry-test"])))
    (is (= [(conj operator "seon.dev.cli-test")]
           (#'cli/test-commands config ["operator" "seon.dev.cli-test"])))
    (is (= [operator [database] [pod]]
           (#'cli/test-commands config ["all"])))))

(deftest selector-free-targets-reject-accidental-arguments
  (let [config (configuration (str (fs/normalize (fs/absolutize "."))))]
    (testing "only the combined complete gate rejects selectors"
      (is (thrown? Exception
                   (#'cli/test-commands config ["all" "extra"]))))
    (is (thrown? Exception
                 (#'cli/test-commands config ["unknown"])))))

(deftest start-options-are-explicit-and-ordered
  (is (= {:seon.dev.start/open? true
          :seon.dev.start/config-path "config/system.edn"}
         (#'cli/parse-start-options ["--config" "config/system.edn" "--open"])))
  (is (thrown? Exception (#'cli/parse-start-options ["--config"])))
  (is (thrown? Exception (#'cli/parse-start-options ["--unknown"]))))

(deftest down-reports-the-coordinator-classification
  (let [configuration {:seon.dev.config/cluster-name "default"}
        request (atom nil)
        result (stop-result :seon.dev.process.operation/down
                            (set process/target-processes)
                            :seon.dev.process.classification/forced)]
    (with-redefs-fn
      {#'state/with-lock (fn [_ _ _ transition] (transition))
       #'process/clean-or-force!
       (fn [value]
         (reset! request value)
         result)}
      (fn []
        (is (= (str "○ Seon is down\n"
                    "  down: forced\n"
                    "    pod: forced\n"
                    "    writer: forced\n"
                    "    watcher: forced\n")
               (with-out-str (#'cli/down! configuration []))))))
    (is (= {:seon.dev.process/configuration configuration
            :seon.dev.process/operation :seon.dev.process.operation/down
            :seon.dev.process/targets (set process/target-processes)}
           @request))))

(deftest restart-coordinates-one-full-stop-before-reconcile
  (let [configuration {:seon.dev.config/cluster-name "default"}
        stopped (stop-result :seon.dev.process.operation/restart
                             (set process/target-processes)
                             :seon.dev.process.classification/absent)
        calls (atom [])]
    (with-redefs-fn
      {#'cli/select-config (fn [selected _] selected)
       #'state/with-lock (fn [_ _ _ transition] (transition))
       #'process/clean-or-force!
       (fn [request]
         (swap! calls conj [:stop request])
         stopped)
       #'cli/reconcile-development!
       (fn [selected stop-results]
         (swap! calls conj [:reconcile selected stop-results])
         {:seon.dev.target/status :seon.dev.target.status/ready})
       #'cli/print-ready!
       (fn [target open?]
         (swap! calls conj [:print target open?]))}
      (fn [] (#'cli/restart! configuration [])))
    (is (= [[:stop {:seon.dev.process/configuration configuration
                    :seon.dev.process/operation
                    :seon.dev.process.operation/restart
                    :seon.dev.process/targets (set process/target-processes)}]
            [:reconcile configuration [stopped]]
            [:print {:seon.dev.target/status :seon.dev.target.status/ready}
             false]]
           @calls))))

(deftest restart-uncertainty-prevents-reconcile-and-start
  (let [configuration {:seon.dev.config/cluster-name "default"}
        reconciled? (atom false)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"uncertain"
         (with-redefs-fn
           {#'cli/select-config (fn [selected _] selected)
            #'state/with-lock (fn [_ _ _ transition] (transition))
            #'process/clean-or-force!
            (fn [_]
              (throw (ex-info "containment uncertain"
                              {:seon.dev.process/classification
                               :seon.dev.process.classification/containment-uncertain})))
            #'cli/reconcile-development!
            (fn [& _]
              (reset! reconciled? true))}
           #(#'cli/restart! configuration []))))
    (is (false? @reconciled?))))

(deftest restart-resumes-retained-restore-before-ordinary-reconcile
  (let [configuration {:seon.dev.config/cluster-name "default"}
        calls (atom [])
        intent {:seon.dev.restore/intent-id "retained01"}]
    (with-redefs-fn
      {#'cli/select-config (fn [selected _] selected)
       #'state/with-lock (fn [_ _ _ transition] (transition))
       #'cli/retained-restore-intent (constantly intent)
       #'cli/resume-retained-restore!
       (fn [selected] (swap! calls conj [:resume selected]))
       #'cli/stop-development!
       (fn [& _] (swap! calls conj :unexpected-stop))
       #'cli/reconcile-development!
       (fn [selected]
         (swap! calls conj [:reconcile selected])
         {:seon.dev.target/status :seon.dev.target.status/ready})
       #'cli/print-ready! (fn [& _] nil)}
      (fn [] (#'cli/restart! configuration [])))
    (is (= [[:resume configuration] [:reconcile configuration]] @calls))))

(deftest retained-undo-auto-resume-carries-no-arbitrary-branch-selector
  (let [configuration {:seon.dev.config/cluster-name "default"}
        intent
        {:seon.dev.restore/intent-id "retained01"
         ::restore/operation :seon.dev.restore.operation/undo
         ::restore/selected-target-descriptor
         {:seon.launch/database
          {:seon.db.branch/head
           {:seon.db.branch/name :seon.restore.undo/r-prior}}}}
        calls (atom [])]
    (with-redefs-fn
      {#'cli/retained-restore-intent (constantly intent)
       #'restore-state/resume! (fn [request] (swap! calls conj request))}
      #(#'cli/resume-retained-restore! configuration))
    (is (= [{::restore-state/configuration configuration}] @calls))))

(deftest branch-commands-call-only-the-retained-lifecycle-owner
  (let [configuration {:seon.dev.config/launch-descriptor :source}
        open-request {::branch/configuration configuration
                      ::branch/lifecycle-path "/retained/default-proof.edn"}
        calls (atom [])]
    (with-redefs-fn
      {#'branch/request
       (fn [request]
         (swap! calls conj [:request request])
         open-request)
       #'branch/open! (fn [request] (swap! calls conj [:open request]) :opened)
       #'branch/restart!
       (fn [request] (swap! calls conj [:restart request]) :restarted)
       #'branch/close! (fn [request] (swap! calls conj [:close request]) :closed)
       #'branch/status
       (fn [selected name]
         (swap! calls conj [:status selected name])
         {:seon.dev.target/status :seon.dev.target.status/degraded})
       #'cli/print-branch-result!
       (fn [result] (swap! calls conj [:print-result result]))
       #'cli/print-branch-status!
       (fn [result] (swap! calls conj [:print-status result]))}
      (fn []
        (#'cli/branch! configuration ["open" "proof"])
        (#'cli/branch! configuration ["restart" "proof"])
        (#'cli/branch! configuration ["close" "proof"])
        (#'cli/branch! configuration ["status" "proof"])))
    (is (= [[:request {::branch/configuration configuration
                       ::branch/name "proof"}]
            [:open open-request]
            [:print-result :opened]
            [:request {::branch/configuration configuration
                       ::branch/name "proof"}]
            [:restart open-request]
            [:print-result :restarted]
            [:request {::branch/configuration configuration
                       ::branch/name "proof"}]
            [:close {::branch/configuration configuration
                     ::branch/lifecycle-path
                     "/retained/default-proof.edn"}]
            [:print-result :closed]
            [:status configuration "proof"]
            [:print-status
             {:seon.dev.target/status :seon.dev.target.status/degraded}]]
           @calls))
    (doseq [arguments [["create" "proof"]
                       ["release" "proof"]
                       ["delete" "proof"]
                       ["open" "proof" "extra"]
                       ["status" "proof" "--json"]]]
      (is (thrown? Exception (#'cli/branch! configuration arguments))))))

(deftest ordinary-status-includes-retained-branch-projections
  (let [configuration {:seon.dev.config/launch-descriptor :source
                       :seon.dev.config/cluster-dir "/cluster"
                       :seon.dev.config/cluster-name "default"}
        retained [{::branch/runtime-cluster "default-proof"}]]
    (with-redefs-fn
      {#'process/ownership-conflicts (fn [_] [])
       #'artifact/read-manifest (constantly nil)
       #'branch/inventory (fn [selected]
                            (is (= configuration selected))
                            retained)}
      (fn []
        (is (= retained
               (:seon.dev.target/branches
                (#'cli/status-value configuration))))))))

(deftest retained-restore-makes-ordinary-status-explicitly-degraded
  (let [configuration {:seon.dev.config/launch-descriptor :source
                       :seon.dev.config/cluster-dir "/cluster"
                       :seon.dev.config/cluster-name "default"}
        intent-id "restoretest01"]
    (with-redefs-fn
      {#'process/ownership-conflicts (constantly [])
       #'artifact/read-manifest (constantly nil)
       #'branch/inventory (constantly [])
       #'cli/retained-restore-intent
       (constantly {::restore/intent-id intent-id})}
      (fn []
        (let [status (#'cli/status-value configuration)]
          (is (= :seon.dev.target.status/degraded
                 (:seon.dev.target/status status)))
          (is (= :seon.dev.target.maintenance/restore
                 (:seon.dev.target/maintenance status)))
          (is (= intent-id (:seon.dev.restore/intent-id status))))))))

(deftest config-is-implicit-only-for-a-fresh-database
  (let [root (fs/create-temp-dir {:prefix "seon-cli-config-"})
        cluster (fs/path root "data/clusters/default")
        default-config (fs/path root "config/system.edn")
        explicit-config (fs/path root "config/custom.edn")
        base {:seon.dev.config/root (str root)
              :seon.dev.config/cluster-dir (str cluster)
              :seon.dev.config/environment {}}]
    (try
      (fs/create-dirs (fs/parent default-config))
      (spit (str default-config) "{}\n")
      (spit (str explicit-config) "{}\n")
      (testing "first-ever boot selects the shipped manifest"
        (is (= (str default-config)
               (get-in (#'cli/select-config base nil)
                       [:seon.dev.config/environment "SEON_CONFIG"]))))
      (testing "an existing database receives no ambient config apply"
        (fs/create-dirs (fs/path cluster "db"))
        (spit (str (fs/path cluster "db/root.ksv")) "database")
        (is (nil? (get-in (#'cli/select-config base nil)
                          [:seon.dev.config/environment "SEON_CONFIG"]))))
      (testing "an explicit relative path is rooted at the checkout"
        (is (= (str explicit-config)
               (get-in (#'cli/select-config base "config/custom.edn")
                       [:seon.dev.config/environment "SEON_CONFIG"]))))
      (finally (fs/delete-tree root {:force true})))))

(deftest explicit-config-apply-uses-the-ready-pod-operation
  (let [configuration {:seon.dev.config/root "/repo"
                       :seon.dev.config/environment {}}
        selected      (assoc-in configuration
                                [:seon.dev.config/environment "SEON_CONFIG"]
                                "/repo/config/system.edn")
        calls         (atom [])]
    (with-redefs-fn
      {#'cli/select-config (fn [value path]
                             (swap! calls conj [:select value path])
                             selected)
       #'state/with-lock (fn [value owner timeout-ms thunk]
                           (swap! calls conj [:lock value owner timeout-ms])
                           (thunk))
       #'cli/apply-live-config! (fn [value]
                                  (swap! calls conj [:apply value])
                                  {:seon.state/ok? true
                                   :seon.state/changed? false
                                   :seon.state/operations 0
                                   :seon.state/basis-t 42})
       #'cli/print-config-result! (fn [result]
                                    (swap! calls conj [:print result]))
       #'cli/reconcile-development!
       (fn [_] (throw (ex-info "config apply widened into up" {})))}
      (fn [] (#'cli/config! configuration ["apply" "config/system.edn"])))
    (is (= [[:select configuration "config/system.edn"]
            [:lock selected :stack 300000]
            [:apply selected]
            [:print {:seon.state/ok? true
                     :seon.state/changed? false
                     :seon.state/operations 0
                     :seon.state/basis-t 42}]]
           @calls))))

(deftest legacy-database-layout-is-never-silently-replaced
  (let [root (fs/create-temp-dir {:prefix "seon-cli-legacy-db-"})
        cluster (fs/path root "data/clusters/acme")
        configuration {:seon.dev.config/cluster-dir (str cluster)
                       :seon.dev.config/cluster-name "acme"}]
    (try
      (fs/create-dirs (fs/path cluster "store"))
      (is (thrown-with-msg?
            Exception #"Refusing to create a fresh database"
            (#'cli/assert-current-database-layout! configuration)))
      (is (= {:seon.dev.target/name :seon.dev.target/development
              :seon.dev.target/status
              :seon.dev.target.status/ownership-conflict
              :seon.dev.target/failure
              :seon.dev.target.failure/legacy-database-layout
              :seon.dev.target/cluster-name "acme"
              :seon.dev.target/database-path (str (fs/path cluster "db"))
              :seon.dev.target/legacy-database-path
              (str (fs/path cluster "store"))}
             (#'cli/status-value configuration)))
      (fs/create-dirs (fs/path cluster "db"))
      (is (= configuration
             (#'cli/assert-current-database-layout! configuration)))
      (finally (fs/delete-tree root {:force true})))))

(deftest cluster-reset-rebuilds-before-reconciling
  (let [root (fs/create-temp-dir {:prefix "seon-cli-reset-"})
        cluster (fs/path root "data/clusters/default")
        database (fs/path cluster "db")
        configuration {:seon.dev.config/root (str root)
                       :seon.dev.config/cluster-dir (str cluster)
                       :seon.dev.config/cluster-name "default"
                       :seon.dev.config/environment {}}
        requests (atom [])
        reconciled (atom [])]
    (try
      (fs/create-dirs database)
      (spit (str (fs/path database "old.ksv")) "old database")
      (with-redefs-fn
        {#'state/with-lock (fn [_configuration _owner _timeout thunk]
                            (thunk))
         #'process/clean-or-force!
         (fn [{:seon.dev.process/keys [operation targets] :as request}]
           (swap! requests conj request)
           (stop-result operation targets
                        :seon.dev.process.classification/forced))
         #'cli/select-config (fn [selected _path] selected)
         #'cli/reconcile-development!
         (fn [selected stop-results]
           (is (not (fs/exists? database)))
           (swap! reconciled conj [selected stop-results])
           {:seon.dev.target/status :seon.dev.target.status/ready
            :seon.dev.target/stop-results stop-results})}
        (fn []
          (is (= (str "  reset: forced\n"
                      "    pod: forced\n"
                      "    writer: forced\n"
                      "● cluster default reset and ready\n")
                 (with-out-str
                   (#'cli/reset-cluster! configuration ["default"]))))))
      (is (= [{:seon.dev.process/configuration configuration
               :seon.dev.process/operation :seon.dev.process.operation/reset
               :seon.dev.process/targets #{process/pod-id process/writer-id}}]
             @requests))
      (is (= configuration (ffirst @reconciled)))
      (is (= :seon.dev.process.operation/reset
             (-> @reconciled first second first
                 :seon.dev.process/operation)))
      (finally (fs/delete-tree root {:force true})))))

(deftest cluster-reset-uncertainty-preserves-the-database
  (let [root (fs/create-temp-dir {:prefix "seon-cli-reset-uncertain-"})
        cluster (fs/path root "data/clusters/default")
        database (fs/path cluster "db")
        configuration {:seon.dev.config/root (str root)
                       :seon.dev.config/cluster-dir (str cluster)
                       :seon.dev.config/cluster-name "default"
                       :seon.dev.config/environment {}}
        reconciled? (atom false)]
    (try
      (fs/create-dirs database)
      (spit (str (fs/path database "old.ksv")) "old database")
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"uncertain"
           (with-redefs-fn
             {#'state/with-lock (fn [_ _ _ transition] (transition))
              #'process/clean-or-force!
              (fn [_]
                (throw (ex-info "containment uncertain"
                                {:seon.dev.process/classification
                                 :seon.dev.process.classification/containment-uncertain})))
              #'cli/reconcile-development!
              (fn [& _] (reset! reconciled? true))}
             #(#'cli/reset-cluster! configuration ["default"]))))
      (is (fs/exists? (fs/path database "old.ksv")))
      (is (false? @reconciled?))
      (finally (fs/delete-tree root {:force true})))))

(deftest cluster-restore-runs-under-the-stack-lock
  (let [configuration {:seon.dev.config/cluster-name "default"}
        calls (atom [])
        plan {::restore/confirmation-text "EXACT CONFIRMATION"}
        result
        {:seon.dev.restore/intent-id "restoretest01"
         ::restore-state/restored-branch-head
         {:seon.db.branch/store-id (random-uuid)
          :seon.db.branch/name :db
          :seon.db.branch/commit-id (random-uuid)
          :seon.db.branch/basis-t 42}
         ::restore-state/admin-outcome
         :seon.db.restore-admin.outcome/applied
         ::restore-state/transitions []}]
    (with-redefs-fn
      {#'state/with-lock
       (fn [selected owner timeout transition]
         (swap! calls conj [:lock selected owner timeout])
         (transition))
       #'restore-state/plan!
       (fn [request]
         (swap! calls conj [:plan request])
         plan)
       #'cli/read-console-confirmation!
       (fn [expected]
         (swap! calls conj [:console expected])
         expected)
       #'restore-state/apply!
       (fn [request]
         (swap! calls conj [:apply request])
         result)}
      (fn []
        (is (str/includes?
             (with-out-str (#'cli/restore-cluster! configuration ["target"]))
             "restored retained branch target"))))
    (is (= [[:lock configuration :stack 1800000]
            [:plan {::restore-state/configuration configuration
                    ::restore-state/branch-name "target"}]
            [:console "EXACT CONFIRMATION"]
            [:lock configuration :stack 1800000]
            [:apply {::restore-state/configuration configuration
                     ::restore-state/branch-name "target"
                     ::restore/plan plan
                     ::restore/confirmation-text "EXACT CONFIRMATION"}]]
           @calls))
    (is (thrown? Exception
                 (#'cli/restore-cluster! configuration [])))
    (is (thrown? Exception
                 (#'cli/restore-cluster! configuration ["a" "b"])))))

(deftest restore-cli-separates-plan-apply-and-abort-authority
  (let [root (fs/create-temp-dir {:prefix "seon-cli-restore-plan-"})
        plan-path (str (fs/path root "plan.edn"))
        configuration {:seon.dev.config/cluster-name "default"}
        plan {::restore/confirmation-text "EXACT"}
        result
        {:seon.dev.restore/intent-id "restoretest01"
         ::restore-state/restored-branch-head
         {:seon.db.branch/store-id (random-uuid)
          :seon.db.branch/name :db
          :seon.db.branch/commit-id (random-uuid)
          :seon.db.branch/basis-t 42}
         ::restore-state/admin-outcome
         :seon.db.restore-admin.outcome/applied
         ::restore-state/transitions []}
        calls (atom [])]
    (try
      (spit plan-path (pr-str plan))
      (with-redefs-fn
        {#'state/with-lock (fn [_ _ _ transition] (transition))
         #'restore-state/plan! (constantly plan)}
        (fn []
          (is (= (str (pr-str plan) "\n")
                 (with-out-str
                   (#'cli/restore-cluster!
                    configuration ["target" "--plan" "--edn"]))))))
      (with-redefs-fn
        {#'state/with-lock (fn [_ _ _ transition] (transition))
         #'restore-state/plan! (constantly plan)
         #'cli/read-console-confirmation!
         (fn [_]
           (throw (ex-info "no console" {})))
         #'restore-state/apply!
         (fn [_] (swap! calls conj :unexpected-apply))}
        (fn []
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"no console"
               (#'cli/restore-cluster! configuration ["target"])))))
      (is (empty? @calls))
      (with-redefs-fn
        {#'state/with-lock (fn [_ _ _ transition] (transition))
         #'restore-state/apply!
         (fn [request]
           (swap! calls conj [:apply request])
           result)}
        (fn []
          (#'cli/restore-cluster!
           configuration
           ["target" "--apply-plan" plan-path "--confirm" "EXACT"])))
      (is (= [[:apply {::restore-state/configuration configuration
                       ::restore-state/branch-name "target"
                       ::restore/plan plan
                       ::restore/confirmation-text "EXACT"}]]
             @calls))
      (reset! calls [])
      (with-redefs-fn
        {#'state/with-lock (fn [_ _ _ transition] (transition))
         #'restore-state/apply!
         (fn [request]
           (swap! calls conj [:apply request])
           result)}
        (fn []
          (#'cli/restore-cluster!
           configuration
           ["target" "--apply-plan" plan-path "--confirm" "EXACT"
            "--proof-crash-cut" "after-force-before-completion"])))
      (is (= [[:apply
               {::restore-state/configuration configuration
                ::restore-state/branch-name "target"
                ::restore/plan plan
                ::restore/confirmation-text "EXACT"
                ::restore-state/proof-crash-cut
                :seon.dev.restore.proof-cut/after-force-before-completion}]]
             @calls))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Unknown restore proof crash cut"
           (#'cli/restore-cluster!
            configuration
            ["target" "--apply-plan" plan-path "--confirm" "EXACT"
             "--proof-crash-cut" "unknown"])))
      (reset! calls [])
      (with-redefs-fn
        {#'state/with-lock (fn [_ _ _ transition] (transition))
         #'restore-state/abort!
         (fn [request]
           (swap! calls conj [:abort request])
           {:seon.dev.restore/intent-id "restoretest01"
            :seon.dev.restore/plan-digest (apply str (repeat 64 "a"))
            ::restore-state/aborted? true
            ::restore-state/prior-main-branch-head
            (::restore-state/restored-branch-head result)
            ::restore-state/current-main-branch-head
            (::restore-state/restored-branch-head result)
            ::restore-state/selected-target-branch-head
            (assoc (::restore-state/restored-branch-head result)
                   :seon.db.branch/name :seon.branch/target)})}
        (fn []
          (#'cli/restore-cluster!
           configuration ["target" "--abort" "--confirm" "ABORT"])))
      (is (= [[:abort {::restore-state/configuration configuration
                       ::restore-state/branch-name "target"
                       ::restore/confirmation-text "ABORT"}]]
             @calls))
      (finally (fs/delete-tree root {:force true})))))

(deftest undo-cli-binds-plan-apply-and-abort-to-one-completion-id
  (let [root (fs/create-temp-dir {:prefix "seon-cli-undo-plan-"})
        plan-path (str (fs/path root "undo-plan.edn"))
        configuration {:seon.dev.config/cluster-name "default"}
        completion-id "priorundo001"
        plan {::restore/confirmation-text "EXACT UNDO"}
        result
        {:seon.dev.restore/intent-id "restoretest01"
         ::restore-state/restored-branch-head
         {:seon.db.branch/store-id (random-uuid)
          :seon.db.branch/name :db
          :seon.db.branch/commit-id (random-uuid)
          :seon.db.branch/basis-t 42}
         ::restore-state/admin-outcome
         :seon.db.restore-admin.outcome/applied
         ::restore-state/transitions []}
        calls (atom [])]
    (try
      (spit plan-path (pr-str plan))
      (with-redefs-fn
        {#'state/with-lock (fn [_ _ _ transition] (transition))
         #'restore-state/plan-undo!
         (fn [request]
           (swap! calls conj [:plan request])
           plan)}
        #(is (= (str (pr-str plan) "\n")
                (with-out-str
                  (#'cli/undo-cluster!
                   configuration [completion-id "--plan" "--edn"])))))
      (is (= [[:plan {::restore-state/configuration configuration
                      ::restore-state/completion-id completion-id}]]
             @calls))
      (reset! calls [])
      (with-redefs-fn
        {#'state/with-lock (fn [_ _ _ transition] (transition))
         #'restore-state/apply-undo!
         (fn [request]
           (swap! calls conj [:apply request])
           result)}
        #(#'cli/undo-cluster!
          configuration
          [completion-id "--apply-plan" plan-path
           "--confirm" "EXACT UNDO"]))
      (is (= [[:apply {::restore-state/configuration configuration
                       ::restore-state/completion-id completion-id
                       ::restore/plan plan
                       ::restore/confirmation-text "EXACT UNDO"}]]
             @calls))
      (reset! calls [])
      (with-redefs-fn
        {#'state/with-lock (fn [_ _ _ transition] (transition))
         #'restore-state/abort-undo!
         (fn [request]
           (swap! calls conj [:abort request])
           {:seon.dev.restore/intent-id "restoretest01"
            :seon.dev.restore/plan-digest (apply str (repeat 64 "a"))
            ::restore-state/aborted? true
            ::restore-state/prior-main-branch-head
            (::restore-state/restored-branch-head result)
            ::restore-state/current-main-branch-head
            (::restore-state/restored-branch-head result)
            ::restore-state/selected-target-branch-head
            (assoc (::restore-state/restored-branch-head result)
                   :seon.db.branch/name :seon.restore.undo/r-prior)})}
        #(#'cli/undo-cluster!
          configuration [completion-id "--abort" "--confirm" "ABORT"]))
      (is (= [[:abort {::restore-state/configuration configuration
                       ::restore-state/completion-id completion-id
                       ::restore/confirmation-text "ABORT"}]]
             @calls))
      (is (thrown? Exception (#'cli/undo-cluster! configuration [])))
      (is (thrown? Exception
                   (#'cli/undo-cluster! configuration
                    [completion-id "unexpected"])))
      (finally (fs/delete-tree root {:force true})))))

(deftest cluster-undo-dispatches-only-to-the-completion-selected-owner
  (let [configuration {:seon.dev.config/cluster-name "default"}
        calls (atom [])]
    (with-redefs-fn
      {#'cli/undo-cluster!
       (fn [selected arguments]
         (swap! calls conj [selected (vec arguments)]))}
      #(#'cli/cluster! configuration ["undo" "priorundo001" "--plan" "--edn"]))
    (is (= [[configuration ["priorundo001" "--plan" "--edn"]]] @calls))))

(deftest mutating-branch-commands-share-the-restore-stack-lock
  (let [configuration {:cluster :default}
        calls (atom [])
        request {::branch/lifecycle-path "/branch.edn"}]
    (with-redefs-fn
      {#'cli/branch-request (fn [_ _] request)
       #'state/with-lock
       (fn [selected owner timeout transition]
         (swap! calls conj [:lock selected owner timeout])
         (transition))
       #'branch/open! (fn [_] (swap! calls conj :open) {})
       #'branch/restart! (fn [_] (swap! calls conj :restart) {})
       #'branch/close! (fn [_] (swap! calls conj :close) {})
       #'cli/print-branch-result! (constantly nil)}
      (fn []
        (#'cli/branch! configuration ["open" "proof"])
        (#'cli/branch! configuration ["restart" "proof"])
        (#'cli/branch! configuration ["close" "proof"])))
    (is (= [[:lock configuration :stack 1800000] :open
            [:lock configuration :stack 1800000] :restart
            [:lock configuration :stack 1800000] :close]
           @calls))))

(deftest retained-restore-blocks-competing-branch-mutation
  (let [configuration {:cluster :default}
        called? (atom false)]
    (is
     (thrown-with-msg?
      clojure.lang.ExceptionInfo
      #"retained restore"
      (with-redefs-fn
        {#'cli/branch-request
         (fn [_ _] {::branch/lifecycle-path "/branch.edn"})
         #'state/with-lock (fn [_ _ _ transition] (transition))
         #'cli/require-no-retained-restore!
         (fn [& _]
           (throw (ex-info "retained restore blocks mutation" {})))
         #'branch/open! (fn [_] (reset! called? true))}
        #(#'cli/branch! configuration ["open" "proof"]))))
    (is (false? @called?))))
