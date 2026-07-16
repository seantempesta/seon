(ns seon.db.replica-test
  "Unit tests for `seon.db.replica/ping!`'s bounded retry (unit 5 —
   the `bin/seon start all` race): the pod's boot ping retries the
   wire rpc up to 5 times (~10s) before the existing fail-loud throw.
   Boot stays fail-loud, just not fail-instant.

   `seon.db.transport.uds/rpc` is stubbed via root `set!` (same rationale
   as `seon.agent.message-test/with-conn`: dynamic `binding` is popped at the
   first microtask boundary inside `^:async` bodies; the root swap is
   visible across microtasks, tests run serially, restore in
   `.finally`). No real socket is touched.

   Run interactively via MCP eval:
     (require 'seon.db.replica-test :reload)
     (cljs.test/run-tests 'seon.db.replica-test)"
  (:require
   [cljs.core.async :refer [take! poll!]]
   [cljs.test :refer [deftest is async use-fixtures]]
   [datahike.api :as d]
   [datahike.writer :as writer]
   [seon.db.coordinate :as coordinate]
   [seon.db.protocol :as protocol]
   [seon.db.transport.uds :as uds]
   [seon.launch :as launch]
   [seon.db.replica :as replica]))

(defn- with-rpc-stub
  "Run `body` with the map-in UDS request function replaced by `stub`."
  [stub body]
  (let [orig uds/rpc
        wrapped
        (fn [{::uds/keys [socket-path message timeout-ms]}]
          (stub socket-path message {::uds/timeout-ms timeout-ms}))]
    (set! uds/rpc wrapped)
    (-> (js/Promise.resolve (body))
        (.finally (fn [] (set! uds/rpc orig))))))

(declare with-as-of-stub)

(defn- with-feed-stubs
  "Run an async body with the pub connector and paginated replay stubbed."
  [connect-stub replay-stub body]
  (let [original-connect uds/connect-publisher!
        original-rpc uds/rpc
        wrapped-connect
        (fn [{::uds/keys [socket-path on-message on-close]}]
          (connect-stub socket-path {:on-event on-message
                                     :on-close on-close}))
        wrapped-rpc
        (fn [{::uds/keys [socket-path message] :as request}]
          (if (= protocol/replay-transactions-operation
                 (::protocol/operation message))
            (replay-stub
             socket-path
             (cond-> {:since-t (get-in message
                                       [::protocol/since-coordinate
                                        ::coordinate/t])
                      :db-name (::protocol/database-name message)}
               (some? (::protocol/through-coordinate message))
               (assoc :through-t
                      (get-in message
                              [::protocol/through-coordinate
                               ::coordinate/t]))))
            (original-rpc request)))
        restore! (fn []
                   (set! uds/connect-publisher! original-connect)
                   (set! uds/rpc original-rpc))]
    (set! uds/connect-publisher! wrapped-connect)
    (set! uds/rpc wrapped-rpc)
    (try
      (-> (with-as-of-stub body)
          (.finally restore!))
      (catch :default error
        (restore!)
        (js/Promise.reject error)))))

(defn- channel->promise
  "Resolve a Promise with the one value delivered on a promise-chan."
  [channel]
  (js/Promise.
   (fn [deliver _reject]
     (take! channel deliver))))

(def ^:private fake-database-id
  #uuid "9dcfa740-5f7f-4ff5-ac08-a9c8b605a8aa")

(def ^:private fake-commit-id
  #uuid "cfd65c4c-c4f5-4f2b-afef-117b9fd6779a")

(def ^:private clj-printed-branch-descriptor
  "#:seon.launch{:runtime {:seon.launch/runtime-cluster \"trial\", :seon.launch/artifact-flavor :seon.dev.artifact.flavor/default, :seon.launch/client-build-id \"client\", :seon.client/launch-capability #:seon.client{:autonomous? false}}, :database {:seon.db.protocol/database-name \"trial-route\", :seon.db.coordinate/attachment #:seon.db.coordinate{:database-id #uuid \"9dcfa740-5f7f-4ff5-ac08-a9c8b605a8aa\", :branch :trial}, :seon.db.coordinate/coordinate #:seon.db.coordinate{:database-id #uuid \"9dcfa740-5f7f-4ff5-ac08-a9c8b605a8aa\", :branch :trial, :commit-id #uuid \"a2bd215f-7ec6-47dc-a627-f8e4948df581\", :t 42}, :seon.db.protocol/backend :file, :seon.db.protocol/database-path \"data/clusters/default/db\"}, :writer-owner #:seon.launch{:writer-cluster \"default\", :writer-process-dir \"tmp/source-process\", :request-socket-path \"tmp/req.sock\", :publish-socket-path \"tmp/pub.sock\", :writer-repl-port-file \"tmp/writer.port\"}, :process #:seon.launch{:process-dir \"tmp/trial\", :log-dir \"logs/trial\", :http-port 0, :http-port-file \"tmp/trial/http.port\"}, :blob-storage-view #:my.blob{:writable-dir \"data/branches/trial/blobs\", :read-only-dirs [\"data/clusters/default/blobs\"]}}")

(deftest clj-printed-descriptor-round-trips-through-the-cljs-reader
  (let [descriptor
        (replica/decode-launch-descriptor clj-printed-branch-descriptor)]
    (is (= fake-database-id
           (get-in descriptor
                   [::launch/database ::coordinate/coordinate
                    ::coordinate/database-id])))
    (is (= #uuid "a2bd215f-7ec6-47dc-a627-f8e4948df581"
           (get-in descriptor
                   [::launch/database ::coordinate/coordinate
                    ::coordinate/commit-id])))
    (is (= :trial
           (get-in descriptor
                   [::launch/database ::coordinate/coordinate
                    ::coordinate/branch])))
    (is (= 42
           (get-in descriptor
                   [::launch/database ::coordinate/coordinate
                    ::coordinate/t])))
    (is (= "tmp/source-process"
           (get-in descriptor
                   [::launch/writer-owner ::launch/writer-process-dir])))
    (is (false?
         (get-in descriptor
                 [::launch/runtime :seon.client/launch-capability
                  :seon.client/autonomous?])))))

(deftest invalid-published-descriptor-fails-before-consumption
  (doseq [encoded ["{}" "{:unclosed"]]
    (try
      (replica/decode-launch-descriptor encoded)
      (is false (str "accepted invalid descriptor " encoded))
      (catch :default error
        (is (= :core-bug (:seon.error/kind (ex-data error))))))))

(defn- point
  ([t] (point t :db))
  ([t branch]
   {::coordinate/database-id fake-database-id
    ::coordinate/branch branch
    ::coordinate/commit-id fake-commit-id
    ::coordinate/t t}))

(let [resolve-coordinate coordinate/resolved]
  (use-fixtures
   :once
   {:before
    (fn []
      (set! coordinate/resolved
            (fn [db]
              (if (map? db)
                {::coordinate/database-id (get-in db [:config :store :id])
                 ::coordinate/branch (get-in db [:config :branch])
                 ::coordinate/commit-id fake-commit-id
                 ::coordinate/t (:max-tx db)}
                (resolve-coordinate db)))))
    :after (fn [] (set! coordinate/resolved resolve-coordinate))}))

(def ^:private expected-transaction-attempts 3)

(deftest database-config-uses-the-writer-owned-attachment
  (let [point {::coordinate/database-id fake-database-id
               ::coordinate/branch :experiment
               ::coordinate/commit-id fake-commit-id
               ::coordinate/t 17}
        descriptor
        (launch/with-coordinate
         {::launch/descriptor replica/default-launch-descriptor
          ::coordinate/coordinate point})
        config (replica/database-config
                {::launch/descriptor descriptor})]
    (is (= fake-database-id (get-in config [:store :id])))
    (is (= :experiment (:branch config)))
    (is (= replica/database-name (get-in config [:writer :database-name])))
    (is (= replica/default-request-socket-path
           (get-in config [:writer :socket-path])))))

(defn- branch-launch-descriptor
  []
  (let [source-attachment {::coordinate/database-id fake-database-id
                           ::coordinate/branch :db}
        source
        (assoc-in replica/default-launch-descriptor
                  [::launch/database ::coordinate/attachment]
                  source-attachment)]
    (launch/branch-descriptor
     {::launch/source-descriptor source
      ::launch/runtime-cluster "experiment"
      ::launch/target-database-name "experiment-route"
      ::launch/target-coordinate
      (merge source-attachment
             {::coordinate/branch :experiment
              ::coordinate/commit-id fake-commit-id
              ::coordinate/t 17})
      ::launch/process-dir "tmp/experiment"
      ::launch/log-dir "logs/experiment"
      ::launch/http-port 0
      ::launch/http-port-file "tmp/experiment/http.port"
      ::launch/writable-blob-dir "data/branches/experiment/blobs"})))

(defn- attachment-descriptor
  ([point route]
   (attachment-descriptor point route "req.sock" "pub.sock"))
  ([point route request-socket-path publish-socket-path]
   (-> (launch/with-coordinate
        {::launch/descriptor replica/default-launch-descriptor
         ::coordinate/coordinate point})
       (assoc-in [::launch/database ::protocol/database-name] route)
       (assoc-in [::launch/writer-owner ::launch/request-socket-path]
                 request-socket-path)
       (assoc-in [::launch/writer-owner ::launch/publish-socket-path]
                 publish-socket-path))))

(defn- open-response
  [descriptor request-id]
  (let [database-selection (::launch/database descriptor)
        attachment (::coordinate/attachment database-selection)]
    {::protocol/success? true
     ::protocol/request-id request-id
     ::protocol/database-name (::protocol/database-name database-selection)
     ::coordinate/coordinate
     (merge attachment
            {::coordinate/commit-id fake-commit-id
             ::coordinate/t 17})
     ::protocol/backend (::protocol/backend database-selection)
     ::protocol/database-path (::protocol/database-path database-selection)}))

(deftest ensure-response-validation-rejects-every-crossed-selection
  (let [descriptor (branch-launch-descriptor)
        response (open-response descriptor "ensure/validation")
        validate #(replica/validate-ensure-response
                   {::launch/descriptor descriptor ::replica/response %})]
    (is (= response (validate response)))
    (let [advanced
          (update response ::coordinate/coordinate
                  assoc
                  ::coordinate/commit-id
                  #uuid "ce476f3c-077e-48ba-b5f8-a4acfe75a26f"
                  ::coordinate/t 23)]
      (is (= advanced (validate advanced))
          "reopen returns the current head, which may advance on the same attachment"))
    (doseq [crossed
            [(assoc response ::protocol/database-name "other-route")
             (assoc-in response
                       [::coordinate/coordinate ::coordinate/branch]
                       :other-branch)
             (assoc response ::protocol/backend :memory)
             (assoc response ::protocol/database-path "data/other/db")]]
      (is (thrown? js/Error (validate crossed))))))

(deftest ensure-database-routes-the-exact-branch-selection
  (async done
    (let [descriptor (branch-launch-descriptor)
          writer-owner (::launch/writer-owner descriptor)]
      (-> (with-rpc-stub
           (fn [socket-path request _]
             (is (= (::launch/request-socket-path writer-owner) socket-path))
             (is (= (protocol/ensure-database-request
                     (assoc (::launch/database descriptor)
                            ::protocol/request-id (::protocol/request-id request)))
                    request))
             (js/Promise.resolve
              (open-response descriptor (::protocol/request-id request))))
           #(replica/ensure-database! {::launch/descriptor descriptor}))
          (.then (fn [response]
                   (is (string? (::protocol/request-id response)))
                   (is (= (dissoc (open-response descriptor "ignored")
                                  ::protocol/request-id)
                          (dissoc response ::protocol/request-id)))))
          (.catch #(is false (str "exact branch ensure threw: " %)))
          (.finally done)))))

(defn- fake-db
  ([basis-t] (fake-db basis-t :db replica/database-name))
  ([basis-t branch] (fake-db basis-t branch replica/database-name))
  ([basis-t branch route]
   {:max-tx basis-t
    :config {:store {:id fake-database-id}
             :branch branch
             :writer {:backend :seon.db.writer/remote
                      :database-name route}}}))

(deftest connection-coordinate-joins-the-descriptor-feed-route
  (let [db (fake-db 17 :experiment nil)
        descriptor (attachment-descriptor
                    (point 17 :experiment)
                    "experiment-route")
        coordinate (#'replica/connection-coordinate db descriptor)]
    (is (= "experiment-route" (::replica/database-name coordinate)))
    (is (= :seon.db.writer/remote (::replica/writer-backend coordinate)))
    (is (= :experiment
           (get-in coordinate
                   [::coordinate/attachment ::coordinate/branch])))))

(deftest connected-coordinate-retains-route-after-non-streaming-deref
  (async done
    (let [database-id (random-uuid)
          base-config {:store {:backend :memory :id database-id}
                       :keep-history? true
                       :schema-flexibility :write}
          route "connected-experiment-route"
          remote-config
          (assoc base-config
                 :writer {:backend :seon.db.writer/remote
                          :database-name route
                          :socket-path "unused.sock"})]
      (-> (d/create-database base-config)
          (.then (fn [_]
                   (d/connect remote-config {:sync? false})))
          (.then
           (fn [conn]
             (let [db @conn
                   point (coordinate/resolved db)
                   descriptor (attachment-descriptor point route)
                   connection-point
                   (#'replica/connection-coordinate db descriptor)]
               (is (= :self (get-in db [:config :writer :backend]))
                   "non-streaming deref exposes the durable self-writer config")
               (is (nil? (get-in db [:config :writer :database-name]))
                   "the logical route is runtime launch data, not a stored DB fact")
               (is (= route (::replica/database-name connection-point)))
               (is (= (coordinate/attachment point)
                      (::coordinate/attachment connection-point)))
               (d/release conn))))
          (.catch (fn [error]
                    (is false (str "real connected route probe threw: " error))))
          (.finally done)))))

(defn- fake-conn
  "Minimal conn surface used by the wire writer and native listeners."
  ([basis-t]
   (fake-conn basis-t (atom {})))
  ([basis-t listeners]
   (reify
     IDeref
     (-deref [_] (fake-db basis-t))
     IMeta
     (-meta [_] {:listeners listeners}))))

(defn- fake-changing-conn
  "Fake connection whose branch-local head can advance during a test."
  [database listeners]
  (reify
    IDeref
    (-deref [_] @database)
    IMeta
    (-meta [_] {:listeners listeners})))

(defn- dispatch-transaction
  "Drive the real SeonWireWriter branch without Datahike's outer writer loop."
  [conn arg-map]
  (channel->promise
   (writer/-dispatch!
    (replica/->RemoteWriter
     "test-route" "stub.sock" conn
     (atom {:seon.db.replica/writer-open? true
            :seon.db.replica/writer-pending #{}}))
    {:op 'transact! :args [arg-map]})))

(defn- test-writer
  [conn]
  (replica/->RemoteWriter
   "test-route" "stub.sock" conn
   (atom {:seon.db.replica/writer-open? true
          :seon.db.replica/writer-pending #{}})))

(defn- success-response
  [basis-t]
  {::protocol/success? true
   ::protocol/coordinate (point basis-t)
   ::protocol/temporary-ids {}
   ::protocol/transaction-data []
   ::protocol/datoms-added 0
   ::protocol/datoms-retracted 0})

(defn- with-wire-state
  "Install hermetic attachment state for an async body, then restore."
  [adapter-state body]
  (let [adapter           @#'replica/!attachment
        saved-adapter     @adapter
        restore!          (fn []
                            (replica/detach!)
                            (reset! adapter saved-adapter))]
    (reset! adapter adapter-state)
    (try
      (-> (js/Promise.resolve (body))
          (.finally restore!))
      (catch :default error
        (restore!)
        (js/Promise.reject error)))))

(defn- stopped-state
  []
  {::replica/phase ::replica/stopped
   ::replica/generation 0
   ::replica/correlations {}})

(defn- attached-state
  ([conn basis-t] (attached-state conn basis-t ::replica/live))
  ([conn basis-t phase]
   (let [db @conn
         descriptor (attachment-descriptor (coordinate/resolved db)
                                           replica/database-name)
         coordinate (#'replica/connection-coordinate db descriptor)]
     {::replica/phase phase
      ::replica/generation 1
      ::replica/conn conn
      ::replica/database-coordinate coordinate
      ::replica/last-applied-coordinate
      (#'replica/progress-coordinate coordinate (point basis-t))
      ::replica/request-socket-path "req.sock"
      ::replica/publish-socket-path "pub.sock"
      ::replica/own-skips 0
      ::replica/correlations {}})))

(defn- adapter-state
  []
  @(deref #'replica/!attachment))

(defn- adapter-generation
  []
  (::replica/generation (adapter-state)))

(defn- adapter-basis-t
  []
  (get-in (adapter-state)
          [::replica/last-applied-coordinate ::coordinate/t]))

(defn- correlations
  []
  (::replica/correlations (adapter-state)))

(defn- connect-feed!
  [conn sock-path pub-sock-path on-drop]
  (let [state (adapter-state)]
    (#'replica/connect-feed!
     (::replica/generation state)
     conn
     (::replica/database-coordinate state)
     sock-path
     pub-sock-path
     on-drop)))

(defn- with-as-of-stub
  [body]
  (let [original d/as-of]
    (set! d/as-of (fn [db basis-t] (assoc db :max-tx basis-t)))
    (try
      (-> (js/Promise.resolve (body))
          (.finally #(set! d/as-of original)))
      (catch :default error
        (set! d/as-of original)
        (js/Promise.reject error)))))

(defn- after-macrotask
  "Wait long enough for deferred native listener callbacks to run."
  []
  (js/Promise.
   (fn [deliver _reject]
     (js/setTimeout deliver 25))))

(defn- replay-event
  ([db-name basis-t basis-t-before]
   (replay-event db-name basis-t basis-t-before :db))
  ([db-name basis-t basis-t-before branch]
   {::protocol/event protocol/transaction-event
    ::protocol/database-name db-name
    ::protocol/coordinate (point basis-t branch)
    ::protocol/previous-coordinate (point basis-t-before branch)
    ::protocol/transaction-data
    [[basis-t :seon.db.replica-test/value basis-t basis-t true]]}))

(defn- replay-page
  ([db-name since-t through-t continuation-t done? events]
   (replay-page db-name since-t through-t continuation-t done? events :db))
  ([db-name since-t through-t continuation-t done? events branch]
   {::protocol/success? true
    ::protocol/database-name db-name
    ::protocol/since-coordinate (point since-t branch)
    ::protocol/through-coordinate (point through-t branch)
    ::protocol/continuation-coordinate (point continuation-t branch)
    ::protocol/complete? done?
    ::protocol/events events
    ::protocol/replayed-count (count events)}))

(deftest wire-writer-shutdown-closes-admission-and-drains-accepted-rpcs
  (async done
    (let [conn (fake-conn 17)
          respond (atom nil)]
      (-> (with-wire-state
           (stopped-state)
           (fn []
             (with-rpc-stub
              (fn [_sock-path request _opts]
                (is (= "test-route" (::protocol/database-name request)))
                (js/Promise.
                 (fn [deliver _reject]
                   (reset! respond deliver))))
              (fn []
                (let [wire-writer (test-writer conn)
                      result (writer/-dispatch!
                              wire-writer
                              {:op 'transact!
                               :args [{:tx-data
                                       [{:seon.db.replica-test/value
                                         "accepted"}]}]})
                      shutdown (writer/-shutdown wire-writer)]
                  (is (nil? (poll! shutdown))
                      "shutdown waits for the already-admitted RPC")
                  (@respond (success-response 17))
                  (-> (channel->promise result)
                      (.then
                       (fn [report]
                         (is (= 17 (get-in report [:db-after :max-tx])))
                         (channel->promise shutdown)))
                      (.then
                       (fn [drained]
                         (is (true? drained))
                         (channel->promise
                          (writer/-dispatch!
                           wire-writer
                           {:op 'transact!
                            :args [{:tx-data []}]}))))
                      (.then
                       (fn [error]
                         (is (instance? js/Error error))
                         (is (re-find #"shut down" (.-message error)))))))))))
          (.catch (fn [error]
                    (is false (str "wire writer drain test threw: " error))))
          (.finally done)))))

(deftest ping-retries-through-transient-failure
  ;; First two rpcs fail (socket not accepting yet — the start-all
  ;; race); the third succeeds. ping! must resolve, not throw.
  (async done
    (let [!calls (atom 0)]
      (-> (with-rpc-stub
            (fn [_sock-path _req _opts]
              (if (< (swap! !calls inc) 3)
                (js/Promise.reject (js/Error. "connect ECONNREFUSED (stub)"))
                (js/Promise.resolve {::protocol/success? true})))
            (fn [] (replica/ping!
                    {::launch/descriptor
                     replica/default-launch-descriptor})))
          (.then (fn [resp]
                   (is (true? (::protocol/success? resp))
                       "resolves to the reply map once an attempt succeeds")
                   (is (= 3 @!calls)
                       "two failed attempts consumed, third succeeded")))
          (.catch (fn [e]
                    (is false (str "ping! must survive transient failures, threw: "
                                   (.-message e)))))
          (.finally done)))))

(deftest ping-exhausts-budget-then-fails-loud
  ;; Every rpc fails — after the 5-attempt budget the SAME fail-loud
  ;; error throws (boots-only-against-cluster-store is not weakened).
  (async done
    (let [!calls (atom 0)]
      (-> (with-rpc-stub
            (fn [_sock-path _req _opts]
              (swap! !calls inc)
              (js/Promise.reject (js/Error. "connect ECONNREFUSED (stub)")))
            (fn [] (replica/ping!
                    {::launch/descriptor
                     replica/default-launch-descriptor})))
          (.then (fn [_]
                   (is false "ping! must throw once the retry budget is exhausted")))
          (.catch (fn [e]
                    (is (= 5 @!calls) "all 5 attempts consumed")
                    (is (= 5 (::replica/attempts (ex-data e))))
                    (is (= replica/default-request-socket-path
                           (::replica/socket-path (ex-data e))))
                    (is (= :core-bug (:seon.error/kind (ex-data e)))
                        "error kind unchanged")))
          (.finally done)))))

;; ── FIX 3: the tx-feed pump dispatches each listener ASYNCHRONOUSLY ────────
;; so one slow/throwing listener can't block the pump for all the others.
;; A fake conn carries its listeners exactly where `d/listen` puts them —
;; an atom in the conn's `:listeners` metadata — which fire-native-listeners!
;; reads. We prove: (1) callbacks do NOT run inline (deferred to a later
;; macrotask), and (2) a throwing listener doesn't stop another from firing
;; (the per-listener throw guard is preserved).

(deftest fire-native-listeners!-dispatches-async-and-survives-a-throwing-listener
  (async done
    (let [fired    (atom #{})
          throw-cb (fn [_report] (throw (js/Error. "boom — a slow/bad listener")))
          ok-cb    (fn [_report] (swap! fired conj :ok))
          conn     (with-meta {} {:listeners (atom {:k1 throw-cb :k2 ok-cb})})]
      (#'replica/fire-native-listeners! conn {:tx-data []})
      (is (empty? @fired)
          "listeners are dispatched on a later macrotask, NOT inline (pump never blocks)")
      (js/setTimeout
        (fn []
          (is (contains? @fired :ok)
              "the non-throwing listener still ran — a throwing one doesn't block it")
          (done))
        25))))

;; ── DE-2: feed application is IDEMPOTENT on the basis-t watermark ──────────
;; The reconnect since-t replay can deliver a tx by BOTH the replay and the
;; live path (same basis-t) — handle-feed-event! must apply each tx at most
;; once. We drive a fake conn (IDeref → a db value with :max-tx; IMeta →
;; listeners) and assert: a foreign tx above the watermark fires listeners once
;; and advances the watermark; a same-bt overlap and a stale (lower) bt are
;; no-ops. The attachment state is installed and restored hermetically.

(deftest handle-feed-event!-fires-foreign-once-and-dedups-overlap
  (async done
    (let [fired      (atom [])
          listeners  (atom {:k (fn [report] (swap! fired conj (count (:tx-data report))))})
          conn       (fake-conn 100 listeners)
          ev         (fn [bt] {::protocol/event protocol/transaction-event
                               ::protocol/coordinate (point bt)
                               ::protocol/previous-coordinate (point (dec bt))
                               ::protocol/transaction-data [[1 :a "v" bt true]]})]
      (-> (with-wire-state
           (attached-state conn 99)
           (fn []
             (with-as-of-stub
              (fn []
                ;; foreign tx, bt=100 > watermark 99 → fire + advance.
                (#'replica/handle-feed-event!
                 (adapter-generation) conn (ev 100))
                ;; replay/live overlap and a stale event are both no-ops.
                (#'replica/handle-feed-event!
                 (adapter-generation) conn (ev 100))
                (#'replica/handle-feed-event!
                 (adapter-generation) conn (ev 95))
                (is (= 100 (adapter-basis-t))
                    "the branch-qualified watermark advanced")
                (-> (after-macrotask)
                    (.then
                     (fn []
                       (is (= 1 (count @fired))
                           "overlap and stale frames did not redeliver"))))))))
          (.catch (fn [error]
                    (is false (str "foreign-event dedup test threw: " error))))
          (.finally done)))))

(deftest connect-feed!-walks-pages-then-dedups-the-buffered-live-overlap
  (async done
    (let [db-name       replica/database-name
          !requests     (atom [])
          !callbacks    (atom nil)
          !destroyed?   (atom false)
          !deliveries   (atom 0)
          listeners     (atom {:listener (fn [_] (swap! !deliveries inc))})
          conn          (fake-conn 104 listeners)
          socket        #js {:destroy (fn [] (reset! !destroyed? true))}
          connect-stub  (fn [_ {:keys [on-event] :as callbacks}]
                          (reset! !callbacks callbacks)
                          (is (fn? on-event))
                          (js/Promise.resolve socket))
          replay-stub   (fn [_ opts]
                          (swap! !requests conj opts)
                          (case (count @!requests)
                            1 (do
                                ;; These frames arrive while both replay pages
                                ;; are in flight. They overlap page two and must
                                ;; be discarded by the monotonic watermark.
                                ((:on-event @!callbacks)
                                 (replay-event db-name 103 102))
                                ((:on-event @!callbacks)
                                 (replay-event db-name 104 103))
                                (js/Promise.resolve
                                 (replay-page
                                  db-name 100 104 102 false
                                  [(replay-event db-name 101 100)
                                   (replay-event db-name 102 101)])))
                            2 (js/Promise.resolve
                               (replay-page
                                db-name 102 104 104 true
                                [(replay-event db-name 103 102)
                                 (replay-event db-name 104 103)]))))]
      (-> (with-wire-state
           (attached-state conn 100 ::replica/connecting)
           (fn []
             (with-feed-stubs
              connect-stub replay-stub
              (fn []
                (-> (connect-feed! conn "req.sock" "pub.sock" (fn [_] nil))
                    (.then
                     (fn [result]
                       (is (= 4 (::replica/replayed result)))
                       (is (= db-name (::replica/database-name result)))
                       (is (= [{:since-t 100 :db-name db-name}
                               {:since-t 102 :through-t 104 :db-name db-name}]
                              (mapv #(select-keys % [:since-t :through-t :db-name])
                                    @!requests))
                           "only continuations carry the fixed upper watermark")
                       (is (= 104 (adapter-basis-t))
                           "every replay page advanced the durable reconnect cursor")
                       (is (false? @!destroyed?))
                       (-> (after-macrotask)
                           (.then
                            (fn []
                              (is (= 4 @!deliveries)
                                  "four replay txs fired once; buffered duplicates did not")))))))))))
          (.catch (fn [error]
                    (is false (str "paginated feed test threw: " error))))
          (.finally done)))))

(deftest connect-feed!-rejects-a-non-final-empty-page-without-advancing
  (async done
    (let [db-name      replica/database-name
          !destroyed?  (atom false)
          !calls       (atom 0)
          conn         (fake-conn 102)
          socket       #js {:destroy (fn [] (reset! !destroyed? true))}
          connect-stub (fn [_ _] (js/Promise.resolve socket))
          replay-stub  (fn [_ _]
                         (swap! !calls inc)
                         (js/Promise.resolve
                          (replay-page db-name 100 102 100 false [])))]
      (-> (with-wire-state
           (attached-state conn 100 ::replica/connecting)
           (fn []
             (with-feed-stubs
              connect-stub replay-stub
              (fn []
                (-> (connect-feed! conn "req.sock" "pub.sock" (fn [_] nil))
                    (.then (fn [_]
                             (is false "a no-progress page must not go live")))
                    (.catch
                     (fn [error]
                       (is (= :core-bug (:seon.error/kind (ex-data error))))
                       (is (= 1 @!calls) "the client cannot spin on an empty page")
                       (is (= 100 (adapter-basis-t))
                           "an invalid page never advances past unseen txs")
                       (is (true? @!destroyed?)))))))))
          (.catch (fn [error]
                    (is false (str "empty-page safety test threw: " error))))
          (.finally done)))))

(deftest reconnect-during-replay-resumes-from-the-last-complete-page
  (async done
    (let [db-name       replica/database-name
          !connects     (atom 0)
          !replays      (atom 0)
          !callbacks    (atom nil)
          !drops        (atom [])
          !deliveries   (atom 0)
          listeners     (atom {:listener (fn [_] (swap! !deliveries inc))})
          conn          (fake-conn 104 listeners)
          connect-stub  (fn [_ callbacks]
                          (swap! !connects inc)
                          (reset! !callbacks callbacks)
                          (js/Promise.resolve #js {:destroy (fn [] nil)}))
          replay-stub   (fn [_ opts]
                          (case (swap! !replays inc)
                            1 (do
                                (is (= 100 (:since-t opts)))
                                (js/Promise.resolve
                                 (replay-page
                                  db-name 100 104 102 false
                                  [(replay-event db-name 101 100)
                                   (replay-event db-name 102 101)])))
                            2 (do
                                (is (= 102 (:since-t opts)))
                                (is (= 104 (:through-t opts)))
                                ((:on-close @!callbacks) "drop during replay")
                                (js/Promise.resolve
                                 (replay-page
                                  db-name 102 104 104 true
                                  [(replay-event db-name 103 102)
                                   (replay-event db-name 104 103)])))
                            3 (do
                                (is (= 102 (:since-t opts))
                                    "the new connection resumes after the applied page")
                                (is (not (contains? opts :through-t))
                                    "a reconnect captures a fresh upper watermark")
                                (js/Promise.resolve
                                 (replay-page
                                  db-name 102 104 104 true
                                  [(replay-event db-name 103 102)
                                   (replay-event db-name 104 103)])))))]
      (-> (with-wire-state
           (attached-state conn 100 ::replica/connecting)
           (fn []
             (with-feed-stubs
              connect-stub replay-stub
              (fn []
                (-> (connect-feed!
                     conn "req.sock" "pub.sock" #(swap! !drops conj %))
                    (.then (fn [_]
                             (is false "the dropped replay must not go live")))
                    (.catch
                     (fn [error]
                       (is (= "drop during replay"
                              (::replica/drop-reason (ex-data error))))
                       (is (= 102 (adapter-basis-t))
                           "only the fully applied page advances the watermark")
                       (connect-feed!
                        conn "req.sock" "pub.sock" #(swap! !drops conj %))))
                    (.then
                     (fn [result]
                       (is (= 2 (::replica/replayed result)))
                       (is (= 2 @!connects))
                       (is (empty? @!drops)
                           "a pre-live drop rejects the attempt; only a live feed calls on-drop")
                       (is (= 104 (adapter-basis-t)))
                       (-> (after-macrotask)
                           (.then
                            (fn []
                              (is (= 4 @!deliveries)
                                  "each transaction was delivered once across attempts")))))))))))
          (.catch (fn [error]
                    (is false (str "mid-replay reconnect test threw: " error))))
          (.finally done)))))

(deftest listen-adapter-attaches-stops-and-reattaches-with-branch-qualified-progress
  (async done
    (let [db-name         replica/database-name
          !connections    (atom [])
          !replays        (atom [])
          !a-destroyed    (atom 0)
          !b-destroyed    (atom 0)
          !a-deliveries   (atom [])
          !b-deliveries   (atom [])
          a-listeners     (atom {:a #(swap! !a-deliveries conj %)})
          b-listeners     (atom {:b #(swap! !b-deliveries conj %)})
          a-database      (atom (fake-db 50 :branch/a))
          b-database      (atom (fake-db 50 :branch/b))
          conn-a          (fake-changing-conn a-database a-listeners)
          conn-b          (fake-changing-conn b-database b-listeners)
          socket-for      (fn [connection-number]
                            #js {:destroy
                                 #(swap! (if (= 1 connection-number)
                                           !a-destroyed
                                           !b-destroyed)
                                         inc)})
          connect-stub    (fn [_ callbacks]
                            (let [connection-number
                                  (inc (count @!connections))]
                              (swap! !connections conj callbacks)
                              (js/Promise.resolve
                               (socket-for connection-number))))
          replay-stub     (fn [_ {:keys [since-t db-name] :as request}]
                            (swap! !replays conj request)
                            (let [branch (if (= 1 (count @!connections))
                                           :branch/a
                                           :branch/b)]
                              (js/Promise.resolve
                               (replay-page db-name since-t since-t since-t
                                            true [] branch))))
          start!          (fn [conn]
                            (let [db @conn]
                              (replica/attach!
                               {::replica/conn conn
                                ::launch/descriptor
                                (attachment-descriptor
                                 (coordinate/resolved db)
                                 db-name)})))
          lifecycle!      (fn []
                            (-> (start! conn-a)
                                (.then
                                 (fn [resolved-db-name]
                                   (is (= db-name resolved-db-name))
                                   (is (= 1 (count @!connections)))
                                   (is (= :branch/a
                                          (get-in
                                           (replica/status)
                                           [::coordinate/coordinate
                                            ::coordinate/branch])))
                                   (start! conn-a)))
                                (.then
                                 (fn [_]
                                   (is (= 1 (count @!connections))
                                       "starting the same attachment is idempotent")
                                   (is (= ::replica/tracked
                                          (#'replica/begin-transaction!
                                           conn-a "pending-a")))
                                   (is (= 1 (::replica/correlation-count
                                             (replica/status))))
                                   (is (true?
                                        (replica/detach!)))
                                   (is (false?
                                        (replica/detach!))
                                       "stopping an already stopped adapter is idempotent")
                                   (is (= 1 @!a-destroyed)
                                       "stopping closes the old pub socket once")
                                   (is (zero?
                                        (::replica/correlation-count
                                         (replica/status)))
                                       "attachment-owned correlations are disposed")
                                   (start! conn-b)))
                                (.then
                                 (fn [_]
                                   (let [status      (replica/status)
                                         a-callbacks (first @!connections)
                                         b-callbacks (second @!connections)]
                                     (is (= 2 (count @!connections)))
                                     (is (= 2 (count @!replays)))
                                     (is (= :branch/b
                                            (get-in
                                             status
                                             [::coordinate/coordinate
                                              ::coordinate/branch])))
                                     (is (= :branch/b
                                            (get-in
                                             (adapter-state)
                                             [::replica/database-coordinate
                                              ::coordinate/attachment
                                              ::coordinate/branch])))
                                     (is (= 50
                                            (adapter-basis-t))
                                         "branch B starts from its own t=50, not A's cursor")
                                     (reset! a-database
                                             (fake-db 51 :branch/a))
                                     (reset! b-database
                                             (fake-db 51 :branch/b))
                                     ((:on-event a-callbacks)
                                      (replay-event db-name 51 50 :branch/a))
                                     ((:on-event b-callbacks)
                                      (replay-event db-name 51 50 :branch/b))
                                     ((:on-event b-callbacks)
                                      (replay-event db-name 51 50 :branch/b))
                                     (-> (after-macrotask)
                                         (.then
                                          (fn []
                                            (is (empty? @!a-deliveries)
                                                "a stale A callback cannot reach either attachment")
                                            (is (= 1 (count @!b-deliveries))
                                                "B applies its commit exactly once")
                                            (is (= 51 (adapter-basis-t)))
                                            (is (true?
                                                 (replica/detach!)))
                                            (is (= 1 @!b-destroyed))))))))))]
      (-> (with-wire-state
           (stopped-state)
           #(with-feed-stubs connect-stub replay-stub lifecycle!))
          (.catch
           (fn [error]
             (is false (str "attachment lifecycle test threw: " error))))
          (.finally done)))))

;; ── Durable transaction ids + reply/feed ordering ─────────────────────────

(deftest transact-retry-resends-one-frozen-request
  (async done
    (let [!requests (atom [])
          !attempts (atom 0)
          conn      (fake-conn 17)]
      (-> (with-wire-state
           (stopped-state)
           (fn []
             (with-rpc-stub
               (fn [_sock-path request _opts]
                 (swap! !requests conj request)
                 (if (< (swap! !attempts inc) expected-transaction-attempts)
                   (js/Promise.reject
                    (ex-info "ambiguous reply loss"
                             {::uds/failure
                              :seon.db.transport.uds.failure/timeout}))
                   (js/Promise.resolve (success-response 17))))
               (fn []
                 (-> (dispatch-transaction
                      conn
                      {:tx-data [{:seon.db.replica-test/value "probe"}]})
                     (.then
                      (fn [report]
                        (is (= expected-transaction-attempts (count @!requests))
                            "the bounded retry budget reached the successful attempt")
                        (is (= 1
                               (count
                                (set
                                 (map ::protocol/request-id @!requests))))
                            "every ambiguous retry retained one durable wire id")
                        (is (apply = @!requests)
                            "the complete request stayed frozen across retries")
                        (is (= 17 (:max-tx (:db-after report)))
                            "the eventual response materialized normally")
                        (is (empty? (correlations))
                            "success without a running feed leaves no per-id state"))))))))
          (.catch (fn [error]
                    (is false (str "frozen-request retry test threw: " error))))
          (.finally done)))))

(deftest definite-allocator-protocol-rejection-cleans-state-and-is-structural
  (async done
    (let [!request (atom nil)
          conn     (fake-conn 23)]
      (-> (with-wire-state
           (stopped-state)
           (fn []
             (with-rpc-stub
               (fn [_sock-path request _opts]
                 (reset! !request request)
                 (js/Promise.resolve
                  {::protocol/success? false
                   ::protocol/error-kind protocol/protocol-error
                   ::protocol/error "invalid allocation shape"}))
               (fn []
                 (-> (dispatch-transaction
                      conn
                      {:tx-data []
                       :seon.db.id/generated-candidates ["mint-ember-otter"]
                       :seon.db.id/generated-identity-attrs
                       #{:seon.db.replica-test/id}})
                     (.then
                      (fn [error]
                        (let [data (ex-data error)]
                          (is (= :seon.db.id.error/invalid-allocation-transaction
                                 (:seon.db.id/error data))
                              "allocator protocol failure has a stable machine tag")
                          (is (= :core-bug (:seon.error/kind data))
                              "malformed allocator protocol is blamed on core")
                          (is (= ["mint-ember-otter"]
                                 (::protocol/generated-candidates
                                  @!request))
                              "the candidate manifest crosses the wire unchanged")
                          (is (not (contains?
                                   @!request
                                   :seon.db.id/generated-identity-attrs))
                              "the client-side identity catalog never crosses the wire")
                          (is (nil?
                               (get (correlations)
                                    (::protocol/request-id @!request)))
                              "a definite rejection removes its request-id state")))))))))
          (.catch (fn [error]
                    (is false (str "allocator protocol rejection test threw: "
                                   error))))
          (.finally done)))))

(deftest stale-coordinate-rejection-retains-the-protocol-discriminator
  (async done
    (let [conn (fake-conn 23)]
      (-> (with-wire-state
           (stopped-state)
           (fn []
             (with-rpc-stub
               (fn [_sock-path _request _opts]
                 (js/Promise.resolve
                  {::protocol/success? false
                   ::protocol/error-kind protocol/stale-coordinate-error
                   ::protocol/error "Transaction coordinate is stale."
                   ::protocol/expected-coordinate (point 22)
                   ::protocol/current-coordinate (point 23)}))
               (fn []
                 (-> (dispatch-transaction
                      conn
                      {:tx-data []
                       :seon.db/expected-coordinate (point 22)})
                     (.then
                      (fn [error]
                        (let [data (ex-data error)]
                          (is (= protocol/stale-coordinate-error
                                 (::protocol/error-kind data))
                              "callers can recognize the protocol rejection")
                          (is (= (point 22)
                                 (::protocol/expected-coordinate data)))
                          (is (= (point 23)
                                 (::protocol/current-coordinate data)))
                          (is (not (contains? data ::replica/error-kind))
                              "the replica boundary does not rename protocol data")))))))))
          (.catch
           (fn [error]
             (is false (str "stale-coordinate structure test threw: " error))))
          (.finally done)))))

(deftest definite-candidate-conflict-cleans-state-and-identifies-candidate
  (async done
    (let [candidate "mint-ember-otter"
          !request-id  (atom nil)
          conn      (fake-conn 29)]
      (-> (with-wire-state
           (stopped-state)
           (fn []
             (with-rpc-stub
               (fn [_sock-path request _opts]
                 (reset! !request-id (::protocol/request-id request))
                 (js/Promise.resolve
                  {::protocol/success? false
                   ::protocol/error-kind
                   protocol/generated-candidate-conflict-error
                   ::protocol/generated-candidate candidate
                   ::protocol/error "candidate already present"}))
               (fn []
                 (-> (dispatch-transaction
                      conn
                      {:tx-data []
                       :seon.db.id/generated-candidates [candidate]})
                     (.then
                      (fn [error]
                        (let [data (ex-data error)]
                          (is (= :seon.db.id.error/candidate-conflict
                                 (:seon.db.id/error data))
                              "candidate conflicts have a stable machine tag")
                          (is (= candidate
                                 (:seon.db.id/generated-candidate data))
                              "the rejected candidate remains inspectable")
                          (is (= :user-input (:seon.error/kind data))
                              "a caller-provided collision is structurally distinct")
                          (is (nil? (get (correlations) @!request-id))
                              "a definite candidate conflict removes per-id state")))))))))
          (.catch (fn [error]
                    (is false (str "candidate-conflict test threw: " error))))
          (.finally done)))))

(deftest exhausted-replies-return-unknown-without-claiming-non-commit
  (async done
    (let [!requests (atom [])
          conn      (fake-conn 31)]
      (-> (with-wire-state
           (stopped-state)
           (fn []
             (with-rpc-stub
               (fn [_sock-path request _opts]
                 (swap! !requests conj request)
                 (js/Promise.reject
                  (ex-info "ambiguous reply loss"
                           {::uds/failure
                            :seon.db.transport.uds.failure/timeout})))
               (fn []
                 (-> (dispatch-transaction
                      conn
                      {:tx-data [{:seon.db.replica-test/value "unknown"}]})
                     (.then
                      (fn [error]
                        (let [data (ex-data error)]
                          (is (= expected-transaction-attempts
                                 (::protocol/attempts data))
                              "unknown is returned only after the retry budget")
                          (is (= protocol/unknown-status
                                 (::protocol/status data))
                              "reply exhaustion reports commit ambiguity")
                          (is (= :seon.db.transport.uds.failure/timeout
                                 (::protocol/transport-failure data))
                              "the transport failure remains structured")
                          (is (= :core-bug (:seon.error/kind data))
                              "exhausted infrastructure ambiguity is a core fault")
                          (is (not (contains? data
                                              :seon.db.replica/committed?))
                              "unknown never falsely claims the transaction did not commit")
                          (is (= 1
                                 (count
                                  (set
                                   (map ::protocol/request-id @!requests))))
                              "reply exhaustion still used one durable wire id")
                          (is (apply = @!requests)
                              "every exhausted attempt resent the frozen request")
                          (is (empty? (correlations))
                              "terminal unknown removes the local per-id state")))))))))
          (.catch (fn [error]
                    (is false (str "reply-exhaustion test threw: " error))))
          (.finally done)))))

(deftest feed-before-response-delivers-once-and-cleans-per-id-state
  (async done
    (let [!deliveries (atom [])
          listeners   (atom {:listener #(swap! !deliveries conj %)})
          conn        (fake-conn 37 listeners)
          !request    (atom nil)
          !respond    (atom nil)]
      (-> (with-wire-state
           (attached-state conn 36)
           (fn []
             (with-rpc-stub
               (fn [_sock-path request _opts]
                 (reset! !request request)
                 (js/Promise.
                  (fn [deliver _reject]
                    (reset! !respond deliver))))
               (fn []
                 (let [result-promise
                       (dispatch-transaction
                        conn
                        {:tx-data [{:seon.db.replica-test/value "feed-first"}]})
                       request-id (::protocol/request-id @!request)
                       event   {::protocol/event protocol/transaction-event
                                ::protocol/request-id request-id
                                ::protocol/coordinate (point 37)
                                ::protocol/previous-coordinate (point 36)
                                ::protocol/transaction-data
                                [[1 :seon.db.replica-test/value
                                  "feed-first" 37 true]]}]
                   (#'replica/handle-feed-event!
                    (adapter-generation) conn event)
                   (is (contains? (correlations) request-id)
                       "feed-first remains recoverable until the response arrives")
                   (is (empty? @!deliveries)
                       "the own feed never delivers inline")
                   (@!respond (success-response 37))
                   (-> result-promise
                       (.then
                        (fn [report]
                          ;; Datahike's outer writer loop performs this step in
                          ;; production after it receives a successful report.
                          (#'replica/fire-native-listeners! conn report)
                          (is (empty? (correlations))
                              "the matching response consumes feed-first state")
                          (-> (after-macrotask)
                              (.then
                               (fn []
                                 (is (= 1 (count @!deliveries))
                                     "feed then response delivers exactly once"))))))))))))
          (.catch (fn [error]
                    (is false (str "feed-before-response test threw: " error))))
          (.finally done)))))

(deftest response-before-feed-delivers-once-and-cleans-per-id-state
  (async done
    (let [!deliveries (atom [])
          listeners   (atom {:listener #(swap! !deliveries conj %)})
          conn        (fake-conn 41 listeners)
          !request    (atom nil)]
      (-> (with-wire-state
           (attached-state conn 40)
           (fn []
             (with-rpc-stub
               (fn [_sock-path request _opts]
                 (reset! !request request)
                 (js/Promise.resolve (success-response 41)))
               (fn []
                 (-> (dispatch-transaction
                      conn
                      {:tx-data [{:seon.db.replica-test/value
                                  "response-first"}]})
                     (.then
                      (fn [report]
                        (let [request-id (::protocol/request-id @!request)
                              event   {::protocol/event protocol/transaction-event
                                       ::protocol/request-id request-id
                                       ::protocol/coordinate (point 41)
                                       ::protocol/previous-coordinate (point 40)
                                       ::protocol/transaction-data
                                       [[1 :seon.db.replica-test/value
                                         "response-first" 41 true]]}]
                          (is (contains? (correlations) request-id)
                              "response-first remains tracked until its feed")
                          ;; Datahike's outer writer loop delivers the response
                          ;; report; the own feed must suppress its duplicate.
                          (#'replica/fire-native-listeners! conn report)
                          (#'replica/handle-feed-event!
                           (adapter-generation) conn event)
                          (is (empty? (correlations))
                              "the matching feed consumes response-first state")
                          (-> (after-macrotask)
                              (.then
                               (fn []
                                 (is (= 1 (count @!deliveries))
                                     "response then feed delivers exactly once"))))))))))))
          (.catch (fn [error]
                    (is false (str "response-before-feed test threw: " error))))
          (.finally done)))))
