(ns datahike.versioning-test
  "Datahike's commits as Seon's merge and reactions read them: what a commit changed, kept beside it
  (task datahike.versioning/a-commits-changes-are-kept-and-read-in-their-size)."
  (:require [clojure.test :refer [deftest is]] [datahike.api :as d] [seon.test-store :as ts] [seon.db :as pdb]))

(defn- facts
  "datoms as [e a v added] facts, a set: what a change is, its transaction aside."
  [ds]
  (into #{} (map (fn [x] [(:e x) (:a x) (:v x) (:added x)])) ds))

;; Estimate: a test branch (~1M), three transacts and their commits (~1M), one d/changes over three commits
;; (3 reads, ~10 datoms: ~0.1M), one datoms-diff of the branch's start and head for the answer (~5M), ~7M = 7000000
(deftest ^{:seon.test/estimate {:ticks 7000000}} a-commits-changes-are-read-in-their-size
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
        (is (< (* 10 dt) diff-dt) (str "and costs what changed, not the index: " dt " ticks against the diff's " diff-dt))))))
