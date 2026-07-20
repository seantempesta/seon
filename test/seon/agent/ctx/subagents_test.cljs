(ns seon.agent.ctx.subagents-test
  "Remote acquisition and pure formatting for child-agent context blocks."
  (:require
    [cljs.test :refer [async deftest is testing]]
    [clojure.string :as str]
    [seon.agent.ctx.subagents :as sub]
    [seon.ai.tokens :as tokens]
    [seon.db :as db]
    [seon.db.protocol :as protocol]))

(def ^:private database
  {:datahike/commit-id #uuid "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
   :max-tx 42})

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
    (is (str/includes? out "failed-child [idle] risky · ✗ error"))))

(deftest child-formatting-is-bounded-and-overflow-is-truthful
  (let [children (mapv (fn [i]
                         [(str "child-" i) (apply str (repeat 300 "x")) absent])
                       (range 20))
        out (format-block {::sub/children children ::sub/overflow? true})]
    (is (str/includes? out "the 20+ agents you spawned"))
    (is (str/includes? out "more children"))
    (is (<= (tokens/estimate out) 900))))

(deftest prompt-acquisition-is-bounded-and-database-value-pinned
  (async done
    (let [original db/execute-many
          requests (atom [])
          contexts (atom [])
          responses
          (atom
            [{::db/results
              [(success [["child" "compute" absent]])
               (success {:seon.config.breaker/crash-count 4
                         :seon.config.breaker/window-ms 60000})
               (success now)]}
             {::db/results
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
            {::db/db database}
            #(sub/subagents-block {:seon.agent/id "parent"} nil))
          (.then
            (fn [out]
              (is (str/includes? out "child [running]"))
              (is (= [3 4] (mapv (comp count ::db/members) @requests)))
              (is (every? #(identical? database (::db/db %)) @requests)
                  "both batches use the inherited database value")
              (is (every? #(identical? database (::db/db %)) @contexts)
                  "both requests execute inside the inherited database context")
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
                {::db/results [(success []) (success {}) (success now)]})))
      (-> (sub/subagents-block {:seon.agent/id "parent" ::db/db database} nil)
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

(deftest no-orphans-render-as-absent-with-explicit-query-arguments
  (async done
    (let [original db/execute-many
          observed (atom nil)]
      (set! db/execute-many
            (fn [request]
              (reset! observed request)
              (js/Promise.resolve
               {::db/results [(success #{}) (success #{})]})))
      (-> (sub/orphaned-agents-block {::db/db database} nil)
          (.then
           (fn [out]
             (is (= "" out))
             (is (= [[] []]
                    (mapv ::protocol/arguments (::db/members @observed)))
                 "every protocol query member carries its arguments vector")))
          (.catch
           (fn [error]
             (is false (str "empty orphan acquisition rejected: " error))))
          (.finally
           (fn []
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
                {::db/results
                 [(success [["orphan" "dead-parent" "research"]])
                  (success [["orphan" :running]])]})))
      (-> (sub/orphaned-agents-block {::db/db database} nil)
          (.then (fn [out]
                   (is (str/includes? out "orphan [running]"))
                   (is (str/includes? out "parent dead-parent"))
                   (is (= 2 (count (::db/members @observed))))
                   (is (identical? database (::db/db @observed)))
                   (set! db/execute-many original)
                   (done)))
          (.catch
            (fn [error]
              (is false (str "orphan acquisition rejected: " error))
              (set! db/execute-many original)
              (done)))))))
