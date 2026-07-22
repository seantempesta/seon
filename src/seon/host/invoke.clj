(ns seon.host.invoke
  "Own invocation settlement, execution, cancellation, and shutdown."
  (:require [sci.core :as sci]
            [sci.ctx-store]
            [seon.error :as error]
            [seon.host.context :as context]
            [seon.host.eval :as eval]
            [seon.host.instrument :as instrument]
            [seon.host.sample :as sample]
            [seon.host.session :as session]
            [seon.schema :as schema])
  (:import [java.nio.channels SocketChannel]
           [java.util.concurrent ExecutorService ScheduledExecutorService TimeUnit]))

(set! *warn-on-reflection* true)

(def maximum-invocation-ms (* 10 60 1000))

(defn record-core-fault!
  "Record one core host fault."
  {:malli/schema [:=> [:cat :any] :nil]}
  [throwable]
  (error/record! {:seon.error/raw throwable :seon.error/fault :core})
  nil)

(defn- close-session-channel! [session]
  (try (.close ^SocketChannel (::session/channel session)) (catch Throwable _ nil))
  nil)

(defn- interrupt-evaluation! [session worker]
  (locking (::session/interrupt-lock session)
    (reset! (::session/interrupt-fired? session) true)
    (when (= :evaluating @(::session/worker-phase session))
      (.interrupt ^Thread worker)))
  nil)

(defn- invoke-authored!
  [session database function-symbol source-fingerprint arguments run-fence]
  (let [writer (::session/writer session)
        retained-ctx (::session/ctx session)
        agent-id (:seon.execution/agent-id @(::session/startup session))
        fence-result (eval/claim-run-fence! writer database agent-id run-fence)]
    (if (:seon/error fence-result)
      {:seon.eval/fenced? true}
      (instrument/call-with-read-admission
       (::instrument/state session)
       (fn []
         (context/verify-pinned-function! writer database function-symbol
                                          source-fingerprint)
         (let [live-var (sci/resolve retained-ctx function-symbol)
               [call-ctx function-var]
               (if (and live-var
                        (instrument/source-fingerprint-matches?
                         @live-var source-fingerprint))
                 [retained-ctx live-var]
                 (context/materialize-pinned-function!
                  writer retained-ctx database function-symbol
                  source-fingerprint
                  #(instrument/reconcile-ephemeral-vars!
                    (::instrument/state session) %)))]
           (sci.ctx-store/with-ctx call-ctx
             (apply @function-var arguments))))))))

(defn settle!
  "Send one terminal frame for the active invocation exactly once."
  {:malli/schema [:=> [:cat ::session/session :map :map]
                  [:or :nil :boolean]]}
  [session token message]
  (let [active (::session/active session)
        prepared (session/prepare-frame message)]
    (when (compare-and-set! active token nil)
      (try
        (session/write-prepared-frame! session prepared)
        true
        (catch Throwable throwable
          (record-core-fault! throwable)
          (close-session-channel! session)
          false)))))

