;;; Render-pipeline design experiment.
;;;
;;; Run from the repository root:
;;;   clojure -M:test tmp/render-pipeline/server_bench.clj
;;;
;;; This is measurement, not a correctness test. It extends the N4 harness
;;; with the stages that harness deliberately left open: Datastar framing,
;;; one-versus-N serialization, core.async mult fan-out, and fast loopback
;;; socket submission.

(ns server-bench
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [seon.render.hiccup :as hiccup]
            [starfederation.datastar.clojure.adapter.common :as adapter]
            [starfederation.datastar.clojure.api.elements :as elements]
            [starfederation.datastar.clojure.consts :as consts])
  (:import [java.net InetAddress InetSocketAddress]
           [java.nio ByteBuffer]
           [java.nio.channels ServerSocketChannel SocketChannel]
           [java.nio.charset StandardCharsets]))

(defn percentile
  [sorted-samples proportion]
  (nth sorted-samples
       (min (dec (count sorted-samples))
            (long (* proportion (count sorted-samples))))))

(defn measure
  [trials warmup thunk]
  (dotimes [_ warmup] (thunk))
  (let [samples
        (loop [remaining trials
               result (transient [])]
          (if (zero? remaining)
            (persistent! result)
            (let [started (System/nanoTime)]
              (thunk)
              (recur (dec remaining)
                     (conj! result (- (System/nanoTime) started))))))
        sorted-samples (vec (sort samples))]
    {:trials trials
     :p50-ns (percentile sorted-samples 0.50)
     :p95-ns (percentile sorted-samples 0.95)
     :p99-ns (percentile sorted-samples 0.99)
     :max-ns (peek sorted-samples)}))

(defn event-row
  [index token]
  (if (even? index)
    [:div {:id (str "event-" index)
           :class "py-1 flex"}
     [:div {:class (str "seon-bubble max-w-[78%] min-w-0 rounded "
                        "px-2.5 py-1.5 mr-auto bg-base-900 "
                        "border border-base-800")}
      [:div {:class "flex items-baseline gap-2 flex-wrap"}
       [:span {:class "text-xs font-mono font-semibold"} "agent-a"]]
      [:div {:class "markdown mt-0.5 min-w-0"}
       (str "a reply with <angle> & ampersand content, number " index " "
            (when (zero? index)
              token))]]]
    [:div {:id (str "event-" index)
           :class (str "agent-activity flex items-baseline gap-1.5 "
                       "px-2 py-1 text-xs min-w-0")}
     [:span {:class "font-medium text-text-400 truncate"}
      (str "ran my.agents.agent-a/step-" index)]
     [:span {:class "font-mono text-text-600 shrink-0"} "12ms"]
     [:span {:class "font-mono shrink-0 text-success"} "done"]]))

