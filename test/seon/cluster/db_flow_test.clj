(ns seon.cluster.db-flow-test
  "db<->flow's decision, `seon.cluster.agent/plan`, as a table: facts read on
  main and the roster's live graphs in, the actions db<->flow applies out."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.cluster.agent :as agent]
            [seon.id :as id]))

(defn- facts
  "Plan facts over `overrides`: nothing startable, nothing open, a running cluster."
  {:malli/schema [:=> [:cat :map] :seon.flow.plan/facts]}
  [overrides]
  (merge {:seon.flow.plan/startable #{} :seon.flow.plan/invalid #{} :seon.flow.plan/open []
          :seon.flow.plan/ended {} :seon.flow.plan/created {} :seon.flow.plan/basis {}
          :seon.flow.plan/cluster :running}
         overrides))

(defn- roster
  "A roster holding `started` graphs, `paused` among them."
  {:malli/schema [:function [:=> [:cat] :seon.flow.plan/roster]
                  [:=> [:cat :seon.flow.plan/started :seon.flow.plan/paused] :seon.flow.plan/roster]]}
  ([] (roster #{} #{}))
  ([started paused] {:seon.flow.plan/started started :seon.flow.plan/paused paused}))

(def ^:private a "aaaaaaaaaaaa")
(def ^:private b "bbbbbbbbbbbb")
(def ^:private key-a (id/id [a 1]))
(def ^:private agent-a (str "attempt/" key-a))

(defn- open
  "The open attempt 1 of `a` under `intent`."
  {:malli/schema [:=> [:cat :seon.task/execution] :seon.flow.plan/open-attempt]}
  [intent]
  {:seon.task/assigned a :seon.task/assignment key-a :seon.agent/id agent-a
   :seon.task/branch (keyword (str "attempt-" key-a)) :seon.task/execution intent})

(defn- ops
  "The plan's actions as [op task] pairs, in order."
  {:malli/schema [:=> [:cat :seon.flow.plan/actions] [:vector [:tuple :seon.flow.plan/op :seon.task/assigned]]]}
  [actions]
  (mapv (juxt :seon.flow.plan/op :seon.task/assigned) actions))

(deftest ^{:seon/tasks #{'seon.cluster.agent/two-plan-passes-start-one-attempt}}
  plan-writes-the-attempt-first-and-makes-flow-follow
  (testing "a fresh leaf: its attempt, then its flow, in one pass"
    (let [[attempt start :as actions] (agent/plan (facts {:seon.flow.plan/startable #{a}}) (roster) 4)]
      (is (= [[:attempt a] [:start-flow a]] (ops actions)))
      (is (= {:seon.task/assignment key-a :seon.task.attempt/number 1 :seon.agent/id agent-a
              :seon.task/branch (keyword (str "attempt-" key-a)) :seon.flow.plan/fork-point :main}
             (select-keys attempt [:seon.task/assignment :seon.task.attempt/number :seon.agent/id
                                   :seon.task/branch :seon.flow.plan/fork-point])))
      (is (= :running (:seon.task/execution start)))))
  (testing "two passes over the same facts plan the same attempt, so the second write is a no-op"
    (is (= (agent/plan (facts {:seon.flow.plan/startable #{a}}) (roster) 4)
           (agent/plan (facts {:seon.flow.plan/startable #{a}}) (roster) 4))))
  (testing "once written and started, nothing"
    (is (= [] (agent/plan (facts {:seon.flow.plan/startable #{a} :seon.flow.plan/open [(open :running)]})
                          (roster #{agent-a} #{}) 4))))
  (testing "a restart: the open attempt with no graph is started again, never attempted again"
    (is (= [[:start-flow a]]
           (ops (agent/plan (facts {:seon.flow.plan/startable #{a} :seon.flow.plan/open [(open :running)]})
                            (roster) 4)))))
  (testing "an attempt after ended ones is numbered after them"
    (is (= (id/id [a 3])
           (:seon.task/assignment (first (agent/plan (facts {:seon.flow.plan/startable #{a}
                                                             :seon.flow.plan/ended {a 2}})
                                                     (roster) 4))))))
  (testing "a successor forks from its predecessor's end commit"
    (let [commit #uuid "00000000-0000-0000-0000-00000000000a"]
      (is (= #{commit}
             (:seon.flow.plan/fork-point
              (first (agent/plan (facts {:seon.flow.plan/startable #{b} :seon.flow.plan/basis {b #{commit}}})
                                 (roster) 4)))))))
  (testing "max-running counts open attempts: ten ready under four start the four oldest"
    (let [handles (mapv #(format "%012d" %) (range 10))
          created (zipmap handles (range 100 0 -10))
          attempts (filter (comp #{:attempt} :seon.flow.plan/op)
                           (agent/plan (facts {:seon.flow.plan/startable (set handles) :seon.flow.plan/created created})
                                       (roster) 4))]
      (is (= (take 4 (reverse handles)) (map :seon.task/assigned attempts)))
      (is (= [] (filter (comp #{:attempt} :seon.flow.plan/op)
                        (agent/plan (facts {:seon.flow.plan/startable (set handles)
                                            :seon.flow.plan/open (mapv #(assoc (open :running) :seon.task/assigned %
                                                                               :seon.agent/id (str "attempt/" %))
                                                                       (take 4 handles))})
                                    (roster (set (map #(str "attempt/" %) (take 4 handles))) #{}) 4))))))
  (testing "an invalid running leaf is a problem, never an attempt"
    (is (= [[:problem b] [:attempt a] [:start-flow a]]
           (ops (agent/plan (facts {:seon.flow.plan/startable #{a} :seon.flow.plan/invalid #{b}}) (roster) 4))))))

(deftest
  each-intent-is-one-flow-action
  (doseq [[intent started paused cluster expected]
          [[:running #{} #{} :running [[:start-flow :running]]]
           [:paused #{} #{} :running [[:start-flow :paused]]]
           [:held #{agent-a} #{} :running [[:pause nil]]]
           [:paused #{agent-a} #{} :running [[:pause nil]]]
           [:paused #{agent-a} #{agent-a} :running []]
           [:running #{agent-a} #{agent-a} :running [[:resume nil]]]
           [:running #{agent-a} #{} :paused [[:pause nil]]]
           [:stopped #{agent-a} #{} :running [[:stop nil]]]
           [:stopped #{} #{} :running []]
           [:cancelled #{agent-a} #{} :running [[:cancel nil]]]]]
    (is (= expected (mapv (juxt :seon.flow.plan/op :seon.task/execution)
                          (agent/plan (facts {:seon.flow.plan/open [(open intent)] :seon.flow.plan/cluster cluster})
                                      (roster started paused) 4)))
        "each intent and roster state plans exactly its Flow verb")))
