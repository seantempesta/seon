(ns seon.execution.host
  "Native Bun supervision for flavor-owned agent execution children."
  (:require
   [seon.db.coordinate :as coordinate]
   [seon.db.protocol :as db.protocol]
   [seon.execution :as execution]
   [seon.launch :as launch]
   [seon.schema :as schema]))

(def ^:private default-ready-timeout-ms 10000)
(def ^:private default-idle-timeout-ms 30000)
(def ^:private default-cancel-grace-ms 1000)
(def ^:private maximum-tail-characters (* 16 1024))

(schema/register! ::javascript-runtime [:string {:min 1}])
(schema/register! ::ready-timeout-ms [:int {:min 1}])
(schema/register! ::idle-timeout-ms [:int {:min 1}])
(schema/register! ::cancel-grace-ms [:int {:min 1}])
(schema/register! ::spawn! 'fn?)
(schema/register! ::run-fence-current? 'fn?)
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
(defonce ^:private text-decoder (js/TextDecoder. "utf-8"))

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
     (::execution/coordinate invocation)
     (assoc ::execution/coordinate (::execution/coordinate invocation)))))

(defn- canceled-error [invocation]
  {::execution/message execution/error-message
   ::execution/protocol-version execution/protocol-version
   ::execution/invocation-id (::execution/invocation-id invocation)
   ::execution/coordinate (::execution/coordinate invocation)
   ::execution/error {:seon.error/message "The invocation was canceled."
                      :seon.error/kind :agent}})

(defn- append-tail [tail text]
  (let [combined (str tail text)
        length (count combined)]
    (if (<= length maximum-tail-characters)
      combined
      (subs combined (- length maximum-tail-characters)))))

(defn- pump-stream!
  [stream append!]
  (when (and stream (fn? (.-getReader stream)))
    (let [reader (.getReader stream)]
      (letfn [(step []
                (-> (.read reader)
                    (.then
                     (fn [read]
                       (when-not (.-done read)
                         (append! (.decode text-decoder (.-value read)
                                           #js {:stream true}))
                         (step))))
                    (.catch (fn [_] nil))))]
        (step)))))

(defn- native-spawn! [options]
  (js-invoke js/Bun "spawn"
             #js {:cmd (clj->js (::cmd options))
                  :ipc (::ipc options)
                  :stdout (::stdout options)
                  :stderr (::stderr options)}))

(defn- send-message! [^js process message]
  (try
    (.send process (execution/encode-message message))
    true
    (catch :default _ false)))

(defn- kill-process! [^js process]
  (try
    (.kill process "SIGKILL")
    true
    (catch :default _ false)))

(defn- host-configuration [] (::configuration @!host))
(defn- child [agent-id] (get-in @!host [::children agent-id]))

(defn- same-child?
  [agent-id generation process]
  (let [current (child agent-id)]
    (and (= generation (::generation current))
         (identical? process (::process current)))))

(declare cancel! stop-child! settle-active!)

(defn- remove-child!
  [agent-id generation process]
  (swap! !host
         (fn [host]
           (if (same-child? agent-id generation process)
             (update host ::children dissoc agent-id)
             host))))

(defn- exit-child!
  [agent-id generation ^js process exit-code]
  (when (same-child? agent-id generation process)
    (let [current (child agent-id)
          active (::active current)]
      (when-let [timer (::ready-timer current)] (js/clearTimeout timer))
      (when-let [timer (::idle-timer current)] (js/clearTimeout timer))
      (when-let [timer (::kill-timer current)] (js/clearTimeout timer))
      (when-not (::ready? current)
        ((get-in current [::ready ::resolve!])
         (host-error
          {::execution/invocation-id "startup"}
          "The execution child exited before becoming ready."
          {::pid (.-pid process)
           ::exit-code exit-code
           ::stderr-tail (::stderr-tail current)
           ::artifact-digest (::artifact-digest current)})))
      (when active
        ((::resolve! active)
         (host-error
          (::invocation active)
          "The execution child exited before returning a result."
          {::pid (.-pid process)
           ::exit-code exit-code
           ::stderr-tail (::stderr-tail current)
           ::artifact-digest (::artifact-digest current)
           ::execution/coordinate
           (get-in active [::invocation ::execution/coordinate])})))
      (remove-child! agent-id generation process))))

(defn- schedule-idle-stop!
  [agent-id generation process]
  (let [timeout-ms (get-in (host-configuration) [::idle-timeout-ms])
        timer (js/setTimeout
               (fn []
                 (when (and (same-child? agent-id generation process)
                            (nil? (::active (child agent-id))))
                   (stop-child! agent-id)))
               timeout-ms)]
    (swap! !host assoc-in [::children agent-id ::idle-timer] timer)))

(defn- settle-active!
  [agent-id generation process message]
  (let [accepted (atom nil)]
    (swap! !host
           (fn [host]
             (let [current (get-in host [::children agent-id])
                   active (::active current)]
               (if (and (= generation (::generation current))
                        (identical? process (::process current))
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
        (schedule-idle-stop! agent-id generation process))
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
         (or (nil? (::coordinate/attachment database))
             (= (::coordinate/attachment database)
                (::execution/database-attachment message))))))

(defn- result-current?
  [config active message]
  (let [invocation (::invocation active)
        runtime (get-in config [::launch-descriptor ::launch/runtime])
        current-run-fence? (::run-fence-current? config)]
    (and (= (::artifact-digest active) (::launch/execution-digest runtime))
         (= (::execution/coordinate invocation)
            (::execution/coordinate message))
         (or (nil? (::execution/run-fence invocation))
             (current-run-fence? (::execution/run-fence invocation))))))

(defn- receive!
  [agent-id generation process encoded]
  (when (and (string? encoded) (same-child? agent-id generation process))
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
                (kill-process! process))))

          (:seon.execution.message/result :seon.execution.message/error)
          (when-let [active (::active current)]
            (if (result-current? (host-configuration) active message)
              (settle-active! agent-id generation process message)
              (settle-active!
               agent-id generation process
               (host-error (::invocation active)
                           "The execution result is no longer current."))))

          nil))
      (catch :default _
        (kill-process! process)))))

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
       (::coordinate/attachment database)
       (assoc :seon.db/attachment (::coordinate/attachment database)))}))

