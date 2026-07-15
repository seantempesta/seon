(ns seon.dev.restore-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [seon.db.coordinate :as coordinate]
            [seon.db.protocol :as protocol]
            [seon.dev.restore :as restore]
            [seon.dev.restore-state :as restore-state]
            [seon.dev.state :as state]
            [seon.launch :as launch]))

(def database-id #uuid "10000000-0000-0000-0000-000000000001")
(def source-commit #uuid "20000000-0000-0000-0000-000000000001")
(def target-commit #uuid "30000000-0000-0000-0000-000000000001")
(def forced-commit #uuid "40000000-0000-0000-0000-000000000001")
(def latest-commit #uuid "50000000-0000-0000-0000-000000000001")
(def intent-id "q66ljwup2b5r")
(def prior-completion-id "priorundo001")
(def digest (apply str (repeat 64 "a")))
(def other-digest (apply str (repeat 64 "b")))

(def source
  {::coordinate/database-id database-id
   ::coordinate/branch :db
   ::coordinate/commit-id source-commit
   ::coordinate/t 100})

(def target
  {::coordinate/database-id database-id
   ::coordinate/branch :seon.branch/target
   ::coordinate/commit-id target-commit
   ::coordinate/t 80})

(def latest-source
  (assoc source
         ::coordinate/commit-id latest-commit
         ::coordinate/t 120))

(def prior-undo-branch
  (keyword "seon.restore.undo" (str "r-" prior-completion-id)))
(def prior-target-branch
  (keyword "seon.restore.target" (str "r-" prior-completion-id)))
(def prior-undo-coordinate
  (assoc source ::coordinate/branch prior-undo-branch))

(def undo-branch (keyword "seon.restore.undo" (str "r-" intent-id)))
(def prepared-target-branch
  (keyword "seon.restore.target" (str "r-" intent-id)))

(def writer-owner
  {::launch/writer-cluster "default"
   ::launch/writer-process-dir "/process/writer"
   ::launch/request-socket-path "/process/request.sock"
   ::launch/publish-socket-path "/process/publish.sock"
   ::launch/writer-repl-port-file "/process/writer.port"})

(def main-descriptor
  {::launch/runtime
   {::launch/runtime-cluster "default"
    ::launch/artifact-flavor :seon.dev.artifact.flavor/default
    ::launch/client-build-id "client-build"
    :seon.client/launch-capability {:seon.client/autonomous? true}}
   ::launch/database
   {::protocol/database-name "default"
    ::coordinate/attachment (coordinate/attachment source)
    ::coordinate/coordinate source
    ::protocol/backend :file
    ::protocol/database-path "/cluster/db"}
   ::launch/writer-owner writer-owner
   ::launch/process
   {::launch/process-dir "/process/main"
    ::launch/log-dir "/logs/main"
    ::launch/http-port 7890
    ::launch/http-port-file "/process/main/http.port"}
   ::launch/blob-storage-view
   {:my.blob/writable-dir "/main/blobs"
    :my.blob/read-only-dirs []}})

(def target-descriptor
  {::launch/runtime
   {::launch/runtime-cluster "target"
    ::launch/artifact-flavor :seon.dev.artifact.flavor/default
    ::launch/client-build-id "client-build"
    :seon.client/launch-capability {:seon.client/autonomous? false}}
   ::launch/database
   {::protocol/database-name "target"
    ::coordinate/attachment (coordinate/attachment target)
    ::coordinate/coordinate target
    ::protocol/backend :file
    ::protocol/database-path "/cluster/db"}
   ::launch/writer-owner writer-owner
   ::launch/process
   {::launch/process-dir "/process/target"
    ::launch/log-dir "/logs/target"
    ::launch/http-port 0
    ::launch/http-port-file "/process/target/http.port"}
   ::launch/blob-storage-view
   {:my.blob/writable-dir "/target/blobs"
    :my.blob/read-only-dirs ["/main/blobs"]}})

(defn- descriptor-at [descriptor point]
  (-> descriptor
      (assoc-in [::launch/database ::coordinate/attachment]
                (coordinate/attachment point))
      (assoc-in [::launch/database ::coordinate/coordinate] point)))

(def prior-completion
  {:seon.db.restore/id prior-completion-id
   :seon.db.restore/db-name :default
   :seon.db.restore/database-id database-id
   :seon.db.restore/from-branch :db
   :seon.db.restore/from-commit-id source-commit
   :seon.db.restore/from-t 100
   :seon.db.restore/to-branch :seon.branch/target
   :seon.db.restore/to-commit-id target-commit
   :seon.db.restore/to-t 80
   :seon.db.restore/forced-commit-id forced-commit
   :seon.db.restore/undo-branch prior-undo-branch
   :seon.db.restore/target-branch prior-target-branch})

(defn- retained-observation
  ([] (retained-observation [prior-completion]))
  ([completions]
   {::restore/main-coordinate latest-source
    ::restore/branch-heads
    {:db latest-source
     :seon.branch/target target
     prior-undo-branch prior-undo-coordinate
     prior-target-branch
     (assoc target ::coordinate/branch prior-target-branch)}
    ::restore/completion-facts completions}))

(defn- intent-request []
  {::restore/intent-id intent-id
   ::restore/operation :seon.dev.restore.operation/restore
   ::restore/pre-restore-main-descriptor main-descriptor
   ::restore/selected-target-descriptor target-descriptor
   ::restore/expected-branch-roster
   #{:db :seon.branch/target undo-branch prepared-target-branch}
   ::restore/protocol-version protocol/current-version
   ::restore/writer-artifact-digest digest
   ::restore/consumer-generations
   {:seon.dev.process/pod
    #uuid "60000000-0000-0000-0000-000000000001"}
   ::restore/core-overlay-selection :seon.dev.restore.overlay/preserve
   ::restore/config-overlay-selection :seon.dev.restore.overlay/preserve
   ::restore/reachable-hash-digest digest})

(defn- derived-intent []
  (restore/derive-intent (intent-request)))

(defn- undo-intent-request []
  (let [observation (retained-observation)]
    (-> (intent-request)
        (assoc ::restore/operation :seon.dev.restore.operation/undo
               ::restore/pre-restore-main-descriptor
               (descriptor-at main-descriptor latest-source)
               ::restore/selected-target-descriptor
               (descriptor-at target-descriptor prior-undo-coordinate)
               ::restore/expected-branch-roster
               (conj (set (keys (::restore/branch-heads observation)))
                     undo-branch prepared-target-branch)
               ::restore/retained-head-observation observation
               ::restore/completion-selector
               {::restore/selected-completion-id prior-completion-id}))))

(deftest startup-identity-is-the-exact-frozen-intent-projection
  (let [intent (derived-intent)
        identity (restore/startup-identity intent)]
    (is (= (select-keys intent
                        [::restore/intent-id
                         ::restore/plan-digest
                         ::restore/reachable-hash-digest
                         ::restore/consumer-generations])
           identity))
    (is (= (get-in intent [::restore/consumer-generations
                           :seon.dev.process/pod])
           (get-in identity [::restore/consumer-generations
                             :seon.dev.process/pod])))))

(defn- observation
  ([intent main heads ancestors completed]
   (observation intent main nil heads ancestors completed))
  ([_intent main forced-main heads _ancestors completed]
   (cond-> {::restore/main-coordinate main
            ::restore/forced-main-parent-commit-ids #{}
            ::restore/branch-heads
            (merge {:db main :seon.branch/target target} heads)
            ::restore/completed-intent-ids completed}
     forced-main (assoc ::restore/forced-main-coordinate forced-main
                        ::restore/forced-main-parent-commit-ids
                        #{target-commit}))))

(defn- command [intent observation]
  (::restore/command
   (restore/next-command {::restore/intent intent
                          ::restore/observation observation})))

(deftest immutable-intent-derives-reserved-branches-and-coordinates
  (let [intent (derived-intent)
        target-branch prepared-target-branch]
    (is (m/validate ::restore/intent intent))
    (is (= undo-branch (::restore/undo-branch intent)))
    (is (= target-branch (::restore/prepared-target-branch intent)))
    (is (= (assoc source ::coordinate/branch undo-branch)
           (::restore/undo-coordinate intent)))
    (is (= (assoc target ::coordinate/branch target-branch)
           (::restore/prepared-target-coordinate intent)))
    (is (= :seon.dev.restore.overlay/preserve
           (::restore/core-overlay-selection intent)))
    (is (= :seon.dev.restore.overlay/preserve
           (::restore/config-overlay-selection intent)))
    (is (= main-descriptor (::restore/pre-restore-main-descriptor intent)))
    (is (= target-descriptor (::restore/selected-target-descriptor intent)))
    (is (not-any? #(contains? intent %)
                  [:seon.dev.restore/phase :seon.dev.restore/status
                   :seon.dev.restore/retry-count]))))

(deftest undo-derives-the-same-transition-from-one-retained-completion
  (let [undo-intent (restore/derive-intent (undo-intent-request))]
    (is (= :seon.dev.restore.operation/undo
           (::restore/operation undo-intent)))
    (is (= (descriptor-at main-descriptor latest-source)
           (::restore/pre-restore-main-descriptor undo-intent)))
    (is (= (descriptor-at target-descriptor prior-undo-coordinate)
           (::restore/selected-target-descriptor undo-intent)))
    (is (= (assoc latest-source ::coordinate/branch undo-branch)
           (::restore/undo-coordinate undo-intent))
        "the inverse preserves the actual latest main as redo")
    (is (not-any? #(contains? undo-intent %)
                  [::restore/retained-head-observation
                   ::restore/completion-selector])
        "selection evidence compiles into the same immutable intent shape")
    (is (= :seon.dev.restore.command/create-undo
           (command undo-intent
                    (observation
                     undo-intent latest-source
                     (dissoc (::restore/branch-heads (retained-observation)) :db)
                     #{latest-commit} #{}))))))

(deftest undo-selection-fails-closed-before-intent-publication
  (testing "an operation keyword cannot relabel an arbitrary retained branch"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"exact retained completion"
         (restore/derive-intent
          (assoc (intent-request) ::restore/operation
                 :seon.dev.restore.operation/undo)))))
  (testing "id and retained-branch selectors each resolve exactly one fact"
    (let [request (undo-intent-request)
          by-branch
          (assoc request ::restore/completion-selector
                 {::restore/selected-undo-branch prior-undo-branch})]
      (is (= (restore/derive-intent request)
             (restore/derive-intent by-branch)))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"exactly one"
           (restore/derive-intent
            (assoc-in
             by-branch
             [::restore/retained-head-observation ::restore/completion-facts]
             [prior-completion
              (assoc prior-completion :seon.db.restore/id "otherundo001")]))))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"more than one completion"
           (restore/derive-intent
            (assoc-in
             request
             [::restore/retained-head-observation ::restore/completion-facts]
             [prior-completion
              (assoc prior-completion :seon.db.restore/id "otherundo001")]))))))
  (testing "the target must be the selected fact's exact retained undo head"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"exact retained head"
         (restore/derive-intent
          (assoc (undo-intent-request) ::restore/selected-target-descriptor
                 target-descriptor)))))
  (testing "completion and branch observations stay in one database lineage"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"completion lineage"
         (restore/derive-intent
          (assoc-in
           (undo-intent-request)
           [::restore/retained-head-observation ::restore/completion-facts 0
            :seon.db.restore/database-id]
           (random-uuid))))))
  (testing "the actual latest main is retained, including later ordinary work"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"actual latest observed main"
         (restore/derive-intent
          (assoc (undo-intent-request) ::restore/pre-restore-main-descriptor
                 main-descriptor)))))
  (testing "advanced retained heads and stale rosters are rejected"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"exact retained head"
         (restore/derive-intent
          (assoc-in
           (undo-intent-request)
           [::restore/retained-head-observation ::restore/branch-heads
            prior-undo-branch ::coordinate/commit-id]
           (random-uuid)))))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"stale or incomplete"
         (restore/derive-intent
          (update (undo-intent-request) ::restore/expected-branch-roster
                  disj prior-target-branch)))))
  (testing "new reserved names must still be absent at publication"
    (let [existing
          (assoc prior-undo-coordinate ::coordinate/branch undo-branch)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"already exist"
           (restore/derive-intent
            (assoc-in
             (undo-intent-request)
             [::restore/retained-head-observation ::restore/branch-heads
              undo-branch]
             existing))))))
  (testing "a completion target consumed by a later completion is not reusable"
    (let [later
          (assoc prior-completion
                 :seon.db.restore/id "laterundo001"
                 :seon.db.restore/from-commit-id latest-commit
                 :seon.db.restore/from-t 120
                 :seon.db.restore/to-branch prior-undo-branch
                 :seon.db.restore/to-commit-id source-commit
                 :seon.db.restore/to-t 100
                 :seon.db.restore/forced-commit-id (random-uuid)
                 :seon.db.restore/undo-branch :seon.restore.undo/r-laterundo001
                 :seon.db.restore/target-branch
                 :seon.restore.target/r-laterundo001)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"already been consumed"
           (restore/derive-intent
            (assoc-in
             (undo-intent-request)
             [::restore/retained-head-observation ::restore/completion-facts]
             [prior-completion later])))))))

