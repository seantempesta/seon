(ns seon.union-test
  "U, the union of every branch's history (seon.union): one plain d/q over (d/history U) joins
  what different branches wrote, and U is kept by inserting what each commit adds."
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is testing]]
            [datahike.api :as d] [seon.db :as pdb] [seon.union :as u]))

(def conversation
  "The owner's example: root's message on main, the agent's reply on its branch, as one query."
  '[:find ?body ?reply ?branch
    :in $ ?agent
    :where [?m :seon.message/to ?agent] [?m :seon.message/id ?id] [?m :seon.message/body ?body]
           [?r :seon.message/reply-to ?id ?tx] [?r :seon.message/body ?reply]
           [?tx :seon.tx/branch ?branch]])

(deftest a-message-and-its-reply-on-two-branches-are-one-query
  (ts/test-branch!
   (fn [cfg conn]
     (let [main (:branch cfg)
           b (keyword (str (name main) "-a1"))
           _ (d/branch! conn main b)
           bc (d/connect (assoc cfg :branch b))]
       (try
         ;; root writes on main after the agent forked: the agent's branch never holds it
         (d/transact conn {:tx-data [{:seon.message/id "m1" :seon.message/from "root" :seon.message/to "demo/f#1" :seon.message/body "main moved, merge main"}]
                           :tx-meta (pdb/tx-meta main)})
         (d/transact bc {:tx-data [{:seon.message/id "r1" :seon.message/from "demo/f#1" :seon.message/reply-to "m1" :seon.message/body "merged; 3 forms changed"}]
                         :tx-meta (pdb/tx-meta b)})
         (let [un (-> (u/empty-union @conn)
                      (u/of-history main @conn)
                      (u/catch-up b (pdb/branch-base conn main b) @bc))
               U (:db un)]
           (testing "neither branch holds the conversation"
             (is (empty? (d/q conversation @conn "demo/f#1")))
             (is (empty? (d/q conversation @bc "demo/f#1"))))
           (testing "the union holds it: one plain query, and each datom's transaction names its branch"
             (is (= #{["main moved, merge main" "merged; 3 forms changed" b]} (d/q conversation (d/history U) "demo/f#1"))))
           (testing "U applied each branch at its head"
             (is (= {main (d/commit-id @conn) b (d/commit-id @bc)} (select-keys (:applied un) [main b]))))
           (testing "a later commit on the branch is its datoms inserted; one already held is held once"
             (d/transact bc {:tx-data [{:seon.message/id "r2" :seon.message/from "demo/f#1" :seon.message/reply-to "m1" :seon.message/body "tests green"}]
                             :tx-meta (pdb/tx-meta b)})
             (let [before (count (d/datoms (d/history U) :eavt))
                   un2 (u/catch-up un b (d/commit-as-db conn (get-in un [:applied b])) @bc)
                   un3 (u/catch-up un2 b (d/commit-as-db conn (get-in un [:applied b])) @bc)
                   U2 (:db un2)]
               (is (= #{["main moved, merge main" "merged; 3 forms changed" b] ["main moved, merge main" "tests green" b]}
                      (d/q conversation (d/history U2) "demo/f#1")))
               (is (< before (count (d/datoms (d/history U2) :eavt))))
               (is (= (count (d/datoms (d/history U2) :eavt)) (count (d/datoms (d/history (:db un3)) :eavt))) "inserting again adds nothing"))))
         (finally (d/release bc) (d/delete-branch! conn b)))))))