(defn transcript-hiccup
  [event-count token]
  (into [:section {:id "surface-transcript" :class "seon-transcript"}]
        (map #(event-row % token))
        (range event-count)))

(def fixed-surfaces
  [[:header {:id "surface-header" :class "seon-header"}
    [:span "◆"] [:span "seon"] [:span "3 agents"]]
   [:section {:id "surface-canvas" :class "seon-canvas"}
    [:div {:class "grid"}
     (for [index (range 9)]
       [:div {:id (str "card-" index) :class "card"} (str "card " index)])]]
   [:section {:id "surface-problems" :class "seon-problems"}
    [:span "no problems"]]])

(def build-event
  (adapter/->build-event-str))

(defn datastar-frame
  [serialized-elements]
  (build-event
   consts/event-type-patch-elements
   (elements/->patch-elements-seq serialized-elements {})
   {}))

(defn serialized-page
  [event-count token]
  (conj (mapv hiccup/->string fixed-surfaces)
        (hiccup/->string (transcript-hiccup event-count token))))

(defn package
  [event-count token]
  (let [surfaces (serialized-page event-count token)
        transcript (peek surfaces)
        leaf (hiccup/->string (event-row 0 token))]
    {:keyframe (datastar-frame surfaces)
     :block-delta (datastar-frame [transcript])
     :leaf-delta (datastar-frame [leaf])}))

(defn stage-measurements
  [event-count]
  (let [trials (if (<= event-count 1000) 1000 400)
        warmup (if (<= event-count 1000) 300 120)
        hiccup-value (transcript-hiccup event-count "warm")
        transcript-html (hiccup/->string hiccup-value)
        leaf-hiccup (event-row 0 "warm")
        leaf-html (hiccup/->string leaf-hiccup)
        surfaces (conj (mapv hiccup/->string fixed-surfaces) transcript-html)]
    {:events event-count
     :transcript-bytes (alength (.getBytes ^String transcript-html
                                           StandardCharsets/UTF_8))
     :keyframe-bytes (alength (.getBytes
                               ^String (datastar-frame surfaces)
                               StandardCharsets/UTF_8))
     :block-delta-bytes (alength (.getBytes
                                  ^String (datastar-frame [transcript-html])
                                  StandardCharsets/UTF_8))
     :leaf-delta-bytes (alength (.getBytes
                                 ^String (datastar-frame [leaf-html])
                                 StandardCharsets/UTF_8))
     :render-hiccup (measure trials warmup
                             #(transcript-hiccup event-count "stream-token"))
     :serialize-transcript (measure trials warmup
                                    #(hiccup/->string hiccup-value))
     :serialize-leaf (measure trials warmup
                              #(hiccup/->string leaf-hiccup))
     :frame-block-delta (measure trials warmup
                                 #(datastar-frame [transcript-html]))
     :frame-leaf-delta (measure trials warmup
                                #(datastar-frame [leaf-html]))
     :frame-keyframe (measure trials warmup
                              #(datastar-frame surfaces))
     :render-serialize-package
     (measure trials warmup #(package event-count "stream-token"))}))

(defn with-fanout
  [tab-count f]
  (let [source (async/chan 1)
        multicast (async/mult source)
        taps (vec (repeatedly tab-count
                              #(async/chan (async/sliding-buffer 1))))]
    (doseq [tap taps] (async/tap multicast tap))
    (try
      (f source taps)
      (finally
        (doseq [tap taps]
          (async/untap multicast tap)
          (async/close! tap))
        (async/close! source)))))

(defn fanout-measurement
  [tab-count value]
  (with-fanout
    tab-count
    (fn [source taps]
      (measure
       1000 300
       (fn []
         (async/>!! source value)
         (doseq [tap taps]
           (when-not (identical? value (async/<!! tap))
             (throw (ex-info "mult copied or changed the package" {})))))))))

(defn serialization-sharing
  [tab-count event-count]
  (let [trials (if (= tab-count 50) 200 500)
        warmup (quot trials 3)]
    {:tabs tab-count
     :events event-count
     :serialize-per-tab
     (measure trials warmup
              #(dotimes [_ tab-count]
                 (package event-count "stream-token")))
     :serialize-once-and-mult
     (with-fanout
       tab-count
       (fn [source taps]
         (measure
          trials warmup
          (fn []
            (let [value (package event-count "stream-token")]
              (async/>!! source value)
              (doseq [tap taps]
                (async/<!! tap)))))))}))

(defn socket-pairs
  [tab-count]
  (let [server (ServerSocketChannel/open)
        _ (.bind (.socket server)
                 (InetSocketAddress. (InetAddress/getLoopbackAddress) 0))
        address (.getLocalAddress server)
        clients (mapv (fn [_]
                        (doto (SocketChannel/open)
                          (.configureBlocking true)
                          (.connect address)))
                      (range tab-count))
        writers (mapv (fn [_]
                        (doto (.accept server)
                          (.configureBlocking true)))
                      (range tab-count))
        readers
        (mapv
         (fn [^SocketChannel client]
           (.start
            (Thread/ofVirtual)
            (fn []
              (let [buffer (ByteBuffer/allocateDirect 65536)]
                (try
                  (loop []
                    (.clear buffer)
                    (when-not (neg? (.read client buffer))
                      (recur)))
                  (catch Throwable _ nil))))))
         clients)]
    (.close server)
    {:clients clients :writers writers :readers readers}))

(defn close-sockets!
  [{:keys [clients writers readers]}]
  (doseq [^SocketChannel channel (concat writers clients)]
    (try (.close channel) (catch Throwable _ nil)))
  (doseq [^Thread reader readers]
    (.join reader 1000)))

(defn write-all!
  [writers payload-bytes]
  (doseq [^SocketChannel writer writers]
    (let [buffer (ByteBuffer/wrap payload-bytes)]
      (loop []
        (when (.hasRemaining buffer)
          (.write writer buffer)
          (recur))))))

(defn loopback-write-measurement
  [tab-count label frame]
  (let [sockets (socket-pairs tab-count)
        payload-bytes (.getBytes ^String frame StandardCharsets/UTF_8)
        trials (if (= label :keyframe) 120 500)
        warmup (quot trials 4)]
    (try
      {:tabs tab-count
       :payload label
       :bytes-per-tab (alength payload-bytes)
       :submit-to-all (measure trials warmup
                               #(write-all! (:writers sockets)
                                            payload-bytes))}
      (finally
        (close-sockets! sockets)))))

(defn churn-measurement
  "One complete server-side streamed-token frame.

  The package is rendered and serialized once, distributed as the same object
  through `mult`, then the selected Datastar bytes are submitted to every
  fast loopback tab. `payload` varies only what fast tabs transmit; both paths
  still build the newest shared keyframe."
  [tab-count event-count payload]
  (let [sockets (socket-pairs tab-count)
        trials 300
        warmup 100
        sequence-number (volatile! 0)]
    (try
      (with-fanout
        tab-count
        (fn [source taps]
          {:tabs tab-count
           :events event-count
           :payload payload
           :complete-frame
           (measure
            trials warmup
            (fn []
              (let [revision (vswap! sequence-number inc)
                    value (package event-count (str "stream-" revision))
                    payload-bytes (.getBytes ^String (get value payload)
                                             StandardCharsets/UTF_8)]
                (async/>!! source value)
                (doseq [tap taps]
                  (async/<!! tap))
                (write-all! (:writers sockets) payload-bytes))))}))
      (finally
        (close-sockets! sockets)))))

(defn packet
  [base revision]
  {:base base
   :revision revision
   :delta (str "delta-" revision)
   :keyframe (str "keyframe-" revision)})

(defn choose-transmission
  [delivered {:keys [base revision delta keyframe]}]
  (if (= delivered base)
    {:mode :delta :bytes delta :delivered revision}
    {:mode :snap :bytes keyframe :delivered revision}))

(defn loss-proof
  []
  (let [channel (async/chan (async/sliding-buffer 1))
        _ (doseq [value [(packet 0 1) (packet 1 2) (packet 2 3)]]
            (async/offer! channel value))
        received (async/poll! channel)]
    {:retained-revision (:revision received)
     :decision-from-revision-zero (choose-transmission 0 received)
     :decision-from-immediate-base (choose-transmission 2 received)}))

(defn ns->ms
  [measurement]
  (into {}
        (map (fn [[field value]]
               (if (and (keyword? field)
                        (.endsWith (name field) "-ns"))
                 [(keyword (namespace field)
                           (str/replace (name field) #"-ns$" "-ms"))
                  (/ value 1e6)]
                 [field value])))
        measurement))

(defn printable
  [value]
  (walk/postwalk
   (fn [node]
     (if (and (map? node) (contains? node :p50-ns))
       (ns->ms node)
       node))
   value))

(defn -main
  []
  (let [stage-results (mapv stage-measurements [250 1000 2500 5000])
        package-250 (package 250 "stream-token")
        sharing-results (mapv #(serialization-sharing % 250) [2 10 50])
        fanout-results (mapv #(hash-map :tabs %
                                        :fanout (fanout-measurement
                                                 %
                                                 package-250))
                             [2 10 50])
        write-results
        (vec
         (mapcat
          (fn [tabs]
            [(loopback-write-measurement tabs :leaf-delta
                                         (:leaf-delta package-250))
             (loopback-write-measurement tabs :block-delta
                                         (:block-delta package-250))
             (loopback-write-measurement tabs :keyframe
                                         (:keyframe package-250))])
          [2 10 50]))
        churn-results
        (vec
         (mapcat
          (fn [tabs]
            [(churn-measurement tabs 250 :leaf-delta)
             (churn-measurement tabs 250 :block-delta)])
          [2 10 50]))
        result {:environment
                {:java-version (System/getProperty "java.version")
                 :available-processors (.availableProcessors
                                        (Runtime/getRuntime))}
                :stage-results stage-results
                :serialization-sharing sharing-results
                :mult-fanout fanout-results
                :loopback-write write-results
                :streamed-token-churn churn-results
                :loss-proof (loss-proof)}]
    (prn (printable result))))

(-main)
