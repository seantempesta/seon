(ns seon.ctx-test
  "The agent's record as one map (seon.ctx): kept by a patch from each transaction's datoms, equal
  to a build from the db after every one, at the cost of the change; drawn on the page as a block,
  each key by its own schema's render. Run: bin/test seon.ctx-test"
  (:refer-clojure :exclude [defn defn-])
  (:require [clojure.test :refer [deftest is testing]] [seon.contract :refer [defn defn-]] [seon.cost :as cost]
            [seon.test-store :as ts] [datahike.api :as d] [seon.ctx :as ctx] [seon.db :as pdb] [seon.context :as context] [seon.task :as task] [seon.vector :as vector] [seon.render :as render] [seon.render.ui] [seon.repl] [seon.agent] [seon.web.html] [seon.root] [my.schema]))

(def ^:private k "The test agent's key." "test-boot/a1")

(defn- aid "The test agent's entity on db." {:malli/schema [:=> [:cat :seon.host/db] [:maybe pos-int?]]}
  [db] (:db/id (d/entity db [:seon.agent/key k])))

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
                                                    :seon.task/kind :work :seon.task/proofs #{'app.x-test/t0}
                                                    :seon.task/execution :running :seon.task/branch :main/lab-ctx}]
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
    (case (.nextInt r 15)
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
      11 (or (not-empty (vec (for [m (d/q '[:find [?m ...] :in $ ?r :where [?re :seon.run/id ?r] [?m :seon.message/to ?re]] db run)] [:db/retractEntity m])))
             (eval-tx (+ 50000 i))))))

(deftest ctx-is-the-record-after-every-transaction
  ;; the patch holds what a build holds, its map and its index, after each of 150 changes of a
  ;; session's kinds (a seeded random choice, so a failure repeats): the map is never a second
  ;; record of its own
  (let [r (java.util.Random. 25)]
    (testing "the record holds the session: its evals keyed by id, its runs with their task, turns and messages"
      (let [db (seeded 60) t (:tree (ctx/build db (aid db) :b))]
        (is (= 60 (count (:evals t))))
        (is (= {:seon.eval/id "e7" :by "a1"} {:seon.eval/id (get-in t [:evals "e7" :seon.eval/id]) :by (get-in t [:evals "e7" :seon.eval/by])}))
        (is (= "lab/ctx" (get-in t [:runs "lab/ctx#1" :task :seon.task/id])))
        (is (= #{'app.x-test/t0} (get-in t [:runs "lab/ctx#1" :task :seon.task/proofs])))
        (is (= 20 (count (get-in t [:runs "lab/ctx#1" :turns]))))
        (is (= #{"m20" "m40" "m60"} (set (keys (get-in t [:runs "lab/ctx#1" :messages])))))))
    (loop [db (seeded 60) c (ctx/build db (aid db) :b) i 0 bad [] unshared [] checked 0]
      (if (= i 150)
        (do (is (empty? bad) (str "the change after which the patch and a build differ: " (first bad)))
            (is (< 1000 checked) (str "the sharing check reads the paths no change touched: " checked))
            (is (empty? unshared) (str "a path no change touched that is a new object: " (first unshared))))
        (let [tx (change db i r) rep (d/with db tx) db' (:db-after rep) c' (ctx/patch c db' (:tx-data rep) :b)
              b (ctx/build db' (aid db') :b)
              ;; every member path held before and after that is not at, under or above a changed path
              related? (fn [p q] (let [n (min (count p) (count q))] (= (subvec p 0 n) (subvec q 0 n))))
              kept (for [[_ ps] (:ix c) [p _] ps :when (and (seq p) (not-any? #(related? p %) (:changed c'))
                                                           (some? (get-in (:tree c') p)))] p)]
          (recur db' c' (inc i)
                 (cond-> bad (not= (dissoc c' :changed) (dissoc b :changed)) (conj [i tx]))
                 (into unshared (for [p kept :when (not (identical? (get-in (:tree c) p) (get-in (:tree c') p)))] [i p]))
                 (+ checked (count kept))))))
    (testing "a build answers the root path changed: a memo misses once, everywhere"
      (let [db (seeded 5)] (is (= #{[]} (:changed (ctx/build db (aid db) :b))))))))

(deftest any-entitys-record-follows-mains-commits
  ;; root's, the user's and a task's record are built over their entity and patched from the
  ;; writer's commits of main ({:t :ops :db}, explicit-id ops): equal to a build after each
  (let [r (java.util.Random. 7)
        db0 (seeded 20)
        te (:db/id (d/entity db0 [:seon.task/id "lab/ctx"]))
        re (:db/id (d/entity db0 [:seon.run/id "lab/ctx#1"]))]
    (is (= "lab/ctx" (:seon.task/id (:tree (ctx/build db0 te :main)))) "a task's record")
    (is (contains? (:messages (:tree (ctx/build db0 re :main))) "m20") "a run's, with its units")
    (loop [db db0 cs {te (ctx/build db0 te :main) re (ctx/build db0 re :main)} i 0 bad []]
      (if (= i 60)
        (is (empty? bad) (str "the commit after which a followed record and a build differ: " (first bad)))
        (let [tx (change db i r) rep (d/with db tx) db' (:db-after rep)
              commit {:t (:max-tx db') :ops (pdb/report->tx rep) :db db'}
              cs' (into {} (for [[e c] cs] [e (ctx/follow c commit :main)]))]
          (recur db' cs' (inc i)
                 (into bad (for [[e c] cs' :let [b (ctx/build db' (when (seq (d/datoms db' :eavt e)) e) :main)]
                                 :when (not= (dissoc c :changed) (dissoc b :changed))] [i e tx]))))))
    (testing "a followed run retracted: its environment alone, as a build of no entity"
      (let [rep (d/with db0 [[:db/retractEntity re]]) db' (:db-after rep)
            c (ctx/follow (ctx/build db0 re :main) {:t (:max-tx db') :ops (pdb/report->tx rep) :db db'} :main)]
        (is (= (dissoc (ctx/build db' nil :main) :changed) (dissoc c :changed)))))))

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
      (is (contains? (:evals @ctx/ctx) "e11") "a transaction's eval joins")
      (is (= (:tree (ctx/build (:db-after rep) (aid (:db-after rep)) :b)) @ctx/ctx))
      (swap! seon.agent/state assoc :db db)
      (is (= (:tree (ctx/build db (aid db) :b)) @ctx/ctx) "a move with no transaction is patched by the difference")
      (finally (reset! seon.agent/state nil)))))

(defn new-eval
  "The input of size n: the record of a session of n evals, built, and one more eval's
  transaction applied to its db: [c db-after tx-data branch]."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :seon.host/db [:sequential :any] :keyword]]}
  [n]
  (let [db (seeded n) c (ctx/build db (aid db) :b) rep (d/with db (eval-tx (inc n)))]
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
                "datom seek); the environment's t, "
                "branch and commit set. Nothing per eval already held, nothing derived. Six contracted calls: "
                "patch, join, node, own (the pull), key-of (the seek), environment")
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

(deftest a-change-names-its-eval
  ;; task seon.ctx/a-change-names-its-eval: who made each fact is a query on the tx-meta of its
  ;; transaction (seon.ctx/made-by): the eval whose code wrote it on the agent's branch, main for
  ;; one the agent was forked holding, main again for one a catch-up merged; the page's block
  ;; says so beside each key
  (let [w (fn [db branch more tx] (:db-after (d/with db tx (pdb/tx-meta branch more))))
        db (-> (ts/test-db)
               (w :main {} [{:seon.agent/key k :seon.agent/id :a1}])
               (w :main/a1 {:seon.tx/eval "e5"} [{:db/id [:seon.agent/key k] :seon.agent/screen ["(seon.screen/show)"]}])
               (w :main/a1 {:seon.tx/merged :main} [{:db/id [:seon.agent/key k] :seon.agent/answered-t 3}]))
        e (:db/id (d/entity db [:seon.agent/key k]))
        by (fn [a] (dissoc (ctx/made-by db e a) :t))]
    (is (= {:seon.tx/branch :main/a1 :seon.tx/eval "e5"} (by :seon.agent/screen)) "written by an eval: that eval, on the agent's branch")
    (is (= {:seon.tx/branch :main} (by :seon.agent/id)) "forked holding it: main's, by no eval")
    (is (= {:seon.tx/branch :main/a1 :seon.tx/merged :main} (by :seon.agent/answered-t)) "caught up: merged from main")
    (is (nil? (ctx/made-by db e :seon.eval/by)) "an attribute it does not have: nobody made it")
    (let [h (render/html (with-meta (:tree (ctx/build db (aid db) :main/a1)) {:seon.render/schema :seon.ctx/ctx}) {:db db})
          rows (into {} (for [x (tree-seq coll? seq h) :when (and (vector? x) (= :tr (first x)))] [(last (nth x 2)) (last (nth x 4))]))]
      (is (= {":seon.agent/screen" "e5" ":seon.agent/id" "from :main" ":seon.agent/answered-t" "merged from :main"}
             (select-keys rows [":seon.agent/screen" ":seon.agent/id" ":seon.agent/answered-t"]))
          "the page names who made each key"))))

(declare steps)

(deftest an-agent-adds-a-datum-and-its-render
  ;; what an agent evaluates to add to its record and draw it (the owner, 2026-10-25: "they can
  ;; easily update it and just add more data and render functions"): its key registered with a
  ;; render is an attribute at once; @ctx's eval is recorded as its basis, never a copy; the page
  ;; renders it from the branch, and a key whose render the branch defines is drawn by its agent as
  ;; a part of its page (seon.repl/parts; root, 2026-10-25: "nothing is stored")
  (let [form '[:vector {:seon.render/html seon.ctx-test/steps} :string]
        db (seeded 3)
        reg (my.schema/registration db :lab.ctx/steps form false)]
    (is (some #{{:db/ident :lab.ctx/steps :db/valueType :db.type/data :db/cardinality :db.cardinality/one}} (:tx reg))
        "a registered key holding a vector is stored as plain data, with no entity map listing it")
    (let [db (:db-after (d/with db (:tx reg)))
          db (:db-after (d/with db [{:seon.agent/key k :lab.ctx/steps ["read the plan" "write the test" "read the plan"]}]))
          tree (:tree (ctx/build db (aid db) :b))
          c (with-meta tree {:seon.render/schema :seon.ctx/ctx :seon.render/basis {:t (:t tree) :branch :b}})
          as-agent (fn [f] (try (reset! seon.agent/state {:db db}) (f) (finally (reset! seon.agent/state nil))))
          rec (as-agent #(seon.repl/record-details db "@ctx" {:handle 'result/e9 :value c} {} nil))
          drawn (try (reset! seon.agent/state {:db db :key k :branch :b}) (seon.repl/parts) (finally (reset! seon.agent/state nil)))
          steps (some #(when (= [:lab.ctx/steps] (:key %)) %) (:parts drawn))]
      (is (= ["read the plan" "write the test" "read the plan"] (:lab.ctx/steps c)) "the vector comes back in its order, duplicates kept")
      (is (= {:schema :seon.ctx/ctx :basis {:t (:t tree) :branch :b}} (select-keys rec [:schema :basis])) "the record names the value's basis")
      (is (not (contains? (:response rec) :value)) "and holds no copy of the record")
      (is (re-find #":lab.ctx/steps" (:printed rec)) "the model's text is the one printer's, as it read it")
      (is (= "<ol><li>read the plan</li><li>write the test</li><li>read the plan</li></ol>" (:html steps))
          "the agent draws its key, a part of its page, by the render its branch names, recording nothing")
      (testing "the page the model reads (seon.repl/page, the screen) is the page the user sees (seon.repl/parts), hiccup for html"
        (let [[parts page] (try (reset! seon.agent/state {:db db :key k :branch :b}) [(seon.repl/parts) (seon.repl/page)]
                                (finally (reset! seon.agent/state nil)))
              shown (remove (comp #{:eval :turn :message} first :key) (:parts parts))]
          (is (= (map :key shown) (keys page)) "every part but the conversation's, in the page's order")
          (is (= (map :html shown) (map seon.web.html/html (vals page))) "each part's hiccup is the html the page shows, byte for byte")
          (is (vector? (get page [:lab.ctx/steps])) "as hiccup, exactly as its render returned it")))
      (testing "the page rebuilds the value from the branch at its basis"
        (let [db (:db-after (d/with db [{:seon.eval/id "e9" :seon.eval/agent [:seon.agent/key k] :seon.eval/record rec}]))
              h (seon.web.html/html (seon.render.ui/evaluation (d/pull db '[*] [:seon.eval/id "e9"]) {:db db}))]
          (is (re-find (re-pattern (str "ctx · :b · t " (:t tree))) h) "the block of the record at t")
          (is (re-find #":lab.ctx/steps" h))))
      (testing "a render that throws in the agent is answered as its error, and the agent goes on"
        (let [db (:db-after (d/with db (:tx (my.schema/registration db :lab.ctx/boom '[:vector {:seon.render/html seon.ctx-test/boom} :any] false))))
              db (:db-after (d/with db [{:seon.agent/key k :lab.ctx/boom []}]))
              r (try (reset! seon.agent/state {:db db :key k :branch :b}) (seon.repl/parts) (finally (reset! seon.agent/state nil)))
              boom (some #(when (= [:lab.ctx/boom] (:key %)) %) (:parts r))]
          (is (re-find #"cannot draw this" (:html boom)) "the render's throw, as its error's render in its part's place")
          (is (some #(= [:lab.ctx/steps] (:key %)) (:parts r)) "and every other part drawn"))))))

(defn boom
  "A render that throws, as an agent's unmerged one may."
  {:malli/schema [:=> [:cat :any :map] :any]}
  [_ _] (throw (ex-info "cannot draw this" {})))

(defn steps
  "A render an agent writes for its datum: the steps, numbered."
  {:malli/schema [:=> [:cat [:vector :string] :map] :any]}
  [v _] [:ol (for [s v] [:li s])])

(defn- as-agent!
  "f with the agent's state holding db in test mode (transact! applies to the value, as in an agent's
  test fork) and the branch's schemas bound, @ctx built from it; answers [f's value, the db after]."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/fn] [:tuple :any :seon.host/db]]}
  [db f]
  (try (reset! seon.agent/state {:db db :key k :branch :b :mode :test})
       ;; under the branch's schemas, as the agent's REPL evaluates a form
       [(#'seon.repl/with-branch-schemas f) (:db @seon.agent/state)]
       (finally (reset! seon.agent/state nil))))

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
      (let [[_ db'] (step db #(swap! ctx/ctx update :evals dissoc "e3"))]
        (is (nil? (d/entity db' [:seon.eval/id "e3"])))
        (is (some? (d/entity db' [:seon.eval/id "e4"])))))
    (testing "a member's attribute, and a new member, written under its parent by the unit's ref"
      (let [[[_ same] db'] (step db #(swap! ctx/ctx (fn [c] (-> c (assoc-in [:runs "lab/ctx#1" :seon.run/status] :done)
                                                                (assoc-in [:runs "lab/ctx#1" :messages "m-new"]
                                                                          {:seon.message/id "m-new" :seon.message/from "agent" :seon.message/body "a note to myself"})))))]
        (is (= :done (:seon.run/status (d/entity db' [:seon.run/id "lab/ctx#1"]))))
        (is (= "lab/ctx#1" (:seon.run/id (:seon.message/to (d/entity db' [:seon.message/id "m-new"])))))
        (is same)
        (is (= "a note to myself" (get-in @ctx/ctx [:runs "lab/ctx#1" :messages "m-new" :seon.message/body])) "the swap!'s map gave way to the db's")))
    (testing "a cardinality-many attribute of a forward unit, member by member"
      (let [[[_ same] db'] (step db #(swap! ctx/ctx update-in [:runs "lab/ctx#1" :task :seon.task/proofs] conj 'app.x-test/t9))]
        (is (= #{'app.x-test/t0 'app.x-test/t9} (:seon.task/proofs (d/entity db' [:seon.task/id "lab/ctx"]))))
        (is same)))
    (testing "a new member with its own unit: a run and its turn, each naming its parent"
      (let [[[r same] db'] (step db #(swap! ctx/ctx assoc-in [:runs "lab/ctx#9"]
                                            {:seon.run/id "lab/ctx#9" :seon.run/status :running
                                             :turns {"lab/ctx#9/0" {:seon.turn/n 0 :seon.turn/reply "begin"}}}))]
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
  [db schema old new eid path acc], seon.ctx/edits's."
  {:malli/schema [:=> [:cat pos-int?] [:vector :any]]}
  [n]
  (let [db (seeded n) t (:tree (ctx/build db (aid db) :b))]
    [db :seon.db/agent-fact t (assoc t :seon.agent/status :busy) (:db/id t) [] {:tx [] :invalid [] :paths []}]))

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
     :sizes [100 1000 3000]}))

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
     :sizes [100 1000 3000]}))
