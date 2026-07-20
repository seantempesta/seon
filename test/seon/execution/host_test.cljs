(ns seon.execution.host-test
  (:require
   [cljs.test :refer [async deftest is testing]]
   [seon.db.transport.uds :as uds]
   [seon.execution :as execution]
   [seon.execution.host :as host]
   [seon.launch :as launch]
   [seon.subprocess :as subprocess]))

(def digest (apply str (repeat 64 "e")))
(def database
  {:db-name "test-cluster"
   :store-id [#uuid "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa" :db]
   :t 42
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id #uuid "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"})

(deftest default-idle-retention-covers-ordinary-interactive-work
  (is (= 300000 @#'host/default-idle-timeout-ms)))

(defn descriptor []
  (launch/with-execution-artifact
   {::launch/descriptor
    (launch/default-descriptor
     {::launch/cluster-dir "tmp/test-cluster"
      ::launch/artifact-flavor :seon.dev.artifact.flavor/default
      ::launch/client-build-id "client"
      ::launch/execution-build-id "execution"
      ::launch/execution-output "out/execution/main.js"
      ::launch/request-socket-path "tmp/test.req.sock"
      ::launch/writer-repl-port-file "tmp/test.writer.port"
      ::launch/process-dir "tmp/test-processes"
      ::launch/log-dir "logs/test"
      ::launch/http-port 0
      ::launch/http-port-file "tmp/test.http.port"})
    ::launch/execution-build-id "execution"
    ::launch/execution-output "out/execution/main.js"
    ::launch/execution-digest digest}))

(defn invocation
  ([invocation-id] (invocation "agent-1" invocation-id))
  ([agent-id invocation-id]
   {::execution/message execution/invoke-message
    ::execution/protocol-version execution/protocol-version
    ::execution/agent-id agent-id
    ::execution/invocation-id invocation-id
    :seon.db/db database
    ::execution/function-identity
    {::execution/function-symbol 'my.render/view
     ::execution/source-digest digest}
    ::execution/arguments [{:my.render/value 1}]
    ::execution/deadline-ms 9999999999999
    ::execution/result-limit-bytes 4096}))

(defn ready-message
  ([] (ready-message "agent-1"))
  ([agent-id]
   {::execution/message execution/ready-message
    ::execution/protocol-version execution/protocol-version
    ::execution/agent-id agent-id
    ::execution/bun-version "1.2.0"
    ::execution/shadow-build-id "execution"
    ::execution/artifact-digest digest
    :seon.db/db database}))

(defn result-message [invocation-id value]
  {::execution/message execution/result-message
   ::execution/protocol-version execution/protocol-version
   ::execution/invocation-id invocation-id
   :seon.db/db database
   ::execution/result value
   ::execution/result-bytes 32})

(defn fake-process
  ([pid] (fake-process pid nil))
  ([pid live-resource-usage]
  (let [sent (atom [])
        kills (atom [])
        resolve-exit! (atom nil)
        exited (js/Promise.
                (fn [resolve-promise _]
                  (reset! resolve-exit! resolve-promise)))
        process (js-obj)]
    (aset process "pid" pid)
    (aset process "stdout" nil)
    (aset process "stderr" nil)
    (aset process "exited" exited)
    (aset process "send" #(swap! sent conj (execution/decode-message %)))
    (aset process "kill" #(swap! kills conj %))
    (aset process "resourceUsage"
          (fn [^js options]
            (when (true? (some-> options .-live))
              live-resource-usage)))
    {:process process
     :sent sent
     :kills kills
     :resolve-exit! @resolve-exit!})))

(defn configure [spawn!]
  (host/configure!
   {::host/launch-descriptor (descriptor)
    ::host/javascript-runtime "bun"
    ::host/ready-timeout-ms 1000
    ::host/idle-timeout-ms 60000
    ::host/cancel-grace-ms 5
    ::host/spawn!
    (fn [request]
      (subprocess/start!
       (assoc request ::subprocess/spawn!
              (fn [^js options]
                (spawn! {::host/cmd (vec (js->clj (.-cmd options)))
                         ::host/ipc (.-ipc options)
                         ::host/stdout "pipe"
                         ::host/stderr "pipe"})))))}))

(defn feed! [options process message]
  ((::host/ipc options) (execution/encode-message message) process))

(deftest lazy-child-is-reused-and-reconfiguration-terminates-it
  (async done
    (let [spawned (atom [])
          options (atom nil)
          child (fake-process 101)
          spawn! (fn [value]
                   (reset! options value)
                   (swap! spawned conj (:process child))
                   (:process child))
          _ (configure spawn!)
          first-completion (host/invoke! (invocation "invoke-1"))]
      (testing "the flavor-owned child is spawned lazily"
        (is (= 1 (count @spawned)))
        (is (= ["bun" "out/execution/main.js"]
               (subvec (::host/cmd @options) 0 2)))
        (let [startup (execution/decode-message
                       (last (::host/cmd @options)))]
          (is (= "execution" (::execution/shadow-build-id startup)))
          (is (= "tmp/test.req.sock"
                 (get-in startup [::execution/database-selection
                                  :seon.db/socket-path])))
          (is (false?
               (get-in startup [::execution/database-selection
                                :seon.db/database-advanced?])))))
      (feed! @options (:process child) (ready-message))
      (-> (js/Promise.resolve nil)
          (.then
           (fn [_]
             (js/Promise.
              (fn [resolve-promise _]
                (js/setTimeout resolve-promise 5)))))
          (.then
           (fn [_]
             (is (= execution/invoke-message
                    (::execution/message (first @(:sent child)))))
             (is (not-any? #(= execution/cancel-message
                                (::execution/message %))
                           @(:sent child))
                 "a far-future deadline is bounded without firing early")
             (feed! @options (:process child)
                    (result-message "invoke-1" {:my.render/value 1}))
             first-completion))
          (.then
           (fn [result]
             (is (= {:my.render/value 1} (::execution/result result)))
             (let [second-completion (host/invoke! (invocation "invoke-2"))]
               (-> (js/Promise.resolve nil)
                   (.then
                    (fn [_]
                      (is (= 1 (count @spawned))
                          "an idle ready child serves the next invocation")
                      (feed! @options (:process child)
                             (result-message
                              "invoke-2" {:my.render/value 2}))))
                   (.then (fn [_] second-completion))))))
          (.then
           (fn [_]
             (configure spawn!)
             (is (= execution/shutdown-message
                    (::execution/message (last @(:sent child)))))
             (js/Promise.
              (fn [resolve-promise _]
                (js/setTimeout resolve-promise 15)))))
          (.then
           (fn [_]
             (is (= ["SIGKILL"] @(:kills child))
                 "reconfiguration retains a direct terminal kill handle")
             ((:resolve-exit! child) 0)
             (done)))
          (.catch
           (fn [error]
             (is false (str "unexpected host failure: " error))
             (done)))))))

(deftest stop-awaits-every-execution-child-exit
  (async done
    (let [options (atom [])
          children [(fake-process 111) (fake-process 112)]
          next-child (atom children)
          _ (configure
             (fn [value]
               (swap! options conj value)
               (let [child (first @next-child)]
                 (swap! next-child subvec 1)
                 (:process child))))
          _ (host/invoke! (invocation "agent-a" "invoke-a"))
          _ (host/invoke! (invocation "agent-b" "invoke-b"))]
      (feed! (first @options) (:process (first children))
             (ready-message "agent-a"))
      (feed! (second @options) (:process (second children))
             (ready-message "agent-b"))
      (let [settled? (atom false)
            stopped (-> (host/stop!)
                        (.then (fn [count]
                                 (reset! settled? true)
                                 count)))]
        (-> (js/Promise.resolve nil)
            (.then
             (fn [_]
               (is (false? @settled?))
               (is (every?
                    #(= execution/shutdown-message
                        (::execution/message (last @(:sent %))))
                    children))
               ((:resolve-exit! (first children)) 0)))
            (.then
             (fn [_]
               (is (false? @settled?)
                   "one child exit cannot complete the host drain")
               ((:resolve-exit! (second children)) 0)
               stopped))
            (.then
             (fn [count]
               (is (= 2 count))
               (is (empty? (host/processes)))
               (done)))
            (.catch
             (fn [error]
               (is false (str "unexpected stop failure: " error))
               (done))))))))

(deftest process-snapshot-samples-the-child-without-child-cooperation
  (async done
    (let [options (atom nil)
          child (fake-process
                 109
                 #js {:cpuTime #js {:user (js/BigInt 100)
                                    :system (js/BigInt 20)
                                    :total (js/BigInt 120)}
                      :rss 2048})
          _ (configure (fn [value]
                         (reset! options value)
                         (:process child)))
          completion (host/invoke! (invocation "live-process"))]
      (feed! @options (:process child) (ready-message))
      (-> (js/Promise.resolve nil)
          (.then
           (fn [_]
             (let [process (first (host/processes))]
               (is (= "agent-1" (::execution/agent-id process)))
               (is (= 109 (::host/pid process)))
               (is (= 2048
                      (get-in process [::host/resource-usage
                                       ::subprocess/rss-bytes])))
               (is (= "live-process"
                      (get-in process [::host/invocation
                                       ::execution/invocation-id])))
               (is (inst? (get-in process [::host/invocation
                                           ::host/started-at]))))
             (feed! @options (:process child)
                    (result-message "live-process" :ok))
             completion))
          (.then (fn [_] ((:resolve-exit! child) 0)))
          (.catch (fn [error]
                    (is false (str "process snapshot rejected: " error))))
          (.finally (fn [] (done)))))))

(deftest failed-ipc-send-preserves-the-cause-and-child-evidence
  (async done
    (let [options (atom nil)
          child (fake-process 111)
          _ (aset (:process child) "send"
                  (fn [_] (throw (js/Error. "broken IPC"))))
          _ (configure (fn [value]
                         (reset! options value)
                         (:process child)))
          completion (host/invoke! (invocation "send-failure"))]
      (feed! @options (:process child) (ready-message))
      (-> completion
          (.then
           (fn [result]
             (is (= "The execution invocation could not be sent."
                    (get-in result [::execution/error :seon.error/message])))
             (is (= "broken IPC"
                    (get-in result [::execution/error :seon.error/data
                                    :seon.error/cause])))
             (is (= 111
                    (get-in result [::execution/error :seon.error/data
                                    ::host/pid])))
             (is (= ["SIGKILL"] @(:kills child)))
             ((:resolve-exit! child) 1)
             (done)))
          (.catch
           (fn [error]
             (is false (str "send failure evidence rejected: " error))
             (done)))))))

(deftest same-agent-invocations-share-one-ordered-child-queue
  (async done
    (let [options (atom nil)
          child (fake-process 120)
          _ (configure (fn [value]
                         (reset! options value)
                         (:process child)))
          first-completion (host/invoke! (invocation "queued-first"))
          second-completion (host/invoke! (invocation "queued-second"))]
      (feed! @options (:process child) (ready-message))
      (-> (js/Promise.resolve nil)
          (.then
           (fn [_]
             (is (= ["queued-first"]
                    (mapv ::execution/invocation-id @(:sent child)))
                 "the second invocation waits while the first is active")
             (feed! @options (:process child)
                    (result-message "queued-first" :first))
             first-completion))
          (.then
           (fn [first-result]
             (is (= :first (::execution/result first-result)))
             (js/Promise.resolve nil)))
          (.then
           (fn [_]
             (is (= ["queued-first" "queued-second"]
                    (mapv ::execution/invocation-id @(:sent child))))
             (feed! @options (:process child)
                    (result-message "queued-second" :second))
             second-completion))
          (.then
           (fn [second-result]
             (is (= :second (::execution/result second-result)))
             ((:resolve-exit! child) 0)))
          (.catch
           (fn [error]
             (is false (str "same-agent queue rejected: " error))))
          (.finally (fn [] (done)))))))

(deftest queued-deadline-does-not-retire-the-active-predecessor
  (async done
    (let [options (atom nil)
          child (fake-process 121)
          _ (configure (fn [value]
                         (reset! options value)
                         (:process child)))
          first-completion (host/invoke! (invocation "long-first"))
          queued (host/invoke!
                  (assoc (invocation "short-second")
                         ::execution/deadline-ms (+ (.now js/Date) 5)))]
      (feed! @options (:process child) (ready-message))
      (-> queued
          (.then
           (fn [result]
             (is (= "The invocation was canceled."
                    (get-in result [::execution/error :seon.error/message])))
             (is (empty? @(:kills child)))
             (is (not-any? #(= execution/shutdown-message
                                (::execution/message %))
                           @(:sent child))
                 "the queued deadline leaves the active predecessor alone")
             (feed! @options (:process child)
                    (result-message "long-first" :finished))
             first-completion))
          (.then
           (fn [result]
             (is (= :finished (::execution/result result)))
             ((:resolve-exit! child) 0)))
          (.catch
           (fn [error]
             (is false (str "queued deadline rejected: " error))))
          (.finally (fn [] (done)))))))

(deftest changed-program-replaces-one-child-and-retries-once
  (async done
    (let [options (atom [])
          first-child (fake-process 111)
          second-child (fake-process 112)
          remaining (atom [first-child second-child])
          _ (configure
             (fn [value]
               (swap! options conj value)
               (let [[before after] (swap-vals! remaining subvec 1)]
                 (:process (first before)))))
          request (invocation "program-change")
          completion (host/invoke! request)]
      (feed! (first @options) (:process first-child) (ready-message))
      (-> (js/Promise.resolve nil)
          (.then
           (fn [_]
             (is (= [execution/invoke-message]
                    (mapv ::execution/message @(:sent first-child))))
             (feed!
              (first @options) (:process first-child)
              {::execution/message execution/error-message
               ::execution/protocol-version execution/protocol-version
               ::execution/invocation-id "program-change"
               :seon.db/db database
               ::execution/error
               {:seon.error/message "The authored program changed."
                :seon.error/kind :core-bug
                :seon.error/data {::execution/reload-required? true}}})
             (js/Promise.
              (fn [resolve-promise _]
                (js/setTimeout resolve-promise 0)))))
          (.then
           (fn [_]
             (is (= 2 (count @options))
                 "the retry spawns exactly one fresh child")
             (is (= ["SIGKILL"] @(:kills first-child)))
             (feed! (second @options) (:process second-child)
                    (ready-message))))
          (.then
           (fn [_]
             (feed! (second @options) (:process second-child)
                    (result-message "program-change"
                                    {:my.render/value :current}))
             completion))
          (.then
           (fn [result]
             (is (= {:my.render/value :current}
                    (::execution/result result)))
             (is (= [execution/invoke-message]
                    (mapv ::execution/message @(:sent second-child)))
                 "the original invocation is sent once to the replacement")
             (is (empty? @remaining)
                 "a reload response cannot create a third attempt")
             ((:resolve-exit! first-child) 1)
             ((:resolve-exit! second-child) 0)))
          (.catch
           (fn [error]
             (is false (str "program replacement rejected: " error))))
          (.finally (fn [] (done)))))))

(deftest pre-ready-exit-settles-the-waiting-invocation
  (async done
    (let [child (fake-process 102)
          _ (configure (fn [_] (:process child)))
          completion (host/invoke! (invocation "pre-ready"))]
      ((:resolve-exit! child) 17)
      (-> completion
          (.then
           (fn [result]
             (is (= execution/error-message (::execution/message result)))
             (is (= "startup" (::execution/invocation-id result)))
             (is (= 17 (get-in result [::execution/error :seon.error/data
                                       ::host/exit-code])))
             (done)))
          (.catch
           (fn [error]
             (is false (str "pre-ready exit rejected: " error))
           (done)))))))

(deftest active-child-exit-settles-once-and-next-call-reconstructs
  (async done
    (let [options (atom [])
          first-child (fake-process 107)
          replacement-child (fake-process 108)
          remaining (atom [first-child replacement-child])
          _ (configure
             (fn [value]
               (swap! options conj value)
               (let [[before _] (swap-vals! remaining subvec 1)]
                 (:process (first before)))))
          interrupted (host/invoke! (invocation "interrupted"))]
      (feed! (first @options) (:process first-child) (ready-message))
      (-> (js/Promise.resolve nil)
          (.then
           (fn [_]
             ((:resolve-exit! first-child) 137)
             interrupted))
          (.then
           (fn [result]
             (is (= execution/error-message (::execution/message result)))
             (is (= "The execution child exited before returning a result."
                    (get-in result [::execution/error :seon.error/message])))
             (is (= 137
                    (get-in result [::execution/error :seon.error/data
                                    ::host/exit-code])))
             (let [replacement (host/invoke! (invocation "replacement"))]
               (feed! (second @options) (:process replacement-child)
                      (ready-message))
               (-> (js/Promise.resolve nil)
                   (.then
                    (fn [_]
                      (feed! (second @options) (:process replacement-child)
                             (result-message "replacement" :reconstructed))))
                   (.then (fn [_] replacement))))))
          (.then
           (fn [result]
             (is (= :reconstructed (::execution/result result)))
             (is (empty? @remaining)
                 "the next call starts exactly one replacement")
             ((:resolve-exit! replacement-child) 0)))
          (.catch
           (fn [error]
             (is false (str "unexpected-death recovery rejected: " error))))
          (.finally (fn [] (done)))))))

(deftest reconfiguration-between-ready-and-claim-cannot-create-a-child
  (async done
    (let [old-options (atom nil)
          new-options (atom nil)
          old-child (fake-process 103)
          new-child (fake-process 104)
          _ (configure (fn [value]
                         (reset! old-options value)
                         (:process old-child)))
          old-completion (host/invoke! (invocation "old-invocation"))]
      (feed! @old-options (:process old-child) (ready-message))
      ;; The ready Promise continuation has not run yet. Replacing the host
      ;; here must not let it install an active-only child in the new registry.
      (configure (fn [value]
                   (reset! new-options value)
                   (:process new-child)))
      (-> old-completion
          (.then
           (fn [result]
             (is (= execution/error-message (::execution/message result)))
             (is (= "old-invocation" (::execution/invocation-id result)))
             (let [completion (host/invoke! (invocation "new-invocation"))]
               (feed! @new-options (:process new-child) (ready-message))
               (-> (js/Promise.resolve nil)
                   (.then
                    (fn [_]
                      (feed! @new-options (:process new-child)
                             (result-message "new-invocation"
                                             {:my.render/value 4}))))
                   (.then (fn [_] completion))))))
          (.then
           (fn [result]
             (is (= {:my.render/value 4} (::execution/result result)))
             ((:resolve-exit! old-child) 0)
             ((:resolve-exit! new-child) 0)
             (done)))
          (.catch
           (fn [error]
             (is false (str "configure/claim race rejected: " error))
             (done)))))))

(deftest cancellation-retires-the-child-after-a-terminal-message
  (async done
    (let [options (atom [])
          first-child (fake-process 105)
          replacement-child (fake-process 106)
          children (atom [first-child replacement-child])
          _ (configure
             (fn [value]
               (swap! options conj value)
               (let [[before _] (swap-vals! children subvec 1)]
                 (when (identical? replacement-child (first before))
                   (js/setTimeout
                    #(feed! value (:process replacement-child)
                            (ready-message))
                    0)
                   (js/setTimeout
                    #(feed! value (:process replacement-child)
                            (result-message "after-cancel"
                                            {:my.render/value :fresh}))
                    5))
                 (:process (first before)))))
          completion (host/invoke! (invocation "cancelled"))]
      (feed! (first @options) (:process first-child) (ready-message))
      (-> (js/Promise.resolve nil)
          (.then
           (fn [_]
             (is (host/cancel! "agent-1" "cancelled"))
             ;; Even a cooperative terminal reply cannot make this process
             ;; reusable: the canceled function may still be running.
             (feed! (first @options) (:process first-child)
                    (result-message "cancelled" {:my.render/value 5}))
             completion))
          (.then
           (fn [result]
             (is (= execution/error-message (::execution/message result)))
             (is (= "The invocation was canceled."
                    (get-in result [::execution/error
                                    :seon.error/message])))
             (is (nil? (get-in result [::execution/error :seon.error/data
                                       ::execution/child-retired?])))
             (is (nil? (::execution/result result))
                 "the late success was discarded")
             (let [after-cancel (host/invoke! (invocation "after-cancel"))]
               (is (= 1 (count @children))
                   "retirement does not spawn before the old child exits")
               ((:resolve-exit! first-child) 0)
               after-cancel)))
          (.then
           (fn [result]
             (is (= {:my.render/value :fresh}
                    (::execution/result result)))
             (is (= [execution/invoke-message]
                    (mapv ::execution/message @(:sent replacement-child))))
             ((:resolve-exit! replacement-child) 0)
             (done)))
          (.catch
           (fn [error]
             (is false (str "hard cancellation rejected: " error "\n"
                            (.-stack error)))
             (done)))))))

(deftest synchronous-spawn-failure-is-an-ordinary-error
  (async done
    (configure (fn [_] (throw (js/Error. "spawn failed"))))
    (-> (host/invoke! (invocation "spawn-failure"))
        (.then
         (fn [result]
           (is (= execution/error-message (::execution/message result)))
           (is (= "startup" (::execution/invocation-id result)))
           (done)))
        (.catch
         (fn [error]
           (is false (str "spawn failure rejected: " error))
           (done))))))

(deftest parent-deadline-retires-a-non-settling-child
  (async done
    (let [options (atom [])
          first-child (fake-process
                       106
                       #js {:cpuTime #js {:user (js/BigInt 90)
                                          :system (js/BigInt 10)
                                          :total (js/BigInt 100)}
                            :rss 3072})
          sibling-child (fake-process 107)
          children (atom [first-child sibling-child])
          _ (configure
             (fn [value]
               (swap! options conj value)
               (:process (first (first (swap-vals! children subvec 1))))))
          timed (assoc (invocation "non-settling")
                       ::execution/deadline-ms (+ (.now js/Date) 10))
          completion (host/invoke! timed)]
      (feed! (first @options) (:process first-child) (ready-message))
      (-> completion
          (.then
           (fn [result]
             (is (= execution/error-message (::execution/message result)))
             (is (= "The invocation was canceled."
                    (get-in result [::execution/error
                                    :seon.error/message])))
             (is (true? (get-in result [::execution/error :seon.error/data
                                        ::execution/child-retired?])))
             (is (= 3072
                    (get-in result [::execution/error :seon.error/data
                                    ::host/resource-usage
                                    ::subprocess/rss-bytes])))
             (feed! (first @options) (:process first-child)
                    (result-message "non-settling" {:my.render/value :late}))
             (is (nil? (::execution/result result))
                 "a late success cannot settle the timed-out invocation")
             (js/Promise.
              (fn [resolve-promise _]
                (js/setTimeout resolve-promise 15)))))
          (.then
           (fn [_]
             (is (= ["SIGKILL"] @(:kills first-child)))
             ((:resolve-exit! first-child) 1)
             (js/Promise.resolve nil)))
          (.then
           (fn [_]
             (let [replacement (host/invoke! (invocation "replacement"))]
               (feed! (second @options) (:process sibling-child)
                      (ready-message))
               (-> (js/Promise.resolve nil)
                   (.then
                    (fn [_]
                      (feed! (second @options) (:process sibling-child)
                             (result-message "replacement"
                                             {:my.render/value :ok}))))
                   (.then (fn [_] replacement))))))
          (.then
           (fn [result]
             (is (= {:my.render/value :ok} (::execution/result result)))
             ((:resolve-exit! sibling-child) 0)
             (done)))
          (.catch
           (fn [error]
             (is false (str "deadline supervision rejected: " error))
             (done)))))))

