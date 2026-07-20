(ns seon.execution.host
  "Supervise flavor-owned agent execution over one dispatch mechanism.

   The host owns spawning/connecting, request correlation, liveness, and
   shutdown while execution semantics stay inside each serving runtime.
   ONE dispatch mechanism serves TWO transports speaking the same message
   contract (`seon.execution`'s startup/ready, invoke/result/error,
   cancel, shutdown):

   - the Bun execution child, spawned per agent, messages over Bun IPC
     strings (today's default for every agent);
   - the JVM agent host (`seon.host`), reached over a length-prefixed
     Transit-UDS stream through `seon.db.transport.uds/connect-stream!`.

   Tier assignment is DATA on the agent entity: an agent carrying
   `::eval-socket-path` has its `eval-batch!` invocations served by the
   JVM agent host at that socket; every other invocation (prompt/view
   rendering, authored calls) stays on the Bun child until the U4/U11
   seams move them. Absence of the fact keeps today's child for
   everything (sci-execution-runtime design §9 step 1)."
  (:require
   [seon.db :as db]
   [seon.db.branch :as branch]
   [seon.db.protocol :as db.protocol]
   [seon.db.transport.uds :as uds]
   [seon.execution :as execution]
   [seon.launch :as launch]
   [seon.schema :as schema]
   [seon.subprocess :as subprocess]))

(def ^:private default-ready-timeout-ms 10000)
(def ^:private default-idle-timeout-ms 300000)
(def ^:private default-cancel-grace-ms 1000)
(def ^:private maximum-tail-characters (* 16 1024))

;; Tier assignment as data: an agent entity carrying this host coordinate
;; has its eval batches served by the JVM agent host at that UDS socket
;; path; absence keeps today's Bun execution child. The coordinate IS the
;; fact — no :type taxonomy, no enum, no second registry.
(schema/register! ::eval-socket-path [:string {:min 1}])
(schema/register! ::javascript-runtime [:string {:min 1}])
(schema/register! ::eval-host-coordinate! 'fn?)
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
  ;; A Bun child carries its pid; a JVM host session carries the host's
  ;; socket path instead (there is no child process to sample).
  [::pid {:optional true} ::pid]
  [::eval-socket-path {:optional true} ::eval-socket-path]
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
  [::run-fence-current? {:optional true} ::run-fence-current?]
  [::eval-host-coordinate! {:optional true} ::eval-host-coordinate!]])

;; One state atom, two transport lanes with identical entry shapes. The
;; lane keyword is the top-level key selecting the entry map.
(def ^:private child-lane ::children)
(def ^:private host-lane ::host-sessions)

(defonce ^:private !host
  (atom {::generation 0 ::children {} ::host-sessions {}}))
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
(defn- entry [lane agent-id] (get-in @!host [lane agent-id]))
(defn- child [agent-id] (entry child-lane agent-id))