(deftest intent-is-closed-and-rejects-crossed-or-mutable-data
  (is (thrown? Exception
               (restore/derive-intent
                (assoc (intent-request) :seon.dev.restore/phase :planned))))
  (is (thrown? Exception
               (restore/derive-intent
                (assoc (intent-request) ::restore/core-overlay-selection
                       :seon.dev.restore.overlay/current))))
  (is (thrown? Exception
               (restore/derive-intent
                (assoc-in (intent-request)
                          [::restore/selected-target-descriptor
                           ::launch/blob-storage-view]
                          (::launch/blob-storage-view main-descriptor)))))
  (is (thrown? Exception
               (restore/derive-intent
                (assoc-in (intent-request)
                          [::restore/selected-target-descriptor
                           ::launch/database ::coordinate/coordinate
                           ::coordinate/database-id]
                          (random-uuid)))))
  (is (thrown? Exception
               (restore/derive-intent
                (assoc (intent-request) ::restore/selected-target-descriptor
                       main-descriptor))))
  (is (thrown? Exception
               (restore/derive-intent
                (assoc (intent-request) ::restore/consumer-generations {}))))
  (is (thrown? Exception
               (restore/derive-intent
                (assoc (intent-request) ::restore/consumer-generations
                       {:seon.dev.process/writer (random-uuid)}))))
  (let [intent (derived-intent)]
    (is (thrown? Exception
                 (restore/validate-intent
                  (assoc intent ::restore/undo-branch :crossed))))))

