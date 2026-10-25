(ns seon.ctx-test
  "The agent's record as one map (seon.ctx): kept by a patch from each transaction's datoms, equal
  to a build from the db after every one, at the cost of the change; drawn on the page as a block,
  each key by its own schema's render. Run: bin/test seon.ctx-test"
  (:refer-clojure :exclude [defn defn-])
  (:require [clojure.test :refer [deftest is testing]] [seon.contract :refer [defn defn-]] [seon.cost :as cost]
            [seon.test-store :as ts] [datahike.api :as d] [seon.ctx :as ctx] [seon.render :as render] [seon.render.ui] [seon.repl] [seon.agent] [my.schema]))

(def ^:private k "The test agent's key." "test-boot/a1")

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
  "The test store's db with the agent's record of n evals: its task, one run with n/3 turns and
  n/20 messages, written in transactions of 100 evals as a session writes them."
  {:malli/schema [:=> [:cat nat-int?] :seon.host/db]}
  [n]
  (let [db (:db-after (d/with (ts/test-db) (into [{:seon.agent/key k :seon.agent/id :a1}
                                                   {:seon.task/id "lab/ctx" :seon.task/ns 'app.x :seon.task/name "ctx" :seon.task/plan "Make app.x/f0 pass."
                                                    :seon.task/kind :work :seon.task/proofs #{'app.x-test/t0}}]
                                                  (run-tx "lab/ctx#1" 0))))]
    (reduce (fn [db part]
              (:db-after (d/with db (vec (mapcat (fn [i] (cond-> (eval-tx i)
                                                            (zero? (mod i 3)) (conj {:seon.turn/id (str "lab/ctx#1/" i) :seon.turn/run [:seon.run/id "lab/ctx#1"] :seon.turn/n i})
                                                            (zero? (mod i 20)) (conj {:seon.message/id (str "m" i) :seon.message/from "user"
                                                                                      :seon.message/to [:seon.run/id "lab/ctx#1"] :seon.message/body "How is it going?"})))
                                                 part)))))
            db (partition-all 100 (range 1 (inc n))))))

(defn- change
  "The i-th change of a session, chosen by r from what one does: an eval, a new run with turns, a
  turn or message on a run, a run's status, the task's plan and proofs, an eval gone, the agent's
  own attribute."
  {:malli/schema [:=> [:cat :seon.host/db nat-int? :any] [:vector :any]]}
  [db i ^java.util.Random r]
  (let [runs (vec (d/q '[:find [?id ...] :where [_ :seon.run/id ?id]] db))
        evals (vec (sort (d/q '[:find [?id ...] :where [_ :seon.eval/id ?id]] db)))
        run (nth runs (.nextInt r (count runs)))]
    (case (.nextInt r 9)
      0 (eval-tx (+ 10000 i))
      1 (run-tx (str "lab/ctx#" (+ 2 i)) 2)
      2 [{:seon.turn/id (str run "/x" i) :seon.turn/run [:seon.run/id run] :seon.turn/n i}]
      3 [{:seon.message/id (str "mx" i) :seon.message/from "user" :seon.message/to [:seon.run/id run] :seon.message/body (str "note " i)}]
      4 [[:db/add [:seon.run/id run] :seon.run/status (nth [:running :done :closed] (.nextInt r 3))]]
      5 [[:db/add [:seon.task/id "lab/ctx"] :seon.task/plan (str "plan " i)]]
      6 [[(if (.nextBoolean r) :db/add :db/retract) [:seon.task/id "lab/ctx"] :seon.task/proofs (symbol "app.x-test" (str "t" (.nextInt r 3)))]]
      7 (if (seq evals) [[:db/retractEntity [:seon.eval/id (nth evals (.nextInt r (count evals)))]]] (eval-tx (+ 20000 i)))
      8 [[:db/add [:seon.agent/key k] :seon.agent/status (nth [:idle :busy] (.nextInt r 2))]])))

(deftest ctx-is-the-record-after-every-transaction
  ;; the patch holds what a build holds, after each of 150 changes of a session's kinds (a seeded
  ;; random choice, so a failure repeats): the map is never a second record of its own
  (let [r (java.util.Random. 25)]
    (testing "the record holds the session: its evals keyed by id, its runs with their task, turns and messages"
      (let [t (:tree (ctx/build (seeded 60) k :b))]
        (is (= 60 (count (:evals t))))
        (is (= {:seon.eval/id "e7" :by "a1"} {:seon.eval/id (get-in t [:evals "e7" :seon.eval/id]) :by (get-in t [:evals "e7" :seon.eval/by])}))
        (is (= "lab/ctx" (get-in t [:runs "lab/ctx#1" :task :seon.task/id])))
        (is (= #{'app.x-test/t0} (get-in t [:runs "lab/ctx#1" :task :seon.task/proofs])))
        (is (= 20 (count (get-in t [:runs "lab/ctx#1" :turns]))))
        (is (= #{"m20" "m40" "m60"} (set (keys (get-in t [:runs "lab/ctx#1" :messages])))))))
    (loop [db (seeded 60) c (ctx/build db k :b) i 0 bad []]
      (if (= i 150)
        (is (empty? bad) (str "the change after which the patch and a build differ: " (first bad)))
        (let [tx (change db i r) rep (d/with db tx) db' (:db-after rep) c' (ctx/patch c db' (:tx-data rep) :b)]
          (recur db' c' (inc i) (cond-> bad (not= (:tree c') (:tree (ctx/build db' k :b))) (conj [i tx]))))))))

(defn new-eval
  "The input of size n: the record of a session of n evals, built, and one more eval's
  transaction applied to its db: [c db-after tx-data branch]."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :seon.host/db [:sequential :any] :keyword]]}
  [n]
  (let [db (seeded n) c (ctx/build db k :b) rep (d/with db (eval-tx (inc n)))]
    [c (:db-after rep) (:tx-data rep) :b]))

(deftest the-patch-costs-the-change-not-the-session
  ;; a new eval's transaction patches the record in the work of its datoms, whatever the
  ;; session's size: the decision rule of the note (8,040 ticks at 115 evals, 8,269 at 3,041 in
  ;; the lab); a patch that walked the record, or a rebuild, grows with the session
  (cost/proof
    {:of 'seon.ctx/patch :input 'seon.ctx-test/new-eval
     :n "evals in the session's record"
     :work (str "one eval's three datoms (its id, its agent, its record): the id's entity is new, so its "
                "ref to the agent, a known entity, joins it, read whole (one pull) and keyed by its id (one "
                "datom seek); the environment's t, branch and commit set. Nothing per eval already held. Six "
                "contracted calls: patch, join, node, own (the pull), key-of (the seek), environment; measured "
                "6 calls, 9,746 / 9,841 ticks and 46,960 / 47,744 bytes at 100 / 1,000 evals")
     :growth :constant
     :fixed {:calls 6}
     :sizes [100 1000 3000]}))

(deftest a-key-renders-by-its-own-schema
  ;; the page draws @ctx as a block: a row per key, each value by its key's schema's declared
  ;; render, so an attribute an agent registers with a render draws by it; a key with none, and
  ;; a short key, by the one printer
  (let [proofs {'app.x-test/t0 {:status :pass}}
        c (with-meta {:t 7 :branch :main/a1 :commit (random-uuid) :seon.render/proofs proofs :evals {"e1" {:seon.eval/id "e1"}}}
            {:seon.render/schema :seon.ctx/ctx})
        h (render/html c {})
        has? (fn [x] (some #(= x %) (tree-seq coll? seq h)))]
    (is (= 'seon.render.ui/ctx (render/declared (render/schema-of c nil) :html)) "@ctx's value names its block")
    (is (has? (render/html proofs {:schema :seon.render/proofs})) "a key whose schema declares a render draws by it")
    (is (has? (render/html {"e1" {:seon.eval/id "e1"}} {:schema nil})) "a key with none draws as the printer's structure")
    (is (has? ":seon.render/proofs") "each row names its key")))

(declare steps)

(deftest an-agent-adds-a-datum-and-its-render
  ;; what an agent evaluates to add to its record and draw it (the owner, 2026-10-25: "they can
  ;; easily update it and just add more data and render functions"): its key registered with a
  ;; render is an attribute at once, and @ctx's eval is recorded as the text the model read and the
  ;; block the page draws, never as a copy of the record
  (let [db (seeded 3)
        reg (my.schema/registration db :lab.ctx/steps '[:vector {:seon.render/html seon.ctx-test/steps} :string] false)]
    (is (some #{{:db/ident :lab.ctx/steps :db/valueType :db.type/data :db/cardinality :db.cardinality/one}} (:tx reg))
        "a registered key holding a vector is stored as plain data, with no entity map listing it")
    (let [db (:db-after (d/with db (:tx reg)))
          db (:db-after (d/with db [{:seon.agent/key k :lab.ctx/steps ["read the plan" "write the test" "read the plan"]}]))
          c (with-meta (:tree (ctx/build db k :b)) {:seon.render/schema :seon.ctx/ctx})
          ;; the REPL reads its branch's schemas from the agent's state, as an agent holds it
          rec (try (reset! seon.agent/state {:db db})
                   (seon.repl/record-details db "@ctx" {:handle 'result/e9 :value c} {} nil)
                   (finally (reset! seon.agent/state nil)))]
      (is (= ["read the plan" "write the test" "read the plan"] (:lab.ctx/steps c)) "the vector comes back in its order, duplicates kept")
      (is (not (contains? (:response rec) :value)) "the record holds no copy of the record")
      (is (re-find #":lab.ctx/steps" (:printed rec)) "the model's text is the one printer's, as it read it")
      (is (re-find #"<ol><li>read the plan</li><li>write the test</li><li>read the plan</li></ol>" (:seon.web.html/raw (:seon.render/html rec)))
          "the block draws the key by the render its registered schema names"))))

(defn steps
  "A render an agent writes for its datum: the steps, numbered."
  {:malli/schema [:=> [:cat [:vector :string] :map] :any]}
  [v _] [:ol (for [s v] [:li s])])