(defn- child-evidence [current]
  (let [usage ((::subprocess/resource-usage! (::control current)))
        started-at (get-in current [::active ::started-at])]
    (cond-> {::artifact-digest (::artifact-digest current)
             ::stdout-tail (::stdout-tail current)
             ::stderr-tail (::stderr-tail current)}
      (some? (::pid current)) (assoc ::pid (::pid current))
      (::eval-socket-path current)
      (assoc ::eval-socket-path (::eval-socket-path current))
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
  (->> (concat (::children @!host) (::host-sessions @!host))
       (map (fn [[agent-id current]]
              (let [active (::active current)
                    invocation (::invocation active)
                    usage ((::subprocess/resource-usage! (::control current)))]
                (cond-> {::execution/agent-id agent-id
                         ::artifact-digest (::artifact-digest current)
                         ::ready? (boolean (::ready? current))
                         ::retiring? (boolean (::retiring? current))
                         ::stdout-tail (::stdout-tail current)
                         ::stderr-tail (::stderr-tail current)}
                  (some? (::pid current)) (assoc ::pid (::pid current))
                  (::eval-socket-path current)
                  (assoc ::eval-socket-path (::eval-socket-path current))
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
  [lane agent-id generation child-id]
  (let [current (entry lane agent-id)]
    (and (= generation (::generation current))
         (= child-id (::child-id current)))))

(declare cancel! stop-child! settle-active!)

(defn- remove-child!
  [lane agent-id generation child-id]
  (swap! !host
         (fn [host]
           (if (same-child? lane agent-id generation child-id)
             (update host lane dissoc agent-id)
             host))))

(defn- exit-child!
  [lane agent-id generation child-id result]
  (when (same-child? lane agent-id generation child-id)
    (let [current (entry lane agent-id)
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
      (remove-child! lane agent-id generation child-id)
      ((::resolve! exit) (::subprocess/exit result)))))

(defn- schedule-idle-stop!
  "Schedule the Bun child's idle shutdown; host sessions never idle-stop.

   Parking a JVM host context today drops the agent's in-context defs —
   the corpus tee that would make park/restore lossless is the recorded
   U2/U4 seam, and the park/idle policy itself is U7. Until then an idle
   host session stays open (C1 measured ~118 KB working set per context)."
  [lane agent-id generation child-id]
  (when (= child-lane lane)
    (let [timeout-ms (get-in (host-configuration) [::idle-timeout-ms])
          timer (js/setTimeout
                 (fn []
                   (when (and (same-child? lane agent-id generation child-id)
                              (nil? (::active (entry lane agent-id))))
                     (stop-child! agent-id)))
                 timeout-ms)]
      (swap! !host assoc-in [lane agent-id ::idle-timer] timer))))

(defn- settle-active!
  [lane agent-id generation child-id message]
  (let [accepted (atom nil)]
    (swap! !host
           (fn [host]
             (let [current (get-in host [lane agent-id])
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
                   (update-in host [lane agent-id] dissoc ::active))
                 host))))
    (when-let [{::keys [active retiring?]} @accepted]
      ((::resolve! active) message)
      (when-not retiring?
        (schedule-idle-stop! lane agent-id generation child-id))
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
  [lane agent-id generation child-id encoded]
  (when (and (string? encoded)
             (same-child? lane agent-id generation child-id))
    (try
      (let [message (execution/decode-message encoded)
            current (entry lane agent-id)]
        (case (::execution/message message)
          :seon.execution.message/ready
          (let [ready (::ready current)]
            (if (ready-message-valid? (host-configuration) agent-id message)
              (do
                (js/clearTimeout (::ready-timer current))
                (swap! !host assoc-in [lane agent-id ::ready?] true)
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
                (settle-active! lane agent-id generation child-id message)
                (settle-active!
                 lane agent-id generation child-id
                 (host-error (::invocation active)
                             "The execution result is no longer current.")))))

          nil))
      (catch :default _
        (when-let [control (::control (entry lane agent-id))]
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
              :seon.db/backend (::db.protocol/backend database)
              :seon.db/database-advanced? false}
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
               (receive! child-lane agent-id generation child-id message))
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
             (when (and (same-child? child-lane agent-id generation child-id)
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
          (.then #(exit-child! child-lane agent-id generation child-id %))
          (.catch #(exit-child! child-lane agent-id generation child-id
                                {::subprocess/exit -1})))
      (::promise ready))
    (catch :default error
      (js/Promise.resolve
       (host-error {::execution/invocation-id "startup"}
                   "The execution child could not be spawned."
                   {:seon.error/cause (ex-message error)})))))

(defn- connect-host-session!
  "Open one agent's session on the JVM agent host at its coordinate.

   Same message contract as the Bun child, one transport difference: the
   startup value that a child receives as argv[2] is the session's FIRST
   frame, and messages ride length-prefixed Transit-UDS text through the
   one `seon.db.transport.uds` stream codec. The startup carries the
   launch descriptor's honest artifact identity; the JVM host trusts its
   classpath and ECHOES those fields (the documented U1 trust-root
   divergence), so the ready validation passes on the echo."
  [agent-id socket-path]
  (let [config (host-configuration)
        generation (::generation @!host)
        startup (startup-value config agent-id)
        runtime (get-in config [::launch-descriptor ::launch/runtime])
        session-id (str (random-uuid))
        ready (deferred)
        exit (deferred)]
    (-> (uds/connect-stream!
         {::uds/socket-path socket-path
          ::uds/on-text!
          (fn [text]
            (receive! host-lane agent-id generation session-id text))
          ::uds/on-close!
          (fn [_error]
            (exit-child! host-lane agent-id generation session-id
                         {::subprocess/exit nil}))})
        (.then
         (fn [stream]
           (let [control {::subprocess/id session-id
                          ::subprocess/send!
                          (fn [encoded] ((::uds/send-text! stream) encoded))
                          ::subprocess/kill!
                          (fn [_signal] (uds/close-stream! stream))
                          ::subprocess/resource-usage! (constantly nil)
                          ::subprocess/exited (::promise exit)}
                 timeout
                 (js/setTimeout
                  (fn []
                    (when (and (same-child? host-lane agent-id generation
                                            session-id)
                               (not (::ready? (entry host-lane agent-id))))
                      ((::resolve! ready)
                       (host-error
                        {::execution/invocation-id "startup"}
                        "The agent host session did not become ready."
                        {::eval-socket-path socket-path}))
                      (uds/close-stream! stream)))
                  (::ready-timeout-ms config))
                 state {::generation generation
                        ::child-id session-id
                        ::control control
                        ::exit exit
                        ::eval-socket-path socket-path
                        ::artifact-digest
                        (::launch/execution-digest runtime)
                        ::ready ready
                        ::ready-timer timeout
                        ::ready? false
                        ::stdout-tail ""
                        ::stderr-tail ""}]
             (swap! !host assoc-in [host-lane agent-id] state)
             (when-let [send-error
                        (send-message! control startup)]
               ((::resolve! ready)
                (host-error {::execution/invocation-id "startup"}
                            "The agent host startup could not be sent."
                            {::eval-socket-path socket-path
                             :seon.error/cause
                             (:seon.error/message send-error)}))
               (uds/close-stream! stream))
             (::promise ready))))
        (.catch
         (fn [error]
           (host-error {::execution/invocation-id "startup"}
                       "The agent host session could not be opened."
                       {::eval-socket-path socket-path
                        :seon.error/cause (ex-message error)}))))))

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

(defn- ensure-entry!
  [lane agent-id socket-path]
  (if-let [current (entry lane agent-id)]
    (cond
      (::retiring? current)
      (-> (::promise (::exit current))
          (.then (fn [_] (ensure-entry! lane agent-id socket-path))))

      (::ready? current)
      (js/Promise.resolve current)

      :else
      (::promise (::ready current)))
    (if (= host-lane lane)
      (connect-host-session! agent-id socket-path)
      (spawn-child! agent-id))))

(defn configure!
  "Configure the one Bun execution-child supervisor."
  {:malli/schema [:=> [:cat ::configure-request] :boolean]}
  [{::keys [launch-descriptor javascript-runtime ready-timeout-ms
            idle-timeout-ms cancel-grace-ms spawn! run-fence-current?
            eval-host-coordinate!]}]
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
      (doseq [current (concat (vals (::children previous))
                              (vals (::host-sessions previous)))]
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
               ::run-fence-current? (or run-fence-current? (constantly true))
               ::eval-host-coordinate! eval-host-coordinate!}
              ::children {}
              ::host-sessions {}}))
    (reset! !invocation-tails {})
    true))

(defn- invoke-once!
  "Run one invocation on its agent's serving transport lane."
  [lane socket-path invocation]
  (let [agent-id (::execution/agent-id invocation)]
    (-> (ensure-entry! lane agent-id socket-path)
        (.then
         (fn [ready]
           (if (::execution/message ready)
             ready
             (let [claimed (atom nil)
                   decision (atom :stale)
                   completion (deferred)]
               (swap! !host
                      (fn [host]
                        (let [current (get-in host [lane agent-id])]
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
                                (assoc-in host [lane agent-id ::active]
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
                            (cond-> {:seon.error/cause
                                     (or (:seon.error/message send-error)
                                         (get-in send-error
                                                 [:seon.error/data
                                                  :seon.error/cause]))
                                     ::stdout-tail (::stdout-tail current)
                                     ::stderr-tail (::stderr-tail current)}
                              (some? (::pid current))
                              (assoc ::pid (::pid current))
                              (::eval-socket-path current)
                              (assoc ::eval-socket-path
                                     (::eval-socket-path current))))]
                       (settle-active! lane agent-id (::generation current)
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

(def ^:private eval-batch-symbol 'seon.execution.runtime/eval-batch!)

(defn- eval-batch-invocation? [invocation]
  (= eval-batch-symbol
     (get-in invocation [::execution/function-identity
                         ::execution/function-symbol])))

(defn- ^:async pull-eval-host-coordinate!
  "Read the agent's eval host coordinate fact at the pinned database value."
  [invocation]
  (let [pulled (await
                (db/pull (:seon.db/db invocation)
                         [::eval-socket-path]
                         [:seon.agent/id
                          (::execution/agent-id invocation)]))]
    (if (:seon.error/message pulled)
      {::coordinate-error pulled}
      (::eval-socket-path pulled))))

(defn- invoke-in-lane!
  "Run the head invocation on one lane, replacing a source-stale child once."
  [lane socket-path invocation]
  (let [agent-id (::execution/agent-id invocation)]
    (-> (invoke-once! lane socket-path invocation)
        (.then
         (fn [message]
           (if-not (reload-required? message)
             message
             (let [current (entry lane agent-id)]
               (when current
                 (kill-process! (::control current))
                 (remove-child! lane agent-id (::generation current)
                                (::child-id current)))
               (invoke-once! lane socket-path invocation)))))
        (.catch
         (fn [exception]
           (let [diagnostic (get (ex-data exception) :seon.error/data)]
             (host-error
              invocation
              "The execution host invocation failed."
              (cond-> {:seon.error/cause (ex-message exception)}
                (map? diagnostic) (merge diagnostic)))))))))

(defn- invoke-now!
  "Route the head invocation by the agent's tier data, then run it.

   Only `eval-batch!` consults the tier fact in U1.5: prompt/view
   rendering and authored calls stay on the Bun child (synchronous spawn
   preserved) until their seams move (U4 recording, U11 retirement). A
   failed tier read surfaces loudly as an error frame — never a silent
   child fallback that could run a host-tier agent's eval in a fresh
   empty child context."
  [invocation]
  (if-not (eval-batch-invocation? invocation)
    (invoke-in-lane! child-lane nil invocation)
    (let [lookup (or (::eval-host-coordinate! (host-configuration))
                     pull-eval-host-coordinate!)]
      (-> (lookup invocation)
          (.then
           (fn [coordinate]
             (if (::coordinate-error coordinate)
               (host-error invocation
                           "The agent's execution tier fact could not be read."
                           {:seon.error/cause
                            (get-in coordinate
                                    [::coordinate-error
                                     :seon.error/message])})
               (invoke-in-lane! (if coordinate host-lane child-lane)
                                coordinate invocation))))))))

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
   (let [cancel-in-lane!
         (fn [lane]
           (if-let [current (entry lane agent-id)]
             (if (= invocation-id
                    (get-in current [::active ::invocation
                                     ::execution/invocation-id]))
               (let [control (::control current)
                     child-id (::child-id current)
                     generation (::generation current)
                     timer (js/setTimeout
                            (fn []
                              (when (same-child? lane agent-id generation
                                                 child-id)
                                (kill-process! control)))
                            (get-in (host-configuration)
                                    [::cancel-grace-ms]))]
                 (swap! !host
                        (fn [host]
                          (-> host
                              (assoc-in [lane agent-id ::retiring?] true)
                              (assoc-in [lane agent-id ::kill-timer] timer))))
                 (settle-active! lane agent-id generation child-id
                                 (canceled-error
                                  (get-in current [::active ::invocation])
                                  (when child-retired?
                                    (assoc (child-evidence current)
                                           ::execution/child-retired? true))))
                 ;; A Bun child exits after cancel; the JVM host ends only
                 ;; the SESSION while the agent's context survives in the
                 ;; host process (the documented favorable divergence).
                 (when
                  (send-message!
                   control
                   {::execution/message execution/cancel-message
                    ::execution/protocol-version execution/protocol-version
                    ::execution/invocation-id invocation-id})
                   (kill-process! control))
                 true)
               false)
             false))]
     (or (cancel-in-lane! child-lane)
         (cancel-in-lane! host-lane)))))

(defn stop-child!
  "Ask one agent's execution resources to stop with the shutdown grace.

   Both transport lanes stop: the Bun child receives shutdown then a kill
   after the grace, and a JVM host session receives shutdown (the host
   parks the agent's context and acknowledges) then a socket close."
  {:malli/schema [:=> [:cat ::execution/agent-id] :boolean]}
  [agent-id]
  (let [stop-in-lane!
        (fn [lane]
          (if-let [current (entry lane agent-id)]
            (let [control (::control current)
                  child-id (::child-id current)
                  generation (::generation current)]
              (swap! !host assoc-in [lane agent-id ::retiring?] true)
              (when
               (send-message!
                control
                {::execution/message execution/shutdown-message
                 ::execution/protocol-version execution/protocol-version})
                (kill-process! control))
              (js/setTimeout
               (fn []
                 (when (same-child? lane agent-id generation child-id)
                   (kill-process! control)))
               (get-in (host-configuration) [::cancel-grace-ms]))
              true)
            false))
        stopped-child? (stop-in-lane! child-lane)
        stopped-session? (stop-in-lane! host-lane)]
    (or stopped-child? stopped-session?)))

(defn ^:async stop!
  "Stop every supervised execution child and agent host session."
  {:malli/schema [:=> [:cat] :int]}
  []
  (let [current @!host
        entries (concat (::children current) (::host-sessions current))
        agent-ids (distinct (map first entries))]
    (doseq [agent-id agent-ids] (stop-child! agent-id))
    (await
     (js/Promise.all
      (clj->js
       (mapv #(get-in (second %) [::exit ::promise]) entries))))
    (count agent-ids)))