(deftest plan-digest-commits-every-frozen-field
  (let [intent (derived-intent)
        mutations
        {"backend path"
         (fn [request]
           (-> request
               (assoc-in [::restore/pre-restore-main-descriptor
                          ::launch/database ::protocol/database-path]
                         "/other/db")
               (assoc-in [::restore/selected-target-descriptor
                          ::launch/database ::protocol/database-path]
                         "/other/db")))
         "branch roster"
         #(update % ::restore/expected-branch-roster conj :seon.branch/other)
         "writer artifact"
         #(assoc % ::restore/writer-artifact-digest other-digest)
         "blob overlay order"
         #(update-in % [::restore/selected-target-descriptor
                        ::launch/blob-storage-view :my.blob/read-only-dirs]
                     conj "/older/blobs")
         "consumer generation"
         #(assoc-in % [::restore/consumer-generations :seon.dev.process/pod]
                    (random-uuid))}]
    (doseq [[label mutate] mutations]
      (let [changed (restore/derive-intent (mutate (intent-request)))]
        (is (not= (::restore/plan-digest intent)
                  (::restore/plan-digest changed)) label)
        (is (thrown? Exception
                     (restore/validate-intent
                      (assoc changed ::restore/plan-digest
                             (::restore/plan-digest intent))))
            label)))))

