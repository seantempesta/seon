(ns seon.agent-test
  (:require [clojure.core.async :as a]
            [clojure.test :refer [deftest is testing]]
            [seon.agent :as agent]
            [seon.server :as server]
            [seon.store :as store]))

(deftest repl-eval-keeps-worker-warm-and-persists-results
  (let [dir (str "/tmp/seon-agent-test-" (System/nanoTime))
        {:keys [conn cfg]} (store/open! dir)]
    (try
      (testing "eval-route normalizes :agent into :agent-id"
        (let [ch (a/chan 1)
              out ((var server/eval-route) {:conn conn} {:op :eval :branch :branch/demo :agent :repl/demo :code "(+ 1 2)"} ch)
              ev (first (:eval out))]
          (is (= :branch/demo (:branch ev)))
          (is (= :repl/demo (:agent-id ev)))))
      (testing "handle-eval defaults keep-warm? true, honors :agent, reuses idle PID, and honors explicit :keep-warm? false"
        (let [fork-out (a/chan 8)
              st0 {:conn conn :cfg cfg :fork-out fork-out :live {} :eval-waiters {} :req-seq 0 :max-os-procs 4}
              [st1 _] ((var agent/handle-eval) st0 1 {:branch :branch/demo :code "(def x 41)" :reply-ch (a/chan 1)})
              cmd1 (a/poll! fork-out)
              w1 (get-in st1 [:eval-waiters "eval-1"])]
          (is (= :fork-agent (:op cmd1)))
          (is (= :repl/demo (:agent-id cmd1)))
          (is (true? (:keep-warm? w1)))
          (let [st-idle (-> st1 (assoc-in [:live :repl/demo :pid] 4242) (assoc-in [:live :repl/demo :busy?] false))
                [st2 _] ((var agent/handle-eval) st-idle 2 {:branch :branch/demo :code "(inc x)" :reply-ch (a/chan 1)})
                cmd2 (a/poll! fork-out)]
            (is (= :to-child (:op cmd2)))
            (is (= 4242 (:pid cmd2)))
            (is (true? (get-in st2 [:eval-waiters "eval-2" :keep-warm?]))))
          (let [[st3 _] ((var agent/handle-eval) st0 3 {:branch :branch/oneshot :agent :repl/oneshot :keep-warm? false :code "1" :reply-ch (a/chan 1)})]
            (is (false? (get-in st3 [:eval-waiters "eval-1" :keep-warm?])))
            (is (= :repl/oneshot (get-in st3 [:eval-waiters "eval-1" :agent-id]))))))
      (finally
        (store/close! conn)
        (doseq [f (reverse (file-seq (java.io.File. dir)))] (.delete ^java.io.File f))))))
