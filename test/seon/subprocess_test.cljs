(ns seon.subprocess-test
  (:require
   [cljs.test :refer [async deftest is testing]]
   [seon.subprocess :as subprocess]))

(defonce ^:private encoder (js/TextEncoder.))

(defn- stream
  ([chunks] (stream chunks 0))
  ([chunks delay-ms]
   (let [remaining (atom (seq chunks))]
     (js/ReadableStream.
      #js {:pull
           (fn [controller]
             (js/Promise.
              (fn [resolve _]
                (js/setTimeout
                 (fn []
                   (if-let [chunk (first @remaining)]
                     (do
                       (swap! remaining next)
                       (.enqueue controller
                                 (if (string? chunk) (.encode encoder chunk) chunk)))
                     (.close controller))
                   (resolve nil))
                 delay-ms))))}))))

(defn- deferred []
  (let [resolve! (atom nil)
        promise (js/Promise. (fn [resolve _] (reset! resolve! resolve)))]
    {:promise promise :resolve! @resolve!}))

(defn- fake-process
  [{:keys [out err exit-delay-ms exit resource-usage]
    :or {out [] err [] exit-delay-ms 0 exit 0}}]
  (let [{:keys [promise resolve!]} (deferred)
        kills (atom [])
        process (js-obj)]
    (aset process "pid" 321)
    (aset process "stdout" (stream out))
    (aset process "stderr" (stream err))
    (aset process "signalCode" nil)
    (aset process "exited" promise)
    (aset process "kill" (fn [signal]
                            (swap! kills conj signal)
                            (when (= "SIGKILL" signal) (resolve! 137))))
    (aset process "unref" (fn [] nil))
    (aset process "send" (fn [_] nil))
    (aset process "resourceUsage" (fn [] resource-usage))
    (when (some? exit-delay-ms)
      (js/setTimeout #(resolve! exit) exit-delay-ms))
    {:process process :kills kills :resolve-exit! resolve!}))

(defn- settle! [done promise assertions]
  (-> promise
      (.then assertions)
      (.then (fn [_] (done)))
      (.catch (fn [exception]
                (is false (str "unexpected rejection: " exception))
                (done)))))

(deftest captures-both-streams-and-closes-string-stdin
  (async done
    (let [seen-options (atom nil)
          fake (fake-process {:out ["hello " "world"] :err ["warning"]})]
      (settle!
       done
       (subprocess/run!
        {::subprocess/cmd ["tool" "arg"]
         ::subprocess/stdin "input"
         ::subprocess/spawn! (fn [options]
                               (reset! seen-options options)
                               (:process fake))})
       (fn [result]
         (let [^js options @seen-options]
           (is (= ["tool" "arg"] (js->clj (.-cmd options))))
           (is (instance? js/Uint8Array (.-stdin options)))
           (is (= "input" (.decode (js/TextDecoder.) (.-stdin options)))))
         (is (= "hello world" (::subprocess/out result)))
         (is (= "warning" (::subprocess/err result)))
         (is (= 11 (::subprocess/out-bytes result)))
         (is (= 7 (::subprocess/err-bytes result)))
         (is (= 0 (::subprocess/exit result)))
         (is (false? (::subprocess/output-truncated? result))))))))

(deftest split-utf8-and-throwing-observers-do-not-stop-stream-drain
  (async done
    (let [bytes (.encode encoder "A😀Z")
          chunks [(.subarray bytes 0 3) (.subarray bytes 3)]
          fake (fake-process {:out chunks})]
      (settle!
       done
       (subprocess/run!
        {::subprocess/cmd ["utf8"]
         ::subprocess/on-out (fn [_] (throw (js/Error. "observer failed")))
         ::subprocess/spawn! (constantly (:process fake))})
       (fn [result]
         (is (= "A😀Z" (::subprocess/out result)))
         (is (= (.-byteLength bytes) (::subprocess/out-bytes result)))
         (is (nil? (::subprocess/out-stream-error result))))))))

(deftest ipc-receives-the-ordinary-subprocess-id
  (let [received (atom nil)
        options (atom nil)
        fake (fake-process {})
        started (subprocess/start!
                 {::subprocess/cmd ["ipc-child"]
                  ::subprocess/ipc #(reset! received [%1 %2])
                  ::subprocess/spawn! (fn [value]
                                        (reset! options value)
                                        (:process fake))})]
    ((.-ipc ^js @options) {:message "ready"} (:process fake))
    (is (= [{:message "ready"} (::subprocess/id started)] @received))
    (is (string? (::subprocess/id started)))))

(deftest byte-cap-keeps-the-exact-head-and-stops-the-child
  (async done
    (let [fake (fake-process {:out ["abcdefghij"] :exit-delay-ms nil})]
      (settle!
       done
       (subprocess/run!
        {::subprocess/cmd ["firehose"]
         ::subprocess/max-output-bytes 4
         ::subprocess/kill-grace-ms 5
         ::subprocess/spawn! (constantly (:process fake))})
       (fn [result]
         (is (= "abcd" (::subprocess/out result)))
         (is (= 4 (::subprocess/out-bytes result)))
         (is (true? (::subprocess/out-truncated? result)))
         (is (false? (::subprocess/err-truncated? result)))
         (is (true? (::subprocess/output-limited? result)))
         (is (true? (::subprocess/output-truncated? result)))
         (is (= ["SIGTERM" "SIGKILL"] @(:kills fake))))))))

(deftest timeout-is-latched-and-escalates-when-term-is-ignored
  (async done
    (let [fake (fake-process {:exit-delay-ms nil})]
      (settle!
       done
       (subprocess/run!
        {::subprocess/cmd ["sleep"]
         ::subprocess/timeout-ms 2
         ::subprocess/kill-grace-ms 3
         ::subprocess/spawn! (constantly (:process fake))})
       (fn [result]
         (is (true? (::subprocess/timed-out? result)))
         (is (false? (::subprocess/aborted? result)))
         (is (= ["SIGTERM" "SIGKILL"] @(:kills fake)))
         (is (= 137 (::subprocess/exit result))))))))

(deftest completion-waits-for-stream-drain-after-process-exit
  (async done
    (let [drained? (atom false)
          process (js-obj)
          output (js/ReadableStream.
                  #js {:start (fn [controller]
                                (js/setTimeout
                                 (fn []
                                   (.enqueue controller (.encode encoder "late"))
                                   (.close controller)
                                   (reset! drained? true))
                                 12))})]
      (aset process "pid" 9)
      (aset process "stdout" output)
      (aset process "stderr" (stream []))
      (aset process "signalCode" nil)
      (aset process "exited" (js/Promise.resolve 0))
      (aset process "kill" (fn [_] nil))
      (aset process "unref" (fn [] nil))
      (aset process "send" (fn [_] nil))
      (aset process "resourceUsage" (fn [] nil))
      (settle!
       done
       (subprocess/run! {::subprocess/cmd ["fast"]
                         ::subprocess/spawn! (constantly process)})
       (fn [result]
         (is (true? @drained?))
         (is (= "late" (::subprocess/out result))))))))