(deftest publication-is-durable-idempotent-and-conflict-closed
  (let [directory (fs/create-temp-dir {:prefix "seon-restore-intent-"})
        intent (derived-intent)
        request {::restore-state/cluster-dir (str directory)
                 ::restore/intent intent}]
    (try
      (is (true? (::restore-state/published?
                  (restore-state/publish-intent! request))))
      (is (= intent (restore-state/read-intent! (str directory))))
      (is (false? (::restore-state/published?
                   (restore-state/publish-intent! request))))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"already retained"
           (restore-state/publish-intent!
            (assoc request ::restore/intent
                   (restore/derive-intent
                    (assoc (intent-request) ::restore/writer-artifact-digest
                           other-digest))))))
      (is (= intent
             (state/read-edn (restore/intent-path (str directory)))))
      (finally (fs/delete-tree directory {:force true})))))

(deftest admin-invocation-owns-one-bounded-atomic-result-destination
  (let [intent (derived-intent)
        invocation
        (restore/derive-admin-invocation
         {::restore/cluster-dir "/cluster"
          ::restore/intent intent
          ::restore/admin-timeout-ms 30000})]
    (is (= :seon.dev.restore.admin.transport/atomic-edn-file
           (::restore/admin-result-transport invocation)))
    (is (= "/cluster/lifecycle/restore.edn"
           (::restore/intent-path invocation)))
    (is (= "/cluster/lifecycle/restore-admin-q66ljwup2b5r.edn"
           (::restore/admin-result-path invocation)))
    (is (= 30000 (::restore/admin-timeout-ms invocation)))))