(defn- run-invocation!
  "Execute one claimed invocation on the calling pool thread."
  [session token invocation]
  ;; Cancellation revokes this exact invocation generation before touching
  ;; its Future. If the pool won the FutureTask start race, it still cannot
  ;; acquire policy, create receipts, evaluate, or record after settlement.
  (when (identical? token @(::session/active session))
    (let [{invocation-id :seon.execution/invocation-id
           database :seon.db/db
           identity-value :seon.execution/function-identity
           arguments :seon.execution/arguments
           run-fence :seon.execution/run-fence
           result-limit :seon.execution/result-limit-bytes} invocation
          function-symbol (:seon.execution/function-symbol identity-value)
          compiled? (contains? identity-value
                               :seon.execution/artifact-digest)
          worker (Thread/currentThread)
          remaining (min maximum-invocation-ms
                         (max 1 (- (:seon.execution/deadline-ms invocation)
                                   (session/now-ms))))
          watchdog ^ScheduledExecutorService (::session/watchdog session)
          deadline-task (.schedule watchdog
                                   ^Runnable #(interrupt-evaluation! session worker)
                                   (long remaining) TimeUnit/MILLISECONDS)
          outcome
          (try
            (cond
            (and compiled?
                 (not= (:seon.execution/artifact-digest identity-value)
                       (get-in @(::session/startup session)
                               [:seon.execution/artifact-digest])))
            {::error (session/error-value
                      "The compiled function identity is not trusted by this artifact."
                      :core-bug)}

            (not compiled?)
            {::value
             (invoke-authored!
              session database function-symbol
              (:seon.execution/source-digest identity-value)
              arguments (or run-fence {}))}

            (= function-symbol 'seon.execution.runtime/eval-batch!)
              (let [sampling-limits (sample/acquire-sampling-policy!
                                   (::session/writer session) database)
                  result (binding [context/*agent-id*
                                   (:seon.execution/agent-id
                                    @(::session/startup session))]
                           (eval/eval-batch-result session (first arguments)
                                              sampling-limits database
                                              (or run-fence {})))]
              (if (and (eval/interrupted-batch? result)
                       @(::session/cancel-requested? session))
                {::error (session/error-value "The invocation was canceled." :agent)}
                (if (eval/interrupted-batch? result)
                  {::error (eval/interrupted-error result)}
                  {::value result})))

            :else
            ;; render-prompt!/render-agent-view! remain pod-served: the
            ;; host serves EVAL; the pod keeps rendering (design §1).
            {::error (session/error-value
                      (str "The JVM host does not serve " function-symbol
                           "; prompt and view rendering stay on the pod.")
                      :core-bug)})
            (catch Throwable throwable
              {::error
               (eval/classified-error-value
                (::session/ctx session)
                (eval/agent-home-ns
                 (:seon.execution/agent-id @(::session/startup session)))
                throwable)})
            (finally
              (.cancel deadline-task false)
              (locking (::session/interrupt-lock session)
                (reset! (::session/worker-phase session) :idle)
                (Thread/interrupted))))]
      (settle!
       session token
       (if-let [error (::error outcome)]
         (session/error-frame invocation-id error database)
         (let [bounded (session/bounded-result (::value outcome) result-limit)]
           (if (::session/ok? bounded)
             (session/result-frame invocation-id database (::session/value bounded)
                           (::session/result-bytes bounded))
             (session/error-frame invocation-id (::session/error bounded) database))))))))

(defn begin-invocation!
  "Begin one admitted invocation."
  {:malli/schema [:=> [:cat ::session/session :map] :any]}
  [session invocation]
  (let [{invocation-id :seon.execution/invocation-id
         agent-id :seon.execution/agent-id
         database :seon.db/db} invocation
        startup @(::session/startup session)
        remaining (- (:seon.execution/deadline-ms invocation) (session/now-ms))]
    (cond
      (not= (:seon.execution/agent-id startup) agent-id)
      (session/send-frame! session
                   (session/error-frame invocation-id
                                (session/error-value
                                 "The invocation names another agent."
                                 :core-bug)
                                database))

      (some? @(::session/active session))
      (session/send-frame!
       session
       (session/error-frame invocation-id
                    (session/error-value
                     "The execution child already has an active invocation."
                     :core-bug)
                    database))

      (not (pos? remaining))
      (session/send-frame! session
                   (session/error-frame invocation-id
                                (session/error-value
                                 "The invocation deadline has elapsed."
                                 :agent)
                                database))

      :else
      (let [token {::invocation invocation ::started-at (session/now-ms)}]
        (reset! (::session/active session) token)
        (reset! (::session/cancel-requested? session) false)
        (reset! (::session/interrupt-fired? session) false)
        (let [worker-holder (promise)
              submitted
              (.submit ^ExecutorService (::session/eval-pool session)
                       ^Runnable
                       (fn []
                         (deliver worker-holder (Thread/currentThread))
                         (run-invocation! session token invocation)))]
          (reset! (::session/active-run session)
                  {::future submitted ::worker worker-holder}))))))

(defn cancel-active!
  "Settle a matching active invocation with the canceled error value."
  {:malli/schema [:=> [:cat ::session/session :string] [:or :nil :boolean]]}
  [session invocation-id]
  (when-let [token @(::session/active session)]
    (when (= invocation-id
             (get-in token [::invocation :seon.execution/invocation-id]))
      (reset! (::session/cancel-requested? session) true)
      (when (settle! session token
                     (session/error-frame
                      invocation-id
                      (session/error-value "The invocation was canceled." :agent)
                      (get-in token [::invocation :seon.db/db])))
        (when-let [{::keys [worker future]} @(::session/active-run session)]
          (if (realized? worker)
            (interrupt-evaluation! session @worker)
            (.cancel ^java.util.concurrent.Future future false))
          ;; Bound the wait so a wedged native call cannot wedge the reader.
          (try (.get ^java.util.concurrent.Future future
                     2000 TimeUnit/MILLISECONDS)
               (catch Throwable _ nil))))
      true)))

(defn shutdown-session!
  "Cancel active work, park the agent context, acknowledge, and close."
  {:malli/schema [:=> [:cat ::session/session] :nil]}
  [session]
  (when-let [token @(::session/active session)]
    (cancel-active!
     session
     (get-in token [::invocation :seon.execution/invocation-id])))
  ;; Park = drop: restore forks the base and replays defs from the corpus.
  (when-let [agent-id (:seon.execution/agent-id @(::session/startup session))]
    (swap! (::session/contexts session) dissoc agent-id))
  (reset! (::session/live-values session) {::session/order [] ::session/values {}})
  (session/send-frame! session {:seon.execution/message session/stopped-message
                        :seon.execution/protocol-version session/protocol-version})
  nil)
