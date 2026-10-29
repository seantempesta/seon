(ns seon.ctx-test
  "The agent's record as one map (seon.ctx): kept by a patch from each transaction's datoms, equal
  to a build from the db after every one, at the cost of the change; drawn on the page as a block,
  each key by its own schema's render. Run: bin/test seon.ctx-test"
  (:refer-clojure :exclude [defn defn-])
  (:require [clojure.test :refer [deftest is testing]] [seon.contract :refer [defn defn-]] [seon.cost :as cost]
            [seon.test-store :as ts] [datahike.api :as d] [seon.ctx :as ctx] [seon.db :as pdb] [seon.context :as context] [seon.task :as task] [seon.vector :as vector] [seon.render :as render] [seon.render.ui] [seon.repl] [seon.agent] [seon.web.html] [seon.root] [my.schema] [clojure.test.check :as tc] [clojure.test.check.generators :as gen] [clojure.test.check.properties :as prop] [seon.proofs :as proofs] [clojure.string :as str] [clojure.walk :as walk]))

(def ^:private k "The test agent's key." "test-boot/a1")

;; The pattern these tests of seon.ctx's machinery (patch = build, windows, sharing) run on: the run-shaped
;; record the agent's pull had until seon.agent/the-agent-is-the-unit-not-the-run, kept as the machinery's
;; input (runs with their task, turns and messages; windowed evals). The agent's own pattern is proved by
;; an-agents-record-holds-its-task-and-every-turn-and-eval.
(def ^:private run-pattern
  "The run-shaped agent pattern the machinery tests build their records by."
  '[* {[:seon.run/_agent :as :runs :limit :seon.config.ctx/window :reverse true] :seon.run/run-fact}
    {[:seon.eval/_agent :as :evals :limit :seon.config.ctx/window :reverse true] [*]}
    {[:seon.catch-up/_agent :as :catch-ups :limit :seon.config.ctx/window :reverse true] [*]}])

(defn- end-agent!
  "The agent state ended as a test leaves it: the walk of its render (seon.repl/render-ctx holds one
  in the state, reactions on @ctx that run at every write of it) disposed, then the state reset. A
  walk left behind makes every later write of the record in this process cost its reactions
  (seon.ctx-test/a-swap-costs-what-it-changed read 52 calls after this test, not 39)."
  {:malli/schema [:=> [:cat] :nil]}
  []
  (some-> (:walk @seon.agent/state) seon.render/dispose-walk!)
  (reset! seon.agent/state nil)
  nil)

(defn- aid "The test agent's entity on db." {:malli/schema [:=> [:cat :seon.host/db] [:maybe pos-int?]]}
  [db] (:db/id (d/entity db [:seon.agent/key k])))

(defn- id "An entity's id on db by a lookup ref: its key in a collection of ctx." {:malli/schema [:=> [:cat :seon.host/db :any] [:maybe pos-int?]]}
  [db lookup] (:db/id (d/entity db lookup)))

(defn- names "The members of collection m by attribute a (each its own name), in m's order." {:malli/schema [:=> [:cat [:maybe :map] :keyword] [:vector :any]]}
  [m a] (mapv a (vals m)))

(defn- response
  "Eval i's recorded response, shaped as a session's: a value with proofs and changes, output."
  {:malli/schema [:=> [:cat nat-int?] :map]}
  [i]
  {:handle (symbol "result" (str "e" i)) :ms 1.5 :ticks (* 1000 i) :bytes (* 64 i)
   :value {:task "lab/ctx" :proofs (vec (for [j (range 4)] {:test (symbol "app.x-test" (str "t" j)) :status (if (odd? (+ i j)) :pass :fail)}))
           :changes {:vars (vec (for [j (range 3)] (symbol "app.x" (str "f" j))))}}
   :out "a line of output from the form, as printed\n"})

(defn- eval-tx
  "Eval i of the agent, as the REPL records it."
  {:malli/schema [:=> [:cat nat-int?] [:vector :map]]}
  [i] [{:seon.eval/id (str "e" i) :seon.eval/agent [:seon.agent/key k] :seon.eval/by "a1"
        :seon.eval/record {:code (str "(app.x/f" (mod i 3) " " i ")") :response (response i)}}])

(defn- run-tx
  "Run r of the agent's task, with turns of it numbered from 0 below n."
  {:malli/schema [:=> [:cat :string nat-int?] [:vector :map]]}
  [r n]
  (into [{:seon.run/id r :seon.run/task [:seon.task/id "lab/ctx"] :seon.run/status :running :seon.run/agent [:seon.agent/key k]}]
        (for [t (range n)] {:seon.turn/id (str r "/" t) :seon.turn/run [:seon.run/id r] :seon.turn/n t
                            :seon.turn/reply "I will check f0 against its failing assertion, then edit it."})))

