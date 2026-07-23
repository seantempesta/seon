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

   Execution-plan selection is DATA on each eval invocation. `:jvm` uses the
   agent's `::eval-socket-path`; `:bun` uses the existing child. Compiled
   prompt/view rendering remains on Bun until the U11 seam moves it."
  (:require
   [seon.config.resolve :as config.resolve]
   [seon.db :as db]
   [seon.db.branch :as branch]
   [seon.db.protocol :as db.protocol]
   [seon.db.transport.uds :as uds]
   [seon.execution :as execution]
   [seon.launch :as launch]
   [seon.render.value :as render.value]
   [seon.schema :as schema]
   [seon.subprocess :as subprocess]))

(def ^:private default-ready-timeout-ms 10000)
(def ^:private default-idle-timeout-ms 300000)
(def ^:private ensure-host-timeout-ms 240000)
(def ^:private ensure-host-output-limit-bytes (* 64 1024))
(def ^:private maximum-tail-characters (* 16 1024))

;; Tier assignment as data: an agent entity carrying this host coordinate
;; has its eval batches served by the JVM agent host at that UDS socket
;; path; absence keeps today's Bun execution child. The coordinate IS the
;; fact — no :type taxonomy, no enum, no second registry.
(schema/register! ::eval-socket-path [:string {:min 1}])
(schema/register! ::javascript-runtime [:string {:min 1}])
(schema/register! ::eval-host-coordinate! 'fn?)
(schema/register! ::ensure-host! 'fn?)
(schema/register! ::now-fn 'fn?)
(schema/register! ::ready-timeout-ms [:int {:min 1}])
(schema/register! ::idle-timeout-ms [:int {:min 1}])
(schema/register! ::cancel-grace-ms [:int {:min 1}])
(schema/register! ::spawn! 'fn?)
(schema/register! ::run-fence-current? 'fn?)
(schema/register! ::eval-id-order [:vector ::execution/eval-id])
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
  [::eval-host-coordinate! {:optional true} ::eval-host-coordinate!]
  [::ensure-host! {:optional true} ::ensure-host!]
  [::now-fn {:optional true} ::now-fn]])

;; One state atom, two transport lanes with identical entry shapes. The
;; lane keyword is the top-level key selecting the entry map.
(def ^:private child-lane ::children)
(def ^:private host-lane ::host-sessions)

(defonce ^:private !host
  (atom {::generation 0
         ::children {}
         ::host-sessions {}
         ::ensure-state {::in-flight? false}}))
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

(defn- sample-host-error [sample message]
  {::execution/message execution/value-sample-error-message
   ::execution/protocol-version execution/protocol-version
   ::execution/agent-id (::execution/agent-id sample)
   ::execution/request-id (::execution/request-id sample)
   ::execution/error {:seon.error/message message
                      :seon.error/kind :core-bug}})

(defn- sample-host-unavailable [sample message]
  {::execution/message execution/value-sample-error-message
   ::execution/protocol-version execution/protocol-version
   ::execution/agent-id (::execution/agent-id sample)
   ::execution/request-id (::execution/request-id sample)
   ::execution/error {:seon.error/message message
                      :seon.error/kind :seon.runtime/unavailable
                      :seon.error/data {::execution/child-retired? true}}})

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

(defn- mark-retiring! [lane agent-id generation child-id]
  (let [marked? (atom false)]
    (swap! !host
           (fn [host]
             (let [current (get-in host [lane agent-id])]
               (if (and (= generation (::generation current))
                        (= child-id (::child-id current)))
                 (do (reset! marked? true)
                     (assoc-in host [lane agent-id ::retiring?] true))
                 host))))
    @marked?))

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
      (when-let [timer (get-in current [::active ::timer])]
        (js/clearTimeout timer))
      (when-not (::ready? current)
        ((get-in current [::ready ::resolve!])
         (host-error
          {::execution/invocation-id "startup"}
          "The execution child exited before becoming ready."
          (assoc (exit-evidence current result)
                 ::execution/child-retired? true))))
      (when active
        ((::resolve! active)
         (if-let [invocation (::invocation active)]
           (host-error
            invocation
            "The execution child exited before returning a result."
            (assoc (exit-evidence current result)
                   ::execution/child-retired? true
                   :seon.db/db (:seon.db/db invocation)))
           (sample-host-unavailable
            (::sample active)
            "The execution value owner retired before returning a sample."))))
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

(defn- append-owned-eval-ids [order ids]
  (let [next-order (reduce (fn [current id]
                             (conj (vec (remove #{id} current)) id))
                           (or order []) ids)
        over (max 0 (- (count next-order)
                       render.value/retained-value-cap))]
    (subvec next-order over)))

(defn- sample-message-current? [sample message]
  (let [result? (= execution/value-sample-result-message
                   (::execution/message message))
        required (if result?
                   [::execution/message ::execution/protocol-version
                    ::execution/agent-id ::execution/request-id
                    :seon.render.value/result]
                   [::execution/message ::execution/protocol-version
                    ::execution/agent-id ::execution/request-id
                    ::execution/error])]
   (and (= 5 (count message))
       (every? #(contains? message %) required)
       (= (::execution/agent-id sample)
          (::execution/agent-id message))
       (= (::execution/request-id sample)
          (::execution/request-id message))
       (contains? #{execution/value-sample-result-message
                    execution/value-sample-error-message}
                  (::execution/message message))
       (or (not result?)
           (let [result (:seon.render.value/result message)
                 projection (:seon.render.value/projection result)
                 limits (:seon.render.value/effective-limits sample)]
             (and (render.value/bounded-drill-result? result limits)
                  (or (false? (:seon.render.value/ok? result))
                      (and (= (:seon.render.value/path sample)
                              (:seon.render.value/path projection))
                           (= (:seon.render.value/offset sample)
                              (:seon.render.value/offset projection))
                           (= (:seon.render.value/page-size limits)
                              (:seon.render.value/page-size projection)))))))
       (execution/valid-child-message? message))))

(defn- settle-active!
  [lane agent-id generation child-id message]
  (let [accepted (atom nil)]
    (swap! !host
           (fn [host]
             (let [current (get-in host [lane agent-id])
                   active (::active current)
                   invocation (::invocation active)
                   sample (::sample active)
                   expected (if invocation
                              (::execution/invocation-id invocation)
                              (::execution/request-id sample))
                   actual (if invocation
                            (::execution/invocation-id message)
                            (::execution/request-id message))]
               (if (and (= generation (::generation current))
                        (= child-id (::child-id current))
                        (= expected actual)
                        (or invocation (sample-message-current? sample message)))
                 (do
                   (reset! accepted
                           {::active active
                            ::retiring? (::retiring? current)})
                   (cond-> (update-in host [lane agent-id] dissoc ::active)
                     (and invocation
                          (= execution/result-message
                             (::execution/message message)))
                     (update-in [lane agent-id ::eval-id-order]
                                append-owned-eval-ids
                                (get-in message [::execution/result
                                                 :seon.eval/ids] []))))
                 host))))
    (when-let [{::keys [active retiring?]} @accepted]
      (when-let [timer (::timer active)] (js/clearTimeout timer))
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

          (:seon.execution.message/value-sample-result
           :seon.execution.message/value-sample-error)
          (when-let [active (::active current)]
            (when-let [sample (::sample active)]
              (when (= (::execution/request-id sample)
                       (::execution/request-id message))
                (if (sample-message-current? sample message)
                  (settle-active! lane agent-id generation child-id message)
                  (do
                    (mark-retiring! lane agent-id generation child-id)
                    (settle-active!
                     lane agent-id generation child-id
                     (sample-host-error
                      sample "The value owner returned an invalid sample frame."))
                    (kill-process! (::control current)))))))

          (when-let [active (::active current)]
            (when-let [sample (::sample active)]
              (when (= (::execution/request-id sample)
                       (::execution/request-id message))
                (settle-active!
                 lane agent-id generation child-id
                 (do (mark-retiring! lane agent-id generation child-id)
                     (sample-host-error
                      sample "The value owner returned an unknown sample frame.")))
                (kill-process! (::control current)))))))
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
                       (cond-> {::eval-socket-path socket-path
                                :seon.error/cause (ex-message error)}
                         (some-> error .-code)
                         (assoc :seon.error/code (str (.-code error))))))))))

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
      (when-let [timer (::timer active)] (js/clearTimeout timer))
      ((::resolve! active)
       (if-let [invocation (::invocation active)]
         (host-error invocation
                     "The execution host configuration changed.")
         (sample-host-unavailable
          (::sample active)
          "The retained value owner was replaced by a new configuration."))))
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
            eval-host-coordinate! ensure-host! now-fn]}]
  (let [runtime (::launch/runtime launch-descriptor)]
    (when-not (and (::launch/execution-build-id runtime)
                   (::launch/execution-output runtime)
                   (::launch/execution-digest runtime))
      (throw (ex-info "The launch has no complete execution artifact."
                      {::launch-descriptor launch-descriptor
                       :seon.error/kind :core-bug})))
    (let [previous @!host
          grace-ms (or (get-in previous [::configuration ::cancel-grace-ms])
                       subprocess/default-kill-grace-ms)]
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
               ::cancel-grace-ms (or cancel-grace-ms
                                     subprocess/default-kill-grace-ms)
               ::spawn! spawn!
               ::run-fence-current? (or run-fence-current? (constantly true))
               ::eval-host-coordinate! eval-host-coordinate!
               ::ensure-host! ensure-host!
               ::now-fn (or now-fn #(.now js/Date))}
              ::children {}
              ::host-sessions {}
              ::ensure-state {::in-flight? false}}))
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
(declare sample-owner)

