(ns seon.execution.host
  "Native Bun supervision for flavor-owned agent execution children."
  (:require
   [seon.db.branch :as branch]
   [seon.db.protocol :as db.protocol]
   [seon.execution :as execution]
   [seon.launch :as launch]
   [seon.schema :as schema]
   [seon.subprocess :as subprocess]))

(def ^:private default-ready-timeout-ms 10000)
(def ^:private default-idle-timeout-ms 300000)
(def ^:private default-cancel-grace-ms 1000)
(def ^:private maximum-tail-characters (* 16 1024))

(schema/register! ::javascript-runtime [:string {:min 1}])
(schema/register! ::ready-timeout-ms [:int {:min 1}])
(schema/register! ::idle-timeout-ms [:int {:min 1}])
(schema/register! ::cancel-grace-ms [:int {:min 1}])
(schema/register! ::spawn! 'fn?)
(schema/register! ::run-fence-current? 'fn?)
(schema/register! ::pid :int)
(schema/register! ::ready? :boolean)
(schema/register! ::retiring? :boolean)
(schema/register! ::stdout-tail :string)
(schema/register! ::stderr-tail :string)
(schema/register! ::started-at :inst)
(schema/register! ::elapsed-ms :int)
(schema/register!
 ::resource-usage
 [:map {:closed true}
  [::subprocess/rss-bytes :int]
  [::subprocess/max-rss-bytes {:optional true} :int]
  [::subprocess/cpu-time
   {:optional true}
   [:map {:closed true}
    [::subprocess/user :int]
    [::subprocess/system :int]
    [::subprocess/total :int]]]])
(schema/register!
 ::invocation
 [:map {:closed true}
  [::execution/invocation-id ::execution/invocation-id]
  [::execution/function-identity ::execution/function-identity]
  [::execution/deadline-ms ::execution/deadline-ms]
  [::started-at ::started-at]])
(schema/register!
 ::process
 [:map {:closed true}
  [::execution/agent-id ::execution/agent-id]
  [::pid ::pid]
  [::artifact-digest ::execution/artifact-digest]
  [::ready? ::ready?]
  [::retiring? ::retiring?]
  [::stdout-tail ::stdout-tail]
  [::stderr-tail ::stderr-tail]
  [::resource-usage {:optional true} ::resource-usage]
  [::invocation {:optional true} ::invocation]])
(schema/register! ::processes [:vector ::process])
(schema/register!
 ::configure-request
 [:map {:closed true}
  [::launch-descriptor ::launch/descriptor]
  [::javascript-runtime ::javascript-runtime]
  [::ready-timeout-ms {:optional true} ::ready-timeout-ms]
  [::idle-timeout-ms {:optional true} ::idle-timeout-ms]
  [::cancel-grace-ms {:optional true} ::cancel-grace-ms]
  [::spawn! {:optional true} ::spawn!]
  [::run-fence-current? {:optional true} ::run-fence-current?]])

(defonce ^:private !host (atom {::generation 0 ::children {}}))
(defonce ^:private !invocation-tails (atom {}))

(defn- deferred []
  (let [resolve! (atom nil)
        promise (js/Promise.
                 (fn [resolve-promise _]
                   (reset! resolve! resolve-promise)))]
    {::promise promise ::resolve! @resolve!}))

(defn- host-error
  ([invocation message] (host-error invocation message {}))
  ([invocation message data]
   (cond-> {::execution/message execution/error-message
            ::execution/protocol-version execution/protocol-version
            ::execution/invocation-id (::execution/invocation-id invocation)
            ::execution/error {:seon.error/message message
                               :seon.error/kind :core-bug
                               :seon.error/data data}}
     (:seon.db/db invocation)
     (assoc :seon.db/db (:seon.db/db invocation)))))

(defn- canceled-error
  ([invocation] (canceled-error invocation nil))
  ([invocation diagnostic]
   {::execution/message execution/error-message
    ::execution/protocol-version execution/protocol-version
    ::execution/invocation-id (::execution/invocation-id invocation)
    :seon.db/db (:seon.db/db invocation)
    ::execution/error
    (cond-> {:seon.error/message "The invocation was canceled."
             :seon.error/kind :agent}
      diagnostic
      (assoc :seon.error/data diagnostic))}))

(defn- append-tail [tail text]
  (let [combined (str tail text)
        length (count combined)]
    (if (<= length maximum-tail-characters)
      combined
      (subs combined (- length maximum-tail-characters)))))

(defn- send-message! [control message]
  ((::subprocess/send! control) (execution/encode-message message)))

(defn- kill-process! [control]
  ((::subprocess/kill! control) "SIGKILL"))

(defn- host-configuration [] (::configuration @!host))
(defn- child [agent-id] (get-in @!host [::children agent-id]))

(defn- child-evidence [current]
  (let [usage ((::subprocess/resource-usage! (::control current)))
        started-at (get-in current [::active ::started-at])]
    (cond-> {::pid (::pid current)
             ::artifact-digest (::artifact-digest current)
             ::stdout-tail (::stdout-tail current)
             ::stderr-tail (::stderr-tail current)}
      usage (assoc ::resource-usage usage)
      started-at (assoc ::elapsed-ms
                        (max 0 (- (.now js/Date) (.getTime started-at)))))))

(defn- exit-evidence [current result]
  (cond-> (child-evidence current)
    (some? (::subprocess/exit result))
    (assoc ::exit-code (::subprocess/exit result))
    (::subprocess/signal result)
    (assoc ::signal (::subprocess/signal result))
    (::subprocess/resource-usage result)
    (assoc ::resource-usage (::subprocess/resource-usage result))))

(defn processes
  "Return one demanded ordinary-data snapshot of supervised execution children.

   Sampling is parent-owned and synchronous; it does not ask the child event
   loop to cooperate and does not retain or transact healthy measurements."
  {:malli/schema [:=> [:cat] ::processes]}
  []
  (->> (::children @!host)
       (map (fn [[agent-id current]]
              (let [active (::active current)
                    invocation (::invocation active)
                    usage ((::subprocess/resource-usage! (::control current)))]
                (cond-> {::execution/agent-id agent-id
                         ::pid (::pid current)
                         ::artifact-digest (::artifact-digest current)
                         ::ready? (boolean (::ready? current))
                         ::retiring? (boolean (::retiring? current))
                         ::stdout-tail (::stdout-tail current)
                         ::stderr-tail (::stderr-tail current)}
                  usage (assoc ::resource-usage usage)
                  active
                  (assoc ::invocation
                         {::execution/invocation-id
                          (::execution/invocation-id invocation)
                          ::execution/function-identity
                          (::execution/function-identity invocation)
                          ::execution/deadline-ms
                          (::execution/deadline-ms invocation)
                          ::started-at (::started-at active)})))))
       (sort-by ::execution/agent-id)
       vec))

(defn- same-child?
  [agent-id generation child-id]
  (let [current (child agent-id)]
    (and (= generation (::generation current))
         (= child-id (::child-id current)))))

(declare cancel! stop-child! settle-active!)

(defn- remove-child!
  [agent-id generation child-id]
  (swap! !host
         (fn [host]
           (if (same-child? agent-id generation child-id)
             (update host ::children dissoc agent-id)
             host))))

(defn- exit-child!
  [agent-id generation child-id result]
  (when (same-child? agent-id generation child-id)
    (let [current (child agent-id)
          active (::active current)
          exit (::exit current)]
      (when-let [timer (::ready-timer current)] (js/clearTimeout timer))
      (when-let [timer (::idle-timer current)] (js/clearTimeout timer))
      (when-let [timer (::kill-timer current)] (js/clearTimeout timer))
      (when-not (::ready? current)
        ((get-in current [::ready ::resolve!])
         (host-error
          {::execution/invocation-id "startup"}
          "The execution child exited before becoming ready."
          (assoc (exit-evidence current result)
                 ::execution/child-retired? true))))
      (when active
        ((::resolve! active)
         (host-error
          (::invocation active)
          "The execution child exited before returning a result."
          (assoc (exit-evidence current result)
                 ::execution/child-retired? true
                 :seon.db/db
                 (get-in active [::invocation :seon.db/db])))))
      (remove-child! agent-id generation child-id)
      ((::resolve! exit) (::subprocess/exit result)))))

(defn- schedule-idle-stop!
  [agent-id generation child-id]
  (let [timeout-ms (get-in (host-configuration) [::idle-timeout-ms])
        timer (js/setTimeout
               (fn []
                 (when (and (same-child? agent-id generation child-id)
                            (nil? (::active (child agent-id))))
                   (stop-child! agent-id)))
               timeout-ms)]
    (swap! !host assoc-in [::children agent-id ::idle-timer] timer)))

(defn- settle-active!
  [agent-id generation child-id message]
  (let [accepted (atom nil)]
    (swap! !host
           (fn [host]
             (let [current (get-in host [::children agent-id])
                   active (::active current)]
               (if (and (= generation (::generation current))
                        (= child-id (::child-id current))
                        (= (::execution/invocation-id message)
                           (get-in active
                                   [::invocation
                                    ::execution/invocation-id])))
                 (do
                   (reset! accepted
                           {::active active
                            ::retiring? (::retiring? current)})
                   (update-in host [::children agent-id] dissoc ::active))
                 host))))
    (when-let [{::keys [active retiring?]} @accepted]
      ((::resolve! active) message)
      (when-not retiring?
        (schedule-idle-stop! agent-id generation child-id))
      true)))

(defn- ready-message-valid?
  [config agent-id message]
  (let [runtime (get-in config [::launch-descriptor ::launch/runtime])
        database (get-in config [::launch-descriptor ::launch/database])]
    (and (execution/valid-child-message? message)
         (= execution/ready-message (::execution/message message))
         (= agent-id (::execution/agent-id message))
         (= (::launch/execution-build-id runtime)
            (::execution/shadow-build-id message))
         (= (::launch/execution-digest runtime)
            (::execution/artifact-digest message))
         (= (::db.protocol/database-name database)
            (:db-name (:seon.db/db message))))))

(defn- result-current?
  [config active message]
  (let [invocation (::invocation active)
        runtime (get-in config [::launch-descriptor ::launch/runtime])
        current-run-fence? (::run-fence-current? config)]
    (and (= (::artifact-digest active) (::launch/execution-digest runtime))
         (= (:seon.db/db invocation)
            (:seon.db/db message))
         (or (nil? (::execution/run-fence invocation))
             (current-run-fence? (::execution/run-fence invocation))))))

(defn- receive!
  [agent-id generation child-id encoded]
  (when (and (string? encoded) (same-child? agent-id generation child-id))
    (try
      (let [message (execution/decode-message encoded)
            current (child agent-id)]
        (case (::execution/message message)
          :seon.execution.message/ready
          (let [ready (::ready current)]
            (if (ready-message-valid? (host-configuration) agent-id message)
              (do
                (js/clearTimeout (::ready-timer current))
                (swap! !host assoc-in [::children agent-id ::ready?] true)
                ((::resolve! ready) current))
              (do
                ((::resolve! ready)
                 (host-error {::execution/invocation-id "startup"}
                             "The execution child reported another artifact."))
                (kill-process! (::control current)))))

          (:seon.execution.message/result :seon.execution.message/error)
          (if (and (= execution/error-message (::execution/message message))
                   (not (::ready? current))
                   (= "startup" (::execution/invocation-id message)))
            ((get-in current [::ready ::resolve!]) message)
            (when-let [active (::active current)]
              (if (result-current? (host-configuration) active message)
                (settle-active! agent-id generation child-id message)
                (settle-active!
                 agent-id generation child-id
                 (host-error (::invocation active)
                             "The execution result is no longer current.")))))

          nil))
      (catch :default _
        (when-let [control (::control (child agent-id))]
          (kill-process! control))))))

(defn- startup-value
  [config agent-id]
  (let [descriptor (::launch-descriptor config)
        runtime (::launch/runtime descriptor)
        database (::launch/database descriptor)
        writer (::launch/writer-owner descriptor)]
    {::execution/protocol-version execution/protocol-version
     ::execution/agent-id agent-id
     ::execution/artifact-digest (::launch/execution-digest runtime)
     ::execution/shadow-build-id (::launch/execution-build-id runtime)
     ::execution/database-selection
     (cond-> {:seon.db/socket-path (::launch/request-socket-path writer)
              :seon.db/database-name (::db.protocol/database-name database)
              :seon.db/backend (::db.protocol/backend database)}
       (::db.protocol/database-path database)
       (assoc :seon.db/database-path
              (::db.protocol/database-path database))
       (::branch/connection-id database)
       (assoc :seon.db/connection-id (::branch/connection-id database)))}))

(defn- spawn-child!
  [agent-id]
  (try
    (let [config (host-configuration)
          generation (::generation @!host)
          runtime (get-in config [::launch-descriptor ::launch/runtime])
          startup (startup-value config agent-id)
          ready (deferred)
          start! (or (::spawn! config) subprocess/start!)
          control
          (start!
           {::subprocess/cmd
             [(:seon.execution.host/javascript-runtime config)
              (::launch/execution-output runtime)
              (execution/encode-message startup)]
             ::subprocess/ipc
             (fn [message child-id]
               (receive! agent-id generation child-id message))
             ::subprocess/on-out
             #(swap! !host update-in [::children agent-id ::stdout-tail]
                     append-tail %)
             ::subprocess/on-err
             #(swap! !host update-in [::children agent-id ::stderr-tail]
                     append-tail %)
             ::subprocess/capture-output? false})
          child-id (::subprocess/id control)
          timeout
          (js/setTimeout
           (fn []
             (when (and (same-child? agent-id generation child-id)
                        (not (::ready? (child agent-id))))
               ((::resolve! ready)
                (host-error {::execution/invocation-id "startup"}
                            "The execution child did not become ready."))
               (kill-process! control)))
           (::ready-timeout-ms config))
          exit (deferred)
          state {::generation generation
                 ::child-id child-id
                 ::control control
                 ::pid (::subprocess/pid control)
                 ::exit exit
                 ::artifact-digest (::launch/execution-digest runtime)
                 ::ready ready
                 ::ready-timer timeout
                 ::ready? false
                 ::stdout-tail ""
                 ::stderr-tail ""}]
      (swap! !host assoc-in [::children agent-id] state)
      (-> (::subprocess/exited control)
          (.then #(exit-child! agent-id generation child-id %))
          (.catch #(exit-child! agent-id generation child-id
                                {::subprocess/exit -1})))
      (::promise ready))
    (catch :default error
      (js/Promise.resolve
       (host-error {::execution/invocation-id "startup"}
                   "The execution child could not be spawned."
                   {:seon.error/cause (ex-message error)})))))

