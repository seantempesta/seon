(ns seon.client-runtime-test
  "Process-local launch capability and non-autonomous runtime tests."
  (:require
    [cljs.test :refer [async deftest is testing]]
    [seon.agent :as agent]
    [seon.agent.loop :as agent-loop]
    [seon.agent.runtime :as agent-runtime]
    [seon.ai :as ai]
    [seon.client :as client]
    [seon.db :as db]
    [seon.error :as error]
    [seon.eval :as seval]
    [seon.instrument :as instrument]
    [seon.launch :as launch]
    [my.blob :as blob]
    [seon.log :as log]
    [seon.repl :as repl]
    [seon.db.replica :as replica]
    [seon.runtime.admission :as admission]
    [seon.runtime.recovery :as recovery]
    [seon.schema :as schema]
    [seon.web.brand :as web.brand]
    [seon.web.serve :as web.serve]))

(def non-autonomous-capability
  {::client/autonomous? false})

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

(defn- with-start-stubs
  "Run `body` while retaining async-safe runtime launch stubs."
  [publication body]
  (let [effects (atom [])
        connection (atom {})
        previous-state @client/!state
        previous-connection db/*conn*
        original-attached? db/attached?
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
        original-publish admission/publish-committed!
        original-resume agent/resume!
        original-web-start web.serve/start!
        original-ai-sync ai/sync!
        original-brand-sync web.brand/sync!
        original-ticker agent-loop/install-ticker!]
    (set! db/*conn* nil)
    (reset! client/!state
            (dissoc previous-state
                    ::client/launch-capability
                    ::client/runtime-phase))
    (set! db/attached? (fn [] (some? db/*conn*)))
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
    (set! agent/resume!
          (fn [_]
            (swap! effects conj :forbidden/host)
            (promise-value {:seon.agent.runtime/resumed? true})))
    (set! web.serve/start!
          (fn []
            (swap! effects conj :web)
            (promise-value {:seon.web/port 7890
                            :seon.web/port-file "tmp/test-port"})))
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
           (set! db/attached? original-attached?)
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
           (set! admission/publish-committed! original-publish)
           (set! agent/resume! original-resume)
           (set! web.serve/start! original-web-start)
           (set! ai/sync! original-ai-sync)
           (set! web.brand/sync! original-brand-sync)
           (set! agent-loop/install-ticker! original-ticker)
           (set! db/*conn* previous-connection)
           (reset! client/!state previous-state))))))

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
            (fn []
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
               (done))))))))

(deftest failed-web-close-retains-state-and-retries-the-same-inverse
  (async done
    (let [previous-state @client/!state
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
             (done)))))))

(defn- run-stop-step-retry-proof!
  [failed-stage]
  (let [previous-state @client/!state
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
           (reset! client/!state previous-state))))))

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