(deftest callbacks-can-drain-without-retaining-long-lived-output
  (async done
    (let [out (atom [])
          err (atom [])
          fake (fake-process {:out ["first" "second"] :err ["warning"]})]
      (settle!
       done
       (subprocess/run!
        {::subprocess/cmd ["long-lived"]
         ::subprocess/capture-output? false
         ::subprocess/on-out #(swap! out conj %)
         ::subprocess/on-err #(swap! err conj %)
         ::subprocess/spawn! (constantly (:process fake))})
       (fn [result]
         (is (= "" (::subprocess/out result)))
         (is (= "" (::subprocess/err result)))
         (is (= 11 (::subprocess/out-bytes result)))
         (is (= ["first" "second"] @out))
         (is (= ["warning"] @err)))))))

(deftest synchronous-spawn-failure-is-an-ordinary-result
  (async done
    (settle!
     done
     (subprocess/run!
      {::subprocess/cmd ["missing"]
       ::subprocess/spawn! (fn [_]
                             (let [error (js/Error. "not found")]
                               (aset error "code" "ENOENT")
                               (throw error)))})
     (fn [result]
       (is (nil? (::subprocess/pid result)))
       (is (= "not found" (get-in result [::subprocess/spawn-error
                                           :seon.error/message])))
       (is (= "ENOENT" (get-in result [::subprocess/spawn-error
                                        :seon.error/code])))
       (is (= "" (::subprocess/out result)))))))

(deftest post-exit-resource-usage-is-namespaced-ordinary-data
  (async done
    (let [usage #js {:maxRSS (js/BigInt 4096)
                     :shmSize 3
                     :signalCount 2
                     :swapCount 1
                     :cpuTime #js {:user (js/BigInt 10)
                                   :system 20
                                   :total (js/BigInt 30)}
                     :contextSwitches #js {:voluntary 4 :involuntary 5}
                     :messages #js {:sent 6 :received 7}
                     :ops #js {:in 8 :out 9}}
          fake (fake-process {:resource-usage usage})]
      (settle!
       done
       (subprocess/run! {::subprocess/cmd ["measure"]
                         ::subprocess/spawn! (constantly (:process fake))})
       (fn [result]
         (let [resources (::subprocess/resource-usage result)]
           (is (= 4096 (::subprocess/max-rss resources)))
           (is (= {::subprocess/user 10
                   ::subprocess/system 20
                   ::subprocess/total 30}
                  (::subprocess/cpu-time resources)))
           (is (= {::subprocess/in 8 ::subprocess/out 9}
                  (::subprocess/ops resources)))
           (is (every? keyword? (keys resources)))))))))
