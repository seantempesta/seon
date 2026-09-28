(ns seon.profile.measurement
  "A definition version's timing and error behaviour, stored in stable buckets
  only when it moves, on commits that already happen (health plan 2026-09-28
  §1.3).

  The armed wrappers' cells (`seon.profile`) count every call into a log2
  histogram. A settle or test-batch writer hands `significant-tx` the
  definitions its evaluation executed (its call sink's `[sym digest]` keys);
  for each, the window is what its cells counted since they were last
  measured. A window of at least `min-calls` calls is measured: the p50 and p99
  buckets and the error-ratio class. It is written, as one noHistory upsert
  keyed by `[sym digest]`, only when it moved from the measurement the
  writer's database holds; most commits carry nothing. A new digest is a new
  definition version and starts its own baseline. Nothing here commits:
  the caller's one transaction carries the data.

  What a measurement means (slow, throwing as control flow) is the named
  warning schema its stored attributes satisfy
  (`:seon.profile/over-bound-warning`, `:seon.profile/frequent-throws-warning`),
  never a stored label.

  A JVM starts every cell and mark from zero (owner ruling): after a restart
  the first window of each definition is compared with what the database
  stored before it."
  (:require [seon.db :as db]
            [seon.error.refusal :as error.refusal]
            [seon.id :as id]
            [seon.profile :as profile]
            [seon.schema.edn :as schema.edn])
  (:import [java.util.concurrent.atomic AtomicReference]))

(schema.edn/load! {})

(def min-calls
  "The fewest calls in a window that are measured: below it the window keeps
  accumulating, so a p99 is never measured from a handful of calls."
  20)

(def hysteresis
  "How far (in log2 buckets) a window's quantile must lie beyond its stored
  bucket's edges to move it: half a bucket, a factor of 1.41. A 30% jitter
  (0.38 of a bucket) at an edge never moves a measurement; a change of 2.83x
  or more always does."
  0.5)

(def floor-position
  "The lowest quantile position measured: 13.0, a call of 2^13 ns (8.2 us).
  Faster quantiles are measured there, so every definition under about 8 us
  sits in bucket 14 and never moves: at that scale the two clock reads, JIT
  and cache effects are the variance (a 1 us function moved 39 times in 10,000
  unchanged measurements before this floor, 2026-09-28)."
  13.0)

(defn- window
  "The calls `members` (one definition's cells) counted since each was last
  measured: the summed counts, and each member with the counts it was
  measured at and the counts now, for `advance!`."
  {:malli/schema [:=> [:cat [:vector :seon.profile/cell]] :seon.profile/window]}
  [members]
  (let [sum (long-array (inc profile/buckets))
        marks (mapv (fn [held]
                      (let [^longs before (.get ^AtomicReference (:seon.profile/measured held))
                            ^longs now (profile/counts held)]
                        (dotimes [i (inc profile/buckets)]
                          (aset sum i (+ (aget sum i) (- (aget now i) (if before (aget before i) 0)))))
                        [held before now]))
                    members)]
    {:seon.profile/window-counts sum
     :seon.profile/calls (profile/histogram-calls sum)
     :seon.profile/marks marks}))

(defn- advance!
  "Move each member's mark to the counts its window was measured at. A member
  another measurement moved meanwhile keeps that one's mark."
  {:malli/schema [:=> [:cat :seon.profile/window] :nil]}
  [{marks :seon.profile/marks}]
  (doseq [[held before now] marks]
    (.compareAndSet ^AtomicReference (:seon.profile/measured held) before now))
  nil)

(defn measure
  "A window's measurement in stable buckets.

  The p50 and p99 buckets with their interpolated log2 positions, never
  below `floor-position`, and the error-ratio class: none, rare (under one
  throw in ten calls), else frequent.
  A p99 in bucket 31 or above (at least 2^30 ns, 1.07 s) is over the
  one-second bound; that is derived from the stored p99, never stored beside
  it."
  {:malli/schema [:=> [:cat :seon.profile/counts pos-int?] :seon.profile/measurement]}
  [^longs counted calls]
  (let [p50 (max floor-position (profile/quantile-position counted calls 0.5))
        p99 (max floor-position (profile/quantile-position counted calls 0.99))
        throws (aget counted profile/buckets)]
    {:seon.profile/p50-position p50
     :seon.profile/p99-position p99
     :seon.profile.measurement/p50 (profile/bucket-at p50)
     :seon.profile.measurement/p99 (profile/bucket-at p99)
     :seon.profile.measurement/errors (cond (zero? throws) :none
                                            (< (* 10 throws) calls) :rare
                                            :else :frequent)}))

(defn- moved?
  "Whether `measured` differs from the `stored` measurement: none stored, an
  error class that changed, or a quantile position `hysteresis` beyond its
  stored bucket."
  {:malli/schema [:=> [:cat [:or :nil :seon.profile/stored-measurement] :seon.profile/measurement] :boolean]}
  [stored measured]
  (let [beyond? (fn [stored-bucket p]
                  (or (< p (- stored-bucket 1 hysteresis)) (>= p (+ stored-bucket hysteresis))))]
    (or (nil? stored)
        (beyond? (:seon.profile.measurement/p50 stored) (:seon.profile/p50-position measured))
        (beyond? (:seon.profile.measurement/p99 stored) (:seon.profile/p99-position measured))
        (not= (:seon.profile.measurement/errors stored) (:seon.profile.measurement/errors measured)))))

(defn measurement-id
  "The stored measurement's identity for the executed definition `[sym digest]`."
  {:malli/schema [:=> [:cat [:tuple :qualified-symbol :seon.program/definition-digest]] :seon.profile.measurement/id]}
  [executed-key]
  (id/digest 16 executed-key))

(defn- stored-measurements
  "The measurements `database` holds for `ids`, by id: one query over the ids."
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector :seon.profile.measurement/id]]
                  [:or [:map-of :seon.profile.measurement/id :seon.profile/stored-measurement] :seon.db/invalid-read-error]]}
  [database ids]
  (let [rows (db/q database
                   '[:find ?id ?p50 ?p99 ?errors
                     :in $ [?id ...]
                     :where [?m :seon.profile.measurement/id ?id]
                     [?m :seon.profile.measurement/p50 ?p50]
                     [?m :seon.profile.measurement/p99 ?p99]
                     [?m :seon.profile.measurement/errors ?errors]]
                   ids)]
    (if (:seon.error/at rows)
      rows
      (into {} (map (fn [[i p50 p99 errors]]
                      [i {:seon.profile.measurement/p50 p50 :seon.profile.measurement/p99 p99
                          :seon.profile.measurement/errors errors}]))
            rows))))

