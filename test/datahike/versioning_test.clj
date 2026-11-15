(ns datahike.versioning-test
  "Datahike's commits as Seon's merge and reactions read them: what a commit changed, kept beside it
  (task datahike.versioning/a-commits-changes-are-kept-and-read-in-their-size)."
  (:require [clojure.test :refer [deftest is]] [datahike.api :as d] [seon.test-store :as ts] [seon.db :as pdb]))

(defn- facts
  "datoms as [e a v added] facts, a set: what a change is, its transaction aside."
  [ds]
  (into #{} (map (fn [x] [(:e x) (:a x) (:v x) (:added x)])) ds))

;; Estimate: a test branch (~0.5M), three transacts and their commits (~0.4M), one d/changes over three commits
;; (3 commit records and 3 changes read, ~10 datoms: ~0.06M), one datoms-diff of the branch's start and head for the
;; answer (~0.1M: few nodes differ), ~1.2M = 1200000 (7M first, the diff taken as a merge's; measured 1.08M)
(deftest ^{:seon.test/estimate {:ticks 1200000}} a-commits-changes-are-read-in-their-size
  ;; every "what changed between X and Y" diffed whole index values (seon.merge/own-datoms ~3.1B ticks on lb76's change,
  ;; 30x its apply): each commit's changes are kept beside it, and d/changes reads the commits between two, netted by
  ;; [e a v], never the indexes
  (ts/test-branch!
    (fn [_ conn]
      (let [c0 (d/commit-id @conn)
            start @conn
            e1 (get (:tempids (d/transact conn [{:db/id "a" :note/text "first" :note/agent :one}])) "a")
            _ (d/transact conn [{:note/text "second" :note/agent :two}])
            _ (d/transact conn [[:db/retract e1 :note/text "first"] [:db/add e1 :note/text "first, edited"]])
            head @conn
            c3 (d/commit-id head)
            t0 (jolt.host/ticks)
            changed (d/changes conn c0 c3)
            dt (- (jolt.host/ticks) t0)
            t1 (jolt.host/ticks)
            [gone added] (d/datoms-diff start head :eavt [])
            diff-dt (- (jolt.host/ticks) t1)
            want (into (facts (remove #(= :db/txInstant (:a %)) added))
                       (map (fn [[e a v _]] [e a v false]))
                       (facts (remove #(= :db/txInstant (:a %)) gone)))]
        (is (= want (facts (remove #(= :db/txInstant (:a %)) changed)))
            (str "the commits' net change is the diff's: " (pr-str (facts changed)) " against " (pr-str want)))
        (is (not-any? #(= [e1 :note/text "first"] [(:e %) (:a %) (:v %)]) changed)
            "a fact added and retracted between the two nets out")
        ;; three commits: three commit records and three changes values read, ~20k ticks each, and their ~10 datoms; flat in
        ;; the store's size (a merge's diff at d = 7,163 is ~139M, lb76's own-datoms ~3.1B)
        (is (< dt 200000) (str "and costs its three commits, never the index: " dt " ticks (the diff here: " diff-dt ")"))))))
