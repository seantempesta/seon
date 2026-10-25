(ns seon.ctx-test
  "The agent's record as one map (seon.ctx): kept by a patch from each transaction's datoms, equal
  to a build from the db after every one, at the cost of the change; drawn on the page as a block,
  each key by its own schema's render. Run: bin/test seon.ctx-test"
  (:refer-clojure :exclude [defn defn-])
  (:require [clojure.test :refer [deftest is testing]] [seon.contract :refer [defn defn-]] [seon.cost :as cost]
            [seon.test-store :as ts] [datahike.api :as d] [seon.ctx :as ctx] [seon.render :as render] [seon.render.ui] [seon.repl] [seon.agent] [seon.web.html] [my.schema]))

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
  own attribute, a turn moved to another run, a run gone, a run's messages all gone (a unit
  emptied)."
  {:malli/schema [:=> [:cat :seon.host/db nat-int? :any] [:vector :any]]}
  [db i ^java.util.Random r]
  (let [runs (vec (d/q '[:find [?id ...] :where [_ :seon.run/id ?id]] db))
        evals (vec (sort (d/q '[:find [?id ...] :where [_ :seon.eval/id ?id]] db)))
        run (nth runs (.nextInt r (count runs)))
        turns (vec (sort (d/q '[:find [?id ...] :where [_ :seon.turn/id ?id]] db)))]
    (case (.nextInt r 12)
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
      11 (or (not-empty (vec (for [m (d/q '[:find [?m ...] :in $ ?r :where [?re :seon.run/id ?r] [?m :seon.message/to ?re]] db run)] [:db/retractEntity m])))
             (eval-tx (+ 50000 i))))))

(deftest ctx-is-the-record-after-every-transaction
  ;; the patch holds what a build holds, its map and its index, after each of 150 changes of a
  ;; session's kinds (a seeded random choice, so a failure repeats): the map is never a second
  ;; record of its own
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
          (recur db' c' (inc i) (cond-> bad (not= c' (ctx/build db' k :b)) (conj [i tx]))))))))

(deftest the-agents-state-keeps-ctx
  ;; the atom follows the agent's db: built when the state first holds it, patched from the
  ;; transaction transact! records, and from what two values differ by when no transaction moved it
  (let [db (seeded 10) rep (d/with db (eval-tx 11))]
    (try
      (reset! seon.agent/state {:db db :key k :branch :b})
      (is (= (:tree (ctx/build db k :b)) @ctx/ctx) "built when the state first holds the db")
      (is (= {:seon.render/schema :seon.ctx/ctx :seon.render/basis {:t (:max-tx db) :branch :b}} (meta @ctx/ctx))
          "marked for its block, with the basis an eval of it records")
      (swap! seon.agent/state assoc :db (:db-after rep) :tx (select-keys rep [:db-after :tx-data]))
      (is (contains? (:evals @ctx/ctx) "e11") "a transaction's eval joins")
      (is (= (:tree (ctx/build (:db-after rep) k :b)) @ctx/ctx))
      (swap! seon.agent/state assoc :db db)
      (is (= (:tree (ctx/build db k :b)) @ctx/ctx) "a move with no transaction is patched by the difference")
      (finally (reset! seon.agent/state nil)))))

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
        rows (into {} (for [x (tree-seq coll? seq h) :when (and (vector? x) (= :tr (first x)))]
                        [(last (nth x 2)) (last (nth x 3))]))]
    (is (= 'seon.render.ui/ctx (render/declared (render/schema-of c nil) :html)) "@ctx's value names its block")
    (is (= [":evals" ":seon.render/proofs"] (sort (keys rows))) "a row per key, its environment above them")
    (is (= (render/html proofs {:schema :seon.render/proofs}) (rows ":seon.render/proofs")) "a key whose schema declares a render draws by it")
    (is (= (render/html {"e1" {:seon.eval/id "e1"}} {:schema nil}) (rows ":evals")) "a key with none draws as the printer's structure")))

(declare steps)

(deftest an-agent-adds-a-datum-and-its-render
  ;; what an agent evaluates to add to its record and draw it (the owner, 2026-10-25: "they can
  ;; easily update it and just add more data and render functions"): its key registered with a
  ;; render is an attribute at once; @ctx's eval is recorded as its basis, never a copy; the page
  ;; renders it from the branch, and a key whose render the branch defines is drawn by its agent,
  ;; asked while viewed (root, 2026-10-25: "nothing is stored")
  (let [form '[:vector {:seon.render/html seon.ctx-test/steps} :string]
        db (seeded 3)
        reg (my.schema/registration db :lab.ctx/steps form false)]
    (is (some #{{:db/ident :lab.ctx/steps :db/valueType :db.type/data :db/cardinality :db.cardinality/one}} (:tx reg))
        "a registered key holding a vector is stored as plain data, with no entity map listing it")
    (let [db (:db-after (d/with db (:tx reg)))
          db (:db-after (d/with db [{:seon.agent/key k :lab.ctx/steps ["read the plan" "write the test" "read the plan"]}]))
          tree (:tree (ctx/build db k :b))
          c (with-meta tree {:seon.render/schema :seon.ctx/ctx :seon.render/basis {:t (:t tree) :branch :b}})
          as-agent (fn [f] (try (reset! seon.agent/state {:db db}) (f) (finally (reset! seon.agent/state nil))))
          rec (as-agent #(seon.repl/record-details db "@ctx" {:handle 'result/e9 :value c} {} nil))
          block (fn [drawn] (let [w (volatile! #{})
                                  h (binding [seon.render.ui/*drawn* drawn seon.render.ui/*wanted* w]
                                      (seon.web.html/html (render/html c {:db db :drawer {:agent :a1 :handle 'result/e9}})))]
                              [h @w]))
          answer (as-agent #(-> (seon.repl/agent-step {} :sock {:op :render :id 7 :schema :lab.ctx/steps :value (:lab.ctx/steps c)}) second :sock first))]
      (is (= ["read the plan" "write the test" "read the plan"] (:lab.ctx/steps c)) "the vector comes back in its order, duplicates kept")
      (is (= {:schema :seon.ctx/ctx :basis {:t (:t tree) :branch :b}} (select-keys rec [:schema :basis])) "the record names the value's basis")
      (is (not (contains? (:response rec) :value)) "and holds no copy of the record")
      (is (re-find #":lab.ctx/steps" (:printed rec)) "the model's text is the one printer's, as it read it")
      (let [[h w] (block {})]
        (is (= #{[:a1 'result/e9 :lab.ctx/steps ["read the plan" "write the test" "read the plan"]]} w) "the page asks the agent to draw the key")
        (is (re-find #"drawn by seon.ctx-test/steps in a1 when it answers" h) "and shows its structure meanwhile, naming its render"))
      (is (= {:op :reply :id 7 :response {:html "<ol><li>read the plan</li><li>write the test</li><li>read the plan</li></ol>"}} answer)
          "the agent draws it by the render its branch names, recording nothing")
      (let [[h w] (block {[:a1 'result/e9 :lab.ctx/steps] (:response answer)})]
        (is (re-find #"<ol><li>read the plan</li>" h) "the page shows the agent's drawing")
        (is (empty? w) "and asks nothing more"))
      (let [[h w] (block {[:a1 'result/e9 :lab.ctx/steps] {:error {:seon.error/message "no live agent :a1"}}})]
        (is (re-find #"drawn by seon.ctx-test/steps in a1, which did not answer" h) "an agent that is gone: its structure, saying so")
        (is (empty? w))))))

(defn steps
  "A render an agent writes for its datum: the steps, numbered."
  {:malli/schema [:=> [:cat [:vector :string] :map] :any]}
  [v _] [:ol (for [s v] [:li s])])
