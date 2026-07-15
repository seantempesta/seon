(ns seon.dev.restore-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [seon.db.coordinate :as coordinate]
            [seon.db.protocol :as protocol]
            [seon.db.restore :as db.restore]
            [seon.db.restore-admin :as restore-admin]
            [seon.dev.artifact :as artifact]
            [seon.dev.branch :as branch]
            [seon.dev.config :as config]
            [seon.dev.process :as process]
            [seon.dev.restore :as restore]
            [seon.dev.restore-state :as restore-state]
            [seon.dev.state :as state]
            [seon.launch :as launch]))

(def database-id #uuid "10000000-0000-0000-0000-000000000001")
(def source-commit #uuid "20000000-0000-0000-0000-000000000001")
(def target-commit #uuid "30000000-0000-0000-0000-000000000001")
(def forced-commit #uuid "40000000-0000-0000-0000-000000000001")
(def latest-commit #uuid "50000000-0000-0000-0000-000000000001")
(def completion-commit #uuid "60000000-0000-0000-0000-000000000001")
(def intent-id #uuid "70000000-0000-4000-8000-000000000001")
(def completion-id "restore00001")
(def prior-completion-id "priorundo001")
(def digest (apply str (repeat 64 "a")))
(def other-digest (apply str (repeat 64 "b")))

(def artifact-identity
  {:seon.dev.artifact/application-digest digest
   :seon.dev.artifact/client-digest digest
   :seon.dev.artifact/bootstrap-digest digest
   :seon.dev.artifact/css-digest digest
   :seon.dev.artifact/writer-digest digest})

(defrecord UnsupportedRecord [value])

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

(defn- undo-lifecycle
  ([] (undo-lifecycle [prior-completion]))
  ([completions]
   {::protocol/main-coordinate latest-source
    ::protocol/main-parent-commit-ids #{latest-commit}
    ::protocol/branch-roster
    #{:db :seon.branch/target prior-undo-branch prior-target-branch}
    ::protocol/branch-coordinates
    (::restore/branch-heads (retained-observation completions))
    ::protocol/restore-completions completions
    ::protocol/completed-restore-ids
    (set (map :seon.db.restore/id completions))
    ::protocol/restore-completion-coordinates
    (into {} (map (fn [completion]
                    [(:seon.db.restore/id completion) latest-source]))
          completions)}))

(defn- intent-request []
  {::restore/intent-id intent-id
   ::restore/operation :seon.dev.restore.operation/restore
   ::restore/pre-restore-main-descriptor main-descriptor
   ::restore/selected-target-descriptor target-descriptor
   ::restore/expected-branch-roster
   #{:db :seon.branch/target undo-branch prepared-target-branch}
   ::restore/protocol-version protocol/current-version
   ::restore/artifact-identity artifact-identity
   ::restore/consumer-generations
   {:seon.dev.process/pod
    #uuid "60000000-0000-0000-0000-000000000001"}
   ::restore/core-overlay-selection :seon.dev.restore.overlay/preserve
   ::restore/config-overlay-selection :seon.dev.restore.overlay/preserve
   ::restore/reachable-hash-digest digest})

(defn- derived-intent []
  (restore/derive-intent (intent-request)))

(deftest new-intents-are-uuids-while-exact-legacy-intents-remain-readable
  (let [legacy-id "abcdef12345678"
        legacy
        (#'restore/derive-intent-value
         (assoc (intent-request)
                ::restore/intent-id legacy-id
                ::restore/expected-branch-roster
                (restore/reserved-branch-roster
                 legacy-id #{:db :seon.branch/target})))]
    (is (uuid? (::restore/intent-id (derived-intent))))
    (is (= legacy (restore/validate-intent legacy)))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"intent request is invalid"
         (restore/derive-intent
          (assoc (intent-request) ::restore/intent-id legacy-id))))
    (is (false? (m/validate ::restore/intent-id "zzzzzzzzzzzzzz")))
    (is (false? (m/validate ::restore/intent-id "restore00001")))
    (is (false? (m/validate ::restore/digest (str digest "a"))))))

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

(defn- completion-fact [intent forced-commit-id]
  (let [from (get-in intent [::restore/pre-restore-main-descriptor
                             ::launch/database ::coordinate/coordinate])
        to (get-in intent [::restore/selected-target-descriptor
                           ::launch/database ::coordinate/coordinate])]
    {:seon.db.restore/id completion-id
     :seon.db.restore/plan-digest (::restore/plan-digest intent)
     :seon.db.restore/db-name
     (keyword
      (get-in intent [::restore/pre-restore-main-descriptor
                      ::launch/database ::protocol/database-name]))
     :seon.db.restore/database-id (::coordinate/database-id from)
     :seon.db.restore/from-branch (::coordinate/branch from)
     :seon.db.restore/from-commit-id (::coordinate/commit-id from)
     :seon.db.restore/from-t (::coordinate/t from)
     :seon.db.restore/to-branch (::coordinate/branch to)
     :seon.db.restore/to-commit-id (::coordinate/commit-id to)
     :seon.db.restore/to-t (::coordinate/t to)
     :seon.db.restore/forced-commit-id forced-commit-id
     :seon.db.restore/undo-branch (::restore/undo-branch intent)
     :seon.db.restore/target-branch (::restore/prepared-target-branch intent)}))

(defn- observation
  [_intent main heads parents completions completion-index]
  {::restore/main-coordinate main
   ::restore/main-parent-commit-ids parents
   ::restore/branch-heads
   (merge {:db main :seon.branch/target target} heads)
   ::restore/completed-restore-ids
   (set (map :seon.db.restore/id completions))
   ::restore/completion-facts completions
   ::restore/completion-coordinates
   (into {}
         (map (fn [[completion-id value]]
                [completion-id
                 (if (int? value)
                   (assoc main ::coordinate/t value)
                   value)]))
         completion-index)})

(defn- command [intent observation]
  (::restore/command
   (restore/next-command {::restore/intent intent
                          ::restore/observation observation})))

(deftest immutable-intent-derives-reserved-branches-and-coordinates
  (let [intent (derived-intent)
        target-branch prepared-target-branch]
    (is (= #{:db :seon.branch/target undo-branch prepared-target-branch}
           (restore/reserved-branch-roster
            intent-id #{:db :seon.branch/target})))
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
    (is (= [:vector :seon.db.restore/completion]
           (m/form (m/deref ::restore/completion-facts)))
        "the planner references the canonical completion schema")
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
                     #{latest-commit} [] {}))))))

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

(deftest canonical-tree-supports-only-the-versioned-intent-domain
  (let [canonical #'restore/canonical-tree]
    (is (= [:nil] (canonical nil)))
    (is (= [:boolean "false"] (canonical false)))
    (is (= [:boolean "true"] (canonical true)))
    (is (= [:string "text"] (canonical "text")))
    (is (= [:keyword "" "plain"] (canonical :plain)))
    (is (= [:keyword "qualified" "name"]
           (canonical :qualified/name)))
    (is (= [:uuid "10000000-0000-0000-0000-000000000001"]
           (canonical database-id)))
    (is (= [:integer "42"] (canonical 42)))
    (is (= [:vector [[:integer "1"] [:keyword "" "a"]]]
           (canonical [1 :a])))
    (is (= [:set [[:keyword "" "a"] [:keyword "" "b"]]]
           (canonical #{:b :a})))
    (is (= [:map [[[:keyword "" "a"] [:integer "1"]]
                  [[:keyword "" "b"] [:integer "2"]]]]
           (canonical {:b 2 :a 1})))
    (doseq [unsupported
            ['symbol '(1 2) 1/2 1.5 (java.util.Date.)
             (->UnsupportedRecord :value)
             (tagged-literal 'unsupported/value "value")]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"unsupported canonical value"
           (canonical unsupported))
          (str "unsupported canonical leaf " (class unsupported))))))

(deftest canonical-plan-bytes-are-domain-separated-and-order-independent
  (let [intent (derived-intent)
        payload (dissoc intent ::restore/plan-digest)
        reordered
        (-> (into (array-map) (reverse (seq payload)))
            (update ::restore/expected-branch-roster
                    #(into (sorted-set-by
                            (fn [left right]
                              (compare (str right) (str left))))
                           %)))
        bytes (restore/canonical-intent-bytes payload)]
    (is (bytes? bytes))
    (is (= (seq bytes)
           (seq (restore/canonical-intent-bytes reordered))))
    (is (= (restore/plan-digest payload)
           (restore/plan-digest reordered)))
    (is (.startsWith (String. bytes java.nio.charset.StandardCharsets/UTF_8)
                     "[:seon.dev.restore.canonical/v1 "))
    (is (not (.contains (String. bytes java.nio.charset.StandardCharsets/UTF_8)
                        "RESTORE ")))
    (is (thrown? Exception
                 (restore/canonical-intent-bytes intent)))
    (let [ordered
          (update-in payload
                     [::restore/selected-target-descriptor
                      ::launch/blob-storage-view :my.blob/read-only-dirs]
                     conj "/older/blobs")]
      (is (not= (restore/plan-digest ordered)
                (restore/plan-digest
                 (update-in ordered
                            [::restore/selected-target-descriptor
                             ::launch/blob-storage-view
                             :my.blob/read-only-dirs]
                            #(vec (reverse %)))))))))

(deftest confirmation-is-an-exact-relational-projection-of-one-intent
  (let [intent (derived-intent)
        apply-text
        (restore/confirmation-text
         {::restore/intent intent
          ::restore/confirmation-action
          :seon.dev.restore.confirmation/apply})
        abort-text
        (restore/confirmation-text
         {::restore/intent intent
          ::restore/confirmation-action
          :seon.dev.restore.confirmation/abort})
        expected-apply
        (str "RESTORE default DATABASE " database-id
             " FROM :db/" source-commit "@100"
             " TO :seon.branch/target/" target-commit "@80"
             " INTENT " intent-id
             " PLAN " (::restore/plan-digest intent))
        expected-abort
        (str "ABORT RESTORE default DATABASE " database-id
             " TARGET :seon.branch/target/" target-commit "@80"
             " INTENT " intent-id
             " PLAN " (::restore/plan-digest intent))
        plan {::restore/intent intent
              ::restore/confirmation-text apply-text}]
    (is (= expected-apply apply-text))
    (is (= expected-abort abort-text))
    (is (= plan (restore/validate-plan plan)))
    (doseq [wrong [(subs apply-text 0 (- (count apply-text) 8))
                   (.toLowerCase apply-text)
                   (str apply-text "\n")
                   (.replace apply-text " DATABASE " "  DATABASE ")
                   (.replace apply-text ":seon.branch/target"
                             ":seon.branch/other")
                   abort-text]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"confirmation text does not match"
           (restore/validate-plan
            (assoc plan ::restore/confirmation-text wrong)))))
    (is (thrown? Exception
                 (restore/validate-plan (dissoc plan ::restore/confirmation-text))))
    (is (thrown? Exception
                 (restore/validate-plan (assoc plan :extra/value true))))
    (is (thrown? Exception
                 (restore/confirmation-text
                  {::restore/intent intent
                   ::restore/confirmation-action
                   :seon.dev.restore.confirmation/apply
                   :extra/value true})))
    (is (thrown? Exception
                 (restore/validate-intent
                  (assoc intent ::restore/confirmation-text apply-text))))))

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
         #(assoc-in % [::restore/artifact-identity
                       :seon.dev.artifact/writer-digest]
                    other-digest)
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

(deftest artifact-identity-freezes-every-runtime-build-output
  (let [intent (derived-intent)]
    (is (= digest
           (restore/writer-artifact-digest
            (::restore/artifact-identity intent))))
    (doseq [artifact-key
            [:seon.dev.artifact/application-digest
             :seon.dev.artifact/client-digest
             :seon.dev.artifact/bootstrap-digest
             :seon.dev.artifact/css-digest
             :seon.dev.artifact/writer-digest]]
      (let [changed
            (restore/derive-intent
             (assoc-in (intent-request)
                       [::restore/artifact-identity artifact-key]
                       other-digest))]
        (is (not= (::restore/plan-digest intent)
                  (::restore/plan-digest changed))
            (str artifact-key))))
    (is (thrown? Exception
                 (restore/derive-intent
                  (update (intent-request) ::restore/artifact-identity
                          dissoc :seon.dev.artifact/css-digest))))
    (is (thrown? Exception
                 (restore/derive-intent
                  (assoc-in (intent-request)
                            [::restore/artifact-identity :extra/digest]
                            digest))))))

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
                    (assoc-in (intent-request)
                              [::restore/artifact-identity
                               :seon.dev.artifact/writer-digest]
                              other-digest))))))
      (is (= intent
             (state/read-edn (restore/intent-path (str directory)))))
      (finally (fs/delete-tree directory {:force true})))))

(deftest convergence-rereads-facts-and-retains-no-phase
  (let [intent (derived-intent)
        promoted (assoc source ::coordinate/commit-id forced-commit
                        ::coordinate/t 101)
        completed (assoc source ::coordinate/commit-id completion-commit
                         ::coordinate/t 102)
        completion (completion-fact intent forced-commit)
        observations
        (atom [(observation intent source {} #{} [] {})
               (observation intent source
                            {undo-branch (::restore/undo-coordinate intent)}
                            #{} [] {})
               (observation intent source
                            {undo-branch (::restore/undo-coordinate intent)
                             prepared-target-branch
                             (::restore/prepared-target-coordinate intent)}
                            #{} [] {})
               (observation intent promoted
                            {undo-branch (::restore/undo-coordinate intent)
                             prepared-target-branch
                             (::restore/prepared-target-coordinate intent)}
                            #{target-commit} [] {})
               (observation intent completed
                            {undo-branch (::restore/undo-coordinate intent)
                             prepared-target-branch
                             (::restore/prepared-target-coordinate intent)}
                            #{forced-commit} [completion]
                            {completion-id 102})])
        effects (atom [])
        result
        (restore-state/converge!
         {::restore/intent intent
          ::restore-state/observe!
          (fn [_]
            (let [current (first @observations)]
              (swap! observations subvec 1)
              current))
          ::restore-state/effect!
          (fn [effect] (swap! effects conj (::restore/command effect)))})]
    (is (= [:seon.dev.restore.command/create-undo
            :seon.dev.restore.command/create-target
            :seon.dev.restore.command/prepare-exclusive-transition
            :seon.dev.restore.command/reconstruct-and-complete
            :seon.dev.restore.command/prove-readiness]
           @effects))
    (is (= @effects
           (mapv ::restore/command (::restore-state/transitions result))))
    (is (empty? @observations))
    (is (not-any? #(contains? result %)
                  [:seon.dev.restore/phase :seon.dev.restore/retry-count]))))

(deftest convergence-fails-before-an-effect-on-divergence
  (let [intent (derived-intent)
        effects (atom [])
        completed (assoc source ::coordinate/commit-id completion-commit
                         ::coordinate/t 102)
        completion (assoc (completion-fact intent forced-commit)
                          :seon.db.restore/to-commit-id (random-uuid))]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"diverged"
         (restore-state/converge!
          {::restore/intent intent
           ::restore-state/observe!
           (fn [_]
             (observation
              intent completed
              {undo-branch (::restore/undo-coordinate intent)
               prepared-target-branch
               (::restore/prepared-target-coordinate intent)}
              #{forced-commit} [completion] {completion-id 102}))
           ::restore-state/effect!
           (fn [effect] (swap! effects conj effect))})))
    (is (empty? @effects))))

(deftest production-coordinator-orders-effects-around-the-exclusive-cut
  (let [configuration (config/load! (System/getProperty "user.dir"))
        intent (derived-intent)
        calls (atom [])
        admin-result {::restore-admin/outcome
                      :seon.db.restore-admin.outcome/applied}
        blob-result {:my.blob/ok? true}
        manifest artifact-identity
        commands [:seon.dev.restore.command/create-undo
                  :seon.dev.restore.command/create-target
                  :seon.dev.restore.command/prepare-exclusive-transition
                  :seon.dev.restore.command/reconstruct-and-complete
                  :seon.dev.restore.command/prove-readiness]]
    (with-redefs-fn
      {#'restore-state/require-manifest! (fn [_] manifest)
       #'restore-state/retained-intent (fn [_] intent)
       #'restore-state/require-selected-branch! (fn [_ _ value] value)
       #'artifact/current-writer-digest (fn [_] digest)
       #'artifact/current-output-digests (fn [_] artifact-identity)
       #'restore-state/admin-invocation
       (fn [& _] {::restore/admin-result-path "/admin.edn"})
       #'restore-state/read-admin-result (constantly nil)
       #'restore-state/read-materialization-result (constantly nil)
       #'restore-state/prepare-observation-writer!
       (fn [& _] (swap! calls conj :prepare-observation-writer))
       #'restore-state/create-reserved-branch!
       (fn [_ _ role] (swap! calls conj [:create role]))
       #'restore-state/require-next-command!
       (fn [_ _ command] (swap! calls conj [:require command]))
       #'restore-state/materialize-retained-blobs!
       (fn [& _]
         (swap! calls conj :materialize)
         blob-result)
       #'restore-state/stop-retained-pods!
       (fn [_] (swap! calls conj :stop-retained))
       #'restore-state/stop-main-consumers!
       (fn [_ _] (swap! calls conj :stop-main))
       #'restore-state/invoke-admin!
       (fn [& _]
         (swap! calls conj :admin)
         admin-result)
       #'restore-state/start-writer-after-admin!
       (fn [& _] (swap! calls conj :start-writer))
       #'restore-state/start-restore-pod!
       (fn [& _] (swap! calls conj :start-restore-pod))
       #'restore-state/prove-restore-pod!
       (fn [& _]
         (swap! calls conj :prove-restore-pod)
         :seon.dev.restore.runtime/restore)
       #'state/delete-edn!
       (fn [path] (swap! calls conj [:delete path]))
       #'restore-state/ensure-processes!
       (fn [_ _ ids] (swap! calls conj [:ensure ids]))
       #'restore-state/converge!
       (fn [{effect ::restore-state/effect!}]
         (doseq [command commands]
           (effect {::restore/intent intent
                    ::restore/observation
                    (observation intent source {} #{} [] {})
                    ::restore/command command}))
         {::restore/intent intent
          ::restore/observation
          (observation intent source {} #{} [] {})
          ::restore-state/transitions []})}
      (fn []
        (is (= :seon.db.restore-admin.outcome/applied
               (::restore-state/admin-outcome
                (restore-state/resume!
                 {::restore-state/configuration configuration
                  ::restore-state/branch-name "target"}))))))
    (is (= [:prepare-observation-writer
            :materialize
            :stop-retained
            :stop-main
            [:ensure [process/writer-id]]
            [:require :seon.dev.restore.command/create-undo]
            [:create :undo]
            [:create :target]
            :stop-retained
            :stop-main
            :admin
            :start-writer
            :start-restore-pod
            :prove-restore-pod
            [:require :seon.dev.restore.command/prove-readiness]
            :stop-main
            [:delete (restore/intent-path
                      (:seon.dev.config/cluster-dir configuration))]
            [:delete "/admin.edn"]
            [:delete (str (:seon.dev.config/cluster-dir configuration)
                          "/lifecycle/restore-blobs-" intent-id ".edn")]
            [:ensure [process/watcher-id process/writer-id process/pod-id]]]
           @calls))))

(deftest stale-managed-records-drain-before-restore-replacement
  (let [configuration (config/load! (System/getProperty "user.dir"))
        calls (atom [])
        specs {process/writer-id {:seon.dev.process/id process/writer-id}
               process/pod-id {:seon.dev.process/id process/pod-id}}]
    (with-redefs-fn
      {#'process/specs (fn [& _] specs)
       #'process/read-process
       (fn [_ id] {:seon.dev.process/id id})
       #'process/converged? (fn [& _] false)
       #'process/clean-or-force!
       (fn [request]
         (swap! calls conj [:clean (:seon.dev.process/targets request)]))
       #'process/with-startup-ownership
       (fn [_ transition] (transition (fn [_ spawn!] (spawn!))))
       #'process/ensure!
       (fn [_ spec _]
         (swap! calls conj [:ensure (:seon.dev.process/id spec)]))}
      (fn []
        (#'restore-state/ensure-processes!
         configuration {} [process/writer-id process/pod-id])))
    (is (= [[:clean #{process/writer-id process/pod-id}]
            [:ensure process/writer-id]
            [:ensure process/pod-id]]
           @calls))))

(deftest retained-pod-absence-does-not-trust-closed-desired-state
  (let [configuration (config/load! (System/getProperty "user.dir"))
        closed-descriptor target-descriptor
        calls (atom [])]
    (with-redefs-fn
      {#'branch/inventory
       (fn [_]
         [{::branch/desired-state :seon.dev.branch.state/open
           ::branch/lifecycle-path "/open.edn"}
          {::branch/desired-state :seon.dev.branch.state/closed
           ::branch/lifecycle-path "/closed.edn"
           ::branch/launch-descriptor closed-descriptor}])
       #'branch/stop!
       (fn [request] (swap! calls conj [:branch-stop request]))
       #'config/select-launch-descriptor
       (fn [_ descriptor]
         (swap! calls conj [:select descriptor])
         :closed-config)
       #'process/clean-or-force!
       (fn [request] (swap! calls conj [:process-stop request]))}
      #(#'restore-state/stop-retained-pods! configuration))
    (is (= [[:branch-stop
             {::branch/configuration configuration
              ::branch/lifecycle-path "/open.edn"}]
            [:select closed-descriptor]
            [:process-stop
             {:seon.dev.process/configuration :closed-config
              :seon.dev.process/operation
              :seon.dev.process.operation/restore
              :seon.dev.process/targets #{process/pod-id}}]]
           @calls))))

(deftest lifecycle-adapter-carries-completion-head-evidence
  (let [intent (derived-intent)
        completion (completion-fact intent forced-commit)
        current (assoc source
                       ::coordinate/commit-id completion-commit
                       ::coordinate/t 102)
        lifecycle
        {::protocol/main-coordinate current
         ::protocol/main-parent-commit-ids #{forced-commit}
         ::protocol/branch-coordinates
         {:db current
          :seon.branch/target target
          undo-branch (::restore/undo-coordinate intent)
          prepared-target-branch (::restore/prepared-target-coordinate intent)}
         ::protocol/completed-restore-ids #{completion-id}
         ::protocol/restore-completions [completion]
         ::protocol/restore-completion-coordinates {completion-id current}}
        observation (#'restore-state/lifecycle->observation intent lifecycle)]
    (is (m/validate ::restore/observation observation))
    (is (= #{forced-commit} (::restore/main-parent-commit-ids observation)))
    (is (= {completion-id current}
           (::restore/completion-coordinates observation)))))

(deftest completion-admission-requires-the-exact-commit-coordinate
  (let [intent (derived-intent)
        completion (completion-fact intent forced-commit)
        current (assoc source
                       ::coordinate/commit-id completion-commit
                       ::coordinate/t 102)
        other-force-at-same-t
        (assoc current ::coordinate/commit-id (random-uuid))
        observed
        (observation
         intent current
         {undo-branch (::restore/undo-coordinate intent)
          prepared-target-branch (::restore/prepared-target-coordinate intent)}
         #{forced-commit} [completion]
         {completion-id other-force-at-same-t})]
    (is (= :seon.dev.restore.command/diagnose-divergence
           (command intent observed)))))

(deftest completion-admission-joins-one-generated-id-by-plan-digest
  (let [intent (derived-intent)
        completion (completion-fact intent forced-commit)
        duplicate-id "restore00002"
        duplicate (assoc completion :seon.db.restore/id duplicate-id)
        current (assoc source
                       ::coordinate/commit-id completion-commit
                       ::coordinate/t 102)
        heads {undo-branch (::restore/undo-coordinate intent)
               prepared-target-branch
               (::restore/prepared-target-coordinate intent)}]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"plan digest resolves more than one completion"
         (restore/next-command
          {::restore/intent intent
           ::restore/observation
           (observation intent current heads #{forced-commit}
                        [completion duplicate]
                        {completion-id current duplicate-id current})})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (restore/next-command
                  {::restore/intent intent
                   ::restore/observation
                   (observation intent current heads #{forced-commit}
                                [completion] {intent-id current})})))))

(deftest restore-pod-proof-reads-the-exact-closed-readiness-body
  (let [configuration (config/load! (System/getProperty "user.dir"))
        intent (derived-intent)
        completion (completion-fact intent forced-commit)
        current (assoc source
                       ::coordinate/commit-id completion-commit
                       ::coordinate/t 102)
        observation
        (observation
         intent current
         {undo-branch (::restore/undo-coordinate intent)
          prepared-target-branch (::restore/prepared-target-coordinate intent)}
         #{forced-commit} [completion] {completion-id current})
        expected {::db.restore/ready? true
                  ::db.restore/executable? false
                  ::db.restore/completion completion
                  ::db.restore/completion-coordinate current}
        response (atom expected)
        calls (atom [])]
    (is (m/validate ::db.restore/readiness-response expected))
    (is (= {::db.restore/completion completion
            ::db.restore/completion-coordinate current}
           (restore/completion-evidence
            {::restore/intent intent ::restore/observation observation})))
    (with-redefs-fn
      {#'restore-state/restore-startup-descriptor (fn [& _] target-descriptor)
       #'restore-state/selected-configuration (fn [value _] value)
       #'process/specs
       (fn [& _]
         {process/pod-id
          {:seon.dev.process/id process/pod-id
           :seon.dev.process/http-port-file "/restore.port"}})
       #'process/read-process (fn [& _] {:seon.dev.process/id process/pod-id})
       #'process/converged? (fn [& _] true)
       #'fs/regular-file? (fn [path] (= "/restore.port" (str path)))
       #'clojure.core/slurp (fn [_] "17890")
       #'restore-state/get-edn!
       (fn [url response-schema]
         (swap! calls conj [url response-schema])
         @response)}
      (fn []
        (is (= expected
               (#'restore-state/prove-restore-pod!
                configuration artifact-identity intent {} {} observation)))
        (is (= [["http://127.0.0.1:17890/_seon/ready"
                 ::db.restore/readiness-response]]
               @calls))
        (reset! response
                (assoc expected ::db.restore/completion-coordinate
                       (assoc current ::coordinate/commit-id (random-uuid))))
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"does not prove exact completion"
             (#'restore-state/prove-restore-pod!
              configuration artifact-identity intent {} {} observation)))))))

(deftest plan-is-read-only-and-returns-one-exact-confirmation
  (let [configuration (config/load! (System/getProperty "user.dir"))
        intent (derived-intent)
        calls (atom [])
        plan
        (with-redefs-fn
          {#'restore-state/retained-intent (constantly nil)
           #'restore-state/require-manifest! (constantly artifact-identity)
           #'artifact/current-output-digests (constantly artifact-identity)
           #'restore-state/derive-intent!
           (fn [& _]
             (swap! calls conj :derive)
             intent)
           #'restore-state/publish-intent!
           (fn [& _]
             (throw (ex-info "plan attempted publication" {})))}
          #(restore-state/plan!
            {::restore-state/configuration configuration
             ::restore-state/branch-name "target"}))]
    (is (= [:derive] @calls))
    (is (= intent (::restore/intent plan)))
    (is (= (restore/confirmation-text
            {::restore/intent intent
             ::restore/confirmation-action
             :seon.dev.restore.confirmation/apply})
           (::restore/confirmation-text plan)))))

(deftest undo-plan-is-pure-and-derives-the-recorded-head-from-one-completion
  (let [configuration (config/load! (System/getProperty "user.dir"))
        calls (atom [])
        plan
        (with-redefs-fn
          {#'restore-state/retained-intent (constantly nil)
           #'restore-state/require-manifest! (constantly artifact-identity)
           #'artifact/current-output-digests (constantly artifact-identity)
           #'restore-state/observe-lifecycle!
           (fn [_]
             (swap! calls conj :observe-lifecycle)
             (undo-lifecycle))
           #'restore-state/main-blob-url
           (fn [& _] "http://127.0.0.1:7890/_seon/operator/blobs")
           #'restore-state/observe-retained-blobs!
           (fn [url point]
             (swap! calls conj [:observe-blobs url point])
             {:my.blob/ok? true
              :my.blob/target-coordinate point
              :my.blob/reachable-hash-digest digest
              :my.blob/hash-count 0})
           #'restore-state/publish-intent!
           (fn [& _]
             (throw (ex-info "undo plan attempted publication" {})))}
          #(restore-state/plan-undo!
            {::restore-state/configuration configuration
             ::restore-state/completion-id prior-completion-id}))
        intent (::restore/intent plan)
        selected
        (get-in intent [::restore/selected-target-descriptor
                        ::launch/database ::coordinate/coordinate])]
    (is (= :seon.dev.restore.operation/undo (::restore/operation intent)))
    (is (= latest-source
           (get-in intent [::restore/pre-restore-main-descriptor
                           ::launch/database ::coordinate/coordinate])))
    (is (= prior-undo-coordinate selected))
    (is (= [:observe-lifecycle
            [:observe-blobs
             "http://127.0.0.1:7890/_seon/operator/blobs"
             prior-undo-coordinate]]
           @calls))
    (is (= (restore/confirmation-text
            {::restore/intent intent
             ::restore/confirmation-action
             :seon.dev.restore.confirmation/apply})
           (::restore/confirmation-text plan)))))

(deftest undo-plan-fails-closed-on-missing-duplicate-and-invalid-legacy-authority
  (let [configuration (config/load! (System/getProperty "user.dir"))
        base
        {#'restore-state/retained-intent (constantly nil)
         #'restore-state/require-manifest! (constantly artifact-identity)
         #'artifact/current-output-digests (constantly artifact-identity)
         #'restore-state/main-blob-url (constantly "http://main/blobs")
         #'restore-state/observe-retained-blobs!
         (fn [_ point]
           {:my.blob/ok? true
            :my.blob/target-coordinate point
            :my.blob/reachable-hash-digest digest
            :my.blob/hash-count 0})}
        plan #(restore-state/plan-undo!
               {::restore-state/configuration configuration
                ::restore-state/completion-id prior-completion-id})]
    (testing "missing and duplicate ids never choose a nearby completion"
      (doseq [completions [[] [prior-completion prior-completion]]]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"does not resolve exactly one"
             (with-redefs-fn
               (assoc base #'restore-state/observe-lifecycle!
                      (constantly (undo-lifecycle completions)))
               plan)))))
    (testing "an exact legacy row remains valid only while its retained head agrees"
      (is (m/validate ::db.restore/legacy-completion prior-completion))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"exact retained head"
           (with-redefs-fn
             (assoc base #'restore-state/observe-lifecycle!
                    (constantly
                     (update-in (undo-lifecycle)
                                [::protocol/branch-coordinates
                                 prior-undo-branch ::coordinate/commit-id]
                                (constantly (random-uuid)))))
             plan))))))

(deftest undo-apply-rechecks-completion-authority-and-resumes-without-a-branch-name
  (let [configuration (config/load! (System/getProperty "user.dir"))
        intent (restore/derive-intent (undo-intent-request))
        plan {::restore/intent intent
              ::restore/confirmation-text
              (restore/confirmation-text
               {::restore/intent intent
                ::restore/confirmation-action
                :seon.dev.restore.confirmation/apply})}
        calls (atom [])
        base
        {#'restore-state/observe-lifecycle! (constantly (undo-lifecycle))
         #'restore-state/retained-intent (constantly nil)
         #'restore-state/require-manifest! (constantly artifact-identity)
         #'artifact/current-output-digests (constantly artifact-identity)
         #'restore-state/derive-undo-intent! (fn [& _] intent)
         #'restore-state/publish-intent!
         (fn [request]
           (swap! calls conj [:publish (::restore/intent request)]))
         #'restore-state/resume!
         (fn [request]
           (swap! calls conj [:resume request])
           {::restore/intent-id intent-id
            ::restore-state/restored-coordinate latest-source
            ::restore-state/admin-outcome
            :seon.db.restore-admin.outcome/applied
            ::restore-state/transitions []})}
        request
        {::restore-state/configuration configuration
         ::restore-state/completion-id prior-completion-id
         ::restore/plan plan
         ::restore/confirmation-text (::restore/confirmation-text plan)}]
    (with-redefs-fn base #(restore-state/apply-undo! request))
    (is (= [[:publish intent]
            [:resume {::restore-state/configuration configuration
                      ::restore-state/admin-timeout-ms 120000}]]
           @calls))
    (reset! calls [])
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"does not resolve exactly one"
         (with-redefs-fn
           (assoc base #'restore-state/observe-lifecycle!
                  (constantly (undo-lifecycle [])))
           #(restore-state/apply-undo! request))))
    (is (empty? @calls))
    (testing "an equal retained plan retries through the same completion authority"
      (with-redefs-fn
        (assoc base #'restore-state/retained-intent (constantly intent))
        #(restore-state/apply-undo! request))
      (is (= [[:resume {::restore-state/configuration configuration
                        ::restore-state/admin-timeout-ms 120000}]]
             @calls)))))

(deftest apply-rejects-wrong-or-stale-authority-before-publication
  (let [configuration (config/load! (System/getProperty "user.dir"))
        intent (derived-intent)
        plan {::restore/intent intent
              ::restore/confirmation-text
              (restore/confirmation-text
               {::restore/intent intent
                ::restore/confirmation-action
                :seon.dev.restore.confirmation/apply})}
        calls (atom [])
        base-redefs
        {#'restore-state/require-selected-branch! (fn [_ _ value] value)
         #'restore-state/retained-intent (constantly nil)
         #'restore-state/require-manifest! (constantly artifact-identity)
         #'artifact/current-output-digests (constantly artifact-identity)
         #'restore-state/publish-intent!
         (fn [request]
           (swap! calls conj [:publish (::restore/intent request)]))
         #'restore-state/resume!
         (fn [_]
           (swap! calls conj :resume)
           {::restore/intent-id intent-id
            ::restore-state/restored-coordinate source
            ::restore-state/admin-outcome
            :seon.db.restore-admin.outcome/applied
            ::restore-state/transitions []})}]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"does not exactly authorize"
         (restore-state/apply!
          {::restore-state/configuration configuration
           ::restore-state/branch-name "target"
           ::restore/plan plan
           ::restore/confirmation-text "wrong"})))
    (is (empty? @calls))
    (let [stale (restore/derive-intent
                 (assoc (intent-request)
                        ::restore/reachable-hash-digest other-digest))]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"became stale"
           (with-redefs-fn
             (assoc base-redefs #'restore-state/derive-intent!
                    (fn [& _] stale))
             #(restore-state/apply!
               {::restore-state/configuration configuration
                ::restore-state/branch-name "target"
                ::restore/plan plan
                ::restore/confirmation-text
                (::restore/confirmation-text plan)})))))
    (is (empty? @calls))
    (with-redefs-fn
      (assoc base-redefs #'restore-state/derive-intent!
             (fn [& _] intent))
      #(restore-state/apply!
        {::restore-state/configuration configuration
         ::restore-state/branch-name "target"
         ::restore/plan plan
         ::restore/confirmation-text (::restore/confirmation-text plan)}))
    (is (= [[:publish intent] :resume] @calls))))

(deftest abort-deletes-only-preparation-free-authority-and-intent-last
  (let [configuration (config/load! (System/getProperty "user.dir"))
        intent (derived-intent)
        confirmation
        (restore/confirmation-text
         {::restore/intent intent
          ::restore/confirmation-action
          :seon.dev.restore.confirmation/abort})
        safe-observation (observation intent source {} #{} [] {})
        calls (atom [])
        invocation {::restore/admin-result-path "/admin.edn"}
        redefs
        {#'restore-state/retained-intent (constantly intent)
         #'restore-state/require-selected-branch! (fn [_ _ value] value)
         #'restore-state/admin-invocation (fn [& _] invocation)
         #'restore-state/require-no-admin-result!
         (fn [_] (swap! calls conj :admin-result-absent))
         #'restore-state/require-admin-absent!
         (fn [_] (swap! calls conj :admin-process-absent))
         #'restore-state/matching-restore-pod-record (constantly nil)
         #'restore-state/observe-restore! (fn [& _] safe-observation)
         #'state/delete-edn! (fn [path] (swap! calls conj [:delete path]))}
        result
        (with-redefs-fn
          redefs
          #(restore-state/abort!
            {::restore-state/configuration configuration
             ::restore-state/branch-name "target"
             ::restore/confirmation-text confirmation}))]
    (is (true? (::restore-state/aborted? result)))
    (is (= [[:delete (str (:seon.dev.config/cluster-dir configuration)
                          "/lifecycle/restore-blobs-" intent-id ".edn")]
            :admin-result-absent
            :admin-process-absent
            [:delete (restore/intent-path
                      (:seon.dev.config/cluster-dir configuration))]]
           (subvec (vec @calls) 2)))
    (reset! calls [])
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"forbids abort"
         (with-redefs-fn
           (assoc redefs #'restore-state/observe-restore!
                  (fn [& _]
                    (observation intent source
                                 {undo-branch (::restore/undo-coordinate intent)}
                                 #{} [] {})))
           #(restore-state/abort!
             {::restore-state/configuration configuration
              ::restore-state/branch-name "target"
              ::restore/confirmation-text confirmation}))))
    (is (not-any? #(and (vector? %) (= :delete (first %))) @calls))
    (reset! calls [])
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"forbids abort"
         (with-redefs-fn
           (assoc redefs #'restore-state/observe-restore!
                  (fn [& _]
                    (observation
                     intent source {} #{}
                     [(completion-fact intent forced-commit)]
                     {completion-id source})))
           #(restore-state/abort!
             {::restore-state/configuration configuration
              ::restore-state/branch-name "target"
              ::restore/confirmation-text confirmation}))))
    (is (not-any? #(and (vector? %) (= :delete (first %))) @calls))))

(deftest undo-abort-requires-the-same-completion-and-exact-confirmation
  (let [configuration (config/load! (System/getProperty "user.dir"))
        intent (restore/derive-intent (undo-intent-request))
        confirmation
        (restore/confirmation-text
         {::restore/intent intent
          ::restore/confirmation-action
          :seon.dev.restore.confirmation/abort})
        observation
        (observation intent latest-source {} #{} [prior-completion]
                     {prior-completion-id latest-source})
        calls (atom [])
        redefs
        {#'restore-state/retained-intent (constantly intent)
         #'restore-state/observe-lifecycle! (constantly (undo-lifecycle))
         #'restore-state/admin-invocation
         (fn [& _] {::restore/admin-result-path "/admin.edn"})
         #'restore-state/require-no-admin-result! (constantly true)
         #'restore-state/require-admin-absent! (constantly true)
         #'restore-state/matching-restore-pod-record (constantly nil)
         #'restore-state/observe-restore! (fn [& _] observation)
         #'state/delete-edn! (fn [path] (swap! calls conj path))}]
    (is (true?
         (::restore-state/aborted?
          (with-redefs-fn
            redefs
            #(restore-state/abort-undo!
              {::restore-state/configuration configuration
               ::restore-state/completion-id prior-completion-id
               ::restore/confirmation-text confirmation})))))
    (is (= (restore/intent-path
            (:seon.dev.config/cluster-dir configuration))
           (last @calls)))
    (reset! calls [])
    (doseq [request
            [{::restore-state/configuration configuration
              ::restore-state/completion-id "missing00001"
              ::restore/confirmation-text confirmation}
             {::restore-state/configuration configuration
              ::restore-state/completion-id prior-completion-id
              ::restore/confirmation-text "wrong"}]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (with-redefs-fn redefs
                     #(restore-state/abort-undo! request)))))
    (is (empty? @calls))))

(deftest abort-associates-completion-only-through-the-plan-digest
  (let [intent (derived-intent)
        matching (completion-fact intent forced-commit)
        unrelated (assoc matching
                         :seon.db.restore/id "restore00002"
                         :seon.db.restore/plan-digest other-digest)
        matching-observation
        (observation intent source {} #{} [matching] {completion-id source})
        unrelated-observation
        (observation intent source {} #{} [unrelated] {"restore00002" source})
        legacy-observation
        (observation intent source {} #{} [prior-completion]
                     {prior-completion-id source})]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"forbids abort"
         (#'restore-state/require-abortable-observation!
          intent matching-observation)))
    (is (= unrelated-observation
           (#'restore-state/require-abortable-observation!
            intent unrelated-observation)))
    (is (= legacy-observation
           (#'restore-state/require-abortable-observation!
            intent legacy-observation)))))

(deftest admin-result-is-associated-with-the-exact-retained-intent
  (let [intent (derived-intent)
        base (restore-admin/result-base intent)
        forced (assoc target
                      ::coordinate/branch :db
                      ::coordinate/commit-id forced-commit
                      ::coordinate/t 101)
        result
        (merge base
               {::restore-admin/outcome
                :seon.db.restore-admin.outcome/applied
                ::restore-admin/forced-main-coordinate forced
                ::restore-admin/branch-roster
                (::restore/expected-branch-roster intent)
                ::restore-admin/force-invoked? true
                ::restore-admin/connection-state
                :seon.db.restore-admin.connection/released})]
    (is (= result
           (#'restore-state/require-associated-admin-result! intent result)))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"another immutable intent"
         (#'restore-state/require-associated-admin-result!
          intent (assoc result ::restore-admin/plan-digest other-digest))))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"violates intent fences"
         (#'restore-state/require-associated-admin-result!
          intent (assoc result ::restore-admin/branch-roster #{:db}))))))

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
    (is (= (str "/cluster/lifecycle/restore-admin-" intent-id ".edn")
           (::restore/admin-result-path invocation)))
    (is (= 30000 (::restore/admin-timeout-ms invocation)))))

(deftest retry-command-is-derived-from-current-facts-without-a-phase
  (let [intent (derived-intent)
        undo-branch (::restore/undo-branch intent)
        target-branch (::restore/prepared-target-branch intent)
        undo (::restore/undo-coordinate intent)
        prepared-target (::restore/prepared-target-coordinate intent)
        promoted (assoc source ::coordinate/commit-id forced-commit
                        ::coordinate/t 101)
        completion (completion-fact intent forced-commit)
        completed (assoc source ::coordinate/commit-id completion-commit
                         ::coordinate/t 102)
        later (assoc completed ::coordinate/commit-id (random-uuid)
                     ::coordinate/t 103)
        reserved {undo-branch undo target-branch prepared-target}]
    (testing "preparation advances only from exact observed branch heads"
      (is (= :seon.dev.restore.command/create-undo
             (command intent
                      (observation intent source {} #{} [] {}))))
      (is (= :seon.dev.restore.command/create-target
             (command intent
                      (observation intent source {undo-branch undo}
                                   #{} [] {}))))
      (is (= :seon.dev.restore.command/prepare-exclusive-transition
             (command intent
                      (observation intent source reserved #{} [] {})))))
    (testing "the forced head and completion head are distinct exact commits"
      (is (= :seon.dev.restore.command/reconstruct-and-complete
             (command intent
                      (observation intent promoted reserved
                                   #{target-commit} [] {}))))
      (is (= :seon.dev.restore.command/prove-readiness
             (command intent
                      (observation intent completed reserved
                                   #{forced-commit} [completion]
                                   {completion-id 102}))))
      (is (= :seon.dev.restore.command/diagnose-divergence
             (command intent
                      (observation intent later reserved
                                   #{completion-commit} [completion]
                                   {completion-id 102})))
          "a later ordinary write cannot masquerade as completion readiness"))
    (testing "target ancestry cannot masquerade as an observed forced main"
      (is (= :seon.dev.restore.command/create-undo
             (command intent
                      (observation intent source {} #{source-commit target-commit}
                                   [] {}))))
      (is (= :seon.dev.restore.command/diagnose-divergence
             (command intent
                      (observation intent promoted reserved
                                   #{source-commit forced-commit} [] {})))))
    (testing "every crossed or unexplained durable state diagnoses divergence"
      (let [divergent-commit (random-uuid)]
        (is (= :seon.dev.restore.command/diagnose-divergence
               (command intent
                        (observation intent
                                     (assoc source ::coordinate/commit-id
                                            divergent-commit)
                                     {} #{divergent-commit} [] {})))))
      (is (= :seon.dev.restore.command/diagnose-divergence
             (command intent
                      (observation intent source
                                   {undo-branch
                                    (assoc undo ::coordinate/commit-id
                                           (random-uuid))}
                                   #{} [] {}))))
      (is (= :seon.dev.restore.command/diagnose-divergence
             (command intent
                      (observation intent completed reserved
                                   #{forced-commit}
                                   [(assoc completion
                                           :seon.db.restore/to-commit-id
                                           (random-uuid))]
                                   {completion-id 102}))))
      (is (= :seon.dev.restore.command/diagnose-divergence
             (command intent
                      (observation intent promoted {undo-branch undo}
                                   #{target-commit} [] {})))))))

(deftest observations-must-be-exact-self-consistent-facts
  (let [intent (derived-intent)
        wrong-database (random-uuid)
        completion (completion-fact intent forced-commit)
        completed (assoc source ::coordinate/commit-id completion-commit
                         ::coordinate/t 102)
        reserved {undo-branch (::restore/undo-coordinate intent)
                  prepared-target-branch
                  (::restore/prepared-target-coordinate intent)}]
    (is (thrown? Exception
                 (restore/next-command
                  {::restore/intent intent
                   ::restore/observation
                   (observation intent
                                (assoc source ::coordinate/database-id
                                       wrong-database)
                                {} #{} [] {})})))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"selected retained target moved"
         (restore/next-command
          {::restore/intent intent
           ::restore/observation
           (assoc-in
            (observation intent source {} #{} [] {})
            [::restore/branch-heads :seon.branch/target
             ::coordinate/commit-id]
            (random-uuid))})))
    (is (thrown? Exception
                 (restore/next-command
                  {::restore/intent intent
                   ::restore/observation
                   (observation intent source
                                {:wrong (assoc source ::coordinate/branch :db)}
                                #{} [] {})})))
    (is (thrown? Exception
                 (restore/next-command
                  {::restore/intent intent
                   ::restore/observation
                   (assoc (observation intent source {} #{} [] {})
                          ::restore/completed-restore-ids #{completion-id})})))
    (is (thrown? Exception
                 (restore/next-command
                  {::restore/intent intent
                   ::restore/observation
                   (observation intent completed reserved #{forced-commit}
                                [completion completion]
                                {completion-id 102})})))
    (is (thrown? Exception
                 (restore/next-command
                  {::restore/intent intent
                   ::restore/observation
                   (observation
                    intent completed reserved #{forced-commit}
                    [(assoc completion :seon.db.restore/database-id
                            wrong-database)]
                    {completion-id 102})})))
    (is (thrown? Exception
                 (restore/next-command
                  {::restore/intent intent
                   ::restore/observation
                   (observation intent completed reserved #{forced-commit}
                                [(dissoc completion :seon.db.restore/to-t)]
                                {completion-id 102})})))
    (is (thrown? Exception
                 (restore/next-command
                  {::restore/intent intent
                   ::restore/observation
                   (observation intent completed reserved #{forced-commit}
                                [completion] {completion-id 103})})))))