(defn- eval-batch-invocation? [invocation]
  (= eval-batch-symbol
     (get-in invocation [::execution/function-identity
                         ::execution/function-symbol])))

(defn- executable-symbols
  "Symbols in executable positions, excluding ordinary quoted data."
  [form]
  (cond
    (and (seq? form) (= 'quote (first form))) []
    (symbol? form) [form]
    (coll? form) (mapcat executable-symbols form)
    :else []))

(defn- result-reference-ids [invocation]
  (into #{}
        (comp
         (mapcat (comp executable-symbols :seon.repl/form))
         (filter #(= "result" (namespace %)))
         (map name))
        (get-in invocation [::execution/arguments 0 :seon.eval/parsed])))

(defn- cross-tier-result-reference [lane invocation]
  (some (fn [eval-id]
          (when-let [[owner-lane _] (sample-owner (::execution/agent-id invocation)
                                                   eval-id)]
            (when (not= lane owner-lane) eval-id)))
        (result-reference-ids invocation)))

(defn- tier-local-result-error [invocation eval-id]
  (host-error
   invocation
   (str "result/" eval-id " belongs to another execution tier. "
        "Result symbols are tier-local; persist ordinary data in the database "
        "or re-run the producing form on this tier.")
   {:seon.error/kind :agent
    :seon.eval/id eval-id}))

(defn- authored-invocation? [invocation]
  (contains? (::execution/function-identity invocation)
             ::execution/source-digest))

(defn- ^:async pull-eval-host-coordinate!
  "Read the agent's eval host coordinate fact at the pinned database value.

   A presence QUERY, deliberately not a pull: the tier attribute is
   optional and registered pod-side, so a database where no agent was
   ever host-tier has never installed it — Datahike rejects a pull
   SELECTOR naming an uninstalled attribute, while the query engine
   treats the unknown attribute as zero datoms. Absence of the fact
   (including absence of the attribute itself) routes to the child lane;
   a real read failure (writer down, malformed request) still returns
   its error envelope and fails the turn loudly."
  [invocation]
  (let [result (await
                (db/query
                 {:seon.db/db (:seon.db/db invocation)
                  :seon.db/query
                  '[:find ?socket-path .
                    :in $ ?agent-id
                    :where
                    [?agent :seon.agent/id ?agent-id]
                    [?agent :seon.execution.host/eval-socket-path
                     ?socket-path]]
                  :seon.db/args [(::execution/agent-id invocation)]}))]
    (if (and (map? result) (:seon.error/message result))
      {::coordinate-error result}
      result)))

(defn- host-reconcile-failure?
  [message]
  (let [data (get-in message [::execution/error :seon.error/data])]
    (or (true? (::execution/child-retired? data))
        (contains? #{"ECONNREFUSED" "ENOENT"}
                   (:seon.error/code data)))))

(defn- claim-host-reconcile!
  [now-ms backoff-ms]
  (let [attempt-id (str (random-uuid))
        decision (atom :backoff)]
    (swap! !host
           (fn [host]
             (let [{::keys [in-flight? attempted-at-ms]}
                   (::ensure-state host)]
               (cond
                 in-flight?
                 (do (reset! decision :in-flight) host)

                 (and attempted-at-ms
                      (< (- now-ms attempted-at-ms) backoff-ms))
                 host

                 :else
                 (do
                   (reset! decision :launch)
                   (assoc host ::ensure-state
                          {::attempt-id attempt-id
                           ::attempted-at-ms now-ms
                           ::in-flight? true}))))))
    (when (= :launch @decision) attempt-id)))

(defn- default-ensure-host!
  []
  (subprocess/run!
   {::subprocess/cmd ["bin/seon" "ensure" "host"]
    ::subprocess/timeout-ms ensure-host-timeout-ms
    ::subprocess/max-output-bytes ensure-host-output-limit-bytes}))

(defn- trigger-host-reconcile!
  []
  (let [configuration (host-configuration)
        now-ms ((::now-fn configuration))
        backoff-ms
        (config.resolve/execution-host-respawn-backoff-ms
         (:seon.config/configuration (db/current-tx-context)))]
    (when-let [attempt-id (claim-host-reconcile! now-ms backoff-ms)]
      (let [completion
            (try
              (js/Promise.resolve
               ((or (::ensure-host! configuration) default-ensure-host!)))
              (catch :default error (js/Promise.reject error)))]
        (-> completion
            (.catch (fn [_] nil))
            (.finally
             (fn []
               (swap! !host
                      (fn [host]
                        (if (= attempt-id
                               (get-in host [::ensure-state ::attempt-id]))
                          (assoc-in host [::ensure-state ::in-flight?] false)
                          host))))))))))

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
        (.then
         (fn [message]
           (when (and (= host-lane lane)
                      (host-reconcile-failure? message))
             (trigger-host-reconcile!))
           message))
        (.catch
         (fn [exception]
           (let [diagnostic (get (ex-data exception) :seon.error/data)]
             (host-error
              invocation
              "The execution host invocation failed."
              (cond-> {:seon.error/cause (ex-message exception)}
                (map? diagnostic) (merge diagnostic)))))))))

