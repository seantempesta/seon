(ns seon.agent.ctx.subagents-test
  "Remote acquisition and pure formatting for child-agent context blocks."
  (:require
    [cljs.test :refer [async deftest is testing]]
    [clojure.string :as str]
    [seon.agent.ctx.subagents :as sub]
    [seon.ai.tokens :as tokens]
    [seon.db :as db]
    [seon.db.protocol :as protocol]))

(def ^:private point
  {:seon.db.coordinate/database-id #uuid "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
   :seon.db.coordinate/branch :db
   :seon.db.coordinate/commit-id #uuid "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
   :seon.db.coordinate/t 42})

(def ^:private absent :seon.agent.ctx.subagents/absent)
(def ^:private now (js/Date. 7200000))

(defn- format-block [data]
  ((deref #'sub/format-subagents-block)
   (merge {:seon.agent/id "parent"
           ::sub/children []
           ::sub/overflow? false
           ::sub/open-runs []
           ::sub/turn-counts []
           ::sub/closed-runs []
           ::sub/crash-counts []
           ::sub/now now
           ::sub/breaker-n 3
           ::sub/breaker-w 1800000}
          data)))

(defn- success [result]
  {::protocol/success? true ::protocol/result result})

(deftest ordinary-child-data-renders-every-derived-state
  (testing "no children vanish"
    (is (= "" (format-block {}))))
  (testing "an open run uses grouped turn and crash counts"
    (let [out (format-block
                {::sub/children [["running-child" "research duckdb" absent]]
                 ::sub/open-runs
                 [["running-child" "run-1" 8 (js/Date. 7195000) absent]]
                 ::sub/turn-counts [["running-child" 2]]
                 ::sub/crash-counts [["running-child" 3]]})]
      (is (str/includes? out "running-child [running]"))
      (is (str/includes? out "turn 2/8 · beat 5s ago"))
      (is (str/includes? out "schedule-wake paused"))))
  (testing "a paused run uses the one state rule"
    (let [out (format-block
                {::sub/children [["paused-child" "waiting" absent]]
                 ::sub/open-runs
                 [["paused-child" "run-2" 5 absent (js/Date. 7100000)]]})]
      (is (str/includes? out "paused-child [paused]"))))
  (testing "termination wins over an inconsistent open-run projection"
    (let [out (format-block
                {::sub/children
                 [["terminated-child" "finished" (js/Date. 7150000)]]
                 ::sub/open-runs
                 [["terminated-child" "run-3" 5 absent absent]]})]
      (is (str/includes? out "terminated-child [terminated]")))))

(deftest latest-closed-run-renders-result-reference-or-death
  (let [out (format-block
              {::sub/children
               [["completed-child" "compute" absent]
                ["failed-child" "risky" absent]]
               ::sub/closed-runs
               [["completed-child" "old" (js/Date. 1000) :error "" -1]
                ["completed-child" "new" (js/Date. 2000) :completed
                 "the answer is 42" 99]
                ["failed-child" "failed" (js/Date. 3000) :error "" -1]]})]
    (is (str/includes? out "completed: the answer is 42 [→ eid 99]"))
    (is (str/includes? out "failed-child [idle] · ✗ error"))))

(deftest child-formatting-is-bounded-and-overflow-is-truthful
  (let [children (mapv (fn [i]
                         [(str "child-" i) (apply str (repeat 300 "x")) absent])
                       (range 20))
        out (format-block {::sub/children children ::sub/overflow? true})]
    (is (str/includes? out "the 20+ agents you spawned"))
    (is (str/includes? out "more children"))
    (is (<= (tokens/estimate out) 900))))

(deftest prompt-acquisition-is-bounded-and-coordinate-pinned
  (async done
    (let [original db/execute-many
          requests (atom [])
          contexts (atom [])
          responses
          (atom
            [{::db/coordinate point
              ::db/results
              [(success [["child" "compute" absent]])
               (success {:seon.config.breaker/crash-count 4
                         :seon.config.breaker/window-ms 60000})]}
             {::db/coordinate point
              ::db/results
              [(success [["child" "run" 6 absent absent]])
               (success [["child" 1]])
               (success [])
               (success [["child" 4]])]}])]
      (set! db/execute-many
            (fn [request]
              (swap! requests conj request)
              (swap! contexts conj (db/current-tx-context))
              (let [response (first @responses)]
                (swap! responses subvec 1)
                (js/Promise.resolve response))))
      (-> (db/with-tx-context
            {::db/coordinate point}
            #(sub/subagents-block {:seon.agent/id "parent"} nil))
          (.then
            (fn [out]
              (is (str/includes? out "child [running]"))
              (is (= [2 4] (mapv (comp count ::db/members) @requests)))
              (is (nil? (::db/coordinate (first @requests)))
                  "the first request inherits the active coordinate")
              (is (= point (::db/coordinate (second @requests)))
                  "dependent detail acquisition is pinned to the same coordinate")
              (is (every? #(= point (::db/coordinate %)) @contexts)
                  "both requests execute inside the inherited coordinate context")
              (is (every? #(not (contains? % :seon.db/db)) @requests)
                  "no Datahike value crosses the prompt owner")
              (set! db/execute-many original)
              (done)))
          (.catch
            (fn [error]
              (is false (str "remote subagent acquisition rejected: " error))
              (set! db/execute-many original)
              (done)))))))

(deftest childless-acquisition-skips-the-detail-request
  (async done
    (let [original db/execute-many
          calls (atom 0)]
      (set! db/execute-many
            (fn [_]
              (swap! calls inc)
              (js/Promise.resolve
                {::db/coordinate point
                 ::db/results [(success []) (success {})]})))
      (-> (sub/subagents-block {:seon.agent/id "parent"} nil)
          (.then (fn [out]
                   (is (= "" out))
                   (is (= 1 @calls))
                   (set! db/execute-many original)
                   (done)))
          (.catch
            (fn [error]
              (is false (str "childless acquisition rejected: " error))
              (set! db/execute-many original)
              (done)))))))

(deftest orphaned-owner-still-uses-one-bounded-remote-request
  (async done
    (let [original db/execute-many
          observed (atom nil)]
      (set! db/execute-many
            (fn [request]
              (reset! observed request)
              (js/Promise.resolve
                {::db/coordinate point
                 ::db/results
                 [(success [["orphan" "dead-parent" "research"]])
                  (success [["orphan" :running]])]})))
      (-> (sub/orphaned-agents-block {} nil)
          (.then (fn [out]
                   (is (str/includes? out "orphan [running]"))
                   (is (str/includes? out "parent dead-parent"))
                   (is (= 2 (count (::db/members @observed))))
                   (set! db/execute-many original)
                   (done)))
          (.catch
            (fn [error]
              (is false (str "orphan acquisition rejected: " error))
              (set! db/execute-many original)
              (done)))))))