(defn- seeded
  "The test store's db with the agent's record of n evals (seeded n pattern: the agent's own pattern, the schema's
  when nil; one arity less, run-pattern): its task, citing app.x/f0 (defined, with
  one caller) and app.x/f1 (not written yet), one run with n/3 turns and n/20 messages, written in
  transactions of 100 evals as a session writes them."
  {:malli/schema [:function [:=> [:cat nat-int?] :seon.host/db] [:=> [:cat nat-int? [:maybe [:vector :any]]] :seon.host/db]]}
  ([n] (seeded n run-pattern))
  ([n pattern]
  (let [db (:db-after (d/with (ts/test-db) (into [(cond-> {:seon.agent/key k :seon.agent/id :a1 :seon.agent/task {:seon.task/id "lab/ctx"}}
                                                     pattern (assoc :seon.ctx/pattern pattern))
                                                   {:seon.task/id "lab/ctx" :seon.task/ns 'app.x :seon.task/name "ctx" :seon.task/plan "Make app.x/f0 pass."
                                                    :seon.task/kind :work :seon.task/proofs #{'app.x-test/t0}
                                                    :seon.task/references #{'app.x/f0 'app.x/f1} :seon.task/sections #{"docs/x.md#the-x"}
                                                    :seon.task/execution :running :seon.task/branch :main/lab-ctx}
                                                   {:db/id "f0" :form/id "app.x/f0-1" :form/src "(defn f0 [x] x)"} {:var/name 'app.x/f0 :var/form "f0"}
                                                   {:form/id "app.x/c-1" :form/src "(defn c [] (f0 1))" :form/refs #{'app.x/f0}}
                                                   {:seon.section/address "docs/x.md#the-x" :seon.section/heading "The x" :seon.section/text "What x does."}
                                                   {:db/id "t0" :form/id "app.x-test/t0-1" :form/src "(deftest t0 (is (= 1 (f0 1))))" :form/refs #{'app.x/f0 'clojure.test/is}}
                                                   {:var/name 'app.x-test/t0 :var/kind :test :var/form "t0"}]
                                                  (run-tx "lab/ctx#1" 0))))]
    (reduce (fn [db part]
              (:db-after (d/with db (vec (mapcat (fn [i] (cond-> (eval-tx i)
                                                            (zero? (mod i 3)) (conj {:seon.turn/id (str "lab/ctx#1/" i) :seon.turn/run [:seon.run/id "lab/ctx#1"] :seon.turn/agent [:seon.agent/key k] :seon.turn/n i})
                                                            (zero? (mod i 20)) (conj {:seon.message/id (str "m" i) :seon.message/from "user"
                                                                                      :seon.message/to [:seon.run/id "lab/ctx#1"] :seon.message/agent [:seon.agent/key k] :seon.message/body "How is it going?"})))
                                                 part)))))
            db (partition-all 100 (range 1 (inc n)))))))

(defn- order-of
  "Every collection's keys in t, in the order the map holds them, by path: what = on maps ignores."
  {:malli/schema [:=> [:cat :any] :any]}
  [t]
  (let [node? #(and (map? %) (contains? % :db/id)) coll? #(and (map? %) (seq %) (every? node? (vals %)))]
    (into {} (for [[p x] (tree-seq (comp map? second) (fn [[p m]] (for [[k v] m :when (map? v)] [(conj p k) v])) [[] t])
                   :when (coll? x)] [p (vec (keys x))]))))

(defn- change
  "The i-th change of a session, chosen by r from what one does: an eval, a new run with turns, a
  turn or message on a run, a run's status, the task's plan and proofs, an eval gone, the agent's
  own attribute, a turn moved to another run, a run gone, a run's messages all gone (a unit
  emptied)."
  {:malli/schema [:=> [:cat :seon.host/db nat-int? :any] [:vector :any]]}
  [db i ^java.util.Random r]
  (let [runs (vec (d/q '[:find [?id ...] :where [_ :seon.run/id ?id]] db))
        evals (vec (sort (d/q '[:find [?id ...] :where [_ :seon.eval/id ?id]] db)))
        run (nth runs (.nextInt r (count runs)))
        turns (vec (sort (d/q '[:find [?id ...] :where [_ :seon.turn/id ?id]] db)))]
    (case (.nextInt r 23)
      0 (eval-tx (+ 10000 i))
      1 (run-tx (str "lab/ctx#" (+ 2 i)) 2)
      2 [{:seon.turn/id (str run "/x" i) :seon.turn/run [:seon.run/id run] :seon.turn/n i}]
      3 [{:seon.message/id (str "mx" i) :seon.message/from "user" :seon.message/to [:seon.run/id run] :seon.message/body (str "note " i)}]
      4 [[:db/add [:seon.run/id run] :seon.run/status (nth [:running :done :closed] (.nextInt r 3))]]
      5 [[:db/add [:seon.task/id "lab/ctx"] :seon.task/plan (str "plan " i)]]
      6 [[(if (.nextBoolean r) :db/add :db/retract) [:seon.task/id "lab/ctx"] :seon.task/proofs (symbol "app.x-test" (str "t" (.nextInt r 3)))]]
      7 (if (seq evals) [[:db/retractEntity [:seon.eval/id (nth evals (.nextInt r (count evals)))]]] (eval-tx (+ 20000 i)))
      8 [[:db/add [:seon.agent/key k] :seon.agent/status (nth [:idle :busy] (.nextInt r 2))]]
      9 (if (seq turns) [[:db/add [:seon.turn/id (nth turns (.nextInt r (count turns)))] :seon.turn/run [:seon.run/id run]]] (eval-tx (+ 30000 i)))
      10 (if (< 1 (count runs)) [[:db/retractEntity [:seon.run/id run]]] (eval-tx (+ 40000 i)))
      12 [{:seon.task/id (str "lab/other" i) :seon.task/ns 'app.y :seon.task/name (str "other" i) :seon.task/plan "Another."
           :seon.task/kind :work :seon.task/execution :held :seon.task/branch (keyword "main" (str "lab-other" i))}
          [:db/add [:seon.run/id run] :seon.run/task [:seon.task/id (str "lab/other" i)]]]
      ;; what a run's derived members read: the plan's vector written, a program form written
      13 (vector/tx-data [(:db/id (d/entity db [:seon.task/id "lab/ctx"]))] [i] [(mapv #(Math/sin (* (inc i) (inc %) 0.37)) (range 768))] [10] "m")
      14 [{:db/id "f" :form/id (str "app.x/g" i) :form/src (str "(defn g" i " [])")} {:var/name (symbol "app.x" (str "g" i)) :var/form "f"}]
      ;; what a task's lookups read: a cited var redefined, a new caller, the var not written yet
      ;; defined (or gone again), a reference added or dropped
      15 [{:db/id "f" :form/id (str "app.x/f0-" (+ 2 i)) :form/src (str "(defn f0 [x] " i ")")} [:db/add [:var/name 'app.x/f0] :var/form "f"]]
      16 [{:form/id (str "app.x/c" i) :form/src (str "(defn c" i " [] (f0 " i "))") :form/refs #{'app.x/f0}}]
      17 (if (d/entity db [:var/name 'app.x/f1])
           [[:db/retractEntity [:var/name 'app.x/f1]]]
           [{:db/id "f" :form/id (str "app.x/f1-" i) :form/src "(defn f1 [])"} {:var/name 'app.x/f1 :var/form "f"}])
      18 [[(if (.nextBoolean r) :db/add :db/retract) [:seon.task/id "lab/ctx"] :seon.task/references (symbol "app.x" (str "f" (.nextInt r 3)))]]
      ;; a cited section's text edited, or the section another task cites written
      22 (if (.nextBoolean r)
           [[:db/add [:seon.section/address "docs/x.md#the-x"] :seon.section/text (str "What x does, " i ".")]]
           [{:seon.section/address (str "docs/y.md#y" i) :seon.section/text "Y."} [:db/add [:seon.task/id "lab/ctx"] :seon.task/sections (str "docs/y.md#y" i)]])
      ;; the agent edits its pattern: a narrower window of its evals, or its schema's again
      19 [[:db/add [:seon.agent/key k] :seon.ctx/pattern
           (if (.nextBoolean r)
             '[* {[:seon.eval/_agent :as :evals :limit 5 :reverse true] [*]} {[:seon.run/_agent :as :runs :limit 5 :reverse true] :seon.run/run-fact}]
             run-pattern)]]
      ;; a window's moves in one transaction: several evals join while one it holds leaves; a member's
      ;; ref retracted and asserted again (it leaves and comes back)
      20 (into (vec (mapcat #(eval-tx (+ 70000 (* 10 i) %)) (range 3)))
               (when (seq evals) [[:db/retractEntity [:seon.eval/id (peek evals)]]]))
      21 (if (seq evals)
           (let [e (nth evals (.nextInt r (count evals)))]
             [[:db/retract [:seon.eval/id e] :seon.eval/agent [:seon.agent/key k]] [:db/add [:seon.eval/id e] :seon.eval/agent [:seon.agent/key k]]])
           (eval-tx (+ 80000 i)))
      11 (or (not-empty (vec (for [m (d/q '[:find [?m ...] :in $ ?r :where [?re :seon.run/id ?r] [?m :seon.message/to ?re]] db run)] [:db/retractEntity m])))
             (eval-tx (+ 50000 i))))))

(deftest an-agents-record-holds-its-task-and-every-turn-and-eval
  ;; the owner, 2026-10-29: "get rid of runs ... by default show everything: get rid of the limits for evals and
  ;; turns; messages at the newest 100 is fine" (seon.agent/the-agent-is-the-unit-not-the-run, cut 1): the agent's
  ;; schema pattern reaches its task directly, holds its turns and evals with no window, and has no runs or catch-ups
  (let [pattern (:seon.ctx/pattern (seon.contract/properties :seon.db/agent-fact))
        joins (into {} (keep #(when (map? %) (first %))) pattern)
        db (:db-after (d/with (ts/test-db)
                              (into [{:seon.agent/key k :seon.agent/id :a1 :seon.agent/task {:seon.task/id "lab/ctx"}}
                                     {:seon.task/id "lab/ctx" :seon.task/ns 'app.x :seon.task/name "ctx" :seon.task/plan "Make app.x/f0 pass."
                                      :seon.task/kind :work :seon.task/proofs #{} :seon.task/execution :running :seon.task/branch :main/lab-ctx}]
                                    (concat (mapcat eval-tx (range 1 6))
                                            (for [t (range 3)] {:seon.turn/id (str "lab/ctx#1/" t) :seon.turn/agent [:seon.agent/key k] :seon.turn/n t})
                                            [{:seon.message/id "m1" :seon.message/from "user" :seon.message/body "How is it going?"
                                              :seon.message/agent [:seon.agent/key k]}]))))
        t (:tree (ctx/build db (aid db) :b))]
    (is (= "lab/ctx" (get-in t [:task :seon.task/id])) "the task, pulled whole by the agent's own ref")
    (is (= "Make app.x/f0 pass." (get-in t [:task :seon.task/plan])))
    (is (= 3 (count (:turns t))) "every turn")
    (is (= 5 (count (:evals t))) "every eval")
    (is (= ["m1"] (names (:messages t) :seon.message/id)) "the messages it took")
    (is (= [:seon.message/_agent] (for [[j _] joins :when (some #{:limit} j)] (first j))) "no window on the turns and evals: the messages alone are windowed")
    (is (and (not (contains? t :runs)) (not (contains? t :catch-ups))) "no runs, no catch-ups")))

(deftest an-agents-record-holds-only-its-work
  ;; the owner, 2026-10-29: "The agent's record is facts about its work. Telemetry and derived data live on their
  ;; own entities, pointing at what they describe ... a model call at its turn" (seon.ctx/the-record-is-facts-about-the-work,
  ;; move 1): a turn's request bookkeeping is its model call's entity, which the agent's pull never reaches;
  ;; move 2: an agent's process (pid, memory) is its process entity, pointing at the agent.
  ;; move 3: an embedding is its own vector entity pointing at what it embeds (docs/vector.md#a-vector-is-its-own-entity).
  (let [db (:db-after (d/with (ts/test-db)
                              [{:seon.agent/key k :seon.agent/id :a1 :seon.agent/task {:seon.task/id "lab/ctx"}}
                               {:seon.task/id "lab/ctx" :seon.task/ns 'app.x :seon.task/name "ctx" :seon.task/plan "Make app.x/f0 pass."
                                :seon.task/kind :work :seon.task/proofs #{} :seon.task/execution :running :seon.task/branch :main/lab-ctx}
                               {:seon.turn/id "lab/ctx#1/0" :seon.turn/agent [:seon.agent/key k] :seon.turn/n 0 :seon.turn/reply "I will check f0."}
                               {:seon.model-call/id "lab/ctx#1/0" :seon.model-call/turn [:seon.turn/id "lab/ctx#1/0"]
                                :seon.model-call/tokens 1200 :seon.model-call/cache-hit-tokens 1000 :seon.model-call/cache-miss-tokens 100
                                :seon.model-call/ms 7 :seon.model-call/model "scripted" :seon.model-call/forms 1 :seon.model-call/errors 0 :seon.model-call/red 0
                                :seon.model-call/split {:context-ms 2 :model-ms 5} :seon.model-call/sent [[:eval "e1"]]}
                               {:seon.process/id (str k "/77") :seon.process/agent [:seon.agent/key k] :seon.process/pid 77 :seon.process/mem-mb 120.5
                                :seon.process/forked-ms 5.0}
                               {:seon.eval/id "e1" :seon.eval/agent [:seon.agent/key k] :seon.eval/by "a1"}
                               {:seon.vector/of [:seon.eval/id "e1"] :seon.vector/model "m" :seon.vector/tokens 3}]))
        t (:tree (ctx/build db (aid db) :b))
        keys-in (fn keys-in [x] (cond (map? x) (concat (keys x) (mapcat keys-in (vals x))) (coll? x) (mapcat keys-in x)))]
    (is (= ["I will check f0."] (map :seon.turn/reply (vals (:turns t)))) "the turn is in the record, with its reply")
    (is (empty? (filter #(and (keyword? %) (= "seon.model-call" (namespace %))) (keys-in t))) "no :seon.model-call/* key anywhere in the record")
    (is (empty? (filter #(and (keyword? %) (or (= "seon.process" (namespace %)) (#{:seon.agent/pid :seon.agent/mem-mb} %))) (keys-in t)))
        "no :seon.process/* key, no pid or memory on the agent, anywhere in the record")
    (is (= #{[77 120.5]} (d/q '[:find ?p ?m :where [?x :seon.process/agent ?a] [?a :seon.agent/key] [?x :seon.process/pid ?p] [?x :seon.process/mem-mb ?m]] db))
        "the process is reached by a query on its agent")
    (is (contains? (set (map :seon.eval/id (vals (:evals t)))) "e1") "the eval is in the record")
    (is (empty? (filter #(and (keyword? %) (= "seon.vector" (namespace %))) (keys-in t))) "no :seon.vector/* in the record")
    (is (= [1200] (d/q '[:find [?n ...] :where [?m :seon.model-call/turn ?t] [?t :seon.turn/id "lab/ctx#1/0"] [?m :seon.model-call/tokens ?n]] db))
        "the model call is reached by a query on its turn")))

(deftest ctx-is-the-record-after-every-transaction
  ;; the patch holds what a build holds, its map and its index, after each of 150 changes of a
  ;; session's kinds (a seeded random choice, so a failure repeats): the map is never a second
  ;; record of its own
  (let [r (java.util.Random. 25)]
    (testing "the record holds the session: its evals keyed by id, its runs with their task, turns and messages"
      (let [db (seeded 60) t (:tree (ctx/build db (aid db) :b))]
        (is (= 60 (count (:evals t))))
        (let [e7 (id db [:seon.eval/id "e7"]) r1 (id db [:seon.run/id "lab/ctx#1"])]
          (is (= {:seon.eval/id "e7" :by "a1"} {:seon.eval/id (get-in t [:evals e7 :seon.eval/id]) :by (get-in t [:evals e7 :seon.eval/by])}))
          (is (= "lab/ctx" (get-in t [:runs r1 :task :seon.task/id])))
          (is (= #{'app.x-test/t0} (get-in t [:runs r1 :task :seon.task/proofs])))
          (is (= 20 (count (get-in t [:runs r1 :turns]))))
          (is (= #{"m20" "m40" "m60"} (set (names (get-in t [:runs r1 :messages]) :seon.message/id)))))))
    (loop [db (seeded 60) c (ctx/build db (aid db) :b) i 0 bad [] unshared [] checked 0]
      (if (= i 150)
        (do (is (empty? bad) (str "the change after which the patch and a build differ: " (first bad)))
            (is (< 1000 checked) (str "the sharing check reads the paths no change touched: " checked))
            (is (empty? unshared) (str "a path no change touched that is a new object: " (first unshared))))
        (let [tx (change db i r) rep (d/with db tx) db' (:db-after rep) c' (ctx/patch c db' (:tx-data rep) :b)
              b (ctx/build db' (aid db') :b)
              ;; every member path held before and after that is not at, under or above a changed path
              related? (fn [p q] (let [n (min (count p) (count q))] (= (subvec p 0 n) (subvec q 0 n))))
              moved (map second (ctx/changes c c'))
              kept (for [p (vals (:held (:ix c))) :when (and (seq p) (not-any? #(related? p %) moved)
                                                           (some? (get-in (:tree c') p)))] p)]
          (recur db' c' (inc i)
                 (cond-> bad (or (not= c' b) (not= (order-of (:tree c')) (order-of (:tree b)))) (conj [i tx]))
                 (into unshared (for [p kept :when (not (identical? (get-in (:tree c) p) (get-in (:tree c') p)))] [i p]))
                 (+ checked (count kept))))))
    (testing "a build and its rebuild are the same data, and the same order: every collection newest first"
      (let [db (seeded 60) t (:tree (ctx/build db (aid db) :b))]
        (is (= (map #(str "e" %) (range 60 0 -1)) (names (:evals t) :seon.eval/id)) "evals, newest first: their ids, issued in time order")
        (let [ms (get-in t [:runs (id db [:seon.run/id "lab/ctx#1"]) :messages])]
          (is (= (sort > (keys ms)) (keys ms)) "messages by id, as every collection (their ids are their content's)"))))))

(deftest any-entitys-record-follows-mains-commits
  ;; root's, the user's and a task's record are built over their entity and patched from the
  ;; writer's commits of main ({:t :ops :db}, explicit-id ops): equal to a build after each
  (let [r (java.util.Random. 7)
        db0 (seeded 20)
        te (:db/id (d/entity db0 [:seon.task/id "lab/ctx"]))
        re (:db/id (d/entity db0 [:seon.run/id "lab/ctx#1"]))]
    (is (= "lab/ctx" (:seon.task/id (:tree (ctx/build db0 te :main)))) "a task's record")
    (is (some #{"m20"} (names (:messages (:tree (ctx/build db0 re :main))) :seon.message/id)) "a run's, with its joins")
    (loop [db db0 cs {te (ctx/build db0 te :main) re (ctx/build db0 re :main)} i 0 bad []]
      (if (= i 60)
        (is (empty? bad) (str "the commit after which a followed record and a build differ: " (first bad)))
        (let [tx (change db i r) rep (d/with db tx) db' (:db-after rep)
              commit {:t (:max-tx db') :ops (pdb/report->tx rep) :db db'}
              cs' (into {} (for [[e c] cs] [e (ctx/follow c commit :main)]))]
          (recur db' cs' (inc i)
                 (into bad (for [[e c] cs' :let [b (ctx/build db' (when (seq (d/datoms db' :eavt e)) e) :main)]
                                 :when (not= c b)] [i e tx]))))))
    (testing "a followed run retracted: its environment alone, as a build of no entity"
      (let [rep (d/with db0 [[:db/retractEntity re]]) db' (:db-after rep)
            c (ctx/follow (ctx/build db0 re :main) {:t (:max-tx db') :ops (pdb/report->tx rep) :db db'} :main)]
        (is (= (ctx/build db' nil :main) c))))))

(deftest the-agents-state-keeps-ctx
  ;; the atom follows the agent's db: built when the state first holds it, patched from the
  ;; transaction transact! records, and from what two values differ by when no transaction moved it
  (let [db (seeded 10) rep (d/with db (eval-tx 11))]
    (try
      (reset! seon.agent/state {:db db :key k :branch :b})
      (is (= (:tree (ctx/build db (aid db) :b)) @ctx/ctx) "built when the state first holds the db")
      (is (= {:seon.render/schema :seon.ctx/ctx :seon.render/basis {:t (:max-tx db) :branch :b}} (meta @ctx/ctx))
          "marked for its block, with the basis an eval of it records")
      (swap! seon.agent/state assoc :db (:db-after rep) :tx (select-keys rep [:db-after :tx-data]))
      (is (contains? (:evals @ctx/ctx) (id (:db-after rep) [:seon.eval/id "e11"])) "a transaction's eval joins")
      (is (= (:tree (ctx/build (:db-after rep) (aid (:db-after rep)) :b)) @ctx/ctx))
      (swap! seon.agent/state assoc :db db)
      (is (= (:tree (ctx/build db (aid db) :b)) @ctx/ctx) "a move with no transaction is patched by the difference")
      (finally (end-agent!)))))

(deftest the-record-holds-what-the-agent-heard-of-main
  ;; main's head and the branch's merge base as the agent last heard them are facts its turn writes
  ;; (:seon.agent/main-commit, :seon.agent/base-commit), never process state merged into the record:
  ;; a cold build holds them as the live record does (the branch views read them)
  (let [db (seeded 10) m (ts/test-db)]
    (try
      (reset! seon.agent/state {:db db :key k :branch :b})
      (let [c0 @ctx/ctx]
        (swap! seon.agent/state assoc :main m :base m)
        (is (= c0 @ctx/ctx) "hearing main changes no record")
        (let [db1 (:db-after (d/with db [{:seon.agent/key k :seon.agent/main-commit (d/commit-id m) :seon.agent/base-commit (d/commit-id m)}]))]
          (swap! seon.agent/state assoc :db db1)
          (is (= (d/commit-id m) (:seon.agent/main-commit @ctx/ctx)) "the fact, as the record is patched")
          (is (= (:seon.agent/main-commit @ctx/ctx) (:seon.agent/main-commit (:tree (ctx/build db1 (seon.db/eid db1 :seon.agent/key k) :b)))) "and as a cold build holds it")))
      (finally (end-agent!)))))

(defn new-eval
  "The input of size n: the record of a session of n evals, built, and one more eval's
  transaction applied to its db: [c db-after tx-data branch]."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :seon.host/db [:sequential :any] :keyword]]}
  [n]
  (let [db (seeded n) c (ctx/build db (aid db) :b) rep (d/with db (eval-tx (inc n)))]
    [c (:db-after rep) (:tx-data rep) :b]))

(defn- patched-by
  "The input of size n for a patch by transaction tx-of (a function of the db): the record of a
  session of n evals, built, and tx applied: [c db-after tx-data branch]."
  {:malli/schema [:=> [:cat pos-int? :seon.host/fn] [:tuple :map :seon.host/db [:sequential :any] :keyword]]}
  [n tx-of]
  (let [db (seeded n) c (ctx/build db (aid db) :b) rep (d/with db (tx-of db))]
    [c (:db-after rep) (:tx-data rep) :b]))

(defn cited-redefined
  "A cited var's redefinition (a new form, the var pointed to it), on a session of n evals."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :seon.host/db [:sequential :any] :keyword]]}
  [n] (patched-by n (fn [_] [{:db/id "f" :form/id "app.x/f0-2" :form/src "(defn f0 [x] (inc x))"} [:db/add [:var/name 'app.x/f0] :var/form "f"]])))

(defn new-caller
  "A new form naming a cited var (its caller), on a session of n evals."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :seon.host/db [:sequential :any] :keyword]]}
  [n] (patched-by n (fn [_] [{:form/id "app.x/c-2" :form/src "(defn c2 [] (f0 2))" :form/refs #{'app.x/f0}}])))

(defn callers-of
  "A new caller of a cited var already named by n forms, on a session of 10 evals."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :seon.host/db [:sequential :any] :keyword]]}
  [n]
  (let [db (:db-after (d/with (seeded 10) (vec (for [i (range n)] {:form/id (str "app.x/hot" i) :form/src (str "(defn hot" i " [] (f0 " i "))") :form/refs #{'app.x/f0}}))))
        c (ctx/build db (aid db) :b) rep (d/with db [{:form/id "app.x/hot-new" :form/src "(defn hot-new [] (f0 0))" :form/refs #{'app.x/f0}}])]
    [c (:db-after rep) (:tx-data rep) :b]))

(defn plan-edited
  "The task's plan rewritten, on a session of n evals."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :seon.host/db [:sequential :any] :keyword]]}
  [n] (patched-by n (fn [_] [[:db/add [:seon.task/id "lab/ctx"] :seon.task/plan "Make app.x/f0 pass, then app.x/f1."]])))

(defn eval-in-window
  "The input of size n: a session of n evals whose record holds a window of all n (the agent's
  pattern, :limit n), built, and one more eval's transaction applied: [c db-after tx-data branch]."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :seon.host/db [:sequential :any] :keyword]]}
  [n]
  (let [db (:db-after (d/with (seeded n) [[:db/add [:seon.agent/key k] :seon.ctx/pattern [(symbol "*") {[:seon.eval/_agent :as :evals :limit n :reverse true] [(symbol "*")]}]]]))
        c (ctx/build db (aid db) :b) rep (d/with db (eval-tx (inc n)))]
    [c (:db-after rep) (:tx-data rep) :b]))

(defn errored
  "The input of size n: the agent's record built on a store whose history holds n recorded errors
  last raised in the agent's task, and n errors of other tasks: [db agent-id branch]. The store grows;
  what the agent's record reads of it is the task's newest few."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db pos-int? :keyword]]}
  [n]
  (let [db (:db-after (d/with (seeded 20)
                              (vec (for [i (range n)]
                                     (cond-> {:seon.error/signature (format "%016d" i) :seon.error/class :eval :seon.error/count 1 :seon.error/agents 1
                                              :seon.error/first-ms i :seon.error/last-ms i :seon.error/type :seon.agent/eval-error
                                              :seon.error/message (str "boom " i) :seon.error/last-task (if (even? i) "lab/ctx" "lab/other")})))))]
    [db (aid db) :b]))

(deftest a-pull-costs-what-the-task-cites-not-the-stores-history
  ;; the agent's record reads the errors last raised in its task (a lookup by the task's id, newest
  ;; first, :seon.config.ctx/errors) and not the store's history of them: its bytes and datoms read
  ;; stay flat as the store holds 10, 100, 1,000 recorded errors of the task (measured: 541k, 543k
  ;; and 548k ticks, 2.39 MB and 292 calls at each, against 408k ticks, 1.89 MB and 263 calls before
  ;; the join; 298 calls with the content-id windows' one more check per reverse join read). The members are the task's own and the newest, never another task's
  (let [[db a b] (errored 20) c (ctx/build db a b)
        errs (:errors (:task (val (first (:runs (:tree c))))))]
    (is (= #{"boom 18" "boom 16" "boom 14" "boom 12" "boom 10"} (set (map :seon.error/message (vals errs)))) "the newest errors raised in the task: five of its ten")
    (is (every? #(= #{:db/id :seon.error/signature :seon.error/class :seon.error/type :seon.error/message :seon.error/count :seon.error/first-ms :seon.error/last-ms :seon.error/last-task} (set (keys %))) (vals errs)) "each a few attributes (what the scorer reads, docs/ctx.md), not the example's whole map"))
  (cost/proof
    {:of 'seon.ctx/build :input 'seon.ctx-test/errored
     :n "recorded errors in the store, half of them last raised in the agent's task"
     :work "the agent's pull as ever, and the task's errors join: an index seek of the task's id on :seon.error/last-task, the newest :seon.config.ctx/errors members pulled, whatever the history holds"
     :growth :constant
     :fixed {:calls 298 :bytes 2550000}
     :sizes [10 100 1000]}))

(deftest the-patch-costs-the-change-not-the-session
  ;; a new eval's transaction patches the record in the work of its datoms, whatever the session's
  ;; size: a patch that walked the record, or a rebuild, grows with the session (measured: 25 calls,
  ;; 159 KB, 28k ticks at 100 and at 1,000 evals; 815 KB and 200k ticks while the window was read
  ;; again and its map rebuilt)
  (cost/proof
    {:of 'seon.ctx/patch :input 'seon.ctx-test/new-eval
     :n "evals in the session's record"
     :work (str "the eval's ref datom touches the agent's :evals window (a watch key), which moves by it "
                "(window-moves): the new member pulled whole and assoc'd under its id, the oldest dissoc'd "
                "and forgotten; every other member and join the same object; the environment set")
     :growth :constant
     :fixed {:calls 25 :bytes 165000}
     :sizes [100 300 1000 3000]}))

(deftest a-ref-asserted-back-leaves-its-member-as-it-was
  ;; the regression the generator's draw hid (main red from Jolt f561902a, a sorted-map assoc that
  ;; replaces an equal value): an eval whose ref to the agent is retracted and asserted again in one
  ;; transaction is touched (its own datoms) and rebuilt, and came back a new object of equal value;
  ;; a rebuilt entity keeps every attribute equal to before, and a node equal in every value is the
  ;; node it was (seon.ctx/node)
  (let [db (seeded 30) c (ctx/build db (aid db) :b) e26 (id db [:seon.eval/id "e26"])
        rep (d/with db [[:db/retract e26 :seon.eval/agent [:seon.agent/key k]] [:db/add e26 :seon.eval/agent [:seon.agent/key k]]])
        c' (ctx/patch c (:db-after rep) (:tx-data rep) :b)]
    (is (identical? (get-in (:tree c) [:evals e26]) (get-in (:tree c') [:evals e26])) "the member: the same object")
    (is (identical? (:evals (:tree c)) (:evals (:tree c'))) "and its window")
    (is (= [] (ctx/changes c c')) "nothing changed")
    (is (= c' (ctx/build (:db-after rep) (aid (:db-after rep)) :b)) "as a build holds it")))

(deftest a-fresh-build-shares-what-it-did-not-change
  ;; task seon.ctx/share-assocs-what-it-shares: a fresh build (a relocation's) is handed back the
  ;; record's own objects wherever it holds the same values, by assoc into its collections (sorted
  ;; maps by id), which replace a value with an identical one (Jolt f561902a: before it, an assoc of
  ;; a value = to the one held kept the held one, and share built each map anew)
  (let [db (seeded 30) a (:tree (ctx/build db (aid db) :b)) b (:tree (ctx/build db (aid db) :b))
        share #'ctx/share]
    (is (not (identical? (:evals a) (:evals b))) "two builds: equal values, new objects")
    (is (identical? (:evals a) (share (:evals a) (:evals b))) "an equal collection: the record's own")
    (let [e7 (id db [:seon.eval/id "e7"])
          b' (assoc-in (:evals b) [e7 :seon.eval/by] "someone")
          s (share (:evals a) b')]
      (is (= "someone" (get-in s [e7 :seon.eval/by])) "the changed member: the new value")
      (is (every? (fn [[k v]] (or (= k e7) (identical? v (get (:evals a) k)))) s) "every other member: the record's own object")
      (is (identical? (get-in s [e7 :seon.eval/record]) (get-in (:evals a) [e7 :seon.eval/record])) "and, in the changed one, what did not change")
      (is (= (keys b') (keys s)) "in the collection's order"))))

(deftest a-window-moves-by-its-change
  ;; root's ruling, 2026-10-26: a window is a map by id, so a new member is an assoc and the oldest
  ;; a dissoc, never the window read again or its map rebuilt: flat in the window's size (measured:
  ;; 25 calls, 155 KB at a window of 100, 159 KB at 1,000), and the record a fresh build's
  (let [[c db tx b] (eval-in-window 30) c' (ctx/patch c db tx b)]
    (is (= c' (ctx/build db (aid db) b)) "as a build holds it")
    (is (= 30 (count (:evals (:tree c')))) "the window's size, the oldest gone")
    (is (= (str "e" 31) (:seon.eval/id (val (first (:evals (:tree c')))))) "the new eval first"))
  (cost/proof
    {:of 'seon.ctx/patch :input 'seon.ctx-test/eval-in-window
     :n "members of the window (the agent's pattern's :limit)"
     :work "the new member assoc'd, the oldest dissoc'd: two map operations on a sorted map by id, one pull, one forget"
     :growth :constant
     :fixed {:calls 25 :bytes 165000}
     :sizes [100 300 1000 3000]}))

(deftest a-key-renders-by-its-own-schema
  ;; the page draws @ctx as a block: a row per key, each value by its key's schema's declared
  ;; render, so an attribute an agent registers with a render draws by it; a key with none, and
  ;; a short key, by the one printer
  (let [proofs {'app.x-test/t0 {:status :pass}}
        c (with-meta {:t 7 :branch :main/a1 :commit (random-uuid) :seon.render/proofs proofs :evals {"e1" {:seon.eval/id "e1"}}}
            {:seon.render/schema :seon.ctx/ctx})
        h (render/html {} c)
        rows (into {} (for [x (tree-seq coll? seq h) :when (and (vector? x) (= :tr (first x)))]
                        [(last (nth x 2)) (last (nth x 3))]))]
    (is (= 'seon.render.ui/ctx (render/declared (render/schema-of c nil))) "@ctx's value names its block")
    (is (= [":evals" ":seon.render/proofs"] (sort (keys rows))) "a row per key, its environment above them")
    (is (= (render/html {:schema :seon.render/proofs} proofs) (rows ":seon.render/proofs")) "a key whose schema declares a render draws by it")
    (is (= (render/html {:schema nil} {"e1" {:seon.eval/id "e1"}}) (rows ":evals")) "a key with none draws as the printer's structure")))

(deftest a-change-names-its-eval
  ;; task seon.ctx/a-change-names-its-eval: who made each fact is a query on the tx-meta of its
  ;; transaction (seon.ctx/made-by): the eval whose code wrote it on the agent's branch, main for
  ;; one the agent was forked holding, main again for one a catch-up merged; the page's block
  ;; says so beside each key
  (let [w (fn [db branch more tx] (:db-after (d/with db tx (pdb/tx-meta branch more))))
        db (-> (ts/test-db)
               (w :main {} [{:seon.agent/key k :seon.agent/id :a1}])
               (w :main/a1 {:seon.tx/eval "e5"} [{:db/id [:seon.agent/key k] :seon.agent/description "Works the screen."}])
               (w :main/a1 {:seon.tx/merged :main} [{:db/id [:seon.agent/key k] :seon.agent/max-turns 3}]))
        e (:db/id (d/entity db [:seon.agent/key k]))
        by (fn [a] (dissoc (ctx/made-by db e a) :t))]
    (is (= {:seon.tx/branch :main/a1 :seon.tx/eval "e5"} (by :seon.agent/description)) "written by an eval: that eval, on the agent's branch")
    (is (= {:seon.tx/branch :main} (by :seon.agent/id)) "forked holding it: main's, by no eval")
    (is (= {:seon.tx/branch :main/a1 :seon.tx/merged :main} (by :seon.agent/max-turns)) "caught up: merged from main")
    (is (nil? (ctx/made-by db e :seon.eval/by)) "an attribute it does not have: nobody made it")
    (let [h (render/html {:db db} (with-meta (:tree (ctx/build db (aid db) :main/a1)) {:seon.render/schema :seon.ctx/ctx}))
          rows (into {} (for [x (tree-seq coll? seq h) :when (and (vector? x) (= :tr (first x)))] [(last (nth x 2)) (last (nth x 4))]))]
      (is (= {":seon.agent/description" "e5" ":seon.agent/id" "from :main" ":seon.agent/max-turns" "merged from :main"}
             (select-keys rows [":seon.agent/description" ":seon.agent/id" ":seon.agent/max-turns"]))
          "the page names who made each key"))))

(defn- replay
  "Tree t with diff d applied: each path's new value set, a gone one taken out (shorter paths first,
  as a member joins before its attributes)."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [t d]
  (reduce (fn [t [p [_ n]]] (cond (empty? p) n
                                  (nil? n) (update-in t (pop p) dissoc (peek p))
                                  :else (assoc-in t p n)))
          t (sort-by (comp count key) d)))

(deftest a-turns-diff-is-what-changed-between-its-ts
  ;; one derivation, revision 4.2: what changed of the record over a turn is a pure function of the
  ;; record at its start and end t (seon.ctx/diff), bounded by the paths its transactions changed,
  ;; read from the db as their net change (seon.ctx/advance); equal paths dropped
  (let [r (java.util.Random. 42)
        env #(apply dissoc % [:t :branch :commit])]
    (dotimes [turn 12]
      (let [db0 (seeded 30)
            ;; a turn: a few transactions of a session's kinds
            db1 (reduce (fn [db i] (:db-after (d/with db (change db i r)))) db0 (range (inc (.nextInt r 5))))
            c0 (ctx/build db0 (aid db0) :b)
            c1 (ctx/advance c0 db0 db1 :b)
            d (ctx/diff c0 c1)]
        (is (= c1 (ctx/build db1 (aid db1) :b)) "advanced, the record is the end t's")
        (is (= (env (:tree c1)) (env (replay (:tree c0) d))) "the start's record with the diff applied is the end's")
        (is (every? (fn [[_ [o n]]] (not= o n)) d) "no path whose value is equal at both ends")))
    (testing "a member retracted and made again under its key within the turn: the end's"
      (let [db0 (seeded 30)
            db1 (-> db0 (d/with [[:db/retractEntity [:seon.eval/id "e7"]]]) :db-after
                    (d/with [{:seon.eval/id "e7" :seon.eval/agent [:seon.agent/key k] :seon.eval/by "a1" :seon.eval/record {:code "(again)" :response {:value 1}}}]) :db-after)
            c0 (ctx/build db0 (aid db0) :b)
            c1 (ctx/advance c0 db0 db1 :b)]
        (is (= c1 (ctx/build db1 (aid db1) :b)))
        (let [e7 (id db1 [:seon.eval/id "e7"])]
          (is (= "(again)" (get-in (:tree c1) [:evals e7 :seon.eval/record :code])))
          (is (contains? (ctx/diff c0 c1) [:evals e7]) "the new e7, another entity: its member")
          (is (contains? (ctx/diff c0 c1) [:evals (id db0 [:seon.eval/id "e7"])]) "the old one's gone"))))
    (testing "a change undone within the turn is no change"
      (let [db0 (seeded 30)
            db1 (-> db0 (d/with [[:db/add [:seon.run/id "lab/ctx#1"] :seon.run/status :done]]) :db-after
                    (d/with [[:db/add [:seon.run/id "lab/ctx#1"] :seon.run/status :running]]) :db-after)
            c0 (ctx/build db0 (aid db0) :b)]
        (is (= {} (ctx/diff c0 (ctx/advance c0 db0 db1 :b))))))
    (testing "an eval added: its member, old nil"
      (let [db0 (seeded 30) db1 (:db-after (d/with db0 (eval-tx 31))) c0 (ctx/build db0 (aid db0) :b)
            d (ctx/diff c0 (ctx/advance c0 db0 db1 :b))]
        (is (= [[:evals (id db1 [:seon.eval/id "e31"])]] (keys d)))
        (is (nil? (first (val (first d)))))))))

(deftest a-window-of-content-ids-is-newest-first
  ;; a run's, turn's and message's entity id is derived from its content, so the id's order is not the
  ;; order they were written in: the window kept the members in the order members answers them (newest
  ;; first) and then sorted them again by id, so (first (:runs @ctx)) was the lost run after a restart
  ;; (found live by the context session). The window holds them newest first, and a patch that adds
  ;; one keeps it so
  (let [db0 (seeded 3)
        run (fn [db r] (:db-after (d/with db [{:seon.run/id r :seon.run/task [:seon.task/id "lab/ctx"] :seon.run/status :running :seon.run/agent [:seon.agent/key k]}])))
        ;; a pair of names whose later run has the smaller id
        [db older newer] (first (for [i (range 400)
                                      :let [a (str "w" i "#1") b (str "w" i "#2") db (run (run db0 a) b)
                                            ea (id db [:seon.run/id a]) eb (id db [:seon.run/id b])]
                                      :when (< eb ea)]
                                  [db ea eb]))]
    (is (some? db) "a later run with a smaller id exists among the names tried")
    (let [c (ctx/build db (aid db) :b)]
      (is (= newer (first (keys (:runs (:tree c))))) "the newer run first, whatever its id")
      (is (= [older] (rest (filter #{older newer} (keys (:runs (:tree c)))))) "and the older after it")
      (testing "a run added by a patch is first, and the diff names only it"
        (let [db2 (run db "w-third#1") c2 (ctx/advance c db db2 :b) third (id db2 [:seon.run/id "w-third#1"])]
          (is (= third (first (keys (:runs (:tree c2))))) "the newest first after the patch")
          (is (contains? (ctx/diff c c2) [:runs third]) "and the diff names it as the member that joined (the task, held once under the newest run, moves to its path)"))))))

(deftest a-lookups-patch-costs-its-change
  ;; a task's lookups (:by) patched in the work of what moved, whatever the session's size
  ;; (measured at 100 and 1,000 evals: a redefinition 23 calls and 118 KB, a new caller 22 and
  ;; 113 KB, a plan edit 10 and 86 KB), and each names the one path it changed
  (let [changed (fn [[c db tx b]] [db (set (map second (ctx/changes c (ctx/patch c db tx b))))])
        task #(vector :runs (id % [:seon.run/id "lab/ctx#1"]) :task)
        f0 #(into (task %) [:cited (id % [:var/name 'app.x/f0])])]
    (let [[db ps] (changed (cited-redefined 10))] (is (= #{(conj (f0 db) :form)} ps) "a redefinition: the cited var's form"))
    (let [[db ps] (changed (new-caller 10))] (is (= #{(conj (f0 db) :callers (id db [:form/id "app.x/c-2"]))} ps) "a new caller: that caller"))
    (let [[db ps] (changed (plan-edited 10))] (is (= #{(conj (task db) :seon.task/plan)} ps) "a plan edit: the plan")))
  (cost/proof
    {:of 'seon.ctx/patch :input 'seon.ctx-test/cited-redefined
     :n "evals in the session's record"
     :work "the var's datoms touch its :form join: the new form pulled whole, the old forgotten; the var, task, run and agent nodes copied up the path"
     :growth :constant :fixed {:calls 23 :bytes 125000} :sizes [100 300 1000 3000]})
  (cost/proof
    {:of 'seon.ctx/patch :input 'seon.ctx-test/new-caller
     :n "evals in the session's record"
     :work "the form's :form/refs datom touches the var's :callers watch: its callers read and ordered by when each was written (a form's id is its content's, not in time order), the newest five kept, a new one pulled whole, the path copied up"
     :growth :constant :fixed {:calls 22 :bytes 135000} :sizes [100 300 1000 3000]})
  (cost/proof
    {:of 'seon.ctx/patch :input 'seon.ctx-test/plan-edited
     :n "evals in the session's record"
     :work "the task's own datom: its attributes pulled again, every join kept, the path copied up"
     :growth :constant :fixed {:calls 10 :bytes 90000} :sizes [100 300 1000 3000]}))

(deftest a-callers-window-costs-its-forms
  ;; a window of content-id members (a var's callers: a form's id is its content's, not in time
  ;; order) is read whole and ordered by when each was written, so a new caller costs every form
  ;; naming the var: linear in them, flat in calls (measured: 25 calls; 0.9 MB and 288k ticks at
  ;; 100 forms, 9.1 MB and 2.4M ticks at 1,000). A var named by thousands (a hot core fn) costs that
  ;; on each new caller while a task cites it
  (cost/proof
    {:of 'seon.ctx/patch :input 'seon.ctx-test/callers-of
     :n "forms naming the cited var"
     :work "the new form's :form/refs datom touches the var's :callers watch: every form naming the var read from :avet and sorted by its ref's transaction, the newest five kept, the new one pulled whole, the path copied up"
     :growth :linear
     :per-n {:bytes 8800 :ticks 2600}
     :fixed {:calls 25 :bytes 500000 :ticks 100000}
     :sizes [100 300 1000 3000]}))

(defn one-eval-diff
  "The input of size n: a session of n evals at a turn's start, and its record advanced by one eval:
  [c0 c1], diff's."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :map]]}
  [n]
  (let [db0 (seeded n) db1 (:db-after (d/with db0 (eval-tx (inc n)))) c0 (ctx/build db0 (aid db0) :b)]
    [c0 (ctx/advance c0 db0 db1 :b)]))

(deftest a-turns-diff-costs-its-changed-paths-not-the-record
  ;; measured: 5 calls and 279 KB at 100 and 1,000 evals (diff, changes, its sort)
  (cost/proof
    {:of 'seon.ctx/diff :input 'seon.ctx-test/one-eval-diff
     :n "evals in the session's record"
     :work "changes' walk: the root's keys, each the same object but the evals' window, whose members are each the same object but the new one (and the oldest that left); the new eval's value read once"
     :growth :constant
     :fixed {:calls 5 :bytes 290000}
     :sizes [100 300 1000 3000]}))

(deftest a-redefined-vars-record-holds-its-new-form
  ;; a var's form is part of the var's record (:seon.db/var-fact's pattern, [:var/form :as :form]): a record over a
  ;; var, patched by its redefinition (a new form, the var pointed to it), holds the new form whole
  (let [db (d/db-with (ts/test-db) [{:db/id "f" :form/id "demo/f-1" :form/src "(defn f [] 1)"} {:var/name 'demo/f :var/form "f"}])
        e (:db/id (d/entity db [:var/name 'demo/f]))
        c (ctx/build db e :b)
        rep (d/with db [{:db/id "g" :form/id "demo/f-2" :form/src "(defn f [] 2)"} [:db/add e :var/form "g"]])
        c' (ctx/patch c (:db-after rep) (:tx-data rep) :b)]
    (is (= "(defn f [] 1)" (get-in (:tree c) [:form :form/src])) "built: the var with its form")
    (is (= "(defn f [] 2)" (get-in (:tree c') [:form :form/src])) "patched: the new form, whole")
    (is (= [[:replace [:form] (:form (:tree c'))]] (ctx/changes c c')) "what changed: the form")
    (is (= c' (ctx/build (:db-after rep) e :b)) "as a build holds it")))

(declare steps)

(deftest the-page-draws-what-the-task-record-holds
  ;; the live-agents context audit (2026-10-26): the task's record held each cited var's tests and
  ;; each cited section's text (D1-D3), and the page drew neither: the cited part showed the callers
  ;; alphabetically with no result, and a section only as an address. Each is drawn from ctx
  (let [db (d/db-with (seeded 3 nil) [;; a second test, and a caller that is no test
                                  {:db/id "t1" :form/id "app.x-test/t1-1" :form/src "(deftest t1 (is (= 2 (f0 2))))" :form/refs #{'app.x/f0}}
                                  {:var/name 'app.x-test/t1 :var/kind :test :var/form "t1"}
                                  {:db/id "g" :form/id "app.x/g-1" :form/src "(defn g [] (f0 2))" :form/refs #{'app.x/f0}}
                                  {:var/name 'app.x/g :var/form "g"}])
        page (try (reset! seon.agent/state {:db db :key k :branch :b}) (seon.repl/render-ctx-hiccup) (finally (end-agent!)))
        part (fn [prefix] (some (fn [[id h]] (when (str/starts-with? id prefix) h)) page))
        cited (part "cited-")
        task (pr-str (part "task-data-"))]
    (is (some #(and (= '[app.x-test/t0 app.x-test/t1] (:tests %)) (= ['app.x/g] (:callers %))) (tree-seq coll? seq cited))
        (str "the cited var's tests, then its other callers: " (pr-str cited)))
    (is (str/includes? task "What x does.") "the cited section's text, not only its address")
    (is (str/includes? task "docs/x.md#the-x") "under its heading and address")))

(deftest a-cited-part-shows-its-source-beside-its-tests
  ;; the second context audit (F1, F2): the cited part printed one map at one budget, so a long source
  ;; crowded its tests out; and a var whose form was also another cited var's caller printed its source
  ;; blank, with no cut (ctx holds an entity whole at its first path, {:db/id e} at the others). Its
  ;; source and the rest are each a call of the printer under its own budget, the source read by id
  (let [db (d/db-with (seeded 3 nil) [{:db/id "f1" :form/id "app.x/f1-1" :form/src "(defn f1 [] (f0 1))" :form/refs #{'app.x/f0}}
                                  {:var/name 'app.x/f1 :var/form "f1"}])
        page (try (reset! seon.agent/state {:db db :key k :branch :b}) (seon.repl/render-ctx-hiccup) (finally (end-agent!)))
        part (fn [id] (some (fn [[i h]] (when (str/ends-with? i id) h)) page))
        calls (fn [h] (filterv #(and (vector? %) (= #'seon.render/html (first %))) h))]
    (is (= "(defn f1 [] (f0 1))" (:seon.print/code (nth (first (calls (part "app.x_2Ff1"))) 2)))
        (str "the source of a form ctx holds at another path (f1's form is f0's caller): " (pr-str (part "app.x_2Ff1"))))
    (is (= 2 (count (calls (part "app.x_2Ff0")))) "the source and the tests, each its own call")
    (is (= "(defn f0 [x] x)" (:seon.print/code (nth (first (calls (part "app.x_2Ff0"))) 2))) "the source first")
    (is (contains? (nth (second (calls (part "app.x_2Ff0"))) 2) :tests) "then the tests, under their own budget")
    (is (not-any? #(= "ctx-keys" (first %)) page) "no part for keys the agent never added")))

(deftest a-component-is-styled-by-its-renders-name
  ;; the owner's styling ruling (DECISIONS, compact hiccup): a component's root element carries one
  ;; class, its render's name; the page's html adds the Tailwind classes its render's var states
  ;; (:seon.render/style), and what the model reads holds the name only
  (let [db (seeded 3 nil)
        as #(try (reset! seon.agent/state {:db db :key k :branch :b}) (%) (finally (end-agent!)))
        hiccup (into {} (as seon.repl/render-ctx-hiccup))
        units (:units (as seon.repl/render-ctx))
        task-id (some #(when (str/starts-with? % "task-data-") %) (keys hiccup))
        style (:seon.render/style (meta #'seon.render.ui/task-data))]
    (is (string? style) "the render's var states its style")
    (is (= "task-data" (:class (second (hiccup task-id)))) "its root's one class is its render's name")
    (is (not (str/includes? (pr-str hiccup) style)) "the model reads no style")
    (is (str/includes? (:html (some #(when (= task-id (:id %)) %) units)) (str "class=\"task-data " (first (str/split style #" "))))
        "the page's html has the name, then its var's classes")))

(deftest a-cited-vars-tests-are-shown-past-its-callers-window
  ;; build 3's self-review (D2): ctx holds a cited var's newest :seon.config.ctx/callers callers of any
  ;; kind, so its tests went off the page once newer callers outnumbered the window. The tests are
  ;; the cited render's own query (root's ruling (C)): three tests, then sixty newer callers
  (let [db (reduce d/db-with (seeded 3 nil)
                   [(vec (mapcat (fn [i] [{:db/id (str "t" i) :form/id (str "app.x-test/w" i "-1") :form/src (str "(deftest w" i " (is (f0 1)))") :form/refs #{'app.x/f0}}
                                          {:var/name (symbol "app.x-test" (str "w" i)) :var/kind :test :var/form (str "t" i)}])
                                 (range 3)))
                    (vec (mapcat (fn [i] [{:db/id (str "c" i) :form/id (str "app.y/c" i "-1") :form/src (str "(defn c" i " [] (f0 1))") :form/refs #{'app.x/f0}}
                                          {:var/name (symbol "app.y" (str "c" i)) :var/kind :fn :var/form (str "c" i)}])
                                 (range 60)))])
        c (ctx/build db (aid db) :b)
        n (seon.config/value :seon.config.ctx/callers)
        drawn (seon.render.ui/cited-of db (:tree c) 'app.x/f0)
        tests (:tests drawn)]
    (is (< n 61) "more newer callers than the window holds")
    (is (every? (set tests) '[app.x-test/w0 app.x-test/w1 app.x-test/w2]) (str "every test, past the window: " (pr-str tests)))
    (is (= n (count (:callers drawn))) "the other callers: ctx's window, the newest it holds")
    (testing "more tests than the window: the newest, the rest left to (my.agent/callers)"
      (let [db (d/db-with db (vec (mapcat (fn [i] [{:db/id (str "u" i) :form/id (str "app.x-test/u" i "-1") :form/src (str "(deftest u" i " (is (f0 1)))") :form/refs #{'app.x/f0}}
                                                    {:var/name (symbol "app.x-test" (str "u" i)) :var/kind :test :var/form (str "u" i)}])
                                          (range n))))
            tests (:tests (seon.render.ui/cited-of db (:tree (ctx/build db (aid db) :b)) 'app.x/f0))]
        (is (= n (count tests)))
        (is (not-any? '#{app.x-test/w0 app.x-test/w1 app.x-test/w2} tests) "the older three past the window")))))

(deftest an-agent-adds-a-datum-and-its-render
  ;; what an agent evaluates to add to its record and draw it (the owner, 2026-10-25: "they can
  ;; easily update it and just add more data and render functions"): its key registered with a
  ;; render is an attribute at once; @ctx's eval is recorded as its basis, never a copy; the page
  ;; renders it from the branch, and a key whose render the branch defines is drawn by its agent as
  ;; a part of its page (seon.repl/render-ctx; root, 2026-10-25: "nothing is stored")
  (let [form '[:vector {:seon.render/html seon.ctx-test/steps} :string]
        db (seeded 3)
        reg (my.schema/registration db :lab.ctx/steps form false)]
    (is (some #{{:db/ident :lab.ctx/steps :db/valueType :db.type/data :db/cardinality :db.cardinality/one}} (:tx reg))
        "a registered key holding a vector is stored as plain data, with no entity map listing it")
    (let [db (:db-after (d/with db (:tx reg)))
          db (:db-after (d/with db [{:seon.agent/key k :lab.ctx/steps ["read the plan" "write the test" "read the plan"]}]))
          tree (:tree (ctx/build db (aid db) :b))
          c (with-meta tree {:seon.render/schema :seon.ctx/ctx :seon.render/basis {:t (:t tree) :branch :b}})
          as-agent (fn [f] (try (reset! seon.agent/state {:db db}) (f) (finally (end-agent!))))
          rec (as-agent #(seon.repl/record-details db "@ctx" {:handle 'result/e9 :value c} {} nil))
          drawn (try (reset! seon.agent/state {:db db :key k :branch :b}) (seon.repl/render-ctx) (finally (end-agent!)))
          ;; the keys it added, one component (ctx-keys) holding each key's own
          steps (some #(when (= "ctx-keys" (:id %)) %) (:units drawn))]
      (is (= ["read the plan" "write the test" "read the plan"] (:lab.ctx/steps c)) "the vector comes back in its order, duplicates kept")
      (is (= {:schema :seon.ctx/ctx :basis {:t (:t tree) :branch :b}} (select-keys rec [:schema :basis])) "the record names the value's basis")
      (is (not (contains? (:response rec) :value)) "and holds no copy of the record")
      (is (re-find #":lab.ctx/steps" (:printed rec)) "the model's text is the one printer's, as it read it")
      (is (str/includes? (:html steps) "<ol><li>read the plan</li><li>write the test</li><li>read the plan</li></ol>")
          "the agent draws its key, a component of its page, by the render its branch names, recording nothing")
      (testing "the page the model reads (seon.repl/render-ctx-hiccup, the screen) is the page the user sees (seon.repl/render-ctx), hiccup for html"
        (let [[parts page] (try (reset! seon.agent/state {:db db :key k :branch :b}) [(seon.repl/render-ctx) (seon.repl/render-ctx-hiccup)]
                                (finally (end-agent!)))
              shown (:units parts)]
          (is (= (map :id shown) (keys page)) "every component, the conversation's too, in order: nothing left out")
          ;; the one difference: the page's html adds each root's render's style after its name (seon.render/styled)
          (let [styles (keep #(some-> (requiring-resolve (symbol "seon.render.ui" %)) meta :seon.render/style) (map #(:class (second %)) (vals page)))
                ;; a style as the html attribute holds it, escaped
                unstyled (fn [h] (reduce #(str/replace %1 (str " " (-> %2 (str/replace "&" "&amp;") (str/replace ">" "&gt;"))) "") h styles))]
            (is (= (map (comp unstyled :html) shown) (map (comp unstyled seon.web.html/html) (vals page)))
                "each component's hiccup is the html the page shows, byte for byte, but for its render's style"))
          (is (str/includes? (pr-str (get page "ctx-keys")) "read the plan") "as hiccup, exactly as its render returned it")))
      (testing "the page rebuilds the value from the branch at its basis"
        (let [db (:db-after (d/with db [{:seon.eval/id "e9" :seon.eval/agent [:seon.agent/key k] :seon.eval/record rec}]))
              h (seon.web.html/html (seon.render.ui/evaluation {:db db} (d/pull db '[*] [:seon.eval/id "e9"])))]
          (is (re-find (re-pattern (str "ctx · :b · t " (:t tree))) h) "the block of the record at t")
          (is (re-find #":lab.ctx/steps" h))))
      (testing "a render that throws in the agent is answered as its error, and the agent goes on"
        (let [db (:db-after (d/with db (:tx (my.schema/registration db :lab.ctx/boom '[:vector {:seon.render/html seon.ctx-test/boom} :any] false))))
              db (:db-after (d/with db [{:seon.agent/key k :lab.ctx/boom []}]))
              r (try (reset! seon.agent/state {:db db :key k :branch :b}) (seon.repl/render-ctx) (finally (end-agent!)))
              keys-html (:html (some #(when (= "ctx-keys" (:id %)) %) (:units r)))]
          (is (re-find #"cannot draw this" keys-html) "the render's throw, as its error's render in its component's place")
          (is (re-find #"<ol><li>read the plan" keys-html) "and every other component drawn"))))))

(defn boom
  "A render that throws, as an agent's unmerged one may."
  {:malli/schema [:=> [:cat :map :any] :any]}
  [_ _] (throw (ex-info "cannot draw this" {})))

(defn steps
  "A render an agent writes for its datum: the steps, numbered."
  {:malli/schema [:=> [:cat :map [:vector :string]] :any]}
  [_ v] [:ol (for [s v] [:li s])])

(defn- as-agent!
  "f with the agent's state holding db in test mode (transact! applies to the value, as in an agent's
  test fork) and the branch's schemas bound, @ctx built from it; answers [f's value, the db after]."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/fn] [:tuple :any :seon.host/db]]}
  [db f]
  (try (reset! seon.agent/state {:db db :key k :branch :b :mode :test})
       ;; under the branch's schemas, as the agent's REPL evaluates a form
       [(#'seon.repl/with-branch-schemas f) (:db @seon.agent/state)]
       (finally (end-agent!))))

(deftest a-tasks-record-holds-what-its-work-needs
  ;; the 20-turn context (shared note context-for-20-turns): the cited sections' text (D3), the
  ;; cited vars whole with their forms (D2) and their callers, the tests among them (D1), and
  ;; what each proof calls, by name (D5: its form's :form/refs)
  (let [db (seeded 10)
        t (get-in (:tree (ctx/build db (aid db) :b)) [:runs (id db [:seon.run/id "lab/ctx#1"]) :task])
        f0 (get-in t [:cited (id db [:var/name 'app.x/f0])])]
    (is (= ["What x does."] (names (:sections t) :seon.section/text)) "D3: the cited section's text, by its address")
    (is (= "(defn f0 [x] x)" (get-in f0 [:form :form/src])) "D2: the cited var's source")
    (is (= #{(id db [:form/id "app.x/c-1"]) (id db [:form/id "app.x-test/t0-1"])} (set (keys (:callers f0)))) "D1: its callers, the test among them")
    (let [t0 (get-in f0 [:callers (id db [:form/id "app.x-test/t0-1"])])]
      (is (= "(deftest t0 (is (= 1 (f0 1))))" (:form/src t0)) "D1: the test's source, held whole at its first path (a caller)")
      (is (= {:db/id (:db/id t0)} (get-in t [:proved (id db [:var/name 'app.x-test/t0]) :form]))
          "and {:db/id} under :proved, which reaches it later with the same pattern, so nothing is lost")
      (is (= #{'app.x/f0 'clojure.test/is} (:form/refs t0)) "D5: what the proof calls, each var by its name"))))

(deftest a-symbol-joins-by-name
  ;; :by, ctx's one option beside Datahike's pull syntax (docs/ctx.md#the-pattern): a symbol a
  ;; task holds joins the var it names by a lookup ref, [:var/name sym], and a var's callers join by
  ;; the same name reversed; the references stay the author's symbols, and a name of no var joins
  ;; nothing until the var is written
  (let [db (seeded 10)
        t #(get-in (:tree %) [:runs (id db [:seon.run/id "lab/ctx#1"]) :task])
        c (ctx/build db (aid db) :b)]
    (is (= #{'app.x/f0 'app.x/f1} (:seon.task/references (t c))) "the references, the author's symbols")
    (is (= ['app.x/f0] (names (:cited (t c)) :var/name)) "app.x/f0 joined by name; app.x/f1, written by nobody yet, not")
    (is (= "(defn f0 [x] x)" (get-in (t c) [:cited (id db [:var/name 'app.x/f0]) :form :form/src])) "the var whole, its form joined")
    (is (some #{"app.x/c-1"} (names (get-in (t c) [:cited (id db [:var/name 'app.x/f0]) :callers]) :form/id)) "its caller: the form naming it, by the same name reversed")
    (let [rep (d/with db [{:db/id "f" :form/id "app.x/f1-1" :form/src "(defn f1 [])"} {:var/name 'app.x/f1 :var/form "f"}])
          c' (ctx/patch c (:db-after rep) (:tx-data rep) :b)]
      (is (= ['app.x/f0 'app.x/f1] (sort-by str (names (:cited (t c')) :var/name))) "written, the var joins by its name")
      (is (= c' (ctx/build (:db-after rep) (aid (:db-after rep)) :b)) "as a build holds it"))
    (let [db (reduce (fn [db i] (:db-after (d/with db [{:form/id (str "app.x/c" i) :form/src (str "(defn c" i " [] (f0 " i "))") :form/refs #{'app.x/f0}}])))
                     db (range 2 9))]
      (is (= #{"app.x/c8" "app.x/c7" "app.x/c6"}
             (set (names (get-in (:tree (ctx/build (:db-after (d/with db [[:db/add [:seon.agent/key k] :seon.ctx/pattern
                                                                             [(symbol "*") {[:seon.run/_agent :as :runs :limit 5 :reverse true]
                                                                                            [(symbol "*") {[:seon.run/task :as :task]
                                                                                                           [(symbol "*") {[:seon.task/references :by :var/name :as :cited]
                                                                                                                          [(symbol "*") {[:form/_refs :by :var/name :as :callers :limit 3 :reverse true] [(symbol "*")]}]}]}]}]]]))
                                                   (aid db) :b))
                                 [:runs (id db [:seon.run/id "lab/ctx#1"]) :task :cited (id db [:var/name 'app.x/f0]) :callers]) :form/id)))
          "a callers' window of three: the newest three by when each was written (a form's id is its content's)"))
    (let [refused (fn [p] (:seon.error/type (try (render/pattern p) (catch clojure.lang.ExceptionInfo e (ex-data e)))))]
      (is (= :seon.ctx/invalid-pattern (refused '[* {[:seon.task/references :via :var/name] [*]}])) "an option neither pull's nor :by: refused, naming it")
      (is (= :seon.ctx/invalid-pattern (refused '[* (:seon.agent/key :limit 3)])) "a clause that is no '*, attribute or join")
      (is (= :seon.ctx/invalid-pattern (refused '[:seon.eval/_agent])) "a reverse attribute outside a join")
      (is (= :seon.ctx/invalid-pattern (refused '[{[:seon.eval/_agent :limit 3] [*]}])) "a window with no :reverse (the oldest n)"))))

(deftest an-agent-edits-its-pattern
  ;; the owner, 2026-10-26: "the pull pattern ... stored in the context and the agent can update it":
  ;; a swap! of :seon.ctx/pattern is written on the agent's entity, and the record is pulled by it
  (let [db (seeded 20)
        step (fn [db f] (as-agent! db #(let [r (try (f) (catch clojure.lang.ExceptionInfo e (ex-data e)))] [r @ctx/ctx])))]
    (let [[[_ c] db'] (step db #(swap! ctx/ctx assoc :seon.ctx/pattern '[* {[:seon.eval/_agent :as :evals :limit 3 :reverse true] [*]}]))]
      (is (= '[* {[:seon.eval/_agent :as :evals :limit 3 :reverse true] [*]}] (:seon.ctx/pattern (d/entity db' [:seon.agent/key k]))) "the pattern, on the agent's entity")
      (is (= ["e20" "e19" "e18"] (names (:evals c) :seon.eval/id)) "the record pulled by it: its three newest evals")
      (is (nil? (:runs c)) "and nothing the pattern does not name"))
    (let [[[r _] _] (step db #(swap! ctx/ctx assoc :seon.ctx/pattern '[* {[:seon.eval/_agent :newest 3] [*]}]))]
      (is (= :seon.ctx/invalid-pattern (:seon.error/type r)) "a pattern the walk cannot read: refused, nothing written"))))

(deftest a-swap-writes-the-record
  ;; the owner, 2026-10-25: "they can easily update it and just add more data": swap! of @ctx is one
  ;; transaction on the branch, checked by the schemas; the record after it is the db's
  (let [db (seeded 20)
        db (:db-after (d/with db (:tx (my.schema/registration db :lab.ctx/steps '[:vector :string] false))))
        same? (fn [db] (= (:tree (ctx/build db (aid db) :b)) @ctx/ctx))
        ;; f's value (or the ex-data of the error that refused it) and whether @ctx is the db's then
        step (fn [db f] (as-agent! db #(let [r (try (f) (catch clojure.lang.ExceptionInfo e (ex-data e)))] [r (same? (:db @seon.agent/state))])))]
    (testing "a datum the agent adds, an attribute of its own entity"
      (let [[[_ same] db'] (step db #(swap! ctx/ctx assoc :lab.ctx/steps ["read the plan" "make it pass"]))]
        (is (= ["read the plan" "make it pass"] (:lab.ctx/steps (d/pull db' [:lab.ctx/steps] [:seon.agent/key k]))))
        (is same "the record after the swap! is the db's")))
    (testing "a member gone, retracted whole"
      (let [[_ db'] (step db #(swap! ctx/ctx update :evals dissoc (id db [:seon.eval/id "e3"])))]
        (is (nil? (d/entity db' [:seon.eval/id "e3"])))
        (is (some? (d/entity db' [:seon.eval/id "e4"])))))
    (testing "a member's attribute, and a new member, written under its parent by the unit's ref"
      (let [r1 (id db [:seon.run/id "lab/ctx#1"])
            [[_ same] db'] (step db #(swap! ctx/ctx (fn [c] (-> c (assoc-in [:runs r1 :seon.run/status] :done)
                                                                (assoc-in [:runs r1 :messages "m-new"]
                                                                          {:seon.message/id "m-new" :seon.message/from "agent" :seon.message/body "a note to myself"})))))]
        (is (= :done (:seon.run/status (d/entity db' [:seon.run/id "lab/ctx#1"]))))
        (is (= "lab/ctx#1" (:seon.run/id (:seon.message/to (d/entity db' [:seon.message/id "m-new"])))))
        (is same)
        (is (= "a note to myself" (get-in @ctx/ctx [:runs r1 :messages (id db' [:seon.message/id "m-new"]) :seon.message/body])) "the swap!'s map gave way to the db's: the new member under its id")))
    (testing "a cardinality-many attribute of a forward unit, member by member"
      (let [[[_ same] db'] (step db #(swap! ctx/ctx update-in [:runs (id db [:seon.run/id "lab/ctx#1"]) :task :seon.task/proofs] conj 'app.x-test/t9))]
        (is (= #{'app.x-test/t0 'app.x-test/t9} (:seon.task/proofs (d/entity db' [:seon.task/id "lab/ctx"]))))
        (is same)))
    (testing "a new member with its own unit: a run and its turn, each naming its parent"
      (let [[[r same] db'] (step db #(swap! ctx/ctx assoc-in [:runs "lab/ctx#9"]
                                            {:seon.run/id "lab/ctx#9" :seon.run/status :running
                                             :turns {"lab/ctx#9/0" {:seon.turn/id "lab/ctx#9/0" :seon.turn/n 0 :seon.turn/reply "begin"}}}))]
        (is (nil? (:seon.error/type r)) (pr-str r))
        (is (= "lab/ctx#9" (:seon.run/id (:seon.turn/run (d/entity db' [:seon.turn/id "lab/ctx#9/0"])))))
        (is (= k (:seon.agent/key (:seon.run/agent (d/entity db' [:seon.run/id "lab/ctx#9"])))))
        (is same)))
    (testing "refused, nothing written, the record as it was: a key with no schema, a value its schema
      refuses, the environment, a reset! to what is no record"
      (doseq [[f t] [[#(swap! ctx/ctx assoc :notes "x") :seon.ctx/invalid]
                     [#(swap! ctx/ctx assoc :lab.ctx/steps [1 2]) :seon.ctx/invalid]
                     [#(swap! ctx/ctx assoc :t 1) :seon.ctx/basis]
                     [#(reset! ctx/ctx nil) :seon.ctx/basis]]]
        (let [[[r same] db'] (step db f)]
          (is (= t (:seon.error/type r)) (pr-str r))
          (is same "the record is the db's")
          (is (= (:max-tx db) (:max-tx db')) "the branch is unchanged"))))
    (testing "a cited var's source is refused, naming its definition as the way to change it"
      (let [r1 (id db [:seon.run/id "lab/ctx#1"]) f0 (id db [:var/name 'app.x/f0])
            [[r same] db'] (step db #(swap! ctx/ctx assoc-in [:runs r1 :task :cited f0 :form :form/src] "(defn f0 [x] (inc x))"))]
        (is (= :seon.ctx/invalid (:seon.error/type r)))
        (is (str/includes? (:why (first (:invalid r))) "(in-ns 'app.x) (defn f0 ...)") (pr-str r))
        (is same)
        (is (= (:max-tx db) (:max-tx db'))))
      (let [r1 (id db [:seon.run/id "lab/ctx#1"]) f0 (id db [:var/name 'app.x/f0])
            [[r] _] (step db #(swap! ctx/ctx update-in [:runs r1 :task :cited] dissoc f0))]
        (is (= "a lookup's members follow :seon.task/references: change it" (:why (first (:invalid r))))
            "no var changed in it (one gone): what the members follow, no definition named")))
    (testing "a swap! that changes nothing writes nothing, and the record stays the db's"
      (let [[[r same] db'] (step db #(swap! ctx/ctx update :runs dissoc "nope"))]
        (is (map? r))
        (is same)
        (is (= (:max-tx db) (:max-tx db')))))
    (testing "a swap!'s function that writes the db is refused once, never retried (the livelock)"
      (let [calls (atom 0)
            [[r] _] (step db #(swap! ctx/ctx (fn [c] (swap! calls inc) (seon.agent/transact! [{:seon.agent/key k :lab.ctx/steps ["x"]}]) (assoc c :lab.ctx/steps ["y"]))))]
        (is (= :seon.ctx/basis (:seon.error/type r)))
        (is (= 1 @calls))))))

(defn one-write
  "The input of size n: a session of n evals, its record, and a swap! of it changing one attribute:
  [db spec old new eid path acc], seon.ctx/edits's (spec, the agent's pattern as the walk takes it)."
  {:malli/schema [:=> [:cat pos-int?] [:vector :any]]}
  [n]
  (let [db (seeded n) c (ctx/build db (aid db) :b) t (:tree c)]
    [db (get-in c [:ix :spec (:db/id t)]) t (assoc t :seon.agent/status :busy) (:db/id t) [] {:tx [] :invalid [] :paths []}]))

(deftest a-write-costs-its-change-not-the-record
  ;; a swap!'s change found in the work of what differs: a unit identical to its old self (the
  ;; session's evals, its runs) is skipped by one identical? test, whatever its size
  (cost/proof
    {:of 'seon.ctx/edits :input 'seon.ctx-test/one-write
     :n "evals in the session's record"
     :work (str "one attribute of the agent's entity changed: its keys compared once (a dozen), the one "
                "that differs checked by its schema and written as one datom; the evals and runs units, "
                "identical, skipped whole: five contracted calls (edits, why and registered? for its schema, "
                "ref-of for its value, and edits' own return); measured 5 calls, 2,709 ticks and 23,280 bytes "
                "at 100 and at 1,000 evals")
     :growth :constant
     :fixed {:calls 5}
     :sizes [100 300 1000 3000]}))

(defn swap-input
  "The input of size n: a session of n evals as the agent's state and its record, and its run's
  id: [state record rid], one-swap's."
  {:malli/schema [:=> [:cat pos-int?] [:vector :any]]}
  [n]
  (let [st {:db (seeded n) :key k :branch :b :mode :test}]
    ;; the state's watch builds the record the swap! starts from (seon.ctx/on-state)
    (reset! seon.agent/state st)
    [st (ctx/record) (key (first (:runs (:tree (ctx/record)))))]))

(defn one-swap
  "One swap! of the task's plan through @ctx, from the session's state and record (set again
  each call, the state's watch, seon.ctx's, off while it is: constant work, a share of what is
  measured): write!'s edits, its d/with, the transaction applied in test mode and its patch, and
  the record installed."
  {:malli/schema [:=> [:cat :map :map :int] :nil]}
  [st record rid]
  (remove-watch seon.agent/state :seon.ctx/ctx)
  (reset! seon.agent/state st)
  (add-watch seon.agent/state :seon.ctx/ctx @#'seon.ctx/on-state)
  (#'seon.ctx/install! record)
  (swap! ctx/ctx assoc-in [:runs rid :task :seon.task/plan] "Make app.x/f0 pass, then f1.")
  nil)

(deftest a-swap-costs-what-it-changed
  ;; the owner, 2026-10-27: easy ops on the atom; a swap! of the plan costs the change, flat in
  ;; the session, as the patch and edits it is made of are
  (cost/proof
    {:of 'seon.ctx-test/one-swap :input 'seon.ctx-test/swap-input
     :n "evals in the session's record"
     :work (str "the task's plan changed: edits finds the one attribute (the evals unit skipped by one "
                "identical?), one d/with, the datom applied to the agent's db, the patch of it (the task "
                "rebuilt where it is held), the record installed, and one-swap itself; measured 39 calls, 117k-122k ticks and "
                "400-409 KB at 100, 300, 1,000 and 3,000 evals")
     :growth :constant
     :fixed {:calls 39}
     :sizes [100 300 1000 3000]})
  ;; no record left behind: an empty state builds none (as-agent!'s callers leave theirs too)
  (reset! seon.agent/state nil))

(defn one-attribute
  "The input of size n: a session of n evals and one attribute of its agent: [db e a], seon.ctx/made-by's."
  {:malli/schema [:=> [:cat pos-int?] [:vector :any]]}
  [n]
  (let [db (seeded n)] [db (:db/id (d/entity db [:seon.agent/key k])) :seon.agent/id]))

(deftest who-made-a-fact-costs-two-seeks-not-the-record
  ;; the page asks it of each key of the block: its work is the attribute's datoms and its
  ;; transaction, whatever the session holds
  (cost/proof
    {:of 'seon.ctx/made-by :input 'seon.ctx-test/one-attribute
     :n "evals in the session's record"
     :work "one seek of the entity's attribute (its one datom) and one pull of that datom's transaction: made-by's own call"
     :growth :constant
     :fixed {:calls 1}
     :sizes [100 300 1000 3000]}))

;; ---- changes' bound, checked on generated records -----------------------------------------

(defn- text "n letters, a function of seed." {:malli/schema [:=> [:cat :int nat-int?] :string]}
  [seed n] (apply str (map #(char (+ 97 (mod (+ seed (* 7 %)) 26))) (range n))))

(defn- members "Entities es as a collection: ctx's newest-first map by eid." {:malli/schema [:=> [:cat [:sequential :map]] :map]}
  [es] (#'seon.ctx/members-map (mapv (juxt :db/id identity) es)))

(defn- an-eval {:malli/schema [:=> [:cat nat-int? pos-int?] :map]} [i len]
  {:db/id (+ 1000 i) :seon.eval/id (str "e" i) :seon.eval/by "a1"
   :seon.eval/record {:code (str "(f " i ")") :response {:handle (symbol "result" (str "e" i)) :value (vec (range (mod i 9)))}
                      :line (text i (min len 80)) :prose (text (inc i) len)}})

(defn- record-of
  "An agent's record shaped as ctx holds it: evals, and runs each with the task (held once, a
  stub under the others) and its cited vars with their forms."
  {:malli/schema [:=> [:cat [:map [:evals nat-int?] [:runs nat-int?] [:cites nat-int?] [:len pos-int?]]] :map]}
  [{:keys [evals runs cites len]}]
  {:db/id 1 :seon.agent/key "b/a1"
   :evals (members (map #(an-eval % len) (range evals)))
   :runs (members (for [r (range runs)]
                    {:db/id (+ 100 r) :seon.run/id (str "demo/f#" r)
                     :task (if (pos? r) {:db/id 50}
                               {:db/id 50 :seon.task/id "demo/f" :seon.task/plan (text r (* 4 len))
                                :cited (members (for [i (range cites)] {:db/id (+ 5000 i) :var/name (symbol "demo" (str "f" i)) :var/doc (text i 40)
                                                                        :var/form {:db/id (+ 6000 i) :form/src (text (* 3 i) len)}}))})}))})

(defn- edited
  "Record t after edit [kind i], sharing every subtree it does not touch, as the patch does: a new
  eval, an eval's line replaced, every eval's line replaced, the plan edited, a cited form
  redefined, an eval gone; or an equal copy sharing nothing (a record built twice)."
  {:malli/schema [:=> [:cat :map [:tuple :keyword nat-int?]] :map]}
  [t [kind i]]
  (let [ev (keys (:evals t)) cited (some-> (get-in t [:runs 100 :task :cited]) keys)]
    (case kind
      :new-eval (assoc-in t [:evals (+ 1900 i)] (an-eval (+ 900 i) 60))
      :eval-edit (if (seq ev) (assoc-in t [:evals (nth ev (mod i (count ev))) :seon.eval/record :line] (text i 30)) t)
      :plan (if (get-in t [:runs 100]) (assoc-in t [:runs 100 :task :seon.task/plan] (text (+ 7 i) 200)) t)
      :cited (if (seq cited) (assoc-in t [:runs 100 :task :cited (nth cited (mod i (count cited))) :var/form :form/src] (text i 300)) t)
      :all-evals (reduce (fn [t e] (assoc-in t [:evals e :seon.eval/record :line] (text (+ e i) 30))) t ev)
      :rebuilt (walk/postwalk identity t)
      :gone (if (seq ev) (update t :evals dissoc (nth ev (mod i (count ev)))) t))))

(def records
  "changes' arguments, generated: a record and the same record after 0 to 5 edits."
  (gen/let [shape (gen/hash-map :evals (gen/choose 0 80) :runs (gen/choose 0 4) :cites (gen/choose 0 15) :len (gen/choose 10 400))
            edits (gen/vector (gen/tuple (gen/elements [:new-eval :eval-edit :all-evals :plan :cited :gone :rebuilt]) gen/nat) 0 5)]
    (let [t (record-of shape)] [t (reduce edited t edits)])))

(defn changes-unskipped
  "seon.ctx/changes without its identical? skip, under changes' own bound: every pair walked."
  {:malli/schema [:=> [:cat :map :map] [:vector [:tuple [:enum :add :remove :replace] [:vector :any] :any]]]
   :seon.cost/bound (fn [c0 c1] ((:seon.cost/bound (meta #'seon.ctx/changes)) c0 c1))}
  [c0 c1]
  (let [entity? (fn [x] (and (map? x) (contains? x :db/id)))
        members? (fn [x] (and (map? x) (not (entity? x)) (every? entity? (vals x))))
        walk (fn walk [acc p a b]
               (cond (nil? a) (if (nil? b) acc (conj acc [:add p b]))
                     (nil? b) (conj acc [:remove p nil])
                     (or (and (entity? a) (entity? b) (= (:db/id a) (:db/id b))) (and (members? a) (members? b)))
                     (reduce (fn [acc k] (walk acc (conj p k) (get a k) (get b k))) acc (distinct (concat (keys a) (keys b))))
                     (= a b) acc
                     :else (conj acc [:replace p b])))]
    (vec (sort-by (fn [[_ p]] (str/join "\u0001" (map str p))) (walk [] [] c0 c1)))))

(def ^:private changes-bound
  {:of 'seon.ctx/changes :gen 'seon.ctx-test/records :cases 200
   :n "a generated record and the same record after up to five edits sharing what they leave, or an equal copy sharing nothing"
   :work "the walk's keys where the two sides are not the same object, each value compared there, each change and its sort"})

(deftest changes-answers-what-every-pair-walked-answers
  ;; the walk in step (a collection's entries through its order, a member that is the same object
  ;; passed by) answers what the walk of every key of both sides answers, on generated records
  (let [r (tc/quick-check 200 (prop/for-all [[a b] records] (= (changes-unskipped a b) (ctx/changes a b))) :seed (proofs/test-seed))]
    (is (:pass? r) (pr-str (select-keys r [:seed]) (get-in r [:shrunk :smallest]))))
  (let [db (seeded 0) c (ctx/build db (aid db) :b) rep (d/with db (eval-tx 1))
        c' (ctx/patch c (:db-after rep) (:tx-data rep) :b)]
    (is (nil? (:evals (:tree c))) "a collection with no members is not held (node)")
    (is (= [[:add [:evals] (:evals (:tree c'))]] (ctx/changes c c')) "so its first member adds it whole, as every other pair walked")
    (is (= [] (ctx/changes (assoc-in c [:tree :t] 1) (assoc-in c [:tree :t] 2))) "the root's environment is not its data")))

(deftest changes-holds-its-bound-on-generated-records
  ;; task seon.cost/a-contract-carries-its-cost-bound: the bound sketches the walk; a copy that
  ;; skips nothing goes over it, so the proof can fail
  (cost/proof changes-bound)
  (let [{:keys [verdict]} (cost/run (assoc changes-bound :of 'seon.ctx-test/changes-unskipped))]
    (is (not (:ok? verdict)) "the walk with no identical? skip is over changes' bound")
    (is (some #(#{:ticks :bytes} (:dim %)) (:over verdict)) "over in a counted dimension")))

(deftest changes-step-costs-are-current
  ;; each step of seon.ctx/change-steps measured again on the case that isolates it, as the unit
  ;; table is (seon.cost-test/the-unit-table-is-current): a bound priced from stale steps is red here
  (let [m (fn [a b] (select-keys (cost/measure ctx/changes [a b]) [:ticks :bytes]))
        t (record-of {:evals 0 :runs 0 :cites 0 :len 10})
        per (fn [case a b] (let [x (case a) y (case b)] (into {} (for [d [:ticks :bytes]] [d (/ (- (get y d) (get x d)) (double (- b a)))]))))
        ;; a collection of n members re-made as a new map: n entries stepped past, nothing changed
        entries-at (fn [n] (let [t (record-of {:evals n :runs 0 :cites 0 :len 10})] (m t (update t :evals #(into (empty %) %)))))
        ;; n members of two keys each re-made: n entity pairs walked into, each two entries and four keys
        entities-at (fn [n] (let [t {:db/id 1 :evals (members (for [i (range n)] {:db/id i :a (str "v" i)}))}]
                              (m t (update t :evals #(into (empty %) (map (fn [[i e]] [i (into {} e)])) %)))))
        ;; n evals as the generator makes them, each rebuilt equal (walk/postwalk, as :rebuilt does):
        ;; per member an entity pair, two entries, eight keys, and its :seon.eval/record compared by =
        ;; (walk-of's size of it)
        eq-at (fn [n] (let [t {:db/id 1 :evals (members (map #(an-eval % 30) (range n)))}]
                        (m t (update t :evals #(into (empty %) (map (fn [[i e]] [i (walk/postwalk identity e)])) %)))))
        rec-size (#'seon.ctx/walk-of {:db/id 1 :r (:seon.eval/record (an-eval 7 30))} {:db/id 1 :r (walk/postwalk identity (:seon.eval/record (an-eval 7 30)))})
        ;; n members each holding a one-member collection re-made (the member itself kept): per member
        ;; what a member holding a string costs, less two entries, plus a collection pair walked into
        coll-at (fn [n] (let [t {:db/id 1 :evals (members (for [i (range n)] {:db/id i :c (members [{:db/id 0}])}))}]
                          (m t (update t :evals #(into (empty %) (map (fn [[i e]] [i (update e :c (fn [c] (into (empty c) c)))])) %)))))
        str-at (fn [n] (let [t {:db/id 1 :evals (members (for [i (range n)] {:db/id i :c "x"}))}]
                         (m t (update t :evals #(into (empty %) (map (fn [[i e]] [i (into {} e)])) %)))))
        ;; 300 members of k keys each re-made (small entities, array maps, as a record's members are):
        ;; per key added, a key of each side visited
        members-of (fn [k] (let [t {:db/id 1 :evals (members (for [i (range 300)] (into {:db/id i} (for [j (range (dec k))] [(keyword (str "a" j)) (str "v" i j)]))))}]
                             (m t (update t :evals #(into (empty %) (map (fn [[i e]] [i (into {} e)])) %)))))
        keys-per (let [x (members-of 2) y (members-of 6)] (into {} (for [d [:ticks :bytes]] [d (/ (- (get y d) (get x d)) (* 300.0 4))])))
        ;; n evals each changed
        changes-at (fn [n] (let [t (record-of {:evals n :runs 0 :cites 0 :len 10})] (m t (edited t [:all-evals 1]))))
        side (fn [x] (update-vals x #(/ % 2)))
        ;; a key or an entry is a step of one side (walk-of counts both): half of each pair's
        key (side keys-per) entry (side (per entries-at 100 1000))
        entity (merge-with - (per entities-at 100 1000) (update-vals entry #(* 2 %)) (update-vals key #(* 4 %)))
        measured {:fixed (m t t) :key key :entry entry :change (per changes-at 100 1000)
                  :entity entity
                  :collection (merge-with - (per coll-at 100 1000) (per str-at 100 1000) (update-vals entry #(* 2 %)))
                  :eq (update-vals (merge-with - (per eq-at 100 1000) entity (update-vals entry #(* 2 %)) (update-vals key #(* 8 %))) #(/ % (:eq rec-size)))}]
    (doseq [[step dims] measured [d v] dims :let [stated (get-in ctx/change-steps [step d])]]
      ;; within the slack above (the bound would be under), and not past a third below (it would be loose)
      (is (<= (* cost/loose stated) v (* (get cost/slack d) stated)) (str step " " d ": measured " v " against " stated " stated")))))