(defn- invoke-now!
  "Route the head invocation from its selected execution-plan tier.

   Result-symbol ownership remains a runtime fact. Artifact-digest prompt/view
   rendering stays on the Bun child until its claimant phase moves."
  [invocation]
  (if (and (eval-batch-invocation? invocation)
           (not (contains? #{:jvm :bun}
                           (:seon.execution/selected-tier invocation))))
    (js/Promise.resolve
     (host-error invocation
                 "The eval batch has no selected execution-plan tier."
                 {:seon.error/kind :core-bug}))
    (cond
      (and (eval-batch-invocation? invocation)
           (= :bun (:seon.execution/selected-tier invocation)))
      (if-let [eval-id (cross-tier-result-reference child-lane invocation)]
        (js/Promise.resolve (tier-local-result-error invocation eval-id))
        (invoke-in-lane! child-lane nil invocation))

      (not (or (eval-batch-invocation? invocation)
               (authored-invocation? invocation)))
      (invoke-in-lane! child-lane nil invocation)

      :else
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
                 (let [lane (if (eval-batch-invocation? invocation)
                              host-lane
                              (if coordinate host-lane child-lane))
                       socket-path (when (= host-lane lane) coordinate)]
                   (if (and (= host-lane lane) (nil? socket-path))
                     (host-error
                      invocation
                      "The selected JVM execution tier is unavailable."
                      {:seon.error/kind :core-bug})
                     (if-let [eval-id
                              (cross-tier-result-reference lane invocation)]
                       (tier-local-result-error invocation eval-id)
                       (invoke-in-lane! lane socket-path invocation))))))))))))

