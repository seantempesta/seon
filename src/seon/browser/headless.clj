(ns seon.browser.headless
  "Standalone one-shot headless WKWebView measurement and Hiccup frame capture."
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
        send! (fn [cmd-map]
                (try
                  (.write stdin-w (str (json/write-str cmd-map) "\n"))
                  (.flush stdin-w)
                  true
                  (catch Throwable _ false)))
        stop! (fn []
                (try (send! {:op "quit"}) (catch Throwable _))
                (try (.close stdin-w) (catch Throwable _))
                (try (proc/destroy-tree p) (catch Throwable _)))]
    (a/io-thread
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

(defn ^:seon.flow/edge safe-start-browser!
  {:malli/schema [:=> [:cat :string :string :map :seon.host/chan] [:maybe :map]], :seon.flow/edge true}
  [bin url dims ipc-ch]
  (try (start-browser-process! bin url dims ipc-ch) (catch Throwable _ nil)))

(defn normalize-snapshot
  {:malli/schema [:=> [:cat [:maybe :map]] [:maybe :map]], :cost/ticks 200000}
  [msg]
  (when (and (:ok msg) (not (str/blank? (str (:base64 msg)))))
    {:route (or (get-in msg [:dom :url]) "/")
     :width (:width msg 1280) :height (:height msg 820)
     :mime (or (:mime msg) "image/jpeg") :bytes (:bytes msg) :ms (:ms msg)
     :base64 (:base64 msg) :dom (or (:dom msg) {})
     :captured-at (System/currentTimeMillis)}))

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

(defn dom-probe-js
  {:malli/schema [:=> [:cat] :string], :cost/ticks 50000}
  []
  (str "(function(){try{var b=document.body;var boot=!!(b&&b.children.length===1&&b.firstElementChild&&b.firstElementChild.tagName==='PRE');return{"
       "readyState:document.readyState,children:b?b.children.length:0,booting:boot,"
       "svgs:document.querySelectorAll('svg').length,"
       "app:!!document.getElementById('seon-app'),url:location.href};"
       "}catch(e){return{error:String(e)};}})()"))

(defn coerce-html
  {:malli/schema [:=> [:cat :any] :string], :cost/ticks 500000}
  [x]
  (cond
    (nil? x) ""
    (string? x) x
    (and (map? x) (contains? x :seon.ui/hiccup)) (coerce-html (:seon.ui/hiccup x))
    :else (let [f (or (when-let [v (find-var 'seon.render/hiccup->html)] (when (bound? v) @v))
                      (requiring-resolve 'seon.render/hiccup->html))]
            (str (f x)))))

(defn normalize-widths
  {:malli/schema [:=> [:cat :any] [:vector :int]], :cost/ticks 200000}
  [ws]
  (let [raw (if (and (sequential? ws) (seq ws)) ws [360 640 1100])
        cleaned (->> raw
                     (keep #(when (number? %)
                              (let [d (double %)]
                                (when (and (== d d) (not (Double/isInfinite d)))
                                  (max 120 (min 4096 (long (Math/round d))))))))
                     (take 8) vec)]
    (if (seq cleaned) cleaned [360 640 1100])))

(defn frame-label
  {:malli/schema [:=> [:cat [:maybe :string] :int] :string], :cost/ticks 50000}
  [prefix w]
  (if (and (string? prefix) (not (str/blank? prefix)))
    (str (str/trim prefix) " · " w "px")
    (str w "px")))

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

(defn clamp
  {:malli/schema [:=> [:cat :any :int :int :int] :int], :cost/ticks 50000}
  [v d lo hi]
  (let [n (cond (int? v) (long v)
                (and (number? v) (<= -1e15 (double v) 1e15)) (long v)
                :else (long d))]
    (max (long lo) (min (long hi) n))))

(defn snap-height-for-dom
  {:malli/schema [:=> [:cat :any [:maybe :map]] :int], :cost/ticks 50000}
  [explicit-h dom]
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