(deftest one-agent-deadline-does-not-block-another-agent-child
  (async done
    (let [options (atom {})
          children {"agent-1" (fake-process 109)
                    "agent-2" (fake-process 110)}
          _ (configure
             (fn [value]
               (let [startup (execution/decode-message (last (::host/cmd value)))
                     agent-id (::execution/agent-id startup)]
                 (swap! options assoc agent-id value)
                 (:process (get children agent-id)))))
          stuck (host/invoke! (invocation "agent-1" "stuck"))
          healthy (host/invoke! (invocation "agent-2" "healthy"))]
      (doseq [agent-id ["agent-1" "agent-2"]]
        (feed! (get @options agent-id)
               (:process (get children agent-id))
               (assoc (ready-message) ::execution/agent-id agent-id)))
      (-> (js/Promise.resolve nil)
          (.then
           (fn [_]
             (feed! (get @options "agent-2")
                    (:process (get children "agent-2"))
                    (result-message "healthy" {:my.render/value :parallel}))
             healthy))
          (.then
           (fn [result]
             (is (= {:my.render/value :parallel}
                    (::execution/result result)))
             (is (host/cancel! "agent-1" "stuck"))
             stuck))
          (.then
           (fn [result]
             (is (= execution/error-message (::execution/message result)))
             ((:resolve-exit! (get children "agent-1")) 1)
             ((:resolve-exit! (get children "agent-2")) 0)
             (done)))
          (.catch
           (fn [error]
             (is false (str "cross-agent isolation rejected: " error))
             (done)))))))

