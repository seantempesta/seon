(ns seon.request-cost-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [are deftest is]]
            [seon.batch :as batch]
            [seon.db :as db]
            [seon.mcp :as mcp]
            [seon.test-support :as support]))

(defn- answer
  "The parsed MCP result of `code` evaluated as `agent-id` on `running`."
  {:malli/schema [:=> [:cat :string :string :string :string] :map]}
  [running agent-id mode code]
  (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                            "arguments" {"cluster" running "agent" agent-id "mode" mode
                                                         "code" code}}))))

(deftest a-reply-carries-its-request-cost
  (support/with-running-agent
   (fn [running agent-id _]
     (let [ping (answer running agent-id "sci" ":ping")
           {total :seon.cost/ms stages :seon.cost/stages} (support/mcp-request ping)
           form-ms (:seon.repl/ms (support/mcp-reply ping) 0)
           defined (support/mcp-request
                    (answer running agent-id "sci" "(in-ns 'request.cost) (defn twice [x] (* 2 x))"))]
       (is (= :ping (:seon.repl/value (support/mcp-reply ping))))
       (is (pos? total) "a ping costs its real wall time, never 0")
       (is (<= (reduce + (vals stages)) (+ total 0.5)) "stages are disjoint parts of the request")
       (is (<= form-ms total))
       (is (every? #(contains? stages %) [:custody :fork :read :evaluate :settle :print]))
       (is (every? #(contains? (:seon.cost/stages defined) %) [:settle :test-step]))))))

(deftest a-jvm-reply-carries-its-request-cost
  (support/with-running-agent
   (fn [running agent-id _]
     (let [{total :seon.cost/ms stages :seon.cost/stages :as cost}
           (support/mcp-request (answer running agent-id "jvm" "(count (vec (range 100000)))"))]
       (is (pos? (:seon.cost/alloc-mb cost 0)) "an evaluated form's allocation is measured")
       (is (contains? cost :seon.cost/cpu-ms) "an evaluated form's CPU time is measured")
       (is (not (contains? cost :seon.cost/unmeasured)))
       (is (pos? total))
       (is (<= (reduce + (vals stages)) (+ total 0.5)))
       (is (every? #(contains? stages %) [:custody :evaluate :settle :print]))))))

(deftest ^{:seon/tasks #{'seon.batch/a-request-cost-counts-only-the-evaluation-arm
                        'seon.repl/a-request-spends-seconds-outside-its-named-stages}}
  a-request-shows-the-all-thread-window-and-accounts-for-unnamed-time
  (support/with-running-agent
   (fn [running agent-id connection]
     (doseq [[kind mode code expected]
             [[:trivial "sci" "(+ 1 2)" 3]
              [:define "sci" "(in-ns 'request.cost.measure) (defn tiny {:malli/schema [:=> [:cat :int] :int]} [x] (inc x)) (clojure.test/deftest tiny-test (clojure.test/is (= 2 (tiny 1))))" nil]
              [:redefine "sci" "(defn tiny {:malli/schema [:=> [:cat :int] :int]} [x] (+ 1 x))" nil]
              [:check "sci" "(select-keys (my.test/check {:seon.test/changed ['request.cost.measure/fresh-test]}) [:seon.test/executed-count :seon.test/passed?])" {:seon.test/executed-count 1 :seon.test/passed? true}]
              [:write "sci" "(my.program/write-branch! {:seon.fn/root \".\" :seon.program/identities #{}})" nil]
              [:source "sci" "(my.program/source 'seon.id/id)" nil]
              [:virtual "jvm" "(let [task (java.util.concurrent.FutureTask. ^java.util.concurrent.Callable (fn [] (alength (byte-array 8000000)))) thread (.start (Thread/ofVirtual) task)] (.join thread) (.get task))" 8000000]]]
       (when (= kind :check)
         (support/apply-config! connection (:seon.cluster/name (support/execution-handle nil)) {:seon.config.test/step? false})
         (answer running agent-id "sci" "(clojure.test/deftest fresh-test (clojure.test/is (= 3 (tiny 2))))"))
       (let [before (batch/jvm-counters)
             reply (answer running agent-id mode code)
             after (batch/jvm-counters)
             cost (support/mcp-request reply)
             window (:seon.cost/jvm-window cost)
             outer (batch/jvm-window before after)]
         (is (false? (get reply "isError")))
         (when expected (is (= expected (:seon.repl/value (support/mcp-reply reply)))))
         (is (= :includes-unrelated-work (:seon.cost/attribution window)))
         (is (contains? (:seon.cost/stages cost) :unattributed))
         (is (< (Math/abs (double (- (:seon.cost/ms cost) (reduce + (vals (:seon.cost/stages cost)))))) 0.000001))
         ;; Outer JSON encoding and decoding are outside the request's window.
         (is (<= (:seon.cost/alloc-mb window) (:seon.cost/alloc-mb outer)))
         (is (<= (:seon.cost/cpu-ms window) (:seon.cost/cpu-ms outer)))
         (is (<= (- (:seon.cost/alloc-mb outer) (:seon.cost/alloc-mb window))
                 (max 8.0 (* 0.2 (:seon.cost/alloc-mb outer)))))
         (when (= mode "jvm") (is (<= 8.0 (:seon.cost/alloc-mb window)))))))))

(deftest unavailable-counters-are-unknown-and-overlapping-stages-are-visible
  (let [window (batch/jvm-window [-1 -1] [-1 -1])
        cost (batch/request-cost (System/nanoTime) {:overlapping 1000000.0} nil)]
    (is (not (contains? window :seon.cost/alloc-mb)))
    (is (not (contains? window :seon.cost/cpu-ms)))
    (is (= :counter-unavailable (get-in window [:seon.cost/unmeasured :seon.cost/alloc-mb])))
    (is (neg? (get-in cost [:seon.cost/stages :unattributed])))))

(deftest a-request-counts-its-own-calls-reads-and-transactions
  (support/with-running-agent
   (fn [running agent-id connection]
     (let [_warm (answer running agent-id "sci" ":ping")
           ping (support/mcp-request (answer running agent-id "sci" ":ping"))
           pulls (support/mcp-request
                  (answer running agent-id "sci"
                          "(seon.db/pull '[:db/ident] [:db/ident :db/ident]) (seon.db/pull '[:db/ident] [:db/ident :db/ident]) (seon.db/pull '[:db/ident] [:db/ident :db/ident])"))
           ;; another thread reads the same database throughout the next request
           stop (java.util.concurrent.atomic.AtomicBoolean.)
           made (java.util.concurrent.atomic.AtomicLong.)
           noise (.start (Thread/ofPlatform)
                         ^Runnable (fn [] (while (not (.get stop))
                                            (db/pull (db/db connection) '[:db/ident] [:db/ident :db/ident])
                                            (.incrementAndGet made))))
           noisy (support/mcp-request (answer running agent-id "sci" ":ping"))
           _ (.set stop true)
           _ (.join ^Thread noise (java.time.Duration/ofSeconds 1))]
       (is (pos? (:seon.cost/calls ping)))
       (is (pos? (:seon.cost/transactions ping)) "the request's settlement commits")
       (is (<= 3 (- (:seon.cost/reads pulls) (:seon.cost/reads ping))))
       (is (pos? (.get made)))
       (is (= (:seon.cost/reads ping) (:seon.cost/reads noisy))
           "another thread's reads are not this request's")))))

(defn- window-share
  "The least share of the outer JVM allocation window a redefinition reply attributes: the entrance, step, tests, writer and commit (card FEEDBACK-LOOP stages 10-12)."
  {:malli/schema [:=> [:cat] :double]}
  []
  0.9)

(deftest a-redefinitions-reply-accounts-for-the-window
  (support/with-running-agent
   (fn [running agent-id _]
     (answer running agent-id "sci" "(in-ns 'request.cost.window) (defn tiny {:malli/schema [:=> [:cat :int] :int]} [x] (inc x)) (clojure.test/deftest tiny-test (clojure.test/is (= 2 (tiny 1))))")
     (let [before (batch/jvm-counters)
           reply (answer running agent-id "sci" "(defn tiny {:malli/schema [:=> [:cat :int] :int]} [x] (+ 1 x))")
           after (batch/jvm-counters)
           cost (support/mcp-request reply)
           outer (batch/jvm-window before after)
           attributed (transduce (map #(get-in cost [% :seon.cost/alloc-mb] 0.0)) + 0.0
                                 [:seon.cost/entrance :seon.cost/step :seon.cost/writer :seon.cost/commit :seon.cost/tests])
           shared (:seon.cost/shared cost)]
       (is (<= (* (window-share) (:seon.cost/alloc-mb outer)) attributed)
           "the entrance, step, writer, commit and tests together cover the share of the outer window")
       (is (= :shared-never-apportioned (:seon.cost/attribution shared)))
       (is (every? #(contains? shared %) [:seon.cost/alloc-mb :seon.cost/cpu-ms :seon.cost/gc-ms :seon.cost/jit-ms])
           "the residual, GC CPU and JIT time are labelled beside the parts")))))

(deftest a-repeated-form-writes-no-cost-entities-and-no-extra-transactions
  (support/with-running-agent
   (fn [running agent-id _]
     ;; the repeated work (200 queries) spends more self time than :seon.config.fn.cost/min-total-us
     (let [form "(do (dotimes [_ 200] (seon.db/q '[:find ?e . :where [?e :db/ident :db/ident]] (seon.db/db))) (let [rows (seon.db/q '[:find ?e :where [?e :seon.fn.cost/key]] (seon.db/db))] (if (set? rows) (count rows) 0)))"
           runs (vec (repeatedly 5 #(answer running agent-id "sci" form)))
           seen (mapv #(:seon.repl/value (support/mcp-reply %)) runs)
           [fourth fifth] (map support/mcp-request (take-last 2 runs))]
       (is (<= 1 (seen 1) 12) "the first request wrote cost entities, at most definitions defined + tests run + top")
       (is (= (seen 3) (seen 4)) "the same work again writes none")
       (is (= (:seon.cost/transactions fourth) (:seon.cost/transactions fifth)) "accounting adds no transaction")
       (is (<= (:seon.cost/calls fifth) (* 1.1 (:seon.cost/calls fourth))) "accounting adds no armed calls")))))

(deftest a-reply-shows-its-live-cost-against-main-at-once
  (support/with-running-agent
   (fn [running agent-id _]
     (answer running agent-id "sci" "(in-ns 'request.cost.live) (defn tiny {:malli/schema [:=> [:cat :int] :int]} [x] (inc x)) (clojure.test/deftest tiny-test (clojure.test/is (= 2 (tiny 1))))")
     (let [cost (support/mcp-request
                 (answer running agent-id "sci" "(defn tiny {:malli/schema [:=> [:cat :int] :int]} [x] (reduce + (range 20000)) (inc x)) (clojure.test/deftest tiny-test (clojure.test/is (= 2 (tiny 1))) (clojure.test/is (= 2000000 (count (vec (range 2000000))))))"))
           entry (first (filter #(= 'request.cost.live/tiny (:seon.cost.comparison/subject %)) (:seon.cost/diff cost)))]
       (is (some? entry) "the very same reply compares the redefined function")
       (is (#{:self-us :cold-self-us} (:seon.cost.comparison/measure entry)) "a warm or a cold sample, never the two averaged")
       (is (pos? (:seon.cost.comparison/live entry 0.0)) "the live side is the request's own measurement")
       (is (not (contains? entry :seon.cost.comparison/main)) "main stores nothing for a function it has never run")
       (is (= #{:new :interpreted} (:seon.cost.comparison/flags entry)))
       (let [test-entry (first (filter #(= 'request.cost.live/tiny-test (:seon.cost.comparison/subject %)) (:seon.cost/diff cost)))]
         (is (some? test-entry) "the very same reply shows the test its step ran on a changed reach")
         (is (= :alloc-bytes (:seon.cost.comparison/measure test-entry)))
         (is (pos? (:seon.cost.comparison/live test-entry 0.0)) "the live side is the member the step just ran")
         (is (not (contains? test-entry :seon.cost.comparison/ratio)) "one run of another reach is no ratio"))
       (is (pos? (get-in cost [:seon.cost/tests :seon.cost/alloc-mb] 0.0)) "the step's members sum")
       (is (= 1 (get-in cost [:seon.cost/tests :seon.cost/count])))))))

(deftest a-function-defined-and-called-in-one-request-is-compared-and-stored
  (support/with-running-agent
   (fn [running agent-id _]
     (let [cost (support/mcp-request
                 (answer running agent-id "sci"
                         "(in-ns 'request.cost.own) (defn summed \"Sum.\" {:malli/schema [:=> [:cat :int] :int]} [n] (reduce + (map identity (vec (range n))))) (dotimes [_ 30] (summed 5000))"))
           entry (first (filter #(= 'request.cost.own/summed (:seon.cost.comparison/subject %)) (:seon.cost/diff cost)))
           stored (:seon.repl/value (support/mcp-reply
                                     (answer running agent-id "sci"
                                             "(seon.db/pull '[:seon.fn.cost/warm-calls] [:seon.fn.cost/key ['request.cost.own/summed :seon.profile/context]])")))]
       (is (= #{:new :interpreted} (:seon.cost.comparison/flags entry))
           "the very same reply compares the function it defined and called")
       (is (<= 29 (:seon.fn.cost/warm-calls stored 0))
           "its interpreted cost entity is stored on the branch, the first call apart as cold")))))

(def ^:private digest-a (apply str (repeat 64 "a")))
(def ^:private digest-b (apply str (repeat 64 "b")))

(defn- cost
  "A cost entity of `sym` in `scope` holding the given samples
  (`[digest calls self-us]` each)."
  [sym scope & {:keys [warm cold]}]
  (cond-> {:seon.fn.cost/key [sym scope]}
    warm (batch/with-sample :warm (conj warm (peek warm)))
    cold (batch/with-sample :cold (conj cold (peek cold)))))

(defn- shown
  "The comparisons `cost-diff` gives `live` against `stored` entities, without their subject."
  [live stored]
  (mapv #(dissoc % :seon.cost.comparison/subject)
        (batch/cost-diff [live] (into {} (map (juxt :seon.fn.cost/key identity)) stored) 20)))

(deftest an-unchanged-platform-function-shows-no-entry
  (let [sym 'seon.schema/projection-cache-value
        host #(cost sym :seon.profile/host :warm [%1 30 %2])
        flags (fn [live stored] (mapv :seon.cost.comparison/flags (shown live stored)))]
    (are [live stored expected] (= expected (flags live stored))
      ;; main's value, the definition unchanged: an improvement or regression the agent did not cause
      (host digest-a 5.0) [(host digest-a 46.0)] []
      (host digest-a 2576.0) [(host digest-a 46.0)] []
      ;; a definition the agent changed
      (host digest-a 5.0) [(host digest-b 46.0)] [#{:changed}]
      (cost sym :seon.profile/context :warm [digest-a 30 5.0]) [] [#{:new :interpreted}]
      ;; a compiled definition main has no value for is left out
      (host digest-a 5.0) [] [])))

(deftest ^{:seon/tasks #{'seon.profile/cost-values-are-reliable-and-like-for-like}}
  an-interpreted-live-value-is-never-given-a-ratio-against-a-compiled-main-value
  (let [sym 'request.cost.scope/f]
    (is (= [{:seon.cost.comparison/measure :self-us :seon.cost.comparison/live 300.0
             :seon.cost.comparison/flags #{:new :interpreted}}]
           (shown (cost sym :seon.profile/context :warm [digest-b 30 300.0])
                  [(cost sym :seon.profile/host :warm [digest-a 100 1.0] :cold [digest-a 1 40.0])]))
        "main's compiled samples are another scope: no main value, no ratio")
    (is (= [{:seon.cost.comparison/measure :self-us :seon.cost.comparison/live 300.0
             :seon.cost.comparison/main 100.0 :seon.cost.comparison/unknown :few-calls
             :seon.cost.comparison/flags #{:changed :interpreted}}]
           (shown (cost sym :seon.profile/context :warm [digest-b 3 300.0])
                  [(cost sym :seon.profile/context :warm [digest-a 100 100.0])]))
        "a warm sample of three calls is too few for a ratio: the unknown says why")))

(deftest ^{:seon/tasks #{'seon.profile/cost-values-are-reliable-and-like-for-like}}
  a-warm-like-for-like-regression-of-a-changed-definition-still-shows
  (let [sym 'seon.schema/projection-cache-value]
    (is (= [{:seon.cost.comparison/measure :self-us :seon.cost.comparison/live 200.0
             :seon.cost.comparison/main 50.0 :seon.cost.comparison/ratio 4.0
             :seon.cost.comparison/flags #{:changed}}]
           (shown (cost sym :seon.profile/host :warm [digest-b 30 200.0])
                  [(cost sym :seon.profile/host :warm [digest-a 100 50.0] :cold [digest-a 1 5000.0])])))))

(deftest ^{:seon/tasks #{'seon.profile/cost-values-are-reliable-and-like-for-like}}
  after-a-restart-the-first-call-compares-against-the-stored-cold-value
  ;; root's interpreted definition on main, called once after a restart: its
  ;; one call is cold, compared with main's cold sample, never its warm one
  (let [sym 'request.cost.restart/f]
    (is (= [{:seon.cost.comparison/measure :cold-self-us :seon.cost.comparison/live 520.0
             :seon.cost.comparison/main 500.0 :seon.cost.comparison/ratio 1.04
             :seon.cost.comparison/flags #{:interpreted}}]
           (shown (cost sym :seon.profile/context :cold [digest-a 1 520.0])
                  [(cost sym :seon.profile/context :warm [digest-a 100 5.0] :cold [digest-a 1 500.0])])))))

(deftest ^{:seon/tasks #{'seon.profile/cost-values-are-reliable-and-like-for-like}}
  a-test-reaching-no-change-is-no-entry-and-a-member-gets-no-ratio
  (let [live (fn [reach] [(cond-> {:seon.test/sym 'request.cost.t/a-test :seon.test.member/alloc-bytes 4000}
                            reach (assoc :seon.test.member/reach-digest reach))])
        main {'request.cost.t/a-test {:seon.test.member/alloc-bytes 1000 :seon.test.member/reach-digest digest-a}}
        bare (fn [entries] (mapv #(dissoc % :seon.cost.comparison/subject :seon.cost.comparison/measure) entries))]
    (are [entries expected] (= expected (bare entries))
      ;; the reach main tested: unchanged, whatever its allocation
      (batch/test-cost-diff (live digest-a) main) []
      ;; a reach main never tested: shown, but one run is no ratio
      (batch/test-cost-diff (live digest-b) main)
      [{:seon.cost.comparison/live 4000.0 :seon.cost.comparison/main 1000.0
        :seon.cost.comparison/unknown :single-run :seon.cost.comparison/flags #{:changed}}]
      (batch/test-cost-diff (live digest-b) {})
      [{:seon.cost.comparison/live 4000.0 :seon.cost.comparison/flags #{:new}}])))

(deftest the-reply-is-offered-after-accounting
  (support/with-running-agent
   (fn [running agent-id _]
     (let [step (:seon.cost/step (support/mcp-request (answer running agent-id "sci" "(+ 1 2)")))]
       (is (pos? (:seon.cost/alloc-mb step 0.0)) "the reply carries the step's cost, read before it was offered")
       (is (contains? step :seon.cost/cpu-ms))))))
