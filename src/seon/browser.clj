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
            [jolt.process :as proc]))

(defn default-binary-path
  "Returns executable path to `target/seon-browser` (or `SEON_BROWSER_BIN`) if present, else nil."
  {:malli/schema [:=> [:cat] [:maybe :string]], :seon.flow/edge true}
  []
  (let [env-bin (System/getenv "SEON_BROWSER_BIN")
        candidates (remove str/blank?
                           [env-bin
                            (str store/checkout-root "/target/seon-browser")
                            (str (System/getProperty "user.dir") "/target/seon-browser")])]
    (some (fn [p]
            (when (and (fs/regular-file? p) (fs/executable? p))
              (str (fs/absolutize p))))
          candidates)))

(defn ^:seon.flow/edge start-browser-process!
  "Starts `seon-browser --headless` via `jolt.process/process` (with `:shutdown destroy-tree`) and pumps JSON-line responses into `resp-ch`."
  {:malli/schema [:=> [:cat :string :string :map :seon.host/chan] :map], :seon.flow/edge true}
  [binary-path url {:keys [width height] :or {width 1280 height 820}} resp-ch]
  (let [p (proc/process [binary-path "--headless" "--url" (str url)
                         "--width" (str width) "--height" (str height)]
                        {:shutdown proc/destroy-tree})
        raw-proc (:proc p)
        stdin-w (java.io.BufferedWriter. (java.io.OutputStreamWriter. (:in p) "UTF-8"))
        stdout-r (java.io.BufferedReader. (java.io.InputStreamReader. (:out p) "UTF-8"))
        send-lock (Object.)
        send! (fn [cmd-map]
                (try
                  (locking send-lock
                    (.write stdin-w (str (json/write-str cmd-map) "\n"))
                    (.flush stdin-w))
                  true
                  (catch Throwable _ false)))
        stop! (fn []
                (try (send! {:op "quit"}) (catch Throwable _))
                (try (.close stdin-w) (catch Throwable _))
                (try (proc/destroy-tree p) (catch Throwable _)))]
    (a/thread
      (try
        (loop []
          (when-let [line (.readLine stdout-r)]
            (when-not (str/blank? line)
              (when-let [parsed (try (json/read-str line :key-fn keyword) (catch Throwable _ nil))]
                (a/put! resp-ch parsed)))
            (recur)))
        (catch Throwable _ nil)
        (finally
          (try (.close stdout-r) (catch Throwable _))
          (a/put! resp-ch {:event "exited"}))))
    {:proc raw-proc :process p :binary binary-path :url url :send! send! :stop! stop!}))

(defn- ^:seon.flow/edge safe-start-browser! [bin url dims ipc-ch]
  (try (start-browser-process! bin url dims ipc-ch) (catch Throwable _ nil)))

(defn- normalize-snapshot [msg]
  (when (and (:ok msg) (not (str/blank? (str (:base64 msg)))))
    {:route (or (get-in msg [:dom :url]) "/")
     :width (:width msg 1280) :height (:height msg 820)
     :mime (or (:mime msg) "image/jpeg") :bytes (:bytes msg) :ms (:ms msg)
     :base64 (:base64 msg) :dom (or (:dom msg) {})
     :captured-at (System/currentTimeMillis)}))

(defn next-retry-delay
  "Bounded exponential backoff in ms for attempt `attempt` (0-based): 250, 500, 1000, capped at 2000."
  {:malli/schema [:=> [:cat [:maybe :int]] :int] :cost/ticks 5000}
  [attempt]
  (let [a (max 0 (long (or attempt 0)))]
    (long (min 2000 (* 250 (bit-shift-left 1 (min a 6)))))))

