(ns seon.db.restore-admin-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [datahike.index.audit :as index-audit]
            [seon.db.backend :as backend]
            [seon.db.branch :as branch]
            [seon.db.protocol :as protocol]
            [seon.db.registry :as registry]
            [seon.db.restore-admin :as restore-admin]
            [seon.db.server :as server]
            [seon.db.writer :as writer]
            [seon.dev.restore :as restore]
            [seon.embed :as embed]
            [seon.launch :as launch])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- thrown-info [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo exception exception)))

(defn- delete-tree! [^File root]
  (when (.exists root)
    (run! (fn [^File file] (.delete file))
          (reverse (file-seq root)))))

(defn- restore-intent-fixture []
  (let [database-id (random-uuid)
        old {::branch/store-id database-id
             ::branch/name :db
             ::branch/commit-id (random-uuid)
             ::branch/basis-t 100}
        target {::branch/store-id database-id
                ::branch/name :restore.test/selected
                ::branch/commit-id (random-uuid)
                ::branch/basis-t 110}
        intent-id #uuid "70000000-0000-4000-8000-000000000002"
        digest (apply str (repeat 64 "a"))
        writer-owner
        {::launch/writer-cluster "default"
         ::launch/writer-process-dir "/process/writer"
         ::launch/request-socket-path "/process/request.sock"
         ::launch/writer-repl-port-file "/process/writer.port"}
        descriptor
        (fn [cluster branch-head autonomous? writable read-only]
          {::launch/runtime
           {::launch/runtime-cluster cluster
            ::launch/artifact-flavor :seon.dev.artifact.flavor/default
            ::launch/client-build-id "client-build"
            :seon.client/launch-capability
            {:seon.client/autonomous? autonomous?}}
           ::launch/database
           {::protocol/database-name cluster
            ::branch/connection-id (branch/connection-id branch-head)
            ::branch/head branch-head
            ::protocol/backend :file
            ::protocol/database-path "/cluster/db"}
           ::launch/writer-owner writer-owner
           ::launch/process
           {::launch/process-dir (str "/process/" cluster)
            ::launch/log-dir (str "/logs/" cluster)
            ::launch/http-port (if autonomous? 7890 0)
            ::launch/http-port-file (str "/process/" cluster "/http.port")}
           ::launch/blob-storage-view
           {:my.blob/writable-dir writable
            :my.blob/read-only-dirs read-only}})
        undo-branch (keyword "seon.restore.undo" (str "r-" intent-id))
        prepared-branch
        (keyword "seon.restore.target" (str "r-" intent-id))]
    (restore/derive-intent
     {::restore/intent-id intent-id
      ::restore/operation :seon.dev.restore.operation/restore
      ::restore/pre-restore-main-descriptor
      (descriptor "default" old true "/main/blobs" [])
      ::restore/selected-target-descriptor
      (descriptor "target" target false "/target/blobs" ["/main/blobs"])
      ::restore/expected-branch-roster
      #{:db :restore.test/selected undo-branch prepared-branch}
      ::restore/protocol-version protocol/current-version
      ::restore/artifact-identity
      {:seon.dev.artifact/application-digest digest}
      ::restore/consumer-generations
      {:seon.dev.process/pod (random-uuid)}
      ::restore/core-overlay-selection :seon.dev.restore.overlay/preserve
      ::restore/config-overlay-selection :seon.dev.restore.overlay/preserve
      ::restore/reachable-hash-digest digest})))

