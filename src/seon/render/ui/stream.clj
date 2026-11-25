(ns seon.render.ui.stream
  "Pure stream-phase, token-rate, and live-snippet helpers for the Seon UI status sidebar."
  (:require [clojure.string :as str]))

(defn stream-phase-info
  "Classifies a live-stream entry into phase metadata."
  {:malli/schema [:=> [:cat [:maybe :map]] [:map [:phase :keyword] [:label :string] [:css-class :string]]]
   :cost/ticks 50000 :cost/bytes 16384}
  [sinfo]
  (let [raw (when (map? sinfo) (or (:phase sinfo) (:status sinfo)))]
    (case raw
      (:thinking :reasoning :reasoning-token) {:phase :thinking :label "Thinking" :css-class "phase-thinking"}
      (:tools :eval :running-tools :done)     {:phase :tools :label "Tools" :css-class "phase-tools"}
      (:writing :token)                       {:phase :writing :label "Writing" :css-class "phase-writing"}
      :testing                                {:phase :testing :label "Testing" :css-class "phase-testing"}
      :merging                                {:phase :merging :label "Merging" :css-class "phase-merging"}
      {:phase :working :label "Working" :css-class "phase-working"})))

(defn stream-live-snippet
  "Extracts a 1-line live preview snippet for a fixed 60px status card."
  {:malli/schema [:=> [:cat [:maybe :map]] :string] :cost/ticks 100000 :cost/bytes 32768}
  [sinfo]
  (if-not (map? sinfo)
    "\u00A0"
    (let [{:keys [phase]} (stream-phase-info sinfo)
          ll #(when (and (string? %) (not (str/blank? %)))
                (some->> (str/split-lines %) (map str/trim) (remove (fn [x] (or (str/blank? x) (str/starts-with? x "──")))) last not-empty))
          ts (when (string? (:tool-snippet sinfo)) (not-empty (str/trim (:tool-snippet sinfo))))
          raw (case phase
                :writing  (or (ll (:text sinfo)) (ll (:reasoning sinfo)) "Writing response…")
                :thinking (or (ll (:reasoning sinfo)) (ll (:text sinfo)) "Reasoning…")
                :tools    (or ts (ll (:text sinfo)) (ll (:reasoning sinfo)) "Running REPL tools…")
                :testing  (or ts "Running reach tests…")
                :merging  (or ts "Merging verified branch…")
                (or ts (ll (:text sinfo)) (ll (:reasoning sinfo)) "Working…"))]
      (if (str/blank? raw) "\u00A0" (if (> (count raw) 140) (subs raw 0 140) raw)))))

(defn num-or
  {:malli/schema [:=> [:cat [:maybe :map] :keyword :int] :int], :cost/ticks 50000}
  [m k d] (let [v (get m k)] (if (number? v) (max 0 (long v)) d)))

(defn- calc-tps [phase step-ch st lm]
  (when (and (contains? #{:thinking :writing} phase) (pos? step-ch) (pos? st) (>= lm st))
    (max 1 (Math/round (/ (/ (double step-ch) 4.0) (max 0.5 (/ (double (- lm st)) 1000.0)))))))

(defn aggregate-stream-rate
  "Computes aggregate live `tok/s` string (e.g. `\"· 142 tok/s\"`) across actively streaming entries."
  {:malli/schema [:=> [:cat [:maybe :map]] :string] :cost/ticks 100000 :cost/bytes 32768}
  [streams]
  (if-not (map? streams)
    ""
    (let [tot (reduce-kv
               (fn [acc _aid sinfo]
                 (if-not (map? sinfo)
                   acc
                   (let [{:keys [phase]} (stream-phase-info sinfo)
                         sc (num-or sinfo :step-chars (num-or sinfo :chars 0))
                         tps (calc-tps phase sc (num-or sinfo :started-ms 0) (num-or sinfo :last-ms 0))]
                     (if tps (+ acc tps) acc))))
               0 streams)]
      (if (pos? tot) (str "· " tot " tok/s") ""))))

(defn format-stream-rate
  "Formats live streaming throughput (`48 tok/s`) or total tokens (`1.2k tok`) / `—`."
  {:malli/schema [:=> [:cat [:maybe :map]] :string] :cost/ticks 50000 :cost/bytes 16384}
  [sinfo]
  (if-not (map? sinfo)
    "—"
    (let [{:keys [phase]} (stream-phase-info sinfo)
          chars (num-or sinfo :chars (+ (count (str (:reasoning sinfo ""))) (count (str (:text sinfo "")))))
          step-ch (num-or sinfo :step-chars chars)
          ctx-toks (num-or sinfo :context-tokens 0)
          run-toks (if (pos? ctx-toks) (+ ctx-toks (quot step-ch 4)) (Math/round (/ (double chars) 4.0)))
          fmt (fn [n] (cond (>= n 1000000) (str (/ (Math/round (/ (double n) 100000.0)) 10.0) "M")
                            (>= n 1000) (str (/ (Math/round (/ (double n) 100.0)) 10.0) "k")
                            :else (str (max 1 n))))
          tps (calc-tps phase step-ch (num-or sinfo :started-ms 0) (num-or sinfo :last-ms 0))]
      (cond (and tps (pos? run-toks)) (str (fmt run-toks) " (" tps "/s)")
            tps (str tps " tok/s")
            (pos? run-toks) (str (fmt run-toks) " tok")
            :else "—"))))