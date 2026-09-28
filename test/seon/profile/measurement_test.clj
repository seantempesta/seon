(ns seon.profile.measurement-test
  "A definition version's measurement is written on significance only.

  One regression per behaviour class, on deterministic histogram counts (no
  timing is asserted): the first measured window writes the baseline; a
  window in the same bucket, or under the minimum calls, writes nothing; a
  window two buckets away moves it once; a new digest is its own baseline;
  sub-8 us definitions sit on the floor bucket and never move."
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.profile :as profile]
            [seon.profile.measurement :as measurement]
            [seon.test-support :as test-support])
  (:import [java.util.concurrent.atomic LongAdder]))

(defn- held-cell
  "A host cell for a fresh probe definition at `digest-char` repeated."
  [label digest-char]
  (profile/cell {:seon.profile/sym (symbol "seon.profile.measurement-test" (str label "-" (random-uuid)))
                 :seon.profile/digest (apply str (repeat 64 digest-char))
                 :seon.profile/scope :seon.profile/host}))

(defn- record!
  "Count `n` calls into bucket `b` of `held`'s histogram."
  [held b n]
  (dotimes [_ n] (.increment ^LongAdder (aget ^objects (:seon.profile/histogram held) b))))

(defn- key-of [held] [(:seon.profile/sym held) (:seon.profile/digest held)])

(defn- settle!
  "What a commit carrying `held`'s measurement would add, committed."
  [connection held]
  (let [tx (measurement/significant-tx (db/db connection) [(key-of held)])]
    (when (seq tx) (test-support/transacted! connection tx))
    tx))

(deftest a-measurement-is-written-only-when-its-bucket-moves
  (test-support/with-database
   (fn [connection]
     (let [held (held-cell "moved" "a")
           p50 (fn [tx] (mapv :seon.profile.measurement/p50 tx))]
       (record! held 18 100)
       (is (= [18] (p50 (settle! connection held))) "the first measured window is the baseline")
       (record! held 18 100)
       (is (= [] (settle! connection held)) "an unchanged window writes nothing")
       (record! held 21 10)
       (is (= [] (settle! connection held)) "a window under the minimum calls keeps accumulating")
       (record! held 21 100)
       (is (= [21] (p50 (settle! connection held))) "a window three buckets away moves it once")
       (record! held 21 100)
       (is (= [] (settle! connection held)))
       (is (= {:seon.profile.measurement/p50 21 :seon.profile.measurement/errors :none}
              (db/pull (db/db connection) [:seon.profile.measurement/p50 :seon.profile.measurement/errors]
                       [:seon.profile.measurement/id (measurement/measurement-id (key-of held))])))))))

(deftest a-new-digest-is-its-own-baseline
  (test-support/with-database
   (fn [connection]
     (let [old (held-cell "versioned" "b")
           newer (profile/cell (assoc (select-keys old [:seon.profile/sym :seon.profile/scope])
                                    :seon.profile/digest (apply str (repeat 64 "c"))))]
       (record! old 18 50)
       (settle! connection old)
       (record! newer 22 50)
       (is (= [[(:seon.profile/digest newer) 22]]
              (mapv (juxt :seon.profile.measurement/digest :seon.profile.measurement/p50)
                    (settle! connection newer))))))))

(deftest a-definition-under-the-floor-never-moves
  (let [counted (long-array (inc profile/buckets))]
    (aset counted 10 90)
    (aset counted 12 10)
    (is (= {:seon.profile.measurement/p50 14 :seon.profile.measurement/p99 14
            :seon.profile.measurement/errors :none}
           (select-keys (measurement/measure counted 100)
                        [:seon.profile.measurement/p50 :seon.profile.measurement/p99
                         :seon.profile.measurement/errors])))
    (aset counted profile/buckets 20)
    (is (= :frequent (:seon.profile.measurement/errors (measurement/measure counted 100))))))

(deftest the-settle-writers-executed-keys-are-a-set-of-tuples
  ;; the shape both settle writers pass (seon.turn/settle-batch!, seon.turn.batch/settle):
  ;; a set of [sym digest], nil digests included; `distinct` over it threw on every settle
  (test-support/with-database
   (fn [connection]
     (let [held (held-cell "settled" "e")]
       (record! held 18 30)
       (is (= [18] (mapv :seon.profile.measurement/p50
                         (measurement/significant-tx (db/db connection)
                                                     #{(key-of held) ['seon.profile.measurement-test/unknown nil]}))))))))