(defn- unavailable-sample [request message]
  (let [root-request (assoc request
                            :seon.render.value/path []
                            :seon.render.value/offset 0)
        rendered (render.value/drill-value
                  (schema/current-projection)
                  {:seon.eval/ok? false :seon.error/message message}
                  root-request)]
    (if (:seon.render.value/ok? rendered)
      (-> rendered
          (assoc :seon.render.value/availability :unavailable
                 :seon.render.value/recompute? true)
          (assoc-in [:seon.render.value/projection :seon.render.value/path]
                    (:seon.render.value/path request))
          (assoc-in [:seon.render.value/projection :seon.render.value/offset]
                    (:seon.render.value/offset request)))
      rendered)))

(defn- sample-owner [agent-id eval-id]
  (let [owners (keep (fn [lane]
                       (let [current (entry lane agent-id)]
                         (when (and current
                                    (some #{eval-id} (::eval-id-order current)))
                           [lane current])))
                     [child-lane host-lane])]
    (when (= 1 (count owners)) (first owners))))

(defn- sample-once! [agent-id eval-id request]
  (if-let [[lane owner] (sample-owner agent-id eval-id)]
    (let [completion (deferred)
          request-id (str (random-uuid))
          sample (merge {::execution/message execution/value-sample-message
                         ::execution/protocol-version execution/protocol-version
                         ::execution/agent-id agent-id
                         ::execution/request-id request-id
                         ::execution/eval-id eval-id}
                        request)
          claimed (atom nil)
          timer (js/setTimeout
                 (fn []
                   (mark-retiring! lane agent-id
                                   (::generation owner) (::child-id owner))
                   (when (settle-active!
                          lane agent-id (::generation owner) (::child-id owner)
                          (sample-host-unavailable
                           sample "The retained value sample timed out."))
                     (when (same-child? lane agent-id
                                        (::generation owner) (::child-id owner))
                       (kill-process! (::control (entry lane agent-id))))))
                 (get-in (host-configuration) [::ready-timeout-ms]))]
      (swap! !host
             (fn [host]
               (let [current (get-in host [lane agent-id])]
                 (if (and (= (::generation owner) (::generation current))
                          (= (::child-id owner) (::child-id current))
                          (::ready? current)
                          (not (::retiring? current))
                          (nil? (::active current)))
                   (do (reset! claimed current)
                       (assoc-in host [lane agent-id ::active]
                                 {::sample sample
                                  ::artifact-digest (::artifact-digest current)
                                  ::started-at (js/Date.)
                                  ::timer timer
                                  ::resolve! (::resolve! completion)}))
                   host))))
      (if-let [current @claimed]
        (do
          (when-let [send-error (send-message! (::control current) sample)]
            (mark-retiring! lane agent-id
                            (::generation current) (::child-id current))
            (settle-active!
             lane agent-id (::generation current) (::child-id current)
             (sample-host-unavailable
              sample (or (:seon.error/message send-error)
                         "The value sample could not be sent.")))
            (kill-process! (::control current)))
          (::promise completion))
        (do
          (js/clearTimeout timer)
          (js/Promise.resolve
           (sample-host-unavailable
            sample "The retained value owner is no longer current.")))))
    (js/Promise.resolve nil)))

(defn sample-value!
  "Sample one eval only in the retained runtime that produced it."
  {:malli/schema [:=> [:catn [::execution/agent-id ::execution/agent-id]
                             [::execution/eval-id ::execution/eval-id]
                             [::request :seon.render.value/drill-request]]
                  :any]}
  [agent-id eval-id request]
  (if-not (render.value/admitted-drill-request? request)
    (js/Promise.resolve
     {:seon.render.value/ok? false
      :seon/error {:seon.error/message
                   "Invalid or over-budget value drill request."
                   :seon.error/kind :agent}})
    (let [queued (atom nil)]
      (swap! !invocation-tails
             (fn [tails]
               (let [prior (get tails agent-id)
                     work (if prior
                            (.then prior (fn [_]
                                           (sample-once! agent-id eval-id request)))
                            (sample-once! agent-id eval-id request))]
                 (reset! queued work)
                 (assoc tails agent-id work))))
      (let [work @queued]
        (-> work
            (.then
             (fn [frame]
               (cond
                 (nil? frame)
                 (unavailable-sample
                  request "The eval value owner is missing, retired, or evicted.")

                 (= execution/value-sample-result-message
                    (::execution/message frame))
                 (:seon.render.value/result frame)

                 (= :seon.runtime/unavailable
                    (get-in frame [::execution/error :seon.error/kind]))
                 (unavailable-sample
                  request (get-in frame [::execution/error :seon.error/message]
                                  "The retained value owner is unavailable."))

                 :else
                 {:seon.render.value/ok? false
                  :seon/error
                  {:seon.error/message
                   (get-in frame [::execution/error :seon.error/message]
                           "The retained runtime could not sample the value.")
                   :seon.error/kind :core-bug}})))
            (.finally
             (fn []
               (swap! !invocation-tails
                      (fn [tails]
                        (if (identical? work (get tails agent-id))
                          (dissoc tails agent-id)
                          tails))))))))))

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
