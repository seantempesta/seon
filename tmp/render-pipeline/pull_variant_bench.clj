;;; Explicit-pull versus composite-package protocol experiment.
;;;
;;; Run from the repository root:
;;;   clojure -M:test tmp/render-pipeline/pull_variant_bench.clj
;;;
;;; This measures protocol payload bytes and recovery counts from the same
;;; Datastar frames as the render-pipeline benchmark. It does not claim TCP,
;;; TLS, or browser scheduling costs.

(ns pull-variant-bench
  (:require [seon.render.hiccup :as hiccup]
            [starfederation.datastar.clojure.adapter.common :as adapter]
            [starfederation.datastar.clojure.api.elements :as elements]
            [starfederation.datastar.clojure.consts :as consts])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(def build-event (adapter/->build-event-str))

(defn event-row
  [index token]
  [:div {:id (str "event-" index) :class "agent-activity"}
   [:span (if (zero? index) token (str "event " index))]
   [:span "12ms"]
   [:span "done"]])

(defn transcript-hiccup
  [event-count token]
  (into [:section {:id "surface-transcript" :class "seon-transcript"}]
        (map #(event-row % token))
        (range event-count)))

(def fixed-surfaces
  [[:header {:id "surface-header"} [:span "◆"] [:span "seon"]]
   [:section {:id "surface-canvas"} [:div "canvas"]]
   [:section {:id "surface-problems"} [:span "no problems"]]])

(defn datastar-frame
  [serialized-elements]
  (build-event
   consts/event-type-patch-elements
   (elements/->patch-elements-seq serialized-elements {})
   {}))

(defn byte-count
  [value]
  (alength (.getBytes ^String value StandardCharsets/UTF_8)))

(defn digest
  [value]
  (let [result (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes ^String value StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) result))))

(defn package
  [event-count revision]
  (let [token (str "token-" revision)
        surfaces (conj (mapv hiccup/->string fixed-surfaces)
                       (hiccup/->string
                        (transcript-hiccup event-count token)))
        keyframe (datastar-frame surfaces)
        delta (datastar-frame [(hiccup/->string (event-row 0 token))])]
    {:base (dec revision)
     :revision revision
     :hash (digest keyframe)
     :keyframe keyframe
     :delta delta}))

(defn revision-lines
  [{:keys [base revision] :as packet}]
  ;; Explicit pull must expose the chain to browser code. The composite writer
  ;; keeps the same fields process-local and sends only the chosen Datastar
  ;; event. Datastar @get ignores this custom event, so these bytes model the
  ;; custom frontend stream parser the pull variant requires.
  (str "id: " revision "\n"
       "event: seon-render-revision\n"
       "data: base " base "\n"
       "data: revision " revision "\n"
       "data: hash " (:hash packet) "\n\n"))

(defn keyframe-get-overhead
  [{:keys [revision] :as packet}]
  ;; Reproducible application-protocol bytes, deliberately excluding TCP/TLS.
  ;; The URL and headers are the minimum explicit-pull request/response shape.
  (byte-count
   (str "GET /render/keyframe HTTP/1.1\r\n"
        "Accept: text/html\r\n"
        "Cache-Control: no-cache\r\n\r\n"
        "HTTP/1.1 200 OK\r\n"
        "Content-Type: text/event-stream\r\n"
        "Cache-Control: no-store\r\n"
        "ETag: \"" (:hash packet) "\"\r\n"
        "Seon-Render-Revision: " revision "\r\n\r\n")))

(defn delivery-revisions
  "The revisions a tab actually takes from a sliding-1 stream.

  `stride=1` is fast. A larger stride models a parked writer whose tap keeps
  only the newest package before it drains."
  [revision-count stride]
  (vec (distinct (concat [1]
                         (range stride (inc revision-count) stride)
                         [revision-count]))))

(defn composite-tab
  [packages delivered-revisions]
  (let [initial (first packages)]
    (reduce
     (fn [{:keys [delivered] :as result} revision]
       (let [{:keys [base keyframe delta]} (nth packages (dec revision))
             snap? (not= delivered base)
             payload (if snap? keyframe delta)]
         (-> result
             (assoc :delivered revision)
             (update :bytes + (byte-count payload))
             (update (if snap? :keyframes :deltas) inc))))
     {:delivered 1
      :bytes (byte-count (:keyframe initial))
      :keyframes 1
      :deltas 0
      :pulls 0
      :discarded-deltas 0}
     (rest delivered-revisions))))

(defn pull-tab
  [packages delivered-revisions]
  (let [initial (first packages)]
    (reduce
     (fn [{:keys [delivered] :as result} revision]
       (let [{:keys [base keyframe delta] :as packet}
             (nth packages (dec revision))
           control-bytes (byte-count (revision-lines packet))]
         (if (= delivered base)
           (-> result
               (assoc :delivered revision)
               (update :bytes + control-bytes (byte-count delta))
               (update :deltas inc))
           ;; The server does not know the browser's applied revision. It sends
           ;; the newest delta+chain metadata; the browser rejects it, then
           ;; pulls the shared newest keyframe.
           (-> result
               (assoc :delivered revision)
               (update :bytes + control-bytes (byte-count delta)
                       (keyframe-get-overhead packet) (byte-count keyframe))
               (update :keyframes inc)
               (update :pulls inc)
               (update :discarded-deltas inc)))))
     {:delivered 1
      :bytes (+ (keyframe-get-overhead initial)
                (byte-count (:keyframe initial)))
      :keyframes 1
      :deltas 0
      :pulls 1
      :discarded-deltas 0}
     (rest delivered-revisions))))

(defn scenario
  [packages tabs stride]
  (let [revisions (delivery-revisions (count packages) stride)
        composite (composite-tab packages revisions)
        pull (pull-tab packages revisions)]
    {:tabs tabs
     :stride stride
     :delivered-events (count revisions)
     :composite
     (update composite :bytes * tabs)
     :pull
     (update pull :bytes * tabs)
     :pull-minus-composite-bytes
     (* tabs (- (:bytes pull) (:bytes composite)))}))

(defn join-race
  []
  ;; One update R1→R2 can occur in three intervals around GET and subscribe.
  ;; Naive pull subscribes only to future changes, so the middle interval is
  ;; stale. Composite taps before reading latest. Repaired pull makes SSE
  ;; admission compare the supplied revision with current revision.
  {:schedules 3
   :naive-get-then-subscribe-safe 2
   :composite-tap-then-latest-safe 3
   :pull-with-revision-admission-safe 3
   :repaired-pull-extra-server-decisions
   [:compare-client-revision :emit-pull-required-or-open-stream]})

(defn -main
  []
  (let [revision-count 60
        packages (mapv #(package 250 %) (range 1 (inc revision-count)))
        first-package (first packages)]
    (prn
     {:fixture
      {:events 250
       :revisions revision-count
       :keyframe-bytes (byte-count (:keyframe first-package))
       :delta-bytes (byte-count (:delta first-package))
       :pull-revision-control-bytes
       (byte-count (revision-lines first-package))
       :keyframe-get-control-bytes (keyframe-get-overhead first-package)}
      :scenarios
      (vec
       (mapcat (fn [tabs]
                 (map #(scenario packages tabs %) [1 5 20]))
               [2 10 50]))
      :join-race (join-race)})))

(-main)
