(ns seon.subprocess
  "The single Bun-native subprocess boundary for the JavaScript runtime.

   Callers keep authorization and domain result interpretation. This owner
   alone constructs Bun subprocesses, drains their Web streams, enforces the
   capture and time bounds, closes stdin, and samples post-exit resources."
  (:refer-clojure :exclude [run!]))

(def default-kill-grace-ms 1000)

(defonce ^:private text-encoder (js/TextEncoder.))

(defn- native-spawn! [options]
  (js-invoke js/Bun "spawn" options))

(defn- error-value [exception]
  (cond-> {:seon.error/message (or (ex-message exception) (str exception))}
    (some-> exception .-code)
    (assoc :seon.error/code (str (.-code exception)))))

(defn- numeric-value [value]
  (when (some? value)
    (if (= "bigint" (js* "typeof ~{}" value))
      (js/Number value)
      value)))

(defn- resource-usage [^js process]
  (when-let [usage (try (.resourceUsage process) (catch :default _ nil))]
    (let [cpu (.-cpuTime usage)
          switches (.-contextSwitches usage)
          messages (.-messages usage)
          ops (.-ops usage)]
      (cond-> {::max-rss (numeric-value (.-maxRSS usage))
               ::shm-size (numeric-value (.-shmSize usage))
               ::signal-count (numeric-value (.-signalCount usage))
               ::swap-count (numeric-value (.-swapCount usage))}
        cpu (assoc ::cpu-time
                   {::user (numeric-value (.-user cpu))
                    ::system (numeric-value (.-system cpu))
                    ::total (numeric-value (.-total cpu))})
        switches (assoc ::context-switches
                        {::voluntary (numeric-value (.-voluntary switches))
                         ::involuntary (numeric-value (.-involuntary switches))})
        messages (assoc ::messages
                        {::sent (numeric-value (.-sent messages))
                         ::received (numeric-value (.-received messages))})
        ops (assoc ::ops
                   {::in (numeric-value (.-in ops))
                    ::out (numeric-value (.-out ops))})))))