(defn loaded?
  "True when an `eval_js` DOM-probe `:value` represents a rendered Seon document (has `#seon-app` or >=2 real children, rejecting 503 `<pre>Seon booting...</pre>`)."
  {:malli/schema [:=> {:examples [[[{:app true, :children 5}] true] [[{:children 0, :readyState "complete"}] false] [[{:app false, :children 1, :booting true}] false]]} [:cat :any] :boolean]
   :cost/ticks 5000 :cost/bytes 4096}
  [value]
  (boolean
   (and (map? value)
        (nil? (:error value))
        (not (true? (:booting value)))
        (or (true? (:app value))
            (>= (long (or (:children value) 0)) 2)))))

(defn retry-exhausted?
  "True when `attempt` reached `max-attempts` or `now-ms` reached `deadline-ms`."
  {:malli/schema [:=> [:cat :int :int :int :int] :boolean] :cost/ticks 5000}
  [attempt max-attempts now-ms deadline-ms]
  (or (>= (long attempt) (long max-attempts))
      (>= (long now-ms) (long deadline-ms))))

(defn- dom-probe-js []
  (str "(function(){try{var b=document.body;var boot=!!(b&&b.children.length===1&&b.firstElementChild&&b.firstElementChild.tagName==='PRE');return{"
       "readyState:document.readyState,children:b?b.children.length:0,booting:boot,"
       "svgs:document.querySelectorAll('svg').length,"
       "app:!!document.getElementById('seon-app'),url:location.href};"
       "}catch(e){return{error:String(e)};}})()"))

