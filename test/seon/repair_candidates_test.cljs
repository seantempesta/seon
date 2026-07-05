(ns seon.repair-candidates-test
  "Unit tests for `seon.repair.candidates` — the SHARED candidate /
   distance / threshold / nearest-tier / unique-winner intelligence
   behind BOTH repair consumers (the pod pre-flight gate and the
   worker-eval `op:\"repair\"`). Pure fns + a stubbed async `passes?`,
   fully hermetic (no conn, no compile-state)."
  (:require
    [cljs.test :as t :refer [deftest is testing async]]
    [seon.repair.candidates :as candidates]))

;; ============================================================
;; Token helpers
;; ============================================================

(deftest name-and-ns-parts
  (is (= "even" (candidates/name-part "even")))
  (is (= "add!" (candidates/name-part "my.plan/add!")))
  (is (nil? (candidates/ns-part "even")))
  (is (= "my.plan" (candidates/ns-part "my.plan/add!"))))

(deftest substitute-symbol-respects-word-boundaries
  (testing "bare token replaced everywhere it stands alone"
    (is (= "(filter even? [1 2 even?])"
           (candidates/substitute-symbol "(filter even [1 2 even])"
                                         "even" "even?"))))
  (testing "never matches inside a longer symbol / qualified / keyword"
    (is (= "(even? :even my/even evens)"
           (candidates/substitute-symbol "(even? :even my/even evens)"
                                         "even" "odd")))
    (is (= "(map even? evens)"
           (candidates/substitute-symbol "(map even evens)" "even" "even?"))))
  (testing "a QUALIFIED from only matches the full qualified token"
    (is (= "(my.plan/add! {:a 1})"
           (candidates/substitute-symbol "(my.plan/addd! {:a 1})"
                                         "my.plan/addd!" "my.plan/add!")))
    (is (= "(other/addd! (my.plan/add! 1))"
           (candidates/substitute-symbol "(other/addd! (my.plan/addd! 1))"
                                         "my.plan/addd!" "my.plan/add!")))))

;; ============================================================
;; Ranking — the ⌈n/3⌉ band, nearest-then-shortest, k ≤ 5, no d=0.
;; ============================================================

(deftest threshold-is-ceil-n-over-3-floor-1
  (is (= 1 (candidates/threshold "ab")))
  (is (= 2 (candidates/threshold "even")))     ; ⌈4/3⌉
  (is (= 3 (candidates/threshold "transct!")))) ; ⌈8/3⌉

(deftest rank-candidates-band-and-order
  (let [cands (candidates/rank-candidates
                "even" ["even?" "eval" "evens" "reduce" "even"])]
    (testing "an exact (resolving) name is NEVER a candidate (d=0 excluded)"
      (is (not-any? #(= "even" (:seon.repair/to %)) cands)))
    (testing "distance-ranked, nearest first"
      (is (= "even?" (:seon.repair/to (first cands))))
      (is (= 1 (:seon.repair/distance (first cands)))))
    (testing "outside the ⌈n/3⌉=2 band is dropped"
      (is (not-any? #(= "reduce" (:seon.repair/to %)) cands))))
  (testing "k-cap holds"
    (is (<= (count (candidates/rank-candidates
                     "aa" ["ab" "ac" "ad" "ae" "af" "ag" "ah"]))
            candidates/max-candidates))))

(deftest nearest-tier-takes-only-the-min-distance
  (let [cands [{:seon.repair/to "a" :seon.repair/distance 1}
               {:seon.repair/to "b" :seon.repair/distance 1}
               {:seon.repair/to "c" :seon.repair/distance 2}]]
    (is (= ["a" "b"] (mapv :seon.repair/to (candidates/nearest-tier cands))))
    (is (= [] (candidates/nearest-tier [])))))

;; ============================================================
;; pick-winner — unique passer wins; 2+ ambiguous; deeper tiers never
;; tried past a populated nearer tier (the transct!→tapset lesson).
;; ============================================================

(defn- passes-set
  "A stubbed async trial: candidate passes iff its name is in `oks`.
   Records every trialled name into `seen`."
  [oks seen]
  (fn [c]
    (let [to (:seon.repair/to c)]
      (swap! seen conj to)
      (js/Promise.resolve (contains? oks to)))))

(def ^:private never-over (constantly false))

(deftest unique-winner-in-nearest-tier-wins
  (async done
    (let [seen (atom [])
          cands [{:seon.repair/to "even?" :seon.repair/distance 1}
                 {:seon.repair/to "eval" :seon.repair/distance 2}]]
      (-> (candidates/pick-winner
            {:seon.repair/cands cands
             :seon.repair/passes? (passes-set #{"even?" "eval"} seen)
             :seon.repair/over? never-over})
          (.then (fn [pick]
                   (is (= "even?" (get-in pick [:seon.repair/winner
                                                :seon.repair/to])))
                   (is (= ["even?"] @seen)
                       "the d=2 tier was NEVER trialled — nearest tier only")
                   (done)))))))

(deftest failing-nearest-tier-never-falls-through
  (async done
    (let [seen (atom [])
          cands [{:seon.repair/to "tapset" :seon.repair/distance 2}
                 {:seon.repair/to "transact!" :seon.repair/distance 3}]]
      (-> (candidates/pick-winner
            {:seon.repair/cands cands
             ;; only the FARTHER candidate would pass — must NOT be tried
             :seon.repair/passes? (passes-set #{"transact!"} seen)
             :seon.repair/over? never-over})
          (.then (fn [pick]
                   (is (true? (:seon.repair/none? pick))
                       "a failing nearest tier is a REFUSAL, not fall-through")
                   (is (= ["tapset"] @seen))
                   (done)))))))

(deftest two-passers-is-ambiguous-refusal
  (async done
    (let [cands [{:seon.repair/to "thing-aa" :seon.repair/distance 1}
                 {:seon.repair/to "thing-ab" :seon.repair/distance 1}]]
      (-> (candidates/pick-winner
            {:seon.repair/cands cands
             :seon.repair/passes? (passes-set #{"thing-aa" "thing-ab"} (atom []))
             :seon.repair/over? never-over})
          (.then (fn [pick]
                   (is (= 2 (count (:seon.repair/ambiguous pick))))
                   (is (nil? (:seon.repair/winner pick)))
                   (done)))))))

(deftest empty-candidates-and-budget
  (async done
    (-> (candidates/pick-winner {:seon.repair/cands []
                                 :seon.repair/passes? (fn [_] (js/Promise.resolve true))
                                 :seon.repair/over? never-over})
        (.then (fn [pick]
                 (is (true? (:seon.repair/none? pick)))
                 (candidates/pick-winner
                   {:seon.repair/cands [{:seon.repair/to "x"
                                         :seon.repair/distance 1}]
                    :seon.repair/passes? (fn [_] (js/Promise.resolve true))
                    :seon.repair/over? (constantly true)})))
        (.then (fn [pick]
                 (is (true? (:seon.repair/budget? pick))
                     "over-budget refuses instead of guessing")
                 (done))))))
