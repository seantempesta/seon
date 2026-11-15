(ns datahike.versioning-test
  "Datahike's commits as Seon's merge and reactions read them: what a commit changed, kept beside it
  (task datahike.versioning/a-commits-changes-are-kept-and-read-in-their-size)."
  (:require [clojure.test :refer [deftest is]] [datahike.api :as d] [seon.test-store :as ts] [seon.db :as pdb] [konserve.core] [datahike.writing]))

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
        (is (< dt 200000) (str "and costs its three commits, never the index: " dt " ticks (the diff here: " diff-dt ")"))
        ;; what a commit writes for its changes, as the store encodes it (konserve.jolt.fasl-store, before zstd): estimate
        ;; 81-87 B a datom measured on main's datoms, plus the key and :base, under 300 B
        (let [ser @(requiring-resolve 'konserve.jolt.fasl-store/serialize)
              ch (konserve.core/get (:store @conn) (datahike.writing/commit-changes-key c3) nil {:sync? true})
              n (count (:datoms ch)) bytes (count (ser ch))]
          (is (<= bytes (+ 300 (* 100 n))) (str "a commit's changes cost their datoms: " bytes " B for " n " datoms")))))))

;; Estimate: a test branch (~0.5M), one transact and commit (~0.15M), a d/with and its force-branch! (~0.3M), the
;; head followed (~0.05M), d/changes over two commits (~0.05M), ~1M = 1000000
(deftest ^{:seon.test/estimate {:ticks 1000000}} a-commit-of-a-speculated-value-keeps-its-changes
  ;; Seon's merges commit a value built by d/with on another (seon.writer/merge-main-into: (d/with main tx), then
  ;; force-branch!), never through the writer's transact: the changes ride every transaction (datahike.core/with), so
  ;; that commit keeps them too, with the commit its value was built on as its base
  (ts/test-branch!
    (fn [cfg conn]
      (d/transact conn [{:note/text "written" :note/agent :w}])
      (let [c1 (d/commit-id @conn)
            v (:db-after (d/with @conn [{:note/text "speculated" :note/agent :s}]))
            _ (d/force-branch! v (:branch cfg) #{c1} {:expected-current-commit c1})
            _ (datahike.connector/follow-head! conn)
            c2 (d/commit-id @conn)
            changed (into #{} (map (fn [x] [(:a x) (:v x) (:added x)])) (d/changes conn c1 c2))]
        (is (not= c1 c2) "force-branch! made a commit")
        (is (contains? changed [:note/text "speculated" true]) (str "its changes are the speculated transaction's: " (pr-str changed)))
        (is (not (contains? changed [:note/text "written" true])) "and nothing before its base")))))

;; Estimate: a test branch (~0.5M), two transacts and commits (~0.3M), two d/with (~0.2M), two force-branch! (~0.4M),
;; two d/changes over one commit each (~0.05M), ~1.5M = 1500000
(deftest ^{:seon.test/estimate {:ticks 1500000}} a-merge-built-on-another-lineage-states-its-change-against-the-head-it-replaces
  ;; landing 6's merge (data, 2026-11-15): the merged value is built on main's head and forced onto the branch, so its
  ;; own change record names main's head as its base and d/changes from the branch head never reaches it
  ;; (:datahike/changes-not-ancestor; seon.merge/changed-datoms then diffed whole heads, and every branch fact looked
  ;; moved). force-branch!'s :changes states the change against the branch head instead, so d/changes from there
  ;; answers exactly it
  (ts/test-branch!
    (fn [cfg conn]
      (d/transact conn [{:note/text "base" :note/agent :base}])
      (let [c0 (d/commit-id @conn)
            base-db @conn
            _ (d/transact conn [{:note/text "the branch's own" :note/agent :branch}])
            c1 (d/commit-id @conn)
            ;; main's change since the base, and the merged value built on main's lineage (the base plus main's change
            ;; plus the branch's own fact replayed)
            main-report (d/with base-db [{:note/text "main's" :note/agent :main}])
            ds (:tx-data main-report)
            merged (:db-after (d/with (:db-after main-report) [{:note/text "the branch's own" :note/agent :branch}]))
            ;; without :changes: the record's base is c0's lineage, so d/changes from c1 is refused
            _ (d/force-branch! merged (:branch cfg) #{c1} {:expected-current-commit c1})
            _ (datahike.connector/follow-head! conn)
            c2 (d/commit-id @conn)
            refused (try (d/changes conn c1 c2) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))
            ;; with :changes: the same main-lineage value forced over the head it replaces (c2), its change stated
            _ (d/force-branch! merged (:branch cfg) #{c2} {:expected-current-commit c2 :changes {:base c2 :datoms ds}})
            _ (datahike.connector/follow-head! conn)
            c3 (d/commit-id @conn)
            changed (d/changes conn c2 c3)]
        ;; refused either way: the walk by :base leaves the branch head's lineage (not-ancestor), or meets the test
        ;; branch's first commit, which kept no changes (unrecorded), before it
        (is (#{:datahike/changes-not-ancestor :datahike/changes-unrecorded} refused) (str "the value's own record does not reach the branch head: " (pr-str refused)))
        (is (= (facts ds) (facts changed)) (str "d/changes from the head it replaced answers exactly the stated change: " (pr-str (facts changed))))
        (is (= c2 (:base (konserve.core/get (:store @conn) (datahike.writing/commit-changes-key c3) nil {:sync? true})))
            "the record names the stated base")))))
