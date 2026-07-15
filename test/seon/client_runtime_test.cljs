(ns seon.client-runtime-test
  "Process-local launch capability and non-autonomous runtime tests."
  (:require
    [cljs.test :refer [async deftest is testing]]
    [seon.agent :as agent]
    [seon.agent.loop :as agent-loop]
    [seon.agent.run :as agent-run]
    [seon.agent.runtime :as agent-runtime]
    [seon.ai :as ai]
    [seon.client :as client]
    [seon.config :as config]
    [seon.db :as db]
    [seon.db.coordinate :as db.coordinate]
    [seon.error :as error]
    [seon.eval :as seval]
    [seon.instrument :as instrument]
    [seon.launch :as launch]
    [my.blob :as blob]
    [seon.log :as log]
    [seon.repl :as repl]
    [seon.db.replica :as replica]
    [seon.db.restore :as db.restore]
    [seon.runtime.admission :as admission]
    [seon.runtime.recovery :as recovery]
    [seon.schema :as schema]
    [seon.web.brand :as web.brand]
    [seon.web.serve :as web.serve]))

(def non-autonomous-capability
  {::client/autonomous? false})

(defn- restore-launch-fixture []
  (let [database-id #uuid "9dcfa740-5f7f-4ff5-ac08-a9c8b605a8aa"
        generation #uuid "c5d99792-8f1b-4c22-a0ab-0f15cd11d739"
        point (fn [branch commit-id t]
                {::db.coordinate/database-id database-id
                 ::db.coordinate/branch branch
                 ::db.coordinate/commit-id commit-id
                 ::db.coordinate/t t})
        pre (point :db #uuid "7755012e-48ea-422b-9bdb-9a5b00f06378" 51)
        selected (point :seon.branch/retained
                        #uuid "64a83a56-14c2-467f-9bb8-3641b273be5f" 47)
        prepared (assoc selected ::db.coordinate/branch
                        :seon.restore.target/r-restoretest1)
        undo (assoc pre ::db.coordinate/branch
                    :seon.restore.undo/r-restoretest1)
        forced (point :db #uuid "497628ef-2308-455a-ab83-9487584bf2ed" 47)
        digest-a (apply str (repeat 64 "a"))
        digest-b (apply str (repeat 64 "b"))
        startup
        {:seon.dev.restore/startup-identity
         {:seon.dev.restore/intent-id
          #uuid "77777777-7777-4777-8777-777777777777"
          :seon.dev.restore/plan-digest digest-a
          :seon.dev.restore/reachable-hash-digest digest-b
          :seon.dev.restore/consumer-generations
          {:seon.dev.process/pod generation}}
         :seon.db.restore-admin/result
         {:seon.db.restore-admin/intent-id
          #uuid "77777777-7777-4777-8777-777777777777"
          :seon.db.restore-admin/plan-digest digest-a
          :seon.db.restore-admin/outcome
          :seon.db.restore-admin.outcome/applied
          :seon.db.restore-admin/pre-restore-main-coordinate pre
          :seon.db.restore-admin/selected-target-coordinate selected
          :seon.db.restore-admin/prepared-target-coordinate prepared
          :seon.db.restore-admin/undo-coordinate undo
          :seon.db.restore-admin/forced-main-coordinate forced
          :seon.db.restore-admin/branch-roster
          #{:db :seon.branch/retained
            :seon.restore.target/r-restoretest1
            :seon.restore.undo/r-restoretest1}
          :seon.db.restore-admin/force-invoked? true
          :seon.db.restore-admin/connection-state
          :seon.db.restore-admin.connection/released}
         :my.blob/materialization-result
         {:my.blob/ok? true
          :my.blob/target-coordinate selected
          :my.blob/reachable-hash-digest digest-b
          :my.blob/hash-count 2
          :my.blob/verified-count 2
          :my.blob/newly-materialized-count 1
          :my.blob/repaired-count 0}}
        descriptor
        (launch/with-restore-startup
          {::launch/descriptor
           (launch/with-coordinate
             {::launch/descriptor replica/default-launch-descriptor
              ::db.coordinate/coordinate forced})
           ::launch/restore-startup startup})]
    {:seon.client.test/descriptor descriptor
     :seon.client.test/startup startup
     :seon.client.test/generation generation
     :seon.client.test/forced-coordinate forced}))

(deftest launch-descriptor-composes-the-client-capability-owner
  (let [previous-state @client/!state
        capability
        (get-in replica/default-launch-descriptor
                [::launch/runtime :seon.client/launch-capability])]
    (try
      (swap! client/!state dissoc ::client/launch-capability)
      (is (= client/default-launch-capability capability))
      (is (= capability
             ((deref #'client/claim-launch-capability!) capability)))
      (is (= capability (client/launch-capability)))
      (finally
        (reset! client/!state previous-state)))))

(deftest client-claims-one-valid-process-blob-view
  (let [previous-state @client/!state
        previous-view @blob/!storage-view
        claimed {:my.blob/writable-dir "tmp/claimed-blobs"
                 :my.blob/read-only-dirs ["data/source-blobs"]}
        claim! (deref #'client/claim-blob-storage-view!)]
    (try
      (swap! client/!state dissoc ::client/blob-storage-view)
      (is (thrown? js/Error
                   (claim! {:my.blob/writable-dir ""
                            :my.blob/read-only-dirs []})))
      (is (= previous-view @blob/!storage-view)
          "invalid launch data cannot mutate the blob view")
      (is (nil? (::client/blob-storage-view @client/!state))
          "invalid launch data cannot reserve process ownership")
      (is (= claimed (claim! claimed)))
      (is (= claimed @blob/!storage-view))
      (is (= claimed (claim! claimed))
          "hot reload may reclaim the identical launch view")
      (is (thrown? js/Error
                   (claim! {:my.blob/writable-dir "tmp/other-blobs"
                            :my.blob/read-only-dirs []})))
      (finally
        (reset! client/!state previous-state)
        (reset! blob/!storage-view previous-view)))))

(defn- promise-value
  [value]
  (js/Promise.resolve value))

(defn- next-event-loop-turn
  []
  (js/Promise. (fn [resolve _reject]
                 (js/setTimeout resolve 0))))

(defn- effect-index [effects effect]
  (first
    (keep-indexed (fn [index value]
                    (when (= effect value) index))
                  effects)))

(defn- with-start-stubs
  "Run `body` while retaining async-safe runtime launch stubs."
  ([publication body]
   (with-start-stubs publication replica/process-launch-descriptor body))
  ([publication descriptor body]
  (let [effects (atom [])
        connection (atom {})
        environment (.-env js/process)
        previous-generation (aget environment "SEON_PROCESS_GENERATION")
        previous-state @client/!state
        previous-connection db/*conn*
        original-descriptor replica/process-launch-descriptor
        original-attached? db/attached?
        original-head db/head-coordinate
        original-installed db/installed-schema
        original-entity db/entity
        original-open client/open-database-connection!
        original-preconditions db/assert-preconditions!
        original-bootstrap repl/ensure-bootstrap!
        original-boot-seed client/boot-seed!
        original-recovery recovery/recover!
        original-create agent/create!
        original-initial agent/ensure-initial-agent!
        original-resumable agent/resumable-agent-ids
        original-query db/query
        original-transact db/transact!
        original-begin admission/begin-publication!
        original-replay client/replay-program-graph!
        original-prepare admission/prepare-committed!
        original-admit admission/admit-prepared!
        original-publish admission/publish-committed!
        original-record db.restore/record!
        original-readiness db.restore/readiness
        original-replica-attach replica/attach!
        original-resume agent/resume!
        original-web-start web.serve/start!
        original-ai-sync ai/sync!
        original-brand-sync web.brand/sync!
        original-ticker agent-loop/install-ticker!
        startup (::launch/restore-startup descriptor)
        forced (get-in descriptor
                       [::launch/database ::db.coordinate/coordinate])
        completion-coordinate
        (assoc forced
               ::db.coordinate/commit-id
               #uuid "4bc361ce-9c21-40b0-a61a-52a4dd3ecdb2"
               ::db.coordinate/t (inc (::db.coordinate/t forced)))
        !head (atom forced)
        preparation {::admission/prepared? true
                     ::admission/recovered? false
                     ::admission/generation 101
                     ::admission/instrumentation {}}]
    (set! replica/process-launch-descriptor descriptor)
    (if startup
      (aset environment "SEON_PROCESS_GENERATION"
            (str (get-in startup
                         [:seon.dev.restore/startup-identity
                          :seon.dev.restore/consumer-generations
                          :seon.dev.process/pod])))
      (js-delete environment "SEON_PROCESS_GENERATION"))
    (set! db/*conn* nil)
    (reset! client/!state
            (dissoc previous-state
                    ::client/launch-capability
                    ::client/runtime-phase))
    (set! db/attached? (fn [] (some? db/*conn*)))
    (set! db/head-coordinate
          (fn
            ([] @!head)
            ([_] @!head)))
    (set! db/installed-schema
          (fn [_] (zipmap db.restore/completion-attrs (repeat {}))))
    (set! db/entity
          (fn
            ([_] nil)
            ([_ _] nil)))
    (set! client/open-database-connection!
          (fn [request]
            (swap! effects conj [:attach request])
            (promise-value connection)))
    (set! db/assert-preconditions!
          (fn
            ([] (swap! effects conj :preconditions) nil)
            ([_] (swap! effects conj :preconditions) nil)))
    (set! repl/ensure-bootstrap!
          (fn []
            (swap! effects conj :bootstrap)
            (promise-value :compile-state)))
    (set! client/boot-seed!
          (fn [_]
            (swap! effects conj :forbidden/boot-seed)
            (promise-value {})))
    (set! recovery/recover!
          (fn [_]
            (swap! effects conj :forbidden/recovery)
            (promise-value {:seon.db/ok? true})))
    (set! agent/create!
          (fn [_]
            (swap! effects conj :forbidden/genesis)
            (promise-value {:seon.db/ok? true})))
    (set! agent/ensure-initial-agent!
          (fn [_]
            (swap! effects conj :forbidden/initial-agent)
            (promise-value {:seon.db/ok? true})))
    (set! agent/resumable-agent-ids
          (fn [_]
            (swap! effects conj :available-agents)
            ["root" "worker"]))
    (set! db/query
          (fn [& _]
            (swap! effects conj :read-agents)
            ["root" "worker"]))
    (set! db/transact!
          (fn [_]
            (swap! effects conj :forbidden/transaction)
            (promise-value {:seon.db/ok? true})))
    (set! admission/begin-publication!
          (fn []
            (swap! effects conj :publication-begin)
            true))
    (set! client/replay-program-graph!
          (fn [request]
            (swap! effects conj [:replay (::client/record-failures? request)])
            (promise-value {:seon.client/replay-n-total 0
                            :seon.client/replay-n-ok 0
                            :seon.client/replay-n-fail 0})))
    (set! admission/publish-committed!
          (fn []
            (swap! effects conj :publication-finish)
            publication))
    (set! admission/prepare-committed!
          (fn []
            (swap! effects conj :restore/prepare)
            preparation))
    (set! admission/admit-prepared!
          (fn [actual]
            (swap! effects conj [:restore/admit actual])
            (assoc (dissoc actual ::admission/prepared?)
                   ::admission/published? true)))
    (set! db.restore/record!
          (fn [{::db.restore/keys [completion-claim expected-coordinate]
                :as request}]
            (swap! effects conj [:restore/completion request])
            (when (= expected-coordinate @!head)
              (reset! !head completion-coordinate))
            (promise-value
              {::db.restore/ok? true
               ::db.restore/recorded? true
               ::db.restore/already-completed? false
               ::db.restore/completion
               (assoc completion-claim ::db.restore/id "restoretest1")
               ::db.restore/completion-coordinate completion-coordinate})))
    (set! db.restore/readiness
          (fn [{::db.restore/keys [completion completion-coordinate]}]
            {::db.restore/ready? true
             ::db.restore/executable? false
             ::db.restore/completion completion
             ::db.restore/completion-coordinate completion-coordinate}))
    (set! replica/attach!
          (fn [_]
            (swap! effects conj :replica)
            (promise-value {})))
    (set! agent/resume!
          (fn [_]
            (swap! effects conj :forbidden/host)
            (promise-value {:seon.agent.runtime/resumed? true})))
    (set! web.serve/start!
          (fn [request]
            (if (empty? request)
              (do
             (swap! effects conj :web)
             (promise-value {:seon.web/port 7890
                             :seon.web/port-file "tmp/test-port"}))
              (do
                (swap! effects conj [:web request])
                (promise-value {:seon.web/port 7890
                                :seon.web/port-file "tmp/test-port"})))))
    (set! ai/sync!
          (fn []
            (swap! effects conj :forbidden/provider)
            (promise-value {})))
    (set! web.brand/sync!
          (fn []
            (swap! effects conj :forbidden/brand)
            (promise-value {})))
    (set! agent-loop/install-ticker!
          (fn []
            (swap! effects conj :forbidden/ticker)
            :ticker))
    (-> (promise-value (body effects))
        (.finally
         (fn []
           (set! replica/process-launch-descriptor original-descriptor)
           (if (some? previous-generation)
             (aset environment "SEON_PROCESS_GENERATION" previous-generation)
             (js-delete environment "SEON_PROCESS_GENERATION"))
           (set! db/attached? original-attached?)
           (set! db/head-coordinate original-head)
           (set! db/installed-schema original-installed)
           (set! db/entity original-entity)
           (set! client/open-database-connection! original-open)
           (set! db/assert-preconditions! original-preconditions)
           (set! repl/ensure-bootstrap! original-bootstrap)
           (set! client/boot-seed! original-boot-seed)
           (set! recovery/recover! original-recovery)
           (set! agent/create! original-create)
           (set! agent/ensure-initial-agent! original-initial)
           (set! agent/resumable-agent-ids original-resumable)
           (set! db/query original-query)
           (set! db/transact! original-transact)
           (set! admission/begin-publication! original-begin)
           (set! client/replay-program-graph! original-replay)
           (set! admission/prepare-committed! original-prepare)
           (set! admission/admit-prepared! original-admit)
           (set! admission/publish-committed! original-publish)
           (set! db.restore/record! original-record)
           (set! db.restore/readiness original-readiness)
           (set! replica/attach! original-replica-attach)
           (set! agent/resume! original-resume)
           (set! web.serve/start! original-web-start)
           (set! ai/sync! original-ai-sync)
           (set! web.brand/sync! original-brand-sync)
           (set! agent-loop/install-ticker! original-ticker)
           (set! db/*conn* previous-connection)
           (reset! client/!state previous-state)))))))

(deftest non-autonomous-start-attaches-and-publishes-without-effects
  (async done
    (-> (with-start-stubs
         {::admission/published? true ::admission/instrumentation {}}
         (fn [effects]
           (-> (client/start-runtime!
                {::client/launch-capability non-autonomous-capability})
               (.then
                (fn [result]
                  (let [forbidden
                        (into #{}
                              (filter #(and (keyword? %)
                                            (= "forbidden" (namespace %))))
                              @effects)]
                    (is (true? (db/attached?)))
                    (is (false? (::client/autonomous? result)))
                    (is (= [] (:seon.client/resumed-ids result)))
                    (is (= [] (:seon.client/created-ids result)))
                    (is (= non-autonomous-capability
                           (client/launch-capability)))
                    (is (= :seon.client.runtime/running
                           ((deref #'client/runtime-phase))))
                    (is (= false
                           (-> @effects first second ::client/prepare-writes?)))
                    (is (some #{[:replay false]} @effects))
                    (is (some #{:publication-finish} @effects))
                    (is (some #{:web} @effects))
                    (is (empty? forbidden)
                        (str "non-autonomous start ran effects " forbidden))))))))
        (.then (fn [_] (done)))
        (.catch (fn [error]
                  (is false (str "non-autonomous start threw " error))
                  (done))))))

(deftest ordinary-autonomous-cold-start-retains-its-existing-composition
  (async done
    (-> (with-start-stubs
          {::admission/published? true ::admission/instrumentation {}}
          (fn [effects]
            (-> (client/start-runtime!
                  {::client/launch-capability client/default-launch-capability})
                (.then
                  (fn [result]
                    (is (true? (::client/autonomous? result)))
                    (is (= ["root"] (::client/created-ids result)))
                    (is (= true
                           (-> @effects first second
                               ::client/prepare-writes?)))
                    (is (every? (set @effects)
                                [:forbidden/boot-seed
                                 :forbidden/recovery
                                 :forbidden/genesis
                                 :forbidden/initial-agent
                                 :publication-finish
                                 :forbidden/provider
                                 :forbidden/brand
                                 :forbidden/ticker]))
                    (is (not-any? #(or (= :restore/prepare %)
                                       (and (vector? %)
                                            (#{:restore/completion
                                               :restore/admit}
                                             (first %))))
                                  @effects)))))))
        (.then (fn [_] (done)))
        (.catch
          (fn [error]
            (is false (str "ordinary autonomous start threw " error))
            (done))))))

(deftest restore-startup-rejects-capability-and-schema-before-writes
  (async done
    (let [{descriptor :seon.client.test/descriptor}
          (restore-launch-fixture)]
      (-> (with-start-stubs
            {::admission/published? true ::admission/instrumentation {}}
            descriptor
            (fn [effects]
              (-> (promise-value nil)
                  (.then
                    (fn []
                      (client/start-runtime!
                        {::client/launch-capability
                         client/default-launch-capability})))
                  (.then
                    (fn [_]
                      (throw (js/Error. "non-autonomous restore passed"))))
                  (.catch
                    (fn [error]
                      (is (re-find #"nonautonomous capability"
                                   (or (.-message error) (str error))))
                      (is (empty? @effects))
                      (reset! client/!state
                              (dissoc @client/!state
                                      ::client/launch-capability
                                      ::client/runtime-phase))
                      (set! db/installed-schema
                            (fn [_]
                              (zipmap (remove #{::db.restore/id}
                                              db.restore/completion-attrs)
                                      (repeat {}))))
                      (-> (promise-value nil)
                          (.then
                            (fn []
                              (client/start-runtime!
                                {::client/launch-capability
                                 non-autonomous-capability})))
                          (.then
                            (fn [_]
                              (throw (js/Error. "missing restore schema passed"))))
                          (.catch
                            (fn [schema-error]
                              (is (re-find #"lacks completion schema"
                                           (or (.-message schema-error)
                                               (str schema-error))))
                              (is (= [[:attach
                                       {::client/prepare-writes? false}]]
                                     @effects))))))))))
          (.then (fn [_] (done)))
          (.catch
            (fn [error]
              (is false (str "restore precondition proof threw " error))
              (done)))))))

(deftest restore-startup-prepares-completes-and-stays-closed
  (async done
    (let [{descriptor :seon.client.test/descriptor}
          (restore-launch-fixture)
          expected-claim
          (db.restore/completion-from-launch
            {::launch/descriptor descriptor})]
      (-> (with-start-stubs
            {::admission/published? true ::admission/instrumentation {}}
            descriptor
            (fn [effects]
              (-> (client/start-runtime!
                    {::client/launch-capability
                     non-autonomous-capability})
                  (.then
                    (fn [result]
                      (let [events @effects
                            completion-event
                            (first
                              (filter #(and (vector? %)
                                            (= :restore/completion (first %)))
                                      events))
                            completion-request (second completion-event)
                            web-event
                            (first
                              (filter #(and (vector? %)
                                            (= :web (first %)))
                                      events))
                            web-request (second web-event)]
                        (is (false? (::client/autonomous? result)))
                        (is (= [] (::client/created-ids result)))
                        (is (= [] (::client/resumed-ids result)))
                        (is (= false
                               (-> events first second
                                   ::client/prepare-writes?)))
                        (is (= expected-claim
                               (::db.restore/completion-claim
                                completion-request)))
                        (is (= (get-in descriptor
                                       [::launch/database
                                        ::db.coordinate/coordinate])
                               (::db.restore/expected-coordinate
                                completion-request)))
                        (is (= :restore/prepare
                               (nth events
                                    (effect-index events :restore/prepare))))
                        (is (< (effect-index events :publication-begin)
                               (effect-index events :restore/prepare)
                               (effect-index events completion-event)
                               (effect-index events web-event)))
                        (is (true? (::web.serve/readiness-only? web-request)))
                        (is (= "restoretest1"
                               (get-in web-request
                                       [::web.serve/restore-completion-result
                                        ::db.restore/completion
                                        ::db.restore/id])))
                        (is (not-any? #{:forbidden/boot-seed
                                        :forbidden/recovery
                                        :forbidden/genesis
                                        :forbidden/initial-agent
                                        :forbidden/transaction
                                        :publication-finish
                                        :forbidden/host
                                        :web
                                        :forbidden/provider
                                        :forbidden/brand
                                        :forbidden/ticker}
                                      events))
                        (is (not-any? #(and (vector? %)
                                           (= :restore/admit (first %)))
                                      events))
                        (client/start-runtime!
                          {::client/launch-capability
                           non-autonomous-capability}))))
                  (.then
                    (fn [repeated]
                      (is (false? (::client/autonomous? repeated)))
                      (is (= 1 (count (filter #(and (vector? %)
                                                    (= :restore/completion
                                                       (first %)))
                                             @effects)))
                          "attached refresh reuses retained completion+C")
                      (is (= 2 (count (filter #(and (vector? %)
                                                    (= :web (first %)))
                                             @effects)))
                          "attached refresh reproves the same readiness door"))))))
          (.then (fn [_] (done)))
          (.catch
            (fn [error]
              (is false (str "restore composition threw " error))
              (done)))))))

(deftest restore-startup-mismatches-fail-before-writes-or-autonomy
  (async done
    (let [{descriptor :seon.client.test/descriptor
           forced :seon.client.test/forced-coordinate}
          (restore-launch-fixture)
          environment (.-env js/process)]
      (-> (with-start-stubs
            {::admission/published? true ::admission/instrumentation {}}
            descriptor
            (fn [effects]
              (aset environment "SEON_PROCESS_GENERATION"
                    "00000000-0000-4000-8000-000000000000")
              (-> (client/start-runtime!
                    {::client/launch-capability
                     non-autonomous-capability})
                  (.then
                    (fn [_]
                      (throw (js/Error. "generation mismatch passed"))))
                  (.catch
                    (fn [error]
                      (is (re-find #"expected pod generation"
                                   (or (.-message error) (str error))))
                      (is (empty? @effects))
                      (aset environment "SEON_PROCESS_GENERATION"
                            (str (get-in descriptor
                                         [::launch/restore-startup
                                          :seon.dev.restore/startup-identity
                                          :seon.dev.restore/consumer-generations
                                          :seon.dev.process/pod])))
                      (set! db/head-coordinate
                            (fn
                              ([] (update forced ::db.coordinate/t inc))
                              ([_] (update forced ::db.coordinate/t inc))))
                      (swap! client/!state dissoc ::client/runtime-phase)
                      (-> (client/start-runtime!
                            {::client/launch-capability
                             non-autonomous-capability})
                          (.then
                            (fn [_]
                              (throw (js/Error. "head mismatch passed"))))
                          (.catch
                            (fn [head-error]
                              (is (re-find #"another main database point"
                                           (or (.-message head-error)
                                               (str head-error))))
                              (is (= [[:attach
                                       {::client/prepare-writes? false}]]
                                     @effects))))))))))
          (.then (fn [_] (done)))
          (.catch
            (fn [error]
              (is false (str "restore mismatch proof threw " error))
              (done)))))))

(deftest restore-replay-fault-recording-stays-closed-and-blocks-completion
  (async done
    (let [{descriptor :seon.client.test/descriptor}
          (restore-launch-fixture)]
      (-> (with-start-stubs
            {::admission/published? true ::admission/instrumentation {}}
            descriptor
            (fn [effects]
              (set! client/replay-program-graph!
                    (fn [request]
                      (swap! effects conj
                             [:restore/replay-fault
                              (::client/record-failures? request)])
                      (promise-value
                        {::client/replay-n-total 1
                         ::client/replay-n-ok 0
                         ::client/replay-n-fail 1})))
              (-> (client/start-runtime!
                    {::client/launch-capability
                     non-autonomous-capability})
                  (.then
                    (fn [_]
                      (throw (js/Error. "failed restore replay completed"))))
                  (.catch
                    (fn [error]
                      (is (re-find #"restored program replay failed"
                                   (or (.-message error) (str error))))
                      (is (some #{[:restore/replay-fault false]} @effects))
                      (is (not-any? #(or (= :restore/prepare %)
                                         (and (vector? %)
                                              (#{:restore/completion
                                                 :restore/admit}
                                               (first %)))
                                         (#{:forbidden/host :web
                                            :forbidden/provider
                                            :forbidden/brand
                                            :forbidden/ticker} %))
                                    @effects)))))))
          (.then (fn [_] (done)))
          (.catch
            (fn [error]
              (is false (str "restore replay proof threw " error))
              (done)))))))

(deftest restore-completion-failure-remains-closed
  (async done
    (let [{descriptor :seon.client.test/descriptor}
          (restore-launch-fixture)]
      (-> (with-start-stubs
            {::admission/published? true ::admission/instrumentation {}}
            descriptor
            (fn [effects]
              (set! db.restore/record!
                    (fn [_]
                      (swap! effects conj :restore/completion-failed)
                      (promise-value
                        {::db.restore/ok? false
                         :seon/error {:seon.error/kind :core-bug}})))
              (-> (client/start-runtime!
                    {::client/launch-capability
                     non-autonomous-capability})
                  (.then
                    (fn [_]
                      (throw (js/Error. "completion failure admitted"))))
                  (.catch
                    (fn [error]
                      (is (re-find #"restore completion failed"
                                   (or (.-message error) (str error))))
                      (is (some #{:restore/prepare} @effects))
                      (is (some #{:restore/completion-failed} @effects))
                      (is (not-any? #(or (and (vector? %)
                                              (= :restore/admit (first %)))
                                         (#{:forbidden/host :web
                                            :forbidden/provider
                                            :forbidden/brand
                                            :forbidden/ticker} %))
                                    @effects)))))))
          (.then (fn [_] (done)))
          (.catch
            (fn [error]
              (is false (str "restore completion retry proof threw " error))
              (done)))))))

(deftest restore-rejects-completion-whose-origin-is-not-current-head
  (async done
    (let [{descriptor :seon.client.test/descriptor
           forced :seon.client.test/forced-coordinate}
          (restore-launch-fixture)]
      (-> (with-start-stubs
            {::admission/published? true ::admission/instrumentation {}}
            descriptor
            (fn [effects]
              (set! db.restore/record!
                    (fn [{::db.restore/keys [completion-claim]}]
                      (swap! effects conj :restore/completion-at-stale-head)
                      (set! db/head-coordinate
                            (fn
                              ([] (update forced ::db.coordinate/t inc))
                              ([_] (update forced ::db.coordinate/t inc))))
                      (promise-value
                       {::db.restore/ok? true
                        ::db.restore/recorded? false
                        ::db.restore/already-completed? true
                        ::db.restore/completion
                        (assoc completion-claim
                               ::db.restore/id "restoretest1")
                        ::db.restore/completion-coordinate forced})))
              (-> (client/start-runtime!
                    {::client/launch-capability
                     non-autonomous-capability})
                  (.then
                    (fn [_]
                      (throw (js/Error. "stale completion reported ready"))))
                  (.catch
                    (fn [error]
                      (is (re-find #"restore completion failed"
                                   (or (.-message error) (str error))))
                      (is (some #{:restore/completion-at-stale-head} @effects))
                      (is (not-any? #{:forbidden/host :web
                                      :forbidden/provider
                                      :forbidden/brand
                                      :forbidden/ticker}
                                    @effects)))))))
          (.then (fn [_] (done)))
          (.catch
            (fn [error]
              (is false (str "restore post-completion retry proof threw " error))
              (done)))))))

(deftest autonomous-launch-capability-remains-the-default
  (let [previous-state @client/!state]
    (try
      (swap! client/!state dissoc ::client/launch-capability)
      (is (= client/default-launch-capability (client/launch-capability)))
      (is (true? (client/autonomous-runtime?)))
      (finally
        (reset! client/!state previous-state)))))

(deftest attached-running-start-is-an-idempotent-read-surface-refresh
  (async done
    (let [previous-state @client/!state
          previous-connection db/*conn*
          connection (atom {})
          effects (atom [])
          original-attached? db/attached?
          original-resumable agent/resumable-agent-ids
          original-replica-attach replica/attach!
          original-web-start web.serve/start!
          original-replay client/replay-program-graph!
          original-begin admission/begin-publication!]
      (set! db/*conn* connection)
      (reset! client/!state
              (assoc previous-state
                     ::client/launch-capability non-autonomous-capability
                     ::client/runtime-phase :seon.client.runtime/running))
      (set! db/attached? (constantly true))
      (set! agent/resumable-agent-ids
            (fn [_]
              (swap! effects conj :status)
              ["root"]))
      (set! replica/attach!
            (fn [_]
              (swap! effects conj :replica)
              (promise-value {})))
      (set! web.serve/start!
            (fn [_]
              (swap! effects conj :web)
              (promise-value {:seon.web/port 7890
                              :seon.web/port-file "tmp/test-port"})))
      (set! client/replay-program-graph!
            (fn [_]
              (swap! effects conj :forbidden/replay)
              (promise-value {})))
      (set! admission/begin-publication!
            (fn []
              (swap! effects conj :forbidden/publication)
              true))
      (-> (client/start-runtime!
           {::client/launch-capability non-autonomous-capability})
          (.then
           (fn [result]
             (is (= [:status :replica :web] @effects))
             (is (= [] (::client/resumed-ids result)))
             (is (= :seon.client.runtime/running
                    ((deref #'client/runtime-phase))))))
          (.catch
           (fn [error]
             (is false (str "attached refresh threw " error))))
          (.finally
           (fn []
             (set! db/attached? original-attached?)
             (set! agent/resumable-agent-ids original-resumable)
             (set! replica/attach! original-replica-attach)
             (set! web.serve/start! original-web-start)
             (set! client/replay-program-graph! original-replay)
             (set! admission/begin-publication! original-begin)
             (set! db/*conn* previous-connection)
             (reset! client/!state previous-state)
             (done)))))))

(deftest failed-publication-cannot-be-reported-as-an-attached-runtime
  (async done
    (-> (with-start-stubs
         {::admission/published? false ::admission/instrumentation {}}
         (fn [effects]
           (-> (client/start-runtime!
                {::client/launch-capability non-autonomous-capability})
               (.then (fn [_] (throw (js/Error. "publication unexpectedly passed"))))
               (.catch
                (fn [_]
                  (is (true? (db/attached?)))
                  (is (= :seon.client.runtime/cleanup-required
                         ((deref #'client/runtime-phase))))
                  (let [effect-count (count @effects)]
                    (-> (client/start-runtime!
                         {::client/launch-capability non-autonomous-capability})
                        (.then
                         (fn [_]
                           (throw (js/Error. "partial runtime reported success"))))
                        (.catch
                         (fn [error]
                           (is (re-find #"requires cleanup"
                                        (or (.-message error) (str error))))
                           (is (= effect-count (count @effects))
                               "repeat refuses before attach/replay/web"))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [error]
                  (is false (str "publication failure proof threw " error))
                  (done))))))

(deftest incomplete-unattached-phases-refuse-without-effects
  (async done
    (-> (with-start-stubs
         {::admission/published? true ::admission/instrumentation {}}
         (fn [effects]
           (letfn [(refused [phase]
                     (swap! client/!state assoc ::client/runtime-phase phase)
                     (-> (client/start-runtime!
                          {::client/launch-capability
                           non-autonomous-capability})
                         (.then
                          (fn [_]
                            (throw (js/Error. (str phase " unexpectedly ran")))))
                         (.catch
                          (fn [error]
                            (is (re-find #"requires cleanup"
                                         (or (.-message error) (str error))))
                            (is (empty? @effects))))))]
             (-> (refused :seon.client.runtime/starting)
                 (.then
                  (fn [_]
                    (refused :seon.client.runtime/cleanup-required)))))))
        (.then (fn [_] (done)))
        (.catch (fn [error]
                  (is false (str "unattached phase proof threw " error))
                  (done))))))

(deftest concurrent-cold-start-refuses-before-a-second-attachment
  (async done
    (-> (with-start-stubs
         {::admission/published? true ::admission/instrumentation {}}
         (fn [effects]
           (let [connection (atom {})
                 resolve-open (atom nil)
                 pending-open
                 (js/Promise. (fn [resolve _reject]
                                (reset! resolve-open resolve)))]
             (set! client/open-database-connection!
                   (fn [request]
                     (swap! effects conj [:pending-attach request])
                     pending-open))
             (let [first-start
                   (client/start-runtime!
                    {::client/launch-capability non-autonomous-capability})]
               (-> (client/start-runtime!
                    {::client/launch-capability non-autonomous-capability})
                   (.then
                    (fn [_]
                      (throw (js/Error. "concurrent start unexpectedly ran"))))
                   (.catch
                    (fn [error]
                      (is (re-find #"requires cleanup"
                                   (or (.-message error) (str error))))
                      (is (= 1 (count (filter vector? @effects)))
                          "only the first launch reaches attachment")
                      (-> (client/stop-runtime!)
                          (.then
                           (fn [stop-result]
                             (is (false? (::client/stopped? stop-result)))
                             (is (= :seon.client.runtime/starting
                                    ((deref #'client/runtime-phase)))
                                 "a concurrent stop cannot overwrite start")
                             (@resolve-open connection)
                             first-start))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [error]
                  (is false (str "concurrent start proof threw " error))
                  (done))))))

(deftest pre-attachment-failure-remains-cleanup-required
  (async done
    (-> (with-start-stubs
         {::admission/published? true ::admission/instrumentation {}}
         (fn [effects]
           (set! client/open-database-connection!
                 (fn [_]
                   (swap! effects conj :attach-failed)
                   (js/Promise.reject (js/Error. "injected attach failure"))))
           (-> (client/start-runtime!
                {::client/launch-capability non-autonomous-capability})
               (.then (fn [_] (throw (js/Error. "attach unexpectedly passed"))))
               (.catch
                (fn [_]
                  (is (false? (db/attached?)))
                  (is (= :seon.client.runtime/cleanup-required
                         ((deref #'client/runtime-phase))))
                  (is (= [:attach-failed] @effects)))))))
        (.then (fn [_] (done)))
        (.catch (fn [error]
                  (is false (str "pre-attachment proof threw " error "\n"
                                 (.-stack error)))
                  (done))))))

(deftest capability-is-exact-and-survives-non-autonomous-reload
  (let [previous-state @client/!state
        effects (atom [])]
    (try
      (reset! client/!state
              (assoc previous-state
                     ::client/launch-capability non-autonomous-capability))
      (with-redefs [db/attached? (constantly true)
                    admission/publish-committed!
                    (fn []
                      (swap! effects conj :publish)
                      {::admission/published? true
                       ::admission/instrumentation {}})
                    agent-loop/install-ticker!
                    (fn [] (swap! effects conj :forbidden/ticker))
                    agent-loop/uninstall-ticker!
                    (fn [] (swap! effects conj :ticker-stopped))
                    agent-runtime/unhost-all!
                    (fn []
                      (swap! effects conj :agents-unhosted)
                      {::agent-runtime/unhosted-ids []})
                    client/start-heartbeat!
                    (fn [] (swap! effects conj :heartbeat))]
        (is (true? (client/shadow-build-notify! {:type :build-complete})))
        (is (= [:publish :ticker-stopped :agents-unhosted :heartbeat]
               @effects))
        (is (= non-autonomous-capability (client/launch-capability)))
        (is (thrown? js/Error
                     ((deref #'client/claim-launch-capability!)
                      client/default-launch-capability))))
      (testing "an attached legacy process cannot infer a retained grant"
        (swap! client/!state dissoc ::client/launch-capability)
        (with-redefs [db/attached? (constantly true)]
          (is (thrown? js/Error
                       ((deref #'client/claim-launch-capability!)
                        client/default-launch-capability)))))
      (finally
        (reset! client/!state previous-state)))))

(deftest restore-preparation-ignores-shadow-build-admission
  (let [{descriptor :seon.client.test/descriptor}
        (restore-launch-fixture)
        previous-descriptor replica/process-launch-descriptor
        previous-state @client/!state
        effects (atom [])]
    (try
      (set! replica/process-launch-descriptor descriptor)
      (reset! client/!state
              (assoc previous-state
                     ::client/launch-capability non-autonomous-capability))
      (with-redefs [db/attached? (constantly true)
                    admission/begin-publication!
                    (fn [] (swap! effects conj :begin) true)
                    admission/mark-unavailable!
                    (fn [_] (swap! effects conj :unavailable) true)
                    admission/publish-committed!
                    (fn []
                      (swap! effects conj :publish)
                      {::admission/published? true})]
        (doseq [message [{:type :build-start}
                         {:type :build-failure}
                         {:type :build-complete}]]
          (is (true? (client/shadow-build-notify! message))))
        (is (empty? @effects)
            "restore preparation cannot open or replace admission on reload"))
      (finally
        (set! replica/process-launch-descriptor previous-descriptor)
        (reset! client/!state previous-state)))))

(deftest bulk-unhost-derives-exact-process-local-owners
  (let [loop-input (deref #'agent-loop/!loop-input)
        previous-input @loop-input
        original-uninstall agent-loop/uninstall-wake-trigger!
        calls (atom [])]
    (try
      (reset! loop-input {"worker-b" {} "worker-a" {}})
      (set! agent-loop/uninstall-wake-trigger!
            (fn [{:seon.agent/keys [id]}]
              (swap! calls conj id)
              (swap! loop-input dissoc id)
              {:seon.agent/id id
               :seon.agent.loop/uninstalled? true}))
      (let [result (agent-runtime/unhost-all!)]
        (is (= ["worker-a" "worker-b"] @calls))
        (is (= ["worker-a" "worker-b"]
               (::agent-runtime/unhosted-ids result)))
        (is (empty? @loop-input)))
      (finally
        (set! agent-loop/uninstall-wake-trigger! original-uninstall)
        (reset! loop-input previous-input)))))

(deftest non-autonomous-replay-failures-are-console-only-and-continue
  (async done
    (let [eval-calls (atom 0)
          database-writes (atom 0)
          fault-records (atom 0)
          console-errors (atom 0)
          original-eval seval/eval
          original-transact db/transact!
          original-record error/record!
          original-console log/error-console!]
      (set! seval/eval
            (fn
              ([_compile-state _source _options]
               (case (swap! eval-calls inc)
                 1 (promise-value {:seon.eval/ok? false
                                   :seon/error (js/Error. "returned failure")})
                 2 (throw (js/Error. "thrown machinery failure"))
                 (promise-value {:seon.eval/ok? true})))
              ([_compile-state _source]
               (promise-value {:seon.eval/ok? true}))))
      (set! db/transact!
            (fn [_]
              (swap! database-writes inc)
              (promise-value {:seon.db/ok? true})))
      (set! error/record! (fn [_] (swap! fault-records inc)))
      (set! log/error-console!
            (fn [& _]
              (swap! console-errors inc)
              nil))
      (let [run-replay (deref #'client/replay-ordered-sources!)]
        (-> (apply run-replay
                   [:compile-state
                    [['my.returned "(returned)"]
                     ['my.thrown "(thrown)"]
                     ['my.later "(later)"]]
                    "root"
                    false])
          (.then
           (fn [n-fail]
             (is (= 2 n-fail))
             (is (= 3 @eval-calls)
                 "the namespace after both failure shapes still evaluates")
             (is (zero? @database-writes)
                 "the database replay logger never runs")
             (is (zero? @fault-records)
                 "neither machinery nor double-fault records to the database")
             (is (= 3 @console-errors)
                 "returned failure plus machinery and its namespace report")))
          (.catch
           (fn [replay-error]
             (is false (str "non-autonomous replay threw " replay-error))))
          (.finally
           (fn []
             (set! seval/eval original-eval)
             (set! db/transact! original-transact)
             (set! error/record! original-record)
             (set! log/error-console! original-console)
             (done))))))))

(deftest stop-is-ordered-awaited-idempotent-and-serialized
  (async done
    (let [previous-state @client/!state
          previous-admission @(deref #'admission/!state)
          previous-connection db/*conn*
          connection (atom {})
          effects (atom [])
          resolve-web (atom nil)
          pending-web (js/Promise. (fn [resolve _reject]
                                     (reset! resolve-web resolve)))
          original-attached? db/attached?
          original-web-stop web.serve/stop!
          original-ticker-stop agent-loop/uninstall-ticker!
          original-unhost agent-runtime/unhost-all!
          original-replica-detach replica/detach!
          original-admission-detach admission/detach!
          original-release db/release-connection!]
      (set! db/*conn* connection)
      (reset! client/!state
              (assoc previous-state
                     ::client/launch-capability non-autonomous-capability
                     ::client/runtime-phase :seon.client.runtime/running))
      (set! db/attached? (fn [] (identical? db/*conn* connection)))
      (set! web.serve/stop!
            (fn []
              (swap! effects conj :web)
              pending-web))
      (set! agent-loop/uninstall-ticker!
            (fn [] (swap! effects conj :ticker)))
      (set! agent-runtime/unhost-all!
            (fn []
              (swap! effects conj :hosts)
              {::agent-runtime/unhosted-ids ["root"]}))
      (set! replica/detach! (fn [] (swap! effects conj :replica)))
      (set! admission/detach!
            (fn []
              (swap! effects conj :admission)
              {::admission/detached? true
               ::admission/instrumentation {}}))
      (set! db/release-connection!
            (fn [{::db/keys [conn]}]
              (is (identical? connection conn))
              (swap! effects conj :release)
              (promise-value {::db/released? true})))
      (let [first-stop (client/stop-runtime!)]
        (-> (next-event-loop-turn)
            (.then
             (fn [_]
               (is (= [:web] @effects)
                   "teardown waits at the web/SSE server-close promise")
               (is (= :seon.client.runtime/stopping
                      ((deref #'client/runtime-phase))))
               (js/Promise.all
                #js [(client/stop-runtime!)
                     (-> (client/start-runtime!
                          {::client/launch-capability
                           non-autonomous-capability})
                         (.then (fn [_] :unexpected-start))
                         (.catch (fn [_] :start-refused)))])))
            (.then
             (fn [concurrent]
               (is (false? (::client/stopped? (aget concurrent 0))))
               (is (= :start-refused (aget concurrent 1)))
               (is (= :seon.client.runtime/stopping
                      ((deref #'client/runtime-phase)))
                   "concurrent calls cannot publish a false phase")
               (@resolve-web nil)
               first-stop))
            (.then
             (fn [result]
               (is (true? (::client/stopped? result)))
               (is (= ["root"] (::agent-runtime/unhosted-ids result)))
               (is (= [:web :ticker :hosts :replica :admission :release]
                      @effects))
               (is (nil? db/*conn*))
               (is (nil? ((deref #'client/runtime-phase))))
               (client/stop-runtime!)))
            (.then
             (fn [second-result]
               (is (true? (::client/stopped? second-result)))
               (is (= [:web :ticker :hosts :replica :admission :release]
                      @effects)
                   "an already-stopped inverse is effect-free")))
            (.catch
             (fn [error]
               (is false (str "ordered stop proof threw " error))))
            (.finally
             (fn []
               (set! db/attached? original-attached?)
               (set! web.serve/stop! original-web-stop)
               (set! agent-loop/uninstall-ticker! original-ticker-stop)
               (set! agent-runtime/unhost-all! original-unhost)
               (set! replica/detach! original-replica-detach)
               (set! admission/detach! original-admission-detach)
               (set! db/release-connection! original-release)
               (set! db/*conn* previous-connection)
               (reset! client/!state previous-state)
               (reset! (deref #'admission/!state) previous-admission)
               (done))))))))

(deftest planned-quiesce-drains-durable-work-and-retains-one-result
  (async done
    (let [previous-state @client/!state
          previous-admission @(deref #'admission/!state)
          previous-connection db/*conn*
          previous-generation
          (aget (.-env js/process) "SEON_PROCESS_GENERATION")
          process-generation "6d295410-5883-4d9f-a532-8f7b71b9812a"
          connection (atom {})
          effects (atom [])
          work-call (atom 0)
          release! (atom nil)
          release-promise (js/Promise. (fn [resolve _reject]
                                         (reset! release! resolve)))
          coordinate {:seon.db.coordinate/database-id (random-uuid)
                      :seon.db.coordinate/branch :db
                      :seon.db.coordinate/commit-id (random-uuid)
                      :seon.db.coordinate/t 42}
          original-ticker agent-loop/uninstall-ticker!
          original-wakes agent-loop/uninstall-all-wake-triggers!
          original-work agent-run/quiescence-work
          original-close agent-run/close-run!
          original-unhost agent-runtime/unhost-all!
          original-replica replica/detach!
          original-admission admission/detach!
          original-entity db/entity
          original-head db/head-coordinate
          original-release db/release-connection!]
      (aset (.-env js/process) "SEON_PROCESS_GENERATION" process-generation)
      (set! db/*conn* connection)
      (reset! client/!state
              (assoc previous-state
                     ::client/launch-capability client/default-launch-capability
                     ::client/runtime-phase :seon.client.runtime/running))
      (reset! (deref #'admission/!state)
              {::admission/status :available ::admission/generation 42})
      (set! agent-loop/uninstall-ticker!
            (fn [] (swap! effects conj :ticker)))
      (set! agent-loop/uninstall-all-wake-triggers!
            (fn []
              (swap! effects conj :wakes)
              {::agent-loop/uninstalled-ids ["root"]}))
      (set! agent-run/quiescence-work
            (fn [_]
              (let [call (swap! work-call inc)]
                (swap! effects conj [:work call])
                (case call
                  (1 2)
                  {::agent-run/current-runs
                   [{:seon.agent/id "root"
                     :seon.agent.run/id "run-1"}]
                   ::agent-run/running-turns
                   [{:seon.agent.run/id "run-1"
                     :seon.agent.turn/id "turn-1"}]}

                  3
                  {::agent-run/current-runs
                   [{:seon.agent/id "root"
                     :seon.agent.run/id "run-1"}]
                   ::agent-run/running-turns []}

                  {::agent-run/current-runs []
                   ::agent-run/running-turns []}))))
      (set! agent-run/close-run!
            (fn [request]
              (swap! effects conj [:close request])
              (promise-value {:seon.db/ok? true})))
      (set! agent-runtime/unhost-all!
            (fn []
              (swap! effects conj :hosts)
              {::agent-runtime/unhosted-ids []}))
      (set! replica/detach! (fn [] (swap! effects conj :replica) true))
      (set! admission/detach!
            (fn []
              (swap! effects conj :projection)
              {::admission/detached? true}))
      (set! db/entity
            (fn
              ([{:seon.db/keys [ref]}]
               (is (= [:seon.agent.turn/id "turn-1"] ref))
               {:seon.agent.turn/status :done})
              ([_database ref]
               (is (= [:seon.agent.turn/id "turn-1"] ref))
               {:seon.agent.turn/status :done})))
      (set! db/head-coordinate
            (fn
              ([] coordinate)
              ([database]
               (is (identical? @connection database))
               (swap! effects conj :coordinate)
               coordinate)))
      (set! db/release-connection!
            (fn [{::db/keys [conn]}]
              (is (identical? connection conn))
              (swap! effects conj :release)
              release-promise))
      (let [first (client/quiesce-runtime!)]
        (-> (next-event-loop-turn)
            (.then
             (fn [_]
               (is (= :seon.client.runtime/quiescing
                      ((deref #'client/runtime-phase))))
               (client/quiesce-runtime!)))
            (.then
             (fn [overlap]
               (is (false? (::client/quiesced? overlap)))
               (is (re-find #"already in progress"
                            (::client/quiesce-error overlap)))
               (@release! {::db/released? true})
               first))
            (.then
             (fn [result]
               (is (= {::client/quiesced? true
                       ::db.coordinate/coordinate coordinate
                       ::client/quiesced-run-ids ["run-1"]
                       ::client/completed-turn-ids ["turn-1"]
                       ::client/errored-turn-ids []
                       ::agent-runtime/unhosted-ids ["root"]
                       :seon.runtime.lifecycle/process-generation
                       process-generation}
                      result))
               (is (= :seon.client.runtime/quiesced
                      ((deref #'client/runtime-phase))))
               (let [positions (zipmap @effects (range))]
                 (is (< (get positions :coordinate)
                        (get positions :projection))
                     "the final coordinate validates before schema projection detach"))
               (is (nil? db/*conn*))
               (is (not (contains? @client/!state
                                   ::client/launch-capability)))
               (client/quiesce-runtime!)))
            (.then
             (fn [repeated]
               (is (= (::client/quiesce-result @client/!state) repeated))
               (is (= 1 (count (filter #{:release} @effects)))
                   "a completed repeat reuses typed data without effects")))
            (.catch
             (fn [error]
               (is false (str "planned quiesce proof threw " error))))
            (.finally
             (fn []
               (set! agent-loop/uninstall-ticker! original-ticker)
               (set! agent-loop/uninstall-all-wake-triggers! original-wakes)
               (set! agent-run/quiescence-work original-work)
               (set! agent-run/close-run! original-close)
               (set! agent-runtime/unhost-all! original-unhost)
               (set! replica/detach! original-replica)
               (set! admission/detach! original-admission)
               (set! db/entity original-entity)
               (set! db/head-coordinate original-head)
               (set! db/release-connection! original-release)
               (if previous-generation
                 (aset (.-env js/process) "SEON_PROCESS_GENERATION"
                       previous-generation)
                 (js-delete (.-env js/process) "SEON_PROCESS_GENERATION"))
               (set! db/*conn* previous-connection)
               (reset! client/!state previous-state)
               (reset! (deref #'admission/!state) previous-admission)
               (done))))))))

(deftest failed-planned-release-retains-authority-for-the-same-retry
  (async done
    (let [previous-state @client/!state
          previous-admission @(deref #'admission/!state)
          previous-connection db/*conn*
          connection (atom {})
          release-calls (atom 0)
          wake-calls (atom 0)
          coordinate {:seon.db.coordinate/database-id (random-uuid)
                      :seon.db.coordinate/branch :db
                      :seon.db.coordinate/commit-id (random-uuid)
                      :seon.db.coordinate/t 51}
          original-ticker agent-loop/uninstall-ticker!
          original-wakes agent-loop/uninstall-all-wake-triggers!
          original-unhost agent-runtime/unhost-all!
          original-replica replica/detach!
          original-admission admission/detach!
          original-head db/head-coordinate
          original-release db/release-connection!]
      (set! db/*conn* connection)
      (reset! client/!state
              (assoc previous-state
                     ::client/launch-capability non-autonomous-capability
                     ::client/runtime-phase :seon.client.runtime/running))
      (reset! (deref #'admission/!state)
              {::admission/status :available ::admission/generation 51})
      (set! agent-loop/uninstall-ticker! (constantly nil))
      (set! agent-loop/uninstall-all-wake-triggers!
            (fn []
              {::agent-loop/uninstalled-ids
               (if (= 1 (swap! wake-calls inc)) ["root"] [])}))
      (set! agent-runtime/unhost-all!
            (fn [] {::agent-runtime/unhosted-ids []}))
      (set! replica/detach! (constantly true))
      (set! admission/detach!
            (fn [] {::admission/detached? true}))
      (set! db/head-coordinate
            (fn
              ([] coordinate)
              ([_database] coordinate)))
      (set! db/release-connection!
            (fn [_]
              (if (= 1 (swap! release-calls inc))
                (js/Promise.reject (js/Error. "injected release failure"))
                (promise-value {::db/released? true}))))
      (-> (client/quiesce-runtime!)
          (.then
           (fn [failed]
             (is (false? (::client/quiesced? failed)))
             (is (= :seon.client.runtime/cleanup-required
                    ((deref #'client/runtime-phase))))
             (is (identical? connection db/*conn*))
             (is (= non-autonomous-capability
                    (::client/launch-capability @client/!state)))
             (client/quiesce-runtime!)))
          (.then
           (fn [retried]
             (is (true? (::client/quiesced? retried)))
             (is (= 2 @release-calls))
             (is (= ["root"] (::agent-runtime/unhosted-ids retried))
                 "retry preserves the first attempt's completed inverse data")
             (is (nil? db/*conn*))))
          (.catch
           (fn [error]
             (is false (str "planned release retry proof threw " error))))
          (.finally
           (fn []
             (set! agent-loop/uninstall-ticker! original-ticker)
             (set! agent-loop/uninstall-all-wake-triggers! original-wakes)
             (set! agent-runtime/unhost-all! original-unhost)
             (set! replica/detach! original-replica)
             (set! admission/detach! original-admission)
             (set! db/head-coordinate original-head)
             (set! db/release-connection! original-release)
             (set! db/*conn* previous-connection)
             (reset! client/!state previous-state)
             (reset! (deref #'admission/!state) previous-admission)
             (done)))))))

(deftest planned-quiesce-deadline-fails-closed-with-retained-authority
  (async done
    (let [previous-state @client/!state
          previous-admission @(deref #'admission/!state)
          previous-connection db/*conn*
          connection (atom {})
          original-timeout config/turn-timeout-ms
          original-ticker agent-loop/uninstall-ticker!
          original-wakes agent-loop/uninstall-all-wake-triggers!
          original-work agent-run/quiescence-work]
      (set! db/*conn* connection)
      (reset! client/!state
              (assoc previous-state
                     ::client/launch-capability client/default-launch-capability
                     ::client/runtime-phase :seon.client.runtime/running))
      (reset! (deref #'admission/!state)
              {::admission/status :available ::admission/generation 61})
      (set! config/turn-timeout-ms (constantly 0))
      (set! agent-loop/uninstall-ticker! (constantly nil))
      (set! agent-loop/uninstall-all-wake-triggers!
            (fn [] {::agent-loop/uninstalled-ids ["root"]}))
      (set! agent-run/quiescence-work
            (fn [_]
              {::agent-run/current-runs
               [{:seon.agent/id "root" :seon.agent.run/id "run-blocked"}]
               ::agent-run/running-turns
               [{:seon.agent.run/id "run-blocked"
                 :seon.agent.turn/id "turn-blocked"}]}))
      (-> (client/quiesce-runtime!)
          (.then
           (fn [result]
             (is (false? (::client/quiesced? result)))
             (is (re-find #"timed out" (::client/quiesce-error result)))
             (is (= :seon.client.runtime/cleanup-required
                    ((deref #'client/runtime-phase))))
             (is (admission/quiescing?)
                 "deadline failure leaves ordinary admission closed")
             (is (identical? connection db/*conn*))
             (is (= client/default-launch-capability
                    (::client/launch-capability @client/!state)))))
          (.catch
           (fn [error]
             (is false (str "planned quiesce deadline proof threw " error))))
          (.finally
           (fn []
             (set! config/turn-timeout-ms original-timeout)
             (set! agent-loop/uninstall-ticker! original-ticker)
             (set! agent-loop/uninstall-all-wake-triggers! original-wakes)
             (set! agent-run/quiescence-work original-work)
             (set! db/*conn* previous-connection)
             (reset! client/!state previous-state)
             (reset! (deref #'admission/!state) previous-admission)
             (done)))))))

(deftest failed-web-close-retains-state-and-retries-the-same-inverse
  (async done
    (let [previous-state @client/!state
          previous-admission @(deref #'admission/!state)
          previous-connection db/*conn*
          connection (atom {})
          web-calls (atom 0)
          effects (atom [])
          original-attached? db/attached?
          original-web-stop web.serve/stop!
          original-ticker-stop agent-loop/uninstall-ticker!
          original-unhost agent-runtime/unhost-all!
          original-replica-detach replica/detach!
          original-admission-detach admission/detach!
          original-release db/release-connection!]
      (set! db/*conn* connection)
      (reset! client/!state
              (assoc previous-state
                     ::client/launch-capability non-autonomous-capability
                     ::client/runtime-phase :seon.client.runtime/running))
      (set! db/attached? (fn [] (identical? db/*conn* connection)))
      (set! web.serve/stop!
            (fn []
              (swap! effects conj :web)
              (if (= 1 (swap! web-calls inc))
                (js/Promise.reject (js/Error. "close failed"))
                (promise-value nil))))
      (set! agent-loop/uninstall-ticker!
            (fn [] (swap! effects conj :ticker)))
      (set! agent-runtime/unhost-all!
            (fn []
              (swap! effects conj :hosts)
              {::agent-runtime/unhosted-ids []}))
      (set! replica/detach! (fn [] (swap! effects conj :replica)))
      (set! admission/detach!
            (fn []
              (swap! effects conj :admission)
              {::admission/detached? true
               ::admission/instrumentation {}}))
      (set! db/release-connection!
            (fn [_]
              (swap! effects conj :release)
              (promise-value {::db/released? true})))
      (-> (client/stop-runtime!)
          (.then
           (fn [failed]
             (is (false? (::client/stopped? failed)))
             (is (= [:web] @effects))
             (is (identical? connection db/*conn*))
             (is (= non-autonomous-capability (client/launch-capability)))
             (is (= :seon.client.runtime/cleanup-required
                    ((deref #'client/runtime-phase))))
             (client/stop-runtime!)))
          (.then
           (fn [retried]
             (is (true? (::client/stopped? retried)))
             (is (= [:web :web :ticker :hosts :replica :admission :release]
                    @effects))
             (is (nil? db/*conn*))))
          (.catch
           (fn [error]
             (is false (str "retryable stop proof threw " error))))
          (.finally
           (fn []
             (set! db/attached? original-attached?)
             (set! web.serve/stop! original-web-stop)
             (set! agent-loop/uninstall-ticker! original-ticker-stop)
             (set! agent-runtime/unhost-all! original-unhost)
             (set! replica/detach! original-replica-detach)
             (set! admission/detach! original-admission-detach)
             (set! db/release-connection! original-release)
             (set! db/*conn* previous-connection)
             (reset! client/!state previous-state)
             (reset! (deref #'admission/!state) previous-admission)
             (done)))))))

(defn- run-stop-step-retry-proof!
  [failed-stage]
  (let [previous-state @client/!state
        previous-admission @(deref #'admission/!state)
        previous-connection db/*conn*
        connection (atom {})
        attempts (atom {})
        effects (atom [])
        original-attached? db/attached?
        original-web-stop web.serve/stop!
        original-ticker-stop agent-loop/uninstall-ticker!
        original-unhost agent-runtime/unhost-all!
        original-replica-detach replica/detach!
        original-admission-detach admission/detach!
        original-release db/release-connection!
        effect!
        (fn [stage]
          (swap! effects conj stage)
          (let [attempt (get (swap! attempts update stage (fnil inc 0)) stage)]
            (when (and (= failed-stage stage) (= 1 attempt))
              (throw (js/Error. (str "injected " (name stage) " failure"))))))]
    (set! db/*conn* connection)
    (reset! client/!state
            (assoc previous-state
                   ::client/launch-capability non-autonomous-capability
                   ::client/runtime-phase :seon.client.runtime/running))
    (set! db/attached? (fn [] (identical? db/*conn* connection)))
    (set! web.serve/stop! (fn [] (effect! :web) (promise-value nil)))
    (set! agent-loop/uninstall-ticker! (fn [] (effect! :ticker)))
    (set! agent-runtime/unhost-all!
          (fn []
            (effect! :hosts)
            {::agent-runtime/unhosted-ids []}))
    (set! replica/detach! (fn [] (effect! :replica)))
    (set! admission/detach!
          (fn []
            (swap! effects conj :admission)
            (let [attempt
                  (get (swap! attempts update :admission (fnil inc 0))
                       :admission)]
              (if (and (= failed-stage :admission) (= 1 attempt))
                {::admission/detached? false
                 :seon/error {:seon.error/message "injected admission failure"}}
                {::admission/detached? true
                 ::admission/instrumentation {}}))))
    (set! db/release-connection!
          (fn [_]
            (try
              (effect! :release)
              (promise-value {::db/released? true})
              (catch :default error
                (js/Promise.reject error)))))
    (-> (client/stop-runtime!)
        (.then
         (fn [failed]
           (is (false? (::client/stopped? failed))
               (str (name failed-stage) " failure is not successful stop"))
           (is (identical? connection db/*conn*))
           (is (= non-autonomous-capability (client/launch-capability)))
           (is (= :seon.client.runtime/cleanup-required
                  ((deref #'client/runtime-phase))))
           (client/stop-runtime!)))
        (.then
         (fn [retried]
           (is (true? (::client/stopped? retried))
               (str (name failed-stage) " failure is retryable"))
           (is (= 2 (get @attempts failed-stage)))
           (is (= (if (= failed-stage :release) 2 1)
                  (get @attempts :release))
               "release runs once unless release itself is the injected fault")
           (is (nil? db/*conn*))))
        (.finally
         (fn []
           (set! db/attached? original-attached?)
           (set! web.serve/stop! original-web-stop)
           (set! agent-loop/uninstall-ticker! original-ticker-stop)
           (set! agent-runtime/unhost-all! original-unhost)
           (set! replica/detach! original-replica-detach)
           (set! admission/detach! original-admission-detach)
           (set! db/release-connection! original-release)
           (set! db/*conn* previous-connection)
           (reset! client/!state previous-state)
           (reset! (deref #'admission/!state) previous-admission))))))

(deftest every-destructive-stop-step-retains-authority-and-retries
  (async done
    (-> (reduce
         (fn [proof stage]
           (.then proof (fn [] (run-stop-step-retry-proof! stage))))
         (promise-value nil)
         [:ticker :hosts :replica :admission :release])
        (.then (fn [_] (done)))
        (.catch
         (fn [error]
           (is false (str "stop retry matrix threw " error))
           (done))))))

(deftest stop-detaches-the-actual-admission-projection-before-release
  (async done
    (let [previous-state @client/!state
          previous-connection db/*conn*
          previous-admission @(deref #'admission/!state)
          connection (atom {})
          old-projection {:seon.schema.projection/fingerprint 41}
          empty-projection {:seon.schema.projection/fingerprint 0}
          effects (atom [])
          original-attached? db/attached?
          original-web-stop web.serve/stop!
          original-ticker-stop agent-loop/uninstall-ticker!
          original-unhost agent-runtime/unhost-all!
          original-replica-detach replica/detach!
          original-current-projection schema/current-projection
          original-build-projection schema/build-projection
          original-reconcile instrument/reconcile-projection!
          original-activate schema/activate-projection!
          original-release db/release-connection!]
      (set! db/*conn* connection)
      (reset! client/!state
              (assoc previous-state
                     ::client/launch-capability non-autonomous-capability
                     ::client/runtime-phase :seon.client.runtime/running))
      (reset! (deref #'admission/!state)
              {::admission/status :available
               ::admission/generation 41})
      (set! db/attached? (fn [] (identical? db/*conn* connection)))
      (set! web.serve/stop!
            (fn [] (swap! effects conj :web) (promise-value nil)))
      (set! agent-loop/uninstall-ticker!
            (fn [] (swap! effects conj :ticker)))
      (set! agent-runtime/unhost-all!
            (fn []
              (swap! effects conj :hosts)
              {::agent-runtime/unhosted-ids []}))
      (set! replica/detach! (fn [] (swap! effects conj :replica)))
      (set! schema/current-projection (constantly old-projection))
      (set! schema/build-projection
            (fn
              ([registry]
               (is (= {} registry))
               empty-projection)
              ([registry _function-contracts]
               (is (= {} registry))
               empty-projection)))
      (set! instrument/reconcile-projection!
            (fn [{::instrument/keys [old-projection new-projection]}]
              (swap! effects conj [:reconcile old-projection new-projection])
              {::instrument/ok? true}))
      (set! schema/activate-projection!
            (fn [projection]
              (swap! effects conj [:activate projection])
              projection))
      (set! db/release-connection!
            (fn [_]
              (swap! effects conj :release)
              (promise-value {::db/released? true})))
      (-> (client/stop-runtime!)
          (.then
           (fn [result]
             (is (true? (::client/stopped? result)))
             (is (= [:web
                     :ticker
                     :hosts
                     :replica
                     [:reconcile old-projection empty-projection]
                     [:activate empty-projection]
                     :release]
                    @effects))
             (is (= :starting
                    (::admission/status (admission/state))))
             (is (nil? db/*conn*))))
          (.catch
           (fn [error]
             (is false (str "actual admission detach proof threw " error))))
          (.finally
           (fn []
             (set! db/attached? original-attached?)
             (set! web.serve/stop! original-web-stop)
             (set! agent-loop/uninstall-ticker! original-ticker-stop)
             (set! agent-runtime/unhost-all! original-unhost)
             (set! replica/detach! original-replica-detach)
             (set! schema/current-projection original-current-projection)
             (set! schema/build-projection original-build-projection)
             (set! instrument/reconcile-projection! original-reconcile)
             (set! schema/activate-projection! original-activate)
             (set! db/release-connection! original-release)
             (set! db/*conn* previous-connection)
             (reset! client/!state previous-state)
             (reset! (deref #'admission/!state) previous-admission)
             (done)))))))

(deftest running-without-a-connection-cannot-report-stopped
  (async done
    (let [previous-state @client/!state
          previous-connection db/*conn*
          effects (atom [])
          original-attached? db/attached?
          original-web-stop web.serve/stop!]
      (set! db/*conn* nil)
      (reset! client/!state
              (assoc previous-state
                     ::client/launch-capability non-autonomous-capability
                     ::client/runtime-phase :seon.client.runtime/running))
      (set! db/attached? (constantly false))
      (set! web.serve/stop! (fn [] (swap! effects conj :web)))
      (-> (client/stop-runtime!)
          (.then
           (fn [result]
             (is (false? (::client/stopped? result)))
             (is (empty? @effects))
             (is (= :seon.client.runtime/cleanup-required
                    ((deref #'client/runtime-phase))))
             (is (true? (::client/cleanup-requires-connection?
                         @client/!state)))))
          (.finally
           (fn []
             (set! db/attached? original-attached?)
             (set! web.serve/stop! original-web-stop)
             (set! db/*conn* previous-connection)
             (reset! client/!state previous-state)
             (done)))))))

(deftest release-connection-is-indexed-documentation-not-agent-toolkit
  (is (not (true? (:seon.fn/agent-facing?
                    (meta #'db/release-connection!))))
      "only an explicit positive metadata fact enters the agent toolkit")
  (let [row ((deref #'client/var->fn-row)
             #'db/release-connection!
             (js/Date.))]
    (is (map? row))
    (is (not (contains? row :seon.fn/agent-facing?))
        "whole-surface indexing keeps the lifecycle API non-agent-facing")))

(defn- run-real-non-autonomous-replay-proof!
  "Return one ordinary Promise chain, outside cljs.test's async CPS form."
  []
  (let [!conn (atom nil)]
    (-> (apply repl/ensure-bootstrap! [])
        (.then
         (fn [compile-state]
           (-> (apply client/open-agent-conn! [])
               (.then
                (fn [conn]
                  (reset! !conn conn)
                  (is (= (pr-str
                           (schema/schema-definition
                            :seon.db.coordinate/coordinate))
                         (db/query
                          {::db/query
                           '[:find ?form .
                             :in $ ?key
                             :where
                             [?schema :seon.schema/key ?key]
                             [?schema :seon.schema/form ?form]]
                           ::db/args
                           [:seon.db.coordinate/coordinate]
                           ::db/conn conn}))
                      "isolated diagnostic boot persists canonical schema facts")
                  (-> (apply db/transact!
                             [{::db/conn conn
                               ::db/tx-data (client/index-schemas)}])
                      (.then
                       (fn [_]
                         (apply
                          db/transact!
                          [{::db/conn conn
                            ::db/tx-data
                            [{:seon.ns/name :my.runtime.replay-broken
                              :seon.ns/source "(ns my.runtime.replay-broken)"}
                             {:seon.fn/sym "my.runtime.replay-broken/nope"
                              :seon.fn/ns
                              [:seon.ns/name :my.runtime.replay-broken]
                              :seon.fn/source "(defn nope [)"
                              :seon.fn/arglists "([])"
                              :seon.fn/doc ""
                              :seon.fn/private? false}
                             {:seon.ns/name :my.runtime.replay-good
                              :seon.ns/source "(ns my.runtime.replay-good)"}
                             {:seon.fn/sym "my.runtime.replay-good/answer"
                              :seon.fn/ns
                              [:seon.ns/name :my.runtime.replay-good]
                              :seon.fn/source "(defn answer [] 42)"
                              :seon.fn/arglists "([])"
                              :seon.fn/doc ""
                              :seon.fn/private? false}]}])))
                      (.then
                       (fn [_]
                         (let [before (db/basis-t @conn)]
                           (-> (apply
                                client/replay-program-graph!
                                [{::client/conn conn
                                  ::client/compile-state compile-state
                                  ::client/agent-id "root"
                                  ::client/record-failures? false}])
                               (.then
                                (fn [stats]
                                  (is (= 2 (::client/replay-n-total stats)))
                                  (is (= 1 (::client/replay-n-fail stats)))
                                  (is (= before (db/basis-t @conn))
                                      "real replay failure emits no datom")
                                  (apply
                                   seval/eval
                                   [compile-state
                                    "(my.runtime.replay-good/answer)"
                                    {:seon.eval/starting-ns 'cljs.user
                                     :seon.eval/analyze-deps? false}])))
                               (.then
                                (fn [result]
                                  (is (:seon.eval/ok? result))
                                  (is (= 42 (:seon.eval/value result))
                                      "the later real namespace still loads")))))))))))))
        (.finally
         (fn []
           (if-let [conn @!conn]
             (apply db/release-connection! [{::db/conn conn}])
             (promise-value nil)))))))

(deftest real-non-autonomous-replay-continues-without-a-database-write
  (async done
    (-> (run-real-non-autonomous-replay-proof!)
        (.then (fn [_] (done)))
        (.catch
         (fn [error]
           (is false (str "real non-autonomous replay threw " error))
           (done))))))
