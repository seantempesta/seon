(ns seon.cron
  "The cron proc (AGENTS section 4, the one place a schedule may put a message on a queue): at
  the time of day :seon.config.proofs/full-run-at names (UTC, \"hh:mm\"; \"\": never), it puts one
  full-run request for main on the test service's queue (seon.prove, :full mode), and arms the
  next day's. It decides nothing: the service runs the tests, records what happened and the
  :test-red rows say which are red.
  The wait is one core.async timeout to the next time (docs/core-async.md, a delayed message):
  no poll, no ping, nothing runs while it waits. A root started after the time waits for the
  next one (a missed day is not made up: the daily run is the net, and the next one is a day
  away); its request names the day (\"full-yyyy-mm-dd\"), so one day's request that the
  service already ran is answered from its result, not run again."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [clojure.core.async :as a]
            [clojure.core.async.flow :as flow] [clojure.string :as str]))

(def ^:private day-ms 86400000)

(defn epoch-ms
  "Wall-clock milliseconds since the epoch (UTC): the one clock read of the proc, to find the
  next time of day."
  {:malli/schema [:=> [:cat] :int]}
  [] (System/currentTimeMillis))

(defn time-of-day-ms
  "The milliseconds into a UTC day of at (\"hh:mm\"), or nil for \"\" (the proc never fires)."
  {:malli/schema [:=> [:cat :string] [:maybe :int]]}
  [at]
  (when-not (str/blank? at)
    (let [[h m] (map #(Long/parseLong %) (str/split at #":"))]
      (+ (* h 3600000) (* m 60000)))))

(defn next-fire-ms
  "The epoch milliseconds of the first time of day at (\"hh:mm\", UTC) strictly after now-ms."
  {:malli/schema [:=> [:cat :int :string] [:maybe :int]]}
  [now-ms at]
  (when-let [tod (time-of-day-ms at)]
    (let [today (+ (* day-ms (quot now-ms day-ms)) tod)]
      (if (> today now-ms) today (+ today day-ms)))))

(defn day-of
  "The UTC date of epoch milliseconds ms as \"yyyy-mm-dd\" (days since 1970-01-01 to a civil date,
  Hinnant's algorithm: Jolt has no zone database, and UTC needs none)."
  {:malli/schema [:=> [:cat :int] :string]}
  [ms]
  (let [z (+ (quot ms day-ms) 719468)
        era (quot (if (neg? z) (- z 146096) z) 146097)
        doe (- z (* era 146097))
        yoe (quot (- doe (quot doe 1460) (- (quot doe 36524)) (quot doe 146096)) 365)
        y (+ yoe (* era 400))
        doy (- doe (- (+ (* 365 yoe) (quot yoe 4)) (quot yoe 100)))
        mp (quot (+ (* 5 doy) 2) 153)
        d (inc (- doy (quot (+ (* 153 mp) 2) 5)))
        m (if (< mp 10) (+ mp 3) (- mp 9))]
    (format "%04d-%02d-%02d" (if (<= m 2) (inc y) y) m d)))

(defn- arm!
  "One tick on port tick at epoch milliseconds due (a timeout to it, now-ms being now)."
  {:malli/schema [:=> [:cat :seon.host/chan :int :int] :any]}
  [tick due now-ms]
  (a/take! (a/timeout (max 1 (- due now-ms))) (fn [_] (a/put! tick {}))))

(defn cron-proc
  "The cron proc. Args :tick (its clock's channel, an external in-port) and :at. In :tick, the
  time came; out :to-prove (one full-run request for main, then the next day's armed)."
  {:malli/schema :seon.step/step-fn}
  ([] {:params {:tick "its clock's channel (external in-port): a :tick at the time of day" :at "the time of day (UTC, hh:mm; \"\": never)"}
       :ins {:tick "the time of day came"} :outs {:to-prove "a full-run request for main"}
       ;; one message a day
       :workload :io
       :ping-map-fn (fn [st] (select-keys st [:at :due :fired]))})
  ([{:keys [tick at]}]
   (let [now (epoch-ms) due (next-fire-ms now (or at ""))]
     (when due (arm! tick due now))
     (cond-> {:at at :tick tick :fired 0 ::flow/in-ports {:tick tick}} due (assoc :due due))))
  ([st _] st)
  ([st in m]
   (if (or (nil? m) (not= :tick in) (nil? (:due st)))
     [st nil]
     (let [due (:due st) now (max (epoch-ms) due) next-due (next-fire-ms now (:at st))]
       (arm! (:tick st) next-due now)
       [(-> st (assoc :due next-due) (update :fired inc))
        {:to-prove [{:branch :main :digest (str "full-" (day-of due)) :mode :full}]}]))))