(defn significant-tx
  "The measurement upserts a commit that already happens adds for its work.

  `executed-keys` are the definitions its evaluation executed (`[sym digest]`,
  from its call sink). One noHistory upsert per definition whose window since
  its last measurement holds at least `min-calls` calls and moved from the
  measurement `database` stores; nothing otherwise, which is most commits.

  Measuring a window advances its cells' marks, written or not, so the next
  window starts there. A definition with no digest, no cells or too few calls
  is left accumulating. A store without the measurement attribute (not yet
  adopted) answers no data.

  # Errors
  A refused read of the stored measurements, or any failure measuring, is
  returned as one error value (`seon.error.refusal/diagnostic`, operation
  `seon.profile.measurement/significant-tx`, the cause chain whole): the
  settle writers commit without measurements and deliver it beside the
  settlement as a diagnostic, so measuring never fails a settlement.

  # Costs
  Proportional to `executed-keys`: 41 counter sums per cell and, when a window
  is measured, one query over the measured ids. Unarmed on default
  2026-09-28: 10 definitions 28 us to 3.9 ms, 1,000 definitions 2.2 ms (all
  under `min-calls`) to 289 ms (all measured, 1,000 ids in one query)."
  {:malli/schema [:=> [:cat :seon.db/database-value [:or [:set :seon.batch/executed-key] [:sequential :seon.batch/executed-key]]]
                  [:or :seon.store/transaction-data :seon.error/base]]}
  [database executed-keys]
  (try
  (if-not (get (:schema database) :seon.profile.measurement/id)
    []
    (let [measured (into []
                         (keep (fn [[_ digest :as k]]
                                 (when digest
                                   (let [w (window (profile/cells-of k))]
                                     (when (<= min-calls (:seon.profile/calls w))
                                       (assoc w ::key k ::id (measurement-id k)))))))
                         ;; a set, never `distinct`: distinct destructures its collection
                         ;; (clojure.core 5105) and throws on the set every settle passes
                         (set executed-keys))
          stored (if (seq measured) (stored-measurements database (mapv ::id measured)) {})
          at (java.util.Date.)]
      (run! advance! measured)
      (if (:seon.error/at stored)
        stored
        (into []
              (keep (fn [{[sym digest] ::key i ::id calls :seon.profile/calls counted :seon.profile/window-counts}]
                      (let [now (measure counted calls)]
                        (when (moved? (get stored i) now)
                          (merge {:seon.profile.measurement/id i
                                  :seon.profile.measurement/sym sym
                                  :seon.profile.measurement/digest digest
                                  :seon.profile.measurement/calls calls
                                  :seon.profile.measurement/at at}
                                 (select-keys now [:seon.profile.measurement/p50 :seon.profile.measurement/p99
                                                   :seon.profile.measurement/errors]))))))
              measured))))
  (catch Throwable failure
    (error.refusal/diagnostic {:seon.error/at (java.util.Date.)
                               :seon.error/layer ::measurement
                               :seon.error/operation `significant-tx
                               :seon.error/throwable failure}))))
