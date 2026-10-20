(ns seon.error-test
  "Recorded errors (lane R4): the signature, the fold and its window write, the
  reconciler's file and resolve, a recurrence reopening, the filed tasks' evidence.
  Run: bin/test seon.error-test"
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.db :as pdb] [seon.error :as err] [seon.template :as tpl] [seon.reconcile :as r] [seon.task :as task]))

(defn- refusal [op v]
  {:seon.error/type :seon.instrument/contract-error :seon.error/operation op :seon.error/member :malli.core/invalid-input
   :seon.error/message (str op " invalid-input: its arguments do not satisfy its contract")
   :seon.error/problems [{:path [0] :in [0] :expected :seon.host/db :value v}]})

(deftest one-cause-one-signature
  (is (= (err/signature (refusal 'app/f nil)) (err/signature (refusal 'app/f 42))) "values never enter it")
  (is (not= (err/signature (refusal 'app/f nil)) (err/signature (refusal 'app/g nil))) "another producer, another signature")
  (is (= (err/signature {:class "IllegalStateException" :message "slot 17 of 64 taken"})
         (err/signature {:class "IllegalStateException" :message "slot 3 of 64 taken"})) "digits are normalised"))

(defn- conn-with-program []
  (let [[_ conn] (pdb/create! 0)]
    (d/transact conn [{:ns/name 'app}
                      {:var/name 'app/f :var/src "(defn f [db] (d/q '[:find ?e] db))" :var/kind :fn}
                      {:var/name 'app/caller :var/src "(defn caller [] (app/f nil))" :var/kind :fn :var/refs #{'app/f}}
                      {:var/name 'app/other :var/src "(defn other [x] (app/f x))" :var/kind :fn :var/refs #{'app/f}}])
    conn))

(defn- occurrences [n op agents]
  (for [i (range n)] (err/occurrence (refusal op i) {:agent (nth agents (mod i (count agents))) :form "(app/caller)"} (+ 1000 i))))