(deftest retry-command-is-derived-from-current-facts-without-a-phase
  (let [intent (derived-intent)
        undo-branch (::restore/undo-branch intent)
        target-branch (::restore/prepared-target-branch intent)
        undo (::restore/undo-coordinate intent)
        prepared-target (::restore/prepared-target-coordinate intent)
        source-ancestors #{source-commit}
        promoted (assoc source ::coordinate/commit-id forced-commit
                        ::coordinate/t 101)
        promoted-ancestors #{source-commit target-commit forced-commit}]
    (testing "preparation advances only from exact observed branch heads"
      (is (= :seon.dev.restore.command/create-undo
             (command intent
                      (observation intent source {} source-ancestors #{}))))
      (is (= :seon.dev.restore.command/create-target
             (command intent
                      (observation intent source {undo-branch undo}
                                   source-ancestors #{}))))
      (is (= :seon.dev.restore.command/prepare-exclusive-transition
             (command intent
                      (observation intent source
                                   {undo-branch undo
                                    target-branch prepared-target}
                                   source-ancestors #{})))))
    (testing "promotion and completion select reconstruction, never force again"
      (is (= :seon.dev.restore.command/reconstruct-and-complete
             (command intent
                      (observation intent promoted promoted
                                   {undo-branch undo
                                    target-branch prepared-target}
                                   promoted-ancestors #{}))))
      (is (= :seon.dev.restore.command/prove-readiness
             (command intent
                      (observation intent promoted promoted
                                   {undo-branch undo
                                    target-branch prepared-target}
                                   promoted-ancestors #{intent-id})))))
    (testing "target ancestry cannot masquerade as an observed forced main"
      (is (= :seon.dev.restore.command/create-undo
             (command intent
                      (observation intent source {} #{source-commit target-commit}
                                   #{}))))
      (is (= :seon.dev.restore.command/diagnose-divergence
             (command intent
                      (observation intent promoted
                                   {undo-branch undo
                                    target-branch prepared-target}
                                   promoted-ancestors #{})))))
    (testing "every crossed or unexplained durable state diagnoses divergence"
      (let [divergent-commit (random-uuid)]
        (is (= :seon.dev.restore.command/diagnose-divergence
               (command intent
                        (observation intent
                                     (assoc source ::coordinate/commit-id
                                            divergent-commit)
                                     {} #{divergent-commit} #{})))))
      (is (= :seon.dev.restore.command/diagnose-divergence
             (command intent
                      (observation intent source
                                   {undo-branch
                                    (assoc undo ::coordinate/commit-id
                                           (random-uuid))}
                                   source-ancestors #{}))))
      (is (= :seon.dev.restore.command/diagnose-divergence
             (command intent
                      (observation intent source {} source-ancestors
                                   #{intent-id}))))
      (is (= :seon.dev.restore.command/diagnose-divergence
             (command intent
                      (observation intent promoted promoted
                                   {undo-branch undo}
                                   promoted-ancestors #{})))))))

(deftest observations-must-be-exact-self-consistent-facts
  (let [intent (derived-intent)
        wrong-database (random-uuid)]
    (is (thrown? Exception
                 (restore/next-command
                  {::restore/intent intent
                   ::restore/observation
                   (observation intent
                                (assoc source ::coordinate/database-id
                                       wrong-database)
                                {} #{source-commit} #{})})))
    (is (thrown? Exception
                 (restore/next-command
                  {::restore/intent intent
                   ::restore/observation
                   (observation intent source
                                {:wrong (assoc source ::coordinate/branch :db)}
                                #{source-commit} #{})})))
    (is (thrown? Exception
                 (restore/next-command
                  {::restore/intent intent
                   ::restore/observation
                   (assoc (observation intent source {} #{} #{})
                          ::restore/forced-main-parent-commit-ids
                          #{target-commit})})))
    (is (thrown? Exception
                 (restore/next-command
                  {::restore/intent intent
                   ::restore/observation
                   (observation intent source
                                (assoc source ::coordinate/commit-id forced-commit
                                       ::coordinate/t 101)
                                {} #{source-commit} #{})})))))