(defn- with-restore-database
  ([f] (with-restore-database :memory f))
  ([backend-kind f]
  (let [database-name (keyword "restore-admin-test" (str (random-uuid)))
        database-root (File. "tmp" (str "restore-admin-" (random-uuid)))
        database-path (.getPath (File. database-root "db"))
        config (backend/datahike-config
                (cond-> {::backend/database-name database-name
                         ::backend/backend backend-kind}
                  (= :file backend-kind)
                  (assoc ::backend/path database-path)))
        open-connections (atom [])]
    (d/create-database config)
    (try
      (let [main (d/connect config)
            _ (swap! open-connections conj main)
            _ (when (= :file backend-kind) (embed/install! main))
            _ (d/transact main
                          [{:db/ident :restore.admin/value
                            :db/valueType :db.type/string
                            :db/cardinality :db.cardinality/one}
                           {:db/id -1 :restore.admin/value "old"}])
            old (branch/head (d/db main))
            selected-branch :restore.admin/selected
            prepared-branch :restore.admin/prepared
            undo-branch :restore.admin/undo
            _ (d/branch! main (::branch/commit-id old) selected-branch)
            selected-config
            (backend/datahike-config
             (cond->
               {::backend/database-name database-name
                ::backend/backend backend-kind
                ::branch/connection-id
                (assoc (branch/connection-id old) 1 selected-branch)}
               (= :file backend-kind)
               (assoc ::backend/path database-path)))
            selected (d/connect selected-config)
            _ (swap! open-connections conj selected)
            embedding (into [(float 1.0)] (repeat 1535 (float 0.0)))
            _ (d/transact selected
                          (cond->
                            [{:db/id -1 :restore.admin/value "target"}]
                            (= :file backend-kind)
                            (conj {:db/id -2
                                   :seon/embedding embedding
                                   :seon.embed/source-hash "restore-target"})))
            target (branch/head (d/db selected))
            _ (d/branch! selected (::branch/commit-id target)
                         prepared-branch)
            _ (d/branch! main (::branch/commit-id old) undo-branch)
            roster #{:db selected-branch prepared-branch undo-branch}
            request
            (cond->
              {::registry/database-name database-name
             ::registry/backend backend-kind
             ::registry/pre-restore-main-branch-head old
             ::registry/selected-target-branch-head target
             ::registry/prepared-target-branch-head
             (assoc target ::branch/name prepared-branch)
             ::registry/undo-branch-head
             (assoc old ::branch/name undo-branch)
             ::registry/expected-branch-roster roster
             ::registry/validate-db! (constantly true)}
              (= :file backend-kind)
              (assoc ::registry/path database-path))]
        (d/release selected)
        (d/release main)
        (reset! open-connections [])
        (f {:config config :request request :target target :roster roster
            :embedding embedding}))
      (finally
        (doseq [connection @open-connections]
          (try (d/release connection) (catch Throwable _)))
        (d/delete-database config)
        (delete-tree! database-root))))))

(deftest admin-restore-is-exact-and-retry-converges-after-result-loss
  (with-restore-database
    (fn [{:keys [request target roster]}]
      (let [applied (registry/admin-restore-main! request)
            retry (registry/admin-restore-main! request)]
        (is (= :seon.db.registry.admin/applied
               (::registry/admin-outcome applied)))
        (is (true? (::registry/force-invoked? applied)))
        (is (= :seon.db.restore-admin.connection/released
               (::registry/admin-connection-state applied)))
        (is (= (::branch/basis-t target)
               (get-in applied [::registry/branch-head ::branch/basis-t])))
        (is (= roster (::registry/branch-roster applied)))
        (is (= :seon.db.registry.admin/already-applied
               (::registry/admin-outcome retry))
            "a lost first result converges from durable storage")
        (is (false? (::registry/force-invoked? retry)))
        (is (= (::registry/branch-head applied)
               (::registry/branch-head retry)))))))

(deftest file-backed-proximum-root-follows-the-forced-primary-head
  (with-restore-database
    :file
    (fn [{:keys [config request target roster embedding]}]
      (let [applied (registry/admin-restore-main! request)
            retry (registry/admin-restore-main! request)
            connection (d/connect config)]
        (try
          (let [main-db (d/db connection)
                target-db
                (d/branch-as-db
                 connection
                 (get-in request
                         [::registry/prepared-target-branch-head
                          ::branch/name]))
                main-index (get-in main-db [:secondary-indices embed/index-ident])
                target-index
                (get-in target-db [:secondary-indices embed/index-ident])]
            (is (= :seon.db.registry.admin/applied
                   (::registry/admin-outcome applied)))
            (is (true? (::registry/force-invoked? applied)))
            (is (= :seon.db.restore-admin.connection/released
                   (::registry/admin-connection-state applied)))
            (is (= :seon.db.registry.admin/already-applied
                   (::registry/admin-outcome retry)))
            (is (false? (::registry/force-invoked? retry)))
            (is (= roster (set (d/branches connection))))
            (is (= (::branch/basis-t target)
                   (::branch/basis-t (branch/head main-db))))
            (is (= #{(::branch/commit-id target)}
                   (set (d/parent-commit-ids main-db))))
            (is (= (vec (d/datoms target-db :eavt))
                   (vec (d/datoms main-db :eavt))))
            (is (some? main-index))
            (is (some? target-index))
            (is (= 1 (count (embed/knn target-db embedding 5))))
            (is (= 1 (count (embed/knn main-db embedding 5))))
            (is (some? (index-audit/-merkle-root main-index)))
            (is (= (index-audit/-merkle-root target-index)
                   (index-audit/-merkle-root main-index))
                "guarded force moves the native secondary with the primary head"))
          (finally
            (d/release connection)))))))