(deftest a-storm-is-one-write-per-window
  (let [conn (conn-with-program)
        f (reduce err/fold {} (concat (occurrences 1000 'app/f [:a1 :a2]) (occurrences 10 'app/g [:a3])))
        [f tx] (err/flush-tx f @conn)]
    (is (= 2 (count tx)) "1,010 occurrences, two signatures, two entities")
    (d/transact conn tx)
    (let [rf (err/record @conn (err/signature (refusal 'app/f nil)))]
      (is (= [1000 2 'app/f] [(:seon.error/count rf) (:seon.error/agents rf) (:seon.error/producer rf)]))
      (is (= "(defn f [db] (d/q '[:find ?e] db))" (:seon.error/producer-src rf)) "the producer's definition at first record")
      (is (= "(app/caller)" (:seon.error/form rf))))
    (testing "an unchanged window writes nothing"
      (is (= [] (second (err/flush-tx f @conn)))))
    (testing "counts are absolute and continue across windows and across a restart"
      (let [[f tx] (err/flush-tx (reduce err/fold f (occurrences 5 'app/f [:a1])) @conn)]
        (d/transact conn tx)
        (is (= 1005 (:seon.error/count (err/record @conn (err/signature (refusal 'app/f nil))))))
        (let [[_ tx] (err/flush-tx (reduce err/fold {} (occurrences 7 'app/f [:a9])) @conn)]
          (d/transact conn tx)
          (is (= 1012 (:seon.error/count (err/record @conn (err/signature (refusal 'app/f nil))))) "a new boot's fold adds to the stored count"))))))

(defn- record! [conn n op agents]
  (let [[_ tx] (err/flush-tx (reduce err/fold {} (occurrences n op agents)) @conn)] (d/transact conn tx)))

(defn- file! [conn s]
  (d/transact conn (err/file-tx @conn s tpl/templates)))

(deftest filed-once-with-evidence
  (let [conn (conn-with-program)
        s (err/signature (refusal 'app/f nil))]
    (record! conn 2 'app/f [:a1])
    (is (= [] (err/actions @conn (r/view @conn) err/dials)) "2 occurrences in 1 agent: not yet")
    (record! conn 1 'app/f [:a1])
    (is (= [{:op :file :signature s}] (err/actions @conn (r/view @conn) err/dials)))
    (file! conn s)
    (is (= [] (err/actions @conn (r/view @conn) err/dials)) "its task exists: filed once")
    (let [ts (task/tasks @conn)
          prove (get ts (str (err/task-id s 0) "-prove"))
          fix (get ts (err/task-id s 0))]
      (is (= [:design :work :merge] (map #(get-in ts [% :seon.task/kind]) [(str (err/task-id s 0) "-prove") (err/task-id s 0) (str (err/task-id s 0) "-merge")])))
      (is (= #{:running} (set (map :seon.task/execution [prove fix]))) "released")
      (is (= ['app/caller 'app/other] (get-in prove [:seon.task/evidence :callers])) "callers from the reference facts")
      (is (= 3 (get-in prove [:seon.task/evidence :count])))
      (is (re-find #"`app/f`" (get-in fix [:seon.task/accumulated :plan])) "the plan flows along :needs, the producer a reference")
      (is (= (err/task-id s 0) (:seon.error/task (err/record @conn s))) "the error names its task"))))

(deftest resolved-by-deletion-or-a-green-change-then-reopened
  (let [conn (conn-with-program) s (err/signature (refusal 'app/f nil))]
    (record! conn 3 'app/f [:a1])
    (file! conn s)
    (testing "the producer changed but its task is not done: still open"
      (d/transact conn [{:var/name 'app/f :var/src "(defn f [db] (d/q '[:find ?e :where [?e :x]] db))"}])
      (is (= [] (err/actions @conn (r/view @conn) err/dials))))
    (testing "the task merged with its proof green: resolved"
      (let [fix (err/task-id s 0)]
        (d/transact conn [{:seon.task/id fix :seon.task/proofs #{'app.f-test/repro}}
                          {:seon.attempt/id (str fix "#1") :seon.attempt/task [:seon.task/id fix] :seon.attempt/status :done
                           :seon.attempt/boot "b" :seon.attempt/agent :a :seon.attempt/start-ms 0 :seon.attempt/branch :b :seon.attempt/max-turns 8
                           :seon.attempt/results [{:seon.proof/test 'app.f-test/repro :seon.proof/status :pass}]}])
        (is (= [{:op :resolve :signature s :why :producer-changed-green}] (err/actions @conn (r/view @conn) err/dials)))
        (d/transact conn (err/resolve-tx s 5000))))
    (testing "it occurs again: reopened as generation 1 and filed again"
      (record! conn 3 'app/f [:a2])
      (let [rec (err/record @conn s)]
        (is (nil? (:seon.error/resolved-ms rec)))
        (is (= 1 (:seon.error/generation rec)))
        (is (= [{:op :file :signature s}] (err/actions @conn (r/view @conn) err/dials)))
        (file! conn s)
        (is (some? (get (task/tasks @conn) (err/task-id s 1))) "task -r1")))
    (testing "the producer is deleted: resolved"
      (d/transact conn [[:db/retractEntity [:var/name 'app/f]]])
      (is (= :producer-deleted (:why (first (err/actions @conn (r/view @conn) err/dials))))))))

(deftest the-reconciler-proc-counts-a-storm-in-few-writes
  (let [conn (conn-with-program)
        rp #'r/reconciler-proc
        st (rp {:boot "b" :provider {:kind :scripted :script 'seon.fixtures/reply}})
        msg {:op :error :pid :writer :message "x" :data (refusal 'app/f 1)}
        ;; before main is known: folded, nothing written
        [st o1] (rp st :errors msg)
        _ (is (= [] (:to-api o1)))
        ;; main arrives: one write with the fold
        [st o2] (rp st :main {:db @conn :ns 0})
        w (filter #(= :write (get-in % [:req :op])) (:to-api o2))
        _ (is (= 1 (count w)))
        ;; 500 more while that write is in flight: folded, no write
        [st outs] (reduce (fn [[st acc] _] (let [[st o] (rp st :errors msg)] [st (into acc (:to-api o))])) [st []] (range 500))
        _ (is (= [] outs) "one write in flight at a time")
        _ (d/transact conn (get-in (first w) [:req :req :tx-data]))
        ;; its answer: the next write carries all 500 at once
        [st o3] (rp st :answers {:tag (:tag (first w)) :r {:ok true}})
        w2 (filter #(= :write (get-in % [:req :op])) (:to-api o3))]
    (is (= 1 (count w2)))
    (is (= 501 (:seon.error/count (first (filter :seon.error/count (get-in (first w2) [:req :req :tx-data]))))))))
