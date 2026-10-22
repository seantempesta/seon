(ns seon.patterns-test
  "Proofs that our procs follow the idioms in [flow](docs/flow.md#idioms-for-seon), run as
  plain step-fn calls on the real code (no graph, no clock). From jolt/patterns (49d2842,
  35e1208): the backlog bound (`seon.fx/bound`) and competing workers (`seon.fx/worker-proc`).
  The branch's delayed-retry and credit proofs exercised step-fns defined in the test, which
  prove no code of ours, and its group commit is `seon.procs-test/the-flush-is-continuous-group-commit`."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [seon.config :as cfg]
            [seon.fx :as fx]))

(deftest a-backlog-is-the-unread-queue
  (testing "seon.fx/bound: at :seon.config.fx/buf queued requests the owner stops reading :in, still reads :done"
    (let [buf (cfg/value :seon.config.fx/buf)
          full {:queue (into (sorted-map) (for [i (range buf)] [[0 i] {}]))}
          reads (::flow/input-filter (#'fx/bound full))]
      (is (not (reads :in)))
      (is (reads :done))
      (testing "one below the bound it reads :in again"
        (is ((::flow/input-filter (#'fx/bound (update full :queue #(dissoc % (key (first %)))))) :in))))))

(deftest workers-compete-for-one-shared-in-port
  (let [work (a/chan 4)
        worker #(fx/worker-proc {:perform identity :kind :k :owner :fx/k :work work})
        l1 (worker) l2 (worker)]
    (is (identical? (get-in l1 [::flow/in-ports :work]) (get-in l2 [::flow/in-ports :work])) "every worker reads the one channel")
    (a/>!! work {:req 1})
    (is (= {:req 1} (a/poll! (get-in l1 [::flow/in-ports :work]))) "a request is taken by one worker")
    (is (nil? (a/poll! (get-in l2 [::flow/in-ports :work]))) "and no other: work is shared, not broadcast")))