(deftest admin-restore-refuses-stale-roster-before-force
  (with-restore-database
    (fn [{:keys [request]}]
      (let [force-calls (atom 0)
            failure
            (with-redefs [d/force-branch!
                          (fn [& _]
                            (swap! force-calls inc))]
              (thrown-info
               #(registry/admin-restore-main!
                 (update request ::registry/expected-branch-roster
                         conj :restore.admin/missing))))]
        (is (= :seon.db.protocol.error/stale-branch-roster
               (:seon.error/kind (ex-data failure))))
        (is (zero? @force-calls))))))

(deftest admin-connection-evidence-is-conservative
  (testing "connect failure"
    (let [failure
          (with-redefs [d/connect
                        (fn [_]
                          (throw (ex-info "connect failed" {})))]
            (thrown-info
             #(#'registry/with-admin-connection
               {} (atom false) identity)))]
      (is (= :seon.db.restore-admin.connection/cleanup-unproved
             (::registry/admin-connection-state (ex-data failure))))
      (is (false? (::registry/force-invoked? (ex-data failure))))))
  (testing "operation failure before force"
    (let [releases (atom 0)
          failure
          (with-redefs [d/connect (constantly ::connection)
                        d/release (fn [_] (swap! releases inc))]
            (thrown-info
             #(#'registry/with-admin-connection
               {} (atom false)
               (fn [_] (throw (ex-info "operation failed" {}))))))]
      (is (= 1 @releases))
      (is (= :seon.db.restore-admin.connection/released
             (::registry/admin-connection-state (ex-data failure))))
      (is (false? (::registry/force-invoked? (ex-data failure))))))
  (testing "release failure after force"
    (let [failure
          (with-redefs [d/connect (constantly ::connection)
                        d/release
                        (fn [_]
                          (throw (ex-info "release failed" {})))]
            (thrown-info
             #(#'registry/with-admin-connection
               {} (atom true) (constantly {::registry/force-invoked? true}))))]
      (is (= :seon.db.restore-admin.connection/cleanup-unproved
             (::registry/admin-connection-state (ex-data failure))))
      (is (true? (::registry/force-invoked? (ex-data failure)))))))