(deftest deadline-before-ready-never-sends-expired-work
  (async done
    (let [options (atom nil)
          child (fake-process 108)
          _ (configure (fn [value]
                         (reset! options value)
                         (:process child)))
          request (assoc (invocation "before-ready")
                         ::execution/deadline-ms (+ (.now js/Date) 5))]
      (-> (host/invoke! request)
          (.then
           (fn [result]
             (is (= "The invocation was canceled."
                    (get-in result [::execution/error
                                    :seon.error/message])))
             (is (= [execution/shutdown-message]
                    (mapv ::execution/message @(:sent child)))
                 "pending readiness is retired without sending invocation")
             (feed! @options (:process child) (ready-message))
             (js/Promise.
              (fn [resolve-promise _]
                (js/setTimeout resolve-promise 10)))))
          (.then
           (fn [_]
             (is (not-any? #(= execution/invoke-message
                                (::execution/message %))
                           @(:sent child)))
             (is (= ["SIGKILL"] @(:kills child)))
             ((:resolve-exit! child) 1)
             (done)))
          (.catch
           (fn [error]
             (is false (str "pre-ready deadline rejected: " error))
             (done)))))))

(deftest plan-groups-overlap-across-agents-and-preserve-result-position
  (async done
    (let [plans [(execution/invocation-plan "agent-1" 'my.one/first [1])
                 (execution/invocation-plan "agent-2" 'my.two/run [2])
                 (execution/invocation-plan "agent-1" 'my.one/second [3])]
          invocations
          [(invocation "agent-1" "agent-1-first")
           (invocation "agent-2" "agent-2-run")
           (invocation "agent-1" "agent-1-second")]
          started (atom [])
          resolve-first! (atom nil)
          first-completion
          (js/Promise. (fn [resolve _] (reset! resolve-first! resolve)))
          resolve-first-wave! (atom nil)
          first-wave-started
          (js/Promise. (fn [resolve _] (reset! resolve-first-wave! resolve)))
          resolve-second-agent1! (atom nil)
          second-agent1-started
          (js/Promise. (fn [resolve _]
                         (reset! resolve-second-agent1! resolve)))
          invoke-stub
          (fn [request]
            (let [id (::execution/invocation-id request)]
              (let [current (swap! started conj id)]
                (when (= #{"agent-1-first" "agent-2-run"} (set current))
                  (@resolve-first-wave! true))
                (when (some #{"agent-1-second"} current)
                  (@resolve-second-agent1! true)))
              (case id
                "agent-1-first" first-completion
                "agent-2-run"
                (js/Promise.resolve (result-message id :agent-2))
                "agent-1-second"
                (js/Promise.resolve (result-message id :agent-1-second)))))
          original-prepare execution/prepare-invocations!
          original-invoke host/invoke!]
      (set! execution/prepare-invocations!
            (fn [_] (js/Promise.resolve invocations)))
      (set! host/invoke! invoke-stub)
      (let [completion (host/invoke-plans! database plans)]
        (-> first-wave-started
            (.then
             (fn [_]
               (is (= #{"agent-1-first" "agent-2-run"} (set @started))
                   "different agents begin without waiting for each other")
               (is (not-any? #{"agent-1-second"} @started)
                   "one agent's second call waits for its first")
               (@resolve-first!
                (result-message "agent-1-first" :agent-1-first))
               second-agent1-started))
            (.then
             (fn [_]
               (is (= ["agent-1-first" "agent-2-run" "agent-1-second"]
                      @started))
               completion))
            (.then
             (fn [results]
               (is (= [:agent-1-first :agent-2 :agent-1-second]
                      (mapv ::execution/result results))
                   "parallel grouping does not change caller position")))
            (.catch
             (fn [error]
               (is false (str "plan scheduling rejected: " error))))
            (.finally
             (fn []
               (set! execution/prepare-invocations! original-prepare)
               (set! host/invoke! original-invoke)
               (done))))))))

(deftest compiled-call-is-pinned-to-the-configured-artifact
  (async done
    (let [captured (atom nil)
          original-invoke host/invoke!
          _ (configure (fn [_] (:process (fake-process 301))))]
      (set! host/invoke!
            (fn [request]
              (reset! captured request)
              (js/Promise.resolve
               (result-message (::execution/invocation-id request) :ok))))
      (-> (host/invoke-compiled!
           database "agent-1" 'seon.execution.runtime/render-prompt! [:input])
          (.then
           (fn [result]
             (is (= :ok (::execution/result result)))
             (is (= database (:seon.db/db @captured)))
             (is (= digest
                    (get-in @captured
                            [::execution/function-identity
                             ::execution/artifact-digest])))
             (is (= 'seon.execution.runtime/render-prompt!
                    (get-in @captured
                            [::execution/function-identity
                             ::execution/function-symbol])))))
          (.catch
           (fn [error]
             (is false (str "compiled host call rejected: " error))))
          (.finally
           (fn []
             (set! host/invoke! original-invoke)
             (done)))))))

;; ============================================================
;; JVM agent host lane — the same message contract over Transit-UDS.
;; Tier assignment is data (`::host/eval-socket-path` on the agent);
;; these tests inject the tier lookup and fake the native socket through
;; the one `seon.db.transport.uds` connect seam.
;; ============================================================

(def ^:private !connect-native @#'uds/!connect-native)
(def ^:private frame-text-encoder (js/TextEncoder.))
(def ^:private frame-text-decoder (js/TextDecoder. "utf-8"))

(defn- host-frame
  "One length-prefixed frame carrying an encoded execution message."
  [message]
  (let [payload (.encode frame-text-encoder
                         (execution/encode-message message))
        n (.-byteLength payload)
        frame (js/Uint8Array. (+ 4 n))]
    (aset frame 0 (bit-and (unsigned-bit-shift-right n 24) 255))
    (aset frame 1 (bit-and (unsigned-bit-shift-right n 16) 255))
    (aset frame 2 (bit-and (unsigned-bit-shift-right n 8) 255))
    (aset frame 3 (bit-and n 255))
    (.set frame payload 4)
    frame))

(defn- written-message
  "Decode one complete written frame back into its execution message."
  [{::keys [frame]}]
  (execution/decode-message
   (.decode frame-text-decoder (.subarray ^js frame 4))))

(defn- fake-host-socket []
  (let [!handler (atom nil)
        !socket (atom nil)
        !options (atom nil)
        !writes (atom [])
        !close-count (atom 0)
        socket (js-obj
                "write"
                (fn [frame offset byte-count]
                  (swap! !writes conj
                         {::frame (.slice ^js frame offset
                                          (+ offset byte-count))})
                  byte-count)
                "close" (fn [] (swap! !close-count inc)))
        connect (fn [options]
                  (let [handler (aget options "socket")]
                    (reset! !options options)
                    (reset! !handler handler)
                    (reset! !socket socket)
                    ((aget handler "open") socket)
                    (js/Promise.resolve socket)))]
    {::connect connect
     ::handler !handler
     ::socket !socket
     ::options !options
     ::writes !writes
     ::close-count !close-count}))

(defn- host-inject!
  "Deliver one framed message as a native data chunk."
  [fixture message]
  ((aget @(::handler fixture) "data") @(::socket fixture)
   (host-frame message)))

(defn- host-close!
  "Fire the native close event — the host process died."
  [fixture]
  ((aget @(::handler fixture) "close") @(::socket fixture) nil))

(defn- host-ready-message []
  (assoc (ready-message) ::execution/bun-version "jvm-21.0.2"))

(defn- eval-batch-invocation [invocation-id]
  {::execution/message execution/invoke-message
   ::execution/protocol-version execution/protocol-version
   ::execution/agent-id "agent-1"
   ::execution/invocation-id invocation-id
   :seon.db/db database
   ::execution/function-identity
   {::execution/function-symbol 'seon.execution.runtime/eval-batch!
    ::execution/artifact-digest digest}
   ::execution/arguments [{:seon.eval/parsed []}]
   ::execution/deadline-ms 9999999999999
   ::execution/result-limit-bytes 4096})

(defn- configure-with-host! [spawn!]
  (host/configure!
   {::host/launch-descriptor (descriptor)
    ::host/javascript-runtime "bun"
    ::host/ready-timeout-ms 1000
    ::host/idle-timeout-ms 60000
    ::host/cancel-grace-ms 5
    ::host/eval-host-coordinate!
    (fn [_invocation] (js/Promise.resolve "tmp/fake-agent-host.sock"))
    ::host/spawn!
    (fn [request]
      (subprocess/start!
       (assoc request ::subprocess/spawn!
              (fn [^js options]
                (spawn! {::host/cmd (vec (js->clj (.-cmd options)))
                         ::host/ipc (.-ipc options)})))))}))

(defn- turn* []
  (js/Promise. (fn [resolve-promise _] (js/setTimeout resolve-promise 10))))

(deftest host-tier-eval-batch-rides-the-uds-stream-not-a-child
  (async done
    (let [fixture (fake-host-socket)
          prior-connect @!connect-native
          spawned (atom [])
          child (fake-process 401)
          spawn! (fn [value]
                   (swap! spawned conj value)
                   (:process child))]
      (reset! !connect-native (::connect fixture))
      (configure-with-host! spawn!)
      (let [completion (host/invoke! (eval-batch-invocation "host-eval-1"))]
        (-> (turn*)
            (.then
             (fn [_]
               (testing "the session opens at the agent's host coordinate
                         and the startup value is the FIRST frame"
                 (is (empty? @spawned)
                     "no Bun child spawns for a host-tier eval batch")
                 (is (= "tmp/fake-agent-host.sock"
                        (aget @(::options fixture) "unix")))
                 (let [startup (written-message (first @(::writes fixture)))]
                   (is (= "agent-1" (::execution/agent-id startup)))
                   (is (= digest (::execution/artifact-digest startup)))
                   (is (= "execution"
                          (::execution/shadow-build-id startup)))
                   (is (= "test-cluster"
                          (get-in startup
                                  [::execution/database-selection
                                   :seon.db/database-name])))))
               (host-inject! fixture (host-ready-message))
               (turn*)))
            (.then
             (fn [_]
               (testing "the ready echo admits the session and the invoke
                         frame follows on the same stream"
                 (is (= execution/invoke-message
                        (::execution/message
                         (written-message (second @(::writes fixture))))))
                 (let [processes (host/processes)
                       session (first processes)]
                   (is (= 1 (count processes)))
                   (is (= "tmp/fake-agent-host.sock"
                          (::host/eval-socket-path session)))
                   (is (not (contains? session ::host/pid))
                       "a host session has no child pid to sample")))
               (host-inject! fixture
                             (result-message "host-eval-1"
                                             {:seon.eval/ids []}))
               completion))
            (.then
             (fn [result]
               (is (= execution/result-message
                      (::execution/message result)))
               (is (= {:seon.eval/ids []} (::execution/result result)))
               (is (= database (:seon.db/db result)))))
            (.catch
             (fn [error]
               (is false (str "host lane invocation failed: " error))))
            (.finally
             (fn []
               (reset! !connect-native prior-connect)
               (done))))))))

(deftest host-session-death-mid-invocation-records-child-exited
  (async done
    (let [fixture (fake-host-socket)
          prior-connect @!connect-native
          spawn! (fn [_] (:process (fake-process 402)))]
      (reset! !connect-native (::connect fixture))
      (configure-with-host! spawn!)
      (let [completion (host/invoke! (eval-batch-invocation "host-eval-2"))]
        (-> (turn*)
            (.then
             (fn [_]
               (host-inject! fixture (host-ready-message))
               (turn*)))
            (.then
             (fn [_]
               (is (= execution/invoke-message
                      (::execution/message
                       (written-message (second @(::writes fixture))))))
               ;; kill -9 on the JVM host: the stream closes mid-invocation.
               (host-close! fixture)
               completion))
            (.then
             (fn [result]
               (testing "the pod synthesizes the contract child-exited
                         error value for the in-flight invocation"
                 (is (= execution/error-message
                        (::execution/message result)))
                 (is (= "The execution child exited before returning a result."
                        (get-in result [::execution/error
                                        :seon.error/message])))
                 (is (true? (get-in result [::execution/error
                                            :seon.error/data
                                            ::execution/child-retired?])))
                 (is (= database (:seon.db/db result))))
               (testing "the dead session is removed; the next invocation
                         reconnects instead of reusing it"
                 (is (empty? (host/processes)))
                 (let [next-completion
                       (host/invoke! (eval-batch-invocation "host-eval-3"))]
                   (-> (turn*)
                       (.then
                        (fn [_]
                          (let [startup (written-message
                                         (nth @(::writes fixture) 2))]
                            (is (= "agent-1"
                                   (::execution/agent-id startup))
                                "a fresh session re-sends startup"))
                          (host-inject! fixture (host-ready-message))
                          (turn*)))
                       (.then
                        (fn [_]
                          (host-inject!
                           fixture
                           (result-message "host-eval-3"
                                           {:seon.eval/ids []}))
                          next-completion))
                       (.then
                        (fn [next-result]
                          (is (= {:seon.eval/ids []}
                                 (::execution/result next-result))
                              "the agent's next turn works after restart"))))))))
            (.catch
             (fn [error]
               (is false (str "host death drill failed: " error))))
            (.finally
             (fn []
               (reset! !connect-native prior-connect)
               (done))))))))

(deftest render-invocations-stay-on-the-bun-child-for-host-tier-agents
  (async done
    (let [fixture (fake-host-socket)
          prior-connect @!connect-native
          spawned (atom [])
          child (fake-process 403)
          options (atom nil)
          spawn! (fn [value]
                   (reset! options value)
                   (swap! spawned conj value)
                   (:process child))]
      (reset! !connect-native (::connect fixture))
      (configure-with-host! spawn!)
      (let [completion (host/invoke! (invocation "render-1"))]
        (is (= 1 (count @spawned))
            "a non-eval invocation spawns the Bun child synchronously")
        (feed! @options (:process child) (ready-message))
        (-> (turn*)
            (.then
             (fn [_]
               (is (zero? (count @(::writes fixture)))
                   "no host session opens for a render invocation")
               (feed! @options (:process child)
                      (result-message "render-1" {:my.render/value 7}))
               completion))
            (.then
             (fn [result]
               (is (= {:my.render/value 7} (::execution/result result)))))
            (.catch
             (fn [error]
               (is false (str "render lane failed: " error))))
            (.finally
             (fn []
               (reset! !connect-native prior-connect)
               (done))))))))