(defn- spawn-child!
  [agent-id]
  (try
    (let [config (host-configuration)
          generation (::generation @!host)
          runtime (get-in config [::launch-descriptor ::launch/runtime])
          startup (startup-value config agent-id)
          ready (deferred)
          options
          {::cmd [(:seon.execution.host/javascript-runtime config)
                  (::launch/execution-output runtime)
                  (execution/encode-message startup)]
           ::ipc (fn [message process & _]
                   (receive! agent-id generation process message))
           ::stdout "pipe"
           ::stderr "pipe"}
          ^js process ((::spawn! config) options)
          timeout
          (js/setTimeout
           (fn []
             (when (and (same-child? agent-id generation process)
                        (not (::ready? (child agent-id))))
               ((::resolve! ready)
                (host-error {::execution/invocation-id "startup"}
                            "The execution child did not become ready."))
               (kill-process! process)))
           (::ready-timeout-ms config))
          state {::generation generation
                 ::process process
                 ::artifact-digest (::launch/execution-digest runtime)
                 ::ready ready
                 ::ready-timer timeout
                 ::ready? false
                 ::stdout-tail ""
                 ::stderr-tail ""}]
      (swap! !host assoc-in [::children agent-id] state)
      (pump-stream! (.-stdout process)
                    #(swap! !host update-in [::children agent-id ::stdout-tail]
                            append-tail %))
      (pump-stream! (.-stderr process)
                    #(swap! !host update-in [::children agent-id ::stderr-tail]
                            append-tail %))
      (-> (.-exited process)
          (.then #(exit-child! agent-id generation process %))
          (.catch #(exit-child! agent-id generation process -1)))
      (::promise ready))
    (catch :default error
      (js/Promise.resolve
       (host-error {::execution/invocation-id "startup"}
                   "The execution child could not be spawned."
                   {:seon.error/cause (ex-message error)})))))

(defn- retire-child!
  [current grace-ms]
  (let [process (::process current)]
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
    (when-not
     (send-message!
      process
      {::execution/message execution/shutdown-message
       ::execution/protocol-version execution/protocol-version})
      (kill-process! process))
    (js/setTimeout #(kill-process! process) grace-ms)))

(defn- ensure-child!
  [agent-id]
  (if-let [current (child agent-id)]
    (if (::ready? current)
      (js/Promise.resolve current)
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
               ::spawn! (or spawn! native-spawn!)
               ::run-fence-current? (or run-fence-current? (constantly true))}
              ::children {}}))
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
                                   (identical? (::process ready)
                                               (::process current)))
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
                                           ::resolve! (::resolve! completion)})))
                            host))))
               (case @decision
                 :claimed
                 (let [current @claimed]
                   (if (send-message! (::process current) invocation)
                     (::promise completion)
                     (let [message
                           (host-error invocation
                                       "The execution invocation could not be sent.")]
                       (settle-active! agent-id (::generation current)
                                       (::process current) message)
                       (kill-process! (::process current))
                       (::promise completion))))

                 :busy
                 (host-error invocation
                             "The agent already has an active invocation.")

                 (host-error invocation
                             "The execution child is no longer current.")))))))))