(deftest post-force-release-and-readback-failures-retain-force-evidence
  (doseq [[label fail-release fail-connect]
          [["first release" 1 nil]
           ["second release" 2 nil]
           ["fresh readback connect" nil 2]]]
    (testing label
      (with-restore-database
        (fn [{:keys [request]}]
          (let [real-connect d/connect
                real-release d/release
                connects (atom 0)
                releases (atom 0)
                failure
                (with-redefs
                  [d/connect
                   (fn [config]
                     (let [attempt (swap! connects inc)]
                       (if (= fail-connect attempt)
                         (throw (ex-info "injected connect failure" {}))
                         (real-connect config))))
                   d/release
                   (fn [connection]
                     (let [attempt (swap! releases inc)]
                       (real-release connection)
                       (when (= fail-release attempt)
                         (throw (ex-info "injected release failure" {})))))]
                  (thrown-info #(registry/admin-restore-main! request)))]
            (is (= :seon.db.restore-admin.connection/cleanup-unproved
                   (::registry/admin-connection-state (ex-data failure))))
            (is (true? (::registry/force-invoked? (ex-data failure))))))))))

(deftest atomic-result-publication-replaces-the-operator-provided-path
  (let [directory (Files/createTempDirectory
                   "restore-admin-result"
                   (make-array FileAttribute 0))
        path (str (.resolve directory "restore.edn"))]
    (#'server/atomic-write-edn! path {:attempt 1 :result :rejected})
    (#'server/atomic-write-edn! path {:attempt 2 :result :already-applied})
    (is (= {:attempt 2 :result :already-applied}
           (edn/read-string (slurp path))))
    (Files/deleteIfExists (.resolve directory "restore.edn"))
    (Files/deleteIfExists directory)))

(deftest writer-maps-known-and-unknown-registry-outcomes-into-closed-results
  (let [intent (restore-intent-fixture)
        base (restore-admin/result-base intent)
        forced (assoc (:seon.db.restore-admin/selected-target-branch-head base)
                      ::branch/name :db)
        known
        (with-redefs
          [registry/admin-restore-main!
           (fn [_]
             {::registry/admin-outcome :seon.db.registry.admin/applied
              ::registry/pre-restore-main-branch-head
              (:seon.db.restore-admin/pre-restore-main-branch-head base)
              ::registry/selected-target-branch-head
              (:seon.db.restore-admin/selected-target-branch-head base)
              ::registry/prepared-target-branch-head
              (:seon.db.restore-admin/prepared-target-branch-head base)
              ::registry/undo-branch-head
              (:seon.db.restore-admin/undo-branch-head base)
              ::registry/branch-head forced
              ::registry/branch-roster
              (:seon.dev.restore/expected-branch-roster intent)
              ::registry/force-invoked? true
              ::registry/admin-connection-state
              :seon.db.restore-admin.connection/released})]
          (writer/admin-restore! {:seon.db.restore-admin/intent intent}))
        unknown
        (with-redefs
          [registry/admin-restore-main!
           (fn [_]
             (throw (ex-info "escaped post-force failure" {})))]
          (writer/admin-restore! {:seon.db.restore-admin/intent intent}))]
    (is (= :seon.db.restore-admin.outcome/applied
           (:seon.db.restore-admin/outcome known)))
    (is (restore-admin/valid-result? known))
    (is (= :seon.db.restore-admin.effect/unknown
           (:seon.db.restore-admin/effect-state unknown)))
    (is (not (contains? unknown :seon.db.restore-admin/force-invoked?)))
    (is (restore-admin/valid-result? unknown))))

(deftest intent-read-is-byte-bounded-on-the-open-stream
  (let [directory (Files/createTempDirectory
                   "restore-admin-bounded"
                   (make-array FileAttribute 0))
        path (.resolve directory "oversized.edn")]
    (spit (str path) (apply str (repeat (inc (* 1024 1024)) "x")))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"bounded input size"
         (#'server/read-bounded-edn (str path))))
    (Files/deleteIfExists path)
    (Files/deleteIfExists directory)))

(deftest post-invocation-throw-or-invalid-output-is-explicitly-unknown
  (let [directory (Files/createTempDirectory
                   "restore-admin-unknown"
                   (make-array FileAttribute 0))
        intent-path (str (.resolve directory "intent.edn"))
        result-path (str (.resolve directory "restore.edn"))
        intent (restore-intent-fixture)
        ordinary-starts (atom 0)
        invocation {::server/intent-path intent-path
                    ::server/result-path result-path}]
    (spit intent-path (pr-str intent))
    (doseq [[label implementation]
            [["unexpected post-force throw"
              (fn [_]
                (throw
                 (ex-info "result lost after force"
                          {::registry/force-invoked? true})))]
             ["invalid output" (constantly {})]]]
      (testing label
        (let [result
              (with-redefs [writer/admin-restore! implementation
                            writer/start!
                            (fn [_]
                              (swap! ordinary-starts inc)
                              (throw (ex-info "ordinary writer started" {})))]
                (server/run-restore-admin! invocation))]
          (is (= :seon.db.restore-admin.effect/unknown
                 (:seon.db.restore-admin/effect-state result)))
          (is (= :seon.db.restore-admin.connection/cleanup-unproved
                 (:seon.db.restore-admin/connection-state result)))
          (is (not (contains? result :seon.db.restore-admin/force-invoked?)))
          (is (= result (edn/read-string (slurp result-path)))))))
    (is (zero? @ordinary-starts))
    (is (= {::server/intent-path intent-path
            ::server/result-path result-path}
           (#'server/admin-configuration
            ["--restore-admin-intent" intent-path
             "--restore-admin-result" result-path])))
    (Files/deleteIfExists (.resolve directory "intent.edn"))
    (Files/deleteIfExists (.resolve directory "restore.edn"))
    (Files/deleteIfExists directory)))