(defn- retire-child!
  [current grace-ms]
  (let [control (::control current)]
    (when-let [timer (::ready-timer current)] (js/clearTimeout timer))
    (when-let [timer (::idle-timer current)] (js/clearTimeout timer))
    (when-let [timer (::kill-timer current)]
      (js/clearTimeout timer))
    (when-not (::ready? current)
      ((get-in current [::ready ::resolve!])
       (host-error {::execution/invocation-id "startup"}
                   "The execution host configuration changed.")))
    (when-let [active (::active current)]
      ((::resolve! active)
       (host-error (::invocation active)
                   "The execution host configuration changed.")))
    (when
     (send-message!
      control
      {::execution/message execution/shutdown-message
       ::execution/protocol-version execution/protocol-version})
      (kill-process! control))
    (js/setTimeout #(kill-process! control) grace-ms)))

(defn- ensure-child!
  [agent-id]
  (if-let [current (child agent-id)]
    (cond
      (::retiring? current)
      (-> (::promise (::exit current))
          (.then (fn [_] (ensure-child! agent-id))))

      (::ready? current)
      (js/Promise.resolve current)

      :else
      (::promise (::ready current)))
    (spawn-child! agent-id)))

(defn configure!
  "Configure the one Bun execution-child supervisor."
  {:malli/schema [:=> [:cat ::configure-request] :boolean]}
  [{::keys [launch-descriptor javascript-runtime ready-timeout-ms
            idle-timeout-ms cancel-grace-ms spawn! run-fence-current?]}]
  (let [runtime (::launch/runtime launch-descriptor)]
    (when-not (and (::launch/execution-build-id runtime)
                   (::launch/execution-output runtime)
                   (::launch/execution-digest runtime))
      (throw (ex-info "The launch has no complete execution artifact."
                      {::launch-descriptor launch-descriptor
                       :seon.error/kind :core-bug})))
    (let [previous @!host
          grace-ms (or (get-in previous [::configuration ::cancel-grace-ms])
                       default-cancel-grace-ms)]
      (doseq [current (vals (::children previous))]
        (retire-child! current grace-ms)))
    (swap! !host
           (fn [host]
             {::generation (inc (::generation host))
              ::configuration
              {::launch-descriptor launch-descriptor
               ::javascript-runtime javascript-runtime
               ::ready-timeout-ms (or ready-timeout-ms
                                      default-ready-timeout-ms)
               ::idle-timeout-ms (or idle-timeout-ms default-idle-timeout-ms)
               ::cancel-grace-ms (or cancel-grace-ms default-cancel-grace-ms)
               ::spawn! spawn!
               ::run-fence-current? (or run-fence-current? (constantly true))}
              ::children {}}))
    (reset! !invocation-tails {})
    true))

(defn- invoke-once!
  "Run one invocation in its agent's supervised Bun child."
  {:malli/schema [:=> [:cat :seon.execution/invoke] :any]}
  [invocation]
  (let [agent-id (::execution/agent-id invocation)]
    (-> (ensure-child! agent-id)
        (.then
         (fn [ready]
           (if (::execution/message ready)
             ready
             (let [claimed (atom nil)
                   decision (atom :stale)
                   completion (deferred)]
               (swap! !host
                      (fn [host]
                        (let [current (get-in host [::children agent-id])]
                          (if (and current
                                   (::ready? current)
                                   (not (::retiring? current))
                                   (= (::generation ready)
                                      (::generation current))
                                   (= (::child-id ready)
                                      (::child-id current)))
                            (if (::active current)
                              (do (reset! decision :busy) host)
                              (do
                                (reset! decision :claimed)
                                (reset! claimed current)
                                (when-let [timer (::idle-timer current)]
                                  (js/clearTimeout timer))
                                (assoc-in host [::children agent-id ::active]
                                          {::invocation invocation
                                           ::artifact-digest
                                           (::artifact-digest current)
                                           ::started-at (js/Date.)
                                           ::resolve! (::resolve! completion)})))
                            host))))
               (case @decision
                 :claimed
                 (let [current @claimed
                       control (::control current)
                       child-id (::child-id current)]
                   (if-let [send-error
                            (send-message! control invocation)]
                     (let [message
                           (host-error
                            invocation
                            "The execution invocation could not be sent."
                            {:seon.error/cause
                             (or (:seon.error/message send-error)
                                 (get-in send-error
                                         [:seon.error/data
                                          :seon.error/cause]))
                             ::pid (::pid current)
                             ::stdout-tail (::stdout-tail current)
                             ::stderr-tail (::stderr-tail current)})]
                       (settle-active! agent-id (::generation current)
                                       child-id message)
                       (kill-process! control)
                       (::promise completion))
                     (::promise completion)))

                 :busy
                 (host-error invocation
                             "The agent already has an active invocation.")

                 (host-error invocation
                             "The execution child is no longer current.")))))))))

(defn- reload-required? [message]
  (true? (get-in message [::execution/error :seon.error/data
                          ::execution/reload-required?])))

(defn- invoke-now!
  "Run the head invocation, replacing a source-stale child once."
  [invocation]
  (let [agent-id (::execution/agent-id invocation)]
    (-> (invoke-once! invocation)
        (.then
         (fn [message]
           (if-not (reload-required? message)
             message
             (let [current (child agent-id)]
               (when current
                 (kill-process! (::control current))
                 (remove-child! agent-id (::generation current)
                                (::child-id current)))
               (invoke-once! invocation)))))
        (.catch
         (fn [exception]
           (let [diagnostic (get (ex-data exception) :seon.error/data)]
             (host-error
              invocation
              "The execution host invocation failed."
              (cond-> {:seon.error/cause (ex-message exception)}
                (map? diagnostic) (merge diagnostic)))))))))

(defn invoke!
  "Queue one invocation in its agent's child; agents remain parallel."
  {:malli/schema [:=> [:cat :seon.execution/invoke] :any]}
  [invocation]
  (let [agent-id (::execution/agent-id invocation)
        invocation-id (::execution/invocation-id invocation)
        completion (deferred)
        started? (atom false)
        expired? (atom false)
        remaining (min execution/maximum-invocation-ms
                       (max 0 (- (::execution/deadline-ms invocation)
                                 (.now js/Date))))
        timer
        (js/setTimeout
         (fn []
           (reset! expired? true)
           ;; A head invocation may be active or still waiting for child
           ;; readiness. Retire only that head's child; queued invocations
           ;; time out without disturbing the active predecessor. A successful
           ;; cancel settles through the active invocation so its process
           ;; evidence is not replaced by this queue-level fallback.
           (when-not (and @started? (cancel! agent-id invocation-id true))
             (when @started? (stop-child! agent-id))
             ((::resolve! completion)
              (canceled-error
               invocation
               (when @started? {::execution/child-retired? true})))))
         remaining)
        queued (atom nil)]
    (swap! !invocation-tails
           (fn [tails]
             (let [prior (get tails agent-id)
                   work
                   (if prior
                     (.then
                      prior
                      (fn [_]
                        (if @expired?
                          (canceled-error invocation)
                          (do
                            (reset! started? true)
                            (invoke-now! invocation)))))
                     (do
                       (reset! started? true)
                       (invoke-now! invocation)))]
               (reset! queued work)
               (assoc tails agent-id work))))
    (let [work @queued]
      (-> work
          (.then
           (fn [message]
             (js/clearTimeout timer)
             ((::resolve! completion) message)
             message))
          (.finally
           (fn []
             (swap! !invocation-tails
                    (fn [tails]
                      (if (identical? work (get tails agent-id))
                        (dissoc tails agent-id)
                        tails))))))
      (::promise completion))))

(defn ^:async invoke-plans!
  "Prepare and execute ordinary authored calls at one database value."
  {:malli/schema [:=> [:cat :seon.db/db
                       :seon.execution/invocation-plans]
                  [:vector :map]]}
  [database plans]
  (let [invocations
        (await
         (execution/prepare-invocations!
          {:seon.db/db database
           ::execution/invocation-plans plans}))]
    (let [groups (vals (group-by (comp ::execution/agent-id second)
                                 (map-indexed vector invocations)))
          run-group
          (fn [indexed]
            (reduce
             (fn [pending [index invocation]]
               (.then pending
                      (fn [results]
                        (-> (invoke! invocation)
                            (.then #(conj results [index %]))))))
             (js/Promise.resolve [])
             indexed))
          grouped-results
          (await (js/Promise.all (clj->js (mapv run-group groups))))]
      (->> (array-seq grouped-results)
           (apply concat)
           (sort-by first)
           (mapv second)))))

(defn ^:async invoke-compiled!
  "Invoke one trusted function from the digest-verified execution artifact."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/db :seon.execution/agent-id
          :seon.execution/function-symbol :seon.execution/arguments]
     :map]
    [:=> [:cat :seon.db/db :seon.execution/agent-id
          :seon.execution/function-symbol :seon.execution/arguments
          [:or :nil :seon.execution/run-fence]]
     :map]]}
  ([database agent-id function-symbol arguments]
   (await (invoke-compiled! database agent-id function-symbol arguments nil)))
  ([database agent-id function-symbol arguments run-fence]
   (let [runtime (get-in (host-configuration)
                         [::launch-descriptor ::launch/runtime])
         invocation
         (execution/compiled-invocation
          agent-id function-symbol arguments database
          (::launch/execution-digest runtime) run-fence)]
     (await (invoke! invocation)))))

(defn cancel!
  "Cancel one active invocation and bound non-cooperative shutdown."
  {:malli/schema
   [:function
    [:=> [:cat ::execution/agent-id ::execution/invocation-id] :boolean]
    [:=> [:cat ::execution/agent-id ::execution/invocation-id :boolean]
     :boolean]]}
  ([agent-id invocation-id]
   (cancel! agent-id invocation-id false))
  ([agent-id invocation-id child-retired?]
  (if-let [current (child agent-id)]
    (if (= invocation-id
           (get-in current [::active ::invocation
                            ::execution/invocation-id]))
      (let [control (::control current)
            child-id (::child-id current)
            generation (::generation current)
            timer (js/setTimeout
                   (fn []
                     (when (same-child? agent-id generation child-id)
                       (kill-process! control)))
                   (get-in (host-configuration) [::cancel-grace-ms]))]
        (swap! !host
               (fn [host]
                 (-> host
                     (assoc-in [::children agent-id ::retiring?] true)
                     (assoc-in [::children agent-id ::kill-timer] timer))))
        (settle-active! agent-id generation child-id
                        (canceled-error
                         (get-in current [::active ::invocation])
                         (when child-retired?
                           (assoc (child-evidence current)
                                  ::execution/child-retired? true))))
        (when
         (send-message!
          control
          {::execution/message execution/cancel-message
           ::execution/protocol-version execution/protocol-version
           ::execution/invocation-id invocation-id})
          (kill-process! control))
        true)
      false)
    false)))

(defn stop-child!
  "Ask one agent child to stop and kill it after the shutdown grace."
  {:malli/schema [:=> [:cat ::execution/agent-id] :boolean]}
  [agent-id]
  (if-let [current (child agent-id)]
    (let [control (::control current)
          child-id (::child-id current)
          generation (::generation current)]
      (swap! !host assoc-in [::children agent-id ::retiring?] true)
      (when
       (send-message!
        control
        {::execution/message execution/shutdown-message
         ::execution/protocol-version execution/protocol-version})
        (kill-process! control))
      (js/setTimeout
       (fn []
         (when (same-child? agent-id generation child-id)
           (kill-process! control)))
       (get-in (host-configuration) [::cancel-grace-ms]))
      true)
    false))

(defn stop!
  "Stop every supervised execution child."
  {:malli/schema [:=> [:cat] :int]}
  []
  (let [agent-ids (keys (::children @!host))]
    (doseq [agent-id agent-ids] (stop-child! agent-id))
    (count agent-ids)))
