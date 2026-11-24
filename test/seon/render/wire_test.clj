(ns seon.render.wire-test
  "Proof tests for unified :zone :agent-prefix recursive <agent> wire rendering (:mode :example and :mode :continue)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.model.messages :as messages]
            [seon.render.wire :as wire]
            [seon.store :as store]
            [seon.test-fixture :refer [with-test-store]]
            [seon.transactor.spawn :as tspawn]))

(deftest ^{:cost/ticks 140000000} unified-agent-prefix-fork-preserves-kv-cache-and-renders-examples
  (testing "ONE :zone :agent-prefix mechanism renders both closed <agent kind=example> and verbatim <agent forked-from=...> with 100% parent KV prefix match and :already-shown delta"
    (with-test-store [{:keys [conn cfg]}]
      ;; Seed an example agent + a worker with multi-turn evals
      (d/transact conn {:tx-data
        [{:db/id "ex1" :seon.agent/id :example/demo-check :seon.agent/branch :main :seon.agent/goal "Demonstrate clean check"}
         {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :example/demo-check :seon.agent.eval/agent-ref "ex1"
          :seon.agent.eval/turn 1 :seon.agent.eval/call-index 0 :seon.agent.eval/call-id "ex-c1"
          :seon.agent.eval/code "(user/health)" :seon.agent.eval/value "{:ok? true}"}
         {:seon.task/id :task/w-demo :seon.task/title "Demo worker task" :seon.task/plan "Build demo" :seon.task/done? false}
         {:seon.task/id :template/review-and-improve :seon.task/title "Review forked session" :seon.task/plan "Audit and compact." :seon.task/template? true :seon.task/done? false}
         {:db/id "w1" :seon.agent/id :w/kv-demo :seon.agent/branch :branch/w-kv-demo :seon.agent/goal "Build demo" :seon.agent/tasks #{[:seon.task/id :task/w-demo]}
          :seon.agent/example-refs #{"ex1"}}]})
      (store/branch! conn :main :branch/w-kv-demo)
      (let [wconn (store/connect cfg :branch/w-kv-demo)]
        (try
          (let [we (:e (first (d/datoms @wconn :avet :seon.agent/id :w/kv-demo)))]
            (d/transact wconn {:tx-data
              (for [t (range 1 5)]
                {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :w/kv-demo :seon.agent.eval/agent-ref we
                 :seon.agent.eval/turn t :seon.agent.eval/call-index 0 :seon.agent.eval/call-id (str "wc-" t)
                 :seon.agent.eval/intent (str "Step " t) :seon.agent.eval/code (str "(+ " t " 1)") :seon.agent.eval/value (str (inc t))})}))
          ;; 1. Worker wire includes closed <agent id=":example/demo-check" kind="example">...</agent> before its own open <agent>
          (let [w-wire (wire/build-live-wire-request @conn @wconn :w/kv-demo {:conn conn :vision? false})
                w-core (let [ms (:messages w-wire)]
                (if (str/includes? (str (:content (last ms))) "ephemeral=\"true\"") (vec (butlast ms)) (vec ms)))
                ex-hdr (some #(when (str/includes? (str (:content %)) "kind=\"example\"") (:content %)) w-core)]
            (is (some? ex-hdr) "Worker must receive closed <agent kind=\"example\"> from :seon.agent/example-refs")
            (is (str/includes? (str ex-hdr) "<agent id=\":example/demo-check\""))
            ;; 2. Spawn COW-forked reviewer off :branch/w-kv-demo
            (let [st0 {:conn conn :cfg cfg :msg-seq 0 :writes 0 :commit-git? false}
                  _ (tspawn/handle-spawn-agent! st0
                      {:op :spawn-agent :id :review/kv-demo :branch :branch/rev-kv-demo :parent :w/kv-demo
                       :forked-from :w/kv-demo :tasks [:template/review-and-improve] :goal "Review :w/kv-demo"}
                      (d/commit-id @conn))
                  rconn (store/connect cfg :branch/rev-kv-demo)]
              (try
                (let [r-wire (wire/build-live-wire-request @conn @rconn :review/kv-demo {:conn conn :vision? false})
                      msg-eq? (fn [a b] (= (select-keys a [:role :content :tool_calls :tool_call_id :reasoning_content])
                                           (select-keys b [:role :content :tool_calls :tool_call_id :reasoning_content])))
                      matched (count (take-while true? (map msg-eq? w-core (:messages r-wire))))
                      boundary (:content (nth (:messages r-wire) matched nil))]
                  (is (= (count w-core) matched) "Forked reviewer must match 100% of worker's pre-tail wire prefix byte-for-byte!")
                  (is (str/starts-with? (str boundary) "</agent>\n<agent id=\":review/kv-demo\""))
                  (is (str/includes? (str boundary) "forked-from=\":w/kv-demo\""))
                  (is (str/includes? (str boundary) "### [forked-agent-evals"))
                  (is (str/includes? (str boundary) ":template/review-and-improve"))
                  ;; Verify :already-shown skipped unchanged worker task!
                  (is (some #(= :unchanged (:status %)) (:tiles r-wire)) "Unchanged worker entities must be skipped via :already-shown!"))
                (finally (d/release rconn)))))
          (finally (d/release wconn)))))))
