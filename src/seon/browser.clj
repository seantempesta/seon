(ns seon.browser
  "The `:browser` Flow proc (`:io` workload) — Native Embedded Browser (`target/seon-browser`,
   macOS `WKWebView` via `native/browser/seon_browser.m`) for live ephemeral viewport snapshots,
   pixel-exact DOM box measurement (`:measure-dom`, `:measure-snippet`), and multi-width
   Hiccup frame capture (`:render-hiccup-frames`)."
  (:require [clojure.core.async :as a]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [seon.browser.measure :as measure]
            [seon.store :as store]
            [jolt.fs :as fs]
            [jolt.process :as proc]
            [seon.browser.headless :as headless]))

(defn ^:seon.flow/edge ^{:seon.code/reexport 'seon.browser.headless/default-binary-path} default-binary-path [& args] (apply headless/default-binary-path args))

(defn ^:seon.flow/edge ^{:seon.code/reexport 'seon.browser.headless/start-browser-process!} start-browser-process! [& args] (apply headless/start-browser-process! args))

(defn- safe-start-browser! [& args] (apply headless/safe-start-browser! args))

(defn- normalize-snapshot [& args] (apply headless/normalize-snapshot args))

(defn next-retry-delay
  "Bounded exponential backoff in ms for attempt `attempt` (0-based): 250, 500, 1000, capped at 2000."
  {:malli/schema [:=> [:cat [:maybe :int]] :int] :cost/ticks 5000}
  [attempt]
  (let [a (max 0 (long (or attempt 0)))]
    (long (min 2000 (* 250 (bit-shift-left 1 (min a 6)))))))

(defn ^{:seon.code/reexport 'seon.browser.headless/loaded?} loaded? [& args] (apply headless/loaded? args))

(defn retry-exhausted?
  "True when `attempt` reached `max-attempts` or `now-ms` reached `deadline-ms`."
  {:malli/schema [:=> [:cat :int :int :int :int] :boolean] :cost/ticks 5000}
  [attempt max-attempts now-ms deadline-ms]
  (or (>= (long attempt) (long max-attempts))
      (>= (long now-ms) (long deadline-ms))))

(defn- dom-probe-js [& args] (apply headless/dom-probe-js args))

(defn- coerce-html [& args] (apply headless/coerce-html args))

(defn- normalize-widths [& args] (apply headless/normalize-widths args))

(defn- frame-label [& args] (apply headless/frame-label args))

(defn- error-reply [st op err job]
  (case op
    :measure-dom
    {:ok? false :error err
     :viewport {:w 0 :h 0 :doc-scroll-w 0 :doc-scroll-h 0 :overflow-x? false}
     :doc-overflow-x false :clipped-items [] :scrollable-items []
     :by-renderer {} :element-count 0 :elements [] :summary (str "Browser error: " (name err))}
    :measure-snippet
    {:ok? false :error err :w 0 :h 0 :area 0 :scroll-w 0 :scroll-h 0 :overflow-x? false}
    :render-hiccup-frames
    {:ok? false :error err :frames (vec (or (:frames job) []))}
    {:ok? false :error err :snapshot (:last-snap st)}))

(defn- send-cmd [st cmd pending-entry timeout-ms]
  (let [cmd-id (inc (long (:cmd-seq st)))
        send! (get-in st [:handle :send!])
        timer-ch (get-in st [:clojure.core.async.flow/in-ports :timer])]
    (when-not (and send! (send! (assoc cmd :id cmd-id)))
      (a/put! timer-ch {:ev :timeout :id cmd-id}))
    (a/go (a/<! (a/timeout (long timeout-ms)))
          (a/put! timer-ch {:ev :timeout :id cmd-id}))
    (-> st (assoc :cmd-seq cmd-id) (assoc-in [:pending cmd-id] pending-entry))))

(defn- stage-frame-cmd [st job w]
  (send-cmd st
            {:op "eval_js" :code (measure/stage-hiccup-js (:html job) w (:theme job))}
            {:op :render-hiccup-frames :phase :stage :cur-width w
             :reply-ch (:reply-ch job) :req-id (:req-id job) :agent-id (:agent-id job)
             :job (assoc job :cur-width w)}
            (:step-timeout-ms job)))

(defn- clear-frame-cmd [st job err]
  (send-cmd st
            {:op "eval_js" :code (measure/clear-stage-js)}
            {:op :render-hiccup-frames :phase :clear
             :reply-ch (:reply-ch job) :req-id (:req-id job) :agent-id (:agent-id job)
             :job (cond-> job err (assoc :error err))}
            2000))

(defn- verify-probe-reply [st value]
  (if (loaded? value)
    [(assoc st :verify nil :verified? true)
     {:clojure.core.async.flow/report [(str "[:browser] viewport loaded svgs=" (:svgs value) " children=" (:children value))]}]
    (let [{:keys [attempt deadline-ms]} (:verify st)
          a (inc (long (or attempt 0)))]
      (if (retry-exhausted? a 12 (System/currentTimeMillis) deadline-ms)
        [(assoc st :verify nil)
         {:clojure.core.async.flow/report [(str "[:browser] viewport blank after " a " bounded retries")]}]
        (let [delay (next-retry-delay a)
              tch (get-in st [:clojure.core.async.flow/in-ports :timer])]
          (a/go (a/<! (a/timeout (long delay))) (a/put! tch {:ev :verify-navigate :attempt a}))
          [(assoc-in st [:verify :phase] :waiting)
           {:clojure.core.async.flow/report [(str "[:browser] viewport blank; re-navigating attempt " a " in " delay "ms")]}])))))

(defn ^{:seon.code/reexport 'seon.browser.headless/headless-url} headless-url [& args] (apply headless/headless-url args))

(defn- reply! [ch v]
  (when ch (a/put! ch v) (a/close! ch)))

(defn- finish-snapshot [st {:keys [req-id agent-id reply-ch]} snap err]
  (let [st' (cond-> st snap (-> (assoc :last-snap snap) (update :snap-count inc)))]
    (reply! reply-ch {:ok? (boolean snap) :snapshot snap :error err})
    [st' (cond-> {}
           req-id (assoc :agents-res [{:req-id req-id :agent-id agent-id :snapshot snap}])
           snap (assoc :web-snap [{:snapshot snap}]))]))

(defn- clamp [& args] (apply headless/clamp args))

(defn- snap-height-for-dom [& args] (apply headless/snap-height-for-dom args))

(defn ^:seon.flow/edge ^{:seon.code/reexport 'seon.browser.headless/measure-live-url!} measure-live-url! [& args] (apply headless/measure-live-url! args))

(defn ^:seon.flow/edge ^{:seon.code/reexport 'seon.browser.headless/render-hiccup-frames!} render-hiccup-frames! [& args] (apply headless/render-hiccup-frames! args))

(defn- send-verify-cmd [st phase attempt cmd tmo-ms]
  (let [cmd-id (inc (long (:cmd-seq st)))
        send! (get-in st [:handle :send!])
        tch (get-in st [:clojure.core.async.flow/in-ports :timer])]
    (when send! (send! (assoc cmd :id cmd-id)))
    (a/go (a/<! (a/timeout (long tmo-ms))) (a/put! tch {:ev :verify-timeout :id cmd-id}))
    (-> st (assoc :cmd-seq cmd-id) (update :verify assoc :phase phase :attempt attempt :cmd-id cmd-id))))

(defn- send-verify-navigate [st attempt]
  (send-verify-cmd st :navigate attempt {:op "navigate" :url (:url st)} 9000))

(defn- send-verify-probe [st attempt]
  (send-verify-cmd st :probe attempt {:op "eval_js" :code (dom-probe-js)} 2500))

(defn- begin-verify [st]
  (if-not (get-in st [:handle :send!])
    [st nil]
    (let [st (-> st
                 (assoc :verify {:phase :probe :attempt 0 :deadline-ms (+ (System/currentTimeMillis) 30000)})
                 (send-verify-probe 0))]
      [st {:clojure.core.async.flow/report ["[:browser] verifying rendered viewport after ready"]}])))

(defn- dispatch-req [st {:keys [op req-id agent-id reply-ch wait-ms] :as msg}]
  (let [handle (:handle st)
        timer-ch (get-in st [:clojure.core.async.flow/in-ports :timer])]
    (cond
      (nil? handle)
      (do (reply! reply-ch (error-reply st op :browser-unavailable nil))
          [st (when req-id {:agents-res [{:req-id req-id :agent-id agent-id :snapshot nil}]})])

      (= op :reload)
      (do (reply! reply-ch {:ok? true}) (begin-verify st))

      (= op :snapshot)
      (let [cmd-id (inc (long (:cmd-seq st)))
            cmd {:id cmd-id :op "snapshot"
                 :width (or (:width msg) (:width st) 1280) :height (or (:height msg) (:height st) 820)
                 :max_width (or (:max-width msg) 1024) :quality (or (:quality msg) 0.74)}
            send! (:send! handle) delay-ms (long (or wait-ms 0))]
        (if (pos? delay-ms)
          (a/go (a/<! (a/timeout delay-ms)) (a/put! timer-ch {:ev :send-delayed :id cmd-id :cmd cmd}))
          (when-not (send! cmd) (a/put! timer-ch {:ev :timeout :id cmd-id})))
        (a/go (a/<! (a/timeout (+ delay-ms 2200))) (a/put! timer-ch {:ev :timeout :id cmd-id}))
        [(-> st (assoc :cmd-seq cmd-id)
             (assoc-in [:pending cmd-id] {:req-id req-id :agent-id agent-id :reply-ch reply-ch :op :snapshot})) nil])

      (= op :navigate)
      (let [u (or (:url msg) (:url st))]
        [(send-cmd st {:op "navigate" :url (if (string? u) (headless-url u) u)}
                   {:req-id req-id :agent-id agent-id :reply-ch reply-ch :op :navigate} 8500) nil])

      (= op :measure-dom)
      (let [sel-opts (cond (string? (:selector msg)) (:selector msg)
                           (map? (:opts msg)) (:opts msg)
                           :else (select-keys msg [:selector :max-elements]))]
        [(send-cmd st {:op "eval_js" :code (measure/measure-boxes-js sel-opts)}
                   {:req-id req-id :agent-id agent-id :reply-ch reply-ch :op :measure-dom :envelope? (boolean (:envelope? msg))}
                   (clamp (:timeout-ms msg) 3500 500 15000)) nil])

      (= op :measure-snippet)
      (let [html (coerce-html (if (contains? msg :html) (:html msg) (:hiccup msg)))
            sw (clamp (or (:slot-w msg) (:width msg) (:width st)) 480 32 10000)]
        [(send-cmd st {:op "eval_js" :code (measure/measure-html-snippet-js html sw)}
                   {:req-id req-id :agent-id agent-id :reply-ch reply-ch :op :measure-snippet}
                   (clamp (:timeout-ms msg) 3500 500 15000)) nil])

      (= op :render-hiccup-frames)
      (let [html (coerce-html (if (contains? msg :html) (:html msg) (:hiccup msg)))
            ws (normalize-widths (:widths msg))
            job {:html html :all-widths ws :rem-widths ws
                 :theme (:theme msg) :height (:height msg)
                 :quality (or (:quality msg) 0.78) :max-width (:max-width msg)
                 :label-prefix (:label-prefix msg) :var-sym (:var-sym msg) :form-hash (:form-hash msg)
                 :step-timeout-ms (clamp (:step-timeout-ms msg) 3500 1000 10000)
                 :frames [] :reply-ch reply-ch :req-id req-id :agent-id agent-id}]
        [(stage-frame-cmd st job (first ws)) nil])

      :else [st nil])))

(defn- sync-browser-filter [st]
  (if (>= (count (:queue st)) 32)
    (assoc st :clojure.core.async.flow/input-filter #{:ipc :timer})
    (dissoc st :clojure.core.async.flow/input-filter)))

(defn- handle-req [st msg]
  (if (and (:handle st) (seq (:pending st)))
    [(sync-browser-filter (update st :queue conj msg)) nil]
    (dispatch-req st msg)))

(defn- maybe-auto-recover [st analysis raw-val]
  (if (and (nil? (:verify st))
           (get-in st [:handle :send!])
           (or (false? (:app? analysis))
               (and (map? raw-val) (contains? raw-val :app) (not (:app raw-val)))))
    (begin-verify st)
    [st nil]))

(defn- restore-size! [st]
  (when-let [send! (get-in st [:handle :send!])]
    (send! {:id 0 :op "resize" :width (or (:width st) 1280) :height (or (:height st) 820)})))

(defn- handle-render-frames-ipc [st {:keys [phase cur-width reply-ch job]} msg]
  (case phase
    :stage
    (let [sdom (when (:ok msg) (measure/normalize-snippet-measurement (:value msg)))]
      (if (and (:ok msg) (:ok? sdom))
        (let [sw (max 200 (long cur-width))
              sh (snap-height-for-dom (:height job) sdom)
              mw (max 320 (long (or (:max-width job) sw)))]
          [(send-cmd st {:op "snapshot" :width sw :height sh :max_width mw :quality (or (:quality job) 0.78)}
                     {:op :render-hiccup-frames :phase :snapshot :cur-width cur-width
                      :reply-ch reply-ch :req-id (:req-id job) :agent-id (:agent-id job)
                      :job (assoc job :stage-dom sdom)}
                     (:step-timeout-ms job))
           nil])
        [(clear-frame-cmd st job (or (:error msg) (:error sdom) :stage-failed)) nil]))

    :snapshot
    (if-let [snap (normalize-snapshot msg)]
      (let [frame (measure/normalize-image-frame snap (frame-label (:label-prefix job) cur-width) cur-width (:stage-dom job))
            job' (update job :frames conj frame)
            rem-ws (subvec (:rem-widths job') 1)]
        [(if (seq rem-ws)
           (stage-frame-cmd st (assoc job' :rem-widths rem-ws) (first rem-ws))
           (clear-frame-cmd st (assoc job' :rem-widths []) nil))
         nil])
      [(clear-frame-cmd st job (or (:error msg) :snapshot-failed)) nil])

    :clear
    (let [err (:error job)
          frames (vec (:frames job))
          res (cond-> {:ok? (and (nil? err) (boolean (seq frames))) :widths (:all-widths job) :frames frames}
                err (assoc :error err))]
      (when (and (:ok? res) (qualified-symbol? (:var-sym job)) (seq frames))
        (when-let [rec (requiring-resolve (quote seon.render.fit/record-browser-box!))]
          (doseq [f frames :when (number? (:seon.media/width f))]
            (rec (:var-sym job) (:form-hash job) (long (:seon.media/width f))
                 (if (map? (:seon.media/dom f)) (:seon.media/dom f)
                     {:w (:seon.media/width f) :h (:seon.media/height f)})))))
      (restore-size! st)
      (reply! reply-ch res)
      [st nil])

    [st nil]))

(defn- handle-pending-ipc [st {:keys [req-id agent-id reply-ch op] :as pending} msg]
  (case op
    :render-hiccup-frames (handle-render-frames-ipc st pending msg)
    :measure-dom
    (let [res (if (:ok msg)
                (measure/analyze-pixel-fit (:value msg))
                (assoc (measure/analyze-pixel-fit {:error (or (:error msg) "eval_js failed")})
                       :error (or (:error msg) :eval-js-failed)))
          out (if (:envelope? pending)
                {:ok? (:ok? res) :url (:url st)
                 :width (get-in res [:viewport :w] (:width st))
                 :height (get-in res [:viewport :h] (:height st))
                 :analysis res :calibration (measure/calibrate-hints res) :snippets []}
                res)
          [st' rec-out] (if (:ok msg) (maybe-auto-recover st res (:value msg)) [st nil])]
      (reply! reply-ch out)
      [st' rec-out])
    :measure-snippet
    (do (reply! reply-ch
                (if (:ok msg)
                  (measure/normalize-snippet-measurement (:value msg))
                  (error-reply st :measure-snippet (or (:error msg) :eval-js-failed) nil)))
        [st nil])
    :snapshot
    (if-let [snap (normalize-snapshot msg)]
      (finish-snapshot st pending snap nil)
      (finish-snapshot st pending nil (:error msg)))
    (do (reply! reply-ch {:ok? (boolean (:ok msg)) :raw msg})
        [st (when req-id {:agents-res [{:req-id req-id :agent-id agent-id :snapshot nil}]})])))

(defn- drain-browser-queue [st out]
  (if (and (:handle st) (empty? (:pending st)) (seq (:queue st)))
    (let [nxt (peek (:queue st))
          st1 (sync-browser-filter (update st :queue pop))
          [st2 out2] (dispatch-req st1 nxt)
          merged (if (and out out2) (merge-with into out out2) (or out out2))]
      (if (and (empty? (:pending st2)) (seq (:queue st2)))
        (recur st2 merged)
        [st2 merged]))
    [(sync-browser-filter st) out]))

(defn- handle-timer [st msg]
  (case (:ev msg)
    :verify-navigate (if (= :waiting (get-in st [:verify :phase]))
                       [(send-verify-navigate st (long (:attempt msg))) nil] [st nil])
    :verify-timeout (if (and (:verify st) (= (:id msg) (get-in st [:verify :cmd-id])))
                      (verify-probe-reply st {:error "verify-timeout"}) [st nil])
    :send-delayed (let [cmd-id (:id msg) send! (get-in st [:handle :send!])
                        tch (get-in st [:clojure.core.async.flow/in-ports :timer])]
                    (when (and send! (contains? (:pending st) cmd-id))
                      (when-not (send! (:cmd msg)) (a/put! tch {:ev :timeout :id cmd-id})))
                    [st nil])
    (let [cmd-id (:id msg)]
      (if-let [{:keys [req-id agent-id reply-ch op phase job]} (get-in st [:pending cmd-id])]
        (do
          (when (and (= op :render-hiccup-frames) (not= phase :clear))
            (when-let [send! (get-in st [:handle :send!])]
              (send! {:id 0 :op "eval_js" :code (measure/clear-stage-js)}))
            (restore-size! st))
          (reply! reply-ch
                  (if (and (= op :render-hiccup-frames) (= phase :clear) (nil? (:error job)) (seq (:frames job)))
                    {:ok? true :widths (:all-widths job) :frames (vec (:frames job))}
                    (error-reply st op (case op
                                         (:measure-dom :measure-snippet) :measure-timeout
                                         :render-hiccup-frames :render-frames-timeout
                                         :snapshot-timeout) job)))
          (drain-browser-queue (update st :pending dissoc cmd-id)
                               (when req-id {:agents-res [{:req-id req-id :agent-id agent-id :snapshot (:last-snap st)}]})))
        [st nil]))))

(defn- handle-ipc [st msg]
  (cond
    (= "ready" (:event msg))
    (let [[st out] (begin-verify (assoc st :ready? true))
          ready (str "[:browser] native WKWebView ready " (:mode msg) " " (:width msg) "x" (:height msg))]
      [st {:clojure.core.async.flow/report (into (get out :clojure.core.async.flow/report []) [ready])}])

    (= "exited" (:event msg))
    (let [p-outs (keep (fn [[_ {:keys [req-id agent-id reply-ch op job]}]]
                         (reply! reply-ch (error-reply st op :browser-exited job))
                         (when req-id {:req-id req-id :agent-id agent-id :snapshot nil}))
                       (:pending st))
          q-outs (keep (fn [{:keys [req-id agent-id reply-ch op]}]
                         (reply! reply-ch (error-reply st op :browser-exited nil))
                         (when req-id {:req-id req-id :agent-id agent-id :snapshot nil}))
                       (:queue st))
          outs (vec (concat p-outs q-outs))]
      [(sync-browser-filter (assoc st :handle nil :ready? false :pending {} :queue clojure.lang.PersistentQueue/EMPTY :verify nil))
       (when (seq outs) {:agents-res outs})])

    (and (:verify st) (= (:id msg) (get-in st [:verify :cmd-id])))
    (if (= :navigate (get-in st [:verify :phase]))
      (let [attempt (long (or (get-in st [:verify :attempt]) 0))]
        [(send-verify-probe st attempt)
         {:clojure.core.async.flow/report [(str "[:browser] re-navigated; re-probing attempt " attempt)]}])
      (verify-probe-reply st (:value msg)))

    (contains? (:pending st) (:id msg))
    (let [cid (:id msg)
          [st1 out1] (handle-pending-ipc (update st :pending dissoc cid) (get-in st [:pending cid]) msg)]
      (drain-browser-queue st1 out1))

    :else [st nil]))

(defn browser-proc
  {:malli/schema [:function [:=> [:cat] :map] [:=> [:cat :map] :map] [:=> [:cat :map :any] :map] [:=> [:cat :map :any :any] [:tuple :map :any]]]}
  ([] {:params {:port "Standing HTTP port (0 disables automatic headless browser launch)"
                :binary-path "Optional path to seon-browser binary"
                :width "Viewport width (default 1280)"
                :height "Viewport height (default 820)"}
       :ins {:req "On-demand snapshot/navigate/reload/measure-dom/measure-snippet/render-hiccup-frames requests"}
       :outs {:agents-res "Snapshot replies {:req-id :agent-id :snapshot} back to :agents"
              :web-snap "Latest live viewport snapshot {:snapshot} for :web Debug View"}
       :workload :io
       :doc "Native embedded macOS WKWebView host providing serialized live viewport snapshots, pixel-exact DOM measurement, off-screen snippet probing, and multi-width Hiccup frame capture."
       :ping-map-fn (fn [st]
                      {:enabled? (boolean (:handle st)) :ready? (boolean (:ready? st))
                       :verified? (:verified? st) :verify-phase (get-in st [:verify :phase])
                       :snaps (:snap-count st 0) :pending (count (:pending st)) :queued (count (:queue st))
                       :last-ms (get-in st [:last-snap :ms]) :last-bytes (get-in st [:last-snap :bytes])})})
  ([{:keys [port binary-path width height]}]
   (let [ipc-ch (a/chan 32) timer-ch (a/chan 32)
         w (or width 1280) h (or height 820)
         bin (when (and port (pos? (long port))) (or binary-path (default-binary-path)))
         url (when bin (headless-url (str "http://127.0.0.1:" port "/")))
         handle (when bin (safe-start-browser! bin url {:width w :height h} ipc-ch))]
     {:port port :url url :width w :height h :handle handle
      :ready? false :verified? nil :verify nil :cmd-seq 0 :pending {}
      :queue clojure.lang.PersistentQueue/EMPTY :snap-count 0 :last-snap nil
      :clojure.core.async.flow/in-ports {:ipc ipc-ch :timer timer-ch}}))
  ([st transition]
   (when (= transition :clojure.core.async.flow/stop)
     (doseq [[_ {:keys [reply-ch op job]}] (:pending st)] (reply! reply-ch (error-reply st op :browser-stopped job)))
     (doseq [{:keys [reply-ch op]} (:queue st)] (reply! reply-ch (error-reply st op :browser-stopped nil)))
     (when-let [stop! (get-in st [:handle :stop!])] (stop!))
     (when-let [ch (get-in st [:clojure.core.async.flow/in-ports :ipc])] (a/close! ch))
     (when-let [ch (get-in st [:clojure.core.async.flow/in-ports :timer])] (a/close! ch)))
   st)
  ([st cid msg]
   (if (nil? msg) [st nil]
       (case cid
         :req (handle-req st msg)
         :ipc (handle-ipc st msg)
         :timer (handle-timer st msg)
         [st nil]))))