(defn- reload-required? [message]
  (true? (get-in message [::execution/error :seon.error/data
                          ::execution/reload-required?])))

(defn invoke!
  "Run once, replacing a source-stale child and retrying exactly once."
  {:malli/schema [:=> [:cat :seon.execution/invoke] :any]}
  [invocation]
  (let [agent-id (::execution/agent-id invocation)
        invocation-id (::execution/invocation-id invocation)
        remaining (min execution/maximum-invocation-ms
                       (max 0 (- (::execution/deadline-ms invocation)
                                 (.now js/Date))))
        completion (deferred)
        timer (js/setTimeout
               (fn []
                 ;; The parent owns the deadline even while a child is still
                 ;; spawning. If no active invocation can be canceled yet,
                 ;; retire the pending child so its later readiness cannot
                 ;; claim and send already-expired work.
                 (when-not (cancel! agent-id invocation-id)
                   (stop-child! agent-id))
                 ((::resolve! completion) (canceled-error invocation)))
               remaining)]
    (-> (invoke-once! invocation)
        (.then
         (fn [message]
           (if-not (reload-required? message)
             message
             (let [current (child agent-id)]
               (when current
                 (kill-process! (::process current))
                 (remove-child! agent-id (::generation current)
                                (::process current)))
               (invoke-once! invocation)))))
        (.then (fn [message]
                 (js/clearTimeout timer)
                 ((::resolve! completion) message)))
        (.catch
         (fn [exception]
           (js/clearTimeout timer)
           ((::resolve! completion)
            (host-error invocation
                        "The execution host invocation failed."
                        {:seon.error/cause (ex-message exception)})))))
    (::promise completion)))

(defn ^:async invoke-plans!
  "Prepare and execute ordinary authored calls at one database coordinate."
  {:malli/schema [:=> [:cat :seon.db.coordinate/coordinate
                       :seon.execution/invocation-plans]
                  [:vector :map]]}
  [coordinate plans]
  (let [invocations
        (await
         (execution/prepare-invocations!
          {::execution/coordinate coordinate
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
  {:malli/schema [:=> [:cat :seon.db.coordinate/coordinate
                       :seon.execution/agent-id
                       :seon.execution/function-symbol
                       :seon.execution/arguments]
                  :map]}
  [coordinate agent-id function-symbol arguments]
  (let [runtime (get-in (host-configuration)
                        [::launch-descriptor ::launch/runtime])
        invocation
        (execution/compiled-invocation
         agent-id function-symbol arguments coordinate
         (::launch/execution-digest runtime))]
    (await (invoke! invocation))))

(defn cancel!
  "Cancel one active invocation and bound non-cooperative shutdown."
  {:malli/schema [:=> [:cat ::execution/agent-id ::execution/invocation-id]
                  :boolean]}
  [agent-id invocation-id]
  (if-let [current (child agent-id)]
    (if (= invocation-id
           (get-in current [::active ::invocation
                            ::execution/invocation-id]))
      (let [process (::process current)
            generation (::generation current)
            timer (js/setTimeout
                   (fn []
                     (when (same-child? agent-id generation process)
                       (kill-process! process)))
                   (get-in (host-configuration) [::cancel-grace-ms]))]
        (swap! !host
               (fn [host]
                 (-> host
                     (assoc-in [::children agent-id ::retiring?] true)
                     (assoc-in [::children agent-id ::kill-timer] timer))))
        (settle-active! agent-id generation process
                        (canceled-error
                         (get-in current [::active ::invocation])))
        (when-not
         (send-message!
          process
          {::execution/message execution/cancel-message
           ::execution/protocol-version execution/protocol-version
           ::execution/invocation-id invocation-id})
          (kill-process! process))
        true)
      false)
    false))

(defn stop-child!
  "Ask one agent child to stop and kill it after the shutdown grace."
  {:malli/schema [:=> [:cat ::execution/agent-id] :boolean]}
  [agent-id]
  (if-let [current (child agent-id)]
    (let [process (::process current)
          generation (::generation current)]
      (swap! !host assoc-in [::children agent-id ::retiring?] true)
      (when-not
       (send-message!
        process
        {::execution/message execution/shutdown-message
         ::execution/protocol-version execution/protocol-version})
        (kill-process! process))
      (js/setTimeout
       (fn []
         (when (same-child? agent-id generation process)
           (kill-process! process)))
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