(defn- live-resource-usage [^js process]
  (when-let [usage (try
                     (.resourceUsage process #js {:live true})
                     (catch :default _ nil))]
    (let [cpu (.-cpuTime usage)]
      (cond-> {::rss-bytes (numeric-value (.-rss usage))}
        (some? (.-maxRSS usage))
        (assoc ::max-rss-bytes (numeric-value (.-maxRSS usage)))

        cpu
        (assoc ::cpu-time
               {::user (numeric-value (.-user cpu))
                ::system (numeric-value (.-system cpu))
                ::total (numeric-value (.-total cpu))})))))

(defn- stream-pump!
  [stream maximum-bytes capture-output? on-chunk! on-limit!]
  (if-not (and stream (fn? (.-getReader stream)))
    (js/Promise.resolve {::text "" ::bytes 0 ::truncated? false})
    (let [reader (.getReader stream)
          decoder (js/TextDecoder. "utf-8")
          text (atom "")
          bytes (atom 0)
          truncated? (atom false)
          stream-error (atom nil)]
      (letfn [(notify! [text]
                (when (and on-chunk! (not= "" text))
                  (try (on-chunk! text) (catch :default _ nil))))
              (accept! [chunk]
                (let [remaining (max 0 (- maximum-bytes @bytes))
                      length (.-byteLength chunk)
                      accepted (if (<= length remaining)
                                 chunk
                                 (.subarray chunk 0 remaining))]
                  (when (pos? (.-byteLength accepted))
                    (swap! bytes + (.-byteLength accepted))
                    (let [decoded (.decode decoder accepted #js {:stream true})]
                      (when capture-output?
                        (swap! text str decoded))
                      (notify! decoded)))
                  (when (> length remaining)
                    (when (compare-and-set! truncated? false true)
                      (on-limit!)))))
              (finish []
                (let [tail (.decode decoder)]
                  (when-not (= "" tail)
                    (when capture-output?
                      (swap! text str tail))
                    (notify! tail)))
                (cond-> {::text @text
                         ::bytes @bytes
                         ::truncated? @truncated?}
                  @stream-error (assoc ::stream-error @stream-error)))
              (step []
                (-> (.read reader)
                    (.then (fn [read]
                             (if (.-done read)
                               (finish)
                               (do (accept! (.-value read))
                                   (step)))))
                    (.catch (fn [exception]
                              (reset! stream-error (error-value exception))
                              (finish)))))]
        (step)))))

(defn- result-with-streams [base out-result err-result]
  (assoc base
         ::out (::text out-result)
         ::err (::text err-result)
         ::out-bytes (::bytes out-result)
         ::err-bytes (::bytes err-result)
         ::out-truncated? (boolean (::truncated? out-result))
         ::err-truncated? (boolean (::truncated? err-result))
         ::output-truncated? (boolean (or (::truncated? out-result)
                                          (::truncated? err-result)))
         ::out-stream-error (::stream-error out-result)
         ::err-stream-error (::stream-error err-result)))

(defn start!
  "Start one subprocess and return only ordinary control functions and data."
  [{::keys [cmd cwd env stdin timeout-ms max-output-bytes abort-signal
            kill-grace-ms capture-output? on-out on-err ipc spawn!]
    :or {max-output-bytes js/Number.MAX_SAFE_INTEGER
         capture-output? true
         kill-grace-ms default-kill-grace-ms}}]
  (let [injected-spawn? (some? spawn!)
        spawn! (or spawn! native-spawn!)]
    (try
      (let [id (str (random-uuid))
            options #js {:cmd (clj->js cmd)
                         :detached true
                         :stdin (if (string? stdin)
                                  (.encode text-encoder stdin)
                                  (or stdin "ignore"))
                         :stdout "pipe"
                         :stderr "pipe"}
            _ (when cwd (aset options "cwd" cwd))
            _ (when env (aset options "env" (clj->js env)))
            _ (when ipc
                (aset options "ipc"
                      (fn [message _]
                        (try (ipc message id) (catch :default _ nil)))))
            ^js process (spawn! options)
            ended? (atom false)
            stopping? (atom false)
            timed-out? (atom false)
            aborted? (atom false)
            output-limited? (atom false)
            escalation-timer (atom nil)
            timeout-timer (atom nil)
            signal!
            (fn [signal]
              (try
                (if injected-spawn?
                  (.kill process signal)
                  (js* "process.kill(~{}, ~{})" (- (.-pid process)) signal))
                true
                (catch :default _
                  (try (.kill process signal)
                       true
                       (catch :default _ false)))))
            request-stop!
            (fn [reason]
              (case reason
                :timeout (reset! timed-out? true)
                :abort (reset! aborted? true)
                :output-limit (reset! output-limited? true)
                nil)
              (when (and (not @ended?) (compare-and-set! stopping? false true))
                (signal! "SIGTERM")
                (reset! escalation-timer
                        (js/setTimeout
                         (fn []
                           (when-not @ended?
                             (signal! "SIGKILL")))
                         kill-grace-ms))))
            abort! #(request-stop! :abort)
            _ (when (and timeout-ms (pos? timeout-ms))
                (reset! timeout-timer
                        (js/setTimeout #(request-stop! :timeout) timeout-ms)))
            _ (when abort-signal
                (.addEventListener abort-signal "abort" abort! #js {:once true})
                (when (.-aborted abort-signal) (abort!)))
            out-promise (stream-pump! (.-stdout process) max-output-bytes
                                      capture-output? on-out
                                      #(request-stop! :output-limit))
            err-promise (stream-pump! (.-stderr process) max-output-bytes
                                      capture-output? on-err
                                      #(request-stop! :output-limit))
            exited
            (-> (js/Promise.all #js [(.-exited process) out-promise err-promise])
                (.then
                 (fn [values]
                   (reset! ended? true)
                   (when-let [timer @timeout-timer] (js/clearTimeout timer))
                   (when-let [timer @escalation-timer] (js/clearTimeout timer))
                   (when abort-signal
                     (.removeEventListener abort-signal "abort" abort!))
                   (let [exit (aget values 0)
                         out-result (aget values 1)
                         err-result (aget values 2)]
                     (result-with-streams
                      {::pid (.-pid process)
                       ::exit exit
                       ::signal (.-signalCode process)
                       ::timed-out? @timed-out?
                       ::aborted? @aborted?
                       ::output-limited? @output-limited?
                       ::spawn-error nil
                       ::resource-usage (resource-usage process)}
                      out-result err-result)))))]
        {::id id
         ::pid (.-pid process)
         ::exited exited
         ::resource-usage! #(live-resource-usage process)
         ::kill! #(signal! (or % "SIGTERM"))
         ::unref! (fn [] (try (.unref process) true (catch :default _ false)))
         ::send! (fn [message]
                   (try (.send process message) nil
                        (catch :default exception (error-value exception))))})
      (catch :default exception
        (let [result {::pid nil
                      ::exit nil
                      ::signal nil
                      ::out ""
                      ::err ""
                      ::out-bytes 0
                      ::err-bytes 0
                      ::out-truncated? false
                      ::err-truncated? false
                      ::timed-out? false
                      ::aborted? false
                      ::output-limited? false
                      ::output-truncated? false
                      ::out-stream-error nil
                      ::err-stream-error nil
                      ::spawn-error (error-value exception)
                      ::resource-usage nil}]
          {::pid nil
           ::exited (js/Promise.resolve result)
           ::resource-usage! (constantly nil)
           ::kill! (constantly false)
           ::unref! (constantly false)
           ::send! (constantly {:seon.error/message "The subprocess did not start."})})))))

(defn run!
  "Run one subprocess through the shared bounded capture path."
  [request]
  (::exited (start! request)))