(defn- coerce-html [x]
  (cond
    (nil? x) ""
    (string? x) x
    (and (map? x) (contains? x :seon.ui/hiccup)) (coerce-html (:seon.ui/hiccup x))
    :else (let [f (or (when-let [v (find-var 'seon.render/hiccup->html)] (when (bound? v) @v))
                      (requiring-resolve 'seon.render/hiccup->html))]
            (str (f x)))))

(defn- normalize-widths [ws]
  (let [raw (if (and (sequential? ws) (seq ws)) ws [360 640 1100])
        cleaned (->> raw
                     (keep #(when (number? %)
                              (let [d (double %)]
                                (when (and (== d d) (not (Double/isInfinite d)))
                                  (max 120 (min 4096 (long (Math/round d))))))))
                     (take 8) vec)]
    (if (seq cleaned) cleaned [360 640 1100])))

(defn- frame-label [prefix w]
  (if (and (string? prefix) (not (str/blank? prefix)))
    (str (str/trim prefix) " · " w "px")
    (str w "px")))

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

(defn- ^:seon.flow/edge ipc-await! [ipc-ch pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
    (loop []
      (let [rem (- deadline (System/currentTimeMillis))]
        (if (<= rem 0)
          {:ok false :error :timeout}
          (let [[v ch] (a/alts!! [ipc-ch (a/timeout rem)])]
            (cond
              (not= ch ipc-ch) {:ok false :error :timeout}
              (nil? v) {:ok false :error :closed}
              (= "exited" (:event v)) {:ok false :error :browser-exited}
              (pred v) v
              :else (recur))))))))

(defn- ^:seon.flow/edge ipc-call! [handle ipc-ch cmd-id cmd timeout-ms]
  (if-not ((:send! handle) (assoc cmd :id cmd-id))
    {:ok false :error :send-failed}
    (ipc-await! ipc-ch #(= cmd-id (:id %)) timeout-ms)))

(defn headless-url
  "Appends `#seon-headless` when no `#` fragment is present so headless previews never overwrite `:viewport-w`."
  {:malli/schema [:=> [:cat :string] :string] :cost/ticks 5000 :cost/bytes 4096}
  [url]
  (let [u (str url)]
    (if (or (str/blank? u) (str/includes? u "#")) u (str u "#seon-headless"))))

(defn- reply! [ch v]
  (when ch (a/put! ch v) (a/close! ch)))

(defn- finish-snapshot [st {:keys [req-id agent-id reply-ch]} snap err]
  (let [st' (cond-> st snap (-> (assoc :last-snap snap) (update :snap-count inc)))]
    (reply! reply-ch {:ok? (boolean snap) :snapshot snap :error err})
    [st' (cond-> {}
           req-id (assoc :agents-res [{:req-id req-id :agent-id agent-id :snapshot snap}])
           snap (assoc :web-snap [{:snapshot snap}]))]))

(defn- clamp [v d lo hi]
  (max lo (min hi (long (or v d)))))

(defn- snap-height-for-dom [explicit-h dom]
  (if (and (number? explicit-h) (pos? (long explicit-h)))
  (clamp explicit-h 200 200 2400)
  (clamp (+ (max 0 (long (or (:h dom) 240))) 24) 240 220 1200)))

(defn- ^:seon.flow/edge with-headless-browser! [url o on-err f]
  (let [bin (or (:binary-path o) (default-binary-path))
        w (clamp (:width o) 1280 240 4096)
        h (clamp (:height o) 820 240 4096)
        tmo (clamp (:timeout-ms o) 8000 500 20000)
        settle (clamp (:settle-ms o) 180 0 3000)
        hurl (headless-url url)]
    (if-not (and (string? bin) (fs/executable? bin))
      (on-err :browser-unavailable)
      (let [ipc-ch (a/chan 32)
            handle (safe-start-browser! bin hurl {:width w :height h} ipc-ch)]
        (if-not handle
          (do (a/close! ipc-ch) (on-err :browser-spawn-failed))
          (try
            (let [rdy (ipc-await! ipc-ch #(= "ready" (:event %)) tmo)]
              (if (:error rdy)
                (on-err :ready-timeout)
                (let [nav (ipc-call! handle ipc-ch 1 {:op "navigate" :url hurl} tmo)]
                  (if-not (:ok nav)
                    (on-err (or (:error nav) :navigate-failed))
                    (let [next-id (loop [attempt 0 cid 2]
                                    (let [pr (ipc-call! handle ipc-ch cid {:op "eval_js" :code (dom-probe-js)} 2500)]
                                      (if (or (loaded? (:value pr)) (>= attempt 5))
                                        (inc cid)
                                        (do (Thread/sleep 120) (recur (inc attempt) (inc cid))))))]
                      (when (pos? settle) (Thread/sleep settle))
                      (f handle ipc-ch next-id tmo w h))))))
            (finally
              ((:stop! handle))
              (a/close! ipc-ch))))))))

(defn ^:seon.flow/edge measure-live-url!
  "Spawns headless `seon-browser` against `url`, measures DOM boxes (and optional `:snippets`),
   analyzes pixel fit + hint calibrations, and terminates the child process cleanly in `finally`."
  {:malli/schema [:function [:=> [:cat :string] :map] [:=> [:cat :string [:maybe :map]] :map]]}
  ([url] (measure-live-url! url nil))
  ([url opts]
   (let [o (if (map? opts) opts {})
         on-err (fn [e] {:ok? false :error e :url url :analysis nil :calibration {} :snippets []})]
     (with-headless-browser! url o on-err
       (fn [handle ipc-ch next-id tmo w h]
         (let [js (measure/measure-boxes-js (select-keys o [:selector :max-elements]))
               dom-r (ipc-call! handle ipc-ch next-id {:op "eval_js" :code js} tmo)
               analysis (measure/analyze-pixel-fit
                         (if (:ok dom-r) (:value dom-r) {:error (or (:error dom-r) "eval_js failed")}))
               snips (vec
                      (map-indexed
                       (fn [idx sn]
                         (let [sw (max 32 (min 10000 (long (or (:slot-w sn) w))))
                               sr (ipc-call! handle ipc-ch (+ next-id 1 idx)
                                             {:op "eval_js"
                                              :code (measure/measure-html-snippet-js
                                                     (coerce-html (or (:html sn) (:hiccup sn))) sw)}
                                             tmo)
                               norm (if (:ok sr)
                                      (measure/normalize-snippet-measurement (:value sr))
                                      {:ok? false :error (or (:error sr) :eval-js-failed)
                                       :w 0 :h 0 :area 0 :scroll-w 0 :scroll-h 0 :overflow-x? false})]
                           (cond-> norm (contains? sn :id) (assoc :id (:id sn)))))
                       (or (:snippets o) [])))]
           {:ok? (:ok? analysis) :url url :width w :height h
            :analysis analysis :calibration (measure/calibrate-hints analysis)
            :snippets snips :raw (:value dom-r)}))))))

(defn ^:seon.flow/edge render-hiccup-frames!
  "Renders `html-or-hiccup` across display widths (default `[360 640 1100]`) using a `:browser`
   `:req` channel or live URL, returning canonical `:seon.media/image` `:frames`."
  {:malli/schema [:function [:=> [:cat :any :any] :map] [:=> [:cat :any :any [:maybe :map]] :map]]}
  ([browser-or-url html-or-hiccup] (render-hiccup-frames! browser-or-url html-or-hiccup nil))
  ([browser-or-url html-or-hiccup opts]
   (let [o (if (map? opts) opts {})
         html (coerce-html html-or-hiccup)
         widths (normalize-widths (:widths o))
         {:keys [theme height quality max-width label-prefix]} o
         q (or quality 0.78)
         tmo (clamp (:timeout-ms o) 8000 1000 20000)]
     (if (instance? clojure.core.async.impl.channels.ManyToManyChannel browser-or-url)
       (let [rch (a/chan 1)
             total-tmo (max tmo (* (inc (count widths)) 3500))]
         (a/put! browser-or-url
                 {:op :render-hiccup-frames :html html :widths widths :theme theme
                  :height height :quality q :max-width max-width
                  :label-prefix label-prefix :step-timeout-ms (min 5000 tmo) :reply-ch rch})
         (let [[v ch] (a/alts!! [rch (a/timeout total-tmo)])]
           (a/close! rch)
           (if (= ch rch) (or v {:ok? false :error :empty-reply :frames []})
               {:ok? false :error :render-frames-timeout :frames []})))
       (let [url (if (and (string? browser-or-url) (not (str/blank? browser-or-url)))
                   browser-or-url "http://127.0.0.1:9998/")
             fail (fn [e acc] {:ok? false :error e :url url :widths widths :frames acc})]
         (with-headless-browser! url (assoc o :width (first widths) :height (or height 820)) #(fail % [])
           (fn [handle ipc-ch next-id tmo _ _]
             (try
               (loop [ws widths cid next-id acc []]
                 (if (empty? ws)
                   {:ok? true :url url :widths widths :frames acc}
                   (let [w (first ws)
                         st-r (ipc-call! handle ipc-ch cid {:op "eval_js" :code (measure/stage-hiccup-js html w theme)} tmo)
                         sdom (when (:ok st-r) (measure/normalize-snippet-measurement (:value st-r)))]
                     (if-not (and (:ok st-r) (:ok? sdom))
                       (fail (or (:error st-r) (:error sdom) :stage-failed) acc)
                       (let [sw (max 200 (long w))
                             sh (snap-height-for-dom height sdom)
                             sn-r (ipc-call! handle ipc-ch (inc cid)
                                             {:op "snapshot" :width sw :height sh
                                              :max_width (max 320 (long (or max-width sw))) :quality q} tmo)
                             snap (normalize-snapshot sn-r)]
                         (if-not snap
                           (fail (or (:error sn-r) :snapshot-failed) acc)
                           (recur (rest ws) (+ cid 2)
                                  (conj acc (measure/normalize-image-frame snap (frame-label label-prefix w) w sdom)))))))))
               (finally
                 (try (ipc-call! handle ipc-ch 99999 {:op "eval_js" :code (measure/clear-stage-js)} 1000)
                      (catch Throwable _)))))))))))

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

(defn- handle-req [st {:keys [op req-id agent-id reply-ch wait-ms] :as msg}]
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
          (a/go (a/<! (a/timeout delay-ms)) (when-not (send! cmd) (a/put! timer-ch {:ev :timeout :id cmd-id})))
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
                 :label-prefix (:label-prefix msg)
                 :step-timeout-ms (clamp (:step-timeout-ms msg) 3500 1000 10000)
                 :frames [] :reply-ch reply-ch :req-id req-id :agent-id agent-id}]
        [(stage-frame-cmd st job (first ws)) nil])

      :else [st nil])))

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

(defn- handle-timer [st msg]
  (case (:ev msg)
    :verify-navigate (if (= :waiting (get-in st [:verify :phase]))
                       [(send-verify-navigate st (long (:attempt msg))) nil] [st nil])
    :verify-timeout (if (and (:verify st) (= (:id msg) (get-in st [:verify :cmd-id])))
                      (verify-probe-reply st {:error "verify-timeout"}) [st nil])
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
          [(update st :pending dissoc cmd-id)
           (when req-id {:agents-res [{:req-id req-id :agent-id agent-id :snapshot (:last-snap st)}]})])
        [st nil]))))

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

(defn- handle-ipc [st msg]
  (cond
    (= "ready" (:event msg))
    (let [[st out] (begin-verify (assoc st :ready? true))
          ready (str "[:browser] native WKWebView ready " (:mode msg) " " (:width msg) "x" (:height msg))]
      [st {:clojure.core.async.flow/report (into (get out :clojure.core.async.flow/report []) [ready])}])

    (= "exited" (:event msg))
    (let [outs (keep (fn [[_ {:keys [req-id agent-id reply-ch op job]}]]
                     (reply! reply-ch (error-reply st op :browser-exited job))
                     (when req-id {:req-id req-id :agent-id agent-id :snapshot nil}))
                     (:pending st))]
      [(assoc st :handle nil :ready? false :pending {} :verify nil)
       (when (seq outs) {:agents-res (vec outs)})])

    (and (:verify st) (= (:id msg) (get-in st [:verify :cmd-id])))
    (if (= :navigate (get-in st [:verify :phase]))
      (let [attempt (long (or (get-in st [:verify :attempt]) 0))]
        [(send-verify-probe st attempt)
         {:clojure.core.async.flow/report [(str "[:browser] re-navigated; re-probing attempt " attempt)]}])
      (verify-probe-reply st (:value msg)))

    (contains? (:pending st) (:id msg))
    (let [cid (:id msg)]
      (handle-pending-ipc (update st :pending dissoc cid) (get-in st [:pending cid]) msg))

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
       :doc "Native embedded macOS WKWebView host providing live viewport snapshots, pixel-exact DOM measurement, off-screen snippet probing, and multi-width Hiccup frame capture."
       :ping-map-fn (fn [st]
                      {:enabled? (boolean (:handle st)) :ready? (boolean (:ready? st))
                       :verified? (:verified? st) :verify-phase (get-in st [:verify :phase])
                       :snaps (:snap-count st 0) :pending (count (:pending st))
                       :last-ms (get-in st [:last-snap :ms]) :last-bytes (get-in st [:last-snap :bytes])})})
  ([{:keys [port binary-path width height]}]
   (let [ipc-ch (a/chan 32) timer-ch (a/chan 32)
         w (or width 1280) h (or height 820)
         bin (when (and port (pos? (long port))) (or binary-path (default-binary-path)))
         url (when bin (headless-url (str "http://127.0.0.1:" port "/")))
         handle (when bin (safe-start-browser! bin url {:width w :height h} ipc-ch))]
     {:port port :url url :width w :height h :handle handle
      :ready? false :verified? nil :verify nil :cmd-seq 0 :pending {} :snap-count 0 :last-snap nil
      :clojure.core.async.flow/in-ports {:ipc ipc-ch :timer timer-ch}}))
  ([st transition]
   (when (= transition :clojure.core.async.flow/stop)
     (when-let [stop! (get-in st [:handle :stop!])] (stop!)))
   st)
  ([st cid msg]
   (if (nil? msg) [st nil]
       (case cid
         :req (handle-req st msg)
         :ipc (handle-ipc st msg)
         :timer (handle-timer st msg)
         [st nil]))))